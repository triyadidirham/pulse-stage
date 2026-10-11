import { PassThrough, Readable } from 'node:stream';
import { createApp } from '../../src/app.mjs';

// Exercise the actual HTTP handler without requiring a listening socket.
// RUN_HTTP_TESTS=1 also exercises Node's network transport on an unrestricted host.
export async function inject(app, path, options = {}) {
  const req = Readable.from(options.body ? [Buffer.from(options.body)] : []);
  Object.assign(req, { url: path, method: options.method || 'GET', socket: { remoteAddress: '127.0.0.1' }, headers: { host: 'localhost', ...Object.fromEntries(Object.entries(options.headers || {}).map(([k, v]) => [k.toLowerCase(), v])) } });
  const res = new PassThrough();
  res.statusCode = 200;
  const headers = {};
  res.setHeader = (key, value) => { headers[key] = value; };
  res.writeHead = (status, values = {}) => { res.statusCode = status; Object.assign(headers, values); res.headersSent = true; };
  await app.server.listeners('request')[0](req, res);
  return new Response(Readable.toWeb(res), { status: res.statusCode, headers });
}

export async function fixture(t, options = {}) {
  let clock = Date.now();
  const app = createApp({ now: () => clock, ...options });
  if (process.env.RUN_HTTP_TESTS === '1') await new Promise((resolve, reject) => { app.server.once('error', reject); app.server.listen(0, '127.0.0.1', resolve); });
  const origin = process.env.RUN_HTTP_TESTS === '1' ? `http://127.0.0.1:${app.server.address().port}` : 'http://localhost';
  const transport = (path, options) => process.env.RUN_HTTP_TESTS === '1' ? fetch(`${origin}${path}`, options) : inject(app, path, options);
  t.after(() => app.close());
  async function request(path, { token, method = 'GET', body, headers } = {}) {
    const response = await transport(path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, data: await response.json() };
  }
  async function pair() {
    const { data: device } = await request('/api/devices', { method: 'POST' });
    const { data: owner } = await request('/api/pair', { method: 'POST', body: { code: device.pairingCode, playerName: 'Dirham' } });
    return { ...device, ...owner };
  }
  return { app, origin, request, pair, transport, time: () => clock, advance: ms => { clock += ms; } };
}
