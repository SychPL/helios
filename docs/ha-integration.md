# Integracja Helios w Home Assistant (0.7)

Zegar łączy się z HA jednym gniazdem WebSocket (tym samym, co dashboard) i rejestruje subskrypcję `helios/connect`. Po stronie HA komponent `custom_components/helios` tworzy urządzenie **Helios** z encjami:

| Encja | Źródło na zegarze |
| --- | --- |
| `sensor.*_wersja_aplikacji` (diagnostyczny) | `BuildConfig.VERSION_NAME` |
| `sensor.*_stan_glosu` | idle / listening / processing / responding / error z `AssistClient` |
| `sensor.*_wersja_docka` (diagnostyczny) | `padVersion` z listenera OEM |
| `binary_sensor.*_dock_podlaczony` | listener połączenia docka; `unknown` do pierwszego zdarzenia |
| `binary_sensor.*_ladowanie_telefonu` | listener ładowania; niedostępny bez docka |
| `light.*_lampka_docka` | `isLedOn` z OEM; jasność = ostatnia przyjęta nastawa 1-10 |
| `number.*_glosnosc_urzadzenia` | `STREAM_MUSIC` 0-100 %, odczyt co 3 s |

Kontrakt kanału i allowlista komend (`lamp.turn_on`, `lamp.turn_off`, `lamp.set_brightness`, `audio.set_device_volume`): [SPEC 0.7](SPEC-0.7-home-assistant-integration.md), plan: [PLAN 0.6/0.7](PLAN-0.6-0.7-implementation.md).

## Instalacja komponentu

1. Skopiuj katalog `ha/custom_components/helios` do `config/custom_components/helios` w HA (Samba, SSH lub edytor plików dodatku). Bez zależności pip.
2. Zrestartuj HA.
3. Ustawienia → Urządzenia i usługi → Dodaj integrację → **Helios**. HA pokaże 6-cyfrowy kod ważny 5 minut.
4. Na zegarze przytrzymaj HELIOS → **Paruj z HA (kod)** → wpisz kod → OK. Zegar musi już być sparowany z HA (token) i połączony.
5. Po sparowaniu przypisz urządzenie do obszaru (np. Sypialnia). Od następnej rozmowy `assist_pipeline/run` dostaje `device_id` tego urządzenia.

Ponowne parowanie tego samego zegara (np. nowe konto HA dla tokena) odświeża istniejący wpis zamiast tworzyć duplikat. Usunięcie integracji kończy subskrypcję: zegar czyści `device_id` i nie kontynuuje starych rozmów.

## Zachowanie

- Encje są `unavailable`, dopóki zegar nie przyśle pierwszego snapshotu po połączeniu; rozłączenie WS lub zapis dashboardu (restart sesji) daje krótkie `unavailable`.
- Polecenia czekają na `helios/result` do 10 s; brak odpowiedzi to błąd usługi, nie ponowienie. Druga komenda dla tego samego zasobu (lampka albo głośność) w trakcie pierwszej dostaje `busy`.
- Token zegara ma prawa konta HA; użyj dedykowanego użytkownika bez uprawnień administratora.

## Lokalnie na zegarze

Menu → **Urządzenie: głośność i lampka**: suwak głośności (zmiana po puszczeniu), przełącznik lampki, jasność 1-10. Menu → **Odśwież parowanie** pobiera ponownie dokument parowania z mostu (`tools/native_bridge.py`); zmiana danych HA wymaga potwierdzenia, sekcja `music_assistant` (0.6) jest dołączana z `.local/ma.json`, gdy plik istnieje.

Testy czystych helperów komponentu: `python -m pytest ha/tests`. Integracja nie była jeszcze uruchomiona na HA 2026.8.3 - pierwszy odbiór wg SPEC 0.7 pkt 7.
