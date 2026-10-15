package com.portfinder.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The installed PortPerson launcher icon, drawn as a rounded square (the same shape on every phone),
 * plus the short splash screen shown when the app opens.
 */
final class AppIcon {

    private AppIcon() {}

    /** The app's launcher icon, or null if Android can't provide it. */
    static Drawable load(Context c) {
        try {
            return c.getPackageManager().getApplicationIcon(c.getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    /** Draws the icon as a rounded square in a size x size box at x, y. */
    static void draw(Canvas c, Drawable icon, float x, float y, float size) {
        if (c == null || icon == null) return;
        int save = c.save();
        Path clip = new Path();
        clip.addRoundRect(new RectF(x, y, x + size, y + size), size * 0.24f, size * 0.24f, Path.Direction.CW);
        c.clipPath(clip);
        if (icon instanceof AdaptiveIconDrawable) {
            // Adaptive icon layers are 108dp with the visible 72dp in the middle: draw them 1.5x and clip
            float extra = size * 0.25f;
            int l = Math.round(x - extra), t = Math.round(y - extra), r = Math.round(x + size + extra), b = Math.round(y + size + extra);
            AdaptiveIconDrawable ai = (AdaptiveIconDrawable) icon;
            for (Drawable layer : new Drawable[]{ai.getBackground(), ai.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(l, t, r, b);
                layer.draw(c);
            }
        } else {
            icon.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
            icon.draw(c);
        }
        c.restoreToCount(save);
    }

    /** A view that shows the app icon. */
    static final class IconView extends View {
        private final Drawable icon;

        IconView(Context c) {
            super(c);
            icon = load(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight());
            AppIcon.draw(c, icon, (getWidth() - size) / 2f, (getHeight() - size) / 2f, size);
        }
    }

    private static LinearLayout.LayoutParams centered() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    static final long SPLASH_MS = 900, FADE_MS = 350;

    /**
     * Covers the app with a dark splash (icon and name) for a moment, then fades it away and runs
     * {@code after}. The app's UI is built underneath, so it's ready the moment the splash fades.
     */
    static void showSplash(MainActivity a, FrameLayout host, Runnable after) {
        LinearLayout splash = new LinearLayout(a);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(MainActivity.BG);
        splash.setClickable(true); // swallow taps until it's gone

        IconView icon = new IconView(a);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(a.dp(120), a.dp(120));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        splash.addView(icon, ilp);
        // Text lines are centered on their own: a vertical LinearLayout otherwise stretches them full width
        TextView name = a.text("PortPerson", 26, MainActivity.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, a.dp(18), 0, 0);
        splash.addView(name, centered());
        TextView by = a.text("by YZIOHP", 13, MainActivity.MUTED, false);
        by.setGravity(Gravity.CENTER);
        by.setPadding(0, a.dp(4), 0, 0);
        splash.addView(by, centered());

        host.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon.setScaleX(0.85f);
        icon.setScaleY(0.85f);
        icon.animate().scaleX(1f).scaleY(1f).setDuration(400).start();
        splash.postDelayed(() -> splash.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            host.removeView(splash);
            if (after != null) after.run();
        }).start(), SPLASH_MS);
    }
}
