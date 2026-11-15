/**
 * LRCLIB Publishing Service
 * Handles immediate local persistence and background submission to the global LRCLIB database.
 */
import { lyricsAutoSync, buildWordLevelTimings } from './lyricsAutoSync.js';
import { getApiBaseUrl } from '../utils/apiBase.js';

/**
 * Formats seconds into standard LRC timestamp [mm:ss.xx]
 */
export function formatSecondsToLrcTag(seconds) {
  if (typeof seconds !== 'number' || isNaN(seconds) || seconds < 0) {
    return '[00:00.00]';
  }
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  const ms = Math.floor((seconds % 1) * 100);
  return `[${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}.${String(ms).padStart(2, '0')}]`;
}

/**
 * Converts array of { time, text } into standard .lrc file text
 */
export function buildLrcString(lines) {
  if (!Array.isArray(lines) || lines.length === 0) return '';
  return lines
    .map(line => `${formatSecondsToLrcTag(line.time)} ${line.text || ''}`.trim())
    .join('\n');
}

/**
 * Submits synchronized lyrics:
 * 1. Instantly saves to local vault so it takes effect in AuraWave with zero latency.
 * 2. Asynchronously publishes to global LRCLIB so all users worldwide get synced lyrics.
 */
export async function submitLyricsToLRCLIB({ track, lines, lrcText }) {
  if (!track || !track.title) {
    throw new Error('Track metadata is required');
  }

  // 1. Prepare structured synced lines
  let syncedLines = lines;
  let finalLrc = lrcText;

  if (Array.isArray(syncedLines) && syncedLines.length > 0) {
    syncedLines = buildWordLevelTimings(syncedLines);
    if (!finalLrc) {
      finalLrc = buildLrcString(syncedLines);
    }
  }

  // 2. Immediate local save into AuraWave Vault (Zero Latency)
  if (Array.isArray(syncedLines) && syncedLines.length > 0) {
    lyricsAutoSync.saveToVault(track, syncedLines, false);
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('aurawave_lyrics_synced', {
        detail: {
          trackId: track.id,
          trackTitle: track.title,
          lines: syncedLines,
          isAuraModel: false
        }
      }));
    }
  }

  // 3. Background publishing to global LRCLIB database
  const plain = syncedLines && Array.isArray(syncedLines)
    ? syncedLines.map(l => l.text).join('\n')
    : (finalLrc || '').replace(/\[\d{2}:\d{2}\.?\d{2,3}?\]\s*/g, '');

  const payload = {
    trackName: track.title,
    artistName: track.artist || 'Unknown Artist',
    albumName: track.album || track.title,
    duration: Math.round(track.duration || 180),
    plainLyrics: plain,
    syncedLyrics: finalLrc
  };

  try {
    const apiBase = getApiBaseUrl();
    const res = await fetch(`${apiBase}/api/music/publish-lyrics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (res.ok) {
      const data = await res.json();
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('aurawave_lyrics_published', {
          detail: {
            trackId: track.id,
            trackTitle: track.title,
            success: true,
            data
          }
        }));
      }
      return { success: true, globalPublished: true, data };
    } else {
      const errData = await res.json().catch(() => ({}));
      console.warn('LRCLIB publish API notice:', errData);
      return {
        success: true,
        globalPublished: false,
        notice: errData.error || 'Saved locally in high-precision vault.'
      };
    }
  } catch (err) {
    console.warn('Background LRCLIB publish network notice:', err);
    // Even if global LRCLIB network is slow or offline, it is 100% saved in the local vault!
    return {
      success: true,
      globalPublished: false,
      notice: 'Saved locally in high-precision vault.'
    };
  }
}
