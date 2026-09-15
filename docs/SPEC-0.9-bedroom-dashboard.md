# SPEC 0.9 - rolety sypialni, światło kontekstowe i pogoda na jutro

Status: **projekt po recenzji (Claude Code, 15 września 2026), gotowy do planu**; bez publikacji w HA i instalacji APK do czasu odbioru. Encje z tabeli w pkt 2 potwierdzone w żywym HA 15 września 2026 (obie rolety `supported_features: 15`, grupa światła z dwoma członkami, `weather.forecast_dom` z prognozą dzienną, `sun.sun`); trzy pomocniki `helios_*` nie istnieją, nazwy są wolne. Numer dokumentu nie ustala numeru wydania aplikacji.

## 1. Cel i decyzje

Rozszerzyć istniejący dashboard, nie zmniejszając zegara i nie przesuwając dolnych powiadomień:

- Stały kafelek **Rolety** otwiera podmenu z osobnym sterowaniem dwiema roletami sypialni. Dotknięcie kafelka nie porusza rolet.
- Kafelek **Światło sypialni** pojawia się po zmroku lub przy zamkniętej co najmniej jednej z tych rolet.
- Ten sam kafelek pogody pokazuje bieżące warunki w dzień, a od 18:00 prognozę podpisaną **Jutro**.

Doprecyzowania projektowe do zatwierdzenia w recenzji: „zamknięta roleta” oznacza przynajmniej jedną całkowicie zamkniętą roletę A/B; wieczór dla pogody to 18:00–23:59 w strefie HA. Warunek światła opiera się na zachodzie/wschodzie słońca, a nie na godzinie 18:00. Są to dwie niezależne reguły.

Zakres nie obejmuje automatycznego zapalania światła ani poruszania rolet, sterowania grupowego „obie naraz”, suwaka pozycji, ogólnego systemu zagnieżdżonych dashboardów, nowych powiadomień ani zmian muzyki/Nabu. To nadal jedna aplikacja Helios.

## 2. Stan wyjściowy i źródła

W kodzie odniesienia `DashboardSpec` akceptuje schematy 2/3. Typ `cover` wskazuje jedną encję, a `MainActivity.coverPanel` otwiera jej sterowanie. Nie ma podmenu kilku rolet ani przełączania kafelka weather na prognozę. `visible_when` przyjmuje jeden warunek; złożoną logikę pozostawiamy w HA.

Podczas odczytu HA w tej sesji znaleziono poniższe encje. Przed wdrożeniem ponownie sprawdzić ich tożsamość i skład grupy; identyfikatorów nie wpisywać na stałe w kod Androida.

| Rola | Encja HA |
| --- | --- |
| Roleta A, główna sypialnia | `cover.bedroom_main_cover_a` |
| Roleta B, główna sypialnia | `cover.bedroom_main_cover_b` |
| Światła głównej sypialni | `light.bedroom_a_all` |
| Członkowie grupy światła | `light.bedroom_a_light_swiatlo`, `light.bedroom_a_light_swiatlo_2` |
| Pogoda i źródło prognozy dziennej | `weather.forecast_dom` |
| Dzień/noc | `sun.sun` - sprawdzić dostępność przed publikacją |

Nie używać lampki docka zegara ani encji sypialni dzieci. Nie tworzyć grupy rolet, ponieważ użytkownik chce wybierać je osobno. W odczytanej odpowiedzi `weather.get_forecasts` źródło zwróciło prognozę dzienną również na następny dzień; nie jest to gwarancja jej późniejszej dostępności.

## 3. Układ głównego ekranu

Zachować siatkę 4×3, skalowanie, pasek i paletę z [SPEC 0.8a](SPEC-0.8a-renderer-music.md). Tła nadal podlegają [SPEC 0.8b](SPEC-0.8b-backgrounds.md).

| Element | Kolumna | Wiersz | Rozmiar | Widoczność |
| --- | --- | --- | --- | --- |
| Zegar i data | 1 | 1 | 2×2 | Stała |
| Pogoda / Jutro | 3 | 1 | 2×1 | Stała |
| Rolety | 3 | 2 | 1×1 | Stała, także przy obu zamkniętych |
| Światło sypialni | 4 | 2 | 1×1 | Warunek z pkt 5 |
| Światła wymagające uwagi | 1 | 3 | 1×1 | Bez zmiany |
| Garaż | 2 | 3 | 1×1 | Bez zmiany |
| Blaszak | 3 | 3 | 1×1 | Bez zmiany |
| Wiking był | 4 | 3 | 1×1 | Bez zmiany |

Ukryte światło zostawia puste pole, nie przesuwa pozostałych kafelków. Nie nakładać dwóch warunkowych kafelków pogody w tym samym miejscu - walidator odrzuca nakładanie niezależnie od widoczności.

Uchwyt muzyki zasłania część pola światła w kolumnie 4/wierszu 2. Dotyk uchwytu obsługuje wyłącznie muzykę, odsłonięta część kafelka wyłącznie światło; ukryty kafelek nie ma aktywnego obszaru dotyku. Rozwinięta muzyka nadal przykrywa dashboard, nie zmieniając siatki.

## 4. Podmenu Rolety

### 4.1. Wygląd i nawigacja

Kafelek ma ikonę `window-shutter`, tytuł „Rolety” i dwa krótkie wiersze w polach istniejącego kafelka 1×1: wartość (24-28, pomiar) dla pierwszej rolety, np. „A: zamknięta”, opis (17) dla drugiej, np. „B: 40%”. Procent oznacza otwarcie, nie zamknięcie. `opening`/`closing` pokazują „otwieranie”/„zamykanie”; brak stanu pokazuje „brak danych”, nie 0%.

Dotknięcie otwiera jeden modalny panel **Rolety sypialni**, zawierający od razu dwa osobne wiersze: nazwa, aktualny stan/pozycja i kontrolki **Otwórz / Stop / Zamknij**. Nie wymaga kolejnego wejścia w roletę A lub B. Bez zbiorczego przycisku dla obu.

Geometria referencyjna przy 800×480: panel 720×368, wyśrodkowany w obszarze pod paskiem (x=40, y=82), padding 16; nagłówek 40, odstęp 8, dwa wiersze po 96 z odstępem 8, odstęp 8, przycisk „Wróć” wysokości 72. Trzy kontrolki w każdym wierszu mają co najmniej 72×72; etykieta i stan są po lewej. Używać współrzędnych referencyjnych skalowanych jak dashboard, nie nieskalowanych minimów Androida. Nazwy ograniczyć wielokropkiem, nie zwężać kontrolek.

Panel jest nieprzezroczysty, w aktywnym motywie, nad muzyką. Otwieranie zwija panel muzyki bez zatrzymywania odtwarzania. „Wróć”, systemowe cofnięcie i dotknięcie poza panelem zamykają go bez akcji i bez przepuszczania dotyku do dashboardu. Powrót nie otwiera automatycznie muzyki.

### 4.2. Akcje i stan

- Każda kontrolka wywołuje wyłącznie `cover.open_cover`, `cover.stop_cover` lub `cover.close_cover` dla encji danego wiersza. Nazwy usług nie pochodzą z YAML.
- Aktualizacje stanu/pozycji obu rolet docierają przez istniejącą subskrypcję HA, również przy otwartym panelu. Odpowiedź usługi nie jest dowodem osiągnięcia pozycji; nie aktualizować pozycji optymistycznie.
- Niedostępność jednej rolety wyłącza wyłącznie jej kontrolki. Druga działa dalej. Otwieranie/zamykanie/stop wymagają odpowiedniej funkcji z `supported_features`; brak wsparcia wyłącza daną kontrolkę.
- Otwarcie/zamknięcie mają blokadę oczekującego wywołania z kluczem `encja:usługa` (dziś `MainActivity.call` używa `id kafelka:usługa`, co dla dwóch rolet w jednym kafelku jest za szerokie i wymaga zmiany). Wywołanie dla A nie blokuje B. Timeout 10 s, brak automatycznych powtórzeń i kolejki offline.
- Stop nie wymaga potwierdzenia i nie jest blokowany przez oczekujące otwarcie/zamknięcie ani przez poprzedni Stop: dla `stop_cover` blokada nie obowiązuje wcale, każde dotknięcie wysyła osobne wywołanie (dziś `call()` odrzuciłby drugi Stop, dopóki pierwszy nie wróci). Odpowiedzi są rozliczane po identyfikatorach wywołań (`HaDashboardClient.request` już to robi), bez nadpisania callbacku. Stop nadal wymaga aktualnego połączenia i dostępnej encji wspierającej tę funkcję.
- Nowy typ nie przyjmuje `confirmation`; w tym przyroście sterowanie domowymi roletami jest bez potwierdzeń. Dotychczasowy pojedynczy typ `cover` zachowuje własne zasady.
- Utrata HA lub podmiana konfiguracji zamyka panel, unieważnia jego powiązania i nie wykonuje żadnego polecenia. Po reconnect panel sam się nie otwiera. Kafelek pozostaje na miejscu, z oznaczeniem nieaktualności.

## 5. Kontekstowe światło sypialni

W HA utworzyć pomocnik `binary_sensor.helios_sypialnia_swiatlo_pokaz`. Nazwa encji jest docelowa; jeśli zajęta przez cudzą konfigurację, nie przejmować jej, tylko uzgodnić inne ID i zaktualizować odwołania.

Warunek logiczny:

```text
sun.sun == below_horizon
LUB cover.bedroom_main_cover_a == closed
LUB cover.bedroom_main_cover_b == closed
```

Za zamknięcie uznawać wyłącznie stan `closed`, nie `closing` ani arbitralny próg procentowy. Częściowe przymknięcie w dzień nie wystarcza. Znany prawdziwy człon daje `on`, nawet gdy inne źródło jest niedostępne. Jeżeli żaden człon nie jest prawdziwy i choć jedno źródło jest nieznane/niedostępne, pomocnik jest niedostępny; przy komplecie znanych fałszywych stanów daje `off`.

Kafelek korzysta z istniejącego typu `light`, encji `light.bedroom_a_all` i `visible_when` na `on`. To przycisk grupy dwóch świateł sypialni, nie lampki zegara. Dotyk wykonuje pojedyncze `light.toggle`; stan włączony oznacza, że przynajmniej jeden członek grupy świeci. Przed wdrożeniem zweryfikować tę semantykę i skład istniejącej grupy, nie zmieniając jej ustawień bez potrzeby.

Zmiana widoczności nigdy nie wysyła komendy do światła. W dzień, przy otwartych roletach, przycisk znika nawet wtedy, gdy światło pozostało włączone - brak dodatkowego warunku „światło jest włączone”. Nie dodawać sypialni automatycznie do dolnego licznika obserwowanych świateł.

Przy niedostępnym pomocniku i działającym HA kafelek jest ukryty zgodnie z istniejącymi zasadami. Przy niedostępnym świetle, ale prawdziwym warunku, kafelek jest widoczny i nieaktywny. Przy utracie całego połączenia HA zamrozić ostatnią widoczność, przyciemnić dane i wyłączyć akcje.

## 6. Pogoda dzisiaj i jutro

### 6.1. Prezentacja i wybór trybu

- 00:00–17:59: obecny widok pogody, tytuł z konfiguracji („Pogoda”), bieżąca temperatura i opis warunków. Dotychczasowe `temperature_entity`, jeśli ustawione, działa tylko w tym trybie.
- 18:00-23:59: tytuł **Jutro**, temperatura maksymalna z prefiksem „maks.” (44), pod spodem jedna linia opisu (18): polski opis warunków i opcjonalnie „ · min. 9°C”. Jednostka jest jawna; nie zakładać °C. Brak minimum pomija wyłącznie minimum. Nie pokazywać aktualnego wiatru jako prognozy na jutro. Kafelek 2×1 (388×132) mieści dokładnie te trzy linie; nie dodawać czwartej.
- Pora jest liczona w HA, w jego strefie (`Europe/Warsaw` dla tego domu), przez pomocnik `binary_sensor.helios_pogoda_jutro_tryb`. Aplikacja nie oblicza zachodu słońca ani nie ustala własnego harmonogramu.
- Pomocnik trybu jest niezależny od dostępności prognozy. Wieczorem jej brak pokazuje **Jutro - brak prognozy**, nigdy bieżących warunków pod nagłówkiem „Jutro”. Nieznany tryb przy aktywnym HA daje „Pogoda - brak danych o trybie”, zamiast zgadywania pory.

### 6.2. Pobieranie i kontrakt danych

Prognozę pobiera HA, nie zegar: `weather.get_forecasts`, `type: daily`, źródło `weather.forecast_dom`. Mechanizm wdrożeniowy jest trwały: trigger-based template sensor w pakiecie YAML. Pomocniki tworzone w UI nie mają `action`/`response_variable`, więc YAML jest jedyną drogą; **warunek wstępny wdrożenia**: dostęp do `configuration.yaml` (File editor/SSH) i włączone `homeassistant: packages:`. Brak tego dostępu to bramka, nie powód do jednorazowego ustawiania stanu przez REST.

W trigger-based template sensorze błąd akcji przerywa cały przebieg i sensor zachowuje poprzedni stan, więc bez dodatkowej ochrony stary rekord `ready` przeżyłby `valid_until`. Wymagane: akcja `weather.get_forecasts` z `continue_on_error: true`, stan i atrybuty liczone z zapisanego rekordu (zmienna/`this.attributes`) niezależnie od powodzenia akcji, a wyzwalacze `time_pattern` co godzinę oraz `time` 18:00 i 00:00 zawsze przeliczają `valid_until`.

Odświeżanie: start HA, co godzinę, dodatkowo 18:00, północ oraz powrót źródła z niedostępności. Nakładające się wyzwolenia scalają się w jedno pobranie, bez równoległych zapytań. Tylko jedno źródło danych, bez nowego WS i bez nowego dostawcy pogody.

Wybrać rekord po dacie `datetime` przeliczonej na strefę HA, równej lokalnemu „dzisiaj + jeden dzień kalendarzowy”. Nie używać stałego indeksu `[1]` ani dodawania 24 godzin, które może zawieść przy zmianie czasu. Brak jednoznacznego rekordu oznacza brak prognozy.

Publikowany pomocnik `sensor.helios_pogoda_jutro` ma stan `ready` i wyłącznie poniższy kontrakt atrybutów. Przy niepoprawnych danych nie publikuje `ready`.

| Atrybut | Kontrakt |
| --- | --- |
| `forecast_date` | Data docelowa `YYYY-MM-DD` w strefie HA |
| `condition` | Niepusty kod warunków HA; opis przez istniejące `WeatherLabels` |
| `temperature` | Skończona liczba, prognozowane maksimum |
| `templow` | Opcjonalna skończona liczba, minimum |
| `temperature_unit` | `°C` albo `°F`, ze źródła, bez wartości domyślnej |
| `fetched_at` | Czas poprawnego pobrania, ISO 8601 UTC |
| `valid_until` | Wcześniejszy z: następna lokalna północ i `fetched_at + 6 h`, jako ISO 8601 UTC |

Stan i atrybuty publikować atomowo. Błąd odświeżenia nie niszczy wcześniejszego poprawnego rekordu przed jego wygaśnięciem. Wygaśnięcie oraz zmiana daty unieważniają go także wtedy, gdy kolejne pobranie zawiedzie; znaczników nie odświeżać samym odtworzeniem cache po restarcie. Wdrożenie HA musi zapewnić tę ocenę czasu niezależnie od powodzenia akcji pobierania.

Renderer sprawdza typy danych, stan `ready` i `valid_until > teraz`; planuje odświeżenie widoku na moment wygaśnięcia także bez kolejnego zdarzenia HA. Brak/niepoprawna jednostka, data lub termin ważności daje brak prognozy. Weryfikacja, że wybrano jutro, należy do HA; warunkiem odbioru jest poprawny zegar systemowy Lenovo. Nieznany kod warunków dostaje neutralny opis „Brak opisu warunków”, nie losową ikonę. Brak `templow` w delcie czyści poprzednie minimum zgodnie z semantyką stanów HA.

Po utracie HA obowiązuje wspólna reguła zamrożenia dashboardu: nie przełączać samodzielnie „Pogoda”/„Jutro”, a zachowane dane wyraźnie oznaczyć jako nieaktualne. Po reconnect pierwszy aktualny snapshot przywraca właściwy tryb i rekord.

## 7. Zmiana kontraktu dashboardu

Ten przyrost wprowadza **`helios.version: 4`**, ponieważ starszy parser nie zna nowych elementów. To osobna zmiana funkcjonalna, nie przywrócenie odrzuconego schematu wyglądu: appearance nadal jest poza YAML zgodnie z 0.8b. Nowa aplikacja zachowuje obsługę 2/3; numer APK uzgodnić z bieżącymi pracami Claude Code.

### 7.1. Typ `cover_group`

Dozwolone pola: wspólna geometria i `id`, `type`, opcjonalne `title`, `icon`, `visible_when` oraz wymagane `covers`. W tym przyroście `covers` zawiera **dokładnie dwie** pozycje `{entity, title}`; obie wymagane, różne encje `cover.*`, tytuł 1–40 znaków. Brak dowolnych dzieci, kolejnych podmenu i pól akcji. Domyślny tytuł „Rolety”, ikona `window-shutter`. Pojedyncze `entity`, `tap_action`, `confirmation`, `attribute` są niedozwolone dla tego typu.

Podmenu nie zajmuje kolejnych komórek i nie zwiększa liczby kafelków. Do subskrybowanych encji dodać obie rolety, z atrybutami `current_position` i `supported_features`; ta sama lista atrybutów obowiązuje istniejące typy `cover` i `garage` (jedna `COVER_ATTRIBUTES` dla domeny, bez dwóch kontraktów). Zmiany obu muszą aktualizować kafelek i otwarty panel, nie tylko encję główną kafelka.

### 7.2. Rozszerzenie `weather`

Dwa opcjonalne, ale występujące **razem**, pola wyłącznie od schematu 4:

- `forecast_entity`: encja `sensor.*` o kontrakcie z pkt 6.2.
- `forecast_when`: obiekt `{entity, state}` o walidacji identycznej z `visible_when`; określa wybór prognozy, nie ukrywa kafelka. Stan znany zgodny wybiera jutro, znany niezgodny pogodę bieżącą; brak stanu, `unknown` i `unavailable` nie pozwalają wybrać trybu (komunikat z pkt 6.1).

Do subskrypcji dodać encję trybu oraz prognozy; dla prognozy zatrzymywać wyłącznie siedem atrybutów z tabeli. Bez obu pól weather działa jak dotąd, również w schemacie 4. Pola nie są dozwolone dla innych typów. Nie dopuszczać `icon` na weather.

### 7.3. Fragment konfiguracji docelowej

Poniższy fragment pokazuje tylko trzy zmieniane/dodawane kafelki, **nie jest pełną konfiguracją do zastąpienia pulpitu**. W pełnym dokumencie zachować zegar, cztery powiadomienia, `views` i pozostałe obce pola.

```yaml
helios:
  version: 4
  grid: {columns: 4, rows: 3}
  items:
    - id: weather
      type: weather
      entity: weather.forecast_dom
      title: Pogoda
      column: 3
      row: 1
      width: 2
      height: 1
      forecast_entity: sensor.helios_pogoda_jutro
      forecast_when:
        entity: binary_sensor.helios_pogoda_jutro_tryb
        state: "on"
    - id: bedroom-covers
      type: cover_group
      title: Rolety
      column: 3
      row: 2
      width: 1
      height: 1
      covers:
        - entity: cover.bedroom_main_cover_a
          title: Roleta A
        - entity: cover.bedroom_main_cover_b
          title: Roleta B
    - id: bedroom-light
      type: light
      title: Światło sypialni
      entity: light.bedroom_a_all
      column: 4
      row: 2
      width: 1
      height: 1
      visible_when:
        entity: binary_sensor.helios_sypialnia_swiatlo_pokaz
        state: "on"
```

Zachować dotychczasowe limity, walidację geometrii, rejestr ikon i atomową podmianę układu wraz z pierwszym snapshotem wszystkich jego encji. Błąd dokumentu pozostawia ostatni poprawny układ. Zamknąć istniejący panel przed przyjęciem nowej konfiguracji, aby nie sterował usuniętą encją.

## 8. Wdrożenie i ochrona istniejącego dashboardu

1. Uzgodnić bieżący stan gałęzi i numer APK z równoległą implementacją 0.8a/b. Nie nadpisywać jej plików i nie traktować tego dokumentu jako zgody na instalację.
2. Zbudować/testować aplikację z obsługą 2/3/4 na fałszywym HA. Sprawdzić panel i pogodę na 800×480.
3. Przygotować wersjonowany pakiet/konfigurację pomocników HA i osobną operację publikacji. Brak trwałego sposobu instalacji pomocników jest bramką wdrożenia, nie powodem do prowizorycznego ustawiania stanów. Zweryfikować API i składnię z używaną wersją HA.
4. Wykonać kopię całego dokumentu Lovelace i zapisać listę tworzonych pomocników. Tworzyć wyłącznie nowe, własne zasoby, bez przejmowania cudzych encji. Nie modyfikować istniejących automatyzacji światła/rolet.
5. Dopiero po uzgodnionej instalacji zgodnego APK opublikować schemat 4. Ponownie odczytać dokument przed zapisem; jeśli zmienił się od kopii, przerwać zamiast nadpisywać cudze zmiany. Nie używać bez zmian starego `deploy_attention_dashboard.py --apply`, który odtwarza wcześniejszy manifest.
6. Sprawdzić odczyt konfiguracji i obraz zegara. Sam test publikacji nie może wywołać ruchu rolet ani przełączenia światła. Test fizycznych akcji wykonać osobno z użytkownikiem.
7. Rollback: przed powrotem do starego APK przywrócić poprzedni schemat dashboardu. Usuwać tylko własne nowe pomocniki, po sprawdzeniu braku innych odwołań. Uaktualnić manifesty/publikatory, aby późniejsze wdrożenie powiadomień nie skasowało nowych kafelków.

## 9. Kryteria odbioru

1. Parser przyjmuje stary zestaw dokumentów 2/3 i nowy 4. Odrzuca nowe pola w 2/3, nieznane pola, błędne domeny, duplikaty rolet, inną liczbę członków niż dwa, niepełną parę pól prognozy i kolizje komórek. Odrzucenie nie usuwa działającego układu.
2. Subskrypcja obejmuje obie rolety, ich pozycje i funkcje, światło, warunek światła, tryb i komplet atrybutów prognozy. Pierwszy snapshot oraz delty aktualizują właściwe widoki, także usunięcia opcjonalnego minimum.
3. Rolety zawsze zajmują kolumnę 3/wiersz 2. Otwarcie panelu nie wysyła usługi; kliknięcie A nie steruje B. Dostępny Stop działa podczas oczekiwania na zamknięcie. Timeout, spóźniona odpowiedź i zamknięcie panelu nie powodują powtórzeń.
4. Niedostępność A nie blokuje B; brak wsparcia usługi wyłącza tylko jej przycisk. Utrata HA i zmiana konfiguracji zamykają panel bez akcji. Reconnect nie otwiera go sam.
5. Macierz światła: dzień + obie otwarte → ukryte; dzień + A lub B `closed` → widoczne; dzień + obie tylko częściowo przymknięte → ukryte; noc + obie otwarte → widoczne. Sprawdzić także kombinacje znanego prawdziwego warunku z niedostępnością drugiego i brak możliwości rozstrzygnięcia.
6. Ukrywanie/pokazywanie światła nie przełącza go. Niedostępne światło nie jest klikalne. Grupa zawiera tylko dwa ustalone obwody sypialni, bez lampki zegara i bez zmiany grupy dolnego licznika.
7. 17:59 → bieżąca pogoda; 18:00 → Jutro; północ → bieżąca pogoda. Test obejmuje inną strefę urządzenia niż HA, zmianę czasu, koniec miesiąca/roku oraz różną kolejność rekordów prognozy. O wyborze daty/pory decyduje HA.
8. Brak jutra, błędna jednostka, niepoprawne liczby, brak minimum, wygaśnięcie cache, restart HA z odtworzonym starym rekordem i awaria pobrania na północy nie pokazują fałszywej prognozy. Brak trybu ma osobny komunikat. Wygaśnięcie przy aktywnym HA działa także bez nowej delty.
9. Emulator 800×480: oba wiersze rolet i „Wróć” mieszczą się bez przewijania/obcięcia; hitboxy minimum 72×72, czytelne nazwy i pozycje. Cztery dolne powiadomienia nadal są osobnymi kafelkami 1×1.
10. Muzyka: dotyk uchwytu nad światłem nie przełącza światła, dotyk odsłoniętego kafelka nie otwiera muzyki, a dotyk pustego pola niczego nie wykonuje. Panel rolet ma właściwy priorytet dotyku; jego otwarcie nie zatrzymuje muzyki.
11. Offline: zamrożona widoczność i pogoda są oznaczone jako nieaktualne, akcje wyłączone. Powrót HA daje aktualny tryb i warunki po snapshotcie, bez restartowania muzyki.
12. Odbiór wdrożenia obejmuje kopię/rollback, odczyt pełnego zapisanego dokumentu i zrzuty: dzień, wieczór, podmenu rolet, muzyka z uchwytem oraz brak danych. Symulacje stanów przeprowadzić w testach, nie fałszując encji produkcyjnych.

## 10. Odniesienia

- [SPEC 0.5 - bazowy kontrakt dashboardu](SPEC-0.5-ha-configurable-dashboard.md).
- [SPEC 0.8a - geometria, muzyka i paleta](SPEC-0.8a-renderer-music.md), [SPEC 0.8b - wygląd poza YAML](SPEC-0.8b-backgrounds.md).
- [Dashboard uwagi - wdrożenie i wykluczenia](../artifacts/attention-dashboard-20260915.md).
- HA udostępnia prognozy przez `weather.get_forecasts`; bieżący stan encji weather nie jest prognozą. [Oficjalna dokumentacja Weather](https://www.home-assistant.io/integrations/weather/).
- Trwałe obliczenia i wyzwalane pobrania można oprzeć na encjach template; dokładny sposób wdrożenia wymaga weryfikacji na instalacji użytkownika. [Oficjalna dokumentacja Template](https://www.home-assistant.io/integrations/template/).
- Reguła zmroku korzysta ze stanu słońca dla lokalizacji HA. [Oficjalna dokumentacja Sun](https://www.home-assistant.io/integrations/sun/).
