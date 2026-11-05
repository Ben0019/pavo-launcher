package com.ben.pavo;

import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Samples the current display for the screen-follow LED mode. The root PServer
 * helper grabs a raw screencap, then the left and right halves are evaluated
 * separately so each joystick can show its side of the screen.
 *
 * <p>A saturation-weighted hue vote picks the dominant colour of each half;
 * a little of the plain average is mixed back so the result still reads like
 * the screen instead of an over-saturated poster.</p>
 */
final class ScreenAmbient {

    private static final Object LOCK = new Object();
    /** Precomputed perceptual weights remove two pow() calls per pixel. */
    private static final float[] SAT_WEIGHT = new float[64];
    private static final float[] VAL_WEIGHT = new float[64];

    static {
        for (int i = 0; i < 64; i++) {
            float v = i / 63f;
            SAT_WEIGHT[i] = (float) Math.pow(v, 1.35);
            VAL_WEIGHT[i] = (float) Math.pow(v, 1.15);
        }
    }

    private ScreenAmbient() {}

    /** Mild vividness lift so dim scenes still read as colour, not grey. */
    private static final float SCREEN_VIVIDNESS = 1.12f;

    /** Returns {left, right} RGB colours, or null when capture failed. */
    static int[] sampleLR(Context c) {
        if (c == null) return null;
        synchronized (LOCK) {
            File file = new File(c.getCacheDir(), "aurora_ambient.raw");
            file.delete();
            String command = "screencap " + file.getAbsolutePath()
                    + "; chmod 666 " + file.getAbsolutePath();
            PServer.run(command);

            long deadline = SystemClock.uptimeMillis() + 900L;
            long last = -1L;
            long stableAt = 0L;
            while (SystemClock.uptimeMillis() < deadline) {
                long size = file.length();
                if (size > 16L && size == last) {
                    if (SystemClock.uptimeMillis() - stableAt >= 12L) break;
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

                // ~50k samples per capture is far more than the colour vote
                // needs. Reading only the sampled rows keeps the worker short
                // enough to sustain the 5 Hz cadence.
                int step = Math.max(6, w / 200);
                int yStep = Math.max(2, step / 2);
                byte[] row = new byte[w * 4];
                RegionStats left = new RegionStats();
                RegionStats right = new RegionStats();
                for (int y = 0; y < h; y++) {
                    if ((y % yStep) != 0) {
                        if (!skipFully(in, row.length)) return null;
                        continue;
                    }
                    if (!readFully(in, row, row.length)) return null;
                    for (int x = 0; x < w; x += step) {
                        int o = x * 4;
                        int r = row[o] & 0xFF;
                        int g = row[o + 1] & 0xFF;
                        int b = row[o + 2] & 0xFF;
                        if (x < w / 2) left.add(r, g, b);
                        else right.add(r, g, b);
                    }
                }
                if (left.count == 0 || right.count == 0) return null;
                return new int[]{left.color(), right.color()};
            } catch (Throwable ignored) {
                return null;
            } finally {
                file.delete();
            }
        }
    }

    /**
     * Samples one raw frame. Used by {@link ScreenStream}; {@code bpp} is 3
     * for RGB888 frames and 4 for the RGBA frames written by screencap.
     */
    static int[] sampleFrame(byte[] data, int w, int h, int bpp, int step, int yStep) {
        if (data == null || w <= 0 || h <= 0 || bpp < 3) return null;
        RegionStats left = new RegionStats();
        RegionStats right = new RegionStats();
        int half = w / 2;
        for (int y = 0; y < h; y += yStep) {
            int row = y * w * bpp;
            for (int x = 0; x < w; x += step) {
                int offset = row + x * bpp;
                if (offset + 2 >= data.length) break;
                int r = data[offset] & 0xFF;
                int g = data[offset + 1] & 0xFF;
                int b = data[offset + 2] & 0xFF;
                if (x < half) {
                    left.add(r, g, b);
                } else {
                    right.add(r, g, b);
                }
            }
        }
        if (left.count == 0 || right.count == 0) return null;
        return new int[]{left.color(), right.color()};
    }

    /** Aggregated colours for one half of the display. */
    private static final class RegionStats {
        private final float[] hueBins = new float[36];
        private final float[] hsv = new float[3];
        private double avgR;
        private double avgG;
        private double avgB;
        private double satSum;
        private double valSum;
        private double weightSum;
        private int count;

        void add(int r, int g, int b) {
            avgR += r;
            avgG += g;
            avgB += b;
            count++;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            if (max == 0) return;
            float val = max / 255f;
            if (val <= 0.05f) return;
            float sat = (max - min) / (float) max;
            float weight = SAT_WEIGHT[(int) (sat * 63f + 0.5f)]
                    * VAL_WEIGHT[(int) (val * 63f + 0.5f)];
            if (weight <= 0.006f) return;
            int delta = max - min;
            float hue;
            if (delta == 0) {
                hue = 0f;
            } else if (max == r) {
                hue = 60f * ((g - b) / (float) delta);
            } else if (max == g) {
                hue = 60f * (2f + (b - r) / (float) delta);
            } else {
                hue = 60f * (4f + (r - g) / (float) delta);
            }
            if (hue < 0f) hue += 360f;
            int bin = (int) (hue / 10f) % 36;
            hueBins[bin] += weight;
            satSum += sat * weight;
            valSum += val * weight;
            weightSum += weight;
        }

        int color() {
            if (count == 0) return 0;
            int avg = Color.rgb(
                    (int) Math.round(avgR / count),
                    (int) Math.round(avgG / count),
                    (int) Math.round(avgB / count));
            if (weightSum > count * 0.004) {
                int best = 0;
                for (int i = 1; i < hueBins.length; i++) {
                    if (hueBins[i] > hueBins[best]) best = i;
                }
                double hx = 0.0, hy = 0.0, clusterWeight = 0.0;
                for (int d = -1; d <= 1; d++) {
                    int bin = (best + d + hueBins.length) % hueBins.length;
                    float weight = hueBins[bin];
                    if (weight <= 0f) continue;
                    double angle = Math.toRadians(bin * 10.0 + 5.0);
                    hx += Math.cos(angle) * weight;
                    hy += Math.sin(angle) * weight;
                    clusterWeight += weight;
                }
                float hue = clusterWeight > 0.0
                        ? (float) Math.toDegrees(Math.atan2(hy, hx))
                        : best * 10f + 5f;
                if (hue < 0f) hue += 360f;
                float sat = clamp((float) (satSum / weightSum) * 1.25f, 0.45f, 1f);
                float val = clamp((float) (valSum / weightSum) * 1.12f, 0.28f, 1f);
                int dominant = Color.HSVToColor(new float[]{hue, sat, val});
                // Keep a little of the actual average so the LED still reads
                // like the region rather than only its brightest accent.
                return applySaturation(blend(dominant, avg, 0.22f),
                        SCREEN_VIVIDNESS);
            }
            Color.RGBToHSV(Color.red(avg), Color.green(avg), Color.blue(avg), hsv);
            if (hsv[2] < 0.03f) return 0xFF000000;
            // Grey and white content stays grey/white; just lift it slightly.
            hsv[2] = Math.min(1f, hsv[2] * 1.08f);
            return applySaturation(Color.HSVToColor(hsv), SCREEN_VIVIDNESS);
        }
    }

    /** Applies the fixed SCREEN vividness without touching brightness. */
    private static int applySaturation(int color, float factor) {
        if (factor < 0f) factor = 0f;
        if (Math.abs(factor - 1f) < 0.001f) return color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * factor));
        return Color.HSVToColor(hsv);
    }

    private static int blend(int from, int to, float amount) {
        float f = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean skipFully(FileInputStream in, long length)
            throws IOException {
        while (length > 0) {
            long skipped = in.skip(length);
            if (skipped > 0) {
                length -= skipped;
            } else if (in.read() < 0) {
                return false;
            } else {
                length--;
            }
        }
        return true;
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
