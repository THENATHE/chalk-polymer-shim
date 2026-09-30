#!/usr/bin/env python3
"""Fetch hash-pinned public dependencies; supply the separately built 26.3 Chalk port."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mc', choices=['26.2', '26.3'], default='26.3')
parser.add_argument('--chalk-port', type=Path, help='Chalk 3.2.1+26.3 production JAR from the linked port')
args = parser.parse_args()
lock = json.loads((root / 'dependencies.lock.json').read_text())[args.mc]
out = root / 'libs' / args.mc
out.mkdir(parents=True, exist_ok=True)
for project, entry in lock.items():
    if 'url' not in entry:
        target = root / 'libs' / entry['filename']
        source = args.chalk_port or target
        if not source.is_file():
            parser.error('Supply --chalk-port /path/to/' + entry['filename'] + '; build source commit ' + entry['commit'] + ' from ' + entry['source'])
        with zipfile.ZipFile(source) as archive:
            meta = json.loads(archive.read('fabric.mod.json'))
        if meta['id'] != 'chalk' or meta['version'] != entry['version']:
            raise ValueError('Wrong Chalk port: expected ' + entry['version'])
        if source.resolve() != target.resolve():
            shutil.copy2(source, target)
        print('Using port:', target.name, 'SHA-512:', hashlib.sha512(target.read_bytes()).hexdigest())
        continue
    target = out / entry['filename']
    expected = entry['hashes']['sha512']
    if not target.exists() or hashlib.sha512(target.read_bytes()).hexdigest() != expected:
        request = urllib.request.Request(entry['url'], headers={'User-Agent': 'THENATHE/chalk-polymer-shim dependency setup'})
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
        if hashlib.sha512(data).hexdigest() != expected:
            raise ValueError('SHA-512 mismatch: ' + entry['filename'])
        target.write_bytes(data)
    print('Verified:', target.name)
