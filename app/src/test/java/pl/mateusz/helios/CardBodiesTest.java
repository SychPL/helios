package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Text every card shows, pinned to what DashboardView rendered before the bodies moved out of it. */
public class CardBodiesTest {
    private static final CardBodies.Env ENV=new CardBodies.Env(){
        public String time(){return "10:05";}public String weekday(){return "Wtorek";}public String date(){return "22 września";}public String musicInfo(){return "Kuchnia · Utwór";}public long now(){return 1_000L;}
    };
    private static DashboardSpec.Item item(String type,String entity,String title,String attribute){
        return new DashboardSpec.Item("id",type,1,1,1,1,title,null,entity,null,attribute,null,null,null,false,null);
    }
    private static EntityStates.Entity state(String s,String... attrs){Map<String,String> a=new HashMap<>();for(int i=0;i+1<attrs.length;i+=2)a.put(attrs[i],attrs[i+1]);return new EntityStates.Entity(s,a);}
    private static Map<String,EntityStates.Entity> states(String id,EntityStates.Entity e){Map<String,EntityStates.Entity> m=new HashMap<>();m.put(id,e);return m;}
    private static CardBodies.CardContent render(DashboardSpec.Item item,Map<String,EntityStates.Entity> states,boolean live){return CardBodies.FOR.get(item.type).render(item,states,live,ENV);}

    @Test public void clockAndMusicComeFromTheEnvironment(){
        CardBodies.CardContent c=render(item("clock",null,"Dom",null),Collections.emptyMap(),true);
        assertEquals("10:05",c.value);assertEquals("22 września",c.detail);assertEquals("Wtorek",c.detail2);assertEquals("10:05, Wtorek, 22 września",c.description);
        c=render(item("music",null,null,null),Collections.emptyMap(),false);
        assertEquals("Kuchnia · Utwór",c.value);assertEquals("",c.detail);assertEquals("Muzyka: Kuchnia · Utwór",c.description);assertFalse(c.accent);
    }
    @Test public void entityShowsStateOrAttributeOrBrakDanych(){
        DashboardSpec.Item i=item("entity","sensor.x","Światła",null);
        assertEquals("3",render(i,states("sensor.x",state("3")),true).value);
        assertEquals("Światła: 3, dane nieaktualne",render(i,states("sensor.x",state("3")),false).description);
        assertEquals("Brak danych",render(i,states("sensor.x",state("unavailable")),true).value);
        assertEquals("Brak danych",render(i,Collections.emptyMap(),true).value);
        assertEquals("hi",render(item("entity","sensor.x",null,"msg"),states("sensor.x",state("ok","msg","hi")),true).value);
        assertEquals("Brak danych",render(item("entity","sensor.x",null,"msg"),states("sensor.x",state("ok")),true).value);
        assertEquals("X: Brak danych",render(item("entity","sensor.x",null,null),Collections.emptyMap(),true).description);
    }
    @Test public void lightAndCoverWordsAndAccents(){
        DashboardSpec.Item l=item("light","light.x",null,null);
        CardBodies.CardContent c=render(l,states("light.x",state("on")),true);assertEquals("Włączone",c.value);assertTrue(c.accent);
        c=render(l,states("light.x",state("off")),true);assertEquals("Wyłączone",c.value);assertFalse(c.accent);
        assertEquals("Brak danych",render(l,Collections.emptyMap(),true).value);
        DashboardSpec.Item r=item("cover","cover.roleta",null,null);
        c=render(r,states("cover.roleta",state("open","current_position","40.0")),true);assertEquals("Otwarta",c.value);assertEquals("Otwarcie 40%",c.detail);assertTrue(c.accent);
        c=render(r,states("cover.roleta",state("closed")),true);assertEquals("Zamknięta",c.value);assertEquals("",c.detail);assertFalse(c.accent);
        assertEquals("Otwieranie…",render(item("garage","cover.brama",null,null),states("cover.brama",state("opening")),true).value);
        assertEquals("Roleta: Otwarta, Otwarcie 40%",render(r,states("cover.roleta",state("open","current_position","40")),true).description);
    }
    @Test public void weatherCurrentTomorrowAndUnknownMode(){
        DashboardSpec.Item w=new DashboardSpec.Item("w","weather",1,1,2,1,null,null,"weather.dom",null,null,null,null,null,false,null);
        Map<String,EntityStates.Entity> s=states("weather.dom",state("rainy","temperature","12.6","temperature_unit","°C","wind_speed","14","wind_speed_unit","km/h"));
        CardBodies.CardContent c=render(w,s,true);assertEquals("13°C",c.value);assertEquals("Deszcz · 14 km/h",c.detail); // without a forecast the line keeps the condition in wordsassertNull(c.label);assertEquals(0,c.expiresAt);
        assertNull(c.side); // no forecast in the environment, so the tile is today alone
        assertEquals("—",render(w,Collections.emptyMap(),true).value);assertEquals("Brak danych",render(w,Collections.emptyMap(),true).detail);
        DashboardSpec.Item t=new DashboardSpec.Item("w","weather",1,1,2,1,null,null,"weather.dom","sensor.temp",null,null,null,null,false,null);
        s.put("sensor.temp",state("21.4","unit_of_measurement","°C"));assertEquals("21°C",render(t,s,true).value);
        DashboardSpec.Item f=new DashboardSpec.Item("w","weather",1,1,2,1,null,null,"weather.dom",null,null,null,null,null,false,null,Collections.emptyList(),"sensor.jutro","binary_sensor.tryb","on",null);
        s.put("binary_sensor.tryb",state("on"));
        c=render(f,s,true);assertEquals("Jutro",c.label);assertEquals("—",c.value);assertEquals("brak prognozy",c.detail);
        s.put("sensor.jutro",state("ready","condition","sunny","temperature","18","templow","9","temperature_unit","°C","forecast_date","2026-09-23","fetched_at","1970-01-01T00:00:00Z","valid_until","1970-01-01T00:00:05Z"));
        c=render(f,s,true);assertEquals("Jutro",c.label);assertEquals("maks. 18°C",c.value);assertEquals("Słonecznie · min. 9°C",c.detail);assertEquals(5_000L,c.expiresAt);assertEquals("Jutro: maks. 18°C, Słonecznie · min. 9°C",c.description);
        s.put("binary_sensor.tryb",state("unknown"));
        c=render(f,s,true);assertNull(c.label);assertEquals("—",c.value);assertEquals("brak danych o trybie",c.detail);
        s.put("binary_sensor.tryb",state("off"));assertEquals("13°C",render(f,s,true).value);
    }
    @Test public void coverGroupListsBothShutters(){
        DashboardSpec.Item g=new DashboardSpec.Item("g","cover_group",1,1,1,1,null,null,null,null,null,"covers",null,null,false,null,Arrays.asList(new DashboardSpec.Cover("cover.a","Roleta A"),new DashboardSpec.Cover("cover.b","Roleta B")),null,null,null,null);
        Map<String,EntityStates.Entity> s=states("cover.a",state("closed"));s.put("cover.b",state("open","current_position","55"));
        CardBodies.CardContent c=render(g,s,true);
        assertEquals("A: zamknięta",c.value);assertEquals("B: 55%",c.detail);assertTrue(c.accent);assertEquals("Rolety: Roleta A: zamknięta, Roleta B: 55%",c.description);
        assertEquals("Rolety: Roleta A: brak danych, Roleta B: brak danych, dane nieaktualne",render(g,Collections.emptyMap(),false).description);
    }
    @Test public void defaultLabelsAndNumbers(){
        assertEquals("Salon lampa",CardBodies.defaultLabel(item("light","light.salon_lampa",null,null)));
        assertEquals("Dom",CardBodies.defaultLabel(item("light","light.x","Dom",null)));
        assertEquals("",CardBodies.defaultLabel(item("clock",null,null,null)));assertEquals("Pogoda",CardBodies.defaultLabel(item("weather","weather.x",null,null)));
        assertEquals("—",CardBodies.number(null,"%"));assertEquals("13°C",CardBodies.number("12.6","°C"));assertEquals("abc%",CardBodies.number("abc","%"));
    }
    @Test public void tileSpeaksTheDomainsLanguageAndPrefersTheEntitysOwnNameAndIcon() throws Exception {
        MdiIconsTest.install();
        DashboardSpec.Item l=item("tile","light.salon",null,null);
        CardBodies.CardContent c=render(l,states("light.salon",state("on","friendly_name","Lampa w salonie","icon","mdi:ceiling-light")),true);
        assertEquals("Włączone",c.value);assertTrue(c.accent);assertEquals("Lampa w salonie",c.label);assertEquals("mdi:ceiling-light",c.icon);assertEquals("Lampa w salonie: Włączone",c.description);
        c=render(l,states("light.salon",state("off","icon","mdi:no-such-glyph")),true);assertEquals("Wyłączone",c.value);assertFalse(c.accent);assertNull(c.label);assertNull(c.icon);
        c=render(l,Collections.emptyMap(),false);assertEquals("Brak danych",c.value);assertEquals("Salon: Brak danych, dane nieaktualne",c.description);
        assertEquals("Dom",render(item("tile","light.salon","Dom",null),states("light.salon",state("on","friendly_name","Inna")),true).label);
        DashboardSpec.Item own=new DashboardSpec.Item("id","tile",1,1,1,1,null,"mdi:lamp","light.salon",null,null,null,null,null,false,null,Collections.emptyList(),null,null,null,null,true);
        assertNull("a configured icon is never replaced by the entity's",render(own,states("light.salon",state("on","icon","mdi:ceiling-light")),true).icon);
        assertEquals("21,4 °C",render(item("tile","sensor.t",null,null),states("sensor.t",state("21.4","unit_of_measurement","°C")),true).value);
        assertEquals("21 °C",render(item("tile","sensor.t",null,null),states("sensor.t",state("21.0","unit_of_measurement","°C")),true).value);
        assertEquals("ready",render(item("tile","sensor.t",null,null),states("sensor.t",state("ready")),true).value);
        assertEquals("55",render(item("tile","sensor.t",null,"humidity"),states("sensor.t",state("21","humidity","55")),true).value);
        assertEquals("Brak danych",render(item("tile","sensor.t",null,"humidity"),states("sensor.t",state("21")),true).value);
        assertEquals("Otwarta",render(item("tile","cover.r",null,null),states("cover.r",state("open")),true).value);
        assertEquals("Zamknięty",render(item("tile","lock.d",null,null),states("lock.d",state("locked")),true).value);
        assertTrue(render(item("tile","lock.d",null,null),states("lock.d",state("unlocked")),true).accent);
        assertEquals("Otwarte",render(item("tile","binary_sensor.d",null,null),states("binary_sensor.d",state("on","device_class","door")),true).value);
        assertEquals("Brak",render(item("tile","binary_sensor.m",null,null),states("binary_sensor.m",state("off","device_class","motion")),true).value);
        assertEquals("Tak",render(item("tile","binary_sensor.x",null,null),states("binary_sensor.x",state("on")),true).value);
        assertEquals("Włączone",CardBodies.stateText("script",state("on"))); // a running script reads as on; good enough for a tile
        assertEquals("—",render(item("tile","scene.noc",null,null),states("scene.noc",state("unknown")),true).value); // never used yet, still activatable
        assertEquals("Brak danych",render(item("tile","scene.noc",null,null),states("scene.noc",state("unavailable")),true).value);
        assertEquals("Brak danych",render(item("tile","light.x",null,null),states("light.x",state("unknown")),true).value);
        assertEquals("abc °C",CardBodies.decimal("abc","°C"));
    }
    @Test public void aWideWeatherTileCarriesTomorrowBesideToday() throws Exception {
        final Tomorrow[] box=new Tomorrow[1];
        CardBodies.Env env=new CardBodies.Env(){
            public String time(){return "10:05";}public String weekday(){return "Wtorek";}public String date(){return "22 września";}
            public String musicInfo(){return "";}public long now(){return 1_000L;}
            public Tomorrow tomorrow(){return box[0];}
        };
        DashboardSpec.Item wide=new DashboardSpec.Item("w","weather",1,1,2,1,null,null,"weather.dom",null,null,null,null,null,false,null);
        DashboardSpec.Item narrow=new DashboardSpec.Item("w","weather",1,1,1,1,null,null,"weather.dom",null,null,null,null,null,false,null);
        Map<String,EntityStates.Entity> s=states("weather.dom",state("rainy","temperature","11.8","temperature_unit","°C","wind_speed","16","wind_speed_unit","km/h"));
        CardBodies.CardContent c=CardBodies.FOR.get("weather").render(wide,s,true,env);
        assertNull(c.side);assertEquals("mdi:weather-rainy",c.icon);assertEquals("12°C",c.value);
        box[0]=Tomorrow.parse(new org.json.JSONObject("{\"forecast\":[{\"datetime\":\"1970-01-02T00:00:00+00:00\",\"condition\":\"partlycloudy\",\"temperature\":21,\"templow\":12}]}"),"°C",1_000L,java.time.ZoneId.of("UTC"));
        assertNotNull(box[0]);
        c=CardBodies.FOR.get("weather").render(wide,s,true,env);
        assertNotNull(c.side);
        assertEquals("21°C",c.side.value);assertEquals("↓ 12°",c.side.detail);assertEquals("mdi:weather-partly-cloudy",c.side.icon);
        assertEquals("↓ 12° · 16 km/h",c.detail); // the condition word gives way to tonight's low; the icon carries it
        assertEquals("Pogoda: 12°C, Deszcz, w nocy 12°, 16 km/h, jutro 21°C ↓ 12°",c.description);
        box[0]=Tomorrow.parse(new org.json.JSONObject("{\"forecast\":[{\"datetime\":\"1970-01-02T00:00:00+00:00\",\"condition\":\"sunny\",\"temperature\":21}]}"),"°C",1_000L,java.time.ZoneId.of("UTC"));
        assertEquals("16 km/h",CardBodies.FOR.get("weather").render(wide,s,true,env).detail); // no low in the feed: the wind alone, no stray separator
        // one cell has no room for two halves, so the same forecast changes nothing there
        assertNull(CardBodies.FOR.get("weather").render(narrow,s,true,env).side);
    }
}
