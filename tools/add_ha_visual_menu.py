"""Add the visual menu tab, preserving the user's existing dashboard and rules."""
import json
from pathlib import Path
from datetime import datetime, timezone
import urllib.parse
import websocket
import yaml

ROOT=Path(__file__).resolve().parents[1]

def main():
    cfg=json.loads((ROOT/'.local/ha.json').read_text(encoding='utf-8-sig'))
    view=yaml.safe_load((ROOT/'ha/helios-menu-view.yaml').read_text(encoding='utf-8'))
    parsed=urllib.parse.urlparse(cfg['url'])
    endpoint=urllib.parse.urlunparse(parsed._replace(scheme='wss' if parsed.scheme=='https' else 'ws',path='/api/websocket'))
    ws=websocket.create_connection(endpoint,timeout=15)
    try:
        ws.recv();ws.send(json.dumps({'type':'auth','access_token':cfg['token']}));assert json.loads(ws.recv())['type']=='auth_ok'
        mid=0
        def call(kind,**fields):
            nonlocal mid
            mid+=1;ws.send(json.dumps(dict(id=mid,type=kind,**fields)))
            while True:
                reply=json.loads(ws.recv())
                if reply.get('id')==mid and reply.get('type')=='result':
                    if not reply.get('success'):raise RuntimeError(str(reply.get('error')))
                    return reply.get('result')
        path='helios-clock';before=call('lovelace/config',url_path=path)
        if any(v.get('path')=='menu-zegara' for v in before.get('views',[])):
            print('Menu already exists; edit it visually in HA. No changes made.');return
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        (ROOT/'.local'/f'ha-before-menu-{stamp}.json').write_text(json.dumps(before,ensure_ascii=False,indent=2),encoding='utf-8')
        after=json.loads(json.dumps(before));after.setdefault('views',[]).append(view)
        call('lovelace/config/save',url_path=path,config=after)
        readback=call('lovelace/config',url_path=path)
        assert readback==after and readback['helios']==before['helios']
        print('Visual menu saved and verified: '+cfg['url'].rstrip('/')+'/'+path+'/menu-zegara')
    finally:ws.close()

if __name__=='__main__':main()
