package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Runs NetworkTester against a simulated access port: a DHCP server behind a relay, a gateway,
 * a DNS server, and a 5-hop path to the internet with one silent hop.
 */
public class NetworkTesterTest {

    static final byte[] GW_MAC = {0x00, 0x1b, 0x54, 0x11, 0x22, 0x33};
    static final byte[] CLIENT_MAC = {0x00, (byte) 0xe0, 0x4c, 0x68, 0x01, 0x02};
    static final int GW = ip("10.20.30.1"), OFFER = ip("10.20.30.57"), DHCP_SRV = ip("10.0.0.5"),
            DNS = ip("10.0.0.53"), TARGET = ip("142.250.72.14");
    /** Index = TTL-1. null = router that doesn't answer. Last entry is the destination. */
    static final int[] PATH = {GW, ip("10.0.0.1"), 0, ip("203.0.113.9"), TARGET};

    static int ip(String s) { return NetworkTester.ipToInt(s); }

    /** The simulated network. Replies are delivered asynchronously like a real wire. */
    static final class SimNet implements NetworkTester.FrameSender {
        final ScheduledExecutorService wire = Executors.newSingleThreadScheduledExecutor();
        NetworkTester tester;
        boolean dhcpServerUp = true;
        boolean nakFirstRequest = false;
        int requestsSeen, releasesSeen, discoversSeen;
        boolean clientAnsweredArp;
        int arpProbeSent;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        void deliver(byte[] f) {
            wire.schedule(() -> tester.onFrame(f, 0, f.length), 2, TimeUnit.MILLISECONDS);
        }

        @Override
        public synchronized boolean send(byte[] f) {
            int type = NetworkTester.u16(f, 12);
            if (type == 0x0806) arp(f);
            else if (type == 0x0800) ipv4(f);
            return true;
        }

        void arp(byte[] f) {
            int op = NetworkTester.u16(f, 20);
            int targetIp = NetworkTester.i32(f, 38);
            int senderIp = NetworkTester.i32(f, 28);
            if (op == 1 && targetIp == GW && senderIp != 0) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                w16(o, 1); w16(o, 0x0800); o.write(6); o.write(4); w16(o, 2);
                o.write(GW_MAC, 0, 6); w32(o, GW); o.write(f, 22, 6); w32(o, senderIp);
                deliver(eth(CLIENT_MAC, GW_MAC, 0x0806, o.toByteArray()));
            }
            if (op == 2 && senderIp == OFFER && NetworkTester.i32(f, 38) == GW) clientAnsweredArp = true;
            // Once the client has announced itself, the gateway ARPs for it (as a router would before replying)
            if (op == 1 && senderIp == OFFER && targetIp == OFFER && arpProbeSent++ == 0) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                w16(o, 1); w16(o, 0x0800); o.write(6); o.write(4); w16(o, 1);
                o.write(GW_MAC, 0, 6); w32(o, GW); o.write(new byte[6], 0, 6); w32(o, OFFER);
                deliver(eth(new byte[]{-1, -1, -1, -1, -1, -1}, GW_MAC, 0x0806, o.toByteArray()));
            }
        }

        void ipv4(byte[] f) {
            int p = 14;
            int hl = (f[p] & 0x0F) * 4;
            int total = NetworkTester.u16(f, p + 2);
            assertEquals("IP header checksum", 0, NetworkTester.checksum(f, p, hl));
            int ttl = f[p + 8] & 0xFF, proto = f[p + 9] & 0xFF;
            int src = NetworkTester.i32(f, p + 12), dst = NetworkTester.i32(f, p + 16);
            int l4 = p + hl;
            if (proto == 17) {
                int sport = NetworkTester.u16(f, l4), dport = NetworkTester.u16(f, l4 + 2);
                byte[] data = new byte[total - hl - 8];
                System.arraycopy(f, l4 + 8, data, 0, data.length);
                if (dport == 67) dhcp(data);
                else if (dport == 53 && dst == DNS) dns(data, sport);
            } else if (proto == 1 && (f[l4] & 0xFF) == 8) {
                byte[] icmp = new byte[total - hl];
                System.arraycopy(f, l4, icmp, 0, icmp.length);
                assertEquals("ICMP checksum", 0, NetworkTester.checksum(icmp, 0, icmp.length));
                if (dst == GW) {
                    echoReply(GW, icmp);
                } else if (dst == TARGET) {
                    if (ttl >= PATH.length) echoReply(TARGET, icmp);
                    else if (PATH[ttl - 1] != 0) timeExceeded(PATH[ttl - 1], f, p, hl);
                }
            }
        }

        void echoReply(int from, byte[] req) {
            byte[] r = req.clone();
            r[0] = 0;
            r[2] = 0; r[3] = 0;
            int c = NetworkTester.checksum(r, 0, r.length);
            r[2] = (byte) (c >> 8); r[3] = (byte) c;
            deliver(eth(CLIENT_MAC, GW_MAC, 0x0800, ipPkt(1, from, OFFER, r)));
        }

        void timeExceeded(int from, byte[] f, int p, int hl) {
            byte[] r = new byte[8 + hl + 8];
            r[0] = 11;
            System.arraycopy(f, p, r, 8, hl + 8);
            int c = NetworkTester.checksum(r, 0, r.length);
            r[2] = (byte) (c >> 8); r[3] = (byte) c;
            deliver(eth(CLIENT_MAC, GW_MAC, 0x0800, ipPkt(1, from, OFFER, r)));
        }

        void dhcp(byte[] d) {
            Map<Integer, byte[]> opts = new HashMap<>();
            for (int q = 240; q < d.length && (d[q] & 0xFF) != 255; ) {
                if (d[q] == 0) { q++; continue; }
                int code = d[q] & 0xFF, l = d[q + 1] & 0xFF;
                byte[] v = new byte[l];
                System.arraycopy(d, q + 2, v, 0, l);
                opts.put(code, v);
                q += 2 + l;
            }
            int type = opts.get(53)[0];
            assertEquals("client id", 7, opts.get(61).length);
            int xid = NetworkTester.i32(d, 4);
            if (type == 7) { releasesSeen++; return; }
            if (!dhcpServerUp) { if (type == 1) discoversSeen++; return; }
            if (type == 1) {
                discoversSeen++;
                assertTrue("asks for broadcast reply", (d[10] & 0x80) != 0);
                deliver(dhcpReply(xid, 2));
            } else if (type == 3) {
                requestsSeen++;
                assertEquals(OFFER, NetworkTester.i32(opts.get(50), 0));
                assertEquals(DHCP_SRV, NetworkTester.i32(opts.get(54), 0));
                deliver(dhcpReply(xid, nakFirstRequest && requestsSeen == 1 ? 6 : 5));
            }
        }

        byte[] dhcpReply(int xid, int type) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(2); o.write(1); o.write(6); o.write(1);
            w32(o, xid); w16(o, 0); w16(o, 0x8000);
            w32(o, 0); w32(o, type == 6 ? 0 : OFFER); w32(o, 0); w32(o, GW); // ciaddr yiaddr siaddr giaddr
            o.write(CLIENT_MAC, 0, 6);
            o.write(new byte[202], 0, 202);
            w32(o, 0x63825363);
            opt(o, 53, new byte[]{(byte) type});
            opt(o, 54, be(DHCP_SRV));
            if (type != 6) {
                opt(o, 51, be(86400));
                opt(o, 1, be(ip("255.255.255.0")));
                opt(o, 3, be(GW));
                byte[] dns = new byte[8];
                System.arraycopy(be(DNS), 0, dns, 0, 4);
                System.arraycopy(be(ip("10.0.0.54")), 0, dns, 4, 4);
                opt(o, 6, dns);
                opt(o, 15, "corp.example.com".getBytes(StandardCharsets.US_ASCII));
            }
            o.write(255);
            byte[] udp = udpPkt(67, 68, o.toByteArray());
            return eth(new byte[]{-1, -1, -1, -1, -1, -1}, GW_MAC, 0x0800, ipPkt(17, GW, 0xFFFFFFFF, udp));
        }

        void dns(byte[] q, int sport) {
            int id = NetworkTester.u16(q, 0);
            String name = NetworkTester.readName(q, 12);
            int qend = NetworkTester.skipName(q, 12);
            int qtype = NetworkTester.u16(q, qend);
            log.add("DNS " + qtype + " " + name);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            Map<String, String> ptr = new HashMap<>();
            ptr.put("1.30.20.10.in-addr.arpa", "idf2-gw.corp.example.com");
            ptr.put("9.113.0.203.in-addr.arpa", "edge1.isp.example.net");
            boolean found = (qtype == 1 && name.equals("google.com")) || (qtype == 12 && ptr.containsKey(name));
            w16(o, id); w16(o, found ? 0x8180 : 0x8183); w16(o, 1); w16(o, found ? 2 : 0); w16(o, 0); w16(o, 0);
            o.write(q, 12, qend + 4 - 12);
            if (found) {
                if (qtype == 1) {
                    // A CNAME first (exercises skipping), then the A record, both using name compression
                    w16(o, 0xC00C); w16(o, 5); w16(o, 1); w32(o, 60);
                    byte[] cn = {3, 'w', 'w', 'w', (byte) 0xC0, 12};
                    w16(o, cn.length); o.write(cn, 0, cn.length);
                    w16(o, 0xC00C); w16(o, 1); w16(o, 1); w32(o, 60); w16(o, 4); o.write(be(TARGET), 0, 4);
                } else {
                    byte[] enc = encode(ptr.get(name));
                    w16(o, 0xC00C); w16(o, 12); w16(o, 1); w32(o, 60); w16(o, enc.length); o.write(enc, 0, enc.length);
                    w16(o, 0xC00C); w16(o, 99); w16(o, 1); w32(o, 60); w16(o, 0);
                }
            }
            deliver(eth(CLIENT_MAC, GW_MAC, 0x0800, ipPkt(17, DNS, OFFER, udpPkt(53, sport, o.toByteArray()))));
        }
    }

    // ---------------------------------------------------------------- packet helpers

    static byte[] encode(String name) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String l : name.split("\\.")) { o.write(l.length()); o.write(l.getBytes(StandardCharsets.US_ASCII), 0, l.length()); }
        o.write(0);
        return o.toByteArray();
    }

    static void opt(ByteArrayOutputStream o, int c, byte[] v) { o.write(c); o.write(v.length); o.write(v, 0, v.length); }

    static byte[] be(int v) { return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }

    static void w16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }

    static void w32(ByteArrayOutputStream o, int v) { w16(o, v >>> 16); w16(o, v); }

    static byte[] eth(byte[] dst, byte[] src, int type, byte[] payload) {
        byte[] f = new byte[Math.max(60, 14 + payload.length)];
        System.arraycopy(dst, 0, f, 0, 6);
        System.arraycopy(src, 0, f, 6, 6);
        f[12] = (byte) (type >> 8); f[13] = (byte) type;
        System.arraycopy(payload, 0, f, 14, payload.length);
        return f;
    }

    static byte[] ipPkt(int proto, int src, int dst, byte[] payload) {
        byte[] p = new byte[20 + payload.length];
        p[0] = 0x45;
        p[2] = (byte) (p.length >> 8); p[3] = (byte) p.length;
        p[8] = 64; p[9] = (byte) proto;
        System.arraycopy(be(src), 0, p, 12, 4);
        System.arraycopy(be(dst), 0, p, 16, 4);
        int c = NetworkTester.checksum(p, 0, 20);
        p[10] = (byte) (c >> 8); p[11] = (byte) c;
        System.arraycopy(payload, 0, p, 20, payload.length);
        return p;
    }

    static byte[] udpPkt(int sport, int dport, byte[] data) {
        byte[] u = new byte[8 + data.length];
        u[0] = (byte) (sport >> 8); u[1] = (byte) sport; u[2] = (byte) (dport >> 8); u[3] = (byte) dport;
        u[4] = (byte) (u.length >> 8); u[5] = (byte) u.length;
        System.arraycopy(data, 0, u, 8, data.length);
        return u;
    }

    // ---------------------------------------------------------------- recorder

    static final class Recorder implements NetworkTester.Listener {
        NetworkTester.DhcpLease lease;
        String dhcpFail;
        String gwMac;
        NetworkTester.PingResult gwPing, ping;
        NetworkTester.DnsResult dns;
        final Map<Integer, NetworkTester.Hop> hops = new HashMap<>();
        Boolean reached;
        String traceNote;
        boolean finished;
        final List<String> status = new ArrayList<>();

        public void onNetStatus(String m) { status.add(m); }
        public void onDhcp(NetworkTester.DhcpLease l) { lease = l; }
        public void onDhcpFailed(String r) { dhcpFail = r; }
        public void onGateway(String mac, NetworkTester.PingResult p) { gwMac = mac; gwPing = p; }
        public void onDns(NetworkTester.DnsResult r) { dns = r; }
        public void onPing(NetworkTester.PingResult r) { ping = r; }
        public void onHop(NetworkTester.Hop h) { hops.put(h.ttl, h); }
        public void onTraceDone(boolean r, String n) { reached = r; traceNote = n; }
        public void onNetFinished() { finished = true; }
    }

    static NetworkTester.Config fastConfig() {
        NetworkTester.Config c = new NetworkTester.Config();
        c.dhcpTimeoutMs = 500;
        c.dhcpAttempts = 2;
        c.pingTimeoutMs = 300;
        c.hopTimeoutMs = 300;
        c.dnsTimeoutMs = 300;
        c.ptrTimeoutMs = 300;
        c.arpTimeoutMs = 300;
        return c;
    }

    // ---------------------------------------------------------------- tests

    @Test
    public void fullRunAgainstSimulatedNetwork() throws Exception {
        SimNet net = new SimNet();
        Recorder rec = new Recorder();
        NetworkTester t = new NetworkTester(net, CLIENT_MAC, fastConfig(), rec);
        net.tester = t;
        // Unrelated traffic on the wire must be ignored
        byte[] noise = eth(new byte[]{-1, -1, -1, -1, -1, -1}, GW_MAC, 0x0800,
                ipPkt(17, ip("10.20.30.99"), 0xFFFFFFFF, udpPkt(137, 137, new byte[40])));
        t.onFrame(noise, 0, noise.length);
        long start = System.currentTimeMillis();
        t.run();
        long took = System.currentTimeMillis() - start;
        net.wire.shutdownNow();

        assertNull(rec.dhcpFail);
        assertNotNull(rec.lease);
        assertEquals("10.20.30.57", rec.lease.ip);
        assertEquals(24, rec.lease.prefix);
        assertEquals("10.20.30.0/24", rec.lease.network());
        assertEquals("10.20.30.1", rec.lease.gateway);
        assertEquals("10.0.0.5", rec.lease.server);
        assertEquals("10.20.30.1", rec.lease.relay);
        assertEquals("corp.example.com", rec.lease.domain);
        assertEquals(86400L, rec.lease.leaseSeconds);
        assertEquals(2, rec.lease.dns.size());

        assertEquals("00:1b:54:11:22:33", rec.gwMac);
        assertEquals(3, rec.gwPing.received);
        assertTrue("client answered the gateway's ARP", net.clientAnsweredArp);

        assertTrue(rec.dns.ok());
        assertEquals("142.250.72.14", rec.dns.address);
        assertEquals("10.0.0.53", rec.dns.server);

        assertEquals(4, rec.ping.sent);
        assertEquals(4, rec.ping.received);

        assertEquals(Boolean.TRUE, rec.reached);
        assertEquals(5, rec.hops.size());
        assertEquals("10.20.30.1", rec.hops.get(1).address());
        assertEquals("idf2-gw.corp.example.com", rec.hops.get(1).name);
        assertEquals("10.0.0.1", rec.hops.get(2).address());
        assertEquals("", rec.hops.get(2).name); // NXDOMAIN
        assertTrue(rec.hops.get(3).silent());
        assertEquals("edge1.isp.example.net", rec.hops.get(4).name);
        assertEquals("142.250.72.14", rec.hops.get(5).address());
        for (Double r : rec.hops.get(5).rttMs) assertNotNull(r);

        assertEquals(1, net.releasesSeen);
        assertTrue(rec.finished);
        System.out.println("  simulated run took " + took + " ms");
        for (int i = 1; i <= 5; i++) System.out.println("  " + rec.hops.get(i).line());
    }

    @Test
    public void dhcpNakThenSuccess() {
        SimNet net = new SimNet();
        net.nakFirstRequest = true;
        Recorder rec = new Recorder();
        NetworkTester.Config c = fastConfig();
        c.target = "142.250.72.14"; // IP literal: DNS step is skipped
        NetworkTester t = new NetworkTester(net, CLIENT_MAC, c, rec);
        net.tester = t;
        t.run();
        net.wire.shutdownNow();
        assertNotNull(rec.lease);
        assertEquals(2, net.requestsSeen);
        assertTrue(rec.dns.error.startsWith("Skipped"));
        assertEquals(Boolean.TRUE, rec.reached);
    }

    @Test
    public void noDhcpServer() {
        SimNet net = new SimNet();
        net.dhcpServerUp = false;
        Recorder rec = new Recorder();
        NetworkTester t = new NetworkTester(net, CLIENT_MAC, fastConfig(), rec);
        net.tester = t;
        t.run();
        net.wire.shutdownNow();
        assertNull(rec.lease);
        assertNotNull(rec.dhcpFail);
        assertEquals(2, net.discoversSeen);
        assertEquals(0, net.releasesSeen);
        assertTrue(rec.finished);
    }

    @Test
    public void stopIsPrompt() throws Exception {
        SimNet net = new SimNet();
        net.dhcpServerUp = false;
        Recorder rec = new Recorder();
        NetworkTester.Config c = fastConfig();
        c.dhcpTimeoutMs = 10000;
        NetworkTester t = new NetworkTester(net, CLIENT_MAC, c, rec);
        net.tester = t;
        Thread th = new Thread(t::run);
        th.start();
        Thread.sleep(200);
        long s = System.currentTimeMillis();
        t.stop();
        th.join(2000);
        net.wire.shutdownNow();
        assertTrue("worker exited", !th.isAlive());
        assertTrue("stopped within 1 s", System.currentTimeMillis() - s < 1000);
    }

    @Test
    public void ipParsing() {
        assertEquals(0, NetworkTester.ipToIntOrZero("google.com"));
        assertEquals(0, NetworkTester.ipToIntOrZero("1.2.3"));
        assertEquals(0, NetworkTester.ipToIntOrZero("1.2.3.256"));
        assertEquals("8.8.4.4", NetworkTester.intToIp(NetworkTester.ipToInt("8.8.4.4")));
        assertEquals("255.255.255.255", NetworkTester.intToIp(0xFFFFFFFF));
    }
}
