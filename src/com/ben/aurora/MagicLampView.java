package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Draws a snapshot of the popup drawer with the macOS "genie" / magic-lamp mesh:
 * the lower edge necks into the dock button first, the sides curve, and the top
 * follows until the whole card collapses into the icon. Progress 0 is the real
 * card, progress 1 is fully collapsed.
 */
final class MagicLampView extends View {

    private static final int COLS = 18;
    private static final int ROWS = 28;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF from = new RectF();
    private final float[] verts = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final float[] xs = new float[COLS + 1];

    private Bitmap bitmap;
    private float targetX;
    private float targetY;
    private float targetW;
    private float targetH;
    private float progress;
    private boolean dirty = true;
    private boolean uniform;

    MagicLampView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    void setSnapshot(Bitmap bitmap, RectF source, float targetX, float targetY,
                     float targetW, float targetH) {
        this.bitmap = bitmap;
        from.set(source);
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetW = Math.max(1f, targetW);
        this.targetH = Math.max(1f, targetH);
        dirty = true;
        invalidate();
    }

    void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        dirty = true;
        invalidate();
    }

    /** A plain rounded-tile zoom with no funnel, used for app open/close. */
    void setUniformScale(boolean uniform) {
        this.uniform = uniform;
        dirty = true;
        invalidate();
    }

    void release() {
        bitmap = null;
        invalidate();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void buildMesh() {
        if (uniform) {
            buildUniformMesh();
            return;
        }
        float p = progress;
        float topK = (float) Math.pow(p, 2.0);
        float bottomK = (float) Math.pow(p, 0.55);
        float wave = (float) Math.sin(Math.PI * p);

        float halfW = from.width() * 0.5f;
        float targetHalfW = targetW * 0.5f;
        float topHalf = lerp(halfW, targetHalfW, topK);
        float bottomHalf = lerp(halfW, targetHalfW, bottomK);
        float topCx = lerp(from.centerX(), targetX, topK);
        float bottomCx = lerp(from.centerX(), targetX, bottomK);
        float topY = lerp(from.top, targetY - targetH * 0.5f, topK);
        float bottomY = lerp(from.bottom, targetY + targetH * 0.5f, bottomK);

        float bend = from.height() * 0.10f * wave;
        float sway = from.width() * 0.035f * wave;
        int i = 0;
        for (int row = 0; row <= ROWS; row++) {
            float v = row / (float) ROWS;
            float curve = (float) Math.sin(Math.PI * v);
            float y = lerp(topY, bottomY, v) + bend * curve;
            float half = lerp(topHalf, bottomHalf, v)
                    * (1f + 0.09f * curve * wave);
            float cx = lerp(topCx, bottomCx, v) + sway * curve;
            for (int col = 0; col <= COLS; col++) {
                float u = col / (float) COLS;
                xs[col] = cx + (u - 0.5f) * 2f * half;
            }
            for (int col = 0; col <= COLS; col++) {
                verts[i++] = xs[col];
                verts[i++] = y;
            }
        }
        dirty = false;
    }

    private void buildUniformMesh() {
        float p = progress;
        float l = lerp(from.left, targetX - targetW / 2f, p);
        float t = lerp(from.top, targetY - targetH / 2f, p);
        float r = lerp(from.right, targetX + targetW / 2f, p);
        float b = lerp(from.bottom, targetY + targetH / 2f, p);
        int i = 0;
        for (int row = 0; row <= ROWS; row++) {
            float v = row / (float) ROWS;
            float y = lerp(t, b, v);
            for (int col = 0; col <= COLS; col++) {
                float u = col / (float) COLS;
                verts[i++] = lerp(l, r, u);
                verts[i++] = y;
            }
        }
        dirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (dirty) buildMesh();
        canvas.drawBitmapMesh(bitmap, COLS, ROWS, verts, 0, null, 0, paint);

        // A soft lamp flare at the neck, strongest halfway through the warp.
        float p = progress;
        float flare = (float) Math.sin(Math.PI * p);
        if (flare > 0.01f) {
            int r = Math.max(1, Math.round(targetW * 2.6f));
            int alpha = Math.round(0x66 * flare);
            glowPaint.setShader(new RadialGradient(targetX, targetY, r,
                    (alpha << 24) | 0x00FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawCircle(targetX, targetY, r, glowPaint);
            glowPaint.setShader(null);
        }
    }
}
