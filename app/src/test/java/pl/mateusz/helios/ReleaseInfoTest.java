package pl.mateusz.helios;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseInfoTest {
    private static JSONObject release(String tag,String asset,String url,long size) throws Exception {
        return new JSONObject().put("tag_name",tag).put("draft",false).put("prerelease",false)
            .put("assets",new JSONArray().put(new JSONObject().put("name",asset).put("browser_download_url",url).put("size",size)));
    }
    @Test public void versionsCompareNumerically(){
        assertTrue(Version.compare("0.9.10","0.9.9")>0);assertTrue(Version.compare("0.9.0","0.8.18")>0);assertEquals(0,Version.compare("1.0","1.0.0"));assertTrue(Version.compare("0.9","0.9.1")<0);
        try{Version.compare("0.9.x","0.9.0");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void onlyAWellFormedFinalReleaseWithOneApkIsAccepted() throws Exception {
        ReleaseInfo info=ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/SychPL/helios/releases/download/v0.9.1/helios-0.9.1.apk",6_000_000));
        assertEquals("0.9.1",info.version);assertEquals(6_000_000,info.size);assertTrue(info.url.startsWith("https://github.com/"));
        assertNull(ReleaseInfo.parse(release("0.9.1","helios-0.9.1.apk","https://github.com/x",1)));
        assertNull("exactly three components",ReleaseInfo.parse(release("v1","helios-1.apk","https://github.com/x",1)));assertNull(ReleaseInfo.parse(release("v0.9.1.2","helios-0.9.1.2.apk","https://github.com/x",1)));
        assertNull("initial download only from github.com or objects.githubusercontent.com",ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://raw.githubusercontent.com/x",1)));
        assertNotNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://objects.githubusercontent.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.2.apk","https://github.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","http://github.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://evil.com/x",1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/x",ReleaseInfo.MAX_BYTES+1)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/x",1).put("prerelease",true)));
        assertNull(ReleaseInfo.parse(release("v0.9.1","helios-0.9.1.apk","https://github.com/x",1).put("draft",true)));
        JSONObject two=release("v0.9.1","helios-0.9.1.apk","https://github.com/x",1);two.getJSONArray("assets").put(new JSONObject().put("name","helios-0.9.1.apk").put("browser_download_url","https://github.com/y").put("size",1));
        assertNull(ReleaseInfo.parse(two));
        assertNull(ReleaseInfo.parse(null));
        assertTrue(ReleaseInfo.allowedHost("https://objects.githubusercontent.com/a"));assertFalse(ReleaseInfo.allowedHost("https://githubusercontent.com.evil.com/a"));assertFalse(ReleaseInfo.allowedHost("https://github.com.evil.com/a"));
        assertFalse("the API host is not an asset host",ReleaseInfo.allowedHost("https://api.github.com/x"));assertFalse(ReleaseInfo.allowedHost("https://githubusercontent.com/a"));
    }
}
