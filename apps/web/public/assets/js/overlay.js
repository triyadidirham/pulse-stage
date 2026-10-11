import { subscribe } from './live.js';
import { overlayTokenFromLocation, normalizeTheme } from '/shared/links.js';
const bpm = document.getElementById('bpm');
const status = document.getElementById('status');
const card = document.getElementById('overlay-card');
document.body.dataset.theme = normalizeTheme(new URLSearchParams(location.search).get('theme'));
let snapshot;
let connection = 'connecting';
function render() {
  const usable = connection === 'connected' || connection === 'reconnecting';
  bpm.textContent = usable ? snapshot?.bpm ?? '--' : '--';
  card.dataset.state = connection === 'connected' ? snapshot?.status || 'idle' : 'idle';
  status.textContent = connection === 'connected'
    ? { live: 'LIVE', waiting: 'MENUNGGU SENSOR', stopped: 'SESI BERHENTI' }[snapshot?.status] || 'MENUNGGU JAM'
    : { connecting: 'MENGHUBUNGKAN', reconnecting: 'MENYAMBUNG ULANG', disconnected: 'TERPUTUS', revoked: 'AKSES DICABUT', invalid: 'TAUTAN TIDAK VALID' }[connection];
}
const token = overlayTokenFromLocation(location);
if (!token) { connection = 'invalid'; render(); }
else subscribe(token, {
  onData(data) {
    snapshot = data;
    document.getElementById('name').textContent = data.playerName;
    render();
  },
  onStatus(state) { connection = state; render(); },
});
