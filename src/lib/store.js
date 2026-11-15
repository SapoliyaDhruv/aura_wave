/** Safe JSON localStorage helpers (storage can be full, blocked or corrupted). */
export function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw == null ? fallback : JSON.parse(raw);
  } catch {
    return fallback;
  }
}

export function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (err) {
    console.warn(`Could not save ${key}:`, err);
    return false;
  }
}

/** Time-limited cache entry in localStorage. */
export function cached(key, maxAgeMs) {
  const entry = load(key, null);
  if (!entry || typeof entry !== 'object' || !('t' in entry)) return null;
  if (Date.now() - entry.t > maxAgeMs) return null;
  return entry.v;
}

export function setCached(key, value) {
  if (!save(key, { t: Date.now(), v: value })) {
    // Storage full: drop old cache entries and try once more.
    try {
      Object.keys(localStorage).filter((k) => k.startsWith('aw2c:')).forEach((k) => localStorage.removeItem(k));
    } catch { /* ignore */ }
    save(key, { t: Date.now(), v: value });
  }
}
