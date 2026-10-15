package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** Runs SpeedTest against a local server that mimics speed.cloudflare.com's endpoints. */
public class SpeedTestTest {

    static final class Rec implements SpeedTest.Listener {
        SpeedTest.Meta meta;
        double latency = -1, jitter = -1, down = -1, up = -1;
        String error;
        int progress;
        final List<String> phases = new ArrayList<>();
        public void onPhase(String p) { phases.add(p); }
        public void onMeta(SpeedTest.Meta m) { meta = m; }
        public void onLatency(double m, double j) { latency = m; jitter = j; }
        public void onProgress(String p, double mbps) { progress++; }
        public void onResult(String p, double mbps) { if (SpeedTest.DOWNLOAD.equals(p)) down = mbps; else up = mbps; }
        public void onError(String m) { error = m; }
    }

    /**
     * A minimal HTTP/1.1 server on plain sockets (Android unit tests can't use com.sun.net.httpserver)
     * that mimics speed.cloudflare.com's /meta, /__down and /__up endpoints.
     */
    static final class MiniServer implements Closeable {
        final ServerSocket ss;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final AtomicLong uploaded;

        MiniServer(AtomicLong uploaded) throws IOException {
            this.uploaded = uploaded;
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            pool.execute(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        pool.execute(() -> handle(s));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        int port() { return ss.getLocalPort(); }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.write(c);
            if (c == -1 && b.size() == 0) return null;
            return b.toString("US-ASCII");
        }

        private void handle(Socket s) {
            try (Socket sock = s) {
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                String request = readLine(in);
                if (request == null) return;
                long contentLength = 0;
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    if (h.toLowerCase().startsWith("content-length:")) contentLength = Long.parseLong(h.substring(15).trim());
                }
                String path = request.split(" ")[1];
                if (path.startsWith("/meta")) {
                    byte[] b = ("{\"clientIp\":\"203.0.113.7\",\"asOrganization\":\"Example ISP\",\"colo\":\"DEN\","
                            + "\"city\":\"Denver\",\"country\":\"US\"}").getBytes(StandardCharsets.UTF_8);
                    header(out, b.length);
                    out.write(b);
                } else if (path.startsWith("/__down")) {
                    long n = Long.parseLong(path.substring(path.indexOf('=') + 1));
                    header(out, n);
                    byte[] buf = new byte[65536];
                    for (long left = n; left > 0; left -= buf.length) {
                        out.write(buf, 0, (int) Math.min(buf.length, left));
                        Thread.sleep(1); // throttle a little so the test isn't purely memory speed
                    }
                } else if (path.startsWith("/__up")) {
                    byte[] buf = new byte[65536];
                    long left = contentLength;
                    while (left > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) break;
                        uploaded.addAndGet(r);
                        left -= r;
                    }
                    header(out, 0);
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.flush();
            } catch (Exception ignored) {
                // client went away (normal when a test stops a transfer)
            }
        }

        private static void header(OutputStream out, long len) throws IOException {
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + len + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws IOException {
            ss.close();
            pool.shutdownNow();
        }
    }

    static SpeedTest.Config fast(int port) {
        SpeedTest.Config c = new SpeedTest.Config();
        c.baseUrl = "http://127.0.0.1:" + port;
        c.durationMs = 2000;
        c.warmupMs = 300;
        c.downChunk = 5_000_000;
        c.upChunk = 1_000_000;
        c.latencySamples = 5;
        return c;
    }

    @Test
    public void fullRun() throws Exception {
        AtomicLong uploaded = new AtomicLong();
        try (MiniServer s = new MiniServer(uploaded)) {
            Rec r = new Rec();
            SpeedTest t = new SpeedTest(u -> (HttpURLConnection) u.openConnection(), fast(s.port()), r);
            t.run();
            assertNull(r.error);
            assertNotNull(r.meta);
            assertEquals("DEN", r.meta.colo);
            assertEquals("Example ISP", r.meta.isp);
            assertEquals("203.0.113.7", r.meta.clientIp);
            assertTrue("latency measured", r.latency >= 0 && r.jitter >= 0);
            assertTrue("download > 0", r.down > 0);
            assertTrue("upload > 0", r.up > 0);
            assertTrue("server received upload bytes", uploaded.get() > 0);
            assertTrue("live progress reported", r.progress > 4);
            assertTrue(t.bytesUsed() > 0);
            System.out.printf("  local: latency %.2f ms, down %s, up %s, used %s%n", r.latency,
                    SpeedTest.formatMbps(r.down), SpeedTest.formatMbps(r.up), SpeedTest.formatBytes(t.bytesUsed()));
        }
    }

    @Test
    public void unreachableServerReportsError() {
        Rec r = new Rec();
        SpeedTest.Config c = fast(1); // nothing listens on port 1
        c.timeoutMs = 1000;
        new SpeedTest(u -> (HttpURLConnection) u.openConnection(), c, r).run();
        assertNotNull(r.error);
        assertTrue(r.error.startsWith("Couldn't reach"));
        assertEquals(-1.0, r.down, 0);
    }

    @Test
    public void cancelStopsQuickly() throws Exception {
        AtomicLong uploaded = new AtomicLong();
        try (MiniServer s = new MiniServer(uploaded)) {
            Rec r = new Rec();
            SpeedTest.Config c = fast(s.port());
            c.durationMs = 30_000;
            SpeedTest t = new SpeedTest(u -> (HttpURLConnection) u.openConnection(), c, r);
            Thread th = new Thread(t::run);
            th.start();
            Thread.sleep(1500);
            long t0 = System.currentTimeMillis();
            t.cancel();
            th.join(5000);
            assertTrue("stopped", !th.isAlive());
            assertTrue("within 4 s", System.currentTimeMillis() - t0 < 4000);
        }
    }

    @Test
    public void jsonExtraction() {
        String j = "{\"city\":\"Salt Lake City\",\"asOrganization\":\"Comcast \\\"Xfinity\\\"\"}";
        assertEquals("Salt Lake City", SpeedTest.jsonString(j, "city"));
        assertEquals("Comcast \"Xfinity\"", SpeedTest.jsonString(j, "asOrganization"));
        assertEquals("", SpeedTest.jsonString(j, "colo"));
    }
}
