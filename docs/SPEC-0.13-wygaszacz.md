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
  pokoju daje inne wartości. Trzymamy je w ustawieniach zegara (`SharedPreferences`), z regulacją w menu i
  **bieżącym odczytem w luksach wyświetlonym obok**, żeby próg dobierać patrząc na liczbę. Świadomie **nie**
  idziemy przez `appearance`: `Appearance.parse()` przyjmuje dokładnie trzy pola i wersję 1, więc zdalne progi
  wymagałyby zmiany formatu i nadawcy po stronie `ha-helios` - a ta zmiana ma zostać wyłącznie w zegarze.
- Walidacja: obie wartości skończone, `0 <= DARK_ENTER < DARK_EXIT <= 100`; wartość spoza zakresu wraca do
  domyślnej. Zmiana progu przelicza stan natychmiast, także w trakcie trwającego wygaszacza.
- Ostatnia znana wartość jest stanem. Dopóki nie przyszedł pierwszy odczyt (`luxKnown == false`), wygaszacz
  nie wchodzi. Brak czujnika albo `registerListener()` zwracające `false` znaczy: funkcja nieaktywna, panel
  działa jak dotąd.

## 4. Sygnał "nic nie wymaga uwagi"

Wygaszacz nie wchodzi, gdy zachodzi którykolwiek z warunków:

1. widoczny jest którykolwiek kafelek warunkowy (`visible_when`) - to są dzisiejsze powiadomienia panelu,
2. sesja muzyczna jest inna niż `NONE` (`MusicSession.Ui`) - **pauza też blokuje**, bo pauza to przerwa w
   słuchaniu, nie koniec,
3. trwa rozmowa albo nasłuch po odpowiedzi (`busy` w `MainActivity`),
4. otwarty jest dialog, menu, panel rolet, onboarding albo pełny ekran muzyki,
5. panel zgłasza problem z HA - przez `setIssue` (brak połączenia, zła konfiguracja) **albo** przez komunikat
   o odrzuconej autoryzacji; te dwie drogi trzeba połączyć w jedną flagę `hasHaIssue`, bo dziś idą osobno i z
   samego widoku nie da się ich rozróżnić,
6. od ostatniego dotyku minęło mniej niż `IDLE = 60 s`.

Dotyk daje **gwarantowane `MIN_AWAKE = 15 s` panelu**, niezależnie od wszystkiego innego. To nie jest to samo
co `IDLE`: `IDLE` decyduje, kiedy wygaszacz wchodzi po cichym okresie, a `MIN_AWAKE` jest dolną granicą, której
nie wolno skrócić żadnym progiem ani konfiguracją z HA. Gdyby ktoś ustawił `IDLE` poniżej 15 s, obowiązuje
`MIN_AWAKE`. Liczy się każdy dotyk ekranu, także ten, który tylko wybudził panel z wygaszacza.

## 5. Wejście i wyjście

Czas liczy **jeden** znacznik: `eligibleSince` - moment, od którego jednocześnie jest ciemno, nie ma żadnego
blokera z punktu 4 i nie było dotyku. Każde złamanie któregokolwiek warunku kasuje znacznik, a po ustaniu
blokera odliczanie zaczyna się od zera. Wejście następuje po `max(IDLE, MIN_AWAKE)` od `eligibleSince`.
Tak zapisane, bo dwa osobne liczniki dałyby w naiwnej implementacji 120 s zamiast 60, a koniec rozmowy
wrzucałby wygaszacz natychmiast, w środku zdania.

- Wejście: `eligibleSince` starszy niż `max(IDLE, MIN_AWAKE)`.
- Wyjście jest **symetryczne**: natychmiast, gdy znika dowolny warunek wejścia - nie tylko przy zdarzeniach
  wymienionych niżej, ale też przy otwarciu dowolnego okna, wejściu w `busy` czy zmianie stanu muzyki.
  Zdarzenia wybijające od razu: dotyk ekranu, słowo wybudzające, odczyt `>= DARK_EXIT`, kafelek warunkowy,
  `hasHaIssue`.
- Wyjście przez dotyk **nie wykonuje akcji pod palcem**: gest jest połykany w `dispatchTouchEvent` w całości,
  od `ACTION_DOWN` do `ACTION_UP`/`ACTION_CANCEL`. Połknięcie samego `ACTION_DOWN` nie wystarczy - reszta
  gestu i tak doszłaby do kafelka.

## 6. Wygląd

- Czarne tło (`#000000`), bez gradientu i bez tła graficznego.
- Godzina `HH:MM` wyśrodkowana, wysokość znaku około 40% wysokości ekranu, kolor `#FFFFFF` przyciemniony do
  `#9A9A9A` (czytelny w ciemności, nie oślepia).
- Pod spodem data małym krojem, ta sama co na panelu.
- Bez przesuwania treści przeciw wypaleniu. Ekran jest IPS, ryzyka nie potwierdza żaden pomiar, a poprawne
  przesuwanie w granicach ekranu to nie jedna linijka. Wraca do rozważenia, jeśli wypalenie kiedyś wystąpi.
- Jasność okna schodzi do `0.01`; przy wyjściu wraca do `BRIGHTNESS_OVERRIDE_NONE`, czyli pod kontrolę
  automatyki firmware'u.
- **Jeden arbiter jasności.** Dziś dotyk podbija jasność o 0,2 i po 15 s bezwarunkowo oddaje ją systemowi
  (`unboost`). Wejście w wygaszacz musi skasować ten odroczony powrót i ustawić `0.01`; wyjście przywraca
  `NONE`, a dopiero potem wyjście przez dotyk może nałożyć zwykłe podbicie. Bez tego dwa mechanizmy będą
  sobie nawzajem nadpisywać jasność okna.

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
8. Progi zmienione w menu zegara działają natychmiast, także w trakcie trwającego wygaszacza.
9. Pauza muzyki blokuje wygaszacz tak samo jak odtwarzanie.
10. Po zakończeniu rozmowy panel zostaje widoczny pełne 60 s, a nie wraca do wygaszacza od razu.
11. Zegar bez działającego czujnika światła zachowuje się jak dotąd - wygaszacz po prostu się nie włącza.

## 9. Ryzyka

- **Sprzężenie zwrotne panelu z czujnikiem.** Świecący panel oświetla własny czujnik. Jeśli po wyjściu z
  wygaszacza odczyt przekroczy `DARK_EXIT`, dostaniemy stan, w którym wygaszacz nie może wrócić, dopóki ktoś
  nie zgasi ekranu. To trzeba **zmierzyć na sprzęcie** przed wydaniem: odczyt w ciemnym pokoju przy jasnym
  panelu i przy wygaszaczu. Jeśli różnica będzie istotna, progi muszą być liczone przy jasności panelu, a nie
  w oderwaniu od niej - i właśnie dlatego są regulowalne.
- **Dotyk budzący panel** musi przechwycić zdarzenie zanim dojdzie do kafelka - inaczej pierwsze dotknięcie
  w ciemności zgasi komuś światło w salonie.
- **Kafelki warunkowe jako definicja powiadomień** zależą od konfiguracji w HA. Jeśli użytkownik nie ma
  żadnych kafelków warunkowych, wygaszacz będzie wchodził zawsze - to jest poprawne, ale warto to powiedzieć
  wprost w dokumentacji.

## 10. Podział na zadania

1. **Maszyna stanów bez Androida** (`ScreensaverPolicy`) plus testy jednostkowe. Wejścia: `nowElapsedMs`
   (monotoniczny), `luxKnown`, `lux`, `darkEnter`, `darkExit`, `anyConditionalTileVisible`, `musicActive`,
   `voiceBusy`, `blockingUiOpen`, `hasHaIssue`, `resumed`, `idleMs`, `minAwakeMs`. Zdarzenia: `onTouch(now)`,
   `onWakeWord(now)`. Wyjście: `ENTER`, `EXIT` albo brak zmiany. Klasa nie widzi `Activity`, `SensorEvent`
   ani widoków. Testy: histereza, nieznany lux, każdy bloker z osobna, brak natychmiastowego powrotu po
   blokerze, `MIN_AWAKE`, zmiana progów w locie.
2. **Podłączenie w `MainActivity`**: rejestracja czujnika w `onResume` i zwolnienie w `onPause`, `luxKnown`
   zerowane przy każdym wznowieniu, agregacja blokerów, jeden timer na `SystemClock.elapsedRealtime()`,
   pełne przechwycenie gestu i scentralizowana jasność. `NavigationMenu` potrzebuje akcesora `isShowing()`.
3. **Nakładka nocna w `DashboardView`** - ten widok dostaje już czas i datę w `clock()`, więc to cienka
   warstwa rysowania, nie nowa aktywność.
4. **Menu i sprzęt**: bieżący odczyt w luksach oraz progi w menu zegara; pomiar sprzężenia panel-czujnik,
   czas reakcji na zapalenie światła, dotyk nad kafelkiem, słowo wybudzające w trakcie wygaszacza.
