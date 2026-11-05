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
import android.os.PowerManager;

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
    private PowerManager.WakeLock wakeLock;
    private boolean wakeLockHeld;
    private DeviceHealthStore store;
    private boolean autoApplied;
    private boolean polling;
    private int unpluggedPolls;
    private String lastStatus;

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
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Pavo:ChargeGuard");
            wakeLock.setReferenceCounted(false);
        }
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
                idle ? "Nothing to do" : target + "%"));
        // Keep the foreground contract while starting, then drop the row once
        // another Pavo service is foreground. The process (and the partial
        // wake lock) stays alive, so the 5 s poll keeps working with the
        // display off without a permanent charge-guard notification.
        if (idle) {
            setWakeLock(false);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        ServiceNotifications.hideLaterIfHosted(this, NOTIFICATION_ID, 8000L);
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
                                setWakeLock(false);
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
        // The main looper cannot run while the CPU is suspended. Hold a
        // partial wake lock only while charging, so the 5 s guard survives
        // display-off and deep sleep without draining the battery outdoors.
        setWakeLock(snap.plugged);
        // Debounce cable loss: separation itself flips some charger nodes for a
        // moment, and releasing on the first bad sample lets the battery creep up.
        if (snap.plugged) unpluggedPolls = 0;
        else unpluggedPolls++;
        boolean atLimit = snap.plugged && snap.level >= threshold;
        boolean release = unpluggedPolls >= 3 || snap.level <= threshold - 3;
        if (atLimit && snap.separated) {
            if (!autoApplied) android.util.Log.i("BatteryGuard", "separation holding at " + snap.level + "%");
            autoApplied = true;
            updateStatus("Charge separation active", snap.level + "%");
        } else if (atLimit && !snap.separated) {
            android.util.Log.i("BatteryGuard", "separating at " + snap.level + "% (target " + threshold + "%)");
            DeviceHealthController.Result r = DeviceHealthController.setSeparation(this, true);
            if (r.ok) {
                autoApplied = true;
                updateStatus("Charge separation active", snap.level + "%");
                toastState("Charge separation active", " · " + snap.level + "%");
            } else {
                updateStatus("Charge separation unavailable", snap.level + "%");
                toastState("Charge separation unavailable", "");
            }
        } else if (release && (autoApplied || (snap.separated && !snap.plugged))) {
            if (unpluggedPolls >= 3) android.util.Log.i("BatteryGuard", "cable removed; releasing separation");
            DeviceHealthController.Result r = DeviceHealthController.setSeparation(this, false);
            if (r.ok) {
                autoApplied = false;
                updateStatus("Charging normally", threshold + "%");
                toastState("Charging normally", " · " + threshold + "%");
            }
        }
    }


    private void updateStatus(String title, String text) {
        String value = title + "|" + text;
        if (value.equals(lastStatus)) return;
        lastStatus = value;
        ServiceNotifications.update(this, NOTIFICATION_ID,
                notification(title, text));
    }

    private void setWakeLock(boolean hold) {
        if (wakeLock == null || hold == wakeLockHeld) return;
        try {
            if (hold) {
                wakeLock.acquire();
                wakeLockHeld = true;
            } else {
                wakeLock.release();
                wakeLockHeld = false;
            }
        } catch (Throwable ignored) {
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
                .setOnlyAlertOnce(true)
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
        setWakeLock(false);
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
