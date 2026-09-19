"""Serve one local DEX briefly and invoke it through the existing agent."""
import argparse,http.server,json,os,re,socket,threading,urllib.parse,urllib.request
from pathlib import Path


def _clock_ip():
    """the clock's address (set CLOCK_IP, or put {"clock_ip": "..."} in .local/bridge.json)"""
    import os as _os, json as _json
    from pathlib import Path as _Path
    value = _os.environ.get('CLOCK_IP')
    if not value:
        config = _Path(__file__).resolve().parents[1] / '.local' / 'bridge.json'
        if config.is_file():
            value = _json.loads(config.read_text(encoding='utf-8')).get('clock_ip')
    if not value:
        raise SystemExit('Set CLOCK_IP to %s' % "the clock's address, e.g. CLOCK_IP=10.0.0.5")
    return value


CLOCK_IP = _clock_ip()
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('dex',type=Path);parser.add_argument('entry');parser.add_argument('--arg',default='');parser.add_argument('--output',type=Path);args=parser.parse_args()
token=os.environ.get('CLOCK_AGENT_TOKEN') or re.findall(r'TOKEN="([A-Za-z0-9]+)"',(ROOT.parent/'docs/SSH-HOWTO.md').read_text(encoding='utf-8'))[0]
payload=args.dex.read_bytes()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path!='/plugin.dex' or self.client_address[0]!=CLOCK_IP:self.send_error(403);return
        self.send_response(200);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
    def log_message(self,*a):pass
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:sock.connect((CLOCK_IP,8555));host=sock.getsockname()[0]
server=http.server.ThreadingHTTPServer((host,0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
try:
    params=dict(token=token,url=f'http://{host}:{server.server_port}/plugin.dex',entry=args.entry,arg=args.arg)
    result=urllib.request.urlopen('http://'+CLOCK_IP+':8555/agent/dex?'+urllib.parse.urlencode(params),timeout=45).read().decode().replace(token,'[REDACTED]')
    if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(result,encoding='utf-8')
    print(result)
finally:server.shutdown();server.server_close()
