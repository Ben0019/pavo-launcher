package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Tiny translucent in-game handle for the Touch Mapper editor. */
final class TouchMapFabView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean editing;

    TouchMapFabView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(true);
        setContentDescription("Touch Mapper editor");
        bg.setColor(0x770B0E14);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(context, 1.4f));
        ring.setColor(0xCC8BE9FF);
        glyph.setColor(0xF2FFFFFF);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Ui.dp(context, 1.8f));
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setEditing(boolean editing) {
        this.editing = editing;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - Ui.dp(getContext(), 1.5f);
        bg.setColor(editing ? 0xCC123A4A : 0x770B0E14);
        canvas.drawCircle(cx, cy, r, bg);
        ring.setColor(editing ? 0xFFFFFFFF : 0xCC8BE9FF);
        canvas.drawCircle(cx, cy, r, ring);

        float arm = r * 0.46f;
        canvas.drawLine(cx - arm, cy, cx + arm, cy, glyph);
        canvas.drawLine(cx, cy - arm, cx, cy + arm, glyph);
        canvas.drawCircle(cx, cy, r * 0.23f, glyph);
        glyph.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, Ui.dp(getContext(), 1.7f), glyph);
        glyph.setStyle(Paint.Style.STROKE);
    }
}
