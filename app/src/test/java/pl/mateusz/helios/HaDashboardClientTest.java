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
    static class Server extends WebSocketServer {
        final CountDownLatch ready=new CountDownLatch(1);
        volatile WebSocket client;
        volatile String entity="sensor.first";
        volatile boolean badMenu;
        Server(){super(new InetSocketAddress("127.0.0.1",0));}
        @Override public void onStart(){ready.countDown();}
        @Override public void onOpen(WebSocket ws,ClientHandshake handshake){client=ws;ws.send("{\"type\":\"auth_required\"}");}
        @Override public void onClose(WebSocket ws,int code,String reason,boolean remote){}
        @Override public void onError(WebSocket ws,Exception ex){}
        @Override public void onMessage(WebSocket ws,String message){
            try{
                JSONObject request=new JSONObject(message);String type=request.getString("type");int id=request.optInt("id");
                if(type.equals("auth")){ws.send("{\"type\":\"auth_ok\"}");return;}
                JSONObject response=new JSONObject().put("id",id).put("type","result").put("success",true);
                if(type.equals("lovelace/config")){
                    JSONObject rule=new JSONObject().put("entity",entity).put("name","Test").put("label","Open").put("when",new JSONArray().put("open")).put("clear_when",new JSONArray().put("close"));
                    response.put("result",new JSONObject().put("helios",new JSONObject().put("version",1).put("weather",JSONObject.NULL).put("indicators",new JSONArray().put(rule))));
                    JSONObject card=new JSONObject().put("type",badMenu?"unsupported":"button").put("name",entity).put("tap_action",new JSONObject().put("action","url").put("url_path","helios://settings"));
                    response.getJSONObject("result").put("views",new JSONArray().put(new JSONObject().put("path","menu-zegara").put("cards",new JSONArray().put(card))));
                }
                ws.send(response.toString());
                if(type.equals("subscribe_entities")){
                    if(!request.getJSONArray("entity_ids").toString().equals(new JSONArray().put(entity).toString()))throw new IllegalStateException("Unexpected entity subscription");
                    sendEvent(id,new JSONObject().put("a",new JSONObject().put(entity,new JSONObject().put("s","close"))));
                }
            }catch(Exception e){ws.close(1011,"Test protocol failure");}
        }
        void sendEvent(int id,JSONObject event)throws Exception{client.send(new JSONObject().put("id",id).put("type","event").put("event",event).toString());}
    }
    @Test public void receivesChangesReloadsRulesAndRecoversFromDisconnect() throws Exception {
        Server server=new Server();server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        BlockingQueue<Map<String,String>> states=new LinkedBlockingQueue<>();BlockingQueue<DashboardSpec> configs=new LinkedBlockingQueue<>();BlockingQueue<String> errors=new LinkedBlockingQueue<>();
        BlockingQueue<MenuSpec> menus=new LinkedBlockingQueue<>();BlockingQueue<String> menuErrors=new LinkedBlockingQueue<>();
        HaDashboardClient client=new HaDashboardClient(new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","test-token"),new HaDashboardClient.Listener(){
            public void onConfig(JSONObject raw,DashboardSpec spec){configs.add(spec);}
            public void onStates(Map<String,String> values){states.add(values);}
            public void onUnavailable(String reason){errors.add(reason);}
            public void onMenu(JSONObject raw,MenuSpec menu){menus.add(menu);}
            public void onMenuError(String reason){menuErrors.add(reason);}
        });
        try{
            client.start();assertNotNull(configs.poll(5,TimeUnit.SECONDS));
            assertEquals("sensor.first",menus.poll(5,TimeUnit.SECONDS).items.get(0).name);
            assertEquals("close",take(states).get("sensor.first"));
            server.sendEvent(3,new JSONObject("{\"c\":{\"sensor.first\":{\"+\":{\"s\":\"open\"}}}}"));
            assertEquals("open",take(states).get("sensor.first"));
            server.sendEvent(3,new JSONObject("{\"r\":[\"sensor.first\"]}"));assertTrue(take(states).isEmpty());
            server.entity="sensor.second";
            server.sendEvent(1,new JSONObject("{\"data\":{\"url_path\":\"helios-clock\"}}"));
            DashboardSpec changed=configs.poll(5,TimeUnit.SECONDS);assertNotNull(changed);assertEquals("sensor.second",changed.entities().get(0));
            assertEquals("sensor.second",menus.poll(5,TimeUnit.SECONDS).items.get(0).name);
            assertEquals("close",take(states).get("sensor.second"));
            server.client.close(1001,"Test disconnect");assertNotNull(errors.poll(5,TimeUnit.SECONDS));
            assertNotNull(configs.poll(7,TimeUnit.SECONDS));assertEquals("close",take(states).get("sensor.second"));
            assertNotNull(menus.poll(5,TimeUnit.SECONDS));
            server.badMenu=true;server.sendEvent(1,new JSONObject("{\"data\":{\"url_path\":\"helios-clock\"}}"));
            assertNotNull(menuErrors.poll(5,TimeUnit.SECONDS));assertNotNull(configs.poll(5,TimeUnit.SECONDS));
            assertEquals("close",take(states).get("sensor.second"));assertTrue(menus.isEmpty());
        }finally{client.stop();server.stop(2000);}
    }
    private static Map<String,String> take(BlockingQueue<Map<String,String>> queue)throws Exception{Map<String,String> value=queue.poll(5,TimeUnit.SECONDS);assertNotNull(value);return value;}
}
