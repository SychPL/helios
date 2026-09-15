package pl.mateusz.helios;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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
    // Dashboard model: last good layout, its latest snapshot, frozen visibility and liveness.
    private DashboardSpec spec;
    private JSONObject specRaw;
    private Map<String,EntityStates.Entity> states=new HashMap<>();
    private final Map<String,Boolean> visibility=new HashMap<>();
    private boolean live;
    private String connectionIssue="Łączenie z konfiguracją ekranu w HA…",configIssue;
    private final Set<String> pendingActions=new HashSet<>();
    private Dialog panel;
    private TextView panelTitle;
    private DashboardSpec.Item panelItem;
    private AssistClient voice;
    private boolean resumed,busy,recording,pendingVoice;
    private final Runnable tick=new Runnable(){public void run(){
        Date now=new Date();
        String date=new SimpleDateFormat("EEEE, d MMMM",new Locale("pl","PL")).format(now);
        dashboard.clock(new SimpleDateFormat("HH:mm",Locale.ROOT).format(now),date.substring(0,1).toUpperCase(new Locale("pl"))+date.substring(1));
        if(resumed)main.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        dashboard=new DashboardView(this);setContentView(dashboard);
        navigation=new NavigationMenu(this,()->config,this::manualTalk,()->{if(voice!=null)voice.cancel();});
        dashboard.onBrandHold(()->navigation.show());
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{config=new JSONObject(saved);}catch(Exception ignored){}
        String cached=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_v2",null);
        if(cached!=null)try{specRaw=new JSONObject(cached);spec=DashboardSpec.parse(specRaw);}catch(Exception ignored){specRaw=null;}
        if(spec==null){spec=DashboardSpec.fallback();configIssue=DashboardSpec.VERSION_ERROR;}
        dashboard.setSpec(spec,this::tap);renderDashboard();
        if(config==null)connect();
    }
    @Override public void onResume(){super.onResume();resumed=true;tick.run();startDashboard();if(pendingVoice){pendingVoice=false;startVoice();}else startWake();dashboard.post(()->onEvent("dashboard_visible","width="+dashboard.getWidth()+" height="+dashboard.getHeight()));}
    @Override public void onPause(){resumed=false;stopDashboard();stopWake();main.removeCallbacks(tick);if(voice!=null)voice.cancel();super.onPause();}
    @Override public void onDestroy(){if(navigation!=null)navigation.close();closePanel();stopWake();if(voice!=null)voice.cancel();audio.shutdown();network.shutdownNow();diagnostics.shutdown();super.onDestroy();}
    private void manualTalk(){
        if(config==null){connect();return;}
        if(busy){if(recording)voice.finishSpeech();else voice.cancel();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);return;}
        startVoice();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){if(request==1){if(resumed)startVoice();else pendingVoice=true;}else startWake();}
        else dashboard.setMessage("Zezwól na mikrofon, aby rozmawiać.");
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
        dashboard.setMessage("Łączenie z Twoim Home Assistantem…");
        network.execute(()->{
            try{
                if(BuildConfig.PROVISION_URL.isEmpty())throw new IOException("No pairing configuration");
                JSONObject received=get(BuildConfig.PROVISION_URL,null);
                if(received.getString("token").isEmpty()||received.getString("pipeline").isEmpty())throw new IOException("Incomplete pairing");
                new URI(received.getString("url"));
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",received.toString()).apply();
                main.post(()->{config=received;dashboard.setMessage("");onEvent("configured","Helios "+BuildConfig.VERSION_NAME);startWake();startDashboard();});
            }catch(Exception error){main.post(()->{dashboard.connected(false);dashboard.setMessage("Uruchom parowanie na komputerze,\na potem spróbuj ponownie.");});}
        });
    }

    // --- dashboard ---
    private void renderDashboard(){
        String issue=connectionIssue!=null?connectionIssue:configIssue;
        dashboard.setIssue(issue);navigation.status(issue==null?"HA: połączono, dane aktualne":issue);
        dashboard.render(states,visibility,live);
        if(panelTitle!=null&&panelItem!=null)panelTitle.setText(coverTitle(panelItem));
    }
    private String coverTitle(DashboardSpec.Item item){
        EntityStates.Entity e=states.get(item.entity);String position=e==null||!e.known()?null:e.attribute("current_position");
        return dashboardLabel(item)+(position==null?"":" · "+position.replaceAll("\\.0+$","")+"%");
    }
    private void decideVisibility(){
        for(DashboardSpec.Item item:spec.items)if(item.conditional())visibility.put(item.id,item.visible(states.get(item.visibleEntity)));
    }
    private void stopDashboard(){
        dashboardGeneration++;
        if(liveDashboard!=null){liveDashboard.stop();liveDashboard=null;}
        live=false;
    }
    private void startDashboard(){
        if(config==null||!resumed||liveDashboard!=null)return;
        final int generation=++dashboardGeneration;
        live=false;connectionIssue="Łączenie z konfiguracją ekranu w HA…";renderDashboard();
        HaDashboardClient next=new HaDashboardClient(config,specRaw,new HaDashboardClient.Listener(){
            private void update(Runnable action){main.post(()->{if(resumed&&liveDashboard!=null&&generation==dashboardGeneration)action.run();});}
            @Override public void onDashboard(JSONObject raw,DashboardSpec received,Map<String,EntityStates.Entity> snapshot,String issue){
                update(()->{
                    if(received!=null&&(specRaw==null||!raw.toString().equals(specRaw.toString()))){
                        spec=received;specRaw=raw;visibility.clear();closePanel();dashboard.setSpec(spec,MainActivity.this::tap);
                        onEvent("dashboard_configured","items="+spec.items.size());
                    }
                    if(received!=null&&issue==null)getSharedPreferences("helios",MODE_PRIVATE).edit().putString("dashboard_v2",raw.toString()).apply();
                    if(received==null&&specRaw==null){spec=DashboardSpec.fallback();dashboard.setSpec(spec,MainActivity.this::tap);}
                    configIssue=issue;connectionIssue=null;states=snapshot;live=true;decideVisibility();dashboard.connected(true);renderDashboard();
                });
            }
            @Override public void onStates(Map<String,EntityStates.Entity> snapshot){update(()->{states=snapshot;live=true;decideVisibility();renderDashboard();});}
            @Override public void onUnavailable(String reason){update(()->{live=false;connectionIssue=reason;dashboard.connected(false);closePanel();renderDashboard();});}
        });
        liveDashboard=next;next.start();
    }
    private void tap(DashboardSpec.Item item){
        if(!live||liveDashboard==null){Toast.makeText(this,"Brak połączenia z Home Assistant",Toast.LENGTH_SHORT).show();return;}
        switch(item.type){
            case "light":confirm(item,"Przełączyć: "+dashboardLabel(item)+"?",()->call(item,"light","toggle"));break;
            case "garage":confirm(item,"Zamknąć bramę?",()->call(item,"cover","close_cover"));break;
            case "cover":coverPanel(item);break;
            default:break;
        }
    }
    private String dashboardLabel(DashboardSpec.Item item){return item.title!=null?item.title:item.entity;}
    /** Confirmation is only ever a gate; cancel, outside touch and connection loss all close it without sending. */
    private void confirm(DashboardSpec.Item item,String fallbackText,Runnable action){
        if(!item.confirm){action.run();return;}
        closePanel();
        panel=new AlertDialog.Builder(this).setMessage(item.confirmText!=null?item.confirmText:fallbackText)
            .setPositiveButton("Potwierdź",(d,w)->{panel=null;if(live)action.run();else Toast.makeText(this,"Brak połączenia z Home Assistant",Toast.LENGTH_SHORT).show();})
            .setNegativeButton("Anuluj",(d,w)->panel=null).setOnCancelListener(d->panel=null).create();
        panel.setCanceledOnTouchOutside(true);panel.show();
    }
    private void coverPanel(DashboardSpec.Item item){
        closePanel();
        Dialog dialog=new Dialog(this);panel=dialog;panelItem=item;
        float density=getResources().getDisplayMetrics().density;int pad=Math.round(16*density);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(pad,pad,pad,pad);column.setBackgroundColor(0xFF242C25);
        panelTitle=new TextView(this);panelTitle.setText(coverTitle(item));panelTitle.setTextSize(20);panelTitle.setTextColor(0xFFF1EFE6);panelTitle.setPadding(0,0,0,pad);column.addView(panelTitle);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);column.addView(row);
        // ponytail: stop is never confirmed and never blocked by another pending action, so a moving cover can always be halted.
        panelButton(row,"▲","Otwórz",item,"open_cover",true);
        panelButton(row,"■","Zatrzymaj",item,"stop_cover",false);
        panelButton(row,"▼","Zamknij",item,"close_cover",true);
        Button close=new Button(this);close.setText("Zamknij panel");close.setAllCaps(false);close.setOnClickListener(v->closePanel());
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-1,Math.round(54*density));c.topMargin=pad;column.addView(close,c);
        dialog.setContentView(column);dialog.setCanceledOnTouchOutside(true);dialog.setOnCancelListener(d->{panel=null;panelTitle=null;panelItem=null;});
        if(dialog.getWindow()!=null)dialog.getWindow().setGravity(Gravity.CENTER);
        dialog.show();
    }
    private void panelButton(LinearLayout parent,String symbol,String label,DashboardSpec.Item item,String service,boolean gated){
        Button b=new Button(this);b.setText(symbol);b.setContentDescription(label);b.setTextSize(28);
        b.setEnabled(!pendingActions.contains(item.id+":"+service));
        float density=getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Math.round(96*density),Math.round(80*density));p.rightMargin=Math.round(8*density);parent.addView(b,p);
        b.setOnClickListener(v->{
            if(gated&&item.confirm){closePanel();confirm(item,label+": "+dashboardLabel(item)+"?",()->call(item,"cover",service,null));return;}
            b.setEnabled(false);
            if(!call(item,"cover",service,()->b.setEnabled(true)))b.setEnabled(true);
        });
    }
    private void call(DashboardSpec.Item item,String domain,String service){call(item,domain,service,null);}
    private boolean anyPending(String id){for(String key:pendingActions)if(key.startsWith(id+":"))return true;return false;}
    private boolean call(DashboardSpec.Item item,String domain,String service,Runnable done){
        String key=item.id+":"+service;
        if(pendingActions.contains(key)||liveDashboard==null)return false;
        pendingActions.add(key);dashboard.pending(item.id,true);onEvent("service_call",domain+"."+service+" "+item.entity);
        liveDashboard.callService(domain,service,item.entity,error->main.post(()->{
            pendingActions.remove(key);dashboard.pending(item.id,anyPending(item.id));
            if(done!=null)done.run();
            if(error!=null){onEvent("service_error",error);Toast.makeText(this,"Nie wykonano: "+error,Toast.LENGTH_LONG).show();}
        }));
        return true;
    }
    private void closePanel(){if(panel!=null){panel.dismiss();panel=null;}panelTitle=null;panelItem=null;}

    // --- voice ---
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
            dashboard.setMessage("Mikrofon wymaga zgody. Przytrzymaj HELIOS → Rozmowa.");
            if(!getSharedPreferences("helios",MODE_PRIVATE).getBoolean("microphone_requested",false)){
                getSharedPreferences("helios",MODE_PRIVATE).edit().putBoolean("microphone_requested",true).apply();
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2);
            }
            return;
        }
        final WakeWordListener current=new WakeWordListener();wakeListener=current;
        dashboard.setMessage("Uruchamiam nasłuch Okay Nabu…");
        audio.execute(()->{
            boolean detected=false;String failure=null;
            try{detected=current.listen(this,()->{
                onEvent("wake_listening","Okay Nabu; local audio only");
                main.post(()->{if(wakeListener==current&&resumed&&!busy)dashboard.setMessage("");});
            });}catch(Exception|LinkageError error){failure=error.toString();}
            final boolean found=detected;final String error=failure;
            onEvent("wake_microphone_released",found?"detected":"stopped");
            main.post(()->{
                if(wakeListener!=current)return;wakeListener=null;
                if(error!=null){onEvent("wake_error",error);dashboard.setMessage("Nasłuch hasła niedostępny.\nPrzytrzymaj HELIOS → Rozmowa.");}
                else if(found&&resumed&&!busy){onEvent("wake_detected","Okay Nabu");startVoice();}
            });
        });
    }
    @Override public void onState(String text){main.post(()->{if(!isDestroyed())dashboard.setMessage(text);});}
    @Override public void onEvent(String event,String detail){
        main.post(()->{
            if(event.equals("microphone_started")){recording=true;}
            if(event.equals("microphone_released")){recording=false;}
        });
        try{
            JSONObject row=new JSONObject().put("time_ms",System.currentTimeMillis()).put("event",event).put("detail",detail);
            String encoded=row.toString();
            try(FileOutputStream file=openFileOutput("assist-events.jsonl",MODE_APPEND)){file.write((encoded+"\n").getBytes("UTF-8"));}
            String endpoint=config==null?"":config.optString("diagnostics_url","");
            if(!endpoint.isEmpty()&&!diagnostics.isShutdown())diagnostics.execute(()->{
                HttpURLConnection c=null;
                try{c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(1000);c.setReadTimeout(1000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(encoded.getBytes("UTF-8"));}c.getResponseCode();}
                catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            });
        }catch(Exception ignored){}
    }
}
