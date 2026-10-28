package com.ben.aurora;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent settings for charge limiting and automatic charge separation. */
final class DeviceHealthStore {
    private static final String FILE = "device_health";
    private static final String KEY_LIMIT_ON = "limit_on";
    private static final String KEY_LIMIT = "limit";
    private static final String KEY_SEPARATED = "separated";
    private static final String KEY_AUTO = "auto_separation";
    private static final String KEY_AUTO_LEVEL = "auto_level";

    private final SharedPreferences sp;

    DeviceHealthStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    boolean limitEnabled() { return sp.getBoolean(KEY_LIMIT_ON, false); }
    void setLimitEnabled(boolean v) { sp.edit().putBoolean(KEY_LIMIT_ON, v).apply(); }

    int limitPercent() { return clamp(sp.getInt(KEY_LIMIT, 80), 50, 100); }
    void setLimitPercent(int v) { sp.edit().putInt(KEY_LIMIT, clamp(v, 50, 100)).apply(); }

    boolean separated() { return sp.getBoolean(KEY_SEPARATED, false); }
    void setSeparated(boolean v) { sp.edit().putBoolean(KEY_SEPARATED, v).apply(); }

    boolean autoSeparation() { return sp.getBoolean(KEY_AUTO, false); }
    void setAutoSeparation(boolean v) { sp.edit().putBoolean(KEY_AUTO, v).apply(); }

    int autoLevel() { return clamp(sp.getInt(KEY_AUTO_LEVEL, 80), 50, 100); }
    void setAutoLevel(int v) { sp.edit().putInt(KEY_AUTO_LEVEL, clamp(v, 50, 100)).apply(); }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
