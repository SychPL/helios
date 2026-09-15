# Specyfikacja Helios 0.4 — spokojny ekran i menu edytowane w HA

Status: zaimplementowano i zweryfikowano build/lint, testami JVM oraz w emulatorze; instalacja 0.4 na zegarze oczekuje na użytkownika. Specyfikację zapisano przed implementacją, 2026-09-15.

## Cel i zachowanie

Ekran zegara ma służyć do odczytu godziny, pogody i ostrzeżeń. Rozmowę rozpoczyna „Okay Nabu”. Usuwamy widoczne przyciski Menu i Porozmawiaj oraz stale widoczne instrukcje rozmowy. Podczas rozmowy pojawia się krótki status; po niej znika. Nasłuch lokalny wraca po zakończeniu TTS. Fizyczny przełącznik służy do wyciszenia mikrofonu.

Menu otwiera wyłącznie przytrzymanie HELIOS. Usuwamy także gest od krawędzi, aby przypadkowy dotyk nie pokazywał menu. Ikona HA w prawym górnym rogu jest niebieska przy aktualnym połączeniu konfiguracji/wskaźników, szara przy łączeniu lub błędzie. Bez napisu „połączono”. Opis dostępności wyjaśnia stan. Ostrzeżenie o nieaktualnych danych pozostaje jawne: szara ikona sama nie wystarcza do stwierdzenia stanu garażu.

## Decyzja: edytor HA zamiast dodatku serwerowego

Dodatek serwerowy HA wprowadza dodatkowy proces, instalację, uprawnienia i utrzymanie. Do edytowania listy skrótów wystarcza wbudowany wizualny edytor kart `button`. W dedykowanym panelu HA dodajemy zakładkę o stałym identyfikatorze `menu-zegara`, z kartami w `grid`. Helios odczytuje ich kolejność, nazwy i akcje. HA zapisuje je we własnym magazynie; zapis generuje istniejące zdarzenie odświeżenia. Komputer nie uczestniczy w codziennej pracy konfiguracji.

Alternatywa na przyszłość: własna karta Lovelace z `getConfigElement()` dla edycji całego ekranu (w tym reguł wskaźników). Nie jest potrzebna do wizualnej edycji menu w tej wersji. Edytor wskaźników pozostaje YAML z 0.3.

## Kontrakt konfiguracji menu

Źródło: `views` w panelu `helios-clock`, widok z `path: menu-zegara`. Nie dodajemy nowych pól do sekcji `helios`, więc 0.3 nadal może odczytać wskaźniki podczas aktualizacji.

Obsługiwane kontenery: `grid`, `vertical-stack`, `horizontal-stack`; kolejność wg tablic `cards`, limit 20 przycisków i 4 poziomy zagnieżdżenia. Karty `markdown` służą tylko instrukcji w HA. Inne karty i nieobsługiwane akcje zgłaszają błąd menu i nie zastępują ostatniej poprawnej definicji. Brak zakładki przy aktualizacji z 0.3 nie usuwa ostatniego menu; pusta istniejąca zakładka świadomie daje puste menu.

Karta `button` ma `name` (1–60 znaków) i `tap_action`. Obsługujemy `action: url` oraz `action: navigate` z lokalną ścieżką HA. Ikony kart służą orientacji w edytorze HA; menu zegara jest listą tekstową. Akcje urządzenia zapisujemy jako URL:

| URL | Znaczenie na zegarze |
| --- | --- |
| `helios://settings` | Ustawienia Androida |
| `helios://accessibility` | Dostępność / TalkBack |
| `helios://home` | Wybór ekranu głównego |
| `helios://dashboard` | Konfiguracja Heliosa w przeglądarce HA |
| `helios://ha` | Strona główna HA |
| `helios://update` | APK z już skonfigurowanego serwera aktualizacji |
| `helios://apps` | Wybór aplikacji / launchera |
| `helios://app/org.galexander.sshd` | Uruchomienie wskazanego pakietu |
| `helios://talk` | Ręczne rozpoczęcie rozmowy / zgoda na mikrofon |
| `helios://cancel` | Anulowanie bieżącej rozmowy |
| `http://…`, `https://…` | Otworzenie strony w przeglądarce |

Linki `helios://` wykonuje zegar; kliknięcie karty na komputerze nie steruje zdalnie urządzeniem. Edytor HA służy ich konfiguracji. Nie obsługujemy shell, intent URI ani automatycznego wykonania akcji po otrzymaniu YAML. Akcja wymaga dotknięcia pozycji w ukrytym menu na zegarze.

## Zachowanie awaryjne i cykl życia

Menu i konfiguracja wskaźników są walidowane osobno: błąd menu nie wyłącza alarmu garażu. Ostatnie poprawne menu jest zapisane lokalnie i działa bez HA. Zawsze istnieje lokalne zamknięcie panelu — to kontrolka okna, nie konfigurowalny skrót. Przy braku jakiejkolwiek konfiguracji dostępne jest minimalne menu odzyskiwania (ustawienia i HA), jasno oznaczone jako takie. Po otrzymaniu definicji HA wszystkie skróty pochodzą z HA, także ręczna rozmowa.

Pierwsze uruchomienie bez zgody na mikrofon wyświetla systemową prośbę. Odmowa daje czytelny status; ponowienie jest dostępne w pozycji Rozmowa. Aktualizacja definicji odświeża także otwarte menu. Zmiana definicji nigdy sama nie uruchamia aplikacji ani rozmowy.

## Plan realizacji i odbiór

1. Zmiana ekranu, ikona HA, ukrycie wejść dotykowych i statusów spoczynkowych.
2. Parser wizualnych kart menu, walidacja, cache i subskrypcja zmian.
3. Renderer menu oraz jawne mapowanie do standardowych akcji Androida.
4. Dodanie zakładki w istniejącym HA z kopią zapasową, bez zmiany reguły garażu ani innych pulpitów.
5. Testy parsera, odświeżenia, błędów, cache i sesji; build/lint; sprawdzenie ekranu 800×480.
6. Udostępnienie APK 0.4.0. Instalacja na zegarze wymaga zwykłego potwierdzenia użytkownika.

Źródła: [karty button](https://www.home-assistant.io/dashboards/button/), [własne edytory kart](https://developers.home-assistant.io/docs/frontend/custom-ui/custom-card/).
