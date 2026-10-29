package com.ben.aurora;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.graphics.Point;
import android.view.Display;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

final class Ui {
    private Ui() {}

    /**
     * Keep logical UI size proportional when the logical display resolution or
     * aspect ratio changes. Landscape uses the short side as the reference;
     * portrait preserves the launcher's usable width instead of squeezing the
     * header and dock into a narrow phone-width column.
     */
    /** Column count for card grids, derived from the current screen width. */
    static int columns(Context c, int max) {
        if (c == null) return Math.max(1, max);
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = dm.density > 0 ? Math.round(dm.widthPixels / dm.density) : 0;
        if (w >= 720) return Math.min(3, Math.max(1, max));
        if (w >= 460) return Math.min(2, Math.max(1, max));
        return 1;
    }

    /**
     * Narrowest aspect the launcher is designed for: the Retroid Pocket Nova's
     * 1280x960 4:3 panel. Anything narrower is laid out as 4:3 instead of being
     * squeezed further.
     */
    static final float MIN_ASPECT = 4f / 3f;

    static int heightDp(Context c) {
        if (c == null) return 0;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return dm.density > 0 ? Math.round(dm.heightPixels / dm.density) : 0;
    }

    /** Height available to the layout, clamped to the 4:3 design floor. */
    static int layoutHeightDp(Context c) {
        int w = widthDp(c);
        int h = heightDp(c);
        if (w > 0) {
            int floor = Math.round(w / MIN_ASPECT);
            if (h > floor) h = floor;
        }
        return h;
    }

    static int widthDp(Context c) {
        if (c == null) return 0;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return dm.density > 0 ? Math.round(dm.widthPixels / dm.density) : 0;
    }

    /** Spacing/text scale for compact or very wide screens. */
    static float densityScale(Context c) {
        if (c == null) return 1f;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = dm.density > 0 ? Math.round(dm.widthPixels / dm.density) : 800;
        return Math.max(0.86f, Math.min(1.16f, w / 800f));
    }

    static Context adaptiveContext(Context base) {
        if (base == null) return null;
        try {
            DisplayManager dm = (DisplayManager) base.getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return base;
            Point size = new Point();
            display.getRealSize(size);
            if (size.x <= 0 || size.y <= 0) return base;

            int nativeLong = 0;
            int nativeShort = 0;
            try {
                for (Display.Mode mode : display.getSupportedModes()) {
                    int mw = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                    int mh = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                    nativeLong = Math.max(nativeLong, mw);
                    nativeShort = Math.max(nativeShort, mh);
                }
            } catch (Throwable ignored) {
            }
            if (nativeLong <= 0 || nativeShort <= 0) {
                nativeLong = Math.max(size.x, size.y);
                nativeShort = Math.min(size.x, size.y);
            }
            // The longest native dimension is the portrait-width reference too.
            int nativeWidth = Math.max(nativeShort, nativeLong);

            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            int baseDpi = base.getResources().getDisplayMetrics().densityDpi;
            float densityScale;
            if (cfg.orientation == Configuration.ORIENTATION_PORTRAIT) {
                densityScale = size.x / (float) Math.max(1, nativeWidth);
            } else {
                densityScale = Math.min(size.x, size.y) / (float) nativeShort;
            }
            int scaledDpi = Math.round(baseDpi * densityScale);
            // Keep at least 820dp of width so multi-column cards, headers and
            // dialogs always fit; narrower or portrait panels scale instead of
            // overlapping.
            int widthDpi = Math.round(size.x * 160f / 820f);
            scaledDpi = Math.min(scaledDpi, widthDpi);
            scaledDpi = Math.max(140, Math.min(640, scaledDpi));
            if (scaledDpi != baseDpi) {
                cfg.densityDpi = scaledDpi;
                return base.createConfigurationContext(cfg);
            }
        } catch (Throwable ignored) {
        }
        return base;
    }

    static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    /**
     * Ask the compositor to blur the real content behind a window. Unlike a captured
     * bitmap this follows the wallpaper, icons and widgets as they actually are, at
     * their real screen positions, and keeps updating while the window is visible.
     */
    static void blurWindow(Window window, int radiusDp) {
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        if (Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(Math.max(1, dp(window.getContext(), radiusDp)));
        }
    }

    static void blurWindow(WindowManager.LayoutParams lp, Context c, int radiusDp) {
        if (lp == null || c == null) return;
        lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (Build.VERSION.SDK_INT >= 31) {
            lp.setBlurBehindRadius(Math.max(1, dp(c, radiusDp)));
        }
    }

    static float dpf(Context c, float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable rounded(int color, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable d = rounded(color, radiusPx);
        if (strokePx > 0) d.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        return d;
    }

    /** Dark selection plate with the wallpaper-derived launcher accent. */
    static Drawable focusRing(Context c, float radiusPx) {
        FocusRingDrawable ring = new FocusRingDrawable(c, ThemeColors.accent());
        ring.setRingRadius(radiusPx);
        ring.setPlateColor(0x66101418);
        return ring;
    }

    /** Press-only plate; unlike RippleDrawable it has no focus-state overlay. */
    static Drawable pressHighlight(Context c, float radiusPx) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(0x26FFFFFF, radiusPx));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    static Drawable ripple(Context c, float radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    static void setPadding(View v, Context c, float l, float t, float r, float b) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }
}
