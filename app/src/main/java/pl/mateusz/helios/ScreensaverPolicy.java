package pl.mateusz.helios;

/**
 * Decides when the panel gives way to the night clock (SPEC 0.13). No Android here: the whole contract is
 * a monotonic clock, the last light reading and one aggregated "something wants attention" flag, so every
 * rule below is checked in unit tests rather than in a dark bedroom.
 *
 * One timestamp drives the wait. {@code eligibleSince} is the moment darkness, silence and the absence of a
 * touch started holding together; anything that breaks one of them clears it, so a blocker that ends starts
 * the wait from zero instead of dropping the screensaver in mid-sentence.
 */
final class ScreensaverPolicy {
    static final long IDLE_MS=60_000L,MIN_AWAKE_MS=15_000L,IDLE_MIN_MS=15_000L,IDLE_MAX_MS=3_600_000L;
    static final int DEFAULT_ENTER=3,DEFAULT_EXIT=8,MAX_THRESHOLD=100;
    private static final long NEVER=Long.MIN_VALUE;

    /** OFF never dims; DARK waits for a dark room (SPEC 0.13); ALWAYS only waits for quiet (SPEC 0.14). */
    enum Mode {OFF,DARK,ALWAYS}
    private Mode mode=Mode.DARK;

    private long idleMs;
    private final long minAwakeMs;
    private int enter=DEFAULT_ENTER,exit=DEFAULT_EXIT;
    private boolean dark,luxKnown,active;
    private long eligibleSince=NEVER;

    ScreensaverPolicy(){this(IDLE_MS,MIN_AWAKE_MS);}
    ScreensaverPolicy(long idleMs,long minAwakeMs){this.idleMs=idleMs;this.minAwakeMs=minAwakeMs;}

    /** Enter below this many lux, leave at or above the other one; the gap is the hysteresis. */
    boolean thresholds(int darkEnter,int darkExit){
        if(darkEnter<0||darkExit<=darkEnter||darkExit>MAX_THRESHOLD)return false;
        enter=darkEnter;exit=darkExit;
        if(luxKnown)relatch();
        return true;
    }
    int darkEnter(){return enter;}
    int darkExit(){return exit;}

    private int lastLux;
    private void relatch(){if(lastLux<=enter)dark=true;else if(lastLux>=exit)dark=false;}

    /** A reading from the light sensor. Until the first one arrives the room is "unknown", never "dark". */
    void lux(int lux){lastLux=lux;luxKnown=true;relatch();}
    boolean luxKnown(){return luxKnown;}
    int lux(){return lastLux;}
    boolean dark(){return luxKnown&&dark;}

    /** The activity was resumed: the previous reading says nothing about the room we came back to. */
    void forget(){luxKnown=false;dark=false;eligibleSince=NEVER;active=false;}

    /**
     * A touch, or the wake word. Clearing the stamp is the whole guarantee: the next quiet moment starts a
     * fresh wait of {@code max(IDLE, MIN_AWAKE)}, so the panel stays up at least that long after a finger.
     */
    void interaction(){eligibleSince=NEVER;active=false;}

    /**
     * @param blocked anything that wants the panel visible: a conditional tile, a music session that is not
     *                NONE, a conversation, an open window, a problem talking to Home Assistant.
     * @return whether the night clock should be on screen now.
     */
    boolean update(long now,boolean blocked){
        if(!environmentAllows()||blocked){eligibleSince=NEVER;active=false;return false;}
        if(eligibleSince==NEVER)eligibleSince=now;
        active=now-eligibleSince>=Math.max(idleMs,minAwakeMs);
        return active;
    }

    private boolean environmentAllows(){
        switch(mode){
            case OFF:return false;
            case ALWAYS:return true;
            default:return luxKnown&&dark;
        }
    }

    /** A mode arriving from Home Assistant starts a whole new wait, never finishes an old one. */
    void mode(Mode value){if(value==null||value==mode)return;mode=value;eligibleSince=NEVER;active=false;}
    Mode mode(){return mode;}

    /** Idle time from Home Assistant; out-of-range values are refused so a typo cannot blank the panel. */
    boolean idleMs(long value){
        if(value<IDLE_MIN_MS||value>IDLE_MAX_MS)return false;
        idleMs=value;eligibleSince=NEVER;active=false;return true;
    }
    long idleMs(){return idleMs;}

    /** Photos belong to a lit room: in the dark the night clock stays black whatever the configuration says. */
    boolean photosAllowed(){return mode==Mode.ALWAYS&&luxKnown&&!dark;}

    boolean active(){return active;}
}
