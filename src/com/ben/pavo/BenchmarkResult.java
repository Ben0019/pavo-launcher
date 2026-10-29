package com.ben.pavo;

import android.content.Intent;

/** Immutable-enough snapshot of one completed benchmark run. */
final class BenchmarkResult {
    static final String EXTRA_TIMESTAMP = "benchmark_result_timestamp";
    static final String EXTRA_DURATION = "benchmark_result_duration";
    static final String EXTRA_MODE = "benchmark_result_mode";
    static final String EXTRA_THREADS = "benchmark_result_threads";
    static final String EXTRA_LENGTH = "benchmark_result_length";
    static final String EXTRA_CPU_TUNE = "benchmark_result_cpu_tune";
    static final String EXTRA_CPU_EFF_TUNE = "benchmark_result_cpu_eff_tune";
    static final String EXTRA_GPU_TUNE = "benchmark_result_gpu_tune";
    static final String EXTRA_FAN_TUNE = "benchmark_result_fan_tune";
    static final String EXTRA_COMPLETED = "benchmark_result_completed";
    static final String EXTRA_CPU_SCORE = "benchmark_result_cpu_score";
    static final String EXTRA_GPU_FPS = "benchmark_result_gpu_fps";
    static final String EXTRA_AVG_FPS = "benchmark_result_avg_fps";
    static final String EXTRA_MIN_FPS = "benchmark_result_min_fps";
    static final String EXTRA_MAX_FPS = "benchmark_result_max_fps";
    static final String EXTRA_CPU_TEMP_MAX = "benchmark_result_cpu_temp_max";
    static final String EXTRA_CPU_TEMP_AVG = "benchmark_result_cpu_temp_avg";
    static final String EXTRA_GPU_TEMP_MAX = "benchmark_result_gpu_temp_max";
    static final String EXTRA_GPU_TEMP_AVG = "benchmark_result_gpu_temp_avg";
    static final String EXTRA_CPU_USE_AVG = "benchmark_result_cpu_use_avg";
    static final String EXTRA_CPU_USE_MAX = "benchmark_result_cpu_use_max";
    static final String EXTRA_GPU_USE_AVG = "benchmark_result_gpu_use_avg";
    static final String EXTRA_GPU_USE_MAX = "benchmark_result_gpu_use_max";
    static final String EXTRA_SAMPLES = "benchmark_result_samples";
    static final String EXTRA_THROTTLE_PCT = "benchmark_result_throttle_pct";
    static final String EXTRA_CPU_THROTTLE_PCT = "benchmark_result_cpu_throttle_pct";
    static final String EXTRA_GPU_THROTTLE_PCT = "benchmark_result_gpu_throttle_pct";
    static final String EXTRA_CPU_SUSTAINED_PCT = "benchmark_result_cpu_sustained_pct";
    static final String EXTRA_GPU_SUSTAINED_PCT = "benchmark_result_gpu_sustained_pct";
    static final String EXTRA_CPU_HELD_PCT = "benchmark_result_cpu_held_pct";
    static final String EXTRA_GPU_HELD_PCT = "benchmark_result_gpu_held_pct";
    static final String EXTRA_CPU_HOLD_SEC = "benchmark_result_cpu_hold_sec";
    static final String EXTRA_GPU_HOLD_SEC = "benchmark_result_gpu_hold_sec";
    static final String EXTRA_CPU_CLOCK_HISTORY = "benchmark_result_cpu_clock_history";
    static final String EXTRA_GPU_CLOCK_HISTORY = "benchmark_result_gpu_clock_history";
    static final String EXTRA_FPS_HISTORY = "benchmark_result_fps_history";
    static final String EXTRA_CPU_TEMP_HISTORY = "benchmark_result_cpu_temp_history";
    static final String EXTRA_GPU_TEMP_HISTORY = "benchmark_result_gpu_temp_history";
    static final String EXTRA_CPU_USE_HISTORY = "benchmark_result_cpu_use_history";
    static final String EXTRA_GPU_USE_HISTORY = "benchmark_result_gpu_use_history";

    long timestamp;
    long durationMs;
    int mode;
    int threads;
    int lengthSec;
    int cpuTune;
    int cpuEffTune = 100;
    int gpuTune;
    int fanTune;
    boolean completed;
    float cpuScore;
    float gpuFps;
    float avgFps;
    float minFps;
    float maxFps;
    float cpuTempMax;
    float cpuTempAvg;
    float gpuTempMax;
    float gpuTempAvg;
    int cpuUseAvg;
    int cpuUseMax;
    int gpuUseAvg;
    int gpuUseMax;
    int samples;
    int throttlePct = 100;
    int cpuThrottlePct = 100;
    int gpuThrottlePct = 100;
    int cpuSustainedPct = 100;
    int gpuSustainedPct = 100;
    int cpuHeldPct = 100;
    int gpuHeldPct = 100;
    int cpuHoldSec;
    int gpuHoldSec;
    int[] cpuClockHistory;
    int[] gpuClockHistory;
    int[] fpsHistory;
    int[] cpuTempHistory;
    int[] gpuTempHistory;
    int[] cpuUseHistory;
    int[] gpuUseHistory;

    void writeTo(Intent intent) {
        intent.putExtra(EXTRA_TIMESTAMP, timestamp);
        intent.putExtra(EXTRA_DURATION, durationMs);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_THREADS, threads);
        intent.putExtra(EXTRA_LENGTH, lengthSec);
        intent.putExtra(EXTRA_CPU_TUNE, cpuTune);
        intent.putExtra(EXTRA_CPU_EFF_TUNE, cpuEffTune);
        intent.putExtra(EXTRA_GPU_TUNE, gpuTune);
        intent.putExtra(EXTRA_FAN_TUNE, fanTune);
        intent.putExtra(EXTRA_COMPLETED, completed);
        intent.putExtra(EXTRA_CPU_SCORE, cpuScore);
        intent.putExtra(EXTRA_GPU_FPS, gpuFps);
        intent.putExtra(EXTRA_AVG_FPS, avgFps);
        intent.putExtra(EXTRA_MIN_FPS, minFps);
        intent.putExtra(EXTRA_MAX_FPS, maxFps);
        intent.putExtra(EXTRA_CPU_TEMP_MAX, cpuTempMax);
        intent.putExtra(EXTRA_CPU_TEMP_AVG, cpuTempAvg);
        intent.putExtra(EXTRA_GPU_TEMP_MAX, gpuTempMax);
        intent.putExtra(EXTRA_GPU_TEMP_AVG, gpuTempAvg);
        intent.putExtra(EXTRA_CPU_USE_AVG, cpuUseAvg);
        intent.putExtra(EXTRA_CPU_USE_MAX, cpuUseMax);
        intent.putExtra(EXTRA_GPU_USE_AVG, gpuUseAvg);
        intent.putExtra(EXTRA_GPU_USE_MAX, gpuUseMax);
        intent.putExtra(EXTRA_SAMPLES, samples);
        intent.putExtra(EXTRA_THROTTLE_PCT, throttlePct);
        intent.putExtra(EXTRA_CPU_THROTTLE_PCT, cpuThrottlePct);
        intent.putExtra(EXTRA_GPU_THROTTLE_PCT, gpuThrottlePct);
        intent.putExtra(EXTRA_CPU_SUSTAINED_PCT, cpuSustainedPct);
        intent.putExtra(EXTRA_GPU_SUSTAINED_PCT, gpuSustainedPct);
        intent.putExtra(EXTRA_CPU_HELD_PCT, cpuHeldPct);
        intent.putExtra(EXTRA_GPU_HELD_PCT, gpuHeldPct);
        intent.putExtra(EXTRA_CPU_HOLD_SEC, cpuHoldSec);
        intent.putExtra(EXTRA_GPU_HOLD_SEC, gpuHoldSec);
        intent.putExtra(EXTRA_CPU_CLOCK_HISTORY, cpuClockHistory);
        intent.putExtra(EXTRA_GPU_CLOCK_HISTORY, gpuClockHistory);
        intent.putExtra(EXTRA_FPS_HISTORY, fpsHistory);
        intent.putExtra(EXTRA_CPU_TEMP_HISTORY, cpuTempHistory);
        intent.putExtra(EXTRA_GPU_TEMP_HISTORY, gpuTempHistory);
        intent.putExtra(EXTRA_CPU_USE_HISTORY, cpuUseHistory);
        intent.putExtra(EXTRA_GPU_USE_HISTORY, gpuUseHistory);
    }

    static BenchmarkResult fromIntent(Intent intent) {
        BenchmarkResult r = new BenchmarkResult();
        if (intent == null) return r;
        r.timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0L);
        r.durationMs = intent.getLongExtra(EXTRA_DURATION, 0L);
        r.mode = intent.getIntExtra(EXTRA_MODE, BenchmarkStageView.MODE_BOTH);
        r.threads = intent.getIntExtra(EXTRA_THREADS, 1);
        r.lengthSec = intent.getIntExtra(EXTRA_LENGTH, 0);
        r.cpuTune = intent.getIntExtra(EXTRA_CPU_TUNE, 100);
        r.cpuEffTune = intent.getIntExtra(EXTRA_CPU_EFF_TUNE, 100);
        r.gpuTune = intent.getIntExtra(EXTRA_GPU_TUNE, 100);
        r.fanTune = intent.getIntExtra(EXTRA_FAN_TUNE, 0);
        r.completed = intent.getBooleanExtra(EXTRA_COMPLETED, true);
        r.cpuScore = intent.getFloatExtra(EXTRA_CPU_SCORE, 0f);
        r.gpuFps = intent.getFloatExtra(EXTRA_GPU_FPS, 0f);
        r.avgFps = intent.getFloatExtra(EXTRA_AVG_FPS, 0f);
        r.minFps = intent.getFloatExtra(EXTRA_MIN_FPS, 0f);
        r.maxFps = intent.getFloatExtra(EXTRA_MAX_FPS, 0f);
        r.cpuTempMax = intent.getFloatExtra(EXTRA_CPU_TEMP_MAX, 0f);
        r.cpuTempAvg = intent.getFloatExtra(EXTRA_CPU_TEMP_AVG, 0f);
        r.gpuTempMax = intent.getFloatExtra(EXTRA_GPU_TEMP_MAX, 0f);
        r.gpuTempAvg = intent.getFloatExtra(EXTRA_GPU_TEMP_AVG, 0f);
        r.cpuUseAvg = intent.getIntExtra(EXTRA_CPU_USE_AVG, 0);
        r.cpuUseMax = intent.getIntExtra(EXTRA_CPU_USE_MAX, 0);
        r.gpuUseAvg = intent.getIntExtra(EXTRA_GPU_USE_AVG, 0);
        r.gpuUseMax = intent.getIntExtra(EXTRA_GPU_USE_MAX, 0);
        r.samples = intent.getIntExtra(EXTRA_SAMPLES, 0);
        r.throttlePct = intent.getIntExtra(EXTRA_THROTTLE_PCT, 100);
        r.cpuThrottlePct = intent.getIntExtra(EXTRA_CPU_THROTTLE_PCT, 100);
        r.gpuThrottlePct = intent.getIntExtra(EXTRA_GPU_THROTTLE_PCT, 100);
        r.cpuSustainedPct = intent.getIntExtra(EXTRA_CPU_SUSTAINED_PCT, 100);
        r.gpuSustainedPct = intent.getIntExtra(EXTRA_GPU_SUSTAINED_PCT, 100);
        r.cpuHeldPct = intent.getIntExtra(EXTRA_CPU_HELD_PCT, 100);
        r.gpuHeldPct = intent.getIntExtra(EXTRA_GPU_HELD_PCT, 100);
        r.cpuHoldSec = intent.getIntExtra(EXTRA_CPU_HOLD_SEC, 0);
        r.gpuHoldSec = intent.getIntExtra(EXTRA_GPU_HOLD_SEC, 0);
        r.cpuClockHistory = intent.getIntArrayExtra(EXTRA_CPU_CLOCK_HISTORY);
        r.gpuClockHistory = intent.getIntArrayExtra(EXTRA_GPU_CLOCK_HISTORY);
        r.fpsHistory = intent.getIntArrayExtra(EXTRA_FPS_HISTORY);
        r.cpuTempHistory = intent.getIntArrayExtra(EXTRA_CPU_TEMP_HISTORY);
        r.gpuTempHistory = intent.getIntArrayExtra(EXTRA_GPU_TEMP_HISTORY);
        r.cpuUseHistory = intent.getIntArrayExtra(EXTRA_CPU_USE_HISTORY);
        r.gpuUseHistory = intent.getIntArrayExtra(EXTRA_GPU_USE_HISTORY);
        return r;
    }

    String modeLabel() {
        if (mode == BenchmarkStageView.MODE_CPU) return "CPU";
        if (mode == BenchmarkStageView.MODE_GPU) return "GPU";
        return "COMBINED";
    }

    void normalizeThrottle() {
        cpuSustainedPct = sustained(cpuClockHistory, cpuThrottlePct);
        gpuSustainedPct = sustained(gpuClockHistory, gpuThrottlePct);
        cpuHeldPct = heldPercent(cpuClockHistory, cpuThrottlePct);
        gpuHeldPct = heldPercent(gpuClockHistory, gpuThrottlePct);
        cpuHoldSec = holdSeconds(cpuClockHistory);
        gpuHoldSec = holdSeconds(gpuClockHistory);

        int cpuScore = performanceScore(cpuHeldPct, cpuSustainedPct);
        int gpuScore = performanceScore(gpuHeldPct, gpuSustainedPct);
        int scoreTotal = 0;
        int scoreCount = 0;
        if (mode != BenchmarkStageView.MODE_GPU) {
            scoreTotal += cpuScore;
            scoreCount++;
        }
        if (mode != BenchmarkStageView.MODE_CPU) {
            scoreTotal += gpuScore;
            scoreCount++;
        }
        int baseScore = scoreCount > 0 ? scoreTotal / scoreCount : 100;
        // Short runs are less conclusive. At least 30 seconds of sustained
        // clock holding is required for full confidence.
        float evidence = Math.min(1f, Math.max(0f, durationMs / 30000f));
        throttlePct = Math.round(baseScore * (0.5f + 0.5f * evidence));
    }

    int evidencePct() {
        return Math.round(Math.min(1f, Math.max(0f, durationMs / 30000f)) * 100f);
    }

    String evidenceLabel() {
        return durationMs >= 30000L ? "30s+ VALID" : "SHORT RUN";
    }

    private static int performanceScore(int heldPct, int sustainedPct) {
        // Sustained-clock duration matters most; the final-quarter retention
        // adds context for how far the clock fell after throttling began.
        return Math.round(heldPct * 0.72f + sustainedPct * 0.28f);
    }

    private static int heldPercent(int[] history, int fallback) {
        if (history == null || history.length == 0) return fallback;
        int held = 0;
        int count = 0;
        for (int v : history) {
            if (v <= 0) continue;
            count++;
            if (v >= 90) held++;
        }
        return count == 0 ? fallback : Math.round(held * 100f / count);
    }

    private static int holdSeconds(int[] history) {
        if (history == null || history.length == 0) return 0;
        int best = 0;
        int run = 0;
        for (int v : history) {
            if (v >= 90) {
                run++;
                if (run > best) best = run;
            } else {
                run = 0;
            }
        }
        return best;
    }

    private static int sustained(int[] history, int fallback) {
        if (history == null || history.length == 0) return fallback;
        int start = Math.max(0, history.length - Math.max(1, history.length / 4));
        int sum = 0;
        int count = 0;
        for (int i = start; i < history.length; i++) {
            if (history[i] > 0) {
                sum += history[i];
                count++;
            }
        }
        return count == 0 ? fallback : Math.round(sum / (float) count);
    }

    int primaryHeldPct() {
        if (cpuHoldSec <= 0) return gpuHeldPct;
        if (gpuHoldSec <= 0) return cpuHeldPct;
        return Math.min(cpuHeldPct, gpuHeldPct);
    }

    int primarySustainedPct() {
        if (cpuHoldSec <= 0) return gpuSustainedPct;
        if (gpuHoldSec <= 0) return cpuSustainedPct;
        return Math.min(cpuSustainedPct, gpuSustainedPct);
    }

    int primaryHoldSec() {
        if (cpuHoldSec <= 0) return gpuHoldSec;
        if (gpuHoldSec <= 0) return cpuHoldSec;
        return Math.min(cpuHoldSec, gpuHoldSec);
    }

    String throttleLabel() {
        if (throttlePct >= 90) return "GOOD";
        if (throttlePct >= 75) return "MEDIUM";
        return "BAD";
    }

    int throttleColor() {
        if (throttlePct >= 90) return 0xFF69D98B;
        if (throttlePct >= 75) return 0xFFFFC857;
        return 0xFFFF6B6B;
    }

    String lengthLabel() {
        if (lengthSec <= 0) return "--";
        if (lengthSec < 60) return lengthSec + "s";
        return String.format(java.util.Locale.US, "%d:%02d",
                lengthSec / 60, lengthSec % 60);
    }

    String tuneLabel(android.content.Context c) {
        String fan = fanTune == -1 ? Loc.s(c, "QUIET")
                : fanTune == -2 ? Loc.s(c, "SMART")
                : fanTune == -3 ? Loc.s(c, "SPORT")
                : fanTune == 0 ? Loc.s(c, "AUTO") : fanTune + "%";
        return "E " + cpuEffTune + "%  ·  P " + cpuTune + "%"
                + "  ·  " + Loc.s(c, "GPU") + " " + gpuTune + "%  ·  "
                + Loc.s(c, "FAN") + " " + fan;
    }
}
