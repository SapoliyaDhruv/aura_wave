import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const ytSearchCache = new Map();

function spotifyAndAudioPlugin() {
  return {
    name: 'spotify-audio-api',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        try {
          const url = new URL(req.url, 'http://localhost:5173');

          // Global Music Search API
          if (url.pathname === '/api/music/search') {
            const q = url.searchParams.get('q');
            if (!q) {
              res.statusCode = 400;
              res.end(JSON.stringify({ error: 'Missing query parameter q' }));
              return;
            }

            try {
              const itunesUrl = `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&entity=song&limit=25`;
              const fetchRes = await fetch(itunesUrl, {
                headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' },
                signal: AbortSignal.timeout(4000)
              });

              if (fetchRes.ok) {
                const data = await fetchRes.json();
                const songs = (data.results || []).map((r, idx) => ({
                  id: `itunes-${r.trackId || idx}-${Date.now()}`,
                  title: r.trackName,
                  artist: r.artistName,
                  album: r.collectionName || 'Single',
                  duration: Math.round((r.trackTimeMillis || 180000) / 1000),
                  coverUrl: (r.artworkUrl100 || 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80').replace('100x100bb', '600x600bb'),
                  themeColor: '#1DB954',
                  secondaryColor: '#8b5cf6'
                }));
                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify({ results: songs }));
                return;
              }
            } catch (err) {
              console.error('Search error in middleware:', err);
            }

            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({ results: [] }));
            return;
          }

          // Spotify Playlist Resolver - Scrapes real playlist metadata and all tracks
          if (url.pathname === '/api/spotify/playlist') {
            const playlistId = url.searchParams.get('id');
            if (!playlistId) {
              res.statusCode = 400;
              res.end(JSON.stringify({ error: 'Missing id parameter' }));
              return;
            }

            try {
              const spotifyUrl = `https://open.spotify.com/embed/playlist/${playlistId}`;
              const fetchRes = await fetch(spotifyUrl, {
                headers: {
                  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
                },
                signal: AbortSignal.timeout(5000)
              });

              if (!fetchRes.ok) {
                res.statusCode = fetchRes.status;
                res.end(JSON.stringify({ error: `Spotify returned status ${fetchRes.status}` }));
                return;
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

                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify({
                  id: `imported-${playlistId}`,
                  name,
                  description: `Imported from Spotify • ${tracks.length} tracks`,
                  cover,
                  tracks
                }));
                return;
              }
            } catch (err) {
              console.error('Error fetching Spotify playlist:', err);
              res.statusCode = 500;
              res.end(JSON.stringify({ error: err.message }));
              return;
            }
          }

          // YouTube Search Resolver - Resolves song to clean studio album audio (dialogue-free, Spotify-equivalent)
          if (url.pathname === '/api/youtube/search') {
            const q = url.searchParams.get('q') || '';
            const titleParam = url.searchParams.get('title') || '';
            const artistParam = url.searchParams.get('artist') || '';
            const excludeParam = url.searchParams.get('exclude') || '';

            if (!q && !titleParam) {
              res.statusCode = 400;
              res.end(JSON.stringify({ error: 'Missing search parameter' }));
              return;
            }

            const cacheKey = `${titleParam.toLowerCase().trim()}:::${artistParam.toLowerCase().trim()}:::${q.toLowerCase().trim()}`;
            if (!excludeParam && ytSearchCache.has(cacheKey)) {
              const cached = ytSearchCache.get(cacheKey);
              res.setHeader('Content-Type', 'application/json');
              res.end(JSON.stringify(cached));
              return;
            }

            try {
              // Construct search queries prioritizing official studio audio, and alternative lyric/song queries on retry
              const primaryArtist = (artistParam || '').split(/[,&]/)[0].trim();
              const cleanSearchQuery = excludeParam
                ? `${titleParam} ${primaryArtist} song lyrics`
                : ((titleParam && primaryArtist) 
                    ? `${titleParam} ${primaryArtist} official audio`
                    : (q.toLowerCase().includes('audio') ? q : `${q} official audio`));

              const ytUrl = `https://www.youtube.com/results?search_query=${encodeURIComponent(cleanSearchQuery)}`;
              const fetchRes = await fetch(ytUrl, {
                headers: {
                  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
                },
                signal: AbortSignal.timeout(6000)
              });
              const html = await fetchRes.text();
              const match = html.match(/var ytInitialData = ({.*?});<\/script>/) || html.match(/ytInitialData = ({.*?});/);

              if (match) {
                const data = JSON.parse(match[1]);
                const contents = data?.contents?.twoColumnSearchResultsRenderer?.primaryContents?.sectionListRenderer?.contents || [];
                const candidates = [];

                for (const sec of contents) {
                  const items = sec?.itemSectionRenderer?.contents || [];
                  for (const item of items) {
                    const vr = item?.videoRenderer;
                    if (vr && vr.videoId) {
                      const title = vr.title?.runs?.[0]?.text || '';
                      const channel = vr.ownerText?.runs?.[0]?.text || '';
                      candidates.push({ videoId: vr.videoId, title, channel });
                    }
                  }
                }

                if (candidates.length > 0) {
                  const targetQuery = (titleParam ? `${titleParam} ${artistParam}` : q).toLowerCase();
                  const normTargetTitle = (titleParam || q).toLowerCase().replace(/[^a-z0-9]/g, '');
                  const normArtist = (artistParam || '').toLowerCase().replace(/[^a-z0-9]/g, '');
                  const qWords = targetQuery.match(/[a-z0-9]+/g)?.filter(w => w.length > 2 && !['audio', 'song', 'topic', 'official', 'the', 'and'].includes(w)) || [];

                  // Score candidates: reward official artist channels, VEVO, topic & studio releases
                  const scored = candidates.map(c => {
                    if (excludeParam && c.videoId === excludeParam) {
                      return { ...c, score: -9999 };
                    }

                    const t = c.title.toLowerCase();
                    const ch = c.channel.toLowerCase();
                    let score = 0;

                    // 1. NON-ORIGINAL DISQUALIFIERS / MASSIVE PENALTIES (-5000)
                    const nonOriginalTerms = [
                      'mashup', 'mash up', 'mash-up', 'masted',
                      'remix', 're-mix', 'dj mix', 'club mix', 'dhol mix',
                      'slowed', 'reverb', 'lofi', 'lo-fi',
                      'sped up', 'speed up', 'nightcore', '8d', '16d', 'bass boost',
                      'live', 'concert', 'performance', 'hustle', 'episode', 'audition', 'stage show', 'unplugged',
                      'dialogue', 'scene', 'teaser', 'trailer', 'reaction', 'review', 'parody', 'chipmunk',
                      'status', 'shorts', 'reel', 'cover', 'fan made', 'tribute',
                      'ringtone', 'bgm', 'instrumental', 'karaoke', 'vocals only', 'acapella',
                      'originally performed by', 'in the style of', 'tribute to', 'backing track',
                      'minus one', 'karaoke version', 'instrumental version', 'piano version',
                      'flute version', 'guitar version', 'violin version', 'orchestral version',
                      'piano tutorial', 'guitar tutorial', 'how to play'
                    ];

                    for (const term of nonOriginalTerms) {
                      if (!targetQuery.includes(term)) {
                        if (t.includes(term)) score -= 5000;
                        if (ch.includes(term)) score -= 5000;
                      }
                    }

                    // Disqualify known karaoke / generic cover channels
                    const nonOriginalChannels = [
                      'release - topic', 'karaoke - topic', 'sing2music', 'sing2piano', 'sing2guitar',
                      'sunfly', 'hit trax', 'cc karaoke', 'zoom karaoke', 'sing king'
                    ];
                    if (nonOriginalChannels.some(noc => ch.includes(noc))) {
                      score -= 5000;
                    }

                    // Match significant keywords from title/artist
                    if (qWords.length > 0) {
                      const matchingWords = qWords.filter(w => t.includes(w)).length;
                      const matchRatio = matchingWords / qWords.length;
                      if (matchRatio < 0.35) score -= 80;
                      else score += Math.round(matchRatio * 60);
                    }

                    // 2. ARTIST CHANNEL MATCH (+600)
                    const normCh = ch.replace(/[^a-z0-9]/g, '');
                    const isArtistChannel = normArtist.length >= 3 && (normCh.includes(normArtist) || normArtist.includes(normCh));
                    if (isArtistChannel) {
                      score += 600;
                    }

                    // 3. OFFICIAL TOPIC CHANNEL BOOST (+700) - Only for actual artist topic channels
                    const isArtistTopic = (ch.includes('- topic') || ch.endsWith('topic')) && isArtistChannel;
                    if (isArtistTopic) {
                      score += 700;
                    } else if (ch.includes('- topic') || ch.endsWith('topic')) {
                      score -= 3000;
                    }

                    // Major record labels boost
                    const majorLabels = ['vevo', 'records', 'music', 't-series', 'sony music', 'zee music', 'yrf', 'saregama', 'tips official', 'speed records'];
                    if (majorLabels.some(lbl => ch.includes(lbl))) {
                      score += 350;
                    }

                    // Exact title match boost
                    const normT = t.replace(/[^a-z0-9]/g, '');
                    if (normT === normTargetTitle || normT === normTargetTitle + 'audio') {
                      score += 450;
                    } else if (normT.startsWith(normTargetTitle)) {
                      score += 250;
                    }

                    // Positive boosts for pure studio audio releases
                    if (t.includes('official audio') || t.includes('full audio') || t.includes('(audio)')) {
                      score += 200;
                    } else if (t.includes('audio')) {
                      score += 80;
                    }

                    // Official music videos are embeddable
                    if (t.includes('official video') || t.includes('official music video')) {
                      score += 150;
                    }

                    if (t.includes('lyrical') || t.includes('lyrics')) score += 40;

                    // Severe penalties for film clips, dialogues, movie scenes, actor conversation videos
                    if (t.includes('dialogue') || t.includes('scene') || t.includes('conversation') || t.includes('movie clip')) score -= 500;
                    if (t.includes('trailer') || t.includes('teaser')) score -= 500;
                    if (t.includes('reaction') || t.includes('review') || t.includes('shorts') || t.includes('status')) score -= 500;

                    return { ...c, score };
                  });

                  scored.sort((a, b) => b.score - a.score);
                  const best = scored[0];
                  if (best && best.videoId && best.score > -9000) {
                    const result = { videoId: best.videoId, title: best.title, channel: best.channel };
                    ytSearchCache.set(cacheKey, result);
                    res.setHeader('Content-Type', 'application/json');
                    res.end(JSON.stringify(result));
                    return;
                  }
                }
              }

              // Fallback: regex search
              const matches = html.match(/"videoId":"([a-zA-Z0-9_-]{11})"/g);
              if (matches && matches.length > 0) {
                const firstId = matches[0].replace(/"videoId":"|"/g, '');
                const result = { videoId: firstId };
                ytSearchCache.set(cacheKey, result);
                res.setHeader('Content-Type', 'application/json');
                res.end(JSON.stringify(result));
                return;
              }
            } catch (err) {
              console.error('Error resolving YouTube video:', err);
            }

            res.setHeader('Content-Type', 'application/json');
            res.end(JSON.stringify({ videoId: null }));
            return;
          }
        } catch (e) {
          console.error('Middleware error:', e);
        }

        next();
      });
    }
  };
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), spotifyAndAudioPlugin()],
  server: {
    host: true,
    port: 5173
  }
})
