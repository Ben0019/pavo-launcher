package com.ben.pavo;

import android.content.Context;

/**
 * Picks the fan-loop host: the accessibility service when it is connected
 * (zero notifications), otherwise the foreground FanCurveService.
 */
final class FanControl {
    private FanControl() {}

    static void start(Context c) {
        if (c == null) return;
        if (PavoA11yService.isConnected()) {
            FanEngine.start(c);
            if (FanCurveService.isActive()) FanCurveService.stop(c);
        } else {
            FanCurveService.start(c);
            FanEngine.stop(c);
        }
    }

    static void update(Context c) {
        if (c == null) return;
        if (PavoA11yService.isConnected()) FanEngine.update(c);
        else FanCurveService.update(c);
    }

    static void stop(Context c) {
        if (c == null) return;
        FanEngine.stop(c);
        if (FanCurveService.isActive()) FanCurveService.stop(c);
    }

    /** Called when the accessibility host goes away: keep the curve running. */
    static void fallback(Context c) {
        if (c == null) return;
        FanEngine.stop(c);
        Prefs prefs = new Prefs(c);
        int mode = prefs.fanAppMode();
        if (mode == FanActivity.MODE_CURVE || FanCurve.presetCurve(mode) != null
                || mode == PerfTuner.FAN_SMART || mode == PerfTuner.FAN_HIGH) {
            if (!FanCurveService.isActive()) FanCurveService.start(c);
        }
    }
}
