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
    private static final String CHANNEL = "aurora_device_health_guard_v3";
    private static final int NOTIFICATION_ID = 0x42415448; // "BATH"
    private static final long POLL_MS = 5000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DeviceHealthStore store;
    private boolean autoApplied;
    private boolean polling;
    private int unpluggedPolls;

    static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, BatteryGuardService.class);
            ServiceNotifications.start(c, i);
        } catch (Throwable ignored) {
        }
    }

    /** Start the guard whenever any charge rule is active, not only auto separation. */
    static void armIfNeeded(Context c) {
        if (c == null) return;
        try {
            DeviceHealthStore store = new DeviceHealthStore(c);
            if (targetLevel(store) < 0) return;
            start(c);
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
        boolean idle = target < 0;
        // startForegroundService() requires startForeground() even when the
        // guard has nothing to do; stopping first makes Android kill the app
        // five seconds later.
        // startForegroundService() may have been used even if accessibility
        // connected in the meantime; always satisfy the contract or Android
        // kills the whole process (taking the RGB service with it).
        startForeground(NOTIFICATION_ID, notification(
                idle ? "Charge guard" : store.limitEnabled()
                        && (target == store.limitPercent() || !store.autoSeparation())
                        ? "Charge limit" : "Automatic charge separation",
                idle ? "Nothing to do" : "Waiting for " + target + "%"));
        ServiceNotifications.hideLater(this);
        if (idle) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
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
                            if (threshold < 0) {
                                if (!store.separated()) {
                                    DeviceHealthController.setSeparation(
                                            BatteryGuardService.this, false);
                                }
                                stopSelf();
                                return;
                            }
                            apply(snap, threshold);
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

    private void apply(DeviceHealthReader.Snapshot snap, int threshold) {
        if (snap.level < 0) return;
        // Debounce cable loss: separation itself flips some charger nodes for a
        // moment, and releasing on the first bad sample lets the battery creep up.
        if (snap.plugged) unpluggedPolls = 0;
        else unpluggedPolls++;
        boolean atLimit = snap.plugged && snap.level >= threshold;
        boolean release = unpluggedPolls >= 3 || snap.level <= threshold - 3;
        if (atLimit && snap.separated) {
            if (!autoApplied) android.util.Log.i("BatteryGuard", "separation holding at " + snap.level + "%");
            autoApplied = true;
        } else if (atLimit && !snap.separated) {
            android.util.Log.i("BatteryGuard", "separating at " + snap.level + "% (target " + threshold + "%)");
            DeviceHealthController.Result r = DeviceHealthController.setSeparation(this, true);
            if (r.ok) {
                autoApplied = true;
                toastState("Charge separation active", " · " + snap.level + "%");
            } else {
                toastState("Charge separation unavailable", "");
            }
        } else if (release && (autoApplied || (snap.separated && !snap.plugged))) {
            if (unpluggedPolls >= 3) android.util.Log.i("BatteryGuard", "cable removed; releasing separation");
            DeviceHealthController.Result r = DeviceHealthController.setSeparation(this, false);
            if (r.ok) {
                autoApplied = false;
                toastState("Charging normally", " · " + threshold + "%");
            }
        }
    }


    private String lastToast = "";

    private void toastState(String key, String suffix) {
        // Translate the key first: whole sentences with a level appended would
        // miss the catalogue and fall back to English.
        final String text = Loc.s(this, key) + (suffix == null ? "" : suffix);
        if (text.equals(lastToast)) return;
        lastToast = text;
        main.post(new Runnable() {
            @Override
            public void run() {
                android.util.Log.i("BatteryGuard", "toast: " + text);
                android.widget.Toast.makeText(getApplicationContext(), text,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Device health guard", NotificationManager.IMPORTANCE_DEFAULT);
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
                .setOngoing(true).setGroup("pavo_services")
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
