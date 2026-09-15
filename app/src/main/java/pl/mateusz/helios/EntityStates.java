package pl.mateusz.helios;

import org.json.*;
import java.util.*;

/** Minimal state-only decoder for HA subscribe_entities snapshots and diffs. */
final class EntityStates {
    private final Map<String,String> states=new HashMap<>();
    void apply(JSONObject event) throws JSONException {
        JSONObject added=event.optJSONObject("a");
        if(added!=null)for(Iterator<String> i=added.keys();i.hasNext();){String id=i.next();states.put(id,added.getJSONObject(id).optString("s","unknown"));}
        JSONObject changed=event.optJSONObject("c");
        if(changed!=null)for(Iterator<String> i=changed.keys();i.hasNext();){String id=i.next();JSONObject plus=changed.getJSONObject(id).optJSONObject("+");if(plus!=null&&plus.has("s"))states.put(id,plus.getString("s"));}
        JSONArray removed=event.optJSONArray("r");if(removed!=null)for(int i=0;i<removed.length();i++)states.remove(removed.getString(i));
    }
    Map<String,String> snapshot(){return new HashMap<>(states);}
}
