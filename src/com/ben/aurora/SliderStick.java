package com.ben.aurora;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

import java.util.WeakHashMap;

/** Shared right-stick handling for every launcher slider. */
final class SliderStick {
    private static final WeakHashMap<SeekBar, long[]> HOLD_STARTS = new WeakHashMap<>();

    private SliderStick() {}

    static boolean isGamepad(MotionEvent event) {
        if (event == null) return false;
        int source = event.getSource();
        return (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) != 0;
    }

    /** Right-stick X, with the RX fallback used by some Android gamepad mappings. */
    static float rightX(MotionEvent event) {
        if (event == null) return 0f;
        float x = event.getAxisValue(MotionEvent.AXIS_Z);
        if (Math.abs(x) < 0.12f) x = event.getAxisValue(MotionEvent.AXIS_RX);
        return Math.max(-1f, Math.min(1f, x));
    }

    static boolean active(float x) {
        return Math.abs(x) >= 0.16f;
    }

    /** Clear the acceleration state when the stick returns to centre. */
    static void reset(SeekBar bar) {
        if (bar != null) HOLD_STARTS.remove(bar);
    }

    /**
     * Adjust a focused slider from the right stick. The caller supplies a
     * per-window timestamp so holding the stick produces a smooth repeat.
     */
    static boolean adjust(SeekBar bar, float x, long[] lastAt, View hapticTarget) {
        if (bar == null || !bar.isEnabled() || Math.abs(x) < 0.16f) return false;
        long now = android.os.SystemClock.uptimeMillis();
        float power = Math.min(1f, (Math.abs(x) - 0.12f) / 0.88f);

        // A fresh deflection starts slow; holding it accelerates smoothly so
        // crossing a long range does not take repeated flicks of the stick.
        long[] hold = HOLD_STARTS.get(bar);
        if (hold == null || now - lastAt[0] > 140L) {
            hold = new long[] {now};
            HOLD_STARTS.put(bar, hold);
        }
        long held = now - hold[0];
        float holdCurve = Math.min(1f, held / 3200f);
        float holdWeight = holdCurve * (0.30f + 0.70f * power);

        // Small deflections nudge; a full push moves several steps; holding
        // ramps continuously toward a genuinely fast slide.
        long interval = Math.max(18L, Math.round(
                95f - 28f * power - 45f * holdWeight));
        if (now - lastAt[0] < interval) return true;

        lastAt[0] = now;
        // Use the slider's own key path so SeekBar reports fromUser=true and the
        // activity applies the setting/hardware change exactly as for touch.
        int keyCode = x > 0f ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        int steps = 1 + (int) (power * 3.2f) + (int) (holdWeight * 5.5f);
        steps = Math.max(1, Math.min(9, steps));
        boolean changed = false;
        for (int i = 0; i < steps; i++) {
            changed |= bar.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            bar.onKeyUp(keyCode, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
        if (changed) Haptics.tick(hapticTarget == null ? bar : hapticTarget);
        return true;
    }
}
