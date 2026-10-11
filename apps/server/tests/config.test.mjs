import { test } from 'node:test';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../src/config.mjs';

test('starting from another folder preserves the existing default database location', () => {
  const workingDirectory = process.cwd();
  try {
    // Synchronous scope: restore cwd before another test or request can run.
    process.chdir(tmpdir());
    const config = loadConfig({});
    assert.equal(config.databasePath, fileURLToPath(new URL('../../../data/pulsestage.sqlite', import.meta.url)));
    assert.equal(config.host, '127.0.0.1');
    assert.equal(config.port, 3000);
  } finally { process.chdir(workingDirectory); }
});

test('explicit deployment environment overrides the local defaults', () => {
  const config = loadConfig({ DATABASE_PATH: './custom/state.sqlite', HOST: '0.0.0.0', PORT: '4000', PUBLIC_ORIGIN: 'https://pulse.example.com/', NODE_ENV: 'production' });
  assert.equal(config.databasePath, resolve('./custom/state.sqlite'));
  assert.equal(config.publicOrigin, 'https://pulse.example.com');
  assert.equal(config.production, true);
  assert.equal(config.host, '0.0.0.0');
  assert.equal(config.port, 4000);
});
