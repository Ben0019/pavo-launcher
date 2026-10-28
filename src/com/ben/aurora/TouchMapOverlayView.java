package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Non-touchable visual guide drawn over the target game. */
final class TouchMapOverlayView extends View {
    private static final int ACCENT = 0xFF8BE9FF;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glassClip = new Path();
    private final Matrix glassMatrix = new Matrix();
    private Backdrop backdrop;
    private BitmapShader glassShader;
    private final JoystickArt joystick;
    private final List<TouchTarget> targets = new ArrayList<>();
    private final List<TouchCombo> combos = new ArrayList<>();
    private final TouchStick stick = TouchStick.defaults();
    private boolean stickEnabled;
    private boolean buttonsHidden;
    private boolean leftJoyHidden;
    private boolean rightJoyHidden;
    private float leftJoyAlpha = 1f;
    private float rightJoyAlpha = 1f;
    private RightStick right = RightStick.defaults();

    TouchMapOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);
        fill.setColor(0x26000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(context, 1.4f));
        stroke.setColor(0x99FFFFFF);
        text.setColor(0xE6FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(Ui.dp(context, 15));
        stickPaint.setStyle(Paint.Style.STROKE);
        stickPaint.setStrokeWidth(Ui.dp(context, 1.6f));
        stickPaint.setColor(0xAA7BE6B0);
        knob.setColor(0x6618C37A);
        glassTint.setColor(0x66000000);
        glassPaint.setFilterBitmap(true);
        joystick = new JoystickArt(context);
    }

    void setBackdrop(Backdrop backdrop) {
        this.backdrop = backdrop;
        if (backdrop != null && backdrop.bitmap != null
                && backdrop.bitmap.getWidth() > 0 && backdrop.bitmap.getHeight() > 0) {
            glassShader = new BitmapShader(backdrop.bitmap,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sx = backdrop.bitmap.getWidth() / (float) backdrop.screenWidth;
            float sy = backdrop.bitmap.getHeight() / (float) backdrop.screenHeight;
            glassMatrix.reset();
            glassMatrix.setScale(1f / sx, 1f / sy);
            glassShader.setLocalMatrix(glassMatrix);
            glassPaint.setShader(glassShader);
        } else {
            glassShader = null;
            glassPaint.setShader(null);
        }
        invalidate();
    }

    void setLayout(List<TouchTarget> next, TouchStick nextStick, boolean useStick) {
        setLayout(next, nextStick, useStick, right);
    }

    void setLayout(List<TouchTarget> next, TouchStick nextStick, boolean useStick,
                   RightStick nextRight) {
        setLayout(next, nextStick, useStick, nextRight, combos);
    }

    void setLayout(List<TouchTarget> next, TouchStick nextStick, boolean useStick,
                   RightStick nextRight, List<TouchCombo> nextCombos) {
        targets.clear();
        if (next != null) {
            for (TouchTarget t : next) targets.add(t.copy());
        }
        TouchStick s = nextStick == null ? TouchStick.defaults() : nextStick;
        stick.x = s.x;
        stick.y = s.y;
        stick.radius = s.radius;
        stickEnabled = useStick;
        right = nextRight == null ? RightStick.defaults() : nextRight.copy();
        combos.clear();
        if (nextCombos != null) {
            for (TouchCombo c : nextCombos) combos.add(c.copy());
        }
        invalidate();
    }

    void setTargets(List<TouchTarget> next) {
        setLayout(next, stick, stickEnabled, right, combos);
    }

    void setButtonsHidden(boolean hidden) {
        buttonsHidden = hidden;
        invalidate();
    }

    void setJoystickAppearance(boolean leftHidden, float leftAlpha,
                               boolean rightHidden, float rightAlpha) {
        leftJoyHidden = leftHidden;
        leftJoyAlpha = leftAlpha;
        rightJoyHidden = rightHidden;
        rightJoyAlpha = rightAlpha;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (buttonsHidden) return;
        if (stickEnabled && !leftJoyHidden) {
            joystick.draw(canvas, stick.x * w, stick.y * h, stick.radius * h,
                    0xFF7BE6B0, 0.86f * leftJoyAlpha, "MOVE", false);
        }
        if (rightJoyHidden) {
            // The right-stick guides are hidden individually.
        } else if (right.mode == RightStick.MOUSE) {
            text.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("MOUSE · R1 CLICK", w - Ui.dp(getContext(), 16),
                    Ui.dp(getContext(), 26), text);
            text.setTextAlign(Paint.Align.CENTER);
        } else if (right.usesTouchZone() && right.mode != RightStick.AIM) {
            int color = right.mode == RightStick.MOBA ? 0xFFB99CFF
                    : (right.mode == RightStick.JOYSTICK ? 0xFF8BE9FF : 0xFFFFC857);
            joystick.draw(canvas, right.zone.x * w, right.zone.y * h,
                    right.zone.radius * h, color, 0.92f * rightJoyAlpha,
                    RightStick.label(right.mode), false);
        }
        TouchTarget r3 = null;
        for (TouchTarget t : targets) {
            if (t.code == TouchTarget.R3_STICK) {
                r3 = t;
                continue;
            }
            if (buttonsHidden) continue;
            if (stickEnabled && (t.code == TouchTarget.DPAD_UP
                    || t.code == TouchTarget.DPAD_DOWN
                    || t.code == TouchTarget.DPAD_LEFT
                    || t.code == TouchTarget.DPAD_RIGHT)) {
                continue;
            }
            if (t.hidden) continue;
            float cx = t.x * w;
            float cy = t.y * h;
            int a = Math.max(0, Math.min(255, Math.round(t.alpha * 255f)));
            float tr = t.radius * h;
            int color = t.code >= 544 && t.code < 560 ? 0xFFFFC857
                    : TouchTarget.colorFor(t.code);
            drawGlassCircle(canvas, cx, cy, tr, color, a, t.label);
        }

        if (r3 != null && !r3.hidden && !rightJoyHidden) {
            float rr = (right.mode == RightStick.OFF ? 0.16f : right.zone.radius) * h;
            joystick.draw(canvas, r3.x * w, r3.y * h, rr, 0xFFFFC857,
                    Math.min(r3.alpha, rightJoyAlpha), "R3 STICK", false);
        }
        for (TouchCombo c : combos) {
            if (c.hidden) continue;
            drawGlassCircle(canvas, c.x * w, c.y * h, c.radius * h,
                    0xFF69D98B, Math.round(c.alpha * 255f), c.label());
        }
    }

    private void drawGlassCircle(Canvas canvas, float cx, float cy, float radius,
                                 int color, int alpha, String label) {
        int a = Math.max(0, Math.min(255, alpha));
        if (glassShader != null && radius > 0f) {
            int save = canvas.save();
            glassClip.reset();
            glassClip.addCircle(cx, cy, radius, Path.Direction.CW);
            canvas.clipPath(glassClip);
            glassPaint.setAlpha(Math.round(a * 0.72f));
            canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, glassPaint);
            glassTint.setAlpha(Math.round(a * 0.30f));
            canvas.drawRect(cx - radius, cy - radius, cx + radius, cy + radius, glassTint);
            canvas.restoreToCount(save);
        } else {
            fill.setColor(withAlpha(color, Math.round(a * 0.16f)));
            canvas.drawCircle(cx, cy, radius, fill);
        }
        stroke.setColor(withAlpha(color, Math.round(a * 0.92f)));
        stroke.setStrokeWidth(Ui.dp(getContext(), 1.8f));
        canvas.drawCircle(cx, cy, radius, stroke);
        text.setColor(withAlpha(0xFFFFFFFF, a));
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        text.setShadowLayer(Ui.dp(getContext(), 2f), 0, Ui.dp(getContext(), 1f), 0xCC000000);
        canvas.drawText(label, cx, baseline, text);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
