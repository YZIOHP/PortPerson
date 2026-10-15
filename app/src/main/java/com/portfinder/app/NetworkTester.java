package com.portfinder.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A minimal IPv4 client that runs entirely over raw Ethernet frames: DHCP, ARP, ICMP echo, DNS and an
 * ICMP traceroute. Because the app owns the USB adapter directly, Android's own network stack never
 * sees this link; doing it here also guarantees every result came from the wall drop under test,
 * never from Wi-Fi or cellular.
 *
 * Pure Java (no Android imports) so the whole thing can be exercised against a simulated network.
 * Feed every received frame to {@link #onFrame}; call {@link #run} on a worker thread.
 */
public final class NetworkTester {

    // ------------------------------------------------------------------ public API

    public interface FrameSender {
        boolean send(byte[] frame);
    }

    /** All callbacks arrive on the worker thread. */
    public interface Listener {
        void onNetStatus(String message);
        void onDhcp(DhcpLease lease);
        void onDhcpFailed(String reason);
        void onGateway(String gatewayMac, PingResult ping);
        void onDns(DnsResult result);
        void onPing(PingResult result);
        void onHop(Hop hop);
        void onTraceDone(boolean reached, String note);
        void onNetFinished();
    }

    public static final class Config {
        public String target = "google.com";
        public String hostname = "PortPerson";
        public int dhcpAttempts = 4;
        public int dhcpTimeoutMs = 4000;
        public int arpTimeoutMs = 1000;
        public int pingCount = 4;
        public int pingTimeoutMs = 1000;
        public int dnsTimeoutMs = 2000;
        public int maxHops = 30;
        public int probesPerHop = 3;
        public int hopTimeoutMs = 2000;
        public int maxSilentHops = 8;
        public int ptrTimeoutMs = 1000;
        public boolean releaseLease = true;
    }

    public static final class DhcpLease {
        public String ip = "", mask = "", gateway = "", server = "", serverMac = "", relay = "", domain = "";
        public int prefix;
        public long leaseSeconds = -1;
        public final List<String> dns = new ArrayList<>();
        public long offerMs, ackMs;

        public String cidr() { return ip + "/" + prefix; }

        public String network() {
            int a = ipToInt(ip), m = ipToInt(mask);
            return intToIp(a & m) + "/" + prefix;
        }
    }

    public static final class PingResult {
        public String target = "";
        public int sent, received;
        public double minMs = -1, avgMs = -1, maxMs = -1;

        public String summary() {
            if (received == 0) return "No reply (" + sent + " sent)";
            return String.format(Locale.US, "%d/%d replies · min %.1f / avg %.1f / max %.1f ms",
                    received, sent, minMs, avgMs, maxMs);
        }
    }

    public static final class DnsResult {
        public String name = "", address = "", server = "", error = "";
        public long ms = -1;

        public boolean ok() { return !address.isEmpty(); }
    }

    public static final class Hop {
        public final int ttl;
        public final List<String> addresses = new ArrayList<>();
        public final Double[] rttMs;
        public String name = "";
        public String flag = ""; // e.g. "!H" for host unreachable

        Hop(int ttl, int probes) {
            this.ttl = ttl;
            this.rttMs = new Double[probes];
        }

        public boolean silent() { return addresses.isEmpty(); }

        public String address() { return addresses.isEmpty() ? "*" : addresses.get(0); }

        public String rttText() {
            StringBuilder sb = new StringBuilder();
            for (Double r : rttMs) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(r == null ? "*" : String.format(Locale.US, "%.1f ms", r));
            }
            return sb.toString();
        }

        public String line() {
            if (silent()) return String.format(Locale.US, "%2d  %s  (no reply)", ttl, rttText());
            String who = name.isEmpty() ? address() : name + " (" + address() + ")";
            if (addresses.size() > 1) who += " +" + (addresses.size() - 1) + " more";
            return String.format(Locale.US, "%2d  %s  %s%s", ttl, who, rttText(), flag.isEmpty() ? "" : "  " + flag);
        }
    }

    // ------------------------------------------------------------------ state

    private static final int ETH_IP = 0x0800, ETH_ARP = 0x0806;
    private static final int PROTO_ICMP = 1, PROTO_UDP = 17;
    private static final byte[] BROADCAST = {-1, -1, -1, -1, -1, -1};

    private final FrameSender sender;
    private final Listener listener;
    private final Config cfg;
    private final byte[] mac;
    private final SecureRandom rnd = new SecureRandom();

    private final LinkedBlockingQueue<IpPacket> inbox = new LinkedBlockingQueue<>(4096);
    private final ConcurrentHashMap<Integer, byte[]> arpTable = new ConcurrentHashMap<>();
    private final Object arpLock = new Object();

    private volatile boolean stopped;
    private volatile int myIp;        // 0 until DHCP completes
    private volatile boolean dhcpPhase;
    private int mask, gateway;
    private int ipId;
    private final int icmpId;
    private int icmpSeq;

    public NetworkTester(FrameSender sender, byte[] mac, Config cfg, Listener listener) {
        this.sender = sender;
        this.mac = mac.clone();
        this.cfg = cfg;
        this.listener = listener;
        this.icmpId = rnd.nextInt(0xFFFF) + 1;
        this.ipId = rnd.nextInt(0xFFFF);
    }

    public void stop() {
        stopped = true;
    }

    public String macString() { return DiscoveryParser.mac(mac, 0); }

    // ------------------------------------------------------------------ receive path (capture thread)

    private static final class IpPacket {
        final byte[] p;   // IPv4 packet starting at the IP header
        final int hl;     // IP header length
        final int proto, src, dst;
        final byte[] srcMac;
        final long rxNanos;

        IpPacket(byte[] p, byte[] srcMac, long rxNanos) {
            this.p = p;
            this.hl = (p[0] & 0x0F) * 4;
            this.proto = p[9] & 0xFF;
            this.src = i32(p, 12);
            this.dst = i32(p, 16);
            this.srcMac = srcMac;
            this.rxNanos = rxNanos;
        }

        int l4() { return hl; }
    }

    /** Called for every received Ethernet frame. Cheap; never blocks. */
    public void onFrame(byte[] b, int off, int len) {
        try {
            if (stopped || len < 14) return;
            long now = System.nanoTime();
            int type = u16(b, off + 12);
            int p = off + 14;
            if (type == 0x8100) return; // tagged frames belong to other VLANs; we test the untagged (access) VLAN
            int end = off + len;
            if (type == ETH_ARP) {
                handleArp(b, p, end);
                return;
            }
            if (type != ETH_IP || p + 20 > end) return;
            if ((b[p] & 0xF0) != 0x40) return;
            int hl = (b[p] & 0x0F) * 4;
            int total = u16(b, p + 2);
            if (hl < 20 || total < hl || p + total > end) return;
            int dst = i32(b, p + 16);
            boolean forUs;
            int ip = myIp;
            if (ip != 0) {
                forUs = dst == ip;
            } else {
                // Before we have an address, only DHCP replies matter: UDP to port 68, sent to our MAC or broadcast.
                boolean macOk = macEquals(b, off, mac) || macEquals(b, off, BROADCAST);
                forUs = dhcpPhase && macOk && (b[p + 9] & 0xFF) == PROTO_UDP && p + hl + 4 <= end
                        && u16(b, p + hl + 2) == 68;
            }
            if (!forUs) return;
            byte[] pkt = new byte[total];
            System.arraycopy(b, p, pkt, 0, total);
            byte[] srcMac = new byte[6];
            System.arraycopy(b, off + 6, srcMac, 0, 6);
            inbox.offer(new IpPacket(pkt, srcMac, now));
        } catch (RuntimeException ignored) {
            // malformed frame
        }
    }

    private void handleArp(byte[] b, int p, int end) {
        if (p + 28 > end) return;
        if (u16(b, p) != 1 || u16(b, p + 2) != ETH_IP || b[p + 4] != 6 || b[p + 5] != 4) return;
        int op = u16(b, p + 6);
        byte[] senderMac = new byte[6];
        System.arraycopy(b, p + 8, senderMac, 0, 6);
        int senderIp = i32(b, p + 14);
        int targetIp = i32(b, p + 24);
        int ip = myIp;
        if (senderIp != 0 && (op == 2 || targetIp == ip)) {
            arpTable.put(senderIp, senderMac);
            synchronized (arpLock) {
                arpLock.notifyAll();
            }
        }
        if (op == 1 && ip != 0 && targetIp == ip) {
            sender.send(arpFrame(2, senderMac, senderMac, senderIp));
        }
    }

    // ------------------------------------------------------------------ test sequence (worker thread)

    public void run() {
        DhcpLease lease = null;
        try {
            listener.onNetStatus("Requesting an address via DHCP…");
            lease = dhcp();
            if (stopped) return;
            if (lease == null) {
                listener.onDhcpFailed("No DHCP offer after " + cfg.dhcpAttempts + " attempts ("
                        + (cfg.dhcpAttempts * cfg.dhcpTimeoutMs / 1000) + " s). The port may be in a VLAN without "
                        + "a DHCP server or helper, blocked by 802.1X, or err-disabled.");
                return;
            }
            myIp = ipToInt(lease.ip);
            mask = ipToInt(lease.mask);
            gateway = lease.gateway.isEmpty() ? 0 : ipToInt(lease.gateway);
            sender.send(arpFrame(1, BROADCAST, new byte[6], myIp)); // gratuitous ARP announcement
            listener.onDhcp(lease);

            // Gateway
            String gwMac = "";
            PingResult gwPing = null;
            if (gateway != 0) {
                listener.onNetStatus("Checking the default gateway…");
                byte[] m = resolve(gateway);
                if (m != null) gwMac = DiscoveryParser.mac(m, 0);
                gwPing = ping(gateway, 3);
            }
            if (stopped) return;
            listener.onGateway(gwMac, gwPing);
            if (gateway == 0) {
                listener.onTraceDone(false, "DHCP gave no default gateway, so internet tests were skipped.");
                return;
            }

            // DNS
            int targetIp = ipToIntOrZero(cfg.target);
            DnsResult dns = new DnsResult();
            dns.name = cfg.target;
            if (targetIp == 0) {
                listener.onNetStatus("Resolving " + cfg.target + "…");
                dns = resolveName(cfg.target, lease.dns);
                if (dns.ok()) targetIp = ipToInt(dns.address);
            } else {
                dns.address = cfg.target;
                dns.error = "Skipped (target is an IP address)";
            }
            if (stopped) return;
            listener.onDns(dns);
            if (targetIp == 0) {
                targetIp = ipToInt("8.8.8.8");
                listener.onNetStatus("DNS failed; testing to 8.8.8.8 instead");
            }

            // Ping
            listener.onNetStatus("Pinging " + intToIp(targetIp) + "…");
            PingResult pr = ping(targetIp, cfg.pingCount);
            if (stopped) return;
            listener.onPing(pr);

            // Traceroute
            listener.onNetStatus("Tracing the route to " + intToIp(targetIp) + "…");
            List<Hop> hops = traceroute(targetIp);
            if (stopped) return;

            // Reverse DNS names for the hops
            if (!lease.dns.isEmpty()) {
                listener.onNetStatus("Looking up hop names…");
                Map<String, String> cache = new HashMap<>();
                for (Hop h : hops) {
                    if (stopped) return;
                    if (h.silent()) continue;
                    String a = h.address();
                    String name = cache.containsKey(a) ? cache.get(a) : reverseLookup(a, lease.dns);
                    cache.put(a, name);
                    if (!name.isEmpty()) {
                        h.name = name;
                        listener.onHop(h);
                    }
                }
            }
        } finally {
            if (lease != null && cfg.releaseLease && myIp != 0) {
                try {
                    releaseLease(lease);
                } catch (RuntimeException ignored) {
                }
            }
            listener.onNetFinished();
        }
    }

    // ------------------------------------------------------------------ DHCP

    private DhcpLease dhcp() {
        dhcpPhase = true;
        try {
            long start = System.nanoTime();
            for (int attempt = 1; attempt <= cfg.dhcpAttempts && !stopped; attempt++) {
                if (attempt > 1) listener.onNetStatus("No DHCP offer yet, retrying (" + attempt + "/" + cfg.dhcpAttempts + ")…");
                int xid = rnd.nextInt();
                sendDhcp(1, xid, 0, 0, 0, BROADCAST, 0xFFFFFFFF);
                IpPacket offer = await(pk -> dhcpType(pk, xid) == 2, cfg.dhcpTimeoutMs);
                if (offer == null) continue;
                long offerMs = ms(start, offer.rxNanos);
                Map<Integer, byte[]> oo = dhcpOptions(offer);
                int offered = i32(offer.p, offer.l4() + 8 + 16);
                int serverId = oo.containsKey(54) ? i32(oo.get(54), 0) : offer.src;
                listener.onNetStatus("Offered " + intToIp(offered) + ", requesting…");
                for (int r = 0; r < 2 && !stopped; r++) {
                    sendDhcp(3, xid, offered, serverId, 0, BROADCAST, 0xFFFFFFFF);
                    IpPacket ack = await(pk -> {
                        int t = dhcpType(pk, xid);
                        return t == 5 || t == 6;
                    }, cfg.dhcpTimeoutMs);
                    if (ack == null) continue;
                    if (dhcpType(ack, xid) == 6) {
                        listener.onNetStatus("DHCP server refused the request (NAK), starting over…");
                        break;
                    }
                    return leaseFrom(ack, offerMs, ms(start, ack.rxNanos));
                }
            }
            return null;
        } finally {
            dhcpPhase = false;
        }
    }

    private DhcpLease leaseFrom(IpPacket ack, long offerMs, long ackMs) {
        int b = ack.l4() + 8;
        Map<Integer, byte[]> o = dhcpOptions(ack);
        DhcpLease l = new DhcpLease();
        l.ip = intToIp(i32(ack.p, b + 16));
        int m = o.containsKey(1) && o.get(1).length >= 4 ? i32(o.get(1), 0) : 0xFFFFFF00;
        l.mask = intToIp(m);
        l.prefix = Integer.bitCount(m);
        if (o.containsKey(3) && o.get(3).length >= 4) l.gateway = intToIp(i32(o.get(3), 0));
        if (o.containsKey(6)) {
            byte[] d = o.get(6);
            for (int i = 0; i + 4 <= d.length; i += 4) l.dns.add(intToIp(i32(d, i)));
        }
        if (o.containsKey(15)) l.domain = new String(o.get(15), StandardCharsets.US_ASCII).replace("\0", "").trim();
        if (o.containsKey(51) && o.get(51).length >= 4) l.leaseSeconds = u32(o.get(51), 0);
        l.server = intToIp(o.containsKey(54) && o.get(54).length >= 4 ? i32(o.get(54), 0) : ack.src);
        l.serverMac = DiscoveryParser.mac(ack.srcMac, 0);
        int giaddr = i32(ack.p, b + 24);
        if (giaddr != 0) l.relay = intToIp(giaddr);
        l.offerMs = offerMs;
        l.ackMs = ackMs;
        return l;
    }

    private void releaseLease(DhcpLease l) {
        int server = ipToInt(l.server);
        byte[] dstMac = nextHopMac(server, 300);
        if (dstMac == null) return;
        sendDhcp(7, rnd.nextInt(), 0, server, myIp, dstMac, server);
    }

    /** type: 1 discover, 3 request, 7 release. */
    private void sendDhcp(int type, int xid, int requestedIp, int serverId, int ciaddr, byte[] dstMac, int dstIp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1); o.write(1); o.write(6); o.write(0);           // op, htype, hlen, hops
        w32(o, xid);
        w16(o, 0);                                                 // secs
        w16(o, type == 7 ? 0 : 0x8000);                            // flags: ask for broadcast replies
        w32(o, ciaddr);
        w32(o, 0); w32(o, 0); w32(o, 0);                           // yiaddr, siaddr, giaddr
        o.write(mac, 0, 6);
        o.write(new byte[10 + 64 + 128], 0, 202);                  // chaddr pad, sname, file
        w32(o, 0x63825363);                                        // magic cookie
        opt(o, 53, new byte[]{(byte) type});
        byte[] cid = new byte[7];
        cid[0] = 1;
        System.arraycopy(mac, 0, cid, 1, 6);
        opt(o, 61, cid);
        if (requestedIp != 0) opt(o, 50, be32(requestedIp));
        if (serverId != 0) opt(o, 54, be32(serverId));
        if (type != 7) {
            opt(o, 12, cfg.hostname.getBytes(StandardCharsets.US_ASCII));
            opt(o, 55, new byte[]{1, 3, 6, 15, 28, 42, 51, 54, 58, 59});
        }
        o.write(255);
        while (o.size() < 300) o.write(0);
        byte[] udp = udp(68, 67, o.toByteArray(), ciaddr, dstIp);
        sender.send(eth(dstMac, ETH_IP, ip(PROTO_UDP, ciaddr, dstIp, 64, udp)));
    }

    private static void opt(ByteArrayOutputStream o, int code, byte[] v) {
        o.write(code);
        o.write(v.length);
        o.write(v, 0, v.length);
    }

    private static int dhcpType(IpPacket pk, int xid) {
        if (pk.proto != PROTO_UDP) return -1;
        int u = pk.l4();
        if (pk.p.length < u + 8 + 240 || u16(pk.p, u + 2) != 68) return -1;
        int b = u + 8;
        if (pk.p[b] != 2 || i32(pk.p, b + 4) != xid || i32(pk.p, b + 236) != 0x63825363) return -1;
        byte[] t = dhcpOptions(pk).get(53);
        return t == null || t.length < 1 ? -1 : t[0] & 0xFF;
    }

    private static Map<Integer, byte[]> dhcpOptions(IpPacket pk) {
        Map<Integer, byte[]> m = new HashMap<>();
        byte[] p = pk.p;
        int q = pk.l4() + 8 + 240;
        while (q < p.length) {
            int code = p[q] & 0xFF;
            if (code == 255) break;
            if (code == 0) { q++; continue; }
            if (q + 2 > p.length) break;
            int l = p[q + 1] & 0xFF;
            if (q + 2 + l > p.length) break;
            byte[] v = new byte[l];
            System.arraycopy(p, q + 2, v, 0, l);
            if (!m.containsKey(code)) m.put(code, v);
            q += 2 + l;
        }
        return m;
    }

    // ------------------------------------------------------------------ ARP / routing

    private byte[] resolve(int ip) {
        return resolve(ip, cfg.arpTimeoutMs);
    }

    private byte[] resolve(int ip, int timeoutMs) {
        byte[] known = arpTable.get(ip);
        if (known != null) return known;
        for (int attempt = 0; attempt < 3 && !stopped; attempt++) {
            sender.send(arpFrame(1, BROADCAST, new byte[6], ip));
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (arpLock) {
                while (!stopped) {
                    known = arpTable.get(ip);
                    if (known != null) return known;
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    try {
                        arpLock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private byte[] nextHopMac(int dst) {
        return nextHopMac(dst, cfg.arpTimeoutMs);
    }

    private byte[] nextHopMac(int dst, int timeoutMs) {
        boolean local = ((dst ^ myIp) & mask) == 0;
        return resolve(local ? dst : gateway, timeoutMs);
    }

    private byte[] arpFrame(int op, byte[] dstMac, byte[] targetMac, int targetIp) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, 1); w16(o, ETH_IP); o.write(6); o.write(4); w16(o, op);
        o.write(mac, 0, 6);
        w32(o, myIp);
        o.write(targetMac, 0, 6);
        w32(o, targetIp);
        return eth(dstMac, ETH_ARP, o.toByteArray());
    }

    // ------------------------------------------------------------------ ICMP

    private PingResult ping(int dst, int count) {
        PingResult r = new PingResult();
        r.target = intToIp(dst);
        byte[] nh = nextHopMac(dst);
        double sum = 0;
        for (int i = 0; i < count && !stopped; i++) {
            r.sent++;
            if (nh == null) {
                sleep(cfg.pingTimeoutMs / 4);
                continue;
            }
            final int seq = ++icmpSeq & 0xFFFF;
            long t0 = System.nanoTime();
            sender.send(eth(nh, ETH_IP, ip(PROTO_ICMP, myIp, dst, 64, icmpEcho(seq))));
            IpPacket rep = await(pk -> pk.proto == PROTO_ICMP && pk.src == dst && (pk.p[pk.hl] & 0xFF) == 0
                    && u16(pk.p, pk.hl + 4) == icmpId && u16(pk.p, pk.hl + 6) == seq, cfg.pingTimeoutMs);
            if (rep != null) {
                double rtt = (rep.rxNanos - t0) / 1e6;
                r.received++;
                sum += rtt;
                r.minMs = r.minMs < 0 ? rtt : Math.min(r.minMs, rtt);
                r.maxMs = Math.max(r.maxMs, rtt);
                long rest = cfg.pingTimeoutMs / 4 - (long) rtt;
                if (rest > 0 && i < count - 1) sleep(rest);
            }
        }
        if (r.received > 0) r.avgMs = sum / r.received;
        return r;
    }

    private byte[] icmpEcho(int seq) {
        byte[] m = new byte[8 + 32];
        m[0] = 8;
        w16(m, 4, icmpId);
        w16(m, 6, seq);
        byte[] pad = "PortPerson traceroute/ping data!".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(pad, 0, m, 8, 32);
        w16(m, 2, checksum(m, 0, m.length));
        return m;
    }

    private List<Hop> traceroute(int dst) {
        List<Hop> hops = new ArrayList<>();
        byte[] nh = nextHopMac(dst);
        if (nh == null) {
            listener.onTraceDone(false, "Could not reach the next hop (no ARP reply from the gateway).");
            return hops;
        }
        int silent = 0;
        boolean reached = false;
        String note = "";
        for (int ttl = 1; ttl <= cfg.maxHops && !stopped; ttl++) {
            Hop hop = new Hop(ttl, cfg.probesPerHop);
            Map<Integer, Integer> seqToProbe = new HashMap<>();
            long[] sentAt = new long[cfg.probesPerHop];
            for (int k = 0; k < cfg.probesPerHop; k++) {
                int seq = ++icmpSeq & 0xFFFF;
                seqToProbe.put(seq, k);
                sentAt[k] = System.nanoTime();
                sender.send(eth(nh, ETH_IP, ip(PROTO_ICMP, myIp, dst, ttl, icmpEcho(seq))));
            }
            LinkedHashSet<String> addrs = new LinkedHashSet<>();
            int answered = 0;
            boolean hopReached = false;
            long deadline = System.currentTimeMillis() + cfg.hopTimeoutMs;
            while (answered < cfg.probesPerHop && !stopped) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                IpPacket pk = await(x -> x.proto == PROTO_ICMP, left);
                if (pk == null) break;
                int type = pk.p[pk.hl] & 0xFF, code = pk.p[pk.hl + 1] & 0xFF;
                Integer probe = null;
                if (type == 0 && pk.src == dst && u16(pk.p, pk.hl + 4) == icmpId) {
                    probe = seqToProbe.get(u16(pk.p, pk.hl + 6));
                    if (probe != null) hopReached = true;
                } else if (type == 11 || type == 3) {
                    int inner = pk.hl + 8;
                    if (pk.p.length >= inner + 20) {
                        int ihl = (pk.p[inner] & 0x0F) * 4;
                        int ic = inner + ihl;
                        if (pk.p.length >= ic + 8 && (pk.p[inner + 9] & 0xFF) == PROTO_ICMP
                                && i32(pk.p, inner + 16) == dst && (pk.p[ic] & 0xFF) == 8
                                && u16(pk.p, ic + 4) == icmpId) {
                            probe = seqToProbe.get(u16(pk.p, ic + 6));
                            if (probe != null && type == 3) {
                                hop.flag = unreachableFlag(code);
                                if (pk.src == dst && (code == 2 || code == 3)) hopReached = true;
                            }
                        }
                    }
                }
                if (probe == null || hop.rttMs[probe] != null) continue;
                hop.rttMs[probe] = (pk.rxNanos - sentAt[probe]) / 1e6;
                addrs.add(intToIp(pk.src));
                answered++;
            }
            hop.addresses.addAll(addrs);
            hops.add(hop);
            listener.onHop(hop);
            if (hopReached) {
                reached = true;
                break;
            }
            if (!hop.flag.isEmpty()) {
                note = "Stopped: " + hop.address() + " reported destination unreachable (" + hop.flag + ").";
                break;
            }
            silent = hop.silent() ? silent + 1 : 0;
            if (silent >= cfg.maxSilentHops) {
                note = "Stopped after " + silent + " hops in a row without a reply (probably a firewall dropping ICMP).";
                break;
            }
        }
        if (!reached && note.isEmpty() && !stopped) note = "Did not reach " + intToIp(dst) + " within " + cfg.maxHops + " hops.";
        if (reached) note = "Reached " + intToIp(dst) + " in " + hops.size() + " hops.";
        listener.onTraceDone(reached, note);
        return hops;
    }

    private static String unreachableFlag(int code) {
        switch (code) {
            case 0: return "!N";
            case 1: return "!H";
            case 2: return "!P";
            case 3: return "";   // port unreachable from the destination itself
            case 9:
            case 10:
            case 13: return "!X";
            default: return "!" + code;
        }
    }

    // ------------------------------------------------------------------ DNS

    private DnsResult resolveName(String name, List<String> servers) {
        DnsResult r = new DnsResult();
        r.name = name;
        if (servers.isEmpty()) {
            r.error = "DHCP gave no DNS servers";
            return r;
        }
        for (String s : servers) {
            for (int attempt = 0; attempt < 2 && !stopped; attempt++) {
                long t0 = System.nanoTime();
                DnsReply rep = dnsQuery(ipToInt(s), name, 1, cfg.dnsTimeoutMs);
                if (rep == null) {
                    r.error = "No reply from " + s;
                    continue;
                }
                r.server = s;
                r.ms = ms(t0, rep.rxNanos);
                if (!rep.answer.isEmpty()) {
                    r.address = rep.answer;
                    r.error = "";
                    return r;
                }
                r.error = rep.rcode == 3 ? "Name not found (NXDOMAIN) from " + s : "Server " + s + " returned no address (rcode " + rep.rcode + ")";
                break;
            }
        }
        return r;
    }

    private String reverseLookup(String ip, List<String> servers) {
        int a = ipToInt(ip);
        String q = (a & 0xFF) + "." + ((a >>> 8) & 0xFF) + "." + ((a >>> 16) & 0xFF) + "." + (a >>> 24) + ".in-addr.arpa";
        DnsReply rep = dnsQuery(ipToInt(servers.get(0)), q, 12, cfg.ptrTimeoutMs);
        return rep == null ? "" : rep.answer;
    }

    private static final class DnsReply {
        int rcode;
        String answer = "";
        long rxNanos;
    }

    private DnsReply dnsQuery(int server, String name, int qtype, int timeoutMs) {
        byte[] nh = nextHopMac(server);
        if (nh == null) return null;
        final int id = rnd.nextInt(0xFFFF);
        final int sport = 20000 + rnd.nextInt(40000);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w16(o, id); w16(o, 0x0100); w16(o, 1); w16(o, 0); w16(o, 0); w16(o, 0);
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
            o.write(Math.min(lb.length, 63));
            o.write(lb, 0, Math.min(lb.length, 63));
        }
        o.write(0);
        w16(o, qtype); w16(o, 1);
        sender.send(eth(nh, ETH_IP, ip(PROTO_UDP, myIp, server, 64, udp(sport, 53, o.toByteArray(), myIp, server))));
        IpPacket pk = await(x -> x.proto == PROTO_UDP && x.src == server && x.p.length >= x.hl + 8 + 12
                && u16(x.p, x.hl) == 53 && u16(x.p, x.hl + 2) == sport && u16(x.p, x.hl + 8) == id, timeoutMs);
        if (pk == null) return null;
        DnsReply r = new DnsReply();
        r.rxNanos = pk.rxNanos;
        int m0 = pk.hl + 8;
        byte[] msg = new byte[pk.p.length - m0];
        System.arraycopy(pk.p, m0, msg, 0, msg.length);
        r.rcode = msg[3] & 0x0F;
        int qd = u16(msg, 4), an = u16(msg, 6);
        int q = 12;
        for (int i = 0; i < qd && q > 0; i++) {
            q = skipName(msg, q);
            if (q > 0) q += 4;
        }
        for (int i = 0; i < an && q > 0 && q < msg.length; i++) {
            q = skipName(msg, q);
            if (q < 0 || q + 10 > msg.length) break;
            int type = u16(msg, q), rdlen = u16(msg, q + 8);
            int rd = q + 10;
            if (rd + rdlen > msg.length) break;
            if (type == qtype) {
                if (type == 1 && rdlen == 4) r.answer = intToIp(i32(msg, rd));
                else if (type == 12) r.answer = readName(msg, rd);
                if (!r.answer.isEmpty()) break;
            }
            q = rd + rdlen;
        }
        return r;
    }

    static int skipName(byte[] m, int p) {
        while (p >= 0 && p < m.length) {
            int l = m[p] & 0xFF;
            if (l == 0) return p + 1;
            if ((l & 0xC0) == 0xC0) return p + 2;
            p += l + 1;
        }
        return -1;
    }

    static String readName(byte[] m, int p) {
        StringBuilder sb = new StringBuilder();
        int jumps = 0;
        while (p >= 0 && p < m.length) {
            int l = m[p] & 0xFF;
            if (l == 0) break;
            if ((l & 0xC0) == 0xC0) {
                if (p + 1 >= m.length || ++jumps > 20) break;
                p = ((l & 0x3F) << 8) | (m[p + 1] & 0xFF);
                continue;
            }
            p++;
            if (p + l > m.length) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(m, p, l, StandardCharsets.US_ASCII));
            p += l;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ packet building

    private byte[] eth(byte[] dst, int type, byte[] payload) {
        byte[] f = new byte[Math.max(60, 14 + payload.length)];
        System.arraycopy(dst, 0, f, 0, 6);
        System.arraycopy(mac, 0, f, 6, 6);
        w16(f, 12, type);
        System.arraycopy(payload, 0, f, 14, payload.length);
        return f;
    }

    private byte[] ip(int proto, int src, int dst, int ttl, byte[] payload) {
        byte[] p = new byte[20 + payload.length];
        p[0] = 0x45;
        w16(p, 2, p.length);
        w16(p, 4, ipId++ & 0xFFFF);
        p[8] = (byte) ttl;
        p[9] = (byte) proto;
        w32(p, 12, src);
        w32(p, 16, dst);
        w16(p, 10, checksum(p, 0, 20));
        System.arraycopy(payload, 0, p, 20, payload.length);
        return p;
    }

    private static byte[] udp(int sport, int dport, byte[] data, int src, int dst) {
        byte[] u = new byte[8 + data.length];
        w16(u, 0, sport);
        w16(u, 2, dport);
        w16(u, 4, u.length);
        System.arraycopy(data, 0, u, 8, data.length);
        byte[] ph = new byte[12 + u.length + (u.length & 1)];
        w32(ph, 0, src);
        w32(ph, 4, dst);
        ph[9] = PROTO_UDP;
        w16(ph, 10, u.length);
        System.arraycopy(u, 0, ph, 12, u.length);
        int c = checksum(ph, 0, ph.length);
        w16(u, 6, c == 0 ? 0xFFFF : c);
        return u;
    }

    static int checksum(byte[] b, int off, int len) {
        long s = 0;
        for (int i = 0; i + 1 < len; i += 2) s += u16(b, off + i);
        if ((len & 1) != 0) s += (b[off + len - 1] & 0xFF) << 8;
        while ((s >> 16) != 0) s = (s & 0xFFFF) + (s >> 16);
        return (int) (~s & 0xFFFF);
    }

    // ------------------------------------------------------------------ waiting

    private interface Match {
        boolean test(IpPacket p);
    }

    /** Waits for a matching packet, discarding others. Returns null on timeout or stop. */
    private IpPacket await(Match m, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopped) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            IpPacket p;
            try {
                p = inbox.poll(Math.min(left, 200), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (p == null) continue;
            try {
                if (m.test(p)) return p;
            } catch (RuntimeException ignored) {
                // truncated packet
            }
        }
        return null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long ms(long startNanos, long endNanos) { return Math.max(0, (endNanos - startNanos) / 1_000_000); }

    static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }

    static int i32(byte[] b, int i) { return (u16(b, i) << 16) | u16(b, i + 2); }

    static long u32(byte[] b, int i) { return i32(b, i) & 0xFFFFFFFFL; }

    static void w16(byte[] b, int i, int v) { b[i] = (byte) (v >> 8); b[i + 1] = (byte) v; }

    static void w32(byte[] b, int i, int v) { w16(b, i, v >>> 16); w16(b, i + 2, v); }

    private static void w16(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }

    private static void w32(ByteArrayOutputStream o, int v) { w16(o, v >>> 16); w16(o, v); }

    private static byte[] be32(int v) { byte[] b = new byte[4]; w32(b, 0, v); return b; }

    private static boolean macEquals(byte[] b, int off, byte[] m) {
        for (int i = 0; i < 6; i++) if (b[off + i] != m[i]) return false;
        return true;
    }

    public static String intToIp(int a) {
        return (a >>> 24) + "." + ((a >>> 16) & 0xFF) + "." + ((a >>> 8) & 0xFF) + "." + (a & 0xFF);
    }

    public static int ipToInt(String s) {
        int v = ipToIntOrZero(s);
        if (v == 0 && !"0.0.0.0".equals(s)) throw new IllegalArgumentException("Not an IPv4 address: " + s);
        return v;
    }

    /** Parses dotted-quad IPv4, or returns 0 if the text is not one (e.g. a hostname). */
    public static int ipToIntOrZero(String s) {
        if (s == null) return 0;
        String[] parts = s.trim().split("\\.");
        if (parts.length != 4) return 0;
        int v = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) return 0;
            int n = Integer.parseInt(p);
            if (n > 255) return 0;
            v = (v << 8) | n;
        }
        return v;
    }
}
