package com.ben.pavo;

import android.content.Context;

/** Fan-only profile used by the global and per-app fan contexts. */
final class FanProfile {
    static final int MODE_CURVE = 7;
    static final int MODE_SMALL = 8;
    static final int MODE_MEDIUM = 9;
    static final int MODE_LARGE = 10;
    static final int MODE_HOLD = 11;
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
        if (value == MODE_CURVE || value == MODE_SMALL || value == MODE_MEDIUM
                || value == MODE_LARGE || value == MODE_HOLD || value == PerfTuner.FAN_MANUAL
                || value == PerfTuner.FAN_OFF || value == PerfTuner.FAN_LOW
                || value == PerfTuner.FAN_SMART || value == PerfTuner.FAN_HIGH) {
            return value;
        }
        return PerfTuner.FAN_SMART;
    }

    static int clampPct(int pct) {
        return Math.max(10, Math.min(100, pct));
    }

    /** Pavo's live fan mode, including per-app overrides. */
    static int activeMode(Context c) {
        if (c == null) return PerfTuner.FAN_SMART;
        Prefs p = new Prefs(c);
        String fg = PavoA11yService.foregroundPackage();
        if (fg != null && !fg.isEmpty() && !fg.equals(c.getPackageName())) {
            FanProfile app = new FanProfileStore(c).app(fg);
            if (app != null) return app.mode;
        }
        return p.hasFanAppMode() ? p.fanAppMode() : PerfTuner.fanMode(c);
    }

    static String label(Context c, int mode) {
        switch (mode) {
            case PerfTuner.FAN_OFF: return Loc.s(c, "OFF");
            case PerfTuner.FAN_LOW: return Loc.s(c, "LOW");
            case PerfTuner.FAN_SMART: return Loc.s(c, "SMART");
            case PerfTuner.FAN_HIGH: return Loc.s(c, "HIGH");
            case PerfTuner.FAN_MANUAL: return Loc.s(c, "MANUAL");
            case MODE_CURVE: return Loc.s(c, "CURVE");
            case MODE_SMALL: return Loc.s(c, "SMALL");
            case MODE_MEDIUM: return Loc.s(c, "MEDIUM");
            case MODE_LARGE: return Loc.s(c, "LARGE");
            case MODE_HOLD: return Loc.s(c, "HOLD");
            default: return null;
        }
    }

    static String activeLabel(Context c) {
        return label(c, activeMode(c));
    }
}
