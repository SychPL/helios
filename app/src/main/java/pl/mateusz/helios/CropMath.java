package pl.mateusz.helios;

/** Centre-crop window with a focus point (0-100), clamped so the target is always fully covered: no bars, no stretch. */
final class CropMath {
    private CropMath(){}
    /** {x, y, w, h} inside the source that maps onto targetW x targetH at the source's own scale. */
    static int[] rect(int srcW,int srcH,int targetW,int targetH,int focusX,int focusY){
        double scale=Math.max(targetW/(double)srcW,targetH/(double)srcH); // fill: the larger factor covers the target
        int w=(int)Math.min(srcW,Math.round(targetW/scale)),h=(int)Math.min(srcH,Math.round(targetH/scale));
        int x=(int)Math.round((srcW-w)*clamp(focusX)/100.0),y=(int)Math.round((srcH-h)*clamp(focusY)/100.0);
        return new int[]{x,y,w,h};
    }
    private static int clamp(int v){return Math.max(0,Math.min(100,v));}
}
