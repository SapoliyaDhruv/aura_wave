/**
 * Lyrics: synced lines when available, plain text otherwise.
 * Sources, in order: your own synced lyrics (saved by the previous app version),
 * built-in hand-timed lyrics, LRCLIB (synced), then YouTube Music (plain).
 */
import { load, save, cached, setCached } from '../lib/store.js';
import { plainLyrics } from './catalog.js';
import { titleSimilarity, artistOverlap, coreTitle, songKey } from '../../shared/match.js';

const OFFSETS_KEY = 'aw2_lyrics_offsets';

export function parseLrc(lrc) {
  if (!lrc) return [];
  let offset = 0;
  const off = lrc.match(/^\[offset:\s*([+-]?\d+)\s*\]/im);
  // LRC convention: a positive offset shows lyrics sooner.
  if (off) offset = -parseInt(off[1], 10) / 1000;
  const lines = [];
  for (const raw of lrc.split('\n')) {
    const stamps = [...raw.matchAll(/\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
    if (!stamps.length) continue;
    const text = raw.replace(/\[[^\]]*\]/g, '').trim();
    for (const m of stamps) {
      const ms = m[3] ? parseInt(m[3].padEnd(3, '0'), 10) : 0;
      lines.push({ time: Math.max(0, +m[1] * 60 + +m[2] + ms / 1000 + offset), text });
    }
  }
  lines.sort((a, b) => a.time - b.time);
  // Collapse empty instrumental gaps into a single pause marker.
  return lines.filter((l, i) => l.text || (lines[i - 1] && lines[i - 1].text));
}

const isSameSong = (track, title, artist) => titleSimilarity(track.title, title) >= 0.85 && (!artist || artistOverlap(track.artist, artist).any || artistOverlap(artist, track.artist).any);

function fromVault(track) {
  const vault = load('aurawave_synced_lyrics_vault', {});
  for (const entry of Object.values(vault)) {
    if (entry?.isAuraModel || !Array.isArray(entry?.lines) || entry.lines.length < 3) continue;
    if (isSameSong(track, entry.title, entry.artist)) return entry.lines.map((l) => ({ time: l.time, text: l.text }));
  }
  return null;
}

let builtinPromise;
async function fromBuiltin(track) {
  builtinPromise ||= import('../data/builtinLyrics.json').then((m) => m.default).catch(() => []);
  const list = await builtinPromise;
  return list.find((e) => isSameSong(track, e.title, e.artist))?.lines || null;
}

async function fromLrclib(track) {
  const title = coreTitle(track.title);
  const artist = track.artist.split(/\s*(?:,|&|\bfeat\.?|\bft\.?)\s*/i)[0];
  const tryJson = (url) => fetch(url, { signal: AbortSignal.timeout(7000) }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const candidates = [];
  if (track.duration) {
    const exact = await tryJson(`https://lrclib.net/api/get?track_name=${encodeURIComponent(title)}&artist_name=${encodeURIComponent(artist)}&duration=${Math.round(track.duration)}`);
    if (exact?.syncedLyrics) return { lines: parseLrc(exact.syncedLyrics), source: 'LRCLIB' };
    if (exact) candidates.push(exact);
  }
  const found = await tryJson(`https://lrclib.net/api/search?track_name=${encodeURIComponent(title)}&artist_name=${encodeURIComponent(artist)}`);
  if (Array.isArray(found)) candidates.push(...found);
  if (!candidates.length) {
    const loose = await tryJson(`https://lrclib.net/api/search?q=${encodeURIComponent(`${title} ${artist}`)}`);
    if (Array.isArray(loose)) candidates.push(...loose);
  }
  const ranked = candidates
    .filter((c) => !c.instrumental && (c.syncedLyrics || c.plainLyrics) && isSameSong(track, c.trackName, c.artistName))
    .map((c) => ({ c, s: (c.syncedLyrics ? 100 : 0) - (track.duration && c.duration ? Math.min(60, Math.abs(c.duration - track.duration)) : 10) }))
    .filter(({ c }) => !track.duration || !c.duration || Math.abs(c.duration - track.duration) < 20)
    .sort((a, b) => b.s - a.s);
  const best = ranked[0]?.c;
  if (!best) return null;
  if (best.syncedLyrics) return { lines: parseLrc(best.syncedLyrics), source: 'LRCLIB' };
  return { plain: best.plainLyrics, source: 'LRCLIB' };
}

/** Returns { lines?: [{time,text}], plain?: string, source } or null. */
export async function getLyrics(track) {
  if (!track) return null;
  const mine = fromVault(track);
  if (mine) return { lines: mine, source: 'Your synced lyrics' };
  const builtin = await fromBuiltin(track);
  if (builtin) return { lines: builtin, source: 'AuraWave' };

  const key = `aw2c:lrc:${songKey(track)}:${Math.round((track.duration || 0) / 10)}`;
  const hit = cached(key, 14 * 86400e3);
  if (hit) return hit;

  let result = await fromLrclib(track);
  if (!result?.lines?.length) {
    const ytm = await plainLyrics(track).catch(() => null);
    if (ytm?.plain && !result?.plain) result = { plain: ytm.plain, source: ytm.source || 'YouTube Music' };
  }
  if (result) setCached(key, result);
  return result;
}

/** Per-song timing nudge in seconds (positive = lyrics later). */
export function getOffset(track) {
  return load(OFFSETS_KEY, {})[songKey(track)] || 0;
}
export function setOffset(track, seconds) {
  const all = load(OFFSETS_KEY, {});
  all[songKey(track)] = Math.round(seconds * 10) / 10;
  save(OFFSETS_KEY, all);
}
