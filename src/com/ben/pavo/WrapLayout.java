package com.ben.pavo;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Minimal flow layout: children wrap onto new lines when the width runs out. */
final class WrapLayout extends ViewGroup {

    private final int hGap;
    private final int vGap;

    WrapLayout(Context c, int hGapPx, int vGapPx) {
        super(c);
        hGap = hGapPx;
        vGap = vGapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int maxWidth = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? 0 : Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft()
                - getPaddingRight());
        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;
        int widestLine = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (maxWidth > 0 && lineWidth > 0 && lineWidth + hGap + cw > maxWidth) {
                totalHeight += lineHeight + vGap;
                widestLine = Math.max(widestLine, lineWidth);
                lineWidth = cw;
                lineHeight = ch;
            } else {
                lineWidth += (lineWidth > 0 ? hGap : 0) + cw;
                lineHeight = Math.max(lineHeight, ch);
            }
        }
        totalHeight += lineHeight;
        widestLine = Math.max(widestLine, lineWidth);
        setMeasuredDimension(
                resolveSize(widestLine + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = Math.max(0, r - l - getPaddingLeft() - getPaddingRight());
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (maxWidth > 0 && x > getPaddingLeft() && x + cw > getPaddingLeft() + maxWidth) {
                x = getPaddingLeft();
                y += lineHeight + vGap;
                lineHeight = 0;
            }
            child.layout(x, y, x + cw, y + ch);
            x += cw + hGap;
            lineHeight = Math.max(lineHeight, ch);
        }
    }
}
