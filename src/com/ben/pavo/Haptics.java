package com.ben.pavo;

import android.content.Context;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;

/**
 * A small haptic vocabulary for the launcher. Each interaction has its own
 * envelope so a focus move, a page turn, a hold and a drop feel distinct.
 */
final class Haptics {
    private Haptics() {}

    private static final long[] TICK = {8};
    private static final float[] TICK_GAIN = {0.22f};

    private static final long[] HOVER = {5};
    private static final float[] HOVER_GAIN = {0.12f};

    private static final long[] KEY = {6, 7, 5};
    private static final float[] KEY_GAIN = {0.52f, 0f, 0.22f};

    private static final long[] CLICK = {11, 5, 7};
    private static final float[] CLICK_GAIN = {0.86f, 0f, 0.30f};

    private static final long[] LONG_PRESS = {15, 12, 24};
    private static final float[] LONG_PRESS_GAIN = {0.55f, 0f, 1.02f};

    private static final long[] PICKUP = {19, 8, 8};
    private static final float[] PICKUP_GAIN = {0.78f, 0f, 0.34f};

    private static final long[] DROP = {7, 5, 24};
    private static final float[] DROP_GAIN = {1.08f, 0f, 0.62f};

    private static final long[] LAUNCH = {7, 5, 18};
    private static final float[] LAUNCH_GAIN = {0.90f, 0f, 0.45f};

    private static final long[] PAGE = {5, 22, 5};
    private static final float[] PAGE_GAIN = {0.60f, 0f, 0.28f};

    private static final long[] EDGE = {6, 14, 5};
    private static final float[] EDGE_GAIN = {0.72f, 0f, 0.38f};

    private static final long[] OUTSIDE = {6, 4, 5};
    private static final float[] OUTSIDE_GAIN = {0.40f, 0f, 0.12f};

    private static final int G_TICK = 0;
    private static final int G_KEY = 1;
    private static final int G_CLICK = 2;
    private static final int G_PAGE = 3;
    private static final int G_EDGE = 4;
    private static final int G_HOVER = 5;
    private static final int G_OUTSIDE = 6;
    private static final long[] LAST = new long[7];
    private static long lastLongPressAt;

    static void click(View v) {
        if (v != null) click(v.getContext());
    }

    static void click(Context c) {
        play(c, CLICK, CLICK_GAIN, G_CLICK, 42L);
    }

    static void key(View v) {
        if (v != null) key(v.getContext());
    }

    static void key(Context c) {
        play(c, KEY, KEY_GAIN, G_KEY, 30L);
    }

    static void tick(View v) {
        if (v == null) return;
        play(v.getContext(), TICK, TICK_GAIN, G_TICK, 22L);
    }

    static void hover(View v) {
        if (v == null) return;
        play(v.getContext(), HOVER, HOVER_GAIN, G_HOVER, 55L);
    }

    static void longPress(View v) {
        if (v != null) longPress(v.getContext());
    }

    static void longPress(Context c) {
        lastLongPressAt = SystemClock.uptimeMillis();
        play(c, LONG_PRESS, LONG_PRESS_GAIN, -1, 0L);
    }

    static void pickup(View v) {
        if (v == null) return;
        // A lift that follows a hold is the same gesture; let the hold cue
        // finish instead of cutting it off with a second pattern.
        if (SystemClock.uptimeMillis() - lastLongPressAt < 110L) return;
        play(v.getContext(), PICKUP, PICKUP_GAIN, -1, 0L);
    }

    static void drop(View v) {
        if (v != null) drop(v.getContext());
    }

    static void drop(Context c) {
        play(c, DROP, DROP_GAIN, -1, 0L);
    }

    static void launch(View v) {
        if (v != null) launch(v.getContext());
    }

    static void launch(Context c) {
        play(c, LAUNCH, LAUNCH_GAIN, -1, 0L);
    }

    static void page(View v) {
        if (v != null) play(v.getContext(), PAGE, PAGE_GAIN, G_PAGE, 55L);
    }

    static void edge(View v) {
        if (v != null) play(v.getContext(), EDGE, EDGE_GAIN, G_EDGE, 70L);
    }

    static void outside(View v) {
        if (v == null) return;
        play(v.getContext(), OUTSIDE, OUTSIDE_GAIN, G_OUTSIDE, 150L);
    }

    /**
     * Three soft pulses that mirror the card's left/right/left settle.
     */
    static void giggle(View v) {
        if (v == null) return;
        Context c = v.getContext();
        if (c == null) return;
        Prefs p = new Prefs(c);
        if (!p.hapticsEnabled()) return;
        int strength = p.hapticsStrength();
        if (strength <= 0) return;
        Vibrator vibrator = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            float perceptual = 0.38f + 0.62f * (strength / 100f);
            long[] timings = {8, 55, 8, 50, 7};
            if (vibrator.hasAmplitudeControl()) {
                int[] amplitudes = {
                        amp(0.55f, perceptual), 0,
                        amp(0.40f, perceptual), 0,
                        amp(0.26f, perceptual)
                };
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void play(Context c, long[] timings, float[] gains,
                             int group, long cooldownMs) {
        if (c == null) return;
        Prefs p = new Prefs(c);
        if (!p.hapticsEnabled()) return;
        int strength = p.hapticsStrength();
        if (strength <= 0) return;
        Vibrator vibrator = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long now = SystemClock.uptimeMillis();
        if (group >= 0 && cooldownMs > 0) {
            if (now - LAST[group] < cooldownMs) return;
            LAST[group] = now;
        }

        try {
            float perceptual = 0.38f + 0.62f * (strength / 100f);
            if (vibrator.hasAmplitudeControl()) {
                int[] amplitudes = new int[gains.length];
                for (int i = 0; i < gains.length; i++) {
                    amplitudes[i] = amp(gains[i], perceptual);
                }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
            }
        } catch (Throwable ignored) {
        }
    }

    private static int amp(float gain, float perceptual) {
        if (gain <= 0f) return 0;
        return Math.max(1, Math.min(255,
                Math.round(255f * gain * perceptual)));
    }
}
