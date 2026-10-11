import { subscribe } from './live.js';
import { overlayTokenFromLocation, normalizeTheme } from '/shared/links.js';
const bpm = document.getElementById('bpm');
const status = document.getElementById('status');
const card = document.getElementById('overlay-card');
document.body.dataset.theme = normalizeTheme(new URLSearchParams(location.search).get('theme'));
let lastUpdate = 0;
function clear(text) { lastUpdate = 0; bpm.textContent = '--'; status.textContent = text; card.dataset.state = 'idle'; }
const token = overlayTokenFromLocation(location);
if (!token) clear('TAUTAN TIDAK VALID');
else subscribe(token, {
  onData(data) {
    lastUpdate = Date.now();
    document.getElementById('name').textContent = data.playerName;
    bpm.textContent = data.bpm ?? '--';
    status.textContent = { live: 'LIVE', waiting: 'MENUNGGU SENSOR', stopped: 'SESI BERHENTI' }[data.status] || 'MENUNGGU JAM';
    card.dataset.state = data.status;
  },
  onStatus(state) { if (state !== 'connected') clear(state === 'revoked' ? 'AKSES DICABUT' : 'TERPUTUS'); },
});
setInterval(() => { if (lastUpdate && Date.now() - lastUpdate > 15_000) clear('TERPUTUS'); }, 1000);
