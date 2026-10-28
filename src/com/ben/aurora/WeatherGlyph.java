package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small, self-drawn weather icon so the widget never depends on emoji fonts. */
final class WeatherGlyph extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int code = -1;

    WeatherGlyph(Context c) {
        super(c);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setCode(int code) {
        if (this.code == code && getVisibility() == VISIBLE) return;
        this.code = code;
        setContentDescription(Loc.t(getContext(), Weather.describe(code)));
        invalidate();
    }

    private static int cloudColor() {
        return 0xFFE7EDF5;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (code < 0) return;
        float w = getWidth();
        float h = getHeight();
        if (w <= 1 || h <= 1) return;
        float unit = Math.min(w, h);

        if (code == 0) {
            drawSun(canvas, w * 0.5f, h * 0.48f, unit * 0.24f);
            return;
        }
        if (code == 1 || code == 2) {
            drawSun(canvas, w * 0.34f, h * 0.34f, unit * 0.19f);
            drawCloud(canvas, w * 0.58f, h * 0.62f, unit * 0.72f);
            return;
        }

        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || (code >= 85 && code <= 86);
        boolean storm = code >= 95;
        boolean fog = code == 45 || code == 48;

        drawCloud(canvas, w * 0.5f, h * 0.40f, unit * 0.82f);

        paint.setStrokeWidth(Math.max(1.2f, unit * 0.075f));
        if (rain) {
            paint.setColor(0xFF8FCBFF);
            for (int i = 0; i < 3; i++) {
                float x = w * (0.31f + i * 0.19f);
                canvas.drawLine(x, h * 0.68f, x - w * 0.045f, h * 0.86f, paint);
            }
        } else if (snow) {
            paint.setColor(0xFFF4F9FF);
            for (int i = 0; i < 3; i++) {
                float x = w * (0.31f + i * 0.19f);
                float y = h * 0.78f;
                float r = unit * 0.055f;
                canvas.drawCircle(x, y, r, paint);
            }
        } else if (storm) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFD166);
            path.reset();
            path.moveTo(w * 0.55f, h * 0.58f);
            path.lineTo(w * 0.38f, h * 0.82f);
            path.lineTo(w * 0.52f, h * 0.82f);
            path.lineTo(w * 0.45f, h * 0.98f);
            path.lineTo(w * 0.68f, h * 0.70f);
            path.lineTo(w * 0.53f, h * 0.70f);
            path.close();
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        } else if (fog) {
            paint.setColor(0xFFB9C5D4);
            canvas.drawLine(w * 0.22f, h * 0.72f, w * 0.78f, h * 0.72f, paint);
            canvas.drawLine(w * 0.30f, h * 0.87f, w * 0.70f, h * 0.87f, paint);
        }
    }

    private void drawSun(Canvas canvas, float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFD166);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, r * 0.24f));
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4.0;
            float x1 = cx + (float) Math.cos(a) * r * 1.45f;
            float y1 = cy + (float) Math.sin(a) * r * 1.45f;
            float x2 = cx + (float) Math.cos(a) * r * 1.95f;
            float y2 = cy + (float) Math.sin(a) * r * 1.95f;
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }

    /** Draws a puffy cloud centred on cx/cy; scale is the overall width. */
    private void drawCloud(Canvas canvas, float cx, float cy, float scale) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(cloudColor());

        float w = scale;
        float h = scale * 0.56f;
        float left = cx - w * 0.5f;
        float top = cy - h * 0.5f;
        float baseTop = top + h * 0.42f;
        float radius = h * 0.30f;

        canvas.drawCircle(left + w * 0.30f, baseTop, radius, paint);
        canvas.drawCircle(left + w * 0.52f, baseTop - radius * 0.52f, radius * 1.12f, paint);
        canvas.drawCircle(left + w * 0.74f, baseTop + radius * 0.06f, radius * 0.88f, paint);
        canvas.drawRoundRect(new android.graphics.RectF(
                        left + w * 0.13f, baseTop - radius * 0.05f,
                        left + w * 0.88f, top + h * 0.94f),
                h * 0.24f, h * 0.24f, paint);
    }
}
