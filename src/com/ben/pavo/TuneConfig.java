package com.ben.pavo;

import android.content.Context;

/** Full CPU, GPU, fan and performance-mode snapshot used by the tuner and per-app watcher. */
final class TuneConfig {

    int p0Max;
    int p0Min;
    int p6Max;
    int p6Min;
    int gpuMax;
    int gpuMin;
    String p0Gov = "walt";
    String p6Gov = "walt";
    int fanMode = PerfTuner.FAN_SMART;
    int fanPct = 50;
    int fanSpeed = 25000;
    int performanceMode = 0;
    String profile = "CUSTOM";

    TuneConfig copy() {
        TuneConfig c = new TuneConfig();
        c.p0Max = p0Max;
        c.p0Min = p0Min;
        c.p6Max = p6Max;
        c.p6Min = p6Min;
        c.gpuMax = gpuMax;
        c.gpuMin = gpuMin;
        c.p0Gov = p0Gov;
        c.p6Gov = p6Gov;
        c.fanMode = fanMode;
        c.fanPct = fanPct;
        c.fanSpeed = fanSpeed;
        c.performanceMode = performanceMode;
        c.profile = profile;
        return c;
    }

    static TuneConfig fromState(PerfTuner.State s, int fanMode, int fanPct,
                                int performanceMode, String profile) {
        TuneConfig c = new TuneConfig();
        if (s == null) return c;
        if (s.efficiency != null) {
            c.p0Max = s.efficiency.max;
            c.p0Min = s.efficiency.min;
            c.p0Gov = s.efficiency.governor == null ? "walt" : s.efficiency.governor;
        }
        if (s.performance != null) {
            c.p6Max = s.performance.max;
            c.p6Min = s.performance.min;
            c.p6Gov = s.performance.governor == null ? "walt" : s.performance.governor;
        }
        if (s.gpu != null) {
            c.gpuMax = s.gpu.max;
            c.gpuMin = s.gpu.min;
        }
        if (s.fan != null) c.fanSpeed = s.fan.speedPref;
        c.fanMode = fanMode;
        c.fanPct = clampPct(fanPct);
        c.performanceMode = performanceMode;
        if (profile != null && !profile.isEmpty()) c.profile = profile;
        return c;
    }

    static TuneConfig fromState(PerfTuner.State s) {
        int mode = s != null && s.fan != null ? s.fan.mode : PerfTuner.FAN_SMART;
        int pct = fanPercent(s);
        int perf = s == null ? 0 : s.performanceMode;
        TuneConfig c = fromState(s, mode, pct, perf, "CUSTOM");
        return c;
    }

    static int fanPercent(PerfTuner.State s) {
        if (s == null || s.fan == null) return 50;
        if (s.fan.mode == PerfTuner.FAN_MANUAL && s.fan.speedPref >= 500) {
            return clampPct(Math.round(s.fan.speedPref / 500f));
        }
        if (s.fan.period > 0 && s.fan.duty > 0) {
            return clampPct(Math.round(s.fan.duty * 100f / s.fan.period));
        }
        return 50;
    }

    private static int clampPct(int pct) {
        return Math.max(10, Math.min(100, pct));
    }

    void apply(Context context, PerfTuner.State state) {
        if (state == null) return;
        PerfTuner.setPerformanceMode(performanceMode);
        sleepBriefly();
        if (state.efficiency != null) {
            PerfTuner.setCpu(state.efficiency, p0Max, p0Min, p0Gov);
        }
        if (state.performance != null) {
            PerfTuner.setCpu(state.performance, p6Max, p6Min, p6Gov);
        }
        if (state.gpu != null) {
            PerfTuner.setGpu(state.gpu, gpuMax, gpuMin);
        }
    }

    String encode() {
        return "1|" + p0Max + "|" + p0Min + "|" + safe(p0Gov)
                + "|" + p6Max + "|" + p6Min + "|" + safe(p6Gov)
                + "|" + gpuMax + "|" + gpuMin
                + "|" + fanMode + "|" + fanPct + "|" + fanSpeed
                + "|" + performanceMode + "|" + safe(profile);
    }

    static TuneConfig decode(String value) {
        if (value == null) return null;
        String[] p = value.split("\\|", -1);
        if (p.length < 14 || !"1".equals(p[0])) return null;
        try {
            TuneConfig c = new TuneConfig();
            c.p0Max = Integer.parseInt(p[1]);
            c.p0Min = Integer.parseInt(p[2]);
            c.p0Gov = p[3];
            c.p6Max = Integer.parseInt(p[4]);
            c.p6Min = Integer.parseInt(p[5]);
            c.p6Gov = p[6];
            c.gpuMax = Integer.parseInt(p[7]);
            c.gpuMin = Integer.parseInt(p[8]);
            c.fanMode = Integer.parseInt(p[9]);
            c.fanPct = clampPct(Integer.parseInt(p[10]));
            c.fanSpeed = Integer.parseInt(p[11]);
            c.performanceMode = Integer.parseInt(p[12]);
            c.profile = p[13] == null || p[13].isEmpty() ? "CUSTOM" : p[13];
            if (c.p0Max <= 0 || c.p6Max <= 0 || c.gpuMax <= 0) return null;
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('|', ' ');
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(120L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
