package pl.mateusz.helios;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Music Assistant WebSocket API (2.10.3): auth first, message_id correlation, player events.
 * One request in flight per lane (players, play, cmd); a new search supersedes the previous one; stop is never blocked.
 * The token is sent only to the configured host and never logged.
 */
final class MusicAssistantClient {
    interface Listener {
        void onConnection(boolean connected,String detail);
        void onPlayerUpdated(JSONObject player);
    }
    static volatile long TIMEOUT_MS=10_000;
    static final int SEARCH_LIMIT=50,QUERY_MIN=2,QUERY_MAX=100;
    private final String url,token;
    private final Listener listener;
    private volatile boolean stopped,authenticated;
    private volatile Socket socket;
    private Thread worker;
    private int nextId=1;
    private final Map<String,Pending> pending=new ConcurrentHashMap<>();
    private final Map<String,String> lanes=new ConcurrentHashMap<>(); // lane -> message id in flight
    private static final class Pending {
        final String lane;final Consumer<Object> ok;final Consumer<String> fail;final long deadline;
        Pending(String lane,Consumer<Object> ok,Consumer<String> fail,long deadline){this.lane=lane;this.ok=ok;this.fail=fail;this.deadline=deadline;}
    }

    MusicAssistantClient(String url,String token,Listener listener){this.url=url;this.token=token;this.listener=listener;}
    /** http(s)://host:8095 -> ws(s)://host:8095/ws */
    static String wsUrl(String base){String b=base.replaceAll("/+$","");return b.replaceFirst("^http","ws")+"/ws";}
    /** Authenticates once on a temporary socket; null on success, otherwise an error text without the token. */
    static String probe(String base,String token){
        MusicAssistantClient[] holder=new MusicAssistantClient[1];
        BlockingQueue<String> outcome=new LinkedBlockingQueue<>();
        try{
            String ws=wsUrl(base);
            if(!ws.startsWith("ws://")&&!ws.startsWith("wss://"))return "Adres MA musi zaczynać się od http:// lub https://";
            if(token==null||token.trim().isEmpty())return "Pusty token MA";
            holder[0]=new MusicAssistantClient(ws,token,new Listener(){
                public void onConnection(boolean connected,String detail){outcome.offer(connected?"":detail);}
                public void onPlayerUpdated(JSONObject player){}
            });
            holder[0].start();
            String result=outcome.poll(12,TimeUnit.SECONDS);
            if(result==null)return "Music Assistant nie odpowiada";
            return result.isEmpty()?null:result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return "Przerwano";}
        finally{if(holder[0]!=null)holder[0].stop();}
    }
    void start(){worker=new Thread(this::loop,"helios-ma");worker.start();}
    void stop(){stopped=true;Socket s=socket;if(s!=null)s.close();if(worker!=null)worker.interrupt();}
    boolean connected(){return authenticated;}
    String baseUrl(){return url.replaceFirst("^ws","http").replaceAll("/ws/?$","");}
    /** Artwork always goes through MA's image proxy on the configured host, so the token never reaches a third party. */
    String imageUrl(String path,String provider,int size){
        try{return baseUrl()+"/imageproxy?path="+java.net.URLEncoder.encode(path,"UTF-8")+"&provider="+java.net.URLEncoder.encode(provider,"UTF-8")+"&size="+size;}
        catch(Exception e){return null;}
    }

    void players(Consumer<JSONArray> ok,Consumer<String> fail){call("players/all",new JSONObject(),"players",r->ok.accept(r instanceof JSONArray?(JSONArray)r:new JSONArray()),fail);}
    void search(String query,Consumer<JSONObject> ok,Consumer<String> fail){
        String q=query==null?"":query.trim();
        if(q.length()<QUERY_MIN||q.length()>QUERY_MAX){fail.accept("Wpisz od 2 do 100 znaków");return;}
        try{
            JSONObject args=new JSONObject().put("search_query",q).put("limit",SEARCH_LIMIT).put("providers",new JSONArray().put("library"))
                .put("media_types",new JSONArray().put("track").put("album").put("playlist").put("radio"));
            call("music/search",args,"search",r->ok.accept(r instanceof JSONObject?(JSONObject)r:new JSONObject()),fail);
        }catch(JSONException e){fail.accept("Błąd zapytania");}
    }
    void activeQueue(String playerId,Consumer<JSONObject> ok,Consumer<String> fail){
        try{call("player_queues/get_active_queue",new JSONObject().put("player_id",playerId),"players",r->ok.accept(r instanceof JSONObject?(JSONObject)r:null),fail);}catch(JSONException e){fail.accept("Błąd");}
    }
    /** Tracks play now; albums, playlists and radio replace the queue and start from the first item. */
    void playMedia(String queueId,String uri,boolean replace,Consumer<Object> ok,Consumer<String> fail){
        try{call("player_queues/play_media",new JSONObject().put("queue_id",queueId).put("media",uri).put("option",replace?"replace":"play"),"play",ok,fail);}catch(JSONException e){fail.accept("Błąd");}
    }
    /** play, pause, next, previous; stop has its own lane and is never blocked by a pending command (SPEC 0.6 pkt 9). */
    void playerCommand(String playerId,String command,Consumer<Object> ok,Consumer<String> fail){
        if(!Arrays.asList("play","pause","next","previous","stop").contains(command)){fail.accept("Nieobsługiwane polecenie");return;}
        try{call("players/cmd/"+command,new JSONObject().put("player_id",playerId),command.equals("stop")?"stop":"cmd",ok,fail);}catch(JSONException e){fail.accept("Błąd");}
    }
    void volume(String playerId,int level,Consumer<Object> ok,Consumer<String> fail){
        try{call("players/cmd/volume_set",new JSONObject().put("player_id",playerId).put("volume_level",Math.max(0,Math.min(100,level))),"cmd",ok,fail);}catch(JSONException e){fail.accept("Błąd");}
    }
    void mute(String playerId,boolean muted,Consumer<Object> ok,Consumer<String> fail){
        try{call("players/cmd/volume_mute",new JSONObject().put("player_id",playerId).put("muted",muted),"cmd",ok,fail);}catch(JSONException e){fail.accept("Błąd");}
    }

    synchronized void call(String command,JSONObject args,String lane,Consumer<Object> ok,Consumer<String> fail){
        Socket s=socket;
        if(s==null||!authenticated||s.failed){fail.accept("Brak połączenia z Music Assistant");return;}
        String previous=lanes.get(lane);
        if(previous!=null&&pending.containsKey(previous)){
            if(lane.equals("search")){Pending old=pending.remove(previous);if(old!=null)old.fail.accept("superseded");}
            else if(!lane.equals("stop")){fail.accept("Poprzednie polecenie jeszcze trwa");return;}
        }
        String id=String.valueOf(nextId++);
        pending.put(id,new Pending(lane,ok,fail,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)));
        if(!lane.equals("stop"))lanes.put(lane,id);
        try{s.send(new JSONObject().put("message_id",id).put("command",command).put("args",args).toString());}
        catch(Exception e){pending.remove(id);fail.accept("Nie udało się wysłać polecenia");}
    }
    private void finish(String id,Object result,String error){
        Pending p=pending.remove(id);if(p==null)return;
        if(error==null)p.ok.accept(result);else p.fail.accept(error);
    }
    private void failAll(String reason){for(String id:new ArrayList<>(pending.keySet()))finish(id,null,reason);}
    private void expire(){
        long now=System.nanoTime();
        for(Map.Entry<String,Pending> e:new ArrayList<>(pending.entrySet()))if(now>e.getValue().deadline)finish(e.getKey(),null,"Music Assistant nie odpowiedział w czasie "+(TIMEOUT_MS/1000)+" s");
    }

    private final class Socket extends WebSocketClient {
        final BlockingQueue<String> inbox=new LinkedBlockingQueue<>(256);
        volatile boolean failed;
        Socket(URI uri){super(uri);setConnectionLostTimeout(15);}
        @Override public void onOpen(ServerHandshake h){}
        @Override public void onMessage(String text){if(text.length()>1_048_576||!inbox.offer(text))failed=true;}
        @Override public void onClose(int code,String reason,boolean remote){failed=true;}
        @Override public void onError(Exception e){failed=true;}
    }
    private void loop(){
        int delay=2;
        while(!stopped){
            try{session();delay=2;}
            catch(InterruptedException e){break;}
            catch(Exception e){if(!stopped)listener.onConnection(false,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
            finally{authenticated=false;Socket old=socket;socket=null;if(old!=null)old.close();failAll("Połączenie z Music Assistant przerwane");lanes.clear();}
            if(stopped)break;
            try{Thread.sleep(delay*1000L);}catch(InterruptedException e){break;}
            delay=Math.min(30,delay*2);
        }
    }
    private void session() throws Exception {
        URI base=new URI(url);
        if(!"ws".equals(base.getScheme())&&!"wss".equals(base.getScheme()))throw new java.io.IOException("Adres MA musi zaczynać się od ws:// lub wss://");
        Socket s=new Socket(base);socket=s;
        if(!s.connectBlocking(10,TimeUnit.SECONDS))throw new java.io.IOException("Music Assistant nie odpowiada");
        String authId="auth-"+System.nanoTime();
        s.send(new JSONObject().put("message_id",authId).put("command","auth").put("args",new JSONObject().put("token",token)).toString());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!authenticated){
            if(s.failed)throw new java.io.IOException("Music Assistant rozłączył");
            if(System.nanoTime()>deadline)throw new java.io.IOException("Music Assistant nie odpowiedział na auth");
            String raw=s.inbox.poll(200,TimeUnit.MILLISECONDS);if(raw==null)continue;
            JSONObject m=new JSONObject(raw);
            if(authId.equals(m.optString("message_id"))){
                JSONObject result=m.optJSONObject("result");
                if(result==null||!result.optBoolean("authenticated",false))throw new java.io.IOException("Music Assistant odrzucił token");
                authenticated=true;
            }
        }
        listener.onConnection(true,"connected");
        while(!stopped){
            if(s.failed)throw new java.io.IOException("Połączenie z Music Assistant przerwane");
            expire();
            String raw=s.inbox.poll(200,TimeUnit.MILLISECONDS);if(raw==null)continue;
            JSONObject m=new JSONObject(raw);
            if(m.has("message_id")){
                String id=m.optString("message_id");
                if(m.has("error_code")||m.has("error"))finish(id,null,m.optString("details",m.optString("error_code",m.optString("error","Błąd Music Assistant"))));
                else finish(id,m.opt("result"),null);
            }else if("player_updated".equals(m.optString("event"))){
                JSONObject data=m.optJSONObject("data");if(data!=null)listener.onPlayerUpdated(data);
            }
        }
    }
}
