package com.ben.pavo;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * Live version of the liquid-glass-react / BitChord material:
 *
 *   backdrop blur + saturation, then an edge-only rounded-rect displacement
 *   with chromatic dispersion.
 *
 * The effect is applied to the real home layer while the app drawer is open,
 * so the glass really reacts to the page underneath instead of a frozen
 * screenshot.
 */
final class LiveGlassEffect {
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
            "  float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "  float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
            "  float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
            "  return outside + inside;\n" +
            "}\n" +
            "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
            "  float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
            "  if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
            "    return sign(coord) * normalize(max(cornerCoord, 0.0));\n" +
            "  } else {\n" +
            "    float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
            "    return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
            "  }\n" +
            "}\n";

    private static final String LENS =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            SDF +
            "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n" +
            "half4 main(float2 coord) {\n" +
            "  float2 halfSize = size * 0.5;\n" +
            "  float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "  float radius = radiusAt(coord, cornerRadii);\n" +
            "  float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "  if (-sd >= refractionHeight) return content.eval(coord);\n" +
            "  sd = min(sd, 0.0);\n" +
            "  float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "  float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)\n" +
            "      + depthEffect * normalize(centeredCoord));\n" +
            "  return content.eval(coord + d * grad);\n" +
            "}\n";

    private static final String LENS_DISPERSE =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float2 offset;\n" +
            "uniform float4 cornerRadii;\n" +
            "uniform float refractionHeight;\n" +
            "uniform float refractionAmount;\n" +
            "uniform float depthEffect;\n" +
            "uniform float chromaticAberration;\n" +
            SDF +
            "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n" +
            "half4 main(float2 coord) {\n" +
            "  float2 halfSize = size * 0.5;\n" +
            "  float2 centeredCoord = (coord + offset) - halfSize;\n" +
            "  float radius = radiusAt(coord, cornerRadii);\n" +
            "  float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n" +
            "  if (-sd >= refractionHeight) return content.eval(coord);\n" +
            "  sd = min(sd, 0.0);\n" +
            "  float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n" +
            "  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n" +
            "  float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)\n" +
            "      + depthEffect * normalize(centeredCoord));\n" +
            "  float2 refractedCoord = coord + d * grad;\n" +
            "  float dispersionIntensity = chromaticAberration\n" +
            "      * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n" +
            "  float2 dispersedCoord = d * grad * dispersionIntensity;\n" +
            "  half4 color = half4(0.0);\n" +
            "  half4 red = content.eval(refractedCoord + dispersedCoord);\n" +
            "  color.r += red.r / 3.5; color.a += red.a / 7.0;\n" +
            "  half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n" +
            "  color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;\n" +
            "  half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n" +
            "  color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;\n" +
            "  half4 green = content.eval(refractedCoord);\n" +
            "  color.g += green.g / 3.5; color.a += green.a / 7.0;\n" +
            "  half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n" +
            "  color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n" +
            "  half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n" +
            "  color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n" +
            "  half4 purple = content.eval(refractedCoord - dispersedCoord);\n" +
            "  color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;\n" +
            "  return color;\n" +
            "}\n";

    private LiveGlassEffect() {}

    static RenderEffect build(float density, int cardLeft, int cardTop,
                              int cardWidth, int cardHeight, float radiusPx,
                              float scale) {
        return build(density, cardLeft, cardTop, cardWidth, cardHeight, radiusPx,
                scale, 8f);
    }

    static RenderEffect build(float density, int cardLeft, int cardTop,
                              int cardWidth, int cardHeight, float radiusPx,
                              float scale, float blurDp) {
        return build(density, cardLeft, cardTop, cardWidth, cardHeight, radiusPx,
                scale, blurDp, false);
    }

    static RenderEffect build(float density, int cardLeft, int cardTop,
                              int cardWidth, int cardHeight, float radiusPx,
                              float scale, float blurDp, boolean chromatic) {
        return build(density, cardLeft, cardTop, cardWidth, cardHeight, radiusPx,
                scale, blurDp, chromatic, 1.5f, 0f);
    }

    static RenderEffect build(float density, int cardLeft, int cardTop,
                              int cardWidth, int cardHeight, float radiusPx,
                              float scale, float blurDp, boolean chromatic,
                              float saturationAmount) {
        return build(density, cardLeft, cardTop, cardWidth, cardHeight, radiusPx,
                scale, blurDp, chromatic, saturationAmount, 0f);
    }

    static RenderEffect build(float density, int cardLeft, int cardTop,
                              int cardWidth, int cardHeight, float radiusPx,
                              float scale, float blurDp, boolean chromatic,
                              float saturationAmount, float darkness) {
        return build(density, cardLeft, cardTop, cardWidth, cardHeight, radiusPx,
                scale, blurDp, chromatic, saturationAmount, darkness, 24f, 24f);
    }

    /** Same stack with an explicit lens: {@code refractionDp} is the rim width
     *  and {@code bendDp} how far the backdrop is pulled at the very edge. */
    static RenderEffect build(float density, int cardLeft, int cardTop,
                              int cardWidth, int cardHeight, float radiusPx,
                              float scale, float blurDp, boolean chromatic,
                              float saturationAmount, float darkness,
                              float refractionDp, float bendDp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(Math.max(0f, saturationAmount));
        float scaleDown = 1f - Math.max(0f, Math.min(1f, darkness));
        if (scaleDown < 0.999f) {
            ColorMatrix dim = new ColorMatrix(new float[]{
                    scaleDown, 0f, 0f, 0f, 0f,
                    0f, scaleDown, 0f, 0f, 0f,
                    0f, 0f, scaleDown, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f});
            saturation.postConcat(dim);
        }
        RenderEffect color = RenderEffect.createColorFilterEffect(
                new ColorMatrixColorFilter(saturation));
        float blur = blurDp * density * scale;
        RenderEffect blurred = RenderEffect.createBlurEffect(
                blur, blur, color, Shader.TileMode.CLAMP);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || cardWidth <= 2 || cardHeight <= 2) {
            return blurred;
        }

        RuntimeShader shader = new RuntimeShader(chromatic ? LENS_DISPERSE : LENS);
        shader.setFloatUniform("size", cardWidth, cardHeight);
        shader.setFloatUniform("offset", -cardLeft, -cardTop);
        shader.setFloatUniform("cornerRadii", radiusPx, radiusPx, radiusPx, radiusPx);
        shader.setFloatUniform("refractionHeight", refractionDp * density * scale);
        shader.setFloatUniform("refractionAmount", -bendDp * density * scale);
        shader.setFloatUniform("depthEffect", 1f);
        if (chromatic) shader.setFloatUniform("chromaticAberration", 1f);
        RenderEffect lens = RenderEffect.createRuntimeShaderEffect(shader, "content");
        return RenderEffect.createChainEffect(lens, blurred);
    }

    /**
     * Dock lens: a gravity-lens refraction profile (almost all of the bend in
     * the outermost pixels, a gentle tail inwards), per-channel dispersion and
     * a directional rim light. Samples inwards only, so it never reads outside
     * the recorded backdrop.
     */
    private static final String DOCK_LENS =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float radius;\n" +
            "uniform float bevel;\n" +
            "uniform float refractPx;\n" +
            "uniform float falloff;\n" +
            "uniform float dispersion;\n" +
            "uniform float2 lightDir;\n" +
            "uniform float spec;\n" +
            "uniform float hairPx;\n" +
            "float sdBox(float2 p, float2 b, float r) {\n" +
            "  float2 q = abs(p) - (b - float2(r));\n" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "half4 main(float2 coord) {\n" +
            "  float2 hs = size * 0.5;\n" +
            "  float2 p = coord - hs;\n" +
            "  float d = min(sdBox(p, hs, radius), 0.0);\n" +
            "  if (-d >= bevel) return content.eval(coord);\n" +
            "  float2 n = float2(\n" +
            "      sdBox(p + float2(1.0, 0.0), hs, radius) - sdBox(p - float2(1.0, 0.0), hs, radius),\n" +
            "      sdBox(p + float2(0.0, 1.0), hs, radius) - sdBox(p - float2(0.0, 1.0), hs, radius));\n" +
            "  float nl = length(n);\n" +
            "  n = nl > 0.0001 ? n / nl : float2(0.0, -1.0);\n" +
            "  float t = clamp(-d / bevel, 0.0, 1.0);\n" +
            "  float gB = pow(5.0, -falloff);\n" +
            "  float slope = (pow(1.0 + 4.0 * t, -falloff) - gB) / (1.0 - gB);\n" +
            "  float2 off = n * (-slope * refractPx);\n" +
            "  float2 lo = float2(0.0);\n" +
            "  float2 cR = clamp(coord + off * (1.0 - dispersion * slope), lo, size);\n" +
            "  float2 cG = clamp(coord + off, lo, size);\n" +
            "  float2 cB = clamp(coord + off * (1.0 + dispersion * slope), lo, size);\n" +
            "  float3 col = float3(content.eval(cR).r, content.eval(cG).g, content.eval(cB).b);\n" +
            "  float facing = dot(n, -lightDir);\n" +
            "  float lobeF = pow(max(facing, 0.0), 4.5);\n" +
            "  float lobeB = pow(max(-facing, 0.0), 4.5);\n" +
            "  float hair = clamp(1.0 - abs(d + hairPx) / (hairPx * 1.6), 0.0, 1.0);\n" +
            "  float glow = pow(clamp(1.0 - (-d - hairPx * 2.0) / (hairPx * 5.0), 0.0, 1.0), 1.5);\n" +
            "  col += float3(spec * (hair * 0.75 * (lobeF + lobeB) + glow * 0.12 * lobeF));\n" +
            "  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);\n" +
            "}\n";

    /** Blur + saturation + dimming, then the dock lens. Sizes are in dp. */
    static RenderEffect buildDock(float density, int cardWidth, int cardHeight,
                                  float radiusPx, float scale, float blurDp,
                                  float saturationAmount, float darkness,
                                  float bevelDp, float refractDp, float falloff,
                                  float dispersion, float spec) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || cardWidth <= 2 || cardHeight <= 2) {
            return build(density, 0, 0, cardWidth, cardHeight, radiusPx, scale, blurDp,
                    true, saturationAmount, darkness);
        }
        ColorMatrix color = new ColorMatrix();
        color.setSaturation(Math.max(0f, saturationAmount));
        float keep = 1f - Math.max(0f, Math.min(1f, darkness));
        if (keep < 0.999f) {
            color.postConcat(new ColorMatrix(new float[]{
                    keep, 0f, 0f, 0f, 0f,
                    0f, keep, 0f, 0f, 0f,
                    0f, 0f, keep, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f}));
        }
        float blur = blurDp * density * scale;
        RenderEffect blurred = RenderEffect.createBlurEffect(blur, blur,
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(color)),
                Shader.TileMode.CLAMP);
        float px = density * scale;
        RuntimeShader shader = new RuntimeShader(DOCK_LENS);
        shader.setFloatUniform("size", cardWidth, cardHeight);
        shader.setFloatUniform("radius", radiusPx);
        shader.setFloatUniform("bevel", bevelDp * px);
        shader.setFloatUniform("refractPx", refractDp * px);
        shader.setFloatUniform("falloff", falloff);
        shader.setFloatUniform("dispersion", dispersion);
        shader.setFloatUniform("lightDir", 0.5f, 0.85f);
        shader.setFloatUniform("spec", spec);
        shader.setFloatUniform("hairPx", Math.max(1f, 0.75f * px));
        return RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "content"), blurred);
    }
}
