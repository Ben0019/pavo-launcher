package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Rear internals board for the Performance Tuner: battery, flex cables, copper
 * SoC shield, CPU/RAM dies, heat spreader and steam. Modelled on the Odin 3 back.
 */
final class ThermalBoardView extends View {
    private static final float W = 560f;
    private static final float H = 200f;

    private final Paint shell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shellFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pcb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint battery = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint copper = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spreader = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flex = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipDark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint steamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint qr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meat = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panSteam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path shield = new Path();
    private final Path steamPath = new Path();
    private final RectF rect = new RectF();
    private final List<Steam> steam = new ArrayList<>();
    private final Random random = new Random(23);

    private float cpuTemp;
    private float gpuTemp;
    private int cpuColor = 0xFF5CD68A;
    private int ramColor = 0xFF5CD68A;
    private long lastFrame;
    private boolean attached;
    private float emitAccumulator;
    private final GyroTiltController gyro;
    private float gyroRoll;
    private float gyroPitch;
    private float gyroRollVelocity;
    private float lastGyroRoll;
    private float panCook = 0.12f;
    private float panHold;
    private float panTime;

    private final class Steam {
        float x, y, vx, vy, life, maxLife, size;
    }

    private boolean framePosted;
    private final Runnable resumeFrame = new Runnable() {
        @Override
        public void run() {
            if (attached && !framePosted) {
                framePosted = true;
                Choreographer.getInstance().postFrameCallback(frame);
            }
        }
    };

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (!attached) return;
            if (!Ui.onScreen(ThermalBoardView.this)) {
                // Off-screen widget pages must not keep the UI thread busy.
                lastFrame = 0L;
                removeCallbacks(resumeFrame);
                postDelayed(resumeFrame, 500L);
                return;
            }
            if (lastFrame == 0L) lastFrame = frameTimeNanos;
            float dt = Math.min(0.05f, (frameTimeNanos - lastFrame) / 1_000_000_000f);
            lastFrame = frameTimeNanos;
            if (gyro != null) {
                float roll = gyro.getRoll();
                float pitch = gyro.getPitch();
                float velocity = dt > 0f ? (roll - lastGyroRoll) / dt : 0f;
                velocity = clamp(velocity, 240f);
                gyroRollVelocity += (velocity - gyroRollVelocity)
                        * Math.min(1f, dt * 8f);
                gyroRoll = roll;
                gyroPitch = pitch;
                lastGyroRoll = roll;
            }
            float heat = heatNorm();
            panTime += dt;
            float cpuHot = clamp((cpuTemp - 35f) / 50f, 1f);
            if (panCook < 1f) {
                panCook = Math.min(1f, panCook + dt * (0.012f + 0.55f * cpuHot));
            } else {
                panHold += dt;
                if (panHold > 5f) {
                    panCook = 0.12f;
                    panHold = 0f;
                }
            }
            emitAccumulator += dt * (3f + 26f * heat);
            while (emitAccumulator >= 1f) {
                emitAccumulator -= 1f;
                emitSteam(heat);
            }
            float sideForce = gyroRoll * 3.4f + gyroRollVelocity * 0.055f;
            float liftForce = -gyroPitch * 1.1f;
            for (int i = steam.size() - 1; i >= 0; i--) {
                Steam s = steam.get(i);
                s.vx += sideForce * dt;
                s.vy += liftForce * dt;
                s.vx *= 0.996f;
                s.x += (s.vx + (float) Math.sin(s.life * 3.2f + s.y * 0.05f) * 5f) * dt;
                s.y += s.vy * dt;
                s.life -= dt;
                if (s.life <= 0f || s.y < 5f) steam.remove(i);
            }
            invalidate();
            if (attached && !framePosted) {
                framePosted = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    ThermalBoardView(Context c) {
        this(c, true);
    }

    ThermalBoardView(Context c, boolean allowTilt) {
        super(c);
        shell.setStyle(Paint.Style.STROKE);
        shell.setStrokeWidth(1.6f);
        shell.setColor(0xB8E6EDF5);
        shellFill.setStyle(Paint.Style.FILL);
        shellFill.setColor(0xFF080C10);
        detail.setStyle(Paint.Style.STROKE);
        detail.setStrokeWidth(0.9f);
        detail.setColor(0x557A8797);
        text.setColor(0xCCFFFFFF);
        text.setTextSize(7.5f);
        smallText.setColor(0x88A9B5C2);
        smallText.setTextSize(5.2f);
        pcb.setStyle(Paint.Style.FILL);
        pcb.setColor(0xFF171D23);
        battery.setStyle(Paint.Style.FILL);
        battery.setColor(0xFF15191D);
        copper.setStyle(Paint.Style.FILL);
        copper.setColor(0xFF70604B);
        spreader.setStyle(Paint.Style.FILL);
        spreader.setColor(0xFF2E353B);
        flex.setStyle(Paint.Style.FILL);
        flex.setColor(0xFFC8941E);
        chip.setStyle(Paint.Style.FILL);
        chipDark.setStyle(Paint.Style.FILL);
        chipDark.setColor(0xFF11171C);
        glow.setStyle(Paint.Style.FILL);
        steamPaint.setStyle(Paint.Style.STROKE);
        steamPaint.setStrokeCap(Paint.Cap.ROUND);
        qr.setStyle(Paint.Style.FILL);
        qr.setColor(0xFFD8D8D8);
        pan.setStyle(Paint.Style.FILL);
        panSteam.setStyle(Paint.Style.STROKE);
        panSteam.setStrokeCap(Paint.Cap.ROUND);
        gyro = allowTilt ? new GyroTiltController(c, this) : null;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (gyro == null) return;
        if (visibility == VISIBLE) gyro.start();
        else gyro.stop();
    }

    void setTelemetry(int rpm, int duty, float cpuTemp, float gpuTemp) {
        this.cpuTemp = cpuTemp;
        this.gpuTemp = gpuTemp;
        cpuColor = cpuTemp > 0
                ? HeatColor.ramp(HeatColor.severity(0, cpuTemp)) : 0xFF7C8B99;
        ramColor = gpuTemp > 0
                ? HeatColor.ramp(HeatColor.severity(0, gpuTemp)) : cpuColor;
        invalidate();
    }

    private float heatNorm() {
        return HeatColor.severity(0, Math.max(cpuTemp, gpuTemp));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrame = 0L;
        if (gyro != null) gyro.start();
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        if (gyro != null) gyro.stop();
        super.onDetachedFromWindow();
    }

    private void emitSteam(float heat) {
        if (steam.size() > 38) return;
        int source = random.nextInt(3);
        float x;
        float y;
        if (source == 0) {
            x = 430f + random.nextFloat() * 66f;
            y = 38f;
        } else if (source == 1) {
            x = 370f + random.nextFloat() * 66f;
            y = 112f;
        } else {
            x = 445f + random.nextFloat() * 52f;
            y = 124f;
        }
        Steam s = new Steam();
        s.x = x;
        s.y = y;
        s.vx = -6f + random.nextFloat() * 12f
                + gyroRoll * 0.70f + gyroRollVelocity * 0.022f;
        s.vy = -(9f + 20f * heat) * (0.75f + random.nextFloat() * 0.5f)
                - gyroPitch * 0.25f;
        s.life = 2.2f + random.nextFloat() * 1.8f;
        s.maxLife = s.life;
        s.size = 0.7f + random.nextFloat() * 1.5f;
        steam.add(s);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (!Ui.onScreen(this)) return;
        float s = Math.min(getWidth() / W, getHeight() / H);
        c.save();
        c.translate((getWidth() - W * s) / 2f, (getHeight() - H * s) / 2f);
        c.scale(s, s);
        text.setColor(0xCCFFFFFF);
        drawShell(c);
        drawBattery(c);
        drawBoard(c);
        drawChips(c);
        drawPan(c);
        drawSteam(c);
        c.restore();
    }

    private void drawShell(Canvas c) {
        body.reset();
        body.moveTo(52, 8);
        body.lineTo(508, 8);
        body.quadTo(546, 8, 548, 44);
        body.lineTo(552, 157);
        body.quadTo(552, 192, 512, 192);
        body.lineTo(48, 192);
        body.quadTo(8, 192, 8, 157);
        body.lineTo(12, 44);
        body.quadTo(12, 8, 52, 8);
        body.close();
        c.drawPath(body, shellFill);
        c.drawPath(body, shell);

        // Grip pads and shoulder contours.
        c.drawArc(new RectF(15, 52, 76, 146), 95, 160, false, detail);
        c.drawArc(new RectF(485, 52, 546, 146), -75, 160, false, detail);
        c.drawArc(new RectF(18, 66, 60, 132), 90, 125, false, detail);
        c.drawArc(new RectF(500, 66, 542, 132), -25, 125, false, detail);
        c.drawRoundRect(new RectF(24, 155, 78, 188), 4, 4, detail);
        c.drawRoundRect(new RectF(482, 155, 536, 188), 4, 4, detail);

        text.setTextSize(11f);
        text.setColor(0x99FFFFFF);
        c.drawText("R", 37, 113, text);
        c.drawText("L", 520, 113, text);
        text.setTextSize(7.5f);

        float[][] screws = {{64, 20}, {180, 18}, {286, 18}, {360, 18}, {454, 20},
                {532, 20}, {64, 176}, {180, 176}, {286, 176}, {360, 176}, {532, 176}};
        for (float[] p : screws) drawScrew(c, p[0], p[1]);
    }

    private void drawBattery(Canvas c) {
        rect.set(66, 30, 338, 168);
        c.drawRoundRect(rect, 7, 7, battery);
        c.drawRoundRect(rect, 7, 7, detail);

        // Label paper and QR.
        rect.set(82, 40, 322, 156);
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setColor(0x1AFFFFFF);
        c.drawRoundRect(rect, 3, 3, label);
        c.drawText("8000mAh  3.85V", 92, 53, smallText);
        drawQr(c, 92, 58, 30);
        for (int i = 0; i < 9; i++) {
            float y = 58 + i * 8;
            c.drawLine(132, y, 308 - (i % 3) * 21, y, detail);
        }
        c.drawText("Li-ion", 94, 135, smallText);
        Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(1.1f);
        icon.setColor(0x88FFFFFF);
        c.drawCircle(100, 145, 8, icon);
        c.drawLine(96, 145, 104, 145, icon);

        // Battery flex/connector toward the mainboard.
        rect.set(330, 72, 352, 124);
        c.drawRoundRect(rect, 2, 2, battery);
        c.drawRoundRect(rect, 2, 2, detail);
    }

    private void drawBoard(Canvas c) {
        rect.set(342, 20, 536, 172);
        c.drawRoundRect(rect, 3, 3, pcb);
        c.drawRoundRect(rect, 3, 3, detail);

        // Copper heat shield over the SoC, and the L-shaped spreader below it.
        rect.set(390, 28, 526, 98);
        c.drawRoundRect(rect, 6, 6, copper);
        c.drawRoundRect(rect, 6, 6, detail);
        shield.reset();
        shield.moveTo(344, 98);
        shield.lineTo(526, 98);
        shield.lineTo(526, 158);
        shield.lineTo(458, 158);
        shield.lineTo(458, 136);
        shield.lineTo(344, 136);
        shield.close();
        c.drawPath(shield, spreader);
        c.drawPath(shield, detail);

        // Yellow flex cables and connectors between battery and board.
        for (int i = 0; i < 3; i++) {
            float x = 338 + i * 11;
            rect.set(x, 31, x + 7, 164);
            c.drawRoundRect(rect, 2, 2, flex);
            for (int y = 38; y < 160; y += 9) {
                c.drawLine(x + 1, y, x + 6, y, detail);
            }
        }
        rect.set(352, 20, 386, 30);
        c.drawRoundRect(rect, 2, 2, flex);
        rect.set(408, 158, 442, 170);
        c.drawRoundRect(rect, 2, 2, flex);

        // Top flexes and bottom speaker modules.
        c.drawRoundRect(new RectF(102, 10, 142, 20), 2, 2, flex);
        c.drawRoundRect(new RectF(236, 10, 280, 19), 2, 2, flex);
        c.drawRoundRect(new RectF(338, 164, 396, 190), 4, 4, chipDark);
        c.drawRoundRect(new RectF(410, 164, 464, 190), 4, 4, chipDark);
        c.drawText("SPEAKER", 346, 180, smallText);

        // Small passives and traces around the board.
        for (int i = 0; i < 7; i++) {
            float x = 400 + i * 18;
            c.drawLine(x, 160, x + 7, 167, detail);
        }
        c.drawText(DeviceProfile.get(getContext()).name.toUpperCase(), 466, 184, smallText);
    }

    private void drawChips(Canvas c) {
        // CPU die; the copper shield is already behind it.
        glow.setColor(HeatColor.withAlpha(cpuColor, 42));
        c.drawRoundRect(new RectF(398, 34, 512, 92), 8, 8, glow);
        chip.setColor(HeatColor.mixColor(0xFF1B3425, cpuColor, 0.72f));
        rect.set(414, 42, 504, 86);
        c.drawRoundRect(rect, 3, 3, chip);
        c.drawRoundRect(rect, 3, 3, detail);
        chipDark.setColor(HeatColor.mixColor(0xFF0B100C, cpuColor, 0.24f));
        rect.set(434, 51, 486, 79);
        c.drawRoundRect(rect, 2, 2, chipDark);
        c.drawText("CPU", 418, 40, smallText);

        // RAM packages on the L-spreader.
        glow.setColor(HeatColor.withAlpha(ramColor, 36));
        c.drawRoundRect(new RectF(352, 104, 416, 132), 5, 5, glow);
        c.drawRoundRect(new RectF(448, 104, 518, 132), 5, 5, glow);
        chip.setColor(HeatColor.mixColor(0xFF141C25, ramColor, 0.68f));
        rect.set(358, 108, 412, 128);
        c.drawRoundRect(rect, 2, 2, chip);
        c.drawRoundRect(rect, 2, 2, detail);
        rect.set(453, 108, 513, 128);
        c.drawRoundRect(rect, 2, 2, chip);
        c.drawRoundRect(rect, 2, 2, detail);
        c.drawText("RAM", 358, 103, smallText);
        c.drawText("RAM", 453, 103, smallText);

        // Small controller and power chips.
        chipDark.setColor(0xFF1C252D);
        rect.set(362, 143, 392, 156);
        c.drawRoundRect(rect, 2, 2, chipDark);
        rect.set(478, 139, 508, 151);
        c.drawRoundRect(rect, 2, 2, chipDark);
        c.drawText("PMIC", 362, 141, smallText);
    }

    private void drawPan(Canvas c) {
        float hot = clamp((cpuTemp - 35f) / 50f, 1f);
        float cx = 460f;
        float cy = 61f;
        float wobble = (float) Math.sin(panTime * 9f) * 0.45f * hot;

        c.save();
        c.translate(0f, wobble);
        c.scale(1.45f, 1.45f, cx, cy);

        // Handle, then the little pan over the CPU die.
        pan.setStyle(Paint.Style.STROKE);
        pan.setStrokeCap(Paint.Cap.ROUND);
        pan.setStrokeWidth(3.0f);
        pan.setColor(0xAA9FB0BA);
        c.drawLine(cx + 10f, cy - 1f, cx + 23f, cy - 10f, pan);
        pan.setStrokeWidth(1.0f);
        pan.setColor(0x55E8F6FF);
        c.drawLine(cx + 10f, cy - 1f, cx + 23f, cy - 10f, pan);

        pan.setStyle(Paint.Style.FILL);
        pan.setColor(0xE6121B23);
        c.drawOval(new RectF(cx - 13f, cy - 7f, cx + 13f, cy + 7f), pan);
        pan.setStyle(Paint.Style.STROKE);
        pan.setStrokeWidth(1.1f);
        pan.setColor(0xCCB8D6E8);
        c.drawOval(new RectF(cx - 13f, cy - 7f, cx + 13f, cy + 7f), pan);
        pan.setStrokeWidth(0.7f);
        pan.setColor(0x6682B9D8);
        c.drawOval(new RectF(cx - 10.5f, cy - 5f, cx + 10.5f, cy + 5f), pan);

        // Meat: pink when raw, seared brown when hot.
        int meatColor = panCook < 0.65f
                ? HeatColor.mixColor(0xFFFF7C84, 0xFFC06B45, panCook / 0.65f)
                : HeatColor.mixColor(0xFFC06B45, 0xFF4A2416,
                (panCook - 0.65f) / 0.35f);
        meat.setStyle(Paint.Style.FILL);
        meat.setColor(meatColor);
        c.drawOval(new RectF(cx - 7.5f, cy - 3.4f, cx + 8f, cy + 4.2f), meat);
        meat.setStyle(Paint.Style.STROKE);
        meat.setStrokeWidth(0.6f);
        meat.setColor(0x6627120D);
        c.drawOval(new RectF(cx - 7.5f, cy - 3.4f, cx + 8f, cy + 4.2f), meat);
        meat.setStrokeWidth(0.7f);
        meat.setColor(0x44301A10);
        c.drawLine(cx - 5f, cy - 1.5f, cx + 4.5f, cy - 3.0f, meat);
        c.drawLine(cx - 5.5f, cy + 1.0f, cx + 4.0f, cy - 0.4f, meat);
        c.drawLine(cx - 4.5f, cy + 2.2f, cx + 3.0f, cy + 1.0f, meat);
        meat.setColor(0x55FFE0BE);
        meat.setStyle(Paint.Style.FILL);
        c.drawOval(new RectF(cx - 4.5f, cy - 2.8f, cx + 1.5f, cy - 0.3f), meat);

        // Sizzle bubbles.
        for (int i = 0; i < 4; i++) {
            float p = (panTime * 1.5f + i * 0.27f) % 1f;
            float bx = cx - 5f + (i * 4.1f) + (float) Math.sin(panTime * 4f + i) * 1.2f;
            float by = cy - 2f + (i % 2) * 2.5f - p * 2.3f;
            meat.setColor(0x88FFF4D8);
            c.drawCircle(bx, by, 0.45f + (i % 2) * 0.25f, meat);
        }

        // Wispy steam, stronger when the CPU is hot.
        panSteam.setStrokeWidth(1.1f);
        float bend = gyroRoll * 0.45f + gyroRollVelocity * 0.018f;
        for (int i = 0; i < 3; i++) {
            float p = (panTime * 0.55f + i * 0.31f) % 1f;
            float baseX = cx - 5f + i * 5f;
            float baseY = cy - 6f;
            int alpha = (int) ((8f + 86f * hot) * (1f - p));
            if (alpha <= 2) continue;
            panSteam.setColor(HeatColor.withAlpha(0xFFFFFFFF, alpha));
            steamPath.reset();
            steamPath.moveTo(baseX, baseY);
            steamPath.cubicTo(baseX + bend * 0.4f - 2f, baseY - 5f,
                    baseX + bend * 0.8f + 2f, baseY - 10f,
                    baseX + bend, baseY - 14f - p * 7f);
            c.drawPath(steamPath, panSteam);
        }
        c.restore();
    }

    private void drawSteam(Canvas c) {
        if (steam.isEmpty()) return;
        float heat = heatNorm();
        for (Steam s : steam) {
            float life = Math.max(0f, Math.min(1f, s.life / s.maxLife));
            int tint = HeatColor.mixColor(0xFFFFFFFF, HeatColor.ramp(heat), 0.18f);
            steamPaint.setColor(HeatColor.withAlpha(tint, (int) (8 + 72 * life)));
            steamPaint.setStrokeWidth(s.size);
            float bend = s.vx * 0.085f;
            steamPath.reset();
            steamPath.moveTo(s.x, s.y + 9f);
            steamPath.cubicTo(s.x + 4f + bend * 0.35f, s.y + 4f,
                    s.x - 5f + bend * 0.80f, s.y - 3f,
                    s.x + bend, s.y);
            c.drawPath(steamPath, steamPaint);
        }
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private void drawScrew(Canvas c, float x, float y) {
        c.drawCircle(x, y, 3.2f, detail);
        c.drawLine(x - 1.6f, y - 1.6f, x + 1.6f, y + 1.6f, detail);
    }

    private void drawQr(Canvas c, float x, float y, float size) {
        qr.setColor(0x99FFFFFF);
        int cells = 9;
        float step = size / cells;
        for (int row = 0; row < cells; row++) {
            for (int col = 0; col < cells; col++) {
                if (((row * 7 + col * 5 + row * col) % 7) < 3) {
                    c.drawRect(x + col * step, y + row * step,
                            x + (col + 1) * step, y + (row + 1) * step, qr);
                }
            }
        }
    }
}
