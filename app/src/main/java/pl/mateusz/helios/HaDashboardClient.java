package pl.mateusz.helios;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.*;
import java.net.URI;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Read-only, reconnecting live configuration and entity-state subscription. */
final class HaDashboardClient {
    interface Listener {
        void onConfig(JSONObject raw,DashboardSpec spec);
        void onStates(Map<String,String> states);
        void onUnavailable(String reason);
        default void onMenu(JSONObject raw,MenuSpec menu){}
        default void onMenuError(String reason){}
    }
    private final JSONObject connection;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread worker;
    HaDashboardClient(JSONObject connection,Listener listener){this.connection=connection;this.listener=listener;}
    void start(){worker=new Thread(this::loop,"helios-dashboard");worker.start();}
    void stop(){stopped=true;Socket current=socket;if(current!=null)current.close();if(worker!=null)worker.interrupt();}
    private final class Socket extends WebSocketClient {
        final BlockingQueue<JSONObject> queue=new ArrayBlockingQueue<>(128);
        volatile boolean failed;
        Socket(URI uri){super(uri);setConnectionLostTimeout(10);}
        @Override public void onOpen(ServerHandshake handshake){}
        @Override public void onMessage(String message){
            try{if(message.length()>262144||!queue.offer(new JSONObject(message)))failed=true;}catch(Exception e){failed=true;}
        }
        @Override public void onClose(int code,String reason,boolean remote){failed=true;}
        @Override public void onError(Exception e){failed=true;}
        JSONObject next(int seconds) throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(!stopped&&System.nanoTime()<until){
                if(failed)throw new IOException("Połączenie z HA przerwane");
                JSONObject message=queue.poll(200,TimeUnit.MILLISECONDS);if(message!=null)return message;
            }
            if(stopped)throw new InterruptedException();
            return null;
        }
        JSONObject required() throws Exception {JSONObject m=next(15);if(m==null)throw new IOException("HA nie odpowiada");return m;}
        void command(int id,String type) throws Exception {send(new JSONObject().put("id",id).put("type",type).toString());}
    }
    private void loop(){
        int delay=2;
        while(!stopped){
            boolean reload=false;
            try{reload=session();delay=2;}
            catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            catch(Exception e){if(!stopped)listener.onUnavailable(e instanceof ConfigError?"Błąd konfiguracji w HA: "+e.getMessage():"HA niedostępny — stan wskaźników nieznany");}
            finally{Socket old=socket;socket=null;if(old!=null)old.close();}
            if(!stopped&&!reload)try{Thread.sleep(delay*1000L);delay=Math.min(30,delay*2);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }
    private static final class ConfigError extends Exception {ConfigError(String message){super(message);}}
    private boolean session() throws Exception {
        URI base=new URI(connection.getString("url"));
        Socket s=new Socket(new URI(base.getScheme().equals("https")?"wss":"ws",null,base.getHost(),base.getPort(),"/api/websocket",null,null));socket=s;
        if(stopped)return false;
        if(!s.connectBlocking(10,TimeUnit.SECONDS))throw new IOException("HA connection failed");
        if(!s.required().optString("type").equals("auth_required"))throw new IOException("HA handshake");
        s.send(new JSONObject().put("type","auth").put("access_token",connection.getString("token")).toString());
        if(!s.required().optString("type").equals("auth_ok"))throw new IOException("HA authentication");
        String path=connection.optString("dashboard_path","helios-clock");
        s.send(new JSONObject().put("id",1).put("type","subscribe_events").put("event_type","lovelace_updated").toString());
        s.send(new JSONObject().put("id",2).put("type","lovelace/config").put("url_path",path).toString());
        DashboardSpec spec=null;EntityStates states=new EntityStates();boolean initial=false;
        long initialDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!stopped){
            if(!initial&&System.nanoTime()>initialDeadline)throw new IOException("HA initial state timeout");
            JSONObject m=s.next(1);if(m==null)continue;
            int id=m.optInt("id");String type=m.optString("type");
            if(type.equals("result")){
                if(!m.optBoolean("success"))throw new ConfigError(id==2?"Panel Helios nie jest dostępny":"Subskrypcja odrzucona");
                if(id==2){
                    JSONObject document=m.getJSONObject("result");
                    try{
                        JSONObject menu=MenuSpec.view(document);
                        if(menu!=null)listener.onMenu(menu,MenuSpec.parse(menu));
                    }catch(Exception e){listener.onMenuError("Błąd menu w HA: "+e.getMessage());}
                    try{
                        JSONObject raw=document.getJSONObject("helios");
                        spec=DashboardSpec.parse(raw);listener.onConfig(raw,spec);
                    }catch(Exception e){throw new ConfigError(e.getMessage());}
                    List<String> entities=spec.entities();
                    if(entities.isEmpty()){initial=true;listener.onStates(Collections.emptyMap());}
                    else s.send(new JSONObject().put("id",3).put("type","subscribe_entities").put("entity_ids",new JSONArray(entities)).toString());
                }
            }else if(type.equals("event")){
                JSONObject event=m.getJSONObject("event");
                if(id==1&&path.equals(event.optJSONObject("data")==null?null:event.getJSONObject("data").optString("url_path")))return true;
                if(id==3&&spec!=null){states.apply(event);initial=true;listener.onStates(states.snapshot());}
            }
            if(!initial&&spec==null&&m.optString("type").equals("auth_invalid"))throw new IOException("HA auth failed");
        }
        return false;
    }
}
