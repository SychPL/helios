# SPEC 0.11 - muzyka na pełnym ekranie, pauza kolejki MA, głośność urządzenia

Status: projekt po brainstormie z użytkownikiem (16 września 2026), do planu. Bez publikacji w HA i instalacji APK do czasu odbioru. Baza: Helios 0.8.17, ha-helios 0.7.6, MA 2.10.3 (Sendspin legacy).

## 1. Cel

Trzy zmiany w muzyce, wszystkie w aplikacji (integracja HA bez zmian):

1. **Pełny ekran muzyki** otwierany ręcznie z panelu: lewa połowa okładka, prawa połowa zegar, tytuł, wykonawca, przyciski, pasek postępu przez całą szerokość.
2. **Pauza nie kasuje karty.** Odtwarzacz Sendspin w MA 2.10.3 nie ma funkcji PAUSE (`music_assistant/providers/sendspin/player.py`: `supported_features` = PLAY_MEDIA, SET_MEMBERS, MULTI_DEVICE_DSP, VOLUME_SET, VOLUME_MUTE, PLAY_ANNOUNCEMENT), więc MA realizuje pauzę jako stop strumienia (`group.stop()` → `group/update playback_state: stopped`), a kolejka MA zachowuje pozycję. Zegar ma to rozpoznać i pokazać „Pauza” zamiast usuwać kartę.
3. **Głośność MA steruje urządzeniem**, nie wzmocnieniem strumienia (zmiana decyzji z SPEC 0.6 pkt 9 i 0.8a pkt 4.2 na życzenie użytkownika).

Poza zakresem: automatyczne otwieranie pełnego ekranu, wygaszacz, zmiany w bibliotece muzyki, tła 0.8b na pełnym ekranie, nowe komendy z HA.

## 2. Stan wyjściowy (kod 0.8.17)

- `SendspinClient.refreshState()`: `NONE` gdy `!sessionActive`; `stopped`/`idle` z `group/update` kończy sesję. `server/command volume` → `sink.setGain(volume/100)`, `mute` → `sink.setMuted`, potwierdzenie `client/state`.
- `HeliosService`: `MusicSession` (focus), `MusicSnapshot(ui, title, artist, album, artwork, accent, volume, muted, commands, maConnected, localConnected, remoteInfo, issue, progressMs, durationMs, progressAtMs)`, `musicCommand/musicSeek/musicMute`, `MusicAssistantClient` z lane’ami (`players/cmd/*`, `player_queues/play_media`, `player_queues/seek`), `DeviceVolume` (STREAM_MUSIC 0-100, encja `number` w HA, `audio.set_device_volume`).
- `MusicOverlay`: uchwyt + panel prawej połowy (geometria 4.1 z SPEC 0.8a, suwak pozycji, `pendingSeekUntil`, ticker 1 s), `IconButton`, `applyTheme()`.
- `docs/ma-api-2.10.3.md`: `player_queues/get_active_queue` (`player_id`) → `PlayerQueue` z `queue_id`, `state`, `current_item`.

## 3. Pełny ekran

### 3.1. Geometria (jednostki 800×480, pasek 52 zostaje)

| Element | Pozycja i rozmiar | Zasada |
| --- | --- | --- |
| Tło pełnego ekranu | x=0, y=52, 800×428 | Kolor bazowy motywu, 100 % krycia; zdjęcie tła 0.8b niewidoczne (jeden obraz na ekranie) |
| Okładka | x=14, y=66, 400×400 | Zaokrąglenie 18; bez okładki płytka `raised` z nutką 96×96; bitmapa ta sama, którą ma panel (bez ponownego dekodowania, `CENTER_CROP`) |
| Zamknięcie, chevron w dół | x=728, y=52, 72×72 | Zawsze widoczny; „Wróć do dashboardu”; systemowe cofnięcie robi to samo |
| Zegar HH:MM | x=440, y=70, 280×80 | Geist Mono 72, tekst motywu; aktualizowany z istniejącego ticku |
| Tytuł | x=440, y=160, 344×96 | 26, maks. 3 linie, wielokropek, „Nieznany utwór” |
| Wykonawca | x=440, y=262, 344×56 | 20, maks. 2 linie, pusty bez myślnika |
| Poprzedni / play-pauza / następny / stop / wyciszenie | x=440 / 516 / 592 / 668 / 744; y=330; każde 56×72 hitbox z ikoną 32 | Kolejność i ikony jak w panelu; play-pauza z akcentem okładki jak w panelu; odstępy 20 między hitboxami (76 kroku, 56 szerokości) |
| Etykieta czasu lewa / prawa | x=14, y=418, 72×24 / x=714, y=418, 72×24 | 16, `m:ss` |
| Pasek postępu | x=90, y=412, 620×36 | Suwak pozycji jak w panelu (jedna komenda `player_queues/seek` po puszczeniu, `pendingSeekUntil` 10 s); brak `track_duration` = suwak wyłączony, prawa etykieta pusta |

Hitboxy 56×72 są węższe od 72 z SPEC 0.8a, żeby zmieścić pięć w 344 jednostkach z odstępami; test geometrii wymaga braku nakładania i minimum 56×72. Rysowana ikona 32.

### 3.2. Zachowanie

- Wejście: przycisk „Pełny ekran” (ikona `fullscreen`, 72×72) na panelu w polu x=248, y=0 (między statusem a chevronem; status skrócony do szerokości 232). Wyjście: chevron w dół lub systemowe cofnięcie. Stan widoku (dashboard / panel / pełny ekran) jest pamiętany do końca sesji UI; nowa sesja zaczyna od uchwytu.
- Pełny ekran jest trzecią warstwą `MusicOverlay` nad panelem: dashboard i panel pod spodem nie są przebudowywane; przy zamknięciu wraca panel w stanie sprzed wejścia (otwarty).
- Dotyk poza elementami pełnego ekranu nic nie robi (warstwa konsumuje wszystkie dotknięcia w polu 0,52,800,428). Pasek statusu (0-52) działa jak dotąd, w tym przytrzymanie HELIOS → menu.
- Pauza (dowolne źródło): pełny ekran zostaje, przycisk pokazuje ikonę play, zegar i metadane bez zmian; osobny napis „Pauza” nie jest potrzebny. Stop/idle/koniec sesji: warstwa znika razem z panelem i uchwytem (dashboard).
- Rozmowa z Nabu, powiadomienia HA, zmiana motywu i tła: pełny ekran nie znika; `applyTheme()` przemalowuje bez przebudowy.
- Dialogi (rolety, klawiatura, biblioteka) otwierają się nad pełnym ekranem jak nad panelem; otwarcie biblioteki zwija pełny ekran do uchwytu (jak dziś panel).
- Wygaszanie: nie ma; pełny ekran świeci jak dashboard (jasność jak dotąd, +20 pkt po dotknięciu).

## 4. Pauza kolejki MA

### 4.1. Kontrakt

- Po `group/update playback_state: stopped|idle` (albo po wysłaniu przez zegar `pause`) sesja Sendspin kończy się jak dotąd (strumień zamknięty, focus oddany), ale **sesja UI** nie znika od razu: serwis pyta MA `player_queues/get_active_queue` (`player_id` Lenovo). Odpowiedź `state == "paused"` → UI `PAUSED` („Pauza”, play aktywny, okładka i metadane bez zmian, pozycja zamrożona na ostatnim `progress`). Każdy inny stan (`idle`, `playing` innego źródła, brak kolejki, błąd, timeout 10 s) → UI `NONE`.
- Do czasu odpowiedzi MA panel/pełny ekran/uchwyt pozostają w stanie `PAUSED` tymczasowo (nie `NONE`), żeby nie mrugać; jeśli odpowiedź to nie `paused`, znikają wtedy.
- Play w stanie `PAUSED` bez sesji Sendspin: `players/cmd/play` przez API MA (nie `client/command`, bo strumień nie istnieje). MA wznawia kolejkę → `stream/start` + audio → nowa sesja Sendspin, UI `PLAYING`, widok bez zmiany (panel lub pełny ekran zostaje otwarty).
- Stan odtwarzacza `idle` w `player_updated` nie kończy pauzy: dla odtwarzacza Sendspin to normalna reprezentacja zapauzowanej kolejki. Koniec pauzy ogłasza kolejka: `queue_updated` dla zapamiętanego `queue_id` ze stanem `idle`/`stopped` → UI `NONE`. Stan `playing` (z `queue_updated` albo `player_updated` własnego odtwarzacza) uzbraja 10 s: nowa sesja Sendspin w tym czasie = wznowienie (UI `PLAYING`), brak = urządzenie gra coś, czego zegar nie odtwarza (przejęcie przez inne źródło/grupę) → UI `NONE`.
- `PAUSED` bez sesji ma limit: po 60 min bez wznowienia UI `NONE` (kolejka MA nie znika, ale nocny ekran nie ma trzymać karty bez końca). Limit resetowany każdą aktualizacją `player_updated` ze stanem `paused`.
- Sam `player_updated` ze stanem `paused` bez wcześniejszej lokalnej sesji nie tworzy karty (bez zmian względem 0.8a 4.3).
- Focus audio: `PAUSED` bez strumienia nie trzyma focusu; wznowienie prosi o focus jak nowa sesja.

### 4.2. Dane

`MusicSnapshot` bez nowych pól: `ui` przyjmuje `PAUSED` z nowego źródła (kolejka), `commands` = `["play","next","previous","stop"]` (API MA), `localConnected` bez zmian. Diagnostyka: `music_queue state=<...>` po każdym zapytaniu, `music_transport` jak w 0.8.16.

## 5. Głośność urządzenia

- `server/command volume N` z MA → `DeviceVolume.set(N)` (STREAM_MUSIC), wzmocnienie strumienia stałe 1.0; `client/state` potwierdza rzeczywisty poziom urządzenia (po zaokrągleniu do kroków systemowych, np. 15 kroków = ok. 7 %).
- `server/command mute` → wyciszenie strumienia (`sink.setMuted`), nie systemu (wyciszenie systemowe wycięłoby Nabu i alarmy); `client/state muted`.
- Przyciski sprzętowe / HA (`audio.set_device_volume`) → `DeviceVolume` zmienia się jak dotąd; serwis wysyła `client/state` z nowym poziomem przy każdej zmianie (nasłuch `VOLUME_CHANGED_ACTION` w `DeviceVolume` już istnieje dla telemetrii; rozszerzyć o callback), żeby suwak w MA pokazywał realny poziom.
- Suwak w bibliotece muzyki i `musicVolume()` (API `players/cmd/volume_set`) zostają: MA odeśle `server/command volume`, które trafi do urządzenia.
- Ducking podczas rozmowy: wzmocnienie strumienia ×0,2 (jak dziś), nie głośność urządzenia.
- Kompromis zapisany: STREAM_MUSIC jest wspólny z TTS Nabu, więc ściszenie w MA ścisza też odpowiedzi głosowe (dziś tak samo działają przyciski).
- Przy starcie sesji Sendspin i po `server/hello` zegar wysyła `client/state` z bieżącym poziomem urządzenia, żeby MA nie startowało od 100 %.

## 5b. Ciągłość strumienia

Zgłoszenie użytkownika (16 września 2026): „stream przestaje grać na chwilę i wraca”. Dziennik: `music_stats dropped` rośnie o 50-150 porcji na minutę przy pojedynczych underrunach, czyli porcje są odrzucane jako spóźnione (próg 50 ms), a nie brakuje ich w sieci. Zegar jest pojedynczym odtwarzaczem, więc ciągłość ma pierwszeństwo przed synchronizacją: porcje spóźnione do 1,5 s są odtwarzane, spóźnienie powyżej 200 ms wymusza ponowną synchronizację zegara (najwyżej raz na 30 s), a `music_stats` raportuje największe spóźnienie i głębokość kolejki. Kryterium: 30 minut muzyki na zegarze bez słyszalnych przerw i `dropped` bliskie zeru.

## 6. Kryteria odbioru

| Test | Wynik |
| --- | --- |
| Geometria pełnego ekranu (test JVM) | Elementy w polu 0,52,800,428; pięć hitboxów bez nakładania, ≥56×72; chevron 72×72; okładka 400×400 w lewej połowie |
| Panel → Pełny ekran → chevron | Panel wraca otwarty; dashboard nieprzebudowany (te same widoki), muzyka gra |
| Pełny ekran: pauza z zegara, pauza z MA, play | Widok zostaje; ikona play/pauza; okładka i metadane bez zmian; pozycja zamrożona i wznowiona |
| Stop z zegara i z MA | Pełny ekran, panel i uchwyt znikają |
| Pauza → 60 min bez wznowienia | Karta znika; play z MA tworzy nową sesję z samym uchwytem |
| Pauza → kolejka przejęta przez inne urządzenie | Karta znika przy `queue_updated` ze stanem `idle`/`stopped` albo najpóźniej 10 s po `playing` bez nowego strumienia |
| `get_active_queue` timeout / MA offline przy pauzie | Karta znika po timeoutcie (10 s), bez wyjątków |
| Głośność: MA 30 % | `STREAM_MUSIC` 30 % (encja `number` w HA pokazuje 30), `client/state volume` = poziom po zaokrągleniu |
| Przyciski sprzętowe | MA pokazuje nowy poziom w ciągu 1 s |
| Wyciszenie MA | Strumień cichy, głośność urządzenia bez zmian, Nabu słyszalne |
| Rozmowa z Nabu podczas pełnego ekranu | Ducking, status na pasku, widok zostaje |
| Emulator: fixture MA symuluje pauzę jak MA 2.10.3 (`stream/end` + `group/update stopped`, `get_active_queue` → `paused`) | Karta w „Pauza”; play → `players/cmd/play` → nowy `stream/start` → PLAYING |
| Zegar fizyczny | Pauza z zegara i z MA, play, stop, głośność w obie strony; zrzuty pełnego ekranu z okładką i bez |

## 7. Kolejność

1. Głośność (pkt 5) - najmniejsza, niezależna.
2. Pauza kolejki (pkt 4) - serwis + fixture + testy.
3. Pełny ekran (pkt 3) - geometria, warstwa, testy, zrzuty.
4. Odbiór na zegarze; potem dopiero SPEC 0.10 (onboarding).

Fixture MA (`.local/ui-music-fixture.py`) ma dostać `player_queues/get_active_queue` ze stanem zależnym od ostatniej komendy (`pause` → `paused`, `stop` → `idle`) i odpowiadać na `players/cmd/play` nowym strumieniem od zapamiętanej pozycji.
