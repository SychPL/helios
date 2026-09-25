# SPEC 0.20 - kafelek "Uwagi" (`alerts`): wszystkie ostrzeżenia w jednym miejscu, lista w wysuwanym panelu

Status: zaakceptowany przez właściciela 2026-09-25, zaimplementowany w Helios 0.17.0. Uzgodniony z Codexem w 4 rundach.
Makiety 800x480 z czcionkami, ikonami i kolorami aplikacji: `docs/mockups/alerts-board-active.png` (dolny rząd
sypialni: kafelek 2x1 z dwoma ostrzeżeniami), `alerts-board-empty.png` (to samo miejsce, gdy nic nie obowiązuje:
karta zastępcza energii), `alerts-sizes.png` (1x1 i 2x1 we wszystkich stanach), `alerts-sizes-tall.png` (1x2 we
wszystkich stanach, `Brak treści`, `0 / Brak uwag` w czasie opóźnienia), `alerts-drawer.png` (wysunięta lista), `alerts-drawer-states.png` (pusty panel, długi tekst, `Zgaś`: potwierdzenie, w toku, błąd, niedostępny);
skrypt `alerts_mock.py`.

## 1. Problem

Dziś każde ostrzeżenie to osobny warunkowy kafelek 1x1 (`visible_when`): światła, garaż, blaszak, Wiking w
dolnym rzędzie dokumentu sypialni, zasilane pomocnikami z `tools/deploy_attention_dashboard.py` (tekst
`sensor.helios_*`, flaga `binary_sensor.helios_*_pokaz`). Ukryty kafelek i tak zajmuje komórkę, a nic nie mówi,
od kiedy ostrzeżenie trwa. Właściciel chce widzieć wieczorem otwarty garaż, wiedzieć, że jutro wywóz śmieci, i
nie tracić miejsca na sytuacje, których nie ma. Rozwiązanie (jego propozycja): **jeden kafelek z liczbą i kilkoma
pierwszymi ostrzeżeniami; stuknięcie wysuwa panel z pełną listą; gdy nic nie obowiązuje, w tym miejscu stoi inna
karta**. Źródło = istniejące pomocniki (nie powiadomienia HA, nie nowy kanał); lista osobna dla każdego zegara (w
dokumencie danego zegara, SPEC 0.16).

## 2. Schemat (version 6), typ `alerts`


```yaml
- id: uwagi
  type: alerts
  column: 1
  row: 3
  width: 2          # 1x1, 1x2 (pionowy), 2x1 (poziomy); domyślnie zalecane 2x1
  height: 1
  title: Uwagi
  empty:            # opcjonalnie: karta w miejscu kafelka, gdy wszystko potwierdzone nieaktywne
    type: energy
    entity: sensor.goodwe_pv_power
    load_entity: sensor.goodwe_house_consumption
    battery_entity: sensor.goodwe_battery_state_of_charge
  sources:          # 1-12; kolejność z konfiguracji = rozstrzyganie remisów (nie "ważność")
    - {title: Garaż otwarty, entity: sensor.helios_garaz_uwaga, icon: mdi:garage-open,
       when: {entity: binary_sensor.helios_garaz_uwaga_pokaz, state: 'on'}}
    - {title: Śmieci jutro, entity: sensor.helios_smieci_jutro, icon: mdi:trash-can,
       when: {entity: binary_sensor.helios_smieci_jutro_pokaz, state: 'on'}}
    - {title: Światła, entity: sensor.helios_zapalone_swiatla, icon: mdi:lightbulb,
       when: {entity: binary_sensor.helios_zapalone_swiatla_pokaz, state: 'on'},
       off_entity: light.helios_swiatla_do_sprawdzenia}
    - {title: Wiking był, entity: sensor.helios_wiking_godzina, show_since: false,
       when: {entity: binary_sensor.helios_wiking_godzina_pokaz, state: 'on'}}
```

- Źródło: `title`, `entity`, `when` wymagane; `icon`, `off_entity` (light, akcja `lights_off`), `show_since`
  (domyślnie true) opcjonalne. Duplikat pary (`when.entity`, `when.state`) = błąd. `when` ma tę samą
  składnię co `visible_when` (`DashboardSpec.when()`), ale **własną trójstanową ocenę** (nie `Item.visible()`,
  które zna tylko tak/nie):
  - **aktywne** - encja warunku istnieje, stan znany i równy `when.state`;
  - **nieaktywne** - encja istnieje, stan znany i inny;
  - **nieznane** - brak encji w migawce, stan `unknown`/`unavailable`, przed pierwszą migawką i przez cały czas
    bez połączenia z HA (`live=false`).
  Tekst źródła (`entity`) nie wpływa na tę ocenę: aktywne źródło z nieznanym tekstem pokazuje `Brak treści`.
- `empty`: jedna karta bez `id`/pozycji/rozmiaru/`visible_when`, typy tylko z listy **energy, climate, weather,
  clock, tile** (nie `alerts`, nie `music`, bez zagnieżdżeń); zachowuje własne akcje i bramki. Encje `empty` idą
  do subskrypcji, ale karta **nie** jest osobnym elementem ani źródłem ostrzeżeń (nie w `allItems()`).
- Liczba = kategorie (pięć lamp = jedno "Światła").

## 3. Stany kafelka

`A` = liczba aktywnych, `U` = liczba nieznanych **warunków**.

| Stan | Wygląd |
|---|---|
| A > 0 | pomarańczowe wyróżnienie (`DashboardView.java:287-298`), liczba A, podgląd; przy U > 0 stopka `Brak danych: U` |
| A = 0, U > 0 | neutralnie, `-`, `Brak danych` (nigdy karta `empty`) |
| A = 0, U = 0 | karta `empty` po 2 s ciągłego trwania tego stanu; do tego czasu i bez `empty`: neutralnie `0`, `Brak uwag` |

- Przejścia asymetryczne: wyjście z `empty` natychmiast (A > 0 albo U > 0, w tym utrata połączenia), wejście po 2 s;
  opóźnienie dotyczy tylko wyglądu, nie liczb ani blokady wygaszacza.
- Bez połączenia: jak inne kafelki - tytuł z dopiskiem `(offline)`, bez wyróżnienia; wszystkie warunki nieznane,
  więc karta `empty` nigdy nie stoi na nieaktualnych danych.
- Zmiana wyświetlanej karty w trakcie dotyku anuluje stuknięcie (gest rozpoczęty na "Uwagach" nie trafi w kartę
  zastępczą i odwrotnie).
- Blokada wygaszacza (`notifications_block`, `MainActivity.panelWanted()`): A > 0 w dowolnym kafelku `alerts` na
  dowolnej stronie.

Geometria kafelka (jednostki 800x480, padding 12, współrzędne = górne krawędzie pól tekstu, każdy tekst mieści się
w polu z odstępem >= 10 od dolnej krawędzi):

| Rozmiar | Wymiar | Treść |
|---|---:|---|
| 1x1 | 190x132 | nagłówek (12,10) h22: `mdi:bell-alert`/`mdi:bell` 22 + tytuł 17 px; liczba (12,32) h48, 44 px; podsumowanie (12,84) h20, 17 px; stopka (12,106) h16, 13 px |
| 1x2 | 190x272 | nagłówek i liczba jw.; 3 sloty od (12,92), co 44, h40 (ikona 20 + do 2 linii 17 px); stopka (12,228) h16 |
| 2x1 | 388x132 | nagłówek (12,10) h22; liczba (12,40) w bloku 64 szer.; 2 sloty (92,40) i (92,72), 284x28, 19 px; stopka (92,106) h16 |

- Podsumowanie 1x1: tytuł najnowszego + ` +N` (N = A-1); sufiks mierzony i rezerwowany, tytuł ucinany wielokropkiem
  w pozostałej szerokości (pikselowo).
- Przepełnienie: 1x2 pokazuje do 3 źródeł, przy A >= 4 dwa + `+N pozostałych`; 2x1 do 2, przy A >= 3 jedno +
  `+N pozostałych`. Tytuły w slotach: 1x2 zawija do 2 linii, potem wielokropek; 2x1 jedna linia z wielokropkiem.
- Sloty to podgląd, nie przyciski - całe pole otwiera panel.

## 4. Wysuwany panel (`docs/mockups/alerts-drawer.png`)

- Okno x=0, y=52, 800x428, wjeżdża z prawej (~200 ms). Nagłówek: ikona (16,67) 34, tytuł (60,70) 26 px, liczba
  aktywnych `N aktywne` 18 px wyciszona za tytułem; zamknięcie X (728,52) 64x64. Zamyka też wstecz (najpierw
  potwierdzenie, potem panel), przesunięcie w prawo i 2 min bez dotyku (własny licznik, zerowany dotykiem w panelu).
  Otwierany tylko stuknięciem kafelka.
- Gesty: panel otwarty/animowany przejmuje dotyk, stronicowanie pulpitu wyłączone; progi SPEC 0.18 (rozpoznanie
  > 24 i |dx| > 2|dy|, zamknięcie po puszczeniu przy >= 80 w prawo); gest, w którym wygrało przewijanie pionowe, nie
  zamyka; rozpoznanie poziome anuluje stuknięcia przycisków; gest zamykający nie trafia do pulpitu. Po zamknięciu
  świeży licznik bezczynności pulpitu.
- Lista: od y=124, x 8-792, wiersze o wysokości min. 100 co 8 (odstęp). Przy trzech wierszach minimalnych widać 32 px
  czwartego (sygnał przewijania); przy dłuższych wierszach odpowiednio mniej.
- Wiersz (współrzędne względem wiersza): ikona (16,16) 28; tytuł (56,16) 22 px, jedna linia z wielokropkiem;
  czas `Aktywne od 14:32` 16 px wyrównany do prawej, kończy się 16 przed prawym brzegiem albo przed kolumną
  przycisku; tytuł kończy się 16 przed czasem. Tekst pomocnika (56,52) 19 px, zawija do 3 linii, potem wielokropek.
  Z `off_entity` prawa kolumna 112 zarezerwowana na obie linie, `Zgaś` (680,18) 96x64. Wiersze to informacja, nie
  przyciski.
- Czas: dziś `Aktywne od 14:32`, wczoraj `Aktywne od wczoraj 22:10`, starsze `Aktywne od 23 wrz 22:10`; strefa i
  granice dni zegara. Bez `lc` albo z `show_since: false` - bez czasu.
- Kolejność: aktywne od najnowszych (`lc` warunku), bez czasu na końcu, remis = konfiguracja; potem wyciszone
  `<tytuł> - brak danych` w kolejności konfiguracji (bez czasu, bez `Zgaś`); nic = `Brak uwag`.
- Na żywo: tekst, czas i dostępność `Zgaś` aktualizują się od razu; w trakcie dotyku lub przewijania (także inercji)
  wysokości wierszy są zamrożone, a dłuższy nowy tekst ucinany do zamrożonego pola. Pojawienie/zniknięcie/
  przestawienie wierszy dopiero po interakcji, raz, do najnowszego stanu; kotwica przewijania = najwyżej położony w
  pełni widoczny wiersz (jego id i przesunięcie), a gdy zniknął - następny.
- Tytuły w kafelku 1x2: zawijane słowami do 2 linii; co nie mieści się w drugiej linii, ucina wielokropek na jej
  końcu (także gdy reszta to całe kolejne słowa). Słowo dłuższe niż linia - łamane z wielokropkiem.
- `Zgaś`:
  - bramki przy stuknięciu i ponownie po potwierdzeniu: połączenie z HA, źródło **aktywne**, encja `off_entity` w
    migawce ze znanym stanem; niespełnione - nic nie wychodzi, komunikat w wierszu;
  - potwierdzenie `Zgasić wszystkie obserwowane światła?` w istniejącym oknie `confirmDialog` (Anuluj/Potwierdź,
    320 szer.) nad panelem; panel zostaje otwarty, anulowanie/dotyk obok nic nie wysyła;
  - w toku: przycisk nieaktywny ze spinnerem (drugie stuknięcie niemożliwe);
  - komunikaty (bramka niespełniona, błąd) zastępują w wierszu tekst pomocnika w kolorze akcentu, 19 px, jedna
    linia z wielokropkiem, na 10 s albo do zmiany stanu źródła; wysokość wiersza się nie zmienia (min. 100 mieści
    tytuł i komunikat), więc nie łamie zamrożenia;
  - `Zgaś` niedostępny (brak połączenia, encja nieznana): przycisk wyszarzony, stuknięcie pokazuje komunikat
    `Brak połączenia z Home Assistant` albo `Światła - brak danych`;
  - błąd lub brak odpowiedzi w 10 s (limit transportu `HaDashboardClient`): komunikat w wierszu
    `Nie udało się zgasić`, przycisk znów aktywny (ponowienie = kolejne stuknięcie, nigdy automatycznie);
  - sukces: przycisk nieaktywny, dopóki warunek nie przestanie być aktywny albo przez 10 s; wiersz zostaje, dopóki
    pomocnik nie zgaśnie;
  - źródło przestaje być aktywne (lub staje się nieznane) w trakcie potwierdzenia - wysyłka anulowana.
- Poza zakresem: potwierdzanie/odrzucanie ostrzeżeń, historia, mruganie lampką.

## 5. Czas "Aktywne od"

`lc` z `subscribe_entities` (sekundy epoki) -> `EntityStates.Entity.lastChanged` (ms, 0 = brak), zachowywany
przy zmianach samych atrybutów. Znaczy "od kiedy pomocnik jest aktywny w HA"; restart HA/przeładowanie go
przesuwa - udokumentowane ograniczenie.

## 6. Po stronie HA (pakiet YAML, bo szablony wyzwalane z akcjami nie są dostępne w UI)

- **Śmieci**: szablon wyzwalany (start HA, północ, 19:00, co 30 min) z `calendar.get_events` na `calendar.smieci`
  od jutrzejszej lokalnej północy do następnej; zapisuje datę zapytania i czas udanego odświeżenia; błąd/brak
  odpowiedzi nie jest pustą listą; wynik starszy niż 65 min albo dla złej daty = `unavailable`. Tekst
  `Jutro: <rodzaje>`. Flaga `pokaz` (stanowa) = ważny wynik na jutro, niepusta lista i jutrzejszy wywóz **nie**
  odhaczony (reguła w punkcie niżej - jedyna, sam stan przełącznika nie decyduje). Widoczna przez cały dzień przed wywozem; automatyzacja Telegram o 19:00 zostaje bez zmian.
  Nieważny/przeterminowany wynik kalendarza albo nieznany `smieci_wyniesione` = flaga `unknown`, nie `off` -
  karta zastępcza nigdy nie przykryje braku danych.
- **"Wyniesione" przypisane do daty wywozu**: włączenie `input_boolean.smieci_wyniesione` zapisuje w
  `input_datetime.smieci_wyniesione_dla` datę wywozu, którego dotyczy: od 12:00 - jutrzejszą (jeśli jutro jest
  wywóz), przed 12:00 - dzisiejszą (jeśli dziś jest wywóz); gdy wskazanego dnia wywozu nie ma, bierze drugi z nich.
  Przy wywozach dzień po dniu wieczorne odhaczenie dotyczy więc jutra, poranne - dzisiaj. "Jutrzejszy wywóz
  odhaczony" = `smieci_wyniesione_dla` równa dacie jutra; pusta data = nieodhaczony, nieznany stan pomocnika daty =
  flaga `unknown`. Stan samego przełącznika w regule nie występuje, więc stare
  odhaczenie nigdy nie wyciszy kolejnego wywozu, także po przestoju HA w kolejne dni wywozu. Przełącznik wraca na
  off, gdy zapisana data różni się od bieżącej daty docelowej z reguły 12:00 (sprawdzane przy starcie HA, o 00:05,
  o 12:00 i przy każdym odświeżeniu). Przykład: odhaczenie w niedzielę wieczorem (dla poniedziałku) trzyma
  przełącznik do poniedziałku 12:00; wtedy cel zmienia się na wtorek, przełącznik gaśnie i można odhaczyć wtorek.
  Wyłączenie ręczne czyści datę. Logika flagi zależy tylko od daty. Odhaczanie jak dziś w HA (bez akcji w panelu zegara).
- **Garaż wieczorem**: flaga garażu aktywna 19:00-07:00 (czas lokalny); niedostępny czujnik w tym oknie = nieznane.
- Pliki: `ha/packages/helios_attention_extra.yaml` (przykład) + prywatna kopia w `.local/ha/`; restart/przeładowanie
  szablonów HA przy wdrożeniu (decyzja właściciela przy wdrożeniu).

## 7. Pliki

- dash: `CardDefinition` (`alerts`, akcja panelu), `DashboardSpec` (`sources`, `empty`, `entities()`,
  `attributes()`), `EntityStates` (`lc`), nowe czyste `AlertsModel` (stany, kolejność, podgląd, format czasu,
  opóźnienie `empty`), `CardBodies`, `ActionPolicy` (`Panel.ALERTS`), `DashboardView` (kafelek wielowierszowy,
  podmiana na `empty`, anulowanie stuknięcia przy zmianie), nowe `AlertsDrawer` + `AlertsGeometry` (wzorzec
  `ClimatePanel`/`ClimatePanelGeometry`, gest `PageSwipe`), `MainActivity` (tap, `panelWanted`, odświeżanie,
  `onPause`), testy `AlertsCardTest`, docs `SPEC-0.20-alerts-card.md`, `ha-dashboard.md`, makiety
  `docs/mockups/alerts-*.png` (skrypt jak `climate_mock.py`), fixture emulatora jak `climate_fixture.py`.
- ha-helios: `panel/helios-schema.js` (typ, walidator lustrzany, formularz źródeł, "Gdy brak uwag: Brak / Karta"
  z formularzem zwykłej karty bez pozycji, podgląd stanów aktywne/puste/brak danych), testy node.
- Helios 0.17.0 (wydanie na GitHubie - gabinet pobierze z menu), ha-helios 0.15.0 (tylko panel - bez restartu HA).

## 8. Kolejność prac

1. SPEC 0.20 z makietami (kafelek 1x1/1x2/2x1 w stanach, panel, lista) -> recenzja Codex -> akceptacja właściciela.
2. dash: model + testy, kafelek, panel, emulator.
3. ha-helios panel.
4. Pakiet HA (śmieci, reset, garaż wieczorem) - pokazany właścicielowi przed wdrożeniem.
5. Sypialnia: dolny rząd -> `alerts` 2x1 w kolumnach 1-2 z kartą `empty`, kolumny 3-4 wolne na inne karty.

## 9. Weryfikacja

- JVM: parsowanie i odrzucenia (duplikaty, typy `empty`); `lc` w snapshot/diff i przy zmianie samych atrybutów;
  trójstanowa ocena warunku (brak encji, `unknown`, `unavailable`, przed pierwszą migawką, bez połączenia); stany
  kafelka z tabeli dla 1x1/1x2/2x1, przepełnienie, `Brak treści`, `Brak danych: U`, `0 / Brak uwag` w czasie
  opóźnienia; `empty` tylko przy A=0 i U=0, wejście po 2 s, wyjście od razu; kolejność, format czasu, długie tytuły
  i teksty (ucinanie, rezerwa sufiksu), 12 aktywnych; blokada wygaszacza; `Zgaś`: bramki, źródło znikające w trakcie
  potwierdzenia, cel niedostępny, blokada powtórzeń, błąd i limit 10 s.
- Zrzuty: pola z tabeli geometrii jako kryteria (makiety `alerts-sizes.png`, `alerts-sizes-tall.png`).
- Emulator z fixture: 0 -> karta zastępcza, 2 aktywne, brak danych, panel i gesty (przesunięcie w prawo vs strona,
  przewijanie), `Zgaś`, zniknięcie wiersza, zmiana karty w trakcie dotyku.
- HA: szablon śmieci przy błędzie kalendarza, po restarcie, na granicy północy; przestój obejmujący kolejne dni
  wywozu (stare odhaczenie nie wycisza nowego); wywozy pon.+wt.: odhaczenie w niedzielę wieczorem, w poniedziałek
  po 12:00 przełącznik off i ostrzeżenie o wtorku widoczne, drugie odhaczenie je wycisza.
- Sprzęt: sypialnia (192.168.1.113); gabinet po aktualizacji z GitHuba.
