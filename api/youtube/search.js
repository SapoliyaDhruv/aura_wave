const ytSearchCache = new Map();

function parseDuration(str) {
  if (!str) return 0;
  const parts = str.split(':').map(Number);
  if (parts.length === 2) return parts[0] * 60 + parts[1];
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  return 0;
}

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

  const q = req.query?.q || '';
  const titleParam = req.query?.title || '';
  const artistParam = req.query?.artist || '';
  const excludeParam = req.query?.exclude || '';
  const durationParam = parseInt(req.query?.duration, 10) || 0;

  if (!q && !titleParam) {
    return res.status(400).json({ error: 'Missing search parameter' });
  }

  const cacheKey = `${titleParam.toLowerCase().trim()}:::${artistParam.toLowerCase().trim()}:::${q.toLowerCase().trim()}:::${durationParam}`;
  if (!excludeParam && ytSearchCache.has(cacheKey)) {
    return res.status(200).json(ytSearchCache.get(cacheKey));
  }

  try {
    const primaryArtist = (artistParam || '').split(/[,&]/)[0].trim();
    const cleanSearchQuery = excludeParam
      ? `${titleParam} ${primaryArtist} song lyrics`
      : ((titleParam && primaryArtist) 
          ? `${titleParam} ${primaryArtist} official audio`
          : (q.toLowerCase().includes('topic') || q.toLowerCase().includes('audio') ? q : `${q} official audio`));

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
            const durText = vr.lengthText?.simpleText || '';
            candidates.push({
              videoId: vr.videoId,
              title,
              channel,
              durationText: durText,
              durationSec: parseDuration(durText)
            });
          }
        }
      }

      if (candidates.length > 0) {
        const targetQuery = (titleParam ? `${titleParam} ${artistParam}` : q).toLowerCase();
        const normTargetTitle = (titleParam || q).toLowerCase().replace(/[^a-z0-9]/g, '');
        const normArtist = (artistParam || '').toLowerCase().replace(/[^a-z0-9]/g, '');

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

          // 2. ARTIST CHANNEL MATCH (+600) - Huge boost for verified artist channel
          const normCh = ch.replace(/[^a-z0-9]/g, '');
          const isArtistChannel = normArtist.length >= 3 && (normCh.includes(normArtist) || normArtist.includes(normCh));
          if (isArtistChannel) {
            score += 600;
          }

          // 3. OFFICIAL TOPIC CHANNEL BOOST (+700)
          // ONLY boost if topic channel belongs to the actual artist! E.g. "Tai Verdes - Topic", NOT "Release - Topic"
          const isArtistTopic = (ch.includes('- topic') || ch.endsWith('topic')) && isArtistChannel;
          if (isArtistTopic) {
            score += 700;
          } else if (ch.includes('- topic') || ch.endsWith('topic')) {
            // Unrelated or generic topic channel (e.g. Release - Topic, Various Artists - Topic)
            score -= 3000;
          }

          // Major record labels boost
          const majorLabels = ['vevo', 'records', 'music', 't-series', 'sony music', 'zee music', 'yrf', 'saregama', 'tips official', 'speed records'];
          if (majorLabels.some(lbl => ch.includes(lbl))) {
            score += 350;
          }

          // 4. EXACT SONG TITLE MATCH (+450)
          const normT = t.replace(/[^a-z0-9]/g, '');
          if (normT === normTargetTitle || normT === normTargetTitle + 'audio') {
            score += 450;
          } else if (normT.startsWith(normTargetTitle)) {
            score += 250;
          }

          if (t.includes('official audio') || t.includes('(audio)') || t.includes('[audio]')) {
            score += 300;
          } else if (t.includes('official video') || t.includes('official music video')) {
            score += 180;
          }

          // 5. DURATION MATCH ACCURACY
          if (durationParam > 0 && c.durationSec > 0) {
            const diff = Math.abs(c.durationSec - durationParam);
            if (diff <= 6) {
              score += 300; // Perfect duration match for album track!
            } else if (diff <= 15) {
              score += 150;
            } else if (diff > 45) {
              score -= 800; // Likely a compilation or movie scene clip
            }
          }

          return { ...c, score };
        });

        scored.sort((a, b) => b.score - a.score);
        const best = scored[0];
        if (best && best.videoId && best.score > -2000) {
          const result = {
            videoId: best.videoId,
            title: best.title,
            channel: best.channel,
            durationSec: best.durationSec
          };
          ytSearchCache.set(cacheKey, result);
          return res.status(200).json(result);
        }
      }
    }

    const matches = html.match(/"videoId":"([a-zA-Z0-9_-]{11})"/g);
    if (matches && matches.length > 0) {
      const firstId = matches[0].replace(/"videoId":"|"/g, '');
      const result = { videoId: firstId };
      ytSearchCache.set(cacheKey, result);
      return res.status(200).json(result);
    }
  } catch (err) {
    console.error('Error resolving YouTube video in api handler:', err);
  }

  return res.status(200).json({ videoId: null });
}
