"""Fake HA for the emulator smoke test of the climate card (SPEC 0.19): schema-6 dashboard with three thermostats,
states copied from the owner's HA on 2026-09-24, climate.* services that answer after 0.5 s and update the state 1 s later."""
import asyncio, json, sys
from aiohttp import web

ITEMS = [
    {'id': 'clock', 'type': 'clock', 'column': 1, 'row': 1, 'width': 2, 'height': 2, 'title': 'Dom'},
    {'id': 'salon', 'type': 'climate', 'entity': 'climate.salon_termostat', 'column': 3, 'row': 1, 'width': 1, 'height': 1, 'title': 'Salon'},
    {'id': 'gree', 'type': 'climate', 'entity': 'climate.gree', 'column': 4, 'row': 1, 'width': 1, 'height': 1},
    {'id': 'biuro', 'type': 'climate', 'entity': 'climate.biuro_termostat', 'column': 3, 'row': 2, 'width': 1, 'height': 1, 'title': 'Biuro'},
    {'id': 'lazienka', 'type': 'climate', 'entity': 'climate.lazienka', 'column': 4, 'row': 2, 'width': 1, 'height': 1, 'title': 'Łazienka'},
]
SPEC = {'version': 6, 'pages': [{'id': 'main', 'title': 'Dom', 'items': ITEMS}]}
STATES = {
    'climate.salon_termostat': {'s': 'heat', 'a': {'hvac_modes': ['heat', 'off'], 'min_temp': 14, 'max_temp': 25, 'preset_modes': ['none', 'away'],
        'current_temperature': 23, 'temperature': 20.5, 'hvac_action': 'heating', 'preset_mode': 'none', 'friendly_name': 'Salon - Termostat', 'supported_features': 401}},
    'climate.gree': {'s': 'cool', 'a': {'hvac_modes': ['auto', 'cool', 'dry', 'fan_only', 'heat', 'off'], 'min_temp': 8, 'max_temp': 30, 'target_temp_step': 1,
        'fan_modes': ['auto', 'low', 'medium low', 'medium', 'medium high', 'high'], 'preset_modes': ['eco', 'away', 'boost', 'none', 'sleep'],
        'swing_modes': ['default', 'full_swing', 'fixed_upper', 'fixed_upper_middle', 'fixed_middle', 'fixed_lower_middle', 'fixed_lower', 'swing_upper', 'swing_upper_middle', 'swing_middle', 'swing_lower_middle', 'swing_lower'],
        'swing_horizontal_modes': ['default', 'full_swing', 'left', 'left_center', 'center', 'right_center', 'right'],
        'current_temperature': 22, 'temperature': 24, 'hvac_action': 'cooling', 'fan_mode': 'low', 'preset_mode': 'none', 'swing_mode': 'full_swing',
        'swing_horizontal_mode': 'default', 'friendly_name': 'gree', 'supported_features': 953}},
    'climate.biuro_termostat': {'s': 'heat', 'a': {'hvac_modes': ['heat', 'off'], 'min_temp': 14, 'max_temp': 25, 'preset_modes': ['none', 'away'],
        'current_temperature': None, 'temperature': 21, 'hvac_action': 'idle', 'preset_mode': 'none', 'friendly_name': 'Biuro - Termostat', 'supported_features': 401}},
    'climate.lazienka': {'s': 'unavailable', 'a': {'friendly_name': 'Łazienka'}},
}
KEYS = {'set_temperature': 'temperature', 'set_hvac_mode': None, 'set_preset_mode': 'preset_mode', 'set_fan_mode': 'fan_mode',
        'set_swing_mode': 'swing_mode', 'set_swing_horizontal_mode': 'swing_horizontal_mode'}
SUBS = []
REJECT = {'on': '--reject' in sys.argv}  # every climate call fails: the error path


def log(*a):
    print(*a, flush=True)


async def push(entity, state=None, attrs=None):
    plus = {}
    if state is not None:
        STATES[entity]['s'] = state
        plus['s'] = state
    if attrs:
        STATES[entity]['a'].update(attrs)
        plus['a'] = attrs
    for ws, sub in list(SUBS):
        try:
            await ws.send_json({'id': sub, 'type': 'event', 'event': {'c': {entity: {'+': plus}}}})
        except Exception as e:
            log('push failed', e)


async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=None)
    await ws.prepare(request)
    await ws.send_json({'type': 'auth_required'})
    async for msg in ws:
        if msg.type != web.WSMsgType.TEXT:
            break
        m = json.loads(msg.data)
        kind, mid = m['type'], m.get('id')
        if kind == 'auth':
            await ws.send_json({'type': 'auth_ok'})
            continue
        log('<-', kind, json.dumps({k: v for k, v in m.items() if k not in ('type', 'id')}, ensure_ascii=False)[:200])
        ok = {'id': mid, 'type': 'result', 'success': True}
        if kind == 'helios/connect':
            await ws.send_json(ok)
            await ws.send_json({'id': mid, 'type': 'event', 'event': {'type': 'connected', 'device_id': 'dev-emu', 'area_id': 'biuro', 'name': 'Zegar emu'}})
            await ws.send_json({'id': mid, 'type': 'event', 'event': {'type': 'connection', 'pipeline': 'pipe-emu', 'dashboard_path': 'helios-clock', 'music_assistant': None, 'diagnostics_url': None}})
            continue
        if kind == 'lovelace/config':
            ok['result'] = {'helios': SPEC}
        if kind == 'get_config':
            ok['result'] = {'unit_system': {'temperature': '°C'}}
        if kind == 'call_service' and m.get('domain') == 'climate':
            entity, service, data = m['target']['entity_id'], m['service'], m.get('service_data', {})

            async def answer(mid=mid, entity=entity, service=service, data=data):
                await asyncio.sleep(0.5)
                if REJECT['on']:
                    await ws.send_json({'id': mid, 'type': 'result', 'success': False, 'error': {'code': 'home_assistant_error', 'message': 'Urządzenie nie odpowiada'}})
                    return
                await ws.send_json({'id': mid, 'type': 'result', 'success': True})
                await asyncio.sleep(1.0)
                if service == 'set_hvac_mode':
                    mode = data['hvac_mode']
                    await push(entity, mode, {'hvac_action': 'off' if mode == 'off' else 'idle'})
                else:
                    await push(entity, None, {KEYS[service]: data[KEYS[service]]})
            asyncio.ensure_future(answer())
            continue
        await ws.send_json(ok)
        if kind == 'subscribe_entities':
            SUBS.append((ws, mid))
            await ws.send_json({'id': mid, 'type': 'event', 'event': {'a': {e: STATES[e] for e in m['entity_ids'] if e in STATES}}})
    SUBS[:] = [s for s in SUBS if s[0] is not ws]
    return ws


async def main():
    app = web.Application()
    app.router.add_get('/api/websocket', ws_handler)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, '0.0.0.0', 8765).start()
    log('climate fixture on 8765', 'REJECT' if REJECT['on'] else '')
    await asyncio.Event().wait()

asyncio.run(main())
