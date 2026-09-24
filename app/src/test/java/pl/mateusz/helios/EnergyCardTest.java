package pl.mateusz.helios;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** SPEC 0.17: the 1x1 energy tile - PV power, house load, optional battery charge. */
public class EnergyCardTest {
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}

    private static JSONObject energy(boolean battery) throws Exception {
        JSONObject e=DashboardSpecV6Test.item("energia","energy",4,3,1,1).put("entity","sensor.pv_power").put("load_entity","sensor.house_load");
        return battery?e.put("battery_entity","sensor.battery_soc"):e;
    }
    private static JSONObject doc(JSONObject item) throws Exception {return new JSONObject().put("version",6).put("pages",new org.json.JSONArray().put(DashboardSpecV6Test.page("main",null,item)));}
    private static void rejects(JSONObject item,String why){
        try{DashboardSpec.parse(doc(item));fail("accepted: "+why);}catch(Exception expected){assertNotNull(why,expected.getMessage());}
    }
    private static EntityStates.Entity state(String s,String unit){Map<String,String> a=new HashMap<>();if(unit!=null)a.put("unit_of_measurement",unit);return new EntityStates.Entity(s,a);}
    private static final CardBodies.Env ENV=new CardBodies.Env(){
        public String time(){return "";}public String weekday(){return "";}public String date(){return "";}public String musicInfo(){return "";}public long now(){return 0;}
    };

    @Test public void parsesWithAndWithoutTheBatteryAndSubscribesToEverySensor() throws Exception {
        DashboardSpec spec=DashboardSpec.parse(doc(energy(true)));
        DashboardSpec.Item i=spec.items.get(0);
        assertEquals("sensor.house_load",i.loadEntity);assertEquals("sensor.battery_soc",i.batteryEntity);
        assertEquals("mdi:solar-power",i.icon);assertNull("read-only",i.action);
        assertEquals(Arrays.asList("sensor.pv_power","sensor.house_load","sensor.battery_soc"),spec.entities());
        for(String e:spec.entities())assertTrue(e,spec.attributes().get(e).contains("unit_of_measurement"));
        DashboardSpec without=DashboardSpec.parse(doc(energy(false)));
        assertNull(without.items.get(0).batteryEntity);assertEquals(2,without.entities().size());
    }
    @Test public void rejectsWhatTheTypeDoesNotTake() throws Exception {
        JSONObject noLoad=energy(false);noLoad.remove("load_entity");rejects(noLoad,"load_entity missing");
        rejects(energy(false).put("entity","light.pv"),"PV not a sensor");
        rejects(energy(false).put("load_entity","switch.house"),"load not a sensor");
        rejects(energy(false).put("battery_entity","battery.x"),"battery not a sensor");
        rejects(energy(false).put("battery_entity",JSONObject.NULL),"battery null");
        rejects(energy(false).put("load_entity",new org.json.JSONArray().put("sensor.load")),"load as a list");
        StringBuilder longId=new StringBuilder("sensor.");while(longId.length()<129)longId.append('x');
        rejects(energy(false).put("load_entity",longId.toString()),"load longer than 128");
        rejects(energy(false).put("tap_action",new JSONObject().put("action","toggle")),"a tap on a read-only tile");
        rejects(DashboardSpecV6Test.tile("t","sensor.x",1,1).put("load_entity","sensor.y"),"load_entity on a tile");
        JSONObject v5=DashboardSpecTest.example().put("version",5);v5.getJSONArray("items").put(energy(false));
        try{DashboardSpec.parse(v5);fail("energy at version 5");}catch(IllegalArgumentException e){assertEquals("Typ energy wymaga version: 6",e.getMessage());}
    }
    @Test public void productionSlashLoadOnTopAndTheBatteryBigUnderIt() throws Exception {
        DashboardSpec.Item i=DashboardSpec.parse(doc(energy(true))).items.get(0);
        Map<String,EntityStates.Entity> s=new HashMap<>();
        s.put("sensor.pv_power",state("1504","W"));s.put("sensor.house_load",state("698.4","W"));s.put("sensor.battery_soc",state("68","%"));
        CardBodies.CardContent c=CardBodies.FOR.get("energy").render(i,s,true,ENV);
        assertEquals("1504 / 698,4 W",c.label);assertEquals("68 %",c.value);assertEquals("",c.detail);
        assertEquals("PV: produkcja 1504 W, dom 698,4 W, bateria 68 %",c.description);
        s.put("sensor.house_load",state("0.7","kW"));
        assertEquals("1504 W / 0,7 kW",CardBodies.FOR.get("energy").render(i,s,true,ENV).label); // different units: each its own
        s.put("sensor.battery_soc",state("unavailable","%"));s.remove("sensor.house_load");
        c=CardBodies.FOR.get("energy").render(i,s,false,ENV);
        assertEquals("1504 W / —",c.label);assertEquals("—",c.value);assertTrue(c.description.endsWith("dane nieaktualne"));
        DashboardSpec.Item noBattery=DashboardSpec.parse(doc(energy(false))).items.get(0);
        s.put("sensor.house_load",state("742","W"));
        c=CardBodies.FOR.get("energy").render(noBattery,s,true,ENV);
        assertNull("the title stays PV",c.label);assertEquals("1504 / 742 W",c.value); // no battery: the pair is the value
    }
}
