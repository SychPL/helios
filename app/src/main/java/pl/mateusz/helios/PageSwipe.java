package pl.mateusz.helios;

/** Horizontal swipe between dashboard pages, as pure arithmetic in 800x480 units (SPEC 0.18). */
final class PageSwipe {
    /** How far a finger must travel sideways before the gesture is taken from the tile under it. */
    static final float SLOP=24;
    /** How far it must travel for the page to change on release. */
    static final float DISTANCE=80;
    private PageSwipe(){}

    /** True once a move is clearly sideways: past the slop and at least twice as wide as it is tall. */
    static boolean sideways(float dx,float dy){return Math.abs(dx)>SLOP&&Math.abs(dx)>2*Math.abs(dy);}

    /** The page after a finished gesture: left = next, right = previous, clamped - no wrap-around, a short or diagonal swipe stays put. */
    static int target(int page,int pages,float dx,float dy){
        if(pages<2||Math.abs(dx)<DISTANCE||Math.abs(dx)<=2*Math.abs(dy))return page;
        return Math.max(0,Math.min(pages-1,page+(dx<0?1:-1)));
    }
}
