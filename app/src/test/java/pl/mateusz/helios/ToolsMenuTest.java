package pl.mateusz.helios;

import org.junit.Test;

import java.util.List;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolsMenuTest {
    /** A rooted, accepted tool with nothing wrong: the baseline every test bends. */
    private static Builder state() {
        return new Builder();
    }

    static final class Builder {
        ToolsTrust.Status trust = ToolsTrust.Status.OK;
        Boolean root = Boolean.TRUE;
        Boolean adbListening = Boolean.FALSE;
        Boolean firmwareSupported = Boolean.TRUE;
        Integer micHolders = 0;
        boolean micSaved;
        boolean recordAudio = true;
        boolean canWriteSettings = true;
        boolean isHome = true;
        boolean declaresHome = true;

        Builder withoutTool() { trust = ToolsTrust.Status.ABSENT; return this; }
        Builder toolChanged() { trust = ToolsTrust.Status.CHANGED; return this; }
        Builder toolTooOld() { trust = ToolsTrust.Status.TOO_OLD; return this; }
        Builder notRooted() { root = Boolean.FALSE; return this; }
        Builder rootUnknown() { root = null; return this; }
        Builder adbListening() { adbListening = Boolean.TRUE; return this; }
        Builder micUnknown() { micHolders = null; return this; }
        Builder micHeld() { micHolders = 1; return this; }
        Builder micSaved() { micSaved = true; return this; }
        Builder withoutRecordAudio() { recordAudio = false; return this; }
        Builder cannotWriteSettings() { canWriteSettings = false; return this; }
        Builder notHome() { isHome = false; return this; }
        Builder foreignFirmware() { firmwareSupported = Boolean.FALSE; return this; }

        List<String> items() {
            return ToolsMenu.items(new ToolsState(trust, root, adbListening, firmwareSupported, micHolders,
                    micSaved, recordAudio, canWriteSettings, isHome, declaresHome));
        }
    }

    @Test
    public void withoutTheToolOnlyInstallIsOffered() {
        assertEquals(singletonList(ToolsMenu.INSTALL), state().withoutTool().items());
    }

    @Test
    public void anUnacceptedOrChangedToolOffersOnlyTheAcceptance() {
        assertEquals(singletonList(ToolsMenu.ACCEPT), state().toolChanged().items());
        assertEquals(singletonList(ToolsMenu.UPDATE), state().toolTooOld().items());
    }

    @Test
    public void theChainIsOfferedWhileRootIsNotUpAndHiddenOnAForeignFirmware() {
        assertTrue(state().notRooted().items().contains(ToolsMenu.ROOT_AND_ADB));
        assertTrue("an unknown root state still offers the repair", state().rootUnknown().items().contains(ToolsMenu.ROOT_AND_ADB));
        assertFalse(state().items().contains(ToolsMenu.ROOT_AND_ADB));
        assertFalse("a firmware that would refuse does not get the entry",
                state().notRooted().foreignFirmware().items().contains(ToolsMenu.ROOT_AND_ADB));
    }

    @Test
    public void adbOnAndAdbOffNeverShowTogether() {
        List<String> listening = state().adbListening().items();
        assertTrue(listening.contains(ToolsMenu.ADB_OFF));
        assertFalse(listening.contains(ToolsMenu.ADB_ON));

        List<String> silent = state().items();
        assertTrue(silent.contains(ToolsMenu.ADB_ON));
        assertFalse(silent.contains(ToolsMenu.ADB_OFF));
    }

    @Test
    public void anUnknownMicStateKeepsTheRepairVisible() {
        assertTrue(state().micUnknown().items().contains(ToolsMenu.MIC_FIX));
        assertTrue(state().micHeld().items().contains(ToolsMenu.MIC_FIX));
        assertFalse("nobody holds it and we know that", state().items().contains(ToolsMenu.MIC_FIX));
    }

    @Test
    public void restoreShowsUpOnlyWithASavedState() {
        assertFalse(state().items().contains(ToolsMenu.MIC_RESTORE));
        assertTrue(state().micSaved().items().contains(ToolsMenu.MIC_RESTORE));
    }

    @Test
    public void permissionItemsFollowLocalMeasurements() {
        assertTrue(state().withoutRecordAudio().items().contains(ToolsMenu.PERMISSION_MIC));
        assertFalse(state().items().contains(ToolsMenu.PERMISSION_MIC));

        assertTrue(state().cannotWriteSettings().items().contains(ToolsMenu.ALLOW_BRIGHTNESS));
        assertFalse(state().items().contains(ToolsMenu.ALLOW_BRIGHTNESS));

        assertTrue(state().notHome().items().contains(ToolsMenu.SET_HOME));
        assertFalse(state().items().contains(ToolsMenu.SET_HOME));
    }

    @Test
    public void nothingThatNeedsRootIsOfferedWithoutRoot() {
        List<String> items = state().notRooted().withoutRecordAudio().cannotWriteSettings().notHome().micHeld().items();
        assertTrue(items.contains(ToolsMenu.ROOT_AND_ADB));
        assertFalse(items.contains(ToolsMenu.PERMISSION_MIC));
        assertFalse(items.contains(ToolsMenu.ALLOW_BRIGHTNESS));
        assertFalse(items.contains(ToolsMenu.SET_HOME));
        assertFalse(items.contains(ToolsMenu.MIC_FIX));
    }

    @Test
    public void aSnapshotThatMeasuredNothingLeavesEverythingUnknown() {
        ToolsState state = ToolsState.from(ToolsTrust.Status.OK,
                "{\"root\":\"unknown\",\"mic_holders\":\"unknown\"}", true, true, true, true);
        assertFalse(state.rooted());
        assertTrue("unknown mic state is a maybe, not a no", state.micNeedsRepair());
        assertTrue(ToolsMenu.items(state).contains(ToolsMenu.ROOT_AND_ADB));
    }

    @Test
    public void aRealSnapshotIsReadCorrectly() {
        String snapshot = "{\"root\":true,\"adb_listening\":true,\"firmware_supported\":true,"
                + "\"mic_holders\":[\"com.google.android.apps.mediashell\"],\"mic_saved_state\":\"saved\"}";
        ToolsState state = ToolsState.from(ToolsTrust.Status.OK, snapshot, false, false, false, true);
        List<String> items = ToolsMenu.items(state);
        assertTrue(items.contains(ToolsMenu.ADB_OFF));
        assertTrue(items.contains(ToolsMenu.MIC_FIX));
        assertTrue(items.contains(ToolsMenu.MIC_RESTORE));
        assertTrue(items.contains(ToolsMenu.PERMISSION_MIC));
        assertTrue(items.contains(ToolsMenu.ALLOW_BRIGHTNESS));
        assertTrue(items.contains(ToolsMenu.SET_HOME));
    }
}
