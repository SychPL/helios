package pl.mateusz.helios;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ArtworkLoaderTest {
    private final List<String> fetches=new ArrayList<>();
    private final List<String> results=new ArrayList<>();
    private long now=0;
    private final ArtworkLoader<String> loader=new ArtworkLoader<>((url,gen)->fetches.add(url+"#"+gen),r->results.add(r),()->now);

    @Test public void sameUrlFetchesOnceAndChangeClearsBeforeTheNewCoverArrives(){
        loader.request("a");loader.request("a");loader.request("a");
        assertEquals(1,fetches.size());
        loader.deliver(1,"A");
        assertEquals(Arrays.asList(null,"A"),results);
        loader.request("b");
        assertEquals(Arrays.asList(null,"A",null),results); // cover A never sits next to title B
        loader.deliver(2,"B");
        assertEquals("B",results.get(results.size()-1));
    }
    @Test public void lateResultsAfterClearOrNewerRequestAreDropped(){
        loader.request("a");loader.clear();loader.deliver(1,"A");
        assertFalse(results.contains("A"));
        loader.request("a"); // same URL, new session: fetched again, not deduplicated against the cleared one
        assertEquals(2,fetches.size());
        loader.request("b");loader.deliver(3,"A");loader.deliver(4,"B");
        assertEquals("B",results.get(results.size()-1));assertFalse(results.contains("A"));
        loader.deliver(99,"X");assertFalse(results.contains("X"));
    }
    @Test public void failedFetchRetriesOnlyAfterTheBackoff(){
        loader.request("a");loader.deliver(1,null);
        loader.request("a");assertEquals(1,fetches.size());
        now=ArtworkLoader.RETRY_AFTER_MS;loader.request("a");assertEquals(2,fetches.size());
    }
    @Test public void emptyUrlClears(){
        loader.request("a");loader.deliver(1,"A");loader.request(null);
        assertNull(results.get(results.size()-1));
        loader.request("");assertEquals(1,fetches.size());
    }
}
