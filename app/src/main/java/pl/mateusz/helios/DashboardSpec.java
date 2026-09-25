package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Helios grid dashboard contract (helios.version 2-6). Pure data, validated atomically; no device entity IDs. */
final class DashboardSpec {
    static final int COLUMNS=4,ROWS=3,MAX_ITEMS=12,MAX_PAGES=8,MAX_BYTES=65536,MAX_VERSION=6;
    static final String VERSION_ERROR="Wymagana konfiguracja Helios version: 2, 3, 4, 5 lub 6";
    /** The six bare icon names of versions 2-5; at version 6 they are aliases of the same `mdi:` names. */
    static final List<String> ICONS=Arrays.asList("information","weather-rainy","lightbulb","window-shutter","garage-open","music");
    static final List<String> WEATHER_ATTRIBUTES=Arrays.asList("temperature","temperature_unit","wind_speed","wind_speed_unit","humidity","pressure","pressure_unit","cloud_coverage");
    static final List<String> COVER_ATTRIBUTES=Arrays.asList("current_position","supported_features");
    static final List<String> FORECAST_ATTRIBUTES=Arrays.asList("forecast_date","condition","temperature","templow","temperature_unit","fetched_at","valid_until");
    static final List<String> TILE_ATTRIBUTES=Arrays.asList("friendly_name","icon","unit_of_measurement","device_class");
    static final List<String> UNIT_ATTRIBUTES=Collections.singletonList("unit_of_measurement");
    private static final List<String> COMMON=Arrays.asList("id","type","column","row","width","height","title","icon","visible_when","tap_action","confirmation");
    /** Keys every type takes; icon, tap_action and confirmation come from the CardDefinition. */
    private static final List<String> BASE=Arrays.asList("id","type","column","row","width","height","title","visible_when");
    private static final String ENTITY="[a-z0-9_]+\\.[a-z0-9_]+",ID="[a-z0-9_-]{1,40}";
    /** Default `mdi:` icon of a tile by domain (version 6). */
    private static final Map<String,String> TILE_ICONS=new HashMap<>();
    static {
        String[][] rows={{"light","lightbulb"},{"switch","toggle-switch"},{"input_boolean","toggle-switch"},{"fan","fan"},{"cover","window-shutter"},{"lock","lock"},{"sensor","eye"},{"binary_sensor","radiobox-blank"},{"climate","thermostat"},{"script","script-text"},{"scene","palette"},{"input_button","gesture-tap-button"},{"button","gesture-tap-button"},{"media_player","cast"},{"weather","weather-partly-cloudy"}};
        for(String[] r:rows)TILE_ICONS.put(r[0],"mdi:"+r[1]);
    }
    final List<Page> pages;
    /** The first page: what the clock renders in this increment (swiping between pages is a later step). */
    final List<Item> items;
    final int version;
    private DashboardSpec(int version,List<Page> pages){this.version=version;this.pages=Collections.unmodifiableList(pages);this.items=pages.get(0).items;}

    /** One screen of the 4x3 grid (version 6); versions 2-5 are a single implicit page `main`. */
    static final class Page {
        final String id,title;final List<Item> items;
        Page(String id,String title,List<Item> items){this.id=id;this.title=title;this.items=Collections.unmodifiableList(items);}
    }
    /** One roller shutter inside a cover_group: entity for the services, title for the panel row. */
    static final class Cover {final String entity,title;Cover(String entity,String title){this.entity=entity;this.title=title;}}
    /** One warning feeding an `alerts` tile (SPEC 0.20 pkt 2): its text entity, its condition, an optional icon and lights to turn off. */
    static final class Source {
        final String title,entity,icon,whenEntity,whenState,offEntity;final boolean showSince;
        Source(String title,String entity,String icon,String whenEntity,String whenState,String offEntity,boolean showSince){this.title=title;this.entity=entity;this.icon=icon;this.whenEntity=whenEntity;this.whenState=whenState;this.offEntity=offEntity;this.showSince=showSince;}
    }
    /** Card types an `alerts` tile may stand aside for while nothing needs attention. */
    static final List<String> EMPTY_TYPES=Arrays.asList("energy","climate","weather","clock","tile");
    static final class Item {
        final String id,type,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirmText,forecastEntity,forecastWhenEntity,forecastWhenState,offEntity;
        /** energy (SPEC 0.17): the house load, required, and the battery charge, optional; null for every other type. */
        String loadEntity,batteryEntity;
        /** alerts (SPEC 0.20): the warnings in config order, and the card shown in their place while none applies (null = none). */
        List<Source> sources=Collections.emptyList();Item empty;
        final int column,row,width,height;
        final boolean confirm,ownIcon; // ownIcon: the icon was named in the config, so the entity's own icon never replaces it
        final List<Cover> covers;
        Item(String id,String type,int column,int row,int width,int height,String title,String icon,String entity,String temperatureEntity,String attribute,String action,String visibleEntity,String visibleState,boolean confirm,String confirmText){
            this(id,type,column,row,width,height,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirm,confirmText,Collections.emptyList(),null,null,null,null);
        }
        Item(String id,String type,int column,int row,int width,int height,String title,String icon,String entity,String temperatureEntity,String attribute,String action,String visibleEntity,String visibleState,boolean confirm,String confirmText,List<Cover> covers,String forecastEntity,String forecastWhenEntity,String forecastWhenState,String offEntity){
            this(id,type,column,row,width,height,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirm,confirmText,covers,forecastEntity,forecastWhenEntity,forecastWhenState,offEntity,false);
        }
        Item(String id,String type,int column,int row,int width,int height,String title,String icon,String entity,String temperatureEntity,String attribute,String action,String visibleEntity,String visibleState,boolean confirm,String confirmText,List<Cover> covers,String forecastEntity,String forecastWhenEntity,String forecastWhenState,String offEntity,boolean ownIcon){
            this.ownIcon=ownIcon;this.id=id;this.type=type;this.column=column;this.row=row;this.width=width;this.height=height;this.title=title;this.icon=icon;this.entity=entity;this.temperatureEntity=temperatureEntity;this.attribute=attribute;this.action=action;this.visibleEntity=visibleEntity;this.visibleState=visibleState;this.confirm=confirm;this.confirmText=confirmText;
            this.covers=Collections.unmodifiableList(covers);this.forecastEntity=forecastEntity;this.forecastWhenEntity=forecastWhenEntity;this.forecastWhenState=forecastWhenState;this.offEntity=offEntity;
        }
        /** The whole tile swaps to tomorrow when the mode entity is live and equals the configured state (SPEC 0.9 pkt 7.2). */
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
        if(!(root.opt("version") instanceof Integer)||root.getInt("version")<2||root.getInt("version")>MAX_VERSION)throw new IllegalArgumentException(VERSION_ERROR);
        int version=root.getInt("version");
        if(root.toString().length()>MAX_BYTES)throw new IllegalArgumentException("Sekcja helios przekracza 64 KiB");
        List<Page> pages=new ArrayList<>();Set<String> ids=new HashSet<>();
        if(version>=6){
            keys(root,Arrays.asList("version","pages"),"konfiguracji");
            JSONArray list=root.optJSONArray("pages");
            if(list==null||list.length()==0)throw new IllegalArgumentException("Wymagane pole pages");
            if(list.length()>MAX_PAGES)throw new IllegalArgumentException("pages: najwyżej "+MAX_PAGES+" stron");
            Set<String> pageIds=new HashSet<>();
            for(int i=0;i<list.length();i++){
                JSONObject p=list.optJSONObject(i);if(p==null)throw new IllegalArgumentException("pages: pozycja musi być obiektem");
                keys(p,Arrays.asList("id","title","items"),"strony");
                String id=string(p,"id",true,40);
                if(!id.matches(ID))throw new IllegalArgumentException("Nieprawidłowy id strony: "+id);
                if(!pageIds.add(id))throw new IllegalArgumentException("Powtórzony id strony: "+id);
                pages.add(new Page(id,p.has("title")?string(p,"title",true,40):null,items(p.optJSONArray("items"),version,ids)));
            }
        }else{
            keys(root,Arrays.asList("version","grid","items"),"konfiguracji");
            JSONObject grid=root.optJSONObject("grid");
            if(grid==null)throw new IllegalArgumentException("Wymagane pole grid");
            keys(grid,Arrays.asList("columns","rows"),"grid");
            if(integer(grid,"columns",1,COLUMNS)!=COLUMNS||integer(grid,"rows",1,ROWS)!=ROWS)throw new IllegalArgumentException("W 0.5 siatka ma dokładnie 4 kolumny i 3 wiersze");
            pages.add(new Page("main",null,items(root.optJSONArray("items"),version,ids)));
        }
        return new DashboardSpec(version,pages);
    }
    /** One page's items: at most 12, no overlap, one of each singleton type; ids unique across the whole document (pending calls and visibility are keyed by id). */
    private static List<Item> items(JSONArray rows,int version,Set<String> ids) throws Exception {
        if(rows==null)throw new IllegalArgumentException("Wymagane pole items");
        if(rows.length()>MAX_ITEMS)throw new IllegalArgumentException("items: najwyżej "+MAX_ITEMS+" elementów");
        List<Item> result=new ArrayList<>();Set<String> singles=new HashSet<>();boolean[][] used=new boolean[ROWS][COLUMNS];
        for(int i=0;i<rows.length();i++){
            Item item=item(rows.getJSONObject(i),version);
            if(CardDefinition.of(item.type).singleton&&!singles.add(item.type))throw new IllegalArgumentException("Dozwolony jest jeden kafelek "+item.type);
            if(!ids.add(item.id))throw new IllegalArgumentException("Powtórzony id: "+item.id);
            for(int r=item.row;r<item.row+item.height;r++)for(int c=item.column;c<item.column+item.width;c++){
                if(used[r-1][c-1])throw new IllegalArgumentException("Element "+item.id+" nakłada się na inny element");
                used[r-1][c-1]=true;
            }
            result.add(item);
        }
        return result;
    }

    private static Item item(JSONObject o,int version) throws Exception {
        String type=string(o,"type",true,16);
        CardDefinition def=CardDefinition.of(type);
        if(def==null)throw new IllegalArgumentException("Nieobsługiwany typ elementu: "+type);
        if(version<def.minVersion)throw new IllegalArgumentException("Typ "+type+" wymaga version: "+def.minVersion);
        Set<String> allowed=new LinkedHashSet<>(BASE);allowed.addAll(def.fieldsAt(version));
        boolean tappable=def.intentDriven||(def.action!=null&&(def.actionRequires==null||o.has(def.actionRequires)));
        if(tappable&&def.tapConfigurable)allowed.addAll(Arrays.asList("tap_action","confirmation"));
        for(Iterator<String> k=o.keys();k.hasNext();){
            String key=k.next();
            if(!allowed.contains(key))throw new IllegalArgumentException((COMMON.contains(key)||CardDefinition.KNOWN_FIELDS.contains(key)?"Pole niedozwolone dla typu "+type+": ":"Nieznane pole elementu: ")+key);
        }
        String id=string(o,"id",true,40);
        if(!id.matches(ID))throw new IllegalArgumentException("Nieprawidłowy id: "+id);
        int column=integer(o,"column",1,COLUMNS),row=integer(o,"row",1,ROWS),width=integer(o,"width",1,COLUMNS),height=integer(o,"height",1,ROWS);
        if(column+width-1>COLUMNS||row+height-1>ROWS)throw new IllegalArgumentException("Element "+id+" wychodzi poza siatkę");
        String entity=null,temperatureEntity=null,attribute=null,offEntity=null;
        if(o.has("off_entity")){
            offEntity=string(o,"off_entity",true,128);
            if(!offEntity.matches("light\\.[a-z0-9_]+"))throw new IllegalArgumentException("off_entity wymaga encji z domeny light");
        }
        if(allowed.contains("entity")){
            entity=string(o,"entity",true,128);
            if(!entity.matches(ENTITY)||(!def.entityDomain.isEmpty()&&!entity.startsWith(def.entityDomain+".")))throw new IllegalArgumentException("Element "+id+" wymaga encji z domeny "+def.domainLabel());
        }
        if(o.has("temperature_entity")){temperatureEntity=string(o,"temperature_entity",true,128);if(!temperatureEntity.matches("sensor\\.[a-z0-9_]+"))throw new IllegalArgumentException("temperature_entity wymaga encji sensor");}
        if(o.has("attribute")){attribute=string(o,"attribute",true,64);if(!attribute.matches("[a-z0-9_]{1,64}"))throw new IllegalArgumentException("Nieprawidłowy attribute");}
        List<Cover> covers=new ArrayList<>();
        if(allowed.contains("covers")){
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
        // The action: a fixed word per legacy type, or for a tile an intent the entity's domain allows (ActionPolicy).
        String action;ActionPolicy.Intent intent=null;
        if(def.intentDriven){
            String domain=entity.substring(0,entity.indexOf('.'));
            intent=ActionPolicy.defaultIntent(domain);
            if(o.has("tap_action")){
                JSONObject tap=o.optJSONObject("tap_action");if(tap==null)throw new IllegalArgumentException("tap_action musi być obiektem");
                keys(tap,Collections.singletonList("action"),"tap_action");
                String name=string(tap,"action",true,16);intent=ActionPolicy.intent(name);
                if(intent==null||!ActionPolicy.allowed(domain).contains(intent))throw new IllegalArgumentException("Encja "+entity+" nie obsługuje akcji "+name);
            }
            action=intent==ActionPolicy.Intent.NONE?null:ActionPolicy.name(intent);
        }else{
            action=tappable?def.action:null;
            if(o.has("tap_action")){
                JSONObject tap=o.optJSONObject("tap_action");if(tap==null)throw new IllegalArgumentException("tap_action musi być obiektem");
                keys(tap,Collections.singletonList("action"),"tap_action");
                if(!string(tap,"action",true,16).equals(action))throw new IllegalArgumentException("Typ "+type+" dopuszcza wyłącznie tap_action.action: "+action);
            }
        }
        boolean ownIcon=o.has("icon");
        String icon=icon(ownIcon?string(o,"icon",true,40):def.intentDriven?TILE_ICONS.getOrDefault(entity.substring(0,entity.indexOf('.')),"mdi:information"):def.defaultIcon,version);
        String visibleEntity=null,visibleState=null;
        if(o.has("visible_when")){String[] when=when(o,"visible_when");visibleEntity=when[0];visibleState=when[1];}
        boolean forced=intent!=null&&ActionPolicy.forcedConfirm(intent);
        boolean confirm=forced||(tappable&&def.confirmByDefault);String confirmText=null;
        if(o.has("confirmation")){
            JSONObject c=o.optJSONObject("confirmation");if(c==null)throw new IllegalArgumentException("confirmation musi być obiektem");
            keys(c,Arrays.asList("enabled","text"),"confirmation");
            if(!(c.opt("enabled") instanceof Boolean))throw new IllegalArgumentException("confirmation.enabled musi być boolean");
            confirm=c.getBoolean("enabled");
            if(forced&&!confirm)throw new IllegalArgumentException("Akcja "+action+" wymaga potwierdzenia");
            if(c.has("text"))confirmText=string(c,"text",true,80);
        }
        Item out=new Item(id,type,column,row,width,height,title,icon,entity,temperatureEntity,attribute,action,visibleEntity,visibleState,confirm,confirmText,covers,forecastEntity,forecastWhenEntity,forecastWhenState,offEntity,ownIcon);
        if(allowed.contains("load_entity")){
            out.loadEntity=string(o,"load_entity",true,128);
            if(!out.loadEntity.matches("sensor\\.[a-z0-9_]+"))throw new IllegalArgumentException("load_entity wymaga encji sensor");
            if(o.has("battery_entity")){out.batteryEntity=string(o,"battery_entity",true,128);if(!out.batteryEntity.matches("sensor\\.[a-z0-9_]+"))throw new IllegalArgumentException("battery_entity wymaga encji sensor");}
        }
        if(allowed.contains("sources"))alerts(o,out,version);
        return out;
    }
    private static final Set<String> SOURCE_KEYS=new HashSet<>(Arrays.asList("title","entity","icon","when","off_entity","show_since"));
    /** The `alerts` parts: 1-12 sources with distinct conditions, and an optional stand-in card of a plain type. */
    private static void alerts(JSONObject o,Item out,int version) throws Exception {
        boolean size=(out.width==1&&out.height==1)||(out.width==1&&out.height==2)||(out.width==2&&out.height==1);
        if(!size)throw new IllegalArgumentException("Kafelek alerts ma rozmiar 1x1, 1x2 albo 2x1");
        JSONArray list=o.optJSONArray("sources");
        if(list==null||list.length()==0||list.length()>MAX_ITEMS)throw new IllegalArgumentException("alerts wymaga od 1 do "+MAX_ITEMS+" pozycji sources");
        List<Source> sources=new ArrayList<>();Set<String> conditions=new HashSet<>();
        for(int n=0;n<list.length();n++){
            JSONObject src=list.optJSONObject(n);if(src==null)throw new IllegalArgumentException("sources: pozycja musi być obiektem");
            keys(src,new ArrayList<>(SOURCE_KEYS),"sources");
            String title=string(src,"title",true,40),entity=string(src,"entity",true,128);
            if(!entity.matches(ENTITY))throw new IllegalArgumentException("sources: nieprawidłowa encja "+entity);
            String[] when=when(src,"when");
            if(!conditions.add(when[0]+"="+when[1]))throw new IllegalArgumentException("sources: powtórzony warunek "+when[0]+" = "+when[1]);
            String off=null;
            if(src.has("off_entity")){off=string(src,"off_entity",true,128);if(!off.matches("light\\.[a-z0-9_]+"))throw new IllegalArgumentException("off_entity wymaga encji z domeny light");}
            Object since=src.opt("show_since");
            if(since!=null&&!(since instanceof Boolean))throw new IllegalArgumentException("show_since musi być boolean");
            String icon=src.has("icon")?icon(string(src,"icon",true,40),version):null;
            sources.add(new Source(title,entity,icon,when[0],when[1],off,since==null||(Boolean)since));
        }
        out.sources=Collections.unmodifiableList(sources);
        if(o.has("empty")){
            JSONObject e=o.optJSONObject("empty");if(e==null)throw new IllegalArgumentException("empty musi być obiektem");
            for(String k:Arrays.asList("id","column","row","width","height","visible_when"))if(e.has(k))throw new IllegalArgumentException("empty: pole "+k+" bierze się z kafelka alerts");
            String type=string(e,"type",true,16);
            if(!EMPTY_TYPES.contains(type))throw new IllegalArgumentException("empty: dozwolone typy "+String.join(", ",EMPTY_TYPES));
            JSONObject copy=new JSONObject(e.toString()).put("id",out.id).put("column",out.column).put("row",out.row).put("width",out.width).put("height",out.height);
            out.empty=item(copy,version);
        }
    }
    /** Versions 2-5: one of six drawn names. Version 6: `mdi:<name>` from the bundled catalogue, the six old names normalised to it. */
    private static String icon(String icon,int version){
        if(icon==null)return null;
        if(version<6){if(!ICONS.contains(icon))throw new IllegalArgumentException("Nieznana ikona: "+icon);return icon;}
        String name=icon.startsWith("mdi:")?icon.substring(4):ICONS.contains(icon)?icon:null;
        if(name==null)throw new IllegalArgumentException("Nieznana ikona: "+icon);
        MdiIcons mdi=MdiIcons.installed();
        if(mdi==null)throw new IllegalArgumentException("Ikony mdi niezaładowane");
        if(!mdi.has(name))throw new IllegalArgumentException("Nieznana ikona: "+icon);
        return "mdi:"+name;
    }
    /** {entity, state} condition shared by visible_when and forecast_when: a live state only, never unknown/unavailable. */
    private static String[] when(JSONObject o,String key) throws Exception {
        JSONObject when=o.optJSONObject(key);if(when==null)throw new IllegalArgumentException(key+" musi być obiektem");
        keys(when,Arrays.asList("entity","state"),key);
        String entity=string(when,"entity",true,128),state=string(when,"state",true,64);
        if(!entity.matches(ENTITY))throw new IllegalArgumentException("Nieprawidłowa encja "+key);
        if(state.equals("unknown")||state.equals("unavailable"))throw new IllegalArgumentException("Brak danych nie może oznaczać "+(key.equals("visible_when")?"widoczności":key.equals("when")?"ostrzeżenia":"trybu prognozy"));
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

    /** Every page's items in order: visibility, warnings and the forecast look past the page on screen (SPEC 0.18). */
    List<Item> allItems(){List<Item> out=new ArrayList<>();for(Page p:pages)out.addAll(p.items);return out;}
    /** Every item that draws anything: the pages' items plus the stand-in cards of `alerts` tiles, which are not items of their own. */
    List<Item> rendered(){List<Item> out=new ArrayList<>();for(Item i:allItems()){out.add(i);if(i.empty!=null)out.add(i.empty);}return out;}
    /** Any page's item by id; ids are unique across the document. */
    Item item(String id){for(Page p:pages)for(Item i:p.items)if(i.id.equals(id))return i;return null;}
    /** Every entity the renderer or visibility needs on any page, in first-use order, without duplicates. */
    List<String> entities(){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(Item i:rendered()){
            for(Source s:i.sources){out.add(s.whenEntity);out.add(s.entity);out.add(s.offEntity);}
            if(i.entity!=null)out.add(i.entity);for(Cover c:i.covers)out.add(c.entity);if(i.temperatureEntity!=null)out.add(i.temperatureEntity);
            out.add(i.loadEntity);out.add(i.batteryEntity); // energy; nulls go with out.remove(null) below
            if(i.forecastWhenEntity!=null)out.add(i.forecastWhenEntity); // a forecast entity may stand without a mode entity; a null here would be sent to HA and take the whole subscription down
            if(i.forecastEntity!=null)out.add(i.forecastEntity);
            if(i.visibleEntity!=null)out.add(i.visibleEntity);
        }
        out.remove(null);
        return new ArrayList<>(out);
    }
    /** Attributes worth retaining per entity; everything else is dropped at decode time. */
    Map<String,Set<String>> attributes(){
        Map<String,Set<String>> out=new HashMap<>();
        for(Item i:rendered()){
            CardDefinition def=CardDefinition.of(i.type);
            if(i.entity!=null&&!def.attributes.isEmpty())out.computeIfAbsent(i.entity,k->new HashSet<>()).addAll(def.attributes);
            for(Cover c:i.covers)out.computeIfAbsent(c.entity,k->new HashSet<>()).addAll(COVER_ATTRIBUTES);
            if(i.forecastEntity!=null)out.computeIfAbsent(i.forecastEntity,k->new HashSet<>()).addAll(FORECAST_ATTRIBUTES);
            if(i.attribute!=null)out.computeIfAbsent(i.entity,k->new HashSet<>()).add(i.attribute);
            if(i.temperatureEntity!=null)out.computeIfAbsent(i.temperatureEntity,k->new HashSet<>()).add("unit_of_measurement"); // the weather tile shows the sensor's own unit, never a default
            for(String extra:new String[]{i.loadEntity,i.batteryEntity})if(extra!=null)out.computeIfAbsent(extra,k->new HashSet<>()).add("unit_of_measurement");
        }
        return out;
    }
}
