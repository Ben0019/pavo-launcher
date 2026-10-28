package com.ben.aurora;

import android.content.Context;

/**
 * Cached accent sampled from the device wallpaper. The launcher uses this for
 * neutral chrome (focus rings, page dots, selection and glass rims) while
 * semantic colours for status values, category icons, buttons and sliders stay
 * untouched.
 */
final class ThemeColors {
    private static final int DEFAULT_ACCENT = 0xFFA9BEFF;
    private static int accent = DEFAULT_ACCENT;

    private ThemeColors() {}

    static void initialize(Context context) {
        if (context == null) return;
        set(new Prefs(context).themeAccent());
    }

    static void set(int colour) {
        accent = (colour & 0x00FFFFFF) | 0xFF000000;
    }

    static int rawAccent() {
        return accent;
    }

    /** Accent after the user's saturation preference is applied. */
    static int accent() {
        return ColorTuning.colour(accent);
    }

    static int withAlpha(int alpha) {
        return ColorTuning.withAlpha(accent(), alpha);
    }

    static int defaultAccent() {
        return DEFAULT_ACCENT;
    }
}
