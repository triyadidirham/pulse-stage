import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createApp } from './app.mjs';
import { loadConfig } from './config.mjs';

process.umask(0o077);
const config = loadConfig();
mkdirSync(dirname(config.databasePath), { recursive: true });
const app = createApp(config);
const { host, port } = config;
app.server.listen(port, host, () => console.log(`PulseStage: http://${host}:${port}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await app.close(); process.exit(0); });
