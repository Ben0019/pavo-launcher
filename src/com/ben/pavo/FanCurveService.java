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

    private static volatile boolean active;

    static boolean isActive() { return active; }

    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;
    private int lastDuty = -1;
    private String lastCurve = "";
    private final FanCurveController controller = new FanCurveController();
    private boolean manualHeld;
    private boolean modeNormalized;
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
        active = true;
        running = true;
        thread = new HandlerThread("PavoFanCurve");
        thread.start();
        handler = new Handler(thread.getLooper());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Loc.s(this, "Fan curve"),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Temperature-based fan curve");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        Prefs prefs = new Prefs(this);
        // Satisfy startForegroundService() before any early stop below.
        startForeground(NOTIFICATION_ID, notification(prefs));
        ServiceNotifications.hideLaterIfHosted(this, NOTIFICATION_ID, 7000L);
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
        if (appMode != FanActivity.MODE_CURVE && !FanCurve.isPresetMode(appMode)
                && appMode != PerfTuner.FAN_SMART && appMode != PerfTuner.FAN_HIGH) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startedAt = SystemClock.uptimeMillis();
        lastDuty = -1;
        manualHeld = false;
        modeNormalized = false;
        lastCurve = "";
        controller.reset();
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
            boolean high = appMode == PerfTuner.FAN_HIGH;
            String preset = FanCurve.presetCurve(prefs, appMode);
            if (appMode != FanActivity.MODE_CURVE && preset == null && !smart && !high) {
                stopSelf();
                return;
            }
            if (!modeNormalized) {
                modeNormalized = true;
                // Leave the firmware on a sane preset. Our duty writes below
                // override its curve and cancel its loop; if this service is
                // ever reclaimed, the vendor curve keeps the blower running
                // instead of leaving it at whatever the last mode was.
                PerfTuner.setFanMode(FanCurveService.this, PerfTuner.FAN_SMART);
            }
            float[] temps = PerfTuner.readTemperaturesRaw();
            float hal = FanCurve.sourceTemp(temps[0], temps[1], prefs.fanTempSource());
            float temp = Math.max(hal, 0f);
            boolean warmup = SystemClock.uptimeMillis() - startedAt < 3000L;
            if (temp <= 0f) temp = 40f;
            // Smart used to be the firmware's own curve, which reacts slowly.
            // Driving the built-in smart curve here makes it as responsive as
            // the user's curve mode.
            // High is always full speed, driven by the same controller so it
            // holds a flat duty instead of being re-written every tick.
            String curve = high ? FanCurve.flat(100)
                    : smart ? FanCurve.SMART
                    : preset != null ? preset : prefs.fanCurve();
            if (!curve.equals(lastCurve)) {
                lastCurve = curve;
                controller.reset();
                // A reset would forget the seed and jump to the curve value on
                // the next tick; keep the fan where it is and ease from there.
                controller.seed(PerfTuner.fanPercent(FanCurveService.this));
            }
            // High is a hard maximum: do not ease into it or let the curve
            // controller round it down.
            int duty = high ? 100
                    : controller.next(curve, temp, SystemClock.uptimeMillis(), smart);
            if (warmup) duty = -1;  // start silently, keep the current speed
            if (duty >= 0) {
                lastDuty = duty;
                // The vendor provider cancels its own four second curve and
                // writes the duty node directly, so the requested duty holds
                // without the 4<->6 mode fight that made the fan step audibly.
                if (!VendorFan.setPercent(duty)) {
                    // Fallback for builds without the provider: take manual
                    // control once, then move only the duty.
                    if (manualHeld) {
                        PerfTuner.setFanDuty(duty);
                    } else {
                        PerfTuner.setFanManual(duty);
                        manualHeld = true;
                    }
                }
            } else if (!warmup && manualHeld
                    && PerfTuner.fanManualDrifted(FanCurveService.this,
                            controller.applied())) {
                // A preset or the game panel moved the fan away from the
                // curve; re-assert it instead of waiting for the temperature.
                if (!VendorFan.setPercent(controller.applied())) {
                    PerfTuner.setFanManual(controller.applied());
                }
            }
            handler.postDelayed(this, smart || high ? 750L : 1500L);
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
                .setOngoing(true).setGroup("pavo_services")
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_perf),
                        "USE SMART", disable).build())
                .build();
    }

    @Override
    public void onDestroy() {
        active = false;
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
