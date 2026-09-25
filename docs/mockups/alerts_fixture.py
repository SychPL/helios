"""Fake HA for the emulator smoke test of the "Uwagi" tile (SPEC 0.20): a schema-6 page with the tile in all three sizes
(2x1 with an energy stand-in card, 1x1, 1x2), warnings with last_changed, light.turn_off that clears the lights warning
1 s after it answers, and a control port: GET http://127.0.0.1:8776/set?e=<entity>&s=<state> pushes a state change."""
import asyncio, json, sys, time
from aiohttp import web

NOW = time.time()
SOURCES = [
    {'title': 'Garaż otwarty', 'entity': 'sensor.helios_garaz_uwaga', 'icon': 'mdi:garage-open', 'when': {'entity': 'binary_sensor.helios_garaz_uwaga_pokaz', 'state': 'on'}},
    {'title': 'Śmieci jutro', 'entity': 'sensor.helios_smieci_jutro', 'icon': 'mdi:trash-can', 'when': {'entity': 'binary_sensor.helios_smieci_jutro_pokaz', 'state': 'on'}},
    {'title': 'Światła', 'entity': 'sensor.helios_zapalone_swiatla', 'icon': 'mdi:lightbulb', 'when': {'entity': 'binary_sensor.helios_zapalone_swiatla_pokaz', 'state': 'on'},
     'off_entity': 'light.helios_swiatla_do_sprawdzenia'},
    {'title': 'Blaszak otwarty', 'entity': 'sensor.helios_blaszak_uwaga', 'icon': 'mdi:garage-open', 'when': {'entity': 'binary_sensor.helios_blaszak_uwaga_pokaz', 'state': 'on'}},
    {'title': 'Wiking był', 'entity': 'sensor.helios_wiking_godzina', 'show_since': False, 'when': {'entity': 'binary_sensor.helios_wiking_godzina_pokaz', 'state': 'on'}},
]
ITEMS = [
    {'id': 'clock', 'type': 'clock', 'column': 1, 'row': 1, 'width': 2, 'height': 2, 'title': 'Dom'},
    {'id': 'uwagi', 'type': 'alerts', 'column': 1, 'row': 3, 'width': 2, 'height': 1, 'title': 'Uwagi', 'sources': SOURCES,
     'empty': {'type': 'energy', 'entity': 'sensor.pv', 'load_entity': 'sensor.dom', 'battery_entity': 'sensor.bateria'}},
    {'id': 'uwagi-maly', 'type': 'alerts', 'column': 3, 'row': 3, 'width': 1, 'height': 1, 'sources': SOURCES},
    {'id': 'uwagi-wysoki', 'type': 'alerts', 'column': 4, 'row': 2, 'width': 1, 'height': 2, 'sources': SOURCES},
]
SPEC = {'version': 6, 'pages': [{'id': 'main', 'title': 'Dom', 'items': ITEMS}]}
W = {'unit_of_measurement': 'W'}
STATES = {
    'binary_sensor.helios_garaz_uwaga_pokaz': {'s': 'on', 'a': {}, 'lc': NOW - 2 * 3600},
    'sensor.helios_garaz_uwaga': {'s': 'Brama garażowa otwarta', 'a': {}, 'lc': NOW - 2 * 3600},
    'binary_sensor.helios_smieci_jutro_pokaz': {'s': 'on', 'a': {}, 'lc': NOW - 20 * 60},
    'sensor.helios_smieci_jutro': {'s': 'Jutro: bio, zmieszane', 'a': {}, 'lc': NOW - 20 * 60},
    'binary_sensor.helios_zapalone_swiatla_pokaz': {'s': 'on', 'a': {}, 'lc': NOW - 26 * 3600},
    'sensor.helios_zapalone_swiatla': {'s': '3 - kuchnia, salon, wiatrołap', 'a': {}, 'lc': NOW - 26 * 3600},
    'light.helios_swiatla_do_sprawdzenia': {'s': 'on', 'a': {}, 'lc': NOW - 26 * 3600},
    'binary_sensor.helios_blaszak_uwaga_pokaz': {'s': 'unavailable', 'a': {}, 'lc': NOW - 600},
    'sensor.helios_blaszak_uwaga': {'s': 'unavailable', 'a': {}, 'lc': NOW - 600},
    'binary_sensor.helios_wiking_godzina_pokaz': {'s': 'off', 'a': {}, 'lc': NOW - 600},
    'sensor.helios_wiking_godzina': {'s': '12:40', 'a': {}, 'lc': NOW - 600},
    'sensor.pv': {'s': '1824', 'a': W, 'lc': NOW}, 'sensor.dom': {'s': '831', 'a': W, 'lc': NOW},
    'sensor.bateria': {'s': '70', 'a': {'unit_of_measurement': '%'}, 'lc': NOW},
}
SUBS = []


def log(*a):
    print(*a, flush=True)


async def push(entity, state):
    if entity not in STATES:
        STATES[entity] = {'s': state, 'a': {}, 'lc': time.time()}
        event = {'a': {entity: STATES[entity]}}
    else:
        STATES[entity]['s'] = state
        STATES[entity]['lc'] = time.time()
        event = {'c': {entity: {'+': {'s': state, 'lc': STATES[entity]['lc']}}}}
    for ws, sub in list(SUBS):
        try:
            await ws.send_json({'id': sub, 'type': 'event', 'event': event})
        except Exception as e:
            log('push failed', e)
    log('pushed', entity, state)


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
        if kind == 'call_service' and m.get('domain') == 'light':
            entity = m['target']['entity_id']

            async def answer(mid=mid, entity=entity):
                await asyncio.sleep(1.5)  # long enough to see the spinner
                if '--reject' in sys.argv:
                    await ws.send_json({'id': mid, 'type': 'result', 'success': False, 'error': {'code': 'home_assistant_error', 'message': 'Urządzenie nie odpowiada'}})
                    return
                await ws.send_json({'id': mid, 'type': 'result', 'success': True})
                await asyncio.sleep(1.0)
                await push(entity, 'off')
                await push('binary_sensor.helios_zapalone_swiatla_pokaz', 'off')
            asyncio.ensure_future(answer())
            continue
        await ws.send_json(ok)
        if kind == 'subscribe_entities':
            SUBS.append((ws, mid))
            await ws.send_json({'id': mid, 'type': 'event', 'event': {'a': {e: STATES[e] for e in m['entity_ids'] if e in STATES}}})
    SUBS[:] = [s for s in SUBS if s[0] is not ws]
    return ws


async def control(request):
    await push(request.query['e'], request.query['s'])
    return web.Response(text='ok')


async def main():
    app = web.Application()
    app.router.add_get('/api/websocket', ws_handler)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, '0.0.0.0', 8775).start()
    ctl = web.Application()
    ctl.router.add_get('/set', control)
    cr = web.AppRunner(ctl)
    await cr.setup()
    await web.TCPSite(cr, '127.0.0.1', 8776).start()
    log('alerts fixture on 8775, control on 8776')
    await asyncio.Event().wait()

asyncio.run(main())
