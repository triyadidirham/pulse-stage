import { DatabaseSync } from 'node:sqlite';

// Persistent device metadata only. Sessions and BPM remain in application memory.
export function createDeviceRepository(databasePath) {
  const db = new DatabaseSync(databasePath);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY, ingest_hash TEXT NOT NULL UNIQUE,
      pair_hash TEXT UNIQUE, pair_expires INTEGER,
      manage_hash TEXT UNIQUE, overlay_hash TEXT UNIQUE,
      player_name TEXT NOT NULL DEFAULT 'Player', created_at INTEGER NOT NULL
    );`);

  const queries = {
    byId: db.prepare('SELECT * FROM devices WHERE id = ?'),
    byPair: db.prepare('SELECT id FROM devices WHERE pair_hash = ?'),
    create: db.prepare('INSERT INTO devices(id, ingest_hash, created_at) VALUES (?, ?, ?)'),
    renewPair: db.prepare('UPDATE devices SET pair_hash = ?, pair_expires = ? WHERE id = ?'),
    claimPair: db.prepare(`UPDATE devices SET manage_hash = ?, overlay_hash = ?, player_name = ?, pair_hash = NULL, pair_expires = NULL
      WHERE pair_hash = ? AND pair_expires > ? AND manage_hash IS NULL RETURNING id`),
    rotateOverlay: db.prepare('UPDATE devices SET overlay_hash = ? WHERE id = ?'),
    remove: db.prepare('DELETE FROM devices WHERE id = ?'),
    prune: db.prepare('DELETE FROM devices WHERE manage_hash IS NULL AND created_at < ?'),
  };
  const credentials = Object.fromEntries(
    ['ingest', 'manage', 'overlay'].map(role => [role, db.prepare(`SELECT * FROM devices WHERE ${role}_hash = ?`)]),
  );

  return {
    findById: id => queries.byId.get(id),
    findByToken: (role, digest) => credentials[role].get(digest),
    hasPairingCode: digest => Boolean(queries.byPair.get(digest)),
    create: (id, ingestHash, createdAt) => queries.create.run(id, ingestHash, createdAt),
    renewPairing: (id, digest, expiresAt) => queries.renewPair.run(digest, expiresAt, id),
    // Claiming is atomic: two requests cannot consume the same pairing code.
    claimPairing: ({ codeHash, manageHash, overlayHash, playerName, now }) =>
      queries.claimPair.get(manageHash, overlayHash, playerName, codeHash, now),
    rotateOverlay: (id, digest) => queries.rotateOverlay.run(digest, id),
    delete: id => queries.remove.run(id),
    pruneUnpaired: before => queries.prune.run(before),
    close: () => db.close(),
  };
}
