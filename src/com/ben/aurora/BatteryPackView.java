package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Stylised Odin battery pack with a translucent liquid fill and pouring charge pipe. */
final class BatteryPackView extends View {
    private static final int ACCENT = 0xFF8BE9FF;
    private static final int WATER = 0xFF7CEBFF;
    private static final int GREEN = 0xFF63E6A2;
    private static final int AMBER = 0xFFFFC857;
    private static final int RED = 0xFFFF6B6B;
    private static final int[] FISH_COLORS = {0xFFFFC857, 0xFFFF8E7A, 0xFF8BE9FF};
    private static final float[] FISH_SIZES = {10.5f, 8.2f, 6.6f};
    private static final float[] FISH_SEEDS = {0.6f, 2.5f, 4.7f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pipe = new Path();
    private final Path pipeSegment = new Path();
    private final Path liquid = new Path();
    private final Path surface = new Path();
    private final Path stream = new Path();
    private final Path streamShape = new Path();
    private final Path plantPath = new Path();
    private final Path fishTail = new Path();
    private final Path fishFin = new Path();
    private final RectF fishOval = new RectF();
    private final Path sharkBody = new Path();
    private final Path sharkFin = new Path();
    private final Path sharkTail = new Path();
    private final Path octoBody = new Path();
    private final Path octoArm = new Path();
    private final Path jellyBell = new Path();
    private final Path jellyArm = new Path();
    private final Path seahorseBody = new Path();
    private final Path seahorseFin = new Path();
    private final Path starfishPath = new Path();
    private final Path diverBody = new Path();
    private final Path diverFin = new Path();
    private final Path diverHose = new Path();
    private final Path dolphinBody = new Path();
    private final Path dolphinFin = new Path();
    private final Path dolphinTail = new Path();
    private final Path whaleBody = new Path();
    private final Path whaleFin = new Path();
    private final Path whaleTail = new Path();
    private final Path seabed = new Path();
    private final Path sandTop = new Path();
    private final RectF creatureRect = new RectF();
    private final Path clipPath = new Path();
    private final Path measurePath = new Path();
    private final PathMeasure pipeMeasure = new PathMeasure();
    private final RectF body = new RectF();
    private final RectF fill = new RectF();
    private final RectF terminal = new RectF();
    private final RectF port = new RectF();

    private int targetLevel = 100;
    private float shownLevel = 100f;
    private boolean charging;
    private boolean plugged;
    private boolean separated;
    private boolean full;
    private float shownPlug = 1f;
    private long lastFrame;
    private boolean animating = true;
    private GyroTiltController gyro;
    private float liquidTilt;
    private float liquidTiltVelocity;
    private float cableSway;
    private float cableSwayVelocity;
    private float lastGyroRoll;
    private float lastGyroPitch;
    private float gyroRollVelocity;

    BatteryPackView(Context c) {
        this(c, true);
    }

    BatteryPackView(Context c, boolean allowTilt) {
        this(c, null, allowTilt);
    }

    BatteryPackView(Context c, AttributeSet attrs) {
        this(c, attrs, true);
    }

    BatteryPackView(Context c, AttributeSet attrs, boolean allowTilt) {
        super(c, attrs);
        textPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        gyro = allowTilt ? new GyroTiltController(c, this) : null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (gyro != null) gyro.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (gyro != null) gyro.stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (gyro == null) return;
        if (visibility == VISIBLE) gyro.start();
        else gyro.stop();
    }

    private void updateGyroPhysics(float dt) {
        if (dt <= 0f || gyro == null) return;
        float roll = gyro.getRoll();
        float pitch = gyro.getPitch();
        float rawRollVelocity = dt > 0f ? (roll - lastGyroRoll) / dt : 0f;
        rawRollVelocity = clamp(rawRollVelocity, 260f);
        gyroRollVelocity += (rawRollVelocity - gyroRollVelocity)
                * Math.min(1f, dt * 10f);
        lastGyroRoll = roll;
        lastGyroPitch = pitch;

        // The battery card rotates with the device; the liquid surface tries to
        // stay level, with a damped spring producing a little slosh on the way.
        float targetTilt = clamp(-roll * 0.95f, 11f);
        float tiltAccel = (targetTilt - liquidTilt) * 55f - liquidTiltVelocity * 7.5f;
        liquidTiltVelocity += tiltAccel * dt;
        liquidTilt = clamp(liquidTilt + liquidTiltVelocity * dt, 13f);

        // The hanging cable behaves like a light pendulum: gravity pulls it
        // toward world-down, while roll velocity and pitch kick it into a swing.
        float targetSway = clamp((-roll * 0.95f - pitch * 0.35f) * dp(0.80f)
                + gyroRollVelocity * dp(0.025f), dp(16f));
        float swayAccel = (targetSway - cableSway) * 72f - cableSwayVelocity * 5.0f;
        cableSwayVelocity += swayAccel * dt;
        cableSway = clamp(cableSway + cableSwayVelocity * dt, dp(18f));
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    void setAnimating(boolean enabled) {
        animating = enabled;
        if (enabled) {
            lastFrame = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    void setBattery(int level, boolean charging, boolean plugged,
                    boolean separated, boolean full) {
        targetLevel = Math.max(0, Math.min(100, level));
        this.charging = charging;
        this.plugged = plugged;
        this.separated = separated;
        this.full = full;
        long now = SystemClock.uptimeMillis();
        lastFrame = now;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, Math.max(0f,
                (now - (lastFrame == 0 ? now : lastFrame)) / 1000f));
        lastFrame = now;
        updateGyroPhysics(dt);
        shownLevel += (targetLevel - shownLevel) * Math.min(1f, dt * 5.5f);
        if (Math.abs(targetLevel - shownLevel) < 0.08f) shownLevel = targetLevel;
        float plugTarget = plugged ? 1f : 0f;
        shownPlug += (plugTarget - shownPlug) * Math.min(1f, dt * 4.8f);
        if (Math.abs(plugTarget - shownPlug) < 0.008f) shownPlug = plugTarget;

        float side = w * 0.055f;
        float bodyRight = w * 0.865f;
        float top = h * 0.22f;
        float bottom = h * 0.86f;
        body.set(side, top, bodyRight, bottom);
        float radius = h * 0.095f;

        float pad = Math.max(dp(3f), h * 0.018f);
        // Only a thin rim at the top: at 100% the liquid should reach the brim.
        fill.set(body.left + pad, body.top + dp(2f),
                body.right - pad, body.bottom - pad);
        float fillRadius = Math.max(dp(4f), radius - pad * 0.5f);

        float phase = (now % 3400L) / 3400f;
        float wave = (float) (Math.PI * 2f * phase);
        float amp = dp(2.2f) + (charging ? dp(1.2f) : 0f)
                + Math.min(dp(2.8f), Math.abs(liquidTiltVelocity) * 0.045f);
        float level = Math.max(0f, Math.min(100f, shownLevel));
        float surfaceY = fill.bottom - fill.height() * level / 100f;
        if (surfaceY < fill.top) surfaceY = fill.top;
        int levelColor = levelColor();

        drawFloor(canvas, w, h);
        drawTerminal(canvas);

        // Battery pack body and inner glass.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x660B0E14);
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setColor(0x33101820);
        canvas.drawRoundRect(fill, fillRadius, fillRadius, paint);

        // Internal cell structure, visible through the translucent liquid.
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(fill, fillRadius, fillRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x1BFFFFFF);
        for (int i = 1; i < 5; i++) {
            float x = fill.left + fill.width() * i / 5f;
            canvas.drawLine(x, fill.top, x, fill.bottom, paint);
        }
        paint.setColor(0x0CFFFFFF);
        canvas.drawLine(fill.left, fill.centerY(), fill.right, fill.centerY(), paint);
        canvas.restore();

        drawLiquid(canvas, fillRadius, surfaceY, wave, amp, levelColor, now, level);

        // Inner rim and pack outline.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x1FFFFFFF);
        canvas.drawRoundRect(new RectF(body.left + dp(3f), body.top + dp(3f),
                body.right - dp(3f), body.bottom - dp(3f)),
                radius - dp(2f), radius - dp(2f), paint);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(withAlpha(levelColor, 0xA8));
        canvas.drawRoundRect(body, radius, radius, paint);

        float portX = body.left + body.width() * 0.72f;
        drawPort(canvas, portX);

        textPaint.setTextSize(dp(7.4f));
        textPaint.setColor(0x77FFFFFF);
        canvas.drawText(DeviceProfile.get(getContext()).name.toUpperCase()
                + "  ·  " + Math.max(0, targetLevel) + "%",
                body.left + dp(8f), body.top + dp(15f), textPaint);

        if (separated) {
            drawShield(canvas, body.right - dp(24f), body.top + dp(24f), dp(10f));
        }

        float[] geometry = drawPipe(canvas, w, h, now, phase, portX);
        if (geometry != null && charging && !separated) {
            drawPour(canvas, phase, surfaceY, portX, geometry[2], levelColor);
        }
        if (isShown() && animating) postInvalidateOnAnimation();
    }

    private void drawFloor(Canvas canvas, float w, float h) {
        // A very quiet shelf under the pack, so the disconnected cable can rest
        // on something instead of floating at the bottom of the card.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x0CFFFFFF);
        canvas.drawRoundRect(new RectF(body.left + dp(6f), h * 0.955f,
                w - dp(8f), h * 0.958f), dp(2f), dp(2f), paint);
    }

    private void drawTerminal(Canvas canvas) {
        float tw = body.width() * 0.19f;
        terminal.set(body.centerX() - tw / 2f, body.top - getHeight() * 0.055f,
                body.centerX() + tw / 2f, body.top + getHeight() * 0.03f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xA00B0E14);
        canvas.drawRoundRect(terminal, getHeight() * 0.022f, getHeight() * 0.022f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x448BE9FF);
        canvas.drawRoundRect(terminal, getHeight() * 0.022f, getHeight() * 0.022f, paint);
    }

    private void drawPort(Canvas canvas, float portX) {
        port.set(portX - dp(11f), body.top - dp(5f),
                portX + dp(11f), body.top + dp(9f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB20A1016);
        canvas.drawRoundRect(port, dp(4f), dp(4f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x4DFFFFFF);
        canvas.drawRoundRect(port, dp(4f), dp(4f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF03070A);
        canvas.drawRoundRect(new RectF(portX - dp(5f), body.top - dp(1f),
                portX + dp(5f), body.top + dp(6f)), dp(3f), dp(3f), paint);
        paint.setColor(0x308BE9FF);
        canvas.drawCircle(portX, body.top - dp(1.5f), dp(2.4f), paint);
    }

    private void drawLiquid(Canvas canvas, float fillRadius, float surfaceY,
                            float wave, float amp, int levelColor, long now, float level) {
        int segments = 34;
        float tiltSlope = (float) Math.tan(Math.toRadians(liquidTilt));
        surface.reset();
        for (int i = 0; i <= segments; i++) {
            float x = fill.left + fill.width() * i / segments;
            float u = i / (float) segments;
            float y = surfaceY + (x - fill.centerX()) * tiltSlope
                    + amp * (float) Math.sin(wave + u * 7.2f)
                    + amp * 0.34f * (float) Math.sin(wave * 1.7f + u * 15.0f);
            if (i == 0) surface.moveTo(x, y); else surface.lineTo(x, y);
        }

        liquid.set(surface);
        liquid.lineTo(fill.right, fill.bottom);
        liquid.lineTo(fill.left, fill.bottom);
        liquid.close();

        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(fill, fillRadius, fillRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);

        if (level > 0.4f) {
        // Deliberately translucent: the pack internals stay visible through it.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0f, surfaceY, 0f, fill.bottom,
                withAlpha(levelColor, separated ? 0x1E : 0x28),
                withAlpha(levelColor, separated ? 0x46 : 0x62),
                Shader.TileMode.CLAMP));
        paint.setAlpha(0xFF);
        canvas.drawPath(liquid, paint);
        paint.setShader(null);

        // Soft glow just below the surface.
        paint.setShader(new LinearGradient(0f, surfaceY, 0f, surfaceY + dp(28f),
                withAlpha(levelColor, 0x42), 0x00000000, Shader.TileMode.CLAMP));
        paint.setAlpha(0xFF);
        canvas.drawRect(fill.left, surfaceY, fill.right, surfaceY + dp(28f), paint);
        paint.setShader(null);

        // Bubbles rising through the liquid.
        float liquidHeight = Math.max(dp(1f), fill.bottom - surfaceY);
        for (int i = 0; i < 9; i++) {
            float p = ((now % 4200L) / 4200f + i * 0.171f) % 1f;
            float x = fill.left + fill.width() * (0.13f + ((i * 0.37f) % 0.74f));
            float y = fill.bottom - liquidHeight * p;
            float r = dp(0.8f + (i % 3) * 0.55f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (58 * (1f - p))));
            canvas.drawCircle(x, y, r, paint);
        }

        // A glass reflection down the left side.
        paint.setShader(new LinearGradient(fill.left, 0f, fill.left + dp(42f), 0f,
                0x18FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        paint.setAlpha(0xFF);
        canvas.drawRect(fill.left, fill.top, fill.left + dp(42f), fill.bottom, paint);
        paint.setShader(null);
        }

        // The tank's little ecosystem lives behind the surface line.
        drawAquatic(canvas, surfaceY, level, now);

        if (level > 0.4f) {
            // The liquid surface and its highlight.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(1.25f));
            paint.setColor(withAlpha(levelColor, 0xB8));
            canvas.drawPath(surface, paint);
            paint.setStrokeWidth(dp(0.8f));
            paint.setColor(0x55FFFFFF);
            canvas.drawPath(surface, paint);
        }

        canvas.restore();
    }

    private void drawAquatic(Canvas canvas, float surfaceY, float level, long now) {
        float waterHeight = Math.max(0f, fill.bottom - surfaceY);
        drawSeabed(canvas);

        float pitch = gyro != null ? gyro.getPitch() : 0f;
        float driftX = clamp(liquidTilt * fill.width() * 0.0018f
                - gyroRollVelocity * dp(0.04f), dp(18f));
        float driftY = clamp(pitch * dp(0.10f), dp(2.5f));

        canvas.save();
        canvas.translate(driftX, driftY);
        drawWhale(canvas, surfaceY, waterHeight, Math.max(0f, Math.min(1f,
                (15f - level) / 15f)), now / 1000f);
        canvas.restore();

        drawPlants(canvas, waterHeight, now);

        float t = now / 1000f;
        float distress = Math.max(0f, Math.min(1f, (15f - level) / 15f));
        canvas.save();
        canvas.translate(driftX, driftY);
        drawStarfish(canvas, distress, t);
        drawCrab(canvas, waterHeight, distress, t);
        drawSeahorse(canvas, surfaceY, waterHeight, distress, t);
        drawJellyfish(canvas, surfaceY, waterHeight, distress, t);
        drawShark(canvas, surfaceY, waterHeight, distress, t);
        drawOctopus(canvas, surfaceY, waterHeight, distress, t);
        for (int i = 0; i < FISH_COLORS.length; i++) {
            float s = dp(FISH_SIZES[i]);
            float seed = FISH_SEEDS[i];
            float speed = 0.46f + i * 0.11f;
            float swim = (float) Math.sin(t * speed + seed);
            float direction = (float) Math.cos(t * speed + seed);
            float angle = direction >= 0f ? 0f : 180f;
            float x = fill.left + fill.width() * (0.5f + 0.33f * swim);
            float y;
            float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
            if (charging && plugged && !separated && distress < 0.5f) {
                float safeX = fill.left + fill.width() * (0.16f + i * 0.10f);
                x = lerp(x, safeX, 0.45f);
            }
            if (distress > 0.02f) {
                float lowX = fill.left + fill.width() * (0.22f + i * 0.27f)
                        + (float) Math.sin(t * 0.24f + seed) * dp(7f);
                x = lerp(x, lowX, distress);
                float gaspY = Math.min(fill.bottom - s * 0.7f, surfaceY + s * 0.85f);
                y = lerp(surfaceY + Math.max(s * 0.75f,
                        waterHeight * (0.50f - 0.26f * shallow)), gaspY, distress);
                angle += 70f * distress;
                angle += (float) Math.sin(t * 0.8f + seed) * 5f;
            } else {
                y = surfaceY + Math.max(s * 0.75f,
                        waterHeight * (0.50f - 0.24f * shallow))
                        + (float) Math.sin(t * 0.72f + seed * 2.1f)
                        * Math.max(dp(2f), waterHeight * 0.19f);
                angle += (float) Math.sin(t * 1.3f + seed) * 5f;
            }
            float minY = surfaceY + s * 0.65f;
            float maxY = fill.bottom - s * 0.65f;
            if (maxY <= minY) y = fill.bottom - s * 0.55f;
            else y = Math.max(minY, Math.min(maxY, y));
            x = Math.max(fill.left + s * 1.3f,
                    Math.min(fill.right - s * 1.3f, x));
            angle += liquidTilt * 0.45f
                    + clamp(-gyroRollVelocity * 0.022f, 7f);
            float tailSpeed = (5.8f - i * 0.7f) * (1f - distress * 0.9f)
                    * (1f + Math.min(0.8f, Math.abs(gyroRollVelocity) / 260f));
            drawFish(canvas, x, y, s, angle, t * tailSpeed + seed * 3f,
                    FISH_COLORS[i], distress);
            if (distress > 0.35f) drawGaspBubbles(canvas, x, y, s, angle, t, i, distress);
        }
        drawDolphin(canvas, surfaceY, waterHeight, distress, t, 0);
        drawDolphin(canvas, surfaceY, waterHeight, distress, t, 1);
        drawDiver(canvas, surfaceY, waterHeight, distress, t);
        canvas.restore();
    }

    private void drawSeabed(Canvas canvas) {
        // Distant hill.
        seabed.reset();
        seabed.moveTo(fill.left, fill.bottom);
        seabed.lineTo(fill.left, fill.bottom - dp(15f));
        seabed.cubicTo(fill.left + fill.width() * 0.16f, fill.bottom - dp(38f),
                fill.left + fill.width() * 0.31f, fill.bottom - dp(8f),
                fill.left + fill.width() * 0.49f, fill.bottom - dp(18f));
        seabed.cubicTo(fill.left + fill.width() * 0.68f, fill.bottom - dp(30f),
                fill.left + fill.width() * 0.84f, fill.bottom - dp(4f),
                fill.right, fill.bottom - dp(12f));
        seabed.lineTo(fill.right, fill.bottom);
        seabed.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55224B40);
        canvas.drawPath(seabed, paint);

        // Nearer bank.
        seabed.reset();
        seabed.moveTo(fill.left, fill.bottom);
        seabed.lineTo(fill.left, fill.bottom - dp(7f));
        seabed.cubicTo(fill.left + fill.width() * 0.20f, fill.bottom - dp(18f),
                fill.left + fill.width() * 0.38f, fill.bottom - dp(5f),
                fill.left + fill.width() * 0.56f, fill.bottom - dp(10f));
        seabed.cubicTo(fill.left + fill.width() * 0.74f, fill.bottom - dp(16f),
                fill.left + fill.width() * 0.90f, fill.bottom - dp(4f),
                fill.right, fill.bottom - dp(7f));
        seabed.lineTo(fill.right, fill.bottom);
        seabed.close();
        paint.setColor(0x77305246);
        canvas.drawPath(seabed, paint);

        // Broad sandy seabed with a softly rolling top edge.
        seabed.reset();
        sandTop.reset();
        seabed.moveTo(fill.left, fill.bottom);
        seabed.lineTo(fill.left, fill.bottom - dp(8f));
        sandTop.moveTo(fill.left, fill.bottom - dp(8f));
        seabed.cubicTo(fill.left + fill.width() * 0.12f, fill.bottom - dp(17f),
                fill.left + fill.width() * 0.25f, fill.bottom - dp(5f),
                fill.left + fill.width() * 0.40f, fill.bottom - dp(11f));
        sandTop.cubicTo(fill.left + fill.width() * 0.12f, fill.bottom - dp(17f),
                fill.left + fill.width() * 0.25f, fill.bottom - dp(5f),
                fill.left + fill.width() * 0.40f, fill.bottom - dp(11f));
        seabed.cubicTo(fill.left + fill.width() * 0.56f, fill.bottom - dp(18f),
                fill.left + fill.width() * 0.72f, fill.bottom - dp(4f),
                fill.left + fill.width() * 0.86f, fill.bottom - dp(10f));
        sandTop.cubicTo(fill.left + fill.width() * 0.56f, fill.bottom - dp(18f),
                fill.left + fill.width() * 0.72f, fill.bottom - dp(4f),
                fill.left + fill.width() * 0.86f, fill.bottom - dp(10f));
        seabed.cubicTo(fill.left + fill.width() * 0.94f, fill.bottom - dp(12f),
                fill.left + fill.width() * 0.98f, fill.bottom - dp(7f),
                fill.right, fill.bottom - dp(6f));
        sandTop.cubicTo(fill.left + fill.width() * 0.94f, fill.bottom - dp(12f),
                fill.left + fill.width() * 0.98f, fill.bottom - dp(7f),
                fill.right, fill.bottom - dp(6f));
        seabed.lineTo(fill.right, fill.bottom);
        seabed.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB39A8053);
        canvas.drawPath(seabed, paint);

        // Warm lit crest of the sand.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0x66E4CFA0);
        canvas.drawPath(sandTop, paint);

        // Fine sand ripples.
        paint.setStrokeWidth(dp(0.7f));
        for (int i = 0; i < 3; i++) {
            sandTop.reset();
            float y = fill.bottom - dp(3.5f + i * 2.8f);
            sandTop.moveTo(fill.left + fill.width() * (0.05f + i * 0.04f), y);
            for (int j = 1; j <= 4; j++) {
                float x0 = fill.left + fill.width() * (0.05f + j * 0.21f + i * 0.04f);
                sandTop.quadTo(x0 - fill.width() * 0.06f, y - dp(1.2f),
                        x0, y);
            }
            paint.setColor(0x33FFF2C7);
            canvas.drawPath(sandTop, paint);
        }

        // Scattered grains and tiny pebbles.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 22; i++) {
            float gx = fill.left + fill.width()
                    * (0.04f + ((i * 0.171f) % 0.92f));
            float gy = fill.bottom - dp(1.2f + ((i * 0.37f) % 8.5f));
            float gr = dp(0.35f + (i % 3) * 0.28f);
            paint.setColor((i % 4 == 0) ? 0x6638271A : 0x55FFF0C4);
            canvas.drawCircle(gx, gy, gr, paint);
        }
        for (int i = 0; i < 4; i++) {
            float px = fill.left + fill.width() * (0.16f + i * 0.21f);
            float py = fill.bottom - dp(2.0f + (i % 2) * 2f);
            paint.setColor(0x66424F54);
            canvas.drawOval(new RectF(px - dp(2.4f), py - dp(1.2f),
                    px + dp(2.4f), py + dp(1.2f)), paint);
            paint.setColor(0x33FFFFFF);
            canvas.drawOval(new RectF(px - dp(1.4f), py - dp(1.0f),
                    px + dp(0.2f), py - dp(0.1f)), paint);
        }

        // Rocks in a few sizes.
        drawRock(canvas, fill.left + fill.width() * 0.13f, fill.bottom - dp(2f),
                dp(22f), dp(13f), 0xFF4C5B61);
        drawRock(canvas, fill.left + fill.width() * 0.20f, fill.bottom - dp(2f),
                dp(12f), dp(8f), 0xFF5A696E);
        drawRock(canvas, fill.left + fill.width() * 0.47f, fill.bottom - dp(2f),
                dp(15f), dp(9f), 0xFF46555C);
        drawRock(canvas, fill.left + fill.width() * 0.72f, fill.bottom - dp(2f),
                dp(18f), dp(11f), 0xFF536268);
        drawRock(canvas, fill.left + fill.width() * 0.91f, fill.bottom - dp(2f),
                dp(10f), dp(7f), 0xFF405056);
    }

    private void drawRock(Canvas canvas, float x, float y, float w, float h, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        creatureRect.set(x - w / 2f, y - h, x + w / 2f, y);
        canvas.drawOval(creatureRect, paint);
        paint.setColor(0x22FFFFFF);
        canvas.drawOval(new RectF(x - w * 0.30f, y - h * 0.78f,
                x + w * 0.02f, y - h * 0.30f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x66101A1E);
        canvas.drawOval(creatureRect, paint);
    }

    private void drawWhale(Canvas canvas, float surfaceY, float waterHeight,
                           float distress, float t) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(22f);
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        float x = fill.centerX() + fill.width()
                * (0.28f * (float) Math.sin(t * 0.12f + 0.3f));
        float y = surfaceY + Math.max(s * 0.95f,
                waterHeight * (0.37f - 0.14f * shallow))
                + (float) Math.sin(t * 0.34f + 0.3f)
                * Math.max(dp(2f), waterHeight * 0.06f);
        if (charging && plugged && !separated) {
            x = lerp(x, fill.left + fill.width() * 0.38f, 0.18f);
        }
        x = lerp(x, fill.centerX(), distress * 0.75f);
        y = lerp(y, fill.bottom - s * 0.75f, distress);
        x = Math.max(fill.left + s * 1.1f,
                Math.min(fill.right - s * 1.1f, x));
        float minY = surfaceY + s * 0.8f;
        float maxY = fill.bottom - s * 0.75f;
        if (maxY <= minY) y = fill.bottom - s * 0.55f;
        else y = Math.max(minY, Math.min(maxY, y));

        boolean faceLeft = Math.cos(t * 0.12f + 0.3f) < 0f;
        float pitch = (float) Math.sin(t * 0.40f + 0.3f) * 3f + distress * 16f;
        float tail = (float) Math.sin(t * 1.7f + 0.3f)
                * s * 0.18f * (1f - distress * 0.7f);

        canvas.save();
        canvas.translate(x, y);
        if (faceLeft) canvas.scale(-1f, 1f);
        canvas.rotate(pitch);

        whaleTail.reset();
        whaleTail.moveTo(-s * 0.82f, 0f);
        whaleTail.lineTo(-s * 1.34f, -s * 0.40f + tail);
        whaleTail.lineTo(-s * 1.18f, 0f);
        whaleTail.lineTo(-s * 1.34f, s * 0.40f + tail);
        whaleTail.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF417D93, 0xC8));
        canvas.drawPath(whaleTail, paint);

        whaleBody.reset();
        whaleBody.moveTo(-s * 0.88f, 0f);
        whaleBody.cubicTo(-s * 0.42f, -s * 0.40f, s * 0.42f, -s * 0.42f,
                s * 0.98f, -s * 0.05f);
        whaleBody.cubicTo(s * 1.12f, s * 0.04f, s * 0.98f, s * 0.28f,
                s * 0.34f, s * 0.32f);
        whaleBody.cubicTo(-s * 0.22f, s * 0.38f, -s * 0.66f, s * 0.22f,
                -s * 0.88f, 0f);
        whaleBody.close();
        paint.setColor(withAlpha(0xFF3F7E95, 0xC6));
        canvas.drawPath(whaleBody, paint);

        canvas.save();
        canvas.clipPath(whaleBody);
        paint.setColor(0x3DFFFFFF);
        canvas.drawOval(new RectF(-s * 0.42f, s * 0.10f, s * 0.92f, s * 0.38f), paint);
        canvas.restore();

        // Pectoral flipper and dorsal fin.
        whaleFin.reset();
        whaleFin.moveTo(s * 0.10f, s * 0.18f);
        whaleFin.lineTo(-s * 0.12f, s * 0.60f);
        whaleFin.lineTo(s * 0.46f, s * 0.24f);
        whaleFin.close();
        paint.setColor(withAlpha(0xFF346B80, 0xC8));
        canvas.drawPath(whaleFin, paint);
        whaleFin.reset();
        whaleFin.moveTo(-s * 0.22f, -s * 0.30f);
        whaleFin.lineTo(-s * 0.32f, -s * 0.55f);
        whaleFin.lineTo(s * 0.18f, -s * 0.31f);
        whaleFin.close();
        paint.setColor(withAlpha(0xFF346B80, 0xB8));
        canvas.drawPath(whaleFin, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        paint.setColor(0x88C7EFFA);
        canvas.drawPath(whaleBody, paint);

        float ex = s * 0.70f;
        float ey = -s * 0.10f;
        if (distress > 0.5f) {
            paint.setStrokeWidth(dp(1.3f));
            paint.setColor(0xDD101820);
            canvas.drawLine(ex - s * 0.11f, ey - s * 0.11f, ex + s * 0.11f, ey + s * 0.11f, paint);
            canvas.drawLine(ex + s * 0.11f, ey - s * 0.11f, ex - s * 0.11f, ey + s * 0.11f, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6FFFFFF);
            canvas.drawCircle(ex, ey, s * 0.09f, paint);
            paint.setColor(0xFF0B1115);
            canvas.drawCircle(ex + s * 0.025f, ey, s * 0.05f, paint);
        }

        // Blowhole bubbles.
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 3; k++) {
            float p = (t * 0.36f + k * 0.27f) % 1f;
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (55 * (1f - p))));
            canvas.drawCircle(s * 0.20f + (float) Math.sin(t + k) * dp(2f),
                    -s * 0.44f - p * s * 1.1f, dp(0.8f + k * 0.4f), paint);
        }
        canvas.restore();
    }

    private void drawDolphin(Canvas canvas, float surfaceY, float waterHeight,
                             float distress, float t, int index) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(index == 0 ? 12.8f : 9.8f);
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        float phase = index == 0 ? 0.7f : 3.3f;
        float speed = index == 0 ? 0.22f : 0.19f;
        float swing = (float) Math.sin(t * speed + phase);
        float x = fill.centerX() + fill.width()
                * ((index == 0 ? 0.29f : -0.27f) * swing);
        float y = surfaceY + Math.max(s * 0.9f,
                waterHeight * ((index == 0 ? 0.29f : 0.40f) - 0.10f * shallow))
                + (float) Math.sin(t * 0.55f + phase + 1.0f)
                * Math.max(dp(2f), waterHeight * 0.07f);
        if (charging && plugged && !separated) {
            x = lerp(x, fill.left + fill.width() * (index == 0 ? 0.40f : 0.22f), 0.30f);
        }
        x = lerp(x, fill.left + fill.width() * (index == 0 ? 0.40f : 0.58f),
                distress * 0.70f);
        y = lerp(y, fill.bottom - s * 0.75f, distress);
        x = Math.max(fill.left + s * 1.2f,
                Math.min(fill.right - s * 1.2f, x));
        float minY = surfaceY + s * 0.65f;
        float maxY = fill.bottom - s * 0.70f;
        if (maxY <= minY) y = fill.bottom - s * 0.55f;
        else y = Math.max(minY, Math.min(maxY, y));

        // A periodic barrel roll/flip. The 360th and 0th degrees look the same.
        float period = 4.8f + index * 1.1f;
        float fp = ((t + phase * 1.7f) % period) / period;
        float spin = 0f;
        if (fp < 0.34f && distress < 0.25f) {
            float q = fp / 0.34f;
            float ease = q * q * (3f - 2f * q);
            spin = 360f * ease;
            y -= (float) Math.sin(q * Math.PI) * s * 0.45f;
        }

        boolean faceLeft = Math.cos(t * speed + phase) < 0f;
        float pitch = (float) Math.sin(t * 0.7f + phase) * 4f + distress * 28f
                + liquidTilt * 0.40f - gyroRollVelocity * 0.018f;
        float kick = (float) Math.sin(t * 5.2f + phase)
                * s * 0.22f * (1f - distress * 0.85f);

        canvas.save();
        canvas.translate(x, y);
        if (faceLeft) canvas.scale(-1f, 1f);
        canvas.rotate(pitch + spin);

        dolphinTail.reset();
        dolphinTail.moveTo(-s * 0.85f, 0f);
        dolphinTail.lineTo(-s * 1.42f, -s * 0.42f + kick);
        dolphinTail.lineTo(-s * 1.22f, 0f);
        dolphinTail.lineTo(-s * 1.42f, s * 0.42f + kick);
        dolphinTail.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF50A6C3, 0xE0));
        canvas.drawPath(dolphinTail, paint);

        dolphinBody.reset();
        dolphinBody.moveTo(-s * 0.88f, 0f);
        dolphinBody.cubicTo(-s * 0.42f, -s * 0.30f, s * 0.30f, -s * 0.34f,
                s * 0.84f, -s * 0.08f);
        dolphinBody.lineTo(s * 1.16f, -s * 0.02f);
        dolphinBody.lineTo(s * 0.86f, s * 0.07f);
        dolphinBody.cubicTo(s * 0.28f, s * 0.28f, -s * 0.42f, s * 0.25f,
                -s * 0.88f, 0f);
        dolphinBody.close();
        paint.setColor(withAlpha(0xFF58B2CF, 0xE6));
        canvas.drawPath(dolphinBody, paint);

        canvas.save();
        canvas.clipPath(dolphinBody);
        paint.setColor(0x44FFFFFF);
        canvas.drawOval(new RectF(-s * 0.35f, s * 0.06f, s * 0.88f, s * 0.30f), paint);
        canvas.restore();

        dolphinFin.reset();
        dolphinFin.moveTo(-s * 0.10f, -s * 0.24f);
        dolphinFin.lineTo(-s * 0.26f, -s * 0.60f);
        dolphinFin.lineTo(s * 0.20f, -s * 0.26f);
        dolphinFin.close();
        paint.setColor(withAlpha(0xFF4397B5, 0xE0));
        canvas.drawPath(dolphinFin, paint);
        dolphinFin.reset();
        dolphinFin.moveTo(s * 0.06f, s * 0.13f);
        dolphinFin.lineTo(-s * 0.12f, s * 0.48f);
        dolphinFin.lineTo(s * 0.35f, s * 0.18f);
        dolphinFin.close();
        canvas.drawPath(dolphinFin, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.9f));
        paint.setColor(0x99DFF8FF);
        canvas.drawPath(dolphinBody, paint);

        float ex = s * 0.66f;
        float ey = -s * 0.08f;
        if (distress > 0.5f) {
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0xDD101820);
            canvas.drawLine(ex - s * 0.10f, ey - s * 0.10f, ex + s * 0.10f, ey + s * 0.10f, paint);
            canvas.drawLine(ex + s * 0.10f, ey - s * 0.10f, ex - s * 0.10f, ey + s * 0.10f, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6FFFFFF);
            canvas.drawCircle(ex, ey, s * 0.08f, paint);
            paint.setColor(0xFF0B1115);
            canvas.drawCircle(ex + s * 0.02f, ey, s * 0.04f, paint);
        }
        canvas.restore();
    }

    private void drawPlants(Canvas canvas, float waterHeight, long now) {
        float baseY = fill.bottom - dp(2.5f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);

        // Dense seagrass beds with different heights, tones and leaf shapes.
        int plantCount = 9;
        for (int i = 0; i < plantCount; i++) {
            float fx = 0.10f + i * (0.80f / (plantCount - 1));
            float x = fill.left + fill.width() * fx;
            float maxHeight = dp(22f + ((i * 7) % 5) * 9f);
            float factor = 0.34f + ((i * 3) % 5) * 0.12f;
            float height = Math.max(dp(2f), Math.min(maxHeight, waterHeight * factor));
            int color = (i % 3 == 0) ? 0xFF7BE8B2
                    : (i % 3 == 1) ? 0xFF4FD6B0 : 0xFFA6E58C;

            // Two short side blades make a clump rather than one lonely stem.
            for (int b = 0; b < 2; b++) {
                float off = (b == 0 ? -1f : 1f) * dp(3.2f);
                float bh = height * (0.35f + b * 0.12f);
                float tip = (float) Math.sin(now / 1200f + i * 1.6f + b)
                        * dp(2.5f);
                paint.setStrokeWidth(dp(1.3f));
                paint.setColor(withAlpha(color, 0x52));
                canvas.drawLine(x + off, baseY, x + off + tip, baseY - bh, paint);
            }

            plantPath.reset();
            plantPath.moveTo(x, baseY);
            int steps = 5;
            for (int j = 1; j <= steps; j++) {
                float p = j / (float) steps;
                float sway = (float) Math.sin(now / 1400f + i * 1.3f)
                        * dp(4.5f) * p
                        + (float) Math.sin(now / 850f + i * 2.0f + p * 3f)
                        * dp(1.8f) * p;
                plantPath.lineTo(x + sway, baseY - height * p);
            }
            paint.setStrokeWidth(dp(1.9f + (i % 3) * 0.35f));
            paint.setColor(withAlpha(color, 0x76));
            canvas.drawPath(plantPath, paint);

            // Broad paired leaves.
            int leaves = 3 + (i % 3);
            paint.setStyle(Paint.Style.FILL);
            for (int j = 1; j <= leaves; j++) {
                float p = j / (float) (leaves + 1);
                float sway = (float) Math.sin(now / 1400f + i * 1.3f)
                        * dp(4.5f) * p;
                float y = baseY - height * p;
                float leaf = dp(5.2f + (i % 2) * 1.8f);
                for (int side = -1; side <= 1; side += 2) {
                    canvas.save();
                    canvas.rotate(side * 30f, x + sway, y);
                    paint.setColor(withAlpha(color, 0x58));
                    creatureRect.set(x + sway + side * leaf - leaf,
                            y - dp(1.5f), x + sway + side * leaf + leaf, y + dp(1.5f));
                    canvas.drawOval(creatureRect, paint);
                    canvas.restore();
                }
            }
            paint.setStyle(Paint.Style.STROKE);
        }

        // Two branching coral heads at the edges.
        drawCoral(canvas, fill.left + fill.width() * 0.945f, baseY,
                Math.max(dp(3f), waterHeight * 0.56f), now, 0xFFFF9EBA);
        drawCoral(canvas, fill.left + fill.width() * 0.025f, baseY,
                Math.max(dp(2f), waterHeight * 0.42f), now, 0xFFFFB07A);
    }

    private void drawCoral(Canvas canvas, float x, float baseY, float height,
                           long now, int color) {
        float sway = (float) Math.sin(now / 1600f + x * 0.07f) * dp(2.4f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int k = 0; k < 3; k++) {
            float spread = (k - 1) * height * 0.30f;
            float tipX = x + spread + sway;
            float tipY = baseY - height * (0.62f + 0.16f * k);
            plantPath.reset();
            plantPath.moveTo(x + spread * 0.18f, baseY);
            plantPath.cubicTo(x + spread * 0.32f, baseY - height * 0.34f,
                    tipX - sway * 0.30f, tipY + height * 0.24f, tipX, tipY);
            paint.setStrokeWidth(dp(2.5f - k * 0.25f));
            paint.setColor(withAlpha(color, 0x8A));
            canvas.drawPath(plantPath, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(color, 0xA8));
            canvas.drawCircle(tipX, tipY, dp(1.9f), paint);
            paint.setStyle(Paint.Style.STROKE);
        }
    }

    private void drawShark(Canvas canvas, float surfaceY, float waterHeight,
                          float distress, float t) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(18f);
        float swim = (float) Math.sin(t * 0.23f + 0.8f);
        float x = fill.centerX() + fill.width() * 0.33f * swim;
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        float y = surfaceY + Math.max(s * 0.75f,
                waterHeight * (0.45f - 0.20f * shallow))
                + (float) Math.sin(t * 0.4f + 0.8f)
                * Math.max(dp(2f), waterHeight * 0.08f);
        x = lerp(x, fill.left + fill.width() * 0.32f, distress);
        if (charging && plugged && !separated && distress < 0.5f) {
            x = lerp(x, fill.left + fill.width() * 0.22f, 0.25f);
        }
        y = lerp(y, fill.bottom - s * 0.8f, distress);
        float minY = surfaceY + s * 0.7f;
        float maxY = fill.bottom - s * 0.8f;
        if (maxY <= minY) y = fill.bottom - s * 0.6f;
        else y = Math.max(minY, Math.min(maxY, y));
        x = Math.max(fill.left + s * 1.2f,
                Math.min(fill.right - s * 1.2f, x));

        boolean faceLeft = Math.cos(t * 0.23f + 0.8f) < 0f;
        float pitch = (float) Math.sin(t * 0.7f + 0.8f) * 4f + distress * 38f
                + liquidTilt * 0.35f - gyroRollVelocity * 0.018f;
        float tail = (float) Math.sin(t * 3.0f * (1f - distress * 0.8f) + 0.8f)
                * s * 0.35f;

        canvas.save();
        canvas.translate(x, y);
        if (faceLeft) canvas.scale(-1f, 1f);
        canvas.rotate(pitch);

        // Tail fin.
        sharkTail.reset();
        sharkTail.moveTo(-s * 0.82f, 0f);
        sharkTail.lineTo(-s * 1.48f, -s * 0.45f + tail);
        sharkTail.lineTo(-s * 1.20f, 0f + tail * 0.25f);
        sharkTail.lineTo(-s * 1.48f, s * 0.45f + tail);
        sharkTail.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF78B4C8, 0xD0));
        canvas.drawPath(sharkTail, paint);

        // Dorsal fin.
        sharkFin.reset();
        sharkFin.moveTo(-s * 0.25f, -s * 0.25f);
        sharkFin.lineTo(-s * 0.42f, -s * 0.75f);
        sharkFin.lineTo(s * 0.20f, -s * 0.26f);
        sharkFin.close();
        paint.setColor(withAlpha(0xFF6FA8BD, 0xD8));
        canvas.drawPath(sharkFin, paint);

        // Sleek body.
        float undulate = (float) Math.sin(t * 2.2f + 0.8f)
                * s * 0.07f * (1f - distress * 0.75f);
        sharkBody.reset();
        sharkBody.moveTo(-s * 0.88f, 0f);
        sharkBody.cubicTo(-s * 0.45f, -s * 0.34f + undulate,
                s * 0.35f, -s * 0.36f - undulate, s * 0.92f, -s * 0.06f);
        sharkBody.cubicTo(s * 1.08f, s * 0.02f, s * 0.96f, s * 0.22f, s * 0.36f, s * 0.30f);
        sharkBody.cubicTo(-s * 0.20f, s * 0.38f - undulate,
                -s * 0.62f, s * 0.22f + undulate, -s * 0.88f, 0f);
        sharkBody.close();
        paint.setColor(withAlpha(0xFF8EC9DB, 0xDE));
        canvas.drawPath(sharkBody, paint);

        // Pale belly.
        canvas.save();
        canvas.clipPath(sharkBody);
        paint.setColor(0x46FFFFFF);
        canvas.drawOval(new RectF(-s * 0.45f, s * 0.08f, s * 0.92f, s * 0.38f), paint);
        canvas.restore();

        // Pectoral fin.
        sharkFin.reset();
        sharkFin.moveTo(s * 0.10f, s * 0.16f);
        sharkFin.lineTo(-s * 0.08f, s * 0.58f);
        sharkFin.lineTo(s * 0.48f, s * 0.22f);
        sharkFin.close();
        paint.setColor(withAlpha(0xFF6FA8BD, 0xB8));
        canvas.drawPath(sharkFin, paint);

        // Gill lines.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.9f));
        paint.setColor(0x55152A31);
        for (int i = 0; i < 3; i++) {
            float gx = s * 0.22f + i * s * 0.10f;
            canvas.drawLine(gx, -s * 0.12f, gx - s * 0.03f, s * 0.12f, paint);
        }

        // Outline.
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0x99D9F6FF);
        canvas.drawPath(sharkBody, paint);

        // Eye or X.
        float ex = s * 0.65f;
        float ey = -s * 0.10f;
        if (distress > 0.5f) {
            paint.setStrokeWidth(dp(1.3f));
            paint.setColor(0xDD101820);
            canvas.drawLine(ex - s * 0.13f, ey - s * 0.13f, ex + s * 0.13f, ey + s * 0.13f, paint);
            canvas.drawLine(ex + s * 0.13f, ey - s * 0.13f, ex - s * 0.13f, ey + s * 0.13f, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6FFFFFF);
            canvas.drawCircle(ex, ey, s * 0.10f, paint);
            paint.setColor(0xFF0B1115);
            canvas.drawCircle(ex + s * 0.025f, ey, s * 0.055f, paint);
        }

        canvas.restore();
        if (distress > 0.35f) {
            drawGaspBubbles(canvas, x, y, s, faceLeft ? 180f : 0f, t, 5, distress);
        }
    }

    private void drawOctopus(Canvas canvas, float surfaceY, float waterHeight,
                             float distress, float t) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(13.5f);
        float x = fill.centerX() + fill.width() * 0.27f * (float) Math.sin(t * 0.29f + 2.2f);
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        float y = surfaceY + Math.max(s * 1.0f,
                waterHeight * (0.58f - 0.30f * shallow))
                + (float) Math.sin(t * 0.47f + 2.2f)
                * Math.max(dp(2f), waterHeight * 0.08f);
        x = lerp(x, fill.left + fill.width() * 0.71f, distress);
        if (charging && plugged && !separated && distress < 0.5f) {
            x = lerp(x, fill.left + fill.width() * 0.60f, 0.25f);
        }
        y = lerp(y, fill.bottom - s * 0.9f, distress);
        float minY = surfaceY + s * 1.0f;
        float maxY = fill.bottom - s * 0.9f;
        if (maxY <= minY) y = fill.bottom - s * 0.65f;
        else y = Math.max(minY, Math.min(maxY, y));
        x = Math.max(fill.left + s * 1.2f,
                Math.min(fill.right - s * 1.2f, x));
        float angle = (float) Math.sin(t * 0.6f + 2.2f) * 5f + distress * 22f
                + liquidTilt * 0.40f - gyroRollVelocity * 0.018f;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);

        // Tentacles move behind the mantle.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int k = 0; k < 5; k++) {
            float baseX = -s * 0.46f + k * s * 0.23f;
            float baseY = s * 0.30f;
            float sway = (float) Math.sin(t * 2.2f + k * 1.25f + 2.2f)
                    * s * 0.28f * (1f - distress * 0.82f);
            float drop = s * (1.30f - distress * 0.55f);
            float endX = baseX + (k - 2) * s * 0.30f + sway;
            float endY = drop + (float) Math.cos(t * 1.9f + k * 1.1f + 2.2f)
                    * s * 0.12f * (1f - distress * 0.9f);
            octoArm.reset();
            octoArm.moveTo(baseX, baseY);
            octoArm.cubicTo(baseX + s * 0.08f, baseY + s * 0.38f,
                    endX + sway * 0.35f, endY - s * 0.28f, endX, endY);
            paint.setStrokeWidth(dp(2.35f - k * 0.08f));
            paint.setColor(withAlpha(0xFFD77BB4, 0xC4));
            canvas.drawPath(octoArm, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFFE99ACB, 0xA8));
            canvas.drawCircle(endX, endY, dp(1.25f), paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        // Mantle, gently pulsing.
        float pulse = (float) Math.sin(t * 2.0f + 2.2f);
        float mw = s * (1f + 0.055f * pulse);
        float mh = s * (0.96f - 0.045f * pulse);
        octoBody.reset();
        octoBody.moveTo(0f, -mh * 0.98f);
        octoBody.cubicTo(mw * 0.82f, -mh * 0.72f, mw * 0.80f, mh * 0.12f,
                mw * 0.50f, mh * 0.40f);
        octoBody.cubicTo(mw * 0.24f, mh * 0.60f, -mw * 0.24f, mh * 0.60f,
                -mw * 0.50f, mh * 0.40f);
        octoBody.cubicTo(-mw * 0.80f, mh * 0.12f, -mw * 0.82f, -mh * 0.72f,
                0f, -mh * 0.98f);
        octoBody.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFFE88BC0, 0xD8));
        canvas.drawPath(octoBody, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        paint.setColor(0x66FFE0F0);
        canvas.drawPath(octoBody, paint);

        // Eye pair.
        if (distress > 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0xDD101820);
            for (int side = -1; side <= 1; side += 2) {
                float ex = side * s * 0.26f;
                float ey = -s * 0.08f;
                canvas.drawLine(ex - s * 0.10f, ey - s * 0.10f, ex + s * 0.10f, ey + s * 0.10f, paint);
                canvas.drawLine(ex + s * 0.10f, ey - s * 0.10f, ex - s * 0.10f, ey + s * 0.10f, paint);
            }
        } else {
            paint.setStyle(Paint.Style.FILL);
            for (int side = -1; side <= 1; side += 2) {
                float ex = side * s * 0.26f;
                float ey = -s * 0.08f;
                paint.setColor(0xE6FFFFFF);
                canvas.drawCircle(ex, ey, s * 0.15f, paint);
                paint.setColor(0xFF0B1115);
                canvas.drawCircle(ex + side * s * 0.035f, ey, s * 0.07f, paint);
            }
        }

        paint.setColor(0x32FFFFFF);
        canvas.drawOval(new RectF(-s * 0.42f, -s * 0.60f, s * 0.08f, -s * 0.25f), paint);
        canvas.restore();
    }

    private void drawJellyfish(Canvas canvas, float surfaceY, float waterHeight,
                               float distress, float t) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(10f);
        float x = fill.centerX() + fill.width() * 0.31f * (float) Math.sin(t * 0.21f + 4.0f);
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        float y = surfaceY + Math.max(s * 1.1f,
                waterHeight * (0.28f - 0.10f * shallow))
                + (float) Math.sin(t * 0.8f + 4.0f)
                * Math.max(dp(2f), waterHeight * 0.08f);
        x = lerp(x, fill.left + fill.width() * 0.50f, distress);
        if (charging && plugged && !separated && distress < 0.5f) {
            x = lerp(x, fill.left + fill.width() * 0.46f, 0.20f);
        }
        y = lerp(y, fill.bottom - s * 0.75f, distress);
        x = Math.max(fill.left + s,
                Math.min(fill.right - s, x));

        float pulse = (float) Math.sin(t * 2.1f + 4.0f);
        float w = s * (1f + 0.13f * pulse);
        float h = s * (0.80f + 0.08f * pulse) * (1f - distress * 0.20f);

        canvas.save();
        canvas.translate(x, y);

        // Trailing tentacles.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int k = 0; k < 4; k++) {
            float startX = -w * 0.58f + k * w * 0.38f;
            float sway = (float) Math.sin(t * 1.8f + k * 1.4f + 4.0f)
                    * dp(3.2f) * (1f - distress * 0.75f);
            float endX = startX + sway;
            float endY = h * 1.9f * (1f - distress * 0.28f)
                    + (float) Math.cos(t * 1.6f + k) * dp(2.2f) * (1f - distress * 0.8f);
            jellyArm.reset();
            jellyArm.moveTo(startX, h * 0.25f);
            jellyArm.cubicTo(startX + sway * 0.4f, h * 0.85f,
                    endX - sway * 0.5f, endY - h * 0.45f, endX, endY);
            paint.setStrokeWidth(dp(1.0f));
            paint.setColor(withAlpha(0xFF9ADFFF, 0x68));
            canvas.drawPath(jellyArm, paint);
        }

        // Bell.
        jellyBell.reset();
        jellyBell.moveTo(-w, 0f);
        jellyBell.cubicTo(-w * 1.02f, -h * 1.65f, w * 1.02f, -h * 1.65f, w, 0f);
        jellyBell.cubicTo(w * 0.66f, h * 0.22f, -w * 0.66f, h * 0.22f, -w, 0f);
        jellyBell.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0f, -h, 0f, h * 0.2f,
                withAlpha(0xFFB5E9FF, 0x66), withAlpha(0xFF8BE9FF, 0x1C),
                Shader.TileMode.CLAMP));
        paint.setAlpha(0xFF);
        canvas.drawPath(jellyBell, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.9f));
        paint.setColor(withAlpha(0xFFB5E9FF, 0x7A));
        canvas.drawPath(jellyBell, paint);

        // Soft ribs in the bell.
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(0x339ADFFF);
        for (int k = -1; k <= 1; k++) {
            canvas.drawLine(k * w * 0.40f, -h * 0.90f, k * w * 0.55f, h * 0.10f, paint);
        }
        canvas.restore();
    }

    private void drawDiver(Canvas canvas, float surfaceY, float waterHeight,
                           float distress, float t) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(13.2f);
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        // Keep the diver in a separate right-hand lane so it does not disappear
        // into the shark or octopus when the tank is full.
        float x = fill.left + fill.width()
                * (0.78f + 0.045f * (float) Math.sin(t * 0.23f + 1.7f));
        float y = surfaceY + Math.max(s * 0.9f,
                waterHeight * (0.58f - 0.20f * shallow))
                + (float) Math.sin(t * 0.5f + 1.7f)
                * Math.max(dp(2f), waterHeight * 0.06f);
        if (charging && plugged && !separated) {
            x = lerp(x, fill.left + fill.width() * 0.52f, 0.42f);
        }
        x = lerp(x, fill.left + fill.width() * 0.68f, distress * 0.65f);
        y = lerp(y, fill.bottom - s * 0.8f, distress);
        x = Math.max(fill.left + s * 1.2f,
                Math.min(fill.right - s * 1.2f, x));
        float minY = surfaceY + s * 0.65f;
        float maxY = fill.bottom - s * 0.75f;
        if (maxY <= minY) y = fill.bottom - s * 0.55f;
        else y = Math.max(minY, Math.min(maxY, y));
        boolean faceLeft = Math.cos(t * 0.23f + 1.7f) < 0f;
        float pitch = (float) Math.sin(t * 0.5f + 1.7f) * 4f + distress * 30f
                + liquidTilt * 0.40f - gyroRollVelocity * 0.018f;
        float kick = (float) Math.sin(t * 4.1f + 1.7f)
                * s * 0.30f * (1f - distress * 0.85f);

        canvas.save();
        canvas.translate(x, y);
        if (faceLeft) canvas.scale(-1f, 1f);
        canvas.rotate(pitch);

        // Dive light cone.
        if (distress < 0.6f) {
            diverFin.reset();
            diverFin.moveTo(s * 0.92f, -s * 0.24f);
            diverFin.lineTo(s * 3.1f, -s * 0.82f);
            diverFin.lineTo(s * 3.1f, s * 0.42f);
            diverFin.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x12FFE9A6);
            canvas.drawPath(diverFin, paint);
        }

        // Legs and fins.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int side = -1; side <= 1; side += 2) {
            float hipX = -s * 0.46f;
            float hipY = s * 0.08f + side * s * 0.10f;
            float kneeX = -s * 0.82f;
            float kneeY = hipY + side * s * 0.10f + kick * side;
            float footX = -s * 1.16f;
            float footY = kneeY + side * s * 0.04f - kick * side * 0.55f;
            paint.setStrokeWidth(dp(2.0f));
            paint.setColor(0xFF1C3D4A);
            canvas.drawLine(hipX, hipY, kneeX, kneeY, paint);
            canvas.drawLine(kneeX, kneeY, footX, footY, paint);

            diverFin.reset();
            diverFin.moveTo(footX, footY - s * 0.12f);
            diverFin.lineTo(footX - s * 0.42f, footY - s * 0.34f + kick * 0.4f);
            diverFin.lineTo(footX - s * 0.34f, footY + s * 0.18f);
            diverFin.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFFF0A35E, 0xE6));
            canvas.drawPath(diverFin, paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        // Air tank.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xD9FFC857);
        canvas.drawRoundRect(new RectF(-s * 0.35f, -s * 0.68f,
                s * 0.01f, -s * 0.02f), dp(3f), dp(3f), paint);
        paint.setColor(0x66FFFFFF);
        canvas.drawRoundRect(new RectF(-s * 0.34f, -s * 0.66f,
                -s * 0.16f, -s * 0.04f), dp(2f), dp(2f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x99FFE7A8);
        canvas.drawRoundRect(new RectF(-s * 0.35f, -s * 0.68f,
                s * 0.01f, -s * 0.02f), dp(3f), dp(3f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFD9D9D9);
        canvas.drawCircle(-s * 0.16f, -s * 0.70f, s * 0.08f, paint);

        // Body in a dark wetsuit.
        diverBody.reset();
        diverBody.moveTo(-s * 0.62f, -s * 0.14f);
        diverBody.cubicTo(-s * 0.30f, -s * 0.44f, s * 0.34f, -s * 0.44f,
                s * 0.64f, -s * 0.14f);
        diverBody.cubicTo(s * 0.78f, s * 0.18f, s * 0.26f, s * 0.38f,
                -s * 0.34f, s * 0.28f);
        diverBody.cubicTo(-s * 0.64f, s * 0.18f, -s * 0.74f, s * 0.02f,
                -s * 0.62f, -s * 0.14f);
        diverBody.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE62A6374);
        canvas.drawPath(diverBody, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        paint.setColor(0xAAAEEBFA);
        canvas.drawPath(diverBody, paint);

        // Orange chest stripe.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.3f));
        paint.setColor(0xB3F08A5D);
        canvas.drawLine(-s * 0.24f, -s * 0.20f, s * 0.34f, -s * 0.12f, paint);

        // Arms.
        paint.setStrokeWidth(dp(2.0f));
        paint.setColor(0xFF2A6374);
        canvas.drawLine(s * 0.18f, -s * 0.03f, s * 0.82f, -s * 0.25f, paint);
        paint.setColor(0xFF2C5D6B);
        canvas.drawLine(-s * 0.10f, s * 0.16f, s * 0.30f, s * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE8B88E);
        canvas.drawCircle(s * 0.86f, -s * 0.27f, s * 0.12f, paint);

        // Hood, mask and regulator.
        paint.setColor(0xE62C5D6B);
        canvas.drawCircle(s * 0.68f, -s * 0.22f, s * 0.34f, paint);
        paint.setColor(0xE68BE9FF);
        canvas.drawOval(new RectF(s * 0.55f, -s * 0.37f,
                s * 0.98f, -s * 0.05f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.0f));
        paint.setColor(0xCCE8FBFF);
        canvas.drawOval(new RectF(s * 0.55f, -s * 0.37f,
                s * 0.98f, -s * 0.05f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0B1115);
        canvas.drawCircle(s * 0.78f, -s * 0.21f, s * 0.08f, paint);
        paint.setColor(0xFF9AABB2);
        canvas.drawRoundRect(new RectF(s * 0.85f, -s * 0.05f,
                s * 1.03f, s * 0.06f), dp(1.5f), dp(1.5f), paint);

        // Regulator hose.
        diverHose.reset();
        diverHose.moveTo(-s * 0.18f, -s * 0.68f);
        diverHose.cubicTo(-s * 0.55f, -s * 0.95f, s * 0.38f, s * 0.08f,
                s * 0.88f, -s * 0.01f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0xCC1A252B);
        canvas.drawPath(diverHose, paint);

        if (distress > 0.5f) {
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0xDD101820);
            canvas.drawLine(s * 0.66f, -s * 0.30f, s * 0.86f, -s * 0.12f, paint);
            canvas.drawLine(s * 0.86f, -s * 0.30f, s * 0.66f, -s * 0.12f, paint);
        }
        canvas.restore();

        // Rising exhaust bubbles from the regulator.
        float mouthX = x + (faceLeft ? -1f : 1f) * s * 0.92f;
        float mouthY = y - s * 0.06f;
        paint.setStyle(Paint.Style.FILL);
        int bubbles = distress > 0.5f ? 2 : 4;
        for (int k = 0; k < bubbles; k++) {
            float p = (t * 0.55f + k * 0.26f) % 1f;
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (80 * (1f - p))));
            canvas.drawCircle(mouthX + (float) Math.sin(k * 1.7f + p * 5f) * dp(2.2f),
                    mouthY - p * s * 3.0f, dp(0.8f + (k % 2) * 0.6f), paint);
        }
    }

    private void drawCrab(Canvas canvas, float waterHeight, float distress, float t) {
        float s = dp(7.2f);
        float x = fill.left + fill.width()
                * (0.17f + 0.12f * (float) Math.sin(t * 0.30f + 3.1f));
        if (charging && plugged && !separated) {
            x = lerp(x, fill.left + fill.width() * 0.12f, 0.3f);
        }
        x = lerp(x, fill.left + fill.width() * 0.24f, distress);
        float y = fill.bottom - dp(6.2f);
        float angle = (float) Math.sin(t * 0.9f + 3.1f) * 4f + distress * 8f
                + liquidTilt * 0.45f - gyroRollVelocity * 0.018f;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);

        // Legs scuttle sideways.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(1.35f));
        paint.setColor(withAlpha(0xFFD97852, 0xD0));
        for (int side = -1; side <= 1; side += 2) {
            for (int leg = 0; leg < 3; leg++) {
                float baseX = side * s * 0.45f;
                float baseY = -s * 0.05f + leg * s * 0.16f;
                float step = (float) Math.sin(t * 8f + leg * 2.1f + side * 1.3f)
                        * s * 0.14f;
                float footX = side * (s * 0.86f + leg * s * 0.18f) + step;
                float footY = s * 0.52f + leg * s * 0.08f;
                canvas.drawLine(baseX, baseY, footX, footY, paint);
                canvas.drawLine(footX, footY, footX + side * s * 0.12f,
                        footY + s * 0.10f, paint);
            }
        }

        // Claws, raised when the water is low.
        for (int side = -1; side <= 1; side += 2) {
            float raise = distress * s * 0.28f;
            float armX = side * s * 0.58f;
            float armY = -s * 0.15f - raise;
            float clawX = side * s * 1.02f;
            float clawY = -s * 0.32f - raise;
            paint.setStrokeWidth(dp(1.8f));
            paint.setColor(withAlpha(0xFFE7865E, 0xD8));
            canvas.drawLine(armX, armY, clawX, clawY, paint);
            float open = (float) Math.sin(t * 2.2f + side) * s * 0.08f
                    + distress * s * 0.08f;
            paint.setStrokeWidth(dp(1.3f));
            canvas.drawLine(clawX, clawY, clawX + side * s * 0.22f,
                    clawY - s * 0.20f - open, paint);
            canvas.drawLine(clawX, clawY, clawX + side * s * 0.26f,
                    clawY + s * 0.08f + open, paint);
        }

        // Shell.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF08A5D);
        creatureRect.set(-s * 0.72f, -s * 0.48f, s * 0.72f, s * 0.38f);
        canvas.drawOval(creatureRect, paint);
        paint.setColor(0x35FFFFFF);
        canvas.drawOval(new RectF(-s * 0.45f, -s * 0.38f,
                s * 0.22f, -s * 0.06f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.9f));
        paint.setColor(0x99FFD2B8);
        canvas.drawOval(creatureRect, paint);

        // Eye stalks.
        paint.setColor(0xCCF08A5D);
        canvas.drawLine(-s * 0.22f, -s * 0.42f, -s * 0.30f, -s * 0.82f, paint);
        canvas.drawLine(s * 0.22f, -s * 0.42f, s * 0.30f, -s * 0.82f, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int side = -1; side <= 1; side += 2) {
            float ex = side * s * 0.30f;
            float ey = -s * 0.84f;
            if (distress > 0.5f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.1f));
                paint.setColor(0xDD101820);
                canvas.drawLine(ex - s * 0.09f, ey - s * 0.09f, ex + s * 0.09f, ey + s * 0.09f, paint);
                canvas.drawLine(ex + s * 0.09f, ey - s * 0.09f, ex - s * 0.09f, ey + s * 0.09f, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                paint.setColor(0xE6FFFFFF);
                canvas.drawCircle(ex, ey, s * 0.13f, paint);
                paint.setColor(0xFF0B1115);
                canvas.drawCircle(ex, ey, s * 0.06f, paint);
            }
        }
        canvas.restore();
        if (distress > 0.35f) {
            drawGaspBubbles(canvas, x, y, s, 0f, t, 8, distress);
        }
    }

    private void drawSeahorse(Canvas canvas, float surfaceY, float waterHeight,
                              float distress, float t) {
        float level = Math.max(0f, Math.min(100f,
                (fill.bottom - surfaceY) / Math.max(1f, fill.height()) * 100f));
        float s = dp(8.2f);
        float shallow = Math.max(0f, Math.min(1f, (30f - level) / 30f));
        float x = fill.centerX() + fill.width()
                * (0.22f * (float) Math.sin(t * 0.26f + 5.2f));
        float y = surfaceY + Math.max(s * 1.6f,
                waterHeight * (0.47f - 0.25f * shallow))
                + (float) Math.sin(t * 0.7f + 5.2f)
                * Math.max(dp(2f), waterHeight * 0.07f);
        x = lerp(x, fill.left + fill.width() * 0.82f, distress);
        if (charging && plugged && !separated && distress < 0.5f) {
            x = lerp(x, fill.left + fill.width() * 0.72f, 0.35f);
        }
        y = lerp(y, fill.bottom - s * 1.35f, distress);
        x = Math.max(fill.left + s * 1.2f,
                Math.min(fill.right - s * 1.2f, x));
        float angle = (float) Math.sin(t * 0.8f + 5.2f) * 4f + distress * 25f
                + liquidTilt * 0.45f - gyroRollVelocity * 0.020f;

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);

        // Curled body and tail.
        seahorseBody.reset();
        seahorseBody.moveTo(0f, -s * 1.45f);
        seahorseBody.cubicTo(s * 0.58f, -s * 1.45f, s * 0.64f, -s * 0.72f,
                s * 0.20f, -s * 0.30f);
        seahorseBody.cubicTo(-s * 0.10f, s * 0.00f, -s * 0.38f, s * 0.18f,
                -s * 0.24f, s * 0.55f);
        seahorseBody.cubicTo(-s * 0.08f, s * 0.92f, -s * 0.58f, s * 1.10f,
                -s * 0.58f, s * 0.78f);
        seahorseBody.cubicTo(-s * 0.58f, s * 0.56f, -s * 0.22f, s * 0.48f,
                -s * 0.16f, s * 0.78f);
        seahorseBody.cubicTo(-s * 0.38f, s * 0.42f, -s * 0.14f, s * 0.08f,
                -s * 0.34f, -s * 0.22f);
        seahorseBody.cubicTo(-s * 0.56f, -s * 0.68f, -s * 0.48f, -s * 1.20f,
                0f, -s * 1.45f);
        seahorseBody.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6FFC46B);
        canvas.drawPath(seahorseBody, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.9f));
        paint.setColor(0xAAFFE1A8);
        canvas.drawPath(seahorseBody, paint);

        // Snout and head.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(0xFFFFD58A);
        canvas.drawLine(s * 0.12f, -s * 1.15f, s * 0.75f, -s * 1.05f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFCF7D);
        canvas.drawCircle(s * 0.08f, -s * 1.20f, s * 0.37f, paint);

        // Dorsal fin.
        float fin = (float) Math.sin(t * 5.5f + 5.2f) * s * 0.12f;
        seahorseFin.reset();
        seahorseFin.moveTo(-s * 0.28f, -s * 0.35f);
        seahorseFin.lineTo(-s * 0.74f + fin, s * 0.02f);
        seahorseFin.lineTo(-s * 0.24f, s * 0.10f);
        seahorseFin.close();
        paint.setColor(0x88FFE0A8);
        canvas.drawPath(seahorseFin, paint);

        // Eye.
        if (distress > 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.1f));
            paint.setColor(0xDD101820);
            canvas.drawLine(s * 0.00f, -s * 1.30f, s * 0.16f, -s * 1.14f, paint);
            canvas.drawLine(s * 0.16f, -s * 1.30f, s * 0.00f, -s * 1.14f, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6FFFFFF);
            canvas.drawCircle(s * 0.07f, -s * 1.22f, s * 0.11f, paint);
            paint.setColor(0xFF0B1115);
            canvas.drawCircle(s * 0.09f, -s * 1.22f, s * 0.05f, paint);
        }
        canvas.restore();
        if (distress > 0.35f) {
            drawGaspBubbles(canvas, x, y, s, 0f, t, 9, distress);
        }
    }

    private void drawStarfish(Canvas canvas, float distress, float t) {
        float s = dp(6.2f);
        float x = fill.left + fill.width() * 0.58f
                + (float) Math.sin(t * 0.14f + 2.8f) * dp(2.5f);
        float y = fill.bottom - dp(3.4f);
        float outer = s * (1f - distress * 0.10f);
        float inner = s * (0.38f + distress * 0.08f);
        starfishPath.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float r = (i % 2 == 0) ? outer : inner;
            float px = (float) Math.cos(a) * r;
            float py = (float) Math.sin(a) * r;
            if (i == 0) starfishPath.moveTo(x + px, y + py);
            else starfishPath.lineTo(x + px, y + py);
        }
        starfishPath.close();
        canvas.save();
        canvas.rotate((float) Math.sin(t * 0.30f + 2.8f) * 6f, x, y);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFFFF9E7A, 0xD8));
        canvas.drawPath(starfishPath, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0xAAFFE0C8);
        canvas.drawPath(starfishPath, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66FFFFFF);
        canvas.drawCircle(x, y, dp(0.8f), paint);
        canvas.restore();
    }

    private void drawFish(Canvas canvas, float x, float y, float s, float angle,
                          float tailPhase, int color, float distress) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);

        float tailWave = (float) Math.sin(tailPhase) * s * 0.28f;
        fishTail.reset();
        fishTail.moveTo(-s * 0.76f, 0f);
        fishTail.lineTo(-s * 1.62f, -s * 0.55f + tailWave);
        fishTail.lineTo(-s * 1.42f, s * 0.02f + tailWave);
        fishTail.lineTo(-s * 1.62f, s * 0.55f + tailWave);
        fishTail.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, 0xB0));
        canvas.drawPath(fishTail, paint);

        fishFin.reset();
        fishFin.moveTo(-s * 0.28f, -s * 0.35f);
        fishFin.lineTo(-s * 0.72f, -s * 0.76f);
        fishFin.lineTo(s * 0.06f, -s * 0.42f);
        fishFin.close();
        paint.setColor(withAlpha(color, 0x98));
        canvas.drawPath(fishFin, paint);

        fishOval.set(-s * 0.88f, -s * 0.45f, s * 0.92f, s * 0.45f);
        paint.setColor(withAlpha(color, 0xD8));
        canvas.drawOval(fishOval, paint);

        // Belly highlight.
        paint.setColor(0x38FFFFFF);
        canvas.drawOval(new RectF(-s * 0.5f, s * 0.02f, s * 0.55f, s * 0.36f), paint);

        // Eye, or an X when the battery has drained.
        float ex = s * 0.54f;
        float ey = -s * 0.13f;
        if (distress > 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(0xDD101820);
            canvas.drawLine(ex - s * 0.18f, ey - s * 0.18f, ex + s * 0.18f, ey + s * 0.18f, paint);
            canvas.drawLine(ex + s * 0.18f, ey - s * 0.18f, ex - s * 0.18f, ey + s * 0.18f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xDD101820);
            canvas.drawCircle(s * 0.94f, s * 0.08f, s * 0.12f, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE6FFFFFF);
            canvas.drawCircle(ex, ey, s * 0.15f, paint);
            paint.setColor(0xFF0B1115);
            canvas.drawCircle(ex + s * 0.03f, ey, s * 0.07f, paint);
        }
        canvas.restore();
    }

    private void drawGaspBubbles(Canvas canvas, float x, float y, float s,
                                 float angle, float t, int seed, float distress) {
        double rad = Math.toRadians(angle);
        float mouthX = x + (float) Math.cos(rad) * s * 0.95f;
        float mouthY = y + (float) Math.sin(rad) * s * 0.95f;
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 2; k++) {
            float p = (t * 0.5f + k * 0.35f + seed * 0.23f) % 1f;
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (115 * (1f - p) * distress)));
            canvas.drawCircle(mouthX + (float) Math.sin(seed + p * 7f) * dp(2.5f),
                    mouthY - p * s * 2.2f, dp(1.1f + k * 0.6f), paint);
        }
    }

    /** Returns {startX, startY, pipe length, connectorX, connectorY} or null. */
    private float[] drawPipe(Canvas canvas, float w, float h, long now,
                             float phase, float portX) {
        float insert = Math.max(0f, Math.min(1f, shownPlug));
        float sway = cableSway * (1f - insert * 0.35f);
        float startX = w + dp(18f);
        float startY = body.centerY() + dp(8f) + sway * 0.18f;
        // At rest the connector sits in the right-hand gutter, nose pointing
        // back toward the pack, with a visible loop of cable behind it.
        float restX = w - dp(30f);
        float restY = h * 0.925f;
        // The pipe ends at the back of the connector. The connector then
        // extends down into the socket, so the hose joins the plug cleanly.
        float nozzleY = body.top - dp(12f);
        float endX = lerp(restX, portX, insert);
        float endY = lerp(restY, nozzleY, insert);
        if (insert < 0.9f) {
            endX += sway * (1f - insert) * 1.10f;
            endY += Math.abs(sway) * (1f - insert) * 0.22f;
        }

        // One smooth cubic for both states, so the hose cannot develop a kink
        // at the join while it is swinging down to or up from the floor.
        float c1x = lerp(w - dp(4f), w - dp(22f), insert);
        float c1y = lerp(startY + dp(54f), startY + dp(16f), insert);
        // The connected control point sits directly above the port so the
        // connector descends vertically into the socket instead of arriving
        // at a diagonal beside it.
        float c2x = lerp(w - dp(12f), portX, insert);
        float c2y = lerp(restY, nozzleY - dp(22f), insert);
        // Keep the cable loop inside the view on compact home-screen widgets.
        c2y = Math.max(dp(8f), Math.min(h - dp(5f), c2y));

        pipe.reset();
        pipe.moveTo(startX, startY);
        pipe.cubicTo(c1x, c1y, c2x, c2y, endX, endY);
        pipeMeasure.setPath(pipe, false);
        float length = pipeMeasure.getLength();

        // Shadow/residue on the shelf while the cable is disconnected.
        if (insert < 0.92f) {
            int shadowAlpha = Math.round(0x40 * (1f - insert));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(shadowAlpha << 24);
            float shadowShift = sway * 0.95f * (1f - insert);
            canvas.drawOval(new RectF(restX - dp(40f) + shadowShift, restY + dp(5f),
                    restX + dp(7f) + shadowShift, restY + dp(10f)), paint);
        }

        // A substantial translucent cable, visible even when it is empty.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(10.6f));
        paint.setColor(0x5EFFFFFF);
        canvas.drawPath(pipe, paint);
        paint.setStrokeWidth(dp(7.8f));
        paint.setColor(0xAA0B141A);
        canvas.drawPath(pipe, paint);

        boolean flowing = charging && plugged && !separated && insert > 0.72f
                && length > dp(8f);
        if (flowing) {
            paint.setStrokeWidth(dp(5.0f));
            paint.setColor(withAlpha(WATER, 0xD6));
            canvas.drawPath(pipe, paint);
            paint.setStrokeWidth(dp(1.8f));
            paint.setColor(0x88FFFFFF);
            canvas.drawPath(pipe, paint);

            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0xE6FFFFFF);
            for (int i = 0; i < 3; i++) {
                float from = ((phase + i * 0.29f) % 1f) * length;
                float to = Math.min(length, from + length * 0.11f);
                if (to <= from) continue;
                pipeSegment.reset();
                if (pipeMeasure.getSegment(from, to, pipeSegment, true)) {
                    canvas.drawPath(pipeSegment, paint);
                }
            }
            float[] pos = new float[2];
            for (int i = 0; i < 4; i++) {
                float p = (phase * 1.4f + i * 0.23f) % 1f;
                if (pipeMeasure.getPosTan(length * p, pos, null)) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(withAlpha(0xFFFFFFFF, 100));
                    canvas.drawCircle(pos[0], pos[1], dp(1.7f), paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }
        } else {
            paint.setStrokeWidth(dp(2.8f));
            paint.setColor(0x3A63E6FF);
            canvas.drawPath(pipe, paint);
        }

        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0x55FFFFFF);
        canvas.drawPath(pipe, paint);

        // Connector/nozzle at the end, aligned with the pipe direction.
        float[] pos = new float[2];
        float[] tan = new float[2];
        float angle = 0f;
        if (pipeMeasure.getPosTan(length, pos, tan)) {
            angle = (float) Math.toDegrees(Math.atan2(tan[1], tan[0]));
        }
        drawConnector(canvas, endX, endY, angle, insert, flowing);

        return new float[]{startX, startY, length, endX, endY};
    }

    private void drawConnector(Canvas canvas, float x, float y, float angle,
                               float insert, boolean flowing) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);

        // Strain relief / boot at the back, exactly where the pipe arrives.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0D151B);
        canvas.drawRoundRect(new RectF(-dp(5f), -dp(4.2f), dp(4f), dp(4.2f)),
                dp(3f), dp(3f), paint);

        // Neutral connector body, starting at the back of the plug.
        paint.setColor(0xFF19262F);
        canvas.drawRoundRect(new RectF(dp(3f), -dp(7.2f), dp(27f), dp(7.2f)),
                dp(4.5f), dp(4.5f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        paint.setColor(0x66FFFFFF);
        canvas.drawRoundRect(new RectF(dp(3f), -dp(7.2f), dp(27f), dp(7.2f)),
                dp(4.5f), dp(4.5f), paint);

        // Metal tip.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF34464F);
        canvas.drawRoundRect(new RectF(dp(26f), -dp(4.8f), dp(36f), dp(4.8f)),
                dp(2.4f), dp(2.4f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(0x887EEBFF);
        canvas.drawRoundRect(new RectF(dp(26f), -dp(4.8f), dp(36f), dp(4.8f)),
                dp(2.4f), dp(2.4f), paint);

        // A single soft highlight along the top of the shell.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x24FFFFFF);
        canvas.drawLine(dp(6f), -dp(5.4f), dp(24f), -dp(5.4f), paint);
        canvas.restore();
    }

    private void drawPour(Canvas canvas, float phase, float surfaceY,
                          float portX, float unusedLength, int levelColor) {
        float startY = body.top + dp(7f);
        float endY = surfaceY - dp(1f);
        if (endY - startY < dp(5f)) return;

        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(body, body.height() * 0.095f, body.height() * 0.095f,
                Path.Direction.CW);
        canvas.clipPath(clipPath);

        int n = 18;
        stream.reset();
        streamShape.reset();
        for (int i = 0; i <= n; i++) {
            float p = i / (float) n;
            float y = startY + (endY - startY) * p;
            float x = portX + dp(1.5f) * (float) Math.sin(
                    phase * (float) Math.PI * 2f + p * 4.2f) * p;
            if (i == 0) stream.moveTo(x, y); else stream.lineTo(x, y);
            float half = dp(2.1f) + p * dp(4.0f);
            if (i == 0) streamShape.moveTo(x - half, y);
            else streamShape.lineTo(x - half, y);
        }
        for (int i = n; i >= 0; i--) {
            float p = i / (float) n;
            float y = startY + (endY - startY) * p;
            float x = portX + dp(1.5f) * (float) Math.sin(
                    phase * (float) Math.PI * 2f + p * 4.2f) * p;
            float half = dp(2.1f) + p * dp(4.0f);
            streamShape.lineTo(x + half, y);
        }
        streamShape.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0f, startY, 0f, endY,
                withAlpha(WATER, 0x60), withAlpha(WATER, 0xC2), Shader.TileMode.CLAMP));
        paint.setAlpha(0xFF);
        canvas.drawPath(streamShape, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(10.5f));
        paint.setColor(withAlpha(WATER, 0x22));
        canvas.drawPath(stream, paint);
        paint.setStrokeWidth(dp(1.7f));
        paint.setColor(0xB3FFFFFF);
        canvas.drawPath(stream, paint);

        // Droplets travelling down the falling water.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float p = (phase * 2.1f + i * 0.19f) % 1f;
            float y = startY + (endY - startY) * p;
            float x = portX + dp(1.4f) * (float) Math.sin(
                    phase * 6.28f + p * 5f) * p;
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (80 * (1f - p * 0.35f))));
            canvas.drawCircle(x, y, dp(1.1f), paint);
        }

        // Wet sheet where the falling water meets the surface.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(WATER, 0x30));
        canvas.drawOval(new RectF(portX - dp(25f), surfaceY - dp(4.5f),
                portX + dp(25f), surfaceY + dp(4.5f)), paint);

        // Impact ripples on the slowly waving surface.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.1f));
        for (int i = 0; i < 3; i++) {
            float p = (phase * 1.55f + i * 0.31f) % 1f;
            float rx = dp(4f) + p * dp(27f);
            float ry = rx * 0.26f;
            paint.setColor(withAlpha(levelColor, (int) (125 * (1f - p))));
            canvas.drawOval(new RectF(portX - rx, surfaceY - ry,
                    portX + rx, surfaceY + ry), paint);
        }

        // Small splash flecks around the pour point.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) {
            float p = (phase * 1.9f + i * 0.163f) % 1f;
            float x = portX + dp(3f) + (float) Math.cos(i * 2.1f) * dp(11f) * p;
            float y = surfaceY - dp(2f) - (float) Math.sin(p * Math.PI) * dp(8f);
            paint.setColor(withAlpha(0xFFFFFFFF, (int) (105 * (1f - p))));
            canvas.drawCircle(x, y, dp(0.9f + (i % 2) * 0.5f), paint);
        }
        canvas.restore();
    }

    private void drawShield(Canvas canvas, float cx, float cy, float r) {
        Path shield = new Path();
        shield.moveTo(cx, cy - r);
        shield.lineTo(cx + r * 0.82f, cy - r * 0.55f);
        shield.lineTo(cx + r * 0.66f, cy + r * 0.45f);
        shield.lineTo(cx, cy + r);
        shield.lineTo(cx - r * 0.66f, cy + r * 0.45f);
        shield.lineTo(cx - r * 0.82f, cy - r * 0.55f);
        shield.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC101820);
        canvas.drawPath(shield, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0xFFFFC857);
        canvas.drawPath(shield, paint);
        canvas.drawLine(cx - r * 0.45f, cy + r * 0.15f,
                cx + r * 0.34f, cy - r * 0.34f, paint);
    }

    private int levelColor() {
        float t = Math.max(0f, Math.min(1f, shownLevel / 100f));
        if (t <= 0.35f) return blend(RED, AMBER, t / 0.35f);
        if (t < 0.70f) return blend(AMBER, GREEN, (t - 0.35f) / 0.35f);
        return GREEN;
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(Color.red(a) * (1f - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1f - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1f - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0f, Math.min(1f, t));
    }

    private float dp(float v) {
        return Ui.dpf(getContext(), v);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }
}
