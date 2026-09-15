"""Device output volume as a 0..100 percent number; the reported value is what the clock read back."""

from __future__ import annotations

from homeassistant.components.number import NumberEntity, NumberMode
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN
from .entity import HeliosEntity


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    async_add_entities([HeliosVolume(hass.data[DOMAIN]["entries"][entry.entry_id])])


class HeliosVolume(HeliosEntity, NumberEntity):
    _attr_name = "Głośność urządzenia"
    _attr_native_min_value = 0
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_native_unit_of_measurement = "%"
    _attr_mode = NumberMode.SLIDER
    _attr_icon = "mdi:volume-high"

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "volume_percent")

    @property
    def native_value(self):
        return self.value

    async def async_set_native_value(self, value: float) -> None:
        await self.coordinator.async_command("audio.set_device_volume", {"percent": int(round(value))})
