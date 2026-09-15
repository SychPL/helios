# HELIOS — microWakeWord na zegarze, 15 września 2026

## POTWIERDZONE: uruchomienie silnika i przetwarzanie mikrofonu w tle

Zbudowano silnik z modułu microWakeWord aplikacji Home Assistant dla `armeabi-v7a`, Android API 29, Release, ze statyczną biblioteką C++. Załadowano go przez DEX do istniejącego Clock ADB Probe v2.13, bez instalowania APK i zmiany HOME. Zegar zgłosił SDK 29, ABI `[armeabi-v7a, armeabi]`, UID 10058.

### Próba syntetyczna

Dowód: `20260915T075956Z/result.txt`.

- Natywna biblioteka załadowała się, model został zainicjalizowany.
- 32 000 próbek cyfrowej ciszy: 0 detekcji, bez wyjątku.
- Stan detektora zresetowany; zasoby silnika zwolnione.
- To próba uruchomienia i przetwarzania; nie jest pozytywnym testem hasła.

### Minutowa próba z mikrofonu

Dowód: `20260915T080007Z/result.txt`, statusy agenta przed/po w tym samym katalogu.

Dodatkowo `native-log.txt` potwierdza inicjalizację frontendu i interpretera: 16 kHz, 40 pasm mel, okno 30 ms, krok 10 ms, stride=3, arena interpretera 65 536 bajtów. W odczytanych logach tych komponentów nie ma błędów. Arena rzeczywistego silnika HA różni się od `tensor_arena_size=37000` z metadanych modelu dla ESPHome.

| Pomiar | Wynik |
|---|---|
| Czas pętli | 60 000 ms |
| Źródło / format odczytany z AudioRecord | VOICE_COMMUNICATION (7), 16 kHz, mono, PCM16 |
| Próbki odczytane | 960 320 |
| RMS / peak / próbki niezerowe | 35,19 / 528 / 96,56% |
| Detekcje | 0 |
| CPU wątku pomiarowego | 4933 ms, 8,22% jednego rdzenia przez czas próby |
| Łączny czas wywołań nativeProcessAudio | 2268,19 ms czasu ściennego |
| PSS całego procesu podczas próby | 20 646–20 694 KiB, około 20,2 MiB |
| Importance w zapisanych oknach | 125, proces z usługą pierwszoplanową |
| Ekran | interactive=true |
| Wyciszenie klienta przez system | silenced=false we wszystkich zapisanych oknach |
| Koniec | RESULT=COMPLETED, AUDIO_RELEASED, ENGINE_RELEASED |

CPU dotyczy naszego wątku, nie wszystkich wątków procesu ani całego urządzenia. PSS dotyczy całego Clock ADB Probe, nie samego modelu. W procesie pozostała również biblioteka załadowana przez wcześniejszą próbę syntetyczną; nie wyznaczono izolowanego kosztu RAM silnika. Zwolnienie instancji silnika nie oznacza wymuszonego usunięcia biblioteki z pamięci procesu.

Użytkownik wcześniej zgłosił rozmowy wielu osób przy zegarze. Nie oznaczano konkretnych wypowiedzi ani nie potwierdzano nieobecności hasła podczas tej minuty. Dlatego zapisujemy **0 detekcji podczas 60 sekund**, a nie wyliczony wskaźnik fałszywych wybudzeń ani potwierdzoną skuteczność. Nie zapisano/przesłano PCM, nie wykonano transkrypcji ani akcji HA. Nie zastosowano dodatkowego gainu; działa przetwarzanie wstępne silnika upstream.

## Model i pochodzenie

- Model: **Okay Nabu**, wersja 2, autor Kevin Ahrendt, 60 264 bajty.
- Parametry z JSON: feature step 10 ms, cutoff 0,85, sliding window 5.
- Model SHA256: `0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891`.
- Kod silnika: [Home Assistant Android, przypięty commit](https://github.com/home-assistant/android/tree/bdf91410ee54bf3a2baf698af5c071c5cbac4ea2/microwakeword).
- Kod pochodzi z repozytorium z [LICENSE.md Apache 2.0](https://github.com/home-assistant/android/blob/bdf91410ee54bf3a2baf698af5c071c5cbac4ea2/LICENSE.md).
- Bajty modelu porównano i są identyczne z [ESPHome models/v2/okay_nabu.tflite](https://github.com/esphome/micro-wake-word-models/blob/05b65922cc433c9df13e98e32a7fe520758c837e/models/v2/okay_nabu.tflite); to repozytorium deklaruje [Apache 2.0](https://github.com/esphome/micro-wake-word-models/blob/05b65922cc433c9df13e98e32a7fe520758c837e/LICENSE). Plik models/okay_nabu.tflite bez v2 ma inne bajty i nie został użyty.
- Metadata modelu wymienia en/nl/fr/de/it/es/sv; nie wymienia pl. Rozpoznanie hasła wypowiadanego przez polskich domowników wymaga rzeczywistego testu. Nie traktujemy „Hey Nabu” i „Okay Nabu” jako tej samej frazy.
- Zależności natywne i ich hashe są przypięte w upstream CMakeLists.txt. Pełny spis hashy użytych źródeł, DEX i biblioteki: `provenance.json` oraz `config.json` każdej próby.

## Home Assistant

POTWIERDZONE: `http://192.168.1.212`, HTTP głównej strony 200, API 401 bez tokenu, WebSocket `auth_required`, wersja serwera 2026.8.3. Dowód: `../ha-preflight-20260915.json`.

NIESPRAWDZONE: konfiguracja pipeline, STT/TTS, transkrypcja, akcja, odpowiedź z głośnika. Przygotowano `tools/ha_preflight.py`, który po dodaniu tokenu wykona wyłącznie odczyt listy pipeline. Lokalna konfiguracja `.local/ha.json` zawiera adres i pusty token; `.local/` jest ignorowane przez Git.

## Następny najmniejszy test

Po spotkaniu: kilka oznaczonych prób „Okay Nabu” z bliska oraz około 2 metrów. Odrębnie Google przed/podczas/po nasłuchu. Do integracji HA potrzebny jest token, a przed wysłaniem audio wybór kontrolowanej wypowiedzi. NIESPRAWDZONE pozostają wyłączony/przyciemniony ekran, różni domownicy, TTS wywołujący detektor, dłuższa stabilność i odzyskiwanie.
