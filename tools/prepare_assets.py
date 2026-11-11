#!/usr/bin/env python3
"""Fetch fixed-revision open fonts. Personal dictionary assets are prepared by Gradle."""
import hashlib, json, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'data/src/main/assets'
FILES = [
 ('JetBrainsMono', 'JetBrains/JetBrainsMono/19371302b95d218af43299bce79ddbddd0bc364d/fonts/ttf/JetBrainsMono-Regular.ttf', 'app/src/main/res/font/jetbrains_mono.ttf'),
 ('JetBrainsMono OFL', 'JetBrains/JetBrainsMono/19371302b95d218af43299bce79ddbddd0bc364d/OFL.txt', 'licenses/JetBrainsMono-OFL.txt'),
 ('SourceSerif', 'adobe-fonts/source-serif/80d3f8894c09c937bebfa9011247d2e1c79fd6f4/TTF/SourceSerif4-Regular.ttf', 'app/src/main/res/font/source_serif.ttf'),
 ('SourceSerif OFL', 'adobe-fonts/source-serif/80d3f8894c09c937bebfa9011247d2e1c79fd6f4/LICENSE.md', 'licenses/SourceSerif-OFL.txt'),
]
def main():
    records = []
    for name, source, target in FILES:
        p = ROOT/target; p.parent.mkdir(parents=True, exist_ok=True)
        url = 'https://raw.githubusercontent.com/' + source
        if not p.exists():
            with urllib.request.urlopen(url, timeout=60) as response: p.write_bytes(response.read())
        records.append(dict(name=name, url=url, bytes=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS/'asset-manifest.json').write_text(json.dumps(dict(resources=records, dictionary=dict(mode='personal build inputs from data/dic/*.mdx; user selectable MDX/SQLite', catalog='generated assets/dictionaries/catalog.tsv', source_archive_includes_corpus=False)),indent=2))
    (ASSETS/'licenses').mkdir(exist_ok=True)
    for name in ['JetBrainsMono-OFL.txt','SourceSerif-OFL.txt']:
        (ASSETS/'licenses'/name).write_bytes((ROOT/'licenses'/name).read_bytes())
    source = ROOT/'domain/reference/PY_FSRS_LICENSE'
    if source.exists():
        for p in [ROOT/'licenses/FSRS-MIT.txt', ASSETS/'licenses/FSRS-MIT.txt']: p.write_bytes(source.read_bytes())
    print('Font assets verified; dictionary assets are prepared separately by Gradle.')
if __name__ == '__main__': main()
