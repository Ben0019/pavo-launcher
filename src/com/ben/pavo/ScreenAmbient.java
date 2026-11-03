package com.ben.pavo;

import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Samples the current display for the screen-follow LED mode. The root PServer
 * helper is used to grab a raw screencap, then the image is sampled on a sparse
 * grid. A saturation-weighted hue vote gives the LEDs the dominant screen
 * colour instead of a flat grey average.
 */
final class ScreenAmbient {

    private static final Object LOCK = new Object();

    private ScreenAmbient() {}

    /** Returns an RGB colour, or 0 when capture failed. */
    static int sample(Context c) {
        if (c == null) return 0;
        synchronized (LOCK) {
            File file = new File(c.getCacheDir(), "aurora_ambient.raw");
            file.delete();
            String command = "screencap " + file.getAbsolutePath()
                    + "; chmod 666 " + file.getAbsolutePath();
            PServer.run(command);

            long deadline = SystemClock.uptimeMillis() + 850L;
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
                return 0;
            }

            try (FileInputStream in = new FileInputStream(file)) {
                byte[] header = new byte[16];
                if (!readFully(in, header, 16)) return 0;
                int w = littleInt(header, 0);
                int h = littleInt(header, 4);
                int format = littleInt(header, 8);
                if (w <= 0 || h <= 0 || format != 1) return 0;

                int step = Math.max(4, w / 240);
                int yStep = Math.max(1, step / 2);
                byte[] row = new byte[w * 4];
                float[] hsv = new float[3];
                float[] hueBins = new float[36];
                double satSum = 0.0, valSum = 0.0, weightSum = 0.0;
                double avgR = 0.0, avgG = 0.0, avgB = 0.0;
                int count = 0;
                for (int y = 0; y < h; y++) {
                    if (!readFully(in, row, row.length)) return 0;
                    if ((y % yStep) != 0) continue;
                    for (int x = 0; x < w; x += step) {
                        int o = x * 4;
                        int r = row[o] & 0xFF;
                        int g = row[o + 1] & 0xFF;
                        int b = row[o + 2] & 0xFF;
                        avgR += r;
                        avgG += g;
                        avgB += b;
                        count++;
                        Color.RGBToHSV(r, g, b, hsv);
                        float weight = (float) (Math.pow(hsv[1], 1.35)
                                * Math.pow(hsv[2], 1.15));
                        if (hsv[2] > 0.06f && weight > 0.008f) {
                            int bin = (int) (hsv[0] / 10f) % 36;
                            hueBins[bin] += weight;
                            satSum += hsv[1] * weight;
                            valSum += hsv[2] * weight;
                            weightSum += weight;
                        }
                    }
                }
                if (count == 0) return 0;

                float hue;
                float sat;
                float val;
                if (weightSum > count * 0.004) {
                    // Pick the dominant hue cluster rather than averaging
                    // complementary colours into a muddy grey.
                    int best = 0;
                    for (int i = 1; i < hueBins.length; i++) {
                        if (hueBins[i] > hueBins[best]) best = i;
                    }
                    double hx = 0.0, hy = 0.0, clusterWeight = 0.0;
                    for (int d = -1; d <= 1; d++) {
                        int bin = (best + d + hueBins.length) % hueBins.length;
                        float wgt = hueBins[bin];
                        if (wgt <= 0f) continue;
                        double angle = Math.toRadians(bin * 10.0 + 5.0);
                        hx += Math.cos(angle) * wgt;
                        hy += Math.sin(angle) * wgt;
                        clusterWeight += wgt;
                    }
                    hue = clusterWeight > 0.0
                            ? (float) Math.toDegrees(Math.atan2(hy, hx)) : best * 10f + 5f;
                    if (hue < 0f) hue += 360f;
                    sat = clamp((float) (satSum / weightSum) * 1.55f, 0.60f, 1f);
                    val = clamp((float) (valSum / weightSum) * 1.18f, 0.32f, 1f);
                } else {
                    // Mostly white/grey content: keep its brightness but give the
                    // ring a little saturation so it still reads as a light.
                    int rr = (int) Math.round(avgR / count);
                    int gg = (int) Math.round(avgG / count);
                    int bb = (int) Math.round(avgB / count);
                    Color.RGBToHSV(rr, gg, bb, hsv);
                    if (hsv[2] < 0.04f) return 0;
                    hue = hsv[0];
                    sat = Math.max(0.38f, hsv[1]);
                    val = clamp(hsv[2] * 1.1f, 0.30f, 1f);
                }
                return Color.HSVToColor(new float[]{hue, sat, val});
            } catch (Throwable ignored) {
                return 0;
            } finally {
                file.delete();
            }
        }
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
