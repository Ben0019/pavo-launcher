package com.ben.aurora;

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;

        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.5f);
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
        shader.setFloatUniform("refractionHeight", 24f * density * scale);
        shader.setFloatUniform("refractionAmount", -24f * density * scale);
        shader.setFloatUniform("depthEffect", 1f);
        if (chromatic) shader.setFloatUniform("chromaticAberration", 1f);
        RenderEffect lens = RenderEffect.createRuntimeShaderEffect(shader, "content");
        return RenderEffect.createChainEffect(lens, blurred);
    }
}
