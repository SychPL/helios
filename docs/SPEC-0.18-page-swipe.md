# SPEC 0.18 - przełączanie stron na zegarze

Status: zaimplementowane w Helios 0.15.0 (versionCode 39).

## Wymagania właściciela (2026-09-24)

- Przesunięcie palcem w poziomie zmienia stronę (lewo = następna, prawo = poprzednia, bez zawijania).
- Kropki stron są tylko wskaźnikiem (nieklikalne, poza fokusem i czytnikiem), rysowane nad kartami w pasku pod
  napisem HELIOS, tak żeby nie przesłaniały żadnej karty; widoczne tylko podczas dotyku.
- Po 2 minutach bez dotyku zegar wraca na pierwszą stronę.

## Zachowanie

- Gest (`PageSwipe`): ruch uznany za boczny, gdy |dx| > 24 i |dx| > 2|dy| (jednostki 800x480) - wtedy
  `DashboardView` przechwytuje gest i kafelek dostaje CANCEL zamiast kliknięcia. Strona zmienia się po puszczeniu
  palca, gdy |dx| >= 80 i nadal |dx| > 2|dy|. Gest zaczęty na pustym polu siatki działa tak samo.
- Nie przełącza: przy jednej stronie, przy otwartym panelu muzyki, przy zegarze nocnym.
- Kropki: pojawiają się na DOWN (120 ms), gasną 1,5 s po UP/CANCEL (300 ms) albo od razu, gdy okno traci fokus
  (otwarte okno panelu). Bieżąca kropka pełna, pozostałe przygaszone.
- Powrót: licznik 2 min startuje po puszczeniu palca i po każdej zmianie strony, staje przy utracie fokusu okna
  i rusza od nowa po jego odzyskaniu; dotyk budzący zegar nocny też go resetuje.
- Strona przetrwa nowy dokument z HA, dopóki ten dokument ma tyle stron (indeks, nie id strony).
- Widoczność warunkowa, blokada wygaszacza przez ostrzeżenia i wybór prognozy biorą wszystkie strony
  (`DashboardSpec.allItems`). Prognoza jutra jest przypisana do encji, dla której ją zasubskrybowano; szeroki
  kafelek pogody z inną encją pokazuje samo dziś.
- Kafelki niewidocznej strony nie istnieją jako widoki; stan wywołań w toku jest trzymany osobno i odtwarzany
  przy przebudowie strony.

Recenzja Codex: APPROVE po 3 rundach.
