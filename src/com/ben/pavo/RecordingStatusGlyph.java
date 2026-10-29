package com.ben.pavo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Header recording status: a static idle mark and a pulsing live dot. */
final class RecordingStatusGlyph extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean active;
    private boolean attached;
    private float phase;
    private ValueAnimator animator;

    RecordingStatusGlyph(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        if (active) start(); else stop();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (active) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && attached && active) start();
        else stop();
    }

    private void start() {
        if (!attached || animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.max(1.5f, Math.min(w, h) * 0.225f);
        int red = ColorTuning.colour(0xFFFF3D3D);

        if (!active) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.2f, density * 1.15f));
            paint.setColor(ColorTuning.withAlpha(0xB3FFFFFF,
                    ColorTuning.glowAlpha(0xB3)));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xB3FFFFFF);
            canvas.drawCircle(cx, cy, Math.max(1.1f, radius * 0.55f), paint);
            return;
        }

        float pulse = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorTuning.withAlpha(red, Math.round(0x24 + 0x38 * pulse)));
        canvas.drawCircle(cx, cy, radius * (1.6f + 0.65f * pulse), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.1f, density * 1.05f));
        paint.setColor(ColorTuning.withAlpha(red, Math.round(0x55 + 0x55 * (1f - pulse))));
        canvas.drawCircle(cx, cy, radius * (1.25f + 0.75f * (1f - pulse)), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(red);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(ColorTuning.withAlpha(0xFFFFFFFF,
                ColorTuning.glowAlpha(0x99)));
        canvas.drawCircle(cx - radius * 0.28f, cy - radius * 0.28f,
                Math.max(0.8f, radius * 0.28f), paint);
    }
}
