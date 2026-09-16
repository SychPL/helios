package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;
import pl.mateusz.helios.OverlayGeometry.Box;
import static pl.mateusz.helios.FullscreenGeometry.*;

public class FullscreenGeometryTest {
    @Test public void everythingStaysBelowTheBarAndTouchTargetsNeverOverlap(){
        Box inner=new Box(0,0,AREA.w,AREA.h);
        assertEquals(DashboardView.BAR,AREA.y);assertEquals(DashboardView.HEIGHT,AREA.y+AREA.h);
        for(Box b:ALL)assertTrue(b.x+","+b.y,inner.contains(b));
        for(int i=0;i<ALL.length;i++)for(int j=i+1;j<ALL.length;j++)if(ALL[i]!=ART)assertFalse(i+" vs "+j,ALL[i].overlaps(ALL[j]));
        assertTrue(SEEK.overlaps(ART));assertTrue(TIME_LEFT.overlaps(ART)); // the seek bar spans the screen over the bottom of the cover, by design (SPEC 0.11 pkt 3.1)
        for(Box b:new Box[]{CLOSE,CLOCK,TITLE,ARTIST,PREVIOUS,PLAY,NEXT,STOP,MUTE,TIME_RIGHT})assertFalse(b.overlaps(ART));
        for(Box b:new Box[]{PREVIOUS,PLAY,NEXT,STOP,MUTE})assertTrue(b.w>=56&&b.h>=72);
        assertTrue(CLOSE.w>=72&&CLOSE.h>=72);
    }
    @Test public void coverFillsTheLeftHalfAndTheSeekBarSpansBetweenTheTimeLabels(){
        assertEquals(400,ART.w);assertEquals(400,ART.h);assertTrue(ART.x+ART.w<CLOCK.x);
        assertTrue(TIME_LEFT.x+TIME_LEFT.w<=SEEK.x);assertTrue(SEEK.x+SEEK.w<=TIME_RIGHT.x);
        assertEquals(DashboardView.WIDTH,CLOSE.x+CLOSE.w);assertEquals(DashboardView.WIDTH-14,TIME_RIGHT.x+TIME_RIGHT.w);
        assertEquals(DashboardView.WIDTH,MUTE.x+MUTE.w); // the button row ends at the right edge of the screen
    }
    @Test public void panelStatusLeavesRoomForTheFullscreenButton(){
        assertEquals(OverlayGeometry.STATUS.x+OverlayGeometry.STATUS.w,OverlayGeometry.FULL.x);
        assertEquals(OverlayGeometry.CLOSE.x,OverlayGeometry.FULL.x+OverlayGeometry.FULL.w);
        assertFalse(OverlayGeometry.FULL.overlaps(OverlayGeometry.CLOSE));assertFalse(OverlayGeometry.FULL.overlaps(OverlayGeometry.STATUS));
    }
}
