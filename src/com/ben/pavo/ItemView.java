package com.ben.pavo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An icon + label cell used on the home grid, the dock and the app drawer. */
final class ItemView extends LinearLayout {

    interface Listener {
        void onItemClick(ItemView v);

        void onItemLongPress(ItemView v);

        void onItemDragStart(ItemView v);

        void onItemDragMove(ItemView v, float rawX, float rawY);

        void onItemRelease(ItemView v, float rawX, float rawY, boolean dragged, boolean longFired);

        void onItemDragCancel(ItemView v);
    }

    private final BuiltinIconView image;
    private final TextView label;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    Listener listener;
    Item item;
    Apps.Entry entry;
    int cellIndex = -1;
    int dockIndex = -1;
    boolean dragEnabled = true;

    private int iconSizePx;
    private float labelSp;
    private boolean marquee;
    private boolean marqueePaused;
    private float focusScale = 1f;
    private Drawable focusRingDrawable;
    private float downX, downY;
    private float lastRawX, lastRawY;
    private boolean longFired;
    private boolean dragging;
    private boolean moved;

    private final Runnable longRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow() || getParent() == null) return;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            longFired = true;
            Haptics.longPress(ItemView.this);
            if (listener != null) listener.onItemLongPress(ItemView.this);
        }
    };

    ItemView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(Ui.dp(c, 2), Ui.dp(c, 4), Ui.dp(c, 2), Ui.dp(c, 4));
        setForeground(Ui.pressHighlight(c, Ui.dpf(c, 18)));
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

        image = new BuiltinIconView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(image);

        label = new TextView(c);
        label.setTextColor(0xFFFFFFFF);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setShadowLayer(Ui.dpf(c, 3), 0, Ui.dpf(c, 1), 0xB3000000);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 5);
        addView(label, lp);

        setIconDp(52);
        setLabelSp(10f);
    }

    void setIconDp(int dp) {
        setIconSizePx(Ui.dp(getContext(), dp));
    }

    private int baseIconPx;

    void setIconSizePx(int px) {
        baseIconPx = Math.max(1, px);
        applyIconSizePx(baseIconPx);
        requestLayout();
    }

    private void applyIconSizePx(int px) {
        iconSizePx = Math.max(1, px);
        LayoutParams lp = (LayoutParams) image.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(iconSizePx, iconSizePx);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            image.setLayoutParams(lp);
        } else {
            lp.width = iconSizePx;
            lp.height = iconSizePx;
        }
    }

    int iconSizePx() {
        return iconSizePx;
    }

    float lastTouchX() {
        return lastRawX;
    }

    float lastTouchY() {
        return lastRawY;
    }

    /**
     * Fit the icon to the home-grid cell. When the grid is dense the icon shrinks; if even
     * a compact icon plus a label will not fit, the label is dropped for this layout so
     * rows can never overlap.
     */
    void fitToCell(int cellWidth, int cellHeight) {
        if (cellHeight <= 0) return;
        boolean labels = labelVisible();
        int padding = Ui.dp(getContext(), 8);
        if (cellWidth > 0) {
            int labelWidth = Math.max(1, cellWidth - Ui.dp(getContext(), 4));
            if (label.getMaxWidth() != labelWidth) label.setMaxWidth(labelWidth);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (label.getVisibility() == View.VISIBLE && label.getText() != null) {
                // Long translated app names should shrink a little before they
                // are ellipsized, so the home grid stays readable.
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
                float available = Math.max(1f, labelWidth - Ui.dp(getContext(), 2));
                float textWidth = label.getPaint().measureText(label.getText().toString());
                if (textWidth > available) {
                    float size = Math.max(7f, labelSp * available / textWidth);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
                }
            }
        }
        int labelSpace = labels ? Ui.dp(getContext(), 5) + Ui.dp(getContext(), 15) : 0;
        int maxIcon = cellHeight - padding - labelSpace;
        if (labels && maxIcon < Ui.dp(getContext(), 26)) {
            label.setVisibility(GONE);
            labels = false;
            maxIcon = cellHeight - padding;
        }
        maxIcon = Math.max(Ui.dp(getContext(), 14), maxIcon);
        applyIconSizePx(Math.min(baseIconPx, maxIcon));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int availH = MeasureSpec.getSize(hSpec);
        if (availH > 0) fitToCell(MeasureSpec.getSize(wSpec), availH);
        super.onMeasure(wSpec, hSpec);
    }

    boolean labelVisible() {
        return label.getVisibility() == View.VISIBLE;
    }

    void setLabelSp(float sp) {
        labelSp = sp;
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    /** Home-screen labels scroll from right to left when the text is wider than the cell. */
    void setMarqueeEnabled(boolean enabled) {
        marquee = enabled;
        if (enabled) {
            label.setSingleLine(true);
            label.setMaxLines(1);
            label.setHorizontallyScrolling(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            label.setMarqueeRepeatLimit(-1);
        } else {
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        updateMarqueeSelection();
    }

    /**
     * Pause the native TextView marquee so the window can become genuinely idle.
     * This gives the device's burn-in pixel shifter a chance to move the display.
     */
    void setMarqueePaused(boolean paused) {
        marqueePaused = paused;
        updateMarqueeSelection();
    }

    private void updateMarqueeSelection() {
        // Every label scrolls, focused or not, like the stock launcher.
        label.setSelected(marquee && !marqueePaused
                && label.getVisibility() == View.VISIBLE);
    }

    /** The dock uses 1.0 so its outer cells cannot clip the ring at the pill edge. */
    void setFocusScale(float scale) {
        focusScale = Math.max(1f, scale);
    }

    void bind(Item item, Apps.Entry entry, Bitmap customIcon, String labelText, boolean showLabel) {
        this.item = item;
        this.entry = entry;
        setContentDescription(labelText);

        Bitmap icon = customIcon;
        if (icon == null && entry != null) icon = entry.icon;
        if (icon == null) icon = Apps.letterTile(labelText, Math.max(1, iconSizePx));

        image.setImageBitmap(icon);
        image.setBackground(null);
        image.setBuiltInPackage(item == null ? null : item.pkg);

        label.setText(labelText);
        label.setVisibility(showLabel && labelText != null && !labelText.isEmpty()
                ? View.VISIBLE : View.GONE);
        updateMarqueeSelection();
    }

    Bitmap iconBitmap() {
        return Apps.drawableToBitmap(image.getDrawable(), Math.max(1, iconSizePx));
    }

    /**
     * Icon bounds in the parent grid's untransformed coordinates. This is what
     * the card-exit/scale animation must land on, rather than the temporary
     * on-screen bounds while the page is still card-scaled.
     */
    void iconLocalRect(RectF out) {
        if (image.getWidth() > 0 && image.getHeight() > 0) {
            out.set(getLeft() + image.getLeft(), getTop() + image.getTop(),
                    getLeft() + image.getRight(), getTop() + image.getBottom());
        } else {
            float cx = getLeft() + getWidth() / 2f;
            float cy = getTop() + getHeight() / 2f;
            float half = Math.max(1, iconSizePx) / 2f;
            out.set(cx - half, cy - half, cx + half, cy + half);
        }
    }

    /** Screen-space rectangle of the drawn icon (not the whole cell with its label). */
    void iconScreenRect(RectF out) {
        int[] loc = new int[2];
        if (image.getWidth() > 0 && image.getHeight() > 0) {
            image.getLocationOnScreen(loc);
            out.set(loc[0], loc[1], loc[0] + image.getWidth(), loc[1] + image.getHeight());
            return;
        }
        getLocationOnScreen(loc);
        float cx = loc[0] + getWidth() / 2f;
        float cy = loc[1] + getHeight() / 2f;
        float half = Math.max(1, iconSizePx) / 2f;
        out.set(cx - half, cy - half, cx + half, cy + half);
    }

    void setIconAnimationPaused(boolean paused) {
        if (image != null) image.setPaused(paused);
    }

    void setLabelVisible(boolean visible) {
        label.setVisibility(visible && label.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (focused) {
            Haptics.tick(this);
            showFocusRing();
            animate().scaleX(focusScale).scaleY(focusScale).setDuration(110).start();
        } else {
            hideFocusRing();
            animate().scaleX(1f).scaleY(1f).setDuration(110).start();
        }
        // Only the focused label scrolls. Every other label stays at its start
        // with an ellipsis, so resting grids never look clipped mid-marquee.
        if (marquee) {
            label.setSelected(focused && !marqueePaused);
            if (!focused) label.scrollTo(0, 0);
        }
    }

    /**
     * Draw the selection as an overlay sized from the icon bitmap itself. Using the
     * icon bounds (rather than the taller grid cell) keeps the ring away from every
     * page/header edge, and the overlay is drawn above the icon so full-bleed icons
     * cannot cover its top edge.
     */
    private void showFocusRing() {
        hideFocusRing();
        int iw = image.getWidth();
        int ih = image.getHeight();
        if (iw <= 0 || ih <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        int pad = Ui.dp(getContext(), 4);
        int edge = Ui.dp(getContext(), 2);
        int left = Math.max(edge, image.getLeft() - pad);
        int top = Math.max(edge, image.getTop() - pad);
        int right = Math.min(getWidth() - edge, image.getRight() + pad);
        int bottom = Math.min(getHeight() - edge, image.getBottom() + pad);
        if (right <= left || bottom <= top) return;
        float radius = Math.min(right - left, bottom - top) * 0.24f;
        focusRingDrawable = Ui.focusRing(getContext(), radius);
        focusRingDrawable.setBounds(left, top, right, bottom);
        getOverlay().add(focusRingDrawable);
        invalidate();
    }

    private void hideFocusRing() {
        if (focusRingDrawable != null) {
            getOverlay().remove(focusRingDrawable);
            focusRingDrawable = null;
            invalidate();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (isFocused()) showFocusRing();
    }

    void setLifted(boolean lifted) {
        animate().scaleX(lifted ? 1.12f : 1f).scaleY(lifted ? 1.12f : 1f)
                .alpha(lifted ? 0.95f : 1f).setDuration(120).start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float rx = e.getRawX();
        float ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = rx;
                downY = ry;
                lastRawX = rx;
                lastRawY = ry;
                longFired = false;
                dragging = false;
                moved = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                setPressed(true);
                handler.removeCallbacks(longRunnable);
                handler.postDelayed(longRunnable, 520);
                return true;
            case MotionEvent.ACTION_MOVE: {
                lastRawX = rx;
                lastRawY = ry;
                float dist = (float) Math.hypot(rx - downX, ry - downY);
                if (dist > touchSlop) moved = true;
                if (!longFired) {
                    if (dist > touchSlop) {
                        handler.removeCallbacks(longRunnable);
                        detachLongPress();
                    }
                } else if (dragEnabled) {
                    if (!dragging && dist > touchSlop) {
                        dragging = true;
                        if (listener != null) listener.onItemDragStart(this);
                    }
                    if (dragging && listener != null) listener.onItemDragMove(this, rx, ry);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                lastRawX = rx;
                lastRawY = ry;
                handler.removeCallbacks(longRunnable);
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging) {
                    if (listener != null) listener.onItemRelease(this, rx, ry, true, true);
                } else if (longFired) {
                    if (listener != null) listener.onItemRelease(this, rx, ry, false, true);
                } else if (!moved) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longRunnable);
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (dragging && listener != null) listener.onItemDragCancel(this);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void detachLongPress() {
        longFired = false;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onItemClick(this);
        return true;
    }
}
