"""Dock lamp as a brightness-only light; on/off comes from the OEM readback, brightness is the last accepted setpoint."""

from __future__ import annotations

from typing import Any

from homeassistant.components.light import ATTR_BRIGHTNESS, ColorMode, LightEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN, brightness_to_level, level_to_brightness
from .entity import HeliosEntity


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([HeliosLamp(hass.data[DOMAIN]["entries"][entry.entry_id])])


class HeliosLamp(HeliosEntity, LightEntity):
    _attr_name = "Lampka docka"
    _attr_color_mode = ColorMode.BRIGHTNESS
    _attr_supported_color_modes = {ColorMode.BRIGHTNESS}
    _attr_icon = "mdi:lamp"

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "led_on")

    @property
    def available(self) -> bool:
        return super().available and self.coordinator.value("dock_connected") is not False

    @property
    def is_on(self) -> bool | None:
        return self.value

    @property
    def brightness(self) -> int | None:
        level = self.coordinator.value("led_brightness")
        return None if level is None else level_to_brightness(int(level))

    async def async_turn_on(self, **kwargs: Any) -> None:
        if ATTR_BRIGHTNESS in kwargs:
            await self.coordinator.async_command("lamp.set_brightness", {"level": brightness_to_level(int(kwargs[ATTR_BRIGHTNESS]))})
        await self.coordinator.async_command("lamp.turn_on", {})

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self.coordinator.async_command("lamp.turn_off", {})
