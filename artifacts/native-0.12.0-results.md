# Helios 0.11.0 i 0.12.0 - wyniki

Dwa wydania jednej linii pracy: 0.11.0 to wygaszacz sterowany z HA ([SPEC 0.13](../docs/SPEC-0.13-wygaszacz.md),
[SPEC 0.14](../docs/SPEC-0.14-wygaszacz-zdjecia.md)), 0.12.0 to karty, intencje, strony i ikony MDI
([SPEC 0.15](../docs/SPEC-0.15-dashboard-cards.md), [PLAN 0.15](../docs/PLAN-0.15-implementation.md)).

Zegar: Lenovo Smart Clock 2, firmware `LenovoCD-24502F_ROW_1.2.2.627_220105`, Helios 0.12.0 (versionCode 32).
Home Assistant 2026.8.3. Weryfikacja na fizycznym zegarze 22 września 2026.

## Co powstało

| wydanie | elementy |
| --- | --- |
| 0.11.0 | `ScreensaverPolicy` (tryby `off`/`dark`/`always`, progi z czujnika światła, jasność zależna od trybu), telemetria poziomu światła do HA, wyciszanie muzyki na czas rozmowy do 7 % wzmocnienia zamiast ciszy |
| 0.12.0 | `CardDefinition`, `CardBodies`, `ActionPolicy` (jeden opis typu karty zamiast switchy), schemat 6 w `DashboardSpec` (strony, `tile`, intencje), `MdiIcons` + font MDI 7.4.47 w assetach, `DetailsDialog`, `tools/prepare_mdi_assets.py`, `ha/helios-clock-v6.yaml` |

Testy jednostkowe po scaleniu do `main`: **186 zielonych, 0 błędów** (`./gradlew testDebugUnitTest`), `lintDebug`
czysty, `assembleDebug` przechodzi. APK urósł o około 1,3 MB przez font MDI.

## Co potwierdzono na sprzęcie 22 września

| krok | wynik |
| --- | --- |
| instalacja `adb install -r` (APK sha256 `e2ef8f8e...`) | `Success`, `versionCode=32`, `versionName=0.12.0` |
| start aplikacji po podmianie | proces żyje, `dashboard_visible width=800 height=480`, zero wpisów błędu w `assist-events.jsonl`, zero crashy w `logcat` |
| konfiguracja wygaszacza z HA | wczytana: `screensaver_wait tryb=ALWAYS blokada=kafelek lights-watched` - zegar zna tryb i wie, który kafelek trzyma ekran |
| hasło wybudzające po aktualizacji | `wake_listening "Okay Nabu; local audio only"` |
| przepisanie dashboardu na schemat 6 | dokument `version: 6` z jedną stroną `main` i tymi samymi ośmioma elementami; zegar odpowiedział `dashboard_configured items=8` |
| układ po przepisaniu | identyczny jak przed zmianą, łącznie z pustymi polami po ukrytych kafelkach uwag |
| dowód, że to nie fallback | ikona lampki ustawiona na `mdi:desk-lamp` - nazwę spoza sześciu starych ikon - i taka się narysowała; przy odrzuceniu dokumentu zegar zostałby przy poprzednim układzie z żarówką |

Przepisany dokument zmienił typy: `clock-lamp` z `light` na `tile` z intencją `toggle`, trzy kafelki uwag
z `entity` na `tile`. `lights-watched` został typem `entity`, bo tylko ten typ niesie `off_entity` i akcję
gaszenia grupy - `tile` pokazywałby wtedy stan grupy zamiast tekstu czujnika.

## Czego nie sprawdzono na zegarze

- Okno szczegółów (`details`) i intencje inne niż `toggle`: zamek, scena, skrypt, przycisk.
- Druga strona dokumentu - model ją czyta i subskrybuje, ale zegar renderuje stronę 1, więc nie ma czego oglądać.
- Panel edytora w HA: `ha-helios` 0.11.0 nie jest zainstalowany w żywym Home Assistancie, więc `loadCardHelpers()`
  i `ha-form` pozostają niepotwierdzone poza testami.
- Wygaszacz z pokazem zdjęć w codziennej pracy (0.14) - sprawdzony był tryb i blokada, nie sam pokaz.

Wcześniejszy smoke na emulatorze (inna sesja, ta sama gałąź) pokazał poprawne renderowanie schematu 6 z `tile`
i ikonami MDI oraz działające przełączanie, okno szczegółów, pytanie zamka i scenę.

## Pułapki znalezione przy okazji

1. **Publishery dokładające kafelki cofają schemat.** `tools/deploy_bedroom_dashboard.py`
   i `tools/deploy_attention_dashboard.py` zapisują `version: 5` z `items` w korzeniu. Uruchomienie
   któregokolwiek po przejściu na schemat 6 przywróci dokument 5. Do przerobienia na strony.
2. **Domyślna ścieżka publishera.** `tools/publish_ha_dashboard.py` bez `--file` bierze
   `.local/ha/helios-clock.yaml`; ten plik musi być dokumentem 6, inaczej zapis to cichy downgrade.
3. **Kafelek uwag stał się klikalny.** Typ `entity` bez `off_entity` był martwy, a `tile` ma domyślną
   intencję `details`. Kto chce zachować dawne zachowanie, dopisuje `tap_action: { action: none }`.

## Odtworzenie

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
python tools/publish_ha_dashboard.py --replace     # .local/ha/helios-clock.yaml, dokument 6
adb shell "run-as pl.mateusz.helios sh -c 'tail files/assist-events.jsonl'"   # dashboard_configured
```
