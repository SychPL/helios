package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;
import pl.mateusz.helios.OverlayGeometry.Box;
import static pl.mateusz.helios.OnboardingGeometry.*;

public class OnboardingGeometryTest {
    @Test public void nothingOverlapsAndTouchTargetsAreBigEnough(){
        Box screen=new Box(0,0,DashboardView.WIDTH,DashboardView.HEIGHT);
        for(Box b:ALL)assertTrue(b.x+","+b.y,screen.contains(b));
        for(int i=0;i<ALL.length;i++)for(int j=i+1;j<ALL.length;j++)assertFalse(i+" vs "+j,ALL[i].overlaps(ALL[j]));
        for(Box b:BUTTONS)assertTrue(b.w>=200&&b.h>=72);
        assertTrue(ROW_H>=56);assertEquals(0,LIST.h%ROW_H); // five whole rows
        assertTrue(LIST.x+LIST.w<MANUAL.x);assertTrue(STATUS.y>=LIST.y+LIST.h);
    }
}
