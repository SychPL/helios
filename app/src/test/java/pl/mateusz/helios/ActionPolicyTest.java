package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ActionPolicyTest {
    private static DashboardSpec.Item item(String type,String action,String entity,String offEntity){
        return new DashboardSpec.Item("id",type,1,1,1,1,null,null,entity,null,null,action,null,null,false,null,Collections.emptyList(),null,null,null,offEntity);
    }
    @Test public void tapsMapToTheHardcodedServicesOnTheTilesOwnEntity(){
        ActionPolicy.Call c=ActionPolicy.call(item("light","toggle","light.salon",null),"Salon");
        assertEquals("light",c.domain);assertEquals("toggle",c.service);assertEquals("light.salon",c.entity);assertEquals("Przełączyć: Salon?",c.question);
        c=ActionPolicy.call(item("garage","close","cover.brama",null),"Brama");
        assertEquals("cover",c.domain);assertEquals("close_cover",c.service);assertEquals("cover.brama",c.entity);assertEquals("Zamknąć bramę?",c.question);
        c=ActionPolicy.call(item("entity","lights_off","sensor.swiatla","light.grupa"),"Światła");
        assertEquals("light",c.domain);assertEquals("turn_off",c.service);assertEquals("light.grupa",c.entity);assertEquals("Zgasić światła?",c.question);
    }
    @Test public void panelsAreNotCallsAndReadOnlyTilesAreNeither(){
        assertEquals(ActionPolicy.Panel.COVER,ActionPolicy.panel("controls"));assertEquals(ActionPolicy.Panel.COVER_GROUP,ActionPolicy.panel("covers"));assertEquals(ActionPolicy.Panel.MUSIC_LIBRARY,ActionPolicy.panel("library"));
        assertNull(ActionPolicy.call(item("cover","controls","cover.x",null),"X"));
        assertNull(ActionPolicy.call(item("entity",null,"sensor.x",null),"X"));assertNull(ActionPolicy.panel("toggle"));
    }
    @Test public void onlyActionsOnAnOwnEntityNeedAKnownState(){
        for(String a:new String[]{"toggle","close","lights_off","controls"})assertTrue(a,ActionPolicy.needsKnown(a));
        for(String a:new String[]{"covers","library"})assertFalse(a,ActionPolicy.needsKnown(a));
    }
}
