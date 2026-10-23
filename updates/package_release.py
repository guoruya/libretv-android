#!/usr/bin/env python3
"""Package the signed build and its corresponding source for app updates."""
import json
import os
from pathlib import Path
import re
import shutil
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent.parent
output = root / 'app/build/outputs/apk/release'
build = json.loads((output / 'output-metadata.json').read_text())
item = build['elements'][0]
version = item['versionName']
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise ValueError('正式版需要三段数字版本号')
dist = root / 'dist'
dist.mkdir(exist_ok=True)
apk = dist / f'LibreTV-{version}.apk'
shutil.copyfile(output / item['outputFile'], apk.with_suffix('.apk.part'))
os.replace(apk.with_suffix('.apk.part'), apk)
source = dist / f'LibreTV-Android-{version}-source.zip'
files = [root / name for name in ['.gitignore', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat', 'build-apk.sh', 'README.md', 'TESTING.md', 'LICENSE', 'THIRD_PARTY_NOTICES.txt', 'app/build.gradle']]
for folder in ['app/src', 'gradle/wrapper', 'preview', 'updates']:
    files.extend(p for p in (root / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
with zipfile.ZipFile(source.with_suffix('.zip.part'), 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in files:
        archive.write(file, Path('LibreTV-Android') / file.relative_to(root))
os.replace(source.with_suffix('.zip.part'), source)
channel = ET.parse(root / 'app/src/main/res/values/updates.xml').getroot().find('string').text or ''
apk_url = urllib.parse.urljoin(channel, apk.name)
if channel.startswith('https://github.com/') and '/releases/latest/download/' in channel:
    apk_url = apk_url.replace('/releases/latest/download/', f'/releases/download/v{version}/')
manifest = {'applicationId': build['applicationId'], 'versionCode': item['versionCode'], 'versionName': version, 'minSdk': 26,
            'apkUrl': apk_url,
            'notes': (root / 'updates/release-notes.txt').read_text().strip()}
pending = dist / 'update.json.part'
pending.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
os.replace(pending, dist / 'update.json')
print(f'安装包：{apk}\n配套源码：{source}\n更新信息：{dist / "update.json"}')
