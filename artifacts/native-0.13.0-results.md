# Helios 0.13.0 - kolumna szczegółów w kafelku pogody

Kafelek `weather` szerszy niż jedna komórka pokazuje z prawej strony odczyty, które encja pogodowa naprawdę
podaje, oraz ikonę warunku. Wiersz „Jutro" czeka na sensor prognozy. Opis kontraktu:
[docs/ha-dashboard.md](../docs/ha-dashboard.md).

Weryfikacja na fizycznym zegarze 22 września 2026, Helios 0.13.0 (versionCode 33), HA 2026.8.3,
encja `weather.forecast_dom` (met.no).

## Co widać na zegarze

| wiersz | źródło | wynik na sprzęcie |
| --- | --- | --- |
| Wiatr | `wind_speed` + `wind_speed_unit` | `16 km/h` |
| Zachmurzenie | `cloud_coverage` | `93%` |
| Wilgotność | `humidity` | `92%` |
| Ciśnienie | `pressure` + `pressure_unit` | `1019 hPa` |
| Jutro | `forecast_entity` | brak - sensor prognozy nie istnieje jeszcze w HA |
| ikona kafelka | stan encji | `mdi:weather-rainy` przy deszczu |

Czego encja nie podaje, tego nie ma. Pierwotny pomysł z obrazka referencyjnego zakładał wiersz „Odczuwalna",
ale met.no nie publikuje `apparent_temperature`, a w HA nie ma żadnego sensora odczuwalnej - zamiast zgadywać,
wiersz zastąpiło zachmurzenie.

## Regresja znaleziona i naprawiona na sprzęcie

Pierwsza wersja zmiany dopuściła `forecast_entity` bez `forecast_when` (bo wiersz „Jutro" nie potrzebuje encji
trybu), ale `DashboardSpec.entities()` nadal dokładało do listy encji **`null`** w miejsce brakującej encji
trybu. Taka lista trafiała do `subscribe_entities`, Home Assistant odrzucał całą subskrypcję, klient rzucał
`Subskrypcja odrzucona` i wpadał w pętlę ponownych połączeń: zegar pokazywał `HA niedostępny - dane nieaktualne`
i nie wracał sam. Objaw pojawił się dopiero po zapisie dokumentu, bo dopiero wtedy powstała ta kombinacja pól.

Naprawa jest w jednym miejscu, przez które przechodzą wszyscy wywołujący: `entities()` dokłada encję trybu
tylko wtedy, gdy istnieje, i na koniec usuwa `null` z zestawu. Test `forecastEntityStandsAloneButForecastWhenDoesNot`
sprawdza, że żadna encja na liście nie jest `null`.

Przebieg na sprzęcie: dokument z `forecast_entity` → `HA niedostępny` (stara binarka) → cofnięcie dokumentu →
zegar wraca → instalacja poprawki → ten sam dokument → `dashboard_configured items=8`, połączenie utrzymane.

## Co zostało do zrobienia po stronie HA

Wiersz „Jutro" zapali się, gdy w Home Assistancie powstanie `sensor.helios_pogoda_jutro` w formacie z
[SPEC 0.9](../docs/SPEC-0.9-bedroom-dashboard.md) punkt 6.2. Gotowy szablon leży w
[ha/packages/helios_bedroom.yaml](../ha/packages/helios_bedroom.yaml) i wymaga pakietów YAML - zwykły szablon
nie sięgnie prognozy, bo od 2024 roku prognoza jest akcją `weather.get_forecasts`, nie atrybutem encji.

## Bramka

`./gradlew testDebugUnitTest lintDebug assembleDebug` - 189 testów, zero błędów, lint czysty.
