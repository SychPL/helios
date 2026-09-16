package pl.mateusz.helios;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

/**
 * Device output volume as a 0..100 percent over STREAM_MUSIC, polled every 3 s so Cast and hardware buttons are reflected in HA;
 * 500 ms while a Sendspin session runs so MA sees the buttons quickly (plan 0.11 V2). Listeners run on the main thread.
 */
final class DeviceVolume {
    static final long POLL_MS=3000,FAST_POLL_MS=500;
    private final AudioManager audio;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.List<Consumer<Integer>> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private final int max;
    private int last=-1;
    private boolean running;
    private volatile long pollMs=POLL_MS;
    private final Runnable poll=new Runnable(){public void run(){if(!running)return;check();handler.postDelayed(this,pollMs);}};
    DeviceVolume(Context context,Consumer<Integer> onChange){
        audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);listeners.add(onChange);
        max=Math.max(1,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }
    void addListener(Consumer<Integer> listener){listeners.add(listener);}
    void setFast(boolean fast){long next=fast?FAST_POLL_MS:POLL_MS;if(next==pollMs)return;pollMs=next;if(running){handler.removeCallbacks(poll);handler.post(poll);}}
    void start(){if(running)return;running=true;handler.post(poll);}
    void stop(){running=false;handler.removeCallbacks(poll);}
    int percent(){return toPercent(audio.getStreamVolume(AudioManager.STREAM_MUSIC),max);}
    /** Sets the shared MUSIC level without the system volume UI and reports the level actually read back. */
    int set(int percent){
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,toSteps(percent,max),0);
        check();return last;
    }
    private void check(){int now=percent();if(now!=last){last=now;for(Consumer<Integer> l:listeners)l.accept(now);}}
    static int toSteps(int percent,int max){return (int)Math.round(Math.max(0,Math.min(100,percent))*max/100.0);}
    static int toPercent(int steps,int max){return (int)Math.round(Math.max(0,Math.min(max,steps))*100.0/max);}
}
