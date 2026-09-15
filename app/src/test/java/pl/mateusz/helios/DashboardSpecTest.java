package pl.mateusz.helios;

import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class DashboardSpecTest {
    static JSONObject item(String id,String type,int column,int row,int width,int height) throws Exception {
        return new JSONObject().put("id",id).put("type",type).put("column",column).put("row",row).put("width",width).put("height",height);
    }
    static JSONObject example() throws Exception {
        JSONArray items=new JSONArray()
            .put(item("clock","clock",1,1,2,1).put("title","Dom"))
            .put(item("weather","weather",3,1,2,1).put("entity","weather.forecast_dom").put("temperature_entity","sensor.temperatura_salon"))
            .put(item("rain-info","entity",1,2,2,1).put("entity","sensor.helios_rain_message").put("icon","weather-rainy").put("visible_when",new JSONObject().put("entity","binary_sensor.helios_show_rain_daytime").put("state","on")))
            .put(item("living-room-light","light",3,2,1,1).put("entity","light.salon").put("tap_action",new JSONObject().put("action","toggle")))
            .put(item("living-room-cover","cover",4,2,1,1).put("entity","cover.roleta_salon"))
            .put(item("garage","garage",1,3,4,1).put("entity","cover.brama_garazowa").put("title","Brama otwarta")
                .put("visible_when",new JSONObject().put("entity","binary_sensor.helios_show_open_garage_evening").put("state","on"))
                .put("confirmation",new JSONObject().put("enabled",true).put("text","Zamknąć bramę garażową?")));
        return new JSONObject().put("version",2).put("grid",new JSONObject().put("columns",4).put("rows",3)).put("items",items);
    }
    private static void rejects(JSONObject config,String why){
        try{DashboardSpec.parse(config);fail("accepted: "+why);}catch(IllegalArgumentException expected){}catch(Exception other){fail(why+": "+other);}
    }
    private static JSONObject with(String id,String key,Object value) throws Exception {
        JSONObject c=example();JSONArray items=c.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals(id))items.getJSONObject(i).put(key,value);
        return c;
    }

    @Test public void specExampleParsesWithDefaultsPerType() throws Exception {
        DashboardSpec spec=DashboardSpec.parse(new JSONObject(example().toString()));
        assertEquals(6,spec.items.size());
        assertEquals("toggle",spec.item("living-room-light").action);assertFalse(spec.item("living-room-light").confirm);assertEquals("lightbulb",spec.item("living-room-light").icon);
        assertEquals("controls",spec.item("living-room-cover").action);assertEquals("window-shutter",spec.item("living-room-cover").icon);
        assertEquals("close",spec.item("garage").action);assertTrue(spec.item("garage").confirm);assertEquals("Zamknąć bramę garażową?",spec.item("garage").confirmText);
        assertNull(spec.item("clock").action);assertNull(spec.item("clock").icon);assertNull(spec.item("weather").icon);
        assertEquals("weather-rainy",spec.item("rain-info").icon);
        assertEquals(Arrays.asList("weather.forecast_dom","sensor.temperatura_salon","sensor.helios_rain_message","binary_sensor.helios_show_rain_daytime","light.salon","cover.roleta_salon","cover.brama_garazowa","binary_sensor.helios_show_open_garage_evening"),spec.entities());
        assertEquals(new HashSet<>(DashboardSpec.WEATHER_ATTRIBUTES),spec.attributes().get("weather.forecast_dom"));
        assertNull(spec.attributes().get("sensor.helios_rain_message"));
        assertEquals(Collections.singleton("current_position"),spec.attributes().get("cover.roleta_salon"));assertEquals(Collections.singleton("current_position"),spec.attributes().get("cover.brama_garazowa"));
    }
    @Test public void garageConfirmationCanBeDisabledExplicitlyAndAttributeIsRetained() throws Exception {
        DashboardSpec spec=DashboardSpec.parse(with("garage","confirmation",new JSONObject().put("enabled",false)));
        assertFalse(spec.item("garage").confirm);
        spec=DashboardSpec.parse(with("rain-info","attribute","message"));
        assertEquals(Collections.singleton("message"),spec.attributes().get("sensor.helios_rain_message"));
    }
    @Test public void fallbackIsAClockOverTheWholeGrid() {
        DashboardSpec.Item clock=DashboardSpec.fallback().items.get(0);
        assertEquals("clock",clock.type);assertEquals(4,clock.width);assertEquals(3,clock.height);assertTrue(DashboardSpec.fallback().entities().isEmpty());
    }
    @Test public void visibilityNeedsAnExactLiveState() throws Exception {
        DashboardSpec.Item garage=DashboardSpec.parse(example()).item("garage");
        assertTrue(garage.visible(new EntityStates.Entity("on",new HashMap<>())));
        assertFalse(garage.visible(new EntityStates.Entity("off",new HashMap<>())));
        assertFalse(garage.visible(new EntityStates.Entity("unavailable",new HashMap<>())));
        assertFalse(garage.visible(null));
        assertTrue(DashboardSpec.parse(example()).item("clock").visible(null));
    }
    @Test public void rejectsWrongVersionAndGrid() throws Exception {
        rejects(example().put("version",1),"version 1");
        rejects(example().put("version","2"),"version as text");
        rejects(example().put("grid",new JSONObject().put("columns",3).put("rows",3)),"3 columns");
        rejects(example().put("extra",1),"unknown root field");
        JSONObject c=example();c.remove("grid");rejects(c,"missing grid");
    }
    @Test public void rejectsGeometryErrors() throws Exception {
        rejects(with("garage","width",5),"outside grid");
        rejects(with("garage","row",2),"overlap");
        rejects(with("garage","column",0),"column 0");
        rejects(with("garage","column",1.5),"fractional column");
        JSONObject c=example();c.getJSONArray("items").put(item("clock","clock",4,3,1,1));rejects(c,"duplicate id");
        c=example();for(int i=0;i<12;i++)c.getJSONArray("items").put(item("x"+i,"clock",1,1,1,1));rejects(c,"too many items");
    }
    @Test public void rejectsTypeMismatchesAndForbiddenFields() throws Exception {
        rejects(with("living-room-light","entity","switch.salon"),"light with switch entity");
        rejects(with("garage","entity","binary_sensor.brama"),"garage with sensor");
        rejects(with("weather","entity","sensor.pogoda"),"weather with sensor");
        rejects(with("living-room-light","tap_action",new JSONObject().put("action","close")),"wrong action");
        rejects(with("living-room-light","tap_action",new JSONObject().put("action","toggle").put("service","light.turn_on")),"extra tap_action field");
        rejects(with("rain-info","confirmation",new JSONObject().put("enabled",true)),"confirmation on entity");
        rejects(with("rain-info","tap_action",new JSONObject().put("action","toggle")),"tap_action on entity");
        rejects(with("clock","icon","information"),"icon on clock");
        rejects(with("weather","icon","weather-rainy"),"icon on weather");
        rejects(with("weather","attribute","temperature"),"attribute on weather");
        rejects(with("living-room-light","temperature_entity","sensor.x"),"temperature_entity on light");
        rejects(with("living-room-light","icon","alert"),"unknown icon");
        rejects(with("living-room-light","icn","lightbulb"),"typo field");
        rejects(with("rain-info","attribute","Bad-Name"),"attribute format");
        rejects(with("weather","temperature_entity","number.temp"),"temperature_entity domain");
        rejects(with("rain-info","title","0123456789012345678901234567890123456789X"),"title too long");
        JSONObject c=example();c.getJSONArray("items").put(item("id with space","clock",4,3,1,1));rejects(c,"bad id");
    }
    @Test public void rejectsBrokenVisibilityAndConfirmation() throws Exception {
        rejects(with("garage","visible_when",new JSONObject().put("entity","binary_sensor.x")),"visible_when without state");
        rejects(with("garage","visible_when",new JSONObject().put("entity","binary_sensor.x").put("state","on").put("attribute","y")),"visible_when extra field");
        rejects(with("garage","visible_when",new JSONObject().put("entity","binary_sensor.x").put("state","unavailable")),"unavailable as visible");
        rejects(with("garage","confirmation",new JSONObject().put("text","?")),"confirmation without enabled");
        rejects(with("garage","confirmation",new JSONObject().put("enabled","yes")),"enabled as text");
        rejects(with("garage","confirmation",new JSONObject().put("enabled",true).put("text",new String(new char[81]).replace('\0','a'))),"text too long");
    }
    @Test public void statesKeepOnlyRequestedAttributesAndForgetRemovedOnes() throws Exception {
        Map<String,Set<String>> wanted=new HashMap<>();wanted.put("weather.dom",new HashSet<>(Arrays.asList("temperature","temperature_unit")));
        EntityStates states=new EntityStates(wanted);
        states.apply(new JSONObject("{\"a\":{\"weather.dom\":{\"s\":\"rainy\",\"a\":{\"temperature\":12.5,\"temperature_unit\":\"°C\",\"humidity\":80}},\"light.x\":{\"s\":\"on\",\"a\":{\"brightness\":10}}}}"));
        EntityStates.Entity weather=states.snapshot().get("weather.dom");
        assertEquals("rainy",weather.state);assertEquals("12.5",weather.attribute("temperature"));assertNull(weather.attribute("humidity"));
        assertTrue(states.snapshot().get("light.x").attributes.isEmpty());
        states.apply(new JSONObject("{\"c\":{\"weather.dom\":{\"+\":{\"a\":{\"temperature\":13}}}}}"));
        assertEquals("rainy",states.snapshot().get("weather.dom").state);assertEquals("13",states.snapshot().get("weather.dom").attribute("temperature"));
        states.apply(new JSONObject("{\"c\":{\"weather.dom\":{\"+\":{\"s\":\"unavailable\"},\"-\":{\"a\":[\"temperature\"]}}}}"));
        assertFalse(states.snapshot().get("weather.dom").known());assertNull(states.snapshot().get("weather.dom").attribute("temperature"));
        states.apply(new JSONObject("{\"r\":[\"weather.dom\"]}"));assertFalse(states.snapshot().containsKey("weather.dom"));
    }
}
