package pl.mateusz.helios;

import org.junit.Test;
import static org.junit.Assert.*;

public class AddressInputTest {
    @Test public void hostsPortsAndUrls(){
        assertEquals("http://192.168.1.10:8123",AddressInput.parse("192.168.1.10"));
        assertEquals("http://192.168.1.10:8443",AddressInput.parse("192.168.1.10:8443"));
        assertEquals("http://ha:8123",AddressInput.parse("http://ha:8123/"));
        assertNull("manual addresses are http only (SPEC 0.10 pkt 3)",AddressInput.parse("https://ha.local"));
        assertEquals("http://[fd00::5]:8123",AddressInput.parse("[fd00::5]"));
        assertNull(AddressInput.parse("fd00::5"));assertNull(AddressInput.parse(""));assertNull(AddressInput.parse("1.2.3.4:0"));assertNull(AddressInput.parse("1.2.3.4:70000"));assertNull(AddressInput.parse("ftp://x"));
        assertNull("only a base url",AddressInput.parse("http://ha/inna/sciezka?x=1"));assertNull(AddressInput.parse("http://ha:8123/#f"));assertNull(AddressInput.parse("http://user:pw@ha:8123"));assertNull(AddressInput.parse("http://ha:8123?x=1"));
    }
}
