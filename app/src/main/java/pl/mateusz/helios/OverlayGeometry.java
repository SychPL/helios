package pl.mateusz.helios;

/** Music overlay hitboxes from SPEC 0.8a pkt 4.1 in 800x480 screen units; element boxes are relative to PANEL, HANDLE is absolute. */
final class OverlayGeometry {
    static final class Box {
        final int x,y,w,h;
        Box(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;}
        boolean overlaps(Box o){return x<o.x+o.w&&o.x<x+w&&y<o.y+o.h&&o.y<y+h;}
        boolean contains(Box o){return o.x>=x&&o.y>=y&&o.x+o.w<=x+w&&o.y+o.h<=y+h;}
        boolean contains(float px,float py){return px>=x&&px<x+w&&py>=y&&py<y+h;}
    }
    static final Box PANEL=new Box(400,60,392,412);
    static final Box STATUS=new Box(16,16,280,48),CLOSE=new Box(320,0,72,72),ART=new Box(16,76,144,144),TITLE=new Box(176,76,200,84),ARTIST=new Box(176,168,200,48);
    static final Box PREVIOUS=new Box(16,228,72,72),PLAY=new Box(112,228,72,72),NEXT=new Box(208,228,72,72),STOP=new Box(304,228,72,72);
    static final Box MUTE=new Box(16,316,72,72),SEEK=new Box(96,316,280,72); // SEEK: track position, not volume (the clock has hardware volume keys)
    static final Box HANDLE=new Box(728,204,72,72);
    static final Box[] TOUCH={CLOSE,PREVIOUS,PLAY,NEXT,STOP,MUTE,SEEK};
    static final Box[] ALL={STATUS,CLOSE,ART,TITLE,ARTIST,PREVIOUS,PLAY,NEXT,STOP,MUTE,SEEK};
    private OverlayGeometry(){}
    /** Grid cell in screen units (same formula as DashboardView.arrange). */
    static Box cell(int column,int row){
        int cellW=(DashboardView.WIDTH-DashboardView.GAP*(DashboardSpec.COLUMNS+1))/DashboardSpec.COLUMNS,cellH=(DashboardView.HEIGHT-DashboardView.BAR-DashboardView.GAP*(DashboardSpec.ROWS+1))/DashboardSpec.ROWS;
        return new Box(DashboardView.GAP+(column-1)*(cellW+DashboardView.GAP),DashboardView.BAR+DashboardView.GAP+(row-1)*(cellH+DashboardView.GAP),cellW,cellH);
    }
}
