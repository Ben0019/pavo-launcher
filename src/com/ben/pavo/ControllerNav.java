package com.ben.pavo;

import android.app.Activity;
import android.content.Context;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared controller navigation for the Stress Test activity, result screens and
 * dialogs. The left stick and D-pad move focus; only the right stick adjusts
 * sliders. One physical press produces at most one focus move, even when the
 * controller firmware emits both a D-pad key and a synthetic stick axis.
 */
final class ControllerNav {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View root;

    private final Set<Integer> heldDpad = new HashSet<>();
    private int pendingDpadDir = -1;
    private Runnable pendingDpadTask;
    private long lastDpadAt;
    private long lastFocusAt;
    private long lastStickAt;
    private long lastStickMotionAt;
    private boolean stickActive;
    private boolean stickEngaged;
    private static long externalDpadAt;

    private final long[] sliderStickAt = {0L};
    private SeekBar sliderBar;
    private float sliderX;
    private boolean sliderScheduled;
    private boolean rightStickHeld;
    private final Runnable sliderRepeat = new Runnable() {
        @Override public void run() {
            sliderScheduled = false;
            if (!rightStickHeld) return;
            View focus = currentFocus();
            if (!(focus instanceof SeekBar)) {
                stopSlider();
                return;
            }
            SeekBar bar = (SeekBar) focus;
            if (!SliderStick.active(sliderX) || !bar.isEnabled()) {
                stopSlider();
                return;
            }
            if (sliderBar != bar) {
                SliderStick.reset(sliderBar);
                sliderBar = bar;
            }
            SliderStick.adjust(bar, sliderX, sliderStickAt, bar);
            scheduleSlider();
        }
    };

    ControllerNav(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
    }

    void setRoot(View root) {
        this.root = root;
        cancel();
    }

    /** Stop repeating adjustment but keep the navigator usable after resume. */
    void cancel() {
        stopSlider();
        heldDpad.clear();
        pendingDpadDir = -1;
        if (pendingDpadTask != null) {
            handler.removeCallbacks(pendingDpadTask);
            pendingDpadTask = null;
        }
        stickEngaged = false;
    }

    /** Permanently detach (activity destruction). */
    void release() {
        cancel();
        handler.removeCallbacksAndMessages(null);
    }

    void focusFirst() {
        View focus = findFirst(root);
        if (focus != null) focusView(focus);
    }

    private View currentFocus() {
        if (root != null) {
            View f = root.findFocus();
            if (f != null) return f;
        }
        return activity.getCurrentFocus();
    }

    /**
     * Root container for cards and dialogs. Key events are intercepted here
     * before the focused child can apply Android's default focus navigation,
     * otherwise a single D-pad press can move twice (once by the framework and
     * once by this navigator). Activities get the same guarantee by overriding
     * dispatchKeyEvent; dialogs need this explicit first chance.
     */
    interface KeyInterceptor {
        boolean onKey(KeyEvent event);
    }

    static final class RootLayout extends FrameLayout {
        private ControllerNav navigator;
        private KeyInterceptor keyInterceptor;

        RootLayout(Context context) {
            super(context);
        }

        void setNavigator(ControllerNav navigator) {
            this.navigator = navigator;
        }

        void setKeyInterceptor(KeyInterceptor interceptor) {
            this.keyInterceptor = interceptor;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (keyInterceptor != null && keyInterceptor.onKey(event)) return true;
            if (navigator != null && navigator.dispatchKeyEvent(event)) return true;
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (navigator != null && navigator.dispatchGenericMotionEvent(event)) return true;
            return super.dispatchGenericMotionEvent(event);
        }
    }

    private View findFirst(View view) {
        if (view == null) return null;
        if (view != root && navigable(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirst(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Left-stick movement is also delivered by Android as synthetic DPAD keys
     * (scan code 0, joystick source). Callers that do their own DPAD handling
     * must ignore those and let the analog path move focus once per deflection.
     */
    static boolean isSyntheticStickDpad(KeyEvent event) {
        return isDirectionKey(event) && event.getScanCode() == 0
                && (event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) != 0
                && !RootlessRemap.isVirtualEvent(event);
    }

    static boolean isDirectionKey(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** Tell shared analog navigation that a custom D-pad handler just moved focus. */
    static void noteExternalDpad(KeyEvent event) {
        if (isDirectionKey(event)) externalDpadAt = SystemClock.uptimeMillis();
    }

    static boolean externalDpadRecent() {
        return SystemClock.uptimeMillis() - externalDpadAt < 250L;
    }

    private static boolean navigable(View view) {
        return view != null && view.getVisibility() == View.VISIBLE
                && view.isEnabled() && view.isFocusable()
                && !(view instanceof android.widget.ScrollView)
                && !(view instanceof android.widget.HorizontalScrollView)
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private void focusView(View view) {
        if (view == null) return;
        view.requestFocus();
        view.post(new Runnable() {
            @Override public void run() {
                if (view.isFocused()) {
                    view.requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), false);
                }
            }
        });
    }

    boolean dispatchKeyEvent(KeyEvent event) {
        // The left stick can arrive as synthetic D-pad keys as well as axes.
        // Let the analog path own it so one deflection produces one move.
        if (isSyntheticStickDpad(event)) return true;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = down && event.getRepeatCount() > 0;

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            return false;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && !repeat) {
                View focus = currentFocus();
                if (focus != null && !(focus instanceof SeekBar)) {
                    focus.performClick();
                }
            }
            return true;
        }

        int dir = direction(code);
        if (dir < 0) return false;
        long now = SystemClock.uptimeMillis();
        lastDpadAt = now;
        if (!down) {
            heldDpad.remove(code);
            if (pendingDpadDir == dir && pendingDpadTask != null && heldDpad.isEmpty()) {
                handler.removeCallbacks(pendingDpadTask);
                pendingDpadTask = null;
                pendingDpadDir = -1;
                performMove(dir);
            }
            return true;
        }
        if (repeat) return true;
        // If the firmware sent this DPAD event together with left-stick axes,
        // treat the whole deflection as one gesture. Otherwise the analog path
        // will move focus a second time after the D-pad move.
        if (stickActive && now - lastStickMotionAt < 220L) stickEngaged = true;
        heldDpad.add(code);
        int horizontal = heldDpad.contains(KeyEvent.KEYCODE_DPAD_LEFT) ? 0
                : heldDpad.contains(KeyEvent.KEYCODE_DPAD_RIGHT) ? 1 : -1;
        int vertical = heldDpad.contains(KeyEvent.KEYCODE_DPAD_UP) ? 2
                : heldDpad.contains(KeyEvent.KEYCODE_DPAD_DOWN) ? 3 : -1;
        if (horizontal >= 0 && vertical >= 0) {
            if (pendingDpadTask != null) handler.removeCallbacks(pendingDpadTask);
            pendingDpadTask = null;
            pendingDpadDir = -1;
            if (now - lastFocusAt >= 160L) {
                moveDiagonal(horizontal, vertical);
            }
        } else if (pendingDpadTask == null) {
            pendingDpadDir = dir;
            pendingDpadTask = new Runnable() {
                @Override public void run() {
                    if (pendingDpadDir < 0 || heldDpad.isEmpty()) return;
                    int move = pendingDpadDir;
                    pendingDpadDir = -1;
                    pendingDpadTask = null;
                    if (SystemClock.uptimeMillis() - lastStickAt < 250L) return;
                    performMove(move);
                }
            };
            handler.postDelayed(pendingDpadTask, 180L);
        }
        return true;
    }

    boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!SliderStick.isGamepad(event)
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        long now = SystemClock.uptimeMillis();

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float leftX = event.getAxisValue(MotionEvent.AXIS_X);
        float leftY = event.getAxisValue(MotionEvent.AXIS_Y);
        float x = Math.abs(hatX) > Math.abs(leftX) ? hatX : leftX;
        float y = Math.abs(hatY) > Math.abs(leftY) ? hatY : leftY;
        final float threshold = 0.62f;
        final float release = 0.38f;
        boolean active = Math.abs(x) >= threshold || Math.abs(y) >= threshold;
        stickActive = active;
        if (active) lastStickMotionAt = now;

        View focus = currentFocus();
        boolean sliderActive = false;
        if (focus instanceof SeekBar) {
            float rx = SliderStick.rightX(event);
            if (SliderStick.active(rx)) {
                startSlider((SeekBar) focus, rx);
                sliderActive = true;
            } else {
                stopSlider();
            }
        } else {
            stopSlider();
        }

        if (!active) {
            if (Math.abs(x) < release && Math.abs(y) < release) {
                stickEngaged = false;
                lastStickAt = 0L;
                stickActive = false;
            }
            return sliderActive;
        }
        // A D-pad press can also emit synthetic analogue axes. Ignore every
        // analogue axis briefly so a D-pad key can never adjust a slider or
        // move focus a second time.
        if (now - externalDpadAt < 250L
                || now - lastDpadAt < 250L) {
            stickEngaged = true;
            return true;
        }
        if (stickEngaged) return true;
        if (now - lastFocusAt < 160L) {
            stickEngaged = true;
            return true;
        }
        stickEngaged = true;
        lastStickAt = now;

        boolean diagonal = Math.abs(x) >= threshold && Math.abs(y) >= threshold;
        if (diagonal) {
            moveDiagonal(x > 0f ? 1 : 0, y > 0f ? 3 : 2);
        } else if (Math.abs(x) >= Math.abs(y)) {
            performMove(x > 0f ? 1 : 0);
        } else {
            performMove(y > 0f ? 3 : 2);
        }
        return true;
    }

    private void startSlider(SeekBar bar, float x) {
        if (bar == null) return;
        if (sliderBar != bar) {
            SliderStick.reset(sliderBar);
            sliderBar = bar;
        }
        sliderX = x;
        rightStickHeld = true;
        scheduleSlider();
    }

    private void scheduleSlider() {
        if (sliderScheduled) return;
        sliderScheduled = true;
        handler.post(sliderRepeat);
    }

    private void stopSlider() {
        rightStickHeld = false;
        sliderScheduled = false;
        handler.removeCallbacks(sliderRepeat);
        SliderStick.reset(sliderBar);
        sliderBar = null;
    }

    private void performMove(int dir) {
        View focus = currentFocus();
        if (focus == null || !navigable(focus)) {
            focusFirst();
            return;
        }
        int direction = dir == 0 ? View.FOCUS_LEFT
                : dir == 1 ? View.FOCUS_RIGHT
                : dir == 2 ? View.FOCUS_UP : View.FOCUS_DOWN;
        View next = null;
        if (root instanceof ViewGroup) {
            next = android.view.FocusFinder.getInstance()
                    .findNextFocus((ViewGroup) root, focus, direction);
        }
        if (next == null) next = focus.focusSearch(direction);
        if (next != null && next != focus && navigable(next)) {
            lastFocusAt = SystemClock.uptimeMillis();
            focusView(next);
        }
    }

    private void moveDiagonal(int horizontalDir, int verticalDir) {
        View focus = currentFocus();
        if (focus == null || root == null) return;
        int[] currentLoc = new int[2];
        focus.getLocationOnScreen(currentLoc);
        float cx = currentLoc[0] + focus.getWidth() / 2f;
        float cy = currentLoc[1] + focus.getHeight() / 2f;
        float dxDir = horizontalDir == 1 ? 1f : -1f;
        float dyDir = verticalDir == 3 ? 1f : -1f;
        List<View> all = new ArrayList<>();
        collectNavigable(root, all);
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (View v : all) {
            if (v == focus) continue;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float dx = loc[0] + v.getWidth() / 2f - cx;
            float dy = loc[1] + v.getHeight() / 2f - cy;
            if (dx * dxDir < 4f || dy * dyDir < 4f) continue;
            float projection = dx * dxDir + dy * dyDir;
            float perpendicular = Math.abs(dx * dyDir - dy * dxDir);
            float score = projection + perpendicular * 1.35f;
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) {
            lastFocusAt = SystemClock.uptimeMillis();
            focusView(best);
        }
    }

    private void collectNavigable(View view, List<View> out) {
        if (view == null) return;
        if (view != root && navigable(view)) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectNavigable(group.getChildAt(i), out);
            }
        }
    }

    /**
     * Give every focusable control a visible accent outline. The Stress Test
     * screens use custom backgrounds, so Android's default focus highlight is
     * otherwise invisible.
     */
    void decorateFocusables() {
        decorate(root);
    }

    private void decorate(View view) {
        if (view == null) return;
        if (view != root && navigable(view)) {
            // A card opened after a touch is still in touch mode. Controller
            // focus must be able to land immediately instead of waiting for
            // the first hardware key press to leave touch mode.
            view.setFocusableInTouchMode(true);
        }
        if (view != root && navigable(view) && view.getForeground() == null) {
            view.setDefaultFocusHighlightEnabled(false);
            StateListDrawable states = new StateListDrawable();
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(0x00000000);
            int d = Math.max(1, Math.round(activity.getResources()
                    .getDisplayMetrics().density * 1.5f));
            focused.setStroke(d, 0xE6FFFFFF);
            focused.setCornerRadius(activity.getResources()
                    .getDisplayMetrics().density * 12f);
            states.addState(new int[]{android.R.attr.state_focused}, focused);
            states.addState(new int[]{}, new ColorDrawable(0x00000000));
            view.setForeground(states);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                decorate(group.getChildAt(i));
            }
        }
    }

    /** Wire a dialog's key events into this navigator after it is shown. */
    void attachDialog(final Dialog dialog, final View panel) {
        final View dialogRoot = dialog.getWindow() == null ? null
                : dialog.getWindow().getDecorView();
        setRoot(dialogRoot);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                return dispatchKeyEvent(event);
            }
        });
        if (panel != null) {
            panel.setOnGenericMotionListener(new View.OnGenericMotionListener() {
                @Override public boolean onGenericMotion(View v, MotionEvent event) {
                    return dispatchGenericMotionEvent(event);
                }
            });
        }
        if (dialogRoot != null) {
            dialogRoot.post(new Runnable() {
                @Override public void run() {
                    decorateFocusables();
                    attachGeneric(dialogRoot);
                }
            });
        }
    }

    private void attachGeneric(View view) {
        if (view == null) return;
        if (view != root && navigable(view)) {
            view.setOnGenericMotionListener(new View.OnGenericMotionListener() {
                @Override public boolean onGenericMotion(View v, MotionEvent event) {
                    return dispatchGenericMotionEvent(event);
                }
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                attachGeneric(group.getChildAt(i));
            }
        }
    }

    private static int direction(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) return 0;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return 1;
        if (code == KeyEvent.KEYCODE_DPAD_UP) return 2;
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) return 3;
        return -1;
    }


}
