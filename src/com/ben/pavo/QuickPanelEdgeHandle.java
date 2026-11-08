package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;

/**
 * Slim grab handle pinned to one screen edge. Swiping it inwards opens the
 * quick controls panel; long-pressing and dragging parks it on any edge.
 *
 * <p>Width, height, opacity, edge and slide position come from {@link Prefs}
 * and can be changed live from the Overlay &amp; Drawer settings.
 */
final class QuickPanelEdgeHandle {

    static final int EDGE_RIGHT = 0;
    static final int EDGE_LEFT = 1;
    static final int EDGE_TOP = 2;
    static final int EDGE_BOTTOM = 3;

    private static final long REOPEN_GUARD_MS = 350L;

    private static HandleView view;
    private static WindowManager windowManager;
    private static WindowManager.LayoutParams params;
    private static boolean attached;
    private static long lastOpenedAt;
    private static Context hostRef;

    private QuickPanelEdgeHandle() {}

    /** Temporarily remove the handle so it is not frozen into a screenshot. */
    static void hide() {
        detach();
    }

    static void show() {
        if (hostRef != null) attach(hostRef);
    }

    static void attach(final Context host) {
        if (attached || host == null) return;
        hostRef = host;
        Prefs prefs = new Prefs(host);
        if (!prefs.quickHandleVisible()) return;
        WindowManager wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        Context ui = Ui.adaptiveContext(host);

        HandleView handle = new HandleView(ui, new Runnable() {
            @Override
            public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastOpenedAt < REOPEN_GUARD_MS) return;
                lastOpenedAt = now;
                QuickPanelHost.show(host);
            }
        }, new HandleView.Mover() {
            @Override
            public void moveTo(float rawX, float rawY) {
                dragTo(rawX, rawY);
            }

            @Override
            public void dropAt(float rawX, float rawY) {
                snapTo(rawX, rawY);
            }
        });
        handle.applyPrefs(prefs);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("Pavo quick controls handle");
        layout(ui, prefs, lp);

        boolean added = false;
        if (PavoA11yService.hasAccessibility()) {
            added = PavoA11yService.addAccessibilityOverlay(handle, lp);
        }
        if (!added) {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                wm.addView(handle, lp);
                added = true;
            } catch (Throwable ignored) {
                added = false;
            }
        }
        if (!added) return;
        view = handle;
        windowManager = wm;
        params = lp;
        attached = true;
    }

    /** Re-applies size, opacity, position and visibility after a settings change. */
    static void refresh() {
        Context host = hostRef;
        if (host == null) return;
        Prefs prefs = new Prefs(host);
        if (!prefs.quickHandleVisible()) {
            detach();
            return;
        }
        if (view == null) {
            attach(host);
            return;
        }
        view.applyPrefs(prefs);
        if (windowManager != null && params != null) {
            layout(Ui.adaptiveContext(host), prefs, params);
            try {
                windowManager.updateViewLayout(view, params);
            } catch (Throwable ignored) {
            }
        }
    }

    static void detach() {
        HandleView v = view;
        WindowManager wm = windowManager;
        view = null;
        windowManager = null;
        params = null;
        attached = false;
        if (v == null) return;
        if (PavoA11yService.hasAccessibility()) {
            PavoA11yService.removeAccessibilityOverlay(v);
        } else if (wm != null) {
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Window size and slot for the configured edge. */
    private static void layout(Context c, Prefs prefs, WindowManager.LayoutParams lp) {
        float density = c.getResources().getDisplayMetrics().density;
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int thick = Math.round(Math.max(26, prefs.quickHandleWidth() + 20) * density);
        int length = Math.round((prefs.quickHandleHeight() + 40) * density);
        int edge = prefs.quickHandleEdge();
        lp.gravity = Gravity.TOP | Gravity.START;
        if (edge == EDGE_LEFT || edge == EDGE_RIGHT) {
            lp.width = thick;
            lp.height = Math.min(length, height);
            int span = Math.max(0, height - lp.height);
            lp.y = Math.round(prefs.quickHandlePos() / 1000f * span);
            lp.x = edge == EDGE_RIGHT ? width - thick : 0;
        } else {
            lp.width = Math.min(length, width);
            lp.height = thick;
            int span = Math.max(0, width - lp.width);
            lp.x = Math.round(prefs.quickHandlePos() / 1000f * span);
            lp.y = edge == EDGE_BOTTOM ? height - thick : 0;
        }
        if (view != null) view.setEdge(edge);
    }

    /** Called while the handle is held: follow the finger inside the display. */
    private static void dragTo(float rawX, float rawY) {
        if (view == null || params == null || windowManager == null) return;
        Context host = hostRef;
        if (host == null) return;
        DisplayMetrics dm = view.getResources().getDisplayMetrics();
        params.x = Math.round(clamp(rawX - params.width / 2f, 0, dm.widthPixels - params.width));
        params.y = Math.round(clamp(rawY - params.height / 2f, 0, dm.heightPixels - params.height));
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Throwable ignored) {
        }
    }

    /** Called on release: snap to the nearest edge and remember the slot. */
    private static void snapTo(float rawX, float rawY) {
        Context host = hostRef;
        if (host == null || view == null || params == null) return;
        Prefs prefs = new Prefs(host);
        DisplayMetrics dm = view.getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;

        float dLeft = rawX;
        float dRight = width - rawX;
        float dTop = rawY;
        float dBottom = height - rawY;
        float best = Math.min(Math.min(dLeft, dRight), Math.min(dTop, dBottom));
        int edge;
        if (best == dLeft) edge = EDGE_LEFT;
        else if (best == dRight) edge = EDGE_RIGHT;
        else if (best == dTop) edge = EDGE_TOP;
        else edge = EDGE_BOTTOM;

        prefs.setQuickHandleEdge(edge);
        if (edge == EDGE_LEFT || edge == EDGE_RIGHT) {
            int length = Math.round((prefs.quickHandleHeight() + 40)
                    * dm.density);
            length = Math.min(length, height);
            int span = Math.max(0, height - length);
            float frac = span <= 0 ? 0f : (rawY - length / 2f) / span;
            prefs.setQuickHandlePos(Math.round(clamp(frac, 0f, 1f) * 1000f));
        } else {
            int length = Math.round((prefs.quickHandleHeight() + 40)
                    * dm.density);
            length = Math.min(length, width);
            int span = Math.max(0, width - length);
            float frac = span <= 0 ? 0f : (rawX - length / 2f) / span;
            prefs.setQuickHandlePos(Math.round(clamp(frac, 0f, 1f) * 1000f));
        }
        view.setEdge(edge);
        layout(Ui.adaptiveContext(host), prefs, params);
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Throwable ignored) {
        }
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static final class HandleView extends View {

        interface Mover {
            void moveTo(float rawX, float rawY);

            void dropAt(float rawX, float rawY);
        }

        private static final long LONG_PRESS_MS = 320L;

        private final Runnable open;
        private final Mover mover;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final float density;
        private final Runnable longPressRun = new Runnable() {
            @Override
            public void run() {
                if (tracking && !moved) {
                    dragging = true;
                    Haptics.longPress(HandleView.this);
                }
            }
        };
        private int barWidthDp = 4;
        private int barHeightDp = 112;
        private int edge = EDGE_RIGHT;
        private float downX;
        private float downY;
        private boolean tracking;
        private boolean moved;
        private boolean fired;
        private boolean dragging;

        HandleView(Context c, Runnable open, Mover mover) {
            super(c);
            this.open = open;
            this.mover = mover;
            density = getResources().getDisplayMetrics().density;
            ThemeColors.initialize(c);
            setClickable(true);
            setContentDescription(Loc.s(c, "Quick Controls"));
        }

        void applyPrefs(Prefs prefs) {
            barWidthDp = prefs.quickHandleWidth();
            barHeightDp = prefs.quickHandleHeight();
            edge = prefs.quickHandleEdge();
            barPaint.setColor(ThemeColors.withAlpha(Math.round(
                    Math.max(0, Math.min(100, prefs.quickHandleAlpha())) * 2.55f)));
            invalidate();
        }

        void setEdge(int value) {
            if (edge == value) return;
            edge = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float thickness = barWidthDp * density;
            float length = barHeightDp * density;
            float gap = dp(3f);
            if (edge == EDGE_LEFT || edge == EDGE_RIGHT) {
                float barH = Math.min(h, length);
                float top = (h - barH) / 2f;
                if (edge == EDGE_RIGHT) {
                    float right = w - gap;
                    bar.set(right - thickness, top, right, top + barH);
                } else {
                    float left = gap;
                    bar.set(left, top, left + thickness, top + barH);
                }
            } else {
                float barW = Math.min(w, length);
                float left = (w - barW) / 2f;
                if (edge == EDGE_TOP) {
                    float top = gap;
                    bar.set(left, top, left + barW, top + thickness);
                } else {
                    float bottom = h - gap;
                    bar.set(left, bottom - thickness, left + barW, bottom);
                }
            }
            float radius = Math.min(thickness / 2f, dp(6f));
            canvas.drawRoundRect(bar, radius, radius, barPaint);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (Build.VERSION.SDK_INT >= 29 && w > 0 && h > 0) {
                // Keep the system back gesture from stealing the inward swipe.
                setSystemGestureExclusionRects(Collections.singletonList(
                        new Rect(0, 0, w, h)));
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    tracking = true;
                    moved = false;
                    fired = false;
                    dragging = false;
                    postDelayed(longPressRun, LONG_PRESS_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        mover.moveTo(event.getRawX(), event.getRawY());
                        return true;
                    }
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > dp(10f)) {
                        moved = true;
                        removeCallbacks(longPressRun);
                    }
                    if (moved && !fired) {
                        float inward;
                        if (edge == EDGE_RIGHT) inward = -dx;
                        else if (edge == EDGE_LEFT) inward = dx;
                        else if (edge == EDGE_TOP) inward = dy;
                        else inward = -dy;
                        if (inward >= dp(14f)) {
                            fired = true;
                            Haptics.click(this);
                            open.run();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPressRun);
                    if (dragging) {
                        dragging = false;
                        mover.dropAt(event.getRawX(), event.getRawY());
                    }
                    tracking = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPressRun);
                    dragging = false;
                    tracking = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(longPressRun);
            super.onDetachedFromWindow();
        }

        private float dp(float value) {
            return value * density;
        }
    }
}
