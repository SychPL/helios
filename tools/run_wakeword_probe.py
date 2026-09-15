"""Run a bounded stats-only microWakeWord DEX experiment on the owner's clock."""
import argparse
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import socket
import threading
import urllib.parse
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--seconds', type=int, default=60, choices=range(1, 121))
    args = parser.parse_args()
    token = os.environ.get('CLOCK_AGENT_TOKEN')
    if not token:
        # Reuse the pre-existing local configuration without printing its secret.
        text = (ROOT.parent / 'docs/SSH-HOWTO.md').read_text(encoding='utf-8')
        token = re.findall(r'TOKEN="([A-Za-z0-9]+)"', text)[0]
    base = os.environ.get('CLOCK_AGENT_BASE', 'http://192.168.1.113:8555/agent').rstrip('/')
    device = urllib.parse.urlparse(base).hostname
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    artifact = ROOT / 'artifacts' / 'wakeword-20260915' / stamp
    artifact.mkdir(parents=True)
    payload = ROOT / '.local/microwakeword/payload'
    payload.mkdir(exist_ok=True)
    shutil.copy2(ROOT / '.local/microwakeword/build-armv7/libmicrowakeword.so', payload)
    shutil.copy2(ROOT / '.local/microwakeword/app/src/main/assets/wakeword/okay_nabu.tflite', payload)
    def digest(name):
        return hashlib.sha256((payload / name).read_bytes()).hexdigest()
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *unused):
            pass
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect((device, 8555))
        host = sock.getsockname()[0]
    server = http.server.ThreadingHTTPServer((host, 0), functools.partial(Handler, directory=str(payload)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    config = dict(base=f'http://{host}:{server.server_port}', library_sha256=digest('libmicrowakeword.so'),
                  model_sha256=digest('okay_nabu.tflite'), live=args.live, seconds=args.seconds)
    (artifact / 'config.json').write_text(json.dumps(dict(config, dex_sha256=digest('classes.dex')), indent=2), encoding='utf-8')
    def call(route, **params):
        url = base + '/' + route + '?' + urllib.parse.urlencode(dict(token=token, **params))
        return urllib.request.urlopen(url, timeout=args.seconds + 60).read().decode().replace(token, '[REDACTED]')
    try:
        before = call('status')
        (artifact / 'before.txt').write_text(before, encoding='utf-8')
        print(before, flush=True)
        result = call('dex', url=config['base'] + '/classes.dex', entry='WakeWordProbe', arg=json.dumps(config))
        (artifact / 'result.txt').write_text(result, encoding='utf-8')
        print(result, flush=True)
        (artifact / 'after.txt').write_text(call('status'), encoding='utf-8')
        print(f'Artifacts: {artifact}', flush=True)
        if 'ERROR=' in result or 'RESULT=' not in result:
            raise SystemExit(1)
    finally:
        server.shutdown()
        server.server_close()

if __name__ == '__main__':
    main()
