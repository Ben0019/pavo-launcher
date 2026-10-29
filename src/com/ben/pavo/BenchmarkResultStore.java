package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistent rolling history for the Stress Test result page. */
final class BenchmarkResultStore {
    private static final String PREFS = "stress_test_results";
    private static final String KEY_RUNS = "runs";
    private static final int MAX_RESULTS = 12;

    private BenchmarkResultStore() {}

    static List<BenchmarkResult> load(Context context) {
        List<BenchmarkResult> out = new ArrayList<>();
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_RUNS, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray runs = new JSONArray(raw);
            for (int i = 0; i < runs.length() && out.size() < MAX_RESULTS; i++) {
                JSONObject o = runs.optJSONObject(i);
                if (o == null) continue;
                BenchmarkResult r = fromJson(o);
                if (r != null) out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static void add(Context context, BenchmarkResult result) {
        if (result == null) return;
        if (result.timestamp <= 0) result.timestamp = System.currentTimeMillis();
        List<BenchmarkResult> runs = load(context);
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).timestamp == result.timestamp) runs.remove(i);
        }
        runs.add(0, result);
        while (runs.size() > MAX_RESULTS) runs.remove(runs.size() - 1);
        save(context, runs);
    }

    static void remove(Context context, long timestamp) {
        List<BenchmarkResult> runs = load(context);
        for (int i = runs.size() - 1; i >= 0; i--) {
            if (runs.get(i).timestamp == timestamp) runs.remove(i);
        }
        save(context, runs);
    }

    private static void save(Context context, List<BenchmarkResult> runs) {
        JSONArray array = new JSONArray();
        for (BenchmarkResult r : runs) array.put(toJson(r));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RUNS, array.toString()).apply();
    }

    private static JSONObject toJson(BenchmarkResult r) {
        JSONObject o = new JSONObject();
        try {
            o.put("timestamp", r.timestamp);
            o.put("durationMs", r.durationMs);
            o.put("mode", r.mode);
            o.put("threads", r.threads);
            o.put("lengthSec", r.lengthSec);
            o.put("cpuTune", r.cpuTune);
            o.put("cpuEffTune", r.cpuEffTune);
            o.put("gpuTune", r.gpuTune);
            o.put("fanTune", r.fanTune);
            o.put("completed", r.completed);
            o.put("cpuScore", r.cpuScore);
            o.put("gpuFps", r.gpuFps);
            o.put("avgFps", r.avgFps);
            o.put("minFps", r.minFps);
            o.put("maxFps", r.maxFps);
            o.put("cpuTempMax", r.cpuTempMax);
            o.put("cpuTempAvg", r.cpuTempAvg);
            o.put("gpuTempMax", r.gpuTempMax);
            o.put("gpuTempAvg", r.gpuTempAvg);
            o.put("cpuUseAvg", r.cpuUseAvg);
            o.put("cpuUseMax", r.cpuUseMax);
            o.put("gpuUseAvg", r.gpuUseAvg);
            o.put("gpuUseMax", r.gpuUseMax);
            o.put("samples", r.samples);
            o.put("throttlePct", r.throttlePct);
            o.put("cpuThrottlePct", r.cpuThrottlePct);
            o.put("gpuThrottlePct", r.gpuThrottlePct);
            o.put("cpuSustainedPct", r.cpuSustainedPct);
            o.put("gpuSustainedPct", r.gpuSustainedPct);
            o.put("cpuHeldPct", r.cpuHeldPct);
            o.put("gpuHeldPct", r.gpuHeldPct);
            o.put("cpuHoldSec", r.cpuHoldSec);
            o.put("gpuHoldSec", r.gpuHoldSec);
            o.put("cpuClock", ints(r.cpuClockHistory));
            o.put("gpuClock", ints(r.gpuClockHistory));
            o.put("fps", ints(r.fpsHistory));
            o.put("cpuTemp", ints(r.cpuTempHistory));
            o.put("gpuTemp", ints(r.gpuTempHistory));
            o.put("cpuUse", ints(r.cpuUseHistory));
            o.put("gpuUse", ints(r.gpuUseHistory));
        } catch (Throwable ignored) {
        }
        return o;
    }

    private static BenchmarkResult fromJson(JSONObject o) {
        try {
            BenchmarkResult r = new BenchmarkResult();
            r.timestamp = o.optLong("timestamp", 0L);
            r.durationMs = o.optLong("durationMs", 0L);
            r.mode = o.optInt("mode", BenchmarkStageView.MODE_BOTH);
            r.threads = o.optInt("threads", 1);
            r.lengthSec = o.optInt("lengthSec", 0);
            r.cpuTune = o.optInt("cpuTune", 100);
            r.cpuEffTune = o.optInt("cpuEffTune", 100);
            r.gpuTune = o.optInt("gpuTune", 100);
            r.fanTune = o.optInt("fanTune", 100);
            r.completed = o.optBoolean("completed", true);
            r.cpuScore = (float) o.optDouble("cpuScore", 0d);
            r.gpuFps = (float) o.optDouble("gpuFps", 0d);
            r.avgFps = (float) o.optDouble("avgFps", 0d);
            r.minFps = (float) o.optDouble("minFps", 0d);
            r.maxFps = (float) o.optDouble("maxFps", 0d);
            r.cpuTempMax = (float) o.optDouble("cpuTempMax", 0d);
            r.cpuTempAvg = (float) o.optDouble("cpuTempAvg", 0d);
            r.gpuTempMax = (float) o.optDouble("gpuTempMax", 0d);
            r.gpuTempAvg = (float) o.optDouble("gpuTempAvg", 0d);
            r.cpuUseAvg = o.optInt("cpuUseAvg", 0);
            r.cpuUseMax = o.optInt("cpuUseMax", 0);
            r.gpuUseAvg = o.optInt("gpuUseAvg", 0);
            r.gpuUseMax = o.optInt("gpuUseMax", 0);
            r.samples = o.optInt("samples", 0);
            r.throttlePct = o.optInt("throttlePct", 100);
            r.cpuThrottlePct = o.optInt("cpuThrottlePct", 100);
            r.gpuThrottlePct = o.optInt("gpuThrottlePct", 100);
            r.cpuSustainedPct = o.optInt("cpuSustainedPct", 100);
            r.gpuSustainedPct = o.optInt("gpuSustainedPct", 100);
            r.cpuHeldPct = o.optInt("cpuHeldPct", 100);
            r.gpuHeldPct = o.optInt("gpuHeldPct", 100);
            r.cpuHoldSec = o.optInt("cpuHoldSec", 0);
            r.gpuHoldSec = o.optInt("gpuHoldSec", 0);
            r.cpuClockHistory = ints(o.optJSONArray("cpuClock"));
            r.gpuClockHistory = ints(o.optJSONArray("gpuClock"));
            r.fpsHistory = ints(o.optJSONArray("fps"));
            r.cpuTempHistory = ints(o.optJSONArray("cpuTemp"));
            r.gpuTempHistory = ints(o.optJSONArray("gpuTemp"));
            r.cpuUseHistory = ints(o.optJSONArray("cpuUse"));
            r.gpuUseHistory = ints(o.optJSONArray("gpuUse"));
            r.normalizeThrottle();
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONArray ints(int[] values) {
        JSONArray array = new JSONArray();
        if (values != null) for (int v : values) array.put(v);
        return array;
    }

    private static int[] ints(JSONArray array) {
        if (array == null) return new int[0];
        int[] out = new int[array.length()];
        for (int i = 0; i < out.length; i++) out[i] = array.optInt(i, 0);
        return out;
    }
}
