package com.ben.aurora;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String FILE = "aurora";

    static final int MODE_DEVICE = 0;
    static final int MODE_AURORA = 1;
    static final int MODE_CUSTOM = 2;
    static final int MODE_VIDEO = 3;

    static final int DRAWER_FULL = 0;
    static final int DRAWER_POPUP = 1;

    static final int DOCK_CENTER = 0;
    static final int DOCK_LEFT = 1;
    static final int DOCK_RIGHT = 2;
    static final int DOCK_HIDDEN = 3;

    static final String DEFAULT_CONTROLLER_RAW = "BTN_Z";

    private final SharedPreferences sp;

    /** Raw store, for screens that need to observe changes made elsewhere. */
    SharedPreferences shared() {
        return sp;
    }

    Prefs(Context c) {
        SharedPreferences prefs;
        try {
            prefs = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        } catch (IllegalStateException locked) {
            // Accessibility/launcher services can start before the user
            // unlocks; credential-encrypted preferences are unavailable until
            // then. Keep the process alive with defaults and avoid a crash.
            prefs = c.createDeviceProtectedStorageContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
        sp = prefs;
        // The old build defaulted to accessibility filtering.  Move existing
        // installs to the PServer controller path once; the switch remains
        // available afterwards for genuinely rootless devices.
        if (!sp.contains("pserver_input_migrated")) {
            sp.edit().putBoolean("remap_rootless", false)
                    .putBoolean("pserver_input_migrated", true).apply();
        }
        ColorTuning.set(colorSaturation(), glowStrength());
    }

    // --- glass / dock style ---
    int blur() { return sp.getInt("blur", 12); }
    int glassPercent() { return sp.getInt("glass", 0); }
    int dockSheen() { return clamp(sp.getInt("dock_sheen", 100), 0, 100); }
    int cornerDp() { return sp.getInt("corner", 28); }
    boolean lightGlass() { return sp.getBoolean("light_glass", false); }
    int wallpaperMode() { return sp.getInt("wallpaper_mode", MODE_DEVICE); }
    String videoWallpaper() { return sp.getString("wallpaper_video", ""); }
    void setVideoWallpaper(String path) {
        sp.edit().putString("wallpaper_video", path == null ? "" : path).apply();
    }
    /** Archive file backing the current still wallpaper, for the "in use" mark. */
    String wallpaperHistoryPath() {
        return sp.getString("wallpaper_history_path", "");
    }
    void setWallpaperHistoryPath(String path) {
        sp.edit().putString("wallpaper_history_path", path == null ? "" : path).apply();
    }
    int themeAccent() { return sp.getInt("theme_accent", 0xFFA9BEFF); }
    void setThemeAccent(int colour) {
        sp.edit().putInt("theme_accent", colour).apply();
    }

    // --- clock ---
    boolean clock24() { return sp.getBoolean("clock24", true); }
    boolean clockVisible() { return sp.getBoolean("clock_visible", true); }
    float clockScale() {
        float v = sp.getFloat("clock_scale", 1f);
        return Math.max(0.7f, Math.min(1.6f, v));
    }
    float clockDx() { return sp.getFloat("clock_dx", 0f); }
    float clockDy() { return sp.getFloat("clock_dy", 0f); }
    int clockPage() { return Math.max(0, sp.getInt("clock_page", 0)); }
    int clockRow() { return Math.max(0, sp.getInt("clock_row", 0)); }
    int clockCol() { return Math.max(0, sp.getInt("clock_col", 0)); }

    // --- weather ---
    boolean weatherVisible() { return sp.getBoolean("weather_visible", true); }
    boolean weatherUseLocation() { return sp.getBoolean("weather_location", true); }
    float weatherTemp() { return sp.getFloat("weather_temp", Float.NaN); }
    int weatherCode() { return sp.getInt("weather_code", -1); }
    long weatherAt() { return sp.getLong("weather_at", 0L); }

    // --- clock / date colours ---
    int clockColor() { return sp.getInt("clock_color", 0xFFFF4D5E); }
    int dateColor() { return sp.getInt("date_color", 0xFFFFB3B8); }
    int colorSaturation() { return clamp(sp.getInt("color_saturation", 100), 0, 200); }
    int glowStrength() { return clamp(sp.getInt("glow_strength", 100), 0, 200); }
    void setColorSaturation(int value) {
        sp.edit().putInt("color_saturation", clamp(value, 0, 200)).apply();
    }
    void setGlowStrength(int value) {
        sp.edit().putInt("glow_strength", clamp(value, 0, 200)).apply();
    }

    // --- global panel calibration ---
    int panelSaturation() { return clamp(sp.getInt("panel_saturation", 100), 0, 100); }
    int panelTemperature() { return clamp(sp.getInt("panel_temperature", 6500), 2500, 6500); }
    int panelRed() { return clamp(sp.getInt("panel_red", 100), 50, 150); }
    int panelGreen() { return clamp(sp.getInt("panel_green", 100), 50, 150); }
    int panelBlue() { return clamp(sp.getInt("panel_blue", 100), 50, 150); }
    boolean panelHdrEnabled() { return sp.getBoolean("panel_hdr_enabled", true); }

    void setPanelSaturation(int value) {
        sp.edit().putInt("panel_saturation", clamp(value, 0, 100)).apply();
    }
    void setPanelTemperature(int value) {
        sp.edit().putInt("panel_temperature", clamp(value, 2500, 6500)).apply();
    }
    void setPanelRed(int value) {
        sp.edit().putInt("panel_red", clamp(value, 50, 150)).apply();
    }
    void setPanelGreen(int value) {
        sp.edit().putInt("panel_green", clamp(value, 50, 150)).apply();
    }
    void setPanelBlue(int value) {
        sp.edit().putInt("panel_blue", clamp(value, 50, 150)).apply();
    }
    void setPanelHdrEnabled(boolean value) {
        sp.edit().putBoolean("panel_hdr_enabled", value).apply();
    }

    boolean panelCalibrationActive() {
        return panelSaturation() != 100
                || panelTemperature() != 6500
                || panelRed() != 100
                || panelGreen() != 100
                || panelBlue() != 100
                || !panelHdrEnabled();
    }

    void resetPanelCalibration() {
        sp.edit()
                .putInt("panel_saturation", 100)
                .putInt("panel_temperature", 6500)
                .putInt("panel_red", 100)
                .putInt("panel_green", 100)
                .putInt("panel_blue", 100)
                .putBoolean("panel_hdr_enabled", true)
                .apply();
    }

    // --- labels ---
    boolean dockLabels() { return sp.getBoolean("dock_labels", true); }
    boolean homeLabels() { return sp.getBoolean("home_labels", true); }
    boolean drawerLabels() { return sp.getBoolean("drawer_labels", true); }

    // --- grids ---
    int homeCols() { return clamp(sp.getInt("home_cols", 6), 3, 8); }
    int homeRows() { return clamp(sp.getInt("home_rows", 3), 2, 6); }
    int drawerCols() { return clamp(sp.getInt("drawer_cols", 7), 4, 9); }

    // --- icons ---
    int iconDp() { return clamp(sp.getInt("icon_dp", 52), 36, 72); }

    // --- dock ---
    int dockCount() { return clamp(sp.getInt("dock_count", 7), 3, 8); }
    float dockScale() {
        float v = sp.getFloat("dock_scale", 0.8f);
        return Math.max(0.8f, Math.min(1.3f, v));
    }
    int dockPosition() {
        return clamp(sp.getInt("dock_position", DOCK_CENTER), DOCK_CENTER, DOCK_HIDDEN);
    }
    int dockDrawerIndex() {
        return clamp(sp.getInt("dock_drawer_index", 8), 0, 8);
    }
    String dockDrawerName() {
        String v = sp.getString("dock_drawer_name", "Apps");
        return v == null || v.trim().isEmpty() ? "Apps" : v.trim();
    }

    // --- language ---
    String language() {
        String v = sp.getString("language", Loc.AUTO);
        return v == null || v.trim().isEmpty() ? Loc.AUTO : v;
    }
    void setLanguage(String code) {
        sp.edit().putString("language", code == null ? Loc.AUTO : code).apply();
    }

    // --- misc ---
    boolean statsVisible() { return sp.getBoolean("stats", true); }
    boolean bluetoothStatusVisible() { return sp.getBoolean("bt_status", true); }
    boolean soundStatusVisible() { return sp.getBoolean("sound_status", true); }
    boolean controllerRemapEnabled() { return sp.getBoolean("remap_enabled", false); }
    boolean rootlessRemap() { return sp.getBoolean("remap_rootless", false); }
    boolean burnInProtection() { return sp.getBoolean("burn_in_protection", true); }
    boolean autoHidePageDots() { return sp.getBoolean("dots_auto_hide", true); }
    boolean dockDrawerButton() { return sp.getBoolean("dock_drawer_button", true); }
    boolean drawerGlass() { return sp.getBoolean("drawer_glass", true); }
    int drawerMode() { return sp.getInt("drawer_mode", DRAWER_FULL); }
    boolean drawerButtonPopup() { return sp.getBoolean("drawer_button_popup", false); }
    String pageAnimation() { return sp.getString("page_animation", "slide"); }
    boolean sleepEnabled() { return sp.getBoolean("sleep_enabled", true); }
    String sleepMethod() { return sp.getString("sleep_method", "accessibility"); }
    boolean hapticsEnabled() { return sp.getBoolean("haptics_enabled", true); }
    int hapticsStrength() { return clamp(sp.getInt("haptics_strength", 70), 0, 100); }
    void setHapticsEnabled(boolean value) { sp.edit().putBoolean("haptics_enabled", value).apply(); }
    void setHapticsStrength(int value) { sp.edit().putInt("haptics_strength", clamp(value, 0, 100)).apply(); }

    // --- performance overlay ---
    boolean perfOverlay() { return sp.getBoolean("perf_overlay", false); }
    int perfOverlayX() { return sp.getInt("perf_overlay_x", -1); }
    int perfOverlayY() { return sp.getInt("perf_overlay_y", -1); }
    float perfOverlayScale() {
        float v = sp.getFloat("perf_overlay_scale", 1f);
        return Math.max(0.55f, Math.min(1.8f, v));
    }
    int perfOverlayAlpha() { return clamp(sp.getInt("perf_overlay_alpha", 88), 25, 100); }
    int perfRefreshMs() { return clamp(sp.getInt("perf_refresh_ms", 1000), 250, 10000); }
    void setPerfRefreshMs(int ms) {
        sp.edit().putInt("perf_refresh_ms", clamp(ms, 250, 10000)).apply();
    }
    boolean perfOverlayLocked() { return sp.getBoolean("perf_overlay_locked", false); }
    void setPerfOverlayLocked(boolean value) {
        sp.edit().putBoolean("perf_overlay_locked", value).apply();
    }
    boolean perfLogging() { return sp.getBoolean("perf_logging", true); }
    int perfCleanupHours() { return clamp(sp.getInt("perf_cleanup_hours", 0), 0, 24); }
    void setPerfCleanupHours(int hours) {
        sp.edit().putInt("perf_cleanup_hours", clamp(hours, 0, 24)).apply();
    }
    boolean perfCpuTemp() { return sp.getBoolean("perf_cpu_temp", true); }
    boolean perfCpuClock() { return sp.getBoolean("perf_cpu_clock", true); }
    boolean perfCpuUsage() { return sp.getBoolean("perf_cpu_usage", true); }
    boolean perfGpuTemp() { return sp.getBoolean("perf_gpu_temp", true); }
    boolean perfGpuClock() { return sp.getBoolean("perf_gpu_clock", true); }
    boolean perfGpuUsage() { return sp.getBoolean("perf_gpu_usage", true); }
    boolean perfFps() { return sp.getBoolean("perf_fps", true); }
    boolean perfFpsGraph() { return sp.getBoolean("perf_fps_graph", true); }
    boolean perfBattery() { return sp.getBoolean("perf_battery", true); }
    boolean perfRam() { return sp.getBoolean("perf_ram", true); }
    boolean perfCores() { return sp.getBoolean("perf_cores", true); }
    String perfControllerRaw() {
        return sp.getString("perf_controller_raw", DEFAULT_CONTROLLER_RAW);
    }
    String perfLogControllerRaw() {
        return sp.getString("perf_log_controller_raw", "");
    }
    void setPerfLogControllerRaw(String key) {
        sp.edit().putString("perf_log_controller_raw", key == null ? "" : key).apply();
    }

    // --- app drawer controller & floating drawer columns ---
    String drawerControllerRaw() {
        return sp.getString("drawer_controller_raw", "");
    }

    void setDrawerControllerRaw(String key) {
        sp.edit().putString("drawer_controller_raw", key == null ? "" : key).apply();
    }

    int drawerOverlayColumns() { return clamp(sp.getInt("drawer_overlay_columns", 1), 1, 2); }

    void setDrawerOverlayColumns(int value) {
        sp.edit().putInt("drawer_overlay_columns", clamp(value, 1, 2)).apply();
    }


    int dockTransparency() { return clamp(sp.getInt("dock_transparency", 95), 20, 100); }
    int menuTransparency() { return clamp(sp.getInt("menu_transparency", 95), 20, 100); }
    int appDrawerTransparency() { return clamp(sp.getInt("app_drawer_transparency", 95), 20, 100); }
    int popupDrawerTransparency() { return clamp(sp.getInt("popup_drawer_transparency", 95), 20, 100); }
    int settingsTransparency() { return clamp(sp.getInt("settings_transparency", 95), 20, 100); }
    int folderTransparency() { return clamp(sp.getInt("folder_transparency", 95), 20, 100); }

    void setDockTransparency(int v) { sp.edit().putInt("dock_transparency", clamp(v, 20, 100)).apply(); }
    void setMenuTransparency(int v) { sp.edit().putInt("menu_transparency", clamp(v, 20, 100)).apply(); }
    void setAppDrawerTransparency(int v) { sp.edit().putInt("app_drawer_transparency", clamp(v, 20, 100)).apply(); }
    void setPopupDrawerTransparency(int v) { sp.edit().putInt("popup_drawer_transparency", clamp(v, 20, 100)).apply(); }
    void setSettingsTransparency(int v) { sp.edit().putInt("settings_transparency", clamp(v, 20, 100)).apply(); }
    void setFolderTransparency(int v) { sp.edit().putInt("folder_transparency", clamp(v, 20, 100)).apply(); }

    // --- joystick RGB ---
    int fanAppMode() {
        return clamp(sp.getInt("fan_app_mode", PerfTuner.FAN_SMART), 0, 7);
    }
    boolean hasFanAppMode() {
        return sp.contains("fan_app_mode");
    }
    int fanManualPercent() {
        return clamp(sp.getInt("fan_manual_percent", 50), 10, 100);
    }
    String fanCurve() {
        return sp.getString("fan_curve", FanCurve.DEFAULT);
    }
    void setFanAppMode(int v) {
        sp.edit().putInt("fan_app_mode", clamp(v, 0, 7)).apply();
    }
    void setFanManualPercent(int v) {
        sp.edit().putInt("fan_manual_percent", clamp(v, 10, 100)).apply();
    }
    void setFanCurve(String v) {
        sp.edit().putString("fan_curve", v == null ? FanCurve.DEFAULT : v).apply();
    }

    int rgbMode() { return clamp(sp.getInt("rgb_mode", RgbControl.MODE_OFF), 0, 5); }
    int rgbColor() { return sp.getInt("rgb_color", 0xFF2BE0D8); }
    int rgbBrightness() { return clamp(sp.getInt("rgb_brightness", 50), 1, 100); }
    int rgbSpeed() { return clamp(sp.getInt("rgb_speed", 100), 10, 300); }
    boolean rgbLeft() { return sp.getBoolean("rgb_left", true); }
    boolean rgbRight() { return sp.getBoolean("rgb_right", true); }
    void setRgbMode(int v) { sp.edit().putInt("rgb_mode", clamp(v, 0, 5)).apply(); }
    void setRgbColor(int v) { sp.edit().putInt("rgb_color", v).apply(); }
    void setRgbBrightness(int v) {
        sp.edit().putInt("rgb_brightness", clamp(v, 1, 100)).apply();
    }
    void setRgbSpeed(int v) {
        sp.edit().putInt("rgb_speed", clamp(v, 10, 300)).apply();
    }
    void setRgbLeft(boolean v) { sp.edit().putBoolean("rgb_left", v).apply(); }
    void setRgbRight(boolean v) { sp.edit().putBoolean("rgb_right", v).apply(); }

    int drawerOverlayAlpha() { return clamp(sp.getInt("drawer_overlay_alpha", 100), 20, 100); }
    int drawerOverlayWidthScale() {
        return clamp(sp.getInt("drawer_overlay_width_scale", 100), 70, 170);
    }
    int drawerOverlayIconDp() {
        return clamp(sp.getInt("drawer_overlay_icon_dp", 52), 34, 76);
    }
    void setDrawerOverlayIconDp(int value) {
        sp.edit().putInt("drawer_overlay_icon_dp", clamp(value, 34, 76)).apply();
    }
    String drawerFavorites() { return sp.getString("drawer_favorites", ""); }

    boolean perfHoldEnabled() { return sp.getBoolean("perf_hold_enabled", true); }

    void setPerfHoldEnabled(boolean value) {
        sp.edit().putBoolean("perf_hold_enabled", value).apply();
    }

    boolean perfHoldMigrated() { return sp.getBoolean("perf_hold_migrated", false); }

    void setPerfHoldMigrated(boolean value) {
        sp.edit().putBoolean("perf_hold_migrated", value).apply();
    }

    int tuneP0Max() { return sp.getInt("tune_p0_max", 0); }
    int tuneP0Min() { return sp.getInt("tune_p0_min", 0); }
    int tuneP6Max() { return sp.getInt("tune_p6_max", 0); }
    int tuneP6Min() { return sp.getInt("tune_p6_min", 0); }
    int tuneGpuMax() { return sp.getInt("tune_gpu_max", 0); }
    int tuneGpuMin() { return sp.getInt("tune_gpu_min", 0); }
    String tuneP0Gov() { return sp.getString("tune_p0_gov", "walt"); }
    String tuneP6Gov() { return sp.getString("tune_p6_gov", "walt"); }

    void setTuneTargets(int p0Max, int p0Min, String p0Gov,
                        int p6Max, int p6Min, String p6Gov,
                        int gpuMax, int gpuMin) {
        sp.edit()
                .putInt("tune_p0_max", p0Max)
                .putInt("tune_p0_min", p0Min)
                .putString("tune_p0_gov", p0Gov == null ? "walt" : p0Gov)
                .putInt("tune_p6_max", p6Max)
                .putInt("tune_p6_min", p6Min)
                .putString("tune_p6_gov", p6Gov == null ? "walt" : p6Gov)
                .putInt("tune_gpu_max", gpuMax)
                .putInt("tune_gpu_min", gpuMin)
                .apply();
    }

    void clearTuneTargets() {
        sp.edit()
                .putInt("tune_p0_max", 0)
                .putInt("tune_p0_min", 0)
                .putInt("tune_p6_max", 0)
                .putInt("tune_p6_min", 0)
                .putInt("tune_gpu_max", 0)
                .putInt("tune_gpu_min", 0)
                .apply();
    }

    void setDrawerOverlayAlpha(int value) {
        sp.edit().putInt("drawer_overlay_alpha", clamp(value, 20, 100)).apply();
    }
    void setDrawerOverlayWidthScale(int value) {
        sp.edit().putInt("drawer_overlay_width_scale", clamp(value, 70, 170)).apply();
    }
    void setDrawerFavorites(String value) {
        sp.edit().putString("drawer_favorites", value == null ? "" : value).apply();
    }

    // --- icon pack ---
    String iconPack() { return sp.getString("icon_pack", ""); }

    // --- layout data ---
    String layoutJson() { return sp.getString("layout", null); }
    String customJson() { return sp.getString("custom", null); }

    // --- setters ---
    void setBlur(int v) { sp.edit().putInt("blur", v).apply(); }
    void setGlassPercent(int v) { sp.edit().putInt("glass", v).apply(); }
    void setDockSheen(int v) {
        sp.edit().putInt("dock_sheen", clamp(v, 0, 100)).apply();
    }
    void setCornerDp(int v) { sp.edit().putInt("corner", v).apply(); }
    void setLightGlass(boolean v) { sp.edit().putBoolean("light_glass", v).apply(); }
    void setWallpaperMode(int v) { sp.edit().putInt("wallpaper_mode", v).apply(); }
    void setClock24(boolean v) { sp.edit().putBoolean("clock24", v).apply(); }
    void setClockVisible(boolean v) { sp.edit().putBoolean("clock_visible", v).apply(); }
    void setClockScale(float v) {
        sp.edit().putFloat("clock_scale", Math.max(0.7f, Math.min(1.6f, v))).apply();
    }
    void setClockOffset(float dx, float dy) {
        sp.edit().putFloat("clock_dx", dx).putFloat("clock_dy", dy).apply();
    }
    void setClockCell(int page, int row, int col) {
        sp.edit().putInt("clock_page", Math.max(0, page))
                .putInt("clock_row", Math.max(0, row))
                .putInt("clock_col", Math.max(0, col)).apply();
    }
    void setWeatherVisible(boolean v) { sp.edit().putBoolean("weather_visible", v).apply(); }
    void setWeatherUseLocation(boolean v) { sp.edit().putBoolean("weather_location", v).apply(); }
    void setWeather(float temp, int code) {
        sp.edit().putFloat("weather_temp", temp).putInt("weather_code", code)
                .putLong("weather_at", System.currentTimeMillis()).apply();
    }
    void setClockColor(int v) { sp.edit().putInt("clock_color", v).apply(); }
    void setDateColor(int v) { sp.edit().putInt("date_color", v).apply(); }
    void setDockLabels(boolean v) { sp.edit().putBoolean("dock_labels", v).apply(); }
    void setHomeLabels(boolean v) { sp.edit().putBoolean("home_labels", v).apply(); }
    void setDrawerLabels(boolean v) { sp.edit().putBoolean("drawer_labels", v).apply(); }
    void setHomeCols(int v) { sp.edit().putInt("home_cols", clamp(v, 3, 8)).apply(); }
    void setHomeRows(int v) { sp.edit().putInt("home_rows", clamp(v, 2, 6)).apply(); }
    void setDrawerCols(int v) { sp.edit().putInt("drawer_cols", clamp(v, 4, 9)).apply(); }
    void setIconDp(int v) { sp.edit().putInt("icon_dp", clamp(v, 36, 72)).apply(); }
    void setDockCount(int v) { sp.edit().putInt("dock_count", clamp(v, 3, 8)).apply(); }
    void setDockScale(float v) {
        sp.edit().putFloat("dock_scale", Math.max(0.8f, Math.min(1.3f, v))).apply();
    }
    void setDockPosition(int v) {
        sp.edit().putInt("dock_position", clamp(v, DOCK_CENTER, DOCK_HIDDEN)).apply();
    }
    void setDockDrawerIndex(int v) {
        sp.edit().putInt("dock_drawer_index", clamp(v, 0, 8)).apply();
    }
    void setDockDrawerName(String v) {
        sp.edit().putString("dock_drawer_name",
                v == null || v.trim().isEmpty() ? "Apps" : v.trim()).apply();
    }
    void setStatsVisible(boolean v) { sp.edit().putBoolean("stats", v).apply(); }
    void setBluetoothStatusVisible(boolean v) { sp.edit().putBoolean("bt_status", v).apply(); }
    void setSoundStatusVisible(boolean v) { sp.edit().putBoolean("sound_status", v).apply(); }
    void setControllerRemapEnabled(boolean v) { sp.edit().putBoolean("remap_enabled", v).apply(); }
    void setRootlessRemap(boolean v) { sp.edit().putBoolean("remap_rootless", v).apply(); }
    void setBurnInProtection(boolean v) { sp.edit().putBoolean("burn_in_protection", v).apply(); }
    void setAutoHidePageDots(boolean v) { sp.edit().putBoolean("dots_auto_hide", v).apply(); }
    void setDockDrawerButton(boolean v) { sp.edit().putBoolean("dock_drawer_button", v).apply(); }
    void setDrawerGlass(boolean v) { sp.edit().putBoolean("drawer_glass", v).apply(); }
    void setDrawerMode(int v) { sp.edit().putInt("drawer_mode", v).apply(); }
    void setDrawerButtonPopup(boolean v) { sp.edit().putBoolean("drawer_button_popup", v).apply(); }
    void setPageAnimation(String v) { sp.edit().putString("page_animation", v == null ? "slide" : v).apply(); }
    void setSleepEnabled(boolean v) { sp.edit().putBoolean("sleep_enabled", v).apply(); }
    void setSleepMethod(String v) {
        sp.edit().putString("sleep_method", "root".equals(v) ? "root" : "accessibility").apply();
    }
    void setPerfOverlay(boolean v) { sp.edit().putBoolean("perf_overlay", v).apply(); }
    void setPerfOverlayPosition(int x, int y) {
        sp.edit().putInt("perf_overlay_x", x).putInt("perf_overlay_y", y).apply();
    }
    void setPerfOverlayScale(float v) {
        sp.edit().putFloat("perf_overlay_scale", Math.max(0.55f, Math.min(1.8f, v))).apply();
    }
    void setPerfOverlayAlpha(int v) {
        sp.edit().putInt("perf_overlay_alpha", clamp(v, 25, 100)).apply();
    }
    void setPerfLogging(boolean v) { sp.edit().putBoolean("perf_logging", v).apply(); }
    void setPerfCpuTemp(boolean v) { sp.edit().putBoolean("perf_cpu_temp", v).apply(); }
    void setPerfCpuClock(boolean v) { sp.edit().putBoolean("perf_cpu_clock", v).apply(); }
    void setPerfCpuUsage(boolean v) { sp.edit().putBoolean("perf_cpu_usage", v).apply(); }
    void setPerfGpuTemp(boolean v) { sp.edit().putBoolean("perf_gpu_temp", v).apply(); }
    void setPerfGpuClock(boolean v) { sp.edit().putBoolean("perf_gpu_clock", v).apply(); }
    void setPerfGpuUsage(boolean v) { sp.edit().putBoolean("perf_gpu_usage", v).apply(); }
    void setPerfFps(boolean v) { sp.edit().putBoolean("perf_fps", v).apply(); }
    void setPerfFpsGraph(boolean v) { sp.edit().putBoolean("perf_fps_graph", v).apply(); }
    void setPerfBattery(boolean v) { sp.edit().putBoolean("perf_battery", v).apply(); }
    void setPerfRam(boolean v) { sp.edit().putBoolean("perf_ram", v).apply(); }
    void setPerfCores(boolean v) { sp.edit().putBoolean("perf_cores", v).apply(); }
    String perfCombo() { return sp.getString("perf_combo", ""); }
    void setPerfCombo(String value) {
        sp.edit().putString("perf_combo", value == null ? "" : value).apply();
    }
    String drawerCombo() { return sp.getString("drawer_combo", ""); }
    void setDrawerCombo(String value) {
        sp.edit().putString("drawer_combo", value == null ? "" : value).apply();
    }
    void setPerfControllerRaw(String key) {
        sp.edit().putString("perf_controller_raw", key == null ? "" : key).apply();
    }
    void setIconPack(String v) {
        sp.edit().putString("icon_pack", v == null ? "" : v).apply();
    }
    void setLayoutJson(String v) { sp.edit().putString("layout", v).apply(); }
    void setCustomJson(String v) { sp.edit().putString("custom", v).apply(); }

    /** Reset style only; keeps the user's layout and customisations. */
    void resetStyle() {
        sp.edit()
                .remove("blur").remove("glass").remove("dock_sheen")
                .remove("corner").remove("light_glass")
                .remove("wallpaper_mode").remove("clock24").remove("clock_visible")
                .remove("clock_scale").remove("weather_visible").remove("weather_location")
                .remove("clock_dx").remove("clock_dy")
                .remove("clock_page").remove("clock_row").remove("clock_col")
                .remove("clock_color").remove("date_color")
                .remove("color_saturation").remove("glow_strength")
                .remove("dock_labels").remove("home_labels").remove("drawer_labels")
                .remove("home_cols").remove("home_rows").remove("drawer_cols")
                .remove("icon_dp").remove("stats").remove("dock_drawer_button")
                .remove("drawer_mode").remove("dock_count").remove("drawer_glass")
                .remove("drawer_button_popup").remove("icon_pack")
                .remove("page_animation")
                .remove("dock_scale").remove("dock_position")
                .remove("dock_drawer_index")
                .remove("dots_auto_hide")
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
