# Ekran Heliosa konfigurowany w Home Assistant

## Wizualna edycja ukrytego menu (0.4)

Otwórz [Menu zegara](http://192.168.1.212/helios-clock/menu-zegara), wybierz edycję pulpitu i edytuj karty **Przycisk** wewnątrz siatki. Zmieniaj nazwę oraz akcję dotknięcia (URL), dodawaj/usuwaj przyciski i zmieniaj ich kolejność. Zegar odczytuje ten sam dokument po zapisie — nie trzeba aktualizować APK ani utrzymywać dodatku HA.

Przykładowy przycisk:

```yaml
type: button
name: Ustawienia zegara
icon: mdi:cog
tap_action:
  action: url
  url_path: helios://settings
```

Linki `helios://` definiują akcje wykonywane na zegarze dopiero po dotknięciu jego menu. Klikanie kart na komputerze nie steruje zegarem. Ikony widoczne w HA służą orientacji w edytorze; lista na zegarze jest tekstowa. Obsługiwane są wyłącznie akcje dotknięcia URL/nawigacja, bez dodatkowych warunków widoczności i potwierdzeń. Pełna tabela URL i zachowanie przy błędach: [specyfikacja 0.4](SPEC-0.4-hidden-menu.md).

Nie zmieniaj identyfikatora ścieżki widoku `menu-zegara`; tytuł można zmieniać. Wskaźniki pozostają edytowane jako YAML w sekcji `helios` opisanej niżej. W nowej instalacji uruchom `python tools/add_ha_visual_menu.py` po utworzeniu bazowego panelu; skrypt zachowuje aktualną konfigurację, tworzy kopię zapasową i dodaje brakującą zakładkę. W bieżącym HA zakładka jest już dodana. Skrypt nie nadpisuje istniejącego menu.

## Gdzie zmieniać ustawienia

Otwórz [Helios w HA](http://192.168.1.212/helios-clock), wybierz edycję pulpitu, a następnie z menu ⋮ edytor konfiguracji tekstowej. Zmieniaj główną sekcję `helios`, pozostawiając `views` z kartami podglądu. Panel wymaga zalogowania kontem administratora. Menu zegara ma skrót „Konfiguracja ekranu w HA”.

Przykład z tej instalacji:

```yaml
helios:
  version: 1
  clock: true
  weather: weather.forecast_dom
  indicators:
    - entity: sensor.camera_garage_garage_gate_classification
      name: Garaż
      when: ["open"]
      clear_when: ["close"]
      icon: garage-open
      label: Garaż otwarty
      color: orange
```

Zegar otrzymuje konfigurację z API HA; nie potrzebuje dostępu do plików YAML ani uruchomionego komputera. HA przechowuje panel trwale w swoim magazynie konfiguracji. Edytor pokazuje YAML, API przekazuje ten sam dokument jako JSON. To własny format ekranu Heliosa umieszczony obok standardowej sekcji Lovelace `views`, nie dowolny renderer kart Lovelace.

## Reguły

- Maksymalnie trzy wskaźniki; kolejność listy określa kolejność od lewej.
- `entity`: dowolny identyfikator encji ze stanem tekstowym. Bez Jinja i bez wykonania kodu na zegarze.
- `when`: lista stanów, przy których pojawia się podpis `label` i ikona. Wartości są porównywane dokładnie, z uwzględnieniem wielkości liter.
- `clear_when`: lista stanów, przy których wskaźnik znika. W tym czujniku zamknięcie to **`close`**, nie `closed` (sprawdzono odczytem).
- `unknown`, `unavailable`, usunięta/brakująca encja i utrata połączenia nie oznaczają zamknięcia. Wskaźnik lub baner pokazuje brak danych. Każdy inny nierozpoznany stan jest opisany jako nieznany.
- `name`: do 28 znaków; `label`: do 48 znaków. Krótkie podpisy są czytelniejsze przy kilku wskaźnikach.
- Ikony: `garage-open`, `door-open`, `window-open`, `lightbulb`, `alert`.
- Kolory: `orange`, `red`, `yellow`, `green`, `blue`.
- `weather: null` ukrywa pogodę. `clock: false` ukrywa godzinę i datę. Przytrzymanie HELIOS nadal otwiera ukryte menu.

Nieznane pola, nieobsługiwana wersja, powtórzone encje lub nakładające się reguły powodują komunikat błędu konfiguracji. Ostatnia poprawna definicja jest zachowana, ale aplikacja nie przedstawia starych stanów jako aktualnych. Po poprawieniu YAML połączenie ponowi pobranie (maksymalny odstęp 30 s).

## Aktualizacje na żywo i automatyzacje

Zmiana stanu encji, również przez automatyzację HA, odświeża wskaźnik. Aby zbudować bardziej złożoną regułę (np. garaż otwarty po 22:00), wystaw w HA czujnik szablonowy lub pomocnika i wskaż go w YAML. Nie dodawaj tej logiki do APK.

Zapis konfiguracji panelu wywołuje `lovelace_updated`; Helios pobiera nową definicję i zakłada subskrypcję `subscribe_entities` tylko dla wybranych encji. Początkowy snapshot oraz kolejne różnice stanów są odbierane przez to samo połączenie, bez luki pomiędzy odczytem a nasłuchem. Po zerwaniu połączenia stany są oznaczane jako nieznane, a po powrocie pobierany jest nowy snapshot. Pogoda nadal korzysta z odczytu co dwie minuty.

Przy pierwszym wdrożeniu na innym HA można utworzyć panel poleceniem `python tools/publish_ha_dashboard.py` (Python: `websocket-client`, `PyYAML`). Przykład całego dokumentu: [ha/helios-clock.yaml](../ha/helios-clock.yaml). Skrypt domyślnie odmawia nadpisania istniejącego panelu; `--replace` zapisuje kopię poprzedniej konfiguracji w `.local/` i zastępuje ją plikiem. W bieżącej instalacji panel został już utworzony — dalsze zmiany wykonuj w HA. Plik w repo jest przykładem, nie aktywnym źródłem po pierwszej publikacji.

Domyślny adres panelu to `helios-clock`. Inny adres można podać w konfiguracji parowania jako `dashboard_path`. Mikrofon i hasło wybudzające nie są sterowane tym YAML.

API sprawdzono w HA 2026.8.3: [konfiguracja Lovelace](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/lovelace/websocket.py), [powiadomienia o zapisie](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/lovelace/dashboard.py), [subskrypcje encji](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/websocket_api/commands.py).
