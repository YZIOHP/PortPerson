package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ApInfoTest {

    static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    public void ciscoNameAndBssLoad() {
        byte[] cisco = new byte[30];
        System.arraycopy(ascii("IDF2-AP-17"), 0, cisco, 10, 10);
        cisco[26] = 9;
        List<ApInfo.Element> e = new ArrayList<>();
        e.add(new ApInfo.Element(11, b(12, 0, 46, 0, 0)));  // 12 stations, 46/255 busy
        e.add(new ApInfo.Element(133, cisco));
        ApInfo a = ApInfo.parse(e);
        assertEquals("IDF2-AP-17", a.name);
        assertEquals("Cisco beacon", a.nameSource);
        assertEquals(12, a.clients);          // BSS Load wins over the Cisco count
        assertEquals(18, a.channelUtilPct);
    }

    @Test
    public void arubaMistAerohive() {
        List<ApInfo.Element> e = new ArrayList<>();
        e.add(new ApInfo.Element(221, cat(b(0x00, 0x0b, 0x86, 3, 0), ascii("aruba-lobby-1"))));
        assertEquals("aruba-lobby-1", ApInfo.parse(e).name);
        e.clear();
        e.add(new ApInfo.Element(221, cat(b(0x5c, 0x5b, 0x35, 1), ascii("mist-ap-3F"))));
        assertEquals("mist-ap-3F", ApInfo.parse(e).name);
        e.clear();
        e.add(new ApInfo.Element(221, cat(b(0x00, 0x19, 0x77, 33, 6), ascii("AH-2nd"))));
        assertEquals("AH-2nd", ApInfo.parse(e).name);
    }

    @Test
    public void wpsDeviceDetails() {
        byte[] wps = cat(b(0x00, 0x50, 0xf2, 4),
                b(0x10, 0x21, 0, 7), ascii("NETGEAR"),
                b(0x10, 0x23, 0, 5), ascii("RAX40"),
                b(0x10, 0x11, 0, 8), ascii("Kitchen1"));
        List<ApInfo.Element> e = new ArrayList<>();
        e.add(new ApInfo.Element(221, wps));
        ApInfo a = ApInfo.parse(e);
        assertEquals("Kitchen1", a.name);
        assertEquals("WPS device name", a.nameSource);
        assertEquals("NETGEAR", a.wpsManufacturer);
        assertEquals("RAX40", a.wpsModel);
    }

    @Test
    public void ciscoBeatsWpsAndGarbageIsSafe() {
        byte[] cisco = new byte[30];
        System.arraycopy(ascii("CORE-AP"), 0, cisco, 10, 7);
        List<ApInfo.Element> e = new ArrayList<>();
        e.add(new ApInfo.Element(221, cat(b(0x00, 0x50, 0xf2, 4, 0x10, 0x11, 0, 3), ascii("wps"))));
        e.add(new ApInfo.Element(133, cisco));
        e.add(new ApInfo.Element(221, b(0x00, 0x0b)));   // truncated vendor IE
        e.add(new ApInfo.Element(11, b(1)));              // truncated BSS load
        e.add(new ApInfo.Element(133, b(1, 2, 3)));       // truncated Cisco IE
        assertEquals("CORE-AP", ApInfo.parse(e).name);
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 5000; i++) {
            byte[] g = new byte[r.nextInt(40)];
            r.nextBytes(g);
            List<ApInfo.Element> x = new ArrayList<>();
            x.add(new ApInfo.Element(r.nextBoolean() ? 221 : 133, g));
            ApInfo.parse(x); // must not throw
        }
    }

    @Test
    public void sameApHeuristic() {
        assertTrue(ApInfo.sameAp("f0:9f:c2:10:20:30", "f0:9f:c2:10:20:31"));
        assertTrue(ApInfo.sameAp("f0:9f:c2:10:20:30", "f2:9f:c2:10:20:38")); // locally administered sibling
        assertTrue(!ApInfo.sameAp("f0:9f:c2:10:20:30", "f0:9f:c2:10:99:30"));
        assertTrue(!ApInfo.sameAp("f0:9f:c2:10:20:30", "00:11:22:33:44:55"));
        assertTrue(ApInfo.locallyAdministered("02:aa:bb:cc:dd:ee"));
        assertTrue(!ApInfo.locallyAdministered("f0:9f:c2:10:20:30"));
        assertEquals("F0:9F:C2", ApInfo.oui("f0:9f:c2:10:20:30"));
    }
}
