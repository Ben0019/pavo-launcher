package com.ben.pavo;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
            + "65:26,70:44,75:56,80:70,85:88,90:100,95:100,100:100";
    static final String MEDIUM = "30:0,35:0,40:0,45:0,50:5,55:8,60:11,"
            + "65:16,70:23,75:33,80:49,85:74,90:100,95:100,100:100";
    static final String LARGE = "30:0,35:0,40:0,45:0,50:5,55:7,57:10,"
            + "60:15,65:17,67:19,70:21,75:30,77:36,80:46,85:71,87:83,"
            + "90:100,95:100,100:100";

    /** The factory curves were tuned against the Odin 3's 95 C passive trip. */
    private static final float REFERENCE_TRIP_C = 95f;
    private static volatile float tripC = -1f;
    private static final String[] AUTO = new String[3];
    private static float autoTrip = -1f;

    /** Fixed low-noise curve for a performance-preset fan mode, or null. */
    static String factoryPreset(int mode) {
        if (mode == FanProfile.MODE_SMALL) return SMALL;
        if (mode == FanProfile.MODE_MEDIUM) return MEDIUM;
        if (mode == FanProfile.MODE_LARGE) return LARGE;
        return null;
    }

    static boolean isPresetMode(int mode) {
        return factoryPreset(mode) != null;
    }

    /** Saved preset curve, falling back to the device-calibrated default. */
    static String presetCurve(Prefs prefs, int mode) {
        if (factoryPreset(mode) == null) return null;
        String saved = prefs == null ? null : prefs.fanPresetCurve(mode);
        if (saved != null && !saved.isEmpty()) return saved;
        return calibratedPreset(mode);
    }

    /** Shift the factory curve so its 95 C anchor follows this device's trip. */
    private static synchronized String calibratedPreset(int mode) {
        int index = mode == FanProfile.MODE_SMALL ? 0
                : mode == FanProfile.MODE_MEDIUM ? 1 : 2;
        float trip = deviceTripC();
        if (trip != autoTrip) {
            float delta = trip - REFERENCE_TRIP_C;
            AUTO[0] = adapt(SMALL, delta);
            AUTO[1] = adapt(MEDIUM, delta);
            AUTO[2] = adapt(LARGE, delta);
            autoTrip = trip;
        }
        String curve = AUTO[index];
        return curve == null ? factoryPreset(mode) : curve;
    }

    private static String adapt(String curve, float deltaC) {
        if (Math.abs(deltaC) < 1f) return curve;
        TreeMap<Integer, Integer> shifted = new TreeMap<>();
        for (int[] point : points(curve)) {
            int t = clamp(Math.round(point[0] + deltaC), 30, 100);
            Integer old = shifted.get(t);
            if (old == null || point[1] > old) shifted.put(t, point[1]);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : shifted.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        return ensureTerminal(b.toString());
    }

    /** Lowest CPU/GPU passive trip exposed by the kernel, or the Odin default. */
    static float deviceTripC() {
        float cached = tripC;
        if (cached > 0f) return cached;
        float best = 0f;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File zone : zones) {
                String type = readWord(new File(zone, "type"));
                if (type == null) continue;
                String t = type.toLowerCase(Locale.US);
                if (!(t.startsWith("cpu-") || t.startsWith("cpuss-")
                        || t.contains("gpu"))) {
                    continue;
                }
                File[] files = zone.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    String name = f.getName();
                    if (!name.startsWith("trip_point_") || !name.endsWith("_temp")) {
                        continue;
                    }
                    float value = readTempC(f);
                    if (value >= 60f && value <= 120f
                            && (best <= 0f || value < best)) {
                        best = value;
                    }
                }
            }
        }
        if (best <= 0f) best = REFERENCE_TRIP_C;
        tripC = best;
        return best;
    }

    private static String readWord(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[96];
            int n = in.read(data);
            return n <= 0 ? null : new String(data, 0, n).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float readTempC(File file) {
        String text = readWord(file);
        if (text == null || text.isEmpty()) return 0f;
        try {
            return Float.parseFloat(text.trim()) / 1000f;
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    private FanCurve() {}

    /** Pick the temperature that drives the curve: CPU, GPU, or the hotter one. */
    static float sourceTemp(float cpu, float gpu, int source) {
        if (source == 0) return cpu > 0f ? cpu : gpu;
        if (source == 1) return gpu > 0f ? gpu : cpu;
        return Math.max(cpu, gpu);
    }

    /** One-sided target curve: the chosen duty at the target, guard above. */
    static String holdCurve(float targetC) {
        return holdCurve(targetC, 100);
    }

    static String holdCurve(float targetC, int dutyPct) {
        int t = clamp(Math.round(targetC), 45, 85);
        int d = clamp(dutyPct, 10, 100);
        TreeMap<Integer, Integer> map = new TreeMap<>();
        int[][] pts = {
                {t - 15, 0},
                {t - 10, Math.round(d * 0.10f)},
                {t - 5, Math.round(d * 0.35f)},
                {t - 2, Math.round(d * 0.75f)},
                {t, d}
        };
        for (int[] p : pts) {
            int temp = clamp(p[0], 30, 100);
            Integer old = map.get(temp);
            if (old == null || p[1] > old) map.put(temp, p[1]);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        return b.toString();
    }

    /** Saved custom HOLD curve, or the generated curve ending at the target. */
    static String holdCurve(Prefs prefs, float targetC, int dutyPct) {
        String saved = prefs == null ? null : prefs.fanHoldCurve();
        if (saved != null && !saved.isEmpty()) return saved;
        return holdCurve(targetC, dutyPct);
    }

    /**
     * Hidden service curve for HOLD: the displayed curve stops at the hold
     * point, but above it the fan steps up by 5 points per °C (capped at
     * 100) so a small skip is corrected with a little noise, not full blast.
     */
    static String holdServiceCurve(Prefs prefs, float targetC, int dutyPct) {
        String base = holdCurve(prefs, targetC, dutyPct);
        int t = clamp(Math.round(targetC), 45, 85);
        int d = clamp(dutyPct, 10, 100);
        TreeMap<Integer, Integer> map = new TreeMap<>();
        for (int[] p : points(base)) {
            map.put(p[0], p[1]);
        }
        for (int dt = 1; dt <= 25; dt++) {
            int temp = t + dt;
            if (temp > 100) break;
            int guard = Math.min(100, d + Math.round(dt * 5f));
            Integer old = map.get(temp);
            if (old == null || guard > old) map.put(temp, guard);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (b.length() > 0) b.append(',');
            b.append(e.getKey()).append(':').append(e.getValue());
        }
        return ensureTerminal(b.toString());
    }

    /** 15 control points at 5 °C intervals at one duty level. */
    static String flat(int duty) {        int d = clamp(duty, 0, 100);
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
