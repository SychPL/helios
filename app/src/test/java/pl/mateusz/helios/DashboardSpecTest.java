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
        assertEquals(Collections.singleton("unit_of_measurement"),spec.attributes().get("sensor.temperatura_salon"));
        assertEquals(new HashSet<>(DashboardSpec.COVER_ATTRIBUTES),spec.attributes().get("cover.roleta_salon"));assertEquals(new HashSet<>(DashboardSpec.COVER_ATTRIBUTES),spec.attributes().get("cover.brama_garazowa"));
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
    @Test public void schemaThreeAllowsOneMusicTileWithoutEntityAndForbidsMusicLayout() throws Exception {
        JSONObject c=example().put("version",3);c.getJSONArray("items").getJSONObject(5).put("width",3);c.getJSONArray("items").put(item("music","music",4,3,1,1));
        DashboardSpec spec=DashboardSpec.parse(new JSONObject(c.toString()));
        assertEquals(3,spec.version);assertEquals(7,spec.items.size());
        DashboardSpec.Item music=spec.item("music");
        assertNull(music.entity);assertTrue(music.interactive());assertEquals("music",music.icon);assertEquals("library",music.action);
        JSONObject two=new JSONObject().put("version",3).put("grid",new JSONObject().put("columns",4).put("rows",3)).put("items",new JSONArray().put(item("m1","music",1,1,1,1)).put(item("m2","music",2,1,1,1)));rejects(two,"two music tiles");
        rejects(example().put("version",2).put("items",new JSONArray().put(item("music","music",1,1,1,1))),"music at version 2");
        rejects(new JSONObject(c.toString()).put("music_layout",new JSONObject()),"music_layout");
        JSONObject tap=new JSONObject(c.toString());tap.getJSONArray("items").getJSONObject(6).put("tap_action",new JSONObject().put("action","library"));rejects(tap,"tap_action on music");
        JSONObject withEntity=new JSONObject(c.toString());withEntity.getJSONArray("items").getJSONObject(6).put("entity","media_player.x");rejects(withEntity,"entity on music");
        assertEquals(2,DashboardSpec.parse(example()).version);
    }
    /** SPEC 0.9 pkt 7.3: the three changed tiles plus the clock and four bottom notifications. */
    static JSONObject exampleV4() throws Exception {
        JSONArray items=new JSONArray()
            .put(item("clock","clock",1,1,2,2).put("title","Dom"))
            .put(item("weather","weather",3,1,2,1).put("entity","weather.forecast_dom").put("title","Pogoda").put("forecast_entity","sensor.helios_pogoda_jutro")
                .put("forecast_when",new JSONObject().put("entity","binary_sensor.helios_pogoda_jutro_tryb").put("state","on")))
            .put(item("bedroom-covers","cover_group",3,2,1,1).put("title","Rolety").put("covers",new JSONArray()
                .put(new JSONObject().put("entity","cover.bedroom_main_cover_a").put("title","Roleta A"))
                .put(new JSONObject().put("entity","cover.bedroom_main_cover_b").put("title","Roleta B"))))
            .put(item("bedroom-light","light",4,2,1,1).put("title","Światło sypialni").put("entity","light.bedroom_a_all")
                .put("visible_when",new JSONObject().put("entity","binary_sensor.helios_sypialnia_swiatlo_pokaz").put("state","on")));
        String[][] bottom={{"lights-watched","sensor.helios_zapalone_swiatla","lightbulb"},{"garage-attention","sensor.helios_garaz_uwaga","garage-open"},{"shed-attention","sensor.helios_blaszak_uwaga","information"},{"courier","sensor.helios_wiking_godzina","information"}};
        for(int i=0;i<4;i++)items.put(item(bottom[i][0],"entity",i+1,3,1,1).put("entity",bottom[i][1]).put("icon",bottom[i][2]).put("visible_when",new JSONObject().put("entity",bottom[i][1]+"_pokaz").put("state","on")));
        return new JSONObject().put("version",4).put("grid",new JSONObject().put("columns",4).put("rows",3)).put("items",items);
    }
    private static JSONObject v4with(String id,String key,Object value) throws Exception {
        JSONObject c=exampleV4();JSONArray items=c.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals(id)){if(value==null)items.getJSONObject(i).remove(key);else items.getJSONObject(i).put(key,value);}
        return c;
    }
    private static JSONArray covers(String... pairs) throws Exception {
        JSONArray out=new JSONArray();for(int i=0;i+1<pairs.length;i+=2)out.put(new JSONObject().put("entity",pairs[i]).put("title",pairs[i+1]));return out;
    }
    @Test public void schemaFourAddsCoverGroupAndTomorrowForecast() throws Exception {
        DashboardSpec spec=DashboardSpec.parse(exampleV4());
        assertEquals(4,spec.version);assertEquals(8,spec.items.size());
        DashboardSpec.Item covers=spec.item("bedroom-covers");
        assertEquals("covers",covers.action);assertEquals("window-shutter",covers.icon);assertNull(covers.entity);assertFalse(covers.confirm);
        assertEquals(2,covers.covers.size());assertEquals("cover.bedroom_main_cover_a",covers.covers.get(0).entity);assertEquals("Roleta B",covers.covers.get(1).title);
        DashboardSpec.Item weather=spec.item("weather");
        assertEquals("sensor.helios_pogoda_jutro",weather.forecastEntity);assertEquals("binary_sensor.helios_pogoda_jutro_tryb",weather.forecastWhenEntity);assertEquals("on",weather.forecastWhenState);
        List<String> entities=spec.entities();
        // A null here would reach HA as a malformed subscribe_entities and cost the clock its whole session.
        assertFalse(entities.contains(null));
        for(String e:new String[]{"cover.bedroom_main_cover_a","cover.bedroom_main_cover_b","light.bedroom_a_all","binary_sensor.helios_sypialnia_swiatlo_pokaz","binary_sensor.helios_pogoda_jutro_tryb","sensor.helios_pogoda_jutro","weather.forecast_dom"})assertTrue(e,entities.contains(e));
        Map<String,Set<String>> attributes=spec.attributes();
        assertEquals(new HashSet<>(Arrays.asList("current_position","supported_features")),attributes.get("cover.bedroom_main_cover_a"));
        assertEquals(new HashSet<>(Arrays.asList("current_position","supported_features")),attributes.get("cover.bedroom_main_cover_b"));
        assertEquals(new HashSet<>(DashboardSpec.FORECAST_ATTRIBUTES),attributes.get("sensor.helios_pogoda_jutro"));
        assertNull(attributes.get("binary_sensor.helios_pogoda_jutro_tryb"));
        // the old single cover keeps the same attribute list (one contract per domain)
        assertEquals(new HashSet<>(Arrays.asList("current_position","supported_features")),DashboardSpec.parse(example()).attributes().get("cover.roleta_salon"));
        DashboardSpec plain=DashboardSpec.parse(exampleV4().put("version",4));assertNull(plain.item("bedroom-light").forecastEntity);assertTrue(plain.item("bedroom-light").covers.isEmpty());
    }
    /** SPEC 0.12: the "Światła" tile stays a read-only sensor tile but may turn a light group off, always after a question. */
    private static JSONObject exampleV5() throws Exception {
        JSONObject c=exampleV4().put("version",5);JSONArray items=c.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals("lights-watched"))items.getJSONObject(i).put("off_entity","light.helios_swiatla_do_sprawdzenia");
        return c;
    }
    @Test public void schemaFiveMakesTheLightsTileTurnItsGroupOffAfterAConfirmation() throws Exception {
        DashboardSpec spec=DashboardSpec.parse(exampleV5());
        assertEquals(5,spec.version);
        DashboardSpec.Item lights=spec.item("lights-watched");
        assertEquals("light.helios_swiatla_do_sprawdzenia",lights.offEntity);
        assertEquals("sensor.helios_zapalone_swiatla",lights.entity);
        assertEquals("lights_off",lights.action);
        assertTrue("a tile that turns lights off must be tappable",lights.interactive());
        assertTrue("the question is never optional by default",lights.confirm);
        assertNull(lights.confirmText);
        // the other read-only tiles stay untouchable
        assertFalse(spec.item("garage-attention").interactive());
        assertNull(spec.item("garage-attention").offEntity);
        // an own question text is allowed, switching the question off is allowed too (the user owns the dashboard)
        JSONObject own=exampleV5();JSONArray items=own.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals("lights-watched"))
            items.getJSONObject(i).put("confirmation",new JSONObject().put("enabled",true).put("text","Zgasić wszystkie światła?")).put("tap_action",new JSONObject().put("action","lights_off"));
        assertEquals("Zgasić wszystkie światła?",DashboardSpec.parse(own).item("lights-watched").confirmText);
    }
    @Test public void schemaFiveRejectsMalformedOffEntities() throws Exception {
        rejects(exampleV5().put("version",4),"off_entity at version 4");
        JSONObject wrongDomain=exampleV5();JSONArray items=wrongDomain.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals("lights-watched"))items.getJSONObject(i).put("off_entity","switch.cokolwiek");
        rejects(wrongDomain,"off_entity outside the light domain");
        JSONObject onClock=exampleV5();onClock.getJSONArray("items").getJSONObject(0).put("off_entity","light.helios_swiatla_do_sprawdzenia");
        rejects(onClock,"off_entity on a clock tile");
        JSONObject wrongTap=exampleV5();items=wrongTap.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals("lights-watched"))items.getJSONObject(i).put("tap_action",new JSONObject().put("action","toggle"));
        rejects(wrongTap,"tap_action other than lights_off");
        JSONObject plainEntityTap=exampleV4().put("version",5);items=plainEntityTap.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals("courier"))items.getJSONObject(i).put("confirmation",new JSONObject().put("enabled",true));
        rejects(plainEntityTap,"confirmation on an entity tile without off_entity");
        rejects(exampleV5().put("version",7),"version 7");
    }
    @Test public void schemaFourRejectsMalformedCoverGroupsAndForecastFields() throws Exception {
        rejects(exampleV4().put("version",3),"cover_group and forecast fields at version 3");
        JSONObject v3=exampleV4().put("version",3);JSONArray items=v3.getJSONArray("items");for(int i=items.length()-1;i>=0;i--)if(items.getJSONObject(i).getString("id").equals("bedroom-covers"))items.remove(i);rejects(v3,"forecast fields at version 3");
        rejects(v4with("bedroom-covers","covers",covers("cover.a","A")),"one cover");
        rejects(v4with("bedroom-covers","covers",covers("cover.a","A","cover.b","B","cover.c","C")),"three covers");
        rejects(v4with("bedroom-covers","covers",covers("cover.a","A","cover.a","B")),"same cover twice");
        rejects(v4with("bedroom-covers","covers",covers("light.a","A","cover.b","B")),"light in covers");
        rejects(v4with("bedroom-covers","covers",new JSONArray().put(new JSONObject().put("entity","cover.a")).put(new JSONObject().put("entity","cover.b").put("title","B"))),"cover without title");
        rejects(v4with("bedroom-covers","covers",new JSONArray().put(new JSONObject().put("entity","cover.a").put("title","A").put("icon","x")).put(new JSONObject().put("entity","cover.b").put("title","B"))),"extra field in cover");
        rejects(v4with("bedroom-covers","covers",null),"cover_group without covers");
        rejects(v4with("bedroom-covers","entity","cover.a"),"entity on cover_group");
        rejects(v4with("bedroom-covers","confirmation",new JSONObject().put("enabled",false)),"confirmation on cover_group");
        rejects(v4with("bedroom-covers","tap_action",new JSONObject().put("action","covers")),"tap_action on cover_group");
        rejects(v4with("weather","forecast_when",null),"forecast_entity without forecast_when");
        rejects(v4with("weather","forecast_entity",null),"forecast_when without forecast_entity");
        rejects(v4with("weather","forecast_entity","binary_sensor.x"),"forecast_entity domain");
        rejects(v4with("weather","forecast_when",new JSONObject().put("entity","binary_sensor.x").put("state","unavailable")),"forecast_when unavailable");
        rejects(v4with("weather","forecast_when",new JSONObject().put("entity","binary_sensor.x").put("state","on").put("extra",1)),"forecast_when extra field");
        rejects(v4with("weather","icon","weather-rainy"),"icon on weather v4");
        rejects(v4with("bedroom-light","forecast_entity","sensor.x"),"forecast_entity on light");
        JSONObject overlap=exampleV4();overlap.getJSONArray("items").put(item("other","light",3,2,1,1).put("entity","light.x"));rejects(overlap,"cell (3,2) used twice");
    }
    @Test public void rejectsWrongVersionAndGrid() throws Exception {
        rejects(example().put("version",1),"version 1");rejects(example().put("version",7),"version 7");
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
