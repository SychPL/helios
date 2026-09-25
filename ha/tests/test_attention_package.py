"""SPEC 0.20 pkt 6 helpers: the Jinja in ha/packages/helios_attention_extra.yaml with minimal stubs of HA's template functions.

Run: python -m pytest -q ha/tests  (needs pyyaml, jinja2; no Home Assistant)
"""

import ast
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import jinja2
import jinja2.nativetypes
import yaml

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = yaml.safe_load((ROOT / "ha/packages/helios_attention_extra.yaml").read_text(encoding="utf-8"))
WARSAW = ZoneInfo("Europe/Warsaw")
FLAGS = {s["unique_id"]: s for s in PACKAGE["template"][0]["binary_sensor"]}
CEL = PACKAGE["automation"][0]["actions"][1]["variables"]["cel"]


def render(template, now, states=None, **variables):
    now = now.replace(tzinfo=WARSAW)
    states = states or {}
    env = jinja2.nativetypes.NativeEnvironment()
    env.tests["mapping"] = lambda v: isinstance(v, dict)
    env.globals.update(
        now=lambda: now,
        utcnow=lambda: now.astimezone(timezone.utc),
        timedelta=timedelta,
        as_datetime=lambda v: datetime.fromisoformat(str(v)),
        is_state=lambda e, s: states.get(e, {}).get("state") == s,
        has_value=lambda e: states.get(e, {}).get("state") not in (None, "unknown", "unavailable"),
        states=lambda e: states.get(e, {}).get("state", "unknown"),
        state_attr=lambda e, a: states.get(e, {}).get("attributes", {}).get(a),
        namespace=jinja2.utils.Namespace,
    )
    out = env.from_string(template).render(**variables)
    if isinstance(out, str):  # HA strips the result and reads Python literals from it, as here
        out = out.strip()
        try:
            out = ast.literal_eval(out)
        except (ValueError, SyntaxError):
            pass
    return out


def events(*days):
    return {"calendar.smieci": {"events": [{"start": d, "summary": "bio"} for d in days]}}


def test_tick_goes_to_the_collection_the_12_oclock_rule_points_at():
    # Monday and Tuesday collections: Sunday evening ticks Monday, Monday morning Monday, Monday afternoon Tuesday
    both = events("2026-09-28", "2026-09-29")
    assert render(CEL, datetime(2026, 9, 27, 21, 0), odpowiedz=both) == "2026-09-28"
    assert render(CEL, datetime(2026, 9, 28, 7, 0), odpowiedz=both) == "2026-09-28"
    assert render(CEL, datetime(2026, 9, 28, 15, 0), odpowiedz=both) == "2026-09-29"
    # only today: the afternoon tick still means today; nothing at all (or no calendar): tomorrow
    assert render(CEL, datetime(2026, 9, 28, 15, 0), odpowiedz=events("2026-09-28")) == "2026-09-28"
    assert render(CEL, datetime(2026, 9, 28, 15, 0), odpowiedz=events()) == "2026-09-29"
    assert render(CEL, datetime(2026, 9, 28, 15, 0)) == "2026-09-29"
    # timed events come as datetimes: their date counts
    assert render(CEL, datetime(2026, 9, 28, 7, 0), odpowiedz=events("2026-09-28T06:00:00+02:00")) == "2026-09-28"


def test_garage_flag_only_in_the_evening_and_unknown_without_the_camera():
    flag = FLAGS["helios_garaz_wieczor_pokaz"]
    cam = lambda s: {"sensor.camera_garage_garage_gate_classification": {"state": s}}
    assert render(flag["state"], datetime(2026, 9, 25, 20, 0), cam("open")) is True
    assert render(flag["state"], datetime(2026, 9, 25, 6, 59), cam("open")) is True
    assert render(flag["state"], datetime(2026, 9, 25, 14, 0), cam("open")) is False
    assert render(flag["availability"], datetime(2026, 9, 25, 14, 0), cam("unavailable")) is True, "daytime: known off"
    assert render(flag["availability"], datetime(2026, 9, 25, 22, 0), cam("unavailable")) is False, "evening: unknown"


def test_trash_flag_needs_a_fresh_result_for_tomorrow_and_an_unticked_tomorrow():
    flag = FLAGS["helios_smieci_jutro_pokaz"]
    now = datetime(2026, 9, 27, 19, 30)
    fresh = (now.replace(tzinfo=WARSAW) - timedelta(minutes=10)).isoformat()
    def st(count=1, query="2026-09-28", at=fresh, ticked="2000-01-01", state="Jutro: bio"):
        return {"sensor.helios_smieci_jutro": {"state": state, "attributes": {"count": count, "query_date": query, "refreshed_at": at}},
                "input_datetime.smieci_wyniesione_dla": {"state": ticked}}
    assert render(flag["state"], now, st()) is True
    assert render(flag["state"], now, st(ticked="2026-09-28")) is False, "tomorrow ticked"
    assert render(flag["state"], now, st(ticked="2026-09-27")) is True, "an old tick never silences the next collection"
    assert render(flag["state"], now, st(count=0, state="")) is False
    assert render(flag["availability"], now, st()) is True
    assert render(flag["availability"], now, st(query="2026-09-27")) is False, "a result for the wrong day"
    stale = (now.replace(tzinfo=WARSAW) - timedelta(minutes=70)).isoformat()
    assert render(flag["availability"], now, st(at=stale)) is False, "older than 65 min"
    assert render(flag["availability"], now, st(state="unavailable")) is False, "calendar failed"
    assert render(flag["availability"], now, st(ticked="unavailable")) is False
