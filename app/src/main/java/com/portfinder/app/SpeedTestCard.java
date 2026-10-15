package com.portfinder.app;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * A "Speed test (Cloudflare)" card. The same card is used on the LAN and WLAN tabs; each supplies the
 * Android {@link Network} to test over, and every connection is opened on that network.
 */
final class SpeedTestCard implements SpeedTest.Listener {

    /** Finds the network to test. Runs on a background thread; throw with a user-facing message on failure. */
    interface NetworkSource {
        Network acquire(SpeedTestCard card) throws Exception;
    }

    private final MainActivity a;
    private final String networkLabel;
    private final Runnable beforeStart;
    private final NetworkSource source;
    private final Runnable onFinished;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout card, rows;
    private TextView big, phaseText, noteText;
    private Button button;

    private volatile SpeedTest test;
    private boolean running;
    private double latency = -1, jitter = -1, down = -1, up = -1;
    private SpeedTest.Meta meta;
    private String error = "";
    private long bytesUsed;
    private String via = "";

    SpeedTestCard(MainActivity a, String networkLabel, Runnable beforeStart, NetworkSource source, Runnable onFinished) {
        this.a = a;
        this.networkLabel = networkLabel;
        this.beforeStart = beforeStart;
        this.source = source;
        this.onFinished = onFinished;
    }

    View build() {
        LinearLayout section = new LinearLayout(a);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView right = a.text("Cloudflare", 15, MainActivity.BLUE, false);
        section.addView(a.sectionHeader("Speed Test", right));

        button = a.button("Start Speed Test", true);
        button.setOnClickListener(v -> {
            if (running) stop();
            else start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(56));
        lp.topMargin = a.dp(8);
        section.addView(button, lp);

        card = a.card();
        card.setVisibility(View.GONE);
        big = a.text("", 32, MainActivity.TEXT, true);
        big.setVisibility(View.GONE);
        card.addView(big);
        phaseText = a.text("", 14, MainActivity.MUTED, false);
        phaseText.setPadding(0, a.dp(2), 0, a.dp(4));
        card.addView(phaseText);
        rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        card.addView(rows);
        section.addView(card);

        noteText = a.text("Download, upload and latency to the nearest Cloudflare server over " + networkLabel
                + " (speed.cloudflare.com). Uses roughly 50 MB to 1 GB of data, depending on speed.", 12, MainActivity.MUTED, false);
        noteText.setPadding(a.dp(2), a.dp(8), 0, 0);
        section.addView(noteText);
        return section;
    }

    boolean isRunning() { return running; }

    /** Called by a NetworkSource to show progress while it waits for the network. */
    void status(String s) {
        ui.post(() -> {
            if (running) phaseText.setText(s);
        });
    }

    private void start() {
        if (running) return;
        if (beforeStart != null) beforeStart.run();
        running = true;
        latency = jitter = down = up = -1;
        meta = null;
        error = "";
        bytesUsed = 0;
        via = "";
        button.setText("Stop");
        card.setVisibility(View.VISIBLE);
        big.setVisibility(View.GONE);
        phaseText.setText("Starting…");
        phaseText.setTextColor(MainActivity.MUTED);
        render();
        new Thread(() -> {
            Network n;
            try {
                n = source.acquire(this);
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                ui.post(() -> finish(msg));
                return;
            }
            if (!running) return;
            final Network net = n;
            status("Checking the route over " + networkLabel + "…");
            try {
                final String path = verifyPath(net);
                ui.post(() -> {
                    via = path;
                    render();
                });
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                ui.post(() -> finish(msg));
                return;
            }
            if (!running) return;
            SpeedTest t = new SpeedTest(u -> (HttpURLConnection) net.openConnection(u), new SpeedTest.Config(), this);
            test = t;
            t.run();
            ui.post(() -> {
                bytesUsed = t.bytesUsed();
                finish(t.isCancelled() ? "Stopped." : null);
            });
        }, "speedtest").start();
    }

    void stop() {
        SpeedTest t = test;
        if (t != null) t.cancel();
        if (running) finish("Stopped.");
    }

    private void finish(String message) {
        if (!running) return;
        running = false;
        test = null;
        button.setText("Start Speed Test");
        if (message != null && error.isEmpty() && !message.equals("Stopped.")) error = message;
        if (!error.isEmpty()) {
            phaseText.setText(error);
            phaseText.setTextColor(MainActivity.ERROR);
        } else if (message != null) {
            phaseText.setText(message);
            phaseText.setTextColor(MainActivity.MUTED);
        } else {
            phaseText.setText("Done. Saved to History.");
            phaseText.setTextColor(MainActivity.ACCENT);
        }
        if (message == null && error.isEmpty() && down >= 0) save();
        big.setVisibility(down >= 0 ? View.VISIBLE : View.GONE);
        if (down >= 0) {
            big.setText("↓ " + SpeedTest.formatMbps(down) + (up >= 0 ? "    ↑ " + SpeedTest.formatMbps(up) : ""));
            big.setTextColor(MainActivity.TEXT);
        }
        render();
        if (onFinished != null) onFinished.run();
    }

    /**
     * Opens a test connection to the speed test server on the chosen network and confirms it really
     * leaves through that interface (its local address belongs to it). Returns "eth0 · 10.20.30.57".
     */
    private String verifyPath(Network net) throws Exception {
        ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties lp = cm == null ? null : cm.getLinkProperties(net);
        if (lp == null) throw new Exception(networkLabel + " isn't connected (no IP settings).");
        InetAddress server;
        try {
            server = net.getByName("speed.cloudflare.com");
        } catch (Exception e) {
            throw new Exception("Couldn't look up the speed test server over " + networkLabel
                    + ". Check DNS and internet access on this connection.");
        }
        try (Socket s = new Socket()) {
            net.bindSocket(s);
            s.connect(new InetSocketAddress(server, 443), 6000);
            InetAddress local = s.getLocalAddress();
            boolean onInterface = false;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress().equals(local)) onInterface = true;
            }
            if (!onInterface) {
                throw new Exception("Safety check failed: the test connection didn't leave through " + networkLabel
                        + " (it used " + local.getHostAddress() + "). The test was cancelled so it can't measure the wrong network.");
            }
            String iface = lp.getInterfaceName();
            return (iface != null ? iface + "  ·  " : "") + local.getHostAddress();
        } catch (java.io.IOException e) {
            throw new Exception("Couldn't reach the speed test server over " + networkLabel + " ("
                    + e.getClass().getSimpleName() + "). Check internet access on this connection.");
        }
    }

    private void render() {
        rows.removeAllViews();
        if (!via.isEmpty()) a.addRow(rows, "Via", networkLabel + "  ·  " + via);
        if (latency >= 0) a.addRow(rows, "Latency", String.format(Locale.US, "%.0f ms  (jitter %.1f ms)", latency, jitter));
        if (down >= 0) a.addRow(rows, "Download", SpeedTest.formatMbps(down));
        if (up >= 0) a.addRow(rows, "Upload", SpeedTest.formatMbps(up));
        if (meta != null) {
            String where = meta.colo + (meta.city.isEmpty() ? "" : " · " + meta.city) + (meta.country.isEmpty() ? "" : ", " + meta.country);
            a.addRow(rows, "Server", where.trim().isEmpty() ? "" : "Cloudflare " + where);
            a.addRow(rows, "ISP", meta.isp);
            a.addRow(rows, "Public IP", meta.clientIp);
        }
        if (!running && bytesUsed > 0) a.addRow(rows, "Data used", SpeedTest.formatBytes(bytesUsed));
    }

    /** Saves a completed speed test to History. */
    private void save() {
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.type = HistoryStore.SPEED;
        e.label = a.speedTestLabel(networkLabel);
        String where = meta != null && !meta.colo.isEmpty() ? "Cloudflare " + meta.colo + (meta.city.isEmpty() ? "" : " (" + meta.city + ")") : "Cloudflare";
        e.title = networkLabel + " → " + where;
        e.summary = "↓ " + SpeedTest.formatMbps(down) + "   ↑ " + SpeedTest.formatMbps(up)
                + (latency >= 0 ? String.format(Locale.US, "   ·   %.0f ms", latency) : "");
        e.status = HistoryStore.OK;
        e.report = shareText();
        a.saveToHistory(e);
    }

    String shareText() {
        if (down < 0 && latency < 0) return "";
        StringBuilder sb = new StringBuilder("Speed test (Cloudflare, over ").append(networkLabel).append(")\n");
        if (!via.isEmpty()) sb.append("  Via: ").append(networkLabel).append(" · ").append(via.replace("  ·  ", " · ")).append('\n');
        if (latency >= 0) sb.append(String.format(Locale.US, "  Latency: %.0f ms (jitter %.1f ms)%n", latency, jitter));
        if (down >= 0) sb.append("  Download: ").append(SpeedTest.formatMbps(down)).append('\n');
        if (up >= 0) sb.append("  Upload: ").append(SpeedTest.formatMbps(up)).append('\n');
        if (meta != null) {
            sb.append("  Server: Cloudflare ").append(meta.colo).append(meta.city.isEmpty() ? "" : " (" + meta.city + ")").append('\n');
            if (!meta.isp.isEmpty()) sb.append("  ISP: ").append(meta.isp).append('\n');
            if (!meta.clientIp.isEmpty()) sb.append("  Public IP: ").append(meta.clientIp).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ SpeedTest.Listener (worker thread)

    @Override
    public void onPhase(String phase) {
        ui.post(() -> {
            if (!running) return;
            phaseText.setText(phase);
            phaseText.setTextColor(MainActivity.MUTED);
        });
    }

    @Override
    public void onMeta(SpeedTest.Meta m) {
        ui.post(() -> {
            meta = m;
            render();
        });
    }

    @Override
    public void onLatency(double medianMs, double jitterMs) {
        ui.post(() -> {
            latency = medianMs;
            jitter = jitterMs;
            render();
        });
    }

    @Override
    public void onProgress(String phase, double mbps) {
        ui.post(() -> {
            if (!running) return;
            big.setVisibility(View.VISIBLE);
            big.setText((SpeedTest.DOWNLOAD.equals(phase) ? "↓ " : "↑ ") + SpeedTest.formatMbps(mbps));
            big.setTextColor(MainActivity.BLUE);
        });
    }

    @Override
    public void onResult(String phase, double mbps) {
        ui.post(() -> {
            if (SpeedTest.DOWNLOAD.equals(phase)) down = mbps;
            else up = mbps;
            render();
        });
    }

    @Override
    public void onError(String message) {
        ui.post(() -> error = message);
    }
}
