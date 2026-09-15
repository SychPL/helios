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
        final MusicSession.Ui ui;final String title,artist,album,remoteInfo,issue;final byte[] artwork;final int volume;final boolean muted,maConnected,localConnected;final java.util.List<String> commands;
        MusicSnapshot(MusicSession.Ui ui,String title,String artist,String album,byte[] artwork,int volume,boolean muted,java.util.List<String> commands,boolean maConnected,boolean localConnected,String remoteInfo,String issue){
            this.ui=ui;this.title=title;this.artist=artist;this.album=album;this.artwork=artwork;this.volume=volume;this.muted=muted;this.commands=commands;this.maConnected=maConnected;this.localConnected=localConnected;this.remoteInfo=remoteInfo;this.issue=issue;
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
    private byte[] musicArtwork;
    private int musicVolume=100;private boolean musicMuted,maConnected,localConnected;
    private java.util.List<String> musicCommands=java.util.Collections.emptyList();
    private MusicListener musicListener;
    private final RecentPlays recent=new RecentPlays();
    private Runnable onDeviceLost;
    private Consumer<String> onDeviceChanged;
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
    void setVoiceState(String state){if(!state.equals(voiceState)){voiceState=state;publish();}}
    void setOnDeviceLost(Runnable action){onDeviceLost=action;}
    void setOnDeviceChanged(Consumer<String> action){onDeviceChanged=action;}
    void setDiagnostics(java.util.function.BiConsumer<String,String> sink){diagnostics=sink;}
    void publish(){if(device!=null)device.publish();}
    /** Our own conversation finished: the sink may resume only if the system grants focus again. */
    void onVoiceReady(){if(session!=null){session.onVoiceReady(this::requestMusicFocus);publishMusic();}}

    // --- music ---
    boolean musicConfigured(){return connection!=null&&connection.optJSONObject("music_assistant")!=null;}
    void setMusicListener(MusicListener listener){musicListener=listener;if(listener!=null)listener.onMusic(musicSnapshot());}
    MusicSnapshot musicSnapshot(){
        return new MusicSnapshot(session==null?MusicSession.Ui.NONE:session.ui(),musicTitle,musicArtist,musicAlbum,musicArtwork,musicVolume,musicMuted,musicCommands,maConnected,localConnected,remoteInfo,musicIssue);
    }
    MusicAssistantClient ma(){return ma;}
    String lenovoPlayerId(){return lenovoPlayerId;}
    RecentPlays recent(){return recent;}
    void rememberPlay(RecentPlays.Entry entry){recent.add(entry);getSharedPreferences("helios",MODE_PRIVATE).edit().putString("music_recent",recent.serialize()).apply();}
    private void publishMusic(){if(musicListener!=null)musicListener.onMusic(musicSnapshot());}
    private boolean requestMusicFocus(){
        int result=audioManager.requestAudioFocus(musicFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
        musicFocusHeld=result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;return musicFocusHeld;
    }
    private void abandonMusicFocus(){if(musicFocusHeld){audioManager.abandonAudioFocus(musicFocus);musicFocusHeld=false;}}
    /** Local overlay controls. Transport commands go over Sendspin when the server advertises them, else through the MA API; volume always through the MA API to this player. */
    void musicCommand(String command,Consumer<String> done){
        if(command.equals("play")&&session!=null&&!session.onUserPlay(this::requestMusicFocus)){done.accept("Głośnik jest zajęty przez inną aplikację");return;}
        if(sendspin!=null&&sendspin.command(command)){done.accept(null);return;}
        if(ma==null||lenovoPlayerId==null){done.accept("Brak połączenia z Music Assistant");return;}
        ma.playerCommand(lenovoPlayerId,command,r->main.post(()->done.accept(null)),e->main.post(()->done.accept(e)));
    }
    void musicVolume(int level,Consumer<String> done){
        if(ma==null||lenovoPlayerId==null){done.accept("Brak połączenia z Music Assistant");return;}
        ma.volume(lenovoPlayerId,level,r->main.post(()->done.accept(null)),e->main.post(()->done.accept(e)));
    }
    void musicMute(boolean muted,Consumer<String> done){
        if(ma==null||lenovoPlayerId==null){if(sink!=null){sink.setMuted(muted);musicMuted=muted;publishMusic();done.accept(null);}else done.accept("Brak odtwarzacza");return;}
        ma.mute(lenovoPlayerId,muted,r->main.post(()->done.accept(null)),e->main.post(()->done.accept(e)));
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
                public void onState(SendspinClient.State state){main.post(()->{
                    if(session==null)return;
                    MusicSession.Ui before=session.ui();session.onTransport(state);
                    if(state!=SendspinClient.State.NONE&&before==MusicSession.Ui.NONE)requestMusicFocus();
                    if(state==SendspinClient.State.NONE){abandonMusicFocus();musicArtwork=null;}
                    publishMusic();
                });}
                public void onMetadata(SendspinClient.Metadata m){main.post(()->{musicTitle=m.title;musicArtist=m.artist;musicAlbum=m.album;publishMusic();});fetchArtwork(m.artworkUrl);}
                public void onController(java.util.List<String> commands,Integer groupVolume,Boolean groupMuted){main.post(()->{musicCommands=commands;publishMusic();});}
                public void onArtwork(byte[] jpeg){main.post(()->{musicArtwork=jpeg;publishMusic();});}
                public void onPlayer(int volume,boolean muted){main.post(()->{musicVolume=volume;musicMuted=muted;publishMusic();});}
                public void onConnection(boolean connected,String detail){if(diagnostics!=null)diagnostics.accept("sendspin_"+(connected?"connected":"down"),detail==null?"":detail);main.post(()->{localConnected=connected;musicIssue=connected&&"connected".equals(detail)?null:detail;publishMusic();});}
            });
            sendspin.start();
        }
        ma=new MusicAssistantClient(MusicAssistantClient.wsUrl(music.optString("url","")),music.optString("token",""),new MusicAssistantClient.Listener(){
            public void onConnection(boolean connected,String detail){if(diagnostics!=null)diagnostics.accept("ma_"+(connected?"connected":"down"),detail==null?"":detail);main.post(()->{maConnected=connected;if(connected)findLenovo();publishMusic();});}
            public void onPlayerUpdated(JSONObject player){main.post(()->trackPlayer(player));}
        });
        ma.start();
    }
    private volatile String artworkUrlInFlight;
    /** Cover art only from the MA host (image proxy or a same-host URL); other hosts are ignored so the token never leaks and nothing foreign is fetched. */
    private void fetchArtwork(String url){
        if(url==null||url.isEmpty()){main.post(()->{musicArtwork=null;publishMusic();});return;}
        JSONObject music=connection==null?null:connection.optJSONObject("music_assistant");
        if(music==null)return;
        String base=music.optString("url","").replaceAll("/+$","");
        String target=url.startsWith("http")?url:base+(url.startsWith("/")?"":"/")+url;
        try{java.net.URI t=new java.net.URI(target),b=new java.net.URI(base);if(t.getHost()==null||!t.getHost().equalsIgnoreCase(b.getHost()))return;}catch(Exception e){return;}
        artworkUrlInFlight=target;
        network.execute(()->{
            java.net.HttpURLConnection c=null;
            try{
                c=(java.net.HttpURLConnection)new java.net.URL(target).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(8000);c.setInstanceFollowRedirects(false);
                if(c.getResponseCode()!=200)return;
                try(java.io.InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                    byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(out.size()+n>1048576)return;out.write(chunk,0,n);}
                    byte[] bytes=out.toByteArray();
                    main.post(()->{if(target.equals(artworkUrlInFlight)){musicArtwork=bytes;publishMusic();}});
                }
            }catch(Exception ignored){}
            finally{if(c!=null)c.disconnect();}
        });
    }
    private void stopMusic(){
        if(sendspin!=null){sendspin.stop();sendspin=null;}
        if(ma!=null){ma.stop();ma=null;}
        if(sink!=null){sink.stop();sink=null;}
        abandonMusicFocus();session=null;lenovoPlayerId=null;maConnected=false;localConnected=false;remoteInfo=null;musicArtwork=null;musicTitle=null;musicArtist=null;musicAlbum=null;
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
        if(lenovo)lenovoPlayerId=player.optString("player_id",lenovoPlayerId);
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
        device=new HeliosDeviceClient(ha,installationId,this::telemetry,this::execute,(id,area,name)->main.post(()->{
            boolean lost=deviceId!=null&&id==null;deviceId=id;
            if(name!=null&&!name.equals(getSharedPreferences("helios",MODE_PRIVATE).getString("device_name",null))){
                getSharedPreferences("helios",MODE_PRIVATE).edit().putString("device_name",name).apply();
                if(diagnostics!=null)diagnostics.accept("device_name",name);
                if(ma!=null||sendspin!=null){stopMusic();startMusic();} // the MA player carries the HA device name
            }
            if(lost&&onDeviceLost!=null)onDeviceLost.run();
            if(onDeviceChanged!=null)onDeviceChanged.accept(id);
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
                if(restartHa){stopHa();startHa();}
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
