package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;
import static pl.mateusz.helios.OverlayGeometry.*;

public class OverlayGeometryTest {
    @Test public void touchTargetsNeverOverlapAndAllElementsStayInsideThePanel(){
        Box inner=new Box(0,0,PANEL.w,PANEL.h);
        for(Box b:ALL)assertTrue(b.x+","+b.y,inner.contains(b));
        for(int i=0;i<TOUCH.length;i++)for(int j=i+1;j<TOUCH.length;j++)assertFalse(i+" vs "+j,TOUCH[i].overlaps(TOUCH[j]));
        for(Box b:TOUCH)assertTrue(b.w>=72&&b.h>=72); // finger-sized
        assertFalse(ART.overlaps(TITLE));assertFalse(TITLE.overlaps(ARTIST));assertFalse(STATUS.overlaps(CLOSE));
    }
    @Test public void handleTouchesTheRightEdgeAndCutsOnlyColumnFourRowTwo(){
        assertEquals(DashboardView.WIDTH,HANDLE.x+HANDLE.w);
        assertEquals((DashboardView.HEIGHT-HANDLE.h)/2,HANDLE.y); // centred in the full screen height, not under the bar (spec: y=204)
        assertEquals(204,HANDLE.y);
        for(int c=1;c<=4;c++)for(int r=1;r<=3;r++)assertEquals(c+","+r,c==4&&r==2,HANDLE.overlaps(cell(c,r)));
        assertTrue(new Box(0,0,DashboardView.WIDTH,DashboardView.HEIGHT).contains(HANDLE));
        assertTrue(new Box(0,DashboardView.BAR,DashboardView.WIDTH,DashboardView.HEIGHT-DashboardView.BAR).contains(PANEL));
    }
    @Test public void pointsAreClassifiedByBox(){
        assertTrue(HANDLE.contains(729,205));assertFalse(HANDLE.contains(727,240));assertFalse(HANDLE.contains(760,276));
        assertTrue(cell(4,2).contains(727,240)); // just left of the handle: the tile's own hit
    }
    @Test public void aSquareScreenGetsThreeColumnsAndTheClockKeepsFour(){
        assertEquals(800,DashboardView.units(800,800,480));assertEquals(480,DashboardView.units(480,800,480));assertEquals(4,DashboardView.columns(800));
        assertEquals(480,DashboardView.units(480,480,480));assertEquals(3,DashboardView.columns(480));
        assertEquals(819,DashboardView.units(1024,1024,600));assertEquals(4,DashboardView.columns(819)); // a 1024x600 tablet: wider cards, no bars
        assertEquals(640,DashboardView.units(1024,1024,768));assertEquals(4,DashboardView.columns(640)); // 4:3 still holds four
        assertEquals(854,DashboardView.units(854,480,854)); // portrait: 480 across, taller rows
    }
}
