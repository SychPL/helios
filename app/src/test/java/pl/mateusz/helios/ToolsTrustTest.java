package pl.mateusz.helios;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolsTrustTest {
    private static ToolsTrust.Installed installed(String fingerprint, int versionCode) {
        return new ToolsTrust.Installed(fingerprint, versionCode);
    }

    @Test
    public void theToolIsNotTrustedJustBecauseItIsInstalled() {
        assertEquals(ToolsTrust.Status.NEEDS_ACCEPT, ToolsTrust.check(installed("AA", 32), null));
        assertEquals(ToolsTrust.Status.NEEDS_ACCEPT, ToolsTrust.check(installed("AA", 32), ""));
    }

    @Test
    public void aChangedFingerprintStopsEverythingUntilAcceptedAgain() {
        assertEquals(ToolsTrust.Status.CHANGED, ToolsTrust.check(installed("BB", 32), "AA"));
        assertFalse("a changed tool may not receive a call or a file",
                ToolsTrust.mayCall(ToolsTrust.check(installed("BB", 32), "AA")));
    }

    @Test
    public void theBuildWithoutTheBridgeIsTooOld() {
        assertEquals("31 is the last build without a bridge", ToolsTrust.Status.TOO_OLD,
                ToolsTrust.check(installed("AA", 31), "AA"));
        assertEquals(ToolsTrust.Status.OK, ToolsTrust.check(installed("AA", 32), "AA"));
        assertEquals(ToolsTrust.Status.OK, ToolsTrust.check(installed("AA", 40), "AA"));
    }

    @Test
    public void nothingInstalledIsANormalState() {
        assertEquals(ToolsTrust.Status.ABSENT, ToolsTrust.check(null, "AA"));
        assertFalse(ToolsTrust.mayCall(ToolsTrust.Status.ABSENT));
        assertFalse("and it is not an error the user has to fix", ToolsTrust.explain(ToolsTrust.Status.ABSENT).isEmpty());
    }

    @Test
    public void onlyAnAcceptedCurrentToolMayBeCalled() {
        assertTrue(ToolsTrust.mayCall(ToolsTrust.check(installed("AA", 32), "AA")));
        assertFalse(ToolsTrust.mayCall(ToolsTrust.Status.NEEDS_ACCEPT));
        assertFalse(ToolsTrust.mayCall(ToolsTrust.Status.TOO_OLD));
        assertEquals("", ToolsTrust.explain(ToolsTrust.Status.OK));
    }
}
