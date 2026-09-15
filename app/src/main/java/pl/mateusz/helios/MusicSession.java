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
    private boolean ducked,pausedByFocus,permanentLoss;

    MusicSession(Sink sink,Transport transport){this.sink=sink;this.transport=transport;}
    boolean pausedByFocus(){return pausedByFocus;}
    boolean permanentLoss(){return permanentLoss;}
    Ui ui(){
        if(state==SendspinClient.State.NONE)return Ui.NONE;
        if(state==SendspinClient.State.PAUSED||pausedByFocus)return Ui.PAUSED;
        return Ui.PLAYING;
    }
    void onTransport(SendspinClient.State next){
        state=next;
        if(next==SendspinClient.State.NONE){pausedByFocus=false;ducked=false;}
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
    /** Our own conversation ended; the speaker is free only if the system grants focus again. */
    void onVoiceReady(FocusRequester focus){
        if(permanentLoss||(!pausedByFocus&&!ducked))return;
        if(focus.request())restore();
    }
    /** The user pressed play: a fresh focus request lifts even a permanent loss. */
    boolean onUserPlay(FocusRequester focus){
        if(!focus.request())return false;
        permanentLoss=false;restore();return true;
    }
    private void restore(){
        if(ducked){ducked=false;sink.duck(false);}
        if(pausedByFocus){pausedByFocus=false;sink.resume();}
    }
}
