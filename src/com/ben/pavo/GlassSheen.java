package com.ben.pavo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The lighting half of the glass treatment (specular drift, rim light, border) without
 * any tint or backdrop sampling. Overlaid on surfaces that already have a blurred
 * backdrop, such as the app drawer and folder cards.
 */
final class GlassSheen extends View {

    private final WallpaperView wallpaper;
    private final float radiusPx;

    private final Paint liquid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private ValueAnimator anim;
    private float t;
    private Backdrop backdrop;
    private boolean paused;
    private final int[] location = new int[2];

    GlassSheen(Context c, WallpaperView wallpaper, float radiusDp) {
        super(c);
        this.wallpaper = wallpaper;
        this.radiusPx = Ui.dpf(c, radiusDp);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
    }

    void setBackdrop(Backdrop b) {
        backdrop = b;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) start();
        else stop();
    }

    void setPaused(boolean value) {
        paused = value;
        if (paused) stop();
        else if (isAttachedToWindow() && getWindowVisibility() == View.VISIBLE) start();
    }

    private void start() {
        if (anim != null || paused) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(16000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                t = (float) animation.getAnimatedValue();
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

    private int accentLight() {
        int a = wallpaper != null ? wallpaper.accent() : 0xFFA9BEFF;
        return Color.rgb((Color.red(a) + 255) / 2,
                (Color.green(a) + 255) / 2,
                (Color.blue(a) + 255) / 2);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = Math.min(radiusPx, Math.min(w, h) / 2f);
        rect.set(0, 0, w, h);
        int accent = accentLight();

        float cx1 = w * (0.18f + 0.16f * t);
        liquid.setShader(new RadialGradient(cx1, h * 0.08f, w * 0.55f,
                withAlpha(accent, 0x30), withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquid);

        float cx2 = w * (0.84f - 0.12f * t);
        liquid.setShader(new RadialGradient(cx2, h * 0.94f, w * 0.36f,
                withAlpha(accent, 0x16), withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquid);
        liquid.setShader(null);

        Matrix backgroundMatrix = null;
        Bitmap background = null;
        if (backdrop != null && backdrop.bitmap != null
                && backdrop.bitmap.getWidth() > 0 && backdrop.bitmap.getHeight() > 0) {
            background = backdrop.bitmap;
            getLocationOnScreen(location);
            float sx = background.getWidth() / (float) backdrop.screenWidth;
            float sy = background.getHeight() / (float) backdrop.screenHeight;
            backgroundMatrix = new Matrix();
            backgroundMatrix.setScale(1f / sx, 1f / sy);
            backgroundMatrix.postTranslate(-location[0], -location[1]);
        }
        int rawAccent = wallpaper != null ? wallpaper.accent() : 0xFFA9BEFF;
        LiquidGlass.draw(canvas, rect, radius, Ui.dpf(getContext(), 9), background,
                backgroundMatrix, rawAccent);
    }
}
