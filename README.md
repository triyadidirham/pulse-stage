# PulseStage

Aplikasi **Wear OS + server Node.js + overlay web untuk OBS**. Ditujukan untuk Xiaomi Watch 2 Pro dan perangkat Wear OS dengan sensor heart rate. Folder repository memakai nama `sensor-stream-watchos`, tetapi aplikasi ini untuk **Wear OS Android**, bukan Apple watchOS.

Status: prototipe fungsional untuk pengujian. Belum dipublikasikan ke Play Store atau di-deploy ke domain publik; pembacaan sensor dan ketahanan baterai belum diuji pada jam fisik.

## Tampilan studio dan jam

- Dashboard gelap dengan aksen mint, panduan pairing, indikator koneksi, dan preview overlay yang memakai komponen visual yang sama dengan Browser Source OBS.
- Pilihan warna **Mint**, **Coral**, dan **Ice**. Pilih warna sebelum menyalin tautan; perubahan warna menghasilkan URL baru untuk ditempel ke OBS tanpa mengganti kode akses perangkat.
- Wear OS memakai dial BPM besar, tombol Start/Stop, kartu kode pairing dengan hitung mundur, dan kontrol yang dapat digulir melalui layar sentuh atau crown. Data tidak ditampilkan sebagai LIVE sebelum pengiriman berhasil.
- Preview tidak memakai angka demo. Pembacaan yang kedaluwarsa atau koneksi terputus ditampilkan sebagai `--`. Animasi dekoratif pada web mengikuti preferensi reduced motion; dial jam hanya digambar ulang saat nilai/status berubah.

## Alur pengguna

1. Pengguna membuka aplikasi PulseStage di smartwatch dan menampilkan tautan pairing, misalnya `https://domain/ABCD-EFGH`.
2. Pengguna membuka tautan tersebut di browser; kode sudah terisi. Isi nama pemain dan tekan **Hubungkan jam**. Memasukkan kode manual di halaman utama juga tetap bisa.
3. Web menghasilkan tautan overlay pribadi berbentuk `https://domain/kode-akses-overlay` untuk Browser Source OBS.
4. Setelah membaca penjelasan pembagian data dan memberi izin sensor, pengguna menekan **Start** di jam.
5. Jam mengirim pembacaan BPM melalui HTTPS ke server. Server meneruskannya ke web/OBS melalui **Server-Sent Events (SSE)**.
6. **Stop** menghentikan sensor dan pengiriman; **Hapus pairing / Reset pairing** mencabut akses perangkat dan overlay.

Kode di layar jam adalah kode **sekali pakai, berlaku 10 menit**, bukan ID permanen untuk membaca data seseorang. Kredensial jam, pengelola, dan overlay memiliki hak berbeda. Tidak ada aplikasi HP pendamping yang wajib dipasang.

## Menjalankan server

Memerlukan **Node.js 24 atau lebih baru**. Server memakai modul bawaan Node.js, termasuk SQLite; tidak memerlukan `npm install`.

```sh
npm start
```

Buka `http://localhost:3000`. Pada mesin pengembangan ini Node tersedia di `/opt/node-v24.18.0-linux-x64/bin`; jika `node` belum ada di PATH:

```sh
export PATH="/opt/node-v24.18.0-linux-x64/bin:$PATH"
npm start
```

Untuk diakses jam pada Wi-Fi yang sama:

```sh
HOST=0.0.0.0 npm start
```

Atur server pada **aplikasi jam debug** menjadi `http://IP-KOMPUTER:3000`. `localhost` pada jam menunjuk ke jam itu sendiri. Pastikan firewall dan jaringan tidak memblokir perangkat satu sama lain. Semua contoh HTTP hanya untuk pengujian lokal; build release memerlukan HTTPS.

Variabel konfigurasi ada di `.env.example`. File `.env` tidak dimuat otomatis; gunakan variabel shell atau `node --env-file=.env server/main.mjs`. SQLite disimpan di `data/pulsestage.sqlite`, dengan kredensial berbentuk hash; jangan masukkan direktori tersebut ke Git.

## Mencoba tanpa smartwatch

Biarkan server berjalan, lalu pada terminal lain:

```sh
npm run demo
```

Simulator mencetak tautan pairing. Buka tautan dan masukkan nama **DEMO** agar data sintetis jelas dibedakan dari data sensor. Setelah pairing, angka simulasi akan muncul di preview dan overlay. Ctrl+C menghapus registrasi simulator. Kode akan kedaluwarsa jika tidak diklaim dalam 10 menit.

## Aplikasi Wear OS

Buka folder `wear/` di Android Studio. Konfigurasi saat ini: Kotlin 2.1.20, Android Gradle Plugin 8.9.1, Gradle 8.12, compile/target SDK 36, min SDK 30. Gunakan JDK 17 atau yang kompatibel dan pasang Android SDK 36 serta Build Tools 36.0.0.

```sh
cd wear
./gradlew assembleDebug
```

Untuk menanam alamat server lokal langsung ke APK debug, gunakan (ganti IP sesuai komputer Anda):

```sh
./gradlew assembleDebug -PbackendUrl=http://192.168.10.6:3000
```

Server menggunakan port `3000` secara default. APK berada di `wear/app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` menerima HTTP untuk pengujian lokal; `bundleRelease`/`assembleRelease` tetap memerlukan HTTPS. Jalankan server dengan `HOST=0.0.0.0 npm start` dari root proyek agar dapat dijangkau jam pada jaringan yang sama. Jika alamat server sudah ditanam, menu **Server pengembangan** tidak perlu diisi. Jika instalasi lama memiliki alamat yang pernah diatur manual, alamat tersimpan itu tetap dipakai sampai diubah dari menu tersebut.

Pasang APK debug melalui Android Studio atau ADB yang sudah terhubung ke jam:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pada jam:

1. Buka PulseStage. Jika APK dibuat tanpa `-PbackendUrl`, gulir ke **Server pengembangan** dan masukkan alamat server, tanpa path. Jika alamat sudah ditanam saat build, langsung lanjut ke langkah berikutnya.
2. Tekan **Tampilkan kode**, kemudian buka URL yang ditampilkan di jam melalui browser komputer/HP. Kode sudah terisi; masukkan nama dan tekan **Hubungkan jam**.
3. Tekan **Start**, baca penjelasan pembagian data, dan berikan izin sensor.
4. Tekan **Stop** ketika selesai. Batas sesi prototipe adalah 4 jam; mulai sesi baru setelahnya jika diperlukan.

Pemilihan server hanya tersedia di build debug. Pada rilis Play Store, alamat server tertanam saat build; pengguna cukup membuka tautan yang ditampilkan. Tidak perlu mengatur environment di jam. Reset pairing diperlukan sebelum mengganti server pada debug agar kredensial server lama tidak terkirim ke server lain.

### Artefak uji dari sesi pengembangan ini

`wear/app/build/outputs/apk/debug/pulsestage-ui-debug.apk`, bila tersedia, adalah APK dengan tampilan terbaru yang dikompilasi langsung memakai Kotlin compiler, AAPT2, D8, zipalign, dan apksigner lokal. APK ini memakai kunci debug Android lokal dan server uji `http://192.168.10.6:3000`. Gunakan nama file itu pada perintah `adb install` di atas. Ini bukan paket distribusi produksi dan belum diuji di jam fisik; build Gradle standar tetap perlu diverifikasi pada mesin tanpa pembatasan filesystem/socket.

## OBS

Setelah pairing, pilih **Salin tautan overlay** pada dashboard. Tambahkan **Sources → + → Browser** di OBS, tempel URL lengkap berbentuk `https://domain/kode-akses-overlay`, dan set ukuran **480 × 180**. Latar halaman overlay transparan. Tidak perlu plugin OBS khusus. Tautan lama `/overlay#token=…` juga tetap berfungsi.

Warna Coral dan Ice ditambahkan sebagai `?theme=coral` atau `?theme=ice` pada URL; tanpa parameter, warna default adalah Mint. Pilihan warna tersimpan di browser dashboard. Jika Anda mengganti warna setelah setup OBS, salin ulang URL ke Browser Source. Tombol **Buka overlay** membuka tampilan transparan di tab baru.

URL pendek delapan karakter di jam digunakan untuk pairing sekali pakai. URL OBS memakai kode akses acak yang lebih panjang dan tetap berlaku sampai Anda mengganti tautan atau menghapus pairing. Membuka halaman pairing saja tidak mengklaim jam; pengguna tetap menekan **Hubungkan jam**. Ketika URL pairing dibuka pada browser yang sudah mengelola jam lain, penggantian perangkat aktif harus dikonfirmasi sebelum klaim.

Browser dashboard menyimpan akses pengelola dan URL overlay di localStorage. Prototipe belum memiliki akun/login atau pemulihan lintas browser. Jika data browser hilang, gunakan **Reset pairing** pada jam, lalu pairing ulang. Gunakan **Ganti tautan** untuk mencabut URL OBS lama tanpa mengganti kredensial jam.

## Perilaku pembacaan

- `SensorManager.TYPE_HEART_RATE` dibaca dalam foreground service yang dimulai dari tombol Start saat activity terlihat. Tidak ada pelacakan otomatis ketika aplikasi dibuka atau perangkat menyala.
- Izin `BODY_SENSORS` digunakan hingga API 35; `READ_HEART_RATE` digunakan pada API 36+. Tidak meminta izin background sensor terpisah atau akses Health Connect.
- Wake lock hanya dipegang selama sesi aktif, maksimal 4 jam. Konsumsi baterai dan perilaku sensor saat layar mati perlu diuji pada firmware Xiaomi yang sebenarnya.
- Jam mengirim pembacaan terbaru, maksimal sekitar sekali per detik, sesuai ketersediaan sensor. Tidak ada jaminan bahwa sensor menghasilkan pembacaan baru setiap detik.
- Timestamp pengukuran asli dipertahankan. Tidak mengulang pembacaan lama seolah-olah baru, tidak menyimpan antrean offline, dan tidak mengirim BPM nol ketika sensor tidak valid.
- Overlay menampilkan `--` jika data kedaluwarsa, sesi berhenti, atau koneksi gagal. Batas kedaluwarsa server 15 detik; notifikasi periodik maksimum 5 detik sesudahnya.
- Jam dan server harus mempunyai waktu sistem yang benar. API menolak timestamp jauh di masa depan atau pembacaan yang sudah lebih dari 15 detik.
- Animasi ikon jantung dekoratif; aplikasi ini mengirim angka BPM, bukan sinyal ECG atau waktu setiap denyut.

## Pengujian

```sh
npm test
# Opsional, jalankan dengan HTTP sungguhan pada mesin yang mengizinkan port lokal:
RUN_HTTP_TESTS=1 npm test
```

Tes default menggunakan handler HTTP yang sebenarnya dengan request/response stream dalam proses, tanpa membuka port. Meliputi alur pairing, pemisahan hak kredensial, pengiriman melalui SSE, kedaluwarsa, Stop, rotasi URL, penghapusan, validasi input, pembatasan percobaan, dan persistensi database tanpa riwayat BPM.

Di lingkungan pembuatan ini, socket lokal diblokir (`EPERM`), sehingga preview browser, transport HTTP nyata, dan Gradle daemon tidak dapat dijalankan. Kompilasi langsung Kotlin/resource Android serta verifikasi tanda tangan APK tidak menggantikan pengujian jam/OBS sebenarnya.

## Produksi

Lihat [panduan produksi dan Play Store](docs/production.md) untuk deployment HTTPS, batas prototipe, build AAB, dan persiapan rilis. Lihat [kontrak API](docs/api.md) jika ingin mengembangkan aplikasi pendamping atau integrasi lain.

Struktur proyek:

```text
server/   HTTP API, SQLite, dan SSE
web/      Dashboard pairing, overlay, dan penjelasan privasi prototipe
wear/     Aplikasi native Wear OS
tests/    Pengujian alur server
scripts/  Simulator jam untuk pengembangan
deploy/   Contoh deployment satu server dengan Docker Compose dan Caddy
```
