package pl.mateusz.helios;

/**
 * MA has no pause for a Sendspin player: pause = the stream stops and the queue stays "paused" on the server (SPEC 0.11 pkt 4).
 * Pure timing of that card-keeping pause: it starts tentatively when a session ends while MA is reachable, is confirmed or
 * refuted by the queue state, ends when the queue reports idle/stopped, when a new session starts, 10 s after a "playing"
 * without a new stream (another device took the queue), or after 60 minutes. All times are SystemClock.elapsedRealtime().
 */
final class QueuePause {
    static final long LIMIT_MS=60*60_000L,PLAYING_GRACE_MS=10_000L;
    private boolean paused;
    private long pausedAt,playingDeadline=-1;
    /** A Sendspin session ended: tentative pause only when MA can still tell us about the queue. Returns the new paused flag. */
    boolean onSessionEnded(boolean hasMa,long now){
        paused=hasMa;pausedAt=now;playingDeadline=-1;return paused;
    }
    /** Queue state from get_active_queue or queue_updated; null = no active queue. */
    void onQueueState(String state,long now){
        if(!paused)return;
        if("paused".equals(state)){playingDeadline=-1;pausedAt=now;} // confirmed again: the hour counts from here
        else if("playing".equals(state))arm(now);
        else paused=false;
    }
    /** Our own player's playback_state: "playing" arms the takeover deadline, "idle" is the normal look of a paused Sendspin queue. */
    void onPlayerUpdate(String state,long now){if(paused&&"playing".equals(state))arm(now);}
    void onNewSession(){paused=false;playingDeadline=-1;}
    boolean paused(long now){
        if(paused&&(now-pausedAt>=LIMIT_MS||(playingDeadline>=0&&now>=playingDeadline)))paused=false;
        return paused;
    }
    /** Milliseconds until the pause expires by itself, or -1 when it is not paused. */
    long expiresInMs(long now){
        if(!paused(now))return -1;
        long limit=pausedAt+LIMIT_MS-now;
        return playingDeadline<0?limit:Math.min(limit,playingDeadline-now);
    }
    private void arm(long now){if(playingDeadline<0)playingDeadline=now+PLAYING_GRACE_MS;}
}
