package com.ben.pavo;

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

    /**
     * Presets are fractions of whatever this device actually offers, snapped to
     * a supported step, so the same profile means "quiet" or "max" on any CPU
     * rather than a fixed Odin clock.
     */
    static Spec get(String name, PerfTuner.State state) {
        PerfTuner.Cpu efficiency = state == null ? null : state.efficiency;
        PerfTuner.Cpu performance = state == null ? null : state.performance;
        PerfTuner.Gpu gpu = state == null ? null : state.gpu;
        if (QUIET.equals(name)) {
            return new Spec(QUIET,
                    maxFrac(efficiency, 0.63f), minFrac(efficiency, 0.56f),
                    maxFrac(performance, 0.45f), minFrac(performance, 0.24f),
                    maxFrac(gpu, 0.48f),
                    PerfTuner.FAN_LOW, 0, 10, "schedutil", 4);
        }
        if (PERFORMANCE.equals(name)) {
            return new Spec(PERFORMANCE,
                    maxFrac(efficiency, 1f), minFrac(efficiency, 0.63f),
                    maxFrac(performance, 1f), minFrac(performance, 0.76f),
                    maxFrac(gpu, 1f),
                    PerfTuner.FAN_HIGH, 0, 50, "walt", 1);
        }
        if (EXTREME.equals(name)) {
            int eMax = maxFrac(efficiency, 1f);
            int pMax = maxFrac(performance, 1f);
            return new Spec(EXTREME, eMax, eMax, pMax, pMax, maxFrac(gpu, 1f),
                    PerfTuner.FAN_MANUAL, 0, 100, "performance", 3);
        }
        if (STOCK.equals(name)) {
            return new Spec(STOCK,
                    maxFrac(efficiency, 1f), minFrac(efficiency, 0.56f),
                    maxFrac(performance, 1f), minFrac(performance, 0.52f),
                    maxFrac(gpu, 1f),
                    PerfTuner.FAN_SMART, 25000, 50, "walt", 0);
        }
        return new Spec(BALANCED,
                maxFrac(efficiency, 0.78f), minFrac(efficiency, 0.56f),
                maxFrac(performance, 0.76f), minFrac(performance, 0.52f),
                maxFrac(gpu, 0.76f),
                PerfTuner.FAN_SMART, 0, 50, "walt", 0);
    }

    /** Highest supported frequency at or below the fraction of the top step. */
    private static int maxFrac(PerfTuner.Cpu cpu, float fraction) {
        if (cpu == null || cpu.freqs == null || cpu.freqs.length == 0) return 0;
        int top = cpu.freqs[cpu.freqs.length - 1];
        int target = Math.round(top * Math.max(0.05f, Math.min(1f, fraction)));
        int best = cpu.freqs[0];
        for (int freq : cpu.freqs) {
            if (freq <= target) best = freq;
        }
        return best;
    }

    /** Lowest supported frequency at or above the fraction of the top step. */
    private static int minFrac(PerfTuner.Cpu cpu, float fraction) {
        if (cpu == null || cpu.freqs == null || cpu.freqs.length == 0) return 0;
        int top = cpu.freqs[cpu.freqs.length - 1];
        int target = Math.round(top * Math.max(0.05f, Math.min(1f, fraction)));
        for (int freq : cpu.freqs) {
            if (freq >= target) return freq;
        }
        return top;
    }

    private static int maxFrac(PerfTuner.Gpu gpu, float fraction) {
        if (gpu == null || gpu.freqs == null || gpu.freqs.length == 0) return 0;
        int top = gpu.freqs[gpu.freqs.length - 1];
        int target = Math.round(top * Math.max(0.05f, Math.min(1f, fraction)));
        int best = gpu.freqs[0];
        for (int freq : gpu.freqs) {
            if (freq <= target) best = freq;
        }
        return best;
    }

    static boolean isProfile(String name) {
        for (String n : NAMES) if (n.equals(name)) return true;
        return false;
    }
}
