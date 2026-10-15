package com.portfinder.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** "Share as image / Share as text" for a test result. */
final class ReportShare {

    private ReportShare() {}

    /** Asks how to share, then shares the result as a PNG report card or as plain text. */
    static void choose(MainActivity a, HistoryStore.Entry e, String subject, String text) {
        new AlertDialog.Builder(a)
                .setTitle("Share result")
                .setItems(new CharSequence[]{"Share as image", "Share as text"}, (d, which) -> {
                    if (which == 0) shareImage(a, e, subject);
                    else shareText(a, subject, text);
                })
                .show();
    }

    static void shareText(MainActivity a, String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        a.startActivity(Intent.createChooser(send, "Share"));
    }

    static void shareImage(MainActivity a, HistoryStore.Entry e, String subject) {
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File f = null;
            try {
                f = render(a, e);
            } catch (Exception | OutOfMemoryError ignored) {
            }
            final File file = f;
            ui.post(() -> {
                if (file == null) {
                    Toast.makeText(a, "Couldn't create the image", Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = ExportProvider.uriFor(a, file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/png");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, subject);
                send.setClipData(ClipData.newRawUri("", uri)); // lets the share sheet show a preview
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.startActivity(Intent.createChooser(send, "Share image"));
            });
        }, "report-image").start();
    }

    /** Draws the report card into a PNG in cache/exports and returns it. */
    static File render(MainActivity a, HistoryStore.Entry e) throws Exception {
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(e.time == 0 ? System.currentTimeMillis() : e.time));
        Painter measure = new Painter(null, null);
        int h = Math.min(ReportImage.layout(measure, false, e, date), 6000);
        Bitmap bmp = Bitmap.createBitmap(ReportImage.WIDTH, h, Bitmap.Config.ARGB_8888);
        ReportImage.layout(new Painter(new Canvas(bmp), AppIcon.load(a)), true, e, date);

        File dir = new File(a.getCacheDir(), "exports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles((d, n) -> n.endsWith(".png"));
        if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
            o.delete(); // keep only the latest image
        File f = new File(dir, "portperson-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png");
        try (OutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            bmp.recycle();
        }
        return f;
    }

    /** ReportImage drawing on an Android Canvas (canvas == null: measure only). */
    private static final class Painter implements ReportImage.Painter {
        private final Canvas c;
        private final Drawable appIcon;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);

        Painter(Canvas c, Drawable appIcon) {
            this.c = c;
            this.appIcon = appIcon;
        }

        @Override
        public void appIcon(float x, float y, float size) {
            AppIcon.draw(c, appIcon, x, y, size);
        }

        private Paint font(float size, int style) {
            text.setTextSize(size);
            text.setTypeface(style == ReportImage.MONO ? Typeface.MONOSPACE
                    : style == ReportImage.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return text;
        }

        @Override
        public float width(String s, float size, int style) {
            return font(size, style).measureText(s);
        }

        @Override
        public void text(String s, float x, float baseline, float size, int color, int style) {
            if (c == null) return;
            Paint p = font(size, style);
            p.setColor(color);
            c.drawText(s, x, baseline, p);
        }

        @Override
        public void rect(float x, float y, float w, float h, float radius, int fill, int stroke) {
            if (c == null) return;
            RectF r = new RectF(x, y, x + w, y + h);
            shape.setStyle(Paint.Style.FILL);
            shape.setColor(fill);
            c.drawRoundRect(r, radius, radius, shape);
            if (stroke != 0) {
                shape.setStyle(Paint.Style.STROKE);
                shape.setStrokeWidth(2);
                shape.setColor(stroke);
                c.drawRoundRect(r, radius, radius, shape);
            }
        }

        @Override
        public void icon(String icon, float cx, float cy, float size, int color) {
            if (c == null) return;
            Icons.Type t = "wifi".equals(icon) ? Icons.Type.WIFI : "speed".equals(icon) ? Icons.Type.SPEED : Icons.Type.ETHERNET;
            Icons.draw(c, t, cx, cy, size, color, shape);
        }
    }
}
