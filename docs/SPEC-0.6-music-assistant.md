# Specyfikacja Helios 0.6 — Music Assistant

Status: zaakceptowany kierunek następnego etapu, jeszcze niezaimplementowany. Dokument rozszerza Heliosa 0.5 o lokalne odtwarzanie oraz sterowanie innymi odtwarzaczami Music Assistant.

Decyzja użytkownika: dalszą implementację i testy audio prowadzimy bezpośrednio w aplikacji Helios, bez tworzenia ani instalowania osobnej aplikacji testowej. Najpierw lokalny odtwarzacz i jego współpraca z dashboardem oraz głosem, następnie pełny interfejs muzyczny. Dotychczasowa izolowana sonda kompilacji pozostaje materiałem badawczym. Wymagane próby z punktu 12 są bramkami odbioru kolejnych przyrostów Heliosa, nie warunkiem tworzenia osobnego APK. Muzyka jest opcjonalna; jej brak konfiguracji lub awaria nie blokuje dashboardu ani Assist.

Ustalone z użytkownikiem są obie role Lenovo, kafelek wejścia, wybór gracza/wyszukiwanie/ostatnie pozycje oraz wysuwany z prawej panel lokalnej muzyki. Uchwyt z nutką znajduje się przy prawej krawędzi, w połowie wysokości ekranu. Dotknięcie wysuwa nakładkę na prawą połowę dashboardu, bez zmiany jego układu. Ta decyzja zastępuje wcześniejszy podział i osobny układ muzyczny. Schemat YAML, liczby limitów i rozwiązania transportu to propozycje projektowe. Prototyp 2026-09-15 potwierdził kompilację, ale wykazał rozbieżność protokołu; dalszy odbiór wymaga sprawdzenia zgodności z uruchomionym MA 2.10.3 (punkt 12). Integrację urządzenia i kontekst pokoju dla głosu opisuje osobno [SPEC 0.7](SPEC-0.7-home-assistant-integration.md).

## 1. Cel

Helios ma pełnić dwie role:

1. być odtwarzaczem Music Assistant, grającym przez głośnik Lenovo;
2. być dotykowym pilotem pozostałych odtwarzaczy Music Assistant w domu.

Codzienne wejście do muzyki zapewnia kafelek dashboardu. Stałe menu Heliosa pozostaje menu technicznym i nie służy do zwykłego sterowania muzyką.

## 2. Zakres pierwszego etapu

W zakresie:

- lokalny odtwarzacz Lenovo oparty na Sendspin;
- stabilna tożsamość gracza `Helios` w Music Assistant;
- wybór dostępnego odtwarzacza;
- wyszukiwanie biblioteki MA i lista ostatnio uruchamianych pozycji;
- bieżący utwór, okładka, podstawowe sterowanie, głośność i wyciszenie;
- nakładka lokalnej muzyki wysuwana z prawej na połowę ekranu przez uchwyt z nutką;
- chowanie i ponowne rozwijanie panelu bez zmiany układu dashboardu;
- współpraca z Okay Nabu i Assist;
- jednoznaczne zachowanie przy utracie połączenia.

Poza zakresem:

- edycja kolejki i playlist;
- tworzenie grup odtwarzaczy;
- teksty piosenek, wizualizer i DSP;
- odtwarzanie offline;
- dowolne komendy API lub adresy strumieni wpisywane w YAML;
- kolejka poleceń offline.

## 3. Architektura integracji

### 3.1. Lokalny odtwarzacz

Lenovo łączy się bezpośrednio z providerem Sendspin w Music Assistant. Klient Heliosa deklaruje role:

- `player` — odbiór i odtwarzanie audio;
- `metadata` — tytuł, wykonawca, album i postęp;
- `artwork` — okładka albumu;
- `controller` — poprzedni, play/pauza, następny, stop, głośność i wyciszenie.

**Decyzja projektowa po recenzji: własny klient Java protokołu Sendspin legacy, na istniejącym Java-WebSocket 1.6.0, bez dołączania `sendspin-jvm` do APK.** Uzasadnienie: zachowanie obecnego stosu Java i uniknięcie dodatkowego runtime Kotlin/coroutines/Moshi/OkHttp oraz konfliktu wersji WebSocket na ARMv7. Nie jest to dowód mniejszego zużycia RAM ani poprawności audio — wymagane pomiary i testy pozostają bramką odbioru. Klient korzysta z natywnego `AudioTrack`, audio focus i lifecycle Heliosa. Rozważone warianty:

| Wariant | Koszt i warunek wyboru |
| --- | --- |
| `sendspin-jvm` | Gotowe mechanizmy protokołu, ale Java 17 w zależności, Kotlin/coroutines/Moshi/OkHttp i dodatkowe DEX. Zmierzyć wzrost APK, RAM i CPU na ARMv7; sprawdzić rozstrzygnięcie Java-WebSocket 1.5.7 biblioteki kontra 1.6.0 aplikacji w pełnym APK i testach, nie tylko w izolowanej sondzie. |
| Własny klient Java legacy na istniejącym Java-WebSocket 1.6.0 | Mniej zależności, lecz własna odpowiedzialność za walidację ramek, bufor PCM, synchronizację zegarów, przerwania i reconnect. Nie zakładać z góry, że kompletna obsługa zmieści się w 300–400 liniach. |

Implementację i pomiary wybranego klienta wykonujemy w Heliosie, bez osobnej aplikacji. Porównanie obejmuje APK, PSS/heap, CPU, underruny i działanie głosu względem 0.5. Wynik samej kompilacji nie zamyka tej bramki. Własny klient wymaga testów walidacji ramek, synchronizacji zegarów, ograniczonego bufora PCM, przerwań i reconnect; nie zastępujemy tych mechanizmów samym odtwarzaniem odebranych bajtów. Obsługujemy wyłącznie przypięty kontrakt i potrzebne role, bez równoległego rozwijania drugiego adaptera. Legacy wymaga późniejszej migracji, jeśli serwer przestanie dopuszczać ten protokół.

Kontrakt wersji bazowej: MA 2.10.3 z `aiosendspin[server]==9.1.1`, przejściowa obsługa legacy. Nie włączać jej automatycznie, jeśli użytkownik ją wyłączył, i nie deklarować obsługi Noise/parowania przez klienta legacy. Przypięte rewizje, licencje i dotychczasowe wyniki zawiera [raport prototypu](../artifacts/music-assistant-prototype.md).

### 3.2. Pilot innych odtwarzaczy

Wyszukiwanie, lista graczy i uruchamianie pozycji używają bezpośredniego API Music Assistant. Integracja HA może nadal wystawiać graczy jako `media_player`, ale sama nie daje kompletnego interfejsu biblioteki potrzebnego Heliosowi.

Klient MA odpowiada za:

- listę graczy i ich możliwości;
- stan gracza i bieżącej kolejki;
- wyszukiwanie biblioteki;
- uruchomienie wyniku na wybranym graczu;
- podstawowe komendy odtwarzania i głośności.

Kontrakt API: MA 2.10.3, schemat 65; nazwy i argumenty poleceń z `/api-docs` tej wersji. API WebSocket: `ws://host:8095/ws`, pierwsza komenda `auth`, korelacja przez `message_id`, automatyczne zdarzenia player/queue. HTTP RPC: `POST /api` z Bearer. Nie stosować tokena HA jako tokena MA. Host i port są konfigurowalne.

Lista graczy: `players/all`. Wyszukiwanie biblioteki: `music/search` z `search_query`, `providers=["library"]`, `media_types` i `limit`; nie używać przestarzałego `library_only`. Sendspin jest osobnym kanałem legacy `ws://host:8927/sendspin`, nie portem API ani streamservera. Obserwacje wdrożenia i przebieg prób pozostają wyłącznie w [raporcie prototypu](../artifacts/music-assistant-prototype.md).

### 3.3. Dane dostępowe

Adres i token MA nie mogą znajdować się w YAML dashboardu ani APK. Trzeba rozszerzyć provisioning o MA oraz ścieżkę aktualizacji już sparowanego zegara; obecne pobranie tylko przy `config == null` nie wystarcza.

Stałe menu techniczne otrzymuje pozycję **Odśwież parowanie**. Po świadomym uruchomieniu przez użytkownika pobiera ona jednorazowo konfigurację z ponownie uruchomionego, ograniczonego czasowo mostu parowania. Most dołącza `music_assistant` z prywatnego pliku MA; nie wybiera przy okazji innego pipeline HA. Nie pobierać konfiguracji automatycznie przy każdym starcie.

Most musi ponownie udostępnić trasę parowania znaną zainstalowanej aplikacji, zachowaną w prywatnej konfiguracji mostu. Nie generować nowego URL przy samym odświeżeniu tokena; zmiana adresu wymaga osobnej aktualizacji konfiguracji adresu w aplikacji lub APK. Pozycja menu działa także przy istniejącym `config`, a nie wyłącznie w ścieżce pierwszego uruchomienia.

Odświeżenie zachowuje dotychczasowe HA, pipeline, identyfikatory instalacji/playera i dashboard, chyba że użytkownik osobno zatwierdzi ich zmianę. Brak sekcji MA oznacza brak zmiany, nie usunięcie istniejącego połączenia. Po walidacji pól i uwierzytelnienia do docelowego MA nowa konfiguracja jest atomowo zapisywana w prywatnych preferencjach z wyłączonym backupem. Błąd/wygaśnięcie mostu zachowuje poprzednią konfigurację. Przełączenie klienta muzyki anuluje jego stare żądania i nie wznawia samodzielnie muzyki; nie resetuje danych aplikacji ani HA. Odbiór obejmuje dodanie MA do już sparowanej 0.5, rotację tokena, niepoprawny token i niedostępny most.

Proponowana struktura prywatna:

```json
{
  "music_assistant": {
    "url": "http://music-assistant-host:8095",
    "token": "<sekret>",
    "sendspin_url": "ws://music-assistant-host:8927/sendspin",
    "player_name": "Helios"
  }
}
```

Port i ścieżkę Sendspin trzeba potwierdzić na lokalnej instalacji. Aktualna dokumentacja serwera wskazuje bezpośrednie połączenie LAN na porcie 8927, a aplikacje mobilne dopuszczają konfigurowalny endpoint.

Token API MA nie zastępuje uwierzytelniania Sendspin. Przy protokole wymagającym parowania klucze tożsamości i PSK przechowuje się prywatnie zgodnie z jego kontraktem; nie wysyłać tokena MA w wymyślonym nagłówku Sendspin. Przykład konfiguracji nie jest kompletnym kontraktem parowania.

## 4. Rozszerzenie dashboardu

Etap dodaje `helios.version: 3`. Zachowuje znaczenie siatki i typów wersji 2 oraz dodaje opcjonalny typ `music`. Nakładka jest częścią interfejsu Heliosa, nie osobnym układem YAML.

### 4.1. Kafelek `music`

```yaml
- id: music
  type: music
  column: 3
  row: 3
  width: 2
  height: 1
  title: Muzyka
  icon: music
```

Kafelek zawsze otwiera ekran biblioteki i pilota MA, także podczas grania na Lenovo. Podczas grania na zdalnym graczu może pokazać jego nazwę i tytuł. Lokalną nakładkę rozwija osobny uchwyt z nutką. Kafelek nie przyjmuje dowolnej akcji ani adresu API.

W schemacie 3 dopuszczamy zero albo jeden kafelek `music`; drugi jest błędem walidacji całego dokumentu. Brak kafelka nie wyłącza lokalnego uchwytu ani nakładki. Wymóg dokładnie jednego kafelka nie dotyczy już nowego projektu nakładki niezależnej od YAML.

| Typ | Ikona domyślna | Wymaganie implementacji |
| --- | --- | --- |
| `music` | `music` | Dodać `music` do rejestru walidatora i obsługi `IconView`; nie jest dowolną nazwą MDI. |

### 4.2. Niezmienny dashboard pod nakładką

Dashboard zachowuje jedną siatkę 4×3 i te same pozycje oraz rozmiary kafelków. Panel muzyczny przykrywa prawą połowę obszaru pod stałym górnym paskiem. Nie ściska, nie przesuwa i nie przebudowuje dashboardu — także tymczasowo. Po schowaniu odsłania ten sam układ z aktualnymi stanami encji.

Nie wprowadzamy `music_layout`; pole jest niedozwolone. Nie ma drugiego zestawu geometrii, subskrypcji ani snapshotu na potrzeby muzyki. Edytor dashboardu projektuje jeden układ; ewentualny podgląd otwartej nakładki nie zapisuje zmian geometrii.

Nakładka i uchwyt obsługują dotyk przed dashboardem: dotknięcie zasłoniętej części kafelka nie wywołuje jego akcji. Odsłonięta lewa część pozostaje interaktywna. Zasada obowiązuje również dla kafelka przecinającego granicę połowy ekranu i podczas animacji panelu. Otwieranie i chowanie nie zmieniają konfiguracji ani subskrypcji encji.

Nowa aplikacja nadal przyjmuje schemat 2. Przy skonfigurowanym lokalnym odtwarzaniu uchwyt i nakładka działają również bez kafelka `music`, niezależnie od wersji dashboardu. Schemat 3 pozwala dodać kafelek biblioteki i pilota, ale nie wymaga go. Schemat 2 nadal nie dopuszcza typu `music`. Błąd dokumentu zachowuje poprzedni poprawny układ; poprawny dokument wchodzi razem z pierwszym snapshotem swoich encji, zgodnie z 0.5. Schemat 3 publikuje się dopiero po instalacji obsługującej go aplikacji.

## 5. Stany interfejsu

### 5.1. Pełny dashboard

Dashboard 4×3 jest stałą warstwą bazową. Rozpoczęcie lokalnego odtwarzania pokazuje tylko uchwyt z nutką, bez automatycznego otwierania panelu. Bez lokalnej sesji muzycznej nie ma ani uchwytu, ani nakładki.

### 5.2. Ekran biblioteki i pilota

Kafelek `Muzyka` otwiera panel z widocznym zamknięciem. Panel zawiera:

1. wybór odtwarzacza, z Lenovo jako zwykłą pozycją;
2. pole wyszukiwania;
3. ostatnio uruchamiane pozycje;
4. wyniki wyszukiwania;
5. bieżący tytuł, wykonawcę i podstawowe sterowanie wybranego gracza;
6. głośność i wyciszenie, jeśli gracz je obsługuje.

Wyniki obejmują utwory, albumy, playlisty i radio. Utwór gra bezpośrednio. Album lub playlista zastępują kolejkę wybranego gracza i rozpoczynają od pierwszej pozycji. Nie ma edycji kolejki.

`Ostatnio odtwarzane` to lokalna historia najwyżej 10 pozycji uruchomionych z Heliosa. Nie udaje historii całego MA. Powtórne uruchomienie przesuwa wpis na początek.

**Bramka UI: klawiatura ekranowa na OTA 627 pozostaje niesprawdzona.** Przed projektowaniem pola sprawdzić dostępny/włączony IME oraz faktyczne wpisywanie, zatwierdzanie, usuwanie i zamykanie klawiatury na ekranie 800×480 w Heliosie. Sam odczyt listy IME nie potwierdza używalności. Brak działającego IME wymaga osobnej decyzji o klawiaturze w aplikacji lub zakresie wydania; nie usuwa automatycznie uzgodnionego wyszukiwania. Assist może uzupełniać wyszukiwanie, ale nie jest jego przyjętym zamiennikiem.

### 5.3. Uchwyt i wysuwany panel lokalny

Uchwyt z nutką `♪` znajduje się przy prawej krawędzi dashboardu, w połowie wysokości ekranu. Dotknięcie wysuwa od prawej panel o szerokości połowy ekranu, pod stałym górnym paskiem. Panel zawiera okładkę, tytuł, wykonawcę, poprzedni, play/pauzę, następny, stop, głośność, mute i widoczny przycisk schowania. Dotyk jest wystarczający; przeciąganie nie jest wymagane. Uchwyt ma pole dotyku co najmniej 72×72 px na docelowym ekranie 800×480.

Kontrolka jest aktywna tylko wtedy, gdy odpowiedni transport deklaruje komendę jako obsługiwaną; obowiązują również reguły offline z punktu 9.

`playing` oznacza faktyczny stan lokalnego odtwarzacza, nie samo połączenie. W prototypie `sendspin-jvm` przechodzi do `STREAMING` już przy metadanych bez audio: tego enum nie wolno używać jako wyzwalacza uchwytu. Adapter musi powiązać rozpoczęcie sesji UI z aktywnym lokalnym strumieniem i sinkiem audio. Samo odtwarzanie na innym graczu nie pokazuje uchwytu.

Proponowana reguła pauzy: rozpoczęta lokalna sesja zachowuje uchwyt i bieżący stan otwarcia panelu podczas `paused`, z oznaczeniem pauzy i możliwością wznowienia. Pauza nie jest zakończeniem sesji. Wznowienie, zmiana utworu ani chwilowe wyciszenie na potrzeby Assist nie otwierają schowanego panelu. `stopped`, `idle`, wyłączenie lokalnego odtwarzacza lub utrata Sendspin kończą sesję UI i chowają panel oraz uchwyt. Następna sesja zaczyna się ponownie od samego uchwytu.

Ekran biblioteki i pilota ma pierwszeństwo: podczas jego wyświetlania lokalny uchwyt i nakładka są ukryte. Po zamknięciu biblioteki wraca dashboard z samym uchwytem, jeśli lokalna sesja nadal trwa. Muzyka nie przerywa interakcji z biblioteką ani dialogiem potwierdzenia dashboardu; dialog pozostaje nad nakładką i przechwytuje dotyk.

### 5.4. Schowanie panelu

Przycisk schowania lub systemowe Wstecz zamyka nakładkę bez pauzy ani zatrzymania muzyki. Dashboard zostaje odsłonięty, a uchwyt wraca do prawej krawędzi. Kolejne dotknięcie uchwytu ponownie otwiera panel. Zdarzenie zamknięcia nie przechodzi do kafelka pod nim.

Nie zapisujemy otwarcia panelu w YAML ani nie odtwarzamy go automatycznie po restarcie. Nie ma automatycznego rozwinięcia po rozpoczęciu lub wznowieniu odtwarzania.

Górny pasek zachowuje priorytety 0.5: błąd > rozmowa > nasłuch > teraz gra. Uchwyt jest niezależny od tekstu statusu, nie jest pozycją menu developerskiego i zastępuje wcześniejszy pomysł kontrolki rozwinięcia w pasku.

### 5.5. Inny odtwarzacz

Odtwarzanie wyłącznie na zdalnym graczu nigdy nie pokazuje lokalnego uchwytu ani nakładki. Sterowanie pozostaje w ekranie kafelka `Muzyka`; jego zamknięcie wraca do dashboardu.

## 6. Metadane i okładka

Lokalny Sendspin dostarcza tytuł, wykonawcę, album, postęp i okładkę. UI:

- przycina tekst elipsą;
- pokazuje neutralną grafikę zastępczą przy braku okładki;
- nie blokuje sterowania podczas ładowania grafiki;
- odrzuca obraz ponad limit i skaluje go przed przechowaniem.

Propozycja: jeden kanał artwork albumu JPEG 320×320, bez cache dyskowego. Przechowujemy tylko aktualną okładkę w pamięci, zwalnianą po zastąpieniu lub zamknięciu klienta; odrzucamy spóźniony wynik dotyczący poprzedniego utworu. Limit zakodowanego obrazu: 1 MiB, dekodowanie ze skalowaniem do 320×320 i kontrolą wymiarów przed alokacją. Dla zdalnego gracza URL grafiki pochodzi z API MA. Token nie może być wysłany do obcego hosta przy pobieraniu grafiki.

## 7. Odtwarzanie na Lenovo

### 7.1. Audio

Audio odtwarza Android `AudioTrack` z atrybutami muzycznymi. Prototyp ma zacząć od PCM, a następnie sprawdzić FLAC. Opus i automatyczna negocjacja wielu kodeków mogą poczekać. Bufor służy tylko synchronizacji i krótkim przerwom sieciowym, nie odtwarzaniu offline.

### 7.2. Lifecycle

Odtwarzanie działa w foreground service, aby krótkie otwarcie ustawień nie przerywało muzyki. Serwis:

- utrzymuje Sendspin po skonfigurowaniu lokalnego gracza;
- używa trwałej, prywatnie zapisanej tożsamości zgodnej z ustaloną rewizją protokołu (dla nowego protokołu pary kluczy, nie dowolnego UUID);
- pokazuje wymagane powiadomienie Androida;
- zwalnia zasoby po wyłączeniu lokalnego gracza;
- nie uruchamia ostatniej muzyki samodzielnie po restarcie.

Zachowanie foreground service wymaga próby na OTA 627.

### 7.3. Głośność

Głośność Sendspin 0–100 steruje wyłącznie wzmocnieniem muzycznego `AudioTrack.setVolume()` (0–1), nie przestawia systemowego `STREAM_MUSIC`. Wyciszenie muzyki zachowuje nastawę do późniejszego przywrócenia. Samo ściszenie muzyki nie ścisza odpowiedzi Assist ani piku.

Źródłem potwierdzonego stanu suwaka sterowania jest MA. Lokalny suwak wysyła jedno żądanie do MA po zakończeniu gestu i pokazuje stan oczekiwania do aktualizacji z serwera; odczyt/echo nigdy nie uruchamia ponownie tej komendy. Błąd lub timeout 10 s przywraca ostatnią potwierdzoną wartość. Tak samo działa mute.

Nie mylić poziomu grupy i pojedynczego playera: Sendspin `server/state.controller.volume` opisuje grupę. Panel pilota korzysta ze stanu wybranego celu MA i jawnie oznacza grupy; dla samego Lenovo używa komendy API kierowanej do jego player_id, nie grupowego `client/command.controller.volume`. Lokalny sink stosuje skierowane do niego `server/command.player` (volume/mute) i raportuje zastosowaną nastawę przez `client/state.player`. To potwierdzenie protokołu, nie nowe żądanie zmiany głośności. Nie kopiować grupowego controller volume bezpośrednio do lokalnego AudioTrack. Test musi obejmować grupę o różnych poziomach jej członków, również gdy tworzenie grup nie jest funkcją Heliosa.

Osobna regulacja głośności urządzenia, dostępna lokalnie i przez pełną integrację HA z SPEC 0.7, zmienia wspólny poziom systemowy. Fizyczne przyciski działają na ten poziom, nie zmieniają procentu głośności muzyki raportowanego do MA. Efektywna głośność muzyki zależy od obu nastaw. Rozdzielenie oraz zachowanie na OTA 627 wymagają testu na zegarze.

## 8. Współpraca z głosem

Wymaganiem odbioru jest działanie Okay Nabu podczas muzyki; **skuteczność na OTA 627 pozostaje hipotezą do próby**, nie potwierdzoną właściwością. Sam wybór `VOICE_COMMUNICATION` nie gwarantuje działającego AEC ani braku fałszywych wybudzeń. Zmierzyć detekcje i fałszywe wybudzenia z muzyką i bez niej przy zapisanych poziomach głośności. Nie uznawać funkcji za gotową przy nieudanej próbie ani po cichu nie usuwać jej z zakresu.

Docelowo po wykryciu hasła Helios:

1. obniża lub pauzuje lokalną muzykę;
2. uruchamia Assist;
3. odtwarza TTS;
4. przywraca muzykę, jeśli sesja nadal jest aktualna i użytkownik jej nie zatrzymał;
5. wraca do nasłuchu hasła.

Propozycja: najpierw sprawdzić Android audio focus z duckingiem; jeśli rozmowa nie będzie czytelna, użyć pauzy i wznowienia. `Stop` albo zmiana kolejki podczas rozmowy anulują automatyczne wznowienie starego stanu.

Ogłoszenia Music Assistant są osobnym mechanizmem i wymagają próby integracyjnej Sendspin; Helios nie tworzy równoległego systemu ogłoszeń.

## 9. Błędy i offline

- Akcje blokują się osobno do odpowiedzi lub timeoutu; `Stop` pozostaje dostępny.
- Błąd pokazuje komunikat i odblokowuje kontrolkę.
- Polecenia nie są ponawiane ani kolejkowane offline.
- Bez API MA lista graczy, wyszukiwanie i pilot są nieaktywne; stare dane są oznaczone.
- Bez Sendspin lokalne audio się zatrzymuje, a klient ponawia połączenie z backoffem.
- Ponowne połączenie nie rozpoczyna samodzielnie nowej kolejki.
- Błąd okładki nie zatrzymuje audio.
- Awaria muzyki nie wyłącza dashboardu, menu ani Assist.

Proponowany timeout akcji API: 10 s, wyszukiwania: 10 s. `Stop` nie czeka na blokadę innej akcji, ale zdalna kontrolka wymaga połączenia; lokalne przerwanie sinka działa także po utracie MA. Zmiana gracza lub zapytania unieważnia spóźniony wynik poprzedniego żądania. Wyniki wyszukiwania mają limit 50 na stronę, bez nieograniczonego pobierania biblioteki.

## 10. Bezpieczeństwo

- Token MA nie trafia do logów, YAML, raportów ani repozytorium.
- Warunek wdrożenia: unieważnić token MA ujawniony w rozmowie i dostarczyć nowy wyłącznie przez prywatny provisioning oraz `Odśwież parowanie`. Przed odbiorem potwierdzić, że stary token jest odrzucany, a nowy działa. Nie wklejać zamiennika do rozmowy; preferować dedykowane konto o minimalnych wymaganych uprawnieniach. Dotychczasowe próby nie są potwierdzeniem wykonanej rotacji.
- Provisioning akceptuje tylko właściwe schematy HTTP i WebSocket.
- API używa tylko skonfigurowanego hosta i odrzuca przekierowania do innego hosta.
- YAML nie zawiera poleceń API, URL-i strumieni ani sekretów.
- Wyszukiwanie ma limit 100 znaków i zaczyna się od 2 znaków.
- Metadane są tekstem, nigdy HTML-em.
- Diagnostyka nie zapisuje tokena, pełnego zapytania ani URL-i mediów.

## 11. Kryteria akceptacji

### Lokalny gracz

- `Helios` pojawia się w MA z trwałą tożsamością.
- Muzyka gra przez głośnik Lenovo i raportuje stan.
- Podstawowe komendy, głośność i mute działają.
- Tytuł, wykonawca i okładka aktualizują się na żywo.

### Nakładka muzyczna

- Lokalny start pokazuje tylko uchwyt z nutką przy prawej krawędzi, w połowie wysokości ekranu; panel otwiera dopiero dotknięcie.
- Panel przykrywa prawą połowę pod stałym paskiem; pozycje i rozmiary kafelków są identyczne przed otwarciem, podczas niego i po schowaniu.
- Aktualizacje encji nadal docierają pod nakładkę; otwarcie nie zmienia subskrypcji ani YAML.
- Dotyk panelu i uchwytu nie uruchamia zasłoniętych kafelków, również podczas animacji i dla kafelków przecinających granicę panelu; odsłonięta część działa normalnie.
- Schowanie przyciskiem lub Wstecz nie zatrzymuje muzyki i nie wywołuje akcji pod panelem; uchwyt ponownie go rozwija.
- Pauza zachowuje otwarcie lub schowanie zgodnie z regułą 5.3; wznowienie, zmiana utworu i nowa sesja nie otwierają panelu automatycznie.
- `stopped`, `idle` i utrata Sendspin usuwają panel oraz uchwyt; zdalny gracz sam ich nie pokazuje.
- Nakładka działa ze schematem 2 i 3, również bez kafelka `music`. Typ `music` wymaga schematu 3; drugi kafelek tego typu oraz pole `music_layout` są odrzucane atomowo.
- Biblioteka i dialog potwierdzenia zachowują pierwszeństwo interakcji zgodnie z 5.3.

### Pilot

- Kafelek otwiera wybór gracza, wyszukiwanie i ostatnie pozycje.
- Wynik uruchamia się na wskazanym graczu.
- Ostatnie 10 pozycji można ponownie odtworzyć.
- Zamknięcie panelu wraca do dashboardu bez zatrzymania muzyki.
- Wprowadzanie zapytania i zamknięcie IME działa na fizycznym zegarze.

### Głos i odporność

- Okay Nabu wykrywa hasło podczas muzyki.
- TTS jest czytelny, a muzyka wraca tylko bez późniejszego `Stop`.
- Bez MA dashboard i Assist nadal działają.
- Polecenia offline nigdy nie wykonują się później.

## 12. Obowiązkowy prototyp

### Dowody i granice dotychczasowych prób

[Raport i odtwarzalny prototyp](../artifacts/music-assistant-prototype.md) zawiera przypięte rewizje, licencje, testy kompilacji, handshake oraz odczyty API. Nie jest dowodem działania audio, klawiatury ani wake word podczas muzyki na zegarze, ani decyzją o wyborze biblioteki.

### Dalszy odbiór

Przed uznaniem odpowiednich przyrostów w jednym APK Heliosa za gotowe należy:

1. zaimplementować wybrany w 3.1 własny adapter legacy z testami kontraktu oraz przypiętym protokołem, zależnościami i ich licencjami;
2. potwierdzić pełny build Heliosa dla `minSdk 29`, Java 8 i ARMv7 oraz koszt APK/RAM/CPU;
3. zarejestrować trwałego klienta na lokalnym MA;
4. odebrać i odtworzyć 60 sekund audio przez LAN;
5. zmierzyć underruny i stabilność synchronizacji;
6. odebrać metadane, okładkę i obsługiwane komendy;
7. wysłać stop i głośność;
8. sprawdzić konflikt z WakeWordListener oraz Assist/TTS;
9. sprawdzić reconnect bez samoczynnego uruchomienia muzyki;
10. zamknąć próbę IME i odświeżenia parowania oraz warunek rotacji tokena z punktu 10.

Nie stosować automatycznego przełączania między dwiema implementacjami klienta. Niepowodzenie wybranego wariantu wymaga jawnej rewizji decyzji z 3.1, nie cichego dołączenia biblioteki. Kopiowanie rozwijanej aplikacji mobilnej bez analizy licencji jest niedopuszczalne.

## 13. Źródła

- [Music Assistant API](https://www.music-assistant.io/api/)
- [Integracja HA](https://www.music-assistant.io/integration/)
- [Sendspin provider](https://github.com/music-assistant/server/blob/dev/music_assistant/providers/sendspin/README.md)
- [Specyfikacja Sendspin](https://github.com/Sendspin/spec/blob/main/README.md)
- [Sendspin JVM](https://github.com/Sendspin/sendspin-jvm)
- [Sendspin player — komendy i raportowanie własnej głośności](https://github.com/Sendspin/spec/blob/8fc2f8f8d8aa324cf385bd3332a284fd3a75520c/roles/player/v1.md)
- [Sendspin controller — głośność grupy](https://github.com/Sendspin/spec/blob/8fc2f8f8d8aa324cf385bd3332a284fd3a75520c/roles/controller/v1.md)
- [Lokalny gracz aplikacji mobilnej](https://github.com/music-assistant/mobile-app/blob/main/docs/app-documentation/local-sendspin-player-settings.md)

Źródła sprawdzono 2026-09-15. Przed implementacją trzeba ponownie sprawdzić wersję serwera, endpointy, schemat Sendspin i bibliotekę JVM.
