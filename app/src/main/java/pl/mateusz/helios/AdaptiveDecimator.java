package pl.mateusz.helios;

import java.util.Arrays;

/**
 * Repairs the vendor-HAL sample flood observed on this clock since 2026-09-15 (artifacts/wakeword-microphone-20260915.md):
 * the mic input delivers a fixed multiple (~6x) of the requested rate while AudioRecord keeps reporting 16 kHz mono. The
 * stream looks like interleaved 48 kHz stereo, so averaging groups of k samples yields a valid target-rate mono signal.
 *
 * Calibration: raw samples are held back until the first measurement window completes, then replayed through the chosen
 * factor, so nothing is lost. The factor is re-measured every window and switches only when a different value is seen in
 * two consecutive windows AND the measured rate sits within TOLERANCE of that value (no 5/6 flapping on jittery HALs);
 * a HAL that returns to normal heals the stream the same way (factor 1 = passthrough).
 *
 * ponytail: heuristic workaround, remove once the foreign 48 kHz stereo capture client (see wake_level "active=") is gone.
 */
final class AdaptiveDecimator {
    static final int MIN_FACTOR=1,MAX_FACTOR=12;
    static final double TOLERANCE=.08;
    private final int targetRate,windowMs;
    private short[] out=new short[8192];private int outLen,outRead;
    private short[] holdback=new short[16384];private int holdbackLen;
    private int factor=1,candidate,votes,phase,acc;
    private boolean calibrated;
    private long windowSamples,windowStartMs=Long.MIN_VALUE;
    private double measuredRate;

    AdaptiveDecimator(int targetRate,int windowMs){this.targetRate=targetRate;this.windowMs=windowMs;}

    /** Feeds raw mic samples read at nowMs (elapsedRealtime); decimated output becomes available through poll(). */
    void push(short[] in,int count,long nowMs){
        if(count<=0)return;
        if(windowStartMs==Long.MIN_VALUE){
            // the first chunk was captured before this moment: start the window now but keep those samples out of the rate estimate
            windowStartMs=nowMs;
            if(!calibrated)hold(in,count);
            return;
        }
        windowSamples+=count;
        if(!calibrated)hold(in,count);
        long elapsed=nowMs-windowStartMs;
        if(elapsed>=windowMs){
            measuredRate=windowSamples*1000.0/elapsed;
            int k=clamp((int)Math.round(measuredRate/targetRate));
            boolean plausible=Math.abs(measuredRate/(k*(double)targetRate)-1)<TOLERANCE;
            windowSamples=0;windowStartMs=nowMs;
            if(!calibrated){factor=k;calibrated=true;replayHoldback();return;}
            if(k!=factor&&plausible){
                if(k==candidate)votes++;else{candidate=k;votes=1;}
                if(votes>=2){factor=k;phase=0;acc=0;candidate=0;votes=0;}
            }else{candidate=0;votes=0;}
        }
        if(calibrated)for(int i=0;i<count;i++)consume(in[i]); // the chunk that closed a window is live audio too
    }
    private void hold(short[] in,int count){
        if(holdbackLen+count>holdback.length)holdback=Arrays.copyOf(holdback,Math.max(holdback.length*2,holdbackLen+count));
        System.arraycopy(in,0,holdback,holdbackLen,count);holdbackLen+=count;
    }
    private void replayHoldback(){
        for(int i=0;i<holdbackLen;i++)consume(holdback[i]);
        holdback=new short[0];holdbackLen=0;
    }
    private void consume(short sample){
        if(factor==MIN_FACTOR){emit(sample);return;}
        acc+=sample;
        if(++phase==factor){emit((short)(acc/factor));acc=0;phase=0;}
    }
    private void emit(short sample){
        if(outLen==out.length){
            if(outRead>0){System.arraycopy(out,outRead,out,0,outLen-outRead);outLen-=outRead;outRead=0;}
            if(outLen==out.length)out=Arrays.copyOf(out,out.length*2);
        }
        out[outLen++]=sample;
    }
    /** Decimated samples waiting for poll(). */
    int available(){return outLen-outRead;}
    /** Copies up to max decimated samples into dst at off; returns the count (0 when nothing is ready). */
    int poll(short[] dst,int off,int max){
        int n=Math.min(max,available());
        if(n<=0)return 0;
        System.arraycopy(out,outRead,dst,off,n);outRead+=n;
        if(outRead==outLen){outRead=0;outLen=0;}
        return n;
    }
    /** True once the first rate measurement completed and output flows. */
    boolean calibrated(){return calibrated;}
    /** Current decimation factor (1 = passthrough, 6 = the observed flood). */
    int factor(){return factor;}
    /** Rate measured over the most recent window, raw samples per second. */
    double measuredRate(){return measuredRate;}
    private static int clamp(int k){return Math.max(MIN_FACTOR,Math.min(MAX_FACTOR,k));}
}
