package com.ben.aurora;

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
        boolean ok = true;
        if (left) ok &= writeColor(COLOR_LEFT, r, g, b);
        if (right) ok &= writeColor(COLOR_RIGHT, r, g, b);
        if (!ok) writeFailed = true;
        else writeFailed = false;
        return ok;
    }

    static boolean applyStatic(int color, int brightness, boolean left, boolean right) {
        boolean ok = setEnabled(left, right);
        return applyColors(color, brightness, left, right) && ok;
    }

    static boolean off() {
        return setEnabled(false, false);
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
