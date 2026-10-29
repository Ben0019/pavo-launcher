package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * The drop shadow from BitChord's Shadow.Default: 24dp blur, 4dp downward
 * offset, black at 10%. Drawn in a larger host view so the blur is not clipped
 * by the glass card's rounded outline.
 */
final class LiquidGlassShadowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RenderNode node;
    private float radiusPx;
    private float padPx;
    private float blurPx;
    private float offsetPx;
    private boolean effectSet;

    LiquidGlassShadowView(Context context) {
        super(context);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        radiusPx = Ui.dpf(context, 26f);
        blurPx = Ui.dpf(context, 24f);
        offsetPx = Ui.dpf(context, 4f);
        padPx = blurPx * 2f + offsetPx;
        paint.setColor(0x1A000000); // black, 10%
    }

    float requiredPadding() {
        return padPx;
    }

    void setRadiusDp(float dp) {
        radiusPx = Ui.dpf(getContext(), dp);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !canvas.isHardwareAccelerated()) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w < 4 || h < 4) return;
        try {
            if (node == null) {
                node = new RenderNode("aurora-glass-drop-shadow");
            }
            node.setPosition(0, 0, w, h);
            if (!effectSet) {
                node.setRenderEffect(RenderEffect.createBlurEffect(
                        blurPx, blurPx, Shader.TileMode.CLAMP));
                effectSet = true;
            }
            RecordingCanvas recording = node.beginRecording(w, h);
            recording.drawRoundRect(padPx, padPx + offsetPx,
                    w - padPx, h - padPx + offsetPx,
                    radiusPx, radiusPx, paint);
            node.endRecording();
            canvas.drawRenderNode(node);
        } catch (Throwable ignored) {
            // Shadows are decorative; never let one break the drawer.
        }
    }
}
