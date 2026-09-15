package pl.mateusz.helios;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.*;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class HaDashboardClientTest {
    static final class Dashboard {
        final JSONObject raw;final DashboardSpec spec;final Map<String,EntityStates.Entity> states;final String issue;
        Dashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){this.raw=raw;this.spec=spec;this.states=states;this.issue=issue;}
    }
    /** Fake HA: enforces strictly increasing ids per connection like websocket_api does. */
    static class Server extends WebSocketServer {
        final CountDownLatch ready=new CountDownLatch(1);
        volatile WebSocket client;
        volatile String entity="light.first";
        volatile boolean broken,ignoreCalls,rejectCalls,rejectToken;
        final BlockingQueue<JSONObject> calls=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> custom=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> connects=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> device=new LinkedBlockingQueue<>();
        volatile boolean rejectConnect;
        volatile int connectId=-1;
        final Map<WebSocket,Integer> lastId=new ConcurrentHashMap<>();
        volatile int entitiesId=-1,customSubscriptionId=-1,updatesId=-1;
        Server(){super(new InetSocketAddress("127.0.0.1",0));}
        @Override public void onStart(){ready.countDown();}
        @Override public void onOpen(WebSocket ws,ClientHandshake handshake){client=ws;lastId.put(ws,0);ws.send("{\"type\":\"auth_required\"}");}
        @Override public void onClose(WebSocket ws,int code,String reason,boolean remote){lastId.remove(ws);}
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
                if(type.equals("auth")){ws.send(rejectToken?"{\"type\":\"auth_invalid\",\"message\":\"bad\"}":"{\"type\":\"auth_ok\"}");return;}
                Integer previous=lastId.get(ws);
                if(previous!=null&&id<=previous){ws.send(new JSONObject().put("id",id).put("type","result").put("success",false).put("error",new JSONObject().put("code","id_reuse").put("message","Identifier values have to increase")).toString());return;}
                lastId.put(ws,id);
                if(type.equals("call_service")){
                    calls.add(request);
                    if(ignoreCalls)return;
                    JSONObject response=new JSONObject().put("id",id).put("type","result").put("success",!rejectCalls);
                    if(rejectCalls)response.put("error",new JSONObject().put("code","not_found").put("message","Encja nie istnieje"));
                    ws.send(response.toString());return;
                }
                if(type.equals("helios/connect")){
                    connects.add(request);connectId=id;
                    if(rejectConnect){ws.send(new JSONObject().put("id",id).put("type","result").put("success",false).put("error",new JSONObject().put("code","unauthorized").put("message","Nieznane urządzenie")).toString());return;}
                    ws.send(new JSONObject().put("id",id).put("type","result").put("success",true).toString());
                    sendEvent(id,new JSONObject().put("type","connected").put("device_id","dev1").put("area_id","bedroom"));return;
                }
                if(type.equals("helios/state")||type.equals("helios/result")){device.add(request);ws.send(new JSONObject().put("id",id).put("type","result").put("success",true).toString());return;}
                if(type.equals("helios/echo")){custom.add(request);ws.send(new JSONObject().put("id",id).put("type","result").put("success",true).put("result",request.opt("value")).toString());return;}
                if(type.equals("helios/subscribe")){customSubscriptionId=id;ws.send(new JSONObject().put("id",id).put("type","result").put("success",true).toString());sendEvent(id,new JSONObject().put("type","hello"));return;}
                if(type.equals("helios/rejected")){ws.send(new JSONObject().put("id",id).put("type","result").put("success",false).put("error",new JSONObject().put("code","unauthorized").put("message","Odmowa")).toString());return;}
                if(type.equals("subscribe_events"))updatesId=id;
                JSONObject response=new JSONObject().put("id",id).put("type","result").put("success",true);
                if(type.equals("lovelace/config"))response.put("result",document());
                ws.send(response.toString());
                if(type.equals("subscribe_entities")){
                    entitiesId=id;
                    JSONObject snapshot=new JSONObject();JSONArray requested=request.getJSONArray("entity_ids");
                    for(int i=0;i<requested.length();i++)snapshot.put(requested.getString(i),new JSONObject().put("s","off"));
                    snapshot.put("weather.dom",new JSONObject().put("s","rainy").put("a",new JSONObject().put("temperature",12).put("humidity",50)));
                    sendEvent(id,new JSONObject().put("a",snapshot));
                }
            }catch(Exception e){ws.close(1011,"Test protocol failure: "+e);}
        }
        void sendEvent(int id,JSONObject event)throws Exception{client.send(new JSONObject().put("id",id).put("type","event").put("event",event).toString());}
        void sendEntityChange(String json)throws Exception{sendEvent(entitiesId,new JSONObject(json));}
        void sendCommand(String requestId,String command,JSONObject args)throws Exception{sendEvent(connectId,new JSONObject().put("type","command").put("request_id",requestId).put("command",command).put("args",args));}
        void reload()throws Exception{sendEvent(updatesId,new JSONObject("{\"data\":{\"url_path\":\"helios-clock\"}}"));}
    }
    private Server server;
    private final BlockingQueue<Dashboard> dashboards=new LinkedBlockingQueue<>();
    private final BlockingQueue<Map<String,EntityStates.Entity>> states=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> errors=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> sessions=new LinkedBlockingQueue<>();
    private final HaDashboardClient.Listener listener=new HaDashboardClient.Listener(){
        public void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> values,String issue){dashboards.add(new Dashboard(raw,spec,values,issue));}
        public void onStates(Map<String,EntityStates.Entity> values){states.add(values);}
        public void onUnavailable(String reason){errors.add(reason);}
        public void onSessionStarted(){sessions.add("started");}
    };
    private JSONObject connection() throws Exception {return new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","test-token");}
    private HaDashboardClient client(JSONObject cached) throws Exception {
        server=new Server();server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        HaDashboardClient client=new HaDashboardClient(connection(),cached);client.attach(listener);
        errors.clear();
        return client;
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
            assertNotNull(sessions.poll(5,TimeUnit.SECONDS));
            Dashboard first=dashboard();
            assertNull(first.issue);assertEquals("light.first",first.spec.item("light").entity);
            assertEquals("off",first.states.get("light.first").state);assertEquals("12",first.states.get("weather.dom").attribute("temperature"));assertNull(first.states.get("weather.dom").attribute("humidity"));
            server.sendEntityChange("{\"c\":{\"light.first\":{\"+\":{\"s\":\"on\"}}}}");
            assertEquals("on",states.poll(5,TimeUnit.SECONDS).get("light.first").state);
            server.entity="light.second";
            server.reload();
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            Dashboard second=dashboard();assertEquals("light.second",second.spec.item("light").entity);assertEquals("off",second.states.get("light.second").state);
            server.sendEvent(server.updatesId,new JSONObject("{\"data\":{\"url_path\":\"other-panel\"}}"));
            assertNull(dashboards.poll(1,TimeUnit.SECONDS));
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void brokenDocumentKeepsLastGoodLayoutLiveAndReportsTheIssue() throws Exception {
        HaDashboardClient client=client(null);
        try{
            client.start();assertNull(dashboard().issue);
            server.broken=true;server.reload();
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            Dashboard kept=dashboard();
            assertTrue(kept.issue,kept.issue.contains(DashboardSpec.VERSION_ERROR));assertEquals("light.first",kept.spec.item("light").entity);assertEquals("off",kept.states.get("light.first").state);
            server.sendEntityChange("{\"c\":{\"light.first\":{\"+\":{\"s\":\"on\"}}}}");
            assertEquals("on",states.poll(5,TimeUnit.SECONDS).get("light.first").state);
            server.broken=false;server.reload();
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
            assertNotEquals("ok",call(client,"light","toggle","light.first"));
            client.start();assertNull(dashboard().issue);
            assertEquals("ok",call(client,"light","toggle","light.first"));
            JSONObject sent=server.calls.poll(5,TimeUnit.SECONDS);
            assertEquals("call_service",sent.getString("type"));assertEquals("light",sent.getString("domain"));assertEquals("toggle",sent.getString("service"));assertEquals("light.first",sent.getJSONObject("target").getString("entity_id"));
            server.rejectCalls=true;assertEquals("Encja nie istnieje",call(client,"cover","close_cover","cover.x"));
            server.rejectCalls=false;server.ignoreCalls=true;
            assertTrue(call(client,"light","toggle","light.first").contains("nie potwierdził"));
            BlockingQueue<String> result=new LinkedBlockingQueue<>();
            client.callService("light","toggle","light.first",error->result.add(error==null?"ok":error));
            assertNotNull(server.calls.poll(5,TimeUnit.SECONDS));
            server.client.close(1001,"Test disconnect");
            assertEquals("Połączenie z HA przerwane",result.poll(5,TimeUnit.SECONDS));
            assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            server.ignoreCalls=false;
            assertNull(dashboard().issue);assertEquals("ok",call(client,"light","toggle","light.first"));
        }finally{HaDashboardClient.CALL_TIMEOUT_MS=previous;client.stop();server.stop(2000);}
    }
    @Test public void attachReplaysNewestSnapshotThenUnavailability() throws Exception {
        HaDashboardClient client=client(null);
        try{
            client.start();assertNull(dashboard().issue);
            client.detach(listener);
            server.sendEntityChange("{\"c\":{\"light.first\":{\"+\":{\"s\":\"on\"}}}}");
            Thread.sleep(300);
            server.client.close(1001,"Test disconnect");
            Thread.sleep(500);
            assertTrue(states.isEmpty());assertTrue(errors.isEmpty());
            client.attach(listener);
            Dashboard replayed=dashboards.poll(1,TimeUnit.SECONDS);assertNotNull(replayed);
            assertEquals("on",replayed.states.get("light.first").state);
            assertNotNull(errors.poll(1,TimeUnit.SECONDS));
            assertFalse(client.live());
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void requestsAndSubscriptionsShareOneIncreasingIdSequence() throws Exception {
        HaDashboardClient client=client(null);
        try{
            BlockingQueue<JSONObject> early=new LinkedBlockingQueue<>();
            client.request(new JSONObject().put("type","helios/echo").put("value",1),early::add);
            assertFalse(early.poll(1,TimeUnit.SECONDS).getBoolean("success"));
            client.start();assertNotNull(sessions.poll(5,TimeUnit.SECONDS));assertNull(dashboard().issue);
            BlockingQueue<JSONObject> results=new LinkedBlockingQueue<>();
            ExecutorService pool=Executors.newFixedThreadPool(4);AtomicInteger sent=new AtomicInteger();
            for(int i=0;i<50;i++)pool.execute(()->{try{client.request(new JSONObject().put("type","helios/echo").put("value",sent.incrementAndGet()),results::add);}catch(Exception e){throw new RuntimeException(e);}});
            pool.shutdown();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));
            for(int i=0;i<50;i++){JSONObject r=results.poll(5,TimeUnit.SECONDS);assertNotNull(r);assertTrue(r.toString(),r.getBoolean("success"));}
            BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();BlockingQueue<String> ended=new LinkedBlockingQueue<>();
            int subscription=client.subscribe(new JSONObject().put("type","helios/subscribe"),events::add,ended::add);
            assertTrue(subscription>0);
            assertEquals("hello",events.poll(5,TimeUnit.SECONDS).getString("type"));
            server.sendEvent(server.customSubscriptionId,new JSONObject().put("type","again"));
            assertEquals("again",events.poll(5,TimeUnit.SECONDS).getString("type"));
            BlockingQueue<String> rejectedEnd=new LinkedBlockingQueue<>();
            client.subscribe(new JSONObject().put("type","helios/rejected"),e->fail("no events"),rejectedEnd::add);
            assertEquals("Odmowa",rejectedEnd.poll(5,TimeUnit.SECONDS));
            assertEquals("ok",call(client,"light","toggle","light.first"));
            server.client.close(1001,"Test disconnect");
            assertEquals("Połączenie z HA przerwane",ended.poll(5,TimeUnit.SECONDS));
            assertNotNull(sessions.poll(7,TimeUnit.SECONDS));
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void probeAcceptsGoodTokenAndRejectsBadOne() throws Exception {
        server=new Server();server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        try{
            assertNull(HaDashboardClient.probe(connection()));
            server.rejectToken=true;
            assertEquals("HA odrzucił token",HaDashboardClient.probe(connection()));
            assertNotNull(HaDashboardClient.probe(new JSONObject().put("url","ftp://127.0.0.1:1").put("token","x")));
        }finally{server.stop(2000);}
    }
}
