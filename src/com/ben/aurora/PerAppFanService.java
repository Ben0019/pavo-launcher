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

/** Applies a fan-only profile for each foreground app, falling back to the global fan profile. */
public final class PerAppFanService extends Service {
    static final String ACTION_STOP = "com.ben.aurora.fanperapp.STOP";
    static final String ACTION_REFRESH = "com.ben.aurora.fanperapp.REFRESH";
    private static final String CHANNEL = "aurora_per_app_fan";
    private static final int NOTIFICATION_ID = 0x46414E50; // "FANP"
    private static final long POLL_MS = 1200L;

    private static volatile boolean running;
    private static volatile boolean tunerVisible;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean tickInFlight;
    private boolean nodesPrepared;
    private boolean lastApplyOk;
    private String lastKey = "";
    private int lastCurveDuty = -1;
    private String lastNotificationText = "";
    private FanProfileStore store;
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
            Intent intent = new Intent(context, PerAppFanService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, PerAppFanService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
            context.stopService(new Intent(context, PerAppFanService.class));
        }
    }

    static void refresh(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, PerAppFanService.class);
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
        store = new FanProfileStore(this);
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
            startForeground(NOTIFICATION_ID, notification("Global fan profile"));
            applyGlobalOnce();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Watching foreground apps"));
        if (tunerVisible) return START_STICKY;
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            lastKey = "";
            lastCurveDuty = -1;
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
        FanProfile override = pkg == null ? null : store.app(pkg);
        boolean appOverride = override != null;
        FanProfile profile = appOverride ? override : FanProfile.fromPrefs(prefs);
        if (profile == null) return false;

        String key = (pkg == null ? "global" : pkg) + "|" + profile.encode();
        boolean ok = applyFanProfile(profile, key);
        if (ok) notifyState(appOverride ? appLabel(pkg) : "Global fan profile");
        return false;
    }

    private boolean applyFanProfile(FanProfile profile, String key) {
        if (profile == null) return false;
        if (!nodesPrepared) {
            if (!PerfTuner.prepare()) return false;
            nodesPrepared = true;
        } else if (!PerfTuner.Root.ensure()) {
            return false;
        }

        if (profile.mode == FanProfile.MODE_CURVE) {
            if (!key.equals(lastKey)) {
                lastKey = key;
                lastCurveDuty = -1;
            }
            float[] temps = PerfTuner.temperatures();
            float temp = Math.max(temps[0], temps[1]);
            if (temp <= 0f) temp = 40f;
            int duty = FanCurve.dutyAt(profile.curve, temp);
            if (duty != lastCurveDuty) {
                PerfTuner.setFanManual(duty);
                lastCurveDuty = duty;
            }
            lastApplyOk = true;
            return true;
        }

        if (!key.equals(lastKey) || !lastApplyOk) {
            if (profile.mode == PerfTuner.FAN_MANUAL) {
                PerfTuner.setFanManual(profile.manualPct);
            } else {
                PerfTuner.setFanMode(this, profile.mode);
            }
            lastKey = key;
            lastCurveDuty = -1;
            lastApplyOk = true;
        }
        return true;
    }

    private void applyGlobalOnce() {
        FanProfile global = FanProfile.fromPrefs(prefs);
        applyFanProfile(global, "global|" + global.encode());
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

    private String appLabel(String pkg) {
        if (pkg == null) return "Global fan profile";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    private void notifyState(String text) {
        if (text.equals(lastNotificationText)) return;
        lastNotificationText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, FanActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 32, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(Loc.s(this, "Per-app fan profile"))
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
                "Per-app fan", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Applies each app's fan profile when it comes forward");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }
}
