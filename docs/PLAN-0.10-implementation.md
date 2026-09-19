# Plan implementacji 0.10 - onboarding bez tokena

Status: plan do recenzji, 2026-09-16. Realizuje [SPEC 0.10](SPEC-0.10-onboarding.md) w dwóch repozytoriach: **ha-helios** 0.7.6 (`d1248e2`) → 0.8.0 oraz **Helios** 0.8.18 (`ba7f61e`, versionCode 27) → 0.9.0 (versionCode 28). Kolejność ze SPEC pkt 11: najpierw integracja (stary zegar dalej działa), potem aplikacja, potem parowanie na zegarze, na końcu wydania na GitHub.

Zasady: ha-helios - logika bez HA w `const.py`/`identity.py` testowana czystym pytest, glue z HA w testach komponentowych na `pytest-homeassistant-custom-component==0.13.357` (pin `homeassistant==2026.8.3`, Python ≥ 3.14; lokalnie `uv venv --python 3.14`, w CI job `tests`); Helios - testy JVM bez mocków Androida, Java 8, bez nowych zależności, każde zadanie kończy `./gradlew -q testDebugUnitTest assembleDebug lintDebug` (`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`). Sekrety tylko w `.local/`; żaden test, dziennik ani dokument nie zawiera tokena.

## Stan wyjściowy (potwierdzony w kodzie)

| Plik | Dziś | Zmiana |
| --- | --- | --- |
| `ha-helios/const.py` | `PROTOCOL=1`; `PairingRegistry.claim(code)` liczy pomyłki dla wszystkich kodów | `PROTOCOL=2`; `claim(code, source)` z limitem per źródło; `parse_pair_request`, `sendspin_url_for` |
| `ha-helios/config_flow.py` | `async_step_user` rejestruje WS, wystawia kod, `_wait_for_clock`; `async_step_finish` tworzy albo aktualizuje wpis i zwalnia `done` | rejestracja widoków przez helper; `pending` z `flow_id`, `cancelled`, `existing`; `finish` tworzy tylko nowy wpis, dla istniejącego abort `reconfigured` |
| `ha-helios/websocket.py` | `helios/connect` z `pairing_code`, `protocol != 1` odrzucane | bez `pairing_code`, protokół 1 lub 2, po `appearance` zdarzenie `connection`, sekcja MA pod blokadą z ponowną kontrolą właściciela |
| `ha-helios/http.py` | tylko `HeliosAppearanceView` (`requires_auth=True`) | + `HeliosPairView` (`requires_auth=False`), `async_register_views` |
| `ha-helios/__init__.py` | `async_setup` rejestruje WS i widok obrazów; `async_remove_entry` usuwa katalog | `views_registered`; `cancelled_users` w `async_setup_entry`; usuwanie tożsamości i tokena MA w `async_remove_entry`; `send_connection` po zmianie opcji |
| `ha-helios/coordinator.py` | `send_appearance`, `send_removed` | + `send_connection(payload)` |
| `ha-helios/tests` | `test_helpers.py` bez HA; CI `tests` na 3.13 bez HA | + `conftest.py`, `test_pairing.py` (komponentowe); CI `tests` na 3.14 z HA |
| `HeliosDeviceClient.java` | `PROTOCOL=1`, `pair(code)`, `removed`/`replaced` → `ended` bez ponowienia; wynik `helios/state` ignorowany | `PROTOCOL=2`; `removed` → ponowne `connect` z backoffem; `replaced` → komunikat; błąd wyniku `helios/state` → jak `removed`; zdarzenie `connection` → `Listener.onConnection` |
| `HaDashboardClient.java` | `auth_invalid` → `IOException` → pętla; `probe()` | `auth_invalid` → `Listener.onAuthInvalid()` + `stopped=true`; `probe()` usunięty |
| `HeliosService.java` | `reconfigure` (sonda HA + MA, merge), `pair`, `sameHa`, `startMusic` z `player_name` | `pairedWith` (commit, restart), `applyConnection` (trzy stany MA), flaga `auth_invalid`, `legacyConnection()`; bez `reconfigure`/`pair`/`sameHa` |
| `MainActivity.java` | `connect()` z `PROVISION_URL`, `applyProvisioning`, `refreshPairing`, `pairDialog` (wymaga połączenia) | `Onboarding` (lista mDNS, adres, instrukcja, sonda, kod, `POST`), `pairDialog` jako krok onboardingu; bez mostu |
| `NavigationMenu.java` | „Paruj z HA (kod)”, „Odśwież parowanie”, „Aktualizacja Heliosa” przez przeglądarkę | „Paruj z HA”, bez odświeżania, aktualizacja przez `Updater` |
| `AndroidManifest.xml` | `INTERNET`, `RECORD_AUDIO`, `FOREGROUND_SERVICE` | + `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`, `REQUEST_INSTALL_PACKAGES`, `<receiver .UpdateReceiver exported=false>` |
| `app/build.gradle` | `PROVISION_URL` z `.local/provision.json`, 0.8.18/27 | bez `PROVISION_URL`, 0.9.0/28 |
| `tools/native_bridge.py` | `--prepare`, `/config` (30 min), `/helios.apk`, `/install.dex`, `/events` | bez `--prepare`/`/config`; ścieżka z `.local/bridge.json` |
| `.local/ui-dashboard-fixture-v2.py` | WS HA z `helios/connect`; HTTP tylko zdjęcie | + `GET/POST /api/helios/pair`, zdarzenie `connection`, sterowanie `/removed` |

Nazwy wspólne dla obu repozytoriów (kontrakt SPEC pkt 4.1 i 6.3): `GET /api/helios/pair` → `{"protocol": 2}`; `POST` body `{installation_id, code, app_version, version_code}`; odpowiedź `{"protocol": 2, "token", "pipeline", "dashboard_path"}`; zdarzenie `{"type": "connection", "pipeline", "dashboard_path", "music_assistant": {url, token, sendspin_url} | null, "diagnostics_url": str | null}`.

---

## Etap A - ha-helios 0.8.0

### A1 Czyste helpery: żądanie parowania, limit per źródło, adres Sendspin

**Pliki:** `custom_components/helios/const.py`, `tests/test_helpers.py`.

**Interfejs (produkuje):**
- `PROTOCOL = 2`, `PROTOCOLS = (1, 2)`, `PAIRING_MAX_SOURCES = 256`, `PAIR_BODY_LIMIT = 4096`, `DASHBOARD_PATH = "helios-clock"`, `PAIRING_TIMEOUT_SECONDS = 30`, `FLOW_TIMEOUT_SECONDS = 20`, `SETUP_TIMEOUT_SECONDS = 10`, `MA_TIMEOUT_SECONDS = 5` (arytmetyka: transakcja 30 s + przywracający reload 10 s + rollback 10 s = 50 s < 60 s odczytu po stronie zegara).
- `parse_pair_request(body: object) -> dict | None`: zwraca `{"installation_id", "code", "app_version", "version_code"}` albo `None`.
- `PairingRegistry.claim(code: str, source: str) -> dict | None` i `PairingRegistry.blocked(source: str) -> bool` (limit per źródło, okno `PAIRING_TTL_SECONDS`, leniwe czyszczenie, limit `PAIRING_MAX_SOURCES`).
- `sendspin_url_for(url: str) -> str`: `ws://<host>:8927/sendspin` z hosta adresu MA (IPv6 w nawiasach zachowane).

- [ ] **Krok 1: testy (RED)** - dopisać do `tests/test_helpers.py`:

```python
import re

INSTALLATION = "0f3c1b2a-9d8e-4c7b-a6f5-1e2d3c4b5a69"


def test_pair_request_is_strictly_validated():
    good = {"installation_id": INSTALLATION, "code": "123456", "app_version": "0.9.0", "version_code": 28}
    assert const.parse_pair_request(good) == good
    assert const.parse_pair_request({**good, "extra": 1}) == good, "unknown keys are ignored, not rejected"
    for bad in (
        None, [], "x",
        {**good, "code": "12345"}, {**good, "code": "12345a"}, {**good, "code": 123456}, {**good, "code": "123456\n"}, {**good, "installation_id": INSTALLATION + "\n"},
        {**good, "installation_id": "short"}, {**good, "installation_id": "x" * 65}, {**good, "installation_id": "bad/id"},
        {**good, "app_version": "v" * 33}, {**good, "app_version": 1},
        {**good, "version_code": 0}, {**good, "version_code": "28"}, {**good, "version_code": True},
        {k: v for k, v in good.items() if k != "code"},
    ):
        assert const.parse_pair_request(bad) is None, bad


def test_wrong_attempts_are_counted_per_source_and_never_retire_codes():
    now = [1000.0]
    registry = const.PairingRegistry(clock=lambda: now[0])
    registry.issue("123456", {"flow": "a"})
    for _ in range(const.PAIRING_MAX_ATTEMPTS):
        assert registry.claim("000000", "10.0.0.5") is None
    assert registry.pending("123456"), "a code survives any number of wrong attempts"
    assert registry.blocked("10.0.0.5")
    assert registry.claim("123456", "10.0.0.5") is None, "a blocked source is refused even with the right code"
    assert registry.pending("123456")
    assert not registry.blocked("10.0.0.6")
    assert registry.claim("123456", "10.0.0.6") == {"flow": "a"}
    assert registry.claim("123456", "10.0.0.6") is None, "single use"
    now[0] += const.PAIRING_TTL_SECONDS + 1
    assert not registry.blocked("10.0.0.5"), "the block expires with the window"


def test_source_map_is_purged_and_bounded():
    now = [0.0]
    registry = const.PairingRegistry(clock=lambda: now[0])
    for i in range(const.PAIRING_MAX_SOURCES):
        registry.claim("000000", f"10.1.{i // 256}.{i % 256}")
    assert registry.blocked("10.9.9.9"), "a full map treats unknown sources as blocked"
    now[0] += const.PAIRING_TTL_SECONDS + 1
    assert not registry.blocked("10.9.9.9"), "expired sources are purged lazily"
    registry.claim("000000", "10.9.9.9")
    assert not registry.blocked("10.9.9.9")


def test_expired_codes_are_still_refused():
    now = [0.0]
    registry = const.PairingRegistry(clock=lambda: now[0])
    registry.issue("654321", {"flow": "b"})
    now[0] += const.PAIRING_TTL_SECONDS
    assert registry.claim("654321", "10.0.0.1") is None, "exactly at expiry the code is already dead"
    registry.issue("111111", {"flow": "c"})
    now[0] += const.PAIRING_TTL_SECONDS - 1
    assert registry.claim("111111", "10.0.0.1") == {"flow": "c"}


def test_sendspin_url_is_derived_from_the_ma_host():
    assert const.sendspin_url_for("http://<home-assistant>:8095") == "ws://<home-assistant>:8927/sendspin"
    assert const.sendspin_url_for("https://ma.local/") == "ws://ma.local:8927/sendspin"
    assert const.sendspin_url_for("http://[fd00::5]:8095") == "ws://[fd00::5]:8927/sendspin"
    assert const.sendspin_url_for("") == ""
```

Usunąć stary `test_pairing_codes_expire_and_wrong_attempts_are_bounded` (semantyka „5 pomyłek kasuje kod” przestaje istnieć).

- [ ] **Krok 2: uruchomić** `python -m pytest -q tests` (Python 3.13 wystarcza) - oczekiwane FAIL (`AttributeError: parse_pair_request`, `TypeError: claim()`).

- [ ] **Krok 3: implementacja** w `const.py` (zastąpić `PROTOCOL` i klasę `PairingRegistry`; `import re`, `from urllib.parse import urlsplit`):

```python
PROTOCOL = 2
PROTOCOLS = (1, 2)
PAIRING_TTL_SECONDS = 300
PAIRING_MAX_ATTEMPTS = 5
PAIRING_MAX_SOURCES = 256
PAIR_BODY_LIMIT = 4096
PAIRING_TIMEOUT_SECONDS = 30
FLOW_TIMEOUT_SECONDS = 20
SETUP_TIMEOUT_SECONDS = 10
MA_TIMEOUT_SECONDS = 5
DASHBOARD_PATH = "helios-clock"
COMMAND_TIMEOUT_SECONDS = 10

_INSTALLATION_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")
_CODE_RE = re.compile(r"^[0-9]{6}$")


def parse_pair_request(body: object) -> dict | None:
    """The clock's pairing request; None for anything that is not exactly the documented shape (unknown keys ignored)."""
    if not isinstance(body, dict):
        return None
    installation_id, code, app_version, version_code = body.get("installation_id"), body.get("code"), body.get("app_version"), body.get("version_code")
    if not (isinstance(installation_id, str) and _INSTALLATION_RE.fullmatch(installation_id)):  # fullmatch: "$" alone lets a trailing newline through
        return None
    if not (isinstance(code, str) and _CODE_RE.fullmatch(code)):
        return None
    if not (isinstance(app_version, str) and 1 <= len(app_version) <= 32):
        return None
    if not isinstance(version_code, int) or isinstance(version_code, bool) or version_code < 1:
        return None
    return {"installation_id": installation_id, "code": code, "app_version": app_version, "version_code": version_code}


def sendspin_url_for(url: str) -> str:
    """Sendspin lives next to the MA API on port 8927 (SPEC 0.6 / docs/ma-api-2.10.3.md)."""
    if not url:
        return ""
    host = urlsplit(url).hostname or ""
    if ":" in host:
        host = f"[{host}]"
    return f"ws://{host}:8927/sendspin" if host else ""


class PairingRegistry:
    """One-time pairing codes with expiry; wrong attempts are bounded per source address and never touch the codes."""

    def __init__(self, clock=time.monotonic) -> None:
        self._clock = clock
        self._codes: dict[str, dict] = {}
        self._sources: dict[str, dict] = {}  # source -> {"attempts": int, "since": float}

    def issue(self, code: str, payload: dict) -> None:
        self._codes[code] = {"payload": payload, "expires": self._clock() + PAIRING_TTL_SECONDS}

    def cancel(self, code: str) -> None:
        self._codes.pop(code, None)

    def _purge(self, now: float) -> None:
        for key in [k for k, v in self._codes.items() if v["expires"] <= now]:
            self._codes.pop(key)
        for key in [k for k, v in self._sources.items() if now - v["since"] >= PAIRING_TTL_SECONDS]:
            self._sources.pop(key)

    def blocked(self, source: str) -> bool:
        now = self._clock()
        self._purge(now)
        entry = self._sources.get(source)
        if entry is not None:
            return entry["attempts"] >= PAIRING_MAX_ATTEMPTS
        return len(self._sources) >= PAIRING_MAX_SOURCES  # a full map: unknown sources wait for the window to purge

    def claim(self, code: str, source: str) -> dict | None:
        """Consumes and returns the pending payload for a valid code from a source under the attempt limit; wrong or expired codes count against the source only."""
        if self.blocked(source):
            return None
        entry = self._codes.get(code)
        if entry is not None:
            return self._codes.pop(code)["payload"]
        record = self._sources.setdefault(source, {"attempts": 0, "since": self._clock()})
        record["attempts"] += 1
        return None

    def pending(self, code: str) -> bool:
        entry = self._codes.get(code)
        return entry is not None and entry["expires"] > self._clock()
```

- [ ] **Krok 4:** `python -m pytest -q tests` → PASS.
- [ ] **Krok 5: commit** `feat(pairing): request validation, per-source attempt limit, sendspin url helper (SPEC 0.10 pkt 4.1, 6.2)`.

### A2 Harness testów komponentowych

**Pliki:** `requirements_test.txt` (nowy), `tests/conftest.py` (nowy), `tests/test_setup.py` (nowy), `.github/workflows/validate.yml`, `.gitignore` (`.venv314/`).

**Interfejs (produkuje):** fixture `hass` z HA 2026.8.3, `hass_client_no_auth`, `hass_ws_client`, `MockConfigEntry` (z `pytest_homeassistant_custom_component.common`), fixture `auto_enable_custom_integrations`.

- [ ] **Krok 1:** `requirements_test.txt`:

```
pytest-homeassistant-custom-component==0.13.357
pillow
music-assistant-client==1.4.3
```

`tests/conftest.py`:

```python
"""Component tests run against the real HA 2026.8.3 (pytest-homeassistant-custom-component pins it)."""

import sys
from unittest.mock import patch

import pytest


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    yield


if sys.platform == "win32":
    import pytest_socket

    # Windows: asyncio's selector loop is built on a socket pair, which the HA plugin's disable_socket() would refuse.
    # The block stays in force in CI (Linux); locally nothing else opens sockets in these tests.
    pytest_socket.disable_socket = lambda *args, **kwargs: None

    @pytest.fixture(scope="session")
    def mock_zeroconf_resolver():
        """Windows: pycares opens a socket while pytest-socket is armed; the resolver is never exercised by these tests."""
        with patch("homeassistant.helpers.aiohttp_client._async_make_resolver") as patcher:
            yield patcher
```

`tests/test_setup.py` (RED, bo helper jeszcze nie istnieje):

```python
from custom_components.helios import async_setup
from custom_components.helios.const import DOMAIN


async def test_setup_registers_both_views_once(hass):
    from homeassistant.setup import async_setup_component

    assert await async_setup_component(hass, "http", {})  # the bare hass fixture has no http app
    assert await async_setup(hass, {})
    assert await async_setup(hass, {})
    routes = [(r.method, r.resource.canonical) for r in hass.http.app.router.routes()]  # HA registers routes without names
    assert routes.count(("GET", "/api/helios/appearance/{entry_id}/{image_id}")) == 1
    assert routes.count(("GET", "/api/helios/pair")) == 1 and routes.count(("POST", "/api/helios/pair")) == 1
    assert hass.data[DOMAIN]["views_registered"] is True
```

- [ ] **Krok 2: środowisko lokalne** (`ha-helios`) - już przygotowane podczas planowania i sprawdzone (`7 passed` dla dzisiejszych testów): `uv venv --python 3.14 .venv314 && uv pip install --python .venv314 -r requirements_test.txt`; HA 2026.8.3 na Windows wymaga dwóch stubów w `.venv314/Lib/site-packages/` (`fcntl.py`: `LOCK_SH/EX/NB/UN` + no-op `flock/fcntl/ioctl`; `resource.py`: `RLIMIT_NOFILE`, `getrlimit`, no-op `setrlimit`) - poza repozytorium; w repozytorium `pytest.ini` (`asyncio_mode = auto`, `testpaths = tests`) i `tests/conftest.py` z blokiem `if sys.platform == "win32":` (nadpisanie session fixture `mock_zeroconf_resolver` na `patch("homeassistant.helpers.aiohttp_client._async_make_resolver")`, bo pycares otwiera gniazdo; `pytest_socket.disable_socket = lambda *a, **k: None`, bo pętla zdarzeń na Windows jest zbudowana na parze gniazd). W CI (Linux) blok nie działa, blokada gniazd obowiązuje. Uruchomić `.venv314/Scripts/python -m pytest -q tests` → `test_setup` FAIL (`api:helios:pair` = 0).
- [ ] **Krok 3: CI** - w `validate.yml` job `tests`: `python-version: "3.14"`, `pip install -r requirements_test.txt && python -m pytest -q tests`; job `import-check` zostaje. Zielony job `tests` na Linuksie (z prawdziwą blokadą gniazd) jest warunkiem wydania w A6 - lokalny przebieg na Windows nie zastępuje CI.
- [ ] **Krok 4:** test przechodzi po A4 krok 3 (helper `async_register_views`); w tym zadaniu commit samego harnessu z testem oznaczonym `@pytest.mark.xfail(strict=True, reason="A4")`, usuwanym w A4.
- [ ] **Krok 5: commit** `test: component test harness on HA 2026.8.3 (pytest-homeassistant-custom-component 0.13.357)`.

### A3 Tożsamość zegara i sekcja MA

**Pliki:** `custom_components/helios/identity.py` (nowy), `tests/test_identity.py` (nowy), `manifest.json` (`after_dependencies: ["music_assistant"]`, `dependencies` + `assist_pipeline`, `version` 0.8.0).

**Interfejs (produkuje):**
- `async def async_create_identity(hass, installation_id: str) -> tuple[str, str]` → `(user_id, access_token)`.
- `async def async_remove_identity(hass, user_id: str | None) -> None` (idempotentne; tylko `system_generated`; najpierw refresh tokeny).
- `def music_source(hass) -> tuple[str, str] | None` → `(url, token)` z załadowanego wpisu `music_assistant`.
- `async def async_create_music_section(hass, installation_id: str) -> dict | None` → `{"url", "token"}` (token MA zegara) albo `None`.
- `async def async_revoke_music_section(hass, section: dict | None) -> None` (logout tokenem zegara; best effort).
- `def connection_payload(hass, entry_data: dict, options: dict) -> dict` → zdarzenie `connection` (pipeline preferowany, `dashboard_path`, sekcja MA z `sendspin_url` = opcja albo wyliczony, `diagnostics_url` opcja albo `None`).
- `def preferred_pipeline(hass) -> str | None`.

- [ ] **Krok 1: testy (RED)** `tests/test_identity.py`:

```python
import pytest
from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_SYSTEM
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.helios import identity

INSTALLATION = "0f3c1b2a-9d8e-4c7b-a6f5-1e2d3c4b5a69"


async def test_identity_is_a_local_system_user_with_a_ten_year_token(hass):
    user_id, token = await identity.async_create_identity(hass, INSTALLATION)
    user = await hass.auth.async_get_user(user_id)
    assert user.system_generated and user.local_only and not user.is_admin
    assert user.name == "Helios 0f3c1b2a" and [g.id for g in user.groups] == [GROUP_ID_USER]
    refresh = next(iter(user.refresh_tokens.values()))
    assert refresh.token_type == TOKEN_TYPE_SYSTEM and refresh.client_name == "Helios 0f3c1b2a"
    assert refresh.access_token_expiration.days == 3650
    assert hass.auth.async_validate_access_token(token).user.id == user_id  # @callback in 2026.8.3, not a coroutine


async def test_remove_identity_revokes_tokens_first_and_is_idempotent(hass):
    user_id, token = await identity.async_create_identity(hass, INSTALLATION)
    refresh = next(iter((await hass.auth.async_get_user(user_id)).refresh_tokens.values()))
    revoked = []
    hass.auth.async_register_revoke_token_callback(refresh.id, lambda: revoked.append(refresh.id))
    await identity.async_remove_identity(hass, user_id)
    assert revoked == [refresh.id], "the websocket revoke callback must fire (async_remove_user alone skips it)"
    assert await hass.auth.async_get_user(user_id) is None
    assert hass.auth.async_validate_access_token(token) is None
    await identity.async_remove_identity(hass, user_id)
    await identity.async_remove_identity(hass, None)


async def test_identity_creation_cleans_up_when_the_token_step_fails(hass, monkeypatch):
    async def boom(*a, **k):
        raise ValueError("no token")

    monkeypatch.setattr(hass.auth, "async_create_refresh_token", boom)
    with pytest.raises(ValueError):
        await identity.async_create_identity(hass, INSTALLATION)
    assert not [u for u in await hass.auth.async_get_users() if u.name == "Helios 0f3c1b2a"], "no half-made user survives"


async def test_remove_identity_never_touches_a_human_user(hass, hass_admin_user):
    await identity.async_remove_identity(hass, hass_admin_user.id)
    assert await hass.auth.async_get_user(hass_admin_user.id) is not None


def test_music_source_uses_the_first_loaded_entry_and_logs(hass, caplog):
    from homeassistant.config_entries import ConfigEntryState

    assert identity.music_source(hass) is None
    assert "Music Assistant" in caplog.text and "WARNING" in caplog.text
    caplog.clear()
    old = MockConfigEntry(domain="music_assistant", data={"url": "http://old:8095", "token": "old-token"})
    old.add_to_hass(hass)
    assert identity.music_source(hass) is None, "not loaded yet"
    new = MockConfigEntry(domain="music_assistant", data={"url": "http://new:8095", "token": "new-token"})
    new.add_to_hass(hass)
    new.mock_state(hass, ConfigEntryState.LOADED)
    assert identity.music_source(hass) == ("http://new:8095", "new-token"), "the first LOADED entry, not the first entry"
    old.mock_state(hass, ConfigEntryState.LOADED)
    caplog.clear()
    assert identity.music_source(hass) == ("http://old:8095", "old-token")
    assert "INFO" in caplog.text and "http://old:8095" in caplog.text and "old-token" not in caplog.text
    tokenless = MockConfigEntry(domain="music_assistant", data={"url": "http://x:8095"})
    for e in (old, new):
        e.mock_state(hass, ConfigEntryState.NOT_LOADED)
    tokenless.add_to_hass(hass)
    tokenless.mock_state(hass, ConfigEntryState.LOADED)
    caplog.clear()
    assert identity.music_source(hass) is None and "WARNING" in caplog.text


async def test_connection_payload_shape(hass):
    hass.config_entries.async_entries  # noqa: B018 - hass fixture ready
    payload = identity.connection_payload(hass, {"installation_id": INSTALLATION, "music_assistant": {"url": "http://ma:8095", "token": "clock-token"}}, {"diagnostics_url": "", "sendspin_url": ""})
    assert payload == {"type": "connection", "pipeline": identity.preferred_pipeline(hass), "dashboard_path": "helios-clock",
                       "music_assistant": {"url": "http://ma:8095", "token": "clock-token", "sendspin_url": "ws://ma:8927/sendspin"}, "diagnostics_url": None}
    payload = identity.connection_payload(hass, {"installation_id": INSTALLATION}, {"diagnostics_url": "http://pc:8757/x/events", "sendspin_url": "ws://other:8927/sendspin"})
    assert payload["music_assistant"] is None and payload["diagnostics_url"] == "http://pc:8757/x/events"


async def test_music_section_is_created_and_revoked_through_the_ma_client(hass, monkeypatch):
    created, revoked = [], []

    class FakeAuth:
        def __init__(self, token):
            self.token = token

        async def create_token(self, name):
            created.append((self.token, name))
            return "clock-token"

        async def logout(self):
            revoked.append(self.token)

    class FakeClient:
        def __init__(self, server_url, aiohttp_session, token=None, ssl_context=None, locale=None):  # the 1.4.3 signature
            self.auth = FakeAuth(token)
            self.connected = False

        async def __aenter__(self):
            self.connected = True  # 1.4.3: __aenter__ connects; auth commands need the connection
            return self

        async def __aexit__(self, *a):
            return False

    monkeypatch.setattr(identity, "_client", lambda hass, url, token: FakeClient(url, None, token))
    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://ma:8095", "ma-token"))
    section = await identity.async_create_music_section(hass, INSTALLATION)
    assert section == {"url": "http://ma:8095", "token": "clock-token"}
    assert created[0][0] == "ma-token" and created[0][1].startswith("Helios 0f3c1b2a ") and len(created[0][1].split()[-1]) == 6
    await identity.async_revoke_music_section(hass, section)
    assert revoked == ["clock-token"]
    await identity.async_revoke_music_section(hass, None)


async def test_client_factory_matches_the_installed_music_assistant_client(hass):  # async: the aiohttp session needs a running loop
    from music_assistant_client import MusicAssistantClient

    client = identity._client(hass, "http://ma:8095", "ma-token")
    assert isinstance(client, MusicAssistantClient) and hasattr(client, "auth")
    assert hasattr(client, "__aenter__") and hasattr(client.auth, "create_token") and hasattr(client.auth, "logout")


async def test_music_section_is_none_without_ma_or_on_errors(hass, monkeypatch):
    assert await identity.async_create_music_section(hass, INSTALLATION) is None
    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://ma:8095", "ma-token"))

    class Broken:
        async def __aenter__(self):
            raise OSError("down")

        async def __aexit__(self, *a):
            return False

    monkeypatch.setattr(identity, "_client", lambda hass, url, token: Broken())
    assert await identity.async_create_music_section(hass, INSTALLATION) is None


@pytest.mark.parametrize("failure", ["timeout", "cancel"])
async def test_ambiguous_create_token_revokes_only_this_attempt(hass, monkeypatch, failure):
    import asyncio
    from types import SimpleNamespace

    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://ma:8095", "ma-token"))
    tokens, revoked = [SimpleNamespace(token_id="t-foreign", name="Helios 0f3c1b2a a1b2c3")], []

    class Auth:
        async def create_token(self, name):
            tokens.append(SimpleNamespace(token_id="t-ours", name=name))  # the server acted...
            if failure == "timeout":
                raise TimeoutError()  # ...but the answer never came
            raise asyncio.CancelledError()  # ...and the surrounding transaction was cancelled

        async def get_tokens(self):
            return list(tokens)

        async def revoke_token(self, token_id):
            revoked.append(token_id)

    class Client:
        auth = Auth()

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

    monkeypatch.setattr(identity, "_client", lambda hass, url, token: Client())
    if failure == "cancel":
        with pytest.raises(asyncio.CancelledError):
            await identity.async_create_music_section(hass, INSTALLATION)
    else:
        assert await identity.async_create_music_section(hass, INSTALLATION) is None
    assert revoked == ["t-ours"], "the foreign token with the same prefix stays"
```

- [ ] **Krok 2:** `pytest -q tests/test_identity.py` → FAIL (`ImportError`).
- [ ] **Krok 3: implementacja** `identity.py`:

```python
"""Per-clock HA identity (system user + system token) and the clock's Music Assistant token (SPEC 0.10 pkt 5, 6)."""

from __future__ import annotations

import asyncio
import logging
import secrets
from datetime import timedelta

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_SYSTEM
from homeassistant.config_entries import ConfigEntryState
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import DASHBOARD_PATH, MA_TIMEOUT_SECONDS, sendspin_url_for

_LOGGER = logging.getLogger(__name__)
TOKEN_LIFETIME = timedelta(days=3650)


def _label(installation_id: str) -> str:
    return f"Helios {installation_id[:8]}"


async def async_create_identity(hass: HomeAssistant, installation_id: str) -> tuple[str, str]:
    """A non-admin, LAN-only system user with one never-expiring system refresh token; returns (user_id, access token JWT)."""
    user = await hass.auth.async_create_system_user(_label(installation_id), group_ids=[GROUP_ID_USER], local_only=True)
    try:
        refresh = await hass.auth.async_create_refresh_token(user, client_name=_label(installation_id), token_type=TOKEN_TYPE_SYSTEM, access_token_expiration=TOKEN_LIFETIME)
        return user.id, hass.auth.async_create_access_token(refresh)
    except BaseException:
        await hass.auth.async_remove_user(user)  # never leave a half-made identity behind
        raise


async def async_remove_identity(hass: HomeAssistant, user_id: str | None) -> None:
    """Removes the clock's system user; refresh tokens first so open websockets are closed (async_remove_user skips those callbacks). Never a human user."""
    if not user_id:
        return
    user = await hass.auth.async_get_user(user_id)
    if user is None or not user.system_generated:
        return
    for token in list(user.refresh_tokens.values()):
        hass.auth.async_remove_refresh_token(token)
    await hass.auth.async_remove_user(user)


def music_source(hass: HomeAssistant) -> tuple[str, str] | None:
    """url and token of the first LOADED core music_assistant entry (SPEC 0.10 pkt 6.1); None with one warning when there is none."""
    loaded = [e for e in hass.config_entries.async_entries("music_assistant") if e.state is ConfigEntryState.LOADED]
    if not loaded:
        _LOGGER.warning("Brak załadowanej integracji Music Assistant - zegar bez muzyki")
        return None
    entry = loaded[0]
    if len(loaded) > 1:
        _LOGGER.info("Kilka wpisów Music Assistant, Helios używa %s", entry.data.get("url"))
    if not (entry.data.get("url") and entry.data.get("token")):
        _LOGGER.warning("Wpis Music Assistant bez tokena (schemat < 28) - zegar bez muzyki")
        return None
    return entry.data["url"], entry.data["token"]


def _client(hass: HomeAssistant, url: str, token: str):
    from music_assistant_client import MusicAssistantClient  # noqa: PLC0415 - optional: installed through after_dependencies

    return MusicAssistantClient(url, async_get_clientsession(hass), token)  # TLS verified like everywhere else (SPEC 0.10 pkt 9)


async def async_create_music_section(hass: HomeAssistant, installation_id: str) -> dict | None:
    """A clock-specific MA token minted with the core integration's token; None (with one warning) when MA is absent or fails."""
    source = music_source(hass)
    if source is None:
        return None
    url, token = source
    name = f"{_label(installation_id)} {secrets.token_hex(3)}"
    sent = False
    try:
        async with asyncio.timeout(MA_TIMEOUT_SECONDS), _client(hass, url, token) as client:
            sent = True  # from here on the server may have acted even if we never see the answer
            clock_token = await client.auth.create_token(name)
        return {"url": url, "token": clock_token}
    except ImportError:
        _LOGGER.warning("music_assistant_client nie jest zainstalowany - zegar bez muzyki")
    except (Exception, asyncio.CancelledError) as err:  # CancelledError: the pairing transaction timed out around us
        _LOGGER.warning("Nie udało się utworzyć tokena MA dla %s: %s", _label(installation_id), type(err).__name__)
        if sent:
            await _revoke_by_name(hass, url, token, name)  # bounded (MA_TIMEOUT_SECONDS), safe after a delivered cancel
        if isinstance(err, asyncio.CancelledError):
            raise
    return None


async def _revoke_by_name(hass: HomeAssistant, url: str, token: str, name: str) -> None:
    """After an ambiguous create_token (timeout after the server acted) the token of this attempt is found by its unique name and revoked."""
    try:
        async with asyncio.timeout(MA_TIMEOUT_SECONDS), _client(hass, url, token) as client:
            for item in await client.auth.get_tokens():
                if item.name == name:
                    await client.auth.revoke_token(item.token_id)
    except Exception:  # noqa: BLE001
        pass


async def async_revoke_music_section(hass: HomeAssistant, section: dict | None) -> None:
    """logout() with the clock's own token revokes exactly that token; best effort."""
    if not section or not section.get("token"):
        return
    try:
        async with asyncio.timeout(MA_TIMEOUT_SECONDS), _client(hass, section["url"], section["token"]) as client:
            await client.auth.logout()
    except Exception as err:  # noqa: BLE001
        _LOGGER.warning("Nie udało się unieważnić tokena MA zegara: %s", type(err).__name__)


def preferred_pipeline(hass: HomeAssistant) -> str | None:
    from homeassistant.components.assist_pipeline import async_get_pipeline  # noqa: PLC0415

    try:
        return async_get_pipeline(hass).id
    except Exception:  # noqa: BLE001 - no preferred pipeline / assist_pipeline not ready
        return None


def connection_payload(hass: HomeAssistant, entry_data: dict, options: dict) -> dict:
    section = entry_data.get("music_assistant")
    music = None
    if section:
        music = {"url": section["url"], "token": section["token"], "sendspin_url": options.get("sendspin_url") or sendspin_url_for(section["url"])}
    return {"type": "connection", "pipeline": preferred_pipeline(hass), "dashboard_path": DASHBOARD_PATH, "music_assistant": music, "diagnostics_url": options.get("diagnostics_url") or None}
```

`manifest.json`: `"dependencies": ["assist_pipeline", "file_upload", "http", "websocket_api"]`, `"after_dependencies": ["music_assistant"]`, `"version": "0.8.0"`.

- [ ] **Krok 4:** `pytest -q tests/test_identity.py` → PASS.
- [ ] **Krok 5: commit** `feat(identity): per-clock system user and token, MA token for the clock, connection payload (SPEC 0.10 pkt 5, 6)`.

### A4 Endpoint parowania i transakcja

**Pliki:** `http.py`, `config_flow.py`, `__init__.py`, `tests/test_pairing.py` (nowy), `tests/test_setup.py` (zdjąć `xfail`).

**Interfejs (produkuje):**
- `http.async_register_views(hass)` - obie klasy, raz (`hass.data[DOMAIN]["views_registered"]`); `hass.data[DOMAIN]` inicjalizowane przez `const`-owy `_data(hass)`: `{"pairing", "entries", "locks", "pairing_active", "cancelled_users", "views_registered", "ws_registered"}`.
- `HeliosPairView`: `url="/api/helios/pair"`, `name="api:helios:pair"`, `requires_auth=False`, `get` → `{"protocol": 2}`, `post` → transakcja SPEC pkt 4.1.
- Payload kodu w rejestrze: `{"future", "done", "flow_id", "cancelled": False, "existing": entry | None}`; `future` dostaje dane wpisu tylko dla nowego wpisu; dla istniejącego handler robi `async_update_entry` + `async_reload` sam, a `future` dostaje `{"reconfigured": True}` (flow → abort `reconfigured`).
- `__init__.async_setup_entry`: wpis z `user_id` w `cancelled_users` usuwa sam siebie i zwraca `False`.
- `__init__.async_remove_entry`: `async_remove_identity` + `async_revoke_music_section` + katalog obrazów; czyści `cancelled_users`.

- [ ] **Krok 1: testy (RED)** `tests/test_pairing.py`:

```python
import asyncio
import json

import pytest
from homeassistant.config_entries import ConfigEntryState
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.helios import identity
from custom_components.helios.const import DOMAIN, PAIRING_MAX_ATTEMPTS

INSTALLATION = "0f3c1b2a-9d8e-4c7b-a6f5-1e2d3c4b5a69"
BODY = {"installation_id": INSTALLATION, "code": None, "app_version": "0.9.0", "version_code": 28}


async def start_flow(hass):
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": "user"})
    assert result["type"] == "progress"
    return result["flow_id"], result["description_placeholders"]["code"]


async def frontend(hass, flow_id):
    """When the progress task ends, HA only moves the flow to progress_done (data_entry_flow.py: the done callback calls the
    private single-step _async_configure); the HA frontend then continues to `finish`. Tests play the frontend."""
    from homeassistant.data_entry_flow import FlowResultType

    for _ in range(600):
        flow = hass.config_entries.flow._progress.get(flow_id)  # noqa: SLF001
        if flow is None:
            return
        if flow.cur_step and flow.cur_step["type"] is FlowResultType.SHOW_PROGRESS_DONE:
            try:
                await hass.config_entries.flow.async_configure(flow_id)
            except Exception:  # noqa: BLE001 - aborted meanwhile
                pass
            return
        await asyncio.sleep(0.05)


async def post(hass, client, flow_id, code, **overrides):
    """POST with the frontend running alongside (a wrong code never reaches progress_done, the task simply ends)."""
    body = {**BODY, "code": code, **overrides}
    ui = asyncio.ensure_future(frontend(hass, flow_id))
    try:
        return await client.post("/api/helios/pair", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    finally:
        ui.cancel()


async def test_get_is_a_bare_capability_probe(hass, hass_client_no_auth):
    flow_id, _ = await start_flow(hass)
    client = await hass_client_no_auth()
    response = await client.get("/api/helios/pair")
    assert response.status == 200 and await response.json() == {"protocol": 2}


async def test_pairing_creates_entry_identity_and_a_usable_token(hass, hass_client_no_auth, hass_ws_client):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    response = await post(hass, client, flow_id, code)
    assert response.status == 200
    body = await response.json()
    assert set(body) == {"protocol", "token", "pipeline", "dashboard_path"} and body["protocol"] == 2 and body["dashboard_path"] == "helios-clock"
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    assert entry.unique_id == INSTALLATION and entry.state is ConfigEntryState.LOADED
    user = await hass.auth.async_get_user(entry.data["user_id"])
    assert user.system_generated and not user.is_admin
    ws = await hass_ws_client(hass, access_token=body["token"])
    await ws.send_json({"id": 1, "type": "helios/connect", "protocol": 2, "installation_id": INSTALLATION, "app_version": "0.9.0", "version_code": 28})
    assert (await ws.receive_json())["success"] is True
    assert (await post(hass, client, flow_id, code)).status == 401, "a code is single use"


async def test_wrong_codes_are_401_and_the_source_is_blocked_after_five(hass, hass_client_no_auth, caplog, monkeypatch):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    registry = hass.data[DOMAIN]["pairing"]
    claims = []
    real_claim = registry.claim
    monkeypatch.setattr(registry, "claim", lambda c, source: (claims.append(c), real_claim(c, source))[1])
    for _ in range(PAIRING_MAX_ATTEMPTS):
        assert (await post(hass, client, flow_id, "000000")).status == 401
    assert (await post(hass, client, flow_id, code)).status == 401, "blocked source, right code"
    assert claims == ["000000"] * PAIRING_MAX_ATTEMPTS, "the sixth request never reaches claim()"
    assert registry.pending(code), "the code itself survives"
    assert code not in caplog.text and "odrzucone parowanie" in caplog.text


async def test_chunked_and_oversized_bodies(hass, hass_client_no_auth, monkeypatch):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    registry = hass.data[DOMAIN]["pairing"]
    payload = json.dumps({**BODY, "code": code}).encode()

    async def chunks():
        for i in range(0, len(payload), 7):
            yield payload[i : i + 7]

    response = await client.post("/api/helios/pair", data=chunks(), headers={"Content-Type": "application/json"})  # aiohttp sends this chunked
    assert response.status == 200, "a chunked body is read to EOF before parsing"

    def boom(*a, **k):
        raise AssertionError("claim() must not run for an oversized body")

    monkeypatch.setattr(registry, "claim", boom)

    async def big():
        yield b'{"installation_id":"' + b"a" * 5000 + b'"}'

    assert (await client.post("/api/helios/pair", data=big(), headers={"Content-Type": "application/json"})).status == 400


async def test_bad_bodies_are_400(hass, hass_client_no_auth):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    assert (await client.post("/api/helios/pair", data=b"{", headers={"Content-Type": "application/json"})).status == 400
    assert (await client.post("/api/helios/pair", data=b"x" * 5000, headers={"Content-Type": "application/json"})).status == 400
    assert (await post(hass, client, flow_id, "12345")).status == 400
    assert (await client.post("/api/helios/pair", data=b"[]", headers={"Content-Type": "application/json"})).status == 400


async def test_repairing_replaces_identity_only_after_success(hass, hass_client_no_auth):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    first = await (await post(hass, client, flow_id, code)).json()
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    old_user = entry.data["user_id"]
    flow_id2, code2 = await start_flow(hass)
    second = await (await post(hass, client, flow_id2, code2)).json()
    assert second["token"] != first["token"]
    assert len(hass.config_entries.async_entries(DOMAIN)) == 1 and entry.data["user_id"] != old_user
    await hass.async_block_till_done()  # _retire runs as a task after the answer
    assert await hass.auth.async_get_user(old_user) is None
    assert hass.auth.async_validate_access_token(second["token"]) is not None


async def test_failed_reload_restores_the_old_identity(hass, hass_client_no_auth, monkeypatch):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    first = await (await post(hass, client, flow_id, code)).json()
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    old_user, old_data = entry.data["user_id"], dict(entry.data)
    flow_id2, code2 = await start_flow(hass)
    real_reload = hass.config_entries.async_reload
    calls = []

    async def failing_reload(entry_id):
        calls.append(entry_id)
        if len(calls) == 1:
            return False
        return await real_reload(entry_id)

    monkeypatch.setattr(hass.config_entries, "async_reload", failing_reload)
    assert (await post(hass, client, flow_id2, code2)).status == 503
    assert calls == [entry.entry_id, entry.entry_id], "the restoring reload is awaited inside the transaction"
    assert entry.data == old_data and entry.state is ConfigEntryState.LOADED
    assert await hass.auth.async_get_user(old_user) is not None
    assert hass.auth.async_validate_access_token(first["token"]) is not None
    users = [u for u in await hass.auth.async_get_users() if u.system_generated and u.name.startswith("Helios")]
    assert [u.id for u in users] == [old_user], "the new identity of the failed attempt is gone"


async def test_setup_failure_of_the_new_data_restores_a_working_old_entry(hass, hass_client_no_auth, monkeypatch):
    """The realistic failure: the reload really unloads the entry and async_setup_entry raises for the new data."""
    import custom_components.helios as component

    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    first = await (await post(hass, client, flow_id, code)).json()
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    old_user, old_data = entry.data["user_id"], dict(entry.data)
    flow_id2, code2 = await start_flow(hass)
    real_setup = component.async_setup_entry

    async def setup_once_broken(hass_, entry_):
        if entry_.data["user_id"] != old_user:
            raise RuntimeError("new data cannot load")
        return await real_setup(hass_, entry_)

    monkeypatch.setattr(component, "async_setup_entry", setup_once_broken)
    assert (await post(hass, client, flow_id2, code2)).status == 503
    await hass.async_block_till_done()
    assert entry.data == old_data and entry.state is ConfigEntryState.LOADED
    assert hass.data[DOMAIN]["entries"].get(entry.entry_id) is not None, "a live coordinator again"
    assert hass.auth.async_validate_access_token(first["token"]) is not None


async def test_hanging_restore_reload_is_bounded(hass, hass_client_no_auth, monkeypatch, caplog):
    from custom_components.helios import http as pair_http

    monkeypatch.setattr(pair_http, "PAIRING_TIMEOUT_SECONDS", 0.3)
    monkeypatch.setattr(pair_http, "ROLLBACK_TIMEOUT_SECONDS", 0.3)
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    await post(hass, client, flow_id, code)
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    flow_id2, code2 = await start_flow(hass)
    calls = []

    async def reload(entry_id):
        calls.append(entry_id)
        if len(calls) == 1:
            return False
        await asyncio.sleep(3600)

    monkeypatch.setattr(hass.config_entries, "async_reload", reload)
    started = asyncio.get_running_loop().time()
    assert (await post(hass, client, flow_id2, code2)).status == 503
    assert asyncio.get_running_loop().time() - started < 1.5, "transaction + restore + rollback share one arithmetic: 0.3 + 0.3 + 0.3 here, 30 + 10 + 10 in production, always under the clock's 60 s"
    assert "przywrócenie poprzedniego wpisu nie powiodło się" in caplog.text


async def test_cleanup_timeout_after_the_commit_point_keeps_the_new_identity(hass, hass_client_no_auth, monkeypatch):
    from custom_components.helios import http as pair_http

    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    await post(hass, client, flow_id, code)
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    old_user = entry.data["user_id"]
    flow_id2, code2 = await start_flow(hass)

    async def stuck_remove(hass_, user_id):
        await asyncio.sleep(3600)

    revoked = []

    async def fake_revoke(hass_, section):
        revoked.append(section)

    monkeypatch.setattr(pair_http.identity, "async_remove_identity", stuck_remove)
    monkeypatch.setattr(pair_http.identity, "async_revoke_music_section", fake_revoke)
    monkeypatch.setattr(pair_http, "RETIRE_TIMEOUT_SECONDS", 0.3)
    hass.config_entries.async_update_entry(entry, data={**entry.data, "music_assistant": {"url": "http://ma:8095", "token": "old-clock-token"}})
    started = asyncio.get_running_loop().time()
    response = await post(hass, client, flow_id2, code2)
    assert response.status == 200
    body = await response.json()
    await asyncio.sleep(0.5)
    await hass.async_block_till_done()
    assert asyncio.get_running_loop().time() - started < 1.5, "one shared budget (0.3 s here), not one per step"
    assert entry.data["user_id"] != old_user and entry.state is ConfigEntryState.LOADED
    assert hass.auth.async_validate_access_token(body["token"]) is not None, "past the commit point nothing rolls back"
    assert {"url": "http://ma:8095", "token": "old-clock-token"} in revoked, "the MA step runs even though the user step hung"


async def test_late_entry_creation_after_timeout_is_undone(hass, hass_client_no_auth, monkeypatch):
    from custom_components.helios import http as pair_http

    monkeypatch.setattr(pair_http, "FLOW_TIMEOUT_SECONDS", 0.2)
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    real_finish = hass.config_entries.flow.async_finish_flow

    async def slow_finish(flow, result):
        await asyncio.sleep(0.5)
        return await real_finish(flow, result)

    monkeypatch.setattr(hass.config_entries.flow, "async_finish_flow", slow_finish)  # ConfigEntriesFlowManager.async_finish_flow(flow, result), config_entries.py:1672 in 2026.8.3
    assert (await post(hass, client, flow_id, code)).status == 409
    await asyncio.sleep(0.6)
    await hass.async_block_till_done()
    assert hass.config_entries.async_entries(DOMAIN) == []
    assert not [u for u in await hass.auth.async_get_users() if u.system_generated and u.name.startswith("Helios")]
    assert hass.data[DOMAIN]["cancelled_users"] == {}, "the self-removing setup consumed the marker"


async def test_rollback_continues_when_one_step_fails(hass, hass_client_no_auth, monkeypatch, caplog):
    from custom_components.helios import http as pair_http

    monkeypatch.setattr(pair_http, "FLOW_TIMEOUT_SECONDS", 0.2)
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()

    async def never_finish(flow, result):
        await asyncio.sleep(3600)

    async def broken_remove(hass_, user_id):
        raise RuntimeError("store down")

    revoked = []

    async def fake_revoke(hass_, section):
        revoked.append(section)

    monkeypatch.setattr(hass.config_entries.flow, "async_finish_flow", never_finish)
    monkeypatch.setattr(pair_http.identity, "async_remove_identity", broken_remove)
    monkeypatch.setattr(pair_http.identity, "async_revoke_music_section", fake_revoke)
    monkeypatch.setattr(pair_http.identity, "async_create_music_section", lambda hass_, i: _coro({"url": "http://ma:8095", "token": "clock-token"}))
    assert (await post(hass, client, flow_id, code)).status == 409, "a controlled answer, never a 500"
    assert revoked == [{"url": "http://ma:8095", "token": "clock-token"}], "the later step still ran"
    assert "użytkownik" in caplog.text and "store down" not in caplog.text


async def _coro(value):
    return value


async def test_rollback_runs_all_steps_when_one_hangs(hass, hass_client_no_auth, monkeypatch):
    from custom_components.helios import http as pair_http

    monkeypatch.setattr(pair_http, "FLOW_TIMEOUT_SECONDS", 0.2)
    monkeypatch.setattr(pair_http, "ROLLBACK_TIMEOUT_SECONDS", 0.3)
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    gate = asyncio.Event()

    async def gated_finish(flow, result):
        await gate.wait()

    async def hung_remove(hass_, user_id):
        await asyncio.sleep(3600)

    revoked = []

    async def fake_revoke(hass_, section):
        revoked.append(section)

    monkeypatch.setattr(hass.config_entries.flow, "async_finish_flow", gated_finish)
    monkeypatch.setattr(pair_http.identity, "async_remove_identity", hung_remove)
    monkeypatch.setattr(pair_http.identity, "async_revoke_music_section", fake_revoke)
    monkeypatch.setattr(pair_http.identity, "async_create_music_section", lambda hass_, i: _coro({"url": "http://ma:8095", "token": "clock-token"}))
    started = asyncio.get_running_loop().time()
    assert (await post(hass, client, flow_id, code)).status == 409
    assert asyncio.get_running_loop().time() - started < 1.5
    assert revoked == [{"url": "http://ma:8095", "token": "clock-token"}], "the MA step ran although the user step hung"
    gate.set()


async def test_a_finish_completing_after_the_marker_ttl_still_cannot_keep_an_entry(hass, hass_client_no_auth, monkeypatch):
    from custom_components.helios import http as pair_http

    monkeypatch.setattr(pair_http, "FLOW_TIMEOUT_SECONDS", 0.2)
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    gate = asyncio.Event()
    real_finish = hass.config_entries.flow.async_finish_flow

    async def gated_finish(flow, result):
        await gate.wait()
        return await real_finish(flow, result)

    monkeypatch.setattr(hass.config_entries.flow, "async_finish_flow", gated_finish)
    assert (await post(hass, client, flow_id, code)).status == 409
    hass.data[DOMAIN]["cancelled_users"].clear()  # the TTL purge ran "an hour later"
    gate.set()  # ...and only now the flow manager finishes the entry
    await asyncio.sleep(0.2)
    await hass.async_block_till_done()
    assert hass.config_entries.async_entries(DOMAIN) == [], "an entry whose user is gone removes itself in async_setup_entry"


async def test_timeout_keeps_a_marker_until_the_finish_settles_and_stale_markers_are_purged(hass, hass_client_no_auth, monkeypatch):
    from custom_components.helios import http as pair_http

    monkeypatch.setattr(pair_http, "FLOW_TIMEOUT_SECONDS", 0.2)
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    gate = asyncio.Event()  # a controlled gate: the test decides when the manager may finish (a sleep would outlive the test)
    real_finish = hass.config_entries.flow.async_finish_flow

    async def gated_finish(flow, result):
        await gate.wait()
        return await real_finish(flow, result)

    monkeypatch.setattr(hass.config_entries.flow, "async_finish_flow", gated_finish)
    assert (await post(hass, client, flow_id, code)).status == 409
    markers = hass.data[DOMAIN]["cancelled_users"]
    assert len(markers) == 1 and hass.config_entries.async_entries(DOMAIN) == [], "the marker guards against a finish that may still complete"
    assert not [u for u in await hass.auth.async_get_users() if u.system_generated and u.name.startswith("Helios")]
    markers["stale-user"] = __import__("time").monotonic() - 3601
    assert (await post(hass, client, flow_id, "000000")).status == 401  # every POST purges aged markers first, the live one stays
    assert "stale-user" not in markers and len(markers) == 1
    gate.set()
    await hass.async_block_till_done()
    assert hass.config_entries.async_entries(DOMAIN) == [] and markers == {}, "the late entry removed itself and consumed the marker"


async def test_concurrent_pairing_of_the_same_clock_is_409_without_claiming(hass, hass_client_no_auth):
    flow_id, code = await start_flow(hass)
    flow_id2, code2 = await start_flow(hass)
    client = await hass_client_no_auth()
    hass.data[DOMAIN]["pairing_active"].add(INSTALLATION)
    assert (await post(hass, client, flow_id2, code2)).status == 409
    assert hass.data[DOMAIN]["pairing"].pending(code2)
    hass.data[DOMAIN]["pairing_active"].discard(INSTALLATION)


async def test_removing_the_entry_removes_identity_and_closes_the_socket(hass, hass_client_no_auth, hass_ws_client):
    flow_id, code = await start_flow(hass)
    client = await hass_client_no_auth()
    body = await (await post(hass, client, flow_id, code)).json()
    entry = hass.config_entries.async_entries(DOMAIN)[0]
    ws = await hass_ws_client(hass, access_token=body["token"])
    await ws.send_json({"id": 1, "type": "helios/connect", "protocol": 2, "installation_id": INSTALLATION, "app_version": "0.9.0", "version_code": 28})
    assert (await ws.receive_json())["success"] is True
    assert (await ws.receive_json())["event"]["type"] == "connected"  # the live channel is what the removal must tear down
    await hass.config_entries.async_remove(entry.entry_id)
    await hass.async_block_till_done()
    assert await hass.auth.async_get_user(entry.data["user_id"]) is None
    async with asyncio.timeout(3):
        while True:  # appearance, connection and removed may still be queued before the close
            msg = await ws.receive()
            if msg.type.name in ("CLOSE", "CLOSED", "CLOSING"):
                break
    await hass.config_entries.async_remove(entry.entry_id) if hass.config_entries.async_get_entry(entry.entry_id) else None


async def test_legacy_entry_keeps_its_human_user(hass, hass_admin_user):
    entry = MockConfigEntry(domain=DOMAIN, unique_id=INSTALLATION, data={"installation_id": INSTALLATION, "user_id": hass_admin_user.id, "app_version": "0.8.18", "version_code": 27})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.config_entries.async_remove(entry.entry_id)
    assert await hass.auth.async_get_user(hass_admin_user.id) is not None
```

- [ ] **Krok 2:** `pytest -q tests/test_pairing.py` → FAIL (404 na `/api/helios/pair`). (`frontend()` sięga do `flow._progress` - prywatne pole menedżera; alternatywa publiczna `async_progress_by_handler(DOMAIN)` nie zwraca `cur_step`, dlatego test czyta `_progress`, z `noqa`.)
- [ ] **Krok 3: implementacja.** `const.py` - helper danych:

```python
def new_domain_data() -> dict:
    return {"pairing": PairingRegistry(), "entries": {}, "locks": {}, "pairing_active": set(), "cancelled_users": {}, "views_registered": False, "ws_registered": False}
```

(`hass.data.setdefault(DOMAIN, new_domain_data())` we wszystkich miejscach, które dziś budują słownik ręcznie: `__init__.py`, `config_flow.py`, `HeliosOptionsFlow._save`).

`http.py` - dopisać:

```python
import asyncio
import json
import logging
import time

from homeassistant.core import HomeAssistant

from . import identity
from .const import (DASHBOARD_PATH, DOMAIN, FLOW_TIMEOUT_SECONDS, PAIR_BODY_LIMIT, PAIRING_TIMEOUT_SECONDS, PROTOCOL,
                    SETUP_TIMEOUT_SECONDS, new_domain_data, parse_pair_request)

_LOGGER = logging.getLogger(__name__)
RETIRE_TIMEOUT_SECONDS = 10
ROLLBACK_TIMEOUT_SECONDS = 10  # worst case: PAIRING_TIMEOUT (30) + restore reload (10) + rollback (10) = 50 s < the clock's 60 s read timeout
CANCELLED_USERS_TTL_SECONDS = 3600


def async_register_views(hass: HomeAssistant) -> None:
    """Both views, once; called from async_setup and from the config flow (the first pairing runs before async_setup)."""
    data = hass.data.setdefault(DOMAIN, new_domain_data())
    if data.get("views_registered"):
        return
    hass.http.register_view(HeliosAppearanceView())
    hass.http.register_view(HeliosPairView())
    data["views_registered"] = True


class PairingFailed(Exception):
    def __init__(self, status: int, error: str) -> None:
        super().__init__(error)
        self.status, self.error = status, error


class HeliosPairView(HomeAssistantView):
    """Unauthenticated: the clock has no token yet. GET is a capability probe, POST trades a one-time code for the clock's own token."""

    url = "/api/helios/pair"
    name = "api:helios:pair"
    requires_auth = False

    async def get(self, request: web.Request) -> web.Response:
        return self.json({"protocol": PROTOCOL})

    async def post(self, request: web.Request) -> web.Response:
        hass: HomeAssistant = request.app[KEY_HASS]
        data = hass.data.setdefault(DOMAIN, new_domain_data())
        source = request.remote or "?"
        now = time.monotonic()
        for stale in [u for u, at in data["cancelled_users"].items() if now - at > CANCELLED_USERS_TTL_SECONDS]:
            data["cancelled_users"].pop(stale, None)  # bounded growth; a finish cannot take an hour
        parsed = parse_pair_request(await _read_json(request))
        if parsed is None:
            return self.json({"error": "invalid_request"}, status_code=400)
        registry = data["pairing"]
        if registry.blocked(source):
            _LOGGER.warning("Helios: odrzucone parowanie z %s", source)
            return self.json({"error": "unauthorized"}, status_code=401)
        installation_id = parsed["installation_id"]
        if installation_id in data["pairing_active"]:
            return self.json({"error": "pairing_failed"}, status_code=409)
        data["pairing_active"].add(installation_id)
        try:
            lock = data["locks"].setdefault(installation_id, asyncio.Lock())
            async with lock:
                pending = registry.claim(parsed["code"], source)
                if pending is None:
                    _LOGGER.warning("Helios: odrzucone parowanie z %s", source)
                    return self.json({"error": "unauthorized"}, status_code=401)
                pending["existing"] = _entry_for(hass, installation_id)  # read under the lock: a re-pair in flight cannot slip in between
                try:
                    async with asyncio.timeout(PAIRING_TIMEOUT_SECONDS):
                        token, pipeline = await _pair(hass, data, pending, parsed)
                except PairingFailed as err:
                    return self.json({"error": err.error}, status_code=err.status)
                except TimeoutError:
                    return self.json({"error": "not_ready"}, status_code=503)
            _LOGGER.info("Helios %s sparowany z %s", installation_id[:8], source)
            return self.json({"protocol": PROTOCOL, "token": token, "pipeline": pipeline, "dashboard_path": DASHBOARD_PATH})
        finally:
            data["pairing_active"].discard(installation_id)
            lock = data["locks"].get(installation_id)
            if lock is not None and not lock.locked() and not lock._waiters:  # noqa: SLF001 - drop idle locks so random ids cannot grow the map
                data["locks"].pop(installation_id, None)


async def _read_json(request: web.Request) -> object:
    """Bounded read (also for chunked bodies), then JSON; anything over PAIR_BODY_LIMIT, slow or malformed is None."""
    chunks, size = [], 0
    try:
        async with asyncio.timeout(5):
            while True:
                chunk = await request.content.read(PAIR_BODY_LIMIT)
                if not chunk:
                    break
                size += len(chunk)
                if size > PAIR_BODY_LIMIT:
                    return None
                chunks.append(chunk)
        return json.loads(b"".join(chunks))
    except (TimeoutError, ValueError, UnicodeDecodeError):
        return None


async def _pair(hass: HomeAssistant, data: dict, pending: dict, parsed: dict) -> tuple[str, str]:
    """SPEC 0.10 pkt 4.1 steps 3-5: new identity first, the entry, then (past the commit point) the old identity goes."""
    installation_id = parsed["installation_id"]
    pipeline = identity.preferred_pipeline(hass)
    if pipeline is None:
        await _rollback(hass, data, pending, None, None, pending["existing"] is None)  # the code is spent: the flow ends now
        raise PairingFailed(503, "not_ready")
    user_id = music = None
    existing = pending["existing"]
    previous_user = previous_music = None
    try:
        user_id, token = await identity.async_create_identity(hass, installation_id)
        music = await identity.async_create_music_section(hass, installation_id)
        entry_data = {"installation_id": installation_id, "user_id": user_id, "app_version": parsed["app_version"], "version_code": parsed["version_code"], "music_assistant": music}
        if existing is not None:
            previous = dict(existing.data)
            previous_user, previous_music = previous.get("user_id"), previous.get("music_assistant")
            hass.config_entries.async_update_entry(existing, data={**previous, **entry_data})
            ok = False
            try:
                ok = await hass.config_entries.async_reload(existing.entry_id)
            finally:
                if not ok:
                    # the restoring reload is part of the transaction (criterion 15: a working old entry before the answer), with its own bound
                    hass.config_entries.async_update_entry(existing, data=previous)
                    try:
                        async with asyncio.timeout(ROLLBACK_TIMEOUT_SECONDS):  # its own bound, also after the transaction timeout already fired
                            restored = await hass.config_entries.async_reload(existing.entry_id)
                    except TimeoutError:
                        restored = False
                    if not restored:
                        _LOGGER.error("Helios %s: przywrócenie poprzedniego wpisu nie powiodło się", installation_id[:8])
            if not ok:
                raise PairingFailed(503, "not_ready")
            if not pending["future"].done():
                pending["future"].set_result({"reconfigured": True})
        else:
            if not pending["future"].done():
                pending["future"].set_result(entry_data)
            try:
                async with asyncio.timeout(FLOW_TIMEOUT_SECONDS):
                    await pending["done"].wait()
                    while any(f["flow_id"] == pending["flow_id"] for f in hass.config_entries.flow.async_progress_by_handler(DOMAIN)):
                        await asyncio.sleep(0.1)  # the flow manager finishes async_create_entry after the step returns
            except TimeoutError as err:
                raise PairingFailed(409, "pairing_failed") from err
            for _ in range(int(SETUP_TIMEOUT_SECONDS / 0.2)):
                entry = _entry_by_user(hass, user_id)
                if entry is not None and entry.state is ConfigEntryState.LOADED:
                    break
                await asyncio.sleep(0.2)
            else:
                raise PairingFailed(503, "not_ready")
    except PairingFailed:
        await _rollback(hass, data, pending, user_id, music, existing is None)
        raise
    except BaseException as err:
        await _rollback(hass, data, pending, user_id, music, existing is None)
        if isinstance(err, TimeoutError | asyncio.CancelledError):
            raise
        _LOGGER.warning("Helios %s: parowanie nieudane: %s", installation_id[:8], type(err).__name__)
        raise PairingFailed(503, "not_ready") from err
    # commit point: the new entry is loaded, nothing below may fail the request
    hass.async_create_task(_retire(hass, previous_user if previous_user != user_id else None, previous_music))
    return token, pipeline


def _entry_by_user(hass: HomeAssistant, user_id: str):
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.data.get("user_id") == user_id:
            return entry
    return None


async def _rollback(hass: HomeAssistant, data: dict, pending: dict, user_id: str | None, music: dict | None, new_entry: bool) -> None:
    """Own budget: runs after the transaction timeout may already have fired, so nothing here may hang."""
    pending["cancelled"] = True
    if new_entry and user_id:
        # async_abort() only forgets the flow; a finish that is already running may still create the entry later.
        # The marker stays until async_setup_entry consumes it or it ages out (purged in the view, 1 h).
        data["cancelled_users"][user_id] = time.monotonic()  # belt; the braces: async_setup_entry also drops any entry whose user no longer exists
    try:
        hass.config_entries.flow.async_abort(pending["flow_id"])
    except Exception:  # noqa: BLE001 - already finished
        pass
    names, steps = [], []
    if user_id:
        entry = _entry_by_user(hass, user_id)
        if entry is not None:
            names.append("wpis"); steps.append(hass.config_entries.async_remove(entry.entry_id))
        names.append("użytkownik"); steps.append(identity.async_remove_identity(hass, user_id))
    names.append("token MA"); steps.append(identity.async_revoke_music_section(hass, music))
    try:
        async with asyncio.timeout(ROLLBACK_TIMEOUT_SECONDS):
            # all at once and independently (like _retire): a hung entry removal never starves the user or the MA token;
            # the removals are idempotent, so async_remove_entry's own cleanup running alongside is harmless
            results = await asyncio.gather(*steps, return_exceptions=True)
    except TimeoutError:
        _LOGGER.error("Helios: sprzątanie nieudanego parowania przekroczyło %s s", ROLLBACK_TIMEOUT_SECONDS)
        return
    for name, result in zip(names, results, strict=True):
        if isinstance(result, BaseException):
            _LOGGER.warning("Helios: sprzątanie nieudanego parowania (%s): %s", name, type(result).__name__)


async def _retire(hass: HomeAssistant, user_id: str | None, music: dict | None) -> None:
    """Past the commit point: best effort, both steps independently under one 10 s budget, warnings only."""
    names = ("użytkownik", "token MA")
    steps = (identity.async_remove_identity(hass, user_id), identity.async_revoke_music_section(hass, music))
    try:
        async with asyncio.timeout(RETIRE_TIMEOUT_SECONDS):
            results = await asyncio.gather(*steps, return_exceptions=True)  # a hung step never blocks the other one
    except TimeoutError:
        _LOGGER.warning("Poprzednia tożsamość zegara nie została w pełni usunięta w %s s", RETIRE_TIMEOUT_SECONDS)
        return
    for name, result in zip(names, results, strict=True):
        if isinstance(result, BaseException):
            _LOGGER.warning("Poprzednia tożsamość zegara nie została w pełni usunięta (%s): %s", name, type(result).__name__)
```

(`from homeassistant.config_entries import ConfigEntryState` i `from .websocket import _entry_for` u góry.)

`config_flow.py`:
- `async_step_user`: `data = self.hass.data.setdefault(DOMAIN, new_domain_data())`; `websocket.async_register` jak dziś; dodatkowo `http.async_register_views(self.hass)`; payload kodu `{"future", "done", "flow_id": self.flow_id, "cancelled": False, "existing": None}` - `existing` wyliczane przy wystawieniu kodu? Nie: kod nie zna zegara. `existing` wyznacza handler HTTP: przed `_pair` `pending["existing"] = _entry_for(hass, installation_id)` (przenieść `_entry_for` z `websocket.py` do `const`-free helpera w `__init__`? Zostaje w `websocket.py`, `http.py` importuje `from .websocket import _entry_for`). Handler ustawia `existing` po `claim`, przed `_pair`.
- `async_step_finish`:

```python
    async def async_step_finish(self, user_input=None) -> ConfigFlowResult:
        self._registry.cancel(self._code)
        if self._timed_out or self._future is None or not self._future.done() or self._pending["cancelled"]:
            self._release()
            return self.async_abort(reason="timeout")
        data = self._future.result()
        if data.get("reconfigured"):
            self._release()
            return self.async_abort(reason="reconfigured")
        await self.async_set_unique_id(data["installation_id"])
        self._abort_if_unique_id_configured()
        result = self.async_create_entry(title=f"Helios {data['installation_id'][:8]}", data=data)
        self._release()
        return result
```

(`self._pending` = słownik przekazany do `issue`, trzymany w atrybucie; `_abort_if_unique_id_configured` chroni przed duplikatem, gdyby wpis powstał w międzyczasie - handler i tak sprawdza `existing` pod blokadą.)

`__init__.py`:

```python
async def async_setup(hass, config):
    data = hass.data.setdefault(DOMAIN, new_domain_data())
    if not data.get("ws_registered"):
        websocket.async_register(hass)
        data["ws_registered"] = True
    async_register_views(hass)
    return True


async def async_setup_entry(hass, entry):
    data = hass.data.setdefault(DOMAIN, new_domain_data())
    user_id = entry.data.get("user_id")
    if user_id in data["cancelled_users"] or await hass.auth.async_get_user(user_id) is None:
        # the pairing that created this entry was rolled back after its flow had already finished: either the marker is
        # still there, or (a finish that took longer than the marker's TTL) the identity is already gone - an entry whose
        # user does not exist can never authenticate, so it removes itself either way
        data["cancelled_users"].pop(user_id, None)
        hass.async_create_task(hass.config_entries.async_remove(entry.entry_id))
        return False
    repaired = await hass.async_add_executor_job(ap.repair_storage, hass.config.path("helios", entry.entry_id), dict(entry.options))
    if repaired != dict(entry.options):
        hass.config_entries.async_update_entry(entry, options=repaired)
    coordinator = HeliosCoordinator(hass, entry)
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, coordinator.installation_id)},
        manufacturer="Lenovo",
        model="Smart Clock 2",
        name=entry.title,
        sw_version=entry.data.get("app_version"),
    )
    data["entries"][entry.entry_id] = coordinator
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    return True


async def _options_updated(hass, entry):
    """Options saved (appearance, sendspin_url, diagnostics_url): push both snapshots over the live subscription; no reload."""
    coordinator: HeliosCoordinator | None = hass.data[DOMAIN]["entries"].get(entry.entry_id)
    if coordinator is not None:
        coordinator.send_appearance()
        coordinator.send_connection(identity.connection_payload(hass, dict(entry.data), dict(entry.options)))


async def async_remove_entry(hass, entry):
    data = hass.data.setdefault(DOMAIN, new_domain_data())
    data["cancelled_users"].pop(entry.data.get("user_id"), None)
    await identity.async_remove_identity(hass, entry.data.get("user_id"))
    await identity.async_revoke_music_section(hass, entry.data.get("music_assistant"))
    await hass.async_add_executor_job(shutil.rmtree, hass.config.path("helios", entry.entry_id), True)
```

`coordinator.py`:

```python
    @callback
    def send_connection(self, payload: dict) -> None:
        if self.connection is None:
            return
        try:
            self.connection.send_event(self.sub_id, payload)
        except Exception:  # noqa: BLE001
            pass
```

- [ ] **Krok 4:** `pytest -q tests` → PASS (w tym `test_setup` bez `xfail`). `async_finish_flow(flow, result)` jest metodą `ConfigEntriesFlowManager` w 2026.8.3 (`homeassistant/config_entries.py:1672`), więc `monkeypatch.setattr(hass.config_entries.flow, "async_finish_flow", …)` jest deterministyczne.
- [ ] **Krok 5: commit** `feat(pairing): unauthenticated /api/helios/pair with a transactional identity swap (SPEC 0.10 pkt 4.1, 5)`.

### A5 Kanał: protokół 2, bez kodu, zdarzenie `connection`, opcje

**Pliki:** `websocket.py`, `config_flow.py` (opcje), `translations/pl.json` i `en.json` (etykiety pól `sendspin_url`, `diagnostics_url`), `tests/test_channel.py` (nowy).

**Interfejs:** `helios/connect` bez `pairing_code`; `protocol` ∈ `PROTOCOLS`; po `appearance` → `connection`; gdy wpis bez sekcji MA i `music_source(hass)` istnieje → pod `locks[installation_id]` ponowna kontrola właściciela, `async_create_music_section`, `async_update_entry` (nieudany zapis → `async_revoke_music_section`); przy zmianie `url` źródła MA względem sekcji → unieważnienie i nowa sekcja. Opcje: pola `sendspin_url` (`str`, domyślnie `""`) i `diagnostics_url` (`str`, `""`) w tym samym formularzu `init`; `_save` zapisuje je obok `appearance`.

- [ ] **Krok 1: testy (RED)** `tests/test_channel.py`:

```python
import pytest
from homeassistant.config_entries import ConfigEntryState
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.helios import identity
from custom_components.helios.const import DOMAIN

INSTALLATION = "0f3c1b2a-9d8e-4c7b-a6f5-1e2d3c4b5a69"


async def paired(hass):
    user_id, token = await identity.async_create_identity(hass, INSTALLATION)
    entry = MockConfigEntry(domain=DOMAIN, unique_id=INSTALLATION, data={"installation_id": INSTALLATION, "user_id": user_id, "app_version": "0.9.0", "version_code": 28, "music_assistant": None})
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    return entry, token


async def connect(ws, protocol=2, msg_id=1):
    await ws.send_json({"id": msg_id, "type": "helios/connect", "protocol": protocol, "installation_id": INSTALLATION, "app_version": "0.9.0", "version_code": 28})
    result = await ws.receive_json()
    events = []
    if result["success"]:
        for _ in range(3):
            events.append((await ws.receive_json())["event"])
    return result, events


async def test_connect_accepts_protocol_1_and_2_and_sends_connection(hass, hass_ws_client):
    entry, token = await paired(hass)
    for protocol in (1, 2):
        ws = await hass_ws_client(hass, access_token=token)
        result, events = await connect(ws, protocol)
        assert result["success"], protocol
        assert [e["type"] for e in events] == ["connected", "appearance", "connection"]
        assert events[2]["music_assistant"] is None and events[2]["dashboard_path"] == "helios-clock" and events[2]["diagnostics_url"] is None


async def test_pairing_code_is_no_longer_part_of_connect(hass, hass_ws_client):
    entry, token = await paired(hass)
    ws = await hass_ws_client(hass, access_token=token)
    await ws.send_json({"id": 1, "type": "helios/connect", "protocol": 2, "installation_id": INSTALLATION, "app_version": "0.9.0", "version_code": 28, "pairing_code": "123456"})
    assert (await ws.receive_json())["error"]["code"] == "invalid_format"


async def test_protocol_3_is_refused(hass, hass_ws_client):
    entry, token = await paired(hass)
    ws = await hass_ws_client(hass, access_token=token)
    result, _ = await connect(ws, 3)
    assert result["error"]["code"] == "unsupported_protocol"


async def test_music_section_is_minted_on_connect_when_ma_appears(hass, hass_ws_client, monkeypatch):
    entry, token = await paired(hass)
    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://ma:8095", "ma-token"))

    async def fake_create(hass, installation_id):
        return {"url": "http://ma:8095", "token": "clock-token"}

    monkeypatch.setattr(identity, "async_create_music_section", fake_create)
    ws = await hass_ws_client(hass, access_token=token)
    _, events = await connect(ws)
    assert events[2]["music_assistant"] == {"url": "http://ma:8095", "token": "clock-token", "sendspin_url": "ws://ma:8927/sendspin"}
    assert entry.data["music_assistant"] == {"url": "http://ma:8095", "token": "clock-token"}


async def until_connection(ws):
    async with __import__("asyncio").timeout(3):
        while True:
            msg = await ws.receive_json()
            if msg.get("event", {}).get("type") == "connection":
                return msg["event"]


async def test_options_change_resends_connection_without_restart(hass, hass_ws_client):
    entry, token = await paired(hass)
    coordinator = hass.data[DOMAIN]["entries"][entry.entry_id]
    ws = await hass_ws_client(hass, access_token=token)
    await connect(ws)
    hass.config_entries.async_update_entry(entry, options={**entry.options, "diagnostics_url": "http://pc:8757/x/events"})
    await hass.async_block_till_done()
    assert (await until_connection(ws))["diagnostics_url"] == "http://pc:8757/x/events"
    hass.config_entries.async_update_entry(entry, data={**entry.data, "music_assistant": {"url": "http://ma:8095", "token": "clock-token"}}, options={**entry.options, "sendspin_url": "ws://other:8927/sendspin"})
    await hass.async_block_till_done()
    assert (await until_connection(ws))["music_assistant"]["sendspin_url"] == "ws://other:8927/sendspin"
    assert hass.data[DOMAIN]["entries"][entry.entry_id] is coordinator and entry.state is ConfigEntryState.LOADED, "options never reload the entry"


async def test_failed_section_save_keeps_the_old_token_and_revokes_only_the_new_one(hass, hass_ws_client, monkeypatch):
    entry, token = await paired(hass)
    old = {"url": "http://old:8095", "token": "old-clock-token"}
    hass.config_entries.async_update_entry(entry, data={**entry.data, "music_assistant": old})
    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://new:8095", "ma-token"))
    revoked = []

    async def fake_revoke(hass, section):
        revoked.append(section)

    async def fake_create(hass, installation_id):
        return {"url": "http://new:8095", "token": "new-clock-token"}

    def failing_update(entry_, **kwargs):
        raise RuntimeError("store down")

    monkeypatch.setattr(identity, "async_revoke_music_section", fake_revoke)
    monkeypatch.setattr(identity, "async_create_music_section", fake_create)
    monkeypatch.setattr(hass.config_entries, "async_update_entry", failing_update)
    ws = await hass_ws_client(hass, access_token=token)
    _, events = await connect(ws)
    assert entry.data["music_assistant"] == old and events[2]["music_assistant"]["token"] == "old-clock-token"
    assert revoked == [{"url": "http://new:8095", "token": "new-clock-token"}], "the old token is never revoked before the new section is persisted"


async def test_ma_removed_from_ha_drops_the_section(hass, hass_ws_client, monkeypatch):
    entry, token = await paired(hass)
    hass.config_entries.async_update_entry(entry, data={**entry.data, "music_assistant": {"url": "http://old:8095", "token": "old-clock-token"}})
    revoked = []

    async def fake_revoke(hass, section):
        revoked.append(section)

    monkeypatch.setattr(identity, "async_revoke_music_section", fake_revoke)
    ws = await hass_ws_client(hass, access_token=token)
    _, events = await connect(ws)  # no loaded music_assistant entry in this test
    assert events[2]["music_assistant"] is None and entry.data["music_assistant"] is None
    assert revoked == [{"url": "http://old:8095", "token": "old-clock-token"}]


async def test_ma_server_change_revokes_the_old_token_and_mints_a_new_one(hass, hass_ws_client, monkeypatch):
    entry, token = await paired(hass)
    hass.config_entries.async_update_entry(entry, data={**entry.data, "music_assistant": {"url": "http://old:8095", "token": "old-clock-token"}})
    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://new:8095", "ma-token"))
    revoked, created = [], []

    async def fake_revoke(hass, section):
        revoked.append(section)

    async def fake_create(hass, installation_id):
        created.append(installation_id)
        return {"url": "http://new:8095", "token": "new-clock-token"}

    monkeypatch.setattr(identity, "async_revoke_music_section", fake_revoke)
    monkeypatch.setattr(identity, "async_create_music_section", fake_create)
    ws = await hass_ws_client(hass, access_token=token)
    _, events = await connect(ws)
    assert revoked == [{"url": "http://old:8095", "token": "old-clock-token"}] and created == [INSTALLATION]
    assert events[2]["music_assistant"] == {"url": "http://new:8095", "token": "new-clock-token", "sendspin_url": "ws://new:8927/sendspin"}
    assert entry.data["music_assistant"]["url"] == "http://new:8095"


async def test_connect_rechecks_the_owner_after_waiting_for_the_lock(hass, hass_ws_client, monkeypatch):
    entry, token = await paired(hass)
    monkeypatch.setattr(identity, "music_source", lambda hass: ("http://ma:8095", "ma-token"))
    import asyncio

    lock = hass.data[DOMAIN]["locks"].setdefault(INSTALLATION, asyncio.Lock())
    await lock.acquire()
    ws = await hass_ws_client(hass, access_token=token)
    await ws.send_json({"id": 1, "type": "helios/connect", "protocol": 2, "installation_id": INSTALLATION, "app_version": "0.9.0", "version_code": 28})
    async with asyncio.timeout(3):
        while not lock._waiters:  # noqa: SLF001 - the handler must really be parked on the lock before the owner changes
            await asyncio.sleep(0.01)
    new_user, _ = await identity.async_create_identity(hass, INSTALLATION)  # re-pair while the connect waits
    hass.config_entries.async_update_entry(entry, data={**entry.data, "user_id": new_user})
    lock.release()
    result = await ws.receive_json()
    assert result["success"] is False and result["error"]["code"] == "unauthorized"
```

- [ ] **Krok 2:** `pytest -q tests/test_channel.py` → FAIL.
- [ ] **Krok 3: implementacja** `websocket.py` - cały `ws_connect` (zastępuje dzisiejszy; schemat bez `pairing_code`; importy `from . import identity`, `from .const import DOMAIN, PROTOCOL, PROTOCOLS`):

```python
@websocket_api.websocket_command(
    {
        vol.Required("type"): "helios/connect",
        vol.Required("protocol"): int,
        vol.Required("installation_id"): cv.string,
        vol.Required("app_version"): cv.string,
        vol.Required("version_code"): int,
        vol.Optional("capabilities"): [cv.string],
    }
)
@websocket_api.async_response
async def ws_connect(hass: HomeAssistant, connection, msg: dict) -> None:
    """Subscription owned by one clock (SPEC 0.10 pkt 4.1, 6.2): the entry must exist and belong to this user; pairing happens over HTTP."""
    data = hass.data[DOMAIN]
    installation_id = msg["installation_id"]
    if msg["protocol"] not in PROTOCOLS:
        connection.send_error(msg["id"], "unsupported_protocol", f"Helios obsługuje protokół {PROTOCOL}")
        return

    def owned():
        """The entry as of now, or None when it is gone or belongs to someone else (re-paired meanwhile)."""
        current = _entry_for(hass, installation_id)
        return current if current is not None and current.data.get("user_id") == connection.user.id else None

    entry = owned()
    if entry is None:
        connection.send_error(msg["id"], "unauthorized", "Nieznane urządzenie albo sparowane z innym użytkownikiem - sparuj kodem")
        return
    section = entry.data.get("music_assistant")
    source = identity.music_source(hass)
    stale = section is not None and (source is None or section["url"] != source[0])  # MA gone or moved: the stored section is dead
    missing = section is None and source is not None
    if stale or missing:
        lock = data["locks"].setdefault(installation_id, asyncio.Lock())
        try:
            async with lock:  # the same lock the pairing transaction holds through its steps 3-5
                entry = owned()
                if entry is None:
                    connection.send_error(msg["id"], "unauthorized", "To urządzenie zostało sparowane ponownie")
                    return
                if entry.data.get("music_assistant") != section:
                    section = entry.data.get("music_assistant")  # another connect already did the work
                else:
                    old = section
                    fresh = await identity.async_create_music_section(hass, installation_id) if source is not None else None
                    try:
                        hass.config_entries.async_update_entry(entry, data={**entry.data, "music_assistant": fresh})
                    except Exception:  # noqa: BLE001 - the stored (old) section stays valid: only the token of this attempt goes
                        await identity.async_revoke_music_section(hass, fresh)
                    else:
                        section = fresh
                        await identity.async_revoke_music_section(hass, old)  # only after the new section is persisted (best effort when the server is gone)
        finally:
            if not lock.locked() and not lock._waiters:  # noqa: SLF001 - every exit path drops an idle lock
                data["locks"].pop(installation_id, None)
    coordinator: HeliosCoordinator | None = None
    for _ in range(50):  # a freshly created or reloaded entry finishes its setup shortly after the flow completes
        entry = owned()
        if entry is None:
            break
        coordinator = data["entries"].get(entry.entry_id)
        if coordinator is not None:
            break
        await asyncio.sleep(0.2)
    # directly before attach, after the last await: a re-pair or reload meanwhile replaces both the entry data and the coordinator
    entry = owned()
    if entry is None:
        connection.send_error(msg["id"], "unauthorized", "To urządzenie zostało sparowane ponownie")
        return
    if coordinator is None or data["entries"].get(entry.entry_id) is not coordinator:
        connection.send_error(msg["id"], "not_ready", "Integracja Helios jeszcze się ładuje")
        return
    sub_id = msg["id"]
    coordinator.attach(connection, sub_id)

    device = dr.async_get(hass).async_get_device(identifiers={(DOMAIN, installation_id)})

    @callback
    def device_updated(event) -> None:
        """Name or area edited in HA: tell the clock so its player name and voice context follow the HA device."""
        if not coordinator.is_owner(connection, sub_id):
            return
        current = dr.async_get(hass).async_get(event.data["device_id"])
        if current is not None:
            connection.send_event(sub_id, {"type": "device", "device_id": current.id, "area_id": current.area_id, "name": current.name_by_user or current.name})

    unsubscribe_registry = async_track_device_registry_updated_event(hass, device.id, device_updated) if device else None

    @callback
    def cleanup() -> None:
        if unsubscribe_registry is not None:
            unsubscribe_registry()
        coordinator.detach(connection, sub_id, "disconnected")

    connection.subscriptions[sub_id] = cleanup
    connection.send_result(sub_id)
    connection.send_event(
        sub_id,
        {"type": "connected", "device_id": device.id if device else None, "area_id": device.area_id if device else None, "name": (device.name_by_user or device.name) if device else None},
    )
    coordinator.send_appearance()
    coordinator.send_connection(identity.connection_payload(hass, dict(entry.data), dict(entry.options)))
```

Schemat `helios/connect`: usunąć `vol.Optional("pairing_code")` i całą gałąź `if code:`. `config_flow.HeliosOptionsFlow.async_step_init`: dwa pola `vol.Optional("sendspin_url", default=current_options.get("sendspin_url", "")): str`, `vol.Optional("diagnostics_url", default=...): str`; w `_save`: `options["sendspin_url"] = (draft.get("sendspin_url") or "").strip()`, tak samo `diagnostics_url`; walidacja: `sendspin_url` puste albo zaczyna się od `ws://`/`wss://`, `diagnostics_url` puste albo `http://`/`https://` (błąd formularza `invalid_url`). Tłumaczenia: „Adres Sendspin (puste = wyliczony z adresu MA)”, „Adres diagnostyki (puste = brak)”.

- [ ] **Krok 4:** `pytest -q tests` → PASS; `hassfest` lokalnie nie ma - CI po pushu.
- [ ] **Krok 5: commit** `feat(channel): protocol 2 without pairing_code, connection event, MA section on connect, options sendspin_url/diagnostics_url (SPEC 0.10 pkt 4.1, 6)`.

### A6 Wydanie ha-helios 0.8.0

**Pliki:** `README.md` (ha-helios: parowanie kodem przez zegar, bez tokena; opcje), `dash/docs/ha-integration.md` (sekcja parowania: kolejność „aktualizacja integracji → restart → APK 0.9.0 → Paruj z HA”, usunięcie starego tokena administratora w profilu), `CHANGELOG`/notatka wydania.

- [ ] **Krok 1:** push `main`, workflow (hassfest, hacs, tests na 3.14 z HA, import-check) musi być zielony - bez tego brak wydania.
- [ ] **Krok 2:** `gh release create v0.8.0 --title "ha-helios 0.8.0" --notes "Parowanie kodem bez tokena (endpoint /api/helios/pair), użytkownik HA per zegar, token MA per zegar, zdarzenie connection, opcje sendspin_url/diagnostics_url. Zegary 0.8.18 działają dalej (protokół 1)."` - repozytorium publiczne od dawna, zgoda użytkownika na wydania ha-helios już jest.
- [ ] **Krok 3 (użytkownik):** HACS → aktualizacja → restart HA; zegar 0.8.18 nadal połączony (kryterium 8).
- [ ] **Krok 4: commit** w `dash`: `docs: ha-integration 0.8 pairing flow`.

---

## Etap B - Helios 0.9.0

### B1 Czyste klasy: `Version`, `ReleaseInfo`

**Pliki:** `app/src/main/java/pl/mateusz/helios/Version.java`, `ReleaseInfo.java`, `app/src/test/java/pl/mateusz/helios/ReleaseInfoTest.java`.

**Interfejs:**
- `static int Version.compare(String a, String b)` - porównanie po składnikach liczbowych (`0.9.10` > `0.9.9`), brakujące składniki = 0, nieliczbowe = `IllegalArgumentException`.
- `ReleaseInfo`: `static ReleaseInfo ReleaseInfo.parse(JSONObject release)` → `null` gdy draft/prerelease/zły tag/asset ≠ 1/zły host/rozmiar > `MAX_BYTES`; pola `version`, `url`, `size`.
- `static boolean ReleaseInfo.assetHost(String url)` - `https` i host dokładnie `github.com` albo `objects.githubusercontent.com` (`browser_download_url` w wydaniu); `static boolean ReleaseInfo.allowedHost(String url)` - `https` i host dokładnie `github.com` albo kończący się na `.githubusercontent.com` (cele przekierowań); `static final String API_URL="https://api.github.com/repos/SychPL/helios/releases/latest"` - jedyny adres metadanych, nigdy nie przechodzi przez żadną z tych polityk ani przekierowania (`setInstanceFollowRedirects(false)`, przekierowanie odpowiedzi API = „Brak wydań”). Tag dokładnie `v<x.y.z>` (trzy składniki).

- [ ] **Krok 1: test (RED)**

```java
package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseInfoTest {
    private static JSONObject release(String tag,String asset,String url,long size) throws Exception {
        return new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",false)
            .put("assets",new JSONArray().put(new JSONObject().put("name",asset).put("browser_download_url",url).put("size",size)));
    }
    @Test public void versionsCompareNumerically(){
        assertTrue(Version.compare("0.9.10","0.9.9")>0);assertTrue(Version.compare("0.9.0","0.8.18")>0);assertEquals(0,Version.compare("1.0","1.0.0"));assertTrue(Version.compare("0.9","0.9.1")<0);
        try{Version.compare("0.9.x","0.9.0");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void onlyAWellFormedFinalReleaseWithOneApkIsAccepted() throws Exception {
        ReleaseInfo info=ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk",6_000_000));
        assertEquals("0.9.1",info.version);assertEquals(6_000_000,info.size);assertTrue(info.url.startsWith("https://github.com/"));
        assertNull(ReleaseInfo.parse(release("0.9.1","helios-0.9.1.apk","https://github.com/x",1)));
        assertNull("exactly three components",ReleaseInfo.parse(release("v1","helios-1.apk","https://github.com/x",1)));assertNull(ReleaseInfo.parse(release("v0.9.1.2","helios-0.9.1.2.apk","https://github.com/x",1)));
        assertNull("initial download only from github.com or objects.githubusercontent.com",ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://raw.githubusercontent.com/x",1)));
        assertNotNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://objects.githubusercontent.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.2.apk","https://github.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","http://github.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://evil.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/x",ReleaseInfo.MAX_BYTES+1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/x",1).put("prerelease",true)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/x",1).put("draft",true)));
        JSONObject two=release("v0.9.1","helios-0.9.1.apk","https://github.com/x",1);two.getJSONArray("assets").put(new JSONObject().put("name","helios-0.9.1.apk").put("browser_download_url","https://github.com/y").put("size",1));
        assertNull(ReleaseInfo.parse(two));
        assertTrue(ReleaseInfo.allowedHost("https://objects.githubusercontent.com/a"));assertFalse(ReleaseInfo.allowedHost("https://githubusercontent.com.evil.com/a"));assertFalse(ReleaseInfo.allowedHost("https://github.com.evil.com/a"));
        assertFalse("the API host is not an asset host",ReleaseInfo.allowedHost("https://api.github.com/x"));assertFalse(ReleaseInfo.allowedHost("https://githubusercontent.com/a"));
    }
}
```

- [ ] **Krok 2:** `./gradlew -q testDebugUnitTest --tests '*ReleaseInfoTest*'` → FAIL (brak klas).
- [ ] **Krok 3: implementacja**

```java
package pl.mateusz.helios;

/** Dotted numeric versions ("0.9.10" > "0.9.9"); missing components count as zero. */
final class Version {
    private Version(){}
    static int compare(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){
            int p=i<x.length?part(x[i]):0,q=i<y.length?part(y[i]):0;
            if(p!=q)return Integer.compare(p,q);
        }
        return 0;
    }
    private static int part(String s){if(!s.matches("[0-9]{1,9}"))throw new IllegalArgumentException("Nieprawidłowa wersja: "+s);return Integer.parseInt(s);}
}
```

```java
package pl.mateusz.helios;

import java.net.URI;
import java.util.regex.*;
import org.json.*;

/** The one APK of a final GitHub release of SychPL/helios; null for anything else (SPEC 0.10 pkt 7). */
final class ReleaseInfo {
    static final long MAX_BYTES=40L*1024*1024;
    private static final Pattern TAG=Pattern.compile("^v([0-9]+\\.[0-9]+\\.[0-9]+)$");
    final String version,url;final long size;
    private ReleaseInfo(String version,String url,long size){this.version=version;this.url=url;this.size=size;}
    static ReleaseInfo parse(JSONObject release){
        if(release==null||release.optBoolean("draft")||release.optBoolean("prerelease"))return null;
        Matcher m=TAG.matcher(release.optString("tag_name",""));if(!m.matches())return null;
        String version=m.group(1);JSONArray assets=release.optJSONArray("assets");if(assets==null)return null;
        JSONObject found=null;
        for(int i=0;i<assets.length();i++){JSONObject a=assets.optJSONObject(i);if(a!=null&&("helios-"+version+".apk").equals(a.optString("name"))){if(found!=null)return null;found=a;}}
        if(found==null)return null;
        String url=found.optString("browser_download_url","");long size=found.optLong("size",-1);
        if(!assetHost(url)||size<1||size>MAX_BYTES)return null;
        return new ReleaseInfo(version,url,size);
    }
    private static String httpsHost(String url){try{URI u=new URI(url);return "https".equals(u.getScheme())?u.getHost():null;}catch(Exception e){return null;}}
    /** Where a release asset may point (the first hop). */
    static boolean assetHost(String url){String h=httpsHost(url);return h!=null&&(h.equals("github.com")||h.equals("objects.githubusercontent.com"));}
    /** Where a redirect may land. */
    static boolean allowedHost(String url){String h=httpsHost(url);return h!=null&&(h.equals("github.com")||h.endsWith(".githubusercontent.com"));}
}
```

- [ ] **Krok 4:** test PASS; **commit** `feat(update): Version and ReleaseInfo (SPEC 0.10 pkt 7)`.

### B2 `HeliosDeviceClient`: protokół 2, ponowna subskrypcja, `connection`

**Pliki:** `HeliosDeviceClient.java`, `HeliosDeviceClientTest.java`, `HaDashboardClientTest.java` (serwer: flaga `stateUnauthorized`, licznik `connects` już jest).

**Interfejs:**
- `PROTOCOL=2`; `pair()` usunięte; `Listener` + `default void onConnection(JSONObject payload){}` + `default void onChannelIssue(String text){}`.
- `removed` → `ended(gen)` + `scheduleReconnect()` (opóźnienia `RESUBSCRIBE_DELAYS_MS = {2000,5000,10000,30000}`, ostatnie powtarzane; zerowane przy `connected`; anulowane w `stop()` i w `onSessionStarted()`); `replaced` → `ended` + `onChannelIssue("Inne urządzenie przejęło to parowanie")`, bez ponowienia; wynik `helios/state`/`helios/result` z `success:false` → jak `removed`; wynik `helios/connect` z błędem o **kodzie** `unauthorized` → `onChannelIssue("Zegar usunięty z HA - sparuj ponownie")`, bez ponowienia (`not_ready` i inne → ponowienie jak `removed`). Kod błędu jest strukturalny: `HaDashboardClient.subscribe(payload, event, ended)` przekazuje do `ended` dokładnie `error.code` (`errorCode(m)`, `"disconnected"` przy zerwaniu, `"subscription_rejected"` bez `error`), nie tłumaczony `message`.
- Właścicielem wznowienia po zerwaniu gniazda jest sesja: zaplanowana próba wykonuje `connect()` tylko przy `ha.live()`; gdy sesja nie żyje, próba wygasa bez ponownego planowania, bo nowa sesja i tak woła `onSessionStarted()` → `connect()`. Test: `removed` → natychmiastowe zerwanie gniazda → nowa sesja → dokładnie jedno `helios/connect`.

- [ ] **Krok 1: testy (RED)** - w `HaDashboardClientTest.Server` dodać `volatile boolean stateUnauthorized; volatile String connectError="unauthorized";` i w obsłudze `helios/state`: gdy `stateUnauthorized` → `success:false, error.code=unauthorized`; `rejectConnect` używa `connectError`. Do `HeliosDeviceClientTest` dodać:

```java
    private final BlockingQueue<String> issues=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> connections=new LinkedBlockingQueue<>();
    // w Listener klienta: onConnection → connections.add(payload.toString()); onChannelIssue → issues.add(text)

    @Test public void removedResubscribesWithBackoffAndReplacedDoesNot() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));server.connects.clear();
            long t0=System.currentTimeMillis();
            server.sendEvent(server.connectId,new JSONObject().put("type","removed"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            assertNotNull("resubscribe after removed",server.connects.poll(4,TimeUnit.SECONDS));
            assertTrue(System.currentTimeMillis()-t0>=1900);
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));assertTrue(client.active());
            server.connects.clear();
            server.sendEvent(server.connectId,new JSONObject().put("type","replaced"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            assertEquals("Inne urządzenie przejęło to parowanie",issues.poll(2,TimeUnit.SECONDS));
            assertNull("no resubscribe after replaced",server.connects.poll(3,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void aRejectedStateOrResultCountsAsRemoved() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));device("helios/state");server.connects.clear();
            server.stateUnauthorized=true;telemetry.set(snapshot(41));client.publish();
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.stateUnauthorized=false;
            assertNotNull(server.connects.poll(4,TimeUnit.SECONDS));assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));device("helios/state");server.connects.clear();
            server.stateUnauthorized=true; // the server flag covers helios/result too
            server.sendCommand("r1","lamp.turn_on",new JSONObject());
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.stateUnauthorized=false;
            assertNotNull(server.connects.poll(4,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void removedFollowedByADeadSocketResubscribesExactlyOnceInTheNewSession() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));server.connects.clear();
            server.sendEvent(server.connectId,new JSONObject().put("type","removed"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.client.close(1001,"drop"); // before the 2 s retry fires
            assertNotNull(server.connects.poll(7,TimeUnit.SECONDS)); // the new session's onSessionStarted
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
            assertNull("no second subscription from the stale retry",server.connects.poll(4,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void unauthorizedConnectStopsRetryingAndReportsIt() throws Exception {
        server=new HaDashboardClientTest.Server();server.rejectConnect=true;server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        ha=new HaDashboardClient(new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","t"),null);
        HeliosDeviceClient client=new HeliosDeviceClient(ha,"install-1",telemetry::get,(c,a)->null,new HeliosDeviceClient.Listener(){
            public void onDevice(String d,String a,String n){devices.add(d+"/"+a);}
            public void onChannelIssue(String text){issues.add(text);}
        });
        client.start();ha.start();
        try{
            assertNotNull(server.connects.poll(5,TimeUnit.SECONDS));
            assertEquals("Zegar usunięty z HA - sparuj ponownie",issues.poll(5,TimeUnit.SECONDS));
            assertNull(server.connects.poll(3,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void connectionEventReachesTheListener() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
            server.sendEvent(server.connectId,new JSONObject().put("type","connection").put("pipeline","p1").put("dashboard_path","helios-clock").put("music_assistant",JSONObject.NULL).put("diagnostics_url",JSONObject.NULL));
            String c=connections.poll(5,TimeUnit.SECONDS);assertNotNull(c);assertTrue(c,c.contains("\"pipeline\":\"p1\""));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
```

Zmienić `connectsPublishesStateAndClearsDeviceWhenTheSessionEnds`: `assertEquals(2,connect.getInt("protocol"))`; `publishIsCoalescedAndPairingCodeIsSentOnce` → `publishIsCoalesced` (usunąć część z `pair`).

- [ ] **Krok 2:** testy → FAIL.
- [ ] **Krok 3: implementacja** (fragmenty `HeliosDeviceClient`):

```java
    static final int PROTOCOL=2;
    static final long[] RESUBSCRIBE_DELAYS_MS={2000,5000,10000,30000};
    private int resubscribeAttempt;
    private ScheduledFuture<?> resubscribe;

    @Override public void onSessionStarted(){cancelResubscribe();resubscribeAttempt=0;connect();}

    private synchronized void connect(){
        final int gen=++generation;
        active=false;
        Telemetry now=telemetry.get();
        try{
            JSONObject payload=new JSONObject().put("type","helios/connect").put("protocol",PROTOCOL).put("installation_id",installationId)
                .put("app_version",now.appVersion).put("version_code",now.versionCode).put("capabilities",new org.json.JSONArray(Arrays.asList("lamp","volume","music")));
            ha.subscribe(payload,event->handle(gen,event),reason->rejected(gen,reason));
        }catch(Exception e){ended(gen,true);}
    }
    /** Subscription refused or lost; the argument is HA's error.code ("disconnected" when the socket went): unauthorized = entry gone, no retry. */
    private void rejected(int gen,String code){
        boolean unauthorized="unauthorized".equals(code);
        ended(gen,!unauthorized);
        if(unauthorized&&listener!=null)listener.onChannelIssue("Zegar usunięty z HA - sparuj ponownie");
    }
    private void handle(int gen,JSONObject event){
        if(gen!=generation)return;
        switch(event.optString("type")){
            case "connected": ... resubscribeAttempt=0; ... (jak dziś)
            case "connection":{if(listener!=null)listener.onConnection(event);break;}
            case "removed":ended(gen,true);break;
            case "replaced":ended(gen,false);if(listener!=null)listener.onChannelIssue("Inne urządzenie przejęło to parowanie");break;
            ...
        }
    }
    private synchronized void ended(int gen,boolean retry){
        if(gen!=generation)return;
        generation++;active=false;deviceId=null;areaId=null;
        synchronized(publishLock){if(scheduledPublish!=null){scheduledPublish.cancel(false);scheduledPublish=null;}}
        if(listener!=null)listener.onDevice(null,null,deviceName);
        if(retry)scheduleResubscribe();
    }
    private void scheduleResubscribe(){
        cancelResubscribe();
        long delay=RESUBSCRIBE_DELAYS_MS[Math.min(resubscribeAttempt++,RESUBSCRIBE_DELAYS_MS.length-1)];
        try{resubscribe=scheduler.schedule(()->{if(ha.live())connect();},delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){}
    }
    private void cancelResubscribe(){if(resubscribe!=null){resubscribe.cancel(false);resubscribe=null;}}
    private void sendState(){
        ... final int gen=generation;
        try{ha.request(new JSONObject().put("type","helios/state").put("state",now.toJson()),r->{if(!r.optBoolean("success",true))ended(gen,true);});}catch(Exception ignored){}
    }
    private void result(int gen,String requestId,String status,String code){
        ... ha.request(payload,r->{if(!r.optBoolean("success",true))ended(gen,true);}); // a refused result = the subscription is gone (SPEC 8.1)
    }
```

`HaDashboardClient`: nowy `private static String errorCode(JSONObject m){JSONObject e=m.optJSONObject("error");return e==null?"subscription_rejected":e.optString("code","subscription_rejected");}`; w pętli sesji `sub.ended.accept(errorCode(m))`, a `failPending`/zerwanie gniazda woła `ended.accept("disconnected")` (dziś tekst po polsku - zamienić na kod; diagnostyka tekstu zostaje w `emitUnavailable`). `stop()` klienta urządzenia woła `cancelResubscribe()`.

- [ ] **Krok 4:** `./gradlew -q testDebugUnitTest` → PASS; **commit** `feat(channel): protocol 2, resubscribe after removed with backoff, replaced/unauthorized reported, connection event (SPEC 0.10 pkt 8.1)`.

### B3 `HaDashboardClient`: `auth_invalid` kończy pętlę, bez `probe`

**Pliki:** `HaDashboardClient.java`, `HaDashboardClientTest.java`.

**Interfejs:** `Listener` + `default void onAuthInvalid(){}`; po `auth_invalid` klient ustawia `stopped=true`, woła `onUnavailable("HA odrzucił token - sparuj ponownie")` i `onAuthInvalid()` na każdym listenerze, zamyka gniazdo; brak kolejnych prób. `probe(JSONObject)` i jego test usunięte.

- [ ] **Krok 1: test (RED)** (zastępuje `probeAcceptsGoodTokenAndRejectsBad`):

```java
    @Test public void authInvalidStopsTheReconnectLoop() throws Exception {
        HaDashboardClient client=client(null);
        server.rejectToken=true;
        BlockingQueue<String> auth=new LinkedBlockingQueue<>();
        client.attach(new HaDashboardClient.Listener(){
            public void onDashboard(JSONObject r,DashboardSpec s,Map<String,EntityStates.Entity> v,String i){}
            public void onStates(Map<String,EntityStates.Entity> v){}
            public void onUnavailable(String reason){}
            public void onAuthInvalid(){auth.add("invalid");}
        });
        client.start();
        try{
            assertEquals("invalid",auth.poll(5,TimeUnit.SECONDS));
            assertTrue(errors.stream().anyMatch(e->e.contains("odrzucił token")));
            int opens=server.opens.get();Thread.sleep(3000);
            assertEquals("no reconnect after auth_invalid",opens,server.opens.get());assertFalse(client.live());assertNull(auth.poll(200,TimeUnit.MILLISECONDS));
        }finally{client.stop();server.stop(2000);}
    }
```

- [ ] **Krok 2:** FAIL. **Krok 3:** w `session()` po wysłaniu `auth`:

```java
        JSONObject auth=s.required();String authType=auth.optString("type");
        if(authType.equals("auth_invalid")){stopped=true;emitUnavailable("HA odrzucił token - sparuj ponownie");for(Listener l:listeners)l.onAuthInvalid();return false;} // permanent: no retry until a new token is stored
        if(!authType.equals("auth_ok"))throw new IOException("HA authentication"); // anything else is a transient handshake failure and retries as before
```

Usunąć `probe`. Serwer testowy: `final AtomicInteger opens=new AtomicInteger();` inkrementowany w `onOpen`; flaga `volatile boolean garbleAuth` (odpowiedź `{"type":"weird"}` na `auth`). Test uzupełnić o oba przypadki: `rejectToken` → `onAuthInvalid` raz, `opens` nie rośnie przez 3 s, `live()==false`; `garbleAuth` → brak `onAuthInvalid`, `opens` rośnie (reconnect co 2 s), po `garbleAuth=false` sesja wstaje (`sessions.poll`).
- [ ] **Krok 4:** PASS; **commit** `feat(ha): auth_invalid ends the session loop and reports it; probe removed (SPEC 0.10 pkt 8.2)`.

### B4 `HeliosService`: `pairedWith`, `applyConnection`, flaga `auth_invalid`

**Pliki:** `HeliosService.java`, `ConnectionMerge.java` (nowy), `ConnectionController.java` (nowy), `ConnectionMergeTest.java`, `ConnectionControllerTest.java`.

**Interfejs:**
- `ConnectionController(Store store, Probe probe, Transports transports)` - czysta orkiestracja bez Androida, wołana przez `HeliosService` na wątku `network` (wynik na main przez `Transports`): `interface Store {JSONObject current();boolean save(JSONObject connection);}` (serwis: `commit()`), `interface Probe {String music(String url,String token);}` (serwis: `MusicAssistantClient.probe`), `interface Transports {void restartAll();void restartHa();void restartMusic();void stopAll();void issue(String text);}` (`stopAll` zatrzymuje wyłącznie transporty i nigdy nie rusza `connection`; zapominanie połączenia to `Store.clear()`).
  - `int generation()`: rośnie tylko przy zmianie tożsamości (`pairedWith`, `markAuthInvalid`); sesja HA bierze numer przy `startHa()` i trzyma go przez wszystkie zdarzenia `connection`.
  - `String pairedWith(JSONObject response,String url)`: buduje `{url, token, pipeline, dashboard_path, protocol: 2}` (brak `token`/`pipeline` → `"Niepełna odpowiedź HA"`), `store.save` (`false` → `store.clear()` + `transports.stopAll()` + zwrot `"Nie udało się zapisać parowania - sparuj ponownie"`: stan terminalny, stara konfiguracja nie jest uruchamiana ani po restarcie, bo HA już ją unieważnił), sukces → `transports.restartAll()` (serwis: `resetAppearance(); stopHa(); stopMusic(); startHa(); startMusic();`), zwrot `null`.
  - `void applyConnection(JSONObject event,int gen)`: `gen != generation()` → zdarzenie starej sesji, ignorowane; `ConnectionMerge.apply`; nowa/zmieniona sekcja MA → `probe.music` (błąd → `transports.issue("Muzyka: "+error)`, sekcja cofnięta do poprzedniej); `store.save` **przed** restartami; `restartHa` tylko gdy `ConnectionMerge.haRestart`, `restartMusic` tylko gdy `maRestart`; zmiana samego `diagnostics_url` = zapis bez restartu (aktywność dowiaduje się przez `Store.save` → `notifyConnection`).
  - `String markAuthInvalid(int gen)` → `IGNORED` (stara sesja), `SAVED`, `SAVE_FAILED` (flaga nie utrwalona; kontroler pamięta to w procesie); `boolean startAllowed()` - bramka `startHa()`: `false` po `SAVED` (flaga w zapisanym `connection`) i po `SAVE_FAILED`, `true` po udanym `pairedWith`.
- `static JSONObject ConnectionMerge.apply(JSONObject current, JSONObject event)` (czysta): zwraca nowe `connection` albo `null` gdy bez zmian; `pipeline` puste → ignorowane; `dashboard_path` string; `diagnostics_url` string/`null` (usuwa); `music_assistant`: brak klucza = bez zmian, `null` = usunięcie, obiekt = `{url, token, sendspin_url}` (walidacja `ws://`/`wss://`, `url` `http(s)://`, `token` niepusty; niepoprawny obiekt = ignorowany, log). `static boolean ConnectionMerge.haRestart(JSONObject a,JSONObject b)` (`pipeline`/`dashboard_path` różne), `static boolean ConnectionMerge.maRestart(a,b)`.
- `HeliosService.pairedWith(JSONObject response, String url, Consumer<String> done)` i `HeliosService.applyConnection(JSONObject event, int gen)`: cienkie opakowania `ConnectionController` (`network.execute`, wynik `done` na main).
- `boolean legacyConnection()` = `connection!=null && connection.optInt("protocol",1)<2`; `boolean authInvalid()` = `!connections.startAllowed()` (flaga w `connection` albo zapamiętany `SAVE_FAILED` - oba w kontrolerze); `startHa()` nie startuje, gdy `authInvalid()`; `HaDashboardClient.Listener.onAuthInvalid` (przez `cache`) → `connection.put("auth_invalid",true)`, `commit`, `stopHa`, `onAuthInvalid` callback do aktywności.
- `startMusic`: `playerName=prefs.getString("device_name","Helios")`.
- Usunięte: `reconfigure`, `pair`, `sameHa`, `copy` (jeśli nieużywane).

- [ ] **Krok 1: test (RED)** `ConnectionMergeTest`:

```java
package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionMergeTest {
    private static JSONObject base() throws Exception {return new JSONObject().put("url","http://ha:8123").put("token","t").put("pipeline","p1").put("dashboard_path","helios-clock").put("protocol",2);}
    private static JSONObject ma(String url) throws Exception {return new JSONObject().put("url",url).put("token","m").put("sendspin_url","ws://ma:8927/sendspin");}
    @Test public void musicSectionHasThreeStates() throws Exception {
        JSONObject with=ConnectionMerge.apply(base(),new JSONObject().put("pipeline","p1").put("music_assistant",ma("http://ma:8095")));
        assertEquals("http://ma:8095",with.getJSONObject("music_assistant").getString("url"));
        assertNull("absent key = unchanged",ConnectionMerge.apply(with,new JSONObject().put("pipeline","p1")));
        JSONObject without=ConnectionMerge.apply(with,new JSONObject().put("pipeline","p1").put("music_assistant",JSONObject.NULL));
        assertFalse(without.has("music_assistant"));
        assertTrue(ConnectionMerge.maRestart(with,without));assertFalse(ConnectionMerge.haRestart(with,without));
    }
    @Test public void invalidSectionsAndEmptyPipelineAreIgnored() throws Exception {
        assertNull(ConnectionMerge.apply(base(),new JSONObject().put("pipeline","").put("music_assistant",ma("ftp://x"))));
        assertNull(ConnectionMerge.apply(base(),new JSONObject().put("music_assistant",new JSONObject().put("url","http://ma:8095").put("token","m").put("sendspin_url","http://ma:8927"))));
    }
    @Test public void pipelineAndDiagnosticsMerge() throws Exception {
        JSONObject m=ConnectionMerge.apply(base(),new JSONObject().put("pipeline","p2").put("diagnostics_url","http://pc:8757/x/events"));
        assertEquals("p2",m.getString("pipeline"));assertEquals("http://pc:8757/x/events",m.getString("diagnostics_url"));assertTrue(ConnectionMerge.haRestart(base(),m));
        JSONObject cleared=ConnectionMerge.apply(m,new JSONObject().put("pipeline","p2").put("diagnostics_url",JSONObject.NULL));
        assertFalse(cleared.has("diagnostics_url"));assertFalse(ConnectionMerge.haRestart(m,cleared));
        assertEquals("t",m.getString("token"));assertEquals(2,m.getInt("protocol"));
    }
}
```

`ConnectionControllerTest`:

```java
package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConnectionControllerTest {
    private final List<String> log=new ArrayList<>();
    private JSONObject stored;private boolean saveOk=true,clearOk=true;private String probeError;
    private ConnectionController controller(JSONObject current){
        stored=current;
        return new ConnectionController(new ConnectionController.Store(){public JSONObject current(){return stored;}public boolean save(JSONObject c){log.add("save");if(saveOk)stored=c;return saveOk;}public boolean clear(){log.add("clear");if(clearOk)stored=null;return clearOk;}},
            (url,token)->{log.add("probe:"+url);return probeError;},
            new ConnectionController.Transports(){public void restartAll(){log.add("all");}public void restartHa(){log.add("ha");}public void restartMusic(){log.add("music");}public void stopAll(){log.add("stop");}public void issue(String t){log.add("issue:"+t);}});
    }
    private static JSONObject response() throws Exception {return new JSONObject().put("protocol",2).put("token","new").put("pipeline","p1").put("dashboard_path","helios-clock");}
    @Test public void pairingPersistsBeforeRestartAndFailsTerminally() throws Exception {
        ConnectionController c=controller(null);
        assertNull(c.pairedWith(response(),"http://ha:8123"));
        assertEquals(Arrays.asList("save","all"),log);assertEquals(2,stored.getInt("protocol"));assertEquals("new",stored.getString("token"));
        log.clear();saveOk=false;
        assertEquals("Nie udało się zapisać parowania - sparuj ponownie",c.pairedWith(response(),"http://ha:8123"));
        assertEquals("the old stored connection is cleared: a restart must not revive a retired token",Arrays.asList("save","clear","stop"),log);assertNull(stored);
        log.clear();clearOk=false;
        assertEquals("Nie udało się zapisać parowania - sparuj ponownie",controller(new JSONObject().put("url","http://old").put("token","old")).pairedWith(response(),"http://ha:8123"));
        assertEquals(Arrays.asList("save","clear","issue:Nie udało się wyczyścić starego parowania","stop"),log);
        assertEquals("Niepełna odpowiedź HA",c.pairedWith(new JSONObject().put("token","x"),"http://ha:8123"));
    }
    @Test public void connectionEventDrivesThreeMusicStatesAndMinimalRestarts() throws Exception {
        JSONObject base=new JSONObject().put("url","http://ha:8123").put("token","t").put("pipeline","p1").put("dashboard_path","helios-clock").put("protocol",2);
        ConnectionController c=controller(base);
        JSONObject ma=new JSONObject().put("url","http://ma:8095").put("token","m").put("sendspin_url","ws://ma:8927/sendspin");
        final int g=c.generation(); // one session, one number: every event and the auth_invalid below carry the same generation
        c.applyConnection(new JSONObject().put("pipeline","p1").put("music_assistant",ma),g);
        assertEquals(Arrays.asList("probe:http://ma:8095","save","music"),log);log.clear();
        c.applyConnection(new JSONObject().put("pipeline","p1"),g);assertTrue("absent key = nothing",log.isEmpty());
        c.applyConnection(new JSONObject().put("pipeline","p1").put("diagnostics_url","http://pc:8757/x/events"),g);
        assertEquals("diagnostics change saves without restarts",Arrays.asList("save"),log);log.clear();
        c.applyConnection(new JSONObject().put("pipeline","p2"),g);assertEquals(Arrays.asList("save","ha"),log);log.clear();
        assertEquals("connection events never change the generation",g,c.generation());
        probeError="MA odrzucił token";
        c.applyConnection(new JSONObject().put("pipeline","p2").put("music_assistant",new JSONObject(ma.toString()).put("token","bad")),g);
        assertEquals(Arrays.asList("probe:http://ma:8095","issue:Muzyka: MA odrzucił token"),log);assertEquals("m",stored.getJSONObject("music_assistant").getString("token"));log.clear();
        c.applyConnection(new JSONObject().put("pipeline","p2").put("music_assistant",JSONObject.NULL),g);
        assertEquals(Arrays.asList("save","music"),log);assertFalse(stored.has("music_assistant"));log.clear();
        int old=c.generation();
        assertNull(c.pairedWith(response(),"http://ha:8123"));log.clear();
        assertEquals(old+1,c.generation());
        c.applyConnection(new JSONObject().put("pipeline","p9"),old);
        assertTrue("an event from the previous connection's session is ignored",log.isEmpty());assertEquals("p1",stored.getString("pipeline"));
        assertEquals("stale auth_invalid is ignored",ConnectionController.IGNORED,c.markAuthInvalid(old));assertTrue(log.isEmpty());assertFalse(stored.has("auth_invalid"));
        int live=c.generation();
        c.applyConnection(new JSONObject().put("pipeline","p3"),live);log.clear();
        assertEquals("after a connection event the same session may still report auth_invalid",ConnectionController.SAVED,c.markAuthInvalid(live));
        assertEquals(Arrays.asList("save","stop"),log);assertTrue(stored.getBoolean("auth_invalid"));assertEquals(live+1,c.generation());
    }
    @Test public void authInvalidIsPersistedOrReportedAsUnsaved() throws Exception {
        JSONObject base=new JSONObject().put("url","http://ha:8123").put("token","t").put("pipeline","p1").put("protocol",2);
        ConnectionController c=controller(base);
        assertEquals(ConnectionController.SAVED,c.markAuthInvalid(c.generation()));
        assertEquals(Arrays.asList("save","stop"),log);assertTrue(stored.getBoolean("auth_invalid"));log.clear();
        ConnectionController d=controller(new JSONObject(base.toString()));saveOk=false;
        assertEquals(ConnectionController.SAVE_FAILED,d.markAuthInvalid(d.generation()));
        assertEquals(Arrays.asList("save","issue:Nie udało się zapisać stanu połączenia - po restarcie zegar spróbuje raz jeszcze","stop"),log);
        assertFalse("SAVE_FAILED is remembered in this process",d.startAllowed());
        log.clear();saveOk=true;
        assertNull("a later successful pairing is a clean start",d.pairedWith(response(),"http://ha:8123"));assertEquals(Arrays.asList("save","all"),log);assertFalse(stored.has("auth_invalid"));
        assertTrue("the service's startHa() gate opens again",d.startAllowed());
        assertEquals(ConnectionController.SAVED,c.markAuthInvalid(c.generation()));assertFalse("a persisted flag closes the gate too",c.startAllowed());
    }
}
```

- [ ] **Krok 2:** FAIL. **Krok 3:** `ConnectionMerge.java`:

```java
package pl.mateusz.helios;

import org.json.*;

/** Merges a `connection` event from the integration into the stored connection (SPEC 0.10 pkt 6.3); pure, no I/O. */
final class ConnectionMerge {
    private ConnectionMerge(){}
    /** Returns the merged connection or null when nothing changed; never touches url/token/protocol. */
    static JSONObject apply(JSONObject current,JSONObject event){
        try{
            JSONObject merged=new JSONObject(current.toString());
            String pipeline=event.optString("pipeline","");if(!pipeline.isEmpty())merged.put("pipeline",pipeline);
            String path=event.optString("dashboard_path","");if(!path.isEmpty())merged.put("dashboard_path",path);
            if(event.has("diagnostics_url")){String d=event.isNull("diagnostics_url")?"":event.optString("diagnostics_url","");if(d.isEmpty())merged.remove("diagnostics_url");else merged.put("diagnostics_url",d);}
            if(event.has("music_assistant")){
                if(event.isNull("music_assistant"))merged.remove("music_assistant");
                else{JSONObject music=validMusic(event.optJSONObject("music_assistant"));if(music!=null)merged.put("music_assistant",music);}
            }
            return merged.toString().equals(current.toString())?null:merged;
        }catch(Exception e){return null;}
    }
    static JSONObject validMusic(JSONObject music){
        if(music==null)return null;
        String url=music.optString("url",""),token=music.optString("token",""),sendspin=music.optString("sendspin_url","");
        if(!(url.startsWith("http://")||url.startsWith("https://"))||token.isEmpty()||!(sendspin.startsWith("ws://")||sendspin.startsWith("wss://")))return null;
        try{return new JSONObject().put("url",url).put("token",token).put("sendspin_url",sendspin);}catch(Exception e){return null;}
    }
    static boolean haRestart(JSONObject a,JSONObject b){return !a.optString("pipeline").equals(b.optString("pipeline"))||!a.optString("dashboard_path").equals(b.optString("dashboard_path"));}
    static boolean maRestart(JSONObject a,JSONObject b){return !String.valueOf(a.opt("music_assistant")).equals(String.valueOf(b.opt("music_assistant")));}
}
```

`ConnectionController.java`:

```java
package pl.mateusz.helios;

import org.json.JSONObject;

/** Connection changes without Android: pairing response and `connection` events (SPEC 0.10 pkt 4.2, 6.3). Called on the network thread. */
final class ConnectionController {
    interface Store {JSONObject current();boolean save(JSONObject connection);boolean clear();}
    interface Probe {String music(String url,String token);}
    interface Transports {void restartAll();void restartHa();void restartMusic();void stopAll();void issue(String text);}
    private final Store store;private final Probe probe;private final Transports transports;
    private int generation; // bumps only when the identity changes (pairing, auth_invalid): the HA session of the current identity keeps its number across connection events
    ConnectionController(Store store,Probe probe,Transports transports){this.store=store;this.probe=probe;this.transports=transports;}
    synchronized int generation(){return generation;}
    private synchronized boolean save(JSONObject value,int expected,boolean newIdentity){
        if(expected>=0&&expected!=generation)return false; // a newer pairing won meanwhile
        if(!store.save(value))return false;
        if(newIdentity)generation++;
        return true;
    }
    /** Persist first, then restart everything; HA has already retired the old identity, so a failed save is terminal: nothing old is started again. */
    String pairedWith(JSONObject response,String url){
        String token=response.optString("token",""),pipeline=response.optString("pipeline","");
        if(token.isEmpty()||pipeline.isEmpty())return "Niepełna odpowiedź HA";
        JSONObject fresh;
        try{fresh=new JSONObject().put("url",url).put("token",token).put("pipeline",pipeline).put("dashboard_path",response.optString("dashboard_path","helios-clock")).put("protocol",2);}
        catch(JSONException e){return "Niepełna odpowiedź HA";} // checked on Android's org.json
        authInvalidUnsaved=false; // a fresh identity supersedes any remembered refusal
        if(!save(fresh,-1,true)){
            // HA has retired the old identity: the stored old connection must not come back after a restart either
            if(!store.clear())transports.issue("Nie udało się wyczyścić starego parowania");
            transports.stopAll();
            return "Nie udało się zapisać parowania - sparuj ponownie";
        }
        transports.restartAll();
        return null;
    }
    static final String IGNORED="ignored",SAVED="saved",SAVE_FAILED="save_failed";
    private volatile boolean authInvalidUnsaved; // SAVE_FAILED happened: this process must not reuse the token even though the flag is not on disk
    /** false while the stored connection is flagged auth_invalid or an unsaved auth_invalid is remembered; the service's startHa() asks this. */
    boolean startAllowed(){JSONObject c=store.current();return !authInvalidUnsaved&&(c==null||!c.optBoolean("auth_invalid",false));}
    /** HA refused the token of the connection with this generation: persist the flag (nothing must reuse the token after a restart). IGNORED for a stale session: no UI, no stop. */
    String markAuthInvalid(int gen){
        JSONObject current=store.current();if(current==null||gen!=generation())return IGNORED;
        JSONObject flagged;
        try{flagged=new JSONObject(current.toString()).put("auth_invalid",true);}catch(JSONException e){flagged=null;}
        boolean saved=flagged!=null&&save(flagged,gen,true); // the identity is dead: events of this session are stale from now on
        if(!saved){synchronized(this){generation++;}authInvalidUnsaved=true;transports.issue("Nie udało się zapisać stanu połączenia - po restarcie zegar spróbuje raz jeszcze");} // SPEC 8.2 cannot be honoured without a durable flag; say so instead of pretending
        transports.stopAll();
        return saved?SAVED:SAVE_FAILED;
    }
    /** gen = generation() taken when the event arrived; an event from a client of an older connection is ignored. */
    void applyConnection(JSONObject event,int gen){
        JSONObject current=store.current();if(current==null||gen!=generation())return;
        JSONObject merged=ConnectionMerge.apply(current,event);if(merged==null)return;
        JSONObject music=merged.optJSONObject("music_assistant"),before=current.optJSONObject("music_assistant");
        if(music!=null&&(before==null||!before.toString().equals(music.toString()))){
            String error=probe.music(music.optString("url"),music.optString("token"));
            if(error!=null){transports.issue("Muzyka: "+error);try{if(before==null)merged.remove("music_assistant");else merged.put("music_assistant",before);}catch(Exception ignored){}}
        }
        if(merged.toString().equals(current.toString()))return;
        boolean restartHa=ConnectionMerge.haRestart(current,merged),restartMa=ConnectionMerge.maRestart(current,merged);
        if(!save(merged,gen,false))return; // the probe took time: a pairing in between wins; the session keeps its generation
        if(restartHa)transports.restartHa();
        if(restartMa)transports.restartMusic();
    }
}
```

(`import org.json.JSONException;` obok `JSONObject`.)

`HeliosService` (zastępuje `reconfigure`/`pair`/`sameHa`):

```java
    private final ConnectionController connections=new ConnectionController(
        new ConnectionController.Store(){
            public JSONObject current(){return connection;}
            public boolean save(JSONObject value){ // commit(): the old identity is gone in HA, an apply() lost to a restart would strand the clock
                boolean ok=getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",value.toString()).commit();
                if(ok){connection=value;main.post(HeliosService.this::notifyConnection);} // every persisted change reaches the activity (diagnostics_url alone included), restart or not
                return ok;}
            public boolean clear(){boolean ok=getSharedPreferences("helios",MODE_PRIVATE).edit().remove("connection").commit();if(ok){connection=null;main.post(HeliosService.this::notifyConnection);}return ok;}},
        MusicAssistantClient::probe,
        new ConnectionController.Transports(){
            public void restartAll(){main.post(()->{resetAppearance();stopHa();stopMusic();startHa();startMusic();});} // pairing: everything from scratch
            public void restartHa(){main.post(()->{stopHa();startHa();});} // pipeline/dashboard changed: the HA session only, music untouched
            public void restartMusic(){main.post(()->{stopMusic();startMusic();});}
            public void stopAll(){main.post(()->{stopHa();stopMusic();});} // transports only: the in-memory connection (with its auth_invalid flag) stays for authInvalid(); Store.clear() is what forgets it
            public void issue(String text){main.post(()->{musicIssue=text;publishMusic();});}});
    private Runnable onConnectionChanged;
    void setOnConnectionChanged(Runnable r){onConnectionChanged=r;}
    private void notifyConnection(){if(onConnectionChanged!=null)onConnectionChanged.run();} // MainActivity: config=service.connection(); detachHa(); attachHa()
    boolean authInvalid(){return !connections.startAllowed();} // the controller owns both the persisted flag and the unsaved memory (tested there)
    void pairedWith(JSONObject response,String url,Consumer<String> done){network.execute(()->{String error=connections.pairedWith(response,url);main.post(()->done.accept(error));});}
    /** Called by the device client of the current HA session; gen was read when the session's client was created. */
    void applyConnection(JSONObject event,int gen){network.execute(()->connections.applyConnection(event,gen));}
    boolean legacyConnection(){return connection!=null&&connection.optInt("protocol",1)<2;}
    void setOnAuthInvalid(Runnable r){onAuthInvalid=r;}
```

W `startHa()`: `if(authInvalid())return;` i `final int gen=connections.generation();` przekazywany do listenerów tej sesji: `cache` dostaje `onAuthInvalid(){network.execute(()->{String r=connections.markAuthInvalid(gen);main.post(()->{if(diagnostics!=null)diagnostics.accept("auth_invalid",r);if(!ConnectionController.IGNORED.equals(r)&&onAuthInvalid!=null)onAuthInvalid.run();});});}` (po `SAVED` flaga jest w `connection` w pamięci i na dysku, po `SAVE_FAILED` kontroler pamięta ją w procesie - w obu przypadkach `authInvalid()` = `!connections.startAllowed()` daje `true`, dotknięcie statusu otwiera parowanie bez restartu, a `startHa()` nie startuje; sukces `pairedWith` otwiera bramkę - sekwencja `SAVE_FAILED → pairedWith → startAllowed()` jest w `ConnectionControllerTest`) - callback UI (komunikat i ekran parowania) tylko dla zgodnej generacji; stara sesja po `pairedWith` zostaje bez śladu w UI (test kontrolera `markAuthInvalid(old)==IGNORED` bez wywołań transportów; smoke B7: `/auth-invalid` wysłane do starego gniazda tuż po ponownym parowaniu nie otwiera okna) (`stopAll` w `Transports` zatrzymuje HA i muzykę, `connection` zostaje w pamięci; przy `SAVE_FAILED` ten proces nie użyje już tokena dzięki `startAllowed()==false`, a po restarcie jedna próba skończy się kolejnym `auth_invalid` - ograniczenie zapisane w diagnostyce); listener `HeliosDeviceClient` w `startHa` dostaje `onConnection(JSONObject e){applyConnection(e,gen);}` i `onChannelIssue(String t){if(diagnostics!=null)diagnostics.accept("channel",t);main.post(()->{if(gen!=connections.generation())return;channelIssue=t;if(onChannelIssue!=null)onChannelIssue.accept(t);});}`; serwis: pole `channelIssue` (`String channelIssue()`, zerowane przy `connected` przez `onDevice(id!=null)` i przy `pairedWith`), `setOnChannelIssue(Consumer<String>)`. `startMusic`: `playerName=getSharedPreferences("helios",MODE_PRIVATE).getString("device_name","Helios");`. Test `ConnectionControllerTest` dostaje przypadek: `int old=c.generation(); c.pairedWith(...); c.applyConnection(event,old)` → brak zapisu i restartów.

- [ ] **Krok 4:** `./gradlew -q testDebugUnitTest assembleDebug lintDebug` → PASS. Żeby `MainActivity` się kompilowała, w tym zadaniu usuwane są `applyProvisioning`, `refreshPairing`, `pendingProvision` i ciało `pairDialog` (B5 buduje je na nowo), a `connect()` tymczasowo tylko `dashboard.setMessage("Sparuj zegar z HA (menu → Paruj z HA)")`; `NavigationMenu.Actions.refresh` tymczasowo `{}`.
- [ ] **Krok 5: commit** `feat(service): ConnectionController (pairedWith, applyConnection with three-state MA), persistent auth_invalid; bridge provisioning removed from the service (SPEC 0.10 pkt 4.2, 6.3, 8.2)`.

### B5 Onboarding: wykrywanie, adres, sonda, kod, `POST`

**Pliki:** `HaDiscovery.java` (nowy), `PairingClient.java` (nowy), `AddressInput.java` (nowy), `OnboardingGeometry.java` (nowy), `MainActivity.java` (`Onboarding` jako wewnętrzna klasa dialogu, `pairDialog` przerobiony), `PairingClientTest.java` (nowy, `com.sun.net.httpserver.HttpServer`), `AddressInputTest.java` (nowy), `OnboardingGeometryTest.java` (nowy).

**Interfejs:**
- `HaDiscovery(Context, Listener)`: `start()`, `stop()`; `Listener.onServers(List<Server>)` na main; `Server{name, host, port, version}` z `url()` = `http://host:port`. Wewnątrz: `MulticastLock`, `NsdManager.discoverServices("_home-assistant._tcp.",PROTOCOL_DNS_SD,…)`, zbiór `known`, kolejka `resolveService` po jednym, ponowna próba po 2 s, generacja `gen` (wyniki ze starej generacji odrzucane), `stopServiceDiscovery` + `releaseMulticast` w `stop()`.
- `PairingClient` (czysta sieć, bez Androida): `static int probe(String baseUrl)` → 200/404/-1 (timeout 8 s, bez przekierowań); `static Result pair(String baseUrl, JSONObject body)` → `Result{status, JSONObject json}` (connect 8 s, read 60 s, `POST`, body ≤ 4 KiB, odpowiedź ≤ 64 KiB); `static String message(int status)` mapuje 401/409/503/-1 na teksty ze SPEC 4.2.
- `static String AddressInput.parse(String typed)` (czysta): zwraca bazowy URL `http://<host>:<port>` albo `null`; akceptuje `host`, `host:port`, `[v6]`, `[v6]:port` i pełny `http://host[:port]` z pustą ścieżką albo samym `/`; każdy inny schemat (także `https`), userinfo, ścieżka, query lub fragment → `null` - SPEC pkt 3: ręczny adres jest zawsze `http` i tylko bazowy; brak portu → `:8123`; adres z więcej niż jednym dwukropkiem bez nawiasów, pusty host, port poza 1-65535 → `null` (klawiatura zegara i tak ma tylko `0-9 . :`, ale parser nie zakłada tego). Komunikat przy `null`: „Nieprawidłowy adres”.
- `OnboardingGeometry`: `LIST(20,64,440,360)`, wiersze `ROW_H=72`, kolumna przycisków `MANUAL(480,64,300,72)`, `LATER(480,148,300,72)`, `CANCEL(480,392,300,72)`, `STATUS(20,432,440,40)`, klawiatura adresu: klawisze `84×58` jak kod + rząd `. : ⌫` - test: bez nakładania, przyciski ≥ 72 wysokości poza klawiaturą, wiersze ≥ 56.
- `HeliosService.pair(String url,String code,Consumer<String> done)`: na `network` buduje body (`installationId`, `code`, `VERSION_NAME`, `VERSION_CODE`), `PairingClient.pair`, `status != 200` → `done("<PairingClient.message>")`; `200` → `connections.pairedWith(json,url)` **niezależnie od aktywności** (HA już zatwierdził tożsamość; zapis i restart należą do serwisu), wynik do `done` na main. Aktywność tylko pokazuje wynik, jeśli żyje.
- `MainActivity`: `onboarding(boolean allowLater)`: własne pole `onboardingDialog` (nie `panel`): `closePanel()`, `onUnavailable`, zmiana dashboardu i `detachHa()` nigdy go nie zamykają; zamyka je wyłącznie użytkownik („Anuluj”/„Później”, gdy odblokowane) i sukces parowania; stany `LIST → INSTRUCTION → PROBING → CODE → PAIRING`; „Później” tylko gdy `allowLater`; przy istniejącym połączeniu (nie legacy) przed kodem `confirmDialog("Zegar jest sparowany z <url>. Nowe parowanie zastąpi to połączenie; stary wpis usuń w HA.","Dalej",…)`; `startPairing(String url,String code,Runnable unlock)` - od wysłania `POST` do odpowiedzi: wszystkie klawisze i „Anuluj” `setEnabled(false)`, `dialog.setCancelable(false)`, `setCanceledOnTouchOutside(false)`; `unlock` (odwrotność) wołany na **każdej** ścieżce błędu: błąd budowy JSON, `status != 200`, `json == null`, błąd `pairedWith`; po `isDestroyed()` nic nie jest dotykane. Sukces → `closePanel()`, `pairing=true`, `setOnDeviceChanged` jak dziś daje „Sparowano z HA”. Błąd terminalny z `pairedWith` („Nie udało się zapisać parowania - sparuj ponownie”) → `config=null`, okno wraca do listy (nowe parowanie). `connect()` → `onboarding(false)`; `onCreate`: `if(config==null||config.optBoolean("auth_invalid",false))onboarding(false); else if(config.optInt("protocol",1)<2)onboarding(true);`; `service.setOnAuthInvalid(()->{dashboard.setMessage("HA odrzucił token - sparuj ponownie");})`, `service.setOnConnectionChanged(()->{config=service.connection();detachHa();attachHa();})` (diagnostyka czyta `diagnostics_url` z `config`, listenery przepinane na nowego `HaDashboardClient`); `service.setOnChannelIssue(text->dashboard.setMessage(text))` - „Zegar usunięty z HA - sparuj ponownie” (wynik `unauthorized` z `helios/connect`, bez ponawiania) i „Inne urządzenie przejęło to parowanie” (`replaced`, bez ponawiania) trafiają na pasek statusu; dotknięcie statusu, gdy `service.authInvalid()` albo `service.channelIssue()` zaczyna się od „Zegar usunięty” → `onboarding(false)`.

- [ ] **Krok 1: testy (RED)** `AddressInputTest`:

```java
public class AddressInputTest {
    @Test public void hostsPortsAndUrls(){
        assertEquals("http://<host>:8123",AddressInput.parse("<host>"));
        assertEquals("http://<host>:8443",AddressInput.parse("<host>:8443"));
        assertEquals("http://ha:8123",AddressInput.parse("http://ha:8123/"));
        assertNull("manual addresses are http only (SPEC 0.10 pkt 3)",AddressInput.parse("https://ha.local"));
        assertEquals("http://[fd00::5]:8123",AddressInput.parse("[fd00::5]"));
        assertNull(AddressInput.parse("fd00::5"));assertNull(AddressInput.parse(""));assertNull(AddressInput.parse("1.2.3.4:0"));assertNull(AddressInput.parse("1.2.3.4:70000"));assertNull(AddressInput.parse("ftp://x"));
        assertNull("only a base url",AddressInput.parse("http://ha/inna/sciezka?x=1"));assertNull(AddressInput.parse("http://ha:8123/#f"));assertNull(AddressInput.parse("http://user:pw@ha:8123"));assertNull(AddressInput.parse("http://ha:8123?x=1"));
    }
}
```

`PairingClientTest`:

```java
package pl.mateusz.helios;

import com.sun.net.httpserver.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.net.InetSocketAddress;
import static org.junit.Assert.*;

public class PairingClientTest {
    private static HttpServer server(HttpHandler handler) throws Exception {HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/api/helios/pair",handler);s.start();return s;}
    private static void reply(HttpExchange x,int status,String body) throws IOException {byte[] b=body.getBytes("UTF-8");x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}}
    @Test public void probeDistinguishesIntegrationFromNothing() throws Exception {
        HttpServer s=server(x->reply(x,"GET".equals(x.getRequestMethod())?200:405,"{\"protocol\":2}"));
        try{assertEquals(200,PairingClient.probe("http://127.0.0.1:"+s.getAddress().getPort()));}finally{s.stop(0);}
        HttpServer none=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);none.start();
        try{assertEquals(404,PairingClient.probe("http://127.0.0.1:"+none.getAddress().getPort()));}finally{none.stop(0);}
        assertEquals(-1,PairingClient.probe("http://127.0.0.1:1"));
    }
    @Test public void pairPostsJsonAndReturnsStatusWithBody() throws Exception {
        final String[] seen=new String[2];
        HttpServer s=server(x->{seen[0]=x.getRequestMethod();seen[1]=new String(x.getRequestBody().readAllBytes(),"UTF-8");reply(x,200,"{\"protocol\":2,\"token\":\"tok\",\"pipeline\":\"p\",\"dashboard_path\":\"helios-clock\"}");});
        try{
            PairingClient.Result r=PairingClient.pair("http://127.0.0.1:"+s.getAddress().getPort(),new JSONObject().put("installation_id","abcdefgh-1").put("code","123456").put("app_version","0.9.0").put("version_code",28));
            assertEquals(200,r.status);assertEquals("tok",r.json.getString("token"));assertEquals("POST",seen[0]);assertTrue(seen[1],seen[1].contains("\"code\":\"123456\""));
        }finally{s.stop(0);}
        HttpServer bad=server(x->reply(x,401,"{\"error\":\"unauthorized\"}"));
        try{PairingClient.Result r=PairingClient.pair("http://127.0.0.1:"+bad.getAddress().getPort(),new JSONObject());assertEquals(401,r.status);assertEquals("Kod nieprawidłowy lub wygasł",PairingClient.message(r.status));}finally{bad.stop(0);}
        assertEquals("HA nie dokończył parowania - spróbuj ponownie",PairingClient.message(409));
        assertEquals("HA nie jest gotowy (brak pipeline Assist lub błąd tożsamości)",PairingClient.message(503));
        assertEquals("Brak połączenia z HA",PairingClient.message(-1));
    }
}
```

`OnboardingGeometryTest` jak `FullscreenGeometryTest` (brak nakładania między `LIST`, `MANUAL`, `LATER`, `CANCEL`, `STATUS`; wysokości ≥ 72 dla przycisków; `ROW_H >= 56`; wszystko w 800×480).

- [ ] **Krok 2:** FAIL. **Krok 3: implementacja** `AddressInput`:

```java
package pl.mateusz.helios;

import java.net.URI;

/** Typed HA address to a base URL: host, host:port, [v6], or a full http URL; port 8123 when missing; never anything but http (SPEC 0.10 pkt 3). */
final class AddressInput {
    private AddressInput(){}
    static String parse(String typed){
        String t=typed==null?"":typed.trim();
        if(t.isEmpty())return null;
        String withScheme=t.contains("://")?t:"http://"+t;
        try{
            URI u=new URI(withScheme);
            String scheme=u.getScheme(),host=u.getHost();int port=u.getPort();
            if(host==null||host.isEmpty()||!"http".equals(scheme))return null;
            if(u.getUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!(u.getRawPath().isEmpty()||u.getRawPath().equals("/")))return null; // a base url only
            if(port==-1)port=8123;
            if(port<1||port>65535)return null;
            return scheme+"://"+host+":"+port; // URI.getHost() keeps the brackets of an IPv6 literal
        }catch(Exception e){return null;}
    }
}
```

(`new URI("http://1.2.3.4:0")` daje port 0 → `null`; `70000` → `URISyntaxException`/port -1 z hostem `1.2.3.4:70000`? Nie: `URI` odrzuca taki port jako składnię - w obu przypadkach wynik `null`; `fd00::5` bez nawiasów → `getHost()==null` → `null`.)

`PairingClient`:

```java
package pl.mateusz.helios;

import java.io.*;
import java.net.*;
import org.json.JSONObject;

/** The clock side of /api/helios/pair (SPEC 0.10 pkt 3-4): plain HttpURLConnection, no redirects, bounded bodies. */
final class PairingClient {
    static final class Result {final int status;final JSONObject json;Result(int status,JSONObject json){this.status=status;this.json=json;}}
    private PairingClient(){}
    static int probe(String baseUrl){
        HttpURLConnection c=null;
        try{c=(HttpURLConnection)new URL(baseUrl.replaceAll("/$","")+"/api/helios/pair").openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
            int status=c.getResponseCode();
            if(status==200){JSONObject body=read(c.getInputStream());return body!=null&&body.optInt("protocol")==2?200:404;}
            return status;
        }catch(Exception e){return -1;}finally{if(c!=null)c.disconnect();}
    }
    static Result pair(String baseUrl,JSONObject body){
        HttpURLConnection c=null;
        try{
            byte[] bytes=body.toString().getBytes("UTF-8");
            c=(HttpURLConnection)new URL(baseUrl.replaceAll("/$","")+"/api/helios/pair").openConnection();c.setConnectTimeout(8000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();
            InputStream in=status>=400?c.getErrorStream():c.getInputStream();
            return new Result(status,in==null?null:read(in));
        }catch(Exception e){return new Result(-1,null);}finally{if(c!=null)c.disconnect();}
    }
    private static JSONObject read(InputStream in){
        try(InputStream s=in;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[4096];int n;while((n=s.read(chunk))!=-1){if(bytes.size()+n>65536)return null;bytes.write(chunk,0,n);}
            return new JSONObject(bytes.toString("UTF-8"));
        }catch(Exception e){return null;}
    }
    static String message(int status){
        switch(status){
            case 401:return "Kod nieprawidłowy lub wygasł";
            case 409:return "HA nie dokończył parowania - spróbuj ponownie";
            case 503:return "HA nie jest gotowy (brak pipeline Assist lub błąd tożsamości)";
            case -1:return "Brak połączenia z HA";
            default:return "HA odpowiedział błędem "+status;
        }
    }
}
```

`HaDiscovery`:

```java
package pl.mateusz.helios;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** mDNS discovery of Home Assistant (SPEC 0.10 pkt 3): serial resolves, lost services dropped, results of an old generation ignored. */
final class HaDiscovery {
    static final class Server {
        final String name,host,version;final int port;
        Server(String name,String host,int port,String version){this.name=name;this.host=host;this.port=port;this.version=version;}
        String url(){return "http://"+host+":"+port;}
    }
    interface Listener {void onServers(List<Server> servers);}
    static final String TYPE="_home-assistant._tcp.";
    private final NsdManager nsd;private final WifiManager.MulticastLock lock;private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Server> resolved=new LinkedHashMap<>();private final Set<String> known=new HashSet<>();
    private final Deque<NsdServiceInfo> queue=new ArrayDeque<>();private final Map<String,Integer> retries=new HashMap<>();
    private boolean resolving;private int gen;private NsdManager.DiscoveryListener discovery;

    HaDiscovery(Context context,Listener listener){
        nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        lock=wifi==null?null:wifi.createMulticastLock("helios-mdns");
        this.listener=listener;
    }
    void start(){
        stop(); // idempotent: a second start never stacks a discovery or the multicast lock
        final int g=++gen;resolved.clear();known.clear();queue.clear();retries.clear();resolving=false;
        if(lock!=null&&!lock.isHeld()){lock.setReferenceCounted(false);lock.acquire();}
        discovery=new NsdManager.DiscoveryListener(){
            public void onStartDiscoveryFailed(String t,int e){}public void onStopDiscoveryFailed(String t,int e){}
            public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}
            public void onServiceFound(NsdServiceInfo info){main.post(()->{if(g!=gen)return;known.add(info.getServiceName());queue.add(info);drain(g);});}
            public void onServiceLost(NsdServiceInfo info){main.post(()->{if(g!=gen)return;known.remove(info.getServiceName());resolved.remove(info.getServiceName());publish();});}
        };
        try{nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);}catch(Exception e){discovery=null;}
    }
    void stop(){
        gen++;queue.clear();resolving=false; // callbacks of the old generation return before touching any state (gen check first)
        if(discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}
        discovery=null;if(lock!=null&&lock.isHeld())lock.release();
    }
    // A resolve still running from the old generation may keep the system resolver busy for a moment after a quick
    // stop()/start(): the new generation's first resolveService then fails with FAILURE_ALREADY_ACTIVE and takes the 2 s retry.
    private void drain(final int g){
        if(resolving||queue.isEmpty()||g!=gen)return;
        final NsdServiceInfo next=queue.poll();resolving=true;
        nsd.resolveService(next,new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo info,int code){main.post(()->{if(g!=gen)return;resolving=false;int n=retries.merge(info.getServiceName(),1,Integer::sum);if(n<=1&&known.contains(info.getServiceName()))main.postDelayed(()->{if(g==gen){queue.add(info);drain(g);}},2000);else drain(g);});}
            public void onServiceResolved(NsdServiceInfo info){main.post(()->{if(g!=gen)return;resolving=false;if(!known.contains(info.getServiceName())){drain(g);return;}
                if(info.getHost()!=null&&info.getHost().getHostAddress()!=null&&!info.getHost().getHostAddress().contains(":")){
                    resolved.put(info.getServiceName(),new Server(txt(info,"location_name",info.getServiceName()),info.getHost().getHostAddress(),info.getPort(),txt(info,"version","")));publish();}
                drain(g);});}
        });
    }
    private static String txt(NsdServiceInfo info,String key,String fallback){byte[] v=info.getAttributes()==null?null:info.getAttributes().get(key);try{return v==null?fallback:new String(v,"UTF-8");}catch(Exception e){return fallback;}}
    private void publish(){listener.onServers(new ArrayList<>(resolved.values()));}
}
```

`MainActivity.onboarding(boolean allowLater)` - jeden `Dialog` w jednostkach 800×480 (jak `pairDialog`), zawartość przełączana między krokami (`showList()`, `showManual()`, `showInstruction(url)`, `showCode(url)`); klawiatura kodu = dzisiejszy `pairDialog` przeniesiony do `showCode(url)` z `OK` → `startPairing(url, code)`:

```java
    /** From POST to answer the dialog cannot be closed (the HA-side transaction cannot be undone from the clock); unlock runs on every error path. */
    private void startPairing(String url,String code,Runnable unlock){
        if(service==null){unlock.run();return;}
        dashboard.setMessage("Paruję z HA…");
        service.pair(url,code,error->{ // the service persists a 200 even if this activity is gone meanwhile
            if(isDestroyed())return;
            if(error!=null){dashboard.setMessage(error);config=service.connection();unlock.run();if(config==null)showList();return;} // terminal save failure: back to the list
            pairing=true;closePanel();config=service.connection();onEvent("configured","Helios "+BuildConfig.VERSION_NAME);startWake();attachHa();
            main.postDelayed(()->{if(pairing){pairing=false;dashboard.setMessage("HA nie potwierdził połączenia");}},20000);
        });
    }
```

`HeliosService.pair`:

```java
    void pair(String url,String code,Consumer<String> done){
        network.execute(()->{
            String error;
            try{
                JSONObject body=new JSONObject().put("installation_id",installationId).put("code",code).put("app_version",BuildConfig.VERSION_NAME).put("version_code",BuildConfig.VERSION_CODE);
                PairingClient.Result r=PairingClient.pair(url,body);
                error=r.status!=200||r.json==null?PairingClient.message(r.status):connections.pairedWith(r.json,url);
            }catch(Exception e){error="Błąd przygotowania żądania";}
            final String result=error;main.post(()->done.accept(result));
        });
    }
```

Klawiatura kodu: `OK` → `lock(true); startPairing(url, code, ()->lock(false));` gdzie `lock(boolean on)` ustawia `setEnabled(!on)` na klawiszach i „Anuluj”, `dialog.setCancelable(!on)`, `dialog.setCanceledOnTouchOutside(!on)`.

```java
```

Krok `showInstruction(url)`: tekst „W HA: Ustawienia → Integracje → Dodaj → Helios. Gdy zobaczysz kod, dotknij Dalej.”; „Dalej” → `network.execute(()->{int s=PairingClient.probe(url);main.post(()->{if(s==200)showCode(url);else showInstruction(url, s==404?"HA pod tym adresem nie ma integracji Helios ≥ 0.8 albo kreator nie jest otwarty. Zainstaluj ją z HACS, otwórz Dodaj → Helios i dotknij Ponów.":"HA nie odpowiada pod tym adresem.");});})` z przyciskami „Ponów”/„Wróć”. Krok `showManual()`: klawiatura `0-9 . : ⌫ OK`, `OK` → `String url=AddressInput.parse(typed)`; `null` → status „Nieprawidłowy adres” (klawiatura zostaje), inaczej `showInstruction(url)`. Krok `showList()`: `HaDiscovery.start()` w `onShow`, `stop()` w `onDismiss`; wiersze `Theme.button` z dwoma liniami (`name` / `host:port · version`), „Wpisz adres”, „Później” (gdy `allowLater`), „Anuluj” (gdy `config!=null`), status „Szukam HA w sieci…” → po 10 s bez wyników „Nie znaleziono HA w sieci”. Istniejące nie-legacy połączenie: przed `showInstruction` `confirmDialog(...)`.

- [ ] **Krok 4:** testy + lint PASS; emulator: ścieżka „Wpisz adres” z fixture (B7). `HaDiscovery` nie ma testu JVM (Androidowy `NsdManager`): scenariusz na zegarze w B8 - otwarcie okna dwa razy z rzędu (jeden wpis na serwer, bez duplikatów), wyłączenie jednego HA w trakcie (wiersz znika), zamknięcie okna w trakcie rozwiązywania (brak wiersza po ponownym otwarciu do czasu ponownego odkrycia), `dumpsys wifi` bez wiszącej blokady multicast po zamknięciu. **Commit** `feat(onboarding): HA discovery over mDNS, manual address, capability probe, code pairing over HTTP (SPEC 0.10 pkt 3, 4.2)`.

### B6 Menu, aktualizacje, manifest, build

**Pliki:** `NavigationMenu.java`, `Updater.java` (nowy, z wewnętrznym `AndroidHost`), `UpdateReceiver.java` (nowy), `AndroidManifest.xml`, `app/build.gradle`, `MainActivity.java` (wiring), `UpdaterTest.java` (nowy: decyzje, przekierowania, pobieranie, `run()` przez fałszywy `Host`, odtwarzanie, statusy odbiornika).

**Interfejs:**
- `NavigationMenu.Actions {talk, cancel, pair, device, update}`; pozycje: „Paruj z HA” → `onboarding(false)`; „Aktualizacja Heliosa” → `actions.update()`; bez „Odśwież parowanie”.
- Stan trwały aktualizacji: prefs `update_operation` = JSON `{id, started, file, session}` (`session` = -1 do `createSession`), zapis zawsze przez `commit()`; w procesie tylko `volatile boolean active` (operacja przed `commit`). Menu: pozycja „Aktualizacja Heliosa” `setEnabled(!updater.busy())` przy każdym otwarciu (B6 krok 3).
  - `static String restoreDecision(boolean hasRecord,Boolean sealed)` (czysta, test) - używana **tylko** przy odtwarzaniu po starcie procesu (w trakcie działania `active` mówi prawdę): `!hasRecord` → `"none"`; `sealed==TRUE` → `"busy"` (bez limitu czasu: zatwierdzona sesja czeka na użytkownika lub system); `sealed==FALSE` → `"abandon"` (proces zginął podczas zapisu APK do sesji); `sealed==null` (brak sesji) → `"abandon"` (proces zginął przed `createSession`; nic już tej operacji nie dokończy).
  - `Updater` jest orkiestratorem bez Androida nad `interface Updater.Host` (wszystko, co dotyka systemu): `boolean canInstall()`, `void openInstallSettings()`, `JSONObject release() throws IOException` (GET API), `void download(url,target,expected) throws IOException` (produkcja: `Updater.download(…, ReleaseInfo::allowedHost)`), `String check(File,String version)` (`null`/`"ok"` = APK zgodny: pakiet, `versionName`, `versionCode`; inaczej tekst błędu), `int createSession(long size)`, `void write(int session,File)`, `void commit(int session,String operation,File)` (ponowne `openSession` + `PendingIntent` + `commit`), `void abandon(int session)`, `Boolean sealed(int session)` (`null` = brak sesji w `getMySessions()`), `JSONObject record()`, `boolean saveRecord(JSONObject|null)` (prefs `update_operation`, `commit()`; `null` = kasowanie), `File cacheDir()`, `void deleteFile(String)`, `void status(String)`, `void launch(Intent)`. Produkcyjna implementacja `AndroidHost` w `Updater.java` (klasa wewnętrzna) to cienkie wywołania API. Testy JVM sterują fałszywym `Host`.
  - `Updater(Host host,String versionName,int versionCode)`: `run()`: (a) `busy()` → `status("Aktualizacja w toku…")`; (b) `!host.canInstall()` → `openInstallSettings()` + status; (c) `release()` (`IOException` → „Brak połączenia z GitHub”) → `ReleaseInfo.parse` (`null` → „Brak wydań”); (d) `decision` ≠ `"newer"` → „Masz najnowszą wersję (<versionName>)”; (e) `active=true`; `record={id, started, file, session:-1}` → `saveRecord` (`false` → „Nie udało się zapisać stanu aktualizacji”, `active=false`, koniec); `status("Pobieram Helios <v>…")`; `download` (błąd → `fail("Brak połączenia z GitHub")`); (f) `check` (błąd → `fail(text)`); (g) `createSession` → `record.session` → `saveRecord` (`false` → `abandon` + `fail("Nie udało się zapisać stanu aktualizacji")`); (h) `write` (błąd → `abandon` + `fail("Instalacja nieudana")`); (i) `commit` (błąd → `abandon` + `fail("Instalacja nieudana")`), sukces: `active=false`, rekord zostaje (od teraz `busy()` = rekord + `sealed(session)==TRUE`). `fail(text)` = `status(text)` + `cleanup()` = `deleteFile(record.file)` + `saveRecord(null)` + `active=false`.
  - `boolean busy()` = `active || restoreDecision(record()!=null, record()==null?null:sealed(record.session))=="busy"`.
  - `restore()` (start serwisu): `restoreDecision`: `"abandon"` → `abandon(session)` gdy `session>=0` i `sealed(session)!=null`, `deleteFile`, `saveRecord(null)`; `"busy"`/`"none"` → nic; dodatkowo każdy `helios-update-*.apk` w `cacheDir()` poza `record.file` → `deleteFile`.
  - `onStatus(String operation,int status,Intent confirm,String file,String message)` (wołane przez `UpdateReceiver`; `message` = `EXTRA_STATUS_MESSAGE`): rekord `null` albo `record.id != operation` → tylko `deleteFile(file)` (cudza/stara operacja, także dla `STATUS_PENDING_USER_ACTION` - nie uruchamiamy potwierdzenia nie naszej sesji); `STATUS_PENDING_USER_ACTION` → `launch(confirm z FLAG_ACTIVITY_NEW_TASK)`, rekord zostaje; status końcowy → `deleteFile(file)` + `saveRecord(null)`, `STATUS_SUCCESS` bez komunikatu, inne → `status("Instalacja odrzucona: "+message)` (pusty `message` → `"status <kod>"`).
  - `static void download(String url,File target,long expected)`: jak w kroku 3 niżej - limit `MAX_BYTES` zawsze; `expected` porównywane tylko gdy `> 0`: w trakcie `total > expected` = błąd, po EOF `total != expected` = błąd „Niepełne pobranie”.
- `UpdateReceiver` (manifest, `exported=false`, `ACTION="pl.mateusz.helios.UPDATE_STATUS"`): buduje `Updater` z `AndroidHost(context)` (stan jest w prefs i w `PackageInstaller`, więc nie zależy od życia serwisu) i woła `updater.onStatus(intent.getStringExtra("operation"), intent.getIntExtra(EXTRA_STATUS,-1), intent.getParcelableExtra(EXTRA_INTENT), intent.getStringExtra("file"), intent.getStringExtra(EXTRA_STATUS_MESSAGE))`; `status(...)` w `AndroidHost` poza serwisem = `Toast`. Serwis po każdym odbiorze odświeża `busy()` przy następnym otwarciu menu (liczone na żądanie, bez cache).
- Manifest: uprawnienia `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`, `REQUEST_INSTALL_PACKAGES`; `<receiver android:name=".UpdateReceiver" android:exported="false"/>`. `build.gradle`: bez `provisionFile`/`PROVISION_URL`; `versionCode 28`, `versionName '0.9.0'`.

- [ ] **Krok 1: test (RED)** `UpdaterTest` (czyste metody statyczne oraz `download` na lokalnym `HttpServer` - `download` przyjmuje `Function<String,HttpURLConnection>` `opener`? Nie: test używa hosta `127.0.0.1`, więc `allowedHost` odrzuciłby przekierowania. `download(url,target,expected,HostPolicy policy)` z `interface HostPolicy {boolean allowed(String url);}`; produkcja przekazuje `ReleaseInfo::allowedHost`, test `u->true` albo politykę odrzucającą):

```java
public class UpdaterTest {
    @Test public void decisionRedirectPolicyAndRestore(){
        assertEquals("newer",Updater.decision("0.9.1","0.9.0"));assertEquals("same",Updater.decision("0.9.0","0.9.0"));assertEquals("older",Updater.decision("0.8.18","0.9.0"));
        assertEquals("https://objects.githubusercontent.com/a?b=1",Updater.nextHop("https://objects.githubusercontent.com/a?b=1","https://github.com/x",ReleaseInfo::allowedHost));
        assertEquals("https://github.com/rel/x",Updater.nextHop("/rel/x","https://github.com/a/b",ReleaseInfo::allowedHost));
        assertNull(Updater.nextHop("http://github.com/x","https://github.com/a",ReleaseInfo::allowedHost));assertNull(Updater.nextHop("https://evil.com/x","https://github.com/a",ReleaseInfo::allowedHost));
        assertNull(Updater.nextHop("https://githubusercontent.com.evil/x","https://github.com/a",ReleaseInfo::allowedHost));
        assertEquals("none",Updater.restoreDecision(false,null));assertEquals("busy",Updater.restoreDecision(true,Boolean.TRUE));
        assertEquals("abandon",Updater.restoreDecision(true,Boolean.FALSE));assertEquals("abandon",Updater.restoreDecision(true,null));
    }
    /** Fake Android surface: every stateful path of run()/restore()/onStatus() is driven through it. */
    static final class Host implements Updater.Host {
        final List<String> log=new ArrayList<>();JSONObject record;boolean canInstall=true,saveOk=true,clearOk=true,downloadOk=true,createOk=true,writeOk=true,commitOk=true;int saveFailAt=-1,saves;JSONObject release;String check="ok";Boolean sealed;int nextSession=41;
        public boolean canInstall(){return canInstall;}
        public void openInstallSettings(){log.add("settings");}
        public JSONObject release() throws java.io.IOException {if(release==null)throw new java.io.IOException("offline");return release;}
        public void download(String url,java.io.File target,long expected) throws java.io.IOException {log.add("download:"+url);if(!downloadOk)throw new java.io.IOException("net");}
        public String check(java.io.File file,String version){return check;}
        public int createSession(long size) throws java.io.IOException {log.add("create");if(!createOk)throw new java.io.IOException("create");return nextSession;}
        public void write(int session,java.io.File file) throws java.io.IOException {log.add("write:"+session);if(!writeOk)throw new java.io.IOException("write");}
        public void commit(int session,String operation,java.io.File file) throws java.io.IOException {log.add("commit:"+session+":"+operation);if(!commitOk)throw new java.io.IOException("commit");}
        public void abandon(int session){log.add("abandon:"+session);}
        public Boolean sealed(int session){return sealed;}
        public JSONObject record(){return record;}
        public boolean saveRecord(JSONObject r){
            if(r==null){log.add("clear");if(clearOk)record=null;return clearOk;}
            log.add("save");saves++;boolean ok=saveOk&&saves!=saveFailAt;if(ok)record=r;return ok;
        }
        public java.io.File cacheDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}
        public void deleteFile(String path){log.add("delete");}
        public void status(String text){log.add("status:"+text);}
        public void launch(android.content.Intent intent){log.add("launch");}
    }
    private static final String DL="download:https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk";
    private static JSONObject newer() throws Exception {return new JSONObject().put("tag_name","v0.9.1").put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name","helios-0.9.1.apk").put("browser_download_url","https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk").put("size",6_000_000)));}
    @Test public void runCoversPermissionReleaseVersionAndFailures() throws Exception {
        Host h=new Host();Updater u=new Updater(h,"0.9.0",28);
        h.canInstall=false;u.run();assertEquals(Arrays.asList("settings","status:Zezwól Heliosowi na instalację, potem powtórz"),h.log);assertNull("no record without permission",h.record);h.log.clear();h.canInstall=true;
        u.run();assertEquals(Arrays.asList("status:Brak połączenia z GitHub"),h.log);h.log.clear();
        h.release=new JSONObject().put("tag_name","v0.9.0").put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name","helios-0.9.0.apk").put("browser_download_url","https://github.com/x").put("size",1)));
        u.run();assertEquals(Arrays.asList("status:Masz najnowszą wersję (0.9.0)"),h.log);h.log.clear();
        h.release=newer();h.check="Nieprawidłowy plik wydania";
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…","download:https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk","status:Nieprawidłowy plik wydania","delete","clear"),h.log);assertNull(h.record);assertFalse(u.busy());h.log.clear();
        h.check="ok";h.writeOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41","status:Instalacja nieudana","abandon:41","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();
        h.writeOk=true;h.saveOk=false;
        u.run();assertEquals("the first record save fails: nothing else happens",Arrays.asList("save","status:Nie udało się zapisać stanu aktualizacji"),h.log);assertFalse(u.busy());h.log.clear();h.saveOk=true;
    }
    @Test public void everyLaterFailureAbandonsAndCleansUp() throws Exception {
        Host h=new Host();Updater u=new Updater(h,"0.9.0",28);h.release=newer();
        h.downloadOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"status:Brak połączenia z GitHub","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.downloadOk=true;
        h.createOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","status:Instalacja nieudana","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.createOk=true;
        h.saves=0;h.saveFailAt=2; // the second save (session id) fails
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","status:Nie udało się zapisać stanu aktualizacji","abandon:41","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.saveFailAt=-1;
        h.commitOk=false;
        u.run();assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41","status:Instalacja nieudana","abandon:41","delete","clear"),h.log);assertFalse(u.busy());h.log.clear();h.commitOk=true;
        h.clearOk=false;h.writeOk=false;
        u.run();assertEquals("a failed clear is reported, not hidden",Arrays.asList("save","status:Pobieram Helios 0.9.1…",DL,"create","save","write:41","status:Instalacja nieudana","abandon:41","delete","clear","status:Nie udało się wyczyścić stanu aktualizacji"),h.log);
        h.sealed=null;assertFalse("an unsealed leftover record is not busy",u.busy());
    }
    @Test public void successfulCommitLeavesADurableOperationAndTheReceiverClearsIt() throws Exception {
        Host h=new Host();Updater u=new Updater(h,"0.9.0",28);h.release=newer();
        u.run();
        assertEquals(Arrays.asList("save","status:Pobieram Helios 0.9.1…","download:https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk","create","save","write:41","commit:41:"+h.record.getString("id")),h.log);
        assertEquals(41,h.record.getInt("session"));h.sealed=Boolean.TRUE;assertTrue("busy from the durable record + sealed session",u.busy());h.log.clear();
        String op=h.record.getString("id");
        u.onStatus("other-op",android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,null,"/tmp/x.apk",null);assertEquals("a foreign pending status is ignored",Arrays.asList("delete"),h.log);h.log.clear();
        u.onStatus(op,android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION,new android.content.Intent(),"/tmp/x.apk",null);assertEquals(Arrays.asList("launch"),h.log);assertTrue(u.busy());h.log.clear();
        u.onStatus(op,android.content.pm.PackageInstaller.STATUS_SUCCESS,null,"/tmp/x.apk",null);assertEquals(Arrays.asList("delete","clear"),h.log);assertNull(h.record);h.sealed=null;assertFalse(u.busy());h.log.clear();
        u.run();h.log.clear();h.sealed=Boolean.TRUE;String op2=h.record.getString("id");
        u.onStatus(op,android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,null,"/tmp/x.apk","aborted");assertEquals("a stale operation id never clears the current record",Arrays.asList("delete"),h.log);assertNotNull(h.record);h.log.clear();
        u.onStatus(op2,android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED,null,"/tmp/y.apk","INSTALL_FAILED_UPDATE_INCOMPATIBLE");assertEquals("a final non-success status reports the installer's reason and clears",Arrays.asList("status:Instalacja odrzucona: INSTALL_FAILED_UPDATE_INCOMPATIBLE","delete","clear"),h.log);assertNull(h.record);assertFalse(u.busy());
    }
    @Test public void restoreAfterProcessDeath() throws Exception {
        Host h=new Host();h.record=new JSONObject().put("id","a").put("session",-1).put("file","/tmp/a.apk").put("started",0);
        new Updater(h,"0.9.0",28).restore();assertEquals(Arrays.asList("delete","clear"),h.log);h.log.clear();
        h.record=new JSONObject().put("id","b").put("session",7).put("file","/tmp/b.apk").put("started",0);h.sealed=Boolean.FALSE;
        new Updater(h,"0.9.0",28).restore();assertEquals(Arrays.asList("abandon:7","delete","clear"),h.log);h.log.clear();
        h.record=new JSONObject().put("id","c").put("session",8).put("file","/tmp/c.apk").put("started",0);h.sealed=Boolean.TRUE;
        Updater u=new Updater(h,"0.9.0",28);u.restore();assertTrue(h.log.isEmpty());assertTrue(u.busy());
        u.run();assertEquals(Arrays.asList("status:Aktualizacja w toku…"),h.log);
    }

    @Test public void downloadFollowsAtMostFiveAllowedRedirectsAndEnforcesSizes() throws Exception {
        byte[] payload=new byte[100_000];new java.util.Random(1).nextBytes(payload);
        com.sun.net.httpserver.HttpServer s=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        s.createContext("/hop",x->{int n=Integer.parseInt(x.getRequestURI().getQuery());x.getResponseHeaders().set("Location",n>0?"/hop?"+(n-1):"/file");x.sendResponseHeaders(302,-1);x.close();});
        s.createContext("/file",x->{x.sendResponseHeaders(200,payload.length);try(java.io.OutputStream o=x.getResponseBody()){o.write(payload);}});
        s.createContext("/short",x->{x.sendResponseHeaders(200,50);try(java.io.OutputStream o=x.getResponseBody()){o.write(payload,0,50);}});
        s.start();String base="http://127.0.0.1:"+s.getAddress().getPort();
        java.io.File target=java.io.File.createTempFile("helios-update-","apk");
        try{
            Updater.download(base+"/hop?4",target,payload.length,u->true);assertEquals(payload.length,target.length());
            try{Updater.download(base+"/hop?5",target,payload.length,u->true);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("przekierowań"));}
            try{Updater.download(base+"/hop?1",target,payload.length,u->!u.contains("/file"));fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("poza GitHub"));}
            try{Updater.download(base+"/file",target,50,u->true);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("większy"));}
            try{Updater.download(base+"/short",target,payload.length,u->true);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Niepełne"));}
            Updater.download(base+"/file",target,0,u->true);assertEquals("unknown size: only the hard cap applies",payload.length,target.length());
            try{Updater.download(base+"/file",target,0,u->true,50_000L);fail();}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("większy"));} // the hard cap, injected small
        }finally{s.stop(0);target.delete();}
    }
}
```

- [ ] **Krok 2:** FAIL. **Krok 3:** implementacja `Updater` (fragment sieciowy):

```java
    interface HostPolicy {boolean allowed(String url);}
    static String decision(String latest,String current){int c=Version.compare(latest,current);return c>0?"newer":c==0?"same":"older";}
    static String nextHop(String location,String base,HostPolicy policy){
        try{if(location==null)return null;String abs=new URL(new URL(base),location).toString();return policy.allowed(abs)?abs:null;}catch(Exception e){return null;}
    }
    static String restoreDecision(boolean hasRecord,Boolean sealed){
        if(!hasRecord)return "none";
        return Boolean.TRUE.equals(sealed)?"busy":"abandon"; // no session (null) or an unsealed one: nothing will ever finish it
    }
    /** Manual redirects (at most 5, each target through the policy), streaming with the hard cap; expected>0 must match exactly. */
    static void download(String url,File target,long expected,HostPolicy policy) throws IOException {download(url,target,expected,policy,ReleaseInfo.MAX_BYTES);}
    static void download(String url,File target,long expected,HostPolicy policy,long maxBytes) throws IOException {
        for(int hop=0;hop<=5;hop++){
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
            try{
                int status=c.getResponseCode();
                if(status/100==3){if(hop==5)break;String next=nextHop(c.getHeaderField("Location"),url,policy);if(next==null)throw new IOException("Przekierowanie poza GitHub");url=next;continue;}
                if(status!=200)throw new IOException("HTTP "+status);
                long total=0;byte[] buf=new byte[65536];
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
                    int n;while((n=in.read(buf))!=-1){total+=n;if(total>maxBytes||(expected>0&&total>expected))throw new IOException("Plik większy niż zapowiedziany");out.write(buf,0,n);}
                }
                if(expected>0&&total!=expected)throw new IOException("Niepełne pobranie");
                return;
            }finally{c.disconnect();}
        }
        throw new IOException("Za dużo przekierowań");
    }
```

Orkiestrator (`Updater.java`; testy JVM widzą stałe `PackageInstaller.STATUS_*` i klasę `Intent` z `android.jar` bez ich wykonywania; `new android.content.Intent()` w teście JVM działa dzięki `testOptions { unitTests.returnDefaultValues = true }` w `build.gradle` - dopisać, dziś brak):

```java
package pl.mateusz.helios;

import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.io.*;
import java.security.SecureRandom;
import org.json.JSONObject;

/** GitHub Releases updater (SPEC 0.10 pkt 7): all decisions here, every system call behind Host so the JVM tests drive it. */
final class Updater {
    interface Host {
        boolean canInstall();void openInstallSettings();
        JSONObject release() throws IOException;
        void download(String url,File target,long expected) throws IOException;
        /** null or "ok" when the archive is pl.mateusz.helios with versionName==version and a higher versionCode; otherwise the message to show. */
        String check(File file,String version);
        int createSession(long size) throws IOException;
        void write(int session,File file) throws IOException;
        void commit(int session,String operation,File file) throws IOException;
        void abandon(int session);
        /** null when PackageInstaller.getMySessions() has no such session. */
        Boolean sealed(int session);
        JSONObject record();boolean saveRecord(JSONObject record);
        File cacheDir();void deleteFile(String path);
        void status(String text);void launch(Intent intent);
    }
    private final Host host;private final String versionName;private final int versionCode;
    private volatile boolean active; // an operation running in this process (before commit)
    Updater(Host host,String versionName,int versionCode){this.host=host;this.versionName=versionName;this.versionCode=versionCode;}

    boolean busy(){
        if(active)return true;
        JSONObject record=host.record();
        return "busy".equals(restoreDecision(record!=null,record==null?null:host.sealed(record.optInt("session",-1))));
    }
    /** Menu entry; network thread. */
    void run(){
        if(busy()){host.status("Aktualizacja w toku…");return;}
        if(!host.canInstall()){host.openInstallSettings();host.status("Zezwól Heliosowi na instalację, potem powtórz");return;}
        JSONObject release;
        try{release=host.release();}catch(IOException e){host.status("Brak połączenia z GitHub");return;}
        ReleaseInfo info=ReleaseInfo.parse(release);
        if(info==null){host.status("Brak wydań");return;}
        if(!"newer".equals(decision(info.version,versionName))){host.status("Masz najnowszą wersję ("+versionName+")");return;}
        active=true;
        byte[] rnd=new byte[8];new SecureRandom().nextBytes(rnd);StringBuilder id=new StringBuilder();for(byte b:rnd)id.append(String.format("%02x",b));
        File file=new File(host.cacheDir(),"helios-update-"+id+".apk");
        JSONObject record;
        try{record=new JSONObject().put("id",id.toString()).put("started",System.currentTimeMillis()).put("file",file.getPath()).put("session",-1);}catch(Exception e){active=false;return;}
        if(!host.saveRecord(record)){host.status("Nie udało się zapisać stanu aktualizacji");active=false;return;} // nothing else happened yet
        host.status("Pobieram Helios "+info.version+"…");
        int session=-1;
        try{
            try{host.download(info.url,file,info.size);}catch(IOException e){fail(record,session,"Brak połączenia z GitHub");return;}
            String problem=host.check(file,info.version);
            if(problem!=null&&!problem.equals("ok")){fail(record,session,problem);return;}
            try{session=host.createSession(file.length());}catch(IOException e){fail(record,session,"Instalacja nieudana");return;}
            try{record.put("session",session);}catch(Exception ignored){}
            if(!host.saveRecord(record)){fail(record,session,"Nie udało się zapisać stanu aktualizacji");return;}
            try{host.write(session,file);}catch(IOException e){fail(record,session,"Instalacja nieudana");return;}
            try{host.commit(session,record.optString("id"),file);}catch(IOException e){fail(record,session,"Instalacja nieudana");return;}
            // committed: from here the installer owns the session; the durable record + sealed session keep busy() true
        }finally{active=false;}
    }
    private void fail(JSONObject record,int session,String text){
        host.status(text);
        if(session>=0)host.abandon(session);
        cleanup(record);
    }
    private void cleanup(JSONObject record){
        host.deleteFile(record.optString("file"));
        if(!host.saveRecord(null))host.status("Nie udało się wyczyścić stanu aktualizacji"); // busy() may stay true until the next restore
    }
    /** Service start: a record without a sealed session can never finish; a sealed one is waited for. */
    void restore(){
        JSONObject record=host.record();
        if(record!=null){
            int session=record.optInt("session",-1);
            Boolean sealed=session>=0?host.sealed(session):null;
            if("abandon".equals(restoreDecision(true,sealed))){if(sealed!=null)host.abandon(session);cleanup(record);}
        }
        File[] stale=host.cacheDir().listFiles((d,n)->n.startsWith("helios-update-")&&n.endsWith(".apk"));
        String keep=record==null?null:record.optString("file");
        if(stale!=null)for(File f:stale)if(!f.getPath().equals(keep))host.deleteFile(f.getPath());
    }
    /** UpdateReceiver: statuses of foreign or stale operations only drop their file; ours drive the record. message = EXTRA_STATUS_MESSAGE. */
    void onStatus(String operation,int status,Intent confirm,String file,String message){
        JSONObject record=host.record();
        if(record==null||operation==null||!operation.equals(record.optString("id"))){if(file!=null)host.deleteFile(file);return;}
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){if(confirm!=null)host.launch(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        if(status!=PackageInstaller.STATUS_SUCCESS)host.status("Instalacja odrzucona: "+(message==null||message.isEmpty()?"status "+status:message));
        cleanup(record);
    }
}
```

`AndroidHost` (klasa wewnętrzna `Updater.AndroidHost implements Host`, konstruktor `(Context)`): `canInstall` = `pm.canRequestPackageInstalls()`; `openInstallSettings` = `startActivity(new Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:pl.mateusz.helios")).addFlags(FLAG_ACTIVITY_NEW_TASK))`; `release()`:

```java
        public JSONObject release() throws IOException {
            HttpURLConnection c=(HttpURLConnection)new URL(ReleaseInfo.API_URL).openConnection(); // the only metadata address; never a redirect
            c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/vnd.github+json");
            try{
                if(c.getResponseCode()!=200)return null; // 404 = no releases yet, 3xx = refused
                try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(bytes.size()+n>262144)throw new IOException("Odpowiedź za duża");bytes.write(chunk,0,n);}
                    return new JSONObject(bytes.toString("UTF-8"));
                }
            }catch(org.json.JSONException e){throw new IOException("Nieprawidłowa odpowiedź GitHub");}
            finally{c.disconnect();}
        }
```

(`release()==null` → `ReleaseInfo.parse(null)` = `null` → „Brak wydań”); `download` = `Updater.download(url,target,expected,ReleaseInfo::allowedHost)`; `check` = `pm.getPackageArchiveInfo(file.getPath(),0)` + trzy warunki → `"ok"` albo „Nieprawidłowy plik wydania”; `createSession` = `SessionParams(MODE_FULL_INSTALL)` + `setAppPackageName` + `setSize` → `installer.createSession`; `write` = `openSession` → `openWrite("helios.apk",0,len)` → kopia → `fsync` → zamknięcie; `commit` = `openSession` + `PendingIntent.getBroadcast(context,session,new Intent(context,UpdateReceiver.class).setAction(UpdateReceiver.ACTION).putExtra("operation",operation).putExtra("file",file.getPath()),FLAG_MUTABLE|FLAG_UPDATE_CURRENT)` → `commit(sender)`; `sealed` = szukanie `session` w `installer.getMySessions()` → `isSealed()` albo `null`; `record`/`saveRecord` = prefs `update_operation` (`commit()`, `null` = `remove`); `status` = `Toast` w odbiorniku, `dashboard.setMessage` przez callback w serwisie; `launch` = `startActivity`. `NavigationMenu(Activity,Supplier<JSONObject> connection,java.util.function.BooleanSupplier updateBusy,Actions actions)`: usunąć `refresh`; pozycja „Aktualizacja Heliosa” budowana w `show()` z `b.setEnabled(!updateBusy.getAsBoolean())` (menu jest budowane od nowa przy każdym otwarciu, a `busy()` liczy stan na żądanie z prefs i sesji, więc po `commit`, po wyniku odbiornika i po restarcie procesu pozycja jest zawsze aktualna bez osobnego callbacku); `update()` → `actions.update()`. `MainActivity`: `updateBusy = ()->service!=null&&service.updater().busy()`, `update(){service.update();}`; `HeliosService`: pole `updater=new Updater(new Updater.AndroidHost(this),BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE)`, `Updater updater()`, `void update(){network.execute(updater::run);}`, `status` z `AndroidHost` do `main.post(dashboard message)` przez istniejący `diagnostics`/`musicIssue`-podobny callback `setOnStatus`; `onCreate` woła `updater.restore()` na `network`.

- [ ] **Krok 4:** `./gradlew -q testDebugUnitTest assembleDebug lintDebug` → PASS (lint: `UnspecifiedRegisterReceiverFlag` nie dotyczy odbiornika z manifestu; `QueryPermissionsNeeded` bez zmian). **Commit** `feat(update): GitHub Releases updater with PackageInstaller, menu without bridge, manifest permissions, 0.9.0 (SPEC 0.10 pkt 7)`.

### B7 Most, fixture, smoke na emulatorze, raport

**Pliki:** `tools/native_bridge.py`, `tools/deploy_helios.py`, `.local/ui-dashboard-fixture-v2.py`, `artifacts/native-0.9.0-results.md`, `docs/ha-integration.md`, pamięć projektu.

- [ ] **Krok 1: most** - usunąć `--prepare`, `/config`, `provision.json`; ścieżka z `.local/bridge.json` (`{"route": "/<token_urlsafe>"}`, tworzony przy pierwszym starcie, gdy brak); zostają `/helios.apk`, `<route>/install.dex`, `<route>/events`. `deploy_helios.py`: `route` z `bridge.json`. Uruchomić most: baner bez „config expires”.
- [ ] **Krok 2: fixture** - w `handler` WS: po `connected` (i po `appearance`) wysłać `connection` (`pipeline: 'pipe-emu'`, `dashboard_path`, `music_assistant: {'url':'http://10.0.2.2:8095','token':'ui-test-only','sendspin_url':'ws://10.0.2.2:8927/sendspin'}` gdy `--music`, inaczej `None`, `diagnostics_url: None`); `helios/connect` z `protocol` 1 lub 2, `pairing_code` odrzucany `invalid_format`; Serwer fixture przechodzi z `websockets.sync` na `aiohttp` 3.13 (dostępne w domyślnym Pythonie; `websockets` 15 odrzuca każdą metodę poza `GET` w parserze, zanim `process_request` zobaczy żądanie, więc `POST` nie da się tam obsłużyć): jedna `web.Application` na 8765 z trasami `GET /api/websocket` (`web.WebSocketResponse`, dzisiejsza logika `handler(ws)` przepisana na `async for msg in ws` / `await ws.send_json`), `GET /api/helios/appearance/entry1/<id>` (zdjęcie, dziś `http_photo`), `GET /api/helios/pair` → `web.json_response({"protocol": 2})`, `POST /api/helios/pair` → `await request.read()` (aiohttp składa body niezależnie od fragmentacji), `> 4096` bajtów → 400, `json.loads`, `code == "123456"` → `web.json_response({"protocol": 2, "token": "ui-test-only", "pipeline": "pipe-emu", "dashboard_path": "helios-clock"})`, inny → `web.json_response({"error": "unauthorized"}, status=401)`; zdarzenia z serwera sterującego (`push`, `/removed`, `/replaced`, `/auth-invalid`, `/reject-connect` - następne `helios/connect` dostaje `unauthorized`, `/drop` - zamknięcie gniazda WS, `/slow-pair <s>` - opóźnienie odpowiedzi `POST`, `/diagnostics <url>` - nowe `connection` z innym `diagnostics_url`, `/reload-dashboard` - zdarzenie `lovelace_updated`) trafiają do pętli aiohttp przez `asyncio.run_coroutine_threadsafe` (serwer sterujący zostaje wątkowym `ThreadingHTTPServer` na 8099). Test fixture przed smoke (z PC): `curl -s http://127.0.0.1:8765/api/helios/pair` → `{"protocol": 2}`; `curl -s -X POST -H 'Content-Type: application/json' -H 'Transfer-Encoding: chunked' -d '{"installation_id":"abcdefgh-1","code":"123456","app_version":"0.9.0","version_code":28}' http://127.0.0.1:8765/api/helios/pair` → 200 z tokenem `ui-test-only` (fragmentowany POST); zły kod → 401. 
- [ ] **Krok 3: smoke** (build `-I .local/ui-smoke.init.gradle`, świeża instalacja `adb uninstall pl.mateusz.helios.uitest`); przypadek nieudanego `commit()` prefs nie da się wywołać na emulatorze - pokrywa go `ConnectionControllerTest` (po `clear` restart aplikacji startuje bez połączenia, czyli od okna parowania); zmiana samego `diagnostics_url` przez `connection` (fixture: sterowanie `/diagnostics <url>` wysyła nowe `connection`) → bez restartu WS (fixture nie widzi nowego `auth`), a kolejne zdarzenie `assist-events` trafia pod nowy adres (fixture loguje POST na `/events`): (a) start bez konfiguracji → ekran „Wybierz Home Assistant” (zrzut), „Wpisz adres” → `10.0.2.2:8765` → instrukcja → Dalej → klawiatura kodu → `000000` → „Kod nieprawidłowy lub wygasł” (klawiatura zostaje) → `123456` → „Paruję z HA…” → „Sparowano z HA”; `assist-events.jsonl`: `configured`; prefs `connection` z `protocol: 2` (przez `run-as … cat shared_prefs/helios.xml` - token nie trafia do raportu); (b) `--music`: po `connection` muzyka gra z fixture MA (uchwyt, panel); (c) `/removed` → w dzienniku `helios/connect` ponownie w ≤ 5 s i `connected`; (d) `/replaced` → status „Inne urządzenie przejęło to parowanie”, brak ponownego `connect` przez 10 s; (e) `/auth-invalid` + `adb shell am force-stop` + start → okno parowania od razu (flaga `auth_invalid` w prefs), status „HA odrzucił token - sparuj ponownie”, fixture nie widzi `auth` po restarcie; ponowne parowanie kasuje flagę i łączy; (f) legacy: `run-as` podmiana `connection` na wersję bez `protocol` → start → okno z „Później” → „Później” zamyka, dashboard działa; (g) menu „Aktualizacja Heliosa” bez repozytorium → „Brak wydań” (GitHub 404; emulator ma internet) - zrzut; (h) sterowanie `/reject-connect` (fixture odpowiada na następne `helios/connect` błędem `unauthorized`) + `/removed` → status „Zegar usunięty z HA - sparuj ponownie”, brak kolejnych `helios/connect` przez 10 s, dotknięcie statusu otwiera okno parowania; `/replaced` → status „Inne urządzenie przejęło to parowanie”, okno się nie otwiera; (i) zerwanie WS (fixture `/drop`) w trakcie `POST` (fixture `/slow-pair 5` opóźnia odpowiedź o 5 s) → okno parowania zostaje otwarte i zablokowane do odpowiedzi, potem „Sparowano z HA”; (j) start legacy z otwartym oknem „Później” + `lovelace_updated` z fixture (`/reload-dashboard`) → dashboard się odświeża, okno zostaje.
- [ ] **Krok 4: raport** `artifacts/native-0.9.0-results.md` w stylu 0.8.18 (bez tokenów, SHA256 APK), `docs/ha-integration.md` (już z A6), pamięć projektu (`helios-project-state.md`): 0.9.0 zbudowane, kolejność wdrożenia dla użytkownika.
- [ ] **Krok 5: commit** `docs: 0.9.0 results (plan 0.10: onboarding without token)`; APK przez most po raz ostatni.

### B8 Recenzja implementacji i wdrożenie

- [ ] Warunek wejścia: CI ha-helios zielone (job `tests` z prawdziwą blokadą gniazd na Linuksie), `./gradlew -q testDebugUnitTest assembleDebug lintDebug` zielone, smoke B7 wykonany.
- [ ] Codex: recenzja diffu `ba7f61e..HEAD` w `dash` i `d1248e2..HEAD` w `ha-helios` (prompt jak `.local/review/prompt-impl11.md`, rundy do `VERDICT: APPROVE`).
- [ ] Użytkownik (SPEC pkt 11 krok 3): HACS 0.8.0 → restart HA → sprawdzić zegar 0.8.18 (kryterium 8) → APK 0.9.0 z mostu → „Paruj z HA” → kryteria 1, 3, 6, 7, 10 → usunąć stary token administratora w profilu HA.
- [ ] Wydania `SychPL/helios` (SPEC pkt 7, 11 krok 4): **utworzenie publicznego repozytorium wymaga osobnej zgody użytkownika** (działanie na zewnątrz; przed pierwszym pushem przegląd historii `dash` pod kątem sekretów i adresów LAN w artefaktach). Potem `v0.9.0` (kryterium 14) i `v0.9.1` z `versionCode 29` (kryterium 13).

---

## Ryzyka planu

- HA 2026.8.3 na Windows działa w `.venv314` tylko ze stubami `fcntl`/`resource` i wyłączoną blokadą gniazd (conftest, tylko `win32`); wynik lokalny jest wskazówką, bramką jest CI na Linuksie.
- `test_late_entry_creation_after_timeout_is_undone` opiera się na `ConfigEntriesFlowManager.async_finish_flow(flow, result)` (2026.8.3, `config_entries.py:1672`) - nazwa sprawdzona w źródłach.
- `NsdManager` na OTA 627 i potwierdzenie `PackageInstaller` nad launcherem: tylko na zegarze (kryteria 1, 13).
- `hass_ws_client` w 0.13.357: sprawdzone w zainstalowanym pakiecie - `create_client(hass=hass, access_token=hass_access_token)`; testy wołają `hass_ws_client(hass, access_token=token)` i to jest ostateczna forma.
