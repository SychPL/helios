"""Developer-loop APK delivery for the clock and the controlled-test diagnostics sink. Pairing is no longer served here (SPEC 0.10):
the clock pairs with HA directly; the diagnostics route is what goes into the integration's diagnostics_url option."""
import re
import http.server
import json
from pathlib import Path
import secrets
import socket

ROOT=Path(__file__).resolve().parents[1]
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
    sock.connect(('192.168.1.113',8555));host=sock.getsockname()[0]
bridge=ROOT/'.local/bridge.json'
if not bridge.exists():bridge.write_text(json.dumps({'route':'/'+secrets.token_urlsafe(32)},indent=2),encoding='utf-8')
route=json.loads(bridge.read_text(encoding='utf-8-sig'))['route']
artifact=ROOT/'artifacts/native-0.1';artifact.mkdir(exist_ok=True)
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def allowed(self):return self.client_address[0] in ('192.168.1.113',host,'127.0.0.1')
    def send(self,payload,content_type='application/octet-stream',filename=None):
        self.send_response(200);self.send_header('Content-Type',content_type);self.send_header('Content-Length',str(len(payload)));self.send_header('Cache-Control','no-store')
        if filename:self.send_header('Content-Disposition','attachment; filename="'+filename+'"')  # versioned name: the clock's Downloads never confuse an old APK with the new one
        self.end_headers();self.wfile.write(payload)
    def do_GET(self):
        if not self.allowed():return self.send_error(403)
        if self.path in (route+'/helios.apk', '/helios.apk'):
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
print(f'APK bridge ready on port 8757; diagnostics sink for the integration option: http://{host}:8757{route}/events',flush=True)
try:server.serve_forever()
finally:server.server_close()
