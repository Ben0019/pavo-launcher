package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/**
 * Outline of the Odin 3 face based on AYN's front reference image:
 * left stick above the D-pad, X/Y/B/A diamond above the right stick, centre
 * system buttons, shoulder rows and the two extra front keys by the speakers.
 * Controls light up with their Xbox palette colour while held.
 */
final class OdinOutlineView extends View implements SensorEventListener {
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gameText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backDetail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path shape = new Path();
    private final Path screenClip = new Path();
    private final Path backBody = new Path();
    private final Path backShape = new Path();
    private final RectF rect = new RectF();
    private final long[] pressAt = new long[1024];
    private final long[] releaseAt = new long[1024];
    private final boolean[] pressed = new boolean[1024];
    private final Map<Integer, Integer> mappings = new HashMap<>();
    private float leftX, leftY, rightX, rightY, hatX, hatY;
    private float gameTime;
    private float scroll;
    private float runPhase;
    private float jumpOffset;
    private float jumpVelocity;
    private boolean jumping;
    private boolean jumpRequest;
    private float nextAutoJump = 1.4f;
    private long lastFrameMs;
    private long lastInputAt;
    private int simulatedRaw;
    private long simulatedAt;
    private long simulatedReleaseAt;
    private boolean simulatedHeld;
    private int autoJumpCount;
    private final float[] pressPoint = new float[2];
    private final float[] rotationMatrix = new float[9];
    private final float[] remappedMatrix = new float[9];
    private final float[] baseMatrix = new float[9];
    private final float[] deltaMatrix = new float[9];
    private final float[] orientation = new float[3];
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private boolean sensorsRegistered;
    private boolean orientationBaseSet;
    private float smoothPitch, smoothYaw, smoothRoll;
    private float smoothScale = 1f;
    private float manualYaw;
    private float manualPitch;
    private float fingerTargetYaw;
    private float fingerTargetPitch;
    private float smoothFingerYaw;
    private float smoothFingerPitch;
    private float displayYaw;
    private float displayPitch;
    private float displayRoll;
    private long lastYawStickAt;
    private long yawStickStartedAt;
    private long yawStickLastEvent;
    private float yawStickAxis;
    private float yawStickSpeed;
    private float lastTouchX, lastTouchY;

    OdinOutlineView(Context c) {
        super(c);
        outline.setStyle(Paint.Style.STROKE);
        bodyPaint.setStyle(Paint.Style.STROKE);
        bodyPaint.setStrokeCap(Paint.Cap.ROUND);
        bodyPaint.setStrokeJoin(Paint.Join.ROUND);
        bodyPaint.setStrokeWidth(1.45f);
        bodyPaint.setColor(0x66FFFFFF);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setColor(0x66FFFFFF);
        fill.setStyle(Paint.Style.FILL);
        screen.setStyle(Paint.Style.STROKE);
        screen.setColor(0x38FFFFFF);
        glow.setStyle(Paint.Style.FILL);
        gamePaint.setStyle(Paint.Style.FILL);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeCap(Paint.Cap.ROUND);
        gameText.setTextAlign(Paint.Align.CENTER);
        gameText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        backFill.setStyle(Paint.Style.FILL);
        backFill.setColor(0x322F6DBE);
        backLine.setStyle(Paint.Style.STROKE);
        backLine.setStrokeWidth(1.35f);
        backLine.setStrokeCap(Paint.Cap.ROUND);
        backLine.setColor(0xAA8CCFFF);
        backDetail.setStyle(Paint.Style.STROKE);
        backDetail.setStrokeWidth(0.85f);
        backDetail.setColor(0x6680B9E8);
        backAccent.setStyle(Paint.Style.FILL);
        backAccent.setColor(0xCC4F93EE);
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                        // Direct, speed-following drag: moving the finger slowly
                        // rotates slowly; a full-width drag is only 170 degrees.
                        float dragWidth = Math.max(1f, getWidth());
                        float dragHeight = Math.max(1f, getHeight());
                        fingerTargetYaw = normalizeDegrees(fingerTargetYaw
                                + dx / dragWidth * 60f);
                        fingerTargetPitch = clamp(fingerTargetPitch
                                - dy / dragHeight * 16f, 10f);
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        setContentDescription(DeviceProfile.get(getContext()).name + " controller layout");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setCameraDistance(16000f * getResources().getDisplayMetrics().density);
    }

    private boolean isDown(int raw) {
        if (raw <= 0) return false;
        if (raw < pressed.length && pressed[raw]) return true;
        return raw == simulatedRaw && simulatedHeld;
    }

    void setPressed(int raw, boolean down) {
        if (raw <= 0 || raw >= pressed.length) return;
        if (pressed[raw] == down) return;
        long now = SystemClock.uptimeMillis();
        pressed[raw] = down;
        lastInputAt = now;
        if (down) {
            pressAt[raw] = now;
            if (raw == 304) jumpRequest = true;
        } else {
            releaseAt[raw] = now;
        }
        invalidate();
    }

    void clearPressed() {
        boolean any = false;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < pressed.length; i++) {
            if (pressed[i]) {
                pressed[i] = false;
                releaseAt[i] = now;
                any = true;
            }
        }
        if (any) invalidate();
    }

    void setMappings(Map<Integer, Integer> values) {
        mappings.clear();
        if (values != null) mappings.putAll(values);
        invalidate();
    }

    private int shown(int raw) {
        Integer target = mappings.get(raw);
        return target == null ? raw : target;
    }

    private static String shortLabel(int raw) {
        switch (raw) {
            case 304: return "A";
            case 305: return "B";
            case 306: return "C";
            case 307: return "X";
            case 308: return "Y";
            case 309: return "Z";
            case 310: return "L1";
            case 311: return "R1";
            case 312: return "L2";
            case 313: return "R2";
            case 314: return "SE";
            case 315: return "ST";
            case 316: return "HM";
            case 317: return "L3";
            case 318: return "R3";
            case 544: return "\u2191";
            case 545: return "\u2193";
            case 546: return "\u2190";
            case 547: return "\u2192";
            case 102: return "HM";
            case 158: return "BK";
            case 114: return "V-";
            case 115: return "V+";
            default: return "?";
        }
    }

    private static int colourFor(int raw) {
        switch (raw) {
            case 304: return 0xFF7ED957;
            case 305: return 0xFFFF4D4D;
            case 306: return 0xFFC792EA;
            case 307: return 0xFF4DA3FF;
            case 308: return 0xFFFFD54D;
            case 309: return 0xFF80D8FF;
            default: return 0xE6FFFFFF;
        }
    }

    void setStickAxes(float lx, float ly, float rx, float ry) {
        leftX = lx;
        leftY = ly;
        rightX = rx;
        rightY = ry;
        invalidate();
    }

    void setHat(float x, float y) {
        hatX = x;
        hatY = y;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameMs = 0L;
        startSensors();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSensors();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        setPivotX(w / 2f);
        setPivotY(h / 2f);
        setCameraDistance(Math.max(1f, Math.max(w, h) * 6f));
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startSensors();
        else stopSensors();
    }

    private void startSensors() {
        if (sensorsRegistered) return;
        Context context = getContext();
        if (context == null) return;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rotationSensor == null) return;
        orientationBaseSet = false;
        smoothPitch = smoothYaw = smoothRoll = 0f;
        smoothScale = 1f;
        yawStickAxis = 0f;
        yawStickSpeed = 0f;
        yawStickStartedAt = 0L;
        sensorsRegistered = sensorManager.registerListener(this, rotationSensor,
                SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopSensors() {
        if (!sensorsRegistered || sensorManager == null) return;
        sensorManager.unregisterListener(this);
        sensorsRegistered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == null) return;
        int type = event.sensor.getType();
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR
                && type != Sensor.TYPE_ROTATION_VECTOR) return;
        if (event.values.length < 4) return;

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        remapToDisplay(rotationMatrix, remappedMatrix);
        if (!orientationBaseSet) {
            System.arraycopy(remappedMatrix, 0, baseMatrix, 0, 9);
            orientationBaseSet = true;
            return;
        }

        // delta = base^T * current. This is a true relative rotation, so yaw
        // wrap and pitch/roll interactions do not distort the result.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += baseMatrix[k * 3 + row] * remappedMatrix[k * 3 + col];
                }
                deltaMatrix[row * 3 + col] = sum;
            }
        }
        SensorManager.getOrientation(deltaMatrix, orientation);

        float targetYaw = wrapDegrees((float) Math.toDegrees(orientation[0]) * 0.55f);
        float targetPitch = clamp((float) Math.toDegrees(orientation[1]) * 0.50f, 14f);
        float targetRoll = clamp((float) Math.toDegrees(orientation[2]) * 0.50f, 12f);
        smoothYaw += wrapDegrees(targetYaw - smoothYaw) * 0.14f;
        smoothPitch += (targetPitch - smoothPitch) * 0.14f;
        smoothRoll += (targetRoll - smoothRoll) * 0.14f;
        applyOrientation();
    }

    private void applyOrientation() {
        displayYaw = wrapDegrees(manualYaw + smoothFingerYaw + smoothYaw);
        displayPitch = clamp(manualPitch + smoothFingerPitch + smoothPitch, 20f);
        displayRoll = smoothRoll;

        // A controller is wide, so roll needs extra vertical room. Shrink the
        // whole transformed view just enough to keep its top and bottom visible.
        double pitchRad = Math.toRadians(displayPitch);
        double yawRad = Math.toRadians(displayYaw);
        double rollRad = Math.toRadians(displayRoll);
        float projectedHeight = 150f * (float) Math.abs(Math.cos(pitchRad) * Math.cos(rollRad))
                + 360f * (float) Math.abs(Math.sin(rollRad))
                + 360f * (float) Math.abs(Math.sin(yawRad)) * 0.16f;
        float targetScale = projectedHeight <= 151f ? 1f
                : Math.min(1f, 0.98f * 150f / projectedHeight);
        targetScale = Math.max(0.58f, targetScale);
        smoothScale += (targetScale - smoothScale) * 0.25f;

        setScaleX(smoothScale);
        setScaleY(smoothScale);
        setRotationX(displayPitch);
        setRotationY(displayYaw);
        setRotation(-displayRoll);
        setTranslationX(displayYaw * 0.10f * smoothScale);
        setTranslationY(-displayPitch * 0.14f * smoothScale);
        invalidate();
    }

    void rotateYawByStick(float axis) {
        long now = SystemClock.uptimeMillis();
        if (Math.abs(axis) < 0.18f) {
            yawStickAxis = 0f;
            return;
        }
        if (yawStickStartedAt == 0L) yawStickStartedAt = now;
        yawStickAxis = axis;
        yawStickLastEvent = now;
        invalidate();
    }

    void stopYawStick() {
        yawStickAxis = 0f;
        yawStickLastEvent = 0L;
    }

    private void updateFingerRotation(float dt) {
        float alpha = Math.min(1f, dt * 9f);
        float yawDelta = wrapDegrees(fingerTargetYaw - smoothFingerYaw);
        float pitchDelta = fingerTargetPitch - smoothFingerPitch;
        if (Math.abs(yawDelta) < 0.05f && Math.abs(pitchDelta) < 0.05f) {
            smoothFingerYaw = fingerTargetYaw;
            smoothFingerPitch = fingerTargetPitch;
            return;
        }
        smoothFingerYaw = normalizeDegrees(smoothFingerYaw + yawDelta * alpha);
        smoothFingerPitch += pitchDelta * alpha;
        applyOrientation();
    }

    private void updateYawStick(float dt) {
        long now = SystemClock.uptimeMillis();
        if (yawStickAxis != 0f && now - yawStickLastEvent > 180L) {
            yawStickAxis = 0f;
        }
        boolean moving = Math.abs(yawStickAxis) > 0.01f || Math.abs(yawStickSpeed) > 0.5f;
        if (!moving) {
            yawStickSpeed = 0f;
            yawStickStartedAt = 0L;
            return;
        }
        if (yawStickAxis != 0f && yawStickStartedAt == 0L) {
            yawStickStartedAt = now;
        }
        float held = yawStickStartedAt == 0L ? 0f
                : (now - yawStickStartedAt) / 1000f;
        float ramp = Math.min(1f, held / 0.60f);
        ramp = ramp * ramp * (3f - 2f * ramp);
        float target = yawStickAxis * (120f + 520f * Math.abs(yawStickAxis) * ramp);
        yawStickSpeed += (target - yawStickSpeed) * Math.min(1f, dt * 7f);
        manualYaw = normalizeDegrees(manualYaw + yawStickSpeed * dt);
        applyOrientation();
    }


    private static float normalizeDegrees(float value) {
        value %= 360f;
        if (value < 0f) value += 360f;
        return value;
    }

    private static float wrapDegrees(float value) {
        value %= 360f;
        if (value > 180f) value -= 360f;
        if (value < -180f) value += 360f;
        return value;
    }

    private void remapToDisplay(float[] in, float[] out) {
        Display display = getDisplay();
        int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;
        switch (rotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            default:
                break;
        }
        SensorManager.remapCoordinateSystem(in, axisX, axisY, out);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float wrapRadians(float value) {
        while (value > Math.PI) value -= Math.PI * 2f;
        while (value < -Math.PI) value += Math.PI * 2f;
        return value;
    }

    /**
     * Vertical stretch that turns the 176x108 reference screen into the real
     * panel aspect. Everything else in the artwork stretches with it so the
     * chassis keeps the proportions of the device it represents.
     */
    private float artScale() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        float shortSide = Math.min(m.widthPixels, m.heightPixels);
        if (shortSide <= 0) return 1f;
        float aspect = Math.max(m.widthPixels, m.heightPixels) / shortSide;
        float scale = (176f / Math.max(1.05f, aspect)) / 108f;
        return Math.max(0.70f, Math.min(1.30f, scale));
    }

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0L ? 0f
                : Math.min(0.05f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        advanceGame(dt);
        // Match the drawing to the real panel: the screen is drawn 176x108, so
        // stretch the whole device vertically until that rect matches the
        // device's aspect (4:3 handhelds get a taller body, 21:9 a shorter one).
        float artScale = artScale();
        float artH = 17f + (150f - 17f) * artScale;
        float s = Math.min(w / 360f, h / artH) * 0.94f;
        c.save();
        c.translate((w - 360f * s) / 2f, (h - artH * s) / 2f);
        c.scale(s, s);
        c.scale(1f, artScale, 0f, 17f);
        boolean back = Math.abs(displayYaw) > 90f;
        if (back) {
            // The back artwork is pre-mirrored; the View's Y rotation mirrors it
            // back into the correct orientation when we are looking at the rear.
            c.save();
            c.scale(-1f, 1f, 180f, 75f);
            drawBack(c);
            c.restore();
        } else {
            drawDevice(c);
            drawPressPulses(c, now);
        }
        c.restore();
        if (isShown()) postInvalidateOnAnimation();
    }

    private void advanceGame(float dt) {
        gameTime += dt;
        scroll += dt * 30f;
        if (scroll > 240f) scroll -= 240f;
        runPhase += dt * 10f;
        if (jumpRequest) {
            startJump();
            jumpRequest = false;
        }
        if (!jumping && gameTime >= nextAutoJump) {
            startJump();
            nextAutoJump = gameTime + 2.2f;
            autoJumpCount++;
            if (SystemClock.uptimeMillis() - lastInputAt > 1400L) {
                simulatedRaw = 304;
                simulatedHeld = true;
                simulatedAt = SystemClock.uptimeMillis();
            }
        }
        updateFingerRotation(dt);
        updateYawStick(dt);
        if (jumping) {
            jumpVelocity += 205f * dt;
            jumpOffset += jumpVelocity * dt;
            if (jumpOffset >= 0f) {
                jumpOffset = 0f;
                jumpVelocity = 0f;
                jumping = false;
                nextAutoJump = gameTime + 1.8f;
                if (simulatedHeld && simulatedRaw == 304) {
                    simulatedHeld = false;
                    simulatedReleaseAt = SystemClock.uptimeMillis();
                }
            }
        }
    }

    private void startJump() {
        if (jumping) return;
        jumping = true;
        jumpVelocity = -62f;
        jumpOffset = 0f;
    }

    private void drawDevice(Canvas c) {
        float d = 1.45f;
        outline.setStrokeWidth(d);
        screen.setStrokeWidth(d);

        // Wide glass front with the lower grip flare from the Odin 3 render.
        body.reset();
        body.moveTo(62, 17);
        body.lineTo(298, 17);
        body.quadTo(336, 17, 342, 55);
        body.lineTo(348, 91);
        body.quadTo(354, 130, 306, 132);
        body.lineTo(54, 132);
        body.quadTo(6, 130, 12, 91);
        body.lineTo(18, 55);
        body.quadTo(24, 17, 62, 17);
        body.close();
        c.drawPath(body, bodyPaint);

        // Soft grip contours.
        outline.setColor(0x33FFFFFF);
        c.drawArc(new RectF(16, 72, 54, 128), 95, 80, false, outline);
        c.drawArc(new RectF(306, 72, 344, 128), 5, 80, false, outline);
        outline.setColor(0x66FFFFFF);

        // Display: a tiny animated platformer, like a game running on the device.
        rect.set(92, 20, 268, 128);
        drawGameScreen(c);
        screen.setColor(0x66FFFFFF);
        c.drawRoundRect(rect, 6, 6, screen);
        screen.setColor(0x38FFFFFF);

        // Shoulder row.
        // Shoulder and volume controls are highlighted segments of the chassis
        // outline itself, not separate floating buttons.
        drawCurvedShoulder(c, true, 312, shortLabel(shown(312)));
        drawCurvedShoulder(c, false, 313, shortLabel(shown(313)));
        drawEdgeControl(c, 84, 123, 17, 310, shortLabel(shown(310)), 6.2f);
        drawEdgeControl(c, 159, 177, 17, 114, "-", 8.2f);
        drawEdgeControl(c, 183, 201, 17, 115, "+", 8.2f);
        drawEdgeControl(c, 237, 276, 17, 311, shortLabel(shown(311)), 6.2f);

        // Left: stick above D-pad.
        drawStick(c, 52, 48, 317);
        drawDpad(c, 52, 88);

        // Top inner Select/Start keys.
        drawTinyTop(c, 78, 23, 314, shortLabel(shown(314)));
        drawTinyTop(c, 280, 23, 315, shortLabel(shown(315)));

        // Right: X/Y/B/A diamond above stick.
        drawFace(c, 296, 31, 307, shortLabel(shown(307)), colourFor(shown(307)));
        drawFace(c, 281, 46, 308, shortLabel(shown(308)), colourFor(shown(308)));
        drawFace(c, 296, 61, 305, shortLabel(shown(305)), colourFor(shown(305)));
        drawFace(c, 311, 46, 304, shortLabel(shown(304)), colourFor(shown(304)));
        drawStick(c, 296, 96, 318);

        // Speaker grilles, front Home/Back keys, and the rear C/Z buttons
        // are not part of this front view.
        drawSpeaker(c, 27, 110, 63, 120);
        drawSpeaker(c, 297, 110, 333, 120);
        drawHomeKey(c, 75, 120);
        drawBackKey(c, 285, 120);
        drawRearKey(c, 22, 73, 306, "Z", 0xFF80D8FF);
        drawRearKey(c, 338, 73, 309, "C", 0xFFC792EA);
    }

    /** Rear shell from the transparent-blue Odin reference, drawn in outline style. */
    private void drawBack(Canvas c) {
        backBody.reset();
        backBody.moveTo(62, 15);
        backBody.lineTo(298, 15);
        backBody.quadTo(336, 15, 342, 53);
        backBody.lineTo(348, 91);
        backBody.quadTo(354, 130, 306, 132);
        backBody.lineTo(54, 132);
        backBody.quadTo(6, 130, 12, 91);
        backBody.lineTo(18, 53);
        backBody.quadTo(24, 15, 62, 15);
        backBody.close();
        backFill.setColor(0x322F6DBE);
        c.drawPath(backBody, backFill);
        backLine.setStrokeWidth(1.45f);
        backLine.setColor(0xAA8CCFFF);
        c.drawPath(backBody, backLine);

        // Shoulder buttons on the rear edge.
        backFill.setColor(0xCC141A20);
        c.drawRoundRect(new RectF(18, 3, 88, 18), 7, 7, backFill);
        c.drawRoundRect(new RectF(272, 3, 342, 18), 7, 7, backFill);
        backDetail.setColor(0x6689A5B8);
        c.drawRoundRect(new RectF(18, 3, 88, 18), 7, 7, backDetail);
        c.drawRoundRect(new RectF(272, 3, 342, 18), 7, 7, backDetail);

        // USB / top seam.
        backFill.setColor(0xAA0C1822);
        c.drawRoundRect(new RectF(171, 10, 189, 16), 2, 2, backFill);
        backDetail.setStrokeWidth(0.8f);
        backDetail.setColor(0x6688C8FF);
        c.drawRoundRect(new RectF(171, 10, 189, 16), 2, 2, backDetail);

        // Grips: translucent dot texture.
        backAccent.setColor(0x227CC6FF);
        for (int side = 0; side < 2; side++) {
            float sx = side == 0 ? 24f : 286f;
            for (int x = 0; x < 12; x++) {
                for (int y = 0; y < 12; y++) {
                    float px = sx + x * 4.2f;
                    float py = 51f + y * 5.5f;
                    float edge = side == 0 ? (px - 18f) : (342f - px);
                    if (edge < 0f) continue;
                    c.drawCircle(px, py, 0.65f, backAccent);
                }
            }
        }
        backDetail.setColor(0x4477B8E8);
        c.drawArc(new RectF(14, 54, 86, 132), 92, 150, false, backDetail);
        c.drawArc(new RectF(274, 54, 346, 132), -62, 150, false, backDetail);

        // Twin top exhaust grilles.
        drawBackVent(c, 92f, 28f, 76f, 18f);
        drawBackVent(c, 196f, 28f, 76f, 18f);

        // Perforated central cover.
        backFill.setColor(0x2A163F66);
        backShape.reset();
        backShape.addRoundRect(new RectF(88, 52, 282, 120), 8, 8, Path.Direction.CW);
        c.drawPath(backShape, backFill);
        backDetail.setStrokeWidth(0.9f);
        backDetail.setColor(0x5589C8FF);
        c.drawPath(backShape, backDetail);

        backDetail.setStrokeWidth(0.55f);
        for (int row = 0; row < 7; row++) {
            for (int col = 0; col < 16; col++) {
                float hx = 96f + col * 11.3f + (row % 2) * 5.4f;
                float hy = 59f + row * 8.3f;
                if (hx > 278f) continue;
                backFill.setColor(0x77101B27);
                c.drawCircle(hx, hy, 1.55f, backFill);
                backDetail.setColor(0x5589D0FF);
                c.drawCircle(hx, hy, 1.55f, backDetail);
            }
        }

        // Round rear fan and impeller.
        backFill.setColor(0xAA0B2139);
        c.drawCircle(252f, 86f, 18f, backFill);
        backLine.setStrokeWidth(1.2f);
        backLine.setColor(0xCC77C8FF);
        c.drawCircle(252f, 86f, 18f, backLine);
        backAccent.setColor(0xAA4F93EE);
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 * i / 5f + 0.4f;
            float ax = 252f + (float) Math.cos(a) * 13f;
            float ay = 86f + (float) Math.sin(a) * 13f;
            backLine.setColor(0xCC54B5FF);
            c.drawLine(252f, 86f, ax, ay, backLine);
            c.drawCircle(ax, ay, 2.4f, backAccent);
        }
        backFill.setColor(0xFFB8DDFF);
        c.drawCircle(252f, 86f, 3.2f, backFill);

        // Oval rear paddle buttons.
        drawBackPaddle(c, 69f, 111f, 18f, 25f, "M1");
        drawBackPaddle(c, 291f, 109f, 18f, 25f, "M2");

        // QR-style label on the battery cover.
        backFill.setColor(0xAA0B1824);
        c.drawRect(82f, 63f, 101f, 82f, backFill);
        backDetail.setStrokeWidth(0.7f);
        backDetail.setColor(0x778ED8FF);
        c.drawRect(82f, 63f, 101f, 82f, backDetail);
        backAccent.setColor(0xCCB8E7FF);
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 6; col++) {
                if (((row * 3 + col * 5 + row * col) % 5) < 2) {
                    c.drawRect(84f + col * 2.6f, 65f + row * 2.6f,
                            86f + col * 2.6f, 67f + row * 2.6f, backAccent);
                }
            }
        }

        // Screws and branding.
        backDetail.setStrokeWidth(0.8f);
        backDetail.setColor(0x7775B8E8);
        float[][] screws = {{55, 23}, {305, 23}, {55, 123}, {305, 123}};
        for (float[] p : screws) {
            c.drawCircle(p[0], p[1], 2.2f, backDetail);
            c.drawCircle(p[0], p[1], 0.8f, backFill);
        }
        text.setTextSize(8f);
        text.setColor(0x99D5F1FF);
        c.drawText("AYN", 180f, 139f, text);
        text.setTextSize(3.6f);
        text.setColor(0x668EC8EA);
        c.drawText("TRANSPARENT BLUE  ·  "
                + DeviceProfile.get(getContext()).name.toUpperCase(), 180f, 146f, text);
    }

    private void drawBackVent(Canvas c, float x, float y, float w, float h) {
        backFill.setColor(0x88101B26);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), 3, 3, backFill);
        backDetail.setStrokeWidth(0.8f);
        backDetail.setColor(0x5589C8FF);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), 3, 3, backDetail);
        backAccent.setColor(0xAA50A4E8);
        for (float sx = x + 6f; sx < x + w - 5f; sx += 6f) {
            c.drawRect(sx, y + 4f, sx + 1.6f, y + h - 4f, backAccent);
        }
    }

    private void drawBackPaddle(Canvas c, float x, float y, float w, float h, String label) {
        backFill.setColor(0xCC111A22);
        c.drawOval(new RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f), backFill);
        backLine.setStrokeWidth(0.9f);
        backLine.setColor(0x8877B9E8);
        c.drawOval(new RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f), backLine);
        text.setTextSize(4.5f);
        text.setColor(0x7799D8FF);
        c.drawText(label, x, y + 1.5f, text);
    }

    private void drawGameScreen(Canvas c) {
        screenClip.reset();
        screenClip.addRoundRect(rect, 6f, 6f, Path.Direction.CW);
        // The whole game layer is translucent: the device shell remains visible
        // through the display while the level plays.
        int layer = c.saveLayerAlpha(rect, 205);
        c.clipPath(screenClip);

        // Sky and a slow-moving sun.
        gamePaint.setStyle(Paint.Style.FILL);
        gamePaint.setColor(0xFF69B7E8);
        c.drawRect(rect, gamePaint);
        gamePaint.setColor(0xFF9BDCF5);
        c.drawRect(rect.left, rect.top, rect.right, rect.top + 44f, gamePaint);
        gamePaint.setColor(0x44FFF0A8);
        c.drawCircle(rect.right - 24f, rect.top + 18f,
                10f + (float) Math.sin(gameTime * 1.2f) * 0.8f, gamePaint);

        // Parallax hills: the two layers wrap independently, no visible reset.
        gamePaint.setColor(0xFF3F9142);
        float hillShift = (gameTime * 5f) % 92f;
        for (int i = -1; i < 5; i++) {
            float hx = rect.left + i * 72f - hillShift;
            c.drawCircle(hx, rect.bottom - 12f, 22f, gamePaint);
        }
        gamePaint.setColor(0xFF58A85B);
        float hillShift2 = (gameTime * 3f) % 110f;
        for (int i = -1; i < 5; i++) {
            float hx = rect.left + 24f + i * 76f - hillShift2;
            c.drawCircle(hx, rect.bottom - 10f, 17f, gamePaint);
        }

        // Drifting clouds.
        gamePaint.setColor(0xD9FFFFFF);
        for (int i = 0; i < 3; i++) {
            float cx = rect.left + ((gameTime * 8f + i * 76f) % 210f) - 20f;
            float cy = rect.top + 14f + (i % 2) * 14f;
            c.drawCircle(cx, cy, 5f, gamePaint);
            c.drawCircle(cx + 6f, cy + 1f, 6f, gamePaint);
            c.drawCircle(cx + 13f, cy, 4.5f, gamePaint);
            c.drawRect(cx, cy, cx + 13f, cy + 4.5f, gamePaint);
        }

        float ground = rect.bottom - 13f;

        // Seamless ground tile scrolling.
        gamePaint.setColor(0xFF4CAF50);
        c.drawRect(rect.left, ground, rect.right, ground + 4f, gamePaint);
        gamePaint.setColor(0xFF7A4B29);
        c.drawRect(rect.left, ground + 4f, rect.right, rect.bottom, gamePaint);
        float brickOffset = scroll % 12f;
        gamePaint.setColor(0xFF5A351D);
        for (float x = rect.left - brickOffset; x < rect.right; x += 12f) {
            c.drawRect(x, ground + 4f, x + 10f, ground + 8f, gamePaint);
            c.drawRect(x + 6f, ground + 9f, x + 16f, ground + 13f, gamePaint);
        }
        gamePaint.setColor(0x1AFFFFFF);
        c.drawRect(rect.left, ground, rect.right, ground + 1.2f, gamePaint);

        // Repeating level furniture: blocks, pipe, coins and one Goomba per
        // 240-unit loop. Objects wrap far outside the visible screen.
        drawQuestionBlockAt(c, 96f, ground);
        drawQuestionBlockAt(c, 120f, ground);
        drawBrickAt(c, 144f, ground);

        float pipeX = gameX(166f);
        if (pipeX < rect.right + 14f && pipeX > rect.left - 16f) {
            gamePaint.setColor(0xFF2C8A38);
            c.drawRect(pipeX, ground - 13f, pipeX + 11f, ground, gamePaint);
            gamePaint.setColor(0xFF57C95C);
            c.drawRect(pipeX - 1f, ground - 14f, pipeX + 12f, ground - 10f, gamePaint);
            gamePaint.setColor(0x66000000);
            c.drawRect(pipeX + 8f, ground - 13f, pipeX + 11f, ground, gamePaint);
        }

        drawCoinAt(c, 76f, ground);
        drawCoinAt(c, 206f, ground);

        float goombaX = gameX(132f);
        if (goombaX < rect.right + 10f && goombaX > rect.left - 12f) {
            float goombaBob = (float) Math.sin(gameTime * 5f) * 0.7f;
            gamePaint.setColor(0xFF9A5A2A);
            c.drawOval(new RectF(goombaX - 4f, ground - 8f + goombaBob,
                    goombaX + 4f, ground - 1f + goombaBob), gamePaint);
            gamePaint.setColor(0xFF4A2814);
            c.drawRect(goombaX - 4f, ground - 2f + goombaBob,
                    goombaX - 1f, ground + goombaBob, gamePaint);
            c.drawRect(goombaX + 1f, ground - 2f + goombaBob,
                    goombaX + 4f, ground + goombaBob, gamePaint);
            gamePaint.setColor(0xFFFFFFFF);
            c.drawRect(goombaX - 2f, ground - 6f + goombaBob,
                    goombaX - 1f, ground - 5f + goombaBob, gamePaint);
            c.drawRect(goombaX + 1f, ground - 6f + goombaBob,
                    goombaX + 2f, ground - 5f + goombaBob, gamePaint);
        }

        drawMario(c, rect.left + 42f, ground, jumpOffset, runPhase, jumping);

        gameText.setTextSize(3.4f);
        gameText.setColor(0xAAFFFFFF);
        c.drawText("WORLD 1-1", rect.left + 26f, rect.top + 8f, gameText);
        c.restoreToCount(layer);
    }

    private float gameX(float world) {
        float x = (world - scroll) % 240f;
        if (x < 0f) x += 240f;
        return rect.left + x;
    }

    private void drawQuestionBlockAt(Canvas c, float world, float ground) {
        float x = gameX(world);
        if (x < rect.right + 12f && x > rect.left - 12f) {
            drawQuestionBlock(c, x, ground - 34f);
        }
    }

    private void drawBrickAt(Canvas c, float world, float ground) {
        float x = gameX(world);
        if (x < rect.right + 12f && x > rect.left - 12f) {
            gamePaint.setColor(0xFFB8792E);
            c.drawRect(x, ground - 34f, x + 11f, ground - 23f, gamePaint);
            gamePaint.setColor(0xFF5A351D);
            c.drawRect(x + 1f, ground - 33f, x + 10f, ground - 32f, gamePaint);
        }
    }

    private void drawCoinAt(Canvas c, float world, float ground) {
        float x = gameX(world);
        if (x < rect.right - 3f && x > rect.left - 5f) {
            float y = ground - 24f + (float) Math.sin(gameTime * 4f + world) * 1.8f;
            float spin = 0.45f + 0.55f * Math.abs((float) Math.cos(gameTime * 4.5f + world));
            gamePaint.setColor(0xFFFFD34D);
            c.drawOval(new RectF(x - 2.2f * spin, y - 4f,
                    x + 2.2f * spin, y + 4f), gamePaint);
            gamePaint.setColor(0xFFFFF4B0);
            c.drawRect(x - 0.5f, y - 2.6f, x + 0.5f, y + 2.6f, gamePaint);
        }
    }

    private void drawQuestionBlock(Canvas c, float x, float y) {
        gamePaint.setColor(0xFFD9A02B);
        c.drawRect(x, y, x + 11f, y + 11f, gamePaint);
        gamePaint.setColor(0xFF6B4319);
        c.drawRect(x + 0.8f, y + 0.8f, x + 10.2f, y + 1.8f, gamePaint);
        c.drawRect(x + 0.8f, y + 9.2f, x + 10.2f, y + 10.2f, gamePaint);
        gameText.setTextSize(7f);
        gameText.setColor(0xFFFFF3C9);
        c.drawText("?", x + 5.5f, y + 8.2f, gameText);
    }

    private void drawMario(Canvas c, float x, float ground, float jump,
                           float run, boolean airborne) {
        float bob = airborne ? 0f : (float) Math.sin(run * 2f) * 0.35f;
        float y = ground - 14f + jump - bob;
        gamePaint.setStyle(Paint.Style.FILL);
        float shadow = Math.max(0.25f, 1f + jump / 22f);
        gamePaint.setColor(0x33000000);
        c.drawOval(new RectF(x - 5f * shadow, ground - 1.8f,
                x + 6f * shadow, ground + 1.2f), gamePaint);

        int step = airborne ? 1 : (int) Math.round(Math.sin(run) * 2f);
        float armSwing = airborne ? 1.8f : (float) Math.sin(run) * 1.7f;

        // Legs and shoes.
        gamePaint.setColor(0xFF603719);
        c.drawRect(x - 4f + step, y + 11f, x + 0.5f, y + 14f, gamePaint);
        c.drawRect(x + 0.5f - step, y + 11f, x + 4f, y + 14f, gamePaint);
        gamePaint.setColor(0xFF2452A8);
        c.drawRect(x - 3f, y + 6f, x + 3f, y + 12f, gamePaint);

        // Arms swing opposite the legs.
        gamePaint.setColor(0xFFF2C28C);
        c.drawRect(x - 4.2f, y + 6.2f + armSwing, x - 3f, y + 9.5f + armSwing, gamePaint);
        c.drawRect(x + 3f, y + 6.2f - armSwing, x + 4.2f, y + 9.5f - armSwing, gamePaint);

        // Shirt, head, cap and face.
        gamePaint.setColor(0xFFE23A3A);
        c.drawRect(x - 3.5f, y + 5f, x + 3.5f, y + 7f, gamePaint);
        gamePaint.setColor(0xFFF2C28C);
        c.drawRect(x - 2.8f, y + 1.7f, x + 2.8f, y + 6f, gamePaint);
        gamePaint.setColor(0xFFE23A3A);
        c.drawRect(x - 3.6f, y + 0.2f, x + 3.6f, y + 2.3f, gamePaint);
        c.drawRect(x - 2.8f, y + 2.2f, x + 1f, y + 2.9f, gamePaint);
        gamePaint.setColor(0xFF111111);
        c.drawRect(x + 1.2f, y + 3.3f, x + 2.1f, y + 4.2f, gamePaint);
        gamePaint.setColor(0xFF8A4B24);
        c.drawRect(x - 2.2f, y + 4.4f, x + 0.3f, y + 5.1f, gamePaint);
    }

    private void drawPressPulses(Canvas c, long now) {
        for (int raw = 1; raw < pressed.length; raw++) {
            boolean down = pressed[raw];
            long stamp = down ? pressAt[raw] : releaseAt[raw];
            if (stamp != 0L) drawPressPulse(c, raw, now, down, stamp);
        }
        if (simulatedRaw > 0) {
            if (simulatedHeld) {
                drawPressPulse(c, simulatedRaw, now, true, simulatedAt);
            } else {
                long age = now - simulatedReleaseAt;
                if (age < 320L && age >= 0L) {
                    drawPressPulse(c, simulatedRaw, now, false, simulatedReleaseAt);
                }
            }
        }
    }

    private void drawPressPulse(Canvas c, int raw, long now, boolean down, long stamp) {
        if (stamp == 0L) return;
        long age = now - stamp;
        if (!down && age > 300L) return;
        if (!pressCenter(raw, pressPoint)) return;
        float cx = pressPoint[0];
        float cy = pressPoint[1];
        float phase;
        int alpha;
        if (down) {
            phase = (age % 650L) / 650f;
            alpha = 225;
        } else {
            phase = Math.min(1f, age / 320f);
            alpha = (int) (215f * (1f - phase));
        }
        int color = colourFor(shown(raw));
        float radius = 4.5f + phase * 12f;

        // Soft outer halo.
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(5.0f);
        pulsePaint.setColor(withAlpha(color, down ? 85 : alpha / 3));
        c.drawCircle(cx, cy, radius + 1.5f, pulsePaint);

        // Crisp colored ring and white inner ring.
        pulsePaint.setStrokeWidth(2.2f);
        pulsePaint.setColor(withAlpha(color, alpha));
        c.drawCircle(cx, cy, radius, pulsePaint);
        pulsePaint.setStrokeWidth(1.0f);
        pulsePaint.setColor(withAlpha(0xFFFFFFFF, down ? 190 : alpha / 2));
        c.drawCircle(cx, cy, 3.5f + phase * 6f, pulsePaint);

        if (down) {
            pulsePaint.setStyle(Paint.Style.FILL);
            pulsePaint.setColor(withAlpha(color, 160));
            c.drawCircle(cx, cy, 2.6f, pulsePaint);
            pulsePaint.setStyle(Paint.Style.STROKE);
        }
    }

    private boolean pressCenter(int raw, float[] out) {
        switch (raw) {
            case 304: out[0] = 311f; out[1] = 46f; return true;
            case 305: out[0] = 296f; out[1] = 61f; return true;
            case 306: out[0] = 338f; out[1] = 73f; return true;
            case 307: out[0] = 296f; out[1] = 31f; return true;
            case 308: out[0] = 281f; out[1] = 46f; return true;
            case 309: out[0] = 22f; out[1] = 73f; return true;
            case 310: out[0] = 84f; out[1] = 123f; return true;
            case 311: out[0] = 256f; out[1] = 123f; return true;
            case 312: out[0] = 64f; out[1] = 12f; return true;
            case 313: out[0] = 288f; out[1] = 12f; return true;
            case 314: out[0] = 78f; out[1] = 23f; return true;
            case 315: out[0] = 280f; out[1] = 23f; return true;
            case 317: out[0] = 52f; out[1] = 48f; return true;
            case 318: out[0] = 296f; out[1] = 96f; return true;
            case 544: out[0] = 52f; out[1] = 68f; return true;
            case 545: out[0] = 52f; out[1] = 108f; return true;
            case 546: out[0] = 32f; out[1] = 88f; return true;
            case 547: out[0] = 72f; out[1] = 88f; return true;
            case 102: out[0] = 75f; out[1] = 120f; return true;
            case 158: out[0] = 285f; out[1] = 120f; return true;
            case 114: out[0] = 160f; out[1] = 17f; return true;
            case 115: out[0] = 187f; out[1] = 17f; return true;
            default: return false;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private void drawDpad(Canvas c, float cx, float cy) {
        float a = 20;
        float arm = 7;
        int[] codes = {544, 545, 546, 547};
        boolean[] on = new boolean[4];
        for (int i = 0; i < 4; i++) on[i] = isDown(codes[i]);
        if (hatY < -0.5f) on[0] = true;
        if (hatY > 0.5f) on[1] = true;
        if (hatX < -0.5f) on[2] = true;
        if (hatX > 0.5f) on[3] = true;
        boolean any = on[0] || on[1] || on[2] || on[3];
        if (any) {
            glow.setColor(0x557ED957);
            c.drawCircle(cx, cy, a + 6, glow);
        }
        outline.setColor(any ? 0xFFB7F5D1 : 0x66FFFFFF);
        c.drawLine(cx, cy - a, cx, cy + a, outline);
        c.drawLine(cx - a, cy, cx + a, cy, outline);
        fill.setColor(0x22000000);
        c.drawCircle(cx, cy, arm, fill);
        triangle(c, cx, cy - a + 7, 0, on[0]);
        triangle(c, cx, cy + a - 7, 180, on[1]);
        triangle(c, cx - a + 7, cy, -90, on[2]);
        triangle(c, cx + a - 7, cy, 90, on[3]);
    }

    private void triangle(Canvas c, float x, float y, float angle, boolean on) {
        c.save();
        c.rotate(angle, x, y);
        shape.reset();
        shape.moveTo(x, y - 6);
        shape.lineTo(x - 5, y + 4);
        shape.lineTo(x + 5, y + 4);
        shape.close();
        fill.setColor(on ? 0xFF7ED957 : 0x99FFFFFF);
        c.drawPath(shape, fill);
        c.restore();
    }

    private void drawStick(Canvas c, float x, float y, int raw) {
        boolean left = raw == 317;
        float ax = left ? leftX : rightX;
        float ay = left ? leftY : rightY;
        float mag = (float) Math.hypot(ax, ay);
        boolean on = isDown(raw) || mag > 0.22f;
        if (on) {
            glow.setColor(0x6680D8FF);
            c.drawCircle(x, y, 24, glow);
            fill.setColor(0x4480D8FF);
            c.drawCircle(x, y, 13, fill);
        }
        outline.setColor(on ? 0xFF80D8FF : 0x66FFFFFF);
        outline.setStrokeWidth(on ? 2.4f : 1.45f);
        c.drawCircle(x, y, 13, outline);
        c.drawCircle(x, y, 9, outline);
        outline.setStrokeWidth(1.45f);

        if (mag > 0.22f) {
            float nx = ax / mag;
            float ny = ay / mag;
            float edgeX = x + nx * 9f;
            float edgeY = y + ny * 9f;
            outline.setColor(0xFF80D8FF);
            outline.setStrokeWidth(2.4f);
            c.drawLine(x, y, edgeX, edgeY, outline);
            double head = Math.atan2(ny, nx);
            float hx = edgeX + (float) Math.cos(head) * 4f;
            float hy = edgeY + (float) Math.sin(head) * 4f;
            float lx1 = hx + (float) Math.cos(head + 2.5f) * 4f;
            float ly1 = hy + (float) Math.sin(head + 2.5f) * 4f;
            float lx2 = hx + (float) Math.cos(head - 2.5f) * 4f;
            float ly2 = hy + (float) Math.sin(head - 2.5f) * 4f;
            c.drawLine(hx, hy, lx1, ly1, outline);
            c.drawLine(hx, hy, lx2, ly2, outline);
            fill.setColor(0xAA80D8FF);
            c.drawCircle(edgeX, edgeY, 4f, fill);
            outline.setStrokeWidth(1.45f);
        } else {
            fill.setColor(0x66000000);
            c.drawCircle(x, y, 9, fill);
            c.drawCircle(x, y, 3, outline);
        }
    }

    private void drawFace(Canvas c, float x, float y, int raw, String label, int colour) {
        boolean on = isDown(raw);
        float r = 6.5f;
        if (on) {
            glow.setColor((colour & 0x00FFFFFF) | 0x55000000);
            c.drawCircle(x, y, r * 1.55f, glow);
        }
        fill.setColor((colour & 0x00FFFFFF) | (on ? 0xFF000000 : 0x44000000));
        c.drawCircle(x, y, r, fill);
        outline.setColor((colour & 0x00FFFFFF) | (on ? 0xFF000000 : 0xA0000000));
        outline.setStrokeWidth(1.4f);
        c.drawCircle(x, y, r, outline);
        text.setColor(on ? 0xFF071016 : 0xFFFFFFFF);
        text.setTextSize(7.4f);
        c.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text);
        outline.setStrokeWidth(1.45f);
    }

    private void drawSmallFace(Canvas c, float x, float y, int raw,
                               String label, int colour) {
        boolean on = isDown(raw);
        float r = 6.6f;
        if (on) {
            glow.setColor((colour & 0x00FFFFFF) | 0x66000000);
            c.drawCircle(x, y, r * 1.8f, glow);
        }
        fill.setColor((colour & 0x00FFFFFF) | (on ? 0xFF000000 : 0x44000000));
        c.drawCircle(x, y, r, fill);
        outline.setColor((colour & 0x00FFFFFF) | (on ? 0xFF000000 : 0xAA000000));
        c.drawCircle(x, y, r, outline);
        text.setColor(on ? 0xFF071016 : 0xFFFFFFFF);
        text.setTextSize(6.8f);
        c.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text);
    }

    private void drawPill(Canvas c, float l, float t, float r, float b,
                          int raw, String label) {
        boolean on = isDown(raw);
        if (on) {
            glow.setColor(0x66FFD54D);
            rect.set(l - 2, t - 2, r + 2, b + 2);
            c.drawRoundRect(rect, 5, 5, glow);
        }
        outline.setColor(on ? 0xFFFFD54D : 0x55FFFFFF);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, 4, 4, outline);
        text.setColor(on ? 0xFFFFD54D : 0x88FFFFFF);
        text.setTextSize(6f);
        c.drawText(label, (l + r) / 2f, (t + b) / 2f + 2f, text);
    }

    private void drawCurvedShoulder(Canvas c, boolean left, int raw, String label) {
        boolean on = isDown(raw);
        Path p = new Path();
        if (left) {
            // Exactly the top-left corner segment from the chassis outline.
            p.moveTo(18, 55);
            p.quadTo(24, 17, 62, 17);
        } else {
            // Exactly the top-right corner segment from the chassis outline.
            p.moveTo(342, 55);
            p.quadTo(336, 17, 298, 17);
        }
        Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG);
        wide.setStyle(Paint.Style.STROKE);
        wide.setStrokeCap(Paint.Cap.ROUND);
        wide.setStrokeJoin(Paint.Join.ROUND);

        wide.setStrokeWidth(10f);
        wide.setColor(0x33000000);
        c.drawPath(p, wide);
        wide.setStrokeWidth(7.2f);
        wide.setColor(on ? 0xAAFFD54D : 0x77FFFFFF);
        c.drawPath(p, wide);
        wide.setStrokeWidth(5.2f);
        wide.setColor(on ? 0xDD3A2B07 : 0xDD1A2430);
        c.drawPath(p, wide);
        wide.setStrokeWidth(1.1f);
        wide.setColor(on ? 0xCCFFD54D : 0x44FFFFFF);
        c.drawPath(p, wide);

        text.setColor(on ? 0xFFFFD54D : 0xCCFFFFFF);
        text.setTextSize(6.1f);
        c.drawText(label, left ? 31f : 329f, 47f, text);
    }

    private void drawEdgeControl(Canvas c, float l, float r, float y,
                                 int raw, String label, float labelSize) {
        boolean on = isDown(raw);
        Path p = new Path();
        p.moveTo(l, y);
        p.lineTo(r, y);
        Paint wide = new Paint(Paint.ANTI_ALIAS_FLAG);
        wide.setStyle(Paint.Style.STROKE);
        wide.setStrokeCap(Paint.Cap.ROUND);
        wide.setStrokeJoin(Paint.Join.ROUND);

        wide.setStrokeWidth(10f);
        wide.setColor(0x55000000);
        c.drawPath(p, wide);
        wide.setStrokeWidth(8f);
        wide.setColor(on ? 0xAAFFD54D : 0x88FFFFFF);
        c.drawPath(p, wide);
        wide.setStrokeWidth(6f);
        wide.setColor(on ? 0xDD3A2B07 : 0xDD1A2430);
        c.drawPath(p, wide);
        wide.setStrokeWidth(1.2f);
        wide.setColor(on ? 0xCCFFD54D : 0x44FFFFFF);
        c.drawPath(p, wide);

        text.setColor(on ? 0xFFFFD54D : 0xCCFFFFFF);
        text.setTextSize(labelSize);
        c.drawText(label, (l + r) / 2f, y - 7f, text);
    }

    private void drawShoulder(Canvas c, float l, float t, float r, float b,
                              int raw, String label) {
        boolean on = isDown(raw);
        if (on) {
            glow.setColor(0x44FFD54D);
            rect.set(l - 2, t - 2, r + 2, b + 2);
            c.drawRoundRect(rect, 8, 8, glow);
        }
        rect.set(l, t, r, b);
        fill.setColor(on ? 0xDD3A2B07 : 0xDD1A2430);
        c.drawRoundRect(rect, 6, 6, fill);
        outline.setColor(on ? 0xFFFFD54D : 0xAAFFFFFF);
        outline.setStrokeWidth(1.3f);
        c.drawRoundRect(rect, 6, 6, outline);
        outline.setStrokeWidth(1f);
        outline.setColor(on ? 0x55FFD54D : 0x22FFFFFF);
        c.drawLine(l + 5, t + 1.8f, r - 5, t + 1.8f, outline);
        text.setColor(on ? 0xFFFFD54D : 0xCCFFFFFF);
        text.setTextSize(6.1f);
        c.drawText(label, (l + r) / 2f, (t + b) / 2f + 2.1f, text);
        outline.setStrokeWidth(1.45f);
    }

    private void drawVolume(Canvas c, float l, float t, float r, float b) {
        boolean down = isDown(114);
        boolean up = isDown(115);
        boolean on = down || up;
        if (on) {
            glow.setColor(0x44FFD54D);
            rect.set(l - 2, t - 2, r + 2, b + 2);
            c.drawRoundRect(rect, 8, 8, glow);
        }
        rect.set(l, t, r, b);
        fill.setColor(on ? 0xDD3A2B07 : 0xDD1A2430);
        c.drawRoundRect(rect, 6, 6, fill);
        outline.setColor(on ? 0xFFFFD54D : 0xAAFFFFFF);
        outline.setStrokeWidth(1.3f);
        c.drawRoundRect(rect, 6, 6, outline);
        outline.setStrokeWidth(1f);
        outline.setColor(0x33FFFFFF);
        c.drawLine((l + r) / 2f, t + 2.2f, (l + r) / 2f, b - 2.2f, outline);
        c.drawLine(l + 5, t + 1.8f, r - 5, t + 1.8f, outline);
        text.setTextSize(8.4f);
        text.setColor(down ? 0xFFFF8A80 : 0xCCFFFFFF);
        c.drawText("-", l + (r - l) * 0.25f, b - 3.3f, text);
        text.setColor(up ? 0xFF7ED957 : 0xCCFFFFFF);
        c.drawText("+", l + (r - l) * 0.75f, b - 3.3f, text);
        outline.setStrokeWidth(1.45f);
    }

    private void drawHomeKey(Canvas c, float x, float y) {
        boolean on = isDown(102);
        if (on) {
            glow.setColor(0x6680D8FF);
            c.drawCircle(x, y, 12, glow);
        }
        outline.setColor(on ? 0xFF80D8FF : 0x77FFFFFF);
        fill.setColor(on ? 0x4480D8FF : 0x22000000);
        c.drawCircle(x, y, 7, fill);
        c.drawCircle(x, y, 7, outline);
        shape.reset();
        shape.moveTo(x - 4, y);
        shape.lineTo(x, y - 4);
        shape.lineTo(x + 4, y);
        shape.lineTo(x + 3, y);
        shape.lineTo(x + 3, y + 4);
        shape.lineTo(x - 3, y + 4);
        shape.lineTo(x - 3, y);
        shape.close();
        c.drawPath(shape, outline);
        outline.setColor(0x77FFFFFF);
    }

    private void drawBackKey(Canvas c, float x, float y) {
        boolean on = isDown(158);
        if (on) {
            glow.setColor(0x66FF8A80);
            c.drawCircle(x, y, 12, glow);
        }
        outline.setColor(on ? 0xFFFF8A80 : 0x77FFFFFF);
        fill.setColor(on ? 0x44FF8A80 : 0x22000000);
        c.drawCircle(x, y, 7, fill);
        c.drawCircle(x, y, 7, outline);
        shape.reset();
        shape.moveTo(x + 4, y - 4);
        shape.lineTo(x - 3, y);
        shape.lineTo(x + 4, y + 4);
        c.drawPath(shape, outline);
        outline.setColor(0x77FFFFFF);
    }

    private void drawRearKey(Canvas c, float x, float y, int raw,
                             String label, int colour) {
        boolean on = isDown(raw);
        if (on) {
            glow.setColor((colour & 0x00FFFFFF) | 0x66000000);
            c.drawOval(new RectF(x - 10, y - 6, x + 10, y + 6), glow);
        }
        outline.setColor(on ? colour : ((colour & 0x00FFFFFF) | 0x88000000));
        rect.set(x - 7, y - 4, x + 7, y + 4);
        c.drawRoundRect(rect, 4, 4, outline);
        text.setColor(on ? 0xFF071016 : ((colour & 0x00FFFFFF) | 0xBB000000));
        text.setTextSize(5.5f);
        c.drawText(label, x, y + 2f, text);
    }

    private void drawTinyTop(Canvas c, float x, float y, int raw, String label) {
        boolean on = isDown(raw);
        if (on) {
            glow.setColor(0x66B388FF);
            c.drawCircle(x, y, 10, glow);
        }
        outline.setColor(on ? 0xFFB388FF : 0x55FFFFFF);
        rect.set(x - 4, y - 4, x + 4, y + 4);
        c.drawRoundRect(rect, 1.5f, 1.5f, outline);
        text.setColor(on ? 0xFFB388FF : 0x77FFFFFF);
        text.setTextSize(3.6f);
        c.drawText(label, x, y + 10, text);
    }

    private void drawTopPill(Canvas c, float l, float t, float r, float b,
                             int raw, String label) {
        boolean on = isDown(raw);
        if (on) {
            glow.setColor(0x66FFD54D);
            rect.set(l - 2, t - 2, r + 2, b + 2);
            c.drawRoundRect(rect, 4, 4, glow);
        }
        outline.setColor(on ? 0xFFFFD54D : 0x55FFFFFF);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, 4, 4, outline);
        text.setColor(on ? 0xFFFFD54D : 0x66FFFFFF);
        text.setTextSize(3.4f);
        c.drawText(label, (l + r) / 2f, b + 9, text);
    }

    private void drawSmall(Canvas c, float x, float y, int raw, String label) {
        boolean on = isDown(raw);
        if (on) {
            glow.setColor(0x66B388FF);
            c.drawCircle(x, y, 10, glow);
        }
        outline.setColor(on ? 0xFFB388FF : 0x55FFFFFF);
        c.drawCircle(x, y, 5, outline);
        text.setColor(on ? 0xFFB388FF : 0x66FFFFFF);
        text.setTextSize(4.2f);
        c.drawText(label, x, y + 13, text);
    }

    private void drawSpeaker(Canvas c, float l, float t, float r, float b) {
        fill.setColor(0x55FFFFFF);
        int lines = 5;
        for (int i = 0; i < lines; i++) {
            float x = l + (r - l) * i / (float) (lines - 1);
            c.drawLine(x, t, x, b, fill);
        }
    }
}
