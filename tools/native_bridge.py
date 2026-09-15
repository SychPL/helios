"""Temporary clock-only APK/config delivery and controlled-test diagnostics."""
import argparse
import re
import http.server
import json
from pathlib import Path
import secrets
import socket
import time
import urllib.parse

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--prepare',action='store_true');args=parser.parse_args()
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
    sock.connect(('192.168.1.113',8555));host=sock.getsockname()[0]
provision=ROOT/'.local/provision.json'
if args.prepare:
    provision.write_text(json.dumps({'url':f'http://{host}:8757/'+secrets.token_urlsafe(32)+'/config'},indent=2),encoding='utf-8')
    print('Private provisioning route ready; no HA token is embedded in the APK')
    raise SystemExit()
url=json.loads(provision.read_text())['url'];route=urllib.parse.urlparse(url).path.rsplit('/',1)[0]
config=json.loads((ROOT/'.local/ha.json').read_text(encoding='utf-8-sig'))
inventory=json.loads((ROOT/'artifacts/ha-preflight-auth-20260915.json').read_text(encoding='utf-8'))
config.update(pipeline=inventory['preferred_pipeline'],weather_entity='weather.forecast_dom',diagnostics_url=f'http://{host}:8757'+route+'/events')
music=ROOT/'.local/ma.json'
if music.exists():  # optional Music Assistant section (SPEC 0.6 3.3); absent file = no music, never an error
    ma=json.loads(music.read_text(encoding='utf-8-sig'))
    config['music_assistant']={'url':ma['url'],'token':ma['token'],'sendspin_url':ma.get('sendspin_url',''),'player_name':ma.get('player_name','Helios')}
artifact=ROOT/'artifacts/native-0.1';artifact.mkdir(exist_ok=True)
started=time.monotonic()
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def allowed(self):return self.client_address[0] in ('192.168.1.113',host,'127.0.0.1')
    def send(self,payload,content_type='application/octet-stream',filename=None):
        self.send_response(200);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(payload)));self.send_header('Cache-Control','no-store')
        if filename:self.send_header('Content-Disposition','attachment; filename="'+filename+'"')  # versioned name: the clock's Downloads never confuse an old APK with the new one
        self.end_headers();self.wfile.write(payload)
    def do_GET(self):
        if not self.allowed():return self.send_error(403)
        if self.path==route+'/config':
            if time.monotonic()-started>1800:return self.send_error(410)
            self.send(json.dumps(config).encode(),'application/json');print('Helios configuration delivered',flush=True)
        elif self.path in (route+'/helios.apk', '/helios.apk'):
            version=re.search(r"versionName\s+'([^']+)'",(ROOT/'app/build.gradle').read_text(encoding='utf-8')).group(1)
            self.send((ROOT/'app/build/outputs/apk/debug/app-debug.apk').read_bytes(),'application/vnd.android.package-archive','helios-'+version+'.apk');print('APK '+version+' delivered',flush=True)
        elif self.path==route+'/install.dex':self.send((ROOT/'.local/helios-installer/classes.dex').read_bytes())
        else:self.send_error(404)
    def do_POST(self):
        if not self.allowed() or self.path!=route+'/events':return self.send_error(403)
        length=int(self.headers.get('Content-Length','0'))
        if length>65536:return self.send_error(413)
        row=json.loads(self.rfile.read(length))
        with (artifact/'device-events.jsonl').open('a',encoding='utf-8') as out:out.write(json.dumps(row,ensure_ascii=False)+'\n')
        self.send(b'{}','application/json')
        print('Device event:',row.get('event'),flush=True)
server=http.server.ThreadingHTTPServer(('0.0.0.0',8757),Handler)
print('Native APK/config bridge ready on port 8757; config expires in 30 minutes',flush=True)
try:server.serve_forever()
finally:server.server_close()
