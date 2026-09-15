# Plan implementacji Helios 0.7 (integracja HA) i 0.6 (Music Assistant)

Status: zatwierdzony przez zewnętrzną recenzję Codex (7 rund, 2026-09-15, APPROVE w R7). Zrealizowane 2026-09-15: T1.1-T1.7 (Helios 0.7.0) i T2.1-T2.6 (Helios 0.8.0), z testami JVM i smoke w emulatorze na fałszywych serwerach; raporty `artifacts/native-0.7-results.md`, `artifacts/native-0.8-results.md`. Otwarte: T1.8 i T2.7 (odbiór na fizycznym zegarze, prawdziwe HA i MA). Realizuje [SPEC 0.7](SPEC-0.7-home-assistant-integration.md) i [SPEC 0.6](SPEC-0.6-music-assistant.md) na bazie zaimplementowanego [SPEC 0.5](SPEC-0.5-ha-configurable-dashboard.md). Kolejność: najpierw 0.7, bo wymaga przeniesienia transportu HA do serwisu, z którego 0.6 też korzysta, i odblokowuje kontekst pokoju dla głosu bez czekania na bramki audio.

Każde zadanie kończy się zielonym `./gradlew testDebugUnitTest assembleDebug lintDebug` (JAVA_HOME = JBR Android Studio) i osobnym commitem. Testy JVM używają wzorca z `HaDashboardClientTest`: prawdziwy `WebSocketServer` na `127.0.0.1:0`, bez mocków Androida. Kod Android pozostaje Java 8, bez nowych zależności.

## Stan wyjściowy (0.5.0, commit `53ede71`)

| Plik | Rola dziś | Co się zmienia |
| --- | --- | --- |
| `MainActivity.java` | tworzy `HaDashboardClient` w `onResume`, zatrzymuje w `onPause`; dialogi, akcje, głos | przestaje posiadać transport; binduje `HeliosService`; dodaje ekrany parowania, ustawień urządzenia, muzyki |
| `HaDashboardClient.java` | jedno gniazdo WS do HA: auth, `subscribe_events` (id 1), `lovelace/config` (id 2), `subscribe_entities` (id 3), `call_service` (id >= 100) z `pending` | dostaje ogólne `request()` i `subscribe()` dla innych modułów; nadal restartuje sesję po `lovelace_updated` (świadomy koszt z SPEC 0.7 pkt 3) |
| `AssistClient.java` | `assist_pipeline/run` bez `device_id` | dodaje `device_id`; raportuje stan głosu |
| `DashboardSpec.java` | schemat 2, sześć typów, rejestr ikon | schemat 3, typ `music`, ikona `music`, `music_layout` zabronione |
| `DashboardView.java` | pasek + siatka 4x3 | nakładka muzyczna i uchwyt nad siatką |
| `NavigationMenu.java` | stałe pozycje | nowe pozycje: Paruj z HA, Urządzenie (głośność, lampka), Odśwież parowanie |
| `AndroidManifest.xml` | activity, INTERNET, RECORD_AUDIO | `FOREGROUND_SERVICE`, `<service>` |
| `ha/` | YAML panelu | `ha/custom_components/helios/` |

Globalne ograniczenia (z obu spec-ów): brak dowolnych usług, komend, intentów i URL-i z YAML lub z HA; komendy to allowlista; brak kolejki offline i ponawiania; `null`/`unknown` nigdy nie znaczy "off"; sekrety tylko w prywatnych preferencjach z `allowBackup=false`; brak tokenów w logach i diagnostyce.

---

## Etap 1 - Helios 0.7

### T1.1 Serwis i wspólny transport HA

**Pliki:** nowy `HeliosService.java`; zmiany `HaDashboardClient.java`, `MainActivity.java`, `AndroidManifest.xml`.

**Interfejs:**
- `HeliosService extends Service` - foreground service z jednym stałym powiadomieniem ("Helios działa"), lokalny `Binder` z `HaDashboardClient client()`. Start: `MainActivity.onCreate` woła `startForegroundService` + `bindService`; serwis tworzy klienta, gdy w prefs jest `connection`, i zatrzymuje go w `onDestroy`. Serwis nie zależy od widoczności aktywności.
- `HeliosService.reconfigure(JSONObject connection)` - jedyna ścieżka zmiany konfiguracji. Kolejność: (1) walidacja strukturalna (URL-e ze schematem http/https lub ws/wss, niepuste tokeny); (2) porównanie sekcji z bieżącymi prefs: `url/token/pipeline/dashboard_path` (HA) i `music_assistant` (MA) - weryfikowane i restartowane są **tylko zmienione sekcje**: HA zmienione → WS `auth` do `auth_ok` na tymczasowym połączeniu; MA zmienione → WS `auth` MA do sukcesu; Sendspin bez uwierzytelnienia, tylko schemat URL; sekcja niezmieniona nie jest sprawdzana ani jej klient restartowany (wymiana tokena MA działa przy niedostępnym HA i nie zrywa kanału HA); (3) zapis i restart **per sekcja, niezależnie**: sekcja, która przeszła (1)+(2), jest zapisywana i jej klient restartowany, nawet gdy druga sekcja nie przeszła; sekcja z błędem zostaje przy poprzedniej wartości (albo bez wartości przy pierwszym provisioningu) i daje osobny komunikat w UI ("HA: połączono", "MA: token odrzucony"). Niedostępny lub błędny MA nigdy nie blokuje konfiguracji HA, także przy pierwszym provisioningu; brak MA to normalny stan aplikacji; `MainActivity.connect()` po udanym provisioning woła `reconfigure` zamiast usuwanego `startDashboard()`; to samo robi "Odśwież parowanie" (T1.7). Podpięci słuchacze dostają `onUnavailable("Zmiana konfiguracji…")`, potem nowe `onDashboard`.
- `HaDashboardClient`: zamiast jednego `Listener` w konstruktorze - `void attach(Listener)` / `void detach(Listener)`; przy `attach` klient **zawsze** odtwarza najpierw `onDashboard(lastRaw, lastSpec, lastStates, lastIssue)` z najnowszym snapshotem (aktualizowanym przy każdym `onStates`, także gdy nikt nie jest podpięty), o ile jakikolwiek układ kiedykolwiek przyszedł, a **potem** `onUnavailable(lastReason)`, jeśli sesja nie jest w tej chwili żywa - aktywność rozstrzyga widoczność z najnowszego snapshotu, a dopiero potem zamraża ją i oznacza dane jako nieaktualne (SPEC 0.5 pkt 9), żeby aktywność po powrocie nie pokazała starego stanu jako aktualnego. Cache `dashboard_v2` zapisuje serwis, nie aktywność.
- Nowe metody ogólne w kliencie, używane przez T1.2 i T2.x:
  - HA wymaga ściśle rosnących `id` w obrębie połączenia. Wszystkie wiadomości sesji (auth nie ma `id`; `subscribe_events`, `lovelace/config`, `subscribe_entities`, `call_service`, subskrypcje modułów) pobierają `id` z jednego licznika `ids` (start 1, nigdy nie resetowany); stałe 1/2/3 znikają, a dopasowanie odpowiedzi idzie po zapamiętanych `id`. Przydział `id` i `send()` są jedną sekcją krytyczną (`synchronized` na gnieździe): żaden wątek nie może wysłać `N+1` przed `N`; wątek sesji też przechodzi przez tę samą metodę. Fałszywy serwer w testach odrzuca `id` mniejsze lub równe poprzedniemu (`id_invalid`), jak HA; test wysyła 50 `request` z 4 wątków równolegle i oczekuje 50 sukcesów.
  - `int request(JSONObject payload, Consumer<JSONObject> result)` - nadaje `id` z tego licznika, wysyła, wynik (`success` true/false + `result`/`error`) trafia do `result`; timeout `CALL_TIMEOUT_MS`; `callService` staje się jednym z wywołań `request`.
  - `int subscribe(JSONObject payload, Consumer<JSONObject> event, Consumer<String> ended)` - jak `request`, ale zdarzenia `type=event` z tym `id` idą do `event`; `ended` woła się przy zamknięciu sesji. Subskrypcja jest odnawiana przez wołającego po `onSessionStarted()` (nowy callback listenera), nie automatycznie.
- `MainActivity`: usuwa `startDashboard/stopDashboard`; w `onResume` `attach(listener)`, w `onPause` `detach`. Reszta logiki dashboardu (widoczność, dialogi, pending) bez zmian.

**Testy (`HaDashboardClientTest`):** `attach` po pierwszym snapshotcie dostaje natychmiastowe `onDashboard`; `detach`, zmiana stanu encji, rozłączenie serwera, `attach` → kolejno `onDashboard` z nowym stanem i `onUnavailable`; `subscribe` odbiera zdarzenia i `ended` po rozłączeniu; `request` z błędem HA zwraca `success=false`; istniejące 4 testy przechodzą.

**Ryzyko do próby na OTA 627:** czy foreground service z powiadomieniem jest tolerowany przez launcher OEM i czy proces przeżywa otwarcie Ustawień. Bramka: 10 minut w Ustawieniach, encje w HA nie stają się `unavailable`.

### T1.2 Kanał urządzenia po stronie zegara

**Pliki:** nowy `HeliosDeviceClient.java`, nowy `Telemetry.java`; zmiany `HeliosService.java`.

**Interfejs:**
- `Telemetry` - niemutowalny snapshot: `appVersion`, `versionCode`, `voiceState` (idle/listening/processing/responding/error), `dockConnected` (Boolean|null), `charging` (Boolean|null), `ledOn` (Boolean|null), `ledBrightness` (Integer|null), `padVersion` (String|null), `volumePercent` (Integer|null), `uptimeSeconds`. `JSONObject toJson()` - `null` → JSON `null`. Test: round-trip i brak zamiany `null` na `false`.
- `HeliosDeviceClient(HaDashboardClient ha, Supplier<Telemetry> telemetry, CommandHandler handler)`:
  - na `onSessionStarted()` woła `ha.subscribe({type:"helios/connect", protocol:1, installation_id, app_version, version_code, capabilities:["lamp","volume"], pairing_code?}, ...)`;
  - zdarzenie `connected` → zapisuje `device_id`, `area_id` (getter `String deviceId()` dla `AssistClient`), wysyła pełny `helios/state` przez `request`;
  - zdarzenie `command` `{request_id, command, args}` → `handler.execute(command, args)` w tle, potem `request({type:"helios/result", request_id, status:"ok"|"error", code})`; jedna akcja na zasób (`lamp`, `volume`), druga równoległa dostaje `status:"busy"`;
  - każda komenda jest związana z numerem generacji subskrypcji (`int generation`, +1 przy każdym `helios/connect`); zadanie w tle sprawdza generację tuż przed wywołaniem sprzętu (nieaktualna → porzucone bez dotykania sprzętu) i przed wysłaniem wyniku (nieaktualna → wynik i telemetria z tego zadania są odrzucane, nie trafiają do nowego kanału);
  - `ended` subskrypcji (rozłączenie, unsubscribe przez HA po usunięciu wpisu, zastąpienie sesji) natychmiast czyści `device_id`/`area_id` i podnosi generację; `AssistClient` dostaje `null` do następnego `connected`;
  - `void publish()` - wysyła `helios/state` po każdej zmianie telemetrii, nie częściej niż 10/s (ostatnia zmiana wygrywa), i tylko gdy subskrypcja jest aktywna; bez kolejki po rozłączeniu.
  - `installation_id`: UUID generowany raz, w prefs `installation_id`; `pairing_code` przekazany z T1.8 tylko do pierwszego udanego `connected`.
- `CommandHandler` allowlista: `lamp.turn_on`, `lamp.turn_off`, `lamp.set_brightness{level:1..10}`, `audio.set_device_volume{percent:0..100}`; wszystko inne → `status:"error", code:"unknown_command"` bez dotykania sprzętu. Walidacja typów: `level` i `percent` muszą być `Integer`.

**Testy (`HeliosDeviceClientTest`, fałszywy serwer HA jak w `HaDashboardClientTest`):** `helios/connect` zawiera `installation_id` i `capabilities`; po `connected` przychodzi `helios/state`; `command` z allowlisty wywołuje handler i zwraca `ok`; nieznana komenda i `level: "5"` zwracają `error` bez wywołania handlera; druga komenda `lamp` w trakcie pierwszej → `busy`; po rozłączeniu i ponownej sesji subskrypcja jest odnawiana; zmiany telemetrii są łączone do max 10/s.

### T1.3 Adapter lampki i docka (binder OEM)

**Pliki:** nowy `DockController.java`, nowy `LampMath.java`.

**Interfejs:**
- `LampMath.brightnessToLevel(int haBrightness1to255) -> int 1..10` = `max(1, round(b*10/255))`; `levelToBrightness(int level) -> int`. Test JVM: 1→1, 255→10, 128→5, 13→1.
- `DockController(Context, Listener)`: `bindService` do `com.google.assistant.oemapp/.ScoriaAssistantOemAccessoryService` z `BIND_AUTO_CREATE`; po `onServiceConnected` rejestruje listener połączenia (transakcja 2) i ładowania (7) - raz na binder; `linkToDeath` → stan `unknown` i ponowny bind z backoffem 5-60 s.
  - `void turnOn()`, `void turnOff()`, `void setBrightness(int level)` (walidacja 1..10 przed transakcją; `turnOn` po `setBrightness` jawnie, zgodnie ze spec), `Boolean isLedOn()` (transakcja 6, `readException`).
  - Stan: `dockConnected` `null` do pierwszego callbacku; `charging` `null` do pierwszego zdarzenia i po odłączeniu docka; `ledBrightness` = ostatnia przyjęta nastawa w tej sesji bindera, `null` przed nią; `padVersion` z `onConnect`.
  - Brak pakietu OEM lub `SecurityException` → stan `unavailable` raportowany przez `Listener.onUnavailable(reason)`; nie zatrzymuje reszty aplikacji.
- `INTERFACE_TRANSACTION` nie jest używana (wadliwa odpowiedź OEM, nota lampki).

**Testy:** JVM tylko `LampMath`. Binder - sonda na urządzeniu: `tools/lamp-probe/LampProbe.java` już istnieje; odbiór ręczny: on, off, jasność 3, `isLedOn` po każdej operacji, odłączenie docka → `unknown`.

### T1.4 Głośność urządzenia

**Pliki:** nowy `DeviceVolume.java`.

**Interfejs:** `DeviceVolume(Context, Consumer<Integer> onChange)`: `int percent()` = `getStreamVolume(STREAM_MUSIC) * 100 / getStreamMaxVolume`, `void set(int percent)` → `setStreamVolume(STREAM_MUSIC, round(percent*max/100), 0)` i natychmiastowy odczyt; polling `getStreamVolume` co 3 s na `Handler` serwisu, `onChange` tylko przy zmianie odczytu; `stop()` kończy polling. Bez `VOLUME_CHANGED_ACTION` w pierwszej wersji (spec: opcjonalny akcelerator, osobna próba).

**Test:** logika procentów w czystej metodzie `static int toSteps(int percent,int max)` / `static int toPercent(int steps,int max)`: 0→0, 100→max, zaokrąglenia dla max=100 i max=15.

### T1.5 Stan głosu i `device_id` w Assist

**Pliki:** zmiany `AssistClient.java`, `MainActivity.java`, `HeliosService.java`.

- `AssistClient(Context, JSONObject config, Supplier<String> deviceId, Listener)`: wartość pobierana **przy każdym** `assist_pipeline/run`, także przy dopowiedzeniu; `null` gdy brak urządzenia. Źródło: `HeliosDeviceClient.deviceId()` przez serwis. Jeśli `device_id` zmienił się między kolejnymi uruchomieniami w tej samej rozmowie (usunięcie wpisu w HA podczas TTS, ponowne parowanie), pętla dopowiedzeń kończy się po bieżącym TTS: `conversation_id` jest porzucany, następna rozmowa startuje od zera i od nowego identyfikatora. `HeliosService` przy `ended` kanału urządzenia dodatkowo woła `voice.cancelFollowUp()` (nowa metoda: nie przerywa trwającego TTS, blokuje kolejny `runOnce`).
- Stan głosu do telemetrii: `MainActivity` mapuje istniejące zdarzenia: `wake_listening`→idle, `microphone_started`→listening, `Czekam na odpowiedź`→processing, `playback_started`→responding, `test_error`→error, `ready`→idle; serwis publikuje przez `HeliosDeviceClient.publish()`.

**Test:** `AssistClient` nie ma testu JVM (audio); sprawdzić w emulatorze z fałszywym HA, że `assist_pipeline/run` zawiera `device_id` - rozszerzyć `.local/ui-dashboard-fixture-v2.py` o logowanie tego pola.

### T1.6 Custom component `helios` w HA

**Pliki (od 2026-09-15 w osobnym repozytorium HACS `SychPL/ha-helios`):** `custom_components/helios/{manifest.json, __init__.py, const.py, config_flow.py, websocket.py, coordinator.py, entity.py, sensor.py, binary_sensor.py, light.py, number.py, strings.json, translations/pl.json}`, `ha/custom_components/helios/tests/test_helpers.py`.

**Kontrakt (SPEC 0.7 pkt 3):**
- `config_flow.py`: krok `user` generuje 6-cyfrowy kod, zapisuje `{code, expires: now+5min, flow_id}` w `hass.data[DOMAIN]["pairing"]`, pokazuje `async_show_progress` z kodem; po `helios/connect` z pasującym kodem websocket woła `flow.async_configure` → `async_show_progress_done` → `async_create_entry(unique_id=installation_id, data={installation_id, user_id})`. Limit 5 prób złego kodu na flow, potem `abort`. Ponowne parowanie tego samego `installation_id` → `async_update_reload_and_abort` istniejącego wpisu.
- `websocket.py`: `helios/connect` (`@websocket_api.async_response`, wymaga zalogowanego użytkownika): jeśli brak wpisu dla `installation_id` i brak pasującego kodu → `unauthorized`; jeśli wpis istnieje, `user_id` się nie zgadza i nie ma pasującego kodu parowania → `unauthorized`; jeśli kod pasuje → wpis dostaje nowy `user_id` (ponowne parowanie na nowe konto); sukces → koordynator zapisuje `connection` i `msg_id` jako aktywnego właściciela (poprzedniemu wysyła zdarzenie `{type:"replaced"}` i kończy jego subskrypcję), `connection.subscriptions[msg_id] = cleanup`, wysyła zdarzenie `connected {device_id, area_id}`. `cleanup` działa **tylko gdy** `(connection, msg_id)` jest nadal aktualnym właścicielem; cleanup zastąpionej subskrypcji nic nie zmienia. Przy usunięciu wpisu (`async_unload_entry`/`async_remove_entry`) integracja wysyła aktywnej subskrypcji `{type:"removed"}` i kończy ją, więc zegar dostaje `ended` i czyści `device_id`. `helios/state` i `helios/result` sprawdzają, że pochodzą z aktywnego połączenia, inaczej `unauthorized`. `cleanup` (unsubscribe, zamknięcie WS) → koordynator `available=False`, oczekujące komendy kończą się błędem.
- `coordinator.py`: `HeliosCoordinator` trzyma `data: Telemetry|None`, `available`, `connection`; `async_command(command, args) -> dict` tworzy `request_id` (uuid4), wysyła `connection.send_message(event_message(msg_id, {type:"command", request_id, command, args}))`, czeka `asyncio.wait_for(future, 10)`; po timeoutcie wynik nieznany, encja nie zmienia stanu optymistycznie; wynik po timeoutcie jest ignorowany.
- Encje (`unique_id = f"{installation_id}_{suffix}"`, jedno `DeviceInfo` z `identifiers={(DOMAIN, installation_id)}`, `manufacturer="Lenovo"`, `model="Smart Clock 2"`, `sw_version=app_version`): `sensor` wersja (diagnostic), stan głosu, `pad_version` (diagnostic); `binary_sensor` dock (`connectivity`), ładowanie (`battery_charging`, `unavailable` gdy dock `null`); `light` lampka (`ColorMode.BRIGHTNESS`, `is_on` z `led_on`, `brightness = levelToBrightness(led_brightness)` lub `None`; `turn_on(brightness)` → `lamp.set_brightness` potem `lamp.turn_on`; `turn_on` bez jasności → `lamp.turn_on`; `turn_off` → `lamp.turn_off`); `number` głośność 0-100 (`set_native_value` → `audio.set_device_volume`).
- Wszystkie encje `available = coordinator.available and pole != None` (dla lampki dodatkowo `dock_connected is not False`).

**Testy:** `tests/test_helpers.py` (pytest bez HA): mapowanie jasności obustronnie, walidacja komend i argumentów, wygaśnięcie kodu parowania, limit prób. Reszta - ręczny odbiór na HA 2026.8.3 wg SPEC 0.7 pkt 7 scenariusze 1-5, 8, 9. Instalacja: kopiowanie katalogu do `config/custom_components/helios` skryptem `tools/install_ha_component.py` (ssh/samba - ustalić z użytkownikiem; domyślnie instrukcja ręczna).

### T1.7 Ekrany na zegarze: parowanie, urządzenie, odświeżenie parowania

**Pliki:** zmiany `NavigationMenu.java`, `MainActivity.java`.

- "Paruj z HA": dialog z polem kodu i własną klawiaturą numeryczną 0-9/usuń/OK (bez zależności od IME), widoczne Anuluj; OK → `HeliosDeviceClient.pair(code)` → ponowna subskrypcja `helios/connect` z kodem; wynik w pasku ("Sparowano z HA" / "Kod odrzucony").
- "Urządzenie": dialog z suwakiem głośności 0-100 (`SeekBar`, zmiana po puszczeniu), przełącznikiem lampki i suwakiem jasności 1-10; nieaktywne przy `unavailable`; Zamknij.
- "Odśwież parowanie" (dla 0.6, ale ta sama ścieżka): pobiera `PROVISION_URL` ponownie; scala tylko sekcję `music_assistant` (jeśli jest) do istniejącego `connection`; `url/token/pipeline` HA zmienia tylko po osobnym potwierdzeniu w dialogu; błąd zostawia poprzednią konfigurację; wynik idzie do `HeliosService.reconfigure`.
- Most provisioning po stronie komputera: `tools/native_bridge.py` dziś składa tylko konfigurację HA; zadanie dodaje odczyt prywatnego `.local/ma.json` (`url`, `token`, `sendspin_url`, `player_name`) i dołączenie go jako `music_assistant` do odpowiedzi `/config`, gdy plik istnieje. Bez pliku odpowiedź jest jak dziś. Most nadal jest jednorazowy i ograniczony czasowo (jak przy pierwszym parowaniu).

**Test:** logika scalania w czystej metodzie `static JSONObject mergeProvisioning(JSONObject current, JSONObject received, boolean replaceHa)` z testem JVM: brak sekcji MA = brak zmiany; nowa sekcja MA dodana; HA niezmienione bez `replaceHa`.

### T1.8 Odbiór 0.7 na urządzeniu

Wg SPEC 0.7 pkt 7. Dodatkowo: 10 min w Ustawieniach Androida bez `unavailable`; zapis dashboardu w HA → krótkie `unavailable` i powrót (świadomy koszt); "zamknij rolety" na encjach testowych w sypialni/salonie z Google Conversation - wynik zapisany w `artifacts/native-0.7-acceptance-<data>.md`. Wersja `0.7.0`, `versionCode 8`.

---

## Etap 2 - Helios 0.6

### T2.1 Klient Sendspin legacy

**Pliki:** nowy `SendspinClient.java`, nowy `AudioSink.java` (interfejs), nowy `ClockOffset.java`, nowy `AudioTrackSink.java`.

**Kontrakt protokołu (przypięty: aiosendspin 9.1.1, wersja 1, tekst JSON + ramki binarne):**
- `client/hello` payload: `client_id` (UUID w prefs `sendspin_client_id`), `name` z provisioning (`player_name`, domyślnie "Helios"), `version: 1`, `device_info {manufacturer:"Lenovo", product_name:"Smart Clock 2", software_version}`, `supported_roles: ["player@v1","metadata@v1","artwork@v1","controller@v1"]`, `player@v1_support {supported_formats:[{codec:"pcm", channels:2, sample_rate:48000, bit_depth:16}], buffer_capacity: 262144, supported_commands:["volume","mute"]}`, `metadata@v1_support {}`, `artwork@v1_support {channels:[{source:"album", format:"jpeg", width:320, height:320}]}`, `controller@v1_support {}`.
- `server/hello` → zapamiętaj `active_roles`; brak `player@v1` = brak lokalnego audio (UI: "MA nie aktywował odtwarzacza").
- Synchronizacja: `client/time {client_transmitted: t1µs}` co 1 s przez pierwsze 5 s, potem co 10 s; `server/time {client_transmitted, server_received, server_transmitted}` → `ClockOffset.sample(t1,t2,t3,t4)`; offset = mediana ostatnich 8 próbek z najmniejszym RTT. `ponytail:` bez filtru Kalmana i estymacji dryfu; próg do rozważenia po pomiarze underrunów (bramka 5).
- `stream/start {player:{codec, sample_rate, channels, bit_depth}}` → `AudioSink.open(format)`; `stream/clear` (z rolą `player` lub bez ról) → `AudioSink.flush()` (porzuca zbuforowane chunki, wyjście zostaje otwarte, kolejne ramki grają dalej - tak działa przewinięcie w MA); `stream/end` → **drain**: zbuforowane chunki grają do końca, potem `AudioSink.stop()` (limit 2 s od `stream/end`, potem stop bez względu na resztę); `stream/start` w trakcie drainu o tym samym formacie nie zatrzymuje wyjścia - nowe chunki dopisywane są za starymi bez przerwy (tak działa przejście między utworami w aiosendspin: `end` potem `start` z audio jeszcze w buforze); inny format → drain, `stop()`, `open()` nowego; `group/update {playback_state}` → stan sesji (`playing|paused|stopped`).
- Ramka binarna: `[0]=0x04` audio, `[1..8]` int64 BE µs serwera, reszta PCM; `0x08` okładka kanał 0 (JPEG ≤ 1 MiB, inaczej odrzucona). Bufor: kolejka po znaczniku czasu, pojemność `buffer_capacity`; wątek odtwarzania pisze chunk, gdy `localMicros(ts) - lead <= now`; chunk spóźniony o > 50 ms jest odrzucany (licznik `dropped`); stan `playing` dla UI = `group/update=playing` **i** sink zapisał dane w ostatniej sekundzie.
- `server/state {metadata, controller}` → `Listener.onMetadata`, `onController` (supported_commands, volume grupy - tylko do UI pilota, nie do sinka).
- `server/command {player:{command:"volume"|"mute", volume, mute}}` → `AudioSink.setGain(volume/100f)` / mute; potem `client/state {player:{volume, muted, output_delay_ms:0}}` jako potwierdzenie.
- Sterowanie z UI: transport (`client/command {controller:{command:"play"|"pause"|"next"|"previous"|"stop"}}`) tylko dla komend z `controller.supported_commands`. Głośność i mute z lokalnej nakładki **nie** idą przez `controller` (to poziom grupy): nakładka woła `MusicAssistantClient` z `player_id` Lenovo (`players/cmd/volume_set`, `players/cmd/volume_mute` - T2.3); serwer odpowiada `server/command player volume/mute` do sinka, co jest potwierdzeniem. Bez API MA (offline) suwak głośności nakładki jest nieaktywny, mute lokalny zostaje dostępny jako `AudioSink.setMuted` bez raportu do MA.
- Rozłączenie: `client/goodbye {reason}` przy świadomym stopie; reconnect z backoffem 2-30 s; klient nigdy nie wysyła `play` sam; audio startuje wyłącznie po `stream/start` od serwera.
- Limity: tekst ≤ 256 KiB, binarne ≤ 1 MiB, nieznane typy ignorowane bez wyjątku.

**`AudioSink`:** `open(codec, sampleRate, channels, bitDepth)`, `write(byte[] pcm, int offset, int length)`, `flush()`, `setGain(float)`, `setMuted(boolean)`, `stop()`, `long writtenMicros()`; `AudioTrackSink` (Android, `USAGE_MEDIA`, `MODE_STREAM`, bufor 500 ms) i `FakeSink` w testach.

**Testy (`SendspinClientTest`, fałszywy serwer legacy w JVM):** hello zawiera cztery role i format PCM; po `server/time` offset ≈ zadany; `stream/start` + 3 ramki w kolejności odwrotnej trafiają do sinka posortowane; ramka spóźniona odrzucona; `server/command volume 30` → gain 0.3 i `client/state` z `volume:30`; komenda `volume` z UI nigdy nie jest wysyłana jako `client/command controller`; `stream/clear` czyści kolejkę bez zamykania sinka, a kolejne ramki są odtwarzane; `stream/end` z 3 chunkami w buforze zapisuje wszystkie 3 do sinka przed `stop()`; `stream/end` + `stream/start` (ten sam format) z chunkami w buforze → sink nie dostaje `stop()`, a chunki obu strumieni trafiają w kolejności; `stream/end` + `stream/start` z innym `sample_rate` → `stop()` po drainie i `open()` nowego formatu; `client/command` z komendą spoza `supported_commands` nie jest wysyłany; okładka > 1 MiB odrzucona; rozłączenie → reconnect i brak `client/command play`.

### T2.2 Serwis odtwarzania i głos

**Pliki:** zmiany `HeliosService.java`, `MainActivity.java`, `AssistClient.java`.

- Serwis tworzy `SendspinClient` gdy prefs mają `music_assistant.sendspin_url`; stan sesji (`none|playing|paused`) i metadane wystawia przez `MusicState` do aktywności (attach/detach jak w T1.1).
- Audio focus: `AudioTrackSink` żąda `AUDIOFOCUS_GAIN`; `AssistClient` żąda `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` **raz na całą sesję** (`runVoice`, przed pierwszym `runOnce`) i zwalnia go w `finally` `runVoice` - na każdej ścieżce zakończenia: błąd przed TTS, cisza w dopowiedzeniu, `cancel()`, wyjątek; dotychczasowe żądanie/zwolnienie focusu w `play()` znika (jeden właściciel). Sink trzyma flagę `hasFocus` i reaguje: `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` → gain ×0.2; `LOSS_TRANSIENT` → pauza lokalna (bez komendy do MA); `AUDIOFOCUS_LOSS` (trwała, np. fabryczny Cast, inna aplikacja) → `hasFocus=false`, `client/command pause` jeśli `supported_commands` zawiera `pause`, inaczej pauza lokalna, i **brak** automatycznego wznowienia - dopiero użytkownik (play w nakładce/MA) powoduje nowe `requestAudioFocus`, a odtwarzanie rusza tylko przy `AUDIOFOCUS_REQUEST_GRANTED`; `AUDIOFOCUS_GAIN` → przywrócenie gainu i wznowienie tylko po `LOSS_TRANSIENT`/`CAN_DUCK`. Zabezpieczenie po `ready` z `AssistClient` (własna rozmowa zakończona lub anulowana) nie przywraca gainu wprost: sink wykonuje `requestAudioFocus` ponownie i przywraca odtwarzanie wyłącznie przy `GRANTED`; przy `FAILED` (focus ma inna aplikacja) zostaje w pauzie. Rozróżnienie: `ready` mówi tylko, że Helios skończył mówić, o dostępności głośnika decyduje wynik żądania focusu. Jeśli próba na sprzęcie wykaże nieczytelny TTS, przełączenie na `client/command pause` + `play` po `ready`, chyba że w międzyczasie `stop`/zmiana kolejki (flaga `userStopped`).
- Wake word podczas muzyki: bez zmian w `WakeWordListener`; bramka 8 mierzy detekcje i fałszywe wybudzenia (10 prób z muzyką 50%, 10 bez).

**Test:** JVM dla maszyny stanów `MusicState` (wejścia: `group/update`, `sink wrote`, `stream/end`, `focus loss/gain`, `voice ready`, `userStopped`) - tabela przejść z SPEC 0.6 pkt 5.3, w tym: `focus loss transient` → `voice ready` + `request granted` → `playing` z pełnym gainem; `focus loss transient` → `voice ready` + `request failed` → nadal `paused`; `focus loss` (trwała) → `voice ready` → nadal `paused`, bez żądania focusu, do gestu użytkownika.

### T2.3 Klient API Music Assistant

**Pliki:** nowy `MusicAssistantClient.java`.

- WS `ws://host:8095/ws`: pierwsza wiadomość `{"message_id":"1","command":"auth","args":{"token":...}}` (kształt do potwierdzenia z `/api-docs` 2.10.3 przed kodem - zadanie 0 tego tasku, zapisane w `docs/ma-api-2.10.3.md`), korelacja przez `message_id`, timeout 10 s, jedno żądanie w locie na typ (`search`, `players`, `play`, `cmd`) z wyjątkiem `stop`, które ma własny tor i jest wysyłane natychmiast niezależnie od oczekującej pauzy/głośności (SPEC 0.6 pkt 9); spóźniony wynik po zmianie gracza/zapytania odrzucany po `message_id`.
- Komendy: `players/all`; `music/search {search_query, media_types, limit:50, providers:["library"]}`; `player_queues/play_media {queue_id, media, option:"replace"}`; `players/cmd/{play,pause,next,previous,stop}`, `players/cmd/volume_set {volume_level}`, `players/cmd/volume_mute {muted}`; subskrypcja zdarzeń `player_updated` / `queue_updated` do odświeżania panelu.
- Zabezpieczenia: host z provisioning, `HttpURLConnection` dla okładek z `setInstanceFollowRedirects(false)` i sprawdzeniem hosta; token tylko w nagłówku do hosta MA; wyszukiwanie ≥ 2 i ≤ 100 znaków; metadane jako tekst.

**Testy (`MusicAssistantClientTest`):** auth i `players/all`; `music/search` z limitem 50 i `providers`; zapytanie < 2 znaków nie jest wysyłane; spóźniony wynik poprzedniego zapytania odrzucony; timeout 10 s → błąd; token nie występuje w logu `onEvent`.

### T2.4 Schemat 3 i kafelek `music`

**Pliki:** zmiany `DashboardSpec.java`, `IconView.java`, `DashboardSpecTest.java`.

- `version` 2 lub 3; typ `music` tylko przy 3, bez `entity`, z opcjonalnym `title`/`icon` (domyślnie `music`), zero albo jeden na dokument; pole `music_layout` na dowolnym poziomie → odrzucenie. Ikona `music` (nutka) w `IconView`.
- Renderer: `DashboardView.Tile.label()` dziś woła `item.entity.substring(...)` - dla `music` (`entity == null`) domyślny podpis "Muzyka"; `Tile.render()` dla `music` ustawia wartość z `MusicState` (nazwa gracza i tytuł przy zdalnym graniu, "—" bez sesji) i nie dotyka `states`; `Tile` typu `music` jest interaktywny (`item.interactive()` zwraca `true` dla akcji `library`).
- `MainActivity.tap()`: kafelek `music` jest obsługiwany **przed** bramką `!live` (biblioteka i pilot zależą od MA, nie od HA); pozostałe typy bez zmian.

**Testy:** schemat 3 z jednym `music` przechodzi; dwa `music` → błąd; `music` przy `version: 2` → błąd; `music_layout` → błąd; dokument schematu 2 nadal przechodzi bez zmian; `item("music").interactive()` jest `true`, `entity` jest `null`. Emulator (fixture w trybie `--v3`): kafelek `music` renderuje się bez wyjątku i otwiera bibliotekę przy HA offline.

### T2.5 Nakładka muzyczna i uchwyt

**Pliki:** nowy `MusicOverlay.java`; zmiany `DashboardView.java`, `MainActivity.java`.

- `MusicOverlay extends FrameLayout` dodawany do `DashboardView` nad kaflami: uchwyt `♪` 72x72 px (w skali 800x480) przy prawej krawędzi, y = środek obszaru pod paskiem (zasłania prawy skraj kolumny 4, wiersz 2 - zapisane w `ha-dashboard.md`); panel prawej połowy (x od 400) z okładką, tytułem, wykonawcą, przyciskami ◀ ▶/❚❚ ▶▶ ■, suwakiem głośności, mute i "Schowaj". Panel i uchwyt zwracają `true` z `onTouchEvent`, więc dotyk nie dociera do kafli pod spodem (także podczas animacji: animacja `translationX` na panelu, który już jest w hierarchii). Kolejność warstw: `DashboardView.setSpec()` dodaje kafle przez `addView(tile, index)` przed nakładką (indeks = liczba kafli), a po przebudowie woła `overlay.bringToFront()`; test emulatora po zapisie nowego YAML podczas otwartej nakładki sprawdza, że panel nadal zasłania kafle i przechwytuje dotyk.
- Widoczność: uchwyt gdy `MusicState != none`; panel po dotknięciu uchwytu; `paused` zachowuje bieżący stan otwarcia z etykietą "Pauza"; `none` chowa oba; otwarcie biblioteki (T2.6) ukrywa nakładkę, zamknięcie przywraca uchwyt. Bez automatycznego otwierania.
- Dialog potwierdzenia (0.5) jest `Dialog`, więc leży nad nakładką bez zmian.

**Test:** emulator z fałszywym Sendspin (rozszerzenie `.local/` fixture o tryb `--music`): zrzuty przed/po otwarciu, dotknięcie w zasłonięty kafel `light` nie wywołuje `call_service`, pozycje kafli identyczne (porównanie `getLeft/getTop` w logu diagnostycznym).

### T2.6 Ekran biblioteki i pilota

**Pliki:** nowy `MusicLibraryDialog.java`; zmiany `MainActivity.java`, `NavigationMenu.java` (bez pozycji muzyki - wejście tylko z kafelka).

- Pełnoekranowy `Dialog` z: wyborem gracza (lista z `players/all`, Lenovo jako zwykła pozycja po `player_id` przypisanym w MA do `client_id`), polem wyszukiwania (`EditText`; bramka IME z SPEC 0.6 5.2 zamyka się przed tym zadaniem - w razie braku IME własna klawiatura QWERTY jako osobne zadanie T2.6b), listą "Ostatnio odtwarzane" (prefs `music_recent`, JSON, max 10, przesuwanie na początek), wynikami (utwory, albumy, playlisty, radio; 50 na stronę), paskiem "teraz gra" wybranego gracza z ◀ ▶/❚❚ ▶▶ ■ i głośnością; Zamknij.
- Zamknięcie nie zatrzymuje muzyki; odtwarzanie zdalne nigdy nie pokazuje uchwytu.

**Test:** logika "ostatnio odtwarzane" w czystej klasie `RecentPlays` (dodaj, przesuń, limit 10, serializacja) z testem JVM; reszta w emulatorze.

### T2.7 Bramki audio na urządzeniu

Wg SPEC 0.6 pkt 12: pełny build (koszt APK/RAM/CPU względem 0.5, `dumpsys meminfo`), rejestracja `Helios` w MA, 60 s PCM przez LAN, underruny (`AudioTrack.getUnderrunCount`) i `dropped`, metadane/okładka/komendy, stop i głośność, wake word z muzyką, reconnect bez autoplay, IME, odświeżenie parowania, rotacja tokena. Wynik w `artifacts/native-0.6-acceptance-<data>.md`. Numery wydań idą za kolejnością wdrożenia: 0.7.0 (integracja HA), potem 0.8.0 (muzyka); numery spec-ów zostają.

---

## Threat model i granice hardeningu

Zaufane: HA i MA w tej samej sieci LAN, dostęp tylko z tokenami zegara; dokument panelu i telemetria pochodzą od zaufanych serwerów, walidowane strukturalnie (typy, zakresy, limity rozmiaru), nie semantycznie. Niezaufane: wartości z YAML (allowlisty jak w 0.5), argumenty komend z HA (walidacja typu i zakresu przed sprzętem), treść metadanych MA (tylko tekst), okładki (limit rozmiaru, dekodowanie z kontrolą wymiarów). Poza zakresem hardeningu: MITM w LAN bez TLS (decyzja użytkownika z SPEC 0.7), złośliwy administrator HA/MA, ochrona przed kompromitacją tokena innych API HA (spec 0.5/0.7: dedykowane konto).

## Rejestr rebutali

Pusty przed pierwszą rundą recenzji. Format wpisu: `R<n> <recenzent>: <streszczenie> - <powód odrzucenia>`.

## Changelog rund

- R7 (codex): APPROVE, bez znalezisk.
- R6 (codex): naprawiono 1 znalezisko - trwała utrata focusu (`AUDIOFOCUS_LOSS`, np. Cast) pauzuje bez automatycznego wznowienia; po `ready` sink wznawia tylko przy ponownie przyznanym focusie.
- R5 (codex): naprawiono 2 znaleziska - `reconfigure` zapisuje i restartuje sekcje HA i MA niezależnie (awaria MA nie blokuje HA, także przy pierwszym provisioningu); `attach` zawsze odtwarza najnowszy snapshot przed `onUnavailable`.
- R4 (codex): naprawiono 2 znaleziska - audio focus rozmowy trzymany raz na sesję i zwalniany w `finally` na każdej ścieżce, plus kontrola po `ready`; `stream/end` jako drain z ciągłością przy `stream/start` tego samego formatu (przejście między utworami bez przerwy).
- R3 (codex): naprawiono 1 znalezisko - `reconfigure` weryfikuje i restartuje tylko zmienione sekcje (HA / MA), więc wymiana tokena MA nie zależy od dostępności HA i nie zrywa kanału HA.
- R2 (codex): naprawiono 4 znaleziska - przydział `id` i `send` w jednej sekcji krytycznej z testem współbieżnym; `device_id` jako `Supplier` odczytywany przy każdym uruchomieniu pipeline i przerwanie dopowiedzeń po zmianie; `reconfigure` uwierzytelnia nowe dane na tymczasowym połączeniu przed zapisem; głośność/mute z nakładki przez API MA do `player_id` Lenovo, nie przez `controller` grupy.
- R1 (codex): naprawiono 13 znalezisk - jeden licznik `id` dla całej sesji WS (P1); `HeliosService.reconfigure` dla provisioning i odświeżenia; `attach` odtwarza najnowszy snapshot; cleanup subskrypcji w HA tylko dla aktualnego właściciela, `replaced`/`removed`; generacja subskrypcji dla komend sprzętowych i czyszczenie `device_id` na `ended`; ponowne parowanie na nowe konto z kodem; rozszerzenie `tools/native_bridge.py` o `music_assistant`; `stream/clear` = `flush`, `stream/end` = `stop`; osobny tor `stop` w kliencie MA; renderer kafelka `music` bez `entity` i obsługa `tap()` przed bramką `live`; kolejność warstw nakładki po `setSpec`.
