package com.ben.aurora;

/** Shared profile definitions used by the tuner and the per-app watcher. */
final class PerfProfiles {

    static final String QUIET = "QUIET";
    static final String BALANCED = "BALANCED";
    static final String PERFORMANCE = "PERFORMANCE";
    static final String EXTREME = "EXTREME";
    static final String STOCK = "STOCK";

    static final String[] NAMES = {QUIET, BALANCED, PERFORMANCE, EXTREME, STOCK};

    static final class Spec {
        final String name;
        final int p0Max;
        final int p0Min;
        final int p6Max;
        final int p6Min;
        final int gpuMax;
        final int fanMode;
        final int fanSpeed;
        final int fanPct;
        final String governor;
        final int performanceMode;

        Spec(String name, int p0Max, int p0Min, int p6Max, int p6Min, int gpuMax,
             int fanMode, int fanSpeed, int fanPct, String governor, int performanceMode) {
            this.name = name;
            this.p0Max = p0Max;
            this.p0Min = p0Min;
            this.p6Max = p6Max;
            this.p6Min = p6Min;
            this.gpuMax = gpuMax;
            this.fanMode = fanMode;
            this.fanSpeed = fanSpeed;
            this.fanPct = fanPct;
            this.governor = governor;
            this.performanceMode = performanceMode;
        }
    }

    private PerfProfiles() {
    }

    static Spec get(String name) {
        if (QUIET.equals(name)) {
            return new Spec(QUIET, 2227200, 1996800, 1958400, 1017600, 525000000,
                    PerfTuner.FAN_LOW, 0, 10, "schedutil", 4);
        }
        if (PERFORMANCE.equals(name)) {
            return new Spec(PERFORMANCE, 3532800, 2227200, 4320000, 3283200, 1100000000,
                    PerfTuner.FAN_HIGH, 0, 50, "walt", 1);
        }
        if (EXTREME.equals(name)) {
            return new Spec(EXTREME, 3532800, 3532800, 4320000, 4320000, 1100000000,
                    PerfTuner.FAN_MANUAL, 0, 100, "performance", 3);
        }
        if (STOCK.equals(name)) {
            return new Spec(STOCK, 3532800, 1996800, 4320000, 2246400, 1100000000,
                    PerfTuner.FAN_SMART, 25000, 50, "walt", 0);
        }
        return new Spec(BALANCED, 2745600, 1996800, 3283200, 2246400, 832000000,
                PerfTuner.FAN_SMART, 0, 50, "walt", 0);
    }

    static boolean isProfile(String name) {
        for (String n : NAMES) if (n.equals(name)) return true;
        return false;
    }
}
