package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

/** Applies the user's fan curve while the Fan Control app is closed. */
public final class FanCurveService extends Service {
    static final String ACTION_START = "com.ben.pavo.FAN_CURVE_START";
    static final String ACTION_UPDATE = "com.ben.pavo.FAN_CURVE_UPDATE";
    static final String ACTION_STOP = "com.ben.pavo.FAN_CURVE_STOP";
    static final String ACTION_DISABLE = "com.ben.pavo.FAN_CURVE_DISABLE";
    private static final String CHANNEL = "aurora_fan_curve";
    private static final int NOTIFICATION_ID = 202;

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private int lastDuty = -1;
    private String lastCurve = "";
    private final FanCurveController controller = new FanCurveController();
    private boolean manualHeld;
    private long startedAt;

    static void start(Context c) {
        if (c == null) return;
        Intent i = new Intent(c, FanCurveService.class).setAction(ACTION_START);
        c.startService(i);
    }

    static void update(Context c) {
        if (c == null) return;
        Intent i = new Intent(c, FanCurveService.class).setAction(ACTION_UPDATE);
        c.startService(i);
    }

    static void stop(Context c) {
        if (c == null) return;
        Intent i = new Intent(c, FanCurveService.class).setAction(ACTION_STOP);
        c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        thread = new HandlerThread("PavoFanCurve");
        thread.start();
        handler = new Handler(thread.getLooper());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Loc.s(this, "Fan curve"),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Temperature-based fan curve");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        Prefs prefs = new Prefs(this);
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_DISABLE.equals(action)) {
            prefs.setFanAppMode(PerfTuner.FAN_SMART);
            PerfTuner.setFanMode(this, PerfTuner.FAN_SMART);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        int appMode = prefs.fanAppMode();
        if (appMode != FanActivity.MODE_CURVE && appMode != PerfTuner.FAN_SMART) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startedAt = SystemClock.uptimeMillis();
        lastDuty = -1;
        manualHeld = false;
        lastCurve = "";
        controller.reset();
        startForeground(NOTIFICATION_ID, notification(prefs));
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            Prefs prefs = new Prefs(FanCurveService.this);
            int appMode = prefs.fanAppMode();
            boolean smart = appMode == PerfTuner.FAN_SMART;
            if (appMode != FanActivity.MODE_CURVE && !smart) {
                stopSelf();
                return;
            }
            float[] temps = PerfTuner.readTemperatures();
            float hal = Math.max(temps[0], temps[1]);
            float temp = hal;
            boolean warmup = SystemClock.uptimeMillis() - startedAt < 3000L;
            if (temp <= 0f) temp = 40f;
            // Smart used to be the firmware's own curve, which reacts slowly.
            // Driving the built-in smart curve here makes it as responsive as
            // the user's curve mode.
            String curve = smart ? FanCurve.SMART : prefs.fanCurve();
            if (!curve.equals(lastCurve)) {
                lastCurve = curve;
                controller.reset();
                // A reset would forget the seed and jump to the curve value on
                // the next tick; keep the fan where it is and ease from there.
                controller.seed(PerfTuner.fanPercent(FanCurveService.this));
            }
            int duty = controller.next(curve, temp, SystemClock.uptimeMillis(), smart);
            if (warmup) duty = -1;  // start silently, keep the current speed
            if (duty >= 0) {
                lastDuty = duty;
                // Take manual control once, then move only the duty. Rewriting
                // fan_mode every 1.5 s made the firmware's fan daemon re-notify
                // its quick-settings tile constantly, and a bad transient mode
                // in that stream crashes SystemUI.
                if (manualHeld) PerfTuner.setFanDuty(duty);
                else {
                    PerfTuner.setFanManual(duty);
                    manualHeld = true;
                }
            } else if (!warmup && PerfTuner.fanManualDrifted(FanCurveService.this,
                    controller.applied())) {
                // The game panel or a preset switched the fan away from the
                // curve; re-assert it instead of waiting for the temperature.
                if (PerfTuner.fanMode(FanCurveService.this) == PerfTuner.FAN_MANUAL) {
                    PerfTuner.setFanDuty(controller.applied());
                } else {
                    PerfTuner.setFanManual(controller.applied());
                    manualHeld = true;
                }
            }
            handler.postDelayed(this, smart ? 500L : 1500L);
        }
    };

    private Notification notification(Prefs prefs) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, FanActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disable = PendingIntent.getService(this, 1,
                new Intent(this, FanCurveService.class).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_perf)
                .setContentTitle(Loc.s(this, "Pavo fan curve"))
                .setContentText(Loc.s(this, "Temperature-based fan control is active"))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_perf),
                        "USE SMART", disable).build())
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
