package com.ben.pavo;

import android.content.Context;

/** Keeps the Performance Tuner and Fan Control presets paired by name. */
final class PresetPair {

    private PresetPair() {}

    static int fanModeFor(String perfName) {
        if (PerfProfiles.SMALL.equals(perfName)) return FanProfile.MODE_SMALL;
        if (PerfProfiles.MEDIUM.equals(perfName)) return FanProfile.MODE_MEDIUM;
        if (PerfProfiles.LARGE.equals(perfName)) return FanProfile.MODE_LARGE;
        return -1;
    }

    static String perfNameFor(int fanMode) {
        if (fanMode == FanProfile.MODE_SMALL) return PerfProfiles.SMALL;
        if (fanMode == FanProfile.MODE_MEDIUM) return PerfProfiles.MEDIUM;
        if (fanMode == FanProfile.MODE_LARGE) return PerfProfiles.LARGE;
        return null;
    }

    /** Performance preset selected: make Fan Control follow it. */
    static void applyFan(Context c, String perfName) {
        if (c == null) return;
        int mode = fanModeFor(perfName);
        if (mode < 0) return;
        Prefs prefs = new Prefs(c);
        if (prefs.fanAppMode() == mode) return;
        prefs.setFanAppMode(mode);
        FanProfileStore store = new FanProfileStore(c);
        if (store.hasOverrides()) PerAppFanService.refresh(c);
        else FanControl.start(c);
    }

    /** Fan preset selected: make the Performance Tuner follow it. */
    static void applyPerformance(Context c, int fanMode) {
        if (c == null) return;
        String name = perfNameFor(fanMode);
        if (name != null) PerfTuneService.applyPreset(c, name);
    }
}
