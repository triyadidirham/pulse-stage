# Kontrak API v0.1

Semua request berisi JSON menggunakan `Content-Type: application/json`. Endpoint berizin memerlukan `Authorization: Bearer <token>`. Gunakan HTTPS pada produksi. Token acak 256 bit hanya dikembalikan saat pembuatan; database menyimpan SHA-256 token, bukan token asli.

| Endpoint | Kredensial | Perilaku |
|---|---|---|
| `POST /api/devices` | Tidak ada | Menghasilkan `deviceId`, `ingestToken`, `pairingCode`, `expiresAt` |
| `POST /api/pair` | Kode sekali pakai | Body `{code, playerName}`. Menghasilkan `manageToken`, `overlayToken`, `deviceId`, `playerName` |
| `GET /api/device/status` | Ingest | Menghasilkan `paired`, `playerName` |
| `POST /api/device/pairing` | Ingest | Kode baru untuk perangkat yang belum diklaim |
| `POST /api/device/session` | Ingest | `{active:true}` membuat sesi dan mengembalikan `sessionId`; `{active:false,sessionId}` mengakhiri sesi yang sama |
| `POST /api/device/samples` | Ingest | `{sessionId,sequence,bpm,measuredAt}` menerima pembacaan baru |
| `DELETE /api/device/registration` | Ingest | Menghapus registrasi dan mencabut seluruh kredensial |
| `GET /api/manage` | Manage | Status sesi dan pembacaan terbaru yang masih berlaku |
| `POST /api/manage/rotate-overlay` | Manage | Menghasilkan `overlayToken` baru dan menutup stream lama |
| `DELETE /api/manage` | Manage | Menghapus registrasi dan mencabut seluruh kredensial |
| `GET /api/stream` | Overlay | SSE berisi snapshot saat ini dan pembaruan berikutnya |
| `GET /healthz` | Tidak ada | Pemeriksaan proses untuk reverse proxy/container |

`measuredAt` dan `expiresAt` adalah Unix epoch dalam milidetik. `sequence` adalah bilangan bulat yang meningkat dalam satu sesi. Nilai BPM diterima dalam rentang teknis 20–300; batas ini bukan klasifikasi kondisi medis. Pembacaan lama, sesi tidak cocok, dan nomor urut duplikat ditolak. Respons sukses ingest adalah HTTP 202.

Snapshot SSE:

```json
{
  "playerName": "Dirham",
  "status": "live",
  "bpm": 95,
  "measuredAt": 1791712800000,
  "serverTime": 1791712800100
}
```

`status` adalah `live`, `waiting`, atau `stopped`. Saat tidak live, `bpm` dan `measuredAt` adalah `null`. Stream lama menerima `event: revoked` lalu ditutup ketika aksesnya dicabut. Client harus menghentikan percobaan otomatis pada HTTP 401. Gangguan jaringan dapat dicoba kembali dengan jeda bertahap.

Dashboard menggunakan `fetch()` streaming untuk mengirim header Authorization. URL OBS baru berbentuk `/<overlayToken>`; JavaScript membaca token dari path, lalu menggunakannya dalam header stream. URL sebelumnya `/overlay#token=...` tetap didukung. Keduanya memberi akses baca yang sama dan dicabut oleh rotasi token yang sama. Path URL baru mengandung kredensial: matikan atau redaksi access log untuk path tersebut. Tautan yang memakai fragment tidak mengirim token dalam request URL.

`GET /ABCD-EFGH` (atau `/ABCDEFGH`, case-insensitive) membuka dashboard dengan kode pairing terisi dari URL. GET tersebut tidak memvalidasi atau mengonsumsi kode; klaim tetap melalui `POST /api/pair` setelah pengguna mengisi nama dan menekan tombol. Kode delapan karakter tidak bisa digunakan sebagai token overlay. Membuka URL berisi token overlay hanya menyajikan halaman; akses data tetap diverifikasi oleh `/api/stream`. URL lama yang tokennya dicabut akan menampilkan status akses dicabut.

HTTP 400: input/kode tidak valid; 401: kredensial tidak sah; 403: origin browser tidak diizinkan; 409: pairing/sesi/urutan tidak cocok; 413: body lebih dari 4 KiB; 415: tipe body tidak sesuai; 429: batas request terlampaui.

Nomor perangkat tidak memberi akses baca atau tulis. Kepemilikan ditetapkan dengan kode pairing satu kali. Pengelolaan satu perangkat tersimpan per browser dalam prototipe. Tidak ada endpoint publik untuk mencari daftar perangkat.
