
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

        private const val PREFS = "familytrack_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"

        private const val KEY_LAST_LAT = "last_latitude"
        private const val KEY_LAST_LON = "last_longitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_TIME = "last_location_time"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_LAST_BEARING = "last_bearing"

        // Antrean GPS yang tersimpan secara permanen.
        private const val KEY_LOCATION_QUEUE = "location_queue"

        private const val CHANNEL_ID = "familytrack_location"
        private const val NOTIFICATION_ID = 7401

        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val QUEUE_LIMIT = 500
    }

    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager

    @Volatile
    private var sending = false

    private val queueLock = Any()

    private var networkCallback:
        ConnectivityManager.NetworkCallback? = null

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
            updateNotification(
                "Izin lokasi tidak tersedia. Buka FamilyTrack untuk mengaktifkan kembali."
            )

            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        // Kirim kembali antrean yang tersimpan sebelum service berhenti.
        flushQueue()

        beginLocationUpdates()

        return START_STICKY
    }

    // --------------------------------------------------
    // FOREGROUND SERVICE
    // --------------------------------------------------

    private fun startAsForeground() {
        val notification =
            buildNotification("Menyiapkan pelacakan lokasi...")

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

    private fun buildNotification(message: String): Notification {

        val openApp = Intent(this, MainActivity::class.java)

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
                description =
                    "Status layanan pelacakan lokasi FamilyTrack."
            }

            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(message: String) {

        val manager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

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
    // NETWORK RECONNECT
    // --------------------------------------------------

    private fun registerNetworkCallback() {

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            networkCallback != null
        ) {
            return
        }

        val callback =
            object : ConnectivityManager.NetworkCallback() {

                override fun onAvailable(network: Network) {

                    updateNotification(
                        "Internet kembali tersedia. Mengirim antrean lokasi..."
                    )

                    // Kirim antrean, bukan menganggap lokasi lama sebagai posisi baru.
                    flushQueue()
                }

                override fun onLost(network: Network) {

                    updateNotification(
                        "Koneksi internet terputus. Data GPS tetap disimpan."
                    )
                }
            }

        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            updateNotification(
                "Pemantauan koneksi tidak tersedia. Menunggu pembaruan GPS."
            )
        }
    }

    // --------------------------------------------------
    // GPS
    // --------------------------------------------------

    private fun beginLocationUpdates() {

        if (!hasLocationPermission()) {
            updateNotification("Izin lokasi tidak tersedia.")
            stopSelf()
            return
        }

        try {

            // Hindari mendaftarkan listener berulang kali.
            locationManager.removeUpdates(this)

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
                            Looper.getMainLooper()
                        )

                        requested = true
                    }

                } catch (_: IllegalArgumentException) {
                    // Provider tidak tersedia.
                }
            }

            if (!requested) {

                updateNotification(
                    "Layanan aktif, tetapi GPS belum tersedia. Periksa pengaturan lokasi."
                )

                return
            }

            updateNotification(
                "Pelacakan aktif. Menunggu pembaruan lokasi."
            )

            // Lokasi terakhir hanya digunakan sebagai titik awal.
            // Waktu pengambilan aslinya tetap dipertahankan.
            val last = try {

                locationManager.getLastKnownLocation(
                    LocationManager.GPS_PROVIDER
                ) ?: locationManager.getLastKnownLocation(
                    LocationManager.NETWORK_PROVIDER
                )

            } catch (_: SecurityException) {
                null
            }

            if (last != null) {
                enqueueLocation(last)
                flushQueue()
            }

        } catch (_: SecurityException) {

            updateNotification(
                "Izin lokasi tidak tersedia. Buka FamilyTrack."
            )

            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {

        // Simpan posisi terbaru.
        saveLocation(location)

        // Simpan ke antrean permanen.
        enqueueLocation(location)

        // Mulai pengiriman.
        flushQueue()
    }

    // --------------------------------------------------
    // PENYIMPANAN POSISI TERAKHIR
    // --------------------------------------------------

    private fun saveLocation(location: Location) {

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putLong(
                KEY_LAST_LAT,
                java.lang.Double.doubleToRawLongBits(location.latitude)
            )
            .putLong(
                KEY_LAST_LON,
                java.lang.Double.doubleToRawLongBits(location.longitude)
            )
            .putFloat(
                KEY_LAST_ACCURACY,
                if (location.hasAccuracy()) location.accuracy else -1f
            )
            .putLong(KEY_LAST_TIME, location.time)
            .putFloat(
                KEY_LAST_SPEED,
                if (location.hasSpeed()) location.speed else -1f
            )
            .putFloat(
                KEY_LAST_BEARING,
                if (location.hasBearing()) location.bearing else -1f
            )
            .apply()
    }

    // --------------------------------------------------
    // ANTREAN GPS PERMANEN
    // --------------------------------------------------

    private fun locationToJson(location: Location): JSONObject {

        return JSONObject().apply {

            put("latitude", location.latitude)
            put("longitude", location.longitude)

            if (location.hasAccuracy()) {
                put("accuracy", location.accuracy)
            }

            if (location.hasSpeed()) {
                put("speed", location.speed)
            }

            if (location.hasBearing()) {
                put("heading", location.bearing)
            }

            put("captured_at", formatUtc(location.time))
        }
    }

    private fun formatUtc(timestamp: Long): String {

        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        )

        formatter.timeZone = TimeZone.getTimeZone("UTC")

        return formatter.format(Date(timestamp))
    }

    private fun enqueueLocation(location: Location) {

        synchronized(queueLock) {

            val queue = readQueue()

            queue.put(locationToJson(location))

            // Batasi ukuran penyimpanan lokal.
            while (queue.length() > QUEUE_LIMIT) {
                queue.remove(0)
            }

            saveQueue(queue)
        }
    }

    private fun readQueue(): JSONArray {

        val prefs =
            getSharedPreferences(PREFS, MODE_PRIVATE)

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

    private fun saveQueue(queue: JSONArray) {

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(
                KEY_LOCATION_QUEUE,
                queue.toString()
            )
            .commit()
    }

    // --------------------------------------------------
    // PENGIRIMAN ANTREAN
    // --------------------------------------------------

    private fun flushQueue() {

        synchronized(queueLock) {

            if (sending) return

            if (readQueue().length() == 0) return

            sending = true
        }

        thread(name = "FamilyTrackUpload") {

            try {

                while (true) {

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

                        updateNotification(result.second)

                        // Jangan hapus data jika pengiriman gagal.
                        break
                    }

                    // Hapus hanya data pertama setelah server menerima.
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

                    updateNotification(
                        "Pelacakan aktif. Lokasi berhasil dikirim ke server."
                    )

                } else {

                    updateNotification(
                        "$remaining lokasi menunggu pengiriman. Akan dicoba kembali."
                    )
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

        val token = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, "")
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
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
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
                    payload.toString().toByteArray(Charsets.UTF_8)
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
                JSONObject(body.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }

            if (
                code in 200..299 &&
                response.optBoolean("success")
            ) {

                Pair(true, "Lokasi berhasil dikirim.")

            } else if (code == 401 || code == 403) {

                Pair(
                    false,
                    "Server menolak token perangkat (HTTP $code). Periksa status pendaftaran."
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

        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }

        networkCallback?.let {

            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }

        networkCallback = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Menutup aplikasi dari recent apps tidak sama dengan
        // persetujuan pengguna untuk menghentikan pelacakan.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {

        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }

        networkCallback?.let {

            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }

        networkCallback = null

        super.onDestroy()
    }
}
