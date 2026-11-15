import { handle, bad } from '../_lib/http.js';
import { resolve } from '../_lib/ytmusic.js';

// Legacy endpoint kept for older installed app builds. Same strict matching as /api/music/resolve.
export default handle(async ({ title, q, artist, duration, exclude }) => {
  const name = String(title || q || '').trim();
  if (!name) return bad('Missing search parameter');
  const { track } = await resolve({ title: name, artist: String(artist || ''), duration: Number(duration) || 0, exclude: exclude ? [String(exclude)] : [] });
  return track ? { videoId: track.youtubeId, title: track.title, channel: track.artist, durationSec: track.duration } : { videoId: null };
}, { cacheSeconds: 86400 });
