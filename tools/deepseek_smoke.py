#!/usr/bin/env python3
"""Run the actual Kotlin DeepSeek client against its official endpoint; never print the key."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--key-file', type=Path, default=ROOT / 'API.txt')
    parser.add_argument('--out', type=Path, default=ROOT / 'dist/validation-20261029/deepseek-live.json')
    args = parser.parse_args()
    key_path = args.key_file.resolve()
    if not key_path.is_file():
        parser.error('API key file not found')
    # Only a file path enters the process environment. Kotlin reads the secret in memory.
    env = os.environ.copy()
    env.update(LEXICON_DEEPSEEK_LIVE='1', LEXICON_API_FILE=str(key_path), LEXICON_LIVE_REPORT=str(args.out.resolve()))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    started_at = time.time()
    result = subprocess.run([str(ROOT / 'gradlew'), ':data:testDebugUnitTest', '--tests',
        'dev.lexicon.data.DeepSeekLiveTest', '--rerun-tasks', '--console', 'plain'], cwd=ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # Build output is deliberately not echoed: the sanitized report is the only user-facing result.
    if args.out.is_file() and args.out.stat().st_mtime >= started_at:
        report = json.loads(args.out.read_text())
        print(json.dumps({k: report.get(k) for k in ('status', 'failure_code', 'requests_observed')}, ensure_ascii=False))
        for outcome in report.get('outcomes', []):
            print(json.dumps({k: outcome[k] for k in ('task', 'passed', 'elapsed_ms', 'returned_model') if k in outcome}, ensure_ascii=False))
    elif result.returncode:
        print('Build or test setup failed before API verification; no successful API claim.', file=sys.stderr)
    return result.returncode

if __name__ == '__main__':
    raise SystemExit(main())
