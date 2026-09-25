# SPEC 0.17 - kafelek energii `energy`

Status: zaimplementowane w Helios 0.14.0 (versionCode 37) i ha-helios 0.13.0.

## Cel

Jeden kafelek 1x1 z trzema odczytami: moc produkowana przez PV, moc zużywana przez dom, procent baterii
(opcjonalnie). Tylko do odczytu, bez akcji.

## Schemat (version 6)

- `type: energy`, pola: `entity` (moc PV, domena `sensor`, wymagane), `load_entity` (zużycie domu, `sensor`,
  wymagane), `battery_entity` (bateria, `sensor`, opcjonalne), `icon`, `title` i pola wspólne.
- Komunikaty błędów jak w reszcie parsera: `Wymagane pole load_entity`, `load_entity wymaga encji sensor`,
  `battery_entity wymaga encji sensor`, `Element <id> wymaga encji z domeny sensor`,
  `Pole niedozwolone dla typu energy: <pole>`, `Typ energy wymaga version: 6`.
- Domyślnie tytuł `PV`, ikona `mdi:solar-power`. Brak `tap_action` i `confirmation`.
- Zegar subskrybuje wszystkie trzy encje i zachowuje z nich tylko `unit_of_measurement`.

## Wygląd

W miejscu tytułu, obok ikony: `produkcja / pobór`, np. `1392 / 702 W` (jedna jednostka na końcu, gdy obie encje
ją dzielą; inaczej każda przy swojej liczbie: `1504 W / 0,7 kW`). Pod spodem duża wartość: procent baterii
(układ `BIG_FIT`: dopasowanie do szerokości do rozmiaru temperatury z kafelka pogody). Bez `battery_entity`
tytułem zostaje `PV`, a dużą wartością jest para `produkcja / pobór`. Odczyty do jednego miejsca po przecinku,
`—` gdy stan nieznany. Opis dla czytnika: `PV: produkcja 1504 W, dom 698 W, bateria 68 %`.
Układ na prośbę właściciela (2026-09-24): pierwsza wersja z PV jako wartością i dwiema liniami pod spodem odrzucona.

## Zgodność

Zegar starszy niż 0.14 nie zna typu i odrzuca cały dokument (zostaje przy ostatnim dobrym układzie). Dokument z
kafelkiem `energy` wolno więc przypisać tylko zegarom z 0.14 lub nowszym - przy osobnych dashboardach per zegar
(SPEC 0.16) dotyczy to tylko zegarów danego dokumentu.

## Panel HA

Typ `Energia (PV, dom, bateria)` w edytorze, walidator lustrzany do parsera, pola z wyborem encji `sensor`.
