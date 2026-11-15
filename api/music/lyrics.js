import { handle, bad } from '../_lib/http.js';
import { lyrics } from '../_lib/ytmusic.js';

// GET /api/music/lyrics?videoId=  — plain (unsynced) lyrics, used when LRCLIB has none.
export default handle(async ({ videoId }) => {
  if (!/^[\w-]{11}$/.test(String(videoId || ''))) return bad('Missing or invalid videoId.');
  return (await lyrics(videoId)) || { plain: null };
}, { cacheSeconds: 604800 });
