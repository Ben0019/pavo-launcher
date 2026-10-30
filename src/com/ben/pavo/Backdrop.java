package com.ben.pavo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/**
 * A blurred snapshot of what is currently on screen, plus the size of the screen it was
 * captured from. Glass surfaces sample it at their own position so the refraction and
 * tint follow the actual content behind them, like the app drawer.
 */
final class Backdrop {
    final Bitmap bitmap;
    final int screenWidth;
    final int screenHeight;

    Backdrop(Bitmap bitmap, int screenWidth, int screenHeight) {
        this.bitmap = bitmap;
        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
    }

    /**
     * Render a view hierarchy at quarter resolution and blur it. Callers that need to
     * exclude a child (the dock, for example) hide it around this call.
     */
    static Backdrop capture(View view, int blurRadius) {
        int w = Math.max(1, view.getWidth());
        int h = Math.max(1, view.getHeight());
        int sw = Math.max(1, w / 4);
        int sh = Math.max(1, h / 4);

        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(small);
        canvas.scale(sw / (float) w, sh / (float) h);
        view.draw(canvas);
        // A TextureView (the video wallpaper) has no software drawing: its frame
        // only exists in the GPU texture, so view.draw() leaves that area black
        // and every glass card lost its material. Paste the current frame in.
        drawTextures(view, canvas, view);

        if (blurRadius <= 0) {
            return new Backdrop(small, w, h);
        }
        Bitmap blurred = Blur.blur(small, blurRadius);
        small.recycle();
        return new Backdrop(blurred, w, h);
    }

    private static final Paint TEXTURE_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Composite any TextureView content into a software capture of the hierarchy. */
    private static void drawTextures(View view, Canvas canvas, View root) {
        if (view instanceof TextureView) {
            TextureView texture = (TextureView) view;
            if (!texture.isShown() || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
            Bitmap frame = null;
            try {
                frame = texture.getBitmap(Math.max(1, texture.getWidth()),
                        Math.max(1, texture.getHeight()));
            } catch (Throwable ignored) {
            }
            if (frame == null) return;
            int[] rloc = new int[2];
            int[] tloc = new int[2];
            root.getLocationInWindow(rloc);
            texture.getLocationInWindow(tloc);
            float left = tloc[0] - rloc[0];
            float top = tloc[1] - rloc[1];
            canvas.drawBitmap(frame, null,
                    new RectF(left, top, left + texture.getWidth(), top + texture.getHeight()),
                    TEXTURE_PAINT);
            frame.recycle();
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                drawTextures(group.getChildAt(i), canvas, root);
            }
        }
    }
}
