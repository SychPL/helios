package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveDecimatorTest {
    /** Feeds `samples` in 512-sample chunks with an exact fractional clock for the given stream rate; returns samples fed. */
    private static void feed(AdaptiveDecimator d,int samples,double[] clockMs,short value,int samplesPerSec){
        short[] chunk=new short[512];int fed=0;
        while(fed<samples){
            int n=Math.min(512,samples-fed);
            for(int i=0;i<n;i++)chunk[i]=value;
            clockMs[0]+=n*1000.0/samplesPerSec;
            d.push(chunk,n,Math.round(clockMs[0]));
            fed+=n;
        }
    }
    private static int drain(AdaptiveDecimator d,short[] into){
        short[] buf=new short[333];int total=0,n;
        while((n=d.poll(buf,0,buf.length))>0){if(into!=null)System.arraycopy(buf,0,into,total,n);total+=n;}
        return total;
    }

    @Test public void passthroughWhenRateMatches(){
        AdaptiveDecimator d=new AdaptiveDecimator(16000,1500);double[] clock={0};
        feed(d,16000*3,clock,(short)999,16000);
        assertTrue(d.calibrated());assertEquals(1,d.factor());assertEquals(16000,d.measuredRate(),16000*.02);
        assertEquals(48000,d.available());
        short[] out=new short[48000];assertEquals(48000,drain(d,out));
        assertEquals((short)999,out[0]);assertEquals((short)999,out[47999]);assertEquals(0,d.available());
    }
    @Test public void recoversSixxFloodToTargetRateWithoutLosingSamples(){
        AdaptiveDecimator d=new AdaptiveDecimator(16000,1500);double[] clock={0};
        feed(d,96000*3,clock,(short)1200,96000);
        assertTrue(d.calibrated());assertEquals(6,d.factor());
        assertEquals(48000,d.available()); // 3 s of 96k decimated by 6 = exactly 3 s at 16 kHz, holdback included
        short[] out=new short[48000];assertEquals(48000,drain(d,out));
        for(short s:out)assertEquals(1200,s);
    }
    @Test public void pollReturnsEverythingAvailablePromisedAcrossManyBlocks(){
        AdaptiveDecimator d=new AdaptiveDecimator(16000,100);double[] clock={0};
        short[] chunk=new short[512];
        for(int i=0;i<200;i++){for(int j=0;j<512;j++)chunk[j]=(short)((i*512+j)%1000);clock[0]+=512*1000.0/16000;d.push(chunk,512,Math.round(clock[0]));}
        int promised=d.available();assertTrue(promised>50000);
        short[] out=new short[promised];assertEquals(promised,drain(d,out));
        for(int i=0;i<promised;i++)assertEquals("sample "+i,(short)(i%1000),out[i]); // order and continuity across internal buffer growth/compaction
    }
    @Test public void nothingEmittedBeforeFirstWindowAndFirstChunkIsNotLost(){
        AdaptiveDecimator d=new AdaptiveDecimator(16000,1500);
        short[] chunk=new short[512];java.util.Arrays.fill(chunk,(short)7);
        d.push(chunk,512,10);d.push(chunk,512,200);
        assertFalse(d.calibrated());assertEquals(0,d.available());
        double[] clock={200};feed(d,16000*2,clock,(short)7,16000);
        assertTrue(d.calibrated());assertEquals(1,d.factor());assertEquals(1024+32000,d.available());
    }
    @Test public void switchesOnlyAfterTwoAgreeingPlausibleWindows(){
        AdaptiveDecimator d=new AdaptiveDecimator(16000,1500);double[] clock={0};
        feed(d,96000*3,clock,(short)100,96000);assertEquals(6,d.factor());drain(d,null);
        feed(d,88000*2,clock,(short)100,88000); // 5.5x: jittery source-7 reading, neither 5 nor 6 within tolerance
        assertEquals(6,d.factor());
        feed(d,16000,clock,(short)55,16000);assertEquals(6,d.factor()); // less than a window of healthy audio: no switch yet
        feed(d,16000*5,clock,(short)55,16000);assertEquals(1,d.factor()); // two agreeing healthy windows: switch
        drain(d,null);feed(d,16000*2,clock,(short)55,16000);
        short[] out=new short[40000];int n=drain(d,out);assertTrue(n>0);assertEquals(55,out[n-1]);
    }
}
