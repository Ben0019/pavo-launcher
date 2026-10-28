package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Captures the current display for a floating card without asking the compositor to
 * blur the whole overlay window. The raw screencap format is used because it is much
 * faster than PNG (about 120 ms instead of 500 ms on the Odin), and the captured frame
 * is downsampled and blurred in-process. Only the card samples it, so the rest of the
 * screen stays sharp.
 */
final class ScreenCaptureBackdrop {

    private ScreenCaptureBackdrop() {}

    static Backdrop capture(Context c) {
        return capture(c, true);
    }

    /** Sharp screen sample for the liquid-glass shader. */
    static Backdrop captureSharp(Context c) {
        return capture(c, false);
    }

    private static Backdrop capture(Context c, boolean blur) {
        if (c == null) return null;
        File file = new File(c.getCacheDir(), "aurora_side_backdrop.raw");
        file.delete();
        String command = "screencap " + file.getAbsolutePath()
                + "; chmod 666 " + file.getAbsolutePath();
        boolean started = PServer.run(command);
        if (!started) {
            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true).start();
                p.waitFor(700L, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            } finally {
                if (p != null) p.destroy();
            }
        }

        long deadline = SystemClock.uptimeMillis() + 1200L;
        long last = -1L;
        long stableAt = 0L;
        while (SystemClock.uptimeMillis() < deadline) {
            long size = file.length();
            if (size > 16L && size == last) {
                if (SystemClock.uptimeMillis() - stableAt >= 25L) break;
            } else {
                last = size;
                stableAt = SystemClock.uptimeMillis();
            }
            SystemClock.sleep(10L);
        }
        if (file.length() <= 16L) {
            file.delete();
            return null;
        }

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] header = new byte[16];
            if (!readFully(in, header, 16)) return null;
            int w = littleInt(header, 0);
            int h = littleInt(header, 4);
            int format = littleInt(header, 8);
            if (w <= 0 || h <= 0 || format != 1) return null;

            int sw = (w + 3) / 4;
            int sh = (h + 3) / 4;
            int[] pixels = new int[sw * sh];
            byte[] row = new byte[w * 4];
            int out = 0;
            for (int y = 0; y < h; y++) {
                if (!readFully(in, row, row.length)) return null;
                if ((y & 3) != 0) continue;
                int sy = y / 4;
                out = sy * sw;
                for (int x = 0; x < w; x += 4) {
                    int o = x * 4;
                    int r = row[o] & 0xFF;
                    int g = row[o + 1] & 0xFF;
                    int b = row[o + 2] & 0xFF;
                    int a = row[o + 3] & 0xFF;
                    pixels[out++] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }

            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            small.setPixels(pixels, 0, sw, 0, 0, sw, sh);
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
        } finally {
            file.delete();
        }
    }

    /** Build a screen-anchored backdrop from one app-window screenshot. */
    static Backdrop fromWindow(Context c, Bitmap window, Rect bounds) {
        if (c == null || window == null || bounds == null) return null;
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            Point size = new Point();
            if (display != null) display.getRealSize(size);
            int w = Math.max(1, size.x);
            int h = Math.max(1, size.y);
            if (w <= 1 || h <= 1) {
                w = Math.max(1, c.getResources().getDisplayMetrics().widthPixels);
                h = Math.max(1, c.getResources().getDisplayMetrics().heightPixels);
            }
            int sw = Math.max(1, w / 4);
            int sh = Math.max(1, h / 4);
            Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(small);
            canvas.drawColor(Color.BLACK);
            float sx = sw / (float) w;
            float sy = sh / (float) h;
            float left = Math.max(0, bounds.left) * sx;
            float top = Math.max(0, bounds.top) * sy;
            float right = Math.min(w, bounds.right) * sx;
            float bottom = Math.min(h, bounds.bottom) * sy;
            if (right > left && bottom > top) {
                canvas.drawBitmap(window, null, new RectF(left, top, right, bottom),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
            }
            float density = c.getResources().getDisplayMetrics().density;
            int radius = Math.max(8, Math.round(28f * density / 4f));
            Bitmap blurred = Blur.blur(small, radius);
            small.recycle();
            window.recycle();
            return new Backdrop(blurred, w, h);
        } catch (Throwable ignored) {
            window.recycle();
            return null;
        }
    }

    private static boolean readFully(FileInputStream in, byte[] buffer, int length)
            throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n <= 0) return false;
            read += n;
        }
        return true;
    }

    private static int littleInt(byte[] b, int offset) {
        return (b[offset] & 0xFF)
                | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16)
                | ((b[offset + 3] & 0xFF) << 24);
    }
}
