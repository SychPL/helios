package pl.mateusz.helios;

import java.util.*;

/** Schema and rendering facts for one card type. Pure data: no JSON, no Views, no sockets. Adding a type means one row here, one body in CardBodies and, if it taps, one rule in ActionPolicy. */
final class CardDefinition {
    /** How the tile sizes its text: the clock measures its hour, weather keeps a big fixed value, the rest fit the value to the width. */
    enum Layout {CLOCK,LARGE_VALUE,FIT}
    /** When the tile is tappable: always, only with a known state, or only with a known state and no call in flight. */
    enum Gate {NONE,KNOWN,KNOWN_NOT_PENDING}
    /** Which event re-renders the tile: the entity snapshot, the local clock or the music player. */
    enum Feed {ENTITIES,CLOCK,MUSIC}
    final String type;final int minVersion;
    final Map<String,Integer> fields;      // extra allowed key -> schema version that introduced it
    final String entityDomain;             // null = no entity field; "" = any domain
    final String action,actionRequires;    // Item.action; with actionRequires set the tile taps only when that field is present
    final boolean tapConfigurable,confirmByDefault,singleton,notifies;
    final String defaultIcon,defaultTitle; // defaultTitle null = humanised entity id
    final Set<String> attributes;          // retained on `entity`
    final Layout layout;final Gate gate;final Feed feed;
    private CardDefinition(String type,int minVersion,Map<String,Integer> fields,String entityDomain,String action,String actionRequires,boolean tapConfigurable,boolean confirmByDefault,boolean singleton,boolean notifies,String defaultIcon,String defaultTitle,List<String> attributes,Layout layout,Gate gate,Feed feed){
        this.type=type;this.minVersion=minVersion;this.fields=Collections.unmodifiableMap(fields);this.entityDomain=entityDomain;this.action=action;this.actionRequires=actionRequires;
        this.tapConfigurable=tapConfigurable;this.confirmByDefault=confirmByDefault;this.singleton=singleton;this.notifies=notifies;this.defaultIcon=defaultIcon;this.defaultTitle=defaultTitle;
        this.attributes=Collections.unmodifiableSet(new HashSet<>(attributes));this.layout=layout;this.gate=gate;this.feed=feed;
    }
    private static Map<String,Integer> fields(Object... pairs){LinkedHashMap<String,Integer> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],(Integer)pairs[i+1]);return m;}
    private static final List<String> NONE=Collections.emptyList();

    static final Map<String,CardDefinition> ALL;
    /** Every key any type accepts, for telling "Pole niedozwolone dla typu" from "Nieznane pole elementu". */
    static final Set<String> KNOWN_FIELDS;
    static {
        LinkedHashMap<String,CardDefinition> all=new LinkedHashMap<>();
        for(CardDefinition d:new CardDefinition[]{
            new CardDefinition("clock",2,fields(),null,null,null,false,false,false,false,null,"",NONE,Layout.CLOCK,Gate.NONE,Feed.CLOCK),
            new CardDefinition("weather",2,fields("entity",2,"temperature_entity",2,"forecast_entity",4,"forecast_when",4),"weather",null,null,false,false,false,false,null,"Pogoda",DashboardSpec.WEATHER_ATTRIBUTES,Layout.LARGE_VALUE,Gate.NONE,Feed.ENTITIES),
            // schema 5: a read-only tile that may turn its lights off, tappable and confirmed only with off_entity (docs/ha-dashboard.md)
            new CardDefinition("entity",2,fields("entity",2,"attribute",2,"icon",2,"off_entity",5),"","lights_off","off_entity",true,true,false,true,"information",null,NONE,Layout.FIT,Gate.NONE,Feed.ENTITIES),
            new CardDefinition("light",2,fields("entity",2,"icon",2),"light","toggle",null,true,false,false,false,"lightbulb",null,NONE,Layout.FIT,Gate.KNOWN_NOT_PENDING,Feed.ENTITIES),
            new CardDefinition("cover",2,fields("entity",2,"icon",2),"cover","controls",null,true,false,false,false,"window-shutter",null,DashboardSpec.COVER_ATTRIBUTES,Layout.FIT,Gate.KNOWN,Feed.ENTITIES),
            new CardDefinition("garage",2,fields("entity",2,"icon",2),"cover","close",null,true,true,false,false,"garage-open",null,DashboardSpec.COVER_ATTRIBUTES,Layout.FIT,Gate.KNOWN_NOT_PENDING,Feed.ENTITIES),
            new CardDefinition("music",3,fields("icon",3),null,"library",null,false,false,true,false,"music","Muzyka",NONE,Layout.FIT,Gate.NONE,Feed.MUSIC),
            new CardDefinition("cover_group",4,fields("covers",4,"icon",4),null,"covers",null,false,false,false,false,"window-shutter","Rolety",NONE,Layout.FIT,Gate.NONE,Feed.ENTITIES),
        })all.put(d.type,d);
        ALL=Collections.unmodifiableMap(all);
        Set<String> known=new HashSet<>();for(CardDefinition d:all.values())known.addAll(d.fields.keySet());
        KNOWN_FIELDS=Collections.unmodifiableSet(known);
    }
    /** null for a type the schema does not know. */
    static CardDefinition of(String type){return ALL.get(type);}
    /** The extra keys a document of this schema version may use for the type. */
    Set<String> fieldsAt(int version){LinkedHashSet<String> out=new LinkedHashSet<>();for(Map.Entry<String,Integer> f:fields.entrySet())if(f.getValue()<=version)out.add(f.getKey());return out;}
    /** Domain shown in the "wymaga encji z domeny" message: the type itself when any domain is accepted. */
    String domainLabel(){return entityDomain==null||entityDomain.isEmpty()?type:entityDomain;}
}
