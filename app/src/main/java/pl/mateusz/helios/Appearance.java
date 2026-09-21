package pl.mateusz.helios;

import org.json.JSONObject;

/** Validated appearance snapshot from the helios channel (SPEC 0.8b pkt 4): theme plus a solid or image background. Pure. */
final class Appearance {
    static final String PATH_PREFIX="/api/helios/appearance/";
    static final int DIM_MIN=35,DIM_MAX=80;
    final String theme;final boolean image;final String imageId,path;final int dim,focusX,focusY;
    final Screensaver screensaver;
    private Appearance(String theme,boolean image,String imageId,String path,int dim,int focusX,int focusY){this(theme,image,imageId,path,dim,focusX,focusY,Screensaver.DEFAULTS);}
    private Appearance(String theme,boolean image,String imageId,String path,int dim,int focusX,int focusY,Screensaver screensaver){this.theme=theme;this.image=image;this.imageId=imageId;this.path=path;this.dim=dim;this.focusX=focusX;this.focusY=focusY;this.screensaver=screensaver;}
    static Appearance solid(){return new Appearance("warm_graphite",false,null,null,0,50,50);}
    private Appearance with(Screensaver s){return new Appearance(theme,image,imageId,path,dim,focusX,focusY,s);}

    /**
     * Night-clock settings from HA (SPEC 0.14). Read leniently on purpose: a typo here must not cost the user
     * their panel theme and background, so every bad field falls back to its default instead of throwing.
     */
    static final class Screensaver {
        static final Screensaver DEFAULTS=new Screensaver(ScreensaverPolicy.Mode.DARK,ScreensaverPolicy.IDLE_MS,
                ScreensaverPolicy.DEFAULT_ENTER,ScreensaverPolicy.DEFAULT_EXIT,false,120,45,true);
        static final int PHOTO_SECONDS_MIN=15,PHOTO_SECONDS_MAX=3600,PHOTO_DIM_MIN=0,PHOTO_DIM_MAX=90;
        final ScreensaverPolicy.Mode mode;final long idleMs;final int darkEnter,darkExit;
        final boolean photos;final int photoSeconds,photoDim;
        /** Whether a visible conditional tile holds the panel up; false for houses where one stays lit for days. */
        final boolean notificationsBlock;
        Screensaver(ScreensaverPolicy.Mode mode,long idleMs,int darkEnter,int darkExit,boolean photos,int photoSeconds,int photoDim,boolean notificationsBlock){
            this.mode=mode;this.idleMs=idleMs;this.darkEnter=darkEnter;this.darkExit=darkExit;
            this.photos=photos;this.photoSeconds=photoSeconds;this.photoDim=photoDim;this.notificationsBlock=notificationsBlock;
        }
        static Screensaver parse(JSONObject s){
            if(s==null)return DEFAULTS;
            ScreensaverPolicy.Mode mode=DEFAULTS.mode;
            switch(s.optString("mode","")){
                case "off":mode=ScreensaverPolicy.Mode.OFF;break;
                case "always":mode=ScreensaverPolicy.Mode.ALWAYS;break;
                case "dark":mode=ScreensaverPolicy.Mode.DARK;break;
                default:break; // including a missing mode: the 0.13 behaviour
            }
            long idle=1000L*bounded(s,"idle_seconds",(int)(ScreensaverPolicy.IDLE_MIN_MS/1000),(int)(ScreensaverPolicy.IDLE_MAX_MS/1000),(int)(DEFAULTS.idleMs/1000));
            int enter=bounded(s,"dark_enter",0,ScreensaverPolicy.MAX_THRESHOLD,DEFAULTS.darkEnter);
            int exit=bounded(s,"dark_exit",0,ScreensaverPolicy.MAX_THRESHOLD,DEFAULTS.darkExit);
            if(exit<=enter){enter=DEFAULTS.darkEnter;exit=DEFAULTS.darkExit;} // the pair is validated as a pair, never field by field
            return new Screensaver(mode,idle,enter,exit,s.optBoolean("photos",DEFAULTS.photos),
                    bounded(s,"photo_seconds",PHOTO_SECONDS_MIN,PHOTO_SECONDS_MAX,DEFAULTS.photoSeconds),
                    bounded(s,"photo_dim",PHOTO_DIM_MIN,PHOTO_DIM_MAX,DEFAULTS.photoDim),
                    s.optBoolean("notifications_block",DEFAULTS.notificationsBlock));
        }
        private static int bounded(JSONObject o,String key,int low,int high,int fallback){
            Object v=o.opt(key);
            if(!(v instanceof Integer))return fallback;
            int n=(Integer)v;
            return n<low||n>high?fallback:n;
        }
    }
    /** Cache/decode key: the same image with another focus is re-cropped, not re-fetched. */
    String key(){return image?imageId+"|"+focusX+"|"+focusY:"";}
    /** Strict whole-object parse; any problem throws IllegalArgumentException and the caller keeps its last good appearance. */
    static Appearance parse(JSONObject a){
        if(a==null)throw new IllegalArgumentException("brak obiektu");
        int version=a.optInt("version",-1);
        if(version!=1&&version!=2)throw new IllegalArgumentException("nieznana wersja");
        // v2 adds one optional block; a clock that gets v1 keeps the 0.13 behaviour without being told
        if(a.length()!=3&&!(version==2&&a.length()==4&&a.has("screensaver")))throw new IllegalArgumentException("nieznane pola");
        String theme=a.optString("theme","");
        if(Theme.byId(theme)==null)throw new IllegalArgumentException("nieznany motyw");
        JSONObject b=a.optJSONObject("background");
        if(b==null)throw new IllegalArgumentException("brak tła");
        Screensaver screensaver=Screensaver.parse(a.optJSONObject("screensaver"));
        String type=b.optString("type","");
        if(type.equals("solid")){
            if(b.length()!=1)throw new IllegalArgumentException("zbędne pola tła");
            return new Appearance(theme,false,null,null,0,50,50).with(screensaver);
        }
        if(!type.equals("image")||b.length()!=6)throw new IllegalArgumentException("nieznany typ tła");
        String id=b.optString("image_id","");
        if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("zły image_id");
        String path=b.optString("path","");
        if(!path.startsWith(PATH_PREFIX)||path.contains("..")||!path.endsWith("/"+id))throw new IllegalArgumentException("zła ścieżka");
        String entry=path.substring(PATH_PREFIX.length(),path.length()-id.length()-1);
        if(!entry.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("zła ścieżka");
        int dim=integer(b,"dim",DIM_MIN,DIM_MAX),fx=integer(b,"focus_x",0,100),fy=integer(b,"focus_y",0,100);
        return new Appearance(theme,true,id,path,dim,fx,fy,screensaver);
    }
    private static int integer(JSONObject o,String key,int low,int high){
        Object v=o.opt(key);
        if(!(v instanceof Integer)||(Integer)v<low||(Integer)v>high)throw new IllegalArgumentException(key+" poza zakresem");
        return (Integer)v;
    }
}
