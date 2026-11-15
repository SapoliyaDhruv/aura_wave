/**
 * Recommender: builds the home feed and endless autoplay from your own listening.
 *
 *  1. Every track you touched gets an affinity weight: likes, full listens and
 *     plays push it up, skips pull it down, and old activity slowly fades.
 *  2. The strongest tracks (max two per artist, so one artist can't take over)
 *     become seeds. Each seed contributes its YouTube Music radio as candidates.
 *  3. Candidates are scored by how many of *your* seeds point at them, how early
 *     they appear in those radios, and how much you like their artists. Hidden
 *     songs/artists and repeatedly skipped artists are filtered out.
 *  4. The scored pool is cut into shelves, each track carrying a plain-language
 *     reason ("Because you played Tum Hi Ho") so recommendations explain themselves.
 */
import { library } from './library.js';
import * as catalog from './catalog.js';
import { cached, setCached } from '../lib/store.js';
import { leadArtist, shortTitle } from '../lib/format.js';
import { songKey } from '../../shared/match.js';

const DAY = 86400e3;
const FEED_KEY = 'aw2c:feed';

const artistKey = (track) => leadArtist(track.artist).trim().toLowerCase();

/** Seeded PRNG so "today's picks" stay stable during the day and change tomorrow. */
function rng(seed) {
  let s = seed >>> 0;
  return () => { s = (s + 0x6d2b79f5) >>> 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t ^= t + Math.imul(t ^ (t >>> 7), 61 | t); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
const dayNumber = () => Math.floor((Date.now() - new Date().getTimezoneOffset() * 60e3) / DAY);

// ---------- affinity ----------

export function computeAffinity(state = library.getState()) {
  const now = Date.now();
  const tracks = new Map(); // id -> { track, weight, plays, lastPlayed, liked }
  const touch = (track) => {
    if (!tracks.has(track.id)) tracks.set(track.id, { track, weight: 0, plays: 0, completes: 0, skips: 0, lastPlayed: 0, liked: false });
    return tracks.get(track.id);
  };

  for (const s of Object.values(state.stats)) {
    const e = touch(s.track);
    const recency = Math.exp(-(now - (s.lastPlayed || now)) / (21 * DAY));
    e.plays = s.plays; e.completes = s.completes; e.skips = s.skips; e.lastPlayed = s.lastPlayed;
    e.weight += (s.plays * 0.4 + s.completes * 1.0 - s.skips * 1.3) * (0.35 + 0.65 * recency);
  }
  for (const t of state.liked) {
    const e = touch(t);
    e.liked = true;
    e.weight += 3 * (0.5 + 0.5 * Math.exp(-(now - (t.likedAt || now)) / (60 * DAY)));
  }

  const artists = new Map(); // artistKey -> { name, weight, skips, plays }
  for (const e of tracks.values()) {
    const k = artistKey(e.track);
    const a = artists.get(k) || { name: leadArtist(e.track.artist), weight: 0, skips: 0, plays: 0 };
    a.weight += e.weight; a.skips += e.skips; a.plays += e.plays;
    artists.set(k, a);
  }
  for (const name of state.taste.artists || []) {
    const k = name.toLowerCase();
    const a = artists.get(k) || { name, weight: 0, skips: 0, plays: 0 };
    a.weight += 2;
    artists.set(k, a);
  }
  return { tracks, artists };
}

function pickSeeds(aff, count = 6, rand = Math.random) {
  const ranked = [...aff.tracks.values()]
    .filter((e) => e.weight > 0.3)
    .map((e) => ({ ...e, draw: e.weight * (0.6 + rand() * 0.8) })) // a little variety day to day
    .sort((a, b) => b.draw - a.draw);
  const perArtist = new Map();
  const seeds = [];
  for (const e of ranked) {
    const k = artistKey(e.track);
    if ((perArtist.get(k) || 0) >= 2) continue;
    perArtist.set(k, (perArtist.get(k) || 0) + 1);
    seeds.push(e);
    if (seeds.length >= count) break;
  }
  return seeds;
}

/** Cold start: a top song for each artist the listener picked during setup (or top defaults). */
async function seedsFromTaste(state) {
  let names = (state.taste.artists || []).slice(0, 5);
  if (!names.length) {
    const lang = (state.taste.languages && state.taste.languages[0]) || 'Hindi';
    if (lang === 'Punjabi') {
      names = ['Karan Aujla', 'Diljit Dosanjh', 'Shubh', 'AP Dhillon'];
    } else if (lang === 'English') {
      names = ['The Weeknd', 'Taylor Swift', 'Billie Eilish', 'Sabrina Carpenter'];
    } else {
      names = ['Arijit Singh', 'Pritam', 'KK', 'Sonu Nigam', 'Shreya Ghoshal'];
    }
  }

  const results = await Promise.allSettled(names.map((name) => catalog.artist({ name })));
  const seeds = results
    .filter((r) => r.status === 'fulfilled' && r.value?.songs?.length)
    .map((r) => {
      const name = r.value.name;
      const lead = r.value.songs.find((s) => leadArtist(s.artist).toLowerCase() === name.toLowerCase()) || r.value.songs[0];
      return { track: lead, weight: 2, plays: 0, liked: false, fromTaste: name, forArtist: name.toLowerCase() };
    });

  if (!seeds.length) {
    const all = catalog.getCatalogTracks();
    for (const name of names) {
      const found = all.find((t) => leadArtist(t.artist).toLowerCase().includes(name.toLowerCase()));
      if (found) seeds.push({ track: found, weight: 2, plays: 0, liked: false, fromTaste: name, forArtist: name.toLowerCase() });
    }
  }

  return seeds;
}

// ---------- candidate scoring ----------

function scoreCandidates(seedRadios, aff, state, { excludeKnown = false } = {}) {
  const pool = new Map(); // songKey -> { track, score, seeds: [seedTrack] }
  const knownIds = new Set([...aff.tracks.keys()]);
  const knownKeys = new Set([...aff.tracks.values()].map((e) => songKey(e.track)));
  const seedKeys = new Set(seedRadios.map(({ seed }) => songKey(seed.track)));

  seedRadios.forEach(({ seed, tracks }) => {
    tracks.forEach((t, pos) => {
      const key = songKey(t);
      if (seedKeys.has(key) || library.isHidden(t)) return;
      if (excludeKnown && (knownIds.has(t.id) || knownKeys.has(key))) return;
      const entry = pool.get(key) || { track: t, score: 0, seeds: [] };
      entry.score += Math.max(0.5, seed.weight) / (1 + pos / 12);
      if (!entry.seeds.includes(seed)) entry.seeds.push(seed);
      pool.set(key, entry);
    });
  });

  for (const entry of pool.values()) {
    const a = aff.artists.get(artistKey(entry.track));
    if (a) {
      entry.score *= 1 + Math.log1p(Math.max(0, a.weight)) * 0.25;
      if (a.plays >= 3 && a.skips / a.plays > 0.6) entry.score *= 0.3; // you keep skipping this artist
    }
    entry.score *= 1 + 0.35 * (entry.seeds.length - 1); // agreement across several of your seeds
  }
  return [...pool.values()].sort((a, b) => b.score - a.score);
}

function reasonFor(entry, aff) {
  if (entry.seeds.length >= 2) return `Linked to ${shortTitle(entry.seeds[0].track.title)} and ${shortTitle(entry.seeds[1].track.title)}`;
  const seed = entry.seeds[0];
  if (seed?.fromTaste) return `For fans of ${seed.fromTaste}`;
  const a = aff.artists.get(artistKey(entry.track));
  if (a && a.weight > 3) return `You often play ${a.name}`;
  return seed ? `Because you played ${shortTitle(seed.track.title)}` : 'Picked for you';
}

/** Limits how many tracks one artist contributes, keeping order otherwise. */
function diversify(entries, maxPerArtist = 2, limit = 20) {
  const counts = new Map();
  const out = [];
  for (const e of entries) {
    const k = artistKey(e.track);
    if ((counts.get(k) || 0) >= maxPerArtist) continue;
    counts.set(k, (counts.get(k) || 0) + 1);
    out.push(e);
    if (out.length >= limit) break;
  }
  return out;
}

const withReason = (entry, reason) => ({ ...entry.track, reason });

// ---------- shelves ----------

function localShelves(aff, state) {
  const now = Date.now();
  const all = [...aff.tracks.values()];
  const onRepeat = all
    .filter((e) => e.plays >= 2 && now - e.lastPlayed < 30 * DAY)
    .sort((a, b) => b.plays - a.plays || b.lastPlayed - a.lastPlayed)
    .slice(0, 10)
    .map((e) => ({ ...e.track, reason: `${e.plays} plays this month`, plays: e.plays }));
  const rediscover = all
    .filter((e) => (e.liked || e.completes >= 3) && e.lastPlayed && now - e.lastPlayed > 14 * DAY)
    .sort((a, b) => b.weight - a.weight)
    .slice(0, 12)
    .map((e) => ({ ...e.track, reason: `Last played ${Math.round((now - e.lastPlayed) / DAY)} days ago` }));
  const recent = [];
  const seen = new Set();
  for (const h of state.history) {
    if (seen.has(h.track.id)) continue;
    seen.add(h.track.id);
    recent.push(h.track);
    if (recent.length >= 12) break;
  }
  return { onRepeat, rediscover, recent };
}

function timeOfDayName(d = new Date()) {
  const h = d.getHours();
  if (h < 5) return 'Late-night';
  if (h < 12) return 'Morning';
  if (h < 17) return 'Afternoon';
  if (h < 21) return 'Evening';
  return 'Night';
}

/**
 * Builds the full home feed. Returns cached feed immediately when fresh (< 3h),
 * unless `force` is set (the "New picks" button).
 */
export async function buildFeed({ force = false } = {}) {
  const state = library.getState();
  const aff = computeAffinity(state);
  const local = localShelves(aff, state);

  if (!force) {
    const hit = cached(FEED_KEY, 3 * 3600e3);
    if (hit) return { ...hit, ...local, fromCache: true };
  }

  const rand = rng(force ? Date.now() : dayNumber());
  let seeds = pickSeeds(aff, 6, rand);
  if (seeds.length < 3) seeds = [...seeds, ...(await seedsFromTaste(state))].slice(0, 6);
  if (!seeds.length) {
    const defaultCatalog = catalog.getCatalogTracks().slice(0, 6);
    seeds = defaultCatalog.map((track) => ({
      track,
      weight: 2,
      plays: 0,
      liked: false,
      fromTaste: leadArtist(track.artist),
      forArtist: leadArtist(track.artist).toLowerCase(),
    }));
  }

  let radios = (await Promise.allSettled(seeds.map(async (seed) => ({ seed, tracks: await catalog.radio(seed.track) }))))
    .filter((r) => r.status === 'fulfilled' && r.value.tracks.length)
    .map((r) => r.value);

  if (!radios.length) {
    const allTracks = catalog.getCatalogTracks();
    radios = seeds.map((seed, i) => ({
      seed,
      tracks: allTracks.slice(i * 8, (i + 1) * 8 + 8),
    }));
  }

  const fresh = scoreCandidates(radios, aff, state, { excludeKnown: true });
  const everything = scoreCandidates(radios, aff, state);

  // Hero mix: mostly discoveries, with some favourites woven in every few tracks.
  const favourites = [...aff.tracks.values()].filter((e) => e.weight > 1).sort(() => rand() - 0.5).slice(0, 8);
  const discoveries = diversify(fresh, 2, 18);
  const heroTracks = [];
  let f = 0;
  discoveries.forEach((d, i) => {
    heroTracks.push(withReason(d, reasonFor(d, aff)));
    if (i % 3 === 2 && favourites[f]) { heroTracks.push({ ...favourites[f].track, reason: 'One of your favourites' }); f++; }
  });

  if (heroTracks.length < 5) {
    const fallbackAll = catalog.getCatalogTracks();
    fallbackAll.slice(0, 20).forEach((t) => {
      if (!heroTracks.some((ht) => songKey(ht) === songKey(t))) {
        heroTracks.push({ ...t, reason: 'Curated for you' });
      }
    });
  }

  // Artist mixes: your top artists, each blended with the artists their radios lead to.
  const topArtists = [...aff.artists.entries()].sort((a, b) => b[1].weight - a[1].weight).slice(0, 4);
  const seedArtist = (s) => s.forArtist || artistKey(s.track);
  const usedCovers = new Set();
  const mixes = topArtists.map(([key, a]) => {
    const own = [
      ...seeds.filter((s) => s.forArtist === key).map((s) => ({ ...s.track, reason: `A favourite from ${a.name}` })),
      ...[...aff.tracks.values()].filter((e) => artistKey(e.track) === key).sort((x, y) => y.weight - x.weight).slice(0, 5).map((e) => ({ ...e.track, reason: 'You play this a lot' })),
    ];
    const related = everything.filter((e) => e.seeds.some((s) => seedArtist(s) === key) || artistKey(e.track) === key);
    const relatedTracks = diversify(related, 2, 20).map((e) => withReason(e, reasonFor(e, aff)));
    const tracks = [];
    const seenKeys = new Set();
    // interleave: 1 of yours, 3 related
    let oi = 0;
    relatedTracks.forEach((t, i) => {
      if (i % 3 === 0 && own[oi]) { const o = own[oi++]; if (!seenKeys.has(songKey(o))) { seenKeys.add(songKey(o)); tracks.push(o); } }
      if (!seenKeys.has(songKey(t))) { seenKeys.add(songKey(t)); tracks.push(t); }
    });
    // Each mix opens with a cover no other mix uses, so they are easy to tell apart.
    const lead = tracks.findIndex((t) => t.coverUrl && !usedCovers.has(t.coverUrl) && artistKey(t) === key);
    const pick = lead > 0 ? lead : tracks.findIndex((t) => t.coverUrl && !usedCovers.has(t.coverUrl));
    if (pick > 0) tracks.unshift(...tracks.splice(pick, 1));
    tracks.slice(0, 3).forEach((t) => usedCovers.add(t.coverUrl));
    const others = [...new Set(tracks.map((t) => leadArtist(t.artist)).filter((n) => n.toLowerCase() !== key))].slice(0, 2);
    return { id: `mix-${key}`, name: `${a.name} Mix`, subtitle: others.length ? `With ${others.join(' and ')}` : 'Your favourites and more', tracks };
  }).filter((m) => m.tracks.length >= 6);

  if (mixes.length === 0) {
    const all = catalog.getCatalogTracks();
    const hindi = all.filter((t) => /Arijit|Pritam|KK|Atif|Sonu|Shreya|Mohit/i.test(t.artist));
    const punjabi = all.filter((t) => /Aujla|Diljit|Shubh|Dhillon/i.test(t.artist));
    const global = all.filter((t) => /Weeknd|Billie|Sabrina|Taylor|Styles|Joji/i.test(t.artist));
    const indie = all.filter((t) => /Anuv|Kuhad|King|DIVINE|Stan/i.test(t.artist));
    if (hindi.length >= 4) mixes.push({ id: 'mix-bollywood', name: 'Bollywood Romance Mix', subtitle: 'Arijit Singh, Pritam, KK and more', tracks: hindi.slice(0, 15) });
    if (punjabi.length >= 4) mixes.push({ id: 'mix-punjabi', name: 'Punjabi Hits Mix', subtitle: 'Karan Aujla, Diljit Dosanjh, Shubh', tracks: punjabi.slice(0, 15) });
    if (global.length >= 4) mixes.push({ id: 'mix-global', name: 'Global Top Hits Mix', subtitle: 'The Weeknd, Billie Eilish, Sabrina Carpenter', tracks: global.slice(0, 15) });
    if (indie.length >= 4) mixes.push({ id: 'mix-indie', name: 'Indie & Chill Mix', subtitle: 'Anuv Jain, Prateek Kuhad and more', tracks: indie.slice(0, 15) });
  }

  // "Because you played X": the most recent song you finished or liked.
  const anchor = state.history.find((h) => aff.tracks.get(h.track.id)?.weight > 0.8)?.track || seeds[0]?.track || catalog.getCatalogTracks()[0];
  const anchorRadio = radios.find((r) => r.seed?.track?.id === anchor.id)?.tracks || await catalog.radio(anchor).catch(() => []);
  const knownKeys = new Set([...aff.tracks.values()].map((e) => songKey(e.track)));
  const becauseCandidate = (anchorRadio || []).filter((t) => !knownKeys.has(songKey(t)) && !library.isHidden(t)).slice(0, 8)
    .map((t) => ({ ...t, reason: `Often played after ${shortTitle(anchor.title)}` }));
  const because = {
    seed: anchor,
    tracks: becauseCandidate.length > 0 ? becauseCandidate : catalog.getCatalogTracks().slice(1, 9).map((t) => ({ ...t, reason: 'Curated recommendation' })),
  };

  const freshCandidates = diversify(fresh.slice(18), 1, 14).map((e) => withReason(e, reasonFor(e, aff)));
  const feed = {
    hero: { name: `${timeOfDayName()} Aura`, subtitle: 'New finds and favourites, shaped by what you play', tracks: heroTracks.slice(0, 25) },
    mixes,
    because,
    freshFinds: freshCandidates.length > 0 ? freshCandidates : catalog.getCatalogTracks().slice(10, 24).map((t) => ({ ...t, reason: 'Fresh discovery' })),
    builtAt: Date.now(),
  };
  setCached(FEED_KEY, feed);
  return { ...feed, ...local, empty: false };
}

/** Shelves computed purely from local history; cheap enough to recompute on every change. */
export function localFeed(state = library.getState()) {
  return localShelves(computeAffinity(state), state);
}

export function invalidateFeed() {
  setCached(FEED_KEY, null);
}

// ---------- autoplay ----------

/**
 * Picks what plays after the queue runs out: the current song's radio, minus songs
 * heard this session, hidden songs and artists you skipped this session.
 */
export async function autoplayAfter(current, { played = new Set(), skippedArtists = new Set(), count = 12 } = {}) {
  const tracks = await catalog.radio(current);
  const aff = computeAffinity();
  const seen = new Set();
  return tracks
    .filter((t) => {
      const key = songKey(t);
      if (played.has(key) || seen.has(key) || library.isHidden(t) || skippedArtists.has(artistKey(t))) return false;
      seen.add(key);
      return true;
    })
    .map((t, i) => ({ t, s: 1 / (1 + i / 10) + Math.log1p(Math.max(0, aff.artists.get(artistKey(t))?.weight || 0)) * 0.08 }))
    .sort((a, b) => b.s - a.s)
    .slice(0, count)
    .map(({ t }) => ({ ...t, reason: `Similar to ${shortTitle(current.title)}` }));
}
