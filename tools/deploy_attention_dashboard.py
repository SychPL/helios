"""Deploy read-only attention tiles and persistent HA helpers; never actuate hardware."""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request

import websocket
import yaml

ROOT = Path(__file__).resolve().parents[1]


class HomeAssistant:
    def __init__(self):
        config = json.loads((ROOT / '.local/ha.json').read_text(encoding='utf-8-sig'))
        self.base = config['url'].rstrip('/')
        self.headers = {'Authorization': 'Bearer ' + config['token'], 'Content-Type': 'application/json'}
        parsed = urllib.parse.urlparse(self.base)
        endpoint = urllib.parse.urlunparse(parsed._replace(
            scheme='wss' if parsed.scheme == 'https' else 'ws', path='/api/websocket'))
        self.socket = websocket.create_connection(endpoint, timeout=15)
        self.socket.recv()
        self.socket.send(json.dumps({'type': 'auth', 'access_token': config['token']}))
        if json.loads(self.socket.recv()).get('type') != 'auth_ok':
            self.socket.close()
            raise RuntimeError('HA authentication failed')
        self.request_id = 0

    def call(self, kind, **fields):
        self.request_id += 1
        self.socket.send(json.dumps(dict(id=self.request_id, type=kind, **fields)))
        while True:
            reply = json.loads(self.socket.recv())
            if reply.get('id') == self.request_id and reply.get('type') == 'result':
                if not reply.get('success'):
                    raise RuntimeError('HA rejected ' + kind + ': ' + reply.get('error', {}).get('code', 'error'))
                return reply.get('result')

    def http(self, path, data=None):
        body = None if data is None else json.dumps(data).encode('utf-8')
        request = urllib.request.Request(self.base + path, data=body, headers=self.headers)
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)

    def helper(self, domain, helper_type, options, journal):
        entries = self.call('config_entries/get', domain=domain)
        matches = [entry for entry in entries if entry['title'] == options['name']]
        if len(matches) > 1:
            raise RuntimeError('Duplicate helper: ' + options['name'])
        if matches:
            entry = matches[0]
            expected = dict(options, **{'group_type' if domain == 'group' else 'template_type': helper_type})
            actual = entry.get('options', {})
            if any(actual.get(key) != value for key, value in expected.items()):
                raise RuntimeError('Existing helper differs; refusing overwrite: ' + options['name'])
            entry_id = entry['entry_id']
        else:
            flow = self.http('/api/config/config_entries/flow', {'handler': domain, 'show_advanced_options': False})
            if flow.get('type') != 'menu' or helper_type not in flow.get('menu_options', []):
                raise RuntimeError('Unexpected helper menu: ' + domain)
            flow = self.http('/api/config/config_entries/flow/' + flow['flow_id'], {'next_step_id': helper_type})
            if flow.get('type') != 'form':
                raise RuntimeError('Unexpected helper form: ' + domain)
            flow = self.http('/api/config/config_entries/flow/' + flow['flow_id'], options)
            if flow.get('type') != 'create_entry':
                raise RuntimeError('Helper creation failed: ' + json.dumps(flow.get('errors', {})))
            entry_id = flow['result']['entry_id']
            journal['created_entries'].append({'entry_id': entry_id, 'domain': domain, 'name': options['name']})
            save_journal(journal)
        for attempt in range(20):
            registry = self.call('config/entity_registry/list')
            entities = [entry['entity_id'] for entry in registry if entry.get('config_entry_id') == entry_id]
            if len(entities) == 1:
                return entities[0]
            time.sleep(.25)
        raise RuntimeError('Helper entity did not appear: ' + options['name'])


def save_journal(journal):
    Path(journal['journal_path']).write_text(json.dumps(journal, indent=2, ensure_ascii=False), encoding='utf-8')


def validate_tiles(items):
    occupied = set()
    identifiers = set()
    for item in items:
        if item['type'] not in {'clock', 'weather', 'entity'}:
            raise ValueError('This rollout permits read-only tiles only')
        if item['id'] in identifiers:
            raise ValueError('Duplicate tile')
        identifiers.add(item['id'])
        for column in range(item['column'], item['column'] + item['width']):
            for row in range(item['row'], item['row'] + item['height']):
                if not 1 <= column <= 4 or not 1 <= row <= 3 or (column, row) in occupied:
                    raise ValueError('Invalid tile geometry')
                occupied.add((column, row))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    manifest = yaml.safe_load((ROOT / 'ha/helios-attention.yaml').read_text(encoding='utf-8'))
    validate_tiles(manifest['tiles'])
    client = HomeAssistant()
    try:
        before = client.call('lovelace/config', url_path='helios-clock')
        if before.get('helios', {}).get('version') != 2:
            raise RuntimeError('Live dashboard has changed schema; review before deployment')
        states = {item['entity_id']: item for item in client.call('get_states')}
        missing = [entity for entity in manifest['group']['entities'] if entity not in states]
        if missing:
            raise RuntimeError('Missing group members: ' + ', '.join(missing))
        print('Preflight: compatible dashboard, all selected light entities exist; no light commands sent.')
        if not args.apply:
            return
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = ROOT / '.local' / ('attention-dashboard-backup-' + stamp + '.json')
        backup.write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
        journal = {'backup': str(backup), 'journal_path': str(ROOT / '.local' / ('attention-deployment-' + stamp + '.json')),
                   'created_entries': [], 'entities': {}, 'dashboard_saved': False}
        save_journal(journal)
        group = client.helper('group', 'light', dict(manifest['group'], hide_members=False, all=False), journal)
        journal['entities']['group'] = group
        items = deepcopy(manifest['tiles'])
        for key, helper in manifest['helpers'].items():
            template = helper['state'].replace('__LIGHT_GROUP__', group).replace(
                '__LIGHT_LABELS__', json.dumps(manifest['light_labels'], ensure_ascii=False))
            entity = client.helper('template', 'sensor', {'name': helper['name'], 'state': template}, journal)
            visible = client.helper('template', 'binary_sensor', {
                'name': helper['name'] + ' - pokaz',
                'state': "{{ has_value('" + entity + "') and (states('" + entity + "') | trim | length > 0) }}"
            }, journal)
            journal['entities'][key] = entity
            journal['entities'][key + '_visible'] = visible
            save_journal(journal)
            for item in items:
                if item.get('helper') == key:
                    del item['helper']
                    item.update(entity=entity, visible_when={'entity': visible, 'state': 'on'})
        required = set(journal['entities'].values())
        for attempt in range(30):
            states = {item['entity_id']: item for item in client.call('get_states')}
            if required <= states.keys() and all(states[entity]['state'] not in {'unknown', 'unavailable'} for entity in required):
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Helpers are not ready; original dashboard retained')
        validate_tiles(items)
        desired = deepcopy(before)
        desired['helios'] = {'version': 2, 'grid': {'columns': 4, 'rows': 3}, 'items': items}
        if client.call('lovelace/config', url_path='helios-clock') != before:
            raise RuntimeError('Concurrent dashboard edit; refusing overwrite')
        client.call('lovelace/config/save', url_path='helios-clock', config=desired)
        journal['dashboard_saved'] = True
        save_journal(journal)
        actual = client.call('lovelace/config', url_path='helios-clock')
        if actual != desired:
            raise RuntimeError('Dashboard readback mismatch; inspect deployment journal before rollback')
        (ROOT / '.local' / 'attention-dashboard-live.json').write_text(json.dumps(actual, indent=2, ensure_ascii=False), encoding='utf-8')
        print(json.dumps({'verified': True, 'entities': journal['entities'],
                          'states': {key: states[entity]['state'] for key, entity in journal['entities'].items()},
                          'backup': str(backup)}, ensure_ascii=True, indent=2))
    finally:
        client.socket.close()


if __name__ == '__main__':
    main()
