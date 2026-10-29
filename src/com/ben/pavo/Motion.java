package com.ben.pavo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import java.util.WeakHashMap;

/** Small shared spring helpers for cards, menus and app icons. */
final class Motion {

    private static final WeakHashMap<View, ValueAnimator> GIGGLES = new WeakHashMap<>();

    private Motion() {}

    /** Stop a running giggle before another transform takes over the card. */
    static void cancelGiggle(View v) {
        if (v == null) return;
        ValueAnimator anim = GIGGLES.remove(v);
        if (anim != null) anim.cancel();
    }

    /**
     * Springy scale-only pop. The pivot is centred so a card grows out of its own
     * middle instead of its top-left corner.
     */
    static void bounceScale(View v, float fromScale, long duration, float tension) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        v.animate().cancel();
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setInterpolator(new OvershootInterpolator(Math.max(0.1f, tension)))
                .setDuration(Math.max(80L, duration))
                .start();
    }

    /**
     * Menu-card pop with a damped side-to-side giggle. The card has already been
     * unfolded by the Magic Lamp overlay; this is the little overshoot/wobble that
     * lands it in place.
     */
    static void giggleIn(View v, float fromScale, long duration, float tension) {
        giggleIn(v, fromScale, duration, tension, 1.15f, false);
    }

    static void giggleIn(View v, float fromScale, long duration, float tension,
                         float degrees, boolean fadeIn) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        cancelGiggle(v);
        v.animate().cancel();
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
        v.setRotation(0f);
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        v.setAlpha(fadeIn ? 0f : 1f);
        final OvershootInterpolator spring =
                new OvershootInterpolator(Math.max(0.1f, tension));
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Math.max(100L, duration));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                float pop = spring.getInterpolation(t);
                float scale = fromScale + (1f - fromScale) * pop;
                v.setScaleX(scale);
                v.setScaleY(scale);
                float wobble = (float) Math.sin(t * Math.PI * 3.0)
                        * degrees * (1f - t);
                v.setRotation(wobble);
                if (fadeIn) v.setAlpha(Math.min(1f, t / 0.18f));
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                GIGGLES.remove(v);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setRotation(0f);
                v.setAlpha(1f);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                GIGGLES.remove(v);
            }
        });
        GIGGLES.put(v, anim);
        Haptics.giggle(v);
        anim.start();
    }

    /** Bouncy fade/scale-in used when a dialog card first appears. */
    static void bounceIn(View v, float fromScale, long duration, float tension) {
        if (v == null) return;
        v.animate().cancel();
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
        v.setScaleX(fromScale);
        v.setScaleY(fromScale);
        v.setAlpha(0f);
        v.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setInterpolator(new OvershootInterpolator(Math.max(0.1f, tension)))
                .setDuration(Math.max(80L, duration))
                .start();
    }
}
