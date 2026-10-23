import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createUpdateServer } from './server.mjs';
test('serves only the current release, supports resumed downloads and sees a newly built version', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'libretv-updates-'));
  const server = createUpdateServer(dir); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await writeFile(path.join(dir, 'update.json'), JSON.stringify({ versionName:'1.3.0', versionCode:5 }));
    await writeFile(path.join(dir, 'LibreTV-1.3.0.apk'), 'sample-apk-bytes');
    const info = await (await fetch(base + '/update.json')).json(); assert.equal(info.apkUrl, base + '/LibreTV-1.3.0.apk');
    const partial = await fetch(info.apkUrl, {headers:{Range:'bytes=7-9'}}); assert.equal(partial.status,206); assert.equal(await partial.text(),'apk'); assert.equal(partial.headers.get('content-range'),'bytes 7-9/16');
    assert.equal((await fetch(info.apkUrl,{headers:{Range:'bytes=999-'}})).status,416);
    assert.equal((await fetch(base + '/signing.properties')).status,404);
    assert.equal((await fetch(base + '/update.json',{headers:{Origin:'https://example.com'}})).status,403);
    await writeFile(path.join(dir,'update.json'),JSON.stringify({versionName:'1.3.1',versionCode:6}));
    assert.equal((await (await fetch(base + '/update.json')).json()).versionCode,6);
    assert.equal((await fetch(info.apkUrl)).status,200);
  } finally { await new Promise(resolve => server.close(resolve)); await rm(dir,{recursive:true,force:true}); }
});
