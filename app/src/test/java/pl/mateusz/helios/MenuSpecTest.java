package pl.mateusz.helios;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MenuSpecTest {
    private JSONObject button(String name,String target)throws Exception{return new JSONObject().put("type","button").put("name",name).put("tap_action",new JSONObject().put("action","url").put("url_path",target));}
    private JSONObject view(JSONArray cards)throws Exception{return new JSONObject().put("title","Moje menu").put("path","menu-zegara").put("cards",cards);}
    @Test public void visualOrderNamesAndTargetsSurviveCacheRoundTrip()throws Exception{
        JSONObject nested=new JSONObject().put("type","grid").put("cards",new JSONArray().put(button("SSH","helios://app/org.galexander.sshd")).put(button("Ustawienia","helios://settings")));
        JSONObject original=view(new JSONArray().put(nested));MenuSpec parsed=MenuSpec.parse(new JSONObject(original.toString()));
        assertEquals("Moje menu",parsed.title);assertEquals("SSH",parsed.items.get(0).name);assertEquals("helios://settings",parsed.items.get(1).target);
    }
    @Test public void rejectsExecutionSchemesAndAmbiguousTargets()throws Exception{
        for(String target:new String[]{"javascript:alert(1)","intent://example","file:///tmp/test","//evil.example","helios://app/a;b","helios://app/org.foo?cmd=bad","helios://unknown","https://user:pass@example.com/"}){
            try{MenuSpec.validateTarget(target);fail(target);}catch(Exception expected){}
        }
        for(String target:new String[]{"helios://talk","helios://cancel","https://example.com/","/config/dashboard","helios://app/pl.mateusz.clockadbprobe"})MenuSpec.validateTarget(target);
    }
    @Test public void missingViewAndIntentionalEmptyViewAreDifferent()throws Exception{
        assertNull(MenuSpec.view(new JSONObject().put("views",new JSONArray())));
        assertTrue(MenuSpec.parse(view(new JSONArray())).items.isEmpty());
    }
    @Test public void unsupportedCardsDoNotSilentlyDisappear()throws Exception{
        try{MenuSpec.parse(view(new JSONArray().put(new JSONObject().put("type","conditional"))));fail();}catch(IllegalArgumentException expected){}
        JSONObject duplicate=new JSONObject().put("views",new JSONArray().put(view(new JSONArray())).put(view(new JSONArray())));
        try{MenuSpec.view(duplicate);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void relativeHaNavigationIsSupported()throws Exception{
        JSONObject card=button("HA","/config");card.getJSONObject("tap_action").put("action","navigate").remove("url_path");card.getJSONObject("tap_action").put("navigation_path","/config");
        assertEquals("/config",MenuSpec.parse(view(new JSONArray().put(card))).items.get(0).target);
    }
}
