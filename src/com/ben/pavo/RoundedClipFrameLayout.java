package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Frame layout that clips all drawer children to the rounded card outline. */
final class RoundedClipFrameLayout extends android.widget.FrameLayout {
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private float radiusPx;

    RoundedClipFrameLayout(Context context) {
        super(context);
        setClipChildren(true);
    }

    void setRadiusDp(float radiusDp) {
        radiusPx = Ui.dpf(getContext(), radiusDp);
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        rect.set(0, 0, getWidth(), getHeight());
        clip.reset();
        clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);
        canvas.clipPath(clip);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }
}
