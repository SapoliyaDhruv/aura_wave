/** Shared response helpers for the serverless endpoints. */
export function handle(fn, { cacheSeconds = 0 } = {}) {
  return async function handler(req, res) {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
    if (req.method === 'OPTIONS') return res.status(204).end();
    try {
      const body = await fn(req.query || {}, req);
      if (body && body.status && body.error) return res.status(body.status).json({ error: body.error });
      if (cacheSeconds) res.setHeader('Cache-Control', `public, s-maxage=${cacheSeconds}, stale-while-revalidate=${cacheSeconds * 4}`);
      return res.status(200).json(body);
    } catch (err) {
      console.error(`[api] ${req.url}:`, err);
      return res.status(502).json({ error: 'The music service did not respond. Try again in a moment.' });
    }
  };
}

export const bad = (error) => ({ status: 400, error });
