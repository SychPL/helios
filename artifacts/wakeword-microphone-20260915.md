# Helios: mikrofon oddaje 6x za dużo próbek - wake word i STT nie działają (2026-09-15)

Status: otwarty. Do zbadania po stronie zegara (system, inne aplikacje), nie kodu Heliosa. Dokument dla kolejnego agenta / sesji.

## Objaw

Od 2026-09-15 ok. 22:19 (czas lokalny) zegar Lenovo Smart Clock 2 z Heliosem nie wykrywa hasła „Okay Nabu”, a ręczna rozmowa (Menu → Rozmowa z Nabu) kończy się `stt-no-text-recognized`. Użytkownik: „ewidentnie mikrofon nie słyszy”. Wcześniej tego samego dnia (21:06-22:19) detekcje i rozmowy działały poprawnie, także podczas grania muzyki z Music Assistant.

## Twarde dane (dziennik zdarzeń zegara przez most, `artifacts/native-0.1/device-events.jsonl`)

Zdrowy okres (do 22:19), ręczne nagrania rozmowy (`AudioRecord`, źródło 7 = `VOICE_COMMUNICATION`, 16 kHz mono):

| Czas | Próbek | Czas nagrania | Próbek/s | RMS |
| --- | --- | --- | --- | --- |
| 21:23:24-30 | 94 720 | 6 s | ~15 800 | 41 |
| 22:03:27-40 | 192 960 | 13 s | ~14 800 | 20 |

Po incydencie:

| Czas | Próbek | Czas nagrania | Próbek/s | RMS | Wynik |
| --- | --- | --- | --- | --- | --- |
| 22:51:42-44 | 185 536 | ~2 s | ~93 000 | 722 | stt-no-text |
| 23:16:39-42 | 341 056 | ~3,5 s | ~97 000 | 658 | stt-no-text |
| 23:16:52-54 | 198 976 | ~2 s | ~99 000 | 54 | stt-no-text |

Nasłuch hasła z diagnostyką (`wake_level` co 15 s, wprowadzone w 0.8.9-0.8.12; ramka = 160 próbek = 10 ms, zdrowo 1500 ramek na 15 s):

| Wersja / czas | Źródło | Ramek / 15 s | Próbek/s | Urządzenie |
| --- | --- | --- | --- | --- |
| 0.8.9, 23:22-23:27 | 7 (VOICE_COMMUNICATION) | 8 100-8 900 | ~90 000 | - |
| 0.8.11, 23:32-23:36 (po twardym restarcie) | 7 | 8 700-9 000 | ~95 000 | typ 15 = wbudowany mikrofon, `LenovoCD-24502F`, rate=16000, channels=1, obsługiwane 8000-48000 |
| 0.8.12, 23:37-23:39 | 6 (VOICE_RECOGNITION) | dokładnie 9 000 | 96 000 | jak wyżej |

`AudioRecord.getSampleRate()` zgłasza 16000, `getChannelCount()` 1, `getRoutedDevice()` = wbudowany mikrofon. Mimo to strumień ma 96 000 próbek/s. 96 000 / 16 000 = 6 = 48 000 Hz × 2 kanały / 16 000 Hz. Wartości RMS (18-40 w ciszy, piki przy mowie) wyglądają na prawdziwe audio, tylko sześciokrotnie „przyspieszone” lub przeplecione. Model wake word (microWakeWord, 16 kHz) i STT w HA nie mają czego rozpoznać.

Wykluczone:
- Kod Heliosa 0.8.5-0.8.13: diff od działającego 0.8.4 nie dotyka `AudioRecord`, `AudioManager`, focusu ani `WakeWordListener`; ten sam kod (`WakeWordListener`, `AssistClient`) dawał 16 k próbek/s do 22:19.
- Zawieszony HAL po ubitej rozmowie: twardy restart zasilania (zegar bez baterii, odłączony) nic nie zmienił; były też trzy wcześniejsze restarty (zdarzenia ze znacznikiem „04:15:53” to starty przed synchronizacją czasu).
- Brak miejsca: `free_mb=3187`, dziennik aplikacji 121 KB (od 0.8.12 rotowany przy 2 MB).
- Źródło audio: 6 i 7 dają identyczny wynik.
- Wyjście audio jako przyczyna (AEC z referencją 48 kHz stereo): odtwarzacz Heliosa był `idle`, `AudioTrack` zamknięty, a strumień dalej 96 k/s.
- Mikrofon USB/Bluetooth: `getRoutedDevice()` = wbudowany.

## Kontekst incydentu (22:19:33)

Wake word wykrył, ruszył mikrofon rozmowy (`microphone_started`), przez 57 s brak `stt-vad-start` (HA było wtedy restartowane po aktualizacji integracji ha-helios 0.7.5/0.7.6, która nie ładowała się do 0.7.6), aplikacja została zamknięta z otwartym `AudioRecord`, potem restart. Od tego momentu każde nagranie ma 6x za dużo próbek. Równolegle użytkownik przeniósł zegar z łazienki do kuchni (kilka wyłączeń zasilania po drodze).

## Główna hipoteza

Inny klient audio na zegarze (najpewniej fabryczny Asystent Google / usługa OEM „Scoria”, startująca przy boocie) otworzył wejście mikrofonu w formacie 48 kHz stereo (hotword „Hey Google” lub tryb asystenta), a vendorowy HAL/AudioFlinger tego urządzenia nie przelicza formatu per klient i oddaje naszemu 16 kHz mono klientowi surowy strumień 48 kHz stereo. Do 22:19 Helios był jedynym klientem i wejście działało w 16 kHz mono. Coś (przeniesienie zegara, dotknięcie fabrycznego UI, odblokowanie Asystenta po restarcie) uruchomiło obcy klient i stan przeżywa reboot, bo klient startuje z systemem.

Hipoteza alternatywna: trwała zmiana w konfiguracji audio systemu (np. tryb „komunikacja” ustawiony przez inną aplikację, `AudioManager.setMode`, albo profil Bluetooth), którą reboot odtwarza.

## Co sprawdzić po stronie zegara (agent / użytkownik)

1. Zainstalować **0.8.13** (`helios-0.8.13.apk` na moście): `wake_level` dopisuje `active=[{src=… client=rate/ch device=rate/ch session=…}]` z `AudioManager.getActiveRecordingConfigurations()`. Wpis obcy z `device=48000/2` potwierdza hipotezę; brak obcych wpisów ją obala.
2. Na zegarze: Menu Heliosa → Aplikacje - czy działa Asystent Google / „Google” / usługa Scoria; czy po przeniesieniu nie włączył się „Hey Google” (ustawienia Asystenta, „Voice Match”). Wyłączyć/dezaktywować i sprawdzić `wake_level`.
3. Ustawienia zegara → Aplikacje → uprawnienie mikrofonu: która aplikacja ma dostęp i czy jakaś jest „aktywna w tle”.
4. Jeśli adb dostępne: `dumpsys audio` (sekcja „Audio Record Clients” i „mode”), `dumpsys media.audio_flinger` (RecordThread: format, sample rate, ilu klientów), `dumpsys media.audio_policy` (aktywne wejścia i ich konfiguracja). Szukać wejścia 48000 Hz / 2 kanały i klienta innego niż `pl.mateusz.helios`.
5. Test krzyżowy: nagranie w innej aplikacji (np. dyktafon systemowy) - jeśli też „przyspieszone”, problem jest systemowy; jeśli poprawne, HAL różnicuje klientów i wtedy patrzeć na format naszego `AudioRecord` względem obcego.
6. Ostatnia deska: reset do ustawień fabrycznych zegara i ponowna instalacja Heliosa (parowanie kodem z HA, konfiguracja z mostu).

## Obejście po stronie Heliosa (jeśli źródło jest obce i nieusuwalne)

Jeżeli strumień to naprawdę 48 kHz stereo, aplikacja może go sprowadzić do 16 kHz mono sama: brać co 6. próbkę z uśrednieniem par (L+R)/2 i decymacją 3:1 z prostym filtrem. To odgadywanie formatu na podstawie tempa napływu (`próbki/s ÷ 16000 ≈ 6`) i tylko obejście; wymaga potwierdzenia formatu z pkt 1 lub 4. Nie wdrażać przed diagnozą.

## Odniesienia

- Kod: `app/src/main/java/pl/mateusz/helios/WakeWordListener.java` (diagnostyka `wake_level`, źródło 6 od 0.8.12), `AssistClient.java` (nagranie rozmowy, źródło 7, linia ~132).
- Dziennik: `artifacts/native-0.1/device-events.jsonl` (most `tools/native_bridge.py`, port 8757, zdarzenia POST `/events`).
- Wersje: zegar 0.8.13 dostępny; HA: ha-helios 0.7.6 (poprawka importu, parowanie działa).
