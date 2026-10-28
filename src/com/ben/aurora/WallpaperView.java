package com.ben.aurora;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.view.View;

import java.io.File;
import java.util.Random;

final class WallpaperView extends View {

    interface Listener {
        void onWallpaperChanged();
    }

    private static Bitmap grainTile;

    private Bitmap sourceCache;
    private Bitmap screen;
    private Bitmap blurred;
    private int blurRadius = 12;
    private int mode = Prefs.MODE_DEVICE;
    private Listener listener;
    private boolean wallpaperReceiverRegistered;

    private final BroadcastReceiver wallpaperReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                refreshWallpaper();
            }
        }
    };

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint scrimPaint = new Paint(Paint.DITHER_FLAG);

    WallpaperView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setBlurRadius(int radius) {
        radius = Math.max(0, Math.min(30, radius));
        if (radius == blurRadius) return;
        blurRadius = radius;
        rebuildBlur();
        invalidate();
    }

    void setMode(int m) {
        if (mode == m) {
            reload();
            return;
        }
        mode = m;
        clearSourceCache();
        reload();
    }

    int getMode() {
        return mode;
    }

    Bitmap getBlurred() {
        return blurred;
    }


    /** Reload after the system wallpaper changes. */
    void refreshWallpaper() {
        clearSourceCache();
        reload();
    }

    private int accent = 0xFFA9BEFF;
    private boolean trackAccent = true;
    private float burnInDx;
    private float burnInDy;

    /** A usable highlight colour derived from the current wallpaper. */
    int accent() {
        return ColorTuning.colour(accent);
    }

    /**
     * When a video or system live wallpaper owns the background, that view is
     * the source of truth for the accent. While tracking is off, reloading this
     * (hidden) bitmap must not overwrite the accent with a stale image.
     */
    void setAccentTracking(boolean enabled) {
        if (trackAccent == enabled) return;
        trackAccent = enabled;
        // Coming back to a bitmap wallpaper: re-derive the accent immediately
        // so the icons follow the wallpaper that is actually on screen.
        if (enabled && getWidth() > 0 && getHeight() > 0) reload();
    }

    /** Shift the drawn wallpaper with the launcher's burn-in pixel pattern. */
    void setBurnInOffset(float dx, float dy) {
        if (burnInDx == dx && burnInDy == dy) return;
        burnInDx = dx;
        burnInDy = dy;
        invalidate();
    }

    /** Use a colour sampled from a live wallpaper instead of this bitmap. */
    void setAccent(int colour) {
        accent = (colour & 0x00FFFFFF) | 0xFF000000;
        ThemeColors.set(accent);
        if (listener != null) listener.onWallpaperChanged();
        invalidate();
    }

    private static int extractAccent(Bitmap b) {
        return AccentColor.fromBitmap(b);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!wallpaperReceiverRegistered) {
            try {
                getContext().registerReceiver(wallpaperReceiver,
                        new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
                wallpaperReceiverRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (wallpaperReceiverRegistered) {
            try {
                getContext().unregisterReceiver(wallpaperReceiver);
            } catch (Throwable ignored) {
            }
            wallpaperReceiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (w != oldw || h != oldh || screen == null)) reload();
    }

    private void reload() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Bitmap source = obtainSource();
        Bitmap next = centerCrop(source, w, h);

        applyGrain(next);
        if (screen != null && screen != next) screen.recycle();
        screen = next;
        accent = extractAccent(next);
        if (trackAccent) {
            ThemeColors.set(accent);
            Prefs themePrefs = new Prefs(getContext());
            if (themePrefs.themeAccent() != accent) themePrefs.setThemeAccent(accent);
        }
        rebuildBlur();
        if (listener != null) listener.onWallpaperChanged();
        invalidate();
    }

    private Bitmap obtainSource() {
        if (sourceCache != null) return sourceCache;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;

        Bitmap source = null;
        if (mode == Prefs.MODE_AURORA) {
            source = aurora(w, h);
        } else if (mode == Prefs.MODE_CUSTOM) {
            source = loadCustomWallpaper();
        } else if (mode == Prefs.MODE_VIDEO) {
            source = loadVideoFrame(new Prefs(getContext()).videoWallpaper());
        }
        // MODE_DEVICE follows the device's actual wallpaper instead of only the
        // bundled Odin artwork.
        if (source == null) source = loadSystemWallpaper();
        if (source == null) source = fallback(w, h);
        sourceCache = source;
        return source;
    }

    private void clearSourceCache() {
        if (sourceCache != null) {
            sourceCache.recycle();
            sourceCache = null;
        }
    }

    private void applyGrain(Bitmap bitmap) {
        if (bitmap == null) return;
        if (grainTile == null) grainTile = makeGrainTile();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        Matrix matrix = new Matrix();
        matrix.setScale(2f, 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        paint.setAlpha(110);
        new Canvas(bitmap).drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), paint);
    }

    private static Bitmap makeGrainTile() {
        int size = 96;
        int[] pixels = new int[size * size];
        Random random = new Random(11);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = random.nextInt(22);
            boolean white = random.nextBoolean();
            pixels[i] = (alpha << 24) | (white ? 0x00FFFFFF : 0x00000000);
        }
        Bitmap tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        tile.setPixels(pixels, 0, size, 0, 0, size, size);
        return tile;
    }

    private void rebuildBlur() {
        if (screen == null) return;
        int sw = Math.max(1, screen.getWidth() / 4);
        int sh = Math.max(1, screen.getHeight() / 4);
        Bitmap small = Bitmap.createScaledBitmap(screen, sw, sh, true);
        Bitmap next = Blur.blur(small, blurRadius);
        small.recycle();
        if (blurred != null && blurred != next) blurred.recycle();
        blurred = next;
        if (listener != null) listener.onWallpaperChanged();
    }

    private Bitmap loadSystemWallpaper() {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(getContext());
            Drawable d = manager == null ? null : manager.getDrawable();
            if (d == null) return null;
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) {
                iw = Math.max(1, getResources().getDisplayMetrics().widthPixels);
                ih = Math.max(1, getResources().getDisplayMetrics().heightPixels);
            }
            float scale = Math.min(1f, 2560f / Math.max(iw, ih));
            int bw = Math.max(1, Math.round(iw * scale));
            int bh = Math.max(1, Math.round(ih * scale));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, bw, bh);
            d.draw(c);
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Still background for the built-in video wallpaper. Some builds do not
     * expose the system wallpaper through WallpaperManager, which left every
     * secondary screen on the bundled gradient instead of the wallpaper.
     */
    private Bitmap loadVideoFrame(String path) {
        if (path == null || path.isEmpty()) return null;
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(path);
            int w = Math.max(1, getResources().getDisplayMetrics().widthPixels / 2);
            int h = Math.max(1, getResources().getDisplayMetrics().heightPixels / 2);
            return retriever.getScaledFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
        } catch (Throwable t) {
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private Bitmap loadCustomWallpaper() {
        try {
            File f = new File(getContext().getFilesDir(), "wallpaper.png");
            if (!f.exists() || f.length() == 0) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int max = 2560;
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outHeight > 0
                    && (bounds.outWidth / (sample * 2) >= max
                    || bounds.outHeight / (sample * 2) >= max)) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap centerCrop(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * scale;
        float dh = src.getHeight() * scale;
        float left = (w - dw) / 2f;
        float top = (h - dh) / 2f;
        c.drawBitmap(src, null, new RectF(left, top, left + dw, top + dh),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static Bitmap fallback(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF05070D);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow(c, p, w * 0.18f, h * 0.18f, w * 0.8f, 0x9922D3EE);
        glow(c, p, w * 0.85f, h * 0.05f, w * 0.7f, 0x994F46E5);
        glow(c, p, w * 0.6f, h * 1.05f, w * 0.8f, 0x99DB2777);
        return b;
    }

    private static Bitmap aurora(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF04060B);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow(c, p, w * 0.08f, h * 0.16f, w * 0.75f, 0xCC0E7490);
        glow(c, p, w * 0.86f, h * 0.04f, w * 0.65f, 0xCC4338CA);
        glow(c, p, w * 0.74f, h * 0.92f, w * 0.7f, 0xB3BE185D);
        glow(c, p, w * 0.42f, h * 0.62f, w * 0.5f, 0x803B82F6);
        glow(c, p, w * 0.18f, h * 0.86f, w * 0.45f, 0x6622D3EE);

        Paint scrim = new Paint(Paint.DITHER_FLAG);
        scrim.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x88000000, 0x11000000, 0x00000000, 0x66000000},
                new float[]{0f, 0.28f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, scrim);
        return b;
    }

    private static void glow(Canvas c, Paint p, float cx, float cy, float radius, int color) {
        int transparent = color & 0x00FFFFFF;
        p.setShader(new RadialGradient(cx, cy, Math.max(1, radius),
                new int[]{color, transparent}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, radius, p);
        p.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (screen != null) {
            float pad = Math.max(Math.abs(burnInDx), Math.abs(burnInDy));
            if (pad > 0.01f) {
                // Overscan so the shifted wallpaper still fills the panel.
                float over = pad + 1f;
                canvas.save();
                canvas.translate(burnInDx, burnInDy);
                canvas.drawBitmap(screen, null,
                        new RectF(-over, -over, w + over, h + over), paint);
                canvas.restore();
            } else {
                canvas.drawBitmap(screen, 0, 0, paint);
            }
        } else {
            canvas.drawColor(Color.BLACK);
        }

        scrimPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x73000000, 0x1A000000, 0x00000000, 0x33000000, 0x99000000},
                new float[]{0f, 0.22f, 0.55f, 0.76f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, scrimPaint);
    }
}
