package com.portfinder.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a saved test as a shareable report card: header, label, result, summary, status pill, then
 * the full report (sections, key/value rows, traceroute lines). Pure Java: drawing goes through a
 * {@link Painter}, so the same layout is used by the app (Android Canvas) and by tests/previews.
 */
public final class ReportImage {

    public static final int REGULAR = 0, BOLD = 1, MONO = 2;
    public static final int WIDTH = 1080;
    private static final int MAX_BODY_LINES = 90;

    // Theme (same as the app)
    static final int BG = 0xFF1B1D21, CARD = 0xFF22252A, BORDER = 0xFF363A41, TEXT = 0xFFF2F4F5,
            MUTED = 0xFF9AA1A9, BLUE = 0xFF4E8DF5, GREEN = 0xFF5DBB63, AMBER = 0xFFF0B03F, RED = 0xFFE5544B;

    public interface Painter {
        /** Width of text in pixels. */
        float width(String s, float size, int style);
        void text(String s, float x, float baseline, float size, int color, int style);
        void rect(float x, float y, float w, float h, float radius, int fill, int stroke);
        /** icon: "ethernet", "wifi" or "speed". */
        void icon(String icon, float cx, float cy, float size, int color);
        /** The PortPerson app icon (launcher icon) in a size x size box at x, y. */
        default void appIcon(float x, float y, float size) {}
    }

    /** One line of the report body, classified for styling. */
    static final class Line {
        final int kind; // 0 blank, 1 section header, 2 key/value, 3 mono, 4 plain
        final String a, b;

        Line(int kind, String a, String b) {
            this.kind = kind;
            this.a = a;
            this.b = b;
        }
    }

    /** Splits a text report into headers, key/value rows, monospace lines and plain text. */
    static List<Line> parse(String report) {
        List<Line> out = new ArrayList<>();
        if (report == null) return out;
        for (String raw : report.split("\n")) {
            String line = raw.replace("\t", "    ");
            String t = line.trim();
            if (t.isEmpty()) {
                if (!out.isEmpty() && out.get(out.size() - 1).kind != 0) out.add(new Line(0, "", ""));
                continue;
            }
            if (t.matches("^\\d{1,2}\\s{2}.*")) {                       // traceroute hop
                out.add(new Line(3, t, ""));
                continue;
            }
            int c = t.indexOf(": ");
            if (c > 0 && c <= 32) {
                out.add(new Line(2, t.substring(0, c), t.substring(c + 2).trim()));
            } else if (!line.startsWith(" ") && t.length() <= 60 && !t.endsWith(".")) {
                out.add(new Line(1, t.endsWith(":") ? t.substring(0, t.length() - 1) : t, ""));
            } else {
                out.add(new Line(4, t, ""));
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).kind == 0) out.remove(out.size() - 1);
        return out;
    }

    /**
     * Lays out (and, when draw is true, draws) the card. Returns the total height in pixels.
     * Call once with draw=false to measure, then again with draw=true on a canvas of that height.
     */
    public static int layout(Painter p, boolean draw, HistoryStore.Entry e, String dateText) {
        final float pad = 56, w = WIDTH, inner = w - 2 * pad;
        float y = pad;
        if (draw) p.rect(0, 0, w, 100000, 0, BG, 0);

        // Header: app icon + app name; test type icon, type and date on the right
        String icon = HistoryStore.WIFI.equals(e.type) ? "wifi" : HistoryStore.SPEED.equals(e.type) ? "speed" : "ethernet";
        int statusColor = e.status == HistoryStore.OK ? GREEN : e.status == HistoryStore.WARN ? AMBER : RED;
        if (draw) {
            p.appIcon(pad, y, 64);
            p.text("PortPerson", pad + 82, y + 45, 38, TEXT, BOLD);
            String right = e.type + " test  ·  " + dateText;
            float rw = p.width(right, 28, REGULAR);
            p.text(right, w - pad - rw, y + 42, 28, MUTED, REGULAR);
            p.icon(icon, w - pad - rw - 26, y + 32, 34, statusColor);
        }
        y += 64 + 34;

        // Label, result title, summary
        if (!e.label.isEmpty()) {
            y = wrap(p, draw, e.label, pad, y, inner, 34, BLUE, BOLD, 3) + 8;
        }
        String title = e.title.isEmpty() ? e.type + " test" : e.title;
        y = wrap(p, draw, title, pad, y, inner, 52, TEXT, BOLD, 3) + 10;
        if (!e.summary.isEmpty()) y = wrap(p, draw, e.summary, pad, y, inner, 32, MUTED, REGULAR, 4) + 18;

        // Status pill
        String pill = e.status == HistoryStore.OK ? "OK" : e.status == HistoryStore.WARN ? "Warning" : "Failed";
        float pw = p.width(pill, 28, BOLD) + 44;
        if (draw) {
            p.rect(pad, y, pw, 50, 25, statusColor, 0);
            p.text(pill, pad + 22, y + 35, 28, 0xFF111315, BOLD);
        }
        y += 50 + 34;

        // Report card
        List<Line> lines = parse(e.report.isEmpty() ? e.summary : e.report);
        // The label is already shown at the top
        while (!lines.isEmpty() && lines.get(0).kind == 2 && lines.get(0).a.equals("Drop")) lines.remove(0);
        while (!lines.isEmpty() && lines.get(0).kind == 0) lines.remove(0);
        if (!lines.isEmpty()) {
            float cardTop = y, cp = 36, cx = pad + cp, cw = inner - 2 * cp;
            float keyW = cw * 0.38f;
            // Measure first so the card can be drawn behind the text
            float bodyH = body(p, false, lines, cx, 0, cw, keyW);
            if (draw) {
                p.rect(pad, cardTop, inner, bodyH + 2 * cp - 10, 28, CARD, BORDER);
                body(p, true, lines, cx, cardTop + cp, cw, keyW);
            }
            y = cardTop + bodyH + 2 * cp - 10 + 30;
        }

        // Footer
        if (draw) p.text("Generated by PortPerson", pad, y + 26, 26, MUTED, REGULAR);
        y += 26 + pad;
        return (int) Math.ceil(y);
    }

    /** Draws or measures the report body; returns its height. */
    private static float body(Painter p, boolean draw, List<Line> lines, float x, float y0, float w, float keyW) {
        float y = y0;
        int count = 0;
        boolean first = true;
        for (Line l : lines) {
            if (++count > MAX_BODY_LINES) {
                y = wrap(p, draw, "… (see the full text report)", x, y, w, 28, MUTED, REGULAR, 1);
                break;
            }
            switch (l.kind) {
                case 0:
                    y += 18;
                    break;
                case 1:
                    if (!first) y += 8;
                    y = wrap(p, draw, l.a, x, y, w, 34, TEXT, BOLD, 2) + 6;
                    break;
                case 2: {
                    float yk = wrap(p, draw, l.a, x, y, keyW - 16, 29, MUTED, REGULAR, 2);
                    float yv = wrap(p, draw, l.b.isEmpty() ? "—" : l.b, x + keyW, y, w - keyW, 29, TEXT, REGULAR, 4);
                    y = Math.max(yk, yv) + 6;
                    break;
                }
                case 3:
                    y = wrap(p, draw, l.a, x, y, w, 22, TEXT, MONO, 2) + 4;
                    break;
                default:
                    y = wrap(p, draw, l.a, x, y, w, 28, TEXT, REGULAR, 4) + 4;
            }
            first = false;
        }
        return y - y0;
    }

    /** Word-wraps text into at most maxLines lines (the last one ellipsized); returns the y below it. */
    static float wrap(Painter p, boolean draw, String text, float x, float top, float maxW, float size, int color,
                      int style, int maxLines) {
        float lineH = size * 1.32f;
        List<String> rows = breakLines(p, text, maxW, size, style, maxLines);
        float y = top;
        for (String r : rows) {
            if (draw) p.text(r, x, y + size, size, color, style);
            y += lineH;
        }
        return y;
    }

    static List<String> breakLines(Painter p, String text, float maxW, float size, int style, int maxLines) {
        List<String> rows = new ArrayList<>();
        String rest = text.trim();
        while (!rest.isEmpty()) {
            if (rows.size() == maxLines - 1 || p.width(rest, size, style) <= maxW) {
                rows.add(ellipsize(p, rest, maxW, size, style));
                break;
            }
            int n = fit(p, rest, maxW, size, style);
            int brk = rest.lastIndexOf(' ', n);
            if (brk <= 0) brk = n;                      // no space: hard break
            rows.add(rest.substring(0, brk).trim());
            rest = rest.substring(brk).trim();
        }
        return rows;
    }

    private static int fit(Painter p, String s, float maxW, float size, int style) {
        int lo = 1, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.width(s.substring(0, mid), size, style) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return Math.max(1, lo);
    }

    private static String ellipsize(Painter p, String s, float maxW, float size, int style) {
        if (p.width(s, size, style) <= maxW) return s;
        int n = fit(p, s + "…", maxW, size, style);
        n = Math.min(n, s.length());
        while (n > 1 && p.width(s.substring(0, n - 1) + "…", size, style) > maxW) n--;
        return s.substring(0, Math.max(1, n - 1)).trim() + "…";
    }
}
