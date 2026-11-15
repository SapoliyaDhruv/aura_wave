import { handle, bad } from '../_lib/http.js';
import { search } from '../_lib/ytmusic.js';

// GET /api/music/search?q=  — songs, artists and official videos. Works with lyrics too.
export default handle(async ({ q }) => {
  const query = String(q || '').trim().slice(0, 200);
  if (!query) return bad('Type a song, artist or a line from the lyrics.');
  return search(query);
}, { cacheSeconds: 3600 });
