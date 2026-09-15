"""Load the temporary button overlay without installing an APK. Audio transport is clock -> HA."""
import hashlib
import argparse
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import socket
import threading
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--check-only', action='store_true')
args = parser.parse_args()
config = json.loads((ROOT / '.local/ha.json').read_text(encoding='utf-8-sig'))
config['check_only'] = args.check_only
inventory = json.loads((ROOT / 'artifacts/ha-preflight-auth-20260915.json').read_text(encoding='utf-8'))
config['pipeline'] = inventory['preferred_pipeline']
token = os.environ.get('CLOCK_AGENT_TOKEN')
if not token:
    token = re.findall(r'TOKEN="([A-Za-z0-9]+)"', (ROOT.parent / 'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
base = os.environ.get('CLOCK_AGENT_BASE', 'http://192.168.1.113:8555/agent').rstrip('/')
device = urllib.parse.urlparse(base).hostname
route = '/' + secrets.token_urlsafe(32)
dex = (ROOT / '.local/assist/dex/classes.dex').read_bytes()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.client_address[0] != device:
            self.send_error(403)
            return
        if self.path == route + '/config':
            payload = json.dumps(config).encode()
        elif self.path == route + '/classes.dex':
            payload = dex
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(payload)
    def log_message(self, *args):
        pass

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.connect((device, 8555))
    host = sock.getsockname()[0]
server = http.server.ThreadingHTTPServer((host, 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
asset_base = f'http://{host}:{server.server_port}' + route
try:
    query = urllib.parse.urlencode(dict(token=token, url=asset_base+'/classes.dex', entry='AssistButtonProbe', arg=asset_base+'/config'))
    result = urllib.request.urlopen(base+'/dex?'+query, timeout=30).read().decode().replace(token, '[REDACTED]')
    print(result)
    artifact = ROOT/'artifacts/assist-button-20260915'
    artifact.mkdir(exist_ok=True)
    filename = 'connection-check.json' if args.check_only else 'deployment.json'
    (artifact/filename).write_text(json.dumps({'time':datetime.now(timezone.utc).isoformat(), 'dex_sha256':hashlib.sha256(dex).hexdigest(), 'result':result, 'pipeline':config['pipeline']}, indent=2),encoding='utf-8')
finally:
    server.shutdown()
    server.server_close()
