package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** Compact HSV wheel: angle picks the hue, distance from the centre picks saturation. */
final class ColorWheel extends View {

    interface Listener {
        void onColorChanged(int color);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] hueColours = new int[361];

    private float hue = 0f;
    private float saturation = 1f;
    private float value = 1f;
    private int cx;
    private int cy;
    private int radius;
    private Listener listener;

    ColorWheel(Context c) {
        super(c);
        for (int i = 0; i <= 360; i++) {
            hueColours[i] = Color.HSVToColor(new float[]{i, 1f, 1f});
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Ui.dpf(c, 1.5f));
        rim.setColor(0x66FFFFFF);
        marker.setStyle(Paint.Style.FILL);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setColour(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        invalidate();
    }

    void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    float value() {
        return value;
    }

    int getColour() {
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    private void ensureMetrics() {
        if (radius > 0) return;
        cx = getWidth() / 2;
        cy = getHeight() / 2;
        radius = Math.max(1, Math.min(getWidth(), getHeight()) / 2 - Ui.dp(getContext(), 3));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        radius = 0;
        ensureMetrics();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int def = Ui.dp(getContext(), 240);
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = def;
        if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) h = def;
        int size = Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ensureMetrics();
        if (radius <= 0) return;

        paint.setShader(new SweepGradient(cx, cy, hueColours, null));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(new RadialGradient(cx, cy, radius,
                0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        if (value < 1f) {
            paint.setColor(Color.argb(Math.round((1f - value) * 255f), 0, 0, 0));
            canvas.drawCircle(cx, cy, radius, paint);
        }

        canvas.drawCircle(cx, cy, radius, rim);

        double angle = Math.toRadians(hue);
        float mx = cx + (float) Math.cos(angle) * radius * saturation;
        float my = cy + (float) Math.sin(angle) * radius * saturation;
        marker.setStyle(Paint.Style.FILL);
        marker.setColor(getColour());
        canvas.drawCircle(mx, my, Ui.dpf(getContext(), 9), marker);
        marker.setStyle(Paint.Style.STROKE);
        marker.setStrokeWidth(Ui.dpf(getContext(), 2.2f));
        marker.setColor(0xFFFFFFFF);
        canvas.drawCircle(mx, my, Ui.dpf(getContext(), 9), marker);
        marker.setStrokeWidth(Ui.dpf(getContext(), 0.8f));
        marker.setColor(0x66000000);
        canvas.drawCircle(mx, my, Ui.dpf(getContext(), 11), marker);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        ensureMetrics();
        if (radius <= 0) return false;
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN
                && e.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return true;
        }
        float dx = e.getX() - cx;
        float dy = e.getY() - cy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 1f) {
            saturation = 0f;
        } else {
            hue = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360.0) % 360.0);
            saturation = Math.max(0f, Math.min(1f, dist / radius));
        }
        invalidate();
        if (listener != null) listener.onColorChanged(getColour());
        return true;
    }
}
