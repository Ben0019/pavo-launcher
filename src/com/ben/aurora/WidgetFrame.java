package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Wraps a placed widget. Taps open its app, a double tap enables edge resize mode,
 * a long press opens the widget menu, and dragging moves it.
 */
final class WidgetFrame extends FrameLayout {
    static final int EDGE_LEFT = 1;
    static final int EDGE_TOP = 2;
    static final int EDGE_RIGHT = 4;
    static final int EDGE_BOTTOM = 8;

    private static final long DOUBLE_TAP_MS = 300L;
    private static final long SINGLE_TAP_MS = 280L;

    interface Listener {
        void onTap(WidgetFrame frame);

        void onLongPress(WidgetFrame frame);

        void onLongPressReleased(WidgetFrame frame);

        void onDragStart(WidgetFrame frame);

        void onDragMove(WidgetFrame frame, float rawX, float rawY);

        void onRelease(WidgetFrame frame, float rawX, float rawY);

        void onDragCancel(WidgetFrame frame);

        void onResizeModeChanged(WidgetFrame frame, boolean enabled);

        void onResizeStart(WidgetFrame frame, float rawX, float rawY);

        void onResizeMove(WidgetFrame frame, float rawX, float rawY);

        void onResizeEnd(WidgetFrame frame, float rawX, float rawY);

        void onResizeCancel(WidgetFrame frame);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF edgeRect = new RectF();
    private float downX, downY;
    private float lastTapX, lastTapY;
    private long lastTapAt;
    private boolean pressed;
    private boolean longFired;
    private boolean dragging;
    private boolean resizing;
    private boolean resizeMode;
    private boolean resizeEnabled = true;
    private boolean touchPassthrough;
    private int resizeEdge;
    private int pendingResizeEdge;
    private int activeEdge;
    private Runnable singleTap;

    Listener listener;

    float touchX() {
        return downX;
    }

    float touchY() {
        return downY;
    }

    WidgetFrame(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClipChildren(false);
        setWillNotDraw(false);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Ui.dpf(c, 1.4f));
        handlePaint.setStyle(Paint.Style.FILL);
    }

    private void updateWidgetCard() {
        boolean active = resizeMode || resizing || dragging;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof AuroraHomeWidgetView) {
                ((AuroraHomeWidgetView) child).setCardVisible(active);
            }
        }
    }

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!pressed || !isAttachedToWindow()) return;
            longFired = true;
            cancelSingleTap();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            if (listener != null) listener.onLongPress(WidgetFrame.this);
        }
    };

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (touchPassthrough) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                pressed = true;
                longFired = false;
                dragging = false;
                pendingResizeEdge = 0;
                handler.removeCallbacks(longPress);
                if (resizeEnabled && resizeMode) {
                    int edge = edgeAt(e.getX(), e.getY());
                    if (edge != 0) {
                        // Do not resize on DOWN. A double-tap that starts on a
                        // handle must still be able to turn resize mode off.
                        pendingResizeEdge = edge;
                        if (activeEdge != edge) {
                            activeEdge = edge;
                            invalidate();
                        }
                        return true;
                    }
                }
                handler.postDelayed(longPress, 500L);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (resizing) return true;
                float dist = (float) Math.hypot(e.getRawX() - downX, e.getRawY() - downY);
                if (pendingResizeEdge != 0) {
                    if (dist > touchSlop) {
                        int edge = pendingResizeEdge;
                        pendingResizeEdge = 0;
                        beginResize(edge, downX, downY);
                    }
                    return true;
                }
                if (!longFired && dist > touchSlop) {
                    handler.removeCallbacks(longPress);
                }
                return longFired || dragging || resizing;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                pressed = false;
                pendingResizeEdge = 0;
                if (!resizing && activeEdge != 0) {
                    activeEdge = 0;
                    invalidate();
                }
                return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (touchPassthrough) return false;
        if (resizing) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (listener != null) listener.onResizeMove(this, e.getRawX(), e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (listener != null) listener.onResizeEnd(this, e.getRawX(), e.getRawY());
                    resizing = false;
                    resizeEdge = 0;
                    activeEdge = 0;
                    updateWidgetCard();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (listener != null) listener.onResizeCancel(this);
                    resizing = false;
                    resizeEdge = 0;
                    activeEdge = 0;
                    updateWidgetCard();
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
        if (pendingResizeEdge != 0 && !resizing) {
            float dist = (float) Math.hypot(e.getRawX() - downX, e.getRawY() - downY);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (dist > touchSlop) {
                        int edge = pendingResizeEdge;
                        pendingResizeEdge = 0;
                        beginResize(edge, downX, downY);
                        if (listener != null) {
                            listener.onResizeMove(this, e.getRawX(), e.getRawY());
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    pendingResizeEdge = 0;
                    pressed = false;
                    handleTap(e.getRawX(), e.getRawY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pendingResizeEdge = 0;
                    pressed = false;
                    return true;
                default:
                    return true;
            }
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    float dist = (float) Math.hypot(e.getRawX() - downX, e.getRawY() - downY);
                    if (!longFired && dist > touchSlop) {
                        handler.removeCallbacks(longPress);
                    }
                    if (longFired && dist > touchSlop) {
                        dragging = true;
                        updateWidgetCard();
                        if (listener != null) listener.onDragStart(this);
                    }
                }
                if (dragging && listener != null) {
                    listener.onDragMove(this, e.getRawX(), e.getRawY());
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                pressed = false;
                if (dragging) {
                    if (listener != null) listener.onRelease(this, e.getRawX(), e.getRawY());
                } else if (longFired) {
                    if (listener != null) listener.onLongPressReleased(this);
                } else {
                    handleTap(e.getRawX(), e.getRawY());
                }
                dragging = false;
                longFired = false;
                updateWidgetCard();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                pressed = false;
                if (dragging && listener != null) listener.onDragCancel(this);
                dragging = false;
                longFired = false;
                updateWidgetCard();
                return true;
        }
        return true;
    }

    int resizeEdge() {
        return resizeEdge;
    }

    void setResizeEnabled(boolean enabled) {
        resizeEnabled = enabled;
        if (!enabled) resizeMode = false;
        updateWidgetCard();
    }

    void setTouchPassthrough(boolean passthrough) {
        touchPassthrough = passthrough;
    }

    private void beginResize(int edge, float rawX, float rawY) {
        if (!resizeEnabled) return;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        resizing = true;
        updateWidgetCard();
        resizeEdge = edge;
        pendingResizeEdge = 0;
        pressed = false;
        longFired = false;
        dragging = false;
        handler.removeCallbacks(longPress);
        cancelSingleTap();
        if (listener != null) listener.onResizeStart(this, rawX, rawY);
        invalidate();
    }

    private void handleTap(float rawX, float rawY) {
        long now = SystemClock.uptimeMillis();
        if (now - lastTapAt <= DOUBLE_TAP_MS
                && Math.hypot(rawX - lastTapX, rawY - lastTapY) <= touchSlop * 2f) {
            lastTapAt = 0L;
            cancelSingleTap();
            if (!resizeEnabled) return;
            resizeMode = !resizeMode;
            updateWidgetCard();
            invalidate();
            Haptics.click(this);
            if (listener != null) listener.onResizeModeChanged(this, resizeMode);
            return;
        }
        lastTapAt = now;
        lastTapX = rawX;
        lastTapY = rawY;
        cancelSingleTap();
        // While resize handles are visible a single tap must not launch the
        // widget app. The second tap will turn resize mode off if it arrives
        // within the double-tap window; otherwise the tap is intentionally a
        // no-op so a slow double-tap cannot open the app underneath the handles.
        if (resizeMode) return;
        final long stamp = now;
        singleTap = new Runnable() {
            @Override
            public void run() {
                if (lastTapAt != stamp) return;
                lastTapAt = 0L;
                if (listener != null) listener.onTap(WidgetFrame.this);
            }
        };
        handler.postDelayed(singleTap, SINGLE_TAP_MS);
    }

    private void cancelSingleTap() {
        if (singleTap != null) {
            handler.removeCallbacks(singleTap);
            singleTap = null;
        }
    }

    private int edgeAt(float x, float y) {
        if (!resizeEnabled) return 0;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return 0;
        if (resizeMode) {
            // Once resize mode is armed, any drag on the card resizes from the
            // nearest corner. Nothing to aim for, and taps still toggle the mode.
            int edge = 0;
            edge |= x < w / 2f ? EDGE_LEFT : EDGE_RIGHT;
            edge |= y < h / 2f ? EDGE_TOP : EDGE_BOTTOM;
            return edge;
        }
        float mx = Math.min(Ui.dp(getContext(), 72), w * 0.48f);
        float my = Math.min(Ui.dp(getContext(), 72), h * 0.48f);
        int edge = 0;
        if (x <= mx) edge |= EDGE_LEFT;
        if (x >= w - mx) edge |= EDGE_RIGHT;
        if (y <= my) edge |= EDGE_TOP;
        if (y >= h - my) edge |= EDGE_BOTTOM;
        return edge;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!resizeMode && !resizing) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = Ui.dpf(getContext(), 18);
        edgeRect.set(0.7f, 0.7f, w - 0.7f, h - 0.7f);
        // A quiet plate makes the card legible while it is being arranged,
        // without changing the resting home screen.
        cardPaint.setColor(0x220B0E14);
        canvas.drawRoundRect(edgeRect, radius, radius, cardPaint);
        int accent = ThemeColors.accent();
        edgePaint.setColor(ColorTuning.withAlpha(accent, resizing ? 0xEE : 0x99));
        canvas.drawRoundRect(edgeRect, radius, radius, edgePaint);
        handlePaint.setColor(accent);
        float r = Ui.dpf(getContext(), 6.0f);
        float inset = Ui.dpf(getContext(), 2f);
        float hot = Ui.dpf(getContext(), 9.5f);
        int[] corners = {EDGE_LEFT | EDGE_TOP, EDGE_RIGHT | EDGE_TOP,
                EDGE_LEFT | EDGE_BOTTOM, EDGE_RIGHT | EDGE_BOTTOM};
        float[] xs = {inset, w - inset, inset, w - inset};
        float[] ys = {inset, inset, h - inset, h - inset};
        for (int i = 0; i < corners.length; i++) {
            boolean on = (activeEdge & corners[i]) == corners[i];
            canvas.drawCircle(xs[i], ys[i], on ? hot : r, handlePaint);
            if (on) {
                handlePaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(xs[i], ys[i], hot * 0.42f, handlePaint);
                handlePaint.setColor(accent);
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(longPress);
        cancelSingleTap();
        resizing = false;
        pendingResizeEdge = 0;
        super.onDetachedFromWindow();
    }
}
