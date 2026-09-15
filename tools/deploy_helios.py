import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
token=re.findall(r'TOKEN="([A-Za-z0-9]+)"',(ROOT.parent/'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
route=json.loads((ROOT/'.local/provision.json').read_text())['url'].rsplit('/',1)[0]
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
query=urllib.parse.urlencode(dict(token=token,url=route+'/install.dex',entry='pl.mateusz.plugin.HeliosInstall',arg=route+'/helios.apk|'+digest))
result=urllib.request.urlopen('http://192.168.1.113:8555/agent/dex?'+query,timeout=45).read().decode().replace(token,'[REDACTED]')
print(result)
(ROOT/'artifacts/native-0.2-deployment.json').write_text(json.dumps({'package':'pl.mateusz.helios','version':'0.2.0','apk_sha256':digest,'result':result},indent=2),encoding='utf-8')
