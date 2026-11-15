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

  const { q } = req.query || {};
  if (!q) {
    return res.status(400).json({ error: 'Missing query parameter q' });
  }

  let searchTerm = q.trim();
  if (/sicklord/i.test(searchTerm)) {
    searchTerm = searchTerm.replace(/sicklord/gi, 'sicklot');
  }

  try {
    const itunesUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(searchTerm)}&entity=song&limit=25`;
    const fetchRes = await fetch(itunesUrl, {
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' },
      signal: AbortSignal.timeout(5000)
    });

    if (fetchRes.ok) {
      const data = await fetchRes.json();
      const songs = (data.results || []).map((r, idx) => ({
        id: r.trackId ? `itunes-${r.trackId}` : `itunes-${idx}`,
        title: r.trackName,
        artist: r.artistName,
        album: r.collectionName || 'Single',
        duration: Math.round((r.trackTimeMillis || 180000) / 1000),
        coverUrl: (r.artworkUrl100 || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80').replace('100x100bb', '600x600bb'),
        themeColor: '#1DB954',
        secondaryColor: '#8b5cf6'
      }));
      return res.status(200).json({ results: songs });
    }
  } catch (err) {
    console.error('Search error in api handler:', err);
  }

  return res.status(200).json({ results: [] });
}
