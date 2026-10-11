import { readFile } from 'node:fs/promises';
import { pairingCodeFromPath, overlayTokenFromPath } from '../../../../packages/protocol/src/links.js';

const webRoot = new URL('../../../web/public/', import.meta.url);
const sharedLinks = new URL('../../../../packages/protocol/src/links.js', import.meta.url);
const assets = new Map([
  ['/', [new URL('index.html', webRoot), 'text/html']],
  ['/overlay', [new URL('overlay.html', webRoot), 'text/html']],
  ['/privacy', [new URL('privacy.html', webRoot), 'text/html']],
  ['/assets/js/studio.js', [new URL('assets/js/studio.js', webRoot), 'text/javascript']],
  ['/assets/js/overlay.js', [new URL('assets/js/overlay.js', webRoot), 'text/javascript']],
  ['/assets/js/live.js', [new URL('assets/js/live.js', webRoot), 'text/javascript']],
  ['/assets/css/studio.css', [new URL('assets/css/studio.css', webRoot), 'text/css']],
  ['/assets/css/overlay.css', [new URL('assets/css/overlay.css', webRoot), 'text/css']],
  ['/shared/links.js', [sharedLinks, 'text/javascript']],
]);

// Keep older open browser tabs working during a deployment.
for (const [legacy, current] of Object.entries({
  '/app.js': '/assets/js/studio.js', '/overlay.js': '/assets/js/overlay.js',
  '/live.js': '/assets/js/live.js', '/links.js': '/shared/links.js',
  '/styles.css': '/assets/css/studio.css', '/overlay.css': '/assets/css/overlay.css',
})) assets.set(legacy, assets.get(current));

export async function serveStatic(path, res) {
  const pairingLink = pairingCodeFromPath(path);
  const overlayLink = overlayTokenFromPath(path);
  let asset = assets.get(path);
  // Opening a URL serves the UI only. Pairing still requires an explicit POST.
  if (pairingLink || overlayLink) {
    res.setHeader('X-Robots-Tag', 'noindex, nofollow');
    asset = assets.get(pairingLink ? '/' : '/overlay');
  }
  if (!asset) return false;
  const [file, mime] = asset;
  res.setHeader('Content-Type', `${mime}; charset=utf-8`);
  res.end(await readFile(file));
  return true;
}
