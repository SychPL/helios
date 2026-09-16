package pl.mateusz.helios;

import java.net.URI;
import java.util.regex.*;
import org.json.*;

/** The one APK of a final GitHub release of SychPL/helios; null for anything else (SPEC 0.10 pkt 7). */
final class ReleaseInfo {
    static final long MAX_BYTES=40L*1024*1024;
    /** The only metadata address; never redirected, never subject to the asset host policies. */
    static final String API_URL="https://api.github.com/repos/SychPL/helios/releases/latest";
    private static final Pattern TAG=Pattern.compile("^v([0-9]+\\.[0-9]+\\.[0-9]+)$");
    final String version,url;final long size;
    private ReleaseInfo(String version,String url,long size){this.version=version;this.url=url;this.size=size;}
    static ReleaseInfo parse(JSONObject release){
        if(release==null||release.optBoolean("draft")||release.optBoolean("prerelease"))return null;
        Matcher m=TAG.matcher(release.optString("tag_name",""));if(!m.matches())return null;
        String version=m.group(1);JSONArray assets=release.optJSONArray("assets");if(assets==null)return null;
        JSONObject found=null;
        for(int i=0;i<assets.length();i++){JSONObject a=assets.optJSONObject(i);if(a!=null&&("helios-"+version+".apk").equals(a.optString("name"))){if(found!=null)return null;found=a;}}
        if(found==null)return null;
        String url=found.optString("browser_download_url","");long size=found.optLong("size",-1);
        if(!assetHost(url)||size<1||size>MAX_BYTES)return null;
        return new ReleaseInfo(version,url,size);
    }
    private static String httpsHost(String url){try{URI u=new URI(url);return "https".equals(u.getScheme())?u.getHost():null;}catch(Exception e){return null;}}
    /** Where a release asset may point (the first hop). */
    static boolean assetHost(String url){String h=httpsHost(url);return h!=null&&(h.equals("github.com")||h.equals("objects.githubusercontent.com"));}
    /** Where a redirect may land. */
    static boolean allowedHost(String url){String h=httpsHost(url);return h!=null&&(h.equals("github.com")||h.endsWith(".githubusercontent.com"));}
}
