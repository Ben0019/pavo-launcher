package com.ben.aurora;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Animated drawings for the small set of launcher-owned app icons. The static
 * bitmap remains the source for drag previews and accessibility; this canvas
 * animation only replaces the on-screen rendering of the icon itself.
 */
final class LiveBuiltinIcons {
    static final String PERF_LOG = "com.ben.aurora.perflogs";
    static final String PERF_TUNER = "com.ben.aurora.perftuner";
    static final String DISPLAY = "com.ben.aurora.display";
    static final String CONTROLLER = "com.ben.aurora.controllermap";
    static final String RGB = "com.ben.aurora.rgb";
    static final String FAN = "com.ben.aurora.fan";
    static final String TOUCH = "com.ben.aurora.touchmapper";
    static final String HEALTH = "com.ben.aurora.devicehealth";
    static final String BENCHMARK = "com.ben.aurora.benchmark";
    static final String FILE_MANAGER = "com.ben.aurora.files";
    static final String SCREEN_RECORDER = "com.ben.aurora.screenrecorder";

    private LiveBuiltinIcons() {}

    static boolean supports(String pkg) {
        return PERF_LOG.equals(pkg) || PERF_TUNER.equals(pkg)
                || DISPLAY.equals(pkg) || CONTROLLER.equals(pkg)
                || RGB.equals(pkg) || FAN.equals(pkg)
                || TOUCH.equals(pkg) || HEALTH.equals(pkg)
                || BENCHMARK.equals(pkg) || FILE_MANAGER.equals(pkg)
                || SCREEN_RECORDER.equals(pkg);
    }

    static long duration(String pkg) {
        if (RGB.equals(pkg)) return 16000L;
        if (FAN.equals(pkg)) return 9000L;
        if (PERF_TUNER.equals(pkg)) return 5200L;
        if (DISPLAY.equals(pkg)) return 6200L;
        if (CONTROLLER.equals(pkg)) return 4800L;
        if (PERF_LOG.equals(pkg)) return 4200L;
        if (BENCHMARK.equals(pkg)) return 3600L;
        if (FILE_MANAGER.equals(pkg)) return 6200L;
        if (SCREEN_RECORDER.equals(pkg)) return 2600L;
        if (HEALTH.equals(pkg)) return 3000L;
        return 2400L;
    }

    static void draw(Canvas c, int size, String pkg, int accent, float phase) {
        if (size <= 0 || pkg == null) return;
        drawTile(c, size, accent);
        if (PERF_LOG.equals(pkg)) {
            drawLogs(c, size, accent, phase);
        } else if (PERF_TUNER.equals(pkg)) {
            drawTuner(c, size, accent, phase);
        } else if (DISPLAY.equals(pkg)) {
            drawDisplay(c, size, accent, phase);
        } else if (CONTROLLER.equals(pkg)) {
            drawController(c, size, accent, phase);
        } else if (RGB.equals(pkg)) {
            drawRgb(c, size, accent, phase);
        } else if (FAN.equals(pkg)) {
            drawFan(c, size, accent, phase);
        } else if (TOUCH.equals(pkg)) {
            drawTouch(c, size, accent, phase);
        } else if (HEALTH.equals(pkg)) {
            drawHealth(c, size, accent, phase);
        } else if (BENCHMARK.equals(pkg)) {
            drawBenchmark(c, size, accent, phase);
        } else if (FILE_MANAGER.equals(pkg)) {
            drawFileManager(c, size, accent, phase);
        } else if (SCREEN_RECORDER.equals(pkg)) {
            drawRecorder(c, size, accent, phase);
        }
    }

    private static void drawTile(Canvas c, int size, int accent) {
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor(alpha(accent, 0x66));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);
    }

    private static void drawLogs(Canvas c, int size, int accent, float phase) {
        int pad = Math.round(size * 0.22f);
        int bottom = size - pad;
        int width = Math.round(size * 0.09f);
        int gap = Math.round(size * 0.045f);
        int[] heights = {0x2E, 0x54, 0x3E};
        int[] colors = {0xFF69D98B, 0xFFFFC857, 0xFF8BE9FF};
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        float wave = (float) Math.sin(phase * Math.PI * 2.0) * 0.10f;
        for (int i = 0; i < heights.length; i++) {
            bar.setColor(colors[i]);
            float hScale = heights[i] / 255f + wave * (i == 1 ? 1f : 0.55f);
            int h = Math.round((bottom - pad) * Math.max(0.10f, hScale));
            int left = pad + i * (width + gap);
            c.drawRoundRect(new RectF(left, bottom - h, left + width, bottom),
                    width * 0.3f, width * 0.3f, bar);
        }
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1.5f, size * 0.045f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(accent);
        Path path = new Path();
        path.moveTo(pad * 0.9f, bottom - size * 0.17f);
        path.lineTo(size * 0.42f, bottom - size * 0.40f);
        path.lineTo(size * 0.64f, bottom - size * 0.24f);
        path.lineTo(size - pad * 0.9f, bottom - size * 0.58f);
        c.drawPath(path, line);
        float dotX = size * (0.26f + 0.48f * phase);
        float dotY = bottom - size * 0.34f;
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(alpha(accent, 0x88));
        c.drawCircle(dotX, dotY, size * 0.045f, glow);
    }

    private static void drawDisplay(Canvas c, int size, int accent, float phase) {
        Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
        screen.setStyle(Paint.Style.STROKE);
        screen.setStrokeWidth(Math.max(1.6f, size * 0.055f));
        screen.setStrokeJoin(Paint.Join.ROUND);
        screen.setColor(accent);
        RectF frame = new RectF(size * 0.16f, size * 0.22f,
                size * 0.84f, size * 0.67f);
        c.drawRoundRect(frame, size * 0.08f, size * 0.08f, screen);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(Math.max(1.4f, size * 0.045f));
        body.setStrokeCap(Paint.Cap.ROUND);
        body.setColor(accent);
        c.drawLine(size * 0.38f, size * 0.77f, size * 0.62f, size * 0.77f, body);
        c.drawLine(size * 0.50f, size * 0.67f, size * 0.50f, size * 0.77f, body);
        Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        float pulse = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        sun.setColor(blend(0xFFFFC857, accent, 0.28f));
        c.drawCircle(size * 0.50f, size * 0.44f, size * (0.060f + 0.018f * pulse), sun);
        Paint scan = new Paint(Paint.ANTI_ALIAS_FLAG);
        scan.setStyle(Paint.Style.STROKE);
        scan.setStrokeWidth(Math.max(1f, size * 0.018f));
        scan.setColor(alpha(accent, 0x66));
        float sy = frame.top + frame.height() * phase;
        c.drawLine(frame.left + size * 0.04f, sy,
                frame.right - size * 0.04f, sy, scan);
    }

    private static void drawController(Canvas c, int size, int accent, float phase) {
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(Math.max(1.4f, size * 0.042f));
        body.setStrokeCap(Paint.Cap.ROUND);
        body.setStrokeJoin(Paint.Join.ROUND);
        body.setColor(accent);
        RectF pad = new RectF(size * 0.17f, size * 0.31f, size * 0.83f, size * 0.70f);
        c.drawRoundRect(pad, size * 0.16f, size * 0.16f, body);
        c.drawLine(size * 0.30f, size * 0.50f, size * 0.44f, size * 0.50f, body);
        c.drawLine(size * 0.37f, size * 0.43f, size * 0.37f, size * 0.57f, body);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] colors = {0xFFFF6B6B, 0xFF69D98B, 0xFFFFC857, 0xFF8BE9FF};
        float[] xs = {0.66f, 0.73f, 0.66f, 0.59f};
        float[] ys = {0.43f, 0.50f, 0.57f, 0.50f};
        int active = Math.min(colors.length - 1, (int) (phase * colors.length));
        for (int i = 0; i < colors.length; i++) {
            dot.setColor(colors[i]);
            float radius = size * (i == active ? 0.045f : 0.035f);
            c.drawCircle(size * xs[i], size * ys[i], radius, dot);
            if (i == active) {
                Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                ring.setStyle(Paint.Style.STROKE);
                ring.setStrokeWidth(Math.max(1f, size * 0.014f));
                ring.setColor(alpha(colors[i], 0xAA));
                c.drawCircle(size * xs[i], size * ys[i], size * 0.072f, ring);
            }
        }
    }

    private static void drawHealth(Canvas c, int size, int accent, float phase) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f, size * 0.035f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(accent);
        RectF body = new RectF(size * 0.20f, size * 0.31f,
                size * 0.80f, size * 0.75f);
        c.drawRoundRect(body, size * 0.055f, size * 0.055f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(size * 0.43f, size * 0.24f,
                size * 0.57f, size * 0.33f), size * 0.02f, size * 0.02f, p);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0xFF69D98B);
        c.drawRoundRect(new RectF(size * 0.235f, size * 0.345f,
                size * 0.63f, size * 0.715f), size * 0.035f, size * 0.035f, fill);

        Paint pulse = new Paint(Paint.ANTI_ALIAS_FLAG);
        pulse.setStyle(Paint.Style.STROKE);
        pulse.setStrokeWidth(Math.max(1.4f, size * 0.028f));
        pulse.setStrokeCap(Paint.Cap.ROUND);
        pulse.setStrokeJoin(Paint.Join.ROUND);
        pulse.setColor(0xFF101820);
        Path path = new Path();
        path.moveTo(size * 0.23f, size * 0.56f);
        path.lineTo(size * 0.34f, size * 0.56f);
        path.lineTo(size * 0.38f, size * 0.47f);
        path.lineTo(size * 0.44f, size * 0.66f);
        path.lineTo(size * 0.50f, size * 0.54f);
        path.lineTo(size * 0.55f, size * 0.54f);
        c.drawPath(path, pulse);
        Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
        float pulse2 = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        light.setColor(alpha(0xFF69D98B, Math.round(0x66 + 0x99 * pulse2)));
        c.drawCircle(size * 0.77f, size * 0.56f, size * (0.028f + 0.010f * pulse2), light);
    }

    private static void drawBenchmark(Canvas c, int size, int accent, float phase) {
        RectF chip = new RectF(size * 0.22f, size * 0.27f,
                size * 0.78f, size * 0.73f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.3f, size * 0.032f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(alpha(accent, 0xCC));
        c.drawRoundRect(chip, size * 0.055f, size * 0.055f, p);
        for (int i = 0; i < 3; i++) {
            float x = size * (0.31f + i * 0.19f);
            float y = size * (0.31f + i * 0.19f);
            c.drawLine(x, size * 0.22f, x, size * 0.27f, p);
            c.drawLine(x, size * 0.73f, x, size * 0.78f, p);
            c.drawLine(size * 0.22f, y, size * 0.27f, y, p);
            c.drawLine(size * 0.73f, y, size * 0.78f, y, p);
        }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(alpha(accent, 0x2A));
        c.drawRoundRect(new RectF(chip.left + size * 0.045f, chip.top + size * 0.045f,
                chip.right - size * 0.045f, chip.bottom - size * 0.045f),
                size * 0.035f, size * 0.035f, fill);

        Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
        wave.setStyle(Paint.Style.STROKE);
        wave.setStrokeWidth(Math.max(1.5f, size * 0.042f));
        wave.setStrokeCap(Paint.Cap.ROUND);
        wave.setColor(accent);
        Path path = new Path();
        float shift = (float) Math.sin(phase * Math.PI * 2.0) * size * 0.035f;
        float amp = (float) Math.sin(phase * Math.PI * 4.0) * size * 0.035f;
        path.moveTo(size * 0.30f, size * 0.53f);
        path.lineTo(size * 0.38f, size * 0.53f + amp * 0.35f);
        path.lineTo(size * 0.43f, size * 0.40f + amp);
        path.lineTo(size * 0.50f, size * 0.64f + amp);
        path.lineTo(size * 0.57f, size * 0.47f + amp * 0.5f);
        path.lineTo(size * 0.62f, size * 0.53f - amp * 0.25f);
        path.lineTo(size * 0.71f, size * 0.53f + shift);
        c.drawPath(path, wave);

        Paint hot = new Paint(Paint.ANTI_ALIAS_FLAG);
        float pulse = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        hot.setColor(alpha(0xFFFFC857, Math.round(0x66 + 0x99 * pulse)));
        c.drawCircle(size * 0.69f, size * 0.36f,
                size * (0.026f + 0.012f * pulse), hot);
    }

    private static void drawTuner(Canvas c, int size, int accent, float phase) {
        Paint gauge = new Paint(Paint.ANTI_ALIAS_FLAG);
        gauge.setStyle(Paint.Style.STROKE);
        gauge.setStrokeCap(Paint.Cap.ROUND);
        gauge.setStrokeWidth(Math.max(1.8f, size * 0.062f));
        RectF arc = new RectF(size * 0.19f, size * 0.22f,
                size * 0.81f, size * 0.84f);
        gauge.setColor(alpha(accent, 0x44));
        c.drawArc(arc, 150, 240, false, gauge);
        gauge.setColor(accent);
        c.drawArc(arc, 150, 168, false, gauge);

        Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
        needle.setColor(lighten(accent, 0.32f));
        needle.setStrokeWidth(Math.max(1.4f, size * 0.042f));
        needle.setStrokeCap(Paint.Cap.ROUND);
        float cx = size * 0.5f;
        float cy = size * 0.55f;
        float sweep = (float) Math.sin(phase * Math.PI * 2.0) * 34f;
        double angle = Math.toRadians(150 + 168 + sweep);
        c.drawLine(cx, cy,
                cx + (float) Math.cos(angle) * size * 0.25f,
                cy + (float) Math.sin(angle) * size * 0.25f, needle);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(lighten(accent, 0.32f));
        c.drawCircle(cx, cy, size * 0.045f, dot);
    }

    private static void drawRgb(Canvas c, int size, int accent, float phase) {
        float cx = size * 0.5f;
        float cy = size * 0.48f;
        float radius = size * 0.29f;
        RectF ring = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, size * 0.075f));
        p.setStrokeCap(Paint.Cap.ROUND);
        int[] colors = {0xFFFF6B6B, 0xFFFFC857, 0xFF69D98B,
                0xFF7CF7C4, 0xFF8BE9FF, 0xFF6C8CFF, 0xFFD26BFF};
        float segment = 360f / colors.length;
        float offset = phase * 360f;
        for (int i = 0; i < colors.length; i++) {
            p.setColor(colors[i]);
            c.drawArc(ring, -90f + offset + i * segment, 48f, false, p);
        }
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setColor(0xFF101820);
        c.drawCircle(cx, cy, radius * 0.57f, hole);
        Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        knob.setStyle(Paint.Style.STROKE);
        knob.setStrokeWidth(Math.max(1.2f, size * 0.035f));
        knob.setColor(0xCCFFFFFF);
        c.drawCircle(cx, cy, radius * 0.55f, knob);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, size * 0.035f, dot);
        Paint stem = new Paint(Paint.ANTI_ALIAS_FLAG);
        stem.setStrokeWidth(Math.max(1.2f, size * 0.03f));
        stem.setColor(0xAAFFFFFF);
        c.drawLine(cx, cy + radius * 0.55f, cx, cy + radius * 0.85f, stem);
    }

    private static void drawFan(Canvas c, int size, int accent, float phase) {
        float cx = size * 0.48f;
        float cy = size * 0.48f;
        float radius = size * 0.28f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(accent);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, size * 0.025f));
        c.drawCircle(cx, cy, radius, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(blend(0xFF101820, accent, 0.18f));
        c.drawCircle(cx, cy, radius * 0.78f, p);
        p.setColor(lighten(accent, 0.56f));

        c.save();
        c.rotate(phase * 360f, cx, cy);
        for (int i = 0; i < 6; i++) {
            c.save();
            c.rotate(i * 60f + 14f, cx, cy);
            Path blade = new Path();
            blade.moveTo(cx + radius * 0.12f, cy);
            blade.cubicTo(cx + radius * 0.32f, cy - radius * 0.22f,
                    cx + radius * 0.72f, cy - radius * 0.18f,
                    cx + radius * 0.78f, cy);
            blade.cubicTo(cx + radius * 0.54f, cy + radius * 0.12f,
                    cx + radius * 0.24f, cy + radius * 0.13f,
                    cx + radius * 0.12f, cy);
            blade.close();
            c.drawPath(blade, p);
            c.restore();
        }
        c.restore();
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, size * 0.045f, p);

        Paint air = new Paint(Paint.ANTI_ALIAS_FLAG);
        air.setStyle(Paint.Style.STROKE);
        air.setStrokeWidth(Math.max(1f, size * 0.018f));
        air.setStrokeCap(Paint.Cap.ROUND);
        air.setColor(alpha(accent, 0xAA));
        for (int i = 0; i < 3; i++) {
            float y = size * (0.23f + i * 0.085f);
            c.drawLine(size * 0.78f, y, size * 0.92f, y - size * 0.05f, air);
        }
    }

    private static void drawTouch(Canvas c, int size, int accent, float phase) {
        Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
        screen.setStyle(Paint.Style.STROKE);
        screen.setStrokeWidth(Math.max(1.3f, size * 0.032f));
        screen.setColor(alpha(accent, 0xCC));
        c.drawRoundRect(new RectF(size * 0.14f, size * 0.22f,
                size * 0.86f, size * 0.72f), size * 0.07f, size * 0.07f, screen);

        float pulse = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        float grow = 1f + 0.08f * pulse;
        Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
        target.setStyle(Paint.Style.STROKE);
        target.setStrokeWidth(Math.max(1.2f, size * 0.028f));
        target.setColor(accent);
        c.drawCircle(size * 0.35f, size * 0.47f, size * 0.09f * grow, target);
        c.drawCircle(size * 0.63f, size * 0.53f, size * 0.10f * grow, target);
        target.setStyle(Paint.Style.FILL);
        target.setColor(alpha(accent, 0x33));
        c.drawCircle(size * 0.35f, size * 0.47f, size * 0.065f * grow, target);
        c.drawCircle(size * 0.63f, size * 0.53f, size * 0.075f * grow, target);

        Paint finger = new Paint(Paint.ANTI_ALIAS_FLAG);
        finger.setColor(0xFFFFFFFF);
        c.drawCircle(size * 0.63f, size * 0.53f, size * 0.032f, finger);
        finger.setColor(lighten(accent, 0.55f));
        c.drawCircle(size * 0.35f, size * 0.47f, size * 0.026f, finger);
        Paint apex = new Paint(Paint.ANTI_ALIAS_FLAG);
        apex.setColor(0xAAFFFFFF);
        c.drawRoundRect(new RectF(size * 0.43f, size * 0.79f,
                size * 0.57f, size * 0.81f), size * 0.01f, size * 0.01f, apex);
    }

    private static void drawFileManager(Canvas c, int size, int accent, float phase) {
        float s = size;
        // Folder back, with the same silhouette as the static File Manager tile.
        Path folder = new Path();
        folder.moveTo(s * 0.18f, s * 0.34f);
        folder.lineTo(s * 0.18f, s * 0.72f);
        folder.lineTo(s * 0.82f, s * 0.72f);
        folder.lineTo(s * 0.82f, s * 0.34f);
        folder.lineTo(s * 0.48f, s * 0.34f);
        folder.lineTo(s * 0.41f, s * 0.27f);
        folder.lineTo(s * 0.18f, s * 0.27f);
        folder.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(alpha(accent, 0x22));
        c.drawPath(folder, fill);

        float breath = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(alpha(accent, Math.round(0x14 + 0x28 * breath)));
        c.drawRoundRect(new RectF(s * 0.22f, s * 0.38f,
                s * 0.78f, s * 0.68f), s * 0.03f, s * 0.03f, glow);

        // A document rises out of the folder and settles back in.
        float lift = s * (0.018f + 0.075f
                * (float) ((Math.sin(phase * Math.PI * 2.0 - Math.PI / 2.0) + 1.0) * 0.5));
        RectF paper = new RectF(s * 0.29f, s * 0.32f - lift,
                s * 0.71f, s * 0.66f - lift);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x55000000);
        c.drawRoundRect(new RectF(paper.left + s * 0.012f, paper.top + s * 0.018f,
                paper.right + s * 0.012f, paper.bottom + s * 0.018f),
                s * 0.035f, s * 0.035f, shadow);
        Paint page = new Paint(Paint.ANTI_ALIAS_FLAG);
        page.setColor(0xFFEDF3F7);
        c.drawRoundRect(paper, s * 0.035f, s * 0.035f, page);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, s * 0.014f));
        edge.setColor(alpha(accent, 0x88));
        c.drawRoundRect(paper, s * 0.035f, s * 0.035f, edge);

        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(0xCC26343F);
        ink.setStrokeWidth(Math.max(1f, s * 0.024f));
        ink.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(paper.left + s * 0.055f, paper.top + s * 0.085f,
                paper.right - s * 0.055f, paper.top + s * 0.085f, ink);
        c.drawLine(paper.left + s * 0.055f, paper.top + s * 0.145f,
                paper.right - s * 0.115f, paper.top + s * 0.145f, ink);

        // The folder lip covers the bottom of the document.
        Path front = new Path();
        front.moveTo(s * 0.18f, s * 0.55f);
        front.lineTo(s * 0.82f, s * 0.55f);
        front.lineTo(s * 0.82f, s * 0.72f);
        front.lineTo(s * 0.18f, s * 0.72f);
        front.close();
        Paint frontFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        frontFill.setColor(alpha(accent, 0x33));
        c.drawPath(front, frontFill);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1.4f, s * 0.038f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(alpha(accent, 0xD0));
        c.drawPath(folder, line);
        c.drawLine(s * 0.22f, s * 0.55f, s * 0.78f, s * 0.55f,
                line);

        Paint rows = new Paint(Paint.ANTI_ALIAS_FLAG);
        rows.setColor(0xAAFFFFFF);
        rows.setStrokeWidth(Math.max(1f, s * 0.026f));
        rows.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(s * 0.31f, s * 0.625f, s * 0.31f + s * (0.20f + 0.08f * breath),
                s * 0.625f, rows);
        c.drawLine(s * 0.31f, s * 0.685f, s * 0.31f + s * (0.14f + 0.06f * breath),
                s * 0.685f, rows);
    }

    private static void drawRecorder(Canvas c, int size, int accent, float phase) {
        float s = size;
        Paint boardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardFill.setColor(alpha(accent, 0x22));
        Paint boardLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardLine.setStyle(Paint.Style.STROKE);
        boardLine.setStrokeWidth(Math.max(1.2f, s * 0.032f));
        boardLine.setColor(alpha(accent, 0xD0));
        RectF board = new RectF(s * 0.18f, s * 0.38f, s * 0.82f, s * 0.76f);
        c.drawRoundRect(board, s * 0.045f, s * 0.045f, boardFill);
        c.drawRoundRect(board, s * 0.045f, s * 0.045f, boardLine);

        float open = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0 - Math.PI / 2.0);
        float rightTop = s * (0.25f - 0.055f * open);
        Path clap = new Path();
        clap.moveTo(s * 0.18f, s * 0.34f);
        clap.lineTo(s * 0.84f, rightTop);
        clap.lineTo(s * 0.84f, rightTop + s * 0.09f);
        clap.lineTo(s * 0.18f, s * 0.43f);
        clap.close();
        Paint clapFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        clapFill.setColor(alpha(accent, 0x55));
        c.drawPath(clap, clapFill);

        Paint stripe = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripe.setStrokeWidth(Math.max(1.5f, s * 0.030f));
        stripe.setColor(0xFF101820);
        for (int i = 0; i < 3; i++) {
            float x = s * (0.32f + i * 0.18f);
            float y = s * 0.334f + (rightTop - s * 0.25f) * 0.8f;
            c.drawLine(x, y, x + s * 0.10f, y - s * 0.012f, stripe);
        }
        Paint clapLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        clapLine.setStyle(Paint.Style.STROKE);
        clapLine.setStrokeWidth(Math.max(1.2f, s * 0.032f));
        clapLine.setColor(alpha(accent, 0xD0));
        c.drawPath(clap, clapLine);
        Paint hinge = new Paint(Paint.ANTI_ALIAS_FLAG);
        hinge.setColor(alpha(accent, 0xD0));
        c.drawCircle(s * 0.18f, s * 0.38f, s * 0.035f, hinge);

        float pulse = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(alpha(0xFFFF3D3D, Math.round(0x18 + 0x30 * pulse)));
        c.drawCircle(s * 0.50f, s * 0.57f, s * (0.12f + 0.025f * pulse), glow);
        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        red.setColor(0xFFFF3D3D);
        c.drawCircle(s * 0.50f, s * 0.57f, s * (0.088f + 0.008f * pulse), red);
        Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glint.setColor(0x88FFFFFF);
        c.drawCircle(s * 0.47f, s * 0.54f, s * 0.027f, glint);
    }

    private static int alpha(int colour, int value) {
        return (Math.max(0, Math.min(255, value)) << 24) | (colour & 0x00FFFFFF);
    }

    private static int lighten(int colour, float amount) {
        return blend(colour, 0xFFFFFFFF, amount);
    }

    private static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int r = Math.round(Color.red(from) * (1f - f) + Color.red(to) * f);
        int g = Math.round(Color.green(from) * (1f - f) + Color.green(to) * f);
        int b = Math.round(Color.blue(from) * (1f - f) + Color.blue(to) * f);
        return Color.rgb(r, g, b);
    }
}
