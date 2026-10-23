import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createPreviewServer } from './server.mjs';

test('local preview serves media, rejects private files, foreign hosts and cross-origin requests', async () => {
  const server = createPreviewServer(); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port, base = `http://127.0.0.1:${port}`;
  try {
    const index = await fetch(base); assert.equal(index.status, 200); assert.match(await index.text(), /全屏播放/);
    const playlist = await fetch(base + '/demo/sample.m3u8'); assert.match(await playlist.text(), /#EXTM3U/);
    const segment = await fetch(base + '/demo/part00.ts'); assert.equal(segment.status, 200); assert.ok((await segment.arrayBuffer()).byteLength > 10000);
    for (const path of ['/signing.properties', '/../signing.properties', '/api/not-allowed']) assert.equal((await fetch(base + path)).status, 404);
    assert.equal((await fetch(base, { headers: { Origin: 'https://example.com' } })).status, 403);
    const foreign = await new Promise(resolve => { http.get(base, { headers: { Host: 'example.com' } }, response => { response.resume(); resolve(response.statusCode); }); }); assert.equal(foreign, 403);
  } finally { await new Promise(resolve => server.close(resolve)); }
});
