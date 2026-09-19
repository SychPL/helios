# Plan wdrożenia SPEC 0.12 - mostek Helios ↔ Smart Clock 2 Tools

> **Dla wykonawcy:** zadania realizuje się po kolei, każde kończy się działającym, przetestowanym kawałkiem. Kroki mają pola wyboru, bo służą do odhaczania.

**Cel:** Helios prosi narzędzie sc2t o wąsko zdefiniowane operacje (stan, root i ADB, nadanie uprawnień, mikrofon, cicha aktualizacja), nie dostając roota ani powłoki.

**Architektura:** jawna intencja z wynikiem, zamknięta lista operacji, zaufanie po odcisku certyfikatu po obu stronach, trwały rejestr żądań z identyfikatorem nadanym przez wywołującego, czas monotoniczny z identyfikatorem uruchomienia systemu.

**Stos:** dwie aplikacje Android, Java 8, bez AndroidX. Helios: `I:\Projekty\lenovo_clock\dash`, pakiet `pl.mateusz.helios`, minSdk i targetSdk 29, testy JUnit 4 przez `./gradlew testDebugUnitTest`. sc2t: `I:\Projekty\lenovo_clock`, pakiet `pl.mateusz.clockadbprobe`, minSdk 24, targetSdk 27, compileSdk 34, testy JUnit 4 przez `./gradlew :app:testDebugUnitTest`.

**Specyfikacja:** [SPEC-0.12-tools-bridge.md](SPEC-0.12-tools-bridge.md). Plan realizuje ją punkt po punkcie i nie rozstrzyga niczego, czego ona nie mówi.

## Ograniczenia globalne

- Java 8, bez AndroidX, bez nowych zależności w obu aplikacjach. sc2t ma dziś dokładnie jedną zależność (`junit:junit:4.13.2` w testach) i to się nie zmienia.
- Wszystko, co da się wydzielić jako klasę bez `Context`, ma być taką klasą i mieć test JVM. Wzorzec z obu repozytoriów: ręczne atrapy, nigdy biblioteki do mockowania.
- Żadna praca blokująca nie idzie na wątek interfejsu. sc2t używa surowych wątków z nazwami, Helios ma swój `main` handler i executor w serwisie.
- Teksty dla człowieka: w sc2t po angielsku (aplikacja jest już przetłumaczona), w Heliosie po polsku (interfejs jest polski).
- Bez tokenów i ścieżek prywatnych w `detail`, w logu i w commitach.
- Czas: wyłącznie `SystemClock.elapsedRealtime()` do limitów, `System.currentTimeMillis()` tylko do `finished_at_ms`.
- Nazwy operacji, kody stanu i nazwy pól JSON są dokładnie te ze specyfikacji, bez skrótów i synonimów.

## Struktura plików

**sc2t** (`I:\Projekty\lenovo_clock\app\src\main\java\pl\mateusz\clockadbprobe\`):

| plik | odpowiedzialność |
| --- | --- |
| `bridge/BridgeRequest.java` (nowy) | parsowanie i walidacja żądania: akcja, `api`, `op`, `args`, `op_id`, flagi. Czysty, testowalny |
| `bridge/OpRegistry.java` (nowy) | trwały rejestr żądań: krotka klucza, etapy, czasy monotoniczne, `boot_id`, domykanie po restarcie. Czysty, z interfejsem na magazyn |
| `bridge/TrustStore.java` (nowy) | odciski i zgody: zapis, porównanie, unieważnianie po zmianie odcisku. Czysty, z interfejsem na magazyn |
| `bridge/Snapshot.java` (nowy) | budowa migawki stanu z wyników pomiarów, wartości `unknown`, serializacja do JSON. Czysty |
| `bridge/Ops.java` (nowy) | definicje operacji: klasa ryzyka, warunki wstępne, limity, istotne argumenty. Czysty |
| `BridgeActivity.java` (nowy) | jedyne eksportowane wejście: tożsamość, ekrany zaufania i zgody, postęp, wynik |
| `BridgeExecutor.java` (nowy) | wykonanie operacji przez `RootKit` i kanał roota, raportowanie etapów do rejestru |
| `OperationGate.java` (zmiana) | blokada z właścicielem i etapem zamiast globalnego boolean |
| `RootKit.java` (zmiana) | `adbListening()`, `adbOff()` potwierdzane faktem, poprawka ścieżki kanału |
| `AgentRuntime.java` (zmiana) | wyzwalacze `adbwifion`/`adbwifioff` w trybie bez interfejsu |

**Helios** (`I:\Projekty\lenovo_clock\dash\app\src\main\java\pl\mateusz\helios\`):

| plik | odpowiedzialność |
| --- | --- |
| `ToolsBridge.java` (nowy) | klient mostka: budowa intencji, `op_id`, parsowanie wyniku i migawki, reguły ponawiania. Czysty |
| `ToolsState.java` (nowy) | model migawki po stronie Heliosa plus lokalne pomiary (uprawnienie, `canWrite`, ekran główny) |
| `ToolsMenu.java` (nowy) | budowa pozycji menu z warunków |
| `MainActivity.java` (zmiana) | uruchamianie mostka, odbiór wyniku, odpytywanie rejestru |
| `NavigationMenu.java` (zmiana) | pozycja "Narzędzia zegara" |
| `Updater.java` (zmiana) | źródło jako parametr, żeby móc pobrać też sc2t |
| `ReleaseInfo.java` (zmiana) | repozytorium, wzorzec nazwy i oczekiwany pakiet jako parametry |
| `AndroidManifest.xml` (zmiana) | `WRITE_SETTINGS`, `FileProvider` |

---

## Zadanie 1: przywrócić kompilację testów sc2t

Bez tego żaden test w sc2t nie ruszy: trzy pliki testowe odwołują się do klas usuniętych razem z fuzzerem.

**Pliki:**
- Usuń: `app/src/test/java/pl/mateusz/clockadbprobe/ArtifactArchiveTest.java`, `FuzzProtocolTest.java`, `ControlledFuzzCommandTest.java`
- Zmień: `.github/workflows/build-apk.yml`

- [ ] **Krok 1: zobacz błąd**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest
```
Oczekiwane: błąd kompilacji, `cannot find symbol class ArtifactArchive`.

- [ ] **Krok 2: usuń osierocone testy**

```bash
cd I:/Projekty/lenovo_clock && git rm app/src/test/java/pl/mateusz/clockadbprobe/ArtifactArchiveTest.java app/src/test/java/pl/mateusz/clockadbprobe/FuzzProtocolTest.java app/src/test/java/pl/mateusz/clockadbprobe/ControlledFuzzCommandTest.java
```

- [ ] **Krok 3: testy przechodzą**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest
```
Oczekiwane: zielone, 7 plików testowych.

- [ ] **Krok 4: CI uruchamia testy**

W `.github/workflows/build-apk.yml` zamień krok budowania na:

```yaml
      - name: Build and test
        run: ./gradlew :app:testDebugUnitTest assembleDebug
```

- [ ] **Krok 5: commit**

```bash
git add -A && git commit -m "test: drop the three tests orphaned by the fuzzer removal and run the suite in CI"
```

---

## Zadanie 2: blokada z właścicielem i etapem

`OperationGate` jest dziś globalnym boolean bez właściciela, bez etapu i bez odporności na niezbalansowane zwolnienie. Specyfikacja wymaga blokady obejmującej czas pracy wykonawcy, z rozróżnieniem etapów i z `busy` tylko dla nowego żądania.

**Pliki:**
- Zmień: `app/src/main/java/pl/mateusz/clockadbprobe/OperationGate.java`
- Test: `app/src/test/java/pl/mateusz/clockadbprobe/OperationGateTest.java`

**Interfejsy:**
- Produkuje: `OperationGate.acquire(String ownerId, String stage)`, `OperationGate.stage(String ownerId, String stage)`, `OperationGate.release(String ownerId)`, `OperationGate.ownerId()`, `OperationGate.stageOf()`, `OperationGate.isBusy()`; stare `tryStartProbe`/`finishProbe` zostają jako cienkie opakowania na identyfikator `"probe"`, żeby nie ruszać dwudziestu miejsc wywołań.

- [ ] **Krok 1: test, który nie przechodzi**

```java
@Test public void aLockBelongsToItsOwnerAndCarriesAStage() {
    assertTrue(OperationGate.acquire("op-1", "running"));
    assertFalse("someone else must not take it", OperationGate.acquire("op-2", "running"));
    assertTrue("the same owner re-entering is not a second operation", OperationGate.acquire("op-1", "running"));
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

- [ ] **Krok 2: uruchom, zobacz czerwone**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest --tests '*OperationGateTest*'
```
Oczekiwane: `cannot find symbol method acquire`.

- [ ] **Krok 3: implementacja**

```java
final class OperationGate {
    private static String owner;      // null = wolna
    private static String stage = "";
    static synchronized boolean acquire(String ownerId, String stage) {
        if (owner != null && !owner.equals(ownerId)) return false;
        owner = ownerId; OperationGate.stage = stage; return true;
    }
    static synchronized void stage(String ownerId, String stage) {
        if (ownerId != null && ownerId.equals(owner)) OperationGate.stage = stage;
    }
    static synchronized void release(String ownerId) {
        if (ownerId != null && ownerId.equals(owner)) { owner = null; stage = ""; }
    }
    static synchronized String ownerId() { return owner; }
    static synchronized String stageOf() { return stage; }
    static synchronized boolean isBusy() { return owner != null; }
    // zgodność ze starymi wywołaniami z AgentServer, MainActivity i InstallActivity
    static boolean tryStartProbe() { return acquire("probe", "running"); }
    static void finishProbe() { release("probe"); }
}
```

- [ ] **Krok 4: zielone i bez regresji**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest assembleDebug
```

- [ ] **Krok 5: commit**

```bash
git add -A && git commit -m "feat(gate): the execution lock gets an owner and a stage, so a foreign release cannot free it"
```

---

## Zadanie 3: trwały rejestr żądań

Rejestr jest sercem odzyskiwania po przerwaniu. Musi przeżyć śmierć procesu i restart zegara, domykać porzucone wpisy regułą etapu i odpowiadać na pytanie `about`.

**Pliki:**
- Utwórz: `app/src/main/java/pl/mateusz/clockadbprobe/bridge/OpRegistry.java`
- Test: `app/src/test/java/pl/mateusz/clockadbprobe/bridge/OpRegistryTest.java`

**Interfejsy:**
- Konsumuje: `OpRegistry.Store` (`String read()`, `void write(String)`), `OpRegistry.Clock` (`long uptimeMs()`, `long wallMs()`, `String bootId()`).
- Produkuje: `Entry accept(String pkg, String fingerprint, String opId, String op, String argsDigest)`, `void stage(String opId, String stage)`, `void finish(String opId, String status)`, `Entry about(String pkg, String fingerprint, String opId)`, `void recover()`, `boolean duplicate(Entry e, String op, String argsDigest)`.

Etapy: `accepted`, `awaiting_consent`, `copying`, `running`, `installing`, `finished`, `interrupted`. Etapy terminalne: `finished`, `interrupted`.

- [ ] **Krok 1: testy, które nie przechodzą**

```java
public class OpRegistryTest {
    private final FakeStore store = new FakeStore();
    private final FakeClock clock = new FakeClock("boot-a", 1000);

    @Test public void anEntryIsOwnedByCallerPackageAndSignature() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept("pl.mateusz.helios", "AA", "op1", "state", "d1");
        assertEquals("accepted", r.about("pl.mateusz.helios", "AA", "op1").stage);
        assertEquals("a foreign package must not see the entry", "absent", r.about("pl.evil", "AA", "op1").stage);
        assertEquals("a changed signature must not see it either", "absent", r.about("pl.mateusz.helios", "BB", "op1").stage);
        assertEquals("absent", r.about("pl.mateusz.helios", "AA", "never-used").stage);
    }

    @Test public void theSameIdWithDifferentContentIsRefused() {
        OpRegistry r = new OpRegistry(store, clock);
        OpRegistry.Entry first = r.accept("pl.mateusz.helios", "AA", "op1", "adb_off", "d1");
        assertTrue(r.duplicate(first, "adb_off", "d1"));
        assertFalse("another operation under the same id is not a duplicate", r.duplicate(first, "adb_on", "d1"));
        assertFalse("other arguments are not a duplicate either", r.duplicate(first, "adb_off", "d2"));
    }

    @Test public void stageDecidesHowAnAbandonedEntryCloses() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept("p", "AA", "waiting", "root_adb_on", "d");
        r.stage("waiting", "awaiting_consent");
        r.accept("p", "AA", "working", "root_adb_on", "d");
        r.stage("working", "running");
        new OpRegistry(store, clock).recover();           // proces zginął i wstał
        OpRegistry after = new OpRegistry(store, clock);
        assertEquals("finished", after.about("p", "AA", "waiting").stage);
        assertEquals("denied", after.about("p", "AA", "waiting").status);
        assertEquals("interrupted", after.about("p", "AA", "working").stage);
        assertEquals("unknown", after.about("p", "AA", "working").status);
    }

    @Test public void anEntryFromAnotherBootIsClosedToo() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept("p", "AA", "op1", "root_adb_on", "d");
        r.stage("op1", "running");
        clock.boot("boot-b");
        new OpRegistry(store, clock).recover();
        assertEquals("interrupted", new OpRegistry(store, clock).about("p", "AA", "op1").stage);
    }

    @Test public void timesAreMonotonicAndCarryTheBootId() {
        OpRegistry r = new OpRegistry(store, clock);
        r.accept("p", "AA", "op1", "root_adb_on", "d");
        clock.advance(5000);
        r.stage("op1", "running");
        clock.advance(2000);
        OpRegistry.Entry e = r.about("p", "AA", "op1");
        assertEquals(1000, e.startedAtUptimeMs);
        assertEquals(6000, e.stageSinceUptimeMs);
        assertEquals("boot-a", e.bootId);
        assertEquals(0, e.finishedAtMs);            // niezakończona nie ma czasu ściennego
        r.finish("op1", "ok");
        assertTrue(r.about("p", "AA", "op1").finishedAtMs > 0);
    }
}
```

Atrapy w tym samym pliku, zgodnie ze stylem repozytorium:

```java
static final class FakeStore implements OpRegistry.Store {
    String data = "";
    public String read() { return data; }
    public void write(String text) { data = text; }
}
static final class FakeClock implements OpRegistry.Clock {
    private String bootId; private long uptime;
    FakeClock(String bootId, long uptime) { this.bootId = bootId; this.uptime = uptime; }
    void advance(long ms) { uptime += ms; }
    void boot(String id) { bootId = id; uptime = 0; }
    public long uptimeMs() { return uptime; }
    public long wallMs() { return 1_700_000_000_000L + uptime; }
    public String bootId() { return bootId; }
}
```

- [ ] **Krok 2: czerwone**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest --tests '*OpRegistryTest*'
```

- [ ] **Krok 3: implementacja**

Rejestr trzyma listę wpisów w jednym dokumencie JSON (`org.json` jest w Androidzie, w testach JVM trzeba go dodać jako `testImplementation 'org.json:json:20240303'`; to zależność wyłącznie testowa). Kluczowe reguły:

```java
public OpRegistry(Store store, Clock clock) { this.store = store; this.clock = clock; load(); }

public Entry accept(String pkg, String fingerprint, String opId, String op, String argsDigest) {
    Entry e = new Entry(pkg, fingerprint, opId, op, argsDigest,
                        clock.uptimeMs(), clock.uptimeMs(), clock.bootId(), "accepted", "in_progress", 0);
    entries.put(opId, e); save(); return e;
}

public void stage(String opId, String stage) {
    Entry e = entries.get(opId); if (e == null) return;
    e.stage = stage; e.stageSinceUptimeMs = clock.uptimeMs(); e.bootId = clock.bootId();
    e.status = "in_progress"; save();
}

public void finish(String opId, String status) {
    Entry e = entries.get(opId); if (e == null) return;
    e.stage = "finished"; e.status = status; e.finishedAtMs = clock.wallMs();
    e.stageSinceUptimeMs = clock.uptimeMs(); save();
}

/** SPEC 0.12 pkt 4.2: o domknięciu decyduje etap, nie przyczyna. */
public void recover() {
    for (Entry e : entries.values()) {
        if (TERMINAL.contains(e.stage)) continue;
        if ("accepted".equals(e.stage) || "awaiting_consent".equals(e.stage)) { e.stage = "finished"; e.status = "denied"; e.finishedAtMs = clock.wallMs(); }
        else { e.stage = "interrupted"; e.status = "unknown"; }
        e.stageSinceUptimeMs = clock.uptimeMs(); e.bootId = clock.bootId();
    }
    save();
}

public Entry about(String pkg, String fingerprint, String opId) {
    Entry e = entries.get(opId);
    if (e == null || !e.pkg.equals(pkg) || !e.fingerprint.equals(fingerprint)) return Entry.absent(opId);
    return e;
}

public boolean duplicate(Entry e, String op, String argsDigest) {
    return e != null && e.op.equals(op) && e.argsDigest.equals(argsDigest);
}
```

`recover()` woła `BridgeActivity` i `AgentKeepAliveService` przy starcie. Magazyn produkcyjny to plik `filesDir/bridge/registry.json` zapisywany atomowo (zapis do `.tmp`, potem `renameTo`), a zegar produkcyjny czyta `SystemClock.elapsedRealtime()`, `System.currentTimeMillis()` i `boot_id` z `/proc/sys/kernel/random/boot_id` z zapasem w postaci losowego identyfikatora zapisanego przy pierwszym starcie po restarcie.

- [ ] **Krok 4: zielone**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest
```

- [ ] **Krok 5: commit**

```bash
git add -A && git commit -m "feat(bridge): persistent request registry with monotonic stages, boot id and stage-based recovery"
```

---

## Zadanie 4: zaufanie i zgody

**Pliki:**
- Utwórz: `bridge/TrustStore.java`, test `bridge/TrustStoreTest.java`

**Interfejsy:**
- Konsumuje: `TrustStore.Store` (jak wyżej).
- Produkuje: `boolean trusted(String pkg, String fingerprint)`, `void trust(String pkg, String fingerprint)`, `boolean consented(String pkg, String fingerprint, String op, String argsDigest)`, `void consent(...)`, `void revokeAll(String pkg)`, `void revokeOne(String pkg, String op)`, `java.util.List<String> describe()`.

- [ ] **Krok 1: testy**

```java
@Test public void aChangedSignatureInheritsNothing() {
    TrustStore t = new TrustStore(store);
    t.trust("p", "AA"); t.consent("p", "AA", "grant_permission", "RECORD_AUDIO");
    assertTrue(t.consented("p", "AA", "grant_permission", "RECORD_AUDIO"));
    assertFalse("a new signature is a new app", t.trusted("p", "BB"));
    t.trust("p", "BB");
    assertFalse("consents of the old signature are gone", t.consented("p", "BB", "grant_permission", "RECORD_AUDIO"));
}

@Test public void consentIsBoundToTheSignificantArguments() {
    TrustStore t = new TrustStore(store);
    t.trust("p", "AA"); t.consent("p", "AA", "grant_permission", "RECORD_AUDIO");
    assertFalse("a permission added later is not covered", t.consented("p", "AA", "grant_permission", "CAMERA"));
}

@Test public void revokingTrustRemovesEveryConsent() {
    TrustStore t = new TrustStore(store);
    t.trust("p", "AA"); t.consent("p", "AA", "adb_off", "");
    t.revokeAll("p");
    assertFalse(t.trusted("p", "AA"));
    assertFalse(t.consented("p", "AA", "adb_off", ""));
}
```

- [ ] **Krok 2: czerwone** - `./gradlew :app:testDebugUnitTest --tests '*TrustStoreTest*'`
- [ ] **Krok 3: implementacja** - jeden dokument JSON: mapa pakiet → `{fingerprint, consents:[{op, args}]}`. Zmiana odcisku kasuje wpis w całości, zanim zapisze nowy.
- [ ] **Krok 4: zielone** - cała suita
- [ ] **Krok 5: commit** - `feat(bridge): trust and consent store keyed by package, signing certificate and significant arguments`

---

## Zadanie 5: walidacja żądania

**Pliki:**
- Utwórz: `bridge/BridgeRequest.java`, test `bridge/BridgeRequestTest.java`
- Utwórz: `bridge/Ops.java` (tabela operacji: nazwa, klasa ryzyka, limit etapu maszynowego, czy potrzebuje pliku, jak liczyć `argsDigest`)

**Interfejsy:**
- Produkuje: `static BridgeRequest parse(String action, int api, String op, String argsJson, String opId, int flags, boolean hasCaller, boolean forwardResult, boolean viaNewIntent)` zwracające obiekt z polem `error` (`null` = poprawne) i wartościami pól.

- [ ] **Krok 1: testy**

```java
@Test public void onlyTheDocumentedShapeIsAccepted() {
    assertNull(ok().error);
    assertEquals("unsupported", parse(withAction("android.intent.action.VIEW")).error);
    assertEquals("unsupported_api", parse(withApi(2)).error);
    assertEquals("unsupported", parse(withOp("rm -rf")).error);
    assertEquals("unsupported", parse(withArgs("{nope")).error);
    assertEquals("unsupported", parse(withOpId("zażółć")).error);
}

@Test public void theCallerMustBeIdentifiableAndDirect() {
    assertEquals("denied", parse(noCaller()).error);
    assertEquals("denied", parse(forwardResult()).error);
    assertEquals("denied", parse(viaNewIntent()).error);
    assertEquals("denied", parse(withFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).error);
    assertEquals("denied", parse(withFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)).error);
    assertNull("the URI grant is the one allowed flag", parse(withFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)).error);
}

@Test public void theArgumentDigestCoversOnlySignificantArguments() {
    assertEquals(Ops.argsDigest("grant_permission", "{\"permission\":\"android.permission.RECORD_AUDIO\"}"),
                 Ops.argsDigest("grant_permission", "{ \"permission\" : \"android.permission.RECORD_AUDIO\" }"));
    assertNotEquals(Ops.argsDigest("grant_permission", "{\"permission\":\"android.permission.RECORD_AUDIO\"}"),
                    Ops.argsDigest("grant_permission", "{\"permission\":\"android.permission.CAMERA\"}"));
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - `op_id` to `[0-9a-f]{32}`, `op` musi być w tabeli `Ops`, `args` musi parsować się jako JSON, dozwolona jest wyłącznie flaga grantu.
- [ ] **Krok 4: zielone**
- [ ] **Krok 5: commit** - `feat(bridge): request validation refusing foreign actions, unknown ops, forwarded results and extra intent flags`

---

## Zadanie 6: migawka stanu

**Pliki:**
- Utwórz: `bridge/Snapshot.java`, test `bridge/SnapshotTest.java`
- Zmień: `RootKit.java` (dodaj `adbListening()`, `micHolders()`, `bootId()`)

**Interfejsy:**
- Konsumuje: `Snapshot.Probe` z metodami zwracającymi `String`/`Boolean` i mogącymi zwrócić `null` = nie zmierzono.
- Produkuje: `String json(long budgetMs)`.

- [ ] **Krok 1: testy**

```java
@Test public void unmeasuredFieldsAreUnknownNotDefaults() {
    Snapshot s = new Snapshot(probeThatFails(), clock);
    JSONObject j = new JSONObject(s.json(3000));
    assertEquals("unknown", j.getString("root"));
    assertEquals("unknown", j.getString("mic_holders"));
    assertFalse("a failed measurement is never an empty list", j.optJSONArray("mic_holders") != null);
}

@Test public void micHoldersAreUnknownWithoutRoot() {
    Snapshot s = new Snapshot(probeWithoutRoot(), clock);
    JSONObject j = new JSONObject(s.json(3000));
    assertEquals(false, j.getBoolean("root"));
    assertEquals("unknown", j.getString("mic_holders"));
}

@Test public void adbPropertyAndAdbListeningAreSeparate() {
    Snapshot s = new Snapshot(probeWithPortSetButNotListening(), clock);
    JSONObject j = new JSONObject(s.json(3000));
    assertEquals("5555", j.getString("adb_property"));
    assertFalse(j.getBoolean("adb_listening"));
}

@Test public void theBudgetIsRespected() {
    Snapshot s = new Snapshot(probeThatSleeps(5000), clock);
    long started = clock.uptimeMs();
    s.json(3000);
    assertTrue("a slow probe must not hold the snapshot", clock.uptimeMs() - started <= 3000);
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - pomiary równolegle, każdy z własnym `Future`, całość ograniczona budżetem; pole niezmierzone to `"unknown"`. `adb_listening` to `new Socket()` z `connect(new InetSocketAddress("127.0.0.1", 5555), 300)`.
- [ ] **Krok 4: zielone**
- [ ] **Krok 5: commit** - `feat(bridge): state snapshot with a time budget, unknown fields and ADB measured by connection`

---

## Zadanie 7: BridgeActivity, operacje stanu i ADB

Pierwsze wywołanie z zewnątrz. Po tym zadaniu mostek ma sens użytkowy: Helios może zapytać o stan i włączyć ADB.

**Pliki:**
- Utwórz: `BridgeActivity.java`, `BridgeExecutor.java`
- Zmień: `AndroidManifest.xml` (aktywność po `InstallActivity`), `AgentRuntime.java` (wyzwalacze ADB bez interfejsu)
- Test: `bridge/BridgeFlowTest.java` (czysta logika przepływu: co zrobić dla danego żądania, stanu zaufania i rejestru)

**Interfejsy:**
- Produkuje: `BridgeFlow.Decision decide(BridgeRequest r, TrustStore t, OpRegistry reg, Snapshot s)` z wartościami `ASK_TRUST`, `ASK_CONSENT`, `RUN`, `ANSWER` i przypisanym kodem odpowiedzi.

Manifest:

```xml
<activity android:name=".BridgeActivity" android:exported="true" android:launchMode="standard"
          android:excludeFromRecents="true" android:theme="@style/AppTheme">
    <intent-filter>
        <action android:name="pl.mateusz.clockadbprobe.action.BRIDGE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

- [ ] **Krok 1: testy przepływu**

```java
@Test public void anUnknownCallerIsAskedForTrustBeforeAnythingElse() {
    assertEquals(ASK_TRUST, decide(request("state"), emptyTrust(), registry(), snapshot()).kind);
}

@Test public void aReadNeverAsksForConsent() {
    assertEquals(ANSWER, decide(request("state"), trusting(), registry(), snapshot()).kind);
}

@Test public void aHighRiskOperationAsksEveryTime() {
    TrustStore t = trusting(); t.consent("p", "AA", "root_adb_on", "");
    assertEquals(ASK_CONSENT, decide(request("root_adb_on"), t, registry(), snapshot()).kind);
}

@Test public void aDuplicateAnswersInsteadOfRunningTwice() {
    OpRegistry reg = registry();
    reg.accept("p", "AA", "op1", "adb_off", "");
    reg.stage("op1", "running");
    BridgeFlow.Decision d = decide(request("adb_off", "op1"), trustingWithConsent(), reg, snapshot());
    assertEquals(ANSWER, d.kind);
    assertEquals("in_progress", d.status);
}

@Test public void aNewRequestWhileAnotherRunsIsBusy() {
    OpRegistry reg = registry();
    reg.accept("p", "AA", "op1", "root_adb_on", ""); reg.stage("op1", "running");
    assertEquals("busy", decide(request("adb_off", "op2"), trustingWithConsent(), reg, snapshot()).status);
}

@Test public void theSameIdWithOtherContentIsUnsupported() {
    OpRegistry reg = registry();
    reg.accept("p", "AA", "op1", "adb_off", "");
    assertEquals("unsupported", decide(request("adb_on", "op1"), trustingWithConsent(), reg, snapshot()).status);
}

@Test public void anUnsupportedFirmwareRefusesTheChainWithoutRunningIt() {
    assertEquals("wrong_firmware", decide(request("root_adb_on"), trustingWithConsent(), registry(), foreignFirmware()).status);
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja przepływu** - kolejność: kształt żądania, tożsamość, `api`, firmware, duplikat, blokada, zgoda, wykonanie. Każdy etap zapisany w rejestrze przed przejściem dalej.
- [ ] **Krok 4: BridgeActivity** - ekrany zaufania, zgody i postępu; wszystkie przyciski `setFilterTouchesWhenObscured(true)`; `onNewIntent` zawsze `denied`; wynik przez `setResult` z `status`, `detail`, `state`, `op_id`.
- [ ] **Krok 5: BridgeExecutor** - `state`, `root_adb_on`, `adb_on`, `adb_off` na `RootKit`; etapy meldowane do rejestru; blokada brana przez `OperationGate.acquire(opId, stage)`.
- [ ] **Krok 6: wyzwalacze bez interfejsu** - w `AgentRuntime.trigger` dodaj `adbwifion` i `adbwifioff` delegujące do tej samej ścieżki co mostek.
- [ ] **Krok 7: build i testy**

```bash
cd I:/Projekty/lenovo_clock && ./gradlew :app:testDebugUnitTest assembleDebug
```

- [ ] **Krok 8: commit** - `feat(bridge): BridgeActivity with trust, consent and the state, root_adb_on, adb_on and adb_off operations`

---

## Zadanie 8: klient mostka w Heliosie

**Pliki:**
- Utwórz: `dash/app/src/main/java/pl/mateusz/helios/ToolsBridge.java`, test `dash/app/src/test/java/pl/mateusz/helios/ToolsBridgeTest.java`
- Zmień: `dash/app/src/main/java/pl/mateusz/helios/MainActivity.java`

**Interfejsy:**
- Produkuje: `static Intent intentFor(String op, String argsJson, String opId, android.net.Uri file)`, `static String newOpId()`, `static Result parse(int resultCode, Intent data)`, `static Next next(Result r)` gdzie `Next` to `DONE`, `POLL` albo `ASK_AGAIN`.

- [ ] **Krok 1: testy**

```java
@Test public void theIntentIsExplicitAndCarriesOnlyAllowedFlags() {
    Intent i = ToolsBridge.intentFor("state", "", "0123456789abcdef0123456789abcdef", null);
    assertEquals("pl.mateusz.clockadbprobe", i.getComponent().getPackageName());
    assertEquals("pl.mateusz.clockadbprobe.BridgeActivity", i.getComponent().getClassName());
    assertEquals("pl.mateusz.clockadbprobe.action.BRIDGE", i.getAction());
    assertEquals(0, i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
}

@Test public void aFileIsPassedAsIntentDataWithAGrant() {
    Intent i = ToolsBridge.intentFor("install_apk", "", id(), Uri.parse("content://pl.mateusz.helios.files/apk"));
    assertNotNull(i.getData());
    assertTrue((i.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
}

@Test public void inProgressAndCancelledMeanKeepAsking() {
    assertEquals(POLL, ToolsBridge.next(result("in_progress")));
    assertEquals(POLL, ToolsBridge.next(result("unknown")));
    assertEquals(POLL, ToolsBridge.next(cancelled()));
    assertEquals(DONE, ToolsBridge.next(result("ok")));
    assertEquals(DONE, ToolsBridge.next(result("failed")));
    assertEquals(DONE, ToolsBridge.next(result("busy")));
}

@Test public void aLateResultWithAForeignOpIdIsIgnored() {
    assertNull(ToolsBridge.parse(RESULT_OK, intentWithOpId("inne")).statusFor("moje"));
}

@Test public void theLimitIsComputedFromTheSnapshotAndTheBootId() {
    String snap = "{\"now_uptime_ms\":10000,\"about\":{\"stage\":\"running\",\"stage_since_uptime_ms\":4000,\"boot_id\":\"b1\"}}";
    assertEquals(6000, ToolsBridge.stageAgeMs(snap, "b1"));
    assertEquals("a different boot makes the age meaningless", -1, ToolsBridge.stageAgeMs(snap, "b2"));
}
```

- [ ] **Krok 2: czerwone** - `cd I:/Projekty/lenovo_clock/dash && ./gradlew testDebugUnitTest --tests '*ToolsBridgeTest*'`
- [ ] **Krok 3: implementacja klienta**
- [ ] **Krok 4: podpięcie w MainActivity** - `startActivityForResult`, `onActivityResult`, zapis `op_id` w preferencjach przed wywołaniem, odpytywanie `state` co 5 s po powrocie z `in_progress`, pytanie przy każdym otwarciu menu, dopóki rejestr nie poda etapu terminalnego.
- [ ] **Krok 5: zielone** - `./gradlew testDebugUnitTest assembleDebug lintDebug`
- [ ] **Krok 6: commit** - `feat(tools): bridge client with a persisted op id, polling from the registry and late-result rules`

---

## Zadanie 9: wykrywanie narzędzia i menu

**Pliki:**
- Utwórz: `ToolsState.java`, `ToolsMenu.java`, testy `ToolsMenuTest.java`
- Zmień: `NavigationMenu.java`

- [ ] **Krok 1: testy widoczności pozycji**

```java
@Test public void withoutTheToolOnlyInstallIsOffered() {
    assertEquals(asList("Zainstaluj narzędzia"), ToolsMenu.items(state().withoutTool()));
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
}

@Test public void permissionItemsFollowLocalMeasurements() {
    assertTrue(ToolsMenu.items(state().rooted().withoutRecordAudio()).contains("Uprawnienie mikrofonu"));
    assertFalse(ToolsMenu.items(state().rooted().withRecordAudio()).contains("Uprawnienie mikrofonu"));
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - `ToolsState` łączy migawkę z pomiarami lokalnymi (`checkSelfPermission`, `Settings.System.canWrite`, domyślny ekran główny), `ToolsMenu` zwraca listę pozycji z warunków ze specyfikacji pkt 6.1.
- [ ] **Krok 4: podpięcie** - nowa pozycja w `NavigationMenu` otwierająca podmenu narzędzi.
- [ ] **Krok 5: zielone i commit** - `feat(tools): clock tools menu driven by the snapshot and local measurements`

---

## Zadanie 10: operacje uprawnień

**Pliki:**
- Zmień: `BridgeExecutor.java` (sc2t), `bridge/Ops.java`
- Zmień: `dash/app/src/main/AndroidManifest.xml` (dodaj `WRITE_SETTINGS`)
- Testy: `bridge/OpsPreconditionsTest.java`

- [ ] **Krok 1: testy warunków wstępnych**

```java
@Test public void grantPermissionOnlyForTheCallerAndOnlyFromTheList() {
    assertEquals("unsupported", check("grant_permission", args("android.permission.CAMERA"), caller()).error);
    assertEquals("unsupported", check("grant_permission", argsForOtherPackage(), caller()).error);
    assertNull(check("grant_permission", args("android.permission.RECORD_AUDIO"), callerDeclaring("android.permission.RECORD_AUDIO")).error);
    assertEquals("a permission missing from the manifest cannot be granted", "unsupported",
                 check("grant_permission", args("android.permission.RECORD_AUDIO"), caller()).error);
}

@Test public void writeSettingsNeedsTheManifestEntry() {
    assertEquals("unsupported", check("write_settings", "", caller()).error);
    assertNull(check("write_settings", "", callerDeclaring("android.permission.WRITE_SETTINGS")).error);
}

@Test public void setHomeNeedsExactlyOneCandidate() {
    assertEquals("unsupported", check("set_home", "", callerWithHomeActivities(0)).error);
    assertEquals("unsupported", check("set_home", "", callerWithHomeActivities(2)).error);
    assertNull(check("set_home", "", callerWithHomeActivities(1)).error);
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - wykonanie kanałem roota, każda operacja bezczynna, gdy stan już jest docelowy.
- [ ] **Krok 4: zielone i commit** - `feat(bridge): grant_permission, write_settings and set_home with preconditions checked before anything runs`

---

## Zadanie 11: mikrofon

**Pliki:**
- Utwórz: `bridge/MicState.java` (mapa zapisanych stanów), test `bridge/MicStateTest.java`
- Zmień: `BridgeExecutor.java`

- [ ] **Krok 1: testy**

```java
@Test public void theFirstReleaseRecordsTheRealStateOfEveryTarget() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "b"), pkg -> "a".equals(pkg));   // a ma uprawnienie, b nie
    assertEquals("saved", m.status());
    assertTrue(m.saved("a")); assertFalse(m.saved("b"));
}

@Test public void aSecondReleaseDoesNotOverwriteTheRecord() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a"), pkg -> true);
    m.rememberBefore(asList("a"), pkg -> false);                  // teraz już odebrane
    assertTrue("the original state survives", m.saved("a"));
}

@Test public void aNewTargetIsAddedBeforeItIsChanged() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a"), pkg -> true);
    m.rememberBefore(asList("a", "c"), pkg -> "c".equals(pkg));
    assertTrue(m.saved("a")); assertTrue(m.saved("c"));
}

@Test public void restoreNeverGrantsWhatWasNotThere() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "b"), pkg -> "a".equals(pkg));
    assertEquals(asList("a"), m.toGrant());
    assertEquals(asList("b"), m.toDeny());
}

@Test public void aPartialRestoreKeepsTheRest() {
    MicState m = new MicState(store);
    m.rememberBefore(asList("a", "b"), pkg -> true);
    m.restored("a");
    assertEquals("saved", m.status());
    assertEquals(asList("b"), m.remaining());
    m.restored("b");
    assertEquals("none", m.status());
}
```

- [ ] **Krok 2: czerwone**
- [ ] **Krok 3: implementacja** - `mic_release` woła `rememberBefore` przed każdą zmianą, `mic_restore` odtwarza wpisy i kasuje tylko te odtworzone; rozszerzona lista celów to inne istotne argumenty, więc wymaga nowej zgody.
- [ ] **Krok 4: zielone i commit** - `feat(bridge): mic_release and mic_restore with a versioned per-package record that survives a restart`

---

## Zadanie 12: cicha instalacja

**Pliki:**
- Zmień: `BridgeExecutor.java`, `dash/.../Updater.java`, `dash/.../ReleaseInfo.java`, `dash/app/src/main/AndroidManifest.xml` (FileProvider)
- Testy: `bridge/InstallGuardTest.java` (sc2t), `UpdaterSourceTest.java` (Helios)

- [ ] **Krok 1: testy sc2t**

```java
@Test public void onlyTheCallersOwnStrictlyNewerBuildIsAccepted() {
    assertEquals("unsupported", guard(apk("pl.inne", 30, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertEquals("unsupported", guard(apk("pl.mateusz.helios", 29, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertEquals("unsupported", guard(apk("pl.mateusz.helios", 30, "BB"), installed("pl.mateusz.helios", 29, "AA")).error);
    assertNull(guard(apk("pl.mateusz.helios", 30, "AA"), installed("pl.mateusz.helios", 29, "AA")).error);
}

@Test public void theDigestOfTheCopyDecides() {
    assertEquals("failed", guardWithExpected("sha-of-something-else").error);
}

@Test public void theLockGoesDownWhenNoInstallerStarted() {
    InstallOutcome o = runInstall(failingCopy());
    assertEquals("failed", o.status);
    assertFalse("nothing is installing, so nothing may hold the lock", o.lockHeld);
}
```

- [ ] **Krok 2: testy Heliosa**

```java
@Test public void theUpdaterCanPointAtASecondRepository() {
    ReleaseInfo.Source tool = new ReleaseInfo.Source("SychPL/smartclock2tool", "smartclock2tool-%s.apk", "pl.mateusz.clockadbprobe");
    assertEquals("https://api.github.com/repos/SychPL/smartclock2tool/releases/latest", tool.apiUrl());
    assertNotNull(ReleaseInfo.parse(release("v2.19", "smartclock2tool-2.19.apk"), tool));
    assertNull("an asset of the other project is not ours", ReleaseInfo.parse(release("v2.19", "helios-2.19.apk"), tool));
}
```

- [ ] **Krok 3: implementacja** - kopiowanie z limitem, odczyt z kopii, instalacja kanałem roota, sprzątanie wyłącznie plików wpisów terminalnych.
- [ ] **Krok 4: zielone po obu stronach**
- [ ] **Krok 5: commit** - `feat(bridge): install_apk through the root channel, and an updater that can fetch the tool as well`

---

## Zadanie 13: dokumentacja i wydania

- [ ] **Krok 1: dokument API w sc2t** - `docs/BRIDGE-API.md` po angielsku: akcja, pola, operacje, kody, przykłady, odesłanie do SPEC 0.12.
- [ ] **Krok 2: README obu aplikacji** - sekcja o mostku, wprost mówiąca, że Helios nie dostaje roota.
- [ ] **Krok 3: `docs/INSTALL.md` w Heliosie** - opis ścieżki: TalkBack, Helios, menu narzędzi.
- [ ] **Krok 4: wydania** - sc2t 2.19 i Helios 0.10.0, oba z opisem mostka.
- [ ] **Krok 5: commit i tagi**

---

## Zadanie 14: weryfikacja na sprzęcie

Kolejność jest istotna: każdy punkt zakłada poprzedni.

- [ ] **Krok 1: czysty stan** - odciąć zasilanie zegara, włączyć, sprawdzić, że ADB nie odpowiada i że Helios wstaje (albo nie, jeśli nie jest jeszcze ekranem głównym).
- [ ] **Krok 2: instalacja narzędzia z menu Heliosa** - pozycja "Zainstaluj narzędzia", potwierdzenie systemowe, zapamiętany odcisk.
- [ ] **Krok 3: `state` bez roota** - pola `unknown` tam, gdzie trzeba, `mic_holders` nie jest pustą listą.
- [ ] **Krok 4: `root_adb_on`** - ekran zgody z ostrzeżeniem, log postępu, `adb connect` z komputera działa.
- [ ] **Krok 5: `grant_permission` i `write_settings`** - Helios przestaje prosić o mikrofon, `canWrite()` jest prawdziwe.
- [ ] **Krok 6: `mic_release`** - sesja nagrywania Heliosa raportuje `1ch 16000Hz`, hasło wybudzające działa, `mic_restore` przywraca "Hey Google".
- [ ] **Krok 7: `set_home`, restart zasilania** - zegar wstaje z Heliosem.
- [ ] **Krok 8: `adb_off`** - połączenie z innego urządzenia odrzucane; jeśli samo zerowanie właściwości nie wystarcza, zatrzymanie demona i zapis wyniku w dokumentacji sc2t (SPEC 0.12 pkt 10.7).
- [ ] **Krok 9: `install_apk`** - Helios aktualizuje się bez dotykania ekranu, potwierdzenie po restarcie procesu.
- [ ] **Krok 10: przerwania** - odciąć zasilanie w trakcie łańcucha, sprawdzić etap `interrupted` i to, że ponowienie wymaga stanu `idle`.
- [ ] **Krok 11: artefakt** - `dash/artifacts/tools-bridge-results.md` z wynikami każdego kroku i z odpowiedzią na dwa pytania otwarte ze specyfikacji.

---

## Kolejność i bramki

Zadania 1-7 to sc2t, 8-9 to Helios, 10-12 obie strony, 13-14 zamknięcie. Po zadaniu 7 i 9 całość ma już sens użytkowy i wolno ją wydać. Przed każdym commitem: testy jednostkowe obu projektów, `assembleDebug` obu, `lintDebug` w Heliosie. Przed zadaniem 14 wymagany jest działający zegar z zainstalowanym narzędziem i naładowanym akumulatorem, bo część kroków wymaga odcięcia zasilania.
