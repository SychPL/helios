package pl.mateusz.helios;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.*;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SendspinClientTest {
    /** Server clock runs OFFSET microseconds ahead of the client's System.nanoTime clock. */
    static final long OFFSET=5_000_000_000L;
    static long serverNow(){return System.nanoTime()/1000+OFFSET;}

    static final class FakeSink implements AudioSink {
        final List<String> events=new CopyOnWriteArrayList<>();
        final BlockingQueue<byte[]> writes=new LinkedBlockingQueue<>();
        volatile boolean open;volatile float gain=1f;volatile boolean muted;volatile String format;
        public void open(String codec,int sampleRate,int channels,int bitDepth){open=true;format=codec+"/"+sampleRate+"/"+channels+"/"+bitDepth;events.add("open:"+format);}
        public boolean isOpen(){return open;}
        public void write(byte[] pcm,int offset,int length){writes.add(Arrays.copyOfRange(pcm,offset,offset+length));events.add("write:"+pcm[0]);}
        public void flush(){events.add("flush");}
        public void stop(){open=false;events.add("stop");}
        public void setGain(float value){gain=value;events.add("gain:"+value);}
        public void setMuted(boolean value){muted=value;events.add("muted:"+value);}
        public void pause(){events.add("pause");}
        public void resume(){events.add("resume");}
        public long writtenFrames(){return writes.size();}
    }
    static class Server extends WebSocketServer {
        final CountDownLatch ready=new CountDownLatch(1);
        final BlockingQueue<JSONObject> hellos=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> commands=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> states=new LinkedBlockingQueue<>();
        final BlockingQueue<JSONObject> goodbyes=new LinkedBlockingQueue<>();
        volatile WebSocket client;
        Server(){super(new InetSocketAddress("127.0.0.1",0));}
        @Override public void onStart(){ready.countDown();}
        @Override public void onOpen(WebSocket ws,ClientHandshake handshake){client=ws;}
        @Override public void onClose(WebSocket ws,int code,String reason,boolean remote){}
        @Override public void onError(WebSocket ws,Exception ex){}
        @Override public void onMessage(WebSocket ws,String message){
            try{
                JSONObject m=new JSONObject(message);String type=m.getString("type");JSONObject payload=m.optJSONObject("payload");
                switch(type){
                    case "client/hello":hellos.add(payload);send("server/hello",new JSONObject().put("server_id","srv").put("name","Fake MA").put("version",1).put("active_roles",new JSONArray().put("player@v1").put("metadata@v1").put("artwork@v1").put("controller@v1")));break;
                    case "client/time":{long t=serverNow();send("server/time",new JSONObject().put("client_transmitted",payload.getLong("client_transmitted")).put("server_received",t).put("server_transmitted",t+50));break;}
                    case "client/command":commands.add(payload);break;
                    case "client/state":states.add(payload);break;
                    case "client/goodbye":goodbyes.add(payload);break;
                    default:break;
                }
            }catch(Exception e){ws.close(1011,"fake failure "+e);}
        }
        void send(String type,JSONObject payload)throws Exception{client.send(new JSONObject().put("type",type).put("payload",payload).toString());}
        void audio(long serverMicros,byte marker){ByteBuffer b=ByteBuffer.allocate(9+64);b.put((byte)0x04).putLong(serverMicros);byte[] pcm=new byte[64];Arrays.fill(pcm,marker);b.put(pcm);b.flip();client.send(b);}
        void artwork(int size){ByteBuffer b=ByteBuffer.allocate(9+size);b.put((byte)0x08).putLong(serverNow());b.put(new byte[size]);b.flip();client.send(b);}
        void streamStart(int rate)throws Exception{send("stream/start",new JSONObject().put("player",new JSONObject().put("codec","pcm").put("sample_rate",rate).put("channels",2).put("bit_depth",16)));}
    }
    private Server server;
    private final FakeSink sink=new FakeSink();
    private final BlockingQueue<SendspinClient.State> states=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> connections=new LinkedBlockingQueue<>();
    private final BlockingQueue<SendspinClient.Metadata> metadata=new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> artworks=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> players=new LinkedBlockingQueue<>();
    private SendspinClient client() throws Exception {
        server=new Server();server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        SendspinClient c=new SendspinClient("ws://127.0.0.1:"+server.getPort()+"/sendspin","client-1","Helios","0.8.0",sink,new SendspinClient.Listener(){
            public void onState(SendspinClient.State state){states.add(state);}
            public void onMetadata(SendspinClient.Metadata m){metadata.add(m);}
            public void onController(List<String> commands,Integer volume,Boolean muted){}
            public void onArtwork(byte[] jpeg){artworks.add(jpeg==null?-1:jpeg.length);}
            public void onPlayer(int volume,boolean muted){players.add(volume+"/"+muted);}
            public void onConnection(boolean connected,String detail){connections.add((connected?"up:":"down:")+detail);}
        });
        c.start();
        assertEquals("up:connected",connections.poll(5,TimeUnit.SECONDS));
        Thread.sleep(700); // clock burst
        return c;
    }
    private byte marker(byte[] pcm){return pcm[0];}

    @Test public void helloAdvertisesThreeRolesAndPcmAndClockSyncSchedulesChunksInOrder() throws Exception {
        SendspinClient client=client();
        try{
            JSONObject hello=server.hellos.poll(1,TimeUnit.SECONDS);
            assertEquals("client-1",hello.getString("client_id"));assertEquals(1,hello.getInt("version"));
            assertEquals("[\"player@v1\",\"metadata@v1\",\"controller@v1\"]",hello.getJSONArray("supported_roles").toString());assertFalse(hello.has("artwork@v1_support"));
            JSONObject fmt=hello.getJSONObject("player@v1_support").getJSONArray("supported_formats").getJSONObject(0);
            assertEquals("pcm",fmt.getString("codec"));assertEquals(48000,fmt.getInt("sample_rate"));
            server.streamStart(48000);Thread.sleep(200);
            assertEquals("open:pcm/48000/2/16",sink.events.get(0));
            long base=serverNow();
            server.audio(base+500_000,(byte)3);server.audio(base+300_000,(byte)1);server.audio(base+400_000,(byte)2);
            server.audio(base-1_000_000,(byte)9); // a second late: dropped, never written
            assertEquals(1,marker(sink.writes.poll(3,TimeUnit.SECONDS)));assertEquals(2,marker(sink.writes.poll(3,TimeUnit.SECONDS)));assertEquals(3,marker(sink.writes.poll(3,TimeUnit.SECONDS)));
            assertNull(sink.writes.poll(300,TimeUnit.MILLISECONDS));assertTrue(client.dropped()>=1);
            server.send("group/update",new JSONObject().put("playback_state","playing"));
            assertEquals(SendspinClient.State.PLAYING,states.poll(3,TimeUnit.SECONDS));
            server.send("group/update",new JSONObject().put("playback_state","paused"));
            assertEquals(SendspinClient.State.PAUSED,states.poll(3,TimeUnit.SECONDS));
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void volumeAndMuteFromTheServerReachTheSinkAndAreConfirmedAsClientState() throws Exception {
        SendspinClient client=client();
        try{
            server.send("server/command",new JSONObject().put("player",new JSONObject().put("command","volume").put("volume",30)));
            assertEquals("30/false",players.poll(3,TimeUnit.SECONDS));assertEquals(0.3f,sink.gain,0.001f);
            JSONObject state=server.states.poll(3,TimeUnit.SECONDS);assertEquals(30,state.getJSONObject("player").getInt("volume"));
            server.send("server/command",new JSONObject().put("player",new JSONObject().put("command","mute").put("mute",true)));
            assertEquals("30/true",players.poll(3,TimeUnit.SECONDS));assertTrue(sink.muted);
            server.send("server/state",new JSONObject().put("controller",new JSONObject().put("supported_commands",new JSONArray().put("play").put("pause").put("volume")).put("volume",70))
                .put("metadata",new JSONObject().put("title","Track").put("artist","Artist").put("artwork_url","/imageproxy?path=x").put("progress",new JSONObject().put("track_progress",1000).put("track_duration",200000))));
            SendspinClient.Metadata m=metadata.poll(3,TimeUnit.SECONDS);assertEquals("Track",m.title);assertEquals(200000,m.durationMs);assertEquals("/imageproxy?path=x",m.artworkUrl);
            Thread.sleep(100);
            assertTrue(client.command("pause"));assertEquals("pause",server.commands.poll(3,TimeUnit.SECONDS).getJSONObject("controller").getString("command"));
            assertFalse(client.command("next"));assertFalse(client.command("volume"));
            assertNull(server.commands.poll(300,TimeUnit.MILLISECONDS));
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void clearFlushesEndDrainsAndSameFormatContinuesWithoutStop() throws Exception {
        SendspinClient client=client();
        try{
            server.streamStart(48000);Thread.sleep(200);
            server.send("stream/clear",new JSONObject().put("roles",new JSONArray().put("player")));Thread.sleep(200);
            assertTrue(sink.events.contains("flush"));assertTrue(sink.open);
            long base=serverNow();
            for(int i=1;i<=3;i++)server.audio(base+300_000*i,(byte)i);
            Thread.sleep(50);
            server.send("stream/end",new JSONObject());
            Thread.sleep(200);
            server.streamStart(48000); // next track while the previous one is still buffered
            server.audio(base+1_200_000,(byte)4);
            for(int i=1;i<=4;i++)assertEquals(i,marker(sink.writes.poll(3,TimeUnit.SECONDS)));
            assertFalse(sink.events.contains("stop"));
            server.send("stream/end",new JSONObject());
            Thread.sleep(300);server.streamStart(44100);
            long until=System.currentTimeMillis()+4000;
            while(System.currentTimeMillis()<until&&!sink.events.contains("open:pcm/44100/2/16"))Thread.sleep(50);
            int stop=sink.events.indexOf("stop"),reopen=sink.events.indexOf("open:pcm/44100/2/16");
            assertTrue(sink.events.toString(),stop>=0&&reopen>stop);
            assertEquals(SendspinClient.State.NONE,client.state());
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void artworkLimitDisconnectAndReconnectWithoutAutoplay() throws Exception {
        SendspinClient client=client();
        try{
            server.artwork(2048);assertEquals(2048,(int)artworks.poll(3,TimeUnit.SECONDS));
            server.artwork(SendspinClient.MAX_BINARY+1);assertNull(artworks.poll(500,TimeUnit.MILLISECONDS));
            server.streamStart(48000);Thread.sleep(200);assertTrue(sink.open);
            server.client.close(1001,"drop");
            String down=connections.poll(5,TimeUnit.SECONDS);assertTrue(down,down.startsWith("down:"));
            Thread.sleep(200);assertFalse(sink.open);assertEquals(SendspinClient.State.NONE,client.state());
            assertEquals("up:connected",connections.poll(7,TimeUnit.SECONDS));
            assertEquals(2,server.hellos.size());
            assertNull(server.commands.poll(500,TimeUnit.MILLISECONDS));
            client.stop();
            assertNotNull(server.goodbyes.poll(3,TimeUnit.SECONDS));
        }finally{client.stop();server.stop(2000);}
    }
    @Test public void clockOffsetPicksTheSampleWithTheSmallestRoundTrip(){
        ClockOffset clock=new ClockOffset();
        assertFalse(clock.known());
        clock.sample(0,1_000_500,1_000_500,3_000);          // rtt 3000, offset 999000
        clock.sample(10_000,1_010_100,1_010_100,10_200);     // rtt 200, offset 1000000
        assertTrue(clock.known());assertEquals(1_000_000,clock.offsetMicros());
        assertEquals(500,clock.toLocalMicros(1_000_500));
    }
}
