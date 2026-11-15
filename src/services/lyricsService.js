/**
 * Lyrics Service - High-Precision Synchronized LRC Parser with Word-by-Word Timing,
 * Strict Candidate Matching, In-Memory Caching, and Error Resilience.
 */
import { normalizeTrackName, normalizeArtistName } from '../data/curatedTracks.js';

// In-memory cache for ultra-fast instant recall without network latency
const lyricsCache = new Map();

/**
 * Parses standard .lrc format text with millisecond precision
 * and generates word-by-word proportional spans for fluid karaoke wipes.
 */
export function parseLrc(lrcContent) {
  if (!lrcContent || typeof lrcContent !== 'string') return [];

  const rawLines = lrcContent.split('\n');
  const parsedLines = [];
  const timeRegex = /\[(\d{2}):(\d{2})\.?(\d{2,3})?\](.*)/;

  // Extract standard [offset: +/-ms] tag if present in the LRC header
  let globalOffsetSeconds = 0;
  for (const line of rawLines) {
    const trimmed = line.trim();
    const offsetMatch = trimmed.match(/^\[offset:\s*([+-]?\d+)\s*\]/i);
    if (offsetMatch) {
      globalOffsetSeconds = parseInt(offsetMatch[1], 10) / 1000;
      break;
    }
  }

  for (const line of rawLines) {
    const match = timeRegex.exec(line.trim());
    if (match) {
      const minutes = parseInt(match[1], 10);
      const seconds = parseInt(match[2], 10);
      let ms = 0;
      if (match[3]) {
        ms = match[3].length === 2 ? parseInt(match[3], 10) * 10 : parseInt(match[3], 10);
      }
      const totalSeconds = Math.max(0, minutes * 60 + seconds + ms / 1000 + globalOffsetSeconds);
      const text = match[4].trim();

      if (text) {
        parsedLines.push({
          time: totalSeconds,
          text: text
        });
      }
    }
  }

  // Sort chronologically
  parsedLines.sort((a, b) => a.time - b.time);

  // Compute word-level timings for smooth karaoke highlight
  return parsedLines.map((line, idx) => {
    const nextLineTime = parsedLines[idx + 1] ? parsedLines[idx + 1].time : line.time + 4.2;
    const lineDuration = Math.max(1.0, nextLineTime - line.time);
    const words = line.text.split(/\s+/).filter(w => w.length > 0);

    const totalChars = words.reduce((acc, w) => acc + w.length, 0) || words.length;
    let accumulatedTime = line.time;

    const timedWords = words.map((word) => {
      const wordSpan = Math.max(0.18, (word.length / totalChars) * (lineDuration * 0.9));
      const wordObj = {
        word,
        start: accumulatedTime,
        end: accumulatedTime + wordSpan
      };
      accumulatedTime += wordSpan;
      return wordObj;
    });

    return {
      ...line,
      duration: lineDuration,
      words: timedWords
    };
  });
}

/**
 * Fetch synchronized lyrics from LRCLIB with enhanced multi-strategy candidate search,
 * strict validation, and massive prioritization for true synchronized LRC.
 */
export async function fetchLiveLyrics(artist, trackName, duration) {
  if (!trackName) return null;

  const rawArtist = (artist || '').trim();
  const rawTrack = (trackName || '').trim();
  const cacheKey = `${rawArtist}___${rawTrack}`.toLowerCase();

  if (lyricsCache.has(cacheKey)) {
    const cached = lyricsCache.get(cacheKey);
    if (cached && cached.synced) return cached;
  }

  try {
    const cleanTrack = rawTrack
      .replace(/\(.*?\)/g, '')
      .replace(/\[.*?\]/g, '')
      .replace(/[-–—|].*$/, '')
      .replace(/[?!,']/g, '')
      .trim();

    const cleanLeadArtist = rawArtist
      .replace(/\(feat\..*?\)/gi, '')
      .replace(/\[feat\..*?\]/gi, '')
      .replace(/feat\..*$/i, '')
      .replace(/ft\..*$/i, '')
      .replace(/,\s*.*$/, '')
      .replace(/&\s*.*$/, '')
      .replace(/[?!,']/g, '')
      .trim();

    const normTrack = normalizeTrackName(rawTrack);
    const normCleanTrack = normalizeTrackName(cleanTrack);
    const normLeadArtist = normalizeArtistName(cleanLeadArtist);
    const normRawArtist = normalizeArtistName(rawArtist);

    // Collect candidates from multiple targeted endpoints
    const queries = [];

    // 1. Direct GET endpoints
    if (cleanTrack && cleanLeadArtist) {
      queries.push(`https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanTrack)}&artist_name=${encodeURIComponent(cleanLeadArtist)}`);
    }
    if (cleanTrack && rawArtist && rawArtist.toLowerCase() !== cleanLeadArtist.toLowerCase()) {
      queries.push(`https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanTrack)}&artist_name=${encodeURIComponent(rawArtist)}`);
    }

    // 2. Search queries (vital: track_name alone finds collaborations like "Karan Aujla & Signature by SB")
    if (cleanTrack) {
      queries.push(`https://lrclib.net/api/search?track_name=${encodeURIComponent(cleanTrack)}`);
    }
    if (cleanTrack && cleanLeadArtist) {
      queries.push(`https://lrclib.net/api/search?q=${encodeURIComponent(`${cleanTrack} ${cleanLeadArtist}`)}`);
      queries.push(`https://lrclib.net/api/search?track_name=${encodeURIComponent(cleanTrack)}&artist_name=${encodeURIComponent(cleanLeadArtist)}`);
    }

    let candidates = [];
    const seenIds = new Set();

    for (const url of queries) {
      try {
        const res = await fetch(url);
        if (res.ok) {
          const data = await res.json();
          if (Array.isArray(data)) {
            data.forEach(item => {
              if (item && item.id && !seenIds.has(item.id)) {
                seenIds.add(item.id);
                candidates.push(item);
              }
            });
          } else if (data && data.id && !seenIds.has(data.id)) {
            seenIds.add(data.id);
            candidates.push(data);
          }
        }
      } catch (_) {}

      // If we already found a verified synced candidate for this song, we can proceed to score
      const hasSyncedMatch = candidates.some(c => c.syncedLyrics && (normalizeTrackName(c.trackName) === normCleanTrack || normalizeTrackName(c.trackName) === normTrack));
      if (hasSyncedMatch) break;
    }

    if (candidates.length > 0) {
      const scored = candidates
        .map(item => {
          if (!item || (!item.syncedLyrics && !item.plainLyrics)) return null;

          const itemTrackNorm = normalizeTrackName(item.trackName);
          const itemArtistNorm = normalizeArtistName(item.artistName);

          const isExactTrack = itemTrackNorm === normTrack || itemTrackNorm === normCleanTrack;
          const isCloseTrack =
            (itemTrackNorm.includes(normCleanTrack) || normCleanTrack.includes(itemTrackNorm)) &&
            Math.abs(itemTrackNorm.length - normCleanTrack.length) <= 6 &&
            normCleanTrack.length >= 3;

          if (!isExactTrack && !isCloseTrack) return null;

          let score = isExactTrack ? 100 : 60;

          // Artist matching: check exact, lead artist, or partial word match
          const artistExact = normLeadArtist && (itemArtistNorm === normLeadArtist || itemArtistNorm === normRawArtist);
          const artistPartial = normLeadArtist && (itemArtistNorm.includes(normLeadArtist) || normLeadArtist.includes(itemArtistNorm));

          if (artistExact) {
            score += 60;
          } else if (artistPartial) {
            score += 45;
          } else if (!isExactTrack) {
            return null; // Both track and artist mismatched = reject
          }

          // SUPER BOOST: Prioritize syncedLyrics above everything else!
          if (item.syncedLyrics) {
            score += 500;
          }

          // Duration match
          if (duration && item.duration) {
            const diff = Math.abs(item.duration - duration);
            if (diff <= 8) score += 30;
            else if (diff <= 25) score += 10;
            else if (diff > 45 && !item.syncedLyrics) return null;
          }

          return { item, score };
        })
        .filter(Boolean)
        .sort((a, b) => b.score - a.score);

      if (scored.length > 0 && scored[0].score >= 70) {
        const best = scored[0].item;
        if (best.syncedLyrics) {
          const result = {
            synced: true,
            lines: parseLrc(best.syncedLyrics),
            source: 'lrclib_synced'
          };
          lyricsCache.set(cacheKey, result);
          return result;
        }
        if (best.plainLyrics) {
          const plainLines = best.plainLyrics
            .split('\n')
            .map(l => l.trim())
            .filter(l => l.length > 0 && !l.startsWith('['));

          const result = {
            synced: false,
            plainLines,
            source: 'lrclib_plain'
          };
          return result;
        }
      }
    }
  } catch (err) {
    console.warn('Live lyrics fetch notice:', err);
  }

  return null;
}
