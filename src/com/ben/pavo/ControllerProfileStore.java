package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Persisted controller remapping profiles, one per application plus a global one. */
final class ControllerProfileStore {
    private static final String FILE = "aurora_controller_profiles";
    private static final String GLOBAL = "global";
    private static final String APP_PREFIX = "app:";

    private final SharedPreferences sp;

    ControllerProfileStore(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String global() {
        return sp.getString(GLOBAL, null);
    }

    void setGlobal(String text) {
        sp.edit().putString(GLOBAL, text).apply();
    }

    String app(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        return sp.getString(APP_PREFIX + pkg, null);
    }

    void setApp(String pkg, String text) {
        if (pkg == null || pkg.isEmpty()) return;
        sp.edit().putString(APP_PREFIX + pkg, text).apply();
    }

    void removeApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        sp.edit().remove(APP_PREFIX + pkg).apply();
    }

    boolean hasOverrides() {
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(APP_PREFIX)) return true;
        }
        return false;
    }

    int overrideCount() {
        int count = 0;
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith(APP_PREFIX)) count++;
        }
        return count;
    }
}
