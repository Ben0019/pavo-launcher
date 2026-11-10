package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/** Vertical band slider for the graphic EQ: boost/cut from the centre line. */
final class EqBandSlider extends View {
    interface Listener {
        void onLevel(int millibels, boolean released);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centrePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float trackW;
    private final float knobR;
    private final float touchHalfW;
    private Listener listener;
    private int min = -1200;
    private int max = 1200;
    private int level;
    private boolean dragging;

    EqBandSlider(Context c) {
        super(c);
        trackW = Ui.dpf(c, 4);
        knobR = Ui.dpf(c, 8);
        touchHalfW = Ui.dpf(c, 15);
        trackPaint.setColor(0x26FFFFFF);
        centrePaint.setColor(0x55FFFFFF);
        centrePaint.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
        int accent = ThemeColors.accent();
        fillPaint.setColor(accent);
        knobPaint.setColor(accent);
        glowPaint.setColor((accent & 0x00FFFFFF) | 0x33000000);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setAccent(int colour) {
        fillPaint.setColor(colour);
        knobPaint.setColor(colour);
        glowPaint.setColor((colour & 0x00FFFFFF) | 0x33000000);
        invalidate();
    }

    void setRange(int minLevel, int maxLevel) {
        min = minLevel;
        max = Math.max(min + 1, maxLevel);
    }

    void setLevel(int millibels) {
        level = Math.max(min, Math.min(max, millibels));
        invalidate();
    }

    int level() {
        return level;
    }

    void nudge(int delta) {
        if (!isEnabled()) return;
        int next = Math.max(min, Math.min(max, level + delta));
        if (next == level) return;
        level = next;
        invalidate();
        if (listener != null) listener.onLevel(level, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float cx = w / 2f;
        float inset = glowR() + Ui.dpf(getContext(), 0.5f);
        float span = Math.max(1f, h - inset * 2f);
        float norm = (level - min) / (float) Math.max(1, max - min);
        float y = inset + span * (1f - norm);
        float zero = inset + span * (1f - (0 - min) / (float) Math.max(1, max - min));

        rect.set(cx - trackW / 2f, inset, cx + trackW / 2f, h - inset);
        canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, trackPaint);
        rect.set(cx - trackW / 2f, Math.min(zero, y),
                cx + trackW / 2f, Math.max(zero, y));
        canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, fillPaint);
        canvas.drawLine(cx - trackW * 1.7f, zero, cx + trackW * 1.7f, zero, centrePaint);

        canvas.drawCircle(cx, y, glowR(), glowPaint);
        canvas.drawCircle(cx, y, knobR, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(event.getX() - getWidth() / 2f) > touchHalfW) return false;
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getY(), false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) updateFromTouch(event.getY(), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) updateFromTouch(event.getY(), true);
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging && listener != null) listener.onLevel(level, true);
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled()) return true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            nudge(50);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            nudge(-50);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float glowR() {
        return knobR * 1.6f;
    }

    private void updateFromTouch(float y, boolean released) {
        float inset = glowR() + Ui.dpf(getContext(), 0.5f);
        float span = Math.max(1f, getHeight() - inset * 2f);
        float norm = 1f - (y - inset) / span;
        norm = Math.max(0f, Math.min(1f, norm));
        int next = min + Math.round(norm * (max - min));
        next = Math.round(next / 10f) * 10; // 0.1 dB
        next = Math.max(min, Math.min(max, next));
        if (next == level) return;
        level = next;
        invalidate();
        if (listener != null) listener.onLevel(level, released);
    }
}
