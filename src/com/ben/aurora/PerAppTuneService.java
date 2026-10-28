package com.ben.aurora;

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
 * Applies a full tuner configuration for each foreground app. Apps without an
 * override use the global tuner configuration.
 */
public final class PerAppTuneService extends Service {

    static final String ACTION_STOP = "com.ben.aurora.perapp.STOP";
    static final String ACTION_REFRESH = "com.ben.aurora.perapp.REFRESH";
    private static final String CHANNEL = "aurora_per_app";
    private static final int NOTIFICATION_ID = 0x50455241;
    private static final long POLL_MS = 1200L;
    private static final float THERMAL_LIMIT_C = 72f;

    private static volatile boolean running;
    private static volatile boolean tunerVisible;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean tickInFlight;
    private boolean nodesPrepared;
    private boolean lastApplyOk;
    private String lastAppliedKey = "";
    private String lastNotificationText = "";
    private TuneStore store;
    private Prefs prefs;

    static boolean isRunning() {
        return running;
    }

    static void setTunerVisible(boolean visible) {
        tunerVisible = visible;
    }

    static void start(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, PerAppTuneService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, PerAppTuneService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
            context.stopService(new Intent(context, PerAppTuneService.class));
        }
    }

    static void refresh(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, PerAppTuneService.class);
        intent.setAction(ACTION_REFRESH);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        store = new TuneStore(this);
        prefs = new Prefs(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAndRestoreGlobal();
            return START_NOT_STICKY;
        }
        if (!store.hasOverrides()) {
            startForeground(NOTIFICATION_ID, notification("Global tuner settings"));
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Watching foreground apps"));
        if (tunerVisible) return START_STICKY;
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            lastAppliedKey = "";
        }
        schedule(0L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void schedule(long delay) {
        main.removeCallbacks(tick);
        main.postDelayed(tick, delay);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (tickInFlight || !running) return;
            tickInFlight = true;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    boolean stop = processOnce();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            tickInFlight = false;
                            if (!running) return;
                            if (stop) stopSelf();
                            else schedule(POLL_MS);
                        }
                    });
                }
            });
        }
    };

    /** @return true when the service should stop. */
    private boolean processOnce() {
        if (!store.hasOverrides()) {
            applyGlobalOnce();
            return true;
        }
        if (tunerVisible) return false;

        String pkg = foregroundPackage();
        TuneConfig config;
        String label;
        if (pkg != null && store.app(pkg) != null) {
            config = store.app(pkg);
            label = pkg;
        } else {
            config = globalConfig();
            label = "Global tuner settings";
        }
        if (config == null) return false;
        String key = pkg + "|" + config.encode();
        boolean full = !key.equals(lastAppliedKey) || !lastApplyOk;
        lastApplyOk = applyConfig(config, full);
        if (lastApplyOk) lastAppliedKey = key;
        notifyState(label);
        return false;
    }

    private TuneConfig globalConfig() {
        TuneConfig g = store.global();
        if (g != null) return g;
        if (!PerfTuner.prepare()) return null;
        return store.globalOrInit(PerfTuner.readState(this), prefs);
    }

    private void applyGlobalOnce() {
        TuneConfig global = globalConfig();
        if (global == null) return;
        if (!tunerVisible) applyConfig(global, true);
    }

    private void stopAndRestoreGlobal() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                applyGlobalOnce();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        stopSelf();
                    }
                });
            }
        });
    }

    private String foregroundPackage() {
        String cached = ForegroundApp.read(this);
        ForegroundApp.request(this);
        return normalize(cached);
    }

    private String normalize(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (pkg.equals(getPackageName())) return null;
        if (pkg.equals("com.android.systemui")) return null;
        if (pkg.equals("com.odin.gameassistant")) return null;
        if (pkg.equals("com.android.launcher3")) return null;
        if (pkg.equals("com.odin.odinlauncher")) return null;
        if (pkg.equals("android")) return null;
        return pkg;
    }

    private boolean applyConfig(TuneConfig config, boolean full) {
        if (config == null) return false;
        if (!nodesPrepared) {
            if (!PerfTuner.prepare()) return false;
            nodesPrepared = true;
        } else if (!PerfTuner.Root.ensure()) {
            return false;
        }
        float[] temps = PerfTuner.temperatures();
        if (Math.max(temps[0], temps[1]) >= THERMAL_LIMIT_C) return false;
        PerfTuner.State state = PerfTuner.readStateForHold(this);
        if (state.efficiency == null || state.performance == null || state.gpu == null) {
            return false;
        }
        if (full) {
            config.apply(this, state);
        } else {
            PerfTuner.setCpu(state.efficiency, config.p0Max, config.p0Min, config.p0Gov);
            PerfTuner.setCpu(state.performance, config.p6Max, config.p6Min, config.p6Gov);
            PerfTuner.setGpu(state.gpu, config.gpuMax, config.gpuMin);
        }
        if (prefs.perfHoldEnabled()) {
            prefs.setTuneTargets(config.p0Max, config.p0Min, config.p0Gov,
                    config.p6Max, config.p6Min, config.p6Gov,
                    config.gpuMax, config.gpuMin);
            if (!PerfTuneService.isRunning()) PerfTuneService.start(this);
        }
        return true;
    }

    private void notifyState(String text) {
        if (text.equals(lastNotificationText)) return;
        lastNotificationText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, PerformanceTunerActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 31, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(Loc.s(this, "Per-app performance"))
                .setContentText(Loc.s(this, text))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Per-app performance", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Applies each app's tuner configuration when it comes forward");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }
}
