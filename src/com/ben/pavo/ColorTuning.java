package com.ben.pavo;

import android.graphics.Color;

/**
 * Global colour tuning for the launcher's accents, status colours and glows.
 * Icons and wallpaper pixels are deliberately left untouched.
 */
final class ColorTuning {
    private static float saturation = 1f;
    private static float glow = 1f;
    private static int version;

    private ColorTuning() {}

    static void set(int saturationPercent, int glowPercent) {
        float nextSaturation = clamp(saturationPercent, 0, 200) / 100f;
        float nextGlow = clamp(glowPercent, 0, 200) / 100f;
        if (Math.abs(nextSaturation - saturation) < 0.001f
                && Math.abs(nextGlow - glow) < 0.001f) {
            return;
        }
        saturation = nextSaturation;
        glow = nextGlow;
        version++;
    }

    static int version() {
        return version;
    }

    static float saturation() {
        return saturation;
    }

    static float glowScale() {
        return glow;
    }

    static int colour(int colour) {
        if (saturation == 1f) return colour;
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[1] = clamp(hsv[1] * saturation, 0f, 1f);
        return Color.HSVToColor(Color.alpha(colour), hsv);
    }

    static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (clamp(alpha, 0, 255) << 24);
    }

    static int glowAlpha(int alpha) {
        return clamp(Math.round(alpha * glow), 0, 255);
    }

    static float radius(float radius) {
        return radius * glow;
    }

    static int glowColour(int colour, int alpha) {
        return withAlpha(colour(colour), glowAlpha(alpha));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
