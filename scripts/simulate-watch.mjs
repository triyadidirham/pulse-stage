// Synthetic data for development only. A real watch uses the same API contract.
import { pairingPath } from '../packages/protocol/src/links.js';
const origin = process.env.SERVER_URL || 'http://127.0.0.1:3000';
let ingestToken;
let sessionId;
let sequence = 0;
let stopping = false;
async function api(path, method = 'GET', body) {
  const response = await fetch(`${origin}${path}`, {
    method, headers: { 'Content-Type': 'application/json', ...(ingestToken ? { Authorization: `Bearer ${ingestToken}` } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(8000),
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error);
  return data;
}
const registration = await api('/api/devices', 'POST');
ingestToken = registration.ingestToken;
console.log(`SIMULASI — bukan data sensor. Buka ${origin}${pairingPath(registration.pairingCode)}, lalu masukkan nama pemain "DEMO".`);
const until = registration.expiresAt;
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
while (!stopping) {
  try {
    if (!sessionId) {
      const state = await api('/api/device/status');
      if (state.paired) sessionId = (await api('/api/device/session', 'POST', { active: true })).sessionId;
      else if (Date.now() >= until) throw new Error('Kode kedaluwarsa. Jalankan ulang simulator.');
    }
    if (sessionId) await api('/api/device/samples', 'POST', { sessionId, sequence: sequence++, bpm: Math.round(85 + 12 * Math.sin(Date.now() / 5000)), measuredAt: Date.now() });
  } catch (error) { console.error(error.message); break; }
  await new Promise(resolve => setTimeout(resolve, 1000));
}
async function stop() {
  stopping = true;
  try { await api('/api/device/registration', 'DELETE'); } catch { /* Server may already be stopped. */ }
  process.exit(0);
}
await stop();
