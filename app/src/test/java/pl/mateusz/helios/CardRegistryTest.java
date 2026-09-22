package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** The three registries must agree: every type has a definition and a body, every action goes to exactly one place. */
public class CardRegistryTest {
    @Test public void everyDefinedTypeHasABodyAndNothingElseDoes(){
        assertEquals(CardDefinition.ALL.keySet(),CardBodies.FOR.keySet());
        assertEquals(Arrays.asList("clock","weather","entity","light","cover","garage","music","cover_group"),new ArrayList<>(CardDefinition.ALL.keySet()));
    }
    @Test public void everyActionOpensAPanelOrCallsOneServiceNeverBoth(){
        for(CardDefinition def:CardDefinition.ALL.values()){
            if(def.action==null)continue;
            DashboardSpec.Item item=new DashboardSpec.Item("x",def.type,1,1,1,1,null,null,"light.x",null,null,def.action,null,null,false,null,Collections.emptyList(),null,null,null,"light.off");
            boolean panel=ActionPolicy.panel(def.action)!=null,call=ActionPolicy.call(item,"L")!=null;
            assertTrue(def.type+": "+def.action,panel^call);
        }
        assertNull(ActionPolicy.panel(null));assertFalse(ActionPolicy.needsKnown(null));
    }
    @Test public void defaultIconsAreRegisteredIconsAndTitlesMatchTheOldWords(){
        for(CardDefinition def:CardDefinition.ALL.values())if(def.defaultIcon!=null)assertTrue(def.type,DashboardSpec.ICONS.contains(def.defaultIcon));
        assertEquals("",CardDefinition.of("clock").defaultTitle);assertEquals("Pogoda",CardDefinition.of("weather").defaultTitle);
        assertEquals("Muzyka",CardDefinition.of("music").defaultTitle);assertEquals("Rolety",CardDefinition.of("cover_group").defaultTitle);
        assertNull(CardDefinition.of("light").defaultTitle);
    }
    @Test public void fieldsAreGatedByVersion(){
        assertEquals(new HashSet<>(Arrays.asList("entity","temperature_entity")),CardDefinition.of("weather").fieldsAt(3));
        assertTrue(CardDefinition.of("weather").fieldsAt(4).contains("forecast_when"));
        assertFalse(CardDefinition.of("entity").fieldsAt(4).contains("off_entity"));assertTrue(CardDefinition.of("entity").fieldsAt(5).contains("off_entity"));
        assertTrue(CardDefinition.KNOWN_FIELDS.contains("covers"));assertFalse(CardDefinition.KNOWN_FIELDS.contains("icn"));
        assertEquals("cover",CardDefinition.of("garage").domainLabel());assertEquals("entity",CardDefinition.of("entity").domainLabel());
    }
}
