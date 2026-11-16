/**
 * Song identity & matching helpers, shared by the serverless API and the client.
 *
 * The goal is to answer one question reliably: "is this recording the song the
 * listener asked for?" — so a same-named song by another artist, an instrumental,
 * a karaoke track or a slowed/lofi edit never plays in place of the original.
 */

// Version markers. If a candidate carries one of these and the requested title
// does not, it is a different recording of the song (or not the song at all).
const VERSION_MARKERS = [
  ['instrumental', /\binstrumental\b|\bwithout vocals?\b|\bno vocals?\b|\bmusic only\b|\bminus one\b|\bbacking track\b|\bbgm\b|\bbackground (music|score)\b|\btheme music\b|\bdhun\b/],
  ['karaoke', /\bkaraoke\b|\bsing ?along\b|\boriginally performed\b|\bin the style of\b/],
  ['lofi', /\blo-?fi\b/],
  ['slowed', /\bslowed\b|\breverb\b/],
  ['spedup', /\bsped ?up\b|\bspeed ?up\b|\bnightcore\b/],
  ['remix', /\bremix\b|\bre-?mix\b|\bmash-?up\b|\bdj\b|\bclub mix\b|\bdhol mix\b|\bbootleg\b|\bflip\b/],
  ['cover', /\bcover\b|\btribute\b|\bfan made\b|\breplay\b|\brecreated\b/],
  ['live', /\blive\b|\bconcert\b|\bin session\b|\bmtv unplugged\b/],
  ['unplugged', /\bunplugged\b|\bacoustic\b|\bstripped\b/],
  ['reprise', /\breprise\b|\breprised\b/],
  ['female', /\bfemale( version)?\b/],
  ['male', /\bmale version\b/],
  ['spatial', /\b8d\b|\b16d\b|\b3d audio\b|\bbass ?boost(ed)?\b|\bjhankar\b/],
  ['solo', /\b(piano|flute|guitar|violin|sitar|cello|saxophone|orchestral|music box) (version|cover)\b/],
  ['clip', /\bringtone\b|\bstatus\b|\bshorts?\b|\breel\b|\bteaser\b|\btrailer\b|\bscene\b|\bdialogue\b|\breaction\b|\btutorial\b|\bhow to play\b/],
];

export function versionFlags(text, { album = false } = {}) {
  const t = ' ' + String(text || '').toLowerCase().replace(/[_|#]+/g, ' ') + ' ';
  const flags = new Set();
  for (const [flag, re] of VERSION_MARKERS) {
    // Album names like "Short n' Sweet", "Status" or "Unplugged Sessions" say little about the
    // track itself; only strong markers (instrumental, karaoke, slowed, live...) count there.
    if (album && (flag === 'clip' || flag === 'cover' || flag === 'unplugged' || flag === 'solo')) continue;
    if (re.test(t)) flags.add(flag);
  }
  return flags;
}

function stripDiacritics(s) {
  return String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '');
}

/** Removes bracketed credits ("(From "Film")", "(feat. X)", "[Official Audio]") from a title. */
export function coreTitle(title) {
  return stripDiacritics(title)
    .toLowerCase()
    .replace(/\((from|feat\.?|ft\.?|featuring|with|prod\.?|official|lyric|full|audio|video|from the)[^)]*\)/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/\s[-–—|]\s.*$/, ' ') // "Song - From Film", "Song | Artist"
    .replace(/\b(feat\.?|ft\.?|featuring)\s.*$/, ' ')
    .replace(/[“”"'’`]/g, '')
    .replace(/&/g, ' and ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Phonetic folding for romanised Hindi/Punjabi spellings (Raataan ≈ Raatan, Maahi ≈ Mahi). */
export function fold(s) {
  return stripDiacritics(s)
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]/gu, '')
    .replace(/(.)\1+/g, '$1')
    .replace(/ee/g, 'i')
    .replace(/oo/g, 'u')
    .replace(/w/g, 'v')
    .replace(/z/g, 'j')
    .replace(/y$/g, 'i')
    .replace(/([bcdfgjklmnpqrstvx])h/g, '$1');
}

function tokens(s) {
  return coreTitle(s).split(' ').filter(Boolean);
}

function bigrams(s) {
  const out = new Map();
  for (let i = 0; i < s.length - 1; i++) {
    const g = s.slice(i, i + 2);
    out.set(g, (out.get(g) || 0) + 1);
  }
  return out;
}

function dice(a, b) {
  if (!a || !b) return 0;
  if (a === b) return 1;
  const A = bigrams(a);
  const B = bigrams(b);
  let inter = 0;
  for (const [g, n] of A) inter += Math.min(n, B.get(g) || 0);
  const total = Math.max(1, a.length - 1 + b.length - 1);
  return (2 * inter) / total;
}

/** 0..1 similarity between two song titles, tolerant of spelling and credit noise. */
export function titleSimilarity(a, b) {
  const ta = tokens(a);
  const tb = tokens(b);
  if (!ta.length || !tb.length) return 0;
  const fa = fold(ta.join(''));
  const fb = fold(tb.join(''));
  if (fa === fb) return 1;
  const setA = new Set(ta.map(fold));
  const setB = new Set(tb.map(fold));
  let inter = 0;
  for (const t of setA) if (setB.has(t)) inter++;
  const tokenScore = (2 * inter) / (setA.size + setB.size);
  // Character similarity is discounted when lengths differ a lot, so "Tere Bina"
  // does not match "Tere Bin Nahi Laage" just because one starts like the other.
  const lengthRatio = Math.min(fa.length, fb.length) / Math.max(fa.length, fb.length);
  return Math.max(tokenScore, dice(fa, fb) * (0.5 + 0.5 * lengthRatio));
}

/** Splits an artist credit like "Pritam, Arijit Singh & Shreya Ghoshal" into names. */
export function splitArtists(credit) {
  return stripDiacritics(credit)
    .toLowerCase()
    .split(/\s*(?:,|&|\/|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|•)\s*/)
    .map((a) => a.replace(/[^\p{L}\p{N}]/gu, ''))
    .filter((a) => a.length >= 2 && a !== 'various' && a !== 'variousartists');
}

function artistEq(a, b) {
  if (a === b) return true;
  if (a.length >= 4 && b.length >= 4 && (a.includes(b) || b.includes(a))) return true;
  return fold(a) === fold(b);
}

/**
 * How well a candidate's artists cover the requested credit.
 * Returns { primary: bool, any: bool, ratio: 0..1 }.
 */
export function artistOverlap(requested, candidate) {
  const req = splitArtists(requested);
  const cand = splitArtists(candidate);
  if (!req.length || !cand.length) return { primary: false, any: false, ratio: 0 };
  const hits = req.filter((r) => cand.some((c) => artistEq(r, c)));
  return {
    primary: cand.some((c) => artistEq(req[0], c)),
    any: hits.length > 0,
    ratio: hits.length / req.length,
  };
}

/** True when the free text mentions any of the requested artists (for video titles/channels). */
export function mentionsArtist(requested, text) {
  const hay = stripDiacritics(text).toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
  const foldedHay = fold(hay);
  return splitArtists(requested).some((a) => a.length >= 3 && (hay.includes(a) || foldedHay.includes(fold(a))));
}

/**
 * Scores how likely `cand` is the requested recording.
 * want: { title, artist, duration }
 * cand: { title, artist, duration, kind: 'song' | 'video' | 'upload', channel? }
 * Returns { score, accept, reason }.
 */
export function scoreCandidate(want, cand) {
  const wantFlags = versionFlags(want.title);
  const candFlags = versionFlags(cand.title);
  for (const f of versionFlags(cand.album, { album: true })) candFlags.add(f);
  for (const f of candFlags) {
    if (!wantFlags.has(f)) return { score: -1000, accept: false, reason: `different version (${f})` };
  }

  // Video titles often read "Artist - Song (Official Video)"; also score the part after the dash.
  const dash = cand.kind !== 'song' ? cand.title.split(/\s[-–—|]\s/) : [];
  const titleVariants = [cand.title, ...dash.slice(1)];
  const sim = Math.max(...titleVariants.map((t) => titleSimilarity(want.title, t)));
  const containsWanted = (() => {
    const w = coreTitle(want.title);
    const hay = ' ' + stripDiacritics(cand.title).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ') + ' ';
    return w.length >= 4 && hay.includes(` ${w} `);
  })();
  if (sim < 0.55 && !containsWanted) return { score: -500, accept: false, reason: 'title mismatch' };

  // Clips: anything far shorter than a song (status videos, previews, shorts).
  if (cand.duration > 0 && cand.duration < 75 && !(want.duration > 0 && want.duration < 90)) {
    return { score: -800, accept: false, reason: 'too short to be the song' };
  }

  // Video titles are keyword-stuffed, so "contains the title" is weaker evidence there.
  const containsCredit = containsWanted ? (cand.kind === 'song' ? 0.8 : 0.65) : 0;
  let score = Math.max(sim, containsCredit) * 100;
  for (const f of wantFlags) if (!candFlags.has(f)) score -= 40;

  const titleOnlyScore = score;
  let artistOk = !want.artist;
  if (want.artist) {
    const o = cand.kind !== 'song'
      ? { primary: mentionsArtist(want.artist, `${cand.title} ${cand.channel || ''} ${cand.artist || ''}`), any: false }
      : artistOverlap(want.artist, cand.artist);
    if (o.primary) { score += 60; artistOk = true; }
    else if (o.any) { score += 40; artistOk = true; }
    else score -= 80;
  }

  let durationClose = false;
  let exactDuration = false;
  if (want.duration > 0 && cand.duration > 0) {
    const d = Math.abs(want.duration - cand.duration);
    exactDuration = d <= 2;
    if (d <= 3) { score += 30; durationClose = true; }
    else if (d <= 8) { score += 20; durationClose = true; }
    else if (d <= 15) score += 5;
    else if (d > 60) score -= 90;
    else if (d > 30) score -= 50;
  }

  // Official audio tracks are cleanest; music videos may carry intros; uploads are least trusted.
  if (cand.kind === 'video') score -= 15;
  else if (cand.kind === 'upload') score -= 35;

  // Accept when title + artist agree, or (no artist known) the title agrees.
  // Credits sometimes name the composer instead of the singer, so an official audio track
  // with an exact title and a duration within 2s is accepted even without an artist match.
  // (Candidates are ranked, so a version that also matches the artist always wins first.)
  const accept =
    (artistOk && score >= 120) ||
    (!want.artist && sim >= 0.85) ||
    (cand.kind === 'song' && sim >= 0.97 && exactDuration && titleOnlyScore >= 95);

  return { score, accept, reason: accept ? 'match' : 'low confidence' };
}

/** Stable identity for de-duplication: the YouTube recording when known, otherwise title+artist. */
export function trackKey(track) {
  if (!track) return '';
  if (track.youtubeId) return `yt:${track.youtubeId}`;
  return `t:${fold(coreTitle(track.title))}:${splitArtists(track.artist)[0] || ''}`;
}

/** Same song regardless of which upload (used to avoid near-duplicates in recommendations). */
export function songKey(track) {
  if (!track) return '';
  return `${fold(coreTitle(track.title))}:${fold(splitArtists(track.artist)[0] || '')}`;
}
