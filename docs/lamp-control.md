# Lampka docka Lenovo Smart Clock 2 — nota techniczna dla Heliosa

Status: wiedza techniczna przeniesiona 2026-09-15 z [dokumentu źródłowego repozytorium nadrzędnego](../../docs/lamp-control.md). Opisane testy wykonano wcześniej na fizycznym urządzeniu z OTA 627; nie zostały powtórzone podczas przygotowania tej dokumentacji.

## Potwierdzona możliwość sprzętowa

Lampką LED w docku można sterować z nieuprzywilejowanej aplikacji Android bez roota i bez dodatkowych uprawnień. Pośrednikiem jest eksportowany serwis OEM pakietu `com.google.assistant.oemapp`, działający z uprawnieniami systemowymi:

```text
action:    com.google.assistant.START_OEM_ACCESSORY_SERVICE
component: com.google.assistant.oemapp/
           com.google.assistant.oemapp.ScoriaAssistantOemAccessoryService
descriptor: com.google.assistant.IAssistantOemAccessoryService
```

Aplikacja wiąże usługę przez jawny `Intent` i `bindService(..., BIND_AUTO_CREATE)`. Według testów źródłowych serwis ma `exported="true"`, nie wymaga `android:permission`, a wywołania z domeny `untrusted_app` nie są odrzucane przez SELinux/binder.

## Dostępne transakcje bindera

| Kod | Operacja | Dane | Zachowanie potwierdzone w źródle |
| --- | --- | --- | --- |
| `2` | `registerConnectionListener` | binder listenera | Zdarzenia podłączenia i odłączenia docka. |
| `3` | `turnOnLed` | brak | Włącza lampkę na zapamiętanym poziomie; fabryczna wartość domyślna to 7/10. |
| `4` | `turnOffLed` | brak | Wyłącza lampkę. |
| `5` | `setLedBrightness` | liczba całkowita 1–10 | Ustawia jasność lampki. |
| `6` | `isLedOn` | brak | Zwraca stan włączenia lampki. |
| `7` | `registerChargerStatusListener` | binder listenera | Zdarzenia rozpoczęcia i zakończenia ładowania. |

`isLedOn() == false` nie dowodzi braku docka: taki sam wynik występuje, gdy dock jest podłączony, ale lampka jest wyłączona. Wykrywanie obecności docka wymaga osobnej integracji `registerConnectionListener`; nie wolno zastępować jej samym `isLedOn()`.

## Istotne szczegóły implementacyjne

- Deskryptor interfejsu przekazywany w Parcel to `com.google.assistant.IAssistantOemAccessoryService`.
- Transakcje `3`, `4`, `5` i `6` używają zwykłego synchronicznego `transact`; dla jasności należy wcześniej zweryfikować i ograniczyć wartość do 1–10.
- Połączenie z usługą jest asynchroniczne. Akcje można udostępnić dopiero po `onServiceConnected`; po rozłączeniu binder trzeba uznać za nieważny.
- Transakcja `INTERFACE_TRANSACTION` (`0x5f4e5446`) ma wadliwą odpowiedź OEM: zwraca sam string bez nagłówka wyjątku. Należy odczytać `readString()` bez `readException()`. Zwykłe transakcje metod nadal wymagają `reply.readException()`.
- Komendy started-service `1018`/`1019` wykonują jedynie telemetrię, nie sterowanie lampką. Faktyczne sterowanie wymaga bindera.
- Serwis OEM startuje po bootowaniu i może zostać uruchomiony przez `BIND_AUTO_CREATE`, ale Helios zależy od obecności fabrycznego pakietu OEM i zgodności jego prywatnego interfejsu.
- Nie wolno uruchamiać ścieżki `wirelessUpdate()` ani prób aktualizacji firmware docka.

Minimalny kształt wywołania:

```java
private static final String DESCRIPTOR =
        "com.google.assistant.IAssistantOemAccessoryService";

static void turnOnLed(IBinder binder) throws RemoteException {
    Parcel data = Parcel.obtain();
    Parcel reply = Parcel.obtain();
    try {
        data.writeInterfaceToken(DESCRIPTOR);
        binder.transact(3, data, reply, 0);
        reply.readException();
    } finally {
        data.recycle();
        reply.recycle();
    }
}
```

## Ograniczenia platformy

Testy źródłowe wykazały, że nieuprzywilejowana aplikacja nie może ominąć aplikacji OEM:

- `Context.getSystemService("charge_base")` zwraca `null`;
- `ServiceManager.getService("charge_base")` zwraca `null` z domeny `untrusted_app`;
- bezpośredni dostęp do `/sys/class/leds`, `/sys/bus/usb` i `/dev/ttyACM*` jest zablokowany.

Fabryczny stos prowadzi przez usługę OEM, ukrytą usługę systemową `charge_base` i USB CDC do mikrokontrolera WCH CH554 w docku. Helios powinien korzystać wyłącznie z eksportowanego bindera OEM.

## Materiały i wcześniejsze testy

- [LampProbe.java](../../tools/lamp-probe/LampProbe.java) i `LampProbe.dex` — wcześniejsza sonda `state`, `on`, `off`, `bri:N`.
- [DirectCbProbe.java](../../tools/lamp-probe/DirectCbProbe.java) i `DirectCbProbe.dex` — dowód niedostępności bezpośredniej usługi `charge_base`.
- [run_clock_plugin.py](../tools/run_clock_plugin.py) — uruchamianie sond przez agenta zegara.
- Lokalne, niewersjonowane źródła OTA: `I:\Projekty\lenovo_clock\.local\ota-627\`.

Według raportu źródłowego na OTA 627 potwierdzono kolejno: połączenie z usługą i `isLedOn=false`, włączenie i `isLedOn=true`, ustawienie jasności 3/10 oraz wyłączenie i `isLedOn=false`.

## Relacja z dashboardem i Home Assistant

Sterowanie lampką jest lokalną możliwością sprzętową Heliosa. Nie oznacza automatycznie, że lampka jest encją Home Assistant ani że została zaakceptowana jako siódmy typ elementu pierwszego etapu dashboardu 0.5.

Możliwe kierunki przyszłej integracji, wymagające osobnej decyzji:

- lokalny element dashboardu lub pozycja stałego menu, sterujące binderem bez HA;
- most Helios–HA eksponujący lampkę jako encję, wraz z ustaleniem źródła prawdy i synchronizacji;
- wykorzystanie listenera docka do lokalnego statusu lub automatyzacji.

Przed implementacją należy ponownie sprawdzić bind, wszystkie używane transakcje i listener na docelowym urządzeniu/firmware oraz bezpiecznie obsłużyć brak pakietu OEM, brak docka i rozłączenie bindera.

## Detekcja ładowania telefonu na padzie

Dokument źródłowy został uzupełniony 2026-09-15 o wynik testu na żywym urządzeniu: ten sam binder OEM przekazuje w czasie rzeczywistym zdarzenia rozpoczęcia i zakończenia ładowania bezprzewodowego telefonu w docku.

| Element | Wartość potwierdzona w źródle |
| --- | --- |
| Rejestracja | transakcja `7`, `registerChargerStatusListener`, argument: strong binder listenera |
| Deskryptor callbacku | `com.google.assistant.IChargerStatusListener` |
| Rozpoczęcie ładowania | callback `onChargeStart`, kod transakcji `2` |
| Zakończenie ładowania | callback `onChargeStop`, kod transakcji `3` |
| Charakter danych | wyłącznie przejścia stanu; binder nie udostępnia odczytu bieżącego stanu ładowania |

Z dekompilacji wynika, że aplikacja OEM odpytuje `getStatus()` mikrokontrolera co 500 ms, a `status[5] == 2` lub `status[5] == 3` oznacza ładowanie. Ta wewnętrzna interpretacja nie jest jednak bezpośrednio dostępna aplikacji Helios; Helios otrzymuje callbacki listenera.

W teście źródłowym telefon położono na padzie podczas 150-sekundowego nasłuchu. Zarejestrowano sekwencję `onChargeStop`, `onChargeStart`, `onChargeStop`, `onChargeStart`; krótkie przejścia odpowiadały handshake'owi pada, po którym ładowanie ustabilizowało się. Implementacja nie może więc traktować każdego pojedynczego callbacku jako trwałego stanu bez uwzględnienia kolejnych zdarzeń.

Najważniejsze ograniczenie: po rejestracji listenera nie ma snapshotu bieżącego stanu. Jeśli Helios zacznie nasłuch już podczas trwającego ładowania i nie nastąpi kolejne przejście, stan pozostaje nieznany. UI musi odróżniać `nieznany` od `nie ładuje`; nie wolno zgadywać stanu początkowego.

Materiały źródłowe:

- [ChargeProbe.java](../../tools/lamp-probe/ChargeProbe.java) i `ChargeProbe.dex` — sonda listenera; argument określa czas nasłuchu w sekundach, domyślnie 30;
- standardowy [run_clock_plugin.py](../tools/run_clock_plugin.py) ma timeout klienta HTTP 45 s, dlatego dłuższe testy źródłowe używały niewersjonowanego `.local/lamp-test/run_long_charge_probe.py` z własnym timeoutem.

Dokument źródłowy zawiera hipotezę, że fabryczny interfejs Casta może zmieniać lampkę po zdarzeniach ładowania, ponieważ rejestruje ten sam listener. Nie zostało to potwierdzone: podczas jednej z obserwacji lampka pozostawała wyłączona. Nie należy projektować zachowania Heliosa w oparciu o tę hipotezę.

Detekcja ładowania jest lokalną możliwością sprzętową, tak jak sterowanie lampką. Nie oznacza jeszcze zaakceptowanego elementu dashboardu ani encji HA. Jej ewentualne pokazanie w stałym pasku, dashboardzie lub Home Assistant wymaga osobnej decyzji o UX, źródle prawdy i zachowaniu dla nieznanego stanu początkowego.
