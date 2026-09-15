package pl.mateusz.helios;

import org.json.JSONObject;

/** Validated appearance snapshot from the helios channel (SPEC 0.8b pkt 4): theme plus a solid or image background. Pure. */
final class Appearance {
    static final String PATH_PREFIX="/api/helios/appearance/";
    static final int DIM_MIN=35,DIM_MAX=80;
    final String theme;final boolean image;final String imageId,path;final int dim,focusX,focusY;
    private Appearance(String theme,boolean image,String imageId,String path,int dim,int focusX,int focusY){this.theme=theme;this.image=image;this.imageId=imageId;this.path=path;this.dim=dim;this.focusX=focusX;this.focusY=focusY;}
    static Appearance solid(){return new Appearance("warm_graphite",false,null,null,0,50,50);}
    /** Cache/decode key: the same image with another focus is re-cropped, not re-fetched. */
    String key(){return image?imageId+"|"+focusX+"|"+focusY:"";}
    /** Strict whole-object parse; any problem throws IllegalArgumentException and the caller keeps its last good appearance. */
    static Appearance parse(JSONObject a){
        if(a==null)throw new IllegalArgumentException("brak obiektu");
        if(a.optInt("version",-1)!=1)throw new IllegalArgumentException("nieznana wersja");
        if(a.length()!=3)throw new IllegalArgumentException("nieznane pola");
        String theme=a.optString("theme","");
        if(Theme.byId(theme)==null)throw new IllegalArgumentException("nieznany motyw");
        JSONObject b=a.optJSONObject("background");
        if(b==null)throw new IllegalArgumentException("brak tła");
        String type=b.optString("type","");
        if(type.equals("solid")){
            if(b.length()!=1)throw new IllegalArgumentException("zbędne pola tła");
            return new Appearance(theme,false,null,null,0,50,50);
        }
        if(!type.equals("image")||b.length()!=6)throw new IllegalArgumentException("nieznany typ tła");
        String id=b.optString("image_id","");
        if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("zły image_id");
        String path=b.optString("path","");
        if(!path.startsWith(PATH_PREFIX)||path.contains("..")||!path.endsWith("/"+id))throw new IllegalArgumentException("zła ścieżka");
        String entry=path.substring(PATH_PREFIX.length(),path.length()-id.length()-1);
        if(!entry.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("zła ścieżka");
        int dim=integer(b,"dim",DIM_MIN,DIM_MAX),fx=integer(b,"focus_x",0,100),fy=integer(b,"focus_y",0,100);
        return new Appearance(theme,true,id,path,dim,fx,fy);
    }
    private static int integer(JSONObject o,String key,int low,int high){
        Object v=o.opt(key);
        if(!(v instanceof Integer)||(Integer)v<low||(Integer)v>high)throw new IllegalArgumentException(key+" poza zakresem");
        return (Integer)v;
    }
}
