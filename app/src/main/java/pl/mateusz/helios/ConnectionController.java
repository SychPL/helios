package pl.mateusz.helios;

import org.json.JSONException;
import org.json.JSONObject;

/** Connection changes without Android: pairing response and `connection` events (SPEC 0.10 pkt 4.2, 6.3, 8.2). Called on the network thread. */
final class ConnectionController {
    interface Store {JSONObject current();boolean save(JSONObject connection);boolean clear();}
    interface Probe {String music(String url,String token);}
    interface Transports {void restartAll();void restartHa();void restartMusic();void stopAll();void issue(String text);}
    static final String IGNORED="ignored",SAVED="saved",SAVE_FAILED="save_failed";
    private final Store store;private final Probe probe;private final Transports transports;
    private int generation; // bumps only when the identity changes (pairing, auth_invalid): the HA session of the current identity keeps its number across connection events
    private volatile boolean authInvalidUnsaved; // SAVE_FAILED happened: this process must not reuse the token even though the flag is not on disk
    ConnectionController(Store store,Probe probe,Transports transports){this.store=store;this.probe=probe;this.transports=transports;}
    synchronized int generation(){return generation;}
    /** false while the stored connection is flagged auth_invalid or an unsaved auth_invalid is remembered; the service's startHa() asks this. */
    boolean startAllowed(){JSONObject c=store.current();return !authInvalidUnsaved&&(c==null||!c.optBoolean("auth_invalid",false));}
    private synchronized boolean save(JSONObject value,int expected,boolean newIdentity){
        if(expected>=0&&expected!=generation)return false; // a newer pairing won meanwhile
        if(!store.save(value))return false;
        if(newIdentity)generation++;
        return true;
    }
    /** Persist first, then restart everything; HA has already retired the old identity, so a failed save is terminal: nothing old is started again. */
    String pairedWith(JSONObject response,String url){
        String token=response.optString("token",""),pipeline=response.optString("pipeline","");
        if(token.isEmpty()||pipeline.isEmpty())return "Niepełna odpowiedź HA";
        JSONObject fresh;
        try{fresh=new JSONObject().put("url",url).put("token",token).put("pipeline",pipeline).put("dashboard_path",response.optString("dashboard_path","helios-clock")).put("protocol",2);}
        catch(JSONException e){return "Niepełna odpowiedź HA";} // checked on Android's org.json
        authInvalidUnsaved=false; // a fresh identity supersedes any remembered refusal
        if(!save(fresh,-1,true)){
            // HA has retired the old identity: the stored old connection must not come back after a restart either
            if(!store.clear())transports.issue("Nie udało się wyczyścić starego parowania");
            transports.stopAll();
            return "Nie udało się zapisać parowania - sparuj ponownie";
        }
        transports.restartAll();
        return null;
    }
    /** HA refused the token of the connection with this generation: persist the flag (nothing must reuse the token after a restart). IGNORED for a stale session: no UI, no stop. */
    String markAuthInvalid(int gen){
        JSONObject current=store.current();if(current==null||gen!=generation())return IGNORED;
        JSONObject flagged;
        try{flagged=new JSONObject(current.toString()).put("auth_invalid",true);}catch(JSONException e){flagged=null;}
        boolean saved=flagged!=null&&save(flagged,gen,true); // the identity is dead: events of this session are stale from now on
        if(!saved){synchronized(this){generation++;}authInvalidUnsaved=true;transports.issue("Nie udało się zapisać stanu połączenia - po restarcie zegar spróbuje raz jeszcze");} // SPEC 8.2 cannot be honoured without a durable flag; say so instead of pretending
        transports.stopAll();
        return saved?SAVED:SAVE_FAILED;
    }
    /** gen = generation() taken when the event arrived; an event from a client of an older connection is ignored. */
    void applyConnection(JSONObject event,int gen){
        JSONObject current=store.current();if(current==null||gen!=generation())return;
        JSONObject merged=ConnectionMerge.apply(current,event);if(merged==null)return;
        JSONObject music=merged.optJSONObject("music_assistant"),before=current.optJSONObject("music_assistant");
        if(music!=null&&(before==null||!before.toString().equals(music.toString()))){
            String error=probe.music(music.optString("url"),music.optString("token"));
            if(error!=null){transports.issue("Muzyka: "+error);try{if(before==null)merged.remove("music_assistant");else merged.put("music_assistant",before);}catch(Exception ignored){}}
        }
        if(merged.toString().equals(current.toString()))return;
        boolean restartHa=ConnectionMerge.haRestart(current,merged),restartMa=ConnectionMerge.maRestart(current,merged);
        if(!save(merged,gen,false))return; // the probe took time: a pairing in between wins; the session keeps its generation
        if(restartHa)transports.restartHa();
        if(restartMa)transports.restartMusic();
    }
}
