import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { createApp } from './app.mjs';

process.umask(0o077);
const databasePath = resolve(process.env.DATABASE_PATH || './data/pulsestage.sqlite');
mkdirSync(dirname(databasePath), { recursive: true });
const app = createApp({
  databasePath,
  publicOrigin: (process.env.PUBLIC_ORIGIN || '').replace(/\/$/, ''),
  production: process.env.NODE_ENV === 'production',
});
const host = process.env.HOST || '127.0.0.1';
const port = Number(process.env.PORT || 3000);
app.server.listen(port, host, () => console.log(`PulseStage: http://${host}:${port}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await app.close(); process.exit(0); });
