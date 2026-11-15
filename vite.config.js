import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Runs the real serverless handlers in /api during `npm run dev`, so local
 * development behaves exactly like the deployed Vercel functions.
 */
function serverlessApi() {
  return {
    name: 'serverless-api',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const url = new URL(req.url, 'http://localhost');
        if (!url.pathname.startsWith('/api/')) return next();
        const file = path.join(server.config.root, `${url.pathname.replace(/\/$/, '')}.js`);
        if (!file.startsWith(path.join(server.config.root, 'api')) || url.pathname.includes('/_') || !fs.existsSync(file)) {
          res.statusCode = 404;
          return res.end(JSON.stringify({ error: 'Not found' }));
        }
        try {
          const mod = await server.ssrLoadModule(file);
          req.query = Object.fromEntries(url.searchParams);
          res.status = (code) => { res.statusCode = code; return res; };
          res.json = (body) => { res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(body)); return res; };
          await mod.default(req, res);
        } catch (err) {
          console.error(err);
          res.statusCode = 500;
          res.end(JSON.stringify({ error: err.message }));
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), serverlessApi()],
  server: { host: true, port: 5173 },
  build: { chunkSizeWarningLimit: 900 },
});
