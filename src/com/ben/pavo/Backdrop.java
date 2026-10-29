package com.ben.pavo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

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

        if (blurRadius <= 0) {
            return new Backdrop(small, w, h);
        }
        Bitmap blurred = Blur.blur(small, blurRadius);
        small.recycle();
        return new Backdrop(blurred, w, h);
    }
}
