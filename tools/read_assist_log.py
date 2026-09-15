"""Retrieve only the dedicated Helios controlled-test event log, never raw audio."""
import json
import os
from pathlib import Path
import re
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
token = os.environ.get('CLOCK_AGENT_TOKEN')
if not token:
    token = re.findall(r'TOKEN="([A-Za-z0-9]+)"', (ROOT.parent / 'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
base = os.environ.get('CLOCK_AGENT_BASE', 'http://192.168.1.113:8555/agent').rstrip('/')
params = dict(token=token, cmd='cat /data/user/0/pl.mateusz.clockadbprobe/files/helios_assist_test.jsonl')
data = urllib.request.urlopen(base+'/exec?'+urllib.parse.urlencode(params), timeout=15).read().decode().replace(token, '[REDACTED]')
ha = json.loads((ROOT / '.local/ha.json').read_text(encoding='utf-8-sig')).get('token')
if ha:
    data = data.replace(ha, '[REDACTED]')
artifact = ROOT / 'artifacts/assist-button-20260915/device-events.txt'
artifact.write_text(data, encoding='utf-8')
print(data[-8000:])
