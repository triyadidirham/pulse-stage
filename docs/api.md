# Kontrak API v0.1.1

Semua request berisi JSON menggunakan `Content-Type: application/json`. Endpoint berizin memerlukan `Authorization: Bearer <token>`. Gunakan HTTPS pada produksi. Token acak 256 bit hanya dikembalikan saat pembuatan; database menyimpan SHA-256 token, bukan token asli.

| Endpoint | Kredensial | Perilaku |
|---|---|---|
| `POST /api/devices` | Tidak ada | Menghasilkan `deviceId`, `ingestToken`, `pairingCode`, `expiresAt` |
| `POST /api/pair` | Kode permanen sampai direset | Body `{code, playerName}`. Menghasilkan `manageToken`, `overlayToken`, `deviceId`, `playerName` |
| `GET /api/device/status` | Ingest | Menghasilkan `paired`, `playerName`, `pairingAvailable` |
| `POST /api/device/pairing` | Ingest | Reset kode; cabut semua token manage/overlay dan stream, hentikan sesi server, pertahankan ingest |
| `POST /api/device/session` | Ingest | `{active:true}` membuat sesi dan mengembalikan `sessionId`; `{active:false,sessionId}` mengakhiri sesi yang sama |
| `POST /api/device/samples` | Ingest | `{sessionId,sequence,bpm,measuredAt}` menerima pembacaan baru |
| `DELETE /api/device/registration` | Ingest | Menghapus registrasi dan mencabut seluruh kredensial |
| `GET /api/manage` | Manage | Status sesi dan pembacaan terbaru yang masih berlaku |
| `POST /api/manage/rotate-overlay` | Manage | Menghasilkan `overlayToken` baru, mencabut seluruh token overlay lama dan menutup stream lama |
| `DELETE /api/manage` | Manage | Menghapus registrasi dan mencabut seluruh kredensial |
| `GET /api/stream` | Overlay | SSE berisi snapshot saat ini dan pembaruan berikutnya |
| `GET /api/snapshot` | Overlay | Snapshot JSON yang sama untuk fallback polling; maksimal 1.200 request/menit per perangkat |
| `GET /healthz` | Tidak ada | Pemeriksaan proses untuk reverse proxy/container |

`measuredAt` adalah Unix epoch dalam milidetik. `expiresAt` selalu `null`: kode pairing dapat digunakan ulang tanpa batas waktu, sampai direset atau perangkat dihapus. `sequence` adalah bilangan bulat yang meningkat dalam satu sesi. Nilai BPM diterima dalam rentang teknis 20–300; batas ini bukan klasifikasi kondisi medis. Pembacaan lama, sesi tidak cocok, dan nomor urut duplikat ditolak. Respons sukses ingest adalah HTTP 202.

Snapshot SSE:

```json
{
  "playerName": "Dirham",
  "status": "live",
  "bpm": 95,
  "measuredAt": 1791712800000,
  "serverTime": 1791712800100,
  "validForMs": 14900
}
```

`status` adalah `live`, `waiting`, atau `stopped`. Saat tidak live, `bpm` dan `measuredAt` adalah `null`. Stream lama menerima `event: revoked` lalu ditutup ketika aksesnya dicabut. Client harus menghentikan SSE dan polling pada HTTP 401. `validForMs` adalah sisa umur BPM saat snapshot dibuat, maksimum 15.000 ms dan 0 bila tidak live. Client memakai timer monotonic untuk menghapus angka ketika sisa waktu habis, dan tidak memperpanjang deadline untuk pengukuran yang sama. Client lama masih dapat memakai `serverTime - measuredAt` sebagai umur pembacaan. Gangguan jaringan dicoba kembali dengan jeda bertahap. Polling memakai header Authorization yang sama, tanpa token pada query string dan tanpa cache. Respons terlambat dengan `serverTime` lebih tua daripada snapshot terakhir diabaikan.

Dashboard menggunakan `fetch()` streaming untuk mengirim header Authorization. URL OBS baru berbentuk `/<overlayToken>`; JavaScript membaca token dari path, lalu menggunakannya dalam header stream. URL sebelumnya `/overlay#token=...` tetap didukung. Keduanya memberi akses baca yang sama dan dicabut oleh rotasi token yang sama. Path URL baru mengandung kredensial: matikan atau redaksi access log untuk path tersebut. Tautan yang memakai fragment tidak mengirim token dalam request URL.

`GET /ABCD-EFGH` (atau `/ABCDEFGH`, case-insensitive) membuka dashboard dengan kode pairing terisi dari URL. GET tersebut tidak memvalidasi kode; pemberian akses tetap melalui `POST /api/pair` setelah pengguna mengisi nama dan menekan tombol. Kode delapan karakter tidak bisa digunakan sebagai token overlay. Membuka URL berisi token overlay hanya menyajikan halaman; akses data tetap diverifikasi oleh `/api/stream`. URL lama yang tokennya dicabut akan menampilkan status akses dicabut.

HTTP 400: input/kode tidak valid; 401: kredensial tidak sah; 403: origin browser tidak diizinkan; 409: pairing/sesi/urutan tidak cocok; 413: body lebih dari 4 KiB; 415: tipe body tidak sesuai; 429: batas request terlampaui.

Nomor perangkat tidak memberi akses baca atau tulis. Kode pairing adalah kredensial pengelolaan yang dapat digunakan ulang. Setiap pairing memberikan token manage/overlay baru tanpa mencabut token sebelumnya. Nama pertama dipertahankan sampai reset kode; `playerName` pada respons adalah nama yang tersimpan. Pengelolaan satu perangkat tersimpan per browser dalam prototipe. Tidak ada endpoint publik untuk mencari daftar perangkat.

## Reset dan migrasi

- `POST /api/device/pairing` hanya dapat dipanggil dengan token ingest dari jam. Respons `{pairingCode, expiresAt:null}`. Kode lama dan seluruh token manage/overlay langsung ditolak; koneksi SSE menerima `revoked`. Hubungkan web dengan kode baru sebelum Start lagi.
- Rotasi overlay hanya mencabut akses baca overlay. Jika kode pairing bocor, gunakan reset dari jam karena kode itu dapat membuat akses baru.
- Kredensial tetap berlaku sesudah restart server selama database dipertahankan. Sesi BPM tidak dipulihkan otomatis.
- Database versi lama dimigrasikan otomatis ke tabel `device_access`; token manage/overlay lama tetap sah. Kode pending lama menjadi tanpa kedaluwarsa. Kode yang sudah dikonsumsi versi lama tidak dapat dipulihkan dari hash; `pairingAvailable:false` meminta reset dari jam sekali untuk mendapatkan kode permanen.
- Cadangkan SQLite sebelum upgrade dan deploy server beserta APK baru. Kolom kredensial lama dikosongkan setelah migrasi: rollback server lama memerlukan pemulihan backup. APK lama yang menganggap `expiresAt` angka perlu diperbarui.
