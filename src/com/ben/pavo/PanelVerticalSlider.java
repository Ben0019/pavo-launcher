package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Vertical twin of {@link PanelSlider}: thin track, accent fill rising from the
 * bottom and a round accent knob. Used for brightness and volume in the quick
 * panel so the pair can sit in a tall card.
 */
final class PanelVerticalSlider extends View {

    interface Listener {
        void onProgress(int percent, boolean released);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float trackW;
    private final float knobR;
    private Listener listener;
    private float progress;
    private boolean dragging;

    PanelVerticalSlider(Context c) {
        super(c);
        trackW = Ui.dpf(c, 4);
        knobR = Ui.dpf(c, 8);
        trackPaint.setColor(0x26FFFFFF);
        int accent = ThemeColors.accent();
        fillPaint.setColor(accent);
        knobPaint.setColor(accent);
        glowPaint.setColor((accent & 0x00FFFFFF) | 0x33000000);
        setClickable(true);
        setFocusable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Controller nudge: move by a few points and notify the listener live. */
    void nudge(int delta) {
        int p = Math.max(0, Math.min(100, progressPercent() + delta));
        progress = p / 100f;
        invalidate();
        if (listener != null) listener.onProgress(p, false);
    }

    void setProgress(int percent) {
        progress = Math.max(0f, Math.min(1f, percent / 100f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float cx = w / 2f;
        float inset = glowR() + Ui.dpf(getContext(), 0.5f);
        float span = Math.max(1f, h - inset * 2f);
        float y = inset + span * (1f - progress);

        rect.set(cx - trackW / 2f, inset, cx + trackW / 2f, h - inset);
        canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, trackPaint);
        rect.set(cx - trackW / 2f, Math.min(h - inset, y), cx + trackW / 2f, h - inset);
        canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, fillPaint);

        canvas.drawCircle(cx, y, glowR(), glowPaint);
        canvas.drawCircle(cx, y, knobR, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
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
                if (dragging && listener != null) listener.onProgress(progressPercent(), true);
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    int progressPercent() {
        return Math.round(progress * 100f);
    }

    private float glowR() {
        return knobR * 1.6f;
    }

    private void updateFromTouch(float y, boolean released) {
        float inset = glowR() + Ui.dpf(getContext(), 0.5f);
        float span = Math.max(1f, getHeight() - inset * 2f);
        progress = Math.max(0f, Math.min(1f, 1f - (y - inset) / span));
        invalidate();
        if (listener != null) listener.onProgress(progressPercent(), released);
    }
}
