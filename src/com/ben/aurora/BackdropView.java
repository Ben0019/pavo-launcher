package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;

/**
 * Draws a blurred screen snapshot at its real screen position. A plain ImageView would
 * stretch the snapshot to the view bounds, which makes the wallpaper, icons and widgets
 * appear at the wrong scale inside a drawer or card. This view keeps the sample anchored
 * to the screen so the glass always matches what is physically behind it.
 */
final class BackdropView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final int[] location = new int[2];
    private Backdrop backdrop;

    BackdropView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    void setBackdrop(Backdrop backdrop) {
        this.backdrop = backdrop;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Backdrop b = backdrop;
        if (b == null || b.bitmap == null
                || b.bitmap.getWidth() <= 0 || b.bitmap.getHeight() <= 0) {
            return;
        }
        Bitmap bitmap = b.bitmap;
        getLocationOnScreen(location);
        float sx = bitmap.getWidth() / (float) b.screenWidth;
        float sy = bitmap.getHeight() / (float) b.screenHeight;
        // drawBitmap transforms the bitmap geometry, so scale the quarter-res
        // sample back up to screen size and shift it by the view's screen origin.
        matrix.setScale(1f / sx, 1f / sy);
        matrix.postTranslate(-location[0], -location[1]);
        canvas.drawBitmap(bitmap, matrix, paint);
    }
}
