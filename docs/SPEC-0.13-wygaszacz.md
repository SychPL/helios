# SPEC 0.13 - wygaszacz nocny

Gdy w pokoju jest ciemno i nic nie wymaga uwagi, zegar przestaje być panelem i staje się zegarem:
pełnoekranowa godzina na czarnym tle, wygaszona do minimum. Dotyk wraca do panelu.

## 1. Po co

Panel świeci całą noc w sypialni. Dziś jedyną obroną jest automatyczna jasność firmware'u, która i tak
zostawia czytelne kafelki. Chcemy stanu nocnego, który nie świeci i nie informuje - ale nie chcemy przegapić
rzeczy, które dziś wołają o uwagę (otwarte drzwi, zapalone światła, zdarzenie z bramy).

## 2. Zakres

Wyłącznie strona zegara (Helios). Bez zmian w `ha-helios` i bez nowych encji w HA.

## 3. Sygnał "ciemno"

- Źródło: `SensorManager`, `Sensor.TYPE_LIGHT`, tryb `on-change` (czujnik istnieje na tym sprzęcie:
  `0x00000005 LIGHT | MTK | perm: n/a`). Bez uprawnień, bez odpytywania sieci.
- Pomiar na sprzęcie (19 września, wieczór): pokój oświetlony 27-59 lx, pokój zgaszony **0-1 lx**.
- Próg wejścia `DARK_ENTER = 3 lx`, próg wyjścia `DARK_EXIT = 8 lx`. Histereza jest konieczna, bo czujnik
  oddaje wartości całkowite i przy jednym progu migotałby przy 3/4 lx.
- **Progi muszą być regulowalne bez przebudowy aplikacji** - każdy egzemplarz czujnika i każde miejsce w
  pokoju daje inne wartości. Dostarczamy je tą samą drogą co wygląd (`appearance`), z wartościami domyślnymi
  jak wyżej. W menu zegara pokazujemy aktualny odczyt w luksach, żeby dało się próg dobrać patrząc na liczbę.
- Czujnik jest `on-change`: przy stałym oświetleniu nie przychodzą zdarzenia. Ostatnia znana wartość jest
  stanem; brak odczytu przez pierwsze sekundy po starcie oznacza "nie wiem" i wygaszacz nie wchodzi.

## 4. Sygnał "nic nie wymaga uwagi"

Wygaszacz nie wchodzi, gdy zachodzi którykolwiek z warunków:

1. widoczny jest którykolwiek kafelek warunkowy (`visible_when`) - to są dzisiejsze powiadomienia panelu,
2. gra muzyka albo trwa przejście transportu (`music_transport` inne niż `NONE`),
3. trwa rozmowa albo nasłuch po odpowiedzi (`busy` w `MainActivity`),
4. otwarty jest dialog, menu, panel rolet, onboarding albo pełny ekran muzyki,
5. panel zgłasza błąd połączenia z HA (użytkownik ma prawo to zobaczyć),
6. od ostatniego dotyku minęło mniej niż `IDLE = 60 s`.

Dotyk daje **gwarantowane `MIN_AWAKE = 15 s` panelu**, niezależnie od wszystkiego innego. To nie jest to samo
co `IDLE`: `IDLE` decyduje, kiedy wygaszacz wchodzi po cichym okresie, a `MIN_AWAKE` jest dolną granicą, której
nie wolno skrócić żadnym progiem ani konfiguracją z HA. Gdyby ktoś ustawił `IDLE` poniżej 15 s, obowiązuje
`MIN_AWAKE`. Liczy się każdy dotyk ekranu, także ten, który tylko wybudził panel z wygaszacza.

## 5. Wejście i wyjście

- Wejście: wszystkie warunki z punktów 3 i 4 spełnione nieprzerwanie przez `IDLE`.
- Wyjście natychmiastowe przy: dotyku ekranu, wykryciu słowa wybudzającego, starcie muzyki, pojawieniu się
  kafelka warunkowego, odczycie `>= DARK_EXIT`, błędzie połączenia.
- Wyjście przez dotyk **nie wykonuje akcji pod palcem** - pierwszy dotyk tylko budzi panel.

## 6. Wygląd

- Czarne tło (`#000000`), bez gradientu i bez tła graficznego.
- Godzina `HH:MM` wyśrodkowana, wysokość znaku około 40% wysokości ekranu, kolor `#FFFFFF` przyciemniony do
  `#9A9A9A` (czytelny w ciemności, nie oślepia).
- Pod spodem data małym krojem, ta sama co na panelu.
- Co minutę cała treść przesuwa się o kilka pikseli po elipsie - ekran jest IPS, ale wypalenie statycznych
  jasnych pikseli przez lata to realne ryzyko, a przesunięcie kosztuje jedną linijkę.
- Jasność okna schodzi do `0.01`; przy wyjściu wraca do `BRIGHTNESS_OVERRIDE_NONE`, czyli pod kontrolę
  automatyki firmware'u.

## 7. Czego wygaszacz nie robi

- Nie gasi ekranu (`FLAG_KEEP_SCREEN_ON` zostaje) - zegar ma pokazywać godzinę, nie czarną szybę.
- Nie zmienia jasności systemowej ani ustawień urządzenia; działa wyłącznie na oknie aplikacji.
- Nie dotyka mikrofonu - nasłuch słowa wybudzającego pracuje dalej, bez zmian.

## 8. Kryteria odbioru

1. W ciemnym pokoju, po minucie bez dotyku, panel zamienia się w godzinę na czerni.
2. Zapalenie światła (odczyt `>= 8 lx`) przywraca panel w mniej niż dwie sekundy.
3. Dotyk w trakcie wygaszacza przywraca panel i **nie** uruchamia kafelka pod palcem.
3a. Po dotknięciu panel jest widoczny przez co najmniej 15 s, nawet w pełnej ciemności i przy braku
   jakiejkolwiek aktywności - wygaszacz nie wraca wcześniej.
4. Pojawienie się kafelka warunkowego wybija wygaszacz nawet w pełnej ciemności.
5. "Okay Nabu" działa w trakcie wygaszacza i przywraca panel na czas rozmowy.
6. Muzyka gra - wygaszacz nie wchodzi.
7. Po ponownym uruchomieniu aplikacji w ciemności wygaszacz wchodzi dopiero po pierwszym odczycie czujnika.
8. Progi zmienione po stronie HA działają bez przebudowy aplikacji.

## 9. Ryzyka

- **Czujnik on-change milczy w stałym świetle.** Gdyby okazało się, że po starcie nie przychodzi żaden
  odczyt, potrzebny jest jednorazowy odczyt wymuszony albo fallback na `Settings.System.SCREEN_BRIGHTNESS`
  (na tym zegarze automatyczna jasność jest włączona, więc jasność systemowa śledzi otoczenie).
- **Dotyk budzący panel** musi przechwycić zdarzenie zanim dojdzie do kafelka - inaczej pierwsze dotknięcie
  w ciemności zgasi komuś światło w salonie.
- **Kafelki warunkowe jako definicja powiadomień** zależą od konfiguracji w HA. Jeśli użytkownik nie ma
  żadnych kafelków warunkowych, wygaszacz będzie wchodził zawsze - to jest poprawne, ale warto to powiedzieć
  wprost w dokumentacji.
