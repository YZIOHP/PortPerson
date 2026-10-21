package com.portfinder.app;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * The collapsible Findings card under the port test. Collapsed it's one line with problem/warning
 * badges; tapped open it lists each finding (tap one to jump to its tab). It opens by itself the
 * first time a red problem appears in a test, unless the person has already opened or closed it.
 */
final class FindingsCard {

    private final MainActivity a;
    private final IntConsumer openTab;
    private final LinearLayout card, list;
    private final Icons.IconView icon;
    private final TextView title, summary, chevron;
    private final LinearLayout badges;
    private boolean expanded, userToggled, autoOpened;
    private Findings last;

    FindingsCard(MainActivity a, IntConsumer openTab) {
        this.a = a;
        this.openTab = openTab;
        card = a.card();
        card.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        icon = new Icons.IconView(a, Icons.Type.CHECK, MainActivity.ACCENT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(a.dp(22), a.dp(22));
        ilp.rightMargin = a.dp(10);
        head.addView(icon, ilp);
        title = a.text("Findings", 16, MainActivity.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badges = new LinearLayout(a);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(badges);
        chevron = a.text("▾", 16, MainActivity.MUTED, false);
        chevron.setPadding(a.dp(8), 0, 0, 0);
        head.addView(chevron);
        card.addView(head);

        summary = a.text("", 12, MainActivity.MUTED, false);
        summary.setPadding(a.dp(32), a.dp(4), 0, 0);
        card.addView(summary);

        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setVisibility(View.GONE);
        card.addView(list);

        card.setOnClickListener(v -> {
            userToggled = true;
            setExpanded(!expanded);
        });
    }

    View view() { return card; }

    /** New test: back to collapsed, and allowed to auto-open again. */
    void reset() {
        userToggled = false;
        autoOpened = false;
        last = null;
        setExpanded(false);
        card.setVisibility(View.GONE);
    }

    void show(Findings f) {
        last = f;
        if (f == null || (f.items.isEmpty() && f.passed.isEmpty())) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        int bad = f.count(Findings.BAD), warn = f.count(Findings.WARN);
        badges.removeAllViews();
        if (f.items.isEmpty()) {
            icon.set(Icons.Type.CHECK, MainActivity.ACCENT);
            title.setText("No problems found");
            TextView n = a.text(f.passed.size() + " checks passed", 12, MainActivity.MUTED, false);
            badges.addView(n);
            summary.setVisibility(View.GONE);
        } else {
            icon.set(bad > 0 ? Icons.Type.CROSS : Icons.Type.WARN, bad > 0 ? MainActivity.ERROR : MainActivity.WARN);
            title.setText("Findings");
            if (bad > 0) badges.addView(badge(bad + (bad == 1 ? " problem" : " problems"), MainActivity.ERROR));
            if (warn > 0) badges.addView(badge(warn + (warn == 1 ? " warning" : " warnings"), MainActivity.WARN));
            Findings.Item top = f.items.get(0);
            for (Findings.Item i : f.items) if (i.severity > top.severity) top = i;
            summary.setText(top.title + "  ·  tap to see all");
            summary.setVisibility(expanded ? View.GONE : View.VISIBLE);
        }
        if (bad > 0 && !autoOpened && !userToggled) {
            autoOpened = true;
            expanded = true;
        }
        renderList();
        setExpanded(expanded);
    }

    private TextView badge(String s, int color) {
        TextView t = a.text(s, 11, 0xFF111315, true);
        t.setBackground(a.rounded(color, a.dp(10)));
        t.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = a.dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void renderList() {
        list.removeAllViews();
        if (last == null) return;
        for (Findings.Item i : last.items) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, a.dp(10), 0, a.dp(2));
            Icons.IconView ic = new Icons.IconView(a, i.severity == Findings.BAD ? Icons.Type.CROSS : Icons.Type.WARN,
                    i.severity == Findings.BAD ? MainActivity.ERROR : MainActivity.WARN);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(a.dp(20), a.dp(20));
            ilp.rightMargin = a.dp(12);
            ilp.topMargin = a.dp(1);
            row.addView(ic, ilp);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(a.text(i.title, 14, MainActivity.TEXT, true));
            if (!i.detail.isEmpty()) col.addView(a.text(i.detail, 12, MainActivity.MUTED, false));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = a.text("›", 18, MainActivity.MUTED, false);
            go.setPadding(a.dp(8), 0, 0, 0);
            row.addView(go);
            final int tab = i.tab;
            row.setOnClickListener(v -> openTab.accept(tab));
            list.addView(row);
        }
        if (!last.passed.isEmpty()) {
            TextView p = a.text("✓  " + last.passed.size() + " checks passed: " + String.join(", ", last.passed),
                    12, MainActivity.MUTED, false);
            p.setPadding(0, a.dp(10), 0, 0);
            list.addView(p);
        }
    }

    private void setExpanded(boolean e) {
        expanded = e;
        list.setVisibility(e ? View.VISIBLE : View.GONE);
        chevron.setText(e ? "▴" : "▾");
        boolean hasItems = last != null && !last.items.isEmpty();
        summary.setVisibility(!e && hasItems ? View.VISIBLE : View.GONE);
    }
}
