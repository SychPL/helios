package pl.mateusz.helios;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ToolsCallTest {
    private static final String RUNNING_SNAPSHOT =
            "{\"about\":{\"stage\":\"running\",\"stage_since_uptime_ms\":4000,"
                    + "\"now_uptime_ms\":10000,\"boot_id\":\"b1\"}}";

    @Test
    public void inProgressUnknownAndCancelMeanKeepAsking() {
        assertEquals(ToolsCall.Next.POLL, ToolsCall.next("in_progress", false));
        assertEquals("unknown says the tool did not know, not that it failed",
                ToolsCall.Next.POLL, ToolsCall.next("unknown", false));
        assertEquals(ToolsCall.Next.POLL, ToolsCall.next(null, true));
        assertEquals(ToolsCall.Next.POLL, ToolsCall.next(null, false));

        assertEquals(ToolsCall.Next.DONE, ToolsCall.next("ok", false));
        assertEquals(ToolsCall.Next.DONE, ToolsCall.next("failed", false));
        assertEquals(ToolsCall.Next.DONE, ToolsCall.next("busy", false));
        assertEquals(ToolsCall.Next.DONE, ToolsCall.next("denied", false));
        assertEquals(ToolsCall.Next.DONE, ToolsCall.next("wrong_firmware", false));
        assertEquals(ToolsCall.Next.DONE, ToolsCall.next("unsupported", false));
    }

    @Test
    public void everyRequestGetsItsOwnIdentifier() {
        String first = ToolsCall.newOpId();
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]{32}"));
        assertNotEquals(first, ToolsCall.newOpId());
    }

    @Test
    public void aLateResultOfAnotherRequestIsIgnored() {
        assertTrue(ToolsCall.acceptResult("op-mine", "op-mine"));
        assertFalse(ToolsCall.acceptResult("op-other", "op-mine"));
        assertFalse(ToolsCall.acceptResult(null, "op-mine"));
        assertFalse(ToolsCall.acceptResult("op-mine", null));
    }

    @Test
    public void aStateQueryCarriesTheIdOfTheRequestItAsksAbout() {
        assertTrue(ToolsCall.aboutArgs("op-mine").contains("\"about\""));
        assertTrue(ToolsCall.aboutArgs("op-mine").contains("op-mine"));
    }

    @Test
    public void theStageAgeComesFromTheAboutBlockAndNeedsTheSameBoot() {
        assertEquals(6000, ToolsCall.stageAgeMs(RUNNING_SNAPSHOT, "b1"));
        assertEquals("another boot makes the difference meaningless", -1, ToolsCall.stageAgeMs(RUNNING_SNAPSHOT, "b2"));
        assertEquals(-1, ToolsCall.stageAgeMs("{}", "b1"));
        assertEquals(-1, ToolsCall.stageAgeMs("not json at all", "b1"));
        assertEquals("without a known boot we take what the snapshot says", 6000, ToolsCall.stageAgeMs(RUNNING_SNAPSHOT, ""));
    }

    @Test
    public void theStageIsReadableFromTheSnapshot() {
        assertEquals("running", ToolsCall.stageOf(RUNNING_SNAPSHOT));
        assertEquals("", ToolsCall.stageOf("{}"));
    }

    @Test
    public void aStageOfAnotherOperationIsNotOurs() {
        String snapshot = "{\"about\":{\"op_id\":\"op-other\",\"stage\":\"finished\"}}";
        assertEquals("finished", ToolsCall.stageOf(snapshot, "op-other"));
        assertEquals("somebody else finishing says nothing about us", "", ToolsCall.stageOf(snapshot, "op-mine"));
        assertFalse("and it must not let another operation start", ToolsCall.mayStartAnother("op-mine",
                ToolsCall.stageOf(snapshot, "op-mine")));
    }

    @Test
    public void anotherOperationWaitsForATerminalStage() {
        assertFalse(ToolsCall.mayStartAnother("op-mine", "running"));
        assertFalse(ToolsCall.mayStartAnother("op-mine", "awaiting_consent"));
        assertFalse(ToolsCall.mayStartAnother("op-mine", "copying"));
        assertFalse(ToolsCall.mayStartAnother("op-mine", "installing"));
        assertFalse("not knowing is not the same as being over", ToolsCall.mayStartAnother("op-mine", ""));

        assertTrue(ToolsCall.mayStartAnother("op-mine", "finished"));
        assertTrue(ToolsCall.mayStartAnother("op-mine", "interrupted"));
        assertTrue(ToolsCall.mayStartAnother("op-mine", "absent"));
        assertTrue("nothing pending at all is also fine", ToolsCall.mayStartAnother(null, ""));
    }

    @Test
    public void waitingForAHumanHasNoLimitButMachineStagesDo() {
        assertEquals(Long.MAX_VALUE, ToolsCall.limitFor("install_apk", "awaiting_consent"));
        assertEquals(Long.MAX_VALUE, ToolsCall.limitFor("root_adb_on", ""));
        assertEquals(60_000, ToolsCall.limitFor("install_apk", "copying"));
        assertEquals(120_000, ToolsCall.limitFor("install_apk", "installing"));
        assertEquals(240_000, ToolsCall.limitFor("root_adb_on", "running"));
        assertEquals(45_000, ToolsCall.limitFor("adb_off", "running"));
        assertEquals(20_000, ToolsCall.limitFor("set_home", "running"));
    }

    @Test
    public void anEmptyStageNeverCountsAsFinished() {
        assertFalse(ToolsCall.terminal(""));
        assertFalse(ToolsCall.terminal(null));
        assertFalse(ToolsCall.terminal("running"));
        assertTrue(ToolsCall.terminal("finished"));
    }
}
