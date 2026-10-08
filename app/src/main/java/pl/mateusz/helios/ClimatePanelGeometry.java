package pl.mateusz.helios;

import pl.mateusz.helios.OverlayGeometry.Box;

/**
 * The climate panel in absolute screen units (SPEC 0.19 pkt 5, grid agreed with Codex); the window starts under the bar.
 * From 640 units across (4:3 up) the room and the setpoint cards sit side by side and the setpoint card takes any extra width;
 * at 800 that is exactly the agreed grid. A narrower screen (480 on a square panel) stacks a short room line over the setpoint card.
 */
final class ClimatePanelGeometry {
    static final int GAP=8;
    /** The option picker: 448 wide, rows of 64 every 72, four full rows and half a fifth visible (the scroll cue). */
    static final Box PICKER_TITLE=new Box(24,20,340,40),PICKER_CLOSE=new Box(372,8,64,64),PICKER_LIST=new Box(16,84,416,320);
    static final int ROW=64,ROW_STEP=72;
    /** The dialog window: everything under the HELIOS bar, which stays visible. */
    final Box window,title,close,nowCard,setCard,nowLabel,setLabel,nowValue,activity,minus,setValue,plus,setStatus,modeLabel,modes,lists,picker;
    /** Text sizes of the room temperature and of the setpoint. */
    final float nowSize,setSize;
    /** Every touch target, for the >= 64 px rule. */
    final Box[] touch;
    final Box[] cards;

    ClimatePanelGeometry(int w,int h){
        window=new Box(0,DashboardView.BAR,w,h-DashboardView.BAR);
        title=new Box(16,52,w-104,64);close=new Box(w-72,52,64,64);
        if(w>=640){
            nowCard=new Box(8,124,373,156);setCard=new Box(389,124,w-397,156);
            nowLabel=new Box(28,136,337,20);setLabel=new Box(setCard.x+20,136,setCard.w-36,20);
            nowValue=new Box(24,160,341,76);activity=new Box(28,248,337,24);
            minus=new Box(setCard.x+16,160,80,80);plus=new Box(setCard.x+setCard.w-96,160,80,80);
            setValue=new Box(minus.x+88,160,plus.x-8-(minus.x+88),80);setStatus=new Box(setCard.x+16,248,setCard.w-32,24);
            modeLabel=new Box(16,288,w-24,16);modes=new Box(8,312,w-16,64);lists=new Box(8,384,w-16,88);
            nowSize=76;setSize=64;
        }else{
            nowCard=new Box(8,124,w-16,56);
            nowLabel=new Box(28,124,104,56);nowValue=new Box(132,124,136,56);activity=new Box(276,124,w-292,56);
            setCard=new Box(8,188,w-16,100);
            setLabel=new Box(28,194,104,24);setStatus=new Box(132,194,w-156,24);
            minus=new Box(24,220,64,64);plus=new Box(w-88,220,64,64);setValue=new Box(96,220,w-192,64);
            modeLabel=new Box(16,296,w-32,16);modes=new Box(8,320,w-16,64);lists=new Box(8,392,w-16,80);
            nowSize=40;setSize=56;
        }
        picker=new Box((w-448)/2,52,448,420);
        touch=new Box[]{close,minus,plus,modes,lists};
        cards=new Box[]{title,close,nowCard,setCard,modeLabel,modes,lists};
    }

    /** n equal cells across a row with GAP between them. */
    static Box cell(Box row,int n,int i){
        float w=(row.w-GAP*(n-1))/(float)n;
        return new Box(Math.round(row.x+i*(w+GAP)),row.y,Math.round(w),row.h);
    }
}
