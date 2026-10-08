package pl.mateusz.helios;

import org.junit.Test;
import pl.mateusz.helios.OverlayGeometry.Box;
import static org.junit.Assert.*;

public class CoverPanelGeometryTest {
    @Test public void panelSitsUnderTheBarInsideTheScreenAndBlocksDoNotOverlap(){
        for(int w:new int[]{800,480}){
            CoverPanelGeometry g=new CoverPanelGeometry(w);
            assertTrue(w+"",new Box(0,DashboardView.BAR,w,DashboardView.HEIGHT-DashboardView.BAR).contains(g.panel));
            Box inner=new Box(0,0,g.panel.w,g.panel.h);
            for(Box b:g.blocks)assertTrue(b.y+"",inner.contains(b));
            for(int i=0;i<g.blocks.length;i++)for(int j=i+1;j<g.blocks.length;j++)assertFalse(i+"/"+j,g.blocks[i].overlaps(g.blocks[j]));
            assertTrue(g.back.h>=72);assertEquals(g.panel.h-CoverPanelGeometry.PAD,g.back.y+g.back.h);
            assertEquals(g.rowA.h,g.rowB.h);assertEquals(96,g.rowA.h);
        }
        Box clock=new CoverPanelGeometry(800).panel; // the agreed 800x480 panel is unchanged
        assertEquals(40,clock.x);assertEquals(720,clock.w);
    }
    @Test public void rowControlsAreFingerSizedAndSeparate(){
        for(int w:new int[]{800,480}){
            CoverPanelGeometry g=new CoverPanelGeometry(w);
            Box row=new Box(0,0,g.rowA.w,g.rowA.h);
            for(Box c:g.controls)assertTrue(row.contains(c));
            for(Box c:new Box[]{g.open,g.stop,g.close})assertTrue(c.w>=72&&c.h>=72);
            for(int i=0;i<g.controls.length;i++)for(int j=i+1;j<g.controls.length;j++)assertFalse(i+"/"+j,g.controls[i].overlaps(g.controls[j]));
        }
        assertTrue(new CoverPanelGeometry(800).label.w>=300);
        assertTrue("a square screen still has room for a short name",new CoverPanelGeometry(480).label.w>=140);
    }
}
