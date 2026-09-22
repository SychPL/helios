# Specyfikacja Helios 0.5 — dashboard konfigurowany z Home Assistant

Status: zaimplementowano w Helios 0.5.0. Dnia 2026-09-15 potwierdzono wersję na fizycznym zegarze, zmigrowano panel HA do schematu 2 i sprawdzono wyświetlenie danych; build, lint i 13 testów JVM przeszły. Odbiór dźwięku i dopowiedzenia pozostaje otwarty; [raport odbioru](../artifacts/native-0.5-acceptance-20260915.md). Ustalenia produktowe zaakceptowano 2026-09-15. Szczegóły oznaczone jako „propozycja projektowa” są rutynowymi rozstrzygnięciami przyjętymi na potrzeby implementowalnej specyfikacji, a nie osobnymi decyzjami użytkownika.

Planowany następny etap: [SPEC 0.6 — Music Assistant](SPEC-0.6-music-assistant.md). Dodaje kafelek muzyki i alternatywny układ półekranowy; nie należy włączać ich do zakresu 0.5.

## 1. Cel

Helios 0.5 ma zastąpić zapisany w Javie układ zegara jednym dashboardem 800×480, którego treść jest opisana w Home Assistant. Użytkownik ma zmieniać układ i obsługiwane elementy bez aktualizacji APK. Pierwszy etap korzysta z YAML; format ma być strukturalny i wersjonowany, aby późniejszy edytor wizualny mógł zapisywać ten sam model.

Wersja 0.5 nie renderuje dowolnych kart Lovelace. Odczytuje własną sekcję `helios` z konfiguracji dedykowanego panelu HA i renderuje sześć jawnie obsługiwanych typów elementów.

## 2. Zakres etapu

W zakresie:

- jeden ekran bez przewijania;
- stały górny pasek i stałe lokalne menu aplikacji;
- konfigurowalna siatka dużych pól dotykowych poniżej paska;
- elementy `clock`, `weather`, `entity`, `light`, `cover` i `garage`;
- widoczność sterowana stanem encji obliczanym w HA;
- aktualizacje konfiguracji i stanów bez restartu aplikacji;
- proste, ograniczone akcje urządzeń i opcjonalne potwierdzenia;
- zachowanie ostatniego poprawnego układu przy błędzie lub pracy offline.

Poza zakresem:

- edytor wizualny konfiguracji;
- wiele ekranów, przewijanie i swobodne pozycjonowanie pikselowe;
- dowolne karty Lovelace, szablony Jinja lub wykonywanie kodu na zegarze;
- dowolne nazwy usług HA i dowolne dane usług pochodzące z YAML;
- kolejka poleceń offline;
- rozbudowane wykresy, listy encji i niestandardowe wtyczki elementów.

## 3. Stała powłoka aplikacji

### 3.1. Górny pasek

Górny pasek nie należy do konfigurowalnego dashboardu i nie może być usuwany ani zmieniany przez YAML. Zajmuje obszar obecnego napisu HELIOS i ikony HA. Zawiera:

- stały napis `HELIOS`;
- stan połączenia z Home Assistant;
- krótki status aplikacji, na przykład `Nabu nasłuchuje`.

Propozycja projektowa dla 0.5: pasek pokazuje najwyżej jeden tekst statusu, a stan HA jest zawsze widoczny jako osobna ikona. Priorytet tekstów: błąd konfiguracji lub połączenia, aktywna rozmowa/odtwarzanie, aktywne nasłuchiwanie Nabu, stan spoczynkowy. Komunikat wyższego priorytetu zastępuje niższy do czasu ustąpienia przyczyny. Nie wolno uznawać polecenia za wykonane wyłącznie na podstawie wysłania żądania; sposób sygnalizacji oczekiwania opisuje punkt 8.

### 3.2. Menu lokalne

Menu zegara jest stałe i zdefiniowane w aplikacji. Helios 0.5 nie pobiera listy pozycji z widoku `menu-zegara`. Menu musi zapewniać co najmniej zamknięcie panelu oraz dostęp do niezbędnych ustawień i diagnostyki połączenia także bez HA. Ostateczna lista pozycji jest osobnym detalem implementacyjnym, ale żadna pozycja nie może zależeć od poprawności dashboardu.

### 3.3. Nawigacja

Urządzenie nie udostępnia użytkownikowi przycisków nawigacji Androida, dlatego:

- każdy pełnoekranowy widok musi mieć widoczny przycisk powrotu lub zamknięcia;
- każdy panel nakładkowy musi mieć widoczne zamknięcie;
- każdy dialog potwierdzenia musi mieć przyciski potwierdzenia i anulowania;
- dotknięcie poza panelem lub dialogiem może go zamknąć, ale zawsze oznacza anulowanie i nie wykonuje akcji;
- po zakończeniu lub anulowaniu interakcji użytkownik wraca do dashboardu.

## 4. Siatka dashboardu

Dashboard zajmuje cały obszar poniżej górnego paska i nie przewija się.

Propozycja projektowa dla 800×480:

- pasek ma 52 px wysokości, a obszar dashboardu 428 px;
- siatka ma 4 kolumny i 3 wiersze;
- odstęp i margines wynoszą po 8 px;
- element określa pozycję `column`, `row` oraz rozmiar `width`, `height` w komórkach;
- wszystkie widoczne kontrolki dotykowe mają co najmniej 72×72 px;
- pola nie mogą się nakładać ani wychodzić poza siatkę;
- ukryty element zwalnia miejsce, ale pozostałe elementy nie są automatycznie przesuwane.

Puste miejsca po ukryciu elementu są świadomą decyzją pierwszego etapu: układ pozostaje stabilny, a elementy nie nakładają się i nie przeskakują po zmianie stanu. Bardziej elastyczne układy można dodać w kolejnej wersji schematu. Liczby w tym punkcie są propozycją projektową i mogą zostać skorygowane po podglądzie na urządzeniu bez zmiany schematu konfiguracji.

## 5. Kontrakt konfiguracji

### 5.1. Lokalizacja i wersja

Konfiguracja pozostaje w głównej sekcji `helios` panelu `helios-clock`. Wersja 0.5 wymaga `version: 2`; format `version: 1` z wersji 0.4 nie jest interpretowany jako nowy dashboard.

```yaml
helios:
  version: 2
  grid:
    columns: 4
    rows: 3
  items: []
```

`columns` i `rows` są obecne jawnie z myślą o przyszłym edytorze, ale w 0.5 jedyną obsługiwaną wartością jest odpowiednio `4` i `3`.

### 5.2. Wspólne pola elementu

Każdy wpis `items` ma pola:

| Pole | Wymagane | Znaczenie |
| --- | --- | --- |
| `id` | tak | Unikalny, stabilny identyfikator `[a-z0-9_-]{1,40}`. |
| `type` | tak | Jeden z sześciu typów opisanych w punkcie 6. |
| `column`, `row` | tak | Pozycja od 1, mieszcząca się w siatce. |
| `width`, `height` | tak | Rozmiar od 1 do granicy siatki. |
| `title` | nie | Podpis do 40 znaków; jedna linia z elipsą; domyślny zależy od typu/encji. |
| `icon` | nie | Nazwa ikony z zamkniętego rejestru aplikacji. |
| `visible_when` | nie | Warunek widoczności opisany niżej. Brak oznacza zawsze widoczny. |
| `tap_action` | nie | Opcjonalne jawne zapisanie domyślnej akcji typu interaktywnego. |
| `confirmation` | nie | Ustawienia potwierdzenia akcji. |

Propozycja projektowa: maksymalnie 12 elementów i maksymalnie 64 KiB sekcji `helios` po jej wyodrębnieniu. Cały dokument Lovelace podlega osobnemu limitowi transportu 256 KiB zachowanemu z obecnego klienta. Nieznane pola, powtórzony `id`, nakładanie pól, nieznana ikona lub nieobsługiwana wartość powodują odrzucenie całej konfiguracji.

Identyfikator encji musi pasować do `[a-z0-9_]+\.[a-z0-9_]+`; wymagane domeny podaje tabela:

| Typ | Wymagane pola specyficzne | Opcjonalne pola specyficzne | Niedozwolone pola wspólne | Akcja domyślna/dozwolona |
| --- | --- | --- | --- | --- |
| `clock` | brak | brak | `entity`, `temperature_entity`, `attribute`, `icon`, `tap_action`, `confirmation` | brak |
| `weather` | `entity` w domenie `weather` | `temperature_entity` w domenie `sensor` | `attribute`, `icon`, `tap_action`, `confirmation` | brak |
| `entity` | `entity` w dowolnej domenie | `attribute` | `temperature_entity`, `tap_action`, `confirmation` | brak |
| `light` | `entity` w domenie `light` | `tap_action.action: toggle`, `confirmation` | `temperature_entity`, `attribute` | `toggle` |
| `cover` | `entity` w domenie `cover` | `tap_action.action: controls`, `confirmation` | `temperature_entity`, `attribute` | `controls` |
| `garage` | `entity` w domenie `cover` | `tap_action.action: close`, `confirmation` | `temperature_entity`, `attribute` | `close` |

`tap_action` jest opcjonalne, ponieważ typ jednoznacznie wyznacza akcję. Jeśli występuje, musi zawierać dokładnie pole `action` z wartością z tabeli. `confirmation` jest dozwolone tylko dla typów interaktywnych. Opcjonalne `attribute` musi pasować do `[a-z0-9_]{1,64}`.

Rejestr nazw ikon ma być jednym źródłem prawdy używanym przez parser i renderer; literówka odrzuca atomowo konfigurację. Minimalny rejestr 0.5 obejmuje `information`, `weather-rainy`, `lightbulb`, `window-shutter` i `garage-open`. Domyślne ikony to odpowiednio: `entity` — `information`, `light` — `lightbulb`, `cover` — `window-shutter`, `garage` — `garage-open`. Typy `clock` i `weather` nie przyjmują pola `icon`; warunek pogody jest renderowany jako tekst przez istniejące `WeatherLabels`. Obecny `ha-icon-provenance.json` dokumentuje tylko ikonę połączenia HA, dlatego implementacja 0.5 musi dodać jawny rejestr ikon dashboardu i provenance dla dołączonych zasobów.

### 5.3. Widoczność

Zegar nie oblicza czasu dnia, prognozy ani złożonych reguł. Home Assistant wystawia wynik jako encję, najczęściej `binary_sensor`, `input_boolean` albo czujnik szablonowy.

```yaml
visible_when:
  entity: binary_sensor.helios_show_open_garage
  state: "on"
```

W 0.5 warunek ma dokładnie jedną encję i jeden oczekiwany stan tekstowy. Przy żywym połączeniu element jest widoczny wyłącznie, gdy stan jest równy `state`; `unknown`, `unavailable` i brak encji oznaczają warunek niespełniony. Po utracie połączenia Helios zamraża ostatnią rozstrzygniętą widoczność i oznacza dashboard jako nieaktualny. Przy starcie wyłącznie z cache, przed otrzymaniem pierwszego snapshotu w bieżącym procesie, wszystkie elementy z `visible_when` pozostają ukryte.

### 5.4. Potwierdzenie

```yaml
confirmation:
  enabled: true
  text: Zamknąć bramę garażową?
```

`enabled` jest wymaganym booleanem, a opcjonalny `text` ma do 80 znaków. Domyślnie `enabled` wynosi `true` dla `garage` i `false` dla pozostałych typów interaktywnych. `confirmation.enabled: false` dla bramy jest świadomie dozwolone, ponieważ użytkownik może wyłączyć zabezpieczenie jawnie w YAML. Potwierdzenie dotyczy wyłącznie akcji zmieniającej stan. Anulowanie, dotknięcie poza dialogiem i utrata połączenia zamykają dialog bez wysłania polecenia.

### 5.5. Pełny przykład

```yaml
title: Helios
helios:
  version: 2
  grid:
    columns: 4
    rows: 3
  items:
    - id: clock
      type: clock
      column: 1
      row: 1
      width: 2
      height: 1
      title: Dom

    - id: weather
      type: weather
      entity: weather.forecast_dom
      temperature_entity: sensor.temperatura_salon
      column: 3
      row: 1
      width: 2
      height: 1
      title: Pogoda

    - id: rain-info
      type: entity
      entity: sensor.helios_rain_message
      column: 1
      row: 2
      width: 2
      height: 1
      title: Prognoza
      icon: weather-rainy
      visible_when:
        entity: binary_sensor.helios_show_rain_daytime
        state: "on"

    - id: living-room-light
      type: light
      entity: light.salon
      column: 3
      row: 2
      width: 1
      height: 1
      title: Salon
      icon: lightbulb
      tap_action:
        action: toggle

    - id: living-room-cover
      type: cover
      entity: cover.roleta_salon
      column: 4
      row: 2
      width: 1
      height: 1
      title: Roleta
      icon: window-shutter
      tap_action:
        action: controls

    - id: garage
      type: garage
      entity: cover.brama_garazowa
      column: 1
      row: 3
      width: 4
      height: 1
      title: Brama otwarta
      icon: garage-open
      visible_when:
        entity: binary_sensor.helios_show_open_garage_evening
        state: "on"
      tap_action:
        action: close
      confirmation:
        enabled: true
        text: Zamknąć bramę garażową?
views:
  - title: Konfiguracja
    path: konfiguracja
    cards:
      - type: markdown
        content: Edytuj sekcję helios. Karty Lovelace nie są renderowane na zegarze.
```

## 6. Typy elementów

### 6.1. `clock`

Wyświetla godzinę i datę urządzenia. Nie ma `entity` ani akcji. `title` jest opcjonalnym krótkim podpisem. Strefa czasowa pochodzi z Androida.

### 6.2. `weather`

Wymaga `entity` z domeny `weather`. Opcjonalne `temperature_entity` wskazuje encję liczbową temperatury; przy jego braku renderer używa atrybutu `temperature` encji pogody. Zachowane są również atrybuty `temperature_unit`, `wind_speed` i `wind_speed_unit`, aby utrzymać informacje pokazywane w 0.4. Warunek pogodowy jest tłumaczony na tekst przez `WeatherLabels`; typ nie przyjmuje konfigurowalnej ikony ani akcji. Helios nie odczytuje atrybutu `forecast`; prognoza warunkująca widoczność ma być obliczona w HA i wystawiona jako osobna encja.

### 6.3. `entity`

Wymaga dowolnej encji HA. Wyświetla `title`, ikonę oraz tekst stanu encji; opcjonalne `attribute` wskazuje pojedynczy atrybut zamiast stanu. Element nie ma akcji w 0.5. Podpis i wartość są jednoliniowe i przycinane elipsą.

### 6.4. `light`

Wymaga encji z domeny `light`. Domyślna i jedyna dozwolona akcja to `toggle`; opcjonalne `tap_action.action` może ją zapisać jawnie. Dotknięcie wywołuje wyłącznie `light.toggle` dla tej encji. Zegar nie przyjmuje nazwy usługi ani dodatkowych danych usługi z YAML.

### 6.5. `cover`

Wymaga encji z domeny `cover`. Domyślna i jedyna dozwolona akcja to `controls`; opcjonalne `tap_action.action` może ją zapisać jawnie. Dotknięcie otwiera stały lokalny panel z trzema widocznymi przyciskami ▲ (otwórz), ■ (zatrzymaj) i ▼ (zamknij) oraz procentem otwarcia z atrybutu `current_position`, gdy encja go ma. Wywołują one odpowiednio `cover.open_cover`, `cover.stop_cover` i `cover.close_cover` dla skonfigurowanej encji. Blokada oczekiwania jest per przycisk; zatrzymanie nigdy nie wymaga potwierdzenia. Panel ma widoczne zamknięcie; dotknięcie poza panelem niczego nie wykonuje. Element na siatce pokazuje stan i procent otwarcia.

### 6.6. `garage`

Wymaga encji z domeny `cover`. Domyślna i jedyna dozwolona akcja to `close`; opcjonalne `tap_action.action` może ją zapisać jawnie. Dotknięcie proponuje wywołanie `cover.close_cover`. Potwierdzenie jest domyślnie włączone. W 0.5 element nie oferuje otwierania bramy.

## 7. Aktualizacja konfiguracji i stanów

Po uruchomieniu Helios:

1. ładuje lokalnie ostatnią poprawną konfigurację, jeśli istnieje;
2. łączy się i uwierzytelnia z Home Assistant;
3. pobiera dokument panelu `helios-clock` i waliduje całą sekcję `helios` wersji 2;
4. subskrybuje encje używane przez elementy i `visible_when` oraz zdarzenie `lovelace_updated`;
5. oczekuje maksymalnie 20 sekund na pierwsze zdarzenie `subscribe_entities`, które jest snapshotem;
6. dopiero po spójnym snapshotcie oznacza dane jako aktualne i atomowo pokazuje układ;
7. po poprawnym zapisie nowej konfiguracji uruchamia nową sesję pobierania, ale zachowuje stary układ zamrożony i oznaczony jako nieaktualny, dopóki nie otrzyma pierwszego snapshotu encji nowej konfiguracji; nowy układ i jego cache wchodzą razem dopiero wtedy.

Błąd nowej konfiguracji nie może usunąć ani częściowo zmienić działającego dashboardu. Aplikacja zachowuje ostatnią poprawną konfigurację, oznacza problem w stałym pasku i ponawia pobranie po kolejnym zapisie lub ponownym połączeniu. Jeśli urządzenie nie ma poprawnego cache wersji 2 — także po aktualizacji, gdy HA nadal udostępnia `version: 1` — pokazuje wbudowany układ awaryjny z samym lokalnym zegarem zajmującym całą siatkę oraz komunikat `Wymagana konfiguracja Helios version: 2` w pasku. Stałe menu pozostaje dostępne.

Encje nieistniejące mogą nie wystąpić w początkowym snapshotcie. Helios nie czeka na każdą encję osobno: po pierwszym zdarzeniu uznaje snapshot za kompletny, a brakujące encje traktuje jako brak danych. Dekoder `subscribe_entities` musi rozszerzyć obecne wsparcie `s` o atrybuty z `a` w snapshotach i `+.a` w różnicach. Przechowuje wyłącznie atrybuty wymienione w poprawnej konfiguracji: `temperature`, `temperature_unit`, `wind_speed` i `wind_speed_unit` pogody, `current_position` rolety i bramy oraz jawne `attribute` elementów; resztę pomija. Limit wiadomości klienta pozostaje 256 KiB.

Obecny klient korzysta z poleceń WebSocket `lovelace/config`, zdarzenia `lovelace_updated` i `subscribe_entities`. Pierwsze dwa są wewnętrznymi mechanizmami konfiguracji Lovelace i ich dostępność, format odpowiedzi oraz uprawnienia trzeba zweryfikować na docelowej wersji Home Assistant przed implementacją 0.5. Jeśli nie zapewnią stabilnego kontraktu, należy wybrać dedykowany punkt integracji HA bez zmiany schematu `helios` widzianego przez renderer.

## 8. Wykonywanie akcji

Akcję można rozpocząć tylko, gdy połączenie z HA jest aktualne. Przebieg:

1. użytkownik dotyka aktywnego elementu lub przycisku w panelu rolety;
2. jeśli wymagane jest potwierdzenie, Helios pokazuje dialog;
3. po potwierdzeniu Helios wysyła jedno wywołanie do HA i blokuje ponowne wysłanie tej samej akcji do odpowiedzi albo timeoutu;
4. odrzucenie żądania lub timeout daje czytelny błąd i odblokowuje daną akcję;
5. polecenie nie jest ponawiane automatycznie i nie trafia do kolejki offline.

Propozycja projektowa: stan oczekiwania jest pokazywany przy konkretnej akcji przez spinner, maksymalnie przez 10 sekund. W panelu rolety blokada jest per przycisk: po `open_cover` przycisk `Zatrzymaj` pozostaje aktywny przy żywym połączeniu. Odpowiedź WebSocket potwierdza przyjęcie wywołania, ale nie musi potwierdzać fizycznego zakończenia ruchu urządzenia. Aktualizacja właściwej ikony i stanu po zmianie encji pozostaje preferowanym rozwiązaniem technicznym wymagającym sprawdzenia dla konkretnych encji bramy i rolety.

Wywołania usług powinny używać standardowego polecenia WebSocket HA `call_service`; dokładny format odpowiedzi i zachowanie timeoutu trzeba zweryfikować integracyjnie na docelowym HA.

## 9. Offline, brak danych i błędy

- Ostatnia poprawna konfiguracja i ostatnia rozstrzygnięta widoczność elementów pozostają zamrożone po utracie połączenia.
- Przy starcie z cache bez pierwszego snapshotu wszystkie elementy warunkowe są ukryte; elementy bez `visible_when` pozostają widoczne.
- Cały obszar danych zależnych od HA otrzymuje jednoznaczne oznaczenie nieaktualności, a pasek pokazuje brak połączenia.
- Elementy sterujące są nieaktywne. Dotknięcie może pokazać informację `Brak połączenia z Home Assistant`, ale nie tworzy polecenia.
- Żadne polecenie nie jest przechowywane ani wykonywane po odzyskaniu połączenia.
- Wartości `unknown`, `unavailable`, brak encji i brak wymaganego atrybutu są prezentowane jako brak danych, nigdy jako bezpieczny stan urządzenia.
- Po ponownym połączeniu aplikacja pobiera nową konfigurację i pełny snapshot przed usunięciem oznaczenia nieaktualności.
- Błąd jednego stanu encji nie może wyłączyć stałego menu ani górnego paska.

## 10. Walidacja i zgodność

Parser wersji 2 odrzuca konfigurację, gdy:

- brakuje wymaganego pola lub występuje nieznane pole;
- występuje pole niedozwolone dla danego typu, w tym `confirmation` dla typu bez akcji;
- typ, domena encji lub akcja nie pasują do siebie;
- identyfikatory się powtarzają;
- element wychodzi poza siatkę albo nakłada się na inny element;
- liczba lub rozmiar elementów przekracza limity;
- potwierdzenie ma nieprawidłowy typ lub zbyt długi tekst;
- `visible_when` nie zawiera dokładnie encji i oczekiwanego stanu;
- dokument ma inną wersję niż obsługiwana.

Zmiana konfiguracji ma być atomowa: albo cała sekcja `helios` przechodzi walidację, albo nadal działa poprzednia wersja. Cache przechowuje dokładną poprawną sekcję `helios` oraz jej wersję schematu, nie cały dokument Lovelace. Migracja z `version: 1` nie jest automatyczna, ponieważ 0.4 opisuje inny model ekranu.

Konfiguracja połączenia na urządzeniu powinna używać dedykowanego użytkownika Home Assistant bez uprawnień administratora i tokena ograniczonego do tej integracji na tyle, na ile pozwala HA. Sam zamknięty katalog akcji YAML nie ogranicza uprawnień przejętego tokena. Przed wdrożeniem trzeba potwierdzić na docelowym HA, że dedykowany użytkownik może odczytać panel, subskrybować wymagane encje i zdarzenie aktualizacji oraz wywołać wyłącznie potrzebne usługi dla wskazanych urządzeń.

## 11. Kryteria akceptacji

### 11.1. Powłoka i układ

- Na urządzeniu 800×480 stale widoczny jest pasek z HELIOS, stanem HA i statusem aplikacji.
- YAML nie może usunąć ani zmienić paska i lokalnego menu.
- Wszystkie skonfigurowane elementy mieszczą się na jednym ekranie bez przewijania i mają duże cele dotykowe.
- Każdy panel i dialog można bezpiecznie zamknąć lub anulować bez przycisków Androida.

### 11.2. Scenariusz bramy wieczorem

1. HA oblicza `binary_sensor.helios_show_open_garage_evening` na podstawie pory dnia i stanu bramy.
2. Dla `off` element bramy jest ukryty.
3. Gdy wieczorem brama jest otwarta i pomocnik przechodzi na `on`, element pojawia się bez restartu Heliosa.
4. Dotknięcie pokazuje dialog `Zamknąć bramę garażową?`.
5. Anulowanie lub dotknięcie poza dialogiem nie wysyła usługi.
6. Potwierdzenie wysyła dokładnie jedno `cover.close_cover` dla skonfigurowanej bramy.
7. Przy braku połączenia kontrolka jest nieaktywna, a polecenie nie jest wykonywane po późniejszym powrocie HA.

### 11.3. Scenariusz pogody w dzień

1. HA oblicza `binary_sensor.helios_show_rain_daytime` na podstawie pory dnia i prognozy.
2. Dla `on` Helios pokazuje element informacji o deszczu oraz bieżący element pogody zgodnie z YAML.
3. Dla `off` informacja o deszczu znika bez przesuwania innych pól siatki.
4. Przy żywym połączeniu `unknown`, `unavailable` lub brak encji ukrywają warunkową informację o deszczu. Po utracie połączenia ostatnia widoczność zostaje zamrożona, a cały dashboard otrzymuje oznaczenie nieaktualności.

### 11.4. Konfiguracja i odporność

- Poprawny zapis YAML odświeża układ bez aktualizacji APK.
- Błędny zapis nie zastępuje ostatniego poprawnego układu i daje czytelny status błędu.
- Po restarcie bez HA widoczny jest ostatni poprawny układ, oznaczony jako nieaktualny.
- Bez cache wersji 2 widoczny jest awaryjny pełnoekranowy zegar i komunikat wymagający konfiguracji `version: 2`.
- Elementy światła i rolety wywołują wyłącznie usługi dozwolone dla ich typu.
- Konfiguracja nie umożliwia uruchomienia dowolnej usługi, URL-a ani kodu.

> **Od schematu 6** ([SPEC 0.15](SPEC-0.15-dashboard-cards.md)) gwarancja brzmi precyzyjniej, bo doszły
> intencje: konfiguracja nie może wskazać dowolnej usługi, adresu, kodu ani obcego celu; może natomiast
> aktywować jawnie wybrany `script` albo `scene`, których skutki należą już do Home Assistanta. Usługę
> wybiera zegar z zamkniętej tabeli, a celem jest zawsze encja wpisana w tym samym kafelku.

## 12. Droga do edytora wizualnego

Przyszły edytor w HA ma operować na tym samym modelu: stabilnych `id`, typach elementów, współrzędnych i rozmiarach siatki, właściwościach prezentacji, warunku widoczności, akcji oraz potwierdzeniu. Edytor może generować sekcję `helios.version: 2` albo jej zgodną następną wersję. Nie wymaga to renderowania kart Lovelace na zegarze; Lovelace może jedynie hostować interfejs edycji i przechowywać dokument.

Decyzja o pozostawieniu natywnego renderera Androida albo zastąpieniu go inną technologią pozostaje otwarta. Kontrakt danych powinien pozostać od niej niezależny.

## 13. Różnice względem 0.4

- `MainActivity` i `DashboardView` 0.4 tworzą stały natywny układ 800×480; 0.5 ma renderować pozycje z siatki konfiguracji.
- `DashboardSpec` 0.4 obsługuje `clock`, `weather` i maksymalnie trzy `indicators`; 0.5 zastępuje ten kontrakt wersją 2 i tablicą `items`.
- `HaDashboardClient` 0.4 pobiera także widok `menu-zegara`; 0.5 nie używa go jako źródła menu.
- Specyfikacja 0.4 pozostaje opisem wdrożonej wersji historycznej. Niniejsza specyfikacja określa następny etap i nie oznacza, że został on już zaimplementowany.

## 14. Powiązane możliwości sprzętowe poza zakresem

Lokalne sterowanie lampką docka i zdarzeniowa detekcja ładowania telefonu nie należą do etapu 0.5. Potwierdzone możliwości, prywatny binder OEM, ograniczenia stanu początkowego i przyszłe warianty integracji opisuje osobna [nota techniczna lampki docka](lamp-control.md).
