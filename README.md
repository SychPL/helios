# Helios — prototyp głosu na Lenovo Smart Clock 2

Natywna aplikacja Android jest w `app/`: stały pasek statusu, dashboard 4×3 konfigurowany w Home Assistant (zegar, pogoda, encje, światła, rolety, brama) i lokalny nasłuch Okay Nabu. Pakiet `pl.mateusz.helios`, wersja 0.8.0. Dotychczasowy Clock ADB Probe pozostaje osobną aplikacją.

## Dashboard 0.5.0

Górny pasek (HELIOS, ikona HA, jeden tekst statusu) i menu pod przytrzymaniem HELIOS są stałe i lokalne. Cała treść poniżej paska to siatka 4×3 z sekcji `helios` (`version: 2`) panelu HA `/helios-clock`: elementy `clock`, `weather`, `entity`, `light`, `cover`, `garage` z pozycją, rozmiarem, podpisem, ikoną, warunkiem widoczności liczonym w HA i opcjonalnym potwierdzeniem. Zapis YAML odświeża układ bez aktualizacji APK; błędny zapis nie rusza ostatniego poprawnego układu. Po utracie połączenia układ i widoczność zostają zamrożone, dane są przyciemnione, a sterowanie nieaktywne; nic nie jest kolejkowane. Bez żadnej poprawnej konfiguracji zegar pokazuje układ awaryjny z samym zegarem. Specyfikacja: [SPEC 0.5](docs/SPEC-0.5-ha-configurable-dashboard.md), konfiguracja: [ha-dashboard.md](docs/ha-dashboard.md), wymagania: [dashboard-product-requirements.md](docs/dashboard-product-requirements.md).

Zegar wywołuje wyłącznie `light.toggle` oraz `cover.open_cover`/`stop_cover`/`close_cover` dla encji z YAML, po jednym wywołaniu na dotknięcie, z blokadą do odpowiedzi HA (10 s). Panel rolety ma przyciski ▲ ■ ▼ i procent otwarcia; blokuje przyciski osobno, więc ■ działa zawsze przy żywym połączeniu. Zakładka `menu-zegara` z 0.4 nie jest już czytana.

Wcześniejsze testy na fizycznym zegarze z OTA 627 potwierdziły także lokalne sterowanie lampką docka oraz zdarzeniową detekcję ładowania telefonu przez fabryczny binder OEM. Nie są to elementy dashboardu ani integracja HA; szczegóły opisuje [nota techniczna lampki docka](docs/lamp-control.md). Wersja 0.4 (wskaźniki, menu z kart HA) pozostaje opisana historycznie w [SPEC 0.4](docs/SPEC-0.4-hidden-menu.md).

## Integracja HA 0.7.0

Aplikacja ma teraz foreground service (`HeliosService`), który trzyma jedno gniazdo WS do HA niezależnie od widoczności ekranu. Na tym gnieździe działa kanał urządzenia `helios/connect`: telemetria (wersja, stan głosu, dock, ładowanie, lampka, głośność) oraz allowlista komend z HA (lampka docka przez binder OEM, głośność `STREAM_MUSIC`). Komponent HA `ha/custom_components/helios` tworzy urządzenie z encjami `sensor`, `binary_sensor`, `light`, `number` i parowanie kodem. `AssistClient` przekazuje `device_id` urządzenia do pipeline, więc obszar zegara w HA daje kontekst pokoju dla poleceń głosowych. Instalacja i zachowanie: [ha-integration.md](docs/ha-integration.md), spec: [SPEC 0.7](docs/SPEC-0.7-home-assistant-integration.md). Odbiór na fizycznym zegarze i HA 2026.8.3 jeszcze nie wykonany.

## Music Assistant 0.8.0

Lenovo jest lokalnym odtwarzaczem Music Assistant przez własny klient Sendspin legacy (`SendspinClient`, PCM 48 kHz przez `AudioTrack`, synchronizacja zegara, `stream/end` jako drain) i pilotem pozostałych graczy przez API MA (`MusicAssistantClient`). Podczas lokalnego grania przy prawej krawędzi pojawia się uchwyt `♪`; dotknięcie wysuwa panel na prawej połowie (okładka, tytuł, ◀◀ ▶/❚❚ ▶▶ ■, głośność, wycisz, Schowaj), a kafle pod nim nie reagują na dotyk i nie są przebudowywane. Kafelek `music` (schemat `version: 3`, opcjonalny) otwiera bibliotekę: wybór gracza, wyszukiwanie, ostatnie 10 pozycji, wyniki i sterowanie wybranym graczem. Audio focus: rozmowa z Nabu ścisza lub pauzuje muzykę, trwałe przejęcie głośnika (np. Cast) pauzuje bez samoczynnego wznowienia. Dane MA przychodzą przez "Odśwież parowanie" z `.local/ma.json` i są weryfikowane niezależnie od HA. Spec: [SPEC 0.6](docs/SPEC-0.6-music-assistant.md), kontrakt API: [ma-api-2.10.3.md](docs/ma-api-2.10.3.md). Bramki audio na fizycznym zegarze (60 s PCM, underruny, wake word przy muzyce, IME) jeszcze nie wykonane.

Historia projektu etapu muzycznego (prototyp, decyzje): [SPEC 0.6](docs/SPEC-0.6-music-assistant.md) i [raport prototypu](artifacts/music-assistant-prototype.md).

Integrację urządzenia z HA (wersja, lampka docka, ładowanie telefonu i kontekst pokoju dla Assist) opisuje [SPEC 0.7](docs/SPEC-0.7-home-assistant-integration.md). To projekt, nie funkcja zainstalowanego APK. Rozpoznawanie osoby pozostaje osobnym badaniem.

Odbiór 2026-09-15: na fizycznym zegarze potwierdzono 0.5.0, przeniesiono panel HA do schematu 2 z kopią poprzedniej konfiguracji i sprawdzono nowy ekran. [Raport odbioru](artifacts/native-0.5-acceptance-20260915.md). [Prototyp Music Assistant](artifacts/music-assistant-prototype.md) potwierdza kompilację, ale ujawnia różnicę między biblioteką a nowym protokołem; rzeczywiste odtwarzanie wymaga sprawdzenia zgodności z lokalnym MA 2.10.3. Serwer odpowiada na porcie 8095, pierwszy odczyt wykazał niezakończone tworzenie konta.

Nasłuch „Okay Nabu” jest zawsze włączony po udzieleniu uprawnienia mikrofonu, gdy Helios jest widoczny i nie prowadzi rozmowy. Nie ma ekranowego przełącznika; do wyciszania służy fizyczny przełącznik mikrofonu zegara. Starsza zapisana preferencja wyłączenia nasłuchu jest ignorowana. Audio przed wykryciem hasła jest analizowane lokalnie, bez zapisu i wysyłania. Po wykryciu mikrofon jest zwalniany, a następnie uruchamia się ta sama rozmowa Assist co z przycisku. Jeden executor szereguje nasłuch i rozmowę. Po każdej odpowiedzi zegar słucha dalej bez hasła przez 6 s (15 s, gdy HA zgłosi `continue_conversation`) w tej samej rozmowie; cisza kończy sesję. Początek każdego nasłuchu sygnalizuje krótki pik. Nasłuch hasła wraca sekundę po zakończeniu sesji; wyjście z aplikacji zatrzymuje go. Wersja zawiera silnik ARMv7 oraz przypięty model (licencje i provenance w assets/wakeword).

Wersja 0.1.1 zgłasza Heliosa na liście aplikacji ekranu głównego (HOME). Po instalacji użytkownik może wybrać go w ustawieniach launchera. Aplikacja sama nie zmienia domyślnego ekranu głównego.

- Godzina i data korzystają z czasu/strefy urządzenia.
- Encję pogody określa YAML w HA (`weather`); pogoda jest odczytywana co 2 minuty, a przy błędzie zachowany odczyt jest oznaczony jako ostatni. Zmiana encji wywołuje nowy odczyt. `weather: null` ukrywa pogodę.
- Pierwsze uruchomienie prosi o uprawnienie mikrofonu. Rozmowę uruchamia „Okay Nabu”; mów po komunikacie „Mów teraz”. Opcjonalne pozycje ukrytego menu `Rozmowa` i `Anuluj rozmowę` pozwalają uruchomić, zakończyć wypowiedź lub anulować sesję ręcznie. Można je zmienić/usunąć w HA.
- Mikrofon 16 kHz/mono/PCM16 ze źródła VOICE_COMMUNICATION jest przesyłany bezpośrednio do Assist WebSocket w HA. Wybrany pipeline używa HA Cloud. TTS odtwarza się po zwolnieniu mikrofonu.
- Wyjście z aplikacji anuluje rozmowę i zatrzymuje nasłuch hasła.
- Nie zmienia domyślnego launchera, Google ani ustawień startu po restarcie. Utrzymuje ekran włączony, kiedy jest widoczna. Tryb nocny nie jest jeszcze zaimplementowany.

Budowanie (własny Gradle wrapper; zależności Java pobierane z Maven Central):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
./gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Lokalny `local.properties` wskazuje Android SDK. Potrzebne platform 35 i build-tools 36.0.0. Wynik: `app/build/outputs/apk/debug/app-debug.apk`.

Bez `.local/provision.json` APK buduje się bez adresu parowania. Aby skonfigurować nowe urządzenie, przygotuj `.local/ha.json` i raport `python tools/ha_preflight.py --output artifacts/ha-preflight-auth-20260915.json`, następnie wykonaj `python tools/native_bridge.py --prepare` przed budowaniem APK. Modele, biblioteka ARMv7 i fonty wraz z licencjami są w repozytorium. Skrypty eksperymentalne w `probes/` i część narzędzi agenta nadal korzystają z zasobów sąsiedniego projektu `lenovo_clock`; nie są wymagane do budowania aplikacji.

Stan 2026-09-15: użytkownik potwierdził działanie rozmowy i nowego menu na zegarze. Profil HA zapisany w Heliosie (`Home Assistant Cloud`) używa teraz `conversation.google_ai_conversation`, z wyłączonym `prefer_local_intents`; STT i TTS pozostają w HA Cloud. Test tekstowy agenta przeszedł. Lista dalszych prac: wybór profilu HA w aplikacji, pokazanie faktycznie używanego agenta, pamięć konwersacji, testy skuteczności hasła oraz tryb nocny. Surowe logi urządzenia, sekrety i lokalne parowanie nie są wersjonowane.

`native_bridge.py --prepare` tworzy losową trasę parowania w `.local/provision.json`. Przed pierwszym uruchomieniem APK uruchom `python tools/native_bridge.py`. Serwer dostarcza konfigurację przez 30 minut wyłącznie zegarowi i lokalnemu środowisku testowemu; token nie trafia do APK. Po parowaniu aplikacja zapisuje konfigurację w prywatnym SharedPreferences, z wyłączonym backupem. Serwer służy też do dostarczenia APK i odbierania logów kontrolowanego testu. Zegar komunikuje się z HA niezależnie od tego serwera.

[Raport budowania, emulatora i instalacji](artifacts/native-0.1/results.md).

## Wyniki urządzenia

- [Mikrofon w tle](artifacts/mic-background-20260915/results.md)
- [microWakeWord: kompilacja ARMv7, uruchomienie i pomiar 60 sekund](artifacts/wakeword-20260915/results.md)

## Odtworzenie eksperymentu

Wymagane lokalnie: Python, JDK, Android SDK platform 34/build-tools 34.0.0, NDK 27.1.12297006, CMake 3.31.6. Skrypty PowerShell przyjmują ścieżki SDK/JDK. Kod natywny jest pobierany z przypiętego commita Home Assistant; zależności CMake mają przypięte hashe. Pobrane źródła i binarki pozostają w `.local/`.

```powershell
python tools/prepare_microwakeword.py
./tools/build_microwakeword_native.ps1
./tools/build_wakeword_probe.ps1
python tools/run_wakeword_probe.py
python tools/run_wakeword_probe.py --live --seconds 60
```

Pierwsze uruchomienie sprawdza model na cyfrowej ciszy bez mikrofonu. `--live` uruchamia ograniczony czasowo pomiar mikrofonu. Zapisuje statystyki i detekcje, nie audio. Nie uruchamia komend ani głośnika. Biblioteka i model są sprawdzane SHA256 na urządzeniu przed użyciem. Tymczasowy serwer LAN udostępnia wyłącznie katalog payload i jest zamykany po próbie. Każda próba ma osobny katalog wyników UTC.

Agent: domyślnie `http://192.168.1.113:8555/agent`. Można ustawić `CLOCK_AGENT_BASE` i `CLOCK_AGENT_TOKEN`; przy braku tokenu runner odczytuje istniejącą lokalną konfigurację repozytorium nadrzędnego. Nie umieszczaj sekretu w parametrach polecenia.

## Połączenie z HA

Konfiguracja lokalna: `.local/ha.json`, pola `url` i `token`. Adres tej instalacji to `http://192.168.1.212`. Token można utworzyć w profilu użytkownika HA, sekcja długoterminowych tokenów dostępu: [dokumentacja HA](https://developers.home-assistant.io/docs/auth_api/#long-lived-access-token). Wpisz go bezpośrednio do lokalnego pliku; nie dodawaj go do raportów ani Gita.

Odczyt połączenia i dostępnych pipeline (wymaga pakietu Python `websocket-client`):

```powershell
python tools/ha_preflight.py
```

Bez tokenu sprawdzane są HTTP i powitanie WebSocket. Z tokenem odczytywana jest lista pipeline. Skrypt nie wysyła dźwięku i nie wywołuje akcji. Planowany transport głosu: [Assist przez WebSocket](https://developers.home-assistant.io/docs/voice/pipelines/).
