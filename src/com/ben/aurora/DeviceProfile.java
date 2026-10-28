package com.ben.aurora;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Hardware profile for the handheld Aurora is running on.
 *
 * <p>AYN and Retroid devices all ship the privileged PServer service, so the
 * profile is probed with it when available and falls back to plain sysfs reads
 * everywhere else. Nothing here trusts {@code ro.product.model}: those props are
 * routinely spoofed (Play Integrity modules), while {@code ro.soc.*},
 * {@code ro.board.platform} and the /sys layout are not.
 */
final class DeviceProfile {

    static final class Cluster {
        String path;
        int[] cpus = new int[0];
        int min;
        int max;
        String[] governors = new String[0];
        String label = "";

        int cores() {
            return cpus.length == 0 ? 1 : cpus.length;
        }
    }

    final String name;
    final String manufacturer;
    final String soc;
    final String board;
    final List<Cluster> clusters;
    final String gpuPath;
    final boolean kgsl;
    final String fanDir;
    final int fanPeriod;
    final boolean rgb;
    final boolean performanceModes;
    final boolean fanControl;

    private DeviceProfile(String name, String manufacturer, String soc, String board,
                          List<Cluster> clusters, String gpuPath, boolean kgsl,
                          String fanDir, int fanPeriod, boolean rgb,
                          boolean performanceModes, boolean fanControl) {
        this.name = name;
        this.manufacturer = manufacturer;
        this.soc = soc;
        this.board = board;
        this.clusters = clusters;
        this.gpuPath = gpuPath;
        this.kgsl = kgsl;
        this.fanDir = fanDir;
        this.fanPeriod = fanPeriod;
        this.rgb = rgb;
        this.performanceModes = performanceModes;
        this.fanControl = fanControl;
    }

    Cluster efficiency() {
        return clusters.isEmpty() ? null : clusters.get(0);
    }

    Cluster performance() {
        return clusters.isEmpty() ? null : clusters.get(clusters.size() - 1);
    }

    private static volatile DeviceProfile cached;

    static DeviceProfile get(Context context) {
        DeviceProfile p = cached;
        if (p != null) return p;
        Context app = context == null ? null : context.getApplicationContext();
        if (app != null) {
            String stored = prefs(app).getString("profile", null);
            if (stored != null) {
                p = decode(stored);
                if (p != null) {
                    cached = p;
                    return p;
                }
            }
        }
        p = probe(app);
        cached = p;
        if (app != null) prefs(app).edit().putString("profile", encode(p)).apply();
        return p;
    }

    static void invalidate(Context context) {
        cached = null;
        if (context != null) {
            prefs(context.getApplicationContext()).edit().remove("profile").apply();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("aurora_device", Context.MODE_PRIVATE);
    }

    /** Writes a probe script for PServer and refreshes the cached profile. */
    static void refreshWithPServer(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        try {
            File script = new File(app.getCacheDir(), "aurora-probe.sh");
            File out = new File(app.getCacheDir(), "aurora-probe.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(script);
            fos.write(PROBE.getBytes("UTF-8"));
            fos.close();
            out.delete();
            if (!PServer.available()) return;
            if (!PServer.run("sh " + script.getAbsolutePath()
                    + " > " + out.getAbsolutePath() + " 2>&1")) return;
            // The binder call returns before the script finishes.
            for (int i = 0; i < 20 && !out.exists(); i++) Thread.sleep(60);
            Thread.sleep(120);
            String text = readAll(out);
            DeviceProfile p = parse(text, app);
            if (p != null) {
                cached = p;
                prefs(app).edit().putString("profile", encode(p)).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ probe

    private static final String PROBE =
            "echo MANUF=$(getprop ro.soc.manufacturer)\n"
            + "echo SOC=$(getprop ro.soc.model)\n"
            + "echo BOARD=$(getprop ro.board.platform)\n"
            + "echo SKU=$(getprop ro.boot.product.vendor.sku)\n"
            + "echo DT=$(cat /proc/device-tree/model 2>/dev/null)\n"
            + "for p in /sys/devices/system/cpu/cpufreq/policy*; do\n"
            + "  [ -d \"$p\" ] || continue\n"
            + "  echo \"CPU=$(basename $p)|$(cat $p/related_cpus 2>/dev/null)"
            + "|$(cat $p/cpuinfo_min_freq 2>/dev/null)"
            + "|$(cat $p/cpuinfo_max_freq 2>/dev/null)"
            + "|$(cat $p/scaling_available_governors 2>/dev/null | tr ' ' ',')\"\n"
            + "done\n"
            + "if [ -d /sys/class/kgsl/kgsl-3d0 ]; then echo GPU=kgsl\n"
            + "else for d in /sys/class/devfreq/*gpu*; do [ -d \"$d\" ] && echo GPU=$d && break; done; fi\n"
            + "for d in /sys/class/gpio*pwm* /sys/class/hwmon/hwmon*; do\n"
            + "  [ -d \"$d\" ] && echo NODE=$d\n"
            + "done\n"
            + "[ -d /sys/class/sn3112l ] && echo RGB=1\n"
            + "echo PERFMODE=$(settings get system performance_mode)\n"
            + "echo FANDIR=$(ls -d /sys/class/gpio*pwm* 2>/dev/null | head -1)\n"
            + "echo FANPERIOD=$(cat /sys/class/gpio*pwm*/period 2>/dev/null | head -1)\n";

    private static DeviceProfile parse(String text, Context app) {
        if (text == null || text.trim().isEmpty()) return null;
        String manuf = "", soc = "", board = "", sku = "", dt = "";
        String gpu = "";
        String fanDir = "";
        int fanPeriod = 50000;
        boolean rgb = false;
        boolean perfModes = false;
        List<Cluster> clusters = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1).trim();
            if (key.equals("MANUF")) manuf = value;
            else if (key.equals("SOC")) soc = value;
            else if (key.equals("BOARD")) board = value;
            else if (key.equals("SKU")) sku = value;
            else if (key.equals("DT")) dt = value;
            else if (key.equals("GPU")) gpu = value;
            else if (key.equals("RGB")) rgb = "1".equals(value);
            else if (key.equals("PERFMODE")) perfModes = value.matches("-?\\d+");
            else if (key.equals("FANDIR")) fanDir = value;
            else if (key.equals("FANPERIOD")) fanPeriod = parseInt(value, 50000);
            else if (key.equals("CPU")) {
                String[] f = value.split("\\|", -1);
                if (f.length >= 5) {
                    Cluster c = new Cluster();
                    c.path = "/sys/devices/system/cpu/cpufreq/" + f[0];
                    c.cpus = parseInts(f[1]);
                    c.min = parseInt(f[2], 0);
                    c.max = parseInt(f[3], 0);
                    c.governors = f[4].isEmpty() ? new String[0] : f[4].split(",");
                    if (c.max > 0) clusters.add(c);
                }
            }
        }
        if (clusters.isEmpty()) return null;
        return build(manuf, soc, board, sku, dt, clusters, gpu, fanDir, fanPeriod, rgb, perfModes);
    }

    /** Fast, unprivileged probe used on first launch and when PServer is absent. */
    static DeviceProfile probe(Context app) {
        String manuf = prop("ro.soc.manufacturer");
        String soc = prop("ro.soc.model");
        String board = prop("ro.board.platform");
        if (board.isEmpty()) board = prop("ro.boot.product.vendor.sku");
        String dt = readAll(new File("/proc/device-tree/model"));

        List<Cluster> clusters = new ArrayList<>();
        File cpufreq = new File("/sys/devices/system/cpu/cpufreq");
        File[] dirs = cpufreq.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                String n = d.getName();
                if (!n.startsWith("policy")) continue;
                Cluster c = new Cluster();
                c.path = d.getAbsolutePath();
                c.cpus = parseInts(readAll(new File(d, "related_cpus")));
                c.min = parseInt(readAll(new File(d, "cpuinfo_min_freq")), 0);
                c.max = parseInt(readAll(new File(d, "cpuinfo_max_freq")), 0);
                if (c.max <= 0) c.max = maxFreq(d);
                String govs = readAll(new File(d, "scaling_available_governors"));
                c.governors = govs.trim().isEmpty() ? new String[0]
                        : govs.trim().split("\\s+");
                if (c.max > 0) clusters.add(c);
            }
        }

        String gpu = "";
        if (new File("/sys/class/kgsl/kgsl-3d0").isDirectory()) {
            gpu = "kgsl";
        } else {
            File[] dev = new File("/sys/class/devfreq").listFiles();
            if (dev != null) {
                for (File d : dev) {
                    String n = d.getName().toLowerCase(Locale.US);
                    if (n.contains("gpu") || n.contains("mali")) {
                        gpu = d.getAbsolutePath();
                        break;
                    }
                }
            }
        }

        String fanDir = "";
        int fanPeriod = 50000;
        File cls = new File("/sys/class");
        File[] nodes = cls.listFiles();
        if (nodes != null) {
            for (File d : nodes) {
                String n = d.getName();
                if (n.startsWith("gpio") && n.contains("pwm")
                        && new File(d, "duty").exists()) {
                    fanDir = d.getAbsolutePath();
                    fanPeriod = parseInt(readAll(new File(d, "period")), 50000);
                    break;
                }
            }
            if (fanDir.isEmpty()) {
                for (File d : nodes) {
                    if (n(d).startsWith("hwmon") && new File(d, "pwm1").exists()) {
                        fanDir = d.getAbsolutePath();
                        fanPeriod = 255;
                        break;
                    }
                }
            }
        }

        boolean rgb = new File("/sys/class/sn3112l").isDirectory()
                || new File("/sys/class/sn3112r").isDirectory();
        boolean perfModes = app != null && readPerformanceMode(app) >= 0;

        return build(manuf, soc, board, "", dt, clusters, gpu, fanDir, fanPeriod,
                rgb, perfModes);
    }

    private static String n(File f) {
        return f.getName();
    }

    private static DeviceProfile build(String manuf, String soc, String board, String sku,
                                       String dt, List<Cluster> clusters, String gpu,
                                       String fanDir, int fanPeriod, boolean rgb,
                                       boolean perfModes) {
        clusters.sort(Comparator.comparingInt(c -> c.max));
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            if (clusters.size() == 1) c.label = "CPU";
            else if (i == 0) c.label = "EFFICIENCY";
            else if (i == clusters.size() - 1) c.label = "PERFORMANCE";
            else c.label = "PRIME";
        }
        String name = displayName(manuf, soc, board, sku, dt);
        boolean kgsl = "kgsl".equals(gpu);
        String gpuPath = kgsl ? "/sys/class/kgsl/kgsl-3d0"
                : (gpu == null || gpu.isEmpty() ? "" : gpu);
        return new DeviceProfile(name, manuf, soc, board, clusters, gpuPath, kgsl,
                fanDir == null ? "" : fanDir, fanPeriod, rgb, perfModes,
                !(fanDir == null || fanDir.isEmpty()));
    }

    /**
     * Names the hardware from the un-spoofable props. ro.product.* is skipped on
     * purpose: an Odin running a RedMagic Play Integrity module still reports
     * NX789J there.
     */
    private static String displayName(String manuf, String soc, String board,
                                      String sku, String dt) {
        String m = lower(manuf);
        String b = lower(board);
        String d = lower(dt);
        if (b.equals("sun") && (m.contains("ayn") || d.contains("sun"))) {
            return "Odin 3";
        }
        if (b.equals("kalama") && m.contains("ayn")) return "Odin 2";
        if (b.equals("taro") && m.contains("ayn")) return "Odin 2";
        if (b.equals("holi") && m.contains("ayn")) return "Odin Lite";
        if (m.contains("retroid")) {
            String extra = sku == null || sku.isEmpty() ? "" : " " + sku;
            return "Retroid" + extra;
        }
        if (m.contains("ayn")) {
            return soc == null || soc.isEmpty() ? "AYN" : "AYN " + soc;
        }
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (!model.isEmpty()) return model;
        return board == null || board.isEmpty() ? "Aurora" : board;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).trim();
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = c.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int readPerformanceMode(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(), "performance_mode", -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int maxFreq(File dir) {
        String text = readAll(new File(dir, "scaling_boost_frequencies"));
        if (text.trim().isEmpty()) {
            text = readAll(new File(dir, "scaling_available_frequencies"));
        }
        String[] parts = text.trim().split("\\s+");
        int max = 0;
        for (String p : parts) max = Math.max(max, parseInt(p, 0));
        return max;
    }

    private static int[] parseInts(String text) {
        if (text == null) return new int[0];
        String t = text.trim();
        if (t.isEmpty()) return new int[0];
        String[] parts = t.split("[,:\\s]+");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            int v = parseInt(p, -1);
            if (v >= 0) out[n++] = v;
        }
        return Arrays.copyOf(out, n);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static String readAll(File f) {
        if (f == null || !f.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ cache

    private static String encode(DeviceProfile p) {
        StringBuilder b = new StringBuilder();
        b.append(p.name).append('\u0001')
                .append(p.manufacturer).append('\u0001')
                .append(p.soc).append('\u0001')
                .append(p.board).append('\u0001')
                .append(p.gpuPath).append('\u0001')
                .append(p.kgsl).append('\u0001')
                .append(p.fanDir).append('\u0001')
                .append(p.fanPeriod).append('\u0001')
                .append(p.rgb).append('\u0001')
                .append(p.performanceModes).append('\u0001');
        for (Cluster c : p.clusters) {
            b.append(c.path).append(',').append(join(c.cpus)).append(',')
                    .append(c.min).append(',').append(c.max).append(',')
                    .append(String.join(" ", c.governors)).append(';');
        }
        return b.toString();
    }

    private static DeviceProfile decode(String s) {
        try {
            String[] f = s.split("\u0001", -1);
            if (f.length < 11) return null;
            List<Cluster> clusters = new ArrayList<>();
            for (String part : f[10].split(";")) {
                if (part.isEmpty()) continue;
                String[] c = part.split(",", -1);
                if (c.length < 5) continue;
                Cluster cl = new Cluster();
                cl.path = c[0];
                String cpus = c[1].trim();
                cl.cpus = cpus.isEmpty() ? new int[0] : parseInts(cpus.replace(' ', ','));
                cl.min = parseInt(c[2], 0);
                cl.max = parseInt(c[3], 0);
                cl.governors = c[4].trim().isEmpty() ? new String[0] : c[4].trim().split(" ");
                clusters.add(cl);
            }
            if (clusters.isEmpty()) return null;
            boolean kgsl = Boolean.parseBoolean(f[5]);
            return build(f[1], f[2], f[3], "", "", clusters,
                    kgsl ? "kgsl" : f[4], f[6], parseInt(f[7], 50000),
                    Boolean.parseBoolean(f[8]), Boolean.parseBoolean(f[9]));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String join(int[] values) {
        StringBuilder b = new StringBuilder();
        for (int v : values) {
            if (b.length() > 0) b.append(' ');
            b.append(v);
        }
        return b.toString();
    }
}
