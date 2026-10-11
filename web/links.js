const pairingPattern = /^\/([A-HJ-NP-Z2-9]{4})-?([A-HJ-NP-Z2-9]{4})\/?$/i;
const overlayPattern = /^\/([A-Za-z0-9_-]{43})\/?$/;

export function pairingCodeFromPath(pathname) {
  const match = pairingPattern.exec(pathname);
  return match ? `${match[1]}${match[2]}`.toUpperCase() : null;
}

export function pairingPath(code) {
  return `/${code.slice(0, 4)}-${code.slice(4)}`;
}

export function overlayTokenFromPath(pathname) {
  return overlayPattern.exec(pathname)?.[1] || null;
}

export function overlayTokenFromLocation(location) {
  const pathToken = overlayTokenFromPath(location.pathname);
  if (pathToken) return pathToken;
  // Preserve existing OBS sources that use the original fragment URL.
  if (location.pathname !== '/overlay') return null;
  const token = new URLSearchParams(location.hash.slice(1)).get('token');
  return /^[A-Za-z0-9_-]{43}$/.test(token || '') ? token : null;
}

export function normalizeTheme(theme) {
  return ['mint', 'coral', 'ice'].includes(theme) ? theme : 'mint';
}

export function overlayPath(token, theme = 'mint') {
  const selected = normalizeTheme(theme);
  return `/${token}${selected === 'mint' ? '' : `?theme=${selected}`}`;
}
