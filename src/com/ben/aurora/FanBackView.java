package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Stylised rear chassis with the live blower fan and top exhaust airflow. */
final class FanBackView extends View {
    private static final int ACCENT = 0xFF8BE9FF;
    private static final float W = 400f;
    private static final float H = 170f;
    private static final float FAN_CX = 272f;
    private static final float FAN_CY = 98f;
    private static final float FAN_R = 36f;

    private final Paint shell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shellFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint air = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path airPath = new Path();
    private final Path skyPath = new Path();
    private final RectF rect = new RectF();
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random(17);

    private int duty;
    private int rpm;
    private float cpuTemp;
    private float gpuTemp;
    private float angle;
    private float targetRpm;
    private float visualRpm;
    private int heatColor = ACCENT;
    private long lastFrame;
    private boolean attached;
    private float emitAccumulator;
    private float gyroRoll;
    private float gyroPitch;
    private float gyroRollVelocity;
    private float lastGyroRoll;
    private float skydiverY = 92f;
    private float skydiverVelocity;
    private float skydiverTime;
    private float airflowLift = 1f;
    private final GyroTiltController gyro;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!attached) return;
            if (lastFrame == 0L) lastFrame = frameTimeNanos;
            float dt = Math.min(0.05f, (frameTimeNanos - lastFrame) / 1_000_000_000f);
            lastFrame = frameTimeNanos;
            updateVisualRpm(dt);
            if (gyro != null) {
                float roll = gyro.getRoll();
                float pitch = gyro.getPitch();
                float velocity = dt > 0f ? (roll - lastGyroRoll) / dt : 0f;
                velocity = clamp(velocity, 260f);
                gyroRollVelocity += (velocity - gyroRollVelocity)
                        * Math.min(1f, dt * 8f);
                gyroRoll = roll;
                gyroPitch = pitch;
                lastGyroRoll = roll;
            }
            float speed = speedNorm();
            skydiverTime += dt;
            boolean fanOff = isFanOff();
            float liftTarget = fanOff ? 0f : 1f;
            airflowLift += (liftTarget - airflowLift) * Math.min(1f, dt * 1.25f);
            if (fanOff) {
                skydiverVelocity += 165f * dt;
                skydiverY += skydiverVelocity * dt;
                if (skydiverY >= 92f) {
                    skydiverY = 92f;
                    skydiverVelocity = 0f;
                }
            } else {
                // Low airflow rests on the fan face; only meaningful airflow
                // lifts the canopy back toward the exhaust vent.
                float flow = Math.max(0f, Math.min(1f, duty / 100f));
                float hoverY = 92f - 68f * flow * flow;
                float open = airflowLift * airflowLift * (3f - 2f * airflowLift);
                float targetY = 92f + (hoverY - 92f) * open;
                float liftAccel = (targetY - skydiverY) * 18f - skydiverVelocity * 6.5f;
                skydiverVelocity += liftAccel * dt;
                if (skydiverVelocity < -30f) skydiverVelocity = -30f;
                skydiverY = Math.max(15f, Math.min(92f,
                        skydiverY + skydiverVelocity * dt));
            }
            angle = (angle + dt * 360f * visualRps()) % 360f;
            emitAccumulator += dt * (2f + 40f * speed);
            while (emitAccumulator >= 1f) {
                emitAccumulator -= 1f;
                emitParticle();
            }
            float sideForce = gyroRoll * 2.8f + gyroRollVelocity * 0.055f;
            float verticalForce = -gyroPitch * 0.90f;
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle p = particles.get(i);
                p.vx += sideForce * dt;
                p.vy += verticalForce * dt;
                p.vx *= 0.997f;
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                p.life -= dt;
                if (p.life <= 0f || p.y < 15f || p.x < -10f || p.x > W + 10f) {
                    particles.remove(i);
                }
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static final class Particle {
        float x, y, vx, vy, life, size;
    }

    FanBackView(Context c) {
        this(c, true);
    }

    FanBackView(Context c, boolean allowTilt) {
        super(c);
        shell.setStyle(Paint.Style.STROKE);
        shell.setStrokeWidth(1.5f);
        shell.setColor(0xAAE6EDF5);
        shellFill.setStyle(Paint.Style.FILL);
        shellFill.setColor(0xFF0B0F14);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(1f);
        inner.setColor(0x557A8797);
        innerFill.setStyle(Paint.Style.FILL);
        innerFill.setColor(0xFF11161C);
        detail.setStyle(Paint.Style.STROKE);
        detail.setStrokeWidth(1f);
        detail.setColor(0x446C7785);
        text.setColor(0x88FFFFFF);
        text.setTextSize(6.5f);
        blade.setStyle(Paint.Style.FILL);
        blade.setColor(0xFF2A323C);
        hub.setStyle(Paint.Style.FILL);
        hub.setColor(0xFFDDE4EA);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(2.2f);
        glow.setColor(ACCENT);
        air.setStyle(Paint.Style.STROKE);
        air.setStrokeCap(Paint.Cap.ROUND);
        skyPaint.setStyle(Paint.Style.FILL);
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
        this.rpm = Math.max(0, rpm);
        this.duty = Math.max(0, Math.min(100, duty));
        this.cpuTemp = cpuTemp;
        this.gpuTemp = gpuTemp;
        float measured = this.rpm > 0 ? this.rpm : (this.duty > 0 ? this.duty * 180f : 0f);
        targetRpm = measured;
        heatColor = HeatColor.ramp(HeatColor.fanSeverity(duty, this.rpm,
                Math.max(cpuTemp, gpuTemp)));
        invalidate();
    }

    /** Responsive spin-up, inertial spin-down. */
    private void updateVisualRpm(float dt) {
        if (targetRpm > 0f && visualRpm <= 0.1f) {
            visualRpm = targetRpm;
            return;
        }
        float tau = targetRpm > visualRpm ? 0.10f : 1.0f;
        float alpha = 1f - (float) Math.exp(-dt / tau);
        visualRpm += (targetRpm - visualRpm) * alpha;
        if (targetRpm <= 0f && visualRpm < 30f) visualRpm = 0f;
    }

    /** Real revolutions per second, scaled down 20x for a readable animation. */
    private float visualRps() {
        return Math.max(0f, visualRpm / 1200f);
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private float speedNorm() {
        float byRpm = visualRpm / 10000f;
        float byDuty = duty / 100f;
        return Math.max(0f, Math.min(1f, Math.max(byRpm, byDuty)));
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

    private void emitParticle() {
        if (duty <= 0 && visualRpm < 40f) return;
        if (particles.size() > 44) return;
        Particle p = new Particle();
        float dx = (random.nextFloat() * 2f - 1f) * FAN_R * 0.88f;
        float dy = (float) Math.sqrt(Math.max(0f, FAN_R * FAN_R - dx * dx));
        p.x = FAN_CX + dx;
        p.y = FAN_CY - dy + 2f;
        float speed = 28f + 92f * speedNorm();
        p.vx = (random.nextFloat() - 0.5f) * 18f
                + gyroRoll * 0.75f + gyroRollVelocity * 0.025f;
        p.vy = -speed * (0.85f + random.nextFloat() * 0.3f);
        p.life = 1.2f + random.nextFloat() * 1.2f;
        p.size = 0.7f + random.nextFloat() * 1.1f;
        particles.add(p);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float s = Math.min(getWidth() / W, getHeight() / H);
        c.save();
        c.translate((getWidth() - W * s) / 2f,
                (getHeight() - H * s) / 2f);
        c.scale(s, s);
        text.setColor(0x88FFFFFF);

        drawShell(c);
        drawBattery(c);
        drawVents(c);
        drawFan(c);
        drawAirflow(c);
        drawSkydiver(c);
        c.restore();
    }

    private boolean isFanOff() {
        return duty <= 0 && visualRpm < 50f;
    }

    private void drawSkydiver(Canvas c) {
        float speed = speedNorm();
        boolean fanOff = isFanOff();
        float x = FAN_CX + (float) Math.sin(skydiverTime * 1.7f) * 2.2f
                + gyroRoll * 0.9f;
        float y = skydiverY + (float) Math.sin(skydiverTime * 2.6f) * 0.55f;
        float canopyOpen = fanOff ? 0f : airflowLift;
        float canopyScale = 0.52f + 0.48f * canopyOpen
                * (0.70f + 0.30f * Math.min(1f, speed * 1.35f));
        float lean = gyroRoll * 0.35f + gyroRollVelocity * 0.008f
                + (float) Math.sin(skydiverTime * 0.7f) * 1.8f
                + (fanOff ? 14f : 0f);
        float kick = (float) Math.sin(skydiverTime * 6.2f) * (1f - speed * 0.45f);

        c.save();
        c.translate(x, y);
        c.rotate(lean);
        c.scale(1.25f, 1.25f);

        // Canopy.
        float cw = 12f * canopyScale;
        float ch = 9f * canopyScale;
        skyPaint.setStyle(Paint.Style.FILL);
        skyPaint.setColor(0xCC8BE9FF);
        skyPath.reset();
        skyPath.moveTo(-cw, -8f);
        skyPath.cubicTo(-cw * 0.72f, -8f - ch, cw * 0.72f, -8f - ch, cw, -8f);
        skyPath.cubicTo(cw * 0.48f, -6.6f, -cw * 0.48f, -6.6f, -cw, -8f);
        skyPath.close();
        c.drawPath(skyPath, skyPaint);
        skyPaint.setStyle(Paint.Style.STROKE);
        skyPaint.setStrokeWidth(0.7f);
        skyPaint.setColor(0xAAE8FBFF);
        c.drawPath(skyPath, skyPaint);

        // Suspension lines.
        skyPaint.setStrokeWidth(0.45f);
        skyPaint.setColor(0x774F9AB5);
        c.drawLine(-cw, -8f, -2.2f, -2.8f, skyPaint);
        c.drawLine(cw, -8f, 2.2f, -2.8f, skyPaint);
        c.drawLine(-cw * 0.42f, -8f - ch * 0.35f, -2.2f, -2.8f, skyPaint);
        c.drawLine(cw * 0.42f, -8f - ch * 0.35f, 2.2f, -2.8f, skyPaint);

        // Body, helmet, arms and legs.
        skyPaint.setStyle(Paint.Style.FILL);
        skyPaint.setColor(0xFFE8B88E);
        c.drawCircle(0f, -4.4f, 2.1f, skyPaint);
        skyPaint.setColor(0xE6F2F6F8);
        c.drawCircle(0f, -5.4f, 1.7f, skyPaint);
        skyPaint.setColor(0xEEF0A35E);
        c.drawRoundRect(new RectF(-2.4f, -3.3f, 2.4f, 2.5f),
                1.4f, 1.4f, skyPaint);
        skyPaint.setStyle(Paint.Style.STROKE);
        skyPaint.setStrokeWidth(0.9f);
        c.drawLine(-1.4f, -1.8f, -3.2f, -3.8f + kick, skyPaint);
        c.drawLine(1.4f, -1.8f, 3.2f, -3.8f - kick, skyPaint);
        c.drawLine(-0.9f, 1.5f, -2.0f, 5.0f + kick, skyPaint);
        c.drawLine(0.9f, 1.5f, 2.0f, 5.0f - kick, skyPaint);

        // Wind streaks around the canopy at high fan speed.
        if (speed > 0.35f && !fanOff && airflowLift > 0.55f) {
            skyPaint.setStrokeWidth(0.55f);
            skyPaint.setColor(HeatColor.withAlpha(0xC8EFFF, (int) (45 + speed * 80)));
            float streak = 5f + speed * 7f;
            c.drawLine(-cw - 3f, -9f, -cw - 1f, -9f - streak, skyPaint);
            c.drawLine(cw + 2f, -10f, cw + 4f, -10f - streak * 0.7f, skyPaint);
        }
        c.restore();
    }

    private void drawShell(Canvas c) {
        body.reset();
        body.moveTo(52, 18);
        body.lineTo(348, 18);
        body.quadTo(384, 18, 388, 52);
        body.lineTo(394, 112);
        body.quadTo(396, 158, 354, 160);
        body.lineTo(46, 160);
        body.quadTo(4, 158, 6, 112);
        body.lineTo(12, 52);
        body.quadTo(16, 18, 52, 18);
        body.close();
        c.drawPath(body, shellFill);
        c.drawPath(body, shell);

        rect.set(58, 30, 342, 148);
        c.drawRoundRect(rect, 10, 10, innerFill);
        c.drawRoundRect(rect, 10, 10, inner);

        c.drawArc(new RectF(11, 58, 55, 134), 100, 150, false, detail);
        c.drawArc(new RectF(345, 58, 389, 134), -70, 150, false, detail);
        c.drawArc(new RectF(13, 72, 45, 124), 90, 120, false, detail);
        c.drawArc(new RectF(355, 72, 387, 124), -30, 120, false, detail);

        float[][] screws = {{68, 39}, {202, 39}, {332, 39}, {68, 138},
                {120, 138}, {202, 138}, {332, 138}};
        for (float[] p : screws) {
            c.drawCircle(p[0], p[1], 2.4f, detail);
            c.drawLine(p[0] - 1.2f, p[1] - 1.2f, p[0] + 1.2f, p[1] + 1.2f, detail);
        }
    }

    private void drawBattery(Canvas c) {
        rect.set(82, 52, 190, 136);
        c.drawRoundRect(rect, 4, 4, innerFill);
        c.drawRoundRect(rect, 4, 4, detail);
        c.drawLine(91, 77, 176, 77, detail);
        c.drawLine(91, 89, 146, 89, detail);
        c.drawLine(91, 101, 164, 101, detail);
        c.drawLine(91, 113, 130, 113, detail);
        c.drawText("8000mAh", 94, 66, text);
        c.drawText("ODIN 3", 94, 126, text);
        Paint wire = new Paint(Paint.ANTI_ALIAS_FLAG);
        wire.setStyle(Paint.Style.STROKE);
        wire.setStrokeWidth(1.2f);
        wire.setColor(0xFF9A3232);
        c.drawLine(190, 122, 222, 122, wire);
        wire.setColor(0xFF2B3A55);
        c.drawLine(190, 118, 222, 118, wire);
    }

    private void drawVents(Canvas c) {
        rect.set(226, 31, 320, 46);
        c.drawRoundRect(rect, 3, 3, innerFill);
        for (int x = 231; x < 317; x += 4) {
            c.drawLine(x, 33, x, 44, detail);
        }
        c.drawText("EXHAUST", 242, 57, text);
    }

    private void drawFan(Canvas c) {
        float cx = FAN_CX, cy = FAN_CY, r = FAN_R;
        float rotorX = clamp(gyroRoll * 0.28f + gyroRollVelocity * 0.018f, 3.2f);
        float rotorY = clamp(-gyroPitch * 0.18f, 2.4f);
        c.drawCircle(cx, cy, r + 7, innerFill);
        c.drawCircle(cx, cy, r + 7, inner);
        c.drawCircle(cx, cy, r, inner);
        c.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, r - 2, Path.Direction.CW);
        c.clipPath(clip);
        c.translate(cx + rotorX, cy + rotorY);
        c.rotate(angle);
        blade.setColor(HeatColor.mixColor(0xFF2E3844, heatColor, 0.70f));
        float hubR = r * 0.46f;
        int blades = 30;
        for (int i = 0; i < blades; i++) {
            c.save();
            c.rotate(i * 360f / blades);
            Path p = new Path();
            p.moveTo(hubR - 1f, -1.1f);
            p.cubicTo(hubR + 3f, -3.4f, r - 10f, -4.3f, r - 2f, -1.2f);
            p.cubicTo(r - 10f, 2.8f, hubR + 4f, 2.5f, hubR - 1f, 1.1f);
            p.close();
            c.drawPath(p, blade);
            c.restore();
        }
        c.restore();

        float hubX = cx + rotorX;
        float hubY = cy + rotorY;
        hub.setColor(HeatColor.mixColor(0xFFE8EDF2, heatColor, 0.28f));
        c.drawCircle(hubX, hubY, hubR, hub);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45f + 22.5f);
            float sx = hubX + (float) Math.cos(a) * hubR * 0.72f;
            float sy = hubY + (float) Math.sin(a) * hubR * 0.72f;
            c.drawCircle(sx, sy, 1.1f, detail);
        }
        c.drawCircle(hubX, hubY, 3.2f, detail);
        c.drawCircle(hubX, hubY, 1.1f, inner);

        float speed = speedNorm();
        if (speed > 0.05f) {
            glow.setColor(heatColor);
            glow.setAlpha((int) (70 + 150 * speed));
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                    angle, 300, false, glow);
        }
        text.setColor(HeatColor.withAlpha(heatColor, 0xB0));
        c.drawText("FAN", cx - 8, cy + r + 14, text);
    }

    private void drawAirflow(Canvas c) {
        float speed = speedNorm();
        air.setColor(heatColor);
        for (Particle p : particles) {
            float alpha = Math.max(0f, Math.min(1f, p.life));
            air.setAlpha((int) (20 + 110 * alpha * (0.25f + 0.75f * speed)));
            air.setStrokeWidth(p.size);
            airPath.reset();
            airPath.moveTo(p.x, p.y + 8f);
            airPath.cubicTo(p.x + 2.2f, p.y + 3.5f, p.x - 2.2f, p.y - 3.5f, p.x, p.y);
            c.drawPath(airPath, air);
        }
        if (speed > 0.02f) {
            float bend = gyroRoll * 1.5f + gyroRollVelocity * 0.04f;
            float glowX = FAN_CX + bend;
            float glowY = FAN_CY - FAN_R - 18f - gyroPitch * 1.2f;
            air.setStyle(Paint.Style.STROKE);
            air.setStrokeCap(Paint.Cap.ROUND);
            air.setStrokeWidth(11f);
            air.setColor(HeatColor.withAlpha(heatColor, 0x20));
            airPath.reset();
            airPath.moveTo(FAN_CX - 9f, FAN_CY - FAN_R + 1f);
            airPath.cubicTo(FAN_CX - 7f, FAN_CY - FAN_R - 20f + bend * 0.4f,
                    FAN_CX + bend * 0.8f, glowY + 12f,
                    FAN_CX + bend * 1.4f, 22f);
            c.drawPath(airPath, air);
            air.setStrokeWidth(2.5f);
            air.setColor(HeatColor.withAlpha(heatColor, 0x38));
            c.drawPath(airPath, air);

            glow.setStyle(Paint.Style.FILL);
            glow.setShader(new RadialGradient(glowX, glowY, 52f,
                    HeatColor.withAlpha(heatColor, 0x2A),
                    HeatColor.withAlpha(heatColor, 0x00), Shader.TileMode.CLAMP));
            glow.setAlpha((int) (60 + 100 * speed));
            c.drawCircle(glowX, glowY, 52f, glow);
            glow.setShader(null);
            glow.setStyle(Paint.Style.STROKE);
            glow.setAlpha(255);
        }
    }
}
