package com.ben.pavo;

import java.util.Arrays;

/**
 * Damps a temperature-to-duty curve so the blower follows the heatsink instead
 * of the per-core junction sensors.
 *
 * <p>The Snapdragon core sensors move ~40 °C within three seconds of load
 * starting or stopping, while the blower and heatsink need tens of seconds.
 * Driving the PWM straight from the raw maximum made the fan jump between 20 %
 * and 100 % and hunt on every cooldown. This controller keeps the response fast
 * on the way up, slows the release on the way down, and ignores changes that are
 * too small to hear.
 */
final class FanCurveController {

    static final int RESPONSE_FAST = 0;
    static final int RESPONSE_BALANCED = 1;
    static final int RESPONSE_SMOOTH = 2;
    static final int RESPONSE_VERY_SMOOTH = 3;

    /** Fall time constant. Holds the blower up while the heatsink is still hot. */
    static final float RELEASE_SEC = 10f;
    /** Downward slew limit, percentage points per second. */
    static final float FALL_LIMIT = 5f;
    /** Faster release/drop for HOLD when the target is comfortably undershot. */
    static final float HOLD_RELEASE_SEC = 3f;
    static final float HOLD_FALL_LIMIT = 12f;

    private float attackSec = 3.0f;
    private float fastAttackSec = 1.8f;
    private int deadband = 4;
    private float riseLimit = 10f;

    /** Select how aggressively the curve follows the filtered temperature. */
    void setResponse(int response) {
        switch (response) {
            case RESPONSE_FAST:
                attackSec = 1.2f;
                fastAttackSec = 0.6f;
                deadband = 2;
                riseLimit = 60f;
                break;
            case RESPONSE_BALANCED:
                attackSec = 2.0f;
                fastAttackSec = 1.2f;
                deadband = 3;
                riseLimit = 20f;
                break;
            case RESPONSE_VERY_SMOOTH:
                attackSec = 4.5f;
                fastAttackSec = 2.8f;
                deadband = 5;
                riseLimit = 5f;
                break;
            default:
                attackSec = 3.0f;
                fastAttackSec = 1.8f;
                deadband = 4;
                riseLimit = 10f;
                break;
        }
    }

    private boolean primed;
    private boolean seeded;
    private float filtered;
    private int applied = -1;
    private long lastMs;
    private final float[] window = new float[5];
    private int samples;

    void reset() {
        primed = false;
        filtered = 0f;
        applied = -1;
        lastMs = 0L;
        samples = 0;
    }

    int applied() {
        return applied;
    }

    boolean primed() {
        return primed;
    }

    /**
     * Start from the duty the fan is already running at. The first control tick
     * then keeps it and only later ticks ease towards the curve, so enabling the
     * service is inaudible instead of a jump.
     */
    void seed(int dutyPercent) {
        applied = dutyPercent >= 0 ? Math.max(0, Math.min(100, dutyPercent)) : -1;
        primed = false;
        samples = 0;
        filtered = 0f;
        lastMs = 0L;
        seeded = applied >= 0;
    }

    float filteredTemp() {
        return filtered;
    }

    /**
     * @return duty percentage to write, or -1 when the current duty still fits.
     */
    synchronized int next(String curve, float rawMaxTemp, long nowMs) {
        return next(curve, rawMaxTemp, nowMs, false);
    }

    /**
     * @param responsive true for Smart: act on the newest sample and ramp in
     *                   about half a second instead of waiting for a median.
     */
    synchronized int next(String curve, float rawMaxTemp, long nowMs, boolean responsive) {
        return next(curve, rawMaxTemp, nowMs, responsive, false);
    }

    /**
     * @param fastRelease true for HOLD: drop back towards the curve quickly when
     *                    the target is comfortably undershot, instead of holding
     *                    full speed for the usual ten second release.
     */
    synchronized int next(String curve, float rawMaxTemp, long nowMs,
                          boolean responsive, boolean fastRelease) {
        float raw = rawMaxTemp > 0f ? rawMaxTemp : 40f;
        window[samples % 3] = raw;
        samples++;
        // A single hot sample from one core is a burst, not heat in the heatsink.
        // Use a five-sample median in every mode so Smart cannot chase a spike.
        float sample = samples >= 5 ? median5(window) : raw;
        if (!primed) {
            primed = true;
            filtered = sample;
            lastMs = nowMs;
            if (seeded) {
                // Keep the existing speed for this first tick.
                seeded = false;
                return -1;
            }
            applied = FanCurve.dutyAt(curve, filtered);
            return applied;
        }
        float dt = Math.max(0.2f, Math.min(5f, (nowMs - lastMs) / 1000f));
        lastMs = nowMs;

        float tau = sample > filtered
                ? (responsive ? fastAttackSec : attackSec)
                : (fastRelease ? HOLD_RELEASE_SEC : RELEASE_SEC);
        float alpha = 1f - (float) Math.exp(-dt / tau);
        filtered += (sample - filtered) * alpha;

        int target = FanCurve.dutyAt(curve, filtered);
        if (applied < 0) {
            applied = target;
            return applied;
        }
        int delta = target - applied;
        if (Math.abs(delta) < deadband) return -1;
        if (delta > 0) {
            // A transient spike must not slam the blower to full: climb quickly
            // but in steps, so a cold start does not begin at 100%.
            int maxRise = Math.max(1, Math.round(riseLimit * dt));
            applied = Math.min(target, applied + maxRise);
        } else {
            float fallLimit = fastRelease ? HOLD_FALL_LIMIT : FALL_LIMIT;
            int maxFall = Math.max(1, Math.round(fallLimit * dt));
            applied = Math.max(target, applied - maxFall);
        }
        return applied;
    }

    private static float median5(float[] values) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
