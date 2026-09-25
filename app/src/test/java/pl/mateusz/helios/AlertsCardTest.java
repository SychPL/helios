package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** SPEC 0.20: the "Uwagi" tile - schema, three-state conditions, preview, order, time line and the "Zgaś" rules. */
public class AlertsCardTest {
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}

    private static JSONObject source(String title,String flag) throws Exception {
        return new JSONObject().put("title",title).put("entity","sensor."+flag).put("when",new JSONObject().put("entity","binary_sensor."+flag+"_pokaz").put("state","on"));
    }
    private static JSONObject alerts(int w,int h,JSONObject... sources) throws Exception {
        JSONArray a=new JSONArray();for(JSONObject s:sources)a.put(s);
        return DashboardSpecV6Test.item("uwagi","alerts",1,1,w,h).put("sources",a);
    }
    private static JSONObject doc(JSONObject... items) throws Exception {return new JSONObject().put("version",6).put("pages",new JSONArray().put(DashboardSpecV6Test.page("main",null,items)));}
    private static DashboardSpec.Item parse(JSONObject item) throws Exception {return DashboardSpec.parse(doc(item)).items.get(0);}
    private static void rejects(JSONObject item,String why){
        try{DashboardSpec.parse(doc(item));fail("accepted: "+why);}catch(Exception expected){assertNotNull(why,expected.getMessage());}
    }
    private static EntityStates.Entity e(String state,long lc){return new EntityStates.Entity(state,new HashMap<>(),lc);}
    private static DashboardSpec.Source src(String title,String flag,String off){return new DashboardSpec.Source(title,"sensor."+flag,null,"binary_sensor."+flag+"_pokaz","on",off,true);}

    @Test public void parsesSourcesAndTheStandInCardAndSubscribesToAll() throws Exception {
        JSONObject item=alerts(2,1,source("Garaż","garaz").put("icon","mdi:garage-open"),source("Światła","swiatla").put("off_entity","light.do_sprawdzenia"),source("Wiking","wiking").put("show_since",false));
        item.put("empty",new JSONObject().put("type","energy").put("entity","sensor.pv").put("load_entity","sensor.dom"));
        DashboardSpec spec=DashboardSpec.parse(doc(item));
        DashboardSpec.Item i=spec.items.get(0);
        assertEquals("alerts",i.action);assertEquals(ActionPolicy.Panel.ALERTS,ActionPolicy.panel(i.action));
        assertEquals(3,i.sources.size());assertEquals("mdi:garage-open",i.sources.get(0).icon);assertEquals("light.do_sprawdzenia",i.sources.get(1).offEntity);
        assertFalse(i.sources.get(2).showSince);assertTrue(i.sources.get(0).showSince);
        assertEquals("energy",i.empty.type);assertEquals(1,i.empty.column);assertEquals(2,i.empty.width);
        assertEquals("the stand-in is not an item of its own",1,spec.allItems().size());
        List<String> e=spec.entities();
        for(String want:new String[]{"binary_sensor.garaz_pokaz","sensor.garaz","light.do_sprawdzenia","sensor.pv","sensor.dom"})assertTrue(want,e.contains(want));
        assertTrue(spec.attributes().get("sensor.pv").contains("unit_of_measurement"));
    }
    @Test public void rejectsBrokenDocuments() throws Exception {
        rejects(alerts(2,1),"no sources");
        rejects(alerts(2,1,source("A","a"),source("B","a")),"duplicate condition");
        rejects(alerts(2,2,source("A","a")),"2x2 is not a size of the tile");
        rejects(alerts(1,3,source("A","a")),"1x3 either");
        rejects(alerts(2,1,source("A","a").put("off_entity","switch.x")),"off_entity must be a light");
        rejects(alerts(2,1,source("A","a").put("show_since","tak")),"show_since is a boolean");
        rejects(alerts(2,1,source("A","a").put("kolor","red")),"unknown source key");
        JSONObject bad=source("A","a");bad.getJSONObject("when").put("state","unavailable");rejects(alerts(2,1,bad),"no data is never a condition");
        rejects(alerts(2,1,source("A","a")).put("empty",new JSONObject().put("type","music")),"music may not stand in");
        rejects(alerts(2,1,source("A","a")).put("empty",new JSONObject().put("type","alerts")),"no nesting");
        rejects(alerts(2,1,source("A","a")).put("empty",new JSONObject().put("type","clock").put("column",3)),"position comes from the tile");
        JSONObject many=alerts(2,1);for(int n=0;n<13;n++)many.getJSONArray("sources").put(source("S"+n,"s"+n));rejects(many,"13 sources");
        JSONObject twelve=alerts(2,1);for(int n=0;n<12;n++)twelve.getJSONArray("sources").put(source("S"+n,"s"+n));assertEquals(12,parse(twelve).sources.size());
    }
    @Test public void conditionsHaveThreeAnswers(){
        DashboardSpec.Source s=src("Garaż","garaz",null);
        Map<String,EntityStates.Entity> st=new HashMap<>();
        assertEquals("missing",AlertsModel.Cond.UNKNOWN,AlertsModel.cond(s,st,true));
        st.put("binary_sensor.garaz_pokaz",e("unavailable",0));assertEquals(AlertsModel.Cond.UNKNOWN,AlertsModel.cond(s,st,true));
        st.put("binary_sensor.garaz_pokaz",e("unknown",0));assertEquals(AlertsModel.Cond.UNKNOWN,AlertsModel.cond(s,st,true));
        st.put("binary_sensor.garaz_pokaz",e("off",0));assertEquals(AlertsModel.Cond.INACTIVE,AlertsModel.cond(s,st,true));
        st.put("binary_sensor.garaz_pokaz",e("on",0));assertEquals(AlertsModel.Cond.ACTIVE,AlertsModel.cond(s,st,true));
        assertEquals("no session = no knowledge",AlertsModel.Cond.UNKNOWN,AlertsModel.cond(s,st,false));
    }
    @Test public void tileStatesFollowTheTable(){
        List<DashboardSpec.Source> sources=Arrays.asList(src("Garaż","garaz",null),src("Śmieci","smieci",null));
        Map<String,EntityStates.Entity> st=new HashMap<>();
        AlertsModel m=new AlertsModel(sources,st,true);
        assertEquals("-",m.count());assertEquals("Brak danych",m.idleWord());assertFalse(m.quiet());assertNull(m.footer());
        st.put("binary_sensor.garaz_pokaz",e("off",0));st.put("binary_sensor.smieci_pokaz",e("off",0));
        m=new AlertsModel(sources,st,true);
        assertEquals("0",m.count());assertEquals("Brak uwag",m.idleWord());assertTrue(m.quiet());
        assertFalse("offline is never all-clear",new AlertsModel(sources,st,false).quiet());
        st.put("binary_sensor.garaz_pokaz",e("on",1000));st.remove("binary_sensor.smieci_pokaz");
        m=new AlertsModel(sources,st,true);
        assertEquals("1",m.count());assertNull(m.idleWord());assertEquals("Brak danych: 1",m.footer());
        assertEquals("Brak treści comes from a missing text",null,m.active.get(0).text);
        st.put("sensor.garaz",e("Brama otwarta",0));
        assertEquals("Brama otwarta",new AlertsModel(sources,st,true).active.get(0).text);
    }
    @Test public void newestFirstUntimedLastTiesInConfigOrderUnknownAfter(){
        List<DashboardSpec.Source> sources=new ArrayList<>();
        for(String f:new String[]{"a","b","c","d","e"})sources.add(src(f.toUpperCase(),f,null));
        Map<String,EntityStates.Entity> st=new HashMap<>();
        st.put("binary_sensor.a_pokaz",e("on",0));st.put("binary_sensor.b_pokaz",e("on",5000));st.put("binary_sensor.c_pokaz",e("on",9000));
        st.put("binary_sensor.d_pokaz",e("on",5000));
        AlertsModel m=new AlertsModel(sources,st,true);
        List<String> order=new ArrayList<>();for(AlertsModel.Row r:m.active)order.add(r.source.title);
        assertEquals(Arrays.asList("C","B","D","A"),order);
        assertEquals(1,m.unknown.size());assertEquals("E",m.unknown.get(0).source.title);
        DashboardSpec.Source hidden=new DashboardSpec.Source("H","sensor.h",null,"binary_sensor.c_pokaz","off",null,false);
        assertEquals("show_since: false still sorts by the time (the list only hides it)",9000,new AlertsModel(Collections.singletonList(new DashboardSpec.Source("C","sensor.c",null,"binary_sensor.c_pokaz","on",null,false)),st,true).active.get(0).since);
        assertTrue(new AlertsModel(Collections.singletonList(hidden),st,true).active.isEmpty());
    }
    @Test public void previewOverflowsWithARestLine(){
        List<DashboardSpec.Source> sources=new ArrayList<>();Map<String,EntityStates.Entity> st=new HashMap<>();
        for(int n=0;n<12;n++){sources.add(src("S"+n,"s"+n,null));st.put("binary_sensor.s"+n+"_pokaz",e("on",1000+n));}
        AlertsModel m=new AlertsModel(sources,st,true);
        assertEquals("12",m.count());
        List<AlertsModel.Slot> tall=m.slots(3);assertEquals(3,tall.size());assertEquals("S11",tall.get(0).title);assertEquals("+10 pozostałych",tall.get(2).title);
        List<AlertsModel.Slot> wide=m.slots(2);assertEquals(2,wide.size());assertEquals("+11 pozostałych",wide.get(1).title);
        assertEquals("S11",m.summaryTitle());assertEquals(" +11",m.summarySuffix());
        AlertsModel three=new AlertsModel(sources.subList(0,3),st,true);
        assertEquals("three fit a tall tile without a rest line",3,three.slots(3).size());assertEquals("S2",three.slots(3).get(0).title);
        assertEquals("",new AlertsModel(sources.subList(0,1),st,true).summarySuffix());
    }
    @Test public void timeLineInTheClocksZone(){
        TimeZone waw=TimeZone.getTimeZone("Europe/Warsaw");
        Calendar c=Calendar.getInstance(waw);c.clear();c.set(2026,Calendar.SEPTEMBER,25,21,0);long now=c.getTimeInMillis();
        c.set(2026,Calendar.SEPTEMBER,25,14,32);assertEquals("Aktywne od 14:32",AlertsModel.since(c.getTimeInMillis(),now,waw));
        c.set(2026,Calendar.SEPTEMBER,24,22,10);assertEquals("Aktywne od wczoraj 22:10",AlertsModel.since(c.getTimeInMillis(),now,waw));
        c.set(2026,Calendar.SEPTEMBER,23,22,10);assertEquals("Aktywne od 23 wrz 22:10",AlertsModel.since(c.getTimeInMillis(),now,waw));
        c.set(2026,Calendar.SEPTEMBER,25,0,5);assertEquals("Aktywne od 0:05",AlertsModel.since(c.getTimeInMillis(),now,waw));
        assertNull(AlertsModel.since(0,now,waw));
        c.set(2026,Calendar.DECEMBER,31,23,0);long nye=c.getTimeInMillis();c.set(2027,Calendar.JANUARY,1,9,0);
        assertEquals("across the new year",AlertsModel.since(nye,c.getTimeInMillis(),waw),"Aktywne od wczoraj 23:00");
    }
    @Test public void lastChangedSurvivesAttributeOnlyChanges() throws Exception {
        EntityStates states=new EntityStates(Collections.singletonMap("binary_sensor.g",new HashSet<>(Collections.singletonList("friendly_name"))));
        states.apply(new JSONObject("{\"a\":{\"binary_sensor.g\":{\"s\":\"on\",\"a\":{\"friendly_name\":\"G\"},\"lc\":1758800000.5}}}"));
        assertEquals(1758800000500L,states.snapshot().get("binary_sensor.g").lastChanged);
        states.apply(new JSONObject("{\"c\":{\"binary_sensor.g\":{\"+\":{\"a\":{\"friendly_name\":\"Garaż\"},\"lu\":1758800100}}}}"));
        assertEquals(1758800000500L,states.snapshot().get("binary_sensor.g").lastChanged);
        states.apply(new JSONObject("{\"c\":{\"binary_sensor.g\":{\"+\":{\"s\":\"off\",\"lc\":1758800200}}}}"));
        assertEquals(1758800200000L,states.snapshot().get("binary_sensor.g").lastChanged);
        states.apply(new JSONObject("{\"a\":{\"sensor.x\":{\"s\":\"1\"}}}"));
        assertEquals("no lc = unknown time",0,states.snapshot().get("sensor.x").lastChanged);
    }
    @Test public void emptyCardWaitsTwoSecondsAndLeavesAtOnce(){
        AlertsModel.EmptyGate g=new AlertsModel.EmptyGate();
        assertFalse(g.show(true,1000));assertEquals(3000,g.due());
        assertFalse(g.show(true,2999));assertTrue(g.show(true,3000));
        assertFalse("a warning takes the place back at once",g.show(false,3001));assertEquals(-1,g.due());
        assertFalse("and the wait starts over",g.show(true,3002));assertTrue(g.show(true,5002));
    }
    @Test public void offGatesAndCallRules(){
        DashboardSpec.Source s=src("Światła","swiatla","light.obserwowane");
        Map<String,EntityStates.Entity> st=new HashMap<>();
        assertEquals("Brak połączenia z Home Assistant",AlertsModel.offBlock(s,st,false));
        assertNotNull("not active",AlertsModel.offBlock(s,st,true));
        st.put("binary_sensor.swiatla_pokaz",e("on",0));
        assertEquals("Światła - brak danych",AlertsModel.offBlock(s,st,true));
        st.put("light.obserwowane",e("unavailable",0));assertEquals("Światła - brak danych",AlertsModel.offBlock(s,st,true));
        st.put("light.obserwowane",e("on",0));assertNull(AlertsModel.offBlock(s,st,true));
        assertNotNull("no off_entity, no button",AlertsModel.offBlock(src("G","g",null),st,true));

        AlertsModel.OffState o=new AlertsModel.OffState();
        int first=o.send(0);assertTrue(o.busy(0,"ACTIVE|x"));
        o.result(first,"HA nie potwierdził",  "ACTIVE|x",100);
        assertFalse(o.busy(100,"ACTIVE|x"));assertEquals("Nie udało się zgasić",o.message("ACTIVE|x",200));
        assertNull("a changed source drops the message",o.message("ACTIVE|y",300));
        int second=o.send(1000);o.result(second,null,"ACTIVE|x",1100);
        assertTrue("rests after a success",o.held());o.observe(true,5000);assertTrue(o.held());
        o.observe(true,11_100);assertFalse("10 s later it may be tapped again",o.held());
        int third=o.send(20_000);o.observe(false,20_000);
        assertTrue(o.busy(30_000,"ACTIVE|x"));assertFalse("a lost answer frees the button by itself",o.busy(30_500,"ACTIVE|x"));assertEquals("Nie udało się zgasić",o.message("ACTIVE|x",30_600));
        o.result(third,null,"ACTIVE|x",31_000);assertFalse("a late answer changes nothing",o.held());
        int fourth=o.send(40_000);o.result(fourth,null,"ACTIVE|x",40_100);o.observe(false,40_200);assertFalse("the warning went: the rest ends",o.held());
        assertNull("messages expire after 10 s",o.message("ACTIVE|x",45_000));
    }
    @Test public void descriptionReadsTheWholeTile(){
        List<DashboardSpec.Source> sources=Arrays.asList(src("Garaż","garaz",null),src("Śmieci","smieci",null));
        Map<String,EntityStates.Entity> st=new HashMap<>();st.put("binary_sensor.garaz_pokaz",e("on",0));st.put("binary_sensor.smieci_pokaz",e("on",0));
        assertEquals("Uwagi: 2 uwagi: Garaż, Śmieci",new AlertsModel(sources,st,true).description("Uwagi"));
        assertEquals("Uwagi: brak połączenia, dane nieaktualne",new AlertsModel(sources,st,false).description("Uwagi"));
    }
}
