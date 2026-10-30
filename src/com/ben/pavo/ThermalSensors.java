package com.ben.pavo;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared thermal reading used by the Tuner, Fan Control and performance overlay.
 *
 * Snapdragon exposes two sensors for each CPU core and a bank of GPU sensors.
 * The previous code took the maximum of every duplicated zone, which read the
 * hottest internal sensor rather than the filtered per-core values Android's
 * thermal HAL reports. Use sensor 0 of each CPU core (or the max only as a
 * fallback), plus the GPU sensor bank and kgsl temperature.
 */
final class ThermalSensors {
    private static final Pattern CPU_ZONE = Pattern.compile("^cpu-(\\d+)-(\\d+)-(\\d+)$");
    private static final Pattern CPUSS_ZONE = Pattern.compile("^cpuss-.*$");
    private static final Pattern GPU_ZONE = Pattern.compile("^.*gpu.*$");

    private static final List<File> cpuFiles = new ArrayList<>();
    private static final List<File> fallbackCpuFiles = new ArrayList<>();
    private static final List<File> gpuFiles = new ArrayList<>();
    private static File kgslTemp() {
        DeviceProfile p = DeviceProfile.get(null);
        if (p.kgsl && p.gpuPath != null && !p.gpuPath.isEmpty()) {
            return new File(p.gpuPath, "temp");
        }
        return new File("/sys/class/kgsl/kgsl-3d0/temp");
    }
    private static boolean scanned;

    private ThermalSensors() {}

    static float cpuTemp() {
        ensureScanned();
        float best = 0f;
        for (File f : cpuFiles) best = Math.max(best, readTemp(f));
        if (best <= 0f) {
            for (File f : fallbackCpuFiles) best = Math.max(best, readTemp(f));
        }
        return best;
    }

    /**
     * Hottest core junction sensor, including the raw second sensor per core.
     * The HAL-filtered sensor used by cpuTemp() trails a load step by seconds,
     * which is the lag Smart was blamed for.
     */
    static float cpuTempRaw() {
        ensureScanned();
        float best = 0f;
        for (File f : cpuFiles) best = Math.max(best, readTemp(f));
        for (File f : fallbackCpuFiles) best = Math.max(best, readTemp(f));
        return best;
    }

    static float gpuTemp() {
        ensureScanned();
        float best = readTemp(kgslTemp());
        for (File f : gpuFiles) best = Math.max(best, readTemp(f));
        return best;
    }

    private static synchronized void ensureScanned() {
        if (scanned) return;
        scanned = true;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return;
        for (File zone : zones) {
            String type = readWord(new File(zone, "type"));
            if (type == null) continue;
            String t = type.toLowerCase(Locale.US);
            File temp = new File(zone, "temp");
            Matcher cpu = CPU_ZONE.matcher(t);
            if (cpu.matches()) {
                // Sensor suffix 0 matches the per-core values reported by the HAL.
                if ("0".equals(cpu.group(3))) cpuFiles.add(temp);
                fallbackCpuFiles.add(temp);
            } else if (CPUSS_ZONE.matcher(t).matches()) {
                fallbackCpuFiles.add(temp);
            } else if (GPU_ZONE.matcher(t).matches()) {
                gpuFiles.add(temp);
            }
        }
    }

    private static float readTemp(File file) {
        if (file == null) return 0f;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[32];
            int n = in.read(data);
            if (n <= 0) return 0f;
            String text = new String(data, 0, n).trim();
            float value = Float.parseFloat(text) / 1000f;
            return value > 0f && value < 150f ? value : 0f;
        } catch (Throwable ignored) {
            return 0f;
        }
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
}
