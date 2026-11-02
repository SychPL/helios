package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

/** Adapted from Mark Graham's 20a1b92912bbd3f7c9091f32235ba01edf57361c. */
public class PredbatCardTest {
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}
    private DashboardSpec spec() throws Exception {
        return DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new JSONArray().put(
                DashboardSpecV6Test.page("office",null,DashboardSpecV6Test.item("plan","predbat",1,1,1,1).put("entity","predbat.status")))));
    }
    private CardBodies.CardContent render(String status,String detail) throws Exception {
        return CardBodies.FOR.get("predbat").render(spec().items.get(0),
                Collections.singletonMap("predbat.status",new EntityStates.Entity(status,Collections.singletonMap("detail",detail))),true,null);
    }
    @Test public void currentTargetIsShownWithoutAddingAnAction() throws Exception {
        assertTrue(spec().attributes().get("predbat.status").contains("detail"));
        assertFalse(spec().items.get(0).interactive());
        assertEquals("23% → 100%",render("Charging"," target 23%-100%").detail);
        assertEquals("81% → 67%",render("Exporting","target 81%-67%").detail);
    }
    @Test public void demandDoesNotInventATarget() throws Exception {
        assertEquals("Zwykłe zużycie własne",render("Demand","").detail);
        assertEquals("",render("Charging","").detail);
        assertEquals("Wstrzymane ładowanie",render("Hold charging","").value);
        assertEquals("target Charge 23%-100% / Export 80%-50%",render("Mixed charge/export","target Charge 23%-100% / Export 80%-50%").detail);
    }
}
