# SPEC 0.15 - swobodne dashboardy: kafelek `tile`, intencje, strony, ikony MDI

Rozwinięcie [SPEC 0.5](SPEC-0.5-ha-configurable-dashboard.md). Dashboard przestaje być listą ośmiu gotowych
typów kafelków: dochodzi jeden kafelek dla encji z dowolnej domeny, katalog intencji zamiast nazw usług,
pełny zestaw ikon Material Design i strony, na których da się później zbudować przewijanie.

Zaimplementowane w Heliosie 0.12.0 (versionCode 32). Część edytorska (punkt 8) mieszka w `ha-helios` 0.11.0.

## 1. Skąd to się wzięło

Schemat 2-5 opisywał osiem typów (`clock`, `weather`, `entity`, `light`, `cover`, `garage`, `music`,
`cover_group`), a każdy nowy typ oznaczał switch w trzech miejscach naraz (`DashboardSpec`, `DashboardView`,
`MainActivity`), własną ikonę rysowaną w kodzie i własną usługę wpisaną na sztywno. Encja spoza tej listy -
zamek, scena, skrypt, termometr z atrybutem - nie dawała się pokazać wcale. Jedyną drogą edycji był surowy
YAML w Home Assistancie.

Cele: (1) swoboda komponowania, (2) wyklikiwanie zamiast YAML-a, (3) niezmieniony etos - minimum zależności,
ścisła walidacja, układ last-good przy błędzie.

## 2. Rozważone drogi i wybór

| wariant | werdykt |
| --- | --- |
| edytor na samym zegarze | odrzucony: 800x480, brak infrastruktury dotyku, duży diff w Javie |
| własny widok Lovelace z `custom:helios-*` | odrzucony: 2,5-4,5 tys. linii JS i drugi renderer, który dryfuje za frontendem HA |
| zegar renderuje natywne karty HA z widoku `sections` | odrzucony: HA ukrywa kartę przez `display:none` i **przesuwa** resztę, a zegar musi trzymać slot; natywny `tile` ma minimum 6/12 kolumn, więc nie ma pola 1x1; brak stabilnych `id`; edytor HA zapisuje pola, których zegar nie obsłuży |
| **panel Helios w HA** | **wybrany**: jeden moduł ES bez builda, płótno 4x3, formularze `ha-form`, zapis przez `lovelace/config/save`; `helios:` zostaje kanoniczny, zegar niczego nie emuluje |

Kafelki nie są odwzorowaniem kart HA jeden do jednego. Zamiast tego jeden generyczny `tile`, a później
`gauge`, `glance`, `sparkline` i `climate`.

## 3. Korzeń dokumentu: strony

`version: 6` ma dokładnie dwa pola: `version` i `pages`. Siatka jest zawsze 4x3, więc `grid` znika, a `items`
w korzeniu jest błędem.

- 1-8 stron, każda `{ id, title?, items }`.
- Każda strona ma własne 12 pól, własne sprawdzenie nakładania i własny najwyżej jeden kafelek `music`.
- `id` elementów są unikalne **w całym dokumencie**, bo służą za klucze widoczności i operacji w toku.
- Dokument 2-5 to jedna niejawna strona `main` - stare pliki działają bez zmian i bez automigracji na zegarze.
- Zegar renderuje stronę 1, ale czyta i subskrybuje encje ze wszystkich stron. Przewijanie stron to osobny krok.
- Limit dokumentu bez zmian: 64 KiB.

## 4. Kafelek `tile`

Jeden typ dla encji z dowolnej domeny. Pola: `entity`, opcjonalnie `title` (domyślnie `friendly_name` encji),
`icon`, `attribute` (pokazuje atrybut zamiast stanu), `tap_action`, `confirmation`, `visible_when`.

Stan jest tłumaczony na polskie słowa (światła, rolety, zamki, czujniki binarne według `device_class`),
a liczby dostają jednostkę z `unit_of_measurement` (`21,4 °C`). Ikona bierze się kolejno z: atrybutu `icon`
encji, pola `icon`, domeny encji.

## 5. Intencje zamiast nazw usług

`tap_action.action` to **intencja**, nie nazwa usługi. Dozwolone zależą od domeny encji, a domyślna działa bez
wpisywania czegokolwiek:

| domena | dozwolone | domyślna |
| --- | --- | --- |
| `light`, `switch`, `input_boolean`, `fan` | `none`, `details`, `toggle`, `turn_on`, `turn_off` | `details` |
| `cover` | `none`, `details`, `controls`, `open`, `close`, `stop` | `controls` |
| `lock` | `none`, `details`, `lock`, `unlock` | `details` |
| `script`, `scene`, `input_button`, `button` | `none`, `details`, `activate` | `activate` |
| pozostałe (`sensor`, `binary_sensor`, `climate`, ...) | `none`, `details` | `details` |

- `details` otwiera na zegarze okno ze stanem encji i przyciskami dozwolonych intencji.
- `none` czyni kafelek nieklikalnym.
- Intencja spoza listy domeny odrzuca cały dokument: `Encja light.x nie obsługuje akcji open`.
- Zamek pyta zawsze; `confirmation.enabled: false` jest dla niego odrzucane.

Gwarancja z SPEC 0.5 pkt 11.4 brzmi teraz tak: konfiguracja nie może wskazać dowolnej usługi, adresu, kodu ani
obcego celu; może natomiast aktywować jawnie wybrany `script` albo `scene`, których skutki należą do HA. Celem
wywołania jest zawsze encja wpisana w tym samym kafelku, a lista usług żyje w `ActionPolicy`, nie w YAML-u.

## 6. Ikony

`icon: mdi:<nazwa>` z katalogu Material Design Icons 7.4.47 wbudowanego w aplikację - te same nazwy, które
podpowiada Home Assistant. Sześć nazw z poprzednich schematów (`information`, `weather-rainy`, `lightbulb`,
`window-shutter`, `garage-open`, `music`) nadal działa i oznacza tę samą ikonę. Nieznana nazwa odrzuca
dokument (`Nieznana ikona: mdi:foo`). Font i mapa codepointów powstają skryptem `tools/prepare_mdi_assets.py`;
APK rośnie o około 1,3 MB.

## 7. Co zostaje bez zmian

Typy 2-5 działają w dokumencie 6 dokładnie jak dotąd, więc przepisanie układu można robić kafelek po kafelku.
`light`, `cover` i `garage` da się zapisać jako `tile` z intencją `toggle`, `controls` i `close`. Typ `entity`
zostaje jedynym nosicielem pola `off_entity` (jedno `light.turn_off` na grupę po potwierdzeniu) - `tile` tego
nie przejmuje, bo taki kafelek pokazuje tekst czujnika, a gasi inną encję.

Każdy błąd - nieznane pole, literówka w ikonie, zła domena, nakładanie - odrzuca cały zapis: zegar zachowuje
poprzedni układ i pokazuje komunikat w pasku.

## 8. Edytor w Home Assistancie

Integracja `ha-helios` rejestruje panel `helios` (tylko dla administratora): płótno 4x3 z klikaniem w komórkę,
formularze `ha-form` z podpowiadaczami encji i ikon, zakładki stron, zapis przez `lovelace/config/save`.
Walidator w JS jest lustrem walidatora w Javie, z tymi samymi komunikatami, ale rozstrzyga zegar - panel jest
wygodą, nie źródłem prawdy. Przed zapisem panel porównuje dokument z tym, co stoi w HA, żeby nie nadpisać
zmiany zrobionej w surowym edytorze.

## 9. Poza zakresem

Karty HA jeden do jednego (`grid`/`stack`, `entities`, `markdown`, `iframe`, `picture-*`, mapa, energia,
logbook, kalendarz, todo, alarm, dowolne karty społeczności), dowolne `perform-action` z własnym `data`
i `target`, szablony w polach tekstowych (logika należy do pomocników HA), `media_player` i `humidifier`
jako `tile` (muzyka ma własną nakładkę), przeciąganie i zmiana rozmiaru w pierwszej wersji panelu,
walidacja po stronie Pythona.

## 10. Kryteria akceptacji

1. Przykład [ha/helios-clock-v6.yaml](../ha/helios-clock-v6.yaml) parsuje się i renderuje stronę 1.
2. `items` w korzeniu dokumentu 6 i `pages` w dokumencie 5 są odrzucane.
3. Powtórzony `id` między stronami, 13 elementów na stronie i 9 stron są odrzucane.
4. `sensor` z intencją `toggle`, `open` na `light` i zamek z wyłączonym potwierdzeniem są odrzucane.
5. Naga nazwa `lightbulb` normalizuje się do `mdi:lightbulb`, a `mdi:nope` odrzuca dokument.
6. `tile` z `none` nie jest klikalny; z `toggle` przełącza; zamek pyta bez wpisanego `confirmation`.
7. Zimny start z zapisanego dokumentu 6 działa, bo mapa ikon instaluje się przed parsowaniem.
8. Układ zbudowany w panelu HA wygląda na zegarze identycznie jak ten sam układ zapisany ręcznie,
   a ukryty kafelek nadal trzyma swoje pole.
