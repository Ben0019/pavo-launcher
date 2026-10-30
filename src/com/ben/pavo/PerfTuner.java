package com.ben.pavo;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Root-backed CPU/GPU/fan control for the built-in performance tuner.
 * Only uses frequencies and governors the kernel already advertises.
 */
final class PerfTuner {

    static final int FAN_OFF = 0;
    static final int FAN_LOW = 1;
    static final int FAN_SMART = 4;
    static final int FAN_HIGH = 5;
    static final int FAN_MANUAL = 6;

    static final String[] FAN_MODE_LABELS = {"OFF", "LOW", "SMART", "HIGH", "MANUAL"};
    static final int[] FAN_MODE_VALUES = {FAN_OFF, FAN_LOW, FAN_SMART, FAN_HIGH, FAN_MANUAL};

    static final class Cpu {
        int policy;
        String path;
        String label;
        int cores;
        int floorMin;
        int[] freqs = new int[0];
        String[] governors = new String[0];
        String governor;
        int min;
        int max;
        int cur;
    }

    static final class Gpu {
        int[] freqs = new int[0];
        String[] governors = new String[0];
        String governor;
        int min;
        int max;
        int cur;
    }

    static final class Fan {
        int mode = FAN_SMART;
        int speedPref = 25000;
        int state;
        int duty;
        int period;
        int rpm;
    }

    static final class State {
        Cpu efficiency;
        Cpu performance;
        Gpu gpu;
        Fan fan;
        int performanceMode;
        float cpuTemp;
        float gpuTemp;
        boolean root;
    }

    private PerfTuner() {}

    /** Some Odin kernels expose PWM duty but report a zero tach value. */
    static int estimatedRpm(int dutyPercent) {
        if (dutyPercent <= 0) return 0;
        return Math.max(0, Math.min(100, dutyPercent)) * 180;
    }

    static State readState(Context context) {
        State s = new State();
        readCpus(s);
        s.gpu = readGpu();
        s.fan = readFan(context);
        s.performanceMode = Settings.System.getInt(context.getContentResolver(),
                "performance_mode", 0);
        readTemps(s);
        s.root = Root.isReady();
        return s;
    }

    /**
     * Reads the same visible state without ever waiting for su/PServer. Used to
     * bind the tuner controls on the first frame; the privileged refresh follows
     * on the worker thread.
     */
    static State readStateLocal(Context context) {
        State s = new State();
        readCpus(s);
        s.gpu = readGpu();
        s.fan = readFanLocal(context);
        s.performanceMode = Settings.System.getInt(context.getContentResolver(),
                "performance_mode", 0);
        readTemps(s);
        s.root = Root.isReady();
        return s;
    }

    /** Lightweight CPU/GPU temperature read for the fan curve worker. */
    static float[] readTemperatures() {
        State s = new State();
        readTemps(s);
        return new float[]{s.cpuTemp, s.gpuTemp};
    }

    static State readStateForHold(Context context) {
        State s = new State();
        readCpus(s);
        s.gpu = readGpu();
        readTemps(s);
        s.root = Root.isReady();
        return s;
    }

    static float[] temperatures() {
        State s = new State();
        readTemps(s);
        return new float[]{s.cpuTemp, s.gpuTemp};
    }

    private static DeviceProfile profile() {
        return DeviceProfile.get(null);
    }

    private static void readCpus(State s) {
        DeviceProfile p = profile();
        s.efficiency = readCpu(p.efficiency(), "EFFICIENCY");
        s.performance = readCpu(p.performance(), "PERFORMANCE");
    }

    private static Cpu readCpu(DeviceProfile.Cluster cluster, String fallbackLabel) {
        Cpu c = new Cpu();
        String base;
        if (cluster != null) {
            c.path = cluster.path;
            c.policy = -1;
            c.label = cluster.label == null || cluster.label.isEmpty()
                    ? fallbackLabel : cluster.label;
            c.cores = cluster.cores();
            c.floorMin = cluster.min > 0 ? cluster.min : 384000;
            base = cluster.path;
        } else {
            c.label = fallbackLabel;
            c.cores = 1;
            c.floorMin = 384000;
            base = "/sys/devices/system/cpu/cpufreq/policy0";
        }
        c.freqs = readInts(base + "/scaling_available_frequencies");
        if (c.freqs.length == 0) {
            c.freqs = readInts(base + "/scaling_freq_table");
        }
        c.governors = readWords(base + "/scaling_available_governors");
        c.governor = readWord(base + "/scaling_governor");
        c.min = readInt(base + "/scaling_min_freq");
        c.max = readInt(base + "/scaling_max_freq");
        c.cur = readInt(base + "/scaling_cur_freq");
        if (c.max <= 0 && c.freqs.length > 0) c.max = c.freqs[c.freqs.length - 1];
        if (c.min <= 0) c.min = c.floorMin;
        return c;
    }

    private static Gpu readGpu() {
        Gpu g = new Gpu();
        DeviceProfile p = profile();
        String base = p.gpuPath == null ? "" : p.gpuPath;
        if (base.isEmpty()) return g;
        if (p.kgsl) {
            g.freqs = readInts(base + "/devfreq/available_frequencies");
            if (g.freqs.length == 0) g.freqs = readInts(base + "/gpu_available_frequencies");
            Arrays.sort(g.freqs);
            g.governors = readWords(base + "/devfreq/available_governors");
            g.governor = readWord(base + "/devfreq/governor");
            g.min = readInt(base + "/devfreq/min_freq");
            g.max = readInt(base + "/devfreq/max_freq");
            g.cur = readInt(base + "/devfreq/cur_freq");
        } else {
            g.freqs = readInts(base + "/available_frequencies");
            Arrays.sort(g.freqs);
            g.governors = readWords(base + "/available_governors");
            g.governor = readWord(base + "/governor");
            g.min = readInt(base + "/min_freq");
            g.max = readInt(base + "/max_freq");
            g.cur = readInt(base + "/cur_freq");
        }
        if (g.max <= 0 && g.freqs.length > 0) g.max = g.freqs[g.freqs.length - 1];
        return g;
    }

    private static String fanDir() {
        String d = profile().fanDir;
        return d == null ? "" : d;
    }

    private static Fan readFan(Context context) {
        Fan f = new Fan();
        ContentResolver cr = context.getContentResolver();
        f.mode = Settings.System.getInt(cr, "fan_mode", FAN_SMART);
        f.speedPref = Settings.System.getInt(cr, "fan_speed", 25000);
        f.state = readFanInt("state");
        f.duty = readFanInt("duty");
        f.period = readFanInt("period");
        f.rpm = readFanInt("speed");
        return f;
    }

    private static Fan readFanLocal(Context context) {
        Fan f = new Fan();
        ContentResolver cr = context.getContentResolver();
        f.mode = Settings.System.getInt(cr, "fan_mode", FAN_SMART);
        f.speedPref = Settings.System.getInt(cr, "fan_speed", 25000);
        String dir = fanDir();
        f.state = readInt(dir + "/state");
        f.duty = readInt(dir + "/duty");
        f.period = readInt(dir + "/period");
        f.rpm = readInt(dir + "/speed");
        return f;
    }

    private static int readFanInt(String name) {
        String dir = fanDir();
        if (dir.isEmpty()) return 0;
        int direct = readInt(dir + "/" + name);
        if (direct > 0) return direct;
        String out = Root.run("cat " + dir + "/" + name).trim();
        return parseInt(out);
    }

    private static void readTemps(State s) {
        s.cpuTemp = ThermalSensors.cpuTemp();
        s.gpuTemp = ThermalSensors.gpuTemp();
    }

    static boolean prepare() {
        boolean ok = Root.ensure();
        if (!ok) return false;
        Root.write(chmodCpu());
        Root.write(chmodGpu());
        return true;
    }

    private static String chmodCpu() {
        StringBuilder b = new StringBuilder();
        for (DeviceProfile.Cluster c : profile().clusters) {
            if (b.length() > 0) b.append("; ");
            b.append("chmod 666 ").append(c.path)
                    .append("/scaling_max_freq ").append(c.path)
                    .append("/scaling_min_freq ").append(c.path)
                    .append("/scaling_governor");
        }
        return b.toString();
    }

    private static String chmodGpu() {
        DeviceProfile p = profile();
        if (p.gpuPath == null || p.gpuPath.isEmpty()) return "true";
        if (p.kgsl) {
            return "chmod 666 " + p.gpuPath + "/max_gpuclk " + p.gpuPath
                    + "/devfreq/max_freq " + p.gpuPath + "/devfreq/min_freq "
                    + p.gpuPath + "/devfreq/governor";
        }
        return "chmod 666 " + p.gpuPath + "/max_freq " + p.gpuPath
                + "/min_freq " + p.gpuPath + "/governor";
    }

    /**
     * PServer-only preparation for the benchmark. It deliberately never touches
     * su, so a missing PServer leaves the stress test read-only instead of
     * prompting for root.
     */
    static boolean preparePServer() {
        if (!PServer.available()) return false;
        for (DeviceProfile.Cluster c : profile().clusters) {
            PServer.run("chmod 666 " + c.path + "/scaling_max_freq "
                    + c.path + "/scaling_min_freq " + c.path + "/scaling_governor");
        }
        PServer.run(chmodGpu());
        return true;
    }

    static void setCpuPServer(Cpu c, int max, int min, String governor) {
        if (c == null || c.freqs.length == 0 || !PServer.available()) return;
        int lowest = c.freqs[0];
        max = Math.max(min, max);
        min = Math.max(c.floorMin, min);
        max = Math.max(min, max);
        String d = cpuPath(c);
        if (governor != null && !governor.isEmpty()) {
            PServer.run("echo " + governor + " > " + d + "/scaling_governor");
        }
        PServer.run("echo " + lowest + " > " + d + "/scaling_min_freq");
        PServer.run("echo " + max + " > " + d + "/scaling_max_freq");
        PServer.run("echo " + min + " > " + d + "/scaling_min_freq");
    }

    static void setGpuPServer(Gpu g, int max, int min) {
        if (g == null || g.freqs.length == 0 || !PServer.available()) return;
        int lowest = g.freqs[0];
        min = Math.max(lowest, min);
        max = Math.max(min, max);
        DeviceProfile p = profile();
        String d = p.gpuPath;
        if (d == null || d.isEmpty()) return;
        PServer.run("echo " + max + " > " + d
                + (p.kgsl ? "/max_gpuclk" : "/max_freq"));
        PServer.run("echo " + lowest + " > " + d + "/"
                + (p.kgsl ? "devfreq/min_freq" : "min_freq"));
        PServer.run("echo " + max + " > " + d + "/"
                + (p.kgsl ? "devfreq/max_freq" : "max_freq"));
        PServer.run("echo " + min + " > " + d + "/"
                + (p.kgsl ? "devfreq/min_freq" : "min_freq"));
    }

    static void setFanModePServer(int mode) {
        if (!PServer.available()) return;
        PServer.run("settings put system fan_mode " + mode
                + "; settings put global fan_mode " + mode);
    }

    /** Bench mode fan: manual 100% so the blower ramps immediately. */
    static void setFanManualPServer(int percent) {
        if (!PServer.available()) return;
        int value = fanValue(percent);
        PServer.run("settings put system fan_mode " + FAN_MANUAL
                + "; settings put global fan_mode " + FAN_MANUAL
                + "; settings put system fan_speed " + value
                + "; settings put global fan_speed " + value);
    }

    static void setFanSpeedPServer(int raw) {
        if (!PServer.available()) return;
        PServer.run("settings put system fan_speed " + raw
                + "; settings put global fan_speed " + raw);
    }

    static void setCpu(Cpu c, int max, int min, String governor) {
        if (c == null || c.freqs.length == 0) return;
        int lowest = c.freqs[0];
        max = Math.max(min, max);
        min = Math.max(c.floorMin, min);
        max = Math.max(min, max);
        String d = cpuPath(c);
        StringBuilder cmd = new StringBuilder("d=").append(d).append("; ");
        if (governor != null && !governor.isEmpty()) {
            cmd.append("echo ").append(governor).append(" >$d/scaling_governor; ");
        }
        cmd.append("echo ").append(lowest).append(" >$d/scaling_min_freq; ");
        cmd.append("echo ").append(max).append(" >$d/scaling_max_freq; ");
        cmd.append("echo ").append(min).append(" >$d/scaling_min_freq");
        Root.write(cmd.toString());
    }

    static void setGpu(Gpu g, int max, int min) {
        if (g == null || g.freqs.length == 0) return;
        DeviceProfile p = profile();
        String d = p.gpuPath;
        if (d == null || d.isEmpty()) return;
        int lowest = g.freqs[0];
        min = Math.max(lowest, min);
        max = Math.max(min, max);
        if (p.kgsl) {
            Root.write("d=" + d + "; echo " + max + " >$d/max_gpuclk; "
                    + "echo " + lowest + " >$d/devfreq/min_freq; "
                    + "echo " + max + " >$d/devfreq/max_freq; "
                    + "echo " + min + " >$d/devfreq/min_freq");
        } else {
            Root.write("d=" + d + "; echo " + max + " >$d/max_freq; "
                    + "echo " + lowest + " >$d/min_freq; "
                    + "echo " + max + " >$d/max_freq; "
                    + "echo " + min + " >$d/min_freq");
        }
    }

    /** Duty value for a percentage on this device's fan controller. */
    private static int fanValue(int percent) {
        int period = profile().fanPeriod;
        if (period <= 0) period = 50000;
        return Math.max(1, Math.min(100, percent)) * period / 100;
    }

    static void setPerformanceMode(int mode) {
        Root.write("settings put system performance_mode " + mode
                + "; settings put global performance_mode " + mode);
    }

    /** Live Odin performance mode, or -1 when it cannot be read. */
    static int readPerformanceMode(Context context) {
        if (context == null) return -1;
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    "performance_mode", -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static void setFanMode(Context context, int mode) {
        Root.write("settings put system fan_mode " + mode
                + "; settings put global fan_mode " + mode);
    }

    static void setFanSpeed(int raw) {
        Root.write("settings put system fan_speed " + raw);
    }

    /** Live fan mode from system settings, or -1 when it cannot be read. */
    static int fanMode(Context context) {
        if (context == null) return -1;
        try {
            return Settings.System.getInt(context.getContentResolver(), "fan_mode", -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Live fan duty setting (0-50000), or -1 when it cannot be read. */
    static int fanSpeed(Context context) {
        if (context == null) return -1;
        try {
            return Settings.System.getInt(context.getContentResolver(), "fan_speed", -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * True when another app, the game panel or a preset switch has taken the fan
     * away from the curve, so the controller has to write its duty again.
     */
    static boolean fanManualDrifted(Context context, int dutyPercent) {
        if (dutyPercent < 0) return true;
        if (fanMode(context) != FAN_MANUAL) return true;
        int speed = fanSpeed(context);
        return speed >= 0 && speed != dutyPercent * 500;
    }

    static void setFanManual(int percent) {
        int value = fanValue(percent);
        Root.write("settings put system fan_mode " + FAN_MANUAL
                + "; settings put global fan_mode " + FAN_MANUAL
                + "; settings put system fan_speed " + value
                + "; settings put global fan_speed " + value);
    }

    static int closestIndex(int[] values, int target) {
        if (values == null || values.length == 0) return 0;
        int best = 0;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            long d = Math.abs((long) values[i] - target);
            if (d < distance) {
                best = i;
                distance = d;
            }
        }
        return best;
    }

    private static String cpuPath(Cpu c) {
        if (c.path != null && !c.path.isEmpty()) return c.path;
        return "/sys/devices/system/cpu/cpufreq/policy" + c.policy;
    }

    private static int[] readInts(String path) {
        String text = readText(path);
        if (text == null) return new int[0];
        String[] parts = text.trim().split("\\s+");
        int count = 0;
        int[] tmp = new int[parts.length];
        for (String part : parts) {
            int v = parseInt(part);
            if (v > 0) tmp[count++] = v;
        }
        int[] out = Arrays.copyOf(tmp, count);
        Arrays.sort(out);
        return out;
    }

    private static String[] readWords(String path) {
        String text = readText(path);
        if (text == null) return new String[0];
        String[] parts = text.trim().split("\\s+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) out.add(part);
        }
        return out.toArray(new String[0]);
    }

    private static String readWord(String path) {
        String text = readText(path);
        return text == null ? null : text.trim();
    }

    private static String readText(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? "" : line;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readInt(String path) {
        return parseInt(readText(path));
    }

    private static int parseInt(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Root shell used only for short write commands and one-shot reads. */
    static final class Root {
        private static Process process;
        private static BufferedWriter writer;
        private static volatile boolean ready;
        private static volatile boolean checked;
        private static volatile int mode; // 1 PServer, 2 root shell
        private static final Object LOCK = new Object();

        static boolean isReady() {
            return ready;
        }

        static boolean isPServer() {
            return mode == 1;
        }

        static String executionName() {
            if (!ready) return null;
            return mode == 1 ? "PSERVER" : "ROOT";
        }

        static void resetCheck() {
            synchronized (LOCK) {
                checked = false;
                ready = false;
                mode = 0;
                PServer.reset();
            }
        }

        /** PServer is the only privileged channel; there is no su fallback. */
        static boolean ensure() {
            synchronized (LOCK) {
                if (checked) return ready;
                checked = true;
                ready = PServer.available();
                mode = ready ? 1 : 0;
                return ready;
            }
        }

        static String run(String command) {
            return PServer.exec(command);
        }

        static void write(String command) {
            writeOk(command);
        }

        static boolean writeOk(String command) {
            if (command == null || command.isEmpty()) return false;
            synchronized (LOCK) {
                if (mode == 0 && PServer.available()) mode = 1;
                if (mode != 1) return false;
                if (command.length() <= 250) {
                    return PServer.run(command);
                }
                // Keep each PServer transaction below its 255-byte limit.
                for (String part : command.split(";")) {
                    if (!PServer.run(part.trim())) return false;
                }
                return true;
            }
        }

        static void close() {
            synchronized (LOCK) {
                try {
                    if (writer != null) writer.close();
                } catch (Throwable ignored) {
                }
                try {
                    if (process != null) process.destroy();
                } catch (Throwable ignored) {
                }
                writer = null;
                process = null;
            }
        }
    }
}
