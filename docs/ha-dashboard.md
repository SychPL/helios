# Ekran Heliosa konfigurowany w Home Assistant (0.5)

Cały dashboard poniżej stałego górnego paska pochodzi z sekcji `helios` panelu **Helios** (`/helios-clock`). Menu zegara (przytrzymanie HELIOS) i pasek statusu są stałe i lokalne; YAML nie może ich zmienić. Pełny kontrakt, walidacja i zachowanie offline: [SPEC 0.5](SPEC-0.5-ha-configurable-dashboard.md).

## Gdzie zmieniać ustawienia

Otwórz [Helios w HA](http://<home-assistant>/helios-clock), wybierz edycję pulpitu, a następnie z menu ⋮ edytor konfiguracji tekstowej. Zmieniaj główną sekcję `helios`, pozostawiając `views` z kartą instrukcji. Panel wymaga zalogowania kontem administratora; zegar łączy się własnym tokenem. Zapis odświeża zegar bez restartu: stary układ pozostaje zamrożony, aż przyjdzie snapshot encji nowego układu.

Przykład całego dokumentu: [ha/helios-clock.yaml](../ha/helios-clock.yaml). Skrót:

```yaml
helios:
  version: 2
  grid: { columns: 4, rows: 3 }
  items:
    - { id: clock, type: clock, column: 1, row: 1, width: 2, height: 2, title: Dom }
    - { id: weather, type: weather, entity: weather.forecast_dom, column: 3, row: 1, width: 2, height: 1 }
    - id: garage
      type: garage
      entity: cover.brama_garazowa
      column: 3
      row: 3
      width: 2
      height: 1
      title: Brama otwarta
      visible_when: { entity: binary_sensor.helios_show_open_garage_evening, state: "on" }
      confirmation: { enabled: true, text: Zamknąć bramę garażową? }
```

## Reguły w skrócie

- Siatka 4×3; `column`/`row` od 1, `width`/`height` w komórkach; pola nie mogą się nakładać ani wychodzić poza siatkę. Ukryty element zostawia puste miejsce.
- Typy i akcje: `clock` (bez encji), `weather` (encja `weather.*`, opcjonalnie `temperature_entity: sensor.*`), `entity` (dowolna encja, opcjonalnie `attribute`), `light` (`light.*`, dotknięcie = `light.toggle`), `cover` (`cover.*`, panel ▲ ■ ▼ z procentem otwarcia z `current_position`), `garage` (`cover.*`, dotknięcie = `cover.close_cover`, potwierdzenie domyślnie włączone).
- `visible_when` ma dokładnie `entity` i `state`. Logikę (pora dnia, prognoza) licz w HA i wystaw jako `binary_sensor`/pomocnika. `unknown`, `unavailable` i brak encji ukrywają element; po utracie połączenia ostatnia widoczność zostaje zamrożona, a dane oznaczone jako nieaktualne.
- Ikony: `information`, `weather-rainy`, `lightbulb`, `window-shutter`, `garage-open`, `music`. `clock` i `weather` nie przyjmują `icon`.
- `version: 3` dodaje opcjonalny typ `music` (bez `entity`, `tap_action` i `confirmation`, najwyżej jeden): kafelek otwiera bibliotekę i pilota Music Assistant, a przy zdalnym graniu pokazuje nazwę gracza i tytuł. Pole `music_layout` jest niedozwolone. Podczas lokalnego grania uchwyt `♪` przy prawej krawędzi (w połowie wysokości, na kolumnie 4 wiersz 2) zasłania fragment tego kafelka.
- `version: 5` dodaje w typie `entity` opcjonalne pole `off_entity` (encja `light.*`, zwykle grupa świateł). Kafelek nadal pokazuje tekst swojej encji, ale daje się dotknąć: pyta „Zgasić światła?” i wysyła jedno `light.turn_off` na encję z `off_entity`. Potwierdzenie jest domyślnie włączone i można mu zmienić tekst przez `confirmation.text`; `tap_action.action` przyjmuje tu wyłącznie `lights_off`. Bez `off_entity` kafelek `entity` pozostaje nieklikalny.
- Limity: 12 elementów, `title` do 40 znaków, `confirmation.text` do 80 znaków.

## `version: 6` - strony, kafelek `tile`, intencje, ikony `mdi:`

Przykład całego dokumentu: [ha/helios-clock-v6.yaml](../ha/helios-clock-v6.yaml). Wymaga Heliosa 0.12; starszy zegar odrzuca dokument i zachowuje poprzedni układ.

- Korzeń to `version: 6` i `pages` (1-8 stron `{ id, title?, items }`), bez `grid` (siatka jest zawsze 4×3) i bez `items` w korzeniu. Każda strona ma własne 12 pól, własne nakładanie i własny jeden `music`; `id` elementów są unikalne w całym dokumencie. Zegar pokazuje na razie stronę 1, ale czyta i subskrybuje wszystkie strony. Dokument 2-5 to jedna niejawna strona `main`.
- `tile` - jeden kafelek dla encji dowolnej domeny: `entity`, opcjonalnie `title` (domyślnie `friendly_name` encji), `icon`, `attribute` (pokazuje atrybut zamiast stanu), `tap_action.action`, `confirmation`, `visible_when`. Stan w słowach po polsku (światła, rolety, zamki, czujniki binarne wg `device_class`), liczby z jednostką (`21,4 °C`).
- `tap_action.action` to **intencja**, nie nazwa usługi. Dozwolone zależą od domeny encji, a domyślna działa bez `tap_action`:

  | domena | dozwolone | domyślna |
  |---|---|---|
  | `light`, `switch`, `input_boolean`, `fan` | `none`, `details`, `toggle`, `turn_on`, `turn_off` | `details` |
  | `cover` | `none`, `details`, `controls`, `open`, `close`, `stop` | `controls` (panel ▲ ■ ▼) |
  | `lock` | `none`, `details`, `lock`, `unlock` | `details` |
  | `script`, `scene`, `input_button`, `button` | `none`, `details`, `activate` | `activate` |
  | pozostałe (`sensor`, `binary_sensor`, `climate`, ...) | `none`, `details` | `details` |

  `details` otwiera na zegarze okno z wartością encji i przyciskami dozwolonych intencji. `none` czyni kafelek nieklikalnym. Intencja spoza listy domeny odrzuca dokument (`Encja light.x nie obsługuje akcji open`). Zamek (`lock`, `unlock`) zawsze pyta przed wykonaniem; `confirmation.enabled: false` jest dla niego odrzucane.
- Ikony: `icon: mdi:<nazwa>` z katalogu Material Design Icons 7.4.47 wbudowanego w aplikację (te same nazwy, które podpowiada HA). Sześć starych nazw (`information`, `weather-rainy`, `lightbulb`, `window-shutter`, `garage-open`, `music`) nadal działa i oznacza tę samą ikonę `mdi:`. Kafelek `tile` bez `icon` dostaje ikonę domeny (żarówka, roleta, kłódka, oko...), a jeśli encja ma własny atrybut `icon`, pokazuje ten. Nieznana nazwa odrzuca dokument.
- Typy 2-5 (`clock`, `weather`, `entity`, `light`, `cover`, `garage`, `music`, `cover_group`) działają w dokumencie 6 bez zmian; `light`, `cover` i `garage` da się zapisać jako `tile` z intencją `toggle`, `controls` i `close`.
- Każdy błąd (nieznane pole, literówka w ikonie, zła domena, nakładanie) odrzuca cały zapis: zegar zachowuje poprzedni układ i pokazuje komunikat w pasku. Bez żadnej poprawnej konfiguracji `version: 2` zegar pokazuje układ awaryjny (sam zegar) i komunikat `Wymagana konfiguracja Helios version: 2`.

Format `version: 1` z wersji 0.4 nie jest migrowany automatycznie. Po instalacji 0.5 zaktualizuj YAML w HA albo uruchom `python tools/publish_ha_dashboard.py --replace` (Python: `websocket-client`, `PyYAML`; kopia poprzedniej konfiguracji trafia do `.local/`). Zakładka `menu-zegara` z 0.4 nie jest już używana i można ją usunąć.

## Sterowanie i bezpieczeństwo

Zegar wywołuje wyłącznie usługi przypisane w kodzie do intencji (`ActionPolicy`): `toggle`/`turn_on`/`turn_off` w domenach przełączalnych, `cover.open_cover`/`stop_cover`/`close_cover`, `lock.lock`/`unlock`, `script.turn_on`, `scene.turn_on`, `input_button.press`, `button.press` oraz `light.turn_off` dla `off_entity` - zawsze dla encji wpisanej w YAML. Nazwy usług, cele ani dane usług nie pochodzą z konfiguracji; konfiguracja może natomiast uruchomić jawnie wybrany `script`/`scene`, którego skutki należą do HA. Polecenie nie jest ponawiane ani kolejkowane offline; brak odpowiedzi HA w 10 s daje komunikat. Token na zegarze ma prawa użytkownika HA, więc użyj dedykowanego użytkownika bez uprawnień administratora.

Domyślny adres panelu to `helios-clock`. Inny adres można podać w konfiguracji parowania jako `dashboard_path`. Mikrofon i hasło wybudzające nie są sterowane tym YAML.

API sprawdzono w HA 2026.8.3: [konfiguracja Lovelace](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/lovelace/websocket.py), [powiadomienia o zapisie](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/lovelace/dashboard.py), [subskrypcje encji](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/websocket_api/commands.py).
