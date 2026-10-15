package com.portfinder.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internet speed test against Cloudflare's speed test endpoints (the ones speed.cloudflare.com uses):
 * latency/jitter, multi-stream download and multi-stream upload. Pure Java: the caller supplies an
 * {@link Opener} that creates connections on the network under test (Wi-Fi or Ethernet), so the
 * traffic never silently goes over another interface.
 */
public final class SpeedTest {

    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    /** Callbacks arrive on the thread that called {@link #run}. */
    public interface Listener {
        void onPhase(String phase);
        void onMeta(Meta meta);
        void onLatency(double medianMs, double jitterMs);
        void onProgress(String phase, double mbps);
        void onResult(String phase, double mbps);
        void onError(String message);
    }

    public static final class Meta {
        public String colo = "", city = "", country = "", isp = "", clientIp = "";
    }

    public static final class Config {
        public String baseUrl = "https://speed.cloudflare.com";
        public int streams = 4;
        public long durationMs = 10_000;
        public long warmupMs = 1_500;   // excluded from the result (TCP slow start)
        public long downChunk = 25_000_000;
        public int upChunk = 4_000_000;
        public int latencySamples = 10;
        public int timeoutMs = 8_000;
    }

    public static final String DOWNLOAD = "Download", UPLOAD = "Upload";

    private final Opener opener;
    private final Config cfg;
    private final Listener listener;
    private volatile boolean cancelled;
    private final AtomicLong totalBytes = new AtomicLong();
    private final List<HttpURLConnection> open = Collections.synchronizedList(new ArrayList<>());

    public SpeedTest(Opener opener, Config cfg, Listener listener) {
        this.opener = opener;
        this.cfg = cfg;
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
        synchronized (open) {
            for (HttpURLConnection c : open) c.disconnect();
        }
    }

    public boolean isCancelled() { return cancelled; }

    /** Bytes moved in both directions so far. */
    public long bytesUsed() { return totalBytes.get(); }

    /** Runs the whole test. Blocking. */
    public void run() {
        try {
            listener.onPhase("Finding the nearest test server…");
            Meta m = meta();
            if (m != null) listener.onMeta(m);
            if (cancelled) return;

            listener.onPhase("Measuring latency…");
            if (!latency()) return;
            if (cancelled) return;

            listener.onPhase("Testing download…");
            double down = transfer(true);
            if (cancelled) return;
            if (down < 0) {
                listener.onError("Download failed: couldn't reach the speed test server.");
                return;
            }
            listener.onResult(DOWNLOAD, down);

            listener.onPhase("Testing upload…");
            double up = transfer(false);
            if (cancelled) return;
            if (up < 0) {
                listener.onError("Upload failed.");
                return;
            }
            listener.onResult(UPLOAD, up);
        } catch (RuntimeException e) {
            if (!cancelled) listener.onError("Speed test error: " + e);
        }
    }

    // ------------------------------------------------------------------ steps

    private Meta meta() {
        try {
            HttpURLConnection c = connect(cfg.baseUrl + "/meta");
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream(), 16_384);
            c.disconnect();
            Meta m = new Meta();
            m.colo = jsonString(body, "colo");
            m.city = jsonString(body, "city");
            m.country = jsonString(body, "country");
            m.isp = jsonString(body, "asOrganization");
            m.clientIp = jsonString(body, "clientIp");
            return m;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean latency() {
        List<Double> samples = new ArrayList<>();
        String err = null;
        for (int i = 0; i <= cfg.latencySamples && !cancelled; i++) {
            try {
                long t0 = System.nanoTime();
                HttpURLConnection c = connect(cfg.baseUrl + "/__down?bytes=0");
                int code = c.getResponseCode();
                readAll(c.getInputStream(), 1024);
                double ms = (System.nanoTime() - t0) / 1e6;
                if (code != 200) {
                    err = "HTTP " + code;
                    continue;
                }
                if (i > 0) samples.add(ms); // the first request includes DNS + TLS setup
            } catch (IOException e) {
                err = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            }
        }
        if (cancelled) return false;
        if (samples.isEmpty()) {
            listener.onError("Couldn't reach the speed test server" + (err != null ? " (" + err + ")" : "") + ".");
            return false;
        }
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        double jitter = 0;
        for (int i = 1; i < samples.size(); i++) jitter += Math.abs(samples.get(i) - samples.get(i - 1));
        if (samples.size() > 1) jitter /= samples.size() - 1;
        listener.onLatency(median, jitter);
        return true;
    }

    /**
     * Runs cfg.streams parallel transfers for cfg.durationMs and returns the throughput in Mbit/s
     * measured after the warm-up period, or -1 if nothing could be transferred.
     */
    private double transfer(boolean download) {
        final AtomicLong bytes = new AtomicLong();
        final AtomicReference<String> failure = new AtomicReference<>();
        final long start = System.nanoTime();
        final long end = start + cfg.durationMs * 1_000_000L;
        final AtomicBoolean stop = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < cfg.streams; i++) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[64 * 1024];
                int failures = 0;
                while (!stop.get() && !cancelled && System.nanoTime() < end && failures < 3) {
                    HttpURLConnection c = null;
                    try {
                        if (download) {
                            c = connect(cfg.baseUrl + "/__down?bytes=" + cfg.downChunk);
                            open.add(c);
                            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                            try (InputStream in = c.getInputStream()) {
                                int n;
                                while (!stop.get() && !cancelled && (n = in.read(buf)) > 0) {
                                    bytes.addAndGet(n);
                                    totalBytes.addAndGet(n);
                                }
                            }
                        } else {
                            c = connect(cfg.baseUrl + "/__up");
                            open.add(c);
                            c.setDoOutput(true);
                            c.setRequestMethod("POST");
                            c.setRequestProperty("Content-Type", "application/octet-stream");
                            c.setFixedLengthStreamingMode(cfg.upChunk);
                            try (OutputStream out = c.getOutputStream()) {
                                int left = cfg.upChunk;
                                while (left > 0) {
                                    if (stop.get() || cancelled) throw new IOException("stopped");
                                    int n = Math.min(buf.length, left);
                                    out.write(buf, 0, n);
                                    left -= n;
                                    bytes.addAndGet(n);
                                    totalBytes.addAndGet(n);
                                }
                            }
                            int code = c.getResponseCode();
                            if (code >= 400) throw new IOException("HTTP " + code);
                            readAll(c.getInputStream(), 4096);
                        }
                        failures = 0;
                    } catch (IOException e) {
                        if (!stop.get() && !cancelled) {
                            failures++;
                            failure.set(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                        }
                    } finally {
                        if (c != null) {
                            open.remove(c);
                            c.disconnect();
                        }
                    }
                }
            }, (download ? "speed-down-" : "speed-up-") + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }

        String phase = download ? DOWNLOAD : UPLOAD;
        long warmEnd = start + cfg.warmupMs * 1_000_000L;
        long baseBytes = -1, baseTime = 0;
        double mbps = -1;
        while (!cancelled) {
            sleep(250);
            long now = System.nanoTime();
            long b = bytes.get();
            if (baseBytes < 0) {
                if (now >= warmEnd) {
                    baseBytes = b;
                    baseTime = now;
                } else if (now > start) {
                    listener.onProgress(phase, b * 8.0 / ((now - start) / 1e9) / 1e6);
                }
            } else if (now > baseTime) {
                mbps = (b - baseBytes) * 8.0 / ((now - baseTime) / 1e9) / 1e6;
                listener.onProgress(phase, mbps);
            }
            boolean anyAlive = false;
            for (Thread t : workers) anyAlive |= t.isAlive();
            if (now >= end || !anyAlive) break;
        }
        stop.set(true);
        synchronized (open) {
            for (HttpURLConnection c : open) c.disconnect();
        }
        for (Thread t : workers) {
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        if (bytes.get() == 0) return -1;
        if (mbps < 0) { // finished before the warm-up ended (very short test or very slow link)
            long el = System.nanoTime() - start;
            mbps = bytes.get() * 8.0 / (el / 1e9) / 1e6;
        }
        return mbps;
    }

    // ------------------------------------------------------------------ helpers

    private HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c = opener.open(new URL(url));
        c.setConnectTimeout(cfg.timeoutMs);
        c.setReadTimeout(cfg.timeoutMs);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "PortPerson/1.0");
        c.setRequestProperty("Accept-Encoding", "identity"); // measure raw bytes, no compression
        return c;
    }

    private static String readAll(InputStream in, int max) throws IOException {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n, total = 0;
        try (InputStream is = in) {
            while ((n = is.read(buf)) > 0) {
                if (total < max) sb.append(new String(buf, 0, Math.min(n, max - total), StandardCharsets.UTF_8));
                total += n;
            }
        }
        return sb.toString();
    }

    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\/", "/") : "";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String formatMbps(double mbps) {
        if (mbps < 0) return "—";
        if (mbps >= 100) return String.format(Locale.US, "%.0f Mbps", mbps);
        if (mbps >= 10) return String.format(Locale.US, "%.1f Mbps", mbps);
        return String.format(Locale.US, "%.2f Mbps", mbps);
    }

    public static String formatBytes(long b) {
        if (b >= 1_000_000_000L) return String.format(Locale.US, "%.2f GB", b / 1e9);
        return String.format(Locale.US, "%.0f MB", b / 1e6);
    }
}
