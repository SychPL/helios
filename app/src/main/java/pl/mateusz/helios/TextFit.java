package pl.mateusz.helios;

/** Largest text size (integer steps) whose measured width fits; the measurer is Paint.measureText on Android and a linear stub in tests. */
final class TextFit {
    interface Measurer {float width(String text,float size);}
    static final float FLOOR=8;
    private TextFit(){}
    /** Largest size in (FLOOR, max] at which text fits maxWidth; FLOOR when nothing fits. */
    static float fit(Measurer m,String text,float maxWidth,float max){
        for(float size=(float)Math.floor(max);size>FLOOR;size--)if(m.width(text,size)<=maxWidth)return size;
        return FLOOR;
    }
    /** fit() clamped to [min, max]: below min the caller decides whether clipping or shrinking wins. */
    static float size(Measurer m,String text,float maxWidth,float min,float max){
        return Math.max(min,fit(m,text,maxWidth,max));
    }
}
