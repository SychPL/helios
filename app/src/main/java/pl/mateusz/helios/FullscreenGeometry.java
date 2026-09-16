package pl.mateusz.helios;

import pl.mateusz.helios.OverlayGeometry.Box;

/** Music full screen hitboxes from SPEC 0.11 pkt 3.1 in 800x480 screen units; element boxes are relative to AREA (the screen below the bar). */
final class FullscreenGeometry {
    static final Box AREA=new Box(0,DashboardView.BAR,DashboardView.WIDTH,DashboardView.HEIGHT-DashboardView.BAR);
    static final Box ART=new Box(14,14,400,400),CLOSE=new Box(728,0,72,72),CLOCK=new Box(440,18,280,80),TITLE=new Box(440,108,344,96),ARTIST=new Box(440,210,344,56);
    static final Box PREVIOUS=new Box(440,278,56,72),PLAY=new Box(516,278,56,72),NEXT=new Box(592,278,56,72),STOP=new Box(668,278,56,72),MUTE=new Box(744,278,56,72);
    static final Box TIME_LEFT=new Box(14,366,72,24),TIME_RIGHT=new Box(714,366,72,24),SEEK=new Box(90,360,620,36);
    static final Box[] TOUCH={CLOSE,PREVIOUS,PLAY,NEXT,STOP,MUTE,SEEK};
    static final Box[] ALL={ART,CLOSE,CLOCK,TITLE,ARTIST,PREVIOUS,PLAY,NEXT,STOP,MUTE,TIME_LEFT,TIME_RIGHT,SEEK};
    private FullscreenGeometry(){}
}
