package com.ben.aurora;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

/**
 * The live-glass card pipeline for the built-in apps, using their existing
 * screen capture as the backdrop source. The card records only its own area at
 * half resolution, runs the same saturation/blur/lens stack as the drawer and
 * dock, then scales the result back into the card.
 */
final class GlassCardRenderer {
    private static final float RENDER_SCALE = 0.5f;

    private final View view;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final int[] location = new int[2];

    private RenderNode node;
    private RenderEffect effect;
    private Bitmap lastBitmap;
    private int lastLocX;
    private int lastLocY;
    private int lastWidth;
    private int lastHeight;
    private float lastRadius;
    private int nodeWidth;
    private int nodeHeight;

    GlassCardRenderer(View view) {
        this.view = view;
    }

    boolean draw(Canvas canvas, int width, int height, float radius, Backdrop backdrop) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !canvas.isHardwareAccelerated()
                || backdrop == null || backdrop.bitmap == null
                || backdrop.bitmap.isRecycled()
                || width < 4 || height < 4) {
            return false;
        }
        try {
            Bitmap bitmap = backdrop.bitmap;
            view.getLocationOnScreen(location);
            int sw = Math.max(1, Math.round(width * RENDER_SCALE));
            int sh = Math.max(1, Math.round(height * RENDER_SCALE));
            float scale = sw / (float) width;

            if (node == null) {
                node = new RenderNode("aurora-builtin-glass");
            }
            node.setPosition(0, 0, sw, sh);

            boolean changed = bitmap != lastBitmap
                    || location[0] != lastLocX || location[1] != lastLocY
                    || width != lastWidth || height != lastHeight
                    || radius != lastRadius
                    || nodeWidth != sw || nodeHeight != sh;
            if (changed) {
                float bx = bitmap.getWidth() / (float) backdrop.screenWidth;
                float by = bitmap.getHeight() / (float) backdrop.screenHeight;
                Rect src = new Rect(
                        Math.round(location[0] * bx),
                        Math.round(location[1] * by),
                        Math.round((location[0] + width) * bx),
                        Math.round((location[1] + height) * by));
                if (src.isEmpty()
                        || !src.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
                    src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                }
                RecordingCanvas recording = node.beginRecording(sw, sh);
                recording.drawBitmap(bitmap, src, new RectF(0, 0, sw, sh), bitmapPaint);
                node.endRecording();
                effect = LiveGlassEffect.build(
                        view.getResources().getDisplayMetrics().density,
                        0, 0, sw, sh, radius * scale, scale, 16f, true);
                node.setRenderEffect(effect);
                lastBitmap = bitmap;
                lastLocX = location[0];
                lastLocY = location[1];
                lastWidth = width;
                lastHeight = height;
                lastRadius = radius;
                nodeWidth = sw;
                nodeHeight = sh;
            }

            clipPath.reset();
            clipPath.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clipPath);
            canvas.scale(1f / scale, 1f / scale);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
