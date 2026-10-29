package com.ben.pavo;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

/**
 * TextView whose marquee stays active even when a ListView clears child selection.
 * Long app names scroll in the home, dock, full drawer and popup drawer.
 */
final class MarqueeTextView extends TextView {
    private boolean marqueeActive;

    MarqueeTextView(Context c) {
        super(c);
        setSingleLine(true);
        setMaxLines(1);
        setHorizontallyScrolling(true);
        setEllipsize(TextUtils.TruncateAt.END);
    }

    void setMarqueeEnabled(boolean enabled) {
        marqueeActive = enabled;
        if (enabled) {
            setSingleLine(true);
            setMaxLines(1);
            setHorizontallyScrolling(true);
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
            setMarqueeRepeatLimit(-1);
            super.setSelected(true);
        } else {
            setEllipsize(TextUtils.TruncateAt.END);
            super.setSelected(false);
        }
    }

    void setMarqueePaused(boolean paused) {
        marqueeActive = !paused;
        if (paused) {
            super.setSelected(false);
        } else {
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
            setMarqueeRepeatLimit(-1);
            super.setSelected(true);
        }
    }

    @Override
    public void setSelected(boolean selected) {
        if (marqueeActive) {
            super.setSelected(true);
        } else {
            super.setSelected(selected);
        }
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        int save = canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (marqueeActive) post(new Runnable() {
            @Override
            public void run() {
                if (marqueeActive) MarqueeTextView.super.setSelected(true);
            }
        });
    }
}
