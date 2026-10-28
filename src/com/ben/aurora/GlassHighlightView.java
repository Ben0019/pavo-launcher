package com.ben.aurora;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

/**
 * The directional rim from Kyant0/backdrop's Highlight.Default: a white,
 * 45-degree additive edge that follows the rounded shape. It is drawn above
 * the live glass but below the drawer content.
 */
final class GlassHighlightView extends View {
    private static final String SDF =
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "  if (coord.x >= 0.0) {\n" +
            "    if (coord.y <= 0.0) return radii.y;\n" +
            "    else return radii.z;\n" +
            "  } else {\n" +
            "    if (coord.y <= 0.0) return radii.x;\n" +
            "    else return radii.w;\n" +
            "  }\n" +
            "}\n" +
            "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "  float2 q = abs(coord) - (halfSize - float2(radius));\n" +
            "  float outside = length(max(q, 0.0)) - radius;\n" +
            "  float inside = min(max(q.x, q.y), 0.0);\n" +
            "  return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "  float2 q = abs(coord) - (halfSize - float2(radius));\n" +
            "  if (q.x >= 0.0 || q.y >= 0.0) return sign(coord) * normalize(max(q, 0.0));\n" +
            "  float gx = step(q.y, q.x);\n" +
            "  return sign(coord) * float2(gx, 1.0 - gx);\n" +
            "}\n";

    private static final String SHADER =
            "uniform float2 size;\n" +
            "uniform float4 cornerRadii;\n" +
            "layout(color) uniform half4 color;\n" +
            "uniform float angle;\n" +
            "uniform float falloff;\n" +
            SDF +
            "half4 main(float2 coord) {\n" +
            "  float2 halfSize = size * 0.5;\n" +
            "  float2 centered = coord - halfSize;\n" +
            "  float radius = radiusAt(coord, cornerRadii);\n" +
            "  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "  float2 grad = gradSdRoundedRect(centered, halfSize, gradRadius);\n" +
            "  float2 normal = float2(cos(angle), sin(angle));\n" +
            "  float intensity = pow(abs(dot(grad, normal)), falloff);\n" +
            "  return color * intensity;\n" +
            "}\n";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RuntimeShader shader;
    private float radiusPx;
    private boolean unavailable;

    private static Paint sharedPaint;
    private static RuntimeShader sharedShader;
    private static boolean sharedUnavailable;

    GlassHighlightView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dpf(context, 1f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.setBlendMode(BlendMode.PLUS);
        }
    }

    void setRadiusDp(float dp) {
        radiusPx = Ui.dpf(getContext(), dp);
        invalidate();
    }

    /** Draw the same rim for a card rendered directly by GlassPanel. */
    static void drawHighlight(Canvas canvas, int w, int h, float radiusPx, float density) {
        if (w < 4 || h < 4) return;
        float radius = Math.min(radiusPx, Math.min(w, h) / 2f);
        float stroke = Math.max(1f, 0.5f * density * 2f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && canvas.isHardwareAccelerated() && !sharedUnavailable) {
            try {
                if (sharedShader == null) sharedShader = new RuntimeShader(SHADER);
                if (sharedPaint == null) {
                    sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    sharedPaint.setStyle(Paint.Style.STROKE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        sharedPaint.setBlendMode(BlendMode.PLUS);
                    }
                }
                sharedShader.setFloatUniform("size", w, h);
                sharedShader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
                sharedShader.setFloatUniform("angle", (float) Math.toRadians(45f));
                sharedShader.setFloatUniform("falloff", 1f);
                sharedShader.setColorUniform("color", 0x80FFFFFF);
                sharedPaint.setShader(sharedShader);
                sharedPaint.setStrokeWidth(stroke);
                float inset = stroke / 2f;
                canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                        Math.max(0f, radius - inset), Math.max(0f, radius - inset),
                        sharedPaint);
                sharedPaint.setShader(null);
                return;
            } catch (Throwable t) {
                sharedUnavailable = true;
            }
        }
        if (sharedPaint == null) sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sharedPaint.setShader(null);
        sharedPaint.setStyle(Paint.Style.STROKE);
        sharedPaint.setStrokeWidth(stroke);
        sharedPaint.setColor(0x38FFFFFF);
        float inset = stroke / 2f;
        canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                Math.max(0f, radius - inset), Math.max(0f, radius - inset), sharedPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w < 4 || h < 4) return;
        float radius = Math.min(radiusPx, Math.min(w, h) / 2f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && canvas.isHardwareAccelerated() && !unavailable) {
            try {
                if (shader == null) shader = new RuntimeShader(SHADER);
                shader.setFloatUniform("size", w, h);
                shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
                shader.setFloatUniform("angle", (float) Math.toRadians(45f));
                shader.setFloatUniform("falloff", 1f);
                shader.setColorUniform("color", 0x80FFFFFF);
                paint.setShader(shader);
                float inset = paint.getStrokeWidth() / 2f;
                canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                        Math.max(0f, radius - inset), Math.max(0f, radius - inset), paint);
                paint.setShader(null);
                return;
            } catch (Throwable t) {
                unavailable = true;
            }
        }
        paint.setShader(null);
        paint.setColor(0x38FFFFFF);
        float inset = paint.getStrokeWidth() / 2f;
        canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                Math.max(0f, radius - inset), Math.max(0f, radius - inset), paint);
    }
}
