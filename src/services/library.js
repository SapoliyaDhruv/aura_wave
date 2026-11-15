/**
 * Library: liked songs, playlists, listening history and taste signals.
 * A tiny external store (subscribe/getState) used through useLibrary().
 *
 * On first run it migrates data from the previous app version's storage keys
 * (those keys are left untouched, so nothing is lost if you roll back).
 */
import { load, save } from '../lib/store.js';
import { normalizeTrack } from './catalog.js';
import { songKey } from '../../shared/match.js';
import legacyCatalog from '../data/legacyCatalog.json' with { type: 'json' };

const K = {
  liked: 'aw2_liked',
  playlists: 'aw2_playlists',
  history: 'aw2_history',
  stats: 'aw2_stats',
  taste: 'aw2_taste',
  hidden: 'aw2_hidden',
  searches: 'aw2_recent_searches',
  migrated: 'aw2_migrated',
};

const listeners = new Set();
let state = null;

function slim(track) {
  const t = normalizeTrack(track);
  delete t.artists; // keep storage small; artist line is enough for display
  return t;
}

/** Old-version track: forget the guessed YouTube video so it is re-resolved strictly. */
function fromLegacy(t) {
  if (!t) return null;
  if (typeof t === 'string') t = legacyCatalog[t] ? { id: t, ...legacyCatalog[t] } : null;
  if (!t?.title) return null;
  const isYtNative = String(t.id || '').startsWith('yt-');
  const cover = t.coverUrl && !/unsplash|pixabay|\/covers\//.test(t.coverUrl) ? t.coverUrl : '';
  return slim({
    id: t.id,
    title: t.title,
    artist: t.artist,
    album: t.album,
    duration: t.duration,
    coverUrl: cover,
    youtubeId: isYtNative ? t.youtubeId : null,
  });
}

function migrate() {
  if (load(K.migrated, false)) return;
  const DEMO_PLAYLISTS = new Set(['pl-desi-hiphop', 'pl-bollywood-clean', 'pl-global-hits']);
  const isDemo = (t) => /aura lofi|cyberwave|synthwave/i.test(t?.artist || '') || /^track-[1-5]$/.test(String(t?.id || ''));

  // Liked songs
  const favIds = load('aurawave_stored_favorites', []);
  const rawMeta = load('aurawave_favorites_meta', {});
  // Older backups restored this as an array; normalise to an id -> track map.
  const favMeta = Array.isArray(rawMeta) ? Object.fromEntries(rawMeta.filter((m) => m?.id).map((m) => [m.id, m])) : (rawMeta || {});
  const liked = (Array.isArray(favIds) ? favIds : [])
    .map((id) => fromLegacy(favMeta[id] ? { id, ...legacyCatalog[id], ...favMeta[id] } : id))
    .filter((t) => t && !isDemo(t));

  // Playlists the listener made or imported
  const oldPlaylists = load('aurawave_stored_playlists', []);
  const playlists = (Array.isArray(oldPlaylists) ? oldPlaylists : [])
    .filter((p) => p && !DEMO_PLAYLISTS.has(p.id))
    .map((p) => ({
      id: String(p.id || `pl-${Date.now()}`),
      name: p.name || 'Untitled playlist',
      description: p.description && !/Imported from Spotify/.test(p.description) ? p.description : '',
      source: String(p.id || '').startsWith('imported-') ? 'spotify' : 'you',
      tracks: (p.tracks || []).map(fromLegacy).filter((t) => t && !isDemo(t)),
      createdAt: Date.now(),
      updatedAt: Date.now(),
    }));

  // History and play counts
  const oldHistory = load('aurawave_recent_history', []);
  const now = Date.now();
  const history = (Array.isArray(oldHistory) ? oldHistory : [])
    .map(fromLegacy).filter(Boolean)
    .map((track, i) => ({ track, at: now - (i + 1) * 5 * 60e3 }));
  const oldStats = load('aurawave_play_stats', {});
  const stats = {};
  for (const s of Object.values(oldStats || {})) {
    const track = fromLegacy(s);
    if (!track) continue;
    stats[track.id] = { track, plays: s.playCount || 1, completes: Math.max(0, (s.playCount || 1) - 1), skips: 0, lastPlayed: s.lastPlayed || now, firstPlayed: s.lastPlayed || now };
  }

  // Taste from the old onboarding profile
  const profile = load('aurawave_active_profile', null);
  const onboarded = load('aurawave_onboarding_completed', false) === true || profile?.isConfigured;
  const taste = {
    artists: onboarded && Array.isArray(profile?.favoriteArtists) ? profile.favoriteArtists : [],
    languages: onboarded && Array.isArray(profile?.preferredLanguages) ? profile.preferredLanguages : [],
    onboarded: Boolean(onboarded && (liked.length || history.length || profile?.favoriteArtists?.length)),
  };

  const ok = [
    save(K.liked, liked),
    save(K.playlists, playlists),
    save(K.history, history),
    save(K.stats, stats),
    save(K.taste, taste),
    save(K.searches, (Array.isArray(load('aurawave_recent_searches', [])) ? load('aurawave_recent_searches', []) : []).filter((q) => typeof q === 'string').slice(0, 10)),
  ].every(Boolean);
  // Only mark done when everything was written, so a failed migration is retried next launch.
  if (ok) save(K.migrated, true);
}

function read() {
  migrate();
  return {
    liked: load(K.liked, []),
    playlists: load(K.playlists, []),
    history: load(K.history, []),
    stats: load(K.stats, {}),
    taste: load(K.taste, { artists: [], languages: [], onboarded: false }),
    hidden: load(K.hidden, { songs: {}, artists: {} }),
    searches: load(K.searches, []),
  };
}

function commit(patch) {
  state = { ...state, ...patch };
  for (const [key, value] of Object.entries(patch)) save(K[key], value);
  listeners.forEach((fn) => fn());
}

const sameTrack = (a, b) => a && b && (a.id === b.id || (a.youtubeId && a.youtubeId === b.youtubeId));

export const library = {
  getState() {
    if (!state) state = read();
    return state;
  },
  subscribe(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
  },

  // ----- likes -----
  isLiked(track) {
    return this.getState().liked.some((t) => sameTrack(t, track));
  },
  toggleLike(track) {
    const { liked } = this.getState();
    const exists = liked.some((t) => sameTrack(t, track));
    commit({ liked: exists ? liked.filter((t) => !sameTrack(t, track)) : [{ ...slim(track), likedAt: Date.now() }, ...liked] });
    return !exists;
  },

  // ----- playlists -----
  createPlaylist(name, tracks = [], source = 'you') {
    const pl = { id: `pl-${Date.now().toString(36)}`, name: name.trim() || 'New playlist', description: '', source, tracks: tracks.map(slim), createdAt: Date.now(), updatedAt: Date.now() };
    commit({ playlists: [pl, ...this.getState().playlists] });
    return pl;
  },
  updatePlaylist(id, patch) {
    commit({ playlists: this.getState().playlists.map((p) => (p.id === id ? { ...p, ...patch, updatedAt: Date.now() } : p)) });
  },
  deletePlaylist(id) {
    commit({ playlists: this.getState().playlists.filter((p) => p.id !== id) });
  },
  addToPlaylist(id, track) {
    const pl = this.getState().playlists.find((p) => p.id === id);
    if (!pl) return false;
    if (pl.tracks.some((t) => sameTrack(t, track))) return false;
    this.updatePlaylist(id, { tracks: [...pl.tracks, slim(track)] });
    return true;
  },
  removeFromPlaylist(id, index) {
    const pl = this.getState().playlists.find((p) => p.id === id);
    if (pl) this.updatePlaylist(id, { tracks: pl.tracks.filter((_, i) => i !== index) });
  },
  moveInPlaylist(id, from, to) {
    const pl = this.getState().playlists.find((p) => p.id === id);
    if (!pl || to < 0 || to >= pl.tracks.length) return;
    const tracks = [...pl.tracks];
    const [moved] = tracks.splice(from, 1);
    tracks.splice(to, 0, moved);
    this.updatePlaylist(id, { tracks });
  },

  /** When a stored track gets resolved to a recording, save that everywhere it appears. */
  rememberResolution(track) {
    if (!track?.youtubeId) return;
    const s = this.getState();
    const patchList = (list) => {
      let changed = false;
      const next = list.map((t) => {
        if (t.id === track.id && t.youtubeId !== track.youtubeId) { changed = true; return { ...t, youtubeId: track.youtubeId, coverUrl: t.coverUrl || track.coverUrl, duration: t.duration || track.duration }; }
        return t;
      });
      return changed ? next : null;
    };
    const patch = {};
    const liked = patchList(s.liked);
    if (liked) patch.liked = liked;
    let plChanged = false;
    const playlists = s.playlists.map((p) => {
      const tracks = patchList(p.tracks);
      if (tracks) { plChanged = true; return { ...p, tracks }; }
      return p;
    });
    if (plChanged) patch.playlists = playlists;
    if (Object.keys(patch).length) commit(patch);
  },

  // ----- listening signals -----
  recordPlay(track) {
    const s = this.getState();
    const t = slim(track);
    const prev = s.stats[t.id];
    const stats = { ...s.stats, [t.id]: { track: t, plays: (prev?.plays || 0) + 1, completes: prev?.completes || 0, skips: prev?.skips || 0, lastPlayed: Date.now(), firstPlayed: prev?.firstPlayed || Date.now() } };
    const history = [{ track: t, at: Date.now() }, ...s.history].slice(0, 300);
    commit({ stats, history });
  },
  /** listened: seconds actually heard. Classifies the play as a skip or a full listen. */
  recordOutcome(track, listened) {
    const s = this.getState();
    const entry = s.stats[track?.id];
    if (!entry) return;
    const dur = track.duration || 200;
    const completed = listened >= Math.min(dur * 0.6, 150);
    const skipped = listened < 30 && listened < dur * 0.4;
    if (!completed && !skipped) return;
    commit({ stats: { ...s.stats, [track.id]: { ...entry, completes: entry.completes + (completed ? 1 : 0), skips: entry.skips + (skipped ? 1 : 0) } } });
  },
  hide(track, { artist = false } = {}) {
    const { hidden } = this.getState();
    const next = { songs: { ...hidden.songs }, artists: { ...hidden.artists } };
    if (artist) next.artists[track.artist.split(/\s*(?:,|&)\s*/)[0].trim().toLowerCase()] = true;
    else next.songs[songKey(track)] = true;
    commit({ hidden: next });
  },
  resetHidden() {
    commit({ hidden: { songs: {}, artists: {} } });
  },
  isHidden(track) {
    const { hidden } = this.getState();
    if (hidden.songs[songKey(track)]) return true;
    const artists = track.artist.toLowerCase().split(/\s*(?:,|&)\s*/);
    return artists.some((a) => hidden.artists[a]);
  },

  // ----- taste & search -----
  setTaste(taste) {
    commit({ taste: { ...this.getState().taste, ...taste } });
  },
  addRecentSearch(q) {
    const query = q.trim();
    if (!query) return;
    commit({ searches: [query, ...this.getState().searches.filter((s) => s.toLowerCase() !== query.toLowerCase())].slice(0, 10) });
  },
  clearRecentSearches() {
    commit({ searches: [] });
  },
  clearHistory() {
    commit({ history: [], stats: {} });
  },

  // ----- backup -----
  exportBackup() {
    const s = this.getState();
    return { app: 'AuraWave', version: 2, exportedAt: new Date().toISOString(), liked: s.liked, playlists: s.playlists, history: s.history, stats: s.stats, taste: s.taste, hidden: s.hidden, pins: load('aw2_pins', {}), customLyrics: load('aurawave_synced_lyrics_vault', {}) };
  },
  importBackup(data) {
    if (!data || typeof data !== 'object') throw new Error('This file is not an AuraWave backup.');
    if (data.app === 'AuraWave' && data.version === 2) {
      const s = this.getState();
      const mergeTracks = (a, b) => { const seen = new Set(a.map((t) => t.id)); return [...a, ...b.filter((t) => !seen.has(t.id))]; };
      const plIds = new Set(s.playlists.map((p) => p.id));
      commit({
        liked: mergeTracks(s.liked, data.liked || []),
        playlists: [...s.playlists, ...(data.playlists || []).filter((p) => !plIds.has(p.id))],
        history: data.history?.length > s.history.length ? data.history : s.history,
        stats: { ...(data.stats || {}), ...s.stats },
        taste: s.taste.onboarded ? s.taste : (data.taste || s.taste),
        hidden: { songs: { ...data.hidden?.songs, ...s.hidden.songs }, artists: { ...data.hidden?.artists, ...s.hidden.artists } },
      });
      if (data.pins) save('aw2_pins', { ...data.pins, ...load('aw2_pins', {}) });
      if (data.customLyrics) save('aurawave_synced_lyrics_vault', { ...data.customLyrics, ...load('aurawave_synced_lyrics_vault', {}) });
      return;
    }
    // Backup from the previous app version
    const meta = Array.isArray(data.favoriteMeta) ? Object.fromEntries(data.favoriteMeta.map((m) => [m.id, m])) : (data.favoriteMeta || {});
    const liked = (data.favorites || Object.keys(meta)).map((id) => fromLegacy(meta[id] ? { id, ...legacyCatalog[id], ...meta[id] } : id)).filter(Boolean);
    const playlists = (data.customPlaylists || data.playlists || []).filter((p) => p?.tracks?.length).map((p) => ({ id: String(p.id), name: p.name || 'Imported playlist', description: '', source: 'you', tracks: p.tracks.map(fromLegacy).filter(Boolean), createdAt: Date.now(), updatedAt: Date.now() }));
    if (!liked.length && !playlists.length) throw new Error('No songs or playlists were found in this backup file.');
    const s = this.getState();
    const ids = new Set(s.liked.map((t) => t.id));
    const plIds = new Set(s.playlists.map((p) => p.id));
    commit({ liked: [...s.liked, ...liked.filter((t) => !ids.has(t.id))], playlists: [...s.playlists, ...playlists.filter((p) => !plIds.has(p.id))] });
    if (data.lyricsVault && typeof data.lyricsVault === 'object') save('aurawave_synced_lyrics_vault', { ...data.lyricsVault, ...load('aurawave_synced_lyrics_vault', {}) });
  },
};
