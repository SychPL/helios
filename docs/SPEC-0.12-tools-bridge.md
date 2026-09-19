# SPEC 0.12 - mostek Helios ↔ Smart Clock 2 Tools

Status: po przeglądzie (własnym i Codex, runda 1). Dotyczy dwóch repozytoriów: `SychPL/helios` (aplikacja, pakiet `pl.mateusz.helios`) i `SychPL/smartclock2tool` (narzędzie, pakiet `pl.mateusz.clockadbprobe`, dalej **sc2t**).

## 1. Problem i cel

Zegar Lenovo Smart Clock 2 nie ma ADB, launchera ani sklepu. Pierwszy plik APK trafia na niego trikiem z TalkBackiem, a root i ADB daje sc2t, przy czym oba znikają po odcięciu zasilania. Helios działa dziś bez żadnych przywilejów i przez to nie umie czterech rzeczy, które użytkownik musi robić ręcznie: nadać sobie uprawnienia do mikrofonu, odebrać mikrofon fabrycznej powłoce Google (która trzyma go w 48 kHz stereo i psuje strumień hasła wybudzającego), zmienić jasność systemową (tryb nocny) oraz zainstalować własną aktualizację bez dotykania ekranu.

Cel: Helios ma jednym dotknięciem doprowadzić do stanu, w którym te rzeczy są załatwione, korzystając z sc2t jako źródła uprawnień, i ma działać bez zmian, gdy sc2t nie ma.

Nie-cele: Helios nie dostaje trwałego roota, nie zawiera exploita, nie uzyskuje powłoki. sc2t nie zyskuje wiedzy o Home Assistancie ani o zawartości Heliosa.

## 2. Decyzje

1. **Kanał to jawna intencja z wynikiem**, nie HTTP. Agent HTTP sc2t słucha na wszystkich interfejsach, pozwala uruchamiać dowolne polecenia i pobierać kod, a jego token jest prywatny dla sc2t. Nie jest fundamentem dla drugiej aplikacji.
2. **Zamknięta lista operacji**, nie powłoka. Każda operacja to jedna nazwana czynność z własną walidacją.
3. **Skutkiem większości operacji jest nadanie Heliosowi uprawnienia, nie wykonywanie pracy za niego.** Po nadaniu `WRITE_SETTINGS` Helios steruje jasnością sam, bez roota i bez sc2t.
4. **Zaufanie przy pierwszym użyciu, po obu stronach.** sc2t nie zna z góry podpisu Heliosa (każdy buduje własną kopię), a Helios nie zna podpisu sc2t, więc obie strony pytają użytkownika raz i zapamiętują parę: nazwa pakietu plus odcisk certyfikatu podpisującego.
5. **Helios nie instaluje roota automatycznie po starcie.** Uruchomienie łańcucha zawsze wychodzi od człowieka, bo prymityw zapisu potrafi zawiesić jądro.
6. **Skutek każdej operacji ustala się przez ponowny odczyt stanu**, nigdy przez sam kod wyniku. Wynik mówi, co sc2t sądzi, że zrobił; stan mówi, co jest.

## 3. Wykrywanie sc2t

Helios sprawdza, wszystko bez uprawnień i bez wołania mostka:

| co | jak | znaczenie |
| --- | --- | --- |
| obecność | `PackageManager.getPackageInfo("pl.mateusz.clockadbprobe", 0)` | brak = menu pokazuje tylko pozycję instalacji |
| tożsamość | odcisk certyfikatu podpisującego (pkt 4.3) porównany z zapamiętanym | zmiana = pytanie użytkownika przed pierwszym wywołaniem |
| wersja | `versionCode` i `versionName` z tego samego wyniku, próg z pkt 4.6 | niższa = menu proponuje aktualizację narzędzia |
| ADB (konfiguracja) | `getprop service.adb.tcp.port` | `5555` znaczy tylko tyle, że tak ustawiono właściwość |
| ADB (fakt) | próba połączenia TCP na `127.0.0.1:5555` | udane = demon faktycznie nasłuchuje |

Właściwość systemowa jest konfiguracją, a nie dowodem działania: demon czyta ją przy starcie, więc do czasu jego restartu jedno z drugim się rozjeżdża. Menu opiera się na próbie połączenia, a właściwość służy tylko do wyjaśnienia rozbieżności użytkownikowi.

Stanu roota Helios sam nie zbada; pyta o niego operacją `state` (pkt 5.1). Wynik wykrywania nie jest zapamiętywany na trwałe: liczy się stan w chwili otwarcia menu.

## 4. Kontrakt mostka

### 4.1 Wywołanie

Helios woła `startActivityForResult` z **jawną** intencją:

```
component = pl.mateusz.clockadbprobe/.BridgeActivity
action    = pl.mateusz.clockadbprobe.action.BRIDGE
data      = content://... (tylko operacje przekazujące plik, pkt 5.8)
flags     = FLAG_GRANT_READ_URI_PERMISSION (tylko wtedy)
extras:
  api  : int     - wersja kontraktu, dla tej specyfikacji 1
  op   : String  - nazwa operacji z pkt 5
  args : String  - argumenty operacji jako JSON, dopuszczalne puste
```

`BridgeActivity` jest jedynym eksportowanym wejściem mostka. Jawna intencja chroni przed przechwyceniem wywołania przez inną aplikację, ale **nie uwierzytelnia nadawcy**: wywołać eksportowaną aktywność może każdy, a filtr akcji nie zabezpiecza wywołań jawnych. Dlatego sc2t sprawdza w kodzie, że `action` jest dokładnie tą powyżej, że typy pól się zgadzają i że `args` jest poprawnym JSON-em; cokolwiek innego to `unsupported` bez skutków ubocznych.

Wymagania dotyczące zadania i trybu:

- `launchMode="standard"`. `singleTask` i `singleInstance` są wykluczone, bo aktywność w osobnym zadaniu dostaje od systemu natychmiastowe `RESULT_CANCELED`, a `getCallingPackage()` przestaje działać. `singleTop` jest wykluczony, bo `onNewIntent()` przyniósłby nowe argumenty do ekranu, który ma już ustaloną tożsamość i zgodę wywołującego.
- Helios nie ustawia `FLAG_ACTIVITY_NEW_TASK`.
- sc2t odrzuca wywołanie z `FLAG_ACTIVITY_FORWARD_RESULT` jako `denied`: ta flaga przenosi adresata wyniku na inną aktywność, więc tożsamość z `getCallingPackage()` przestałaby odpowiadać temu, kto wynik odbierze.
- Wszystkie przyciski ekranów zgody mają `setFilterTouchesWhenObscured(true)`, żeby nie dało się ich zatwierdzić dotykiem przez nakładkę innej aplikacji.

### 4.2 Odpowiedź

Zawsze `RESULT_OK` z kompletem danych albo `RESULT_CANCELED`, gdy użytkownik zamknął okno zgody lub postępu. Dane wyniku:

```
status  : String - ok | denied | unsupported | unsupported_api | busy | failed | wrong_firmware | in_progress | unknown
detail  : String - jedno zdanie dla człowieka (pkt 7.5)
state   : String - JSON migawki stanu z pkt 5.1, zawsze dołączany, także przy busy i failed
op_id   : String - identyfikator tego żądania, ten sam w migawce stanu
```

Znaczenie kodów: `denied` decyzja człowieka albo brak zaufania, `unsupported` nieznana operacja, zły kształt żądania albo niespełniony warunek wstępny, `unsupported_api` niezgodna wersja kontraktu, `busy` inna operacja w toku, `failed` wykonanie zakończone niepowodzeniem, `wrong_firmware` niezgodny identyfikator kompilacji (pkt 7.1), `in_progress` operacja nadal trwa poza ekranem, `unknown` sc2t nie wie, czy i co się wykonało.

`in_progress` i `unknown` nie są błędami. Łańcuch roota potrafi pracować dalej po zamknięciu ekranu, a zawieszenie jądra nie daje żadnej odpowiedzi, więc kontrakt nazywa te stany zamiast udawać, że ich nie ma.

**Przerwanie w trakcie.** Zabicie którejkolwiek z aplikacji, odcięcie zasilania albo wyjście użytkownika z okna postępu kończy się dla Heliosa brakiem wyniku albo `RESULT_CANCELED`. Żadna z tych sytuacji nie mówi, czy operacja się wykonała. Dlatego:

- Helios po każdej operacji innej niż `state` wykonuje `state` i porównuje migawkę z oczekiwaniem; interfejs buduje z migawki i z własnych sprawdzeń lokalnych (pkt 6.3), nigdy z samego kodu wyniku.
- sc2t zapisuje trwale etap każdej operacji pod jej `op_id`, zanim zrobi cokolwiek nieodwracalnego, i potrafi po ponownym starcie powiedzieć, na czym stanęła.
- Ponowienie jest dozwolone dopiero, gdy migawka mówi `chain: idle`. Przy `chain: running` powtórzenie zwraca `busy`, przy `chain: unknown` sc2t wymaga potwierdzenia człowieka, bo poprzedni uprzywilejowany proces potomny mógł przeżyć śmierć aktywności.

Limity czasu są dwa i liczone osobno: oczekiwanie na decyzję człowieka nie jest ograniczone, a wykonanie ma limit z opisu operacji. Helios uznaje brak jakiegokolwiek wyniku po 6 minutach za `unknown`, nie za `failed`.

### 4.3 Tożsamość i zaufanie

Odcisk to SHA-256 certyfikatu podpisującego, odczytany przez `GET_SIGNING_CERTIFICATES` i `SigningInfo`. Dla pakietu z historią rotacji liczy się bieżący certyfikat, dla pakietu z wieloma podpisującymi zbiór odcisków; zgodność oznacza identyczny zbiór.

sc2t odczytuje `getCallingPackage()`. Wywołanie bez tej informacji (czyli nie przez `startActivityForResult`) jest odrzucane jako `denied`. Dalej porównuje odcisk wywołującego z zapamiętanym:

- brak wpisu: ekran zaufania z nazwą aplikacji, nazwą pakietu i odciskiem; zgoda zapisuje parę, odmowa zwraca `denied`,
- wpis zgodny: bez pytania,
- wpis niezgodny: ekran zaufania z wyraźnym ostrzeżeniem, że podpis się zmienił. Nowy odcisk **nie dziedziczy** żadnych zgód poprzedniego; wszystkie zapisane dla tego pakietu przepadają.

Helios stosuje tę samą zasadę do sc2t (pkt 3): zapamiętuje odcisk narzędzia przy pierwszym użyciu i pyta użytkownika, gdy się zmieni. Bez tego odinstalowanie sc2t i podstawienie aplikacji o tej samej nazwie pakietu wystarczyłoby, by przejąć rolę mostka.

Zapamiętane zaufanie i zgody żyją w prywatnych ustawieniach sc2t, są widoczne na jednym ekranie i dają się skasować pojedynczo albo w całości. Cofnięcie zaufania unieważnia też oczekujące żądania tego pakietu. Zaufanie nie jest nigdy nadawane automatycznie, nawet dla pakietu `pl.mateusz.helios`.

### 4.4 Zgoda na operację

Zaufanie z pkt 4.3 nie wystarcza dla operacji, które zmieniają stan systemu. Operacje dzielą się na trzy klasy:

| klasa | pytanie | operacje |
| --- | --- | --- |
| odczyt | nigdy | `state` |
| zwykła | raz na krotkę (pakiet, odcisk, użytkownik Androida, operacja, istotne argumenty) | `grant_permission`, `write_settings`, `set_home`, `mic_release`, `mic_restore`, `adb_off`, `adb_on` |
| wysokiego ryzyka | zawsze, przy każdym wywołaniu | `root_adb_on`, `install_apk` |

"Istotne argumenty" to te, które zmieniają znaczenie zgody: dla `grant_permission` nazwa uprawnienia. Zgoda na `RECORD_AUDIO` nie obejmuje więc uprawnienia dodanego do listy w przyszłej wersji narzędzia.

**Wiązanie zgody z żądaniem.** Po weryfikacji, a przed pokazaniem pytania, sc2t tworzy niezmienny zapis żądania: `op_id`, pakiet, odcisk, operacja, istotne argumenty, a dla `install_apk` także skrót prywatnej kopii pliku. Bezpośrednio przed wykonaniem sprawdza ponownie, że pakiet o tej nazwie nadal jest zainstalowany, ma ten sam odcisk i że zgoda nie została w międzyczasie cofnięta. Inaczej odinstalowanie albo podmiana pakietu przy otwartym oknie zgody byłaby wyścigiem, który daje uprawnienia komuś innemu.

Ekran zgody nazywa operację po ludzku i mówi, co się stanie. Dla `root_adb_on` mówi wprost, że uruchomi exploita jądra, że może to zawiesić zegar i jakie są skutki włączenia ADB (pkt 7.4).

### 4.5 Blokada i odczyt stanu

sc2t trzyma jedną blokadę wykonawczą. Obejmuje ona **rzeczywisty czas pracy wykonawcy**, a nie czas życia ekranu: zamknięcie okna postępu nie zwalnia blokady, dopóki uprzywilejowany proces potomny żyje. Drugie wywołanie operacji zmieniającej stan dostaje `busy`; dotyczy to także wyzwalaczy agenta HTTP, które dziś blokady nie biorą (pkt 8.4). Łańcuch roota nie może działać w dwóch kopiach.

`state` nigdy nie czeka na tę blokadę. Migawka powstaje z limitem czasu 3 s na całość; pole, którego nie udało się zmierzyć w tym czasie, ma wartość `"unknown"`, a nie wartość domyślną. Każda odpowiedź, także `busy`, niesie świeżą migawkę, więc Helios po timeoucie zawsze ma jak sprawdzić, co się dzieje. Uwierzytelnienie wywołującego zawsze poprzedza zajęcie blokady.

### 4.6 Wersjonowanie

`api` rośnie, gdy zmienia się znaczenie istniejącego pola albo operacji. sc2t odrzuca nieznane `api` odpowiedzią `unsupported_api` i podaje w `detail` swoją wersję. Helios wymaga `versionCode` sc2t nie niższego niż wartość zapisana w kodzie razem z numerem `api`; niższe daje propozycję aktualizacji narzędzia (pkt 6.2). Nowa operacja nie podnosi `api`: nieznana nazwa daje `unsupported`, co Helios traktuje jak brak funkcji.

## 5. Operacje

### 5.1 `state`

Odczyt, bez zgody, bez blokady, bez skutków ubocznych. Każde pole może mieć wartość `"unknown"`. Migawka:

```json
{
  "op_id": "9f2c...",
  "measured_at_ms": 1789760000000,
  "root": true,
  "adb_property": "5555",
  "adb_listening": true,
  "ssh": false,
  "chain": "idle",
  "firmware": "LenovoCD-24502F_ROW_1.2.2.627_220105",
  "firmware_supported": true,
  "tool_version": "2.18",
  "api": 1,
  "trusted": true,
  "caller": {
    "package": "pl.mateusz.helios",
    "version_code": 29,
    "record_audio": "granted",
    "write_settings": "allowed",
    "is_home": false,
    "declares_home": true
  },
  "mic_holders": ["com.google.android.apps.mediashell"],
  "mic_saved_state": "none"
}
```

Zasady pomiaru:

- `root`, `ssh`, `mic_holders`: mierzone kanałem uprzywilejowanym. **Bez działającego roota `mic_holders` to `"unknown"`, nigdy pusta lista.** Android 10 anonimizuje konfiguracje nagrań dla zwykłych aplikacji i pomija część źródeł systemowych, więc lista zbudowana bez przywilejów byłaby myląca dokładnie wtedy, gdy problem występuje.
- `adb_property` to odczyt właściwości, `adb_listening` to faktyczna próba połączenia. Rozjazd tych dwóch pól jest normalny do czasu restartu demona i Helios pokazuje go jako osobny komunikat.
- `chain`: `idle`, `running` albo `unknown` (patrz pkt 4.2).
- `caller`: stan tego, co dotyczy wywołującego. `record_audio` to `granted` albo `denied`, `write_settings` to `allowed` albo `denied` (odpowiednik `Settings.System.canWrite()` po stronie wywołującego), `is_home` mówi, czy wywołujący jest aktualnym domyślnym ekranem głównym, `declares_home` czy w ogóle deklaruje taką kategorię.
- `mic_saved_state`: `none` albo `saved`, czyli czy jest zapis stanu sprzed `mic_release` (pkt 5.7).

Helios może te same rzeczy sprawdzać lokalnie (własne uprawnienie, `canWrite()`, domyślny ekran główny) i tak robi w pierwszej kolejności; `state` jest źródłem dla tego, czego sam nie zmierzy.

### 5.2 `root_adb_on`

Uruchamia łańcuch roota, jeśli root nie żyje, i włącza ADB po Wi-Fi. Operacja wysokiego ryzyka: zgoda przy każdym wywołaniu.

Przebieg: ekran zgody, ekran postępu z tym samym logiem, który sc2t pokazuje dziś, wynik. Limit wykonania 4 minuty; przekroczenie daje `in_progress`, jeśli proces wykonawcy żyje, a `unknown`, jeśli nie da się tego stwierdzić. Przy żywym rootcie łańcuch nie jest powtarzany, włączane jest samo ADB.

Wynik `ok` znaczy: kanał roota odpowiada **i** połączenie TCP na port 5555 zostaje przyjęte. Sama właściwość nie wystarcza.

### 5.3 `adb_on` i `adb_off`

`adb_on` włącza ADB po Wi-Fi przy żywym rootcie, bez uruchamiania łańcucha; przy martwym rootcie zwraca `unsupported`, a Helios proponuje `root_adb_on`. Rozdzielenie jest potrzebne, bo dziś jedyną drogą do ponownego włączenia ADB było wywołanie operacji wysokiego ryzyka.

`adb_off` wyłącza ADB: ustawia właściwość portu na zero, wymusza restart demona i potwierdza, że port 5555 nie przyjmuje już połączeń. Istniejące sesje padają razem z demonem. `ro.adb.secure` jest właściwością tylko do odczytu w zwykłym trybie, więc sc2t zmienia ją tym samym zapisem do obszaru właściwości, którego używa przy włączaniu, i dopiero restart demona nadaje jej znaczenie; opis tego mechanizmu należy do dokumentacji sc2t. Żadna z tych operacji nie gasi roota.

### 5.4 `grant_permission`

`args`: `{"permission": "android.permission.RECORD_AUDIO"}`.

Nadaje uprawnienie **wyłącznie pakietowi wywołującemu**, wyłącznie z listy dopuszczonej w sc2t (początkowo `RECORD_AUDIO`). Inne uprawnienie albo inny pakiet to `unsupported`. Warunek wstępny: wywołujący deklaruje to uprawnienie w manifeście, inaczej `unsupported`. Operacja jest bezczynna, gdy uprawnienie już jest. Potwierdzeniem jest `caller.record_audio` w migawce oraz lokalne sprawdzenie po stronie Heliosa.

### 5.5 `write_settings`

Nadaje pakietowi wywołującemu operację `WRITE_SETTINGS`. Po niej Helios zmienia jasność systemową i czas wygaszania sam, bez roota i bez sc2t; jest to jedyny cel tej operacji.

Warunek wstępny: `android.permission.WRITE_SETTINGS` jest zadeklarowane w manifeście wywołującego, inaczej `unsupported`. Potwierdzeniem jest `Settings.System.canWrite()` po stronie Heliosa, odzwierciedlone w `caller.write_settings`.

### 5.6 `set_home`

Ustawia pakiet wywołujący jako domyślny ekran główny. Warunki wstępne: wywołujący deklaruje kategorię `HOME` i ma dokładnie jeden włączony komponent obsługujący `MAIN` z tą kategorią. Przy zerowej liczbie kandydatów albo przy wielu sc2t zwraca `unsupported` i wymienia je w `detail`, zamiast zgadywać. Potwierdzeniem jest `caller.is_home`. `detail` przypomina, jak wrócić do poprzedniego ekranu głównego.

### 5.7 `mic_release` i `mic_restore`

`mic_release` odbiera uprawnienie do mikrofonu powłokom z twardej listy w sc2t (dziś `com.google.android.apps.mediashell` i `com.google.assistant.launcher`) i restartuje te procesy, żeby zwolniły otwarty strumień. Lista jest w sc2t, nie w argumentach: wywołujący nie wskazuje, komu odebrać mikrofon.

Zasady przywracania:

- przed pierwszą zmianą sc2t zapisuje trwale rzeczywisty stan każdego pakietu z listy (czy uprawnienie było nadane) i oznacza `mic_saved_state: saved`,
- powtórne `mic_release` przy istniejącym zapisie **nie nadpisuje** go stanem już zmienionym,
- `mic_restore` odtwarza dokładnie zapisany stan i nigdy nie nadaje uprawnienia, którego wcześniej nie było; po sukcesie kasuje zapis,
- awaria między pakietami zostawia zapis nietknięty, więc `mic_restore` po restarcie zegara nadal wie, do czego wracać,
- restart zegara nie przywraca niczego samoczynnie: odebrane uprawnienie jest trwałe, i to jest celem.

Skutki uboczne, oba na ekranie zgody: na zegarze przestaje działać "Hey Google", a restart powłoki na chwilę zabiera ekran główny, jeśli to ona nim jest. Po wykonaniu sc2t wraca do wywołującego, przenosząc jego zadanie na wierzch; nie ma warunku wstępnego o widoczności wywołującego, bo w chwili wykonania na wierzchu jest z definicji ekran mostka.

Uzasadnienie w `detail`: te powłoki trzymają mikrofon w 48 kHz stereo, a sterownik nie przelicza formatu osobno dla klienta, więc każda inna aplikacja dostaje sześciokrotnie za dużo próbek. Potwierdzone na sprzęcie 19 września 2026: po odebraniu uprawnienia sesja Heliosa raportuje `1ch 16000Hz`.

### 5.8 `install_apk`

Plik przychodzi jako `Intent.data` (albo `ClipData`) z `FLAG_GRANT_READ_URI_PERMISSION`; adres w `args` nie dostaje żadnego grantu i jest ignorowany. Helios udostępnia plik własnym, nieeksportowanym `FileProvider`. `args` może nieść oczekiwany skrót pliku; rozbieżność ze skrótem kopii to `failed`.

Operacja wysokiego ryzyka: zgoda przy każdym wywołaniu. Przebieg:

1. uwierzytelnienie wywołującego (pkt 4.3) **przed** otwarciem strumienia,
2. kopiowanie do katalogu prywatnego sc2t z limitem 64 MB i 60 s; przekroczenie, brak miejsca, zerwany albo blokujący strumień to `failed`,
3. odczyt z **kopii**: nazwa pakietu, `versionCode`, `versionName`, odcisk podpisu, poprawność archiwum,
4. odmowa (`unsupported`), gdy pakiet w pliku różni się od wywołującego, gdy odcisk różni się od zainstalowanej wersji tego pakietu albo gdy `versionCode` jest niższy niż zainstalowany (instalacja wstecz wymaga odinstalowania i nie należy do mostka),
5. ekran zgody z danymi z kroku 3, nie z argumentów,
6. instalacja przez sesję `PackageInstaller`, do której sc2t podaje strumień z własnej kopii. Root nie znosi reguł SELinux, więc żadna ścieżka w katalogu prywatnym innej aplikacji nie jest czytana przez instalator,
7. kasowanie kopii niezależnie od wyniku, także po awarii i po restarcie (sprzątanie przy starcie sc2t).

Aktualizacja może zakończyć proces wywołującego, zanim odbierze on wynik. Dlatego potwierdzeniem instalacji jest wyłącznie `versionCode` odczytany po ponownym starcie, nigdy sam kod wyniku.

## 6. Strona Heliosa

### 6.1 Menu

Nowa pozycja **Narzędzia zegara** w istniejącym ukrytym menu. Widoczność każdej pozycji wynika z osobnego warunku, nie z jednego stanu zbiorczego:

| pozycja | warunek |
| --- | --- |
| Zainstaluj narzędzia | brak sc2t |
| Zaktualizuj narzędzia | sc2t starsze niż próg z pkt 4.6 |
| Włącz root i ADB | `root` fałszywe albo nieznane |
| Włącz ADB | `root` prawdziwe i `adb_listening` fałszywe |
| Wyłącz ADB | `adb_listening` prawdziwe |
| Napraw mikrofon | `mic_holders` niepuste albo nieznane przy żywym rootcie |
| Przywróć mikrofon | `mic_saved_state` to `saved` |
| Uprawnienie mikrofonu | Helios nie ma `RECORD_AUDIO` |
| Pozwól na jasność | `canWrite()` fałszywe |
| Ustaw jako ekran główny | Helios deklaruje `HOME` i nim nie jest |

Stan nieznany nigdy nie ukrywa pozycji naprawczej: jeśli sc2t nie potrafi zmierzyć `mic_holders`, pozycja naprawy zostaje widoczna z dopiskiem, że stanu nie udało się ustalić. Brak narzędzi to normalny stan, a nie alarm.

### 6.2 Instalacja sc2t

Ten sam mechanizm, którym Helios aktualizuje siebie, uogólniony o źródło: repozytorium, wzorzec nazwy pliku i oczekiwany pakiet stają się parametrami. Reguły z SPEC 0.10 pkt 7 zostają bez zmian: tylko wydania ostateczne, tag `vX.Y.Z`, jeden pasujący plik, adresy wyłącznie w domenach GitHuba, limit rozmiaru, weryfikacja nazwy pakietu w pobranym pliku przed instalacją.

Instalacja sc2t idzie przez systemowy instalator z potwierdzeniem na ekranie, bo Helios nie ma jeszcze żadnych przywilejów. Po instalacji Helios nie uruchamia nic samoczynnie: zapamiętuje odcisk podpisu narzędzia (pkt 4.3), przebudowuje menu i pokazuje **Włącz root i ADB**.

### 6.3 Korzystanie z nadanych uprawnień

Po `grant_permission` Helios przestaje prosić o mikrofon oknem systemowym. Po `write_settings` tryb nocny i jasność działają bez sc2t; sam tryb nocny jest osobną funkcją i nie wchodzi w zakres tej specyfikacji, tu powstaje tylko uprawnienie. Po `set_home` Helios wstaje po restarcie zasilania, co dziś jest jego największą luką.

Helios nigdy nie zakłada, że uprawnienie zostało nadane. Każdą funkcję włącza po sprawdzeniu faktycznego stanu, najpierw lokalnie (`checkSelfPermission`, `Settings.System.canWrite()`, domyślny ekran główny z `PackageManager`), a dla rzeczy, których sam nie widzi, z migawki `state`.

### 6.4 Zachowanie bez sc2t

Brak sc2t, odmowa zgody i każdy błąd mostka są równoważne: funkcja pozostaje niedostępna, Helios pracuje jak dziś, a w pasku statusu pojawia się jedno zdanie. Żadna ścieżka Heliosa nie zależy od mostka.

## 7. Bezpieczeństwo i ryzyka

### 7.1 Firmware

Kalibracja exploita dotyczy kompilacji `LenovoCD-24502F_ROW_1.2.2.627_220105`. `root_adb_on` na innym `ro.build.display.id` zwraca `wrong_firmware` bez uruchamiania czegokolwiek. `state` podaje `firmware_supported`, więc Helios nie pokazuje pozycji, która i tak odmówi.

### 7.2 Zawieszenie zegara

Prymityw zapisu może zawiesić jądro. Dlatego łańcuch startuje wyłącznie z ręki człowieka, sc2t nie ponawia go samoczynnie, a ekran zgody mówi o ryzyku. Znacznik etapu zapisany przed uruchomieniem sprawia, że po ponownym starcie sc2t wie o przerwanej próbie, pokazuje `chain: unknown` i wymaga świadomego potwierdzenia przed kolejną.

### 7.3 Powierzchnia ataku

Mostek nie przyjmuje poleceń powłoki, nie przyjmuje nazw pakietów do operacji na cudzych uprawnieniach i nie zwraca tokenu agenta. Jedyne dane od wywołującego to nazwa operacji, wąsko walidowane argumenty i ewentualny adres pliku z grantem. Aplikacja, która podszyje się pod nazwę pakietu Heliosa, ma inny odcisk podpisu, więc trafia na ekran zaufania z ostrzeżeniem, a wcześniejsze zgody dla tej nazwy już nie obowiązują.

### 7.4 ADB po Wi-Fi to koniec ochrony mostka

Włączone ADB daje każdemu w sieci lokalnej powłokę na zegarze bez uwierzytelnienia, a przez kanał roota także uprawnienia. Wszystko, co mostek chroni zgodami, jest wtedy dostępne z pominięciem mostka, w tym z każdej aplikacji na tym samym zegarze, która ma dostęp do sieci. Ekran zgody `root_adb_on` mówi to wprost. Uwierzytelnione ADB byłoby lepsze, ale fabryczna konfiguracja tego urządzenia go nie daje i kontrakt przyjmuje ten kompromis świadomie. `adb_off` jest zawsze dostępne, a menu Heliosa pokazuje fakt nasłuchu, nie samą właściwość.

### 7.5 Dane w odpowiedziach

Mostek nie przekazuje niczego z Home Assistanta ani z konfiguracji Heliosa. `detail` jest przeznaczone dla człowieka i przechodzi filtr: usuwane są ciągi wyglądające na tokeny i ścieżki zaczynające się od `/data/`, a długość jest ograniczona do jednego zdania. Ostatnia linia logu łańcucha trafia do `detail` dopiero po tym filtrze.

## 8. Zmiany po stronie sc2t

1. `BridgeActivity`: eksportowana, jawna akcja sprawdzana w kodzie, kontrola wywołującego, ekrany zaufania i zgody z ochroną przed nakładkami, ekran postępu, wykonanie operacji, wynik z migawką.
2. Trwały rejestr żądań (`op_id`, etap, wynik) przeżywający restart procesu i zegara.
3. Ustrukturyzowany stan (`state`) zamiast wolnego tekstu w raporcie, z wartościami `unknown` i limitem czasu pomiaru.
4. Blokada wspólna dla mostka, interfejsu i wyzwalaczy agenta, obejmująca czas życia uprzywilejowanego wykonawcy; dziś wyzwalacz agenta nie bierze żadnej, więc da się uruchomić dwa przebiegi exploita naraz.
5. Obsługa przełącznika ADB w trybie bez interfejsu, dziś dostępna tylko przy żywej aktywności, oraz potwierdzanie stanu ADB przez próbę połączenia zamiast przez właściwość.
6. Zapis i odtwarzanie stanu uprawnień mikrofonu powłok.
7. Poprawka ścieżki binarki kanału: skrypt startowy szuka jej w katalogu plików, a kod rozpakowuje ją do podkatalogu.
8. Dokument API po angielsku w repozytorium sc2t, odsyłający do tej specyfikacji.

## 9. Kryteria akceptacji

Ścieżki pozytywne:

1. Helios bez zainstalowanego sc2t pokazuje w menu wyłącznie pozycję instalacji i działa jak dziś; żaden inny ekran się nie zmienia.
2. Pozycja instalacji pobiera sc2t z wydań GitHuba, odrzuca plik o innej nazwie pakietu i kończy się widocznym systemowym potwierdzeniem instalacji; po niej Helios ma zapamiętany odcisk narzędzia.
3. `state` działa bez roota, bez blokady i w mniej niż 3 s, a pola niemierzalne mają wartość `unknown`; przy martwym rootcie `mic_holders` to `unknown`, nie pusta lista.
4. `root_adb_on` na wspieranym firmware kończy się `ok`, połączenie TCP na 5555 z innego urządzenia zostaje przyjęte; na innym firmware kończy się `wrong_firmware` bez uruchomienia łańcucha.
5. `adb_off` sprawia, że połączenie na 5555 z innego urządzenia jest odrzucane, a otwarta sesja ADB pada; `adb_on` przy żywym rootcie przywraca nasłuch bez uruchamiania łańcucha.
6. `grant_permission` z `RECORD_AUDIO` sprawia, że Helios przestaje prosić o mikrofon, a `caller.record_audio` to `granted`.
7. `write_settings` sprawia, że `Settings.System.canWrite()` w Heliosie jest prawdziwe i jasność systemowa zmienia się bez sc2t; `set_home` sprawia, że po odcięciu zasilania zegar wstaje z Heliosem na ekranie.
8. `mic_release` sprawia, że sesja nagrywania Heliosa raportuje `1ch 16000Hz`, pomiar rzeczywistego strumienia pokazuje częstotliwość równą żądanej, a hasło wybudzające jest rozpoznawane; `mic_restore` odtwarza dokładnie stan sprzed zmiany.
9. `install_apk` z własną nowszą wersją instaluje ją bez dodatkowego potwierdzenia instalatora po zgodzie mostka, a potwierdzeniem jest `versionCode` odczytany po restarcie procesu.

Ścieżki negatywne, każda bez zmiany stanu urządzenia:

10. Wywołanie bez `startActivityForResult`, z inną akcją, ze złymi typami pól albo z niepoprawnym JSON-em w `args`.
11. Wywołanie z `FLAG_ACTIVITY_FORWARD_RESULT`.
12. Wywołujący o nieznanym odcisku (odmowa na ekranie zaufania) oraz wywołujący, którego odcisk zmienił się po wcześniejszej zgodzie.
13. Odinstalowanie albo podmiana pakietu wywołującego przy otwartym ekranie zgody.
14. Cofnięcie zaufania w trakcie oczekującego żądania.
15. Drugie wywołanie operacji zmieniającej stan w trakcie pierwszej, z interfejsu i z agenta HTTP, daje `busy` z aktualną migawką.
16. `grant_permission` z uprawnieniem spoza listy, dla innego pakietu albo bez deklaracji w manifeście; `write_settings` bez deklaracji; `set_home` przy zerowej albo wielokrotnej liczbie kandydatów.
17. `install_apk` z plikiem innego pakietu, z obcym podpisem, z niższym `versionCode`, bez grantu do adresu, ze strumienia, który się blokuje albo przekracza limit.
18. Śmierć procesu sc2t i śmierć procesu Heliosa na każdym etapie długiej operacji oraz odcięcie zasilania w trakcie łańcucha: po ponownym starcie migawka mówi `chain: unknown`, ponowienie wymaga potwierdzenia, a `mic_saved_state` przeżywa restart.
19. Timeout przy żyjącym wykonawcy daje `in_progress`, a nie `failed`, i nie zwalnia blokady.
20. Kontrakt z nieznanym `api` daje `unsupported_api`, nieznana operacja daje `unsupported`; `detail` w żadnym z tych przypadków nie zawiera tokenu ani ścieżki prywatnej.

## 10. Kolejność wdrożenia

1. sc2t: blokada obejmująca wykonawcę, rejestr żądań, poprawka ścieżki kanału, ustrukturyzowany stan, potwierdzanie ADB przez połączenie, przełącznik ADB bez interfejsu. Same porządki, bez mostka.
2. sc2t: `BridgeActivity` z tożsamością, zaufaniem, zgodami i operacjami `state`, `root_adb_on`, `adb_on`, `adb_off`.
3. Helios: wykrywanie z odciskiem sc2t, menu, klient mostka, obsługa wyników i migawek. Do tego miejsca całość ma sens użytkowy.
4. sc2t: `grant_permission`, `write_settings`, `mic_release`, `mic_restore` z zapisem stanu, `set_home`.
5. Helios: korzystanie z nadanych uprawnień i uogólniony instalator sc2t.
6. sc2t: `install_apk` przez sesję instalatora, Helios: cicha aktualizacja własna.
7. Dokumentacja po obu stronach, wydania, weryfikacja na zegarze od czystego stanu po odcięciu zasilania.

## 11. Ryzyka

| ryzyko | skutek | co z tym robimy |
| --- | --- | --- |
| exploit zawiesza jądro | zegar wymaga odcięcia prądu | tylko ręczne uruchomienie, brak samoczynnych ponowień, `chain: unknown` po przerwanej próbie |
| inne firmware | łańcuch nie działa albo szkodzi | twarde sprawdzenie `ro.build.display.id` przed czymkolwiek |
| złośliwa aplikacja woła mostek | cudze uprawnienia, ADB w sieci | kontrola odcisku podpisu, zgoda człowieka, wąskie operacje, ochrona przed nakładkami |
| włączone ADB omija mostek | model zgód przestaje obowiązywać | powiedziane wprost na ekranie zgody, `adb_off` zawsze dostępne, menu pokazuje fakt nasłuchu |
| użytkownik traci "Hey Google" | mniej funkcji fabrycznych | `mic_restore` z zapisanym stanem, informacja na ekranie zgody |
| operacja przerwana w połowie | nieznany stan systemu | rejestr etapów, stany `in_progress` i `unknown`, ustalanie skutku przez `state` |
| rozjazd wersji dwóch aplikacji | operacja nie istnieje albo znaczy co innego | `api` w każdym wywołaniu, próg `versionCode`, `unsupported` jako normalna odpowiedź |
| root znika po odcięciu zasilania | funkcje przestają działać w środku nocy | uprawnienia nadane raz zostają na stałe; roota wymaga tylko ich nadanie, nie używanie |
| Helios jako ekran główny przestaje się uruchamiać | zegar bez żadnego interfejsu | ekran zgody `set_home` zaleca zostawienie zapasowego launchera; przywrócenie poprzedniego opisuje `detail` |
