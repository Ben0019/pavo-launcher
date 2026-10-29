package com.ben.pavo;

import android.net.TrafficStats;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** Reads CPU load from /proc/stat and device network throughput from TrafficStats. */
final class SysStats {
    private SysStats() {}

    private static long lastBusy = -1;
    private static long lastTotal = -1;
    private static long lastBytes = -1;

    static long[] readCpu() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu")) return null;
            String[] parts = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < parts.length; i++) {
                total += Long.parseLong(parts[i]);
            }
            long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0);
            return new long[]{total - idle, total};
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return CPU usage percent over the last sample, or -1 if unavailable. */
    static float cpuPercent() {
        long[] cur = readCpu();
        if (cur == null) return -1;
        float result = -1;
        if (lastBusy >= 0) {
            long dBusy = cur[0] - lastBusy;
            long dTotal = cur[1] - lastTotal;
            if (dTotal > 0) result = Math.max(0f, Math.min(100f, dBusy * 100f / dTotal));
        }
        lastBusy = cur[0];
        lastTotal = cur[1];
        return result;
    }

    /** @return bytes/sec since the last sample, or -1 if unavailable. */
    static long netBytesPerSec(long dtMillis) {
        long total;
        try {
            total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        } catch (Throwable t) {
            return -1;
        }
        long result = -1;
        if (lastBytes >= 0 && total >= lastBytes && dtMillis > 0) {
            result = (total - lastBytes) * 1000 / dtMillis;
        }
        lastBytes = total;
        return result;
    }

    static String fmtRate(long bps) {
        if (bps < 0) return "--";
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format(Locale.US, "%.0f KB/s", bps / 1024f);
        return String.format(Locale.US, "%.1f MB/s", bps / (1024f * 1024f));
    }
}
