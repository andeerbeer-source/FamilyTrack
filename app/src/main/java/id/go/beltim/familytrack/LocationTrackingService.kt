
package id.go.beltim.familytrack

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        const val ACTION_START =
            "id.go.beltim.familytrack.action.START_TRACKING"

        const val ACTION_STOP =
            "id.go.beltim.familytrack.action.STOP_TRACKING"

        private const val API_BASE =
            "https://raperda.dprd.beltim.go.id/api/familytrack"

        private const val PREFS =
            "familytrack_secure_prefs"

        private const val KEY_DEVICE_TOKEN =
            "device_token"

        private const val KEY_LOCATION_QUEUE =
            "location_queue"

        private const val KEY_LAST_LAT =
            "last_latitude"

        private const val KEY_LAST_LON =
            "last_longitude"

        private const val KEY_LAST_ACCURACY =
            "last_accuracy"

        private const val KEY_LAST_TIME =
            "last_location_time"

        private const val KEY_LAST_SPEED =
            "last_speed"

        private const val KEY_LAST_BEARING =
            "last_bearing"

        private const val CHANNEL_ID =
            "familytrack_location"

        private const val NOTIFICATION_ID = 7401

        // Interval pembaruan GPS.
        private const val UPDATE_INTERVAL_MS = 30_000L

        // Percobaan pengiriman ulang.
        private const val RETRY_INTERVAL_MS = 60_000L

        // Lokasi cache maksimal 2 menit.
        private const val MAX_CACHED_LOCATION_AGE = 120_000L

        // Maksimal antrean GPS.
        private const val QUEUE_LIMIT = 500

        // Batas percobaan timeout HTTP.
        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 20_000
    }

    private lateinit var locationManager: LocationManager

    private lateinit var connectivityManager: ConnectivityManager

    private val retryHandler = Handler(Looper.getMainLooper())

    private val queueLock = Any()

    @Volatile
    private var sending = false

    @Volatile
    private var trackingStarted = false

    private var networkCallback:
        ConnectivityManager.NetworkCallback? = null

    private var locationUpdatesRegistered = false

    // --------------------------------------------------
    // RETRY OTOMATIS
    // --------------------------------------------------

    private val retryRunnable = object : Runnable {

        override fun run() {

            if (!trackingStarted) return

            flushQueue()

            retryHandler.postDelayed(
                this,
                RETRY_INTERVAL_MS
            )
        }
    }

    private fun startRetryLoop() {

        retryHandler.removeCallbacks(retryRunnable)

        trackingStarted = true

        retryHandler.postDelayed(
            retryRunnable,
            RETRY_INTERVAL_MS
        )
    }

    private fun stopRetryLoop() {

        trackingStarted = false

        retryHandler.removeCallbacks(retryRunnable)
    }

    // --------------------------------------------------
    // SERVICE LIFECYCLE
    // --------------------------------------------------

    override fun onCreate() {

        super.onCreate()

        locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()

        registerNetworkCallback()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

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

            // Kirim antrean lama.
            flushQueue()

            // Mulai GPS.
            beginLocationUpdates()

        } catch (e: Exception) {

            updateNotification(
                "Gagal mengaktifkan pelacakan: ${e.message}"
            )

            stopSelf()

            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // --------------------------------------------------
    // FOREGROUND SERVICE
    // --------------------------------------------------

    private fun startAsForeground() {

        val notification = buildNotification(
            "Menyiapkan pelacakan lokasi..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun buildNotification(
        message: String
    ): Notification {

        val openApp = Intent(
            this,
            MainActivity::class.java
        )

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE
                    else 0
                )
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_menu_mylocation
            )
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

                description =
                    "Status layanan pelacakan lokasi FamilyTrack."

            }

            val manager =
                getSystemService(NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        message: String
    ) {

        val manager =
            getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(message)
        )
    }

    // --------------------------------------------------
    // PERMISSION
    // --------------------------------------------------

    private fun hasLocationPermission(): Boolean {

        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    // --------------------------------------------------
    // NETWORK CALLBACK
    // --------------------------------------------------

    private fun registerNetworkCallback() {

        if (networkCallback != null) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        val callback =
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {

                    if (!trackingStarted) return

                    updateNotification(
                        "Internet kembali tersedia. Mengirim lokasi tertunda..."
                    )

                    flushQueue()
                }

                override fun onLost(network: Network) {

                    if (!trackingStarted) return

                    updateNotification(
                        "Internet terputus. Data GPS tetap disimpan."
                    )
                }
            }

        try {

            connectivityManager.registerDefaultNetworkCallback(
                callback
            )

            networkCallback = callback

        } catch (_: Exception) {

            updateNotification(
                "Pemantauan jaringan tidak tersedia. Percobaan ulang tetap berjalan."
            )
        }
    }

    private fun unregisterNetworkCallback() {

        networkCallback?.let {

            try {

                connectivityManager.unregisterNetworkCallback(
                    it
                )

            } catch (_: Exception) {
            }
        }

        networkCallback = null
    }

    // --------------------------------------------------
    // GPS LOCATION
    // --------------------------------------------------

    private fun beginLocationUpdates() {

        if (!hasLocationPermission()) {

            updateNotification(
                "Izin lokasi tidak tersedia."
            )

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

                    if (
                        locationManager.isProviderEnabled(
                            provider
                        )
                    ) {

                        locationManager.requestLocationUpdates(
                            provider,
                            UPDATE_INTERVAL_MS,
                            0f,
                            this,
                            Looper.getMainLooper()
                        )

                        requested = true
                    }

                } catch (_: IllegalArgumentException) {
                }
            }

            locationUpdatesRegistered = requested

            if (!requested) {

                updateNotification(
                    "Layanan aktif, tetapi GPS belum tersedia. Periksa pengaturan lokasi."
                )

                return
            }

            updateNotification(
                "Pelacakan aktif. Menunggu pembaruan GPS."
            )

            // Gunakan lokasi cache jika masih baru.
            val last = getRecentLastKnownLocation()

            if (last != null) {

                saveLocation(last)

                enqueueLocation(last)

                flushQueue()
            }

        } catch (_: SecurityException) {

            updateNotification(
                "Izin lokasi tidak tersedia. Buka FamilyTrack."
            )

            stopTracking()
        }
    }

    private fun getRecentLastKnownLocation(): Location? {

        if (!hasLocationPermission()) return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )

        var best: Location? = null

        for (provider in providers) {

            val location = try {

                locationManager.getLastKnownLocation(
                    provider
                )

            } catch (_: Exception) {
                null
            }

            if (location != null) {

                val age =
                    System.currentTimeMillis() - location.time

                if (
                    age >= 0 &&
                    age <= MAX_CACHED_LOCATION_AGE
                ) {

                    if (
                        best == null ||
                        location.accuracy < best!!.accuracy
                    ) {

                        best = location
                    }
                }
            }
        }

        return best
    }

    override fun onLocationChanged(
        location: Location
    ) {

        if (!trackingStarted) return

        // Simpan koordinat terbaru.
        saveLocation(location)

        // Masukkan ke antrean permanen.
        enqueueLocation(location)

        // Kirim ke server.
        flushQueue()

        updateNotification(
            "Lokasi GPS diperbarui. Mengirim ke server."
        )
    }

    // --------------------------------------------------
    // SIMPAN POSISI TERAKHIR
    // --------------------------------------------------

    private fun saveLocation(
        location: Location
    ) {

        getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putLong(
                KEY_LAST_LAT,
                java.lang.Double.doubleToRawLongBits(
                    location.latitude
                )
            )
            .putLong(
                KEY_LAST_LON,
                java.lang.Double.doubleToRawLongBits(
                    location.longitude
                )
            )
            .putFloat(
                KEY_LAST_ACCURACY,
                if (location.hasAccuracy())
                    location.accuracy
                else -1f
            )
            .putLong(
                KEY_LAST_TIME,
                location.time
            )
            .putFloat(
                KEY_LAST_SPEED,
                if (location.hasSpeed())
                    location.speed
                else -1f
            )
            .putFloat(
                KEY_LAST_BEARING,
                if (location.hasBearing())
                    location.bearing
                else -1f
            )
            .apply()
    }

    // --------------------------------------------------
    // GPS JSON
    // --------------------------------------------------

    private fun locationToJson(
        location: Location
    ): JSONObject {

        return JSONObject().apply {

            put(
                "latitude",
                location.latitude
            )

            put(
                "longitude",
                location.longitude
            )

            if (location.hasAccuracy()) {

                put(
                    "accuracy",
                    location.accuracy
                )
            }

            if (location.hasSpeed()) {

                put(
                    "speed",
                    location.speed
                )
            }

            if (location.hasBearing()) {

                put(
                    "heading",
                    location.bearing
                )
            }

            put(
                "captured_at",
                formatUtc(location.time)
            )
        }
    }

    private fun formatUtc(
        timestamp: Long
    ): String {

        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        )

        formatter.timeZone =
            TimeZone.getTimeZone("UTC")

        return formatter.format(
            Date(timestamp)
        )
    }

    // --------------------------------------------------
    // QUEUE STORAGE
    // --------------------------------------------------

    private fun enqueueLocation(
        location: Location
    ) {

        synchronized(queueLock) {

            val queue = readQueue()

            queue.put(
                locationToJson(location)
            )

            // Batasi penyimpanan maksimal 500 titik.
            while (queue.length() > QUEUE_LIMIT) {

                queue.remove(0)
            }

            saveQueue(queue)
        }
    }

    private fun readQueue(): JSONArray {

        val prefs = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )

        val raw = prefs.getString(
            KEY_LOCATION_QUEUE,
            "[]"
        ) ?: "[]"

        return try {

            JSONArray(raw)

        } catch (_: Exception) {

            JSONArray()
        }
    }

    private fun saveQueue(
        queue: JSONArray
    ) {

        getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putString(
                KEY_LOCATION_QUEUE,
                queue.toString()
            )
            .commit()
    }

    // --------------------------------------------------
    // UPLOAD QUEUE
    // --------------------------------------------------

    private fun flushQueue() {

        synchronized(queueLock) {

            if (!trackingStarted) return

            if (sending) return

            if (readQueue().length() == 0) return

            sending = true
        }

        thread(
            name = "FamilyTrackUpload"
        ) {

            try {

                while (trackingStarted) {

                    val item = synchronized(queueLock) {

                        val queue = readQueue()

                        if (queue.length() == 0) {

                            null

                        } else {

                            queue.getJSONObject(0)
                        }
                    } ?: break

                    val result = postLocation(item)

                    if (!result.first) {

                        updateNotification(
                            result.second
                        )

                        // Pertahankan data jika gagal.
                        break
                    }

                    // Hapus hanya setelah server mengonfirmasi sukses.
                    synchronized(queueLock) {

                        val queue = readQueue()

                        if (queue.length() > 0) {

                            queue.remove(0)

                            saveQueue(queue)
                        }
                    }
                }

                val remaining = synchronized(queueLock) {

                    readQueue().length()
                }

                if (remaining == 0) {

                    if (trackingStarted) {

                        updateNotification(
                            "Pelacakan aktif. Lokasi berhasil dikirim ke server."
                        )
                    }

                } else {

                    if (trackingStarted) {

                        updateNotification(
                            "$remaining lokasi menunggu pengiriman. Akan dicoba kembali."
                        )
                    }
                }

            } finally {

                synchronized(queueLock) {

                    sending = false
                }
            }
        }
    }

    // --------------------------------------------------
    // HTTP API
    // --------------------------------------------------

    private fun postLocation(
        payload: JSONObject
    ): Pair<Boolean, String> {

        val token = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
            .getString(
                KEY_DEVICE_TOKEN,
                ""
            )
            .orEmpty()

        if (token.isBlank()) {

            return Pair(
                false,
                "Token perangkat tidak ditemukan. Buka FamilyTrack."
            )
        }

        var connection: HttpURLConnection? = null

        return try {

            connection = URL(
                "$API_BASE/location"
            ).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"

            connection.connectTimeout =
                CONNECT_TIMEOUT

            connection.readTimeout =
                READ_TIMEOUT

            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )

            connection.outputStream.use {

                it.write(
                    payload.toString().toByteArray(
                        Charsets.UTF_8
                    )
                )
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {

                connection.inputStream

            } else {

                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {

                it.readText()

            }.orEmpty()

            val response = try {

                JSONObject(
                    body.ifBlank { "{}" }
                )

            } catch (_: Exception) {

                JSONObject()
            }

            if (
                code in 200..299 &&
                response.optBoolean("success")
            ) {

                Pair(
                    true,
                    "Lokasi berhasil dikirim."
                )

            } else if (
                code == 401 ||
                code == 403
            ) {

                Pair(
                    false,
                    "Token perangkat ditolak server (HTTP $code). Periksa pendaftaran."
                )

            } else {

                Pair(
                    false,
                    "Pengiriman gagal (HTTP $code). Data tetap disimpan."
                )
            }

        } catch (_: Exception) {

            Pair(
                false,
                "Internet/server terputus. Data GPS tetap disimpan."
            )

        } finally {

            connection?.disconnect()
        }
    }

    // --------------------------------------------------
    // STOP SERVICE
    // --------------------------------------------------

    private fun stopTracking() {

        stopRetryLoop()

        try {

            locationManager.removeUpdates(this)

        } catch (_: Exception) {
        }

        locationUpdatesRegistered = false

        unregisterNetworkCallback()

        stopForeground(STOP_FOREGROUND_REMOVE)

        stopSelf()
    }

    // --------------------------------------------------
    // TASK REMOVED
    // --------------------------------------------------

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        // Service tetap berjalan jika Android mengizinkan.
        // Tidak melakukan restart tersembunyi.
        super.onTaskRemoved(rootIntent)
    }

    // --------------------------------------------------
    // DESTROY
    // --------------------------------------------------

    override fun onDestroy() {

        stopRetryLoop()

        try {

            locationManager.removeUpdates(this)

        } catch (_: Exception) {
        }

        locationUpdatesRegistered = false

        unregisterNetworkCallback()

        super.onDestroy()
    }
}
