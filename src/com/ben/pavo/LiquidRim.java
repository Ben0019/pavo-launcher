package com.ben.pavo;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/** The polished capsule rim shared by the dock and the clear liquid menus. */
final class LiquidRim {
    private LiquidRim() {}

    static void draw(Canvas canvas, RectF rect, float radius, float sheen) {
        float w = rect.width();
        float h = rect.height();
        if (w <= 2f || h <= 2f) return;
        float s = Math.max(0.55f, Math.min(1f, sheen));
        float density = canvas.getDensity() / 160f;

        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.2f, 1.3f * density));
        rim.setShader(new LinearGradient(0f, 0f, w, h,
                new int[]{0xB0FFFFFF, 0x33FFFFFF, 0x90FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        RectF rimRect = new RectF(rect.left + 0.7f, rect.top + 0.7f,
                rect.right - 0.7f, rect.bottom - 0.7f);
        canvas.drawRoundRect(rimRect, radius, radius, rim);
        rim.setShader(null);

        Paint topGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        topGlow.setShader(new LinearGradient(0f, rect.top, 0f,
                rect.top + h * 0.55f,
                0x10FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, topGlow);
        topGlow.setShader(null);

        Paint fringe = new Paint(Paint.ANTI_ALIAS_FLAG);
        fringe.setStyle(Paint.Style.STROKE);
        fringe.setStrokeWidth(Math.max(1.6f, 1.5f * density));
        fringe.setShader(new LinearGradient(rect.left, 0f, rect.right, 0f,
                new int[]{0x20FF5C7A, 0x0012D3EE, 0x205B8CFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        RectF fr = new RectF(rect.left + 1.6f, rect.top + 1.6f,
                rect.right - 1.6f, rect.bottom - 1.6f);
        canvas.drawRoundRect(fr, Math.max(0f, radius - 1.6f),
                Math.max(0f, radius - 1.6f), fringe);
        fringe.setShader(null);
    }
}
