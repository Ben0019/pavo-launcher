package com.ben.aurora;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * Native Android port of the important part of Kyant0/backdrop's liquid-glass
 * lens: rounded-rect edge refraction with optional chromatic dispersion.
 *
 * The backdrop is the launcher's blurred snapshot, sampled through a
 * BitmapShader. The AGSL source is adapted from Kyant0/backdrop (Apache-2.0,
 * https://github.com/Kyant0/backdrop), the same effect used by BitChord.
 */
final class LiquidGlassShader {
    private static final String SHADER =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            "float radiusAt(float2 coord, float4 radii) {\n" +
            "  if (coord.x >= 0.0) {\n" +
            "    if (coord.y <= 0.0) return radii.y;\n" +
            "    return radii.z;\n" +
            "  }\n" +
            "  if (coord.y <= 0.0) return radii.x;\n" +
            "  return radii.w;\n" +
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
            "}\n" +
            "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n" +
            "half4 main(float2 coord) {\n" +
            "  float2 halfSize = size * 0.5;\n" +
            "  float2 centered = coord - halfSize;\n" +
            "  float radius = radiusAt(centered, cornerRadii);\n" +
            "  float sd = sdRoundedRect(centered, halfSize, radius);\n" +
            "  if (-sd >= refractionHeight) return content.eval(coord);\n" +
            "  sd = min(sd, 0.0);\n" +
            "  float d = circleMap(1.0 - (-sd) / refractionHeight) * refractionAmount;\n" +
            "  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "  float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius)\n" +
            "      + depthEffect * normalize(centered + float2(0.0001, 0.0001)));\n" +
            "  float2 refracted = coord + d * grad;\n" +
            "  float dispersion = chromaticAberration * ((centered.x * centered.y)\n" +
            "      / max(1.0, halfSize.x * halfSize.y));\n" +
            "  float2 spread = d * grad * dispersion;\n" +
            "  half4 color = half4(0.0);\n" +
            "  half4 r = content.eval(refracted + spread);\n" +
            "  color.r += r.r / 3.5; color.a += r.a / 7.0;\n" +
            "  half4 o = content.eval(refracted + spread * 0.66);\n" +
            "  color.r += o.r / 3.5; color.g += o.g / 7.0; color.a += o.a / 7.0;\n" +
            "  half4 y = content.eval(refracted + spread * 0.33);\n" +
            "  color.r += y.r / 3.5; color.g += y.g / 3.5; color.a += y.a / 7.0;\n" +
            "  half4 g = content.eval(refracted);\n" +
            "  color.g += g.g / 3.5; color.a += g.a / 7.0;\n" +
            "  half4 c = content.eval(refracted - spread * 0.33);\n" +
            "  color.g += c.g / 3.5; color.b += c.b / 3.0; color.a += c.a / 7.0;\n" +
            "  half4 b = content.eval(refracted - spread * 0.66);\n" +
            "  color.b += b.b / 3.0; color.a += b.a / 7.0;\n" +
            "  half4 p = content.eval(refracted - spread);\n" +
            "  color.r += p.r / 7.0; color.b += p.b / 3.0; color.a += p.a / 7.0;\n" +
            "  return color;\n" +
            "}\n";

    private RuntimeShader shader;
    private BitmapShader content;
    private Bitmap lastBitmap;
    private Paint paint;
    private boolean unavailable;

    boolean draw(Canvas canvas, float width, float height, float radius,
            Bitmap bitmap, Matrix matrix, float alpha, float density) {
        if (Build.VERSION.SDK_INT < 33 || unavailable || bitmap == null
                || bitmap.isRecycled() || width <= 2f || height <= 2f) {
            return false;
        }
        try {
            if (shader == null) {
                shader = new RuntimeShader(SHADER);
                content = new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                lastBitmap = bitmap;
                paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                ColorMatrix saturation = new ColorMatrix();
                saturation.setSaturation(1.5f);
                paint.setColorFilter(new ColorMatrixColorFilter(saturation));
            }
            if (bitmap != lastBitmap) {
                content = new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP);
                lastBitmap = bitmap;
            }
            content.setLocalMatrix(matrix);
            shader.setInputShader("content", content);
            shader.setFloatUniform("size", width, height);
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            shader.setFloatUniform("refractionHeight", Math.max(1f, 22f * density));
            shader.setFloatUniform("refractionAmount", Math.max(1f, 22f * density));
            shader.setFloatUniform("depthEffect", 1f);
            shader.setFloatUniform("chromaticAberration", 1f);
            paint.setShader(shader);
            paint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
            canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);
            paint.setShader(null);
            return true;
        } catch (Throwable t) {
            unavailable = true;
            return false;
        }
    }
}
