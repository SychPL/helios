package pl.mateusz.helios;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements AssistClient.Listener {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService diagnostics=Executors.newSingleThreadExecutor();
    private final ExecutorService audio=Executors.newSingleThreadExecutor();
    private WakeWordListener wakeListener;
    private DashboardView dashboard;
    private NavigationMenu navigation;
    private JSONObject config;
    private HaDashboardClient liveDashboard;
    private int dashboardGeneration;
    private DashboardSpec dashboardSpec;
    private Map<String,String> indicatorStates=new HashMap<>();
    private boolean indicatorsLive;
    private String dashboardIssue="Łączenie z konfiguracją ekranu w HA…";
    private AssistClient voice;
    private boolean resumed,busy,recording,pendingVoice;
    private long weatherReadAt;
    private final Runnable tick=new Runnable(){public void run(){
        Date now=new Date();dashboard.time.setText(new SimpleDateFormat("HH:mm",Locale.ROOT).format(now));
        String date=new SimpleDateFormat("EEEE, d MMMM",new Locale("pl","PL")).format(now);
        dashboard.date.setText(date.substring(0,1).toUpperCase(new Locale("pl"))+date.substring(1));
        if(resumed)main.postDelayed(this,1000);
    }};
    private final Runnable weatherTick=new Runnable(){public void run(){refreshWeather();if(resumed)main.postDelayed(this,120000);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        dashboard=new DashboardView(this);setContentView(dashboard);
        navigation=new NavigationMenu(this,()->config,this::manualTalk,()->{if(voice!=null)voice.cancel();});
        dashboard.onBrandHold(()->navigation.show());
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{config=new JSONObject(saved);}catch(Exception ignored){}
        String display=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_spec",null);
        if(display!=null)try{dashboardSpec=DashboardSpec.parse(new JSONObject(display));}catch(Exception ignored){}
        renderIndicators();
        String menu=getSharedPreferences("helios",MODE_PRIVATE).getString("menu_spec",null);
        if(menu!=null)try{navigation.configure(MenuSpec.parse(new JSONObject(menu)));}catch(Exception ignored){}
        if(config==null)connect();
    }
    @Override public void onResume(){super.onResume();resumed=true;tick.run();weatherTick.run();startDashboard();if(pendingVoice){pendingVoice=false;startVoice();}else startWake();dashboard.post(()->onEvent("dashboard_visible","width="+dashboard.getWidth()+" height="+dashboard.getHeight()));}
    @Override public void onPause(){resumed=false;stopDashboard();stopWake();main.removeCallbacks(tick);main.removeCallbacks(weatherTick);if(voice!=null)voice.cancel();super.onPause();}
    @Override public void onDestroy(){if(navigation!=null)navigation.close();stopWake();if(voice!=null)voice.cancel();audio.shutdown();network.shutdownNow();diagnostics.shutdown();super.onDestroy();}
    private void manualTalk(){
        if(config==null){connect();return;}
        if(busy){if(recording)voice.finishSpeech();else voice.cancel();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);return;}
        startVoice();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){if(request==1){if(resumed)startVoice();else pendingVoice=true;}else startWake();}
        else dashboard.message.setText("Zezwól na mikrofon, aby rozmawiać.");
    }
    private static JSONObject get(String url,String token) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
        if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
        try{
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1){if(bytes.size()+n>65536)throw new IOException("Response too large");bytes.write(chunk,0,n);}
                return new JSONObject(bytes.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
    private void connect(){
        dashboard.message.setText("Łączenie z Twoim Home Assistantem…");
        network.execute(()->{
            try{
                if(BuildConfig.PROVISION_URL.isEmpty())throw new IOException("No pairing configuration");
                JSONObject received=get(BuildConfig.PROVISION_URL,null);
                if(received.getString("token").isEmpty()||received.getString("pipeline").isEmpty())throw new IOException("Incomplete pairing");
                new URI(received.getString("url"));
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",received.toString()).apply();
                main.post(()->{config=received;dashboard.message.setText("");onEvent("configured","Helios "+BuildConfig.VERSION_NAME);refreshWeather();startWake();startDashboard();});
            }catch(Exception error){main.post(()->{dashboard.connected(false);dashboard.message.setText("Uruchom parowanie na komputerze,\na potem spróbuj ponownie.");});}
        });
    }
    private void refreshWeather(){
        if(config==null||isFinishing())return;final JSONObject current=config;
        final String weather=dashboardSpec==null?current.optString("weather_entity",""):dashboardSpec.weather;
        dashboard.showWeather(weather!=null&&!weather.isEmpty());
        if(weather==null||weather.isEmpty())return;
        network.execute(()->{
            try{
                JSONObject response=get(current.getString("url").replaceAll("/$","")+"/api/states/"+weather,current.getString("token"));
                JSONObject a=response.getJSONObject("attributes");String state=response.getString("state");
                if(state.equals("unavailable")||state.equals("unknown")||!a.has("temperature"))throw new IOException("Weather unavailable");
                double temp=a.getDouble("temperature");String unit=a.optString("temperature_unit","°C");
                String wind=a.has("wind_speed")?String.format(new Locale("pl"),"Wiatr %.0f %s",a.getDouble("wind_speed"),a.optString("wind_speed_unit","")):"";
                main.post(()->{
                    if(dashboardSpec!=null&&!weather.equals(dashboardSpec.weather))return;
                    weatherReadAt=System.currentTimeMillis();
                    dashboard.temperature.setText(String.format(new Locale("pl"),"%.0f%s",temp,unit));dashboard.condition.setText(WeatherLabels.polish(state));dashboard.weatherDetail.setText(wind);
                    dashboard.freshness.setText("Odczyt "+new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(weatherReadAt)));
                    onEvent("weather_updated","state="+state+" temperature="+temp+unit);
                });
            }catch(Exception error){main.post(()->{dashboard.freshness.setText(weatherReadAt==0?"Pogoda niedostępna":"Ostatni odczyt "+new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(weatherReadAt)));});}
        });
    }
    private void renderIndicators(){dashboard.updateIndicators(dashboardSpec,indicatorStates,indicatorsLive,dashboardIssue);}
    private void stopDashboard(){
        dashboardGeneration++;
        if(liveDashboard!=null){liveDashboard.stop();liveDashboard=null;}
        indicatorsLive=false;indicatorStates.clear();
    }
    private void startDashboard(){
        if(config==null||!resumed||liveDashboard!=null)return;
        final int generation=++dashboardGeneration;
        indicatorsLive=false;dashboardIssue=dashboardSpec==null?"Łączenie z konfiguracją ekranu w HA…":null;renderIndicators();
        HaDashboardClient next=new HaDashboardClient(config,new HaDashboardClient.Listener(){
            private void update(Runnable action){main.post(()->{if(resumed&&liveDashboard!=null&&generation==dashboardGeneration)action.run();});}
            @Override public void onConfig(JSONObject raw,DashboardSpec spec){
                update(()->{
                    dashboardSpec=spec;indicatorsLive=false;indicatorStates.clear();dashboardIssue=null;
                    getSharedPreferences("helios",MODE_PRIVATE).edit().putString("dashboard_spec",raw.toString()).apply();
                    renderIndicators();refreshWeather();onEvent("dashboard_configured","indicators="+spec.indicators.size());
                });
            }
            @Override public void onMenu(JSONObject raw,MenuSpec menu){update(()->{
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("menu_spec",raw.toString()).apply();navigation.configure(menu);
            });}
            @Override public void onMenuError(String reason){update(()->navigation.error(reason));}
            @Override public void onStates(Map<String,String> states){update(()->{indicatorStates=states;indicatorsLive=true;dashboardIssue=null;dashboard.connected(true);renderIndicators();});}
            @Override public void onUnavailable(String reason){update(()->{indicatorsLive=false;indicatorStates.clear();dashboardIssue=reason;dashboard.connected(false);renderIndicators();});}
        });
        liveDashboard=next;next.start();
    }
    private void startVoice(){
        if(busy||config==null||!resumed)return;busy=true;stopWake();recording=false;
        voice=new AssistClient(this,config,this);
        final AssistClient current=voice;
        audio.execute(()->{try{current.run();}finally{main.post(()->{busy=false;recording=false;if(isDestroyed())return;main.postDelayed(this::startWake,1000);});}});
    }
    private void stopWake(){if(wakeListener!=null){wakeListener.stop();wakeListener=null;}}
    private void startWake(){
        if(!resumed||isDestroyed()||busy||config==null||wakeListener!=null)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            dashboard.message.setText("Mikrofon wymaga zgody. Przytrzymaj HELIOS → Rozmowa.");
            if(!getSharedPreferences("helios",MODE_PRIVATE).getBoolean("microphone_requested",false)){
                getSharedPreferences("helios",MODE_PRIVATE).edit().putBoolean("microphone_requested",true).apply();
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2);
            }
            return;
        }
        final WakeWordListener current=new WakeWordListener();wakeListener=current;
        dashboard.message.setText("Uruchamiam nasłuch Okay Nabu…");
        audio.execute(()->{
            boolean detected=false;String failure=null;
            try{detected=current.listen(this,()->{
                onEvent("wake_listening","Okay Nabu; local audio only");
                main.post(()->{if(wakeListener==current&&resumed&&!busy)dashboard.message.setText("");});
            });}catch(Exception|LinkageError error){failure=error.toString();}
            final boolean found=detected;final String error=failure;
            onEvent("wake_microphone_released",found?"detected":"stopped");
            main.post(()->{
                if(wakeListener!=current)return;wakeListener=null;
                if(error!=null){onEvent("wake_error",error);dashboard.message.setText("Nasłuch hasła niedostępny.\nPrzytrzymaj HELIOS → Rozmowa.");}
                else if(found&&resumed&&!busy){onEvent("wake_detected","Okay Nabu");startVoice();}
            });
        });
    }
    @Override public void onState(String text){main.post(()->{if(!isDestroyed())dashboard.message.setText(text);});}
    @Override public void onEvent(String event,String detail){
        main.post(()->{
            if(event.equals("microphone_started")){recording=true;}
            if(event.equals("microphone_released")){recording=false;}
        });
        try{
            JSONObject row=new JSONObject().put("time_ms",System.currentTimeMillis()).put("event",event).put("detail",detail);
            String encoded=row.toString();
            try(FileOutputStream file=openFileOutput("assist-events.jsonl",MODE_APPEND)){file.write((encoded+"\n").getBytes("UTF-8"));}
            String endpoint=config.optString("diagnostics_url","");
            if(!endpoint.isEmpty()&&!diagnostics.isShutdown())diagnostics.execute(()->{
                HttpURLConnection c=null;
                try{c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(1000);c.setReadTimeout(1000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(encoded.getBytes("UTF-8"));}c.getResponseCode();}
                catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            });
        }catch(Exception ignored){}
    }
}
