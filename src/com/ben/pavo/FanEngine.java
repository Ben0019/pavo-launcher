package com.ben.pavo;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/**
 * Fan loop hosted by {@link PavoA11yService}.
 *
 * <p>Accessibility services are bound by the system, so the process stays
 * alive without a foreground service (and without a persistent notification).
 * The loop itself is the same controller the old FanCurveService used.</p>
 */
final class FanEngine {
    private static final Object LOCK = new Object();

    private static Context app;
    private static HandlerThread thread;
    private static Handler handler;
    private static volatile boolean running;
    private static volatile float controlTemp;

    private static int lastDuty = -1;
    private static String lastCurve = "";
    private static final FanCurveController controller = new FanCurveController();
    private static boolean manualHeld;
    private static boolean modeNormalized;
    private static long startedAt;

    private FanEngine() {}

    static boolean isRunning() {
        return running;
    }

    /** Temperature the controller is actually following (after damping). */
    static float controlTemp() {
        return controlTemp;
    }

    static void start(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            app = c.getApplicationContext();
            if (thread == null || !thread.isAlive()) {
                thread = new HandlerThread("PavoFan");
                thread.start();
                handler = new Handler(thread.getLooper());
            }
            running = true;
            controlTemp = 0f;
            startedAt = SystemClock.uptimeMillis();
            lastDuty = -1;
            manualHeld = false;
            modeNormalized = false;
            lastCurve = "";
            controller.reset();
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    static void update(Context c) {
        start(c);
    }

    static void stop(Context c) {
        Context context = c;
        synchronized (LOCK) {
            running = false;
            controlTemp = 0f;
            if (handler != null) handler.removeCallbacks(tick);
        }
        final Context target = context != null ? context.getApplicationContext() : app;
        if (target == null) return;
        // Hand the blower back to the firmware curve instead of leaving it at
        // whatever duty the loop last wrote - but do not override a mode the
        // user just picked (OFF/LOW/MANUAL are applied separately).  The old
        // unconditional SMART write raced the OFF write, so OFF sometimes
        // ended up back on the smart curve.
        final int selected = new Prefs(target).fanAppMode();
        if (selected != FanActivity.MODE_CURVE && !FanCurve.isPresetMode(selected)
                && selected != FanActivity.MODE_HOLD
                && selected != PerfTuner.FAN_SMART && selected != PerfTuner.FAN_HIGH) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                PerfTuner.setFanMode(target, PerfTuner.FAN_SMART);
            }
        }, "PavoFanRelease");
        t.start();
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || app == null) return;
            Prefs prefs = new Prefs(app);
            int appMode = prefs.fanAppMode();
            boolean smart = appMode == PerfTuner.FAN_SMART;
            boolean high = appMode == PerfTuner.FAN_HIGH;
            boolean hold = appMode == FanActivity.MODE_HOLD;
            String preset = FanCurve.presetCurve(prefs, appMode);
            if (appMode != FanActivity.MODE_CURVE && preset == null && !hold && !smart && !high) {
                stop(app);
                return;
            }
            if (!modeNormalized) {
                modeNormalized = true;
                // Leave the firmware on a sane preset. Our duty writes below
                // override its curve and cancel its loop; if this process is
                // ever reclaimed, the vendor curve keeps the blower running.
                PerfTuner.setFanMode(app, PerfTuner.FAN_SMART);
            }
            float[] temps = PerfTuner.readTemperaturesRaw();
            float source = FanCurve.sourceTemp(temps[0], temps[1], prefs.fanTempSource());
            float temp = Math.max(source, 0f);
            boolean warmup = SystemClock.uptimeMillis() - startedAt < 3000L;
            if (temp <= 0f) temp = 40f;
            String curve = high ? FanCurve.flat(100)
                    : smart ? FanCurve.SMART
                    : hold ? FanCurve.holdCurve(prefs.fanHoldTargetC())
                    : preset != null ? preset : prefs.fanCurve();
            if (!curve.equals(lastCurve)) {
                lastCurve = curve;
                controller.reset();
                // Keep the fan where it is and ease from there.
                controller.seed(PerfTuner.fanPercent(app));
            }
            // High is a hard maximum: do not ease into it.
            int duty = high ? 100
                    : controller.next(curve, temp, SystemClock.uptimeMillis(), smart, hold);
            controlTemp = controller.filteredTemp();
            if (warmup) duty = -1;
            if (duty >= 0) {
                lastDuty = duty;
                if (!VendorFan.setPercent(duty)) {
                    if (manualHeld) {
                        PerfTuner.setFanDuty(duty);
                    } else {
                        PerfTuner.setFanManual(duty);
                        manualHeld = true;
                    }
                }
            } else if (!warmup && manualHeld
                    && PerfTuner.fanManualDrifted(app, controller.applied())) {
                if (!VendorFan.setPercent(controller.applied())) {
                    PerfTuner.setFanManual(controller.applied());
                }
            }
            Handler h = handler;
            if (h != null) h.postDelayed(this, smart || high ? 750L : hold ? 1000L : 1500L);
        }
    };
}
