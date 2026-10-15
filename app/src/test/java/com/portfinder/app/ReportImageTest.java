package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ReportImageTest {

    /** Fake painter: every character is 0.55 x font size wide; records drawn text. */
    static final class Fake implements ReportImage.Painter {
        final List<String> drawn = new ArrayList<>();
        float maxRight;

        public float width(String s, float size, int style) { return s.length() * size * 0.55f; }
        public void text(String s, float x, float b, float size, int color, int style) {
            drawn.add(s);
            maxRight = Math.max(maxRight, x + width(s, size, style));
        }
        public void rect(float x, float y, float w, float h, float r, int fill, int stroke) {}
        public void icon(String i, float cx, float cy, float size, int color) {}
    }

    static HistoryStore.Entry entry(String report) {
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.type = HistoryStore.LAN;
        e.label = "Rm 204 jack A";
        e.title = "IDF2-3850-A · Gi1/0/24";
        e.summary = "VLAN 110  ·  10.20.30.57/24  ·  Internet OK";
        e.report = report;
        return e;
    }

    @Test
    public void classifiesReportLines() {
        List<ReportImage.Line> l = ReportImage.parse(
                "Drop: Rm 204\n\nCDP neighbor (received 2026-10-14 16:21:07)\n  Switch: IDF2\n  Port:   Gi1/0/24\n\n"
                + "Traceroute\n 1  10.0.0.1  1.0 ms\n12  8.8.8.8  9 ms\nReached 8.8.8.8 in 12 hops.\n\n\n");
        assertEquals(2, l.get(0).kind);
        assertEquals("Drop", l.get(0).a);
        assertEquals(0, l.get(1).kind);
        assertEquals(1, l.get(2).kind);
        assertEquals("Port", l.get(4).a);
        assertEquals("Gi1/0/24", l.get(4).b);   // extra alignment spaces trimmed
        assertEquals(1, l.get(6).kind);           // "Traceroute" header
        assertEquals(3, l.get(7).kind);           // hop lines are monospace
        assertEquals(3, l.get(8).kind);
        assertEquals(4, l.get(9).kind);           // sentence
        assertTrue(l.get(l.size() - 1).kind != 0); // no trailing blanks
    }

    @Test
    public void measureMatchesDrawAndStaysInBounds() {
        StringBuilder r = new StringBuilder("CDP neighbor\n");
        r.append("  Software: ").append("Cisco IOS Software, Catalyst L3 Switch Software (CAT3K), Version 16.12.4 ".repeat(6)).append('\n');
        r.append("  Switch: ").append("x".repeat(400)).append('\n'); // unbreakable
        HistoryStore.Entry e = entry(r.toString());
        e.title = "A very long switch name that will need to wrap onto more than one line in the image ".repeat(3);
        int h1 = ReportImage.layout(new Fake(), false, e, "2026-10-14 16:22");
        Fake f = new Fake();
        int h2 = ReportImage.layout(f, true, e, "2026-10-14 16:22");
        assertEquals(h1, h2);
        assertTrue("text stays inside the 1080px card", f.maxRight <= ReportImage.WIDTH - 56 + 1);
        assertTrue("long title ellipsized", f.drawn.stream().anyMatch(s -> s.endsWith("…")));
        assertTrue(f.drawn.contains("Rm 204 jack A"));
        assertTrue(f.drawn.contains("PortPerson"));
    }

    @Test
    public void dropRowNotRepeatedAndLongReportsCapped() {
        StringBuilder r = new StringBuilder("Drop: Rm 204 jack A\n\n");
        for (int i = 0; i < 300; i++) r.append("  Row ").append(i).append(": value\n");
        Fake f = new Fake();
        int h = ReportImage.layout(f, true, entry(r.toString()), "d");
        assertTrue(f.drawn.stream().noneMatch(s -> s.equals("Drop")));
        assertTrue(f.drawn.stream().anyMatch(s -> s.startsWith("… (see the full text report)")));
        assertTrue("height bounded", h < 6000);
    }

    @Test
    public void emptyReportStillRenders() {
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.type = HistoryStore.SPEED;
        Fake f = new Fake();
        int h = ReportImage.layout(f, true, e, "d");
        assertTrue(h > 200);
        assertTrue(f.drawn.contains("Speed test"));   // fallback title
        assertTrue(f.drawn.contains("OK"));
    }
}
