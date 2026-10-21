package com.portfinder.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a {@link FrameAnalyzer.Report} (plus what the port test learned) into a short list of
 * problems and warnings, and the names of the checks that passed. Pure Java.
 */
public final class Findings {

    public static final int OK = 0, WARN = 1, BAD = 2;
    public static final int TAB_OVERVIEW = 0, TAB_SWITCH = 1, TAB_TRAFFIC = 2;
    /** A topology change this soon after the first frame is most likely our own port coming up. */
    static final long LINK_UP_TC_WINDOW_MS = 45_000;

    public static final class Item {
        public final int severity;
        public final String title, detail;
        public final int tab;

        Item(int severity, String title, String detail, int tab) {
            this.severity = severity;
            this.title = title;
            this.detail = detail;
            this.tab = tab;
        }
    }

    /** What the port test found that the checks need. Strings are "" when unknown. */
    public static final class Context {
        public boolean dhcpOk, dhcpFailed;
        public String gateway = "", leaseIp = "", cdpNativeVlan = "";
        public long seconds;
    }

    public final List<Item> items = new ArrayList<>();
    public final List<String> passed = new ArrayList<>();

    public int count(int severity) {
        int n = 0;
        for (Item i : items) if (i.severity == severity) n++;
        return n;
    }

    public int worst() {
        int w = OK;
        for (Item i : items) w = Math.max(w, i.severity);
        return w;
    }

    public static Findings evaluate(FrameAnalyzer.Report r, Context c) {
        Findings f = new Findings();
        if (r == null || r.frames == 0) return f;

        // DHCP servers
        if (r.dhcpServers.size() >= 2) {
            Set<String> gws = new LinkedHashSet<>(), nets = new LinkedHashSet<>();
            StringBuilder list = new StringBuilder();
            for (FrameAnalyzer.DhcpServer s : r.dhcpServers) {
                if (!s.router.isEmpty()) gws.add(s.router);
                if (!s.offered.isEmpty() && !s.mask.isEmpty()) nets.add(network(s.offered, s.mask));
                if (list.length() > 0) list.append(", ");
                list.append(s.serverId);
            }
            boolean differ = gws.size() > 1 || nets.size() > 1;
            String title = r.dhcpServers.size() + " DHCP servers answered";
            if (differ) {
                f.items.add(new Item(BAD, title, "They hand out different " + (gws.size() > 1 ? "gateways (" + String.join(" vs ", gws) + ")"
                        : "subnets (" + String.join(" vs ", nets) + ")") + ". One is likely a rogue server: " + list + ".", TAB_OVERVIEW));
            } else {
                f.items.add(new Item(WARN, title, "Same gateway, so this is likely DHCP failover. Check it's expected: " + list + ".", TAB_OVERVIEW));
            }
        } else if (r.dhcpServers.size() == 1) {
            f.passed.add("one DHCP server");
        }

        // 802.1X
        if (r.eapol) {
            if (!c.dhcpOk && c.dhcpFailed) {
                f.items.add(new Item(BAD, "802.1X required on this port", "The switch is asking for an 802.1X login, so DHCP can't "
                        + "give an address without it.", TAB_SWITCH));
            } else {
                f.items.add(new Item(WARN, "Port sends 802.1X requests", "The switch asks for an 802.1X login"
                        + (c.dhcpOk ? " but still gave an address (fallback or MAB VLAN)." : "."), TAB_SWITCH));
            }
        } else {
            f.passed.add("no 802.1X");
        }

        // Loops and storms
        boolean loop = r.loopRepeats >= FrameAnalyzer.LOOP_REPEAT_THRESHOLD;
        if (loop) {
            f.items.add(new Item(BAD, "Possible switching loop", String.format(Locale.US,
                    "The same broadcast arrived %d times within one second.", r.loopRepeats), TAB_TRAFFIC));
        }
        if (r.peakBroadcastRate >= 1000 && r.durationMs >= 3000) {
            f.items.add(new Item(BAD, "Broadcast storm", String.format(Locale.US, "Up to %.0f broadcasts per second.", r.peakBroadcastRate), TAB_TRAFFIC));
        } else if (r.broadcastRate >= 200 && r.durationMs >= 3000) {
            f.items.add(new Item(WARN, "High broadcast rate", String.format(Locale.US, "%.0f broadcasts per second on average.", r.broadcastRate), TAB_TRAFFIC));
        } else if (!loop) {
            f.passed.add("no loops or storm");
        }

        // Duplicate IPs
        if (!r.arpConflicts.isEmpty()) {
            for (java.util.Map.Entry<String, List<String>> e : r.arpConflicts.entrySet()) {
                String ip = e.getKey();
                String who = ip.equals(c.gateway) ? " (the gateway)" : ip.equals(c.leaseIp) ? " (the address this test was given)" : "";
                f.items.add(new Item(BAD, "Duplicate IP " + ip + who, "Claimed by " + String.join(" and ", e.getValue()) + ".", TAB_TRAFFIC));
                if (f.count(BAD) > 4) break;
            }
        } else {
            f.passed.add("no duplicate IPs");
        }

        // Trunking
        boolean negotiating = r.dtpMode.startsWith("Dynamic") || r.dtpMode.startsWith("On");
        if (negotiating) {
            f.items.add(new Item(WARN, "Port is negotiating trunking (DTP)", "Mode: " + r.dtpMode.toLowerCase(Locale.US)
                    + ". Hard-set the port to access and add nonegotiate.", TAB_SWITCH));
        } else {
            f.passed.add("no trunk negotiation");
        }
        if (!r.taggedVlans.isEmpty()) {
            List<String> v = new ArrayList<>();
            for (Integer id : r.taggedVlans.keySet()) {
                v.add(String.valueOf(id));
                if (v.size() == 6) break;
            }
            f.items.add(new Item(WARN, "Tagged VLAN traffic on this port", "VLAN " + String.join(", ", v)
                    + (r.taggedVlans.size() > 6 ? " and more" : "") + " seen tagged, so the port may be a trunk.", TAB_SWITCH));
        } else {
            f.passed.add("no tagged VLANs");
        }

        // Native VLAN mismatch (untagged PVST+ BPDU says a different VLAN than CDP)
        if (!c.cdpNativeVlan.isEmpty()) {
            for (FrameAnalyzer.Stp s : r.stp) {
                if (!s.tagged && s.pvstVlan > 0 && !String.valueOf(s.pvstVlan).equals(c.cdpNativeVlan)) {
                    f.items.add(new Item(WARN, "Possible native VLAN mismatch", "CDP reports VLAN " + c.cdpNativeVlan
                            + " but untagged spanning tree is for VLAN " + s.pvstVlan + ".", TAB_SWITCH));
                    break;
                }
            }
        }

        // Spanning tree stability
        // Plugging into a port without PortFast makes the switch itself announce one change as the
        // port starts forwarding (about 30 s after link), so a single early change is expected.
        boolean onlyLinkUp = r.topologyChanges == 1 && r.firstTopologyChangeAfterMs >= 0
                && r.firstTopologyChangeAfterMs <= LINK_UP_TC_WINDOW_MS;
        if (onlyLinkUp) {
            f.passed.add("stable spanning tree (one change at link-up, normal without PortFast)");
        } else if (r.topologyChanges > 0) {
            f.items.add(new Item(WARN, "Spanning tree changed " + (r.topologyChanges == 1 ? "once" : r.topologyChanges + " times"),
                    "A topology change happened during the test; a port somewhere may be flapping.", TAB_SWITCH));
        } else if (!r.stp.isEmpty()) {
            f.passed.add("stable spanning tree");
        }

        // Routing protocols on a user port
        if (!r.routers.isEmpty()) {
            FrameAnalyzer.Router first = r.routers.get(0);
            f.items.add(new Item(WARN, first.protocol + " hellos on this port", "From " + first.sourceIp
                    + (first.detail.isEmpty() ? "" : " (" + first.detail + ")") + ". A user port usually shouldn't see "
                    + "routing traffic; check passive-interface.", TAB_SWITCH));
        } else {
            f.passed.add("no routing hellos");
        }

        // IPv6 routers
        if (r.ipv6Routers.size() > 1) {
            f.items.add(new Item(WARN, r.ipv6Routers.size() + " IPv6 routers advertising", "More than one router "
                    + "is announcing IPv6; one may be unexpected.", TAB_SWITCH));
        }
        return f;
    }

    /** "10.20.30.0/24" from an address and a dotted mask. */
    static String network(String ip, String mask) {
        try {
            String[] a = ip.split("\\."), m = mask.split("\\.");
            int bits = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int mi = Integer.parseInt(m[i]);
                bits += Integer.bitCount(mi);
                if (i > 0) sb.append('.');
                sb.append(Integer.parseInt(a[i]) & mi);
            }
            return sb + "/" + bits;
        } catch (RuntimeException e) {
            return ip;
        }
    }

    /** Lines for the text report / History ("Problem: ..."). */
    public String toText() {
        if (items.isEmpty() && passed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Findings\n");
        for (Item i : items) {
            sb.append("  ").append(i.severity == BAD ? "Problem" : "Warning").append(": ").append(i.title);
            if (!i.detail.isEmpty()) sb.append(". ").append(i.detail);
            sb.append('\n');
        }
        if (!passed.isEmpty()) sb.append("  Passed: ").append(String.join(", ", passed)).append('\n');
        return sb.toString();
    }
}
