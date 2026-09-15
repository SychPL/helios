package pl.mateusz.helios;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Foreground service that owns the HA transport, the device channel, the dock binder and the volume poller independently of the activity. */
public final class HeliosService extends Service {
    public final class Local extends Binder {HeliosService service(){return HeliosService.this;}}
    private static final String CHANNEL="helios";
    private final Local binder=new Local();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final long startedAt=SystemClock.elapsedRealtime();
    private JSONObject connection;
    private String installationId;
    private HaDashboardClient ha;
    private HeliosDeviceClient device;
    private DockController dock;
    private DeviceVolume volume;
    private volatile String voiceState="idle",deviceId;
    private Runnable onDeviceLost;
    private final HaDashboardClient.Listener cache=new HaDashboardClient.Listener(){
        @Override public void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){
            if(raw!=null&&issue==null)getSharedPreferences("helios",MODE_PRIVATE).edit().putString("dashboard_v2",raw.toString()).apply();
        }
        @Override public void onStates(Map<String,EntityStates.Entity> states){}
        @Override public void onUnavailable(String reason){}
    };

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Helios",NotificationManager.IMPORTANCE_MIN));
        Notification notification=new Notification.Builder(this,CHANNEL).setContentTitle("Helios działa").setSmallIcon(R.drawable.ic_helios).setOngoing(true).build();
        startForeground(1,notification);
        installationId=getSharedPreferences("helios",MODE_PRIVATE).getString("installation_id",null);
        if(installationId==null){installationId=UUID.randomUUID().toString();getSharedPreferences("helios",MODE_PRIVATE).edit().putString("installation_id",installationId).apply();}
        dock=new DockController(this,this::publish);dock.start();
        volume=new DeviceVolume(this,v->publish());volume.start();
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{connection=new JSONObject(saved);}catch(Exception ignored){}
        if(connection!=null)startHa();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){stopHa();dock.stop();volume.stop();network.shutdownNow();super.onDestroy();}

    JSONObject connection(){return connection;}
    HaDashboardClient ha(){return ha;}
    DockController dock(){return dock;}
    DeviceVolume volume(){return volume;}
    String installationId(){return installationId;}
    /** HA registry device id of this clock, null until the device channel is connected. */
    String deviceId(){return deviceId;}
    boolean devicePaired(){return device!=null&&device.active();}
    void pair(String code){if(device!=null)device.pair(code);}
    void setVoiceState(String state){if(!state.equals(voiceState)){voiceState=state;publish();}}
    void setOnDeviceLost(Runnable action){onDeviceLost=action;}
    void publish(){if(device!=null)device.publish();}

    private void startHa(){
        String cached=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_v2",null);
        JSONObject cachedRaw=null;
        if(cached!=null)try{cachedRaw=new JSONObject(cached);}catch(Exception ignored){}
        ha=new HaDashboardClient(connection,cachedRaw);ha.attach(cache);
        device=new HeliosDeviceClient(ha,installationId,this::telemetry,this::execute,(id,area)->main.post(()->{
            boolean lost=deviceId!=null&&id==null;deviceId=id;
            if(lost&&onDeviceLost!=null)onDeviceLost.run();
        }));
        device.start();ha.start();
    }
    private void stopHa(){if(device!=null){device.stop();device=null;}if(ha!=null){ha.stop();ha=null;}deviceId=null;}
    private Telemetry telemetry(){
        return new Telemetry(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,voiceState,dock.dockConnected(),dock.charging(),dock.ledOn(),dock.ledBrightness(),dock.padVersion(),volume.percent(),(SystemClock.elapsedRealtime()-startedAt)/1000);
    }
    /** Allowlisted hardware commands from HA; the caller already validated names and argument ranges. */
    private String execute(String command,JSONObject args) throws Exception {
        switch(command){
            case "lamp.turn_on":if(dock.unavailable()!=null)return "dock_unavailable";dock.turnOn();return null;
            case "lamp.turn_off":if(dock.unavailable()!=null)return "dock_unavailable";dock.turnOff();return null;
            case "lamp.set_brightness":if(dock.unavailable()!=null)return "dock_unavailable";dock.setBrightness(args.getInt("level"));return null;
            case "audio.set_device_volume":volume.set(args.getInt("percent"));return null;
            default:return "unknown_command";
        }
    }
    /**
     * The only path that changes the connection: validates and authenticates the changed HA data on a temporary socket,
     * and only then persists and restarts the transport. done receives null on success or an error text (main thread).
     * ponytail: HA section only; the music_assistant section joins in 0.6 with the same per-section rule.
     */
    void reconfigure(JSONObject received,Consumer<String> done){
        network.execute(()->{
            String error=null;
            try{
                if(received.getString("token").isEmpty()||received.getString("pipeline").isEmpty())throw new IllegalArgumentException("Niepełne parowanie");
                boolean haChanged=connection==null||!sameHa(connection,received);
                if(haChanged)error=HaDashboardClient.probe(received);
                if(error==null){
                    JSONObject merged=connection==null?new JSONObject(received.toString()):new JSONObject(connection.toString());
                    for(String key:new String[]{"url","token","pipeline","dashboard_path","diagnostics_url"})if(received.has(key))merged.put(key,received.get(key));else merged.remove(key);
                    getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",merged.toString()).apply();
                    connection=merged;
                    if(haChanged)main.post(()->{stopHa();startHa();});
                }
            }catch(Exception e){error=e.getMessage()==null?"Błąd konfiguracji":e.getMessage();}
            final String result=error;main.post(()->done.accept(result));
        });
    }
    static boolean sameHa(JSONObject a,JSONObject b){
        for(String key:new String[]{"url","token","pipeline","dashboard_path"})if(!a.optString(key,"").equals(b.optString(key,"")))return false;
        return true;
    }
}
