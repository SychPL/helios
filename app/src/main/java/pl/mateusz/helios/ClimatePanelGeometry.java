package pl.mateusz.helios;

import pl.mateusz.helios.OverlayGeometry.Box;

/** The climate panel in absolute 800x480 units (SPEC 0.19 pkt 5, grid agreed with Codex); the window starts under the bar at WINDOW. */
final class ClimatePanelGeometry {
    /** The dialog window: everything under the HELIOS bar, which stays visible. */
    static final Box WINDOW=new Box(0,DashboardView.BAR,DashboardView.WIDTH,DashboardView.HEIGHT-DashboardView.BAR);
    static final Box TITLE=new Box(16,52,696,64),CLOSE=new Box(728,52,64,64);
    static final Box NOW_CARD=new Box(8,124,373,156),SET_CARD=new Box(389,124,403,156);
    static final Box NOW_LABEL=new Box(28,136,337,20),SET_LABEL=new Box(409,136,367,20);
    static final Box NOW_VALUE=new Box(24,160,341,76),ACTIVITY=new Box(28,248,337,24);
    static final Box MINUS=new Box(405,160,80,80),SET_VALUE=new Box(493,160,195,80),PLUS=new Box(696,160,80,80),SET_STATUS=new Box(405,248,371,24);
    static final Box MODE_LABEL=new Box(16,288,776,16),MODES=new Box(8,312,784,64),LISTS=new Box(8,384,784,88);
    static final int GAP=8;
    /** Every touch target, for the >= 64 px rule. */
    static final Box[] TOUCH={CLOSE,MINUS,PLUS,MODES,LISTS};
    static final Box[] CARDS={TITLE,CLOSE,NOW_CARD,SET_CARD,MODE_LABEL,MODES,LISTS};

    /** n equal cells across a row with GAP between them. */
    static Box cell(Box row,int n,int i){
        float w=(row.w-GAP*(n-1))/(float)n;
        return new Box(Math.round(row.x+i*(w+GAP)),row.y,Math.round(w),row.h);
    }
    /** The option picker: 448 wide, rows of 64 every 72, four full rows and half a fifth visible (the scroll cue). */
    static final Box PICKER=new Box(176,52,448,420),PICKER_TITLE=new Box(24,20,340,40),PICKER_CLOSE=new Box(372,8,64,64),PICKER_LIST=new Box(16,84,416,320);
    static final int ROW=64,ROW_STEP=72;
    private ClimatePanelGeometry(){}
}
