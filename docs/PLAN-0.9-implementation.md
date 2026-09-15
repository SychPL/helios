# Plan implementacji 0.9 - rolety sypialni, światło kontekstowe, pogoda na jutro

Status: ZREALIZOWANY w kodzie 2026-09-15 (`artifacts/native-0.8.8-results.md`); wdrożenie do HA i odbiór na zegarze czekają na użytkownika (SPEC pkt 8). Realizuje [SPEC 0.9](SPEC-0.9-bedroom-dashboard.md) na bazie Helios 0.8.7 (`63ae9b4`, versionCode 16) i ha-helios 0.7.6. Wydanie: Helios 0.8.8 (versionCode 17), pakiet HA `ha/packages/helios_bedroom.yaml`, publikator `tools/deploy_bedroom_dashboard.py`.

Zasady jak w [planie 0.8](PLAN-0.8-implementation.md): testy JVM bez mocków Androida, Java 8, bez nowych zależności, każde zadanie kończy `./gradlew testDebugUnitTest assembleDebug lintDebug`. Integracja ha-helios bez zmian (nic z 0.9 nie przechodzi kanałem `helios/*`). Żadnej publikacji do prawdziwego HA i żadnej instalacji APK bez użytkownika; kolejność wdrożenia z SPEC pkt 8.

## Stan wyjściowy (potwierdzony w kodzie i w HA)

| Miejsce | Dziś | Zmiana |
| --- | --- | --- |
| `DashboardSpec.parse` | `version` 2 albo 3, `music` wymaga ≥3, `Item` z jedną encją, `COVER_ATTRIBUTES=[current_position]`, `visible_when` walidowane w `item()` | wersja 4, typ `cover_group` (dokładnie dwie rolety), `weather.forecast_entity`+`forecast_when`, `supported_features` dla domeny cover, wspólna walidacja obiektu `{entity,state}` |
| `DashboardView.Tile.render` | `weather`: temperatura + opis; `cover`: stan + „Otwarcie NN%” | `cover_group`: dwa wiersze; `weather`: tryb Jutro z rekordu prognozy i wygaśnięciem |
| `MainActivity` | `coverPanel(item)` dla jednej encji, `call()` z kluczem `id:usługa`, `panelTitle/panelItem` odświeżane ze stanów | `coverGroupPanel(item)` z dwoma wierszami, klucz `id:encja:usługa`, Stop bez blokady, odświeżanie obu wierszy |
| `IconView` | brak strzałek góra/dół | `arrow-up`, `arrow-down` (stop już jest) |
| HA (żywe, 15.09.2026) | `cover.bedroom_main_cover_a/b` (`supported_features` 15, `closed`, pozycja 0), `light.bedroom_a_all` (2 członków), `weather.forecast_dom` (`supported_features` 3, `°C`), `sun.sun`; pomocników `helios_*` brak; pulpit `helios-clock` w schemacie 2 (manifest `ha/helios-attention.yaml`) | pakiet YAML z trzema pomocnikami, pulpit w schemacie 4 |
| Fixture `.local/ui-dashboard-fixture-v2.py` | `--v3 --hitbox --photo` | `--v4`: trzy kafelki 0.9, sterowanie stanami przez HTTP |

Ograniczenia: identyfikatory encji tylko w YAML/manifeście, nigdy w Javie; nazwy usług `cover.open_cover|stop_cover|close_cover` i `light.toggle` tylko w kodzie; aplikacja nie liczy pory dnia ani daty prognozy; parser odrzuca nowe pola w schematach 2/3; błąd dokumentu zostawia ostatni poprawny układ (istniejący mechanizm last-good w `HaDashboardClient`).

---

## Etap A - kontrakt schematu 4

### A1 Parser i subskrypcja

**Pliki:** `DashboardSpec.java`, `DashboardSpecTest.java`.

**Interfejs:**
- `VERSION_ERROR` = „Wymagana konfiguracja Helios version: 2, 3 lub 4”; `parse` przyjmuje 2/3/4; `DashboardSpec.version` bez zmian.
- `Item` dostaje pola: `final List<Cover> covers` (pusta lista poza `cover_group`), `final String forecastEntity, forecastWhenEntity, forecastWhenState` (null poza weather z prognozą). `static final class Cover {final String entity,title;}`.
- Typ `cover_group` (tylko `version>=4`, inaczej „Typ cover_group wymaga version: 4”): dozwolone `id,type,column,row,width,height,title,icon,visible_when,covers`; `covers` = tablica dokładnie 2 obiektów `{entity,title}` (klucze sprawdzane przez `keys()`), `entity` pasuje do `cover\.[a-z0-9_]+`, różne, `title` 1-40; `action="covers"`; domyślna ikona `window-shutter`; `tap_action`, `confirmation`, `entity`, `attribute` niedozwolone (komunikat „Pole niedozwolone dla typu cover_group”).
- Weather: `forecast_entity` (`sensor\.[a-z0-9_]+`) i `forecast_when` (obiekt `{entity,state}` walidowany tą samą funkcją `when(JSONObject,String where)` co `visible_when`, wydzieloną z `item()`) dozwolone tylko przy `version>=4` i tylko razem („weather: forecast_entity i forecast_when występują razem”); `icon` nadal niedozwolone.
- `entities()` dodaje `covers[*].entity`, `forecastEntity`, `forecastWhenEntity`. `attributes()`: `COVER_ATTRIBUTES=[current_position, supported_features]` dla `cover`, `garage` i każdej rolety `cover_group`; `FORECAST_ATTRIBUTES=[forecast_date, condition, temperature, templow, temperature_unit, fetched_at, valid_until]` dla `forecastEntity`.
- `MAX_ITEMS` i walidacja kolizji komórek bez zmian (podmenu nie zajmuje komórek).
- `EntityStates.merge`: atrybut o wartości JSON `null` w snapshotcie lub delcie usuwa atrybut z mapy (dziś jest ignorowany i stara wartość zostaje). Test w `HaDashboardClientTest`/nowym `EntityStatesTest`: `{"templow": 9}` → delta `{"templow": null}` → `attribute("templow") == null`.

**Testy (`DashboardSpecTest`):** nowy `exampleV4()` z fragmentu SPEC 7.3 + zegar + cztery powiadomienia (8 elementów) parsuje się; `entities()` zawiera obie rolety, światło, pomocnik światła, tryb i sensor prognozy; `attributes()` daje `current_position`+`supported_features` dla obu rolet i siedem atrybutów dla sensora; istniejące `example()` v2/v3 bez zmian. Odrzucenia: `cover_group` w v3; `forecast_entity` w v3; jedna rolety; trzy; ta sama encja dwa razy; `light.x` w `covers`; brak `title` rolety; `covers` z dodatkowym polem; `forecast_entity` bez `forecast_when` i odwrotnie; `forecast_when.state: unavailable`; `icon` na weather; `confirmation` na `cover_group`; kolizja komórek z podmenu bez znaczenia (dwa kafelki na (3,2) odrzucone jak dotąd).

---

## Etap B - renderer i panel

### B1 Kafelek Rolety

**Pliki:** `DashboardView.java`, nowa czysta klasa `CoverText.java`, `CoverTextTest.java`.

**Interfejs:**
- `CoverText.line(String title, EntityStates.Entity e)`: `title + ": " + stan`, gdzie stan: `closed`→„zamknięta”, `opening`→„otwieranie”, `closing`→„zamykanie”, `open` z `current_position`→„NN%” (liczba całkowita, bez `.0`), `open` bez pozycji→„otwarta”, brak/`unknown`/`unavailable`→„brak danych”. `CoverText.attention(Entity e)` = znany stan różny od `closed` (barwi ikonę akcentem, jak dziś `cover`).
- `Tile.render` dla `cover_group`: kafel używa stałych krótkich prefiksów pozycji na liście, „A” i „B” (jak przykład w SPEC 4.1), a pełne `covers[*].title` z YAML tylko w panelu: `value` = `line("A", covers[0])` (rozmiar z `TextFit` 24-28, 1 linia; „A: zamknięta” przy 24 mieści się w 162 jednostkach), `detail` = `line("B", covers[1])` (17); `label()` domyślnie „Rolety”; `contentDescription` = pełne tytuły z YAML + stany; offline jak inne kafle (tekst `muted`, dopisek „(offline)”).

**Testy:** `CoverTextTest` - wszystkie stany, pozycja `40.0`→„40%”, pozycja przy `closed` ignorowana, `unavailable`→„brak danych”.

### B2 Pogoda dziś / jutro

**Pliki:** nowa czysta klasa `Forecast.java`, `ForecastTest.java`, `DashboardView.java`, `WeatherLabels.java` (bez zmian funkcjonalnych; `polish()` zwraca „Brak opisu warunków” dla nieznanego kodu - sprawdzić i w razie potrzeby dodać).

**Interfejs:**
- `Forecast.parse(EntityStates.Entity e, long nowEpochMs) -> Forecast|null`: null gdy `e` null/nieznany, `state != "ready"`, brak/pusty `condition`, `temperature` nie jest skończoną liczbą, `templow` obecne, niepuste i nie liczba (brak atrybutu, `null` usunięty przez `EntityStates` albo pusty tekst = brak minimum), `temperature_unit` ∉ {`°C`,`°F`}, `forecast_date` nie `YYYY-MM-DD`, `valid_until`/`fetched_at` nie ISO-8601 albo `valid_until <= now`. Pola: `condition, temperature (double), templow (Double), unit, validUntilMs`. ISO parsowane przez `java.time.OffsetDateTime`/`Instant` (API 26+, minSdk 29 OK). Nie sprawdza, czy data to jutro (należy do HA).
- Tryb (`Forecast.mode(Entity when, String expected)`): `TOMORROW` gdy stan znany i równy, `TODAY` gdy znany i różny, `UNKNOWN` gdy null/`unknown`/`unavailable`.
- `Tile.render` weather z `forecastEntity`: `TOMORROW` → tytuł „Jutro”, `value` = „maks. 18°C” (format `%.0f` + jednostka), `detail` = `polish(condition)` + opcjonalnie „ · min. 9°C”; rekord null → `value` „—”, `detail` „brak prognozy”. `TODAY` → dotychczasowe renderowanie (w tym `temperature_entity`). `UNKNOWN` → tytuł z konfiguracji, `value` „—”, `detail` „brak danych o trybie”. Tytuł „Jutro” zastępuje `title` tylko w trybie `TOMORROW`; offline: dopisek „(offline)” jak w innych kaflach, bez zmiany trybu (ostatnie stany zamrożone przez `MainActivity`).
- Wygaśnięcie bez delty: `DashboardView` zapamiętuje ostatnie `states`/`live` z `render()`; `clock()` (wołane co sekundę przez istniejący `tick`) sprawdza dla kafli weather z prognozą, czy `validUntilMs <= now` zmienia wynik względem poprzedniego renderu (`Tile.forecastExpiresAt`), i wtedy woła `render` tylko tego kafla. Bez osobnego timera.

**Testy:** `ForecastTest` - poprawny rekord; każde pole z osobna niepoprawne → null; `valid_until` w przeszłości → null; brak `templow` → `templow==null`; przejście `templow: 9` → delta `templow: null` → rekord bez minimum (przez `EntityStates`); tryb dla `on`/`off`/`unknown`/`unavailable`/brak; format temperatur (`18.4`→„18”, `-2.6`→„-3”, `°F`).

### B3 Panel Rolety sypialni

**Pliki:** `MainActivity.java`, `IconView.java` (`arrow-up`, `arrow-down`), nowa czysta klasa `CoverPanelGeometry.java`, `CoverPanelGeometryTest.java`.

**Interfejs:**
- `CoverPanelGeometry` (jednostki 800×480): `PANEL=Box(40,82,720,368)`, `PAD=16`, `HEADER=Box(16,16,688,40)`, `ROW_A=Box(16,64,688,96)`, `ROW_B=Box(16,168,688,96)`, `BACK=Box(16,280,688,72)`; w wierszu: etykieta `Box(0,0,400,96)`, przyciski `OPEN=Box(400,12,72,72)`, `STOP=Box(504,12,72,72)`, `CLOSE=Box(608,12,72,72)`. Test: wszystko wewnątrz `PANEL`, wiersze i przyciski bez nakładania, przyciski ≥72×72, `PANEL` pod paskiem (y ≥ 52) i w ekranie, `BACK` na dole ≥72.
- `MainActivity.tap`: `case "cover_group": coverGroupPanel(item)`. Panel = `Dialog` (jak klawiatura: jednostki ekranu `s`, `FEATURE_NO_TITLE`, tło przezroczyste, `Theme.dialogColumn`), nagłówek = `title` kafelka („Rolety sypialni” z YAML), dwa wiersze `FrameLayout` z pozycjami z geometrii: etykieta `title` rolety (1 linia, wielokropek) + stan (`CoverText.line` bez prefiksu tytułu), trzy `MusicOverlay.IconButton` (`arrow-up`/`stop`/`arrow-down`, opisy „Otwórz/Zatrzymaj/Zamknij <tytuł>”), `Theme.button("Wróć")`. `setCanceledOnTouchOutside(true)`, `onCancel`→`closePanel()`; systemowe cofnięcie = cancel. Otwarcie panelu woła `dashboard.musicOverlay().closePanel()` (muzyka gra dalej) i `closePanel()` poprzedniego dialogu.
- Stan przycisków: `supported_features` bity OPEN=1, CLOSE=2, STOP=8 (`CoverFeatures.has(attr, bit)` w `CoverText`); encja nieznana/`unavailable` → wszystkie trzy wyłączone; brak bitu → tylko dany przycisk; `!live` → wszystkie wyłączone. Odświeżanie: `panelRefresh` (Runnable) wołane z `renderDashboard()` zamiast dzisiejszego `panelTitle` (istniejący `coverPanel` przepięty na ten sam mechanizm).
- Akcje: `callEntity(item, entity, "cover", service, done)` - nowa sygnatura `call()` z jawną encją; klucz oczekiwania `item.id+":"+entity+":"+service` dla `open_cover`/`close_cover` (przycisk wyłączony do odpowiedzi/timeoutu 10 s z `HaDashboardClient`); `stop_cover` nie używa `pendingActions` (zawsze wysyłany, bez potwierdzeń). Bez `confirm` w tym typie. `anyPending(id)` dalej działa po prefiksie `id:`. Istniejące `call(item,domain,service,done)` deleguje do `callEntity` z `item.entity`.
- `onUnavailable` i nowa konfiguracja zamykają panel przez istniejące `closePanel()`; reconnect go nie otwiera.
- Oczekiwanie nie blokuje kafelka: `Tile.pending(on)` wyłącza kafel (`setEnabled(false)`) tylko dla typów, których dotyk jest akcją (`light`, `garage`); dla `cover` i `cover_group` dotyk otwiera panel, więc kafel zostaje aktywny i pokazuje tylko spinner. Użytkownik może po zamknięciu panelu wejść ponownie, nacisnąć Stop albo sterować drugą roletą podczas oczekiwania na odpowiedź dla pierwszej.
- Niedostępna encja akcji: `MainActivity.tap` dla `light`, `garage`, `cover` sprawdza `states.get(entity).known()`; nieznana/`unavailable` → brak wywołania i brak Toastu o połączeniu (kafel jest widoczny, ale nieaktywny). `Tile.render` ustawia `setEnabled(live && known)` dla tych typów (dla `cover_group` panel otwiera się zawsze, niedostępność wyłącza kontrolki wiersza). Test emulatora: `light.bedroom_a_all` `unavailable` przy pomocniku `on` → kafel widoczny, dotyk → 0 `call_service`.

**Testy:** `CoverPanelGeometryTest` (jak wyżej), `CoverTextTest` rozszerzony o `CoverFeatures.has(15,1|2|8)` i `has(3,8)==false`, `has(null,...)==false`. Emulator: panel z obu wierszami i „Wróć” na 800×480, Stop podczas oczekującego zamknięcia wysyła osobne `call_service`, A nie blokuje B, dotyk poza panelem zamyka bez `call_service`.

### B4 Kafle powiadomień świecą na pomarańczowo (prośba użytkownika 2026-09-15, poza SPEC 0.9)

**Pliki:** `Theme.java`, `ThemeTest.java`, `DashboardView.java`.

**Interfejs:**
- Definicja „kafel powiadomienia”: element typu `entity` z `visible_when` (dolny rząd uwagi z manifestu; widoczny tylko, gdy jest co pokazać). Bez nowego pola YAML.
- Kolor powiadomienia jest stały: `Theme.ATTENTION = #EDBE83` (pomarańcz niezależnie od presetu - użytkownik prosił o pomarańczowy, a akcent Nocnego błękitu jest niebieski); `Theme.attentionSurface = composite(ATTENTION, .10f, surface)`, obrys `ATTENTION` o grubości 2 jednostki (`GradientDrawable.setStroke`), ikona, tytuł i wartość w `ATTENTION`; opis (`detail`) w `muted`. Offline: jak inne kafle (bez obrysu, tekst `muted`, dopisek „(offline)”), żeby nieaktualne powiadomienie nie świeciło.
- `ThemeTest`: `contrast(accent, attentionSurface) >= 4.5` i `contrast(muted, attentionSurface) >= 4.5` w obu presetach; także nad zdjęciem (92 % `attentionSurface` nad białym przyciemnionym 35 %).
- `DashboardView.Tile.theme()`: gałąź `attention = item.type.equals("entity") && item.conditional() && live` wybiera tło/obrys/kolory; `Theme.card` dostaje wariant z obrysem (`card(int fill, int stroke, float strokePx, float radiusPx)`). Tło kafla ustawia wyłącznie `theme()`; `scale()` przestaje wołać `setBackground` i na końcu woła `theme()` (dziś `scale()` nadpisuje tło zwykłą kartą po każdej zmianie tekstu i geometrii).

**Testy:** `ThemeTest` jak wyżej; emulator: fixture `--v4` z dwoma widocznymi powiadomieniami (garaż, blaszak) - zrzut z pomarańczowymi kaflami i szarymi pozostałymi; offline (fixture zamyka WS) - kafle bez obrysu.

---

## Etap C - HA, publikacja, fixture, wydanie

### C1 Pakiet pomocników HA

**Pliki:** nowy `ha/packages/helios_bedroom.yaml`, nowy test `ha/tests/test_bedroom_package.py` (pytest + jinja2 z minimalnymi stubami `now/as_local/as_datetime/timedelta/is_state/has_value/states`), `docs/ha-integration.md` (sekcja „Pakiet sypialni”).

**Kontrakt:**
- `template.binary_sensor` `Helios sypialnia swiatlo pokaz` (`unique_id: helios_sypialnia_swiatlo_pokaz`): `state` = `sun.sun == below_horizon` OR `cover_a == closed` OR `cover_b == closed`; `availability` = (którykolwiek człon prawdziwy) OR (wszystkie trzy `has_value`). Efekt: prawdziwy człon → `on` mimo niedostępności innych; wszystkie znane fałszywe → `off`; inaczej `unavailable`.
- `template.binary_sensor` `Helios pogoda jutro tryb` (`unique_id: helios_pogoda_jutro_tryb`): `{{ now().hour >= 18 }}` (przeliczane co minutę przez `now()`; strefa HA).
- `template` z `triggers` (`homeassistant: start`, `time_pattern minutes: 0`, `time at: [18:00:00, 00:00:00]`, `state weather.forecast_dom from: unavailable`), `actions: weather.get_forecasts type: daily response_variable: forecasts continue_on_error: true`, `sensor` `Helios pogoda jutro` (`unique_id: helios_pogoda_jutro`): wybór rekordu, którego `as_local(as_datetime(datetime)).date() == now().date() + timedelta(days=1)`; brak jednoznacznego rekordu → poprzedni rekord z `this.attributes` jeśli `forecast_date == jutro` i `valid_until > now`, inaczej stan `none` bez atrybutów; przy poprawnym rekordzie stan `ready` i dokładnie siedem atrybutów (`temperature_unit` z `state_attr('weather.forecast_dom','temperature_unit')`, `fetched_at = utcnow().isoformat()`, `valid_until = min(następna lokalna północ, fetched_at+6h)` w ISO UTC; przy rekordzie odziedziczonym `fetched_at`/`valid_until` bez zmian).
- Nazwy encji wynikają z nazw (`binary_sensor.helios_sypialnia_swiatlo_pokaz`, `binary_sensor.helios_pogoda_jutro_tryb`, `sensor.helios_pogoda_jutro`); publikator sprawdza ich istnienie i przerywa, gdy encja o tej nazwie ma inny `unique_id`/platformę (cudza).

**Testy (`test_bedroom_package.py`):** YAML parsuje się; szablon światła: (dzień, obie otwarte)→off, (dzień, A closed)→on, (dzień, obie 50%)→off, (noc, otwarte)→on, (A closed, sun unavailable)→on, (dzień, A unavailable, B open)→unavailable; szablon trybu 17:59→off, 18:00→on, 00:00→off; szablon prognozy: dwa rekordy w innej kolejności → jutro wybrane po dacie w strefie `Europe/Warsaw`, brak jutra → `none`, `forecasts` niezdefiniowane (błąd akcji) z ważnym `this.attributes` → `ready` z tymi samymi znacznikami, z przeterminowanym → `none`; `valid_until` = min(północ, +6 h) w dwóch przypadkach; zmiana czasu (data 2026-10-25) daje `forecast_date` 2026-10-26.

### C2 Publikator pulpitu

**Pliki:** nowy `tools/deploy_bedroom_dashboard.py`, `ha/helios-bedroom.yaml` (manifest: identyfikatory encji z SPEC pkt 2, trzy kafelki z SPEC 7.3), `artifacts/attention-dashboard-20260915.md` (dopisek, że publikator powiadomień nie może już nadpisywać schematu 4 - `deploy_attention_dashboard.py` dostaje strażnika: przerywa, gdy pulpit ma `version >= 4`).

**Kontrakt (`--check` domyślnie, `--apply` po instalacji APK 0.8.8):** token z `.local/ha.json`; kopia całego dokumentu `lovelace/config helios-clock` do `.local/bedroom-dashboard-backup-<stamp>.json`; sprawdzenie w `get_states`: trzy pomocniki istnieją i nie są `unknown`/`unavailable`; obie rolety, światło, `weather.forecast_dom` i `sun.sun` istnieją i żadne z nich nie jest `unavailable` (pomocnik światła może być `on` dzięki zamkniętej rolecie mimo braku `sun.sun`, więc źródła reguły sprawdzane są osobno); skład grupy światła: atrybut `entity_id` encji `light.bedroom_a_all` musi być dokładnie zbiorem dwóch członków z manifestu (`light.bedroom_a_light_swiatlo`, `light.bedroom_a_light_swiatlo_2`), a każdy członek musi istnieć - inaczej przerwanie („grupa światła zmieniła skład”); semantyka „przynajmniej jeden świeci”: `config_entries/get` nie zwraca opcji, więc publikator otwiera options flow wpisu grupy (`POST /api/config/config_entries/options/flow {handler: entry_id}`), czyta zapisane wartości pól `all` i `entities` z `description.suggested_value` w `data_schema` (schemat grupy trzyma zapisane opcje w `suggested_value`, a `default` jest zawsze `false`; brak `suggested_value` = wartość domyślna), po czym usuwa flow (`DELETE /api/config/config_entries/options/flow/<flow_id>`) bez zapisu; `all` musi być `false`, inaczej przerwanie („grupa wymaga trybu: dowolny członek”). Wpis grupy znajdowany po `entry_id` z rejestru encji (`config/entity_registry/get` dla `light.bedroom_a_all` → `config_entry_id`); grupa bez wpisu (YAML) → przerwanie; przerwanie, gdy zegar zgłasza `app_version < 0.8.8`: encja wersji szukana w `config/entity_registry/list` po `platform == "helios"` i `unique_id` kończącym się `_app_version` (nazwa encji to `sensor.*_wersja_aplikacji`, więc nie po `entity_id`), potem jej stan z `get_states`; brak encji lub `unavailable` → przerwanie; transformacja: `version: 4`, `weather` z polami prognozy, usunięcie elementów zajmujących komórki (3,2) i (4,2) (dziś `Prognoza` 2×1), dodanie `cover_group` i `light`; zegar, cztery powiadomienia, `views` i obce pola bez zmian; `DashboardSpec`-równoważna walidacja w Pythonie (`validate_tiles` rozszerzone o nowe typy); ponowny odczyt dokumentu przed zapisem (przerwanie przy różnicy), zapis, readback równy oczekiwanemu, `.local/bedroom-dashboard-live.json`; `--rollback <backup>` przywraca wyłącznie sekcję `helios` z kopii: odczytuje bieżący dokument, wymaga, by jego sekcja `helios` była równa opublikowanej (`bedroom-dashboard-live.json`), inaczej przerywa („sekcja helios zmieniona po publikacji, cofnij ręcznie”), a wszystkie pozostałe pola (`views` i obce) zachowuje z bieżącego dokumentu; zapis dopiero po ponownym odczycie równym sprawdzonemu. Publikator nie wysyła żadnej usługi do rolet/światła.

### C3 Fixture i emulator

`.local/ui-dashboard-fixture-v2.py --v4`: układ z SPEC 7.3 + zegar + cztery powiadomienia; stany: rolety (`closed`/0 i `open`/40, `supported_features` 15), światło, pomocnik światła `on`, tryb `off`, sensor prognozy `ready` z siedmioma atrybutami (`valid_until` = teraz+6 h); sterowanie HTTP 8099: `/mode/on|off|unavailable`, `/forecast/ready|none|expired|badunit`, `/light-show/on|off|unavailable`, `/cover/a|b/<state>/<position>`, `/features/a/<bits>`; każde wysyła deltę `subscribe_entities`. Zrzuty: dzień, wieczór z prognozą, wieczór bez prognozy, brak trybu, podmenu rolet (obie dostępne; A bez STOP; B `unavailable`), światło ukryte/widoczne, uchwyt muzyki nad światłem (dotyk uchwytu → 0 `call_service`, odsłonięty kafel → 1 `light.toggle`), Stop podczas oczekującego close (fixture opóźnia odpowiedź 3 s) → dwa `call_service`.

### C4 Wydanie 0.8.8

`versionCode 17`, `versionName 0.8.8`; testy JVM, lint, build; `artifacts/native-0.8.8-results.md` (JVM, emulator, co niesprawdzone); plan oznaczony. Kolejność u użytkownika (SPEC pkt 8): pakiet YAML do `config/packages/` + `packages:` w `configuration.yaml` + restart HA → sprawdzenie trzech encji → instalacja APK 0.8.8 na zegarze → `deploy_bedroom_dashboard.py --check` → `--apply` → zrzuty i test fizycznych akcji z użytkownikiem.

---

## Threat model i granice hardeningu

Zaufane: HA w LAN (token zegara), YAML pakietu i manifest publikatora pisane przez nas. Niezaufane strukturalnie: dokument Lovelace (parser odrzuca wszystko poza schematem), stany/atrybuty encji (walidacja typów/zakresów/ISO w `Forecast`, `CoverText`), odpowiedzi usług (rozliczane po `id`). Aplikacja nigdy nie wysyła usług innych niż `cover.open_cover|stop_cover|close_cover`, `light.toggle` (i dotychczasowe), nigdy z nazw z YAML. Poza zakresem: MITM w LAN bez TLS (decyzja z 0.7), złośliwy administrator HA, cudze pomocniki o tych samych nazwach (publikator przerywa zamiast przejmować), automatyczne poruszanie roletami.

## Rejestr rebutali

Pusty przed pierwszą rundą.

## Changelog rund

- Runda 5 (Codex, REJECT, 1×P2): C2 - preflight sprawdza dostępność każdego źródła reguł (`sun.sun`, rolety, światło, pogoda), nie tylko pomocników.
- Runda 4 (Codex, REJECT, 1×P2): C2 - rollback przywraca tylko sekcję `helios`, po porównaniu z opublikowaną, reszta dokumentu z bieżącego stanu.
- Runda 3 (Codex, REJECT, 2×P2): C2 - `all` z `description.suggested_value` options flow; wersja APK przez rejestr encji (`platform: helios`, `unique_id` `*_app_version`).
- Runda 2 (Codex, REJECT, 2×P2, 1×P3): C2 - `all` grupy czytane z options flow (otwarcie + usunięcie flow, bez zapisu); B1 - kafel Rolety z prefiksami „A”/„B”, pełne tytuły tylko w panelu; B4 - tło kafla tylko w `theme()`, `scale()` woła `theme()`.
- Runda 1 (Codex, REJECT, 4×P2): B3 - oczekiwanie nie wyłącza kafli `cover`/`cover_group` (tylko spinner), niedostępne światło/brama/roleta nie wysyłają usługi i są nieaktywne; A1/B2 - `EntityStates` usuwa atrybut przy `null`, `templow` null/pusty = brak minimum, test przejścia; C2 - preflight sprawdza dokładny skład grupy światła i `all: false`.
