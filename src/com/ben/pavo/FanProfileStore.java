package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Persists fan-only overrides for individual applications. */
final class FanProfileStore {
    private static final String FILE = "aurora_fan_profiles";
    private static final String APP_PREFIX = "app:";

    private final SharedPreferences sp;

    FanProfileStore(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    FanProfile app(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        return FanProfile.decode(sp.getString(APP_PREFIX + pkg, null));
    }

    void setApp(String pkg, FanProfile profile) {
        if (pkg == null || pkg.isEmpty() || profile == null) return;
        sp.edit().putString(APP_PREFIX + pkg, profile.encode()).apply();
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

    void clearOverrides() {
        SharedPreferences.Editor edit = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(APP_PREFIX)) edit.remove(key);
        }
        edit.apply();
    }
}
