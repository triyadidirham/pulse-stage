import { subscribe } from './live.js';
import { pairingCodeFromPath, overlayPath, normalizeTheme } from '/shared/links.js';
const $ = id => document.getElementById(id);
const key = 'pulsestage.pairing.v1';
const themeKey = 'pulsestage.theme.v1';
let incomingCode = pairingCodeFromPath(location.pathname);
let saved;
let theme = 'mint';
try { saved = JSON.parse(localStorage.getItem(key)); theme = normalizeTheme(localStorage.getItem(themeKey)); } catch { saved = null; }
let disconnect;
let overlayPrepared = false;
let copyTimer;
function message(text, error = false) {
  $('message').textContent = text;
  $('message').hidden = !text;
  $('message').classList.toggle('error', error);
}
function persist() { try { localStorage.setItem(key, JSON.stringify(saved)); } catch { message('Penyimpanan browser tidak tersedia. Salin tautan sebelum menutup halaman.', true); } }
async function api(path, { method = 'GET', body } = {}) {
  const response = await fetch(path, { method, headers: { 'Content-Type': 'application/json', ...(saved?.manageToken ? { Authorization: `Bearer ${saved.manageToken}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const data = await response.json();
  if (!response.ok) throw Object.assign(new Error(data.error || 'Permintaan gagal.'), { status: response.status });
  return data;
}
function updateSteps(live = false) {
  const paired = Boolean(saved && !incomingCode);
  $('step-pair').dataset.state = paired ? 'done' : 'current';
  $('step-obs').dataset.state = paired ? (overlayPrepared || live ? 'done' : 'current') : '';
  $('step-live').dataset.state = live ? 'done' : (paired && overlayPrepared ? 'current' : '');
}
function clearBpm(status, note) {
  $('preview-bpm').textContent = '--';
  $('preview-status').textContent = status;
  $('preview-card').dataset.state = 'idle';
  if (note) $('preview-note').textContent = note;
  updateSteps();
}
function updateTheme() {
  document.body.dataset.theme = theme;
  document.querySelectorAll('[data-theme-choice]').forEach(button => {
    button.setAttribute('aria-pressed', String(button.dataset.themeChoice === theme));
  });
  $('overlay-url').value = saved && !incomingCode ? `${location.origin}${overlayPath(saved.overlayToken, theme)}` : '';
  clearTimeout(copyTimer);
  $('copy-label').textContent = 'Salin tautan overlay';
}
function forget() {
  saved = null;
  overlayPrepared = false;
  try { localStorage.removeItem(key); } catch { /* Continue without persistent browser state. */ }
  render();
}
function render() {
  disconnect?.();
  const connected = Boolean(saved && !incomingCode);
  $('pair-form').hidden = connected;
  $('connected-panel').hidden = !connected;
  $('open-overlay').disabled = !connected;
  $('setup-title').textContent = connected ? 'Siap masuk ke stream.' : 'Mulai dari jam Anda.';
  $('setup-description').textContent = connected ? 'Koneksi Anda, kendali di pergelangan.' : 'Satu koneksi. Siap untuk setiap momen.';
  $('preview-name').textContent = connected ? saved.playerName : 'PLAYER';
  clearBpm('MENUNGGU JAM', connected ? 'Tekan Start di jam untuk membagikan BPM ke overlay.' : 'BPM asli muncul setelah jam terhubung dan sesi dimulai.');
  $('connection-status').textContent = connected ? 'Menghubungkan…' : 'Belum terhubung';
  $('connection-status').dataset.state = 'idle';
  updateTheme();
  if (!connected) return;
  $('connected-name').textContent = saved.playerName;
  let connectionState = 'connecting';
  disconnect = subscribe(saved.overlayToken, {
    onData(data) {
      $('preview-name').textContent = data.playerName;
      const connected = connectionState === 'connected';
      const usable = connected || connectionState === 'reconnecting';
      $('preview-bpm').textContent = usable ? data.bpm ?? '--' : '--';
      $('preview-status').textContent = connected
        ? { live: 'LIVE', waiting: 'MENUNGGU SENSOR', stopped: 'SESI BERHENTI' }[data.status] || 'MENUNGGU JAM'
        : connectionState === 'reconnecting' ? 'MENYAMBUNG ULANG' : 'TERPUTUS';
      $('preview-card').dataset.state = connected ? data.status : 'idle';
      $('preview-note').textContent = !connected ? 'Koneksi sedang dipulihkan. Pembacaan lama akan disembunyikan otomatis.' : {
        live: 'Sesi aktif. Angka di preview berasal dari sensor jam Anda.',
        waiting: 'Sesi aktif, menunggu pembacaan baru. Pastikan jam terpasang dengan nyaman.',
        stopped: 'Sesi berhenti. Tekan Start di jam saat Anda siap kembali.'
      }[data.status] || 'Menunggu pembacaan dari jam.';
      updateSteps(connected && data.status === 'live');
    },
    onStatus(status) {
      connectionState = status;
      $('connection-status').textContent = { connecting: 'Menghubungkan…', connected: 'Server terhubung', reconnecting: 'Menyambung ulang…', disconnected: 'Koneksi terputus', revoked: 'Akses dicabut' }[status];
      $('connection-status').dataset.state = status;
      if (status === 'connecting') clearBpm('MENGHUBUNGKAN', 'Menyiapkan koneksi ke jam…');
      if (status === 'reconnecting') {
        $('preview-status').textContent = 'MENYAMBUNG ULANG';
        $('preview-card').dataset.state = 'idle';
        $('preview-note').textContent = 'Koneksi sedang dipulihkan. Pembacaan lama akan disembunyikan otomatis.';
        updateSteps();
      }
      if (status === 'disconnected' || status === 'revoked') clearBpm(status === 'revoked' ? 'AKSES DICABUT' : 'TERPUTUS', status === 'revoked' ? 'Tautan overlay sudah tidak berlaku. Hubungkan ulang atau gunakan tautan terbaru.' : 'Koneksi terputus. Preview akan kembali saat server dapat dijangkau.');
      if (status === 'revoked') api('/api/manage').catch(error => {
        if (error.status === 401) { forget(); message('Pairing dicabut. Masukkan kode baru dari jam.'); }
      });
    },
  });
}
$('pair-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (incomingCode && saved && !confirm(`Hubungkan kode baru dan ganti perangkat aktif ${saved.playerName} di browser ini? Akses dan tautan OBS sebelumnya tetap berlaku.`)) return;
  const button = event.submitter || $('pair-form').querySelector('button[type="submit"]');
  button.disabled = true;
  $('pair-form').setAttribute('aria-busy', 'true');
  message('Menghubungkan jam…');
  try {
    saved = await api('/api/pair', { method: 'POST', body: { code: $('pair-code').value, playerName: $('player-name').value } });
    incomingCode = null;
    overlayPrepared = false;
    history.replaceState(null, '', '/');
    message('Jam terhubung. Salin tautan ke OBS, lalu tekan Start di jam.');
    persist(); render();
  } catch (error) { message(error.message, true); }
  finally { button.disabled = false; $('pair-form').removeAttribute('aria-busy'); }
});
$('copy-link').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText($('overlay-url').value);
    $('copy-label').textContent = 'Tautan tersalin ✓';
    clearTimeout(copyTimer);
    copyTimer = setTimeout(() => { $('copy-label').textContent = 'Salin tautan overlay'; }, 2500);
    overlayPrepared = true;
    updateSteps($('preview-card').dataset.state === 'live');
    message('Tautan disalin. Tempel di Browser Source OBS dengan ukuran 480 × 180.');
  } catch { $('overlay-url').focus(); $('overlay-url').select(); message('Tekan Ctrl+C atau salin teks yang dipilih.'); }
});
$('open-overlay').addEventListener('click', () => {
  if (saved && !incomingCode) window.open($('overlay-url').value, '_blank', 'noopener,noreferrer');
});
document.querySelectorAll('[data-theme-choice]').forEach(button => {
  button.addEventListener('click', () => {
    theme = normalizeTheme(button.dataset.themeChoice);
    try { localStorage.setItem(themeKey, theme); } catch { /* Theme still works for this visit. */ }
    updateTheme();
    if (saved && !incomingCode) message('Warna diperbarui. Salin tautan ini dan perbarui URL Browser Source di OBS.');
  });
});
$('rotate-link').addEventListener('click', async () => {
  if (!confirm('Semua tautan OBS lama untuk jam ini akan dicabut, termasuk dari browser lain. Kode pairing tetap berlaku. Buat tautan baru?')) return;
  $('rotate-link').disabled = true;
  try { Object.assign(saved, await api('/api/manage/rotate-overlay', { method: 'POST' })); overlayPrepared = false; persist(); render(); message('Tautan diganti. Perbarui URL di OBS.'); }
  catch (error) { message(error.message, true); }
  finally { $('rotate-link').disabled = false; }
});
$('delete-device').addEventListener('click', async () => {
  if (!confirm('Hapus pairing dan cabut semua akses ke jam ini?')) return;
  $('delete-device').disabled = true;
  try {
    await api('/api/manage', { method: 'DELETE' });
    forget(); message('Pairing dihapus. Hubungkan ulang dari aplikasi jam.');
  } catch (error) { if (error.status === 401) forget(); message(error.message, true); }
  finally { $('delete-device').disabled = false; }
});
render();
if (incomingCode) {
  $('pair-code').value = `${incomingCode.slice(0, 4)}-${incomingCode.slice(4)}`;
  message('Kode sudah terisi dari tautan. Masukkan nama, lalu tekan Hubungkan smartwatch.');
}
if (saved?.manageToken) {
  try {
    const device = await api('/api/manage');
    saved.playerName = device.playerName;
    $('connected-name').textContent = device.playerName;
  } catch (error) { if (error.status === 401) forget(); message(error.message, true); }
}
