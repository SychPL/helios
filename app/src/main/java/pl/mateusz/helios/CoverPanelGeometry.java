package pl.mateusz.helios;

import pl.mateusz.helios.OverlayGeometry.Box;

/**
 * "Rolety sypialni" panel in screen units (SPEC 0.9 pkt 4.1): panel is absolute, the rest is relative to it, buttons relative to their row.
 * 720 wide on a screen of 800 units or more; a narrower screen keeps 8 units a side and gives the difference up from the names.
 */
final class CoverPanelGeometry {
    static final int PAD=16;
    final Box panel,header,rowA,rowB,back,label,open,stop,close;
    final Box[] blocks,controls;
    CoverPanelGeometry(int screenW){
        int w=Math.min(720,screenW-16),inner=w-2*PAD;
        panel=new Box((screenW-w)/2,82,w,368);
        header=new Box(PAD,16,inner,40);
        rowA=new Box(PAD,64,inner,96);rowB=new Box(PAD,168,inner,96);
        back=new Box(PAD,280,inner,72);
        close=new Box(inner-80,12,72,72);stop=new Box(close.x-104,12,72,72);open=new Box(stop.x-104,12,72,72);
        label=new Box(0,0,open.x,96);
        blocks=new Box[]{header,rowA,rowB,back};
        controls=new Box[]{label,open,stop,close};
    }
}
