package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class HeliosDeviceClientTest {
    private HaDashboardClientTest.Server server;
    private HaDashboardClient ha;
    private final AtomicReference<Telemetry> telemetry=new AtomicReference<>(snapshot(40));
    private final BlockingQueue<String> executed=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> devices=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> appearances=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> issues=new LinkedBlockingQueue<>();
    private final BlockingQueue<String> connections=new LinkedBlockingQueue<>();
    private volatile CountDownLatch hold;
    private static Telemetry snapshot(int volume){return new Telemetry("0.7.0",8,"idle",true,null,false,null,"22.127",volume,5,"none",42);}
    private HeliosDeviceClient client() throws Exception {
        server=new HaDashboardClientTest.Server();server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        ha=new HaDashboardClient(new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","t"),null);
        HeliosDeviceClient client=new HeliosDeviceClient(ha,"install-1",telemetry::get,(command,args)->{
            CountDownLatch latch=hold;if(latch!=null)latch.await(5,TimeUnit.SECONDS);
            executed.add(command+":"+args);return command.equals("lamp.turn_off")?"oem_failure":null;
        },new HeliosDeviceClient.Listener(){
            public void onDevice(String deviceId,String areaId,String name){devices.add(deviceId+"/"+areaId);}
            public void onAppearance(JSONObject a){appearances.add(a.toString());}
            public void onConnection(JSONObject payload){connections.add(payload.toString());}
            public void onChannelIssue(String text){issues.add(text);}
        });
        client.start();ha.start();return client;
    }
    private JSONObject device(String type) throws Exception {
        long until=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<until){JSONObject m=server.device.poll(5,TimeUnit.SECONDS);if(m==null)break;if(m.getString("type").equals(type))return m;}
        fail("no "+type);return null;
    }
    private JSONObject result(String requestId) throws Exception {
        long until=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<until){JSONObject m=server.device.poll(5,TimeUnit.SECONDS);if(m==null)break;if(m.getString("type").equals("helios/result")&&m.getString("request_id").equals(requestId))return m;}
        fail("no result "+requestId);return null;
    }

    @Test public void connectsPublishesStateAndClearsDeviceWhenTheSessionEnds() throws Exception {
        HeliosDeviceClient client=client();
        try{
            JSONObject connect=server.connects.poll(5,TimeUnit.SECONDS);
            assertEquals("install-1",connect.getString("installation_id"));assertEquals(2,connect.getInt("protocol"));assertEquals("0.7.0",connect.getString("app_version"));
            assertEquals("[\"lamp\",\"volume\",\"music\"]",connect.getJSONArray("capabilities").toString());assertFalse(connect.has("pairing_code"));
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));assertEquals("dev1",client.deviceId());
            JSONObject state=device("helios/state").getJSONObject("state");
            assertEquals(40,state.getInt("volume_percent"));assertTrue(state.isNull("charging"));assertFalse(state.getBoolean("led_on"));assertEquals(5,state.getLong("uptime_seconds"));
            server.client.close(1001,"drop");
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));assertNull(client.deviceId());assertFalse(client.active());
            assertNotNull(server.connects.poll(7,TimeUnit.SECONDS));assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void commandsAreValidatedExecutedAndAnsweredWithBusyPerResource() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));device("helios/state");
            server.sendCommand("r1","lamp.set_brightness",new JSONObject().put("level",5));
            assertEquals("ok",result("r1").getString("status"));assertEquals("lamp.set_brightness:{\"level\":5}",executed.poll(1,TimeUnit.SECONDS));
            server.sendCommand("r2","lamp.set_brightness",new JSONObject().put("level","5"));
            JSONObject r2=result("r2");assertEquals("error",r2.getString("status"));assertEquals("invalid_args",r2.getString("code"));
            server.sendCommand("r3","lamp.set_brightness",new JSONObject().put("level",11));assertEquals("invalid_args",result("r3").getString("code"));
            server.sendCommand("r4","shell.exec",new JSONObject().put("cmd","rm"));assertEquals("unknown_command",result("r4").getString("code"));
            server.sendCommand("r5","audio.set_device_volume",new JSONObject().put("percent",101));assertEquals("invalid_args",result("r5").getString("code"));
            assertNull(executed.poll(300,TimeUnit.MILLISECONDS));
            server.sendCommand("r6","lamp.turn_off",new JSONObject());
            JSONObject r6=result("r6");assertEquals("error",r6.getString("status"));assertEquals("oem_failure",r6.getString("code"));
            hold=new CountDownLatch(1);
            server.sendCommand("r7","lamp.turn_on",new JSONObject());
            Thread.sleep(200);
            server.sendCommand("r8","lamp.turn_on",new JSONObject());
            JSONObject r8=result("r8");assertEquals("busy",r8.getString("status"));
            server.sendCommand("r9","audio.set_device_volume",new JSONObject().put("percent",30));
            hold.countDown();hold=null;
            Set<String> done=new HashSet<>();
            for(int i=0;i<2;i++){JSONObject m=null;long until=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<until){m=server.device.poll(5,TimeUnit.SECONDS);if(m!=null&&m.getString("type").equals("helios/result"))break;}done.add(m.getString("request_id")+":"+m.getString("status"));}
            assertTrue(done.toString(),done.contains("r7:ok")&&done.contains("r9:ok"));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void staleCommandsNeverReachHardwareAfterTheChannelEnds() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));device("helios/state");
            hold=new CountDownLatch(1);
            server.sendCommand("r1","lamp.turn_on",new JSONObject());
            Thread.sleep(200);
            server.sendEvent(server.connectId,new JSONObject().put("type","removed"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.sendCommand("r2","lamp.turn_on",new JSONObject());
            hold.countDown();hold=null;
            Thread.sleep(500);
            assertEquals("lamp.turn_on:{}",executed.poll(1,TimeUnit.SECONDS));
            assertNull(executed.poll(300,TimeUnit.MILLISECONDS));
            assertTrue(server.device.stream().noneMatch(m->m.optString("type").equals("helios/result")));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void publishIsCoalesced() throws Exception {
        long previous=HeliosDeviceClient.MIN_PUBLISH_INTERVAL_MS;HeliosDeviceClient.MIN_PUBLISH_INTERVAL_MS=400;
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));assertEquals(40,device("helios/state").getJSONObject("state").getInt("volume_percent"));
            for(int v=41;v<=50;v++){telemetry.set(snapshot(v));client.publish();}
            JSONObject coalesced=device("helios/state");assertEquals(50,coalesced.getJSONObject("state").getInt("volume_percent"));
            assertNull(server.device.poll(600,TimeUnit.MILLISECONDS));
            client.publish();assertNull(server.device.poll(600,TimeUnit.MILLISECONDS));
        }finally{HeliosDeviceClient.MIN_PUBLISH_INTERVAL_MS=previous;client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void removedResubscribesWithBackoffAndReplacedDoesNot() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));server.connects.clear();
            long t0=System.currentTimeMillis();
            server.sendEvent(server.connectId,new JSONObject().put("type","removed"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            assertNotNull("resubscribe after removed",server.connects.poll(4,TimeUnit.SECONDS));
            assertTrue(System.currentTimeMillis()-t0>=1900);
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));assertTrue(client.active());
            server.connects.clear();
            server.sendEvent(server.connectId,new JSONObject().put("type","replaced"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            assertEquals("Inne urządzenie przejęło to parowanie",issues.poll(2,TimeUnit.SECONDS));
            assertNull("no resubscribe after replaced",server.connects.poll(3,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void aRejectedStateOrResultCountsAsRemoved() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));device("helios/state");server.connects.clear();
            server.stateUnauthorized=true;telemetry.set(snapshot(41));client.publish();
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.stateUnauthorized=false;
            assertNotNull(server.connects.poll(4,TimeUnit.SECONDS));assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));device("helios/state");server.connects.clear();
            server.stateUnauthorized=true; // the server flag covers helios/result too
            server.sendCommand("r1","lamp.turn_on",new JSONObject());
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.stateUnauthorized=false;
            assertNotNull(server.connects.poll(4,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void removedFollowedByADeadSocketResubscribesExactlyOnceInTheNewSession() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));server.connects.clear();
            server.sendEvent(server.connectId,new JSONObject().put("type","removed"));
            assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.client.close(1001,"drop"); // before the 2 s retry fires
            assertNotNull(server.connects.poll(7,TimeUnit.SECONDS)); // the new session's onSessionStarted
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
            assertNull("no second subscription from the stale retry",server.connects.poll(4,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void unauthorizedConnectStopsRetryingAndReportsIt() throws Exception {
        server=new HaDashboardClientTest.Server();server.rejectConnect=true;server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        ha=new HaDashboardClient(new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","t"),null);
        HeliosDeviceClient client=new HeliosDeviceClient(ha,"install-1",telemetry::get,(c,a)->null,new HeliosDeviceClient.Listener(){
            public void onDevice(String d,String a,String n){devices.add(d+"/"+a);}
            public void onChannelIssue(String text){issues.add(text);}
        });
        client.start();ha.start();
        try{
            assertNotNull(server.connects.poll(5,TimeUnit.SECONDS));
            assertEquals("Zegar usunięty z HA - sparuj ponownie",issues.poll(5,TimeUnit.SECONDS));
            assertNull(server.connects.poll(3,TimeUnit.SECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void aNotReadyConnectRetriesWithBackoff() throws Exception {
        server=new HaDashboardClientTest.Server();server.rejectConnect=true;server.connectError="not_ready";server.start();assertTrue(server.ready.await(5,TimeUnit.SECONDS));
        ha=new HaDashboardClient(new JSONObject().put("url","http://127.0.0.1:"+server.getPort()).put("token","t"),null);
        HeliosDeviceClient client=new HeliosDeviceClient(ha,"install-1",telemetry::get,(c,a)->null,new HeliosDeviceClient.Listener(){
            public void onDevice(String d,String a,String n){devices.add(d+"/"+a);}
            public void onChannelIssue(String text){issues.add(text);}
        });
        client.start();ha.start();
        try{
            assertNotNull(server.connects.poll(5,TimeUnit.SECONDS));assertEquals("null/null",devices.poll(5,TimeUnit.SECONDS));
            server.rejectConnect=false;
            assertNotNull("retried after not_ready",server.connects.poll(4,TimeUnit.SECONDS));assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
            assertNull(issues.poll(200,TimeUnit.MILLISECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void connectionEventReachesTheListener() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
            server.sendEvent(server.connectId,new JSONObject().put("type","connection").put("pipeline","p1").put("dashboard_path","helios-clock").put("music_assistant",JSONObject.NULL).put("diagnostics_url",JSONObject.NULL));
            String c=connections.poll(5,TimeUnit.SECONDS);assertNotNull(c);assertTrue(c,c.contains("\"pipeline\":\"p1\""));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void appearanceSnapshotsReachTheListenerAndUnknownEventsAreIgnored() throws Exception {
        HeliosDeviceClient client=client();
        try{
            assertEquals("dev1/bedroom",devices.poll(5,TimeUnit.SECONDS));
            server.sendEvent(server.connectId,new JSONObject().put("type","weird").put("command","lamp.turn_on"));
            server.sendEvent(server.connectId,new JSONObject().put("type","appearance").put("appearance",new JSONObject().put("version",1).put("theme","night_blue").put("background",new JSONObject().put("type","solid"))));
            String a=appearances.poll(5,TimeUnit.SECONDS);assertNotNull(a);assertTrue(a,a.contains("night_blue"));
            assertTrue(client.active());assertNull(executed.poll(300,TimeUnit.MILLISECONDS));
        }finally{client.stop();ha.stop();server.stop(2000);}
    }
    @Test public void musicCommandsAreAllowlistedWithoutArguments(){
        assertNull(HeliosDeviceClient.validate("music.stop",new JSONObject()));assertNull(HeliosDeviceClient.validate("music.pause",new JSONObject()));assertNull(HeliosDeviceClient.validate("music.play",new JSONObject()));
        assertEquals("unknown_command",HeliosDeviceClient.validate("music.seek",new JSONObject()));
    }
    @Test public void lampAndVolumeMath(){
        assertEquals(1,LampMath.brightnessToLevel(1));assertEquals(10,LampMath.brightnessToLevel(255));assertEquals(5,LampMath.brightnessToLevel(128));assertEquals(1,LampMath.brightnessToLevel(13));
        assertEquals(255,LampMath.levelToBrightness(10));assertEquals(26,LampMath.levelToBrightness(1));
        assertEquals(0,DeviceVolume.toSteps(0,100));assertEquals(100,DeviceVolume.toSteps(100,100));assertEquals(15,DeviceVolume.toSteps(100,15));assertEquals(8,DeviceVolume.toSteps(50,15));
        assertEquals(100,DeviceVolume.toPercent(15,15));assertEquals(53,DeviceVolume.toPercent(8,15));assertEquals(42,DeviceVolume.toPercent(42,100));
        assertEquals("invalid_args",HeliosDeviceClient.validate("lamp.set_brightness",new JSONObject()));
        assertNull(HeliosDeviceClient.validate("lamp.turn_on",new JSONObject()));
    }
}
