package pl.mateusz.helios;

import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class DashboardSpecTest {
    private JSONObject config() throws Exception {
        return new JSONObject("{\"version\":1,\"clock\":true,\"weather\":null,\"indicators\":[{\"entity\":\"sensor.test_gate\",\"name\":\"Garaż\",\"label\":\"Garaż otwarty\",\"when\":[\"open\"],\"clear_when\":[\"close\"],\"icon\":\"garage-open\"}]}");
    }
    @Test public void onlyExplicitClearStatesHideWarning() throws Exception {
        DashboardSpec.Indicator i=DashboardSpec.parse(config()).indicators.get(0);
        assertNull(i.display("close",true));
        assertEquals("Garaż otwarty",i.display("open",true));
        assertEquals("Garaż — nieznany stan",i.display("closed",true));
        for(String missing:new String[]{null,"unknown","unavailable"})assertEquals("Garaż — brak danych",i.display(missing,true));
        assertEquals("Garaż — brak danych",i.display("close",false));
        assertEquals("Garaż — brak danych",i.display("open",false));
    }
    @Test public void changingEntityAndRuleRequiresOnlyConfig() throws Exception {
        JSONObject c=config();JSONObject row=c.getJSONArray("indicators").getJSONObject(0);
        row.put("entity","binary_sensor.other").put("when",new JSONArray().put("on")).put("clear_when",new JSONArray().put("off"));
        DashboardSpec spec=DashboardSpec.parse(c);assertEquals("binary_sensor.other",spec.entities().get(0));
        assertNull(spec.indicators.get(0).display("off",true));assertNotNull(spec.indicators.get(0).display("on",true));
    }
    @Test public void rejectsDangerousAndAmbiguousRules() throws Exception {
        for(String state:new String[]{"open","unknown","unavailable"}){
            JSONObject c=config();c.getJSONArray("indicators").getJSONObject(0).put("clear_when",new JSONArray().put(state));
            try{DashboardSpec.parse(c);fail("accepted "+state);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void rejectsUnsupportedVersionAndTypos() throws Exception {
        JSONObject c=config().put("version",2);try{DashboardSpec.parse(c);fail();}catch(IllegalArgumentException expected){}
        c=config().put("indictors",new JSONArray());try{DashboardSpec.parse(c);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void snapshotChangesAndDeletionDoNotRetainStaleState() throws Exception {
        EntityStates states=new EntityStates();states.apply(new JSONObject("{\"a\":{\"sensor.test_gate\":{\"s\":\"close\"}}}"));
        assertEquals("close",states.snapshot().get("sensor.test_gate"));
        states.apply(new JSONObject("{\"c\":{\"sensor.test_gate\":{\"+\":{\"a\":{\"friendly_name\":\"Test\"}}}}}"));
        assertEquals("close",states.snapshot().get("sensor.test_gate"));
        states.apply(new JSONObject("{\"c\":{\"sensor.test_gate\":{\"+\":{\"s\":\"open\"}}}}"));
        assertEquals("open",states.snapshot().get("sensor.test_gate"));
        states.apply(new JSONObject("{\"r\":[\"sensor.test_gate\"]}"));assertFalse(states.snapshot().containsKey("sensor.test_gate"));
    }
}
