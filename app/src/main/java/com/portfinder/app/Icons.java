package com.portfinder.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Hand-drawn vector icons and the network map, all drawn with Canvas (no image assets). */
final class Icons {

    enum Type { GLOBE, GATEWAY, SWITCH, AP, PHONE, ETHERNET, WIFI, RADAR, CHECK, WARN, CROSS, SPEED, HISTORY }

    private Icons() {}

    /** Draws an icon centered at (cx, cy) fitting a box of the given size. */
    static void draw(Canvas c, Type type, float cx, float cy, float size, int color, Paint p) {
        float s = size / 2f;
        float sw = Math.max(1.5f, size / 16f);
        p.setAntiAlias(true);
        p.setPathEffect(null);
        p.setColor(color);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        RectF r = new RectF();
        switch (type) {
            case GLOBE: {
                float rad = s * 0.92f;
                c.drawCircle(cx, cy, rad, p);
                r.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad);
                c.drawOval(r, p);
                c.drawLine(cx, cy - rad, cx, cy + rad, p);
                c.drawLine(cx - rad, cy, cx + rad, cy, p);
                float y = rad * 0.55f, x = (float) Math.sqrt(rad * rad - y * y);
                c.drawLine(cx - x, cy - y, cx + x, cy - y, p);
                c.drawLine(cx - x, cy + y, cx + x, cy + y, p);
                break;
            }
            case GATEWAY: { // flat rack box with status lights
                r.set(cx - s, cy - s * 0.28f, cx + s, cy + s * 0.28f);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(r, sw * 2, sw * 2, p);
                p.setColor(0xFF1B1D21);
                for (int i = 0; i < 4; i++) c.drawCircle(cx + s * 0.25f + i * s * 0.17f, cy, sw * 0.8f, p);
                p.setColor(0xFF4E8DF5);
                c.drawCircle(cx - s * 0.72f, cy, sw, p);
                break;
            }
            case SWITCH: { // rack switch with a row of ports
                r.set(cx - s, cy - s * 0.32f, cx + s, cy + s * 0.32f);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(r, sw * 2, sw * 2, p);
                p.setColor(0xFF1B1D21);
                float pw = s * 0.16f, gap = s * 0.06f, x0 = cx - s * 0.82f;
                for (int i = 0; i < 8; i++) {
                    float x = x0 + i * (pw + gap);
                    c.drawRect(x, cy - s * 0.14f, x + pw, cy + s * 0.14f, p);
                }
                break;
            }
            case AP: { // round ceiling access point
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * 0.95f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(0xFFB8C4D6);
                p.setStrokeWidth(sw * 0.7f);
                c.drawCircle(cx, cy, s * 0.32f, p);
                break;
            }
            case PHONE: {
                r.set(cx - s * 0.5f, cy - s * 0.95f, cx + s * 0.5f, cy + s * 0.95f);
                c.drawRoundRect(r, s * 0.15f, s * 0.15f, p);
                c.drawLine(cx - s * 0.12f, cy + s * 0.75f, cx + s * 0.12f, cy + s * 0.75f, p);
                break;
            }
            case ETHERNET: { // RJ-45 jack outline
                Path path = new Path();
                path.moveTo(cx - s * 0.8f, cy - s * 0.6f);
                path.lineTo(cx + s * 0.8f, cy - s * 0.6f);
                path.lineTo(cx + s * 0.8f, cy + s * 0.35f);
                path.lineTo(cx + s * 0.4f, cy + s * 0.35f);
                path.lineTo(cx + s * 0.4f, cy + s * 0.7f);
                path.lineTo(cx - s * 0.4f, cy + s * 0.7f);
                path.lineTo(cx - s * 0.4f, cy + s * 0.35f);
                path.lineTo(cx - s * 0.8f, cy + s * 0.35f);
                path.close();
                c.drawPath(path, p);
                for (int i = 0; i < 4; i++) {
                    float x = cx - s * 0.45f + i * s * 0.3f;
                    c.drawLine(x, cy - s * 0.4f, x, cy - s * 0.1f, p);
                }
                break;
            }
            case WIFI: {
                for (int i = 1; i <= 3; i++) {
                    float rad = s * 0.32f * i;
                    r.set(cx - rad, cy + s * 0.55f - rad, cx + rad, cy + s * 0.55f + rad);
                    c.drawArc(r, 225, 90, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + s * 0.55f, sw * 1.1f, p);
                break;
            }
            case RADAR: {
                c.drawCircle(cx, cy, s * 0.9f, p);
                c.drawCircle(cx, cy, s * 0.5f, p);
                c.drawLine(cx, cy, cx + s * 0.64f, cy - s * 0.64f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, sw * 1.2f, p);
                break;
            }
            case SPEED: { // gauge
                r.set(cx - s * 0.9f, cy - s * 0.8f, cx + s * 0.9f, cy + s);
                c.drawArc(r, 160, 220, false, p);
                c.drawLine(cx, cy + s * 0.1f, cx + s * 0.45f, cy - s * 0.4f, p);
                break;
            }
            case HISTORY: { // clock with a counter-clockwise arrow
                r.set(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f);
                c.drawArc(r, 200, 300, false, p);
                Path arrow = new Path();
                float ax = cx - s * 0.80f, ay = cy - s * 0.29f;
                arrow.moveTo(ax - s * 0.22f, ay - s * 0.22f);
                arrow.lineTo(ax, ay + s * 0.02f);
                arrow.lineTo(ax + s * 0.26f, ay - s * 0.12f);
                c.drawPath(arrow, p);
                c.drawLine(cx, cy, cx, cy - s * 0.45f, p);
                c.drawLine(cx, cy, cx + s * 0.32f, cy + s * 0.2f, p);
                break;
            }
            case CHECK: {
                c.drawCircle(cx, cy, s * 0.9f, p);
                Path path = new Path();
                path.moveTo(cx - s * 0.4f, cy + s * 0.02f);
                path.lineTo(cx - s * 0.1f, cy + s * 0.32f);
                path.lineTo(cx + s * 0.42f, cy - s * 0.28f);
                c.drawPath(path, p);
                break;
            }
            case WARN: {
                Path path = new Path();
                path.moveTo(cx, cy - s * 0.85f);
                path.lineTo(cx + s * 0.95f, cy + s * 0.8f);
                path.lineTo(cx - s * 0.95f, cy + s * 0.8f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(cx, cy - s * 0.25f, cx, cy + s * 0.25f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + s * 0.52f, sw * 0.8f, p);
                break;
            }
            case CROSS: {
                c.drawCircle(cx, cy, s * 0.9f, p);
                c.drawLine(cx - s * 0.35f, cy - s * 0.35f, cx + s * 0.35f, cy + s * 0.35f, p);
                c.drawLine(cx + s * 0.35f, cy - s * 0.35f, cx - s * 0.35f, cy + s * 0.35f, p);
                break;
            }
        }
    }

    /** A single icon as a View. */
    static final class IconView extends View {
        private Type type;
        private int color;
        private final Paint p = new Paint();

        IconView(Context c, Type type, int color) {
            super(c);
            this.type = type;
            this.color = color;
        }

        void set(Type type, int color) {
            this.type = type;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight()) * 0.9f;
            Icons.draw(c, type, getWidth() / 2f, getHeight() / 2f, size, color, p);
        }
    }

    /** One stop on the network map. */
    static final class Node {
        final Type icon;
        final String title, subtitle, stat;
        final int iconColor, statColor;
        Node(Type icon, int iconColor, String title, String subtitle, String stat, int statColor) {
            this.icon = icon;
            this.iconColor = iconColor;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.stat = stat == null ? "" : stat;
            this.statColor = statColor;
        }
    }

    /** The link to the right of a node. */
    static final class Link {
        final boolean dashed;
        final int color;

        Link(boolean dashed, int color) {
            this.dashed = dashed;
            this.color = color;
        }
    }

    /** Internet — gateway — switch/AP — phone, with labels under each node. */
    static final class TopologyView extends View {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Link> links = new ArrayList<>();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint tp = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        TopologyView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
        }

        void set(List<Node> n, List<Link> l) {
            nodes.clear();
            nodes.addAll(n);
            links.clear();
            links.addAll(l);
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) (150 * density));
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = nodes.size();
            if (n == 0) return;
            float colW = getWidth() / (float) n;
            float iconCy = 40 * density;
            float iconSize = 52 * density;
            // links first, behind the icons
            for (int i = 0; i < n - 1 && i < links.size(); i++) {
                Link l = links.get(i);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * density);
                p.setColor(l.color);
                p.setPathEffect(l.dashed ? new DashPathEffect(new float[]{8 * density, 6 * density}, 0) : null);
                float x1 = colW * i + colW / 2 + iconSize * 0.55f;
                float x2 = colW * (i + 1) + colW / 2 - iconSize * 0.55f;
                if (x2 > x1) c.drawLine(x1, iconCy, x2, iconCy, p);
            }
            p.setPathEffect(null);
            for (int i = 0; i < n; i++) {
                Node node = nodes.get(i);
                float cx = colW * i + colW / 2;
                Icons.draw(c, node.icon, cx, iconCy, iconSize, node.iconColor, p);
                float y = iconCy + iconSize / 2 + 22 * density;
                y = text(c, node.title, cx, y, 15, MainActivity.TEXT, colW);
                y = text(c, node.subtitle, cx, y + 2 * density, 12.5f, MainActivity.MUTED, colW);
                text(c, node.stat, cx, y + 3 * density, 14, node.statColor, colW);
            }
        }

        private float text(Canvas c, String s, float cx, float baseline, float sp, int color, float maxW) {
            if (s.isEmpty()) return baseline;
            tp.setTextSize(sp * density);
            tp.setColor(color);
            tp.setTextAlign(Paint.Align.CENTER);
            CharSequence t = TextUtils.ellipsize(s, tp, maxW - 6 * density, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), cx, baseline, tp);
            return baseline + sp * density * 1.25f;
        }
    }
}
