# Mostek Helios ↔ Smart Clock 2 Tools - wyniki

Implementacja SPEC 0.12 według PLAN 0.12. Helios 0.10.0 (versionCode 30), narzędzia 2.19.0 (versionCode 32).
Weryfikacja na fizycznym zegarze 19 września 2026, Lenovo Smart Clock 2, firmware `LenovoCD-24502F_ROW_1.2.2.627_220105`.

## Co powstało

| strona | elementy |
| --- | --- |
| narzędzia | `BridgeActivity` (jedyne wejście), `bridge/`: `BridgeRequest`, `BridgeFlow`, `OpRegistry`, `TrustStore`, `MicState`, `Snapshot`, `ExecutorLock`, `Executions`, `Ops`, `Detail`, `AdbState`, `CallerFacts`, `BridgeExecutor`, `BridgeFiles` |
| Helios | `ToolsTrust`, `ToolsCall`, `ToolsState`, `ToolsMenu` (czyste, z testami), `ToolsBridge` (adapter), `ApkProvider`, pozycja "Narzędzia zegara" w menu |

Testy jednostkowe: narzędzia 104 zielone (było 41 i suita w ogóle się nie kompilowała), Helios cała suita zielona,
lint czysty. Operacje: `state`, `root_adb_on`, `adb_on`, `adb_off`, `grant_permission`, `write_settings`,
`set_home`, `mic_release`, `mic_restore`, `install_apk`.

## Co potwierdzono na sprzęcie

| krok | wynik |
| --- | --- |
| instalacja obu wydań przez ADB | Helios 0.10.0, narzędzia 2.19.0 |
| menu narzędzi bez akceptacji | jedyna pozycja "Zaakceptuj narzędzia"; instalacja to nie zaufanie |
| ekran akceptacji w Heliosie | nazwa pakietu i odcisk `f6d91dae02d64df7...`, zapis dopiero po zgodzie |
| pierwsze wywołanie mostka | ekran zaufania narzędzi z odciskiem Heliosa i ostrzeżeniem, odmowa możliwa |
| `state` po zaufaniu | `ok`, migawka wypełniona, brak wpisu w rejestrze (odczyt niczego nie zapisuje) |
| menu z żywą migawką | Wyłącz ADB, Pozwól na jasność, Ustaw jako ekran główny, Cicha aktualizacja - każda pozycja z własnego warunku |
| `write_settings` bez deklaracji w manifeście | `unsupported`, zanim cokolwiek się wykonało |
| `write_settings` po deklaracji | ekran zgody, wykonanie, `ok`, `appops get` potwierdza `allow` |
| uzgodnienie po operacji | Helios odpytuje `state` i przebudowuje menu z odpowiedzi |
| rejestr | wpis z kluczem pakiet + odcisk + `op_id`, etapami i czasami monotonicznymi, plik przeżywa restart aplikacji |

Nie wykonano na sprzęcie: `set_home` (zmienia ekran główny zegara), `adb_off` (odcięłoby zdalny dostęp w trakcie
pracy), `install_apk`, `mic_release` i `mic_restore` (fabryczna powłoka ma już odebrany mikrofon z 19 września).
Te cztery zostają do świadomego uruchomienia przez użytkownika.

## Co weryfikacja zmieniła w kodzie

Cztery błędy wyszły dopiero na zegarze i żaden nie był widoczny w testach jednostkowych.

1. **Ekran mostka mierzył stan roota na wątku interfejsu.** Kanał roota blokuje bez limitu, więc aktywność wisiała,
   a `am start -W` nie wracał wcale. Pomiary poszły na wątek roboczy, a sprawdzenie kanału dostało własny limit.
2. **Walidacja flag intencji odrzucała każde prawdziwe wywołanie.** System dokłada do doręczanej intencji własne
   flagi (u nas `0x00800000`), a kontrakt mówił "nic poza grantem". Reguła to teraz lista flag zakazanych: tych,
   które przenoszą żądanie do innego zadania albo oddają wynik komuś innemu.
3. **Po zgodzie mostek odpowiadał na własny wpis jak na duplikat.** Wpis powstaje przed pytaniem, więc ponowna
   pełna decyzja widziała go i zwracała `in_progress` zamiast wykonać. Świeża zgoda buduje teraz decyzję wprost.
4. **Polecenia systemowe nie działały z kanału roota.** W kontekście SELinux `kernel_t` menedżer usług jest
   niewidoczny: `service list` zwraca pustkę, a każde `cmd` odpowiada `Can't find service`. Wszystko, co dotyka
   usług systemowych, idzie teraz przez `runcon u:r:shell:s0`. Przy okazji: narzędzia nie mogą odpytać
   `AppOpsManager` o cudzą aplikację, więc weryfikacja uprawnienia czyta to, co raportuje system przez powłokę.

Do tego brakująca deklaracja `WRITE_SETTINGS` w manifeście Heliosa (bez niej mostek słusznie odmawiał) oraz
odświeżanie migawki przy otwarciu menu, bez którego po restarcie widoczna była tylko jedna pozycja.

## Otwarte pytania ze specyfikacji

- **Czy zerowanie właściwości portu wystarcza do wyłączenia ADB na tym firmware**: nierozstrzygnięte, bo `adb_off`
  nie było uruchamiane. Kod ma ścieżkę zapasową (`stop adbd`) i rozstrzyga wynik po gnieździe, nie po tekście skryptu.
- **Czy instalacja kanałem roota przechodzi bez okna instalatora**: nierozstrzygnięte, `install_apk` nie było
  uruchamiane na sprzęcie. Polecenie `pm install` idzie tą samą drogą co reszta, czyli przez kontekst powłoki.
