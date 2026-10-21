package com.portfinder.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Passive analysis of every Ethernet frame the port test receives: spanning tree, DTP/VTP, 802.1X,
 * DHCP servers, gateway redundancy (HSRP/VRRP), routing hellos, IPv6 router advertisements, ARP
 * conflicts, devices that announce themselves, traffic counts and loop/storm signs. Also keeps a
 * bounded copy of the frames so they can be saved as a .pcap file.
 *
 * Only metadata is decoded; payloads of other devices' traffic are never inspected beyond the
 * protocol headers listed above. Pure Java (no Android), thread-safe: frames arrive on the capture
 * thread, the UI reads a {@link Report}.
 */
public final class FrameAnalyzer {

    // ------------------------------------------------------------------ result types

    public static final class Stp {
        public String mode = "";        // "STP", "RSTP", "MST", with "PVST+" when Cisco per-VLAN
        public int rootPriority = -1;
        public String rootMac = "";
        public long rootCost = -1;
        public int bridgePriority = -1;
        public String bridgeMac = "";
        public int portId = -1;
        public String role = "";        // RSTP port role of the sender's port
        public int pvstVlan = -1;       // VLAN from the PVST+ TLV
        public boolean tagged;          // BPDU arrived 802.1Q tagged
        public int helloSeconds = -1;
        public long count;
    }

    public static final class DhcpServer {
        public String serverId = "", sourceIp = "", mac = "", router = "", mask = "", offered = "";
        public long count;
    }

    public static final class Gateway {
        public String protocol = "";    // HSRPv1, HSRPv2, VRRPv2, VRRPv3
        public int group = -1;
        public String state = "";
        public int priority = -1;
        public String virtualIp = "", sourceIp = "", mac = "";
    }

    public static final class Router {
        public String protocol = "", id = "", detail = "", sourceIp = "";
    }

    public static final class Ipv6Router {
        public String sourceIp = "", mac = "";
        public final List<String> prefixes = new ArrayList<>();
        public int lifetime = -1;
        public boolean managed, other;
    }

    public static final class Device {
        public String ip = "", mac = "", name = "", source = "";
    }

    public static final class Talker {
        public String mac = "", ip = "";
        public long frames;
    }

    /** A consistent copy of everything seen so far, safe to use on any thread. */
    public static final class Report {
        public long frames, bytes, broadcasts, multicasts, durationMs;
        public final LinkedHashMap<String, Long> protocols = new LinkedHashMap<>();
        public final TreeMap<Integer, Long> taggedVlans = new TreeMap<>();
        public final List<Stp> stp = new ArrayList<>();
        public int topologyChanges;
        public long lastTopologyChangeMs = -1;
        /** When the first change was seen, in ms after the first frame (-1 = none). */
        public long firstTopologyChangeAfterMs = -1;
        public String dtpMode = "";     // "", "On (trunk)", "Off (access)", "Dynamic desirable", "Dynamic auto"
        public String dtpOperating = "";
        public String dtpDomain = "";
        public String vtpDomain = "";
        public int vtpVersion = -1;
        public boolean eapol, eapIdentityRequest;
        public final List<DhcpServer> dhcpServers = new ArrayList<>();
        public final List<Gateway> gateways = new ArrayList<>();
        public final List<Router> routers = new ArrayList<>();
        public final List<Ipv6Router> ipv6Routers = new ArrayList<>();
        public String igmpQuerier = "";
        public final Map<String, List<String>> arpConflicts = new LinkedHashMap<>();
        public final List<Device> devices = new ArrayList<>();
        public final List<Talker> topBroadcasters = new ArrayList<>();
        public long loopRepeats;        // highest count of one identical broadcast frame within a second
        public double broadcastRate, peakBroadcastRate;
        public int capturedFrames;
        public boolean captureTruncated;

        public double rate(long n) {
            return durationMs <= 0 ? 0 : n * 1000.0 / durationMs;
        }
    }

    // ------------------------------------------------------------------ state

    static final int MAX_PCAP_FRAMES = 20000;
    static final long MAX_PCAP_BYTES = 16L * 1024 * 1024;
    static final int LOOP_REPEAT_THRESHOLD = 25;

    private long startMs = -1, lastMs;
    private long frames, bytes, broadcasts, multicasts;
    private final LinkedHashMap<String, Long> protocols = new LinkedHashMap<>();
    private final TreeMap<Integer, Long> taggedVlans = new TreeMap<>();
    private final LinkedHashMap<String, Stp> stp = new LinkedHashMap<>();
    private int tcEvents;
    private long lastTcMs = -1, firstTcMs = -1;
    private String dtpMode = "", dtpOperating = "", dtpDomain = "", vtpDomain = "";
    private int vtpVersion = -1;
    private boolean eapol, eapIdentityRequest;
    private final LinkedHashMap<String, DhcpServer> dhcpServers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Gateway> gateways = new LinkedHashMap<>();
    private final LinkedHashMap<String, Router> routers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Ipv6Router> ipv6Routers = new LinkedHashMap<>();
    private String igmpQuerier = "";
    private final HashMap<String, LinkedHashSet<String>> arpIpToMacs = new HashMap<>();
    private final LinkedHashMap<String, Device> devices = new LinkedHashMap<>();
    private final HashMap<String, Talker> broadcasters = new HashMap<>();
    private final HashMap<String, String> macToIp = new HashMap<>();
    private long loopRepeats;
    private long secondStart = -1, secondBroadcasts, peakBroadcasts;
    private final HashMap<Integer, Integer> secondHashes = new HashMap<>();
    private String ownMac = "";

    private final ArrayDeque<long[]> pcapTimes = new ArrayDeque<>();
    private final ArrayDeque<byte[]> pcapFrames = new ArrayDeque<>();
    private long pcapBytes;
    private boolean pcapTruncated;

    public synchronized void reset() {
        startMs = -1;
        lastMs = 0;
        frames = bytes = broadcasts = multicasts = 0;
        protocols.clear();
        taggedVlans.clear();
        stp.clear();
        tcEvents = 0;
        lastTcMs = -1;
        firstTcMs = -1;
        dtpMode = dtpOperating = dtpDomain = vtpDomain = "";
        vtpVersion = -1;
        eapol = eapIdentityRequest = false;
        dhcpServers.clear();
        gateways.clear();
        routers.clear();
        ipv6Routers.clear();
        igmpQuerier = "";
        arpIpToMacs.clear();
        devices.clear();
        broadcasters.clear();
        macToIp.clear();
        loopRepeats = 0;
        secondStart = -1;
        secondBroadcasts = peakBroadcasts = 0;
        secondHashes.clear();
        pcapTimes.clear();
        pcapFrames.clear();
        pcapBytes = 0;
        pcapTruncated = false;
    }

    /** This phone's (or the adapter's) MAC, so its own frames aren't listed as another device. */
    public synchronized void setOwnMac(String mac) {
        ownMac = mac == null ? "" : mac.toLowerCase(Locale.US);
    }

    /** A frame this app sent (DHCP, ping...). Kept for the .pcap only; not counted. */
    public synchronized void recordSent(byte[] f, long nowMs) {
        if (f != null) keep(f, 0, f.length, nowMs);
    }

    // ------------------------------------------------------------------ decoding

    public synchronized void onFrame(byte[] b, int off, int len, long nowMs) {
        if (b == null || len < 14 || off < 0 || off + len > b.length) return;
        if (startMs < 0) startMs = nowMs;
        lastMs = Math.max(lastMs, nowMs);
        frames++;
        bytes += len;
        keep(b, off, len, nowMs);

        boolean group = (b[off] & 1) != 0;
        boolean bcast = isBroadcast(b, off);
        if (bcast) broadcasts++;
        else if (group) multicasts++;
        String src = mac(b, off + 6);

        if (bcast) noteBroadcast(b, off, len, nowMs, src);

        int type = u16(b, off + 12);
        int p = off + 14;
        int end = off + len;
        int vlan = -1;
        int guard = 0;
        while ((type == 0x8100 || type == 0x88A8 || type == 0x9100) && p + 4 <= end && guard++ < 2) {
            int tci = u16(b, p);
            if (vlan < 0) vlan = tci & 0x0FFF;
            type = u16(b, p + 2);
            p += 4;
        }
        if (vlan > 0) taggedVlans.merge(vlan, 1L, Long::sum);

        if (type <= 1500) {
            llc(b, off, p, Math.min(end, p + type), vlan);
            return;
        }
        switch (type) {
            case 0x88CC: count("LLDP"); break;
            case 0x888E: count("802.1X (EAPOL)"); eapol(b, p, end); break;
            case 0x8809: count("LACP / slow protocols"); break;
            case 0x0806: count("ARP"); arp(b, p, end); break;
            case 0x0800: ipv4(b, off, p, end); break;
            case 0x86DD: ipv6(b, off, p, end); break;
            default: count("Other");
        }
    }

    private void llc(byte[] b, int off, int p, int end, int vlan) {
        if (p + 3 > end) { count("Other"); return; }
        int dsap = b[p] & 0xFF, ssap = b[p + 1] & 0xFF;
        if (dsap == 0x42 && ssap == 0x42) {
            count("Spanning tree");
            bpdu(b, p + 3, end, false, vlan);
            return;
        }
        if (dsap == 0xAA && ssap == 0xAA && p + 8 <= end) {
            int oui = (b[p + 3] & 0xFF) << 16 | (b[p + 4] & 0xFF) << 8 | (b[p + 5] & 0xFF);
            int pid = u16(b, p + 6);
            int d = p + 8;
            if (oui == 0x00000C) {
                switch (pid) {
                    case 0x2000: count("CDP"); return;
                    case 0x2003: count("VTP"); vtp(b, d, end); return;
                    case 0x2004: count("DTP"); dtp(b, d, end); return;
                    case 0x010B: count("Spanning tree"); bpdu(b, d, end, true, vlan); return;
                    case 0x0104: count("PAgP"); return;
                    default: count("Cisco other"); return;
                }
            }
        }
        count("Other");
    }

    private void bpdu(byte[] b, int p, int end, boolean pvst, int vlan) {
        if (p + 4 > end) return;
        if (u16(b, p) != 0) return;
        int version = b[p + 2] & 0xFF;
        int bpduType = b[p + 3] & 0xFF;
        if (bpduType == 0x80) { // TCN
            topologyChange(lastMs);
            return;
        }
        if (p + 35 > end) return;
        int flags = b[p + 4] & 0xFF;
        if ((flags & 0x01) != 0) topologyChange(lastMs);
        int rootPrio = u16(b, p + 5);
        String rootMac = mac(b, p + 7);
        long cost = u32(b, p + 13);
        int bridgePrio = u16(b, p + 17);
        String bridgeMac = mac(b, p + 19);
        int portId = u16(b, p + 25);
        int hello = u16(b, p + 31) / 256; // hello time (1/256 s); max age is at +29, forward delay at +33
        int pvstVlan = -1;
        if (pvst) {
            // PVST+ appends a TLV (type 0, length 2) carrying the VLAN
            for (int q = p + 35; q + 6 <= end && q <= p + 40; q++) {
                if (u16(b, q) == 0 && u16(b, q + 2) == 2) { pvstVlan = u16(b, q + 4); break; }
            }
        }
        String key = rootMac + "/" + bridgeMac + "/" + portId + "/" + pvstVlan + "/" + vlan;
        Stp s = stp.get(key);
        if (s == null) {
            if (stp.size() >= 16) return;
            s = new Stp();
            stp.put(key, s);
        }
        String m = version >= 3 ? "MST" : version == 2 ? "RSTP" : "STP";
        s.mode = pvst ? (version == 2 ? "Rapid PVST+" : "PVST+") : m;
        s.rootPriority = rootPrio;
        s.rootMac = rootMac;
        s.rootCost = cost;
        s.bridgePriority = bridgePrio;
        s.bridgeMac = bridgeMac;
        s.portId = portId;
        s.helloSeconds = hello;
        s.pvstVlan = pvstVlan;
        s.tagged = vlan > 0;
        if (version >= 2) {
            int role = (flags >> 2) & 0x03;
            s.role = role == 3 ? "Designated" : role == 2 ? "Root" : role == 1 ? "Alternate/backup" : "";
        }
        s.count++;
    }

    private void topologyChange(long now) {
        // Switches set the TC flag on every BPDU for a while after one change, so group them
        if (lastTcMs < 0 || now - lastTcMs > 6000) tcEvents++;
        if (firstTcMs < 0) firstTcMs = now;
        lastTcMs = now;
    }

    private void dtp(byte[] b, int p, int end) {
        if (p + 1 > end) return;
        int q = p + 1; // version byte
        while (q + 4 <= end) {
            int t = u16(b, q), l = u16(b, q + 2);
            if (l < 4 || q + l > end) break;
            int v = q + 4, vl = l - 4;
            if (t == 0x0001 && vl > 0) dtpDomain = ascii(b, v, vl);
            if (t == 0x0002 && vl >= 1) {
                int st = b[v] & 0xFF;
                int admin = st & 0x07;
                dtpMode = admin == 1 ? "On (trunk)" : admin == 2 ? "Off (access)" : admin == 3 ? "Dynamic desirable"
                        : admin == 4 ? "Dynamic auto" : String.format(Locale.US, "Unknown (0x%02x)", st);
                dtpOperating = (st & 0x80) != 0 ? "Trunk" : "Access";
            }
            q += l;
        }
    }

    private void vtp(byte[] b, int p, int end) {
        if (p + 4 > end) return;
        vtpVersion = b[p] & 0xFF;
        int dl = b[p + 3] & 0xFF;
        if (dl > 0 && p + 4 + dl <= end) vtpDomain = ascii(b, p + 4, Math.min(dl, 32));
    }

    private void eapol(byte[] b, int p, int end) {
        eapol = true;
        if (p + 9 > end) return;
        int type = b[p + 1] & 0xFF;
        if (type == 0) {
            int code = b[p + 4] & 0xFF;
            int eapType = b[p + 8] & 0xFF;
            if (code == 1 && eapType == 1) eapIdentityRequest = true;
        }
    }

    private void arp(byte[] b, int p, int end) {
        if (p + 28 > end) return;
        if (u16(b, p) != 1 || u16(b, p + 2) != 0x0800) return;
        String sha = mac(b, p + 8);
        String spa = ip(b, p + 14);
        if (spa.equals("0.0.0.0")) return; // probe
        macToIp.put(sha, spa);
        LinkedHashSet<String> macs = arpIpToMacs.get(spa);
        if (macs == null) {
            if (arpIpToMacs.size() >= 4096) return;
            macs = new LinkedHashSet<>();
            arpIpToMacs.put(spa, macs);
        }
        if (macs.size() < 8) macs.add(sha);
        Talker t = broadcasters.get(sha);
        if (t != null && t.ip.isEmpty()) t.ip = spa;
    }

    private void ipv4(byte[] b, int off, int p, int end) {
        if (p + 20 > end || (b[p] >> 4 & 0x0F) != 4) { count("IPv4 other"); return; }
        int ihl = (b[p] & 0x0F) * 4;
        int total = u16(b, p + 2);
        int iend = Math.min(end, p + Math.max(total, ihl));
        int proto = b[p + 9] & 0xFF;
        String src = ip(b, p + 12);
        String dst = ip(b, p + 16);
        int l4 = p + ihl;
        String srcMac = mac(b, off + 6);
        if (!src.equals("0.0.0.0")) macToIp.putIfAbsent(srcMac, src);
        switch (proto) {
            case 2:
                count("IGMP");
                if (l4 < iend && (b[l4] & 0xFF) == 0x11) igmpQuerier = src;
                return;
            case 89: {
                count("OSPF");
                if (l4 + 16 <= iend) {
                    String rid = ip(b, l4 + 4), area = ip(b, l4 + 8);
                    router("OSPF", rid, "area " + area, src);
                }
                return;
            }
            case 88: {
                count("EIGRP");
                if (l4 + 20 <= iend) router("EIGRP", "AS " + u16(b, l4 + 18), "", src);
                return;
            }
            case 112: {
                count("VRRP");
                if (l4 + 8 <= iend) vrrp(b, l4, iend, src, srcMac);
                return;
            }
            case 17: break;
            case 1: count("ICMP"); return;
            case 6: count("TCP"); return;
            default: count("IPv4 other"); return;
        }
        if (l4 + 8 > iend) { count("UDP other"); return; }
        int sp = u16(b, l4), dp = u16(b, l4 + 2);
        int u = l4 + 8;
        if (sp == 67 || dp == 67 || sp == 68 || dp == 68) { count("DHCP"); dhcp(b, u, iend, src, srcMac); return; }
        if (sp == 1985 || dp == 1985) { count("HSRP"); hsrp(b, u, iend, src, srcMac, dst.equals("224.0.0.102")); return; }
        if (sp == 520 || dp == 520) { count("RIP"); router("RIP", src, "", src); return; }
        if (sp == 5353 || dp == 5353) { count("mDNS"); mdns(b, u, iend, srcMac); return; }
        if (sp == 137 || dp == 137) { count("NetBIOS"); netbios(b, u, iend, src, srcMac); return; }
        if (sp == 1900 || dp == 1900) { count("SSDP"); return; }
        if (sp == 5355 || dp == 5355) { count("LLMNR"); return; }
        if (sp == 53 || dp == 53) { count("DNS"); return; }
        if (sp == 123 || dp == 123) { count("NTP"); return; }
        count("UDP other");
    }

    private void dhcp(byte[] b, int u, int end, String srcIp, String srcMac) {
        if (u + 240 > end) return;
        int op = b[u] & 0xFF;
        if (u32(b, u + 236) != 0x63825363L) return;
        String yiaddr = ip(b, u + 16);
        String chaddr = mac(b, u + 28);
        String ciaddr = ip(b, u + 12);
        int type = -1;
        String serverId = "", router = "", mask = "", hostname = "", requested = "";
        int q = u + 240;
        while (q < end) {
            int code = b[q] & 0xFF;
            if (code == 255) break;
            if (code == 0) { q++; continue; }
            if (q + 2 > end) break;
            int l = b[q + 1] & 0xFF;
            int v = q + 2;
            if (v + l > end) break;
            if (code == 53 && l >= 1) type = b[v] & 0xFF;
            if (code == 54 && l >= 4) serverId = ip(b, v);
            if (code == 3 && l >= 4) router = ip(b, v);
            if (code == 1 && l >= 4) mask = ip(b, v);
            if (code == 12 && l > 0) hostname = ascii(b, v, l);
            if (code == 50 && l >= 4) requested = ip(b, v);
            q = v + l;
        }
        if (op == 2 && (type == 2 || type == 5)) {
            String key = !serverId.isEmpty() ? serverId : srcIp;
            DhcpServer s = dhcpServers.get(key);
            if (s == null) {
                if (dhcpServers.size() >= 8) return;
                s = new DhcpServer();
                dhcpServers.put(key, s);
            }
            s.serverId = key;
            s.sourceIp = srcIp;
            s.mac = srcMac;
            if (!router.isEmpty()) s.router = router;
            if (!mask.isEmpty()) s.mask = mask;
            if (!yiaddr.equals("0.0.0.0")) s.offered = yiaddr;
            s.count++;
        } else if (op == 1 && !hostname.isEmpty()) {
            String ipAddr = !requested.isEmpty() ? requested : !ciaddr.equals("0.0.0.0") ? ciaddr : "";
            device(ipAddr, chaddr, hostname, "DHCP");
        }
    }

    private void hsrp(byte[] b, int u, int end, String src, String srcMac, boolean v2) {
        if (!v2) {
            if (u + 20 > end) return;
            int stateCode = b[u + 2] & 0xFF;
            String state = stateCode == 16 ? "Active" : stateCode == 8 ? "Standby" : stateCode == 4 ? "Speak"
                    : stateCode == 2 ? "Listen" : stateCode == 1 ? "Learn" : "Initial";
            gateway("HSRPv1", b[u + 6] & 0xFF, state, b[u + 5] & 0xFF, ip(b, u + 16), src, srcMac);
            return;
        }
        int q = u;
        while (q + 2 <= end) {
            int t = b[q] & 0xFF, l = b[q + 1] & 0xFF;
            int v = q + 2;
            if (v + l > end) break;
            if (t == 1 && l >= 28) {
                int stateCode = b[v + 2] & 0xFF;
                String state = stateCode == 6 ? "Active" : stateCode == 5 ? "Standby" : stateCode == 4 ? "Speak"
                        : stateCode == 3 ? "Listen" : stateCode == 2 ? "Learn" : "Initial";
                int ipVer = b[v + 3] & 0xFF;
                String vip = ipVer == 4 ? ip(b, v + 24) : "IPv6";
                gateway("HSRPv2", u16(b, v + 4), state, (int) u32(b, v + 12), vip, src, srcMac);
            }
            q = v + l;
        }
    }

    private void vrrp(byte[] b, int p, int end, String src, String srcMac) {
        int version = (b[p] >> 4) & 0x0F;
        int vrid = b[p + 1] & 0xFF;
        int prio = b[p + 2] & 0xFF;
        int n = b[p + 3] & 0xFF;
        String vip = n > 0 && p + 12 <= end ? ip(b, p + 8) : "";
        gateway("VRRPv" + version, vrid, prio == 255 ? "Master (owner)" : "Master", prio, vip, src, srcMac);
    }

    private void gateway(String proto, int group, String state, int prio, String vip, String src, String mac) {
        String key = proto + "/" + group + "/" + src;
        Gateway g = gateways.get(key);
        if (g == null) {
            if (gateways.size() >= 16) return;
            g = new Gateway();
            gateways.put(key, g);
        }
        g.protocol = proto;
        g.group = group;
        g.state = state;
        g.priority = prio;
        g.virtualIp = vip;
        g.sourceIp = src;
        g.mac = mac;
    }

    private void router(String proto, String id, String detail, String src) {
        String key = proto + "/" + id + "/" + src;
        if (routers.containsKey(key) || routers.size() >= 16) return;
        Router r = new Router();
        r.protocol = proto;
        r.id = id;
        r.detail = detail;
        r.sourceIp = src;
        routers.put(key, r);
    }

    private void ipv6(byte[] b, int off, int p, int end) {
        if (p + 40 > end) { count("IPv6 other"); return; }
        int next = b[p + 6] & 0xFF;
        String src = ip6(b, p + 8);
        int l4 = p + 40;
        if (next == 58 && l4 + 4 <= end) {
            int t = b[l4] & 0xFF;
            if (t >= 133 && t <= 137) {
                count("IPv6 neighbor discovery");
                if (t == 134) ra(b, l4, end, src, mac(b, off + 6));
                return;
            }
            if (t == 130 || t == 131 || t == 132 || t == 143) { count("IPv6 multicast (MLD)"); return; }
            count("ICMPv6");
            return;
        }
        if (next == 17 && l4 + 8 <= end) {
            int dp = u16(b, l4 + 2);
            if (dp == 5353) { count("mDNS"); mdns(b, l4 + 8, end, mac(b, off + 6)); return; }
            if (dp == 547 || dp == 546) { count("DHCPv6"); return; }
            if (dp == 1900) { count("SSDP"); return; }
            if (dp == 5355) { count("LLMNR"); return; }
        }
        count("IPv6 other");
    }

    private void ra(byte[] b, int p, int end, String src, String mac) {
        if (p + 16 > end) return;
        Ipv6Router r = ipv6Routers.get(src);
        if (r == null) {
            if (ipv6Routers.size() >= 8) return;
            r = new Ipv6Router();
            ipv6Routers.put(src, r);
        }
        r.sourceIp = src;
        r.mac = mac;
        int flags = b[p + 5] & 0xFF;
        r.managed = (flags & 0x80) != 0;
        r.other = (flags & 0x40) != 0;
        r.lifetime = u16(b, p + 6);
        int q = p + 16;
        while (q + 2 <= end) {
            int t = b[q] & 0xFF, l = (b[q + 1] & 0xFF) * 8;
            if (l == 0 || q + l > end) break;
            if (t == 3 && l >= 32) {
                String pfx = ip6(b, q + 16) + "/" + (b[q + 2] & 0xFF);
                if (!r.prefixes.contains(pfx) && r.prefixes.size() < 8) r.prefixes.add(pfx);
            }
            q += l;
        }
    }

    /** mDNS responses: A records name each device ("HP-LaserJet-4F.local" → 10.20.30.44). */
    private void mdns(byte[] b, int p, int end, String srcMac) {
        if (p + 12 > end) return;
        if ((b[p + 2] & 0x80) == 0) return; // queries don't name the sender
        int qd = u16(b, p + 4), an = u16(b, p + 6), ns = u16(b, p + 8), ar = u16(b, p + 10);
        int[] pos = {p + 12};
        for (int i = 0; i < qd && i < 16; i++) {
            if (dnsName(b, p, end, pos, 0) == null) return;
            pos[0] += 4;
        }
        int records = Math.min(an + ns + ar, 64);
        for (int i = 0; i < records; i++) {
            String name = dnsName(b, p, end, pos, 0);
            if (name == null || pos[0] + 10 > end) return;
            int type = u16(b, pos[0]);
            int rdlen = u16(b, pos[0] + 8);
            int rd = pos[0] + 10;
            if (rd + rdlen > end) return;
            if (type == 1 && rdlen == 4) {
                String host = name.endsWith(".local") ? name.substring(0, name.length() - 6) : name;
                device(ip(b, rd), srcMac, host, "mDNS");
            }
            pos[0] = rd + rdlen;
        }
    }

    /** NetBIOS name registrations: a PC announcing its name and address. */
    private void netbios(byte[] b, int p, int end, String srcIp, String srcMac) {
        if (p + 50 > end) return;
        int opcode = (b[p + 2] >> 3) & 0x0F;
        if (opcode != 5 && opcode != 8) return; // registration / refresh
        if ((b[p + 12] & 0xFF) != 0x20) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            int hi = (b[p + 13 + 2 * i] & 0xFF) - 'A', lo = (b[p + 14 + 2 * i] & 0xFF) - 'A';
            int c = (hi << 4) | lo;
            if (c < 32 || c > 126) break;
            sb.append((char) c);
        }
        String name = sb.toString().trim();
        int suffix = ((b[p + 43] & 0xFF) - 'A') << 4 | ((b[p + 44] & 0xFF) - 'A');
        if (name.isEmpty() || suffix != 0x00) return; // workstation name only
        String addr = p + 68 <= end ? ip(b, p + 64) : srcIp;
        device(addr.equals("0.0.0.0") ? srcIp : addr, srcMac, name, "NetBIOS");
    }

    private void device(String ipAddr, String mac, String name, String source) {
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || mac.equals(ownMac)) return;
        String key = !mac.isEmpty() ? mac : ipAddr;
        Device d = devices.get(key);
        if (d == null) {
            if (devices.size() >= 256) return;
            d = new Device();
            devices.put(key, d);
        }
        if (!ipAddr.isEmpty() && !ipAddr.equals("0.0.0.0")) d.ip = ipAddr;
        d.mac = mac;
        if (d.name.isEmpty() || source.equals("mDNS")) d.name = name;
        d.source = source;
    }

    private void noteBroadcast(byte[] b, int off, int len, long now, String src) {
        if (secondStart < 0 || now - secondStart >= 1000) {
            if (secondStart >= 0) peakBroadcasts = Math.max(peakBroadcasts, secondBroadcasts);
            secondStart = now;
            secondBroadcasts = 0;
            secondHashes.clear();
        }
        secondBroadcasts++;
        int h = len;
        for (int i = 0; i < len; i++) h = 31 * h + b[off + i];
        if (secondHashes.size() < 20000) {
            int c = secondHashes.merge(h, 1, Integer::sum);
            if (c > loopRepeats) loopRepeats = c;
        }
        Talker t = broadcasters.get(src);
        if (t == null) {
            if (broadcasters.size() >= 4096) return;
            t = new Talker();
            t.mac = src;
            broadcasters.put(src, t);
        }
        t.frames++;
    }

    private void count(String proto) {
        protocols.merge(proto, 1L, Long::sum);
    }

    private void keep(byte[] b, int off, int len, long nowMs) {
        if (pcapFrames.size() >= MAX_PCAP_FRAMES || pcapBytes + len > MAX_PCAP_BYTES) {
            pcapTruncated = true;
            byte[] old = pcapFrames.pollFirst();
            pcapTimes.pollFirst();
            if (old != null) pcapBytes -= old.length;
        }
        byte[] copy = new byte[len];
        System.arraycopy(b, off, copy, 0, len);
        pcapFrames.addLast(copy);
        pcapTimes.addLast(new long[]{nowMs});
        pcapBytes += len;
    }

    // ------------------------------------------------------------------ output

    public synchronized Report report() {
        Report r = new Report();
        r.frames = frames;
        r.bytes = bytes;
        r.broadcasts = broadcasts;
        r.multicasts = multicasts;
        r.durationMs = startMs < 0 ? 0 : Math.max(1, lastMs - startMs);
        List<Map.Entry<String, Long>> ps = new ArrayList<>(protocols.entrySet());
        ps.sort((a, c) -> Long.compare(c.getValue(), a.getValue()));
        for (Map.Entry<String, Long> e : ps) r.protocols.put(e.getKey(), e.getValue());
        r.taggedVlans.putAll(taggedVlans);
        r.stp.addAll(stp.values());
        r.topologyChanges = tcEvents;
        r.lastTopologyChangeMs = lastTcMs;
        r.firstTopologyChangeAfterMs = firstTcMs < 0 || startMs < 0 ? -1 : firstTcMs - startMs;
        r.dtpMode = dtpMode;
        r.dtpOperating = dtpOperating;
        r.dtpDomain = dtpDomain;
        r.vtpDomain = vtpDomain;
        r.vtpVersion = vtpVersion;
        r.eapol = eapol;
        r.eapIdentityRequest = eapIdentityRequest;
        r.dhcpServers.addAll(dhcpServers.values());
        r.gateways.addAll(gateways.values());
        r.routers.addAll(routers.values());
        r.ipv6Routers.addAll(ipv6Routers.values());
        r.igmpQuerier = igmpQuerier;
        for (Map.Entry<String, LinkedHashSet<String>> e : arpIpToMacs.entrySet()) {
            Set<String> macs = e.getValue();
            if (macs.size() > 1 && !allVirtual(macs)) r.arpConflicts.put(e.getKey(), new ArrayList<>(macs));
        }
        for (Device d : devices.values()) {
            if (d.ip.isEmpty()) d.ip = macToIp.getOrDefault(d.mac, "");
            r.devices.add(d);
        }
        r.devices.sort((a, c) -> a.name.compareToIgnoreCase(c.name));
        List<Talker> ts = new ArrayList<>(broadcasters.values());
        ts.sort((a, c) -> Long.compare(c.frames, a.frames));
        for (int i = 0; i < ts.size() && i < 5; i++) {
            Talker t = ts.get(i);
            if (t.ip.isEmpty()) t.ip = macToIp.getOrDefault(t.mac, "");
            r.topBroadcasters.add(t);
        }
        r.loopRepeats = loopRepeats;
        r.broadcastRate = r.rate(broadcasts);
        r.peakBroadcastRate = Math.max(peakBroadcasts, secondBroadcasts);
        r.capturedFrames = pcapFrames.size();
        r.captureTruncated = pcapTruncated;
        return r;
    }

    /** HSRP/VRRP virtual MACs answer for the virtual IP; the routers' real MACs aren't a conflict. */
    private static boolean allVirtual(Set<String> macs) {
        int real = 0;
        for (String m : macs) {
            if (!(m.startsWith("00:00:0c:07:ac") || m.startsWith("00:00:0c:9f:f") || m.startsWith("00:00:5e:00:01")
                    || m.startsWith("00:07:b4:00"))) real++;
        }
        return real <= 1;
    }

    /** Writes the kept frames as a classic libpcap file (Ethernet link type), readable by Wireshark. */
    public synchronized void writePcap(OutputStream out) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        le32(h, 0xA1B2C3D4L);
        le16(h, 2);
        le16(h, 4);
        le32(h, 0);
        le32(h, 0);
        le32(h, 65535);
        le32(h, 1);
        out.write(h.toByteArray());
        java.util.Iterator<long[]> ti = pcapTimes.iterator();
        for (byte[] f : pcapFrames) {
            long ms = ti.next()[0];
            ByteArrayOutputStream rec = new ByteArrayOutputStream(16);
            le32(rec, ms / 1000);
            le32(rec, (ms % 1000) * 1000);
            le32(rec, f.length);
            le32(rec, f.length);
            out.write(rec.toByteArray());
            out.write(f);
        }
        out.flush();
    }

    // ------------------------------------------------------------------ helpers

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF));
        o.write((int) ((v >> 24) & 0xFF));
    }

    static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    static boolean isBroadcast(byte[] b, int off) {
        for (int i = 0; i < 6; i++) if ((b[off + i] & 0xFF) != 0xFF) return false;
        return true;
    }

    static String mac(byte[] b, int i) {
        return String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
                b[i] & 0xFF, b[i + 1] & 0xFF, b[i + 2] & 0xFF, b[i + 3] & 0xFF, b[i + 4] & 0xFF, b[i + 5] & 0xFF);
    }

    static String ip(byte[] b, int i) {
        return (b[i] & 0xFF) + "." + (b[i + 1] & 0xFF) + "." + (b[i + 2] & 0xFF) + "." + (b[i + 3] & 0xFF);
    }

    static String ip6(byte[] b, int i) {
        StringBuilder sb = new StringBuilder();
        int[] w = new int[8];
        for (int k = 0; k < 8; k++) w[k] = u16(b, i + 2 * k);
        // compress the longest run of zero words
        int bestStart = -1, bestLen = 0;
        for (int k = 0; k < 8; ) {
            if (w[k] != 0) { k++; continue; }
            int s = k;
            while (k < 8 && w[k] == 0) k++;
            if (k - s > bestLen && k - s > 1) { bestStart = s; bestLen = k - s; }
        }
        for (int k = 0; k < 8; k++) {
            if (k == bestStart) {
                sb.append(k == 0 ? "::" : ":");
                k += bestLen - 1;
                continue;
            }
            sb.append(Integer.toHexString(w[k]));
            if (k < 7) sb.append(':');
        }
        return sb.toString();
    }

    static String ascii(byte[] b, int i, int len) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < len && i + k < b.length; k++) {
            int c = b[i + k] & 0xFF;
            if (c == 0) break;
            sb.append(c >= 32 && c < 127 ? (char) c : '?');
        }
        return sb.toString().trim();
    }

    /** Reads a DNS name with compression; pos[0] advances past it. Returns null on malformed input. */
    static String dnsName(byte[] b, int msg, int end, int[] pos, int depth) {
        StringBuilder sb = new StringBuilder();
        int q = pos[0];
        boolean jumped = false;
        int hops = 0;
        while (true) {
            if (q >= end || hops++ > 64) return null;
            int l = b[q] & 0xFF;
            if (l == 0) {
                if (!jumped) pos[0] = q + 1;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (q + 1 >= end) return null;
                int ptr = msg + (((l & 0x3F) << 8) | (b[q + 1] & 0xFF));
                if (!jumped) pos[0] = q + 2;
                jumped = true;
                if (ptr >= end || ptr == q) return null;
                q = ptr;
                continue;
            }
            if (q + 1 + l > end) return null;
            if (sb.length() > 0) sb.append('.');
            for (int k = 0; k < l; k++) {
                int c = b[q + 1 + k] & 0xFF;
                sb.append(c >= 32 && c < 127 ? (char) c : '?');
            }
            q += 1 + l;
        }
        return sb.toString();
    }
}
