package com.portfinder.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decodes Cisco Discovery Protocol (CDP v1/v2) and IEEE 802.1AB LLDP frames from raw Ethernet bytes.
 * Pure Java so it can be unit-tested off-device. Every read is bounds-checked; malformed frames
 * return null or partial results rather than throwing.
 */
public final class DiscoveryParser {

    private DiscoveryParser() {}

    private static final int[] CDP_DST = {0x01, 0x00, 0x0c, 0xcc, 0xcc, 0xcc};
    public static final int ETHERTYPE_LLDP = 0x88CC;

    /** Returns a decoded neighbor, or null if the frame is not CDP/LLDP (or is unreadable). */
    public static NeighborInfo parse(byte[] f, int off, int len) {
        try {
            return parseUnsafe(f, off, len);
        } catch (RuntimeException e) {
            return null; // never let a weird frame crash the capture thread
        }
    }

    private static NeighborInfo parseUnsafe(byte[] f, int off, int len) {
        if (f == null || len < 14 || off < 0 || off + len > f.length) return null;
        final int end = off + len;
        final String src = mac(f, off + 6);
        int p = off + 12;
        String tag = "";
        int type = u16(f, p);
        if (type == 0x8100 || type == 0x88A8) {
            if (p + 6 > end) return null;
            tag = String.valueOf(u16(f, p + 2) & 0x0FFF);
            p += 4;
            type = u16(f, p);
        }
        if (type == ETHERTYPE_LLDP) {
            NeighborInfo n = parseLldp(f, p + 2, end, src);
            if (n != null) n.frameVlanTag = tag;
            return n;
        }
        // CDP: 802.3 length field + LLC/SNAP AA-AA-03 00-00-0C 20-00, sent to 01:00:0c:cc:cc:cc
        if (type <= 1500 && dstIs(f, off, CDP_DST) && p + 10 <= end
                && b(f, p + 2) == 0xAA && b(f, p + 3) == 0xAA && b(f, p + 4) == 0x03
                && b(f, p + 5) == 0x00 && b(f, p + 6) == 0x00 && b(f, p + 7) == 0x0C
                && u16(f, p + 8) == 0x2000) {
            int cdpEnd = Math.min(end, p + 2 + type);
            NeighborInfo n = parseCdp(f, p + 10, cdpEnd, src);
            if (n != null) n.frameVlanTag = tag;
            return n;
        }
        return null;
    }

    // ---------------------------------------------------------------- CDP

    private static NeighborInfo parseCdp(byte[] f, int p, int end, String src) {
        if (p + 4 > end) return null;
        NeighborInfo n = new NeighborInfo(NeighborInfo.CDP, src);
        int version = b(f, p);
        p += 4; // version, ttl, checksum
        while (p + 4 <= end) {
            int t = u16(f, p);
            int l = u16(f, p + 2);
            if (l < 4 || p + l > end) break;
            int v = p + 4, vl = l - 4;
            switch (t) {
                case 0x0001:
                    n.systemName = str(f, v, vl);
                    n.add("Device ID", n.systemName);
                    break;
                case 0x0002: {
                    List<String> a = cdpAddresses(f, v, vl);
                    for (String s : a) n.add("IP address", s);
                    if (n.mgmtAddress.isEmpty() && !a.isEmpty()) n.mgmtAddress = a.get(0);
                    break;
                }
                case 0x0003:
                    n.portId = str(f, v, vl);
                    n.add("Port ID", n.portId);
                    break;
                case 0x0004:
                    if (vl >= 4) {
                        n.capabilities = cdpCaps(u32(f, v));
                        n.add("Capabilities", n.capabilities);
                    }
                    break;
                case 0x0005:
                    n.add("Software", oneLine(str(f, v, vl), 300));
                    break;
                case 0x0006:
                    n.platform = str(f, v, vl);
                    n.add("Platform", n.platform);
                    break;
                case 0x0009:
                    n.add("VTP domain", str(f, v, vl));
                    break;
                case 0x000A:
                    if (vl >= 2) {
                        n.nativeVlan = String.valueOf(u16(f, v));
                        n.add("Native VLAN", n.nativeVlan);
                    }
                    break;
                case 0x000B:
                    if (vl >= 1) n.add("Duplex", b(f, v) == 1 ? "Full" : "Half");
                    break;
                case 0x000E: // Appliance (voice) VLAN: repeated {appliance id(1), vlan(2)}
                    for (int q = v; q + 3 <= v + vl; q += 3) {
                        String vv = String.valueOf(u16(f, q + 1));
                        if (n.voiceVlan.isEmpty()) n.voiceVlan = vv;
                        n.add("Voice VLAN", vv);
                    }
                    break;
                case 0x0011:
                    if (vl >= 4) n.add("MTU", String.valueOf(u32(f, v)));
                    break;
                case 0x0014:
                    n.add("System name", str(f, v, vl));
                    break;
                case 0x0016: {
                    List<String> a = cdpAddresses(f, v, vl);
                    for (String s : a) n.add("Management address", s);
                    if (!a.isEmpty()) n.mgmtAddress = a.get(0);
                    break;
                }
                case 0x0017:
                    n.add("Location", str(f, v, vl));
                    break;
                case 0x001A: // Power available: request id(2), mgmt id(2), available mW(4), max mW(4)
                    if (vl >= 8) n.add("PoE available", watts(u32(f, v + 4)));
                    break;
                default:
                    break;
            }
            p += l;
        }
        n.add("CDP version", String.valueOf(version));
        return n;
    }

    private static List<String> cdpAddresses(byte[] f, int v, int vl) {
        List<String> out = new ArrayList<>();
        int end = v + vl;
        if (vl < 4) return out;
        long count = u32(f, v);
        int q = v + 4;
        for (long i = 0; i < count && i < 16 && q + 2 <= end; i++) {
            int ptype = b(f, q);
            int plen = b(f, q + 1);
            if (q + 2 + plen + 2 > end) break;
            int alen = u16(f, q + 2 + plen);
            int a = q + 4 + plen;
            if (a + alen > end) break;
            if (ptype == 1 && plen == 1 && b(f, q + 2) == 0xCC && alen == 4) {
                out.add(ipv4(f, a));
            } else if (alen == 16) {
                out.add(ipv6(f, a));
            }
            q = a + alen;
        }
        return out;
    }

    private static String cdpCaps(long c) {
        List<String> s = new ArrayList<>();
        if ((c & 0x01) != 0) s.add("Router");
        if ((c & 0x02) != 0) s.add("Trans-Bridge");
        if ((c & 0x04) != 0) s.add("Source-Route-Bridge");
        if ((c & 0x08) != 0) s.add("Switch");
        if ((c & 0x10) != 0) s.add("Host");
        if ((c & 0x20) != 0) s.add("IGMP");
        if ((c & 0x40) != 0) s.add("Repeater");
        if ((c & 0x80) != 0) s.add("Phone");
        if ((c & 0x100) != 0) s.add("Remotely-Managed");
        if ((c & 0x200) != 0) s.add("CVTA");
        if ((c & 0x400) != 0) s.add("Two-port MAC Relay");
        return String.join(", ", s);
    }

    // ---------------------------------------------------------------- LLDP

    private static NeighborInfo parseLldp(byte[] f, int p, int end, String src) {
        NeighborInfo n = new NeighborInfo(NeighborInfo.LLDP, src);
        boolean sawChassis = false;
        while (p + 2 <= end) {
            int hdr = u16(f, p);
            int t = hdr >>> 9;
            int l = hdr & 0x1FF;
            int v = p + 2;
            if (v + l > end) break;
            if (t == 0) break; // End of LLDPDU
            switch (t) {
                case 1: { // Chassis ID
                    if (l < 2) break;
                    int st = b(f, v);
                    String id;
                    if (st == 4 && l >= 7) id = mac(f, v + 1);
                    else if (st == 5) id = netAddr(f, v + 1, l - 1);
                    else id = str(f, v + 1, l - 1);
                    n.add("Chassis ID", id);
                    sawChassis = true;
                    break;
                }
                case 2: { // Port ID
                    if (l < 2) break;
                    int st = b(f, v);
                    n.portIdSubtype = st;
                    if (st == 3 && l >= 7) n.portId = mac(f, v + 1);
                    else if (st == 4) n.portId = netAddr(f, v + 1, l - 1);
                    else n.portId = str(f, v + 1, l - 1);
                    n.add("Port ID", n.portId + " (" + portSubtype(st) + ")");
                    break;
                }
                case 3:
                    if (l >= 2) n.add("TTL", u16(f, v) + " s");
                    break;
                case 4:
                    n.portDescription = str(f, v, l);
                    n.add("Port description", n.portDescription);
                    break;
                case 5:
                    n.systemName = str(f, v, l);
                    n.add("System name", n.systemName);
                    break;
                case 6:
                    n.platform = oneLine(str(f, v, l), 300);
                    n.add("System description", n.platform);
                    break;
                case 7:
                    if (l >= 4) {
                        int enabled = u16(f, v + 2);
                        int sys = u16(f, v);
                        n.capabilities = lldpCaps(enabled != 0 ? enabled : sys);
                        n.add("Capabilities", n.capabilities);
                    }
                    break;
                case 8: { // Management address
                    if (l < 2) break;
                    int slen = b(f, v); // length of subtype + address
                    if (slen < 2 || 1 + slen > l) break;
                    int st = b(f, v + 1);
                    String a;
                    if (st == 1 && slen == 5) a = ipv4(f, v + 2);
                    else if (st == 2 && slen == 17) a = ipv6(f, v + 2);
                    else if (st == 6 && slen == 7) a = mac(f, v + 2);
                    else a = hex(f, v + 2, slen - 1);
                    if (n.mgmtAddress.isEmpty() || (st == 1 && !n.mgmtAddress.contains("."))) n.mgmtAddress = a;
                    n.add("Management address", a);
                    break;
                }
                case 127:
                    if (l >= 4) parseLldpOrg(n, f, v, l);
                    break;
                default:
                    break;
            }
            p = v + l;
        }
        return sawChassis || !n.portId.isEmpty() ? n : null;
    }

    private static void parseLldpOrg(NeighborInfo n, byte[] f, int v, int l) {
        int oui = (b(f, v) << 16) | (b(f, v + 1) << 8) | b(f, v + 2);
        int sub = b(f, v + 3);
        int d = v + 4, dl = l - 4;
        if (oui == 0x0080C2) { // IEEE 802.1
            if (sub == 1 && dl >= 2) {
                int pvid = u16(f, d);
                if (pvid != 0) {
                    n.nativeVlan = String.valueOf(pvid);
                    n.add("Native VLAN (PVID)", n.nativeVlan);
                }
            } else if (sub == 3 && dl >= 3) {
                int vid = u16(f, d);
                int nl = b(f, d + 2);
                String name = nl > 0 && 3 + nl <= dl ? str(f, d + 3, nl) : "";
                n.add("VLAN", name.isEmpty() ? String.valueOf(vid) : vid + " (" + name + ")");
            }
        } else if (oui == 0x00120F) { // IEEE 802.3
            if (sub == 1 && dl >= 5) {
                int as = b(f, d);
                boolean supported = (as & 0x01) != 0, enabled = (as & 0x02) != 0;
                n.add("Auto-negotiation", supported ? (enabled ? "Supported, enabled" : "Supported, disabled") : "Not supported");
                n.add("Operational speed", mauType(u16(f, d + 3)));
            } else if (sub == 2 && dl >= 3) {
                int cls = b(f, d + 2);
                String s = "Class " + (cls > 0 ? cls - 1 : 0);
                if (dl >= 8) { // 802.3at extension: type/source/priority(1), requested(2), allocated(2)
                    int alloc = u16(f, d + 6);
                    if (alloc > 0) s += ", allocated " + String.format(Locale.US, "%.1f W", alloc / 10.0);
                }
                n.add("PoE (802.3)", s);
            } else if (sub == 4 && dl >= 2) {
                n.add("Max frame size", String.valueOf(u16(f, d)));
            }
        } else if (oui == 0x0012BB) { // TIA LLDP-MED
            if (sub == 2 && dl >= 4) {
                int app = b(f, d);
                int bits = (b(f, d + 1) << 16) | (b(f, d + 2) << 8) | b(f, d + 3);
                boolean unknown = (bits & 0x800000) != 0;
                boolean tagged = (bits & 0x400000) != 0;
                int vlan = (bits >> 9) & 0xFFF;
                int prio = (bits >> 6) & 0x7;
                int dscp = bits & 0x3F;
                if (!unknown) {
                    String s = "VLAN " + vlan + (tagged ? " tagged" : " untagged") + ", CoS " + prio + ", DSCP " + dscp;
                    String label = medApp(app);
                    if (app == 1 && n.voiceVlan.isEmpty()) n.voiceVlan = String.valueOf(vlan);
                    n.add(label, s);
                }
            } else if (sub == 4 && dl >= 3) {
                int watts10 = u16(f, d + 1);
                n.add("PoE (MED)", String.format(Locale.US, "%.1f W", watts10 / 10.0));
            }
        }
    }

    private static String portSubtype(int st) {
        switch (st) {
            case 1: return "interface alias";
            case 2: return "port component";
            case 3: return "MAC address";
            case 4: return "network address";
            case 5: return "interface name";
            case 6: return "agent circuit ID";
            case 7: return "locally assigned";
            default: return "subtype " + st;
        }
    }

    private static String lldpCaps(int c) {
        List<String> s = new ArrayList<>();
        if ((c & 0x01) != 0) s.add("Other");
        if ((c & 0x02) != 0) s.add("Repeater");
        if ((c & 0x04) != 0) s.add("Bridge");
        if ((c & 0x08) != 0) s.add("WLAN AP");
        if ((c & 0x10) != 0) s.add("Router");
        if ((c & 0x20) != 0) s.add("Telephone");
        if ((c & 0x40) != 0) s.add("DOCSIS");
        if ((c & 0x80) != 0) s.add("Station");
        if ((c & 0x100) != 0) s.add("C-VLAN");
        if ((c & 0x200) != 0) s.add("S-VLAN");
        if ((c & 0x400) != 0) s.add("TPMR");
        return String.join(", ", s);
    }

    private static String medApp(int app) {
        switch (app) {
            case 1: return "Voice VLAN";
            case 2: return "Voice signaling VLAN";
            case 3: return "Guest voice VLAN";
            case 4: return "Guest voice signaling VLAN";
            case 5: return "Softphone voice VLAN";
            case 6: return "Video conferencing VLAN";
            case 7: return "Streaming video VLAN";
            case 8: return "Video signaling VLAN";
            default: return "Network policy (app " + app + ")";
        }
    }

    private static String mauType(int t) {
        switch (t) {
            case 10: return "10BASE-T half duplex";
            case 11: return "10BASE-T full duplex";
            case 15: return "100BASE-TX half duplex";
            case 16: return "100BASE-TX full duplex";
            case 29: return "1000BASE-T half duplex";
            case 30: return "1000BASE-T full duplex";
            default: return "MAU type " + t;
        }
    }

    // ---------------------------------------------------------------- helpers

    static int b(byte[] f, int i) { return f[i] & 0xFF; }

    static int u16(byte[] f, int i) { return (b(f, i) << 8) | b(f, i + 1); }

    static long u32(byte[] f, int i) {
        return ((long) b(f, i) << 24) | ((long) b(f, i + 1) << 16) | ((long) b(f, i + 2) << 8) | b(f, i + 3);
    }

    private static boolean dstIs(byte[] f, int off, int[] mac) {
        for (int i = 0; i < 6; i++) if (b(f, off + i) != mac[i]) return false;
        return true;
    }

    public static String mac(byte[] f, int i) {
        return String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
                b(f, i), b(f, i + 1), b(f, i + 2), b(f, i + 3), b(f, i + 4), b(f, i + 5));
    }

    private static String ipv4(byte[] f, int i) {
        return b(f, i) + "." + b(f, i + 1) + "." + b(f, i + 2) + "." + b(f, i + 3);
    }

    private static String ipv6(byte[] f, int i) {
        try {
            byte[] a = new byte[16];
            System.arraycopy(f, i, a, 0, 16);
            return java.net.InetAddress.getByAddress(a).getHostAddress(); // no DNS lookup
        } catch (Exception e) {
            return hex(f, i, 16);
        }
    }

    private static String netAddr(byte[] f, int i, int l) {
        if (l == 5 && b(f, i) == 1) return ipv4(f, i + 1);
        if (l == 17 && b(f, i) == 2) return ipv6(f, i + 1);
        return hex(f, i, l);
    }

    private static String hex(byte[] f, int i, int l) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < l; k++) {
            if (k > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02x", b(f, i + k)));
        }
        return sb.toString();
    }

    /** Decodes text, stripping trailing NULs and replacing control characters. */
    private static String str(byte[] f, int i, int l) {
        if (l <= 0) return "";
        String s = new String(f, i, l, StandardCharsets.UTF_8);
        int z = s.indexOf('\0');
        if (z >= 0) s = s.substring(0, z);
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) sb.append(c == '\n' || c == '\r' || c >= 0x20 ? c : ' ');
        return sb.toString().trim();
    }

    private static String oneLine(String s, int max) {
        s = s.replaceAll("\\s*[\\r\\n]+\\s*", " / ").trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String watts(long milliwatts) {
        return String.format(Locale.US, "%.1f W", milliwatts / 1000.0);
    }
}
