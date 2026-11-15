import { handle, bad } from '../_lib/http.js';

// GET /api/spotify/playlist?id=<playlist id>
// Reads a public Spotify playlist's track list from its embed page. Songs are matched
// to exact recordings later, when played (see /api/music/resolve).
export default handle(async ({ id }) => {
  const playlistId = String(id || '').match(/[A-Za-z0-9]{22}/)?.[0];
  if (!playlistId) return bad('That does not look like a Spotify playlist link.');

  const res = await fetch(`https://open.spotify.com/embed/playlist/${playlistId}`, {
    headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36' },
    signal: AbortSignal.timeout(8000),
  });
  if (res.status === 404) return { status: 404, error: 'Spotify could not find this playlist. Check that it is public.' };
  if (!res.ok) throw new Error(`Spotify returned ${res.status}`);

  const html = await res.text();
  const json = html.match(/<script id="__NEXT_DATA__" type="application\/json">(.*?)<\/script>/s)?.[1];
  const entity = json ? JSON.parse(json)?.props?.pageProps?.state?.data?.entity : null;
  if (!entity?.trackList?.length) return { status: 422, error: 'This playlist has no readable songs. Make sure it is public.' };

  return {
    name: entity.name || 'Spotify playlist',
    coverUrl: entity.visualIdentity?.image?.[0]?.url || '',
    tracks: entity.trackList.map((t, i) => ({
      id: `sp-${(t.uri || '').split(':').pop() || `${playlistId}-${i}`}`,
      title: t.title || 'Untitled',
      artist: t.subtitle || '',
      duration: t.duration ? Math.round(t.duration / 1000) : 0,
    })),
  };
}, { cacheSeconds: 600 });
