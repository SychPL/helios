package pl.mateusz.helios;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** The bundled catalogue: read from the real asset (gradle runs unit tests from app/), so a broken generator run fails here first. */
public class MdiIconsTest {
    static final File ASSET=new File("src/main/assets/mdi-codepoints.txt");
    /** Installs the real catalogue for tests that parse version-6 documents. */
    static MdiIcons install() throws IOException {
        if(MdiIcons.installed()==null)MdiIcons.install(MdiIcons.load(new FileInputStream(ASSET)));
        return MdiIcons.installed();
    }
    @Test public void legacyNamesExistAndCodepointsParse() throws IOException {
        MdiIcons icons=install();
        for(String name:DashboardSpec.ICONS)assertTrue(name,icons.has(name));
        assertEquals(Integer.valueOf(0xF0335),icons.codepoint("lightbulb"));
        assertNull(icons.codepoint("no-such-icon-ever"));assertFalse(icons.has(""));
        assertTrue(icons.size()>7000);
        assertEquals("lightbulb",MdiIcons.name("mdi:lightbulb"));assertNull(MdiIcons.name("lightbulb"));assertNull(MdiIcons.name(null));
    }
    @Test public void catalogueHasNoDuplicateNamesAndOnlyHexCodepoints() throws IOException {
        Set<String> seen=new HashSet<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(ASSET),"UTF-8"))){
            String line;
            while((line=r.readLine())!=null){
                String[] parts=line.split(" ");
                assertEquals(line,2,parts.length);assertTrue(line,parts[0].matches("[a-z0-9-]+"));assertTrue(line,parts[1].matches("F[0-9A-F]{4}"));
                assertTrue("duplicate "+parts[0],seen.add(parts[0]));
            }
        }
    }
    @Test public void malformedLinesAreAnAssetError(){
        try{MdiIcons.load(new ByteArrayInputStream("lightbulb F0335\nbroken\n".getBytes("UTF-8")));fail("accepted a line without a codepoint");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("broken"));}
    }
}
