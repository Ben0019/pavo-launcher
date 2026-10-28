package com.ben.aurora;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.Surface;
import android.view.View;

/**
 * Applies a calibrated, smoothed gyro tilt to one of the animated device
 * outlines. It uses the fused rotation vector and measures relative to the
 * orientation at the moment it becomes visible.
 */
final class GyroTiltController implements SensorEventListener {
    private static final float PITCH_LIMIT = 11f;
    private static final float YAW_LIMIT = 14f;
    private static final float ROLL_LIMIT = 9f;

    private final View target;
    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final float[] rotation = new float[9];
    private final float[] remapped = new float[9];
    private final float[] base = new float[9];
    private final float[] delta = new float[9];
    private final float[] orientation = new float[3];

    private boolean registered;
    private boolean baseSet;
    private boolean geometrySet;
    private float smoothPitch;
    private float smoothYaw;
    private float smoothRoll;
    private float smoothScale = 1f;

    GyroTiltController(Context context, View target) {
        this.target = target;
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor selected = null;
        if (manager != null) {
            selected = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (selected == null) {
                selected = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
        }
        sensorManager = manager;
        sensor = selected;
        target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                configureGeometry();
            }
        });
    }

    void start() {
        if (registered || sensorManager == null || sensor == null) return;
        configureGeometry();
        baseSet = false;
        smoothPitch = smoothYaw = smoothRoll = 0f;
        smoothScale = 1f;
        resetTransform();
        registered = sensorManager.registerListener(this, sensor,
                SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (registered && sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        registered = false;
        resetTransform();
    }

    private void configureGeometry() {
        int w = target.getWidth();
        int h = target.getHeight();
        if (w <= 0 || h <= 0) return;
        target.setPivotX(w / 2f);
        target.setPivotY(h / 2f);
        target.setCameraDistance(Math.max(1f, Math.max(w, h) * 4f));
        geometrySet = true;
    }

    private void resetTransform() {
        target.setRotationX(0f);
        target.setRotationY(0f);
        target.setRotation(0f);
        target.setScaleX(1f);
        target.setScaleY(1f);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == null) return;
        int type = event.sensor.getType();
        if (type != Sensor.TYPE_GAME_ROTATION_VECTOR
                && type != Sensor.TYPE_ROTATION_VECTOR) return;
        if (event.values.length < 4) return;

        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        remapToDisplay(rotation, remapped);
        if (!baseSet) {
            System.arraycopy(remapped, 0, base, 0, 9);
            baseSet = true;
            return;
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float sum = 0f;
                for (int k = 0; k < 3; k++) {
                    sum += base[k * 3 + row] * remapped[k * 3 + col];
                }
                delta[row * 3 + col] = sum;
            }
        }
        SensorManager.getOrientation(delta, orientation);

        float targetPitch = clamp((float) Math.toDegrees(orientation[1]) * 0.55f,
                PITCH_LIMIT);
        float targetYaw = clamp((float) Math.toDegrees(orientation[0]) * 0.55f,
                YAW_LIMIT);
        float targetRoll = clamp((float) Math.toDegrees(orientation[2]) * 0.55f,
                ROLL_LIMIT);
        smoothPitch += (targetPitch - smoothPitch) * 0.18f;
        smoothYaw += (targetYaw - smoothYaw) * 0.18f;
        smoothRoll += (targetRoll - smoothRoll) * 0.18f;
        applyTransform();
    }

    float getPitch() {
        return smoothPitch;
    }

    float getYaw() {
        return smoothYaw;
    }

    float getRoll() {
        return smoothRoll;
    }

    private void applyTransform() {
        if (!geometrySet) configureGeometry();
        int w = target.getWidth();
        int h = target.getHeight();
        if (w <= 0 || h <= 0) return;

        double pitchRad = Math.toRadians(smoothPitch);
        double yawRad = Math.toRadians(smoothYaw);
        double rollRad = Math.toRadians(smoothRoll);
        float projectedHeight = h * (float) Math.abs(Math.cos(pitchRad) * Math.cos(rollRad))
                + w * (float) Math.abs(Math.sin(rollRad))
                + w * (float) Math.abs(Math.sin(yawRad)) * 0.12f;
        float fit = Math.min(1f, 0.96f * h / Math.max(h, projectedHeight));
        fit = Math.max(0.66f, fit);
        smoothScale += (fit - smoothScale) * 0.25f;

        target.setScaleX(smoothScale);
        target.setScaleY(smoothScale);
        target.setRotationX(smoothPitch);
        target.setRotationY(-smoothYaw);
        target.setRotation(smoothRoll);
    }

    private void remapToDisplay(float[] in, float[] out) {
        Display display = target.getDisplay();
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

    private static float clamp(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    @Override
    public void onAccuracyChanged(Sensor changed, int accuracy) {
    }
}
