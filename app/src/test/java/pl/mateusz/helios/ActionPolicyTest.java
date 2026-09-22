package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static pl.mateusz.helios.ActionPolicy.Intent.*;

public class ActionPolicyTest {
    private static DashboardSpec.Item item(String type,String action,String entity,String offEntity){
        return new DashboardSpec.Item("id",type,1,1,1,1,null,null,entity,null,null,action,null,null,false,null,Collections.emptyList(),null,null,null,offEntity);
    }
    @Test public void legacyTapsMapToTheHardcodedServicesOnTheTilesOwnEntity(){
        ActionPolicy.Call c=ActionPolicy.call(item("light","toggle","light.salon",null),"Salon");
        assertEquals("light",c.domain);assertEquals("toggle",c.service);assertEquals("light.salon",c.entity);assertEquals("Przełączyć: Salon?",c.question);
        c=ActionPolicy.call(item("garage","close","cover.brama",null),"Brama");
        assertEquals("cover",c.domain);assertEquals("close_cover",c.service);assertEquals("cover.brama",c.entity);assertEquals("Zamknąć bramę?",c.question);
        c=ActionPolicy.call(item("entity","lights_off","sensor.swiatla","light.grupa"),"Światła");
        assertEquals("light",c.domain);assertEquals("turn_off",c.service);assertEquals("light.grupa",c.entity);assertEquals("Zgasić światła?",c.question);
    }
    @Test public void panelsAreNotCallsAndReadOnlyTilesAreNeither(){
        assertEquals(ActionPolicy.Panel.COVER,ActionPolicy.panel("controls"));assertEquals(ActionPolicy.Panel.COVER_GROUP,ActionPolicy.panel("covers"));
        assertEquals(ActionPolicy.Panel.MUSIC_LIBRARY,ActionPolicy.panel("library"));assertEquals(ActionPolicy.Panel.DETAILS,ActionPolicy.panel("details"));
        assertNull(ActionPolicy.call(item("cover","controls","cover.x",null),"X"));assertNull(ActionPolicy.call(item("tile","details","sensor.x",null),"X"));
        assertNull(ActionPolicy.call(item("entity",null,"sensor.x",null),"X"));assertNull(ActionPolicy.panel("toggle"));
    }
    @Test public void onlyActionsOnAnOwnEntityNeedAKnownState(){
        for(String a:new String[]{"toggle","close","lights_off","controls","turn_on","open","stop","lock","activate"})assertTrue(a,ActionPolicy.needsKnown(a));
        for(String a:new String[]{"covers","library","details"})assertFalse(a,ActionPolicy.needsKnown(a));
        assertFalse(ActionPolicy.needsKnown(null));
        assertEquals(CardDefinition.Gate.NONE,ActionPolicy.gate(null));assertEquals(CardDefinition.Gate.NONE,ActionPolicy.gate("details"));
        assertEquals(CardDefinition.Gate.KNOWN,ActionPolicy.gate("controls"));assertEquals(CardDefinition.Gate.KNOWN_NOT_PENDING,ActionPolicy.gate("toggle"));
    }
    @Test public void everyDomainHasAClosedIntentSetAndEveryIntentOneService(){
        for(String d:new String[]{"light","switch","input_boolean","fan"}){assertEquals(EnumSet.of(NONE,DETAILS,TOGGLE,TURN_ON,TURN_OFF),ActionPolicy.allowed(d));assertEquals(DETAILS,ActionPolicy.defaultIntent(d));assertEquals("turn_on",ActionPolicy.service(TURN_ON,d));}
        assertEquals(EnumSet.of(NONE,DETAILS,CONTROLS,OPEN,CLOSE,STOP),ActionPolicy.allowed("cover"));assertEquals(CONTROLS,ActionPolicy.defaultIntent("cover"));assertEquals("stop_cover",ActionPolicy.service(STOP,"cover"));
        assertEquals(EnumSet.of(NONE,DETAILS,LOCK,UNLOCK),ActionPolicy.allowed("lock"));assertEquals(DETAILS,ActionPolicy.defaultIntent("lock"));assertEquals("unlock",ActionPolicy.service(UNLOCK,"lock"));
        for(String d:new String[]{"script","scene"}){assertEquals(EnumSet.of(NONE,DETAILS,ACTIVATE),ActionPolicy.allowed(d));assertEquals(ACTIVATE,ActionPolicy.defaultIntent(d));assertEquals("turn_on",ActionPolicy.service(ACTIVATE,d));}
        for(String d:new String[]{"input_button","button"}){assertEquals(ACTIVATE,ActionPolicy.defaultIntent(d));assertEquals("press",ActionPolicy.service(ACTIVATE,d));}
        for(String d:new String[]{"sensor","binary_sensor","climate","media_player","weather","whatever"}){assertEquals(EnumSet.of(NONE,DETAILS),ActionPolicy.allowed(d));assertEquals(DETAILS,ActionPolicy.defaultIntent(d));}
        for(ActionPolicy.Intent i:ActionPolicy.Intent.values()){
            assertEquals(i,ActionPolicy.intent(ActionPolicy.name(i)));
            boolean panelOrNone=i==NONE||i==DETAILS||i==CONTROLS;
            assertEquals(i.name(),panelOrNone,ActionPolicy.service(i,"light")==null&&ActionPolicy.service(i,"cover")==null&&ActionPolicy.service(i,"lock")==null&&ActionPolicy.service(i,"button")==null);
            assertEquals(i.name(),panelOrNone,ActionPolicy.question(i,"L")==null);
            assertEquals(i.name(),i==NONE||i==DETAILS,ActionPolicy.verb(i)==null);
        }
        assertNull(ActionPolicy.intent("light.turn_on"));assertNull(ActionPolicy.intent("TOGGLE"));
        assertTrue(ActionPolicy.forcedConfirm(LOCK));assertTrue(ActionPolicy.forcedConfirm(UNLOCK));assertFalse(ActionPolicy.forcedConfirm(TURN_OFF));
    }
    @Test public void tileIntentsCallTheEntitysOwnDomain(){
        ActionPolicy.Call c=ActionPolicy.call(item("tile","turn_on","switch.pompa",null),"Pompa");
        assertEquals("switch",c.domain);assertEquals("turn_on",c.service);assertEquals("switch.pompa",c.entity);assertEquals("Włączyć: Pompa?",c.question);
        c=ActionPolicy.call(item("tile","activate","scene.noc",null),"Noc");assertEquals("scene",c.domain);assertEquals("turn_on",c.service);assertEquals("Uruchomić: Noc?",c.question);
        c=ActionPolicy.call(item("tile","activate","input_button.dzwonek",null),"Dzwonek");assertEquals("press",c.service);
        c=ActionPolicy.call(item("tile","unlock","lock.drzwi",null),"Drzwi");assertEquals("lock",c.domain);assertEquals("unlock",c.service);assertEquals("Otworzyć zamek: Drzwi?",c.question);
        c=ActionPolicy.call(item("tile","close","cover.roleta",null),"Roleta");assertEquals("close_cover",c.service);assertEquals("Zamknąć: Roleta?",c.question);
        c=ActionPolicy.call(item("tile","close","cover.roleta",null),CLOSE,"Roleta");assertEquals("Zamknąć: Roleta?",c.question);
        assertNull(ActionPolicy.call(item("tile","x","cover.roleta",null),CONTROLS,"Roleta"));
    }
}
