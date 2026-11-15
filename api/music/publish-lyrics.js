import crypto from 'crypto';

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

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed. Use POST.' });
  }

  const { trackName, artistName, albumName, duration, plainLyrics, syncedLyrics } = req.body || {};

  if (!trackName || !artistName || !syncedLyrics) {
    return res.status(400).json({
      error: 'Missing required fields: trackName, artistName, and syncedLyrics are required.'
    });
  }

  const cleanTrack = String(trackName).trim();
  const cleanArtist = String(artistName).trim();
  const cleanAlbum = albumName ? String(albumName).trim() : cleanTrack;
  const trackDuration = Math.round(Number(duration) || 180);

  // Generate plainLyrics from syncedLyrics if not provided
  let plain = plainLyrics;
  if (!plain) {
    plain = syncedLyrics
      .split('\n')
      .map(line => line.replace(/^\[\d{2}:\d{2}\.?\d{2,3}?\]\s*/, '').trim())
      .filter(Boolean)
      .join('\n');
  }

  try {
    // 1. Request cryptographic proof-of-work challenge from LRCLIB
    const challengeRes = await fetch('https://lrclib.net/api/request-challenge', {
      method: 'POST',
      headers: {
        'User-Agent': 'AuraWave/1.0 (https://aurawave-music.vercel.app)'
      },
      signal: AbortSignal.timeout(6000)
    });

    if (!challengeRes.ok) {
      throw new Error(`Challenge request failed with status: ${challengeRes.status}`);
    }

    const challengeData = await challengeRes.json();
    const { prefix, target } = challengeData;

    if (!prefix || !target) {
      throw new Error('Invalid challenge response received from LRCLIB');
    }

    // 2. Solve PoW nonce: sha256(prefix + nonce) <= target
    const targetBuf = Buffer.from(target, 'hex');
    let nonce = 0;
    let solved = false;
    const startTime = Date.now();
    const MAX_SOLVE_TIME_MS = 11000; // 11s cap to ensure we stay under serverless execution timeout

    while (Date.now() - startTime < MAX_SOLVE_TIME_MS) {
      const hash = crypto.createHash('sha256').update(prefix + nonce).digest();
      // Fast check: target usually has 00 00 00 in first 3 bytes
      if (hash[0] === 0 && hash[1] === 0 && hash[2] === 0) {
        if (hash.compare(targetBuf) <= 0) {
          solved = true;
          break;
        }
      }
      nonce++;
    }

    if (!solved) {
      return res.status(503).json({
        error: 'Proof-of-work solver timed out. Please try again.',
        code: 'POW_TIMEOUT'
      });
    }

    const publishToken = `${prefix}:${nonce}`;

    // 3. Publish lyrics to LRCLIB
    const publishPayload = {
      trackName: cleanTrack,
      artistName: cleanArtist,
      albumName: cleanAlbum,
      duration: trackDuration,
      plainLyrics: plain,
      syncedLyrics: syncedLyrics
    };

    const publishRes = await fetch('https://lrclib.net/api/publish', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Publish-Token': publishToken,
        'User-Agent': 'AuraWave/1.0 (https://aurawave-music.vercel.app)'
      },
      body: JSON.stringify(publishPayload),
      signal: AbortSignal.timeout(8000)
    });

    if (publishRes.ok) {
      return res.status(200).json({
        success: true,
        message: 'Successfully published synced lyrics to LRCLIB!',
        publishToken,
        trackName: cleanTrack,
        artistName: cleanArtist
      });
    } else {
      const errorText = await publishRes.text().catch(() => '');
      return res.status(publishRes.status).json({
        error: `LRCLIB publishing rejected: ${publishRes.statusText}`,
        details: errorText
      });
    }
  } catch (err) {
    console.error('Error publishing lyrics to LRCLIB:', err);
    return res.status(500).json({
      error: 'Failed to complete lyrics publishing workflow',
      message: err.message
    });
  }
}
