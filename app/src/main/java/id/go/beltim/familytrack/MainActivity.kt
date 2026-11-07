package id.go.beltim.familytrack

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val API_BASE = "https://raperda.dprd.beltim.go.id/api/familytrack"
        private const val PREFS = "familytrack_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }

    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private lateinit var inviteInput: EditText
    private var latestLocation: Location? = null

    private val deviceToken: String
        get() = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEVICE_TOKEN, "").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val registerButton = Button(this).apply {
            text = "Daftarkan Perangkat"
            setOnClickListener { registerDevice() }
        }
        statusText = TextView(this).apply {
            text = if (deviceToken.isNotBlank()) "Status: perangkat sudah terdaftar." else "Status: belum terdaftar. Masukkan token undangan."
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }
        locationText = TextView(this).apply {
            text = "Lokasi belum dibaca"
            textSize = 15f
            setPadding(0, 0, 0, 24)
        }
        val getLocationButton = Button(this).apply {
            text = "Ambil Lokasi"
            setOnClickListener { requestOrReadLocation() }
        }
        val sendButton = Button(this).apply {
            text = "Kirim Lokasi ke Server"
            setOnClickListener { sendLocation() }
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(inviteInput)
        root.addView(registerButton)
        root.addView(statusText)
        root.addView(locationText)
        root.addView(getLocationButton)
        root.addView(sendButton)
        setContentView(root)
    }

    private fun registerDevice() {
        val invite = inviteInput.text.toString().trim()
        if (invite.isBlank()) {
            inviteInput.error = "Token undangan wajib diisi"
            return
        }
        statusText.text = "Mendaftarkan perangkat..."
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
                    val token = response.optString("device_token")
                    if (token.isBlank()) {
                        runOnUiThread { statusText.text = "Registrasi gagal: server tidak mengirim device_token." }
                        return@thread
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DEVICE_TOKEN, token).apply()
                    val member = response.optJSONObject("member")?.optString("nama").orEmpty()
                    val suffix = if (member.isNotBlank()) " untuk $member" else ""
                    runOnUiThread {
                        statusText.text = "Perangkat berhasil didaftarkan$suffix. Siap mengirim lokasi."
                        Toast.makeText(this, "Registrasi berhasil.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val message = response.optString("message").ifBlank { "HTTP $code" }
                    runOnUiThread { statusText.text = "Registrasi gagal: $message (HTTP $code)" }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Gagal registrasi: ${e.message ?: "koneksi bermasalah"}" }
            }
        }
    }

    private fun requestOrReadLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        readLocation()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            readLocation()
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            statusText.text = "Izin lokasi ditolak."
        }
    }

    private fun readLocation() {
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true)
            var best: Location? = null
            for (provider in providers) {
                val candidate = try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
                if (candidate != null && (best == null || candidate.accuracy < best!!.accuracy)) best = candidate
            }
            if (best == null) {
                statusText.text = "Lokasi belum tersedia. Aktifkan GPS dan coba lagi."
                return
            }
            latestLocation = best
            locationText.text = "Latitude: ${best.latitude}\nLongitude: ${best.longitude}\nAkurasi: ${best.accuracy} meter"
            statusText.text = "Lokasi berhasil dibaca."
        } catch (e: Exception) {
            statusText.text = "Gagal membaca lokasi: ${e.message ?: "kesalahan tidak diketahui"}"
        }
    }

    private fun sendLocation() {
        val loc = latestLocation
        if (loc == null) {
            Toast.makeText(this, "Ambil lokasi terlebih dahulu.", Toast.LENGTH_SHORT).show()
            return
        }
        if (deviceToken.isBlank()) {
            statusText.text = "Perangkat belum terdaftar. Masukkan token undangan dan daftarkan perangkat terlebih dahulu."
            return
        }
        statusText.text = "Mengirim lokasi..."
        thread {
            try {
                val payload = JSONObject().apply {
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                    put("accuracy", loc.accuracy)
                    if (loc.hasSpeed()) put("speed", loc.speed)
                    if (loc.hasBearing()) put("heading", loc.bearing)
                }
                val (code, body) = postJson("/location", payload, deviceToken)
                val response = JSONObject(body.ifBlank { "{}" })
                val message = response.optString("message").ifBlank { "HTTP $code" }
                runOnUiThread {
                    statusText.text = if (code in 200..299 && response.optBoolean("success")) {
                        "Lokasi berhasil dikirim ke server."
                    } else {
                        "Pengiriman gagal: $message (HTTP $code)"
                    }
                    if (body.isNotBlank()) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Gagal mengirim: ${e.message ?: "koneksi bermasalah"}" }
            }
        }
    }

    private fun postJson(path: String, payload: JSONObject, bearerToken: String?): Pair<Int, String> {
        val connection = URL(API_BASE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Pair(code, body)
        } finally {
            connection.disconnect()
        }
    }
}
