package pl.mateusz.helios;

import java.util.ArrayDeque;

/**
 * Server clock offset from NTP-style samples. ponytail: the sample with the smallest round trip among the last eight
 * wins; no Kalman filter or drift estimate until underrun measurements on the clock say otherwise.
 */
final class ClockOffset {
    private static final int WINDOW=8;
    private final ArrayDeque<long[]> samples=new ArrayDeque<>();
    private volatile boolean known;
    private volatile long offsetMicros;
    /** t1 client send, t2 server receive, t3 server send, t4 client receive; all microseconds, client and server clocks. */
    synchronized void sample(long t1,long t2,long t3,long t4){
        long rtt=(t4-t1)-(t3-t2);if(rtt<0)return;
        long offset=((t2-t1)+(t3-t4))/2;
        samples.addLast(new long[]{rtt,offset});
        while(samples.size()>WINDOW)samples.removeFirst();
        long best=Long.MAX_VALUE,chosen=0;
        for(long[] s:samples)if(s[0]<best){best=s[0];chosen=s[1];}
        offsetMicros=chosen;known=true;
    }
    boolean known(){return known;}
    /** Server microseconds minus the offset gives the equivalent local microseconds. */
    long toLocalMicros(long serverMicros){return serverMicros-offsetMicros;}
    long offsetMicros(){return offsetMicros;}
    synchronized void reset(){samples.clear();known=false;offsetMicros=0;}
}
