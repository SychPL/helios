package pl.mateusz.helios;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.media.AudioManager;
import org.json.JSONArray;
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
    private volatile Integer lux; // null until the light sensor speaks: "unknown" is not "dark"
    // --- music (0.6) ---
    interface MusicListener {void onMusic(MusicSnapshot snapshot);}
    static final class MusicSnapshot {
        final MusicSession.Ui ui;final String title,artist,album,remoteInfo,issue;final android.graphics.Bitmap artwork;final int volume,accent;final long progressMs,durationMs,progressAtMs;final boolean muted,maConnected,localConnected;final java.util.List<String> commands;
        MusicSnapshot(MusicSession.Ui ui,String title,String artist,String album,android.graphics.Bitmap artwork,int accent,int volume,boolean muted,java.util.List<String> commands,boolean maConnected,boolean localConnected,String remoteInfo,String issue,long progressMs,long durationMs,long progressAtMs){
            this.progressMs=progressMs;this.durationMs=durationMs;this.progressAtMs=progressAtMs;this.ui=ui;this.title=title;this.artist=artist;this.album=album;this.artwork=artwork;this.accent=accent;this.volume=volume;this.muted=muted;this.commands=commands;this.maConnected=maConnected;this.localConnected=localConnected;this.remoteInfo=remoteInfo;this.issue=issue;
        }
    }
    private MusicAssistantClient ma;
    private SendspinClient sendspin;
    /** How loud the music stays while the assistant is talking (SPEC 0.11 pkt 4, adjusted by ear 2026-09-21). */
    static final float DUCK_GAIN=.07f;
    private AudioTrackSink sink;
    private MusicSession session;
    private AudioManager audioManager;
    /** Focus is decided on the Sendspin thread before the output opens (plan 0.11 V1), so the flag lives under its own lock, not on main. */
    private final Object focusLock=new Object();
    private boolean musicFocusHeld;
    private final AudioManager.OnAudioFocusChangeListener musicFocus=change->{
        synchronized(focusLock){if(change==AudioManager.AUDIOFOCUS_LOSS)musicFocusHeld=false;else if(change==AudioManager.AUDIOFOCUS_GAIN)musicFocusHeld=true;}
        main.post(()->{
            diag("music_focus","change="+change+(session==null?" (brak sesji)":""));
            if(session!=null){session.onFocusChange(change);publishMusic();}});
    };
    /** Pause = MA stopped the stream and keeps the queue paused (SPEC 0.11 pkt 4); the card stays until the queue says otherwise. Main thread only. */
    private final QueuePause queuePause=new QueuePause();
    private long queueCheckToken; // bumps on every new session and every definitive end: late get_active_queue answers are dropped
    private String queueId,sendspinClientId;
    private boolean settingVolume; // main only: a volume command is being applied, the DeviceVolume listener must not report a second time
    private final Runnable queueExpiry=this::onQueueExpiry;
    private String musicTitle,musicArtist,musicAlbum,remoteInfo,musicIssue,lenovoPlayerId,playerName="Helios";
    private android.graphics.Bitmap musicArtwork;private int musicAccent;
    private long musicProgressMs=-1,musicDurationMs=-1,musicProgressAt; // last server progress and when it arrived (elapsedRealtime), so the panel can extrapolate
    /** Decoded cover plus its mean colour (SPEC 0.8a pkt 4.2), both computed once per image on the network thread. */
    static final class Cover {final android.graphics.Bitmap bitmap;final int accent;Cover(android.graphics.Bitmap bitmap,int accent){this.bitmap=bitmap;this.accent=accent;}}
    private final ArtworkLoader<Cover> artworkLoader=new ArtworkLoader<>(this::fetchArtwork,cover->main.post(()->{musicArtwork=cover==null?null:cover.bitmap;musicAccent=cover==null?0:cover.accent;publishMusic();}),System::currentTimeMillis);
    private int musicVolume=100;private boolean musicMuted,maConnected,localConnected;
    private java.util.List<String> musicCommands=java.util.Collections.emptyList();
    private MusicListener musicListener;
    private final RecentPlays recent=new RecentPlays();
    private Runnable onDeviceLost;
    private Consumer<String> onDeviceChanged;
    interface AppearanceListener {void onAppearance(Appearance appearance,android.graphics.Bitmap background);}
    private AppearanceListener appearanceListener;
    private Appearance appearance=Appearance.solid();
    private android.graphics.Bitmap background;
    /** Keyed by imageId|focus: the same image with a new focus is re-cropped from the cache file, never re-fetched; the old bitmap stays until the new one is ready. */
    private final ArtworkLoader<android.graphics.Bitmap> backgroundLoader=new ArtworkLoader<>(this::fetchBackground,bitmap->main.post(()->{background=bitmap;publishAppearance();}),System::currentTimeMillis,false);
    private java.util.function.BiConsumer<String,String> diagnostics;
    private final HaDashboardClient.Listener cache=new HaDashboardClient.Listener(){
        @Override public void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){
            if(raw!=null&&issue==null)getSharedPreferences("helios",MODE_PRIVATE).edit().putString("dashboard_v2",raw.toString()).apply();
        }
        @Override public void onStates(Map<String,EntityStates.Entity> states){}
        @Override public void onUnavailable(String reason){}
    };

    @Override public void onCreate(){
        super.onCreate();
        MdiIcons.install(this); // before any dashboard parse: a version-6 layout names its icons by mdi: catalogue
        NotificationManager manager=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Helios",NotificationManager.IMPORTANCE_MIN));
        Notification notification=new Notification.Builder(this,CHANNEL).setContentTitle("Helios działa").setSmallIcon(R.drawable.ic_helios).setOngoing(true).build();
        startForeground(1,notification);
        installationId=getSharedPreferences("helios",MODE_PRIVATE).getString("installation_id",null);
        if(installationId==null){installationId=UUID.randomUUID().toString();getSharedPreferences("helios",MODE_PRIVATE).edit().putString("installation_id",installationId).apply();}
        dock=new DockController(this,new DockController.Listener(){
            public void onChanged(){publish();}
            public void onDiagnostic(String event,String detail){if(diagnostics!=null)diagnostics.accept(event,detail);}
        });dock.start();
        volume=new DeviceVolume(this,v->publish());volume.addListener(v->{if(!settingVolume)reportPlayerState();});volume.start();
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{connection=new JSONObject(saved);}catch(Exception ignored){}
        String cachedAppearance=getSharedPreferences("helios",MODE_PRIVATE).getString("appearance",null);
        if(cachedAppearance!=null)try{applyAppearance(new JSONObject(cachedAppearance),false);}catch(Exception ignored){}
        RecentPlays stored=RecentPlays.parse(getSharedPreferences("helios",MODE_PRIVATE).getString("music_recent",null));
        for(int i=stored.entries().size()-1;i>=0;i--)recent.add(stored.entries().get(i));
        if(connection!=null){startHa();startMusic();}
        updater=new Updater(new Updater.AndroidHost(this,text->main.post(()->{if(onStatus!=null)onStatus.accept(text);})),BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE);
        network.execute(updater::restore); // a leftover update operation without a sealed session is dropped
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){stopHa();stopMusic();dock.stop();volume.stop();network.shutdownNow();super.onDestroy();}

    JSONObject connection(){return connection;}
    HaDashboardClient ha(){return ha;}
    DockController dock(){return dock;}
    DeviceVolume volume(){return volume;}
    String installationId(){return installationId;}
    /** HA registry device id of this clock, null until the device channel is connected. */
    String deviceId(){return deviceId;}
    boolean devicePaired(){return device!=null&&device.active();}
    void setVoiceState(String state){if(!state.equals(voiceState)){voiceState=state;publish();if(state.equals("listening"))blinkLamp();}}
    /**
     * The room's light level, as the clock's own sensor reports it. The sensor is on-change, so this is called
     * only when the room actually changes; publishing is guarded by the telemetry fingerprint anyway.
     */
    void setLux(int value){if(lux==null||lux!=value){lux=value;publish();}}
    /** One short flash of the dock lamp when the clock starts listening for a command; a lit lamp winks off instead. Errors are ignored: the lamp is a hint, not a gate. */
    private void blinkLamp(){
        network.execute(()->{
            try{
                if(dock.unavailable()!=null)return;
                boolean wasOn=Boolean.TRUE.equals(dock.ledOn());
                if(wasOn)dock.turnOff();else dock.turnOn();
                Thread.sleep(180);
                if(wasOn)dock.turnOn();else dock.turnOff();
            }catch(Exception ignored){}
        });
    }
    void setOnDeviceLost(Runnable action){onDeviceLost=action;}
    void setOnDeviceChanged(Consumer<String> action){onDeviceChanged=action;}
    void setAppearanceListener(AppearanceListener listener){appearanceListener=listener;if(listener!=null)listener.onAppearance(appearance,background);}
    Appearance appearance(){return appearance;}
    private void publishAppearance(){if(appearanceListener!=null)appearanceListener.onAppearance(appearance,background);}
    /** Snapshot from HA (or the cached one at start): validated whole, theme applied at once, image after fetch; invalid keeps the last good one. */
    private void applyAppearance(JSONObject raw,boolean persist){
        Appearance next;
        try{next=Appearance.parse(raw);}catch(IllegalArgumentException e){if(diagnostics!=null)diagnostics.accept("appearance_invalid",e.getMessage());return;}
        if(persist)getSharedPreferences("helios",MODE_PRIVATE).edit().putString("appearance",raw.toString()).apply();
        appearance=next;Theme.set(Theme.byId(next.theme));
        if(next.image)backgroundLoader.request(next.key());else{backgroundLoader.clear();}
        publishAppearance();
    }
    /** Pairing moved to another HA or another device: the private photo and its options no longer belong here. */
    private void resetAppearance(){
        getSharedPreferences("helios",MODE_PRIVATE).edit().remove("appearance").remove("background_image_id").apply();
        new java.io.File(getFilesDir(),"background.jpg").delete();
        appearance=Appearance.solid();Theme.set(Theme.WARM_GRAPHITE);backgroundLoader.clear();publishAppearance();
    }
    /** Executor: cache hit decodes the file; otherwise one authenticated GET on the HA origin (no redirects, 2 MiB, 10 s), stored atomically, then decoded to 800x480 with the focus crop. */
    private void fetchBackground(String key,int generation){
        String[] parts=key.split("\\|");if(parts.length!=3)return;
        final String imageId=parts[0];final int fx=Integer.parseInt(parts[1]),fy=Integer.parseInt(parts[2]);
        final Appearance a=appearance;final JSONObject conn=connection;
        network.execute(()->{
            java.io.File cache=new java.io.File(getFilesDir(),"background.jpg");
            String cachedId=getSharedPreferences("helios",MODE_PRIVATE).getString("background_image_id",null);
            String why=null;
            if(!imageId.equals(cachedId)||!cache.isFile()){
                why=downloadBackground(conn,a,imageId,cache);
                if(why==null)getSharedPreferences("helios",MODE_PRIVATE).edit().putString("background_image_id",imageId).apply();
            }
            android.graphics.Bitmap result=why==null?decodeBackground(cache,fx,fy):null;
            if(result==null&&why==null)why="decode";
            if(result!=null&&diagnostics!=null)diagnostics.accept("background_ready",imageId.substring(0,8)+" "+cache.length()+"B focus "+fx+"/"+fy);
            if(why!=null&&diagnostics!=null)diagnostics.accept("background_error",why);
            backgroundLoader.deliver(generation,result);
        });
    }
    private String downloadBackground(JSONObject conn,Appearance a,String imageId,java.io.File cache){
        if(conn==null||a==null||!a.image||!imageId.equals(a.imageId))return "no_connection";
        java.net.HttpURLConnection c=null;java.io.File tmp=new java.io.File(cache.getPath()+".tmp");
        try{
            java.net.URI origin=new java.net.URI(conn.optString("url",""));
            String target=origin.getScheme()+"://"+origin.getRawAuthority()+a.path; // origin from the pairing only, path from the validated snapshot only
            c=(java.net.HttpURLConnection)new java.net.URL(target).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Authorization","Bearer "+conn.optString("token",""));
            int code=c.getResponseCode();if(code!=200)return "http_"+code;
            try(java.io.InputStream in=c.getInputStream();java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){
                byte[] chunk=new byte[16384];int n;long total=0;
                while((n=in.read(chunk))!=-1){total+=n;if(total>2*1024*1024){return "too_large";}out.write(chunk,0,n);}
            }
            if(!tmp.renameTo(cache)){return "store";}
            return null;
        }catch(Exception e){return e.getClass().getSimpleName();}
        finally{if(c!=null)c.disconnect();tmp.delete();}
    }
    static android.graphics.Bitmap decodeBackground(java.io.File file,int fx,int fy){
        try{
            android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(),o);
            if(o.outWidth<=0||o.outHeight<=0)return null;
            int sample=1;while(o.outWidth/(sample*2)>=DashboardView.WIDTH&&o.outHeight/(sample*2)>=DashboardView.HEIGHT)sample*=2;
            android.graphics.BitmapFactory.Options d=new android.graphics.BitmapFactory.Options();d.inSampleSize=sample;
            android.graphics.Bitmap src=android.graphics.BitmapFactory.decodeFile(file.getPath(),d);
            if(src==null)return null;
            int[] r=CropMath.rect(src.getWidth(),src.getHeight(),DashboardView.WIDTH,DashboardView.HEIGHT,fx,fy);
            android.graphics.Bitmap crop=android.graphics.Bitmap.createBitmap(src,r[0],r[1],r[2],r[3]);
            android.graphics.Bitmap out=android.graphics.Bitmap.createScaledBitmap(crop,DashboardView.WIDTH,DashboardView.HEIGHT,true);
            if(crop!=src)src.recycle();if(out!=crop)crop.recycle();
            return out;
        }catch(Exception e){return null;}
    }
    void setDiagnostics(java.util.function.BiConsumer<String,String> sink){diagnostics=sink;}
    /** A method, not a direct field read: the focus listener is built before the field exists. */
    private void diag(String event,String detail){if(diagnostics!=null)diagnostics.accept(event,detail);}
    void publish(){if(device!=null)device.publish();}
    /** Our own conversation finished: the sink may resume only if the system grants focus again. */
    /** Between wake word and "ready" - including the follow-up turns, which never pass through idle. */
    private boolean voiceActive(){String s=voiceState;return !s.equals("idle")&&!s.equals("error");}
    void onVoiceReady(){if(session!=null){session.onVoiceReady(this::requestMusicFocus);publishMusic();}}

    // --- music ---
    boolean musicConfigured(){return connection!=null&&connection.optJSONObject("music_assistant")!=null;}
    void setMusicListener(MusicListener listener){musicListener=listener;if(listener!=null)listener.onMusic(musicSnapshot());}
    /** Single source of the music state for the panel, telemetry and stats: the transport first, then the MA queue pause. */
    MusicSession.Ui musicUi(){
        MusicSession.Ui ui=session==null?MusicSession.Ui.NONE:session.ui();
        if(ui!=MusicSession.Ui.NONE)return ui;
        return queuePause.paused(SystemClock.elapsedRealtime())?MusicSession.Ui.PAUSED:MusicSession.Ui.NONE;
    }
    private static final java.util.List<String> QUEUE_PAUSE_COMMANDS=java.util.Collections.unmodifiableList(java.util.Arrays.asList("play","next","previous","stop"));
    MusicSnapshot musicSnapshot(){
        return new MusicSnapshot(musicUi(),musicTitle,musicArtist,musicAlbum,musicArtwork,musicAccent,musicVolume,musicMuted,queuePaused()?QUEUE_PAUSE_COMMANDS:musicCommands,maConnected,localConnected,remoteInfo,musicIssue,musicProgressMs,musicDurationMs,musicProgressAt);
    }
    MusicAssistantClient ma(){return ma;}
    String lenovoPlayerId(){return lenovoPlayerId;}
    RecentPlays recent(){return recent;}
    void rememberPlay(RecentPlays.Entry entry){recent.add(entry);getSharedPreferences("helios",MODE_PRIVATE).edit().putString("music_recent",recent.serialize()).apply();}
    private void publishMusic(){if(musicListener!=null)musicListener.onMusic(musicSnapshot());}
    /** Once a minute while playing: underruns and dropped chunks for the acceptance measurements (plan R5); stops by itself when playback stops. */
    private final Runnable musicStats=new Runnable(){public void run(){
        if(musicUi()!=MusicSession.Ui.PLAYING||sink==null||sendspin==null){musicStatsScheduled=false;return;}
        if(diagnostics!=null)diagnostics.accept("music_stats","underruns="+sink.underruns()+" dropped="+sendspin.dropped()+" "+sendspin.stats());
        main.postDelayed(this,60_000);
    }};
    private boolean musicStatsScheduled;
    private boolean requestMusicFocus(){
        synchronized(focusLock){
            if(musicFocusHeld)return true;
            int result=audioManager.requestAudioFocus(musicFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
            musicFocusHeld=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;return musicFocusHeld;
        }
    }
    private void abandonMusicFocus(){synchronized(focusLock){if(musicFocusHeld){audioManager.abandonAudioFocus(musicFocus);musicFocusHeld=false;}}}
    /** client/state with the device level and the sink mute: after volume/mute commands (in arrival order on main) and on every device change. */
    private void reportPlayerState(){
        musicVolume=volume.percent();
        if(sendspin!=null)sendspin.reportVolume(musicVolume,musicMuted);
    }
    private boolean queuePaused(){return session!=null&&session.ui()==MusicSession.Ui.NONE&&queuePause.paused(SystemClock.elapsedRealtime());}
    /** Local overlay controls. Transport commands go over Sendspin when the server advertises them, else through the MA API; volume always through the MA API to this player. */
    void musicCommand(String command,Consumer<String> done){
        Consumer<String> report=issue("Muzyka",done);
        boolean queuePaused=queuePaused(); // no transport session: play/stop go to the MA queue, focus is asked for on the next stream/start
        if(command.equals("play")&&!queuePaused&&session!=null&&!session.onUserPlay(this::requestMusicFocus)){report.accept("Głośnik jest zajęty przez inną aplikację");return;}
        if(!queuePaused&&sendspin!=null&&sendspin.command(command)){report.accept(null);return;}
        if(ma==null||lenovoPlayerId==null){report.accept("Brak połączenia z Music Assistant");return;}
        final long token=queueCheckToken; // a late stop answer must not end a newer pause
        ma.playerCommand(lenovoPlayerId,command,r->main.post(()->{
            if(command.equals("stop")&&token==queueCheckToken&&queuePaused()){queueCheckToken++;endQueuePause("stopped");}
            report.accept(null);
        }),e->main.post(()->report.accept(e)));
    }
    void musicVolume(int level,Consumer<String> done){
        Consumer<String> report=issue("Głośność",done);
        if(ma==null||lenovoPlayerId==null){report.accept("Brak połączenia z Music Assistant");return;}
        ma.volume(lenovoPlayerId,level,r->main.post(()->report.accept(null)),e->main.post(()->report.accept(e)));
    }
    /** Track position from the panel's seek bar: one MA queue command per release; the server's next progress update confirms it. */
    void musicSeek(int seconds,Consumer<String> done){
        Consumer<String> report=issue("Przewijanie",done);
        if(ma==null||lenovoPlayerId==null){report.accept("Brak połączenia z Music Assistant");return;}
        musicProgressMs=seconds*1000L;musicProgressAt=android.os.SystemClock.elapsedRealtime();publishMusic();
        ma.seek(lenovoPlayerId,seconds,r->main.post(()->report.accept(null)),e->main.post(()->report.accept(e)));
    }
    /** A failed or timed-out overlay action lands in the snapshot as "<prefix>: <error>" so the panel can show it and reset its slider; the next MA player update clears it. */
    private Consumer<String> issue(String prefix,Consumer<String> done){
        return error->{if(error!=null){musicIssue=prefix+": "+error;publishMusic();}done.accept(error);};
    }
    void musicMute(boolean muted,Consumer<String> done){
        Consumer<String> report=issue("Muzyka",done);
        if(ma==null||lenovoPlayerId==null){if(sink!=null){sink.setMuted(muted);musicMuted=muted;reportPlayerState();publishMusic();report.accept(null);}else report.accept("Brak odtwarzacza");return;}
        ma.mute(lenovoPlayerId,muted,r->main.post(()->report.accept(null)),e->main.post(()->report.accept(e)));
    }
    private void startMusic(){
        JSONObject music=connection.optJSONObject("music_assistant");
        if(music==null)return;
        playerName=getSharedPreferences("helios",MODE_PRIVATE).getString("device_name","Helios"); // the HA device name; the bridge's player_name is gone (SPEC 0.10 pkt 5.4)
        String clientId=getSharedPreferences("helios",MODE_PRIVATE).getString("sendspin_client_id",null);
        if(clientId==null){clientId=UUID.randomUUID().toString();getSharedPreferences("helios",MODE_PRIVATE).edit().putString("sendspin_client_id",clientId).apply();}
        sendspinClientId=clientId;
        sink=new AudioTrackSink();
        session=new MusicSession(new MusicSession.Sink(){
            public void pause(){sink.pause();}
            public void resume(){sink.resume();}
            // Talking to the assistant leaves the music playing, but far back: 7% of the stream gain is
            // roughly 23 dB down, quiet enough not to compete with a spoken answer and still obviously
            // playing. Touches the music stream only - the device volume the answer is spoken at stays put.
            // ponytail: one constant, tuned by ear; a per-room setting only if someone actually asks.
            public void duck(boolean on){sink.setGain(on?DUCK_GAIN:1f);}
        },()->sendspin!=null&&sendspin.command("pause"));
        String sendspinUrl=music.optString("sendspin_url","");
        if(!sendspinUrl.isEmpty()){
            sendspin=new SendspinClient(sendspinUrl,clientId,playerName,BuildConfig.VERSION_NAME,sink,new SendspinClient.Listener(){
                public void onProtocol(String detail){
                    if(diagnostics!=null)diagnostics.accept("sendspin_msg",detail);
                    // stream/start granted focus but MA stopped before any audio: no session ever existed, so onState(NONE) never fires - release here (plan V1)
                    if(detail.startsWith("group/update")&&detail.endsWith("no-session")&&(detail.contains(" stopped ")||detail.contains(" idle ")))abandonMusicFocus();
                }
                /** Sendspin thread, before the output opens: focus already held or granted now; otherwise the stream is refused and MA gets pause. */
                public boolean onStreamStart(){
                    if(requestMusicFocus()){
                        if(voiceActive()){
                            sink.setGain(DUCK_GAIN); // before the output opens: not even the first chunk plays at full level over the answer
                            main.post(()->{if(session==null)return;if(voiceActive())session.duckForVoice();else sink.setGain(1f);}); // the conversation may have ended meanwhile
                        }
                        return true;
                    }
                    main.post(()->{musicIssue="Głośnik jest zajęty przez inną aplikację";publishMusic();});return false;
                }
                public void onStreamFailed(String reason){abandonMusicFocus();main.post(()->{musicIssue=reason;publishMusic();});}
                public void onStreamRefused(boolean pausedViaController){if(!pausedViaController)main.post(()->{if(ma!=null&&lenovoPlayerId!=null)ma.playerCommand(lenovoPlayerId,"pause",r->{},e->{if(diagnostics!=null)diagnostics.accept("music_queue","error="+e);});});}
                /** Ten silent seconds: the client already closed the output and ended the session; drop focus and stop MA feeding a dead player. */
                public void onAudioIdle(){abandonMusicFocus();main.post(()->{if(ma!=null&&lenovoPlayerId!=null)ma.playerCommand(lenovoPlayerId,"pause",r->{},e->{if(diagnostics!=null)diagnostics.accept("music_queue","error="+e);});});}
                public void onVolumeCommand(int percent){main.post(()->{settingVolume=true;try{volume.set(percent);}finally{settingVolume=false;}reportPlayerState();publishMusic();});} // one report per command, not one per listener
                public void onMuteCommand(boolean muted){main.post(()->{if(sink!=null)sink.setMuted(muted);musicMuted=muted;reportPlayerState();publishMusic();});}
                public void onGap(int count,long lateMs){if(diagnostics!=null)diagnostics.accept("music_gap","drops="+count+" late_ms="+lateMs);}
                public void onState(SendspinClient.State state){
                    if(diagnostics!=null)diagnostics.accept("music_transport",state.name());
                    if(state==SendspinClient.State.NONE)abandonMusicFocus(); // the output is already closed on the client's thread
                    main.post(()->{
                        if(session==null)return;
                        long now=SystemClock.elapsedRealtime();
                        MusicSession.Ui before=session.ui();session.onTransport(state);
                        if(state!=SendspinClient.State.NONE){
                            if(before==MusicSession.Ui.NONE){queuePause.onNewSession();queueCheckToken++;main.removeCallbacks(queueExpiry);}
                            if(state==SendspinClient.State.PLAYING&&!musicStatsScheduled){musicStatsScheduled=true;main.postDelayed(musicStats,60_000);}
                        }else{
                            queueCheckToken++;
                            if(before!=MusicSession.Ui.NONE&&queuePause.onSessionEnded(localConnected&&maConnected,now)){checkQueue(queueCheckToken,now);scheduleQueueExpiry();}
                            else{queuePause.onNewSession();artworkLoader.clear();}
                        }
                        publishMusic();publish();
                    });
                }
                public void onMetadata(SendspinClient.Metadata m){final long at=android.os.SystemClock.elapsedRealtime();main.post(()->{
                    if(m.title==null&&queuePaused())return; // MA clears the metadata after stop; the paused card keeps the last track
                    musicTitle=m.title;musicArtist=m.artist;musicAlbum=m.album;musicProgressMs=m.progressMs;musicDurationMs=m.durationMs;musicProgressAt=at;publishMusic();
                });if(m.title!=null||m.artworkUrl!=null)artworkLoader.request(m.artworkUrl);}
                public void onController(java.util.List<String> commands,Integer groupVolume,Boolean groupMuted){main.post(()->{musicCommands=commands;publishMusic();});}
                public void onArtwork(byte[] jpeg){} // artwork@v1 is not advertised (MA 2.10.3 closes on it); covers come by URL
                public void onConnection(boolean connected,String detail){
                    if(diagnostics!=null)diagnostics.accept("sendspin_"+(connected?"connected":"down"),detail==null?"":detail);
                    if(!connected){abandonMusicFocus();artworkLoader.clear();}
                    main.post(()->{
                        localConnected=connected;musicIssue=connected&&"connected".equals(detail)?null:detail;volume.setFast(connected);
                        if(connected){if(sink!=null)sink.setMuted(false);musicMuted=false;reportPlayerState();} // a fresh server/hello starts unmuted at the device level
                        else if(queuePaused()){queuePause.onNewSession();queueCheckToken++;}
                        publishMusic();
                    });
                }
            });
            sendspin.start();
        }
        ma=new MusicAssistantClient(MusicAssistantClient.wsUrl(music.optString("url","")),music.optString("token",""),new MusicAssistantClient.Listener(){
            public void onConnection(boolean connected,String detail){if(diagnostics!=null)diagnostics.accept("ma_"+(connected?"connected":"down"),detail==null?"":detail);main.post(()->{
                maConnected=connected;
                if(connected)findLenovo();
                else if(queuePaused()){queueCheckToken++;endQueuePause("error=ma_down");} // nobody can confirm or end the pause any more
                publishMusic();
            });}
            public void onPlayerUpdated(JSONObject player){main.post(()->trackPlayer(player));}
            public void onQueueUpdated(JSONObject queue){main.post(()->{
                String id=queue.optString("queue_id",""),state=queue.isNull("state")?null:queue.optString("state");
                if(!id.equals(queueId!=null?queueId:lenovoPlayerId)||!queuePaused())return;
                if(diagnostics!=null)diagnostics.accept("music_queue","event="+state);
                queuePause.onQueueState(state,SystemClock.elapsedRealtime());scheduleQueueExpiry();
                if(!queuePaused())artworkLoader.clear();
                publishMusic();publish();
            });}
        });
        ma.start();
    }
    /** Confirms a tentative queue pause with get_active_queue; waits up to 10 s for the player id when the session ended before MA listed the player. */
    private void checkQueue(long token,long startedAt){
        long now=SystemClock.elapsedRealtime();
        if(token!=queueCheckToken||!queuePaused())return;
        if(ma==null||!maConnected){endQueuePause("error=no_ma");return;}
        if(lenovoPlayerId==null){
            if(now-startedAt>10_000){endQueuePause("error=no_player");return;}
            if(diagnostics!=null&&now-startedAt<600)diagnostics.accept("music_queue","deferred");
            main.postDelayed(()->checkQueue(token,startedAt),500);return;
        }
        ma.activeQueue(lenovoPlayerId,q->main.post(()->{
            if(token!=queueCheckToken||!queuePaused())return;
            String state=q.isNull("state")?null:q.optString("state");queueId=q.optString("queue_id",lenovoPlayerId);
            if(diagnostics!=null)diagnostics.accept("music_queue","state="+state);
            queuePause.onQueueQuery(state,SystemClock.elapsedRealtime());scheduleQueueExpiry();
            if(!queuePaused())artworkLoader.clear();
            publishMusic();publish();
        }),e->main.post(()->{
            if(token!=queueCheckToken||"superseded".equals(e))return;
            if(!"Brak kolejki".equals(e))musicIssue="Muzyka: "+e;
            endQueuePause("error="+e);
        }));
    }
    private void onQueueExpiry(){if(session!=null&&session.ui()==MusicSession.Ui.NONE&&!queuePause.paused(SystemClock.elapsedRealtime()))artworkLoader.clear();publishMusic();publish();}
    private void endQueuePause(String why){
        if(diagnostics!=null)diagnostics.accept("music_queue",why);
        queueCheckToken++;queuePause.onNewSession();main.removeCallbacks(queueExpiry);artworkLoader.clear();publishMusic();publish();
    }
    private void scheduleQueueExpiry(){
        main.removeCallbacks(queueExpiry);
        long in=queuePause.expiresInMs(SystemClock.elapsedRealtime());
        if(in>=0)main.postDelayed(queueExpiry,in+50);
    }
    /** Cover art only from the MA host (image proxy or a same-host URL); other hosts are ignored so the token never leaks and nothing foreign is fetched. */
    static{System.setProperty("http.keepAlive","false");} // covers and backgrounds are rare, one-shot GETs; a pooled socket the server already closed fails with 'unexpected end of stream' and is never retried
    private void fetchArtwork(String url,int generation){
        JSONObject music=connection==null?null:connection.optJSONObject("music_assistant");
        if(music==null)return;
        String base=music.optString("url","").replaceAll("/+$","");
        String target=url.startsWith("http")?url:base+(url.startsWith("/")?"":"/")+url;
        try{java.net.URI t=new java.net.URI(target),b=new java.net.URI(base);if(t.getHost()==null||!t.getHost().equalsIgnoreCase(b.getHost()))return;}catch(Exception e){return;}
        network.execute(()->{
            java.net.HttpURLConnection c=null;Cover result=null;String why="";
            try{
                c=(java.net.HttpURLConnection)new java.net.URL(target).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);                if(c.getResponseCode()==200)try(java.io.InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                    byte[] chunk=new byte[8192];int n;boolean tooBig=false;
                    while((n=in.read(chunk))!=-1){if(out.size()+n>1048576){tooBig=true;break;}out.write(chunk,0,n);}
                    if(!tooBig){android.graphics.Bitmap bitmap=decodeCover(out.toByteArray());if(bitmap!=null)result=new Cover(bitmap,accentOf(bitmap));}
                }
            }catch(Exception e){why=" "+e;}
            finally{if(c!=null)c.disconnect();}
            if(diagnostics!=null)diagnostics.accept("cover",result==null?"failed "+target+why:result.bitmap.getWidth()+"x"+result.bitmap.getHeight());
            artworkLoader.deliver(generation,result);
        });
    }
    /** Mean RGB of the cover shrunk to 16x16: computed once per image, never on progress ticks; 0 when it cannot be sampled. */
    static int accentOf(android.graphics.Bitmap bitmap){
        try{
            android.graphics.Bitmap small=android.graphics.Bitmap.createScaledBitmap(bitmap,16,16,true);
            int[] pixels=new int[256];small.getPixels(pixels,0,16,0,0,16,16);if(small!=bitmap)small.recycle();
            return AccentColor.average(pixels);
        }catch(Exception e){return 0;}
    }
    static android.graphics.Bitmap decodeCover(byte[] bytes){
        android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,o);
        if(o.outWidth<=0||o.outHeight<=0)return null;
        int sample=1;while(Math.max(o.outWidth,o.outHeight)/sample>320)sample*=2;
        android.graphics.BitmapFactory.Options d=new android.graphics.BitmapFactory.Options();d.inSampleSize=sample;
        return android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,d);
    }
    private void stopMusic(){
        if(sendspin!=null){sendspin.stop();sendspin=null;}
        if(ma!=null){ma.stop();ma=null;}
        if(sink!=null){sink.stop();sink=null;}
        abandonMusicFocus();session=null;lenovoPlayerId=null;queueId=null;maConnected=false;localConnected=false;remoteInfo=null;artworkLoader.clear();musicArtwork=null;musicProgressMs=-1;musicDurationMs=-1;musicTitle=null;musicArtist=null;musicAlbum=null;
        queuePause.onNewSession();queueCheckToken++;main.removeCallbacks(queueExpiry);musicMuted=false;volume.setFast(false);
        publishMusic();
    }
    private void findLenovo(){
        if(ma==null)return;
        ma.players(players->main.post(()->{for(int i=0;i<players.length();i++)trackPlayer(players.optJSONObject(i));}),e->{});
    }
    /** Lenovo is the sendspin player whose id is our Sendspin client id (name only as a fallback); any other playing player feeds the music tile's "remote" line. */
    private void trackPlayer(JSONObject player){
        if(player==null)return;
        boolean sendspinPlayer="sendspin".equals(player.optString("provider"));
        boolean lenovo=sendspinPlayer&&sendspinClientId!=null&&sendspinClientId.equals(player.optString("player_id"));
        if(!lenovo&&sendspinPlayer&&lenovoPlayerId==null&&playerName.equals(player.optString("name"))){lenovo=true;if(diagnostics!=null)diagnostics.accept("music_player","match=name");}
        if(lenovo){
            lenovoPlayerId=player.optString("player_id",lenovoPlayerId);if(musicIssue!=null&&(musicIssue.startsWith("Głośność: ")||musicIssue.startsWith("Muzyka: ")||musicIssue.startsWith("Przewijanie: ")))musicIssue=null;
            if(queuePaused()){queuePause.onPlayerUpdate(player.optString("playback_state"),SystemClock.elapsedRealtime());scheduleQueueExpiry();}
        }
        else if("playing".equals(player.optString("playback_state"))&&!player.optBoolean("hide_in_ui",false)){
            JSONObject media=player.optJSONObject("current_media");
            remoteInfo=player.optString("name","")+(media==null||media.isNull("title")?"":" · "+media.optString("title",""));
        }else if(remoteInfo!=null&&remoteInfo.startsWith(player.optString("name","\u0000")))remoteInfo=null;
        publishMusic();
    }

    private void startHa(){
        if(connection==null||authInvalid())return; // SPEC 0.10 pkt 8.2: a refused token is never retried until a new pairing
        final int gen=connections.generation(); // this session's identity; events and refusals of an older session are dropped by the controller
        String cached=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_v2",null);
        JSONObject cachedRaw=null;
        if(cached!=null)try{cachedRaw=new JSONObject(cached);}catch(Exception ignored){}
        ha=new HaDashboardClient(connection,cachedRaw);ha.attach(cache);
        ha.attach(new HaDashboardClient.Listener(){
            public void onDashboard(JSONObject raw,DashboardSpec spec,Map<String,EntityStates.Entity> states,String issue){}
            public void onStates(Map<String,EntityStates.Entity> states){}
            public void onUnavailable(String reason){}
            public void onAuthInvalid(){network.execute(()->{String r=connections.markAuthInvalid(gen);main.post(()->{if(diagnostics!=null)diagnostics.accept("auth_invalid",r);if(!ConnectionController.IGNORED.equals(r)&&onAuthInvalid!=null)onAuthInvalid.run();});});}
        });
        device=new HeliosDeviceClient(ha,installationId,this::telemetry,this::execute,new HeliosDeviceClient.Listener(){
            public void onAppearance(JSONObject a){main.post(()->applyAppearance(a,true));}
            public void onConnection(JSONObject e){applyConnection(e,gen);}
            public void onChannelIssue(String t){if(diagnostics!=null)diagnostics.accept("channel",t);main.post(()->{if(gen!=connections.generation())return;channelIssue=t;if(onChannelIssue!=null)onChannelIssue.accept(t);});}
            public void onDevice(String id,String area,String name){main.post(()->{
            boolean lost=deviceId!=null&&id==null;deviceId=id;
            if(id!=null){
                channelIssue=null;
                String previous=getSharedPreferences("helios",MODE_PRIVATE).getString("appearance_device",null);
                if(previous!=null&&!previous.equals(id))resetAppearance(); // a new pairing is a new HA device: the old private photo goes
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("appearance_device",id).apply();
            }
            if(name!=null&&!name.equals(getSharedPreferences("helios",MODE_PRIVATE).getString("device_name",null))){
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("device_name",name).apply();
                if(diagnostics!=null)diagnostics.accept("device_name",name);
                if(ma!=null||sendspin!=null){stopMusic();startMusic();} // the MA player carries the HA device name
            }
            if(lost&&onDeviceLost!=null)onDeviceLost.run();
            if(onDeviceChanged!=null)onDeviceChanged.accept(id);
            });}
        });
        device.start();ha.start();
    }
    private void stopHa(){if(device!=null){device.stop();device=null;}if(ha!=null){ha.stop();ha=null;}deviceId=null;}
    private Telemetry telemetry(){
        MusicSession.Ui ui=musicUi();
        return new Telemetry(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,voiceState,dock.dockConnected(),dock.charging(),dock.ledOn(),dock.ledBrightness(),dock.padVersion(),volume.percent(),(SystemClock.elapsedRealtime()-startedAt)/1000,ui==MusicSession.Ui.PLAYING?"playing":ui==MusicSession.Ui.PAUSED?"paused":"none",lux);
    }
    /** Allowlisted hardware commands from HA; the caller already validated names and argument ranges. */
    private String execute(String command,JSONObject args) throws Exception {
        switch(command){
            case "lamp.turn_on":if(dock.unavailable()!=null)return "dock_unavailable";dock.turnOn();return null;
            case "lamp.turn_off":if(dock.unavailable()!=null)return "dock_unavailable";dock.turnOff();return null;
            case "lamp.set_brightness":if(dock.unavailable()!=null)return "dock_unavailable";dock.setBrightness(args.getInt("level"));return null;
            case "audio.set_device_volume":volume.set(args.getInt("percent"));return null;
            case "music.play":case "music.pause":case "music.stop":{ // Assist "wyłącz muzykę" through the HA media_player: the local transport, same path as the panel buttons
                if(sendspin==null&&ma==null)return "no_music";
                final String transport=command.substring("music.".length());
                java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);final String[] error=new String[1];
                main.post(()->musicCommand(transport,e->{error[0]=e;done.countDown();}));
                if(!done.await(10,java.util.concurrent.TimeUnit.SECONDS))return "timeout";
                return error[0]==null?null:error[0].replaceAll("[^A-Za-z0-9_]+","_").toLowerCase(java.util.Locale.ROOT);}
            default:return "unknown_command";
        }
    }
    private final ConnectionController connections=new ConnectionController(
        new ConnectionController.Store(){
            public JSONObject current(){return connection;}
            public boolean save(JSONObject value){ // commit(): the old identity is gone in HA, an apply() lost to a restart would strand the clock
                boolean ok=getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",value.toString()).commit();
                if(ok){connection=value;main.post(HeliosService.this::notifyConnection);} // every persisted change reaches the activity (diagnostics_url alone included), restart or not
                return ok;}
            public boolean clear(){boolean ok=getSharedPreferences("helios",MODE_PRIVATE).edit().remove("connection").commit();if(ok){connection=null;main.post(HeliosService.this::notifyConnection);}return ok;}},
        MusicAssistantClient::probe,
        new ConnectionController.Transports(){
            // The activity re-attaches on notifyConnection; Store.save() already posted one, but that runs before this
            // restart and binds it to the client about to be stopped - so it is told again once the new client exists.
            public void restartAll(){main.post(()->{resetAppearance();stopHa();stopMusic();startHa();startMusic();notifyConnection();});} // pairing: everything from scratch
            public void restartHa(){main.post(()->{stopHa();startHa();notifyConnection();});} // pipeline/dashboard changed: the HA session only, music untouched
            public void restartMusic(){main.post(()->{stopMusic();startMusic();});}
            public void stopAll(){main.post(()->{stopHa();stopMusic();});} // transports only: the in-memory connection (with its auth_invalid flag) stays for authInvalid(); Store.clear() is what forgets it
            public void issue(String text){main.post(()->{musicIssue=text;publishMusic();});}});
    private java.util.function.Consumer<String> onStatus;
    private Updater updater; // built in onCreate: a Service has no Context before it is attached
    Updater updater(){return updater;}
    void setOnStatus(java.util.function.Consumer<String> c){onStatus=c;}
    /** Menu → "Aktualizacja Heliosa" (SPEC 0.10 pkt 7); one operation at a time, the updater says so itself. */
    void update(){network.execute(updater::run);}
    /** Fetches and installs another package from its own GitHub releases (SPEC 0.12 pkt 6.2). */
    void install(ReleaseInfo.Source source,String installedVersion){network.execute(()->updater.run(source,installedVersion));}
    /** Downloads our own update and hands the file to the caller, which passes it to the bridge. */
    void fetchForBridge(java.util.function.Consumer<java.io.File> done){
        network.execute(()->{java.io.File file=updater.fetch(ReleaseInfo.HELIOS,BuildConfig.VERSION_NAME);done.accept(file);});
    }
    private Runnable onConnectionChanged,onAuthInvalid;
    private Consumer<String> onChannelIssue;
    private String channelIssue;
    void setOnConnectionChanged(Runnable r){onConnectionChanged=r;}
    void setOnAuthInvalid(Runnable r){onAuthInvalid=r;}
    void setOnChannelIssue(Consumer<String> c){onChannelIssue=c;}
    private void notifyConnection(){if(onConnectionChanged!=null)onConnectionChanged.run();} // MainActivity: config=service.connection(); detachHa(); attachHa()
    boolean authInvalid(){return !connections.startAllowed();} // the controller owns both the persisted flag and the unsaved memory (tested there)
    boolean legacyConnection(){return connection!=null&&connection.optInt("protocol",1)<2;}
    /** The channel's last user-facing state ("Zegar usunięty z HA…", "Inne urządzenie…"), null while the channel is fine. */
    String channelIssue(){return channelIssue;}
    /** Pairing over HTTP (SPEC 0.10 pkt 4.2): the service persists a 200 even if the activity is gone meanwhile - HA has already committed the identity. */
    void pair(String url,String code,Consumer<String> done){
        network.execute(()->{
            String error;
            try{
                JSONObject body=new JSONObject().put("installation_id",installationId).put("code",code).put("app_version",BuildConfig.VERSION_NAME).put("version_code",BuildConfig.VERSION_CODE);
                PairingClient.Result r=PairingClient.pair(url,body);
                error=r.status!=200||r.json==null?PairingClient.message(r.status):connections.pairedWith(r.json,url);
            }catch(Exception e){error="Błąd przygotowania żądania";}
            final String result=error;main.post(()->{if(result==null)channelIssue=null;done.accept(result);});
        });
    }
    /** Called by the device client of the current HA session; gen was read when the session's client was created. */
    void applyConnection(JSONObject event,int gen){network.execute(()->connections.applyConnection(event,gen));}
}
