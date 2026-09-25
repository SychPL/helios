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

Wygląd uzgodniony z Codexem (2026-09-24, dwie rundy rozmowy o makietach). Zasada: **kolor akcentu znaczy wyłącznie
"urządzenie teraz grzeje albo chłodzi"** - nigdy "tryb wybrany" ani "opcja zaznaczona".

- Nagłówek jak na innych kafelkach (17 px): ikona + tytuł (tytuł z konfiguracji -> `friendly_name` -> id encji).
  Ikona, chyba że podano `icon`: niedostępny -> `mdi:thermostat`; `off` -> `mdi:power`; `hvac_action`
  `heating`/`preheating` -> `mdi:fire`, `cooling` -> `mdi:snowflake`, `drying` -> `mdi:water-percent`, `fan` ->
  `mdi:fan`, `defrosting` -> `mdi:snowflake-melt`; reszta (włączony, bezczynny, brak akcji) -> `mdi:thermostat`.
  Akcent tylko przy `heating`/`preheating`/`cooling`.
- Wartość (duża, zawsze temperatura zmierzona): `current_temperature`, jedno miejsce po przecinku, sam znak stopnia
  (`23,0°`); `—` bez pomiaru. Nastawa nigdy nie zajmuje miejsca pomiaru.
- Linia pod spodem, w tej kolejności: niedostępny -> `Brak połączenia` (i wartość `—`, nigdy stara liczba);
  `off` -> `Wyłączony`; nastawa -> `Zadana 20,5°`; zakres -> `Zadana 20–24°`; nic z tego -> nazwa trybu.
- Opis dla czytnika: `Salon: w pokoju 23,0 stopnia, zadana 20,5, grzeje` (słowo akcji tylko, gdy HA je podaje).

## 5. Panel pełnoekranowy (`docs/mockups/climate-panel-salon.png`, `climate-panel-gree.png`)

Okno dialogowe pod paskiem HELIOS (pasek zostaje widoczny). Geometria w `ClimatePanelGeometry`, współrzędne
bezwzględne w jednostkach 800x480, skalowane raz; cele dotyku co najmniej 64 px.

| Element | x | y | w | h |
|---|---:|---:|---:|---:|
| Ikona aktywności + tytuł (sam tytuł, bez powtarzania trybu i profilu) | 16 | 52 | 696 | 64 |
| Zamknij | 728 | 52 | 64 | 64 |
| Karta `W pokoju` | 8 | 124 | 373 | 156 |
| Karta `Zadana` | 389 | 124 | 403 | 156 |
| Etykiety kart (18 px) | 28 / 409 | 136 | - | 20 |
| Temperatura w pokoju (76 px) | 24 | 160 | 341 | 76 |
| Aktywność słowem (20 px; akcent tylko przy grzaniu/chłodzeniu) | 28 | 248 | 337 | 24 |
| `−` | 405 | 160 | 80 | 80 |
| Nastawa (64 px) | 493 | 160 | 195 | 80 |
| `+` | 696 | 160 | 80 | 80 |
| Stan nastawy (18 px, neutralny) | 405 | 248 | 371 | 24 |
| Etykieta `Tryb` (16 px) | 16 | 288 | 776 | 16 |
| Przyciski trybów, odstęp 8 | 8 | 312 | 784 | 64 |
| Listy, odstęp 8 | 8 | 384 | 784 | 88 |

- Zaznaczenie (bieżący tryb, bieżąca pozycja listy) jest **neutralne**: jaśniejsza powierzchnia `#3A4352` i ramka
  2 px w kolorze tekstu przy 60 % krycia, rysowana do środka (zaznaczenie niczego nie przesuwa).
- Aktywność słowem: `Grzeje`, `Nagrzewa`, `Chłodzi`, `Osusza`, `Wentyluje`, `Odszrania`, `Bezczynny`,
  `Wyłączony`; niedostępny - `Brak połączenia`. Bez `hvac_action` linii nie ma.

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
- Cykl życia, najprostszy: stuknięcia zmieniają lokalny szkic od razu. Stan nastawy pod wartością, neutralnie
  (akcent jest zarezerwowany dla pracy urządzenia): `Zmieniasz…` (szkic), `Ustawianie…` (wysłane, HA jeszcze
  nie pokazał), `Nie udało się ustawić` (błąd usługi), `Brak potwierdzenia` (10 s bez zmiany w HA). 800 ms po
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

### 5.3 Listy (Profil, Siła nawiewu, Kierunek)

- Przycisk tylko przy odpowiednim bicie i niepustej liście: `Profil` (16, `preset_modes`), `Siła nawiewu`
  (8, `fan_modes`), `Kierunek pionowy` (32, `swing_modes`), `Kierunek poziomy` (512, `swing_horizontal_modes`).
  Szerokość dzielona równo (4 x 190 px, jeden na całą szerokość). Brak wszystkich = wiersz znika. Przycisk:
  etykieta 16 px, wartość 21 px, strzałka `mdi:chevron-down`.
- Lista wyboru (`climate-picker-gree.png`): okno 448 px szerokie (x 176-624), tytuł = etykieta przycisku,
  zamknięcie 64x64, wiersze 64 px co 72 px; widać 4 pełne i połowę piątego, gdy jest więcej opcji (sygnał
  przewijania), pasek przewijania tylko wtedy. Bieżąca pozycja: zaznaczenie neutralne + `mdi:check` po prawej.
  Przewijanie nigdy nie wybiera; stuknięcie poza listą i wstecz zamykają tylko listę. Wybór = jedno wywołanie
  (`set_preset_mode`/`preset_mode`, `set_fan_mode`/`fan_mode`, `set_swing_mode`/`swing_mode`,
  `set_swing_horizontal_mode`/`swing_horizontal_mode`) i zamknięcie listy.
- Słowa: profil `none` -> `Standardowy` (tylko jawne `none`), `away` Poza domem, `eco` Eco, `boost` Boost,
  `sleep` Sen, `comfort` Komfort, `home` Dom, `activity` Aktywność; siła nawiewu `auto` Auto, `low` Niska,
  `medium` Średnia, `high` Wysoka, `off` Wyłączona; kierunek: tłumaczenie po słowach wartości HA, tylko gdy
  **każde** słowo jest znane (`fixed` Stały, `swing` Ruch, `full` pełny, `upper` góra, `lower` dół,
  `middle`/`center` środek, `left` lewo, `right` prawo, `default` Domyślny, `off` Wyłączony, `on` Włączony,
  `both` Oba, `vertical` Pionowy, `horizontal` Poziomy): `fixed_upper_middle` -> `Stały: góra-środek`,
  `full_swing` -> `Ruch: pełny`. Inaczej cała wartość z HA bez zmian poza `_` -> spacja i wielką literą.

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
