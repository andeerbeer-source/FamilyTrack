# FamilyTrack Android

Starter project Android Kotlin untuk FamilyTrack. Project ini disiapkan agar APK dapat dibangun otomatis melalui GitHub Actions tanpa Android Studio.

## Penting sebelum digunakan
Aplikasi ini adalah **starter source code**, bukan APK yang sudah jadi dan bukan integrasi server yang sudah diverifikasi. URL API dan format data perlu disesuaikan dengan endpoint Laravel Anda. Aplikasi tidak mengakses database MySQL secara langsung; aplikasi Android harus mengirim data melalui API Laravel yang aman.

Konfigurasi API:
- File: `app/src/main/java/id/go/beltim/familytrack/MainActivity.kt`
- Ubah `API_URL` menjadi endpoint penerima lokasi Laravel, misalnya `https://raperda.dprd.beltim.go.id/api/gps/location` **hanya jika endpoint itu memang sudah dibuat dan sesuai**.
- Format JSON contoh: `device_id`, `latitude`, `longitude`, `accuracy`, `recorded_at`.
- Endpoint Laravel perlu memvalidasi autentikasi dan menyimpan ke tabel `gps_locations` sesuai skema database aktual.

## Build APK via GitHub Actions
1. Buat repository baru di GitHub, misalnya `FamilyTrack-Android`.
2. Ekstrak ZIP ini di komputer.
3. Upload seluruh isi folder ke repository GitHub (folder `.github` juga harus ikut).
4. Buka tab **Actions**. Jika diminta, aktifkan workflow.
5. Pilih workflow **Build FamilyTrack APK**, lalu klik **Run workflow**.
6. Setelah selesai, buka run yang berhasil dan unduh artifact **FamilyTrack-debug-apk**.
7. Ekstrak artifact ZIP, lalu pasang `app-debug.apk` pada ponsel Android. Anda mungkin perlu mengizinkan instalasi dari sumber ini.

## Build lokal (opsional)
Memerlukan JDK 17 dan Android SDK. Jalankan:
```bash
gradle assembleDebug
```
APK biasanya berada di `app/build/outputs/apk/debug/app-debug.apk`.

## Status fitur
- Layar dasar FamilyTrack.
- Meminta izin lokasi saat aplikasi digunakan.
- Membaca lokasi terakhir melalui Android LocationManager.
- Contoh tombol untuk mengirim koordinat ke API Laravel.
- Belum ada pelacakan latar belakang berkelanjutan, login/berbagi keluarga, atau integrasi database yang dapat dipastikan tanpa endpoint dan skema Laravel yang sebenarnya.
