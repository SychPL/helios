# Helios 0.13.0 - kafelek pogody: dziś i jutro

Kafelek `weather` szerszy niż jedna komórka dzieli się na dwie połowy - dziś po lewej, jutro po prawej -
a prognozę zegar pobiera sam z Home Assistanta, bez żadnej encji pomocniczej. Kontrakt:
[docs/ha-dashboard.md](../docs/ha-dashboard.md).

Weryfikacja na fizycznym zegarze w nocy z 22 na 23 września 2026, Helios 0.13.0 (versionCode 33),
HA 2026.8.3, encja `weather.forecast_dom` (met.no).

## Co widać na zegarze

| miejsce | źródło | wynik na sprzęcie |
| --- | --- | --- |
| ikona po lewej | stan encji | `mdi:weather-partly-cloudy` przy częściowym zachmurzeniu |
| temperatura po lewej | `temperature` + `temperature_unit` | `11°C` |
| linia pod nią | `wind_speed` + `wind_speed_unit` | `15 km/h` |
| ikona po prawej | warunek z prognozy na jutro | `mdi:weather-cloudy` |
| temperatura po prawej | `temperature` rekordu jutra | `15°C` |
| linia pod nią | `templow` rekordu jutra | `↓ 8°` |

Prognoza idzie przez `weather/subscribe_forecast` (typ `daily`), a rekord jutra wybiera się **po dacie**, nie po
pozycji na liście - późnym wieczorem HA zaczyna listę już od jutra. Subskrypcja powstaje dopiero wtedy, gdy
przyjdzie układ, bo to on nazywa encję pogodową; sesja HA gubi subskrypcje przy zerwaniu, więc każdy nowy układ
pyta od nowa.

Czego nie da się odtworzyć z obrazka referencyjnego: kolorowych ikon (wbudowany font Material Design Icons jest
jednokolorowy) i osobnej liczby „dziś odczuwalna", bo met.no nie publikuje `apparent_temperature`. Wiersze
z wilgotnością, ciśnieniem i zachmurzeniem świadomie zniknęły - właściciel chciał sam obraz dziś i jutro,
bez drobnych tekstów.

## Regresja znaleziona i naprawiona na sprzęcie

Po drodze kafelek miał brać prognozę z `forecast_entity` wpisanego bez `forecast_when`. Ta wersja dopuściła taki
zapis w schemacie, ale `DashboardSpec.entities()` nadal dokładało do listy encji **`null`** w miejsce brakującej
encji trybu. Lista z `null` trafiała do `subscribe_entities`, Home Assistant odrzucał **całą** subskrypcję, klient
rzucał `Subskrypcja odrzucona` i wpadał w pętlę ponownych połączeń: zegar pokazywał
`HA niedostępny - dane nieaktualne` i nie wracał sam, dopóki dokument się nie zmienił.

Przebieg na sprzęcie: zapis dokumentu → `HA niedostępny` → cofnięcie dokumentu → zegar wraca → poprawka →
ten sam dokument → `dashboard_configured items=8`, połączenie utrzymane.

Sama droga przez `forecast_entity` została potem porzucona na rzecz subskrypcji prognozy, więc schemat wrócił do
reguły „oba pola albo żadne". Utwardzenie `entities()` zostało: encja trybu dokładana jest tylko wtedy, gdy
istnieje, `null` znika z zestawu na wyjściu, a test schematu 4 sprawdza, że na liście encji nie ma `null`.

## Co po stronie HA nie jest potrzebne

Nic. Pierwotny plan zakładał `sensor.helios_pogoda_jutro` z pakietu
[ha/packages/helios_bedroom.yaml](../ha/packages/helios_bedroom.yaml), czyli plik w konfiguracji HA i włączone
pakiety YAML. Subskrypcja prognozy zdejmuje ten wymóg: zegar pyta o to samo, o co pyta interfejs HA.
Pola `forecast_entity` i `forecast_when` zostają nietknięte dla tych, którzy chcą starego zachowania
(zamiany całego kafelka na prognozę o wybranej porze).

## Bramka

`./gradlew testDebugUnitTest lintDebug assembleDebug` - 189 testów, zero błędów, lint czysty.
