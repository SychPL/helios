package pl.mateusz.helios;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.*;
import java.net.URI;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Reconnecting live dashboard configuration, entity-state subscription and a closed set of service calls. */
final class HaDashboardClient {
    interface Listener {
        /** A layout together with its first complete snapshot. spec is null when no valid version-2 document exists anywhere; configIssue is null when HA's current document parsed. */
        void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String configIssue);
        void onStates(Map<String,EntityStates.Entity> states);
        void onUnavailable(String reason);
    }
    static volatile long CALL_TIMEOUT_MS=10000;
    private final JSONObject connection;
    private final Listener listener;
    private volatile boolean stopped,live;
    private volatile Socket socket;
    private Thread worker;
    private JSONObject lastGoodRaw;
    private DashboardSpec lastGood;
    private final AtomicInteger ids=new AtomicInteger(100);
    private final Map<Integer,Pending> pending=new ConcurrentHashMap<>();
    private static final class Pending {final Consumer<String> done;final long deadline;Pending(Consumer<String> done,long deadline){this.done=done;this.deadline=deadline;}}

    HaDashboardClient(JSONObject connection,JSONObject cachedRaw,Listener listener){
        this.connection=connection;this.listener=listener;
        if(cachedRaw!=null)try{lastGood=DashboardSpec.parse(cachedRaw);lastGoodRaw=cachedRaw;}catch(Exception ignored){}
    }
    void start(){worker=new Thread(this::loop,"helios-dashboard");worker.start();}
    void stop(){stopped=true;Socket current=socket;if(current!=null)current.close();if(worker!=null)worker.interrupt();}
    boolean live(){return live;}

    /** One service call for one entity; done receives null on acceptance or an error text. Never retried, never queued. */
    void callService(String domain,String service,String entityId,Consumer<String> done){
        Socket s=socket;
        if(s==null||!live){done.accept("Brak połączenia z Home Assistant");return;}
        int id=ids.getAndIncrement();
        pending.put(id,new Pending(done,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(CALL_TIMEOUT_MS)));
        try{s.send(new JSONObject().put("id",id).put("type","call_service").put("domain",domain).put("service",service).put("target",new JSONObject().put("entity_id",entityId)).toString());}
        catch(Exception e){if(pending.remove(id)!=null)done.accept("Nie udało się wysłać polecenia");}
    }
    private void failPending(String reason){for(Integer id:new ArrayList<>(pending.keySet())){Pending p=pending.remove(id);if(p!=null)p.done.accept(reason);}}
    private void expirePending(){
        long now=System.nanoTime();
        for(Map.Entry<Integer,Pending> e:new ArrayList<>(pending.entrySet()))if(now>e.getValue().deadline&&pending.remove(e.getKey())!=null)e.getValue().done.accept("HA nie potwierdził polecenia w czasie "+(CALL_TIMEOUT_MS/1000)+" s");
    }

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
    }
    private void loop(){
        int delay=2;
        while(!stopped){
            boolean reload=false;
            try{reload=session();delay=2;}
            catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            catch(Exception e){if(!stopped)listener.onUnavailable("HA niedostępny - dane nieaktualne");}
            finally{live=false;Socket old=socket;socket=null;if(old!=null)old.close();failPending("Połączenie z HA przerwane");}
            if(!stopped&&!reload)try{Thread.sleep(delay*1000L);delay=Math.min(30,delay*2);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
    }
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
        DashboardSpec spec=null;JSONObject raw=null;String issue=null;EntityStates states=new EntityStates();boolean initial=false;
        long initialDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!stopped){
            if(!initial&&System.nanoTime()>initialDeadline)throw new IOException("HA initial state timeout");
            expirePending();
            JSONObject m=s.next(1);if(m==null)continue;
            int id=m.optInt("id");String type=m.optString("type");
            if(type.equals("result")){
                Pending call=pending.remove(id);
                if(call!=null){call.done.accept(m.optBoolean("success")?null:m.optJSONObject("error")==null?"HA odrzucił polecenie":m.getJSONObject("error").optString("message","HA odrzucił polecenie"));continue;}
                if(!m.optBoolean("success")){
                    if(id!=2)throw new IOException("Subskrypcja odrzucona");
                    issue="Panel Helios nie jest dostępny w HA";
                }else if(id==2){
                    try{raw=m.getJSONObject("result").getJSONObject("helios");spec=DashboardSpec.parse(raw);lastGood=spec;lastGoodRaw=raw;}
                    catch(Exception e){issue="Błąd konfiguracji w HA: "+e.getMessage();spec=null;raw=null;}
                }
                if(id==2){
                    // A broken or missing document never removes the last good layout; its entities stay live.
                    if(spec==null){spec=lastGood;raw=lastGoodRaw;}
                    if(spec==null){initial=true;live=true;listener.onDashboard(null,null,Collections.emptyMap(),issue);continue;}
                    states=new EntityStates(spec.attributes());
                    List<String> entities=spec.entities();
                    if(entities.isEmpty()){initial=true;live=true;listener.onDashboard(raw,spec,Collections.emptyMap(),issue);}
                    else s.send(new JSONObject().put("id",3).put("type","subscribe_entities").put("entity_ids",new JSONArray(entities)).toString());
                }
            }else if(type.equals("event")){
                JSONObject event=m.getJSONObject("event");
                if(id==1&&path.equals(event.optJSONObject("data")==null?null:event.getJSONObject("data").optString("url_path"))){
                    listener.onUnavailable("Odświeżam konfigurację z HA…");return true;
                }
                if(id==3&&spec!=null){
                    states.apply(event);
                    if(!initial){initial=true;live=true;listener.onDashboard(raw,spec,states.snapshot(),issue);}
                    else listener.onStates(states.snapshot());
                }
            }else if(type.equals("auth_invalid"))throw new IOException("HA auth failed");
        }
        return false;
    }
}
