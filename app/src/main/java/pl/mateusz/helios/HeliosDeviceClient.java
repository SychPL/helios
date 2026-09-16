package pl.mateusz.helios;

import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * The clock side of the helios/* channel: one subscription per HA session, full telemetry snapshots,
 * and an allowlisted command handler bound to the subscription generation that received it.
 * Pairing is no longer part of the channel (SPEC 0.10 pkt 4): the entry exists before the first connect.
 */
final class HeliosDeviceClient implements HaDashboardClient.Listener {
    interface CommandHandler {
        /** Executes one allowlisted command; returns null on success or a short error code. */
        String execute(String command,JSONObject args) throws Exception;
    }
    interface Listener {
        /** deviceId and areaId are null whenever the channel is not established; name is the HA device name (user rename included). */
        void onDevice(String deviceId,String areaId,String name);
        /** Full appearance snapshot from the integration (after connected and after every save); raw JSON, validated by the service. */
        default void onAppearance(JSONObject appearance){}
        /** The connection event (pipeline, dashboard path, MA section, diagnostics) after connected and after every options save. */
        default void onConnection(JSONObject payload){}
        /** A channel state the user must know about: the entry is gone (no retry) or another device took the pairing. */
        default void onChannelIssue(String text){}
    }
    static final int PROTOCOL=2;
    static volatile long MIN_PUBLISH_INTERVAL_MS=100;
    static final long[] RESUBSCRIBE_DELAYS_MS={2000,5000,10000,30000};
    static final List<String> COMMANDS=Arrays.asList("lamp.turn_on","lamp.turn_off","lamp.set_brightness","audio.set_device_volume","music.play","music.pause","music.stop");
    private final HaDashboardClient ha;
    private final Supplier<Telemetry> telemetry;
    private final CommandHandler handler;
    private final Listener listener;
    private final String installationId;
    private volatile String deviceId,areaId,deviceName;
    private volatile int generation;
    private volatile boolean active;
    private int resubscribeAttempt;
    private ScheduledFuture<?> resubscribe;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private final Object publishLock=new Object();
    private Telemetry lastSent;
    private long lastSentAt;
    private ScheduledFuture<?> scheduledPublish;

    HeliosDeviceClient(HaDashboardClient ha,String installationId,Supplier<Telemetry> telemetry,CommandHandler handler,Listener listener){
        this.ha=ha;this.installationId=installationId;this.telemetry=telemetry;this.handler=handler;this.listener=listener;
    }
    void start(){ha.attach(this);}
    void stop(){ha.detach(this);synchronized(this){cancelResubscribe();}worker.shutdownNow();scheduler.shutdownNow();}
    String deviceId(){return deviceId;}
    String areaId(){return areaId;}
    String deviceName(){return deviceName;}
    boolean active(){return active;}

    @Override public void onSessionStarted(){synchronized(this){cancelResubscribe();resubscribeAttempt=0;}connect();}
    @Override public void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){}
    @Override public void onStates(Map<String,EntityStates.Entity> states){}
    @Override public void onUnavailable(String reason){}

    private synchronized void connect(){
        final int gen=++generation;
        active=false;
        Telemetry now=telemetry.get();
        try{
            JSONObject payload=new JSONObject().put("type","helios/connect").put("protocol",PROTOCOL).put("installation_id",installationId)
                .put("app_version",now.appVersion).put("version_code",now.versionCode).put("capabilities",new org.json.JSONArray(Arrays.asList("lamp","volume","music")));
            ha.subscribe(payload,event->handle(gen,event),code->rejected(gen,code));
        }catch(Exception e){ended(gen,true);}
    }
    /** Subscription refused or lost; the argument is HA's error.code ("disconnected" when the socket went): unauthorized = entry gone, no retry. */
    private void rejected(int gen,String code){
        boolean unauthorized="unauthorized".equals(code);
        ended(gen,!unauthorized);
        if(unauthorized&&listener!=null)listener.onChannelIssue("Zegar usunięty z HA - sparuj ponownie");
    }
    private void handle(int gen,JSONObject event){
        if(gen!=generation)return;
        switch(event.optString("type")){
            case "connected":
                deviceId=event.isNull("device_id")?null:event.optString("device_id",null);
                areaId=event.isNull("area_id")?null:event.optString("area_id",null);
                deviceName=event.isNull("name")?null:event.optString("name",null);
                active=true;
                synchronized(this){resubscribeAttempt=0;}
                if(listener!=null)listener.onDevice(deviceId,areaId,deviceName);
                synchronized(publishLock){lastSent=null;}
                publish();
                break;
            case "device": // HA renamed or moved the device: keep the player name and voice context in step
                areaId=event.isNull("area_id")?null:event.optString("area_id",null);
                deviceName=event.isNull("name")?null:event.optString("name",null);
                if(listener!=null)listener.onDevice(deviceId,areaId,deviceName);
                break;
            case "command":execute(gen,event);break;
            case "appearance":{JSONObject a=event.optJSONObject("appearance");if(a!=null&&listener!=null)listener.onAppearance(a);break;}
            case "connection":{if(listener!=null)listener.onConnection(event);break;}
            case "removed":ended(gen,true);break; // integration reloaded or the entry is going: try again, the socket is still ours
            case "replaced":ended(gen,false);if(listener!=null)listener.onChannelIssue("Inne urządzenie przejęło to parowanie");break;
            default:break;
        }
    }
    private synchronized void ended(int gen,boolean retry){
        if(gen!=generation)return;
        generation++;active=false;deviceId=null;areaId=null;
        synchronized(publishLock){if(scheduledPublish!=null){scheduledPublish.cancel(false);scheduledPublish=null;}}
        if(listener!=null)listener.onDevice(null,null,deviceName);
        if(retry)scheduleResubscribe();
    }
    /** Backoff 2/5/10/30 s on the live socket; when the socket dies meanwhile the session's onSessionStarted owns the next connect, this attempt simply lapses. */
    private void scheduleResubscribe(){
        cancelResubscribe();
        long delay=RESUBSCRIBE_DELAYS_MS[Math.min(resubscribeAttempt++,RESUBSCRIBE_DELAYS_MS.length-1)];
        try{resubscribe=scheduler.schedule(()->{if(ha.live())connect();},delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){}
    }
    private void cancelResubscribe(){if(resubscribe!=null){resubscribe.cancel(false);resubscribe=null;}}

    /** Sends a full snapshot, coalesced to at most one per MIN_PUBLISH_INTERVAL_MS with the newest values; nothing is queued while inactive. */
    void publish(){
        if(!active)return;
        synchronized(publishLock){
            long now=System.currentTimeMillis();long wait=lastSentAt+MIN_PUBLISH_INTERVAL_MS-now;
            if(wait<=0){sendState();return;}
            if(scheduledPublish==null||scheduledPublish.isDone())scheduledPublish=scheduler.schedule(()->{synchronized(publishLock){sendState();}},wait,TimeUnit.MILLISECONDS);
        }
    }
    private void sendState(){
        if(!active)return;
        final int gen=generation;
        Telemetry now=telemetry.get();
        if(now.sameAs(lastSent))return;
        lastSent=now;lastSentAt=System.currentTimeMillis();
        try{ha.request(new JSONObject().put("type","helios/state").put("state",now.toJson()),r->{if(!r.optBoolean("success",true))ended(gen,true);});}catch(Exception ignored){} // a refused state = the subscription is gone (SPEC 0.10 pkt 8.1)
    }

    private void execute(int gen,JSONObject event){
        String requestId=event.optString("request_id","");String command=event.optString("command","");
        JSONObject args=event.optJSONObject("args");if(args==null)args=new JSONObject();
        String invalid=validate(command,args);
        if(invalid!=null){result(gen,requestId,"error",invalid);return;}
        String resource=command.startsWith("lamp.")?"lamp":command.startsWith("music.")?"music":"volume";
        if(!busy.add(resource)){result(gen,requestId,"busy",resource);return;}
        final JSONObject finalArgs=args;
        worker.execute(()->{
            String status="ok",code=null;
            try{
                if(gen!=generation)return; // channel replaced or ended while queued: never touch hardware for a stale command
                code=handler.execute(command,finalArgs);
                if(code!=null)status="error";
            }catch(Exception e){status="error";code=e.getClass().getSimpleName();}
            finally{busy.remove(resource);}
            if(gen==generation){result(gen,requestId,status,code);publish();}
        });
    }
    static String validate(String command,JSONObject args){
        if(!COMMANDS.contains(command))return "unknown_command";
        switch(command){
            case "lamp.set_brightness":{Object level=args.opt("level");return level instanceof Integer&&(Integer)level>=1&&(Integer)level<=10?null:"invalid_args";}
            case "audio.set_device_volume":{Object percent=args.opt("percent");return percent instanceof Integer&&(Integer)percent>=0&&(Integer)percent<=100?null:"invalid_args";}
            default:return null;
        }
    }
    private void result(int gen,String requestId,String status,String code){
        if(gen!=generation)return;
        try{
            JSONObject payload=new JSONObject().put("type","helios/result").put("request_id",requestId).put("status",status);
            if(code!=null)payload.put("code",code);
            ha.request(payload,r->{if(!r.optBoolean("success",true))ended(gen,true);}); // a refused result = the subscription is gone (SPEC 0.10 pkt 8.1)
        }catch(Exception ignored){}
    }
}
