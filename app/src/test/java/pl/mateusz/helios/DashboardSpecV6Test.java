package pl.mateusz.helios;

import org.junit.BeforeClass;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

/** Schema 6: pages, the generic tile with an intent per domain, mdi: icons. Legacy documents 2-5 are untouched by it. */
public class DashboardSpecV6Test {
    @BeforeClass public static void icons() throws Exception {MdiIconsTest.install();}
    static JSONObject item(String id,String type,int column,int row,int width,int height) throws Exception {return DashboardSpecTest.item(id,type,column,row,width,height);}
    static JSONObject tile(String id,String entity,int column,int row) throws Exception {return item(id,"tile",column,row,1,1).put("entity",entity);}
    static JSONObject page(String id,String title,JSONObject... items) throws Exception {
        JSONArray a=new JSONArray();for(JSONObject i:items)a.put(i);
        JSONObject p=new JSONObject().put("id",id).put("items",a);if(title!=null)p.put("title",title);return p;
    }
    static JSONObject example() throws Exception {
        JSONObject main=page("main","Dom",
            item("clock","clock",1,1,2,2).put("title","Dom"),
            item("weather","weather",3,1,2,1).put("entity","weather.forecast_dom"),
            tile("salon","light.salon",3,2).put("icon","mdi:ceiling-light").put("tap_action",new JSONObject().put("action","toggle")),
            tile("roleta","cover.roleta_salon",4,2),
            tile("temp","sensor.temperatura_salon",1,3).put("title","Salon"),
            tile("wilg","sensor.czujnik_salon",2,3).put("attribute","humidity").put("icon","mdi:water-percent"),
            tile("zamek","lock.drzwi",3,3).put("tap_action",new JSONObject().put("action","lock")).put("confirmation",new JSONObject().put("enabled",true).put("text","Zamknąć drzwi?")),
            item("music","music",4,3,1,1));
        JSONObject bedroom=page("sypialnia","Sypialnia",
            item("rolety","cover_group",1,1,1,1).put("covers",new JSONArray().put(new JSONObject().put("entity","cover.a").put("title","Roleta A")).put(new JSONObject().put("entity","cover.b").put("title","Roleta B"))),
            tile("noc","scene.noc",2,1).put("icon","mdi:weather-night").put("title","Dobranoc"),
            item("garaz","garage",1,3,4,1).put("entity","cover.brama").put("visible_when",new JSONObject().put("entity","binary_sensor.wieczor").put("state","on")),
            item("swiatla","entity",3,1,1,1).put("entity","sensor.swiatla").put("icon","lightbulb").put("off_entity","light.grupa"));
        return new JSONObject().put("version",6).put("pages",new JSONArray().put(main).put(bedroom));
    }
    private static void rejects(JSONObject config,String why){
        try{DashboardSpec.parse(config);fail("accepted: "+why);}catch(IllegalArgumentException expected){}catch(Exception other){fail(why+": "+other);}
    }
    private static JSONObject with(String id,String key,Object value) throws Exception {
        JSONObject c=example();JSONArray pages=c.getJSONArray("pages");
        for(int p=0;p<pages.length();p++){JSONArray items=pages.getJSONObject(p).getJSONArray("items");
            for(int i=0;i<items.length();i++)if(items.getJSONObject(i).getString("id").equals(id)){if(value==null)items.getJSONObject(i).remove(key);else items.getJSONObject(i).put(key,value);}}
        return c;
    }

    @Test public void pagesTilesIntentsAndIcons() throws Exception {
        DashboardSpec spec=DashboardSpec.parse(new JSONObject(example().toString()));
        assertEquals(6,spec.version);assertEquals(2,spec.pages.size());assertEquals("Dom",spec.pages.get(0).title);assertEquals("sypialnia",spec.pages.get(1).id);
        assertSame(spec.pages.get(0).items,spec.items);assertEquals(8,spec.items.size());
        assertNotNull(spec.item("garaz"));assertEquals("close",spec.item("garaz").action);
        // intents: explicit, default per domain, forced confirmation on locks
        assertEquals("toggle",spec.item("salon").action);assertEquals("mdi:ceiling-light",spec.item("salon").icon);assertFalse(spec.item("salon").confirm);
        assertEquals("controls",spec.item("roleta").action);assertEquals("mdi:window-shutter",spec.item("roleta").icon);
        assertEquals("details",spec.item("temp").action);assertEquals("mdi:eye",spec.item("temp").icon);assertTrue(spec.item("temp").interactive());
        assertEquals("lock",spec.item("zamek").action);assertTrue(spec.item("zamek").confirm);assertEquals("Zamknąć drzwi?",spec.item("zamek").confirmText);
        assertEquals("activate",spec.item("noc").action);assertEquals("mdi:weather-night",spec.item("noc").icon);
        // legacy types in a version-6 document: same behaviour, icons normalised to the catalogue
        assertEquals("mdi:lightbulb",spec.item("swiatla").icon);assertEquals("lights_off",spec.item("swiatla").action);assertEquals("mdi:garage-open",spec.item("garaz").icon);assertEquals("mdi:music",spec.item("music").icon);
        // subscriptions span every page; the tile keeps the attributes its body reads plus the configured one
        List<String> entities=spec.entities();
        for(String e:new String[]{"weather.forecast_dom","light.salon","lock.drzwi","cover.a","scene.noc","cover.brama","binary_sensor.wieczor","sensor.swiatla"})assertTrue(e,entities.contains(e));
        assertEquals(new HashSet<>(Arrays.asList("friendly_name","icon","unit_of_measurement","device_class","humidity")),spec.attributes().get("sensor.czujnik_salon"));
        assertEquals(new HashSet<>(DashboardSpec.TILE_ATTRIBUTES),spec.attributes().get("light.salon"));
        // a lock whose confirmation is switched off in the config still asks: parse refuses the document instead
        rejects(with("zamek","confirmation",new JSONObject().put("enabled",false)),"lock without confirmation");
        // tap_action: none makes a tile display-only
        assertNull(DashboardSpec.parse(with("salon","tap_action",new JSONObject().put("action","none"))).item("salon").action);
        assertFalse(DashboardSpec.parse(with("salon","tap_action",new JSONObject().put("action","none"))).item("salon").interactive());
    }
    @Test public void rejectsIntentsOutsideTheDomainAndUnknownIcons() throws Exception {
        rejects(with("temp","tap_action",new JSONObject().put("action","toggle")),"toggle on a sensor");
        rejects(with("salon","tap_action",new JSONObject().put("action","open")),"open on a light");
        rejects(with("salon","tap_action",new JSONObject().put("action","light.turn_on")),"a service name as action");
        rejects(with("salon","tap_action",new JSONObject().put("action","toggle").put("data",new JSONObject())),"data in tap_action");
        rejects(with("salon","icon","mdi:nope-not-an-icon"),"unknown mdi icon");
        rejects(with("salon","icon","alert"),"bare name outside the six");
        rejects(with("salon","entity","light"),"entity without domain");
        rejects(with("salon","attribute","Bad-Name"),"attribute format");
        rejects(with("salon","off_entity","light.x"),"off_entity on a tile");
        rejects(with("salon","covers",new JSONArray()),"covers on a tile");
        // legacy rules still hold inside version 6
        rejects(with("garaz","tap_action",new JSONObject().put("action","open")),"garage with open");
        rejects(with("swiatla","tap_action",new JSONObject().put("action","toggle")),"entity with toggle");
        JSONObject v5=example().put("version",5);rejects(v5,"pages at version 5");
        JSONObject v5tile=DashboardSpecTest.example().put("version",5);v5tile.getJSONArray("items").put(tile("t","light.x",4,3));rejects(v5tile,"tile at version 5");
        JSONObject mdiAt5=DashboardSpecTest.example();mdiAt5.getJSONArray("items").getJSONObject(3).put("icon","mdi:lightbulb");rejects(mdiAt5,"mdi icon at version 2");
    }
    @Test public void rejectsBrokenPages() throws Exception {
        JSONObject root=example().put("items",new JSONArray());rejects(root,"items at the root of version 6");
        root=example().put("grid",new JSONObject().put("columns",4).put("rows",3));rejects(root,"grid at version 6");
        root=example();root.remove("pages");rejects(root,"missing pages");
        root=example().put("pages",new JSONArray());rejects(root,"no pages");
        root=example();root.getJSONArray("pages").put(page("main","x"));rejects(root,"duplicate page id");
        root=example();root.getJSONArray("pages").put(page("Zła strona",null));rejects(root,"bad page id");
        root=example();root.getJSONArray("pages").getJSONObject(1).put("extra",1);rejects(root,"unknown page field");
        root=example();root.getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(tile("salon","light.y",4,3));rejects(root,"item id repeated on another page");
        root=example();for(int i=0;i<7;i++)root.getJSONArray("pages").put(page("p"+i,null));rejects(root,"nine pages");
        root=example();for(int i=0;i<5;i++)root.getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(tile("x"+i,"light.x",4,3));rejects(root,"13 items on a page");
        root=example();root.getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(item("music2","music",4,1,1,1));
        assertNotNull(DashboardSpec.parse(root).item("music2")); // one music per page is fine
        root=example();root.getJSONArray("pages").getJSONObject(0).getJSONArray("items").put(item("music2","music",4,2,1,1));rejects(root,"two music tiles on one page");
        // the same cell on two pages is not an overlap
        root=example();root.getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(tile("inny","light.inny",3,2));assertNotNull(DashboardSpec.parse(root).item("inny"));
        root=example();root.getJSONArray("pages").getJSONObject(0).getJSONArray("items").put(tile("inny","light.inny",3,2));rejects(root,"overlap on one page");
        // the icon catalogue is a hard requirement of version 6, not of older documents
        MdiIcons was=MdiIcons.installed();MdiIcons.install((MdiIcons)null);
        try{rejects(example(),"mdi icons not installed");assertEquals(6,DashboardSpec.parse(DashboardSpecTest.example()).items.size());}
        finally{MdiIcons.install(was);}
    }
}
