import android.app.ActivityManager;
import android.content.Context;
import android.media.*;
import android.os.*;
import io.homeassistant.companion.android.microwakeword.MicroWakeWord;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Bounded local wake-word experiment; only aggregate metrics leave the clock. */
public final class WakeWordProbe {
    private static byte[] fetch(String url, String expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
        try {
            if(connection.getResponseCode()!=200) throw new IOException("Asset HTTP error");
            try(InputStream in=connection.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] chunk=new byte[8192]; int n;
                while((n=in.read(chunk))!=-1) {
                    if(out.size()+n>8000000) throw new IOException("Asset too large");
                    out.write(chunk,0,n);
                }
                byte[] data=out.toByteArray();
                StringBuilder hash=new StringBuilder();
                for(byte b:MessageDigest.getInstance("SHA-256").digest(data)) hash.append(String.format(Locale.US,"%02x",b&255));
                if(!hash.toString().equals(expected)) throw new IOException("Asset SHA256 mismatch");
                return data;
            }
        } finally {connection.disconnect();}
    }
    private static int pss() {
        Debug.MemoryInfo info=new Debug.MemoryInfo(); Debug.getMemoryInfo(info); return info.getTotalPss();
    }
    public static String run(Context ctx, String arg) {
        StringBuilder out=new StringBuilder(); AudioRecord rec=null; long handle=0;
        try {
            JSONObject settings=new JSONObject(arg);
            out.append("sdk=").append(Build.VERSION.SDK_INT).append(" abi=").append(Arrays.toString(Build.SUPPORTED_ABIS)).append(" uid=").append(android.os.Process.myUid()).append('\n');
            out.append("pss_before_kb=").append(pss()).append('\n');
            byte[] library=fetch(settings.getString("base")+"/libmicrowakeword.so",settings.getString("library_sha256"));
            // Unique path per classloader; no replacement of existing APK or loaded library.
            File lib=File.createTempFile("helios_mww_", ".so",ctx.getFilesDir());
            try(FileOutputStream stream=new FileOutputStream(lib)){stream.write(library);}
            lib.setReadOnly();
            System.load(lib.getAbsolutePath());
            byte[] model=fetch(settings.getString("base")+"/okay_nabu.tflite",settings.getString("model_sha256"));
            ByteBuffer buffer=ByteBuffer.allocateDirect(model.length); buffer.put(model); buffer.rewind();
            handle=MicroWakeWord.nativeCreate(buffer,16000,10,0.85f,5);
            if(handle==0) throw new IllegalStateException("Engine initialization failed");
            out.append("ENGINE_READY model=Okay_Nabu step_ms=10 cutoff=0.85 window=5 pss_loaded_kb=").append(pss()).append('\n');
            short[] chunk=new short[160]; int synthetic=0;
            for(int i=0;i<200;i++) if(MicroWakeWord.nativeProcessAudio(handle,chunk)) synthetic++;
            MicroWakeWord.nativeReset(handle);
            out.append("synthetic_zero_samples=32000 detections=").append(synthetic).append('\n');
            if(!settings.optBoolean("live",false)) {out.append("RESULT=SELFTEST_COMPLETED\n");} else {
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0) throw new IllegalStateException("Invalid microphone buffer");
            rec=new AudioRecord(7,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*4,8192));
            rec.startRecording();
            out.append("source=").append(rec.getAudioSource()).append(" rate=").append(rec.getSampleRate()).append(" channels=").append(rec.getChannelCount()).append(" encoding=").append(rec.getAudioFormat()).append('\n');
            long start=SystemClock.elapsedRealtime(), cpu=SystemClock.currentThreadTimeMillis(), next=start+5000;
            long samples=0, nz=0, inferenceNs=0; double sum=0; int peak=0, detections=0, offset=0;
            int seconds=Math.max(1,Math.min(120,settings.optInt("seconds",60)));
            while(SystemClock.elapsedRealtime()-start<seconds*1000L) {
                int n=rec.read(chunk,offset,chunk.length-offset,AudioRecord.READ_NON_BLOCKING);
                if(n<0) throw new IOException("AudioRecord read="+n);
                for(int i=offset;i<offset+n;i++){int v=chunk[i];samples++;if(v!=0)nz++;sum+=(double)v*v;peak=Math.max(peak,Math.abs(v));}
                offset+=n;
                if(offset==chunk.length) {
                    long before=System.nanoTime(); boolean detected=MicroWakeWord.nativeProcessAudio(handle,chunk); inferenceNs+=System.nanoTime()-before;
                    Arrays.fill(chunk,(short)0); offset=0;
                    if(detected){detections++;out.append("DETECTION time=").append(System.currentTimeMillis()).append('\n');MicroWakeWord.nativeReset(handle);}
                }
                if(SystemClock.elapsedRealtime()>=next) {
                    ActivityManager.RunningAppProcessInfo info=new ActivityManager.RunningAppProcessInfo();ActivityManager.getMyMemoryState(info);
                    AudioRecordingConfiguration cfg=rec.getActiveRecordingConfiguration();
                    PowerManager power=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
                    out.append("time=").append(System.currentTimeMillis()).append(" importance=").append(info.importance).append(" interactive=").append(power.isInteractive()).append(" silenced=").append(cfg==null?"unknown":String.valueOf(cfg.isClientSilenced())).append(" pss_kb=").append(pss()).append(" samples_total=").append(samples).append(" detections_total=").append(detections).append('\n');
                    next+=5000;
                }
                if(n==0) SystemClock.sleep(5);
            }
            long wall=SystemClock.elapsedRealtime()-start, used=SystemClock.currentThreadTimeMillis()-cpu;
            out.append(String.format(Locale.US,"SUMMARY wall_ms=%d thread_cpu_ms=%d one_core_pct=%.2f inference_ms=%.2f samples=%d rms=%.2f peak=%d nonzero_pct=%.2f detections=%d%n",wall,used,100.0*used/wall,inferenceNs/1e6,samples,samples>0?Math.sqrt(sum/samples):0,peak,samples>0?100.0*nz/samples:0,detections));
            out.append("RESULT=COMPLETED\n");
            }
        } catch(Throwable t) {out.append("ERROR=").append(t).append('\n');}
        finally {
            if(rec!=null){try{rec.stop();}catch(Throwable ignored){}rec.release();out.append("AUDIO_RELEASED\n");}
            if(handle!=0){MicroWakeWord.nativeDestroy(handle);out.append("ENGINE_RELEASED\n");}
        }
        return out.toString();
    }
}
