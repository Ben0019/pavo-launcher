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
 * Keeps automatic charge separation working while the Device Health app is closed.
 * The battery is allowed to charge normally until the configured level, then the
 * vendor charging path is restricted and the device runs from the charger.
 */
public final class BatteryGuardService extends Service {
    private static final String CHANNEL = "aurora_device_health_guard";
    private static final int NOTIFICATION_ID = 0x42415448; // "BATH"
    private static final long POLL_MS = 5000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DeviceHealthStore store;
    private boolean autoApplied;
    private boolean polling;

    static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, BatteryGuardService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, BatteryGuardService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new DeviceHealthStore(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (store == null) store = new DeviceHealthStore(this);
        int target = targetLevel(store);
        if (target < 0 && store.speedMa() >= 2000) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification(
                target < 0 ? "Charge speed" : store.limitEnabled()
                        && (target == store.limitPercent() || !store.autoSeparation())
                        ? "Charge limit" : "Automatic charge separation",
                target < 0 ? "Limited to " + (store.speedMa() / 100f) + " A"
                        : "Waiting for " + target + "%"));
        if (!polling) {
            polling = true;
            main.post(poll);
        }
        return START_STICKY;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    // Heal a fan/performance pairing left by the charger or an
                    // older build: the firmware's fan tile crashes SystemUI on
                    // the next refresh when the two disagree.
                    PerfTuner.repairFanMode(BatteryGuardService.this);
                    final DeviceHealthReader.Snapshot snap = DeviceHealthReader.read(BatteryGuardService.this);
                    final int threshold = targetLevel(store);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (threshold < 0 && store.speedMa() >= 2000) {
                                if (!store.separated()) {
                                    DeviceHealthController.setSeparation(
                                            BatteryGuardService.this, false);
                                }
                                stopSelf();
                                return;
                            }
                            applySpeed(snap);
                            if (threshold >= 0) apply(snap, threshold);
                            main.postDelayed(poll, POLL_MS);
                        }
                    });
                }
            });
        }
    };

    /** Lowest active charge ceiling, or -1 when neither guard is on. */
    static int targetLevel(DeviceHealthStore store) {
        int target = Integer.MAX_VALUE;
        if (store.limitEnabled() && store.limitPercent() < 100) {
            target = store.limitPercent();
        }
        if (store.autoSeparation()) target = Math.min(target, store.autoLevel());
        return target == Integer.MAX_VALUE ? -1 : target;
    }

    /** Re-applies the input ceiling when the firmware resets it after a replug. */
    private void applySpeed(DeviceHealthReader.Snapshot snap) {
        if (snap == null || !snap.plugged || snap.separated) return;
        int want = store.speedMa();
        int node = DeviceHealthReader.num(this,
                "/sys/class/power_supply/usb/input_current_limit", -1);
        boolean ok = want >= 2000 ? node >= 2000000
                : Math.abs(node - want * 1000) < 60000;
        if (!ok) DeviceHealthController.setInputLimit(this, want);
    }

    private void apply(DeviceHealthReader.Snapshot snap, int threshold) {
        if (snap.level < 0) {
            update("Waiting for battery data", "");
            return;
        }
        boolean atLimit = snap.plugged && snap.level >= threshold;
        boolean release = !snap.plugged || snap.level <= threshold - 3;
        if (atLimit && snap.separated) {
            autoApplied = true;
            update("Charge separation active", snap.level + "% · running from charger");
        } else if (atLimit && !snap.separated) {
            DeviceHealthController.Result r = DeviceHealthController.setSeparation(this, true);
            if (r.ok) {
                autoApplied = true;
                update("Charge separation active",
                        snap.level + "% · running from charger");
            } else {
                update("Charge separation unavailable", r.message);
            }
        } else if (release && (autoApplied || (snap.separated && !snap.plugged))) {
            DeviceHealthController.Result r = DeviceHealthController.setSeparation(this, false);
            if (r.ok) autoApplied = false;
            update("Charging normally", snap.level + "% · target " + threshold + "%");
        } else {
            notifyIfChanged();
        }
    }

    private String lastText = "";

    private void update(String title, String text) {
        String value = title + "|" + text;
        if (value.equals(lastText)) return;
        lastText = value;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(title, text));
    }

    private void notifyIfChanged() {
        update(store.limitEnabled() ? "Charge limit" : "Automatic charge separation",
                "Target " + targetLevel(store) + "%");
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Device health guard", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Automatic charge separation at the configured battery level");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notification(String title, String text) {
        Intent open = new Intent(this, DeviceHealthActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 22, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_perf)
                .setContentTitle(Loc.s(this, title))
                .setContentText(Loc.s(this, text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        return b.build();
    }

    @Override
    public void onDestroy() {
        polling = false;
        main.removeCallbacksAndMessages(null);
        if (autoApplied) {
            DeviceHealthController.setSeparation(this, false);
            autoApplied = false;
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
