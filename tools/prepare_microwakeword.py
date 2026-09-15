"""Fetch a pinned HA microWakeWord implementation and its Okay Nabu model."""
import hashlib
import json
from pathlib import Path
import urllib.request

COMMIT = 'bdf91410ee54bf3a2baf698af5c071c5cbac4ea2'
ROOT = Path(__file__).resolve().parents[1] / '.local' / 'microwakeword'
BASE = f'https://raw.githubusercontent.com/home-assistant/android/{COMMIT}/'
FILES = ['LICENSE.md'] + ['microwakeword/src/main/cpp/' + name for name in [
    'CMakeLists.txt', 'Logging.h', 'MicroFrontendWrapper.cpp', 'MicroFrontendWrapper.h',
    'MicroWakeWordEngine.cpp', 'MicroWakeWordEngine.h', 'MicroWakeWord_jni.cpp'
]] + ['app/src/main/assets/wakeword/' + name for name in ['okay_nabu.json', 'okay_nabu.tflite']]

def main():
    manifest = {'repository': 'https://github.com/home-assistant/android', 'commit': COMMIT, 'files': {}}
    for path in FILES:
        data = urllib.request.urlopen(BASE + path, timeout=30).read()
        target = ROOT / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        manifest['files'][path] = hashlib.sha256(data).hexdigest()
    (ROOT / 'provenance.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps(manifest, indent=2))

if __name__ == '__main__':
    main()
