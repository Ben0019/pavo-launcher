package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line-art icons for the Launcher settings section rows. */
final class SettingsOptionIcon extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final String key;
    private final float density;
    private final int baseColor;
    private int appliedVersion = -1;

    SettingsOptionIcon(Context c, String key, int color) {
        super(c);
        this.key = key;
        this.baseColor = color;
        density = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.7f * density);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        applyColorTuning();
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void applyColorTuning() {
        int color = ColorTuning.colour(baseColor);
        stroke.setColor(color);
        stroke.setShadowLayer(ColorTuning.radius(2.6f * density), 0f, 0f,
                ColorTuning.withAlpha(color, ColorTuning.glowAlpha(0xA0)));
        fill.setColor((color & 0x00FFFFFF) | 0x38000000);
        appliedVersion = ColorTuning.version();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (appliedVersion != ColorTuning.version()) applyColorTuning();
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        float scale = size / (24f * density);
        canvas.save();
        canvas.translate((getWidth() - 24f * density * scale) / 2f,
                (getHeight() - 24f * density * scale) / 2f);
        canvas.scale(scale, scale);
        float s = density;
        switch (key == null ? "" : key) {
            case "Dock": drawDock(canvas, s); break;
            case "Transparency": drawTransparency(canvas, s); break;
            case "Home": drawHome(canvas, s); break;
            case "Colors": drawColors(canvas, s); break;
            case "Gestures": drawGestures(canvas, s); break;
            case "Feedback": drawFeedback(canvas, s); break;
            case "Controls": drawControls(canvas, s); break;
            case "Drawer": drawDrawer(canvas, s); break;
            case "Widgets": drawWidgets(canvas, s); break;
            case "Performance": drawPerformance(canvas, s); break;
            case "Wallpaper": drawWallpaper(canvas, s); break;
            case "Backup": drawBackup(canvas, s); break;
            case "Language": drawLanguage(canvas, s); break;
            default: drawDots(canvas, s); break;
        }
        canvas.restore();
    }

    private void drawLanguage(Canvas c, float s) {
        // A small globe with a typed "A" reads as a language picker.
        c.drawCircle(11 * s, 12 * s, 7.5f * s, stroke);
        rect.set(3.5f * s, 8.5f * s, 18.5f * s, 15.5f * s);
        c.drawOval(rect, stroke);
        c.drawLine(11 * s, 4.5f * s, 11 * s, 19.5f * s, stroke);
        c.drawLine(4 * s, 12 * s, 18 * s, 12 * s, stroke);
        c.drawCircle(17.5f * s, 18 * s, 3.2f * s, fill);
        c.drawCircle(17.5f * s, 18 * s, 3.2f * s, stroke);
    }

    private void drawDock(Canvas c, float s) {
        rect.set(2 * s, 13 * s, 22 * s, 20 * s);
        c.drawRoundRect(rect, 3 * s, 3 * s, stroke);
        c.drawCircle(7 * s, 16.5f * s, 1.2f * s, stroke);
        c.drawCircle(12 * s, 16.5f * s, 1.2f * s, stroke);
        c.drawCircle(17 * s, 16.5f * s, 1.2f * s, stroke);
    }

    private void drawTransparency(Canvas c, float s) {
        rect.set(2.5f * s, 5 * s, 13 * s, 15.5f * s);
        c.drawRoundRect(rect, 3 * s, 3 * s, stroke);
        rect.set(11 * s, 8.5f * s, 21.5f * s, 19 * s);
        c.drawRoundRect(rect, 3 * s, 3 * s, stroke);
        fill.setAlpha(42);
        c.drawRoundRect(rect, 3 * s, 3 * s, fill);
        fill.setAlpha(0x33);
    }

    private void drawHome(Canvas c, float s) {
        path.reset();
        path.moveTo(2.5f * s, 11.5f * s);
        path.lineTo(12 * s, 3 * s);
        path.lineTo(21.5f * s, 11.5f * s);
        c.drawPath(path, stroke);
        rect.set(5 * s, 10 * s, 19 * s, 21 * s);
        c.drawRoundRect(rect, 2 * s, 2 * s, stroke);
        rect.set(10.5f * s, 15 * s, 13.5f * s, 21 * s);
        c.drawRoundRect(rect, 1.5f * s, 1.5f * s, stroke);
    }

    private void drawColors(Canvas c, float s) {
        // Three overlapping swatches read as a compact colour palette.
        c.drawCircle(9 * s, 9 * s, 4.6f * s, stroke);
        c.drawCircle(15 * s, 12 * s, 4.6f * s, stroke);
        c.drawCircle(10 * s, 16 * s, 4.6f * s, stroke);
        c.drawCircle(12 * s, 12 * s, 1.4f * s, fill);
    }

    private void drawGestures(Canvas c, float s) {
        rect.set(5.5f * s, 6 * s, 18.5f * s, 21 * s);
        c.drawRoundRect(rect, 6.5f * s, 6.5f * s, stroke);
        c.drawLine(12 * s, 7 * s, 12 * s, 14 * s, stroke);
        path.reset();
        path.moveTo(9.5f * s, 11.5f * s);
        path.lineTo(12 * s, 14 * s);
        path.lineTo(14.5f * s, 11.5f * s);
        c.drawPath(path, stroke);
        path.reset();
        path.moveTo(2.5f * s, 4.5f * s);
        path.quadTo(12 * s, 0.5f * s, 21.5f * s, 4.5f * s);
        c.drawPath(path, stroke);
    }

    private void drawFeedback(Canvas c, float s) {
        rect.set(8 * s, 3.5f * s, 16 * s, 20.5f * s);
        c.drawRoundRect(rect, 2.5f * s, 2.5f * s, stroke);
        c.drawCircle(12 * s, 17.5f * s, 0.9f * s, stroke);
        path.reset();
        path.moveTo(5 * s, 8 * s);
        path.quadTo(2.5f * s, 12 * s, 5 * s, 16 * s);
        c.drawPath(path, stroke);
        path.reset();
        path.moveTo(19 * s, 8 * s);
        path.quadTo(21.5f * s, 12 * s, 19 * s, 16 * s);
        c.drawPath(path, stroke);
    }

    private void drawControls(Canvas c, float s) {
        rect.set(3 * s, 7 * s, 21 * s, 18 * s);
        c.drawRoundRect(rect, 5 * s, 5 * s, stroke);
        c.drawLine(7 * s, 11 * s, 11 * s, 11 * s, stroke);
        c.drawLine(9 * s, 9 * s, 9 * s, 13 * s, stroke);
        c.drawCircle(15.5f * s, 10.5f * s, 1.1f * s, stroke);
        c.drawCircle(18 * s, 13 * s, 1.1f * s, stroke);
        c.drawCircle(13 * s, 13 * s, 1.1f * s, stroke);
        c.drawLine(8 * s, 21 * s, 10 * s, 18 * s, stroke);
        c.drawLine(16 * s, 18 * s, 18 * s, 21 * s, stroke);
    }

    private void drawDrawer(Canvas c, float s) {
        rect.set(3 * s, 4 * s, 21 * s, 20 * s);
        c.drawRoundRect(rect, 3 * s, 3 * s, stroke);
        c.drawLine(7 * s, 9 * s, 17 * s, 9 * s, stroke);
        c.drawLine(7 * s, 12.5f * s, 17 * s, 12.5f * s, stroke);
        c.drawLine(7 * s, 16 * s, 13 * s, 16 * s, stroke);
    }

    private void drawWidgets(Canvas c, float s) {
        rect.set(3 * s, 3 * s, 10.5f * s, 10.5f * s);
        c.drawRoundRect(rect, 1.8f * s, 1.8f * s, stroke);
        rect.set(13.5f * s, 3 * s, 21 * s, 7 * s);
        c.drawRoundRect(rect, 1.8f * s, 1.8f * s, stroke);
        rect.set(13.5f * s, 10 * s, 21 * s, 21 * s);
        c.drawRoundRect(rect, 1.8f * s, 1.8f * s, stroke);
        rect.set(3 * s, 13.5f * s, 10.5f * s, 21 * s);
        c.drawRoundRect(rect, 1.8f * s, 1.8f * s, stroke);
    }

    private void drawPerformance(Canvas c, float s) {
        rect.set(3 * s, 5 * s, 21 * s, 21 * s);
        c.drawArc(rect, 200, 140, false, stroke);
        c.drawLine(12 * s, 13 * s, 16.5f * s, 8.5f * s, stroke);
        c.drawCircle(12 * s, 13 * s, 1.3f * s, stroke);
        c.drawLine(5 * s, 19 * s, 8 * s, 19 * s, stroke);
        c.drawLine(16 * s, 19 * s, 19 * s, 19 * s, stroke);
    }

    private void drawWallpaper(Canvas c, float s) {
        rect.set(3 * s, 4 * s, 21 * s, 20 * s);
        c.drawRoundRect(rect, 2.5f * s, 2.5f * s, stroke);
        path.reset();
        path.moveTo(5 * s, 17 * s);
        path.lineTo(10 * s, 11 * s);
        path.lineTo(13 * s, 14 * s);
        path.lineTo(16 * s, 10 * s);
        path.lineTo(19 * s, 13.5f * s);
        c.drawPath(path, stroke);
        c.drawCircle(16 * s, 7 * s, 1.2f * s, stroke);
    }

    private void drawBackup(Canvas c, float s) {
        rect.set(3.5f * s, 4.5f * s, 20.5f * s, 21 * s);
        c.drawRoundRect(rect, 3 * s, 3 * s, stroke);
        c.drawLine(12 * s, 7 * s, 12 * s, 14 * s, stroke);
        path.reset();
        path.moveTo(8.5f * s, 11 * s);
        path.lineTo(12 * s, 14.5f * s);
        path.lineTo(15.5f * s, 11 * s);
        c.drawPath(path, stroke);
        c.drawLine(8 * s, 17.5f * s, 16 * s, 17.5f * s, stroke);
    }

    private void drawDots(Canvas c, float s) {
        c.drawCircle(7 * s, 12 * s, 1.2f * s, stroke);
        c.drawCircle(12 * s, 12 * s, 1.2f * s, stroke);
        c.drawCircle(17 * s, 12 * s, 1.2f * s, stroke);
    }
}
