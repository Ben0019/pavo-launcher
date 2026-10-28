package com.ben.aurora;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Picks a vivid accent that represents a wallpaper's overall dominant hue.
 *
 * Material's {@code WallpaperColors.getPrimaryColor()} tends to favour small,
 * highly chromatic details (a sunset, a lamp, a red sign) instead of the colour
 * that actually fills the image, which made every wallpaper tint the launcher
 * red. This walks a hue histogram instead and takes the heaviest hue band, so
 * a blue-grey sky yields a blue accent, a forest yields green, and so on.
 */
final class AccentColor {
    private static final int DEFAULT = 0xFFA9BEFF;
    private static final int BINS = 24;

    private AccentColor() {}

    static int fromBitmap(Bitmap source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return DEFAULT;
        }
        Bitmap small = null;
        try {
            int width = 64;
            int height = Math.max(1,
                    Math.round(width * source.getHeight() / (float) source.getWidth()));
            small = Bitmap.createScaledBitmap(source, width, height, true);
            int[] pixels = new int[width * height];
            small.getPixels(pixels, 0, width, 0, 0, width, height);
            return fromPixels(pixels);
        } catch (Throwable t) {
            return DEFAULT;
        } finally {
            if (small != null && small != source) small.recycle();
        }
    }

    static int fromPixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) return DEFAULT;

        float[] hueWeight = new float[BINS];
        double[][] hueSum = new double[BINS][3];
        double allR = 0, allG = 0, allB = 0, allWeight = 0;
        float[] hsv = new float[3];

        for (int colour : pixels) {
            Color.colorToHSV(colour, hsv);
            float hue = hsv[0];
            float sat = hsv[1];
            float val = hsv[2];
            if (val < 0.08f || val > 0.97f) continue;

            float weight = (float) (Math.pow(sat, 1.4f) * (0.35f + 0.65f * val));
            allR += Color.red(colour) * weight;
            allG += Color.green(colour) * weight;
            allB += Color.blue(colour) * weight;
            allWeight += weight;

            // Near-grey pixels carry no usable hue; they only contribute to the
            // neutral fallback below.
            if (sat < 0.08f || weight <= 0f) continue;
            int bin = Math.min(BINS - 1, (int) (hue / 360f * BINS));
            hueWeight[bin] += weight;
            hueSum[bin][0] += Color.red(colour) * weight;
            hueSum[bin][1] += Color.green(colour) * weight;
            hueSum[bin][2] += Color.blue(colour) * weight;
        }

        int best = -1;
        float bestWeight = 0f;
        for (int i = 0; i < BINS; i++) {
            float w = hueWeight[(i + BINS - 1) % BINS] + hueWeight[i]
                    + hueWeight[(i + 1) % BINS];
            if (w > bestWeight) {
                bestWeight = w;
                best = i;
            }
        }

        double r, g, b;
        if (best < 0 || bestWeight < 0.5f) {
            if (allWeight < 0.0001f) return DEFAULT;
            r = allR / allWeight;
            g = allG / allWeight;
            b = allB / allWeight;
        } else {
            double rr = 0, gg = 0, bb = 0, ww = 0;
            for (int k = -1; k <= 1; k++) {
                int j = (best + k + BINS) % BINS;
                rr += hueSum[j][0];
                gg += hueSum[j][1];
                bb += hueSum[j][2];
                ww += hueWeight[j];
            }
            if (ww < 0.0001f) return DEFAULT;
            r = rr / ww;
            g = gg / ww;
            b = bb / ww;
        }

        hsv[0] = 0f;
        hsv[1] = 0f;
        hsv[2] = 0f;
        Color.RGBToHSV(clampChannel(r), clampChannel(g), clampChannel(b), hsv);
        if (hsv[1] < 0.10f) {
            hsv[0] = 226f;
            hsv[1] = 0.30f;
        } else {
            hsv[1] = Math.max(0.45f, Math.min(0.85f, hsv[1] * 1.35f));
        }
        hsv[2] = Math.max(0.82f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static int clampChannel(double value) {
        int v = (int) Math.round(value);
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
