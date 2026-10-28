package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small header glyph for Bluetooth and notification-sound state. */
final class HeaderStatusGlyph extends View {

    static final int BLUETOOTH = 0;
    static final int SOUND = 1;
    static final int BLUETOOTH_OFF = 0;
    static final int BLUETOOTH_ON = 1;
    static final int BLUETOOTH_CONNECTED = 2;
    static final int SOUND_NORMAL = 0;
    static final int SOUND_VIBRATE = 1;
    static final int SOUND_SILENT = 2;

    private final int type;
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private int bluetoothMode = BLUETOOTH_OFF;
    private int soundMode = SOUND_NORMAL;
    private boolean focusRingVisible;
    private int focusRingColour = 0xFF8BE9FF;

    HeaderStatusGlyph(Context c, int type) {
        super(c);
        this.type = type;
        density = c.getResources().getDisplayMetrics().density;
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setStrokeWidth(3.2f * density);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(1.45f * density);
        focus.setStyle(Paint.Style.STROKE);
        focus.setStrokeCap(Paint.Cap.ROUND);
        focus.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setFocusRing(boolean visible, int colour) {
        focusRingVisible = visible;
        focusRingColour = colour;
        invalidate();
    }

    void setBluetoothMode(int mode) {
        int next = mode == BLUETOOTH_ON || mode == BLUETOOTH_CONNECTED
                ? mode : BLUETOOTH_OFF;
        if (bluetoothMode == next) return;
        bluetoothMode = next;
        invalidate();
    }

    void setSoundMode(int mode) {
        int next = mode == SOUND_VIBRATE || mode == SOUND_SILENT
                ? mode : SOUND_NORMAL;
        if (soundMode == next) return;
        soundMode = next;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        buildPath(w, h);
        int colour;
        if (type == BLUETOOTH) {
            colour = bluetoothMode == BLUETOOTH_CONNECTED ? 0xFF00E6A8
                    : bluetoothMode == BLUETOOTH_ON ? 0xFFFFC107 : 0xFFFF3D3D;
        } else {
            colour = soundMode == SOUND_NORMAL ? 0xFF4FC3FF
                    : soundMode == SOUND_VIBRATE ? 0xFFFFC107 : 0xFFFF3D3D;
        }
        colour = ColorTuning.colour(colour);
        if (focusRingVisible) {
            int ring = ColorTuning.colour(focusRingColour);
            focus.setStrokeWidth(4.8f * density);
            focus.setColor(ColorTuning.withAlpha(ring,
                    ColorTuning.glowAlpha(0x66)));
            canvas.drawPath(path, focus);
            focus.setStrokeWidth(2.1f * density);
            focus.setColor(ColorTuning.withAlpha(ring, 0xE6));
            canvas.drawPath(path, focus);
        }
        glow.setStrokeWidth(3.2f * density * ColorTuning.glowScale());
        glow.setColor(ColorTuning.withAlpha(colour, ColorTuning.glowAlpha(0x46)));
        canvas.drawPath(path, glow);
        line.setColor(colour);
        canvas.drawPath(path, line);

        if (type == BLUETOOTH && bluetoothMode == BLUETOOTH_OFF) {
            if (focusRingVisible) {
                int ring = ColorTuning.colour(focusRingColour);
                focus.setStrokeWidth(4.8f * density);
                focus.setColor(ColorTuning.withAlpha(ring, ColorTuning.glowAlpha(0x66)));
                canvas.drawLine(w * 0.18f, h * 0.14f, w * 0.82f, h * 0.86f, focus);
                focus.setStrokeWidth(2.1f * density);
                focus.setColor(ColorTuning.withAlpha(ring, 0xE6));
                canvas.drawLine(w * 0.18f, h * 0.14f, w * 0.82f, h * 0.86f, focus);
            }
            // A dark under-stroke keeps the red slash readable across the glyph.
            line.setColor(0xE60B0E14);
            line.setStrokeWidth(3.6f * density);
            canvas.drawLine(w * 0.18f, h * 0.14f, w * 0.82f, h * 0.86f, line);
            line.setColor(colour);
            line.setStrokeWidth(1.45f * density);
            canvas.drawLine(w * 0.18f, h * 0.14f, w * 0.82f, h * 0.86f, line);
        } else if (type == BLUETOOTH && bluetoothMode == BLUETOOTH_CONNECTED) {
            // Android's connected Bluetooth icon adds a dot on each side.
            float dotX = w * 0.18f;
            float dotY = h * 0.50f;
            float dotR = Math.max(1f, w * 0.065f);
            if (focusRingVisible) {
                int ring = ColorTuning.colour(focusRingColour);
                focus.setColor(ColorTuning.withAlpha(ring, ColorTuning.glowAlpha(0x66)));
                focus.setStrokeWidth(4.8f * density);
                canvas.drawCircle(dotX, dotY, dotR, focus);
                canvas.drawCircle(w - dotX, dotY, dotR, focus);
                focus.setStrokeWidth(2.1f * density);
                focus.setColor(ColorTuning.withAlpha(ring, 0xE6));
                canvas.drawCircle(dotX, dotY, dotR, focus);
                canvas.drawCircle(w - dotX, dotY, dotR, focus);
            }
            glow.setStyle(Paint.Style.FILL);
            glow.setColor(ColorTuning.withAlpha(colour, ColorTuning.glowAlpha(0x55)));
            canvas.drawCircle(dotX, dotY, dotR * 1.8f, glow);
            canvas.drawCircle(w - dotX, dotY, dotR * 1.8f, glow);
            glow.setStyle(Paint.Style.STROKE);
            dot.setColor(colour);
            canvas.drawCircle(dotX, dotY, dotR, dot);
            canvas.drawCircle(w - dotX, dotY, dotR, dot);
        }

        if (type == SOUND && soundMode == SOUND_SILENT) {
            if (focusRingVisible) {
                int ring = ColorTuning.colour(focusRingColour);
                focus.setStrokeWidth(4.8f * density);
                focus.setColor(ColorTuning.withAlpha(ring, ColorTuning.glowAlpha(0x66)));
                canvas.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, focus);
                focus.setStrokeWidth(2.1f * density);
                focus.setColor(ColorTuning.withAlpha(ring, 0xE6));
                canvas.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, focus);
            }
            // A compact mute slash makes silent distinct from disconnected red.
            line.setColor(ColorTuning.colour(0xFFFF3D3D));
            canvas.drawLine(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f, line);
        } else if (type == SOUND && soundMode == SOUND_VIBRATE) {
            // Two side ticks read as vibration without replacing the bell.
            line.setColor(ColorTuning.colour(0xFFFFC107));
            line.setStrokeWidth(Math.max(1f, density * 1.1f));
            canvas.drawLine(w * 0.10f, h * 0.42f, w * 0.10f, h * 0.58f, line);
            canvas.drawLine(w * 0.90f, h * 0.42f, w * 0.90f, h * 0.58f, line);
            line.setStrokeWidth(Math.max(1f, density * 1.45f));
        }
    }

    private void buildPath(float w, float h) {
        path.reset();
        if (type == BLUETOOTH) {
            float stemX = w * 0.36f;
            float top = h * 0.10f;
            float bottom = h * 0.90f;
            path.moveTo(stemX, top);
            path.lineTo(stemX, bottom);
            path.moveTo(stemX, top);
            path.lineTo(w * 0.74f, h * 0.33f);
            path.lineTo(stemX, h * 0.50f);
            path.moveTo(stemX, h * 0.50f);
            path.lineTo(w * 0.74f, h * 0.67f);
            path.lineTo(stemX, bottom);
        } else {
            path.moveTo(w * 0.16f, h * 0.70f);
            path.cubicTo(w * 0.24f, h * 0.64f, w * 0.26f, h * 0.54f,
                    w * 0.28f, h * 0.42f);
            path.cubicTo(w * 0.31f, h * 0.22f, w * 0.69f, h * 0.22f,
                    w * 0.72f, h * 0.42f);
            path.cubicTo(w * 0.74f, h * 0.54f, w * 0.76f, h * 0.64f,
                    w * 0.84f, h * 0.70f);
            path.close();
            path.moveTo(w * 0.14f, h * 0.73f);
            path.lineTo(w * 0.86f, h * 0.73f);
            path.moveTo(w * 0.42f, h * 0.80f);
            path.quadTo(w * 0.50f, h * 0.90f, w * 0.58f, h * 0.80f);
        }
    }
}
