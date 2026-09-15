package pl.mateusz.helios;

/** Mean RGB of a small pixel sample (the cover shrunk to at most 16x16): the play button tint. No AndroidX palette. */
final class AccentColor {
    private AccentColor(){}
    /** Opaque average colour, or 0 when there are no pixels (caller falls back to the theme accent). */
    static int average(int[] argb){
        if(argb==null||argb.length==0)return 0;
        long r=0,g=0,b=0;
        for(int p:argb){r+=(p>>16)&255;g+=(p>>8)&255;b+=p&255;}
        int n=argb.length;
        return 0xFF000000|((int)(r/n)<<16)|((int)(g/n)<<8)|(int)(b/n);
    }
}
