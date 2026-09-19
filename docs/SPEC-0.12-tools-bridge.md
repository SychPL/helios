# SPEC 0.12 - mostek Helios ↔ Smart Clock 2 Tools

Status: propozycja do przeglądu. Dotyczy dwóch repozytoriów: `SychPL/helios` (aplikacja, pakiet `pl.mateusz.helios`) i `SychPL/smartclock2tool` (narzędzie, pakiet `pl.mateusz.clockadbprobe`, dalej **sc2t**).

## 1. Problem i cel

Zegar Lenovo Smart Clock 2 nie ma ADB, launchera ani sklepu. Pierwszy plik APK trafia na niego trikiem z TalkBackiem, a root i ADB daje sc2t, przy czym oba znikają po odcięciu zasilania. Helios działa dziś bez żadnych przywilejów i przez to nie umie czterech rzeczy, które użytkownik musi robić ręcznie: nadać sobie uprawnienia do mikrofonu, odebrać mikrofon fabrycznej powłoce Google (która trzyma go w 48 kHz stereo i psuje strumień hasła wybudzającego), zmienić jasność systemową (tryb nocny) oraz zainstalować własną aktualizację bez dotykania ekranu.

Cel: Helios ma jednym dotknięciem doprowadzić do stanu, w którym te rzeczy są załatwione, korzystając z sc2t jako źródła uprawnień, i ma działać bez zmian, gdy sc2t nie ma.

Nie-cele: Helios nie dostaje trwałego roota, nie zawiera exploita, nie uzyskuje powłoki. sc2t nie zyskuje wiedzy o Home Assistancie ani o zawartości Heliosa.

## 2. Decyzje

1. **Kanał to jawna intencja z wynikiem**, nie HTTP. Agent HTTP sc2t słucha na wszystkich interfejsach, pozwala uruchamiać dowolne polecenia i pobierać kod, a jego token jest prywatny dla sc2t. Nie jest fundamentem dla drugiej aplikacji.
2. **Zamknięta lista operacji**, nie powłoka. Każda operacja to jedna nazwana czynność z własną walidacją.
3. **Skutkiem większości operacji jest nadanie Heliosowi uprawnienia, nie wykonywanie pracy za niego.** Po nadaniu `WRITE_SETTINGS` Helios steruje jasnością sam, bez roota i bez sc2t.
4. **Zaufanie przy pierwszym użyciu.** sc2t nie zna z góry podpisu Heliosa (każdy buduje własną kopię), więc pyta użytkownika raz i zapamiętuje parę: nazwa pakietu plus odcisk podpisu.
5. **Helios nie instaluje roota automatycznie po starcie.** Uruchomienie łańcucha zawsze wychodzi od człowieka, bo prymityw zapisu potrafi zawiesić jądro.

## 3. Wykrywanie sc2t

Helios sprawdza trzy rzeczy, wszystkie bez uprawnień i bez sc2t:

| co | jak | znaczenie |
| --- | --- | --- |
| obecność | `PackageManager.getPackageInfo("pl.mateusz.clockadbprobe", 0)` | brak = menu pokazuje tylko pozycję instalacji |
| wersja API mostka | `versionCode` z tego samego wyniku, próg z pkt 4.6 | starsza = menu proponuje aktualizację narzędzia |
| ADB | `getprop service.adb.tcp.port` (czytelne dla każdej aplikacji) | `5555` = ADB po Wi-Fi działa |

Stanu roota Helios sam nie zbada; pyta o niego operacją `state` (pkt 5.1). Wynik wykrywania nie jest zapamiętywany na trwałe: liczy się stan w chwili otwarcia menu.

## 4. Kontrakt mostka

### 4.1 Wywołanie

Helios woła `startActivityForResult` z **jawną** intencją:

```
component = pl.mateusz.clockadbprobe/.BridgeActivity
action    = pl.mateusz.clockadbprobe.action.BRIDGE
extras:
  api  : int     - wersja kontraktu, dla tej specyfikacji 1
  op   : String  - nazwa operacji z pkt 5
  args : String  - argumenty operacji jako JSON, dopuszczalne puste
```

Intencja jest jawna, więc nie podszyje się pod nią inna aplikacja. `BridgeActivity` jest jedynym eksportowanym wejściem mostka, bez filtra intencji poza własną akcją, `launchMode="singleTask"`, `excludeFromRecents="true"`.

### 4.2 Odpowiedź

Zawsze `RESULT_OK` z kompletem danych albo `RESULT_CANCELED`, gdy użytkownik zamknął okno zgody lub postępu. Dane wyniku:

```
status : String - ok | denied | unsupported | unsupported_api | busy | failed | wrong_firmware
detail : String - jedno zdanie dla człowieka, bez tokenów i bez ścieżek prywatnych
state  : String - JSON stanu z pkt 5.1, zawsze aktualny na moment odpowiedzi
```

`denied` oznacza decyzję człowieka albo brak zaufania do wywołującego, `unsupported` nieznaną operację, `unsupported_api` niezgodną wersję kontraktu, `busy` inną operację w toku, `failed` nieudane wykonanie, `wrong_firmware` niezgodny identyfikator kompilacji (pkt 7.1).

Helios traktuje brak wyniku w 5 minutach jak `failed`; to jedyny limit czasu po jego stronie.

### 4.3 Zaufanie wywołującego

`BridgeActivity` odczytuje `getCallingPackage()`. Wywołanie bez tej informacji (czyli nie przez `startActivityForResult`) jest odrzucane jako `denied`. Dla znanego pakietu sc2t liczy odcisk SHA-256 jego podpisu i porównuje z zapamiętanym:

- brak wpisu: ekran zgody z nazwą aplikacji, nazwą pakietu i odciskiem; zgoda zapisuje parę, odmowa zwraca `denied`,
- wpis zgodny: bez pytania,
- wpis niezgodny (aplikację przeinstalowano innym kluczem): ekran zgody z wyraźnym ostrzeżeniem, że podpis się zmienił.

Zapamiętane zaufanie żyje w prywatnych ustawieniach sc2t i ma ekran do wyczyszczenia. Zaufanie nie jest nigdy nadawane automatycznie, nawet dla pakietu `pl.mateusz.helios`.

### 4.4 Zgoda na operację

Zaufanie z pkt 4.3 nie wystarcza dla operacji, które zmieniają stan systemu. Operacje dzielą się na trzy klasy:

| klasa | pytanie | operacje |
| --- | --- | --- |
| odczyt | nigdy | `state` |
| zwykła | raz na parę aplikacja-operacja, z możliwością cofnięcia | `grant_permission`, `write_settings`, `set_home`, `mic_release`, `mic_restore`, `adb_off` |
| wysokiego ryzyka | zawsze, przy każdym wywołaniu | `root_adb_on`, `install_apk` |

Ekran zgody nazywa operację po ludzku i mówi, co się stanie. Dla `root_adb_on` mówi wprost, że uruchomi exploita jądra, że może to zawiesić zegar i że ADB po Wi-Fi wystawia zegar na całą sieć lokalną.

### 4.5 Jedna operacja naraz

sc2t trzyma jedną blokadę mostka. Drugie wywołanie w trakcie pierwszego dostaje `busy`; dotyczy to także wyzwalaczy agenta HTTP, które dziś blokady nie biorą (pkt 8.4). Łańcuch roota nie może działać w dwóch kopiach.

### 4.6 Wersjonowanie

`api` rośnie, gdy zmienia się znaczenie istniejącego pola albo operacji. sc2t odrzuca nieznane `api` odpowiedzią `unsupported_api` i podaje w `detail` swoją wersję. Helios wymaga `versionCode` sc2t nie niższego niż wartość zapisana w kodzie razem z numerem `api`; niższe = propozycja aktualizacji narzędzia (pkt 6.2). Nowa operacja nie podnosi `api`: nieznana nazwa daje `unsupported`, co Helios traktuje jak brak funkcji.

## 5. Operacje

### 5.1 `state`

Odczyt, bez zgody, bez roota, bez skutków ubocznych. Zwraca JSON:

```json
{
  "root": true,
  "adb": true,
  "ssh": false,
  "chain_running": false,
  "firmware": "LenovoCD-24502F_ROW_1.2.2.627_220105",
  "firmware_supported": true,
  "tool_version": "2.18",
  "api": 1,
  "trusted": true,
  "mic_holders": ["com.google.android.apps.mediashell"]
}
```

`root` i `ssh` sc2t sprawdza własnym kanałem, `adb` z właściwości systemowej, `mic_holders` z listy aktywnych nagrań (puste, gdy żadna z pilnowanych powłok nie trzyma mikrofonu). Gdy kanał roota nie odpowiada, `root` to `false`, a nie błąd.

### 5.2 `root_adb_on`

Uruchamia łańcuch roota, jeśli root nie żyje, i włącza ADB po Wi-Fi. Operacja wysokiego ryzyka: zgoda przy każdym wywołaniu.

Przebieg: ekran zgody, ekran postępu z tym samym logiem, który sc2t pokazuje dziś, wynik. Limit czasu 4 minuty; przekroczenie to `failed` z ostatnią linią logu w `detail`. Przy żywym rootcie łańcuch nie jest powtarzany, włączane jest samo ADB.

Wynik `ok` znaczy: root odpowiada i `service.adb.tcp.port` to 5555.

### 5.3 `adb_off`

Wyłącza ADB po Wi-Fi i przywraca `ro.adb.secure`. Nie gasi roota. Zwykła zgoda.

### 5.4 `grant_permission`

`args`: `{"permission": "android.permission.RECORD_AUDIO"}`.

Nadaje uprawnienie **wyłącznie pakietowi wywołującemu**, wyłącznie z listy dopuszczonej w sc2t (początkowo `RECORD_AUDIO`). Inne uprawnienie albo inny pakiet to `unsupported`. Operacja jest bezczynna, gdy uprawnienie już jest.

### 5.5 `write_settings`

Nadaje pakietowi wywołującemu operację `WRITE_SETTINGS`. Po niej Helios zmienia jasność systemową i czas wygaszania sam, bez roota i bez sc2t; jest to jedyny cel tej operacji. Cofnięcie: przez ekran zaufania sc2t.

### 5.6 `set_home`

Ustawia pakiet wywołujący jako domyślny ekran główny. Sprawdza wcześniej, że wywołujący faktycznie deklaruje kategorię `HOME`; inaczej `unsupported`. `detail` przypomina, jak wrócić do poprzedniego ekranu głównego.

### 5.7 `mic_release` i `mic_restore`

`mic_release` odbiera uprawnienie do mikrofonu powłokom z twardej listy w sc2t (dziś `com.google.android.apps.mediashell` i `com.google.assistant.launcher`) i restartuje te procesy, żeby zwolniły otwarty strumień. `mic_restore` przywraca stan wyjściowy. Lista jest w sc2t, nie w argumentach: wywołujący nie wskazuje, komu odebrać mikrofon.

Uzasadnienie w `detail`: te powłoki trzymają mikrofon w 48 kHz stereo, a sterownik nie przelicza formatu osobno dla klienta, więc każda inna aplikacja dostaje sześciokrotnie za dużo próbek. Potwierdzone na sprzęcie 19 września 2026: po `mic_release` sesja Heliosa raportuje `1ch 16000Hz`.

Skutek uboczny dla człowieka: na zegarze przestaje działać "Hey Google". Ekran zgody musi to powiedzieć.

### 5.8 `install_apk`

`args`: `{"uri": "content://...", "package": "pl.mateusz.helios"}`.

Cicha instalacja wskazanego pliku. Operacja wysokiego ryzyka: zgoda przy każdym wywołaniu, na ekranie widnieje nazwa pakietu i wersja odczytane z pliku, nie z argumentów. sc2t odmawia (`unsupported`), gdy pakiet w pliku różni się od pakietu wywołującego: mostek służy do aktualizowania siebie, nie do instalowania czegokolwiek. Plik przekazywany jest przez `content://` z `FLAG_GRANT_READ_URI_PERMISSION`.

## 6. Strona Heliosa

### 6.1 Menu

Nowa pozycja **Narzędzia zegara** w istniejącym ukrytym menu. Zawartość zależy od wykrywania z pkt 3:

- brak sc2t: jedna pozycja **Zainstaluj narzędzia** (pkt 6.2) i zdanie, do czego służą,
- sc2t obecne, root nieżywy: **Włącz root i ADB**, poniżej stan z `state`,
- root żywy: **Wyłącz ADB**, **Napraw mikrofon** (`mic_release`, widoczna tylko gdy `mic_holders` niepuste), **Pozwól na jasność** (`write_settings`, widoczna tylko gdy brak), **Ustaw jako ekran główny** (`set_home`, widoczna tylko gdy Helios nim nie jest), **Uprawnienie mikrofonu** (`grant_permission`, widoczna tylko gdy brak).

Pozycja znika z menu, gdy jej warunek przestaje obowiązywać. Menu nie pokazuje stanu roota jako alarmu: brak narzędzi to normalny stan.

### 6.2 Instalacja sc2t

Ten sam mechanizm, którym Helios aktualizuje siebie, uogólniony o źródło: repozytorium, wzorzec nazwy pliku i oczekiwany pakiet stają się parametrami. Reguły z SPEC 0.10 pkt 7 zostają bez zmian: tylko wydania ostateczne, tag `vX.Y.Z`, jeden pasujący plik, adresy wyłącznie w domenach GitHuba, limit rozmiaru, weryfikacja nazwy pakietu w pobranym pliku przed instalacją.

Instalacja sc2t idzie przez systemowy instalator z potwierdzeniem na ekranie, bo Helios nie ma jeszcze żadnych przywilejów. Po instalacji Helios nie uruchamia nic samoczynnie: menu przebudowuje się i pokazuje **Włącz root i ADB**.

### 6.3 Korzystanie z nadanych uprawnień

Po `grant_permission` Helios przestaje prosić o mikrofon oknem systemowym. Po `write_settings` tryb nocny i jasność działają bez sc2t; sam tryb nocny jest osobną funkcją i nie wchodzi w zakres tej specyfikacji, tu powstaje tylko uprawnienie. Po `set_home` Helios wstaje po restarcie zasilania, co dziś jest jego największą luką.

Helios nigdy nie zakłada, że uprawnienie zostało nadane: każdą funkcję włącza dopiero po sprawdzeniu faktycznego stanu.

### 6.4 Zachowanie bez sc2t

Brak sc2t, odmowa zgody i każdy błąd mostka są równoważne: funkcja pozostaje niedostępna, Helios pracuje jak dziś, a w pasku statusu pojawia się jedno zdanie. Żadna ścieżka Heliosa nie zależy od mostka.

## 7. Bezpieczeństwo i ryzyka

### 7.1 Firmware

Kalibracja exploita dotyczy kompilacji `LenovoCD-24502F_ROW_1.2.2.627_220105`. `root_adb_on` na innym `ro.build.display.id` zwraca `wrong_firmware` bez uruchamiania czegokolwiek. `state` podaje `firmware_supported`, więc Helios nie pokazuje pozycji, która i tak odmówi.

### 7.2 Zawieszenie zegara

Prymityw zapisu może zawiesić jądro. Dlatego łańcuch startuje wyłącznie z ręki człowieka, sc2t nie ponawia go samoczynnie, a ekran zgody mówi o ryzyku. sc2t zapisuje znacznik rozpoczęcia i po ponownym starcie nie proponuje automatycznie kolejnej próby.

### 7.3 Powierzchnia ataku

Mostek nie przyjmuje poleceń powłoki, nie przyjmuje nazw pakietów do operacji na cudzych uprawnieniach i nie zwraca tokenu agenta. Jedyne dane od wywołującego to nazwa operacji i wąsko walidowane argumenty. Aplikacja, która podszyje się pod nazwę pakietu Heliosa, ma inny podpis, więc trafia na ekran zgody z ostrzeżeniem.

### 7.4 ADB po Wi-Fi

Włączone ADB wystawia zegar całej sieci lokalnej bez uwierzytelnienia. Ekran zgody mówi to wprost, `adb_off` jest zawsze dostępne, a `state` pozwala Heliosowi pokazywać, że ADB jest włączone.

### 7.5 Dane

Mostek nie przekazuje niczego z Home Assistanta ani z konfiguracji Heliosa. `detail` jest przeznaczone dla człowieka i nie zawiera tokenów, ścieżek prywatnych ani zawartości logów poza ostatnią linią błędu łańcucha.

## 8. Zmiany po stronie sc2t

1. `BridgeActivity`: eksportowana, jawna akcja, kontrola wywołującego, ekrany zgody, ekran postępu, wykonanie operacji, wynik. To jedyny nowy eksportowany komponent.
2. Wywołanie nadania dostępu do kanału roota dla operacji, które tego wymagają, bez instalowania wywołującemu binarki podnoszącej uprawnienia. Helios nie dostaje roota, tylko wynik operacji.
3. Ustrukturyzowany stan (`state`) zamiast wolnego tekstu w raporcie.
4. Blokada wspólna dla mostka, interfejsu i wyzwalaczy agenta; dziś wyzwalacz agenta nie bierze żadnej, więc da się uruchomić dwa przebiegi exploita naraz.
5. Obsługa przełącznika ADB w trybie bez interfejsu, dziś dostępna tylko przy żywej aktywności.
6. Poprawka ścieżki binarki kanału: skrypt startowy szuka jej w katalogu plików, a kod rozpakowuje ją do podkatalogu.
7. Dokument API po angielsku w repozytorium sc2t, odsyłający do tej specyfikacji.

## 9. Kryteria akceptacji

1. Helios bez zainstalowanego sc2t pokazuje w menu wyłącznie pozycję instalacji i działa jak dziś; żaden inny ekran się nie zmienia.
2. Pozycja instalacji pobiera sc2t z wydań GitHuba, odrzuca plik o innej nazwie pakietu i kończy się widocznym systemowym potwierdzeniem instalacji.
3. Pierwsze wywołanie dowolnej operacji pokazuje ekran zaufania z nazwą pakietu i odciskiem podpisu; odmowa daje `denied`, a Helios pokazuje jedno zdanie i nic nie zmienia.
4. `state` działa bez roota i zwraca komplet pól z pkt 5.1.
5. `root_adb_on` na wspieranym firmware kończy się `ok`, a `getprop service.adb.tcp.port` zwraca 5555; na innym firmware kończy się `wrong_firmware` bez uruchomienia łańcucha.
6. Dwa równoczesne wywołania `root_adb_on` dają jedno wykonanie i jedno `busy`, także gdy drugie przyjdzie z agenta HTTP.
7. `grant_permission` z uprawnieniem spoza listy albo dla innego pakietu daje `unsupported`; z `RECORD_AUDIO` sprawia, że Helios przestaje prosić o mikrofon.
8. `mic_release` sprawia, że sesja nagrywania Heliosa raportuje `1ch 16000Hz`, a `mic_restore` przywraca stan wyjściowy; oba są widoczne w `state` przez `mic_holders`.
9. `write_settings` sprawia, że Helios zmienia jasność systemową bez sc2t; `set_home` sprawia, że po odcięciu zasilania zegar wstaje z Heliosem na ekranie.
10. `install_apk` z plikiem innego pakietu niż wywołujący daje `unsupported`; z własną nowszą wersją instaluje ją bez dotykania ekranu.
11. Każda operacja kończy się wynikiem w mniej niż 5 minut, a Helios po `RESULT_CANCELED` wraca do menu bez zmian stanu.
12. Kontrakt z nieznanym `api` daje `unsupported_api`, nieznana operacja daje `unsupported`, i żadne z nich nie zmienia stanu urządzenia.

## 10. Kolejność wdrożenia

1. sc2t: blokada wspólna, poprawka ścieżki kanału, ustrukturyzowany stan, przełącznik ADB bez interfejsu. Same porządki, bez mostka.
2. sc2t: `BridgeActivity` z zaufaniem, zgodami i operacjami `state`, `root_adb_on`, `adb_off`.
3. Helios: wykrywanie, menu, klient mostka, obsługa wyników. Do tego miejsca całość ma sens użytkowy.
4. sc2t: `grant_permission`, `write_settings`, `mic_release`, `mic_restore`, `set_home`.
5. Helios: korzystanie z nadanych uprawnień i uogólniony instalator sc2t.
6. sc2t: `install_apk`, Helios: cicha aktualizacja własna.
7. Dokumentacja po obu stronach, wydania, weryfikacja na zegarze od czystego stanu po odcięciu zasilania.

## 11. Ryzyka

| ryzyko | skutek | co z tym robimy |
| --- | --- | --- |
| exploit zawiesza jądro | zegar wymaga odcięcia prądu | tylko ręczne uruchomienie, brak samoczynnych ponowień, ostrzeżenie na ekranie zgody |
| inne firmware | łańcuch nie działa albo szkodzi | twarde sprawdzenie `ro.build.display.id` przed czymkolwiek |
| złośliwa aplikacja woła mostek | cudze uprawnienia, ADB w sieci | jawna intencja, kontrola podpisu, zgoda człowieka, wąskie operacje |
| użytkownik traci "Hey Google" | mniej funkcji fabrycznych | `mic_restore`, informacja na ekranie zgody |
| ADB zostaje włączone i zapomniane | zegar otwarty dla sieci lokalnej | widoczny stan w menu Heliosa, `adb_off` zawsze dostępne |
| rozjazd wersji dwóch aplikacji | operacja nie istnieje albo znaczy co innego | `api` w każdym wywołaniu, próg `versionCode`, `unsupported` jako normalna odpowiedź |
| root znika po odcięciu zasilania | funkcje przestają działać w środku nocy | uprawnienia nadane raz zostają na stałe; roota wymaga tylko ich nadanie, nie używanie |
