package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class BatteryGlyph extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();
    private int level = 100;
    private boolean charging;

    private final float density;

    BatteryGlyph(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    void setLevel(int level, boolean charging) {
        this.level = Math.max(0, Math.min(100, level));
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float capW = Math.max(1.5f, w * 0.055f);
        float bodyRight = w - capW - 1.5f;

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, h * 0.09f));
        paint.setColor(0xE6FFFFFF);
        canvas.drawRoundRect(new RectF(1, 1, bodyRight, h - 1), h * 0.24f, h * 0.24f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6FFFFFF);
        canvas.drawRoundRect(new RectF(bodyRight + 2, h * 0.30f, w - 0.5f, h * 0.70f),
                h * 0.12f, h * 0.12f, paint);

        float pad = Math.max(1.5f, h * 0.20f);
        float maxFill = bodyRight - 1 - pad * 2;
        float fill = Math.max(0, maxFill * level / 100f);
        if (fill > 0) {
            int fillColor = ColorTuning.colour(fillColour());
            RectF fillRect = new RectF(1 + pad, 1 + pad, 1 + pad + fill, h - 1 - pad);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f * density * ColorTuning.glowScale());
            paint.setColor(ColorTuning.withAlpha(fillColor, ColorTuning.glowAlpha(0x55)));
            canvas.drawRoundRect(fillRect, h * 0.12f, h * 0.12f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            canvas.drawRoundRect(fillRect, h * 0.12f, h * 0.12f, paint);
        }

        if (charging) {
            float bh = Math.max(2f, h - pad * 2);
            float bx = 1 + pad + maxFill * 0.5f;
            float by = h * 0.5f;
            bolt.reset();
            bolt.moveTo(bx + bh * 0.13f, 1 + pad);
            bolt.lineTo(bx - bh * 0.22f, by + bh * 0.08f);
            bolt.lineTo(bx + bh * 0.01f, by + bh * 0.08f);
            bolt.lineTo(bx - bh * 0.11f, h - 1 - pad);
            bolt.lineTo(bx + bh * 0.27f, by - bh * 0.10f);
            bolt.lineTo(bx + bh * 0.04f, by - bh * 0.10f);
            bolt.close();

            canvas.save();
            canvas.clipRect(1 + pad, 1 + pad, bodyRight - pad, h - 1 - pad);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, h * 0.12f));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xD90B0E14);
            canvas.drawPath(bolt, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFFFFFF);
            canvas.drawPath(bolt, paint);
            canvas.restore();
        }
    }

    private int fillColour() {
        if (level <= 15) return 0xFFFF3D3D;
        if (level <= 40) return 0xFFFFC107;
        return 0xFF00E6A8;
    }
}
