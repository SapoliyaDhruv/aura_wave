/**
 * AuraWave Next-Gen Recommendation Engine
 * Provides state-of-the-art personalized recommendations, dynamic artist radio,
 * acoustic vibe & BPM vector continuity, skip-dwell feedback loop, endless online radio ingestion,
 * and rich "Based On Your Likes" intelligence.
 */
import { searchGlobalMusic, inferGenreAndLanguage } from './searchService.js';
import { CURATED_TRACKS, normalizeTrackName, normalizeArtistName } from '../data/curatedTracks.js';
import { EXPANDED_CATALOG } from '../data/expandedCatalog.js';
import { SPOTIFY_PRESETS } from './spotifyService.js';
import { isDummyTrack, storageService } from './storageService.js';

// Pre-build preset tracks pool
const PRESET_TRACKS = [];
(SPOTIFY_PRESETS || []).forEach(preset => {
  (preset.tracks || []).forEach((t, idx) => {
    const { genre, language } = inferGenreAndLanguage(t);
    PRESET_TRACKS.push({
      id: `preset-${preset.id}-${idx}-${normalizeTrackName(t.title)}`,
      title: t.title,
      artist: t.artist,
      duration: t.duration || 180,
      album: preset.title,
      coverUrl: preset.cover,
      themeColor: '#1DB954',
      secondaryColor: '#8b5cf6',
      youtubeId: t.youtubeId,
      genre,
      language
    });
  });
});

// Dynamic Online Radio Ingestion Store (populated via background streaming discovery)
const dynamicOnlineCatalog = new Map();

// In-Memory Session Play Tracking to eliminate track fatigue and immediate repeats
const sessionPlayedSet = new Set();
const sessionPlayOrder = [];

// Skip-Dwell Real-Time Feedback State
const sessionArtistAffinity = {}; // artist -> score delta (positive or negative)
const sessionMoodAffinity = {};   // mood -> score delta
const sessionSuppressedArtists = new Set(); // temporarily suppressed artists from rapid skips

/**
 * Infer acoustic mood, energy level, estimated BPM, and valence (Spotify Echo Nest style)
 */
export function inferAcousticFeatures(track) {
  if (!track) return { mood: 'romantic', energy: 'medium', estimatedBpm: 90, valence: 0.65 };

  // If already tagged in catalog, use high-fidelity values
  if (track.mood && track.energy) {
    return {
      mood: track.mood,
      energy: track.energy,
      estimatedBpm: track.estimatedBpm || 95,
      valence: track.valence !== undefined ? track.valence : 0.65
    };
  }

  const title = (track.title || '').toLowerCase();
  const artist = (track.artist || '').toLowerCase();
  const album = (track.album || '').toLowerCase();
  const genre = (track.genre || '').toLowerCase();
  const text = `${title} ${artist} ${album} ${genre}`;

  let mood = 'romantic';
  let energy = 'medium';
  let estimatedBpm = 95;
  let valence = 0.65;

  // 1. High Energy Party & Dance (BPM: 120-135, High Valence)
  if (
    /ghungroo|nashe si|badtameez|kar gayi chull|subha hone|kala chashma|aankh marey|garmi|party|dance|club|dj|remix|bhangra|dhol|nach|thumka|saturday|hookah|lat lag|high energy|drop/i.test(text)
  ) {
    mood = 'party';
    energy = 'high';
    estimatedBpm = 128;
    valence = 0.88;
  }
  // 2. High Energy Trap, Drill & Hype Rap (BPM: 125-145)
  else if (
    genre === 'hiphop' || /hustle|trap|rap|bars|cheques|winning speech|big dawgs|baller|not like us|fe!n|goosebumps|sicko mode|god's plan|3:59|nanchaku|namastute|mirchi|basti ka hasti/i.test(text)
  ) {
    mood = 'hype';
    energy = 'high';
    estimatedBpm = 135;
    valence = 0.65;
  }
  // 3. Low Energy Heartbreak & Melancholy (BPM: 65-80, Low Valence)
  else if (
    /channa mereya|ae dil|tujhe bhula|judai|bekhayali|agar tum|glimpse of us|daylight|until i found|breakup|sad|dard|rona|tanha|alvida|phir bhi tumko|hamari adhuri|teri mitti|hasi|sun raha|saiyyan|dark/i.test(text)
  ) {
    mood = 'heartbreak';
    energy = 'low';
    estimatedBpm = 72;
    valence = 0.20;
  }
  // 4. Low Energy Acoustic, Peaceful & Chill (BPM: 75-85)
  else if (
    /acoustic|unplugged|baarishein|alag aasmaan|kasoor|cold\/mess|anuv jain|prateek kuhad|golden hour|die for you|save your tears|stay|sweater weather|heat waves|chill|lofi/i.test(text)
  ) {
    mood = 'chill';
    energy = 'low';
    estimatedBpm = 80;
    valence = 0.58;
  }
  // 5. Medium Energy Romantic Love & Melodic Soul (BPM: 85-98)
  else if (
    /soniyo|zara sa|tu jaane na|pee loon|tum se hi|dil ibaadat|tera hone|kesariya|apna bana le|o maahi|raataan|kal ho naa|suraj hua|main hoon na|kun faya|iktara|hawayein|mast magan|samjhawan|love|romantic|soul|saibo/i.test(text)
  ) {
    mood = 'romantic';
    energy = 'medium';
    estimatedBpm = 90;
    valence = 0.75;
  }
  // 6. Upbeat Pop Groove (BPM: 105-120)
  else if (
    genre === 'pop' || /espresso|taste|please please|wildflower|cruel summer|anti-hero|good luck|blinding lights|starboy|as it was|lover|with you|maan meri jaan|sheluvme|a-o-k/i.test(text)
  ) {
    mood = 'groove';
    energy = 'medium';
    estimatedBpm = 112;
    valence = 0.82;
  }

  return { mood, energy, estimatedBpm, valence };
}

/**
 * Calculates acoustic transition compatibility between two songs to prevent mood clashes
 */
function getAcousticCompatibilityScore(currentFeatures, candidateFeatures) {
  let score = 0;

  // 1. Energy Continuity Matching
  if (currentFeatures.energy === candidateFeatures.energy) {
    score += 260; // Perfect energy continuity
  } else if (
    (currentFeatures.energy === 'low' && candidateFeatures.energy === 'medium') ||
    (currentFeatures.energy === 'medium' && candidateFeatures.energy === 'low') ||
    (currentFeatures.energy === 'medium' && candidateFeatures.energy === 'high') ||
    (currentFeatures.energy === 'high' && candidateFeatures.energy === 'medium')
  ) {
    score += 90; // Natural 1-step energy transition
  } else {
    // Extreme energy jump (e.g. peaceful acoustic heartbreak to intense club banger) is heavily penalized!
    score -= 450;
  }

  // 2. Mood & Vibe Affinity Matching
  if (currentFeatures.mood === candidateFeatures.mood) {
    score += 240;
  } else if (
    (currentFeatures.mood === 'heartbreak' && (candidateFeatures.mood === 'chill' || candidateFeatures.mood === 'romantic')) ||
    (currentFeatures.mood === 'romantic' && (candidateFeatures.mood === 'chill' || candidateFeatures.mood === 'groove')) ||
    (currentFeatures.mood === 'party' && (candidateFeatures.mood === 'hype' || candidateFeatures.mood === 'groove')) ||
    (currentFeatures.mood === 'hype' && (candidateFeatures.mood === 'party' || candidateFeatures.mood === 'groove'))
  ) {
    score += 120;
  } else if (
    (currentFeatures.mood === 'heartbreak' && candidateFeatures.mood === 'party') ||
    (currentFeatures.mood === 'party' && candidateFeatures.mood === 'heartbreak')
  ) {
    // Severe clash: never transition directly from heartbreak tears to club dancing!
    score -= 600;
  }

  // 3. Emotional Valence Proximity
  const valenceDiff = Math.abs((currentFeatures.valence || 0.5) - (candidateFeatures.valence || 0.5));
  if (valenceDiff < 0.2) score += 100;
  else if (valenceDiff > 0.55) score -= 180;

  return score;
}

// Build unified master catalog (merging built-in tracks and dynamically ingested online radio)
function getUnifiedCatalog(customTracks = []) {
  const fullPool = [
    ...(Array.isArray(customTracks) ? customTracks : []),
    ...CURATED_TRACKS,
    ...EXPANDED_CATALOG,
    ...PRESET_TRACKS,
    ...Array.from(dynamicOnlineCatalog.values())
  ];

  const seenKeys = new Set();
  const valid = [];
  fullPool.forEach(t => {
    if (!t || isDummyTrack(t)) return;
    const key = `${normalizeTrackName(t.title)}-${normalizeArtistName(t.artist)}`;
    if (seenKeys.has(key)) return;
    seenKeys.add(key);
    const { genre, language } = inferGenreAndLanguage(t);
    const acoustic = inferAcousticFeatures(t);
    valid.push({
      ...t,
      genre: t.genre || genre,
      language: t.language || language,
      mood: t.mood || acoustic.mood,
      energy: t.energy || acoustic.energy,
      estimatedBpm: t.estimatedBpm || acoustic.estimatedBpm,
      valence: t.valence !== undefined ? t.valence : acoustic.valence
    });
  });
  return valid;
}

// Curated Artist Similarity Network
const ARTIST_SIMILARITY_GRAPH = {
  'sonu nigam': ['kk', 'shreya ghoshal', 'atif aslam', 'mohit chauhan', 'shaan', 'javed ali', 'pritam', 'arijit singh', 'alka yagnik', 'kumar sanu', 'udit narayan'],
  'kk': ['sonu nigam', 'atif aslam', 'mohit chauhan', 'pritam', 'shaan', 'javed ali', 'arijit singh', 'mustafa zahid'],
  'arijit singh': ['pritam', 'mithoon', 'sachin-jigar', 'atif aslam', 'kk', 'mohit chauhan', 'jubin nautiyal', 'jasleen royal', 'shreya ghoshal', 'vishal mishra', 'sachet tandon'],
  'atif aslam': ['kk', 'sonu nigam', 'mohit chauhan', 'pritam', 'arijit singh', 'mithoon', 'mustafa zahid', 'ali zafar'],
  'mohit chauhan': ['kk', 'sonu nigam', 'atif aslam', 'pritam', 'ar rahman', 'javed ali', 'lucky ali'],
  'shreya ghoshal': ['sonu nigam', 'arijit singh', 'pritam', 'shaan', 'alka yagnik', 'sunidhi chauhan'],
  'pritam': ['arijit singh', 'kk', 'atif aslam', 'mohit chauhan', 'sonu nigam', 'shreya ghoshal'],
  'anuv jain': ['prateek kuhad', 'jasleen royal', 'aditya rikhari', 'dream note', 'the local train', 'zaeden', 'mitraz'],
  'prateek kuhad': ['anuv jain', 'jasleen royal', 'aditya rikhari', 'the local train', 'bayaan'],
  'karan aujla': ['shubh', 'diljit dosanjh', 'ap dhillon', 'sidhu moose wala', 'divine', 'paradox', 'naam sujal', 'ikky', 'amrit maan'],
  'shubh': ['karan aujla', 'diljit dosanjh', 'ap dhillon', 'sidhu moose wala', 'divine'],
  'diljit dosanjh': ['karan aujla', 'ap dhillon', 'shubh', 'ammy virk', 'guru randhawa'],
  'ap dhillon': ['gurinder gill', 'shinda kahlon', 'karan aujla', 'shubh', 'diljit dosanjh'],
  'naam sujal': ['paradox', 'aditya pushkarna', 'karan aujla', 'divine', 'mc stan', 'seedhe maut'],
  'paradox': ['naam sujal', 'karan aujla', 'mc stan', 'seedhe maut', 'divine', 'raftaar', 'krsna'],
  'divine': ['raftaar', 'krsna', 'mc stan', 'seedhe maut', 'karan aujla', 'shubh'],
  'mc stan': ['divine', 'seedhe maut', 'raftaar', 'krsna', 'paradox'],
  'seedhe maut': ['mc stan', 'divine', 'krsna', 'raftaar', 'paradox'],
  'the weeknd': ['daft punk', 'travis scott', 'drake', 'post malone', 'bruno mars', 'sza', 'frank ocean'],
  'sabrina carpenter': ['billie eilish', 'olivia rodrigo', 'taylor swift', 'chappell roan', 'dua lipa', 'ariana grande'],
  'billie eilish': ['sabrina carpenter', 'olivia rodrigo', 'taylor swift', 'phoebe bridgers', 'chappell roan'],
  'taylor swift': ['sabrina carpenter', 'billie eilish', 'olivia rodrigo', 'gracie abrams'],
  'tai verdes': ['glass animals', 'dominic fike', 'surfaces', 'jvke', 'rex orange county', 'still woozy', 'peach tree rascals'],
  'kendrick lamar': ['future', 'metro boomin', 'travis scott', 'drake', 'j cole', 'asap rocky'],
  'travis scott': ['kendrick lamar', 'future', 'metro boomin', 'playboi carti', 'don toliver'],
  'jvke': ['stephen sanchez', 'david kushner', 'joji', 'alec benjamin', 'benson boone'],
  'stephen sanchez': ['jvke', 'david kushner', 'laufey', 'joji'],
  'david kushner': ['stephen sanchez', 'jvke', 'hozier', 'dean lewis']
};

/**
 * Returns list of similar artists based on cluster graph
 */
function getSimilarArtists(artistName) {
  if (!artistName) return [];
  const lower = artistName.toLowerCase().trim();
  const matches = new Set();
  
  for (const [key, cluster] of Object.entries(ARTIST_SIMILARITY_GRAPH)) {
    if (lower.includes(key) || key.includes(lower)) {
      cluster.forEach(a => matches.add(a));
    }
  }
  return Array.from(matches);
}

/**
 * Controlled shuffle with temperature
 */
function shuffleWithTemperature(items, temperature = 0.5) {
  const result = [...items];
  for (let i = result.length - 1; i > 0; i--) {
    const swapWindow = Math.max(1, Math.floor(i * temperature));
    const j = Math.max(0, i - Math.floor(Math.random() * swapWindow));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

export const recommendationService = {
  /**
   * Records a track played during the active session to prevent repetition
   */
  recordTrackPlayed(track) {
    if (!track || !track.title) return;
    const norm = normalizeTrackName(track.title);
    sessionPlayedSet.add(norm);
    sessionPlayOrder.push(norm);
    if (sessionPlayOrder.length > 35) {
      const oldest = sessionPlayOrder.shift();
      sessionPlayedSet.delete(oldest);
    }
  },

  /**
   * Skip-Dwell Negative Feedback:
   * If a user skips a song within 5 seconds, penalize that artist and mood in the active session
   * and temporarily suppress the artist to avoid repeat annoyance!
   */
  recordEarlySkip(track, dwellSeconds = 0) {
    if (!track) return;
    const primaryArtist = (track.artist || '').split(/[,&]/)[0].trim().toLowerCase();
    const acoustic = inferAcousticFeatures(track);

    if (primaryArtist) {
      sessionArtistAffinity[primaryArtist] = (sessionArtistAffinity[primaryArtist] || 0) - 150;
      sessionSuppressedArtists.add(primaryArtist);
      // Suppress for 15 tracks
      setTimeout(() => sessionSuppressedArtists.delete(primaryArtist), 45000);
    }

    if (acoustic && acoustic.mood) {
      sessionMoodAffinity[acoustic.mood] = (sessionMoodAffinity[acoustic.mood] || 0) - 80;
    }
  },

  /**
   * Skip-Dwell Positive Feedback:
   * If a user listens deeply (>45s or >65% duration), heavily boost that artist & mood affinity!
   */
  recordTrackCompleted(track, dwellSeconds = 0) {
    if (!track) return;
    const primaryArtist = (track.artist || '').split(/[,&]/)[0].trim().toLowerCase();
    const acoustic = inferAcousticFeatures(track);

    if (primaryArtist) {
      sessionArtistAffinity[primaryArtist] = (sessionArtistAffinity[primaryArtist] || 0) + 100;
    }

    if (acoustic && acoustic.mood) {
      sessionMoodAffinity[acoustic.mood] = (sessionMoodAffinity[acoustic.mood] || 0) + 60;
    }
  },

  /**
   * Dynamic Online Radio Ingestion:
   * Queries online catalog (iTunes/Apple Music API) in the background for 25+ songs
   * from the active artist and similar peer artists. Ingests them directly into memory
   * so the catalog effectively becomes INFINITE with zero cold-catalog limits!
   */
  async ingestArtistRadioOnline(artistName, language = 'hindi', currentMood = 'romantic') {
    if (!artistName || !artistName.trim()) return [];
    const cleanArtist = artistName.trim();
    const similarArtists = getSimilarArtists(cleanArtist);
    const artistsToQuery = [cleanArtist, ...(similarArtists.slice(0, 2))];

    const newlyIngested = [];

    for (const art of artistsToQuery) {
      try {
        const country = language === 'english' ? 'US' : 'IN';
        const url = `https://itunes.apple.com/search?term=${encodeURIComponent(art)}&entity=song&country=${country}&limit=20`;
        const res = await fetch(url);
        if (res.ok) {
          const data = await res.json();
          if (data && Array.isArray(data.results)) {
            data.results.forEach((r, idx) => {
              const rawTrackName = r.trackName || '';
              const rawArtistName = r.artistName || '';
              const lowerTrack = rawTrackName.toLowerCase();

              // Disqualify unwanted karaoke, instrumental, opera/cover noise
              if (
                lowerTrack.includes('karaoke') ||
                lowerTrack.includes('instrumental') ||
                lowerTrack.includes('tribute') ||
                lowerTrack.includes('backing track') ||
                lowerTrack.includes('ringtone')
              ) {
                return;
              }

              const normTitle = normalizeTrackName(rawTrackName);
              const normArtist = normalizeArtistName(rawArtistName);
              const key = `${normTitle}-${normArtist}`;

              if (!dynamicOnlineCatalog.has(key)) {
                const stableId = r.trackId
                  ? `radio-itunes-${r.trackId}`
                  : `track-${normTitle || 'song'}-${normArtist || idx}`;

                const { genre, language: inferredLang } = inferGenreAndLanguage(r, country);
                const acoustic = inferAcousticFeatures({
                  title: r.trackName,
                  artist: r.artistName,
                  album: r.collectionName,
                  genre
                });

                const trackObj = {
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
                  mood: acoustic.mood,
                  energy: acoustic.energy,
                  estimatedBpm: acoustic.estimatedBpm,
                  valence: acoustic.valence
                };

                dynamicOnlineCatalog.set(key, trackObj);
                newlyIngested.push(trackObj);
              }
            });
          }
        }
      } catch (err) {
        console.warn(`Dynamic radio fetch error for artist ${art}:`, err);
      }
    }

    return newlyIngested;
  },

  /**
   * Generates recommendations directly based on tracks and artists the user has Liked / Favorited.
   */
  getRecommendationsBasedOnLikes(favoriteIds = [], allCatalogTracks = []) {
    const validTracks = getUnifiedCatalog(allCatalogTracks);
    const favoriteSet = new Set(favoriteIds || []);
    const favMeta = (typeof storageService !== 'undefined' && storageService.getFavoritesMeta) 
      ? storageService.getFavoritesMeta() 
      : {};

    const favoriteTracks = [];
    favoriteSet.forEach(fId => {
      const match = validTracks.find(t => t.id === fId);
      if (match) {
        favoriteTracks.push(match);
      } else if (favMeta && favMeta[fId]) {
        favoriteTracks.push(favMeta[fId]);
      }
    });

    if (favoriteTracks.length === 0) {
      const popularTracks = validTracks.filter(t => 
        (t.genre === 'bollywood' || t.genre === 'pop' || t.genre === 'hiphop') && !favoriteSet.has(t.id)
      );
      const shuffled = shuffleWithTemperature(popularTracks, 0.7).slice(0, 10);
      return {
        tag: 'EXPLORE POPULAR HITS',
        reason: 'Most loved tracks on AuraWave • Like songs to customize your radio',
        tracks: shuffled.length > 0 ? shuffled : validTracks.slice(0, 10)
      };
    }

    const favArtists = new Set();
    const favGenres = new Set();
    const favLanguages = new Set();
    const favMoods = new Set();
    const similarArtists = new Set();

    favoriteTracks.forEach(t => {
      if (t.artist) {
        const parts = t.artist.split(/[,&]/).map(a => a.trim().toLowerCase());
        parts.forEach(p => {
          if (p.length >= 3) {
            favArtists.add(p);
            getSimilarArtists(p).forEach(sa => similarArtists.add(sa));
          }
        });
      }
      if (t.genre) favGenres.add(t.genre.toLowerCase().trim());
      if (t.language) favLanguages.add(t.language.toLowerCase().trim());
      if (t.mood) favMoods.add(t.mood);
    });

    // Score non-favorited catalog tracks
    const scored = validTracks
      .filter(t => !favoriteSet.has(t.id))
      .map(t => {
        let score = 0;
        const tArtist = (t.artist || '').toLowerCase();
        const tGenre = (t.genre || '').toLowerCase().trim();
        const tLang = (t.language || '').toLowerCase().trim();
        const normKey = normalizeTrackName(t.title);

        // Direct favorite artist match (+350)
        for (const fa of favArtists) {
          if (tArtist.includes(fa)) {
            score += 350;
            break;
          }
        }

        // Similar cluster artist match (+220)
        for (const sa of similarArtists) {
          if (tArtist.includes(sa)) {
            score += 220;
            break;
          }
        }

        // Genre & Mood matches
        if (favGenres.has(tGenre)) score += 150;
        if (favLanguages.has(tLang)) score += 100;
        if (t.mood && favMoods.has(t.mood)) score += 80;

        // Skip-dwell dynamic penalties / boosts
        const primaryA = tArtist.split(/[,&]/)[0].trim();
        if (sessionArtistAffinity[primaryA]) {
          score += sessionArtistAffinity[primaryA];
        }
        if (t.mood && sessionMoodAffinity[t.mood]) {
          score += sessionMoodAffinity[t.mood];
        }
        if (sessionSuppressedArtists.has(primaryA)) {
          score -= 400;
        }

        // Session fatigue penalty
        if (sessionPlayedSet.has(normKey)) {
          score -= 300;
        }

        score += Math.random() * 60;

        return { track: t, score };
      })
      .filter(item => item.score > 120)
      .sort((a, b) => b.score - a.score);

    const candidates = scored.slice(0, 20).map(s => s.track);
    const finalTracks = shuffleWithTemperature(candidates, 0.35).slice(0, 10);

    const primaryTrack = favoriteTracks[favoriteTracks.length - 1] || favoriteTracks[0];
    const primaryArtist = primaryTrack?.artist?.split(/[,&]/)[0]?.trim() || 'your favorite artists';
    const primaryTitle = primaryTrack?.title || 'your liked tracks';

    return {
      tag: 'BASED ON YOUR LIKES',
      reason: `Inspired by "${primaryTitle}" by ${primaryArtist} & similar gems`,
      tracks: finalTracks.length > 0 ? finalTracks : validTracks.slice(0, 10)
    };
  },

  /**
   * Generates dynamic personalized recommendations based on listening frequency, recents, and favorites.
   */
  async getPersonalizedRecommendations(recentTracks = [], favoriteIds = [], allCatalogTracks = [], playStats = null) {
    const validTracks = getUnifiedCatalog(allCatalogTracks);
    const stats = playStats || (typeof storageService !== 'undefined' ? storageService.getPlayStats() : {});

    const frequentlyPlayed = (typeof storageService !== 'undefined')
      ? storageService.getFrequentlyPlayed(10, validTracks)
      : [];

    const basedOnLikes = this.getRecommendationsBasedOnLikes(favoriteIds, validTracks);
    const artistAffinity = {};

    Object.values(stats || {}).forEach(item => {
      if (item && item.artist && item.playCount > 0) {
        const primary = item.artist.split(/[,&]/)[0].trim().toLowerCase();
        artistAffinity[primary] = (artistAffinity[primary] || 0) + (item.playCount * 4);
      }
    });

    const favoriteTracks = validTracks.filter(t => (favoriteIds || []).includes(t.id));
    favoriteTracks.forEach(t => {
      if (t.artist) {
        const primary = t.artist.split(/[,&]/)[0].trim().toLowerCase();
        artistAffinity[primary] = (artistAffinity[primary] || 0) + 6;
      }
    });

    (recentTracks || []).filter(t => !isDummyTrack(t)).forEach(t => {
      if (t.artist) {
        const primary = t.artist.split(/[,&]/)[0].trim().toLowerCase();
        artistAffinity[primary] = (artistAffinity[primary] || 0) + 2;
      }
    });

    const topArtists = Object.entries(artistAffinity)
      .sort((a, b) => b[1] - a[1])
      .map(entry => entry[0]);

    if (topArtists.length > 0) {
      const primaryArtist = topArtists[0];
      const similarPool = getSimilarArtists(primaryArtist);

      const scored = validTracks.map(t => {
        let score = 0;
        const tArtist = (t.artist || '').toLowerCase();
        const normKey = normalizeTrackName(t.title);

        if (tArtist.includes(primaryArtist)) score += 300;
        for (const sa of similarPool) {
          if (tArtist.includes(sa)) {
            score += 180;
            break;
          }
        }
        if (sessionPlayedSet.has(normKey)) score -= 300;
        score += Math.random() * 50;

        return { track: t, score };
      })
      .filter(item => item.score > 80)
      .sort((a, b) => b.score - a.score);

      const tracks = shuffleWithTemperature(scored.slice(0, 16).map(s => s.track), 0.3).slice(0, 10);
      if (tracks.length > 0) {
        const displayArtist = primaryArtist.charAt(0).toUpperCase() + primaryArtist.slice(1);
        return {
          tag: `BASED ON YOUR LOVE FOR ${displayArtist.toUpperCase()}`,
          reason: `Because you frequently listen to ${displayArtist} & similar artists`,
          tracks,
          frequentlyPlayed,
          basedOnLikes
        };
      }
    }

    const diversePicks = shuffleWithTemperature(validTracks, 0.75).slice(0, 12);
    return {
      tag: 'DISCOVER WEEKLY • FOR YOU',
      reason: 'Fresh Bollywood, Punjabi & Global hits tailored for your taste',
      tracks: diversePicks,
      frequentlyPlayed,
      basedOnLikes
    };
  },

  /**
   * Generates highly relevant, non-repetitive upcoming tracks for Up Next Queue and Autoplay.
   * Matches language, genre, and artist network, penalizes songs already played in the current session,
   * performs Acoustic Vibe & Energy transition continuity, and respects skip-dwell feedback.
   */
  getRelevantUpcomingTracks(currentTrack, allTracks = [], count = 15) {
    if (!currentTrack) return [];

    const normTitle = (str) => normalizeTrackName(str);
    const currentCleanTitle = normTitle(currentTrack.title);
    const currentArtistClean = normalizeArtistName(currentTrack.artist);

    let { genre: currentGenre, language: currentLang } = inferGenreAndLanguage(currentTrack);
    if (currentTrack.genre) currentGenre = currentTrack.genre.toLowerCase().trim();
    if (currentTrack.language) currentLang = currentTrack.language.toLowerCase().trim();

    // Extract current track acoustic features (BPM, Mood, Energy, Valence)
    const currentAcoustic = inferAcousticFeatures(currentTrack);

    const currentArtists = (currentTrack.artist || '')
      .toLowerCase()
      .split(/[,&]/)
      .map(a => a.trim())
      .filter(Boolean);

    // Get similar artists cluster
    const clusterArtists = new Set();
    currentArtists.forEach(ca => {
      getSimilarArtists(ca).forEach(sa => clusterArtists.add(sa));
    });

    const catalog = getUnifiedCatalog(allTracks);

    // Exclude currently playing song (by id, title, or title+artist key)
    const currentKey = `${currentCleanTitle}-${currentArtistClean}`;
    const available = catalog.filter(t => {
      if (!t || isDummyTrack(t)) return false;
      if (t.id && currentTrack.id && t.id === currentTrack.id) return false;
      if (normTitle(t.title) === currentCleanTitle) return false;
      const key = `${normTitle(t.title)}-${normalizeArtistName(t.artist)}`;
      return key !== currentKey;
    });

    if (available.length === 0) return [];

    // Filter out tracks already played in this session so songs NEVER repeat in queue
    const unplayed = available.filter(t => {
      const nTitle = normTitle(t.title);
      return !sessionPlayedSet.has(nTitle);
    });
    const poolToScore = unplayed.length >= count ? unplayed : available;

    // Intelligent Multi-Vector Scoring
    const scored = poolToScore.map(t => {
      let score = 0;
      const tGenre = (t.genre || '').toLowerCase().trim();
      const tLang = (t.language || '').toLowerCase().trim();
      const tArtistLower = (t.artist || '').toLowerCase();
      const tPrimaryArtist = tArtistLower.split(/[,&]/)[0].trim();
      const candidateAcoustic = inferAcousticFeatures(t);

      // 1. Language affinity
      if (currentLang && tLang) {
        if (currentLang === tLang) {
          score += 350;
        } else if (
          (currentLang === 'hindi' && tLang === 'punjabi') ||
          (currentLang === 'punjabi' && tLang === 'hindi')
        ) {
          score += 260; // Natural Desi crossover
        } else {
          score -= 150;
        }
      }

      // 2. Same artist boost (+250)
      if (currentArtists.some(ca => ca.length >= 3 && tArtistLower.includes(ca))) {
        score += 250;
      }

      // 3. Similar artist cluster boost (+180)
      for (const sa of clusterArtists) {
        if (tArtistLower.includes(sa)) {
          score += 180;
          break;
        }
      }

      // 4. Genre matching
      if (tGenre && currentGenre) {
        if (tGenre === currentGenre) {
          score += 200;
        } else if (
          (currentGenre === 'bollywood' && (tGenre === 'romantic' || tGenre === 'acoustic')) ||
          (currentGenre === 'hiphop' && (tGenre === 'rap' || tGenre === 'pop')) ||
          (currentGenre === 'pop' && (tGenre === 'rnb' || tGenre === 'electronic')) ||
          (currentGenre === 'rnb' && tGenre === 'pop')
        ) {
          score += 120;
        }
      }

      // 5. ACOUSTIC VIBE & ENERGY TRANSITION CONTINUITY (Spotify Echo Nest style)
      const acousticScore = getAcousticCompatibilityScore(currentAcoustic, candidateAcoustic);
      score += acousticScore;

      // 6. Real-Time Skip-Dwell Penalties / Boosts
      if (sessionArtistAffinity[tPrimaryArtist]) {
        score += sessionArtistAffinity[tPrimaryArtist];
      }
      if (candidateAcoustic.mood && sessionMoodAffinity[candidateAcoustic.mood]) {
        score += sessionMoodAffinity[candidateAcoustic.mood];
      }
      if (sessionSuppressedArtists.has(tPrimaryArtist)) {
        score -= 500; // Suppress skipped artist
      }

      // 7. Dynamic freshness jitter (0 to 60)
      score += Math.random() * 60;

      return { track: t, score };
    });

    scored.sort((a, b) => b.score - a.score);

    const candidatePool = scored.slice(0, count + 8).map(s => s.track);
    const finalQueue = shuffleWithTemperature(candidatePool, 0.25).slice(0, count);

    return finalQueue;
  },

  /**
   * Generates seamless infinite autoplay continuity when a playlist ends.
   */
  getPlaylistContinuityRecommendations(playlistTracks = [], currentTrack = null, allTracks = [], count = 15) {
    const catalog = getUnifiedCatalog(allTracks);
    const playlistIds = new Set((playlistTracks || []).map(t => t.id || `${normalizeTrackName(t.title)}-${normalizeArtistName(t.artist)}`));

    const genreCounts = {};
    const langCounts = {};
    const moodCounts = {};
    (playlistTracks || []).forEach(t => {
      const g = (t.genre || '').toLowerCase().trim();
      const l = (t.language || '').toLowerCase().trim();
      const ac = inferAcousticFeatures(t);
      if (g) genreCounts[g] = (genreCounts[g] || 0) + 1;
      if (l) langCounts[l] = (langCounts[l] || 0) + 1;
      if (ac.mood) moodCounts[ac.mood] = (moodCounts[ac.mood] || 0) + 1;
    });

    const dominantGenre = Object.entries(genreCounts).sort((a, b) => b[1] - a[1])[0]?.[0] || 'bollywood';
    const dominantLang = Object.entries(langCounts).sort((a, b) => b[1] - a[1])[0]?.[0] || 'hindi';
    const dominantMood = Object.entries(moodCounts).sort((a, b) => b[1] - a[1])[0]?.[0] || 'romantic';

    const pool = catalog.filter(t => {
      const key = t.id || `${normalizeTrackName(t.title)}-${normalizeArtistName(t.artist)}`;
      return !playlistIds.has(key);
    });

    const scored = pool.map(t => {
      let score = 0;
      const tGenre = (t.genre || '').toLowerCase().trim();
      const tLang = (t.language || '').toLowerCase().trim();
      const normKey = normalizeTrackName(t.title);
      const ac = inferAcousticFeatures(t);

      if (tLang === dominantLang) score += 300;
      if (tGenre === dominantGenre) score += 250;
      if (ac.mood === dominantMood) score += 200;
      if (sessionPlayedSet.has(normKey)) score -= 350;
      score += Math.random() * 80;

      return { track: t, score };
    })
    .sort((a, b) => b.score - a.score);

    const candidates = scored.slice(0, count + 6).map(s => s.track);
    return shuffleWithTemperature(candidates, 0.3).slice(0, count);
  },

  /**
   * Asynchronously enriches the queue with fresh, genre-accurate tracks from online catalog
   * and triggers background endless radio ingestion so the queue never ends.
   */
  async fetchSmartUpcomingTracks(currentTrack, allTracks = [], count = 15) {
    if (!currentTrack) return [];

    const immediate = this.getRelevantUpcomingTracks(currentTrack, allTracks, count);
    const primaryArtist = (currentTrack.artist || '').split(/[,&]/)[0].trim();
    let { genre: currentGenre, language: currentLang } = inferGenreAndLanguage(currentTrack);
    if (currentTrack.genre) currentGenre = currentTrack.genre.toLowerCase().trim();
    if (currentTrack.language) currentLang = currentTrack.language.toLowerCase().trim();

    if (!primaryArtist) return immediate;

    try {
      // 1. Ingest endless fresh radio tracks online from streaming search API
      this.ingestArtistRadioOnline(primaryArtist, currentLang, currentTrack.mood).catch(() => {});

      // 2. Fetch fresh tracks for immediate queue enrichment
      const fetchPromise = searchGlobalMusic(primaryArtist, currentLang);
      const timeoutPromise = new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 3000));
      const onlineMatches = await Promise.race([fetchPromise, timeoutPromise]);

      if (onlineMatches && onlineMatches.length > 0) {
        const normTitle = (str) => normalizeTrackName(str);
        const seen = new Set([normTitle(currentTrack.title)]);
        const combined = [];

        immediate.forEach(t => {
          const n = normTitle(t.title);
          if (!seen.has(n)) {
            seen.add(n);
            combined.push(t);
          }
        });

        onlineMatches.forEach(t => {
          if (isDummyTrack(t)) return;
          const n = normTitle(t.title);
          if (seen.has(n)) return;

          const tLang = (t.language || '').toLowerCase();
          if (currentLang === 'english' && tLang !== 'english') return;
          if ((currentLang === 'hindi' || currentLang === 'punjabi') && tLang === 'english') return;

          seen.add(n);
          combined.push(t);
        });

        if (combined.length > 0) {
          return combined.slice(0, count);
        }
      }
    } catch (e) {
      console.warn('Smart upcoming dynamic fetch error:', e);
    }

    return immediate;
  },

  /**
   * Generates authentic Hindi & Global Daily Mixes
   */
  getDailyMixes(recentTracks = [], allTracks = []) {
    const valid = getUnifiedCatalog(allTracks);
    const mixes = [];

    // Daily Mix 1: Bollywood Romance & Soul
    const bollywood = valid.filter(t => {
      const g = (t.genre || '').toLowerCase();
      const l = (t.language || '').toLowerCase();
      const a = (t.artist || '').toLowerCase();
      return (l === 'hindi') && (
        g === 'bollywood' || g === 'romantic' || g === 'acoustic' ||
        /arijit|pritam|mithoon|jubin|sachin|jigar|anirudh|tanishk|jasleen|shreya|sonu|atif|ar rahman|kk|mohit|sachet/i.test(a)
      );
    });
    if (bollywood.length > 0) {
      mixes.push({
        id: 'mix-bollywood-romance',
        name: 'Daily Mix 1',
        subtitle: 'Sonu Nigam, Arijit Singh, KK, Atif Aslam & Bollywood Soul',
        cover: 'https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=600&auto=format&fit=crop&q=80',
        tracks: shuffleWithTemperature(bollywood, 0.4)
      });
    }

    // Daily Mix 2: Desi Hip-Hop & Hustle
    const hiphop = valid.filter(t => {
      const g = (t.genre || '').toLowerCase();
      const a = (t.artist || '').toLowerCase();
      const alb = (t.album || '').toLowerCase();
      const tit = (t.title || '').toLowerCase();
      return g === 'hiphop' || /hustle|sujal|paradox|aujla|karan|divine|raftaar|krsna|stan|seedhe|mc stan|shubh|diljit|ap dhillon|hanumankind/i.test(a + ' ' + alb + ' ' + tit);
    });
    if (hiphop.length > 0) {
      mixes.push({
        id: 'mix-desi-hiphop',
        name: 'Daily Mix 2',
        subtitle: 'Karan Aujla, Shubh, Diljit, AP Dhillon & Desi Hip-Hop',
        cover: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80',
        tracks: shuffleWithTemperature(hiphop, 0.4)
      });
    }

    // Daily Mix 3: Today's Global Top Hits
    const globalHits = valid.filter(t => {
      const l = (t.language || '').toLowerCase();
      const g = (t.genre || '').toLowerCase();
      return l === 'english' || g === 'pop' || g === 'rnb';
    });
    if (globalHits.length > 0) {
      mixes.push({
        id: 'mix-global-hits',
        name: 'Daily Mix 3',
        subtitle: 'The Weeknd, Sabrina Carpenter, Billie Eilish & Tai Verdes',
        cover: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80',
        tracks: shuffleWithTemperature(globalHits, 0.4)
      });
    }

    return mixes;
  },

  /**
   * Generates a 100% on-device personalized mix based on the user's active profile
   */
  getProfilePersonalizedMix(profile, allCatalogTracks = [], favoriteIds = []) {
    if (!profile) return null;
    const valid = getUnifiedCatalog(allCatalogTracks);
    const favSet = new Set(favoriteIds || []);

    const userLangs = (profile.preferredLanguages || []).map(l => l.toLowerCase().trim());
    const userArtists = (profile.favoriteArtists || []).map(a => a.toLowerCase().trim());

    if (userLangs.length === 0 && userArtists.length === 0) {
      return null;
    }

    const scored = valid.map(track => {
      let score = 0;
      const tArtist = (track.artist || '').toLowerCase();
      const tLang = (track.language || '').toLowerCase();
      const tGenre = (track.genre || '').toLowerCase();

      if (userArtists.some(ua => tArtist.includes(ua) || ua.includes(tArtist))) {
        score += 350;
      }
      if (userLangs.some(ul => tLang.includes(ul) || tGenre.includes(ul) || ul.includes(tLang))) {
        score += 200;
      }
      if (favSet.has(track.id)) {
        score += 80;
      }

      score += Math.random() * 40;

      return { track, score };
    })
    .filter(item => item.score > 120)
    .sort((a, b) => b.score - a.score);

    const candidates = scored.slice(0, 16).map(s => s.track);
    const tracks = shuffleWithTemperature(candidates, 0.3).slice(0, 10);
    if (tracks.length === 0) return null;

    const profileName = profile.name && profile.name !== 'Music Lover' ? profile.name : 'You';
    const topArtistsStr = profile.favoriteArtists?.slice(0, 2).join(' & ') || 'your tastes';

    return {
      tag: `MADE FOR ${profileName.toUpperCase()}`,
      reason: `Tailored to your favorite artists (${topArtistsStr}) & preferred languages`,
      tracks
    };
  }
};
