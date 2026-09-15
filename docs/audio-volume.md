# Głośność Lenovo Smart Clock 2 — dane do implementacji Heliosa

Źródło: [raport z repozytorium urządzenia](../../docs/audio-volume.md) i wynik testu przekazany przez użytkownika 2026-09-15. To wcześniejsza próba przez agenta urządzenia, nie test nowej funkcji w APK Heliosa.

**Uwaga o odnośnikach:** ścieżki zaczynające się od `../../` prowadzą poza repozytorium `dash`, do nadrzędnego repozytorium urządzenia `lenovo_clock` (jego `docs/` i `tools/`). Działają przy układzie `lenovo_clock/dash`; mogą nie działać w samodzielnym klonie `dash`. Niniejsza nota zawiera samodzielne podsumowanie wyników potrzebnych do implementacji; zewnętrzna sonda nie jest zależnością budowania Heliosa.

## Potwierdzone w raporcie

- `AudioManager.getStreamVolume` i `setStreamVolume` działają na fizycznym zegarze bez roota; raport podaje brak konieczności dodatkowych uprawnień dla tej próby.
- MUSIC ma zakres 0–100. Sekwencja 42 → 30 → 80 → 42 została potwierdzona odczytami i według użytkownika odsłuchem.
- Jedno wyjście: wbudowany głośnik. Raport nie wykazał ograniczenia fixed volume.
- Odczyty w chwili próby: MUSIC 42/100, ALARM 1/100, RING/NOTIFICATION/SYSTEM 48/100. Nie są to wartości domyślne ani polecenie ich przywracania.

## Kontrakt Heliosa

- Suwak głośności urządzenia używa `AudioManager.STREAM_MUSIC`, skali 0–100 i flagi 0, bez systemowego okienka. `AudioManager.FLAG_SHOW_UI` można użyć tylko w lokalnej interakcji, jeśli projekt UI tego wymaga.
- Przy uruchomieniu odczytaj zakres i aktualną wartość z Androida. Nie ustawiaj automatycznie 42 ani innej zapamiętanej wartości przy starcie/reconnect.
- MUSIC jest wspólny z fabrycznym Cast UI, muzyką oraz odpowiedziami/pikiem Heliosa. Zmiany pochodzące z Casta lub przycisków trzeba odczytać i odzwierciedlić w HA, nie nadpisywać ich stale własną nastawą.
- Proponowana detekcja: odczyt przy starcie/wznowieniu UI, po własnym ustawieniu i polling co 3 s podczas działania modułu urządzenia. Ukryty broadcast `android.media.VOLUME_CHANGED_ACTION` jest opcjonalnym sygnałem do wcześniejszego odczytu po próbie na OTA 627, nie zamiennikiem pollingu ani publicznym kontraktem API.
- Głośność muzyki MA pozostaje osobnym wzmocnieniem `AudioTrack`, opisanym w SPEC 0.6. Nie zmienia globalnego suwaka MUSIC.
- ALARM jest osobnym strumieniem. Niski odczyt jest ostrzeżeniem do przyszłego projektu budzika, nie zgodą na podniesienie głośności alarmów teraz. Budzik nie wchodzi automatycznie do zakresu 0.6/0.7.
- Odbiór implementacji musi potwierdzić regulację z HA i APK, odczyt zmian zewnętrznych, rozdzielenie nastawy muzyki i urządzenia oraz brak zaległych poleceń po reconnect.

Sonda źródłowa: [VolumeProbe.java](../../tools/lamp-probe/VolumeProbe.java). Argument pusty oznacza odczyt; `set:<stream>:<vol>` i `sweep` zmieniają głośność. Nie uruchamiać ich jako części testów tylko do odczytu.
