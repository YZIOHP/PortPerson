package com.portfinder.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The History tab: saved LAN, Wi-Fi and speed tests, newest first. */
final class HistoryPanel {

    private final MainActivity a;
    private final HistoryStore store;
    private final Set<Long> expanded = new HashSet<>();
    private LinearLayout list;
    private TextView countText;
    private String filter = "All";
    private final java.util.List<TextView> filterChips = new java.util.ArrayList<>();

    HistoryPanel(MainActivity a, HistoryStore store) {
        this.a = a;
        this.store = store;
    }

    View build() {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = a.dp(16);
        col.setPadding(pad, 0, pad, pad);
        scroll.addView(col);

        countText = a.text("", 14, MainActivity.MUTED, false);
        col.addView(a.sectionHeader("Recent Tests", countText));

        // Filter chips
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, a.dp(6), 0, a.dp(2));
        for (String f : new String[]{"All", HistoryStore.LAN, HistoryStore.WIFI, HistoryStore.SPEED}) {
            TextView chip = a.text(f, 14, MainActivity.TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(a.dp(14), a.dp(7), a.dp(14), a.dp(7));
            chip.setOnClickListener(v -> {
                filter = f;
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = a.dp(8);
            chips.addView(chip, lp);
            filterChips.add(chip);
        }
        col.addView(chips);

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, a.dp(14), 0, 0);
        Button export = a.button("Export CSV", false);
        export.setOnClickListener(v -> exportCsv());
        Button shareAll = a.button("Share all", false);
        shareAll.setOnClickListener(v -> share("PortPerson test history", store.toText()));
        Button clear = a.button("Clear", false);
        clear.setTextColor(MainActivity.ERROR);
        clear.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, a.dp(46), 1f);
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, a.dp(46), 1f);
        mid.leftMargin = a.dp(8);
        mid.rightMargin = a.dp(8);
        actions.addView(export, half);
        actions.addView(shareAll, mid);
        actions.addView(clear, new LinearLayout.LayoutParams(0, a.dp(46), 1f));
        col.addView(actions);

        TextView foot = a.text("Tests are saved automatically on this phone only (the last " + HistoryStore.MAX_ENTRIES
                + "). Tap a test for its full report. Export CSV gives a spreadsheet for documenting drops.", 12, MainActivity.MUTED, false);
        foot.setPadding(a.dp(2), a.dp(14), 0, 0);
        col.addView(foot);
        col.addView(a.aboutLink());
        refresh();
        return scroll;
    }

    /** Rebuilds the list; call after anything is saved. */
    void refresh() {
        if (list == null) return;
        for (TextView chip : filterChips) {
            boolean on = chip.getText().toString().equals(filter);
            chip.setBackground(on ? a.rounded(MainActivity.BLUE, a.dp(16)) : a.outlined(MainActivity.CARD, a.dp(16)));
            chip.setTextColor(on ? 0xFFFFFFFF : MainActivity.TEXT);
        }
        list.removeAllViews();
        List<HistoryStore.Entry> all = store.all();
        int shown = 0;
        for (HistoryStore.Entry e : all) {
            if (!"All".equals(filter) && !filter.equals(e.type)) continue;
            list.addView(entryCard(e));
            shown++;
        }
        countText.setText(all.isEmpty() ? "" : shown + (shown == 1 ? " test" : " tests"));
        if (shown == 0) {
            LinearLayout c = a.card();
            c.addView(a.text(all.isEmpty() ? "No saved tests yet" : "No " + filter + " tests yet", 16, MainActivity.TEXT, true));
            TextView t = a.text("Run a port test, Wi-Fi test or speed test and it will show up here.", 14, MainActivity.MUTED, false);
            t.setPadding(0, a.dp(4), 0, 0);
            c.addView(t);
            list.addView(c);
        }
    }

    private View entryCard(HistoryStore.Entry e) {
        LinearLayout c = a.card();
        boolean open = expanded.contains(e.time);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Icons.Type icon = HistoryStore.WIFI.equals(e.type) ? Icons.Type.WIFI
                : HistoryStore.SPEED.equals(e.type) ? Icons.Type.SPEED : Icons.Type.ETHERNET;
        top.addView(new Icons.IconView(a, icon, MainActivity.statusColor(e.status)), new LinearLayout.LayoutParams(a.dp(30), a.dp(30)));
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(a.dp(12), 0, 0, 0);
        TextView title = a.text(e.displayTitle().isEmpty() ? e.type + " test" : e.displayTitle(), 16, MainActivity.TEXT, true);
        title.setSingleLine(!open);
        titles.addView(title);
        titles.addView(a.text(e.type + "  ·  " + when(e.time), 13, MainActivity.MUTED, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top);

        if (!e.summary.isEmpty()) {
            TextView s = a.text(e.summary, 14, MainActivity.TEXT, false);
            s.setPadding(a.dp(42), a.dp(6), 0, 0);
            c.addView(s);
        }

        if (open) {
            TextView report = a.text(e.report.isEmpty() ? e.summary : e.report, 12, MainActivity.TEXT, false);
            report.setTypeface(Typeface.MONOSPACE);
            report.setTextIsSelectable(true);
            report.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
            report.setBackground(a.outlined(MainActivity.BG, a.dp(8)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = a.dp(10);
            c.addView(report, rlp);

            LinearLayout btns = new LinearLayout(a);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, a.dp(10), 0, 0);
            Button share = a.button("Share", false);
            share.setOnClickListener(v -> ReportShare.choose(a, e,
                    "PortPerson: " + (e.displayTitle().isEmpty() ? e.type : e.displayTitle()), header(e) + e.report));
            Button rename = a.button(e.label.isEmpty() ? "Add label" : "Rename", false);
            rename.setOnClickListener(v -> askLabel(e));
            Button del = a.button("Delete", false);
            del.setTextColor(MainActivity.ERROR);
            del.setOnClickListener(v -> {
                store.remove(e.time);
                expanded.remove(e.time);
                refresh();
            });
            LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, a.dp(42), 1f);
            mid.leftMargin = a.dp(8);
            mid.rightMargin = a.dp(8);
            btns.addView(share, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
            btns.addView(rename, mid);
            btns.addView(del, new LinearLayout.LayoutParams(0, a.dp(42), 1f));
            c.addView(btns);
        }

        c.setOnClickListener(v -> {
            if (!expanded.remove(e.time)) expanded.add(e.time);
            refresh();
        });
        return c;
    }

    private static String when(long t) {
        long now = System.currentTimeMillis();
        if (now - t < DateUtils.DAY_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        return new SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(new Date(t));
    }

    private static String header(HistoryStore.Entry e) {
        StringBuilder sb = new StringBuilder("PortPerson ").append(e.type).append(" test, ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(e.time))).append('\n');
        if (!e.label.isEmpty()) sb.append("Label: ").append(e.label).append('\n');
        return sb.append('\n').toString();
    }

    private void askLabel(HistoryStore.Entry e) {
        EditText input = new EditText(a);
        input.setText(e.label);
        input.setSingleLine(true);
        input.setHint("e.g. Rm 204 jack A");
        int p = a.dp(20);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(p, a.dp(8), p, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle("Label this test")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    store.rename(e.time, input.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClear() {
        if (store.size() == 0) return;
        new AlertDialog.Builder(a)
                .setTitle("Clear all saved tests?")
                .setMessage("This deletes all " + store.size() + " saved tests from this phone. It can't be undone.")
                .setPositiveButton("Clear all", (d, w) -> {
                    store.clear();
                    expanded.clear();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Shares a CSV file of every saved test (opens in Sheets/Excel). */
    private void exportCsv() {
        if (store.size() == 0) return;
        try {
            java.io.File dir = new java.io.File(a.getCacheDir(), "exports");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name = "portperson-tests-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".csv";
            java.io.File f = new java.io.File(dir, name);
            try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM so Excel reads symbols correctly
                out.write(store.toCsv().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_SUBJECT, "PortPerson test history");
            send.putExtra(Intent.EXTRA_STREAM, ExportProvider.uriFor(a, f));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(send, "Export test history"));
        } catch (java.io.IOException ex) {
            share("PortPerson test history (CSV)", store.toCsv());
        }
    }

    private void share(String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        a.startActivity(Intent.createChooser(send, "Share"));
    }
}
