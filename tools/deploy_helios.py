import hashlib
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request


def _clock_ip():
    """the clock's address (set CLOCK_IP, or put {"clock_ip": "..."} in .local/bridge.json)"""
    import os as _os, json as _json
    from pathlib import Path as _Path
    value = _os.environ.get('CLOCK_IP')
    if not value:
        config = _Path(__file__).resolve().parents[1] / '.local' / 'bridge.json'
        if config.is_file():
            value = _json.loads(config.read_text(encoding='utf-8')).get('clock_ip')
    if not value:
        raise SystemExit('Set CLOCK_IP to %s' % "the clock's address, e.g. CLOCK_IP=10.0.0.5")
    return value


CLOCK_IP = _clock_ip()
ROOT=Path(__file__).resolve().parents[1]
token=re.findall(r'TOKEN="([A-Za-z0-9]+)"',(ROOT.parent/'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
route=json.loads((ROOT/'.local/bridge.json').read_text(encoding='utf-8-sig'))['route']
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
digest=hashlib.sha256(apk.read_bytes()).hexdigest()
query=urllib.parse.urlencode(dict(token=token,url=route+'/install.dex',entry='pl.mateusz.plugin.HeliosInstall',arg=route+'/helios.apk|'+digest))
result=urllib.request.urlopen('http://'+CLOCK_IP+':8555/agent/dex?'+query,timeout=45).read().decode().replace(token,'[REDACTED]')
print(result)
(ROOT/'artifacts/native-0.2-deployment.json').write_text(json.dumps({'package':'pl.mateusz.helios','version':'0.2.0','apk_sha256':digest,'result':result},indent=2),encoding='utf-8')
