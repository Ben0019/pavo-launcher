package com.ben.aurora;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.BitmapShader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Apps {
    private Apps() {}

    static final class Entry {
        final String label;
        final String pkg;
        final String activity;
        Bitmap icon;

        Entry(String label, String pkg, String activity) {
            this.label = label;
            this.pkg = pkg;
            this.activity = activity;
        }

        ComponentName component() {
            return new ComponentName(pkg, activity);
        }
    }

    static List<Entry> query(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);

        Map<String, Entry> byPackage = new HashMap<>();

        for (ResolveInfo ri : infos) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;

            String pkg = ai.packageName;
            if (pkg.equals(context.getPackageName())) continue;
            if (pkg.equals("com.android.launcher3")) continue;
            if (pkg.equals("com.odin.odinlauncher")) continue;

            Intent launch = pm.getLaunchIntentForPackage(pkg);
            String canonical = launch != null && launch.getComponent() != null
                    ? launch.getComponent().getClassName()
                    : ai.name;

            // Usually getLaunchIntentForPackage picks the primary activity. If we
            // encounter a secondary activity for an already-known package, keep the
            // canonical one.
            Entry existing = byPackage.get(pkg);
            if (existing != null) {
                if (!existing.activity.equals(canonical) && ai.name.equals(canonical)) {
                    byPackage.put(pkg, new Entry(labelOf(pm, ri, pkg), pkg, canonical));
                }
                continue;
            }

            byPackage.put(pkg, new Entry(labelOf(pm, ri, pkg), pkg, canonical));
        }

        ArrayList<Entry> list = new ArrayList<>(byPackage.values());
        final Collator collator = Collator.getInstance(Locale.getDefault());
        list.sort((a, b) -> collator.compare(a.label, b.label));
        return list;
    }

    private static String labelOf(PackageManager pm, ResolveInfo ri, String fallback) {
        CharSequence label = ri.loadLabel(pm);
        String s = label == null ? fallback : label.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    static void loadIcons(Context context, List<Entry> entries, int px) {
        PackageManager pm = context.getPackageManager();
        for (Entry e : entries) {
            if (e.icon == null) e.icon = iconOf(pm, e, px);
        }
    }

    private static Bitmap iconOf(PackageManager pm, Entry e, int px) {
        Drawable d = null;
        try {
            d = pm.getActivityIcon(e.component());
        } catch (Throwable ignored) {
        }
        if (d == null) return letterTile(e.label, px);

        Bitmap raw = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(raw);
        d.setBounds(0, 0, px, px);
        d.draw(rc);

        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = px * 0.225f;
        c.drawRoundRect(new RectF(0, 0, px, px), r, r, p);
        raw.recycle();
        return out;
    }

    /** Resolve a single launchable entry from a saved component (may be uninstalled). */
    static Entry entryFor(Context context, String pkg, String cls) {
        PackageManager pm = context.getPackageManager();
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            String label = ai.loadLabel(pm).toString().trim();
            if (label.isEmpty()) label = pkg;
            return new Entry(label, pkg, cls);
        } catch (Throwable t) {
            return new Entry(pkg, pkg, cls);
        }
    }

    static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Bitmap drawableToBitmap(Drawable d, int px) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // ImageView keeps FIT_CENTER drawables at their intrinsic bounds and applies
        // a draw matrix. Mutating those bounds here made the launched dock icon
        // render at a second, tiny scale after returning home. Preserve the
        // drawable's own bounds for the view that owns it.
        Rect oldBounds = new Rect(d.getBounds());
        d.setBounds(0, 0, px, px);
        d.draw(c);
        d.setBounds(oldBounds);
        return b;
    }

    /** A 2x2 preview tile for a home folder. */
    static Bitmap folderIcon(java.util.List<Entry> entries, int px, int accent,
                             float tileAlpha) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int light = ((Color.red(accent) + 255) / 2) << 16
                | (((Color.green(accent) + 255) / 2) << 8)
                | ((Color.blue(accent) + 255) / 2);
        int baseR = (Color.red(accent) + Color.red(0x1A2130) * 5) / 6;
        int baseG = (Color.green(accent) + Color.green(0x1A2130) * 5) / 6;
        int baseB = (Color.blue(accent) + Color.blue(0x1A2130) * 5) / 6;
        // Mostly transparent so the live wallpaper / background shows through the folder.
        float ta = Math.max(0.2f, Math.min(1f, tileAlpha));
        int base = (((int) (0x4D * ta)) << 24) | (baseR << 16) | (baseG << 8) | baseB;

        float r = px * 0.22f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(base);
        RectF tile = new RectF(0, 0, px, px);
        c.drawRoundRect(tile, r, r, p);

        // glass highlight in the top-left corner
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
        hp.setShader(new RadialGradient(px * 0.26f, px * 0.18f, px * 0.72f,
                ((int) (0x3D * ta) << 24) | light, (0x00 << 24) | light,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(tile, r, r, hp);

        // top sheen
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setShader(new LinearGradient(0, 0, 0, px * 0.55f,
                ((int) (0x33 * ta) << 24) | light, 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(tile, r, r, sp);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, px * 0.014f));
        p.setColor(((int) (0x8C * ta) << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, px - 1, px - 1), r, r, p);
        p.setStyle(Paint.Style.FILL);

        // Dedicated paint for the preview icons: no tint/alpha, so they keep their
        // original colours (the previous shared paint leaked the border's alpha).
        Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        iconPaint.setAlpha(255);

        int pad = Math.round(px * 0.13f);
        int gap = Math.round(px * 0.07f);
        int cell = (px - 2 * pad - gap) / 2;
        int[] xs = {pad, pad + cell + gap};
        int[] ys = {pad, pad + cell + gap};
        int n = Math.min(4, entries.size());
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            if (e == null || e.icon == null) continue;
            int x = xs[i % 2];
            int y = ys[i / 2];
            c.drawBitmap(e.icon, null, new RectF(x, y, x + cell, y + cell), iconPaint);
        }
        return b;
    }

    /** A small chart glyph used for Aurora's built-in performance-log drawer app. */
    private static int alpha(int colour, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (colour & 0x00FFFFFF);
    }

    private static int blend(int from, int to, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int r = Math.round(Color.red(from) * (1f - f) + Color.red(to) * f);
        int g = Math.round(Color.green(from) * (1f - f) + Color.green(to) * f);
        int b = Math.round(Color.blue(from) * (1f - f) + Color.blue(to) * f);
        return Color.rgb(r, g, b);
    }

    static Bitmap performanceLogsIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        int pad = Math.round(size * 0.22f);
        int bottom = size - pad;
        int width = Math.round(size * 0.09f);
        int gap = Math.round(size * 0.045f);
        int[] heights = {0x2E, 0x54, 0x3E};
        int[] colors = {0xFF69D98B, 0xFFFFC857, 0xFF8BE9FF};
        for (int i = 0; i < heights.length; i++) {
            bar.setColor(colors[i]);
            int h = Math.round((bottom - pad) * heights[i] / 0xFF);
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
        return b;
    }

    static Bitmap performanceTunerIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

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
        needle.setColor(blend(accent, 0xFFFFFFFF, 0.32f));
        needle.setStrokeWidth(Math.max(1.4f, size * 0.042f));
        needle.setStrokeCap(Paint.Cap.ROUND);
        float cx = size * 0.5f;
        float cy = size * 0.55f;
        double angle = Math.toRadians(150 + 168);
        c.drawLine(cx, cy,
                cx + (float) Math.cos(angle) * size * 0.25f,
                cy + (float) Math.sin(angle) * size * 0.25f, needle);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(blend(accent, 0xFFFFFFFF, 0.32f));
        c.drawCircle(cx, cy, size * 0.045f, dot);
        return b;
    }

    static Bitmap benchmarkIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor(alpha(accent, 0x66));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

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
        path.moveTo(size * 0.30f, size * 0.53f);
        path.lineTo(size * 0.38f, size * 0.53f);
        path.lineTo(size * 0.43f, size * 0.40f);
        path.lineTo(size * 0.50f, size * 0.64f);
        path.lineTo(size * 0.57f, size * 0.47f);
        path.lineTo(size * 0.62f, size * 0.53f);
        path.lineTo(size * 0.71f, size * 0.53f);
        c.drawPath(path, wave);

        Paint hot = new Paint(Paint.ANTI_ALIAS_FLAG);
        hot.setColor(alpha(0xFFFFC857, 0xCC));
        c.drawCircle(size * 0.69f, size * 0.36f, size * 0.032f, hot);
        return b;
    }

    static Bitmap fileManagerIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor(alpha(accent, 0x66));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(Math.max(1.4f, size * 0.038f));
        p.setStrokeCap(Paint.Cap.ROUND);
        Path folder = new Path();
        folder.moveTo(size * 0.18f, size * 0.34f);
        folder.lineTo(size * 0.18f, size * 0.72f);
        folder.lineTo(size * 0.82f, size * 0.72f);
        folder.lineTo(size * 0.82f, size * 0.34f);
        folder.lineTo(size * 0.48f, size * 0.34f);
        folder.lineTo(size * 0.41f, size * 0.27f);
        folder.lineTo(size * 0.18f, size * 0.27f);
        folder.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(alpha(accent, 0x22));
        c.drawPath(folder, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(alpha(accent, 0xD0));
        c.drawPath(folder, p);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(0xAAFFFFFF);
        line.setStrokeWidth(Math.max(1f, size * 0.026f));
        line.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(size * 0.31f, size * 0.50f, size * 0.69f, size * 0.50f, line);
        c.drawLine(size * 0.31f, size * 0.60f, size * 0.60f, size * 0.60f, line);
        return b;
    }

    static Bitmap screenRecorderIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor(alpha(accent, 0x66));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

        // Film slate: a clapperboard makes this distinct from the Display app.
        Paint boardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardFill.setColor(alpha(accent, 0x22));
        Paint boardLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardLine.setStyle(Paint.Style.STROKE);
        boardLine.setStrokeWidth(Math.max(1.2f, size * 0.032f));
        boardLine.setColor(alpha(accent, 0xD0));
        RectF board = new RectF(size * 0.18f, size * 0.38f,
                size * 0.82f, size * 0.76f);
        c.drawRoundRect(board, size * 0.045f, size * 0.045f, boardFill);
        c.drawRoundRect(board, size * 0.045f, size * 0.045f, boardLine);

        Path clap = new Path();
        clap.moveTo(size * 0.18f, size * 0.34f);
        clap.lineTo(size * 0.84f, size * 0.25f);
        clap.lineTo(size * 0.84f, size * 0.34f);
        clap.lineTo(size * 0.18f, size * 0.43f);
        clap.close();
        Paint clapFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        clapFill.setColor(alpha(accent, 0x55));
        c.drawPath(clap, clapFill);
        Paint stripe = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripe.setStrokeWidth(Math.max(1.5f, size * 0.030f));
        stripe.setColor(0xFF101820);
        for (int i = 0; i < 3; i++) {
            float x = size * (0.32f + i * 0.18f);
            c.drawLine(x, size * 0.334f, x + size * 0.10f, size * 0.322f, stripe);
        }
        Paint clapLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        clapLine.setStyle(Paint.Style.STROKE);
        clapLine.setStrokeWidth(Math.max(1.2f, size * 0.032f));
        clapLine.setColor(alpha(accent, 0xD0));
        c.drawPath(clap, clapLine);
        Paint hinge = new Paint(Paint.ANTI_ALIAS_FLAG);
        hinge.setColor(alpha(accent, 0xD0));
        c.drawCircle(size * 0.18f, size * 0.38f, size * 0.035f, hinge);

        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        red.setColor(0xFFFF3D3D);
        c.drawCircle(size * 0.50f, size * 0.57f, size * 0.095f, red);
        Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glint.setColor(0x66FFFFFF);
        c.drawCircle(size * 0.47f, size * 0.54f, size * 0.027f, glint);
        return b;
    }

    static Bitmap controllerMapIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

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
        for (int i = 0; i < colors.length; i++) {
            dot.setColor(colors[i]);
            c.drawCircle(size * xs[i], size * ys[i], size * 0.035f, dot);
        }
        return b;
    }


    /** A small monitor glyph for the built-in Display app. */
    static Bitmap displayIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

        Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
        screen.setStyle(Paint.Style.STROKE);
        screen.setStrokeWidth(Math.max(1.6f, size * 0.055f));
        screen.setStrokeJoin(Paint.Join.ROUND);
        screen.setColor(accent);
        c.drawRoundRect(new RectF(size * 0.16f, size * 0.22f,
                size * 0.84f, size * 0.67f), size * 0.08f, size * 0.08f, screen);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setStyle(Paint.Style.STROKE);
        body.setStrokeWidth(Math.max(1.4f, size * 0.045f));
        body.setStrokeCap(Paint.Cap.ROUND);
        body.setColor(accent);
        c.drawLine(size * 0.38f, size * 0.77f, size * 0.62f, size * 0.77f, body);
        c.drawLine(size * 0.50f, size * 0.67f, size * 0.50f, size * 0.77f, body);
        Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
        sun.setColor(0xFFFFC857);
        c.drawCircle(size * 0.50f, size * 0.44f, size * 0.075f, sun);
        return b;
    }

    /** RGB wheel / joystick ring glyph for the built-in RGB Lighting app. */
    static Bitmap rgbIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

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
        for (int i = 0; i < colors.length; i++) {
            p.setColor(colors[i]);
            c.drawArc(ring, -90f + i * (360f / colors.length), 48f, false, p);
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
        return b;
    }

    /** Fan / airflow glyph for the built-in Fan Control app. */
    static Bitmap fanIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

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
        p.setColor(blend(accent, 0xFFFFFFFF, 0.56f));
        for (int i = 0; i < 6; i++) {
            float a = (float) Math.toRadians(i * 60f + 14f);
            float bx = cx + (float) Math.cos(a) * radius * 0.45f;
            float by = cy + (float) Math.sin(a) * radius * 0.45f;
            c.save();
            c.rotate(i * 60f + 14f, cx, cy);
            android.graphics.Path blade = new android.graphics.Path();
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
        return b;
    }

    /** Touch target over a game screen for the built-in Touch Mapper. */
    static Bitmap touchMapperIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

        Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
        screen.setStyle(Paint.Style.STROKE);
        screen.setStrokeWidth(Math.max(1.3f, size * 0.032f));
        screen.setColor(alpha(accent, 0xCC));
        c.drawRoundRect(new RectF(size * 0.14f, size * 0.22f,
                size * 0.86f, size * 0.72f), size * 0.07f, size * 0.07f, screen);

        Paint target = new Paint(Paint.ANTI_ALIAS_FLAG);
        target.setStyle(Paint.Style.STROKE);
        target.setStrokeWidth(Math.max(1.2f, size * 0.028f));
        target.setColor(accent);
        c.drawCircle(size * 0.35f, size * 0.47f, size * 0.09f, target);
        c.drawCircle(size * 0.63f, size * 0.53f, size * 0.10f, target);
        target.setStyle(Paint.Style.FILL);
        target.setColor(alpha(accent, 0x33));
        c.drawCircle(size * 0.35f, size * 0.47f, size * 0.065f, target);
        c.drawCircle(size * 0.63f, size * 0.53f, size * 0.075f, target);

        Paint finger = new Paint(Paint.ANTI_ALIAS_FLAG);
        finger.setColor(0xFFFFFFFF);
        c.drawCircle(size * 0.63f, size * 0.53f, size * 0.032f, finger);
        finger.setColor(blend(accent, 0xFFFFFFFF, 0.55f));
        c.drawCircle(size * 0.35f, size * 0.47f, size * 0.026f, finger);
        Paint apex = new Paint(Paint.ANTI_ALIAS_FLAG);
        apex.setColor(0xAAFFFFFF);
        c.drawRoundRect(new RectF(size * 0.43f, size * 0.79f,
                size * 0.57f, size * 0.81f), size * 0.01f, size * 0.01f, apex);
        return b;
    }

    static Bitmap letterTile(String label, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int hash = label == null ? 0 : label.hashCode();
        int hue = Math.abs(hash) % 360;
        int color = Color.HSVToColor(new float[]{hue, 0.45f, 0.55f});
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(color);
        float r = px * 0.225f;
        c.drawRoundRect(new RectF(0, 0, px, px), r, r, bg);

        Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        fg.setColor(Color.WHITE);
        fg.setTextSize(px * 0.42f);
        fg.setTextAlign(Paint.Align.CENTER);
        fg.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        String s = label == null || label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(Locale.getDefault());
        Paint.FontMetrics fm = fg.getFontMetrics();
        c.drawText(s, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, fg);
        return b;
    }


    /** Battery pack with a health pulse for the built-in Device Health app. */
    static Bitmap deviceHealthIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

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
        p.setColor(accent);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(size * 0.77f, size * 0.56f, size * 0.028f, p);
        return b;
    }

    /** Clock face used by the Aurora Widgets page. */
    static Bitmap clockIcon(int px, int accent) {
        int size = Math.max(24, px);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size * 0.225f;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF101820);
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, bg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.018f));
        border.setColor((0x66 << 24) | (accent & 0x00FFFFFF));
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), r, r, border);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1.4f, size * 0.035f));
        ring.setColor(accent);
        c.drawCircle(size * 0.5f, size * 0.5f, size * 0.28f, ring);
        Paint hands = new Paint(Paint.ANTI_ALIAS_FLAG);
        hands.setStyle(Paint.Style.STROKE);
        hands.setStrokeWidth(Math.max(1.6f, size * 0.043f));
        hands.setStrokeCap(Paint.Cap.ROUND);
        hands.setColor(0xFFF2F4F8);
        c.drawLine(size * 0.5f, size * 0.5f, size * 0.5f, size * 0.32f, hands);
        c.drawLine(size * 0.5f, size * 0.5f, size * 0.64f, size * 0.58f, hands);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(accent);
        c.drawCircle(size * 0.5f, size * 0.5f, size * 0.035f, dot);
        return b;
    }

    static void launch(Context context, Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(e.component());
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(context, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    static void appInfo(Context context, Entry e) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.pkg, null));
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }
}
