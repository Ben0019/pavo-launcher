package com.ben.aurora;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

/**
 * ImageView that renders a live illustration for the four animated built-in
 * icons. Other apps keep the normal bitmap path, so this has no effect on
 * installed third-party icons.
 */
final class BuiltinIconView extends ImageView {
    private String pkg;
    private ValueAnimator anim;
    private float phase;
    private boolean attached;
    private boolean paused;

    BuiltinIconView(Context context) {
        super(context);
        setScaleType(ScaleType.FIT_CENTER);
    }

    void setBuiltInPackage(String packageName) {
        String next = LiveBuiltinIcons.supports(packageName) ? packageName : null;
        if (next == null ? pkg == null : next.equals(pkg)) {
            invalidate();
            return;
        }
        pkg = next;
        restart();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && attached) restart();
        else stop();
    }

    void setPaused(boolean value) {
        if (paused == value) return;
        paused = value;
        if (paused) stop();
        else restart();
    }

    private void restart() {
        stop();
        if (paused || !attached || pkg == null || getVisibility() != VISIBLE) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(LiveBuiltinIcons.duration(pkg));
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pkg == null) {
            super.onDraw(canvas);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int size = Math.min(w, h);
        float ox = (w - size) / 2f;
        float oy = (h - size) / 2f;
        int save = canvas.save();
        canvas.translate(ox, oy);
        LiveBuiltinIcons.draw(canvas, size, pkg, ThemeColors.accent(), phase);
        canvas.restoreToCount(save);
    }
}
