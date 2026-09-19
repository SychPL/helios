"""Publish the SPEC 0.9 tiles (schema 4) to the helios-clock dashboard; never actuates covers or lights.

  python tools/deploy_bedroom_dashboard.py            # preflight only
  python tools/deploy_bedroom_dashboard.py --apply    # backup, transform, save, read back
  python tools/deploy_bedroom_dashboard.py --rollback .local/bedroom-dashboard-backup-<stamp>.json

Order of operations (SPEC 0.9 pkt 8): package YAML installed and HA restarted, the three helpers live, APK >= 0.8.8 on the clock,
then --apply. Rollback restores only the `helios` section and only when the live section still equals what this tool published.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from deploy_attention_dashboard import HomeAssistant, ROOT, manifest_path  # noqa: E402

MANIFEST = manifest_path('helios-bedroom.yaml')
LIVE = ROOT / '.local/bedroom-dashboard-live.json'
DASHBOARD = 'helios-clock'
ALLOWED_TYPES = {'clock', 'weather', 'entity', 'light', 'cover', 'garage', 'music', 'cover_group'}


def version_tuple(text):
    return tuple(int(part) for part in str(text).split('-')[0].split('.'))


def validate_tiles(items):
    """Mirror of DashboardSpec for schema 4: geometry, ids, cover_group shape, forecast pair."""
    if not 1 <= len(items) <= 12:
        raise ValueError('Invalid tile count')
    occupied, identifiers = set(), set()
    for item in items:
        if item['type'] not in ALLOWED_TYPES:
            raise ValueError('Unknown tile type: ' + item['type'])
        if item['id'] in identifiers:
            raise ValueError('Duplicate tile: ' + item['id'])
        identifiers.add(item['id'])
        if any(type(item[key]) is not int or item[key] < 1 for key in ('column', 'row', 'width', 'height')):
            raise ValueError('Invalid tile geometry: ' + item['id'])
        for column in range(item['column'], item['column'] + item['width']):
            for row in range(item['row'], item['row'] + item['height']):
                if not 1 <= column <= 4 or not 1 <= row <= 3 or (column, row) in occupied:
                    raise ValueError('Invalid tile geometry: ' + item['id'])
                occupied.add((column, row))
        if item['type'] == 'cover_group':
            covers = item.get('covers')
            if not isinstance(covers, list) or len(covers) != 2 or len({c['entity'] for c in covers}) != 2 \
                    or any(set(c) != {'entity', 'title'} or not c['entity'].startswith('cover.') for c in covers):
                raise ValueError('cover_group needs exactly two distinct cover entries with titles')
        if item['type'] == 'weather' and (('forecast_entity' in item) != ('forecast_when' in item)):
            raise ValueError('weather: forecast_entity and forecast_when go together')
        if 'off_entity' in item and (item['type'] != 'entity' or not item['off_entity'].startswith('light.')):
            raise ValueError('off_entity belongs on an entity tile and names a light: ' + item['id'])


def cells(item):
    return {(c, r) for c in range(item['column'], item['column'] + item['width']) for r in range(item['row'], item['row'] + item['height'])}


def transform(before, manifest):
    """Schema 5 document: manifest tiles replace whatever sits in their cells; everything else (clock, notifications, views) stays."""
    desired = deepcopy(before)
    helios = desired.get('helios') or {}
    items = [deepcopy(i) for i in helios.get('items', [])]
    taken = set()
    for tile in manifest['tiles']:
        taken |= cells(tile)
    kept = [i for i in items if not (cells(i) & taken) and i['id'] not in {t['id'] for t in manifest['tiles']}]
    new_items = kept + deepcopy(manifest['tiles'])
    new_items.sort(key=lambda i: (i['row'], i['column']))
    validate_tiles(new_items)
    desired['helios'] = {'version': 5, 'grid': {'columns': 4, 'rows': 3}, 'items': new_items}
    return desired


def preflight(client, manifest):
    states = {item['entity_id']: item for item in client.call('get_states')}
    sources = manifest['sources']
    required = sources['covers'] + [sources['light'], sources['weather'], sources['sun']] + list(manifest['helpers'].values())
    for entity in required:
        if entity not in states:
            raise RuntimeError('Missing entity: ' + entity)
        if states[entity]['state'] in ('unknown', 'unavailable'):
            raise RuntimeError('Entity not ready: ' + entity + ' = ' + states[entity]['state'])
    for entity in sources['covers']:
        features = states[entity]['attributes'].get('supported_features', 0)
        if features & 1 == 0 or features & 2 == 0 or features & 8 == 0:
            raise RuntimeError('Cover without open/close/stop support: ' + entity)
    members = states[sources['light']]['attributes'].get('entity_id')
    if not isinstance(members, list) or set(members) != set(sources['light_members']):
        raise RuntimeError('Light group members changed: ' + json.dumps(members))
    for member in sources['light_members']:
        if member not in states:
            raise RuntimeError('Missing light group member: ' + member)
    registry = client.call('config/entity_registry/list')
    light_entry = next((e for e in registry if e['entity_id'] == sources['light']), None)
    if light_entry is None or not light_entry.get('config_entry_id'):
        raise RuntimeError('Light group has no config entry (YAML group?): ' + sources['light'])
    flow = client.http('/api/config/config_entries/options/flow', {'handler': light_entry['config_entry_id']})
    try:
        if flow.get('type') != 'form':
            raise RuntimeError('Unexpected group options flow: ' + json.dumps(flow)[:200])
        field = next((f for f in flow['data_schema'] if f.get('name') == 'all'), None)
        all_mode = (field or {}).get('description', {}).get('suggested_value', (field or {}).get('default', False))
        if all_mode:
            raise RuntimeError('Light group must use "any member on" mode (all: false)')
    finally:
        try:
            client.http_delete('/api/config/config_entries/options/flow/' + flow['flow_id'])
        except Exception:
            pass
    for helper in manifest['helpers'].values():
        entry = next((e for e in registry if e['entity_id'] == helper), None)
        if entry is None or entry.get('platform') != 'template' or not str(entry.get('unique_id', '')).startswith('helios_'):
            raise RuntimeError('Helper is not ours (platform/unique_id): ' + helper)
    versions = [e for e in registry if e.get('platform') == 'helios' and str(e.get('unique_id', '')).endswith('_app_version')]
    if not versions:
        raise RuntimeError('No Helios app version entity in the registry: pair the clock first')
    app_version = states.get(versions[0]['entity_id'], {}).get('state')
    if app_version in (None, 'unknown', 'unavailable'):
        raise RuntimeError('Clock app version unavailable: ' + versions[0]['entity_id'])
    if version_tuple(app_version) < version_tuple(manifest['min_app_version']):
        raise RuntimeError('Clock runs ' + app_version + ', schema 5 needs ' + manifest['min_app_version'])
    weather_features = states[sources['weather']]['attributes'].get('supported_features', 0)
    if weather_features & 1 == 0:
        raise RuntimeError('Weather entity has no daily forecast support')
    return app_version


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--rollback', metavar='BACKUP_JSON')
    args = parser.parse_args()
    manifest = yaml.safe_load(MANIFEST.read_text(encoding='utf-8'))
    validate_tiles(manifest['tiles'])
    client = HomeAssistant()
    if not hasattr(client, 'http_delete'):
        def http_delete(path):
            import urllib.request
            request = urllib.request.Request(client.base + path, headers=client.headers, method='DELETE')
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status
        client.http_delete = http_delete
    try:
        if args.rollback:
            backup = json.loads(Path(args.rollback).read_text(encoding='utf-8'))
            published = json.loads(LIVE.read_text(encoding='utf-8'))
            current = client.call('lovelace/config', url_path=DASHBOARD)
            if current.get('helios') != published.get('helios'):
                raise RuntimeError('helios section changed after publication; roll back by hand')
            desired = deepcopy(current)
            desired['helios'] = backup['helios']
            if client.call('lovelace/config', url_path=DASHBOARD) != current:
                raise RuntimeError('Concurrent dashboard edit; refusing overwrite')
            client.call('lovelace/config/save', url_path=DASHBOARD, config=desired)
            actual = client.call('lovelace/config', url_path=DASHBOARD)
            if actual.get('helios') != backup['helios']:
                raise RuntimeError('Rollback readback mismatch')
            print('Rolled back the helios section to', args.rollback)
            return
        before = client.call('lovelace/config', url_path=DASHBOARD)
        if before.get('helios', {}).get('version') not in (2, 3):
            raise RuntimeError('Live dashboard is not schema 2/3; review before deployment')
        app_version = preflight(client, manifest)
        desired = transform(before, manifest)
        print('Preflight OK: helpers live, sources available, light group intact, clock app', app_version)
        print('Resulting items:', ', '.join(i['id'] + '@' + str(i['column']) + ',' + str(i['row']) for i in desired['helios']['items']))
        if not args.apply:
            return
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        backup = ROOT / '.local' / ('bedroom-dashboard-backup-' + stamp + '.json')
        backup.write_text(json.dumps(before, ensure_ascii=False, indent=2), encoding='utf-8')
        if client.call('lovelace/config', url_path=DASHBOARD) != before:
            raise RuntimeError('Concurrent dashboard edit; refusing overwrite')
        client.call('lovelace/config/save', url_path=DASHBOARD, config=desired)
        actual = client.call('lovelace/config', url_path=DASHBOARD)
        if actual != desired:
            raise RuntimeError('Dashboard readback mismatch; inspect ' + str(backup) + ' before rollback')
        LIVE.write_text(json.dumps(actual, indent=2, ensure_ascii=False), encoding='utf-8')
        print('Published schema 4; backup at', backup)
    finally:
        client.socket.close()


if __name__ == '__main__':
    main()
