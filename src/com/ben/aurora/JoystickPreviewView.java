package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * A top-down joystick preview: a recessed gate, a physical stick cap and the
 * RGB ring around the base. The live stick position comes from the gamepad
 * axes; the ring colour is the colour currently sent to that stick.
 */
final class JoystickPreviewView extends View {

    interface Listener {
        void onToggle(JoystickPreviewView view);
    }

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float stickX;
    private float stickY;
    private int ringColor = 0xFF2BE0D8;
    private boolean targetOn = true;
    private boolean powered = true;
    private Listener listener;

    JoystickPreviewView(Context c) {
        super(c);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        basePaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        highlightPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setRingColor(int color) {
        ringColor = color & 0x00FFFFFF;
        invalidate();
    }

    void setTargetEnabled(boolean enabled) {
        targetOn = enabled;
        invalidate();
    }

    void setPowered(boolean powered) {
        this.powered = powered;
        invalidate();
    }

    void setStick(float x, float y) {
        float mag = (float) Math.hypot(x, y);
        if (mag < 0.055f) {
            stickX = 0f;
            stickY = 0f;
        } else {
            if (mag > 1f) {
                x /= mag;
                y /= mag;
            }
            stickX = x;
            stickY = y;
        }
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused) Haptics.tick(this);
        invalidate();
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        if (listener != null) listener.onToggle(this);
        return handled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                requestFocus();
                return true;
            case MotionEvent.ACTION_UP:
                setPressed(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) * 0.42f;
        float ringRadius = radius * 0.79f;
        int lit = (powered && targetOn && (ringColor & 0x00FFFFFF) != 0)
                ? (0xFF000000 | ringColor) : 0xFF39424E;

        // Soft LED bloom outside the physical ring.
        if (powered && targetOn && (ringColor & 0x00FFFFFF) != 0) {
            glowPaint.setShader(new RadialGradient(cx, cy, ringRadius * 1.62f,
                    (0x55 << 24) | (ringColor & 0x00FFFFFF), 0x00000000,
                    Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, ringRadius * 1.62f, glowPaint);
            glowPaint.setShader(null);
        }

        // The RGB ring itself.
        ringPaint.setStrokeWidth(Ui.dpf(getContext(), 4.2f));
        ringPaint.setColor(lit);
        ringPaint.setPathEffect(targetOn ? null
                : new DashPathEffect(new float[]{Ui.dpf(getContext(), 3),
                Ui.dpf(getContext(), 3)}, 0f));
        c.drawCircle(cx, cy, ringRadius, ringPaint);
        ringPaint.setPathEffect(null);

        // Recessed base and gate outline.
        float baseRadius = radius * 0.73f;
        basePaint.setStrokeWidth(Ui.dpf(getContext(), 1.3f));
        basePaint.setColor(0x66FFFFFF);
        c.drawCircle(cx, cy, baseRadius, basePaint);
        basePaint.setColor(0x2EFFFFFF);
        c.drawCircle(cx, cy, baseRadius * 0.82f, basePaint);

        capPaint.setStyle(Paint.Style.FILL);
        capPaint.setShader(new RadialGradient(cx - baseRadius * 0.24f,
                cy - baseRadius * 0.28f, baseRadius * 1.12f,
                0xFF3B4653, 0xFF080C12, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, baseRadius * 0.86f, capPaint);
        capPaint.setShader(null);

        // Physical stick: shaft from the gate centre to the cap, then the cap.
        float travel = baseRadius * 0.35f;
        float knobX = cx + stickX * travel;
        float knobY = cy + stickY * travel;
        float knobRadius = baseRadius * 0.27f;
        linePaint.setStrokeWidth(Ui.dpf(getContext(), 7f));
        linePaint.setColor(0xE0080C12);
        c.drawLine(cx, cy, knobX, knobY, linePaint);
        linePaint.setStrokeWidth(Ui.dpf(getContext(), 1.5f));
        linePaint.setColor(0x99FFFFFF);
        c.drawLine(cx, cy, knobX, knobY, linePaint);

        Paint capFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        capFill.setShader(new RadialGradient(knobX - knobRadius * 0.25f,
                knobY - knobRadius * 0.3f, knobRadius * 1.15f,
                0xFF4A5666, 0xFF111820, Shader.TileMode.CLAMP));
        c.drawCircle(knobX, knobY, knobRadius, capFill);
        capFill.setShader(null);

        highlightPaint.setStrokeWidth(Ui.dpf(getContext(), 1.6f));
        highlightPaint.setColor(powered && targetOn
                ? (0xCC000000 | ringColor) : 0x66FFFFFF);
        c.drawCircle(knobX, knobY, knobRadius, highlightPaint);
        highlightPaint.setStrokeWidth(Ui.dpf(getContext(), 1f));
        highlightPaint.setColor(0x55FFFFFF);
        c.drawCircle(knobX, knobY, knobRadius * 0.55f, highlightPaint);

        if (isFocused()) {
            focusPaint.setStrokeWidth(Ui.dpf(getContext(), 4.5f));
            focusPaint.setColor(0x66000000);
            c.drawCircle(cx, cy, ringRadius + Ui.dpf(getContext(), 4), focusPaint);
            focusPaint.setStrokeWidth(Ui.dpf(getContext(), 2.2f));
            focusPaint.setColor(0xFF8BE9FF);
            c.drawCircle(cx, cy, ringRadius + Ui.dpf(getContext(), 4), focusPaint);
        }

        // A small centre pip reads as the stick's moulded top.
        Paint pip = new Paint(Paint.ANTI_ALIAS_FLAG);
        pip.setColor(0x55FFFFFF);
        c.drawCircle(cx, cy, Ui.dpf(getContext(), 1.3f), pip);
    }
}
