package com.ben.pavo;

import android.graphics.Bitmap;

/**
 * Small, dependency-free separable box blur. Three passes approximate a Gaussian and
 * are cheap because the launcher only ever blurs quarter-resolution bitmaps.
 */
final class Blur {
    private Blur() {}

    static Bitmap blur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] a = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);

        if (radius >= 1 && w > 2 && h > 2) {
            int[] b = new int[w * h];
            for (int pass = 0; pass < 3; pass++) {
                blurH(a, b, w, h, radius);
                blurV(b, a, w, h, radius);
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static void blurH(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + clamp(i, w)];
                rs += (c >> 16) & 255;
                gs += (c >> 8) & 255;
                bs += c & 255;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = 0xFF000000
                        | ((rs / div) << 16)
                        | ((gs / div) << 8)
                        | (bs / div);
                int co = in[row + clamp(x - r, w)];
                int ci = in[row + clamp(x + r + 1, w)];
                rs += ((ci >> 16) & 255) - ((co >> 16) & 255);
                gs += ((ci >> 8) & 255) - ((co >> 8) & 255);
                bs += (ci & 255) - (co & 255);
            }
        }
    }

    private static void blurV(int[] in, int[] out, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, h) * w + x];
                rs += (c >> 16) & 255;
                gs += (c >> 8) & 255;
                bs += c & 255;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = 0xFF000000
                        | ((rs / div) << 16)
                        | ((gs / div) << 8)
                        | (bs / div);
                int co = in[clamp(y - r, h) * w + x];
                int ci = in[clamp(y + r + 1, h) * w + x];
                rs += ((ci >> 16) & 255) - ((co >> 16) & 255);
                gs += ((ci >> 8) & 255) - ((co >> 8) & 255);
                bs += (ci & 255) - (co & 255);
            }
        }
    }

    private static int clamp(int v, int max) {
        if (v < 0) return 0;
        if (v >= max) return max - 1;
        return v;
    }
}
