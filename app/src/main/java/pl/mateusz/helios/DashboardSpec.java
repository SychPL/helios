package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Data-only display rules supplied by HA; no device-specific entity IDs. */
final class DashboardSpec {
    final boolean clock;
    final String weather;
    final List<Indicator> indicators;
    private DashboardSpec(boolean clock,String weather,List<Indicator> indicators){this.clock=clock;this.weather=weather;this.indicators=Collections.unmodifiableList(indicators);}
    static final class Indicator {
        final String entity,name,label,icon,color;
        final Set<String> active,clear;
        Indicator(String entity,String name,String label,String icon,String color,Set<String> active,Set<String> clear){
            this.entity=entity;this.name=name;this.label=label;this.icon=icon;this.color=color;this.active=active;this.clear=clear;
        }
        // Never treat a missing, unavailable or unexpected classification as clear.
        String display(String state,boolean live){
            if(!live||state==null||state.equals("unknown")||state.equals("unavailable"))return name+" — brak danych";
            if(active.contains(state))return label;
            if(clear.contains(state))return null;
            return name+" — nieznany stan";
        }
        boolean isActive(String state,boolean live){return live&&state!=null&&active.contains(state)&&!state.equals("unknown")&&!state.equals("unavailable");}
    }
    static DashboardSpec parse(JSONObject root) throws Exception {
        if(root.length()>8||root.optInt("version",0)!=1)throw new IllegalArgumentException("Wymagane version: 1");
        Set<String> keys=new HashSet<>(Arrays.asList("version","clock","weather","indicators"));
        for(Iterator<String> i=root.keys();i.hasNext();)if(!keys.contains(i.next()))throw new IllegalArgumentException("Nieznane pole konfiguracji");
        if(root.has("clock")&&!(root.get("clock") instanceof Boolean))throw new IllegalArgumentException("clock musi być boolean");
        String weather=root.isNull("weather")?null:root.getString("weather");
        if(weather!=null&&!weather.matches("weather\\.[a-z0-9_]+"))throw new IllegalArgumentException("Nieprawidłowa encja pogody");
        JSONArray rows=root.optJSONArray("indicators");
        if(rows==null||rows.length()>3)throw new IllegalArgumentException("indicators: lista do 3 wskaźników");
        List<Indicator> result=new ArrayList<>();Set<String> entities=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            Set<String> allowed=new HashSet<>(Arrays.asList("entity","name","label","icon","color","when","clear_when"));
            for(Iterator<String> k=row.keys();k.hasNext();)if(!allowed.contains(k.next()))throw new IllegalArgumentException("Nieznane pole wskaźnika");
            String entity=required(row,"entity",128);
            if(!entity.matches("[a-z0-9_]+\\.[a-z0-9_]+")||!entities.add(entity))throw new IllegalArgumentException("Nieprawidłowa lub powtórzona encja");
            String name=required(row,"name",28),label=required(row,"label",48);
            String icon=row.optString("icon","alert"),color=row.optString("color","orange");
            if(!Arrays.asList("garage-open","door-open","window-open","alert","lightbulb").contains(icon))throw new IllegalArgumentException("Nieobsługiwana ikona");
            if(!Arrays.asList("orange","red","yellow","green","blue").contains(color))throw new IllegalArgumentException("Nieobsługiwany kolor");
            Set<String> active=states(row.getJSONArray("when")),clear=states(row.getJSONArray("clear_when"));
            if(!Collections.disjoint(active,clear))throw new IllegalArgumentException("when i clear_when muszą być rozłączne");
            result.add(new Indicator(entity,name,label,icon,color,active,clear));
        }
        return new DashboardSpec(root.optBoolean("clock",true),weather,result);
    }
    private static String required(JSONObject o,String key,int max) throws Exception {
        Object value=o.get(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>max)throw new IllegalArgumentException("Nieprawidłowe pole "+key);return (String)value;
    }
    private static Set<String> states(JSONArray array) throws Exception {
        if(array.length()==0||array.length()>16)throw new IllegalArgumentException("Wymagana lista stanów");
        Set<String> result=new HashSet<>();
        for(int i=0;i<array.length();i++){
            Object value=array.get(i);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>128)throw new IllegalArgumentException("Stan musi być tekstem");
            if(value.equals("unknown")||value.equals("unavailable"))throw new IllegalArgumentException("Brak danych nie może oznaczać stanu poprawnego");
            result.add((String)value);
        }
        return Collections.unmodifiableSet(result);
    }
    List<String> entities(){List<String> out=new ArrayList<>();for(Indicator i:indicators)out.add(i.entity);return out;}
}
