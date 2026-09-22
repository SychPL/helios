"""Publish a dedicated Helios dashboard using HA's normal authenticated API.

Default is create-only; --replace explicitly replaces an existing dashboard's
configuration after saving a local backup. Never touches other dashboards.
"""
import argparse
import json
from pathlib import Path
from datetime import datetime, timezone
import urllib.parse
import websocket
import yaml

ROOT = Path(__file__).resolve().parents[1]


def manifest_path(name):
    """Your own manifest in .local/ha/ wins over the example shipped in ha/ (the example names entities of one particular home)."""
    private = ROOT / '.local' / 'ha' / name
    return private if private.is_file() else ROOT / 'ha' / name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--file', type=Path, default=manifest_path('helios-clock.yaml'))
    parser.add_argument('--replace', action='store_true')
    args = parser.parse_args()
    desired = yaml.safe_load(args.file.read_text(encoding='utf-8'))
    assert isinstance(desired, dict) and 2 <= desired['helios']['version'] <= 6
    config = json.loads((ROOT / '.local/ha.json').read_text(encoding='utf-8-sig'))
    parsed = urllib.parse.urlparse(config['url'])
    endpoint = urllib.parse.urlunparse(parsed._replace(scheme='wss' if parsed.scheme == 'https' else 'ws', path='/api/websocket'))
    ws = websocket.create_connection(endpoint, timeout=15)
    try:
        ws.recv()
        ws.send(json.dumps({'type': 'auth', 'access_token': config['token']}))
        assert json.loads(ws.recv())['type'] == 'auth_ok'
        request_id = 0
        def call(kind, **fields):
            nonlocal request_id
            request_id += 1
            ws.send(json.dumps(dict(id=request_id, type=kind, **fields)))
            while True:
                response = json.loads(ws.recv())
                if response.get('id') == request_id and response.get('type') == 'result':
                    if not response.get('success'):
                        raise RuntimeError(str(response.get('error')))
                    return response.get('result')
        path = 'helios-clock'
        dashboards = call('lovelace/dashboards/list')
        existing = next((d for d in dashboards if d['url_path'] == path), None)
        if existing:
            if not args.replace:
                raise RuntimeError('Helios dashboard already exists; use HA editor or explicit --replace')
            previous = call('lovelace/config', url_path=path)
            stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
            (ROOT / '.local' / f'ha-dashboard-backup-{stamp}.json').write_text(json.dumps(previous, ensure_ascii=False, indent=2), encoding='utf-8')
        else:
            call('lovelace/dashboards/create', url_path=path, title='Helios', icon='mdi:clock-digital', show_in_sidebar=True, require_admin=True, mode='storage')
        call('lovelace/config/save', url_path=path, config=desired)
        actual = call('lovelace/config', url_path=path)
        assert actual == desired
        print('Saved and verified: ' + config['url'].rstrip('/') + '/' + path)
    finally:
        ws.close()

if __name__ == '__main__':
    main()
