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

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, PerfTuneService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
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
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification(false, false));
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
                                    nm.notify(NOTIFICATION_ID, notification(hot, paused));
                                }
                            }
                            schedule(hot || paused ? 1000L : 150L);
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
        float[] temps = PerfTuner.temperatures();
        // Only step aside when the chip is genuinely hot; the old 72 C limit
        // meant the caps never applied while gaming (junctions run 80-95 C).
        float limit = prefs.perfHoldLimitC();
        if (limit <= 0f) limit = THERMAL_LIMIT_C;
        if (Math.max(temps[0], temps[1]) >= limit) {
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
        PerfTuner.Root.ensure();
        PerfTuner.State state = PerfTuner.readStateForHold(this);
        // performance_mode is the firmware's fan policy switch: writing a
        // non-zero value makes its daemon set fan_mode=0 (fan off). The tuner
        // owns clocks directly, so leave that setting alone.
        PerfTuner.setCpu(state.efficiency, cfg.p0Max, cfg.p0Min, cfg.p0Gov);
        PerfTuner.setCpu(state.performance, cfg.p6Max, cfg.p6Min, cfg.p6Gov);
        PerfTuner.setGpu(state.gpu, cfg.gpuMax, cfg.gpuMin);
        return new boolean[]{false, false};
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
                .setOngoing(true)
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
                "Performance hold", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps Pavo's CPU and GPU clock caps applied.");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
