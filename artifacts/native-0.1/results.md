# Helios 0.1.0 — zegar, pogoda i przycisk rozmowy

## Artefakt

- Pakiet `pl.mateusz.helios`, versionCode 1, versionName 0.1.0.
- APK `app/build/outputs/apk/debug/app-debug.apk`, 482 310 bajtów.
- SHA256 `a1a00900829b9451e7fcdb248b51482a8f967fb45f17dee83586aec2f03f9a2a`.
- Samodzielna aplikacja; nie zastępuje `pl.mateusz.clockadbprobe`.
- minSdk/targetSdk 29, compileSdk 35, Java 8, natywne Android Views.
- Token HA nie jest składnikiem APK. Konfiguracja pobierana z krótkotrwałego lokalnego endpointu i zapisywana w prywatnych danych aplikacji, backup wyłączony.

## POTWIERDZONE: build i emulator

- `assembleDebug lintDebug`: sukces, brak błędów Lint. Pozostałe ostrzeżenia dotyczą polskich tekstów w kodzie i orientacji landscape na nowszych Androidach.
- Emulator API 36, ekran testowy 800×480, bez hostowego audio. To nie jest pomiar wydajności Lenovo ani test jego mikrofonu.
- Zweryfikowano układ natywnego ekranu: `emulator.png`.
- Parowanie z prawdziwym HA i odczyt `weather.forecast_dom`: sukces. W odczytach pojawiło się 15,9°C/pochmurno, a później 17,7°C/deszcz. UI zaokrągla temperaturę do pełnego stopnia.
- Zegar wyświetla lokalny czas środowiska: emulator używał UTC. Zegar Lenovo zgłasza `Europe/Warsaw`; nie zmieniano strefy.
- Przycisk emulatora otworzył sesję Assist, odebrał `run-start` i `stt-start`, uruchomił AudioRecord.
- Próba bez wypowiedzianego zdania zakończyła się `stt-no-text-recognized` i powrotem do gotowości.
- Kolejna próba, przerwana wyjściem do HOME emulatora: `microphone_released`, `Cancelled`, `ready/microphone_off`.
- Logi powyższych prób: `device-events.jsonl` (na tym etapie pochodzą z emulatora), obraz stanu nasłuchu: `emulator-button.png`.

## POTWIERDZONE: istniejący agent na Lenovo

- Wcześniejszy DEX klienta połączył się bezpośrednio z zegara do HA przez WebSocket i uzyskał `auth_ok`. Dowód: `../assist-button-20260915/connection-check.json`.
- Rzeczywisty mikrofon VOICE_COMMUNICATION i microWakeWord potwierdzono w osobnych wcześniejszych próbach przez UID Clock ADB Probe. Nie przenosimy tego potwierdzenia automatycznie na nowy UID Heliosa.
- Obecny odczyt `canDrawOverlays=false`. Próba nakładki nie została wykonana; zwykłe wywołania startActivity/am start nie dały potwierdzenia widocznego okna aplikacji. Nie zmieniano tych uprawnień.

## Instalacja na Lenovo: OCZEKUJE NA POTWIERDZENIE

APK przesłano istniejącym kanałem DEX do PackageInstaller. Most instalacyjny zweryfikował SHA256 i pakiet `pl.mateusz.helios`, następnie zatwierdził sesję instalatora. Nie uzbrajał AutoTap, nie zmieniał HOME i nie tworzył alarmu relaunch diagnostycznej aplikacji.

To nie dowodzi zakończenia instalacji. Ostatni odczyt `pm list packages pl.mateusz.helios` nie zwrócił pakietu. Potrzebna obserwacja i ewentualne potwierdzenie systemowego instalatora na ekranie. Dowody: `deployment.json`, `device-package-status.txt`.

## NIESPRAWDZONE

- Wyświetlenie tego dashboardu i odczyt pogody w nowej aplikacji na fizycznym Lenovo.
- Zgoda mikrofonu dla nowego UID, transkrypcja rzeczywistej wypowiedzi, odpowiedź na głośniku Lenovo, powrót do gotowości.
- Kontrolowana akcja HA, dystans 2 m, różni domownicy, współdziałanie z Google.
- Wake word w nowej APK, nocna jasność, ekran wyłączony, autostart i praca całodobowa.

Syntezę stałego zdania przez HA Cloud i pobranie MP3 na PC sprawdzono osobno: `../ha-tts-smoke-20260915.json`. Nie był to test głośnika zegara.
