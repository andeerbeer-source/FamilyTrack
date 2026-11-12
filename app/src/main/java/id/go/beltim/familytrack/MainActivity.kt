package id.go.beltim.familytrack

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
        private const val BACKGROUND_LOCATION_REQUEST = 1003

        private const val API_BASE = "https://raperda.dprd.beltim.go.id/api/familytrack"
        private const val PREFS = "familytrack_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }

    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var inviteInput: EditText
    private lateinit var registerButton: Button

    private var latestLocation: Location? = null
    private var pendingStartTracking = false
    private var activeLocationListener: LocationListener? = null

    private var deviceToken: String
        get() = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, "")
            ?.trim()
            .orEmpty()
        set(value) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE_TOKEN, value.trim())
                .commit() // Wajib commit() agar langsung terbaca oleh Service
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        val title = TextView(this).apply {
            text = "FamilyTrack"
            textSize = 30f
            setTextColor(android.graphics.Color.rgb(21, 101, 192))
        }

        val subtitle = TextView(this).apply {
            text = "Pelacak lokasi keluarga"
            textSize = 16f
        }

        inviteInput = EditText(this).apply {
            hint = "Masukkan token undangan"
            setSingleLine(true)
        }

        registerButton = Button(this).apply {
            text = "Daftarkan Perangkat"
            setOnClickListener { registerDevice() }
        }

        val resetTokenButton = Button(this).apply {
            text = "Reset / Ganti Token Perangkat"
            setOnClickListener { confirmResetToken() }
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 24, 0, 16)
        }

        locationText = TextView(this).apply {
            text = "Lokasi belum dibaca"
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }

        val getLocationButton = Button(this).apply {
            text = "Ambil Lokasi GPS Akurat"
            setOnClickListener { requestOrReadLocation() }
        }

        val sendButton = Button(this).apply {
            text = "Kirim Lokasi ke Server"
            setOnClickListener { sendLocation() }
        }

        val startTrackingButton = Button(this).apply {
            text = "MULAI PELACAKAN OTOMATIS"
            setOnClickListener { requestStartTracking() }
        }

        val stopTrackingButton = Button(this).apply {
            text = "HENTIKAN PELACAKAN"
            setOnClickListener { stopTracking() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(inviteInput)
        root.addView(registerButton)
        root.addView(resetTokenButton)
        root.addView(statusText)
        root.addView(locationText)
        root.addView(getLocationButton)
        root.addView(sendButton)
        root.addView(startTrackingButton)
        root.addView(stopTrackingButton)

        scrollView.addView(root)
        setContentView(scrollView)

        refreshStatus()
    }

    override fun onDestroy() {
        cancelActiveSingleLocationUpdate()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val registered = deviceToken.isNotBlank()
        inviteInput.isEnabled = !registered
        registerButton.isEnabled = !registered

        statusText.text = if (registered) {
            "Status: perangkat sudah terdaftar.\nToken perangkat tersimpan di HP."
        } else {
            "Status: belum terdaftar. Masukkan token undangan."
        }
    }

    private fun confirmResetToken() {
        AlertDialog.Builder(this)
            .setTitle("Reset Token Perangkat?")
            .setMessage("Hapus token saat ini agar perangkat bisa didaftarkan ulang dengan token undangan baru?")
            .setPositiveButton("Ya, Reset") { _, _ ->
                stopTracking()
                deviceToken = ""
                inviteInput.text.clear()
                refreshStatus()
                Toast.makeText(this, "Token dihapus. Silakan daftar ulang.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // --------------------------------------------------
    // REGISTRASI PERANGKAT
    // --------------------------------------------------

    private fun registerDevice() {
        val invite = inviteInput.text.toString().trim()
        if (invite.isBlank()) {
            inviteInput.error = "Token undangan wajib diisi"
            return
        }

        statusText.text = "Mendaftarkan perangkat..."
        registerButton.isEnabled = false

        thread {
            try {
                val payload = JSONObject().apply {
                    put("agree", "1")
                    put("device_name", Build.MODEL ?: "Android")
                }

                val path = "/register/" + Uri.encode(invite)
                val (code, body) = postJson(path, payload, null)
                val response = JSONObject(body.ifBlank { "{}" })

                if (code in 200..299 && response.optBoolean("success")) {
                    val token = response.optString("device_token").trim()

                    if (token.isBlank()) {
                        runOnUiThread {
                            registerButton.isEnabled = true
                            statusText.text = "Registrasi gagal: server tidak mengirim device_token."
                        }
                        return@thread
                    }

                    // Simpan secara sinkron sebelum UI diperbarui
                    deviceToken = token

                    val member = response.optJSONObject("member")?.optString("nama").orEmpty()
                    val suffix = if (member.isNotBlank()) " untuk $member" else ""

                    runOnUiThread {
                        refreshStatus()
                        statusText.text = "Perangkat berhasil didaftarkan$suffix.\nSiap mengirim lokasi."
                        Toast.makeText(this, "Registrasi berhasil.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val message = response.optString("message").ifBlank { "HTTP $code" }
                    runOnUiThread {
                        registerButton.isEnabled = true
                        statusText.text = "Registrasi gagal: $message (HTTP $code)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    registerButton.isEnabled = true
                    statusText.text = "Gagal registrasi: ${e.message ?: "koneksi bermasalah"}"
                }
            }
        }
    }

    // --------------------------------------------------
    // PEMBACAAN GPS AKTIF (BUKAN SEKADAR CACHE)
    // --------------------------------------------------

    private fun requestOrReadLocation() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        readFreshLocation()
    }

    @SuppressLint("MissingPermission")
    private fun readFreshLocation() {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            statusText.text = "GPS mati. Aktifkan Lokasi/GPS di pengaturan HP terlebih dahulu."
            return
        }

        statusText.text = "Mencari kunci satelit GPS akurat..."
        cancelActiveSingleLocationUpdate()

        // Gunakan getCurrentLocation pada Android 11+ (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }

            manager.getCurrentLocation(
                provider,
                CancellationSignal(),
                mainExecutor
            ) { location ->
                if (location != null) {
                    applyReadLocation(location)
                } else {
                    fallbackCachedLocation(manager)
                }
            }
        } else {
            // Untuk Android 10 ke bawah: minta update langsung dari GPS_PROVIDER
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.hasAccuracy() && location.accuracy > 50f) return
                    manager.removeUpdates(this)
                    activeLocationListener = null
                    applyReadLocation(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            activeLocationListener = listener
            val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }

            manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun cancelActiveSingleLocationUpdate() {
        activeLocationListener?.let {
            try {
                val manager = getSystemService(LOCATION_SERVICE) as LocationManager
                manager.removeUpdates(it)
            } catch (_: Exception) {
            }
        }
        activeLocationListener = null
    }

    @SuppressLint("MissingPermission")
    private fun fallbackCachedLocation(manager: LocationManager) {
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { manager.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .minByOrNull { it.accuracy }

        if (best != null) {
            applyReadLocation(best)
        } else {
            statusText.text = "Gagal mengunci sinyal GPS. Coba di area terbuka."
        }
    }

    private fun applyReadLocation(loc: Location) {
        latestLocation = loc
        locationText.text =
            "Latitude: ${loc.latitude}\n" +
            "Longitude: ${loc.longitude}\n" +
            "Akurasi: ${loc.accuracy} meter (${loc.provider})"
        statusText.text = "Lokasi GPS berhasil dibaca."
    }

    // --------------------------------------------------
    // KIRIM LOKASI MANUAL
    // --------------------------------------------------

    private fun sendLocation() {
        val loc = latestLocation
        if (loc == null) {
            Toast.makeText(this, "Ambil lokasi terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentToken = deviceToken
        if (currentToken.isBlank()) {
            statusText.text = "Perangkat belum terdaftar. Daftarkan menggunakan token undangan."
            return
        }

        statusText.text = "Mengirim lokasi..."

        thread {
            try {
                val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val payload = JSONObject().apply {
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                    put("accuracy", loc.accuracy)
                    if (loc.hasSpeed()) put("speed", loc.speed)
                    if (loc.hasBearing()) put("heading", loc.bearing)
                    put("provider", loc.provider ?: "unknown")
                    put("captured_at", formatter.format(Date(loc.time)))
                }

                val (code, body) = postJson("/location", payload, currentToken)
                val response = JSONObject(body.ifBlank { "{}" })
                val message = response.optString("message").ifBlank { "HTTP $code" }

                runOnUiThread {
                    if (code in 200..299 && response.optBoolean("success", true)) {
                        statusText.text = "Lokasi berhasil dikirim ke server."
                    } else if (code == 401 || code == 403) {
                        statusText.text = "Token ditolak server (HTTP $code). Klik 'Reset Token' dan daftar ulang."
                    } else {
                        statusText.text = "Pengiriman gagal: $message (HTTP $code)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Gagal mengirim: ${e.message ?: "koneksi bermasalah"}"
                }
            }
        }
    }

    // --------------------------------------------------
    // IZIN & MULAI PELACAKAN LATAR BELAKANG
    // --------------------------------------------------

    private fun requestStartTracking() {
        if (deviceToken.isBlank()) {
            statusText.text = "Daftarkan perangkat terlebih dahulu menggunakan token undangan."
            return
        }

        if (!hasLocationPermission()) {
            pendingStartTracking = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }

        checkBackgroundLocationThenStart()
    }

    private fun checkBackgroundLocationThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Izin Lokasi Sepanjang Waktu")
                .setMessage("Agar pelacakan dan reconnect tetap berjalan saat layar HP mati, pilih 'Izinkan sepanjang waktu' pada layar berikutnya.")
                .setPositiveButton("Lanjutkan") { _, _ ->
                    pendingStartTracking = true
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        BACKGROUND_LOCATION_REQUEST
                    )
                }
                .setNegativeButton("Lewati") { _, _ ->
                    requestNotificationPermissionThenStart()
                }
                .show()
            return
        }

        requestNotificationPermissionThenStart()
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartTracking = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }

        requestIgnoreBatteryOptimizationsIfNeeded()
        startLocationService()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }

        try {
            ContextCompat.startForegroundService(this, intent)
            statusText.text = "Pelacakan otomatis aktif.\nPastikan notifikasi FamilyTrack tampil."
            Toast.makeText(this, "Pelacakan otomatis dimulai.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            statusText.text = "Tidak dapat memulai pelacakan: ${e.message ?: "periksa izin lokasi"}"
        }
    }

    private fun stopTracking() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        try {
            startService(intent)
            statusText.text = "Pelacakan dihentikan."
        } catch (e: Exception) {
            statusText.text = "Gagal menghentikan layanan: ${e.message}"
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (hasLocationPermission()) {
                    if (pendingStartTracking) {
                        pendingStartTracking = false
                        checkBackgroundLocationThenStart()
                    } else {
                        readFreshLocation()
                    }
                } else {
                    pendingStartTracking = false
                    statusText.text = "Izin lokasi ditolak. Pelacakan tidak dapat berjalan."
                }
            }

            BACKGROUND_LOCATION_REQUEST -> {
                pendingStartTracking = false
                requestNotificationPermissionThenStart()
            }

            NOTIFICATION_PERMISSION_REQUEST -> {
                pendingStartTracking = false
                requestIgnoreBatteryOptimizationsIfNeeded()
                startLocationService()
            }
        }
    }

    private fun postJson(
        path: String,
        payload: JSONObject,
        bearerToken: String?
    ): Pair<Int, String> {
        val connection = URL(API_BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.useCaches = false

            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")

            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }

            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            return Pair(code, body)
        } finally {
            connection.disconnect()
        }
    }
}
