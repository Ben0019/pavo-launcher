package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** Touch-driven HSV colour wheel with a saturation radius and hue angle. */
final class ColorWheelView extends View {

    interface Listener {
        void onColorChanged(int color);
    }

    private final Paint wheelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorInner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] hues = new int[13];

    private float hue;
    private float saturation = 1f;
    private Listener listener;
    private long lastHaptic;

    ColorWheelView(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("RGB color wheel. Touch, D-pad or right stick to choose hue and saturation.");
        for (int i = 0; i < hues.length; i++) {
            hues[i] = Color.HSVToColor(new float[]{i * 360f / (hues.length - 1), 1f, 1f});
        }
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0x55FFFFFF);
        selectorPaint.setStyle(Paint.Style.STROKE);
        selectorPaint.setStrokeWidth(Ui.dpf(c, 3));
        selectorPaint.setColor(0xFFFFFFFF);
        selectorInner.setStyle(Paint.Style.FILL);
        selectorInner.setColor(0x66000000);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setHueSaturation(float hue, float saturation) {
        this.hue = ((hue % 360f) + 360f) % 360f;
        this.saturation = Math.max(0f, Math.min(1f, saturation));
        invalidate();
    }

    float hue() {
        return hue;
    }

    float saturation() {
        return saturation;
    }

    int color() {
        return Color.HSVToColor(new float[]{hue, saturation, 1f});
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - Ui.dpf(getContext(), 8);
        if (radius <= 1f) return;

        wheelPaint.setShader(new SweepGradient(cx, cy, hues, null));
        canvas.drawCircle(cx, cy, radius, wheelPaint);
        whitePaint.setShader(new RadialGradient(cx, cy, radius,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, whitePaint);
        whitePaint.setShader(null);
        wheelPaint.setShader(null);

        ringPaint.setStrokeWidth(Math.max(1f, Ui.dpf(getContext(), 1.5f)));
        canvas.drawCircle(cx, cy, radius + Ui.dpf(getContext(), 2), ringPaint);

        // Controller focus is a circular navigation ring, not a rectangular
        // platform highlight. It sits just outside the physical colour wheel.
        if (isFocused()) {
            float focusRadius = radius + Ui.dpf(getContext(), 5);
            focusPaint.setStrokeWidth(Ui.dpf(getContext(), 4.5f));
            focusPaint.setColor(0x66000000);
            canvas.drawCircle(cx, cy, focusRadius, focusPaint);
            focusPaint.setStrokeWidth(Ui.dpf(getContext(), 2.5f));
            focusPaint.setColor(0xFF8BE9FF);
            canvas.drawCircle(cx, cy, focusRadius, focusPaint);
        }

        double angle = Math.toRadians(hue);
        float sx = cx + (float) Math.cos(angle) * radius * saturation;
        float sy = cy + (float) Math.sin(angle) * radius * saturation;
        float thumb = Ui.dpf(getContext(), 11);
        selectorInner.setColor(0x50000000);
        canvas.drawCircle(sx, sy, thumb + Ui.dpf(getContext(), 1.5f), selectorInner);
        canvas.drawCircle(sx, sy, thumb, selectorPaint);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused) Haptics.tick(this);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!isFocused()) requestFocus();
                // fall through
            case MotionEvent.ACTION_MOVE: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                float w = getWidth();
                float h = getHeight();
                float cx = w / 2f;
                float cy = h / 2f;
                float radius = Math.max(1f, Math.min(w, h) / 2f - Ui.dpf(getContext(), 8));
                float dx = e.getX() - cx;
                float dy = e.getY() - cy;
                float dist = (float) Math.hypot(dx, dy);
                saturation = Math.max(0f, Math.min(1f, dist / radius));
                hue = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (hue < 0f) hue += 360f;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastHaptic >= 70L) {
                    lastHaptic = now;
                    Haptics.tick(this);
                }
                invalidate();
                if (listener != null) listener.onColorChanged(color());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) Haptics.click(this);
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
