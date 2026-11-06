package id.go.beltim.familytrack

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
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
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001

        // Ganti dengan endpoint API Laravel yang benar-benar tersedia.
        // Jangan gunakan URL ini sebelum route/controller API dibuat dan diuji.
        private const val API_URL = "https://raperda.dprd.beltim.go.id/api/gps/location"
    }

    private lateinit var statusText: TextView
    private lateinit var locationText: TextView
    private var latestLocation: Location? = null

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
        statusText = TextView(this).apply {
            text = "Status: siap"
            textSize = 16f
            setPadding(0, 32, 0, 16)
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
        val note = TextView(this).apply {
            text = "Catatan: pengiriman membutuhkan endpoint API Laravel yang valid."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(locationText)
        root.addView(getLocationButton)
        root.addView(sendButton)
        root.addView(note)
        setContentView(root)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
        statusText.text = "Mengirim lokasi..."
        thread {
            var connection: HttpURLConnection? = null
            try {
                val payload = JSONObject().apply {
                    put("device_id", UUID.nameUUIDFromBytes(android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID).toByteArray()).toString())
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                    put("accuracy", loc.accuracy)
                    put("recorded_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(loc.time)))
                }
                connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                runOnUiThread {
                    statusText.text = if (code in 200..299) {
                        "Lokasi terkirim (HTTP $code)."
                    } else {
                        "Server menolak permintaan (HTTP $code). Periksa endpoint API."
                    }
                    if (body.isNotBlank()) Toast.makeText(this, body.take(180), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Gagal mengirim: ${e.message ?: "koneksi bermasalah"}" }
            } finally {
                connection?.disconnect()
            }
        }
    }
}
