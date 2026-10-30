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
    private static final int MUTED = 0x66FFFFFF;
    private static final int TEXT = 0xF2FFFFFF;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Bitmap frostNoise;
    private final RectF rect = new RectF();
    private final int corner;
    private int panelAlphaPct = 88;

    private TextView battery;
    private TextView lockIcon;
    private TextView editDoneBar;
    private Runnable editDoneAction;
    private LinearLayout cpuRow, gpuRow, fpsBlock, coresBox;
    private LinearLayout ramRow;
    private LinearLayout battRow;
    private TextView cpuTemp, cpuClock, cpuUsage;
    private TextView gpuTemp, gpuClock, gpuUsage;
    private TextView ramUsage, ramAmount;
    private TextView battTemp, battPower;
    private TextView fpsValue;
    private LinearLayout fpsRow;
    private FpsGraphView graph;
    private final CoreView[] cores = new CoreView[PerfStats.MAX_CORES];

    PerfOverlayView(Context c) {
        super(c);
        ThemeColors.initialize(c);
        accent = ThemeColors.accent();
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setClickable(false);
        setClipChildren(false);
        setPadding(dp(14), dp(10), dp(14), dp(11));
        corner = dp(22);
        if (frostNoise == null) frostNoise = makeFrostNoise();
        noisePaint.setAlpha(13);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("PERFORMANCE", 9.5f, accent);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lockIcon = text("🔒", 11f, 0xFFFFD27F, false);
        lockIcon.setPadding(dp(4), 0, dp(6), 0);
        lockIcon.setContentDescription("Overlay locked");
        lockIcon.setVisibility(View.GONE);
        header.addView(lockIcon);

        battery = text("--%", 10.5f, TEXT, true);
        battery.setPadding(dp(7), dp(2), dp(7), dp(2));
        battery.setBackground(Ui.rounded(0x22FFFFFF, dp(9)));
        header.addView(battery);

        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cpuRow = metricRow();
        addMetricLabel(cpuRow, "CPU");
        cpuTemp = metricValue(Gravity.START);
        cpuClock = metricValue(Gravity.START);
        cpuUsage = metricValue(Gravity.END);
        cpuRow.addView(cpuTemp, weight(0.9f));
        cpuRow.addView(cpuClock, weight(1.05f));
        cpuRow.addView(cpuUsage, weight(0.85f));
        addView(cpuRow, rowParams(8));

        gpuRow = metricRow();
        addMetricLabel(gpuRow, "GPU");
        gpuTemp = metricValue(Gravity.START);
        gpuClock = metricValue(Gravity.START);
        gpuUsage = metricValue(Gravity.END);
        gpuRow.addView(gpuTemp, weight(0.9f));
        gpuRow.addView(gpuClock, weight(1.05f));
        gpuRow.addView(gpuUsage, weight(0.85f));
        addView(gpuRow, rowParams(3));

        ramRow = metricRow();
        addMetricLabel(ramRow, "RAM");
        ramUsage = metricValue(Gravity.START);
        ramAmount = metricValue(Gravity.END);
        ramRow.addView(ramUsage, weight(1.0f));
        ramRow.addView(ramAmount, weight(1.4f));
        addView(ramRow, rowParams(3));

        battRow = metricRow();
        addMetricLabel(battRow, "BATT");
        battTemp = metricValue(Gravity.START);
        battPower = metricValue(Gravity.END);
        battRow.addView(battTemp, weight(1.0f));
        battRow.addView(battPower, weight(1.4f));
        addView(battRow, rowParams(3));

        fpsBlock = new LinearLayout(c);
        fpsBlock.setOrientation(VERTICAL);
        fpsRow = metricRow();
        addMetricLabel(fpsRow, "FPS");
        fpsValue = text("--", 19f, TEXT, true);
        fpsValue.setGravity(Gravity.CENTER_VERTICAL);
        fpsRow.addView(fpsValue, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView fpsUnit = label("fps", 8.5f, MUTED);
        fpsUnit.setPadding(dp(5), 0, 0, 0);
        fpsRow.addView(fpsUnit);
        fpsBlock.addView(fpsRow);
        graph = new FpsGraphView(c);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        glp.topMargin = dp(4);
        fpsBlock.addView(graph, glp);
        addView(fpsBlock, rowParams(8));

        coresBox = new LinearLayout(c);
        coresBox.setOrientation(VERTICAL);
        TextView coresLabel = label("CORES", 8.5f, MUTED);
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
                        0, dp(18), 1f);
                if (col == 0) lp.rightMargin = dp(8);
                row.addView(cv, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(2);
            coresBox.addView(row, rlp);
        }
        addView(coresBox, rowParams(8));
        coresBox.setVisibility(View.GONE);

        editDoneBar = text("DONE", 10f, 0xFF061015, false);
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
    }

    void setLocked(boolean locked) {
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

    void applyPrefs(Prefs p) {
        setPanelAlpha(p.perfOverlayAlpha());
        cpuTemp.setVisibility(p.perfCpuTemp() ? VISIBLE : GONE);
        cpuClock.setVisibility(p.perfCpuClock() ? VISIBLE : GONE);
        cpuUsage.setVisibility(p.perfCpuUsage() ? VISIBLE : GONE);
        cpuRow.setVisibility(p.perfCpuTemp() || p.perfCpuClock() || p.perfCpuUsage()
                ? VISIBLE : GONE);

        gpuTemp.setVisibility(p.perfGpuTemp() ? VISIBLE : GONE);
        gpuClock.setVisibility(p.perfGpuClock() ? VISIBLE : GONE);
        gpuUsage.setVisibility(p.perfGpuUsage() ? VISIBLE : GONE);
        gpuRow.setVisibility(p.perfGpuTemp() || p.perfGpuClock() || p.perfGpuUsage()
                ? VISIBLE : GONE);

        ramRow.setVisibility(p.perfRam() ? VISIBLE : GONE);
        battRow.setVisibility(p.perfBattery() ? VISIBLE : GONE);
        fpsValue.setVisibility(p.perfFps() ? VISIBLE : GONE);
        fpsRow.setVisibility(p.perfFps() ? VISIBLE : GONE);
        graph.setVisibility(p.perfFpsGraph() ? VISIBLE : GONE);
        fpsBlock.setVisibility(p.perfFps() || p.perfFpsGraph() ? VISIBLE : GONE);

        battery.setVisibility(p.perfBattery() ? VISIBLE : GONE);
        coresBox.setVisibility(p.perfCores() ? VISIBLE : GONE);
        graph.setRefreshMs(p.perfRefreshMs());
        requestLayout();
    }

    void update(PerfStats s) {
        boolean cpuTempKnown = s.cpuTempC > 0f;
        setValue(cpuTemp, cpuTempKnown ? Math.round(s.cpuTempC) + "\u00b0" : "\u2014",
                cpuTempKnown ? heat((s.cpuTempC - 32f) / 55f) : 0x55FFFFFF);
        boolean cpuClockKnown = s.cpuHz > 0;
        setValue(cpuClock, cpuClockKnown ? fmtHz(s.cpuHz) : "\u2014",
                cpuClockKnown ? heat(s.cpuMaxHz > 0
                        ? s.cpuHz / (float) s.cpuMaxHz : 0f) : 0x55FFFFFF);
        setValue(cpuUsage, s.cpuUsageKnown ? s.cpuUsage + "%" : "\u2014",
                s.cpuUsageKnown ? heat(s.cpuUsage / 100f) : 0x55FFFFFF);

        boolean gpuTempKnown = s.gpuTempC > 0f;
        setValue(gpuTemp, gpuTempKnown ? Math.round(s.gpuTempC) + "\u00b0" : "\u2014",
                gpuTempKnown ? heat((s.gpuTempC - 32f) / 55f) : 0x55FFFFFF);
        boolean gpuClockKnown = s.gpuHz > 0;
        setValue(gpuClock, gpuClockKnown ? fmtHz(s.gpuHz) : "\u2014",
                gpuClockKnown ? heat(s.gpuMaxHz > 0
                        ? s.gpuHz / (float) s.gpuMaxHz : 0f) : 0x55FFFFFF);
        setValue(gpuUsage, s.gpuUsageKnown ? s.gpuUsage + "%" : "\u2014",
                s.gpuUsageKnown ? heat(s.gpuUsage / 100f) : 0x55FFFFFF);

        if (s.memTotalKb > 0) {
            int totalGb = Math.max(1, Math.round(s.memTotalKb / 1_000_000f));
            float usedGb = Math.max(0f, (s.memTotalKb - s.memAvailKb) / 1_000_000f);
            setValue(ramUsage, s.memPercent + "%", heat(s.memPercent / 100f));
            setValue(ramAmount, String.format(Locale.US, "%.1f/%dG", usedGb, totalGb),
                    0xB3FFFFFF);
        } else {
            setValue(ramUsage, "\u2014", 0x55FFFFFF);
            setValue(ramAmount, "\u2014", 0x55FFFFFF);
        }

        boolean battTempKnown = s.batteryTempC > 0f;
        setValue(battTemp, battTempKnown ? Math.round(s.batteryTempC) + "\u00b0" : "\u2014",
                battTempKnown ? heat((s.batteryTempC - 30f) / 40f) : 0x55FFFFFF);
        boolean powerKnown = s.powerW > 0f;
        setValue(battPower, powerKnown
                ? String.format(Locale.US, "%.1fW", s.powerW) : "\u2014",
                powerKnown ? heat(s.powerW / 25f) : 0x55FFFFFF);

        if (s.battery >= 0) {
            battery.setText(s.battery + "%");
            battery.setTextColor(batteryColor(s.battery));
        } else {
            battery.setText("\u2014");
            battery.setTextColor(0x55FFFFFF);
        }

        if (s.fpsValid) {
            fpsValue.setText(String.valueOf(s.fps));
            fpsValue.setTextColor(fpsColor(s.fps));
            graph.push(s.fps);
        } else {
            fpsValue.setText("\u2014");
            fpsValue.setTextColor(0x55FFFFFF);
        }

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

    private void addMetricLabel(LinearLayout row, String text) {
        TextView v = label(text, 8.5f, MUTED);
        v.setLetterSpacing(0.12f);
        row.addView(v, new LinearLayout.LayoutParams(dp(32),
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView metricValue(int gravity) {
        TextView v = text("--", 10.5f, TEXT, true);
        v.setGravity(gravity | Gravity.CENTER_VERTICAL);
        return v;
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
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        rect.set(0, 0, w, h);
        // Frosted glass only: a translucent body with a soft sheen, a light rim
        // and film grain. Nothing is captured or blurred at run time, so the
        // panel costs the same as any other view.
        int alpha = Math.round(240f * panelAlphaPct / 100f);
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
            textSize = 8.5f * c.getResources().getDisplayMetrics().scaledDensity;
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
            p.setColor(online ? 0x88FFFFFF : 0x44FFFFFF);
            canvas.drawText("C" + index, 0, baseline, p);

            float left = density * 18;
            float right = w - density * 58;
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
            p.setColor(online ? heat(usage / 100f) : 0x44FFFFFF);
            canvas.drawText(!online ? "off" : usageKnown
                            ? Math.round(usage) + "%" : "\u2014",
                    w - density * 30, baseline, p);
            int clockColor = !online ? 0x44FFFFFF
                    : heat(maxHz > 0 ? hz / (float) maxHz : 0f);
            p.setColor(clockColor);
            canvas.drawText(online ? fmtHz(hz) : "\u2014", w, baseline, p);
        }
    }
}
