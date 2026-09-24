package pl.mateusz.helios;

/**
 * Pure state machine joining the transport state with Android audio focus (SPEC 0.6 pkt 5.3, 8; plan R4/R6):
 * transient loss pauses locally and resumes on regained focus; permanent loss (Cast, another app) pauses via the
 * transport and never resumes by itself; after our own voice session the sink resumes only if focus is granted again.
 */
final class MusicSession {
    enum Ui {NONE,PLAYING,PAUSED}
    interface Sink {void pause();void resume();void duck(boolean on);}
    interface Transport {boolean pause();}
    interface FocusRequester {boolean request();}
    static final int FOCUS_GAIN=1,FOCUS_LOSS=-1,FOCUS_LOSS_TRANSIENT=-2,FOCUS_LOSS_TRANSIENT_CAN_DUCK=-3;
    private final Sink sink;
    private final Transport transport;
    private SendspinClient.State state=SendspinClient.State.NONE;
    private boolean ducked,voiceDucked,pausedByFocus,permanentLoss; // two duck reasons: a focus loss, and a stream started mid-conversation

    MusicSession(Sink sink,Transport transport){this.sink=sink;this.transport=transport;}
    boolean pausedByFocus(){return pausedByFocus;}
    boolean permanentLoss(){return permanentLoss;}
    Ui ui(){
        if(state==SendspinClient.State.NONE)return Ui.NONE;
        if(state==SendspinClient.State.PAUSED||pausedByFocus)return Ui.PAUSED;
        return Ui.PLAYING;
    }
    /** NONE means the output is already closed (SendspinClient closes it synchronously): lift ducking and the focus pause so the next stream starts clean. */
    void onTransport(SendspinClient.State next){
        state=next;
        if(next==SendspinClient.State.NONE){liftVoiceDuck();restore();permanentLoss=false;} // the sink keeps its gain across streams, so both reasons go here
    }
    void onFocusChange(int change){
        switch(change){
            case FOCUS_LOSS:permanentLoss=true;pausedByFocus=true;if(!transport.pause())sink.pause();break;
            case FOCUS_LOSS_TRANSIENT:pausedByFocus=true;sink.pause();break;
            case FOCUS_LOSS_TRANSIENT_CAN_DUCK:ducked=true;sink.duck(true);break;
            case FOCUS_GAIN:if(permanentLoss)break;restore();break;
            default:break;
        }
    }
    /** A stream that starts mid-conversation (the user just asked for music) took focus from the assistant with its GAIN
     *  request, so no transient loss will ever duck it: it starts ducked and onVoiceReady lifts it like any other duck. */
    void duckForVoice(){voiceDucked=true;sink.duck(true);} // idempotent on purpose: re-applies the level even after a stale restore
    /** Our own conversation ended; the speaker is free only if the system grants focus again. */
    void onVoiceReady(FocusRequester focus){
        liftVoiceDuck(); // the conversation is over whatever focus says; a focus duck stays
        if(permanentLoss||(!pausedByFocus&&!ducked))return;
        if(focus.request())restore();
    }
    /** The user pressed play: a fresh focus request lifts even a permanent loss. */
    boolean onUserPlay(FocusRequester focus){
        if(!focus.request())return false;
        permanentLoss=false;restore();return true;
    }
    /** Sets the sink to what the duck reasons say now; used when a level set ahead of the state machine may be stale. */
    void reapplyDuck(){sink.duck(ducked||voiceDucked);}
    private void liftVoiceDuck(){if(voiceDucked){voiceDucked=false;if(!ducked)sink.duck(false);}}
    private void restore(){
        if(ducked){ducked=false;if(!voiceDucked)sink.duck(false);} // regained focus must not lift the conversation's duck
        if(pausedByFocus){pausedByFocus=false;sink.resume();}
    }
}
