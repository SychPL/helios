"""Bundle Material Design Icons for `mdi:*` tile icons: the webfont plus a compact name -> codepoint map.

Pinned to the MDI release the Home Assistant frontend ships, so the icon picker in HA and the clock agree.
"""
import hashlib
import json
from pathlib import Path
import urllib.request

VERSION = '7.4.47'
ROOT = Path(__file__).resolve().parents[1]
assets = ROOT / 'app/src/main/assets'
sources = {
    'materialdesignicons-webfont.ttf': f'https://cdn.jsdelivr.net/npm/@mdi/font@{VERSION}/fonts/materialdesignicons-webfont.ttf',
    'meta.json': f'https://cdn.jsdelivr.net/npm/@mdi/svg@{VERSION}/meta.json',
}
manifest = {'version': VERSION, 'license': 'MDI-LICENSE'}
data = {}
for name, url in sources.items():
    data[name] = urllib.request.urlopen(url, timeout=30).read()
    manifest[name] = {'source': url, 'sha256': hashlib.sha256(data[name]).hexdigest()}
(assets / 'materialdesignicons-webfont.ttf').write_bytes(data['materialdesignicons-webfont.ttf'])
icons = json.loads(data['meta.json'])
# a real icon name always wins over another icon's alias: `exponent` lists `power` as an alias, and taking names and
# aliases in one pass let it shadow the real `power` (the clock drew x^y for mdi:power)
rows = {icon['name']: icon['codepoint'] for icon in icons}
for icon in icons:
    for alias in icon.get('aliases', []):
        rows.setdefault(alias, icon['codepoint'])
lines = [f'{name} {rows[name]}' for name in sorted(rows)]
(assets / 'mdi-codepoints.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
manifest['icons'] = len(lines)
(assets / 'mdi-provenance.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(f'MDI {VERSION}: {len(lines)} names, font {len(data["materialdesignicons-webfont.ttf"]) // 1024} KiB')
