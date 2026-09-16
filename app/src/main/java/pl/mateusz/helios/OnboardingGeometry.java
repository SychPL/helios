package pl.mateusz.helios;

import pl.mateusz.helios.OverlayGeometry.Box;

/** The "Wybierz Home Assistant" window (SPEC 0.10 pkt 3) in 800x480 screen units: server list on the left, actions on the right, status at the bottom. */
final class OnboardingGeometry {
    static final Box TITLE=new Box(20,8,760,48),LIST=new Box(20,64,440,360),STATUS=new Box(20,432,440,40);
    static final Box MANUAL=new Box(480,64,300,72),LATER=new Box(480,148,300,72),CANCEL=new Box(480,392,300,72);
    static final int ROW_H=72;
    static final Box[] ALL={TITLE,LIST,STATUS,MANUAL,LATER,CANCEL};
    static final Box[] BUTTONS={MANUAL,LATER,CANCEL};
    private OnboardingGeometry(){}
}
