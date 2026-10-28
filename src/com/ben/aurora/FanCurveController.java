package com.ben.aurora;

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

    /** Rise time constant. A sustained heat step is ~95 % applied in four seconds. */
    static final float ATTACK_SEC = 1.2f;
    /** Fall time constant. Holds the blower up while the heatsink is still hot. */
    static final float RELEASE_SEC = 8f;
    /** Target changes below this many percentage points are not worth a write. */
    static final int DEADBAND = 3;
    /** Downward slew limit, percentage points per second. */
    static final float FALL_LIMIT = 6f;

    private boolean primed;
    private float filtered;
    private int applied = -1;
    private long lastMs;
    private final float[] window = new float[3];
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

    float filteredTemp() {
        return filtered;
    }

    /**
     * @return duty percentage to write, or -1 when the current duty still fits.
     */
    synchronized int next(String curve, float rawMaxTemp, long nowMs) {
        float raw = rawMaxTemp > 0f ? rawMaxTemp : 40f;
        window[samples % 3] = raw;
        samples++;
        // A single hot sample from one core is a burst, not heat in the heatsink.
        float sample = samples >= 3 ? median(window[0], window[1], window[2]) : raw;
        if (!primed) {
            primed = true;
            filtered = sample;
            lastMs = nowMs;
            applied = FanCurve.dutyAt(curve, filtered);
            return applied;
        }
        float dt = Math.max(0.2f, Math.min(5f, (nowMs - lastMs) / 1000f));
        lastMs = nowMs;

        float tau = sample > filtered ? ATTACK_SEC : RELEASE_SEC;
        float alpha = 1f - (float) Math.exp(-dt / tau);
        filtered += (sample - filtered) * alpha;

        int target = FanCurve.dutyAt(curve, filtered);
        if (applied < 0) {
            applied = target;
            return applied;
        }
        int delta = target - applied;
        if (Math.abs(delta) < DEADBAND) return -1;
        if (delta > 0) {
            applied = target;
        } else {
            int maxFall = Math.max(1, Math.round(FALL_LIMIT * dt));
            applied = Math.max(target, applied - maxFall);
        }
        return applied;
    }

    private static float median(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }
}
