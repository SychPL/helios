package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionMergeTest {
    private static JSONObject base() throws Exception {return new JSONObject().put("url","http://ha:8123").put("token","t").put("pipeline","p1").put("dashboard_path","helios-clock").put("protocol",2);}
    private static JSONObject ma(String url) throws Exception {return new JSONObject().put("url",url).put("token","m").put("sendspin_url","ws://ma:8927/sendspin");}
    @Test public void musicSectionHasThreeStates() throws Exception {
        JSONObject with=ConnectionMerge.apply(base(),new JSONObject().put("pipeline","p1").put("music_assistant",ma("http://ma:8095")));
        assertEquals("http://ma:8095",with.getJSONObject("music_assistant").getString("url"));
        assertNull("absent key = unchanged",ConnectionMerge.apply(with,new JSONObject().put("pipeline","p1")));
        JSONObject without=ConnectionMerge.apply(with,new JSONObject().put("pipeline","p1").put("music_assistant",JSONObject.NULL));
        assertFalse(without.has("music_assistant"));
        assertTrue(ConnectionMerge.maRestart(with,without));assertFalse(ConnectionMerge.haRestart(with,without));
    }
    @Test public void invalidSectionsAndEmptyPipelineAreIgnored() throws Exception {
        assertNull(ConnectionMerge.apply(base(),new JSONObject().put("pipeline","").put("music_assistant",ma("ftp://x"))));
        assertNull(ConnectionMerge.apply(base(),new JSONObject().put("music_assistant",new JSONObject().put("url","http://ma:8095").put("token","m").put("sendspin_url","http://ma:8927"))));
    }
    @Test public void pipelineAndDiagnosticsMerge() throws Exception {
        JSONObject m=ConnectionMerge.apply(base(),new JSONObject().put("pipeline","p2").put("diagnostics_url","http://pc:8757/x/events"));
        assertEquals("p2",m.getString("pipeline"));assertEquals("http://pc:8757/x/events",m.getString("diagnostics_url"));assertTrue(ConnectionMerge.haRestart(base(),m));
        JSONObject cleared=ConnectionMerge.apply(m,new JSONObject().put("pipeline","p2").put("diagnostics_url",JSONObject.NULL));
        assertFalse(cleared.has("diagnostics_url"));assertFalse(ConnectionMerge.haRestart(m,cleared));
        assertEquals("t",m.getString("token"));assertEquals(2,m.getInt("protocol"));
    }
}
