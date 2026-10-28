package com.ben.aurora;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One saved performance session for an app. */
final class PerfLogEntry {
    long id;
    String appLabel = "";
    String pkg = "";
    String cls = "";
    long startMs;
    long endMs;
    long durationMs;

    int samples;
    int fpsSamples;
    long fpsSum;
    int avgFps;
    int minFps;
    int maxFps;
    final List<Integer> history = new ArrayList<>();
    final List<Integer> cpuTempHistory = new ArrayList<>();
    final List<Integer> gpuTempHistory = new ArrayList<>();
    final List<Integer> cpuUsageHistory = new ArrayList<>();
    final List<Integer> gpuUsageHistory = new ArrayList<>();

    float powerSum;
    int powerSamples;
    float peakPowerW;
    float avgPowerW;
    float maxCpuTemp;
    float maxGpuTemp;
    long cpuUsageSum;
    long gpuUsageSum;
    int usageSamples;
    int avgCpuUsage;
    int avgGpuUsage;
    String throttle = "NONE";

    int batteryStart = -1;
    int batteryEnd = -1;
    float batteryUsedPct;
    float batteryUsedMah;
    long appRamKb;
    int memPercent;
    long chargeStartUah = -1;

    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", appLabel);
            o.put("pkg", pkg);
            o.put("cls", cls);
            o.put("start", startMs);
            o.put("end", endMs);
            o.put("duration", durationMs);
            o.put("samples", samples);
            o.put("fpsSamples", fpsSamples);
            o.put("avgFps", avgFps);
            o.put("minFps", minFps);
            o.put("maxFps", maxFps);
            JSONArray h = new JSONArray();
            for (Integer v : history) h.put(v);
            o.put("history", h);
            JSONArray ch = new JSONArray();
            for (Integer v : cpuTempHistory) ch.put(v);
            o.put("cpuHistory", ch);
            JSONArray gh = new JSONArray();
            for (Integer v : gpuTempHistory) gh.put(v);
            o.put("gpuHistory", gh);
            JSONArray cu = new JSONArray();
            for (Integer v : cpuUsageHistory) cu.put(v);
            o.put("cpuUsageHistory", cu);
            JSONArray gu = new JSONArray();
            for (Integer v : gpuUsageHistory) gu.put(v);
            o.put("gpuUsageHistory", gu);
            o.put("peakPower", peakPowerW);
            o.put("avgPower", avgPowerW);
            o.put("maxCpuTemp", maxCpuTemp);
            o.put("maxGpuTemp", maxGpuTemp);
            o.put("avgCpuUsage", avgCpuUsage);
            o.put("avgGpuUsage", avgGpuUsage);
            o.put("throttle", throttle);
            o.put("batteryStart", batteryStart);
            o.put("batteryEnd", batteryEnd);
            o.put("batteryUsedPct", batteryUsedPct);
            o.put("batteryUsedMah", batteryUsedMah);
            o.put("appRamKb", appRamKb);
            o.put("memPercent", memPercent);
            return o;
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    static PerfLogEntry fromJson(JSONObject o) {
        PerfLogEntry e = new PerfLogEntry();
        e.id = o.optLong("id", 0);
        e.appLabel = o.optString("label", "");
        e.pkg = o.optString("pkg", "");
        e.cls = o.optString("cls", "");
        e.startMs = o.optLong("start", 0);
        e.endMs = o.optLong("end", 0);
        e.durationMs = o.optLong("duration", 0);
        e.samples = o.optInt("samples", 0);
        e.fpsSamples = o.optInt("fpsSamples", 0);
        e.avgFps = o.optInt("avgFps", 0);
        e.minFps = o.optInt("minFps", 0);
        e.maxFps = o.optInt("maxFps", 0);
        JSONArray h = o.optJSONArray("history");
        if (h != null) {
            for (int i = 0; i < h.length(); i++) e.history.add(h.optInt(i, 0));
        }
        JSONArray ch = o.optJSONArray("cpuHistory");
        if (ch != null) {
            for (int i = 0; i < ch.length(); i++) e.cpuTempHistory.add(ch.optInt(i, 0));
        }
        JSONArray gh = o.optJSONArray("gpuHistory");
        if (gh != null) {
            for (int i = 0; i < gh.length(); i++) e.gpuTempHistory.add(gh.optInt(i, 0));
        }
        JSONArray cu = o.optJSONArray("cpuUsageHistory");
        if (cu != null) {
            for (int i = 0; i < cu.length(); i++) e.cpuUsageHistory.add(cu.optInt(i, 0));
        }
        JSONArray gu = o.optJSONArray("gpuUsageHistory");
        if (gu != null) {
            for (int i = 0; i < gu.length(); i++) e.gpuUsageHistory.add(gu.optInt(i, 0));
        }
        e.peakPowerW = (float) o.optDouble("peakPower", 0);
        e.avgPowerW = (float) o.optDouble("avgPower", 0);
        e.maxCpuTemp = (float) o.optDouble("maxCpuTemp", 0);
        e.maxGpuTemp = (float) o.optDouble("maxGpuTemp", 0);
        e.avgCpuUsage = o.optInt("avgCpuUsage", 0);
        e.avgGpuUsage = o.optInt("avgGpuUsage", 0);
        e.throttle = o.optString("throttle", "NONE");
        e.batteryStart = o.optInt("batteryStart", -1);
        e.batteryEnd = o.optInt("batteryEnd", -1);
        e.batteryUsedPct = (float) o.optDouble("batteryUsedPct", 0);
        e.batteryUsedMah = (float) o.optDouble("batteryUsedMah", 0);
        e.appRamKb = o.optLong("appRamKb", 0);
        e.memPercent = o.optInt("memPercent", 0);
        return e;
    }
}
