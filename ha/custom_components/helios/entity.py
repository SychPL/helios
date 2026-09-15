"""Base entity: one device per installation; unavailable until the clock is connected and reported the value."""

from __future__ import annotations

from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import DOMAIN
from .coordinator import HeliosCoordinator


class HeliosEntity(CoordinatorEntity[HeliosCoordinator]):
    _attr_has_entity_name = True
    key: str = ""

    def __init__(self, coordinator: HeliosCoordinator, key: str) -> None:
        super().__init__(coordinator)
        self.key = key
        self._attr_unique_id = f"{coordinator.installation_id}_{key}"
        self._attr_device_info = DeviceInfo(identifiers={(DOMAIN, coordinator.installation_id)})

    @property
    def value(self):
        return self.coordinator.value(self.key)

    @property
    def available(self) -> bool:
        return self.coordinator.available and self.value is not None
