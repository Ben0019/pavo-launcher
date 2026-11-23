package com.ben.pavo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class DockView extends LinearLayout {
    private final WallpaperView wallpaper;
    private final View contentSource;
    private final Prefs prefs;

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dropGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dropBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap noiseTile;
    private LiquidGlassShader liquidShader;
    private RenderNode glassNode;
    private RenderNode shadowNode;
    private boolean shadowEffectSet;
    private RenderEffect glassEffect;
    private float glassSaturation = 1.5f;
    private float glassDarkness;
    private final Path glassClip = new Path();
    private int glassNodeWidth;
    private int glassNodeHeight;
    private float glassNodeRadius;
    private static final float GLASS_SCALE = 0.5f;
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();

    private ValueAnimator liquidAnim;
    private boolean liquidPaused;
    private float liquidT;
    private int dropIndex = -1;
    private float surfaceAlpha = 1f;

    void setSurfaceAlpha(float alpha) {
        surfaceAlpha = Math.max(0f, Math.min(1f, alpha));
        invalidate();
    }

    DockView(Context c, WallpaperView wallpaper, View contentSource, Prefs prefs) {
        super(c);
        this.wallpaper = wallpaper;
        this.contentSource = contentSource;
        this.prefs = prefs;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setWillNotDraw(false);
        setClipToOutline(false);
        setClipChildren(false);
        setClipToPadding(false);
        setElevation(0f);
        setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 6));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
        noiseTile = makeNoiseTile();
        noisePaint.setAlpha(8);

        dropGlowPaint.setColor(0x33FFFFFF);
        dropBarPaint.setColor(0xD9FFFFFF);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Math.min(cornerPx(), view.getHeight() / 2f));
            }
        });
    }

    float cornerPx() {
        return Ui.dpf(getContext(), prefs.cornerDp());
    }

    private int accentLight() {
        int a = wallpaper.accent();
        int r = (Color.red(a) + 255) / 2;
        int g = (Color.green(a) + 255) / 2;
        int b = (Color.blue(a) + 255) / 2;
        return Color.rgb(r, g, b);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int blendRgb(int from, int to, float t) {
        int r = Math.round(Color.red(from) * (1 - t) + Color.red(to) * t);
        int g = Math.round(Color.green(from) * (1 - t) + Color.green(to) * t);
        int b = Math.round(Color.blue(from) * (1 - t) + Color.blue(to) * t);
        return Color.rgb(r, g, b);
    }

    void refreshStyle() {
        invalidateOutline();
        invalidate();
    }

    void setDropIndex(int index) {
        if (dropIndex == index) return;
        dropIndex = index;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLiquid();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopLiquid();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE) startLiquid();
        else stopLiquid();
    }

    void setLiquidPaused(boolean paused) {
        liquidPaused = paused;
        if (paused) stopLiquid();
        else if (isAttachedToWindow() && getWindowVisibility() == View.VISIBLE) startLiquid();
    }

    private void startLiquid() {
        if (liquidAnim != null || liquidPaused) return;
        liquidAnim = ValueAnimator.ofFloat(0f, 1f);
        liquidAnim.setDuration(14000);
        liquidAnim.setRepeatCount(ValueAnimator.INFINITE);
        liquidAnim.setRepeatMode(ValueAnimator.REVERSE);
        liquidAnim.setInterpolator(new LinearInterpolator());
        liquidAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                liquidT = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        liquidAnim.start();
    }

    private void stopLiquid() {
        if (liquidAnim != null) {
            liquidAnim.cancel();
            liquidAnim = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float radius = h / 2f;
        rect.set(0, 0, w, h);

        // Like the app drawer, the live blurred backdrop is always drawn fully
        // opaque; only the tint, highlights and noise on top follow the
        // transparency setting. Fading the blur itself lets the crisp
        // wallpaper show through and the dock stops reading as frosted glass.
        drawDropShadow(canvas, w, h, radius);
        boolean live = drawLiveGlassBody(canvas, w, h, radius);

        int surfaceLayer = -1;
        if (surfaceAlpha < 0.999f) {
            surfaceLayer = canvas.saveLayerAlpha(0f, 0f, w, h,
                    (int) (255f * surfaceAlpha));
        }

        // Software fallback (and API < 31): use the old pre-blurred sample.
        Bitmap wallpaperBlur = live || wallpaper == null
                || wallpaper.getVisibility() != VISIBLE ? null : wallpaper.getBlurred();
        boolean lensed = false;
        if (wallpaperBlur != null && !wallpaperBlur.isRecycled()
                && wallpaper.getWidth() > 0 && wallpaper.getHeight() > 0) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            float sx = wallpaperBlur.getWidth() / (float) wallpaper.getWidth();
            float sy = wallpaperBlur.getHeight() / (float) wallpaper.getHeight();
            Matrix wm = new Matrix();
            wm.setScale(sx, sy);
            wm.postTranslate(loc[0] * sx, loc[1] * sy);
            if (liquidShader == null) liquidShader = new LiquidGlassShader();
            lensed = liquidShader.draw(canvas, w, h, radius, wallpaperBlur, wm,
                    1f, getResources().getDisplayMetrics().density);
        }

        // The built-in launcher apps use a genuinely clear glass body: the
        // wallpaper and icons stay live underneath, with only a tinted veil,
        // localized shading and the liquid rim on top.
        // Same body as the app drawer card: one low-alpha smoky tint over the
        // live glass, with no extra readability scrim.
        tintPaint.setColor(0x04070A12);
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
        // The drawer reads as a milky veil that lifts the backdrop; a faint
        // white layer gives the dock the same lift instead of a dark slab.
        tintPaint.setColor(0x02E6EEFA);
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
        // Frosted depth: lighter at the top, clearing towards the bottom, as
        // in the glass slab reference.
        tintPaint.setShader(new LinearGradient(0f, 0f, 0f, h,
                0x08FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
        tintPaint.setShader(null);
        // Underside shading: the glass gets thicker towards the bottom edge,
        // which is what makes it read as a slab and not a flat sticker.
        tintPaint.setShader(new LinearGradient(0f, h * 0.45f, 0f, h,
                0x00000000, 0x10000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
        tintPaint.setShader(null);

        float sheen = Math.max(0.15f, Math.min(1f, prefs.dockSheen() / 100f));

        // Drifting white specular highlights.
        float r1 = w * 0.5f;
        float cx1 = w * (0.18f + 0.16f * liquidT);
        float cy1 = h * 0.10f;
        liquidPaint.setShader(new RadialGradient(cx1, cy1, r1,
                withAlpha(0xFFFFFFFF, Math.round(0x42 * sheen)),
                withAlpha(0xFFFFFFFF, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquidPaint);

        float r2 = w * 0.34f;
        float cx2 = w * (0.84f - 0.12f * liquidT);
        float cy2 = h * 0.94f;
        liquidPaint.setShader(new RadialGradient(cx2, cy2, r2,
                withAlpha(0xFFFFFFFF, Math.round(0x20 * sheen)),
                withAlpha(0xFFFFFFFF, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquidPaint);
        liquidPaint.setShader(null);

        // Fine frost noise.
        BitmapShader noiseShader = new BitmapShader(noiseTile,
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix noiseMatrix = new Matrix();
        noiseMatrix.setScale(2f, 2f);
        noiseShader.setLocalMatrix(noiseMatrix);
        noisePaint.setShader(noiseShader);
        canvas.drawRoundRect(rect, radius, radius, noisePaint);
        noisePaint.setShader(null);

        if (surfaceLayer >= 0) canvas.restoreToCount(surfaceLayer);

        LiquidRim.draw(canvas, rect, radius, sheen);

        drawDropIndicator(canvas, w, h, radius);
    }

    void setGlassTuning(float saturation, float darkness) {
        float sat = Math.max(0f, saturation);
        float dark = Math.max(0f, Math.min(1f, darkness));
        if (sat == glassSaturation && dark == glassDarkness) return;
        glassSaturation = sat;
        glassDarkness = dark;
        glassEffect = null;
        invalidate();
    }

    /** Soft drop shadow like the app drawer's: it lifts the dock off the wallpaper. */
    private void drawDropShadow(Canvas canvas, float w, float h, float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) {
            return;
        }
        try {
            float density = getResources().getDisplayMetrics().density;
            float blur = 24f * density;
            float offset = 4f * density;
            float pad = blur * 2f + offset;
            int nw = Math.round(w + pad * 2f);
            int nh = Math.round(h + pad * 2f);
            if (shadowNode == null) shadowNode = new RenderNode("pavo-dock-shadow");
            shadowNode.setPosition(0, 0, nw, nh);
            if (!shadowEffectSet) {
                shadowNode.setRenderEffect(RenderEffect.createBlurEffect(
                        blur, blur, Shader.TileMode.CLAMP));
                shadowEffectSet = true;
            }
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x33000000);
            RecordingCanvas rec = shadowNode.beginRecording(nw, nh);
            rec.drawRoundRect(pad, pad + offset, pad + w, pad + h + offset, radius, radius, p);
            shadowNode.endRecording();
            int save = canvas.save();
            canvas.translate(-pad, -pad);
            canvas.drawRenderNode(shadowNode);
            canvas.restoreToCount(save);
        } catch (Throwable ignored) {
            // Decorative only.
        }
    }

    private boolean drawLiveGlassBody(Canvas canvas, float w, float h, float radius) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !canvas.isHardwareAccelerated() || wallpaper == null
                || wallpaper.getVisibility() != VISIBLE
                || wallpaper.getWidth() <= 0 || wallpaper.getHeight() <= 0) {
            return false;
        }
        try {
            int sw = Math.max(1, Math.round(w * GLASS_SCALE));
            int sh = Math.max(1, Math.round(h * GLASS_SCALE));
            float scale = sw / w;
            if (glassNode == null) glassNode = new RenderNode("aurora-dock-glass");
            glassNode.setPosition(0, 0, sw, sh);
            if (glassEffect == null || glassNodeWidth != sw || glassNodeHeight != sh
                    || glassNodeRadius != radius) {
                glassEffect = LiveGlassEffect.buildDock(
                        getResources().getDisplayMetrics().density, sw, sh,
                        radius * scale, scale, 2f, glassSaturation,
                        0f, 30f, 70f, 1.8f, 0.8f, 0.2f);
                glassNode.setRenderEffect(glassEffect);
                glassNodeWidth = sw;
                glassNodeHeight = sh;
                glassNodeRadius = radius;
            }

            int[] dockLoc = new int[2];
            int[] wallpaperLoc = new int[2];
            getLocationOnScreen(dockLoc);
            wallpaper.getLocationOnScreen(wallpaperLoc);
            RecordingCanvas recording = glassNode.beginRecording(sw, sh);
            recording.scale(scale, scale);
            recording.translate(wallpaperLoc[0] - dockLoc[0],
                    wallpaperLoc[1] - dockLoc[1]);
            wallpaper.draw(recording);
            if (contentSource != null && contentSource.getVisibility() == VISIBLE
                    && contentSource.getWidth() > 0 && contentSource.getHeight() > 0) {
                int[] contentLoc = new int[2];
                contentSource.getLocationOnScreen(contentLoc);
                recording.save();
                recording.translate(contentLoc[0] - wallpaperLoc[0],
                        contentLoc[1] - wallpaperLoc[1]);
                contentSource.draw(recording);
                recording.restore();
            }
            glassNode.endRecording();

            glassClip.reset();
            glassClip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(glassClip);
            canvas.scale(1f / scale, 1f / scale);
            canvas.drawRenderNode(glassNode);
            canvas.restoreToCount(save);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void drawDropIndicator(Canvas canvas, float w, float h, float radius) {
        if (dropIndex < 0) return;
        List<ItemView> items = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ItemView) items.add((ItemView) c);
        }
        if (items.isEmpty()) return;

        float half = Ui.dpf(getContext(), 3.5f);
        if (getOrientation() == VERTICAL) {
            float y;
            if (dropIndex >= items.size()) {
                y = items.get(items.size() - 1).getBottom() + Ui.dpf(getContext(), 4);
            } else {
                y = items.get(dropIndex).getTop() - Ui.dpf(getContext(), 2);
            }
            float left = w * 0.18f;
            float right = w * 0.82f;
            tmp.set(left, y - half, right, y + half);
            float g = Ui.dpf(getContext(), 9);
            dropGlowPaint.setShader(new RadialGradient((left + right) / 2f, y, g * 2.2f,
                    0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(tmp, half, half, dropGlowPaint);
            dropGlowPaint.setShader(null);
            canvas.drawRoundRect(tmp, half, half, dropBarPaint);
            return;
        }

        float x;
        if (dropIndex >= items.size()) {
            x = items.get(items.size() - 1).getRight() + Ui.dpf(getContext(), 4);
        } else {
            x = items.get(dropIndex).getLeft() - Ui.dpf(getContext(), 2);
        }
        float top = h * 0.18f;
        float bottom = h * 0.82f;
        tmp.set(x - half, top, x + half, bottom);
        float g = Ui.dpf(getContext(), 9);
        dropGlowPaint.setShader(new RadialGradient(x, (top + bottom) / 2f, g * 2.2f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(tmp, half, half, dropGlowPaint);
        dropGlowPaint.setShader(null);
        canvas.drawRoundRect(tmp, half, half, dropBarPaint);
    }

    private static Bitmap makeNoiseTile() {
        int size = 64;
        int[] pixels = new int[size * size];
        Random random = new Random(7);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(24);
            boolean white = random.nextBoolean();
            pixels[i] = (alpha << 24) | (white ? 0x00FFFFFF : 0x00000000);
        }
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        tile.setPixels(pixels, 0, size, 0, 0, size, size);
        return tile;
    }
}
