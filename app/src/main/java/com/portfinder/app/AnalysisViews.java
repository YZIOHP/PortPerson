package com.portfinder.app;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fills the LAN Switch and Traffic sub-tabs from a {@link FrameAnalyzer.Report}. */
final class AnalysisViews {

    private static final int[] BAR_COLORS = {MainActivity.BLUE, 0xFF9B7BEA, MainActivity.ACCENT, MainActivity.WARN,
            0xFF2BB5B8, 0xFFE57A4B, 0xFFE0609A, 0xFF8A93A0};

    private AnalysisViews() {}

    private static LinearLayout titled(MainActivity a, String title, String right) {
        LinearLayout c = a.card();
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(a.text(title, 16, MainActivity.TEXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null && !right.isEmpty()) head.addView(a.text(right, 12, MainActivity.MUTED, false));
        c.addView(head);
        return c;
    }

    private static void empty(MainActivity a, LinearLayout box, String msg) {
        TextView t = a.text(msg, 14, MainActivity.MUTED, false);
        t.setPadding(a.dp(2), a.dp(14), 0, 0);
        box.addView(t);
    }

    private static String bridge(int prio, String mac) {
        return prio < 0 ? mac : prio + " · " + mac;
    }

    // ------------------------------------------------------------------ Switch tab

    static void fillSwitch(MainActivity a, LinearLayout box, FrameAnalyzer.Report r, String rootHint) {
        box.removeAllViews();
        if (r == null || r.frames == 0) {
            empty(a, box, "Run a port test to see spanning tree, VLAN, trunking and gateway details from this port.");
            return;
        }
        // Spanning tree
        LinearLayout st = titled(a, "Spanning tree", r.stp.isEmpty() ? "" : r.stp.size() > 1 ? r.stp.size() + " instances" : "");
        if (r.stp.isEmpty()) {
            a.addRow(st, "BPDUs", "None seen (spanning tree off, or BPDU filter on this port)");
        } else {
            FrameAnalyzer.Stp s = r.stp.get(0);
            for (FrameAnalyzer.Stp x : r.stp) if (!x.tagged && x.count > s.count) s = x;
            a.addRow(st, "Mode", s.mode + (s.pvstVlan > 0 ? "  ·  VLAN " + s.pvstVlan : ""));
            a.addRow(st, "Root bridge", bridge(s.rootPriority, s.rootMac));
            if (!rootHint.isEmpty()) a.addRow(st, "Root is", rootHint);
            a.addRow(st, "Root path cost", s.rootCost >= 0 ? String.valueOf(s.rootCost) : "");
            a.addRow(st, "Sending bridge", bridge(s.bridgePriority, s.bridgeMac));
            a.addRow(st, "Sending port", s.portId >= 0 ? String.format(Locale.US, "0x%04x%s", s.portId,
                    s.role.isEmpty() ? "" : "  ·  " + s.role) : "");
            if (s.helloSeconds > 0) a.addRow(st, "Hello time", s.helloSeconds + " s");
            String tc = r.topologyChanges == 0 ? "None during the test" : r.topologyChanges + (r.topologyChanges == 1 ? " change" : " changes");
            a.addRow(st, "Topology changes", tc);
            if (r.stp.size() > 1) {
                StringBuilder vl = new StringBuilder();
                for (FrameAnalyzer.Stp x : r.stp) {
                    if (x.pvstVlan > 0) {
                        if (vl.length() > 0) vl.append(", ");
                        vl.append(x.pvstVlan).append(x.tagged ? " (tagged)" : "");
                    }
                }
                a.addRow(st, "PVST+ VLANs", vl.toString());
            }
        }
        box.addView(st);

        // VLANs & trunking
        LinearLayout vt = titled(a, "VLANs & trunking", "");
        if (r.taggedVlans.isEmpty()) {
            a.addRow(vt, "Tagged frames", "None (normal for an access port)");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Long> e : r.taggedVlans.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("VLAN ").append(e.getKey()).append(" (").append(e.getValue()).append(')');
            }
            a.addRow(vt, "Tagged frames", sb.toString());
        }
        a.addRow(vt, "DTP", r.dtpMode.isEmpty() ? "Not seen" : r.dtpMode + (r.dtpOperating.isEmpty() ? "" : "  ·  now " + r.dtpOperating.toLowerCase(Locale.US)));
        if (!r.dtpDomain.isEmpty()) a.addRow(vt, "DTP domain", r.dtpDomain);
        a.addRow(vt, "VTP", r.vtpDomain.isEmpty() ? "Not seen" : "Domain " + r.vtpDomain + (r.vtpVersion > 0 ? "  ·  v" + r.vtpVersion : ""));
        a.addRow(vt, "802.1X", r.eapol ? (r.eapIdentityRequest ? "Required: the switch asked for an identity" : "EAPOL frames seen") : "Not requested");
        box.addView(vt);

        // Gateway redundancy
        LinearLayout gw = titled(a, "Gateway redundancy", "");
        if (r.gateways.isEmpty()) {
            a.addRow(gw, "HSRP / VRRP", "None seen");
        } else {
            for (FrameAnalyzer.Gateway g : r.gateways) {
                a.addRow(gw, g.protocol + " group " + g.group, (g.virtualIp.isEmpty() ? "" : "Virtual IP " + g.virtualIp + "  ·  ")
                        + g.state + " " + g.sourceIp + (g.priority >= 0 ? "  ·  priority " + g.priority : ""));
            }
        }
        box.addView(gw);

        // Routing
        LinearLayout rt = titled(a, "Routing", "");
        if (r.routers.isEmpty()) {
            a.addRow(rt, "Routing hellos", "None seen");
        } else {
            for (FrameAnalyzer.Router x : r.routers) {
                a.addRow(rt, x.protocol, (x.protocol.equals("OSPF") ? "Router ID " + x.id : x.id) + "  ·  from " + x.sourceIp
                        + (x.detail.isEmpty() ? "" : "  ·  " + x.detail));
            }
        }
        if (r.ipv6Routers.isEmpty()) {
            a.addRow(rt, "IPv6 routers", "None advertising");
        } else {
            for (FrameAnalyzer.Ipv6Router x : r.ipv6Routers) {
                String flags = x.managed ? "DHCPv6" : x.other ? "SLAAC + DHCPv6 options" : "SLAAC";
                a.addRow(rt, "IPv6 router", x.sourceIp + "\n" + (x.prefixes.isEmpty() ? "" : String.join(", ", x.prefixes) + "  ·  ") + flags);
            }
        }
        if (!r.igmpQuerier.isEmpty()) a.addRow(rt, "IGMP querier", r.igmpQuerier);
        box.addView(rt);
    }

    // ------------------------------------------------------------------ Traffic tab

    static void fillTraffic(MainActivity a, LinearLayout box, FrameAnalyzer.Report r, Runnable savePcap) {
        box.removeAllViews();
        if (r == null || r.frames == 0) {
            empty(a, box, "Run a port test to see the traffic on this port and the devices announcing themselves.");
            return;
        }
        LinearLayout tc = titled(a, "Traffic", String.format(Locale.US, "%d s · %,d frames", r.durationMs / 1000, r.frames));
        List<Map.Entry<String, Long>> ps = new ArrayList<>(r.protocols.entrySet());
        long max = ps.isEmpty() ? 1 : Math.max(1, ps.get(0).getValue());
        int shown = 0;
        long other = 0;
        for (Map.Entry<String, Long> e : ps) {
            if (shown < 8) {
                tc.addView(bar(a, e.getKey(), e.getValue(), max, r, BAR_COLORS[shown % BAR_COLORS.length]));
                shown++;
            } else {
                other += e.getValue();
            }
        }
        if (other > 0) tc.addView(bar(a, "Everything else", other, max, r, 0xFF5A6068));
        String rate = String.format(Locale.US, "%.0f/s average, %.0f/s peak", r.broadcastRate, r.peakBroadcastRate);
        a.addRow(tc, "Broadcasts", rate);
        a.addRow(tc, "Multicasts", String.format(Locale.US, "%,d", r.multicasts));
        box.addView(tc);

        LinearLayout dv = titled(a, "Devices announcing themselves", r.devices.isEmpty() ? "" : r.devices.size() + " found");
        if (r.devices.isEmpty()) {
            a.addRow(dv, "Devices", "None announced a name yet (mDNS, NetBIOS or DHCP)");
        } else {
            int n = 0;
            for (FrameAnalyzer.Device d : r.devices) {
                if (n++ == 25) {
                    a.addRow(dv, "+ " + (r.devices.size() - 25) + " more", "Saved in the .pcap and the shared report");
                    break;
                }
                a.addRow(dv, d.name, (d.ip.isEmpty() ? "" : d.ip + "  ·  ") + d.mac + "  ·  " + d.source);
            }
        }
        box.addView(dv);

        LinearLayout tb = titled(a, "Top broadcast senders", "");
        if (r.topBroadcasters.isEmpty()) {
            a.addRow(tb, "Senders", "None");
        } else {
            for (FrameAnalyzer.Talker t : r.topBroadcasters) {
                a.addRow(tb, t.ip.isEmpty() ? t.mac : t.ip, (t.ip.isEmpty() ? "" : t.mac + "  ·  ")
                        + String.format(Locale.US, "%.1f/s", r.rate(t.frames)));
            }
        }
        box.addView(tb);

        Button save = a.button("Save capture (.pcap)", false);
        save.setOnClickListener(v -> savePcap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(48));
        lp.topMargin = a.dp(12);
        box.addView(save, lp);
        TextView note = a.text(String.format(Locale.US, "%,d frames kept%s. Opens in Wireshark. Captures can include other devices' "
                + "information; only share them on networks you're authorized to test.", r.capturedFrames,
                r.captureTruncated ? " (oldest dropped to save memory)" : ""), 12, MainActivity.MUTED, false);
        note.setPadding(a.dp(2), a.dp(6), 0, 0);
        box.addView(note);
    }

    private static View bar(MainActivity a, String name, long n, long max, FrameAnalyzer.Report r, int color) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, a.dp(5), 0, 0);
        TextView l = a.text(name, 12, MainActivity.MUTED, false);
        row.addView(l, new LinearLayout.LayoutParams(a.dp(118), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout track = new LinearLayout(a);
        track.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(a);
        fill.setBackground(a.rounded(color, a.dp(3)));
        long w = Math.max(1, n * 100 / max);
        track.addView(fill, new LinearLayout.LayoutParams(0, a.dp(10), w));
        if (w < 100) track.addView(new View(a), new LinearLayout.LayoutParams(0, a.dp(10), 100 - w));
        row.addView(track, new LinearLayout.LayoutParams(0, a.dp(10), 1f));
        double rate = r.rate(n);
        TextView v = a.text(rate >= 1 ? String.format(Locale.US, "%.0f/s", rate) : String.format(Locale.US, "%,d", n), 12, MainActivity.TEXT, false);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(a.dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ------------------------------------------------------------------ text report

    static String switchText(FrameAnalyzer.Report r) {
        if (r == null || r.frames == 0) return "";
        StringBuilder sb = new StringBuilder("Switch details\n");
        if (!r.stp.isEmpty()) {
            FrameAnalyzer.Stp s = r.stp.get(0);
            sb.append("  Spanning tree: ").append(s.mode).append(s.pvstVlan > 0 ? " VLAN " + s.pvstVlan : "")
              .append(", root ").append(bridge(s.rootPriority, s.rootMac)).append('\n');
            sb.append("  Topology changes: ").append(r.topologyChanges).append('\n');
        } else {
            sb.append("  Spanning tree: no BPDUs seen\n");
        }
        if (!r.taggedVlans.isEmpty()) sb.append("  Tagged VLANs seen: ").append(r.taggedVlans.keySet()).append('\n');
        if (!r.dtpMode.isEmpty()) sb.append("  DTP: ").append(r.dtpMode).append('\n');
        if (!r.vtpDomain.isEmpty()) sb.append("  VTP domain: ").append(r.vtpDomain).append('\n');
        if (r.eapol) sb.append("  802.1X: requested by the switch\n");
        for (FrameAnalyzer.Gateway g : r.gateways) {
            sb.append("  ").append(g.protocol).append(" group ").append(g.group).append(": ").append(g.virtualIp)
              .append(", ").append(g.state.toLowerCase(Locale.US)).append(' ').append(g.sourceIp).append('\n');
        }
        for (FrameAnalyzer.DhcpServer d : r.dhcpServers) {
            sb.append("  DHCP server seen: ").append(d.serverId).append(d.router.isEmpty() ? "" : ", gateway " + d.router).append('\n');
        }
        for (FrameAnalyzer.Router x : r.routers) sb.append("  ").append(x.protocol).append(" hellos from ").append(x.sourceIp).append('\n');
        sb.append(String.format(Locale.US, "  Traffic: %,d frames in %d s, %.0f broadcasts/s\n", r.frames, r.durationMs / 1000, r.broadcastRate));
        if (!r.devices.isEmpty()) sb.append("  Devices announcing names: ").append(r.devices.size()).append('\n');
        return sb.toString();
    }
}
