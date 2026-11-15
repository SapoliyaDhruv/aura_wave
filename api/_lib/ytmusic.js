/**
 * Minimal YouTube Music (InnerTube WEB_REMIX) client.
 *
 * Why YouTube Music instead of scraping youtube.com/results:
 *  - The "Songs" filter returns official audio tracks (art tracks) with structured
 *    artist, album and duration, so we can verify we have the right recording.
 *  - Its search understands lyrics, so typing a line from a song finds the song.
 *  - Its radio ("RDAMVM<id>") gives high-quality related tracks for recommendations.
 */
import { scoreCandidate, versionFlags } from '../../shared/match.js';

const ORIGIN = 'https://music.youtube.com';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const REGION = process.env.YTM_REGION || 'IN';
const FILTER = {
  songs: 'EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D',
  videos: 'EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D',
  artists: 'EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D',
};

let clientVersion = '1.20241111.01.00';
let versionRefreshed = false;

async function refreshClientVersion() {
  if (versionRefreshed) return false;
  versionRefreshed = true;
  try {
    const html = await fetch(ORIGIN, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(5000) }).then((r) => r.text());
    const m = html.match(/"INNERTUBE_CLIENT_VERSION":"([\d.]+)"/);
    if (m) { clientVersion = m[1]; return true; }
  } catch { /* keep the built-in version */ }
  return false;
}

async function call(endpoint, body, attempt = 0) {
  let res;
  try {
    res = await request(endpoint, body);
  } catch (err) {
    // Timeouts and dropped connections are usually transient: retry once.
    if (attempt === 0) return call(endpoint, body, 1);
    throw err;
  }
  if (!res.ok) {
    // An outdated client version is the usual cause of 400s; refresh once and retry.
    if (attempt === 0 && (res.status === 400 || res.status === 403) && (await refreshClientVersion())) {
      return call(endpoint, body, 1);
    }
    if (attempt === 0 && res.status >= 500) return call(endpoint, body, 1);
    throw new Error(`YouTube Music ${endpoint} failed with ${res.status}`);
  }
  return res.json();
}

function request(endpoint, body) {
  return fetch(`${ORIGIN}/youtubei/v1/${endpoint}?prettyPrint=false`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'User-Agent': UA, Origin: ORIGIN, Referer: `${ORIGIN}/` },
    body: JSON.stringify({
      context: { client: { clientName: 'WEB_REMIX', clientVersion, hl: 'en', gl: REGION } },
      ...body,
    }),
    signal: AbortSignal.timeout(6000),
  });
}

// ---------- parsing helpers ----------

function collect(obj, key, out = []) {
  if (!obj || typeof obj !== 'object') return out;
  if (obj[key]) { out.push(obj[key]); return out; }
  for (const k in obj) collect(obj[k], key, out);
  return out;
}

const runsText = (t) => (t?.runs || []).map((r) => r.text).join('') || t?.simpleText || '';

function parseDuration(str) {
  if (!str || !/^\d+(:\d+){1,2}$/.test(str)) return 0;
  return str.split(':').map(Number).reduce((acc, n) => acc * 60 + n, 0);
}

/** Upgrades a googleusercontent / ytimg thumbnail URL to a crisp square image. */
export function bigCover(url, size = 544) {
  if (!url) return '';
  if (/googleusercontent\.com|ggpht\.com/.test(url)) return url.replace(/=w\d+-h\d+[^&]*$/, `=w${size}-h${size}-l90-rj`).replace(/=s\d+[^&]*$/, `=s${size}`);
  return url;
}

function bestThumb(thumbs) {
  if (!Array.isArray(thumbs) || !thumbs.length) return '';
  return bigCover(thumbs[thumbs.length - 1].url);
}

const pageType = (run) => run?.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType;

/** Turns the subtitle runs ("Artist • Album • 4:22") into structured fields. */
function parseByline(runs = []) {
  const artists = [];
  let album = '';
  let albumId = '';
  let duration = 0;
  let year = '';
  const loose = [];
  for (const r of runs) {
    const text = r.text?.trim();
    if (!text || text === '•' || text === ',' || text === '&') continue;
    const type = pageType(r);
    if (type === 'MUSIC_PAGE_TYPE_ARTIST' || type === 'MUSIC_PAGE_TYPE_USER_CHANNEL') {
      artists.push({ name: text, id: r.navigationEndpoint.browseEndpoint.browseId });
    } else if (type === 'MUSIC_PAGE_TYPE_ALBUM') {
      album = text; albumId = r.navigationEndpoint.browseEndpoint.browseId;
    } else if (parseDuration(text)) {
      duration = parseDuration(text);
    } else if (/^\d{4}$/.test(text)) {
      year = text;
    } else if (!/(plays|views|monthly audience|subscribers)$/i.test(text) && !['Song', 'Video', 'Artist', 'Album', 'Single', 'EP'].includes(text)) {
      loose.push(text);
    }
  }
  // Some items list artist names as plain text (no link). Treat leading loose text as artists.
  if (!artists.length && loose.length) {
    loose[0].split(/\s*(?:,|&)\s*/).filter(Boolean).forEach((name) => artists.push({ name, id: '' }));
  }
  return { artists, album, albumId, duration, year };
}

function videoTypeOf(renderer) {
  return renderer?.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
    ?.playNavigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
    || renderer?.navigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
    || '';
}

function toTrack({ videoId, title, byline, thumbs, videoType, plays }) {
  const artistLine = byline.artists.map((a) => a.name).join(', ');
  return {
    id: `yt-${videoId}`,
    youtubeId: videoId,
    title,
    artist: artistLine || 'Unknown artist',
    artists: byline.artists,
    album: byline.album || '',
    albumId: byline.albumId || '',
    year: byline.year || '',
    duration: byline.duration || 0,
    coverUrl: bestThumb(thumbs) || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    // song = official audio track, video = official music video, upload = fan/channel upload
    kind: videoType === 'MUSIC_VIDEO_TYPE_ATV' ? 'song' : videoType === 'MUSIC_VIDEO_TYPE_OMV' ? 'video' : 'upload',
    plays: plays || '',
  };
}

/** Parses a musicResponsiveListItemRenderer (search results, artist top songs). */
function parseListItem(r) {
  const videoId = r.playlistItemData?.videoId
    || r.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId;
  if (!videoId) return null;
  const cols = (r.flexColumns || []).map((c) => c.musicResponsiveListItemFlexColumnRenderer?.text);
  const title = runsText(cols[0]);
  if (!title) return null;
  const subtitleRuns = cols.slice(1).flatMap((c, i) => (i > 0 ? [{ text: ' • ' }, ...(c?.runs || [])] : c?.runs || []));
  const byline = parseByline(subtitleRuns);
  if (!byline.duration) {
    const fixed = (r.fixedColumns || []).map((c) => runsText(c.musicResponsiveListItemFixedColumnRenderer?.text));
    byline.duration = parseDuration(fixed.find((t) => parseDuration(t)) || '');
  }
  const plays = subtitleRuns.map((x) => x.text).find((t) => /plays$/i.test(t || '')) || '';
  return toTrack({ videoId, title, byline, thumbs: r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails, videoType: videoTypeOf(r), plays });
}

/** Parses a playlistPanelVideoRenderer (radio / up next). */
function parsePanelItem(r) {
  if (!r?.videoId) return null;
  const byline = parseByline(r.longBylineText?.runs || []);
  byline.duration = byline.duration || parseDuration(runsText(r.lengthText));
  return toTrack({ videoId: r.videoId, title: runsText(r.title), byline, thumbs: r.thumbnail?.thumbnails, videoType: videoTypeOf(r) });
}

function uniqueById(list) {
  const seen = new Set();
  return list.filter((t) => t && !seen.has(t.youtubeId) && seen.add(t.youtubeId));
}

// ---------- public API ----------

export async function searchSongs(query, kind = 'songs') {
  const data = await call('search', { query, params: FILTER[kind] });
  return uniqueById(collect(data, 'musicResponsiveListItemRenderer').map(parseListItem));
}

export async function searchArtists(query, limit = 4) {
  const data = await call('search', { query, params: FILTER.artists });
  return collect(data, 'musicResponsiveListItemRenderer')
    .map((r) => {
      const id = r.navigationEndpoint?.browseEndpoint?.browseId;
      const cols = (r.flexColumns || []).map((c) => runsText(c.musicResponsiveListItemFlexColumnRenderer?.text));
      if (!id || !id.startsWith('UC')) return null;
      return {
        id,
        name: cols[0],
        audience: (cols[1] || '').replace(/^Artist\s*•\s*/, ''),
        imageUrl: bigCover(r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.slice(-1)[0]?.url, 400),
      };
    })
    .filter(Boolean)
    .slice(0, limit);
}

/** Songs, artists and the "top result" for a free-text (or lyrics) query. */
export async function search(query) {
  const [songs, videos, artists] = await Promise.all([
    searchSongs(query, 'songs'),
    searchSongs(query, 'videos').catch(() => []),
    searchArtists(query, 3).catch(() => []),
  ]);
  // Official music videos only fill gaps (songs missing from the audio catalogue).
  const songKeys = new Set(songs.map((s) => `${s.title.toLowerCase()}::${s.artist.toLowerCase()}`));
  const extraVideos = videos
    .filter((v) => v.kind === 'video' && v.duration >= 90 && v.duration <= 900)
    .filter((v) => versionFlags(v.title).size === 0 && !/jukebox|non-?stop|best of|top \d+|\blyrical\b/i.test(v.title))
    .filter((v) => !songKeys.has(`${v.title.toLowerCase()}::${v.artist.toLowerCase()}`))
    .slice(0, 4);
  // Hide altered versions (instrumental, karaoke, slowed, 8D/jhankar, clips) unless the query asks
  // for them, and list remixes/lofi/covers/live after the originals.
  const asked = versionFlags(query);
  const HIDE = new Set(['instrumental', 'karaoke', 'slowed', 'spedup', 'spatial', 'clip']);
  const flagsOf = (s) => {
    const f = versionFlags(s.title);
    for (const x of versionFlags(s.album, { album: true })) f.add(x);
    return [...f].filter((x) => !asked.has(x));
  };
  const ranked = songs
    .filter((s) => s.duration === 0 || s.duration < 1200)
    .map((s, i) => ({ s, i, flags: flagsOf(s) }))
    .filter(({ flags }) => !flags.some((f) => HIDE.has(f)))
    .sort((a, b) => (a.flags.length ? 1 : 0) - (b.flags.length ? 1 : 0) || a.i - b.i)
    .map(({ s }) => s);
  return { songs: ranked, videos: extraVideos, artists };
}

/** Related tracks ("radio") seeded by a recording. The seed itself is excluded. */
export async function radio(videoId) {
  const data = await call('next', { videoId, playlistId: `RDAMVM${videoId}`, isAudioOnly: true, enablePersistentPlaylistPanel: true });
  return uniqueById(collect(data, 'playlistPanelVideoRenderer').map(parsePanelItem))
    .filter((t) => t.youtubeId !== videoId)
    .filter((t) => versionFlags(t.title).size === 0);
}

export async function artist({ id, name }) {
  let artistId = id;
  if (!artistId && name) artistId = (await searchArtists(name, 1))[0]?.id;
  if (!artistId) return null;
  const page = await call('browse', { browseId: artistId });
  const header = page.header?.musicImmersiveHeaderRenderer || page.header?.musicVisualHeaderRenderer || {};
  const artistName = runsText(header.title) || name || '';
  const shelf = collect(page, 'musicShelfRenderer')[0];
  const top = (shelf?.contents || []).map((c) => c.musicResponsiveListItemRenderer && parseListItem(c.musicResponsiveListItemRenderer)).filter(Boolean);
  // Widen with a song search, keeping only tracks credited to this artist.
  const more = await searchSongs(artistName).catch(() => []);
  const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  const credited = more.filter((t) => t.artists.some((a) => a.id === artistId || norm(a.name) === norm(artistName)));
  const thumbs = header.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || [];
  return {
    id: artistId,
    name: artistName,
    imageUrl: thumbs.length ? thumbs[thumbs.length - 1].url : '',
    audience: runsText(header.monthlyListenerCount) || '',
    songs: uniqueById([...top, ...credited]).slice(0, 30),
  };
}

/** Plain lyrics from YouTube Music (fallback when LRCLIB has nothing). */
export async function lyrics(videoId) {
  const next = await call('next', { videoId });
  const browseId = JSON.stringify(next).match(/"browseId":"(MPLY[^"]+)"/)?.[1];
  if (!browseId) return null;
  const page = await call('browse', { browseId });
  const shelf = collect(page, 'musicDescriptionShelfRenderer')[0];
  const text = runsText(shelf?.description);
  if (!text) return null;
  return { plain: text, source: runsText(shelf?.footer).replace(/^Source:\s*/i, '') };
}

/**
 * Finds the exact recording for a song described by metadata (from a playlist
 * import, old library entries, etc.). Returns null rather than guessing: playing
 * nothing is better than playing the wrong song.
 */
export async function resolve({ title, artist = '', duration = 0, exclude = [] }) {
  const want = { title, artist, duration: Number(duration) || 0 };
  const primaryArtist = artist.split(/\s*(?:,|&|\bfeat\.?|\bft\.?)\s*/i)[0] || '';
  const query = `${title} ${primaryArtist}`.trim();
  const skip = new Set(exclude);

  const rank = (list) => list
    .filter((c) => !skip.has(c.youtubeId))
    .map((c) => ({ c, ...scoreCandidate(want, { ...c, channel: c.artist }) }))
    .sort((a, b) => b.score - a.score);

  const songs = rank(await searchSongs(query, 'songs'));
  const song = songs.find((s) => s.accept);
  if (song) return { track: song.c, confidence: song.score, via: 'song' };

  const videos = rank(await searchSongs(query, 'videos').catch(() => []));
  const video = videos.find((v) => v.accept);
  if (video) return { track: video.c, confidence: video.score, via: 'video' };

  return { track: null, best: songs[0] ? { title: songs[0].c.title, artist: songs[0].c.artist, reason: songs[0].reason } : null };
}
