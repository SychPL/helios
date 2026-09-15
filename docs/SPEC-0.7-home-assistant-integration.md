# Helios — integracja Home Assistant, specyfikacja 0.7

Status: projekt do implementacji, 2026-09-15. Zakres zaakceptowany przez użytkownika: urządzenie w HA, wersja i diagnostyka, lampka docka, stan ładowania telefonu oraz kontekst pomieszczenia dla Assist. Szczegóły transportu poniżej są propozycjami projektowymi. Numer 0.7 oznacza dokument/etap, nie deklarację dostępnej wersji aplikacji.

Użytkownik wybiera pełną integrację Helios po stronie HA i jedną aplikację na Lenovo, nie wariant `mobile_app` z lampką przez powiadomienia/template light. Zakres obejmuje także regulację głośności urządzenia i jasności lampki. Moduły głosu, muzyki, dashboardu i docka pozostają w jednym APK; komponent HA jest instalowany na serwerze.

## 1. Cel i kolejność

Użytkownik dodaje integrację **Helios**, przypisuje zegar do sypialni i widzi jego encje. Polecenie „zamknij rolety” wypowiedziane przy tym zegarze ma dotyczyć sypialni; bez jednoznacznego obszaru nie wolno rozszerzać go automatycznie na cały dom.

Integracja może powstawać przed ukończeniem [Music Assistant](SPEC-0.6-music-assistant.md). Jej identyfikator urządzenia i kontekst głosu są niezależne od renderera dashboardu oraz playera Sendspin. Instalacja 0.5 i migracja dashboardu do schematu 2 pozostają osobnym odbiorem.

## 2. Encje i urządzenie

Jedna instancja zegara to jeden config entry integracji `helios` oraz jedno urządzenie w rejestrze HA. Tożsamością jest trwały UUID instalacji aplikacji, wygenerowany raz i zachowany przy aktualizacji APK; adres IP i nazwa nie są identyfikatorami. Reset danych aplikacji wymaga ponownego parowania.

| Obiekt | Dane / semantyka |
| --- | --- |
| DeviceInfo | producent Lenovo, model Smart Clock 2, nazwa użytkownika, `sw_version` z APK, identyfikator `("helios", installation_id)` |
| Diagnostyczny sensor wersji | faktyczna wersja aplikacji; opcjonalnie versionCode, bez symulowania dostępnej aktualizacji |
| Sensor stanu głosu | idle/listening/processing/responding/error, jako telemetria w pierwszym etapie |
| Sensor wersji docka | `padVersion` przekazana przez listener, np. wartość z wcześniejszej sondy `22.127`; nie wersja telefonu |
| Binary sensor docka | podłączony/odłączony/unknown, na podstawie listenera połączenia |
| Light lampki | on/off, jasność, `ColorMode.BRIGHTNESS`; brak RGB/temperatury barwowej |
| Number głośności urządzenia | ustawienie 0–100%, odczyt faktycznego poziomu systemowego i synchronizacja zmian lokalnych; niezależne od nastawy głośności muzyki w MA |
| Binary sensor ładowania | device class `battery_charging`; on/off/unknown, a po rozłączeniu urządzenia unavailable |
| Diagnostyka połączenia | ostatnia poprawna wiadomość, wersja protokołu i możliwości; bez audio i sekretów |

Każda encja ma stabilny unique_id oparty na UUID i stałym sufiksie. Edycja nazwy i obszaru w HA nie zmienia tych identyfikatorów. Encje należą do własnego config entry. Nie łączyć na siłę urządzenia integracji Helios z urządzeniem należącym do integracji Music Assistant; wiązanie logiczne przez identyfikator playera wystarczy.

Obszar jest własnością rejestru urządzeń HA i użytkownik wybiera go w standardowym ekranie urządzenia. Restart aplikacji ani reconnect nie nadpisują tego wyboru. Encje mogą mieć jawne nadpisanie obszaru w HA, co trzeba uwzględnić w testach głosu.

## 3. Transport i parowanie — propozycja

Mała integracja custom component w `custom_components/helios` udostępnia uwierzytelniony kanał WebSocket HA. Zegar inicjuje połączenie wychodzące do HA, dzięki czemu nie potrzebuje własnego publicznego serwera ani portu otwartego na Androidzie. To jest nowy protokół integracji, nie istniejące API HA.

Decyzja transportowa: `helios/connect` jest kolejną subskrypcją na tym samym gnieździe HA, z którego korzysta `HaDashboardClient`. Nie tworzymy drugiej pętli połączenia, uwierzytelniania, ping/pong ani backoffu dla urządzenia. Transport utrzymuje wspólny właściciel niezależnie od widoczności dashboardu; moduł urządzenia nie zarządza gniazdem. Audio Assist oraz połączenia Music Assistant pozostają poza tym współdzieleniem.

Wspólny nadawca przydziela rosnące identyfikatory `id` wszystkim żądaniom i subskrypcjom w kolejności wysyłania; `4` nie jest stałym identyfikatorem protokołu. Odpowiedzi i zdarzenia są kierowane do właściciela danego `id`. Wynik `helios/result` zawiera osobno `request_id` komendy urządzenia; nie zastępuje on identyfikatora koperty HA. Subskrypcja urządzenia startuje po uwierzytelnieniu, niezależnie od oczekiwania na snapshot dashboardu. Stan dostępności encji urządzenia nie używa flagi gotowości dashboardu `live`.

Błąd lub brak integracji `helios` nie zamyka zdrowego WS i nie blokuje dashboardu; odrzucenie subskrypcji jest błędem jej modułu. Unsubscribe lub usunięcie integracji zwalnia tylko jej kanał, nie wspólne gniazdo. Wywołania OEM wykonują się poza pętlą odbioru WS. Rozłączenie transportu kończy wszystkie jego subskrypcje i oczekujące żądania, a jedna pętla reconnect odtwarza potrzebne subskrypcje i snapshoty, nigdy zaległe akcje.

Świadomy koszt pierwszego etapu: obecny `HaDashboardClient` po `lovelace_updated` restartuje całą sesję WS. Zachowujemy tę ścieżkę 0.5; zapis dashboardu powoduje krótkie unavailable encji Heliosa do ponownego `helios/connect` i snapshotu. Nie resetuje bindera, lokalnej lampki ani głośności. Nie deklarujemy izolacji awarii modułów tylko dlatego, że korzystają z tego samego serwera. Przyszłe odświeżanie samych subskrypcji dashboardu bez reconnect nie jest warunkiem tego wydania.

1. W kreatorze integracji użytkownik rozpoczyna parowanie, HA wydaje jednorazowy kod z limitem 5 minut i limitowaniem prób.
2. Kod wpisuje się lokalnie na zegarze, który już ma skonfigurowane połączenie HA.
3. Integracja wiąże uwierzytelnionego użytkownika kanału z UUID i tworzy config entry/device/encje. Powtórne parowanie tego samego UUID aktualizuje istniejący wpis.
4. Przy reconnect HA sprawdza zapisane powiązanie; sam UUID nie autoryzuje połączenia. Nowy użytkownik lub usunięte powiązanie wymaga ponownego parowania.
5. Usunięcie wpisu odwołuje kanał i blokuje polecenia. Dane dostępowe są prywatne, poza YAML dashboardu; nie logować kodu ani tokena.

Pierwszy etap może ponownie użyć dedykowanego konta HA zegara. Token dziedziczy uprawnienia tego konta; allowlista komend Heliosa nie ogranicza dostępu tokena do pozostałych API HA. Wymagane testy dostępu non-admin do docelowego panelu i pipeline. TLS albo świadomie skonfigurowana zaufana sieć LAN; żadnego wyłączania weryfikacji certyfikatu.

### Kontrakt kanału v1

Komendy `helios/*` są projektowane do rejestracji przez integrację, nazwy i schemat wymagają testu jej implementacji.

| Kierunek | Wiadomość | Pola |
| --- | --- | --- |
| Zegar → HA | `helios/connect` (subskrypcja) | protocol=1, installation_id, app_version, version_code, capabilities; kod tylko podczas parowania |
| HA → zegar | `connected` w zdarzeniu subskrypcji | device_id rejestru HA, aktualny area_id lub null |
| Zegar → HA | `helios/state` | pełny snapshot właściwości, uptime |
| HA → zegar | `command` w zdarzeniu subskrypcji | request_id, command, argumenty |
| Zegar → HA | `helios/result` | request_id, status, kod błędu; stan jest osobną wiadomością |

Jedna aktywna subskrypcja `helios/connect` na UUID i jedno urządzenie na współdzielonym z dashboardem połączeniu WebSocket. `helios/state` i `helios/result` są dopuszczone tylko na uwierzytelnionym połączeniu będącym aktualnym właścicielem subskrypcji. Nowe autoryzowane połączenie zastępuje starą subskrypcję, nie zamyka zdalnie jej współdzielonego gniazda. Cleanup starej subskrypcji nie może oznaczyć nowej jako offline. Wiązanie z obiektem połączenia i konkretną aktywną subskrypcją jest lokalnym stanem integracji, nie dodatkowym `session_id` w protokole. Te same reguły obowiązują przy ponownej subskrypcji na nadal otwartym gnieździe: stare zadania i wyniki nie należą do nowego kanału.

Pełny snapshot na połączeniu oraz po każdej zmianie (maksymalnie 10/s). Aktualizacje wysyła jeden szeregowy nadawca; kolejność zapewnia to samo połączenie WS, bez pola `seq`. Encje stają się dostępne dopiero po pierwszym snapshotcie. `null` znaczy nieznane, nie off. Limit 16 KiB wiadomości sterowania/telemetrii; audio Assist pozostaje osobnym kanałem.

Bez aplikacyjnego heartbeat 15 s / deadline 45 s. Integracja rejestruje cleanup subskrypcji w API HA: unsubscribe, wykryte zamknięcie WS, usunięcie wpisu lub zatrzymanie integracji oznacza urządzenie unavailable i kończy oczekujące akcje błędem. Detekcję zerwanego transportu zapewnia ping/pong istniejącego WS; w HA 2026.8.3 transport używa `heartbeat=55`. Nie obiecywać natychmiastowej detekcji cichego zaniku sieci ani limitu 45 s. W odbiorze trzeba sprawdzić także połączenie bez ramki CLOSE, nie tylko poprawne rozłączenie. Sam żywy transport nie dowodzi responsywności bindera OEM; jego błąd/timeout raportujemy osobno dla funkcji sprzętowej.

| Dozwolona komenda | Argumenty | Wykonanie |
| --- | --- | --- |
| `lamp.turn_on` | brak | jawne włączenie OEM, nie toggle |
| `lamp.turn_off` | brak | jawne wyłączenie OEM |
| `lamp.set_brightness` | `level`: liczba całkowita 1–10 | ustawienie poziomu OEM |
| `audio.set_device_volume` | `percent`: liczba całkowita 0–100 | MUSIC przez `AudioManager.setStreamVolume`, flaga 0, bez systemowego paska głośności |

Bez shell, instalacji APK, dowolnego intentu, dowolnej usługi HA ani aktualizacji firmware docka. Nieznane komendy/pola oraz wartości poza zakresem są odrzucane przed wywołaniem sprzętu.

`request_id` służy wyłącznie do korelacji wyniku z oczekującą akcją w bieżącym połączeniu, nie do trwałej idempotencji. Bez cache wyników, retransmisji i odtwarzania poleceń po reconnect. Komendy są bezwzględnymi nastawami, nie toggle. Jedna oczekująca akcja na dany zasób sprzętowy (lampka albo głośność); następna nie jest kolejkowana.

Odpowiedź RPC ma timeout 10 s. Wynik nie jest obietnicą zmiany fizycznej; timeout oznacza nieznany rezultat, a nie dowód, że polecenie nie zostało wykonane. Nie ponawiać go automatycznie. HA nie wysyła do offline; Android sprawdza aktualne połączenie przed rozpoczęciem pracy i odrzuca zadania z unieważnionego połączenia. Wyniki po timeoutcie i po zastąpieniu połączenia są ignorowane; aktualny pełny snapshot może później ustalić stan. Żadne zakończenie starej akcji nie nadpisuje stanu nowego połączenia.

## 4. Lampka i dock

Adapter OEM opiera się na [nocie lampki](lamp-control.md) i przypiętej wersji firmware OTA 627. Wyniki są z wcześniejszych testów źródłowych, nie z testów niniejszej integracji.

- Rejestruj listener docka i ładowania raz na połączenie bindera; obsłuż śmierć bindera, rozłączenie serwisu i zwalnianie zasobów.
- Stan początkowy docka/ładowania pozostaje nieznany aż do odpowiedniego callbacku. Potwierdzić eksperymentalnie, czy listener docka emituje początkowe `onConnect` i jak rejestracja współistnieje z UI Casta.
- `isLedOn=false` nie jest dowodem odłączenia docka. Przy potwierdzonym braku docka lampka jest unavailable.
- Wartość HA brightness 1–255 mapuje się na `max(1, round(brightness * 10 / 255))`; zero to `turn_off`, nigdy wywołanie OEM z jasnością 0.
- Samo ustawienie jasności nie musi włączać lampki. `light.turn_on(brightness=...)` ustawia poziom, a następnie jawnie włącza lampkę. W razie częściowego błędu zgłoś rezultat i odczytaj `isLedOn`.
- `isLedOn` jest dostępnym odczytem on/off; nie ma potwierdzonego bindera odczytu jasności. Poziom jasności jest ostatnią przyjętą nastawą w bieżącej sesji, a przed pierwszą nastawą pozostaje nieznany. Nie zakładać poziomu 7 jako pomiaru.
- Listener ładowania zgłasza start/stop, bez snapshotu. Po reconnect/reboocie usuń stary stan i czekaj na nowe zdarzenie. Przy odłączeniu docka ładowanie staje się unknown.
- Nie utożsamiaj stopu ładowania ze zdjęciem telefonu: może oznaczać negocjację Qi lub inne zakończenie ładowania. Nie twórz sensora obecności osoby z tego sygnału.

Istniejący, potwierdzony interfejs nie udostępnia tożsamości telefonu. `padVersion` opisuje dock, nie smartfon. Integracja nie używa tego sygnału do rozpoznawania mówcy.

### Regulacja głośności i jasności

Podstawa sprzętowa: [raport głośności](audio-volume.md). Na docelowym zegarze potwierdzono wcześniej odczyt/zapis MUSIC w skali 0–100 i sekwencję 42 → 30 → 80 → 42. Implementacja używa zwykłego `AudioManager`, nie bindera OEM lampki. Nie wymaga roota; odbiór w samym APK Heliosa pozostaje do wykonania.

- W HA jasność reguluje standardowy suwak encji `light`, a głośność urządzenia encja `number`. Helios udostępnia te same nastawy w lokalnych ustawieniach, bez drugiej aplikacji.
- Jasność prezentowana jako procent jest kwantowana do potwierdzonych 10 poziomów docka; nie obiecywać 100 niezależnych poziomów. Ostatnia przyjęta nastawa nie jest pomiarem jasności.
- Głośność urządzenia mapuje się na dostępne stopnie Androida. Po zmianie raportuj odczytany poziom, nie tylko żądaną wartość. Zmiana fizycznymi przyciskami ma być widoczna w HA. Zero oznacza wyciszenie wyjścia audio, nie wyłączenie mikrofonu.
- MUSIC jest współdzielony również z fabrycznym Cast UI. Odczytuj i raportuj jego zmiany, bez walki o nastawę. Start/reconnect nie przestawia głośności. Dla komend HA używaj flagi 0 bez systemowego paska głośności; inne strumienie, w tym ALARM, pozostają nietknięte.
- Propozycja implementacyjna detekcji: `getStreamVolume(STREAM_MUSIC)` przy starcie, po własnej komendzie, przy wznowieniu UI i co 3 s podczas działania modułu urządzenia. Publikuj tylko zmianę odczytu; zatrzymaj polling przy zamknięciu modułu. Działa niezależnie od widoczności dashboardu, jeśli moduł nadal pracuje. Kryterium odbioru: przyciski i Cast są odzwierciedlone w następnym cyklu odczytu przy zdrowym połączeniu.
- Niepubliczny broadcast `android.media.VOLUME_CHANGED_ACTION` może jedynie przyspieszać odczyt po osobnej próbie na OTA 627; nie jest wymagany do poprawności i nie zastępuje pollingu. Brak potwierdzonego publicznego callbacku zmiany tego strumienia w docelowym Androidzie nie może prowadzić do zależności od ukrytego API bez fallbacku.
- Suwak urządzenia jest świadomie regulacją wspólnego wyjścia i może wpływać na muzykę, pik i odpowiedzi Assist. Suwak muzyki MA zmienia wyłącznie wzmocnienie jej `AudioTrack`, zgodnie z SPEC 0.6; nie może automatycznie przestawiać głośności urządzenia.
- W testach odbioru sprawdź wartości skrajne, zaokrąglenia do stopni sprzętu, aktualizacje z HA i lokalne, brak pętli zwrotnej oraz brak wykonania zaległych zmian po reconnect.

## 5. Kontekst pokoju w Assist

### Pierwszy etap: urządzenie + istniejący pipeline

Obecny `AssistClient` uruchamia `assist_pipeline/run` z pipeline i opcjonalnym conversation_id; nie przekazuje device_id. Integracja ma dostarczyć poprawny **device_id z rejestru HA** powiązany z UUID zegara. Każdy start oraz kontynuacja rozmowy przesyła to samo device_id; nie mylić go z installation_id, area_id ani entity_id.

Obszar odczytuje HA z rejestru; nie dopisujemy słowa „sypialnia” do transkrypcji. Zmiana obszaru w HA ma obowiązywać przy następnej rozmowie. Po usunięciu urządzenia wyczyść identyfikator; nie kontynuuj automatycznie starych kontekstowych poleceń.

`device_id` przekazuje kontekst, ale nie jest mechanizmem uprawnień ani filtrem wszystkich usług. Skuteczne ograniczenie zależy od konkretnego agenta rozpoznającego intencje, ekspozycji encji i ich obszarów. Obecny profil używa Google Conversation, dlatego próba z agentem lokalnym nie jest wystarczającym odbiorem.

### Reguła produktu dla niejawnego celu

Dla „zamknij rolety” bez pokoju: celem są rolety obszaru zegara. Jeśli obszaru brak, nie ma rolet albo cel jest niejednoznaczny, agent dopytuje lub zwraca błąd; nie rozszerza celu na dom. Polecenie jawnie wymieniające inny pokój lub cały dom może wskazywać ten zakres zgodnie z uprawnieniami i ekspozycją encji.

Przed uznaniem funkcji za gotową trzeba wykazać to w testach narzędzi agenta. Jeżeli Google Conversation nie respektuje tego warunku, wdrożyć po stronie HA deterministyczne rozwiązywanie celu dla tej intencji (narzędzie/skrypt otrzymujący zweryfikowany obszar źródłowy i walidujący docelowe encje). Sam tekst promptu nie stanowi gwarancji. Dopóki brak tego dowodu, funkcja jest oznaczona jako niezweryfikowana i nie jest testowana na fizycznych roletach.

### Kolejny podetap: AssistSatelliteEntity

Pełna encja `assist_satellite` jest pożądana dla standardowej obsługi głosu w HA, ale wymaga osobnego przestawienia wykonania pipeline na `async_accept_pipeline_from_satellite` oraz zgłoszenia zakończenia TTS przez `tts_response_finished`. Nie tworzyć encji, która tylko kopiuje status z zewnętrznego `assist_pipeline/run` i pozoruje zgodność.

Najpierw działający kontekst device_id i sensor telemetrii; potem natywny adapter satelity z transportem audio, cyklem idle/listening/processing/responding oraz wyborem pipeline. Obsługę ANNOUNCE/START_CONVERSATION deklarować dopiero, gdy jest rzeczywiście wdrożona. Integracja z audio Music Assistant ma wspólnego arbitra na zegarze, aby TTS i muzyka nie rywalizowały o głośnik.

## 6. Osoba mówiąca

Opcjonalny kierunek badawczy, poza pierwszą implementacją. Sprawdzony kontrakt pipeline nie dostarcza gotowej identyfikacji mówcy. Użytkownik tokena, przypisany właściciel urządzenia, obszar oraz stan ładowania nie identyfikują osoby, która właśnie mówi.

Ewentualne rozpoznawanie głosu wymaga dobrowolnego zapisania próbek osób, lokalnego przetwarzania tam, gdzie możliwe, możliwości usunięcia profilu, wyniku „nieznany” i pomiaru pomyłek. Wynik może personalizować odpowiedź; nie zwiększa uprawnień i nie autoryzuje samodzielnie otwierania drzwi czy bramy. Nie implementujemy go jako rozpoznawania płci ani przypisywania tożsamości na podstawie barwy głosu.

## 7. Moduły i odbiór

Proponowane moduły: Android wspólny transport HA wydzielony z `HaDashboardClient`, `HeliosDeviceClient` jako jego odbiorca, `DockController` i model telemetrii; HA `config_flow`, `coordinator`, `sensor`, `binary_sensor`, `light`, `number`. Adapter satelity jest kolejnym podetapem po działającym urządzeniu. Żaden moduł nie dubluje `media_player` utworzonego przez Music Assistant.

Scenariusze akceptacji:

1. Dodanie urządzenia, restart zegara i aktualizacja APK zachowują obszar, encje i unique_id. Zmiana IP nie tworzy duplikatu.
2. HA pokazuje wersję zgodną z APK. Unsubscribe i wykryte zamknięcie WS dają unavailable; powrót wymaga nowego snapshotu. Osobno sprawdzić cichy zanik sieci z działającym ping/pong transportu oraz błąd/timeout OEM przy nadal żywym WS.
3. Żądania lampki przechodzą przez allowlistę; 0 wyłącza, a wartości skrajne mapują się na 1 i 10. Potwierdzenie stanu on/off pochodzi z OEM, jasność jest opisana jako nastawa.
4. Start przy już ładującym się telefonie daje unknown do callbacku. Sekwencja start/stop/start jest obsłużona bez deklarowania obecności ani tożsamości telefonu.
5. Offline odrzuca akcje, reconnect nie wykonuje ich później. Nie ma automatycznego ponawiania po timeoutcie; wynik starej akcji i cleanup starego połączenia nie zmieniają stanu nowego.
6. Testowy zegar w sypialni oraz testowe rolety w sypialni i salonie: „zamknij rolety” wysyła akcję wyłącznie do sypialni; brak obszaru i brak pasujących rolet nie wysyłają poleceń ogólnodomowych. Weryfikacja na mockach/encjach testowych z rzeczywistym agentem, zanim użyjemy fizycznych rolet.
7. „Zamknij rolety w salonie” trafia do jawnego pokoju; dopowiedzenie w tej samej rozmowie zachowuje identyfikator źródła. Zmiana obszaru przed nową rozmową zmienia domyślny cel.
8. Odwołane parowanie, zły użytkownik oraz wiadomości z nieaktywnego połączenia są odrzucane. Diagnostyka nie zawiera sekretów ani audio.
9. Regulacja głośności z HA nie pokazuje systemowego paska; odczyt po komendzie oraz polling odzwierciedlają zmiany przyciskami i Castem bez ich nadpisywania.
10. Dashboard i urządzenie używają jednego WS: przeplatane wyniki i zdarzenia trafiają do właściwego modułu, identyfikatory nie kolidują, a wolny OEM nie blokuje odbioru. Brak integracji, odrzucenie `helios/connect` i unsubscribe nie zrywają dashboardu. Zapis Lovelace odtwarza obie subskrypcje po jednej pętli reconnect, z nowym snapshotem urządzenia i bez ponawiania akcji. Usunięcie i ponowne utworzenie subskrypcji na tym samym WS unieważnia stare zadania i wyniki.

## 8. Źródła i bramki implementacyjne

- [Assist pipeline — device_id i WebSocket](https://developers.home-assistant.io/docs/voice/pipelines/).
- [WebSocket API — identyfikatory żądań i subskrypcji](https://developers.home-assistant.io/docs/api/websocket/).
- [Assist satellite — wymagany cykl pipeline i TTS](https://developers.home-assistant.io/docs/core/entity/assist-satellite/).
- [Config flow i unikalne identyfikatory](https://developers.home-assistant.io/docs/core/integration/config_flow/).
- [Rejestr urządzeń — zmiany API](https://developers.home-assistant.io/blog/2026/08/24/device-registry-follow-up-changes/).
- [Dobre praktyki Assist: obszary i ekspozycja encji](https://www.home-assistant.io/voice_control/best_practices).
- [Lokalna dokumentacja OEM](lamp-control.md).
- [Transport WebSocket HA 2026.8.3 — ping/pong](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/websocket_api/http.py).

HA w obecnej instalacji: 2026.8.3 (odczyt przed realizacją 2026-09-15). API rejestru trzeba dopasować do tej wersji oraz testować migrację na nowszych; dokumentacja internetowa może opisywać nowszą wersję. Nie aktualizować HA jako ubocznego kroku tej pracy.
