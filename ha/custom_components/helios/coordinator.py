"""Connection owner and state holder for one Helios clock."""

from __future__ import annotations

import asyncio
import logging
from uuid import uuid4

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator

from .const import COMMAND_TIMEOUT_SECONDS, DOMAIN

_LOGGER = logging.getLogger(__name__)


class HeliosCoordinator(DataUpdateCoordinator[dict]):
    """Push-only coordinator: the clock owns exactly one active subscription; commands travel over it."""

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        super().__init__(hass, _LOGGER, name=DOMAIN, update_interval=None)
        self.entry = entry
        self.installation_id: str = entry.data["installation_id"]
        self.connection = None
        self.sub_id: int | None = None
        self.available = False
        self._pending: dict[str, asyncio.Future] = {}

    @callback
    def attach(self, connection, sub_id: int) -> None:
        """A newly authorized subscription replaces the previous one; the old cleanup must not touch the new owner."""
        old_connection, old_sub = self.connection, self.sub_id
        self.connection, self.sub_id = connection, sub_id
        self.available = False
        self._fail_pending("replaced")
        if old_connection is not None and old_connection is not connection:
            try:
                old_connection.send_event(old_sub, {"type": "replaced"})
                old_connection.subscriptions.pop(old_sub, None)
            except Exception:  # noqa: BLE001 - the old socket may already be gone
                pass
        self.async_update_listeners()

    def is_owner(self, connection, sub_id: int | None = None) -> bool:
        return self.connection is connection and (sub_id is None or self.sub_id == sub_id)

    @callback
    def detach(self, connection, sub_id: int, reason: str) -> None:
        if not self.is_owner(connection, sub_id):
            return
        self.connection, self.sub_id = None, None
        self.available = False
        self._fail_pending(reason)
        self.async_update_listeners()

    @callback
    def send_removed(self) -> None:
        if self.connection is None:
            return
        connection, sub_id = self.connection, self.sub_id
        try:
            connection.send_event(sub_id, {"type": "removed"})
            connection.subscriptions.pop(sub_id, None)
        except Exception:  # noqa: BLE001
            pass
        self.detach(connection, sub_id, "removed")

    @callback
    def set_state(self, state: dict) -> None:
        self.available = True
        version = state.get("app_version")
        if version and version != self.entry.data.get("app_version"):
            self.hass.config_entries.async_update_entry(self.entry, data={**self.entry.data, "app_version": version})
            registry = dr.async_get(self.hass)
            device = registry.async_get_device(identifiers={(DOMAIN, self.installation_id)})
            if device is not None:
                registry.async_update_device(device.id, sw_version=version)
        self.async_set_updated_data(state)

    @callback
    def resolve(self, request_id: str, status: str, code: str | None) -> None:
        future = self._pending.pop(request_id, None)
        if future is not None and not future.done():
            future.set_result({"status": status, "code": code})

    async def async_command(self, command: str, args: dict) -> None:
        """One command on the active subscription; timeout means unknown outcome and nothing is retried."""
        if self.connection is None:
            raise HomeAssistantError("Helios jest offline")
        request_id = uuid4().hex
        future: asyncio.Future = self.hass.loop.create_future()
        self._pending[request_id] = future
        try:
            self.connection.send_event(self.sub_id, {"type": "command", "request_id": request_id, "command": command, "args": args})
            result = await asyncio.wait_for(future, COMMAND_TIMEOUT_SECONDS)
        except TimeoutError as err:
            raise HomeAssistantError("Helios nie potwierdził polecenia w czasie 10 s") from err
        finally:
            self._pending.pop(request_id, None)
        if result["status"] != "ok":
            raise HomeAssistantError(f"Helios odrzucił polecenie: {result['status']} {result.get('code') or ''}".strip())

    def _fail_pending(self, reason: str) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_result({"status": "error", "code": reason})
        self._pending.clear()

    def value(self, key: str):
        return None if self.data is None else self.data.get(key)
