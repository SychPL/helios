"""Version, voice state and dock version sensors."""

from __future__ import annotations

from homeassistant.components.sensor import SensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN
from .entity import HeliosEntity

SENSORS = (
    ("app_version", "Wersja aplikacji", EntityCategory.DIAGNOSTIC, "mdi:cellphone-arrow-down"),
    ("voice_state", "Stan głosu", None, "mdi:microphone"),
    ("pad_version", "Wersja docka", EntityCategory.DIAGNOSTIC, "mdi:chip"),
)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    coordinator = hass.data[DOMAIN]["entries"][entry.entry_id]
    async_add_entities(HeliosSensor(coordinator, key, name, category, icon) for key, name, category, icon in SENSORS)


class HeliosSensor(HeliosEntity, SensorEntity):
    def __init__(self, coordinator, key, name, category, icon) -> None:
        super().__init__(coordinator, key)
        self._attr_name = name
        self._attr_entity_category = category
        self._attr_icon = icon

    @property
    def native_value(self):
        return self.value
