package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the six background loops quiet while {@link PavoA11yService} is bound.
 *
 * <p>An accessibility service is bound by the system, so the process stays
 * alive and the loops do not need the foreground-service contract. When the
 * accessibility service is not connected the services fall back to their
 * normal foreground notifications.</p>
 */
final class ServiceNotifications {
    static final int FAN = 202;
    static final int OVERLAY = 4211;
    static final int TUNE = 0x4174756E;
    static final int GUARD = 0x42415448;
    static final int INPUT = 0x415552;
    static final int PER_TUNE = 0x50455241;
    static final int PER_FAN = 0x46414E50;

    private static final int[] PERSISTENT = {
            FAN, OVERLAY, TUNE, GUARD, INPUT, PER_TUNE, PER_FAN
    };

    private static final int[] HOSTED_IDS = {
            OVERLAY, TUNE, GUARD, INPUT, PER_TUNE, PER_FAN
    };
    private static final Class<?>[] HOSTED_SERVICES = {
            PerfOverlayService.class, PerfTuneService.class, BatteryGuardService.class,
            PavoInputService.class, PerAppTuneService.class, PerAppFanService.class
    };

    private ServiceNotifications() {}

    /** Start without a foreground-service contract when a11y keeps us alive. */
    static void start(Context context, android.content.Intent intent) {
        if (context == null || intent == null) return;
        if (PavoA11yService.isConnected()) {
            context.startService(intent);
        } else if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Remove a foreground row after the startForeground() contract window has
     * passed. Used when a service was started before the accessibility binding
     * came up; doing it immediately makes the system think startForeground()
     * was never called and kills the app with an ANR.
     */
    static void hideLater(final Service service) {
        if (service == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Stay past the startForeground() contract window, then drop
                // the row as soon as the accessibility service is bound. It
                // may connect a few seconds after this service started.
                try {
                    Thread.sleep(7000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 24; i++) {
                    if (PavoA11yService.isConnected()) {
                        try {
                            service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "PavoQuietRow").start();
    }

    /** True when this service must not call startForeground(). */
    static boolean hosted() {
        return PavoA11yService.isConnected();
    }

    /** Post or update a persistent notification, unless notifications are off. */
    static void update(Context context, int id, Notification notification) {
        if (context == null || notification == null || PavoA11yService.isConnected()) return;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, notification);
    }

    /**
     * Services that were started before the accessibility binding come up are
     * already foreground and their rows cannot be cancelled while they stay
     * foreground. Stop and restart only those, so they take the startService
     * path and never post a row. Called once when the a11y service connects.
     */
    static void restartHosted(final Context context) {
        if (context == null) return;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        StatusBarNotification[] active;
        try {
            active = nm.getActiveNotifications();
        } catch (Throwable ignored) {
            return;
        }
        if (active == null || active.length == 0) return;
        final List<Class<?>> restart = new ArrayList<>();
        for (StatusBarNotification sbn : active) {
            if (sbn == null || !context.getPackageName().equals(sbn.getPackageName())) continue;
            for (int i = 0; i < HOSTED_IDS.length; i++) {
                if (sbn.getId() == HOSTED_IDS[i] && !restart.contains(HOSTED_SERVICES[i])) {
                    restart.add(HOSTED_SERVICES[i]);
                }
            }
        }
        if (restart.isEmpty()) return;
        for (Class<?> service : restart) {
            try {
                context.stopService(new Intent(context, service));
            } catch (Throwable ignored) {
            }
        }
        // restartHosted runs on a worker thread; give the system a moment to
        // finish the stop before starting the replacement instance.
        try {
            Thread.sleep(800L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        for (Class<?> service : restart) {
            try {
                context.startService(new Intent(context, service));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Remove rows that were posted before the accessibility service connected. */
    static void cancelPersistent(Context context) {
        if (context == null) return;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        for (int id : PERSISTENT) nm.cancel(id);
    }
}
