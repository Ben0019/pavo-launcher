package com.ben.pavo;

import android.graphics.Color;

import java.io.FileOutputStream;

/**
 * The Odin 3 joystick LEDs are two SN3112 drivers, one per stick. The vendor
 * firmware exposes a write-only colour node:
 *
 *   <code>index-r:g:b</code>
 *
 * where index 1 is the main joystick light and index 2 is the handle ring.
 * Enable is one value per stick. The nodes are world-writable but SELinux may
 * still reject a normal app, so PServer is preferred when available.
 */
final class RgbControl {

    static final int MODE_STATIC = 0;
    static final int MODE_BREATH = 1;
    static final int MODE_RAINBOW = 2;
    static final int MODE_STROBE = 3;
    static final int MODE_OFF = 4;
    static final int MODE_SCREEN = 5;

    private static final String ENABLE_LEFT = "/sys/class/sn3112l/led/enable";
    private static final String ENABLE_RIGHT = "/sys/class/sn3112r/led/enable";
    private static final String COLOR_LEFT = "/sys/class/sn3112l/led/brightness";
    private static final String COLOR_RIGHT = "/sys/class/sn3112r/led/brightness";

    private static volatile boolean writeFailed;

    private RgbControl() {}

    static String transportLabel() {
        if (writeFailed) return "OFFLINE";
        return PServer.available() ? "PSERVER" : "DIRECT";
    }

    static boolean isWorking() {
        return !writeFailed;
    }

    static boolean setEnabled(boolean left, boolean right) {
        boolean ok = writeTwo(ENABLE_LEFT, left ? "1" : "0",
                ENABLE_RIGHT, right ? "1" : "0");
        writeFailed = !ok;
        return ok;
    }

    static boolean applyColors(int color, int brightness, boolean left, boolean right) {
        float scale = Math.max(0f, Math.min(1f, brightness / 100f));
        int r = Math.round(Color.red(color) * scale);
        int g = Math.round(Color.green(color) * scale);
        int b = Math.round(Color.blue(color) * scale);
        boolean ok = writeZones(r, g, b, left, right);
        writeFailed = !ok;
        return ok;
    }

    /**
     * Write both zones of each stick in one PServer command. The vendor driver
     * takes 1-... for the main light and 2-... for the handle ring; keeping all
     * four writes in a single transact avoids the per-zone binder/shell cost
     * that made fast animations stutter (the same approach Bifrost uses).
     */
    private static boolean writeZones(int r, int g, int b, boolean left, boolean right) {
        StringBuilder command = new StringBuilder(240);
        if (left) appendZone(command, COLOR_LEFT, 1, r, g, b);
        if (left) appendZone(command, COLOR_LEFT, 2, r, g, b);
        if (right) appendZone(command, COLOR_RIGHT, 1, r, g, b);
        if (right) appendZone(command, COLOR_RIGHT, 2, r, g, b);
        if (command.length() == 0) return true;
        if (PServer.available() && PServer.run(command.toString())) return true;
        boolean ok = true;
        if (left) ok &= directColor(COLOR_LEFT, r, g, b);
        if (right) ok &= directColor(COLOR_RIGHT, r, g, b);
        return ok;
    }

    private static void appendZone(StringBuilder command, String path, int index,
                                   int r, int g, int b) {
        if (command.length() > 0) command.append(" && ");
        command.append("echo ").append(index).append('-').append(r).append(':')
                .append(g).append(':').append(b).append(" > ").append(path);
    }

    static boolean applyStatic(int color, int brightness, boolean left, boolean right) {
        boolean ok = setEnabled(left, right);
        return applyColors(color, brightness, left, right) && ok;
    }

    /**
     * Fast wake path: write the sysfs nodes directly (they are world-writable)
     * so the LEDs come back as soon as the display turns on instead of waiting
     * for a PServer round trip. Falls back to the normal transport on failure.
     */
    static void wakeApply(int color, int brightness, boolean left, boolean right) {
        float scale = Math.max(0f, Math.min(1f, brightness / 100f));
        int r = Math.round(Color.red(color) * scale);
        int g = Math.round(Color.green(color) * scale);
        int b = Math.round(Color.blue(color) * scale);
        String v1 = "1-" + r + ":" + g + ":" + b;
        String v2 = "2-" + r + ":" + g + ":" + b;
        StringBuilder command = new StringBuilder();
        if (left) {
            command.append("echo 1 > ").append(ENABLE_LEFT).append("; ")
                    .append("echo '").append(v1).append("' > ").append(COLOR_LEFT).append("; ")
                    .append("echo '").append(v2).append("' > ").append(COLOR_LEFT).append("; ");
        }
        if (right) {
            command.append("echo 1 > ").append(ENABLE_RIGHT).append("; ")
                    .append("echo '").append(v1).append("' > ").append(COLOR_RIGHT).append("; ")
                    .append("echo '").append(v2).append("' > ").append(COLOR_RIGHT).append("; ");
        }
        boolean ok = true;
        if (left) {
            ok &= direct(ENABLE_LEFT, "1");
            ok &= directColor(COLOR_LEFT, r, g, b);
        }
        if (right) {
            ok &= direct(ENABLE_RIGHT, "1");
            ok &= directColor(COLOR_RIGHT, r, g, b);
        }
        if (ok) return;
        if (command.length() > 0 && PServer.available()
                && PServer.run(command.toString())) {
            return;
        }
        setEnabled(left, right);
        applyColors(color, brightness, left, right);
    }

    /** Wake path for animated modes: restore the enable only, so the first
     *  animation frame paints the colour instead of flashing the saved base. */
    static void wakeEnable(boolean left, boolean right) {
        boolean ok = true;
        if (left) ok &= direct(ENABLE_LEFT, "1");
        if (right) ok &= direct(ENABLE_RIGHT, "1");
        if (ok) return;
        StringBuilder command = new StringBuilder();
        if (left) command.append("echo 1 > ").append(ENABLE_LEFT).append("; ");
        if (right) command.append("echo 1 > ").append(ENABLE_RIGHT).append("; ");
        if (command.length() > 0 && PServer.available()
                && PServer.run(command.toString())) {
            return;
        }
        setEnabled(left, right);
    }

    private static boolean directColor(String path, int r, int g, int b) {
        return direct(path, "1-" + r + ":" + g + ":" + b)
                && direct(path, "2-" + r + ":" + g + ":" + b);
    }

    static boolean off() {
        return setEnabled(false, false);
    }

    /**
     * The vendor static control only paints index 1 (the main stick light).
     * Write index 2 (the handle ring) directly so both rings stay lit.
     */
    static void applyHandle(int color, int brightness, boolean left, boolean right) {
        float scale = Math.max(0f, Math.min(1f, brightness / 100f));
        // The vendor main-light writes use 0..25500 units (percent * 100);
        // match them so the handle rings are as bright as the main light.
        int r = Math.round(Color.red(color) * scale * 100f);
        int g = Math.round(Color.green(color) * scale * 100f);
        int b = Math.round(Color.blue(color) * scale * 100f);
        StringBuilder command = new StringBuilder(160);
        if (left) appendZone(command, COLOR_LEFT, 2, r, g, b);
        if (right) appendZone(command, COLOR_RIGHT, 2, r, g, b);
        if (command.length() == 0) return;
        if (PServer.available() && PServer.run(command.toString())) return;
        String value = "2-" + r + ":" + g + ":" + b;
        if (left) direct(COLOR_LEFT, value);
        if (right) direct(COLOR_RIGHT, value);
    }

    /** Write both the main light and handle ring for one stick. */
    private static boolean writeColor(String path, int r, int g, int b) {
        String v1 = "1-" + r + ":" + g + ":" + b;
        String v2 = "2-" + r + ":" + g + ":" + b;
        if (PServer.available()) {
            String command = "echo '" + v1 + "' > " + path
                    + "; echo '" + v2 + "' > " + path;
            if (PServer.run(command)) return true;
        }
        return direct(path, v1) && direct(path, v2);
    }

    private static boolean writeTwo(String pathA, String valueA,
                                    String pathB, String valueB) {
        if (PServer.available()) {
            String command = "echo " + valueA + " > " + pathA
                    + "; echo " + valueB + " > " + pathB;
            if (PServer.run(command)) return true;
        }
        return direct(pathA, valueA) && direct(pathB, valueB);
    }

    private static boolean direct(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int scaledColor(int color, float factor, int brightness) {
        float scale = Math.max(0f, Math.min(1f, factor * brightness / 100f));
        int r = Math.round(Color.red(color) * scale);
        int g = Math.round(Color.green(color) * scale);
        int b = Math.round(Color.blue(color) * scale);
        return Color.rgb(r, g, b);
    }

    static int rainbowColor(long elapsedMs) {
        float hue = (elapsedMs % 8000L) * 360f / 8000f;
        return Color.HSVToColor(new float[]{hue, 1f, 1f});
    }

    static int breathFactor(long elapsedMs) {
        double phase = (elapsedMs % 2600L) * 2.0 * Math.PI / 2600.0;
        return Math.round((0.12f + 0.88f
                * (float) (0.5 - 0.5 * Math.cos(phase))) * 100f);
    }
}
