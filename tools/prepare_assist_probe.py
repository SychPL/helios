"""Fetch the pinned Java-only WebSocket dependencies for the Android DEX probe."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / '.local/assist'
ARTIFACTS = {
    'Java-WebSocket-1.6.0.jar': 'org/java-websocket/Java-WebSocket/1.6.0/Java-WebSocket-1.6.0.jar',
    'slf4j-api-2.0.13.jar': 'org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar',
    'slf4j-nop-2.0.13.jar': 'org/slf4j/slf4j-nop/2.0.13/slf4j-nop-2.0.13.jar',
}

ROOT.mkdir(parents=True, exist_ok=True)
manifest = {}
for name, path in ARTIFACTS.items():
    url = 'https://repo.maven.apache.org/maven2/' + path
    data = urllib.request.urlopen(url, timeout=30).read()
    (ROOT / name).write_bytes(data)
    manifest[name] = {'url': url, 'sha256': hashlib.sha256(data).hexdigest()}
(ROOT / 'dependencies.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print('Dependencies downloaded; hashes saved in .local/assist/dependencies.json')
