export function subscribe(token, { onData, onStatus }) {
  let stopped = false;
  let controller;
  let retryTimer;
  let idleTimer;
  let delay = 1000;
  async function connect() {
    if (stopped) return;
    controller = new AbortController();
    const touch = () => {
      clearTimeout(idleTimer);
      idleTimer = setTimeout(() => controller.abort(), 18_000);
    };
    try {
      touch();
      const response = await fetch('/api/stream', { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal });
      if (response.status === 401) { onStatus('revoked'); stopped = true; return; }
      if (!response.ok) throw new Error('Stream unavailable');
      onStatus('connected');
      delay = 1000;
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (!stopped) {
        const { done, value } = await reader.read();
        if (done) break;
        touch();
        buffer += decoder.decode(value, { stream: true });
        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          const event = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          if (event.startsWith('event: revoked')) { stopped = true; onStatus('revoked'); await reader.cancel(); break; }
          const data = event.split('\n').find(line => line.startsWith('data: '));
          if (data) onData(JSON.parse(data.slice(6)));
        }
      }
    } catch { /* Reconnect below; never retain stale BPM on a broken stream. */ }
    finally {
      clearTimeout(idleTimer);
      if (!stopped) {
        onStatus('disconnected');
        retryTimer = setTimeout(connect, delay);
        delay = Math.min(delay * 2, 15_000);
      }
    }
  }
  connect();
  return () => { stopped = true; controller?.abort(); clearTimeout(retryTimer); clearTimeout(idleTimer); };
}
