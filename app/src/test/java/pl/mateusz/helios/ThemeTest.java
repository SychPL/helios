package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeTest {
    private static final int WHITE=0xFFFFFFFF,BLACK=0xFF000000;
    @Test public void paletteTextPairsReachWcagAaOnPlainSurfaces(){
        for(Theme t:new Theme[]{Theme.WARM_GRAPHITE,Theme.NIGHT_BLUE}){
            assertTrue(t.id,Theme.contrast(t.text,t.surface)>=4.5);
            assertTrue(t.id,Theme.contrast(t.muted,t.surface)>=4.5);
            assertTrue(t.id,Theme.contrast(t.accent,t.surface)>=4.5);
            assertTrue(t.id,Theme.contrast(t.text,t.background)>=4.5);
            assertTrue(t.id,Theme.contrast(t.muted,t.background)>=4.5);
            assertTrue(t.id,Theme.contrast(t.accent,t.background)>=4.5);
            assertTrue(t.id,Theme.contrast(t.onColor(t.accent),t.accent)>=4.5);
            assertTrue(t.id,Theme.contrast(t.text,t.raised)>=4.5);
        }
    }
    /** SPEC 0.8b pkt 6 worst case: an all-white photo dimmed only 35%, tiles at 92% surface, clock under an extra 70% black. */
    @Test public void textStaysReadableOverTheBrightestAllowedPhoto(){
        int photo=Theme.composite(BLACK,.35f,WHITE);
        for(Theme t:new Theme[]{Theme.WARM_GRAPHITE,Theme.NIGHT_BLUE}){
            int tile=Theme.composite(t.surface,.92f,photo),clock=Theme.composite(BLACK,.70f,photo);
            assertTrue(t.id+" text/tile "+Theme.contrast(t.text,tile),Theme.contrast(t.text,tile)>=4.5);
            assertTrue(t.id+" muted/tile "+Theme.contrast(t.muted,tile),Theme.contrast(t.muted,tile)>=4.5);
            assertTrue(t.id+" text/clock",Theme.contrast(t.text,clock)>=4.5);
            assertTrue(t.id+" muted/clock",Theme.contrast(t.muted,clock)>=4.5);
        }
        assertEquals(21.0,Theme.contrast(WHITE,BLACK),.01);
        assertEquals(0xFF808080,Theme.composite(WHITE,.5f,0xFF000000)&0xFFFFFFFF);
    }
    @Test public void presetsResolveByIdAndUnknownIsNull(){
        assertSame(Theme.NIGHT_BLUE,Theme.byId("night_blue"));assertSame(Theme.WARM_GRAPHITE,Theme.byId("warm_graphite"));assertNull(Theme.byId("neon"));
    }
    @Test public void averageColourIsTheMeanOfTheSample(){
        assertEquals(0xFF7F4020,AccentColor.average(new int[]{0xFF000000,0xFFFF8040}));
        assertEquals(0,AccentColor.average(new int[0]));assertEquals(0,AccentColor.average(null));
    }
}
