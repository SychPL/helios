# SPEC 0.8b — tło i wygląd per urządzenie przez HA

Status: **propozycja po recenzji; wymaga próby formularza na HA 2026.8.3 przed implementacją**. Bez wdrożenia. [Mapa etapów](SPEC-0.8-dashboard-appearance.md), [renderer 0.8a](SPEC-0.8a-renderer-music.md).

## 1. Cel i uproszczenie

Użytkownik wybiera motyw i zdjęcie z telefonu lub komputera w Home Assistancie. Ustawienia dotyczą jednego sparowanego zegara, nie wszystkich urządzeń czytających ten sam dashboard_path.

Pierwszy wybór to standardowy **options flow integracji Helios**, bez własnego panelu frontendowego, bundla JS, biblioteki 20 zdjęć, edytora Lovelace, schematu 4 i nowego gniazda WS. Schematy dashboardu 2/3 i konfiguracja encji pozostają bez zmian. Jedna aplikacja Helios. Tła nie blokują hotfixu ani redesignu.

Zmiana architektury względem pierwszego SPEC 0.8 jest propozycją do zatwierdzenia: appearance staje się opcją config entry urządzenia, a nie polem dokumentu Lovelace.

## 2. Weryfikacja propozycji z recenzji

Sprawdzone w źródłach przypiętych do **HA 2026.8.3**, nie przez próbny upload prywatnego zdjęcia:

- Nie ma klasy ImageSelector w rejestrze selektorów. Jest FileSelector: przyjmuje filtr MIME i zwraca identyfikator pliku. Jest też MediaSelector, ale jego wynik nie jest kontraktem image_upload.
- image_upload serwuje obrazy bez wymogu uwierzytelnienia. Miniatury mają 256 albo 512; większy obraz można pobrać jako original, nie jako wariant 800. Oryginał jest zachowywany — nie można obiecać automatycznego usunięcia EXIF/GPS.
- Dlatego wariant „ImageSelector + prywatny obraz ≥800 px bez własnego kodu” nie jest gotowym rozwiązaniem.
- FileSelector i file_upload pozwalają uniknąć własnego frontendu uploadu. Integracja nadal musi przygotować trwały obraz oraz udostępnić go zegarowi w sposób uwierzytelniony.

To rozróżnienie nie wymaga zmiany wymagań użytkownika i nie uzasadnia powrotu do dużego edytora. Własny panel można rozważyć dopiero, jeśli próba natywnego formularza wykaże konkretny brak niezbędnej funkcji.

Źródła: [selektory](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/helpers/selector.py), [image_upload](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/image_upload/__init__.py), [file_upload](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/file_upload/__init__.py).

## 3. Formularz użytkownika

HA → Ustawienia → Urządzenia i usługi → Helios → wybrany zegar → Konfiguruj.

- Motyw: Ciepły grafit / Nocny błękit (SelectSelector).
- Tło: kolor / zachowaj bieżące zdjęcie / wgraj nowe zdjęcie. „Zachowaj” dostępne tylko przy istniejącym obrazie.
- Plik JPEG/PNG (FileSelector) wymagany wyłącznie przy wgrywaniu nowego obrazu.
- Przyciemnienie: 35–80%, domyślnie 50% (NumberSelector).
- Punkt kadru X/Y: 0–100%, domyślnie 50/50 (NumberSelector).
- Zapisz / Anuluj; brak pola pliku przy ponownej edycji nigdy nie oznacza samoczynnego usunięcia zdjęcia.

W tej wersji wybór zdjęcia korzysta z systemowego wyboru pliku, nie z własnej galerii. Nie obiecujemy przeciągania kadru lub symulacji stanów dashboardu w formularzu. Kadr ustala się suwakami i ocenia na zegarze po zapisie. Jest to jawne uproszczenie wcześniejszej propozycji, nie utrata uzgodnionej funkcji uploadu.

Zmiana na kolor wyłącza zdjęcie, ale zachowuje ostatni przygotowany obraz dla opcji „Zachowaj”. Nowy upload zastępuje poprzedni; nie powstaje kolekcja ani limit, który można nieodwracalnie zapełnić. Aby wrócić do starszego niż ostatni obrazu, trzeba wybrać plik ponownie.

Options flow ma używać zwykłego OptionsFlow i listenera zmian, nie OptionsFlowWithReload ani automatycznego restartu integracji. Nie dotyka parowania, nazw, obszaru ani opcji niezwiązanych z wyglądem.

## 4. Dane i dostarczenie do zegara

Źródło prawdy: config_entry.options.appearance. Opcje zapisuje administrator; obraz pobiera administrator lub konto HA przypisane do config entry sparowanego zegara. Nie dodajemy komendy sprzętowej do allowlisty.

Proponowany pełny snapshot zdarzenia istniejącej subskrypcji helios/connect:

```json
{
  "type": "appearance",
  "appearance": {
    "version": 1,
    "theme": "warm_graphite",
    "background": {
      "type": "solid"
    }
  }
}
```

Wariant image ma background.type=image, image_id (64 małe znaki hex, SHA-256 przygotowanej zawartości), dim, focus_x i focus_y. Tło solid nie ma tych pól. Brak opcji oznacza jawny pełny snapshot wartości domyślnych. Nowe zdarzenie nie zmienia numeru schematu Lovelace; version=1 dotyczy tylko kontraktu appearance.

Pełny snapshot wysyłany po connected oraz po poprawnym zapisie opcji. Nie jest deltą. Bez osobnego heartbeat, seq, session_id lub potwierdzania jak komendy lampki. Zegar waliduje atomowo; błąd lub nieznana wersja zachowuje ostatni poprawny wygląd i daje diagnostykę.

Dotychczasowy klient ignoruje nieznany typ zdarzenia; sprawdzić tę kompatybilność testem. Nowy klient ze starszą integracją bez zdarzenia używa domyślnego wyglądu albo własnego poprawnego cache, bez blokowania dashboardu.

Cache konfiguracji należy do tożsamości HA i sparowanego urządzenia. Odłączenie sieci go nie usuwa. Usunięcie parowania lub przejście do innego HA unieważnia prywatne zdjęcie i opcje. Po reconnect nowy snapshot zastępuje cache. Nieznane typy zdarzeń nie mogą uruchamiać komend.

## 5. Plik i prywatność

Upload używa FileSelector/file_upload HA; Helios nie implementuje własnego POST uploadu. Identyfikator tymczasowego pliku jest zużywany przez process_uploaded_file w executorze. Nie zapisywać tymczasowej ścieżki ani identyfikatora uploadu jako trwałego image_id.

Integracja weryfikuje JPEG/PNG, maks. 10 MiB pliku, 24 mln pikseli i 8192 na osi; filtr w kontrolce nie zastępuje walidacji. Limit Heliosa sprawdzany jest po standardowym uploadzie — nie twierdzimy, że ogranicza wcześniej transfer całego HA. Odrzucać uszkodzenia i animacje. Błąd zostawia poprzedni obraz i opcje, a formularz umożliwia ponowny wybór pliku.

Normalizacja w executorze: orientacja EXIF, usunięcie metadanych przez utworzenie nowego obrazu z pikseli, spłaszczenie przezroczystości, zachowanie proporcji i zmniejszenie do obwiedni 1600×960, JPEG maks. 2 MiB. Nie utrzymywać surowego oryginału po zakończeniu przetwarzania. Standardowy upload nie jest obietnicą normalizacji ani kontroli EXIF.

Używamy Pillow dostarczanego przez rdzeń HA; nie dodawać osobnego Pillow ani jego przypiętej wersji w requirements manifest.json integracji. Zachować zgodność z ograniczeniami pakietów HA. Obecność Pillow potwierdza [lista zależności rdzenia HA 2026.8.3](https://github.com/home-assistant/core/blob/2026.8.3/pyproject.toml).

Przechowywanie prywatne per config entry: aktualny przygotowany obraz oraz co najwyżej plik kandydujący do zapisu. Bez współdzielenia pomiędzy zegarami i bez skanowania referencji Lovelace. Zapis pliku i opcji ma zostawiać po błędzie starą poprawną parę; sprzątanie po starcie usuwa osierocone pliki należące wyłącznie do tego magazynu. Równoległe zapisy tego samego urządzenia serializować.

Jeden proponowany, nowy endpoint integracji: GET /api/helios/appearance/{entry_id}/{image_id}. Wymaga uwierzytelnienia i sprawdzenia właściciela wpisu (lub administratora). Odrzucać obcą parę wpis/obraz, traversal i zasób nieaktualny. Nie używać /local ani image_upload/serve. Zwracać image/jpeg; nigdy sekretu w adresie. Nie ma osobnej biblioteki, API listowania, kasowania zdjęć ani POST Heliosa.

image_id w URL służy wersjonowaniu cache, nie wybieraniu historycznego pliku: serwer zwraca wyłącznie bieżący przygotowany obraz danego wpisu i odrzuca nieaktualny identyfikator odpowiedzią 404. Identyfikator nie zastępuje uwierzytelnienia ani autoryzacji.

Zegar buduje adres na skonfigurowanym origin HA, z tokenem tylko w nagłówku i wyłączonymi przekierowaniami. Nie przyjmuje dowolnego URL z opcji. Limit odpowiedzi 2 MiB, timeout 10 s łącznie. Plik zbyt duży, nieprawidłowy, 401/403/404 lub brak sieci daje fallback, nie pętlę pobrań. Obrazy MA mają osobny cache i nigdy nie otrzymują tokenu HA.

Porzucone, nieprzesłane formularze mogą zostawiać pliki tymczasowe w zarządzanym przez HA file_upload. Nie obiecywać ich natychmiastowego sprzątnięcia przez Heliosa, jeśli nie otrzymał identyfikatora. To zachowanie trzeba sprawdzić w próbie formularza; nie dodawać własnego skanowania cudzych plików.

## 6. Renderowanie bez restartów

Układ, godzina i powiadomienia nie czekają na zdjęcie. Motyw i kolor można zastosować natychmiast po poprawnym snapshotcie, obraz dopiero po pobraniu i walidacji. Przy błędzie zachować ostatnie poprawne tło, a bez niego kolor motywu i dyskretny komunikat.

Przy zapisie wyglądu nie restartować muzyki, głosu, parowania, klienta HA ani całej aplikacji. Nowe ustawienia nie wywołują lovelace_updated.

Zdjęcie wypełnia ekran przez crop z wybranym punktem kadru; punkt ograniczony tak, aby nie powstały puste pasy. Uchwyt i panel muzyki nadal nakrywają prawą stronę, bez przestawiania siatki. Okładka muzyki nie staje się automatycznie tapetą.

Stała czarna osłona zdjęcia ma wybrany poziom 35–80%. Kafle informacyjne mają 92% krycia powierzchni motywu. Zegar ma własną stałą osłonę czerni 70% nad już przyciemnionym zdjęciem. Pasek i panel muzyki 100%. Kontrast 4,5:1 sprawdzić statycznie dla skrajnie białego i czarnego tła oraz obu palet; w razie potrzeby zwiększyć stałe krycie w projekcie, nie mierzyć zdjęcia w runtime.

Oczekiwany efekt: przy pełnej siatce zdjęcie jest widoczne przede wszystkim w pustych polach i odstępach 8 jednostek; pod kafelkami i zegarem pozostaje bardzo subtelne. Przy domyślnym przyciemnieniu 50% oraz osłonie zegara 70% udział zdjęcia pod zegarem wynosi 15%. W obecnym układzie największą odsłoniętą częścią jest zwykle dolny rząd po ukryciu powiadomień. To zamierzony kompromis czytelności, nie objaw niedziałającego tła; nie obiecywać fotografii widocznej jak na pustym ekranie.

W stanie ustalonym: jeden plik obrazu w cache (do 2 MiB) i jedna bitmapa wynikowa 800×480 (około 1,46 MiB ARGB_8888). Podczas atomowej podmiany dozwolony drugi plik tymczasowy oraz krótkotrwały bufor dekodowania/kandydat; usunąć poprzednią bitmapę po przełączeniu referencji. Nie deklarować „jednej bitmapy przez cały czas”, jeśli implementacja potrzebuje dwóch do bezpiecznej podmiany. Zmierzyć szczyt RAM.

Pobieranie/dekodowanie poza UI, jedno żądanie na image_id, odrzucanie spóźnionego wyniku po zmianie opcji/tożsamości. Bez dekodowania przy każdej sekundzie zegara. Ponowienie po reconnect lub ponownym zapisie. Offline zachowuje tło po restarcie aplikacji; ręczny wybór koloru unieważnia oczekujące pobranie.

## 7. Bramka przed implementacją

Najpierw izolowana próba, na niesensytywnym obrazie testowym:
1. FileSelector faktycznie działa w options flow używanej wersji HA: przede wszystkim z aplikacji Home Assistant Companion na telefonie użytkownika (wybór zdjęcia z galerii), a w drugim przypadku z przeglądarki na komputerze. Sprawdzić wartość file_id, odczyt przez process_uploaded_file, anulowanie i ponowny upload; test samej przeglądarki desktopowej nie zamyka bramki.
2. Formularz zachowuje opcje niezwiązane z appearance; brak pliku oznacza zachowanie, a wyłączenie zdjęcia jest jawne.
3. Zwykły OptionsFlow i update listener aktualizują dane bez reload; snapshot dociera istniejącą subskrypcją bez zmiany device_id.
4. Nowy endpoint wymaga tokenu i prawa do właściwego urządzenia; inny użytkownik i anonimowe żądanie nie pobierają obrazu.
5. Przygotowana fotografia ma jakość wystarczającą na 800×480; brak ograniczenia do miniatury 512.

Do zamknięcia bramki potrzebne wyniki próby, nie sama lektura kodu. Jeśli FileSelector nie zapewnia wymaganego przepływu, opisać konkretny brak i wrócić do decyzji o alternatywie; nie wdrażać automatycznie większego panelu.

## 8. Odbiór i pliki

Integracja (osobne repo ha-helios): config_flow, manifest zależności file_upload, listener opcji, snapshot appearance w istniejącym websocket/coordinator, magazyn jednego obrazu i chroniony widok HTTP. APK: HeliosDeviceClient, serwis/cache obrazów i renderer; DashboardSpec pozostaje bez nowych pól.

Testy: wszystkie pola i wartości graniczne, błędna wersja snapshotu, dwa zegary na jednym dashboard_path z różnymi tłami, zdjęcie jasne/ciemne/pionowe, EXIF i plik uszkodzony, limity, przerwany zapis, utrata sieci, restart, brak dostępu, spóźniony wynik i niezmieniona sesja muzyczna podczas zapisu. Sprawdzić, że oryginał nie pozostaje w magazynie Heliosa, liczba plików i RAM nie rosną po 20 podmianach.

Nie modyfikować dokumentu Lovelace ani config entry parowania. Instalacja bez resetu parowania; wycofanie nowego APK daje domyślny motyw i nie wymaga cofania schematu dashboardu. Zapis wyglądu nie przerywa muzyki. Wdrożenie dopiero po akceptacji projektu i zaliczeniu bramki.
