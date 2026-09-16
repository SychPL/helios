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

/**
 * One reconnecting WebSocket session to HA shared by every module: dashboard configuration and entity states,
 * a closed set of service calls, and generic request/subscribe for the device channel. HA requires strictly
 * increasing message ids per connection, so id allocation and sending are one critical section.
 */
final class HaDashboardClient {
    interface Listener {
        /** A layout together with its latest snapshot. spec is null when no valid version-2 document exists anywhere; configIssue is null when HA's current document parsed. */
        void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String configIssue);
        void onStates(Map<String,EntityStates.Entity> states);
        void onUnavailable(String reason);
        /** Authenticated session established; modules (re)create their subscriptions here. */
        default void onSessionStarted(){}
    }
    static volatile long CALL_TIMEOUT_MS=10000;
    private final JSONObject connection;
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    private final Object replay=new Object();
    private volatile boolean stopped,authenticated,live;
    private volatile Socket socket;
    private Thread worker;
    private JSONObject lastGoodRaw;
    private DashboardSpec lastGood;
    // Replay state for late listeners: always the newest snapshot, even while nobody is attached.
    private boolean haveDashboard;
    private JSONObject lastRaw;
    private DashboardSpec lastSpec;
    private Map<String,EntityStates.Entity> lastStates=Collections.emptyMap();
    private String lastIssue,lastReason="Łączenie z konfiguracją ekranu w HA…";
    private final AtomicInteger ids=new AtomicInteger(1);
    private final Map<Integer,Pending> pending=new ConcurrentHashMap<>();
    private final Map<Integer,Subscription> subscriptions=new ConcurrentHashMap<>();
    private static final class Pending {final Consumer<JSONObject> done;final long deadline;Pending(Consumer<JSONObject> done,long deadline){this.done=done;this.deadline=deadline;}}
    private static final class Subscription {final Consumer<JSONObject> event;final Consumer<String> ended;Subscription(Consumer<JSONObject> event,Consumer<String> ended){this.event=event;this.ended=ended;}}

    HaDashboardClient(JSONObject connection,JSONObject cachedRaw){
        this.connection=connection;
        if(cachedRaw!=null)try{lastGood=DashboardSpec.parse(cachedRaw);lastGoodRaw=cachedRaw;}catch(Exception ignored){}
    }
    void start(){worker=new Thread(this::loop,"helios-ha");worker.start();}
    void stop(){stopped=true;Socket current=socket;if(current!=null)current.close();if(worker!=null)worker.interrupt();}
    boolean live(){return live;}
    JSONObject connection(){return connection;}

    /** Subscribes and immediately replays the newest layout and, when the session is down, the current reason. */
    void attach(Listener listener){
        synchronized(replay){
            listeners.add(listener);
            if(haveDashboard)listener.onDashboard(lastRaw,lastSpec,lastStates,lastIssue);
            if(!live&&lastReason!=null)listener.onUnavailable(lastReason);
        }
    }
    void detach(Listener listener){listeners.remove(listener);}

    /** One service call for one entity; done receives null on acceptance or an error text. Never retried, never queued. */
    void callService(String domain,String service,String entityId,Consumer<String> done){
        if(!live){done.accept("Brak połączenia z Home Assistant");return;}
        try{
            request(new JSONObject().put("type","call_service").put("domain",domain).put("service",service).put("target",new JSONObject().put("entity_id",entityId)),
                m->done.accept(m.optBoolean("success")?null:errorText(m,"HA odrzucił polecenie")));
        }catch(JSONException e){done.accept("Nie udało się wysłać polecenia");}
    }
    private static String errorText(JSONObject m,String fallback){JSONObject error=m.optJSONObject("error");return error==null?fallback:error.optString("message",fallback);}
    /** HA's structural error.code of a result; subscriptions end with this code (never the translated message), "subscription_rejected" without one. */
    static String errorCode(JSONObject m){JSONObject error=m.optJSONObject("error");return error==null?"subscription_rejected":error.optString("code","subscription_rejected");}
    /** Sends a command on the authenticated session; result receives HA's whole result message or a synthesized failure. Returns the id or -1. */
    int request(JSONObject payload,Consumer<JSONObject> result){
        Socket s=socket;
        if(s==null||!authenticated){result.accept(failure("Brak połączenia z Home Assistant"));return -1;}
        try{
            synchronized(this){
                int id=ids.getAndIncrement();
                pending.put(id,new Pending(result,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(CALL_TIMEOUT_MS)));
                try{s.send(payload.put("id",id).toString());}catch(Exception e){pending.remove(id);throw e;}
                return id;
            }
        }catch(Exception e){result.accept(failure("Nie udało się wysłać polecenia"));return -1;}
    }
    /** Like request, but events with this id go to event until the session ends (ended receives a code: HA's error.code, "disconnected", "send_failed"). Not renewed automatically: subscribe again in onSessionStarted. */
    int subscribe(JSONObject payload,Consumer<JSONObject> event,Consumer<String> ended){
        Socket s=socket;
        if(s==null||!authenticated){ended.accept("disconnected");return -1;}
        try{
            synchronized(this){
                int id=ids.getAndIncrement();
                subscriptions.put(id,new Subscription(event,ended));
                try{s.send(payload.put("id",id).toString());}catch(Exception e){subscriptions.remove(id);throw e;}
                return id;
            }
        }catch(Exception e){ended.accept("send_failed");return -1;}
    }
    private synchronized int send(Socket s,JSONObject payload) throws Exception {int id=ids.getAndIncrement();s.send(payload.put("id",id).toString());return id;}
    static JSONObject failure(String message){try{return new JSONObject().put("success",false).put("error",new JSONObject().put("code","helios").put("message",message));}catch(JSONException e){throw new IllegalStateException(e);}}
    private void failPending(String reason){
        for(Integer id:new ArrayList<>(pending.keySet())){Pending p=pending.remove(id);if(p!=null)p.done.accept(failure(reason));}
        for(Integer id:new ArrayList<>(subscriptions.keySet())){Subscription sub=subscriptions.remove(id);if(sub!=null)sub.ended.accept("disconnected");}
    }
    private void expirePending(){
        long now=System.nanoTime();
        for(Map.Entry<Integer,Pending> e:new ArrayList<>(pending.entrySet()))if(now>e.getValue().deadline&&pending.remove(e.getKey())!=null)e.getValue().done.accept(failure("HA nie potwierdził polecenia w czasie "+(CALL_TIMEOUT_MS/1000)+" s"));
    }
    private void emitDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){
        synchronized(replay){haveDashboard=true;lastRaw=raw;lastSpec=spec;lastStates=states;lastIssue=issue;live=true;for(Listener l:listeners)l.onDashboard(raw,spec,states,issue);}
    }
    private void emitStates(Map<String,EntityStates.Entity> states){
        synchronized(replay){lastStates=states;for(Listener l:listeners)l.onStates(states);}
    }
    private void emitUnavailable(String reason){
        synchronized(replay){live=false;lastReason=reason;for(Listener l:listeners)l.onUnavailable(reason);}
    }

    private final class Socket extends WebSocketClient {
        final BlockingQueue<JSONObject> queue=new ArrayBlockingQueue<>(256);
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
            catch(Exception e){if(!stopped)emitUnavailable("HA niedostępny - dane nieaktualne");}
            finally{authenticated=false;live=false;Socket old=socket;socket=null;if(old!=null)old.close();failPending("Połączenie z HA przerwane");}
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
        int updatesId=send(s,new JSONObject().put("type","subscribe_events").put("event_type","lovelace_updated"));
        int configId=send(s,new JSONObject().put("type","lovelace/config").put("url_path",path));
        authenticated=true;
        for(Listener l:listeners)l.onSessionStarted();
        int entitiesId=-1;
        DashboardSpec spec=null;JSONObject raw=null;String issue=null;EntityStates states=new EntityStates();boolean initial=false;
        long initialDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!stopped){
            if(!initial&&System.nanoTime()>initialDeadline)throw new IOException("HA initial state timeout");
            expirePending();
            JSONObject m=s.next(1);if(m==null)continue;
            int id=m.optInt("id");String type=m.optString("type");
            if(type.equals("result")){
                Pending call=pending.remove(id);
                if(call!=null){call.done.accept(m);continue;}
                if(subscriptions.containsKey(id)){
                    if(!m.optBoolean("success")){Subscription sub=subscriptions.remove(id);if(sub!=null)sub.ended.accept(errorCode(m));}
                    continue;
                }
                if(!m.optBoolean("success")){
                    if(id!=configId)throw new IOException("Subskrypcja odrzucona");
                    issue="Panel Helios nie jest dostępny w HA";
                }else if(id==configId){
                    try{raw=m.getJSONObject("result").getJSONObject("helios");spec=DashboardSpec.parse(raw);lastGood=spec;lastGoodRaw=raw;}
                    catch(Exception e){issue="Błąd konfiguracji w HA: "+e.getMessage();spec=null;raw=null;}
                }
                if(id==configId){
                    // A broken or missing document never removes the last good layout; its entities stay live.
                    if(spec==null){spec=lastGood;raw=lastGoodRaw;}
                    if(spec==null){initial=true;emitDashboard(null,null,Collections.emptyMap(),issue);continue;}
                    states=new EntityStates(spec.attributes());
                    List<String> entities=spec.entities();
                    if(entities.isEmpty()){initial=true;emitDashboard(raw,spec,Collections.emptyMap(),issue);}
                    else entitiesId=send(s,new JSONObject().put("type","subscribe_entities").put("entity_ids",new JSONArray(entities)));
                }
            }else if(type.equals("event")){
                JSONObject event=m.getJSONObject("event");
                if(id==updatesId){
                    if(path.equals(event.optJSONObject("data")==null?null:event.getJSONObject("data").optString("url_path"))){emitUnavailable("Odświeżam konfigurację z HA…");return true;}
                }else if(id==entitiesId&&spec!=null){
                    states.apply(event);
                    if(!initial){initial=true;emitDashboard(raw,spec,states.snapshot(),issue);}
                    else emitStates(states.snapshot());
                }else{
                    Subscription sub=subscriptions.get(id);
                    if(sub!=null)sub.event.accept(event);
                }
            }else if(type.equals("auth_invalid"))throw new IOException("HA auth failed");
        }
        return false;
    }

    /** Authenticates once with the given connection on a temporary socket; returns null on success or an error text. Never touches a running session. */
    static String probe(JSONObject connection){
        WebSocketClient client=null;
        try{
            URI base=new URI(connection.getString("url"));
            String scheme=base.getScheme();
            if(!"http".equals(scheme)&&!"https".equals(scheme))throw new IOException("Adres HA musi zaczynać się od http:// lub https://");
            if(connection.getString("token").trim().isEmpty())throw new IOException("Pusty token HA");
            BlockingQueue<String> messages=new LinkedBlockingQueue<>();
            client=new WebSocketClient(new URI(scheme.equals("https")?"wss":"ws",null,base.getHost(),base.getPort(),"/api/websocket",null,null)){
                @Override public void onOpen(ServerHandshake h){}
                @Override public void onMessage(String message){messages.offer(message);}
                @Override public void onClose(int code,String reason,boolean remote){messages.offer("{\"type\":\"closed\"}");}
                @Override public void onError(Exception e){messages.offer("{\"type\":\"closed\"}");}
            };
            if(!client.connectBlocking(10,TimeUnit.SECONDS))throw new IOException("HA nie odpowiada");
            String first=messages.poll(10,TimeUnit.SECONDS);
            if(first==null||!new JSONObject(first).optString("type").equals("auth_required"))throw new IOException("HA nie odpowiada jak Home Assistant");
            client.send(new JSONObject().put("type","auth").put("access_token",connection.getString("token")).toString());
            String second=messages.poll(10,TimeUnit.SECONDS);
            if(second==null)throw new IOException("HA nie odpowiada");
            if(!new JSONObject(second).optString("type").equals("auth_ok"))throw new IOException("HA odrzucił token");
            return null;
        }catch(Exception e){return e.getMessage()==null?"Błąd połączenia z HA":e.getMessage();}
        finally{if(client!=null)client.close();}
    }
}
