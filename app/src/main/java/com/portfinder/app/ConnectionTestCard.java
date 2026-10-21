package com.portfinder.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Connection Test: checks whether one destination (IP or hostname) answers on one TCP port, over a
 * chosen network (the Ethernet adapter on the LAN tab, Wi-Fi on the Wi-Fi tab). Runs only when the
 * person taps Test; it never scans. Recent checks are remembered on the phone.
 */
final class ConnectionTestCard {

    /** Finds the network to test over. Background thread; throw with a user-facing message. */
    interface NetworkSource {
        Network acquire(ConnectionTestCard card) throws Exception;
    }

    static final String[][] QUICK = {
            {"Print", "9100"}, {"IPP", "631"}, {"HTTPS", "443"}, {"HTTP", "80"},
            {"SMB", "445"}, {"RDP", "3389"}, {"SSH", "22"}, {"DNS", "53"}};

    static final int TRIES = 3, TIMEOUT_MS = 3000, MAX_RECENT = 8;

    private final MainActivity a;
    private final String networkLabel, prefsKey;
    private final Runnable beforeStart;
    private final NetworkSource source;
    private Function<String, String> nameLookup;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText hostEdit, portEdit;
    private Button testButton;
    private LinearLayout resultCard, recentBox;
    private TextView resultTitle, resultState, phase;
    private LinearLayout resultRows;
    private final List<TextView> chips = new ArrayList<>();
    private volatile boolean running;

    ConnectionTestCard(MainActivity a, String networkLabel, Runnable beforeStart, NetworkSource source) {
        this.a = a;
        this.networkLabel = networkLabel;
        this.prefsKey = "recent_" + networkLabel.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
        this.beforeStart = beforeStart;
        this.source = source;
    }

    /** Optional: names for IPs (e.g. from devices seen during the port test). */
    void setNameLookup(Function<String, String> f) { nameLookup = f; }

    boolean isRunning() { return running; }

    void status(String s) {
        ui.post(() -> { if (running) phase.setText(s); });
    }

    View build() {
        LinearLayout section = new LinearLayout(a);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView intro = a.text("Check whether this " + (networkLabel.equals("Ethernet") ? "drop" : "Wi-Fi network")
                + " can reach a printer, server or website. Runs only when you tap Test, over " + networkLabel + ".", 13, MainActivity.MUTED, false);
        intro.setPadding(a.dp(2), a.dp(12), 0, 0);
        section.addView(intro);

        LinearLayout form = a.card();
        form.addView(fieldLabel("Destination IP or hostname", 0));
        hostEdit = edit("e.g. 10.20.30.44 or portal.example.com", false);
        form.addView(hostEdit, matchWrap());
        form.addView(fieldLabel("Port", 10));
        portEdit = edit("e.g. 9100", true);
        form.addView(portEdit, matchWrap());
        form.addView(fieldLabel("Quick picks", 10));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                String[] q = QUICK[r * 4 + c];
                TextView chip = a.text(q[0] + " " + q[1], 12, MainActivity.TEXT, true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(0, a.dp(7), 0, a.dp(7));
                chip.setBackground(a.outlined(MainActivity.CARD, a.dp(16)));
                chip.setOnClickListener(v -> {
                    portEdit.setText(q[1]);
                    highlight(q[1]);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = c < 3 ? a.dp(6) : 0;
                lp.topMargin = a.dp(6);
                row.addView(chip, lp);
                chips.add(chip);
            }
            form.addView(row);
        }
        testButton = a.button("Test connection", true);
        testButton.setOnClickListener(v -> {
            if (running) stop();
            else start(hostEdit.getText().toString(), portEdit.getText().toString());
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(52));
        blp.topMargin = a.dp(12);
        form.addView(testButton, blp);
        section.addView(form);

        resultCard = a.card();
        resultCard.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        resultTitle = a.text("", 16, MainActivity.TEXT, true);
        head.addView(resultTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resultState = a.text("", 14, MainActivity.MUTED, true);
        head.addView(resultState);
        resultCard.addView(head);
        phase = a.text("", 13, MainActivity.MUTED, false);
        phase.setPadding(0, a.dp(4), 0, 0);
        resultCard.addView(phase);
        resultRows = new LinearLayout(a);
        resultRows.setOrientation(LinearLayout.VERTICAL);
        resultCard.addView(resultRows);
        section.addView(resultCard);

        recentBox = new LinearLayout(a);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        section.addView(recentBox);
        renderRecent();

        TextView note = a.text("Only test devices on networks you own or are authorized to test.", 12, MainActivity.MUTED, false);
        note.setPadding(a.dp(2), a.dp(8), 0, 0);
        section.addView(note);
        return section;
    }

    private TextView fieldLabel(String s, int top) {
        TextView t = a.text(s, 13, MainActivity.MUTED, false);
        t.setPadding(a.dp(2), a.dp(top), 0, a.dp(4));
        return t;
    }

    private EditText edit(String hint, boolean number) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setHintTextColor(0xFF6B7178);
        e.setTextColor(MainActivity.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setBackground(a.outlined(MainActivity.BG, a.dp(10)));
        e.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void highlight(String port) {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = QUICK[i][1].equals(port);
            chips.get(i).setBackground(on ? a.rounded(MainActivity.BLUE, a.dp(16)) : a.outlined(MainActivity.CARD, a.dp(16)));
            chips.get(i).setTextColor(on ? 0xFFFFFFFF : MainActivity.TEXT);
        }
    }

    // ------------------------------------------------------------------ running a check

    /** Validated "host" and port, or an error message. Pure; exposed for tests. */
    static String validate(String host, String port) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return "Enter a destination IP address or hostname.";
        if (h.contains("://")) return "Enter just the hostname (no http://), and put the port in the Port box.";
        if (h.contains(" ") || h.contains("/")) return "That doesn't look like an IP address or hostname.";
        int p;
        try {
            p = Integer.parseInt(port == null ? "" : port.trim());
        } catch (NumberFormatException e) {
            return "Enter a port number, or tap a quick pick.";
        }
        if (p < 1 || p > 65535) return "Ports go from 1 to 65535.";
        return null;
    }

    void start(String hostIn, String portIn) {
        if (running) return;
        String err = validate(hostIn, portIn);
        resultCard.setVisibility(View.VISIBLE);
        resultRows.removeAllViews();
        phase.setVisibility(View.VISIBLE); // hidden after a successful run; show progress or the error again
        if (err != null) {
            resultTitle.setText("Connection test");
            resultState.setText("");
            phase.setText(err);
            phase.setTextColor(MainActivity.ERROR);
            return;
        }
        final String host = hostIn.trim();
        final int port = Integer.parseInt(portIn.trim());
        if (beforeStart != null) beforeStart.run();
        running = true;
        hostEdit.setText(host);
        portEdit.setText(String.valueOf(port));
        highlight(String.valueOf(port));
        testButton.setText("Stop");
        resultTitle.setText(host + " : " + port);
        resultState.setText("Testing…");
        resultState.setTextColor(MainActivity.MUTED);
        phase.setText("Starting…");
        phase.setTextColor(MainActivity.MUTED);
        new Thread(() -> run(host, port), "conn-test").start();
    }

    void stop() {
        running = false;
        ui.post(() -> {
            testButton.setText("Test connection");
            if ("Testing…".contentEquals(resultState.getText() == null ? "" : resultState.getText())) {
                resultState.setText("Stopped");
                phase.setText("");
            }
        });
    }

    private void run(String host, int port) {
        Network net;
        try {
            net = source.acquire(this);
        } catch (Exception e) {
            finish(host, port, "Error", MainActivity.ERROR, e.getMessage() != null ? e.getMessage() : e.toString(), null);
            return;
        }
        if (!running) return;
        status("Looking up " + host + "…");
        InetAddress addr;
        try {
            addr = pick(net.getAllByName(host));
        } catch (UnknownHostException e) {
            finish(host, port, "No DNS", MainActivity.ERROR, "Couldn't find " + host + " in DNS over " + networkLabel
                    + ". Check the name, or try the IP address.", null);
            return;
        } catch (Exception e) {
            finish(host, port, "Error", MainActivity.ERROR, e.toString(), null);
            return;
        }
        List<String[]> rows = new ArrayList<>();
        if (!addr.getHostAddress().equals(host)) rows.add(new String[]{"Address", addr.getHostAddress()});
        String outcome = null, local = "";
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < TRIES && running; i++) {
            status("Connecting to port " + port + " (try " + (i + 1) + " of " + TRIES + ")…");
            long t0 = System.nanoTime();
            try (Socket s = new Socket()) {
                net.bindSocket(s);
                s.connect(new InetSocketAddress(addr, port), TIMEOUT_MS);
                times.add((System.nanoTime() - t0) / 1_000_000);
                if (local.isEmpty()) local = s.getLocalAddress().getHostAddress();
                outcome = "Open";
            } catch (SocketTimeoutException e) {
                if (outcome == null) outcome = "Timed out";
            } catch (ConnectException e) {
                String m = String.valueOf(e.getMessage());
                if (m.contains("ECONNREFUSED") || m.toLowerCase(Locale.US).contains("refused")) {
                    times.add((System.nanoTime() - t0) / 1_000_000);
                    if (!"Open".equals(outcome)) outcome = "Refused";
                } else if (m.contains("ENETUNREACH") || m.contains("EHOSTUNREACH")) {
                    if (outcome == null) outcome = "Unreachable";
                } else if (outcome == null) {
                    outcome = "Timed out";
                }
            } catch (NoRouteToHostException e) {
                if (outcome == null) outcome = "Unreachable";
            } catch (Exception e) {
                if (outcome == null) outcome = "Error: " + e.getClass().getSimpleName();
            }
            if ("Open".equals(outcome) && times.size() >= TRIES) break;
        }
        if (!running) return;
        if (outcome == null) outcome = "Timed out";
        int color;
        String explain;
        switch (outcome) {
            case "Open": color = MainActivity.ACCENT; explain = "Connected · port is open"; break;
            case "Refused": color = MainActivity.WARN; explain = "The device answered but nothing is listening on port " + port + "."; break;
            case "Unreachable": color = MainActivity.ERROR; explain = "No route to the device from this " + networkLabel + " network."; break;
            case "Timed out":
            default:
                if (outcome.startsWith("Error")) {
                    color = MainActivity.ERROR;
                    explain = "The connection failed (" + outcome.substring(outcome.indexOf(':') + 1).trim() + ").";
                    break;
                }
                color = MainActivity.ERROR;
                explain = "No answer within " + TIMEOUT_MS / 1000 + " s. A firewall may be dropping it, or the device is off.";
        }
        rows.add(new String[]{"Result", explain});
        if (!times.isEmpty()) rows.add(new String[]{"Response time", timing(times)});
        String name = nameLookup != null ? nameLookup.apply(addr.getHostAddress()) : null;
        if (name != null && !name.isEmpty()) rows.add(new String[]{"Name", name});
        rows.add(new String[]{"Via", networkLabel + (local.isEmpty() ? "" : "  ·  " + iface(net) + local)});
        String recent = outcome.equals("Open") || outcome.equals("Refused") ? outcome + " · " + (times.isEmpty() ? "" : median(times) + " ms") : outcome;
        finish(host, port, outcome, color, null, rows);
        remember(host, port, outcome.equals("Open") ? 0 : outcome.equals("Refused") ? 1 : 2, recent);
    }

    private String iface(Network net) {
        ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties lp = cm == null ? null : cm.getLinkProperties(net);
        String n = lp == null ? null : lp.getInterfaceName();
        return n == null ? "" : n + "  ·  ";
    }

    private static InetAddress pick(InetAddress[] all) throws UnknownHostException {
        if (all == null || all.length == 0) throw new UnknownHostException();
        for (InetAddress x : all) if (x instanceof Inet4Address) return x;
        return all[0];
    }

    static long median(List<Long> t) {
        List<Long> s = new ArrayList<>(t);
        s.sort(Long::compare);
        return s.get(s.size() / 2);
    }

    static String timing(List<Long> t) {
        StringBuilder sb = new StringBuilder();
        for (Long x : t) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(x);
        }
        return median(t) + " ms  (" + t.size() + (t.size() == 1 ? " try: " : " tries: ") + sb + " ms)";
    }

    private void finish(String host, int port, String state, int color, String message, List<String[]> rows) {
        ui.post(() -> {
            running = false;
            testButton.setText("Test connection");
            resultTitle.setText(host + " : " + port);
            resultState.setText(state);
            resultState.setTextColor(color);
            phase.setText(message == null ? "" : message);
            phase.setTextColor(message == null ? MainActivity.MUTED : MainActivity.ERROR);
            phase.setVisibility(message == null ? View.GONE : View.VISIBLE);
            resultRows.removeAllViews();
            if (rows != null) for (String[] r : rows) a.addRow(resultRows, r[0], r[1]);
            renderRecent();
        });
    }

    // ------------------------------------------------------------------ recent checks

    private SharedPreferences prefs() {
        return a.getSharedPreferences("connection_tests", Context.MODE_PRIVATE);
    }

    /** Stored as lines "host\tport\tstate\ttext"; newest first, de-duplicated by host:port. */
    static String addRecent(String stored, String host, int port, int state, String text) {
        List<String> out = new ArrayList<>();
        out.add(host.replace('\t', ' ') + "\t" + port + "\t" + state + "\t" + text.replace('\t', ' ').replace('\n', ' '));
        if (stored != null) {
            for (String line : stored.split("\n")) {
                String[] p = line.split("\t", -1);
                if (p.length < 4 || (p[0].equals(host) && p[1].equals(String.valueOf(port)))) continue;
                if (out.size() < MAX_RECENT) out.add(line);
            }
        }
        return String.join("\n", out);
    }

    private void remember(String host, int port, int state, String text) {
        ui.post(() -> {
            prefs().edit().putString(prefsKey, addRecent(prefs().getString(prefsKey, ""), host, port, state, text)).apply();
            renderRecent();
        });
    }

    private void renderRecent() {
        recentBox.removeAllViews();
        String stored = prefs().getString(prefsKey, "");
        if (stored == null || stored.isEmpty()) return;
        TextView clear = a.text("Clear", 15, MainActivity.BLUE, false);
        clear.setOnClickListener(v -> {
            prefs().edit().remove(prefsKey).apply();
            renderRecent();
        });
        recentBox.addView(a.sectionHeader("Recent checks", clear));
        LinearLayout c = a.card();
        for (String line : stored.split("\n")) {
            String[] p = line.split("\t", -1);
            if (p.length < 4) continue;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, a.dp(8), 0, a.dp(4));
            int st = p[2].equals("0") ? 0 : p[2].equals("1") ? 1 : 2;
            Icons.IconView ic = new Icons.IconView(a, st == 0 ? Icons.Type.CHECK : st == 1 ? Icons.Type.WARN : Icons.Type.CROSS,
                    st == 0 ? MainActivity.ACCENT : st == 1 ? MainActivity.WARN : MainActivity.ERROR);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(a.dp(20), a.dp(20));
            ilp.rightMargin = a.dp(12);
            row.addView(ic, ilp);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(a.text(p[0], 14, MainActivity.TEXT, true));
            col.addView(a.text("port " + p[1], 12, MainActivity.MUTED, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(a.text(p[3], 12, st == 0 ? MainActivity.ACCENT : st == 1 ? MainActivity.WARN : MainActivity.ERROR, false));
            row.setOnClickListener(v -> start(p[0], p[1]));
            c.addView(row);
        }
        recentBox.addView(c);
    }

    /** Text for a shared report: the last result shown, or "". */
    String shareText() {
        if (resultCard == null || resultCard.getVisibility() != View.VISIBLE || running) return "";
        CharSequence t = resultTitle.getText(), s = resultState.getText();
        if (t == null || s == null || s.length() == 0) return "";
        return "Connection test (" + networkLabel + ")\n  " + t + ": " + s + "\n";
    }
}
