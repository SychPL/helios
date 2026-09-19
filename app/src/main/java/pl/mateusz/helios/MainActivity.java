package pl.mateusz.helios;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
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
    static{System.setProperty("http.keepAlive","false");} // before the first HTTP request in the process: OkHttp reads it once when its pool is created
    private final Handler main=new Handler(Looper.getMainLooper());
    /** A touch lifts the screen brightness by 20 points for 15 s (dark room: the panel is hard to read at the night level); dialogs inherit the boost from the tap that opened them. */
    private final Runnable unboost=()->{WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;getWindow().setAttributes(p);};
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(event.getAction()==android.view.MotionEvent.ACTION_DOWN)boostBrightness();
        return super.dispatchTouchEvent(event);
    }
    private void boostBrightness(){
        float base;
        try{base=android.provider.Settings.System.getInt(getContentResolver(),android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f;}catch(Exception e){base=.5f;}
        WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=Math.min(1f,base+.2f);getWindow().setAttributes(p);
        main.removeCallbacks(unboost);main.postDelayed(unboost,15_000);
    }
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService diagnostics=Executors.newSingleThreadExecutor();
    private final ExecutorService audio=Executors.newSingleThreadExecutor();
    private WakeWordListener wakeListener;
    private DashboardView dashboard;
    private NavigationMenu navigation;
    private JSONObject config;
    private HeliosService service;
    private HaDashboardClient attachedTo;
    private int attachGeneration;
    // Dashboard model: last good layout, its latest snapshot, frozen visibility and liveness.
    private DashboardSpec spec;
    private JSONObject specRaw;
    private Map<String,EntityStates.Entity> states=new HashMap<>();
    private final Map<String,Boolean> visibility=new HashMap<>();
    private boolean live;
    private String connectionIssue="Łączenie z konfiguracją ekranu w HA…",configIssue;
    private final Set<String> pendingActions=new HashSet<>();
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            service=((HeliosService.Local)binder).service();
            service.setOnDeviceLost(()->{if(voice!=null)voice.cancelFollowUp();});
            service.setDiagnostics(MainActivity.this::onEvent);
            service.setAppearanceListener((appearance,background)->{dashboard.setBackdrop(background,appearance.image?appearance.dim:0);dashboard.applyTheme();});
            service.setOnDeviceChanged(id->{if(pairing&&id!=null){pairing=false;dashboard.setMessage("Sparowano z HA");main.postDelayed(()->{if(!isDestroyed())dashboard.setMessage("");},4000);}});
            service.setOnConnectionChanged(()->{config=service.connection();detachHa();if(resumed)attachHa();}); // diagnostics_url and the HA client follow every persisted change
            service.setOnAuthInvalid(()->dashboard.setMessage("HA odrzucił token - sparuj ponownie"));
            service.setOnChannelIssue(text->dashboard.setMessage(text));
            service.setOnStatus(text->dashboard.setMessage(text));
            service.setMusicListener(snapshot->{
                dashboard.musicInfo(snapshot.remoteInfo);
                if(library!=null&&library.isShowing())dashboard.musicOverlay().closePanel();
                dashboard.musicOverlay().setSnapshot(snapshot);
            });
            dashboard.musicOverlay().setActions(new MusicOverlay.Actions(){
                // errors reach the panel through the snapshot issue (no toasts over the night screen)
                public void command(String command){service.musicCommand(command,error->{});}
                public void seek(int seconds){service.musicSeek(seconds,error->{});}
                public void mute(boolean muted){service.musicMute(muted,error->{});}
            });
            if(resumed)attachHa();
        }
        @Override public void onServiceDisconnected(ComponentName name){if(service!=null)service.setMusicListener(null);service=null;attachedTo=null;}
    };
    private final HaDashboardClient.Listener haListener=new HaDashboardClient.Listener(){
        private void update(Runnable action){final int generation=attachGeneration;main.post(()->{if(resumed&&attachedTo!=null&&generation==attachGeneration)action.run();});}
        @Override public void onDashboard(JSONObject raw,DashboardSpec received,Map<String,EntityStates.Entity> snapshot,String issue){
            update(()->{
                if(received!=null&&(specRaw==null||!raw.toString().equals(specRaw.toString()))){
                    spec=received;specRaw=raw;visibility.clear();closePanel();dashboard.setSpec(spec,MainActivity.this::tap);
                    onEvent("dashboard_configured","items="+spec.items.size());
                }
                if(received==null&&specRaw==null){spec=DashboardSpec.fallback();dashboard.setSpec(spec,MainActivity.this::tap);}
                configIssue=issue;connectionIssue=null;states=snapshot;live=true;decideVisibility();dashboard.connected(true);renderDashboard();
            });
        }
        @Override public void onStates(Map<String,EntityStates.Entity> snapshot){update(()->{states=snapshot;live=true;decideVisibility();renderDashboard();});}
        @Override public void onUnavailable(String reason){update(()->{live=false;connectionIssue=reason;dashboard.connected(false);closePanel();renderDashboard();});}
    };
    private Dialog panel;
    private MusicLibraryDialog library;
    private TextView panelTitle;
    private DashboardSpec.Item panelItem;
    private Runnable panelRefresh; // re-renders the open cover panel from the latest states (both rows, button availability)
    private AssistClient voice;
    private boolean resumed,busy,recording,pendingVoice,pairing;
    private final Runnable tick=new Runnable(){public void run(){
        Date now=new Date();
        String weekday=new SimpleDateFormat("EEEE",new Locale("pl","PL")).format(now),date=new SimpleDateFormat("d MMMM",new Locale("pl","PL")).format(now);
        dashboard.clock(new SimpleDateFormat("HH:mm",Locale.ROOT).format(now),weekday.substring(0,1).toUpperCase(new Locale("pl"))+weekday.substring(1),date);
        if(resumed)main.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        dashboard=new DashboardView(this);setContentView(dashboard);
        // a killed process can leave an update file behind; the one still being handed over stays
        ApkProvider.sweep(this,ToolsBridge.pendingOpId(this));
        // and an operation we never saw the answer to is picked up again, without waiting for the menu
        if(ToolsBridge.pendingOpId(this)!=null)main.postDelayed(toolsPoll,3000);
        navigation=new NavigationMenu(this,()->config,()->service!=null&&service.updater().busy(),new NavigationMenu.Actions(){
            public void talk(){manualTalk();}
            public void cancel(){if(voice!=null)voice.cancel();}
            public void pair(){onboarding(false);}
            public void device(){deviceDialog();}
            public void update(){if(service!=null)service.update();}
            public void tools(){toolsDialog();}
        });
        dashboard.onBrandHold(()->navigation.show());
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{config=new JSONObject(saved);}catch(Exception ignored){}
        String cached=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_v2",null);
        if(cached!=null)try{specRaw=new JSONObject(cached);spec=DashboardSpec.parse(specRaw);}catch(Exception ignored){specRaw=null;}
        if(spec==null){spec=DashboardSpec.fallback();configIssue=DashboardSpec.VERSION_ERROR;}
        dashboard.setSpec(spec,this::tap);renderDashboard();
        Intent intent=new Intent(this,HeliosService.class);
        startForegroundService(intent);bindService(intent,serviceConnection,Context.BIND_AUTO_CREATE);
        if(config==null||config.optBoolean("auth_invalid",false))onboarding(false);
        else if(config.optInt("protocol",1)<2)onboarding(true); // paired before 0.10 with a human account's token: works, but asks for the new pairing at every start
    }
    /** Back on the music full screen returns to the panel (SPEC 0.11 pkt 3.2); elsewhere the launcher behaviour stays. */
    @Override public void onBackPressed(){
        MusicOverlay overlay=dashboard.musicOverlay();
        if(overlay.closeFullscreen())return;
        if(overlay.isOpen()){overlay.closePanel();return;}
        super.onBackPressed();
    }
    @Override public void onResume(){super.onResume();resumed=true;tick.run();attachHa();if(pendingVoice){pendingVoice=false;startVoice();}else startWake();dashboard.post(()->onEvent("dashboard_visible","width="+dashboard.getWidth()+" height="+dashboard.getHeight()+" free_mb="+getFilesDir().getUsableSpace()/1048576+" log_kb="+new java.io.File(getFilesDir(),"assist-events.jsonl").length()/1024));}
    @Override public void onPause(){resumed=false;detachHa();stopWake();main.removeCallbacks(tick);if(voice!=null)voice.cancel();super.onPause();}
    @Override public void onDestroy(){if(navigation!=null)navigation.close();closePanel();closeOnboarding();if(library!=null)library.close();if(service!=null)service.setMusicListener(null);detachHa();try{unbindService(serviceConnection);}catch(IllegalArgumentException ignored){}stopWake();if(voice!=null)voice.cancel();audio.shutdown();network.shutdownNow();diagnostics.shutdown();super.onDestroy();}
    private void manualTalk(){
        if(config==null||config.optBoolean("auth_invalid",false)){connect();return;}
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
    private void connect(){onboarding(false);}
    private HaDashboardClient ha(){return service==null?null:service.ha();}
    private void attachHa(){
        HaDashboardClient client=ha();
        if(client==null||client==attachedTo||!resumed)return;
        detachHa();attachGeneration++;attachedTo=client;client.attach(haListener);
    }
    private void detachHa(){if(attachedTo!=null){attachedTo.detach(haListener);attachedTo=null;attachGeneration++;}live=false;}

    // --- dashboard ---
    private void renderDashboard(){
        String issue=connectionIssue!=null?connectionIssue:configIssue;
        dashboard.setIssue(issue);navigation.status(issue==null?"HA: połączono, dane aktualne":issue);
        dashboard.render(states,visibility,live);
        if(panelRefresh!=null)panelRefresh.run();
    }
    private String coverTitle(DashboardSpec.Item item){
        EntityStates.Entity e=states.get(item.entity);String position=e==null||!e.known()?null:e.attribute("current_position");
        return dashboardLabel(item)+(position==null?"":" · "+position.replaceAll("\\.0+$","")+"%");
    }
    private void decideVisibility(){
        for(DashboardSpec.Item item:spec.items)if(item.conditional())visibility.put(item.id,item.visible(states.get(item.visibleEntity)));
    }
    private void tap(DashboardSpec.Item item){
        if(item.type.equals("music")){openMusicLibrary();return;} // library and remote control depend on MA, not on HA
        if(!live||ha()==null){Toast.makeText(this,"Brak połączenia z Home Assistant",Toast.LENGTH_SHORT).show();return;}
        EntityStates.Entity e=item.entity==null?null:states.get(item.entity);
        boolean known=e!=null&&e.known(); // unknown/unavailable: the tile is visible but inactive, nothing is sent (SPEC 0.9 pkt 5)
        switch(item.type){
            case "light":if(known)confirm(item,"Przełączyć: "+dashboardLabel(item)+"?",()->call(item,"light","toggle"));break;
            case "garage":if(known)confirm(item,"Zamknąć bramę?",()->call(item,"cover","close_cover"));break;
            case "cover":if(known)coverPanel(item);break;
            case "cover_group":coverGroupPanel(item);break;
            case "entity":if(item.offEntity!=null&&known)confirm(item,"Zgasić światła?",()->callEntity(item,item.offEntity,"light","turn_off",null));break;
            default:break;
        }
    }
    private void openMusicLibrary(){
        if(service==null||!service.musicConfigured()){Toast.makeText(this,"Music Assistant nie jest skonfigurowany (Odśwież parowanie)",Toast.LENGTH_LONG).show();return;}
        closePanel();dashboard.musicOverlay().closePanel();
        library=new MusicLibraryDialog(this,service);library.show();
    }
    private String dashboardLabel(DashboardSpec.Item item){return item.title!=null?item.title:item.entity;}
    /** Confirmation is only ever a gate; cancel, outside touch and connection loss all close it without sending. */
    private void confirm(DashboardSpec.Item item,String fallbackText,Runnable action){
        if(!item.confirm){action.run();return;}
        closePanel();
        confirmDialog(item.confirmText!=null?item.confirmText:fallbackText,"Potwierdź",()->{if(live)action.run();else Toast.makeText(this,"Brak połączenia z Home Assistant",Toast.LENGTH_SHORT).show();},()->{});
    }
    /** Palette confirmation with finger-sized buttons; cancel, outside touch and dismiss all run onCancel without sending anything. */
    private void confirmDialog(String message,String okLabel,Runnable onOk,Runnable onCancel){
        Dialog dialog=new Dialog(this);panel=dialog;dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout column=Theme.dialogColumn(this,20);column.setMinimumWidth(Theme.dp(this,320));
        TextView text=Theme.label(this,message,18,false);text.setPadding(0,0,0,Theme.dp(this,16));column.addView(text);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);column.addView(row);
        Button cancel=Theme.button(this,"Anuluj",false,Theme.dp(this,17),Theme.dp(this,Theme.RADIUS));Button ok=Theme.button(this,okLabel,true,Theme.dp(this,17),Theme.dp(this,Theme.RADIUS));
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,Theme.dp(this,56),1);a.rightMargin=Theme.dp(this,8);row.addView(cancel,a);row.addView(ok,new LinearLayout.LayoutParams(0,Theme.dp(this,56),1));
        cancel.setOnClickListener(v->dialog.cancel());ok.setOnClickListener(v->{panel=null;dialog.setOnCancelListener(null);dialog.dismiss();onOk.run();});
        dialog.setContentView(column);dialog.setCanceledOnTouchOutside(true);dialog.setOnCancelListener(d->{panel=null;onCancel.run();});
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
    }
    private void coverPanel(DashboardSpec.Item item){
        closePanel();
        Dialog dialog=new Dialog(this);panel=dialog;panelItem=item;dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        int pad=Theme.dp(this,16);
        LinearLayout column=Theme.dialogColumn(this,16);
        panelTitle=Theme.label(this,coverTitle(item),20,false);panelTitle.setPadding(0,0,0,pad);column.addView(panelTitle);
        final TextView titleView=panelTitle;panelRefresh=()->titleView.setText(coverTitle(item));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);column.addView(row);
        // ponytail: stop is never confirmed and never blocked by another pending action, so a moving cover can always be halted.
        panelButton(row,"▲","Otwórz",item,"open_cover",true);
        panelButton(row,"■","Zatrzymaj",item,"stop_cover",false);
        panelButton(row,"▼","Zamknij",item,"close_cover",true);
        Button close=Theme.button(this,"Zamknij panel",false,Theme.dp(this,17),Theme.dp(this,Theme.RADIUS));close.setOnClickListener(v->closePanel());
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-1,Theme.dp(this,56));c.topMargin=pad;column.addView(close,c);
        dialog.setContentView(column);dialog.setCanceledOnTouchOutside(true);dialog.setOnCancelListener(d->{panel=null;panelTitle=null;panelItem=null;});
        if(dialog.getWindow()!=null){dialog.getWindow().setGravity(Gravity.CENTER);dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));}
        dialog.show();
    }
    private void panelButton(LinearLayout parent,String symbol,String label,DashboardSpec.Item item,String service,boolean gated){
        Button b=Theme.button(this,symbol,service.equals("stop_cover"),Theme.dp(this,28),Theme.dp(this,Theme.RADIUS));b.setContentDescription(label);
        b.setEnabled(!pendingActions.contains(item.id+":"+item.entity+":"+service));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Theme.dp(this,96),Theme.dp(this,80));p.rightMargin=Theme.dp(this,8);parent.addView(b,p);
        b.setOnClickListener(v->{
            if(gated&&item.confirm){closePanel();confirm(item,label+": "+dashboardLabel(item)+"?",()->call(item,"cover",service,null));return;}
            b.setEnabled(false);
            if(!call(item,"cover",service,()->b.setEnabled(true)))b.setEnabled(true);
        });
    }
    private void call(DashboardSpec.Item item,String domain,String service){call(item,domain,service,null);}
    private boolean anyPending(String id){for(String key:pendingActions)if(key.startsWith(id+":"))return true;return false;}
    private boolean call(DashboardSpec.Item item,String domain,String service,Runnable done){return callEntity(item,item.entity,domain,service,done);}
    /** One service call per entity; open/close are tracked per `id:entity:service` (A never blocks B), stop is never blocked and never queued (SPEC 0.9 pkt 4.2). */
    private boolean callEntity(DashboardSpec.Item item,String entity,String domain,String service,Runnable done){
        boolean tracked=!service.equals("stop_cover");
        String key=item.id+":"+entity+":"+service;
        HaDashboardClient client=ha();
        if(client==null||(tracked&&pendingActions.contains(key)))return false;
        if(tracked){pendingActions.add(key);dashboard.pending(item.id,true);}
        onEvent("service_call",domain+"."+service+" "+entity);
        client.callService(domain,service,entity,error->main.post(()->{
            if(tracked){pendingActions.remove(key);dashboard.pending(item.id,anyPending(item.id));}
            if(done!=null)done.run();
            if(error!=null){onEvent("service_error",error);Toast.makeText(this,"Nie wykonano: "+error,Toast.LENGTH_LONG).show();}
            if(panelRefresh!=null)panelRefresh.run();
        }));
        return true;
    }
    /** "Rolety sypialni": two independent rows (name, state, open/stop/close) sized in 800x480 units (SPEC 0.9 pkt 4.1); music panel folds, playback continues. */
    private void coverGroupPanel(DashboardSpec.Item item){
        closePanel();dashboard.musicOverlay().closePanel();
        float s=Math.min(getResources().getDisplayMetrics().widthPixels/800f,getResources().getDisplayMetrics().heightPixels/480f);
        Theme t=Theme.current();
        FrameLayout root=new FrameLayout(this);root.setBackground(Theme.card(t.surface,Theme.RADIUS*s));
        TextView header=Theme.label(this,dashboardLabel(item),0,false);header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,22*s);header.setGravity(Gravity.CENTER_VERTICAL);header.setMaxLines(1);header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(header,box(CoverPanelGeometry.HEADER,s));
        final TextView[] stateViews=new TextView[2];final MusicOverlay.IconButton[][] buttons=new MusicOverlay.IconButton[2][3];
        final String[] services={"open_cover","stop_cover","close_cover"};final int[] bits={CoverText.OPEN,CoverText.STOP,CoverText.CLOSE};
        final String[] glyphs={"arrow-up","stop","arrow-down"};final String[] verbs={"Otwórz","Zatrzymaj","Zamknij"};
        for(int n=0;n<2;n++){
            final DashboardSpec.Cover cover=item.covers.get(n);
            FrameLayout row=new FrameLayout(this);root.addView(row,box(n==0?CoverPanelGeometry.ROW_A:CoverPanelGeometry.ROW_B,s));
            LinearLayout label=new LinearLayout(this);label.setOrientation(LinearLayout.VERTICAL);label.setGravity(Gravity.CENTER_VERTICAL);row.addView(label,box(CoverPanelGeometry.LABEL,s));
            TextView name=Theme.label(this,cover.title,0,false);name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,20*s);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);label.addView(name);
            TextView state=Theme.label(this,"",0,true);state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,17*s);state.setMaxLines(1);label.addView(state);stateViews[n]=state;
            for(int k=0;k<3;k++){
                final String service=services[k];
                MusicOverlay.IconButton b=new MusicOverlay.IconButton(this,glyphs[k],verbs[k]+" "+cover.title);
                b.style(k==1?t.accent:t.raised,k==1?t.onColor(t.accent):t.text,s);
                row.addView(b,box(k==0?CoverPanelGeometry.OPEN:k==1?CoverPanelGeometry.STOP:CoverPanelGeometry.CLOSE,s));
                b.setOnClickListener(v->{if(callEntity(item,cover.entity,"cover",service,null)&&panelRefresh!=null)panelRefresh.run();});
                buttons[n][k]=b;
            }
        }
        Button back=Theme.button(this,"Wróć",false,17*s,Theme.RADIUS*s);back.setOnClickListener(v->closePanel());root.addView(back,box(CoverPanelGeometry.BACK,s));
        panelRefresh=()->{
            for(int n=0;n<2;n++){
                DashboardSpec.Cover cover=item.covers.get(n);EntityStates.Entity e=states.get(cover.entity);
                stateViews[n].setText(CoverText.state(e));
                for(int k=0;k<3;k++){
                    boolean pending=k!=1&&pendingActions.contains(item.id+":"+cover.entity+":"+services[k]);
                    buttons[n][k].setEnabled(live&&CoverText.has(e,bits[k])&&!pending);
                }
            }
        };
        panelRefresh.run();
        Dialog dialog=new Dialog(this);panel=dialog;panelItem=item;dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(root,new android.view.ViewGroup.LayoutParams(Math.round(CoverPanelGeometry.PANEL.w*s),Math.round(CoverPanelGeometry.PANEL.h*s)));
        dialog.setCanceledOnTouchOutside(true);dialog.setOnCancelListener(d->closePanel());
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));dialog.getWindow().setGravity(Gravity.CENTER);}
        dialog.show();
    }
    private static FrameLayout.LayoutParams box(OverlayGeometry.Box b,float s){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.round(b.w*s),Math.round(b.h*s));p.leftMargin=Math.round(b.x*s);p.topMargin=Math.round(b.y*s);return p;}
    private void closePanel(){if(panel!=null){Dialog d=panel;panel=null;d.setOnCancelListener(null);d.dismiss();}panelTitle=null;panelItem=null;panelRefresh=null;}

    // --- voice ---
    private void startVoice(){
        if(busy||config==null||!resumed)return;busy=true;stopWake();recording=false;
        voice=new AssistClient(this,config,()->service==null?null:service.deviceId(),this);
        final AssistClient current=voice;
        audio.execute(()->{try{current.run();}finally{main.post(()->{busy=false;recording=false;if(isDestroyed())return;main.postDelayed(this::startWake,1000);});}});
    }
    private void stopWake(){if(wakeListener!=null){wakeListener.stop();wakeListener=null;}}
    private void startWake(){
        if(!resumed||isDestroyed()||busy||config==null||config.optBoolean("auth_invalid",false)||wakeListener!=null)return; // SPEC 0.10 pkt 8.2: no socket to HA at all while the token is refused - the assist pipeline included
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
            },level->onEvent("wake_level",level));}catch(Exception|LinkageError error){failure=error.toString();}
            final boolean found=detected;final String error=failure;
            onEvent("wake_microphone_released",found?"detected":"stopped");
            main.post(()->{
                if(wakeListener!=current)return;wakeListener=null;
                if(error!=null){onEvent("wake_error",error);dashboard.setMessage("Nasłuch hasła niedostępny.\nPrzytrzymaj HELIOS → Rozmowa.");}
                else if(found&&resumed&&!busy){onEvent("wake_detected","Okay Nabu");startVoice();}
            });
        });
    }
    /** Maps AssistClient events onto the telemetry voice state; null leaves the state unchanged. */
    static String voiceState(String event){
        switch(event){
            case "microphone_started":return "listening";
            case "microphone_released":return "processing";
            case "playback_started":return "responding";
            case "test_error":return "error";
            case "ready":case "wake_listening":return "idle";
            default:return null;
        }
    }
    // --- device screens: no IME dependency, every dialog has a visible cancel ---
    private Dialog onboardingDialog;private HaDiscovery discovery;
    private float units(){return Math.min(getResources().getDisplayMetrics().widthPixels/800f,getResources().getDisplayMetrics().heightPixels/480f);} // 800x480 screen units, not dp: a dp keypad overflowed the 480 px height on the clock
    private static void place(android.widget.FrameLayout root,View v,OverlayGeometry.Box b,float s){
        android.widget.FrameLayout.LayoutParams p=new android.widget.FrameLayout.LayoutParams(Math.round(b.w*s),Math.round(b.h*s));p.leftMargin=Math.round(b.x*s);p.topMargin=Math.round(b.y*s);root.addView(v,p);
    }
    /**
     * SPEC 0.10 pkt 3: choose HA (mDNS list or a typed address) → instruction → probe → code → POST. Own dialog, never touched by
     * closePanel()/onUnavailable(); closed only by the user (while unlocked) or by a successful pairing. allowLater = a legacy connection keeps working meanwhile.
     */
    private void onboarding(boolean allowLater){
        if(onboardingDialog!=null&&onboardingDialog.isShowing())return;
        closePanel();
        final float s=units();
        android.widget.FrameLayout root=new android.widget.FrameLayout(this);root.setBackground(Theme.card(Theme.current().surface,Theme.RADIUS*s));
        Dialog dialog=new Dialog(this);onboardingDialog=dialog;dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(root,new android.view.ViewGroup.LayoutParams(Math.round(800*s),Math.round(480*s)));
        dialog.setCanceledOnTouchOutside(false);dialog.setCancelable(false);
        dialog.setOnDismissListener(d->{if(discovery!=null){discovery.stop();discovery=null;}if(onboardingDialog==dialog)onboardingDialog=null;});
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));dialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
        showList(root,s,allowLater);
        dialog.show();
    }
    private void closeOnboarding(){Dialog d=onboardingDialog;onboardingDialog=null;if(d!=null)d.dismiss();}
    private TextView onboardingText(String text,float px,boolean muted){TextView v=new TextView(this);v.setText(text);v.setTextColor(muted?Theme.current().muted:Theme.current().text);v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px);return v;}
    private void showList(android.widget.FrameLayout root,float s,boolean allowLater){
        root.removeAllViews();if(discovery!=null){discovery.stop();discovery=null;}
        place(root,onboardingText("Wybierz Home Assistant",26*s,false),OnboardingGeometry.TITLE,s);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);place(root,list,OnboardingGeometry.LIST,s);
        TextView status=onboardingText("Szukam HA w sieci…",15*s,true);place(root,status,OnboardingGeometry.STATUS,s);
        Button manual=Theme.button(this,"Wpisz adres",true,18*s,Theme.RADIUS*s);place(root,manual,OnboardingGeometry.MANUAL,s);manual.setOnClickListener(v->showManual(root,s,allowLater));
        if(allowLater){Button later=Theme.button(this,"Później",false,18*s,Theme.RADIUS*s);place(root,later,OnboardingGeometry.LATER,s);later.setOnClickListener(v->closeOnboarding());}
        else if(config!=null){Button cancel=Theme.button(this,"Anuluj",false,18*s,Theme.RADIUS*s);place(root,cancel,OnboardingGeometry.CANCEL,s);cancel.setOnClickListener(v->closeOnboarding());}
        final boolean[] any={false};
        discovery=new HaDiscovery(this,servers->{
            if(onboardingDialog==null||list.getParent()==null)return;
            list.removeAllViews();any[0]=!servers.isEmpty();
            if(any[0])status.setText(servers.size()==1?"Znaleziono 1 serwer":"Znaleziono "+servers.size()+" serwery - wybierz świadomie");
            for(HaDiscovery.Server server:servers){
                Button row=Theme.button(this,server.name+"\n"+server.host+":"+server.port+(server.version.isEmpty()?"":" · "+server.version),false,16*s,Theme.RADIUS*s);
                row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.setPadding(Math.round(12*s),0,Math.round(12*s),0);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Math.round((OnboardingGeometry.ROW_H-6)*s));p.bottomMargin=Math.round(6*s);list.addView(row,p);
                row.setOnClickListener(v->chooseServer(root,s,allowLater,server.url()));
            }
        });
        discovery.start();
        main.postDelayed(()->{if(onboardingDialog!=null&&status.getParent()!=null&&!any[0])status.setText("Nie znaleziono HA w sieci - wpisz adres");},10000);
    }
    private void chooseServer(android.widget.FrameLayout root,float s,boolean allowLater,String url){
        if(discovery!=null){discovery.stop();discovery=null;}
        if(config!=null&&config.optInt("protocol",1)>=2&&!config.optBoolean("auth_invalid",false)){
            confirmDialog("Zegar jest sparowany z "+config.optString("url","")+". Nowe parowanie zastąpi to połączenie; stary wpis usuń w HA.","Dalej",()->showInstruction(root,s,allowLater,url,null),()->showList(root,s,allowLater));
            return;
        }
        showInstruction(root,s,allowLater,url,null);
    }
    private void showManual(android.widget.FrameLayout root,float s,boolean allowLater){
        root.removeAllViews();if(discovery!=null){discovery.stop();discovery=null;}
        place(root,onboardingText("Adres Home Assistant (np. 192.168.1.20 lub 192.168.1.20:8123)",18*s,true),OnboardingGeometry.TITLE,s);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView typed=onboardingText("",28*s,false);typed.setGravity(Gravity.CENTER);column.addView(typed,new LinearLayout.LayoutParams(-1,Math.round(44*s)));
        String[][] keys={{"1","2","3","."},{"4","5","6",":"},{"7","8","9","⌫"},{"0","OK"}};
        for(String[] rowKeys:keys){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);column.addView(row);
            for(String key:rowKeys){
                Button b=Theme.button(this,key,key.equals("OK"),22*s,Theme.RADIUS*s);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Math.round((key.equals("OK")?174:84)*s),Math.round(58*s));p.rightMargin=Math.round(6*s);p.bottomMargin=Math.round(6*s);row.addView(b,p);
                b.setOnClickListener(v->{
                    String current=typed.getText().toString();
                    if(key.equals("⌫")){if(!current.isEmpty())typed.setText(current.substring(0,current.length()-1));}
                    else if(key.equals("OK")){String url=AddressInput.parse(current);if(url==null)dashboard.setMessage("Nieprawidłowy adres");else showInstruction(root,s,allowLater,url,null);}
                    else if(current.length()<64)typed.setText(current+key);
                });
            }
        }
        place(root,column,new OverlayGeometry.Box(20,64,440,400),s);
        Button back=Theme.button(this,"Wróć",false,18*s,Theme.RADIUS*s);place(root,back,OnboardingGeometry.CANCEL,s);back.setOnClickListener(v->showList(root,s,allowLater));
    }
    private void showInstruction(android.widget.FrameLayout root,float s,boolean allowLater,String url,String error){
        root.removeAllViews();
        place(root,onboardingText(url,18*s,true),OnboardingGeometry.TITLE,s);
        TextView text=onboardingText(error!=null?error:"W HA: Ustawienia → Integracje → Dodaj → Helios. Gdy zobaczysz kod, dotknij Dalej.",22*s,false);place(root,text,new OverlayGeometry.Box(20,64,440,360),s);
        Button next=Theme.button(this,error!=null?"Ponów":"Dalej",true,18*s,Theme.RADIUS*s);place(root,next,OnboardingGeometry.MANUAL,s);
        Button back=Theme.button(this,"Wróć",false,18*s,Theme.RADIUS*s);place(root,back,OnboardingGeometry.CANCEL,s);back.setOnClickListener(v->showList(root,s,allowLater));
        next.setOnClickListener(v->{
            next.setEnabled(false);text.setText("Sprawdzam "+url+"…");
            network.execute(()->{int status=PairingClient.probe(url);main.post(()->{
                if(onboardingDialog==null||root.getParent()==null)return;
                if(status==200)showCode(root,s,allowLater,url);
                else showInstruction(root,s,allowLater,url,status==404?"HA pod tym adresem nie ma integracji Helios ≥ 0.8 albo kreator nie jest otwarty. Zainstaluj ją z HACS, otwórz Dodaj → Helios i dotknij Ponów.":"HA nie odpowiada pod tym adresem.");
            });});
        });
    }
    private void showCode(android.widget.FrameLayout root,float s,boolean allowLater,String url){
        root.removeAllViews();
        place(root,onboardingText("Kod z HA: Ustawienia → Integracje → Dodaj → Helios",18*s,true),OnboardingGeometry.TITLE,s);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView code=onboardingText("",30*s,false);code.setGravity(Gravity.CENTER);code.setLetterSpacing(.3f);column.addView(code,new LinearLayout.LayoutParams(-1,Math.round(44*s)));
        final java.util.List<Button> keys=new java.util.ArrayList<>();
        String[][] layout={{"1","2","3"},{"4","5","6"},{"7","8","9"},{"⌫","0","OK"}};
        Button cancel=Theme.button(this,"Anuluj",false,18*s,Theme.RADIUS*s);
        final Runnable[] lock=new Runnable[2];
        lock[0]=()->{for(Button b:keys)b.setEnabled(false);cancel.setEnabled(false);}; // from POST to answer: the HA-side transaction cannot be undone from the clock
        lock[1]=()->{for(Button b:keys)b.setEnabled(true);cancel.setEnabled(true);code.setText("");}; // a refused code is cleared: the next six digits start fresh
        for(String[] rowKeys:layout){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);column.addView(row);
            for(String key:rowKeys){
                Button b=Theme.button(this,key,key.equals("OK"),22*s,Theme.RADIUS*s);keys.add(b);
                b.setContentDescription(key.equals("⌫")?"Usuń ostatnią cyfrę":key.equals("OK")?"Zatwierdź kod":"Cyfra "+key);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(Math.round(84*s),Math.round(58*s));p.rightMargin=Math.round(6*s);p.bottomMargin=Math.round(6*s);row.addView(b,p);
                b.setOnClickListener(v->{
                    String current=code.getText().toString();
                    if(key.equals("⌫")){if(!current.isEmpty())code.setText(current.substring(0,current.length()-1));}
                    else if(key.equals("OK")){if(current.length()==6){lock[0].run();startPairing(url,current,lock[1]);}}
                    else if(current.length()<6)code.setText(current+key);
                });
            }
        }
        place(root,column,new OverlayGeometry.Box(20,64,440,400),s);
        place(root,cancel,OnboardingGeometry.CANCEL,s);cancel.setOnClickListener(v->showList(root,s,allowLater));
    }
    /** unlock runs on every error path; a success closes the window. The service persists a 200 even if this activity is gone meanwhile. */
    private void startPairing(String url,String code,Runnable unlock){
        if(service==null){dashboard.setMessage("Serwis Heliosa jeszcze startuje - spróbuj za chwilę");unlock.run();return;}
        dashboard.setMessage("Paruję z HA…");
        service.pair(url,code,error->{
            if(isDestroyed())return;
            if(error!=null){
                dashboard.setMessage(error);config=service.connection();unlock.run();
                if(error.startsWith("Nie udało się zapisać")&&onboardingDialog!=null){android.widget.FrameLayout root=(android.widget.FrameLayout)onboardingDialog.findViewById(android.R.id.content);if(root!=null&&root.getChildCount()>0)showList((android.widget.FrameLayout)root.getChildAt(0),units(),false);} // terminal save failure only: a fresh pairing from the list; a wrong code keeps the keypad
                return;
            }
            pairing=true;closeOnboarding();config=service.connection();onEvent("configured","Helios "+BuildConfig.VERSION_NAME);startWake();attachHa();
            main.postDelayed(()->{if(pairing){pairing=false;dashboard.setMessage("HA nie potwierdził połączenia");}},20000);
        });
    }

    private void toast(String text){
        if(text!=null&&!text.isEmpty())Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
    }

    // -- clock tools (SPEC 0.12) -----------------------------------------------------------------------------

    private String toolsSnapshot="{}";
    private boolean toolsMenuPending;

    /** The menu of what the tools can do here; every entry has its own condition, so nothing is offered in vain. */
    /**
     * Asks about whatever operation is pending when it fires, not about the one that was pending when scheduled.
     *
     * <p>It stops once the machine stage has been running longer than its limit (SPEC 0.12 pkt 4.2). The record
     * stays, so opening the menu asks once more; what stops is the five second loop, not the reconciliation.
     */
    private final Runnable toolsPoll=new Runnable(){public void run(){
        String waiting=ToolsBridge.pendingOpId(MainActivity.this);
        if(waiting==null||isFinishing())return;
        long age=ToolsCall.stageAgeMs(toolsSnapshot,null);
        if(age>=0&&age>ToolsCall.limitFor(ToolsBridge.pendingOp(MainActivity.this),
                ToolsCall.stageOf(toolsSnapshot,waiting))){
            return;
        }
        ToolsBridge.ask(MainActivity.this,waiting);
    }};

    private void toolsDialog(){toolsDialog(true);}

    /**
     * The tools menu. Every entry comes from a snapshot, so the snapshot is read again each time the menu opens:
     * root may have been switched on from the tool itself, and a stale answer would hide half the entries.
     */
    private void toolsDialog(boolean refresh){
        closePanel();
        if(refresh&&ToolsTrust.mayCall(ToolsBridge.status(this))){
            toolsMenuPending=true;
            if(ToolsBridge.ask(this,ToolsBridge.pendingOpId(this))!=null)return;
            toolsMenuPending=false;
        }
        ToolsState state=ToolsBridge.stateFrom(this,toolsSnapshot);
        java.util.List<String> items=ToolsMenu.items(state);
        LinearLayout column=Theme.dialogColumn(this,16);
        column.addView(Theme.label(this,"Narzędzia zegara",20,false));
        String pending=ToolsBridge.pendingOpId(this);
        if(pending!=null)column.addView(Theme.label(this,"Trwa: "+ToolsBridge.pendingOp(this),13,true));
        String explain=ToolsTrust.explain(state.trust);
        if(!explain.isEmpty())column.addView(Theme.label(this,explain,13,true));
        for(String item:items){
            Button button=Theme.button(this,item,false,Theme.dp(this,17),Theme.dp(this,Theme.RADIUS));
            button.setOnClickListener(v->{closePanel();toolsAction(item);});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Theme.dp(this,52));p.topMargin=Theme.dp(this,8);
            column.addView(button,p);
        }
        Button close=Theme.button(this,"Zamknij",false,Theme.dp(this,17),Theme.dp(this,Theme.RADIUS));
        close.setOnClickListener(v->closePanel());
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-1,Theme.dp(this,52));c.topMargin=Theme.dp(this,12);
        column.addView(close,c);
        Dialog dialog=new Dialog(this);panel=dialog;dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(column);dialog.setCanceledOnTouchOutside(true);dialog.setOnCancelListener(d->panel=null);
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
    }

    private void toolsAction(String item){
        if(ToolsMenu.INSTALL.equals(item)||ToolsMenu.UPDATE.equals(item)){
            if(service==null){toast("Usługa Heliosa jeszcze nie działa");return;}
            ToolsTrust.Installed tool=ToolsBridge.installed(this);
            // the installed version of the tool decides, not the version of Helios
            service.install(ReleaseInfo.TOOLS,tool==null?"":ToolsBridge.installedVersionName(this));
            toast("Pobieram narzędzia zegara…");
            return;
        }
        if(ToolsMenu.ACCEPT.equals(item)){acceptTools();return;}
        if(ToolsMenu.SILENT_UPDATE.equals(item)){silentUpdate();return;}
        String op=null,args="";
        if(ToolsMenu.ROOT_AND_ADB.equals(item))op="root_adb_on";
        else if(ToolsMenu.ADB_ON.equals(item))op="adb_on";
        else if(ToolsMenu.ADB_OFF.equals(item))op="adb_off";
        else if(ToolsMenu.MIC_FIX.equals(item))op="mic_release";
        else if(ToolsMenu.MIC_RESTORE.equals(item))op="mic_restore";
        else if(ToolsMenu.ALLOW_BRIGHTNESS.equals(item))op="write_settings";
        else if(ToolsMenu.SET_HOME.equals(item))op="set_home";
        else if(ToolsMenu.PERMISSION_MIC.equals(item)){op="grant_permission";args=ToolsBridge.grantArgs("android.permission.RECORD_AUDIO");}
        if(op==null)return;
        if(!ToolsCall.mayStartAnother(ToolsBridge.pendingOpId(this),ToolsCall.stageOf(toolsSnapshot,ToolsBridge.pendingOpId(this)))){toast("Poprzednia operacja jeszcze trwa");return;}
        if(ToolsBridge.start(this,op,args,null)==null)toast(ToolsTrust.explain(ToolsBridge.status(this)));
    }

    /** Installing the tool is not the same as trusting it, so the user says so here, once. */

    /** Downloads our own update and lets the tools install it, so no installer dialog appears on the clock. */
    private void silentUpdate(){
        if(service==null){toast("Usługa Heliosa jeszcze nie działa");return;}
        if(!ToolsTrust.mayCall(ToolsBridge.status(this))){toast(ToolsTrust.explain(ToolsBridge.status(this)));return;}
        if(!ToolsCall.mayStartAnother(ToolsBridge.pendingOpId(this),ToolsCall.stageOf(toolsSnapshot,ToolsBridge.pendingOpId(this)))){
            toast("Poprzednia operacja jeszcze trwa");return;
        }
        toast("Szukam nowej wersji…");
        service.fetchForBridge(file->main.post(()->{
            if(file==null)return;                                  // the updater already said why
            if(isFinishing()){file.delete();return;}               // nobody left to hand the result to
            // the world may have moved while we were downloading: another operation started, or trust withdrawn
            if(!ToolsTrust.mayCall(ToolsBridge.status(this))
                    ||!ToolsCall.mayStartAnother(ToolsBridge.pendingOpId(this),
                            ToolsCall.stageOf(toolsSnapshot,ToolsBridge.pendingOpId(this)))){
                file.delete();toast("Poprzednia operacja jeszcze trwa");return;
            }
            // a file per hand-off: the tools may still be copying the previous one
            String opId=ToolsCall.newOpId();
            java.io.File shared=ApkProvider.shared(this,opId);
            if(!moveInto(file,shared)){shared.delete();toast("Nie udało się przygotować pliku");return;}
            android.net.Uri uri=ApkProvider.uriFor(this,opId);
            if(uri==null){shared.delete();toast("Nie udało się przygotować pliku");return;}
            if(!ToolsBridge.startWithFile(this,"install_apk","",opId,uri)){
                shared.delete();
                toast("Nie udało się uruchomić narzędzi");
            }
        }));
    }

    private boolean moveInto(java.io.File from,java.io.File to){
        try(java.io.InputStream in=new java.io.FileInputStream(from);java.io.OutputStream out=new java.io.FileOutputStream(to)){
            byte[] chunk=new byte[64*1024];int n;
            while((n=in.read(chunk))!=-1)out.write(chunk,0,n);
        }catch(java.io.IOException e){return false;}
        finally{
            //noinspection ResultOfMethodCallIgnored
            from.delete();
        }
        return true;
    }

    private void acceptTools(){
        ToolsTrust.Installed tool=ToolsBridge.installed(this);
        if(tool==null){toast("Narzędzia nie są zainstalowane");return;}
        String fingerprint=tool.fingerprint.length()<=16?tool.fingerprint:tool.fingerprint.substring(0,16)+"...";
        confirmDialog("Pozwolić narzędziom zegara nadawać uprawnienia Heliosowi?\n\nPodpis "+fingerprint,
                "Pozwól",()->{
                    if(ToolsBridge.accept(this,tool.fingerprint))toast("Narzędzia zaakceptowane");
                    else toast("Narzędzia zmieniły się w międzyczasie, spróbuj ponownie");
                },()->{});
    }

    /** Every answer is reconciled against the tool's own record, never trusted on its own. */
    /**
     * Every answer from the tools, reconciled rather than believed (SPEC 0.12 pkt 4.2).
     *
     * <p>Two kinds of answer arrive here: the operation we started, and the state queries that ask what became of
     * it. They have different identifiers on purpose, so what settles the pending record is either a verdict on
     * that operation or a terminal stage reported about that same operation, never a stage belonging to another.
     */
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=ToolsBridge.REQUEST_CODE)return;
        boolean cancelled=resultCode!=RESULT_OK;
        String answeredFor=ToolsBridge.opIdOf(data);
        String waitingFor=ToolsBridge.pendingOpId(this);
        String snapshot=ToolsBridge.snapshotOf(data);
        if(!cancelled&&snapshot.length()>2)toolsSnapshot=snapshot;
        String result=ToolsBridge.statusOf(data);
        String detail=ToolsBridge.detailOf(data);
        if(!cancelled&&result!=null&&!detail.isEmpty())toast(detail);

        boolean settled=false;
        if(ToolsCall.acceptResult(answeredFor,waitingFor)&&!cancelled&&result!=null
                &&ToolsCall.next(result,false)==ToolsCall.Next.DONE){
            ToolsBridge.forget(this,waitingFor);                 // the operation itself came back with a verdict
            settled=true;
        }else if(waitingFor!=null){
            String stage=ToolsCall.stageOf(toolsSnapshot,waitingFor);   // only a stage about our own request counts
            ToolsBridge.observe(this,waitingFor,stage);
            settled=ToolsCall.terminal(stage);
        }

        if(ToolsBridge.pendingOpId(this)!=null){
            main.removeCallbacks(toolsPoll);                     // one poll at a time, whatever answered last
            main.postDelayed(toolsPoll,5000);
        }else if(settled){
            ToolsBridge.ask(this,null);                          // one reconciliation once the operation is over
        }
        if(toolsMenuPending){toolsMenuPending=false;main.post(()->toolsDialog(false));}
    }

    private void deviceDialog(){
        if(service==null)return;
        closePanel();
        float d=getResources().getDisplayMetrics().density;int pad=Math.round(12*d);
        DockController dock=service.dock();DeviceVolume volume=service.volume();
        LinearLayout column=Theme.dialogColumn(this,16);column.setMinimumWidth(Math.round(360*d));
        TextView volumeLabel=Theme.label(this,"Głośność urządzenia: "+volume.percent()+"%",16,false);column.addView(volumeLabel);
        android.widget.SeekBar volumeBar=new android.widget.SeekBar(this);volumeBar.setMax(100);volumeBar.setProgress(volume.percent());Theme.tint(volumeBar);column.addView(volumeBar,new LinearLayout.LayoutParams(-1,Math.round(48*d)));
        volumeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar s,int p,boolean u){volumeLabel.setText("Głośność urządzenia: "+p+"%");}
            public void onStartTrackingTouch(android.widget.SeekBar s){}
            public void onStopTrackingTouch(android.widget.SeekBar s){int applied=volume.set(s.getProgress());volumeLabel.setText("Głośność urządzenia: "+applied+"%");service.publish();}
        });
        boolean lampAvailable=dock.unavailable()==null&&!Boolean.FALSE.equals(dock.dockConnected());
        TextView lampLabel=Theme.label(this,"",16,false);lampLabel.setPadding(0,pad,0,0);
        lampLabel.setText(lampAvailable?"Lampka docka":"Lampka docka: "+(dock.unavailable()!=null?dock.unavailable():"dock odłączony"));column.addView(lampLabel);
        android.widget.Switch lamp=new android.widget.Switch(this);lamp.setText("Włączona");lamp.setTextColor(Theme.current().text);lamp.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.current().accent));lamp.setChecked(Boolean.TRUE.equals(dock.ledOn()));lamp.setEnabled(lampAvailable);column.addView(lamp,new LinearLayout.LayoutParams(-1,Math.round(48*d)));
        TextView brightLabel=Theme.label(this,"Jasność: "+(dock.ledBrightness()==null?"—":dock.ledBrightness()+"/10"),16,false);column.addView(brightLabel);
        android.widget.SeekBar bright=new android.widget.SeekBar(this);bright.setMax(9);bright.setProgress(dock.ledBrightness()==null?6:dock.ledBrightness()-1);bright.setEnabled(lampAvailable);Theme.tint(bright);column.addView(bright,new LinearLayout.LayoutParams(-1,Math.round(48*d)));
        lamp.setOnCheckedChangeListener((b,on)->network.execute(()->{try{if(on)dock.turnOn();else dock.turnOff();}catch(Exception e){main.post(()->Toast.makeText(this,"Lampka: "+e.getClass().getSimpleName(),Toast.LENGTH_SHORT).show());}}));
        bright.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar s,int p,boolean u){brightLabel.setText("Jasność: "+(p+1)+"/10");}
            public void onStartTrackingTouch(android.widget.SeekBar s){}
            public void onStopTrackingTouch(android.widget.SeekBar s){int level=s.getProgress()+1;network.execute(()->{try{dock.setBrightness(level);if(lamp.isChecked())dock.turnOn();}catch(Exception e){main.post(()->Toast.makeText(MainActivity.this,"Lampka: "+e.getClass().getSimpleName(),Toast.LENGTH_SHORT).show());}});}
        });
        Button close=Theme.button(this,"Zamknij",false,Theme.dp(this,17),Theme.dp(this,Theme.RADIUS));close.setOnClickListener(v->closePanel());
        LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,Math.round(56*d));cl.topMargin=pad;column.addView(close,cl);
        Dialog dialog=new Dialog(this);panel=dialog;dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);dialog.setContentView(column);dialog.setCanceledOnTouchOutside(true);dialog.setOnCancelListener(x->panel=null);
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
    }
    @Override public void onState(String text){main.post(()->{if(!isDestroyed())dashboard.setMessage(text);});}
    @Override public void onEvent(String event,String detail){
        main.post(()->{
            if(event.equals("microphone_started")){recording=true;}
            if(event.equals("microphone_released")){recording=false;}
            String state=voiceState(event);if(state!=null&&service!=null)service.setVoiceState(state);
            if(event.equals("ready")&&service!=null)service.onVoiceReady();
        });
        try{
            JSONObject row=new JSONObject().put("time_ms",System.currentTimeMillis()).put("event",event).put("detail",detail);
            String encoded=row.toString();
            java.io.File log=new java.io.File(getFilesDir(),"assist-events.jsonl");
            if(log.length()>2_000_000)log.delete(); // ponytail: unbounded append since 0.4; a simple rotate keeps storage flat on a clock nobody can clean up
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
