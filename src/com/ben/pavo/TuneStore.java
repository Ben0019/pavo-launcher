package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/** Persists the global tuner configuration and full per-app overrides. */
final class TuneStore {

    private static final String FILE = "aurora_tune";
    private static final String GLOBAL = "global";
    private static final String APP_PREFIX = "app:";

    private final SharedPreferences sp;

    TuneStore(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    TuneConfig global() {
        return TuneConfig.decode(sp.getString(GLOBAL, null));
    }

    /**
     * Returns the global config, creating it from the live hardware on first use.
     * Existing HOLD targets win over a vendor-restored hardware snapshot.
     */
    TuneConfig globalOrInit(PerfTuner.State state, Prefs prefs) {
        TuneConfig existing = global();
        if (existing != null) return existing;
        TuneConfig c = TuneConfig.fromState(state);
        if (prefs != null && prefs.perfHoldEnabled()
                && prefs.tuneP0Max() > 0 && prefs.tuneP6Max() > 0
                && prefs.tuneGpuMax() > 0) {
            c.p0Max = prefs.tuneP0Max();
            c.p0Min = prefs.tuneP0Min();
            c.p0Gov = prefs.tuneP0Gov();
            c.p6Max = prefs.tuneP6Max();
            c.p6Min = prefs.tuneP6Min();
            c.p6Gov = prefs.tuneP6Gov();
            c.gpuMax = prefs.tuneGpuMax();
            c.gpuMin = prefs.tuneGpuMin();
        }
        setGlobal(c);
        return c;
    }

    void setGlobal(TuneConfig config) {
        if (config == null) return;
        sp.edit().putString(GLOBAL, config.encode()).apply();
    }

    TuneConfig app(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        return TuneConfig.decode(sp.getString(APP_PREFIX + pkg, null));
    }

    void setApp(String pkg, TuneConfig config) {
        if (pkg == null || pkg.isEmpty() || config == null) return;
        sp.edit().putString(APP_PREFIX + pkg, config.encode()).apply();
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
