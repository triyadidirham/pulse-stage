# Produksi dan distribusi Play Store

Dokumen ini menjelaskan cara membawa prototipe ke layanan publik. Konfigurasi deployment sudah tersedia, tetapi server publik dan listing Play Store belum dibuat. Jangan menganggap APK debug atau halaman privasi prototipe sudah siap dipublikasikan.

## Arsitektur layanan publik

Jam → HTTPS → server PulseStage → HTTPS/SSE → dashboard atau Browser Source OBS.

Server berada di VPS/layanan cloud yang dapat dijangkau jam melalui internet. Website dan API menggunakan origin yang sama. PC OBS tidak harus satu jaringan dengan jam dan tidak perlu menjalankan Node.js sendiri. Jaringan jam dapat memakai Wi-Fi atau koneksi yang disediakan Wear OS; kemampuan konektivitas sebenarnya perlu diuji pada perangkat.

Build release menanam URL server HTTPS. Pengguna tidak mengetik IP server: pasang aplikasi, buka tautan `https://domain/ABCD-EFGH` yang ditampilkan di jam, isi nama dan hubungkan, lalu salin URL OBS berbentuk `https://domain/kode-akses-overlay`. Satu pemain menggunakan satu registrasi jam; banyak pemain dapat memakai server yang sama.

Prototipe menggunakan Node.js 24, HTTP bawaan, SQLite, serta SSE satu arah. WebSocket tidak diperlukan karena BPM hanya mengalir dari server ke tampilan. Tidak ada riwayat BPM yang ditulis ke database.

## Deployment awal satu server

1. Siapkan domain milik Anda dan arahkan DNS ke VPS. Buka port 80/443 untuk Caddy dan biarkan port API hanya ada dalam jaringan container.
2. Instal Docker dan Compose pada VPS, salin repository, lalu dari root proyek:

   ```sh
   DOMAIN=pulse.domain-anda.com docker compose -f infra/compose.yaml up -d --build
   ```

3. Caddy mengurus HTTPS untuk domain tersebut dan meneruskan SSE tanpa buffering. Node memerlukan `PUBLIC_ORIGIN=https://domain` ketika `NODE_ENV=production`.
4. Periksa `/healthz`, pairing, pengiriman dari jam, dan Browser Source OBS menggunakan koneksi internet terpisah. Jam harus bisa memverifikasi sertifikat TLS; jangan menonaktifkan verifikasi sertifikat.
5. Tentukan pencadangan volume `pulse_data`, pemulihan, monitoring error dan uptime, pembaruan image/dependensi, serta kebijakan penghapusan backup. Log jangan mencatat header Authorization, body BPM, kode pairing, atau path yang mengandung kode akses overlay. Konfigurasi Caddy contoh tidak mengaktifkan access log; jika menambah proxy/CDN/analitik, redaksi atau matikan pencatatan URL rahasia tersebut.

Compose ini adalah konfigurasi awal yang belum diuji deployment dalam sesi pengembangan. Tetapkan versi/digest image yang sudah Anda uji sebelum rilis operasional.

File Compose sekarang berada di `infra/compose.yaml`. Nama project tetap `deploy` untuk mempertahankan volume bawaan dari lokasi lama. Untuk instalasi yang memakai nama project khusus, pertahankan nilai `-p` atau `COMPOSE_PROJECT_NAME` sebelumnya saat menjalankan Compose dari lokasi baru.

## Batas sebelum membuka layanan ke banyak pengguna

- Jalankan **satu proses/satu instance** aplikasi. Sesi dan subscriber SSE disimpan di memori proses; SQLite menyimpan registrasi. Restart mempertahankan pairing, tetapi menghentikan sesi; aplikasi jam mencoba membuat sesi baru ketika ada pembacaan baru. Untuk beberapa instance, pindahkan metadata ke PostgreSQL, distribusikan sesi/event lewat Redis atau layanan setara, dan uji pencabutan akses di semua instance.
- Rate limit aplikasi memakai alamat socket, bukan `X-Forwarded-For` yang dapat dipalsukan. Di belakang Caddy, pengguna berbagi bucket IP proxy. Konfigurasikan pembatasan per pengguna/IP pada proxy/edge sebelum membuka registrasi umum, atau implementasikan daftar proxy tepercaya dengan benar. Jangan sekadar mempercayai header IP dari semua koneksi.
- Tambahkan akun dan pemulihan kepemilikan bila pengguna perlu akses lintas browser, mengelola beberapa jam, tim, atau turnamen. Pada prototipe, browser menyimpan token pengelola; kehilangan penyimpanan browser memerlukan reset dari jam. Pairing tanpa akun dapat dipertahankan sebagai mode sederhana.
- Tetapkan batas jumlah perangkat, jumlah stream, lifecycle perangkat tidak aktif, dan kontrol abuse registrasi. Batas stream sekarang sepuluh per perangkat.
- Uji bahwa Start/Stop benar-benar menghentikan/memulai sensor, sesi bertahan saat layar mati, data terputus ditampilkan dengan benar, reconnect tidak menampilkan data lama, serta reset/rotasi langsung mencabut akses.
- Uji 1–4 jam pada Xiaomi Watch 2 Pro: kualitas sinyal saat bermain, keterlambatan, Wi-Fi/Bluetooth, perizinan, konsumsi baterai, panas, dan penghentian proses oleh sistem. Jangan menjanjikan interval satu detik atau kompatibilitas semua firmware sebelum pengujian.

## Membuat paket rilis

1. Pilih nama aplikasi, `applicationId` permanen, domain produksi, ikon final, dan identitas developer. `dev.pulsestage.wear` adalah nama awal yang harus Anda evaluasi sebelum upload pertama.
2. Buka `apps/wear/` di Android Studio. Tentukan `backendUrl` menggunakan properti Gradle lokal atau argumen:

   ```sh
   ./gradlew bundleRelease -PbackendUrl=https://pulse.domain-anda.com
   ```

   Tugas release menolak domain placeholder. Perintah di atas tanpa signing configuration hanya menyiapkan bundle yang belum ditandatangani.

3. Gunakan **Generate Signed App Bundle / APK → Android App Bundle** dan upload key milik Anda. Aktifkan Play App Signing sesuai alur Play Console. Simpan upload key/password di penyimpanan rahasia atau CI secret, jangan di repository. APK debug tidak dapat dipakai sebagai artefak produksi.
4. Aktifkan form factor Wear OS dan mulai dari internal testing. Ikuti persyaratan testing akun developer Anda sebelum mengajukan produksi.
5. Sediakan listing, screenshot dari jam, ikon, kontak dukungan, privacy policy publik, dan petunjuk akses reviewer untuk mencoba pairing tanpa kredensial pribadi Anda. Verifikasi dashboard/overlay dari jaringan reviewer.

## Heart rate dan persyaratan Play

Target SDK saat ini 36. Dokumentasi target API Google Play yang diperiksa pada 11 Oktober 2026 menyatakan aplikasi Wear OS baru/update perlu minimal API 35 sejak 31 Agustus 2026. Persyaratan dapat berubah; periksa kembali saat submit.

Aplikasi membaca data heart rate walaupun digunakan untuk overlay gaming. Jelaskan secara jujur manfaat pengguna dan alur pembagian ke penonton. Penggunaan izin sensor ditinjau Google; penerimaan kasus penggunaan ini tidak dijamin oleh ketersediaan API.

- Lengkapi **Health apps declaration**, **Data safety**, dan deklarasi foreground service bila diminta Play Console, sesuai perilaku aplikasi dan infrastruktur sebenarnya. Heart rate dan pengenal perangkat dikirim ke server; jangan mengklaim aplikasi tidak mengirim data.
- Ganti halaman `/privacy` pengembangan dengan kebijakan final yang memuat identitas operator, kontak, data yang dikumpulkan, tujuan, penerima, retensi, log/backup, cara mencabut consent dan menghapus data. Tampilkan link tersebut pada aplikasi dan listing.
- Dialog sebelum Start sudah menjelaskan pengiriman BPM dan akses penonton. Pastikan izin runtime diberikan setelah penjelasan, dan Stop tetap mudah diakses dari jam/notifikasi. Tidak ada startup otomatis atau pengambilan data sebelum persetujuan sesi.
- Gunakan izin paling sempit. Manifest menyediakan `BODY_SENSORS` hingga API 35 dan `READ_HEART_RATE` untuk API 36+. Aplikasi memulai health foreground service dari activity yang terlihat. Tidak menggunakan Health Connect, bukan pencatatan workout, dan tidak menyamarkan sesi gaming sebagai olahraga.
- Jika kelak menambah akun, analitik, penyimpanan riwayat, iklan, billing, atau layanan pihak ketiga, perbarui deklarasi, consent, retensi, dan alur penghapusan sesuai fitur yang benar-benar diimplementasikan.

Referensi resmi:

- [Persyaratan target API Google Play](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Health apps declaration](https://support.google.com/googleplay/android-developer/answer/14738291?hl=en)
- [Kebijakan izin sensor tubuh](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)
- [Android health permissions: guidance and FAQs](https://support.google.com/googleplay/android-developer/answer/12991134?hl=en)
- [Jenis foreground service: health](https://developer.android.com/develop/background-work/services/fgs/service-types#health)
- [Kualitas aplikasi Wear OS](https://developer.android.com/docs/quality-guidelines/wear-app-quality)
- [Jaringan mandiri Wear OS](https://developer.android.com/training/wearables/data/network-communication)

## Pemeriksaan penerimaan sebelum rilis

Pairing hanya berhasil sekali; kode kedaluwarsa ditolak; perangkat A tidak dapat mengirim data milik B; token overlay tidak dapat menulis data; pergantian URL memutus subscriber lama; Stop meniadakan BPM; sample lama tidak tampil ulang setelah reconnect; tidak ada riwayat BPM atau token mentah dalam database/log; reset dari jam dan hapus dari web sama-sama mencabut registrasi; build release tidak mengizinkan HTTP; dashboard/overlay dan antarmuka jam dapat dipakai pada ukuran layar sebenarnya.
