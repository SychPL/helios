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
    private AudioTrackSink sink;
    private MusicSession session;
    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener musicFocus=change->main.post(()->{if(session!=null){session.onFocusChange(change);publishMusic();}});
    private boolean musicFocusHeld;
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
        volume=new DeviceVolume(this,v->publish());volume.start();
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        String saved=getSharedPreferences("helios",MODE_PRIVATE).getString("connection",null);
        if(saved!=null)try{connection=new JSONObject(saved);}catch(Exception ignored){}
        String cachedAppearance=getSharedPreferences("helios",MODE_PRIVATE).getString("appearance",null);
        if(cachedAppearance!=null)try{applyAppearance(new JSONObject(cachedAppearance),false);}catch(Exception ignored){}
        RecentPlays stored=RecentPlays.parse(getSharedPreferences("helios",MODE_PRIVATE).getString("music_recent",null));
        for(int i=stored.entries().size()-1;i>=0;i--)recent.add(stored.entries().get(i));
        if(connection!=null){startHa();startMusic();}
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
    void pair(String code){if(device!=null)device.pair(code);}
    void setVoiceState(String state){if(!state.equals(voiceState)){voiceState=state;publish();if(state.equals("listening"))blinkLamp();}}
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
    void publish(){if(device!=null)device.publish();}
    /** Our own conversation finished: the sink may resume only if the system grants focus again. */
    void onVoiceReady(){if(session!=null){session.onVoiceReady(this::requestMusicFocus);publishMusic();}}

    // --- music ---
    boolean musicConfigured(){return connection!=null&&connection.optJSONObject("music_assistant")!=null;}
    void setMusicListener(MusicListener listener){musicListener=listener;if(listener!=null)listener.onMusic(musicSnapshot());}
    MusicSnapshot musicSnapshot(){
        return new MusicSnapshot(session==null?MusicSession.Ui.NONE:session.ui(),musicTitle,musicArtist,musicAlbum,musicArtwork,musicAccent,musicVolume,musicMuted,musicCommands,maConnected,localConnected,remoteInfo,musicIssue,musicProgressMs,musicDurationMs,musicProgressAt);
    }
    MusicAssistantClient ma(){return ma;}
    String lenovoPlayerId(){return lenovoPlayerId;}
    RecentPlays recent(){return recent;}
    void rememberPlay(RecentPlays.Entry entry){recent.add(entry);getSharedPreferences("helios",MODE_PRIVATE).edit().putString("music_recent",recent.serialize()).apply();}
    private void publishMusic(){if(musicListener!=null)musicListener.onMusic(musicSnapshot());}
    /** Once a minute while playing: underruns and dropped chunks for the acceptance measurements (plan R5); stops by itself when playback stops. */
    private final Runnable musicStats=new Runnable(){public void run(){
        if(session==null||session.ui()!=MusicSession.Ui.PLAYING||sink==null||sendspin==null){musicStatsScheduled=false;return;}
        if(diagnostics!=null)diagnostics.accept("music_stats","underruns="+sink.underruns()+" dropped="+sendspin.dropped());
        main.postDelayed(this,60_000);
    }};
    private boolean musicStatsScheduled;
    private boolean requestMusicFocus(){
        int result=audioManager.requestAudioFocus(musicFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
        musicFocusHeld=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;return musicFocusHeld;
    }
    private void abandonMusicFocus(){if(musicFocusHeld){audioManager.abandonAudioFocus(musicFocus);musicFocusHeld=false;}}
    /** Local overlay controls. Transport commands go over Sendspin when the server advertises them, else through the MA API; volume always through the MA API to this player. */
    void musicCommand(String command,Consumer<String> done){
        Consumer<String> report=issue("Muzyka",done);
        if(command.equals("play")&&session!=null&&!session.onUserPlay(this::requestMusicFocus)){report.accept("Głośnik jest zajęty przez inną aplikację");return;}
        if(sendspin!=null&&sendspin.command(command)){report.accept(null);return;}
        if(ma==null||lenovoPlayerId==null){report.accept("Brak połączenia z Music Assistant");return;}
        ma.playerCommand(lenovoPlayerId,command,r->main.post(()->report.accept(null)),e->main.post(()->report.accept(e)));
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
        if(ma==null||lenovoPlayerId==null){if(sink!=null){sink.setMuted(muted);musicMuted=muted;publishMusic();report.accept(null);}else report.accept("Brak odtwarzacza");return;}
        ma.mute(lenovoPlayerId,muted,r->main.post(()->report.accept(null)),e->main.post(()->report.accept(e)));
    }
    private void startMusic(){
        JSONObject music=connection.optJSONObject("music_assistant");
        if(music==null)return;
        playerName=getSharedPreferences("helios",MODE_PRIVATE).getString("device_name",music.optString("player_name","Helios"));
        String clientId=getSharedPreferences("helios",MODE_PRIVATE).getString("sendspin_client_id",null);
        if(clientId==null){clientId=UUID.randomUUID().toString();getSharedPreferences("helios",MODE_PRIVATE).edit().putString("sendspin_client_id",clientId).apply();}
        sink=new AudioTrackSink();
        session=new MusicSession(new MusicSession.Sink(){
            public void pause(){sink.pause();}
            public void resume(){sink.resume();}
            public void duck(boolean on){sink.setGain(on?musicVolume/100f*0.2f:musicVolume/100f);}
        },()->sendspin!=null&&sendspin.command("pause"));
        String sendspinUrl=music.optString("sendspin_url","");
        if(!sendspinUrl.isEmpty()){
            sendspin=new SendspinClient(sendspinUrl,clientId,playerName,BuildConfig.VERSION_NAME,sink,new SendspinClient.Listener(){
                public void onProtocol(String detail){if(diagnostics!=null)diagnostics.accept("sendspin_msg",detail);}
                public void onState(SendspinClient.State state){if(diagnostics!=null)diagnostics.accept("music_transport",state.name());main.post(()->{
                    if(session==null)return;
                    MusicSession.Ui before=session.ui();session.onTransport(state);
                    if(state!=SendspinClient.State.NONE&&before==MusicSession.Ui.NONE)requestMusicFocus();
                    if(state==SendspinClient.State.PLAYING&&!musicStatsScheduled){musicStatsScheduled=true;main.postDelayed(musicStats,60_000);}
                    if(state==SendspinClient.State.NONE){abandonMusicFocus();artworkLoader.clear();}
                    publishMusic();publish();
                });}
                public void onMetadata(SendspinClient.Metadata m){final long at=android.os.SystemClock.elapsedRealtime();main.post(()->{musicTitle=m.title;musicArtist=m.artist;musicAlbum=m.album;musicProgressMs=m.progressMs;musicDurationMs=m.durationMs;musicProgressAt=at;publishMusic();});artworkLoader.request(m.artworkUrl);}
                public void onController(java.util.List<String> commands,Integer groupVolume,Boolean groupMuted){main.post(()->{musicCommands=commands;publishMusic();});}
                public void onArtwork(byte[] jpeg){} // artwork@v1 is not advertised (MA 2.10.3 closes on it); covers come by URL
                public void onPlayer(int volume,boolean muted){main.post(()->{musicVolume=volume;musicMuted=muted;publishMusic();});}
                public void onConnection(boolean connected,String detail){if(diagnostics!=null)diagnostics.accept("sendspin_"+(connected?"connected":"down"),detail==null?"":detail);if(!connected)artworkLoader.clear();main.post(()->{localConnected=connected;musicIssue=connected&&"connected".equals(detail)?null:detail;publishMusic();});}
            });
            sendspin.start();
        }
        ma=new MusicAssistantClient(MusicAssistantClient.wsUrl(music.optString("url","")),music.optString("token",""),new MusicAssistantClient.Listener(){
            public void onConnection(boolean connected,String detail){if(diagnostics!=null)diagnostics.accept("ma_"+(connected?"connected":"down"),detail==null?"":detail);main.post(()->{maConnected=connected;if(connected)findLenovo();publishMusic();});}
            public void onPlayerUpdated(JSONObject player){main.post(()->trackPlayer(player));}
        });
        ma.start();
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
        abandonMusicFocus();session=null;lenovoPlayerId=null;maConnected=false;localConnected=false;remoteInfo=null;artworkLoader.clear();musicArtwork=null;musicProgressMs=-1;musicDurationMs=-1;musicTitle=null;musicArtist=null;musicAlbum=null;
        publishMusic();
    }
    private void findLenovo(){
        if(ma==null)return;
        ma.players(players->main.post(()->{for(int i=0;i<players.length();i++)trackPlayer(players.optJSONObject(i));}),e->{});
    }
    /** Lenovo is the sendspin player carrying our name; any other playing player feeds the music tile's "remote" line. */
    private void trackPlayer(JSONObject player){
        if(player==null)return;
        boolean lenovo="sendspin".equals(player.optString("provider"))&&playerName.equals(player.optString("name"));
        if(lenovo){lenovoPlayerId=player.optString("player_id",lenovoPlayerId);if(musicIssue!=null&&(musicIssue.startsWith("Głośność: ")||musicIssue.startsWith("Muzyka: ")||musicIssue.startsWith("Przewijanie: ")))musicIssue=null;}
        else if("playing".equals(player.optString("playback_state"))&&!player.optBoolean("hide_in_ui",false)){
            JSONObject media=player.optJSONObject("current_media");
            remoteInfo=player.optString("name","")+(media==null||media.isNull("title")?"":" · "+media.optString("title",""));
        }else if(remoteInfo!=null&&remoteInfo.startsWith(player.optString("name","\u0000")))remoteInfo=null;
        publishMusic();
    }

    private void startHa(){
        String cached=getSharedPreferences("helios",MODE_PRIVATE).getString("dashboard_v2",null);
        JSONObject cachedRaw=null;
        if(cached!=null)try{cachedRaw=new JSONObject(cached);}catch(Exception ignored){}
        ha=new HaDashboardClient(connection,cachedRaw);ha.attach(cache);
        device=new HeliosDeviceClient(ha,installationId,this::telemetry,this::execute,new HeliosDeviceClient.Listener(){
            public void onAppearance(JSONObject a){main.post(()->applyAppearance(a,true));}
            public void onDevice(String id,String area,String name){main.post(()->{
            boolean lost=deviceId!=null&&id==null;deviceId=id;
            if(id!=null){
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
        MusicSession.Ui ui=session==null?MusicSession.Ui.NONE:session.ui();
        return new Telemetry(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,voiceState,dock.dockConnected(),dock.charging(),dock.ledOn(),dock.ledBrightness(),dock.padVersion(),volume.percent(),(SystemClock.elapsedRealtime()-startedAt)/1000,ui==MusicSession.Ui.PLAYING?"playing":ui==MusicSession.Ui.PAUSED?"paused":"none");
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
    /**
     * The only path that changes the connection: validates and authenticates the changed HA data on a temporary socket,
     * and only then persists and restarts the transport. done receives null on success or an error text (main thread).
     * ponytail: HA section only; the music_assistant section joins in 0.6 with the same per-section rule.
     */
    void reconfigure(JSONObject received,Consumer<String> done){
        network.execute(()->{
            java.util.List<String> messages=new java.util.ArrayList<>();
            JSONObject merged=copy(connection);
            boolean haChanged=false,maChanged=false;
            // HA section: verified only when it changed; failure keeps the previous HA data.
            try{
                if(received.optString("token").isEmpty()||received.optString("pipeline").isEmpty())throw new IllegalArgumentException("Niepełne parowanie HA");
                haChanged=connection==null||!sameHa(connection,received);
                String error=haChanged?HaDashboardClient.probe(received):null;
                if(error!=null){messages.add("HA: "+error);haChanged=false;}
                else{for(String key:new String[]{"url","token","pipeline","dashboard_path","diagnostics_url"})if(received.has(key))merged.put(key,received.get(key));else merged.remove(key);}
            }catch(Exception e){messages.add("HA: "+(e.getMessage()==null?"błąd konfiguracji":e.getMessage()));haChanged=false;}
            // MA section: absent means unchanged (never a removal); verified independently of HA.
            JSONObject music=received.optJSONObject("music_assistant");
            if(music!=null){
                JSONObject current=connection==null?null:connection.optJSONObject("music_assistant");
                maChanged=current==null||!current.toString().equals(music.toString());
                if(maChanged){
                    String error=null;
                    String sendspinUrl=music.optString("sendspin_url","");
                    if(!sendspinUrl.isEmpty()&&!sendspinUrl.startsWith("ws://")&&!sendspinUrl.startsWith("wss://"))error="Adres Sendspin musi zaczynać się od ws:// lub wss://";
                    if(error==null)error=MusicAssistantClient.probe(music.optString("url",""),music.optString("token",""));
                    if(error!=null){messages.add("MA: "+error);maChanged=false;}
                    else try{merged.put("music_assistant",new JSONObject(music.toString()));}catch(Exception ignored){}
                }
            }
            if(merged.has("url")&&(haChanged||maChanged||connection==null)){
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("connection",merged.toString()).apply();
                connection=merged;
            }
            final boolean restartHa=haChanged,restartMa=maChanged;
            main.post(()->{
                if(restartHa){resetAppearance();stopHa();startHa();}
                if(restartMa){stopMusic();startMusic();}
                done.accept(messages.isEmpty()?null:String.join("; ",messages));
            });
        });
    }
    private static JSONObject copy(JSONObject source){try{return source==null?new JSONObject():new JSONObject(source.toString());}catch(Exception e){return new JSONObject();}}
    static boolean sameHa(JSONObject a,JSONObject b){
        for(String key:new String[]{"url","token","pipeline","dashboard_path"})if(!a.optString(key,"").equals(b.optString(key,"")))return false;
        return true;
    }
}
