package pl.mateusz.helios;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.*;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HaDashboardClientTest {
    static final class Dashboard {
        final JSONObject raw;final DashboardSpec spec;final Map<String,EntityStates.Entity> states;final String issue;
        Dashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){this.raw=raw;this.spec=spec;this.states=states;this.issue=issue;}
    }
    static class Server extends WebSocketServer {
        final CountDownLatch ready=new CountDownLatch(1);
        volatile WebSocket client;
        volatile String entity="light.first";
        volatile boolean broken,ignoreCalls,rejectCalls;
        final BlockingQueue<JSONObject> calls=new LinkedBlockingQueue<>();
        Server(){super(new InetSocketAddress("127.0.0.1",0));}
        @Override public void onStart(){ready.countDown();}
        @Override public void onOpen(WebSocket ws,ClientHandshake handshake){client=ws;ws.send("{\"type\":\"auth_required\"}");}
        @Override public void onClose(WebSocket ws,int code,String reason,boolean remote){}
        @Override public void onError(WebSocket ws,Exception ex){}
        JSONObject document() throws Exception {
            JSONObject light=DashboardSpecTest.item("light","light",1,1,1,1).put("entity",entity);
            JSONObject weather=DashboardSpecTest.item("weather","weather",2,1,2,1).put("entity","weather.dom");
            JSONObject helios=new JSONObject().put("version",broken?1:2).put("grid",new JSONObject().put("columns",4).put("rows",3)).put("items",new JSONArray().put(light).put(weather));
            return new JSONObject().put("helios",helios);
        }
        @Override public void onMessage(WebSocket ws,String message){
            try{
                JSONObject request=new JSONObject(message);String type=request.getString("type");int id=request.optInt("id");
                if(type.equals("auth")){ws.send("{\"type\":\"auth_ok\"}");return;}
                if(type.equals("call_service")){
                    calls.add(request);
                    if(ignoreCalls)return;
                    JSONObject response=new JSONObject().put("id",id).put("type","result").put("success",!rejectCalls);
                    if(rejectCalls)response.put("error",new JSONObject().put("code","not_found").put("message","Encja nie istnieje"));
                    ws.send(response.toString());return;
                }
                JSONObject response=new JSONObject().put("id",id).put("type","result").put("success",true);
                if(type.equals("lovelace/config"))response.put("result",document());
                ws.send(response.toString());
                if(type.equals("subscribe_entities")){
                    JSONObject snapshot=new JSONObject();JSONArray requested=request.getJSONArray("entity_ids");
                    for(int i=0;i<requested.length();i++)snapshot.put(requested.getString(i),new JSONObject().put("s","off"));
                    snapshot.put("weather.dom",new JSONObject().put("s","rainy").put("a",new JSONObject().put("temperature",12).put("humidity",50)));
                    sendEvent(id,new JSONObject().put("a",snapshot));
                }
            }catch(Exception e){ws.close(1011,"Test protocol failure: "+e);}
        }
        void sendEvent(int id,JSONObject event)throws Exception{client.send(new JSONObject().put("id",id).put("type","event").put("event",event).toString());}
    }
    private Server server;
    private final BlockingQueue<Dashboard> dashboards=new LinkedBlockingQueue<>();
    private final BlockingQueue<Map<String,EntityStates.Entity>> states=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> errors=new LinkedBlockingQueue<>();
    private HaDashboardClient client(JSONObject cached) throws Exception {
        server=new Server();server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        return new HaDashboardClient(new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","test-token"),cached,new HaDashboardClient.Listener(){
            public void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> values,String issue){dashboards.add(new Dashboard(raw,spec,values,issue));}
            public void onStates(Map<String,EntityStates.Entity> values){states.add(values);}
            public void onUnavailable(String reason){errors.add(reason);}
        });
    }
    private Dashboard dashboard() throws Exception {Dashboard d=dashboards.poll(7,TimeUnit.SECONDS);assertNotNull(d);return d;}
    private String call(HaDashboardClient client,String domain,String service,String entity) throws Exception {
        BlockingQueue<String> result=new LinkedBlockingQueue<>();
        client.callService(domain,service,entity,error->result.add(error==null?"ok":error));
        String value=result.poll(15,TimeUnit.SECONDS);assertNotNull(value);return value;
    }

    @Test public void layoutArrivesWithItsSnapshotAndReloadKeepsOldLayoutUntilTheNewOne() throws Exception {
        HaDashboardClient client=client(null);
        try{
            client.start();
            Dashboard first=dashboard();
            assertNull(first.issue);assertEquals("light.first",first.spec.item("light").entity);
            assertEquals("off",first.states.get("light.first").state);assertEquals("12",first.states.get("weather.dom").attribute("temperature"));assertNull(first.states.get("weather.dom").attribute("humidity"));
            server.sendEvent(3,new JSONObject("{\"c\":{\"light.first\":{\"+\":{\"s\":\"on\"}}}}"));
            assertEquals("on",states.poll(5,TimeUnit.SECONDS).get("light.first").state);
            server.entity="light.second";
            server.sendEvent(1,new JSONObject("{\"data\":{\"url_path\":\"helios-clock\"}}"));
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            Dashboard second=dashboard();assertEquals("light.second",second.spec.item("light").entity);assertEquals("off",second.states.get("light.second").state);
            server.sendEvent(1,new JSONObject("{\"data\":{\"url_path\":\"other-panel\"}}"));
            assertNull(dashboards.poll(1,TimeUnit.SECONDS));
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void brokenDocumentKeepsLastGoodLayoutLiveAndReportsTheIssue() throws Exception {
        HaDashboardClient client=client(null);
        try{
            client.start();assertNull(dashboard().issue);
            server.broken=true;server.sendEvent(1,new JSONObject("{\"data\":{\"url_path\":\"helios-clock\"}}"));
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            Dashboard kept=dashboard();
            assertTrue(kept.issue,kept.issue.contains(DashboardSpec.VERSION_ERROR));assertEquals("light.first",kept.spec.item("light").entity);assertEquals("off",kept.states.get("light.first").state);
            server.sendEvent(3,new JSONObject("{\"c\":{\"light.first\":{\"+\":{\"s\":\"on\"}}}}"));
            assertEquals("on",states.poll(5,TimeUnit.SECONDS).get("light.first").state);
            server.broken=false;server.sendEvent(1,new JSONObject("{\"data\":{\"url_path\":\"helios-clock\"}}"));
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));assertNull(dashboard().issue);
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void withoutAnyGoodDocumentTheListenerGetsNullSpecAndCachedDocumentSeedsLastGood() throws Exception {
        HaDashboardClient client=client(null);
        try{
            server.broken=true;client.start();
            Dashboard none=dashboard();assertNull(none.spec);assertNotNull(none.issue);
        }finally{client.stop();server.stop(2000);}
        dashboards.clear();
        JSONObject cached=DashboardSpecTest.example();
        client=client(cached);
        try{
            server.broken=true;server.entity="light.salon";client.start();
            Dashboard fromCache=dashboard();assertNotNull(fromCache.issue);assertEquals(6,fromCache.spec.items.size());assertSame(cached,fromCache.raw);
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void serviceCallsReportAcceptanceRejectionTimeoutAndDisconnect() throws Exception {
        HaDashboardClient client=client(null);
        long previous=HaDashboardClient.CALL_TIMEOUT_MS;HaDashboardClient.CALL_TIMEOUT_MS=1500;
        try{
            assertNotNull(call(client,"light","toggle","light.first"));assertNotEquals("ok",call(client,"light","toggle","light.first"));
            client.start();assertNull(dashboard().issue);
            assertEquals("ok",call(client,"light","toggle","light.first"));
            JSONObject sent=server.calls.poll(5,TimeUnit.SECONDS);
            assertEquals("call_service",sent.getString("type"));assertEquals("light",sent.getString("domain"));assertEquals("toggle",sent.getString("service"));assertEquals("light.first",sent.getJSONObject("target").getString("entity_id"));
            server.rejectCalls=true;assertEquals("Encja nie istnieje",call(client,"cover","close_cover","cover.x"));
            server.rejectCalls=false;server.ignoreCalls=true;
            assertTrue(call(client,"light","toggle","light.first").contains("nie potwierdził"));
            server.ignoreCalls=false;
            BlockingQueue<String> result=new LinkedBlockingQueue<>();
            server.ignoreCalls=true;client.callService("light","toggle","light.first",error->result.add(error==null?"ok":error));
            assertNotNull(server.calls.poll(5,TimeUnit.SECONDS));
            server.client.close(1001,"Test disconnect");
            assertEquals("Połączenie z HA przerwane",result.poll(5,TimeUnit.SECONDS));
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            server.ignoreCalls=false;
            assertNull(dashboard().issue);assertEquals("ok",call(client,"light","toggle","light.first"));
        }finally{HaDashboardClient.CALL_TIMEOUT_MS=previous;client.stop();server.stop(2000);}
    }
}
