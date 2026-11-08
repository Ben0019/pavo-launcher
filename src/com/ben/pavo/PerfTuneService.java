package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the user's CPU/GPU clock caps applied while the vendor performance
 * service tries to restore its own values. A thermal guard backs off above
 * 72 C so this never overrides cooling.
 */
public final class PerfTuneService extends Service {

    static final String ACTION_STOP = "com.ben.pavo.perftune.STOP";
    static final String ACTION_RESUME = "com.ben.pavo.perftune.RESUME";
    private static final String CHANNEL = "aurora_performance_hold";
    private static final int NOTIFICATION_ID = 0x4174756E;
    /** Fallback when the preference cannot be read. */
    private static final float THERMAL_LIMIT_C = 88f;

    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean tickInFlight;
    private boolean thermalHold;
    private boolean modePaused;
    private volatile float lastTempC;
    private int holdP0Max;
    private int holdP6Max;
    private int holdGpuMax;
    private String holdCeilingKey;
    private long lastHoldAdjust;
    private boolean holdActive;
    /** Last static (non-HOLD) target that was written, and when. */
    private String staticKey;
    private long lastStaticApplyAt;
    /** Last applied mode; a static preset is not rewritten on every tick. */
    private volatile boolean lastHoldMode;
    private PerfTuner.State cachedState;
    private long cachedStateAt;

    /**
     * The Odin firmware parks cores 6-7 for apps it does not classify as games:
     * with twenty runnable threads the six efficiency cores sit at 100% while
     * the big cluster stays at exactly 0%. A brief WALT boost migrates the work
     * onto the big cluster and it stays there while the load lasts.
     */
    private static final long KICK_COOLDOWN_MS = 8000L;
    private static final long BOOST_PULSE_MS = 1500L;
    private static final float CORE_SATURATED = 90f;
    private static final int SATURATED_MID_CORES = 4;
    private static final float BIG_IDLE = 12f;

    private final long[] prevCoreTotal = new long[8];
    private final long[] prevCoreIdle = new long[8];
    private boolean coreBaseline;
    private long lastKickAt;
    private boolean boostActive;
    private long boostAt;

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, PerfTuneService.class);
        ServiceNotifications.start(context, intent);
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, PerfTuneService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
            context.stopService(new Intent(context, PerfTuneService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Satisfy startForegroundService() before any early stop below.
        if (!ServiceNotifications.hosted()) {
            startForeground(NOTIFICATION_ID, notification(false, false));
            ServiceNotifications.hideLater(this, NOTIFICATION_ID);
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            new Prefs(this).setPerfHoldEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RESUME.equals(intent.getAction())) {
            // Take the firmware's current mode as the one to hold for again.
            new Prefs(this).setHoldMode(PerfTuner.currentMode(this));
        }
        Prefs prefs = new Prefs(this);
        if (!prefs.perfHoldEnabled()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // A previous process may have died mid-pulse; never inherit a stuck boost.
        boostActive = false;
        PerfTuner.setSchedBoost(false);
        io.execute(new Runnable() {
            @Override
            public void run() {
                PerfTuner.prepare();
            }
        });
        schedule(350L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        // Never leave WALT boosted if the hold is killed mid-pulse.
        PerfTuner.setSchedBoost(false);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void schedule(long delay) {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, delay);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (tickInFlight) return;
            tickInFlight = true;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final boolean[] result = applyOnce();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            tickInFlight = false;
                            if (!running) return;
                            boolean hot = result[0];
                            boolean paused = result.length > 1 && result[1];
                            if (hot != thermalHold || paused != modePaused) {
                                thermalHold = hot;
                                modePaused = paused;
                                NotificationManager nm = (NotificationManager)
                                        getSystemService(NOTIFICATION_SERVICE);
                                if (nm != null) {
                                    ServiceNotifications.update(PerfTuneService.this, NOTIFICATION_ID,
                                            notification(hot, paused));
                                }
                            }
                            long delay;
                            if (hot || paused) {
                                // Thermal pause only needs a slow re-check.
                                delay = 1000L;
                            } else if (lastHoldMode) {
                                // HOLD tracks the ceiling, so keep the fast
                                // loop that undoes vendor cap lifts.
                                delay = lastTempC >= 45f ? 60L : 150L;
                            } else {
                                // A plain preset is a static cap. Poll twice a
                                // second so a vendor cap lift is undone quickly;
                                // the poll only reads nodes, no shell is spawned.
                                delay = 500L;
                            }
                            schedule(delay);
                        }
                    });
                }
            });
        }
    };

    /** @return {thermalHold} */
    private boolean[] applyOnce() {
        Prefs prefs = new Prefs(this);
        if (!prefs.perfHoldEnabled()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
            return new boolean[]{false, false};
        }
        expireBoost();
        // HOLD and the safety guard use the hottest junction, not the filtered
        // HAL sensor, so the target cannot be crossed by a lagging value.
        float[] temps = PerfTuner.readTemperaturesRaw();
        lastTempC = Math.max(temps[0], temps[1]);
        boolean holdMode = prefs.fanAppMode() == FanActivity.MODE_HOLD;
        // Only step aside when the chip is genuinely hot; the old 72 C limit
        // meant the caps never applied while gaming (junctions run 80-95 C).
        // HOLD is exempt: its own target is the ceiling and it throttles before
        // this safety pause would otherwise stop the governor.
        float limit = prefs.perfHoldLimitC();
        if (limit <= 0f) limit = THERMAL_LIMIT_C;
        if (!holdMode && Math.max(temps[0], temps[1]) >= limit) {
            return new boolean[]{true, false};
        }
        // The firmware's performance mode can be changed outside Pavo (its Quick
        // Settings tile). Anything Pavo was holding then belongs to the old mode,
        // and re-applying it is what made the big cores look disabled: the mode
        // change lifted the caps for a moment and this hold put them straight
        // back. Step aside until Pavo re-applies clocks or the mode returns.
        int mode = PerfTuner.currentMode(this);
        int heldMode = prefs.holdMode();
        if (heldMode < 0) {
            prefs.setHoldMode(mode);
        } else if (mode != heldMode) {
            // Follow the firmware: apply that mode's clocks and hold those. Only
            // if that fails do we step aside (which would otherwise leave the old
            // mode's caps in place and make the big cores look disabled).
            if (!PerfTuner.applyModeClocks(this, mode)) return new boolean[]{false, true};
            // The firmware mode changed; re-assert this preset's caps next.
            staticKey = null;
            prefs = new Prefs(this);
        }
        // Hold whatever profile was applied last: the per-app watcher stores its
        // targets here, so a per-app profile stays pinned even if that service is
        // busy, thermal-holding or restarted.
        TuneConfig cfg = new TuneConfig();
        cfg.p0Max = prefs.tuneP0Max();
        cfg.p0Min = prefs.tuneP0Min();
        cfg.p0Gov = prefs.tuneP0Gov();
        cfg.p6Max = prefs.tuneP6Max();
        cfg.p6Min = prefs.tuneP6Min();
        cfg.p6Gov = prefs.tuneP6Gov();
        cfg.gpuMax = prefs.tuneGpuMax();
        cfg.gpuMin = prefs.tuneGpuMin();
        if (cfg.p0Max <= 0 || cfg.p6Max <= 0 || cfg.gpuMax <= 0) {
            cfg = new TuneStore(this).global();
        }
        if (cfg == null) {
            return new boolean[]{false, false};
        }
        long now = SystemClock.uptimeMillis();
        lastHoldMode = holdMode;
        // HOLD is a thermal ceiling: the fan curve ends at the user's hold
        // point, and the CPU/GPU max steps down until the target is met. It
        // steps back up as the load allows, up to the current preset's caps.
        if (holdMode) {
            PerfTuner.State state = holdState();
            // Anchor the hold at the selected preset's own peak clock. HOLD
            // always starts at, for example, SMALL's 3.07 GHz and steps down
            // from there, whatever lower point a manual tune left behind.
            int ceilP0 = cfg.p0Max;
            int ceilP6 = cfg.p6Max;
            int ceilGpu = cfg.gpuMax;
            if (PerfProfiles.isProfile(cfg.profile)) {
                PerfProfiles.Spec spec = PerfProfiles.get(cfg.profile, state);
                if (spec.p0Max > 0) ceilP0 = spec.p0Max;
                if (spec.p6Max > 0) ceilP6 = spec.p6Max;
                if (spec.gpuMax > 0) ceilGpu = spec.gpuMax;
            }
            String ceil = ceilP0 + ":" + ceilP6 + ":" + ceilGpu
                    + ":" + cfg.p0Min + ":" + cfg.p6Min + ":" + cfg.gpuMin;
            if (!holdActive || !ceil.equals(holdCeilingKey)) {
                holdP0Max = ceilP0;
                holdP6Max = ceilP6;
                holdGpuMax = ceilGpu;
                holdCeilingKey = ceil;
                holdActive = true;
                lastHoldAdjust = 0L;
            }
            float target = prefs.fanHoldTargetC();
            float cpu = temps[0];
            float gpu = temps[1];
            float source = FanCurve.sourceTemp(cpu, gpu, prefs.fanTempSource());
            PerfTuner.Root.ensure();
            if (now - lastHoldAdjust >= 1000L) {
                lastHoldAdjust = now;
                if (source > target + 1f) {
                    // One step per second with a small band around the target.
                    // Two steps at the first spike dumped the whole preset peak
                    // whenever HOLD was set low or the fan ran slowly.
                    if (cpu >= gpu - 2f) {
                        holdP0Max = stepDown(state.efficiency, holdP0Max);
                        holdP6Max = stepDown(state.performance, holdP6Max);
                    }
                    if (gpu >= cpu - 2f) {
                        holdGpuMax = stepDownGpu(state.gpu, holdGpuMax);
                    }
                } else if (source < target - 1f) {
                    // Climb back as soon as the target is met again, instead of
                    // waiting for a five-degree margin that kept clocks low.
                    holdP0Max = stepUp(state.efficiency, holdP0Max, ceilP0);
                    holdP6Max = stepUp(state.performance, holdP6Max, ceilP6);
                    holdGpuMax = stepUpGpu(state.gpu, holdGpuMax, ceilGpu);
                }
            }
            PerfTuner.setCpu(state.efficiency, holdP0Max, cfg.p0Min, cfg.p0Gov);
            PerfTuner.setCpu(state.performance, holdP6Max, cfg.p6Min, cfg.p6Gov);
            PerfTuner.setGpu(state.gpu, holdGpuMax, cfg.gpuMin);
            maybeKickBigCores(prefs);
            return new boolean[]{false, false};
        }
        holdActive = false;
        // A plain preset is a fixed ceiling; the governor does the load
        // scaling. Re-apply only when something changes (or once every few
        // seconds to undo a vendor lift) instead of on every tick: writing
        // clocks continuously spawned pservice shells and kept the CPU awake,
        // so idle never dropped to its low steps.
        String key = cfg.p0Max + ":" + cfg.p0Min + ":" + cfg.p0Gov + ":"
                + cfg.p6Max + ":" + cfg.p6Min + ":" + cfg.p6Gov + ":"
                + cfg.gpuMax + ":" + cfg.gpuMin;
        PerfTuner.State state = holdState();
        // The vendor performance service intermittently lifts the caps back to
        // the stock maximum. Compare the live nodes each tick and only rewrite
        // when they moved, so the cap is held without a shell on every tick.
        boolean drifted = PerfTuner.capsDrifted(state.efficiency,
                cfg.p0Max, cfg.p0Min, cfg.p0Gov)
                || PerfTuner.capsDrifted(state.performance,
                cfg.p6Max, cfg.p6Min, cfg.p6Gov);
        if (!key.equals(staticKey) || drifted || now - lastStaticApplyAt >= 30000L) {
            PerfTuner.Root.ensure();
            // performance_mode is the firmware's fan policy switch: writing a
            // non-zero value makes its daemon set fan_mode=0 (fan off). The
            // tuner owns clocks directly, so leave that setting alone.
            PerfTuner.setCpu(state.efficiency, cfg.p0Max, cfg.p0Min, cfg.p0Gov);
            PerfTuner.setCpu(state.performance, cfg.p6Max, cfg.p6Min, cfg.p6Gov);
            PerfTuner.setGpu(state.gpu, cfg.gpuMax, cfg.gpuMin);
            staticKey = key;
            lastStaticApplyAt = now;
        }
        maybeKickBigCores(prefs);
        return new boolean[]{false, false};
    }

    /** Cached device tables; the frequency lists do not change at runtime. */
    private PerfTuner.State holdState() {
        long now = SystemClock.uptimeMillis();
        if (cachedState == null || now - cachedStateAt >= 5000L) {
            cachedState = PerfTuner.readStateForHold(this);
            cachedStateAt = now;
        }
        return cachedState;
    }

    private static int stepDown(PerfTuner.Cpu c, int current) {
        if (c == null || c.freqs.length == 0) return current;
        int idx = PerfTuner.closestIndex(c.freqs, current);
        return c.freqs[Math.max(0, idx - 1)];
    }

    private static int stepUp(PerfTuner.Cpu c, int current, int ceiling) {
        if (c == null || c.freqs.length == 0) return current;
        int idx = PerfTuner.closestIndex(c.freqs, current);
        int next = c.freqs[Math.min(c.freqs.length - 1, idx + 1)];
        return Math.min(next, ceiling);
    }

    private static int stepDownGpu(PerfTuner.Gpu g, int current) {
        if (g == null || g.freqs.length == 0) return current;
        int idx = PerfTuner.closestIndex(g.freqs, current);
        return g.freqs[Math.max(0, idx - 1)];
    }

    private static int stepUpGpu(PerfTuner.Gpu g, int current, int ceiling) {
        if (g == null || g.freqs.length == 0) return current;
        int idx = PerfTuner.closestIndex(g.freqs, current);
        int next = g.freqs[Math.min(g.freqs.length - 1, idx + 1)];
        return Math.min(next, ceiling);
    }

    /**
     * Watches the per-core counters and unparks the big cluster when the
     * efficiency cores are saturated but cores 6-7 are doing nothing. The boost
     * is only pulsed: once migrated, WALT keeps the work there.
     */
    /**
     * Ends a pulse on the next tick no matter what happened in between, so a
     * thermal pause, a mode change or a blocked io thread cannot leave WALT
     * boosted forever.
     */
    private void expireBoost() {
        if (!boostActive) return;
        long now = SystemClock.uptimeMillis();
        if (now - boostAt < BOOST_PULSE_MS) return;
        PerfTuner.setSchedBoost(false);
        boostActive = false;
        lastKickAt = now;
    }

    private void maybeKickBigCores(Prefs prefs) {
        if (!prefs.bigCoreKick()) {
            if (boostActive) {
                PerfTuner.setSchedBoost(false);
                boostActive = false;
            }
            return;
        }
        if (boostActive) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastKickAt < KICK_COOLDOWN_MS) return;
        String stat = readText("/proc/stat");
        if (stat == null) return;

        long bigTotal = 0, bigIdle = 0;
        boolean valid = true;
        int seen = 0;
        int saturatedMid = 0;
        for (String line : stat.split("\n")) {
            if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue;
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) continue;
            int index;
            try {
                index = Integer.parseInt(p[0].substring(3));
            } catch (Throwable ignored) {
                continue;
            }
            if (index < 0 || index > 7) continue;
            long total = 0;
            int fields = Math.min(8, p.length - 1);
            for (int i = 1; i <= fields; i++) total += parseLong(p[i]);
            long idle = parseLong(p[4]) + (p.length > 5 ? parseLong(p[5]) : 0);
            if (!coreBaseline) {
                prevCoreTotal[index] = total;
                prevCoreIdle[index] = idle;
                continue;
            }
            long dt = total - prevCoreTotal[index];
            long di = idle - prevCoreIdle[index];
            prevCoreTotal[index] = total;
            prevCoreIdle[index] = idle;
            if (dt <= 0) {
                valid = false;
                continue;
            }
            if (index <= 5) {
                if ((dt - di) * 100f / dt >= CORE_SATURATED) saturatedMid++;
            } else {
                bigTotal += dt;
                bigIdle += di;
            }
            seen++;
        }
        coreBaseline = true;
        if (!valid || seen < 8 || bigTotal <= 0) return;

        float bigUse = (bigTotal - bigIdle) * 100f / bigTotal;
        if (saturatedMid < SATURATED_MID_CORES || bigUse > BIG_IDLE) return;

        PerfTuner.setSchedBoost(true);
        boostActive = true;
        boostAt = now;
    }

    private static String readText(String path) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(new File(path));
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private Notification notification(boolean hot, boolean paused) {
        Intent open = new Intent(this, PerformanceTunerActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 20, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, PerfTuneService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 21, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(hot ? Loc.s(this, "Performance hold · thermal guard")
                        : Loc.s(this, "Performance hold active"))
                .setContentText(hot
                        ? Loc.s(this, "Letting the Odin cool down; stock limits restored.")
                        : Loc.s(this, "Maintaining your CPU and GPU clock caps."))
                .setContentIntent(content)
                .setOngoing(true).setGroup("pavo_services")
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                                this, R.mipmap.ic_launcher),
                        "Stop hold", stopIntent).build());
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Performance hold", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Keeps Pavo's CPU and GPU clock caps applied.");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
