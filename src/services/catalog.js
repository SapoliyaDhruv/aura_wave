/**
 * Catalog: every way the app finds music goes through here.
 * Search, exact-recording resolution, radio, artists and plain lyrics,
 * with caching so repeat visits are instant and work through brief outages.
 */
import { getJson } from '../lib/api.js';
import { cached, setCached, load, save } from '../lib/store.js';
import { songKey, scoreCandidate, titleSimilarity, versionFlags } from '../../shared/match.js';

const HOUR = 3600e3;
const PINS_KEY = 'aw2_pins';
const inflight = new Map();

function once(key, fn) {
  if (inflight.has(key)) return inflight.get(key);
  const p = fn().finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

/** Ensures every track object has the fields the UI relies on. */
export function normalizeTrack(t) {
  if (!t) return null;
  return {
    id: t.id || (t.youtubeId ? `yt-${t.youtubeId}` : `t-${songKey(t)}`),
    youtubeId: t.youtubeId || null,
    title: t.title || 'Untitled',
    artist: t.artist || 'Unknown artist',
    artists: Array.isArray(t.artists) ? t.artists : [],
    album: t.album || '',
    duration: Number(t.duration) || 0,
    coverUrl: t.coverUrl || '',
    kind: t.kind || (t.youtubeId ? 'song' : 'unresolved'),
    ...(t.audioUrl ? { audioUrl: t.audioUrl } : {}),
  };
}

// ---------- search ----------

export async function search(query) {
  const q = query.trim();
  if (!q) return { songs: [], videos: [], artists: [] };
  const key = `aw2c:search:${q.toLowerCase()}`;
  const hit = cached(key, 6 * HOUR);
  if (hit) return hit;
  const res = await once(key, () => getJson('/api/music/search', { q }));
  const out = {
    songs: (res.songs || []).map(normalizeTrack),
    videos: (res.videos || []).map(normalizeTrack),
    artists: res.artists || [],
  };
  setCached(key, out);
  return out;
}

/**
 * Heuristic: did this query find the song through its lyrics rather than its title?
 * True when the query is phrase-like and shares few words with title/artist.
 */
export function isLyricsMatch(query, track) {
  const words = query.toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/).filter((w) => w.length > 1);
  if (words.length < 3) return false;
  const hay = `${track.title} ${track.artist} ${track.album}`.toLowerCase();
  const hits = words.filter((w) => hay.includes(w)).length;
  return hits / words.length < 0.5;
}

// ---------- exact recording ----------

export function getPin(track) {
  return load(PINS_KEY, {})[songKey(track)] || null;
}

/** Remember the listener's choice of recording for a song, forever. */
export function pinRecording(track, chosen) {
  const pins = load(PINS_KEY, {});
  pins[songKey(track)] = { youtubeId: chosen.youtubeId, title: chosen.title, artist: chosen.artist, coverUrl: chosen.coverUrl, duration: chosen.duration };
  save(PINS_KEY, pins);
}

/**
 * Returns the track with a verified youtubeId, or null when no exact match exists.
 * `exclude` skips recordings that failed to play (e.g. embedding disabled).
 */
export async function resolveTrack(track, { exclude = [] } = {}) {
  if (!track) return null;
  const pin = getPin(track);
  if (pin && !exclude.includes(pin.youtubeId)) return { ...track, youtubeId: pin.youtubeId, coverUrl: track.coverUrl || pin.coverUrl };
  if (track.youtubeId && !exclude.includes(track.youtubeId)) return track;

  const key = `aw2c:resolve:${songKey(track)}:${Math.round((track.duration || 0) / 5)}:${exclude.join(',')}`;
  const hit = cached(key, 7 * 24 * HOUR);
  if (hit?.youtubeId) return { ...track, ...hit };
  if (cached(`${key}:miss`, 12 * HOUR)) return null;

  const res = await once(key, () => getJson('/api/music/resolve', {
    title: track.title,
    artist: track.artist,
    duration: track.duration || undefined,
    exclude: exclude.join(',') || undefined,
  }, { timeout: 12000 }));
  if (!res.track) {
    setCached(`${key}:miss`, true);
    return null;
  }
  const patch = {
    youtubeId: res.track.youtubeId,
    coverUrl: track.coverUrl || res.track.coverUrl,
    album: track.album || res.track.album,
    duration: track.duration || res.track.duration,
  };
  setCached(key, patch);
  return { ...track, ...patch };
}

/** Candidate recordings for the "Not the right song?" picker. */
export async function alternatives(track) {
  const [byArtist, byTitle] = await Promise.all([
    search(`${track.title} ${track.artist.split(',')[0]}`),
    search(track.title).catch(() => ({ songs: [], videos: [] })),
  ]);
  const seen = new Set([track.youtubeId]);
  const wanted = versionFlags(track.title);
  return [...byArtist.songs, ...byArtist.videos, ...byTitle.songs]
    .filter((t) => t.youtubeId && !seen.has(t.youtubeId) && seen.add(t.youtubeId))
    .filter((t) => titleSimilarity(track.title, t.title) >= 0.5)
    .filter((t) => [...versionFlags(t.title)].every((f) => wanted.has(f) || !['instrumental', 'karaoke', 'slowed', 'spedup', 'spatial', 'clip'].includes(f)))
    .map((t) => ({ t, s: scoreCandidate({ title: track.title, artist: track.artist, duration: track.duration }, t).score }))
    .sort((a, b) => b.s - a.s)
    .slice(0, 12)
    .map(({ t }) => t);
}

// ---------- radio, artists, lyrics ----------

export async function radio(track) {
  const resolved = track.youtubeId ? track : await resolveTrack(track).catch(() => null);
  if (!resolved?.youtubeId) return [];
  const key = `aw2c:radio:${resolved.youtubeId}`;
  const hit = cached(key, 12 * HOUR);
  if (hit) return hit;
  const res = await once(key, () => getJson('/api/music/radio', { videoId: resolved.youtubeId }));
  const tracks = (res.tracks || []).map(normalizeTrack);
  setCached(key, tracks);
  return tracks;
}

export async function artist({ id, name }) {
  const key = `aw2c:artist:${id || name.toLowerCase()}`;
  const hit = cached(key, 12 * HOUR);
  if (hit) return hit;
  const res = await once(key, () => getJson('/api/music/artist', { id, name }));
  const out = { ...res, songs: (res.songs || []).map(normalizeTrack) };
  setCached(key, out);
  return out;
}

export async function plainLyrics(track) {
  if (!track?.youtubeId) return null;
  const key = `aw2c:lyrics:${track.youtubeId}`;
  const hit = cached(key, 30 * 24 * HOUR);
  if (hit) return hit;
  const res = await getJson('/api/music/lyrics', { videoId: track.youtubeId }, { retries: 0 });
  if (res.plain) setCached(key, res);
  return res.plain ? res : null;
}

// ---------- imports ----------

/** Reads a public Spotify playlist. Songs resolve to exact recordings when played. */
export async function importSpotifyPlaylist(link) {
  const id = String(link || '').match(/playlist[/:]([A-Za-z0-9]{22})/)?.[1] || String(link || '').trim().match(/^[A-Za-z0-9]{22}$/)?.[0];
  if (!id) throw new Error('Paste a Spotify playlist link, like open.spotify.com/playlist/…');
  const res = await getJson('/api/spotify/playlist', { id }, { timeout: 15000 });
  return { name: res.name, tracks: (res.tracks || []).map((t) => normalizeTrack({ ...t, coverUrl: '' })) };
}
