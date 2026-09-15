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
    private boolean wakeEnabled;
    private DashboardView dashboard;
    private NavigationMenu navigation;
    private float edgeX,edgeY;
    private boolean edgeGesture;
    private JSONObject config;
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
        navigation=new NavigationMenu(this);
        dashboard.menu.setOnClickListener(v->navigation.show());
        dashboard.onBrandHold(()->navigation.show());
        wakeEnabled=getSharedPreferences("helios",MODE_PRIVATE).getBoolean("wake_enabled",true);
        dashboard.wake.setChecked(wakeEnabled);
        dashboard.wake.setOnCheckedChangeListener((button,enabled)->{
            wakeEnabled=enabled;
            getSharedPreferences("helios",MODE_PRIVATE).edit().putBoolean("wake_enabled",enabled).apply();
            if(enabled){
                if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2);
                else startWake();
            }else{stopWake();if(!busy)dashboard.message.setText("Naciśnij, aby porozmawiać.\nNasłuch hasła wyłączony.");}
        });
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{config=new JSONObject(saved);}catch(Exception ignored){}
        dashboard.talk.setOnClickListener(v->{
            if(config==null){connect();return;}
            if(busy){if(recording)voice.finishSpeech();else voice.cancel();return;}
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);return;}
            startVoice();
        });
        if(config==null)connect();
    }
    @Override public void onResume(){super.onResume();resumed=true;tick.run();weatherTick.run();if(pendingVoice){pendingVoice=false;startVoice();}else startWake();dashboard.post(()->onEvent("dashboard_visible","width="+dashboard.getWidth()+" height="+dashboard.getHeight()));}
    @Override public void onPause(){resumed=false;stopWake();main.removeCallbacks(tick);main.removeCallbacks(weatherTick);if(voice!=null)voice.cancel();super.onPause();}
    @Override public void onDestroy(){if(navigation!=null)navigation.close();stopWake();if(voice!=null)voice.cancel();audio.shutdown();network.shutdownNow();diagnostics.shutdown();super.onDestroy();}
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        float scale=getResources().getDisplayMetrics().density;
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){
            edgeX=event.getX();edgeY=event.getY();edgeGesture=edgeX>=getWindow().getDecorView().getWidth()-32*scale;
        }else if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&edgeGesture){
            if(Math.abs(event.getY()-edgeY)>48*scale)edgeGesture=false;
            else if(edgeX-event.getX()>64*scale){
                edgeGesture=false;
                android.view.MotionEvent cancel=android.view.MotionEvent.obtain(event);cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();
                navigation.show();return true;
            }
        }else if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP||event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)edgeGesture=false;
        return super.dispatchTouchEvent(event);
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
        dashboard.message.setText("Łączenie z Twoim Home Assistantem…");dashboard.talk.setEnabled(false);
        network.execute(()->{
            try{
                if(BuildConfig.PROVISION_URL.isEmpty())throw new IOException("No pairing configuration");
                JSONObject received=get(BuildConfig.PROVISION_URL,null);
                if(received.getString("token").isEmpty()||received.getString("pipeline").isEmpty())throw new IOException("Incomplete pairing");
                new URI(received.getString("url"));
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",received.toString()).apply();
                main.post(()->{config=received;dashboard.talk.setEnabled(true);dashboard.talk.setText("Porozmawiaj");dashboard.message.setText("Naciśnij, aby porozmawiać.");onEvent("configured","Helios "+BuildConfig.VERSION_NAME);refreshWeather();startWake();});
            }catch(Exception error){main.post(()->{dashboard.connected(false);dashboard.talk.setText("Połącz ponownie");dashboard.talk.setEnabled(true);dashboard.message.setText("Uruchom parowanie na komputerze,\na potem spróbuj ponownie.");});}
        });
    }
    private void refreshWeather(){
        if(config==null||isFinishing())return;final JSONObject current=config;
        network.execute(()->{
            try{
                JSONObject response=get(current.getString("url").replaceAll("/$","")+"/api/states/"+current.optString("weather_entity","weather.forecast_dom"),current.getString("token"));
                JSONObject a=response.getJSONObject("attributes");String state=response.getString("state");
                if(state.equals("unavailable")||state.equals("unknown")||!a.has("temperature"))throw new IOException("Weather unavailable");
                double temp=a.getDouble("temperature");String unit=a.optString("temperature_unit","°C");
                String wind=a.has("wind_speed")?String.format(new Locale("pl"),"Wiatr %.0f %s",a.getDouble("wind_speed"),a.optString("wind_speed_unit","")):"";
                main.post(()->{
                    weatherReadAt=System.currentTimeMillis();dashboard.connected(true);
                    dashboard.temperature.setText(String.format(new Locale("pl"),"%.0f%s",temp,unit));dashboard.condition.setText(WeatherLabels.polish(state));dashboard.weatherDetail.setText(wind);
                    dashboard.freshness.setText("Odczyt "+new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(weatherReadAt)));
                    onEvent("weather_updated","state="+state+" temperature="+temp+unit);
                });
            }catch(Exception error){main.post(()->{dashboard.connected(false);dashboard.freshness.setText(weatherReadAt==0?"Pogoda niedostępna":"Ostatni odczyt "+new SimpleDateFormat("HH:mm",Locale.ROOT).format(new Date(weatherReadAt)));});}
        });
    }
    private void startVoice(){
        if(busy||config==null||!resumed)return;busy=true;stopWake();recording=false;dashboard.talk.setText("Anuluj");
        voice=new AssistClient(this,config,this);
        final AssistClient current=voice;
        audio.execute(()->{try{current.run();}finally{main.post(()->{busy=false;recording=false;if(isDestroyed())return;dashboard.talk.setText("Porozmawiaj");main.postDelayed(this::startWake,1000);});}});
    }
    private void stopWake(){if(wakeListener!=null){wakeListener.stop();wakeListener=null;}}
    private void startWake(){
        if(!resumed||isDestroyed()||busy||!wakeEnabled||config==null||wakeListener!=null)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            dashboard.message.setText("Naciśnij Porozmawiaj, aby zezwolić na mikrofon.");return;
        }
        final WakeWordListener current=new WakeWordListener();wakeListener=current;
        dashboard.message.setText("Uruchamiam nasłuch Okay Nabu…");
        audio.execute(()->{
            boolean detected=false;String failure=null;
            try{detected=current.listen(this,()->{
                onEvent("wake_listening","Okay Nabu; local audio only");
                main.post(()->{if(wakeListener==current&&resumed&&!busy)dashboard.message.setText("Powiedz „Okay Nabu”.\nNasłuch lokalny jest włączony.");});
            });}catch(Exception|LinkageError error){failure=error.toString();}
            final boolean found=detected;final String error=failure;
            onEvent("wake_microphone_released",found?"detected":"stopped");
            main.post(()->{
                if(wakeListener!=current)return;wakeListener=null;
                if(error!=null){onEvent("wake_error",error);dashboard.message.setText("Nasłuch hasła niedostępny.\nMożesz użyć przycisku Porozmawiaj.");}
                else if(found&&resumed&&wakeEnabled&&!busy){onEvent("wake_detected","Okay Nabu");startVoice();}
            });
        });
    }
    @Override public void onState(String text){main.post(()->{if(!isDestroyed())dashboard.message.setText(text);});}
    @Override public void onEvent(String event,String detail){
        main.post(()->{
            if(event.equals("microphone_started")){recording=true;dashboard.talk.setText("Zakończ mowę");}
            if(event.equals("microphone_released")){recording=false;dashboard.talk.setText("Anuluj");}
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
