# SPEC 0.8a — poprawki odtwarzania i redesign Heliosa

Status: **projekt po recenzji, bez implementacji i wdrożenia**. 15 września 2026. Zastępuje część transportową i wizualną wcześniejszego zbiorczego SPEC 0.8. [Mapa etapów](SPEC-0.8-dashboard-appearance.md).

## 1. Zakres i wydania

Dwa niezależne przyrosty:
1. **Hotfix 0.8.5**: błędy metadanych, sesji po pauzie i pobierania/dekodowania okładki, opisane w punktach 2, 4.3 i 5. Powinien wyjść przed redesignem i nie czeka na tła. Zweryfikowana baza to APK 0.8.4, versionCode 13, commit `48eecff`; przed realizacją ponownie sprawdzić gałąź i uzgodnić ewentualne równoległe zmiany Claude Code. Nie nadpisywać innego wydania.
2. **Redesign**: paleta, większa godzina/data, ergonomiczny prawy panel muzyki i spójne dialogi, opisane w punktach 3–4.

Oba przyrosty zachowują schematy dashboardu 2/3. Bez appearance w YAML, bez schematu 4, bez zmian API HA i bez nowego połączenia WS. [Tła i ustawienia wyglądu](SPEC-0.8b-backgrounds.md) są osobnym etapem i nie blokują naprawy muzyki.

Redesign nie zmienia warunków ani geometrii dolnego rzędu czterech powiadomień 1×1; źródłem konfiguracji jest [raport dashboardu uwagi](../artifacts/attention-dashboard-20260915.md) i wskazany tam manifest. Nie kopiować listy domowych encji do kontraktu aplikacji.

Poza zakresem: nowe akcje kafelków, trwała kolejka powiadomień, miganie lampki, nowe protokoły audio, pełny renderer Lovelace i sterowanie muzyką przez Nabu (oddzielna konfiguracja HA). Jedna aplikacja Helios, bez WebView zastępującego dashboard.

## 2. Stan wyjściowy i błędy do odtworzenia

Kod odniesienia: `DashboardView.java`, `MusicOverlay.java`, `SendspinClient.java`, `HeliosService.java` w `app/src/main/java/pl/mateusz/helios/`.

- Ekran referencyjny ma 800×480, pasek 52, odstęp siatki 8 jednostek. Kafelek zegara 2×2 ma 388×272.
- Obecna godzina w takim kafelku ma rozmiar 96, a data 15. Typografia jest sztywna i słabo wykorzystuje dostępną przestrzeń.
- Panel muzyki ma 392×412, ale pionowo sumuje zdjęcie, teksty i standardowe kontrolki Androida. Własne minimalne rozmiary i padding przycisków prowadzą do obcięcia dolnej kontrolki.
- Panel ma półprzezroczyste tło: zawartość kafelków prześwituje pod sterowaniem.
- Parser metadanych zastępuje wszystkie pola przy aktualizacji, zamiast rozróżniać brak pola od jego wyczyszczenia. Aktualizacja postępu może w ten sposób usunąć tytuł i adres okładki.
- Zamknięcie strumienia audio może zgłosić brak sesji, zanim dotrze stan pauzy. Widok reaguje schowaniem panelu i uchwytu.
- `fetchArtwork` nie unieważnia oczekującego pobrania przy wyczyszczeniu okładki i może ponownie ustawić spóźniony obraz. Ponadto obraz jest dekodowany przy kolejnych odświeżeniach widoku.

Dowody wizualne, lokalne i nieprzeznaczone do commita: `.local/helios-clock-spacing-before.jpg`, `.local/helios-music-playing-design-before.jpg`. Na drugim ujęciu panel nie pokazuje okładki ani tytułu; niezależny odczyt MA w tej sesji zawierał oba. Zachowanie na protokole trzeba utrwalić w testach, nie naprawiać samym ukryciem pustego tekstu.

## 3. Język wizualny

### 3.1. Motyw

- Domyślny motyw **Ciepły grafit**: tło `#181C24`, powierzchnia `#262D38`, tekst `#F7F4EE`, tekst pomocniczy `#C1C7D0`, akcent `#EDBE83`. Zastępuje zgaszoną zieloną paletę.
- Drugi preset **Nocny błękit** (wybór per urządzenie w etapie 0.8b): tło `#151D2B`, powierzchnia `#243247`, tekst i tekst pomocniczy jak wyżej, akcent `#9CCFE0`.
- W etapie 0.8a aktywny jest domyślny Ciepły grafit, bez zdjęć. Wybór motywu i zdjęcia należy do 0.8b; drugi preset można zweryfikować testowo bez tworzenia dodatkowego menu.
- Kolor oznacza akcent lub stan, nie zalewa wszystkich kafelków. Ostrzeżenia mają ikonę i tekst, a nie wyłącznie inny kolor.
- Dotychczasowe fonty Geist i Geist Mono; bez nowych zależności fontowych. Promień narożników kart 18 jednostek. Bez ciężkich cieni, rozmycia na żywo i domyślnych szarych przycisków Androida.
- Kontrast tekstu co najmniej 4,5:1, liczony statycznie dla palety i najjaśniejszego możliwego tła po kompozycji. Bez próbkowania luminancji zdjęcia w runtime. W 0.8a wszystkie powierzchnie są jednolite.

### 3.2. Warstwy i siatka

Kolejność rysowania w 0.8a: kolor bazowy → kafelki → stały pasek → panel/uchwyt muzyki → dialogi modalne. Warstwa zdjęcia dochodzi dopiero w 0.8b.

Siatka nadal 4×3, odstęp 8 i pasek 52. Współrzędne są jednostkami ekranu 800×480, nie niezależnymi wartościami dp. Wszystkie rozmiary, tekst i obszary dotykowe skalują się jednolicie; nie mieszać tego z minimalnymi wymiarami systemowych kontrolek.

Kafle mają stałą powierzchnię motywu. Pasek statusu i panel muzyki są zawsze nieprzezroczyste. Na jednolitym tle nie rezerwujemy pustej ramki na zdjęcie. Dla późniejszych zdjęć obowiązują stałe krycia z 0.8b, nie dynamiczne testy kontrastu.

Pasek statusu: wysokość 52, tło koloru bazowego aktywnego motywu (#181C24 / #151D2B) o kryciu 100%; napis HELIOS 17 i status 16 w #C1C7D0, błąd/ostrzeżenie w #EDBE83. Zachować pozycje HELIOS (24,10,120×32), status (150,10,560×32) i ikony HA (744,10,32×32). Status jednoliniowy z wielokropkiem; priorytety błąd > rozmowa > nasłuch > teraz gra pozostają bez zmian. Ikona HA: połączenie #18BCF2, brak aktualnego połączenia #C1C7D0, z tekstowym opisem dostępności.

Ukryty kafelek pozostawia puste pole. Nie ma automatycznego pakowania siatki ani przesuwania powiadomień. Puste miejsca odsłaniają tło, lecz nie otrzymują obramowań ani zastępczych kafelków.

### 3.3. Zegar, data i pozostałe kafelki

- Dla zegara 2×2: padding 20, 120 jest sufitem rozmiaru godziny, nie oczekiwanym rozmiarem; dopasowanie do szerokości przez pomiar tekstu, bez wielokropka i obcinania cyfr. Dla pięciu znaków Geist Mono dostępna szerokość 348 zwykle wymusi rozmiar około 112; rozstrzyga pomiar fontu, nie ta estymacja. Zakres dopasowania dla tego rozmiaru 96–120; przy skrajnych metrykach fontu priorytet ma pełna godzina, nie dolna granica rozmiaru.
- Data 24, dzień tygodnia 20. Dopuszczone dwie oddzielne linie zamiast jednego długiego napisu. Data liczona lokalnie, po polsku, zgodnie ze strefą czasową urządzenia. Test obejmuje długie nazwy miesiąca i dnia tygodnia.
- Brak tytułu zegara nie tworzy pustego nagłówka. Tytuł jawnie ustawiony przez użytkownika pozostaje czytelny (18); nie kasować go globalną regułą. W dostarczonym układzie usunąć zbędne `title: Dom` dopiero przy świadomej publikacji konfiguracji.
- Pozostałe rozmiary zegara korzystają z pomiaru dostępnej szerokości i wysokości, nie skali dobranej tylko dla 2×2. Układ awaryjny nadal musi działać.
- Pogoda: temperatura 44, opis 18, jednostka widoczna. Nie przypisywać °C do danych bez jednostki. Brak jednostki wyświetla wartość bez symbolu skali. Nie dodajemy ikon warunków pogody bez rozszerzenia rejestru.
- Kafelek 1×1: nagłówek 17, wartość 24–28 zależnie od pomiaru. Dłuższa wartość może mieć dwie linie; pełny tekst w opisie dostępności. Nie wyświetlać nakładających się napisów.
- Dialogi, biblioteka muzyki, klawiatura parowania i dialog głośności/lampki używają tej samej palety, fontów i zaokrąglonych kontrolek. Nie zmienia to ich zakresu funkcjonalnego. Przyciski zamknięcia/anulowania pozostają widoczne bez systemowej nawigacji Androida.

## 4. Panel muzyki

### 4.1. Geometria i dotyk

Panel znajduje się pod paskiem: `x=400, y=60, width=392, height=412`. Nie zmienia żadnych współrzędnych dashboardu. Wewnętrzne pozycje poniżej są względem panelu:

| Element | Pozycja i rozmiar | Zasada |
| --- | --- | --- |
| Status „Teraz gra” / „Pauza” | x=16, y=16, szer. 280, wys. 48 | Tekst 16, maks. 2 linie przy komunikacie błędu |
| Schowanie, chevron w prawo | x=320, y=0, 72×72 | Zawsze widoczny, bez dolnego przycisku „Schowaj” |
| Okładka | x=16, y=76, 144×144 | Zaokrąglenie 12; stałe miejsce również bez obrazu |
| Tytuł | x=176, y=76, 200×84 | Tekst 23, maks. 3 linie, wielokropek na końcu |
| Wykonawca | x=176, y=168, 200×48 | Tekst 18, maks. 2 linie |
| Poprzedni / play–pauza / następny / stop | x=16 / 112 / 208 / 304, y=228, każde 72×72 | Okrągłe ikony; play–pauza z mocniejszym akcentem |
| Wyciszenie | x=16, y=316, 72×72 | Ikona głośnika, nie checkbox |
| Głośność MA | x=96, y=316, 280×72 | Suwak z czytelną wartością 0–100 we własnym obszarze |

Rysowana ikona może być mniejsza od pola dotykowego. Sąsiednie pola nie nachodzą na siebie. Grafiki transportu rysowane wektorowo; nie polegać na dostępności nietypowych znaków Unicode w foncie. Brak przewijania potrzebnego do dotarcia do stopu, głośności lub zamknięcia.

Uchwyt ma pole 72×72, dotyka prawej krawędzi i jest wyśrodkowany w całym ekranie w pionie (`x=728, y=204`). To świadoma zmiana względem centrowania pod paskiem; uchwyt przykrywa fragment kolumny 4, wiersza 2. Widoczny kształt może być węższy. Otwarty panel zastępuje uchwyt. Uchwyt przechwytuje dotyk tylko w swoim obszarze; otwarty panel tylko w prawej połowie. Lewa połowa dashboardu pozostaje interaktywna. Dotyk panelu nigdy nie wywołuje akcji kafelka pod nim.

Rozwinięcie trwa około 180 ms; animacja wyłącznie przesunięcia istniejącej powierzchni, bez przebudowy siatki i bez dekodowania obrazów w każdej klatce. Przy wyłączonych animacjach systemowych zmiana jest natychmiastowa.

### 4.2. Wygląd i stany

- Pełne, nieprzezroczyste tło; pogoda i teksty spod panelu nie prześwitują.
- Okładka może nadać subtelny akcent przyciskowi play i drobnemu detalowi, ale nie zastępuje wybranego tła dashboardu. Kolor liczony własną średnią kanałów RGB z bitmapy pomniejszonej do maks. 16×16, tylko po zmianie obrazu i poza wątkiem UI. Bez androidx.palette i bez włączania AndroidX (projekt ma android.useAndroidX=false). Fallback to akcent motywu; dobrać jasną lub ciemną ikonę zapewniającą kontrast, a przy braku poprawnego wyniku użyć fallbacku. Nie przeliczać koloru przy tickach postępu.
- Brak okładki: estetyczna płytka z nutką, bez pustego szarego prostokąta. Brak tytułu: „Nieznany utwór”; brak wykonawcy nie tworzy samotnego myślnika.
- Tytuł nie przesuwa kontrolek nawet przy 200 znakach. Pełny tekst jest opisem dostępności; bez ciągłego przewijania tekstu na nocnym ekranie.
- Nieobsługiwana lub chwilowo niedostępna akcja jest wyłączona, a nie usuwana. Stop pozostaje niezależny od oczekującej komendy play/pauza.
- Głośność muzyki pozostaje nastawą MA/wzmocnieniem AudioTrack, nie systemowym `STREAM_MUSIC`. Po puszczeniu suwaka wysłać jedną komendę i czekać na stan serwera; bez wysyłania komendy przy odbiorze echa. Po błędzie lub istniejącym timeoutcie 10 s wrócić do ostatniego stanu MA i pokazać błąd.

### 4.3. Reguły widoczności

| Zdarzenie | Stan panelu i sesji |
| --- | --- |
| Nowa sesja faktycznego lokalnego odtwarzania | Pojawia się uchwyt, bez automatycznego zasłaniania dashboardu |
| Dotknięcie uchwytu | Otwiera panel |
| Schowanie panelu | Zostawia uchwyt i odtwarzanie |
| Pauza otwartego panelu, także z MA | Panel pozostaje otwarty, pokazuje „Pauza” i play |
| Pauza schowanego panelu | Uchwyt pozostaje, panel się sam nie rozwija |
| Wznowienie tej samej sesji | Zachowuje wybór otwarty/schowany |
| Zmiana utworu | Zachowuje panel; metadane i obraz dotyczą nowego utworu |
| Potwierdzony stop / idle / koniec sesji lokalnej | Usuwa panel i uchwyt; unieważnia pobrania okładki |
| Utrata lokalnego połączenia Sendspin | Kończy lokalny stan UI i czyści sesję; klient nie wysyła sam komendy play przy reconnect |
| Po reconnect serwer przysyła stream/start i audio | Nowa sesja lokalnego odtwarzania: pojawia się tylko uchwyt, panel nie otwiera się sam |
| Utrata samego API MA przy działającym Sendspin | Nie usuwa lokalnego panelu; pokazuje ograniczenie sterowania |
| Gra tylko inny odtwarzacz w domu | Nie tworzy lokalnego panelu ani uchwytu |
| Sam stan paused lub same metadane bez wcześniejszej sesji lokalnego grania | Nie tworzy nowej sesji UI |
| Restart aplikacji przy spauzowanym lokalnym odtwarzaniu | Nie odtwarza uchwytu z poprzedniego procesu; wznowienie przez MA albo bibliotekę muzyki |

`stream/end` oznacza koniec transportu bieżącego audio, nie sam w sobie ostateczny stop sesji. Kolejność `stream/end` i informacji o pauzie nie może na moment emitować braku sesji. Implementację oprzeć na rozdzieleniu logicznego stanu sesji i stanu bufora, nie opóźnieniu chowania widoku o arbitralny czas. Rozłączenie, reset serwera lub jednoznaczne idle/stop kończą sesję niezależnie od metadanych.

Brak automatycznego play oznacza brak komendy inicjowanej przez klienta, nie blokadę audio zarządzonego przez serwer. MA może po reconnect kontynuować odtwarzanie, które nadal uważa za aktywne; klient przyjmuje nowy strumień zgodnie z protokołem i dotychczasową obsługą audio focus.

Świadoma decyzja dla hotfixu: nie odbudowywać sesji UI po restarcie wyłącznie na podstawie paused, nawet jeśli serwer przygotował strumień. Uchwyt wymaga nowego faktycznego lokalnego odtwarzania. Oznacza to wznowienie z biblioteki (gdy dashboard udostępnia kafelek music) albo z MA, zamiast przywracania stanu panelu zapisanego przed restartem. Nie dodawać persystencji sesji ani wyjątku opartego na samych metadanych.

## 5. Poprawność metadanych i okładki muzyki

- Odtworzyć kontrakt aktualizacji z wersji Sendspin rzeczywiście używanej przez MA: brak pola zachowuje poprzednie, jawne `null` czyści. `progress` jest pominięty (zachowaj), `null` (wyczyść) albo kompletnym nowym obiektem (zastąp); nie scalać jego zagnieżdżonych pól z poprzednim obiektem.
- Sama zmiana tytułu nie czyści pominiętej okładki lub albumu: sąsiadujące utwory mogą mieć ten sam obraz, którego serwer nie wysyła ponownie. Wyczyszczenie wymaga jawnego `null` albo resetu sesji. Reset i reconnect zaczynają od pustego stanu metadanych. Test musi odtworzyć zarówno dwa utwory ze wspólną okładką, jak nowy utwór bez okładki z właściwym komunikatem czyszczącym serwera. Nie dopisywać arbitralnego rozpoznawania nowego utworu po każdym ticku postępu.
- Pokazać ostatni poprawny tytuł podczas zmian samego postępu. Nie maskować błędu transportu stałym zastępczym tytułem z innego odtwarzacza MA.
- Pobieranie okładki powiązać z aktualną sesją i żądaniem. Po zmianie adresu, jego wyczyszczeniu, stopie lub rozłączeniu ignorować stary wynik — również gdy nowa sesja używa takiego samego URL.
- Bez powtarzania identycznego pobrania przy każdym ticku. Dekodować raz po zmianie danych, poza wątkiem UI, z kontrolą wymiarów i istniejącym limitem pobieranego pliku 1 MiB.
- Źródła okładek pozostają ograniczone do skonfigurowanego MA zgodnie z dotychczasową integracją; żadnych sekretów HA w pobieraniu okładek. Tła HA i okładki MA mają osobne mechanizmy autoryzacji i cache.
- Nie reklamować dodatkowej roli Sendspin `artwork@v1` jako „naprawy” — obecna zgodność MA opiera się na URL okładki w metadanych. Rozszerzenia protokołu są poza redesignem.

## 6. Kryteria odbioru

| Test | Wymagany wynik |
| --- | --- |
| Zegar 2×2, godzina 00:00/11:11/23:59, długa polska data | Pełne cyfry, wyraźna data, brak nadmiarowego pustego nagłówka i obcięcia |
| Zegar w innych dozwolonych rozmiarach i fallback | Mieści się w swoim kafelku, nie nachodzi na sąsiadów |
| Obie palety, wszystkie używane kolory tekstu i tła | Statyczny kontrast co najmniej 4,5:1, bez analizy obrazu w runtime |
| Zero/jedno/cztery aktywne powiadomienia | Każde we własnym dolnym polu 1×1, bez reflow i powrotu wykluczonych encji |
| Panel otwarty z okładką, bez okładki, długim tytułem | Cały transport, stop, suwak i schowanie mieszczą się na 800×480 |
| Dotyk lewego kafelka, panelu oraz krawędzi uchwytu | Lewa strona działa; żadnej akcji pod panelem/uchwytem; bez nakładających się hitboxów |
| Emulator: interaktywny kafelek w kolumnie 4, wierszu 2 przecięty uchwytem | Dotyk w uchwycie otwiera tylko muzykę (zero akcji kafelka); dotyk odsłoniętej części wykonuje dokładnie jedną akcję kafelka; sprawdzić punkty po obu stronach krawędzi hitboxu na fixture, bez sterowania fizycznym urządzeniem |
| Play → otwórz → pauza → play, komendy lokalne i z MA | Panel i metadane pozostają, ikona zmienia się poprawnie |
| Play → schowaj → pauza → play | Panel nie rozwija się sam, uchwyt pozostaje |
| Sam paused/metadata od innego lub nieaktywnego playera | Nie powstaje lokalny panel |
| Obie kolejności stream-end/pauza, koniec utworu, stop | Brak chwilowego znikania na pauzie i duchów zakończonej sesji |
| Reconnect bez strumienia oraz reconnect ze stream/start i audio od MA | Klient nie wysyła play; bez audio nie ma uchwytu, audio serwera tworzy nową sesję z samym uchwytem |
| Restart przy paused, następnie wznowienie przez MA/bibliotekę | Po restarcie brak uchwytu i samoczynnej komendy play; faktyczne wznowienie pokazuje uchwyt bez otwarcia panelu |
| Zmiana okładki, następnie wiele delt postępu | Średnia koloru liczona raz na nowy obraz, bez AndroidX i bez ponownego dekodowania na tickach |
| Pełne metadane → delta postępu → jawne null → nowy utwór | Zachowanie/wyczyszczenie właściwych pól; nigdy okładka poprzedniego utworu |
| Spóźnione pobranie okładki po stopie lub zmianie | Wynik odrzucony; brak ponownego pokazania usuniętego obrazu |
| Suwak MA, echo, odmowa i timeout | Jedna komenda po puszczeniu, bez pętli i bez zmiany globalnej głośności |
| 30 min muzyki i co najmniej 20 zmian obrazu/panelu | Brak rosnącego cache/kolejki i ANR; pomiar RAM, czasu klatek i underrunów względem wersji bazowej |

Dokumentować: wyniki JVM, build i lint, zrzuty pełnego dashboardu i muzyki w playing/paused, pomiary na fizycznym Lenovo oraz rzeczywiście nieprzetestowane scenariusze. Emulator nie zastępuje odbioru dźwięku na zegarze. Końcowe przeklikanie przez użytkownika następuje po dostarczeniu wersji, nie jest dowodem wcześniejszego wykonania testów przez autora.


## 7. Kolejność implementacji i odbioru

- Hotfix: testy RED dla pełnego snapshotu, delt absent/null/value, pełnego progress/null, wspólnej okładki dwóch utworów, obu kolejności pauzy/stream-end oraz spóźnionego pobrania po stopie i nowej sesji. Dopiero potem poprawki w SendspinClient, MusicSession i HeliosService oraz jednokrotne dekodowanie w MusicOverlay.
- Redesign: testy granic i hitboxów 800×480, dopasowania tekstu i stałego kontrastu; następnie DashboardView, MusicOverlay oraz istniejące dialogi w MainActivity/MusicLibraryDialog i klawiatura parowania. Dokładne pliki potwierdzić przed edycją, nie zmieniać logiki sterowania urządzeniami.
- Testy JVM, build, lint i zrzuty emulatora; osobno na prawdziwym Lenovo: odtwarzanie, pauza z MA i z zegara, wznowienie, schowanie, stop i zmiana utworu.
- Nie zmieniać ustawień HA podczas testów grafiki; wykorzystać fixture encji. Instalacja zachowuje parowanie. Żadnej aktualizacji APK ani restartu procesu w środku grania bez uzgodnienia okna testowego.
- Przyszły zapis wyglądu w 0.8b nie może restartować sesji muzycznej, głosu, połączenia HA ani całej aplikacji.
- Zrobić kopię poprzedniego APK przed zaakceptowanym wdrożeniem. Brak zmiany schematu pozwala cofnąć APK bez migracji dokumentu Lovelace. Końcowe przeklikanie należy do użytkownika; nie deklarować go jako wykonanego testu.
- W tej turze zmieniono wyłącznie dokumenty. Recenzja nie jest zgodą na automatyczne wdrożenie hotfixu.

## 8. Odniesienia

[Kontrakt muzyki 0.6](SPEC-0.6-music-assistant.md), [konfiguracja dashboardu 0.5](SPEC-0.5-ha-configurable-dashboard.md), [integracja urządzenia 0.7](SPEC-0.7-home-assistant-integration.md). Kontrakt delty potwierdzony także przez recenzenta w [aiosendspin metadata/state.py](https://github.com/Sendspin/aiosendspin/blob/5c024b42893bc6e372f6c87bb8504552051daf24/aiosendspin/server/roles/metadata/state.py); fixture muszą odzwierciedlać wersję używaną przez uruchomione MA.
