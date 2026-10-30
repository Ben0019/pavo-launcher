package com.ben.pavo;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads the device's performance counters directly from sysfs/procfs, and asks the
 * PServer daemon to dump the foreground layer's SurfaceFlinger frame timestamps for
 * the FPS meter. No root shell is needed on AYN/Retroid firmware.
 */
final class PerfSampler {
    private static final long FPS_WINDOW_NS = 600_000_000L;
    /** Frames older than this mean the resolved surface is no longer drawing. */
    private static final long FPS_STALE_NS = 3_500_000_000L;
    private static final long PSERVER_OFF_NS = 30_000_000_000L;
    /**
     * Runs on the device's PServer daemon (the same service ClusterTune and
     * PULSE use), so the FPS meter works on AYN/Retroid handhelds without a
     * user-granted root shell. The daemon is fire-and-forget, so the script
     * writes its output to a file in our cache dir and we poll for it.
     */
    private static final String PSERVER_SCRIPT =
            "out=\"$1\"; tok=\"$2\"; cached=\"$3\"\n"
            + "tmp=\"$out.$tok.tmp\"\n"
            + "top=$(dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity)\n"
            + "comp=$(printf '%s\\n' \"$top\" | grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | head -n1)\n"
            + "pkg=${comp%%/*}; cls=${comp#*/}\n"
            + "case \"$cls\" in .*) cls=\"$pkg$cls\";; esac\n"
            + "full=\"$pkg/$cls\"\n"
            + "{\n"
            + "echo \"TOKEN:$tok\"\n"
            + "echo \"APP:$full\"\n"
            + "pid=$(pidof \"$pkg\" 2>/dev/null | awk '{print $1}')\n"
            + "if [ -n \"$pid\" ]; then rss=$(grep VmRSS /proc/$pid/status 2>/dev/null | awk '{print $2}'); [ -n \"$rss\" ] && echo \"RSS:$rss\"; fi\n"
            + "best=\"$cached\"; bestn=0; bestout=\n"
            + "case \"$best\" in *\"$pkg\"*) ;; *) best=;; esac\n"
            + "if [ -n \"$best\" ]; then\n"
            + "  bestout=$(dumpsys SurfaceFlinger --latency \"$best\" 2>/dev/null)\n"
            + "  bestn=$(printf '%s\\n' \"$bestout\" | awk 'NR>1 && $2+0>0 {c++} END {print c+0}')\n"
            + "fi\n"
            + "if [ -z \"$best\" ] || [ \"$bestn\" -eq 0 ]; then\n"
            + "  cands=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"$full\" "
            + "| grep -vE 'ActivityRecordInputSink|ActivityRecord|Splash Screen|Background for|animation-leash|Bounds for|Pavo performance overlay|ClusterTune|Cluster Tune' "
            + "| sed -E 's/^RequestedLayerState\\{([0-9a-f]+ )?//; s/ parentId=.*$//' | head -n 4)\n"
            + "  bestn=0; bestNewest=0; best=; bestout=\n"
            + "  while IFS= read -r cand; do\n"
            + "    [ -z \"$cand\" ] && continue\n"
            + "    o=$(dumpsys SurfaceFlinger --latency \"$cand\" 2>/dev/null)\n"
            + "    n=$(printf '%s\\n' \"$o\" | awk 'NR>1 && $2+0>0 {c++} END {print c+0}')\n"
            + "    nw=$(printf '%s\\n' \"$o\" | awk 'NR>1 && $2+0>0 {if ($2+0>m) m=$2+0; c++} END {printf \"%.0f\\n\", m}')\n"
            + "    better=0;\n"
            + "    if [ -z \"$best\" ]; then better=1;\n"
            + "    elif [ \"$n\" -gt \"$bestn\" ]; then better=1;\n"
            + "    elif [ \"$n\" -eq \"$bestn\" ] && awk -v a=\"${nw:-0}\" -v b=\"$bestNewest\" 'BEGIN{exit !(a>b)}'; then better=1; fi\n"
            + "    if [ \"$better\" -eq 1 ]; then bestNewest=$nw; bestn=$n; best=$cand; bestout=$o; fi\n"
            + "  done <<EOF\n$cands\nEOF\n"
            + "fi\n"
            + "if [ -n \"$best\" ] && [ \"$bestn\" -gt 0 ]; then echo \"LAYER:$best\"; printf '%s\\n' \"$bestout\"; else echo \"LAYER:\"; fi\n"
            + "} > \"$tmp\" 2>/dev/null\n"
            + "chmod 644 \"$tmp\"\n"
            + "mv \"$tmp\" \"$out\"\n"
            + "cp \"$out\" /data/local/tmp/aurora-fps-last.txt 2>/dev/null\n";

    /** Privileged snapshot used when SELinux hides the counters from apps. */
    private static final String PROC_SCRIPT =
            "out=\"$1\"; tmp=\"$out.tmp\"\n"
            + "{\n"
            + "  echo '##STAT'\n"
            + "  cat /proc/stat 2>/dev/null\n"
            + "  echo '##GPUCLK'\n"
            + "  cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null"
            + " || cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null\n"
            + "  echo '##GPUMAX'\n"
            + "  cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null\n"
            + "  echo '##GPUPCT'\n"
            + "  cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null\n"
            + "  echo '##GPUBUSY'\n"
            + "  cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null\n"
            + "  echo '##END'\n"
            + "} > \"$tmp\" 2>/dev/null\n"
            + "chmod 644 \"$tmp\" 2>/dev/null\n"
            + "mv \"$tmp\" \"$out\" 2>/dev/null\n";

    private static File procScript;
    private static long procKickNs;
    private static int procFailures;
    private String rootText;
    private final long[] prevIdleUs = new long[PerfStats.MAX_CORES];
    private final long[] prevIdleAt = new long[PerfStats.MAX_CORES];

    private String rootSnapshot(Context context, long now) {
        // Stock SELinux forbids pservice from writing into app data, so this is
        // only useful on permissive/rooted units. Stop retrying after two misses.
        if (procFailures >= 2) return null;
        if (context == null || !PServer.available()) return null;
        File cache = context.getCacheDir();
        if (cache == null) return null;
        try {
            if (procScript == null || !procScript.exists()) {
                File script = new File(cache, "aurora-proc.sh");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(script);
                fos.write(PROC_SCRIPT.getBytes("UTF-8"));
                fos.close();
                procScript = script;
            }
            File out = new File(cache, "aurora-proc.txt");
            String text = readAllText(out);
            if (text != null) {
                procFailures = 0;
                return text;
            }
            if (procKickNs != 0 && now - procKickNs > 1_500_000_000L) {
                // The helper ran but nothing landed in app data: give up.
                procFailures++;
                procKickNs = 0;
                return null;
            }
            if (procKickNs == 0) {
                procKickNs = now;
                PServer.run("sh " + procScript.getAbsolutePath()
                        + " " + out.getAbsolutePath());
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String section(String text, String name) {
        if (text == null) return null;
        String start = "##" + name + "\n";
        int a = text.indexOf(start);
        if (a < 0) return null;
        a += start.length();
        int b = text.indexOf("\n##", a);
        return b < 0 ? text.substring(a) : text.substring(a, b);
    }

    private static File pserverScript;
    private static long pserverOffUntil;
    private static String pserverToken;
    private static long pserverStartedNs;

    private final int coreCount;
    private final long[] prevTotal = new long[PerfStats.MAX_CORES];
    private final long[] prevIdle = new long[PerfStats.MAX_CORES];
    private long prevCpuTotal;
    private long prevCpuIdle;
    private long prevGpuBusy;
    private long prevGpuTotal;
    private String cachedLayer;
    private long noLayerUntil;
    private final long[] fpsRecent = new long[3];
    private int fpsRecentCount;
    private int fpsRecentIndex;
    private String fpsRecentLayer;
    private String cachedTargetPkg;
    private String cachedTargetCls;
    private long cachedAppRamKb;

    PerfSampler() {
        int count = 0;
        while (count < PerfStats.MAX_CORES
                && new File("/sys/devices/system/cpu/cpu" + count).exists()) {
            count++;
        }
        coreCount = Math.max(1, count);
    }

    PerfStats sample(Context context, boolean wantFps) {
        PerfStats s = new PerfStats();
        s.coreCount = coreCount;
        readCpu(s, context);
        readGpu(s, context);
        readMemory(s);
        readBattery(context, s);
        if (wantFps) readFps(s, context);
        s.thermalHot = s.cpuTempC >= 75f || s.gpuTempC >= 75f;
        if (s.thermalHot) s.throttle = "THERMAL";
        else if (s.cpuCapped) s.throttle = "CPU CAP";
        else if (s.gpuCapped) s.throttle = "GPU CAP";
        else s.throttle = "NONE";
        return s;
    }

    private void readCpu(PerfStats s, Context context) {
        long maxCur = 0;
        long maxCap = 0;
        int online = 0;
        for (int i = 0; i < s.coreCount; i++) {
            File base = new File("/sys/devices/system/cpu/cpu" + i);
            boolean isOnline = i == 0 || readInt(new File(base, "online"), 1) != 0;
            s.coreOnline[i] = isOnline;
            long cap = readLong(new File(base, "cpufreq/cpuinfo_max_freq")) * 1000L;
            long cur = readLong(new File(base, "cpufreq/scaling_cur_freq")) * 1000L;
            long policyMax = readLong(new File(base, "cpufreq/scaling_max_freq")) * 1000L;
            if (isOnline && cap > 0 && policyMax > 0 && policyMax < cap * 0.92f) {
                s.cpuCapped = true;
            }
            s.coreMaxHz[i] = cap;
            s.coreHz[i] = cur;
            if (isOnline) {
                online++;
                if (cur > maxCur) maxCur = cur;
                if (cap > maxCap) maxCap = cap;
            }
        }
        s.cpuHz = maxCur;
        s.cpuMaxHz = maxCap;

        // Stock firmware denies untrusted apps /proc/stat (SELinux proc_stat),
        // which used to leave CPU and core load stuck at 0%. Fall back to a
        // privileged snapshot; it lags one refresh, which is invisible here.
        rootText = null;
        String stat = readAllText(new File("/proc/stat"));
        boolean haveStat = stat != null && stat.contains("cpu0");

        float sumUsage = 0f;
        int usageCount = 0;
        long aggregateTotal = 0;
        long aggregateIdle = 0;
        boolean haveAggregate = false;
        if (haveStat) {
            for (String line : stat.split("\n")) {
                if (!line.startsWith("cpu")) break;
                String[] p = line.trim().split("\\s+");
                if (p.length < 5) continue;
                if (line.startsWith("cpu ")) {
                    long total = 0;
                    int fields = Math.min(8, p.length - 1);
                    for (int i = 1; i <= fields; i++) total += parseLong(p[i]);
                    aggregateTotal = total;
                    aggregateIdle = parseLong(p[4])
                            + (p.length > 5 ? parseLong(p[5]) : 0);
                    haveAggregate = true;
                    continue;
                }
                int index;
                try {
                    index = Integer.parseInt(p[0].substring(3));
                } catch (Throwable ignored) {
                    continue;
                }
                if (index < 0 || index >= s.coreCount) continue;
                long total = 0;
                int fields = Math.min(8, p.length - 1);
                for (int i = 1; i <= fields; i++) total += parseLong(p[i]);
                long idle = parseLong(p[4]) + (p.length > 5 ? parseLong(p[5]) : 0);
                if (prevTotal[index] == 0) {
                    s.coreUsage[index] = 0f;
                } else {
                    long dt = total - prevTotal[index];
                    long di = idle - prevIdle[index];
                    s.coreUsage[index] = dt > 0
                            ? clamp((dt - di) * 100f / dt, 0f, 100f) : 0f;
                }
                prevTotal[index] = total;
                prevIdle[index] = idle;
                if (s.coreOnline[index]) {
                    sumUsage += s.coreUsage[index];
                    usageCount++;
                }
            }
        }

        s.cpuTempC = ThermalSensors.cpuTemp();
        if (!haveStat) {
            // No /proc/stat: derive load from per-core idle residency, which
            // stock firmware still exposes to apps.
            if (readUsageFromIdle(s)) {
                return;
            }
            rootText = rootSnapshot(context, System.nanoTime());
            if (rootText != null) {
                stat = section(rootText, "STAT");
                haveStat = stat != null && stat.contains("cpu0");
            }
            if (!haveStat) {
                s.cpuUsage = 0;
                s.cpuUsageKnown = false;
                s.coreStatsKnown = false;
                return;
            }
        }
        s.cpuUsageKnown = true;
        s.coreStatsKnown = true;
        if (haveAggregate && prevCpuTotal > 0) {
            long dt = aggregateTotal - prevCpuTotal;
            long di = aggregateIdle - prevCpuIdle;
            s.cpuUsage = dt > 0
                    ? Math.round(clamp((dt - di) * 100f / dt, 0f, 100f)) : 0;
        } else {
            s.cpuUsage = usageCount > 0 ? Math.round(sumUsage / usageCount) : 0;
        }
        if (haveAggregate) {
            prevCpuTotal = aggregateTotal;
            prevCpuIdle = aggregateIdle;
        }
    }

    /**
     * CPU load from cpuidle residency: a core is busy whenever it is not in an
     * idle state. Works on firmwares that deny /proc/stat to untrusted apps.
     */
    private boolean readUsageFromIdle(PerfStats s) {
        long now = android.os.SystemClock.elapsedRealtime();
        int measured = 0;
        float sum = 0f;
        for (int i = 0; i < s.coreCount; i++) {
            if (!s.coreOnline[i]) continue;
            long idleUs = idleTimeUs(i);
            if (idleUs < 0) {
                prevIdleAt[i] = 0;
                continue;
            }
            long at = prevIdleAt[i];
            long before = prevIdleUs[i];
            prevIdleUs[i] = idleUs;
            prevIdleAt[i] = now;
            if (at <= 0 || now <= at) continue;
            double elapsedUs = (now - at) * 1000.0;
            double idle = idleUs - before;
            if (elapsedUs <= 0 || idle < 0) continue;
            s.coreUsage[i] = clamp((float) (100.0 * (1.0 - idle / elapsedUs)), 0f, 100f);
            sum += s.coreUsage[i];
            measured++;
        }
        if (measured == 0) return false;
        s.cpuUsage = Math.round(sum / measured);
        s.cpuUsageKnown = true;
        s.coreStatsKnown = true;
        return true;
    }

    /** Total time (microseconds) each core has spent in idle states, or -1. */
    private static long idleTimeUs(int core) {
        String base = "/sys/devices/system/cpu/cpu" + core + "/cpuidle";
        long total = 0;
        boolean found = false;
        for (int k = 0; k < 4; k++) {
            String text = readText(new File(base, "state" + k + "/time"));
            if (text == null) continue;
            long v = parseLong(text.trim());
            if (v < 0) continue;
            total += v;
            found = true;
        }
        return found ? total : -1;
    }

    private void readGpu(PerfStats s, Context context) {
        DeviceProfile profile = DeviceProfile.get(null);
        File kgsl = profile.kgsl && profile.gpuPath != null && !profile.gpuPath.isEmpty()
                ? new File(profile.gpuPath) : new File("/sys/class/kgsl/kgsl-3d0");
        long hz = readLong(new File(kgsl, "devfreq/cur_freq"));
        if (hz <= 0) hz = readLong(new File(kgsl, "gpuclk"));
        long max = readLong(new File(kgsl, "max_gpuclk"));
        int busy = readPercentValue(new File(kgsl, "gpu_busy_percentage"));
        // Some Odin kernels expose only the vendor interface; it reports a plain
        // percentage.
        int vendorBusy = readPercentValue(new File("/sys/kernel/gpu/gpu_busy"));
        if (vendorBusy > busy) busy = vendorBusy;
        // kgsl's gpubusy holds per-window busy/total cycles, not counters that
        // grow forever. Some kernels report 0% in gpu_busy_percentage while the
        // ratio is still meaningful, so keep the larger of the two.
        int ratio = -1;
        String counters = readText(new File(kgsl, "gpubusy"));
        if (counters != null) {
            String[] p = counters.trim().split("\\s+");
            if (p.length >= 2) {
                long busyCount = parseLong(p[0]);
                long totalCount = parseLong(p[1]);
                if (totalCount > 0 && busyCount >= 0 && busyCount <= totalCount) {
                    ratio = (int) Math.round(clamp(busyCount * 100f / totalCount, 0f, 100f));
                }
            }
        }
        if (ratio > busy) busy = ratio;

        if (busy < 0 || hz <= 0 || max <= 0) {
            if (rootText == null) rootText = rootSnapshot(context, System.nanoTime());
            if (rootText != null) {
                if (busy < 0) {
                    String pct = section(rootText, "GPUPCT");
                    if (pct != null && !pct.trim().isEmpty()) {
                        long v = parseLong(pct.trim());
                        if (v >= 0) busy = (int) Math.round(clamp(v, 0f, 100f));
                    }
                }
                if (busy < 0) {
                    String raw = section(rootText, "GPUBUSY");
                    if (raw != null) {
                        String[] p = raw.trim().split("\\s+");
                        if (p.length >= 2) {
                            long busyCount = parseLong(p[0]);
                            long totalCount = parseLong(p[1]);
                            if (totalCount > 0 && busyCount >= 0 && busyCount <= totalCount) {
                                busy = (int) Math.round(
                                        clamp(busyCount * 100f / totalCount, 0f, 100f));
                            }
                        }
                    }
                }
                if (hz <= 0) {
                    String clock = section(rootText, "GPUCLK");
                    if (clock != null) hz = parseLong(clock.trim());
                }
                if (max <= 0) {
                    String cap = section(rootText, "GPUMAX");
                    if (cap != null) max = parseLong(cap.trim());
                }
            }
        }

        if (hz <= 0 || max <= 0) {
            long cur = readLong(new File(kgsl, "devfreq/cur_freq"));
            long cap = readLong(new File(kgsl, "devfreq/max_freq"));
            if (cur > 0) hz = cur;
            if (cap > 0) max = cap;
        }
        if (hz <= 0 || max <= 0) {
            File devfreq = new File("/sys/class/devfreq");
            File[] dirs = devfreq.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    String name = readText(new File(d, "name"));
                    if (name == null) continue;
                    String n = (name + " " + d.getName()).toLowerCase(Locale.US);
                    if (!n.contains("kgsl") && !n.contains("gpu")
                            && !n.contains("mali") && !n.contains("3d")) continue;
                    long cur = readLong(new File(d, "cur_freq"));
                    long cap = readLong(new File(d, "max_freq"));
                    if (cur > 0) hz = cur;
                    if (cap > 0) max = cap;
                    break;
                }
            }
        }

        long hardwareMax = 0;
        String freqs = readText(new File(kgsl, "gpu_available_frequencies"));
        if (freqs != null) {
            for (String token : freqs.split("\\s+")) {
                try {
                    hardwareMax = Math.max(hardwareMax, Long.parseLong(token));
                } catch (Throwable ignored) {
                }
            }
        }
        if (hardwareMax > 0 && max > 0 && max < hardwareMax * 0.92f) {
            s.gpuCapped = true;
        }

        s.gpuHz = Math.max(0, hz);
        s.gpuMaxHz = Math.max(0, max);
        s.gpuUsage = busy;
        s.gpuUsageKnown = busy >= 0;
        s.gpuTempC = ThermalSensors.gpuTemp();
    }

    private void readMemory(PerfStats s) {
        long total = 0;
        long avail = 0;
        long free = 0;
        long buffers = 0;
        long cached = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/meminfo")))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                long value = 0;
                try {
                    value = Long.parseLong(p[1]);
                } catch (Throwable ignored) {
                }
                if ("MemTotal:".equals(p[0])) total = value;
                else if ("MemAvailable:".equals(p[0])) avail = value;
                else if ("MemFree:".equals(p[0])) free = value;
                else if ("Buffers:".equals(p[0])) buffers = value;
                else if ("Cached:".equals(p[0])) cached = value;
            }
        } catch (Throwable ignored) {
        }
        if (avail <= 0) avail = free + buffers + cached;
        s.memTotalKb = total;
        s.memAvailKb = avail;
        s.memPercent = total > 0
                ? (int) Math.max(0, Math.min(100, Math.round((total - avail) * 100f / total)))
                : 0;
    }

    private void readBattery(Context context, PerfStats s) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int pct = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (pct < 0) pct = readInt(new File("/sys/class/power_supply/battery/capacity"), -1);
            s.battery = Math.max(0, Math.min(100, pct));

            long currentUa = bm == null ? 0
                    : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (currentUa == 0 || currentUa == Integer.MIN_VALUE) {
                currentUa = readLong(new File("/sys/class/power_supply/battery/current_now"));
            }
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            long voltageUv = readLong(new File("/sys/class/power_supply/battery/voltage_now"));
            if (voltageUv <= 0) {
                int mv = battery == null ? -1
                        : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                voltageUv = mv * 1000L;
            }
            // Battery temperature: sysfs reports tenths of a degree.
            long tenths = readLong(new File("/sys/class/power_supply/battery/temp"));
            if (tenths <= 0 && battery != null) {
                tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            }
            if (tenths > 0) s.batteryTempC = tenths / 10f;
            if (voltageUv > 0 && currentUa != 0 && currentUa != Integer.MIN_VALUE) {
                s.powerW = Math.abs(currentUa) / 1_000_000f * (voltageUv / 1_000_000f);
            }
            if (bm != null) {
                s.chargeCounterUah = bm.getLongProperty(
                        BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            }
        } catch (Throwable ignored) {
            s.battery = -1;
        }
    }

    private void readFps(PerfStats s, Context context) {
        long now = System.nanoTime();
        if (cachedLayer == null && now < noLayerUntil) return;
        readFpsViaPServer(s, context, now);
    }

    private static boolean pserverActive(Context context, long now) {
        if (context == null || now < pserverOffUntil) return false;
        return PServer.available();
    }

    /**
     * PServer is fire-and-forget: each sample kicks off a script run and the
     * next sample picks the file up. Nothing blocks the sampler thread, and a
     * slow layer scan on a SurfaceView app still resolves within a tick or two.
     */
    private void readFpsViaPServer(PerfStats s, Context context, long now) {
        File cache = context.getCacheDir();
        if (cache == null) return;
        if (pserverScript == null || !pserverScript.exists()) {
            File script = new File(cache, "aurora-fps.sh");
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(script);
                fos.write(PSERVER_SCRIPT.getBytes("UTF-8"));
                fos.close();
                pserverScript = script;
            } catch (Throwable t) {
                pserverOffUntil = now + PSERVER_OFF_NS;
                return;
            }
        }
        File out = new File(cache, "aurora-fps.txt");
        boolean consumed = false;
        if (pserverToken != null) {
            String text = readAllText(out);
            if (text != null && text.contains("TOKEN:" + pserverToken)) {
                pserverToken = null;
                try {
                    out.delete();
                } catch (Throwable ignored) {
                }
                parseFpsLatency(s, text, true, now, FPS_STALE_NS);
                consumed = true;
            } else if (now - pserverStartedNs > 4_000_000_000L) {
                // The daemon never came back with this run; try again next tick
                // instead of disabling the meter for a minute.
                pserverToken = null;
            }
        }
        // Keep the pipeline full: a run started right after a sample is read
        // on the next tick, so the overlay still refreshes once per sample.
        if (pserverToken == null) {
            String token = Long.toString(now);
            String layerArg = cachedLayer == null ? ""
                    : "'" + cachedLayer.replace("'", "'\\''") + "'";
            try {
                out.delete();
            } catch (Throwable ignored) {
            }
            if (!PServer.run("sh " + pserverScript.getAbsolutePath() + " "
                    + out.getAbsolutePath() + " " + token + " " + layerArg)) {
                pserverOffUntil = now + PSERVER_OFF_NS;
                return;
            }
            pserverToken = token;
            pserverStartedNs = now;
            if (cachedLayer != null) {
                // Fast path: a cached layer only needs one short dumpsys, so
                // wait briefly and update this sample instead of the next one.
                long deadline = now + 260_000_000L;
                while (System.nanoTime() < deadline) {
                    String text = readAllText(out);
                    if (text != null && text.contains("TOKEN:" + token)) {
                        pserverToken = null;
                        try {
                            out.delete();
                        } catch (Throwable ignored) {
                        }
                        parseFpsLatency(s, text, true, System.nanoTime(), FPS_STALE_NS);
                        return;
                    }
                    try {
                        Thread.sleep(15L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        if (consumed) return;
    }

    private void parseFpsLatency(PerfStats s, String out, boolean resolved, long now,
                                 long staleLimit) {
        boolean layerFound = !resolved;
        long newest = 0;
        long refreshNs = 0;
        String layer = null;
        List<Long> times = new ArrayList<>();
        String[] lines = out.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("APP:")) {
                String comp = line.substring("APP:".length()).trim();
                int slash = comp.indexOf('/');
                if (slash > 0) {
                    cachedTargetPkg = comp.substring(0, slash);
                    cachedTargetCls = comp.substring(slash + 1);
                }
                continue;
            }
            if (line.startsWith("RSS:")) {
                try {
                    cachedAppRamKb = Long.parseLong(line.substring("RSS:".length()).trim());
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (line.startsWith("LAYER:")) {
                layer = line.substring("LAYER:".length()).trim();
                layerFound = !layer.isEmpty();
                continue;
            }
            if (!layerFound) continue;
            String[] p = line.split("\\s+");
            if (p.length == 1) {
                // Header line of --latency: the panel's current vsync period.
                try {
                    long v = Long.parseLong(p[0]);
                    if (v >= 1_000_000L && v <= 40_000_000L) refreshNs = v;
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (p.length < 3) continue;
            long actual;
            try {
                actual = Long.parseLong(p[1]);
            } catch (Throwable ignored) {
                continue;
            }
            if (actual <= 0 || actual == Long.MAX_VALUE) continue;
            // Some drivers repeat the same present time for queued frames;
            // counting duplicates inflates the rate.
            if (!times.isEmpty() && actual <= times.get(times.size() - 1)) continue;
            times.add(actual);
            if (actual > newest) newest = actual;
        }
        if (resolved) {
            cachedLayer = layerFound ? layer : null;
            if (!layerFound) noLayerUntil = now + 500_000_000L;
        }
        if (!layerFound || times.isEmpty() || newest <= 0) {
            // An empty dump means the layer went away; resolve again instead of
            // pinning a dead surface for the rest of the session.
            cachedLayer = null;
            noLayerUntil = now + 400_000_000L;
            return;
        }
        s.targetPkg = cachedTargetPkg;
        s.targetCls = cachedTargetCls;
        s.appRamKb = cachedAppRamKb;
        long age = now - newest;
        if (age > staleLimit) {
            // A stale layer is often the wrong SurfaceView or a just-created one.
            // Re-resolve, but keep the last reading on screen instead of
            // flashing a zero for one frame.
            cachedLayer = null;
            noLayerUntil = now + 400_000_000L;
            return;
        }
        int count = 0;
        long oldest = newest;
        long from = newest - FPS_WINDOW_NS;
        for (long t : times) {
            if (t >= from) {
                count++;
                if (t < oldest) oldest = t;
            }
        }
        long raw;
        if (count > 1 && newest > oldest) {
            raw = Math.round((count - 1) * 1_000_000_000.0 / (newest - oldest));
        } else {
            raw = count;
        }
        raw = Math.round(raw);
        // Never report more frames than the panel can show.
        long cap = refreshNs > 0 ? Math.round(1_000_000_000.0 / refreshNs * 1.10) : 240;
        raw = Math.max(0, Math.min(Math.max(1, cap), raw));
        if (fpsRecentLayer == null || !fpsRecentLayer.equals(layer)) {
            fpsRecentLayer = layer;
            fpsRecentCount = 0;
            fpsRecentIndex = 0;
        }
        fpsRecent[fpsRecentIndex] = raw;
        fpsRecentIndex = (fpsRecentIndex + 1) % fpsRecent.length;
        if (fpsRecentCount < fpsRecent.length) fpsRecentCount++;
        long[] sorted = new long[fpsRecentCount];
        System.arraycopy(fpsRecent, 0, sorted, 0, fpsRecentCount);
        java.util.Arrays.sort(sorted);
        s.fps = (int) sorted[sorted.length / 2];
        s.fpsValid = true;
    }

    private static long readLong(File f) {
        String s = readText(f);
        if (s == null) return 0;
        try {
            int end = 0;
            while (end < s.length()
                    && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) {
                end++;
            }
            if (end == 0) return 0;
            return Long.parseLong(s.substring(0, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int readInt(File f, int fallback) {
        long v = readLong(f);
        return v == 0 && !f.exists() ? fallback : (int) v;
    }

    private static int readPercent(File f) {
        long v = readLong(f);
        return (int) Math.max(0, Math.min(100, v));
    }

    /** Like readPercent, but keeps "no such node" distinct from a real 0%. */
    private static int readPercentValue(File f) {
        String s = readText(f);
        if (s == null || s.trim().isEmpty()) return -1;
        return readPercent(f);
    }

    /** Full read for the latency dump; readText() only samples sysfs-sized files. */
    private static String readAllText(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] data = new byte[64 * 1024];
            int n = in.read(data);
            if (n <= 0) return null;
            return new String(data, 0, n);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[256];
            int n = in.read(data);
            if (n <= 0) return null;
            return new String(data, 0, n).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
