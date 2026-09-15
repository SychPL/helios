"""The helios/* WebSocket channel: connect (subscription), state and result."""

from __future__ import annotations

import asyncio

import voluptuous as vol

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import device_registry as dr

from .const import DOMAIN, PROTOCOL
from .coordinator import HeliosCoordinator


def _entry_for(hass: HomeAssistant, installation_id: str):
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.unique_id == installation_id:
            return entry
    return None


def _coordinator_for_connection(hass: HomeAssistant, connection) -> HeliosCoordinator | None:
    for coordinator in hass.data[DOMAIN]["entries"].values():
        if coordinator.is_owner(connection):
            return coordinator
    return None


@websocket_api.websocket_command(
    {
        vol.Required("type"): "helios/connect",
        vol.Required("protocol"): int,
        vol.Required("installation_id"): cv.string,
        vol.Required("app_version"): cv.string,
        vol.Required("version_code"): int,
        vol.Optional("capabilities"): [cv.string],
        vol.Optional("pairing_code"): cv.string,
    }
)
@websocket_api.async_response
async def ws_connect(hass: HomeAssistant, connection, msg: dict) -> None:
    """Subscription owned by one clock; a pairing code creates or re-binds the config entry first."""
    data = hass.data[DOMAIN]
    installation_id = msg["installation_id"]
    if msg["protocol"] != PROTOCOL:
        connection.send_error(msg["id"], "unsupported_protocol", f"Helios obsługuje protokół {PROTOCOL}")
        return
    code = msg.get("pairing_code")
    if code:
        pending = data["pairing"].claim(code)
        if pending is None:
            connection.send_error(msg["id"], "unauthorized", "Nieprawidłowy lub wygasły kod parowania")
            return
        if not pending["future"].done():
            pending["future"].set_result(
                {"installation_id": installation_id, "user_id": connection.user.id, "app_version": msg["app_version"], "version_code": msg["version_code"]}
            )
        try:
            await asyncio.wait_for(pending["done"].wait(), 30)
        except TimeoutError:
            connection.send_error(msg["id"], "pairing_failed", "Parowanie nie zostało dokończone w HA")
            return
    entry = _entry_for(hass, installation_id)
    if entry is None:
        connection.send_error(msg["id"], "unauthorized", "Nieznane urządzenie - dodaj integrację Helios i sparuj")
        return
    if entry.data.get("user_id") != connection.user.id:
        connection.send_error(msg["id"], "unauthorized", "To urządzenie jest sparowane z innym użytkownikiem HA")
        return
    coordinator: HeliosCoordinator | None = None
    for _ in range(50):  # a freshly created or reloaded entry finishes its setup shortly after the flow completes
        coordinator = data["entries"].get(entry.entry_id)
        if coordinator is not None:
            break
        await asyncio.sleep(0.2)
    if coordinator is None:
        connection.send_error(msg["id"], "not_ready", "Integracja Helios jeszcze się ładuje")
        return
    sub_id = msg["id"]
    coordinator.attach(connection, sub_id)

    @callback
    def cleanup() -> None:
        coordinator.detach(connection, sub_id, "disconnected")

    connection.subscriptions[sub_id] = cleanup
    connection.send_result(sub_id)
    device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, installation_id)})
    connection.send_event(sub_id, {"type": "connected", "device_id": device.id if device else None, "area_id": device.area_id if device else None})


@websocket_api.websocket_command({vol.Required("type"): "helios/state", vol.Required("state"): dict})
@callback
def ws_state(hass: HomeAssistant, connection, msg: dict) -> None:
    coordinator = _coordinator_for_connection(hass, connection)
    if coordinator is None:
        connection.send_error(msg["id"], "unauthorized", "Brak aktywnej subskrypcji helios/connect")
        return
    coordinator.set_state(msg["state"])
    connection.send_result(msg["id"])


@websocket_api.websocket_command(
    {vol.Required("type"): "helios/result", vol.Required("request_id"): cv.string, vol.Required("status"): cv.string, vol.Optional("code"): cv.string}
)
@callback
def ws_result(hass: HomeAssistant, connection, msg: dict) -> None:
    coordinator = _coordinator_for_connection(hass, connection)
    if coordinator is None:
        connection.send_error(msg["id"], "unauthorized", "Brak aktywnej subskrypcji helios/connect")
        return
    coordinator.resolve(msg["request_id"], msg["status"], msg.get("code"))
    connection.send_result(msg["id"])


@callback
def async_register(hass: HomeAssistant) -> None:
    websocket_api.async_register_command(hass, ws_connect)
    websocket_api.async_register_command(hass, ws_state)
    websocket_api.async_register_command(hass, ws_result)
