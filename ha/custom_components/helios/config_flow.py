"""Pairing flow: HA shows a one-time code, the user types it on the clock, the clock's helios/connect completes the flow."""

from __future__ import annotations

import asyncio
import secrets

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.core import callback

from .const import DOMAIN, PAIRING_TTL_SECONDS, PairingRegistry


class HeliosConfigFlow(ConfigFlow, domain=DOMAIN):
    VERSION = 1

    def __init__(self) -> None:
        self._code: str | None = None
        self._future: asyncio.Future | None = None
        self._done: asyncio.Event | None = None
        self._task: asyncio.Task | None = None
        self._timed_out = False

    @property
    def _registry(self) -> PairingRegistry:
        return self.hass.data.setdefault(DOMAIN, {"pairing": PairingRegistry(), "entries": {}})["pairing"]

    async def async_step_user(self, user_input=None) -> ConfigFlowResult:
        if self._code is None:
            self._code = f"{secrets.randbelow(10**6):06d}"
            self._future = self.hass.loop.create_future()
            self._done = asyncio.Event()
            self._registry.issue(self._code, {"future": self._future, "done": self._done})
            self._task = self.hass.async_create_task(self._wait_for_clock())
        if not self._task.done():
            return self.async_show_progress(
                step_id="user",
                progress_action="pair",
                description_placeholders={"code": self._code, "minutes": str(PAIRING_TTL_SECONDS // 60)},
                progress_task=self._task,
            )
        return self.async_show_progress_done(next_step_id="finish")

    async def _wait_for_clock(self) -> None:
        try:
            await asyncio.wait_for(asyncio.shield(self._future), PAIRING_TTL_SECONDS)
        except TimeoutError:
            self._timed_out = True

    async def async_step_finish(self, user_input=None) -> ConfigFlowResult:
        self._registry.cancel(self._code)
        if self._timed_out or self._future is None or not self._future.done():
            self._release()
            return self.async_abort(reason="timeout")
        data = self._future.result()
        await self.async_set_unique_id(data["installation_id"])
        existing = self._async_current_entries()
        for entry in existing:
            if entry.unique_id == data["installation_id"]:
                self.hass.config_entries.async_update_entry(entry, data={**entry.data, **data})
                self.hass.async_create_task(self.hass.config_entries.async_reload(entry.entry_id))
                self._release()
                return self.async_abort(reason="reconfigured")
        result = self.async_create_entry(title=f"Helios {data['installation_id'][:8]}", data=data)
        self._release()
        return result

    @callback
    def _release(self) -> None:
        """Lets the waiting helios/connect proceed once the entry exists (or the flow gave up)."""
        if self._done is not None:
            self._done.set()

    @callback
    def async_remove(self) -> None:
        if self._code is not None:
            self._registry.cancel(self._code)
        self._release()
