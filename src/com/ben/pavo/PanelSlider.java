package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Horizontal slider in the launcher's language: thin translucent track, accent
 * fill and a round accent knob with a soft glow. Used by the quick panel for
 * brightness and volume.
 */
final class PanelSlider extends View {

    interface Listener {
        void onProgress(int percent, boolean released);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float trackH;
    private final float knobR;
    private Listener listener;
    private float progress;
    private boolean dragging;

    PanelSlider(Context c) {
        super(c);
        trackH = Ui.dpf(c, 4);
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

    void setProgress(int percent) {
        progress = Math.max(0f, Math.min(1f, percent / 100f));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float cy = h / 2f;
        float inset = knobR + Ui.dpf(getContext(), 2);
        float span = Math.max(1f, w - inset * 2f);
        float x = inset + span * progress;

        rect.set(inset, cy - trackH / 2f, w - inset, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, trackPaint);
        rect.set(inset, cy - trackH / 2f, Math.max(inset, x), cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fillPaint);

        canvas.drawCircle(x, cy, knobR * 1.9f, glowPaint);
        canvas.drawCircle(x, cy, knobR, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event.getX(), false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) updateFromTouch(event.getX(), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) updateFromTouch(event.getX(), true);
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

    private void updateFromTouch(float x, boolean released) {
        float inset = knobR + Ui.dpf(getContext(), 2);
        float span = Math.max(1f, getWidth() - inset * 2f);
        progress = Math.max(0f, Math.min(1f, (x - inset) / span));
        invalidate();
        if (listener != null) listener.onProgress(progressPercent(), released);
    }
}
