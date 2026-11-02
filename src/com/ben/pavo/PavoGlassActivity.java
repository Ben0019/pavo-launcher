package com.ben.pavo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Base for the inbuilt Pavo apps. Their windows are translucent and ask the
 * compositor for a real-time blur behind them, so the glass cards show the actual
 * wallpaper, icons and widgets instead of a static wallpaper image.
 *
 * Closing goes through a short window fade/scale. The previous code disabled all
 * window animations to make the tuner appear instantly, which also removed the exit
 * animation and made B feel like an abrupt jump back to the home screen.
 */
abstract class PavoGlassActivity extends Activity {

    static final String EXTRA_RETURN_LEFT = "aurora_return_left";
    static final String EXTRA_RETURN_TOP = "aurora_return_top";
    static final String EXTRA_RETURN_RIGHT = "aurora_return_right";
    static final String EXTRA_RETURN_BOTTOM = "aurora_return_bottom";

    private boolean closing;
    private boolean liquidCard;
    /** The close froze a liquid card so it could shrink to the icon. */
    private boolean returnWasLiquid;
    private boolean perfOverlayHidden;
    private boolean hasReturnTarget;
    private int returnLeft;
    private int returnTop;
    private int returnRight;
    private int returnBottom;
    private boolean returning;
    private boolean returnFinished;
    private ValueAnimator returnAnim;
    private final Runnable readableTask = new Runnable() {
        @Override
        public void run() {
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor != null && !isFinishing() && !isDestroyed()) {
                ReadableText.apply(decor);
            }
        }
    };

    /** Live glass samples the actual app behind the window instead of a bitmap. */
    protected boolean usesLiveBackdrop() { return false; }

    /** Built-ins can opt out when their typography has already been tuned. */
    protected boolean readableTextPass() { return true; }

    /**
     * Whether the card samples a capture of the launcher behind it. A full-bleed
     * information sheet gains nothing from a copy of the home it covers, and that
     * copy made the window read as transparent whenever the launcher behind it was
     * bright (waking after a charge, or reopening from Home).
     */
    protected boolean samplesBackdrop() { return true; }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(Ui.adaptiveContext(newBase));
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recreate();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestHighRefreshRate();
        Loc.applyLocale(this);
        Intent intent = getIntent();
        int l = intent.getIntExtra(EXTRA_RETURN_LEFT, Integer.MIN_VALUE);
        int t = intent.getIntExtra(EXTRA_RETURN_TOP, Integer.MIN_VALUE);
        int r = intent.getIntExtra(EXTRA_RETURN_RIGHT, Integer.MIN_VALUE);
        int b = intent.getIntExtra(EXTRA_RETURN_BOTTOM, Integer.MIN_VALUE);
        if (l != Integer.MIN_VALUE && t != Integer.MIN_VALUE
                && r != Integer.MIN_VALUE && b != Integer.MIN_VALUE
                && r > l && b > t) {
            hasReturnTarget = true;
            returnLeft = l;
            returnTop = t;
            returnRight = r;
            returnBottom = b;
        }
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // Draw edge-to-edge, but leave the gesture navigation bar visible. Hiding
        // it put the system into immersive mode and made the swipe-up Home gesture
        // unreliable in these apps.
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        // Built-in apps stay fully transparent around their card. Live-glass
        // apps ask the compositor to blur the actual content behind them.
        final View decor = window.getDecorView();
        // Do not expose the first layout frame before the captured backdrop and
        // glass effect are attached; otherwise the card visibly changes from a
        // transparent/live surface to the finished material after it opens.
        decor.setAlpha(0f);
        // The live backdrop is the real app behind this transparent window.
        // Do not blur it around the card; the card itself is the only material.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        window.setBackgroundBlurRadius(0);
        // The launch icon rectangle drives either an open-from-icon expansion (when
        // the app was launched from home/dock) or the normal springy entrance.
        revealIfGlassNeverAppeared(decor);
        if (readableTextPass()) {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            decor.removeCallbacks(readableTask);
                            decor.postDelayed(readableTask, 45L);
                        }
                    });
        }
        decor.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean started;

                    @Override
                    public void onGlobalLayout() {
                        if (started) return;
                        GlassPanel card = largestGlassPanel(decor);
                        if (card == null || card.getWidth() <= 0 || card.getHeight() <= 0) return;
                        started = true;
                        if (decor.getViewTreeObserver().isAlive()) {
                            decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                        applyBuiltinSaturation(decor);
                        Backdrop appliedBackdrop = null;
                        if (!usesLiveBackdrop() && samplesBackdrop()) {
                            appliedBackdrop = BuiltInBackdrop.take();
                            // A capture taken while the home layer was not drawn
                            // (screen just woke, charging overlay in front) is
                            // fully transparent. Sampling it would leave the sheet
                            // invisible, so fall through to the readable frost.
                            if (appliedBackdrop != null && blankBackdrop(appliedBackdrop)) {
                                appliedBackdrop = null;
                            }
                        }
                        if (appliedBackdrop != null) {
                            card.setBackdrop(appliedBackdrop);
                            card.setBackdropScreenAnchored(true);
                            card.setLiquidEffect(true);
                            liquidCard = true;
                        } else {
                            // No capture (am start, automation, recovery, or an app
                            // that opts out): a clear card would be see-through, so
                            // fall back to a readable frost instead of an invisible
                            // pane.
                            card.setClearGlass(false);
                            card.setClearBodyAlpha(0xF0);
                        }
                        if (!usesLiveBackdrop()) {
                            // Static-backdrop apps get a quiet textured frost.
                            card.setFrosted(true);
                        }
                        if (readableTextPass()) ReadableText.apply(decor);
                        decor.setAlpha(1f);
                        if (hasReturnTarget && startCardFromIcon(card)) return;
                        final GlassPanel target = card;
                        decor.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && !isDestroyed()) {
                                    Motion.giggleIn(target, 0.98f, 360L, 1.15f, 0.65f, false);
                                }
                            }
                        }, 140L);
                    }
                });
    }

    /** True when a capture has no visible pixels, so it would render as nothing. */
    private static boolean blankBackdrop(Backdrop backdrop) {
        if (backdrop == null || backdrop.bitmap == null || backdrop.bitmap.isRecycled()) {
            return true;
        }
        try {
            int w = backdrop.bitmap.getWidth();
            int h = backdrop.bitmap.getHeight();
            if (w <= 0 || h <= 0) return true;
            for (int y = 1; y <= 4; y++) {
                for (int x = 1; x <= 4; x++) {
                    int px = backdrop.bitmap.getPixel(
                            Math.min(w - 1, w * x / 5), Math.min(h - 1, h * y / 5));
                    if (android.graphics.Color.alpha(px) > 8) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Re-assert the window alpha after an overlay or a wake-up cleared it. */
    private void ensureDecorVisible() {
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null && !isFinishing() && !isDestroyed() && decor.getAlpha() < 1f) {
            decor.setAlpha(1f);
        }
    }

    /**
     * Ask the display for its fastest mode while this window is up: the
     * card-to-icon transitions are only as smooth as the panel's frame rate.
     * The system still caps this at the user's chosen peak.
     */
    private void requestHighRefreshRate() {
        try {
            Window w = getWindow();
            if (w == null) return;
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.preferredRefreshRate = 120f;
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /** Rasterise the card once so a scale animation is a texture blit per frame. */
    private static void liftForScale(View card) {
        if (card == null) return;
        try {
            card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            card.buildLayer();
        } catch (Throwable ignored) {
        }
    }

    private static void dropScaleLayer(View card) {
        if (card == null) return;
        try {
            card.setLayerType(View.LAYER_TYPE_NONE, null);
        } catch (Throwable ignored) {
        }
    }

    /** One saturation/darkness pair for every glass card in a built-in app. */
    private void applyBuiltinSaturation(View root) {
        Prefs prefs = new Prefs(this);
        float saturation = Math.max(0f, prefs.builtinSaturation() / 100f);
        float darkness = prefs.builtinBackdropDarkness() / 100f;
        applyGlassTuning(root, saturation, darkness);
    }

    /** Freeze every glass panel (outer card and nested sub-cards) for a scale. */
    private static void freezeGlassForScale(View view) {
        if (view instanceof GlassPanel) {
            ((GlassPanel) view).freezeForScale();
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                freezeGlassForScale(group.getChildAt(i));
            }
        }
    }

    private static void setFrostVeil(View view, float value) {
        if (view instanceof GlassPanel) {
            ((GlassPanel) view).setFrostVeil(value);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setFrostVeil(group.getChildAt(i), value);
            }
        }
    }

    private ValueAnimator veilAnim;

    private void animateFrostVeil(final View root, float from, float to, long duration,
                                  final Runnable done) {
        if (veilAnim != null) veilAnim.cancel();
        setFrostVeil(root, from);
        veilAnim = ValueAnimator.ofFloat(from, to);
        veilAnim.setDuration(duration);
        veilAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        veilAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                setFrostVeil(root, (float) animation.getAnimatedValue());
            }
        });
        final float target = to;
        veilAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                setFrostVeil(root, target);
                if (done != null) done.run();
            }
        });
        veilAnim.start();
    }

    private static void unfreezeGlassForScale(View view) {
        if (view instanceof GlassPanel) {
            ((GlassPanel) view).unfreezeFromScale();
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                unfreezeGlassForScale(group.getChildAt(i));
            }
        }
    }

    private static void applyGlassTuning(View view, float saturation, float darkness) {
        if (view instanceof GlassPanel) {
            GlassPanel panel = (GlassPanel) view;
            panel.setBackdropSaturation(saturation);
            panel.setBackdropDarkness(darkness);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyGlassTuning(group.getChildAt(i), saturation, darkness);
            }
        }
    }

    private void revealIfGlassNeverAppeared(final View decor) {
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed() && decor.getAlpha() < 1f) {
                    decor.setAlpha(1f);
                }
            }
        }, 320L);
    }

    /** Expand the real app card out of the launch icon, like a window restore. */
    private boolean startCardFromIcon(GlassPanel card) {
        // Mirror the close: a liquid card freezes its sampled backdrop while it
        // scales, otherwise the screen-anchored shader re-crops every frame. At
        // full size the frozen and live renders coincide, so nothing pops when
        // the effect is restored on the last frame.
        final boolean restoreLiquid = liquidCard;
        final View glassRoot = getWindow() == null ? null : getWindow().getDecorView();
        if (restoreLiquid) {
            if (glassRoot != null) freezeGlassForScale(glassRoot);
            liquidCard = false;
        }
        card.animate().cancel();
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setRotation(0f);
        card.setAlpha(1f);
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight() / 2f);
        // Rasterise at full size before the small start transform is applied.
        liftForScale(card);

        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        float cw = card.getWidth();
        float ch = card.getHeight();
        float centerX = loc[0] + cw / 2f;
        float centerY = loc[1] + ch / 2f;
        float targetW = Math.max(1f, returnRight - returnLeft);
        float targetH = Math.max(1f, returnBottom - returnTop);
        float targetX = (returnLeft + returnRight) / 2f;
        float targetY = (returnTop + returnBottom) / 2f;
        final float fromScaleX = Math.max(0.02f, targetW / cw);
        final float fromScaleY = Math.max(0.02f, targetH / ch);
        final float fromX = targetX - centerX;
        final float fromY = targetY - centerY;

        card.setScaleX(fromScaleX);
        card.setScaleY(fromScaleY);
        card.setTranslationX(fromX);
        card.setTranslationY(fromY);

        if (returnAnim != null) returnAnim.cancel();
        returnAnim = ValueAnimator.ofFloat(0f, 1f);
        returnAnim.setDuration(280L);
        // Mirror of the close curve, so the two read as one motion.
        returnAnim.setInterpolator(new PathInterpolator(0.28f, 0.55f, 0.70f, 1f));
        returnAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                card.setScaleX(fromScaleX + (1f - fromScaleX) * t);
                card.setScaleY(fromScaleY + (1f - fromScaleY) * t);
                card.setTranslationX(fromX * (1f - t));
                card.setTranslationY(fromY * (1f - t));
                // Reverse of the close fade: appear while still at the icon.
                float fadeEnd = 0.22f;
                card.setAlpha(t >= fadeEnd ? 1f : t / fadeEnd);
            }
        });
        returnAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                returnAnim = null;
                card.setAlpha(1f);
                dropScaleLayer(card);
                if (restoreLiquid) {
                    if (glassRoot != null) unfreezeGlassForScale(glassRoot);
                    card.setFrosted(false);
                    card.setBackdropScreenAnchored(true);
                    card.setLiquidEffect(true);
                    liquidCard = true;
                }
                if (!closing) {
                    Motion.giggleIn(card, 0.985f, 340L, 1.15f, 0.55f, false);
                }
            }
        });
        returnAnim.start();
        return true;
    }

    private static WallpaperView findWallpaper(View view) {
        if (view instanceof WallpaperView) return (WallpaperView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WallpaperView found = findWallpaper(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    static GlassPanel largestGlassPanel(View view) {
        GlassPanel best = null;
        if (view instanceof GlassPanel) {
            best = (GlassPanel) view;
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                GlassPanel candidate = largestGlassPanel(group.getChildAt(i));
                if (candidate != null && (best == null
                        || candidate.getWidth() * candidate.getHeight()
                        > best.getWidth() * best.getHeight())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Hide the global performance HUD while one of the built-in apps is visible. */
    protected final void hidePerformanceOverlay() {
        if (!PerfOverlayService.hasAnyOverlayVisible()) return;
        PerfOverlayService.hideForApp();
        perfOverlayHidden = true;
    }

    /**
     * Put the HUD back when the built-in app is no longer visible. Doing this in
     * onPause made it reappear while the app was still the foreground activity.
     */
    private void restorePerformanceOverlay() {
        if (!perfOverlayHidden) return;
        PerfOverlayService.setOverlayVisible(true);
        perfOverlayHidden = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (returning || returnFinished) {
            returning = false;
            returnFinished = false;
            resetReturnVisuals();
        }
        ensureDecorVisible();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        ensureDecorVisible();
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor == null) return;
        // Screen off/on can fire onUserLeaveHint while the window has no focus,
        // leaving the wallpaper layer and the card faded after the wake.
        if (returning || returnFinished) {
            returning = false;
            returnFinished = false;
            resetReturnVisuals();
            return;
        }
        WallpaperView wallpaper = findWallpaper(decor);
        if (wallpaper != null && wallpaper.getAlpha() < 0.999f) {
            wallpaper.animate().cancel();
            wallpaper.setAlpha(1f);
        }
        GlassPanel card = largestGlassPanel(decor);
        if (card != null && card.getAlpha() < 0.999f) {
            Motion.cancelGiggle(card);
            card.animate().cancel();
            card.setAlpha(1f);
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setTranslationX(0f);
            card.setTranslationY(0f);
        }
    }

    /**
     * Home swipe / task leave: shrink the app's glass card into the icon that
     * launched it. The task itself stays alive for multitasking; onResume resets
     * the card if the user comes back.
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!hasReturnTarget || closing || returning) return;
        returning = true;
        if (!animateCardToIcon(250L, false)) returning = false;
        // The leave hint also fires for system transitions that never take the
        // window away (the wired charging overlay is one). If this window still
        // owns the focus afterwards, the shrink-to-icon must be undone or the
        // card stays faded out over a still-running app.
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null) {
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isFinishing() || isDestroyed() || !hasWindowFocus()) return;
                    if (returning || returnFinished) {
                        returning = false;
                        returnFinished = false;
                        resetReturnVisuals();
                    }
                    ensureDecorVisible();
                }
            }, 420L);
        }
    }

    private void resetReturnVisuals() {
        if (returnAnim != null) {
            returnAnim.cancel();
            returnAnim = null;
        }
        getWindow().setBackgroundBlurRadius(0);
        View decor = getWindow().getDecorView();
        WallpaperView wallpaper = findWallpaper(decor);
        if (wallpaper != null) {
            wallpaper.animate().cancel();
            wallpaper.setAlpha(1f);
        }
        GlassPanel card = largestGlassPanel(decor);
        if (card != null) {
            dropScaleLayer(card);
            Motion.cancelGiggle(card);
            card.animate().cancel();
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setTranslationX(0f);
            card.setTranslationY(0f);
            card.setRotation(0f);
            card.setAlpha(1f);
            if (returnWasLiquid) {
                unfreezeGlassForScale(decor);
                card.setFrosted(false);
                card.setBackdropScreenAnchored(true);
                card.setLiquidEffect(true);
                liquidCard = true;
                returnWasLiquid = false;
            }
        }
    }

    /**
     * Move the main card to the launch icon's screen rectangle. Returns false
     * when the card is not ready, in which case the normal fade is used.
     */
    private boolean animateCardToIcon(long duration, final boolean finish) {
        if (!hasReturnTarget) return false;
        Window window = getWindow();
        final View decor = window.getDecorView();
        GlassPanel card = largestGlassPanel(decor);
        if (card == null || card.getWidth() <= 1 || card.getHeight() <= 1) return false;
        if (liquidCard) {
            // A live card scales badly: a screen-anchored shader re-crops the
            // capture every frame, and the nested glass cards smear along their
            // edges. Freeze the whole tree into frost for the shrink, the way a
            // non-sampled card closes.
            returnWasLiquid = true;
            freezeGlassForScale(decor);
            liquidCard = false;
        }
        WallpaperView wallpaper = findWallpaper(decor);
        if (wallpaper != null) {
            wallpaper.animate().cancel();
            wallpaper.setAlpha(1f);
            wallpaper.animate().alpha(0f).setDuration(duration).start();
        }

        if (returnAnim != null) {
            returnAnim.cancel();
            returnAnim = null;
        }
        Motion.cancelGiggle(card);
        card.animate().cancel();
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setRotation(0f);
        card.setAlpha(1f);
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight() / 2f);

        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        float cw = card.getWidth();
        float ch = card.getHeight();
        float centerX = loc[0] + cw / 2f;
        float centerY = loc[1] + ch / 2f;
        float targetW = Math.max(1f, returnRight - returnLeft);
        float targetH = Math.max(1f, returnBottom - returnTop);
        float targetX = (returnLeft + returnRight) / 2f;
        float targetY = (returnTop + returnBottom) / 2f;
        final float scaleX = Math.max(0.02f, targetW / cw);
        final float scaleY = Math.max(0.02f, targetH / ch);
        final float translateX = targetX - centerX;
        final float translateY = targetY - centerY;

        if (returnAnim != null) returnAnim.cancel();
        window.setBackgroundBlurRadius(0);
        liftForScale(card);
        returnAnim = ValueAnimator.ofFloat(0f, 1f);
        returnAnim.setDuration(duration);
        returnAnim.setInterpolator(new PathInterpolator(0.30f, 0.00f, 0.72f, 0.45f));
        returnAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (float) animation.getAnimatedValue();
                card.setScaleX(1f + (scaleX - 1f) * t);
                card.setScaleY(1f + (scaleY - 1f) * t);
                card.setTranslationX(translateX * t);
                card.setTranslationY(translateY * t);
                float fadeStart = finish ? 0.78f : 0.62f;
                float fade = t <= fadeStart ? 0f : (t - fadeStart) / (1f - fadeStart);
                card.setAlpha(1f - fade);
            }
        });
        returnAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (finish) {
                    finish();
                    overridePendingTransition(0, 0);
                } else {
                    dropScaleLayer(card);
                    returnFinished = true;
                }
            }
        });
        returnAnim.start();
        return true;
    }

    @Override
    protected void onStop() {
        restorePerformanceOverlay();
        super.onStop();
    }

    /** Finish with the shared Pavo close transition instead of an instant cut. */
    protected final void finishAnimated() {
        if (closing) return;
        closing = true;
        if (animateCardToIcon(280L, true)) return;
        // When there is no launch-icon rectangle (for example the floating
        // drawer), shrink the app card itself instead of fading the whole
        // transparent window. This keeps the close consistent with the
        // card-to-icon transition used from the home screen.
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        GlassPanel card = largestGlassPanel(decor);
        if (card != null && card.getWidth() > 1 && card.getHeight() > 1) {
            WallpaperView wallpaper = findWallpaper(decor);
            if (wallpaper != null) {
                wallpaper.animate().cancel();
                wallpaper.setAlpha(1f);
                wallpaper.animate().alpha(0f).setDuration(170).start();
            }
            Motion.cancelGiggle(card);
            card.animate().cancel();
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight() / 2f);
            card.animate()
                    .alpha(0f)
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            finish();
                            overridePendingTransition(0, 0);
                        }
                    })
                    .start();
            return;
        }
        View content = findViewById(android.R.id.content);
        if (content == null) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        content.animate().cancel();
        content.animate()
                .alpha(0f)
                .scaleX(0.972f)
                .scaleY(0.972f)
                .setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                })
                .start();
    }

    @Override
    public void onBackPressed() {
        finishAnimated();
    }
}
