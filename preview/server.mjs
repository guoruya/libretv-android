import http from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.dirname(fileURLToPath(import.meta.url));
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json', '.m3u8': 'application/vnd.apple.mpegurl', '.ts': 'video/mp2t' };
const apiRoutes = new Set(['/api/status', '/api/auth', '/api/search', '/api/detail', '/api/proxy', '/api/hot-list', '/api/douban']);
const files = new Map(['index.html', 'style.css', 'app.js', 'vendor/hls.min.js'].map(name => ['/' + name, path.join(root, name)]));
files.set('/', path.join(root, 'index.html'));
files.set('/sources.json', path.join(root, '../app/src/main/assets/sources.json'));
for (const name of ['sample.m3u8', ...Array.from({ length: 9 }, (_, i) => `part0${i}.ts`)]) files.set('/demo/' + name, path.join(root, '../app/src/androidTest/assets/offline', name));

export function createPreviewServer() {
  return http.createServer(async (req, res) => {
    const host = req.headers.host || '';
    // A local preview must not expose the existing server through DNS rebinding or cross-site requests.
    if (!/^(localhost|127\.0\.0\.1):\d+$/.test(host) || (req.headers.origin && req.headers.origin !== `http://${host}`)) {
      res.writeHead(403).end('仅允许本机同源访问'); return;
    }
    const url = new URL(req.url, `http://${host}`);
    if (apiRoutes.has(url.pathname)) {
      if (!['GET', 'POST'].includes(req.method)) { res.writeHead(405).end(); return; }
      const upstream = http.request({ hostname: '127.0.0.1', port: 8080, path: url.pathname + url.search, method: req.method,
        headers: { ...req.headers, host: '127.0.0.1:8080' } }, response => {
        res.writeHead(response.statusCode, response.headers); response.pipe(res);
        response.on('error', () => res.destroy());
      });
      upstream.setTimeout(45000, () => upstream.destroy(new Error('timeout')));
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: '网页服务未响应，请确认 LibreTV 的 8080 端口正在运行。演示视频仍可使用。' })); else res.destroy(); });
      req.on('aborted', () => upstream.destroy());
      res.on('close', () => { if (!res.writableEnded) upstream.destroy(); });
      req.pipe(upstream); return;
    }
    const file = files.get(url.pathname);
    if (!file || !['GET', 'HEAD'].includes(req.method)) { res.writeHead(404).end('Not found'); return; }
    try {
      const info = await stat(file);
      res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Content-Length': info.size, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      if (req.method === 'HEAD') res.end(); else createReadStream(file).on('error', () => res.destroy()).pipe(res);
    } catch { res.writeHead(404).end('File not found'); }
  });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  createPreviewServer().listen(4174, '127.0.0.1', () => console.log('LibreTV 平板版网页试用：http://localhost:4174'));
}
