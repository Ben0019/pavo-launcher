package com.ben.pavo;

/** Fan-only profile used by the global and per-app fan contexts. */
final class FanProfile {
    static final int MODE_CURVE = 7;
    private static final String VERSION = "1";

    int mode = PerfTuner.FAN_SMART;
    int manualPct = 50;
    String curve = FanCurve.DEFAULT;

    FanProfile() {}

    FanProfile(int mode, int manualPct, String curve) {
        this.mode = normalizeMode(mode);
        this.manualPct = clampPct(manualPct);
        this.curve = FanCurve.densify(curve == null ? FanCurve.DEFAULT : curve);
    }

    static FanProfile fromPrefs(Prefs prefs) {
        return new FanProfile(prefs.fanAppMode(), prefs.fanManualPercent(), prefs.fanCurve());
    }

    FanProfile copy() {
        return new FanProfile(mode, manualPct, curve);
    }

    void saveToPrefs(Prefs prefs) {
        prefs.setFanAppMode(mode);
        prefs.setFanManualPercent(manualPct);
        prefs.setFanCurve(curve);
    }

    String encode() {
        return VERSION + "|" + mode + "|" + manualPct + "|"
                + (curve == null ? FanCurve.DEFAULT : curve).replace('|', ' ');
    }

    static FanProfile decode(String value) {
        if (value == null) return null;
        String[] p = value.split("\\|", -1);
        if (p.length < 4 || !VERSION.equals(p[0])) return null;
        try {
            return new FanProfile(Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int normalizeMode(int value) {
        if (value == MODE_CURVE || value == PerfTuner.FAN_MANUAL
                || value == PerfTuner.FAN_OFF || value == PerfTuner.FAN_LOW
                || value == PerfTuner.FAN_SMART || value == PerfTuner.FAN_HIGH) {
            return value;
        }
        return PerfTuner.FAN_SMART;
    }

    static int clampPct(int pct) {
        return Math.max(10, Math.min(100, pct));
    }
}
