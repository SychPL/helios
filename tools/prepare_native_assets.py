import hashlib
import json
from pathlib import Path
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
assets=ROOT/'app/src/main/assets'
assets.mkdir(parents=True,exist_ok=True)
sources={
    'Geist.ttf':'https://raw.githubusercontent.com/google/fonts/main/ofl/geist/Geist%5Bwght%5D.ttf',
    'GeistMono.ttf':'https://raw.githubusercontent.com/google/fonts/main/ofl/geistmono/GeistMono%5Bwght%5D.ttf',
    'Geist-OFL.txt':'https://raw.githubusercontent.com/google/fonts/main/ofl/geist/OFL.txt',
    'GeistMono-OFL.txt':'https://raw.githubusercontent.com/google/fonts/main/ofl/geistmono/OFL.txt',
}
manifest={}
for name,url in sources.items():
    data=urllib.request.urlopen(url,timeout=15).read();(assets/name).write_bytes(data)
    manifest[name]={'source':url,'sha256':hashlib.sha256(data).hexdigest()}
(assets/'font-provenance.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Native fonts and licenses ready')
