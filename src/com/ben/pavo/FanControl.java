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
}
