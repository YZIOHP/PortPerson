package com.portfinder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public class HistoryStoreTest {

    static File tempFile() throws Exception {
        File dir = Files.createTempDirectory("hist").toFile();
        return new File(dir, "history.txt");
    }

    static HistoryStore.Entry entry(long t, String type, String title) {
        HistoryStore.Entry e = new HistoryStore.Entry();
        e.time = t;
        e.type = type;
        e.title = title;
        e.summary = "VLAN 110  ·  10.20.30.57/24  ·  Internet OK";
        e.report = "Switch: IDF2\n\tPort: Gi1/0/24\nback\\slash";
        return e;
    }

    @Test
    public void savesAndReloadsWithSpecialCharacters() throws Exception {
        File f = tempFile();
        HistoryStore s = new HistoryStore(f);
        HistoryStore.Entry e = entry(1000, HistoryStore.LAN, "IDF2-3850 · Gi1/0/24");
        e.label = "Rm 204\tjack \"A\"";
        e.status = HistoryStore.WARN;
        s.add(e);
        s.add(entry(2000, HistoryStore.WIFI, "Corp · AP-17"));

        List<HistoryStore.Entry> back = new HistoryStore(f).all();
        assertEquals(2, back.size());
        assertEquals("Corp · AP-17", back.get(0).title);           // newest first
        HistoryStore.Entry r = back.get(1);
        assertEquals("Rm 204\tjack \"A\"", r.label);
        assertEquals("Switch: IDF2\n\tPort: Gi1/0/24\nback\\slash", r.report);
        assertEquals(HistoryStore.WARN, r.status);
        assertEquals("Rm 204\tjack \"A\"  ·  IDF2-3850 · Gi1/0/24", r.displayTitle());
    }

    @Test
    public void renameDeleteClearAndCap() throws Exception {
        File f = tempFile();
        HistoryStore s = new HistoryStore(f);
        for (int i = 0; i < HistoryStore.MAX_ENTRIES + 15; i++) s.add(entry(10_000 + i, HistoryStore.SPEED, "t" + i));
        assertEquals(HistoryStore.MAX_ENTRIES, s.size());
        assertEquals("t" + (HistoryStore.MAX_ENTRIES + 14), s.all().get(0).title); // oldest were dropped
        long t = s.all().get(3).time;
        s.rename(t, "  Lobby  ");
        s.remove(s.all().get(0).time);
        HistoryStore reloaded = new HistoryStore(f);
        assertEquals(HistoryStore.MAX_ENTRIES - 1, reloaded.size());
        assertEquals("Lobby", reloaded.all().get(2).label);
        reloaded.clear();
        assertEquals(0, new HistoryStore(f).size());
    }

    @Test
    public void duplicateTimesStayUnique() throws Exception {
        HistoryStore s = new HistoryStore(tempFile());
        s.add(entry(5000, HistoryStore.LAN, "a"));
        s.add(entry(5000, HistoryStore.LAN, "b"));
        assertTrue(s.all().get(0).time != s.all().get(1).time);
    }

    @Test
    public void survivesCorruptFile() throws Exception {
        File f = tempFile();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(("garbage line\nv1\tnot-a-number\tLAN\t\t\t\t0\t\nv1\t42\tLAN\tlbl\ttitle\tsum\t0\treport\n\u0000\u0001")
                    .getBytes(StandardCharsets.UTF_8));
        }
        HistoryStore s = new HistoryStore(f);
        assertEquals(1, s.size());
        assertEquals("title", s.all().get(0).title);
        assertEquals(0, new HistoryStore(new File(f.getParentFile(), "missing.txt")).size());
    }

    @Test
    public void csvExportIsSpreadsheetSafe() throws Exception {
        HistoryStore s = new HistoryStore(tempFile());
        HistoryStore.Entry e = entry(1700000000000L, HistoryStore.LAN, "=HYPERLINK(\"http://x\")");
        e.label = "Rm 204, jack A";
        s.add(e);
        String csv = s.toCsv();
        String[] lines = csv.split("\r\n");
        assertEquals("Date,Type,Label,Result,Summary,Status", lines[0]);
        assertTrue(lines[1].contains(",LAN,\"Rm 204, jack A\","));
        assertTrue("formula neutralized", lines[1].contains("\"'=HYPERLINK(\"\"http://x\"\")\""));
        assertTrue(lines[1].endsWith(",OK"));
        assertTrue(s.toText().contains("Switch: IDF2"));
    }
}
