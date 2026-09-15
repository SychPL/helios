# Helios 0.3.0 — konfiguracja ekranu z HA

2026-09-15.

- Utworzono i odczytano ponownie dedykowany panel HA /helios-clock. Zawiera regułę dla wskazanego przez użytkownika czujnika, poza APK.
- Rzeczywisty stan czujnika podczas odczytu: close. Nie zmieniano czujnika ani bramy.
- Build, lint i sześć testów JVM: PASS. Testy pokrywają rozłączne reguły, nieznane stany, utratę danych, dekodowanie snapshot/diff/remove, zmianę encji przez konfigurację i reconnect WebSocket.
- Emulator x86: osobny pakiet pl.mateusz.helios.uitest bez biblioteki ARM, bez zgody na mikrofon. Odczytał rzeczywistą konfigurację i pogodę z HA. Zweryfikowano widok 800x480.
- Osobny lokalny serwer testowy podał open tylko emulatorowi; zweryfikowano ikonę i podpis Garaż otwarty. Po zatrzymaniu serwera pojawił się baner HA niedostępny — stan wskaźników nieznany. Serwer zatrzymano, pakiet testowy usunięto, emulator wyłączono.
- Produkcyjny APK nadal zawiera ARMv7 microWakeWord. Usunięto ekranowy przełącznik hasła; nasłuch wraca po rozmowie i działa przy widocznym Heliosie. Fizyczny przełącznik pozostaje po stronie urządzenia.
- Zweryfikowano brak tokenu HA i identyfikatora czujnika garażu w APK. Pobranie HTTP zgodne z wynikiem budowania.
- Instalacja 0.3.0 na zegarze i sprawdzenie realnej zmiany stanu bramy czekają na użytkownika. Nie wykonano nowego testu audio na zegarze.

SHA256 APK: 154d17df8423f2ad39e144070fc12d311c30b95c0076e9fffc4abcb622a40f5c
