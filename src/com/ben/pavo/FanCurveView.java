package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draggable temperature-to-duty fan curve. */
final class FanCurveView extends View {
    interface Listener {
        void onCurveChanged(String curve);
    }

    private static final int ACCENT = 0xFF8BE9FF;
    private static final int MUTED = 0x88FFFFFF;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curveFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liveDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plot = new RectF();
    private final Path path = new Path();
    private final Path fill = new Path();

    private final List<int[]> points = new ArrayList<>();
    private String curve = FanCurve.DEFAULT;
    private Listener listener;
    private int selected = 1;
    private float liveTemp = 40f;
    private int liveDuty = 30;
    private int liveRpm;
    private boolean editable = true;
    private boolean focused;
    private long lastHaptic;

    FanCurveView(Context c) {
        super(c);
        grid.setColor(0x22FFFFFF);
        axis.setStyle(Paint.Style.STROKE);
        axis.setStrokeWidth(Ui.dpf(c, 1.2f));
        axis.setColor(0x66FFFFFF);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(Ui.dpf(c, 2.5f));
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setColor(ACCENT);
        curveFill.setColor(0x228BE9FF);
        pointPaint.setStyle(Paint.Style.STROKE);
        pointPaint.setStrokeWidth(Ui.dpf(c, 2f));
        pointPaint.setColor(ACCENT);
        pointFill.setColor(0xFF0B0E14);
        textPaint.setColor(MUTED);
        textPaint.setTextSize(Ui.dpf(c, 7.2f));
        livePaint.setStyle(Paint.Style.STROKE);
        livePaint.setStrokeWidth(Ui.dpf(c, 1f));
        livePaint.setColor(0x66FFFFFF);
        livePaint.setPathEffect(new DashPathEffect(new float[]{
                Ui.dpf(c, 3), Ui.dpf(c, 4)}, 0));
        liveDot.setColor(0xFFFFFFFF);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(Ui.dpf(c, 2f));
        focusPaint.setColor(ACCENT);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Fan curve. Right stick adjusts duty; D-pad or left stick selects points.");
        setCurve(curve);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setEditable(boolean value) {
        editable = value;
        invalidate();
    }

    void setCurve(String value) {
        curve = FanCurve.ensureTerminal(value == null ? FanCurve.DEFAULT : value);
        points.clear();
        points.addAll(FanCurve.points(curve));
        selected = Math.max(0, Math.min(selected, points.size() - 1));
        invalidate();
    }

    String curve() {
        return FanCurve.serialize(points);
    }

    void setLive(float temp, int duty) {
        setLive(temp, duty, 0);
    }

    void setLive(float temp, int duty, int rpm) {
        liveTemp = temp;
        liveDuty = duty;
        liveRpm = Math.max(0, rpm);
        invalidate();
    }

    private void layoutPlot() {
        float left = Ui.dpf(getContext(), 36);
        float top = Ui.dpf(getContext(), 12);
        float right = getWidth() - Ui.dpf(getContext(), 18);
        float bottom = getHeight() - Ui.dpf(getContext(), 30);
        plot.set(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
    }

    private float xFor(int temp) {
        return plot.left + (Math.max(30, Math.min(100, temp)) - 30) / 70f * plot.width();
    }

    private float yFor(int duty) {
        return plot.bottom - Math.max(0, Math.min(100, duty)) / 100f * plot.height();
    }

    private int tempFor(float x) {
        return Math.round(30 + (x - plot.left) / Math.max(1, plot.width()) * 70);
    }

    private int dutyFor(float y) {
        return Math.round((plot.bottom - y) / Math.max(1, plot.height()) * 100);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        layoutPlot();
        c.drawRect(plot, grid);
        // Light major grid at 10° / 10% intervals.
        for (int t = 40; t < 100; t += 10) {
            float x = xFor(t);
            c.drawLine(x, plot.top, x, plot.bottom, grid);
        }
        for (int p = 10; p < 100; p += 10) {
            float y = yFor(p);
            c.drawLine(plot.left, y, plot.right, y, grid);
        }
        c.drawRect(plot, axis);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float tick = Ui.dpf(getContext(), 3f);
        float xLabelY = plot.bottom + Ui.dpf(getContext(), 12);
        // Temperature labels every 5 °C.
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int t = 30; t <= 100; t += 5) {
            float x = xFor(t);
            c.drawLine(x, plot.bottom, x, plot.bottom + tick, axis);
            c.drawText(t + "°", x, xLabelY, textPaint);
        }
        // Duty labels every 5 %.
        textPaint.setTextAlign(Paint.Align.RIGHT);
        float yLabelX = plot.left - Ui.dpf(getContext(), 5);
        for (int p = 0; p <= 100; p += 5) {
            float y = yFor(p);
            c.drawLine(plot.left - tick, y, plot.left, y, axis);
            c.drawText(p + "%", yLabelX, y - (fm.ascent + fm.descent) / 2f, textPaint);
        }

        if (points.isEmpty()) return;
        path.reset();
        fill.reset();
        int n = points.size();
        for (int i = 0; i < n; i++) {
            int[] p = points.get(i);
            float x = xFor(p[0]);
            float y = yFor(p[1]);
            if (i == 0) {
                path.moveTo(x, y);
                fill.moveTo(x, plot.bottom);
                fill.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(xFor(points.get(n - 1)[0]), plot.bottom);
        fill.close();
        float average = 0f;
        for (int[] p : points) average += HeatColor.severity(p[1], p[0]);
        average /= Math.max(1, points.size());
        curveFill.setColor(HeatColor.withAlpha(HeatColor.ramp(average), 0x22));
        c.drawPath(fill, curveFill);

        // Draw the line in segments so its colour follows duty and temperature.
        for (int i = 1; i < n; i++) {
            int[] a = points.get(i - 1);
            int[] b = points.get(i);
            float severity = (HeatColor.severity(a[1], a[0])
                    + HeatColor.severity(b[1], b[0])) / 2f;
            curvePaint.setColor(HeatColor.ramp(severity));
            c.drawLine(xFor(a[0]), yFor(a[1]), xFor(b[0]), yFor(b[1]), curvePaint);
        }

        for (int i = 0; i < n; i++) {
            int[] p = points.get(i);
            float x = xFor(p[0]);
            float y = yFor(p[1]);
            float r = Ui.dpf(getContext(), i == selected ? 5.2f : 4f);
            int pointColor = HeatColor.ramp(HeatColor.severity(p[1], p[0]));
            pointPaint.setColor(pointColor);
            pointFill.setColor(i == selected ? pointColor : 0xFF0B0E14);
            c.drawCircle(x, y, r, pointFill);
            c.drawCircle(x, y, r, pointPaint);
            if (focused && i == selected) {
                c.drawCircle(x, y, r + Ui.dpf(getContext(), 4f), focusPaint);
            }
        }

        float lx = xFor(Math.round(liveTemp));
        float ly = yFor(FanCurve.dutyAt(curve(), liveTemp));
        int liveColor = HeatColor.ramp(HeatColor.fanSeverity(liveDuty, liveRpm, liveTemp));
        livePaint.setColor(HeatColor.withAlpha(liveColor, 0x99));
        liveDot.setColor(liveColor);
        c.drawLine(lx, plot.top, lx, plot.bottom, livePaint);
        c.drawCircle(lx, ly, Ui.dpf(getContext(), 3f), liveDot);
    }

    boolean moveSelection(int delta) {
        if (points.isEmpty() || delta == 0) return false;
        int next = Math.max(0, Math.min(points.size() - 1, selected + delta));
        if (next == selected) return false;
        selected = next;
        invalidate();
        Haptics.tick(this);
        return true;
    }

    boolean adjustSelectedDuty(int delta) {
        if (points.isEmpty() || delta == 0 || selected < 0
                || selected >= points.size()) return false;
        int[] p = points.get(selected);
        int duty = FanCurve.clamp(p[1] + delta, 0, 100);
        if (duty == p[1]) return false;
        points.set(selected, new int[]{p[0], duty});
        curve = FanCurve.serialize(points);
        invalidate();
        if (listener != null) listener.onCurveChanged(curve);
        Haptics.tick(this);
        return true;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        focused = gainFocus;
        invalidate();
    }

    private static int snap5(int value) {
        return Math.round(value / 5f) * 5;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editable || points.isEmpty()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                layoutPlot();
                int best = -1;
                float bestD = Float.MAX_VALUE;
                for (int i = 0; i < points.size(); i++) {
                    int[] p = points.get(i);
                    float dx = event.getX() - xFor(p[0]);
                    float dy = event.getY() - yFor(p[1]);
                    float d = dx * dx + dy * dy;
                    if (d < bestD) {
                        bestD = d;
                        best = i;
                    }
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (best < 0 || bestD > Ui.dpf(getContext(), 32) * Ui.dpf(getContext(), 32)) {
                        return false;
                    }
                    selected = best;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (selected < 0) return true;
                int minT = selected == 0 ? 30 : points.get(selected - 1)[0] + 5;
                int maxT = selected == points.size() - 1 ? 100 : points.get(selected + 1)[0] - 5;
                if (maxT < minT) maxT = minT;
                int t = FanCurve.clamp(snap5(tempFor(event.getX())), minT, maxT);
                int d = FanCurve.clamp(snap5(dutyFor(event.getY())), 0, 100);
                points.set(selected, new int[]{t, d});
                curve = FanCurve.serialize(points);
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastHaptic >= 45) {
                    lastHaptic = now;
                    Haptics.tick(this);
                }
                invalidate();
                if (listener != null) listener.onCurveChanged(curve);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                Haptics.click(this);
                return true;
        }
        return super.onTouchEvent(event);
    }
}
