import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fixture } from './helpers/http.mjs';

test('all page assets and browser module imports can be loaded through HTTP', async t => {
  const f = await fixture(t);
  const modules = [];
  const visited = new Set();
  for (const page of ['/', '/overlay', '/privacy']) {
    const response = await f.transport(page);
    assert.equal(response.status, 200);
    const html = await response.text();
    for (const tag of html.matchAll(/<(?:script|link)\b[^>]*>/g)) {
      const reference = /(?:src|href)="([^"]+)"/.exec(tag[0]);
      if (reference) modules.push(new URL(reference[1], new URL(page, f.origin)).pathname);
    }
  }
  while (modules.length) {
    const path = modules.pop();
    if (visited.has(path)) continue;
    visited.add(path);
    const response = await f.transport(path);
    assert.equal(response.status, 200, `Missing browser asset: ${path}`);
    const mime = response.headers.get('content-type');
    assert.match(mime, path.endsWith('.css') ? /text\/css/ : /text\/javascript/);
    const source = await response.text();
    if (path.endsWith('.js')) {
      for (const dependency of source.matchAll(/\bfrom\s+['"]([^'"]+)['"]/g)) {
        modules.push(new URL(dependency[1], new URL(path, f.origin)).pathname);
      }
    }
  }
  for (const path of ['/assets/js/studio.js', '/assets/js/overlay.js', '/assets/js/live.js', '/shared/links.js', '/assets/css/studio.css', '/assets/css/overlay.css']) {
    assert.ok(visited.has(path), `Asset was not referenced by any page: ${path}`);
  }
});

test('existing asset URLs remain compatible with older browser tabs', async t => {
  const f = await fixture(t);
  for (const [oldPath, newPath] of Object.entries({
    '/app.js': '/assets/js/studio.js', '/overlay.js': '/assets/js/overlay.js',
    '/live.js': '/assets/js/live.js', '/links.js': '/shared/links.js',
    '/styles.css': '/assets/css/studio.css', '/overlay.css': '/assets/css/overlay.css',
  })) {
    const oldAsset = await f.transport(oldPath);
    const newAsset = await f.transport(newPath);
    assert.equal(oldAsset.status, 200);
    assert.equal(await oldAsset.text(), await newAsset.text());
  }
});

test('the public asset whitelist never exposes repository source or local data', async t => {
  const f = await fixture(t);
  for (const path of ['/.env', '/package.json', '/data/pulsestage.sqlite', '/apps/server/src/app.mjs', '/packages/protocol/src/links.js', '/assets/../../.env']) {
    assert.equal((await f.transport(path)).status, 404, path);
  }
});
