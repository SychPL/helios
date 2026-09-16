package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class QueuePauseTest {
    @Test public void endedSessionPausesOnlyWhileMaIsReachable(){
        QueuePause q=new QueuePause();
        assertFalse(q.onSessionEnded(false,1000));assertFalse(q.paused(1000));
        assertTrue(q.onSessionEnded(true,1000));assertTrue(q.paused(1000));
        assertEquals(QueuePause.LIMIT_MS,q.expiresInMs(1000));
    }
    @Test public void queueStateConfirmsOrEndsThePause(){
        QueuePause q=new QueuePause();
        q.onSessionEnded(true,0);q.onQueueState("paused",100);assertTrue(q.paused(200));
        q.onQueueState("idle",300);assertFalse(q.paused(300));
        q.onSessionEnded(true,0);q.onQueueState(null,100);assertFalse(q.paused(100));
        q.onSessionEnded(true,0);q.onQueueState("stopped",100);assertFalse(q.paused(100));
        q.onSessionEnded(true,0);q.onPlayerUpdate("idle",100);assertTrue("player idle is a paused sendspin queue",q.paused(5000));
    }
    @Test public void playingWithoutANewSessionEndsAfterTenSecondsAndANewSessionEndsAtOnce(){
        QueuePause q=new QueuePause();
        q.onSessionEnded(true,0);q.onQueueState("playing",1000);
        assertTrue(q.paused(10_999));assertEquals(1,q.expiresInMs(10_999));
        assertFalse(q.paused(11_000));
        q.onSessionEnded(true,0);q.onPlayerUpdate("playing",1000);q.onPlayerUpdate("playing",5000); // the first playing sets the deadline
        assertTrue(q.paused(10_999));assertFalse(q.paused(11_000));
        q.onSessionEnded(true,0);q.onQueueState("playing",1000);q.onQueueState("paused",2000); // paused again: deadline lifted
        assertTrue(q.paused(20_000));
        q.onSessionEnded(true,0);q.onQueueState("playing",1000);q.onNewSession();assertFalse(q.paused(1500));
    }
    @Test public void pauseExpiresAfterAnHour(){
        QueuePause q=new QueuePause();
        q.onSessionEnded(true,0);assertTrue(q.paused(QueuePause.LIMIT_MS-1));assertFalse(q.paused(QueuePause.LIMIT_MS));
    }
}
