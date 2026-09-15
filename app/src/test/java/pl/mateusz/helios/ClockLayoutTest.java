package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockLayoutTest {
    /** Geist Mono-like metrics: every glyph advances 0.6 em. */
    static final TextFit.Measurer MONO=(text,size)->text.length()*.6f*size;

    @Test public void fitPicksTheLargestIntegerSizeThatFits(){
        assertEquals(116,TextFit.fit(MONO,"18:19",348,120),0);   // 5*0.6*116=348
        assertEquals(120,TextFit.fit(MONO,"1",348,120),0);       // cap wins
        assertEquals(TextFit.FLOOR,TextFit.fit(MONO,"18:19",10,120),0);
        assertEquals(96,TextFit.size(MONO,"18:19",100,96,120),0); // clamped up to min
    }
    @Test public void twoByTwoShowsEverythingWithTheHourBetween96And120(){
        ClockLayout.Plan p=ClockLayout.plan(MONO,"23:59",388,272,true);
        assertTrue(p.hourSize+"",p.hourSize>=96&&p.hourSize<=120);assertTrue(p.showDate);assertTrue(p.showWeekday);
        assertTrue(p.height(true)<=272);
        p=ClockLayout.plan(MONO,"00:00",388,272,false);assertEquals(116,p.hourSize,0);assertTrue(p.height(false)<=272);
    }
    @Test public void twoByOneWithTitleDropsTheWeekdayAndStillFits(){
        ClockLayout.Plan p=ClockLayout.plan(MONO,"11:11",388,132,true);
        assertTrue(p.showDate);assertFalse(p.showWeekday);assertEquals(34,p.hourSize,0);
        assertTrue(p.height(true)+"",p.height(true)<=132);
        p=ClockLayout.plan(MONO,"11:11",388,132,false);
        assertTrue(p.showDate);assertTrue(p.showWeekday);assertTrue(p.height(false)<=132);
    }
    @Test public void oneByOneKeepsTheFullHourEvenBelowTheFloor(){
        ClockLayout.Plan p=ClockLayout.plan(MONO,"11:11",190,132,true);
        assertFalse(p.showWeekday);assertTrue(p.height(true)<=132);
        assertTrue(p.hourSize<=TextFit.fit(MONO,"11:11",150,120)); // never wider than the tile
        p=ClockLayout.plan(MONO,"11:11",190,80,true); // no budget for any line: width fit, date and weekday hidden
        assertFalse(p.showDate);assertFalse(p.showWeekday);assertEquals(TextFit.fit(MONO,"11:11",150,120),p.hourSize,0);
    }
    @Test public void fallbackFourByThreeUsesTheCapAndAllLines(){
        ClockLayout.Plan p=ClockLayout.plan(MONO,"23:59",784,412,false);
        assertEquals(120,p.hourSize,0);assertTrue(p.showDate&&p.showWeekday);assertTrue(p.height(false)<=412);
    }
}
