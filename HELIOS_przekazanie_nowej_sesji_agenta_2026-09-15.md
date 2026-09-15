# HELIOS — pełne przekazanie dla nowej sesji agenta

**Przygotowano:** 15 września 2026 r.  
**Zakres:** Lenovo Smart Clock 2 → własny terminal głosowy i dashboard Home Assistant, przede wszystkim bez roota. Raporty opisują m.in. próby z 14 września 2026 r. To przekazanie nie oznacza wykonania kolejnych testów urządzenia.

## 1. Cel użytkownika i aktualny kierunek

Użytkownik ma własny Lenovo Smart Clock 2, model CD-24502F, i chce rozwijać własną aplikację roboczo nazywaną **Helios**. Docelowo urządzenie ma zapewniać ekran zegara/dashboard Home Assistant, własny nasłuch głosowy, odpowiedzi z głośnika, muzykę oraz funkcje nocnego ekranu. Sterowanie lampką jest dodatkiem zależnym od znalezienia dostępnego interfejsu.

**Ważna zmiana wymagań:** początkowo pytanie dotyczyło zastąpienia Google Assistanta. Później użytkownik wyraźnie dopuścił pozostawienie „OK Google” i chce przede wszystkim dodać własne hasło, np. **„OK Nabu”**. Usunięcie Google nie jest warunkiem rozpoczęcia projektu. Nie mamy jednak jeszcze dowodu, że oba nasłuchy działają równocześnie. [S3]

Z urządzenia ma korzystać **cała rodzina**, nie jedna osoba. W dalszej rozmowie przyjęto kierunek rozwiązania otwartego/open-source; punktem wyjścia była wypowiedź użytkownika zawierająca „open world”. Nie ustalono formalnie konkretnej wymaganej licencji. Wspólny nasłuch dla różnych domowników nie oznacza wymagania identyfikowania ich tożsamości ani personalizowania odpowiedzi. „Nabu”, „OK Nabu” i „Hey Nabu” nie są automatycznie tym samym hasłem modelu.

**Obecny kamień milowy:** zegar działający w tle odbiera rzeczywistą mowę, przekazuje użyteczne polecenie do HA, odtwarza odpowiedź i wraca do nasłuchu. Pełny dashboard, launcher i muzyka nie powinny wyprzedzać sprawdzenia tej ścieżki.

## 2. Stan projektu w jednym akapicie

**Według najnowszego raportu własna aplikacja otrzymuje rzeczywisty sygnał z mikrofonu przez `AudioRecord` ze źródłem `VOICE_COMMUNICATION`.** `MIC` i `VOICE_RECOGNITION` zwracały w wykonanych próbach wyłącznie zera. Potwierdzono krótki test w widocznej aplikacji, nie stały nasłuch. Nadal nie przetestowano nagrywania w tle, ekranu wyłączonego, interakcji z Google ani silnika wake word. Nie ma też potwierdzonego pełnego polecenia głosowego do HA. Działa istniejący agent LAN, SSH i nakładka; usługa agenta wracała po restartach. Nie jest to dowód autostartu własnego ekranu ani mikrofonu. [S2][S3]

**Nie wracaj do stwierdzenia „mikrofon w ogóle nie był sprawdzony”. To opis starszego etapu, zastąpiony późniejszym raportem. Nie przechodź też do przeciwnego uproszczenia „rodzinny asystent już działa”.**

## 3. Źródła i hierarchia dowodów

Materiały przekazane w poprzedniej sesji:

| Oznaczenie | Plik | Rola |
|---|---|---|
| S1 | `Wklejony kod markdown(1).md` | Pierwotna specyfikacja „Lenovo Smart Clock jako własny dashboard HA”. Łączy dane urządzenia z planami, szacunkami i założeniami. Nie jest raportem wdrożenia. |
| S2 | `Wklejony kod markdown(2).md` | Audyt odpowiedzi agenta: urządzenie, uprawnienia i stan faktycznych testów. Na tym etapie mikrofon nie był jeszcze testowany. |
| S3 | `Wklejony kod markdown(3).md` | Późniejszy raport testu mikrofonu oraz dalsza rozmowa o dodatkowym haśle i rodzinie. Zawiera też niepotwierdzone interpretacje i propozycje stosu. |

Stosuj rozróżnienia: **wynik opisany przez agenta**, **bezpośredni log/kod**, **hipoteza wyjaśniająca**, **propozycja implementacji**, **niesprawdzone**. Najnowszy raport aktualizuje wcześniejszy status tylko w zakresie wykonanych prób. Jego późniejsze akapity nie zamieniają niesprawdzonego współdziałania z Google w potwierdzony fakt.

W tej rozmowie udostępniono podsumowanie testu mikrofonu, ale nie pełne przywołane logi ani kod uruchomionej wersji. Nowa sesja powinna odszukać te artefakty w swoim środowisku. Ich brak w przekazaniu nie dowodzi, że nie istnieją.

## 4. Urządzenie i środowisko

| Parametr | Stan wynikający z materiałów |
|---|---|
| Urządzenie | Lenovo Smart Clock 2; odczyt modelu: `LenovoCD-24502F`. W S1 błędnie wpisano „1st gen”. |
| Nazwa projektu | Helios; nie traktuj samej nazwy jako niezależnego dowodu identyfikacji firmware. |
| Android | **10, SDK 29**, odczytane z urządzenia. Wcześniejsze „Android 8.1” było błędnym wnioskiem z `targetSdk`. |
| Platforma | `mt8167`. |
| CPU | Według audytu: ARMv7 rev 1, 2 rdzenie, NEON/VFPv4. |
| RAM | S1 podaje około 1 GB, `MemTotal` około 982 MB. |
| Kernel | `4.14.141+ SMP PREEMPT`. |
| Build | Według audytu: `1.2.2.627`, data `2022-01-05`. |
| ABI | Własne binarki ARMv7 działają; pełna lista ABI systemu nie została przytoczona. Nie zakładaj ARM64. |
| Fingerprint | Nieodczytany w audycie. |
| Ekran | Dotykowy, kolorowy, układ poziomy według specyfikacji; rzeczywista rozdzielczość nie została ustalona w audycie. |
| Pozostałe dane sprzętowe | S1 wymienia PowerVR Rogue, DDK `1.11@5425693`. Szczegóły przycisków, lampki i dostępności czujnika dla aplikacji wymagają sprawdzenia; nie przenoś opisu handlowego lub założeń na stan aplikacji. |

Źródła: S1, inwentarz sprzętowy; S2, tożsamość urządzenia.

### Dostęp i uprawnienia

| Element | Ustalenie z raportu |
|---|---|
| Aplikacja badawcza | Clock ADB Probe. |
| UID aplikacji/agenta | `u0_a58`, czyli `10058`. |
| SimpleSSHD | `u0_a59`, czyli `10059`. |
| Agent LAN | Port `8555`. Specyfikacja wymienia funkcje exec/status/screen, ale nie dokumentuje tu kompletnego kontraktu HTTP. |
| SSH | Port `2222`. |
| Wykonywanie poleceń | Zwykła aplikacja Android, `untrusted_app`; **nie użytkownik `shell`, nie root**. `exec` to polecenia przez `ProcessBuilder`/`sh -c`. |
| SELinux | Raport cytuje `u:r:untrusted_app:s0:c58,c256,c512,c768`. Ustal kontekst właściwego procesu; nie przenoś tożsamości i uprawnień między agentem a SimpleSSHD. |
| ADB | Nie działało w wykonanej próbie; raportowany skan wykazał agent i SSH, nie działające ADB. Nazwa Clock ADB Probe nie oznacza dostępnego połączenia ADB. |
| Overlay | `SYSTEM_ALERT_WINDOW` przyznane; nakładka nawigacyjna działała. |
| Accessibility | Kod `AutoTapService` istnieje. Jeden odczyt miał `autotapConnected=false`; czynne połączenie usługi nie jest potwierdzone. |
| Mikrofon | W starszym audycie zgoda nie była potwierdzona; **nowszy raport potwierdza `RECORD_AUDIO` granted**. Dotyczy testowanej aplikacji, nie automatycznie każdej nowej APK/UID. |
| `targetSdkVersion` | `27`, według `app/build.gradle` aplikacji badawczej. To nie wersja systemu i nie ustalony target przyszłej aplikacji. |
| Ograniczenia | `dumpsys`/DUMP niedostępne; `/sys/class/leds/` zwracało odmowę dostępu. `pm list packages` działało, `pm disable` nie. |

Źródło: S2; aktualizacja zgody mikrofonu: S3.

Adres IP zegara, dane uwierzytelnienia, lokalizacja repozytorium i adres/token HA **nie są podane w przekazanych raportach**. Odszukaj je w istniejącym workspace i konfiguracji. Nie wymyślaj adresów ani endpointów i nie publikuj sekretów. Nie zakładaj również, że aktualna APK ma dokładnie tę samą wersję co opisana w audycie.

### Istniejące komponenty i artefakty

`MicTester.java` istniał już przed wykonaniem testów. `BootReceiver` dodano w commit `ccf1546`; według audytu usługa agenta wracała po historycznych restartach i była dostępna na porcie 8555. Nie potwierdzono powrotu własnej Activity, roli HOME ani nagrywania. [S2]

Do odszukania:

- `artifacts/mic-test-20260914/results.md` — dokładna ścieżka przywołana w raporcie mikrofonu;
- `speech-test.md` — przywołany z nazwy, bez jednoznacznej pełnej ścieżki;
- uruchomiona wersja `MicTester.java`, manifest i konfiguracja builda;
- `.tmp_clock_screen.jpg` — wcześniejszy screenshot przywoływany w raportach, nie załączony tutaj jako osobny obraz.

Nie myl nazw artefaktów z plikami faktycznie dołączonymi do tej sesji. Pierwszy krok to ich odszukanie, nie deklarowanie, że je przeczytano.

## 5. Najnowszy wynik mikrofonu

Poniższe dane pochodzą z S3 — są **wynikiem raportowanym przez agenta**, nie niezależnym pomiarem wykonanym w tej rozmowie.

| Źródło audio | Wynik | Dane z raportu |
|---|---|---|
| `MIC` (1) | Same zera | 10 s; RMS `0.0`; `0%` próbek niezerowych. |
| `VOICE_RECOGNITION` (6) | Same zera | 10 s; RMS `0.0`; `0%` próbek niezerowych. |
| **`VOICE_COMMUNICATION` (7)** | **Rzeczywisty sygnał reagujący na mowę** | 15 s; RMS `7.8–35.1`; peak `49–158`; `92–98%` próbek niezerowych; około `4,5×` różnicy cisza/mowa. |

Raport potwierdza zgodę `RECORD_AUDIO`, poprawną inicjalizację i rozpoczęcie `AudioRecord` oraz odczyt 16 000 próbek/s. W dalszej części oznacza 16 kHz jako sprawdzone. **Mono i PCM16 były konfiguracją zadaną do testu; pełną konfigurację rzeczywiście uruchomionej wersji trzeba potwierdzić kodem/logiem.**

Agent podaje poziom około **−64 do −59 dBFS**. Bez źródłowych logów i metodologii nie zweryfikowano tutaj przeliczenia. Niski raportowany poziom nie rozstrzyga sam w sobie o skuteczności STT lub wake word. Nie przeprowadzono testu rozpoznawania konkretnego zdania.

**Uzasadniony wniosek:** można kontynuować prototyp bez roota, korzystając z działającej konfiguracji `VOICE_COMMUNICATION`. Nie ma podstaw, by na tym etapie uznać root za konieczny.

**Nieudowodnione wyjaśnienie:** agent przypisał wynik odrębnej ścieżce HAL i wyłącznemu zajęciu głównej ścieżki przez Cast. Nie przedstawiono dowodu tego mechanizmu. Działający proces Cast i rzeczywisty sygnał naszej aplikacji nie dowodzą równoczesnego odbioru mowy przez oba asystenty.

## 6. Co pozostaje niesprawdzone

| Obszar | Status na końcu poprzedniej sesji |
|---|---|
| Nagrywanie przez usługę pierwszoplanową przy fabrycznym zegarze na ekranie | Niesprawdzone. |
| Nasłuch przy przyciemnionym ekranie i przy rzeczywiście wyłączonym ekranie | Niesprawdzone; to dwa osobne scenariusze. |
| Reakcja Google przed, podczas i po naszym nagrywaniu | Niesprawdzone. |
| Wyciszenie naszego klienta i automatyczny powrót sygnału po Google | Niesprawdzone. |
| Rozpoznanie zdania z bliska i z około 2 metrów | Niesprawdzone. |
| Wake word na zegarze | Żaden silnik nie został uruchomiony według ostatnich materiałów. |
| Cała ścieżka mikrofon → STT → HA → akcja → TTS → głośnik | Brak potwierdzonego testu. |
| Rola HOME/ASSISTANT i zmiana domyślnej aplikacji | Niesprawdzone. |
| Powrót własnego UI i mikrofonu po restarcie | Niesprawdzone; potwierdzono tylko usługę agenta. |
| Stabilność wielogodzinna/całodobowa | Brak dowodu. Krótka próba mikrofonu nie jest testem ciągłego nasłuchu. |
| Music Assistant, własna autojasność, lampka | Brak potwierdzonych testów funkcjonalnych. |

Źródła: S2 i S3. Starsze „zero testów mikrofonu” nie dotyczy stanu po S3; pozostałe niewiadome nie zostały w S3 rozstrzygnięte.

## 7. Korekty z wcześniejszego przeglądu — nie powielać założeń

Poniższe uwagi pochodzą z przeglądu technicznego w poprzedniej rozmowie. **Nie są dodatkowymi pomiarami zegara.** Przy implementacji sprawdzaj dokumentację faktycznie używanego SDK, biblioteki i modelu.

**Mikrofon i Google.** Nie opieraj projektu na twierdzeniu „Android 10 pozwala nagrywać tylko jednej aplikacji, więc Google automatycznie straci mikrofon”. W przeglądzie wskazano odrębne reguły dla uprzywilejowanego asystenta. `requestAudioFocus()` dotyczy odtwarzania, nie przyznawania wyłączności mikrofonu. `VOICE_COMMUNICATION` ma szczególne reguły prywatności, więc jego działanie może zmieniać dostęp asystenta do audio. Faktyczne zachowanie Lenovo trzeba zmierzyć. Brak reakcji Google nie jest dowodem, że nie odbiera ono dźwięku.

**HOME, asystent i autostart.** Deklaracja `CATEGORY_HOME`, uzyskanie roli HOME, rola ASSISTANT, odbiór `BOOT_COMPLETED`, start usługi, pojawienie się Activity i działanie mikrofonu po restarcie są osobnymi warunkami. `BOOT_COMPLETED` nie jest ogólną gwarancją możliwości wyświetlenia Activity z tła. Dostępność i przyznanie ról trzeba sprawdzić, nie zakładać.

**API HA.** Przykład `POST /api/assist/cloud` z S1 nie został zaakceptowany w przeglądzie jako poprawny standardowy endpoint. Dla gotowego tekstu wskazano `POST /api/conversation/process`; dla audio — uwierzytelniony WebSocket `/api/websocket` i `assist_pipeline/run`. Komunikat startowy nie przesyła jeszcze nagrania: potrzebna jest obsługa zdarzeń, binarnych fragmentów audio, `stt_binary_handler_id` i odpowiedzi TTS. Nic z tego nie jest jeszcze potwierdzone na zegarze.

**Długość wypowiedzi.** S3 proponował sztywne nagrywanie 3–5 sekund. W ostatnim przeglądzie zalecono zamiast tego strumieniowanie z wykrywaniem końca wypowiedzi i maksymalnym limitem bezpieczeństwa. Nie utrwalaj krótkiego okna jako architektury docelowej.

**Wybór silnika i licencje.** Twierdzenie „Porcupine odpada, ponieważ używa go cała rodzina” nie zostało uzasadnione warunkami licencji. Nie wykorzystuj go jako rozstrzygnięcia. Oddziel licencję kodu, modelu i ewentualnej usługi. W przeglądzie odnotowano, że Apache 2.0 kodu openWakeWord nie oznacza identycznej licencji dołączonych modeli; wskazano dla nich CC BY-NC-SA 4.0. Warunki konkretnego wybranego artefaktu należy sprawdzić.

**Vosk.** Gramatyka `["nabu", "ok nabu"]` nie jest dowodem, że model zna słowo „nabu”. W przeglądzie wskazano pomijanie słów nieobecnych w słowniku i ostrzeżenie `Ignoring word missing in vocabulary`. Konieczny jest rzeczywisty test wybranego modelu. Pełne ASR nie jest tym samym komponentem co wyspecjalizowany detektor hasła.

**Poziom sygnału.** Deklarowany próg „Vosk działa do −60 dBFS” nie miał dowodu dla wybranego modelu. Wzmocnienie programowe podnosi również szum. Kod z S3 ograniczał tylko dodatnią granicę; przed konwersją do `short` trzeba uwzględnić obie granice, −32768 i 32767. Nie dodawaj w ciemno gainu lub bramki szumów: najpierw wariant bazowy i porównanie skuteczności.

**Pozostały sprzęt.** W przeglądzie skorygowano dostępność publicznego `LightsManager` do API 31, nie 29. `targetSdk 27` nie usuwa wszystkich ograniczeń hidden API. Działająca fabryczna autojasność nie potwierdza odczytów `TYPE_LIGHT` we własnej aplikacji. `screenBrightness` własnego okna i globalne ustawienie 0–255 to różne mechanizmy; dla parametru okna wskazano zakres 0.0–1.0.

**Szacunki i obietnice.** Tabele CPU/RAM/rozmiarów modeli oraz terminy „7–10 dni” czy „8–10 dni” w raportach to nie benchmarki ani zobowiązania. Nie przedstawiaj ich jako wyników urządzenia. Architektura bez modułu rozpoznawania mówcy nie uzasadnia obietnicy rozróżniania domowników.

## 8. Robocza architektura, bez zatwierdzonego silnika

**Zegar:** usługa pierwszoplanowa → własny `AudioRecord` z działającym źródłem → lokalne wykrywanie hasła → przesyłanie wypowiedzi do HA → odtworzenie odpowiedzi → powrót do lokalnego nasłuchu.

**Home Assistant:** rozpoznanie mowy → obsługa polecenia → wykonanie akcji → wygenerowanie odpowiedzi głosowej. Whisper, Piper i opcjonalny LLM pojawiły się jako propozycje. Ich faktyczna instalacja, konfiguracja i działanie u użytkownika nie zostały podane. [S3]

Preferowany kierunek transportu z poprzedniego przeglądu to Assist przez WebSocket. Wyoming pojawił się jako alternatywa projektowa, nie wdrożony drugi transport. Nie implementuj dwóch ścieżek naraz przed uruchomieniem jednej.

### Kolejność oceny wake word

**Ostatnia rekomendacja z rozmowy: najpierw sprawdzić microWakeWord.** W przeglądzie wskazano jego użycie przez Androidową aplikację HA. To argument za eksperymentem, nie dowód zgodności z tym zegarem. Sprawdź bibliotekę wykonawczą dla ARMv7/Androida 10, model, rzeczywiste hasło, licencje oraz możliwość podawania własnych próbek z `VOICE_COMMUNICATION`.

**microWakeWord i openWakeWord są różnymi projektami.** Vosk pozostaje alternatywą po weryfikacji słownika, obciążenia i skuteczności. Porcupine był wcześniejszym kandydatem; nie jest obowiązkowy i nie został rzetelnie wykluczony samym argumentem o rodzinie.

Gotowa aplikacja Home Assistant Companion była również rozważana jako możliwa próba. Nie potwierdzono jej działania, wyboru asystenta ani zgodności jej domyślnej ścieżki mikrofonu z potrzebnym na zegarze źródłem. Jej instalacja nie zastępuje walidacji własnego przechwytywania.

## 9. Kolejność pracy dla nowej sesji

### Etap 0 — przejmij istniejący stan

Odszukaj repozytorium, działającą konfigurację dostępu, APK, commit oraz raporty `results.md` i `speech-test.md`. Sprawdź kod uruchomionego testera i kontekst jego uprawnień. Zachowaj działającą wersję agenta i możliwość powrotu do niej.

Nie buduj ponownie całej infrastruktury. Nie powtarzaj wszystkich prób źródeł audio tylko dlatego, że zaczęła się nowa sesja. Gdy brak artefaktu, zapisz „nie znaleziono w tym środowisku”, nie „test na pewno nie został wykonany”.

### Etap 1 — mikrofon w tle i zachowanie Google

Użyj działającego `VOICE_COMMUNICATION`. Potwierdź kontrolowanym dźwiękiem, że sygnał nadal jest dostępny przy fabrycznym zegarze na ekranie, potem przy ekranie przyciemnionym i osobno wyłączonym. Sam niezerowy RMS nie zastępuje reakcji na oznaczony bodziec.

Wykonaj sekwencję Google: **przed nagrywaniem → podczas nagrywania → po zakończeniu nagrywania**. W trakcie zapisz także stan naszego strumienia podczas słuchania i odpowiedzi Google oraz to, czy audio wraca samo, wymaga restartu nagrywania, czy pozostaje niedostępne.

Rozróżniaj wynik obserwacji i mechanizm. Jeżeli źródło blokuje reakcję Google, opisz dokładnie warunki; nie wyciągaj automatycznie wniosku o konieczności roota lub trwałym wyłączeniu Google.

### Etap 2 — jedna zrozumiała komenda bez wake word

Uruchamiaj nagrywanie przyciskiem. Najpierw usuń detektor hasła z równania i sprawdź: wypowiedziane zdanie → rzeczywista transkrypcja → bezpieczna, uzgodniona akcja HA → odpowiedź z głośnika → powrót do gotowości.

Wykorzystaj istniejącą konfigurację HA. Przykład polecenia z rozmowy: „włącz światło w kuchni”; wybierz dostępną, właściwą encję zamiast zakładać jej nazwę. Zapisz zdanie wejściowe, transkrypcję, rezultat i opóźnienia. Powtórz próbę z bliska oraz z około 2 metrów.

Jeżeli HA nie jest dostępny, wskaż brakujący element i kontynuuj niezależne testy urządzenia. Nie deklaruj pełnego testu tylko na podstawie poprawnego połączenia WebSocket.

### Etap 3 — jeden działający detektor hasła

Zweryfikuj najpierw microWakeWord. Ustal dokładny model i to, jakie hasło rzeczywiście wykrywa. Użyj własnych próbek z potwierdzonej ścieżki, zamiast polegać na nieprzetestowanym module nagrywania gotowego SDK.

Pierwszy wynik ma być minimalny: **wypowiedziane hasło → wpis w logu lub sygnał na ekranie**. Zmierz CPU i RAM na urządzeniu. Jeżeli potrzebna jest alternatywa Vosk, najpierw sprawdź słownik i logi, potem rozpoznawanie; zaakceptowana gramatyka JSON nie zalicza testu.

### Etap 4 — różni domownicy, dystans i fałszywe wybudzenia

Zbieraj liczbę detekcji względem liczby prób dla różnych dostępnych domowników, z bliska i z około 2 metrów. Nie zapisuj jedynie „działa dla rodziny”. Dodaj zwykłą rozmowę bez hasła, telewizor lub muzykę oraz TTS z głośnika zegara. Podaj czas obserwacji i liczbę fałszywych wybudzeń.

Sprawdź, czy odpowiedź urządzenia nie uruchamia ponownie własnego detektora, oraz czy po odpowiedzi system wraca do nasłuchu. Nie zakładaj skutecznego AEC tylko na podstawie nazwy źródła audio.

### Etap 5 — dłuższa praca i odzyskiwanie

Dopiero po działającej krótkiej ścieżce sprawdzaj utratę sieci/HA, błędy nagrywania, powrót po TTS i dłuższy nieprzerwany nasłuch. Zapisz rzeczywisty czas testu, przerwy i odzyskiwanie; nie ekstrapoluj kilkunastu sekund na niezawodność 24/7.

Restart oraz zmiana HOME pozostają osobnym późniejszym testem, po uzgodnieniu i przygotowaniu drogi odzyskania dostępu. Rozstrzygnij wtedy osobno: usługę, własny ekran i działający mikrofon.

## 10. Późniejszy zakres aplikacji

Po walidacji ścieżki głosowej wróć do dashboardu: godzina/data, pogoda, kalendarz, sceny HA i mini-player. S1 dopuszczał WebView lub interfejs natywny; nie wybrano ostatecznej implementacji. Nie traktuj przewidywań o wydajności Compose/Views jako benchmarku tego projektu.

**Autojasność i screensaver:** sprawdzić rzeczywiste odczyty sensora oraz zmianę jasności własnego okna. Progi luksów i animacje z S1 są propozycją, nie skalibrowanym wynikiem. Ciemny ekran z godziną nie oznacza fizycznego wyłączenia wyświetlacza.

**Muzyka:** HTTP/Snapcast to opcje. Nie odtworzono potwierdzonego strumienia Music Assistant. `/api/music_assistant/...` jest placeholderem, nie gotowym adresem. W poprzednim przeglądzie rozważano sprawdzenie istniejącego klienta Snapcast/Snapdroid przed pisaniem własnego protokołu.

**Lampka:** jej lokalizacja, funkcje i dostępny interfejs nie są potwierdzone. Odmowa dostępu do sysfs nie dowodzi nieistnienia każdej możliwej ścieżki sterowania, ale nie daje podstaw do obiecywania tej funkcji. Nie blokuj na niej podstawowego terminala HA. [S1][S2]

## 11. Zasady prowadzenia prób

Na obecnym etapie **nie usuwaj ani nie wyłączaj pakietów systemowych, nie zmieniaj ról HOME/ASSISTANT, nie wykonuj resetu fabrycznego, prób eskalacji uprawnień ani celowych crashy/restartów**. Wątek badania kernela nie jest warunkiem tego prototypu.

Zachowaj agent/SSH i działającą APK. Nie zakładaj, że polecenia przez SSH mają uprawnienia ADB. Zmieniaj jeden parametr testowy naraz. Nie publikuj tokenów, danych logowania ani prywatnych nagrań domowników; do raportu wystarczą kontrolowane wypowiedzi, statystyki i logi.

Gdy potrzebna jest osoba przy zegarze, poproś o konkretną czynność. Nie udawaj, że wypowiedziano hasło lub wytworzono dźwięk, jeżeli tego nie potwierdzono. Wymagania już ustalone — Google może zostać, użytkowanie rodzinne, priorytet bez roota — nie wymagają ponownego pytania.

## 12. Oczekiwany raport z następnej sesji

Przedstaw wyniki wykonanych prób, nie kolejną samą specyfikację. Każda ocena powinna mieć status **POTWIERDZONE / NIE DZIAŁA W TEŚCIE / NIESPRAWDZONE**, dowód, kontekst oraz ograniczenia.

```text
Wersja APK / commit / package / UID:
Uruchomiony tester i ścieżki artefaktów:
AudioSource / sample rate / kanały / format:

1. Mikrofon w tle:
   Wynik, bodziec kontrolny, log, ograniczenia.

2. Ekran przyciemniony i osobno wyłączony:
   Wynik każdego scenariusza.

3. Google przed / podczas / po naszym nagrywaniu:
   Reakcja Google, nasz sygnał, wyciszenie, odzyskiwanie.

4. Polecenie uruchomione przyciskiem:
   Zdanie → transkrypcja → akcja → TTS → powrót do gotowości.

5. Wake word:
   Silnik, model, rzeczywiste hasło, licencje, format wejścia.
   Detekcje/liczba prób, odległość, CPU/RAM.

6. Rodzina i próby negatywne:
   Wyniki dla różnych głosów, czas obserwacji, fałszywe wybudzenia.

7. Dłuższa praca:
   Rzeczywisty czas, błędy, przerwy, odzyskiwanie.

Najbliższa niewiadoma:
Najmniejszy test, który ją rozstrzygnie:
```

Log audio powinien pozwalać powiązać znaczniki czasu, stan aplikacji, kontrolowany dźwięk i zdarzenia Google z wynikami odczytu, RMS, peak, udziałem próbek niezerowych, błędami i stanem wyciszenia klienta, o ile uda się go odczytać. Nie wystarczy wpis „startRecording OK”.

**Zadanie startowe:** odszukaj istniejące logi i kod, następnie użyj działającego `VOICE_COMMUNICATION` do sprawdzenia tła oraz reakcji Google przed/podczas/po nagrywaniu. Potem uruchom jedną komendę HA przyciskiem i pierwszy detektor hasła. Nie zaczynaj od usuwania Google, zmiany launchera ani rootowania.
