export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Credentials', 'true');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS,PATCH,DELETE,POST,PUT');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version'
  );

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const { id: playlistId } = req.query || {};
  if (!playlistId) {
    return res.status(400).json({ error: 'Missing id parameter' });
  }

  try {
    const spotifyUrl = `https://open.spotify.com/embed/playlist/${playlistId}`;
    const fetchRes = await fetch(spotifyUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
      },
      signal: AbortSignal.timeout(6000)
    });

    if (!fetchRes.ok) {
      return res.status(fetchRes.status).json({ error: `Spotify returned status ${fetchRes.status}` });
    }

    const html = await fetchRes.text();
    const nextDataMatch = html.match(/<script id="__NEXT_DATA__" type="application\/json">(.*?)<\/script>/);

    if (nextDataMatch) {
      const data = JSON.parse(nextDataMatch[1]);
      const entity = data?.props?.pageProps?.state?.data?.entity;
      const name = entity?.name || 'Spotify Playlist';
      const cover = entity?.visualIdentity?.image?.[0]?.url || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80';
      const trackList = entity?.trackList || [];

      const tracks = trackList.map((t, idx) => ({
        id: `sp-${playlistId}-${idx}-${Date.now()}`,
        title: t.title || 'Untitled Track',
        artist: t.subtitle || 'Unknown Artist',
        album: name,
        duration: t.duration ? Math.round(t.duration / 1000) : 180,
        coverUrl: cover,
        themeColor: '#1DB954',
        secondaryColor: '#3b82f6'
      }));

      return res.status(200).json({
        id: `imported-${playlistId}`,
        name,
        description: `Imported from Spotify • ${tracks.length} tracks`,
        cover,
        isCustom: true,
        isImported: true,
        trackCount: tracks.length,
        tracks
      });
    }
  } catch (err) {
    console.error('Error fetching Spotify playlist:', err);
    return res.status(500).json({ error: err.message });
  }

  return res.status(404).json({ error: 'Playlist metadata could not be parsed' });
}
