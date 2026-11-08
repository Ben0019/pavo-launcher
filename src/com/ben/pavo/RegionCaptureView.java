package com.ben.pavo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Frozen-screen rectangle selector, modelled on Spectacle's region mode.
 *
 * <p>The screen is captured first and dimmed; dragging creates a box, dragging
 * inside it moves it, and dragging a corner or edge handle resizes it. Nothing
 * is saved here - the host shows the SAVE/DELETE/RESET/CANCEL bar.
 */
final class RegionCaptureView extends View {

    interface Listener {
        void onSelectionChanged(boolean hasSelection);

        void onCancel();
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_CREATE = 1;
    private static final int MODE_MOVE = 2;
    private static final int MODE_RESIZE = 3;

    private final Bitmap shot;
    private final Listener listener;
    private final Paint screenPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF selection = new RectF();
    private final RectF startRect = new RectF();
    private final RectF resetRect = new RectF();
    private final String hint;
    private final float dp;
    private final float labelHeight;
    private float startX;
    private float startY;
    private float touchX;
    private float touchY;
    private int mode = MODE_NONE;
    private int handle = -1;
    private boolean hasSelection;
    private boolean hasResetRect;

    RegionCaptureView(Context c, Bitmap shot, Listener listener) {
        super(c);
        this.shot = shot;
        this.listener = listener;
        dp = getResources().getDisplayMetrics().density;
        ThemeColors.initialize(c);
        int accent = ThemeColors.accent();

        screenPaint.setFilterBitmap(true);
        dimPaint.setColor(0xB8000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));
        borderPaint.setColor(0xE6FFFFFF);

        accentPaint.setColor(accent);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(dp(3f));
        accentPaint.setStrokeCap(Paint.Cap.SQUARE);

        pillPaint.setColor(0xE60B0E14);

        labelPaint.setColor(0xF2FFFFFF);
        labelPaint.setTextSize(sp(10.5f));
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setAntiAlias(true);

        hintPaint.setColor(0xF2FFFFFF);
        hintPaint.setTextSize(sp(12f));
        hintPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hintPaint.setAntiAlias(true);
        hintPaint.setTextAlign(Paint.Align.CENTER);

        labelHeight = sp(10.5f) + dp(12f);
        hint = Loc.s(c, "Drag to select · tap to cancel");
        setClickable(true);
    }

    void release() {
        if (shot != null && !shot.isRecycled()) shot.recycle();
    }

    boolean hasSelection() {
        return hasSelection;
    }

    /** DELETE: drop the box and go back to the empty selector. */
    void clearSelection() {
        if (!hasSelection) return;
        hasSelection = false;
        mode = MODE_NONE;
        invalidate();
        if (listener != null) listener.onSelectionChanged(false);
    }

    /** RESET: return to the rectangle the first drag produced. */
    void resetSelection() {
        if (!hasResetRect) return;
        selection.set(resetRect);
        hasSelection = true;
        invalidate();
        if (listener != null) listener.onSelectionChanged(true);
    }

    /** Copies the current box into {@code out}; false when nothing is selected. */
    boolean copySelection(RectF out) {
        if (!hasSelection) return false;
        out.set(selection);
        return true;
    }

    Bitmap cropSelection() {
        if (!hasSelection) return null;
        Rect r = sourceRect(selection);
        try {
            return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || shot == null || shot.isRecycled()) return;

        RectF whole = new RectF(0, 0, w, h);
        canvas.drawBitmap(shot, null, whole, screenPaint);
        canvas.drawRect(whole, dimPaint);

        if (hasSelection) {
            canvas.drawBitmap(shot, sourceRect(selection), selection, screenPaint);
            canvas.drawRect(selection, borderPaint);
            drawHandles(canvas);
            drawSize(canvas);
        } else {
            drawHint(canvas, w, h);
        }
    }

    private void drawHandles(Canvas canvas) {
        float arm = dp(14f);
        float off = dp(1.5f);
        float l = selection.left - off;
        float t = selection.top - off;
        float r = selection.right + off;
        float b = selection.bottom + off;
        // corner brackets
        canvas.drawLine(l, t, l + arm, t, accentPaint);
        canvas.drawLine(l, t, l, t + arm, accentPaint);
        canvas.drawLine(r, t, r - arm, t, accentPaint);
        canvas.drawLine(r, t, r, t + arm, accentPaint);
        canvas.drawLine(l, b, l + arm, b, accentPaint);
        canvas.drawLine(l, b, l, b - arm, accentPaint);
        canvas.drawLine(r, b, r - arm, b, accentPaint);
        canvas.drawLine(r, b, r, b - arm, accentPaint);

        // edge handles
        float half = dp(9f);
        float cx = selection.centerX();
        float cy = selection.centerY();
        canvas.drawLine(cx - half, t, cx + half, t, accentPaint);
        canvas.drawLine(cx - half, b, cx + half, b, accentPaint);
        canvas.drawLine(l, cy - half, l, cy + half, accentPaint);
        canvas.drawLine(r, cy - half, r, cy + half, accentPaint);
    }

    private void drawSize(Canvas canvas) {
        Rect src = sourceRect(selection);
        String text = src.width() + " \u00d7 " + src.height();
        float pad = dp(8f);
        float textW = labelPaint.measureText(text);
        float pillW = textW + pad * 2f;
        float pillH = labelHeight;
        float left = Math.max(dp(6f), Math.min(selection.left,
                getWidth() - pillW - dp(6f)));
        float top = selection.top - pillH - dp(8f);
        if (top < dp(6f)) {
            top = Math.min(selection.bottom + dp(8f),
                    getHeight() - pillH - dp(6f));
        }
        RectF pill = new RectF(left, top, left + pillW, top + pillH);
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, pillPaint);
        float baseline = pill.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f;
        canvas.drawText(text, pill.left + pad, baseline, labelPaint);
    }

    private void drawHint(Canvas canvas, int w, int h) {
        float pad = dp(16f);
        float textW = hintPaint.measureText(hint);
        float pillW = textW + pad * 2f;
        float pillH = dp(34f);
        float left = (w - pillW) / 2f;
        float top = dp(46f);
        RectF pill = new RectF(left, top, left + pillW, top + pillH);
        canvas.drawRoundRect(pill, pillH / 2f, pillH / 2f, pillPaint);
        float baseline = pill.centerY() - (hintPaint.descent() + hintPaint.ascent()) / 2f;
        canvas.drawText(hint, pill.centerX(), baseline, hintPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = clamp(event.getX(), 0f, getWidth());
                touchY = clamp(event.getY(), 0f, getHeight());
                startX = touchX;
                startY = touchY;
                if (hasSelection) {
                    int hit = hitHandle(touchX, touchY);
                    if (hit >= 0) {
                        mode = MODE_RESIZE;
                        handle = hit;
                        startRect.set(selection);
                        return true;
                    }
                    if (selection.contains(touchX, touchY)) {
                        mode = MODE_MOVE;
                        startRect.set(selection);
                        return true;
                    }
                }
                mode = MODE_CREATE;
                handle = -1;
                selection.set(touchX, touchY, touchX, touchY);
                setSelection(false);
                Haptics.tick(this);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_NONE) return true;
                touchX = clamp(event.getX(), 0f, getWidth());
                touchY = clamp(event.getY(), 0f, getHeight());
                if (mode == MODE_CREATE) {
                    selection.set(Math.min(startX, touchX), Math.min(startY, touchY),
                            Math.max(startX, touchX), Math.max(startY, touchY));
                    setSelection(selection.width() >= dp(4f)
                            && selection.height() >= dp(4f));
                } else if (mode == MODE_MOVE) {
                    move();
                    notifySelection();
                } else if (mode == MODE_RESIZE) {
                    resize();
                    notifySelection();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == MODE_CREATE && !hasSelection) {
                    listener.onCancel();
                } else if (mode == MODE_CREATE) {
                    rememberReset();
                }
                mode = MODE_NONE;
                handle = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE;
                handle = -1;
                if (!hasSelection) listener.onCancel();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void setSelection(boolean has) {
        if (hasSelection == has) {
            notifySelection();
            return;
        }
        hasSelection = has;
        notifySelection();
    }

    private void notifySelection() {
        if (listener != null) listener.onSelectionChanged(hasSelection);
    }

    private void rememberReset() {
        resetRect.set(selection);
        hasResetRect = true;
    }

    private void move() {
        float dx = touchX - startX;
        float dy = touchY - startY;
        float l = startRect.left + dx;
        float t = startRect.top + dy;
        float r = startRect.right + dx;
        float b = startRect.bottom + dy;
        if (l < 0f) {
            r -= l;
            l = 0f;
        }
        if (t < 0f) {
            b -= t;
            t = 0f;
        }
        if (r > getWidth()) {
            l -= r - getWidth();
            r = getWidth();
        }
        if (b > getHeight()) {
            t -= b - getHeight();
            b = getHeight();
        }
        selection.set(l, t, r, b);
    }

    private void resize() {
        float l = selection.left;
        float t = selection.top;
        float r = selection.right;
        float b = selection.bottom;
        switch (handle) {
            case 0: l = touchX; t = touchY; break; // top-left
            case 1: t = touchY; break;             // top
            case 2: r = touchX; t = touchY; break; // top-right
            case 3: r = touchX; break;             // right
            case 4: r = touchX; b = touchY; break; // bottom-right
            case 5: b = touchY; break;             // bottom
            case 6: l = touchX; b = touchY; break; // bottom-left
            case 7: l = touchX; break;             // left
            default: break;
        }
        l = clamp(l, 0f, getWidth());
        r = clamp(r, 0f, getWidth());
        t = clamp(t, 0f, getHeight());
        b = clamp(b, 0f, getHeight());
        float min = dp(24f);
        // Keep the anchored edge still when the dragged edge would cross it.
        boolean leftEdge = handle == 0 || handle == 6 || handle == 7;
        boolean rightEdge = handle == 2 || handle == 3 || handle == 4;
        boolean topEdge = handle == 0 || handle == 1 || handle == 2;
        boolean bottomEdge = handle == 4 || handle == 5 || handle == 6;
        if (r - l < min) {
            if (leftEdge) l = r - min;
            else if (rightEdge) r = l + min;
            else {
                l = Math.max(0f, l - (min - (r - l)) / 2f);
                r = Math.min(getWidth(), l + min);
            }
        }
        if (b - t < min) {
            if (topEdge) t = b - min;
            else if (bottomEdge) b = t + min;
            else {
                t = Math.max(0f, t - (min - (b - t)) / 2f);
                b = Math.min(getHeight(), t + min);
            }
        }
        selection.set(l, t, r, b);
    }

    /** 0 TL, 1 T, 2 TR, 3 R, 4 BR, 5 B, 6 BL, 7 L. */
    private int hitHandle(float x, float y) {
        float t = dp(22f);
        float l = selection.left;
        float top = selection.top;
        float r = selection.right;
        float b = selection.bottom;
        float cx = selection.centerX();
        float cy = selection.centerY();
        if (near(x, y, l, top, t)) return 0;
        if (near(x, y, r, top, t)) return 2;
        if (near(x, y, r, b, t)) return 4;
        if (near(x, y, l, b, t)) return 6;
        if (near(x, y, cx, top, t)) return 1;
        if (near(x, y, r, cy, t)) return 3;
        if (near(x, y, cx, b, t)) return 5;
        if (near(x, y, l, cy, t)) return 7;
        return -1;
    }

    private static boolean near(float x, float y, float px, float py, float radius) {
        return Math.abs(x - px) <= radius && Math.abs(y - py) <= radius;
    }

    /** Maps a view rectangle onto the frozen screen bitmap. */
    private Rect sourceRect(RectF dst) {
        float sx = shot.getWidth() / (float) Math.max(1, getWidth());
        float sy = shot.getHeight() / (float) Math.max(1, getHeight());
        int l = (int) clamp(Math.round(dst.left * sx), 0, shot.getWidth());
        int t = (int) clamp(Math.round(dst.top * sy), 0, shot.getHeight());
        int r = (int) clamp(Math.round(dst.right * sx), l + 1, shot.getWidth());
        int b = (int) clamp(Math.round(dst.bottom * sy), t + 1, shot.getHeight());
        return new Rect(l, t, r, b);
    }

    private float dp(float value) {
        return value * dp;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }
}
