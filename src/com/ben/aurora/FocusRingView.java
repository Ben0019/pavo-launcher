package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Shared controller-focus ring used by popup menus and cards. */
final class FocusRingView extends View {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float strokeWidth;
    private final float glowWidth;
    private final float inset;
    private float radius;
    private int accent;

    FocusRingView(Context c, int accent) {
        super(c);
        this.accent = accent;
        strokeWidth = Ui.dpf(c, 1.5f);
        glowWidth = ColorTuning.radius(Ui.dpf(c, 4f));
        inset = Ui.dpf(c, 2.5f);
        fillPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(glowWidth);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        updateColours();
    }

    void setAccent(int color) {
        accent = color;
        updateColours();
        invalidate();
    }

    void setRingRadius(float value) {
        radius = Math.max(0f, value);
        invalidate();
    }

    private void updateColours() {
        fillPaint.setColor((accent & 0x00FFFFFF) | 0x14000000);
        glowPaint.setColor(ColorTuning.withAlpha(accent, ColorTuning.glowAlpha(0x38)));
        strokePaint.setColor((accent & 0x00FFFFFF) | 0xE6000000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float in = Math.max(inset, glowWidth / 2f);
        rect.set(in, in, w - in, h - in);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        canvas.drawRoundRect(rect, r, r, fillPaint);
        canvas.drawRoundRect(rect, r, r, glowPaint);
        canvas.drawRoundRect(rect, r, r, strokePaint);
    }

    /**
     * Match the ring to the target's own corner radius when it exposes one,
     * otherwise use the launcher's regular compact-card radius.
     */
    static float radiusFor(View target, Context c) {
        if (target == null) return Ui.dpf(c, 12f);
        float radius = Ui.dpf(c, 12f);
        Drawable bg = target.getBackground();
        if (bg instanceof GradientDrawable) {
            float own = ((GradientDrawable) bg).getCornerRadius();
            if (own > 0f) radius = own;
        }
        float max = Math.max(0f, target.getHeight() / 2f);
        return max > 0f ? Math.min(radius, max) : radius;
    }
}
