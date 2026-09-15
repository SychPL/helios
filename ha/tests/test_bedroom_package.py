"""SPEC 0.9 helpers: the Jinja in ha/packages/helios_bedroom.yaml evaluated with minimal stubs of HA's template functions.

Run: python -m pytest -q ha/tests  (needs pyyaml, jinja2; no Home Assistant)
"""

from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import jinja2
import jinja2.nativetypes
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = yaml.safe_load((ROOT / "ha/packages/helios_bedroom.yaml").read_text(encoding="utf-8"))
WARSAW = ZoneInfo("Europe/Warsaw")
STATE_SENSORS = PACKAGE["template"][0]["binary_sensor"]
TRIGGER_BLOCK = PACKAGE["template"][1]


class Env:
    """One evaluation context: HA time, entity states and the sensor's previous attributes."""

    def __init__(self, now_local: datetime, states: dict, prev: dict | None = None):
        self.now_local = now_local.astimezone(WARSAW) if now_local.tzinfo else now_local.replace(tzinfo=WARSAW)
        self.states = states
        self.prev = prev or {}
        self.env = jinja2.nativetypes.NativeEnvironment()
        self.env.filters["min"] = min
        self.env.globals.update(
            now=lambda: self.now_local,
            utcnow=lambda: self.now_local.astimezone(timezone.utc),
            timedelta=timedelta,
            as_local=lambda dt: dt.astimezone(WARSAW),
            as_datetime=lambda value: value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00")),
            is_state=lambda entity, state: self.states.get(entity, {}).get("state") == state,
            has_value=lambda entity: self.states.get(entity, {}).get("state") not in (None, "unknown", "unavailable"),
            state_attr=lambda entity, attr: self.states.get(entity, {}).get("attributes", {}).get(attr),
            namespace=jinja2.utils.Namespace,
            none=None,
        )

    def render(self, template: str, **variables):
        """Like HA: a native render, and a string result is stripped and literal_eval'd (dicts, None, numbers) when possible."""
        import ast
        out = self.env.from_string(template).render(this=type("This", (), {"attributes": self.prev})(), **variables)
        if isinstance(out, str):
            out = out.strip()
            try:
                return ast.literal_eval(out)
            except (ValueError, SyntaxError):
                return out
        return out


def binary(name):
    return next(s for s in STATE_SENSORS if s["unique_id"] == name)


def covers(a, b, sun="above_horizon"):
    return {
        "sun.sun": {"state": sun},
        "cover.bedroom_main_cover_a": {"state": a},
        "cover.bedroom_main_cover_b": {"state": b},
        "weather.forecast_dom": {"state": "cloudy", "attributes": {"temperature_unit": "°C"}},
    }


DAY = datetime(2026, 9, 15, 12, 0, tzinfo=WARSAW)


def test_package_declares_exactly_the_three_helpers():
    assert {s["unique_id"] for s in STATE_SENSORS} == {"helios_sypialnia_swiatlo_pokaz", "helios_pogoda_jutro_tryb"}
    assert TRIGGER_BLOCK["sensor"][0]["unique_id"] == "helios_pogoda_jutro"
    assert TRIGGER_BLOCK["actions"][0]["action"] == "weather.get_forecasts"
    assert TRIGGER_BLOCK["actions"][0]["continue_on_error"] is True
    assert set(TRIGGER_BLOCK["sensor"][0]["attributes"]) == {"forecast_date", "condition", "temperature", "templow", "temperature_unit", "fetched_at", "valid_until"}
    kinds = [t["trigger"] for t in TRIGGER_BLOCK["triggers"]]
    assert kinds == ["homeassistant", "time_pattern", "time", "state"]


@pytest.mark.parametrize(
    "a,b,sun,expected_state,expected_available",
    [
        ("open", "open", "above_horizon", False, True),
        ("closed", "open", "above_horizon", True, True),
        ("open", "open", "below_horizon", True, True),
        ("closing", "opening", "above_horizon", False, True),  # partially shut counts as open
        ("closed", "open", "unavailable", True, True),  # a true term wins over an unavailable source
        ("unavailable", "open", "above_horizon", False, False),
        ("open", "unknown", "above_horizon", False, False),
    ],
)
def test_light_visibility_helper(a, b, sun, expected_state, expected_available):
    sensor = binary("helios_sypialnia_swiatlo_pokaz")
    env = Env(DAY, covers(a, b, sun))
    assert env.render(sensor["state"]) is expected_state
    assert env.render(sensor["availability"]) is expected_available


@pytest.mark.parametrize("hour,minute,expected", [(17, 59, False), (18, 0, True), (23, 59, True), (0, 0, False)])
def test_evening_mode_helper(hour, minute, expected):
    sensor = binary("helios_pogoda_jutro_tryb")
    assert Env(datetime(2026, 9, 15, hour, minute, tzinfo=WARSAW), {}).render(sensor["state"]) is expected


def forecast(day, condition="cloudy", temperature=18.4, templow=9.2, hour=12):
    entry = {"datetime": datetime(2026, 9, day, hour, tzinfo=timezone.utc).isoformat(), "condition": condition, "temperature": temperature}
    if templow is not None:
        entry["templow"] = templow
    return entry


def record(env, forecasts):
    template = TRIGGER_BLOCK["actions"][1]["variables"]["record"]
    kwargs = {} if forecasts is None else {"forecasts": forecasts}
    return env.render(template, **kwargs)


def sensor_state(env, rec):
    return env.render(TRIGGER_BLOCK["sensor"][0]["state"], record=rec)


def test_tomorrow_is_picked_by_local_date_regardless_of_order():
    env = Env(datetime(2026, 9, 15, 19, 0, tzinfo=WARSAW), covers("open", "open"))
    rec = record(env, {"weather.forecast_dom": {"forecast": [forecast(17), forecast(16, "rainy", 12.0, 4.0), forecast(15)]}})
    assert rec["forecast_date"] == "2026-09-16" and rec["condition"] == "rainy" and rec["temperature"] == 12.0 and rec["templow"] == 4.0
    assert rec["temperature_unit"] == "°C"
    assert datetime.fromisoformat(rec["fetched_at"]) == env.now_local.astimezone(timezone.utc)
    # 19:00 local: next midnight (22:00Z) is earlier than fetched+6h (23:00Z)
    assert datetime.fromisoformat(rec["valid_until"]) == datetime(2026, 9, 15, 22, 0, tzinfo=timezone.utc)
    assert sensor_state(env, rec) == "ready"
    early = Env(datetime(2026, 9, 15, 9, 0, tzinfo=WARSAW), covers("open", "open"))
    rec = record(early, {"weather.forecast_dom": {"forecast": [forecast(16)]}})
    assert datetime.fromisoformat(rec["valid_until"]) == datetime(2026, 9, 15, 13, 0, tzinfo=timezone.utc)  # fetched+6h wins in the morning
    no_low = record(env, {"weather.forecast_dom": {"forecast": [forecast(16, templow=None)]}})
    assert no_low["templow"] is None and sensor_state(env, no_low) == "ready"


def test_missing_ambiguous_or_broken_forecast_gives_none():
    env = Env(datetime(2026, 9, 15, 19, 0, tzinfo=WARSAW), covers("open", "open"))
    assert record(env, {"weather.forecast_dom": {"forecast": [forecast(15), forecast(17)]}}) is None
    assert record(env, {"weather.forecast_dom": {"forecast": [forecast(16), forecast(16, hour=20)]}}) is None  # two records for tomorrow (12:00Z and 20:00Z = 22:00 local): ambiguous
    assert record(env, {"weather.forecast_dom": {"forecast": [{"datetime": forecast(16)["datetime"], "condition": "cloudy", "temperature": "warm"}]}}) is None
    assert record(env, {}) is None
    assert record(env, None) is None  # action failed: no response variable at all
    unit_less = Env(datetime(2026, 9, 15, 19, 0, tzinfo=WARSAW), {"weather.forecast_dom": {"state": "cloudy", "attributes": {}}})
    assert record(unit_less, {"weather.forecast_dom": {"forecast": [forecast(16)]}}) is None
    assert sensor_state(env, None) == "none"


def test_failed_fetch_keeps_a_valid_previous_record_and_expiry_drops_it():
    prev = {"forecast_date": "2026-09-16", "condition": "sunny", "temperature": 20.0, "templow": None, "temperature_unit": "°C",
            "fetched_at": "2026-09-15T17:00:00+00:00", "valid_until": "2026-09-15T22:00:00+00:00", "friendly_name": "Helios pogoda jutro"}
    env = Env(datetime(2026, 9, 15, 20, 30, tzinfo=WARSAW), covers("open", "open"), prev)
    kept = record(env, None)
    assert kept == {k: v for k, v in prev.items() if k != "friendly_name"}  # same stamps: the cache is not refreshed
    expired = Env(datetime(2026, 9, 16, 0, 5, tzinfo=WARSAW), covers("open", "open"), prev)
    assert record(expired, None) is None  # past midnight: the date no longer matches and valid_until passed
    stale_date = Env(datetime(2026, 9, 15, 20, 30, tzinfo=WARSAW), covers("open", "open"), dict(prev, forecast_date="2026-09-15"))
    assert record(stale_date, None) is None


def test_dst_change_and_year_end_use_calendar_days():
    dst = Env(datetime(2026, 10, 24, 19, 0, tzinfo=WARSAW), covers("open", "open"))  # clocks go back on 2026-10-25
    october = {"datetime": datetime(2026, 10, 25, 11, tzinfo=timezone.utc).isoformat(), "condition": "fog", "temperature": 7.0}
    rec = record(dst, {"weather.forecast_dom": {"forecast": [october]}})
    assert rec["forecast_date"] == "2026-10-25"
    assert datetime.fromisoformat(rec["valid_until"]) == datetime(2026, 10, 24, 22, 0, tzinfo=timezone.utc)
    year_end = Env(datetime(2026, 12, 31, 20, 0, tzinfo=WARSAW), covers("open", "open"))
    entry = {"datetime": datetime(2027, 1, 1, 11, tzinfo=timezone.utc).isoformat(), "condition": "snowy", "temperature": -3.0}
    assert record(year_end, {"weather.forecast_dom": {"forecast": [entry]}})["forecast_date"] == "2027-01-01"
