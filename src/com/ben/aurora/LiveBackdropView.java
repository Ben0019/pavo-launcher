package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

/**
 * The live content behind the app drawer, rendered inside the card only.
 *
 * The home layer is drawn once into this view and the glass RenderEffect chain
 * is attached to this view, so the blur, saturation and edge refraction are
 * clipped to the rounded drawer card instead of tinting the whole screen.
 */
final class LiveBackdropView extends View {
    private View source;
    private float radiusPx;
    private float edgeInsetPx;
    private boolean edgeOnly;
    private boolean enabled;
    private Bitmap snapshot;
    private final Paint snapshotPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private RenderNode node;
    private RenderEffect effect;
    private int effectWidth;
    private int effectHeight;
    private float effectRadius;
    private static final float RENDER_SCALE = 0.5f;
    private boolean refreshPosted;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshPosted = false;
            if (!enabled || getWindowVisibility() != VISIBLE) return;
            invalidate();
            postDelayed(this, 16L);
            refreshPosted = true;
        }
    };

    LiveBackdropView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setRadiusDp(26f);
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.5f);
        snapshotPaint.setColorFilter(new ColorMatrixColorFilter(saturation));
    }

    void setRadiusDp(float dp) {
        radiusPx = Ui.dpf(getContext(), dp);
        invalidate();
    }

    void setEdgeOnlyDp(float dp) {
        edgeOnly = true;
        edgeInsetPx = Ui.dpf(getContext(), dp);
        invalidate();
    }

    void setSource(View source) {
        this.source = source;
        updateEffect();
        invalidate();
    }

    void setSnapshot(Bitmap bitmap) {
        if (snapshot != null && snapshot != bitmap) snapshot.recycle();
        snapshot = bitmap;
        invalidate();
    }

    void setBackdropEnabled(boolean value) {
        enabled = value;
        if (value) {
            updateEffect();
            invalidate();
            scheduleRefresh();
        } else {
            removeCallbacks(refresh);
            refreshPosted = false;
        }
    }

    private void scheduleRefresh() {
        if (!refreshPosted && enabled) {
            refreshPosted = true;
            postDelayed(refresh, 33L);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateEffect();
    }

    private void updateEffect() {
        effect = null;
        node = null;
        invalidate();
    }

    private RenderEffect effectFor(int width, int height, float scale) {
        if (effect != null && effectWidth == width && effectHeight == height
                && effectRadius == radiusPx) {
            return effect;
        }
        effect = LiveGlassEffect.build(
                getResources().getDisplayMetrics().density,
                0, 0, width, height, radiusPx * scale, scale);
        effectWidth = width;
        effectHeight = height;
        effectRadius = radiusPx;
        return effect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!enabled || source == null || source.getWidth() <= 0
                || source.getHeight() <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) {
            int save = canvas.save();
            int[] here = new int[2];
            int[] sourceOnScreen = new int[2];
            getLocationOnScreen(here);
            source.getLocationOnScreen(sourceOnScreen);
            canvas.translate(sourceOnScreen[0] - here[0], sourceOnScreen[1] - here[1]);
            if (snapshot != null && !snapshot.isRecycled()
                    && source.getWidth() > 0 && source.getHeight() > 0) {
                canvas.drawBitmap(snapshot, null,
                        new android.graphics.RectF(0, 0, source.getWidth(), source.getHeight()),
                        snapshotPaint);
            } else {
                source.draw(canvas);
            }
            canvas.restoreToCount(save);
            scheduleRefresh();
            return;
        }
        int width = getWidth();
        int height = getHeight();
        int sw = Math.max(1, Math.round(width * RENDER_SCALE));
        int sh = Math.max(1, Math.round(height * RENDER_SCALE));
        float scale = sw / (float) width;
        if (node == null) node = new RenderNode("aurora-drawer-backdrop");
        node.setPosition(0, 0, sw, sh);
        node.setRenderEffect(effectFor(sw, sh, scale));

        int[] here = new int[2];
        int[] sourceOnScreen = new int[2];
        getLocationOnScreen(here);
        source.getLocationOnScreen(sourceOnScreen);
        RecordingCanvas recording = node.beginRecording(sw, sh);
        recording.scale(scale, scale);
        recording.translate(sourceOnScreen[0] - here[0], sourceOnScreen[1] - here[1]);
        if (edgeOnly) {
            Path outer = new Path();
            outer.addRoundRect(0, 0, width, height, radiusPx, radiusPx,
                    Path.Direction.CW);
            Path inner = new Path();
            float inset = edgeInsetPx;
            inner.addRoundRect(inset, inset, width - inset, height - inset,
                    Math.max(0f, radiusPx - inset), Math.max(0f, radiusPx - inset),
                    Path.Direction.CW);
            outer.op(inner, Path.Op.DIFFERENCE);
            recording.clipPath(outer);
        }
        source.draw(recording);
        node.endRecording();

        int save = canvas.save();
        canvas.scale(1f / scale, 1f / scale);
        canvas.drawRenderNode(node);
        canvas.restoreToCount(save);
        scheduleRefresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (enabled) scheduleRefresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        refreshPosted = false;
        super.onDetachedFromWindow();
    }
}
