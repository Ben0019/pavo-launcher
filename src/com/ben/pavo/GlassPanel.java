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
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

import java.util.Random;

/**
 * Rounded glass surface with a smoky tint, drifting specular highlights, rim light,
 * frost noise and a hairline border. It samples a {@link Backdrop} (a live blurred
 * snapshot of the screen) at its own position, so the glass reacts to whatever is
 * actually behind it — the same treatment as the app drawer.
 */
final class GlassPanel extends LinearLayout {

    private Backdrop backdrop;
    private final int accentColor;
    private final int tintColor;
    private float radiusPx;
    private final boolean sample;

    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] location = new int[2];

    private ValueAnimator liquidAnim;
    private float liquidT;
    private float surfaceAlpha = 1f;
    private boolean clearGlass;
    private boolean screenAnchoredBackdrop;
    private LiquidGlassShader liquidShader;
    private GlassCardRenderer cardRenderer;
    private boolean liquidEffect;
    private int clearBodyAlpha = 0xCC;
    private float rimWidthDp;
    private float sheenScale = 1f;
    private boolean frosted;
    private boolean rimEnabled = false;
    private int scrimAlpha = 0x66;
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Bitmap noiseTile;

    GlassPanel(Context c, Backdrop backdrop, int accentColor, int tintColor, float radiusDp,
               boolean sample) {
        super(c);
        this.backdrop = backdrop;
        this.liquidEffect = sample && backdrop != null && backdrop.bitmap != null;
        this.accentColor = accentColor;
        this.tintColor = tintColor;
        this.radiusPx = Ui.dpf(c, radiusDp);
        this.sample = sample;
        this.scrimAlpha = computeScrimAlpha(backdrop);

        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setClipToOutline(true);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(Math.max(1f, Ui.dpf(c, 1)));
        if (noiseTile == null) noiseTile = makeNoiseTile();
        noisePaint.setAlpha(13);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float r = radiusPx;
                if (view.getWidth() > 0 && view.getHeight() > 0) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            }
        });
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

    private void startLiquid() {
        if (frosted || liquidAnim != null) return;
        liquidAnim = ValueAnimator.ofFloat(0f, 1f);
        liquidAnim.setDuration(16000);
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

    private int accentLight() {
        int a = accentColor;
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

    Backdrop getBackdrop() {
        return backdrop;
    }

    void setBackdrop(Backdrop b) {
        if (backdrop != null && backdrop.bitmap != null
                && (b == null || backdrop.bitmap != b.bitmap)) {
            backdrop.bitmap.recycle();
        }
        backdrop = b;
        liquidEffect = sample && b != null && b.bitmap != null;
        invalidate();
    }

    /**
     * Opt into screen-anchored sampling for cards that show the live content behind
     * them (the dock drawers and the floating card). Older menu/settings cards keep
     * their original dark clamped treatment.
     */
    void setBackdropScreenAnchored(boolean anchored) {
        screenAnchoredBackdrop = anchored;
        invalidate();
    }

    /** Alpha for the glass surface only; child icons remain fully opaque. */
    void setScrimAlpha(int alpha) {
        scrimAlpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    void setSurfaceAlpha(float alpha) {
        surfaceAlpha = Math.max(0f, Math.min(1f, alpha));
        invalidate();
    }

    /**
     * Clear Liquid Glass: the backdrop is sampled but only lightly veiled, so the
     * content underneath stays visible while the rim lenses it. This is the iOS
     * "Clear" variant rather than an opaque frosted card.
     */
    void setClearGlass(boolean clear) {
        clearGlass = clear;
        invalidate();
    }

    /** Opacity of the sampled backdrop inside a clear panel. */
    void setClearBodyAlpha(int alpha) {
        clearBodyAlpha = Math.max(0, Math.min(255, alpha));
        invalidate();
    }

    /** Override the refracting rim thickness without changing the material. */
    void setRimWidthDp(float dp) {
        rimWidthDp = Math.max(0f, dp);
        invalidate();
    }

    /** Reduce the white specular/rim lighting without changing the glass tint. */
    void setSheenStrength(float scale) {
        sheenScale = Math.max(0.15f, Math.min(1f, scale));
        invalidate();
    }

    /** Use the full live-glass card pipeline when a backdrop is present. */
    void setRadiusDp(float dp) {
        radiusPx = Ui.dpf(getContext(), dp);
        invalidateOutline();
        invalidate();
    }

    void setRimEnabled(boolean value) {
        rimEnabled = value;
        invalidate();
    }

    boolean hasLiquidEffect() {
        return liquidEffect;
    }

    void setLiquidEffect(boolean value) {
        liquidEffect = value;
        invalidate();
    }

    /** Replace the refractive liquid-glass treatment with frosted glass. */
    void setFrosted(boolean value) {
        frosted = value;
        if (frosted) stopLiquid();
        else if (isAttachedToWindow()) startLiquid();
        invalidate();
    }

    /**
     * Apps and wallpapers vary from near-black to bright video. Keep the card
     * body readable by deriving a dark scrim from the average backdrop
     * luminance instead of using one fixed transparency.
     */
    private static int computeScrimAlpha(Backdrop b) {
        if (b == null || b.bitmap == null) return 0xC8;
        Bitmap bitmap = b.bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return 0xC8;
        int stepX = Math.max(1, width / 24);
        int stepY = Math.max(1, height / 24);
        double sum = 0;
        int count = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int colour = bitmap.getPixel(x, y);
                sum += (0.2126 * Color.red(colour)
                        + 0.7152 * Color.green(colour)
                        + 0.0722 * Color.blue(colour)) / 255.0;
                count++;
            }
        }
        double luminance = count == 0 ? 0.5 : sum / count;
        if (luminance > 0.62) return 0xC4;
        if (luminance > 0.45) return 0xAE;
        if (luminance > 0.30) return 0x98;
        return 0x84;
    }

    private void drawReadabilityScrim(Canvas canvas, float w, float h, float radius) {
        if (scrimAlpha <= 0) return;
        scrimPaint.setColor((scrimAlpha << 24) | 0x0B0E14);
        canvas.drawRoundRect(rect, radius, radius, scrimPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float radius = Math.min(radiusPx, Math.min(w, h) / 2f);
        rect.set(0, 0, w, h);
        if (liquidEffect && backdrop != null) {
            if (cardRenderer == null) cardRenderer = new GlassCardRenderer(this);
            if (cardRenderer.draw(canvas, (int) w, (int) h, radius, backdrop)) {
                drawReadabilityScrim(canvas, w, h, radius);
                drawLiquidOverlay(canvas, w, h, radius);
                return;
            }
        }
        if (frosted) {
            drawFrosted(canvas, w, h, radius);
            drawReadabilityScrim(canvas, w, h, radius);
            return;
        }

        Matrix glassMatrix = null;
        Bitmap background = null;
        if (sample && backdrop != null && backdrop.bitmap != null) {
            Bitmap blurred = backdrop.bitmap;
            if (blurred.getWidth() > 0 && blurred.getHeight() > 0) {
                getLocationOnScreen(location);
                float sx = blurred.getWidth() / (float) backdrop.screenWidth;
                float sy = blurred.getHeight() / (float) backdrop.screenHeight;
                Matrix m = new Matrix();
                if (screenAnchoredBackdrop) {
                    // Map this view's local canvas coordinates onto the
                    // screen-space backdrop sample, just like the dock.
                    m.setScale(sx, sy);
                    m.postTranslate(location[0] * sx, location[1] * sy);
                } else {
                    // Preserve the existing menu/settings treatment.
                    m.setScale(sx, sy);
                    m.postTranslate(-location[0] * sx, -location[1] * sy);
                }
                glassMatrix = m;
                background = blurred;
                float shaderAlpha = clearGlass ? clearBodyAlpha / 255f : 1f;
                if (liquidShader == null) liquidShader = new LiquidGlassShader();
                if (!liquidShader.draw(canvas, w, h, radius, blurred, m,
                        shaderAlpha, getResources().getDisplayMetrics().density)) {
                    BitmapShader shader = new BitmapShader(blurred,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(m);
                    glassPaint.setShader(shader);
                // A normal glass card uses the blurred backdrop as an opaque body.
                // Clear glass only lightly veils it so the real content remains
                // visible through the material; the lensing rim does the rest.
                glassPaint.setAlpha(clearGlass ? clearBodyAlpha : 0xFF);
                    canvas.drawRoundRect(rect, radius, radius, glassPaint);
                    glassPaint.setAlpha(0xFF);
                    glassPaint.setShader(null);
                }
            }
        }

        int surfaceLayer = -1;
        if (surfaceAlpha < 0.999f) {
            surfaceLayer = canvas.saveLayerAlpha(0f, 0f, w, h,
                    (int) (255f * surfaceAlpha));
        }

        int accent = accentLight();
        int ta = tintColor >>> 24;
        int rgb = clearGlass ? 0x0B0E14
                : blendRgb(tintColor & 0x00FFFFFF, accent, 0.08f);
        tintPaint.setColor((ta << 24) | rgb);
        canvas.drawRoundRect(rect, radius, radius, tintPaint);

        if (clearGlass) {
            // Localized dimming keeps the header and footer readable while the
            // middle of the glass stays genuinely transparent.
            Paint topShade = new Paint(Paint.ANTI_ALIAS_FLAG);
            topShade.setShader(new LinearGradient(0, 0, 0, h * 0.24f,
                    0x730B0E14, 0x000B0E14, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, topShade);

            Paint bottomShade = new Paint(Paint.ANTI_ALIAS_FLAG);
            bottomShade.setShader(new LinearGradient(0, h * 0.74f, 0, h,
                    0x000B0E14, 0x590B0E14, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, radius, radius, bottomShade);
        }
        drawReadabilityScrim(canvas, w, h, radius);

        // drifting liquid specular highlights
        float r1 = w * 0.5f;
        float cx1 = w * (0.18f + 0.16f * liquidT);
        float cy1 = h * 0.10f;
        int sheenA = Math.round((clearGlass ? 0x42 : 0x30) * sheenScale);
        liquidPaint.setShader(new RadialGradient(cx1, cy1, r1,
                withAlpha(accent, sheenA),
                withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquidPaint);

        float r2 = w * 0.34f;
        float cx2 = w * (0.84f - 0.12f * liquidT);
        float cy2 = h * 0.94f;
        int sheenB = Math.round((clearGlass ? 0x20 : 0x16) * sheenScale);
        liquidPaint.setShader(new RadialGradient(cx2, cy2, r2,
                withAlpha(accent, sheenB),
                withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquidPaint);
        liquidPaint.setShader(null);

        edgePaint.setShader(null);

        BitmapShader noise = new BitmapShader(noiseTile,
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix nm = new Matrix();
        nm.setScale(2f, 2f);
        noise.setLocalMatrix(nm);
        noisePaint.setShader(noise);
        canvas.drawRoundRect(rect, radius, radius, noisePaint);
        noisePaint.setShader(null);

        rect.set(0, 0, w, h);
        int rawAccent = accentLight();
        float rimDp = rimWidthDp > 0f ? rimWidthDp : (clearGlass ? 14f : 9f);
        if (rimEnabled) {
            LiquidGlass.draw(canvas, rect, radius,
                    Ui.dpf(getContext(), rimDp), background,
                    glassMatrix, rawAccent, clearGlass, sheenScale);
        }
        if (surfaceLayer >= 0) canvas.restoreToCount(surfaceLayer);
    }

    private void drawFrosted(Canvas canvas, float w, float h, float radius) {
        Bitmap background = null;
        Matrix matrix = null;
        if (sample && backdrop != null && backdrop.bitmap != null) {
            Bitmap blurred = backdrop.bitmap;
            if (blurred.getWidth() > 0 && blurred.getHeight() > 0) {
                getLocationOnScreen(location);
                float sx = blurred.getWidth() / (float) backdrop.screenWidth;
                float sy = blurred.getHeight() / (float) backdrop.screenHeight;
                matrix = new Matrix();
                if (screenAnchoredBackdrop) {
                    matrix.setScale(sx, sy);
                    matrix.postTranslate(location[0] * sx, location[1] * sy);
                } else {
                    matrix.setScale(sx, sy);
                    matrix.postTranslate(-location[0] * sx, -location[1] * sy);
                }
                background = blurred;
            }
        }
        boolean liquidDrawn = false;
        if (liquidEffect && backdrop != null) {
            if (cardRenderer == null) cardRenderer = new GlassCardRenderer(this);
            liquidDrawn = cardRenderer.draw(canvas, (int) w, (int) h, radius, backdrop);
        }
        if (!liquidDrawn && background != null) {
            if (liquidShader == null) liquidShader = new LiquidGlassShader();
            if (liquidShader.draw(canvas, w, h, radius, background, matrix,
                    1f, getResources().getDisplayMetrics().density)) {
                // lensed backdrop drawn in one pass
            } else {
                BitmapShader shader = new BitmapShader(background,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(matrix);
                // Keep the frost layer opaque so the live game cannot show through
                // sharply; transparency is expressed through the tint below.
                glassPaint.setAlpha(0xFF);
                canvas.drawRoundRect(rect, radius, radius, glassPaint);
                glassPaint.setAlpha(0xFF);
                glassPaint.setShader(null);
            }
        }

        if (liquidDrawn) {
            drawReadabilityScrim(canvas, w, h, radius);
            drawLiquidOverlay(canvas, w, h, radius);
            return;
        }

        int baseTint = tintColor >>> 24;
        if (baseTint <= 0) baseTint = 0xB8;
        int alpha = Math.round(baseTint * (0.25f + 1.25f * surfaceAlpha));
        alpha = Math.max(0x10, Math.min(0xFF, alpha));
        tintPaint.setColor((alpha << 24) | (tintColor & 0x00FFFFFF));
        canvas.drawRoundRect(rect, radius, radius, tintPaint);

        // A barely-there top sheen and bottom shade give the frost depth without
        // the bright liquid-glass rim.
        Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        top.setShader(new LinearGradient(0, 0, 0, h * 0.32f,
                0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, top);
        Paint bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottom.setShader(new LinearGradient(0, h * 0.70f, 0, h,
                0x00000000, 0x2E000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, bottom);

        if (noiseTile != null) {
            BitmapShader noise = new BitmapShader(noiseTile,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            Matrix nm = new Matrix();
            nm.setScale(2f, 2f);
            noise.setLocalMatrix(nm);
            noisePaint.setAlpha(7);
            noisePaint.setShader(noise);
            canvas.drawRoundRect(rect, radius, radius, noisePaint);
            noisePaint.setShader(null);
            noisePaint.setAlpha(13);
        }

        borderPaint.setColor(0x38FFFFFF);
        borderPaint.setStrokeWidth(Math.max(1f, Ui.dpf(getContext(), 1f)));
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
    }

    /** Clear-glass tint and lighting for a card rendered by the liquid pipeline. */
    private void drawLiquidOverlay(Canvas canvas, float w, float h, float radius) {
        int layer = -1;
        if (surfaceAlpha < 0.999f) {
            layer = canvas.saveLayerAlpha(0f, 0f, w, h,
                    (int) (255f * surfaceAlpha));
        }

        int ta = tintColor >>> 24;
        if (ta <= 0) ta = 0x4D;
        tintPaint.setColor((ta << 24) | (tintColor & 0x00FFFFFF));
        canvas.drawRoundRect(rect, radius, radius, tintPaint);

        Paint topShade = new Paint(Paint.ANTI_ALIAS_FLAG);
        topShade.setShader(new LinearGradient(0, 0, 0, h * 0.24f,
                0x730B0E14, 0x000B0E14, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, topShade);
        Paint bottomShade = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottomShade.setShader(new LinearGradient(0, h * 0.74f, 0, h,
                0x000B0E14, 0x590B0E14, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, bottomShade);

        int accent = accentLight();
        liquidPaint.setShader(new RadialGradient(w * 0.30f, h * 0.10f, w * 0.50f,
                withAlpha(accent, Math.round(0x30 * sheenScale)),
                withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquidPaint);
        liquidPaint.setShader(new RadialGradient(w * 0.80f, h * 0.92f, w * 0.30f,
                withAlpha(accent, Math.round(0x16 * sheenScale)),
                withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, liquidPaint);
        liquidPaint.setShader(null);

        if (noiseTile != null) {
            BitmapShader noise = new BitmapShader(noiseTile,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            Matrix nm = new Matrix();
            nm.setScale(2f, 2f);
            noise.setLocalMatrix(nm);
            noisePaint.setAlpha(7);
            noisePaint.setShader(noise);
            canvas.drawRoundRect(rect, radius, radius, noisePaint);
            noisePaint.setShader(null);
            noisePaint.setAlpha(13);
        }

        if (rimEnabled) {
            GlassHighlightView.drawHighlight(canvas, (int) w, (int) h, radius,
                    getResources().getDisplayMetrics().density);
            borderPaint.setColor(0x2EFFFFFF);
            borderPaint.setStrokeWidth(Math.max(1f, Ui.dpf(getContext(), 1f)));
            canvas.drawRoundRect(rect, radius, radius, borderPaint);
        }

        if (layer >= 0) canvas.restoreToCount(layer);
    }

    private static Bitmap makeNoiseTile() {
        int size = 64;
        int[] pixels = new int[size * size];
        Random random = new Random(11);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(22);
            boolean white = random.nextBoolean();
            pixels[i] = (alpha << 24) | (white ? 0x00FFFFFF : 0x00000000);
        }
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        tile.setPixels(pixels, 0, size, 0, 0, size, size);
        return tile;
    }
}
