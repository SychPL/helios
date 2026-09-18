# Ekran Heliosa konfigurowany w Home Assistant (0.5)

Cały dashboard poniżej stałego górnego paska pochodzi z sekcji `helios` panelu **Helios** (`/helios-clock`). Menu zegara (przytrzymanie HELIOS) i pasek statusu są stałe i lokalne; YAML nie może ich zmienić. Pełny kontrakt, walidacja i zachowanie offline: [SPEC 0.5](SPEC-0.5-ha-configurable-dashboard.md).

## Gdzie zmieniać ustawienia

Otwórz [Helios w HA](http://192.168.1.212/helios-clock), wybierz edycję pulpitu, a następnie z menu ⋮ edytor konfiguracji tekstowej. Zmieniaj główną sekcję `helios`, pozostawiając `views` z kartą instrukcji. Panel wymaga zalogowania kontem administratora; zegar łączy się własnym tokenem. Zapis odświeża zegar bez restartu: stary układ pozostaje zamrożony, aż przyjdzie snapshot encji nowego układu.

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
- Każdy błąd (nieznane pole, literówka w ikonie, zła domena, nakładanie) odrzuca cały zapis: zegar zachowuje poprzedni układ i pokazuje komunikat w pasku. Bez żadnej poprawnej konfiguracji `version: 2` zegar pokazuje układ awaryjny (sam zegar) i komunikat `Wymagana konfiguracja Helios version: 2`.

Format `version: 1` z wersji 0.4 nie jest migrowany automatycznie. Po instalacji 0.5 zaktualizuj YAML w HA albo uruchom `python tools/publish_ha_dashboard.py --replace` (Python: `websocket-client`, `PyYAML`; kopia poprzedniej konfiguracji trafia do `.local/`). Zakładka `menu-zegara` z 0.4 nie jest już używana i można ją usunąć.

## Sterowanie i bezpieczeństwo

Zegar wywołuje wyłącznie `light.toggle`, `light.turn_off` (kafelek z `off_entity`), `cover.open_cover`, `cover.stop_cover` i `cover.close_cover` dla encji wpisanej w YAML. Nazwy usług ani dane usług nie pochodzą z konfiguracji. Polecenie nie jest ponawiane ani kolejkowane offline; brak odpowiedzi HA w 10 s daje komunikat. Token na zegarze ma prawa użytkownika HA, więc użyj dedykowanego użytkownika bez uprawnień administratora.

Domyślny adres panelu to `helios-clock`. Inny adres można podać w konfiguracji parowania jako `dashboard_path`. Mikrofon i hasło wybudzające nie są sterowane tym YAML.

API sprawdzono w HA 2026.8.3: [konfiguracja Lovelace](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/lovelace/websocket.py), [powiadomienia o zapisie](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/lovelace/dashboard.py), [subskrypcje encji](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/websocket_api/commands.py).
