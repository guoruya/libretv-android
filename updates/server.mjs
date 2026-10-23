import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
function localIp(ip) {
  const address = ip?.replace(/^::ffff:/, '');
  return address === '::1' || /^(127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(address || '');
}
export function createUpdateServer(directory = path.join(root, '../dist')) {
  return http.createServer(async (req, res) => {
    const host = req.headers.host || '';
    if (!localIp(req.socket.remoteAddress) || !/^(localhost|\d{1,3}(?:\.\d{1,3}){3}):\d+$/.test(host) ||
        (req.headers.origin && req.headers.origin !== `http://${host}`)) { res.writeHead(403).end(); return; }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405).end(); return; }
    try {
      const url = new URL(req.url, `http://${host}`);
      const release = JSON.parse(await readFile(path.join(directory, 'update.json'), 'utf8'));
      const apk = `LibreTV-${release.versionName}.apk`;
      if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(release.versionName)) throw new Error('Invalid release version');
      if (url.pathname === '/update.json') {
        const content = JSON.stringify({ ...release, apkUrl: `http://${host}/${apk}` });
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'Content-Length': Buffer.byteLength(content) }); res.end(req.method === 'HEAD' ? undefined : content); return;
      }
      if (!/^\/LibreTV-(?:Android-)?[0-9]+\.[0-9]+\.[0-9]+(?:-source\.zip|\.apk)$/.test(url.pathname)) { res.writeHead(404).end(); return; }
      const file = path.join(directory, url.pathname.slice(1)), info = await stat(file);
      let start = 0, end = info.size - 1, partial = false;
      if (req.headers.range) {
        const match = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range);
        if (!match) { res.writeHead(416, { 'Content-Range': `bytes */${info.size}` }).end(); return; }
        start = Number(match[1]); if (match[2]) end = Math.min(Number(match[2]), end);
        if (!Number.isSafeInteger(start) || start > end || start >= info.size) { res.writeHead(416, { 'Content-Range': `bytes */${info.size}` }).end(); return; }
        partial = true;
      }
      res.writeHead(partial ? 206 : 200, { 'Content-Type': file.endsWith('.apk') ? 'application/vnd.android.package-archive' : 'application/zip',
        'Content-Length': end - start + 1, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
        ...(partial ? { 'Content-Range': `bytes ${start}-${end}/${info.size}` } : {}) });
      if (req.method === 'HEAD') { res.end(); return; }
      const stream = createReadStream(file, { start, end }); stream.on('error', () => res.destroy()); res.on('close', () => stream.destroy()); stream.pipe(res);
    } catch { if (!res.headersSent) res.writeHead(404).end('请先运行 build-apk.sh 生成更新包'); else res.destroy(); }
  });
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  createUpdateServer().listen(4175, '0.0.0.0', () => {
    const addresses = Object.values(os.networkInterfaces()).flat().filter(item => item.family === 'IPv4' && !item.internal && localIp(item.address));
    console.log('LibreTV 同 Wi-Fi 更新服务：');
    for (const item of addresses) console.log(`http://${item.address}:4175/update.json`);
  });
}
