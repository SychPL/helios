package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreensaverPolicyTest {
    private static final long IDLE=60_000L,MIN_AWAKE=15_000L,TICK=1_000L;
    private final ScreensaverPolicy policy=new ScreensaverPolicy(IDLE,MIN_AWAKE);
    private long now;

    /** The app re-evaluates on a timer; the tests tick the same way instead of jumping in one step. */
    private boolean run(long millis,boolean blocked){
        boolean active=policy.update(now,blocked);
        long until=now+millis;
        while(now<until){now=Math.min(now+TICK,until);active=policy.update(now,blocked);}
        return active;
    }

    @Test public void darkAndQuietTurnsIntoTheNightClockAfterTheIdleWait(){
        policy.lux(1);
        assertFalse(run(IDLE-TICK,false));
        assertTrue(run(TICK,false));
    }

    @Test public void unknownLightIsNeverDarkness(){
        assertFalse(run(10*IDLE,false));
        assertFalse(policy.dark());
    }

    @Test public void hysteresisKeepsTheStateBetweenTheThresholds(){
        policy.lux(1);
        assertTrue(policy.dark());
        policy.lux(5); // between the thresholds: the state stands
        assertTrue(policy.dark());
        policy.lux(8); // at the exit threshold: lit
        assertFalse(policy.dark());
        policy.lux(5);
        assertFalse(policy.dark());
        policy.lux(3);
        assertTrue(policy.dark());
    }

    @Test public void lightWakesThePanelImmediately(){
        policy.lux(0);
        assertTrue(run(IDLE,false));
        policy.lux(30);
        assertFalse(run(0,false));
    }

    @Test public void aBlockerHoldsThePanelAndRestartsTheWaitWhenItEnds(){
        policy.lux(0);
        assertFalse(run(IDLE,true));             // something wants attention
        assertFalse(run(IDLE-TICK,false));       // the wait starts over, not where it left off
        assertTrue(run(TICK,false));
    }

    @Test public void aTouchBuysAtLeastTheGuaranteedTime(){
        policy.lux(0);
        assertTrue(run(IDLE,false));
        policy.interaction();
        assertFalse(policy.active());
        assertFalse(run(MIN_AWAKE,false));
        assertTrue(run(IDLE,false));
    }

    @Test public void theGuaranteeSurvivesAnAbsurdlyShortIdleSetting(){
        ScreensaverPolicy eager=new ScreensaverPolicy(TICK,MIN_AWAKE);
        eager.lux(0);
        long t=0;
        eager.update(t,false);
        eager.interaction();
        boolean active=false;
        while(t<MIN_AWAKE){t+=TICK;active=eager.update(t,false);}
        assertFalse(active);                      // idle would have fired long ago; the guarantee holds
        while(t<MIN_AWAKE+2*TICK){t+=TICK;active=eager.update(t,false);}
        assertTrue(active);                       // and the night clock does return right after it
    }

    @Test public void comingBackToTheForegroundForgetsTheOldReading(){
        policy.lux(0);
        assertTrue(run(IDLE,false));
        policy.forget();
        assertFalse(policy.active());
        assertFalse(policy.luxKnown());
        assertFalse(run(2*IDLE,false));           // silent until the sensor speaks again
        policy.lux(0);
        assertTrue(run(IDLE,false));
    }

    @Test public void thresholdsAreValidatedAndApplyAtOnce(){
        assertFalse(policy.thresholds(5,5));
        assertFalse(policy.thresholds(9,8));
        assertFalse(policy.thresholds(-1,8));
        assertFalse(policy.thresholds(3,ScreensaverPolicy.MAX_THRESHOLD+1));
        assertEquals(ScreensaverPolicy.DEFAULT_ENTER,policy.darkEnter());
        policy.lux(12);
        assertFalse(policy.dark());
        assertTrue(policy.thresholds(20,40));     // a brighter room now counts as dark
        assertTrue(policy.dark());
    }
}
