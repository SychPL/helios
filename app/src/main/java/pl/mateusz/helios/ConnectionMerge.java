package pl.mateusz.helios;

import org.json.*;

/** Merges a `connection` event from the integration into the stored connection (SPEC 0.10 pkt 6.3); pure, no I/O. */
final class ConnectionMerge {
    private ConnectionMerge(){}
    /** Returns the merged connection or null when nothing changed; never touches url/token/protocol. */
    static JSONObject apply(JSONObject current,JSONObject event){
        try{
            JSONObject merged=new JSONObject(current.toString());
            String pipeline=event.optString("pipeline","");if(!pipeline.isEmpty())merged.put("pipeline",pipeline);
            String path=event.optString("dashboard_path","");if(!path.isEmpty())merged.put("dashboard_path",path);
            if(event.has("diagnostics_url")){String d=event.isNull("diagnostics_url")?"":event.optString("diagnostics_url","");if(d.isEmpty())merged.remove("diagnostics_url");else merged.put("diagnostics_url",d);}
            if(event.has("music_assistant")){
                if(event.isNull("music_assistant"))merged.remove("music_assistant");
                else{JSONObject music=validMusic(event.optJSONObject("music_assistant"));if(music!=null)merged.put("music_assistant",music);}
            }
            return merged.toString().equals(current.toString())?null:merged;
        }catch(Exception e){return null;}
    }
    static JSONObject validMusic(JSONObject music){
        if(music==null)return null;
        String url=music.optString("url",""),token=music.optString("token",""),sendspin=music.optString("sendspin_url","");
        if(!(url.startsWith("http://")||url.startsWith("https://"))||token.isEmpty()||!(sendspin.startsWith("ws://")||sendspin.startsWith("wss://")))return null;
        try{return new JSONObject().put("url",url).put("token",token).put("sendspin_url",sendspin);}catch(Exception e){return null;}
    }
    static boolean haRestart(JSONObject a,JSONObject b){return !a.optString("pipeline").equals(b.optString("pipeline"))||!a.optString("dashboard_path").equals(b.optString("dashboard_path"));}
    static boolean maRestart(JSONObject a,JSONObject b){return !String.valueOf(a.opt("music_assistant")).equals(String.valueOf(b.opt("music_assistant")));}
}
