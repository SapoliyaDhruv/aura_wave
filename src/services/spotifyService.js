/**
 * Spotify Service - Extracts real Spotify playlist metadata & tracklists
 */

export const SPOTIFY_PRESETS = [
  {
    id: '37i9dQZF1DXcBWIGoYBM5M',
    title: "Today's Top Hits",
    owner: 'Spotify',
    cover: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80',
    description: 'The biggest songs right now',
    tracks: [
      { title: 'Espresso', artist: 'Sabrina Carpenter', duration: 175, youtubeId: '51zjlMhdSTE' },
      { title: 'Birds of a Feather', artist: 'Billie Eilish', duration: 198, youtubeId: 'd5gf9dXbPi0' },
      { title: 'Taste', artist: 'Sabrina Carpenter', duration: 157, youtubeId: 'z9Q9OzL_wI8' },
      { title: 'Good Luck, Babe!', artist: 'Chappell Roan', duration: 218, youtubeId: '1RKqOmSkGgM' },
      { title: 'Cruel Summer', artist: 'Taylor Swift', duration: 178, youtubeId: 'ic8j13piAhQ' }
    ]
  },
  {
    id: '37i9dQZF1DX0XUsuxWHRQd',
    title: 'RapCaviar',
    owner: 'Spotify',
    cover: 'https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=600&auto=format&fit=crop&q=80',
    description: 'New music from Kendrick Lamar, Future, Travis Scott',
    tracks: [
      { title: 'Not Like Us', artist: 'Kendrick Lamar', duration: 274, youtubeId: 'H58vbez_m4E' },
      { title: 'Like That', artist: 'Future, Metro Boomin, Kendrick Lamar', duration: 267, youtubeId: 'N9bKBAA22Go' },
      { title: 'FE!N', artist: 'Travis Scott ft. Playboi Carti', duration: 191, youtubeId: 'B9synWjqBn8' },
      { title: 'Type Shit', artist: 'Future, Metro Boomin, Travis Scott, Playboi Carti', duration: 228, youtubeId: 'XcK8JdVlcXY' }
    ]
  },
  {
    id: '37i9dQZF1DX4WYpdgoIcn6',
    title: 'Chill Hits',
    owner: 'Spotify',
    cover: 'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=600&auto=format&fit=crop&q=80',
    description: 'Kick back to the best new and recent chill hits',
    tracks: [
      { title: 'Golden Hour', artist: 'JVKE', duration: 209, youtubeId: 'PEM0Vs8jf1w' },
      { title: 'Until I Found You', artist: 'Stephen Sanchez', duration: 177, youtubeId: 'GxldQ9eX2wo' },
      { title: 'Daylight', artist: 'David Kushner', duration: 212, youtubeId: 'MoN9ql6Yymw' },
      { title: 'Glimpse of Us', artist: 'Joji', duration: 233, youtubeId: 'FvOpPeKSf_4' }
    ]
  }
];

export function extractSpotifyId(input) {
  if (!input) return null;
  const match = input.match(/playlist[\/:]([a-zA-Z0-9]+)/);
  if (match) return match[1];
  if (/^[a-zA-Z0-9]{22}$/.test(input.trim())) {
    return input.trim();
  }
  return null;
}

/**
 * Imports any Spotify playlist using backend scraper or preset fallback
 */
import { getApiBaseUrl } from '../utils/apiBase.js';

export async function importSpotifyPlaylist(urlOrId) {
  const playlistId = extractSpotifyId(urlOrId);
  if (!playlistId) {
    throw new Error('Invalid Spotify playlist link or ID. Please check the URL.');
  }

  // 1. Try our backend scraper endpoint first (which gets ALL actual tracks)
  try {
    const apiBase = getApiBaseUrl();
    const res = await fetch(`${apiBase}/api/spotify/playlist?id=${playlistId}`);
    if (res.ok) {
      const data = await res.json();
      if (data && data.tracks && data.tracks.length > 0) {
        return {
          ...data,
          id: `imported-${playlistId}`,
          isCustom: true,
          isImported: true,
          trackCount: data.tracks.length
        };
      }
    }
  } catch (err) {
    console.warn('Backend Spotify scraper fetch error:', err);
  }

  // 2. Direct client scrape fallback (parses Spotify embed __NEXT_DATA__ directly)
  try {
    const embedRes = await fetch(`https://open.spotify.com/embed/playlist/${playlistId}`, {
      signal: AbortSignal.timeout(6000)
    });
    if (embedRes.ok) {
      const html = await embedRes.text();
      const match = html.match(/<script id="__NEXT_DATA__" type="application\/json">(.*?)<\/script>/);
      if (match) {
        const parsedData = JSON.parse(match[1]);
        const entity = parsedData?.props?.pageProps?.state?.data?.entity;
        if (entity && Array.isArray(entity.trackList) && entity.trackList.length > 0) {
          const name = entity.name || 'Spotify Playlist';
          const cover = entity.visualIdentity?.image?.[0]?.url || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80';
          const tracks = entity.trackList.map((t, idx) => ({
            id: `sp-${playlistId}-${idx}-${Date.now()}`,
            title: t.title || 'Untitled Track',
            artist: t.subtitle || 'Unknown Artist',
            album: name,
            duration: t.duration ? Math.round(t.duration / 1000) : 180,
            coverUrl: cover,
            themeColor: '#1DB954',
            secondaryColor: '#3b82f6'
          }));

          return {
            id: `imported-${playlistId}`,
            name,
            description: `Imported from Spotify • ${tracks.length} tracks`,
            cover,
            isCustom: true,
            isImported: true,
            trackCount: tracks.length,
            tracks
          };
        }
      }
    }
  } catch (clientErr) {
    console.warn('Direct client Spotify scrape note:', clientErr);
  }

  // 3. Check if it matches a preset
  const preset = SPOTIFY_PRESETS.find(p => p.id === playlistId);
  if (preset) {
    const presetTracks = (preset.tracks || []).map((t, idx) => ({
      id: `sp-track-${preset.id}-${idx}`,
      title: t.title,
      artist: t.artist,
      album: preset.title,
      duration: t.duration,
      coverUrl: preset.cover,
      youtubeId: t.youtubeId,
      themeColor: '#1DB954',
      secondaryColor: '#1ed760'
    }));

    return {
      id: `imported-${preset.id}`,
      name: preset.title,
      description: preset.description,
      cover: preset.cover,
      isCustom: true,
      isImported: true,
      trackCount: presetTracks.length,
      tracks: presetTracks
    };
  }

  // 4. Do NOT inject fake placeholder songs. Throw clear error so user knows exact issue.
  throw new Error('Unable to extract tracks from this Spotify playlist. Please check that the playlist is public and your internet connection is active.');
}

// Client-side persistent cache with automatic legacy purge
const YT_CACHE_VERSION = 'v3_studio_masters';
try {
  if (typeof localStorage !== 'undefined' && localStorage.getItem('yt_cache_ver') !== YT_CACHE_VERSION) {
    Object.keys(localStorage).forEach(k => {
      if (k.startsWith('yt_cache_')) localStorage.removeItem(k);
    });
    localStorage.setItem('yt_cache_ver', YT_CACHE_VERSION);
  }
} catch (_) {}

const clientCache = new Map();

function getCachedYtId(key) {
  if (clientCache.has(key)) return clientCache.get(key);
  try {
    const stored = localStorage.getItem(`yt_cache_${key}`);
    if (stored) {
      clientCache.set(key, stored);
      return stored;
    }
  } catch (_) {}
  return null;
}

function setCachedYtId(key, id) {
  clientCache.set(key, id);
  try {
    localStorage.setItem(`yt_cache_${key}`, id);
  } catch (_) {}
}

export function clearYtCache(title, artist) {
  if (!title) return;
  const cleanTitle = (title || '')
    .replace(/\(feat\..*?\)/i, '')
    .replace(/\[.*?\]/g, '')
    .replace(/\(from ".*?"\)/i, '')
    .trim();
  const cleanArtist = (artist || '').split(',')[0].trim();
  const cacheKey = `${cleanTitle.toLowerCase()}:::${cleanArtist.toLowerCase()}`;
  clientCache.delete(cacheKey);
  try {
    localStorage.removeItem(`yt_cache_${cacheKey}`);
  } catch (_) {}
}

const inFlightResolutions = new Map();

/**
 * Resolves a song title and artist to a YouTube Video ID via backend search,
 * with immediate caching, in-flight deduplication, and fast timeout.
 */
export async function resolveYouTubeTrack(title, artist, forceFresh = false, excludeId = null, duration = 0) {
  if (!title) return null;
  const cleanTitle = (title || '')
    .replace(/\(feat\..*?\)/i, '')
    .replace(/\[.*?\]/g, '')
    .replace(/\(from ".*?"\)/i, '')
    .trim();
  const cleanArtist = (artist || '').split(',')[0].trim();
  const cacheKey = `${cleanTitle.toLowerCase()}:::${cleanArtist.toLowerCase()}`;

  if (!forceFresh && !excludeId) {
    const cached = getCachedYtId(cacheKey);
    if (cached) return cached;
    if (inFlightResolutions.has(cacheKey)) {
      return inFlightResolutions.get(cacheKey);
    }
  } else if (forceFresh) {
    clearYtCache(title, artist);
    inFlightResolutions.delete(cacheKey);
  }

  const promise = (async () => {
    try {
      const apiBase = getApiBaseUrl();
      let url = `${apiBase}/api/youtube/search?title=${encodeURIComponent(cleanTitle)}&artist=${encodeURIComponent(cleanArtist)}`;
      if (duration > 0) {
        url += `&duration=${encodeURIComponent(duration)}`;
      }
      if (excludeId) {
        url += `&exclude=${encodeURIComponent(excludeId)}`;
      }
      const res = await fetch(url, { signal: AbortSignal.timeout(6500) });
      if (res.ok) {
        const data = await res.json();
        if (data && data.videoId) {
          setCachedYtId(cacheKey, data.videoId);
          return data.videoId;
        }
      }
    } catch (err) {
      console.warn('Failed to resolve YouTube track:', err);
    } finally {
      inFlightResolutions.delete(cacheKey);
    }
    return null;
  })();

  if (!forceFresh && !excludeId) {
    inFlightResolutions.set(cacheKey, promise);
  }

  return promise;
}

/**
 * Pre-warms video ID resolution for upcoming queue songs in background
 */
export function prewarmTrackResolution(track) {
  if (!track || track.youtubeId || track.audioUrl) return;
  resolveYouTubeTrack(track.title, track.artist, false, null, track.duration).then(ytId => {
    if (ytId) track.youtubeId = ytId;
  }).catch(() => {});
}
