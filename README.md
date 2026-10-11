# PulseStage

Aplikasi **Wear OS + server Node.js + overlay web untuk OBS**. Ditujukan untuk Xiaomi Watch 2 Pro dan perangkat Wear OS dengan sensor heart rate. Folder repository memakai nama `sensor-stream-watchos`, tetapi aplikasi ini untuk **Wear OS Android**, bukan Apple watchOS.

Status: prototipe untuk pengujian. Belum dipublikasikan ke Play Store; jalur Health Services dan ketahanan baterai masih perlu diverifikasi pada jam fisik.

## Tampilan studio dan jam

- Dashboard gelap dengan aksen mint, panduan pairing, indikator koneksi, dan preview overlay yang memakai komponen visual yang sama dengan Browser Source OBS.
- Pilihan warna **Mint**, **Coral**, dan **Ice**. Pilih warna sebelum menyalin tautan; perubahan warna menghasilkan URL baru untuk ditempel ke OBS tanpa mengganti kode akses perangkat.
- Wear OS memakai dial BPM besar, tombol Start/Stop, kartu kode pairing yang berlaku sampai direset, dan kontrol yang dapat digulir melalui layar sentuh atau crown. Data tidak ditampilkan sebagai LIVE sebelum pengiriman berhasil.
- Preview tidak memakai angka demo. Pembacaan yang kedaluwarsa atau koneksi terputus ditampilkan sebagai `--`. Animasi dekoratif pada web mengikuti preferensi reduced motion; dial jam hanya digambar ulang saat nilai/status berubah.

## Alur pengguna

1. Pengguna membuka aplikasi PulseStage di smartwatch dan menampilkan tautan pairing, misalnya `https://domain/ABCD-EFGH`.
2. Pengguna membuka tautan tersebut di browser; kode sudah terisi. Isi nama pemain dan tekan **Hubungkan jam**. Memasukkan kode manual di halaman utama juga tetap bisa.
3. Web menghasilkan tautan overlay pribadi berbentuk `https://domain/kode-akses-overlay` untuk Browser Source OBS.
4. Setelah membaca penjelasan pembagian data dan memberi izin sensor, pengguna menekan **Start** di jam.
5. Jam mengirim pembacaan BPM melalui HTTPS ke server. Server meneruskannya ke web/OBS melalui **Server-Sent Events (SSE)**, dengan polling snapshot sebagai cadangan ketika streaming tersendat.
6. **Stop** menghentikan sensor dan pengiriman; **Reset kode akses** di jam mengganti kode sekaligus mencabut semua akses browser/OBS lama; **Hapus pairing** di web atau **Hapus koneksi** di jam menghapus registrasi.

Kode di layar jam **tidak kedaluwarsa dan dapat dipakai ulang**, termasuk untuk menghubungkan browser baru. Simpan kode seperti kata sandi: pemiliknya dapat mengelola perangkat dan membuat tautan OBS. Kredensial jam, pengelola, dan overlay memiliki hak berbeda. Tidak ada aplikasi HP pendamping yang wajib dipasang.

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

Variabel konfigurasi ada di `.env.example`. File `.env` tidak dimuat otomatis; gunakan variabel shell atau `node --env-file=.env apps/server/src/main.mjs`. Lokasi SQLite default tetap `data/pulsestage.sqlite` di root repository meskipun server dijalankan dari folder lain. `DATABASE_PATH` relatif mengikuti working directory proses. Database menyimpan registrasi perangkat, dengan kredensial berbentuk hash; jangan masukkan direktori tersebut ke Git.

## Mencoba tanpa smartwatch

Biarkan server berjalan, lalu pada terminal lain:

```sh
npm run demo
```

Simulator mencetak tautan pairing. Buka tautan dan masukkan nama **DEMO** agar data sintetis jelas dibedakan dari data sensor. Setelah pairing, angka simulasi akan muncul di preview dan overlay. Ctrl+C menghapus registrasi simulator. Kode tidak kedaluwarsa; penghapusan registrasi simulator mencabut kode tersebut.

## Aplikasi Wear OS

Buka folder `apps/wear/` di Android Studio. Konfigurasi saat ini: Kotlin 2.1.20, Android Gradle Plugin 8.9.1, Gradle 8.12, compile/target SDK 36, min SDK 30. Gunakan JDK 17 atau yang kompatibel dan pasang Android SDK 36 serta Build Tools 36.0.0.

```sh
cd apps/wear
./gradlew assembleDebug
```

Untuk menanam alamat server lokal langsung ke APK debug, gunakan (ganti IP sesuai komputer Anda):

```sh
./gradlew assembleDebug -PbackendUrl=http://192.168.10.6:3000
```

Server menggunakan port `3000` secara default. APK berada di `apps/wear/app/build/outputs/apk/debug/app-debug.apk`. `assembleDebug` menerima HTTP untuk pengujian lokal; `bundleRelease`/`assembleRelease` tetap memerlukan HTTPS. Jalankan server dengan `HOST=0.0.0.0 npm start` dari root proyek agar dapat dijangkau jam pada jaringan yang sama. Jika alamat server sudah ditanam, menu **Server pengembangan** tidak perlu diisi. Jika instalasi lama memiliki alamat yang pernah diatur manual, alamat tersimpan itu tetap dipakai sampai diubah dari menu tersebut.

Pasang APK debug melalui Android Studio atau ADB yang sudah terhubung ke jam:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pada jam:

1. Buka PulseStage. Jika APK dibuat tanpa `-PbackendUrl`, gulir ke **Server pengembangan** dan masukkan alamat server, tanpa path. Jika alamat sudah ditanam saat build, langsung lanjut ke langkah berikutnya.
2. Tekan **Tampilkan kode**, kemudian buka URL yang ditampilkan di jam melalui browser komputer/HP. Kode sudah terisi; masukkan nama dan tekan **Hubungkan jam**.
3. Tekan **Start**, baca penjelasan pembagian data, dan berikan izin sensor.
4. Tekan **Stop** ketika selesai. Batas sesi prototipe adalah 4 jam; mulai sesi baru setelahnya jika diperlukan.

Pemilihan server hanya tersedia di build debug. Pada rilis Play Store, alamat server tertanam saat build; pengguna cukup membuka tautan yang ditampilkan. Tidak perlu mengatur environment di jam. Hapus koneksi diperlukan sebelum mengganti server pada debug agar kredensial server lama tidak terkirim ke server lain.

### Jika jam terus menunggu sensor

Pada Android API 34, izin yang diperlukan adalah **Sensor tubuh** (`BODY_SENSORS`). Pastikan izin diberikan pada aplikasi PulseStage yang sedang dibuka; paket debug (`dev.pulsestage.wear.debug`) berbeda dari release (`dev.pulsestage.wear`). Gunakan aplikasi detak jantung bawaan sebagai pembanding, kemudian hentikan pengukurannya sebelum mencoba PulseStage.

Aplikasi memakai **Health Services `ExerciseClient`**, jenis sesi `WORKOUT`, hanya meminta `HEART_RATE_BPM`, tanpa GPS atau auto-pause. Jalur ini menggantikan pembacaan langsung SensorManager/LifeQ yang pada pengujian Xiaomi berhasil didaftarkan tetapi tidak memberikan BPM valid. Sesi aktivitas aplikasi lain harus dihentikan dahulu; PulseStage memeriksa kepemilikan sebelum Start. Sesi Wear OS tetap diakhiri jika Stop ditekan saat proses Start masih berjalan.

Perbarui Health Services dari Play Store jam dan gunakan APK terbaru. Saat layar mati, pengiriman sensor dapat dibatch oleh Wear OS. PulseStage meminta batching HR 5 detik jika perangkat mendukungnya; data dengan umur lebih dari 15 detik tetap disembunyikan. Setelah 135 detik tanpa BPM valid, sesi dihentikan dengan pesan diagnosis. Timestamp pengukuran asli dipakai, dan status `NO_CONTACT`/`UNRELIABLE` tidak diterbitkan sebagai BPM.

```bash
adb shell getprop ro.build.version.sdk
adb logcat -c
adb logcat -v time PulseStageSensor:I PulseStageHealth:I AndroidRuntime:E '*:S'
```

Tekan Start setelah logcat berjalan. `PulseStageHealth` mencatat kemampuan perangkat, kepemilikan, registrasi callback, dan status sesi; `PulseStageSensor` mencatat status pembacaan/pengiriman. Log aplikasi tidak berisi BPM atau kredensial. Keberhasilan membaca BPM dan perilaku saat layar mati tetap perlu diuji pada Xiaomi Watch 2 Pro fisik.

```bash
cd apps/wear
./gradlew testDebugUnitTest assembleDebug -PbackendUrl=https://ps.triyadi.dev &&
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Dependensi `com.google.guava:guava:32.0.1-android` dideklarasikan eksplisit pada `implementation`, mengikuti versi yang dipakai Health Services. Ini menyediakan `ListenableFuture` saat kompilasi maupun runtime. Dependensi standalone `listenablefuture:1.0` saja tidak cukup: resolusi Gradle dapat memilih artefak kosong `9999.0-empty-to-avoid-conflict-with-guava`, sementara Guava dari Health Services hanya tersedia pada runtime. Lihat [panduan ExerciseClient](https://developer.android.com/health-and-fitness/health-services/active-data). APK debug hasil percobaan lama bukan build Health Services terbaru; gunakan output Gradle di atas. Operator `&&` mencegah instalasi APK ketika build gagal.

## OBS

Setelah pairing, pilih **Salin tautan overlay** pada dashboard. Tambahkan **Sources → + → Browser** di OBS, tempel URL lengkap berbentuk `https://domain/kode-akses-overlay`, dan set ukuran **480 × 180**. Latar halaman overlay transparan. Tidak perlu plugin OBS khusus. Tautan lama `/overlay#token=…` juga tetap berfungsi.

Jika koneksi streaming belum mengirim snapshot dalam 3 detik, web/OBS mengambil `/api/snapshot` setiap sekitar 1 detik sebagai cadangan. Jika stream yang sudah aktif tidak mengirim snapshot selama 7 detik, cadangan yang sama digunakan. SSE tetap mencoba terhubung kembali; polling dihentikan saat SSE mengirim data terbaru. Permintaan yang gagal memakai jeda bertahap. Koneksi baru dianggap terhubung setelah data diterima, bukan hanya setelah header HTTP diterima.

Cadangan ini mengatasi gangguan jalur server–overlay. Pembacaan Health Services dapat datang dalam batch, dan koneksi jam ke server juga memengaruhi keterlambatan; angka tidak diprediksi atau dibuat-buat untuk mengisi jeda. Untuk memperbarui fitur ini, deploy backend/web terbaru lalu refresh halaman studio dan Browser Source OBS. APK tidak perlu dibangun ulang untuk perubahan koneksi overlay ini.

Warna Coral dan Ice ditambahkan sebagai `?theme=coral` atau `?theme=ice` pada URL; tanpa parameter, warna default adalah Mint. Pilihan warna tersimpan di browser dashboard. Jika Anda mengganti warna setelah setup OBS, salin ulang URL ke Browser Source. Tombol **Buka overlay** membuka tampilan transparan di tab baru.

URL pendek delapan karakter di jam dapat digunakan untuk pairing berulang kali sampai kode direset. Pairing ulang membuat akses browser baru tanpa memutus tautan OBS yang sudah terpasang. URL OBS memakai kode akses acak yang lebih panjang dan tetap berlaku sampai Anda mengganti tautan atau menghapus pairing. Membuka halaman pairing saja tidak mengklaim jam; pengguna tetap menekan **Hubungkan jam**. Ketika URL pairing dibuka pada browser yang sudah mengelola jam lain, penggantian perangkat aktif harus dikonfirmasi sebelum klaim.

Browser dashboard menyimpan akses pengelola dan URL overlay di localStorage. Prototipe belum memiliki akun/login. Jika data browser hilang, masukkan kembali kode yang sama dari jam. Gunakan **Ganti tautan OBS** untuk mencabut seluruh URL OBS lama tanpa mengganti kode pairing atau kredensial jam. Jika kode pairing bocor, tekan **Reset kode akses** di jam; semua akses browser/OBS lama ikut dicabut. Kode tetap berlaku setelah restart server selama database disimpan.

Upgrade: cadangkan database, deploy server baru, lalu pasang APK terbaru. Migrasi mempertahankan akses browser/OBS lama. Untuk pairing yang sudah dikonsumsi versi sebelumnya, tekan **Reset kode akses** sekali di jam untuk membuat kode permanen dan hubungkan ulang OBS. Kode pending lama otomatis menjadi tanpa kedaluwarsa. Detail kompatibilitas/rollback tersedia di [kontrak API](docs/api.md#reset-dan-migrasi).

## Perilaku pembacaan

- `Health Services ExerciseClient` dijalankan dalam foreground service yang dimulai dari tombol Start saat activity terlihat. Tidak ada pelacakan otomatis ketika aplikasi dibuka atau perangkat menyala.
- Izin `BODY_SENSORS` digunakan hingga API 35; `READ_HEART_RATE` digunakan pada API 36+. Tidak meminta izin background sensor terpisah atau akses Health Connect.
- Wake lock hanya dipegang selama sesi aktif, maksimal 4 jam. Konsumsi baterai dan perilaku sensor saat layar mati perlu diuji pada firmware Xiaomi yang sebenarnya.
- Jam mengirim pembacaan terbaru, maksimal sekitar sekali per detik, sesuai ketersediaan sensor. Tidak ada jaminan bahwa sensor menghasilkan pembacaan baru setiap detik.
- Timestamp pengukuran asli dipertahankan. Tidak mengulang pembacaan lama seolah-olah baru, tidak menyimpan antrean offline, dan tidak mengirim BPM nol ketika sensor tidak valid.
- Overlay menghitung umur pembacaan dari timestamp server dan menghapus BPM setelah 15 detik sejak pengukuran. Heartbeat/reconnect tidak memperpanjang umur angka. Saat koneksi terputus sesaat, pembacaan yang masih segar tetap terlihat dengan status **MENYAMBUNG ULANG**; angka disembunyikan ketika kedaluwarsa, sesi berhenti, atau akses dicabut.
- Jam dan server harus mempunyai waktu sistem yang benar. API menolak timestamp jauh di masa depan atau pembacaan yang sudah lebih dari 15 detik.
- Animasi ikon jantung dekoratif; aplikasi ini mengirim angka BPM, bukan sinyal ECG atau waktu setiap denyut.

## Pengujian

```sh
npm test
# Opsional, jalankan dengan HTTP sungguhan pada mesin yang mengizinkan port lokal:
RUN_HTTP_TESTS=1 npm test
```

Tes default menggunakan handler HTTP yang sebenarnya dengan request/response stream dalam proses, tanpa membuka port. Meliputi koneksi overlay yang terputus, fallback polling, respons terlambat, kedaluwarsa angka di browser, pairing permanen, reset/revokasi semua akses, migrasi database lama, pemisahan hak kredensial, pengiriman melalui SSE, kedaluwarsa, Stop, rotasi URL, penghapusan, validasi input, pembatasan percobaan, persistensi database tanpa riwayat BPM, lokasi database, serta pemuatan aset dan modul browser dari struktur baru.

Di lingkungan pembuatan ini, socket lokal diblokir (`EPERM`), sehingga preview browser, transport HTTP nyata, dan Gradle daemon tidak dapat dijalankan. Kompilasi langsung Kotlin/resource Android serta verifikasi tanda tangan APK tidak menggantikan pengujian jam/OBS sebenarnya.

## Produksi

Lihat [panduan produksi dan Play Store](docs/production.md) untuk deployment HTTPS, batas prototipe, build AAB, dan persiapan rilis. Lihat [kontrak API](docs/api.md) jika ingin mengembangkan aplikasi pendamping atau integrasi lain.

Deployment Compose menggunakan template `infra/.env.example`; salin ke `infra/.env` dan isi `DOMAIN` sesuai hostname VPS. Panduan produksi memuat langkah clone, menjalankan layanan, pengecekan HTTPS, serta update deployment.

Jika VPS sudah menjalankan Nginx, gunakan `infra/compose.nginx.yaml` dengan `APP_PORT=3012`. Mode ini hanya menjalankan aplikasi pada `127.0.0.1:3012`; Nginx menyediakan HTTPS. Lihat [panduan mode Nginx](docs/production.md#deployment-dengan-nginx-yang-sudah-ada) dan contoh `infra/nginx/pulsestage.conf.example`.

Pesan commit baru mengikuti [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), misalnya `feat(web): add overlay color selection` atau `fix(wear): reconnect after a network interruption`. Aturan lengkap tersimpan di [AGENTS.md](AGENTS.md).

## Struktur proyek

Proyek memakai satu repository dengan batas aplikasi yang terpisah. Web tetap berupa HTML/CSS/JavaScript native yang disajikan oleh server pada origin yang sama; tidak ada build frontend atau instalasi dependency tambahan.

```text
apps/
  server/
    src/
      app.mjs                 HTTP API, sesi BPM, dan SSE
      main.mjs                Entry point proses dan shutdown
      config.mjs              Pembacaan environment dan lokasi database
      db/                     Repository SQLite untuk registrasi perangkat
      http/                   JSON dan penyajian aset web yang diizinkan
      security/               Pembuatan kode/token dan hashing
    tests/
      helpers/                Fixture HTTP untuk tes integrasi
      *.test.mjs              Tes API, konfigurasi, dan aset web
  web/
    public/
      index.html              Dashboard studio
      overlay.html            Browser Source OBS
      privacy.html            Halaman privasi
      assets/
        css/                  Style studio dan overlay
        js/                   Studio, overlay, dan koneksi SSE
  wear/
    app/src/main/
      java/dev/pulsestage/wear/
        data/                 API client dan penyimpanan koneksi
        service/              Sensor serta foreground service
        ui/                   Activity dan komponen tampilan jam
      res/                    Resource Android
    gradle/wrapper/           Gradle Wrapper
packages/
  protocol/
    src/                      Aturan URL pairing/overlay yang dipakai bersama
    tests/                    Tes helper protokol
infra/
  docker/Dockerfile           Image server dan web
  compose.yaml                Deployment aplikasi, Caddy, dan volume
  compose.nginx.yaml          Aplikasi untuk Nginx yang sudah ada di VPS
  Caddyfile                   Reverse proxy HTTPS
  nginx/                      Contoh virtual host Nginx
scripts/                      Simulator dan alat pengembangan
docs/                        Kontrak API dan panduan produksi
data/                        SQLite lokal (diabaikan Git)
artifacts/                   Ekspor lokal (diabaikan Git)
```

`packages/protocol` tidak mengimpor kode aplikasi, DOM, Node.js, atau kredensial. Server dan simulator mengimpor modul ini secara langsung; browser mengakses modul yang sama melalui `/shared/links.js`. Seluruh SQL berada di `apps/server/src/db`. API tetap di `app.mjs` agar alurnya mudah ditelusuri pada ukuran aplikasi saat ini. Tambahkan modul fitur ketika fitur baru membutuhkannya.

Tes diletakkan bersama pemilik kodenya. Jalankan `npm test` untuk semua tes, `npm run test:server` untuk backend, atau `npm run test:protocol` untuk helper bersama. `.editorconfig` dan `.gitattributes` menetapkan indentasi serta akhir baris lintas platform.

Folder Wear OS dibuka di Android Studio melalui **`apps/wear/`**. Namespace dan application ID tetap `dev.pulsestage.wear`; package kelas internal kini dipisahkan sesuai tanggung jawabnya. Data pairing yang tersimpan tidak dipindahkan. Output Gradle tetap berada di `apps/wear/app/build/`.

Deployment dengan Caddy dari root repository:

```sh
docker build -f infra/docker/Dockerfile -t pulsestage .
DOMAIN=pulse.domain-anda.com docker compose -f infra/compose.yaml up -d --build
```

Nama project Compose dipatok ke `deploy`, sesuai nama bawaan folder deployment sebelumnya, supaya volume SQLite dan Caddy yang sudah ada tetap dipakai setelah pemindahan. Jika sebelumnya menggunakan `-p` atau `COMPOSE_PROJECT_NAME`, gunakan nama yang sama saat menjalankan perintah baru.

File `.env`, database, keystore, cache, dan hasil build tidak dimasukkan ke Git. Folder `.gradle`, `.kotlin`, dan `app/build` milik instalasi lokal ikut berada di bawah `apps/wear` setelah pemindahan; jalankan build ulang dari lokasi baru untuk memperbarui output generated. URL pairing, URL OBS, dan endpoint API tetap sama.
