package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Helios 0.5 grid dashboard contract (helios.version 2). Pure data, validated atomically; no device entity IDs. */
final class DashboardSpec {
    static final int COLUMNS=4,ROWS=3,MAX_ITEMS=12,MAX_BYTES=65536;
    static final String VERSION_ERROR="Wymagana konfiguracja Helios version: 2, 3, 4 lub 5";
    static final List<String> ICONS=Arrays.asList("information","weather-rainy","lightbulb","window-shutter","garage-open","music");
    static final List<String> WEATHER_ATTRIBUTES=Arrays.asList("temperature","temperature_unit","wind_speed","wind_speed_unit");
    static final List<String> COVER_ATTRIBUTES=Arrays.asList("current_position","supported_features");
    static final List<String> FORECAST_ATTRIBUTES=Arrays.asList("forecast_date","condition","temperature","templow","temperature_unit","fetched_at","valid_until");
    private static final List<String> COMMON=Arrays.asList("id","type","column","row","width","height","title","icon","visible_when","tap_action","confirmation");
    private static final String ENTITY="[a-z0-9_]+\\.[a-z0-9_]+";
    final List<Item> items;
    final int version;
    private DashboardSpec(int version,List<Item> items){this.version=version;this.items=Collections.unmodifiableList(items);}

    /** One roller shutter inside a cover_group: entity for the services, title for the panel row. */
    static final class Cover {final String entity,title;Cover(String entity,String title){this.entity=entity;this.title=title;}}
    static final class Item {
        final String id,type,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirmText,forecastEntity,forecastWhenEntity,forecastWhenState,offEntity;
        final int column,row,width,height;
        final boolean confirm;
        final List<Cover> covers;
        Item(String id,String type,int column,int row,int width,int height,String title,String icon,String entity,String temperatureEntity,String attribute,String action,String visibleEntity,String visibleState,boolean confirm,String confirmText){
            this(id,type,column,row,width,height,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirm,confirmText,Collections.emptyList(),null,null,null,null);
        }
        Item(String id,String type,int column,int row,int width,int height,String title,String icon,String entity,String temperatureEntity,String attribute,String action,String visibleEntity,String visibleState,boolean confirm,String confirmText,List<Cover> covers,String forecastEntity,String forecastWhenEntity,String forecastWhenState,String offEntity){
            this.id=id;this.type=type;this.column=column;this.row=row;this.width=width;this.height=height;this.title=title;this.icon=icon;this.entity=entity;this.temperatureEntity=temperatureEntity;this.attribute=attribute;this.action=action;this.visibleEntity=visibleEntity;this.visibleState=visibleState;this.confirm=confirm;this.confirmText=confirmText;
            this.covers=Collections.unmodifiableList(covers);this.forecastEntity=forecastEntity;this.forecastWhenEntity=forecastWhenEntity;this.forecastWhenState=forecastWhenState;this.offEntity=offEntity;
        }
        /** Tomorrow's forecast is shown when the mode entity is live and equals the configured state (SPEC 0.9 pkt 7.2). */
        boolean forecast(){return forecastEntity!=null;}
        boolean interactive(){return action!=null;}
        boolean conditional(){return visibleEntity!=null;}
        /** Visibility is decided only from a live state; unknown, unavailable and missing never satisfy the condition. */
        boolean visible(EntityStates.Entity state){
            if(!conditional())return true;
            return state!=null&&state.state.equals(visibleState);
        }
    }

    /** Built-in emergency layout: local clock over the whole grid. Used when no valid version-2 document exists. */
    static DashboardSpec fallback(){
        try{return parse(new JSONObject("{\"version\":2,\"grid\":{\"columns\":4,\"rows\":3},\"items\":[{\"id\":\"clock\",\"type\":\"clock\",\"column\":1,\"row\":1,\"width\":4,\"height\":3}]}"));}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    static DashboardSpec parse(JSONObject root) throws Exception {
        if(!(root.opt("version") instanceof Integer)||root.getInt("version")<2||root.getInt("version")>5)throw new IllegalArgumentException(VERSION_ERROR);
        int version=root.getInt("version");
        if(root.toString().length()>MAX_BYTES)throw new IllegalArgumentException("Sekcja helios przekracza 64 KiB");
        keys(root,Arrays.asList("version","grid","items"),"konfiguracji");
        JSONObject grid=root.optJSONObject("grid");
        if(grid==null)throw new IllegalArgumentException("Wymagane pole grid");
        keys(grid,Arrays.asList("columns","rows"),"grid");
        if(integer(grid,"columns",1,COLUMNS)!=COLUMNS||integer(grid,"rows",1,ROWS)!=ROWS)throw new IllegalArgumentException("W 0.5 siatka ma dokładnie 4 kolumny i 3 wiersze");
        JSONArray rows=root.optJSONArray("items");
        if(rows==null)throw new IllegalArgumentException("Wymagane pole items");
        if(rows.length()>MAX_ITEMS)throw new IllegalArgumentException("items: najwyżej "+MAX_ITEMS+" elementów");
        List<Item> result=new ArrayList<>();Set<String> ids=new HashSet<>();boolean[][] used=new boolean[ROWS][COLUMNS];int music=0;
        for(int i=0;i<rows.length();i++){
            Item item=item(rows.getJSONObject(i),version);
            if(item.type.equals("music")&&++music>1)throw new IllegalArgumentException("Dozwolony jest jeden kafelek music");
            if(!ids.add(item.id))throw new IllegalArgumentException("Powtórzony id: "+item.id);
            for(int r=item.row;r<item.row+item.height;r++)for(int c=item.column;c<item.column+item.width;c++){
                if(used[r-1][c-1])throw new IllegalArgumentException("Element "+item.id+" nakłada się na inny element");
                used[r-1][c-1]=true;
            }
            result.add(item);
        }
        return new DashboardSpec(version,result);
    }

    private static Item item(JSONObject o,int version) throws Exception {
        String type=string(o,"type",true,16);
        List<String> allowed=new ArrayList<>(COMMON);
        String action;
        switch(type){
            case "clock":allowed.removeAll(Arrays.asList("icon","tap_action","confirmation"));action=null;break;
            case "weather":allowed.removeAll(Arrays.asList("icon","tap_action","confirmation"));allowed.addAll(Arrays.asList("entity","temperature_entity"));if(version>=4)allowed.addAll(Arrays.asList("forecast_entity","forecast_when"));action=null;break;
            case "entity":
                allowed.addAll(Arrays.asList("entity","attribute"));
                if(version>=5&&o.has("off_entity"))action="lights_off";  // SPEC 0.12: a read-only tile that can turn its lights off
                else{allowed.removeAll(Arrays.asList("tap_action","confirmation"));action=null;}
                if(version>=5)allowed.add("off_entity");
                break;
            case "light":allowed.add("entity");action="toggle";break;
            case "cover":allowed.add("entity");action="controls";break;
            case "garage":allowed.add("entity");action="close";break;
            case "music":
                if(version<3)throw new IllegalArgumentException("Typ music wymaga version: 3");
                allowed.removeAll(Arrays.asList("tap_action","confirmation"));action="library";break;
            case "cover_group":
                if(version<4)throw new IllegalArgumentException("Typ cover_group wymaga version: 4");
                allowed.removeAll(Arrays.asList("tap_action","confirmation"));allowed.add("covers");action="covers";break;
            default:throw new IllegalArgumentException("Nieobsługiwany typ elementu: "+type);
        }
        for(Iterator<String> k=o.keys();k.hasNext();){
            String key=k.next();
            if(!allowed.contains(key))throw new IllegalArgumentException((COMMON.contains(key)||Arrays.asList("entity","temperature_entity","attribute","covers","forecast_entity","forecast_when").contains(key)?"Pole niedozwolone dla typu "+type+": ":"Nieznane pole elementu: ")+key);
        }
        String id=string(o,"id",true,40);
        if(!id.matches("[a-z0-9_-]{1,40}"))throw new IllegalArgumentException("Nieprawidłowy id: "+id);
        int column=integer(o,"column",1,COLUMNS),row=integer(o,"row",1,ROWS),width=integer(o,"width",1,COLUMNS),height=integer(o,"height",1,ROWS);
        if(column+width-1>COLUMNS||row+height-1>ROWS)throw new IllegalArgumentException("Element "+id+" wychodzi poza siatkę");
        String entity=null,temperatureEntity=null,attribute=null,offEntity=null;
        if(o.has("off_entity")){
            offEntity=string(o,"off_entity",true,128);
            if(!offEntity.matches("light\\.[a-z0-9_]+"))throw new IllegalArgumentException("off_entity wymaga encji z domeny light");
        }
        if(allowed.contains("entity")){
            entity=string(o,"entity",true,128);
            String domain=type.equals("garage")?"cover":type;
            if(!entity.matches(ENTITY)||(!type.equals("entity")&&!entity.startsWith(domain+".")))throw new IllegalArgumentException("Element "+id+" wymaga encji z domeny "+domain);
        }
        if(o.has("temperature_entity")){temperatureEntity=string(o,"temperature_entity",true,128);if(!temperatureEntity.matches("sensor\\.[a-z0-9_]+"))throw new IllegalArgumentException("temperature_entity wymaga encji sensor");}
        if(o.has("attribute")){attribute=string(o,"attribute",true,64);if(!attribute.matches("[a-z0-9_]{1,64}"))throw new IllegalArgumentException("Nieprawidłowy attribute");}
        List<Cover> covers=new ArrayList<>();
        if(type.equals("cover_group")){
            JSONArray list=o.optJSONArray("covers");
            if(list==null||list.length()!=2)throw new IllegalArgumentException("cover_group wymaga dokładnie dwóch pozycji covers");
            for(int n=0;n<2;n++){
                JSONObject c=list.optJSONObject(n);if(c==null)throw new IllegalArgumentException("covers: pozycja musi być obiektem");
                keys(c,Arrays.asList("entity","title"),"covers");
                String coverEntity=string(c,"entity",true,128),coverTitle=string(c,"title",true,40);
                if(!coverEntity.matches("cover\\.[a-z0-9_]+"))throw new IllegalArgumentException("covers wymaga encji z domeny cover");
                covers.add(new Cover(coverEntity,coverTitle));
            }
            if(covers.get(0).entity.equals(covers.get(1).entity))throw new IllegalArgumentException("covers: ta sama roleta dwa razy");
        }
        String forecastEntity=null,forecastWhenEntity=null,forecastWhenState=null;
        if(o.has("forecast_entity")||o.has("forecast_when")){
            if(!(o.has("forecast_entity")&&o.has("forecast_when")))throw new IllegalArgumentException("weather: forecast_entity i forecast_when występują razem");
            forecastEntity=string(o,"forecast_entity",true,128);
            if(!forecastEntity.matches("sensor\\.[a-z0-9_]+"))throw new IllegalArgumentException("forecast_entity wymaga encji sensor");
            String[] when=when(o,"forecast_when");forecastWhenEntity=when[0];forecastWhenState=when[1];
        }
        String title=o.has("title")?string(o,"title",true,40):null;
        String icon=null;
        if(o.has("icon")){icon=string(o,"icon",true,40);if(!ICONS.contains(icon))throw new IllegalArgumentException("Nieznana ikona: "+icon);}
        else switch(type){case "entity":icon="information";break;case "light":icon="lightbulb";break;case "cover":case "cover_group":icon="window-shutter";break;case "garage":icon="garage-open";break;case "music":icon="music";break;default:break;}
        String visibleEntity=null,visibleState=null;
        if(o.has("visible_when")){String[] when=when(o,"visible_when");visibleEntity=when[0];visibleState=when[1];}
        if(o.has("tap_action")){
            JSONObject tap=o.optJSONObject("tap_action");if(tap==null)throw new IllegalArgumentException("tap_action musi być obiektem");
            keys(tap,Collections.singletonList("action"),"tap_action");
            if(!string(tap,"action",true,16).equals(action))throw new IllegalArgumentException("Typ "+type+" dopuszcza wyłącznie tap_action.action: "+action);
        }
        boolean confirm=type.equals("garage")||offEntity!=null;String confirmText=null;
        if(o.has("confirmation")){
            JSONObject c=o.optJSONObject("confirmation");if(c==null)throw new IllegalArgumentException("confirmation musi być obiektem");
            keys(c,Arrays.asList("enabled","text"),"confirmation");
            if(!(c.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("confirmation.enabled musi być boolean");
            confirm=c.getBoolean("enabled");
            if(c.has("text"))confirmText=string(c,"text",true,80);
        }
        return new Item(id,type,column,row,width,height,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirm,confirmText,covers,forecastEntity,forecastWhenEntity,forecastWhenState,offEntity);
    }
    /** {entity, state} condition shared by visible_when and forecast_when: a live state only, never unknown/unavailable. */
    private static String[] when(JSONObject o,String key) throws Exception {
        JSONObject when=o.optJSONObject(key);if(when==null)throw new IllegalArgumentException(key+" musi być obiektem");
        keys(when,Arrays.asList("entity","state"),key);
        String entity=string(when,"entity",true,128),state=string(when,"state",true,64);
        if(!entity.matches(ENTITY))throw new IllegalArgumentException("Nieprawidłowa encja "+key);
        if(state.equals("unknown")||state.equals("unavailable"))throw new IllegalArgumentException("Brak danych nie może oznaczać "+(key.equals("visible_when")?"widoczności":"trybu prognozy"));
        return new String[]{entity,state};
    }

    private static void keys(JSONObject o,List<String> allowed,String where) throws Exception {
        for(Iterator<String> k=o.keys();k.hasNext();){String key=k.next();if(!allowed.contains(key))throw new IllegalArgumentException("Nieznane pole "+where+": "+key);}
    }
    private static String string(JSONObject o,String key,boolean required,int max) throws Exception {
        Object value=o.opt(key);
        if(value==null){if(required)throw new IllegalArgumentException("Wymagane pole "+key);return null;}
        if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>max)throw new IllegalArgumentException("Nieprawidłowe pole "+key);
        return (String)value;
    }
    private static int integer(JSONObject o,String key,int min,int max) throws Exception {
        Object value=o.opt(key);
        if(!(value instanceof Integer)||(Integer)value<min||(Integer)value>max)throw new IllegalArgumentException("Pole "+key+" musi być liczbą od "+min+" do "+max);
        return (Integer)value;
    }

    Item item(String id){for(Item i:items)if(i.id.equals(id))return i;return null;}
    /** Every entity the renderer or visibility needs, in first-use order, without duplicates. */
    List<String> entities(){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(Item i:items){
            if(i.entity!=null)out.add(i.entity);for(Cover c:i.covers)out.add(c.entity);if(i.temperatureEntity!=null)out.add(i.temperatureEntity);
            if(i.forecastEntity!=null){out.add(i.forecastWhenEntity);out.add(i.forecastEntity);}
            if(i.visibleEntity!=null)out.add(i.visibleEntity);
        }
        return new ArrayList<>(out);
    }
    /** Attributes worth retaining per entity; everything else is dropped at decode time. */
    Map<String,Set<String>> attributes(){
        Map<String,Set<String>> out=new HashMap<>();
        for(Item i:items){
            if(i.type.equals("weather"))out.computeIfAbsent(i.entity,k->new HashSet<>()).addAll(WEATHER_ATTRIBUTES);
            if(i.type.equals("cover")||i.type.equals("garage"))out.computeIfAbsent(i.entity,k->new HashSet<>()).addAll(COVER_ATTRIBUTES);
            for(Cover c:i.covers)out.computeIfAbsent(c.entity,k->new HashSet<>()).addAll(COVER_ATTRIBUTES);
            if(i.forecastEntity!=null)out.computeIfAbsent(i.forecastEntity,k->new HashSet<>()).addAll(FORECAST_ATTRIBUTES);
            if(i.attribute!=null)out.computeIfAbsent(i.entity,k->new HashSet<>()).add(i.attribute);
            if(i.temperatureEntity!=null)out.computeIfAbsent(i.temperatureEntity,k->new HashSet<>()).add("unit_of_measurement"); // the weather tile shows the sensor's own unit, never a default
        }
        return out;
    }
}
