const FRESH_MS = 15_000;
const STREAM_IDLE_MS = 18_000;
const STREAM_QUIET_MS = 7000;

// The same transport and freshness rules serve the studio and OBS.
export function subscribe(token, { onData, onStatus }, {
  fetchImpl = (...args) => fetch(...args), now = () => performance.now(),
  setTimer = setTimeout, clearTimer = clearTimeout,
} = {}) {
  let stopped = false;
  let streamController;
  let pollController;
  let retryTimer, fallbackTimer, pollTimer, idleTimer, expiryTimer, connectionTimer;
  let retryDelay = 1000;
  let pollDelay = 1000;
  let polling = false;
  let status;
  let latest;
  let latestServerTime = -Infinity;
  let expiresAt = 0;
  const headers = { Authorization: `Bearer ${token}` };
  function report(value) {
    if (!stopped && status !== value) { status = value; onStatus(value); }
  }
  function stopPolling() {
    polling = false;
    clearTimer(pollTimer);
    pollTimer = undefined;
    pollController?.abort();
  }
  function cleanup() {
    stopped = true;
    streamController?.abort();
    stopPolling();
    for (const timer of [retryTimer, fallbackTimer, idleTimer, expiryTimer, connectionTimer]) clearTimer(timer);
  }
  function revoked() { report('revoked'); cleanup(); }
  function recovering() {
    if (status !== 'disconnected') report(latest ? 'reconnecting' : 'connecting');
  }
  function watchConnection() {
    clearTimer(connectionTimer);
    connectionTimer = setTimer(() => report('disconnected'), FRESH_MS);
  }
  function deliver(data, requestAge = 0) {
    if (stopped) return false;
    if (!data || !['live', 'waiting', 'stopped'].includes(data.status) || !Number.isFinite(data.serverTime)) {
      throw new Error('Invalid snapshot');
    }
    // A delayed SSE/poll response must not overwrite a newer snapshot.
    if (data.serverTime < latestServerTime) return false;
    let lifetime = 0;
    if (data.status === 'live') {
      if (!Number.isFinite(data.bpm) || data.bpm < 20 || data.bpm > 300 || !Number.isFinite(data.measuredAt)) {
        throw new Error('Invalid measurement');
      }
      // serverTime avoids dependence on the computer's wall clock. Older servers
      // omit validForMs; their measurement timestamp still supplies the age.
      lifetime = Math.max(0, Math.min(FRESH_MS, FRESH_MS - (data.serverTime - data.measuredAt),
        Number.isFinite(data.validForMs) ? data.validForMs : FRESH_MS) - requestAge);
    }
    const deadline = now() + lifetime;
    expiresAt = latest?.measuredAt === data.measuredAt && latest?.status === 'live'
      ? Math.min(expiresAt, deadline) : deadline;
    latestServerTime = data.serverTime;
    latest = data;
    clearTimer(expiryTimer);
    watchConnection();
    report('connected');
    const expire = () => {
      if (!stopped) onData({ ...data, status: 'waiting', bpm: null, measuredAt: null, validForMs: 0 });
    };
    if (data.status === 'live' && expiresAt <= now()) expire();
    else {
      if (!stopped) onData(data.status === 'live' ? data : { ...data, bpm: null, measuredAt: null });
      if (!stopped && data.status === 'live') expiryTimer = setTimer(expire, expiresAt - now());
    }
    return true;
  }
  function planFallback(ms) {
    clearTimer(fallbackTimer);
    fallbackTimer = setTimer(startPolling, ms);
  }
  function startPolling() {
    if (stopped || polling) return;
    polling = true;
    pollDelay = 1000;
    pollOnce();
  }
  async function pollOnce() {
    if (stopped || !polling) return;
    const controller = new AbortController();
    pollController = controller;
    const started = now();
    const timeout = setTimer(() => controller.abort(), 5000);
    try {
      const response = await fetchImpl('/api/snapshot', { headers, cache: 'no-store', signal: controller.signal });
      if (stopped || controller.signal.aborted || !polling) return;
      if (response.status === 401) { revoked(); return; }
      if (!response.ok) throw new Error('Snapshot unavailable');
      const data = await response.json();
      if (stopped || controller.signal.aborted || !polling) return;
      deliver(data, now() - started);
      pollDelay = 1000;
    } catch {
      if (!stopped && polling) { recovering(); pollDelay = Math.min(pollDelay * 2, 15_000); }
    } finally {
      clearTimer(timeout);
      if (pollController === controller) {
        pollController = undefined;
        if (!stopped && polling) pollTimer = setTimer(pollOnce, pollDelay);
      }
    }
  }
  async function connect() {
    if (stopped) return;
    const controller = new AbortController();
    streamController = controller;
    let reader;
    const touch = () => {
      clearTimer(idleTimer);
      idleTimer = setTimer(() => controller.abort(), STREAM_IDLE_MS);
    };
    try {
      touch();
      const response = await fetchImpl('/api/stream', {
        headers: { ...headers, Accept: 'text/event-stream' }, cache: 'no-store', signal: controller.signal,
      });
      if (stopped || controller.signal.aborted) return;
      if (response.status === 401) { revoked(); return; }
      if (!response.ok || !response.body) throw new Error('Stream unavailable');
      // Headers alone do not prove that a proxy is forwarding the stream.
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (!stopped && !controller.signal.aborted) {
        const { done, value } = await reader.read();
        if (stopped || controller.signal.aborted || done) break;
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
        if (buffer.length > 256 * 1024) throw new Error('Stream frame too large');
        let boundary;
        while (!stopped && (boundary = buffer.indexOf('\n\n')) !== -1) {
          const lines = buffer.slice(0, boundary).split('\n');
          buffer = buffer.slice(boundary + 2);
          if (lines.some(line => /^event:\s*revoked$/.test(line))) { revoked(); break; }
          const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
          if (data && deliver(JSON.parse(data)) && !stopped) {
            retryDelay = 1000;
            touch();
            stopPolling();
            planFallback(STREAM_QUIET_MS);
          }
        }
      }
    } catch { /* Polling covers streaming outages while SSE reconnects. */ }
    finally {
      clearTimer(idleTimer);
      controller.abort();
      reader?.cancel().catch(() => {});
      if (!stopped) {
        if (!polling || status !== 'connected') recovering();
        clearTimer(fallbackTimer);
        startPolling();
        retryTimer = setTimer(connect, retryDelay);
        retryDelay = Math.min(retryDelay * 2, 15_000);
      }
    }
  }
  report('connecting');
  watchConnection();
  planFallback(3000);
  connect();
  return cleanup;
}
