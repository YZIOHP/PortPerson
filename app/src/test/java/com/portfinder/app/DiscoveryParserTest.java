package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DiscoveryParserTest {

    // ---------------------------------------------------------------- frame builders

    private static final byte[] SWITCH_MAC = mac(0x00, 0x1b, 0x54, 0xaa, 0xbb, 0x0c);

    static byte[] mac(int... b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = (byte) b[i];
        return r;
    }

    static void u16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }

    static void u32(ByteArrayOutputStream o, long v) { u16(o, (int) (v >> 16)); u16(o, (int) v); }

    static void cdpTlv(ByteArrayOutputStream o, int type, byte[] value) {
        u16(o, type);
        u16(o, value.length + 4);
        o.write(value, 0, value.length);
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    static byte[] cdpAddressV4(int a, int b, int c, int d) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u32(o, 1);
        o.write(1); o.write(1); o.write(0xCC); // NLPID, len 1, IP
        u16(o, 4);
        o.write(a); o.write(b); o.write(c); o.write(d);
        return o.toByteArray();
    }

    static byte[] cdpFrame() {
        ByteArrayOutputStream cdp = new ByteArrayOutputStream();
        cdp.write(2); cdp.write(180); u16(cdp, 0); // version 2, ttl, checksum
        cdpTlv(cdp, 0x0001, ascii("IDF2-3850-A.corp.example.com"));
        cdpTlv(cdp, 0x0002, cdpAddressV4(10, 20, 30, 2));
        cdpTlv(cdp, 0x0003, ascii("GigabitEthernet1/0/24"));
        cdpTlv(cdp, 0x0004, new byte[]{0, 0, 0, 0x29}); // router + switch + igmp
        cdpTlv(cdp, 0x0005, ascii("Cisco IOS Software, Catalyst L3 Switch Software\nVersion 16.12.4"));
        cdpTlv(cdp, 0x0006, ascii("cisco WS-C3850-48P"));
        cdpTlv(cdp, 0x000A, new byte[]{0, 110});
        cdpTlv(cdp, 0x000B, new byte[]{1});
        cdpTlv(cdp, 0x000E, new byte[]{1, 0, (byte) 210}); // voice vlan 210
        cdpTlv(cdp, 0x0016, cdpAddressV4(10, 99, 0, 5));
        byte[] payload = cdp.toByteArray();

        ByteArrayOutputStream f = new ByteArrayOutputStream();
        byte[] dst = mac(0x01, 0x00, 0x0c, 0xcc, 0xcc, 0xcc);
        f.write(dst, 0, 6);
        f.write(SWITCH_MAC, 0, 6);
        u16(f, 8 + payload.length); // 802.3 length (LLC/SNAP + CDP)
        f.write(0xAA); f.write(0xAA); f.write(0x03);
        f.write(0); f.write(0); f.write(0x0C);
        u16(f, 0x2000);
        f.write(payload, 0, payload.length);
        while (f.size() < 60) f.write(0);
        return f.toByteArray();
    }

    static void lldpTlv(ByteArrayOutputStream o, int type, byte[] v) {
        u16(o, (type << 9) | v.length);
        o.write(v, 0, v.length);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] lldpFrame(boolean tagged, int portIdSubtype, String portId, String portDesc) {
        ByteArrayOutputStream l = new ByteArrayOutputStream();
        lldpTlv(l, 1, concat(new byte[]{4}, SWITCH_MAC));
        lldpTlv(l, 2, concat(new byte[]{(byte) portIdSubtype}, ascii(portId)));
        lldpTlv(l, 3, new byte[]{0, 120});
        lldpTlv(l, 4, ascii(portDesc));
        lldpTlv(l, 5, ascii("core-sw-01"));
        lldpTlv(l, 6, ascii("Juniper Networks EX4300-48P"));
        lldpTlv(l, 7, new byte[]{0, 0x14, 0, 0x04}); // sys: bridge+router, enabled: bridge
        lldpTlv(l, 8, new byte[]{5, 1, 10, 0, 0, 1, 2, 0, 0, 0, 1, 0}); // mgmt 10.0.0.1, ifindex 1, no OID
        lldpTlv(l, 127, new byte[]{0x00, (byte) 0x80, (byte) 0xC2, 1, 0, 20}); // PVID 20
        lldpTlv(l, 127, concat(new byte[]{0x00, (byte) 0x80, (byte) 0xC2, 3, 0, 20, 4}, ascii("DATA")));
        lldpTlv(l, 127, new byte[]{0x00, 0x12, 0x0F, 1, 0x03, 0x6C, 0x01, 0, 30}); // 1000BASE-T FD
        lldpTlv(l, 127, new byte[]{0x00, 0x12, 0x0F, 2, 0x0F, 0x01, 0x05, 0x51, 0x00, (byte) 0xF0, 0x01, 0x2C}); // class 4, 30.0 W
        // LLDP-MED network policy: voice, tagged, vlan 30, cos 5, dscp 46
        int bits = (1 << 22) | (30 << 9) | (5 << 6) | 46;
        lldpTlv(l, 127, new byte[]{0x00, 0x12, (byte) 0xBB, 2, 1, (byte) (bits >> 16), (byte) (bits >> 8), (byte) bits});
        lldpTlv(l, 0, new byte[0]);
        byte[] payload = l.toByteArray();

        ByteArrayOutputStream f = new ByteArrayOutputStream();
        byte[] dst = mac(0x01, 0x80, 0xc2, 0x00, 0x00, 0x0e);
        f.write(dst, 0, 6);
        f.write(SWITCH_MAC, 0, 6);
        if (tagged) { u16(f, 0x8100); u16(f, 0x2000 | 20); }
        u16(f, 0x88CC);
        f.write(payload, 0, payload.length);
        return f.toByteArray();
    }

    // ---------------------------------------------------------------- tests

    @Test
    public void decodesCiscoCdp() {
        byte[] f = cdpFrame();
        NeighborInfo n = DiscoveryParser.parse(f, 0, f.length);
        assertNotNull(n);
        assertEquals("CDP", n.protocol);
        assertEquals("IDF2-3850-A.corp.example.com", n.systemName);
        assertEquals("GigabitEthernet1/0/24", n.headlinePort());
        assertEquals("110", n.nativeVlan);
        assertEquals("210", n.voiceVlan);
        assertEquals("10.99.0.5", n.mgmtAddress);
        assertEquals("cisco WS-C3850-48P", n.platform);
        assertEquals("Router, Switch, IGMP", n.capabilities);
        assertEquals("Full", n.details.get("Duplex"));
        assertEquals("10.20.30.2", n.details.get("IP address"));
        assertEquals("00:1b:54:aa:bb:0c", n.sourceMac);
        assertTrue(n.details.get("Software").contains(" / Version 16.12.4"));
        assertTrue(n.looksLikeInfrastructure());
    }

    @Test
    public void decodesCdpAtNonZeroOffset() {
        byte[] f = cdpFrame();
        byte[] padded = new byte[f.length + 28];
        System.arraycopy(f, 0, padded, 28, f.length);
        NeighborInfo n = DiscoveryParser.parse(padded, 28, f.length);
        assertNotNull(n);
        assertEquals("GigabitEthernet1/0/24", n.portId);
    }

    @Test
    public void decodesLldpWithOrgTlvs() {
        byte[] f = lldpFrame(false, 5, "ge-0/0/12", "ge-0/0/12.0");
        NeighborInfo n = DiscoveryParser.parse(f, 0, f.length);
        assertNotNull(n);
        assertEquals("LLDP", n.protocol);
        assertEquals("core-sw-01", n.systemName);
        assertEquals("ge-0/0/12", n.headlinePort());
        assertEquals("ge-0/0/12.0", n.portDescription);
        assertEquals("20", n.nativeVlan);
        assertEquals("30", n.voiceVlan);
        assertEquals("10.0.0.1", n.mgmtAddress);
        assertEquals("Bridge", n.capabilities);
        assertEquals("00:1b:54:aa:bb:0c", n.details.get("Chassis ID"));
        assertEquals("20 (DATA)", n.details.get("VLAN"));
        assertEquals("1000BASE-T full duplex", n.details.get("Operational speed"));
        assertEquals("Class 4, allocated 30.0 W", n.details.get("PoE (802.3)"));
        assertEquals("VLAN 30 tagged, CoS 5, DSCP 46", n.details.get("Voice VLAN"));
        assertEquals("", n.frameVlanTag);
    }

    @Test
    public void lldpPrefersDescriptionWhenPortIdIsAnIndex() {
        byte[] f = lldpFrame(true, 7, "522", "ge-0/0/12");
        NeighborInfo n = DiscoveryParser.parse(f, 0, f.length);
        assertNotNull(n);
        assertEquals("ge-0/0/12", n.headlinePort());
        assertEquals("20", n.frameVlanTag);
    }

    @Test
    public void ignoresOtherTraffic() {
        byte[] arp = new byte[60];
        arp[12] = 0x08; arp[13] = 0x06;
        assertNull(DiscoveryParser.parse(arp, 0, arp.length));
        // 802.3 frame to the CDP MAC but with a non-CDP SNAP PID (e.g. VTP 0x2003)
        byte[] f = cdpFrame();
        f[21] = 0x03;
        assertNull(DiscoveryParser.parse(f, 0, f.length));
    }

    @Test
    public void survivesTruncationAndGarbage() {
        byte[] good = lldpFrame(false, 5, "Gi1/0/1", "uplink");
        for (int len = 0; len <= good.length; len++) DiscoveryParser.parse(good, 0, len);
        byte[] cdp = cdpFrame();
        for (int len = 0; len <= cdp.length; len++) DiscoveryParser.parse(cdp, 0, len);
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < 20000; i++) {
            byte[] g = (i % 2 == 0 ? lldpFrame(false, 5, "x", "y") : cdpFrame()).clone();
            for (int k = 0; k < 6; k++) g[14 + r.nextInt(g.length - 14)] = (byte) r.nextInt(256);
            DiscoveryParser.parse(g, 0, g.length); // must not throw
        }
    }
}
