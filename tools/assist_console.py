"""Loopback push-to-talk controls, with a clock-only LAN asset/event endpoint."""
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import socket
import threading
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
config = json.loads((ROOT / '.local/ha.json').read_text(encoding='utf-8-sig'))
inventory = json.loads((ROOT / 'artifacts/ha-preflight-auth-20260915.json').read_text(encoding='utf-8'))
config.update(pipeline=inventory['preferred_pipeline'], run_once=True)
token = os.environ.get('CLOCK_AGENT_TOKEN') or re.findall(r'TOKEN="([A-Za-z0-9]+)"', (ROOT.parent / 'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
agent = os.environ.get('CLOCK_AGENT_BASE', 'http://192.168.1.113:8555/agent').rstrip('/')
device = urllib.parse.urlparse(agent).hostname
route = '/' + secrets.token_urlsafe(32)
dex = (ROOT / '.local/assist/dex/classes.dex').read_bytes()
lock = threading.Lock()
state = {'busy': False, 'cancel': False, 'events': [], 'result': None}
artifact = ROOT / 'artifacts/assist-button-20260915'
artifact.mkdir(exist_ok=True)

HTML = '''<!doctype html><html lang="pl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Helios — test głosu</title><style>
body{font:20px system-ui;max-width:720px;margin:50px auto;padding:24px;background:#161819;color:#eee}
h1{font-size:36px}p{line-height:1.5}button{font:inherit;padding:16px 22px;margin:8px 8px 8px 0;cursor:pointer}
#phase{font-size:28px;margin:32px 0}pre{font-size:14px;white-space:pre-wrap;color:#bcc5c6}
</style><h1>Helios · test głosu</h1><p>Mikrofon: <b>zegar Lenovo</b>. Rozpoznawanie i odpowiedź: <b>Home Assistant Cloud</b>.</p>
<p>Naciśnij przycisk. Poczekaj na „Mów teraz”, a potem powiedz do zegara: <b>„Która jest godzina?”</b></p>
<button id="start">Rozpocznij test</button><button id="cancel" disabled>Anuluj</button>
<div id="phase" role="status" aria-live="polite">Gotowy · mikrofon wyłączony</div><pre id="events"></pre>
<script>
const phase=document.querySelector('#phase'),start=document.querySelector('#start'),cancel=document.querySelector('#cancel');
async function action(path){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'});if(!r.ok)phase.textContent='Nie udało się uruchomić testu';}
start.onclick=()=>action('/start');cancel.onclick=()=>action('/cancel');
const labels={test_start:'Łączenie z HA…',microphone_started:'Mów teraz — do zegara',capture_progress:'Słucham…',microphone_released:'Czekam na odpowiedź…',stt_end:'Rozpoznano mowę',playback_started:'Zegar odpowiada…',playback_completed:'Odpowiedź zakończona',test_completed:'Test zakończony',ready:'Gotowy · mikrofon wyłączony'};
async function refresh(){try{const s=await(await fetch('/status')).json();start.disabled=s.busy;cancel.disabled=!s.busy;
const meaningful=s.events.filter(e=>labels[e.event]||e.event==='test_error');const last=meaningful.at(-1);
if(last)phase.textContent=last.event==='test_error'?'Błąd: '+last.detail:labels[last.event];
if(s.result&&s.result.startsWith('ERROR'))phase.textContent=s.result;
document.querySelector('#events').textContent=s.events.filter(e=>['transcript','intent_response','test_error','test_completed'].includes(e.event)).map(e=>e.event+': '+e.detail).join('\\n');
}catch(e){phase.textContent='Brak połączenia z konsolą';}setTimeout(refresh,400);}refresh();
</script></html>'''

class Base(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def respond(self, data, status=200, content_type='application/json'):
        payload=data if isinstance(data,bytes) else json.dumps(data,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(payload)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(payload)
    def body(self):
        size=int(self.headers.get('Content-Length','0'))
        if size>65536: raise ValueError('Body too large')
        return json.loads(self.rfile.read(size)) if size else {}

class Clock(Base):
    def do_GET(self):
        if self.client_address[0]!=device:return self.respond({},403)
        if self.path==route+'/config':self.respond(config)
        elif self.path==route+'/classes.dex':self.respond(dex,content_type='application/octet-stream')
        else:self.respond({},404)
    def do_POST(self):
        if self.client_address[0]!=device or self.path!=route+'/events':return self.respond({},403)
        row=self.body()
        with lock:
            state['events'].append(row)
            cancel=state['cancel']
        self.respond({'cancel':cancel})

def run():
    try:
        params=dict(token=token,url=asset_base+'/classes.dex',entry='AssistButtonProbe',arg=asset_base+'/config')
        text=urllib.request.urlopen(agent+'/dex?'+urllib.parse.urlencode(params),timeout=150).read().decode()
        with lock: state['result']=text if text.startswith('VOICE_TEST_FINISHED') else 'ERROR: urządzenie odrzuciło test'
    except Exception as error:
        with lock: state['result']='ERROR: '+type(error).__name__
    finally:
        with lock:
            state['busy']=False
            record=dict(state)
        path=artifact/('console-'+str(time.time_ns())+'.json')
        path.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
        print('Test finished; evidence:',path,flush=True)

class Console(Base):
    def do_GET(self):
        if self.path=='/':self.respond(HTML.encode(),content_type='text/html; charset=utf-8')
        elif self.path=='/status':
            with lock:snapshot=dict(state)
            self.respond(snapshot)
        else:self.respond({},404)
    def do_POST(self):
        if self.headers.get('Origin')!='http://127.0.0.1:8756' or self.headers.get('Content-Type')!='application/json':return self.respond({},403)
        if self.path=='/start':
            with lock:
                if state['busy']:return self.respond({},409)
                state.update(busy=True,cancel=False,events=[],result=None)
            threading.Thread(target=run,daemon=True).start();self.respond({'started':True})
        elif self.path=='/cancel':
            with lock:state['cancel']=True
            self.respond({'cancelled':True})
        else:self.respond({},404)

with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
    sock.connect((device,8555));host=sock.getsockname()[0]
lan=http.server.ThreadingHTTPServer((host,0),Clock)
asset_base=f'http://{host}:{lan.server_port}'+route
config['control_url']=asset_base+'/events'
threading.Thread(target=lan.serve_forever,daemon=True).start()
console=http.server.ThreadingHTTPServer(('127.0.0.1',8756),Console)
print('Helios console ready: http://127.0.0.1:8756',flush=True)
try:console.serve_forever()
finally:console.server_close();lan.shutdown();lan.server_close()
