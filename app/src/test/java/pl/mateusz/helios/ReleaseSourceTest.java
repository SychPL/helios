package pl.mateusz.helios;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ReleaseSourceTest {
    private static JSONObject release(String tag, String asset) throws Exception {
        JSONObject file = new JSONObject()
                .put("name", asset)
                .put("browser_download_url", "https://github.com/x/" + asset)
                .put("size", 6_000_000);
        return new JSONObject().put("tag_name", tag).put("assets", new JSONArray().put(file));
    }

    @Test
    public void theUpdaterCanPointAtASecondRepository() throws Exception {
        assertEquals("https://api.github.com/repos/SychPL/smartclock2tool/releases/latest",
                ReleaseInfo.TOOLS.apiUrl());
        assertEquals("https://api.github.com/repos/SychPL/helios/releases/latest", ReleaseInfo.HELIOS.apiUrl());
        assertEquals("smartclock2tool-2.19.0.apk", ReleaseInfo.TOOLS.assetName("2.19.0"));
        assertEquals("pl.mateusz.clockadbprobe", ReleaseInfo.TOOLS.expectedPackage);
    }

    @Test
    public void eachSourceOnlyAcceptsItsOwnAsset() throws Exception {
        assertNotNull(ReleaseInfo.parse(release("v2.19.0", "smartclock2tool-2.19.0.apk"), ReleaseInfo.TOOLS));
        assertNull("an asset of the other project is not ours",
                ReleaseInfo.parse(release("v2.19.0", "helios-2.19.0.apk"), ReleaseInfo.TOOLS));
        assertNull("and the other way round too",
                ReleaseInfo.parse(release("v0.9.1", "smartclock2tool-0.9.1.apk"), ReleaseInfo.HELIOS));
    }

    @Test
    public void theTagRulesAreTheSameForBothSources() throws Exception {
        assertNull("two-part tags are not accepted on either side",
                ReleaseInfo.parse(release("v2.19", "smartclock2tool-2.19.apk"), ReleaseInfo.TOOLS));
        assertNull(ReleaseInfo.parse(release("2.19.0", "smartclock2tool-2.19.0.apk"), ReleaseInfo.TOOLS));
    }

    @Test
    public void theDefaultSourceIsStillHelios() throws Exception {
        assertNotNull(ReleaseInfo.parse(release("v0.9.1", "helios-0.9.1.apk")));
        assertNull(ReleaseInfo.parse(release("v0.9.1", "smartclock2tool-0.9.1.apk")));
    }
}
