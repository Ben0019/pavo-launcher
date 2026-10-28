package com.ben.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Dims everything except the drawer card. Keeping the scrim out of the card is
 * important for clear glass: the card samples the live blurred backdrop, and a
 * dark scrim underneath would tint it a second time.
 */
final class DrawerScrimView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private View card;
    private float radiusPx;

    DrawerScrimView(Context c, int color, float radiusPx) {
        super(c);
        paint.setColor(color);
        this.radiusPx = radiusPx;
        setWillNotDraw(false);
    }

    void setCard(View card) {
        this.card = card;
        if (card != null) {
            card.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    invalidate();
                }
            });
        }
        invalidate();
    }

    void setCutoutRadius(float radiusPx) {
        this.radiusPx = radiusPx;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, w, h, Path.Direction.CW);
        if (card != null) {
            float sx = Math.max(0.01f, card.getScaleX());
            float sy = Math.max(0.01f, card.getScaleY());
            float cx = card.getX() + card.getWidth() / 2f;
            float cy = card.getY() + card.getHeight() / 2f;
            float hw = card.getWidth() * sx / 2f;
            float hh = card.getHeight() * sy / 2f;
            float l = cx - hw;
            float t = cy - hh;
            float r = cx + hw;
            float b = cy + hh;
            if (r > l && b > t) {
                float rp = radiusPx * Math.min(sx, sy);
                Path hole = new Path();
                hole.addRoundRect(l, t, r, b, rp, rp, Path.Direction.CW);
                if (card.getRotation() != 0f) {
                    Matrix m = new Matrix();
                    m.setRotate(card.getRotation(), cx, cy);
                    hole.transform(m);
                }
                path.addPath(hole);
            }
        }
        canvas.drawPath(path, paint);
    }
}
