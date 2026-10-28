package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Live benchmark graph, using the same line/grid/legend language as the
 * Performance Logs app. FPS, CPU/GPU temperature and CPU/GPU load are updated
 * from the running benchmark and the chart automatically follows the newest
 * sample.
 */
final class BenchmarkStageView extends View {
    static final int MODE_CPU = 0;
    static final int MODE_GPU = 1;
    static final int MODE_BOTH = 2;

    private static final int MAX_SAMPLES = 240;
    private static final int SAMPLE_STEP_DP = 16;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fillPath = new Path();
    private final RectF box = new RectF();
    private final float density;

    private final List<Integer> fpsHistory = new ArrayList<>();
    private final List<Integer> cpuTempHistory = new ArrayList<>();
    private final List<Integer> gpuTempHistory = new ArrayList<>();
    private final List<Integer> cpuUseHistory = new ArrayList<>();
    private final List<Integer> gpuUseHistory = new ArrayList<>();

    private boolean running;
    private int mode = MODE_BOTH;
    private float progress;
    private float latestFps;
    private float cpuTemp;
    private float gpuTemp;
    private int cpuUsage;
    private int gpuUsage;
    private float scrollX;
    private long headStartMs;
    private boolean headReady;
    private boolean historyMode;
    private long sampleIntervalMs = 100L;
    private long lastSampleMs;

    BenchmarkStageView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        grid.setStrokeWidth(Math.max(1f, density * 0.7f));
        line.setStrokeWidth(Math.max(1.4f, density * 1.6f));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        legend.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        legend.setTextSize(dp(7.8f));
        axis.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        axis.setTextSize(dp(6.4f));
    }

    void configure(int mode, int threads) {
        this.mode = mode;
        invalidate();
    }

    void start() {
        running = true;
        progress = 0f;
        latestFps = 0f;
        scrollX = 0f;
        headStartMs = 0L;
        headReady = false;
        historyMode = false;
        lastSampleMs = 0L;
        fpsHistory.clear();
        cpuTempHistory.clear();
        gpuTempHistory.clear();
        cpuUseHistory.clear();
        gpuUseHistory.clear();
        invalidate();
    }

    void stop() {
        running = false;
        invalidate();
    }

    /** Fresh live-monitor view for the device when no test is running. */
    void startIdle() {
        running = false;
        historyMode = false;
        progress = 0f;
        scrollX = 0f;
        latestFps = 0f;
        headStartMs = 0L;
        headReady = false;
        lastSampleMs = 0L;
        sampleIntervalMs = 1000L;
        fpsHistory.clear();
        cpuTempHistory.clear();
        gpuTempHistory.clear();
        cpuUseHistory.clear();
        gpuUseHistory.clear();
        invalidate();
    }

    void setIdleSample(float cpuTemp, float gpuTemp,
                       int cpuUsage, int gpuUsage, float gpuFps) {
        this.cpuTemp = cpuTemp;
        this.gpuTemp = gpuTemp;
        this.cpuUsage = Math.max(0, Math.min(100, cpuUsage));
        this.gpuUsage = Math.max(0, Math.min(100, gpuUsage));
        if (gpuFps > 0f) latestFps = gpuFps;
        long now = SystemClock.uptimeMillis();
        if (lastSampleMs == 0L || now - lastSampleMs >= sampleIntervalMs) {
            append(fpsHistory, Math.round(gpuFps));
            append(cpuTempHistory, Math.round(cpuTemp));
            append(gpuTempHistory, Math.round(gpuTemp));
            append(cpuUseHistory, this.cpuUsage);
            append(gpuUseHistory, this.gpuUsage);
            lastSampleMs = now;
            headStartMs = now;
            headReady = true;
        }
        invalidate();
    }

    void setSampleInterval(long millis) {
        sampleIntervalMs = Math.max(100L, millis);
    }

    void setState(float progress, float cpuTemp, float gpuTemp,
                  int cpuUsage, int gpuUsage, float gpuFps) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        if (gpuFps > 0f) latestFps = gpuFps;
        boolean hasData = cpuTemp > 0f || gpuTemp > 0f
                || cpuUsage > 0 || gpuUsage > 0 || gpuFps > 0f;
        long now = SystemClock.uptimeMillis();
        boolean due = lastSampleMs == 0L
                || now - lastSampleMs >= sampleIntervalMs
                || this.progress >= 1f;
        if (this.progress > 0f && hasData && due) {
            append(fpsHistory, Math.round(gpuFps));
            append(cpuTempHistory, Math.round(cpuTemp));
            append(gpuTempHistory, Math.round(gpuTemp));
            append(cpuUseHistory, cpuUsage);
            append(gpuUseHistory, gpuUsage);
            lastSampleMs = now;
            headStartMs = now;
            headReady = true;
        }
        invalidate();
    }

    float gpuFps() {
        return latestFps;
    }

    private void append(List<Integer> list, int value) {
        list.add(Math.max(0, value));
        while (list.size() > MAX_SAMPLES) list.remove(0);
    }

    private float plotLeft() {
        return dp(30);
    }

    private float plotRight() {
        return Math.max(plotLeft() + 1, getWidth() - dp(32));
    }

    private float plotTop() {
        return dp(29);
    }

    private float plotBottom() {
        return Math.max(plotTop() + 1, getHeight() - dp(6));
    }

    private float contentWidth() {
        int samples = sampleCount();
        return Math.max(plotRight() - plotLeft(),
                Math.max(1, samples - 1) * dp(SAMPLE_STEP_DP));
    }

    private int sampleCount() {
        return Math.max(Math.max(fpsHistory.size(), cpuTempHistory.size()),
                Math.max(gpuTempHistory.size(), Math.max(cpuUseHistory.size(),
                        gpuUseHistory.size())));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 2 || h <= 2) return;
        float radius = dp(18);
        box.set(0, 0, w, h);
        bg.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFF0B1218, 0xF20A1016, 0xFF0B1218},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, radius, radius, bg);
        bg.setShader(null);

        int[] fps = toArray(fpsHistory);
        int[] cpuTemp = toArray(cpuTempHistory);
        int[] gpuTemp = toArray(gpuTempHistory);
        int[] cpuUse = toArray(cpuUseHistory);
        int[] gpuUse = toArray(gpuUseHistory);
        int lastCpu = last(cpuTemp);
        int lastGpu = last(gpuTemp);
        int lastCpuUse = last(cpuUse);
        int lastGpuUse = last(gpuUse);
        int cpuColor = 0xFFFF8A65;
        int gpuColor = 0xFF64B5F6;
        int cpuUseColor = 0xFFFFC857;
        int gpuUseColor = 0xFF7CF7C4;

        canvas.drawText("CPU " + (lastCpu > 0 ? lastCpu + "\u00b0" : "--"),
                dp(8), dp(11), tint(legend, cpuColor));
        canvas.drawText("GPU " + (lastGpu > 0 ? lastGpu + "\u00b0" : "--"),
                dp(120), dp(11), tint(legend, gpuColor));
        canvas.drawText("CPU " + lastCpuUse + "%", dp(8), dp(21),
                tint(legend, cpuUseColor));
        canvas.drawText("GPU " + lastGpuUse + "%", dp(120), dp(21),
                tint(legend, gpuUseColor));

        float left = plotLeft();
        float right = plotRight();
        float top = plotTop();
        float bottom = plotBottom();
        float height = bottom - top;
        float visibleWidth = right - left;
        int samples = sampleCount();
        float content = historyMode ? visibleWidth
                : Math.max(visibleWidth, contentWidth());
        // Follow the newest sample while the test is live. Easing the scroll
        // instead of jumping by one sample keeps the lines gliding.
        long now = SystemClock.uptimeMillis();
        float targetScroll = Math.max(0f, content - visibleWidth);
        if (historyMode) {
            scrollX = targetScroll;
        } else if (Math.abs(targetScroll - scrollX) < 0.35f) {
            scrollX = targetScroll;
        } else {
            scrollX += (targetScroll - scrollX) * 0.24f;
        }
        float head = 1f;
        if (headReady) {
            float raw = Math.min(1f, (now - headStartMs) / 90f);
            head = 1f - (1f - raw) * (1f - raw) * (1f - raw);
        }
        float step = samples > 1 ? content / (samples - 1f) : visibleWidth;

        grid.setColor(0x1FFFFFFF);
        axis.setColor(0x99FFFFFF);
        for (int pct = 0; pct <= 100; pct += 10) {
            float y = bottom - pct / 100f * height;
            canvas.drawLine(left, y, right, y, grid);
            if (h >= dp(130) || pct % 50 == 0) {
                canvas.drawText(pct + "%", dp(2), y + dp(2.2f), axis);
            }
        }

        // A fixed temperature scale avoids rescaling jumps as the device
        // heats up; the graph stays visually stable for the whole test.
        int minTemp = 20;
        int maxTemp = 100;
        for (int v = minTemp; v <= maxTemp; v += 10) {
            float y = bottom - (v - minTemp) / (float) (maxTemp - minTemp) * height;
            canvas.drawText(v + "\u00b0", right + dp(4), y + dp(2.2f), axis);
        }

        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        int division = Math.max(1, (samples - 1) / 6);
        for (int i = division; i < samples; i += division) {
            float x = left + i * step - scrollX;
            if (x >= left && x <= right) canvas.drawLine(x, top, x, bottom, grid);
        }
        drawTemp(canvas, cpuTemp, cpuColor, minTemp, maxTemp,
                left, top, bottom, step, scrollX, head);
        drawTemp(canvas, gpuTemp, gpuColor, minTemp, maxTemp,
                left, top, bottom, step, scrollX, head);
        drawUsage(canvas, cpuUse, cpuUseColor, left, top, bottom, step, scrollX, head);
        drawUsage(canvas, gpuUse, gpuUseColor, left, top, bottom, step, scrollX, head);
        canvas.restore();

        // Progress rail below the live chart.
        line.setPathEffect(null);
        line.setStrokeWidth(dp(2.5f));
        line.setColor(0x33FFFFFF);
        canvas.drawLine(dp(18), h - dp(3), w - dp(18), h - dp(3), line);
        line.setColor(accent());
        canvas.drawLine(dp(18), h - dp(3), dp(18) + (w - dp(36)) * progress,
                h - dp(3), line);
        line.setStrokeWidth(Math.max(1.4f, density * 1.6f));

        if (running || (headReady && head < 0.999f)
                || Math.abs(targetScroll - scrollX) > 0.35f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawFps(Canvas c, int[] data, int color,
                         float left, float top, float bottom,
                         float step, float scroll, float head) {
        if (data.length < 2) return;
        int max = 180;
        path.reset();
        fillPath.reset();
        for (int i = 0; i < data.length; i++) {
            float x = left + i * step - scroll;
            float value = valueAt(data, i, head);
            float y = bottom - Math.min(value, max) / (float) max * (bottom - top);
            if (i == 0) {
                path.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(left + (data.length - 1) * step - scroll, bottom);
        fillPath.close();
        fill.setShader(new LinearGradient(0, top, 0, bottom,
                (color & 0x00FFFFFF) | 0x44000000,
                (color & 0x00FFFFFF) | 0x04000000, Shader.TileMode.CLAMP));
        c.drawPath(fillPath, fill);
        fill.setShader(null);
        line.setColor(color);
        c.drawPath(path, line);
    }

    private void drawTemp(Canvas c, int[] data, int color, int min, int max,
                          float left, float top, float bottom,
                          float step, float scroll, float head) {
        if (data.length < 2 || max <= min) return;
        path.reset();
        boolean started = false;
        for (int i = 0; i < data.length; i++) {
            int v = Math.round(valueAt(data, i, head));
            if (v <= 0) {
                started = false;
                continue;
            }
            float x = left + i * step - scroll;
            float y = bottom - (Math.max(min, Math.min(max, v)) - min)
                    / (float) (max - min) * (bottom - top);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        line.setColor(color);
        line.setStrokeWidth(Math.max(1.2f, density * 1.35f));
        c.drawPath(path, line);
        line.setStrokeWidth(Math.max(1.4f, density * 1.6f));
    }

    private void drawUsage(Canvas c, int[] data, int color,
                           float left, float top, float bottom,
                           float step, float scroll, float head) {
        if (data.length < 2) return;
        path.reset();
        for (int i = 0; i < data.length; i++) {
            float x = left + i * step - scroll;
            float value = valueAt(data, i, head);
            float y = bottom - Math.max(0, Math.min(100, value)) / 100f * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        line.setColor(color);
        line.setStrokeWidth(Math.max(1f, density * 1.1f));
        line.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{dp(3), dp(3)}, 0f));
        c.drawPath(path, line);
        line.setPathEffect(null);
        line.setStrokeWidth(Math.max(1.4f, density * 1.6f));
    }

    void setHistory(int[] fps, int[] cpuTemp, int[] gpuTemp,
                    int[] cpuUse, int[] gpuUse) {
        running = false;
        historyMode = true;
        progress = 1f;
        headReady = false;
        headStartMs = 0L;
        fpsHistory.clear();
        cpuTempHistory.clear();
        gpuTempHistory.clear();
        cpuUseHistory.clear();
        gpuUseHistory.clear();
        if (fps != null) for (int v : fps) fpsHistory.add(v);
        if (cpuTemp != null) for (int v : cpuTemp) cpuTempHistory.add(v);
        if (gpuTemp != null) for (int v : gpuTemp) gpuTempHistory.add(v);
        if (cpuUse != null) for (int v : cpuUse) cpuUseHistory.add(v);
        if (gpuUse != null) for (int v : gpuUse) gpuUseHistory.add(v);
        latestFps = last(toArray(fpsHistory));
        scrollX = 0f;
        invalidate();
    }

    int[] fpsSnapshot() {
        return toArray(fpsHistory);
    }

    int[] cpuTempSnapshot() {
        return toArray(cpuTempHistory);
    }

    int[] gpuTempSnapshot() {
        return toArray(gpuTempHistory);
    }

    int[] cpuUseSnapshot() {
        return toArray(cpuUseHistory);
    }

    int[] gpuUseSnapshot() {
        return toArray(gpuUseHistory);
    }

    private float valueAt(int[] data, int index, float head) {
        if (index < 0 || index >= data.length) return 0f;
        if (index < data.length - 1 || !headReady || head >= 1f) return data[index];
        int previous = data[index - 1];
        return previous + (data[index] - previous) * head;
    }

    private int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private int last(int[] values) {
        return values.length == 0 ? 0 : values[values.length - 1];
    }

    private Paint tint(Paint p, int color) {
        p.setColor(color);
        return p;
    }

    private int accent() {
        return ThemeColors.accent();
    }

    private float dp(float v) {
        return v * density;
    }
}
