package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-game touch layouts for the built-in Touch Mapper. */
final class TouchMapStore {
    private static final String FILE = "aurora_touchmap";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ENABLED_APPS = "enabled_apps";
    private static final String KEY_TARGET = "target";
    private static final String KEY_LAYOUT_PREFIX = "layout:";
    private static final String KEY_STICK_ENABLED_PREFIX = "stick_enabled:";
    private static final String KEY_STICK_PREFIX = "stick:";
    private static final String KEY_RIGHT_MODE_PREFIX = "right_mode:";
    private static final String KEY_RIGHT_ZONE_PREFIX = "right_zone:";
    private static final String KEY_RIGHT_HOLD_PREFIX = "right_hold:";
    private static final String KEY_COMBOS_PREFIX = "combos:";
    private static final String KEY_BUTTONS_HIDDEN_PREFIX = "buttons_hidden:";
    private static final String KEY_CARD_X_PREFIX = "card_x:";
    private static final String KEY_CARD_Y_PREFIX = "card_y:";
    private static final String KEY_COMBO_X_PREFIX = "combo_x:";
    private static final String KEY_COMBO_Y_PREFIX = "combo_y:";
    private static final String KEY_LEFT_JOY_HIDDEN = "joy_hidden_left:";
    private static final String KEY_RIGHT_JOY_HIDDEN = "joy_hidden_right:";
    private static final String KEY_LEFT_JOY_ALPHA = "joy_alpha_left:";
    private static final String KEY_RIGHT_JOY_ALPHA = "joy_alpha_right:";
    private static final String KEY_FAB_X = "fab_x";
    private static final String KEY_FAB_Y = "fab_y";

    private final SharedPreferences prefs;

    TouchMapStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return !enabledApps().isEmpty();
    }

    Set<String> enabledApps() {
        Set<String> raw = prefs.getStringSet(KEY_ENABLED_APPS, null);
        if (raw == null) {
            boolean legacy = prefs.getBoolean(KEY_ENABLED, false);
            String legacyTarget = target();
            if (legacy && !legacyTarget.isEmpty()) {
                Set<String> migrated = new HashSet<>();
                migrated.add(legacyTarget);
                prefs.edit().putStringSet(KEY_ENABLED_APPS, migrated).apply();
                return migrated;
            }
            return new HashSet<>();
        }
        return new HashSet<>(raw);
    }

    int enabledCount() {
        return enabledApps().size();
    }

    List<String> enabledAppList() {
        return new ArrayList<>(enabledApps());
    }

    boolean isAppEnabled(String pkg) {
        return pkg != null && !pkg.isEmpty() && enabledApps().contains(pkg);
    }

    void setAppEnabled(String pkg, boolean enabled) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> apps = enabledApps();
        if (enabled) apps.add(pkg);
        else apps.remove(pkg);
        prefs.edit().putStringSet(KEY_ENABLED_APPS, apps)
                .putBoolean(KEY_ENABLED, !apps.isEmpty()).apply();
    }

    void disableAllApps() {
        prefs.edit().putStringSet(KEY_ENABLED_APPS, new HashSet<>())
                .putBoolean(KEY_ENABLED, false).apply();
    }

    void setEnabled(boolean enabled) {
        if (enabled) {
            String pkg = target();
            if (!pkg.isEmpty()) {
                Set<String> apps = enabledApps();
                apps.add(pkg);
                prefs.edit().putStringSet(KEY_ENABLED_APPS, apps)
                        .putBoolean(KEY_ENABLED, true).apply();
            }
        } else {
            disableAllApps();
        }
    }

    String target() {
        String pkg = prefs.getString(KEY_TARGET, "");
        return pkg == null ? "" : pkg;
    }

    void setTarget(String pkg) {
        prefs.edit().putString(KEY_TARGET, pkg == null ? "" : pkg).apply();
    }

    List<TouchTarget> layout(String pkg) {
        if (pkg == null || pkg.isEmpty()) return TouchTarget.defaults();
        return TouchTarget.decode(prefs.getString(KEY_LAYOUT_PREFIX + pkg, null));
    }

    void saveLayout(String pkg, List<TouchTarget> targets) {
        if (pkg == null || pkg.isEmpty() || targets == null) return;
        prefs.edit().putString(KEY_LAYOUT_PREFIX + pkg,
                TouchTarget.encode(targets)).apply();
    }

    boolean stickEnabled(String pkg) {
        return pkg != null && !pkg.isEmpty()
                && prefs.getBoolean(KEY_STICK_ENABLED_PREFIX + pkg, false);
    }

    void setStickEnabled(String pkg, boolean enabled) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putBoolean(KEY_STICK_ENABLED_PREFIX + pkg, enabled).apply();
    }

    TouchStick stick(String pkg) {
        if (pkg == null || pkg.isEmpty()) return TouchStick.defaults();
        return TouchStick.decode(prefs.getString(KEY_STICK_PREFIX + pkg, null));
    }

    void saveStick(String pkg, TouchStick stick) {
        if (pkg == null || pkg.isEmpty() || stick == null) return;
        prefs.edit().putString(KEY_STICK_PREFIX + pkg, stick.encode()).apply();
    }

    int rightMode(String pkg) {
        if (pkg == null || pkg.isEmpty()) return RightStick.OFF;
        return prefs.getInt(KEY_RIGHT_MODE_PREFIX + pkg, RightStick.OFF);
    }

    void setRightMode(String pkg, int mode) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putInt(KEY_RIGHT_MODE_PREFIX + pkg,
                Math.max(RightStick.OFF, Math.min(RightStick.AIM, mode))).apply();
    }

    TouchStick rightZone(String pkg, int mode) {
        if (pkg == null || pkg.isEmpty() || mode == RightStick.OFF) {
            return RightStick.defaultZone(mode);
        }
        String raw = prefs.getString(KEY_RIGHT_ZONE_PREFIX + pkg + ":" + mode, null);
        if (raw == null) return RightStick.defaultZone(mode);
        TouchStick zone = TouchStick.decode(raw);
        // A pre-release build initialized the right SWIPE zone with the left
        // stick defaults. Migrate that exact overlap to the intended zone.
        if (mode == RightStick.SWIPE
                && Math.abs(zone.x - 0.18f) < 0.0002f
                && Math.abs(zone.y - 0.62f) < 0.0002f
                && Math.abs(zone.radius - 0.16f) < 0.0002f) {
            return RightStick.defaultZone(RightStick.SWIPE);
        }
        return zone;
    }

    void saveRightZone(String pkg, int mode, TouchStick zone) {
        if (pkg == null || pkg.isEmpty() || zone == null || mode == RightStick.OFF) return;
        prefs.edit().putString(KEY_RIGHT_ZONE_PREFIX + pkg + ":" + mode,
                zone.encode()).apply();
    }

    boolean rightHold(String pkg) {
        return pkg != null && !pkg.isEmpty()
                && prefs.getBoolean(KEY_RIGHT_HOLD_PREFIX + pkg, false);
    }

    void setRightHold(String pkg, boolean hold) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putBoolean(KEY_RIGHT_HOLD_PREFIX + pkg, hold).apply();
    }

    List<TouchCombo> combos(String pkg) {
        if (pkg == null || pkg.isEmpty()) return new java.util.ArrayList<>();
        return TouchCombo.decode(prefs.getString(KEY_COMBOS_PREFIX + pkg, null));
    }

    void saveCombos(String pkg, List<TouchCombo> combos) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putString(KEY_COMBOS_PREFIX + pkg,
                TouchCombo.encode(combos == null
                        ? new java.util.ArrayList<>() : combos)).apply();
    }

    boolean buttonsHidden(String pkg) {
        return pkg != null && !pkg.isEmpty()
                && prefs.getBoolean(KEY_BUTTONS_HIDDEN_PREFIX + pkg, false);
    }

    void setButtonsHidden(String pkg, boolean hidden) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putBoolean(KEY_BUTTONS_HIDDEN_PREFIX + pkg, hidden).apply();
    }

    float cardX(String pkg) {
        return prefs.getFloat(KEY_CARD_X_PREFIX + pkg, 0f);
    }

    float cardY(String pkg) {
        return prefs.getFloat(KEY_CARD_Y_PREFIX + pkg, 0f);
    }

    void saveCardPosition(String pkg, float x, float y) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putFloat(KEY_CARD_X_PREFIX + pkg, x)
                .putFloat(KEY_CARD_Y_PREFIX + pkg, y).apply();
    }

    float comboX(String pkg) {
        return prefs.getFloat(KEY_COMBO_X_PREFIX + pkg, 0f);
    }

    float comboY(String pkg) {
        return prefs.getFloat(KEY_COMBO_Y_PREFIX + pkg, 0f);
    }

    void saveComboPosition(String pkg, float x, float y) {
        if (pkg == null || pkg.isEmpty()) return;
        prefs.edit().putFloat(KEY_COMBO_X_PREFIX + pkg, x)
                .putFloat(KEY_COMBO_Y_PREFIX + pkg, y).apply();
    }

    boolean leftJoyHidden(String pkg) {
        return prefs.getBoolean(KEY_LEFT_JOY_HIDDEN + pkg, false);
    }

    boolean rightJoyHidden(String pkg) {
        return prefs.getBoolean(KEY_RIGHT_JOY_HIDDEN + pkg, false);
    }

    float leftJoyAlpha(String pkg) {
        return prefs.getFloat(KEY_LEFT_JOY_ALPHA + pkg, 1f);
    }

    float rightJoyAlpha(String pkg) {
        return prefs.getFloat(KEY_RIGHT_JOY_ALPHA + pkg, 1f);
    }

    void saveLeftJoyAppearance(String pkg, boolean hidden, float alpha) {
        prefs.edit().putBoolean(KEY_LEFT_JOY_HIDDEN + pkg, hidden)
                .putFloat(KEY_LEFT_JOY_ALPHA + pkg, alpha).apply();
    }

    void saveRightJoyAppearance(String pkg, boolean hidden, float alpha) {
        prefs.edit().putBoolean(KEY_RIGHT_JOY_HIDDEN + pkg, hidden)
                .putFloat(KEY_RIGHT_JOY_ALPHA + pkg, alpha).apply();
    }

    int fabX() {
        return prefs.getInt(KEY_FAB_X, Integer.MIN_VALUE);
    }

    int fabY() {
        return prefs.getInt(KEY_FAB_Y, Integer.MIN_VALUE);
    }

    void saveFabPosition(int x, int y) {
        prefs.edit().putInt(KEY_FAB_X, x).putInt(KEY_FAB_Y, y).apply();
    }
}
