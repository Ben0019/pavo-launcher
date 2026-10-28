package com.ben.aurora;

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
        int limitPct = -1;
        int endThreshold = -1;
        int startThreshold = -1;
        int restrictChg = -1;
        int usbChargeNow = -1;
        int chargeControlLimit = -1;
        int chargeControlLimitMax = -1;
        int resistance = -1;
        int parallelCells = -1;
        float cpuTempC = -1f;
        float gpuTempC = -1f;
    }

    static Snapshot read(Context c) {
        Snapshot s = new Snapshot();
        s.pserver = PServer.available();

        s.level = num(c, BATTERY + "capacity", -1);
        String status = text(c, BATTERY + "status");
        s.status = status == null ? "--" : clean(status);
        s.health = clean(text(c, BATTERY + "health"));
        s.technology = clean(text(c, BATTERY + "technology"));
        s.model = clean(text(c, BATTERY + "model_name"));
        s.chargeType = clean(text(c, BATTERY + "charge_type"));
        s.usbType = clean(text(c, QCOM + "usb_real_type"));
        if (s.usbType == null || s.usbType.isEmpty()) {
            s.usbType = clean(text(c, QCOM + "usb_typec_orientation"));
        }

        s.fullUah = num(c, BATTERY + "charge_full", -1);
        s.designUah = num(c, BATTERY + "charge_full_design", -1);
        s.counterUah = num(c, BATTERY + "charge_counter", -1);
        s.cycleCount = findCycleCount(c, s);
        s.soh = num(c, QCOM + "soh", -1);
        s.resistance = num(c, QCOM + "resistance", -1);
        s.parallelCells = num(c, QCOM + "battery_parallel_cell_count", -1);

        int temp = num(c, BATTERY + "temp", Integer.MIN_VALUE);
        if (temp != Integer.MIN_VALUE) s.tempC = temp / 10f;
        int voltage = num(c, BATTERY + "voltage_now", Integer.MIN_VALUE);
        if (voltage != Integer.MIN_VALUE) s.voltageV = voltage / 1_000_000f;
        int current = num(c, BATTERY + "current_now", Integer.MIN_VALUE);
        if (current != Integer.MIN_VALUE) s.currentA = current / 1_000_000f;
        if (s.voltageV > 0f && s.currentA != 0f) {
            s.powerW = Math.abs(s.voltageV * s.currentA);
        }

        s.timeToFullSec = num(c, BATTERY + "time_to_full_now", -1);
        s.timeToEmptySec = num(c, BATTERY + "time_to_empty_avg", -1);

        s.limitPct = num(c, QCOM + "limit_capacity_charge", -1);
        s.endThreshold = num(c, BATTERY + "charge_control_end_threshold", -1);
        s.startThreshold = num(c, BATTERY + "charge_control_start_threshold", -1);
        s.restrictChg = num(c, QCOM + "restrict_chg", -1);
        s.usbChargeNow = num(c, QCOM + "usb_charge_now", -1);
        s.chargeControlLimit = num(c, BATTERY + "charge_control_limit", -1);
        s.chargeControlLimitMax = num(c, BATTERY + "charge_control_limit_max", -1);

        int usbOnline = num(c, USB + "online", 0);
        int wirelessOnline = num(c, "/sys/class/power_supply/wireless/online", 0);
        // Cable presence comes from the USB/wireless power-supply, not from the
        // battery status (which can stay Full for a while after unplugging).
        s.plugged = usbOnline == 1 || wirelessOnline == 1;
        s.charging = "Charging".equalsIgnoreCase(s.status);
        s.full = "Full".equalsIgnoreCase(s.status) || s.level >= 100;
        s.separated = s.plugged && s.usbChargeNow == 0;

        if (s.designUah > 0 && s.fullUah > 0) {
            s.healthPct = Math.max(0, Math.min(100,
                    Math.round(s.fullUah * 100f / s.designUah)));
        } else if (s.soh > 0) {
            s.healthPct = Math.max(0, Math.min(100, s.soh));
        }

        // If a vendor node is hidden from the app process, BatteryManager still
        // gives the essential level/status/plugged state.
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

    private static int findCycleCount(Context c, Snapshot s) {
        String[] paths = {
                BATTERY + "cycle_count",
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
