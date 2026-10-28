package com.ben.aurora;

import android.graphics.Color;

/** Shared green -> yellow -> red ramp for fan speed and thermal intensity. */
final class HeatColor {
    static final int GREEN = 0xFF4DDB91;
    static final int YELLOW = 0xFFFFD166;
    static final int RED = 0xFFFF5C6C;

    private HeatColor() {}

    static int ramp(float t) {
        t = clamp(t);
        if (t < 0.5f) return mixColor(GREEN, YELLOW, t * 2f);
        return mixColor(YELLOW, RED, (t - 0.5f) * 2f);
    }

    static int mixColor(int from, int to, float t) {
        t = clamp(t);
        int r = Math.round(Color.red(from) * (1f - t) + Color.red(to) * t);
        int g = Math.round(Color.green(from) * (1f - t) + Color.green(to) * t);
        int b = Math.round(Color.blue(from) * (1f - t) + Color.blue(to) * t);
        return Color.rgb(r, g, b);
    }

    static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    static int lighten(int color, float amount) {
        return mixColor(color, 0xFFFFFFFF, amount);
    }

    /** Duty / temperature severity for controls and the graph. */
    static float severity(int duty, float temperature) {
        float speed = Math.max(0, Math.min(100, duty)) / 100f;
        float heat = (temperature - 35f) / 45f;
        return clamp(Math.max(speed, heat));
    }

    /** Fan / exhaust severity also includes measured RPM. */
    static float fanSeverity(int duty, int rpm, float temperature) {
        float speed = Math.max(0, Math.min(100, duty)) / 100f;
        float measured = Math.max(0, rpm) / 10000f;
        float heat = (temperature - 35f) / 45f;
        return clamp(Math.max(Math.max(speed, measured), heat));
    }

    private static float clamp(float t) {
        return Math.max(0f, Math.min(1f, t));
    }
}
