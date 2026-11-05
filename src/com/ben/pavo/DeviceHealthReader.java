package com.ben.pavo;

import android.content.Context;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;

/**
 * Reads the Odin's battery and charger state. Most power_supply attributes are
 * world-readable; PServer is used only as a fallback when SELinux hides a node.
 */
final class DeviceHealthReader {
    static final String BATTERY = "/sys/class/power_supply/battery/";
    static final String USB = "/sys/class/power_supply/usb/";
    static final String QCOM = "/sys/class/qcom-battery/";

    private static final Object READ_LOCK = new Object();

    static final class Paths {
        String battery = BATTERY;
        String usb = USB;
    }

    private static volatile Paths cachedPaths;

    /** Finds the battery/charger power supplies instead of assuming /battery and /usb. */
    static Paths paths(Context c) {
        Paths cached = cachedPaths;
        if (cached != null) return cached;
        synchronized (READ_LOCK) {
            if (cachedPaths != null) return cachedPaths;
            Paths found = new Paths();
            try {
                File[] entries = new File("/sys/class/power_supply").listFiles();
                if (entries != null) {
                    String battery = null;
                    String usb = null;
                    int batteryScore = 0;
                    int usbScore = 0;
                    for (File dir : entries) {
                        if (dir == null || !dir.isDirectory()) continue;
                        String name = dir.getName().toLowerCase(java.util.Locale.US);
                        String type = directText(new File(dir, "type").getAbsolutePath());
                        String lower = type == null ? "" : type.toLowerCase(java.util.Locale.US);
                        int b = 0;
                        if ("battery".equals(name)) b += 6;
                        if (lower.contains("battery")) b += 4;
                        if (new File(dir, "capacity").exists()) b += 2;
                        if (new File(dir, "charge_full").exists()) b += 1;
                        if (b > batteryScore) {
                            batteryScore = b;
                            battery = dir.getAbsolutePath();
                        }
                        int u = 0;
                        if ("usb".equals(name)) u += 6;
                        if (lower.contains("usb")) u += 4;
                        else if (lower.contains("mains") || lower.contains("wireless")) u += 3;
                        if (new File(dir, "online").exists()) u += 1;
                        if (u > usbScore) {
                            usbScore = u;
                            usb = dir.getAbsolutePath();
                        }
                    }
                    if (battery != null) found.battery = battery + "/";
                    if (usb != null) found.usb = usb + "/";
                }
            } catch (Throwable ignored) {
            }
            cachedPaths = found;
            return found;
        }
    }

    static String qcomNode(String name) {
        return QCOM + name;
    }

    private static float readVoltageV(int raw) {
        if (raw == Integer.MIN_VALUE || raw <= 0) return -1f;
        if (raw >= 100_000) return raw / 1_000_000f;
        if (raw >= 1_000) return raw / 1_000f;
        return raw;
    }

    private static float readCurrentA(int raw) {
        if (raw == Integer.MIN_VALUE) return 0f;
        // Linux power_supply reports current in microamperes.  Values below
        // 1 mA are tiny but still microamperes, so do not rescale them into
        // amps (which used to turn a 12 mA idle draw into 12 A).
        return raw / 1_000_000f;
    }

    private static float readTempC(int raw) {
        if (raw == Integer.MIN_VALUE) return -1f;
        int abs = Math.abs(raw);
        if (abs >= 10_000) return raw / 1_000f;
        if (abs >= 100) return raw / 10f;
        return raw;
    }

    private static long readChargeUah(int raw) {
        if (raw <= 0) return -1;
        return raw >= 100_000 ? raw : raw * 1_000L;
    }

    private static float readResistanceMOhm(int raw) {
        if (raw <= 0) return -1f;
        return raw >= 10_000 ? raw / 1_000f : raw;
    }

    private DeviceHealthReader() {}

    static final class Snapshot {
        int level = -1;
        String status = "--";
        boolean charging;
        boolean full;
        boolean plugged;
        boolean separated;
        boolean pserver;
        String health = "--";
        int healthPct = -1;
        int cycleCount = -1;
        String cycleSource = "--";
        int soh = -1;
        float tempC = -1f;
        float voltageV = -1f;
        float currentA = 0f;
        float powerW = -1f;
        long fullUah = -1;
        long designUah = -1;
        long counterUah = -1;
        long timeToFullSec = -1;
        long timeToEmptySec = -1;
        String technology = "--";
        String model = "--";
        String chargeType = "--";
        String usbType = "--";
        String inputType = "--";
        float inputVoltageV = -1f;
        float inputCurrentA = -1f;
        float inputLimitA = -1f;
        boolean pdSupported;
        String pdRevision = "--";
        int limitPct = -1;
        int endThreshold = -1;
        int startThreshold = -1;
        int restrictChg = -1;
        int usbChargeNow = -1;
        int chargeControlLimit = -1;
        int chargeControlLimitMax = -1;
        float resistance = -1f;
        int parallelCells = -1;
        float cpuTempC = -1f;
        float gpuTempC = -1f;
    }

    /**
     * Power-supply fields the home widget needs, read straight from sysfs with
     * no PServer fallback: a hidden node must not stall the first paint.
     */
    static Snapshot readFast(Context c) {
        Snapshot s = new Snapshot();
        s.pserver = false;
        Paths p = paths(c);
        s.level = directNum(p.battery + "capacity", -1);
        if (s.level < 0 && c != null) {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (v >= 0) s.level = v;
            }
        }
        String status = directText(p.battery + "status");
        s.status = status == null ? "--" : clean(status);
        s.tempC = readTempC(directNum(p.battery + "temp", Integer.MIN_VALUE));
        s.voltageV = readVoltageV(directNum(p.battery + "voltage_now", Integer.MIN_VALUE));
        int usbOnline = directNum(p.usb + "online", 0);
        int wirelessOnline = directNum("/sys/class/power_supply/wireless/online", 0);
        // Charge separation switches "usb/online" to 0 even though the cable is
        // still attached, so VBUS voltage is the reliable cable-presence signal.
        long vbusUv = directNum(p.usb + "voltage_now", 0);
        s.plugged = usbOnline == 1 || wirelessOnline == 1 || vbusUv > 4_000_000L;
        s.charging = "Charging".equalsIgnoreCase(s.status);
        s.full = "Full".equalsIgnoreCase(s.status) || s.level >= 100;
        int usbChargeNow = directNum(QCOM + "usb_charge_now", -1);
        s.separated = s.plugged && usbChargeNow == 0;
        s.fullUah = readChargeUah(directNum(p.battery + "charge_full", -1));
        s.designUah = readChargeUah(directNum(p.battery + "charge_full_design", -1));
        if (s.designUah > 0 && s.fullUah > 0) {
            s.healthPct = Math.max(0, Math.min(100,
                    Math.round(s.fullUah * 100f / s.designUah)));
        }
        return s;
    }

    private static int directNum(String path, int fallback) {
        String v = directText(path);
        if (v == null) return fallback;
        try {
            String[] parts = v.trim().split("\\s+");
            return Integer.parseInt(parts[0]);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static Snapshot read(Context c) {
        Snapshot s = new Snapshot();
        s.pserver = PServer.available();
        Paths p = paths(c);

        s.level = num(c, p.battery + "capacity", -1);
        String status = text(c, p.battery + "status");
        s.status = status == null ? "--" : clean(status);
        s.health = clean(text(c, p.battery + "health"));
        s.technology = clean(text(c, p.battery + "technology"));
        s.model = clean(text(c, p.battery + "model_name"));
        s.chargeType = clean(text(c, p.battery + "charge_type"));
        s.usbType = clean(text(c, QCOM + "usb_real_type"));
        if (s.usbType == null || s.usbType.isEmpty()) {
            s.usbType = clean(text(c, QCOM + "usb_typec_orientation"));
        }

        s.fullUah = readChargeUah(num(c, p.battery + "charge_full", -1));
        s.designUah = readChargeUah(num(c, p.battery + "charge_full_design", -1));
        s.counterUah = readChargeUah(num(c, p.battery + "charge_counter", -1));
        s.cycleCount = findCycleCount(c, p, s);
        s.soh = num(c, QCOM + "soh", -1);
        s.resistance = readResistanceMOhm(num(c, QCOM + "resistance", -1));
        s.parallelCells = num(c, QCOM + "battery_parallel_cell_count", -1);

        s.tempC = readTempC(num(c, p.battery + "temp", Integer.MIN_VALUE));
        s.voltageV = readVoltageV(num(c, p.battery + "voltage_now", Integer.MIN_VALUE));
        s.currentA = readCurrentA(num(c, p.battery + "current_now", Integer.MIN_VALUE));
        if (s.voltageV > 0f && s.currentA != 0f) {
            s.powerW = Math.abs(s.voltageV * s.currentA);
        }

        s.timeToFullSec = num(c, p.battery + "time_to_full_now", -1);
        s.timeToEmptySec = num(c, p.battery + "time_to_empty_avg", -1);

        s.inputVoltageV = readVoltageV(num(c, p.usb + "voltage_now", Integer.MIN_VALUE));
        s.inputCurrentA = readCurrentA(num(c, p.usb + "current_now", Integer.MIN_VALUE));
        s.inputLimitA = readCurrentA(num(c, p.usb + "input_current_limit", Integer.MIN_VALUE));
        s.inputType = clean(text(c, p.usb + "type"));
        s.pdSupported = "yes".equalsIgnoreCase(
                text(c, "/sys/class/typec/port0-partner/supports_usb_power_delivery"));
        String pdRev = text(c, "/sys/class/typec/port0/usb_power_delivery_revision");
        if (pdRev != null && !pdRev.isEmpty()) s.pdRevision = clean(pdRev);

        s.limitPct = num(c, QCOM + "limit_capacity_charge", -1);
        s.endThreshold = num(c, p.battery + "charge_control_end_threshold", -1);
        s.startThreshold = num(c, p.battery + "charge_control_start_threshold", -1);
        s.restrictChg = num(c, QCOM + "restrict_chg", -1);
        s.usbChargeNow = num(c, QCOM + "usb_charge_now", -1);
        s.chargeControlLimit = num(c, p.battery + "charge_control_limit", -1);
        s.chargeControlLimitMax = num(c, p.battery + "charge_control_limit_max", -1);

        int usbOnline = num(c, p.usb + "online", 0);
        int wirelessOnline = num(c, "/sys/class/power_supply/wireless/online", 0);
        long vbusUv = num(c, p.usb + "voltage_now", 0);
        s.plugged = usbOnline == 1 || wirelessOnline == 1 || vbusUv > 4_000_000L;
        s.charging = "Charging".equalsIgnoreCase(s.status);
        s.full = "Full".equalsIgnoreCase(s.status) || s.level >= 100;
        s.separated = s.plugged && s.usbChargeNow == 0;

        if (s.designUah > 0 && s.fullUah > 0) {
            s.healthPct = Math.max(0, Math.min(100,
                    Math.round(s.fullUah * 100f / s.designUah)));
        } else if (s.soh > 0) {
            s.healthPct = Math.max(0, Math.min(100, s.soh));
        }

        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null && s.cycleCount < 0) {
            try {
                java.lang.reflect.Field f = BatteryManager.class
                        .getField("BATTERY_PROPERTY_CYCLE_COUNT");
                int id = f.getInt(null);
                int v = bm.getIntProperty(id);
                if (v >= 0) {
                    s.cycleCount = v;
                    s.cycleSource = "BatteryManager";
                }
            } catch (Throwable ignored) {
                try {
                    int v = bm.getIntProperty(6);
                    if (v >= 0) {
                        s.cycleCount = v;
                        s.cycleSource = "BatteryManager";
                    }
                } catch (Throwable ignored2) {
                }
            }
        }
        if (bm != null && s.level < 0) {
            int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (v >= 0) s.level = v;
        }

        s.cpuTempC = ThermalSensors.cpuTemp();
        s.gpuTempC = ThermalSensors.gpuTemp();
        return s;
    }

    static String text(Context c, String path) {
        String direct = directText(path);
        if (direct != null) return direct;
        if (c == null || !PServer.available()) return null;
        synchronized (READ_LOCK) {
            File cache = new File(c.getCacheDir(), "device_health_read.tmp");
            try {
                cache.delete();
                String q = path.replace("'", "'\\''");
                PServer.run("cat '" + q + "' > '" + cache.getAbsolutePath()
                        + "'; chmod 666 '" + cache.getAbsolutePath() + "'");
                for (int i = 0; i < 5; i++) {
                    String value = directText(cache.getAbsolutePath());
                    if (value != null && !value.isEmpty()) return value;
                    SystemClock.sleep(15L);
                }
            } finally {
                cache.delete();
            }
        }
        return null;
    }

    private static int findCycleCount(Context c, Paths p, Snapshot s) {
        String[] paths = {
                p.battery + "cycle_count",
                QCOM + "cycle_count",
                QCOM + "battery_cycle_count",
                "/sys/class/power_supply/bms/cycle_count"
        };
        for (String path : paths) {
            int v = num(c, path, -1);
            if (v >= 0) {
                s.cycleSource = path.startsWith(QCOM) ? "QCOM" : "PMIC";
                return v;
            }
        }
        s.cycleSource = "not exposed";
        return -1;
    }

    static int num(Context c, String path, int def) {
        String v = text(c, path);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            try {
                return Math.round(Float.parseFloat(v.trim()));
            } catch (Throwable ignored2) {
                return def;
            }
        }
    }

    private static String directText(String path) {
        if (path == null) return null;
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] data = new byte[256];
            int n = in.read(data);
            if (n <= 0) return null;
            return new String(data, 0, n).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String clean(String s) {
        if (s == null || s.isEmpty()) return "--";
        String out = s.replace('_', ' ').trim();
        if (out.length() > 54) out = out.substring(0, 51) + "…";
        return out;
    }
}
