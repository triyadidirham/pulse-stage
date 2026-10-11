import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Keep the existing database location when starting the server from another folder.
const defaultDatabasePath = fileURLToPath(new URL('../../../data/pulsestage.sqlite', import.meta.url));

export function loadConfig(env = process.env) {
  return {
    databasePath: env.DATABASE_PATH ? resolve(env.DATABASE_PATH) : defaultDatabasePath,
    publicOrigin: (env.PUBLIC_ORIGIN || '').replace(/\/$/, ''),
    production: env.NODE_ENV === 'production',
    host: env.HOST || '127.0.0.1',
    port: Number(env.PORT || 3000),
  };
}
