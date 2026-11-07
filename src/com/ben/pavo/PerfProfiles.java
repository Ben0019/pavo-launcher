package com.ben.pavo;

/**
 * Presets mirror ClusterTune's bundled underclocks: they cap the CPU maximum
 * frequencies and leave minimums, governors, GPU and fan untouched.
 */
final class PerfProfiles {

    static final String SMALL = "SMALL";
    static final String MEDIUM = "MEDIUM";
    static final String LARGE = "LARGE";
    static final String STOCK = "STOCK";

    static final String[] NAMES = {SMALL, MEDIUM, LARGE, STOCK};

    static final class Spec {
        final String name;
        final int p0Max;
        final int p0Min;
        final int p6Max;
        final int p6Min;
        final int gpuMax;
        final int gpuMin;
        final int fanMode;
        final int fanSpeed;
        final int fanPct;
        final String governor;
        final int performanceMode;

        Spec(String name, int p0Max, int p0Min, int p6Max, int p6Min,
             int gpuMax, int gpuMin, int fanMode, int fanSpeed, int fanPct,
             String governor, int performanceMode) {
            this.name = name;
            this.p0Max = p0Max;
            this.p0Min = p0Min;
            this.p6Max = p6Max;
            this.p6Min = p6Min;
            this.gpuMax = gpuMax;
            this.gpuMin = gpuMin;
            this.fanMode = fanMode;
            this.fanSpeed = fanSpeed;
            this.fanPct = fanPct;
            this.governor = governor;
            this.performanceMode = performanceMode;
        }
    }

    // ClusterTune CQ8725S maxima expressed as fractions of the top step, so
    // the same preset lands on the equivalent step of other devices.
    private static final float SMALL0 = 2745600f / 3532800f;
    private static final float SMALL6 = 3072000f / 4320000f;
    private static final float MEDIUM0 = 2227200f / 3532800f;
    private static final float MEDIUM6 = 2246400f / 4320000f;
    private static final float LARGE0 = 1785600f / 3532800f;
    private static final float LARGE6 = 1958400f / 4320000f;

    private PerfProfiles() {
    }

    static Spec get(String name, PerfTuner.State state) {
        PerfTuner.Cpu efficiency = state == null ? null : state.efficiency;
        PerfTuner.Cpu performance = state == null ? null : state.performance;
        PerfTuner.Gpu gpu = state == null ? null : state.gpu;
        // Underclocks must let the minimum fall, otherwise the kernel clamps a
        // cap below the current floor back up to it. ClusterTune lowers the
        // floor for the same reason; Stock leaves the current floor alone.
        int p0Min = efficiency != null && efficiency.freqs.length > 0
                ? (STOCK.equals(name) && efficiency.min > 0
                        ? efficiency.min : efficiency.freqs[0]) : 0;
        int p6Min = performance != null && performance.freqs.length > 0
                ? (STOCK.equals(name) && performance.min > 0
                        ? performance.min : performance.freqs[0]) : 0;
        int gpuMax = gpu != null && gpu.max > 0 ? gpu.max : 0;
        int gpuMin = gpu != null && gpu.min > 0 ? gpu.min : 0;
        String governor = efficiency != null && efficiency.governor != null
                && !efficiency.governor.isEmpty()
                ? efficiency.governor
                : performance != null ? performance.governor : null;
        int perfMode = state == null ? 0 : state.performanceMode;

        if (STOCK.equals(name)) {
            return new Spec(STOCK, maxFrac(efficiency, 1f), p0Min,
                    maxFrac(performance, 1f), p6Min, gpuMax, gpuMin,
                    PerfTuner.FAN_SMART, 0, 50, governor, perfMode);
        }
        float f0 = SMALL0;
        float f6 = SMALL6;
        if (LARGE.equals(name)) {
            f0 = LARGE0;
            f6 = LARGE6;
        } else if (MEDIUM.equals(name)) {
            f0 = MEDIUM0;
            f6 = MEDIUM6;
        }
        return new Spec(name, maxFrac(efficiency, f0), p0Min,
                maxFrac(performance, f6), p6Min, gpuMax, gpuMin,
                PerfTuner.FAN_SMART, 0, 50, governor, perfMode);
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

    static boolean isProfile(String name) {
        for (String n : NAMES) if (n.equals(name)) return true;
        return false;
    }
}
