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

    private ScreenAmbient() {}

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

                int step = Math.max(3, w / 320);
                int yStep = Math.max(1, step / 2);
                byte[] row = new byte[w * 4];
                RegionStats left = new RegionStats();
                RegionStats right = new RegionStats();
                for (int y = 0; y < h; y++) {
                    if (!readFully(in, row, row.length)) return null;
                    if ((y % yStep) != 0) continue;
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
                int[] lp = left.palette();
                int[] rp = right.palette();
                return new int[]{lp[0], lp[1], rp[0], rp[1]};
            } catch (Throwable ignored) {
                return null;
            } finally {
                file.delete();
            }
        }
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
            Color.RGBToHSV(r, g, b, hsv);
            float weight = (float) (Math.pow(hsv[1], 1.35)
                    * Math.pow(hsv[2], 1.15));
            if (hsv[2] > 0.05f && weight > 0.006f) {
                int bin = (int) (hsv[0] / 10f) % 36;
                hueBins[bin] += weight;
                satSum += hsv[1] * weight;
                valSum += hsv[2] * weight;
                weightSum += weight;
            }
        }

        int[] palette() {
            if (count == 0) return new int[]{0, 0};
            int avg = Color.rgb(
                    (int) Math.round(avgR / count),
                    (int) Math.round(avgG / count),
                    (int) Math.round(avgB / count));
            if (weightSum > count * 0.004) {
                int best = bestBin(-1, 0);
                int first = clusterColor(best, avg);
                int secondBin = bestBin(best, 4);
                int second = secondBin >= 0
                        && hueBins[secondBin] >= hueBins[best] * 0.10f
                        ? clusterColor(secondBin, avg) : first;
                return new int[]{first, second};
            }
            Color.RGBToHSV(Color.red(avg), Color.green(avg), Color.blue(avg), hsv);
            int value;
            if (hsv[2] < 0.03f) {
                value = 0xFF000000;
            } else {
                // Grey and white content stays grey/white; just lift it slightly.
                hsv[2] = Math.min(1f, hsv[2] * 1.08f);
                value = Color.HSVToColor(hsv);
            }
            return new int[]{value, value};
        }

        private int bestBin(int exclude, int minDistance) {
            int best = -1;
            for (int i = 0; i < hueBins.length; i++) {
                if (hueBins[i] <= 0f) continue;
                if (exclude >= 0 && angularDistance(i, exclude) < minDistance) continue;
                if (best < 0 || hueBins[i] > hueBins[best]) best = i;
            }
            return best;
        }

        private int clusterColor(int best, int avg) {
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
            return blend(dominant, avg, 0.22f);
        }

        private static int angularDistance(int a, int b) {
            int d = Math.abs(a - b) % 36;
            return Math.min(d, 36 - d);
        }
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
