package com.ben.pavo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Temperature-to-duty curve shared by the fan app and its background service. */
final class FanCurve {
    static final String DEFAULT = "30:0,35:0,40:0,45:0,50:12,55:20,60:30,"
            + "65:42,70:55,75:68,80:82,85:93,90:100,95:100,100:100";

    /** Representative automatic curve shown while the SMART system preset is active. */
    static final String SMART = "30:10,35:10,40:12,45:15,50:20,55:26,60:34,"
            + "65:45,70:58,75:72,80:86,85:95,90:100,95:100,100:100";

    /**
     * Low-noise curves for the three Performance Tuner underclocks. The
     * eight-thread probe held roughly 60 °C on all three at 10-18 % duty, so
     * each preset stays quiet through its own sustained peak and only ramps
     * above it. The deeper the underclock, the longer it stays quiet.
     */
    static final String SMALL = "30:0,35:0,40:0,45:0,50:10,55:14,60:18,"
            + "65:26,70:36,75:48,80:62,85:80,90:100,95:100,100:100";
    static final String MEDIUM = "30:0,35:0,40:0,45:0,50:6,55:9,60:13,"
            + "65:18,70:26,75:37,80:52,85:72,90:100,95:100,100:100";
    static final String LARGE = "30:0,35:0,40:0,45:0,50:6,55:10,60:14,"
            + "65:20,70:28,75:40,80:54,85:72,90:100,95:100,100:100";

    /** Fixed low-noise curve for a performance-preset fan mode, or null. */
    static String presetCurve(int mode) {
        if (mode == FanProfile.MODE_SMALL) return SMALL;
        if (mode == FanProfile.MODE_MEDIUM) return MEDIUM;
        if (mode == FanProfile.MODE_LARGE) return LARGE;
        return null;
    }

    private FanCurve() {}

    /** Pick the temperature that drives the curve: CPU, GPU, or the hotter one. */
    static float sourceTemp(float cpu, float gpu, int source) {
        if (source == 0) return cpu > 0f ? cpu : gpu;
        if (source == 1) return gpu > 0f ? gpu : cpu;
        return Math.max(cpu, gpu);
    }

    /** 15 control points at 5 °C intervals at one duty level. */
    static String flat(int duty) {
        int d = clamp(duty, 0, 100);
        StringBuilder b = new StringBuilder();
        for (int t = 30; t <= 100; t += 5) {
            if (b.length() > 0) b.append(',');
            b.append(t).append(':').append(d);
        }
        return b.toString();
    }

    static List<int[]> points(String curve) {
        List<int[]> out = new ArrayList<>();
        if (curve == null) curve = DEFAULT;
        String[] parts = curve.split(",");
        for (String part : parts) {
            String[] pair = part.trim().split(":");
            if (pair.length != 2) continue;
            try {
                int t = clamp(Math.round(Float.parseFloat(pair[0])), 30, 100);
                int d = clamp(Math.round(Float.parseFloat(pair[1])), 0, 100);
                out.add(new int[]{t, d});
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            parts = DEFAULT.split(",");
            for (String part : parts) {
                String[] pair = part.split(":");
                out.add(new int[]{Integer.parseInt(pair[0]), Integer.parseInt(pair[1])});
            }
        }
        out.sort(Comparator.comparingInt(a -> a[0]));
        return out;
    }

    /** Re-sample a curve at 5 °C intervals, preserving its approximate shape. */
    static String densify(String curve) {
        StringBuilder b = new StringBuilder();
        for (int t = 30; t <= 100; t += 5) {
            int d = clamp(Math.round(dutyAt(curve, t) / 5f) * 5, 0, 100);
            if (b.length() > 0) b.append(',');
            b.append(t).append(':').append(d);
        }
        return b.toString();
    }

    /** Ensure the graph always has a handle on the 100 °C end stop. */
    static String ensureTerminal(String curve) {
        List<int[]> pts = points(curve);
        if (pts.isEmpty()) return DEFAULT;
        int last = pts.get(pts.size() - 1)[0];
        if (last < 100) {
            pts.add(new int[]{100, pts.get(pts.size() - 1)[1]});
        }
        return serialize(pts);
    }

    static String serialize(List<int[]> points) {
        StringBuilder b = new StringBuilder();
        for (int[] p : points) {
            if (b.length() > 0) b.append(',');
            b.append(clamp(p[0], 30, 100)).append(':')
                    .append(clamp(p[1], 0, 100));
        }
        return b.toString();
    }

    static int dutyAt(String curve, float temperature) {
        List<int[]> points = points(curve);
        if (points.isEmpty()) return 50;
        if (temperature <= points.get(0)[0]) return points.get(0)[1];
        for (int i = 1; i < points.size(); i++) {
            int t0 = points.get(i - 1)[0], d0 = points.get(i - 1)[1];
            int t1 = points.get(i)[0], d1 = points.get(i)[1];
            if (temperature <= t1) {
                if (t1 <= t0) return d1;
                float f = (temperature - t0) / (float) (t1 - t0);
                return Math.round(d0 + (d1 - d0) * f);
            }
        }
        return points.get(points.size() - 1)[1];
    }

    static String formatTempDuty(int t, int d) {
        return String.format(Locale.US, "%d°:%d%%", t, d);
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
