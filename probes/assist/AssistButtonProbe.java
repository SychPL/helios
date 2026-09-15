import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.graphics.PixelFormat;
import android.media.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary push-to-talk overlay: microphone goes directly to HA WebSocket. */
public final class AssistButtonProbe {
    private final Context ctx;
    private final JSONObject config;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed, finishAudio;
    private LinearLayout panel;
    private TextView label;
    private Button talk;
    private AlertDialog dialog;
    private final WindowManager wm;
    private final File logFile;

    private AssistButtonProbe(Context context, JSONObject settings) {
        ctx=context.getApplicationContext(); config=settings;
        wm=(WindowManager)ctx.getSystemService(Context.WINDOW_SERVICE);
        logFile=new File(ctx.getFilesDir(),"helios_assist_test.jsonl");
    }
    private synchronized void log(String event, String detail) {
        try {
            JSONObject row=new JSONObject().put("time_ms",System.currentTimeMillis()).put("event",event).put("detail",detail);
            try(FileOutputStream out=new FileOutputStream(logFile,true)){out.write((row.toString()+"\n").getBytes("UTF-8"));}
            String callback=config.optString("control_url","");
            if(!callback.isEmpty()){
                HttpURLConnection connection=(HttpURLConnection)new URL(callback).openConnection();
                connection.setConnectTimeout(1000);connection.setReadTimeout(1000);connection.setRequestMethod("POST");connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");
                try{
                    try(OutputStream stream=connection.getOutputStream()){stream.write(row.toString().getBytes("UTF-8"));}
                    try(InputStream stream=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                        byte[] chunk=new byte[256];int n;while((n=stream.read(chunk))!=-1&&bytes.size()<1024)bytes.write(chunk,0,n);
                        if(new JSONObject(bytes.toString("UTF-8")).optBoolean("cancel",false)){closed=true;finishAudio=true;}
                    }
                }finally{connection.disconnect();}
            }
        }catch(Exception ignored){}
    }
    private void state(String text) {ui.post(()->{if(!closed&&label!=null)label.setText(text);});}
    private void show() {
        if(Settings.canDrawOverlays(ctx)){showPanel(ctx);return;}
        Application app=(Application)ctx;
        Application.ActivityLifecycleCallbacks callbacks=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){
                log("activity_resumed",a.getClass().getName());
                if(a.getClass().getName().equals("pl.mateusz.clockadbprobe.MainActivity")){
                    app.unregisterActivityLifecycleCallbacks(this);showPanel(a);
                }
            }
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){}
        };
        app.registerActivityLifecycleCallbacks(callbacks);
        log("opening_activity","");
        try{ctx.startActivity(new Intent().setClassName(ctx,"pl.mateusz.clockadbprobe.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(Exception e){app.unregisterActivityLifecycleCallbacks(callbacks);log("error","cannot_open_test_activity");}
        ui.postDelayed(()->app.unregisterActivityLifecycleCallbacks(callbacks),15000);
    }
    private void closePanel(String reason){
        if(closed)return;closed=true;finishAudio=true;
        if(dialog!=null)dialog.dismiss();else try{wm.removeView(panel);}catch(Exception ignored){}
        log(reason,"");
    }
    private void showPanel(Context displayContext) {
        panel=new LinearLayout(displayContext);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(8,4,8,4);panel.setBackgroundColor(0xF0222222);
        label=new TextView(displayContext);label.setTextColor(0xFFFFFFFF);label.setTextSize(14);label.setText("Helios · HA Cloud\nNaciśnij, potem mów");panel.addView(label);
        LinearLayout buttons=new LinearLayout(displayContext);
        talk=new Button(displayContext);talk.setText("Test głosu");buttons.addView(talk);
        Button close=new Button(displayContext);close.setText("×");buttons.addView(close,new LinearLayout.LayoutParams(48,48));panel.addView(buttons);
        talk.setOnClickListener(v->{
            if(busy.get()){finishAudio=true;return;}
            if(busy.compareAndSet(false,true)) {
                finishAudio=false;talk.setText("Zakończ mowę");
                new Thread(()->{try{runVoice();}finally{busy.set(false);ui.post(()->{if(!closed)talk.setText("Test głosu");});}},"helios-assist").start();
            }
        });
        close.setOnClickListener(v->closePanel("panel_closed"));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.RIGHT;p.x=4;p.y=4;
        try{
            if(displayContext instanceof Activity){dialog=new AlertDialog.Builder(displayContext).setView(panel).create();dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->closePanel("panel_closed"));dialog.show();}
            else wm.addView(panel,p);
            log("panel_ready","push_to_talk; cloud; no_idle_capture");
        }catch(Exception e){log("error",e.getClass().getSimpleName());}
        // Remove this temporary experiment after 30 minutes even if forgotten.
        ui.postDelayed(()->closePanel("panel_expired"),30*60*1000L);
    }
    private final class Socket extends WebSocketClient {
        final BlockingQueue<JSONObject> messages=new LinkedBlockingQueue<>(256);
        volatile boolean failed;
        Socket(URI uri){super(uri);setConnectionLostTimeout(20);}
        @Override public void onOpen(ServerHandshake h){}
        @Override public void onMessage(String text){try{if(!messages.offer(new JSONObject(text)))failed=true;}catch(Exception e){failed=true;}}
        @Override public void onClose(int code,String reason,boolean remote){failed=true;}
        @Override public void onError(Exception ex){failed=true;}
        JSONObject next(long deadline) throws Exception {
            while(!closed&&SystemClock.elapsedRealtime()<deadline){
                JSONObject message=messages.poll(100,TimeUnit.MILLISECONDS);
                if(message!=null)return message;
                if(failed)throw new IOException("HA disconnected");
            }
            throw new IOException(closed?"Cancelled":"HA timeout");
        }
    }
    private final class Events {
        int handler=-1;
        boolean sttReady, audioDone, sttDone, ended;
        String ttsUrl;
        void accept(JSONObject message) throws Exception {
            if(message.optString("type").equals("result")&&!message.optBoolean("success"))throw new IOException("Pipeline rejected");
            JSONObject event=message.optJSONObject("event");if(event==null)return;
            String type=event.getString("type");JSONObject data=event.optJSONObject("data");
            log(type,"");
            if(type.equals("error"))throw new IOException(data==null?"Pipeline error":data.optString("code","Pipeline error"));
            if(type.equals("run-start"))handler=data.getJSONObject("runner_data").getInt("stt_binary_handler_id");
            if(type.equals("stt-start"))sttReady=true;
            if(type.equals("stt-vad-end"))audioDone=true;
            if(type.equals("stt-end")){
                audioDone=true;sttDone=true;
                String text=data.getJSONObject("stt_output").optString("text","");log("transcript",text);state("Usłyszano: "+text);
            }
            if(type.equals("intent-end")){
                JSONObject output=data.optJSONObject("intent_output");
                if(output!=null){JSONObject response=output.optJSONObject("response");if(response!=null)log("intent_response",response.toString());}
            }
            if(type.equals("tts-end"))ttsUrl=data.getJSONObject("tts_output").getString("url");
            if(type.equals("run-end"))ended=true;
        }
    }
    private void runVoice() {
        Socket socket=null;AudioRecord recorder=null;
        long started=SystemClock.elapsedRealtime();long sampleCount=0;double sumSq=0;
        try{
            log("test_start","");state("Łączenie z HA…");
            URI base=new URI(config.getString("url"));
            URI endpoint=new URI(base.getScheme().equals("https")?"wss":"ws",base.getUserInfo(),base.getHost(),base.getPort(),"/api/websocket",null,null);
            socket=new Socket(endpoint);
            if(!socket.connectBlocking(10,TimeUnit.SECONDS))throw new IOException("HA connection failed");
            if(!socket.next(SystemClock.elapsedRealtime()+10000).optString("type").equals("auth_required"))throw new IOException("HA handshake failed");
            socket.send(new JSONObject().put("type","auth").put("access_token",config.getString("token")).toString());
            if(!socket.next(SystemClock.elapsedRealtime()+10000).optString("type").equals("auth_ok"))throw new IOException("HA authentication failed");
            socket.send(new JSONObject().put("id",1).put("type","assist_pipeline/run").put("pipeline",config.getString("pipeline"))
                    .put("start_stage","stt").put("end_stage","tts").put("timeout",60).put("input",new JSONObject().put("sample_rate",16000)).toString());
            Events events=new Events();long setupDeadline=SystemClock.elapsedRealtime()+20000;
            while(!events.sttReady){events.accept(socket.next(setupDeadline));if(events.ended)throw new IOException("Pipeline ended before microphone");}
            if(events.handler<0||events.handler>255)throw new IOException("Invalid audio handler");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("Microphone buffer unavailable");
            recorder=new AudioRecord(7,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(8192,min*4));
            recorder.startRecording();log("microphone_started","source=7 rate=16000 mono PCM16 gain=1");state("Mów teraz\nKtóra jest godzina?");
            short[] pcm=new short[320];long captureDeadline=SystemClock.elapsedRealtime()+30000,nextPulse=SystemClock.elapsedRealtime()+1000;
            while(!closed&&!finishAudio&&!events.audioDone&&!events.ended&&SystemClock.elapsedRealtime()<captureDeadline){
                if(socket.failed)throw new IOException("HA disconnected");
                JSONObject m;while((m=socket.messages.poll())!=null)events.accept(m);
                if(events.audioDone||events.ended)break;
                int n=recorder.read(pcm,0,pcm.length,AudioRecord.READ_NON_BLOCKING);
                if(n<0)throw new IOException("Microphone read failed: "+n);
                if(n==0){SystemClock.sleep(5);continue;}
                byte[] packet=new byte[n*2+1];packet[0]=(byte)events.handler;
                for(int i=0;i<n;i++){int value=pcm[i];sumSq+=(double)value*value;packet[1+i*2]=(byte)value;packet[2+i*2]=(byte)(value>>8);pcm[i]=0;}
                sampleCount+=n;socket.send(packet);
                if(SystemClock.elapsedRealtime()>=nextPulse){log("capture_progress","samples="+sampleCount);nextPulse=SystemClock.elapsedRealtime()+1000;}
            }
            recorder.stop();recorder.release();recorder=null;
            log("microphone_released","samples="+sampleCount+" rms="+(sampleCount>0?Math.sqrt(sumSq/sampleCount):0));
            if(closed)throw new IOException("Cancelled");
            if(!events.sttDone&&!events.ended)socket.send(new byte[]{(byte)events.handler});
            state("Czekam na odpowiedź…");long responseDeadline=SystemClock.elapsedRealtime()+45000;
            while(!events.ended)events.accept(socket.next(responseDeadline));
            if(events.ttsUrl==null)throw new IOException("No TTS response");
            play(base.resolve(events.ttsUrl).toString());
            log("test_completed","elapsed_ms="+(SystemClock.elapsedRealtime()-started));state("Gotowe · mikrofon wyłączony");
        }catch(Exception e){log("test_error",e.getClass().getSimpleName()+": "+e.getMessage());state("Błąd: "+e.getMessage());}
        finally{
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();log("microphone_released","cleanup");}
            if(socket!=null)socket.close();log("ready","microphone_off");
        }
    }
    private void play(String url) throws Exception {
        if(closed)throw new IOException("Cancelled");
        MediaPlayer player=new MediaPlayer();AtomicBoolean finished=new AtomicBoolean(),failed=new AtomicBoolean();
        AudioManager audio=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        AudioManager.OnAudioFocusChangeListener focus=change->{};
        try{
            audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setOnPreparedListener(p->{if(!closed){p.start();log("playback_started","");}});
            player.setOnCompletionListener(p->finished.set(true));
            player.setOnErrorListener((p,what,extra)->{failed.set(true);finished.set(true);return true;});
            player.setDataSource(url);player.prepareAsync();state("Odpowiadam…");
            long deadline=SystemClock.elapsedRealtime()+30000;
            while(!closed&&!finished.get()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
            if(closed||failed.get()||!finished.get())throw new IOException("Playback failed or cancelled");
            log("playback_completed","");
        }finally{player.release();audio.abandonAudioFocus(focus);}
    }
    public static String run(Context ctx,String arg) throws Exception {
        // Config served once from a random private LAN route; never print its token.
        HttpURLConnection c=(HttpURLConnection)new URL(arg).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);
        JSONObject config;
        try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[2048];int n;while((n=in.read(chunk))!=-1){if(bytes.size()+n>16384)throw new IOException("Config too large");bytes.write(chunk,0,n);}
            config=new JSONObject(bytes.toString("UTF-8"));
        }finally{c.disconnect();}
        AssistButtonProbe probe=new AssistButtonProbe(ctx,config);
        if(config.optBoolean("run_once",false)){probe.runVoice();return "VOICE_TEST_FINISHED; see dedicated event log";}
        if(config.optBoolean("check_only",false)) {
            URI base=new URI(config.getString("url"));
            Socket socket=probe.new Socket(new URI(base.getScheme().equals("https")?"wss":"ws",null,base.getHost(),base.getPort(),"/api/websocket",null,null));
            try {
                if(!socket.connectBlocking(10,TimeUnit.SECONDS))throw new IOException("Connection failed");
                if(!socket.next(SystemClock.elapsedRealtime()+10000).optString("type").equals("auth_required"))throw new IOException("Handshake failed");
                socket.send(new JSONObject().put("type","auth").put("access_token",config.getString("token")).toString());
                if(!socket.next(SystemClock.elapsedRealtime()+10000).optString("type").equals("auth_ok"))throw new IOException("Authentication failed");
                android.app.ActivityManager.RunningAppProcessInfo info=new android.app.ActivityManager.RunningAppProcessInfo();
                android.app.ActivityManager.getMyMemoryState(info);
                StringBuilder diagnostic=new StringBuilder("CLOCK_HA_WEBSOCKET_AUTH_OK; microphone not opened; importance=").append(info.importance).append(" overlay=").append(Settings.canDrawOverlays(ctx));
                android.app.ActivityManager manager=(android.app.ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE);
                for(android.app.ActivityManager.AppTask task:manager.getAppTasks()) diagnostic.append(" task=").append(task.getTaskInfo().topActivity);
                return diagnostic.toString();
            } finally {socket.close();}
        }
        probe.ui.post(probe::show);
        return "OVERLAY_REQUESTED; audio starts only after tap; log=helios_assist_test.jsonl";
    }
}
