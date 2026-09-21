package pl.mateusz.helios;
import android.content.Context;
import android.media.*;
import android.os.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;
import java.util.function.Supplier;

/** One user-triggered Assist session; audio is never captured while idle. */
public final class AssistClient {
    public interface Listener { void onState(String text); void onEvent(String event,String detail); }
    private final Context ctx;
    private final JSONObject config;
    private final Listener listener;
    private final Supplier<String> deviceId;
    private volatile boolean closed, finishAudio, noFollowUp;
    public AssistClient(Context ctx,JSONObject config,Supplier<String> deviceId,Listener listener){this.ctx=ctx.getApplicationContext();this.config=config;this.deviceId=deviceId;this.listener=listener;}
    public void run(){runVoice();}
    public void cancel(){closed=true;finishAudio=true;}
    /** Lets the current answer finish but starts no further follow-up run (device context changed or removed). */
    public void cancelFollowUp(){noFollowUp=true;}
    public void finishSpeech(){finishAudio=true;}
    private void state(String text){listener.onState(text);}
    private void log(String event,String detail){
        String token=config.optString("token", "");
        if(!token.isEmpty())detail=detail.replace(token,"[REDACTED]");
        listener.onEvent(event,detail);
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
        boolean sttReady, vadStarted, audioDone, sttDone, ended, continueConversation;
        String ttsUrl, conversationId;
        void accept(JSONObject message) throws Exception {
            if(message.optString("type").equals("result")&&!message.optBoolean("success"))throw new IOException("Pipeline rejected");
            JSONObject event=message.optJSONObject("event");if(event==null)return;
            String type=event.getString("type");JSONObject data=event.optJSONObject("data");
            if(type.equals("error")){
                // The code alone ("stt-stream-failed") never says why; the message carries the reason and is the only trace we get of a remote failure.
                String reason=data==null?"Pipeline error":(data.optString("code","Pipeline error")+" "+data.optString("message","")).trim();
                log(type,reason);throw new IOException(reason);
            }
            log(type,"");
            if(type.equals("run-start"))handler=data.getJSONObject("runner_data").getInt("stt_binary_handler_id");
            if(type.equals("stt-start"))sttReady=true;
            if(type.equals("stt-vad-start"))vadStarted=true;
            if(type.equals("stt-vad-end"))audioDone=true;
            if(type.equals("stt-end")){
                audioDone=true;sttDone=true;vadStarted=true;
                String text=data.getJSONObject("stt_output").optString("text","");log("transcript",text);state("Usłyszano: "+text);
            }
            if(type.equals("intent-end")){
                JSONObject output=data.optJSONObject("intent_output");
                if(output!=null){
                    JSONObject response=output.optJSONObject("response");if(response!=null)log("intent_response",response.toString());
                    continueConversation=output.optBoolean("continue_conversation",false);
                    if(output.has("conversation_id")&&!output.isNull("conversation_id"))conversationId=output.getString("conversation_id");
                }
            }
            if(type.equals("tts-end"))ttsUrl=data.getJSONObject("tts_output").getString("url");
            if(type.equals("run-end"))ended=true;
        }
    }
    /** One wake-up: a pipeline run, then follow-up runs on the same socket while the user keeps talking. */
    private void runVoice() {
        Socket socket=null;ToneGenerator tone=null;
        long started=SystemClock.elapsedRealtime();
        try{
            log("test_start","");state("Łączenie z HA…");
            URI base=new URI(config.getString("url"));
            URI endpoint=new URI(base.getScheme().equals("https")?"wss":"ws",base.getUserInfo(),base.getHost(),base.getPort(),"/api/websocket",null,null);
            socket=new Socket(endpoint);
            if(!socket.connectBlocking(10,TimeUnit.SECONDS))throw new IOException("HA connection failed");
            if(!socket.next(SystemClock.elapsedRealtime()+10000).optString("type").equals("auth_required"))throw new IOException("HA handshake failed");
            socket.send(new JSONObject().put("type","auth").put("access_token",config.getString("token")).toString());
            if(!socket.next(SystemClock.elapsedRealtime()+10000).optString("type").equals("auth_ok"))throw new IOException("HA authentication failed");
            try{tone=new ToneGenerator(AudioManager.STREAM_MUSIC,70);}catch(RuntimeException ignored){}
            AudioManager audio=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
            // Ducking the music depends entirely on this being granted; a refused request used to pass unnoticed.
            int granted=audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            log("audio_focus","request="+(granted==AudioManager.AUDIOFOCUS_REQUEST_GRANTED?"granted":"refused("+granted+")"));
            String conversationId=null;long followUp=0;int run=1;String sessionDevice=deviceId.get();
            while(!closed){
                Events events=runOnce(socket,base,run++,conversationId,followUp,tone,sessionDevice);
                if(events==null)break;
                // The room context changed mid-conversation: finish here, never continue an old conversation under a new device.
                if(noFollowUp||!Objects.equals(sessionDevice,deviceId.get())){log("follow_up_cancelled","device changed or removed");break;}
                conversationId=events.conversationId;
                // ponytail: after every answer listen again without the wake word; HA's continue_conversation only lengthens the window.
                followUp=events.continueConversation?15000:6000;
            }
            log("test_completed","elapsed_ms="+(SystemClock.elapsedRealtime()-started));state("Gotowe · mikrofon wyłączony");
        }catch(Exception e){log("test_error",e.getClass().getSimpleName()+": "+e.getMessage());state("Błąd: "+e.getMessage());}
        finally{
            if(tone!=null)tone.release();
            ((AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(focus);
            if(socket!=null)socket.close();log("ready","microphone_off");
        }
    }
    private final AudioManager.OnAudioFocusChangeListener focus=change->{};
    /** Returns the run's events after playback, or null when a follow-up window passed without speech. */
    private Events runOnce(Socket socket,URI base,int run,String conversationId,long followUp,ToneGenerator tone,String device) throws Exception {
        AudioRecord recorder=null;long sampleCount=0;double sumSq=0;finishAudio=false;
        try{
            JSONObject request=new JSONObject().put("id",run).put("type","assist_pipeline/run").put("pipeline",config.getString("pipeline"))
                    .put("start_stage","stt").put("end_stage","tts").put("timeout",60).put("input",new JSONObject().put("sample_rate",16000));
            if(conversationId!=null)request.put("conversation_id",conversationId);
            if(device!=null)request.put("device_id",device);
            socket.send(request.toString());
            Events events=new Events();long setupDeadline=SystemClock.elapsedRealtime()+20000;
            while(!events.sttReady){events.accept(socket.next(setupDeadline));if(events.ended)throw new IOException("Pipeline ended before microphone");}
            if(events.handler<0||events.handler>255)throw new IOException("Invalid audio handler");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IOException("Microphone buffer unavailable");
            if(ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("Microphone permission required");
            // VOICE_RECOGNITION (6): the same source as the wake listener; VOICE_COMMUNICATION's AEC chain made the flood jitter (86-95 k/s), which breaks factor calibration.
            recorder=new AudioRecord(6,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(16384,min*4));
            if(tone!=null){tone.startTone(ToneGenerator.TONE_PROP_BEEP,120);SystemClock.sleep(150);}
            recorder.startRecording();log("microphone_started","source=6 rate=16000 mono PCM16 gain=1 run="+run);state(followUp>0?"Słucham dalej…":"Mów teraz\nKtóra jest godzina?");
            // Same HAL sample flood as the wake listener: decimate the measured factor back to 16 kHz before sending to HA's STT
            // (artifacts/wakeword-microphone-20260915.md). Short window so the start of the utterance is not clipped while calibrating.
            // raw is the read buffer (>= 20 ms even at 96 k/s, so the AudioRecord buffer never overruns), pcm the 20 ms output packet.
            AdaptiveDecimator decimator=new AdaptiveDecimator(16000,800);
            short[] raw=new short[2048],pcm=new short[320];long captureStart=SystemClock.elapsedRealtime(),captureDeadline=captureStart+30000,nextPulse=captureStart+1000;
            boolean quiet=false;
            while(!closed&&!finishAudio&&!events.audioDone&&!events.ended&&SystemClock.elapsedRealtime()<captureDeadline){
                if(socket.failed)throw new IOException("HA disconnected");
                JSONObject m;while((m=socket.messages.poll())!=null)events.accept(m);
                if(events.audioDone||events.ended)break;
                if(followUp>0&&!events.vadStarted&&SystemClock.elapsedRealtime()-captureStart>followUp){quiet=true;break;}
                int n=recorder.read(raw,0,raw.length,AudioRecord.READ_NON_BLOCKING);
                if(n<0)throw new IOException("Microphone read failed: "+n);
                if(n>0)decimator.push(raw,n,SystemClock.elapsedRealtime());
                int out;
                while((out=decimator.poll(pcm,0,pcm.length))>0){
                    byte[] packet=new byte[out*2+1];packet[0]=(byte)events.handler;
                    for(int i=0;i<out;i++){int value=pcm[i];sumSq+=(double)value*value;packet[1+i*2]=(byte)value;packet[2+i*2]=(byte)(value>>8);}
                    sampleCount+=out;socket.send(packet);
                }
                if(n==0&&out==0)SystemClock.sleep(5);
                if(SystemClock.elapsedRealtime()>=nextPulse){log("capture_progress","samples="+sampleCount+" decim="+decimator.factor());nextPulse=SystemClock.elapsedRealtime()+1000;}
            }
            recorder.stop();recorder.release();recorder=null;
            log("microphone_released","samples="+sampleCount+" rms="+(sampleCount>0?Math.sqrt(sumSq/sampleCount):0)+" decim="+decimator.factor()+" measured_rate="+Math.round(decimator.measuredRate()));
            if(closed)throw new IOException("Cancelled");
            if(quiet){log("follow_up_quiet","run="+run);return null;}
            if(!events.sttDone&&!events.ended)socket.send(new byte[]{(byte)events.handler});
            state("Czekam na odpowiedź…");long responseDeadline=SystemClock.elapsedRealtime()+45000;
            while(!events.ended)events.accept(socket.next(responseDeadline));
            if(events.ttsUrl==null)throw new IOException("No TTS response");
            play(base.resolve(events.ttsUrl).toString());
            return events;
        }finally{
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();log("microphone_released","cleanup");}
        }
    }
    private void play(String url) throws Exception {
        if(closed)throw new IOException("Cancelled");
        MediaPlayer player=new MediaPlayer();AtomicBoolean finished=new AtomicBoolean(),failed=new AtomicBoolean();
        try{
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setOnPreparedListener(p->{if(!closed){p.start();log("playback_started","");}});
            player.setOnCompletionListener(p->finished.set(true));
            player.setOnErrorListener((p,what,extra)->{failed.set(true);finished.set(true);return true;});
            player.setDataSource(url);player.prepareAsync();state("Odpowiadam…");
            long deadline=SystemClock.elapsedRealtime()+30000;
            while(!closed&&!finished.get()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
            if(closed||failed.get()||!finished.get())throw new IOException("Playback failed or cancelled");
            log("playback_completed","");
        }finally{player.release();}
    }
}
