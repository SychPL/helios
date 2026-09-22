# Plan wdrożenia SPEC 0.15 - karty, intencje, strony, edytor w HA

Plan ma cztery fazy. Każda jest osobnym commitem i osobną recenzją, bo każda zostawia działający zegar.
Kolejność wynika z ryzyka: najpierw refaktor bez zmiany zachowania, potem nowy schemat, potem edytor,
a wyspecjalizowane karty na końcu.

Stan na 2026-09-22: **P1 i P2 zrobione i wydane jako Helios 0.12.0**, P3 zaimplementowane w `ha-helios`
0.11.0, ale jeszcze nie zainstalowane w żywym HA, P4 otwarte.

## Ograniczenia globalne

- Java 8, ręczne `android.view`, `org.json`, bez AndroidX - tak jak reszta aplikacji.
- Komunikaty błędów po polsku, co do znaku takie same jak dotąd; walidacja odrzuca cały dokument.
- Panel w HA to jeden moduł ES bez kroku budowania i bez zależności npm.
- Przed każdym commitem: `./gradlew testDebugUnitTest assembleDebug lintDebug` w `dash`,
  `pytest tests` i `node --test tests/panel` w `ha-helios`.

## P1 - jeden opis typu karty zamiast switchy (repo `dash`) - zrobione

Cel: zachowanie i komunikaty identyczne, a typ karty opisany w jednym miejscu. `DashboardSpecTest`
przechodzi bez edycji - to jest dowód, że refaktor niczego nie przestawił.

Nowe pliki w `app/src/main/java/pl/mateusz/helios/`:

- `CardDefinition.java` - czyste dane per typ: pola i ich minimalna wersja, domena encji, akcja,
  `tapConfigurable`, `confirmByDefault`, `singleton`, `notifies`, domyślna ikona i tytuł, atrybuty,
  enumy `Layout`, `Gate`, `Feed`. Tabela wyprowadzona linia po linii z poprzedniego `DashboardSpec`.
- `CardBodies.java` - `CardContent` (wartość, szczegół, etykieta, ikona, akcent, wygaśnięcie) i ciała
  `Clock`, `Weather`, `Entity`, `Light`, `Cover`, `Music`, `CoverGroup`.
- `ActionPolicy.java` - jedyne miejsce, które zamienia dotknięcie w wywołanie: panel albo usługa.

Zmiany: `DashboardSpec` bierze dozwolone pola z `CardDefinition`, `DashboardView.Tile` zostaje ramą i woła
`body.render()`, `MainActivity.tap()` idzie przez `ActionPolicy`.

Testy: `CardRegistryTest` (klucze trzech rejestrów zgodne, każda akcja rozwiązuje się w panel **albo**
wywołanie), `ActionPolicyTest`, `CardBodiesTest` - ten ostatni napisany z obecnego `render()` **przed**
przeniesieniem kodu.

Akceptacja: bramka zielona bez edycji istniejących testów; w `DashboardView` i `MainActivity` nie ma już
`case "light"` ani `type.equals(`.

## P2 - schemat 6 (repo `dash`) - zrobione, Helios 0.12.0

Reguły dokumentu opisuje [SPEC 0.15](SPEC-0.15-dashboard-cards.md) punkty 3-7. Po stronie kodu:

- `DashboardSpec` - wersje do 6, model `Page`, alias `items` na stronę pierwszą (dzięki temu widok,
  widoczność i stare testy zostają bez zmian), `item(id)` szuka po wszystkich stronach, `entities()`
  i `attributes()` są sumą stron. Cache trzyma ten sam klucz, bo dokument 6 przechodzi przez ten sam parser.
- `CardDefinition` - typ `tile` z `minVersion 6`.
- `ActionPolicy` - enum intencji, `allowed`, `defaultIntent`, `forcedConfirm`, `service`, `question`.
- `CardBodies.TileBody` - stan po polsku, liczba z jednostką, `friendly_name` jako etykieta.
- `MdiIcons.java` - mapa nazwa → codepoint, instalowana w `HeliosService.onCreate` i `MainActivity.onCreate`
  **przed** parsowaniem zapisanego dokumentu; bez instalacji `mdi:` jest odrzucane.
- `IconView` - rysuje glif z fontu, gdy nazwa zaczyna się od `mdi:`, inaczej stary switch.
- `DetailsDialog.java` - okno ze stanem encji i przyciskami dozwolonych intencji.
- `tools/prepare_mdi_assets.py` - pobiera font i metadane MDI 7.4.47 (ta sama wersja co we frontendzie HA),
  generuje `materialdesignicons-webfont.ttf`, `mdi-codepoints.txt` i `mdi-provenance.json`.
- `tools/publish_ha_dashboard.py` przyjmuje wersje do 6; `ha/helios-clock-v6.yaml` jest przykładem;
  `docs/ha-dashboard.md` dostaje sekcję o schemacie 6.

Testy: `DashboardSpecV6Test` (strony i kafelki oraz odrzucenia: `items` w 6, `pages` w 5, powtórzony `id`,
13 elementów, 9 stron, `sensor` z `toggle`, zamek bez potwierdzenia, `open` na `light`, `mdi:nope`, naga nazwa
ikony, `none` bez interakcji), `ActionPolicyTest` (każda para domena-intencja), `MdiIconsTest` (asset czytany
z `src/main/assets`, sześć starych nazw, brak duplikatów), `CardBodiesTest`, `CardRegistryTest`.

## P3 - panel edytora (repo `ha-helios`) - zaimplementowane, niezainstalowane

- `panel.py` rejestruje panel przez `frontend.async_register_built_in_panel` z `require_admin`, a statykę
  przez `hass.http.async_register_static_paths`. `frontend` idzie do `after_dependencies`, nie `dependencies` -
  w testowym venv nie ma `hass_frontend` i cała suita by padła.
- `panel/helios-schema.js` - czysty moduł bez DOM: stały opis schematu 6, walidator z komunikatami takimi jak
  w Javie, `slug()`, wczytanie dokumentu 2-5 jako jednej strony, zapis zawsze jako 6.
- `panel/helios-panel.js` - płótno 4x3, zakładki stron, formularz `ha-form`, „Zamień na tile" dla starych typów.
- Zapis: odczyt świeżej konfiguracji, porównanie z bazą, odmowa przy rozjeździe, podmiana wyłącznie klucza
  `helios` (reszta dokumentu i `views` nietknięte), zapis, odczyt weryfikacyjny.
- Gotcha: `ha-form` to leniwy chunk i na świeżym panelu bywa niezdefiniowany. `ensureHaForm()` ładuje go przez
  `loadCardHelpers()` z limitem czasu, a gdy się nie uda, panel schodzi na zwykłe pola formularza.
- Testy: `tests/test_panel.py` i `tests/panel/schema.test.mjs` (`node --test`, bez npm).

Zostało: instalacja w żywym HA 2026.8.3 i potwierdzenie, że `loadCardHelpers()` naprawdę dociąga `ha-form`.
Tego nie da się sprawdzić offline.

## P4 - karty wyspecjalizowane - otwarte

Każda osobno, każda z własnym formularzem w panelu: `gauge` i `glance`, potem `sparkline` (historia przez
`history/history_during_period`, okna 1h/6h/24h, downsampling), potem `climate` (nastawa jako typowana akcja
z ograniczonym zakresem, minimum 2x2). Po nich przewijanie stron, do którego model jest już gotowy z P2,
a dalej przeciąganie i zmiana rozmiaru w panelu.

## Weryfikacja końcowa

1. `dash`: bramka zielona, instalacja na zegarze, smoke - zimny start z zapisanego dokumentu 6, ikony MDI,
   `tile` z `toggle`, zamkiem i sceną, okno szczegółów, panel rolet, muzyka, wygaszacz przy otwartym oknie.
2. `ha-helios`: `pytest tests`, `node --test tests/panel`, hassfest, instalacja przez HACS, restart HA.
3. E2E: przebudować obecny `helios-clock` (8 elementów, 4 warunkowe w dolnym rzędzie) na dokument 6,
   zapisać i zobaczyć na zegarze identyczny układ; ukryty kafelek nadal trzyma swoje pole; edycja surowym
   edytorem przy otwartym panelu kończy się odmową zapisu, a nie cichym nadpisaniem.

Wyniki: [artifacts/native-0.12.0-results.md](../artifacts/native-0.12.0-results.md).

## Kolejność i stan pośredni

| po fazie | co działa |
| --- | --- |
| P1 | to samo co wcześniej, ale typ karty opisany w jednym miejscu |
| P2 | dokument 6: strony, `tile`, intencje, ikony MDI; edycja nadal tylko w YAML-u |
| P3 | układ wyklikiwany w HA, bez dotykania YAML-a |
| P4 | karty pokazujące wartość inaczej niż tekstem i przewijanie stron |
