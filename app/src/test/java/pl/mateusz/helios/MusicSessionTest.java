package pl.mateusz.helios;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class MusicSessionTest {
    private final List<String> log=new ArrayList<>();
    private boolean transportPauseSupported=true;
    private final MusicSession session=new MusicSession(new MusicSession.Sink(){
        public void pause(){log.add("sink.pause");}
        public void resume(){log.add("sink.resume");}
        public void duck(boolean on){log.add("duck:"+on);}
    },()->{log.add("transport.pause");return transportPauseSupported;});

    @Test public void transientLossPausesAndResumesOnlyWhenFocusIsGrantedAgain(){
        session.onTransport(SendspinClient.State.PLAYING);
        assertEquals(MusicSession.Ui.PLAYING,session.ui());
        session.onFocusChange(MusicSession.FOCUS_LOSS_TRANSIENT);
        assertEquals("[sink.pause]",log.toString());assertEquals(MusicSession.Ui.PAUSED,session.ui());
        session.onVoiceReady(()->false);
        assertEquals(MusicSession.Ui.PAUSED,session.ui());assertEquals(1,log.size());
        session.onVoiceReady(()->true);
        assertEquals("[sink.pause, sink.resume]",log.toString());assertEquals(MusicSession.Ui.PLAYING,session.ui());
    }
    @Test public void duckingIsLiftedOnGainAndOnGrantedRequest(){
        session.onTransport(SendspinClient.State.PLAYING);
        session.onFocusChange(MusicSession.FOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals("[duck:true]",log.toString());assertEquals(MusicSession.Ui.PLAYING,session.ui());
        session.onFocusChange(MusicSession.FOCUS_GAIN);
        assertEquals("[duck:true, duck:false]",log.toString());
        session.onFocusChange(MusicSession.FOCUS_LOSS_TRANSIENT_CAN_DUCK);
        session.onVoiceReady(()->true);
        assertEquals("[duck:true, duck:false, duck:true, duck:false]",log.toString());
    }
    @Test public void aStreamStartedMidConversationStaysDuckedUntilTheConversationEnds(){
        session.onTransport(SendspinClient.State.PLAYING);
        session.duckForVoice();session.duckForVoice(); // a second call must not stack
        assertEquals("[duck:true]",log.toString());assertEquals(MusicSession.Ui.PLAYING,session.ui());
        session.onVoiceReady(()->true);
        assertEquals("[duck:true, duck:false]",log.toString());
    }
    @Test public void permanentLossPausesThroughTheTransportAndNeverResumesByItself(){
        session.onTransport(SendspinClient.State.PLAYING);
        session.onFocusChange(MusicSession.FOCUS_LOSS);
        assertEquals("[transport.pause]",log.toString());assertTrue(session.permanentLoss());assertEquals(MusicSession.Ui.PAUSED,session.ui());
        session.onFocusChange(MusicSession.FOCUS_GAIN);
        session.onVoiceReady(()->true);
        assertEquals("[transport.pause]",log.toString());
        assertFalse(session.onUserPlay(()->false));assertTrue(session.permanentLoss());
        assertTrue(session.onUserPlay(()->true));assertFalse(session.permanentLoss());
        assertEquals("[transport.pause, sink.resume]",log.toString());
    }
    @Test public void permanentLossFallsBackToLocalPauseWhenTheTransportCannotPause(){
        transportPauseSupported=false;
        session.onTransport(SendspinClient.State.PLAYING);
        session.onFocusChange(MusicSession.FOCUS_LOSS);
        assertEquals("[transport.pause, sink.pause]",log.toString());
    }
    @Test public void transportStatesMapToUiAndEndOfSessionClearsFocusFlags(){
        assertEquals(MusicSession.Ui.NONE,session.ui());
        session.onTransport(SendspinClient.State.PAUSED);assertEquals(MusicSession.Ui.PAUSED,session.ui());
        session.onTransport(SendspinClient.State.PLAYING);session.onFocusChange(MusicSession.FOCUS_LOSS_TRANSIENT);
        session.onTransport(SendspinClient.State.NONE);assertEquals(MusicSession.Ui.NONE,session.ui());assertFalse(session.pausedByFocus());
        session.onTransport(SendspinClient.State.PLAYING);assertEquals(MusicSession.Ui.PLAYING,session.ui());
    }
}
