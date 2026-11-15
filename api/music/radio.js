import { handle, bad } from '../_lib/http.js';
import { radio } from '../_lib/ytmusic.js';

// GET /api/music/radio?videoId=  — up to ~50 related songs for a recording.
export default handle(async ({ videoId }) => {
  if (!/^[\w-]{11}$/.test(String(videoId || ''))) return bad('Missing or invalid videoId.');
  return { tracks: await radio(videoId) };
}, { cacheSeconds: 21600 });
