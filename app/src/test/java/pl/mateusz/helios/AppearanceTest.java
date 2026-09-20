package pl.mateusz.helios;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppearanceTest {
    static final String ID="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    static JSONObject image() throws Exception {
        return new JSONObject().put("version",1).put("theme","night_blue").put("background",new JSONObject().put("type","image").put("image_id",ID)
            .put("path","/api/helios/appearance/entry_1/"+ID).put("dim",60).put("focus_x",30).put("focus_y",80));
    }
    @Test public void parsesSolidAndImageSnapshots() throws Exception {
        Appearance a=Appearance.parse(new JSONObject().put("version",1).put("theme","warm_graphite").put("background",new JSONObject().put("type","solid")));
        assertFalse(a.image);assertEquals("warm_graphite",a.theme);assertEquals("",a.key());
        a=Appearance.parse(image());
        assertTrue(a.image);assertEquals(ID,a.imageId);assertEquals(60,a.dim);assertEquals(30,a.focusX);assertEquals(80,a.focusY);assertEquals(ID+"|30|80",a.key());
        assertEquals("/api/helios/appearance/entry_1/"+ID,a.path);
    }
    @Test public void rejectsEverythingOutsideTheContract() throws Exception {
        String[][] bad={{"version","2"},{"theme","neon"},{"dim","34"},{"dim","81"},{"focus_x","101"},{"focus_y","-1"},{"image_id","ABCDEF"},
            {"path","/api/helios/appearance/../"+ID},{"path","/local/"+ID},{"path","/api/helios/appearance/e/"+ID+"x"},{"extra","1"},{"btype","solid"},{"bextra","1"},{"dimtype","60.0"}};
        for(String[] b:bad){
            JSONObject j=image();JSONObject bg=j.getJSONObject("background");
            switch(b[0]){
                case "version":j.put("version",3);break;
                case "theme":j.put("theme",b[1]);break;
                case "extra":j.put("extra",1);break;
                case "btype":bg.put("type","solid");break; // solid with image fields
                case "bextra":bg.put("extra",1);break;
                case "dimtype":bg.put("dim",60.0);break;
                case "image_id":bg.put("image_id",b[1]);break;
                case "path":bg.put("path",b[1]);break;
                default:bg.put(b[0],Integer.parseInt(b[1]));
            }
            try{Appearance.parse(j);fail(b[0]+"="+b[1]);}catch(IllegalArgumentException expected){}
        }
        try{Appearance.parse(null);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void cropCoversTheTargetWithoutBarsAndFollowsTheFocus(){
        assertArrayEquals(new int[]{0,120,1600,960},CropMath.rect(1600,1200,800,480,50,50)); // landscape source: full width, vertical window centred
        assertArrayEquals(new int[]{0,0,1600,960},CropMath.rect(1600,1200,800,480,50,0));
        assertArrayEquals(new int[]{0,240,1600,960},CropMath.rect(1600,1200,800,480,50,100));
        int[] portrait=CropMath.rect(960,1600,800,480,100,50);
        assertEquals(960,portrait[2]);assertEquals(576,portrait[3]);assertEquals(0,portrait[0]);assertEquals(512,portrait[1]);
        int[] small=CropMath.rect(400,300,800,480,0,0); // smaller than target: window never exceeds the source
        assertEquals(400,small[2]);assertEquals(240,small[3]);
        int[] r=CropMath.rect(1600,960,800,480,50,50);assertArrayEquals(new int[]{0,0,1600,960},r);
        assertEquals(1.0*r[2]/r[3],800/480.0,.01);
    }

    @Test public void versionTwoCarriesTheScreensaverBlock() throws Exception {
        JSONObject j=image();j.put("version",2);
        j.put("screensaver",new JSONObject().put("mode","always").put("idle_seconds",300).put("dark_enter",5)
                .put("dark_exit",12).put("photos",true).put("photo_seconds",60).put("photo_dim",30));
        Appearance a=Appearance.parse(j);
        assertEquals(ScreensaverPolicy.Mode.ALWAYS,a.screensaver.mode);
        assertEquals(300_000L,a.screensaver.idleMs);
        assertEquals(5,a.screensaver.darkEnter);assertEquals(12,a.screensaver.darkExit);
        assertTrue(a.screensaver.photos);assertEquals(60,a.screensaver.photoSeconds);assertEquals(30,a.screensaver.photoDim);
    }

    @Test public void aBadScreensaverBlockNeverCostsTheUserTheirPanel() throws Exception {
        JSONObject j=image();j.put("version",2);
        j.put("screensaver",new JSONObject().put("mode","kiedys").put("idle_seconds",2).put("dark_enter",40).put("dark_exit",10));
        Appearance a=Appearance.parse(j);
        assertTrue(a.image);                       // theme and background survive untouched
        assertEquals(ScreensaverPolicy.Mode.DARK,a.screensaver.mode);
        assertEquals(ScreensaverPolicy.IDLE_MS,a.screensaver.idleMs);
        assertEquals(ScreensaverPolicy.DEFAULT_ENTER,a.screensaver.darkEnter);
        assertEquals(ScreensaverPolicy.DEFAULT_EXIT,a.screensaver.darkExit);
    }

    @Test public void versionOneKeepsTheOldBehaviour() throws Exception {
        Appearance a=Appearance.parse(image());
        assertEquals(ScreensaverPolicy.Mode.DARK,a.screensaver.mode);
        assertFalse(a.screensaver.photos);
    }
}
