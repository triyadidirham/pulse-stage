import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { createDeviceRepository } from './db/device-repository.mjs';
import { body, fail, json } from './http/json.mjs';
import { serveStatic } from './http/static.mjs';
import { code, hash, token } from './security/credentials.mjs';

const STALE_MS = 15_000;

export function createApp({ databasePath = ':memory:', publicOrigin = '', now = Date.now, production = false } = {}) {
  if (production && !/^https:\/\/[^/]+$/.test(publicOrigin)) {
    throw new Error('Production requires PUBLIC_ORIGIN=https://your-domain (without trailing slash).');
  }
  const devices = createDeviceRepository(databasePath);
  const live = new Map();
  const listeners = new Map();
  const limits = new Map();

  function limit(key, maximum, windowMs = 60_000) {
    let bucket = limits.get(key);
    if (!bucket || bucket.until <= now()) bucket = { count: 0, until: now() + windowMs };
    bucket.count += 1;
    limits.set(key, bucket);
    if (bucket.count > maximum) throw fail(429, 'Terlalu banyak percobaan. Coba lagi nanti.');
  }
  function authenticate(req, role) {
    const value = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization || '')?.[1];
    if (!value) throw fail(401, 'Akses tidak valid atau sudah dicabut.');
    const digest = hash(value);
    const device = devices.findByToken(role, digest);
    if (!device) throw fail(401, 'Akses tidak valid atau sudah dicabut.');
    return device;
  }
  function snapshot(device) {
    const session = live.get(device.id);
    const sample = session?.sample;
    const fresh = session?.active && sample && now() - sample.measuredAt <= STALE_MS && now() - sample.receivedAt <= STALE_MS;
    return {
      playerName: device.player_name,
      status: !session?.active ? 'stopped' : fresh ? 'live' : 'waiting',
      bpm: fresh ? sample.bpm : null,
      measuredAt: fresh ? sample.measuredAt : null,
      serverTime: now(),
    };
  }
  function broadcast(id) {
    const device = devices.findById(id);
    if (!device) return;
    const message = `data: ${JSON.stringify(snapshot(device))}\n\n`;
    for (const res of listeners.get(id) || []) {
      if (res.writableLength > 64 * 1024) res.destroy();
      else res.write(message);
    }
  }
  function revokeStreams(id) {
    for (const res of listeners.get(id) || []) res.end('event: revoked\ndata: {}\n\n');
    listeners.delete(id);
  }
  function newPair(id) {
    let pairingCode;
    do { pairingCode = code(); } while (devices.hasPairingCode(hash(pairingCode)));
    devices.resetPairing(id, hash(pairingCode));
    return { pairingCode, expiresAt: null };
  }
  const server = createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'self'; base-uri 'none'; form-action 'self'");
    if (production) res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    try {
      const path = new URL(req.url, 'http://localhost').pathname;
      const method = req.method;
      const origin = publicOrigin || `http://${req.headers.host}`;
      if (req.headers.origin && req.headers.origin !== origin) throw fail(403, 'Origin tidak diizinkan.');
      if (method === 'GET' && path === '/healthz') return json(res, 200, { ok: true });
      if (method === 'GET' && await serveStatic(path, res)) return;
      // Unauthenticated limits intentionally use the socket address, never client-supplied X-Forwarded-For.
      const ip = req.socket.remoteAddress;
      if (method === 'POST' && path === '/api/devices') {
        limit(`register:${ip}`, 20, 10 * 60_000);
        const id = randomUUID();
        const ingestToken = token();
        devices.create(id, hash(ingestToken), now());
        return json(res, 201, { deviceId: id, ingestToken, ...newPair(id) });
      }
      if (method === 'POST' && path === '/api/pair') {
        limit(`pair:${ip}`, 10);
        const data = await body(req);
        const normalized = typeof data.code === 'string' ? data.code.replace(/[\s-]/g, '').toUpperCase() : '';
        if (!/^[A-Z2-9]{8}$/.test(normalized)) throw fail(400, 'Masukkan 8 karakter kode dari jam.');
        const name = typeof data.playerName === 'string' ? data.playerName.trim() : '';
        if (!name || name.length > 32 || /[\x00-\x1f]/.test(name)) throw fail(400, 'Nama pemain harus 1–32 karakter.');
        const manageToken = token();
        const overlayToken = token();
        const device = devices.claimPairing({
          codeHash: hash(normalized), manageHash: hash(manageToken), overlayHash: hash(overlayToken),
          playerName: name,
        });
        if (!device) throw fail(400, 'Kode tidak valid atau sudah direset dari jam.');
        return json(res, 200, { deviceId: device.id, manageToken, overlayToken, playerName: device.player_name });
      }
      if (path.startsWith('/api/device/')) {
        const device = authenticate(req, 'ingest');
        limit(`device:${device.id}`, 240);
        if (method === 'GET' && path === '/api/device/status') {
          return json(res, 200, { paired: Boolean(device.paired), playerName: device.player_name, pairingAvailable: Boolean(device.pair_hash) });
        }
        if (method === 'POST' && path === '/api/device/pairing') {
          limit(`new-pair:${device.id}`, 5);
          const pairing = newPair(device.id);
          revokeStreams(device.id);
          live.delete(device.id);
          return json(res, 200, pairing);
        }
        if (method === 'DELETE' && path === '/api/device/registration') {
          revokeStreams(device.id);
          live.delete(device.id);
          devices.delete(device.id);
          return json(res, 200, { deleted: true });
        }
        if (!device.paired) throw fail(409, 'Hubungkan jam di web terlebih dahulu.');
        if (method === 'POST' && path === '/api/device/session') {
          const data = await body(req);
          if (typeof data.active !== 'boolean') throw fail(400, 'active harus boolean.');
          if (!data.active && (!live.has(device.id) || data.sessionId !== live.get(device.id).id)) throw fail(409, 'Sesi telah berubah.');
          const session = { active: data.active, id: randomUUID(), sequence: -1, sample: null };
          live.set(device.id, session);
          broadcast(device.id);
          return json(res, 200, { sessionId: session.id, active: session.active });
        }
        if (method === 'POST' && path === '/api/device/samples') {
          const data = await body(req);
          const session = live.get(device.id);
          if (!session?.active || data.sessionId !== session.id) throw fail(409, 'Sesi tidak aktif.');
          if (!Number.isFinite(data.bpm) || data.bpm < 20 || data.bpm > 300 ||
              !Number.isSafeInteger(data.sequence) || data.sequence < 0 ||
              !Number.isSafeInteger(data.measuredAt) || data.measuredAt > now() + 10_000 || now() - data.measuredAt > STALE_MS) {
            throw fail(400, 'Pembacaan tidak valid atau terlalu lama. Periksa waktu pada jam.');
          }
          if (data.sequence <= session.sequence) throw fail(409, 'Urutan pembacaan sudah diterima.');
          session.sequence = data.sequence;
          session.sample = { bpm: Math.round(data.bpm), measuredAt: data.measuredAt, receivedAt: now() };
          broadcast(device.id);
          return json(res, 202, { accepted: true });
        }
      }
      if (path.startsWith('/api/manage')) {
        const device = authenticate(req, 'manage');
        limit(`manage:${device.id}`, 120);
        if (method === 'GET' && path === '/api/manage') return json(res, 200, { deviceId: device.id, ...snapshot(device) });
        if (method === 'POST' && path === '/api/manage/rotate-overlay') {
          const overlayToken = token();
          devices.rotateOverlay(device.id, hash(overlayToken));
          revokeStreams(device.id);
          return json(res, 200, { overlayToken });
        }
        if (method === 'DELETE' && path === '/api/manage') {
          revokeStreams(device.id);
          live.delete(device.id);
          devices.delete(device.id);
          return json(res, 200, { deleted: true });
        }
      }
      if (method === 'GET' && path === '/api/stream') {
        const device = authenticate(req, 'overlay');
        const set = listeners.get(device.id) || new Set();
        if (set.size >= 10) throw fail(429, 'Terlalu banyak tampilan aktif.');
        listeners.set(device.id, set);
        set.add(res);
        res.writeHead(200, {
          'Content-Type': 'text/event-stream', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no',
        });
        res.write(`data: ${JSON.stringify(snapshot(device))}\n\n`);
        res.on('close', () => {
          set.delete(res);
          if (listeners.get(device.id) === set && set.size === 0) listeners.delete(device.id);
        });
        return;
      }
      throw fail(404, 'Halaman atau endpoint tidak ditemukan.');
    } catch (error) {
      if (!res.headersSent) json(res, error.status || 500, { error: error.status ? error.message : 'Server mengalami kendala.' });
      else res.end();
      if (!error.status) console.error('Request failed:', error.code || error.name);
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  const timer = setInterval(() => {
    for (const id of listeners.keys()) broadcast(id);
    for (const [key, value] of limits) if (value.until <= now()) limits.delete(key);
    for (const [id, session] of live) {
      if (session.sample && now() - session.sample.receivedAt > 60_000) session.sample = null;
    }
  }, 5_000);
  timer.unref();
  let closed = false;
  return {
    server,
    close: async () => {
      if (closed) return;
      closed = true;
      clearInterval(timer);
      // Shutdown is temporary, not an authorization revocation: clients must reconnect.
      for (const set of listeners.values()) for (const res of set) res.end();
      listeners.clear();
      await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
      devices.close();
    },
  };
}
