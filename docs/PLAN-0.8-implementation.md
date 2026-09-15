# Plan implementacji Helios 0.8 - hotfix muzyki, redesign, tła z HA

Status: plan do recenzji, 2026-09-15. Realizuje [SPEC 0.8a](SPEC-0.8a-renderer-music.md) (hotfix + redesign) i [SPEC 0.8b](SPEC-0.8b-backgrounds.md) (tła per urządzenie) na bazie Helios 0.8.4 (`48eecff`, versionCode 13) i ha-helios 0.7.4. Mapa etapów: [SPEC 0.8](SPEC-0.8-dashboard-appearance.md).

Zasady jak w [planie 0.6/0.7](PLAN-0.6-0.7-implementation.md): każde zadanie kończy `./gradlew testDebugUnitTest assembleDebug lintDebug` (JAVA_HOME = JBR Android Studio), testy JVM bez mocków Androida (fałszywe serwery WebSocket, czyste klasy), Java 8, bez nowych zależności, bez AndroidX (`android.useAndroidX=false`). Kod integracji HA żyje w `I:\Projekty\lenovo_clock\ha-helios` (GitHub `SychPL/ha-helios`), nie w `dash/ha`. Wydania: 0.8.5 (hotfix), 0.8.6 (redesign), 0.8.7 + ha-helios 0.7.5 (tła). Każde wydanie: osobny commit, APK przez most `tools/native_bridge.py`, instalacja bez resetu parowania, poza oknem odtwarzania.

## Stan wyjściowy (potwierdzony w kodzie)

| Plik | Dziś | Zmiana |
| --- | --- | --- |
| `SendspinClient.java` | `Metadata` budowana z każdego `server/state` (pole pominięte = `null`); `refreshState()` daje `NONE`, gdy `!streamOpen` i stan != `paused`; okładka tylko przez `artwork_url` | scalanie delt, stan sesji niezależny od bufora |
| `HeliosService.java` | `fetchArtwork(url)`: `artworkUrlInFlight`, brak unieważnienia przy `null`/stopie; `MusicSnapshot.artwork` = `byte[]` | generacja pobrań, dekodowanie raz do `Bitmap` poza UI |
| `MusicOverlay.java` | `BitmapFactory.decodeByteArray` w każdym `refresh()`, półprzezroczyste tło `0xF2242C25`, kontrolki tekstowe `◀◀ ❚❚ ▶▶ ■`, `CheckBox` mute, dolny przycisk Schowaj, uchwyt pod paskiem | geometria z 4.1 spec, ikony wektorowe, nieprzezroczystość, chevron, uchwyt y=204 |
| `DashboardView.java` | paleta zielona (`INK 0xFFF1EFE6, MUTED 0xFF9EA59B, CARD 0xFF262D28, tło 0xFF1B201D`), godzina 142/96/44 wg wysokości, data 15, promień 12 | motyw z `Theme`, pomiar tekstu, promień 18, warstwa tła (0.8b) |
| `IconView.java` | ikony: information, weather-rainy, lightbulb, window-shutter, garage-open, music | + previous, play, pause, next, stop, chevron-right, speaker, speaker-off, note |
| `MainActivity.java`, `MusicLibraryDialog.java`, `NavigationMenu.java` | standardowe `Button`/`AlertDialog` | wspólne style z `Theme` |
| `HeliosDeviceClient.java` | zdarzenia `connected`, `device`, `command`, `replaced`, `removed`; nieznane ignorowane | + `appearance` (0.8b) |
| ha-helios `websocket.py`, `coordinator.py` | `connected` z `device_id/area_id/name`, `device` przy zmianie rejestru | + snapshot `appearance` po `connected` i po zapisie opcji (0.8b) |

Globalne ograniczenia: żadnych zmian schematu Lovelace (2/3 bez zmian), allowlisty komend bez zmian, żadnych restartów muzyki/głosu/HA przy zmianie wyglądu, okładki tylko z hosta MA bez tokena HA, tła tylko z origin HA z tokenem w nagłówku i bez przekierowań, brak sekretów w logach.

---

## Etap H - hotfix 0.8.5 (SPEC 0.8a pkt 2, 4.3, 5) - ZROBIONE (`artifacts/native-0.8.5-results.md`)

### H1 Scalanie delt metadanych

**Pliki:** `SendspinClient.java`, `SendspinClientTest.java`.

**Interfejs:**
- Nowa wewnętrzna klasa `MetadataState` z polami `title, artist, album, artworkUrl` (String|null) i `progressMs, durationMs` (long, -1 = brak). `void apply(JSONObject metadata)`: dla każdego pola tekstowego: klucz nieobecny → bez zmian; `JSONObject.NULL` → `null`; wartość → nadpisanie. `progress`: nieobecny → bez zmian; `null` → -1/-1; obiekt → oba pola z obiektu (bez scalania zagnieżdżonego). `void reset()` → wszystko puste.
- `SendspinClient.Metadata` pozostaje niemutowalnym snapshotem tworzonym z `MetadataState` po każdym `server/state` z sekcją `metadata`; `Listener.onMetadata(Metadata)` bez zmian sygnatury.
- `reset()` wywoływany przy `server/hello` (nowa sesja) i w `endSession()`.

**Testy (fałszywy serwer jak dziś):**
- pełny snapshot (`title`, `artist`, `album`, `artwork_url`, `progress`) → delta tylko z `progress` → snapshot z tymi samymi `title/artwork_url`;
- delta `{"artwork_url": null}` → `artworkUrl == null`, tytuł zachowany;
- dwa utwory: pierwszy z `artwork_url`, drugi tylko z `title` → okładka pierwszego zachowana;
- `progress: null` → -1/-1, potem pełny obiekt → nowe wartości;
- rozłączenie i ponowne `server/hello` → pierwszy `onMetadata` bez pól z poprzedniej sesji.

### H2 Sesja UI niezależna od bufora

**Pliki:** `SendspinClient.java`, `SendspinClientTest.java`, `MusicSessionTest.java`.

**Interfejs:**
- Nowe pole `sessionActive`, zmieniane wyłącznie na wątku WebSocket (ten sam wątek dostaje `stream/start`, ramki binarne, `group/update`, `onClose`): ustawiane na `true` przy pierwszej ramce audio (`0x04`) odebranej po `stream/start` (faktyczne lokalne audio dotarło do zegara; wątek sinka nie dotyka tego pola, więc spóźniony zapis z poprzedniej sesji nie może jej wskrzesić). Ustawiane na `false` wyłącznie przez: `group/update` z `playback_state` ∈ {`stopped`, `idle`}, zakończenie sesji WS (`endSession()`, także z `onClose`), `stop()`.
- `stream/end` i drain nie zmieniają `sessionActive`; `closeStream()` zamyka tylko wyjście audio i nie woła `refreshState()`. `refreshState()` jest wołane wyłącznie z wątku WS (`group/update`, pierwsza ramka audio, `endSession()` z `onClose`/`server/hello`, `stop()`), usunięte z `playback()` i z `closeStream()`; jest `synchronized` i czyta `sessionActive`/`playbackState` raz do zmiennych lokalnych, więc jest jeden nadawca `onState` i żadna przeplatanka odczytów nie da `NONE` między `PLAYING` a `PAUSED`. `lastWriteMicros`/`PLAYING_WINDOW_MICROS` przestają wpływać na stan (zostają tylko w diagnostyce).
- `refreshState()`:
  - `!sessionActive` → `NONE`;
  - `playbackState == "paused"` → `PAUSED`;
  - `playbackState == "playing"` → `PLAYING` (bez warunku ostatniego zapisu; przerwa między utworami nie zmienia stanu);
  - inne stany (`stopped`, `idle`) → sesja kończona jak wyżej → `NONE`.
- `group/update paused` przed pierwszym zapisem (bez sesji) → `NONE` (spec: sam `paused` nie tworzy sesji).

**Testy:**
- `stream/start` + audio + `playing` → `PLAYING`; `stream/end` → nadal `PLAYING` (bez `NONE` w międzyczasie: listener zapisuje każdy `onState`, test sprawdza, że sekwencja stanów nie zawiera `NONE`); potem `paused` → `PAUSED`;
- odwrotna kolejność: `paused` → `stream/end` → sekwencja `[PLAYING, PAUSED]`;
- `paused` bez wcześniejszego audio → brak `onState` innego niż `NONE`;
- `stopped` → `NONE`; rozłączenie → `NONE`; po reconnect `stream/start` + audio → nowa sesja `PLAYING`;
- `stream/end` + `stream/start` (następny utwór) → stan cały czas `PLAYING`.
- `MusicSessionTest` bez zmian funkcjonalnych (mapowanie `State→Ui` zostaje).

### H3 Pobrania okładki związane z sesją

**Pliki:** `HeliosService.java`, nowa czysta klasa `ArtworkLoader.java`, `ArtworkLoaderTest.java`.

**Interfejs:**
- `ArtworkLoader(Fetcher fetcher, Consumer<Result> onResult)` - czysta logika: `int request(String url)`: jeśli `url.equals(currentUrl)` (w locie albo już dostarczony) → bez akcji (delty postępu z tym samym `artworkUrl` nie pobierają ponownie); inaczej zwiększa `generation`, zapamiętuje `currentUrl` i `(generation, url)`, natychmiast publikuje `Result(null)` (okładka poprzedniego utworu znika razem ze zmianą URL, panel pokazuje płytkę z nutką do czasu pobrania) i uruchamia `fetcher.fetch(url, generation)`; `void clear()` zwiększa `generation`, zeruje `currentUrl` i publikuje `Result(null)`; `void deliver(int generation, byte[] bytes)` publikuje tylko, gdy `generation == current`; `bytes == null` (błąd HTTP/dekodowania) zapamiętuje `failedUrl` i czas, a `request()` tego samego URL ponawia pobranie dopiero po `RETRY_AFTER_MS = 30_000` (ticki postępu nie młócą serwera, ale okładka wraca po chwilowym błędzie). Ten sam URL w nowej sesji ma nową generację, więc spóźniony wynik poprzedniej sesji jest odrzucany.
- `HeliosService`: `Fetcher` wykonuje HTTP na `network` (host MA, limit 1 MiB, bez przekierowań, timeout 5/8 s jak dziś); `clear()` przy `artwork_url == null`, `NONE` sesji, `stopMusic()`, reconnect (`onConnection(false)`).
- Dekodowanie do `Bitmap` raz, na wątku `network`, z `inJustDecodeBounds` i `inSampleSize` tak, by dłuższy bok ≤ 320 px; wynik `Bitmap` w `MusicSnapshot.artwork` (typ `android.graphics.Bitmap`); `MusicOverlay` tylko `setImageBitmap` przy zmianie referencji (bez dekodowania w `refresh()`).

**Testy (`ArtworkLoaderTest`, JVM):** ten sam URL trzy razy pod rząd → jedno `fetch`; A dostarczone, `request(B)` → `onResult(null)` przed dostarczeniem B; po `deliver(gen, null)` ten sam URL przed 30 s → brak `fetch`, po 30 s (wstrzykiwany zegar) → `fetch`; spóźniona odpowiedź po `clear()` odrzucona; dwa `request` pod rząd → tylko drugi dostarczony; ten sam URL po `clear()` → dostarczony (nowa generacja); `deliver` z nieznaną generacją ignorowany.

### H4 Wydanie 0.8.5

`versionCode 14`, `versionName 0.8.5`; fixture `.local/ui-music-fixture.py` rozszerzona o sekwencje z H1/H2 (delta postępu, `stream/end` przed `paused`); zrzuty emulatora: panel z tytułem po delcie postępu, `Pauza` bez zniknięcia uchwytu. Raport `artifacts/native-0.8.5-results.md`. Instalacja na zegarze po uzgodnieniu okna (poza graniem).

---

## Etap R - redesign 0.8.6 (SPEC 0.8a pkt 3-4) - ZROBIONE (`artifacts/native-0.8.6-results.md`)

### R1 Motyw i wspólne style

**Pliki:** nowy `Theme.java`, zmiany `DashboardView.java`, `NavigationMenu.java`, `MainActivity.java`, `MusicLibraryDialog.java`, `MusicOverlay.java`.

**Interfejs:**
- `Theme` - stałe dwóch presetów (`WARM_GRAPHITE`, `NIGHT_BLUE`): `background, surface, text, muted, accent, radius=18`; `static Theme current()` (w 0.8a zawsze `WARM_GRAPHITE`; w 0.8b ustawiane przez serwis).
- `Theme.button(Context, String label, boolean primary) -> Button` i `Theme.card(View, float radiusPx)` (GradientDrawable) - jedno miejsce dla zaokrąglonych kontrolek dialogów, klawiatury parowania, dialogu urządzenia, biblioteki i panelu rolety. Bez zmiany logiki dialogów.
- `DashboardView`: stan offline kafla (dziś `setAlpha(.55f)` na całym kaflu, także na tekście - psuje kontrast) zastąpiony: alfa zawsze 1, wartość i opis w `muted`, a w nagłówku kafla dopisek „ (offline)” w `muted` (ikona + tekst zamiast samego koloru, spec 3.1); kolory z `Theme`, pasek wg 3.2 (tło = `background`, HELIOS 17 i status 16 w `muted`, błąd w `accent`, ikona HA `0xFF18BCF2` / `muted`), kafle `surface` 100% krycia (0.8a), promień 18.
- Test: `ThemeTest` - kontrast WCAG (funkcje `Theme.contrast(int fg,int bg)` i `Theme.composite(int over,float alpha,int under)` w czystej Javie) ≥ 4,5 dla par `text/surface`, `muted/surface`, `accent/surface`, `text/background`, `background/accent` (ikona na przycisku akcentowym) w obu presetach, oraz dla tła 0.8b: kafel = `surface` 92% nad zdjęciem skrajnie białym przyciemnionym 35% (najgorszy przypadek), zegar = czerń 70% nad tym samym, dla obu par `text` i `muted` (stan offline używa `muted` przy pełnej alfie, więc ten sam test go pokrywa); jeśli test nie przechodzi, podnieść stałe krycia w planie B2, nie mierzyć zdjęcia w runtime.

### R2 Typografia zegara i kafli z pomiarem

**Pliki:** `DashboardView.java`, `DashboardSpec.java` (atrybut jednostki), nowe czyste klasy `TextFit.java` i `ClockLayout.java`, testy `TextFitTest.java`, `ClockLayoutTest.java`.

**Interfejs:**
- `TextFit.size(Measurer m, String text, float maxWidth, float min, float max)` - największy rozmiar z zakresu (krok 1), przy którym `m.width(text, size) <= maxWidth`; poniżej `min` zwraca `min` (spec: priorytet ma pełna godzina, więc renderer używa wyniku pomiaru nawet poniżej 96, ale nie obcina). `Measurer` to interfejs (w Androidzie `Paint.measureText`), testy używają liniowego pomiaru `0.6*size*len`.
- `ClockLayout.plan(Measurer m, String hour, float w, float h, boolean hasTitle) -> Plan{hourSize, showDate, showWeekday}`: wysokość linii = 1,2·rozmiar; stałe linie: tytuł 18 (gdy ustawiony), data 24, dzień tygodnia 20; budżet godziny = floor((h − 40 − suma linii) / 1,2) (rozmiar, nie wysokość linii); `hourSize = min(TextFit po szerokości w [32,120], budżet)`; gdy budżet < 32 → najpierw ukryj dzień tygodnia, potem datę (tytuł i pełna godzina mają priorytet); gdy nadal < 32 → hourSize = TextFit po szerokości (bez dolnej granicy, pełna godzina ważniejsza od minimum). Dla 2×2 (388×272) wynik mieści się w [96,120] i pokazuje wszystkie linie; dla 2×1 z tytułem (132 wys.): linie 18+24 → 50,4, budżet floor(41,6/1,2)=34 → dzień tygodnia ukryty, godzina 34, suma 40+50,4+40,8=131,2 ≤ 132; test `ClockLayoutTest` liczy `40 + Σ1,2·rozmiar` dla każdego przypadku i wymaga ≤ h; układ awaryjny 4×3 przez tę samą funkcję.
- Pogoda: temperatura 44, opis 18; jednostka z `temperature_unit` encji weather albo `unit_of_measurement` encji z `temperature_entity` - `DashboardSpec.attributes()` dodaje `unit_of_measurement` dla `i.temperatureEntity` (dziś ten atrybut jest odrzucany przy dekodowaniu, a kod dokleja `°C` domyślnie - usunąć domyślną jednostkę: brak jednostki = sama liczba bez symbolu). Test `DashboardSpecTest`: `attributes()` zawiera `unit_of_measurement` dla encji temperatury.
- 1×1: nagłówek 17, wartość `TextFit` w [24,28], do 2 linii, opis dostępności = pełny tekst.

**Testy:** `TextFitTest` (granice, krok, poniżej min), `ClockLayoutTest` (2×2, 2×1 z tytułem i bez, 1×1 194×132, 4×3 - żadna suma linii nie przekracza wysokości), `DashboardSpecTest` (+ atrybut jednostki); emulator: `00:00`, `11:11`, `23:59`, "Środa, 30 października" (długa data).

### R3 Panel muzyki wg geometrii 4.1

**Pliki:** `MusicOverlay.java`, `IconView.java`, nowa czysta klasa `OverlayGeometry.java`, `OverlayGeometryTest.java`.

**Interfejs:**
- `OverlayGeometry` - stałe prostokąty z tabeli 4.1 (w jednostkach 800×480): `PANEL(400,60,392,412)`, `STATUS(16,16,280,48)`, `CLOSE(320,0,72,72)`, `ART(16,76,144,144)`, `TITLE(176,76,200,84)`, `ARTIST(176,168,200,48)`, `PREV/PLAY/NEXT/STOP(16|112|208|304,228,72,72)`, `MUTE(16,316,72,72)`, `VOLUME(96,316,280,72)`, `HANDLE(728,204,72,72)`; `static boolean overlaps(Rect a, Rect b)`.
- `MusicOverlay`: absolutne pozycjonowanie w `FrameLayout` przez `OverlayGeometry` × skala; tło `Theme.surface` 100%; chevron (IconView `chevron-right`) zamiast przycisku Schowaj; ikony transportu z `IconView` (`previous`, `play`/`pause`, `next`, `stop`), przycisk play z tłem `accent` (lub kolor z okładki - `AccentColor.average(Bitmap)`: średnia RGB z bitmapy pomniejszonej do ≤16×16, liczona raz przy zmianie okładki na wątku `network`; ikona ciemna lub jasna wg `Theme.contrast`); mute jako ikona `speaker`/`speaker-off`; suwak z etykietą wartości `0-100`; tytuł 23 do 3 linii, wykonawca 18 do 2 linii, brak okładki → płytka `surface` z ikoną `note`, brak tytułu → "Nieznany utwór".
- Animacja: `panel.setTranslationX(width→0)` 180 ms przez `ViewPropertyAnimator`, mnożnik `Settings.Global.ANIMATOR_DURATION_SCALE` (0 → natychmiast). Uchwyt na `HANDLE` (y=204, przykrywa kolumnę 4 wiersz 2).
- **Zmiana zakresu (użytkownik, 2026-09-15): zamiast suwaka głośności panel ma suwak pozycji w utworze** - zegar ma sprzętowe przyciski głośności. Pole `VOLUME` z tabeli 4.1 staje się `SEEK` (ta sama geometria 96,316,280×72, etykieta `m:ss / m:ss`); pozycja = ostatni `progress` z `server/state` + czas od jego odbioru podczas grania (tick co 1 s tylko przy otwartym panelu); puszczenie suwaka = jedna komenda MA `player_queues/seek` (`queue_id` = player Lenovo, `position` w sekundach), lokalna pozycja ustawiana od razu, następny `progress` serwera ją potwierdza; brak `track_duration` (radio) = suwak wyłączony, sam czas. Głośność MA nadal ustawiana przez API w bibliotece muzyki. Poniższy opis suwaka głośności nie obowiązuje, mechanizm `pending` przeniesiony na seek. Pierwotnie: suwak głośności: `onStopTrackingTouch` wysyła jedną komendę `musicVolume(v)`; podczas przeciągania zmienia się tylko etykieta; echo `server/command`/stan MA aktualizuje suwak bez wysyłania komendy (flaga `fromServer` przy `setProgress`). Droga błędu: `HeliosService.musicVolume/musicMute/musicCommand` przy błędzie lub timeoucie 10 s ustawiają `musicIssue = "Głośność: <kod>"` (odpowiednio `Muzyka: <kod>`) i wołają `publishMusic()`; nakładka w `setSnapshot` ustawia suwak z `snapshot.volume` tylko gdy `!seeking && pendingVolume == null` (istniejąca ochrona gestu zostaje); po puszczeniu `pendingVolume = v`, kasowane gdy przyjdzie snapshot z `volume == pendingVolume` (echo MA), snapshot z `issue` zaczynającym się od „Głośność:” (wtedy suwak wraca do `snapshot.volume`) albo po 10 s (`postDelayed`, spójne z timeoutem serwisu); `issue` pokazywane w statusie (do 2 linii); `MainActivity.Actions` przestaje pokazywać Toast dla tych trzech akcji. Następny poprawny stan MA (`player_updated`) czyści `musicIssue`. Akcje bez wsparcia (`commands` w snapshotcie) są `setEnabled(false)` z alfą 0,4, nigdy ukrywane; stop niezależny od oczekującego play/pauza (bez wspólnej blokady `busy`).
- Dotyk: panel `onTouchEvent` zwraca `true` tylko w obszarze `PANEL`; uchwyt tylko w `HANDLE`; lewa połowa dashboardu zawsze interaktywna (nakładka ma `MATCH_PARENT`, ale dotyk poza panelem/uchwytem zwraca `false`).

**Testy:** `OverlayGeometryTest` - żadne dwa hitboxy nie nachodzą, wszystkie w obrębie `PANEL`, `HANDLE` w ekranie i dotyka prawej krawędzi, `HANDLE` przecina komórkę (4,2) i żadnej innej. Emulator: zrzuty playing/paused/bez okładki/długi tytuł; dotknięcie kafla (4,2) po obu stronach krawędzi uchwytu (fixture `--v3`, kafel `music` w (4,3), a w (4,2) kafel `cover` z wywołaniem `call_service` liczonym w logu fixture).

### R4 Dialogi w palecie

**Pliki:** `MainActivity.java` (klawiatura parowania, dialog urządzenia, panel rolety, potwierdzenie), `MusicLibraryDialog.java`, `NavigationMenu.java`.

Wymiana `new Button(...)` na `Theme.button(...)`, tła na `Theme.card(...)`, kolory tekstu na `Theme`; potwierdzenie (`AlertDialog`) zastąpione własnym `Dialog` z przyciskami 56 px wysokości w palecie (spełnia też uwagę o małych przyciskach z 0.5). Zakres funkcjonalny bez zmian. Test: emulator - zrzuty menu, klawiatury, dialogu urządzenia, biblioteki, panelu rolety, potwierdzenia bramy.

### R5 Wydanie 0.8.6

`versionCode 15`; testy JVM; lint; zrzuty; raport `artifacts/native-0.8.6-results.md`; pomiar na zegarze: RAM (`dumpsys meminfo`) i underruny przed/po (przez `AudioTrackSink.underruns()` w dzienniku diagnostycznym co 60 s podczas grania - nowe zdarzenie `music_stats`).

---

## Etap B - tła i motyw per urządzenie (SPEC 0.8b), Helios 0.8.7 + ha-helios 0.7.5

### B0 Bramka: options flow z FileSelector na HA 2026.8.3

Przed B1 na HA użytkownika: tymczasowy options flow w ha-helios (gałąź, wersja 0.7.5-rc) z samym `FileSelector` + `process_uploaded_file` logującym rozmiar i typ pliku; próba z Companion na telefonie (galeria) i z przeglądarki; anulowanie i ponowny upload. Wynik do `artifacts/ha-helios-fileselector-gate.md`. Brak przepływu → stop i decyzja użytkownika (SPEC 0.8b pkt 7).

### B1 ha-helios: opcje wyglądu, obraz, endpoint, snapshot

**Pliki (repo ha-helios):** `config_flow.py` (klasa `HeliosOptionsFlow` + `@staticmethod @callback async_get_options_flow(config_entry)` na `HeliosConfigFlow` - HA szuka jej na klasie config flow, nie w `__init__.py`), nowy `appearance.py` (walidacja, przetwarzanie obrazu, magazyn), nowy `http.py` (`HeliosAppearanceView`), `__init__.py` (update listener, rejestracja widoku, `async_remove_entry`, sprzątanie), `coordinator.py` (`send_appearance()`), `websocket.py` (snapshot po `connected`), `manifest.json` (`dependencies: ["websocket_api", "file_upload", "http"]`, wersja 0.7.5), `tests/test_appearance.py`.

**Kontrakt:**
- Opcje wpisu: `options["appearance"] = {version:1, theme, background:{type:"solid"} | {type:"image", image_id, path, dim, focus_x, focus_y}}` oraz osobno `options["image_id"]` = ostatni przygotowany obraz (zachowany także przy `solid`, na potrzeby „Zachowaj”); `dim`/`focus_*` z `NumberSelector` przychodzą jako `float` - przed walidacją `int(round(v))`; walidacja `validate_appearance(dict) -> dict` (czysta, testowana): `theme ∈ {warm_graphite, night_blue}`, `dim` int 35-80, `focus_*` int 0-100, `image_id` `[a-f0-9]{64}` tylko dla `image`, brak nieznanych pól.
- Options flow (`OptionsFlow`, nie `OptionsFlowWithReload`), dwa kroki: `init` - `theme` (SelectSelector), `background` (SelectSelector: `solid` / `keep` (opcja obecna tylko gdy obraz istnieje) / `upload`), `dim` (NumberSelector 35-80, slider, domyślnie 50), `focus_x`, `focus_y` (NumberSelector 0-100, domyślnie 50); przy `background == upload` przejście do kroku `upload` z jednym polem `file` (FileSelector `accept: image/jpeg,image/png`), inaczej zapis od razu. Zapisy tego samego wpisu serializowane `asyncio.Lock` w `hass.data[DOMAIN][entry_id]`. Zapis: przy `upload` → `process_uploaded_file` w executorze → `prepare_image(path) -> bytes` (Pillow z rdzenia: EXIF transpose, nowy obraz z pikseli, spłaszczenie alfa na `#181C24`, `thumbnail` do 1600×960, JPEG q=85, ≤ 2 MiB przez obniżanie jakości do 60; odrzucenie: nie JPEG/PNG, > 10 MiB, > 24 Mpx, > 8192 na osi, animacja/uszkodzenie) → `image_id = sha256(bytes)` → zapis do `hass.config.path("helios", entry_id, f"{image_id}.jpg")` (tmp + rename; nazwa = id, więc nowy plik nigdy nie nadpisuje starego) → `async_update_entry(options=...)` z nowym `image_id` → potem usunięcie `*.jpg` innych niż nowy id i poprzedni `options["image_id"]` (na dysku zostają najwyżej dwa pliki: nowy i poprzedni). HA zapisuje wpisy z opóźnieniem (`async_delay_save`, 1 s), więc poprzedni plik zostaje do następnego uploadu; przerwanie w dowolnym momencie zostawia parę spójną z tym, co HA utrwaliło (stare opcje + stary plik albo nowe + nowy), a niepasujący plik usuwa sprzątanie przy starcie. Błąd przetwarzania: `errors["file"]` z komunikatem, stare opcje i plik nietknięte. `keep`/`solid` nie dotykają pliku ani `options["image_id"]`; `keep` przy braku pliku na dysku → `errors["background"]`.
- `HeliosAppearanceView(HomeAssistantView)`: `url = /api/helios/appearance/{entry_id}/{image_id}`, `requires_auth = True`; sprawdzenie: wpis istnieje, `request["hass_user"].is_admin` lub `user.id == entry.data["user_id"]`; `image_id` równy `options["image_id"]`, inaczej 404 (ścieżka pliku budowana wyłącznie z tego zwalidowanego id, nigdy z URL); odpowiedź `web.FileResponse` `image/jpeg`, `Cache-Control: private, max-age=31536000` (id zmienia się z treścią).
- Snapshot: `coordinator.send_appearance()` wysyła `{type:"appearance", appearance:{...}}` na aktywnej subskrypcji; wołany po `connected` (w `ws_connect`) i z update listenera wpisu (`entry.add_update_listener`) - bez `async_reload`. Brak opcji → domyślny snapshot `{version:1, theme:"warm_graphite", background:{type:"solid"}}`.
- Sprzątanie: przy usunięciu wpisu (`async_remove_entry`) katalog `helios/<entry_id>` usuwany; przy starcie (`async_setup_entry`, w executorze) samonaprawa pary: jeśli plik `options["image_id"]` istnieje → pozostałe `*.jpg` usuwane; jeśli nie istnieje, a są inne pliki (HA przerwane zanim utrwaliło opcje po jednym lub kilku uploadach) → przyjęty najnowszy wg mtime: `options["image_id"]` i (przy `background.type == image`) `image_id`/`path` w `appearance` aktualizowane, reszta usuwana; brak plików → `image_id` usuwane z opcji, tło `image` zamieniane na `solid`. Każdy stan po przerwaniu kończy się spójną parą z obrazem, który użytkownik faktycznie wgrał (test `test_appearance.py::test_repair_*` na katalogu tymczasowym z czystą funkcją `repair_storage(dir, options) -> options`).

**Testy (`tests/test_appearance.py`, bez HA):** walidacja (każde pole i granice), `prepare_image` na wygenerowanych obrazach (PNG z alfą → JPEG bez alfy, EXIF orientacja 6 → obrócony, 9000×100 → odrzucony, 3000×2000 → ≤1600×960, wynik ≤ 2 MiB, brak EXIF w wyniku), `image_id` deterministyczny.

### B2 Zegar: odbiór `appearance`, obraz, warstwa tła

**Pliki:** `HeliosDeviceClient.java` (+ `Listener.onAppearance(JSONObject)`), nowa czysta klasa `Appearance.java` (`parse(JSONObject) -> Appearance` z walidacją jak w HA; `theme`, `background`, `imageId`, `dim`, `focusX`, `focusY`), nowa `BackgroundLoader.java` (generacje jak `ArtworkLoader`; deduplikacja tylko dla pobrania w locie lub dostarczonego, nieudane pobranie ponawia się przy następnym snapshotcie - ten przychodzi po każdym `connected` i zapisie opcji, więc bez timera; klucz zadania = `imageId + focusX + focusY`: ten sam `imageId` z innym kadrem nie pobiera ponownie, tylko dekoduje ponownie z pliku cache z nowym kadrem, a zmiana klucza w trakcie pobrania unieważnia wynik w locie; `dim` jest nakładany przy rysowaniu, bez dekodowania; plik cache `files/background.jpg` + `background.json` z `imageId`, origin HA i `device_id`), `HeliosService.java` (przechowanie w prefs `appearance`, pobranie z `connection.url + /api/helios/appearance/<entry?>`... patrz niżej, dekodowanie do 800×480 z `focus` poza UI), `DashboardView.java` (warstwa tła: `ImageView` center-crop pod kaflami + osłona `dim`, kafle 92% krycia gdy obraz, zegar z osłoną 70%, pasek i panel 100%), `MainActivity.java` (przekazanie snapshotu do widoku), testy `AppearanceTest`, `BackgroundLoaderTest`, `CropMathTest`.

**Kontrakt:**
- Zegar nie zna `entry_id`; integracja umieszcza w snapshotcie (wariant `image`) pole `background.path = "/api/helios/appearance/<entry_id>/<image_id>"` (uzupełnienie kontraktu z SPEC 0.8b pkt 4: ścieżka względna, nie URL - origin dokłada zegar z `connection.url`; `validate_appearance` w B1 wymaga tego pola przy `image`). Walidacja: ścieżka zaczyna się od `/api/helios/appearance/`, bez `..`, `image_id` w ścieżce równe `image_id` w obiekcie.
- Pobranie: `HttpURLConnection` na origin HA (schemat+host+port z `connection.url`, ścieżka tylko z walidowanego `path`), nagłówek `Authorization: Bearer <token HA>`, `setInstanceFollowRedirects(false)`, limit 2 MiB, timeout łącznie 10 s; jedno pobranie na `imageId` w locie; 401/403/404/za duży → fallback (ostatnie poprawne tło lub kolor motywu) + zdarzenie diagnostyczne `background_error`.
- Dekodowanie: `inSampleSize` do ≥ 800×480, potem `CropMath.rect(srcW, srcH, 800, 480, focusX, focusY)` (środek kadru ograniczony, bez pasów) → `Bitmap` 800×480 w pamięci; poprzednia bitmapa zwalniana po podmianie referencji.
- Motyw i kolor stosowane natychmiast po poprawnym snapshotcie (`Theme.set(...)`, `DashboardView.applyTheme()` bez przebudowy siatki); obraz po pobraniu. Brak zdarzenia (stara integracja) → prefs/cache lub domyślne. `ended` kanału (offline, `replaced`, `removed` - to ostatnie integracja wysyła także przy zwykłym przeładowaniu, więc nie odróżnia usunięcia wpisu od reloadu) nie kasuje cache. Cache, prefs wyglądu i motyw wracają do domyślnych, gdy: `reconfigure` zmienia sekcję HA, albo `connected` przynosi `device_id` inny niż zapisany razem z wyglądem (nowe parowanie = nowy wpis = nowy device). Usunięty wpis w HA kasuje obraz po stronie integracji (`async_remove_entry`); lokalna kopia na zegarze żyje do nowego parowania - patrz rejestr rebutali.
- Nic nie restartuje: `SendspinClient`, `MusicAssistantClient`, `HaDashboardClient` nietknięte przy `appearance`.

**Testy:** `AppearanceTest` (pola, granice, nieznane pola, zła wersja → wyjątek, ścieżka z `..` → wyjątek), `CropMathTest` (poziome/pionowe źródło, focus 0/50/100, brak pasów), `BackgroundLoaderTest` (generacje jak H3; ten sam `imageId` z nowym `focus` → brak HTTP, nowe dekodowanie; zmiana `focus` w trakcie pobrania → stary wynik odrzucony, dekodowanie z nowym kadrem), `HeliosDeviceClientTest` (+ zdarzenie `appearance` trafia do listenera, nieznany typ nadal ignorowany). Emulator: fixture HA rozszerzona o snapshot `appearance` i endpoint obrazu (HTTP w fixture), zrzuty: kolor, zdjęcie jasne, zdjęcie ciemne, zmiana motywu podczas grania (uchwyt i panel bez zmian).

### B3 Odbiór 0.8.7

Wg SPEC 0.8b pkt 8: dwa zegary (albo dwa wpisy) na jednym `dashboard_path` z różnymi tłami (drugi wpis = drugi emulator/instalacja UI-test), EXIF/GPS usunięte (sprawdzenie pliku w `helios/<entry_id>` narzędziem `exiftool`/Pillow), 20 podmian obrazu bez wzrostu plików i RAM, zapis wyglądu w trakcie grania - sesja muzyczna nietknięta (`sendspin_connected` bez nowego wpisu w dzienniku). Raport `artifacts/native-0.8.7-results.md`.

---

## Threat model i granice hardeningu

Zaufane: HA i MA w LAN, dostęp tokenami zegara; snapshot `appearance` i metadane MA walidowane strukturalnie (typy, zakresy, długości, ścieżki), nie semantycznie. Niezaufane: pliki wgrywane przez FileSelector (walidacja formatu, rozmiaru, pikseli; ponowne kodowanie z pikseli), `artwork_url` z MA (tylko host MA, limit 1 MiB), ścieżka tła ze snapshotu (tylko prefiks `/api/helios/appearance/`, tylko origin HA). Poza zakresem: MITM w LAN bez TLS (decyzja użytkownika z 0.7), złośliwy administrator HA/MA, ochrona oryginału zdjęcia w tymczasowym magazynie `file_upload` HA przed jego własnym sprzątaniem, obrona przed wyczerpaniem dysku HA przez wiele wpisów (jeden plik ≤ 2 MiB na wpis).

## Rejestr rebutali

- R2-2 (`removed` a cache tła, Codex runda 2): integracja wysyła `removed` również przy przeładowaniu wpisu, a po jego usunięciu kanał już nie istnieje - zegar nie ma zdarzenia jednoznacznie oznaczającego usunięcie parowania. Decyzja: cache kasowany przy rekonfiguracji HA i przy `connected` z innym `device_id`; po usunięciu wpisu integracja kasuje obraz na serwerze, a zegar pokazuje lokalną kopię (własne zdjęcie właściciela zegara) do nowego parowania. Nie dodajemy timerów ani nowego typu zdarzenia.

## Changelog rund

- Po zatwierdzeniu, w trakcie R3: użytkownik zmienił suwak głośności na suwak pozycji utworu (opis w R3). Fixture MA obsługuje `player_queues/seek` i ticki `progress` co 5 s.
- Runda 5 (Codex, REJECT, 2×P2): R3 - snapshot nie nadpisuje suwaka podczas gestu ani oczekującej komendy (`pendingVolume`), reset tylko po błędzie/echu/10 s; H3 - zmiana URL natychmiast publikuje pustą okładkę przed pobraniem.
- Runda 4 (Codex, REJECT, 3×P2): H2 - jeden nadawca `onState` (tylko wątek WS, `refreshState` synchronized, usunięte z `playback()`/`closeStream()`); R3 - błąd/timeout głośności trafia do `MusicSnapshot.issue`, nakładka resetuje suwak ze snapshotu, bez Toastów; R1 - offline bez `setAlpha(.55f)`, tekst `muted` przy pełnej alfie + dopisek „(offline)”, test kontrastu pokrywa `muted` nad zdjęciem.
- Runda 3 (Codex, REJECT, 4×P2): H2 - `sessionActive` przełączane tylko na wątku WS przy pierwszej ramce audio po `stream/start` (bez wyścigu z sinkiem); R2 - budżet godziny dzielony przez 1,2, przykład 2×1 policzony; B1 - samonaprawa pary plik-opcje przy starcie (`repair_storage`); B2 - klucz loadera tła `imageId+focus`, ponowny kadr z cache bez HTTP.
- Runda 2 (Codex, REJECT, 4×P2): B1 - stary plik zostaje do następnego uploadu (opóźniony zapis wpisów HA), sprzątanie przy starcie; B2 - `removed` nie kasuje cache (rebutal R2-2), kasowanie przy innym `device_id`; H3 - nieudane pobranie ponawiane po 30 s, `BackgroundLoader` ponawia przy następnym snapshotcie; R2 - `ClockLayout` dopasowuje godzinę i linie do wysokości kafla (2×1 z tytułem).
- Runda 1 (Codex, REJECT, 7×P2): H3 - `ArtworkLoader.request` nie pobiera ponownie tego samego URL (delty postępu); R2 - `DashboardSpec.attributes()` zachowuje `unit_of_measurement` encji temperatury; B1 - `async_get_options_flow` na `HeliosConfigFlow`, normalizacja `float→int` z `NumberSelector`, plik nazwany `<image_id>.jpg` zapisany przed opcjami (stara para przeżywa przerwanie), osobne `options["image_id"]` zachowuje zdjęcie przy `solid`; B2 - zdarzenie `removed` kasuje cache i prefs wyglądu (`Listener.onRemoved()`).
