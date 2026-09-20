# SPEC 0.14 - wygaszacz sterowany z HA, z pokazem zdjęć

Rozwinięcie [SPEC 0.13](SPEC-0.13-wygaszacz.md). Wygaszacz przestaje być funkcją nocną i lokalną: tryb, czasy
i progi przychodzą z Home Assistanta, a tłem może być pokaz slajdów z domowego magazynu zdjęć.

## 1. Co się zmienia wobec 0.13

| | 0.13 (jest) | 0.14 (cel) |
| --- | --- | --- |
| kiedy wchodzi | tylko po ciemku | `off`, `dark` (jak dziś) albo `always` |
| skąd konfiguracja | ustawienia zegara | Home Assistant |
| tło | czerń | czerń **albo** pokaz slajdów |
| zegar | szary na czerni | ten sam, a na zdjęciu z przyciemnieniem i cieniem |

Logika decyzji z 0.13 zostaje bez zmian - `ScreensaverPolicy` dostaje tylko nowe parametry i jeden nowy tryb.
Tryb `always` to ta sama maszyna stanów z pominiętym warunkiem ciemności; warunki "nic nie wymaga uwagi"
obowiązują dalej, bo panel z powiadomieniem ma być widoczny także w dzień.

## 2. Konfiguracja po stronie HA

Rozszerzamy ładunek `appearance` o blok `screensaver` i podnosimy wersję formatu. Dziś `Appearance.parse()`
przyjmuje dokładnie trzy pola i wersję 1 - to jest zmiana po obu stronach, w `ha-helios` i w zegarze.

```json
{
  "version": 2,
  "theme": "night_blue",
  "screensaver": {
    "mode": "dark",            // off | dark | always
    "idle_seconds": 60,        // >= 15, bo tyle wynosi gwarancja po dotknięciu
    "dark_enter": 3,           // luksy; ignorowane w trybie always
    "dark_exit": 8,
    "photos": false,           // pokaz slajdów przy świetle; domyślnie wyłączony
    "photo_seconds": 120,      // co ile zmienia się zdjęcie
    "photo_dim": 45            // przyciemnienie zdjęcia w procentach
  }
}
```

Zegar waliduje każdą wartość i przy błędnej wraca do domyślnej, zamiast odmówić całej konfiguracji: wygaszacz
to nie jest funkcja, dla której warto wyłączyć panel. Brak bloku `screensaver` znaczy `mode: dark` z wartościami
domyślnymi, czyli zachowanie 0.13.

## 3. Skąd zdjęcia

**Zegar nie rozmawia z magazynem zdjęć.** Cały ruch idzie przez `ha-helios`, dokładnie tak jak dzisiejsze tło
panelu: integracja pobiera obraz, kadruje go do 800x480 i serwuje pod własną ścieżką, a zegar tylko prosi o
kolejny. Powody są trzy i każdy z osobna wystarcza:

- poświadczenia do magazynu zostają w HA, nie w aplikacji na zegarze stojącym w sypialni,
- kadrowanie i skalowanie robi maszyna z zapasem mocy, a nie Lenovo z 1 GB RAM,
- wymiana magazynu (Immich na PhotoPrism, katalog na album) nie wymaga dotykania zegara.

Źródłem jest **media source Home Assistanta**, więc obsługujemy każdy magazyn, który ma integrację z HA -
**Immich** jako pierwszy sprawdzony, dalej PhotoPrism, Nextcloud czy zwykły katalog lokalny. W konfiguracji
integracji podaje się identyfikator albumu albo ścieżkę; `ha-helios` losuje z niej zdjęcia.

Nowa ścieżka: `GET /api/helios/screensaver/next?after=<id>` zwraca gotowy obraz 800x480 wraz z nagłówkiem
identyfikatora, żeby zegar mógł poprosić o kolejny i nie dostać tego samego dwa razy pod rząd.

## 4. Wygląd

**Ciemność zawsze wygrywa ze zdjęciami.** Poniżej progu `dark_enter` wygaszacz pokazuje czerń, niezależnie od
tego, czy pokaz jest włączony - w nocy w sypialni świecące zdjęcie jest gorsze od braku wygaszacza. Pokaz
działa wyłącznie przy świetle i tylko wtedy, gdy `photos: true`; domyślnie jest wyłączony, bo to funkcja, którą
ktoś świadomie włącza dla konkretnego pokoju, a nie zachowanie, które ma się pojawić samo po aktualizacji.

- **Tło czarne**: jak w 0.13, szara godzina, data pod spodem. Tak wygląda każda noc.
- **Tło ze zdjęciem** (dzień, `photos: true`): zdjęcie na całym ekranie, na nim przyciemnienie `photo_dim`, godzina biała z miękkim
  cieniem. Cień jest konieczny, bo jasny kadr zjada szary tekst; samo przyciemnienie nie wystarcza przy
  zdjęciach o wysokim kontraście.
- Zmiana zdjęcia to przenikanie w pół sekundy, nie cięcie - nocą nagła zmiana jasności budzi.
- Przy braku zdjęcia (brak sieci, pusty album, błąd) wygaszacz pokazuje czerń. Nigdy nie pokazuje błędu na
  pełnym ekranie: to ma być zegar, nie komunikat.

## 5. Pamięć i sieć

- W pamięci trzymamy najwyżej dwa obrazy: bieżący i ten, który właśnie przenika. Zegar ma 1 GB RAM, a panel
  już dziś trzyma tło i okładki.
- Zdjęcia pobieramy z wyprzedzeniem jednego, nie więcej. Przy `photo_seconds: 120` to jedno żądanie na dwie
  minuty - w domowej sieci bez znaczenia, ale przy pokazie co 10 s już nie, więc dolna granica to 15 s.
- Po wyjściu z wygaszacza zwalniamy obrazy. Panel i tak przerysuje swoje tło.

## 6. Kryteria odbioru

1. `mode: always` - wygaszacz wchodzi po `idle_seconds` także w oświetlonym pokoju.
2. `mode: dark` - zachowanie identyczne z 0.13.
3. `mode: off` - wygaszacz nie wchodzi nigdy, niezależnie od światła i ciszy.
4. Zmiana trybu w HA działa bez restartu zegara i bez ponownego parowania.
5. `photos: true` przy świetle - po wejściu widać zdjęcie, po `photo_seconds` zmienia się przenikaniem.
5a. `photos: true` po ciemku - czerń, żadnego zdjęcia; po zapaleniu światła pokaz rusza przy następnym wejściu.
6. Godzina pozostaje czytelna na jasnym zdjęciu (biel z cieniem na przyciemnieniu).
7. Brak sieci w trakcie pokazu: zostaje ostatnie zdjęcie, a po jego wygaśnięciu czerń; żadnego komunikatu.
8. Błędna konfiguracja (np. `idle_seconds: 2`, `dark_exit` mniejsze od `dark_enter`) - wygaszacz działa na
   wartościach domyślnych, panel bez zmian.
9. Zużycie pamięci po godzinie pokazu nie rośnie (dwa obrazy, nie sto).

## 7. Ryzyka i pytania otwarte

- **Prywatność.** Zegar stoi w sypialni i pokazuje losowe zdjęcia z rodzinnego albumu. Album wybiera człowiek
  w HA, ale warto to powiedzieć wprost w dokumentacji: co trafi do albumu, to pokaże się na ścianie.
- **Immich w HA.** Integracja `immich` jest w Home Assistancie jako media source; przed implementacją trzeba
  potwierdzić na ich instalacji (HA 2026.8.3), czy jest dostępna i jakie identyfikatory albumów wystawia.
- ~~Jasność nocą.~~ Rozstrzygnięte przez użytkownika: w ciemności zawsze czerń, pokaz tylko przy świetle i
  tylko po świadomym włączeniu. Ten sam próg `dark_enter`, który decyduje o wejściu w tryb `dark`, decyduje
  o tym, czy tłem jest czerń czy zdjęcie - jedna liczba, dwie role, nic więcej do strojenia.
- **Wypalenie.** Przy pokazie slajdów problem znika sam; przy czerni pozostaje jak w 0.13.
