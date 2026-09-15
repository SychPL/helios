package pl.mateusz.helios;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Foreground service that owns the HA transport independently of the activity's visibility. */
public final class HeliosService extends Service {
    public final class Local extends Binder {HeliosService service(){return HeliosService.this;}}
    private static final String CHANNEL="helios";
    private final Local binder=new Local();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private JSONObject connection;
    private HaDashboardClient ha;
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
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{connection=new JSONObject(saved);}catch(Exception ignored){}
        if(connection!=null)startHa();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){if(ha!=null){ha.stop();ha=null;}network.shutdownNow();super.onDestroy();}

    JSONObject connection(){return connection;}
    HaDashboardClient ha(){return ha;}
    private void startHa(){
        String cached=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_v2",null);
        JSONObject cachedRaw=null;
        if(cached!=null)try{cachedRaw=new JSONObject(cached);}catch(Exception ignored){}
        ha=new HaDashboardClient(connection,cachedRaw);ha.attach(cache);ha.start();
    }
    /**
     * The only path that changes the connection: validates and authenticates the new HA data on a temporary socket,
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
                    if(haChanged)main.post(()->{if(ha!=null){ha.stop();ha=null;}startHa();});
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
