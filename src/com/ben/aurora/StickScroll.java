package com.ben.aurora;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;

/** Accelerated right-stick vertical scrolling for launcher lists and settings. */
final class StickScroll {
    private StickScroll() {}

    static float rightY(MotionEvent event) {
        if (event == null) return 0f;
        float y = event.getAxisValue(MotionEvent.AXIS_RZ);
        if (Math.abs(y) < 0.12f) y = event.getAxisValue(MotionEvent.AXIS_RY);
        return Math.max(-1f, Math.min(1f, y));
    }

    static boolean active(float y) {
        return Math.abs(y) >= 0.22f;
    }

    /** Scroll a ScrollView/GridView/ListView at a speed driven by stick deflection. */
    static boolean scroll(View target, float y, long[] lastAt) {
        if (target == null || Math.abs(y) < 0.22f) return false;
        long now = android.os.SystemClock.uptimeMillis();
        float power = Math.min(1f, (Math.abs(y) - 0.18f) / 0.82f);
        long interval = 54L - Math.round(28f * power);
        if (now - lastAt[0] < interval) return true;
        lastAt[0] = now;

        float density = target.getResources().getDisplayMetrics().density;
        int distance = Math.round((7f + 42f * power * power) * density);
        int delta = y > 0f ? distance : -distance;
        if (target instanceof AbsListView) {
            AbsListView list = (AbsListView) target;
            list.scrollListBy(delta);
            return true;
        }
        if (target instanceof ScrollView) {
            ScrollView scroll = (ScrollView) target;
            if (scroll.canScrollVertically(y > 0f ? 1 : -1)) {
                scroll.scrollBy(0, delta);
            }
            return true;
        }
        return false;
    }

    /** Find the first scrollable ancestor of a focused/navigated view. */
    static View scrollParent(View child) {
        View current = child;
        while (current != null) {
            if (current instanceof AbsListView || current instanceof ScrollView) return current;
            if (!(current.getParent() instanceof View)) return null;
            current = (View) current.getParent();
        }
        return null;
    }
}
