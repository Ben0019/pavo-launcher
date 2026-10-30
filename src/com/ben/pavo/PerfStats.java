package com.ben.pavo;

/** Snapshot of the metrics shown by the performance overlay. */
final class PerfStats {
    static final int MAX_CORES = 8;

    float cpuTempC;
    float gpuTempC;
    long cpuHz;
    long cpuMaxHz;
    long gpuHz;
    long gpuMaxHz;
    int cpuUsage;
    int gpuUsage;
    int battery;
    long memTotalKb;
    long memAvailKb;
    int memPercent;
    int fps;
    boolean fpsValid;
    int coreCount = MAX_CORES;

    String targetPkg;
    String targetCls;
    long appRamKb;
    float powerW;
    long chargeCounterUah;
    boolean cpuCapped;
    boolean gpuCapped;
    /** False when the kernel hides the counters (stock SELinux blocks /proc/stat). */
    boolean cpuUsageKnown;
    boolean gpuUsageKnown;
    boolean coreStatsKnown;
    boolean thermalHot;
    String throttle = "NONE";

    final float[] coreUsage = new float[MAX_CORES];
    final long[] coreHz = new long[MAX_CORES];
    final long[] coreMaxHz = new long[MAX_CORES];
    final boolean[] coreOnline = new boolean[MAX_CORES];
}
