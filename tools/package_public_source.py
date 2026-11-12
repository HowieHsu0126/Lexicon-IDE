"""Prepare a new, reviewable public source tree; never authenticates, pushes or publishes.

Only explicit source roots and extensions are copied. Dictionaries, credentials,
user databases and runtime state are excluded. Output must be a new directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SUFFIXES = {'.kt','.kts','.xml','.json','.txt','.md','.py','.mjs','.ttf','.otf','.png','.webp','.toml','.properties','.jar','.pro','.bat','.sh','.tsv'}
BANNED = {'.git','.gradle','.kotlin','.build-support','build','node_modules','__pycache__','dic','sessions','dsh-home'}
PRIVATE = re.compile(rb'/Users/bytedance|code\.byted\.org|larkoffice\.com|124\.174\.79\.')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_files():
    names={'LICENSE','AGENTS.md','FREEZE.md','RELEASE_NOTES.md','.gitignore','gradlew','gradlew.bat','gradle.properties','settings.gradle.kts','build.gradle.kts','app/proguard-rules.pro'}
    for name in ['gradle','app/src','data/src','domain/src','data/schemas','domain/reference','licenses']:
        for path in (ROOT/name).rglob('*'):
            if path.is_file() and path.suffix in SOURCE_SUFFIXES and not BANNED.intersection(path.relative_to(ROOT).parts):
                names.add(path.relative_to(ROOT).as_posix())
    names.update(p.relative_to(ROOT).as_posix() for p in (ROOT/'docs/public').glob('*.md'))
    names.update(module+'/build.gradle.kts' for module in ['app','data','domain'])
    names.update(p.relative_to(ROOT).as_posix() for p in (ROOT/'tools').glob('*') if p.suffix in {'.py','.mjs'})
    # The reader reference is reproducible from these frozen public metadata inputs.
    import ielts_corpus
    names.update('docs/research/ielts-20261108/'+name+'.json' for name in ielts_corpus._INPUTS)
    names.add('docs/research/ielts-20261108/final-manifest.json')
    names.add('docs/research/explanation-calibration-20261110/cases.json')
    names.add('dist/validation-ielts-corpus-2026-11-08/corpus-01/reference.json')
    # Read-only archive-verifier unit tests import this script; no archived releases or sessions are included.
    names.add('dist/validation-ielts-pairing-2026-11-09/package-136.py')
    for name in names:
        path=ROOT/name
        if path.is_symlink() or not path.is_file() or ROOT not in path.resolve().parents:
            raise ValueError('source_path_invalid')
    return sorted(names)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args();out=args.out.resolve()
    if out.exists() or not out.parent.is_dir():raise ValueError('new_output_directory_required')
    names=source_files()
    patterns=[]
    keyfile=ROOT/'API.txt'
    if keyfile.is_file():
        raw=keyfile.read_text().strip()
        if not 16<=len(raw)<=1024:raise ValueError('key_scan_input_invalid')
        patterns=[raw.encode(e) for e in ['utf-8','utf-16le','utf-16be']]
    mapping={name:ROOT/name for name in names}
    mapping.update({'README.md':ROOT/'docs/public/README.md','HARNESS.md':ROOT/'docs/public/HARNESS.md',
                    'VALIDATION.md':ROOT/'docs/public/VALIDATION.md'})
    for name,path in mapping.items():
        raw=path.read_bytes()
        if any(p in raw for p in patterns) or (name != 'tools/package_public_source.py' and PRIVATE.search(raw)):raise ValueError('private_content_found:'+name)
    out.mkdir(mode=0o755)
    entries=[]
    for name,path in sorted(mapping.items()):
        dest=out/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
        entries.append({'path':name,'sha256':sha(dest),'bytes':dest.stat().st_size})
    ignore=out/'.gitignore'
    ignore.write_text(ignore.read_text()+'\n.build-support/\n**/__pycache__/\n*.pyc\n*.pem\n*.key\n*.p12\n*.pfx\n*.sqlite\n*.sqlite3\n*.db\n*.mdx\n*.mdd\npublic-source-manifest.json\n')
    for entry in entries:
        if entry['path']=='.gitignore':entry.update(sha256=sha(ignore),bytes=ignore.stat().st_size)
    report={'schema':'lexicon.public-source.v1','status':'prepared','files':entries,'known_key_scan':True,
            'dictionary_files':0,'private_runtime_files':0,'build_verified':False,'published':False}
    (out/'public-source-manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'source_files':len(entries),'output':str(out),'status':'prepared','published':False}))
if __name__=='__main__':main()
