package pl.mateusz.helios;

import org.json.JSONArray;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * One climate entity as the card and its panel see it (SPEC 0.19): what can be edited, the next setpoint, which option
 * lists exist, Polish words. Pure: built from an EntityStates snapshot, no views, no sockets.
 */
final class ClimateModel {
    static final String PROFILE="Profil",FAN="Siła nawiewu";
    static final int TARGET_TEMPERATURE=1,TARGET_RANGE=2,FAN_MODE=8,PRESET_MODE=16,SWING_MODE=32,SWING_HORIZONTAL=512;
    /** Every attribute the card needs; the renderer keeps these and drops the rest. */
    static final List<String> ATTRIBUTES=Arrays.asList("current_temperature","temperature","target_temp_low","target_temp_high","target_temp_step",
        "min_temp","max_temp","hvac_modes","hvac_action","preset_mode","preset_modes","fan_mode","fan_modes","swing_mode","swing_modes",
        "swing_horizontal_mode","swing_horizontal_modes","supported_features","friendly_name");

    /** One option list below the mode row: its button label, the service that sets it and the service's one key. */
    static final class Group {
        final String label,service,key,current;final List<String> options;
        Group(String label,String service,String key,String current,List<String> options){this.label=label;this.service=service;this.key=key;this.current=current;this.options=Collections.unmodifiableList(options);}
    }

    final String state,action;
    final Double current,target,low,high,min,max;
    final double step;
    final int features;
    final List<String> modes;
    final List<Group> groups;
    final boolean known;

    /** fahrenheit: HA's unit system says °F, which makes the default step 1 instead of 0.5. */
    ClimateModel(EntityStates.Entity e,boolean fahrenheit){
        known=e!=null&&e.known();
        state=e==null?"unavailable":e.state;
        action=e==null?null:e.attribute("hvac_action");
        current=number(e,"current_temperature");target=number(e,"temperature");low=number(e,"target_temp_low");high=number(e,"target_temp_high");
        min=number(e,"min_temp");max=number(e,"max_temp");
        Double s=number(e,"target_temp_step");
        step=s!=null&&s>0?s:fahrenheit?1:0.5;
        Double f=number(e,"supported_features");features=f==null?0:(int)Math.round(f);
        modes=list(e==null?null:e.attribute("hvac_modes"));
        List<Group> g=new ArrayList<>();
        group(g,e,PRESET_MODE,PROFILE,"set_preset_mode","preset_mode","preset_modes");
        group(g,e,FAN_MODE,FAN,"set_fan_mode","fan_mode","fan_modes");
        group(g,e,SWING_MODE,"Kierunek pionowy","set_swing_mode","swing_mode","swing_modes");
        group(g,e,SWING_HORIZONTAL,"Kierunek poziomy","set_swing_horizontal_mode","swing_horizontal_mode","swing_horizontal_modes");
        groups=Collections.unmodifiableList(g);
    }
    private void group(List<Group> out,EntityStates.Entity e,int bit,String label,String service,String key,String listKey){
        List<String> options=list(e==null?null:e.attribute(listKey));
        if((features&bit)!=0&&!options.isEmpty())out.add(new Group(label,service,key,e.attribute(key),options));
    }
    boolean has(int bit){return (features&bit)!=0;}

    /** A single setpoint the panel may change: the feature, a finite value, sane bounds and step (pkt 5.1). */
    boolean editable(){return known&&has(TARGET_TEMPERATURE)&&target!=null&&min!=null&&max!=null&&min<=max&&step>0;}
    /** A read-only range when there is no single setpoint. */
    boolean range(){return !editable()&&has(TARGET_RANGE)&&low!=null&&high!=null;}
    String setpoint(){return editable()?degrees(target):range()?degrees(low).replace("°","")+"–"+degrees(high):"—";}

    /** The next setpoint from `from` one step up (+1) or down (-1): snap to the step grid first, clamp to HA's own bounds last. */
    double next(double from,int direction){
        BigDecimal st=BigDecimal.valueOf(step);
        BigDecimal n=BigDecimal.valueOf(from).divide(st,0,RoundingMode.HALF_UP).add(BigDecimal.valueOf(direction));
        double v=n.multiply(st).setScale(Math.max(0,st.stripTrailingZeros().scale()),RoundingMode.HALF_UP).doubleValue();
        if(v>max)return max; // the endpoint exactly as HA gave it, never re-rounded to the step
        if(v<min)return min;
        return v;
    }
    /** A button stays active while the value can still move that way. */
    boolean canStep(double from,int direction){return editable()&&(direction>0?from<max:from>min);}

    /** The value is allowed on the entity as it is now: HA-bounded temperature or a member of the current option list. */
    boolean allows(String service,Object value){
        if(!known)return false;
        switch(service){
            case "set_temperature":return editable()&&value instanceof Double&&Double.isFinite((Double)value)&&(Double)value>=min&&(Double)value<=max;
            case "set_hvac_mode":return modes.contains(value);
            default:for(Group g:groups)if(g.service.equals(service))return g.options.contains(value);return false;
        }
    }

    // --- words and icons ---
    boolean working(){return "heating".equals(action)||"preheating".equals(action)||"cooling".equals(action);}
    /** Accent-free unless the device is working; off has its own shape so "off" and "idle" never look alike. */
    String icon(){
        if(!known)return "mdi:thermostat";
        if(state.equals("off"))return "mdi:power";
        if(action==null)return "mdi:thermostat";
        switch(action){case "heating":case "preheating":return "mdi:fire";case "cooling":return "mdi:snowflake";case "drying":return "mdi:water-percent";
            case "fan":return "mdi:fan";case "defrosting":return "mdi:snowflake-melt";default:return "mdi:thermostat";}
    }
    /** null when HA does not say what the device is doing: an absent action is never shown as "idle". */
    String actionWord(){
        if(action==null)return null;
        switch(action){case "heating":return "Grzeje";case "preheating":return "Nagrzewa";case "cooling":return "Chłodzi";case "drying":return "Osusza";
            case "fan":return "Wentyluje";case "idle":return "Bezczynny";case "off":return "Wyłączony";case "defrosting":return "Odszrania";default:return human(action);}
    }
    static String mode(String m,boolean compact){
        switch(m){case "heat":return "Grzanie";case "cool":return "Chłodzenie";case "heat_cool":return "Grzanie/chł.";case "auto":return "Auto";
            case "dry":return "Osuszanie";case "fan_only":return "Wentylator";case "off":return compact?"Wył.":"Wyłączony";default:return human(m);}
    }
    /** Standard HA option words in Polish; direction values word by word; anything else is HA's own text made readable. */
    static String option(String group,String v){
        if(v==null)return "—";
        if(group.equals(PROFILE))switch(v){case "none":return "Standardowy";case "away":return "Poza domem";case "eco":return "Eco";case "boost":return "Boost";case "sleep":return "Sen";
            case "comfort":return "Komfort";case "home":return "Dom";case "activity":return "Aktywność";default:return human(v);}
        if(group.equals(FAN))switch(v){case "auto":return "Auto";case "low":return "Niska";case "medium":return "Średnia";case "high":return "Wysoka";case "off":return "Wyłączona";default:return human(v);}
        return direction(v);
    }
    private static final Map<String,String> HEADS=new HashMap<>(),PARTS=new HashMap<>();
    static {
        String[][] heads={{"fixed","Stały"},{"swing","Ruch"},{"default","Domyślny"},{"off","Wyłączony"},{"on","Włączony"},{"both","Oba"},{"vertical","Pionowy"},{"horizontal","Poziomy"}};
        String[][] parts={{"full","pełny"},{"upper","góra"},{"lower","dół"},{"middle","środek"},{"center","środek"},{"left","lewo"},{"right","prawo"}};
        for(String[] h:heads)HEADS.put(h[0],h[1]);for(String[] p:parts)PARTS.put(p[0],p[1]);
    }
    /** "fixed_upper_middle" -> "Stały: góra-środek", "full_swing" -> "Ruch: pełny"; only when every word is known, else HA's text. */
    static String direction(String v){
        String[] w=v.split("_");
        if(w.length==1&&HEADS.containsKey(w[0]))return HEADS.get(w[0]);
        String head=null;List<String> rest=new ArrayList<>();
        for(String word:w){
            if(head==null&&HEADS.containsKey(word)&&!word.equals("default"))head=HEADS.get(word);
            else if(PARTS.containsKey(word))rest.add(PARTS.get(word));
            else return human(v);
        }
        if(rest.isEmpty())return human(v);
        String parts=String.join("-",rest);
        return head==null?Character.toUpperCase(parts.charAt(0))+parts.substring(1):head+": "+parts; // "left_center" -> "Lewo-środek"
    }
    static String human(String raw){String s=raw.replace('_',' ').trim();return s.isEmpty()?raw:Character.toUpperCase(s.charAt(0))+s.substring(1);}

    /** The line under the tile's value (pkt 4). */
    String tileDetail(){
        if(!known)return "Brak połączenia";
        if(state.equals("off"))return "Wyłączony";
        if(editable()||range())return "Zadana "+setpoint();
        return mode(state,false);
    }
    /** Always a measured temperature: never the setpoint in its place, never an old value while unavailable. */
    String currentText(){return !known||current==null?"—":degrees1(current);}

    // --- parsing ---
    static String degrees(double v){return String.format(new Locale("pl"),"%.1f°",v).replace(",0°","°").replace("-0°","0°");}
    /** One decimal always on the big "now" value, so it does not jump between 23° and 23,5°. */
    static String degrees1(double v){return String.format(new Locale("pl"),"%.1f°",v);}
    private static Double number(EntityStates.Entity e,String key){
        String raw=e==null?null:e.attribute(key);
        if(raw==null)return null;
        try{double v=Double.parseDouble(raw);return Double.isFinite(v)?v:null;}catch(NumberFormatException x){return null;}
    }
    /** A list attribute arrives as JSON text (EntityStates keeps String.valueOf of the JSONArray). */
    static List<String> list(String json){
        List<String> out=new ArrayList<>();
        if(json==null)return out;
        try{JSONArray a=new JSONArray(json);for(int i=0;i<a.length();i++){String v=a.optString(i,null);if(v!=null&&!v.isEmpty())out.add(v);}}
        catch(Exception ignored){}
        return out;
    }
}
