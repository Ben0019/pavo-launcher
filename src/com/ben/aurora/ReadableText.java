package com.ben.aurora;

import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * One conservative readability pass for the built-in apps: lift very small
 * labels to a usable size, raise the opacity of light secondary text, and add
 * a soft shadow so text remains readable over the live glass background.
 * Views are tagged in a weak set so later layout passes do not keep scaling
 * them or fight text sizes set by dynamic list rows.
 */
final class ReadableText {
    private static final Set<View> STYLED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private ReadableText() {}

    static void apply(View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            style((TextView) root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(group.getChildAt(i));
            }
        }
    }

    private static void style(TextView v) {
        if (STYLED.contains(v)) return;
        STYLED.add(v);

        float density = v.getResources().getDisplayMetrics().density;
        float sp = density <= 0f ? 0f : v.getTextSize() / density;
        float target = sp;
        if (sp > 0f && sp < 8.5f) target = 9.5f;
        else if (sp < 10f) target = 10.5f;
        else if (sp < 11.5f) target = sp + 0.6f;
        if (target > sp + 0.05f) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, target);
        }

        int color = v.getCurrentTextColor();
        int alpha = Color.alpha(color);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        float luminance = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f;

        // Lift low-opacity light text and gently lighten colored captions.
        if (luminance > 0.25f && luminance < 0.9f && target < 14f) {
            float lift = 0.18f;
            red += Math.round((255 - red) * lift);
            green += Math.round((255 - green) * lift);
            blue += Math.round((255 - blue) * lift);
            int newAlpha = Math.max(alpha, 0xC0);
            int newColor = (newAlpha << 24) | (red << 16) | (green << 8) | blue;
            if (newColor != color) v.setTextColor(newColor);
        }

        if (luminance > 0.35f && target < 16f) {
            v.setShadowLayer(dp(density, 1.4f), 0f, dp(density, 0.7f), 0x8C000000);
        }
    }

    private static float dp(float density, float value) {
        return value * density;
    }
}
