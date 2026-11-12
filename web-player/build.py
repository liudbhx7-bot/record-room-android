#!/usr/bin/env python3
"""Assemble the standalone player using the app's original deck and demo assets."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

source = Path(__file__).resolve().parent
project = source.parent
android = project / 'app/src/main'
parser = argparse.ArgumentParser()
parser.add_argument('--out', type=Path, required=True)
out = parser.parse_args().out.resolve()
if out == source or source in out.parents or out in source.parents:
    raise SystemExit('Choose a separate output folder.')
out.mkdir(exist_ok=True, parents=True)
assets = out / 'assets'
assets.mkdir(exist_ok=True)
(out / 'vendor').mkdir(exist_ok=True)
files = []

def copy(file, destination):
    shutil.copyfile(file, destination)
    files.append(destination.relative_to(out).as_posix())

for name in ['index.html','player.css','player.js','manifest.webmanifest']:
    copy(source/name,out/name)
for file in (source/'vendor').iterdir():
    if file.is_file(): copy(file,out/'vendor'/file.name)
for file in sorted((android/'assets/demo').iterdir()):
    if file.suffix in ('.wav','.json','.png'): copy(file,assets/file.name)
for name in ['tonearm-graphite-v1.png','disc-smoke-lilac-v1.png']:
    copy(android/'assets/design'/name, assets/name)
copy(android/'res/raw/needle.wav',assets/'needle.wav')
icons = project/'apple/RecordRoom/Assets.xcassets/AppIcon.appiconset'
for side in [256,512]: copy(icons/f'Mac-{side}.png',assets/f'icon-{side}.png')
worker = (source/'sw.js').read_text()
digest = hashlib.sha256(worker.encode())
for file in sorted(files):
    digest.update(file.encode())
    digest.update((out/file).read_bytes())
(out/'sw.js').write_text(worker.replace('__FILES__', json.dumps(sorted(files))).replace('__VERSION__', digest.hexdigest()[:16]))
(out/'.nojekyll').touch()
print(out)
