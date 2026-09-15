package pl.mateusz.helios;

import org.junit.Test;
import pl.mateusz.helios.OverlayGeometry.Box;
import static org.junit.Assert.*;
import static pl.mateusz.helios.CoverPanelGeometry.*;

public class CoverPanelGeometryTest {
    @Test public void panelSitsUnderTheBarInsideTheScreenAndBlocksDoNotOverlap(){
        assertTrue(new Box(0,DashboardView.BAR,DashboardView.WIDTH,DashboardView.HEIGHT-DashboardView.BAR).contains(PANEL));
        Box inner=new Box(0,0,PANEL.w,PANEL.h);
        for(Box b:BLOCKS)assertTrue(b.y+"",inner.contains(b));
        for(int i=0;i<BLOCKS.length;i++)for(int j=i+1;j<BLOCKS.length;j++)assertFalse(i+"/"+j,BLOCKS[i].overlaps(BLOCKS[j]));
        assertTrue(BACK.h>=72);assertEquals(PANEL.h-PAD,BACK.y+BACK.h);
        assertEquals(ROW_A.h,ROW_B.h);assertEquals(96,ROW_A.h);
    }
    @Test public void rowControlsAreFingerSizedAndSeparate(){
        Box row=new Box(0,0,ROW_A.w,ROW_A.h);
        for(Box c:CONTROLS)assertTrue(row.contains(c));
        for(Box c:new Box[]{OPEN,STOP,CLOSE})assertTrue(c.w>=72&&c.h>=72);
        for(int i=0;i<CONTROLS.length;i++)for(int j=i+1;j<CONTROLS.length;j++)assertFalse(i+"/"+j,CONTROLS[i].overlaps(CONTROLS[j]));
        assertTrue(LABEL.w>=300);
    }
}
