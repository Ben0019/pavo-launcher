package com.ben.pavo;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * A small "liquid glass" pass: a refracting rim that magnifies the backdrop near the edge,
 * a specular crescent, a soft inner shadow, a bright top-left rim and an accent hairline.
 * It does not paint the body of the glass, so surfaces can layer it over their own tint
 * and drifting highlights.
 */
final class LiquidGlass {
    private LiquidGlass() {}

    static int lighten(int color) {
        return Color.rgb((Color.red(color) + 255) / 2,
                (Color.green(color) + 255) / 2,
                (Color.blue(color) + 255) / 2);
    }

    private static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /**
     * @param edge       thickness of the refracting rim in pixels
     * @param bg         backdrop bitmap already mapped by {@code bgMatrix} (may be null)
     * @param bgMatrix   matrix that maps {@code bg} onto this view
     */
    static void draw(Canvas c, RectF rect, float radius, float edge, Bitmap bg,
                     Matrix bgMatrix, int accent) {
        draw(c, rect, radius, edge, bg, bgMatrix, accent, false, 1f);
    }

    static void draw(Canvas c, RectF rect, float radius, float edge, Bitmap bg,
                     Matrix bgMatrix, int accent, boolean clear) {
        draw(c, rect, radius, edge, bg, bgMatrix, accent, clear, 1f);
    }

    static void draw(Canvas c, RectF rect, float radius, float edge, Bitmap bg,
                     Matrix bgMatrix, int accent, boolean clear, float sheen) {
        final float s = clear ? Math.max(0.15f, Math.min(1f, sheen)) : 1f;
        float w = rect.width();
        float h = rect.height();
        if (w <= 2 || h <= 2) return;
        int light = lighten(accent);

        if (bg != null && bgMatrix != null && edge > 1f) {
            Path outer = new Path();
            outer.addRoundRect(rect, radius, radius, Path.Direction.CW);
            RectF inner = new RectF(rect);
            inner.inset(edge, edge);
            Path innerPath = new Path();
            innerPath.addRoundRect(inner, Math.max(0f, radius - edge),
                    Math.max(0f, radius - edge), Path.Direction.CW);
            outer.op(innerPath, Path.Op.DIFFERENCE);

            c.save();
            c.clipPath(outer);
            Matrix m = new Matrix(bgMatrix);
            float lens = clear ? 1.19f : 1.10f;
            m.postScale(lens, lens, rect.centerX(), rect.centerY());
            BitmapShader shader = new BitmapShader(bg, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setShader(shader);
            p.setAlpha(clear ? 0xD8 : 0xB4);
            c.drawRect(rect, p);
            c.restore();

            Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
            wash.setStyle(Paint.Style.FILL);
            wash.setColor(alpha(light, clear ? Math.round(0x22 * s) : 0x16));
            c.drawPath(outer, wash);

            if (clear) {
                // A very subtle chromatic split at the refracting rim; Apple's
                // glass shows this on strong edges without turning into a rainbow.
                Paint fringe = new Paint(Paint.ANTI_ALIAS_FLAG);
                fringe.setStyle(Paint.Style.STROKE);
                fringe.setStrokeWidth(Math.max(1.4f, edge * 0.26f));
                fringe.setShader(new LinearGradient(rect.left, rect.top,
                        rect.right, rect.bottom,
                        new int[]{alpha(0xFFFF6B6B, Math.round(0x40 * s)),
                                0x00000000, alpha(0xFF5B8CFF, Math.round(0x40 * s))},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                RectF fr = new RectF(rect);
                fr.inset(edge * 0.55f, edge * 0.55f);
                c.drawRoundRect(fr, Math.max(0f, radius - edge * 0.55f),
                        Math.max(0f, radius - edge * 0.55f), fringe);
            }
        }

        // bright specular crescent across the top
        Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
        spec.setShader(new RadialGradient(
                rect.left + w * 0.26f, rect.top + h * 0.04f, Math.max(w, h) * 0.55f,
                alpha(light, clear ? Math.round(0x8A * s) : 0x66), alpha(light, 0x00),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, spec);

        // soft dark inner falloff at the bottom
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setShader(new LinearGradient(0, rect.bottom - h * 0.40f, 0, rect.bottom,
                0x00000000, clear ? 0x46000000 : 0x4A000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, shade);

        // top-left rim light running to the bottom-right
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.2f, edge * 0.16f));
        rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{alpha(light, clear ? Math.round(0xFF * s) : 0xE6),
                        alpha(light, clear ? Math.round(0x3A * s) : 0x26),
                        alpha(light, clear ? Math.round(0xCC * s) : 0x99)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        RectF in = new RectF(rect);
        in.inset(0.8f, 0.8f);
        c.drawRoundRect(in, Math.max(0f, radius - 0.8f), Math.max(0f, radius - 0.8f), rim);

        // hairline outer edge
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1f);
        border.setColor(alpha(light, clear ? Math.round(0x55 * s) : 0x3A));
        c.drawRoundRect(in, Math.max(0f, radius - 0.8f), Math.max(0f, radius - 0.8f), border);

        if (clear) {
            // Bright top edge and darker bottom edge make the glass read as a
            // thick, floating lens rather than a flat translucent rectangle.
            Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
            top.setStyle(Paint.Style.STROKE);
            top.setStrokeWidth(Math.max(1.2f, edge * 0.16f));
            top.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.top,
                    new int[]{0x00FFFFFF, alpha(0xFFFFFFFF, Math.round(0xE6 * s)),
                            0x00FFFFFF},
                    new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
            Path tp = new Path();
            tp.moveTo(rect.left + radius, rect.top + 1.6f);
            tp.lineTo(rect.right - radius, rect.top + 1.6f);
            c.drawPath(tp, top);

            Paint bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
            bottom.setStyle(Paint.Style.STROKE);
            bottom.setStrokeWidth(Math.max(1.2f, edge * 0.18f));
            bottom.setShader(new LinearGradient(rect.left, rect.bottom, rect.right,
                    rect.bottom, new int[]{0x00000000, 0x66000000, 0x00000000},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            Path bp = new Path();
            bp.moveTo(rect.left + radius, rect.bottom - 1.6f);
            bp.lineTo(rect.right - radius, rect.bottom - 1.6f);
            c.drawPath(bp, bottom);
        }
    }
}
