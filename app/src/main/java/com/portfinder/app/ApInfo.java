package com.portfinder.app;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Identity and load details an access point puts in its beacons, decoded from 802.11 information
 * elements: the AP name (Cisco, Aruba, Juniper Mist, Extreme/Aerohive, others), WPS device details
 * (home routers), and the BSS Load element (client count and channel utilization).
 * Pure Java so it can be unit-tested; WifiPanel feeds it ScanResult.getInformationElements().
 */
public final class ApInfo {

    /** One information element: id and its payload (without the 2-byte id/length header). */
    public static final class Element {
        public final int id;
        public final byte[] data;

        public Element(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }
    }

    public String name = "";
    public String nameSource = "";
    public int clients = -1;          // from BSS Load (or Cisco IE)
    public int channelUtilPct = -1;   // from BSS Load
    public String wpsManufacturer = "", wpsModel = "", wpsDeviceName = "";

    private int namePriority = Integer.MAX_VALUE;

    private static final int EID_BSS_LOAD = 11, EID_CISCO_CCX1 = 133, EID_VENDOR = 221;

    public static ApInfo parse(List<Element> elements) {
        ApInfo a = new ApInfo();
        if (elements == null) return a;
        for (Element e : elements) {
            try {
                a.add(e);
            } catch (RuntimeException ignored) {
                // malformed element
            }
        }
        if (a.name.isEmpty() && !a.wpsDeviceName.isEmpty()) {
            a.name = a.wpsDeviceName;
            a.nameSource = "WPS device name";
        }
        return a;
    }

    private void add(Element e) {
        byte[] d = e.data;
        if (d == null) return;
        if (e.id == EID_BSS_LOAD && d.length >= 5) {
            clients = (d[0] & 0xFF) | ((d[1] & 0xFF) << 8);
            channelUtilPct = Math.round((d[2] & 0xFF) * 100f / 255f);
        } else if (e.id == EID_CISCO_CCX1 && d.length >= 26) {
            // Cisco "CCX1 CKIP + Device Name": 10 bytes, 16-byte AP name, client count
            setName(ascii(d, 10, 16), "Cisco beacon", 1);
            if (d.length >= 27 && clients < 0) clients = d[26] & 0xFF;
        } else if (e.id == EID_VENDOR && d.length >= 4) {
            int oui = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
            int type = d[3] & 0xFF;
            switch (oui) {
                case 0x000B86: // Aruba (HPE): subtype 3 = AP name, after one more byte
                    if (type == 3 && d.length > 5) setName(ascii(d, 5, d.length - 5), "Aruba beacon", 2);
                    break;
                case 0x5C5B35: // Juniper Mist: type 1 = AP name
                    if (type == 1 && d.length > 4) setName(ascii(d, 4, d.length - 4), "Mist beacon", 2);
                    break;
                case 0x001977: // Extreme / Aerohive: type 33 = host name (length-prefixed)
                    if (type == 33 && d.length > 5) {
                        int len = Math.min(d[4] & 0xFF, d.length - 5);
                        setName(ascii(d, 5, len), "Extreme/Aerohive beacon", 2);
                    }
                    break;
                case 0x0050F2: // Microsoft OUI, type 4 = WPS
                    if (type == 4) parseWps(d, 4);
                    break;
                case 0x00090F: // Fortinet
                case 0x001392: // Ruckus
                case 0x00180A: // Cisco Meraki
                case 0x00156D: // Ubiquiti
                case 0x002722: // Ubiquiti
                    String run = longestPrintable(d, 4);
                    if (run.length() >= 4) setName(run, vendorName(oui) + " beacon", 3);
                    break;
                default:
                    break;
            }
        }
    }

    private void parseWps(byte[] d, int p) {
        while (p + 4 <= d.length) {
            int t = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
            int l = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            int v = p + 4;
            if (v + l > d.length) break;
            String s = ascii(d, v, l);
            if (t == 0x1011) wpsDeviceName = s;
            else if (t == 0x1021) wpsManufacturer = s;
            else if (t == 0x1023) wpsModel = s;
            p = v + l;
        }
    }

    private void setName(String n, String source, int priority) {
        n = n.trim();
        if (n.isEmpty() || priority >= namePriority) return;
        name = n;
        nameSource = source;
        namePriority = priority;
    }

    private static String vendorName(int oui) {
        switch (oui) {
            case 0x00090F: return "Fortinet";
            case 0x001392: return "Ruckus";
            case 0x00180A: return "Meraki";
            default: return "Ubiquiti";
        }
    }

    /** Printable ASCII, stopping at the first NUL. */
    static String ascii(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < d.length; i++) {
            int c = d[i] & 0xFF;
            if (c == 0) break;
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.toString();
    }

    /** Longest run of name-like characters (letters, digits, - _ . space), for vendors without a known layout. */
    static String longestPrintable(byte[] d, int off) {
        String best = "";
        StringBuilder cur = new StringBuilder();
        for (int i = off; i <= d.length; i++) {
            int c = i < d.length ? d[i] & 0xFF : 0;
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '-' || c == '_' || c == '.' || c == ' ';
            if (ok) {
                cur.append((char) c);
            } else {
                if (cur.toString().trim().length() > best.length()) best = cur.toString().trim();
                cur.setLength(0);
            }
        }
        // A real name has at least one letter
        return best.matches(".*[A-Za-z].*") ? best : "";
    }

    /** True if two BSSIDs most likely belong to the same physical AP (virtual BSSIDs/other radio). */
    public static boolean sameAp(String a, String b) {
        byte[] x = mac(a), y = mac(b);
        if (x == null || y == null) return false;
        if (x[1] != y[1] || x[2] != y[2] || x[3] != y[3]) return false;
        // Vendors derive extra BSSIDs by changing the low bits of the last octet(s), sometimes also
        // flipping the "locally administered" bit in the first octet.
        int dx = ((x[4] & 0xFF) << 8) | (x[5] & 0xFF);
        int dy = ((y[4] & 0xFF) << 8) | (y[5] & 0xFF);
        return Math.abs(dx - dy) < 0x40 && ((x[0] & 0xFD) == (y[0] & 0xFD) || (x[0] & 0x02) != (y[0] & 0x02));
    }

    public static boolean locallyAdministered(String bssid) {
        byte[] m = mac(bssid);
        return m != null && (m[0] & 0x02) != 0;
    }

    static byte[] mac(String s) {
        if (s == null) return null;
        String[] p = s.split(":");
        if (p.length != 6) return null;
        byte[] m = new byte[6];
        try {
            for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(p[i], 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return m;
    }

    public static String oui(String bssid) {
        return bssid == null || bssid.length() < 8 ? "" : bssid.substring(0, 8).toUpperCase(Locale.US);
    }
}
