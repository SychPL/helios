package pl.mateusz.helios;

import pl.mateusz.helios.OverlayGeometry.Box;

/** "Rolety sypialni" panel in 800x480 screen units (SPEC 0.9 pkt 4.1): PANEL is absolute, the rest is relative to PANEL, buttons relative to their row. */
final class CoverPanelGeometry {
    static final Box PANEL=new Box(40,82,720,368);
    static final int PAD=16;
    static final Box HEADER=new Box(16,16,688,40);
    static final Box ROW_A=new Box(16,64,688,96),ROW_B=new Box(16,168,688,96);
    static final Box BACK=new Box(16,280,688,72);
    static final Box LABEL=new Box(0,0,400,96),OPEN=new Box(400,12,72,72),STOP=new Box(504,12,72,72),CLOSE=new Box(608,12,72,72);
    static final Box[] BLOCKS={HEADER,ROW_A,ROW_B,BACK};
    static final Box[] CONTROLS={LABEL,OPEN,STOP,CLOSE};
    private CoverPanelGeometry(){}
}
