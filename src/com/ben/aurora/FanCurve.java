package com.ben.aurora;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Temperature-to-duty curve shared by the fan app and its background service. */
final class FanCurve {
    static final String DEFAULT = "30:20,35:25,40:25,45:30,50:35,55:45,60:55,"
            + "65:70,70:80,75:90,80:100,85:100,90:100,95:100,100:100";

    /** Representative automatic curve shown while the SMART system preset is active. */
    static final String SMART = "30:10,35:10,40:15,45:20,50:30,55:40,60:55,"
            + "65:70,70:85,75:95,80:100,85:100,90:100,95:100,100:100";

    private FanCurve() {}

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
