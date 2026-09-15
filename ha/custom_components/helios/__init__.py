"""Helios: a Lenovo Smart Clock 2 running the Helios app, connected over HA's own WebSocket API."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.typing import ConfigType

from . import websocket
from .const import DOMAIN, PairingRegistry
from .coordinator import HeliosCoordinator

PLATFORMS = [Platform.SENSOR, Platform.BINARY_SENSOR, Platform.LIGHT, Platform.NUMBER]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    hass.data.setdefault(DOMAIN, {"pairing": PairingRegistry(), "entries": {}})
    websocket.async_register(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    hass.data.setdefault(DOMAIN, {"pairing": PairingRegistry(), "entries": {}})
    coordinator = HeliosCoordinator(hass, entry)
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, coordinator.installation_id)},
        manufacturer="Lenovo",
        model="Smart Clock 2",
        name=entry.title,
        sw_version=entry.data.get("app_version"),
    )
    hass.data[DOMAIN]["entries"][entry.entry_id] = coordinator
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    coordinator: HeliosCoordinator | None = hass.data[DOMAIN]["entries"].pop(entry.entry_id, None)
    if coordinator is not None:
        coordinator.send_removed()
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
