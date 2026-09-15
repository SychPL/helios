"""Read-only HA connectivity and Assist pipeline inventory. Never sends audio."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
import urllib.parse
import websocket

ROOT = Path(__file__).resolve().parents[1]

def inspect(config):
    base = config['url'].rstrip('/')
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        raise ValueError('HA URL must use http or https')
    token = config.get('token', '').strip()
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    result = {'url': base, 'token_configured': bool(token)}
    try:
        with urllib.request.urlopen(urllib.request.Request(base + '/api/', headers=headers), timeout=8) as response:
            result['api_status'] = response.status
    except urllib.error.HTTPError as error:
        result['api_status'] = error.code
    ws_url = urllib.parse.urlunparse(parsed._replace(scheme='wss' if parsed.scheme == 'https' else 'ws', path=parsed.path + '/api/websocket'))
    ws = websocket.create_connection(ws_url, timeout=10)
    try:
        hello = json.loads(ws.recv())
        result['websocket'] = hello.get('type')
        result['ha_version'] = hello.get('ha_version')
        if token and hello.get('type') == 'auth_required':
            ws.send(json.dumps({'type': 'auth', 'access_token': token}))
            auth = json.loads(ws.recv())
            result['auth'] = auth.get('type')
            if auth.get('type') == 'auth_ok':
                ws.send(json.dumps({'id': 1, 'type': 'assist_pipeline/pipeline/list'}))
                reply = json.loads(ws.recv())
                result['pipelines_success'] = reply.get('success', False)
                if reply.get('success'):
                    data = reply['result']
                    result['preferred_pipeline'] = data.get('preferred_pipeline')
                    fields = ('id', 'name', 'language', 'stt_engine', 'stt_language', 'tts_engine', 'tts_language', 'tts_voice', 'conversation_engine')
                    result['pipelines'] = [{key: p.get(key) for key in fields} for p in data.get('pipelines', [])]
                else:
                    result['pipeline_error_code'] = reply.get('error', {}).get('code')
    finally:
        ws.close()
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, default=ROOT / '.local/ha.json')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = inspect(json.loads(args.config.read_text(encoding='utf-8-sig')))
    output = json.dumps(result, indent=2, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + '\n', encoding='utf-8')
    print(output)

if __name__ == '__main__':
    main()
