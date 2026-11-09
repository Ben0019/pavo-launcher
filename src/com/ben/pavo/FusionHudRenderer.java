package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bannerlator Fusion HUD port.
 *
 * <p>One colour-coded visual language rendered at five size modes, smallest to
 * biggest: Minimal, Pill, Tiles, Full, Mega. Pure canvas drawing with a
 * monospace bold typeface, matching Bannerlator's Fusion overlay. The resizing
 * gesture cycles through the modes; the card always hugs its content.
 */
final class FusionHudRenderer {
    static final int MINIMAL = 0;
    static final int PILL = 1;
    static final int TILES = 2;
    static final int FULL = 3;
    static final int MEGA = 4;
    static final int MODE_COUNT = 5;

    private static final int COL_GPU = 0xFF5EE08A;
    private static final int COL_CPU = 0xFF58A6FF;
    private static final int COL_RAM = 0xFFFF7BC0;
    private static final int COL_BAT = 0xFFFFAB5E;
    private static final int COL_FPS = 0xFFFF6B6B;
    private static final int COL_GRAPH = 0xFF5EE08A;
    private static final int COL_VALUE = 0xFFF2F5F9;
    private static final int COL_DIM = 0xFF9AA4B2;
    private static final int COL_LO = 0xFFE4E8EE;
    private static final int COL_LOCK = 0xFFFFD27F;
    private static final int COL_FAN = 0xFF4DD0E1;

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

    private static final class Row {
        final String label;
        final int labelColor;
        final List<Span> values;
        final boolean inline;

        Row(String label, int labelColor, List<Span> values, boolean inline) {
            this.label = label;
            this.labelColor = labelColor;
            this.values = values;
            this.inline = inline;
        }
    }

    private final Context ctx;
    private final float density;
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path graphPath = new Path();

    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final ArrayList<RectF> tileRects = new ArrayList<>();
    private final ArrayDeque<Float> graphSamples = new ArrayDeque<>();
    private final ArrayDeque<Float> fpsHistory = new ArrayDeque<>();
    private RectF graphRect;
    private boolean pill;

    private Prefs prefs;
    private PerfStats stats;
    private int mode = PILL;
    private boolean locked;
    private float opacity = 0.88f;
    private float scale = 1f;
    private float colorIntensity = 1f;
    private boolean outline = true;
    private int accent = 0xFFA9BEFF;
    private String engineLabel = "";
    private String presetLabel = "";
    private final long startMs = android.os.SystemClock.uptimeMillis();

    // Metric toggles, mapped from Prefs.
    private boolean showFps = true;
    private boolean showCpuTemp = true;
    private boolean showCpuClock = true;
    private boolean showCpuUsage = true;
    private boolean showGpuTemp = true;
    private boolean showGpuClock = true;
    private boolean showGpuUsage = true;
    private boolean showRam = true;
    private boolean showNet = true;
    private boolean showBattery = true;
    private boolean showFan = true;
    private boolean showCores = true;
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
        showNet = p.perfNet();
        showBattery = p.perfBattery();
        showFan = p.perfFan();
        showCores = p.perfCores();
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

    void setMode(int m) {
        int v = Math.max(0, Math.min(MODE_COUNT - 1, m));
        if (mode == v) return;
        mode = v;
        dirty = true;
        rebuild();
    }

    int mode() {
        return mode;
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
        return showGraph && showFps;
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

    private boolean generating() {
        return stats != null && stats.frameGenActive
                && PerfOverlayView.generatedFrames(stats) > 0;
    }

    private String fpsText() {
        float v = fpsNow();
        if (generating()) {
            int made = PerfOverlayView.generatedFrames(stats);
            return Math.max(0, Math.round(v) - made) + "\u2192" + Math.round(v);
        }
        return one(v);
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
        return fmt1(usedGb) + "G";
    }

    private String ramTotalText() {
        if (stats == null || stats.memTotalKb <= 0) return null;
        int totalGb = Math.max(1, Math.round(stats.memTotalKb / 1_000_000f));
        return totalGb + "G";
    }

    private String rate(long bps) {
        return SysStats.fmtRate(bps);
    }

    private String clockTime() {
        try {
            return DateFormat.getTimeFormat(ctx).format(new Date());
        } catch (Throwable t) {
            return "";
        }
    }

    private String elapsedText() {
        int sec = (int) Math.max(0, (android.os.SystemClock.uptimeMillis() - startMs) / 1000);
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
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

    private String resolutionText() {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        String base = w + "x" + h;
        float hz = 0f;
        try {
            android.view.Display d = ctx.getDisplay();
            if (d != null) hz = d.getRefreshRate();
        } catch (Throwable ignored) {
        }
        return hz >= 1f ? base + " @ " + Math.round(hz) + "Hz" : base;
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    private void rebuild() {
        if (!dirty) return;
        dirty = false;
        glyphs.clear();
        tileRects.clear();
        graphRect = null;
        pill = false;
        contentW = 0f;
        contentH = 0f;
        if (stats == null) return;
        switch (mode) {
            case MINIMAL:
                buildMinimal();
                break;
            case PILL:
                buildPill();
                break;
            case TILES:
                buildTiles();
                break;
            case FULL:
                buildFull();
                break;
            case MEGA:
                buildMega();
                break;
        }
        if (pill) fitCapsule();
    }

    private void addClock(float pad, Float centerX) {
        String t = clockTime();
        if (t.isEmpty()) return;
        float px = sp(9.5f);
        float w = measure(t, px);
        float footerTop = contentH - pad;
        float baseline = footerTop - ascent(px);
        float x = centerX != null
                ? Math.max(pad, Math.min(centerX - w / 2f, contentW - pad - w))
                : Math.max(pad, contentW - pad - w);
        glyphs.add(new Glyph(x, baseline, t, blend(COL_DIM), px));
        contentH = footerTop + lineH(px) + pad * 0.5f;
        contentW = Math.max(contentW, pad + w + pad);
    }

    private void buildMinimal() {
        // Smallest mode: just the FPS number, and the frame-generation figure
        // when frames are being generated. Everything else stays in the larger
        // modes, so this card stays a tiny pill that hugs its one number.
        float bigPx = sp(34f);
        float unitPx = bigPx * 0.32f;
        float pad = sp(10f);
        boolean gen = generating();
        float textPx = gen ? bigPx * 0.60f : bigPx;

        List<Span> big = new ArrayList<>();
        big.add(span(fpsText(), COL_VALUE, textPx));
        big.add(span("fps", COL_DIM, gen ? unitPx * 0.8f : unitPx));

        float bigW = runWidth(big);
        float bigH = lineH(textPx);
        contentW = bigW + pad * 2f;
        contentH = bigH + pad * 2f;
        placeRun(pad, pad - ascent(textPx), big);
    }

    private void buildPill() {
        float bigPx = sp(30f);
        float bigUnit = bigPx * 0.36f;
        float stkPx = sp(11.5f);
        float pad = sp(10f);
        float midGap = sp(12f);
        float lineGap = sp(3f);
        boolean gen = generating();

        List<Span> left = new ArrayList<>();
        left.add(span(fpsText(), COL_VALUE, gen ? bigPx * 0.55f : bigPx));
        left.add(span("fps", COL_DIM, gen ? bigUnit * 0.8f : bigUnit));

        List<List<Span>> stack = new ArrayList<>();
        if (showGpuUsage || showCpuUsage) {
            List<Span> l = new ArrayList<>();
            if (showGpuUsage) l.add(span("GPU " + (pct(stats.gpuUsageKnown, stats.gpuUsage) == null
                    ? "\u2014" : stats.gpuUsage) + "%", COL_GPU, stkPx));
            if (showCpuUsage) {
                if (!l.isEmpty()) l.add(span(" \u00b7 ", COL_DIM, stkPx));
                l.add(span("CPU " + (pct(stats.cpuUsageKnown, stats.cpuUsage) == null
                        ? "\u2014" : stats.cpuUsage) + "%", COL_CPU, stkPx));
            }
            stack.add(l);
        }
        if (showRam) {
            List<Span> l = new ArrayList<>();
            l.add(span("RAM " + (stats.memTotalKb > 0 ? Math.round(stats.memPercent) + "%" : "\u2014"),
                    COL_RAM, stkPx));
            if (showCpuTemp && stats.cpuTempC > 0f) {
                l.add(span(" \u00b7 ", COL_DIM, stkPx));
                l.add(span("CPU " + Math.round(stats.cpuTempC) + "\u00b0", COL_CPU, stkPx));
            }
            if (showGpuTemp && stats.gpuTempC > 0f) {
                l.add(span(" \u00b7 ", COL_DIM, stkPx));
                l.add(span("GPU " + Math.round(stats.gpuTempC) + "\u00b0", COL_GPU, stkPx));
            }
            stack.add(l);
        }
        if (showBattery || showGpuTemp) {
            List<Span> l = new ArrayList<>();
            if (showBattery && stats.battery >= 0) {
                l.add(span("BAT " + stats.battery + "%", COL_BAT, stkPx));
            }
            if (showBattery && stats.powerW > 0f) {
                if (!l.isEmpty()) l.add(span(" \u00b7 ", COL_DIM, stkPx));
                l.add(span(fmt1(stats.powerW) + "W", COL_DIM, stkPx));
            }
            if (stats.batteryTempC > 0f) {
                if (!l.isEmpty()) l.add(span(" \u00b7 ", COL_DIM, stkPx));
                l.add(span("BATT " + Math.round(stats.batteryTempC) + "\u00b0", COL_BAT, stkPx));
            }
            if (!l.isEmpty()) stack.add(l);
        }
        {
            List<Span> l = new ArrayList<>();
            l.add(span(fmt1(1000f / Math.max(1f, fpsNow())) + "ms", COL_DIM, stkPx));
            if (showNet) {
                l.add(span(" \u00b7 ", COL_DIM, stkPx));
                l.add(span("\u2193" + rate(stats.netRxBps), COL_DIM, stkPx));
            }
            if (showFan) {
                int duty = PerfTuner.fanPercent(ctx);
                if (duty >= 0) {
                    l.add(span(" \u00b7 FAN " + duty + "%", COL_DIM, stkPx));
                }
            }
            stack.add(l);
        }

        String api = showEngine && engineLabel != null && !engineLabel.isEmpty()
                ? engineLabel : "FPS";
        boolean hasApi = !"FPS".equals(api);
        float apiW = hasApi ? measure(api, stkPx) : 0f;
        float leftW = runWidth(left);
        float leftH = lineH(bigPx);
        float leftBlockW = Math.max(leftW, apiW);
        float leftColH = (hasApi ? lineH(stkPx) + lineGap : 0f) + leftH;
        float stackW = 0f;
        for (List<Span> l : stack) stackW = Math.max(stackW, runWidth(l));
        float stkH = lineH(stkPx);
        float stackTotalH = stack.size() * stkH + Math.max(0, stack.size() - 1) * lineGap;
        float innerH = Math.max(leftColH, stackTotalH);
        contentW = pad + leftBlockW + midGap + stackW + pad;
        contentH = pad + innerH + pad;

        float ly = pad + (innerH - leftColH) / 2f;
        if (hasApi) {
            placeRun(pad + (leftBlockW - apiW) / 2f, ly - ascent(stkPx),
                    listOf(span(api, COL_FPS, stkPx)));
            ly += lineH(stkPx) + lineGap;
        }
        placeRun(pad + (leftBlockW - leftW) / 2f, ly - ascent(bigPx), left);
        float sy = pad + (innerH - stackTotalH) / 2f;
        for (List<Span> l : stack) {
            placeRun(pad + leftBlockW + midGap, sy - ascent(stkPx), l);
            sy += stkH + lineGap;
        }
        pill = true;
    }

    private void buildTiles() {
        float keyPx = sp(10f);
        float valPx = sp(18f);
        float subPx = sp(10f);
        float unitPx = sp(11f);
        float pad = sp(9f);
        float inner = sp(8f);
        float tileGap = sp(6f);
        float lineGap = sp(4f);

        class Tile {
            final String key;
            final int keyColor;
            final List<Span> value;
            final String sub;

            Tile(String key, int keyColor, List<Span> value, String sub) {
                this.key = key;
                this.keyColor = keyColor;
                this.value = value;
                this.sub = sub;
            }
        }

        List<Tile> tiles = new ArrayList<>();
        if (showFps) {
            tiles.add(new Tile("FPS", COL_FPS, listOf(span(fpsText(), COL_VALUE, valPx)),
                    (avgText() == null ? "\u2014" : avgText()) + " avg \u00b7 "
                            + (lowText(1) == null ? "\u2014" : lowText(1)) + " 1%"));
            tiles.add(new Tile("FRAME", COL_DIM,
                    numUnit(fmt1(1000f / Math.max(1f, fpsNow())), "ms", valPx, unitPx), null));
        }
        if (showGpuUsage) {
            tiles.add(new Tile("GPU", COL_GPU,
                    numUnit(pct(stats.gpuUsageKnown, stats.gpuUsage), "%", valPx, unitPx),
                    showGpuTemp && stats.gpuTempC > 0f ? Math.round(stats.gpuTempC) + "\u00b0"
                            : showGpuClock ? PerfOverlayView.fmtHz(stats.gpuHz) : null));
        }
        if (showCpuUsage) {
            List<String> sub = new ArrayList<>();
            if (showCpuTemp && stats.cpuTempC > 0f) sub.add(Math.round(stats.cpuTempC) + "\u00b0");
            if (showCpuClock) sub.add(PerfOverlayView.fmtHz(stats.cpuHz));
            tiles.add(new Tile("CPU", COL_CPU,
                    numUnit(pct(stats.cpuUsageKnown, stats.cpuUsage), "%", valPx, unitPx),
                    join(sub, " \u00b7 ")));
        }
        if (showRam) {
            tiles.add(new Tile("RAM", COL_RAM,
                    numUnit(stats.memTotalKb > 0 ? String.valueOf(Math.round(stats.memPercent)) : null,
                            "%", valPx, unitPx),
                    ramUsedText() == null ? null : ramUsedText() + " / " + ramTotalText()));
        }
        if (showBattery) {
            List<String> sub = new ArrayList<>();
            if (stats.powerW > 0f) sub.add(fmt1(stats.powerW) + "W");
            if (stats.batteryTempC > 0f) sub.add(Math.round(stats.batteryTempC) + "\u00b0");
            tiles.add(new Tile("BAT", COL_BAT,
                    numUnit(stats.battery >= 0 ? String.valueOf(stats.battery) : null,
                            "%", valPx, unitPx), join(sub, " \u00b7 ")));
        }
        if (showNet) {
            tiles.add(new Tile("NET", COL_FPS,
                    listOf(span(rate(stats.netRxBps), COL_VALUE, valPx)),
                    activityUp(stats.netTxBps)));
        }
        if (showEngine) {
            tiles.add(new Tile("API", COL_FPS, listOf(span(engineLabel, COL_VALUE, valPx)),
                    "Android"));
        }
        if (showFan) {
            int duty = PerfTuner.fanPercent(ctx);
            tiles.add(new Tile("FAN", COL_FAN,
                    numUnit(duty >= 0 ? String.valueOf(duty) : null, "%", valPx, unitPx),
                    null));
        }
        if (tiles.isEmpty()) return;

        float keyH = lineH(keyPx);
        float valH = lineH(valPx);
        float subH = lineH(subPx);
        float normalW = 0f;
        for (Tile t : tiles) {
            float w = Math.max(Math.max(measure(t.key, keyPx), runWidth(t.value)),
                    t.sub == null ? 0f : measure(t.sub, subPx)) + inner * 2f;
            normalW = Math.max(normalW, w);
        }
        float tileH = inner * 2f + keyH + lineGap + valH + lineGap + subH;
        float fullW = normalW * 2f + tileGap;

        float x = pad, y = pad;
        int col = 0;
        for (Tile t : tiles) {
            float tw = normalW;
            if (col == 0) {
                placeTile(t.key, t.keyColor, t.value, t.sub, x, y, tw, tileH,
                        keyPx, valPx, subPx, inner, lineGap);
                col = 1;
            } else {
                placeTile(t.key, t.keyColor, t.value, t.sub, x, y, tw, tileH,
                        keyPx, valPx, subPx, inner, lineGap);
                col = 0;
                y += tileH + tileGap;
            }
            x = col == 0 ? pad : pad + normalW + tileGap;
        }
        if (col != 0) y += tileH + tileGap;
        contentW = pad + fullW + pad;
        contentH = y + (pad - tileGap);
    }

    private void placeTile(String key, int keyColor, List<Span> value, String sub,
                           float x, float y, float w, float h,
                           float keyPx, float valPx, float subPx,
                           float inner, float lineGap) {
        tileRects.add(new RectF(x, y, x + w, y + h));
        float baseline = y + inner - ascent(keyPx);
        placeRun(x + inner, baseline, listOf(span(key, keyColor, keyPx)));
        baseline = y + inner + lineH(keyPx) + lineGap - ascent(valPx);
        placeRun(x + inner, baseline, value);
        if (sub != null && !sub.isEmpty()) {
            baseline = y + inner + lineH(keyPx) + lineGap + lineH(valPx) + lineGap - ascent(subPx);
            placeRun(x + inner, baseline, listOf(span(sub, COL_DIM, subPx)));
        }
    }

    private void buildFull() {
        float rowPx = sp(12f);
        float unitPx = rowPx * 0.62f;
        float pad = sp(10f);
        float lineGap = sp(4f);
        float lvGap = sp(8f);

        List<Row> rows = new ArrayList<>();
        if (showGpuUsage) {
            List<Span> v = new ArrayList<>();
            v.addAll(numUnit(pct(stats.gpuUsageKnown, stats.gpuUsage), "%", rowPx, unitPx));
            if (showGpuTemp && stats.gpuTempC > 0f) {
                v.add(gap(unitPx));
                v.addAll(tempSpans(stats.gpuTempC, rowPx, unitPx));
            }
            if (showGpuClock) {
                v.add(gap(unitPx));
                v.addAll(valueUnit(PerfOverlayView.fmtHz(stats.gpuHz), rowPx, unitPx));
            }
            rows.add(new Row("GPU", COL_GPU, v, false));
        }
        if (showCpuUsage) {
            List<Span> v = new ArrayList<>();
            v.addAll(numUnit(pct(stats.cpuUsageKnown, stats.cpuUsage), "%", rowPx, unitPx));
            if (showCpuTemp && stats.cpuTempC > 0f) {
                v.add(gap(unitPx));
                v.addAll(tempSpans(stats.cpuTempC, rowPx, unitPx));
            }
            if (showCpuClock) {
                v.add(gap(unitPx));
                v.addAll(valueUnit(PerfOverlayView.fmtHz(stats.cpuHz), rowPx, unitPx));
            }
            rows.add(new Row("CPU", COL_CPU, v, false));
        }
        if (showRam && ramUsedText() != null) {
            List<Span> v = new ArrayList<>();
            v.addAll(valueUnit(ramUsedText() + "/" + ramTotalText(), rowPx, unitPx));
            v.add(gap(unitPx));
            v.addAll(numUnit(String.valueOf(Math.round(stats.memPercent)), "%", rowPx, unitPx));
            rows.add(new Row("RAM", COL_RAM, v, false));
        }
        if (showBattery) {
            List<Span> v = new ArrayList<>();
            boolean any = false;
            if (stats.battery >= 0) {
                v.addAll(numUnit(String.valueOf(stats.battery), "%", rowPx, unitPx));
                any = true;
            }
            if (stats.batteryTempC > 0f) {
                if (any) v.add(gap(unitPx));
                v.addAll(tempSpans(stats.batteryTempC, rowPx, unitPx));
                any = true;
            }
            if (stats.powerW > 0f) {
                if (any) v.add(gap(unitPx));
                v.addAll(numUnit(fmt1(stats.powerW), "W", rowPx, unitPx));
                any = true;
            }
            if (any) rows.add(new Row("BAT", COL_BAT, v, false));
        }
        if (showFan) {
            int duty = PerfTuner.fanPercent(ctx);
            rows.add(new Row("FAN", COL_FAN,
                    numUnit(duty >= 0 ? String.valueOf(duty) : null, "%", rowPx, unitPx),
                    false));
        }
        if (showFps) {
            List<Span> v = new ArrayList<>();
            v.addAll(numUnit(fpsText(), "FPS", rowPx, unitPx));
            v.add(gap(unitPx));
            v.addAll(numUnit(fmt1(1000f / Math.max(1f, fpsNow())), "ms", rowPx, unitPx));
            String api = showEngine && !engineLabel.isEmpty() ? engineLabel : "FPS";
            rows.add(new Row(api, COL_FPS, v, false));
            if (avgText() != null)
                rows.add(new Row("AVG", COL_LO, numUnit(avgText(), "FPS", rowPx, unitPx), false));
            if (lowText(1) != null)
                rows.add(new Row("1%", COL_LO, numUnit(lowText(1), "FPS", rowPx, unitPx), false));
            if (lowText(0) != null)
                rows.add(new Row("0.1%", COL_LO, numUnit(lowText(0), "FPS", rowPx, unitPx), false));
        }

        float labelCol = 0f;
        for (Row r : rows) labelCol = Math.max(labelCol, measure(r.label, rowPx));
        float h = lineH(rowPx);
        float asc = ascent(rowPx);
        float y = pad;
        float maxRight = pad;
        for (Row r : rows) {
            float baseline = y - asc;
            float end = placeRun(pad, baseline, listOf(span(r.label, r.labelColor, rowPx)));
            float valX = pad + labelCol + lvGap;
            end = placeRun(valX, baseline, r.values);
            maxRight = Math.max(maxRight, end);
            y += h + lineGap;
        }

        if (showFps && showGraph && hasGraphSamples()) {
            float ftPx = unitPx * 1.15f;
            float baseline = y - asc;
            placeRun(pad, baseline, listOf(span("Frametime", COL_FPS, ftPx)));
            String stat = "min:" + fmt1(graphMin()) + " max:" + fmt1(graphMax());
            float statX = pad + Math.max(labelCol, measure("Frametime", ftPx)) + lvGap;
            maxRight = Math.max(maxRight, placeRun(statX, baseline,
                    listOf(span(stat, COL_DIM, unitPx))));
            y += h + lineGap;
            float gh = sp(22f);
            float right = Math.max(maxRight, pad + sp(160f));
            graphRect = new RectF(pad, y, right, y + gh);
            maxRight = Math.max(maxRight, right);
            y += gh;
        }
        contentW = maxRight + pad;
        contentH = y + pad;
    }

    private void buildMega() {
        float rowPx = sp(11.5f);
        float unitPx = rowPx * 0.62f;
        float bandPx = sp(9.5f);
        float pad = sp(10f);
        float lineGap = sp(3.5f);
        float lvGap = sp(7f);
        float gutter = sp(16f);

        List<Row> left = new ArrayList<>();
        if (showGpuUsage) {
            List<Span> v = new ArrayList<>();
            v.addAll(numUnit(pct(stats.gpuUsageKnown, stats.gpuUsage), "%", rowPx, unitPx));
            if (showGpuTemp && stats.gpuTempC > 0f) {
                v.add(gap(unitPx));
                v.addAll(tempSpans(stats.gpuTempC, rowPx, unitPx));
            }
            if (showGpuClock) {
                v.add(gap(unitPx));
                v.addAll(valueUnit(PerfOverlayView.fmtHz(stats.gpuHz), rowPx, unitPx));
            }
            left.add(new Row("GPU", COL_GPU, v, false));
        }
        if (showCpuUsage) {
            List<Span> v = new ArrayList<>();
            v.addAll(numUnit(pct(stats.cpuUsageKnown, stats.cpuUsage), "%", rowPx, unitPx));
            if (showCpuTemp && stats.cpuTempC > 0f) {
                v.add(gap(unitPx));
                v.addAll(tempSpans(stats.cpuTempC, rowPx, unitPx));
            }
            if (showCpuClock) {
                v.add(gap(unitPx));
                v.addAll(valueUnit(PerfOverlayView.fmtHz(stats.cpuHz), rowPx, unitPx));
            }
            left.add(new Row("CPU", COL_CPU, v, false));
        }
        if (showCores) {
            for (int i = 0; i < Math.min(stats.coreCount, PerfStats.MAX_CORES); i++) {
                List<Span> v = new ArrayList<>();
                if (stats.coreStatsKnown && stats.coreUsage[i] >= 0f) {
                    v.addAll(numUnit(String.valueOf(Math.round(stats.coreUsage[i])),
                            "%", rowPx, unitPx));
                } else {
                    v.addAll(numUnit(null, "%", rowPx, unitPx));
                }
                v.add(gap(unitPx));
                v.addAll(valueUnit(PerfOverlayView.fmtHz(stats.coreHz[i]), rowPx, unitPx));
                left.add(new Row("C" + i, COL_CPU, v, false));
            }
        }

        List<Row> right = new ArrayList<>();
        if (showRam && ramUsedText() != null) {
            List<Span> v = new ArrayList<>();
            v.addAll(valueUnit(ramUsedText(), rowPx, unitPx));
            v.add(span("/", COL_DIM, unitPx));
            v.addAll(valueUnit(ramTotalText(), rowPx, unitPx));
            v.add(gap(unitPx));
            v.addAll(numUnit(String.valueOf(Math.round(stats.memPercent)), "%", rowPx, unitPx));
            right.add(new Row("RAM", COL_RAM, v, false));
        }
        if (showNet) {
            List<Span> v = new ArrayList<>();
            v.add(span("\u2193", COL_DIM, unitPx));
            v.add(span(rate(stats.netRxBps), COL_VALUE, rowPx));
            v.add(span(" \u2191", COL_DIM, unitPx));
            v.add(span(rate(stats.netTxBps), COL_VALUE, rowPx));
            right.add(new Row("NET", COL_FPS, v, false));
        }
        if (showBattery) {
            List<Span> v = new ArrayList<>();
            boolean any = false;
            if (stats.battery >= 0) {
                v.addAll(numUnit(String.valueOf(stats.battery), "%", rowPx, unitPx));
                any = true;
            }
            if (stats.powerW > 0f) {
                if (any) v.add(gap(unitPx));
                v.addAll(numUnit(fmt1(stats.powerW), "W", rowPx, unitPx));
                any = true;
            }
            if (any) right.add(new Row("BAT", COL_BAT, v, false));
        }
        if (showFan) {
            int duty = PerfTuner.fanPercent(ctx);
            right.add(new Row("FAN", COL_FAN,
                    numUnit(duty >= 0 ? String.valueOf(duty) : null, "%", rowPx, unitPx),
                    false));
        }
        if (showFps) {
            List<Span> v = new ArrayList<>();
            v.addAll(numUnit(fpsText(), "FPS", rowPx, unitPx));
            v.add(gap(unitPx));
            v.addAll(numUnit(fmt1(1000f / Math.max(1f, fpsNow())), "ms", rowPx, unitPx));
            String api = showEngine && !engineLabel.isEmpty() ? engineLabel : "FPS";
            right.add(new Row(api, COL_FPS, v, false));
            if (avgText() != null)
                right.add(new Row("AVG", COL_LO, numUnit(avgText(), "FPS", rowPx, unitPx), false));
            if (lowText(1) != null)
                right.add(new Row("1%", COL_LO, numUnit(lowText(1), "FPS", rowPx, unitPx), false));
            if (lowText(0) != null)
                right.add(new Row("0.1%", COL_LO, numUnit(lowText(0), "FPS", rowPx, unitPx), false));
        }

        float[] l = layoutColumn(left, pad, pad, rowPx, lineGap, lvGap);
        float rightX = left.isEmpty() ? pad : l[1] + gutter;
        float[] r = layoutColumn(right, rightX, pad, rowPx, lineGap, lvGap);
        float y = Math.max(l[0], r[0]);
        float maxRight = Math.max(l[1], r[1]);

        List<List<Span>> band = new ArrayList<>();
        band.add(listOf(span("RES ", COL_DIM, bandPx), span(resolutionText(), COL_VALUE, bandPx)));
        band.add(listOf(span("DISP ", COL_DIM, bandPx), span("Android", COL_VALUE, bandPx)));
        band.add(listOf(span("elapsed ", COL_DIM, bandPx),
                span(elapsedText(), COL_VALUE, bandPx)));
        if (!band.isEmpty()) {
            y += sp(4f);
            float[] b = layoutWrap(band, pad, y, Math.max(maxRight - pad, sp(180f)),
                    bandPx, lineGap);
            y = b[0];
            maxRight = Math.max(maxRight, b[1]);
        }
        if (showGraph && showFps && hasGraphSamples()) {
            y += sp(3f);
            float gh = sp(22f);
            float gr = Math.max(maxRight, pad + sp(220f));
            graphRect = new RectF(pad, y, gr, y + gh);
            maxRight = Math.max(maxRight, gr);
            y += gh;
        }
        contentW = maxRight + pad;
        contentH = y + pad;
    }

    private float[] layoutColumn(List<Row> rows, float x, float y0, float rowPx,
                                 float lineGap, float lvGap) {
        if (rows.isEmpty()) return new float[]{y0, x};
        float labelCol = 0f;
        for (Row r : rows) labelCol = Math.max(labelCol, measure(r.label, rowPx));
        float h = lineH(rowPx);
        float asc = ascent(rowPx);
        float y = y0;
        float right = x;
        for (Row r : rows) {
            float baseline = y - asc;
            placeRun(x, baseline, listOf(span(r.label, r.labelColor, rowPx)));
            float valX = x + labelCol + lvGap;
            float end = placeRun(valX, baseline, r.values);
            right = Math.max(right, end);
            y += h + lineGap;
        }
        return new float[]{y - lineGap, right};
    }

    private float[] layoutWrap(List<List<Span>> fragments, float x, float y0,
                               float maxWidth, float px, float lineGap) {
        if (fragments.isEmpty()) return new float[]{y0, x};
        float h = lineH(px);
        float asc = ascent(px);
        float sepW = measure(" \u00b7 ", px);
        float cx = x, y = y0, right = x;
        boolean first = true;
        for (List<Span> frag : fragments) {
            float w = runWidth(frag);
            if (!first && (cx + sepW + w - x) > maxWidth) {
                y += h + lineGap;
                cx = x;
                first = true;
            }
            if (!first) {
                placeRun(cx, y - asc, listOf(span(" \u00b7 ", COL_DIM, px)));
                cx += sepW;
            }
            float end = placeRun(cx, y - asc, frag);
            cx = end;
            right = Math.max(right, end);
            first = false;
        }
        return new float[]{y + h, right};
    }

    private void fitCapsule() {
        if (contentW <= 0f || contentH <= 0f || glyphs.isEmpty()) return;
        float margin = sp(2f) + (outline ? sp(1.5f) : 0f);
        android.graphics.Rect ink = new android.graphics.Rect();
        float shift = 0f;
        for (Glyph g : glyphs) {
            measurePaint.setTextSize(g.size);
            measurePaint.getTextBounds(g.text, 0, g.text.length(), ink);
            if (ink.isEmpty()) continue;
            float need = curveIn(g.baseline + ink.top, g.baseline + ink.bottom) + margin;
            shift = Math.max(shift, need - (g.x + ink.left));
        }
        if (shift > 0f) {
            for (int i = 0; i < glyphs.size(); i++) {
                Glyph g = glyphs.get(i);
                glyphs.set(i, new Glyph(g.x + shift, g.baseline, g.text, g.color, g.size));
            }
            contentW += shift;
        }
    }

    private float curveIn(float top, float bottom) {
        float r = contentH / 2f;
        float dy = Math.max(r - top, bottom - r);
        if (dy <= 0f) return 0f;
        if (dy >= r) return r;
        return r - (float) Math.sqrt(r * r - dy * dy);
    }

    private static List<Span> listOf(Span... spans) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) out.add(s);
        return out;
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            if (b.length() > 0) b.append(sep);
            b.append(p);
        }
        return b.length() == 0 ? null : b.toString();
    }

    private String activityUp(long bps) {
        return "\u2191" + rate(bps);
    }

    // ------------------------------------------------------------------
    // Draw
    // ------------------------------------------------------------------

    void draw(Canvas canvas, int viewW, int viewH) {
        if (dirty) rebuild();
        if (contentW <= 0f || contentH <= 0f) return;
        float radius = pill ? viewH / 2f : sp(8f);
        int alpha = Math.round(Math.max(0f, Math.min(1f, opacity)) * 255f);
        bgPaint.setColor((alpha << 24) | 0x000000);
        canvas.drawRoundRect(0f, 0f, viewW, viewH, radius, radius, bgPaint);

        if (outline) {
            strokePaint.setStrokeWidth(sp(1.4f));
            strokePaint.setColor(accent & 0x99FFFFFF);
            float h = sp(0.7f);
            canvas.drawRoundRect(h, h, viewW - h, viewH - h, radius, radius, strokePaint);
        }
        for (RectF rect : tileRects) {
            tilePaint.setColor(Color.argb(Math.max(8, Math.min(40,
                    Math.round(14 * opacity))), 255, 255, 255));
            canvas.drawRoundRect(rect, sp(6f), sp(6f), tilePaint);
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
        graphPaint.setStrokeWidth(sp(1.6f));
        graphPaint.setColor(blend(COL_GRAPH));
        float step = vals.length > 1 ? rect.width() / (vals.length - 1f) : rect.width();
        graphPath.reset();
        boolean started = false;
        for (int j = 0; j < vals.length; j++) {
            if (Float.isNaN(vals[j])) {
                started = false;
                continue;
            }
            float x = rect.left + j * step;
            float y = rect.bottom - Math.min(1f, vals[j] / peak) * rect.height();
            if (!started) {
                graphPath.moveTo(x, y);
                started = true;
            } else {
                graphPath.lineTo(x, y);
            }
        }
        canvas.drawPath(graphPath, graphPaint);
    }
}
