package com.ben.pavo;

import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
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
    static final int MODE_MULTI = 6;

    private static final String ENABLE_LEFT = "/sys/class/sn3112l/led/enable";
    private static final String ENABLE_RIGHT = "/sys/class/sn3112r/led/enable";
    private static final String COLOR_LEFT = "/sys/class/sn3112l/led/brightness";
    private static final String COLOR_RIGHT = "/sys/class/sn3112r/led/brightness";

    // AYN and Retroid share the vendor com.ro.* settings service.  These keys
    // are confirmed on the Odin 3 and Retroid Pocket 6 and let the firmware
    // apply the colour to either the SN3112 or HTR3212 controller.
    private static final String KEY_COLOR = "joystick_led_light_picker_color";
    private static final String KEY_ENABLE = "joystick_light_enabled";
    private static final String KEY_BRIGHTNESS = "led_light_brightness_percent";
    private static final long SETTINGS_FRAME_MS = 100L;

    private static volatile boolean writeFailed;
    private static volatile Boolean settingsPresent;
    private static volatile Boolean sysfsPresent;
    private static String lastEnabled;
    private static String lastColorPair;
    private static long lastSettingsAt;
    private static boolean brightnessSet;
    private static final Object FAST_LOCK = new Object();
    private static final java.util.HashMap<String, FileOutputStream> FAST_STREAMS =
            new java.util.HashMap<>();

    private RgbControl() {}

    private static boolean sysfsAvailable() {
        Boolean cached = sysfsPresent;
        if (cached != null) return cached;
        boolean ok = new File(ENABLE_LEFT).exists() || new File(ENABLE_RIGHT).exists()
                || new File(COLOR_LEFT).exists() || new File(COLOR_RIGHT).exists();
        sysfsPresent = ok;
        return ok;
    }

    /** The shared vendor settings key exists on Odin 3, RP6 and their siblings. */
    static boolean settingsAvailable() {
        Boolean cached = settingsPresent;
        if (cached != null) return cached;
        Context context = appContext();
        if (context == null) return false;
        try {
            String value = Settings.System.getString(context.getContentResolver(), KEY_COLOR);
            boolean ok = value != null;
            settingsPresent = ok;
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Context appContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method current = thread.getMethod("currentApplication");
            return (Context) current.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean settingsWritable() {
        return settingsAvailable() && PServer.available();
    }

    private static boolean writeSettings(String key, String value) {
        return PServer.run("settings put system " + key + " '" + value + "'");
    }

    private static boolean setEnabledSettings(boolean left, boolean right) {
        if (!settingsWritable()) return false;
        String value = (left ? "1" : "0") + "," + (right ? "1" : "0");
        if (value.equals(lastEnabled)) return true;
        if (!writeSettings(KEY_ENABLE, value)) return false;
        lastEnabled = value;
        return true;
    }

    /**
     * Writes one ARGB colour per stick and lets the vendor service drive the
     * panel driver.  Hardware brightness is pinned to 1.0 and the requested
     * brightness is baked into the colour, so animated frames only need one
     * settings write instead of two.
     */
    private static boolean applyColorsSettings(int leftColor, int rightColor,
                                               int brightness, boolean left,
                                               boolean right) {
        if (!settingsWritable()) return false;
        long now = SystemClock.uptimeMillis();
        String pair = argb(leftColor, brightness) + "," + argb(rightColor, brightness);
        if (pair.equals(lastColorPair)) return true;
        if (now - lastSettingsAt < SETTINGS_FRAME_MS) return true;
        if (!brightnessSet) {
            if (writeSettings(KEY_BRIGHTNESS, "1.0")) brightnessSet = true;
        }
        if (!setEnabledSettings(left, right)) return false;
        if (!writeSettings(KEY_COLOR, pair)) return false;
        lastColorPair = pair;
        lastSettingsAt = now;
        return true;
    }

    private static String argb(int color, int brightness) {
        float scale = Math.max(0f, Math.min(1f, brightness / 100f));
        return String.format(java.util.Locale.US, "#ff%02x%02x%02x",
                Math.round(Color.red(color) * scale),
                Math.round(Color.green(color) * scale),
                Math.round(Color.blue(color) * scale));
    }

    static String transportLabel() {
        if (writeFailed) return "OFFLINE";
        return PServer.available() ? "PSERVER" : "DIRECT";
    }

    static boolean isWorking() {
        return !writeFailed;
    }

    static boolean setEnabled(boolean left, boolean right) {
        boolean ok = false;
        if (sysfsAvailable()) {
            ok = writeTwo(ENABLE_LEFT, left ? "1" : "0",
                    ENABLE_RIGHT, right ? "1" : "0");
        }
        if (!ok) ok = setEnabledSettings(left, right);
        writeFailed = !ok;
        return ok;
    }

    static boolean applyColors(int color, int brightness, boolean left, boolean right) {
        boolean ok = false;
        if (sysfsAvailable()) {
            float scale = Math.max(0f, Math.min(1f, brightness / 100f));
            int r = Math.round(Color.red(color) * scale);
            int g = Math.round(Color.green(color) * scale);
            int b = Math.round(Color.blue(color) * scale);
            ok = true;
            if (left) ok &= writeColor(COLOR_LEFT, r, g, b);
            if (right) ok &= writeColor(COLOR_RIGHT, r, g, b);
        }
        if (!ok) ok = applyColorsSettings(color, color, brightness, left, right);
        writeFailed = !ok;
        return ok;
    }

    /**
     * Paint a different colour on each stick in one pass. The ring itself is
     * a single RGB zone on this hardware, so a spatial gradient around one
     * ring is not possible; the multicolour mode uses the two sticks as two
     * simultaneous colours instead.
     */
    static boolean applyColorsLR(int leftColor, int rightColor, int brightness,
                                 boolean left, boolean right) {
        boolean ok = false;
        if (sysfsAvailable()) {
            float scale = Math.max(0f, Math.min(1f, brightness / 100f));
            ok = true;
            if (left) {
                ok &= writeColor(COLOR_LEFT,
                        Math.round(Color.red(leftColor) * scale),
                        Math.round(Color.green(leftColor) * scale),
                        Math.round(Color.blue(leftColor) * scale));
            }
            if (right) {
                ok &= writeColor(COLOR_RIGHT,
                        Math.round(Color.red(rightColor) * scale),
                        Math.round(Color.green(rightColor) * scale),
                        Math.round(Color.blue(rightColor) * scale));
            }
        }
        if (!ok) ok = applyColorsSettings(leftColor, rightColor, brightness, left, right);
        writeFailed = !ok;
        return ok;
    }

    /** One hue of the multicolour cycle; the two sticks use different offsets. */
    static int multiColor(long elapsedMs, float baseHue, int offsetDeg) {
        float hue = (baseHue + offsetDeg + (elapsedMs % 6000L) * 360f / 6000f) % 360f;
        if (hue < 0f) hue += 360f;
        return Color.HSVToColor(new float[]{hue, 1f, 1f});
    }

    /** Direct-write animation path: the nodes are world-writable, so this
     *  avoids a PServer round trip on every frame. Falls back to the normal
     *  transport if the kernel rejects the write. */
    static boolean applyColorsFast(int color, int brightness,
                                   boolean left, boolean right) {
        if (!sysfsAvailable()) {
            return applyColorsSettings(color, color, brightness, left, right);
        }
        float scale = Math.max(0f, Math.min(1f, brightness / 100f));
        int r = Math.round(Color.red(color) * scale);
        int g = Math.round(Color.green(color) * scale);
        int b = Math.round(Color.blue(color) * scale);
        // Zone 2 is not wired on the Odin 3 ring, so the animation path
        // writes only the single live zone per stick. That halves the sysfs
        // round trips and roughly doubles the achievable frame rate.
        boolean ok = true;
        if (left) ok &= directFast(COLOR_LEFT, "1-" + r + ":" + g + ":" + b);
        if (right) ok &= directFast(COLOR_RIGHT, "1-" + r + ":" + g + ":" + b);
        if (ok) {
            writeFailed = false;
            return true;
        }
        return applyColors(color, brightness, left, right);
    }

    /** Direct-write enable for the animation loop. */
    static boolean setEnabledFast(boolean left, boolean right) {
        if (!sysfsAvailable()) return setEnabledSettings(left, right);
        boolean ok = true;
        if (left) ok &= directFast(ENABLE_LEFT, "1");
        if (right) ok &= directFast(ENABLE_RIGHT, "1");
        if (ok) return true;
        return setEnabled(left, right);
    }

    /** Keeps the sysfs descriptors open: the animation path writes hundreds
     *  of frames, and opening/closing every node dominated the frame time. */
    private static boolean directFast(String path, String value) {
        synchronized (FAST_LOCK) {
            try {
                FileOutputStream out = FAST_STREAMS.get(path);
                if (out == null) {
                    out = new FileOutputStream(path);
                    FAST_STREAMS.put(path, out);
                }
                out.write(value.getBytes());
                out.flush();
                return true;
            } catch (Throwable ignored) {
            }
            FileOutputStream stale = FAST_STREAMS.remove(path);
            if (stale != null) {
                try { stale.close(); } catch (Throwable ignored) {}
            }
            try {
                FileOutputStream out = new FileOutputStream(path);
                out.write(value.getBytes());
                out.flush();
                FAST_STREAMS.put(path, out);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static boolean applyStatic(int color, int brightness, boolean left, boolean right) {
        boolean ok = setEnabled(left, right);
        return applyColors(color, brightness, left, right) && ok;
    }

    /** Static paint with an independent colour per stick. */
    static boolean applyStaticLR(int leftColor, int rightColor, int brightness,
                                 boolean left, boolean right) {
        boolean ok = setEnabled(left, right);
        return applyColorsLR(leftColor, rightColor, brightness, left, right) && ok;
    }

    /**
     * Fast wake path: write the sysfs nodes directly (they are world-writable)
     * so the LEDs come back as soon as the display turns on instead of waiting
     * for a PServer round trip. Falls back to the normal transport on failure.
     */
    static void wakeApply(int leftColor, int rightColor, int brightness,
                          boolean left, boolean right) {
        if (!sysfsAvailable()) {
            applyColorsSettings(leftColor, rightColor, brightness, left, right);
            return;
        }
        float scale = Math.max(0f, Math.min(1f, brightness / 100f));
        int lr = Math.round(Color.red(leftColor) * scale);
        int lg = Math.round(Color.green(leftColor) * scale);
        int lb = Math.round(Color.blue(leftColor) * scale);
        int rr = Math.round(Color.red(rightColor) * scale);
        int rg = Math.round(Color.green(rightColor) * scale);
        int rb = Math.round(Color.blue(rightColor) * scale);
        String l1 = "1-" + lr + ":" + lg + ":" + lb;
        String l2 = "2-" + lr + ":" + lg + ":" + lb;
        String r1 = "1-" + rr + ":" + rg + ":" + rb;
        String r2 = "2-" + rr + ":" + rg + ":" + rb;
        StringBuilder command = new StringBuilder();
        if (left) {
            command.append("echo 1 > ").append(ENABLE_LEFT).append("; ")
                    .append("echo '").append(l1).append("' > ").append(COLOR_LEFT).append("; ")
                    .append("echo '").append(l2).append("' > ").append(COLOR_LEFT).append("; ");
        }
        if (right) {
            command.append("echo 1 > ").append(ENABLE_RIGHT).append("; ")
                    .append("echo '").append(r1).append("' > ").append(COLOR_RIGHT).append("; ")
                    .append("echo '").append(r2).append("' > ").append(COLOR_RIGHT).append("; ");
        }
        boolean ok = true;
        if (left) {
            ok &= direct(ENABLE_LEFT, "1");
            ok &= directColor(COLOR_LEFT, lr, lg, lb);
        }
        if (right) {
            ok &= direct(ENABLE_RIGHT, "1");
            ok &= directColor(COLOR_RIGHT, rr, rg, rb);
        }
        if (ok) return;
        if (command.length() > 0 && PServer.available()
                && PServer.run(command.toString())) {
            return;
        }
        setEnabled(left, right);
        applyColorsLR(leftColor, rightColor, brightness, left, right);
    }

    /** Wake path for animated modes: restore the enable only, so the first
     *  animation frame paints the colour instead of flashing the saved base. */
    static void wakeEnable(boolean left, boolean right) {
        if (!sysfsAvailable()) {
            setEnabledSettings(left, right);
            return;
        }
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
        double perceived = 0.12 + 0.88 * (0.5 - 0.5 * Math.cos(phase));
        // SN3112 PWM is linear in light output while the eye is not; a gamma
        // curve keeps the fade visually even instead of hanging bright and
        // then dropping quickly at the bottom.
        return (int) Math.round(Math.pow(perceived, 2.2) * 100.0);
    }
}
