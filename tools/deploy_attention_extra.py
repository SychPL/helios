"""SPEC 0.20 pkt 6 without file access to HA: the helpers of ha/packages/helios_attention_extra.yaml created through HA's own
config API, as the UI would (storage helpers, template helpers, automations.yaml via the automation editor API).

The one change against the package: a UI template helper cannot run actions, so the calendar query lives in an automation
that stores the result in input_text.helios_smieci_rodzaje ("?" = the query failed) and the moment of the query in
input_datetime.helios_smieci_odswiezone; sensor.helios_smieci_jutro is a template over those two.

Run: python tools/deploy_attention_extra.py [--apply]   (reads .local/ha.json; without --apply prints the plan only)
Idempotent: existing helpers and template entities are left alone, automations are overwritten by id.
"""
import asyncio, json, sys, urllib.request
from pathlib import Path

import websockets
import yaml

ROOT = Path(__file__).resolve().parents[1]
CFG = json.loads((ROOT / ".local/ha.json").read_text(encoding="utf-8"))
URL = CFG.get("url", "http://192.168.1.212:8123").rstrip("/")
H = {"Authorization": "Bearer " + CFG["token"], "Content-Type": "application/json"}
PACKAGE = yaml.safe_load((ROOT / "ha/packages/helios_attention_extra.yaml").read_text(encoding="utf-8"))
GARAGE = PACKAGE["template"][0]["binary_sensor"][0]
CEL = PACKAGE["automation"][0]["actions"][1]["variables"]["cel"]
APPLY = "--apply" in sys.argv

FRESH = ("{% set ts = state_attr('input_datetime.helios_smieci_odswiezone', 'timestamp') %}"
         "{{ ts is number and now().timestamp() - ts < 65 * 60"
         " and (ts | timestamp_custom('%Y-%m-%d')) == now().strftime('%Y-%m-%d')"
         " and states('input_text.helios_smieci_rodzaje') not in ['?', 'unknown', 'unavailable'] }}")
TEMPLATES = [
    ("binary_sensor", "Helios garaz wieczor pokaz", GARAGE["state"], GARAGE["availability"]),
    ("sensor", "Helios smieci jutro",
     "{% set t = states('input_text.helios_smieci_rodzaje') %}{{ '' if t in ['', '?', 'unknown', 'unavailable'] else 'Jutro: ' ~ t }}",
     FRESH),
    ("binary_sensor", "Helios smieci jutro pokaz",
     "{{ states('sensor.helios_smieci_jutro') not in ['', 'unknown', 'unavailable']"
     " and states('input_datetime.smieci_wyniesione_dla') != (now() + timedelta(days=1)).strftime('%Y-%m-%d') }}",
     "{{ states('sensor.helios_smieci_jutro') not in ['unknown', 'unavailable']"
     " and states('input_datetime.smieci_wyniesione_dla') not in ['unknown', 'unavailable'] }}"),
]
EVENTS_TODAY_TOMORROW = {
    "action": "calendar.get_events", "target": {"entity_id": "calendar.smieci"},
    "data": {"start_date_time": "{{ now().replace(hour=0, minute=0, second=0, microsecond=0).isoformat() }}",
             "end_date_time": "{{ (now() + timedelta(days=2)).replace(hour=0, minute=0, second=0, microsecond=0).isoformat() }}"},
    "response_variable": "odpowiedz", "continue_on_error": True,
}
AUTOMATIONS = {
    "helios_smieci_odswiez": {
        "alias": "Helios - Smieci jutro z kalendarza",
        "description": "SPEC 0.20: rodzaje odpadow na jutro z calendar.smieci; blad zapytania = '?' (zegar: brak danych), nigdy pusta lista.",
        "mode": "queued",
        "triggers": [{"trigger": "homeassistant", "event": "start"},
                     {"trigger": "time", "at": ["00:00:30", "19:00:00"]},
                     {"trigger": "time_pattern", "minutes": "/30"}],
        "actions": [
            {"action": "calendar.get_events", "target": {"entity_id": "calendar.smieci"},
             "data": {"start_date_time": "{{ (now() + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0).isoformat() }}",
                      "end_date_time": "{{ (now() + timedelta(days=2)).replace(hour=0, minute=0, second=0, microsecond=0).isoformat() }}"},
             "response_variable": "odpowiedz", "continue_on_error": True},
            {"variables": {"ok": PACKAGE["template"][1]["actions"][1]["variables"]["ok"],
                           "rodzaje": PACKAGE["template"][1]["actions"][1]["variables"]["rodzaje"]}},
            {"if": [{"condition": "template", "value_template": "{{ ok }}"}],
             "then": [{"action": "input_text.set_value", "target": {"entity_id": "input_text.helios_smieci_rodzaje"},
                       "data": {"value": "{{ (rodzaje | join(', '))[:250] }}"}},
                      {"action": "input_datetime.set_datetime", "target": {"entity_id": "input_datetime.helios_smieci_odswiezone"},
                       "data": {"timestamp": "{{ now().timestamp() }}"}}],
             "else": [{"action": "input_text.set_value", "target": {"entity_id": "input_text.helios_smieci_rodzaje"}, "data": {"value": "?"}}]},
        ],
    },
    "helios_smieci_odhaczenie": {
        "alias": "Helios - Smieci odhaczenie dla daty",
        "description": "SPEC 0.20: odhaczenie dotyczy wywozu wskazanego regula 12:00.",
        "mode": "queued",
        "triggers": [{"trigger": "state", "entity_id": "input_boolean.smieci_wyniesione", "to": "on"}],
        "actions": [EVENTS_TODAY_TOMORROW, {"variables": {"cel": CEL}},
                    {"action": "input_datetime.set_datetime", "target": {"entity_id": "input_datetime.smieci_wyniesione_dla"}, "data": {"date": "{{ cel }}"}}],
    },
    "helios_smieci_odznaczenie": {
        "alias": "Helios - Smieci odznaczenie recznie",
        "description": "SPEC 0.20: reczne wylaczenie czysci odhaczenie (automatyczne nie).",
        "mode": "queued",
        "triggers": [{"trigger": "state", "entity_id": "input_boolean.smieci_wyniesione", "from": "on", "to": "off"}],
        "conditions": [{"condition": "template", "value_template": "{{ trigger.to_state.context.user_id is not none }}"}],
        "actions": [{"action": "input_datetime.set_datetime", "target": {"entity_id": "input_datetime.smieci_wyniesione_dla"}, "data": {"date": "2000-01-01"}}],
    },
    "helios_smieci_uzgodnienie": {
        "alias": "Helios - Smieci uzgodnienie przelacznika",
        "description": "SPEC 0.20: przelacznik gasnie, gdy odhaczenie dotyczy innej daty niz obecny cel (start, 00:05, 12:00, odswiezenie).",
        "mode": "single",
        "triggers": [{"trigger": "homeassistant", "event": "start"}, {"trigger": "time", "at": ["00:05:00", "12:00:00"]},
                     {"trigger": "state", "entity_id": "input_datetime.helios_smieci_odswiezone"}],
        "conditions": [{"condition": "state", "entity_id": "input_boolean.smieci_wyniesione", "state": "on"}],
        "actions": [EVENTS_TODAY_TOMORROW, {"variables": {"cel": CEL}},
                    {"condition": "template", "value_template": "{{ states('input_datetime.smieci_wyniesione_dla') != cel }}"},
                    {"action": "input_boolean.turn_off", "target": {"entity_id": "input_boolean.smieci_wyniesione"}}],
    },
}


def rest(method, path, body=None):
    r = urllib.request.Request(URL + path, data=None if body is None else json.dumps(body).encode(), headers=H, method=method)
    return json.load(urllib.request.urlopen(r, timeout=30))


async def ws_call(ws, n, msg):
    await ws.send(json.dumps({"id": n, **msg}))
    while True:
        r = json.loads(await ws.recv())
        if r.get("id") == n:
            return r


async def helpers(existing):
    wanted = [
        ("input_datetime", "input_datetime.smieci_wyniesione_dla", {"name": "Smieci wyniesione dla", "has_date": True, "has_time": False, "icon": "mdi:calendar-check"}),
        ("input_datetime", "input_datetime.helios_smieci_odswiezone", {"name": "Helios smieci odswiezone", "has_date": True, "has_time": True, "icon": "mdi:calendar-refresh"}),
        ("input_text", "input_text.helios_smieci_rodzaje", {"name": "Helios smieci rodzaje", "max": 255, "icon": "mdi:trash-can"}),
    ]
    async with websockets.connect(URL.replace("http", "ws") + "/api/websocket", max_size=None) as ws:
        await ws.recv(); await ws.send(json.dumps({"type": "auth", "access_token": CFG["token"]})); await ws.recv()
        n = 1
        for domain, entity, data in wanted:
            if entity in existing:
                print("keep", entity); continue
            print("create", entity)
            if APPLY:
                r = await ws_call(ws, n, {"type": f"{domain}/create", **data}); n += 1
                print("  ->", r.get("success"), r.get("error"))


def main():
    existing = {s["entity_id"] for s in rest("GET", "/api/states")}
    asyncio.run(helpers(existing))
    if APPLY and "input_datetime.smieci_wyniesione_dla" not in existing:
        rest("POST", "/api/services/input_datetime/set_datetime", {"entity_id": "input_datetime.smieci_wyniesione_dla", "date": "2000-01-01"})
    for kind, name, state, availability in TEMPLATES:
        entity = kind + "." + name.lower().replace(" ", "_")
        if entity in existing:
            print("keep", entity); continue
        print("create template", entity)
        if APPLY:
            flow = rest("POST", "/api/config/config_entries/flow", {"handler": "template"})
            rest("POST", "/api/config/config_entries/flow/" + flow["flow_id"], {"next_step_id": kind})
            r = rest("POST", "/api/config/config_entries/flow/" + flow["flow_id"],
                     {"name": name, "state": state, "additional_options": {"availability": availability}})
            print("  ->", r.get("type"), r.get("errors"))
    for aid, body in AUTOMATIONS.items():
        print("write automation", aid)
        if APPLY:
            print("  ->", rest("POST", "/api/config/automation/config/" + aid, body))


main()
