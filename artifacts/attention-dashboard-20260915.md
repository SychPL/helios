# Dashboard spraw wymagających uwagi — pierwsze wdrożenie

## Korekta źródeł po uwagach użytkownika

Użytkownik potwierdził, że blaszak jest zamknięty, a klasyfikacja `blaszak2` jest błędna. Aktualny kafelek korzysta tylko z `sensor.camera_garden_blaszak_classification`; drugi wynik jest pomijany. Garaż korzysta tylko z `sensor.camera_garage_garage_gate_classification`: `close` ukrywa, `open` pokazuje informacyjne `Kamera: otw.`. Poprzednie `Sprawdź` wynikało z porównania tej klasyfikacji z `cover.brama`; ten warunek usunięto. Nie poprawiano źródłowej integracji ani nie dodawano sterowania.

Istotna poprawka tarasu: odczyt rejestru ZHA wykazał odwrotne przypisanie nazw. Encja `light.living_room_terrace_light_swiatlo_2` jest endpointem 1 modułu, czyli według użytkownika zasilaniem kosiarki. Endpoint 2, który ma być liczony, to `light.living_room_terrace_light_swiatlo`. W grupie Heliosa zastąpiono pierwszą encję drugą, zachowując resztę grupy. Poprzednie akapity utożsamiające suffix `_2` z drugim kanałem są historią błędnego założenia, nie bieżącą konfiguracją.

Zaktualizowano żywą grupę oraz własne szablony świateł, garażu i blaszaka, z kopią formularzy w `.local/attention-channel-correction-*.json`. Nie przełączano żadnego obwodu. Weryfikacja HA: poprawny endpoint 2 w grupie, endpoint 1 wykluczony, garaż i blaszak niewidoczne, lista wybranych zapalonych świateł pusta. Próby szablonów potwierdziły ignorowanie `blaszak2 == open` i ukrycie garażu przy `close`, niezależnie od `cover.brama`.

## Aktualizacja: cztery kafelki w dolnym rzędzie

Na życzenie użytkownika 2026-09-15 około 18:07 rozdzielono garaż i blaszak. Bieżący dolny rząd to cztery niezależnie warunkowe kafelki 1×1: światła, garaż, blaszak, Wiking. Zegar i pogoda nie zmieniły geometrii. Każdy kafelek znika, gdy jego warunek nie jest spełniony; nie przesuwamy pozostałych.

Nowe pomocniki garażu i blaszaka zachowują osobne stany. Garaż pokazuje `Sprawdź` przy sprzeczności źródeł, a nie pewny stan otwarcia. Kafelek blaszaka wskazuje numer otwartego obiektu; gdy oba są otwarte, pokazuje `1 i 2 otw.`. Dostawa ma podpis `Wiking był`, a wartością jest sama godzina, aby zmieścić się w 1×1. Światła nadal obejmują tylko taras 2, bez pierwszego obwodu, kul i półek.

Zgodnie z wybranym zestawem czterech pozycji drzwi i basen nie są teraz osobno prezentowane. Stary pomocnik zbiorczy otwarć oraz poprzedni tekst Wikinga pozostają w HA, ale nie są używane przez ten dashboard; nie usunięto ich ani cudzych odwołań. Nie dodano migania ani nowych akcji dotykowych.

Zapis i odczyt potwierdzone w HA, a wygląd na fizycznym zegarze: `.local/helios-attention-bottom-row.jpg`. Kopia poprzedniego układu: `.local/attention-dashboard-backup-20260915T160650Z.json`. Próby szablonów: sprzeczność i zamknięcie garażu, brak/jeden/dwa otwarte blaszaki, nieaktywny/aktywny kurier i krótka godzina. Walidacja potwierdziła cztery pola 1×1 w trzecim rzędzie bez nakładania. Poniżej pozostaje historia pierwszego wdrożenia.

Wdrożono 2026-09-15, około 17:37 czasu lokalnego. Zapis odczytano ponownie z HA i potwierdzono na zrzucie fizycznego zegara. Bez instalacji APK, restartu HA, uruchamiania usług sprzętowych lub modyfikowania istniejących automatyzacji. Nie jest to odbiór systemu trwałych powiadomień ani migania lampką.

## Wdrożone

- Zachowany duży zegar 2×2, pogoda i istniejący wygląd. Schemat dashboardu nadal `version: 2`; pozostałe pola dokumentu Lovelace, w tym `views`, zachowane.
- Trwała grupa `light.helios_swiatla_do_sprawdzenia`: piwnica, garaż, kuchnia, taras i podbitka. Bez kul, półek w salonie i grupy całego oświetlenia zewnętrznego. Skład jest edytowalny przez pomocniki HA.
- Kontrola po publikacji wykryła nieistniejącego członka starej grupy podbitki. Nie zmieniono tej starej grupy; w nowej grupie Heliosa użyto bezpośrednio dwóch istniejących obwodów podbitki. Końcowa kontrola potwierdziła 10 unikalnych i dostępnych obwodów w pięciu lokalizacjach.
- Kafelek zapalonych świateł: liczba włączonych obwodów i nazwy lokalizacji, tylko gdy są włączone wybrane lampy. Grupy rozwijane do członków, pomieszczenia bez powtórzeń. To obserwacja włączenia, nie automatyczna ocena, że światło zostało zapomniane.
- Kafelek otwarć: drzwi wejściowe, klasyfikacje garażu i blaszaków. Rozbieżność `cover.brama` oraz kamery daje `Garaż?`, bez przycisku zamknięcia. Nie użyto sprzecznych encji wiatrołapu.
- Basen uwzględniany tylko przy `binary_sensor.basen_spa_connected == on` i klasyfikacji przykrycia `open`. Stan przełącznika zasilania nie stanowi warunku dostępności. Klasyfikacja kamery nie jest zabezpieczeniem ani dowodem bieżącego stanu fizycznego.
- Kafelek Wikinga widoczny tylko przy istniejącym `input_boolean.night_courier_active == on`; godzina pochodzi z ostatniej zmiany tego pomocnika, nie z analizy czasu dostarczenia paczki.
- Sześć trwałych pomocników szablonowych: trzy teksty i trzy warunki widoczności. Brak danych i pusty tekst nie są pokazywane jako wykryta dostawa.

Stan podczas odbioru: `1 · Taras`, `Garaż? · Blaszak 2`; dostawa ukryta, basen wykluczony przez brak połączenia. Układ, polskie znaki, pogoda i status połączenia wyświetlone poprawnie.

## Granice tego przyrostu

Bieżące przypisanie po sprawdzeniu rejestru ZHA: licznik obejmuje wyłącznie sprzętowy kanał tarasu 2, czyli `light.living_room_terrace_light_swiatlo`. Encja `light.living_room_terrace_light_swiatlo_2` jest kanałem 1, zasila kosiarkę i jest wykluczona. Docelowy zestaw ma 9 obwodów. Kopia `.local/attention-terrace-backup-*.json` dotyczy wcześniejszej korekty opartej na mylącym sufiksie; właściwą korektę kanałów dokumentują `.local/attention-channel-correction-*.json` i początek raportu. Liczba 10 w opisie pierwszego odbioru pozostaje informacją historyczną. Ta redakcja raportu nie oznacza kolejnej zmiany w HA.

- Kafelki informacyjne (pierwotnie trzy, po rozdzieleniu cztery) są tylko do odczytu. Dotknięcie nie otwiera jeszcze listy ani przycisków `Odebrane` / `Wyjęte`. Nie podszyto takich akcji pod przełączanie światła.
- Warunkowe kafelki zostawiają wolne miejsca; nie przebudowują siatki. Długie listy są ucinane przez obecny renderer.
- Lampka nie została uruchomiona ani skonfigurowana do migania. Integracja `helios` nie miała config entry podczas preflight; jej wdrożenie i odbiór pozostają osobną pracą Claude Code. Wymaganie migania również nocą zapisano w wymaganiach produktu.
- Nie zmieniono automatyzacji kuriera: jej warunek menu wskazuje obecnie niedostępne encje, a reset ma również wyzwalacz po 10 minutach. Ten kafelek nie gwarantuje trwałego powiadomienia do ręcznego odbioru.
- Nie wdrożono potwierdzania prania, przypomnień o śmieciach ani alarmów baterii. Kalendarz `calendar.smieci` nie istnieje w odczytanych stanach. Przejście pralki z pracy do zatrzymania nie wystarcza do rozróżnienia końca cyklu i anulowania.
- Klasyfikacje kamer mogą być stare; brak potwierdzonego heartbeat detekcji. Sprzeczne wskazania wymagają ustalenia źródła prawdy przed dodaniem sterowania bramą. Brak sprawdzonych czujników okien.
- Niedostępne lampy nie są liczone jako włączone; licznik nie jest deklaracją, że cały dom jest zgaszony. Po korekcie podbitki wszystkie wybrane obwody były dostępne.

## Pliki i odtwarzalność

- Manifest: [helios-attention.yaml](../ha/helios-attention.yaml).
- Wdrożenie: [deploy_attention_dashboard.py](../tools/deploy_attention_dashboard.py). Bez argumentów tylko preflight; `--apply` tworzy brakujące pomocniki i podmienia tylko sekcję `helios` po ponownym sprawdzeniu, że dokument nie zmienił się równolegle.
- Skrypt nie przejmuje istniejącego pomocnika o tej samej nazwie bez lokalnego dziennika jego utworzenia. Powtórne uruchomienie zachowuje ustawienia pomocników zmienione przez użytkownika, zamiast je nadpisywać. Manifest jest zestawem początkowym, nie synchronizatorem zmian istniejących helperów.
- Kopia przed zapisem: `.local/attention-dashboard-backup-20260915T153720Z.json`.
- Dziennik utworzonych config entries: `.local/attention-deployment-20260915T153720Z.json`.
- Kopia formularzy konfiguracji dwóch własnych pomocników przed korektą podbitki: `.local/attention-helper-options-before-fix.json`.
- Odczyt po zapisie: `.local/attention-dashboard-live.json`.
- Fizyczny ekran: `.local/helios-attention-physical.jpg`.

Przy wycofaniu przywrócić wyłącznie sekcję `helios` z kopii, po porównaniu z obecnym dokumentem, aby nie utracić późniejszych zmian użytkownika. Utworzone pomocniki można pozostawić albo usunąć przez HA po sprawdzeniu odwołań; skrypt nie usuwa automatycznie cudzych konfiguracji. Błąd podczas tworzenia pomocników pozostawia poprzedni dashboard, a utworzone już pomocniki są zapisane w dzienniku.

## Weryfikacja

- Walidacja składni skryptu i YAML, granic siatki, braku nakładania oraz typów tylko do odczytu.
- Próby szablonów bez mutacji HA: pusta lista, dwa obwody w jednym pomieszczeniu, basen odłączony/połączony, sprzeczna/zamknięta brama, nieaktywny kurier, wykluczenie kul i półek.
- Preflight prawdziwego HA, zapis siedmiu config entries, odczyt ich encji i oczekiwanie na poprawne stany przed publikacją.
- Odczyt całego dokumentu po zapisie równy dokumentowi oczekiwanemu; fizyczny zrzut zegara potwierdza przyjęcie konfiguracji.
- Końcowy preflight i ponowny odczyt: 10 dostępnych obwodów, brak kul i półek, możliwość ponownego użycia własnej grupy bez tworzenia duplikatu. Preflight sprawdza też brakujące elementy grup zagnieżdżonych i cykle.
- Bez sztucznego włączania lamp, otwierania drzwi/bramy lub symulowania dostawy na produkcyjnych encjach. Nie testowano restartu HA.

Kontrakty helperów sprawdzono w kodzie [Group HA 2026.8.3](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/group/config_flow.py) i [Template HA 2026.8.3](https://github.com/home-assistant/core/blob/2026.8.3/homeassistant/components/template/config_flow.py).
