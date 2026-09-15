"""Pure-helper tests for the Helios integration; run with `pytest ha/tests` (no Home Assistant needed)."""

import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location("helios_const", pathlib.Path(__file__).resolve().parents[1] / "custom_components" / "helios" / "const.py")
const = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(const)


def test_brightness_mapping_round_trips_and_never_yields_level_zero():
    assert const.brightness_to_level(1) == 1
    assert const.brightness_to_level(13) == 1
    assert const.brightness_to_level(128) == 5
    assert const.brightness_to_level(255) == 10
    assert const.level_to_brightness(10) == 255
    assert const.level_to_brightness(1) == 26
    for level in range(1, 11):
        assert const.brightness_to_level(const.level_to_brightness(level)) == level


def test_command_validation_is_an_allowlist_with_typed_ranges():
    assert const.validate_command("lamp.turn_on", {}) is None
    assert const.validate_command("lamp.set_brightness", {"level": 10}) is None
    assert const.validate_command("audio.set_device_volume", {"percent": 0}) is None
    assert const.validate_command("lamp.set_brightness", {"level": 11}) == "invalid_args"
    assert const.validate_command("lamp.set_brightness", {"level": "5"}) == "invalid_args"
    assert const.validate_command("lamp.set_brightness", {"level": True}) == "invalid_args"
    assert const.validate_command("lamp.set_brightness", {}) == "invalid_args"
    assert const.validate_command("lamp.turn_on", {"extra": 1}) == "invalid_args"
    assert const.validate_command("shell.exec", {"cmd": "rm"}) == "unknown_command"


def test_pairing_codes_expire_and_wrong_attempts_are_bounded():
    now = [1000.0]
    registry = const.PairingRegistry(clock=lambda: now[0])
    registry.issue("123456", {"flow": "a"})
    assert registry.pending("123456")
    for _ in range(const.PAIRING_MAX_ATTEMPTS - 1):
        assert registry.claim("000000") is None
    assert registry.pending("123456")
    assert registry.claim("000000") is None
    assert not registry.pending("123456"), "too many wrong attempts must retire the code"
    registry.issue("654321", {"flow": "b"})
    now[0] += const.PAIRING_TTL_SECONDS + 1
    assert registry.claim("654321") is None, "expired code is never accepted"
    registry.issue("111111", {"flow": "c"})
    assert registry.claim("111111") == {"flow": "c"}
    assert registry.claim("111111") is None, "a code is single use"
