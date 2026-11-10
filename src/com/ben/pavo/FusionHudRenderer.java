package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One adaptive performance panel.
 *
 * <p>Pure canvas drawing with a monospace bold typeface. A hero FPS block sits
 * above a grid of metric modules; every metric toggle adds or removes one
 * module, and optional core bars, a frametime graph and a footer grow with the
 * card. There are no fixed size modes — the panel hugs its content and the
 * scale gesture changes only its size.
 */
final class FusionHudRenderer {

    private static final int COL_GPU = 0xFF5EE08A;
    private static final int COL_CPU = 0xFF58A6FF;
    private static final int COL_RAM = 0xFFFF7BC0;
    private static final int COL_BAT = 0xFFFFAB5E;
    private static final int COL_FPS = 0xFFFF6B6B;
    private static final int COL_GRAPH = 0xFF5EE08A;
    private static final int COL_VALUE = 0xFFF2F5F9;
    private static final int COL_DIM = 0xFF9AA4B2;
    private static final int COL_LOCK = 0xFFFFD27F;
    private static final int COL_FAN = 0xFF4DD0E1;
    private static final int COL_POWER = 0xFFFFD166;

    private static final int GRAPH_CAP = 60;
    private static final int HISTORY_CAP = 180;

    private static final class Span {
        final String text;
        final int color;
        final float size;

        Span(String text, int color, float size) {
            this.text = text;
            this.color = color;
            this.size = size;
        }
    }

    private static final class Glyph {
        final float x;
        final float baseline;
        final float size;
        final String text;
        final int color;

        Glyph(float x, float baseline, String text, int color, float size) {
            this.x = x;
            this.baseline = baseline;
            this.text = text;
            this.color = color;
            this.size = size;
        }
    }

    private static final class Module {
        final String key;
        final int color;
        final List<Span> value;
        final List<Span> detail;
        /** 0..1 inline meter level, or -1 when the metric is not a percentage. */
        final float level;

        Module(String key, int color, List<Span> value, List<Span> detail, float level) {
            this.key = key;
            this.color = color;
            this.value = value;
            this.detail = detail;
            this.level = level;
        }
    }

    private final Context ctx;
    private final float density;
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path graphPath = new Path();
    private final Path graphFillPath = new Path();

    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final ArrayList<RectF> dividers = new ArrayList<>();
    private final ArrayList<RectF> barTracks = new ArrayList<>();
    private final ArrayList<RectF> barFills = new ArrayList<>();
    private final ArrayList<Integer> barColors = new ArrayList<>();
    private final ArrayDeque<Float> graphSamples = new ArrayDeque<>();
    private final ArrayDeque<Float> fpsHistory = new ArrayDeque<>();
    private RectF graphRect;

    private Prefs prefs;
    private PerfStats stats;
    private boolean locked;
    private float opacity = 0.88f;
    private float scale = 1f;
    private float colorIntensity = 1f;
    private boolean outline = true;
    private int accent = 0xFFA9BEFF;
    private String engineLabel = "";
    private String presetLabel = "";

    // Metric toggles, mapped from Prefs.
    private boolean showFps = true;
    private boolean showCpuTemp = true;
    private boolean showCpuClock = true;
    private boolean showCpuUsage = true;
    private boolean showGpuTemp = true;
    private boolean showGpuClock = true;
    private boolean showGpuUsage = true;
    private boolean showRam = true;
    private boolean showBattery = true;
    private boolean showFan = true;
    private boolean showGraph = true;
    private boolean showEngine = true;
    private boolean fpsDecimal = true;

    private float contentW;
    private float contentH;
    private boolean dirty = true;

    FusionHudRenderer(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        ThemeColors.initialize(c);
        accent = ThemeColors.accent();
        measurePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        drawPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        strokePaint.setStyle(Paint.Style.STROKE);
        graphPaint.setStyle(Paint.Style.STROKE);
        graphPaint.setStrokeCap(Paint.Cap.ROUND);
        graphPaint.setStrokeJoin(Paint.Join.ROUND);
        presetLabel = readPreset();
        engineLabel = presetLabel;
    }

    void applyPrefs(Prefs p) {
        prefs = p;
        opacity = Math.max(0f, Math.min(1f, p.perfOverlayAlpha() / 100f));
        showFps = p.perfFps();
        showCpuTemp = p.perfCpuTemp();
        showCpuClock = p.perfCpuClock();
        showCpuUsage = p.perfCpuUsage();
        showGpuTemp = p.perfGpuTemp();
        showGpuClock = p.perfGpuClock();
        showGpuUsage = p.perfGpuUsage();
        showRam = p.perfRam();
        showBattery = p.perfBattery();
        showFan = p.perfFan();
        showGraph = p.perfFpsGraph();
        presetLabel = readPreset();
        engineLabel = presetLabel;
        accent = ThemeColors.accent();
        dirty = true;
        rebuild();
    }

    void update(PerfStats s) {
        stats = s;
        float fps = fpsNow();
        if (fps > 0f) {
            if (graphSamples.size() >= GRAPH_CAP) graphSamples.removeFirst();
            graphSamples.addLast(1000f / Math.max(1f, fps));
            if (fpsHistory.size() >= HISTORY_CAP) fpsHistory.removeFirst();
            fpsHistory.addLast(fps);
        }
        dirty = true;
        rebuild();
    }

    void setLocked(boolean v) {
        locked = v;
    }

    void setOpacity(int percent) {
        opacity = Math.max(0f, Math.min(1f, percent / 100f));
    }

    float width() {
        if (dirty) rebuild();
        return contentW;
    }

    float height() {
        if (dirty) rebuild();
        return contentH;
    }

    boolean graphShown() {
        return showGraph && showFps && stats != null && stats.fpsValid;
    }

    private boolean hasGraphSamples() {
        int valid = 0;
        for (Float f : graphSamples) if (!Float.isNaN(f)) valid++;
        return valid >= 2;
    }

    private float graphMin() {
        float min = Float.MAX_VALUE;
        for (Float f : graphSamples) if (!Float.isNaN(f)) min = Math.min(min, f);
        return min == Float.MAX_VALUE ? 0f : min;
    }

    private float graphMax() {
        float max = 0f;
        for (Float f : graphSamples) if (!Float.isNaN(f)) max = Math.max(max, f);
        return max;
    }

    // ------------------------------------------------------------------
    // Measurement helpers
    // ------------------------------------------------------------------

    private float sp(float v) {
        return v * density * scale;
    }

    private float measure(String text, float sizePx) {
        measurePaint.setTextSize(sizePx);
        return measurePaint.measureText(text == null ? "" : text);
    }

    private float lineH(float sizePx) {
        measurePaint.setTextSize(sizePx);
        Paint.FontMetrics fm = measurePaint.getFontMetrics();
        return fm.descent - fm.ascent;
    }

    private float ascent(float sizePx) {
        measurePaint.setTextSize(sizePx);
        return measurePaint.getFontMetrics().ascent;
    }

    private float runWidth(List<Span> spans) {
        float w = 0f;
        for (Span s : spans) w += measure(s.text, s.size);
        return w;
    }

    private float placeRun(float startX, float baseline, List<Span> spans) {
        float x = startX;
        for (Span s : spans) {
            glyphs.add(new Glyph(x, baseline, s.text, blend(s.color), s.size));
            x += measure(s.text, s.size);
        }
        return x;
    }

    private int blend(int color) {
        float i = Math.max(0f, Math.min(1f, colorIntensity));
        if (i >= 0.999f) return color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] *= i;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private static Span span(String text, int color, float size) {
        return new Span(text, color, size);
    }

    private List<Span> numUnit(String num, String unit, float numPx, float unitPx) {
        List<Span> out = new ArrayList<>();
        if (num == null) {
            out.add(span("\u2014", COL_DIM, numPx));
            out.add(span(unit, COL_DIM, unitPx));
        } else {
            out.add(span(num, COL_VALUE, numPx));
            out.add(span(unit, COL_DIM, unitPx));
        }
        return out;
    }

    private List<Span> valueUnit(String text, float numPx, float unitPx) {
        List<Span> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '/') i++;
            else break;
        }
        if (i <= 0) {
            out.add(span(text, COL_VALUE, numPx));
        } else {
            out.add(span(text.substring(0, i), COL_VALUE, numPx));
            out.add(span(text.substring(i), COL_DIM, unitPx));
        }
        return out;
    }

    private List<Span> tempSpans(float c, float numPx, float unitPx) {
        List<Span> out = new ArrayList<>();
        if (c <= 0f) return out;
        String txt = Math.round(c) + "\u00b0";
        int col = PerfOverlayView.heat((c - 32f) / 55f);
        out.add(span(txt.substring(0, txt.length() - 1), col, numPx));
        out.add(span("\u00b0", COL_DIM, unitPx));
        return out;
    }

    private static Span gap(float unitPx) {
        return span("  ", COL_DIM, unitPx);
    }

    // ------------------------------------------------------------------
    // Data formatting
    // ------------------------------------------------------------------

    private float fpsNow() {
        return stats != null && stats.fpsValid ? stats.fps : 0f;
    }

    private String one(float v) {
        return fpsDecimal ? String.format(Locale.US, "%.1f", v)
                : String.valueOf(Math.round(v));
    }

    private String fpsText() {
        return stats != null && stats.fpsValid ? one(fpsNow()) : "--";
    }

    private String fmt1(float v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private String lowText(int percentile) {
        int n = fpsHistory.size();
        if (n < 8) return null;
        float[] vals = new float[n];
        int i = 0;
        for (Float f : fpsHistory) vals[i++] = f;
        Arrays.sort(vals);
        int idx = Math.max(0, Math.min(n - 1, (int) (n * percentile / 100f)));
        return fmt1(vals[idx]);
    }

    private String avgText() {
        int n = fpsHistory.size();
        if (n == 0) return null;
        float sum = 0f;
        for (Float f : fpsHistory) sum += f;
        return fmt1(sum / n);
    }

    private String pct(boolean known, int value) {
        return known ? String.valueOf(value) : null;
    }

    private String ramUsedText() {
        if (stats == null || stats.memTotalKb <= 0) return null;
        float usedGb = Math.max(0f, (stats.memTotalKb - stats.memAvailKb) / 1_000_000f);
        return fmt1(usedGb);
    }

    private String ramTotalText() {
        if (stats == null || stats.memTotalKb <= 0) return null;
        int totalGb = Math.max(1, Math.round(stats.memTotalKb / 1_000_000f));
        return totalGb + "G";
    }

    private void rebuild() {
        if (!dirty) return;
        dirty = false;
        glyphs.clear();
        dividers.clear();
        barTracks.clear();
        barFills.clear();
        barColors.clear();
        graphRect = null;
        contentW = 0f;
        contentH = 0f;
        if (stats == null) return;
        buildModular();
    }

    private int presetColor() {
        if (!showEngine || engineLabel == null || engineLabel.isEmpty()
                || "FPS".equals(engineLabel)) {
            return COL_FPS;
        }
        return PerformanceTunerActivity.profileColor(engineLabel);
    }

    private String readPreset() {
        try {
            TuneConfig cfg = new TuneStore(ctx).global();
            String p = cfg == null || cfg.profile == null || cfg.profile.isEmpty()
                    ? "PAVO" : cfg.profile;
            return p.toUpperCase(Locale.US);
        } catch (Throwable t) {
            return "PAVO";
        }
    }

    private void buildModular() {
        final float pad = sp(10f);
        final float headerPx = sp(8.5f);
        final float heroPx = sp(30f);
        final float heroUnitPx = sp(10f);
        final float statValuePx = sp(9.5f);
        final float labelPx = sp(8.5f);
        final float valuePx = sp(14f);
        final float unitPx = sp(9f);
        final float detailPx = sp(9f);
        final float dotR = sp(2.4f);
        final float meterW = sp(30f);
        final float meterH = sp(4.5f);
        final float rowGap = sp(4f);
        final float sectionGap = sp(7f);
        final float ribbonH = sp(16f);

        String engine = showEngine && engineLabel != null ? engineLabel : "";
        boolean hasEngine = !engine.isEmpty() && !"FPS".equals(engine);
        boolean hero = showFps;

        // --- hero data --------------------------------------------------
        List<Span> fpsLine = null;
        String[] statNames = {"AVG", "1%", "MS"};
        String[] statValues = {"\u2014", "\u2014", "\u2014"};
        float heroW = 0f, heroH = 0f;
        float statW = 0f, statValueW = 0f, statH = 0f;
        if (hero) {
            fpsLine = listOf(span(fpsText(),
                            stats.fpsValid ? COL_VALUE : COL_DIM, heroPx),
                    span(" fps", COL_DIM, heroUnitPx));
            String avg = stats.fpsValid ? avgText() : null;
            String low = stats.fpsValid ? lowText(1) : null;
            statValues[0] = avg == null ? "\u2014" : avg;
            statValues[1] = low == null ? "\u2014" : low;
            statValues[2] = fpsNow() > 0f ? fmt1(1000f / fpsNow()) : "\u2014";
            float statLabelW = 0f;
            for (int i = 0; i < statNames.length; i++) {
                statLabelW = Math.max(statLabelW, measure(statNames[i], labelPx));
                statValueW = Math.max(statValueW, measure(statValues[i], statValuePx));
            }
            statW = statLabelW + sp(9f) + statValueW;
            statH = 3f * lineH(statValuePx) + 2f * sp(2f);
            heroW = runWidth(fpsLine) + sp(18f) + statW;
            heroH = Math.max(lineH(heroPx), statH);
        }

        // --- one row per enabled metric --------------------------------
        List<Module> modules = new ArrayList<>();
        addCpuModule(modules, valuePx, unitPx, detailPx);
        addGpuModule(modules, valuePx, unitPx, detailPx);
        if (showRam) {
            List<Span> value = stats.memTotalKb > 0
                    ? valueUnit(ramUsedText() + "/" + ramTotalText(), valuePx, unitPx)
                    : listOf(span("\u2014", COL_DIM, valuePx));
            List<Span> detail = stats.memTotalKb > 0
                    ? listOf(span(Math.round(stats.memPercent) + "%", COL_DIM, detailPx))
                    : null;
            float level = stats.memTotalKb > 0
                    ? Math.max(0f, Math.min(1f, stats.memPercent / 100f)) : -1f;
            modules.add(new Module("RAM", COL_RAM, value, detail, level));
        }
        if (showBattery) {
            boolean hasPct = stats.battery >= 0;
            List<Span> value = hasPct
                    ? numUnit(String.valueOf(stats.battery), "%", valuePx, unitPx)
                    : listOf(span("\u2014", COL_DIM, valuePx));
            List<Span> detail = stats.batteryTempC > 0f
                    ? tempSpans(stats.batteryTempC, detailPx, detailPx * 0.78f) : null;
            float level = hasPct ? Math.max(0f, Math.min(1f, stats.battery / 100f)) : -1f;
            modules.add(new Module("BAT", COL_BAT, value, detail, level));
        }
        if (showBattery && stats.powerW > 0f) {
            List<Span> value = numUnit(fmt1(stats.powerW), "W", valuePx, unitPx);
            List<Span> detail = listOf(span(Loc.s(ctx, "SYSTEM"), COL_DIM, detailPx));
            modules.add(new Module(Loc.s(ctx, "POWER"), COL_POWER, value, detail, -1f));
        }
        if (showFan) {
            int duty = PerfTuner.fanPercent(ctx);
            List<Span> value = numUnit(duty >= 0 ? String.valueOf(duty) : null,
                    "%", valuePx, unitPx);
            String mode = fanModeLabel();
            List<Span> detail = mode == null ? null
                    : listOf(span(mode, COL_DIM, detailPx));
            float level = duty >= 0 ? Math.max(0f, Math.min(1f, duty / 100f)) : -1f;
            modules.add(new Module("FAN", COL_FAN, value, detail, level));
        }

        // --- module grid measurements ----------------------------------
        float labelW = 0f, valueW = 0f, detailW = 0f;
        for (Module m : modules) {
            labelW = Math.max(labelW, measure(m.key, labelPx));
            valueW = Math.max(valueW, runWidth(m.value));
            if (m.detail != null) detailW = Math.max(detailW, runWidth(m.detail));
        }
        float dotD = dotR * 2f;
        float rowW = dotD + sp(5f) + labelW + sp(6f) + valueW + sp(6f) + meterW
                + (detailW > 0f ? sp(6f) + detailW + sp(4f) : 0f);
        int rows = modules.size();
        float gridW = rowW;
        float rowH = lineH(valuePx) + sp(4f);
        float gridH = rows > 0 ? rows * rowH + (rows - 1) * rowGap : 0f;

        // --- frametime ribbon ------------------------------------------
        boolean graphOn = hero && showGraph && stats.fpsValid && hasGraphSamples();
        float graphW = graphOn ? sp(200f) : 0f;

        // --- header text -----------------------------------------------
        float headerLeftW = dotD + sp(6f) + measure("PAVO", headerPx);
        float headerW = hasEngine
                ? headerLeftW + sp(16f) + measure(engine, headerPx) : headerLeftW;

        float innerW = Math.max(Math.max(headerW, heroW), gridW);
        innerW = Math.max(innerW, graphW);
        innerW = Math.max(innerW, sp(120f));
        contentW = innerW + pad * 2f;

        // --- header -----------------------------------------------------
        float y = pad;
        barFills.add(dot(pad, y + lineH(headerPx) / 2f, dotR));
        barColors.add(accent);
        placeRun(pad + dotD + sp(6f), y - ascent(headerPx),
                listOf(span("PAVO", COL_DIM, headerPx)));
        if (hasEngine) {
            float ew = measure(engine, headerPx);
            placeRun(pad + innerW - ew, y - ascent(headerPx),
                    listOf(span(engine, presetColor(), headerPx)));
        }
        y += lineH(headerPx) + sp(6f);
        dividers.add(divider(pad, y, pad + innerW, sp(0.8f)));
        y += sp(6f);

        // --- hero and frametime ribbon ---------------------------------
        if (hero) {
            placeRun(pad, y - ascent(heroPx), fpsLine);
            float sx = pad + innerW - statW;
            float sy = y + (heroH - statH) / 2f;
            for (int i = 0; i < statNames.length; i++) {
                float baseline = sy - ascent(statValuePx);
                placeRun(sx, baseline, listOf(span(statNames[i], COL_DIM, labelPx)));
                float vx = pad + innerW - measure(statValues[i], statValuePx);
                placeRun(vx, baseline, listOf(span(statValues[i], COL_VALUE, statValuePx)));
                sy += lineH(statValuePx) + sp(2f);
            }
            y += heroH;
            if (graphOn) {
                y += sp(4f);
                graphRect = new RectF(pad, y, pad + innerW, y + ribbonH);
                y += ribbonH;
            }
            if (!modules.isEmpty()) y += sectionGap;
        }

        // --- metric ledger ---------------------------------------------
        if (!modules.isEmpty()) {
            float gy = y;
            for (int i = 0; i < modules.size(); i++) {
                Module m = modules.get(i);
                float cy = gy + rowH / 2f;
                float baseline = gy + (rowH - lineH(valuePx)) / 2f - ascent(valuePx);
                barFills.add(dot(pad, cy, dotR));
                barColors.add(m.color);
                float lx = pad + dotD + sp(5f);
                float kw = measure(m.key, labelPx);
                placeRun(lx + labelW - kw, baseline,
                        listOf(span(m.key, m.color, labelPx)));
                float vx = lx + labelW + sp(6f);
                float vw = runWidth(m.value);
                placeRun(vx + valueW - vw, baseline, m.value);
                float mx = vx + valueW + sp(6f);
                float my = cy - meterH / 2f;
                if (m.level >= 0f) {
                    barTracks.add(new RectF(mx, my, mx + meterW, my + meterH));
                    float fillW = Math.max(0f, Math.min(1f, m.level)) * meterW;
                    if (fillW > sp(0.5f)) {
                        barFills.add(new RectF(mx, my, mx + fillW, my + meterH));
                        barColors.add(m.color);
                    }
                }
                if (m.detail != null) {
                    placeRun(mx + meterW + sp(6f), baseline, m.detail);
                }
                gy += rowH + rowGap;
                if (i < modules.size() - 1) {
                    dividers.add(divider(pad, gy - rowGap / 2f,
                            pad + innerW, sp(0.7f)));
                }
            }
            y += gridH;
        }

        contentH = y + pad;
    }

    private static RectF dot(float cx, float cy, float r) {
        return new RectF(cx, cy - r, cx + r * 2f, cy + r);
    }

    private static RectF divider(float x1, float y, float x2, float h) {
        return new RectF(x1, y, x2, y + h);
    }

    private String fanModeLabel() {
        int mode = PerfTuner.fanMode(ctx);
        switch (mode) {
            case PerfTuner.FAN_OFF: return Loc.s(ctx, "OFF");
            case PerfTuner.FAN_LOW: return Loc.s(ctx, "LOW");
            case PerfTuner.FAN_SMART: return Loc.s(ctx, "SMART");
            case PerfTuner.FAN_HIGH: return Loc.s(ctx, "HIGH");
            case PerfTuner.FAN_MANUAL: return Loc.s(ctx, "MANUAL");
            case FanProfile.MODE_CURVE: return Loc.s(ctx, "CURVE");
            case FanProfile.MODE_SMALL: return Loc.s(ctx, "SMALL");
            case FanProfile.MODE_MEDIUM: return Loc.s(ctx, "MEDIUM");
            case FanProfile.MODE_LARGE: return Loc.s(ctx, "LARGE");
            case FanProfile.MODE_HOLD: return Loc.s(ctx, "HOLD");
            default: return null;
        }
    }

    private void addCpuModule(List<Module> modules, float valPx, float unitPx, float detailPx) {
        if (!showCpuTemp && !showCpuClock && !showCpuUsage) return;
        int primary = showCpuUsage ? 0 : showCpuClock ? 1 : 2;
        List<Span> value;
        if (primary == 0) {
            value = numUnit(pct(stats.cpuUsageKnown, stats.cpuUsage), "%", valPx, unitPx);
        } else if (primary == 1) {
            value = valueUnit(stats.cpuHz > 0 ? PerfOverlayView.fmtHz(stats.cpuHz) : "\u2014",
                    valPx, unitPx);
        } else {
            value = tempSpans(stats.cpuTempC, valPx, unitPx);
            if (value.isEmpty()) value = listOf(span("\u2014", COL_DIM, valPx));
        }
        List<Span> detail = new ArrayList<>();
        if (primary != 2 && showCpuTemp && stats.cpuTempC > 0f) {
            detail.addAll(tempSpans(stats.cpuTempC, detailPx, detailPx * 0.78f));
        }
        if (primary != 1 && showCpuClock) {
            if (!detail.isEmpty()) detail.add(span("  \u00b7  ", COL_DIM, detailPx));
            detail.addAll(valueUnit(stats.cpuHz > 0 ? PerfOverlayView.fmtHz(stats.cpuHz) : "\u2014",
                    detailPx, detailPx * 0.78f));
        }
        if (primary != 0 && showCpuUsage) {
            if (!detail.isEmpty()) detail.add(span("  \u00b7  ", COL_DIM, detailPx));
            detail.addAll(numUnit(pct(stats.cpuUsageKnown, stats.cpuUsage), "%",
                    detailPx, detailPx * 0.78f));
        }
        float level = showCpuUsage && stats.cpuUsageKnown
                ? Math.max(0f, Math.min(1f, stats.cpuUsage / 100f)) : -1f;
        modules.add(new Module("CPU", COL_CPU, value,
                detail.isEmpty() ? null : detail, level));
    }

    private void addGpuModule(List<Module> modules, float valPx, float unitPx, float detailPx) {
        if (!showGpuTemp && !showGpuClock && !showGpuUsage) return;
        int primary = showGpuUsage ? 0 : showGpuClock ? 1 : 2;
        List<Span> value;
        if (primary == 0) {
            value = numUnit(pct(stats.gpuUsageKnown, stats.gpuUsage), "%", valPx, unitPx);
        } else if (primary == 1) {
            value = valueUnit(stats.gpuHz > 0 ? PerfOverlayView.fmtHz(stats.gpuHz) : "\u2014",
                    valPx, unitPx);
        } else {
            value = tempSpans(stats.gpuTempC, valPx, unitPx);
            if (value.isEmpty()) value = listOf(span("\u2014", COL_DIM, valPx));
        }
        List<Span> detail = new ArrayList<>();
        if (primary != 2 && showGpuTemp && stats.gpuTempC > 0f) {
            detail.addAll(tempSpans(stats.gpuTempC, detailPx, detailPx * 0.78f));
        }
        if (primary != 1 && showGpuClock) {
            if (!detail.isEmpty()) detail.add(span("  \u00b7  ", COL_DIM, detailPx));
            detail.addAll(valueUnit(stats.gpuHz > 0 ? PerfOverlayView.fmtHz(stats.gpuHz) : "\u2014",
                    detailPx, detailPx * 0.78f));
        }
        if (primary != 0 && showGpuUsage) {
            if (!detail.isEmpty()) detail.add(span("  \u00b7  ", COL_DIM, detailPx));
            detail.addAll(numUnit(pct(stats.gpuUsageKnown, stats.gpuUsage), "%",
                    detailPx, detailPx * 0.78f));
        }
        float level = showGpuUsage && stats.gpuUsageKnown
                ? Math.max(0f, Math.min(1f, stats.gpuUsage / 100f)) : -1f;
        modules.add(new Module("GPU", COL_GPU, value,
                detail.isEmpty() ? null : detail, level));
    }

    private static List<Span> listOf(Span... spans) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    void draw(Canvas canvas, int viewW, int viewH) {
        if (dirty) rebuild();
        if (contentW <= 0f || contentH <= 0f) return;
        float radius = sp(14f);
        int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255f);
        int topAlpha = Math.round(alpha * 0.86f);
        bgPaint.setShader(new LinearGradient(0f, 0f, 0f, viewH,
                Color.argb(topAlpha, 0, 0, 0), Color.argb(alpha, 0, 0, 0),
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0f, 0f, viewW, viewH, radius, radius, bgPaint);
        bgPaint.setShader(null);

        if (outline) {
            strokePaint.setStrokeWidth(sp(1.4f));
            strokePaint.setColor(accent & 0x99FFFFFF);
            float h = sp(0.7f);
            canvas.drawRoundRect(h, h, viewW - h, viewH - h, radius, radius, strokePaint);
        }
        dividerPaint.setColor(Color.argb(Math.max(12, Math.min(52,
                Math.round(22 * opacity))), 255, 255, 255));
        for (RectF rect : dividers) {
            canvas.drawRect(rect, dividerPaint);
        }
        barPaint.setColor(Color.argb(Math.max(18, Math.min(64,
                Math.round(34 * opacity))), 255, 255, 255));
        for (RectF rect : barTracks) {
            canvas.drawRoundRect(rect, sp(2.5f), sp(2.5f), barPaint);
        }
        for (int i = 0; i < barFills.size(); i++) {
            barPaint.setColor(barColors.get(i));
            canvas.drawRoundRect(barFills.get(i), sp(2f), sp(2f), barPaint);
        }
        for (Glyph g : glyphs) {
            drawPaint.setTextSize(g.size);
            drawPaint.setColor(g.color);
            canvas.drawText(g.text, g.x, g.baseline, drawPaint);
        }
        if (graphRect != null) drawGraph(canvas, graphRect);
        if (locked) {
            float px = sp(12f);
            drawPaint.setTextSize(px);
            drawPaint.setColor(COL_LOCK);
            canvas.drawText("\uD83D\uDD12", Math.max(sp(4f), viewW - sp(20f)), sp(15f), drawPaint);
        }
    }

    private void drawGraph(Canvas canvas, RectF rect) {
        float[] vals = new float[graphSamples.size()];
        int i = 0;
        int valid = 0;
        float peak = 1f;
        for (Float f : graphSamples) {
            vals[i] = f;
            if (!Float.isNaN(f)) {
                peak = Math.max(peak, f);
                valid++;
            }
            i++;
        }
        if (valid < 2) return;
        float min = Float.MAX_VALUE;
        for (Float f : graphSamples) {
            if (!Float.isNaN(f)) min = Math.min(min, f);
        }
        if (min == Float.MAX_VALUE) return;
        float span = peak - min;
        graphPaint.setStrokeWidth(sp(1.25f));
        graphPaint.setColor(blend(COL_GRAPH));
        float step = vals.length > 1 ? rect.width() / (vals.length - 1f) : rect.width();
        graphPath.reset();
        graphFillPath.reset();
        int first = -1, last = -1;
        for (int j = 0; j < vals.length; j++) {
            if (!Float.isNaN(vals[j])) {
                if (first < 0) first = j;
                last = j;
            }
        }
        if (first < 0) return;
        float firstX = rect.left + first * step;
        graphFillPath.moveTo(firstX, rect.bottom);
        for (int j = first; j <= last; j++) {
            if (Float.isNaN(vals[j])) continue;
            float x = rect.left + j * step;
            float t = span < 0.35f ? 0.5f : (vals[j] - min) / span;
            float y = rect.bottom - Math.max(0f, Math.min(1f, t)) * rect.height();
            if (j == first) graphPath.moveTo(x, y);
            else graphPath.lineTo(x, y);
            graphFillPath.lineTo(x, y);
        }
        graphFillPath.lineTo(rect.left + last * step, rect.bottom);
        graphFillPath.close();
        graphFillPaint.setStyle(Paint.Style.FILL);
        graphFillPaint.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                0x245EE08A, 0x005EE08A, Shader.TileMode.CLAMP));
        canvas.drawPath(graphFillPath, graphFillPaint);
        graphFillPaint.setShader(null);
        canvas.drawPath(graphPath, graphPaint);
        dividerPaint.setColor(0x18FFFFFF);
        canvas.drawRect(rect.left, rect.bottom, rect.right,
                rect.bottom + sp(0.7f), dividerPaint);
    }

}
