import { handle, bad } from '../_lib/http.js';
import { resolve } from '../_lib/ytmusic.js';

// GET /api/music/resolve?title=&artist=&duration=&exclude=id1,id2
// Finds the exact recording for a song described by metadata, or returns track: null.
export default handle(async ({ title, artist, duration, exclude }) => {
  if (!title) return bad('Missing song title.');
  return resolve({
    title: String(title).slice(0, 200),
    artist: String(artist || '').slice(0, 200),
    duration: Number(duration) || 0,
    exclude: String(exclude || '').split(',').filter(Boolean),
  });
}, { cacheSeconds: 86400 });
