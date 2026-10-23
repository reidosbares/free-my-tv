package io.freemytv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.util.concurrent.Executor;

/**
 * Text as frosted glass without AGSL: GlassMath bakes it into a bitmap on a worker thread, once per change, and the
 * caller draws that bitmap. Use from the main thread. The previous bake stays until the next one is ready.
 */
final class GlassClock {
    private final Executor worker;
    private final Handler main;
    private int generation;
    private Bitmap bitmap, previous;
    private float x, y;
    private long readyAt;
    // The last request, to skip one that would bake the same thing.
    private String text;
    private float left, baseline, size;
    private GlassMath.Sampler bg;
    private final GlassMath.Look look = new GlassMath.Look();

    GlassClock(Executor worker, Handler main) {
        this.worker = worker;
        this.main = main;
    }

    /** Bakes `text` as `paint` draws it, its ink's left edge at `left` and its baseline at `baseline` on screen. */
    void request(String text, Paint paint, float left, float baseline, GlassMath.Sampler bg, GlassMath.Look look,
                 final Runnable onReady) {
        if (text == null || text.isEmpty()) return;
        if (same(text, paint, left, baseline, bg, look)) return;
        this.text = text;
        this.left = left;
        this.baseline = baseline;
        this.size = paint.getTextSize();
        this.bg = bg;
        copy(look, this.look);
        final int gen = ++generation;
        final Paint p = new Paint(paint);
        p.clearShadowLayer();
        p.setColor(0xFFFFFFFF);
        p.setTextAlign(Paint.Align.LEFT);
        final GlassMath.Look l = copy(look, new GlassMath.Look());
        final float k = scale();
        worker.execute(() -> {
            final float[] at = new float[2];
            final Bitmap baked;
            try {
                baked = bake(text, p, left, baseline, bg, l, k, at);
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("glass clock: " + e.getMessage());
                return;
            }
            main.post(() -> {
                if (gen != generation) {
                    baked.recycle();
                    return;
                }
                final Bitmap gone = previous;
                // Once the frames that may still draw it are done.
                if (gone != null) main.postDelayed(gone::recycle, 1000);
                previous = bitmap;
                bitmap = baked;
                x = at[0];
                y = at[1];
                readyAt = SystemClock.uptimeMillis();
                if (onReady != null) onReady.run();
            });
        });
    }

    Bitmap bitmap() {
        return bitmap;
    }

    Bitmap previous() {
        return previous;
    }

    float x() {
        return x;
    }

    float y() {
        return y;
    }

    /** SystemClock.uptimeMillis() when the current bitmap became ready. */
    long readyAt() {
        return readyAt;
    }

    /** Drops pending bakes and frees the bitmaps (after the frames that may still draw them). */
    void release() {
        generation++;
        text = null;
        final Bitmap a = bitmap, b = previous;
        bitmap = previous = null;
        main.postDelayed(() -> {
            if (a != null) a.recycle();
            if (b != null) b.recycle();
        }, 1000);
    }

    private boolean same(String text, Paint paint, float left, float baseline, GlassMath.Sampler bg,
                         GlassMath.Look look) {
        GlassMath.Look o = this.look;
        return text.equals(this.text) && bg == this.bg && left == this.left && baseline == this.baseline
                && paint.getTextSize() == size && look.refraction == o.refraction && look.body == o.body
                && look.lift == o.lift && look.specular == o.specular && look.shadow == o.shadow
                && look.darken == o.darken;
    }

    private static GlassMath.Look copy(GlassMath.Look from, GlassMath.Look to) {
        to.refraction = from.refraction;
        to.body = from.body;
        to.lift = from.lift;
        to.specular = from.specular;
        to.shadow = from.shadow;
        to.darken = from.darken;
        return to;
    }

    /** The layout scale the screens use (their width / 1920). */
    private static float scale() {
        DisplayMetrics d = Resources.getSystem().getDisplayMetrics();
        return Math.max(d.widthPixels, d.heightPixels) / 1920f;
    }

    private static Bitmap bake(String text, Paint paint, float left, float baseline, GlassMath.Sampler bg,
                               GlassMath.Look look, float k, float[] at) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        // Room for the drop shadow (blurred 3 × 14k, shifted 6k down) so it never clips at the edge.
        int pad = Math.round(Math.max(40 * k, 3 * 14 * k + 6 * k));
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] alpha = new int[w * h];
        byte[] row = new byte[cover.getByteCount()];
        cover.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(row));
        int stride = cover.getRowBytes();
        cover.recycle();
        for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) alpha[yy * w + xx] = row[yy * stride + xx] & 255;
        float ox = Math.round(left) - pad, oy = Math.round(baseline + ink.top) - pad;
        GlassMath.Mask m = GlassMath.mask(alpha, w, h, k);
        int[] px = GlassMath.render(m, alpha, bg, ox, oy, look);
        at[0] = ox;
        at[1] = oy;
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
