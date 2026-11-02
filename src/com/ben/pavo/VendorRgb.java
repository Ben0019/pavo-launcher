package com.ben.pavo;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.util.Locale;

/**
 * Uses Odin's built-in joystick-light control instead of writing the SN3112
 * nodes directly. The vendor settings app watches these Settings.System keys
 * and applies them to the LED driver, so the built-in RGB page and Pavo stay in
 * sync. Animated modes still use the direct node path because the vendor
 * control is static-only.
 */
final class VendorRgb {
    private static final String ENABLE = "joystick_light_enabled";
    private static final String COLOR = "joystick_led_light_picker_color";
    private static final String HANDLE_ENABLE = "joystick_handle_light_enabled";
    private static final String HANDLE_COLOR = "joystick_handle_light_picker_color";
    private static final String BRIGHTNESS = "led_light_brightness_percent";

    private VendorRgb() {}

    static boolean available(Context context) {
        if (context == null) return false;
        if (!DeviceProfile.get(context).rgb) return false;
        try {
            context.getPackageManager().getPackageInfo("com.odin.settings", 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean applyStatic(Context c, int color, int brightness,
                               boolean left, boolean right) {
        if (!available(c)) return false;
        String hex = String.format(Locale.US, "#%06X", 0xFFFFFF & color);
        String enable = (left ? "1" : "0") + "," + (right ? "1" : "0");
        String colors = hex + "," + hex;
        String handleEnable = enable + "," + enable;
        String handleColors = hex + "," + hex + "," + hex + "," + hex;
        int level = Math.max(0, Math.min(100, brightness));
        // Colour first, enable last: the vendor observer may fire after each
        // change, so it must never see the new enable with the old colour.
        boolean ok = putString(c, COLOR, colors);
        ok &= putString(c, HANDLE_COLOR, handleColors);
        ok &= putBrightness(c, level);
        ok &= putString(c, ENABLE, enable);
        ok &= putString(c, HANDLE_ENABLE, handleEnable);
        return ok;
    }

    /**
     * Force the vendor observer to re-apply. It ignores same-value writes, so
     * a 1% brightness step makes it repaint the current colour/enable (used
     * when returning to Static or when the display wakes).
     */
    static void nudge(Context c, int brightness) {
        int level = Math.max(0, Math.min(100, brightness));
        int alternate = level <= 1 ? level + 1 : level - 1;
        putBrightness(c, alternate);
        putBrightness(c, level);
    }

    /** Clear the vendor static enable when an animated mode takes over. */
    static void disableIfEnabled(Context c) {
        try {
            String main = Settings.System.getString(c.getContentResolver(), ENABLE);
            String handle = Settings.System.getString(c.getContentResolver(), HANDLE_ENABLE);
            boolean mainOn = main != null && !main.trim().equals("0,0");
            boolean handleOn = handle != null && !handle.trim().equals("0,0,0,0");
            if (mainOn || handleOn) off(c);
        } catch (Throwable ignored) {
        }
    }

    static boolean off(Context c) {
        if (!available(c)) return false;
        boolean ok = putString(c, ENABLE, "0,0");
        ok &= putString(c, HANDLE_ENABLE, "0,0,0,0");
        ok &= putBrightness(c, 0);
        return ok;
    }

    private static boolean putString(Context c, String key, String value) {
        try {
            if (PServer.available()) {
                String command = "settings put system " + key + " '" + value + "'";
                // PServer truncates around 255 bytes; these are all short.
                if (command.length() <= 240 && PServer.run(command)) return true;
            }
            if (Settings.System.canWrite(c)) {
                Settings.System.putString(c.getContentResolver(), key, value);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean putBrightness(Context c, int level) {
        try {
            if (PServer.available()) {
                String command = "settings put system " + BRIGHTNESS + " " + level;
                if (PServer.run(command)) return true;
            }
            if (Settings.System.canWrite(c)) {
                Settings.System.putFloat(c.getContentResolver(), BRIGHTNESS, level);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Reads the colour the built-in control is currently using, so the RGB app
     * opens with the same colour instead of Pavo's old stored value.
     */
    static int currentColor(Context c, int fallback) {
        try {
            String raw = Settings.System.getString(c.getContentResolver(), COLOR);
            if (raw == null || raw.isEmpty()) return fallback;
            String first = raw.split(",")[0].trim();
            return android.graphics.Color.parseColor(first);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static int currentBrightness(Context c, int fallback) {
        try {
            float v = Settings.System.getFloat(c.getContentResolver(), BRIGHTNESS, fallback);
            return Math.max(1, Math.min(100, Math.round(v)));
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
