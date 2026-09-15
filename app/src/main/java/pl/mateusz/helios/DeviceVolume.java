package pl.mateusz.helios;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

/** Device output volume as a 0..100 percent over STREAM_MUSIC, polled every 3 s so Cast and hardware buttons are reflected in HA. */
final class DeviceVolume {
    static final long POLL_MS=3000;
    private final AudioManager audio;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Consumer<Integer> onChange;
    private final int max;
    private int last=-1;
    private boolean running;
    private final Runnable poll=new Runnable(){public void run(){if(!running)return;check();handler.postDelayed(this,POLL_MS);}};
    DeviceVolume(Context context,Consumer<Integer> onChange){
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);this.onChange=onChange;
        max=Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }
    void start(){if(running)return;running=true;handler.post(poll);}
    void stop(){running=false;handler.removeCallbacks(poll);}
    int percent(){return toPercent(audio.getStreamVolume(AudioManager.STREAM_MUSIC),max);}
    /** Sets the shared MUSIC level without the system volume UI and reports the level actually read back. */
    int set(int percent){
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,toSteps(percent,max),0);
        check();return last;
    }
    private void check(){int now=percent();if(now!=last){last=now;onChange.accept(now);}}
    static int toSteps(int percent,int max){return (int)Math.round(Math.max(0,Math.min(100,percent))*max/100.0);}
    static int toPercent(int steps,int max){return (int)Math.round(Math.max(0,Math.min(max,steps))*100.0/max);}
}
