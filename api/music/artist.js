import { handle, bad } from '../_lib/http.js';
import { artist } from '../_lib/ytmusic.js';

// GET /api/music/artist?id=UC...  or  ?name=Arijit%20Singh
export default handle(async ({ id, name }) => {
  if (!id && !name) return bad('Missing artist id or name.');
  const result = await artist({ id: id ? String(id) : '', name: name ? String(name) : '' });
  return result || { status: 404, error: 'Artist not found.' };
}, { cacheSeconds: 43200 });
