package pl.mateusz.helios;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import io.homeassistant.companion.android.microwakeword.MicroWakeWord;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Owns the microphone only during local wake-word listening. */
final class WakeWordListener {
    private volatile boolean stopped;
    void stop(){stopped=true;}

    boolean listen(Context context,Runnable ready) throws Exception {return listen(context,ready,null);}
    /** Where AudioFlinger actually routed the capture: built-in mic, USB, Bluetooth SCO... (type constants from AudioDeviceInfo). */
    /** Every capture AudioFlinger currently runs (ours included): source, client format, device format. Another client at 48 kHz stereo explains a 6x sample flood on a HAL without per-client resampling. */
    static String activeRecordings(Context context){
        try{
            android.media.AudioManager am=(android.media.AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            StringBuilder out=new StringBuilder("[");
            for(android.media.AudioRecordingConfiguration c:am.getActiveRecordingConfigurations()){
                android.media.AudioFormat cf=c.getClientFormat(),df=c.getFormat();
                out.append("{src=").append(c.getClientAudioSource()).append(" client=").append(cf==null?"?":cf.getSampleRate()+"/"+cf.getChannelCount()).append(" device=").append(df==null?"?":df.getSampleRate()+"/"+df.getChannelCount()).append(" session=").append(c.getClientAudioSessionId()).append("}");
            }
            return out.append("]").toString();
        }catch(Exception e){return "?";}
    }
    static String routed(AudioRecord recorder){
        try{android.media.AudioDeviceInfo d=recorder.getRoutedDevice();return d==null?"none":d.getType()+":"+d.getProductName()+":"+java.util.Arrays.toString(d.getSampleRates());}catch(Exception e){return "?";}
    }
    /** diagnostics (optional) receives "wake_level" lines every 15 s: captured RMS and frame count, so silence from a stolen microphone is distinguishable from a model that never fires. */
    boolean listen(Context context,Runnable ready,java.util.function.Consumer<String> diagnostics) throws Exception {
        AudioRecord recorder=null;
        long engine=0;
        // Keep a strong reference: the native engine retains the model pointer.
        ByteBuffer model=null;
        try {
            if(stopped)return false;
            System.loadLibrary("microwakeword");
            try(InputStream in=context.getAssets().open("wakeword/okay_nabu.tflite");
                ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] bytes=new byte[8192];int n;
                while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
                model=ByteBuffer.allocateDirect(out.size());model.put(out.toByteArray());model.rewind();
            }
            engine=MicroWakeWord.nativeCreate(model,16000,10,0.85f,5);
            if(engine==0)throw new IllegalStateException("Wake-word engine unavailable");
            if(stopped)return false;
            if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Microphone permission required");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)throw new IllegalStateException("Microphone format unavailable");
            // VOICE_RECOGNITION: the hotword/STT path without the AEC/NS chain of VOICE_COMMUNICATION, which on this clock started
            // delivering ~6x real-time sample streams (diagnosed 2026-09-15); the wake model needs raw 16 kHz audio anyway.
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*4,8192));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone not recording");
            ready.run();
            // Since 2026-09-15 this HAL delivers ~6x the requested rate regardless of format; the decimator measures the true
            // rate and downsamples back to 16 kHz (artifacts/wakeword-microphone-20260915.md). ponytail: remove with the HAL fix.
            AdaptiveDecimator decimator=new AdaptiveDecimator(16000,1500);
            short[] raw=new short[1280];short[] frame=new short[160];int offset=0;
            double energy=0;long frames=0,peak=0,lastReport=SystemClock.elapsedRealtime();
            while(!stopped){
                int n=recorder.read(raw,0,raw.length,AudioRecord.READ_NON_BLOCKING);
                if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                if(n>0)decimator.push(raw,n,SystemClock.elapsedRealtime());
                int m;
                while(offset<frame.length&&(m=decimator.poll(frame,offset,frame.length-offset))>0)offset+=m;
                if(offset==frame.length){
                    for(short sample:frame){energy+=(double)sample*sample;if(Math.abs(sample)>peak)peak=Math.abs(sample);}
                    frames++;
                    if(MicroWakeWord.nativeProcessAudio(engine,frame))return !stopped;
                    offset=0;
                    long now=SystemClock.elapsedRealtime();
                    if(diagnostics!=null&&now-lastReport>=15_000){
                        diagnostics.accept("rms="+Math.round(Math.sqrt(energy/Math.max(1,frames*160L)))+" peak="+peak+" frames="+frames+" source="+recorder.getAudioSource()+" rate="+recorder.getSampleRate()+" channels="+recorder.getChannelCount()+" session="+recorder.getAudioSessionId()+" elapsed_ms="+(now-lastReport)+" device="+routed(recorder)+" active="+activeRecordings(context)+" decim="+decimator.factor()+" measured_rate="+Math.round(decimator.measuredRate())+" calibrated="+decimator.calibrated());
                        energy=0;frames=0;peak=0;lastReport=now;
                    }
                }
                if(offset<frame.length)SystemClock.sleep(5);
            }
            return false;
        } finally {
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}
            if(engine!=0)MicroWakeWord.nativeDestroy(engine);
            // Reachability through teardown for the JNI-backed direct buffer.
            if(model!=null)model.clear();
        }
    }
}
