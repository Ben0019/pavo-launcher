package com.ben.pavo;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** Wallpaper-only fallback for overlay cards when a screen sample is unavailable. */
final class WallpaperBackdrop {
    private WallpaperBackdrop() {}

    static Backdrop capture(Context c) {
        return capture(c, true);
    }

    static Backdrop captureSharp(Context c) {
        return capture(c, false);
    }

    private static Backdrop capture(Context c, boolean blur) {
        if (c == null) return null;
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(DISPLAY_WORKSPACE);
            if (display == null) return null;
            Point size = new Point();
            display.getRealSize(size);
            int w = Math.max(1, size.x);
            int h = Math.max(1, size.y);

            WallpaperManager wm = WallpaperManager.getInstance(c);
            Drawable d = wm == null ? null : wm.getDrawable();
            if (d == null) return null;
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) {
                iw = w;
                ih = h;
            }
            int max = 2048;
            float scale = Math.min(1f, max / (float) Math.max(iw, ih));
            int bw = Math.max(1, Math.round(iw * scale));
            int bh = Math.max(1, Math.round(ih * scale));
            Bitmap source = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(source);
            d.setBounds(0, 0, bw, bh);
            d.draw(canvas);

            int sw = Math.max(1, w / 4);
            int sh = Math.max(1, h / 4);
            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas sc = new Canvas(small);
            float cover = Math.max(sw / (float) bw, sh / (float) bh);
            float dw = bw * cover;
            float dh = bh * cover;
            float left = (sw - dw) / 2f;
            float top = (sh - dh) / 2f;
            sc.drawBitmap(source, null, new RectF(left, top, left + dw, top + dh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            source.recycle();

            if (blur) {
                float density = c.getResources().getDisplayMetrics().density;
                int radius = Math.max(8, Math.round(28f * density / 4f));
                Bitmap blurred = Blur.blur(small, radius);
                small.recycle();
                return new Backdrop(blurred, w, h);
            }
            return new Backdrop(small, w, h);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Avoid importing Display.DEFAULT_DISPLAY semantics into this helper's caller.
    private static final int DISPLAY_WORKSPACE = Display.DEFAULT_DISPLAY;
}
