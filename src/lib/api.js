/**
 * API access. In the browser on a real domain we call our own /api routes.
 * Inside the Android app (capacitor/localhost origins) we call the deployed backend.
 * Set VITE_API_BASE to point the app at a different deployment.
 */
const DEPLOYED = (typeof import.meta !== 'undefined' && import.meta.env?.VITE_API_BASE) || 'https://aurawave-music.vercel.app';

export function apiBase() {
  if (typeof window === 'undefined') return DEPLOYED;
  const { origin, port } = window.location;
  if (typeof import.meta !== 'undefined' && import.meta.env?.DEV && port) return origin; // Vite dev server runs the /api handlers itself
  if (origin.startsWith('capacitor:') || /\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)) return DEPLOYED;
  return origin;
}

export class ApiError extends Error {}

export async function getJson(path, params = {}, { timeout = 9000, retries = 1 } = {}) {
  const qs = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString();
  const url = `${apiBase()}${path}${qs ? `?${qs}` : ''}`;
  let lastError;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(timeout) });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new ApiError(body.error || `Request failed (${res.status})`);
      return body;
    } catch (err) {
      lastError = err;
      if (err instanceof ApiError && !/did not respond/.test(err.message)) break;
      if (attempt < retries) await new Promise((r) => setTimeout(r, 400 * (attempt + 1)));
    }
  }
  if (typeof navigator !== 'undefined' && navigator.onLine === false) throw new ApiError('You are offline. Connect to the internet to stream music.');
  throw lastError instanceof ApiError ? lastError : new ApiError('Could not reach the music service. Check your connection and try again.');
}
