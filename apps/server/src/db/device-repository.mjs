import { DatabaseSync } from 'node:sqlite';

// Persistent device metadata and hashed credentials only. BPM remains in memory.
export function createDeviceRepository(databasePath) {
  const db = new DatabaseSync(databasePath);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY, ingest_hash TEXT NOT NULL UNIQUE,
      pair_hash TEXT UNIQUE, pair_expires INTEGER,
      manage_hash TEXT UNIQUE, overlay_hash TEXT UNIQUE,
      player_name TEXT NOT NULL DEFAULT 'Player', created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS device_access (
      token_hash TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      role TEXT NOT NULL CHECK (role IN ('manage', 'overlay'))
    );
    CREATE INDEX IF NOT EXISTS device_access_device_role ON device_access(device_id, role);
    BEGIN IMMEDIATE;
    INSERT OR IGNORE INTO device_access SELECT manage_hash, id, 'manage' FROM devices WHERE manage_hash IS NOT NULL;
    INSERT OR IGNORE INTO device_access SELECT overlay_hash, id, 'overlay' FROM devices WHERE overlay_hash IS NOT NULL;
    UPDATE devices SET manage_hash = NULL, overlay_hash = NULL, pair_expires = NULL;
    COMMIT;`);

  // Legacy columns are retained for migration, but no longer used for authentication.
  const select = `SELECT devices.*, EXISTS(SELECT 1 FROM device_access
    WHERE device_id = devices.id AND role = 'manage') AS paired FROM devices`;
  const queries = {
    byId: db.prepare(`${select} WHERE id = ?`),
    byPair: db.prepare(`${select} WHERE pair_hash = ?`),
    create: db.prepare('INSERT INTO devices(id, ingest_hash, created_at) VALUES (?, ?, ?)'),
    resetPair: db.prepare('UPDATE devices SET pair_hash = ?, pair_expires = NULL WHERE id = ?'),
    name: db.prepare('UPDATE devices SET player_name = ? WHERE id = ?'),
    grant: db.prepare('INSERT INTO device_access(token_hash, device_id, role) VALUES (?, ?, ?)'),
    revoke: db.prepare('DELETE FROM device_access WHERE device_id = ?'),
    revokeOverlay: db.prepare("DELETE FROM device_access WHERE device_id = ? AND role = 'overlay'"),
    remove: db.prepare('DELETE FROM devices WHERE id = ?'),
    ingest: db.prepare(`${select} WHERE ingest_hash = ?`),
    access: db.prepare(`${select} WHERE id = (SELECT device_id FROM device_access WHERE role = ? AND token_hash = ?)`),
  };
  function transaction(action) {
    db.exec('BEGIN IMMEDIATE');
    try { const result = action(); db.exec('COMMIT'); return result; }
    catch (error) { db.exec('ROLLBACK'); throw error; }
  }
  return {
    findById: id => queries.byId.get(id),
    findByToken: (role, digest) => role === 'ingest' ? queries.ingest.get(digest) : queries.access.get(role, digest),
    hasPairingCode: digest => Boolean(queries.byPair.get(digest)),
    create: (id, ingestHash, createdAt) => queries.create.run(id, ingestHash, createdAt),
    resetPairing: (id, digest) => transaction(() => {
      queries.resetPair.run(digest, id);
      queries.revoke.run(id);
    }),
    // Reusing the code grants this browser access without rotating existing OBS links.
    claimPairing: ({ codeHash, manageHash, overlayHash, playerName }) => transaction(() => {
      const device = queries.byPair.get(codeHash);
      if (!device) return undefined;
      if (!device.paired) queries.name.run(playerName, device.id);
      queries.grant.run(manageHash, device.id, 'manage');
      queries.grant.run(overlayHash, device.id, 'overlay');
      return queries.byId.get(device.id);
    }),
    rotateOverlay: (id, digest) => transaction(() => {
      queries.revokeOverlay.run(id);
      queries.grant.run(digest, id, 'overlay');
    }),
    delete: id => queries.remove.run(id),
    close: () => db.close(),
  };
}
