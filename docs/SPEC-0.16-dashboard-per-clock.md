# SPEC 0.16 - osobny dashboard dla kazdego zegara, zakladki w panelu Helios

Status: projekt, do recenzji. Dotyczy ha-helios (integracja + panel). Aplikacja Helios bez zmian.

## 1. Problem

Integracja wysyla kazdemu zegarowi te sama sciezke dashboardu (`DASHBOARD_PATH = "helios-clock"`,
`identity.connection_payload` i odpowiedz `/api/helios/pair`). Drugi zegar (gabinet) pokazuje wiec dashboard
sypialni: rolety sypialni, lampke docka zegara z sypialni. Nie ma sposobu, zeby zegar dostal inny dokument.

## 2. Cel

1. Kazdy zegar (wpis konfiguracyjny `helios`) ma wlasna sciezke dashboardu. Brak ustawienia = `helios-clock`,
   wiec obecny zegar i kazdy nowo sparowany zachowuja sie jak dotad.
2. Panel Helios pokazuje jedna zakladke na zegar zamiast listy pulpitow. Zakladka edytuje dokument, ktory ten
   zegar faktycznie wyswietla.
3. Z zakladki zegara, ktory dzieli dokument z innym, da sie jednym przyciskiem dac mu wlasny dokument
   (kopia biezacego), a zegar przelacza sie sam, bez restartu HA i bez ruszania aplikacji.

Poza zakresem: jeden dokument z sekcjami per zegar (wymagalby zmiany schematu 6 i aplikacji), automatyczne
tworzenie dokumentu przy parowaniu, sprzatanie pulpitow po usunieciu zegara, encje typu "ten zegar".

## 3. Integracja

### 3.1 Sciezka w danych wpisu

- `entry.data["dashboard_path"]`: string albo brak. Wartosc poprawna = `^[a-z0-9]+(-[a-z0-9]+)+$`, najwyzej
  64 znaki (to, co przyjmuje `lovelace/dashboards/create`: slug z myslnikiem).
- W `entry.data`, nie w `entry.options`: formularz opcji oddaje caly slownik opcji dopiero po powrocie z kroku,
  poza jakimkolwiek zamkiem (`async_create_entry(data=options)`), wiec zapis sciezki w opcjach moglby zostac
  cofniety starym slownikiem. Danych wpisu formularz nie dotyka; ponowne parowanie laczy dane
  (`{**previous, **entry_data}`), wiec sciezka przezywa re-pair.
- `const.dashboard_path_for(entry_data) -> str`: poprawna wartosc albo `DASHBOARD_PATH`. Wartosc niepoprawna
  (reczna edycja storage) = `DASHBOARD_PATH`, nigdy wyjatek.
- `identity.connection_payload` bierze sciezke z `dashboard_path_for(entry_data)`.
- Odpowiedz parowania (`/api/helios/pair`) zostaje `DASHBOARD_PATH`: pierwsze zdarzenie `connection` po
  `helios/connect` i tak niesie sciezke wpisu.

### 3.2 Komendy WebSocket (admin)

Obie wymagaja admina (`@websocket_api.require_admin`), jak panel. Uzytkownik zegara jest systemowym
uzytkownikiem grupy `system-users`, nie adminem, wiec zegar ich nie wywola.

- `helios/clocks` -> `{"clocks": [...]}`, po jednym na wpis `helios`:
  `entry_id`, `name` (nazwa urzadzenia w rejestrze: `name_by_user` albo `name`, awaryjnie tytul wpisu),
  `area` (nazwa obszaru urzadzenia albo null), `dashboard_path` (`dashboard_path_for`), `online`
  (czy coordinator ma aktywna subskrypcje). Kolejnosc: po `name`.
- `helios/clock/set_dashboard` `{entry_id, dashboard_path}`:
  - nieznany `entry_id` -> blad `not_found`; sciezka niezgodna z 3.1 -> `invalid_format`;
  - `async_update_entry(entry, data={**entry.data, "dashboard_path": path})` synchronicznie, bez `await` miedzy
    odczytem a zapisem, wiec nie przeplata sie z zapisem sekcji MA w `ws_connect` (ten tez czyta `entry.data`
    w chwili zapisu);
  - istniejacy listener aktualizacji wpisu (odpala tez na zmiane danych) wysyla `connection` z nowa sciezka;
    aplikacja juz dzis na zmiane `dashboard_path` restartuje klienta HA i wczytuje nowy dokument
    (`ConnectionMerge.haRestart`);
  - wycofanie nieudanego ponownego parowania (`http.py`) przywraca tylko pola zapisane przez parowanie, nie caly
    stary slownik, wiec sciezka ustawiona w trakcie przeladowania wpisu przezywa;
  - integracja NIE sprawdza, czy pulpit istnieje: panel tworzy go przed wywolaniem, a brakujacy pulpit to
    dla zegara stan "Panel Helios nie jest dostepny w HA" z zachowaniem ostatniego dobrego ukladu.

## 4. Panel

- Pasek zamiast `<select>` pulpitow: zakladki zegarow z `helios/clocks` (nazwa, obszar, kropka online).
  Wybor zakladki = `urlPath = clock.dashboard_path` i `_loadDashboard()`; niezapisane zmiany pytaja jak dotad.
  Zapamietany wybor: pierwszy zegar z listy.
- Pod paskiem, wyciszone: sciezka dokumentu i lista innych zegarow, ktore go dziela ("wspolny z: X").
- Przycisk "Wlasny dashboard" widoczny, gdy dokument dzieli wiecej niz jeden zegar:
  1. nowa sciezka = `helios-` + slug nazwy zegara, z przyrostkiem `-2`, `-3`... gdy zajeta
     (`lovelace/dashboards/list`, dowolny tryb);
  2. `lovelace/dashboards/create` (tytul `Helios - <nazwa>`, `show_in_sidebar: false`, `require_admin: true`);
  3. `lovelace/config/save` z kopia calego biezacego dokumentu (swiezo pobranego, nie modelu z edytora;
     `config_not_found` przerywa operacje przed krokiem 2 - kopia niczego to blad, nie pusty uklad);
  4. `helios/clock/set_dashboard`; po potwierdzeniu panel od razu przyjmuje nowa sciezke lokalnie;
  5. wczytanie nowego dokumentu; blad wczytania = komunikat "przelaczony, uzyj Przeladuj".
  Niezapisane zmiany blokuja przycisk (najpierw Zapisz albo Przeladuj). Od kroku 1 do konca kroku 5 edytor
  nie przyjmuje zmian (klikniecia i formularz), bo wczytanie i tak zastapi model. Blad w kroku 2-3 = komunikat, zegar
  zostaje na starym dokumencie (krok 4 sie nie wykonal). Blad w kroku 4 = pulpit istnieje, zegar nie
  przelaczony; komunikat, ponowienie znajdzie wolna sciezke z przyrostkiem - osierocony pulpit uzytkownik
  usuwa w HA (swiadome uproszczenie).
- Brak zegarow (lista pusta) - panel i tak znika z ostatnim zegarem; na wszelki wypadek komunikat.
- Brak pulpitu pod sciezka zegara - zostaje obecny przycisk "Utworz pulpit" dla tej sciezki.

## 5. Kryteria akceptacji

1. Bez `dashboard_path` w opcjach zdarzenie `connection` niesie `helios-clock` (regresja zerowa).
2. `set_dashboard` z poprawna sciezka zmienia dane wpisu i wysyla `connection` z ta sciezka do podlaczonego zegara;
   pozostale opcje (wyglad, token MA) nietkniete.
3. `set_dashboard` odrzuca: brak uprawnien admina, nieznany wpis, `Helios`, `helios`, `a--b`, 65 znakow.
4. `helios/clocks` zwraca oba zegary z nazwami z rejestru urzadzen i sciezkami.
5. Test JS: generator sciezki (slug + przyrostek) dla nazw z polskimi znakami i spacjami.
6. Na zywym HA: zegar gabinet po "Wlasny dashboard" pokazuje kopie, po edycji (np. lampka docka gabinetu)
   pokazuje swoja lampke, a zegar w sypialni dalej dokument `helios-clock`.
