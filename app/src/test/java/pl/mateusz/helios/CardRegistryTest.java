package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** The three registries must agree: every type has a definition and a body, every action goes to exactly one place. */
public class CardRegistryTest {
    @Test public void everyDefinedTypeHasABodyAndNothingElseDoes(){
        assertEquals(CardDefinition.ALL.keySet(),CardBodies.FOR.keySet());
        assertEquals(Arrays.asList("clock","weather","entity","light","cover","garage","music","cover_group","tile"),new ArrayList<>(CardDefinition.ALL.keySet()));
    }
    @Test public void everyActionOpensAPanelOrCallsOneServiceNeverBoth(){
        for(CardDefinition def:CardDefinition.ALL.values()){
            if(def.action==null)continue;
            DashboardSpec.Item item=new DashboardSpec.Item("x",def.type,1,1,1,1,null,null,"light.x",null,null,def.action,null,null,false,null,Collections.emptyList(),null,null,null,"light.off");
            boolean panel=ActionPolicy.panel(def.action)!=null,call=ActionPolicy.call(item,"L")!=null;
            assertTrue(def.type+": "+def.action,panel^call);
        }
        // the tile's actions are intents: each one is a panel, a service, or nothing at all
        for(ActionPolicy.Intent i:ActionPolicy.Intent.values()){
            String action=ActionPolicy.name(i);
            DashboardSpec.Item item=new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"cover.x",null,null,action,null,null,false,null);
            boolean panel=ActionPolicy.panel(action)!=null,call=ActionPolicy.call(item,"L")!=null||ActionPolicy.call(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"lock.x",null,null,action,null,null,false,null),"L")!=null||ActionPolicy.call(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"light.x",null,null,action,null,null,false,null),"L")!=null||ActionPolicy.call(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"scene.x",null,null,action,null,null,false,null),"L")!=null;
            assertEquals(action,i!=ActionPolicy.Intent.NONE,panel^call);
        }
        assertNull(ActionPolicy.panel(null));assertFalse(ActionPolicy.needsKnown(null));
        assertTrue(CardDefinition.of("tile").intentDriven);for(String t:new String[]{"clock","weather","entity","light","cover","garage","music","cover_group"})assertFalse(t,CardDefinition.of(t).intentDriven);
    }
    @Test public void defaultIconsAreRegisteredIconsAndTitlesMatchTheOldWords(){
        for(CardDefinition def:CardDefinition.ALL.values())if(def.defaultIcon!=null)assertTrue(def.type,DashboardSpec.ICONS.contains(def.defaultIcon));
        assertEquals("",CardDefinition.of("clock").defaultTitle);assertEquals("Pogoda",CardDefinition.of("weather").defaultTitle);
        assertEquals("Muzyka",CardDefinition.of("music").defaultTitle);assertEquals("Rolety",CardDefinition.of("cover_group").defaultTitle);
        assertNull(CardDefinition.of("light").defaultTitle);assertNull(CardDefinition.of("tile").defaultTitle);
    }
    @Test public void fieldsAreGatedByVersion(){
        assertEquals(new HashSet<>(Arrays.asList("entity","temperature_entity")),CardDefinition.of("weather").fieldsAt(3));
        assertTrue(CardDefinition.of("weather").fieldsAt(4).contains("forecast_when"));
        assertFalse(CardDefinition.of("entity").fieldsAt(4).contains("off_entity"));assertTrue(CardDefinition.of("entity").fieldsAt(5).contains("off_entity"));
        assertTrue(CardDefinition.KNOWN_FIELDS.contains("covers"));assertFalse(CardDefinition.KNOWN_FIELDS.contains("icn"));
        assertEquals("cover",CardDefinition.of("garage").domainLabel());assertEquals("entity",CardDefinition.of("entity").domainLabel());assertEquals("tile",CardDefinition.of("tile").domainLabel());
        assertEquals(6,CardDefinition.of("tile").minVersion);
    }
    @Test public void theTileGateFollowsItsIntent(){
        CardDefinition tile=CardDefinition.of("tile");
        assertEquals(CardDefinition.Gate.KNOWN_NOT_PENDING,tile.gate(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"light.x",null,null,"toggle",null,null,false,null)));
        assertEquals(CardDefinition.Gate.KNOWN,tile.gate(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"cover.x",null,null,"controls",null,null,false,null)));
        assertEquals(CardDefinition.Gate.NONE,tile.gate(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"sensor.x",null,null,"details",null,null,false,null)));
        assertEquals(CardDefinition.Gate.NONE,tile.gate(new DashboardSpec.Item("x","tile",1,1,1,1,null,null,"sensor.x",null,null,null,null,null,false,null)));
        assertEquals(CardDefinition.Gate.KNOWN_NOT_PENDING,CardDefinition.of("light").gate(new DashboardSpec.Item("x","light",1,1,1,1,null,null,"light.x",null,null,"toggle",null,null,false,null)));
    }
}
