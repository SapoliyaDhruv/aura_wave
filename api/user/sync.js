// In-memory sync store for serverless environment
const globalSyncStore = globalThis._aurawaveSyncStore || (globalThis._aurawaveSyncStore = new Map());

export default async function handler(req, res) {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  // GET: Fetch synced data for a code
  if (req.method === 'GET') {
    const { code } = req.query || {};
    if (!code) {
      return res.status(400).json({ error: 'Missing sync code' });
    }

    const cleanCode = String(code).toUpperCase().trim();
    const data = globalSyncStore.get(cleanCode);

    if (data) {
      return res.status(200).json({ ok: true, found: true, data: data.payload, updatedAt: data.updatedAt });
    }

    // Try fallback external KV if configured, else return not found
    return res.status(200).json({ ok: true, found: false, message: 'Sync code not found or expired' });
  }

  // POST: Store or update data for a code
  if (req.method === 'POST') {
    try {
      const body = typeof req.body === 'string' ? JSON.parse(req.body) : (req.body || {});
      const { code, payload } = body;

      if (!code || !payload) {
        return res.status(400).json({ error: 'Missing code or payload' });
      }

      const cleanCode = String(code).toUpperCase().trim();
      const record = {
        payload,
        updatedAt: Date.now()
      };

      globalSyncStore.set(cleanCode, record);

      // Clean up records older than 30 days if store grows too large
      if (globalSyncStore.size > 1000) {
        const oldestKey = globalSyncStore.keys().next().value;
        globalSyncStore.delete(oldestKey);
      }

      return res.status(200).json({ ok: true, code: cleanCode, updatedAt: record.updatedAt });
    } catch (err) {
      console.error('Error saving sync data:', err);
      return res.status(500).json({ error: 'Failed to process sync data' });
    }
  }

  return res.status(405).json({ error: 'Method not allowed' });
}
