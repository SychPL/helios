# Helios 0.5.0

- Specyfikacja: docs/SPEC-0.5-ha-configurable-dashboard.md, zrecenzowana i poprawiona przed implementacją.
- Stały pasek (HELIOS, jeden status z priorytetem błąd > rozmowa > nasłuch, ikona HA) i stałe lokalne menu; zakładka `menu-zegara` nie jest już czytana.
- Siatka 4×3 z sekcji `helios` `version: 2`: `clock`, `weather`, `entity`, `light`, `cover`, `garage`; walidacja atomowa per typ; ukryty element zostawia puste pole.
- Klient WS: konfiguracja + snapshot wchodzą razem; zły dokument zostawia ostatni dobry układ żywy z komunikatem; `call_service` z blokadą per akcja i timeoutem 10 s; atrybuty `temperature*`, `wind_speed*`, `current_position` i jawne `attribute`.
- Offline: układ i widoczność zamrożone, kafle przyciemnione, sterowanie nieaktywne, brak kolejki. Bez cache v2: układ awaryjny z zegarem.
- Głos: pik przed każdym nasłuchem; po odpowiedzi 6 s okna na dopowiedzenie bez hasła (15 s przy `continue_conversation`), ta sama `conversation_id`.
- Build i lint: PASS (0 błędów lint). 13 testów JVM: PASS; parser v2 (przykład, domyślne, geometria, pola per typ, widoczność, potwierdzenie), dekoder atrybutów, klient (układ ze snapshotem, przeładowanie, zły dokument i cache, wywołania usług: ok/odrzucenie/timeout/rozłączenie).
- Emulator 800×480, osobna APK UI bez biblioteki ARM, fałszywy HA (`.local/ui-dashboard-fixture-v2.py`): układ awaryjny przed połączeniem, pełna siatka z pogodą i atrybutami, spinner i przełączenie światła, dialog bramy (dotknięcie obok = brak wywołania, potwierdzenie = jedno `close_cover`), panel rolety ▲ ■ ▼ z 70% i blokadą per przycisk, offline z oznaczeniem nieaktualności, `version: 1` z HA = ostatni dobry układ + komunikat. Zrzuty: `.local/helios-0.5-*.png`.
- Nie sprawdzono: instalacja na fizycznym zegarze, prawdziwe HA (panel nadal ma `version: 1`; wymaga `publish_ha_dashboard.py --replace` lub edycji w HA), dźwięk i rozmowa w emulatorze.

SHA256 (app/build/outputs/apk/debug/app-debug.apk): 3483a4c8ba1c6339595dc96e2de0815cf80dc7950243001949e666b8ebea2101
