package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class FrameAnalyzerTest {

    // ------------------------------------------------------------------ frame builders

    static byte[] hex(String s) {
        s = s.replaceAll("[^0-9a-fA-F]", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] u16(int v) { return new byte[]{(byte) (v >> 8), (byte) v}; }

    static byte[] ipb(String ip) {
        String[] p = ip.split("\\.");
        return new byte[]{(byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]), (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])};
    }

    static byte[] eth(String dst, String src, int type, byte[] payload) {
        return cat(hex(dst), hex(src), u16(type), payload);
    }

    /** 802.3 + LLC/SNAP frame (length field instead of an EtherType). */
    static byte[] llcFrame(String dst, String src, byte[] llcAndData) {
        return cat(hex(dst), hex(src), u16(llcAndData.length), llcAndData);
    }

    static byte[] ipv4(String src, String dst, int proto, byte[] l4) {
        int total = 20 + l4.length;
        byte[] h = cat(hex("45 00"), u16(total), hex("00 00 00 00 40"), new byte[]{(byte) proto}, hex("00 00"), ipb(src), ipb(dst));
        return cat(h, l4);
    }

    static byte[] udp(int sp, int dp, byte[] data) {
        return cat(u16(sp), u16(dp), u16(8 + data.length), hex("00 00"), data);
    }

    static byte[] rstpBpdu(int flags, String rootMac, int rootPrio) {
        return cat(hex("00 00 02 02"), new byte[]{(byte) flags}, u16(rootPrio), hex(rootMac), hex("00 00 00 04"),
                u16(32778), hex("00 1b 54 aa bb 01"), hex("80 18"), hex("00 00 14 00 02 00 0f 00"), new byte[]{0});
    }

    static byte[] dhcpReply(int type, String server, String router, String yiaddr) {
        byte[] bootp = new byte[240];
        bootp[0] = 2;
        System.arraycopy(ipb(yiaddr), 0, bootp, 16, 4);
        System.arraycopy(hex("63 82 53 63"), 0, bootp, 236, 4);
        byte[] opts = cat(hex("35 01"), new byte[]{(byte) type}, hex("36 04"), ipb(server), hex("03 04"), ipb(router),
                hex("01 04 ff ff ff 00"), hex("ff"));
        return cat(bootp, opts);
    }

    static final String BCAST = "ff ff ff ff ff ff";

    static void feed(FrameAnalyzer a, byte[] f, long t) {
        a.onFrame(f, 0, f.length, t);
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void spanningTreeRstpAndTopologyChanges() {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] llc = hex("42 42 03");
        // designated role (bits 2-3 = 11) without TC, then two TC bursts far apart
        feed(a, llcFrame("01 80 c2 00 00 00", "00 1b 54 aa bb 01", cat(llc, rstpBpdu(0x0C, "00 1b 54 aa 00 01", 24586))), 0);
        feed(a, llcFrame("01 80 c2 00 00 00", "00 1b 54 aa bb 01", cat(llc, rstpBpdu(0x0D, "00 1b 54 aa 00 01", 24586))), 2000);
        feed(a, llcFrame("01 80 c2 00 00 00", "00 1b 54 aa bb 01", cat(llc, rstpBpdu(0x0D, "00 1b 54 aa 00 01", 24586))), 4000);
        feed(a, llcFrame("01 80 c2 00 00 00", "00 1b 54 aa bb 01", cat(llc, rstpBpdu(0x0D, "00 1b 54 aa 00 01", 24586))), 20000);
        FrameAnalyzer.Report r = a.report();
        assertEquals(1, r.stp.size());
        FrameAnalyzer.Stp s = r.stp.get(0);
        assertEquals("RSTP", s.mode);
        assertEquals(24586, s.rootPriority);
        assertEquals("00:1b:54:aa:00:01", s.rootMac);
        assertEquals("Designated", s.role);
        assertEquals(2, s.helloSeconds);
        assertEquals(2, r.topologyChanges); // 2000+4000 is one event, 20000 another
        assertEquals(4L, (long) r.protocols.get("Spanning tree"));
    }

    /** One topology change right after link-up (a port without PortFast coming up) isn't a warning. */
    @Test
    public void singleLinkUpTopologyChangeIsNormal() {
        byte[] llc = hex("42 42 03");
        byte[] calm = llcFrame("01 80 c2 00 00 00", "00 1b 54 aa bb 01", cat(llc, rstpBpdu(0x0C, "00 1b 54 aa 00 01", 24586)));
        byte[] tc = llcFrame("01 80 c2 00 00 00", "00 1b 54 aa bb 01", cat(llc, rstpBpdu(0x0D, "00 1b 54 aa 00 01", 24586)));

        FrameAnalyzer early = new FrameAnalyzer();
        feed(early, calm, 0);
        feed(early, tc, 30000);   // the port starts forwarding ~30 s after link
        feed(early, calm, 60000);
        Findings f = Findings.evaluate(early.report(), new Findings.Context());
        assertEquals(0, f.count(Findings.WARN));
        assertTrue(String.join(",", f.passed).contains("link-up"));

        FrameAnalyzer late = new FrameAnalyzer();
        feed(late, calm, 0);
        feed(late, tc, 80000);    // a change well after link-up is worth a warning
        assertEquals(1, Findings.evaluate(late.report(), new Findings.Context()).count(Findings.WARN));

        FrameAnalyzer twice = new FrameAnalyzer();
        feed(twice, calm, 0);
        feed(twice, tc, 20000);
        feed(twice, tc, 40000);   // two separate changes: something is flapping
        assertEquals(1, Findings.evaluate(twice.report(), new Findings.Context()).count(Findings.WARN));
    }

    @Test
    public void pvstPlusVlanAndTaggedFrames() {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] snap = hex("aa aa 03 00 00 0c 01 0b");
        byte[] bpdu = cat(rstpBpdu(0x3C, "00 1b 54 aa 00 01", 32878), hex("00 00 00 02"), u16(110));
        feed(a, llcFrame("01 00 0c cc cc cd", "00 1b 54 aa bb 01", cat(snap, bpdu)), 0);
        // a tagged ARP on VLAN 20
        byte[] arp = cat(hex("00 01 08 00 06 04 00 01"), hex("00 11 22 33 44 55"), ipb("10.0.20.5"), hex("00 00 00 00 00 00"), ipb("10.0.20.1"));
        byte[] tagged = cat(hex(BCAST), hex("00 11 22 33 44 55"), hex("81 00 00 14"), u16(0x0806), arp);
        feed(a, tagged, 100);
        FrameAnalyzer.Report r = a.report();
        assertEquals("Rapid PVST+", r.stp.get(0).mode);
        assertEquals(110, r.stp.get(0).pvstVlan);
        assertEquals(1L, (long) r.taggedVlans.get(20));
    }

    @Test
    public void dtpVtpAndEapol() {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] dtp = cat(hex("aa aa 03 00 00 0c 20 04"), hex("01"),
                hex("00 01 00 09"), "CORP\0".getBytes(), hex("00 02 00 05 03"), hex("00 03 00 05 a5"));
        feed(a, llcFrame("01 00 0c cc cc cc", "00 1b 54 aa bb 01", dtp), 0);
        byte[] domain = Arrays.copyOf("CORP".getBytes(), 32);
        byte[] vtp = cat(hex("aa aa 03 00 00 0c 20 03"), hex("02 01 00 04"), domain);
        feed(a, llcFrame("01 00 0c cc cc cc", "00 1b 54 aa bb 01", vtp), 10);
        byte[] eap = hex("01 00 00 05 01 01 00 05 01");
        feed(a, eth("01 80 c2 00 00 03", "00 1b 54 aa bb 01", 0x888E, eap), 20);
        FrameAnalyzer.Report r = a.report();
        assertEquals("Dynamic desirable", r.dtpMode);
        assertEquals("Access", r.dtpOperating);
        assertEquals("CORP", r.dtpDomain);
        assertEquals("CORP", r.vtpDomain);
        assertEquals(2, r.vtpVersion);
        assertTrue(r.eapol);
        assertTrue(r.eapIdentityRequest);
    }

    @Test
    public void rogueDhcpWithDifferentGatewayIsAProblem() {
        FrameAnalyzer a = new FrameAnalyzer();
        feed(a, eth("02 00 00 00 00 01", "00 1b 54 11 22 33", 0x0800,
                ipv4("10.0.0.5", "255.255.255.255", 17, udp(67, 68, dhcpReply(2, "10.0.0.5", "10.20.30.1", "10.20.30.57")))), 0);
        feed(a, eth("02 00 00 00 00 01", "50 c7 bf 00 00 99", 0x0800,
                ipv4("192.168.0.1", "255.255.255.255", 17, udp(67, 68, dhcpReply(2, "192.168.0.1", "192.168.0.1", "192.168.0.100")))), 5);
        FrameAnalyzer.Report r = a.report();
        assertEquals(2, r.dhcpServers.size());
        assertEquals("10.20.30.1", r.dhcpServers.get(0).router);
        Findings.Context c = new Findings.Context();
        c.dhcpOk = true;
        Findings f = Findings.evaluate(r, c);
        assertEquals(Findings.BAD, f.worst());
        assertTrue(f.items.get(0).title.startsWith("2 DHCP servers"));
    }

    @Test
    public void dhcpFailoverWithSameGatewayIsOnlyAWarning() {
        FrameAnalyzer a = new FrameAnalyzer();
        for (String s : new String[]{"10.0.0.5", "10.0.0.6"}) {
            feed(a, eth("02 00 00 00 00 01", "00 1b 54 11 22 33", 0x0800,
                    ipv4("10.20.30.1", "10.20.30.57", 17, udp(67, 68, dhcpReply(2, s, "10.20.30.1", "10.20.30.57")))), 0);
        }
        Findings f = Findings.evaluate(a.report(), new Findings.Context());
        assertEquals(Findings.WARN, f.items.get(0).severity);
    }

    @Test
    public void hsrpVrrpAndRouting() {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] hsrp = cat(hex("00 00 10 03 0a 6e 0a 00"), "cisco\0\0\0".getBytes(), ipb("10.20.30.1"));
        feed(a, eth("01 00 5e 00 00 02", "00 1b 54 00 00 02", 0x0800, ipv4("10.20.30.2", "224.0.0.2", 17, udp(1985, 1985, hsrp))), 0);
        byte[] vrrp = cat(hex("21 05 c8 01 00 01 00 00"), ipb("10.9.9.1"));
        feed(a, eth("01 00 5e 00 00 12", "00 00 5e 00 01 05", 0x0800, ipv4("10.9.9.2", "224.0.0.18", 112, vrrp)), 1);
        byte[] ospf = cat(hex("02 01 00 2c"), ipb("1.1.1.1"), ipb("0.0.0.0"), new byte[24]);
        feed(a, eth("01 00 5e 00 00 05", "00 1b 54 00 00 02", 0x0800, ipv4("10.20.30.2", "224.0.0.5", 89, ospf)), 2);
        FrameAnalyzer.Report r = a.report();
        assertEquals(2, r.gateways.size());
        FrameAnalyzer.Gateway h = r.gateways.get(0);
        assertEquals("HSRPv1", h.protocol);
        assertEquals(10, h.group);
        assertEquals("Active", h.state);
        assertEquals(110, h.priority);
        assertEquals("10.20.30.1", h.virtualIp);
        assertEquals("VRRPv2", r.gateways.get(1).protocol);
        assertEquals(200, r.gateways.get(1).priority);
        assertEquals("10.9.9.1", r.gateways.get(1).virtualIp);
        assertEquals("OSPF", r.routers.get(0).protocol);
        assertEquals("1.1.1.1", r.routers.get(0).id);
        Findings f = Findings.evaluate(r, new Findings.Context());
        boolean warned = false;
        for (Findings.Item i : f.items) warned |= i.title.startsWith("OSPF hellos");
        assertTrue(warned);
    }

    @Test
    public void arpConflictButNotForVirtualMacs() {
        FrameAnalyzer a = new FrameAnalyzer();
        for (String mac : new String[]{"00 11 22 33 44 55", "66 77 88 99 aa bb"}) {
            byte[] arp = cat(hex("00 01 08 00 06 04 00 02"), hex(mac), ipb("10.20.30.88"), hex("00 00 00 00 00 00"), ipb("10.20.30.1"));
            feed(a, eth(BCAST, mac, 0x0806, arp), 0);
        }
        // HSRP virtual MAC plus one real MAC for the VIP: not a conflict
        for (String mac : new String[]{"00 00 0c 07 ac 0a", "00 1b 54 00 00 02"}) {
            byte[] arp = cat(hex("00 01 08 00 06 04 00 02"), hex(mac), ipb("10.20.30.1"), hex("00 00 00 00 00 00"), ipb("10.20.30.88"));
            feed(a, eth(BCAST, mac, 0x0806, arp), 0);
        }
        FrameAnalyzer.Report r = a.report();
        assertEquals(1, r.arpConflicts.size());
        assertTrue(r.arpConflicts.containsKey("10.20.30.88"));
    }

    @Test
    public void ipv6RouterAdvertisement() {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] prefixOpt = cat(hex("03 04 40 c0 00 00 0e 10 00 00 0e 10 00 00 00 00"), hex("20 01 0d b8 00 01 00 00 00 00 00 00 00 00 00 00"));
        byte[] ra = cat(hex("86 00 00 00 40 40 07 08 00 00 00 00 00 00 00 00"), prefixOpt);
        byte[] ip6 = cat(hex("60 00 00 00"), u16(ra.length), hex("3a ff"), hex("fe 80 00 00 00 00 00 00 02 1b 54 ff fe 11 22 33"),
                hex("ff 02 00 00 00 00 00 00 00 00 00 00 00 00 00 01"), ra);
        feed(a, eth("33 33 00 00 00 01", "00 1b 54 11 22 33", 0x86DD, ip6), 0);
        FrameAnalyzer.Report r = a.report();
        assertEquals(1, r.ipv6Routers.size());
        assertEquals("fe80::21b:54ff:fe11:2233", r.ipv6Routers.get(0).sourceIp);
        assertEquals("2001:db8:1::/64", r.ipv6Routers.get(0).prefixes.get(0));
        assertTrue(r.ipv6Routers.get(0).other);
        assertEquals(1800, r.ipv6Routers.get(0).lifetime);
    }

    @Test
    public void devicesFromMdnsAndNetbiosAndDhcpHostnames() {
        FrameAnalyzer a = new FrameAnalyzer();
        // mDNS response with one A record: HP-LaserJet-4F.local -> 10.20.30.44
        byte[] name = cat(new byte[]{14}, "HP-LaserJet-4F".getBytes(), new byte[]{5}, "local".getBytes(), new byte[]{0});
        byte[] mdns = cat(hex("00 00 84 00 00 00 00 01 00 00 00 00"), name, hex("00 01 80 01 00 00 00 78 00 04"), ipb("10.20.30.44"));
        feed(a, eth("01 00 5e 00 00 fb", "3c 52 82 00 00 44", 0x0800, ipv4("10.20.30.44", "224.0.0.251", 17, udp(5353, 5353, mdns))), 0);
        // NetBIOS registration of WS-0412
        StringBuilder enc = new StringBuilder();
        String nb = String.format("%-15s", "WS-0412") + "\0";
        for (char ch : nb.toCharArray()) enc.append((char) ('A' + (ch >> 4))).append((char) ('A' + (ch & 0x0F)));
        byte[] nbns = cat(hex("12 34 29 10 00 01 00 00 00 00 00 01"), new byte[]{0x20}, enc.toString().getBytes(), new byte[]{0},
                hex("00 20 00 01"), hex("c0 0c 00 20 00 01 00 04 93 e0 00 06 00 00"), ipb("10.20.30.88"));
        feed(a, eth(BCAST, "00 14 22 00 04 12", 0x0800, ipv4("10.20.30.88", "10.20.30.255", 17, udp(137, 137, nbns))), 1);
        // DHCP request with host name
        byte[] bootp = new byte[240];
        bootp[0] = 1;
        System.arraycopy(hex("aa bb cc 00 00 61"), 0, bootp, 28, 6);
        System.arraycopy(hex("63 82 53 63"), 0, bootp, 236, 4);
        byte[] dreq = cat(bootp, hex("35 01 03 32 04"), ipb("10.20.30.61"), hex("0c 0c"), "Conf-Room-TV".getBytes(), hex("ff"));
        feed(a, eth(BCAST, "aa bb cc 00 00 61", 0x0800, ipv4("0.0.0.0", "255.255.255.255", 17, udp(68, 67, dreq))), 2);
        FrameAnalyzer.Report r = a.report();
        assertEquals(3, r.devices.size());
        assertEquals("Conf-Room-TV", r.devices.get(0).name);
        assertEquals("10.20.30.61", r.devices.get(0).ip);
        assertEquals("HP-LaserJet-4F", r.devices.get(1).name);
        assertEquals("10.20.30.44", r.devices.get(1).ip);
        assertEquals("WS-0412", r.devices.get(2).name);
        assertEquals("10.20.30.88", r.devices.get(2).ip);
    }

    @Test
    public void loopDetectionAndCleanNetworkPasses() {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] arp = cat(hex("00 01 08 00 06 04 00 01"), hex("00 11 22 33 44 55"), ipb("10.0.0.9"), hex("00 00 00 00 00 00"), ipb("10.0.0.1"));
        byte[] f = eth(BCAST, "00 11 22 33 44 55", 0x0806, arp);
        Findings clean = Findings.evaluate(feedAndReport(new FrameAnalyzer(), f, 3), new Findings.Context());
        assertEquals(Findings.OK, clean.worst());
        assertTrue(clean.passed.contains("no loops or storm"));
        for (int i = 0; i < 40; i++) feed(a, f, i * 10);
        FrameAnalyzer.Report r = a.report();
        assertTrue(r.loopRepeats >= FrameAnalyzer.LOOP_REPEAT_THRESHOLD);
        assertEquals(Findings.BAD, Findings.evaluate(r, new Findings.Context()).worst());
        assertEquals("00:11:22:33:44:55", r.topBroadcasters.get(0).mac);
        assertEquals("10.0.0.9", r.topBroadcasters.get(0).ip);
    }

    private static FrameAnalyzer.Report feedAndReport(FrameAnalyzer a, byte[] f, int n) {
        for (int i = 0; i < n; i++) feed(a, f, i * 400);
        return a.report();
    }

    @Test
    public void pcapFileFormat() throws Exception {
        FrameAnalyzer a = new FrameAnalyzer();
        byte[] f = eth(BCAST, "00 11 22 33 44 55", 0x0806, new byte[28]);
        feed(a, f, 1_700_000_000_123L);
        a.recordSent(new byte[60], 1_700_000_000_500L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        a.writePcap(out);
        byte[] p = out.toByteArray();
        assertEquals(24 + 16 + f.length + 16 + 60, p.length);
        assertEquals((byte) 0xD4, p[0]);           // little-endian magic a1b2c3d4
        assertEquals(1, p[20]);                    // link type Ethernet
        long sec = (p[24] & 0xFFL) | (p[25] & 0xFFL) << 8 | (p[26] & 0xFFL) << 16 | (p[27] & 0xFFL) << 24;
        assertEquals(1_700_000_000L, sec);
        assertEquals(2, a.report().capturedFrames);
        assertEquals(1, a.report().frames);       // sent frames aren't counted as received
    }

    @Test
    public void malformedFramesDoNotThrow() {
        FrameAnalyzer a = new FrameAnalyzer();
        java.util.Random rnd = new java.util.Random(42);
        int[] types = {0x0800, 0x86DD, 0x0806, 0x888E, 0x8100, 0x0026, 0x0040};
        for (int i = 0; i < 5000; i++) {
            byte[] f = new byte[14 + rnd.nextInt(300)];
            rnd.nextBytes(f);
            int t = types[rnd.nextInt(types.length)];
            f[12] = (byte) (t >> 8);
            f[13] = (byte) t;
            if (t <= 1500 && f.length > 17) { f[14] = (byte) (rnd.nextBoolean() ? 0x42 : 0xAA); f[15] = f[14]; f[16] = 3; }
            a.onFrame(f, 0, f.length, i);
        }
        FrameAnalyzer.Report r = a.report();
        assertEquals(5000, r.frames);
        Findings.evaluate(r, new Findings.Context());
        assertFalse(r.protocols.isEmpty());
    }
}
