package pl.mateusz.helios;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import pl.mateusz.helios.OverlayGeometry.Box;
import java.util.*;
import static org.junit.Assert.*;

/** SPEC 0.19: the climate tile, its panel's model and lifecycle, the call allowlist and the panel grid. */
public class ClimateCardTest {
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}

    /** States as HA reported them on 2026-09-24 (SPEC 0.19 pkt 1). */
    static EntityStates.Entity salon(String state,String action){
        return entity(state,"hvac_modes","[\"heat\",\"off\"]","min_temp","14","max_temp","25","preset_modes","[\"none\",\"away\"]","current_temperature","23",
            "temperature","20.5","hvac_action",action,"preset_mode","none","friendly_name","Salon - Termostat","supported_features","401");
    }
    static EntityStates.Entity gree(){
        return entity("cool","hvac_modes","[\"auto\",\"cool\",\"dry\",\"fan_only\",\"heat\",\"off\"]","min_temp","8","max_temp","30","target_temp_step","1",
            "fan_modes","[\"auto\",\"low\",\"medium low\",\"medium\",\"medium high\",\"high\"]","preset_modes","[\"eco\",\"away\",\"boost\",\"none\",\"sleep\"]",
            "swing_modes","[\"default\",\"full_swing\",\"fixed_upper\",\"fixed_upper_middle\"]","swing_horizontal_modes","[\"default\",\"left\",\"left_center\",\"center\"]",
            "current_temperature","22","temperature","24","fan_mode","low","preset_mode","none","swing_mode","full_swing","swing_horizontal_mode","default",
            "friendly_name","gree","supported_features","953");
    }
    static EntityStates.Entity entity(String state,String... kv){
        Map<String,String> a=new HashMap<>();for(int i=0;i+1<kv.length;i+=2)if(kv[i+1]!=null)a.put(kv[i],kv[i+1]);return new EntityStates.Entity(state,a);
    }
    private static final CardBodies.Env ENV=new CardBodies.Env(){
        public String time(){return "";}public String weekday(){return "";}public String date(){return "";}public String musicInfo(){return "";}public long now(){return 0;}
    };
    private static DashboardSpec.Item item(String extraIcon) throws Exception {
        JSONObject i=DashboardSpecV6Test.item("t","climate",1,1,1,1).put("entity","climate.salon");
        if(extraIcon!=null)i.put("icon",extraIcon);
        return DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new org.json.JSONArray().put(DashboardSpecV6Test.page("main",null,i)))).items.get(0);
    }
    private static CardBodies.CardContent tile(DashboardSpec.Item item,EntityStates.Entity e){
        Map<String,EntityStates.Entity> s=new HashMap<>();if(e!=null)s.put("climate.salon",e);return CardBodies.FOR.get("climate").render(item,s,true,ENV);
    }

    @Test public void parsesAsAReadOnlyTypeThatOpensThePanel() throws Exception {
        DashboardSpec.Item i=item(null);
        assertEquals("climate",i.action);assertNull(i.icon);
        assertEquals(ActionPolicy.Panel.CLIMATE,ActionPolicy.panel(i.action));assertTrue(ActionPolicy.needsKnown("climate"));
        assertEquals(CardDefinition.Gate.KNOWN,CardDefinition.of("climate").gate(i));
        JSONObject bad=DashboardSpecV6Test.item("t","climate",1,1,1,1).put("entity","sensor.x");
        try{DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new org.json.JSONArray().put(DashboardSpecV6Test.page("main",null,bad))));fail("sensor as climate");}catch(IllegalArgumentException e){assertEquals("Element t wymaga encji z domeny climate",e.getMessage());}
        JSONObject tap=DashboardSpecV6Test.item("t","climate",1,1,1,1).put("entity","climate.x").put("tap_action",new JSONObject().put("action","toggle"));
        try{DashboardSpec.parse(new JSONObject().put("version",6).put("pages",new org.json.JSONArray().put(DashboardSpecV6Test.page("main",null,tap))));fail("tap_action");}catch(IllegalArgumentException e){assertEquals("Pole niedozwolone dla typu climate: tap_action",e.getMessage());}
        JSONObject v5=DashboardSpecTest.example().put("version",5);v5.getJSONArray("items").put(DashboardSpecV6Test.item("c","climate",4,3,1,1).put("entity","climate.x"));
        try{DashboardSpec.parse(v5);fail("version 5");}catch(IllegalArgumentException e){assertEquals("Typ climate wymaga version: 6",e.getMessage());}
    }
    @Test public void theTileShowsMeasuredBigAndTheSetpointUnder() throws Exception {
        DashboardSpec.Item i=item(null);
        CardBodies.CardContent c=tile(i,salon("heat","heating"));
        assertEquals("23,0°",c.value);assertEquals("Zadana 20,5°",c.detail);assertEquals("Salon - Termostat",c.label);assertEquals("mdi:fire",c.icon);assertTrue("amber: heating",c.accent);
        c=tile(i,salon("heat","idle"));assertEquals("mdi:thermostat",c.icon);assertFalse(c.accent);
        c=tile(i,salon("off","off"));assertEquals("Wyłączony",c.detail);assertEquals("mdi:power",c.icon);assertFalse(c.accent);
        EntityStates.Entity noReading=entity("heat","supported_features","401","temperature","21","min_temp","14","max_temp","25","current_temperature",null);
        c=tile(i,noReading);assertEquals("—",c.value);assertEquals("the setpoint never takes the reading's place","Zadana 21°",c.detail);
        c=tile(i,entity("unavailable","current_temperature","23"));assertEquals("—",c.value);assertEquals("Brak połączenia",c.detail);
        c=tile(i,null);assertEquals("Brak połączenia",c.detail);
        c=tile(item("mdi:radiator"),salon("heat","heating"));assertNull("an own icon is never replaced",c.icon);
        c=tile(i,entity("heat_cool","supported_features","3","target_temp_low","20","target_temp_high","24","min_temp","7","max_temp","35"));assertEquals("Zadana 20–24°",c.detail);
        c=tile(i,entity("fan_only","supported_features","8","fan_modes","[\"low\"]"));assertEquals("Wentylator",c.detail);
    }
    @Test public void editabilityStepsAndBounds(){
        ClimateModel m=new ClimateModel(salon("heat","idle"),false);
        assertTrue(m.editable());assertEquals(0.5,m.step,0);assertEquals(21.0,m.next(20.5,1),0);assertEquals(20.0,m.next(20.5,-1),0);
        assertEquals(25.0,m.next(25,1),0);assertFalse(m.canStep(25,1));assertTrue(m.canStep(25,-1));
        assertEquals(1.0,new ClimateModel(salon("heat","idle"),true).step,0);
        ClimateModel frac=new ClimateModel(entity("heat","supported_features","1","temperature","30","min_temp","16","max_temp","30.5","target_temp_step","1"),false);
        assertEquals("the endpoint exactly as HA gave it",30.5,frac.next(30,1),0);
        assertEquals(21.0,m.next(20.7,1),0); // off-grid values snap first
        assertFalse("bit without a value",new ClimateModel(entity("heat","supported_features","1","min_temp","14","max_temp","25"),false).editable());
        assertFalse("bounds upside down",new ClimateModel(entity("heat","supported_features","1","temperature","20","min_temp","25","max_temp","14"),false).editable());
        assertFalse("no bit",new ClimateModel(entity("heat","supported_features","16","temperature","20","min_temp","14","max_temp","25"),false).editable());
        assertFalse("unavailable",new ClimateModel(entity("unavailable","supported_features","1","temperature","20","min_temp","14","max_temp","25"),false).editable());
        ClimateModel range=new ClimateModel(entity("heat_cool","supported_features","3","temperature","22","target_temp_low","20","target_temp_high","24","min_temp","7","max_temp","35"),false);
        assertTrue("single setpoint wins over the range",range.editable());
    }
    @Test public void listsFollowFeatureBitsAndWordsArePolish(){
        ClimateModel g=new ClimateModel(gree(),false);
        List<String> labels=new ArrayList<>();for(ClimateModel.Group x:g.groups)labels.add(x.label);
        assertEquals(Arrays.asList("Profil","Siła nawiewu","Kierunek pionowy","Kierunek poziomy"),labels);
        assertEquals(Arrays.asList("Profil"),Collections.singletonList(new ClimateModel(salon("heat","idle"),false).groups.get(0).label));
        assertEquals(1,new ClimateModel(salon("heat","idle"),false).groups.size());
        assertEquals("Standardowy",ClimateModel.option("Profil","none"));assertEquals("Poza domem",ClimateModel.option("Profil","away"));assertEquals("Fireplace",ClimateModel.option("Profil","fireplace"));
        assertEquals("Niska",ClimateModel.option("Siła nawiewu","low"));assertEquals("Medium low",ClimateModel.option("Siła nawiewu","medium low"));
        assertEquals("Ruch: pełny",ClimateModel.option("Kierunek pionowy","full_swing"));assertEquals("Stały: góra-środek",ClimateModel.option("Kierunek pionowy","fixed_upper_middle"));
        assertEquals("Domyślny",ClimateModel.option("Kierunek pionowy","default"));assertEquals("Lewo-środek",ClimateModel.option("Kierunek poziomy","left_center"));
        assertEquals("unknown word: HA's text","Swing sideways",ClimateModel.option("Kierunek pionowy","swing_sideways"));
        assertEquals("Wył.",ClimateModel.mode("off",true));assertEquals("Grzanie/chł.",ClimateModel.mode("heat_cool",false));
        assertNull("no action is never shown as idle",new ClimateModel(entity("heat"),false).actionWord());
        assertEquals("Odszrania",new ClimateModel(entity("heat","hvac_action","defrosting"),false).actionWord());
        assertTrue(ClimateModel.list("not json").isEmpty());
    }
    @Test public void onlySixServicesWithTheirOwnKeyAndValuesTheEntityOffers() throws Exception {
        assertEquals("{\"temperature\":21.5}",ActionPolicy.climateData("set_temperature",21.5).toString());
        assertEquals("{\"hvac_mode\":\"off\"}",ActionPolicy.climateData("set_hvac_mode","off").toString());
        assertEquals("{\"swing_horizontal_mode\":\"left\"}",ActionPolicy.climateData("set_swing_horizontal_mode","left").toString());
        assertNull(ActionPolicy.climateData("turn_on",null));assertNull(ActionPolicy.climateData("set_humidity",40.0));
        assertNull("a word for the setpoint",ActionPolicy.climateData("set_temperature","21"));assertNull("a number for a mode",ActionPolicy.climateData("set_hvac_mode",1.0));
        ClimateModel g=new ClimateModel(gree(),false);
        assertTrue(g.allows("set_fan_mode","medium low"));assertFalse(g.allows("set_fan_mode","turbo"));
        assertTrue(g.allows("set_temperature",30.0));assertFalse(g.allows("set_temperature",31.0));assertFalse(g.allows("set_temperature",Double.NaN));
        assertTrue(g.allows("set_hvac_mode","dry"));assertFalse(g.allows("set_hvac_mode","heat_cool"));
        assertFalse("salon has no fan",new ClimateModel(salon("heat","idle"),false).allows("set_fan_mode","low"));
        assertFalse("unavailable",new ClimateModel(entity("unavailable","hvac_modes","[\"off\"]"),false).allows("set_hvac_mode","off"));
    }
    @Test public void oneCallAtATimeFromDraftToConfirmation(){
        ClimateFlow f=new ClimateFlow();ClimateModel m=new ClimateModel(salon("heat","idle"),false);
        assertTrue(f.step(m,1,0));assertTrue(f.step(m,1,100));assertEquals(21.5,f.shown(m),0);assertEquals("Zmieniasz…",f.status());
        assertTrue("mode and lists wait while a draft waits",f.busy());assertTrue("-/+ extend the draft",f.stepping());
        assertNull("not yet",f.takeDraft(800,false));
        Double d=f.takeDraft(900,false);assertEquals(21.5,d,0);
        f.sent("set_temperature",d,900);
        assertFalse("no step from a stale value",f.step(m,1,950));assertFalse(f.mayCall());assertEquals("Ustawianie…",f.status());
        f.result(null); // accepted: still waits for HA to show it
        assertTrue(f.busy());
        f.observe(new ClimateModel(entity("heat","supported_features","401","temperature","21.5","min_temp","14","max_temp","25"),false),2000);
        assertFalse(f.busy());assertNull(f.status());assertNull(f.message());
    }
    @Test public void failuresTimeoutsAndDiscards(){
        ClimateModel m=new ClimateModel(salon("heat","idle"),false);
        ClimateFlow f=new ClimateFlow();f.sent("set_hvac_mode","off",0);
        f.result("Entity not found");assertFalse(f.busy());assertEquals("Nie wykonano: Entity not found",f.message());assertNull("a mode failure is not a setpoint line",f.status());
        f=new ClimateFlow();f.sent("set_temperature",21.0,0);
        f.observe(m,9_999);assertTrue(f.busy());
        f.observe(m,10_000);assertFalse(f.busy());assertEquals("Brak potwierdzenia",f.message());assertEquals("Brak potwierdzenia",f.status());
        f.result("late");assertEquals("a late answer after the timeout changes nothing","Brak potwierdzenia",f.message());
        f=new ClimateFlow();f.step(m,-1,0);f.discard();assertFalse(f.busy());assertNull(f.takeDraft(10_000,true));
        f=new ClimateFlow();f.step(m,1,0);assertEquals("closing flushes a draft before its time",21.0,f.takeDraft(10,true),0);
        f=new ClimateFlow();f.refused("set_temperature","Brak połączenia z Home Assistant");assertEquals("Nie udało się ustawić",f.status());
        f=new ClimateFlow();f.sent("set_preset_mode","away",0);
        f.observe(new ClimateModel(entity("heat","supported_features","401","preset_modes","[\"none\",\"away\"]","preset_mode","away"),false),100);
        assertFalse("a list value ends the call when HA shows it",f.busy());
    }
    @Test public void theGridStaysInTheWindowWithoutOverlapsAndFingerSized(){
        Box w=ClimatePanelGeometry.WINDOW;
        for(Box b:ClimatePanelGeometry.CARDS)assertTrue(b.x+","+b.y,w.contains(b));
        for(int i=0;i<ClimatePanelGeometry.CARDS.length;i++)for(int j=i+1;j<ClimatePanelGeometry.CARDS.length;j++)
            assertFalse(i+"/"+j,ClimatePanelGeometry.CARDS[i].overlaps(ClimatePanelGeometry.CARDS[j]));
        for(Box b:ClimatePanelGeometry.TOUCH)assertTrue(b.h>=64&&b.w>=64);
        assertTrue(ClimatePanelGeometry.PICKER_CLOSE.h>=64&&ClimatePanelGeometry.ROW>=64);
        Box six=ClimatePanelGeometry.cell(ClimatePanelGeometry.MODES,6,5);assertEquals(792,six.x+six.w);
        int visible=ClimatePanelGeometry.PICKER_LIST.h;assertEquals("four rows and half a fifth",4*ClimatePanelGeometry.ROW_STEP+ClimatePanelGeometry.ROW/2,visible);
    }
}
