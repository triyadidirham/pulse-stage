import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setImmediate as nextTurn } from 'node:timers/promises';
import { subscribe } from '../public/assets/js/live.js';

const settle = async () => { for (let i = 0; i < 3; i++) await nextTurn(); };
const frame = overrides => ({ playerName: 'Player', status: 'live', bpm: 87,
  serverTime: 1_000_000, measuredAt: 1_000_000, validForMs: 15_000, ...overrides });
function channel() {
  let controller;
  const response = new Response(new ReadableStream({ start(c) { controller = c; } }), {
    headers: { 'Content-Type': 'text/event-stream' },
  });
  return {
    response,
    send: data => controller.enqueue(new TextEncoder().encode(`data: ${JSON.stringify(data)}\n\n`)),
    raw: text => controller.enqueue(new TextEncoder().encode(text)),
    fail: () => controller.error(new Error('Connection reset')),
  };
}
function harness(t, fetchImpl) {
  let time = 0, id = 0;
  const timers = new Map(), requests = [], data = [], statuses = [];
  const stop = subscribe('secret-overlay-token', {
    onData: value => data.push(value), onStatus: value => statuses.push(value),
  }, {
    now: () => time,
    setTimer: (fn, ms) => { timers.set(++id, { fn, at: time + ms }); return id; },
    clearTimer: handle => timers.delete(handle),
    fetchImpl: (path, options) => { requests.push({ path, options, at: time }); return fetchImpl(path, options); },
  });
  t.after(stop);
  async function advance(ms) {
    const end = time + ms;
    await settle();
    for (;;) {
      const next = [...timers].sort((a, b) => a[1].at - b[1].at).find(([, timer]) => timer.at <= end);
      if (!next) break;
      const [key, timer] = next;
      timers.delete(key); time = timer.at; timer.fn();
      await settle();
    }
    time = end;
    await settle();
  }
  return { data, statuses, requests, timers, advance, stop };
}
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  return { promise, resolve, reject };
};

test('fragmented SSE and CRLF deliver updates immediately without polling a healthy stream', async t => {
  const stream = channel();
  const f = harness(t, () => Promise.resolve(stream.response));
  await settle();
  stream.raw('data: ' + JSON.stringify(frame()) + '\r');
  await settle();
  assert.equal(f.data.length, 0);
  stream.raw('\n\r\n');
  await settle();
  assert.equal(f.data.at(-1).bpm, 87);
  assert.deepEqual(f.statuses, ['connecting', 'connected']);
  await f.advance(5000);
  stream.send(frame({ serverTime: 1_005_000, measuredAt: 1_005_000, bpm: 88 }));
  await f.advance(5000);
  assert.equal(f.requests.length, 1);
  assert.equal(f.requests[0].options.cache, 'no-store');
  assert.equal(f.requests[0].options.headers.Authorization, 'Bearer secret-overlay-token');
});

test('buffered SSE headers do not report connected; polling starts after three seconds', async t => {
  const stream = channel();
  const f = harness(t, path => Promise.resolve(path === '/api/stream' ? stream.response : Response.json(frame())));
  await f.advance(2999);
  assert.deepEqual(f.statuses, ['connecting']);
  assert.equal(f.requests.length, 1);
  await f.advance(1);
  assert.equal(f.requests[1].path, '/api/snapshot');
  assert.equal(f.requests[1].options.cache, 'no-store');
  assert.equal(f.requests[1].options.headers.Authorization, 'Bearer secret-overlay-token');
  assert.equal(f.data.at(-1).bpm, 87);
  assert.equal(f.statuses.at(-1), 'connected');
  await f.advance(2000);
  assert.equal(f.requests.filter(r => r.path === '/api/snapshot').length, 3);
});

test('SSE can remain pending while snapshot polling connects', async t => {
  const pending = deferred();
  const f = harness(t, path => path === '/api/stream' ? pending.promise : Promise.resolve(Response.json(frame())));
  await f.advance(3000);
  assert.equal(f.data.at(-1).bpm, 87);
  assert.equal(f.statuses.at(-1), 'connected');
});

test('brief stream reset retains the last fresh reading while reconnecting', async t => {
  const stream = channel();
  const pending = deferred();
  let count = 0;
  const f = harness(t, path => path === '/api/stream' && count++ === 0 ? Promise.resolve(stream.response) : pending.promise);
  await settle(); stream.send(frame()); await settle();
  stream.fail(); await settle();
  assert.equal(f.statuses.at(-1), 'reconnecting');
  assert.equal(f.data.at(-1).bpm, 87);
  await f.advance(15_000);
  assert.equal(f.data.at(-1).bpm, null);
  assert.equal(f.statuses.at(-1), 'disconnected');
});

test('SSE recovery cancels polling and ignores a late response from the cancelled request', async t => {
  const stream = channel(), pending = deferred();
  const f = harness(t, path => path === '/api/stream' ? Promise.resolve(stream.response) : pending.promise);
  await f.advance(3000);
  const poll = f.requests.find(r => r.path === '/api/snapshot');
  stream.send(frame({ bpm: 110, serverTime: 1_003_000, measuredAt: 1_003_000 }));
  await settle();
  assert.equal(poll.options.signal.aborted, true);
  pending.resolve(Response.json(frame({ bpm: 70 })));
  await settle();
  assert.equal(f.data.at(-1).bpm, 110);
  await f.advance(6000);
  assert.equal(f.requests.filter(r => r.path === '/api/snapshot').length, 1);
});

test('older buffered SSE cannot overwrite a newer polling snapshot or stop fallback', async t => {
  const stream = channel();
  const f = harness(t, path => Promise.resolve(path === '/api/stream' ? stream.response
    : Response.json(frame({ bpm: 99, serverTime: 1_005_000, measuredAt: 1_005_000 }))));
  await f.advance(3000);
  stream.send(frame({ bpm: 70 })); await settle();
  assert.equal(f.data.at(-1).bpm, 99);
  await f.advance(1000);
  assert.equal(f.requests.filter(r => r.path === '/api/snapshot').length, 2);
});

test('measurement expiry uses its age, and repeated heartbeats never extend the same reading', async t => {
  const stream = channel();
  const f = harness(t, () => Promise.resolve(stream.response));
  await settle();
  const old = frame({ measuredAt: 990_000, validForMs: 5000 });
  stream.send(old); await settle();
  await f.advance(4000);
  // Even a duplicated heartbeat that claims the original lifetime cannot renew it.
  stream.send(old); await settle();
  await f.advance(1000);
  assert.equal(f.data.at(-1).status, 'waiting');
  assert.equal(f.data.at(-1).bpm, null);
  assert.equal(f.statuses.at(-1), 'connected');
  stream.send(old); await settle();
  assert.equal(f.data.at(-1).bpm, null);
});

test('older servers without validForMs still expire readings independently of the browser clock', async t => {
  const stream = channel();
  const f = harness(t, () => Promise.resolve(stream.response));
  await settle();
  stream.send(frame({ serverTime: 200_000, measuredAt: 186_000, validForMs: undefined })); await settle();
  assert.equal(f.data.at(-1).bpm, 87);
  await f.advance(1000);
  assert.equal(f.data.at(-1).bpm, null);
});

test('revoked polling credentials stop both transports immediately', async t => {
  const stream = channel();
  const f = harness(t, path => Promise.resolve(path === '/api/stream' ? stream.response : new Response('{}', { status: 401 })));
  await f.advance(3000);
  assert.equal(f.statuses.at(-1), 'revoked');
  assert.equal(f.requests[0].options.signal.aborted, true);
  const count = f.requests.length;
  await f.advance(60_000);
  assert.equal(f.requests.length, count);
  assert.equal(f.timers.size, 0);
});

test('revocation within SSE clears timers and prevents reconnecting', async t => {
  const stream = channel();
  const f = harness(t, () => Promise.resolve(stream.response));
  await settle(); stream.send(frame()); await settle();
  stream.raw('event: revoked\r\ndata: {}\r\n\r\n'); await settle();
  assert.equal(f.statuses.at(-1), 'revoked');
  assert.equal(f.timers.size, 0);
  await f.advance(60_000);
  assert.equal(f.requests.length, 1);
});

test('unsubscribing while fetch is pending prevents old subscription callbacks', async t => {
  const pending = deferred();
  const f = harness(t, () => pending.promise);
  f.stop();
  const stream = channel();
  stream.send(frame());
  pending.resolve(stream.response); await settle();
  await f.advance(60_000);
  assert.equal(f.data.length, 0);
  assert.deepEqual(f.statuses, ['connecting']);
  assert.equal(f.requests.length, 1);
  assert.equal(f.timers.size, 0);
});

test('malformed stream switches to fallback without displaying invalid data', async t => {
  const stream = channel();
  const f = harness(t, path => Promise.resolve(path === '/api/stream' ? stream.response : Response.json(frame({ bpm: 95 }))));
  await settle(); stream.raw('data: not-json\n\n'); await settle();
  assert.equal(f.data.at(-1).bpm, 95);
  assert.equal(f.statuses.at(-1), 'connected');
  assert.ok(f.requests.some(r => r.path === '/api/snapshot'));
});

test('browser transport renders actual server samples and stops when access is revoked', async t => {
  const { fixture } = await import('../../server/tests/helpers/http.mjs');
  const server = await fixture(t);
  const d = await server.pair();
  // The harness uses one fixed credential; map it to this fixture's real overlay token.
  const f = harness(t, (path, options) => server.transport(path, {
    ...options, headers: { ...options.headers, Authorization: `Bearer ${d.overlayToken}` },
  }));
  await settle();
  assert.equal(f.data.at(-1).status, 'stopped');
  const { data: session } = await server.request('/api/device/session', {
    method: 'POST', token: d.ingestToken, body: { active: true },
  });
  await settle();
  assert.equal(f.data.at(-1).status, 'waiting');
  await server.request('/api/device/samples', { method: 'POST', token: d.ingestToken,
    body: { sessionId: session.sessionId, sequence: 1, bpm: 96, measuredAt: server.time() - 2000 },
  });
  await settle();
  assert.equal(f.data.at(-1).bpm, 96);
  assert.equal(f.data.at(-1).validForMs, 13_000);
  await server.request('/api/manage/rotate-overlay', { method: 'POST', token: d.manageToken });
  await settle();
  assert.equal(f.statuses.at(-1), 'revoked');
  assert.equal(f.timers.size, 0);
});

test('fallback renders actual server updates when streaming fails, then honors Stop and reset', async t => {
  const { fixture } = await import('../../server/tests/helpers/http.mjs');
  const server = await fixture(t);
  const d = await server.pair();
  const f = harness(t, (path, options) => path === '/api/stream'
    ? Promise.reject(new Error('Proxy interrupted stream'))
    : server.transport(path, { ...options, headers: { Authorization: `Bearer ${d.overlayToken}` } }));
  await settle();
  assert.equal(f.data.at(-1).status, 'stopped');
  const { data: session } = await server.request('/api/device/session', {
    method: 'POST', token: d.ingestToken, body: { active: true },
  });
  await server.request('/api/device/samples', { method: 'POST', token: d.ingestToken,
    body: { sessionId: session.sessionId, sequence: 1, bpm: 102, measuredAt: server.time() },
  });
  await f.advance(1000);
  assert.equal(f.data.at(-1).bpm, 102);
  assert.equal(f.statuses.at(-1), 'connected');
  await server.request('/api/device/session', { method: 'POST', token: d.ingestToken,
    body: { active: false, sessionId: session.sessionId },
  });
  await f.advance(1000);
  assert.equal(f.data.at(-1).status, 'stopped');
  assert.equal(f.data.at(-1).bpm, null);
  await server.request('/api/device/pairing', { method: 'POST', token: d.ingestToken });
  await f.advance(1000);
  assert.equal(f.statuses.at(-1), 'revoked');
  assert.equal(f.timers.size, 0);
});
