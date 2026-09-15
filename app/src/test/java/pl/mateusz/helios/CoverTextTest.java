package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CoverTextTest {
    static EntityStates.Entity e(String state,String... attrs){Map<String,String> a=new HashMap<>();for(int i=0;i+1<attrs.length;i+=2)a.put(attrs[i],attrs[i+1]);return new EntityStates.Entity(state,a);}

    @Test public void statesBecomePolishLinesAndPositionOnlyWhenOpen(){
        assertEquals("A: zamknięta",CoverText.line("A",e("closed","current_position","0")));
        assertEquals("B: 40%",CoverText.line("B",e("open","current_position","40.0")));
        assertEquals("B: otwarta",CoverText.line("B",e("open")));
        assertEquals("A: otwieranie",CoverText.line("A",e("opening","current_position","30")));
        assertEquals("A: zamykanie",CoverText.line("A",e("closing")));
        assertEquals("A: brak danych",CoverText.line("A",e("unavailable","current_position","50")));
        assertEquals("A: brak danych",CoverText.line("A",e("unknown")));
        assertEquals("A: brak danych",CoverText.line("A",null));
        assertEquals("B: otwarta",CoverText.line("B",e("open","current_position","many")));
    }
    @Test public void attentionAndFeatureBits(){
        assertTrue(CoverText.attention(e("open")));assertTrue(CoverText.attention(e("closing")));
        assertFalse(CoverText.attention(e("closed")));assertFalse(CoverText.attention(e("unavailable")));assertFalse(CoverText.attention(null));
        EntityStates.Entity full=e("open","supported_features","15"),noStop=e("open","supported_features","3"),dead=e("unavailable","supported_features","15");
        assertTrue(CoverText.has(full,CoverText.OPEN));assertTrue(CoverText.has(full,CoverText.CLOSE));assertTrue(CoverText.has(full,CoverText.STOP));
        assertTrue(CoverText.has(noStop,CoverText.OPEN));assertFalse(CoverText.has(noStop,CoverText.STOP));
        assertFalse(CoverText.has(dead,CoverText.OPEN));assertFalse(CoverText.has(e("open"),CoverText.OPEN));assertFalse(CoverText.has(e("open","supported_features","x"),CoverText.OPEN));assertFalse(CoverText.has(null,CoverText.STOP));
    }
}
