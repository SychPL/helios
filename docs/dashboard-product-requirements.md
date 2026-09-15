# Wymagania produktowe dashboardu Helios

Status: zaakceptowany kierunek rozwoju; pierwszy etap zaimplementowano jako Helios 0.5.0. Dokument zapisuje ustalenia z rozmowy z użytkownikiem z 2026-09-15. Nie opisuje bieżących możliwości wersji 0.4.0.

Implementowalny opis pierwszego etapu: [SPEC 0.5 — dashboard konfigurowany z Home Assistant](SPEC-0.5-ha-configurable-dashboard.md).

Potwierdzona wcześniej lokalna możliwość sprzętowa, poza zaakceptowanym zakresem sześciu elementów: [sterowanie lampką docka](lamp-control.md).

Następny etap: [SPEC 0.6 — lokalny odtwarzacz i pilot Music Assistant](SPEC-0.6-music-assistant.md).

Równoległy etap: [SPEC 0.7 — urządzenie Helios w Home Assistant](SPEC-0.7-home-assistant-integration.md): wersja, lampka, ładowanie i przypisanie do obszaru. Domyślny cel polecenia bez nazwy pokoju ma wynikać z obszaru zegara; identyfikacja mówcy jest opcjonalnym kierunkiem badawczym, nie gotową funkcją.

## Zakres i rozróżnienie pojęć

- **Menu zegara** oznacza lokalne menu systemowe Heliosa, służące między innymi do powrotu, zamykania paneli i wejścia do ustawień.
- **Dashboard** oznacza główną powierzchnię użytkową zegara: zegar, pogodę, informacje i elementy sterujące urządzeniami.
- Menu zegara ma być stałą częścią aplikacji. Jego pozycje nie mają być pobierane z Home Assistant ani edytowane jako karty HA.
- Zawartość dashboardu ma być konfigurowana z Home Assistant. Stałe menu systemowe i konfigurowalny dashboard są osobnymi warstwami.

## Zaakceptowane wymagania

### Sypialnia i pogoda wieczorna — projekt kolejnego przyrostu

[SPEC 0.9 — rolety, światło i pogoda na jutro](SPEC-0.9-bedroom-dashboard.md) opisuje stały kafelek otwierający osobne sterowanie dwiema roletami sypialni, światło widoczne po zmroku lub przy zamkniętej rolecie oraz wieczorną prognozę na jutro w istniejącym polu pogody. Projekt przyjmuje próg 18:00 dla prognozy i zamknięcie co najmniej jednej rolety dla światła; te doprecyzowania podlegają recenzji. Dolny rząd powiadomień pozostaje bez zmian. Dokument nie oznacza implementacji ani wdrożenia; proponowany schemat 4 dotyczy nowych funkcji, nie wyglądu z 0.8b.

### Redesign i własne tło — kolejny przyrost

Użytkownik zlecił spisanie [SPEC 0.8 — mapa etapów](SPEC-0.8-dashboard-appearance.md). Po recenzji podzielono zakres na [0.8a — poprawki muzyki i redesign](SPEC-0.8a-renderer-music.md) oraz [0.8b — tła z HA](SPEC-0.8b-backgrounds.md). Zakres: cieplejszy wygląd, większa godzina i data, wybór lub wgranie zdjęcia w HA, czytelna prawa nakładka muzyki oraz naprawa metadanych i znikania po pauzie. Dla teł proponowany jest formularz opcji per urządzenie, bez nowego schematu YAML; obsługa uploadu wymaga próby na używanej wersji HA. Dokumenty są do recenzji, nie oznaczają instalacji ani gotowego edytora. Cztery dolne powiadomienia i wykluczenia encji pozostają bez zmian.

### Dashboard wymagający uwagi — ustalenia z 15 września

- Minimalistyczny wygląd. Część kafelków pojawia się tylko wtedy, kiedy jest istotna informacja, np. otwarty garaż; muzyka pozostaje prawą nakładką, bez przesuwania kafelków.
- Powiadomienia zajmują ostatni, dolny rząd, po 1×1 każde. Bieżący zestaw: światła, garaż, blaszak, dostawa Wikinga. Garaż i blaszak są osobnymi kafelkami, a nie jednym zbiorczym tekstem. Każdy ma niezależny warunek widoczności.
- Światła obejmują wyłącznie edytowalną grupę obserwowaną: piwnica, garaż, kuchnia, tylko drugi kanał modułu tarasu i podbitka. Pierwszy kanał tarasu zasila kosiarkę i jest wykluczony, podobnie jak kule świecące nocą i stale świecące półki w salonie. W rejestrze ZHA kanał 2 ma encję `light.living_room_terrace_light_swiatlo` (endpoint 2), a encja z końcówką `_2` oznacza kanał 1 (endpoint 1); nie wnioskować kanału sprzętowego z numeru w nazwie encji. Grupy i ich członkowie nie mogą podwajać licznika.
- Wykrycie `blaszak2` jest błędne i nie może wywoływać kafelka ani powiadomień; pozostaje wyłącznie klasyfikacja `blaszak`. Garaż w tym informacyjnym dashboardzie korzysta z klasyfikacji kamery: `open` pokazuje kafelek, `close` go ukrywa. Nie porównujemy jej z `cover.brama` i nie dodajemy na tej podstawie sterowania bramą.
- Informacja o wykryciu Wikinga ma korzystać z istniejących zdarzeń HA/Frigate, nie ze stanu lampki w toalecie. Docelowo potwierdzenie odbioru kończy oczekujące powiadomienie; wykrycie kuriera samo w sobie nie dowodzi dostarczenia paczki.
- Docelowo lampka zegara miga przy niepotwierdzonych powiadomieniach również nocą. To wymóg do implementacji i odbioru, nie potwierdzona funkcja. Wykluczenie basenu oraz wybranych świateł obowiązuje również przy generowaniu jego powiadomień.
- Basen/jacuzzi istnieje na dashboardzie tylko przy połączeniu urządzenia Bestway, według jego czujnika `Spa Connected`. Teraz sprzęt jest schowany. Sam stan encji zasilania albo klasyfikacja kamery nie wystarczają do włączenia kafelka.
- Stan nieznany lub sprzeczny nie może być pokazywany jako pewne otwarcie/zamknięcie. Zwłaszcza brama i wiatrołap wymagają ustalenia wiarygodnego źródła przed dodaniem akcji.

Pierwszy przyrost dashboardu zapisano w HA bez zmiany APK: [raport wdrożenia i ograniczenia](../artifacts/attention-dashboard-20260915.md). Rozwijana lista świateł, trwała kolejka powiadomień, potwierdzanie i miganie nie są częścią tego przyrostu.

### Konfiguracja całego dashboardu

- Home Assistant ma konfigurować cały dashboard, a nie tylko obecną listę maksymalnie trzech wskaźników.
- Konfiguracja ma obejmować również zegar, temperaturę i pogodę oraz pozostałe elementy ekranu.
- Użytkownik ma móc dobierać co najmniej następujące rodzaje elementów: zegar, pogoda, informacje, światła, rolety i brama garażowa.
- Home Assistant ma sterować widocznością elementów zależnie od kontekstu i automatyzacji. Przykłady: wieczorem pokazanie informacji o otwartej bramie, a w dzień ostrzeżenie o nadchodzącym deszczu.

### Dotyk i akcje Home Assistant

- Element dashboardu może być interaktywny i po dotknięciu wywoływać akcję w Home Assistant.
- Element może opcjonalnie wymagać potwierdzenia. Przykład: dotknięcie ikony otwartej bramy pokazuje pytanie o jej zamknięcie, a dopiero potwierdzenie wywołuje odpowiednią akcję HA.
- Anulowanie lub zamknięcie potwierdzenia nie może wykonać akcji.

### Nawigacja bez przycisków Androida

- Urządzenie nie udostępnia użytkownikowi systemowych przycisków nawigacji Androida, dlatego każdy ekran, panel i menu musi zapewniać widoczny sposób powrotu albo zamknięcia.
- Każdy dialog potwierdzenia musi zapewniać widoczną opcję anulowania.
- Dotknięcie poza panelem lub dialogiem może go zamykać, ale nie może przy tym wykonać akcji przypisanej do potwierdzenia.

## Preferowany sposób konfiguracji

- Docelowo użytkownik preferuje wizualną edycję dashboardu w Home Assistant, podobną do edycji standardowych pulpitów HA.
- W pierwszym etapie dopuszczalna jest konfiguracja w YAML.
- Przejście od YAML do edytora wizualnego nie powinno wymagać zmiany znaczenia zapisanej konfiguracji ani przebudowy samego dashboardu od zera.

## Zaakceptowany zakres pierwszego etapu

### Układ i elementy

- Dashboard ma mieścić się na jednym ekranie bez przewijania.
- Treść ma używać prostej siatki z dużymi polami dotykowymi.
- Pierwszy etap obejmuje sześć typów elementów: zegar, pogodę, informację o encji, światło, roletę i bramę.
- YAML przechowywany po stronie Home Assistant ma określać kolejność, rozmiar, podpis, ikonę, widoczność i akcję dotknięcia elementu.
- Format konfiguracji pierwszego etapu ma nadawać się do późniejszego obsłużenia przez edytor wizualny.

### Widoczność i logika

- Warunki kontekstowe mają być obliczane w Home Assistant, nie na zegarze.
- Konfiguracja dashboardu ma wskazywać wynik takiej logiki, na przykład pomocnika HA określającego, czy pokazać wieczorem otwartą bramę. Zegar ma jedynie odczytywać wynik i zgodnie z nim pokazywać albo ukrywać element.

### Sterowanie

- Światło ma obsługiwać proste włączenie i wyłączenie.
- Roleta ma obsługiwać otwarcie, zamknięcie i zatrzymanie.
- Brama ma obsługiwać polecenie zamknięcia.
- Potwierdzenie akcji ma być konfigurowalne i domyślnie włączone dla bramy.

### Zachowanie bez połączenia

- Po utracie połączenia ma pozostać widoczny ostatni poprawny układ dashboardu.
- Dane zależne od Home Assistant mają być jednoznacznie oznaczone jako nieaktualne.
- Polecenia sterujące wydane bez połączenia nie mogą być kolejkowane do późniejszego wykonania.

### Zakres odłożony na później

- Wizualny edytor konfiguracji w Home Assistant.
- Wiele ekranów dashboardu.
- Bardziej rozbudowane typy elementów i interakcje wykraczające poza prosty zakres opisany wyżej.

## Stały górny pasek

- Nad konfigurowalną treścią dashboardu ma znajdować się stały, nieedytowalny z Home Assistant pasek w obszarze zajmowanym obecnie przez napis HELIOS i ikonę HA.
- Pasek ma służyć do prezentowania statusów i powiadomień aplikacji, na przykład informacji, że Nabu słucha.
- Treść dashboardu poniżej paska pozostaje konfigurowalna z Home Assistant.
- Dokładny zestaw komunikatów, sposób ich prezentacji oraz priorytety przy jednoczesnych statusach nie zostały jeszcze ustalone.

## Propozycja techniczna, nie wymaganie użytkownika

- Aktualizowanie widocznego stanu ikony dopiero po otrzymaniu informacji zwrotnej z Home Assistant jest propozycją asystenta. Wymaga osobnej decyzji projektowej; nie należy przedstawiać go jako zaakceptowanego zachowania.

## Otwarte decyzje

- Nie ustalono jeszcze dokładnego modelu wizualnego edytora ani tego, w jakim stopniu ma wykorzystywać standardowe karty i mechanizmy Lovelace.
- Nie ustalono, czy docelowy renderer dashboardu pozostanie natywnym widokiem Androida, czy zostanie zastąpiony innym rozwiązaniem.
- Nie ustalono jeszcze dokładnego kontraktu YAML/JSON. Zakres pierwszego etapu wymaga jednak pól opisujących kolejność, rozmiar, podpis, ikonę, widoczność, akcję dotknięcia i opcjonalne potwierdzenie.

## Stan historyczny 0.4 i luka zamknięta w 0.5

- W 0.4 `MainActivity` tworzyło natywny `DashboardView` ze stałym układem 800×480 zapisanym w Javie.
- W 0.4 `HaDashboardClient` pobierał sekcję `helios` oraz widok `menu-zegara`.
- Sekcja `helios` wersji 1 obsługiwała zegar, pogodę i maksymalnie trzy wskaźniki, nie konfigurację całego dashboardu.
- W wersji 0.4 pozycje menu pochodziły z kart HA w widoku `menu-zegara`. W 0.5 zastąpiono je stałym lokalnym menu i wdrożono konfigurowalną siatkę zgodnie z SPEC 0.5.
- Dokumentacja wersji 0.4 opisuje stan już zaimplementowany i pozostaje materiałem historycznym. Niniejszy dokument wyznacza późniejszy kierunek produktu i ma pierwszeństwo przy projektowaniu kolejnej wersji.

## Kryteria przyszłego odbioru

- Menu systemowe działa i umożliwia bezpieczne opuszczenie każdego widoku także bez połączenia z HA.
- Zmiana konfiguracji w HA pozwala dodawać, usuwać i układać obsługiwane rodzaje elementów dashboardu bez aktualizacji APK.
- Automatyzacje HA mogą wpływać na widoczność elementów.
- Dotyk elementu może wywołać akcję bezpośrednio albo przez opcjonalne potwierdzenie.
- Każdy panel i dialog można zamknąć lub anulować bez przypadkowego wykonania akcji.
