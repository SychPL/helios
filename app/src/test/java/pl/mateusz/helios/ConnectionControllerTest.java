package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConnectionControllerTest {
    private final List<String> log=new ArrayList<>();
    private JSONObject stored;private boolean saveOk=true,clearOk=true;private String probeError;
    private ConnectionController controller(JSONObject current){
        stored=current;
        return new ConnectionController(new ConnectionController.Store(){public JSONObject current(){return stored;}public boolean save(JSONObject c){log.add("save");if(saveOk)stored=c;return saveOk;}public boolean clear(){log.add("clear");if(clearOk)stored=null;return clearOk;}},
            (url,token)->{log.add("probe:"+url);return probeError;},
            new ConnectionController.Transports(){public void restartAll(){log.add("all");}public void restartHa(){log.add("ha");}public void restartMusic(){log.add("music");}public void stopAll(){log.add("stop");}public void issue(String t){log.add("issue:"+t);}});
    }
    private static JSONObject response() throws Exception {return new JSONObject().put("protocol",2).put("token","new").put("pipeline","p1").put("dashboard_path","helios-clock");}
    @Test public void pairingPersistsBeforeRestartAndFailsTerminally() throws Exception {
        ConnectionController c=controller(null);
        assertNull(c.pairedWith(response(),"http://ha:8123"));
        assertEquals(Arrays.asList("save","all"),log);assertEquals(2,stored.getInt("protocol"));assertEquals("new",stored.getString("token"));
        log.clear();saveOk=false;
        assertEquals("Nie udało się zapisać parowania - sparuj ponownie",c.pairedWith(response(),"http://ha:8123"));
        assertEquals("the old stored connection is cleared: a restart must not revive a retired token",Arrays.asList("save","clear","stop"),log);assertNull(stored);
        log.clear();clearOk=false;
        assertEquals("Nie udało się zapisać parowania - sparuj ponownie",controller(new JSONObject().put("url","http://old").put("token","old")).pairedWith(response(),"http://ha:8123"));
        assertEquals(Arrays.asList("save","clear","issue:Nie udało się wyczyścić starego parowania","stop"),log);
        assertEquals("Niepełna odpowiedź HA",c.pairedWith(new JSONObject().put("token","x"),"http://ha:8123"));
    }
    @Test public void connectionEventDrivesThreeMusicStatesAndMinimalRestarts() throws Exception {
        JSONObject base=new JSONObject().put("url","http://ha:8123").put("token","t").put("pipeline","p1").put("dashboard_path","helios-clock").put("protocol",2);
        ConnectionController c=controller(base);
        JSONObject ma=new JSONObject().put("url","http://ma:8095").put("token","m").put("sendspin_url","ws://ma:8927/sendspin");
        final int g=c.generation(); // one session, one number: every event and the auth_invalid below carry the same generation
        c.applyConnection(new JSONObject().put("pipeline","p1").put("music_assistant",ma),g);
        assertEquals(Arrays.asList("probe:http://ma:8095","save","music"),log);log.clear();
        c.applyConnection(new JSONObject().put("pipeline","p1"),g);assertTrue("absent key = nothing",log.isEmpty());
        c.applyConnection(new JSONObject().put("pipeline","p1").put("diagnostics_url","http://pc:8757/x/events"),g);
        assertEquals("diagnostics change saves without restarts",Arrays.asList("save"),log);log.clear();
        c.applyConnection(new JSONObject().put("pipeline","p2"),g);assertEquals(Arrays.asList("save","ha"),log);log.clear();
        assertEquals("connection events never change the generation",g,c.generation());
        probeError="MA odrzucił token";
        c.applyConnection(new JSONObject().put("pipeline","p2").put("music_assistant",new JSONObject(ma.toString()).put("token","bad")),g);
        assertEquals(Arrays.asList("probe:http://ma:8095","issue:Muzyka: MA odrzucił token"),log);assertEquals("m",stored.getJSONObject("music_assistant").getString("token"));log.clear();
        c.applyConnection(new JSONObject().put("pipeline","p2").put("music_assistant",JSONObject.NULL),g);
        assertEquals(Arrays.asList("save","music"),log);assertFalse(stored.has("music_assistant"));log.clear();
        int old=c.generation();
        assertNull(c.pairedWith(response(),"http://ha:8123"));log.clear();
        assertEquals(old+1,c.generation());
        c.applyConnection(new JSONObject().put("pipeline","p9"),old);
        assertTrue("an event from the previous connection's session is ignored",log.isEmpty());assertEquals("p1",stored.getString("pipeline"));
        assertEquals("stale auth_invalid is ignored",ConnectionController.IGNORED,c.markAuthInvalid(old));assertTrue(log.isEmpty());assertFalse(stored.has("auth_invalid"));
        int live=c.generation();
        c.applyConnection(new JSONObject().put("pipeline","p3"),live);log.clear();
        assertEquals("after a connection event the same session may still report auth_invalid",ConnectionController.SAVED,c.markAuthInvalid(live));
        assertEquals(Arrays.asList("save","stop"),log);assertTrue(stored.getBoolean("auth_invalid"));assertEquals(live+1,c.generation());
    }
    @Test public void authInvalidIsPersistedOrReportedAsUnsaved() throws Exception {
        JSONObject base=new JSONObject().put("url","http://ha:8123").put("token","t").put("pipeline","p1").put("protocol",2);
        ConnectionController c=controller(base);
        assertEquals(ConnectionController.SAVED,c.markAuthInvalid(c.generation()));
        assertEquals(Arrays.asList("save","stop"),log);assertTrue(stored.getBoolean("auth_invalid"));log.clear();
        ConnectionController d=controller(new JSONObject(base.toString()));saveOk=false;
        assertEquals(ConnectionController.SAVE_FAILED,d.markAuthInvalid(d.generation()));
        assertEquals(Arrays.asList("save","issue:Nie udało się zapisać stanu połączenia - po restarcie zegar spróbuje raz jeszcze","stop"),log);
        assertFalse("SAVE_FAILED is remembered in this process",d.startAllowed());
        log.clear();saveOk=true;
        assertNull("a later successful pairing is a clean start",d.pairedWith(response(),"http://ha:8123"));assertEquals(Arrays.asList("save","all"),log);assertFalse(stored.has("auth_invalid"));
        assertTrue("the service's startHa() gate opens again",d.startAllowed());
        assertEquals(ConnectionController.SAVED,c.markAuthInvalid(c.generation()));assertFalse("a persisted flag closes the gate too",c.startAllowed());
    }
}
