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
demos = {}

def copy(file, destination):
    shutil.copyfile(file, destination)
    files.append(destination.relative_to(out).as_posix())

for name in ['index.html','player.css','player.js','manifest.webmanifest']:
    copy(source/name,out/name)
for file in (source/'vendor').iterdir():
    if file.is_file(): copy(file,out/'vendor'/file.name)
demo_dir = android/'assets/demo'
catalog = json.loads((demo_dir/'catalog.json').read_text())
artwork = set()
for track in catalog:
    audio = demo_dir/Path(track['uri']).name
    name = 'assets/' + audio.name
    if name not in demos:
        copy(audio,out/name)
        demos[name] = hashlib.sha256(audio.read_bytes()).hexdigest()
    original = demo_dir/Path(track['artworkUri']).name
    # Web sleeves display small: ship resized versions of the same artwork.
    compact = (source/'covers'/original.name).with_suffix('.webp')
    image = compact if compact.is_file() else original
    if image.name not in artwork:
        copy(image,assets/image.name)
        artwork.add(image.name)
    track['artworkUri'] = 'assets/' + image.name
(assets/'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False))
files.append('assets/catalog.json')
for name in ['tonearm-graphite-v1.png','disc-smoke-lilac-v1.png']:
    copy(android/'assets/design'/name, assets/name)
icons = project/'apple/RecordRoom/Assets.xcassets/AppIcon.appiconset'
for side in [256,512]: copy(icons/f'Mac-{side}.png',assets/f'icon-{side}.png')
worker = (source/'sw.js').read_text()
digest = hashlib.sha256(worker.encode())
for file in sorted(files):
    digest.update(file.encode())
    digest.update((out/file).read_bytes())
media_digest = hashlib.sha256()
for file in sorted(demos):
    media_digest.update(file.encode())
    media_digest.update(demos[file].encode())
(out/'sw.js').write_text(worker.replace('__FILES__', json.dumps(sorted(set(files)-set(demos))))
                        .replace('__DEMOS__', json.dumps(demos, sort_keys=True))
                        .replace('__MEDIA_VERSION__', media_digest.hexdigest()[:16])
                        .replace('__VERSION__', digest.hexdigest()[:16]))
(out/'.nojekyll').touch()
print(out)
