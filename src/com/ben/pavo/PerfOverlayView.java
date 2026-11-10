package com.ben.pavo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Rect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

/** The liquid-glass performance panel shown by {@link PerfOverlayService}. */
final class PerfOverlayView extends LinearLayout {
    private int accent = 0xFFA9BEFF;
    private static final int MUTED = 0xFFB0BAC8;
    private static final int TEXT = 0xFFFFFFFF;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Bitmap frostNoise;
    private final RectF rect = new RectF();
    private final int corner;
    private int panelAlphaPct = 88;

    private TextView battery;
    private LinearLayout fanRow;
    private TextView fanDuty;
    private LinearLayout headerRow;
    private TextView fpsLabel;
    private Prefs lastPrefs;
    private int form = 5;   // 0 = FPS only ... 5 = full spread card
    private boolean spread;
    private TextView lockIcon;
    private TextView editDoneBar;
    private Runnable editDoneAction;
    private LinearLayout cpuRow, gpuRow, netRow, fpsBlock, coresBox;
    private LinearLayout ramRow;
    private LinearLayout battRow;
    private TextView cpuTemp, cpuClock, cpuUsage;
    private TextView gpuTemp, gpuClock, gpuUsage;
    private TextView netUp, netDown;
    private TextView ramUsage, ramAmount;
    private TextView battTemp, battPower;
    private TextView fpsValue;
    private LinearLayout fpsRow;
    private FpsGraphView graph;
    private final boolean horizontal;
    private TextView stripText;
    private TextView titleView;
    private String presetLabel;
    private long presetReadAt;
    private PerfStats lastStats;
    private final CoreView[] cores = new CoreView[PerfStats.MAX_CORES];
    private LinearLayout stackedBox;
    private WrapLayout compactBox;
    private boolean compactMode;
    private TextView chipFps, chipCpu, chipGpu, chipBatt, chipRam, chipNet, chipFan;
    private FusionHudRenderer fusion;

    PerfOverlayView(Context c) {
        super(c);
        ThemeColors.initialize(c);
        accent = ThemeColors.accent();
        horizontal = new Prefs(c).perfOverlayHorizontal();
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setClickable(false);
        if (!horizontal) {
            corner = dp(22);
            initFusion(c);
            return;
        }
        stackedBox = new LinearLayout(c);
        stackedBox.setOrientation(VERTICAL);
        addView(stackedBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        compactBox = new WrapLayout(c, (int) dp(6f), (int) dp(4f));
        addView(compactBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        compactBox.setVisibility(GONE);
        setClipChildren(false);
        setPadding(dp(16), dp(12), dp(16), dp(13));
        corner = dp(22);
        if (frostNoise == null) frostNoise = makeFrostNoise();
        noisePaint.setAlpha(13);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = label(presetName(), 11f, presetColor());
        TextView title = titleView;
        title.setLetterSpacing(0.10f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(dp(9), dp(3), dp(9), dp(3));
        stylePresetChip();
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View headerSpacer = new View(c);
        header.addView(headerSpacer, new LinearLayout.LayoutParams(
                0, 1, 1f));

        lockIcon = text("🔒", 12.5f, 0xFFFFD27F, false);
        lockIcon.setPadding(dp(4), 0, dp(6), 0);
        lockIcon.setContentDescription("Overlay locked");
        lockIcon.setVisibility(View.GONE);
        header.addView(lockIcon);

        battery = text("--%", 12f, TEXT, true);
        battery.setPadding(dp(8), dp(3), dp(8), dp(3));
        battery.setBackground(Ui.rounded(0x22FFFFFF, dp(9)));
        header.addView(battery);

        headerRow = header;
        stackedBox.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cpuRow = metricRow();
        addMetricLabel(cpuRow, Loc.s(getContext(), "CPU"));
        cpuTemp = metricValue(Gravity.START);
        cpuClock = metricValue(Gravity.START);
        cpuUsage = metricValue(Gravity.END);
        cpuTemp.setMinWidth(dp(38));
        cpuClock.setMinWidth(dp(62));
        cpuUsage.setMinWidth(dp(42));
        cpuRow.addView(cpuTemp, cell());
        cpuRow.addView(cpuClock, cell());
        cpuRow.addView(cpuUsage, cell());
        stackedBox.addView(cpuRow, rowParams(10));

        gpuRow = metricRow();
        addMetricLabel(gpuRow, Loc.s(getContext(), "GPU"));
        gpuTemp = metricValue(Gravity.START);
        gpuClock = metricValue(Gravity.START);
        gpuUsage = metricValue(Gravity.END);
        gpuTemp.setMinWidth(dp(38));
        gpuClock.setMinWidth(dp(62));
        gpuUsage.setMinWidth(dp(42));
        gpuRow.addView(gpuTemp, cell());
        gpuRow.addView(gpuClock, cell());
        gpuRow.addView(gpuUsage, cell());
        stackedBox.addView(gpuRow, rowParams(4));

        netRow = metricRow();
        addMetricLabel(netRow, "NET");
        netUp = metricValue(Gravity.START);
        netDown = metricValue(Gravity.END);
        netUp.setMinWidth(dp(62));
        netDown.setMinWidth(dp(62));
        netRow.addView(netUp, cell());
        netRow.addView(netDown, cell());
        stackedBox.addView(netRow, rowParams(4));

        ramRow = metricRow();
        addMetricLabel(ramRow, "RAM");
        ramUsage = metricValue(Gravity.START);
        ramAmount = metricValue(Gravity.END);
        ramUsage.setMinWidth(dp(54));
        ramAmount.setMinWidth(dp(58));
        ramRow.addView(ramUsage, cell());
        ramRow.addView(ramAmount, cell());
        stackedBox.addView(ramRow, rowParams(4));

        battRow = metricRow();
        addMetricLabel(battRow, "BATT");
        battTemp = metricValue(Gravity.START);
        battPower = metricValue(Gravity.END);
        battTemp.setMinWidth(dp(38));
        battPower.setMinWidth(dp(56));
        battRow.addView(battTemp, cell());
        battRow.addView(battPower, cell());
        stackedBox.addView(battRow, rowParams(4));

        fanRow = metricRow();
        addMetricLabel(fanRow, "FAN");
        fanDuty = metricValue(Gravity.START);
        fanDuty.setMinWidth(dp(38));
        fanRow.addView(fanDuty, cell());
        stackedBox.addView(fanRow, rowParams(4));

        fpsBlock = new LinearLayout(c);
        fpsBlock.setOrientation(VERTICAL);
        fpsRow = metricRow();
        fpsLabel = addMetricLabel(fpsRow, "FPS");
        fpsLabel.setVisibility(View.GONE);
        fpsValue = text("--", 22f, TEXT, true);
        fpsValue.setGravity(Gravity.CENTER_VERTICAL);
        fpsValue.setMinWidth(dp(56));
        fpsRow.addView(fpsValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView fpsUnit = label("fps", 9.5f, MUTED);
        fpsUnit.setPadding(dp(5), 0, 0, 0);
        fpsRow.addView(fpsUnit);
        fpsBlock.addView(fpsRow);
        graph = new FpsGraphView(c);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        glp.topMargin = dp(5);
        fpsBlock.addView(graph, glp);
        stackedBox.addView(fpsBlock, rowParams(10));

        coresBox = new LinearLayout(c);
        coresBox.setOrientation(VERTICAL);
        TextView coresLabel = label("CORES", 9.5f, MUTED);
        coresLabel.setLetterSpacing(0.16f);
        coresLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        coresBox.addView(coresLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                int index = r * 2 + col;
                CoreView cv = new CoreView(c, index);
                cores[index] = cv;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, dp(22), 1f);
                if (col == 0) lp.rightMargin = dp(8);
                row.addView(cv, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(3);
            coresBox.addView(row, rlp);
        }
        stackedBox.addView(coresBox, rowParams(10));
        coresBox.setVisibility(View.GONE);

        editDoneBar = text("DONE", 11f, 0xFF061015, false);
        editDoneBar.setGravity(Gravity.CENTER);
        editDoneBar.setSingleLine(true);
        editDoneBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        editDoneBar.setPadding(dp(8), dp(7), dp(8), dp(7));
        editDoneBar.setBackground(Ui.rounded(accent, dp(9)));
        editDoneBar.setContentDescription("Finish moving and resizing the overlay");
        editDoneBar.setVisibility(View.GONE);
        editDoneBar.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (editDoneAction != null) editDoneAction.run();
            }
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doneLp.topMargin = dp(6);
        addView(editDoneBar, doneLp);

        if (!horizontal) buildCompactChips();
        if (horizontal) buildHorizontalStrip(c);
    }

    /** One row of text: measured as a single line, so it always fits and resizes. */
    private void buildHorizontalStrip(Context c) {
        removeAllViews();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(12), dp(6), dp(12), dp(6));
        titleView = new LTextView(c);
        titleView.setSingleLine(true);
        titleView.setIncludeFontPadding(false);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setLetterSpacing(0.08f);
        titleView.setPadding(dp(9), dp(3), dp(9), dp(3));
        titleView.setText(presetName());
        stylePresetChip();
        addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stripText = new LTextView(c);
        stripText.setSingleLine(true);
        stripText.setIncludeFontPadding(false);
        String lang = Loc.languageCode(c);
        float stripSp = ("ja".equals(lang) || "zh-CN".equals(lang)) ? 8f
                : ("ko".equals(lang) || "th".equals(lang)) ? 8.8f : 10f;
        stripText.setTextSize(TypedValue.COMPLEX_UNIT_SP, stripSp);
        stripText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = dp(8);
        addView(stripText, textLp);
    }

    private void renderStrip(PerfStats s) {
        if (stripText == null) return;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        refreshPresetChip();
        if (s == null) {
            appendSpan(b, "waiting for data", 0xFFC4CBD6);
            stripText.setText(b);
            return;
        }
        Prefs p = new Prefs(getContext());
        boolean any = false;
        if (p.perfCpuTemp() || p.perfCpuClock() || p.perfCpuUsage()) {
            appendSpan(b, Loc.s(getContext(), "CPU ") + " ", MUTED);
            if (p.perfCpuTemp()) {
                appendSpan(b, fmtTemp(s.cpuTempC) + " ", s.cpuTempC > 0f
                        ? heat((s.cpuTempC - 32f) / 55f) : 0xFF8792A3);
            }
            if (p.perfCpuClock()) {
                appendSpan(b, (s.cpuHz > 0 ? fmtHz(s.cpuHz) : "\u2014") + " ",
                        s.cpuHz > 0 && s.cpuMaxHz > 0
                                ? heat(s.cpuHz / (float) s.cpuMaxHz) : 0xFF8792A3);
            }
            if (p.perfCpuUsage()) {
                appendSpan(b, (s.cpuUsageKnown ? s.cpuUsage + "%" : "\u2014") + "  ",
                        s.cpuUsageKnown ? heat(s.cpuUsage / 100f) : 0xFF8792A3);
            }
            any = true;
        }
        if (p.perfGpuTemp() || p.perfGpuClock() || p.perfGpuUsage()) {
            if (any) stripSep(b);
            appendSpan(b, Loc.s(getContext(), "GPU ") + " ", MUTED);
            if (p.perfGpuTemp()) {
                appendSpan(b, fmtTemp(s.gpuTempC) + " ", s.gpuTempC > 0f
                        ? heat((s.gpuTempC - 32f) / 55f) : 0xFF8792A3);
            }
            if (p.perfGpuClock()) {
                appendSpan(b, s.gpuHz > 0
                        ? fmtHz(s.gpuHz) + (s.gpuCapHz > 0 ? "/" + fmtHz(s.gpuCapHz) : "")
                        + " " : "\u2014 ", clockHeat(s.gpuHz, s.gpuMaxHz));
            }
            if (p.perfGpuUsage()) {
                appendSpan(b, (s.gpuUsageKnown ? s.gpuUsage + "%" : "\u2014") + "  ",
                        s.gpuUsageKnown ? heat(s.gpuUsage / 100f) : 0xFF8792A3);
            }
            any = true;
        }
        if (p.perfNet()) {
            if (any) stripSep(b);
            appendSpan(b, "NET ", MUTED);
            appendSpan(b, (s.netKnown ? "\u2191 " + SysStats.fmtRate(s.netTxBps)
                    + " \u2193 " + SysStats.fmtRate(s.netRxBps)
                    : "\u2191 \u2014  \u2193 \u2014") + "  ", TEXT);
            any = true;
        }
        if (p.perfRam()) {
            if (any) stripSep(b);
            appendSpan(b, "RAM ", MUTED);
            if (s.memTotalKb > 0) {
                int totalGb = Math.max(1, Math.round(s.memTotalKb / 1_000_000f));
                float usedGb = Math.max(0f, (s.memTotalKb - s.memAvailKb) / 1_000_000f);
                appendSpan(b, String.format(Locale.US, "%.1f/%dG", usedGb, totalGb) + "  ",
                        levelHeat(s.memPercent / 100f));
            } else {
                appendSpan(b, "\u2014  ", TEXT);
            }
            any = true;
        }
        if (p.perfBattery()) {
            if (any) stripSep(b);
            appendSpan(b, "BATT ", MUTED);
            appendSpan(b, fmtTemp(s.batteryTempC) + " ", s.batteryTempC > 0f
                    ? heat((s.batteryTempC - 30f) / 40f) : 0xFF8792A3);
            appendSpan(b, (s.powerW > 0f ? String.format(Locale.US, "%.1fW", s.powerW) : "\u2014") + "  ",
                    s.powerW > 0f ? heat(s.powerW / 25f) : 0xFF8792A3);
            any = true;
        }
        if (p.perfFps()) {
            if (any) stripSep(b);
            appendSpan(b, "FPS ", MUTED);
            String fpsText = "\u2014";
            if (s.fpsValid) fpsText = String.valueOf(s.fps);
            appendSpan(b, fpsText + "  ",
                    s.fpsValid ? fpsColor(s.fps) : 0xFFC4CBD6);
            any = true;
        }
        if (p.perfBattery() && s.battery >= 0) {
            if (any) stripSep(b);
            appendSpan(b, "\u00b7 " + s.battery + "%", batteryColor(s.battery));
        }
        if (p.perfFan()) {
            if (any) stripSep(b);
            appendSpan(b, "FAN ", MUTED);
            int duty = PerfTuner.fanPercent(getContext());
            appendSpan(b, (duty >= 0 ? duty + "%" : "\u2014") + "  ",
                    duty >= 0 ? heat(duty / 100f) : 0xFF8792A3);
        }
        if (!any) appendSpan(b, "no metrics selected", 0xFFC4CBD6);
        stripText.setText(b);
    }

    private String fmtTemp(float c) {
        return c > 0f ? Math.round(c) + "\u00b0" : "\u2014";
    }

    /** Thin white rule between two metrics in the strip. */
    private void stripSep(android.text.SpannableStringBuilder b) {
        appendSpan(b, "\u2502  ", 0xFFD5DAE2);
    }

    private void appendSpan(android.text.SpannableStringBuilder b, String text, int color) {
        int start = b.length();
        b.append(text);
        b.setSpan(new android.text.style.ForegroundColorSpan(color), start, b.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Current tuner preset shown in place of the static PERFORMANCE label. */
    private String presetName() {
        long now = android.os.SystemClock.uptimeMillis();
        if (presetLabel == null || now - presetReadAt >= 1000L) {
            presetReadAt = now;
            TuneConfig config = new TuneStore(getContext()).global();
            String name = config == null || config.profile == null || config.profile.isEmpty()
                    ? PerfProfiles.MEDIUM : config.profile;
            presetLabel = name.toUpperCase(java.util.Locale.US);
        }
        return presetLabel;
    }

    private void stylePresetChip() {
        if (titleView == null) return;
        int color = presetColor();
        int fill = (0x1F << 24) | (color & 0x00FFFFFF);
        int stroke = (0xA6 << 24) | (color & 0x00FFFFFF);
        titleView.setTextColor(color);
        titleView.setBackground(Ui.rounded(fill, dp(9), stroke, dp(1)));
    }

    private void refreshPresetChip() {
        if (titleView == null) return;
        String label = presetName();
        if (!label.contentEquals(titleView.getText())) {
            titleView.setText(label);
            stylePresetChip();
        }
    }

    /** True when a panel-local point lands on the preset title. */
    boolean hitPresetLabel(float x, float y) {
        if (fusion != null) return false;
        if (x < 0f || y < 0f || x > getWidth() || y > getHeight()) return false;
        if (titleView == null) return false;
        float tx = titleView.getLeft();
        float ty = titleView.getTop();
        if (titleView.getParent() instanceof View) {
            tx += ((View) titleView.getParent()).getLeft();
            ty += ((View) titleView.getParent()).getTop();
        }
        return x >= tx && x <= tx + titleView.getWidth()
                && y >= ty - dp(5) && y <= ty + titleView.getHeight() + dp(5);
    }

    void invalidatePreset() {
        presetLabel = null;
        presetReadAt = 0L;
    }

    /** The tuner's own per-preset colour, so the title matches the mode. */
    private int presetColor() {
        return PerformanceTunerActivity.profileColor(presetName());
    }

    boolean horizontal() {
        return horizontal;
    }

    void setLocked(boolean locked) {
        if (fusion != null) {
            fusion.setLocked(locked);
            invalidate();
            return;
        }
        if (lockIcon != null) {
            lockIcon.setVisibility(locked ? View.VISIBLE : View.GONE);
        }
    }

    void setEditMode(boolean editing, Runnable doneAction) {
        editDoneAction = doneAction;
        if (editDoneBar != null) {
            editDoneBar.setVisibility(editing ? View.VISIBLE : View.GONE);
        }
    }

    /** Bannerlator Fusion HUD: canvas-drawn, five size modes, hugging card. */
    private void initFusion(Context c) {
        fusion = new FusionHudRenderer(c);
        lastPrefs = new Prefs(c);
        setClipChildren(false);
        editDoneBar = text("DONE", 11f, 0xFF061015, false);
        editDoneBar.setGravity(Gravity.CENTER);
        editDoneBar.setSingleLine(true);
        editDoneBar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        editDoneBar.setPadding(dp(8), dp(7), dp(8), dp(7));
        editDoneBar.setBackground(Ui.rounded(accent, dp(9)));
        editDoneBar.setContentDescription("Finish moving and resizing the overlay");
        editDoneBar.setVisibility(View.GONE);
        editDoneBar.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (editDoneAction != null) editDoneAction.run();
            }
        });
        addView(editDoneBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fusion.applyPrefs(lastPrefs);
    }

    private void buildCompactChips() {
        chipFps = compactChip("FPS", 0xFF7CFF6B);
        chipCpu = compactChip("CPU", 0xFF42A5F5);
        chipGpu = compactChip("GPU", 0xFFEF5350);
        chipBatt = compactChip("BATT", 0xFFBDBDBD);
        chipRam = compactChip("RAM", 0xFFFFEE58);
        chipNet = compactChip("NET", 0xFFB8C4CE);
        chipFan = compactChip("FAN", 0xFF4DD0E1);
        for (TextView chip : new TextView[]{chipFps, chipCpu, chipGpu, chipBatt, chipRam,
                chipNet, chipFan}) {
            compactBox.addView(chip);
        }
    }

    private TextView compactChip(String label, int color) {
        TextView v = text(label, 9.5f, color, false);
        v.setSingleLine(true);
        v.setPadding(dp(5), dp(2), dp(5), dp(2));
        v.setBackground(Ui.rounded(0x18FFFFFF, dp(7), 0x22FFFFFF, dp(1)));
        return v;
    }

    /** Compact, Bannerlator-style wrap form; text keeps its size at any card width. */
    void setCompact(boolean compact) {
        if (fusion != null) return;
        setForm(compact ? Math.min(form, 3) : Math.max(form, 4));
    }

    /** Size form: 0 = FPS only, 3 = compact wrap with every chip, 4 = stacked, 5 = spread. */
    void setForm(int f) {
        if (fusion != null) {
            if (fusion.mode() == Math.max(0, Math.min(FusionHudRenderer.MODE_COUNT - 1, f))) return;
            fusion.setMode(f);
            requestLayout();
            invalidate();
            return;
        }
        f = Math.max(0, Math.min(5, f));
        boolean compact = f <= 3;
        form = f;
        compactMode = compact;
        stackedBox.setVisibility(compact ? GONE : VISIBLE);
        compactBox.setVisibility(compact ? VISIBLE : GONE);
        int hpad = compact ? 10 : 16;
        int vpad = compact ? 7 : 12;
        setPadding(dp(hpad), dp(vpad), dp(hpad), dp(vpad + 1));
        if (chipFps != null) {
            chipFps.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact && f == 0 ? 16f : 10f);
            chipFps.setPadding(dp(f == 0 ? 7 : 5), dp(f == 0 ? 4 : 2),
                    dp(f == 0 ? 7 : 5), dp(f == 0 ? 4 : 2));
            chipFps.setBackground(f == 0 ? null
                    : Ui.rounded(0x18FFFFFF, dp(7), 0x22FFFFFF, dp(1)));
        }
        if (lastPrefs != null) applyVisibility(lastPrefs);
        requestLayout();
    }

    private static String nonEmpty(TextView v) {
        if (v == null || v.getVisibility() != VISIBLE) return "";
        CharSequence t = v.getText();
        return t == null ? "" : t.toString();
    }

    private static String joinParts(String label, TextView... parts) {
        StringBuilder b = new StringBuilder(label);
        for (TextView v : parts) {
            String t = nonEmpty(v);
            if (!t.isEmpty()) b.append(' ').append(t);
        }
        return b.toString();
    }

    private static String textOf(TextView v) {
        if (v == null) return "";
        CharSequence t = v.getText();
        return t == null ? "" : t.toString();
    }

    private void syncCompactChips() {
        if (chipFps == null || lastPrefs == null) return;
        Prefs p = lastPrefs;
        int f = form;

        chipFps.setText(f == 0 ? textOf(fpsValue) : joinParts("FPS", fpsValue));
        chipFps.setVisibility(VISIBLE);

        StringBuilder cpu = new StringBuilder("CPU");
        if (f >= 1) cpu.append(' ').append(textOf(cpuTemp));
        if (f >= 2) cpu.append(' ').append(textOf(cpuUsage));
        if (f >= 3) cpu.append(' ').append(textOf(cpuClock));
        chipCpu.setText(cpu.toString());
        chipCpu.setVisibility(f >= 1 && (p.perfCpuTemp() || p.perfCpuUsage()
                || p.perfCpuClock()) ? VISIBLE : GONE);

        StringBuilder gpu = new StringBuilder("GPU");
        if (f >= 1) gpu.append(' ').append(textOf(gpuTemp));
        if (f >= 2) gpu.append(' ').append(textOf(gpuUsage));
        if (f >= 3) gpu.append(' ').append(textOf(gpuClock));
        chipGpu.setText(gpu.toString());
        chipGpu.setVisibility(f >= 1 && (p.perfGpuTemp() || p.perfGpuUsage()
                || p.perfGpuClock()) ? VISIBLE : GONE);

        StringBuilder batt = new StringBuilder("BATT");
        if (f >= 1) batt.append(' ').append(textOf(battTemp));
        if (f >= 3) {
            batt.append(' ').append(textOf(battPower));
            batt.append(' ').append(textOf(battery));
        }
        chipBatt.setText(batt.toString());
        chipBatt.setVisibility(f >= 1 && p.perfBattery() ? VISIBLE : GONE);

        chipRam.setText("RAM " + textOf(ramAmount));
        chipRam.setVisibility(f >= 3 && p.perfRam() ? VISIBLE : GONE);

        chipNet.setText("NET " + textOf(netUp) + " " + textOf(netDown));
        chipNet.setVisibility(f >= 3 && p.perfNet() ? VISIBLE : GONE);

        chipFan.setText("FAN " + textOf(fanDuty));
        chipFan.setVisibility(f >= 3 && p.perfFan() ? VISIBLE : GONE);
    }

    void applyPrefs(Prefs p) {
        lastPrefs = p;
        setPanelAlpha(p.perfOverlayAlpha());
        if (fusion != null) {
            fusion.applyPrefs(p);
            requestLayout();
            invalidate();
            return;
        }
        if (horizontal) {
            renderStrip(lastStats);
            graph.setRefreshMs(p.perfRefreshMs());
            requestLayout();
            return;
        }
        applyVisibility(p);
        graph.setRefreshMs(p.perfRefreshMs());
        syncCompactChips();
        requestLayout();
    }

    /**
     * Rows revealed as the card grows, in the Bannerlator order: FPS first,
     * then temperatures, then usage, then the remaining metrics, then graphs
     * and cores. The full spread form still shows every row.
     */
    private void applyVisibility(Prefs p) {
        int f = form;
        boolean compact = f <= 3;
        boolean showHeader = f >= 4;
        boolean showCores = f >= 5;
        boolean showGraph = f >= 4 && p.perfFpsGraph();

        boolean cpuTempOn = p.perfCpuTemp() && f >= 1;
        boolean cpuUsageOn = p.perfCpuUsage() && f >= 2;
        boolean cpuClockOn = p.perfCpuClock() && f >= 3;
        boolean gpuTempOn = p.perfGpuTemp() && f >= 1;
        boolean gpuUsageOn = p.perfGpuUsage() && f >= 2;
        boolean gpuClockOn = p.perfGpuClock() && f >= 3;
        boolean battTempOn = p.perfBattery() && f >= 1;
        boolean battPowerOn = p.perfBattery() && f >= 3;
        boolean netOn = p.perfNet() && f >= 3;
        boolean ramOn = p.perfRam() && f >= 3;
        boolean fanOn = p.perfFan() && f >= 3;
        boolean fpsOn = p.perfFps() || f == 0;

        if (headerRow != null) headerRow.setVisibility(showHeader ? VISIBLE : GONE);
        if (fpsLabel != null) fpsLabel.setVisibility(f == 0 ? GONE : VISIBLE);

        cpuTemp.setVisibility(cpuTempOn ? VISIBLE : GONE);
        cpuClock.setVisibility(cpuClockOn ? VISIBLE : GONE);
        cpuUsage.setVisibility(cpuUsageOn ? VISIBLE : GONE);
        cpuRow.setVisibility(cpuTempOn || cpuClockOn || cpuUsageOn ? VISIBLE : GONE);

        gpuTemp.setVisibility(gpuTempOn ? VISIBLE : GONE);
        gpuClock.setVisibility(gpuClockOn ? VISIBLE : GONE);
        gpuUsage.setVisibility(gpuUsageOn ? VISIBLE : GONE);
        gpuRow.setVisibility(gpuTempOn || gpuClockOn || gpuUsageOn ? VISIBLE : GONE);

        netRow.setVisibility(netOn ? VISIBLE : GONE);
        ramRow.setVisibility(ramOn ? VISIBLE : GONE);
        battRow.setVisibility(battTempOn || battPowerOn ? VISIBLE : GONE);
        battTemp.setVisibility(battTempOn ? VISIBLE : GONE);
        battPower.setVisibility(battPowerOn ? VISIBLE : GONE);
        fanRow.setVisibility(fanOn ? VISIBLE : GONE);
        fpsValue.setVisibility(fpsOn ? VISIBLE : GONE);
        fpsRow.setVisibility(fpsOn ? VISIBLE : GONE);
        graph.setVisibility(showGraph ? VISIBLE : GONE);
        fpsBlock.setVisibility(fpsOn || showGraph ? VISIBLE : GONE);

        battery.setVisibility(showHeader && p.perfBattery() ? VISIBLE : GONE);
        coresBox.setVisibility(showCores ? VISIBLE : GONE);
        syncCompactChips();
    }

    void update(PerfStats s) {
        if (fusion != null) {
            lastStats = s;
            fusion.update(s);
            requestLayout();
            invalidate();
            return;
        }
        refreshPresetChip();
        if (horizontal) {
            lastStats = s;
            renderStrip(s);
            return;
        }
        boolean cpuTempKnown = s.cpuTempC > 0f;
        setValue(cpuTemp, cpuTempKnown ? Math.round(s.cpuTempC) + "\u00b0" : "\u2014",
                cpuTempKnown ? heat((s.cpuTempC - 32f) / 55f) : 0xFF8792A3);
        boolean cpuClockKnown = s.cpuHz > 0;
        setValue(cpuClock, cpuClockKnown ? fmtHz(s.cpuHz) : "\u2014",
                cpuClockKnown ? heat(s.cpuMaxHz > 0
                        ? s.cpuHz / (float) s.cpuMaxHz : 0f) : 0xFF8792A3);
        setValue(cpuUsage, s.cpuUsageKnown ? s.cpuUsage + "%" : "\u2014",
                s.cpuUsageKnown ? heat(s.cpuUsage / 100f) : 0xFF8792A3);

        boolean gpuTempKnown = s.gpuTempC > 0f;
        setValue(gpuTemp, gpuTempKnown ? Math.round(s.gpuTempC) + "\u00b0" : "\u2014",
                gpuTempKnown ? heat((s.gpuTempC - 32f) / 55f) : 0xFF8792A3);
        boolean gpuClockKnown = s.gpuHz > 0;
        setValue(gpuClock, gpuClockKnown
                        ? fmtHz(s.gpuHz) + (s.gpuCapHz > 0 ? "/" + fmtHz(s.gpuCapHz) : "")
                        : "\u2014",
                gpuClockKnown ? clockHeat(s.gpuHz, s.gpuMaxHz) : 0xFF8792A3);
        setValue(gpuUsage, s.gpuUsageKnown ? s.gpuUsage + "%" : "\u2014",
                s.gpuUsageKnown ? heat(s.gpuUsage / 100f) : 0xFF8792A3);

        setValue(netUp, s.netKnown ? "\u2191 " + SysStats.fmtRate(s.netTxBps) : "\u2191 \u2014",
                0xFFD5DAE2);
        setValue(netDown, s.netKnown ? "\u2193 " + SysStats.fmtRate(s.netRxBps) : "\u2193 \u2014",
                0xFFD5DAE2);

        if (s.memTotalKb > 0) {
            int totalGb = Math.max(1, Math.round(s.memTotalKb / 1_000_000f));
            float usedGb = Math.max(0f, (s.memTotalKb - s.memAvailKb) / 1_000_000f);
            // No percentage: the used/total figure is the whole reading.
            ramUsage.setVisibility(GONE);
            setValue(ramAmount, String.format(Locale.US, "%.1f/%dG", usedGb, totalGb),
                    levelHeat(s.memPercent / 100f));
        } else {
            ramUsage.setVisibility(GONE);
            setValue(ramAmount, "\u2014", 0xFF8792A3);
        }

        int fanDutyPct = PerfTuner.fanPercent(getContext());
        setValue(fanDuty, fanDutyPct >= 0 ? fanDutyPct + "%" : "\u2014",
                fanDutyPct >= 0 ? heat(fanDutyPct / 100f) : 0xFF8792A3);

        boolean battTempKnown = s.batteryTempC > 0f;
        setValue(battTemp, battTempKnown ? Math.round(s.batteryTempC) + "\u00b0" : "\u2014",
                battTempKnown ? heat((s.batteryTempC - 30f) / 40f) : 0xFF8792A3);
        boolean powerKnown = s.powerW > 0f;
        setValue(battPower, powerKnown
                ? String.format(Locale.US, "%.1fW", s.powerW) : "\u2014",
                powerKnown ? heat(s.powerW / 25f) : 0xFF8792A3);

        if (s.battery >= 0) {
            battery.setText(s.battery + "%");
            battery.setTextColor(batteryColor(s.battery));
        } else {
            battery.setText("\u2014");
            battery.setTextColor(0xFF8792A3);
        }

        if (s.fpsValid) {
            fpsValue.setText(String.valueOf(s.fps));
            fpsValue.setTextColor(fpsColor(s.fps));
            graph.push(s.fps);
        } else {
            fpsValue.setText("\u2014");
            fpsValue.setTextColor(0xFF8792A3);
        }

        if (compactMode) syncCompactChips();

        for (int i = 0; i < cores.length; i++) {
            CoreView cv = cores[i];
            if (cv == null) continue;
            if (i < s.coreCount) {
                cv.setVisibility(VISIBLE);
                cv.setData(s.coreOnline[i], s.coreUsage[i], s.coreHz[i], s.coreMaxHz[i],
                        s.coreStatsKnown);
            } else {
                cv.setVisibility(GONE);
            }
        }
    }

    private LinearLayout metricRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView addMetricLabel(LinearLayout row, String text) {
        TextView v = label(text, 9.5f, MUTED);
        v.setLetterSpacing(0.12f);
        row.addView(v, new LinearLayout.LayoutParams(dp(38),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private TextView metricValue(int gravity) {
        TextView v = text("--", 12.5f, TEXT, true);
        v.setGravity(gravity | Gravity.CENTER_VERTICAL);
        return v;
    }

    /** Packed value cell: the row takes only as much width as its text needs. */
    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        return lp;
    }

    /**
     * Full-size form keeps the original spread columns; smaller forms pack the
     * values so nothing is wasted.
     */
    void setSpread(boolean value) {
        if (fusion != null) return;
        if (spread == value) return;
        spread = value;
        cpuTemp.setLayoutParams(cellOr(0.9f));
        cpuClock.setLayoutParams(cellOr(1.05f));
        cpuUsage.setLayoutParams(cellOr(0.85f));
        gpuTemp.setLayoutParams(cellOr(0.9f));
        gpuClock.setLayoutParams(cellOr(1.05f));
        gpuUsage.setLayoutParams(cellOr(0.85f));
        netUp.setLayoutParams(cellOr(1.3f));
        netDown.setLayoutParams(cellOr(1.3f));
        ramUsage.setLayoutParams(cellOr(1.0f));
        ramAmount.setLayoutParams(cellOr(1.4f));
        battTemp.setLayoutParams(cellOr(1.0f));
        battPower.setLayoutParams(cellOr(1.4f));
        fanDuty.setLayoutParams(cellOr(1f));
        fpsValue.setLayoutParams(cellOr(1f));
        requestLayout();
    }

    private ViewGroup.LayoutParams cellOr(float w) {
        return spread ? weight(w) : cell();
    }

    /** Natural width of the visible metrics, for a card that hugs its content. */
    int naturalWidthPx() {
        if (fusion != null) {
            measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            return getMeasuredWidth();
        }
        measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return getMeasuredWidth();
    }

    boolean graphShown() {
        if (fusion != null) return fusion.graphShown();
        return graph != null && graph.getVisibility() == View.VISIBLE;
    }

    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private LinearLayout.LayoutParams rowParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView label(String text, float sp, int color) {
        TextView v = text(text, sp, color, false);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private TextView text(String text, float sp, int color, boolean mono) {
        TextView v = new LTextView(getContext());
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private void setValue(TextView v, String text, int color) {
        v.setText(text);
        v.setTextColor(color);
    }

    void setPanelAlpha(int percent) {
        panelAlphaPct = Math.max(25, Math.min(100, percent));
        if (fusion != null) fusion.setOpacity(panelAlphaPct);
        invalidate();
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private static Bitmap makeFrostNoise() {
        int size = 64;
        int[] pixels = new int[size * size];
        Random random = new Random(23);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(20);
            boolean white = random.nextBoolean();
            pixels[i] = (alpha << 24) | (white ? 0x00FFFFFF : 0x00000000);
        }
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        tile.setPixels(pixels, 0, size, 0, 0, size, size);
        return tile;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (fusion != null) {
            int extra = 0;
            if (editDoneBar != null && editDoneBar.getVisibility() == View.VISIBLE) {
                editDoneBar.measure(View.MeasureSpec.makeMeasureSpec(
                                View.MeasureSpec.getSize(widthSpec),
                                View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED
                                        ? View.MeasureSpec.UNSPECIFIED : View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                extra = editDoneBar.getMeasuredHeight() + dp(6);
            }
            int w = (int) Math.ceil(fusion.width());
            int h = (int) Math.ceil(fusion.height()) + extra;
            setMeasuredDimension(resolveSize(Math.max(dp(64), w), widthSpec),
                    resolveSize(Math.max(dp(36), h), heightSpec));
            return;
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (fusion != null) {
            if (editDoneBar != null && editDoneBar.getVisibility() == View.VISIBLE) {
                int m = dp(10);
                int bw = r - l - m * 2;
                int bh = editDoneBar.getMeasuredHeight();
                editDoneBar.layout(m, b - t - bh - dp(5), m + bw, b - t - dp(5));
            }
            return;
        }
        super.onLayout(changed, l, t, r, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (fusion != null) {
            fusion.draw(canvas, getWidth(), getHeight());
            return;
        }
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        rect.set(0, 0, w, h);
        // Frosted glass only: a translucent body with a soft sheen, a light rim
        // and film grain. Nothing is captured or blurred at run time, so the
        // panel costs the same as any other view.
        // 100% is now fully opaque; the low end stays as it was.
        int alpha = Math.round(255f * panelAlphaPct / 100f);
        bgPaint.setColor((alpha << 24) | 0x0C1016);
        canvas.drawRoundRect(rect, corner, corner, bgPaint);

        // Accent halo in the top corner gives the surface a lit, glassy depth.
        tintPaint.setShader(new RadialGradient(w * 0.26f, h * 0.02f, w * 0.85f,
                ThemeColors.withAlpha(0x1A), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, corner, corner, tintPaint);

        tintPaint.setShader(new LinearGradient(0, 0, 0, h * 0.30f,
                0x12FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, corner, corner, tintPaint);
        tintPaint.setShader(new LinearGradient(0, h * 0.72f, 0, h,
                0x00000000, 0x30000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, corner, corner, tintPaint);
        tintPaint.setShader(null);

        if (frostNoise != null) {
            BitmapShader noise = new BitmapShader(frostNoise,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            Matrix nm = new Matrix();
            nm.setScale(2f, 2f);
            noise.setLocalMatrix(nm);
            noisePaint.setAlpha(20);
            noisePaint.setShader(noise);
            canvas.drawRoundRect(rect, corner, corner, noisePaint);
            noisePaint.setShader(null);
        }

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(1));
        border.setColor(ThemeColors.withAlpha(0x66));
        canvas.drawRoundRect(rect, corner, corner, border);
    }

    static int heat(float t) {
        float v = Math.max(0f, Math.min(1f, t));
        int green = 0xFF69D98B;
        int amber = 0xFFFFC857;
        int red = 0xFFFF5C6C;
        if (v < 0.5f) return blend(green, amber, v * 2f);
        return blend(amber, red, (v - 0.5f) * 2f);
    }

    /**
     * Clock colour: the lowest GPU steps sit at ~35% of the hardware maximum, so
     * a plain ratio would already read yellow. Ignore the idle third of the range
     * and the step goes green at rest, amber mid-boost, red at the ceiling.
     */
    static int clockHeat(long hz, long maxHz) {
        if (hz <= 0 || maxHz <= 0) return 0xFF8792A3;
        return levelHeat(hz / (float) maxHz);
    }

    /** Same green-to-red ramp for any 0..1 level, ignoring the lowest quarter. */
    static int levelHeat(float ratio) {
        return heat((ratio - 0.25f) / 0.75f);
    }

    static int fpsColor(int fps) {
        return heat(1f - Math.min(1f, fps / 60f));
    }

    static int batteryColor(int pct) {
        if (pct >= 50) return 0xFF69D98B;
        if (pct >= 20) return 0xFFFFC857;
        return 0xFFFF5C6C;
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    static String fmtHz(long hz) {
        if (hz <= 0) return "\u2014";
        if (hz >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1fG", hz / 1_000_000_000.0);
        }
        if (hz >= 1_000_000L) return Math.round(hz / 1_000_000.0) + "M";
        return Math.round(hz / 1000.0) + "K";
    }

    /** 60-point rolling FPS graph. */
    private static final class FpsGraphView extends View {
        private int[] values = new int[60];
        private int pointCount;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path fillPath = new Path();
        private final RectF box = new RectF();
        private boolean primed;
        private final float density;

        FpsGraphView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
            grid.setStrokeWidth(Math.max(1f, density));
            line.setStrokeWidth(Math.max(1.5f, density * 1.6f));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        void setRefreshMs(int ms) {
            int cap = Math.max(6, Math.min(240,
                    Math.round(60_000f / Math.max(500, Math.min(10_000, ms)))));
            if (cap == values.length) return;
            int keep = Math.min(pointCount, cap);
            int[] next = new int[cap];
            if (keep > 0) {
                System.arraycopy(values, pointCount - keep, next, cap - keep, keep);
            }
            values = next;
            pointCount = keep;
            invalidate();
        }

        void push(int fps) {
            int v = Math.max(0, Math.min(240, fps));
            if (!primed) {
                for (int i = 0; i < values.length; i++) values[i] = v;
                pointCount = values.length;
                primed = true;
            } else if (pointCount < values.length) {
                values[pointCount++] = v;
            } else {
                System.arraycopy(values, 1, values, 0, values.length - 1);
                values[values.length - 1] = v;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 2 || h <= 2) return;
            float r = Math.min(w, h) * 0.24f;
            box.set(0, 0, w, h);
            bg.setColor(0x33101820);
            canvas.drawRoundRect(box, r, r, bg);
            grid.setColor(0x22FFFFFF);
            for (int i = 1; i <= 3; i++) {
                float y = h * i / 4f;
                canvas.drawLine(dp(5), y, w - dp(5), y, grid);
            }

            if (pointCount < 1) return;
            int last = values[pointCount - 1];
            int color = fpsColor(last);
            float max = 120f;
            float step = pointCount > 1 ? w / (pointCount - 1f) : w;
            path.reset();
            fillPath.reset();
            for (int i = 0; i < pointCount; i++) {
                float x = i * step;
                float y = h - Math.min(values[i], max) / max * (h - dp(4)) - dp(2);
                if (i == 0) {
                    path.moveTo(x, y);
                    fillPath.moveTo(x, h);
                    fillPath.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
            }
            fillPath.lineTo(w, h);
            fillPath.close();
            fill.setShader(new LinearGradient(0, 0, 0, h,
                    (color & 0x00FFFFFF) | 0x55000000,
                    (color & 0x00FFFFFF) | 0x05000000,
                    Shader.TileMode.CLAMP));
            canvas.drawPath(fillPath, fill);
            fill.setShader(null);
            line.setColor(color);
            canvas.drawPath(path, line);
            line.setStyle(Paint.Style.FILL);
            canvas.drawCircle(w, h - Math.min(last, max) / max * (h - dp(4)) - dp(2),
                    Math.max(2f, density * 1.7f), line);
            line.setStyle(Paint.Style.STROKE);
        }

        private float dp(float v) {
            return v * density;
        }
    }

    /** One compact core row: name, load bar, usage %, current clock. */
    private final class CoreView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final int index;
        private final float density;
        private final float textSize;
        private boolean online;
        private boolean usageKnown = true;
        private float usage;
        private long hz;
        private long maxHz;

        CoreView(Context c, int index) {
            super(c);
            this.index = index;
            density = c.getResources().getDisplayMetrics().density;
            textSize = 10f * c.getResources().getDisplayMetrics().scaledDensity;
            setWillNotDraw(false);
        }

        void setData(boolean online, float usage, long hz, long maxHz, boolean usageKnown) {
            this.online = online;
            this.usageKnown = usageKnown;
            this.usage = Math.max(0f, Math.min(100f, usage));
            this.hz = hz;
            this.maxHz = maxHz;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 2 || h <= 2) return;
            float center = h / 2f;
            float baseline = center + textSize * 0.35f;
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(textSize);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(online ? 0xFFC2CAD6 : 0xFF8B95A3);
            canvas.drawText("C" + index, 0, baseline, p);

            float left = density * 22;
            float right = w - density * 68;
            if (right < left + density * 8) right = left + density * 8;
            bar.set(left, center - density * 2.2f, right, center + density * 2.2f);
            p.setColor(0x22FFFFFF);
            canvas.drawRoundRect(bar, density * 2.2f, density * 2.2f, p);
            if (online) {
                float fillRight = left + (right - left) * (usage / 100f);
                bar.right = fillRight;
                p.setColor(heat(usage / 100f));
                canvas.drawRoundRect(bar, density * 2.2f, density * 2.2f, p);
            }

            p.setTextAlign(Paint.Align.RIGHT);
            p.setColor(online ? heat(usage / 100f) : 0xFF8B95A3);
            canvas.drawText(Loc.number(getContext(), !online ? "off" : usageKnown
                            ? Math.round(usage) + "%" : "\u2014"),
                    w - density * 34, baseline, p);
            int clockColor = !online ? 0xFF8B95A3
                    : heat(maxHz > 0 ? hz / (float) maxHz : 0f);
            p.setColor(clockColor);
            canvas.drawText(online ? Loc.number(getContext(), fmtHz(hz)) : "\u2014", w, baseline, p);
        }
    }
}
