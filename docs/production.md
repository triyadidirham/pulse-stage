# Produksi dan distribusi Play Store

Dokumen ini menjelaskan cara membawa prototipe ke layanan publik. Konfigurasi deployment sudah tersedia, tetapi server publik dan listing Play Store belum dibuat. Jangan menganggap APK debug atau halaman privasi prototipe sudah siap dipublikasikan.

## Arsitektur layanan publik

Jam → HTTPS → server PulseStage → HTTPS/SSE → dashboard atau Browser Source OBS.

Server berada di VPS/layanan cloud yang dapat dijangkau jam melalui internet. Website dan API menggunakan origin yang sama. PC OBS tidak harus satu jaringan dengan jam dan tidak perlu menjalankan Node.js sendiri. Jaringan jam dapat memakai Wi-Fi atau koneksi yang disediakan Wear OS; kemampuan konektivitas sebenarnya perlu diuji pada perangkat.

Build release menanam URL server HTTPS. Pengguna tidak mengetik IP server: pasang aplikasi, buka tautan `https://domain/ABCD-EFGH` yang ditampilkan di jam, isi nama dan hubungkan, lalu salin URL OBS berbentuk `https://domain/kode-akses-overlay`. Satu pemain menggunakan satu registrasi jam; banyak pemain dapat memakai server yang sama.

Prototipe menggunakan Node.js 24, HTTP bawaan, SQLite, serta SSE satu arah. WebSocket tidak diperlukan karena BPM hanya mengalir dari server ke tampilan. Tidak ada riwayat BPM yang ditulis ke database.

## Deployment awal satu server

Pilih konfigurasi sesuai VPS: `infra/compose.yaml` menyediakan Caddy dan membutuhkan port 80/443 yang tersedia; jika VPS sudah memakai Nginx, gunakan [mode Nginx](#deployment-dengan-nginx-yang-sudah-ada) di bawah.

1. Siapkan satu VPS Linux dan domain/subdomain, misalnya `pulse.domain-anda.com`. Arahkan record DNS **A** ke IPv4 VPS. Jika menggunakan record **AAAA**, pastikan IPv6 tersebut juga mengarah ke VPS dan bisa dijangkau. Izinkan koneksi TCP 80/443 ke Caddy; port API 3000 tetap berada dalam jaringan container.
2. Instal Docker Engine beserta Compose plugin. Untuk Ubuntu, ikuti [instalasi resmi Docker](https://docs.docker.com/engine/install/ubuntu/). Pastikan akun deployment dapat menjalankan `docker version` dan `docker compose version`.
3. Pastikan perubahan lokal sudah di-push ke GitHub, lalu clone repository pada VPS:

   ```sh
   git clone https://github.com/triyadidirham/pulse-stage.git
   cd pulse-stage
   cp infra/.env.example infra/.env
   nano infra/.env
   ```

   Jika repository privat, siapkan autentikasi GitHub pada VPS, misalnya deploy key baca-saja, lalu gunakan URL clone SSH. Tidak perlu menyalin private key laptop ke VPS.

   Ganti isi `infra/.env` dengan hostname sebenarnya, tanpa `https://` atau path:

   ```dotenv
   DOMAIN=pulse.domain-anda.com
   ```

   Template ini khusus deployment Compose. `.env.example` di root tetap digunakan untuk menjalankan Node.js secara langsung. File `infra/.env` diabaikan Git dan tidak ikut masuk ke image aplikasi karena Dockerfile hanya menyalin sumber aplikasi serta modul protokol.

4. Dari root repository, validasi konfigurasi kemudian jalankan layanan:

   ```sh
   docker compose --env-file infra/.env -f infra/compose.yaml config --quiet
   docker compose --env-file infra/.env -f infra/compose.yaml up -d --build
   docker compose --env-file infra/.env -f infra/compose.yaml ps
   ```

   Compose menyiapkan `PUBLIC_ORIGIN=https://domain` secara otomatis. Image aplikasi menetapkan `NODE_ENV=production`. Caddy menerbitkan dan memperbarui sertifikat HTTPS setelah domain serta jaringan dapat diverifikasi, dan meneruskan SSE tanpa buffering. Lihat [persyaratan Automatic HTTPS Caddy](https://caddyserver.com/docs/automatic-https).

5. Verifikasi dari komputer Anda:

   ```sh
   curl -fsS https://pulse.domain-anda.com/healthz
   ```

   Respons yang diharapkan adalah `{"ok":true}`. Buka `https://pulse.domain-anda.com`, lakukan pairing, lalu uji Start/Stop dari jam dan Browser Source OBS. Untuk melihat status saat troubleshooting, jalankan pada VPS:

   ```sh
   docker compose --env-file infra/.env -f infra/compose.yaml logs --tail=100 app proxy
   ```

6. Build aplikasi jam dari komputer pengembangan dengan domain yang sama sebelum pengujian:

   ```sh
   cd apps/wear
   ./gradlew assembleDebug -PbackendUrl=https://pulse.domain-anda.com
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Pada instalasi debug yang pernah menyimpan IP lokal secara manual, reset pairing lalu ubah **Server pengembangan** ke domain HTTPS. Nilai yang tersimpan di jam memiliki prioritas atas URL bawaan APK debug. Untuk distribusi Play Store, ikuti bagian paket rilis di bawah.

7. Tentukan pencadangan volume `pulse_data`, pemulihan, monitoring error dan uptime, pembaruan image/dependensi, serta kebijakan penghapusan backup. Log jangan mencatat header Authorization, body BPM, kode pairing, atau path yang mengandung kode akses overlay. Konfigurasi Caddy contoh tidak mengaktifkan access log; jika menambah proxy/CDN/analitik, redaksi atau matikan pencatatan URL rahasia tersebut.

Compose ini adalah konfigurasi awal yang belum diuji deployment dalam sesi pengembangan. Tetapkan versi/digest image yang sudah Anda uji sebelum rilis operasional.

File Compose sekarang berada di `infra/compose.yaml`. Nama project tetap `deploy` untuk mempertahankan volume bawaan dari lokasi lama. Untuk instalasi yang memakai nama project khusus, pertahankan nilai `-p` atau `COMPOSE_PROJECT_NAME` sebelumnya saat menjalankan Compose dari lokasi baru.

### Memperbarui deployment

Setelah perubahan sudah di-push, jalankan dari root repository pada VPS:

```sh
git pull --ff-only origin main
docker compose --env-file infra/.env -f infra/compose.yaml up -d --build
docker compose --env-file infra/.env -f infra/compose.yaml ps
```

Volume database dan sertifikat tetap dipakai saat container dibuat ulang. Restart menghentikan sesi BPM yang ada di memori, sedangkan pairing tetap tersimpan di SQLite. Periksa kembali `/healthz` dan sesi jam setelah update. Jangan gunakan `down -v` untuk update rutin karena opsi tersebut menghapus named volumes.

## Deployment dengan Nginx yang sudah ada

Gunakan mode ini jika Nginx dipasang langsung pada VPS, termasuk melalui panel hosting. Nginx tetap melayani port 80/443; aplikasi Docker tersedia hanya melalui `127.0.0.1:3012`. File `infra/compose.nginx.yaml` mengambil konfigurasi service `app` dari file utama dan tidak menyertakan service Caddy. Jalankan file ini **sendiri**, bukan digabung dengan `-f infra/compose.yaml`.

1. Setelah perubahan terbaru sudah di-push, jalankan `git pull --ff-only origin main` pada VPS. Isi `/opt/pulse-stage/infra/.env` (sesuaikan lokasi clone) dengan:

   ```dotenv
   DOMAIN=ps.triyadi.dev
   APP_PORT=3012
   ```

   `DOMAIN` tetap hostname tanpa skema/path. `APP_PORT` adalah port localhost pada VPS; port proses di dalam container tetap 3000. Variabel `PORT` pada `.env` root tidak dipakai untuk pemetaan ini.

2. Jika sempat menjalankan mode Caddy dan mendapat error `failed to bind host port 0.0.0.0:80`, hapus hanya service Caddy dari proyek PulseStage:

   ```sh
   cd /opt/pulse-stage
   docker compose --env-file infra/.env -f infra/compose.yaml rm --stop --force proxy
   ```

   Perintah ini tidak menghapus volume SQLite maupun sertifikat. Untuk instalasi baru yang belum pernah menjalankan Caddy, lewati langkah ini. Tidak perlu menghentikan Nginx atau situs lain di VPS.

3. Validasi lalu jalankan aplikasi menggunakan file mode Nginx:

   ```sh
   docker compose --env-file infra/.env -f infra/compose.nginx.yaml config --quiet
   docker compose --env-file infra/.env -f infra/compose.nginx.yaml up -d --build
   docker compose --env-file infra/.env -f infra/compose.nginx.yaml ps
   curl -fsS http://127.0.0.1:3012/healthz
   ```

   Respons health check adalah `{"ok":true}`. Nama project tetap `deploy` dan database memakai volume `deploy_pulse_data` yang sama. Jika sebelumnya menggunakan nama project khusus, pertahankan nilai `-p`/`COMPOSE_PROJECT_NAME` tersebut.

4. Atur virtual host `ps.triyadi.dev` pada Nginx/panel agar meneruskan request ke `http://127.0.0.1:3012`. Contoh lengkap tersedia di `infra/nginx/pulsestage.conf.example`. Untuk virtual host HTTPS yang sudah ada, pertahankan sertifikat dan konfigurasi TLS-nya, lalu gunakan direktif `access_log off;` dan blok berikut pada host tersebut:

   ```nginx
   location / {
       proxy_pass http://127.0.0.1:3012;
       proxy_http_version 1.1;
       proxy_set_header Host $host;
       proxy_set_header X-Real-IP $remote_addr;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_set_header X-Forwarded-Proto $scheme;
       proxy_set_header Connection "";
       proxy_buffering off;
       proxy_cache off;
       proxy_read_timeout 75s;
       gzip off;
   }
   ```

   `proxy_buffering off` meneruskan pembaruan SSE segera setelah diterima. `access_log off` mencegah pencatatan URL overlay di access log. Error log/proxy lain tetap perlu ditinjau agar tidak menyimpan URL rahasia. Lihat [dokumentasi proxy Nginx](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering).

   Jika memakai panel, simpan pengaturan melalui panel agar tidak ditimpa saat panel memperbarui konfigurasi. Pada instalasi Nginx systemd biasa, validasi sebelum reload:

   ```sh
   sudo nginx -t && sudo systemctl reload nginx
   ```

5. Jika domain belum memiliki virtual host, gunakan template HTTP di atas sebagai konfigurasi situs baru dan ganti `pulse.example.com` dengan hostname Anda. Aktifkan HTTPS melalui panel atau, jika Certbot dan plugin Nginx sudah terpasang, jalankan:

   ```sh
   sudo certbot --nginx -d ps.triyadi.dev --redirect
   ```

   Domain harus mengarah ke VPS dan port 80/443 dapat dijangkau. Plugin Nginx memakai Nginx yang sudah berjalan; tidak perlu memakai mode standalone yang membutuhkan port 80 kosong. Ikuti [panduan Certbot Nginx](https://eff-certbot.readthedocs.io/en/stable/using.html#nginx) untuk instalasi/pembaruan sertifikat. Contoh `.conf` HTTP merupakan langkah bootstrap; layanan pengguna tetap memakai HTTPS sesuai `PUBLIC_ORIGIN`.

6. Setelah HTTPS aktif, verifikasi `curl -fsS https://ps.triyadi.dev/healthz`, pairing, dan pembaruan BPM di OBS. Seluruh build jam menggunakan `-PbackendUrl=https://ps.triyadi.dev`, tanpa port 3012.

Untuk update rutin, selalu pakai file mode Nginx:

```sh
git pull --ff-only origin main
docker compose --env-file infra/.env -f infra/compose.nginx.yaml up -d --build
docker compose --env-file infra/.env -f infra/compose.nginx.yaml logs --tail=100 app
```

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
- Gunakan izin paling sempit. Manifest menyediakan `BODY_SENSORS` hingga API 35 dan `READ_HEART_RATE` untuk API 36+. Aplikasi memulai health foreground service dari activity yang terlihat. Tidak menggunakan Health Connect. Health Services memakai sesi `WORKOUT` umum untuk membaca BPM; jelaskan penggunaan sesi aktivitas ini pada consent dan deklarasi Play. Aplikasi tidak meminta GPS atau menyimpan riwayat olahraga.
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

Kode pairing dapat dipakai berulang kali tanpa kedaluwarsa; reset menolak kode dan seluruh akses lama; perangkat A tidak dapat mengirim data milik B; token overlay tidak dapat menulis data; pergantian URL memutus subscriber lama; Stop meniadakan BPM; sample lama tidak tampil ulang setelah reconnect; tidak ada riwayat BPM atau token mentah dalam database/log; reset kode dari jam mencabut seluruh akses browser/OBS, sedangkan Hapus koneksi di jam atau Hapus pairing di web menghapus registrasi; build release tidak mengizinkan HTTP; dashboard/overlay dan antarmuka jam dapat dipakai pada ukuran layar sebenarnya.


### Upgrade kode permanen dan Health Services (v0.1.1)

Cadangkan database SQLite beserta WAL secara konsisten sebelum upgrade. Deploy image backend/web terbaru tanpa menghapus volume data, lalu build/install APK baru. Schema kredensial dimigrasikan otomatis; akses browser dan OBS yang sudah ada tetap berlaku. Kode yang belum diklaim menjadi tanpa kedaluwarsa. Jika kode lama sudah dikonsumsi, gunakan Reset kode akses dari APK baru dan pairing ulang; langkah ini juga mencabut URL OBS lama. Jangan rollback backend lama dengan database yang sudah dimigrasikan: pulihkan backup versi sebelumnya terlebih dahulu.

Uji Start/Stop pada jam, termasuk layar mati dan Stop ketika Health Services masih memulai sesi. Tutup sesi olahraga aplikasi lain sebelum Start. Lihat log `PulseStageSensor` dan `PulseStageHealth` serta petunjuk build di README. APK lama tidak mendukung respons pairing `expiresAt:null`.
