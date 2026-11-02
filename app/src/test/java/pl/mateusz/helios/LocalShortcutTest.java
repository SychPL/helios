package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Adapted from Mark Graham's c61dcafc2ee75bdca99914c785a6545a80d60eeb. */
public class LocalShortcutTest {
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}
    private JSONObject doc(JSONObject card) throws Exception {
        return new JSONObject().put("version",6).put("pages",new JSONArray().put(DashboardSpecV6Test.page("office",null,card)));
    }
    @Test public void spotifyIsLocalAndCannotTargetAnotherPackage() throws Exception {
        DashboardSpec.Item card=DashboardSpec.parse(doc(DashboardSpecV6Test.item("spotify","spotify",1,1,1,1))).items.get(0);
        assertEquals(ActionPolicy.Panel.SPOTIFY,ActionPolicy.panel(card.action));
        assertFalse(ActionPolicy.needsKnown(card.action));
        assertNull(ActionPolicy.call(card,"Spotify"));
        assertTrue(card.interactive());
        try {
            DashboardSpec.parse(doc(DashboardSpecV6Test.item("spotify","spotify",1,1,1,1).put("package","other.app")));
            fail("An arbitrary application target was accepted");
        } catch(IllegalArgumentException expected) {assertTrue(expected.getMessage().contains("package"));}
    }
    @Test public void assistantUsesTheExistingVoiceAction() throws Exception {
        DashboardSpec.Item card=DashboardSpec.parse(doc(DashboardSpecV6Test.item("assistant","assistant",1,1,1,1).put("entity","sensor.voice"))).items.get(0);
        assertEquals(ActionPolicy.Panel.ASSISTANT,ActionPolicy.panel(card.action));
        assertNull(ActionPolicy.call(card,"Assistant"));
        assertTrue(card.interactive());
        assertEquals("sensor.voice",card.entity);
    }
}
