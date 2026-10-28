package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Detailed, colour-coded performance monitor for the app the overlay is sampling.
 * Like the overlay's control card, it is a non-focusable accessibility overlay so the
 * target app remains in front and keeps producing frame data.
 */
final class PerfDetailView extends FrameLayout {
    private int accent = 0xFFA9BEFF;
    private static final int MUTED = 0x7AFFFFFF;
    private static final int TEXT = 0xF2FFFFFF;

    private final Runnable onClose;
    private final TextView appName;
    private final TextView packageName;
    private final TextView fpsBig;
    private final TextView avgFps;
    private final TextView minFps;
    private final TextView maxFps;
    private final TextView cpuTemp;
    private final TextView cpuLoad;
    private final TextView cpuClock;
    private final TextView gpuTemp;
    private final TextView gpuLoad;
    private final TextView gpuClock;
    private final TextView power;
    private final TextView peakPower;
    private final TextView throttle;
    private final TextView runtime;
    private final TextView batteryUsed;
    private final TextView batteryStartView;
    private final TextView appRam;
    private final TextView systemRam;
    private final FrameGraph graph;

    PerfDetailView(Context c, Runnable onClose) {
        super(c);
        ThemeColors.initialize(c);
        accent = ThemeColors.accent();
        this.onClose = onClose;
        setClickable(true);

        View scrim = new View(c);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PerfDetailView.this.onClose.run();
            }
        });
        addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int cardW = Math.min(dp(780), screenW - dp(28));
        int cardH = screenH - dp(28);

        GlassPanel glass = new GlassPanel(c, null, accent, 0xE20B0E14, 26, true);
        glass.setClickable(true);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, cardH, Gravity.CENTER);
        addView(glass, cardLp);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        glass.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(22));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("PERFORMANCE DETAILS", 10f, accent, false);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(9), 0, dp(5), 0);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PerfDetailView.this.onClose.run();
            }
        });
        header.addView(close);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        appName = text("Waiting for an app", 24f, TEXT, false);
        appName.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        appName.setPadding(0, dp(4), 0, 0);
        content.addView(appName);
        packageName = text("", 10f, MUTED, true);
        packageName.setPadding(0, dp(2), 0, dp(8));
        content.addView(packageName);

        LinearLayout fpsRow = new LinearLayout(c);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.BOTTOM);
        TextView fpsLabel = text("FPS", 9f, MUTED, false);
        fpsLabel.setLetterSpacing(0.12f);
        fpsLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fpsLabel.setPadding(0, 0, 0, dp(5));
        fpsRow.addView(fpsLabel);
        fpsBig = text("\u2014", 46f, TEXT, true);
        fpsBig.setPadding(dp(10), 0, dp(4), 0);
        fpsRow.addView(fpsBig);
        TextView fpsUnit = text("fps", 10f, MUTED, false);
        fpsUnit.setPadding(0, 0, 0, dp(7));
        fpsRow.addView(fpsUnit);
        fpsRow.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));

        avgFps = fpsChip(fpsRow, "AVG");
        minFps = fpsChip(fpsRow, "MIN");
        maxFps = fpsChip(fpsRow, "MAX");
        content.addView(fpsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        graph = new FrameGraph(c);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(128));
        glp.topMargin = dp(8);
        content.addView(graph, glp);

        addSection(content, "HARDWARE");
        LinearLayout hw = new LinearLayout(c);
        hw.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout cpuCard = metricCard(hw, "CPU");
        cpuTemp = metricValue(cpuCard);
        cpuLoad = metricValue(cpuCard);
        cpuClock = metricValue(cpuCard);
        hw.addView(new View(c), new LinearLayout.LayoutParams(dp(8), 1));
        LinearLayout gpuCard = metricCard(hw, "GPU");
        gpuTemp = metricValue(gpuCard);
        gpuLoad = metricValue(gpuCard);
        gpuClock = metricValue(gpuCard);
        content.addView(hw);

        addSection(content, "POWER & THERMAL");
        LinearLayout powerRow = row();
        power = statCell(powerRow, "Power draw");
        peakPower = statCell(powerRow, "Peak power");
        content.addView(powerRow);
        LinearLayout throttleRow = row();
        throttle = statCell(throttleRow, "Throttle");
        content.addView(throttleRow);

        addSection(content, "SESSION");
        LinearLayout session1 = row();
        runtime = statCell(session1, "Runtime");
        batteryUsed = statCell(session1, "Battery used");
        content.addView(session1);
        LinearLayout session2 = row();
        batteryStartView = statCell(session2, "Battery now");
        appRam = statCell(session2, "App RAM");
        content.addView(session2);
        LinearLayout session3 = row();
        systemRam = statCell(session3, "System RAM");
        content.addView(session3);
    }

    void update(PerfDetailData d) {
        if (d == null) return;
        appName.setText(d.appLabel == null || d.appLabel.isEmpty()
                ? "No foreground app" : d.appLabel);
        packageName.setText(d.pkg == null ? "" : d.pkg);

        fpsBig.setText(d.fpsValid ? String.valueOf(d.fps) : "\u2014");
        fpsBig.setTextColor(d.fpsValid ? PerfOverlayView.fpsColor(d.fps) : 0x55FFFFFF);
        if (d.maxFps <= 0) {
            avgFps.setText("AVG \u2014");
            minFps.setText("MIN \u2014");
            maxFps.setText("MAX \u2014");
            avgFps.setTextColor(MUTED);
            minFps.setTextColor(MUTED);
            maxFps.setTextColor(MUTED);
        } else {
            avgFps.setText("AVG " + d.avgFps);
            minFps.setText("MIN " + d.minFps);
            maxFps.setText("MAX " + d.maxFps);
            avgFps.setTextColor(PerfOverlayView.fpsColor(d.avgFps));
            minFps.setTextColor(PerfOverlayView.fpsColor(d.minFps));
            maxFps.setTextColor(PerfOverlayView.fpsColor(d.maxFps));
        }
        graph.setHistory(d.history);

        setMetric(cpuTemp, d.cpuTempC > 0 ? Math.round(d.cpuTempC) + "\u00b0" : "\u2014",
                d.cpuTempC > 0 ? PerfOverlayView.heat((d.cpuTempC - 32f) / 55f) : 0x55FFFFFF);
        setMetric(cpuLoad, d.cpuUsage + "%", PerfOverlayView.heat(d.cpuUsage / 100f));
        setMetric(cpuClock, PerfOverlayView.fmtHz(d.cpuHz),
                d.cpuHz > 0 ? PerfOverlayView.heat(d.cpuMaxHz > 0
                        ? d.cpuHz / (float) d.cpuMaxHz : 0f) : 0x55FFFFFF);

        setMetric(gpuTemp, d.gpuTempC > 0 ? Math.round(d.gpuTempC) + "\u00b0" : "\u2014",
                d.gpuTempC > 0 ? PerfOverlayView.heat((d.gpuTempC - 32f) / 55f) : 0x55FFFFFF);
        setMetric(gpuLoad, d.gpuUsage + "%", PerfOverlayView.heat(d.gpuUsage / 100f));
        setMetric(gpuClock, PerfOverlayView.fmtHz(d.gpuHz),
                d.gpuHz > 0 ? PerfOverlayView.heat(d.gpuMaxHz > 0
                        ? d.gpuHz / (float) d.gpuMaxHz : 0f) : 0x55FFFFFF);

        power.setText(String.format(Locale.US, "%.1f W", d.powerW));
        power.setTextColor(PerfOverlayView.heat(d.powerW / 9f));
        peakPower.setText(String.format(Locale.US, "%.1f W", d.peakPowerW));
        peakPower.setTextColor(PerfOverlayView.heat(d.peakPowerW / 9f));

        throttle.setText(d.throttle);
        if ("NONE".equals(d.throttle)) throttle.setTextColor(0xFF69D98B);
        else if ("THERMAL".equals(d.throttle)) throttle.setTextColor(0xFFFF5C6C);
        else throttle.setTextColor(0xFFFFC857);

        runtime.setText(formatDuration(d.runtimeMs));
        runtime.setTextColor(0xD9FFFFFF);

        String batteryText = String.format(Locale.US, "Used %.1f%%", d.batteryUsedPct);
        if (d.batteryUsedMah > 0) {
            batteryText += String.format(Locale.US, " · %.0f mAh", d.batteryUsedMah);
        }
        batteryUsed.setText(batteryText);
        batteryUsed.setTextColor(PerfOverlayView.heat(Math.min(1f, d.batteryUsedPct / 18f)));
        batteryStartView.setText(d.battery >= 0 ? d.battery + "%" : "\u2014");
        batteryStartView.setTextColor(d.battery >= 0
                ? PerfOverlayView.batteryColor(d.battery) : 0x55FFFFFF);

        appRam.setText(fmtBytes(d.appRamKb * 1024L));
        appRam.setTextColor(0xE6FFFFFF);
        long usedKb = Math.max(0, d.memTotalKb - d.memAvailKb);
        systemRam.setText(d.memPercent + "%  " + fmtBytes(usedKb * 1024L)
                + " / " + fmtBytes(d.memTotalKb * 1024L));
        systemRam.setTextColor(PerfOverlayView.heat(d.memPercent / 100f));
    }

    private TextView fpsChip(LinearLayout parent, String name) {
        TextView t = text(name + " \u2014", 9.5f, MUTED, true);
        t.setPadding(dp(7), dp(3), dp(7), dp(3));
        t.setBackground(Ui.rounded(0x18FFFFFF, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        parent.addView(t, lp);
        return t;
    }

    private LinearLayout metricCard(LinearLayout parent, String name) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        card.setBackground(Ui.rounded(0x14FFFFFF, dp(12), 0x1FFFFFFF, dp(1)));
        TextView title = text(name, 9f, accent, false);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        parent.addView(card, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    private TextView metricValue(LinearLayout card) {
        TextView v = text("\u2014", 15f, TEXT, true);
        v.setPadding(0, dp(3), 0, 0);
        card.addView(v);
        return v;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private TextView statCell(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(0, dp(3), dp(16), dp(3));
        TextView name = text(label, 10f, MUTED, false);
        cell.addView(name);
        TextView value = text("\u2014", 15f, TEXT, true);
        value.setPadding(0, dp(1), 0, 0);
        cell.addView(value);
        row.addView(cell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return value;
    }

    private void addSection(LinearLayout parent, String title) {
        TextView t = text(title, 9f, 0x66FFFFFF, false);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(4);
        parent.addView(t, lp);
    }

    private void setMetric(TextView v, String value, int color) {
        v.setText(value);
        v.setTextColor(color);
    }

    private static String fmtBytes(long bytes) {
        if (bytes <= 0) return "\u2014";
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
    }

    private static String formatDuration(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView t = new LTextView(getContext());
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    /** Frame-time line graph, colour-coded by the newest sample. */
    private static final class FrameGraph extends View {
        private static final int MAX_POINTS = 120;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path fillPath = new Path();
        private final RectF box = new RectF();
        private final float density;
        private int[] values = new int[0];

        FrameGraph(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
            grid.setStrokeWidth(Math.max(1f, density));
            line.setStrokeWidth(Math.max(1.5f, density * 1.8f));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        void setHistory(int[] history) {
            values = history == null ? new int[0] : history;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 2 || h <= 2) return;
            float r = dp(12);
            box.set(0, 0, w, h);
            bg.setColor(0x33101820);
            canvas.drawRoundRect(box, r, r, bg);
            grid.setColor(0x22FFFFFF);
            for (int i = 1; i <= 4; i++) {
                float y = h * i / 5f;
                canvas.drawLine(dp(6), y, w - dp(6), y, grid);
            }
            if (values.length < 2) return;

            int max = 60;
            for (int v : values) max = Math.max(max, v);
            max = Math.max(30, Math.min(240, max));
            int last = values[values.length - 1];
            int color = PerfOverlayView.fpsColor(last);
            float step = w / (values.length - 1f);
            path.reset();
            fillPath.reset();
            for (int i = 0; i < values.length; i++) {
                float x = i * step;
                float y = h - Math.min(values[i], max) / (float) max * (h - dp(8)) - dp(4);
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
            canvas.drawCircle(w, h - Math.min(last, max) / (float) max
                    * (h - dp(8)) - dp(4), Math.max(2f, density * 1.8f), line);
        }

        private float dp(float v) {
            return v * density;
        }
    }
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) Haptics.tick(this);
        return super.dispatchTouchEvent(event);
    }

}
