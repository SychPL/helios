"""Dock connected and phone charging."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN
from .entity import HeliosEntity


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback) -> None:
    coordinator = hass.data[DOMAIN]["entries"][entry.entry_id]
    async_add_entities(
        [
            HeliosBinarySensor(coordinator, "dock_connected", "Dock podłączony", BinarySensorDeviceClass.CONNECTIVITY),
            HeliosChargingSensor(coordinator),
        ]
    )


class HeliosBinarySensor(HeliosEntity, BinarySensorEntity):
    def __init__(self, coordinator, key, name, device_class) -> None:
        super().__init__(coordinator, key)
        self._attr_name = name
        self._attr_device_class = device_class

    @property
    def is_on(self):
        return self.value


class HeliosChargingSensor(HeliosBinarySensor):
    """Charging is only meaningful while a dock is known to be connected; a stop is not proof the phone was lifted."""

    def __init__(self, coordinator) -> None:
        super().__init__(coordinator, "charging", "Ładowanie telefonu", BinarySensorDeviceClass.BATTERY_CHARGING)

    @property
    def available(self) -> bool:
        return super().available and self.coordinator.value("dock_connected") is True
