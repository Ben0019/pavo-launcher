package com.ben.pavo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A two-button combo and its placeable action target. */
final class TouchCombo {
    static final float DEFAULT_RADIUS = 0.16f;
    static final float MIN_RADIUS = 0.06f;
    static final float MAX_RADIUS = 0.30f;
    static final float RADIUS = DEFAULT_RADIUS;
    /** Every physical button can be used in either half of a combo. */
    static final int[] BUTTONS = {
            TouchTarget.A, TouchTarget.B, TouchTarget.X, TouchTarget.Y,
            TouchTarget.DPAD_UP, TouchTarget.DPAD_DOWN,
            TouchTarget.DPAD_LEFT, TouchTarget.DPAD_RIGHT,
            TouchTarget.L1, TouchTarget.R1, TouchTarget.L2, TouchTarget.R2,
            TouchTarget.L3, TouchTarget.R3, TouchTarget.SELECT, TouchTarget.START,
            TouchTarget.C, TouchTarget.Z, TouchTarget.MODE, TouchTarget.HOME,
            TouchTarget.BACK, TouchTarget.APPSELECT,
            TouchTarget.VOLUME_UP, TouchTarget.VOLUME_DOWN,
            TouchTarget.LEFT_JOY, TouchTarget.RIGHT_JOY
    };

    final int modifier;
    final int button;
    float x;
    float y;
    float radius = DEFAULT_RADIUS;
    float alpha = 1f;
    boolean hidden;

    TouchCombo(int modifier, int button, float x, float y) {
        this(modifier, button, x, y, DEFAULT_RADIUS);
    }

    TouchCombo(int modifier, int button, float x, float y, float radius) {
        this(modifier, button, x, y, radius, 1f, false);
    }

    TouchCombo(int modifier, int button, float x, float y, float radius,
               float alpha, boolean hidden) {
        this.modifier = modifier;
        this.button = button;
        this.x = clamp(x);
        this.y = clamp(y);
        this.radius = clampRadius(radius);
        this.alpha = clampAlpha(alpha);
        this.hidden = hidden;
    }

    TouchCombo copy() {
        return new TouchCombo(modifier, button, x, y, radius, alpha, hidden);
    }

    String label() {
        return TouchTarget.labelFor(modifier) + "+" + TouchTarget.labelFor(button);
    }

    String key() {
        return modifier + ":" + button;
    }

    String encode() {
        return modifier + ":" + button + ":"
                + String.format(Locale.US, "%.4f", x) + ":"
                + String.format(Locale.US, "%.4f", y) + ":"
                + String.format(Locale.US, "%.4f", radius) + ":"
                + String.format(Locale.US, "%.4f", alpha) + ":"
                + (hidden ? "1" : "0");
    }

    static String encode(List<TouchCombo> combos) {
        StringBuilder b = new StringBuilder();
        for (TouchCombo c : combos) {
            if (b.length() > 0) b.append(';');
            b.append(c.encode());
        }
        return b.toString();
    }

    static List<TouchCombo> decode(String raw) {
        List<TouchCombo> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            String[] f = part.split(":");
            if (f.length < 4) continue;
            try {
                float radius = f.length >= 5
                        ? Float.parseFloat(f[4]) : DEFAULT_RADIUS;
                float alpha = f.length >= 6 ? Float.parseFloat(f[5]) : 1f;
                boolean hidden = f.length >= 7 && "1".equals(f[6]);
                out.add(new TouchCombo(Integer.parseInt(f[0]),
                        Integer.parseInt(f[1]), Float.parseFloat(f[2]),
                        Float.parseFloat(f[3]), radius, alpha, hidden));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static float clamp(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }

    static float clampRadius(float v) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, v));
    }

    static float clampAlpha(float v) {
        return Math.max(0.2f, Math.min(1f, v));
    }
}
