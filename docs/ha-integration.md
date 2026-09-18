# Integracja Helios w Home Assistant (0.7 / 0.10)

Zegar łączy się z HA jednym gniazdem WebSocket (tym samym, co dashboard) i rejestruje subskrypcję `helios/connect`. Po stronie HA integracja **Helios** z osobnego repozytorium HACS [SychPL/ha-helios](https://github.com/SychPL/ha-helios) tworzy urządzenie z encjami:

| Encja | Źródło na zegarze |
| --- | --- |
| `sensor.*_wersja_aplikacji` (diagnostyczny) | `BuildConfig.VERSION_NAME` |
| `sensor.*_stan_glosu` | idle / listening / processing / responding / error z `AssistClient` |
| `sensor.*_wersja_docka` (diagnostyczny) | `padVersion` z listenera OEM |
| `binary_sensor.*_dock_podlaczony` | listener połączenia docka; `unknown` do pierwszego zdarzenia |
| `binary_sensor.*_ladowanie_telefonu` | listener ładowania; niedostępny bez docka |
| `light.*_lampka_docka` | `isLedOn` z OEM; jasność = ostatnia przyjęta nastawa 1-10 |
| `number.*_glosnosc_urzadzenia` | `STREAM_MUSIC` 0-100 %, odczyt co 3 s |

Kontrakt kanału i allowlista komend (`lamp.turn_on`, `lamp.turn_off`, `lamp.set_brightness`, `audio.set_device_volume`): [SPEC 0.7](SPEC-0.7-home-assistant-integration.md), plan: [PLAN 0.6/0.7](PLAN-0.6-0.7-implementation.md).

## Instalacja komponentu

1. HACS → Integracje → ⋮ → Niestandardowe repozytoria → `https://github.com/SychPL/ha-helios`, kategoria Integracja → zainstaluj **Helios** ≥ 0.8.0 (alternatywnie skopiuj `custom_components/helios` do `config/custom_components/helios`). Bez zależności pip poza tymi, które HA sam instaluje dla `assist_pipeline` i `music_assistant`.
2. Zrestartuj HA. Zegar z Heliosem 0.8.x (parowany przez most z tokenem administratora) działa dalej - integracja 0.8 przyjmuje protokół 1 i 2.
3. Zainstaluj Heliosa 0.9.0 na zegarze (ostatni raz przez most). Zegar bez konfiguracji pokazuje od razu okno **Wybierz Home Assistant**; zegar ze starym parowaniem pokazuje je z przyciskiem „Później” przy każdym starcie, dopóki nie sparujesz go ponownie.
4. Na zegarze wybierz swój HA z listy (mDNS `_home-assistant._tcp`; przy dwóch serwerach w sieci wybór jest jawny) albo **Wpisz adres**. Zegar pokaże instrukcję i poczeka.
5. W HA: Ustawienia → Urządzenia i usługi → Dodaj integrację → **Helios**. HA pokaże 6-cyfrowy kod ważny 5 minut; zostaw okno otwarte (to ono dokańcza parowanie).
6. Na zegarze dotknij **Dalej** (zegar sprawdza `GET /api/helios/pair`), wpisz kod → OK. Zegar wysyła `POST /api/helios/pair` bez tokena; integracja tworzy mu użytkownika systemowego HA z tokenem na 10 lat, a jeśli w HA jest Music Assistant, także osobny token MA i adres Sendspin (zdarzenie `connection` po połączeniu).
7. Przypisz urządzenie do obszaru (np. Sypialnia). Od następnej rozmowy `assist_pipeline/run` dostaje `device_id` tego urządzenia. Stary token administratora z `.local/ha.json` usuń ręcznie w profilu HA - integracja go nie zna.

Ponowne parowanie tego samego zegara (menu → **Paruj z HA**) odświeża istniejący wpis: nowy użytkownik i token, stare usunięte dopiero po sukcesie. Usunięcie integracji usuwa użytkownika zegara, jego token MA i katalog obrazów; zegar dostaje `auth_invalid`, przestaje się łączyć i prosi o ponowne parowanie. Szczegóły kontraktu (`GET/POST /api/helios/pair`, zdarzenie `connection`, ponowna subskrypcja po `removed`): [SPEC 0.10](SPEC-0.10-onboarding.md).

## Zachowanie

- Encje są `unavailable`, dopóki zegar nie przyśle pierwszego snapshotu po połączeniu; rozłączenie WS lub zapis dashboardu (restart sesji) daje krótkie `unavailable`.
- Polecenia czekają na `helios/result` do 10 s; brak odpowiedzi to błąd usługi, nie ponowienie. Druga komenda dla tego samego zasobu (lampka albo głośność) w trakcie pierwszej dostaje `busy`.
- Music Assistant zainstalowane jako add-on nie pozwala integracji wystawić tokena dla zegara (HA jest tam użytkownikiem systemowym). W **Ustawienia → Urządzenia i usługi → Helios → Konfiguruj** wklej wtedy raz token MA (Music Assistant → Ustawienia → Tokeny) w pole **Token Music Assistant dla zegara**; integracja go nie weryfikuje i nigdy nie unieważnia, a zmiana pola odświeża muzykę przy najbliższym połączeniu zegara.
- Token zegara należy do jego własnego użytkownika systemowego HA (grupa użytkowników, bez administratora, tylko z sieci lokalnej); zegary parowane przed 0.10 nadal używają tokena konta, którym je sparowano.

## Lokalnie na zegarze

Menu → **Urządzenie: głośność i lampka**: suwak głośności (zmiana po puszczeniu), przełącznik lampki, jasność 1-10. Menu → **Paruj z HA** otwiera okno wyboru HA (0.10); most `tools/native_bridge.py` służy już tylko do dostarczania APK w pętli deweloperskiej i jako odbiornik diagnostyki (adres w opcjach integracji), nie do parowania.

Kod, testy (`pip install -r requirements_test.txt && pytest tests`, Python 3.14, HA 2026.8.3) i workflow hassfest/HACS żyją w `SychPL/ha-helios`; ten katalog `ha/` zawiera tylko YAML panelu.

## Pakiet sypialni (SPEC 0.9)

`ha/packages/helios_bedroom.yaml` definiuje trzy pomocniki dla kafelków Rolety / Światło sypialni / Jutro. Wymaga YAML: skopiować do `config/packages/` i mieć `homeassistant: packages: !include_dir_named packages` w `configuration.yaml`, potem restart HA. Publikacja pulpitu w schemacie 4: `python tools/deploy_bedroom_dashboard.py` (preflight), `--apply` po instalacji APK >= 0.8.8, `--rollback <kopia>` przywraca tylko sekcję `helios`.
