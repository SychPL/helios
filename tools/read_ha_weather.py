"""Read weather entities only; no actions or configuration changes."""
import json
from pathlib import Path
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
config=json.loads((ROOT/'.local/ha.json').read_text(encoding='utf-8-sig'))
request=urllib.request.Request(config['url'].rstrip('/')+'/api/states',headers={'Authorization':'Bearer '+config['token']})
states=json.load(urllib.request.urlopen(request,timeout=10))
weather=[{'entity_id':s['entity_id'],'state':s['state'],'attributes':{k:v for k,v in s.get('attributes',{}).items() if k in ('friendly_name','temperature','temperature_unit','humidity','wind_speed','wind_speed_unit','supported_features')}} for s in states if s['entity_id'].startswith('weather.')]
output=json.dumps(weather,indent=2,ensure_ascii=False)
(ROOT/'artifacts/ha-weather-20260915.json').write_text(output,encoding='utf-8')
print(output)
