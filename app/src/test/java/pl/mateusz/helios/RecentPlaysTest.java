package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecentPlaysTest {
    private static RecentPlays.Entry entry(String uri){return new RecentPlays.Entry(uri,"Name "+uri,"track",null,null);}
    @Test public void newestFirstReplayMovesToFrontAndListIsBounded(){
        RecentPlays recent=new RecentPlays();
        for(int i=1;i<=12;i++)recent.add(entry("library://track/"+i));
        assertEquals(RecentPlays.LIMIT,recent.entries().size());
        assertEquals("library://track/12",recent.entries().get(0).uri);
        assertEquals("library://track/3",recent.entries().get(9).uri);
        recent.add(entry("library://track/5"));
        assertEquals("library://track/5",recent.entries().get(0).uri);assertEquals(RecentPlays.LIMIT,recent.entries().size());
        assertEquals(1,recent.entries().stream().filter(e->e.uri.equals("library://track/5")).count());
    }
    @Test public void survivesSerializationAndIgnoresGarbage(){
        RecentPlays recent=new RecentPlays();
        recent.add(new RecentPlays.Entry("library://album/1","Album","album","a/b","library"));
        recent.add(entry("library://track/2"));
        RecentPlays back=RecentPlays.parse(recent.serialize());
        assertEquals(2,back.entries().size());
        assertEquals("library://track/2",back.entries().get(0).uri);
        assertEquals("a/b",back.entries().get(1).imagePath);assertEquals("library",back.entries().get(1).imageProvider);
        assertNull(back.entries().get(0).imagePath);
        assertTrue(RecentPlays.parse("not json").entries().isEmpty());
        assertTrue(RecentPlays.parse(null).entries().isEmpty());
    }
}
