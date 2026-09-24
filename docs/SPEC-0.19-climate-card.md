# SPEC 0.19 - karta termostatu `climate` i pełny panel sterowania

Status: projekt do akceptacji właściciela (recenzja Codex: APPROVE po 4 rundach). Makiety: `docs/mockups/climate-*.png` (skrypt `climate_mock.py`,
czcionki, ikony i kolory motywu z aplikacji, ekran 800x480).

## 1. Cel

Kafelek 1x1 z temperaturą obecną i zadaną. Stuknięcie otwiera pełnoprawny panel: nastawa, tryb, preset,
wentylator i nawiew - tyle, ile dany termostat umie.

Zaprojektowane na czterech rodzajach termostatów z domu właściciela (stan z HA, 2026-09-24):

| Encja | `hvac_modes` | presety | wentylator / nawiew | `supported_features` |
|---|---|---|---|---|
| `climate.salon_termostat` (PID, ogrzewanie podłogowe; tak samo biuro, sypialnie, łazienki) | heat, off | none, away | - | 401 |
| `climate.haier` (klimatyzator) | heat, cool, auto, fan_only, dry, off | - | 4 biegi / 4 pionowe | 425 |
| `climate.gree` (klimatyzator) | auto, cool, dry, fan_only, heat, off | eco, away, boost, none, sleep | 6 biegów / 12 pion + 7 poziom | 953 |
| `climate.komfovent` (rekuperator) | off, heat_cool | 11 (normal, boost, kitchen, fireplace...) | - | 17 |

`climate.biuro_termostat` ma `current_temperature: null` - karta musi to znieść.

## 2. Poza zakresem

Dwie nastawy naraz (`TARGET_TEMPERATURE_RANGE`, bit 2; żaden z termostatów domu go nie ma - pokazywany tylko do
odczytu), wilgotność zadana, harmonogramy, atrybuty producenta (PID `kp/ki/kd`, haier `echo_mode`...).

## 3. Schemat (version 6)

```yaml
- id: salon-termostat
  type: climate
  column: 3
  row: 2
  width: 1
  height: 1
  entity: climate.salon_termostat   # wymagane, domena climate
  title: Salon                      # opcjonalnie; domyślnie friendly_name, potem id encji
  icon: mdi:radiator                # opcjonalnie; domyślnie ikona z hvac_action (pkt 4)
```

- Nowy `CardDefinition`: `climate`, minVersion 6, pola `entity`, `icon`; domena `climate`; akcja stała
  `climate` (nowy `ActionPolicy.Panel.CLIMATE`), bez `tap_action` i `confirmation`; bramka `KNOWN`, a
  `ActionPolicy.needsKnown("climate")` zwraca `true` (dziś pilnowałaby tego tylko bramka widoku).
  Układ `BIG_FIT` (wartość do 44 px, jak bateria w `energy`). Zaprojektowany na 1x1; większy kafelek jest
  dozwolony i pokazuje to samo, tylko większe (bez walidacji rozmiaru - jak `energy`).
- Body w `CardBodies.FOR`; własna ikona z konfiguracji (`ownIcon`) nigdy nie ustępuje ikonie z `hvac_action`.
- Zachowywane atrybuty encji: `current_temperature`, `temperature`, `target_temp_low`, `target_temp_high`,
  `target_temp_step`, `min_temp`, `max_temp`, `hvac_modes`, `hvac_action`, `preset_mode`, `preset_modes`,
  `fan_mode`, `fan_modes`, `swing_mode`, `swing_modes`, `swing_horizontal_mode`, `swing_horizontal_modes`,
  `supported_features`, `friendly_name`. `EntityStates` trzyma wartości jako tekst (`String.valueOf`), więc lista
  z HA trafia tam już dziś jako tekst JSON (`["heat","off"]`); wystarczy helper `list(attribute)`, który ją
  parsuje - bez zmian w `EntityStates`.
- Edytor w panelu HA: typ `Termostat`, wybór encji z domeny `climate`, walidator lustrzany.
- Starszy zegar (przed Helios 0.16) nie zna typu i odrzuca cały dokument - jak przy `energy`.

## 4. Kafelek (`docs/mockups/climate-tile.png`)

- Nagłówek: ikona + tytuł (tytuł z konfiguracji -> `friendly_name` -> id encji). Ikona z `hvac_action`, chyba że
  podano `icon`: `heating`/`preheating` -> `mdi:fire`, `cooling` -> `mdi:snowflake`, `drying` -> `mdi:water-percent`,
  `fan` -> `mdi:fan`, `defrosting` -> `mdi:snowflake-melt`, pozostałe i brak -> `mdi:thermostat`. Kolor akcentu,
  gdy grzeje lub chłodzi, wyciszony w innych stanach.
- Wartość (duża): `current_temperature`, jedno miejsce po przecinku, sam znak stopnia (`23,0°`); `—` bez pomiaru.
- Linia pod spodem, w tej kolejności: stan `off` -> `Wyłączony`; nastawa pojedyncza (pkt 5.1) -> `Nastawa 20,5°`;
  zakres -> `Nastawa 20–24°`; nic z tego -> nazwa trybu.
- `unavailable`/`unknown`: `Brak danych`, stuknięcie nieaktywne.
- Opis dla czytnika: `Salon: teraz 23,0 stopnia, nastawa 20,5, bezczynny` (słowo akcji tylko, gdy HA je podaje).

## 5. Panel pełnoekranowy (`docs/mockups/climate-panel-salon.png`, `climate-panel-gree.png`)

Okno dialogowe nad całym ekranem jak pełny ekran muzyki. Geometria w `ClimatePanelGeometry` według konwencji
`FullscreenGeometry`: obszar bezwzględny `AREA = (0, 52, 800, 428)`, pudełka poniżej względem niego, skalowane
raz. Cele dotyku co najmniej 64 px wysokości.

| Blok | Pudełko (x, y, w, h względem AREA) | Zawartość |
|---|---|---|
| Nagłówek | 0, 0, 720, 64 | ikona akcji, nazwa, status `tryb · preset` albo komunikat |
| Zamknij | 720, 0, 72, 64 | `mdi:close` |
| Teraz | 8, 72, 372, 176 | `Teraz`, obecna temperatura 84 px, akcja słowem (akcent przy pracy) |
| Nastawa | 388, 72, 404, 176 | `Nastawa`, przyciski `−` i `+` 88x88, wartość 64 px |
| Tryb | 8, 252, 784, 64 | etykieta + przyciski segmentowe (pkt 5.2) |
| Listy | 8, 328, 784, 92 | do 4 przycisków-list (pkt 5.3) |

Właścicielem panelu jest `MainActivity` (to samo pole `panel`, co dla rolet): `closePanel()` zamyka go przy
zmianie dokumentu i utracie połączenia z HA - tymi samymi ścieżkami co dziś. `onPause()` dziś panelu nie zamyka,
więc dochodzi tam jawnie, przed odłączeniem HA: zamknięcie panelu termostatu, porzucenie szkicu (bez wysyłania),
anulowanie jego liczników.

### 5.1 Nastawa

- Edycja pojedynczej nastawy tylko, gdy jednocześnie: bit `TARGET_TEMPERATURE` (1), skończona `temperature`,
  skończone `min_temp <= max_temp` i dodatni skończony krok. Pierwszeństwo: pojedyncza nastawa przed zakresem.
  Inaczej: przy bicie `TARGET_TEMPERATURE_RANGE` (2) i skończonych `target_temp_low/high` - `20–24°` tylko do
  odczytu; w pozostałych przypadkach `—`. W obu przypadkach przyciski nieaktywne. Zakresu nie wnioskuje się z
  trybu `heat_cool` (komfovent ma tryb `heat_cool`, ale tylko bit 1).
- Krok: `target_temp_step`; bez niego 0,5 przy jednostce °C i 1 przy °F. Jednostkę zegar czyta raz na sesję z
  `get_config` HA (`unit_system.temperature`); do tego czasu - 0,5. Na ekranie zawsze sam znak stopnia.
- Następna wartość: najpierw krok - `n = round(bieżąca / krok) ± 1`, wartość `n * krok` zaokrąglona do liczby
  miejsc po przecinku kroku - a dopiero potem przycięcie do `min_temp`–`max_temp`, które zwraca granicę dokładnie
  taką, jaką podał HA (bez ponownego zaokrąglania). Przy granicy bliżej niż krok przycisk dociąga więc dokładnie do
  granicy (`max_temp` 30,5 z krokiem 1: 30 -> 30,5). Przycisk na granicy nieaktywny.
- Cykl życia, najprostszy: stuknięcia zmieniają lokalny szkic od razu (wartość w kolorze akcentu). 800 ms po
  ostatnim stuknięciu idzie jedno `climate.set_temperature` z samym `temperature` - nigdy z `hvac_mode`, więc
  nastawa nie zmienia trybu (edycja przy `off` zmienia nastawę, którą termostat użyje po włączeniu).
- **Jedno wywołanie naraz na cały panel**: od wysłania czegokolwiek (nastawa, tryb, preset, wentylator, nawiew)
  wszystkie przyciski sterujące są nieaktywne, a przycisk wywołania ma spinner, aż zajdzie jedno z dwóch:
  (a) HA pokaże w stanie encji wysłaną wartość, (b) minie 10 s własnego licznika potwierdzenia (niezależnego od
  limitu transportu, który `HaDashboardClient` kasuje przy odpowiedzi usługi). Błąd usługi kończy to od razu:
  komunikat w nagłówku, panel pokazuje wartości HA. Po (b) - `Brak potwierdzenia` w nagłówku i wartości HA.
  Nic nie ściga się z niczym i nie ma stuknięć na nieaktualnej wartości. Nigdy nie ponawia sam.
- Szkic jeszcze niewysłany: wysyłany od razu przy zamknięciu panelu (X, wstecz, bezczynność) - użytkownik ustawił
  wartość i odszedł; porzucany przy utracie połączenia, nowym dokumencie i przejściu aplikacji w tło. Szkic liczy się
  już jako wywołanie w toku: przez jego 800 ms tryb i listy są nieaktywne (aktywne zostają tylko `−`/`+`, które
  przedłużają szkic), więc stuknięcie trybu w trakcie edycji nastawy nie ma czego rozstrzygać.

### 5.2 Tryb

- Przycisk na każdy `hvac_mode` w kolejności z HA, bieżący w kolorze akcentu; stuknięcie = `climate.set_hvac_mode`
  z samym `hvac_mode`. Wyłączenie to tryb `off` - nie ma osobnego włącznika.
- Etykiety: `heat` Grzanie, `cool` Chłodzenie, `heat_cool` Grzanie/chł., `auto` Auto, `dry` Osuszanie,
  `fan_only` Wentylator, `off` Wyłączony (przy 5+ trybach `Wył.`). Nieznany tryb: tekst z HA (pkt 5.3).
- Gdy przyciski się nie mieszczą (więcej niż 6 trybów albo etykieta szersza niż przycisk przy 19 px), wiersz
  staje się jednym przyciskiem-listą `Tryb` jak w pkt 5.3.

### 5.3 Listy (Preset, Wentylator, Nawiew)

- Przycisk tylko przy odpowiednim bicie i niepustej liście: `Preset` (16, `preset_modes`), `Wentylator`
  (8, `fan_modes`), `Nawiew` (32, `swing_modes` - bez zakładania osi, bo lista bywa mieszana), `Nawiew poziomy`
  (512, `swing_horizontal_modes`). Szerokość dzielona równo; brak wszystkich = wiersz znika.
- Lista wyboru (`climate-picker-gree.png`): okno nad panelem, wiersze 64 px, najwyżej 5 widocznych, pasek
  przewijania, stały przycisk zamknięcia w nagłówku. Przewijanie nigdy nie wybiera. Stuknięcie poza listą i
  wstecz zamykają tylko listę. Wybór = jedno wywołanie (`set_preset_mode`/`preset_mode`,
  `set_fan_mode`/`fan_mode`, `set_swing_mode`/`swing_mode`,
  `set_swing_horizontal_mode`/`swing_horizontal_mode`) i zamknięcie listy.
- Etykiety po polsku tylko dla wartości standardowych HA (presety: none Brak, away Poza domem, eco Eco,
  boost Boost, sleep Sen, comfort Komfort, home Dom, activity Aktywność; wentylator: auto Auto, low Niski,
  medium Średni, high Wysoki, off Wyłączony; nawiew: off Wyłączony, on Włączony, both Oba, vertical Pionowy,
  horizontal Poziomy). Reszta (`medium low`, `full_swing`, `fireplace`...): tekst z HA, `_` na spację, wielka
  pierwsza litera. Tłumaczeń producentów nie utrzymujemy.

### 5.4 Stan na żywo, błędy, zamykanie

- Panel odświeża się z każdym stanem encji z HA (bez wysyłania czegokolwiek).
- Przyciski sterujące nieaktywne, gdy: brak połączenia z HA (dane nieaktualne), encja zniknęła, stan
  `unavailable` lub `unknown`; nagłówek mówi dlaczego. X i wstecz działają zawsze.
- Wywołanie w toku: jego przycisk ma spinner, wszystkie inne przyciski sterujące są nieaktywne (pkt 5.1);
  błąd - komunikat w nagłówku.
- Zamykanie: X, wstecz (najpierw zamyka otwartą listę, potem panel), 2 min bez dotyku - własny licznik panelu,
  zerowany dotykiem w panelu i w liście (licznik strony pulpitu stoi, gdy okno ma fokus).

## 6. Bezpieczeństwo

- `HaDashboardClient.callService` dostaje przeciążenie z `service_data` (dziś wysyła tylko domenę, usługę i
  cel). Wywołania termostatu tworzy wyłącznie fabryka w `ActionPolicy` (np. `ClimateCall.of(item, service,
  value)`), która zna dokładnie sześć par usługa/klucz z pkt 5 i odrzuca każdą inną; cel to zawsze
  `entity_id` z kafelka.
- Tuż przed każdym wysłaniem (także opóźnionym i z listy) sprawdzane od nowa: połączenie z HA, istnienie i znany
  stan encji, bit funkcji, przynależność wartości do bieżącej listy z HA, temperatura skończona i w
  `min_temp`–`max_temp`. Niespełnione - nic nie wychodzi, komunikat w nagłówku.

## 7. Testy (do implementacji)

- JVM: parsowanie typu i odrzucenia (domena, `tap_action`, wersja 5); body kafelka dla stanów z tabeli pkt 1
  (w tym `current_temperature: null`, `off`, `fan_only`, `unavailable`, zakres); ikona z `hvac_action` i
  pierwszeństwo `ownIcon`; arytmetyka nastawy (krok z jednostką, granice, dociąganie do granicy, zaokrąglenie);
  warunki edycji (bit bez temperatury, zakres, złe granice); widoczność list z `supported_features`; etykiety z
  fallbackiem; fabryka `ClimateCall` (sześć par, odrzucenie reszty, walidacja przed wysłaniem); cykl życia
  (wysłanie po 800 ms i przy zamknięciu, porzucenie przy rozłączeniu i `onPause`, jedno wywołanie naraz dla
  nastawy/trybu/presetu z opóźnioną odpowiedzią, tryb i listy nieaktywne podczas 800 ms szkicu, odblokowanie po zaobserwowaniu wartości albo po 10 s, brak
  ponowień); granice ułamkowe (`max_temp` 30,5, krok 1).
- Geometria: pudełka w AREA, bez nakładania, cele dotyku >= 64 px (także zamknięcie listy), lista 5x64.
- Panel HA: walidator i formularz typu `climate` (node --test).
- Sprzęt: salon (heat/off, preset away) i gree (pełny zestaw) na zegarze; seria `+` dochodzi do HA jednym
  wywołaniem; wyłączenie HA w trakcie edycji blokuje przyciski.
