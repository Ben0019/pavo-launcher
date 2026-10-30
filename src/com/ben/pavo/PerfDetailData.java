package com.ben.pavo;

/** Snapshot for the detailed performance monitor screen. */
final class PerfDetailData {
    String appLabel;
    String pkg;
    String cls;

    int fps;
    boolean fpsValid;
    int avgFps;
    int minFps;
    int maxFps;
    int[] history = new int[0];

    float cpuTempC;
    float gpuTempC;
    int cpuUsage;
    int gpuUsage;
    boolean cpuUsageKnown = true;
    boolean gpuUsageKnown = true;
    long cpuHz;
    long cpuMaxHz;
    long gpuHz;
    long gpuMaxHz;

    float powerW;
    float peakPowerW;
    String throttle = "NONE";

    long runtimeMs;
    int battery;
    int batteryStart;
    float batteryUsedPct;
    float batteryUsedMah;

    long appRamKb;
    long memTotalKb;
    long memAvailKb;
    int memPercent;
}
