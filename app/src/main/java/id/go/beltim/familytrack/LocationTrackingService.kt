package id.go.beltim.familytrack

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class LocationTrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "id.go.beltim.familytrack.action.START_TRACKING"
        const val ACTION_STOP = "id.go.beltim.familytrack.action.STOP_TRACKING"

        private const val API_BASE = "https://raperda.dprd.beltim.go.id/api/familytrack"
        private const val PREFS = "familytrack_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_LOCATION_QUEUE = "location_queue"

        private const val KEY_LAST_LAT = "last_latitude"
        private const val KEY_LAST_LON = "last_longitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_TIME = "last_location_time"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_LAST_BEARING = "last_bearing"

        private const val CHANNEL_ID = "familytrack_location"
        private const val NOTIFICATION_ID = 7401

        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val RETRY_INTERVAL_MS = 30_000L
        private const val QUEUE_LIMIT = 500

        // Batas toleransi akurasi GPS (dalam meter). Tolak jika meleset > 50m
        private const val MAX_ACCURACY_METERS = 50f
        // Waktu maksimal untuk menunggu GPS murni sebelum menerima Network fallback
        private const val GPS_FALLBACK_TIMEOUT_MS = 45_000L

        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 20_000
    }

    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var workerThread: HandlerThread? = null
    private lateinit var workerHandler: Handler

    private val queueLock = Any()

    @Volatile
    private var sending = false

    @Volatile
    private var trackingStarted = false

    @Volatile
    private var locationUpdatesRegistered = false

    @Volatile
    private var lastGpsFixTime = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // --------------------------------------------------
    // LOOP PEMANTAUAN & RECONNECT OTOMATIS
    // --------------------------------------------------

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!trackingStarted) return

            // 1. Pastikan listener GPS tetap aktif (jika tadi GPS sempat dimatikan user)
            if (!locationUpdatesRegistered) {
                beginLocationUpdates()
            }

            // 2. Kirim ulang antrean jika internet tersedia
            if (isInternetValidated()) {
                flushQueue()
            }

            workerHandler.postDelayed(this, RETRY_INTERVAL_MS)
        }
    }

    private fun startRetryLoop() {
        workerHandler.removeCallbacks(retryRunnable)
        trackingStarted = true
        workerHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
    }

    private fun stopRetryLoop() {
        trackingStarted = false
        if (::workerHandler.isInitialized) {
            workerHandler.removeCallbacks(retryRunnable)
        }
    }

    // --------------------------------------------------
    // SERVICE LIFECYCLE
    // --------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        workerThread = HandlerThread("FamilyTrackWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        acquireWakeLock()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
            startRetryLoop()
            registerNetworkCallback()
            beginLocationUpdates()
            flushQueue()
        } catch (e: Exception) {
            updateNotification("Gagal mengaktifkan pelacakan: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FamilyTrack::LocationWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // Maksimal 12 jam per sesi
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------
    // FOREGROUND SERVICE & NOTIFIKASI
    // --------------------------------------------------

    private fun startAsForeground() {
        val notification = buildNotification("Menyiapkan pelacakan lokasi...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("FamilyTrack aktif")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pelacakan lokasi FamilyTrack",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status layanan pelacakan lokasi FamilyTrack."
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    // --------------------------------------------------
    // PERMISSION & NETWORK VALIDATION
    // --------------------------------------------------

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun isInternetValidated(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        @Suppress("DEPRECATION")
        return connectivityManager.activeNetworkInfo?.isConnected == true
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!trackingStarted) return
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                if (hasInternet) {
                    workerHandler.postDelayed({
                        flushQueue()
                    }, 1500L) // Jeda 1.5 detik agar DNS & routing siap
                }
            }

            override fun onLost(network: Network) {
                if (!trackingStarted) return
                updateNotification("Internet terputus. Data GPS disimpan lokal.")
            }
        }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    // --------------------------------------------------
    // GPS LOCATION & FILTER AKURASI
    // --------------------------------------------------

    private fun beginLocationUpdates() {
        if (!hasLocationPermission()) {
            updateNotification("Izin lokasi tidak tersedia.")
            stopTracking()
            return
        }

        try {
            locationManager.removeUpdates(this)
            locationUpdatesRegistered = false

            var requested = false
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )

            for (provider in providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(
                            provider,
                            UPDATE_INTERVAL_MS,
                            0f,
                            this,
                            workerThread!!.looper
                        )
                        requested = true
                    }
                } catch (_: Exception) {
                }
            }

            locationUpdatesRegistered = requested

            if (!requested) {
                updateNotification("GPS tidak aktif. Aktifkan lokasi di pengaturan.")
                return
            }

            updateNotification("Pelacakan aktif. Mencari sinyal satelit GPS...")
        } catch (_: SecurityException) {
            updateNotification("Izin lokasi dicabut. Buka FamilyTrack.")
            stopTracking()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!trackingStarted) return

        val now = System.currentTimeMillis()

        // 1. Tolak data lokasi usang (> 60 detik)
        if (now - location.time > 60_000L) return

        // 2. Prioritaskan GPS murni; tolak Network jika GPS baru saja aktif (< 45 detik lalu)
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixTime = now
        } else if (now - lastGpsFixTime < GPS_FALLBACK_TIMEOUT_MS) {
            return
        }

        // 3. Filter akurasi: tolak titik yang meleset lebih dari 50 meter
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) {
            return
        }

        saveLocation(location)
        enqueueLocation(location)
        flushQueue()
    }

    override fun onProviderEnabled(provider: String) {
        if (trackingStarted) {
            beginLocationUpdates()
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (trackingStarted && provider == LocationManager.GPS_PROVIDER) {
            updateNotification("Sinyal GPS dimatikan. Aktifkan kembali GPS.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // --------------------------------------------------
    // PENYIMPANAN & ANTREAN
    // --------------------------------------------------

    private fun saveLocation(location: Location) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(location.longitude))
            .putFloat(KEY_LAST_ACCURACY, if (location.hasAccuracy()) location.accuracy else -1f)
            .putLong(KEY_LAST_TIME, location.time)
            .putFloat(KEY_LAST_SPEED, if (location.hasSpeed()) location.speed else -1f)
            .putFloat(KEY_LAST_BEARING, if (location.hasBearing()) location.bearing else -1f)
            .apply()
    }

    private fun locationToJson(location: Location): JSONObject {
        return JSONObject().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            if (location.hasAccuracy()) put("accuracy", location.accuracy)
            if (location.hasSpeed()) put("speed", location.speed)
            if (location.hasBearing()) put("heading", location.bearing)
            put("provider", location.provider ?: "unknown")
            put("captured_at", formatUtc(location.time))
        }
    }

    private fun formatUtc(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestamp))
    }

    private fun enqueueLocation(location: Location) {
        synchronized(queueLock) {
            val queue = readQueue()
            queue.put(locationToJson(location))
            while (queue.length() > QUEUE_LIMIT) {
                queue.remove(0)
            }
            saveQueue(queue)
        }
    }

    private fun readQueue(): JSONArray {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_LOCATION_QUEUE, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveQueue(queue: JSONArray) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCATION_QUEUE, queue.toString())
            .commit()
    }

    // --------------------------------------------------
    // UPLOAD QUEUE
    // --------------------------------------------------

    private fun flushQueue() {
        synchronized(queueLock) {
            if (!trackingStarted || sending) return
            if (readQueue().length() == 0) return
            sending = true
        }

        thread(name = "FamilyTrackUpload") {
            try {
                while (trackingStarted) {
                    val item = synchronized(queueLock) {
                        val queue = readQueue()
                        if (queue.length() == 0) null else queue.getJSONObject(0)
                    } ?: break

                    val result = postLocation(item)

                    if (result.shouldDropItem) {
                        // Hapus item jika sukses (200) atau payload ditolak permanen (400/422)
                        synchronized(queueLock) {
                            val queue = readQueue()
                            if (queue.length() > 0) {
                                queue.remove(0)
                                saveQueue(queue)
                            }
                        }
                    } else {
                        // Pertahankan item jika jaringan putus atau token bermasalah (401/403)
                        updateNotification(result.message)
                        break
                    }
                }

                val remaining = synchronized(queueLock) { readQueue().length() }
                if (trackingStarted) {
                    if (remaining == 0) {
                        updateNotification("Pelacakan aktif. Lokasi terkirim ke server.")
                    } else {
                        updateNotification("$remaining lokasi tertunda. Menunggu koneksi...")
                    }
                }
            } finally {
                synchronized(queueLock) { sending = false }
            }
        }
    }

    private data class PostResult(
        val success: Boolean,
        val shouldDropItem: Boolean,
        val message: String
    )

    private fun postLocation(payload: JSONObject): PostResult {
        // Bersihkan spasi/newline yang sering menempel saat copy-paste token
        val token = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, "")
            ?.trim()
            .orEmpty()

        if (token.isBlank()) {
            return PostResult(false, false, "Token perangkat kosong. Silakan login ulang.")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$API_BASE/location").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                setRequestProperty("Authorization", "Bearer $token")
            }

            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = try {
                JSONObject(body.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }

            when {
                code in 200..299 && response.optBoolean("success", true) -> {
                    PostResult(true, true, "Lokasi berhasil dikirim.")
                }
                code == 401 || code == 403 -> {
                    PostResult(false, false, "Token tidak sesuai/kedaluwarsa (HTTP $code). Buka aplikasi.")
                }
                code == 400 || code == 422 -> {
                    // Data korup/tidak valid: buang dari antrean agar tidak menyumbat data baru
                    PostResult(false, true, "Format lokasi ditolak server (HTTP $code).")
                }
                else -> {
                    PostResult(false, false, "Server sibuk (HTTP $code). Akan dicoba ulang.")
                }
            }
        } catch (_: Exception) {
            PostResult(false, false, "Koneksi terputus. Menunggu jaringan kembali...")
        } finally {
            connection?.disconnect()
        }
    }

    // --------------------------------------------------
    // STOP & CLEANUP
    // --------------------------------------------------

    private fun stopTracking() {
        stopRetryLoop()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        locationUpdatesRegistered = false
        unregisterNetworkCallback()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRetryLoop()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        locationUpdatesRegistered = false
        unregisterNetworkCallback()
        releaseWakeLock()
        workerThread?.quitSafely()
        super.onDestroy()
    }
}
