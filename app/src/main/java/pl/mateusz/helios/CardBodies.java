package pl.mateusz.helios;

import java.util.*;

/** What each card type shows, as pure text and flags; DashboardView owns the Views and only applies the result. Testable on the JVM. */
final class CardBodies {
    /** Local facts the entity snapshot does not carry. */
    interface Env {String time();String weekday();String date();String musicInfo();long now();}
    /** One line of the detail column: an icon, what it is and what it reads. `separated` draws a rule above it. */
    static final class Row {
        final String icon,label,value;final boolean separated;
        Row(String icon,String label,String value,boolean separated){this.icon=icon;this.label=label;this.value=value;this.separated=separated;}
        Row(String icon,String label,String value){this(icon,label,value,false);}
    }
    /** One rendering: value line, up to two detail lines, optional label and icon overrides, the full content description, accent tint and an expiry for self-refreshing content. */
    static final class CardContent {
        final String value,detail,detail2,label,icon,description;final boolean accent;final long expiresAt;
        /** Extra readings for a tile wide enough to carry a second column; empty for every tile that has none. */
        final List<Row> rows;
        CardContent(String value,String detail,String detail2,String label,String icon,String description,boolean accent,long expiresAt){this(value,detail,detail2,label,icon,description,accent,expiresAt,Collections.<Row>emptyList());}
        CardContent(String value,String detail,String detail2,String label,String icon,String description,boolean accent,long expiresAt,List<Row> rows){this.value=value;this.detail=detail;this.detail2=detail2;this.label=label;this.icon=icon;this.description=description;this.accent=accent;this.expiresAt=expiresAt;this.rows=Collections.unmodifiableList(rows);}
        CardContent(String value,String detail,String label,boolean accent,long expiresAt,DashboardSpec.Item item,boolean live){this(value,detail,label,null,accent,expiresAt,item,live);}
        CardContent(String value,String detail,String label,String icon,boolean accent,long expiresAt,DashboardSpec.Item item,boolean live){
            this(value,detail,"",label,icon,(label!=null?label:defaultLabel(item))+": "+value+(detail.isEmpty()?"":", "+detail)+(live?"":", dane nieaktualne"),accent,expiresAt);
        }
        CardContent(String value,String detail,String label,String icon,boolean accent,long expiresAt,DashboardSpec.Item item,boolean live,List<Row> rows){
            this(value,detail,"",label,icon,(label!=null?label:defaultLabel(item))+": "+value+(detail.isEmpty()?"":", "+detail)+describe(rows)+(live?"":", dane nieaktualne"),accent,expiresAt,rows);
        }
        /** The extra readings belong in the spoken description too, in the order they are drawn. */
        private static String describe(List<Row> rows){
            StringBuilder b=new StringBuilder();
            for(Row r:rows)b.append(", ").append(r.label).append(" ").append(r.value);
            return b.toString();
        }
    }
    interface Body {CardContent render(DashboardSpec.Item item,Map<String,EntityStates.Entity> states,boolean live,Env env);}
    private CardBodies(){}

    static final Map<String,Body> FOR;
    static {
        LinkedHashMap<String,Body> m=new LinkedHashMap<>();
        m.put("clock",(item,states,live,env)->new CardContent(env.time(),env.date(),env.weekday(),null,null,env.time()+", "+env.weekday()+", "+env.date(),false,0));
        m.put("weather",CardBodies::weather);
        m.put("entity",(item,states,live,env)->{
            EntityStates.Entity e=states.get(item.entity);
            String v=e==null||!e.known()?null:item.attribute==null?e.state:e.attribute(item.attribute);
            return new CardContent(v==null?"Brak danych":v,"",null,false,0,item,live);
        });
        m.put("light",(item,states,live,env)->{
            EntityStates.Entity e=states.get(item.entity);boolean known=e!=null&&e.known();
            String text=!known?"Brak danych":e.state.equals("on")?"Włączone":e.state.equals("off")?"Wyłączone":e.state;
            return new CardContent(text,"",null,known&&e.state.equals("on"),0,item,live);
        });
        m.put("cover",CardBodies::cover);
        m.put("garage",CardBodies::cover);
        m.put("music",(item,states,live,env)->new CardContent(env.musicInfo(),"","",null,null,"Muzyka: "+env.musicInfo(),false,0)); // the player, not HA, says whether this is live
        m.put("cover_group",(item,states,live,env)->{
            DashboardSpec.Cover ca=item.covers.get(0),cb=item.covers.get(1);
            EntityStates.Entity a=states.get(ca.entity),b=states.get(cb.entity);
            String label=defaultLabel(item);
            return new CardContent(CoverText.line("A",a),CoverText.line("B",b),"",null,null,label+": "+ca.title+": "+CoverText.state(a)+", "+cb.title+": "+CoverText.state(b)+(live?"":", dane nieaktualne"),CoverText.attention(a)||CoverText.attention(b),0);
        });
        m.put("tile",CardBodies::tile);
        FOR=Collections.unmodifiableMap(m);
    }
    private static CardContent cover(DashboardSpec.Item item,Map<String,EntityStates.Entity> states,boolean live,Env env){
        EntityStates.Entity e=states.get(item.entity);boolean known=e!=null&&e.known();
        String text=!known?"Brak danych":coverState(e.state),extra="";
        if(known&&e.attribute("current_position")!=null)extra="Otwarcie "+number(e.attribute("current_position"),"%");
        return new CardContent(text,extra,null,known&&!e.state.equals("closed"),0,item,live);
    }
    private static CardContent weather(DashboardSpec.Item item,Map<String,EntityStates.Entity> states,boolean live,Env env){
        if(item.forecast()){
            Forecast.Mode mode=Forecast.mode(states.get(item.forecastWhenEntity),item.forecastWhenState);
            if(mode==Forecast.Mode.UNKNOWN)return new CardContent("—","brak danych o trybie",null,false,0,item,live);
            if(mode==Forecast.Mode.TOMORROW){
                Forecast f=Forecast.parse(states.get(item.forecastEntity),env.now());
                if(f==null)return new CardContent("—","brak prognozy","Jutro",false,0,item,live);
                return new CardContent(f.max(),WeatherLabels.polish(f.condition)+(f.min()==null?"":" · "+f.min()),"Jutro",false,f.validUntilMs,item,live);
            }
        }
        EntityStates.Entity e=states.get(item.entity);boolean known=e!=null&&e.known();
        String temperature=null,unit="";
        if(item.temperatureEntity!=null){EntityStates.Entity t=states.get(item.temperatureEntity);if(t!=null&&t.known()){temperature=t.state;if(t.attribute("unit_of_measurement")!=null)unit=t.attribute("unit_of_measurement");}}
        else if(known){temperature=e.attribute("temperature");if(e.attribute("temperature_unit")!=null)unit=e.attribute("temperature_unit");}
        String text=number(temperature,unit),extra; // no unit in HA means no unit on screen (SPEC 0.8a pkt 3.3)
        List<Row> rows=new ArrayList<>();long expires=0;
        String wind=known?e.attribute("wind_speed"):null,windUnit=known&&e.attribute("wind_speed_unit")!=null?" "+e.attribute("wind_speed_unit"):"";
        boolean column=item.width>1; // a wide tile carries the readings beside the value, a one-cell tile has only the line under it
        if(known)extra=WeatherLabels.polish(e.state)+(wind==null||column?"":" · Wiatr "+number(wind,windUnit));
        else extra="Brak danych";
        if(known&&column){
            // Only what this entity actually reports: met.no gives no apparent temperature, so a missing reading is a missing row, never a guess.
            add(rows,"mdi:weather-windy","Wiatr",wind,windUnit);
            add(rows,"mdi:weather-cloudy","Zachmurzenie",e.attribute("cloud_coverage"),"%");
            add(rows,"mdi:water-percent","Wilgotność",e.attribute("humidity"),"%");
            add(rows,"mdi:gauge","Ciśnienie",e.attribute("pressure"),e.attribute("pressure_unit")==null?"":" "+e.attribute("pressure_unit"));
        }
        if(item.forecastRow()&&column){
            Forecast f=Forecast.parse(states.get(item.forecastEntity),env.now());
            if(f!=null){rows.add(new Row(WeatherLabels.icon(f.condition),"Jutro",f.range(),true));expires=f.validUntilMs;}
        }
        return new CardContent(text,extra,null,known?WeatherLabels.icon(e.state):null,false,expires,item,live,rows);
    }
    /** A reading the entity does not carry adds no row; "—" would claim the clock knows something it does not. */
    private static void add(List<Row> rows,String icon,String label,String raw,String unit){
        if(raw!=null&&!raw.trim().isEmpty())rows.add(new Row(icon,label,number(raw,unit)));
    }
    private static final List<String> ACTIVE=Arrays.asList("on","open","opening","closing","unlocked","unlocking","playing","heating","cooling","cleaning","running");
    /** One entity of any domain: the state in Polish where the word is fixed, a number with its unit, or the configured attribute; the entity's own name and icon unless the config says otherwise. */
    private static CardContent tile(DashboardSpec.Item item,Map<String,EntityStates.Entity> states,boolean live,Env env){
        EntityStates.Entity e=states.get(item.entity);boolean known=e!=null&&e.known();
        String domain=item.entity.substring(0,item.entity.indexOf('.'));
        String text;
        if(!known)text=ActionPolicy.usable("activate",e)&&ActionPolicy.defaultIntent(domain)==ActionPolicy.Intent.ACTIVATE?"—":"Brak danych"; // a scene or button reads unknown until first used, and still works
        else if(item.attribute!=null){String a=e.attribute(item.attribute);text=a==null?"Brak danych":a;}
        else text=stateText(domain,e);
        String label=item.title!=null?item.title:known&&e.attribute("friendly_name")!=null?e.attribute("friendly_name"):null;
        String icon=known&&!item.ownIcon?e.attribute("icon"):null; // the entity's icon only fills in for a config that named none
        if(icon!=null&&(MdiIcons.installed()==null||MdiIcons.name(icon)==null||!MdiIcons.installed().has(MdiIcons.name(icon))))icon=null; // an icon the font lacks falls back to the configured one
        return new CardContent(text,"",label,icon,known&&ACTIVE.contains(e.state),0,item,live);
    }
    private static final List<String> OPENINGS=Arrays.asList("door","window","garage_door","opening","gate"),PRESENCE=Arrays.asList("motion","occupancy","presence","moving");
    static String stateText(String domain,EntityStates.Entity e){
        String s=e.state,cls=e.attribute("device_class");
        switch(domain){
            case "cover":return coverState(s);
            case "lock":switch(s){case "locked":return "Zamknięty";case "unlocked":return "Otwarty";case "locking":return "Zamykanie…";case "unlocking":return "Otwieranie…";case "jammed":return "Zablokowany";default:return s;}
            case "binary_sensor":
                if(OPENINGS.contains(cls))return s.equals("on")?"Otwarte":s.equals("off")?"Zamknięte":s;
                if(PRESENCE.contains(cls))return s.equals("on")?"Wykryto":s.equals("off")?"Brak":s;
                return s.equals("on")?"Tak":s.equals("off")?"Nie":s;
            default:
                if(s.equals("on"))return "Włączone";if(s.equals("off"))return "Wyłączone";
                String unit=e.attribute("unit_of_measurement");
                return unit!=null?decimal(s,unit):s;
        }
    }
    /** Title from the config, else the type's fixed word, else the entity id humanised. */
    static String defaultLabel(DashboardSpec.Item item){
        if(item.title!=null)return item.title;
        CardDefinition def=CardDefinition.of(item.type);
        if(def!=null&&def.defaultTitle!=null)return def.defaultTitle;
        String name=item.entity.substring(item.entity.indexOf('.')+1).replace('_',' ');
        return name.substring(0,1).toUpperCase(new Locale("pl"))+name.substring(1);
    }
    static String number(String raw,String unit){
        if(raw==null)return "—";
        try{return String.format(new Locale("pl"),"%.0f%s",Double.parseDouble(raw),unit);}catch(NumberFormatException e){return raw+unit;}
    }
    /** Up to one decimal, none when whole: "21,4 °C", "21 °C"; a non-number keeps its text and unit. */
    static String decimal(String raw,String unit){
        try{double v=Double.parseDouble(raw);String s=String.format(new Locale("pl"),"%.1f",v);if(s.endsWith(",0"))s=s.substring(0,s.length()-2);return s+" "+unit;}
        catch(NumberFormatException e){return raw+" "+unit;}
    }
    static String coverState(String state){
        switch(state){case "open":return "Otwarta";case "closed":return "Zamknięta";case "opening":return "Otwieranie…";case "closing":return "Zamykanie…";default:return state;}
    }
}
