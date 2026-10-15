package com.portfinder.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saved test results, newest first, stored as a small text file in the app's private storage.
 * Pure Java (no Android imports) so it can be unit-tested.
 *
 * File format: one entry per line, fields separated by tabs, with \t \n \r and \\ escaped.
 */
public final class HistoryStore {

    public static final int MAX_ENTRIES = 200;
    public static final String LAN = "LAN", WIFI = "Wi-Fi", SPEED = "Speed";
    public static final int OK = 0, WARN = 1, FAIL = 2;

    public static final class Entry {
        public long time;
        public String type = "";     // LAN, Wi-Fi or Speed
        public String label = "";    // user's name for the drop/location, e.g. "Rm 204-A"
        public String title = "";    // e.g. "IDF2-3850 · Gi1/0/24" or the SSID
        public String summary = "";  // one line, e.g. "VLAN 110 · 10.20.30.57 · Internet OK"
        public String report = "";   // full text, as shared
        public int status = OK;

        public String displayTitle() {
            return label.isEmpty() ? title : label + "  ·  " + title;
        }
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public HistoryStore(File file) {
        this.file = file;
        load();
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    public synchronized int size() { return entries.size(); }

    /** Adds an entry at the top and saves. Returns the stored entry. */
    public synchronized Entry add(Entry e) {
        if (e.time == 0) e.time = System.currentTimeMillis();
        // keep times unique: they double as ids
        for (Entry x : entries) if (x.time == e.time) e.time++;
        entries.add(0, e);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        save();
        return e;
    }

    public synchronized void remove(long time) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).time == time) {
                entries.remove(i);
                save();
                return;
            }
        }
    }

    public synchronized void rename(long time, String label) {
        for (Entry e : entries) {
            if (e.time == time) {
                e.label = label == null ? "" : label.trim();
                save();
                return;
            }
        }
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    // ------------------------------------------------------------------ export

    /** Spreadsheet-friendly export of every saved test. */
    public synchronized String toCsv() {
        StringBuilder sb = new StringBuilder("Date,Type,Label,Result,Summary,Status\r\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        for (Entry e : entries) {
            sb.append(csv(f.format(new Date(e.time)))).append(',')
              .append(csv(e.type)).append(',')
              .append(csv(e.label)).append(',')
              .append(csv(e.title)).append(',')
              .append(csv(e.summary)).append(',')
              .append(csv(e.status == OK ? "OK" : e.status == WARN ? "Warning" : "Failed")).append("\r\n");
        }
        return sb.toString();
    }

    /** Every saved report, one after another, for sharing as plain text. */
    public synchronized String toText() {
        StringBuilder sb = new StringBuilder("PortPerson test history\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (Entry e : entries) {
            sb.append("\n==== ").append(f.format(new Date(e.time))).append(" · ").append(e.type);
            if (!e.label.isEmpty()) sb.append(" · ").append(e.label);
            sb.append(" ====\n").append(e.report.isEmpty() ? e.summary : e.report).append('\n');
        }
        return sb.toString();
    }

    static String csv(String s) {
        if (s == null) return "";
        // Neutralize spreadsheet formulas in user/network-supplied text
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        entries.clear();
        if (file == null || !file.exists()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] all = readAll(in);
            for (String line : new String(all, StandardCharsets.UTF_8).split("\n")) {
                Entry e = decode(line);
                if (e != null) entries.add(e);
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable file: start fresh rather than crash
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.time, a.time));
    }

    private void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(encode(e)).append('\n');
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) { // some file systems won't rename over an existing file
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String encode(Entry e) {
        return "v1\t" + e.time + "\t" + esc(e.type) + "\t" + esc(e.label) + "\t" + esc(e.title) + "\t"
                + esc(e.summary) + "\t" + e.status + "\t" + esc(e.report);
    }

    static Entry decode(String line) {
        if (line == null || !line.startsWith("v1\t")) return null;
        String[] f = line.split("\t", -1);
        if (f.length < 8) return null;
        try {
            Entry e = new Entry();
            e.time = Long.parseLong(f[1]);
            e.type = unesc(f[2]);
            e.label = unesc(f[3]);
            e.title = unesc(f[4]);
            e.summary = unesc(f[5]);
            e.status = Integer.parseInt(f[6]);
            e.report = unesc(f[7]);
            return e;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\t': sb.append("\\t"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
