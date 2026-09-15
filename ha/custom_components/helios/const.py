"""Constants and pure helpers for the Helios integration (no Home Assistant imports here; unit-tested directly)."""

from __future__ import annotations

import time

DOMAIN = "helios"
PROTOCOL = 1
PAIRING_TTL_SECONDS = 300
PAIRING_MAX_ATTEMPTS = 5
COMMAND_TIMEOUT_SECONDS = 10

COMMANDS: dict[str, dict[str, tuple[int, int]]] = {
    "lamp.turn_on": {},
    "lamp.turn_off": {},
    "lamp.set_brightness": {"level": (1, 10)},
    "audio.set_device_volume": {"percent": (0, 100)},
}


def brightness_to_level(brightness: int) -> int:
    """HA brightness 1..255 to the dock's ten levels; zero is turn_off and never reaches here."""
    return max(1, min(10, round(brightness * 10 / 255)))


def level_to_brightness(level: int) -> int:
    return max(1, min(255, round(level * 255 / 10)))


def validate_command(command: str, args: dict) -> str | None:
    """Returns None when the command and its integer arguments are inside the allowlist, else an error code."""
    spec = COMMANDS.get(command)
    if spec is None:
        return "unknown_command"
    for key, (low, high) in spec.items():
        value = args.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < low or value > high:
            return "invalid_args"
    if set(args) - set(spec):
        return "invalid_args"
    return None


class PairingRegistry:
    """One-time pairing codes with expiry and a bounded number of wrong attempts per code."""

    def __init__(self, clock=time.monotonic) -> None:
        self._clock = clock
        self._codes: dict[str, dict] = {}

    def issue(self, code: str, payload: dict) -> None:
        self._codes[code] = {"payload": payload, "expires": self._clock() + PAIRING_TTL_SECONDS, "attempts": 0}

    def cancel(self, code: str) -> None:
        self._codes.pop(code, None)

    def claim(self, code: str) -> dict | None:
        """Consumes and returns the pending payload for a valid code; wrong or expired codes count as attempts."""
        now = self._clock()
        for key in [k for k, v in self._codes.items() if v["expires"] < now]:
            self._codes.pop(key)
        entry = self._codes.get(code)
        if entry is not None:
            return self._codes.pop(code)["payload"]
        for pending in self._codes.values():
            pending["attempts"] += 1
        for key in [k for k, v in self._codes.items() if v["attempts"] >= PAIRING_MAX_ATTEMPTS]:
            self._codes.pop(key)
        return None

    def pending(self, code: str) -> bool:
        entry = self._codes.get(code)
        return entry is not None and entry["expires"] >= self._clock()
