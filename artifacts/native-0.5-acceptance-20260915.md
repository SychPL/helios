# Helios 0.5 — odbiór fizycznego zegara, 2026-09-15

Uzupełnia wcześniejszy [raport implementacji i emulatora](native-0.5-results.md). Nie zastępuje jego wyników historycznych.

## Potwierdzone

- Odczyt PackageManager na fizycznym zegarze: `pl.mateusz.helios`, `versionName=0.5.0`, `versionCode=7`. Wersja była już zainstalowana; nie wykonano zbędnej reinstalacji ani resetu danych.
- Sonda tylko do odczytu: `probes/acceptance/HeliosPackageStatus.java`. Wynik prywatny: `.local/helios-package-before.txt`.
- Prawdziwy HA: 2026.8.3, HTTP 200 i poprawne uwierzytelnienie WebSocket.
- Panel `helios-clock` miał sekcję `helios.version: 1`. Migracja zastąpiła wyłącznie tę sekcję konfiguracją v2 z `ha/helios-clock.yaml`; istniejące `views` zachowano. Przed zapisem sprawdzono brak równoległej zmiany dokumentu i istnienie wszystkich użytych encji. Odczyt po zapisie był identyczny z zapisanym dokumentem.
- Migracja zawiera wyłącznie zegar, pogodę i informacyjny stan garażu; nie wywołano usług światła, bramy ani rolet.
- Zrzut ekranu fizycznego urządzenia po zapisie pokazuje nowy dashboard: zegar 14:21, pogodę 21°C / Deszcz / Wiatr 8 km/h, bramę ze stanem `close` i niebieską ikonę HA. Potwierdza renderowanie układu i rzeczywistych danych, nie fizyczny stan bramy.
- `assembleDebug lintDebug testDebugUnitTest`: BUILD SUCCESSFUL. Lint: 0 błędów, 7 ostrzeżeń.
- Dodatkowo `:app:testDebugUnitTest --rerun-tasks`: świeży przebieg, 13 testów, 0 błędów/niepowodzeń/pominięć (9 DashboardSpecTest, 4 HaDashboardClientTest).

## Kopia i dowody lokalne

- Kopia bezpośrednio przed zapisem: `.local/ha-dashboard-backup-acceptance-20260915T122037Z.json`.
- Dokument zapisany i odczytany kontrolnie: `.local/ha-dashboard-acceptance-v2.json`.
- Zrzut: `.local/helios-0.5-physical-after-migration.jpg`.
- Preflight: `.local/ha-preflight-current.json`.

Pliki `.local` pozostają niewersjonowane. Wycofanie migracji wymaga porównania aktualnego dokumentu i przywrócenia poprzedniej sekcji `helios` z kopii; nie nadpisywać późniejszych zmian innych widoków. Schemat v1 jest przeznaczony dla starej aplikacji 0.4, więc rollback konfiguracji należy skoordynować z wersją APK.

## Niepotwierdzone w tym odbiorze

- Słyszalność piku, odpowiedź TTS i 6-sekundowe dopowiedzenie bez hasła na zegarze: poproszono użytkownika o krótką próbę; wynik nie został jeszcze dostarczony.
- Fizyczne sterowanie roletą/bramą/światłem i dotykowe dialogi na zegarze nie były wykonywane. Wcześniejszy raport dokumentuje testy emulatora.
- Odbiór nie udowadnia tożsamości binarnej z lokalnym APK: odczytano wersję pakietu, nie hash zainstalowanej aplikacji.
- Lampka i sensor ładowania nie są jeszcze funkcjami Heliosa 0.5; zakres integracji zapisano w [SPEC 0.7](../docs/SPEC-0.7-home-assistant-integration.md).
- Music Assistant: początkowo HA nie miał skonfigurowanego wpisu integracji `music_assistant`. Następnie użytkownik uruchomił dodatek, a `/info` potwierdziło MA 2.10.3, schemat API 65, port 8095, `onboard_done=false`. Konfiguracja konta i test audio pozostają do wykonania; [osobny raport prototypu](music-assistant-prototype.md).

Nie zmieniano pipeline głosowego, ustawień launchera, wersji HA ani firmware urządzenia. Nie uruchamiano muzyki, nie instalowano Music Assistant i nie dodawano integracji 0.7.
