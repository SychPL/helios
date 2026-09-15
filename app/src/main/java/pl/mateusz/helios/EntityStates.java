package pl.mateusz.helios;

import org.json.*;
import java.util.*;

/** Decoder for HA subscribe_entities snapshots and diffs: state plus only the attributes the dashboard asked for. */
final class EntityStates {
    static final class Entity {
        final String state;
        final Map<String,String> attributes;
        Entity(String state,Map<String,String> attributes){this.state=state;this.attributes=Collections.unmodifiableMap(attributes);}
        boolean known(){return !state.equals("unknown")&&!state.equals("unavailable");}
        String attribute(String name){return attributes.get(name);}
    }
    private final Map<String,Set<String>> wanted;
    private final Map<String,String> states=new HashMap<>();
    private final Map<String,Map<String,String>> attributes=new HashMap<>();
    EntityStates(){this(Collections.emptyMap());}
    EntityStates(Map<String,Set<String>> wanted){this.wanted=wanted;}
    void apply(JSONObject event) throws JSONException {
        JSONObject added=event.optJSONObject("a");
        if(added!=null)for(Iterator<String> i=added.keys();i.hasNext();){
            String id=i.next();JSONObject row=added.getJSONObject(id);
            states.put(id,row.optString("s","unknown"));attributes.remove(id);merge(id,row.optJSONObject("a"));
        }
        JSONObject changed=event.optJSONObject("c");
        if(changed!=null)for(Iterator<String> i=changed.keys();i.hasNext();){
            String id=i.next();JSONObject diff=changed.getJSONObject(id);
            JSONObject plus=diff.optJSONObject("+");
            if(plus!=null){if(plus.has("s"))states.put(id,plus.getString("s"));merge(id,plus.optJSONObject("a"));}
            JSONObject minus=diff.optJSONObject("-");
            JSONArray gone=minus==null?null:minus.optJSONArray("a");
            if(gone!=null&&attributes.containsKey(id))for(int n=0;n<gone.length();n++)attributes.get(id).remove(gone.getString(n));
        }
        JSONArray removed=event.optJSONArray("r");
        if(removed!=null)for(int i=0;i<removed.length();i++){states.remove(removed.getString(i));attributes.remove(removed.getString(i));}
    }
    private void merge(String id,JSONObject values){
        Set<String> keep=wanted.get(id);
        if(values==null||keep==null)return;
        for(String name:keep){
            Object value=values.opt(name);
            if(value==null||value==JSONObject.NULL)continue;
            attributes.computeIfAbsent(id,k->new HashMap<>()).put(name,String.valueOf(value));
        }
    }
    Map<String,Entity> snapshot(){
        Map<String,Entity> out=new HashMap<>();
        for(Map.Entry<String,String> e:states.entrySet())out.put(e.getKey(),new Entity(e.getValue(),new HashMap<>(attributes.getOrDefault(e.getKey(),Collections.emptyMap()))));
        return out;
    }
}
