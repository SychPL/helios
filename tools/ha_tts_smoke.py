"""Generate a fixed test sentence through HA Assist. Does not use a microphone or play audio."""
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
import websocket

ROOT = Path(__file__).resolve().parents[1]

def main():
    config = json.loads((ROOT / '.local/ha.json').read_text(encoding='utf-8-sig'))
    inventory = json.loads((ROOT / 'artifacts/ha-preflight-auth-20260915.json').read_text(encoding='utf-8'))
    base = config['url'].rstrip('/')
    parsed = urllib.parse.urlparse(base)
    ws_url = urllib.parse.urlunparse(parsed._replace(scheme='wss' if parsed.scheme == 'https' else 'ws', path=parsed.path + '/api/websocket'))
    ws = websocket.create_connection(ws_url, timeout=45)
    result = {'text': 'To jest test głosu Heliosa.', 'pipeline': inventory['preferred_pipeline'], 'events': []}
    started = time.monotonic()
    try:
        if json.loads(ws.recv()).get('type') != 'auth_required':
            raise RuntimeError('Unexpected HA handshake')
        ws.send(json.dumps({'type': 'auth', 'access_token': config['token']}))
        if json.loads(ws.recv()).get('type') != 'auth_ok':
            raise RuntimeError('HA authentication failed')
        ws.send(json.dumps({'id': 1, 'type': 'assist_pipeline/run', 'pipeline': result['pipeline'],
                            'start_stage': 'tts', 'end_stage': 'tts', 'input': {'text': result['text']}, 'timeout': 30}))
        audio_url = None
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            message = json.loads(ws.recv())
            if message.get('type') == 'result' and not message.get('success'):
                raise RuntimeError('Pipeline rejected: ' + message.get('error', {}).get('code', 'unknown'))
            event = message.get('event', {})
            if event:
                result['events'].append({'type': event['type'], 'elapsed_ms': round((time.monotonic() - started) * 1000)})
                if event['type'] == 'error':
                    result['error_code'] = event.get('data', {}).get('code')
                    break
                if event['type'] == 'tts-end':
                    output = event['data']['tts_output']
                    audio_url = urllib.parse.urljoin(base + '/', output['url'])
                    result['mime_type'] = output.get('mime_type')
                if event['type'] == 'run-end':
                    break
        if audio_url:
            # Signed media URL supplied by HA. Do not forward the HA bearer token to this URL.
            with urllib.request.urlopen(audio_url, timeout=15) as response:
                audio = response.read(2_000_001)
                if len(audio) > 2_000_000:
                    raise RuntimeError('Unexpectedly large TTS response')
                result['audio_bytes'] = len(audio)
                target = ROOT / '.local/helios-tts-smoke.mp3'
                target.write_bytes(audio)
                result['audio_downloaded'] = True
        result['played_on_clock'] = False
    finally:
        ws.close()
    target = ROOT / 'artifacts/ha-tts-smoke-20260915.json'
    target.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print(target.read_text(encoding='utf-8'))

if __name__ == '__main__':
    main()
