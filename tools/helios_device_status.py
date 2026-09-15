"""Read-only verification of the installed native Helios package."""
import json
from pathlib import Path
import re
import urllib.parse
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
token=re.findall(r'TOKEN="([A-Za-z0-9]+)"',(ROOT.parent/'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
query=urllib.parse.urlencode(dict(token=token,cmd='pm list packages pl.mateusz.helios; getprop persist.sys.timezone'))
data=urllib.request.urlopen('http://192.168.1.113:8555/agent/exec?'+query,timeout=10).read().decode().replace(token,'[REDACTED]')
(ROOT/'artifacts/native-0.1/device-package-status.txt').write_text(data,encoding='utf-8')
print(data)
