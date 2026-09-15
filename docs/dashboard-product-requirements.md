# Wymagania produktowe dashboardu Helios

Status: zaakceptowany kierunek rozwoju; pierwszy etap zaimplementowano jako Helios 0.5.0. Dokument zapisuje ustalenia z rozmowy z użytkownikiem z 2026-09-15. Nie opisuje bieżących możliwości wersji 0.4.0.

Implementowalny opis pierwszego etapu: [SPEC 0.5 — dashboard konfigurowany z Home Assistant](SPEC-0.5-ha-configurable-dashboard.md).

Potwierdzona wcześniej lokalna możliwość sprzętowa, poza zaakceptowanym zakresem sześciu elementów: [sterowanie lampką docka](lamp-control.md).

## Zakres i rozróżnienie pojęć

- **Menu zegara** oznacza lokalne menu systemowe Heliosa, służące między innymi do powrotu, zamykania paneli i wejścia do ustawień.
- **Dashboard** oznacza główną powierzchnię użytkową zegara: zegar, pogodę, informacje i elementy sterujące urządzeniami.
- Menu zegara ma być stałą częścią aplikacji. Jego pozycje nie mają być pobierane z Home Assistant ani edytowane jako karty HA.
- Zawartość dashboardu ma być konfigurowana z Home Assistant. Stałe menu systemowe i konfigurowalny dashboard są osobnymi warstwami.

## Zaakceptowane wymagania

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

## Stan bieżący i luka względem wymagań

- `MainActivity` tworzy natywny `DashboardView`, którego układ jest zaprojektowany dla ekranu 800×480 i zapisany w Javie.
- `HaDashboardClient` pobiera z panelu HA sekcję `helios` oraz widok `menu-zegara`.
- Bieżąca sekcja `helios` obsługuje zegar, pogodę i maksymalnie trzy wskaźniki. Nie stanowi jeszcze konfiguracji całego dashboardu opisanej w tym dokumencie.
- W wersji 0.4 pozycje menu zegara pochodzą z kart HA w widoku `menu-zegara`. To zachowanie jest sprzeczne z nowym wymaganiem stałego, lokalnego menu i będzie wymagało zmiany w przyszłej implementacji.
- Dokumentacja wersji 0.4 opisuje stan już zaimplementowany i pozostaje materiałem historycznym. Niniejszy dokument wyznacza późniejszy kierunek produktu i ma pierwszeństwo przy projektowaniu kolejnej wersji.

## Kryteria przyszłego odbioru

- Menu systemowe działa i umożliwia bezpieczne opuszczenie każdego widoku także bez połączenia z HA.
- Zmiana konfiguracji w HA pozwala dodawać, usuwać i układać obsługiwane rodzaje elementów dashboardu bez aktualizacji APK.
- Automatyzacje HA mogą wpływać na widoczność elementów.
- Dotyk elementu może wywołać akcję bezpośrednio albo przez opcjonalne potwierdzenie.
- Każdy panel i dialog można zamknąć lub anulować bez przypadkowego wykonania akcji.
