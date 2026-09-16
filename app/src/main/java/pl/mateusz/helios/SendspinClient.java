package pl.mateusz.helios;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 * Minimal Sendspin legacy (version 1, cleartext) client for Music Assistant: player, metadata, artwork and controller roles.
 * Audio starts only after the server's stream/start; the client never sends play on its own, and nothing is queued offline.
 */
final class SendspinClient {
    enum State {NONE,PLAYING,PAUSED}
    static final class Metadata {
        final String title,artist,album,artworkUrl;final long progressMs,durationMs;
        Metadata(String title,String artist,String album,String artworkUrl,long progressMs,long durationMs){this.title=title;this.artist=artist;this.album=album;this.artworkUrl=artworkUrl;this.progressMs=progressMs;this.durationMs=durationMs;}
    }
    /** aiosendspin delta contract: an absent key keeps the value, JSON null clears it, progress is replaced whole or cleared; -1 = no progress. */
    static final class MetadataState {
        String title,artist,album,artworkUrl;long progressMs=-1,durationMs=-1;
        void apply(JSONObject m){
            title=text(m,"title",title);artist=text(m,"artist",artist);album=text(m,"album",album);artworkUrl=text(m,"artwork_url",artworkUrl);
            if(m.has("progress")){
                JSONObject p=m.optJSONObject("progress");
                progressMs=p==null?-1:p.optLong("track_progress",-1);durationMs=p==null?-1:p.optLong("track_duration",-1);
            }
        }
        private static String text(JSONObject m,String key,String previous){return !m.has(key)?previous:m.isNull(key)?null:m.optString(key,null);}
        void reset(){title=null;artist=null;album=null;artworkUrl=null;progressMs=-1;durationMs=-1;}
        Metadata snapshot(){return new Metadata(title,artist,album,artworkUrl,progressMs,durationMs);}
    }
    interface Listener {
        void onState(State state);
        void onMetadata(Metadata metadata);
        void onController(List<String> supportedCommands,Integer groupVolume,Boolean groupMuted);
        void onArtwork(byte[] jpeg);
        void onPlayer(int volume,boolean muted);
        void onConnection(boolean connected,String detail);
        /** Raw protocol trace for diagnostics: stream/start|end|clear, group/update with its playback_state, server/hello. */
        default void onProtocol(String detail){}
    }
    static final int PROTOCOL_VERSION=1;
    static final int MAX_TEXT=262144,MAX_BINARY=1048576,BUFFER_CAPACITY=262144;
    static final long LEAD_MICROS=200_000,LATE_MICROS=50_000,DRAIN_LIMIT_MICROS=2_000_000,PLAYING_WINDOW_MICROS=1_500_000;
    static volatile long SYNC_INTERVAL_MS=10_000;
    private static final byte FRAME_AUDIO=0x04,FRAME_ARTWORK_0=0x08;

    private final String url,clientId,name,softwareVersion;
    private final AudioSink sink;
    private final Listener listener;
    private final ClockOffset clock=new ClockOffset();
    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread worker,player;
    private final PriorityBlockingQueue<Chunk> queue=new PriorityBlockingQueue<>(64,Comparator.comparingLong(c->c.serverMicros));
    private volatile long queuedBytes;
    private volatile int dropped;
    private volatile String playbackState="stopped";
    private volatile long lastWriteMicros=-1;
    private volatile boolean streamOpen,endRequested;
    private volatile long endRequestedAt;
    private volatile String[] format;
    private volatile String[] pendingFormat;
    private volatile List<String> supportedCommands=Collections.emptyList();
    private volatile int volume=100;private volatile boolean muted;
    private volatile State state=State.NONE;
    /** Logical UI session, separate from the buffer: starts with the first audio frame after stream/start, ends only on stopped/idle, disconnect or stop(). WebSocket thread only. */
    private boolean sessionActive;
    private final MetadataState metadataState=new MetadataState();
    private static final class Chunk {final long serverMicros;final byte[] pcm;Chunk(long serverMicros,byte[] pcm){this.serverMicros=serverMicros;this.pcm=pcm;}}

    SendspinClient(String url,String clientId,String name,String softwareVersion,AudioSink sink,Listener listener){
        this.url=url;this.clientId=clientId;this.name=name;this.softwareVersion=softwareVersion;this.sink=sink;this.listener=listener;
    }
    void start(){worker=new Thread(this::loop,"helios-sendspin");worker.start();player=new Thread(this::playback,"helios-sendspin-audio");player.setPriority(Thread.MAX_PRIORITY);player.start();}
    void stop(){
        stopped=true;
        Socket s=socket;
        if(s!=null){try{s.send(new JSONObject().put("type","client/goodbye").put("payload",new JSONObject().put("reason","user_request")).toString());}catch(Exception ignored){}s.close();}
        if(worker!=null)worker.interrupt();if(player!=null)player.interrupt();
    }
    State state(){return state;}
    int dropped(){return dropped;}
    List<String> supportedCommands(){return supportedCommands;}
    boolean connected(){Socket s=socket;return s!=null&&s.helloDone;}
    /** Transport controls only (play, pause, next, previous, stop); volume goes through the MA API to this player, never to the group. */
    boolean command(String command){
        Socket s=socket;
        if(s==null||!s.helloDone||!supportedCommands.contains(command)||command.equals("volume")||command.equals("mute"))return false;
        try{s.send(new JSONObject().put("type","client/command").put("payload",new JSONObject().put("controller",new JSONObject().put("command",command))).toString());return true;}
        catch(Exception e){return false;}
    }

    private final class Socket extends WebSocketClient {
        final BlockingQueue<Object> inbox=new LinkedBlockingQueue<>(512);
        volatile boolean failed,helloDone;
        Socket(URI uri){super(uri);setConnectionLostTimeout(15);}
        @Override public void onOpen(ServerHandshake h){}
        @Override public void onMessage(String text){if(text.length()>MAX_TEXT||!inbox.offer(text))failed=true;}
        @Override public void onMessage(ByteBuffer bytes){
            if(bytes.remaining()>MAX_BINARY||bytes.remaining()<9)return;
            byte[] copy=new byte[bytes.remaining()];bytes.get(copy);
            if(!inbox.offer(copy))failed=true;
        }
        @Override public void onClose(int code,String reason,boolean remote){failed=true;}
        @Override public void onError(Exception e){failed=true;}
    }

    private void loop(){
        int delay=2;
        while(!stopped){
            try{session();delay=2;}
            catch(InterruptedException e){break;}
            catch(Exception e){if(!stopped)listener.onConnection(false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
            finally{Socket old=socket;socket=null;if(old!=null)old.close();endSession();}
            if(stopped)break;
            try{Thread.sleep(delay*1000L);}catch(InterruptedException e){break;}
            delay=Math.min(30,delay*2);
        }
    }
    private void session() throws Exception {
        Socket s=new Socket(new URI(url));socket=s;
        if(!s.connectBlocking(10,TimeUnit.SECONDS))throw new java.io.IOException("Sendspin: brak połączenia");
        s.send(hello().toString());
        long nextSync=0;int burst=5;
        while(!stopped){
            if(s.failed)throw new java.io.IOException("Sendspin: połączenie przerwane");
            long now=System.currentTimeMillis();
            if(s.helloDone&&now>=nextSync){
                s.send(new JSONObject().put("type","client/time").put("payload",new JSONObject().put("client_transmitted",micros())).toString());
                nextSync=now+(burst>0?200:SYNC_INTERVAL_MS);if(burst>0)burst--;
            }
            Object message=s.inbox.poll(100,TimeUnit.MILLISECONDS);
            if(message==null)continue;
            if(message instanceof byte[])binary((byte[])message);else text(s,(String)message);
        }
    }
    private JSONObject hello() throws JSONException {
        JSONObject support=new JSONObject()
            .put("supported_formats",new JSONArray().put(new JSONObject().put("codec","pcm").put("channels",2).put("sample_rate",48000).put("bit_depth",16)))
            .put("buffer_capacity",BUFFER_CAPACITY).put("supported_commands",new JSONArray().put("volume").put("mute"));
        JSONObject payload=new JSONObject().put("client_id",clientId).put("name",name).put("version",PROTOCOL_VERSION)
            .put("device_info",new JSONObject().put("manufacturer","Lenovo").put("product_name","Smart Clock 2").put("software_version",softwareVersion))
            // ponytail: no artwork@v1 - MA 2.10.3 (aiosendspin 9.1.1) closes a legacy connection that advertises it (verified 2026-09-15); the cover comes from metadata.artwork_url via the MA host.
            .put("supported_roles",new JSONArray().put("player@v1").put("metadata@v1").put("controller@v1"))
            .put("player@v1_support",support).put("metadata@v1_support",new JSONObject()).put("controller@v1_support",new JSONObject());
        return new JSONObject().put("type","client/hello").put("payload",payload);
    }
    private static long micros(){return System.nanoTime()/1000;}

    private void text(Socket s,String raw) throws Exception {
        JSONObject message=new JSONObject(raw);String type=message.optString("type");JSONObject payload=message.optJSONObject("payload");
        if(payload==null)payload=new JSONObject();
        switch(type){
            case "server/hello":{
                JSONArray roles=payload.optJSONArray("active_roles");List<String> active=new ArrayList<>();
                if(roles!=null)for(int i=0;i<roles.length();i++)active.add(roles.getString(i));
                s.helloDone=true;clock.reset();metadataState.reset();
                listener.onConnection(true,active.contains("player@v1")?"connected":"MA nie aktywował roli odtwarzacza");
                break;}
            case "server/time":{
                long t4=micros();
                clock.sample(payload.getLong("client_transmitted"),payload.getLong("server_received"),payload.getLong("server_transmitted"),t4);
                break;}
            case "stream/start":{listener.onProtocol("stream/start");
                JSONObject p=payload.optJSONObject("player");
                if(p!=null)streamStart(new String[]{p.optString("codec","pcm"),String.valueOf(p.optInt("sample_rate",48000)),String.valueOf(p.optInt("channels",2)),String.valueOf(p.optInt("bit_depth",16))});
                break;}
            case "stream/clear":listener.onProtocol("stream/clear");if(affectsPlayer(payload)){queue.clear();queuedBytes=0;sink.flush();}break;
            case "stream/end":listener.onProtocol("stream/end");if(affectsPlayer(payload)){endRequested=true;endRequestedAt=micros();}break;
            case "group/update":
                if(payload.has("playback_state")){
                    playbackState=payload.getString("playback_state");listener.onProtocol("group/update "+playbackState+(sessionActive?" session":" no-session"));
                    if(playbackState.equals("stopped")||playbackState.equals("idle"))sessionActive=false;
                    refreshState();
                }
                break;
            case "server/state":{
                JSONObject metadata=payload.optJSONObject("metadata");
                if(metadata!=null){metadataState.apply(metadata);listener.onMetadata(metadataState.snapshot());}
                JSONObject controller=payload.optJSONObject("controller");
                if(controller!=null){
                    JSONArray commands=controller.optJSONArray("supported_commands");List<String> list=new ArrayList<>();
                    if(commands!=null)for(int i=0;i<commands.length();i++)list.add(commands.getString(i));
                    supportedCommands=Collections.unmodifiableList(list);
                    listener.onController(supportedCommands,controller.has("volume")&&!controller.isNull("volume")?controller.getInt("volume"):null,controller.has("muted")&&!controller.isNull("muted")?controller.getBoolean("muted"):null);
                }
                break;}
            case "server/command":{
                JSONObject p=payload.optJSONObject("player");
                if(p!=null){
                    String command=p.optString("command");
                    listener.onProtocol("server/command "+p.toString());
                    if(command.equals("volume")&&p.has("volume")){volume=Math.max(0,Math.min(100,p.getInt("volume")));sink.setGain(volume/100f);}
                    else if(command.equals("mute")&&p.has("mute")){muted=p.getBoolean("mute");sink.setMuted(muted);}
                    else break;
                    listener.onPlayer(volume,muted);
                    s.send(new JSONObject().put("type","client/state").put("payload",new JSONObject().put("player",new JSONObject().put("volume",volume).put("muted",muted).put("output_delay_ms",0))).toString());
                }
                break;}
            default:break; // unknown message types are ignored on purpose
        }
    }
    private static boolean affectsPlayer(JSONObject payload) throws JSONException {
        JSONArray roles=payload.optJSONArray("roles");if(roles==null)return true;
        for(int i=0;i<roles.length();i++)if(roles.getString(i).equals("player"))return true;
        return false;
    }
    private void binary(byte[] frame){
        long serverMicros=ByteBuffer.wrap(frame,1,8).getLong();
        if(frame[0]==FRAME_AUDIO){
            if(!streamOpen&&pendingFormat==null)return;
            byte[] pcm=Arrays.copyOfRange(frame,9,frame.length);
            if(queuedBytes+pcm.length>BUFFER_CAPACITY){dropped++;return;}
            queue.add(new Chunk(serverMicros,pcm));queuedBytes+=pcm.length;
            if(!sessionActive){sessionActive=true;refreshState();} // real local audio arrived: the session exists from here on
        }else if(frame[0]==FRAME_ARTWORK_0){
            listener.onArtwork(frame.length>9?Arrays.copyOfRange(frame,9,frame.length):null);
        }
    }
    private synchronized void streamStart(String[] newFormat){
        if(streamOpen&&Arrays.equals(newFormat,format)){endRequested=false;return;} // same format: the next track continues on the open output
        if(streamOpen){pendingFormat=newFormat;endRequested=true;endRequestedAt=micros();return;}
        try{sink.open(newFormat[0],Integer.parseInt(newFormat[1]),Integer.parseInt(newFormat[2]),Integer.parseInt(newFormat[3]));format=newFormat;streamOpen=true;endRequested=false;}
        catch(Exception e){listener.onConnection(true,"Nie można otworzyć wyjścia audio: "+e.getMessage());}
    }
    private synchronized void closeStream(){
        queue.clear();queuedBytes=0;
        if(streamOpen)sink.stop();
        streamOpen=false;endRequested=false;format=null;
        if(pendingFormat!=null){String[] next=pendingFormat;pendingFormat=null;streamStart(next);}
    }
    private void endSession(){
        closeStream();pendingFormat=null;playbackState="stopped";lastWriteMicros=-1;clock.reset();supportedCommands=Collections.emptyList();
        sessionActive=false;metadataState.reset();
        refreshState();
    }
    /** Playback thread: chunks leave in timestamp order once the clock is known; late ones are dropped, early ones wait. */
    private void playback(){
        while(!stopped){
            try{
                Chunk head=queue.peek();
                if(head==null){
                    if(endRequested&&streamOpen&&(queue.isEmpty()))closeStream();
                    Thread.sleep(10);continue;
                }
                if(!clock.known()){Thread.sleep(20);continue;}
                long target=clock.toLocalMicros(head.serverMicros),now=micros();
                if(endRequested&&now-endRequestedAt>DRAIN_LIMIT_MICROS){closeStream();continue;}
                if(now>target+LATE_MICROS){queue.poll();queuedBytes-=head.pcm.length;dropped++;continue;}
                if(now<target-LEAD_MICROS){Thread.sleep(Math.min(20,(target-LEAD_MICROS-now)/1000+1));continue;}
                queue.poll();queuedBytes-=head.pcm.length;
                if(streamOpen){sink.write(head.pcm,0,head.pcm.length);lastWriteMicros=micros();}
            }catch(InterruptedException e){break;}
            catch(Exception e){dropped++;}
        }
    }
    /** Single publisher of onState (WebSocket thread): stream/end and buffer drain never touch the session, so pause and end may arrive in any order without a NONE in between. */
    private synchronized void refreshState(){
        boolean active=sessionActive;String playback=playbackState;
        State next=!active?State.NONE:"playing".equals(playback)?State.PLAYING:"paused".equals(playback)?State.PAUSED:State.NONE;
        if(next!=state){state=next;listener.onState(next);}
    }
}
