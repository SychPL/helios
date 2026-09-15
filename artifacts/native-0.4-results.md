# Helios 0.4.0

- Specyfikacja: docs/SPEC-0.4-hidden-menu.md, zapisana przed implementacją.
- Bez przycisków Menu/Porozmawiaj; przytrzymanie HELIOS otwiera menu. Ikona HA niebieska/szara. Brak przełącznika wake word.
- Skróty menu pochodzą z wizualnie edytowanych standardowych kart HA. Dodano zakładkę menu-zegara do istniejącego panelu z backupem i readback; reguły wskaźników zachowane.
- Cache ostatniego poprawnego menu; błąd menu nie wyłącza obsługi wskaźników. Brak wykonywania akcji bez dotknięcia użytkownika.
- Build i lint: PASS. 11 testów JVM: PASS; w tym kolejność i cache menu, dozwolone URL, brakująca/pusta definicja, zmiana menu na żywo i niezależność błędów menu od stanów encji.
- Emulator 800x480, osobna APK UI bez biblioteki ARM, bez zgody na mikrofon: rzeczywisty HA dostarczył menu; sprawdzono ikonę i brak przycisków, przytrzymanie HELIOS oraz otwarcie Android Settings z pozycji pobranej z HA. Brak testu dźwięku w emulatorze.
- Produkcyjny APK zawiera ARMv7; brak tokenu HA w APK. Pobierany plik zgodny z buildem.
- Instalacja 0.4.0 na fizycznym zegarze nie jest potwierdzona.

SHA256: cfea2f9b27142ec4c9fb3e21802559f6a6eda7546595900c38f81c06c7a2fd51
