import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { fixture, inject } from './helpers/http.mjs';
import { createApp } from '../src/app.mjs';
import { pairingCodeFromPath, pairingPath, overlayPath, overlayTokenFromLocation } from '../../../packages/protocol/src/links.js';

test('pair, stream BPM, expire stale data, stop, rotate, and delete credentials', async t => {
  const f = await fixture(t);
  const d = await f.pair();
  assert.equal((await f.request('/api/device/status', { token: d.ingestToken })).data.paired, true);
  const replay = await f.request('/api/pair', { method: 'POST', body: { code: d.pairingCode, playerName: 'Hijack' } });
  assert.equal(replay.status, 400);
  const abort = new AbortController();
  const response = await f.transport('/api/stream', { headers: { Authorization: `Bearer ${d.overlayToken}` }, signal: abort.signal });
  assert.equal(response.status, 200);
  const reader = response.body.getReader();
  const initial = new TextDecoder().decode((await reader.read()).value);
  assert.match(initial, /"status":"stopped"/);
  const session = await f.request('/api/device/session', { method: 'POST', token: d.ingestToken, body: { active: true } });
  const sample = { bpm: 97, measuredAt: f.time(), sequence: 1, sessionId: session.data.sessionId };
  assert.equal((await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: sample })).status, 202);
  let updates = '';
  while (!updates.includes('"bpm":97')) updates += new TextDecoder().decode((await reader.read()).value);
  assert.match(updates, /"status":"live"/);
  assert.equal((await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: sample })).status, 409);
  f.advance(16_000);
  const stale = await f.request('/api/manage', { token: d.manageToken });
  assert.equal(stale.data.bpm, null);
  assert.equal(stale.data.status, 'waiting');
  assert.equal((await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: { ...sample, sequence: 2 } })).status, 400);
  await f.request('/api/device/session', { method: 'POST', token: d.ingestToken, body: { active: false, sessionId: session.data.sessionId } });
  assert.equal((await f.request('/api/manage', { token: d.manageToken })).data.status, 'stopped');
  assert.equal((await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: { ...sample, measuredAt: f.time(), sequence: 3 } })).status, 409);
  const rotated = await f.request('/api/manage/rotate-overlay', { method: 'POST', token: d.manageToken });
  assert.notEqual(rotated.data.overlayToken, d.overlayToken);
  let revoked = '';
  while (true) { const next = await reader.read(); if (next.done) break; revoked += new TextDecoder().decode(next.value); }
  assert.match(revoked, /event: revoked/);
  abort.abort();
  assert.equal((await f.request('/api/stream', { token: d.overlayToken })).status, 401);
  await f.request('/api/manage', { method: 'DELETE', token: d.manageToken });
  assert.equal((await f.request('/api/device/status', { token: d.ingestToken })).status, 401);
  assert.equal((await f.request('/api/manage', { token: d.manageToken })).status, 401);
  assert.equal((await f.request('/api/stream', { token: rotated.data.overlayToken })).status, 401);
});

test('credentials cannot cross roles and pairing expires', async t => {
  const f = await fixture(t);
  const d = await f.pair();
  for (const auth of [d.ingestToken, d.overlayToken]) assert.equal((await f.request('/api/manage', { token: auth })).status, 401);
  for (const auth of [d.manageToken, d.overlayToken]) assert.equal((await f.request('/api/device/status', { token: auth })).status, 401);
  assert.equal((await f.request('/api/stream', { token: d.manageToken })).status, 401);
  const { data: pending } = await f.request('/api/devices', { method: 'POST' });
  assert.equal((await f.request('/api/device/session', { method: 'POST', token: pending.ingestToken, body: { active: true } })).status, 409);
  f.advance(600_001);
  assert.equal((await f.request('/api/pair', { method: 'POST', body: { code: pending.pairingCode, playerName: 'Player' } })).status, 400);
  const renewed = await f.request('/api/device/pairing', { method: 'POST', token: pending.ingestToken });
  assert.equal((await f.request('/api/pair', { method: 'POST', body: { code: renewed.data.pairingCode, playerName: 'Player' } })).status, 200);
});

test('invalid inputs, cross-origin requests and brute-force pairing are rejected', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/api/devices', { method: 'POST', headers: { Origin: 'https://attacker.invalid' } })).status, 403);
  const d = await f.pair();
  const { data: session } = await f.request('/api/device/session', { method: 'POST', token: d.ingestToken, body: { active: true } });
  for (const bpm of [-1, 0, 301, '95', null]) {
    const result = await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: { sessionId: session.sessionId, bpm, sequence: 1, measuredAt: f.time() } });
    assert.equal(result.status, 400);
  }
  for (let n = 0; n < 9; n++) await f.request('/api/pair', { method: 'POST', body: { code: 'AAAAAAAA', playerName: 'P' } });
  assert.equal((await f.request('/api/pair', { method: 'POST', body: { code: 'AAAAAAAA', playerName: 'P' } })).status, 429);
});

test('database persists pairing without raw credentials or BPM history', async t => {
  const dir = await mkdtemp(join(tmpdir(), 'pulsestage-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const databasePath = join(dir, 'state.sqlite');
  const f = await fixture(t, { databasePath });
  const d = await f.pair();
  const { data: session } = await f.request('/api/device/session', { method: 'POST', token: d.ingestToken, body: { active: true } });
  await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: { sessionId: session.sessionId, bpm: 99, sequence: 1, measuredAt: f.time() } });
  const db = new DatabaseSync(databasePath, { readOnly: true });
  const stored = JSON.stringify(db.prepare('SELECT * FROM devices').all());
  for (const secret of [d.ingestToken, d.manageToken, d.overlayToken, d.pairingCode]) assert.ok(!stored.includes(secret));
  assert.ok(!stored.includes('bpm'));
  db.close();
  const second = await fixture(t, { databasePath });
  assert.equal((await second.request('/api/device/status', { token: d.ingestToken })).data.paired, true);
  assert.equal((await second.request('/api/manage', { token: d.manageToken })).data.bpm, null);
});

test('production refuses an unset or insecure public origin', () => {
  assert.throws(() => createApp({ production: true }), /PUBLIC_ORIGIN/);
  assert.throws(() => createApp({ production: true, publicOrigin: 'http://example.com' }), /PUBLIC_ORIGIN/);
});

test('a delayed Stop from an older watch session cannot stop a newer session', async t => {
  const f = await fixture(t);
  const d = await f.pair();
  const start = () => f.request('/api/device/session', { method: 'POST', token: d.ingestToken, body: { active: true } });
  const first = await start();
  const second = await start();
  assert.equal((await f.request('/api/device/session', { method: 'POST', token: d.ingestToken, body: { active: false, sessionId: first.data.sessionId } })).status, 409);
  assert.equal((await f.request('/api/device/samples', { method: 'POST', token: d.ingestToken, body: { sessionId: second.data.sessionId, sequence: 1, measuredAt: f.time(), bpm: 80 } })).status, 202);
});

test('normal server shutdown ends streams without revoking overlay credentials', async () => {
  const app = createApp();
  try {
    const reg = await (await inject(app, '/api/devices', { method: 'POST' })).json();
    const owner = await (await inject(app, '/api/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code: reg.pairingCode, playerName: 'Player' }) })).json();
    const response = await inject(app, '/api/stream', { headers: { Authorization: `Bearer ${owner.overlayToken}` } });
    const reading = response.text();
    await app.close();
    assert.doesNotMatch(await reading, /event: revoked/);
  } catch (error) { await app.close(); throw error; }
});

test('opening a pairing URL prefills a code without claiming the watch', async t => {
  const f = await fixture(t);
  const { data: device } = await f.request('/api/devices', { method: 'POST' });
  const path = pairingPath(device.pairingCode);
  for (const variant of [path, path.toLowerCase(), `/${device.pairingCode}/`]) {
    const response = await f.transport(variant);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /text\/html/);
    assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
    assert.equal(response.headers.get('x-robots-tag'), 'noindex, nofollow');
    const html = await response.text();
    assert.match(html, /id="pair-form"/);
    assert.ok(!html.includes(device.ingestToken));
    assert.equal(pairingCodeFromPath(variant), device.pairingCode);
  }
  assert.equal((await f.request('/api/device/status', { token: device.ingestToken })).data.paired, false);
  const claim = await f.request('/api/pair', { method: 'POST', body: { code: pairingCodeFromPath(path), playerName: 'URL player' } });
  assert.equal(claim.status, 200);
  assert.equal((await f.request('/api/pair', { method: 'POST', body: { code: device.pairingCode, playerName: 'Second claim' } })).status, 400);
});

test('direct OBS URLs and existing fragment URLs authenticate with the same read-only token', async t => {
  const f = await fixture(t);
  const device = await f.pair();
  for (const path of [overlayPath(device.overlayToken), overlayPath(device.overlayToken, 'coral'), overlayPath(device.overlayToken, 'ice'), '/overlay']) {
    const response = await f.transport(path);
    assert.equal(response.status, 200);
    assert.match(await response.text(), /class="overlay-body"/);
  }
  for (const path of [overlayPath(device.overlayToken), overlayPath(device.overlayToken, 'coral'), overlayPath(device.overlayToken, 'ice'), `${overlayPath(device.overlayToken)}/`, `/overlay?theme=ice#token=${device.overlayToken}`]) {
    const token = overlayTokenFromLocation(new URL(path, f.origin));
    assert.equal(token, device.overlayToken);
    const response = await f.transport('/api/stream', { headers: { Authorization: `Bearer ${token}` } });
    const reader = response.body.getReader();
    assert.match(new TextDecoder().decode((await reader.read()).value), /"playerName":"Dirham"/);
    await reader.cancel();
  }
  await f.request('/api/manage/rotate-overlay', { method: 'POST', token: device.manageToken });
  assert.equal((await f.request('/api/stream', { token: overlayTokenFromLocation(new URL(overlayPath(device.overlayToken), f.origin)) })).status, 401);
  assert.equal(overlayTokenFromLocation(new URL(pairingPath(device.pairingCode), f.origin)), null);
  assert.equal(overlayTokenFromLocation(new URL(`/privacy#token=${device.overlayToken}`, f.origin)), null);
  assert.equal((await f.transport('/api/ABCD-EFGH')).status, 404);
  assert.equal((await f.transport('/ABCD-EFGH/extra')).status, 404);
});
