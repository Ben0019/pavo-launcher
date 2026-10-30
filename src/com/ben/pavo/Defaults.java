package com.ben.pavo;

import android.content.Context;

/**
 * First-run defaults: the Balanced tuner profile and Smart fan control.
 * Applied once, so a fresh install starts from a sensible, quiet setup instead
 * of stock clocks with the fan left to the firmware.
 */
final class Defaults {

    private Defaults() {}

    static void apply(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Prefs prefs = new Prefs(app);
        prefs.setFanAppMode(PerfTuner.FAN_SMART);
        prefs.setDefaultsApplied(true);
        // PServer can still be coming up on the very first launch; make sure the
        // channel exists, then apply and re-apply once it has settled.
        PerfTuner.preparePServer();
        PerfTuner.State state = PerfTuner.readState(app);
        if (state == null) return;
        PerfProfiles.Spec spec = PerfProfiles.get(PerfProfiles.BALANCED, state);
        // The vendor daemon can race the first write with its own restore, so
        // apply a few times until the hardware really reports the target.
        for (int attempt = 0; attempt < 5; attempt++) {
            applyClocks(state, spec);
            try {
                Thread.sleep(350L);
            } catch (InterruptedException ignored) {
            }
            PerfTuner.State now = PerfTuner.readState(app);
            if (now != null && now.efficiency != null && now.performance != null
                    && spec.p0Max > 0 && now.efficiency.max == spec.p0Max
                    && now.performance.max == spec.p6Max) {
                state = now;
                break;
            }
            if (now != null) state = now;
        }
        TuneConfig config = TuneConfig.fromState(state, PerfTuner.FAN_SMART,
                prefs.fanManualPercent(), spec.performanceMode, PerfProfiles.BALANCED);
        config.p0Max = spec.p0Max;
        config.p0Min = spec.p0Min;
        config.p0Gov = spec.governor;
        config.p6Max = spec.p6Max;
        config.p6Min = spec.p6Min;
        config.p6Gov = spec.governor;
        config.gpuMax = spec.gpuMax;
        config.profile = PerfProfiles.BALANCED;
        new TuneStore(app).setGlobal(config);
    }

    private static void applyClocks(PerfTuner.State state, PerfProfiles.Spec spec) {
        PerfTuner.setCpu(state.efficiency, spec.p0Max, spec.p0Min, spec.governor);
        PerfTuner.setCpu(state.performance, spec.p6Max, spec.p6Min, spec.governor);
        if (state.gpu != null && state.gpu.freqs.length > 0) {
            PerfTuner.setGpu(state.gpu, spec.gpuMax, state.gpu.freqs[0]);
        }
    }
}
