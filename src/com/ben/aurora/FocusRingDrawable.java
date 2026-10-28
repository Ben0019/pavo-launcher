package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Drawable form of the shared focus ring, for ViewOverlay use. */
final class FocusRingDrawable extends Drawable {
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float strokeWidth;
    private final float glowWidth;
    private final float inset;
    private float radius;
    private int accent;
    private int plateColor;

    FocusRingDrawable(Context c, int accent) {
        this.accent = accent;
        strokeWidth = Ui.dpf(c, 1.5f);
        glowWidth = ColorTuning.radius(Ui.dpf(c, 4f));
        inset = Ui.dpf(c, 2.5f);
        platePaint.setStyle(Paint.Style.FILL);
        fillPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(glowWidth);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        updateColours();
    }

    void setAccent(int color) {
        accent = color;
        updateColours();
        invalidateSelf();
    }

    void setRingRadius(float value) {
        radius = Math.max(0f, value);
        invalidateSelf();
    }

    void setPlateColor(int color) {
        plateColor = color;
        platePaint.setColor(color);
        invalidateSelf();
    }

    private void updateColours() {
        fillPaint.setColor((accent & 0x00FFFFFF) | 0x14000000);
        glowPaint.setColor(ColorTuning.withAlpha(accent, ColorTuning.glowAlpha(0x38)));
        strokePaint.setColor((accent & 0x00FFFFFF) | 0xE6000000);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float in = Math.max(inset, glowWidth / 2f);
        rect.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        if ((plateColor >>> 24) != 0) {
            canvas.drawRoundRect(rect, r, r, platePaint);
        }
        canvas.drawRoundRect(rect, r, r, fillPaint);
        canvas.drawRoundRect(rect, r, r, glowPaint);
        canvas.drawRoundRect(rect, r, r, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        // The ring's own alpha layers are intentionally fixed.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    static float radiusFor(android.view.View target, Context c) {
        return FocusRingView.radiusFor(target, c);
    }
}
