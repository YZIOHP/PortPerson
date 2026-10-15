package com.portfinder.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The LAN test's step checklist: Adapter → Link → Address → Switch info → Internet. Each step shows a
 * spinner while it runs, then a check, warning or cross. Shown from the moment Run Test is tapped, so
 * it's clear the test is waiting for link rather than failing.
 */
final class TestProgress {

    static final int PENDING = 0, ACTIVE = 1, DONE = 2, WARN = 3, FAIL = 4, SKIPPED = 5;

    private final MainActivity a;
    private final LinearLayout card;
    private final Row[] rows;

    private static final class Row {
        LinearLayout view;
        StepIcon icon;
        TextView title, detail;
    }

    TestProgress(MainActivity a, String... titles) {
        this.a = a;
        card = a.card();
        card.setVisibility(View.GONE);
        rows = new Row[titles.length];
        for (int i = 0; i < titles.length; i++) {
            Row r = new Row();
            r.view = new LinearLayout(a);
            r.view.setOrientation(LinearLayout.HORIZONTAL);
            r.view.setGravity(Gravity.CENTER_VERTICAL);
            r.view.setPadding(0, a.dp(6), 0, a.dp(6));
            r.icon = new StepIcon(a);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(a.dp(26), a.dp(26));
            ilp.rightMargin = a.dp(12);
            r.view.addView(r.icon, ilp);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            r.title = a.text(titles[i], 15, MainActivity.TEXT, true);
            r.detail = a.text("", 13, MainActivity.MUTED, false);
            col.addView(r.title);
            col.addView(r.detail);
            r.view.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(r.view);
            rows[i] = r;
        }
    }

    View view() { return card; }

    void show(boolean visible) {
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Hides or shows one step (e.g. the network steps when network tests are off). */
    void visible(int step, boolean v) {
        rows[step].view.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    void set(int step, int state, String detail) {
        Row r = rows[step];
        r.icon.setState(state);
        r.title.setTextColor(state == PENDING || state == SKIPPED ? MainActivity.MUTED : MainActivity.TEXT);
        r.detail.setText(detail == null ? "" : detail);
        r.detail.setTextColor(state == FAIL ? MainActivity.ERROR : state == WARN ? MainActivity.WARN : MainActivity.MUTED);
        r.detail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Spinner / check / warning / cross, drawn to match the app's icons. */
    static final class StepIcon extends View {
        private int state = PENDING;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        StepIcon(Context c) {
            super(c);
        }

        void setState(int s) {
            if (s == state) return;
            state = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, size = Math.min(w, h);
            float r = size * 0.38f;
            switch (state) {
                case ACTIVE: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(size * 0.11f);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setColor(MainActivity.BORDER);
                    c.drawCircle(cx, cy, r, p);
                    p.setColor(MainActivity.BLUE);
                    oval.set(cx - r, cy - r, cx + r, cy + r);
                    float start = (System.currentTimeMillis() % 1000L) * 0.36f;
                    c.drawArc(oval, start, 100, false, p);
                    postInvalidateOnAnimation();
                    break;
                }
                case DONE:
                    Icons.draw(c, Icons.Type.CHECK, cx, cy, size * 0.9f, MainActivity.ACCENT, p);
                    break;
                case WARN:
                    Icons.draw(c, Icons.Type.WARN, cx, cy, size * 0.9f, MainActivity.WARN, p);
                    break;
                case FAIL:
                    Icons.draw(c, Icons.Type.CROSS, cx, cy, size * 0.9f, MainActivity.ERROR, p);
                    break;
                default: { // PENDING, SKIPPED: hollow dot
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(size * 0.08f);
                    p.setColor(MainActivity.BORDER);
                    c.drawCircle(cx, cy, r * 0.55f, p);
                }
            }
        }
    }
}
