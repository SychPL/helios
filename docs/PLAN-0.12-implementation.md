# Plan wdrożenia SPEC 0.12 - mostek Helios ↔ Smart Clock 2 Tools

> **Dla wykonawcy:** zadania realizuje się po kolei, każde kończy się działającym, przetestowanym kawałkiem. Kroki mają pola wyboru, bo służą do odhaczania.

**Cel:** Helios prosi narzędzie sc2t o wąsko zdefiniowane operacje (stan, root i ADB, nadanie uprawnień, mikrofon, cicha aktualizacja), nie dostając roota ani powłoki.

**Architektura:** jawna intencja z wynikiem, zamknięta lista operacji, zaufanie po odcisku certyfikatu po obu stronach, trwały rejestr żądań z identyfikatorem nadanym przez wywołującego, czas monotoniczny z identyfikatorem uruchomienia systemu.

**Stos:** dwie aplikacje Android, Java 8, bez AndroidX. Helios: `I:\Projekty\lenovo_clock\dash`, pakiet `pl.mateusz.helios`, minSdk i targetSdk 29, testy JUnit 4 przez `./gradlew testDebugUnitTest`. sc2t: `I:\Projekty\lenovo_clock`, pakiet `pl.mateusz.clockadbprobe`, minSdk 24, targetSdk 27, compileSdk 34, testy JUnit 4 przez `./gradlew :app:testDebugUnitTest`.

**Specyfikacja:** [SPEC-0.12-tools-bridge.md](SPEC-0.12-tools-bridge.md). Plan realizuje ją punkt po punkcie i nie rozstrzyga niczego, czego ona nie mówi.

## Ograniczenia globalne

- Java 8, bez AndroidX, bez nowych zależności produkcyjnych w obu aplikacjach.
- **Jedyny dopuszczony wyjątek zależnościowy:** `testImplementation 'org.json:json:20240303'` w obu modułach. `org.json` jest częścią Androida, ale nie ma go na ścieżce testów JVM, a rejestr, migawka i magazyn zaufania są dokumentami JSON. Zależność jest wyłącznie testowa i nie trafia do żadnego pliku APK.
- **Nie ma `FileProvider`**, bo nie ma AndroidX. Helios dostaje własny `ContentProvider` (zadanie 12), który wydaje wyłącznie jeden plik aktualizacji i wyłącznie na czas trwania grantu.
- Wszystko, co da się wydzielić jako klasę bez `Context`, ma być taką klasą i mieć test JVM. Wzorzec z obu repozytoriów: ręczne atrapy, nigdy biblioteki do mockowania. Klasy androidowe (`Intent`, `Uri`, `PackageManager`) są **adapterami** nad czystym modelem i sprawdza się je na urządzeniu, nie w testach JVM, bo Helios ma `unitTests.returnDefaultValues = true` i takie obiekty zachowują się tam jak atrapy zwracające zera.
- Żadna praca blokująca nie idzie na wątek interfejsu.
- Teksty dla człowieka: w sc2t po angielsku, w Heliosie po polsku.
- Bez tokenów i ścieżek prywatnych w `detail`, w logu i w commitach. Filtr `detail` to osobny, testowany kawałek kodu (zadanie 6), a nie obietnica.
- Czas: `SystemClock.elapsedRealtime()` do limitów, `System.currentTimeMillis()` wyłącznie do `finished_at_ms`.
- Nazwy operacji, kody stanu i nazwy pól JSON są dokładnie te ze specyfikacji.
- Wersjonowanie wydań w obu projektach: tag `vX.Y.Z`, trzy człony. sc2t przechodzi więc z `2.18` na **`2.19.0`**, bo aktualizator Heliosa odrzuca tagi dwuczłonowe.

## Struktura plików

**sc2t** (`I:\Projekty\lenovo_clock\app\src\main\java\pl\mateusz\clockadbprobe\`):

| plik | odpowiedzialność |
| --- | --- |
| `bridge/OpRegistry.java` (nowy) | trwały rejestr żądań: klucz z pakietu, odcisku i identyfikatora, etapy, czasy monotoniczne, `boot_id`, domykanie po restarcie |
| `bridge/ExecutorLock.java` (nowy) | ślad uprzywilejowanego wykonawcy poza procesem: plik z pidem i `boot_id`, sprawdzanie żywotności, wyliczanie `chain` |
| `bridge/TrustStore.java` (nowy) | odciski i zgody, unieważnianie po zmianie odcisku, opis dla ekranu zarządzania |
| `bridge/BridgeRequest.java` (nowy) | walidacja żądania: akcja, `api`, `op`, `args`, `op_id`, flagi, użytkownik Androida |
| `bridge/Ops.java` (nowy) | tabela operacji: klasa ryzyka, warunki wstępne, limity, `requestDigest` i `consentScope` |
| `bridge/Snapshot.java` (nowy) | migawka stanu z budżetem czasu i wartościami `unknown` |
| `bridge/Detail.java` (nowy) | filtr tekstu dla człowieka |
| `bridge/MicState.java` (nowy) | trwała mapa stanów mikrofonu per pakiet |
| `bridge/BridgeFlow.java` (nowy) | decyzja: pytać o zaufanie, pytać o zgodę, wykonać, odpowiedzieć |
| `BridgeActivity.java` (nowy) | jedyne eksportowane wejście: ekrany, wykonanie, wynik |
| `BridgeExecutor.java` (nowy) | wykonanie operacji przez `RootKit` i kanał roota |
| `TrustActivity.java` (nowy) | ekran zarządzania zaufaniem i zgodami |
| `BridgeFiles.java` (nowy) | katalog `filesDir/bridge`, atomowy zapis, inicjalizacja raz na proces |
| `OperationGate.java` (zmiana) | blokada z właścicielem i etapem, bez reentrancji |
| `RootKit.java` (zmiana) | `adbListening()`, `adbOff()` potwierdzane faktem, `micHolders()`, poprawka ścieżki kanału |
| `AgentRuntime.java` (zmiana) | wyzwalacze ADB bez interfejsu, `rootssh` pod blokadą |
| `assets/rootkit/bootstrap.sh`, `hell.sh` (zmiana) | ścieżka binarki kanału |

**Helios** (`I:\Projekty\lenovo_clock\dash\app\src\main\java\pl\mateusz\helios\`):

| plik | odpowiedzialność |
| --- | --- |
| `ToolsCall.java` (nowy) | czysty model żądania i odpowiedzi: `op_id`, reguły ponawiania, wiek etapu |
| `ToolsBridge.java` (nowy) | adapter androidowy: budowa intencji, odbiór wyniku |
| `ToolsTrust.java` (nowy) | odcisk sc2t, akceptacja, sprawdzenie przed każdym wywołaniem, próg wersji |
| `ToolsState.java` (nowy) | migawka plus pomiary lokalne |
| `ToolsMenu.java` (nowy) | pozycje menu z warunków |
| `ApkProvider.java` (nowy) | własny `ContentProvider` wydający jeden plik aktualizacji |
| `MainActivity.java`, `NavigationMenu.java` (zmiana) | uruchamianie, odbiór, odpytywanie, menu |
| `Updater.java`, `ReleaseInfo.java` (zmiana) | źródło wydania jako parametr, wersja pakietu docelowego, wybór ścieżki instalacji |
| `AndroidManifest.xml` (zmiana) | `WRITE_SETTINGS`, `ApkProvider` |

---

## Zadanie 1: przywrócić kompilację testów sc2t

Trzy pliki testowe odwołują się do klas usuniętych razem z fuzzerem, więc dziś `:app:testDebugUnitTest` nie kompiluje się wcale.

**Pliki:** usuń `app/src/test/java/pl/mateusz/clockadbprobe/{ArtifactArchiveTest,FuzzProtocolTest,ControlledFuzzCommandTest}.java`; zmień `.github/workflows/build-apk.yml`; zmień `app/build.gradle`.

- [ ] **Krok 1: zobacz błąd**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest
```
Oczekiwane: `cannot find symbol class ArtifactArchive`.

- [ ] **Krok 2: usuń osierocone testy**

```bash
cd I:/Projekty/lenovo_clock && git rm app/src/test/java/pl/mateusz/clockadbprobe/ArtifactArchiveTest.java app/src/test/java/pl/mateusz/clockadbprobe/FuzzProtocolTest.java app/src/test/java/pl/mateusz/clockadbprobe/ControlledFuzzCommandTest.java
```

- [ ] **Krok 3: dodaj testową zależność JSON**

W `app/build.gradle`, w bloku `dependencies`:

```gradle
    testImplementation 'org.json:json:20240303'   // Android ma org.json w runtime, testy JVM nie mają
```

- [ ] **Krok 4: testy przechodzą**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest
```

- [ ] **Krok 5: CI uruchamia testy**

```yaml
      - name: Build and test
        run: ./gradlew :app:testDebugUnitTest assembleDebug
```

- [ ] **Krok 6: commit**

```bash
git add -A && git commit -m "test: drop the three tests orphaned by the fuzzer removal, add org.json for JVM tests, run the suite in CI"
```

---

## Zadanie 2: blokada z właścicielem, bez reentrancji

Dziś `OperationGate` to globalny boolean: każdy może zwolnić cudzą operację, nie ma etapu ani właściciela. Wykluczanie musi zostać dokładnie takie, jakie jest (drugie wejście to odmowa), bo opiera się na nim dwadzieścia miejsc w aplikacji.

**Pliki:** zmień `OperationGate.java`; zmień `OperationGateTest.java`.

**Interfejsy:**
- Produkuje: `boolean acquire(String ownerId, String stage)` (false, gdy zajęta, **także dla tego samego właściciela**), `void stage(String ownerId, String stage)`, `void release(String ownerId)`, `String ownerId()`, `String stageOf()`, `boolean isBusy()`, oraz zgodne opakowania `tryStartProbe()`/`finishProbe()`.

- [ ] **Krok 1: testy, w tym zachowanie starego kontraktu**

```java
@Test public void oneOperationAtATime() {                     // istniejący test zostaje bez zmian
    assertTrue(OperationGate.tryStartProbe());
    assertFalse(OperationGate.tryStartProbe());
    OperationGate.finishProbe();
    assertTrue(OperationGate.tryStartProbe());
    OperationGate.finishProbe();
}

@Test public void aLockBelongsToItsOwnerAndCarriesAStage() {
    assertTrue(OperationGate.acquire("op-1", "running"));
    assertFalse("a busy lock refuses everyone, its owner included", OperationGate.acquire("op-1", "running"));
    assertFalse(OperationGate.acquire("op-2", "running"));
    assertEquals("running", OperationGate.stageOf());
    OperationGate.stage("op-1", "installing");
    assertEquals("installing", OperationGate.stageOf());
    OperationGate.stage("op-2", "copying");
    assertEquals("a foreign stage change is ignored", "installing", OperationGate.stageOf());
    OperationGate.release("op-2");
    assertTrue("a foreign release does not free the lock", OperationGate.isBusy());
    OperationGate.release("op-1");
    assertFalse(OperationGate.isBusy());
    assertNull(OperationGate.ownerId());
}
```

- [ ] **Krok 2: czerwone** - `./gradlew :app:testDebugUnitTest --tests '*OperationGateTest*'`
- [ ] **Krok 3: implementacja**

```java
static synchronized boolean acquire(String ownerId, String stage) {
    if (owner != null) return false;                 // wykluczanie jak dotąd, bez reentrancji
    owner = ownerId; OperationGate.stage = stage; return true;
}
static synchronized void stage(String ownerId, String stage) {
    if (ownerId != null && ownerId.equals(owner)) OperationGate.stage = stage;
}
static synchronized void release(String ownerId) {
    if (ownerId != null && ownerId.equals(owner)) { owner = null; stage = ""; }
}
static boolean tryStartProbe() { return acquire("probe", "running"); }
static void finishProbe() { release("probe"); }
```

- [ ] **Krok 4: zielone i bez regresji** - `./gradlew :app:testDebugUnitTest assembleDebug`
- [ ] **Krok 5: commit** - `feat(gate): the execution lock gets an owner and a stage while keeping strict exclusion`

---

## Zadanie 3: ślad wykonawcy poza procesem

Blokada w pamięci znika razem z procesem, a uprzywilejowany wykonawca nie. To jest mechanizm, z którego bierze się `chain: unknown` i warunki powrotu do `idle`.

**Pliki:** utwórz `bridge/ExecutorLock.java`, `bridge/BridgeFiles.java`; testy `bridge/ExecutorLockTest.java`. Zmień `AgentRuntime.java` (wyzwalacz `rootssh` bierze blokadę i zakłada ślad).

**Interfejsy:**
- Konsumuje: `ExecutorLock.Files` (`String read(String name)`, `void write(String name, String text)`, `void delete(String name)`), `ExecutorLock.Processes` (`boolean alive(int pid)`), `ExecutorLock.Clock` (`String bootId()`).
- Produkuje: `void claim(String opId, int pid)`, `void done(String opId)`, `String chain()` zwracające `idle`, `running` albo `unknown`, `boolean mayStartNew()`.

- [ ] **Krok 1: testy**

```java
@Test public void aLiveExecutorMeansRunning() {
    ExecutorLock l = lock(alive(1234), boot("b1"));
    l.claim("op1", 1234);
    assertEquals("running", l.chain());
    assertFalse(l.mayStartNew());
}

@Test public void aDeadExecutorFromThisBootMeansIdle() {
    ExecutorLock l = lock(dead(), boot("b1"));
    l.claim("op1", 1234);
    assertEquals("a process that is gone did finish, whatever it did", "idle", newLock(dead(), boot("b1")).chain());
}

@Test public void anExecutorFromAnotherBootIsGoneByDefinition() {
    lock(alive(1234), boot("b1")).claim("op1", 1234);
    assertEquals("idle", newLock(alive(1234), boot("b2")).chain());
}

@Test public void anUnreadableTraceIsUnknownNotIdle() {
    ExecutorLock l = lock(unknownLiveness(), boot("b1"));
    l.claim("op1", 1234);
    assertEquals("unknown", newLock(unknownLiveness(), boot("b1")).chain());
    assertFalse("unknown never lets a new operation start", newLock(unknownLiveness(), boot("b1")).mayStartNew());
}

@Test public void doneClearsTheTrace() {
    ExecutorLock l = lock(alive(1234), boot("b1"));
    l.claim("op1", 1234); l.done("op1");
    assertEquals("idle", l.chain());
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - ślad to plik `filesDir/bridge/executor.json` z `op_id`, `pid` i `boot_id`. Żywotność procesu: istnienie `/proc/<pid>`; brak dostępu do `/proc` to `unknown`, nigdy `idle`.
- [ ] **Krok 4: `rootssh` pod blokadą** - w `AgentRuntime.trigger` ścieżka `rootssh` bierze `OperationGate.acquire` i zakłada ślad, tak samo jak mostek. Dziś nie bierze żadnej blokady, więc dwa wywołania z sieci potrafią uruchomić dwa przebiegi exploita naraz (SPEC pkt 8.4).
- [ ] **Krok 5: test regresji wyzwalacza**

```java
@Test public void twoAgentTriggersDoNotStartTwoChains() {
    assertTrue(AgentRuntime.trigger(ctx, "rootssh"));
    assertFalse("the second one must be refused while the first runs", AgentRuntime.trigger(ctx, "rootssh"));
}
```

- [ ] **Krok 6: zielone i commit** - `feat(bridge): out-of-process executor trace so chain state survives a dead app, and the agent rootssh trigger takes the lock`

---

## Zadanie 4: trwały rejestr żądań

**Pliki:** utwórz `bridge/OpRegistry.java`, test `bridge/OpRegistryTest.java`.

**Interfejsy:**
- Konsumuje: `OpRegistry.Store`, `OpRegistry.Clock` (`long uptimeMs()`, `long wallMs()`, `String bootId()`).
- Produkuje: `Entry accept(Key key, String op, String requestDigest)`, `boolean stage(Key key, String stage)`, `boolean finish(Key key, String status)`, `Entry about(Key key)`, `void recoverOnce()`, `String previousBootId()`. `Key` to niezmienna trójka: pakiet, odcisk, `op_id`.

- [ ] **Krok 1: testy**

```java
@Test public void anEntryBelongsToTheWholeKeyNotJustTheId() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("pl.mateusz.helios", "AA", "op1"), "state", "d1");
    assertEquals("accepted", r.about(key("pl.mateusz.helios", "AA", "op1")).stage);
    assertEquals("absent", r.about(key("pl.evil", "AA", "op1")).stage);
    assertEquals("absent", r.about(key("pl.mateusz.helios", "BB", "op1")).stage);
}

@Test public void anotherAppCannotOverwriteAnExistingId() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("pl.mateusz.helios", "AA", "op1"), "root_adb_on", "d1");
    r.stage(key("pl.mateusz.helios", "AA", "op1"), "running");
    assertNull("a foreign key must not be accepted under a taken id", r.accept(key("pl.evil", "CC", "op1"), "state", "d2"));
    assertEquals("running", r.about(key("pl.mateusz.helios", "AA", "op1")).stage);
}

@Test public void reacceptingTheSameIdDoesNotResetTheEntry() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("p", "AA", "op1"), "adb_off", "d1");
    r.stage(key("p", "AA", "op1"), "running");
    assertNull("a duplicate is answered, never re-accepted", r.accept(key("p", "AA", "op1"), "adb_off", "d1"));
    assertEquals("running", r.about(key("p", "AA", "op1")).stage);
}

@Test public void aForeignKeyCannotMoveOrFinishAnEntry() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("p", "AA", "op1"), "adb_off", "d1");
    assertFalse(r.stage(key("p", "BB", "op1"), "running"));
    assertFalse(r.finish(key("pl.evil", "AA", "op1"), "ok"));
    assertEquals("accepted", r.about(key("p", "AA", "op1")).stage);
}

@Test public void stageDecidesHowAnAbandonedEntryCloses() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("p", "AA", "waiting"), "root_adb_on", "d");
    r.stage(key("p", "AA", "waiting"), "awaiting_consent");
    r.accept(key("p", "AA", "working"), "root_adb_on", "d");
    r.stage(key("p", "AA", "working"), "running");
    OpRegistry afterRestart = new OpRegistry(store, clock);
    afterRestart.recoverOnce();
    assertEquals("finished", afterRestart.about(key("p", "AA", "waiting")).stage);
    assertEquals("denied", afterRestart.about(key("p", "AA", "waiting")).status);
    assertEquals("interrupted", afterRestart.about(key("p", "AA", "working")).stage);
    assertEquals("unknown", afterRestart.about(key("p", "AA", "working")).status);
}

@Test public void recoveryRunsOnceAndNeverClosesAnEntryOfThisProcess() {
    OpRegistry r = new OpRegistry(store, clock);
    r.recoverOnce();
    r.accept(key("p", "AA", "op1"), "root_adb_on", "d");
    r.stage(key("p", "AA", "op1"), "running");
    r.recoverOnce();
    assertEquals("a second call must not touch a live operation", "running", r.about(key("p", "AA", "op1")).stage);
}

@Test public void thePreviousBootIdSurvivesRecovery() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("p", "AA", "op1"), "root_adb_on", "d");
    r.stage(key("p", "AA", "op1"), "running");
    clock.boot("boot-b");
    OpRegistry after = new OpRegistry(store, clock);
    after.recoverOnce();
    assertEquals("boot-a", after.previousBootId());
    assertEquals("interrupted", after.about(key("p", "AA", "op1")).stage);
}

@Test public void timesAreMonotonicAndCarryTheBootId() {
    OpRegistry r = new OpRegistry(store, clock);
    r.accept(key("p", "AA", "op1"), "root_adb_on", "d");
    clock.advance(5000);
    r.stage(key("p", "AA", "op1"), "running");
    clock.advance(2000);
    OpRegistry.Entry e = r.about(key("p", "AA", "op1"));
    assertEquals(1000, e.startedAtUptimeMs);
    assertEquals(6000, e.stageSinceUptimeMs);
    assertEquals("boot-a", e.bootId);
    assertEquals(0, e.finishedAtMs);
    r.finish(key("p", "AA", "op1"), "ok");
    assertTrue(r.about(key("p", "AA", "op1")).finishedAtMs > 0);
}
```

Atrapy `FakeStore` i `FakeClock` w tym samym pliku, w stylu `ExecUtilTest`.

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - `accept` zwraca `null`, gdy identyfikator jest zajęty (przez kogokolwiek); `stage` i `finish` zwracają `false` dla obcego klucza; `recoverOnce()` wykonuje się raz na instancję i domyka wyłącznie wpisy zastane przy wczytaniu, nigdy przyjęte później; `previousBootId()` zapamiętuje poprzedni identyfikator uruchomienia przed nadpisaniem.
- [ ] **Krok 4: jedna instancja na proces** - `BridgeFiles.registry()` zwraca singleton i woła `recoverOnce()` przy pierwszym użyciu. Aktywności i serwis biorą rejestr wyłącznie stamtąd; otwarcie aktywności nie jest restartem procesu i nie domyka niczego.
- [ ] **Krok 5: zielone i commit** - `feat(bridge): persistent request registry keyed by caller, signature and id, with one-shot recovery`

---

## Zadanie 5: zaufanie i zgody po stronie sc2t

**Pliki:** utwórz `bridge/TrustStore.java`, `TrustActivity.java`; testy `bridge/TrustStoreTest.java`.

**Interfejsy:** `boolean trusted(String pkg, String fp)`, `void trust(String pkg, String fp)`, `boolean consented(String pkg, String fp, String op, String scope)`, `void consent(...)`, `void revokeAll(String pkg)`, `void revokeOne(String pkg, String op, String scope)`, `List<String> describe()`, `long revision()`.

- [ ] **Krok 1: testy**

```java
@Test public void aChangedSignatureInheritsNothing() {
    TrustStore t = new TrustStore(store);
    t.trust("p", "AA"); t.consent("p", "AA", "grant_permission", "android.permission.RECORD_AUDIO");
    assertFalse(t.trusted("p", "BB"));
    t.trust("p", "BB");
    assertFalse(t.consented("p", "BB", "grant_permission", "android.permission.RECORD_AUDIO"));
}

@Test public void consentIsBoundToTheScope() {
    TrustStore t = trusting();
    t.consent("p", "AA", "grant_permission", "android.permission.RECORD_AUDIO");
    assertFalse("a permission added to the list later is not covered",
                t.consented("p", "AA", "grant_permission", "android.permission.CAMERA"));
    assertFalse("another operation is not covered", t.consented("p", "AA", "set_home", ""));
}

@Test public void revokingBumpsTheRevisionSoPendingRequestsCanBeInvalidated() {
    TrustStore t = trusting();
    long before = t.revision();
    t.revokeOne("p", "adb_off", "");
    assertTrue(t.revision() > before);
}

@Test public void theStoreSurvivesAReload() {
    trusting().consent("p", "AA", "adb_off", "");
    assertTrue(new TrustStore(store).consented("p", "AA", "adb_off", ""));
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - jeden dokument JSON, mapa pakiet → `{fingerprint, consents:[{op, scope}], revision}`.
- [ ] **Krok 4: `TrustActivity`** - lista zaufanych aplikacji i ich zgód, przycisk cofnięcia pojedynczej zgody i całego zaufania, wejście z `MainActivity`. Cofnięcie podnosi `revision()`, co unieważnia oczekujące żądania (zadanie 8).
- [ ] **Krok 5: zielone i commit** - `feat(bridge): trust and consent store with a revision, plus a screen to review and revoke`

---

## Zadanie 6: walidacja żądania, tabela operacji i filtr tekstu

**Pliki:** utwórz `bridge/BridgeRequest.java`, `bridge/Ops.java`, `bridge/Detail.java`; testy `bridge/BridgeRequestTest.java`, `bridge/OpsTest.java`, `bridge/DetailTest.java`.

**Interfejsy:**
- `BridgeRequest.parse(Input in)` gdzie `Input` to czysty rekord: `action`, `hasApi`, `api`, `op`, `args`, `opId`, `flags`, `hasCaller`, `forwardResult`, `viaNewIntent`, `userId`, `hasUri`. Adapter androidowy wypełnia go z `Intent` i sam sprawdza typy pól `Bundle`, bo `getIntExtra` na polu tekstowym cicho zwraca wartość domyślną.
- `Ops.risk(op)`, `Ops.machineLimitMs(op, stage)`, `Ops.needsFile(op)`, `Ops.requestDigest(op, argsJson, fileSha)`, `Ops.consentScope(op, argsJson)`.
- `Detail.clean(String)`.

- [ ] **Krok 1: testy walidacji**

```java
@Test public void onlyTheDocumentedShapeIsAccepted() {
    assertNull(parse(ok()).error);
    assertNull("empty args are legal", parse(ok().args("")).error);
    assertEquals("unsupported", parse(ok().action("android.intent.action.VIEW")).error);
    assertEquals("unsupported_api", parse(ok().api(2)).error);
    assertEquals("unsupported", parse(ok().noApi()).error);
    assertEquals("unsupported", parse(ok().op("rm -rf")).error);
    assertEquals("unsupported", parse(ok().args("{nope")).error);
    assertEquals("unsupported", parse(ok().opId("zazolc")).error);
    assertEquals("unsupported", parse(ok().opId("")).error);
    assertEquals("only user 0 is in scope", "unsupported", parse(ok().userId(10)).error);
}

@Test public void theCallerMustBeIdentifiableAndDirect() {
    assertEquals("denied", parse(ok().noCaller()).error);
    assertEquals("denied", parse(ok().forwardResult()).error);
    assertEquals("denied", parse(ok().viaNewIntent()).error);
    assertEquals("denied", parse(ok().flags(FLAG_ACTIVITY_NEW_TASK)).error);
    assertEquals("denied", parse(ok().flags(FLAG_ACTIVITY_SINGLE_TOP)).error);
    assertEquals("denied", parse(ok().flags(FLAG_ACTIVITY_CLEAR_TOP)).error);
}

@Test public void aFileGrantIsAllowedOnlyForAnOperationThatTakesAFile() {
    assertNull(parse(ok().op("install_apk").flags(FLAG_GRANT_READ_URI_PERMISSION).withUri()).error);
    assertEquals("unsupported", parse(ok().op("state").flags(FLAG_GRANT_READ_URI_PERMISSION).withUri()).error);
    assertEquals("install_apk without a file is nothing", "unsupported", parse(ok().op("install_apk")).error);
}
```

- [ ] **Krok 2: testy tabeli operacji**

```java
@Test public void requestIdentityCoversTheWholeRequestButScopeOnlyTheConsent() {
    assertEquals(Ops.requestDigest("install_apk", "", "sha-1"), Ops.requestDigest("install_apk", "", "sha-1"));
    assertNotEquals("a different file is a different request",
                    Ops.requestDigest("install_apk", "", "sha-1"), Ops.requestDigest("install_apk", "", "sha-2"));
    assertEquals("but consent for install_apk is asked every time anyway", "", Ops.consentScope("install_apk", ""));
    assertEquals("android.permission.RECORD_AUDIO",
                 Ops.consentScope("grant_permission", "{\"permission\":\"android.permission.RECORD_AUDIO\"}"));
    assertEquals("whitespace is not content",
                 Ops.requestDigest("grant_permission", "{\"permission\":\"android.permission.RECORD_AUDIO\"}", null),
                 Ops.requestDigest("grant_permission", "{ \"permission\" : \"android.permission.RECORD_AUDIO\" }", null));
}

@Test public void everyOperationHasARiskClassAndMachineLimits() {
    assertEquals("high", Ops.risk("root_adb_on"));
    assertEquals("high", Ops.risk("install_apk"));
    assertEquals("read", Ops.risk("state"));
    assertEquals("normal", Ops.risk("adb_off"));
    assertEquals(3000, Ops.machineLimitMs("state", "running"));
    assertEquals(45000, Ops.machineLimitMs("adb_off", "running"));
    assertEquals(240000, Ops.machineLimitMs("root_adb_on", "running"));
    assertEquals(60000, Ops.machineLimitMs("install_apk", "copying"));
    assertEquals(120000, Ops.machineLimitMs("install_apk", "installing"));
    assertEquals("waiting for a human has no limit", 0, Ops.machineLimitMs("install_apk", "awaiting_consent"));
}
```

- [ ] **Krok 3: testy filtra**

```java
@Test public void detailNeverCarriesSecretsOrPrivatePaths() {
    assertFalse(Detail.clean("token=eyJhbGciOiJIUzI1NiJ9.abc.def").contains("eyJ"));
    assertFalse(Detail.clean("failed at /data/user/0/pl.mateusz.helios/files/x").contains("/data/"));
    assertFalse(Detail.clean("key 0123456789abcdef0123456789abcdef0123").contains("0123456789abcdef"));
    assertTrue(Detail.clean("chain failed: mode 3 write FAILED").startsWith("chain failed"));
    assertTrue("one sentence, nothing more", Detail.clean(veryLongLog()).length() <= 160);
}
```

- [ ] **Krok 4: implementacja i zielone**
- [ ] **Krok 5: commit** - `feat(bridge): request validation, operation table and a detail filter that strips tokens and private paths`

---

## Zadanie 7: migawka stanu

**Pliki:** utwórz `bridge/Snapshot.java`, test `bridge/SnapshotTest.java`; zmień `RootKit.java` (dodaj `adbListening()`, `micHolders()`).

**Interfejsy:** `Snapshot.Probe` z metodami zwracającymi `Boolean`/`String`/`List<String>` albo `null` = nie zmierzono; `String json(long budgetMs)`.

- [ ] **Krok 1: testy**

```java
@Test public void unmeasuredFieldsAreUnknownNotDefaults() {
    JSONObject j = new JSONObject(new Snapshot(failingProbe(), clock, pool).json(3000));
    assertEquals("unknown", j.getString("root"));
    assertEquals("unknown", j.getString("mic_holders"));
}

@Test public void micHoldersAreUnknownWithoutRoot() {
    JSONObject j = new JSONObject(new Snapshot(probeWithoutRoot(), clock, pool).json(3000));
    assertFalse(j.getBoolean("root"));
    assertEquals("a list built without privileges would lie", "unknown", j.getString("mic_holders"));
}

@Test public void adbPropertyAndAdbListeningAreSeparate() {
    JSONObject j = new JSONObject(new Snapshot(portSetButSilent(), clock, pool).json(3000));
    assertEquals("5555", j.getString("adb_property"));
    assertFalse(j.getBoolean("adb_listening"));
}

@Test public void aSlowProbeIsCutOffByTheBudget() {
    CountingProbe slow = probeThatBlocks(10_000);            // realny wątek, realne blokowanie
    long started = System.nanoTime();
    JSONObject j = new JSONObject(new Snapshot(slow, clock, pool).json(500));
    long tookMs = (System.nanoTime() - started) / 1_000_000;
    assertTrue("the snapshot must return within its budget, took " + tookMs, tookMs < 2000);
    assertEquals("unknown", j.getString("root"));
}

@Test public void theAboutBlockAppearsOnlyWhenAsked() {
    assertFalse(new JSONObject(snapshot().json(3000)).has("about"));
    assertTrue(new JSONObject(snapshotAbout("op1").json(3000)).has("about"));
}
```

Test budżetu używa prawdziwego zegara i prawdziwego wątku, bo zegar atrapa przepuściłby blokującą sondę.

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - pomiary na puli wątków, każdy z `Future`, całość ograniczona budżetem; `adb_listening` to `new Socket().connect(new InetSocketAddress("127.0.0.1", 5555), 300)`.
- [ ] **Krok 4: zielone i commit** - `feat(bridge): state snapshot with a time budget, unknown fields and ADB measured by connection`

---

## Zadanie 8: przepływ decyzji

**Pliki:** utwórz `bridge/BridgeFlow.java`, test `bridge/BridgeFlowTest.java`.

**Interfejsy:** `Decision decide(BridgeRequest r, Identity id, TrustStore t, OpRegistry reg, ExecutorLock lock, Facts facts)`; `Decision.kind` to `ASK_TRUST`, `ASK_CONSENT`, `RUN`, `ANSWER`, a `Decision.status` to kod ze specyfikacji. `Identity` niesie pakiet, odcisk i wersję wywołującego; `Facts` firmware, użytkownika i warunki wstępne operacji.

- [ ] **Krok 1: testy**

```java
@Test public void anUnknownCallerIsAskedForTrustBeforeAnythingElse() {
    assertEquals(ASK_TRUST, decide(request("state"), helios(), emptyTrust(), registry(), idle(), facts()).kind);
}

@Test public void aReadNeverAsksForConsent() {
    assertEquals(ANSWER, decide(request("state"), helios(), trusting(), registry(), idle(), facts()).kind);
}

@Test public void aHighRiskOperationAsksEveryTime() {
    TrustStore t = trusting(); t.consent("pl.mateusz.helios", "AA", "root_adb_on", "");
    assertEquals(ASK_CONSENT, decide(request("root_adb_on"), helios(), t, registry(), idle(), facts()).kind);
}

@Test public void aDuplicateAnswersInsteadOfRunningTwice() {
    OpRegistry reg = registry();
    reg.accept(key("pl.mateusz.helios", "AA", "op1"), "adb_off", digestOf("adb_off", ""));
    reg.stage(key("pl.mateusz.helios", "AA", "op1"), "running");
    Decision d = decide(request("adb_off", "op1"), helios(), consented(), reg, running(), facts());
    assertEquals(ANSWER, d.kind);
    assertEquals("in_progress", d.status);
}

@Test public void aFinishedDuplicateReplaysTheStoredResultWithoutRunning() {
    OpRegistry reg = registry();
    reg.accept(key("pl.mateusz.helios", "AA", "op1"), "adb_off", digestOf("adb_off", ""));
    reg.finish(key("pl.mateusz.helios", "AA", "op1"), "ok");
    Decision d = decide(request("adb_off", "op1"), helios(), consented(), reg, idle(), facts());
    assertEquals(ANSWER, d.kind);
    assertEquals("ok", d.status);
}

@Test public void theSameIdWithAnotherFileIsUnsupported() {
    OpRegistry reg = registry();
    reg.accept(key("pl.mateusz.helios", "AA", "op1"), "install_apk", digestOf("install_apk", "sha-1"));
    assertEquals("unsupported", decide(installRequest("op1", "sha-2"), helios(), consented(), reg, idle(), facts()).status);
}

@Test public void aNewRequestWhileAnotherRunsIsBusy() {
    assertEquals("busy", decide(request("adb_off", "op2"), helios(), consented(), registryWithRunning("op1"), running(), facts()).status);
}

@Test public void anUnknownChainBlocksNewWorkButNotTheOwnerOrReads() {
    assertEquals("busy", decide(request("adb_off", "op2"), helios(), consented(), registry(), unknownChain(), facts()).status);
    assertEquals(ANSWER, decide(request("state", "op3"), helios(), trusting(), registry(), unknownChain(), facts()).kind);
}

@Test public void anUnsupportedFirmwareRefusesTheChainWithoutRunningIt() {
    assertEquals("wrong_firmware", decide(request("root_adb_on"), helios(), consented(), registry(), idle(), foreignFirmware()).status);
}

@Test public void identityIsRecheckedAgainstTheRegisteredRequestBeforeRunning() {
    Decision d = decide(request("adb_off"), helios(), consented(), registry(), idle(), facts());
    assertEquals(RUN, d.kind);
    assertEquals("denied", d.recheck(heliosWithNewSignature()).status);
    assertEquals("denied", d.recheck(uninstalled()).status);
    assertEquals("denied", d.recheck(afterConsentRevoked()).status);
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - kolejność: kształt, tożsamość i użytkownik, `api`, firmware, duplikat, stan łańcucha, zgoda, wykonanie. `Decision.recheck` porównuje tożsamość i `TrustStore.revision()` tuż przed wykonaniem.
- [ ] **Krok 4: zielone i commit** - `feat(bridge): decision flow covering trust, consent, duplicates, busy chains and a recheck before execution`

---

## Zadanie 9: BridgeActivity, wykonanie, operacje stanu i ADB

Po tym zadaniu mostek działa: Helios może zapytać o stan i sterować ADB.

**Pliki:** utwórz `BridgeActivity.java`, `BridgeExecutor.java`; zmień `AndroidManifest.xml`, `RootKit.java`, `assets/rootkit/bootstrap.sh`, `assets/rootkit/hell.sh`, `AgentRuntime.java`.

Manifest, po `InstallActivity`:

```xml
<activity android:name=".BridgeActivity" android:exported="true" android:launchMode="standard"
          android:excludeFromRecents="true" android:theme="@style/AppTheme">
    <intent-filter>
        <action android:name="pl.mateusz.clockadbprobe.action.BRIDGE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

- [ ] **Krok 1: poprawka ścieżki kanału**

`bootstrap.sh:126,155,172` i `hell.sh:365-370` szukają binarki w `filesDir/clockroot`, a `RootKit.unpack` rozpakowuje ją do `filesDir/rootkit/clockroot`. Popraw ścieżki w obu skryptach na `rootkit/clockroot` i sprawdź na sprzęcie, że krok 7 łańcucha przestaje wchodzić w gałąź zapasową.

- [ ] **Krok 2: `adbOff` potwierdzane faktem**

```java
@Test public void adbOffIsJudgedByTheSocketNotByTheScriptText() {
    assertEquals("ok", RootKit.adbOffResult(scriptSaid("adbwifi: OFF"), portClosed()));
    assertEquals("a script that claims success while the port answers has not finished the job",
                 "failed", RootKit.adbOffResult(scriptSaid("adbwifi: OFF"), portOpen()));
    assertEquals("ok", RootKit.adbOffResult(scriptSaid("error"), portClosed()));
}
```

Implementacja: właściwość, restart demona, sprawdzenie portu, a gdy port nadal odpowiada, `stop adbd` i ponowne sprawdzenie. Który wariant wystarcza na tym firmware, rozstrzyga zadanie 15.

- [ ] **Krok 3: `BridgeActivity`** - ekrany zaufania, zgody i postępu; każdy przycisk `setFilterTouchesWhenObscured(true)`; `onNewIntent` zawsze `denied`; wynik przez `setResult` z `status`, `detail` (po filtrze), `state`, `op_id`; rejestr i magazyny brane z `BridgeFiles`.
- [ ] **Krok 4: `BridgeExecutor`** - `state`, `root_adb_on`, `adb_on`, `adb_off`; etapy meldowane do rejestru; blokada i ślad wykonawcy zakładane przed startem łańcucha i zdejmowane po potwierdzeniu.
- [ ] **Krok 5: wyzwalacze bez interfejsu** - `adbwifion` i `adbwifioff` w `AgentRuntime.trigger`, na tej samej ścieżce co mostek.
- [ ] **Krok 6: build i testy** - `./gradlew :app:testDebugUnitTest assembleDebug`
- [ ] **Krok 7: commit** - `feat(bridge): BridgeActivity with state, root_adb_on, adb_on and adb_off, ADB verified by socket, root channel path fixed`

---

## Zadanie 10: zaufanie i wykrywanie po stronie Heliosa

Musi powstać **przed** klientem, bo klient nie ma prawa zawołać mostka, zanim człowiek nie zaakceptuje odcisku narzędzia.

**Pliki:** utwórz `ToolsTrust.java`, test `ToolsTrustTest.java`.

**Interfejsy:** `Status check(Installed tool, Accepted accepted)` zwracające `ABSENT`, `NEEDS_ACCEPT`, `CHANGED`, `TOO_OLD`, `OK`; `void accept(String fingerprint)`.

- [ ] **Krok 1: testy**

```java
@Test public void theToolIsNotTrustedJustBecauseItIsInstalled() {
    assertEquals(NEEDS_ACCEPT, check(installed("AA", 31), nothingAccepted()));
}

@Test public void aChangedFingerprintStopsEverythingUntilAcceptedAgain() {
    assertEquals(CHANGED, check(installed("BB", 31), accepted("AA")));
    assertEquals("a changed tool may not receive a file either", CHANGED, check(installed("BB", 31), accepted("AA")));
}

@Test public void tooOldATool() {
    assertEquals(TOO_OLD, check(installed("AA", 30), accepted("AA")));   // próg = versionCode 31
    assertEquals(OK, check(installed("AA", 31), accepted("AA")));
}

@Test public void nothingInstalledIsANormalState() {
    assertEquals(ABSENT, check(null, accepted("AA")));
}
```

- [ ] **Krok 2: czerwone** - `cd I:/Projekty/lenovo_clock/dash && ./gradlew testDebugUnitTest --tests '*ToolsTrustTest*'`
- [ ] **Krok 3: implementacja** - odcisk z `GET_SIGNING_CERTIFICATES`/`SigningInfo` (na Androidzie 10 dostępne), zbiór odcisków przy wielu podpisujących; akceptacja zapisana w preferencjach Heliosa; ekran akceptacji z nazwą pakietu i odciskiem.
- [ ] **Krok 4: sprawdzenie przed każdym wywołaniem** - jedna metoda `requireOk()` wołana przed budową intencji i przed wydaniem grantu do pliku.
- [ ] **Krok 5: zielone i commit** - `feat(tools): Helios accepts the tool fingerprint before it will talk to it, and rechecks on every call`

---

## Zadanie 11: klient mostka w Heliosie

**Pliki:** utwórz `ToolsCall.java` (czysty model, testy JVM), `ToolsBridge.java` (adapter androidowy, bez testów JVM); test `ToolsCallTest.java`; zmień `MainActivity.java`.

**Interfejsy:** `static String newOpId()`, `static Next next(String status, boolean cancelled)`, `static long stageAgeMs(String snapshotJson, String knownBootId)`, `static boolean acceptResult(String snapshotOpId, String myOpId)`, `static boolean mayStartAnother(String aboutStage)`.

- [ ] **Krok 1: testy czystego modelu**

```java
@Test public void inProgressUnknownAndCancelMeanKeepAsking() {
    assertEquals(POLL, ToolsCall.next("in_progress", false));
    assertEquals(POLL, ToolsCall.next("unknown", false));
    assertEquals(POLL, ToolsCall.next(null, true));
    assertEquals(DONE, ToolsCall.next("ok", false));
    assertEquals(DONE, ToolsCall.next("failed", false));
    assertEquals(DONE, ToolsCall.next("busy", false));
    assertEquals(DONE, ToolsCall.next("denied", false));
    assertEquals(DONE, ToolsCall.next("wrong_firmware", false));
}

@Test public void aLateResultOfAnotherRequestIsIgnored() {
    assertTrue(ToolsCall.acceptResult("op-mine", "op-mine"));
    assertFalse(ToolsCall.acceptResult("op-other", "op-mine"));
    assertFalse(ToolsCall.acceptResult(null, "op-mine"));
}

@Test public void theStageAgeComesFromTheAboutBlockAndNeedsTheSameBoot() {
    String snap = "{\"about\":{\"stage\":\"running\",\"stage_since_uptime_ms\":4000,"
                + "\"now_uptime_ms\":10000,\"boot_id\":\"b1\"}}";
    assertEquals(6000, ToolsCall.stageAgeMs(snap, "b1"));
    assertEquals("another boot makes the difference meaningless", -1, ToolsCall.stageAgeMs(snap, "b2"));
    assertEquals(-1, ToolsCall.stageAgeMs("{}", "b1"));
}

@Test public void anotherOperationWaitsForATerminalStage() {
    assertFalse(ToolsCall.mayStartAnother("running"));
    assertFalse(ToolsCall.mayStartAnother("awaiting_consent"));
    assertTrue(ToolsCall.mayStartAnother("finished"));
    assertTrue(ToolsCall.mayStartAnother("interrupted"));
    assertTrue(ToolsCall.mayStartAnother("absent"));
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja modelu i adaptera** - adapter buduje jawną intencję, wkłada plik w `Intent.data` z grantem wyłącznie dla operacji z plikiem i nie dodaje żadnej innej flagi.
- [ ] **Krok 4: pełny przebieg w `MainActivity`**
  - zapis `op_id` w preferencjach **przed** `startActivityForResult`,
  - `onActivityResult`: odrzucenie wyniku z cudzym `op_id`, przyjęcie własnego,
  - `POLL`: odpytywanie `state` z `about` co 5 s, aż etap będzie terminalny albo minie limit maszynowy etapu,
  - po limicie: koniec odpytywania cyklicznego, ale pytanie przy każdym otwarciu menu,
  - **po każdej operacji innej niż `state`, także zakończonej `ok`**, jedno dodatkowe `state` w celu uzgodnienia,
  - brak możliwości uruchomienia drugiej operacji, dopóki `mayStartAnother` jest fałszywe.
- [ ] **Krok 5: zielone** - `./gradlew testDebugUnitTest assembleDebug lintDebug`
- [ ] **Krok 6: commit** - `feat(tools): bridge client with a persisted op id, registry-driven polling and reconciliation after every operation`

---

## Zadanie 12: menu narzędzi

**Pliki:** utwórz `ToolsState.java`, `ToolsMenu.java`, test `ToolsMenuTest.java`; zmień `NavigationMenu.java`.

- [ ] **Krok 1: testy**

```java
@Test public void withoutTheToolOnlyInstallIsOffered() {
    assertEquals(asList("Zainstaluj narzędzia"), ToolsMenu.items(state().withoutTool()));
}

@Test public void aChangedToolFingerprintOffersOnlyTheAcceptance() {
    assertEquals(asList("Zaakceptuj nowe narzędzia"), ToolsMenu.items(state().toolChanged()));
}

@Test public void anUnknownMicStateKeepsTheRepairVisible() {
    assertTrue(ToolsMenu.items(state().rooted().micUnknown()).contains("Napraw mikrofon"));
}

@Test public void restoreShowsUpOnlyWithASavedState() {
    assertFalse(ToolsMenu.items(state().rooted()).contains("Przywróć mikrofon"));
    assertTrue(ToolsMenu.items(state().rooted().micSaved()).contains("Przywróć mikrofon"));
}

@Test public void adbOnAndAdbOffNeverShowTogether() {
    assertTrue(ToolsMenu.items(state().rooted().adbListening()).contains("Wyłącz ADB"));
    assertFalse(ToolsMenu.items(state().rooted().adbListening()).contains("Włącz ADB"));
    assertTrue(ToolsMenu.items(state().rooted().adbSilent()).contains("Włącz ADB"));
}

@Test public void permissionItemsFollowLocalMeasurements() {
    assertTrue(ToolsMenu.items(state().rooted().withoutRecordAudio()).contains("Uprawnienie mikrofonu"));
    assertFalse(ToolsMenu.items(state().rooted().withRecordAudio()).contains("Uprawnienie mikrofonu"));
    assertTrue(ToolsMenu.items(state().rooted().cannotWriteSettings()).contains("Pozwól na jasność"));
    assertTrue(ToolsMenu.items(state().rooted().notHome()).contains("Ustaw jako ekran główny"));
}
```

- [ ] **Krok 2: czerwone, implementacja, zielone**
- [ ] **Krok 3: podpięcie w `NavigationMenu`** - pozycja "Narzędzia zegara" otwierająca podmenu.
- [ ] **Krok 4: commit** - `feat(tools): clock tools menu driven by the snapshot and local measurements`

---

## Zadanie 13: operacje uprawnień i mikrofon

**Pliki:** zmień `BridgeExecutor.java`, `bridge/Ops.java`; utwórz `bridge/MicState.java`; zmień `dash/app/src/main/AndroidManifest.xml` (`WRITE_SETTINGS`); testy `bridge/OpsPreconditionsTest.java`, `bridge/MicStateTest.java`.

- [ ] **Krok 1: testy warunków wstępnych**

```java
@Test public void grantPermissionOnlyForTheCallerAndOnlyFromTheList() {
    assertEquals("unsupported", check("grant_permission", args("android.permission.CAMERA"), callerDeclaring("android.permission.CAMERA")).error);
    assertEquals("unsupported", check("grant_permission", argsNamingAnotherPackage(), caller()).error);
    assertEquals("unsupported", check("grant_permission", args("android.permission.RECORD_AUDIO"), callerDeclaringNothing()).error);
    assertNull(check("grant_permission", args("android.permission.RECORD_AUDIO"), callerDeclaring("android.permission.RECORD_AUDIO")).error);
    assertEquals("already granted is a no-op, not an error", "ok",
                 run("grant_permission", args("android.permission.RECORD_AUDIO"), callerThatAlreadyHasIt()).status);
}

@Test public void writeSettingsNeedsTheManifestEntry() {
    assertEquals("unsupported", check("write_settings", "", callerDeclaringNothing()).error);
    assertNull(check("write_settings", "", callerDeclaring("android.permission.WRITE_SETTINGS")).error);
}

@Test public void setHomeNeedsExactlyOneEnabledCandidate() {
    assertEquals("unsupported", check("set_home", "", callerWithHomeActivities(0)).error);
    assertEquals("unsupported", check("set_home", "", callerWithHomeActivities(2)).error);
    assertNull(check("set_home", "", callerWithHomeActivities(1)).error);
}
```

- [ ] **Krok 2: testy mikrofonu, z trwałością i wykonaniem**

```java
@Test public void theFirstReleaseRecordsTheRealStateOfEveryTarget() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "b"), pkg -> "a".equals(pkg));
    assertEquals("saved", m.status());
    assertTrue(m.saved("a")); assertFalse(m.saved("b"));
    assertEquals("the record must survive a restart", "saved", new MicState(store).status());
}

@Test public void aSecondReleaseDoesNotOverwriteTheRecord() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a"), pkg -> true);
    new MicState(store).rememberBefore(asList("a"), pkg -> false);
    assertTrue("the original state survives", new MicState(store).saved("a"));
}

@Test public void aTargetAddedInANewToolVersionIsRecordedBeforeItIsChanged() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a"), pkg -> true);
    m.rememberBefore(asList("a", "c"), pkg -> "c".equals(pkg));
    assertTrue(m.saved("a")); assertTrue(m.saved("c"));
}

@Test public void aMissingPackageIsSkippedAndNeverRecorded() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "gone"), presentOnly("a"));
    assertFalse(m.hasEntry("gone"));
}

@Test public void restoreNeverGrantsWhatWasNotThere() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "b"), pkg -> "a".equals(pkg));
    assertEquals(asList("a"), m.toGrant());
    assertEquals(asList("b"), m.toDeny());
}

@Test public void aPartialRestoreKeepsTheRestAndReportsFailure() {
    RecordingExecutor exec = new RecordingExecutor().failOn("b");
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "b"), pkg -> true);
    MicState.Outcome o = m.restore(exec);
    assertEquals("failed", o.status);
    assertEquals(asList("a"), exec.succeeded());
    assertEquals("saved", new MicState(store).status());
    assertEquals(asList("b"), new MicState(store).remaining());
}

@Test public void aFullRestoreClearsTheRecord() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a"), pkg -> true);
    assertEquals("ok", m.restore(new RecordingExecutor()).status);
    assertEquals("none", new MicState(store).status());
}
```

- [ ] **Krok 3: implementacja** - `mic_release` woła `rememberBefore` przed każdą zmianą, restartuje procesy celów i wraca do zadania wywołującego; rozszerzona lista celów to inny `consentScope`, więc wymaga nowej zgody.
- [ ] **Krok 4: zielone i commit** - `feat(bridge): permission operations plus mic_release and mic_restore with a per-package record that survives restarts`

---

## Zadanie 14: cicha instalacja i drugie źródło aktualizacji

**Pliki:** zmień `BridgeExecutor.java`, `dash/.../Updater.java`, `dash/.../ReleaseInfo.java`, `dash/app/src/main/AndroidManifest.xml`; utwórz `dash/.../ApkProvider.java`; testy `bridge/InstallGuardTest.java`, `UpdaterSourceTest.java`.

- [ ] **Krok 1: testy strażnika instalacji (sc2t)**

```java
@Test public void onlyTheCallersOwnStrictlyNewerBuildIsAccepted() {
    assertEquals("unsupported", guard(apk("pl.inne", 30, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertEquals("unsupported", guard(apk("pl.mateusz.helios", 29, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertEquals("unsupported", guard(apk("pl.mateusz.helios", 28, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertEquals("unsupported", guard(apk("pl.mateusz.helios", 30, "BB"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertNull(guard(apk("pl.mateusz.helios", 30, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
}

@Test public void theCopyDecidesNotTheSource() {
    assertEquals("failed", guardWithExpectedSha("sha-of-something-else").error);
    assertNull("a source swapped after the copy changes nothing", guardAfterSourceSwapped().error);
}

@Test public void copyingIsBoundedAndCleansUpAfterItself() {
    InstallOutcome o = runInstall(sourceThatBlocksForever(), budgetMs(200));
    assertEquals("failed", o.status);
    assertFalse("nothing is installing, so nothing may hold the lock", o.lockHeld);
    assertFalse("a partial copy is never left behind", o.copyExists);

    assertEquals("failed", runInstall(sourceLargerThan(64 * 1024 * 1024), budgetMs(60_000)).status);
    assertEquals("failed", runInstall(sourceWithoutGrant(), budgetMs(60_000)).status);
}

@Test public void aFailedPreparationDoesNotWedgeTheLock() {
    InstallOutcome o = runInstall(copyOkButMoveFails());
    assertEquals("failed", o.status);
    assertFalse(o.lockHeld);
}

@Test public void filesOfAnInterruptedInstallAreNotDeletedOnStartup() {
    registryWith("op1", "interrupted");
    new BridgeFiles(files).cleanupOnStart(registry, executorLockThatIsUnknown());
    assertTrue("deleting an APK from under a live installer would break the update", files.exists("bridge/op1.apk"));
    new BridgeFiles(files).cleanupOnStart(registry, executorLockThatConfirmsDeath());
    assertFalse(files.exists("bridge/op1.apk"));
}
```

- [ ] **Krok 2: testy aktualizatora (Helios)**

```java
@Test public void theUpdaterCanPointAtASecondRepository() {
    ReleaseInfo.Source tool = new ReleaseInfo.Source("SychPL/smartclock2tool", "smartclock2tool-%s.apk", "pl.mateusz.clockadbprobe");
    assertEquals("https://api.github.com/repos/SychPL/smartclock2tool/releases/latest", tool.apiUrl());
    assertNotNull(ReleaseInfo.parse(release("v2.19.0", "smartclock2tool-2.19.0.apk"), tool));
    assertNull("an asset of the other project is not ours", ReleaseInfo.parse(release("v2.19.0", "helios-2.19.0.apk"), tool));
    assertNull("two-part tags are not accepted on either side", ReleaseInfo.parse(release("v2.19", "smartclock2tool-2.19.apk"), tool));
}

@Test public void theDecisionUsesTheVersionOfTheTargetPackageNotOfHelios() {
    assertEquals("newer", Updater.decision("2.19.0", installedVersionOf("pl.mateusz.clockadbprobe", "2.18.0")));
    assertEquals("same", Updater.decision("2.19.0", installedVersionOf("pl.mateusz.clockadbprobe", "2.19.0")));
    assertEquals("a missing target package is a first install", "absent", Updater.decision("2.19.0", notInstalled()));
}

@Test public void theInstallPathDependsOnWhatIsAvailable() {
    assertEquals(SYSTEM_INSTALLER, Updater.path(target("pl.mateusz.clockadbprobe"), bridgeUnavailable()));
    assertEquals("the bridge only ever installs Helios itself", SYSTEM_INSTALLER,
                 Updater.path(target("pl.mateusz.clockadbprobe"), bridgeReady()));
    assertEquals(BRIDGE, Updater.path(target("pl.mateusz.helios"), bridgeReady()));
    assertEquals(SYSTEM_INSTALLER, Updater.path(target("pl.mateusz.helios"), bridgeUnavailable()));
}

@Test public void aSilentUpdateIsConfirmedByTheVersionAfterTheRestart() {
    assertEquals("pending", Updater.confirm(recordOf("op1"), installedVersionCode(29)));
    assertEquals("done", Updater.confirm(recordOf("op1"), installedVersionCode(30)));
}
```

- [ ] **Krok 3: `ApkProvider`** - własny `ContentProvider` w Heliosie (bez AndroidX): `exported="false"`, `grantUriPermissions="true"`, wydaje wyłącznie bieżący plik aktualizacji z katalogu prywatnego, tylko do odczytu, i odmawia każdej innej ścieżki. Test na urządzeniu, nie w JVM.
- [ ] **Krok 4: implementacja** - kopiowanie z limitem 64 MB i 60 s, odczyt z kopii, instalacja kanałem roota, sprzątanie wyłącznie plików wpisów terminalnych **i** potwierdzonych jako martwe.
- [ ] **Krok 5: zielone po obu stronach i commit** - `feat(bridge): install_apk through the root channel, ApkProvider in Helios and an updater that handles a second source`

---

## Zadanie 15: weryfikacja na sprzęcie

Kolejność jest istotna: każdy punkt zakłada poprzedni. Artefakt powstaje na bieżąco, nie na końcu.

**Ścieżki pozytywne (kryteria 1-9 specyfikacji):**

- [ ] **Krok 1** - czysty stan: odciąć zasilanie, włączyć, sprawdzić brak ADB i to, czy Helios wstaje.
- [ ] **Krok 2** - instalacja narzędzia z menu Heliosa, potwierdzenie systemowe, ekran akceptacji odcisku.
- [ ] **Krok 3** - `state` bez roota: pola `unknown`, `mic_holders` nie jest pustą listą, odpowiedź poniżej 3 s.
- [ ] **Krok 4** - `root_adb_on`: ostrzeżenie na ekranie zgody, log postępu, `adb connect` z komputera działa.
- [ ] **Krok 5** - `grant_permission` i `write_settings`: Helios przestaje prosić o mikrofon, `canWrite()` prawdziwe.
- [ ] **Krok 6** - `mic_release`: `dumpsys audio` pokazuje sesję Heliosa jako `1ch 16000Hz`, **pomiar rzeczywistego strumienia** (liczba próbek na sekundę z diagnostyki) zgadza się z żądaną częstotliwością, hasło wybudzające działa; `mic_restore` przywraca "Hey Google".
- [ ] **Krok 7** - `set_home`, odciąć zasilanie, zegar wstaje z Heliosem.
- [ ] **Krok 8** - ponownie `root_adb_on` (bo krok 7 skasował roota), potem `adb_off`: połączenie z innego urządzenia odrzucane, otwarta sesja pada. Zapisz, czy wystarczyło zerowanie właściwości, czy trzeba było zatrzymać demona; wynik trafia do dokumentacji sc2t (SPEC pkt 10.7).
- [ ] **Krok 9** - `adb_on` przy żywym rootcie przywraca nasłuch bez łańcucha.
- [ ] **Krok 10** - `install_apk`: Helios aktualizuje się bez dodatkowego okna instalatora, potwierdzenie przez `versionCode` po restarcie procesu.

**Odrzucenia przed wykonaniem (kryteria 10-18):**

- [ ] **Krok 11** - wywołania z testowej aplikacji pomocniczej: bez `startActivityForResult`, z obcą akcją, ze złymi typami pól, z niepoprawnym JSON-em, od użytkownika innego niż `0`.
- [ ] **Krok 12** - flagi: `FLAG_ACTIVITY_FORWARD_RESULT`, `NEW_TASK`, `SINGLE_TOP`, oraz intencja doręczona przez `onNewIntent`.
- [ ] **Krok 13** - obcy odcisk, odmowa na ekranie zaufania, zmiana odcisku po wcześniejszej zgodzie.
- [ ] **Krok 14** - odinstalowanie pakietu wywołującego przy otwartym ekranie zgody; cofnięcie zaufania i pojedynczej zgody w trakcie oczekującego żądania.
- [ ] **Krok 15** - drugie żądanie z nowym `op_id` w trakcie pierwszego, z menu i z agenta HTTP; duplikat w tym samym czasie.
- [ ] **Krok 16** - `grant_permission` spoza listy i bez deklaracji, `write_settings` bez deklaracji, `set_home` przy zerowej i wielokrotnej liczbie kandydatów, `adb_on`/`adb_off` przy martwym rootcie.
- [ ] **Krok 17** - `install_apk`: obcy pakiet, obcy podpis, równy i niższy `versionCode`, brak grantu, strumień blokujący i przekraczający limit.
- [ ] **Krok 18** - Helios wobec sc2t z podmienionym podpisem: żadne wywołanie ani grant nie wychodzi.

**Przerwania po rozpoczęciu (kryteria 19-23):**

- [ ] **Krok 19** - zabicie sc2t i zabicie Heliosa na etapach `awaiting_consent`, `copying`, `running`, `installing`; sprawdzenie etapu z `about` po każdym.
- [ ] **Krok 20** - odcięcie zasilania w trakcie łańcucha; po starcie `chain` wraca do `idle`, a wpis jest `interrupted`.
- [ ] **Krok 21** - zabicie sc2t przy żywym wykonawcy: nowe żądanie dostaje `busy`, duplikat swój etap, odczyty działają.
- [ ] **Krok 22** - przerwane `mic_release` i częściowe `mic_restore`; `mic_saved_state` przeżywa restart zegara.
- [ ] **Krok 23** - nieznane `api`, nieznana operacja, nieznany `op_id`; `detail` bez tokenów i ścieżek prywatnych.

- [ ] **Krok 24: artefakt** - `dash/artifacts/tools-bridge-results.md`: wynik każdego kroku, odpowiedzi na dwa pytania otwarte ze specyfikacji (skuteczność `adb_off` i cichość instalacji), oraz tabela kryterium specyfikacji → krok albo test.

---

## Zadanie 16: dokumentacja i wydania

Dopiero po zadaniu 15, bo wydanie opisuje rzeczy potwierdzone na sprzęcie.

- [ ] **Krok 1** - `docs/BRIDGE-API.md` w sc2t, po angielsku: akcja, pola, operacje, kody, przykłady wywołań, odesłanie do SPEC 0.12.
- [ ] **Krok 2** - README obu aplikacji: sekcja o mostku, wprost mówiąca, że Helios nie dostaje roota ani powłoki.
- [ ] **Krok 3** - `dash/docs/INSTALL.md`: ścieżka TalkBack, Helios, menu narzędzi.
- [ ] **Krok 4** - wydania: sc2t `v2.19.0`, Helios `v0.10.0`, oba z opisem mostka i z ostrzeżeniem o ADB.
- [ ] **Krok 5** - commit i tagi.

---

## Kolejność, bramki i stan pośredni

| po zadaniu | co działa |
| --- | --- |
| 1-2 | testy sc2t w ogóle się uruchamiają, blokada ma właściciela |
| 3-8 | sam rdzeń mostka, jeszcze bez interfejsu: rejestr, zaufanie, walidacja, migawka, decyzje |
| 9 | mostek odpowiada na `state` i steruje ADB; sc2t wolno wydać |
| 10-12 | Helios akceptuje narzędzie, woła je i pokazuje menu; obie aplikacje mają sens użytkowy |
| 13 | uprawnienia i mikrofon, czyli powód, dla którego to powstało |
| 14 | cicha aktualizacja i instalacja narzędzia z menu |
| 15-16 | potwierdzenie na sprzęcie, dokumentacja, wydania |

Zadania 10-12 zakładają, że narzędzie jest już zainstalowane ręcznie; pozycja "Zainstaluj narzędzia" działa dopiero po zadaniu 14 i do tego czasu jest ukryta.

Przed każdym commitem: testy jednostkowe obu projektów, `assembleDebug` obu, `lintDebug` w Heliosie. Zadanie 15 wymaga naładowanego zegara, bo kilka kroków odcina zasilanie.
