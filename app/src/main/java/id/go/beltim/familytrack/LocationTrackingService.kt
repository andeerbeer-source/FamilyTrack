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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class LocationTrackingService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "id.go.beltim.familytrack.action.START_TRACKING"
        const val ACTION_STOP = "id.go.beltim.familytrack.action.STOP_TRACKING"
        private const val API_BASE = "https://raperda.dprd.beltim.go.id/api/familytrack"
        private const val PREFS = "familytrack_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_LAST_LAT = "last_latitude"
        private const val KEY_LAST_LON = "last_longitude"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_TIME = "last_location_time"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_LAST_BEARING = "last_bearing"
        private const val CHANNEL_ID = "familytrack_location"
        private const val NOTIFICATION_ID = 7401
        private const val UPDATE_INTERVAL_MS = 60_000L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile private var sending = false
    @Volatile private var pendingLocation: Location? = null
    @Volatile private var latestLocation: Location? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        // START_STICKY dapat memanggil ulang service dengan intent null.
        // Jangan meminta token undangan lagi: token perangkat tetap dibaca dari penyimpanan lokal.
        if (!hasLocationPermission()) {
            updateNotification("Izin lokasi tidak tersedia. Buka FamilyTrack untuk mengaktifkan kembali.")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        beginLocationUpdates()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("Menyiapkan pelacakan lokasi…")
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
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
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
                description = "Menampilkan status layanan pelacakan lokasi yang sedang aktif."
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED ||
            coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Saat internet kembali, kirim lokasi terakhir yang tersimpan.
                val saved = latestLocation ?: readSavedLocation()
                if (saved != null) sendLocation(saved)
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            // Service tetap dapat mengirim pada pembaruan GPS berikutnya.
        }
    }

    private fun beginLocationUpdates() {
        if (!hasLocationPermission()) {
            updateNotification("Izin lokasi tidak tersedia.")
            stopSelf()
            return
        }
        try {
            var requested = false
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
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
                    // Provider tidak tersedia pada perangkat ini.
                }
            }

            if (!requested) {
                updateNotification("Layanan aktif, tetapi GPS belum tersedia. Periksa pengaturan lokasi.")
            } else {
                updateNotification("Pelacakan aktif. Menunggu pembaruan lokasi dan koneksi server.")
                val last = try {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: SecurityException) {
                    null
                }
                if (last != null) sendLocation(last)
            }
        } catch (_: SecurityException) {
            updateNotification("Izin lokasi tidak tersedia. Buka FamilyTrack untuk mengaktifkan.")
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        latestLocation = location
        saveLocation(location)
        sendLocation(location)
    }

    private fun saveLocation(location: Location) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(location.longitude))
            .putFloat(KEY_LAST_ACCURACY, location.accuracy)
            .putLong(KEY_LAST_TIME, location.time)
            .putFloat(KEY_LAST_SPEED, if (location.hasSpeed()) location.speed else -1f)
            .putFloat(KEY_LAST_BEARING, if (location.hasBearing()) location.bearing else -1f)
            .apply()
    }

    private fun readSavedLocation(): Location? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_TIME)) return null
        return try {
            Location("familytrack_saved").apply {
                latitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, 0L))
                longitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, 0L))
                accuracy = prefs.getFloat(KEY_LAST_ACCURACY, 0f)
                time = prefs.getLong(KEY_LAST_TIME, 0L)
                val speed = prefs.getFloat(KEY_LAST_SPEED, -1f)
                val bearing = prefs.getFloat(KEY_LAST_BEARING, -1f)
                if (speed >= 0f) setSpeed(speed)
                if (bearing >= 0f) setBearing(bearing)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendLocation(location: Location) {
        saveLocation(location)
        val token = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_DEVICE_TOKEN, "").orEmpty()
        if (token.isBlank()) {
            updateNotification("Token perangkat tidak ditemukan. Buka aplikasi untuk pemeriksaan.")
            return
        }

        synchronized(this) {
            if (sending) {
                pendingLocation = location
                return
            }
            sending = true
        }

        thread {
            var message = "Lokasi terakhir berhasil dikirim ke server."
            try {
                val payload = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy", location.accuracy)
                    if (location.hasSpeed()) put("speed", location.speed)
                    if (location.hasBearing()) put("heading", location.bearing)
                }

                val connection = URL("$API_BASE/location").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 20000
                    connection.readTimeout = 20000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.outputStream.use {
                        it.write(payload.toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val response = JSONObject(body.ifBlank { "{}" })
                    if (code !in 200..299 || !response.optBoolean("success")) {
                        message = "Server menolak lokasi (HTTP $code). Token atau endpoint perlu diperiksa."
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                message = "Internet/server terputus. Lokasi terakhir disimpan dan dicoba lagi saat koneksi pulih."
            } finally {
                updateNotification(message)
                val next: Location?
                synchronized(this) {
                    sending = false
                    next = pendingLocation
                    pendingLocation = null
                }
                if (next != null && next.time >= location.time) {
                    sendLocation(next)
                }
            }
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun stopTracking() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Menutup layar aplikasi tidak sama dengan menghentikan pelacakan.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        super.onDestroy()
    }
}
