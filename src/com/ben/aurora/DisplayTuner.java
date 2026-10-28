package com.ben.aurora;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.Locale;
import java.util.TreeSet;

/** Privileged display controls for the built-in Display app. */
final class DisplayTuner {

    private DisplayTuner() {
    }

    static final class Snapshot {
        int width;
        int height;
        int nativeWidth;
        int nativeHeight;
        int densityDpi;
        float refresh;
        String hdr = "SDR";
        int minRefresh;
        int peakRefresh;
        int brightness;
        boolean autoBrightness;
        boolean nightLight;
        int nightTemperature = 4000;
        boolean autoRotate;
        int rotation;
        boolean naturalLandscape;
        int[] refreshRates = new int[]{60, 120};
    }

    static boolean available() {
        return PerfTuner.Root.ensure();
    }

    static Snapshot read(Context context) {
        Snapshot s = new Snapshot();
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                s.width = size.x;
                s.height = size.y;
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                s.densityDpi = metrics.densityDpi;
                s.refresh = display.getRefreshRate();
                s.hdr = hdrLabel(display);
                int maxW = 0;
                int maxH = 0;
                TreeSet<Integer> rates = new TreeSet<>();
                for (Display.Mode mode : display.getSupportedModes()) {
                    int w = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                    int h = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                    if (w * h > maxW * maxH) {
                        maxW = w;
                        maxH = h;
                    }
                    int hz = Math.round(mode.getRefreshRate());
                    if (hz >= 30) rates.add(hz);
                }
                s.nativeWidth = maxW;
                s.nativeHeight = maxH;
                // mode width/height reflects the panel's natural orientation,
                // unlike the max/min portrait-safe helpers above.
                Display.Mode currentMode = display.getMode();
                s.naturalLandscape = currentMode != null
                        && currentMode.getPhysicalWidth() > currentMode.getPhysicalHeight();
                if (!rates.isEmpty()) {
                    s.refreshRates = new int[rates.size()];
                    int i = 0;
                    for (int hz : rates) s.refreshRates[i++] = hz;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            s.minRefresh = Settings.System.getInt(context.getContentResolver(),
                    "min_refresh_rate", 0);
            s.peakRefresh = Settings.System.getInt(context.getContentResolver(),
                    "peak_refresh_rate", 120);
        } catch (Throwable ignored) {
        }
        try {
            s.brightness = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            s.autoBrightness = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Throwable ignored) {
        }
        try {
            s.nightLight = Settings.Secure.getInt(context.getContentResolver(),
                    "night_display_activated", 0) != 0;
            s.nightTemperature = Settings.Secure.getInt(context.getContentResolver(),
                    "night_display_color_temperature", 4000);
        } catch (Throwable ignored) {
        }
        try {
            s.autoRotate = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) != 0;
            s.rotation = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.USER_ROTATION, 0);
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static String hdrLabel(Display display) {
        try {
            if (Build.VERSION.SDK_INT >= 24 && display.isHdr()) {
                int[] types = display.getHdrCapabilities() == null
                        ? null : display.getHdrCapabilities().getSupportedHdrTypes();
                return types == null || types.length == 0 ? "HDR" : "HDR · " + types.length + " types";
            }
        } catch (Throwable ignored) {
        }
        return "SDR";
    }

    /** "auto", "60", "120" or a supported rate. */
    static void setRefresh(Context context, int hz, boolean auto) {
        String cmd;
        if (auto) {
            cmd = "settings put system min_refresh_rate 0; "
                    + "settings put system peak_refresh_rate 120";
        } else {
            cmd = "settings put system min_refresh_rate " + hz + "; "
                    + "settings put system peak_refresh_rate " + hz;
        }
        write(cmd);
    }

    /**
     * Set the logical resolution. Android expects the portrait short side first;
     * normalize whatever the caller provides so 1920x1080 and 1080x1920 both
     * become the same landscape mode.
     */
    static void setResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            write("wm size reset");
            return;
        }
        int shortSide = Math.min(width, height);
        int longSide = Math.max(width, height);
        write("wm size " + shortSide + "x" + longSide);
    }

    static void resetResolution() {
        write("wm size reset");
    }

    static void setDensity(int dpi) {
        if (dpi <= 0) write("wm density reset");
        else write("wm density " + dpi);
    }

    static void setBrightness(int percent) {
        int value = Math.max(0, Math.min(255, Math.round(percent * 255f / 100f)));
        write("settings put system screen_brightness " + value
                + "; settings put system screen_brightness_mode 0");
    }

    static void setAutoBrightness(boolean on) {
        write("settings put system screen_brightness_mode " + (on ? 1 : 0));
    }

    static void setRotation(Context context, String mode) {
        if ("auto".equals(mode)) {
            write("settings put system accelerometer_rotation 1");
            return;
        }
        // user_rotation is relative to the display's natural orientation. This
        // panel is landscape-native, so portrait is 90/270, not 0.
        boolean naturalLandscape = true;
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                Display.Mode currentMode = display.getMode();
                if (currentMode != null) {
                    naturalLandscape = currentMode.getPhysicalWidth()
                            > currentMode.getPhysicalHeight();
                }
            }
        } catch (Throwable ignored) {
        }
        int rotation;
        if ("portrait".equals(mode)) {
            rotation = naturalLandscape ? 1 : 0;
        } else {
            rotation = naturalLandscape ? 0 : 1;
        }
        write("settings put system accelerometer_rotation 0; "
                + "settings put system user_rotation " + rotation);
    }

    static void setNightLight(boolean on) {
        write("settings put secure night_display_activated " + (on ? 1 : 0));
    }

    static void setNightTemperature(int kelvin) {
        int value = Math.max(2500, Math.min(6500, kelvin));
        write("settings put secure night_display_activated 1; "
                + "settings put secure night_display_color_temperature " + value);
    }

    /*
     * Global panel calibration.
     *
     * Saturation, RGB and temperature are folded into one display-wide
     * SurfaceFlinger client colour matrix.  Going through that single matrix
     * avoids a race with ColorDisplayService's asynchronous saturation
     * transform, and it is real panel post-processing rather than a launcher
     * overlay.  It is applied as root because SurfaceFlinger requires
     * ACCESS_SURFACE_FLINGER.  HDR is a separate framework display command.
     */
    static void setHdrEnabled(boolean enabled) {
        write(enabled
                ? "cmd display set-user-disabled-hdr-types"
                : "cmd display set-user-disabled-hdr-types 2 3");
    }

    /**
     * Reads the framework's per-display user HDR override.  Returns 1 when HDR
     * is user-disabled, 0 when it is enabled and -1 when it could not be read
     * (PServer-only devices have no su).  Runs on the IO thread.
     */
    static int readHdrState() {
        String out = PerfTuner.Root.run("cmd display get-user-disabled-hdr-types");
        int open = out.indexOf('[');
        int close = out.indexOf(']', open + 1);
        if (open < 0 || close < 0) return -1;
        String list = out.substring(open + 1, close).trim();
        return list.contains("2") || list.contains("3") || list.contains("4") ? 1 : 0;
    }

    /**
     * Applies the complete persisted calibration.  Does nothing when every
     * control is at its neutral value, so a stock panel is never touched.
     */
    static void applyCalibration(Prefs prefs) {
        if (prefs == null) return;
        if (!prefs.panelCalibrationActive()) return;
        applyPanelColor(prefs);
        setHdrEnabled(prefs.panelHdrEnabled());
    }

    private static volatile boolean startCalibrationApplied;

    /**
     * Restores persisted calibration after the launcher starts at boot.  Kept
     * off the UI thread and intentionally one-shot per process: the Display app
     * applies live edits itself.
     */
    static synchronized void applyCalibrationOnStart(Prefs prefs) {
        if (startCalibrationApplied || prefs == null
                || !prefs.panelCalibrationActive()) return;
        if (!PerfTuner.Root.ensure()) {
            // PServer or su may not be ready yet during boot; allow the next
            // launcher resume to retry instead of caching a failed check.
            PerfTuner.Root.resetCheck();
            return;
        }
        startCalibrationApplied = true;
        applyCalibration(prefs);
    }

    /** Saturation + RGB/temperature transform, without touching HDR. */
    static void applyPanelColor(Prefs prefs) {
        if (prefs == null) return;
        // DWB and Night Light use the same SurfaceFlinger client matrix as this
        // calibration.  DWB is kept out of the way; saturation is folded into
        // our matrix below so there is no ordering race with ColorDisplayService.
        write("cmd display dwb-set-cct -1; " + colorMatrixCommand(prefs));
    }

    static void resetCalibration() {
        write("cmd color_display set-saturation 100; "
                + "cmd display set-user-disabled-hdr-types; "
                + "service call SurfaceFlinger 1015 i32 0");
    }

    /** Composes temperature and per-channel gain into display RGB multipliers. */
    static float[] panelGains(Prefs prefs) {
        float[] white = temperatureGains(prefs.panelTemperature());
        float r = white[0] * (prefs.panelRed() / 100f);
        float g = white[1] * (prefs.panelGreen() / 100f);
        float b = white[2] * (prefs.panelBlue() / 100f);
        return new float[]{
                Math.max(0.05f, Math.min(2f, r)),
                Math.max(0.05f, Math.min(2f, g)),
                Math.max(0.05f, Math.min(2f, b))
        };
    }

    /**
     * Approximate black-body white point normalised to 6500 K.  The attenuation
     * is intentionally softened so 2500 K is warm without crushing blue.
     */
    static float[] temperatureGains(int kelvin) {
        int k = Math.max(2500, Math.min(6500, kelvin));
        float[] base = blackBody(6500);
        float[] value = blackBody(k);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float ratio = base[i] <= 0f ? 1f : value[i] / base[i];
            out[i] = 1f + (ratio - 1f) * 0.82f;
        }
        return out;
    }

    private static float[] blackBody(int kelvin) {
        double t = kelvin / 100.0;
        double r;
        double g;
        double b;
        if (t <= 66.0) {
            r = 255.0;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
            b = t <= 19.0 ? 0.0
                    : 138.5177312231 * Math.log(t - 10.0) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(t - 60.0, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60.0, -0.0755148492);
            b = 255.0;
        }
        return new float[]{
                (float) Math.max(1.0, Math.min(255.0, r)) / 255f,
                (float) Math.max(1.0, Math.min(255.0, g)) / 255f,
                (float) Math.max(1.0, Math.min(255.0, b)) / 255f
        };
    }

    /**
     * Sends one SurfaceFlinger client colour matrix.  Saturation is folded in
     * here instead of going through ColorDisplayService, whose asynchronous
     * transform would otherwise race with the RGB/temperature matrix.
     */
    private static String colorMatrixCommand(Prefs prefs) {
        float[] gains = panelGains(prefs);
        float sat = prefs.panelSaturation() / 100f;
        float lumR = 0.213f * (1f - sat);
        float lumG = 0.715f * (1f - sat);
        float lumB = 0.072f * (1f - sat);
        // Column-major mat4: G(gain) * saturation matrix.
        float[] m = {
                gains[0] * (lumR + sat), gains[1] * lumR, gains[2] * lumR, 0f,
                gains[0] * lumG, gains[1] * (lumG + sat), gains[2] * lumG, 0f,
                gains[0] * lumB, gains[1] * lumB, gains[2] * (lumB + sat), 0f,
                0f, 0f, 0f, 1f
        };
        StringBuilder cmd = new StringBuilder("service call SurfaceFlinger 1015 i32 1");
        for (float value : m) {
            cmd.append(" f ");
            cmd.append(String.format(Locale.US, "%.3f", value));
        }
        return cmd.toString();
    }

    static void resetDisplay() {
        write("wm size reset; wm density reset; "
                + "settings put system min_refresh_rate 0; "
                + "settings put system peak_refresh_rate 120; "
                + "settings put system accelerometer_rotation 1");
    }

    static String brightnessLabel(int value) {
        return Math.round(Math.max(0, Math.min(255, value)) * 100f / 255f) + "%";
    }

    static String refreshLabel(float hz) {
        return String.format(Locale.US, "%.0f Hz", hz);
    }

    private static void write(String command) {
        if (command == null || command.isEmpty()) return;
        if (!PerfTuner.Root.ensure()) return;
        PerfTuner.Root.write(command);
    }
}
