import { CURATED_TRACKS, normalizeTrackName, normalizeArtistName } from '../data/curatedTracks.js';
import { getApiBaseUrl } from '../utils/apiBase.js';

export function inferGenreAndLanguage(raw, country = 'US') {
  const primary = (raw.primaryGenreName || raw.genre || '').toLowerCase();
  const artist = (raw.artistName || raw.artist || '').toLowerCase();
  const title = (raw.trackName || raw.title || '').toLowerCase();
  const album = (raw.collectionName || raw.album || '').toLowerCase();

  // 1. Language detection
  let language = 'english';
  const hasDevanagari = /[\u0900-\u097F]/.test(title + ' ' + artist + ' ' + album);
  const hindiArtists = [
    'arijit', 'pritam', 'mithoon', 'sachin', 'jigar', 'tanishk', 'jubin', 'badshah',
    'karan aujla', 'diljit', 'paradox', 'sujal', 'shreya', 'neha kakkar', 'sonu nigam',
    'alka yagnik', 'kumar sanu', 'sid sriram', 'anirudh', 'vishal', 'shekhar', 'mc stan',
    'seedhe maut', 'divine', 'raftaar', 'kr$na', 'king', 'honey singh', 'darshan raval',
    'ar rahman', 'b praak', 'jaani', 'jasleen', 'shankar', 'ehsaan', 'loy', 'amit trivedi',
    'hardy sandhu', 'guru randhawa', 'shubh', 'ap dhillon', 'sidhu moose wala', 'amrit maan'
  ];
  const isHindiArtist = hindiArtists.some(ha => artist.includes(ha));

  if (hasDevanagari || isHindiArtist || country === 'IN' || primary.includes('bollywood') || primary.includes('indian')) {
    language = 'hindi';
  }

  // 2. Genre detection
  let genre = 'pop';
  if (primary.includes('hip-hop') || primary.includes('rap') || artist.includes('karan aujla') || artist.includes('paradox') || artist.includes('sujal') || artist.includes('drake') || artist.includes('kendrick') || artist.includes('travis scott') || artist.includes('hanumankind') || artist.includes('eminem') || artist.includes('stan') || artist.includes('seedhe maut') || artist.includes('divine') || artist.includes('krsna') || artist.includes('raftaar')) {
    genre = 'hiphop';
  } else if (primary.includes('bollywood') || (language === 'hindi' && !primary.includes('hip-hop') && !primary.includes('rap'))) {
    genre = 'bollywood';
  } else if (primary.includes('r&b') || primary.includes('soul') || artist.includes('tai verdes') || artist.includes('the weeknd') || artist.includes('sza') || artist.includes('frank ocean')) {
    genre = 'rnb';
  } else if (primary.includes('rock') || primary.includes('metal') || primary.includes('alternative') || primary.includes('indie')) {
    genre = 'rock';
  } else if (primary.includes('dance') || primary.includes('electronic') || primary.includes('house') || primary.includes('edm')) {
    genre = 'electronic';
  } else if (primary.includes('pop') || artist.includes('sabrina') || artist.includes('billie') || artist.includes('taylor swift') || artist.includes('harry styles') || artist.includes('dua lipa') || artist.includes('chappell')) {
    genre = 'pop';
  } else if (primary) {
    genre = primary;
  }

  return { genre, language };
}

export const SEARCH_QUERY_ALIASES = {
  'sonio': 'Soniyo',
  'soniyo': 'Soniyo',
  'shelume': 'sheluvme',
  'sheluv me': 'sheluvme',
  'she love me': 'sheluvme',
  'tauba': 'Tauba Tauba',
  'kesariya': 'Kesariya',
  'chaleya': 'Chaleya',
  'apna bana le': 'Apna Bana Le',
  'tum hi ho': 'Tum Hi Ho',
  'o mahi': 'O Maahi',
  'raatan': 'Raataan Lambiyan',
  'zara sa': 'Zara Sa',
  'tu jaane na': 'Tu Jaane Na',
  'pee loon': 'Pee Loon',
  'tum se hi': 'Tum Se Hi',
  'dil ibadat': 'Dil Ibaadat',
  'tera hone laga': 'Tera Hone Laga Hoon',
  'labon ko': 'Labon Ko',
  'mat aazma': 'Mat Aazma Re',
  'kal ho na ho': 'Kal Ho Naa Ho'
};

export function resolveSearchQueryAliases(query) {
  if (!query) return query;
  const qLower = query.toLowerCase().trim();
  if (SEARCH_QUERY_ALIASES[qLower]) return SEARCH_QUERY_ALIASES[qLower];
  for (const [key, val] of Object.entries(SEARCH_QUERY_ALIASES)) {
    if (qLower === key || qLower.startsWith(key + ' ') || qLower.endsWith(' ' + key)) {
      return val;
    }
  }
  return query;
}

export function fuzzyPhonetic(s) {
  if (!s) return '';
  return s.toLowerCase()
    .replace(/[^a-z0-9]/g, '')
    .replace(/y/g, 'i')
    .replace(/ee/g, 'i')
    .replace(/oo/g, 'u')
    .replace(/aa/g, 'a');
}

export async function searchGlobalMusic(query, language = 'all') {
  if (!query || !query.trim()) return [];

  const cleanQuery = query.trim();
  const resolvedQuery = resolveSearchQueryAliases(cleanQuery);
  const normQuery = normalizeTrackName(resolvedQuery);
  const normArtistQuery = normalizeArtistName(resolvedQuery);
  const fuzzyQuery = fuzzyPhonetic(cleanQuery);

  const resultsMap = new Map();

  let itunesTerm = resolvedQuery;
  if (/sicklord/i.test(itunesTerm)) {
    itunesTerm = itunesTerm.replace(/sicklord/gi, 'sicklot');
  }

  // 1. Prioritize 100% verified Curated Master Tracks at top of search results
  CURATED_TRACKS.forEach(ct => {
    const ctTitleNorm = normalizeTrackName(ct.title);
    const ctArtistNorm = normalizeArtistName(ct.artist);
    const ctFuzzyTitle = fuzzyPhonetic(ct.title);

    const matchesTitle = normQuery.length >= 2 && (ctTitleNorm.includes(normQuery) || normQuery.includes(ctTitleNorm));
    const matchesFuzzyTitle = fuzzyQuery.length >= 3 && (ctFuzzyTitle.includes(fuzzyQuery) || fuzzyQuery.includes(ctFuzzyTitle));
    const matchesArtist = normArtistQuery.length >= 2 && (ctArtistNorm.includes(normArtistQuery) || normArtistQuery.includes(ctArtistNorm));
    const isSickLordAlias = (cleanQuery.toLowerCase().includes('sicklord') || cleanQuery.toLowerCase().includes('sicklot')) &&
      ct.artist.toLowerCase().includes('sicklot');
    const rawMatches = ct.title.toLowerCase().includes(cleanQuery.toLowerCase()) || 
                       ct.artist.toLowerCase().includes(cleanQuery.toLowerCase()) ||
                       ct.title.toLowerCase().includes(resolvedQuery.toLowerCase()) ||
                       (ct.album && ct.album.toLowerCase().includes(cleanQuery.toLowerCase())) ||
                       isSickLordAlias;

    if (matchesTitle || matchesFuzzyTitle || matchesArtist || rawMatches) {
      const key = `${ct.title.toLowerCase()}-${ct.artist.toLowerCase()}`;
      resultsMap.set(key, { ...ct });
    }
  });

  // Determine countries to search based on language preference
  let countries = ['IN', 'US'];
  if (language === 'hindi') countries = ['IN'];
  if (language === 'english') countries = ['US', 'GB'];

  for (const country of countries) {
    try {
      const url = `https://itunes.apple.com/search?term=${encodeURIComponent(itunesTerm)}&entity=song&country=${country}&limit=20`;
      const res = await fetch(url);
      if (res.ok) {
        const data = await res.json();
        if (data && Array.isArray(data.results)) {
          data.results.forEach((r, idx) => {
            const rawTrackName = r.trackName || '';
            const rawArtistName = r.artistName || '';
            const lowerTrack = rawTrackName.toLowerCase();
            const lowerArtist = rawArtistName.toLowerCase();

            // Disqualify unwanted karaoke, instrumental, opera/irrelevant non-target songs
            if (
              lowerTrack.includes('karaoke') ||
              lowerTrack.includes('instrumental') ||
              lowerTrack.includes('tribute') ||
              lowerTrack.includes('backing track') ||
              lowerTrack.includes('ringtone') ||
              lowerTrack.includes('ah! non son') // Classical Italian opera
            ) {
              return;
            }

            const normTitle = normalizeTrackName(rawTrackName);
            const normArtist = normalizeArtistName(rawArtistName);
            const key = `${normTitle}-${normArtist}`;

            // If we are searching for Soniyo and already have Sonu Nigam's version, discard alternate versions like Krishna Beura or generic covers
            const isSoniyoQuery = fuzzyQuery === 'sonio' || normQuery === 'soniyo';
            if (isSoniyoQuery) {
              if (lowerTrack.includes('from the heart') || !lowerArtist.includes('sonu nigam')) {
                return;
              }
            }

            // If results already contain a canonical track with this base title (e.g. Soniyo),
            // do NOT flood results with remixes, lofi flips, or alternate cuts of the exact same song
            const baseTitleKey = normTitle;
            const alreadyHasBaseTitle = Array.from(resultsMap.values()).some(
              existing => normalizeTrackName(existing.title) === baseTitleKey
            );
            if (alreadyHasBaseTitle) {
              return;
            }

            if (!resultsMap.has(key)) {
              const stableId = r.trackId
                ? `itunes-${r.trackId}`
                : `track-${normTitle || 'song'}-${normArtist || idx}`;

              const { genre, language: inferredLang } = inferGenreAndLanguage(r, country);

              resultsMap.set(key, {
                id: stableId,
                title: r.trackName,
                artist: r.artistName,
                album: r.collectionName || 'Single',
                duration: Math.round((r.trackTimeMillis || 180000) / 1000),
                coverUrl: (r.artworkUrl100 || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80').replace('100x100bb', '600x600bb'),
                themeColor: '#1DB954',
                secondaryColor: '#8b5cf6',
                genre,
                language: inferredLang,
                primaryGenreName: r.primaryGenreName || genre
              });
            }
          });
        }
      }
    } catch (err) {
      console.warn(`Search error for country ${country}:`, err);
    }
  }

  // If iTunes direct fetch produced results, return them
  if (resultsMap.size > 0) {
    return Array.from(resultsMap.values());
  }

  // Fallback to server proxy endpoint
  try {
    const apiBase = getApiBaseUrl();
    const res = await fetch(`${apiBase}/api/music/search?q=${encodeURIComponent(cleanQuery)}`);
    if (res.ok) {
      const data = await res.json();
      if (data && data.results) {
        return data.results;
      }
    }
  } catch (e) {
    console.warn('Backend search error:', e);
  }

  return [];
}
