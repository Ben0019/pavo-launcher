package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

/** Shared joystick base/knob artwork used by the editor and in-game guides. */
final class JoystickArt {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp1;
    private final float textSize;

    JoystickArt(Context c) {
        dp1 = Ui.dpf(c, 1.6f);
        textSize = Ui.dpf(c, 10);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp1);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(textSize);
    }

    void draw(Canvas canvas, float cx, float cy, float radius, int color,
              float alpha, String label, boolean selected) {
        if (radius <= 1f) return;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        int main = withAlpha(color, a);
        int soft = withAlpha(color, Math.round(a * 0.62f));
        int faint = withAlpha(color, Math.round(a * 0.28f));

        // A soft contact shadow gives the base some depth.
        fill.setColor(0x33000000);
        canvas.drawCircle(cx, cy + radius * 0.06f, radius, fill);

        // Joystick base and inner gate.
        ring.setColor(main);
        ring.setStrokeWidth(dp1 * 1.8f);
        canvas.drawCircle(cx, cy, radius, ring);
        ring.setColor(soft);
        ring.setStrokeWidth(dp1);
        canvas.drawCircle(cx, cy, radius * 0.78f, ring);
        if (selected) {
            ring.setColor(0xCCFFFFFF);
            ring.setStrokeWidth(dp1 * 1.3f);
            canvas.drawCircle(cx, cy, radius * 1.07f, ring);
        }

        // Four gate notches.
        ring.setColor(faint);
        ring.setStrokeWidth(dp1 * 1.4f);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * i / 2.0;
            float x1 = cx + (float) Math.cos(angle) * radius * 0.84f;
            float y1 = cy + (float) Math.sin(angle) * radius * 0.84f;
            float x2 = cx + (float) Math.cos(angle) * radius * 0.96f;
            float y2 = cy + (float) Math.sin(angle) * radius * 0.96f;
            canvas.drawLine(x1, y1, x2, y2, ring);
        }

        // Raised thumb cap with an offset highlight.
        float kr = radius * 0.34f;
        fill.setColor(withAlpha(color, Math.round(a * 0.42f)));
        canvas.drawCircle(cx, cy, kr, fill);
        ring.setColor(main);
        ring.setStrokeWidth(dp1 * 1.3f);
        canvas.drawCircle(cx, cy, kr, ring);
        fill.setColor(withAlpha(0xFFFFFFFF, Math.round(a * 0.22f)));
        canvas.drawCircle(cx - kr * 0.28f, cy - kr * 0.30f, kr * 0.42f, fill);

        if (label != null && !label.isEmpty()) {
            text.setColor(withAlpha(0xFFFFFFFF, a));
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(label, cx, cy + radius + dp1 * 9f
                    - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
