package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String FILE = "pavo";

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
    static final String DEFAULT_DRAWER_RAW = "BTN_C";

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
    int glassPercent() { return sp.getInt("glass", 21); }
    int dockSheen() { return clamp(sp.getInt("dock_sheen", 33), 0, 100); }
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
    boolean dockLabels() { return sp.getBoolean("dock_labels", false); }
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
    boolean controllerStickAutoApp() { return sp.getBoolean("controller_auto_app", true); }

    private static final String KEY_CONTROLLER_BYPASS = "controller_bypass_apps";

    java.util.Set<String> controllerBypassApps() {
        java.util.Set<String> stored = sp.getStringSet(KEY_CONTROLLER_BYPASS,
                java.util.Collections.emptySet());
        return new java.util.HashSet<>(stored);
    }

    boolean hasControllerBypassApps() {
        return !sp.getStringSet(KEY_CONTROLLER_BYPASS,
                java.util.Collections.emptySet()).isEmpty();
    }

    boolean controllerBypass(String pkg) {
        return pkg != null && !pkg.isEmpty()
                && sp.getStringSet(KEY_CONTROLLER_BYPASS,
                        java.util.Collections.emptySet()).contains(pkg);
    }

    void setControllerBypass(String pkg, boolean on) {
        if (pkg == null || pkg.isEmpty()) return;
        java.util.Set<String> apps = controllerBypassApps();
        boolean changed = on ? apps.add(pkg) : apps.remove(pkg);
        if (changed) {
            sp.edit().putStringSet(KEY_CONTROLLER_BYPASS, apps).apply();
        }
    }
    boolean stickExpert() { return sp.getBoolean("stick_expert", false); }
    void setStickExpert(boolean value) {
        sp.edit().putBoolean("stick_expert", value).apply();
    }
    void setControllerStickAutoApp(boolean value) {
        sp.edit().putBoolean("controller_auto_app", value).apply();
    }
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
    boolean setupDone() { return sp.getBoolean("setup_done", false); }
    boolean defaultsApplied() { return sp.getBoolean("defaults_applied", false); }
    void setDefaultsApplied(boolean value) {
        sp.edit().putBoolean("defaults_applied", value).apply();
    }
    void setSetupDone(boolean done) { sp.edit().putBoolean("setup_done", done).apply(); }
    boolean setupSkipped(String key) {
        return sp.getStringSet("setup_skipped", java.util.Collections.emptySet()).contains(key);
    }
    void skipSetup(String key) {
        java.util.Set<String> set = new java.util.HashSet<>(
                sp.getStringSet("setup_skipped", java.util.Collections.emptySet()));
        set.add(key);
        sp.edit().putStringSet("setup_skipped", set).apply();
    }
    boolean benchUseTunerClocks() { return sp.getBoolean("bench_use_tuner_clocks", false); }
    void setBenchUseTunerClocks(boolean value) {
        sp.edit().putBoolean("bench_use_tuner_clocks", value).apply();
    }
    int perfOverlayX() { return sp.getInt("perf_overlay_x", -1); }
    int perfOverlayY() { return sp.getInt("perf_overlay_y", -1); }
    float perfOverlayScale() {
        float v = sp.getFloat("perf_overlay_scale", 0.69f);
        return Math.max(0.55f, Math.min(1.8f, v));
    }
    /** Selected Fusion HUD mode: 0 Minimal, 1 Pill, 2 Tiles, 3 Full, 4 Mega. */
    int perfOverlayMode() { return clamp(sp.getInt("perf_overlay_mode", 1), 0, 4); }
    int updateCheckHours() { return clamp(sp.getInt("update_check_hours", 24), 2, 24); }
    void setUpdateCheckHours(int v) { sp.edit().putInt("update_check_hours", clamp(v, 2, 24)).apply(); }
    int perfOverlayAlpha() { return clamp(sp.getInt("perf_overlay_alpha", 88), 25, 100); }

    // Quick controls edge handle
    boolean quickHandleVisible() { return sp.getBoolean("quick_handle_visible", true); }

    void setQuickHandleVisible(boolean v) {
        sp.edit().putBoolean("quick_handle_visible", v).apply();
    }

    int quickHandleWidth() { return clamp(sp.getInt("quick_handle_width", 4), 2, 16); }

    void setQuickHandleWidth(int v) { sp.edit().putInt("quick_handle_width", v).apply(); }

    int quickHandleHeight() { return clamp(sp.getInt("quick_handle_height", 44), 40, 400); }

    void setQuickHandleHeight(int v) { sp.edit().putInt("quick_handle_height", v).apply(); }

    int quickHandleAlpha() { return clamp(sp.getInt("quick_handle_alpha", 55), 10, 100); }

    void setQuickHandleAlpha(int v) { sp.edit().putInt("quick_handle_alpha", v).apply(); }

    /** 0 right, 1 left, 2 top, 3 bottom. */
    int quickHandleEdge() { return clamp(sp.getInt("quick_handle_edge", 0), 0, 3); }

    void setQuickHandleEdge(int v) { sp.edit().putInt("quick_handle_edge", v).apply(); }

    /** Position along the current edge, 0-1000. */
    int quickHandlePos() { return clamp(sp.getInt("quick_handle_pos", 72), 0, 1000); }

    void setQuickHandlePos(int v) { sp.edit().putInt("quick_handle_pos", v).apply(); }
    boolean perfOverlayHorizontal() { return sp.getBoolean("perf_overlay_horizontal", false); }
    void setPerfOverlayHorizontal(boolean v) {
        if (perfOverlayHorizontal() == v) return;
        if (v) {
            // The strip has no core view. Remember the current choice and hide
            // cores, so the panel can put them back exactly as they were.
            sp.edit()
                    .putBoolean("perf_cores_before_strip", perfCores())
                    .putBoolean("perf_overlay_horizontal", true)
                    .putBoolean("perf_cores", false)
                    .apply();
        } else {
            boolean restore = sp.getBoolean("perf_cores_before_strip", false);
            sp.edit()
                    .putBoolean("perf_overlay_horizontal", false)
                    .putBoolean("perf_cores", restore)
                    .apply();
        }
    }
    int perfRefreshMs() { return clamp(sp.getInt("perf_refresh_ms", 1000), 250, 10000); }
    void setPerfRefreshMs(int ms) {
        sp.edit().putInt("perf_refresh_ms", clamp(ms, 250, 10000)).apply();
    }
    /**
     * Kick WALT's big cluster when the efficiency cores saturate. The Odin
     * firmware parks cores 6-7 for apps it does not recognise as games.
     */
    boolean bigCoreKick() { return sp.getBoolean("big_core_kick", true); }

    void setBigCoreKick(boolean value) {
        sp.edit().putBoolean("big_core_kick", value).apply();
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
    boolean perfNet() { return sp.getBoolean("perf_net", true); }
    boolean perfFps() { return sp.getBoolean("perf_fps", true); }
    boolean perfFpsGraph() { return sp.getBoolean("perf_fps_graph", true); }
    boolean perfBattery() { return sp.getBoolean("perf_battery", true); }
    boolean perfFan() { return sp.getBoolean("perf_fan", true); }
    boolean perfRam() { return sp.getBoolean("perf_ram", true); }
    boolean perfCores() { return sp.getBoolean("perf_cores", true); }

    // System-wide five-band equalizer.
    static final int EQ_BANDS = 5;
    boolean eqEnabled() { return sp.getBoolean("eq_enabled", false); }
    int eqBand(int index) {
        return clamp(sp.getInt("eq_band" + clamp(index, 0, EQ_BANDS - 1), 0), -1200, 1200);
    }
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
        return sp.getString("drawer_controller_raw", DEFAULT_DRAWER_RAW);
    }

    void setDrawerControllerRaw(String key) {
        sp.edit().putString("drawer_controller_raw", key == null ? "" : key).apply();
    }

    int drawerOverlayColumns() { return clamp(sp.getInt("drawer_overlay_columns", 1), 1, 2); }

    void setDrawerOverlayColumns(int value) {
        sp.edit().putInt("drawer_overlay_columns", clamp(value, 1, 2)).apply();
    }


    int dockTransparency() { return clamp(sp.getInt("dock_transparency", 57), 20, 100); }
    int menuTransparency() { return clamp(sp.getInt("menu_transparency", 52), 20, 100); }
    int appDrawerTransparency() { return clamp(sp.getInt("app_drawer_transparency", 53), 20, 100); }
    int popupDrawerTransparency() { return clamp(sp.getInt("popup_drawer_transparency", 53), 20, 100); }
    int settingsTransparency() { return clamp(sp.getInt("settings_transparency", 56), 20, 100); }

    /** One surface value for every built-in app (Fan Control, File Manager ...). */
    int builtinTransparency() {
        return clamp(sp.getInt("builtin_transparency", 100), 20, 100);
    }
    int folderTransparency() { return clamp(sp.getInt("folder_transparency", 56), 20, 100); }

    void setDockTransparency(int v) { sp.edit().putInt("dock_transparency", clamp(v, 20, 100)).apply(); }
    void setMenuTransparency(int v) { sp.edit().putInt("menu_transparency", clamp(v, 20, 100)).apply(); }
    void setAppDrawerTransparency(int v) { sp.edit().putInt("app_drawer_transparency", clamp(v, 20, 100)).apply(); }
    void setPopupDrawerTransparency(int v) { sp.edit().putInt("popup_drawer_transparency", clamp(v, 20, 100)).apply(); }
    void setSettingsTransparency(int v) { sp.edit().putInt("settings_transparency", clamp(v, 20, 100)).apply(); }
    void setBuiltinTransparency(int v) { sp.edit().putInt("builtin_transparency", clamp(v, 20, 100)).apply(); }

    /** Colour strength of the captured background inside built-in app cards. */
    int builtinSaturation() {
        return clamp(sp.getInt("builtin_saturation", 150), 0, 200);
    }

    void setBuiltinSaturation(int v) {
        sp.edit().putInt("builtin_saturation", clamp(v, 0, 200)).apply();
    }

    /** 0 = untouched capture, 100 = black. Applies inside built-in app cards. */
    int builtinBackdropDarkness() {
        return clamp(sp.getInt("builtin_darkness", 50), 0, 100);
    }

    void setBuiltinBackdropDarkness(int v) {
        sp.edit().putInt("builtin_darkness", clamp(v, 0, 100)).apply();
    }

    // Backdrop colour tuning for the launcher's own glass surfaces.
    int dockSaturation() { return clamp(sp.getInt("dock_saturation", 125), 0, 200); }
    void setDockSaturation(int v) { sp.edit().putInt("dock_saturation", clamp(v, 0, 200)).apply(); }
    int dockBackdropDarkness() { return clamp(sp.getInt("dock_darkness", 50), 0, 100); }
    void setDockBackdropDarkness(int v) { sp.edit().putInt("dock_darkness", clamp(v, 0, 100)).apply(); }

    int drawerSaturation() { return clamp(sp.getInt("drawer_saturation", 150), 0, 200); }
    void setDrawerSaturation(int v) { sp.edit().putInt("drawer_saturation", clamp(v, 0, 200)).apply(); }
    int drawerBackdropDarkness() { return clamp(sp.getInt("drawer_darkness", 50), 0, 100); }
    void setDrawerBackdropDarkness(int v) { sp.edit().putInt("drawer_darkness", clamp(v, 0, 100)).apply(); }

    int folderSaturation() { return clamp(sp.getInt("folder_saturation", 150), 0, 200); }
    void setFolderSaturation(int v) { sp.edit().putInt("folder_saturation", clamp(v, 0, 200)).apply(); }
    int folderBackdropDarkness() { return clamp(sp.getInt("folder_darkness", 50), 0, 100); }
    void setFolderBackdropDarkness(int v) { sp.edit().putInt("folder_darkness", clamp(v, 0, 100)).apply(); }

    int popupDrawerSaturation() { return clamp(sp.getInt("popup_drawer_saturation", 150), 0, 200); }
    void setPopupDrawerSaturation(int v) { sp.edit().putInt("popup_drawer_saturation", clamp(v, 0, 200)).apply(); }
    int popupDrawerBackdropDarkness() { return clamp(sp.getInt("popup_drawer_darkness", 50), 0, 100); }
    void setPopupDrawerBackdropDarkness(int v) { sp.edit().putInt("popup_drawer_darkness", clamp(v, 0, 100)).apply(); }

    int settingsSaturation() { return clamp(sp.getInt("settings_saturation", 150), 0, 200); }
    void setSettingsSaturation(int v) { sp.edit().putInt("settings_saturation", clamp(v, 0, 200)).apply(); }
    int settingsBackdropDarkness() { return clamp(sp.getInt("settings_darkness", 50), 0, 100); }
    void setSettingsBackdropDarkness(int v) { sp.edit().putInt("settings_darkness", clamp(v, 0, 100)).apply(); }

    boolean ftpAnonymous() { return sp.getBoolean("ftp_anonymous", true); }
    void setFtpAnonymous(boolean v) { sp.edit().putBoolean("ftp_anonymous", v).apply(); }

    int ftpRoot() { return clamp(sp.getInt("ftp_root", 2), 0, 2); }
    void setFtpRoot(int v) { sp.edit().putInt("ftp_root", clamp(v, 0, 2)).apply(); }

    int ftpPort() { return clamp(sp.getInt("ftp_port", 2121), 1024, 65535); }
    void setFtpPort(int v) { sp.edit().putInt("ftp_port", clamp(v, 1024, 65535)).apply(); }

    String ftpUser() { return sp.getString("ftp_user", "pavo"); }
    void setFtpUser(String v) { sp.edit().putString("ftp_user", v == null ? "pavo" : v).apply(); }

    String ftpPassword() { return sp.getString("ftp_password", "pavo"); }
    void setFtpPassword(String v) { sp.edit().putString("ftp_password", v == null ? "pavo" : v).apply(); }

    /** Whether clients may write to a shared FTP folder ("internal"/"external"). */
    boolean ftpShareWritable(String key) {
        return sp.getBoolean("ftp_share_" + key + "_write", true);
    }

    void setFtpShareWritable(String key, boolean writable) {
        sp.edit().putBoolean("ftp_share_" + key + "_write", writable).apply();
    }

    /** Read/write permission for a storage medium (local or remote). */
    boolean storageWritable(String key) {
        return sp.getBoolean("perm_" + (key == null ? "" : key), true);
    }

    void setStorageWritable(String key, boolean writable) {
        sp.edit().putBoolean("perm_" + (key == null ? "" : key), writable).apply();
    }

    String ftpRemoteHost() { return sp.getString("ftp_remote_host", ""); }
    int ftpRemotePort() { return clamp(sp.getInt("ftp_remote_port", 21), 1, 65535); }
    String ftpRemoteUser() { return sp.getString("ftp_remote_user", "anonymous"); }
    String ftpRemotePassword() { return sp.getString("ftp_remote_password", ""); }

    void setFtpRemote(String host, int port, String user, String password) {
        sp.edit()
                .putString("ftp_remote_host", host == null ? "" : host)
                .putInt("ftp_remote_port", clamp(port, 1, 65535))
                .putString("ftp_remote_user", user == null ? "anonymous" : user)
                .putString("ftp_remote_password", password == null ? "" : password)
                .apply();
    }

    int menuSaturation() { return clamp(sp.getInt("menu_saturation", 150), 0, 200); }
    void setMenuSaturation(int v) { sp.edit().putInt("menu_saturation", clamp(v, 0, 200)).apply(); }
    int menuBackdropDarkness() { return clamp(sp.getInt("menu_darkness", 50), 0, 100); }
    void setMenuBackdropDarkness(int v) { sp.edit().putInt("menu_darkness", clamp(v, 0, 100)).apply(); }
    void setFolderTransparency(int v) { sp.edit().putInt("folder_transparency", clamp(v, 20, 100)).apply(); }

    // --- joystick RGB ---
    /** 0 = CPU, 1 = GPU, 2 = higher of the two. */
    int fanTempSource() { return clamp(sp.getInt("fan_temp_source", 2), 0, 2); }
    int fanResponse() { return clamp(sp.getInt("fan_response", 2), 0, 3); }
    void setFanResponse(int value) {
        sp.edit().putInt("fan_response", clamp(value, 0, 3)).apply();
    }
    void setFanTempSource(int value) {
        sp.edit().putInt("fan_temp_source", Math.max(0, Math.min(2, value))).apply();
    }
    int fanAppMode() {
        return clamp(sp.getInt("fan_app_mode", PerfTuner.FAN_SMART), 0,
                FanProfile.MODE_HOLD);
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
    String fanPresetCurve(int mode) {
        String key = fanPresetKey(mode);
        return key == null ? null : sp.getString(key, null);
    }
    void setFanPresetCurve(int mode, String curve) {
        String key = fanPresetKey(mode);
        if (key != null && curve != null && !curve.isEmpty()) {
            sp.edit().putString(key, FanCurve.ensureTerminal(curve)).apply();
        }
    }
    void resetFanPresetCurve(int mode) {
        String key = fanPresetKey(mode);
        if (key != null) sp.edit().remove(key).apply();
    }
    boolean hasFanPresetCurve(int mode) {
        String key = fanPresetKey(mode);
        return key != null && sp.contains(key);
    }
    private static String fanPresetKey(int mode) {
        if (mode == FanProfile.MODE_SMALL) return "fan_preset_small";
        if (mode == FanProfile.MODE_MEDIUM) return "fan_preset_medium";
        if (mode == FanProfile.MODE_LARGE) return "fan_preset_large";
        return null;
    }
    void setFanAppMode(int v) {
        sp.edit().putInt("fan_app_mode", clamp(v, 0, FanProfile.MODE_HOLD)).apply();
    }
    int fanHoldTargetC() {
        return clamp(sp.getInt("fan_hold_target_c", 65), 30, 95);
    }
    void setFanHoldTargetC(int v) {
        sp.edit().putInt("fan_hold_target_c", clamp(v, 30, 95)).apply();
    }
    String fanHoldCurve() {
        return sp.getString("fan_hold_curve", null);
    }    void setFanHoldCurve(String curve) {
        if (curve != null && !curve.isEmpty()) {
            sp.edit().putString("fan_hold_curve", curve).apply();
        }
    }
    void resetFanHoldCurve() {
        sp.edit().remove("fan_hold_curve").apply();
    }
    int fanHoldDuty() {
        return clamp(sp.getInt("fan_hold_duty", 100), 0, 100);
    }
    void setFanHoldDuty(int v) {
        sp.edit().putInt("fan_hold_duty", clamp(v, 0, 100)).apply();
    }
    void setFanManualPercent(int v) {
        sp.edit().putInt("fan_manual_percent", clamp(v, 10, 100)).apply();
    }
    void setFanCurve(String v) {
        sp.edit().putString("fan_curve", v == null ? FanCurve.DEFAULT : v).apply();
    }

    int rgbMode() { return clamp(sp.getInt("rgb_mode", RgbControl.MODE_OFF), 0, 6); }
    int rgbColor() { return sp.getInt("rgb_color", 0xFF2BE0D8); }
    int rgbBrightness() { return clamp(sp.getInt("rgb_brightness", 50), 1, 100); }
    int rgbSpeed() { return clamp(sp.getInt("rgb_speed", 100), 10, 300); }
    boolean rgbLeft() { return sp.getBoolean("rgb_left", true); }
    boolean rgbRight() { return sp.getBoolean("rgb_right", true); }
    void setRgbMode(int v) { sp.edit().putInt("rgb_mode", clamp(v, 0, 6)).apply(); }
    void setRgbColor(int v) { sp.edit().putInt("rgb_color", v).apply(); }
    void setRgbBrightness(int v) {
        sp.edit().putInt("rgb_brightness", clamp(v, 1, 100)).apply();
    }
    void setRgbSpeed(int v) {
        sp.edit().putInt("rgb_speed", clamp(v, 10, 300)).apply();
    }
    void setRgbLeft(boolean v) { sp.edit().putBoolean("rgb_left", v).apply(); }
    void setRgbRight(boolean v) { sp.edit().putBoolean("rgb_right", v).apply(); }

    /**
     * Per-stick mode and colour. Builds without the new keys migrate from the
     * old shared mode plus the two enable flags, so an upgrade keeps the same
     * look instead of resetting both rings.
     */
    int rgbLeftMode() {
        if (sp.contains("rgb_left_mode")) {
            return clamp(sp.getInt("rgb_left_mode", RgbControl.MODE_OFF), 0, 6);
        }
        return rgbLeft() ? rgbMode() : RgbControl.MODE_OFF;
    }

    int rgbRightMode() {
        if (sp.contains("rgb_right_mode")) {
            return clamp(sp.getInt("rgb_right_mode", RgbControl.MODE_OFF), 0, 6);
        }
        return rgbRight() ? rgbMode() : RgbControl.MODE_OFF;
    }

    int rgbLeftColor() {
        return sp.getInt("rgb_left_color", rgbColor());
    }

    int rgbRightColor() {
        return sp.getInt("rgb_right_color", rgbColor());
    }

    void setRgbLeftMode(int v) { sp.edit().putInt("rgb_left_mode", clamp(v, 0, 6)).apply(); }
    void setRgbRightMode(int v) { sp.edit().putInt("rgb_right_mode", clamp(v, 0, 6)).apply(); }
    void setRgbLeftColor(int v) { sp.edit().putInt("rgb_left_color", v).apply(); }
    void setRgbRightColor(int v) { sp.edit().putInt("rgb_right_color", v).apply(); }

    /**
     * True when both sticks share one colour and mode. Upgrades from the
     * single-mode build default to linked; a build that already stored
     * per-stick settings keeps them separate.
     */
    boolean rgbLinked() {
        return sp.getBoolean("rgb_linked", !sp.contains("rgb_left_mode"));
    }

    void setRgbLinked(boolean v) { sp.edit().putBoolean("rgb_linked", v).apply(); }

    /** Last non-off mode, restored when a stick is toggled back on. */
    int rgbLeftLastMode() {
        return clamp(sp.getInt("rgb_left_last_mode", RgbControl.MODE_STATIC), 0, 6);
    }

    int rgbRightLastMode() {
        return clamp(sp.getInt("rgb_right_last_mode", RgbControl.MODE_STATIC), 0, 6);
    }

    void setRgbLeftLastMode(int v) {
        sp.edit().putInt("rgb_left_last_mode", clamp(v, 0, 6)).apply();
    }

    void setRgbRightLastMode(int v) {
        sp.edit().putInt("rgb_right_last_mode", clamp(v, 0, 6)).apply();
    }

    /** UI size: auto, 90, 100, 110 or 120 percent. */
    String uiScaleMode() {
        String value = sp.getString("ui_scale_mode", "auto");
        if ("90".equals(value) || "100".equals(value) || "110".equals(value)
                || "120".equals(value)) {
            return value;
        }
        return "auto";
    }

    void setUiScaleMode(String value) {
        String mode = "auto";
        if ("90".equals(value) || "100".equals(value) || "110".equals(value)
                || "120".equals(value)) {
            mode = value;
        }
        sp.edit().putString("ui_scale_mode", mode).apply();
    }

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
    /** Junction temperature at which the hold lets the firmware take over. */
    int perfHoldLimitC() { return clamp(sp.getInt("perf_hold_limit_c", 95), 60, 100); }

    void setPerfHoldEnabled(boolean value) {
        sp.edit().putBoolean("perf_hold_enabled", value).apply();
    }

    boolean perfHoldMigrated() { return sp.getBoolean("perf_hold_migrated", false); }

    void setPerfHoldMigrated(boolean value) {
        sp.edit().putBoolean("perf_hold_migrated", value).apply();
    }

    /** Firmware performance_mode in force when Pavo last applied clocks. */
    int holdMode() { return sp.getInt("hold_mode", -1); }

    void setHoldMode(int mode) { sp.edit().putInt("hold_mode", mode).apply(); }

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
    String layoutBackup() { return sp.getString("layout_backup", null); }
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
    void setPerfOverlayMode(int v) {
        sp.edit().putInt("perf_overlay_mode", clamp(v, 0, 4)).apply();
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
    void setPerfNet(boolean v) { sp.edit().putBoolean("perf_net", v).apply(); }
    void setPerfFps(boolean v) { sp.edit().putBoolean("perf_fps", v).apply(); }
    void setPerfFpsGraph(boolean v) { sp.edit().putBoolean("perf_fps_graph", v).apply(); }
    void setPerfBattery(boolean v) { sp.edit().putBoolean("perf_battery", v).apply(); }
    void setPerfFan(boolean v) { sp.edit().putBoolean("perf_fan", v).apply(); }
    void setPerfRam(boolean v) { sp.edit().putBoolean("perf_ram", v).apply(); }
    void setEqEnabled(boolean v) { sp.edit().putBoolean("eq_enabled", v).apply(); }
    void setEqBand(int index, int millibels) {
        sp.edit().putInt("eq_band" + clamp(index, 0, EQ_BANDS - 1),
                clamp(millibels, -1200, 1200)).apply();
    }
    void setPerfCores(boolean v) {
        android.content.SharedPreferences.Editor e = sp.edit().putBoolean("perf_cores", v);
        // Changing cores while the strip is active becomes the state the panel
        // restores when the strip is closed.
        if (perfOverlayHorizontal()) e.putBoolean("perf_cores_before_strip", v);
        e.apply();
    }
    String quickControllerRaw() { return sp.getString("quick_controller_raw", ""); }

    void setQuickControllerRaw(String value) {
        sp.edit().putString("quick_controller_raw", value == null ? "" : value).apply();
    }

    String quickCombo() { return sp.getString("quick_combo", ""); }

    void setQuickCombo(String value) {
        sp.edit().putString("quick_combo", value == null ? "" : value).apply();
    }

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
    void setLayoutJson(String v) { sp.edit().putString("layout", v).commit(); }
    void setLayoutBackup(String v) { sp.edit().putString("layout_backup", v).commit(); }
    void setCustomJson(String v) { sp.edit().putString("custom", v).commit(); }
    String customBackup() { return sp.getString("custom_backup", null); }
    void setCustomBackup(String v) { sp.edit().putString("custom_backup", v).commit(); }

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
