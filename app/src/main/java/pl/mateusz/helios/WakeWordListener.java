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

    boolean listen(Context context,Runnable ready) throws Exception {
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
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,
                AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*4,8192));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
            recorder.startRecording();
            if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone not recording");
            ready.run();
            short[] frame=new short[160];int offset=0;
            while(!stopped){
                int n=recorder.read(frame,offset,frame.length-offset,AudioRecord.READ_NON_BLOCKING);
                if(n<0)throw new IllegalStateException("Microphone read failed: "+n);
                offset+=n;
                if(offset==frame.length){
                    if(MicroWakeWord.nativeProcessAudio(engine,frame))return !stopped;
                    offset=0;
                }
                if(n==0)SystemClock.sleep(5);
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
