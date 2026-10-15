package com.portfinder.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The WLAN tab: details of the current Wi-Fi connection, nearby access points, channel usage, and a
 * test (gateway, DNS, TCP, ping, traceroute) over the Wi-Fi network.
 *
 * Android exposes Wi-Fi through its public APIs only, so unlike the LAN tab this works with whatever
 * Wi-Fi radio the phone has; Android requires Location permission (and Location turned on) before it
 * reveals network names or scan results.
 */
final class WifiPanel {

    static final int PERMISSION_REQUEST = 42;
    private static final int TEST_PING_COUNT = 4;
    private static final int MAX_HOPS = 30;
    private static final int MAX_SILENT_HOPS = 5;

    private final MainActivity a;
    private final WifiManager wifi;
    private final ConnectivityManager cm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout col, permCard, permCardScan, connBox, apBox, factorsBox, testBox, channelBox, nearbyBox;
    private Icons.TopologyView topo;
    private TextView factorsRating;
    // Current connection summary (for the map, header and speed factors)
    private int curRssi = -127, curStd = -1, curInet = -1; // curInet: 0 ok, 1 captive portal, 2 none
    private String curIp = "", curGw = "";
    private Button testButton, scanButton, shareButton;
    private SpeedTestCard speedCard;
    private EditText targetEdit;
    private TextView scanStatus;

    private boolean visible, receiverRegistered, testing;
    private List<ScanResult> scan = new ArrayList<>();
    private long lastScanRequest;
    private int connectedFreq;
    private String connectedBssid = "";
    private String connectedSsid = "";
    private String apText = "";
    private final Map<String, String> vendorCache = new HashMap<>();
    private final Set<String> vendorPending = new HashSet<>();
    private String connectionText = "";

    // Test results (UI thread)
    private final TreeMap<Integer, String> hopLines = new TreeMap<>();
    private final List<String[]> testRows = new ArrayList<>();
    private String testStatus = "", traceNote = "";
    private Boolean testOk;

    WifiPanel(MainActivity a) {
        this.a = a;
        this.wifi = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    // ------------------------------------------------------------------ lifecycle

    void onShow() {
        visible = true;
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.RSSI_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else a.registerReceiver(receiver, f);
            receiverRegistered = true;
        }
        refreshAll();
        requestScan(false);
        ui.removeCallbacks(periodic);
        ui.postDelayed(periodic, 2000);
    }

    void onHide() {
        visible = false;
        ui.removeCallbacks(periodic);
        if (receiverRegistered) {
            try {
                a.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
            receiverRegistered = false;
        }
    }

    void onPermissionsResult() {
        if (visible) {
            refreshAll();
            requestScan(true);
        }
    }

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            refreshConnection();
            if (System.currentTimeMillis() - lastScanRequest > 30_000) requestScan(false);
            ui.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!visible) return;
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(i.getAction())) {
                loadScanResults();
                scanStatus.setText("Updated " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()));
            } else {
                refreshConnection();
            }
        }
    };

    // ------------------------------------------------------------------ permissions

    private boolean hasPermissions() {
        if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT < 33
                || a.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationOn() {
        LocationManager lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        return lm == null || lm.isLocationEnabled();
    }

    private void requestPermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        a.requestPermissions(p.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private void renderPermissionCard() {
        fillPermissionCard(permCard);
        fillPermissionCard(permCardScan);
    }

    private void fillPermissionCard(LinearLayout card) {
        if (card == null) return;
        card.removeAllViews();
        boolean perm = hasPermissions();
        boolean loc = locationOn();
        if (perm && loc) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        card.addView(a.label("Permission needed"));
        String msg = !perm
                ? "Android only shows Wi-Fi network names, BSSIDs and scan results to apps with Location "
                  + "permission. PortPerson doesn't use or store your location."
                : "Location is turned off. Android hides Wi-Fi network names and scan results until it's on.";
        TextView t = a.text(msg, 14, MainActivity.TEXT, false);
        t.setPadding(0, a.dp(4), 0, a.dp(10));
        card.addView(t);
        Button b = a.button(!perm ? "Allow Wi-Fi access" : "Turn on Location", true);
        b.setOnClickListener(v -> {
            if (!hasPermissions()) requestPermissions();
            else a.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        });
        card.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(48)));
    }

    // ------------------------------------------------------------------ connection

    private void refreshAll() {
        renderPermissionCard();
        refreshConnection();
        loadScanResults();
    }

    private Network wifiNetwork() {
        if (cm == null) return null;
        @SuppressWarnings("deprecation")
        Network[] all = cm.getAllNetworks();
        for (Network n : all) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private void refreshConnection() {
        refreshConnectionDetails();
        renderFactors();
        updateTopology();
        a.updateHeader();
    }

    /** Network name for the app header (empty when not connected). */
    String headerTitle() { return connectedSsid; }

    @SuppressWarnings("deprecation")
    private void refreshConnectionDetails() {
        connBox.removeAllViews();
        curRssi = -127;
        curStd = -1;
        curInet = -1;
        curIp = "";
        curGw = "";
        LinearLayout c = a.card();
        c.addView(a.label("Connection Details"));
        connBox.addView(c);
        if (wifi == null) {
            c.addView(a.text("This device has no Wi-Fi.", 15, MainActivity.WARN, false));
            connectedSsid = "";
            return;
        }
        if (!wifi.isWifiEnabled()) {
            c.addView(a.text("Wi-Fi is turned off.", 15, MainActivity.WARN, false));
            connectedFreq = 0;
            connectedBssid = "";
            connectedSsid = "";
            renderApCard();
            return;
        }
        Network n = wifiNetwork();
        WifiInfo wi = wifi.getConnectionInfo();
        if (n == null || wi == null || wi.getNetworkId() == -1 && wi.getRssi() <= -127) {
            c.addView(a.text("Not connected to a Wi-Fi network. Nearby networks are on the Scan tab.", 15, MainActivity.MUTED, false));
            connectedFreq = 0;
            connectedBssid = "";
            connectedSsid = "";
            renderApCard();
            testButton.setEnabled(false);
            testButton.setAlpha(0.4f);
            return;
        }
        testButton.setEnabled(!testing);
        testButton.setAlpha(testing ? 0.6f : 1f);

        String ssid = cleanSsid(wi.getSSID());
        connectedSsid = ssid;
        String bssid = wi.getBSSID();
        if (bssid == null || "02:00:00:00:00:00".equals(bssid)) bssid = "";
        connectedBssid = bssid;
        connectedFreq = wi.getFrequency();
        int rssi = wi.getRssi();
        curRssi = rssi;
        if (Build.VERSION.SDK_INT >= 30) curStd = wi.getWifiStandard();

        c.addView(a.text(ssid.isEmpty() ? "(network name hidden by Android)" : ssid, 22, MainActivity.TEXT, true));
        TextView sig = a.text(rssi + " dBm  ·  " + signalLabel(rssi), 17, signalColor(rssi), true);
        sig.setPadding(0, a.dp(2), 0, a.dp(4));
        c.addView(sig);

        StringBuilder share = new StringBuilder();
        share.append("Wi-Fi connection\n  SSID: ").append(ssid).append('\n');
        row(c, share, "BSSID", bssid);
        row(c, share, "Channel", channelText(connectedFreq, -1));
        if (Build.VERSION.SDK_INT >= 30) row(c, share, "Standard", standardName(wi.getWifiStandard()));
        if (Build.VERSION.SDK_INT >= 31) row(c, share, "Security", securityName(wi.getCurrentSecurityType()));
        String rate = wi.getLinkSpeed() + " Mbps";
        if (Build.VERSION.SDK_INT >= 29 && wi.getTxLinkSpeedMbps() > 0) {
            rate = "TX " + wi.getTxLinkSpeedMbps() + " / RX " + wi.getRxLinkSpeedMbps() + " Mbps";
        }
        if (Build.VERSION.SDK_INT >= 30 && wi.getMaxSupportedTxLinkSpeedMbps() > 0) {
            rate += "  (max " + wi.getMaxSupportedTxLinkSpeedMbps() + ")";
        }
        row(c, share, "Link rate", rate);
        int cochannel = coChannelCount();
        if (cochannel >= 0) row(c, share, "Co-channel APs", cochannel + (cochannel == 0 ? "" : " other AP" + (cochannel == 1 ? "" : "s") + " on this channel above −82 dBm"));

        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        if (nc != null) {
            String inet;
            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                inet = "Captive portal (sign-in page)";
                curInet = 1;
            } else if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                inet = "Internet OK (Android check)";
                curInet = 0;
            } else {
                inet = "No internet detected by Android";
                curInet = 2;
            }
            row(c, share, "Internet", inet);
        }

        LinkProperties lp = cm.getLinkProperties(n);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    row(c, share, "IP address", la.getAddress().getHostAddress() + "/" + la.getPrefixLength());
                    curIp = la.getAddress().getHostAddress();
                }
            }
            String gw = gateway(lp);
            curGw = gw;
            row(c, share, "Gateway", gw);
            List<String> dns = new ArrayList<>();
            for (InetAddress d : lp.getDnsServers()) dns.add(d.getHostAddress());
            row(c, share, "DNS servers", String.join(", ", dns));
            row(c, share, "Domain", lp.getDomains());
            if (Build.VERSION.SDK_INT >= 30 && lp.getDhcpServerAddress() != null) {
                row(c, share, "DHCP server", lp.getDhcpServerAddress().getHostAddress());
            }
        }
        DhcpInfo di = wifi.getDhcpInfo();
        if (di != null && di.leaseDuration > 0) row(c, share, "Lease time", duration(di.leaseDuration));
        row(c, share, "Phone MAC", wi.getMacAddress() == null || wi.getMacAddress().startsWith("02:00:00") ? "" : wi.getMacAddress());

        boolean defaultIsWifi = n.equals(cm.getActiveNetwork());
        if (!defaultIsWifi) {
            TextView w = a.text("Wi-Fi isn't the phone's default network right now (usually because Android found "
                    + "no internet on it). Ping and traceroute would go over mobile data, so the test runs only the "
                    + "Wi-Fi-bound checks (DNS and TCP). Turn off mobile data for the full test.", 12, MainActivity.WARN, false);
            w.setPadding(0, a.dp(8), 0, 0);
            c.addView(w);
        }
        connectionText = share.toString();
        renderApCard();
    }

    private void row(LinearLayout card, StringBuilder share, String label, String value) {
        if (value == null || value.isEmpty()) return;
        a.addRow(card, label, value);
        share.append("  ").append(label).append(": ").append(value).append('\n');
    }

    private static String gateway(LinkProperties lp) {
        for (RouteInfo r : lp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway().getHostAddress();
        }
        return "";
    }

    private int coChannelCount() {
        if (connectedFreq <= 0 || scan.isEmpty()) return -1;
        int ch = channel(connectedFreq);
        int n = 0;
        for (ScanResult r : scan) {
            if (r.BSSID != null && r.BSSID.equalsIgnoreCase(connectedBssid)) continue;
            if (channel(r.frequency) == ch && band(r.frequency).equals(band(connectedFreq)) && r.level >= -82) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ map and speed factors

    private static int signalPct(int rssi) {
        return Math.max(0, Math.min(100, 2 * (rssi + 100)));
    }

    private void updateTopology() {
        if (topo == null) return;
        List<Icons.Node> nodes = new ArrayList<>();
        List<Icons.Link> links = new ArrayList<>();
        boolean connected = !connectedBssid.isEmpty() || !connectedSsid.isEmpty();
        String inet = !connected ? "—" : curInet == 0 ? "Online" : curInet == 1 ? "Sign-in needed" : curInet == 2 ? "Unavailable" : "—";
        int inetColor = curInet == 0 ? MainActivity.ACCENT : curInet == 1 ? MainActivity.WARN : curInet == 2 ? MainActivity.ERROR : MainActivity.MUTED;
        nodes.add(new Icons.Node(Icons.Type.GLOBE, 0xFFB9BEC5, "Internet", "", inet, inetColor));
        links.add(new Icons.Link(false, curInet == 2 ? MainActivity.ERROR : MainActivity.LINE));
        nodes.add(new Icons.Node(Icons.Type.GATEWAY, connected ? 0xFFD5DAE0 : 0xFF6B7178, "Gateway", curGw.isEmpty() ? "—" : curGw, "", MainActivity.MUTED));
        links.add(new Icons.Link(false, MainActivity.LINE));
        ScanResult me = connected ? connectedScan() : null;
        ApInfo info = me != null ? ApInfo.parse(elements(me)) : null;
        String apName = info != null && !info.name.isEmpty() ? info.name : connected ? "Access Point" : "Not connected";
        String apSub = connected && connectedFreq > 0 ? "ch " + channel(connectedFreq) + " · " + band(connectedFreq) : "—";
        String apStat = info != null && info.clients >= 0 ? info.clients + " clients" : "";
        nodes.add(new Icons.Node(Icons.Type.AP, connected ? 0xFFF2F4F5 : 0xFF6B7178, apName, apSub, apStat, MainActivity.TEXT));
        links.add(new Icons.Link(true, connected ? signalColor(curRssi) : MainActivity.LINE));
        nodes.add(new Icons.Node(Icons.Type.PHONE, 0xFFD5DAE0, "This phone", curIp.isEmpty() ? "—" : curIp,
                connected && curRssi > -127 ? signalPct(curRssi) + " %" : "", signalColor(curRssi)));
        // Read left to right from where you are: this phone → switch/AP → gateway → Internet
        java.util.Collections.reverse(nodes);
        java.util.Collections.reverse(links);
        topo.set(nodes, links);
    }

    /** WiFiman-style "speed factors": what limits this phone's Wi-Fi speed right now. */
    private void renderFactors() {
        if (factorsBox == null) return;
        factorsBox.removeAllViews();
        if (connectedBssid.isEmpty() && connectedSsid.isEmpty()) {
            factorsRating.setText("");
            return;
        }
        ScanResult me = connectedScan();
        ApInfo info = me != null ? ApInfo.parse(elements(me)) : null;
        int worst = 0, warns = 0;

        // Spectrum: band and channel width
        String band = connectedFreq > 0 ? band(connectedFreq) : "";
        int width = me != null ? me.channelWidth : -1;
        String widthText = widthName(width);
        int spectrum = 0;
        if ("2.4 GHz".equals(band) || width == 0) spectrum = 1;
        factorsBox.addView(a.factorCard("Spectrum", MainActivity.kv("Wi-Fi Band", band, "Channel Width", widthText), spectrum));

        // Radio potential: standard and signal
        int radio = curRssi >= -67 ? 0 : curRssi >= -75 ? 1 : 2;
        if (radio == 0 && curStd >= 0 && curStd < 5) radio = 1; // older than Wi-Fi 5
        String std = curStd >= 0 ? standardName(curStd) : "";
        if (std.startsWith("Wi-Fi") && std.contains(" (")) std = std.substring(0, std.indexOf(" (")); // "Wi-Fi 6"
        factorsBox.addView(a.factorCard("Radio Potential",
                MainActivity.kv("Standard", std, "Signal", curRssi > -127 ? curRssi + " dBm" : ""), radio));

        // Channel health: utilization reported by the AP and co-channel neighbors
        int cochannel = coChannelCount();
        int util = info != null ? info.channelUtilPct : -1;
        int health = 0;
        if (util >= 70 || cochannel >= 5) health = 2;
        else if (util >= 40 || cochannel >= 3) health = 1;
        factorsBox.addView(a.factorCard("Channel Health", MainActivity.kv(
                "Channel", connectedFreq > 0 ? String.valueOf(channel(connectedFreq)) : "",
                "Utilization", util >= 0 ? util + " %" : "",
                "Neighbors", cochannel >= 0 ? String.valueOf(cochannel) : ""), health));

        for (int st : new int[]{spectrum, radio, health}) {
            worst = Math.max(worst, st);
            if (st == 1) warns++;
        }
        String rating = worst == 2 ? "Poor" : warns >= 2 ? "Fair" : warns == 1 ? "Good" : "Excellent";
        factorsRating.setText(rating);
        factorsRating.setTextColor(worst == 2 ? MainActivity.ERROR : warns >= 1 ? MainActivity.WARN : MainActivity.ACCENT);
    }

    static String widthName(int width) {
        switch (width) {
            case 0: return "20 MHz";
            case 1: return "40 MHz";
            case 2: return "80 MHz";
            case 3: return "160 MHz";
            case 4: return "80+80 MHz";
            case 5: return "320 MHz";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ access point identity

    private ScanResult connectedScan() {
        for (ScanResult r : scan) if (r.BSSID != null && r.BSSID.equalsIgnoreCase(connectedBssid)) return r;
        return null;
    }

    /** The beacon's information elements (Android 11+). */
    private static List<ApInfo.Element> elements(ScanResult r) {
        List<ApInfo.Element> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return out;
        try {
            for (ScanResult.InformationElement ie : r.getInformationElements()) {
                ByteBuffer b = ie.getBytes().duplicate();
                byte[] d = new byte[b.remaining()];
                b.get(d);
                out.add(new ApInfo.Element(ie.getId(), d));
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private void renderApCard() {
        if (apBox == null) return;
        apBox.removeAllViews();
        if (connectedBssid.isEmpty()) {
            apText = "";
            return;
        }
        LinearLayout c = a.card();
        c.addView(a.label("Access Point"));
        StringBuilder share = new StringBuilder("Access point\n");
        ScanResult me = connectedScan();
        ApInfo info = me != null ? ApInfo.parse(elements(me)) : null;
        boolean named = info != null && !info.name.isEmpty();
        c.addView(a.text(named ? info.name : "AP name not broadcast", named ? 22 : 16,
                named ? MainActivity.ACCENT : MainActivity.MUTED, true));
        if (named) row(c, share, "Name from", info.nameSource);
        else share.append("  Name: not broadcast\n");
        row(c, share, "BSSID (AP MAC)", connectedBssid);
        row(c, share, "Vendor", vendor(connectedBssid));
        if (info != null) {
            String model = (info.wpsManufacturer + " " + info.wpsModel).trim();
            row(c, share, "Model (WPS)", model);
            if (info.clients >= 0) row(c, share, "Clients on AP", String.valueOf(info.clients));
            if (info.channelUtilPct >= 0) row(c, share, "Channel busy", info.channelUtilPct + "% (reported by the AP)");
        }
        if (me == null) {
            TextView h = a.text(scan.isEmpty() ? "Waiting for a scan to read the AP's name and load…"
                    : "This AP isn't in the latest scan yet; tap Scan now.", 12, MainActivity.MUTED, false);
            h.setPadding(0, a.dp(6), 0, 0);
            c.addView(h);
        } else if (Build.VERSION.SDK_INT < 30) {
            TextView h = a.text("Reading AP names from beacons needs Android 11 or newer.", 12, MainActivity.MUTED, false);
            h.setPadding(0, a.dp(6), 0, 0);
            c.addView(h);
        }

        // Other SSIDs/radios on the same physical AP
        List<String> same = new ArrayList<>();
        for (ScanResult r : scan) {
            if (r == me || r.BSSID == null || !ApInfo.sameAp(r.BSSID, connectedBssid)) continue;
            same.add(String.format(Locale.US, "%s · %s · %d dBm", scanSsid(r), channelText(r.frequency, -1), r.level));
        }
        if (!same.isEmpty()) {
            row(c, share, "Same AP also has", String.join("\n", same));
        }

        // Other APs broadcasting this network (roaming candidates)
        List<String> others = new ArrayList<>();
        for (ScanResult r : scan) {
            if (r.BSSID == null || r.BSSID.equalsIgnoreCase(connectedBssid) || ApInfo.sameAp(r.BSSID, connectedBssid)) continue;
            if (connectedSsid.isEmpty() || !connectedSsid.equals(r.SSID)) continue;
            ApInfo oi = ApInfo.parse(elements(r));
            others.add(String.format(Locale.US, "%4d dBm  ch %-3d %s%s", r.level, channel(r.frequency), r.BSSID,
                    oi.name.isEmpty() ? "" : "  " + oi.name));
            if (others.size() >= 12) break;
        }
        if (!others.isEmpty()) {
            TextView h = a.text("Other APs on " + connectedSsid, 17, MainActivity.TEXT, true);
            h.setPadding(0, a.dp(12), 0, a.dp(2));
            c.addView(h);
            TextView t = a.text(String.join("\n", others), 12, MainActivity.TEXT, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextIsSelectable(true);
            c.addView(t);
            share.append("  Other APs on ").append(connectedSsid).append(":\n");
            for (String o : others) share.append("    ").append(o.trim()).append('\n');
        }
        apBox.addView(c);
        apText = share.toString();
    }

    /** Vendor of the AP's MAC prefix, looked up once per prefix (only the first 3 bytes are sent). */
    private String vendor(String bssid) {
        if (ApInfo.locallyAdministered(bssid)) return "Virtual/randomized BSSID (no vendor prefix)";
        String oui = ApInfo.oui(bssid);
        if (oui.isEmpty()) return "";
        String v = vendorCache.get(oui);
        if (v != null) return v;
        if (vendorPending.add(oui)) {
            new Thread(() -> {
                String result = "";
                try {
                    URL u = new URL("https://api.macvendors.com/" + oui);
                    Network n = wifiNetwork();
                    HttpURLConnection h = (HttpURLConnection) (n != null ? n.openConnection(u) : u.openConnection());
                    h.setConnectTimeout(4000);
                    h.setReadTimeout(4000);
                    h.setRequestProperty("User-Agent", "PortPerson");
                    int code = h.getResponseCode();
                    if (code == 200) {
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
                            String line = r.readLine();
                            result = line == null ? "" : line.trim();
                        }
                    } else if (code == 404) {
                        result = "Unknown (" + oui + ")";
                    }
                    h.disconnect();
                } catch (Exception ignored) {
                }
                final String fr = result;
                post(() -> {
                    vendorPending.remove(oui);
                    vendorCache.put(oui, fr); // empty = lookup failed; don't retry every refresh
                    renderApCard();
                });
            }, "oui-lookup").start();
        }
        return "Looking up…";
    }

    // ------------------------------------------------------------------ scanning

    @SuppressWarnings("deprecation")
    private void requestScan(boolean userAsked) {
        if (wifi == null || !hasPermissions()) return;
        lastScanRequest = System.currentTimeMillis();
        boolean ok;
        try {
            ok = wifi.startScan();
        } catch (SecurityException e) {
            ok = false;
        }
        if (!ok && userAsked) {
            scanStatus.setText("Android limits apps to 4 scans every 2 minutes; showing the latest results. "
                    + "(Developer options → Wi-Fi scan throttling removes the limit.)");
        } else if (ok) {
            scanStatus.setText("Scanning…");
        }
    }

    private void loadScanResults() {
        if (wifi == null || !hasPermissions()) {
            scan = new ArrayList<>();
        } else {
            try {
                scan = new ArrayList<>(wifi.getScanResults());
            } catch (SecurityException e) {
                scan = new ArrayList<>();
            }
        }
        Collections.sort(scan, (x, y) -> Integer.compare(y.level, x.level));
        renderChannels();
        renderNearby();
        renderApCard();
        renderFactors();
        updateTopology();
    }

    private void renderChannels() {
        channelBox.removeAllViews();
        if (scan.isEmpty()) return;
        LinearLayout c = a.card();
        c.addView(a.label("Channels"));
        TextView hint = a.text("Access points per channel (from the latest scan). Your channel is highlighted.", 12, MainActivity.MUTED, false);
        hint.setPadding(0, 0, 0, a.dp(4));
        c.addView(hint);
        // band -> channel -> [count, best rssi]
        Map<String, TreeMap<Integer, int[]>> bands = new TreeMap<>();
        int max = 1;
        for (ScanResult r : scan) {
            String b = band(r.frequency);
            int ch = channel(r.frequency);
            TreeMap<Integer, int[]> m = bands.get(b);
            if (m == null) bands.put(b, m = new TreeMap<>());
            int[] v = m.get(ch);
            if (v == null) m.put(ch, v = new int[]{0, -127});
            v[0]++;
            v[1] = Math.max(v[1], r.level);
            max = Math.max(max, v[0]);
        }
        int myCh = connectedFreq > 0 ? channel(connectedFreq) : -1;
        String myBand = connectedFreq > 0 ? band(connectedFreq) : "";
        for (Map.Entry<String, TreeMap<Integer, int[]>> be : bands.entrySet()) {
            TextView bt = a.text(be.getKey(), 13, MainActivity.TEXT, true);
            bt.setPadding(0, a.dp(8), 0, a.dp(2));
            c.addView(bt);
            for (Map.Entry<Integer, int[]> ce : be.getValue().entrySet()) {
                boolean mine = ce.getKey() == myCh && be.getKey().equals(myBand);
                c.addView(channelRow(ce.getKey(), ce.getValue()[0], ce.getValue()[1], max, mine));
            }
        }
        channelBox.addView(c);
    }

    private View channelRow(int ch, int count, int best, int max, boolean mine) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, a.dp(3), 0, a.dp(3));
        TextView l = a.text("ch " + ch, 13, mine ? MainActivity.BLUE : MainActivity.MUTED, mine);
        l.setLayoutParams(new LinearLayout.LayoutParams(a.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(l);

        LinearLayout track = new LinearLayout(a);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, a.dp(14), 1f);
        tlp.topMargin = a.dp(3);
        track.setLayoutParams(tlp);
        View bar = new View(a);
        bar.setBackground(a.rounded(mine ? MainActivity.BLUE : 0xFF4A5560, a.dp(3)));
        track.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, count));
        if (max - count > 0) {
            View rest = new View(a);
            track.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, max - count));
        }
        row.addView(track);

        TextView r = a.text(String.format(Locale.US, "%d AP%s · %d dBm%s", count, count == 1 ? "" : "s", best, mine ? " ← you" : ""),
                12, mine ? MainActivity.BLUE : MainActivity.TEXT, false);
        r.setPadding(a.dp(8), 0, 0, 0);
        r.setLayoutParams(new LinearLayout.LayoutParams(a.dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(r);
        return row;
    }

    private void renderNearby() {
        nearbyBox.removeAllViews();
        LinearLayout c = a.card();
        Set<String> ssids = new HashSet<>();
        for (ScanResult r : scan) ssids.add(scanSsid(r));
        c.addView(a.label(String.format(Locale.US, "Nearby · %d access points · %d networks", scan.size(), ssids.size())));
        if (scan.isEmpty()) {
            c.addView(a.text(hasPermissions() ? "No scan results yet." : "Allow Wi-Fi access above to see nearby networks.", 14, MainActivity.MUTED, false));
        }
        int shown = 0;
        for (ScanResult r : scan) {
            if (shown++ >= 60) break;
            boolean mine = r.BSSID != null && r.BSSID.equalsIgnoreCase(connectedBssid);
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, a.dp(8), 0, a.dp(6));
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = a.text(scanSsid(r) + (mine ? "  (connected)" : ""), 14, mine ? MainActivity.ACCENT : MainActivity.TEXT, true);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(name);
            top.addView(a.text(r.level + " dBm", 14, signalColor(r.level), true));
            item.addView(top);
            List<String> bits = new ArrayList<>();
            bits.add(r.BSSID);
            bits.add(channelText(r.frequency, Build.VERSION.SDK_INT >= 23 ? r.channelWidth : -1));
            if (Build.VERSION.SDK_INT >= 30) {
                String std = standardName(r.getWifiStandard());
                if (!std.isEmpty()) bits.add(std);
            }
            bits.add(securityFromCaps(r.capabilities));
            item.addView(a.text(String.join("  ·  ", bits), 12, MainActivity.MUTED, false));
            c.addView(item);
        }
        nearbyBox.addView(c);
    }

    // ------------------------------------------------------------------ Wi-Fi test

    private void runTest() {
        if (testing) return;
        final Network n = wifiNetwork();
        if (n == null) return;
        final LinkProperties lp = cm.getLinkProperties(n);
        final boolean defaultIsWifi = n.equals(cm.getActiveNetwork());
        String t = targetEdit.getText().toString().trim();
        final String target = t.isEmpty() ? "google.com" : t;
        testing = true;
        testOk = null;
        testRows.clear();
        hopLines.clear();
        traceNote = "";
        testStatus = "Starting…";
        testButton.setText("Testing…");
        testButton.setEnabled(false);
        testButton.setAlpha(0.6f);
        shareButton.setVisibility(View.GONE);
        renderTest();
        new Thread(() -> runTestWorker(n, lp, defaultIsWifi, target), "wifi-test").start();
    }

    private void post(Runnable r) { ui.post(r); }

    private void addResult(String label, String value) {
        post(() -> {
            testRows.add(new String[]{label, value});
            renderTest();
        });
    }

    private void setTestStatus(String s) {
        post(() -> {
            testStatus = s;
            renderTest();
        });
    }

    private void runTestWorker(Network n, LinkProperties lp, boolean defaultIsWifi, String target) {
        boolean ok = true;
        try {
            String gw = lp == null ? "" : gateway(lp);
            // Gateway
            if (!gw.isEmpty() && defaultIsWifi) {
                setTestStatus("Pinging the gateway " + gw + "…");
                PingStats p = ping(gw, 3, -1);
                addResult("Ping gateway", p.summary());
            }
            // DNS (bound to Wi-Fi)
            setTestStatus("Resolving " + target + "…");
            String ip = null;
            long t0 = System.nanoTime();
            try {
                for (InetAddress addr : n.getAllByName(target)) {
                    if (addr instanceof Inet4Address) {
                        ip = addr.getHostAddress();
                        break;
                    }
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                boolean literal = NetworkTester.ipToIntOrZero(target) != 0;
                addResult("DNS lookup", ip == null ? "✗ no IPv4 address for " + target
                        : literal ? "Skipped (target is an IP address)" : target + " → " + ip + "  (" + ms + " ms)");
            } catch (Exception e) {
                ok = false;
                addResult("DNS lookup", "✗ failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
            }
            if (ip == null) ip = "8.8.8.8";
            // TCP connect (bound to Wi-Fi)
            setTestStatus("Connecting to " + ip + ":443…");
            int okCount = 0;
            double sum = 0;
            for (int i = 0; i < 3; i++) {
                Socket s = new Socket();
                try {
                    n.bindSocket(s);
                    long s0 = System.nanoTime();
                    s.connect(new InetSocketAddress(ip, 443), 3000);
                    sum += (System.nanoTime() - s0) / 1e6;
                    okCount++;
                } catch (Exception ignored) {
                } finally {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (okCount == 0) ok = false;
            addResult("TCP connect :443", okCount == 0 ? "✗ no connection (3 tries)"
                    : String.format(Locale.US, "%d/3 connected · avg %.0f ms", okCount, sum / okCount));
            if (!defaultIsWifi) {
                post(() -> traceNote = "Ping and traceroute skipped: Wi-Fi isn't the phone's default network.");
                return;
            }
            // Ping target
            setTestStatus("Pinging " + ip + "…");
            PingStats p = ping(ip, TEST_PING_COUNT, -1);
            if (p.received == 0) ok = false;
            addResult("Ping " + ip, p.summary());
            // Traceroute
            setTestStatus("Tracing the route to " + ip + "…");
            traceroute(ip);
        } finally {
            final boolean fOk = ok;
            post(() -> {
                testing = false;
                testOk = fOk;
                testStatus = "";
                testButton.setText("Run Wi-Fi Test");
                testButton.setEnabled(true);
                testButton.setAlpha(1f);
                shareButton.setVisibility(View.VISIBLE);
                renderTest();
                saveTest(fOk);
            });
        }
    }

    /** Saves the finished Wi-Fi test to History. */
    private void saveTest(boolean ok) {
        a.saveToHistory(entry(ok, false));
    }

    /** The current Wi-Fi results as a History entry (also used for image sharing). */
    private HistoryStore.Entry entry(Boolean ok, boolean withSpeed) {
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.type = HistoryStore.WIFI;
        ScanResult me = connectedScan();
        ApInfo info = me != null ? ApInfo.parse(elements(me)) : null;
        String ssid = connectedSsid.isEmpty() ? "Wi-Fi" : connectedSsid;
        e.title = info != null && !info.name.isEmpty() ? ssid + " · " + info.name : ssid;
        java.util.List<String> parts = new ArrayList<>();
        if (curRssi > -127) parts.add(curRssi + " dBm");
        if (connectedFreq > 0) parts.add("ch " + channel(connectedFreq) + " (" + band(connectedFreq) + ")");
        if (ok != null) parts.add(ok ? "Internet OK" : "Problems found");
        for (String[] r : testRows) {
            if (r[0].startsWith("Ping ") && !r[0].equals("Ping gateway") && r[1].contains("avg ")) {
                String avg = r[1].substring(r[1].indexOf("avg ") + 4);
                parts.add("ping " + avg.split(" / ")[0] + " ms");
            }
        }
        e.summary = String.join("  ·  ", parts);
        e.status = ok != null && !ok ? HistoryStore.FAIL : curRssi < -75 ? HistoryStore.WARN : HistoryStore.OK;
        e.report = report(withSpeed);
        return e;
    }

    private void traceroute(String ip) {
        ExecutorService names = Executors.newFixedThreadPool(4);
        int silent = 0;
        boolean reached = false;
        try {
            for (int ttl = 1; ttl <= MAX_HOPS && visibleOrTesting(); ttl++) {
                String hop = null;
                boolean hopReached = false;
                for (int attempt = 0; attempt < 2 && hop == null; attempt++) {
                    PingStats p = ping(ip, 1, ttl);
                    if (p.received > 0) {
                        hop = ip;
                        hopReached = true;
                    } else if (p.from != null) {
                        hop = p.from;
                    }
                }
                final int fTtl = ttl;
                if (hop == null) {
                    silent++;
                    post(() -> {
                        hopLines.put(fTtl, String.format(Locale.US, "%2d  *  (no reply)", fTtl));
                        renderTest();
                    });
                    if (silent >= MAX_SILENT_HOPS) {
                        post(() -> traceNote = "Stopped after " + MAX_SILENT_HOPS + " hops in a row without a reply.");
                        break;
                    }
                    continue;
                }
                silent = 0;
                PingStats rtt = hopReached ? ping(ip, 1, -1) : ping(hop, 1, -1);
                final String fHop = hop;
                final String rttText = rtt.received > 0 ? String.format(Locale.US, "%.1f ms", rtt.avg) : "(no echo reply)";
                post(() -> {
                    hopLines.put(fTtl, String.format(Locale.US, "%2d  %s  %s", fTtl, fHop, rttText));
                    renderTest();
                });
                // Reverse DNS in the background, capped at 1.5 s
                Future<String> f = names.submit(() -> InetAddress.getByName(fHop).getCanonicalHostName());
                names.submit(() -> {
                    try {
                        String name = f.get(1500, TimeUnit.MILLISECONDS);
                        if (name != null && !name.equals(fHop)) {
                            post(() -> {
                                hopLines.put(fTtl, String.format(Locale.US, "%2d  %s (%s)  %s", fTtl, name, fHop, rttText));
                                renderTest();
                            });
                        }
                    } catch (Exception ignored) {
                        f.cancel(true);
                    }
                });
                if (hopReached) {
                    reached = true;
                    final int hops = ttl;
                    post(() -> traceNote = "Reached " + ip + " in " + hops + " hops.");
                    break;
                }
            }
            if (!reached) post(() -> {
                if (traceNote.isEmpty()) traceNote = "Did not reach " + ip + ".";
            });
        } finally {
            names.shutdown();
            try {
                names.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private boolean visibleOrTesting() { return testing; }

    /** Result of one run of the system ping tool. */
    static final class PingStats {
        int sent, received;
        double min = -1, avg = -1, max = -1;
        String from; // source of an ICMP error (e.g. "Time to live exceeded")

        String summary() {
            if (received == 0) return "✗ no reply (" + sent + " sent)";
            return String.format(Locale.US, "%d/%d replies · min %.1f / avg %.1f / max %.1f ms", received, sent, min, avg, max);
        }
    }

    private static final Pattern P_STATS = Pattern.compile("(\\d+) packets transmitted, (\\d+) (?:packets )?received");
    private static final Pattern P_RTT = Pattern.compile("= ([\\d.]+)/([\\d.]+)/([\\d.]+)");
    private static final Pattern P_TIME = Pattern.compile("time[=<]([\\d.]+) ?ms");
    private static final Pattern P_FROM = Pattern.compile("^From ([0-9.]+)");

    /**
     * Runs Android's ping tool (ICMP via unprivileged ping sockets). ttl > 0 sets the IP TTL, which is
     * how the traceroute finds each hop ("From x.x.x.x ... Time to live exceeded").
     */
    static PingStats ping(String host, int count, int ttl) {
        PingStats s = new PingStats();
        s.sent = count;
        List<String> cmd = new ArrayList<>();
        cmd.add(new java.io.File("/system/bin/ping").exists() ? "/system/bin/ping" : "ping");
        cmd.add("-n");
        cmd.add("-c");
        cmd.add(String.valueOf(count));
        cmd.add("-W");
        cmd.add("1");
        if (ttl > 0) {
            cmd.add("-t");
            cmd.add(String.valueOf(ttl));
        }
        cmd.add(host);
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process proc = p;
            Thread killer = new Thread(() -> {
                try {
                    if (!proc.waitFor(count + 4L, TimeUnit.SECONDS)) proc.destroy();
                } catch (InterruptedException ignored) {
                }
            });
            killer.setDaemon(true);
            killer.start();
            List<Double> times = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    parsePingLine(line, s, times);
                }
            }
            p.waitFor(2, TimeUnit.SECONDS);
            if (s.received > 0 && s.avg < 0 && !times.isEmpty()) {
                double sum = 0, mn = Double.MAX_VALUE, mx = 0;
                for (double t : times) {
                    sum += t;
                    mn = Math.min(mn, t);
                    mx = Math.max(mx, t);
                }
                s.min = mn;
                s.max = mx;
                s.avg = sum / times.size();
            }
        } catch (Exception e) {
            // ping unavailable: report as no reply
        } finally {
            if (p != null) p.destroy();
        }
        return s;
    }

    /** Parses one line of iputils/toybox ping output. Package-private for tests. */
    static void parsePingLine(String line, PingStats s, List<Double> times) {
        Matcher m = P_STATS.matcher(line);
        if (m.find()) {
            s.sent = Integer.parseInt(m.group(1));
            s.received = Integer.parseInt(m.group(2));
            return;
        }
        m = P_RTT.matcher(line);
        if (line.contains("min/avg/max") && m.find()) {
            s.min = Double.parseDouble(m.group(1));
            s.avg = Double.parseDouble(m.group(2));
            s.max = Double.parseDouble(m.group(3));
            return;
        }
        m = P_FROM.matcher(line.trim());
        if (m.find() && s.from == null) {
            s.from = m.group(1);
            return;
        }
        if (line.contains("bytes from")) {
            m = P_TIME.matcher(line);
            if (m.find()) times.add(Double.parseDouble(m.group(1)));
        }
    }

    private void renderTest() {
        testBox.removeAllViews();
        if (testRows.isEmpty() && hopLines.isEmpty() && testStatus.isEmpty() && testOk == null) return;
        LinearLayout c = a.card();
        c.addView(a.label("Network Test Results"));
        if (testOk != null) {
            c.addView(a.text(testOk ? "✓  Internet reachable over Wi-Fi" : "✗  Problems found", 17,
                    testOk ? MainActivity.ACCENT : MainActivity.ERROR, true));
        }
        for (String[] r : testRows) a.addRow(c, r[0], r[1]);
        if (!hopLines.isEmpty()) {
            TextView h = a.label("Traceroute");
            h.setPadding(0, a.dp(12), 0, 0);
            c.addView(h);
            TextView lines = a.text(String.join("\n", hopLines.values()), 12, MainActivity.TEXT, false);
            lines.setTypeface(Typeface.MONOSPACE);
            lines.setTextIsSelectable(true);
            lines.setPadding(0, a.dp(4), 0, 0);
            c.addView(lines);
        }
        if (!traceNote.isEmpty()) {
            TextView note = a.text(traceNote, 13, MainActivity.MUTED, false);
            note.setPadding(0, a.dp(8), 0, 0);
            c.addView(note);
        }
        if (!testStatus.isEmpty()) {
            TextView st = a.text(testStatus, 13, MainActivity.MUTED, false);
            st.setPadding(0, a.dp(8), 0, 0);
            c.addView(st);
        }
        testBox.addView(c);
    }

    private String report(boolean withSpeed) {
        StringBuilder sb = new StringBuilder(connectionText).append('\n');
        if (!apText.isEmpty()) sb.append(apText).append('\n');
        if (!testRows.isEmpty()) {
            sb.append("Wi-Fi test\n");
            for (String[] r : testRows) sb.append("  ").append(r[0]).append(": ").append(r[1]).append('\n');
        }
        if (!hopLines.isEmpty()) {
            sb.append("\nTraceroute\n");
            for (String l : hopLines.values()) sb.append(l).append('\n');
            if (!traceNote.isEmpty()) sb.append(traceNote).append('\n');
        }
        if (withSpeed) {
            String speed = speedCard.shareText();
            if (!speed.isEmpty()) sb.append('\n').append(speed);
        }
        return sb.toString();
    }

    private void share() {
        HistoryStore.Entry e = entry(testOk, true);
        e.time = System.currentTimeMillis();
        ReportShare.choose(a, e, "PortPerson Wi-Fi results", "PortPerson Wi-Fi results\n\n" + report(true));
    }

    // ------------------------------------------------------------------ UI

    View build() {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = a.dp(16);
        col.setPadding(pad, 0, pad, pad);
        scroll.addView(col);

        topo = new Icons.TopologyView(a);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = a.dp(12);
        col.addView(topo, tlp);

        permCard = a.card();
        permCard.setVisibility(View.GONE);
        col.addView(permCard);

        // Wi-Fi test at the top, speed test at the bottom (same order as the LAN tab)
        col.addView(a.sectionHeader("Network Test", a.text("Ping · DNS · Traceroute", 14, MainActivity.MUTED, false)));
        testButton = a.button("Run Wi-Fi Test", true);
        testButton.setOnClickListener(v -> runTest());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(56));
        blp.topMargin = a.dp(8);
        col.addView(testButton, blp);

        TextView targetLabel = a.text("Internet test target", 13, MainActivity.MUTED, false);
        targetLabel.setPadding(a.dp(4), a.dp(10), 0, a.dp(4));
        col.addView(targetLabel);
        targetEdit = new EditText(a);
        targetEdit.setText("google.com");
        targetEdit.setSingleLine(true);
        targetEdit.setTextColor(MainActivity.TEXT);
        targetEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        targetEdit.setBackground(a.outlined(MainActivity.CARD, a.dp(10)));
        targetEdit.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        col.addView(targetEdit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        testBox = new LinearLayout(a);
        testBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(testBox);

        shareButton = a.button("Share results", false);
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> share());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(48));
        slp.topMargin = a.dp(12);
        col.addView(shareButton, slp);

        factorsRating = a.text("", 17, MainActivity.ACCENT, false);
        col.addView(a.sectionHeader("Wi-Fi Speed Factors", factorsRating));
        factorsBox = new LinearLayout(a);
        factorsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(factorsBox);

        apBox = new LinearLayout(a);
        apBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(apBox);

        connBox = new LinearLayout(a);
        connBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(connBox);

        speedCard = new SpeedTestCard(a, "Wi-Fi", null, card -> {
            Network n = wifiNetwork();
            if (n == null) throw new Exception("Not connected to a Wi-Fi network.");
            return n;
        }, () -> shareButton.setVisibility(View.VISIBLE));
        col.addView(speedCard.build());

        TextView foot = a.text("Wi-Fi details come from Android, so they describe this phone's Wi-Fi radio. "
                + "Signal: −67 dBm or better is good, −67 to −75 fair, below −75 poor.", 12, MainActivity.MUTED, false);
        foot.setPadding(a.dp(2), a.dp(18), 0, 0);
        col.addView(foot);
        col.addView(a.aboutLink());
        updateTopology();
        return scroll;
    }

    /** The Scan tab: channel usage and every nearby access point. */
    View buildScan() {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout sc = new LinearLayout(a);
        sc.setOrientation(LinearLayout.VERTICAL);
        int pad = a.dp(16);
        sc.setPadding(pad, 0, pad, pad);
        scroll.addView(sc);

        permCardScan = a.card();
        permCardScan.setVisibility(View.GONE);
        sc.addView(permCardScan);

        scanButton = a.button("Scan Now", true);
        scanButton.setOnClickListener(v -> requestScan(true));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(52));
        blp.topMargin = a.dp(12);
        sc.addView(scanButton, blp);
        scanStatus = a.text("", 12, MainActivity.MUTED, false);
        scanStatus.setPadding(a.dp(2), a.dp(6), 0, 0);
        sc.addView(scanStatus);

        channelBox = new LinearLayout(a);
        channelBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(channelBox);

        nearbyBox = new LinearLayout(a);
        nearbyBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(nearbyBox);
        return scroll;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation")
    private static String scanSsid(ScanResult r) {
        String s = r.SSID;
        return s == null || s.isEmpty() ? "(hidden network)" : s;
    }

    static String cleanSsid(String s) {
        if (s == null || "<unknown ssid>".equals(s)) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    static String band(int freq) {
        if (freq >= 5925) return "6 GHz";
        if (freq >= 4900) return "5 GHz";
        return "2.4 GHz";
    }

    static int channel(int freq) {
        if (freq == 2484) return 14;
        if (freq >= 2412 && freq < 2484) return (freq - 2407) / 5;
        if (freq == 5935) return 2;
        if (freq >= 5955) return (freq - 5950) / 5;
        if (freq >= 4900 && freq < 5925) return (freq - 5000) / 5;
        return 0;
    }

    static String channelText(int freq, int width) {
        if (freq <= 0) return "";
        String w;
        switch (width) {
            case 0: w = "20 MHz"; break;
            case 1: w = "40 MHz"; break;
            case 2: w = "80 MHz"; break;
            case 3: w = "160 MHz"; break;
            case 4: w = "80+80 MHz"; break;
            case 5: w = "320 MHz"; break;
            default: w = "";
        }
        return "ch " + channel(freq) + " (" + band(freq) + ")" + (w.isEmpty() ? "" : " · " + w);
    }

    static String standardName(int std) {
        switch (std) {
            case 1: return "802.11a/b/g";
            case 4: return "Wi-Fi 4 (802.11n)";
            case 5: return "Wi-Fi 5 (802.11ac)";
            case 6: return "Wi-Fi 6 (802.11ax)";
            case 7: return "802.11ad";
            case 8: return "Wi-Fi 7 (802.11be)";
            default: return "";
        }
    }

    static String securityName(int t) {
        switch (t) {
            case 0: return "Open";
            case 1: return "WEP";
            case 2: return "WPA/WPA2-Personal";
            case 3: return "WPA2-Enterprise";
            case 4: return "WPA3-Personal (SAE)";
            case 5: return "WPA3-Enterprise 192-bit";
            case 6: return "Enhanced Open (OWE)";
            case 7: return "WAPI-PSK";
            case 8: return "WAPI-Cert";
            case 9: return "WPA3-Enterprise";
            case 10: return "OSEN";
            case 11: return "Passpoint R1/R2";
            case 12: return "Passpoint R3";
            case 13: return "DPP";
            default: return "";
        }
    }

    static String securityFromCaps(String caps) {
        if (caps == null) return "";
        String c = caps.toUpperCase(Locale.US);
        boolean eap = c.contains("EAP");
        if (c.contains("SAE") && c.contains("PSK")) return "WPA2/WPA3";
        if (c.contains("SAE")) return "WPA3";
        if (c.contains("OWE")) return "OWE";
        if (eap && (c.contains("SUITE_B") || c.contains("SUITE-B"))) return "WPA3-Ent 192";
        if (eap) return c.contains("RSN") || c.contains("WPA2") ? "WPA2-Ent" : "WPA-Ent";
        if (c.contains("WPA2") || c.contains("RSN-PSK")) return "WPA2";
        if (c.contains("WPA-PSK")) return "WPA";
        if (c.contains("WEP")) return "WEP";
        return "Open";
    }

    static String signalLabel(int rssi) {
        if (rssi >= -60) return "Good";
        if (rssi >= -70) return "Fair";
        return "Poor";
    }

    static int signalColor(int rssi) {
        if (rssi >= -60) return MainActivity.ACCENT;
        if (rssi >= -70) return MainActivity.WARN;
        return MainActivity.ERROR;
    }

    private static String duration(long s) {
        if (s >= 86400) return (s / 86400) + " d" + (s % 86400 >= 3600 ? " " + (s % 86400) / 3600 + " h" : "");
        if (s >= 3600) return (s / 3600) + " h " + (s % 3600) / 60 + " min";
        if (s >= 60) return (s / 60) + " min";
        return s + " s";
    }
}
