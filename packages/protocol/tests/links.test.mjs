import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeTheme, overlayPath } from '../src/links.js';

test('overlay colors use a fixed palette and never change the access token', () => {
  for (const theme of ['mint', 'coral', 'ice']) assert.equal(normalizeTheme(theme), theme);
  for (const theme of [null, '', 'unknown', 'coral&token=other', '#bad']) {
    assert.equal(normalizeTheme(theme), 'mint');
    assert.equal(overlayPath('unchanged-token', theme), '/unchanged-token');
  }
  assert.equal(overlayPath('unchanged-token', 'coral'), '/unchanged-token?theme=coral');
});
