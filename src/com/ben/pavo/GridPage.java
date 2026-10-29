package com.ben.pavo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A cell-positioned grid that fills the whole home page. */
final class GridPage extends ViewGroup {

    static final class WidgetSlot {
        final View view;
        int cell;
        int cols;
        int rows;

        WidgetSlot(View view, int cell, int cols, int rows) {
            this.view = view;
            this.cell = cell;
            this.cols = cols;
            this.rows = rows;
        }
    }

    private int cols = 6;
    private int rows = 3;
    private final List<ItemView> cells = new ArrayList<>();
    private final List<WidgetSlot> widgets = new ArrayList<>();
    private int highlight = -1;
    private int highlightCols = 1;
    private int highlightRows = 1;

    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF highlightRect = new RectF();
    private final RectF cardRect = new RectF();
    private final RectF gridRect = new RectF();
    private boolean cardMode;
    private float cardProgress;
    private View resizeActive;
    private final Map<Integer, Integer> resizeMoves = new LinkedHashMap<>();
    private final Set<ItemView> displacedIcons = new HashSet<>();
    private final Set<View> displacedWidgets = new HashSet<>();
    private float highlightProgress;
    private ValueAnimator highlightAnimator;

    GridPage(Context c) {
        super(c);
        setWillNotDraw(false);
        // The focus ring scales slightly outside its cell; never clip it at the
        // left/right page edges (the pager itself already allows overdraw).
        setClipChildren(false);
        setClipToPadding(false);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(Ui.dpf(c, 2));
        highlightPaint.setColor(0xB3FFFFFF);
        highlightFill.setStyle(Paint.Style.FILL);
        highlightFill.setColor(0x1FFFFFFF);
        cardFill.setStyle(Paint.Style.FILL);
        cardFill.setColor(0x4D0B0E14);
        cardStroke.setStyle(Paint.Style.STROKE);
        cardStroke.setStrokeWidth(Ui.dpf(c, 1));
        cardStroke.setColor(0x33FFFFFF);
        gridStroke.setStyle(Paint.Style.STROKE);
        gridStroke.setStrokeWidth(Ui.dpf(c, 1));
        gridStroke.setColor(0x30FFFFFF);
        gridFill.setStyle(Paint.Style.FILL);
        gridFill.setColor(0x12FFFFFF);
    }

    void setCardMode(boolean enabled) {
        if (cardMode == enabled) return;
        cardMode = enabled;
        if (!enabled) cardProgress = 0f;
        invalidate();
    }

    void setCardProgress(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(cardProgress - p) < 0.001f) return;
        cardProgress = p;
        invalidate();
    }

    void setGrid(int cols, int rows) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
    }

    int capacity() {
        return cols * rows;
    }

    int cellWidth() {
        return Math.max(1, getWidth() / cols);
    }

    int cellHeight() {
        return Math.max(1, getHeight() / rows);
    }

    int gridLeft() {
        return 0;
    }

    int gridTop() {
        return 0;
    }

    /** Bind one entry per cell; null is an empty (but usable) slot. */
    void bind(List<ItemView> views) {
        removeAllViews();
        cells.clear();
        widgets.clear();
        for (int i = 0; i < capacity(); i++) {
            ItemView v = i < views.size() ? views.get(i) : null;
            cells.add(v);
            if (v != null) {
                v.cellIndex = i;
                addView(v);
            }
        }
        requestLayout();
        invalidate();
    }

    /** Add a widget / clock view spanning one or more cells. */
    void addWidget(View v, int cell, int spanCols, int spanRows) {
        if (v == null || cell < 0) return;
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
        WidgetSlot slot = new WidgetSlot(v, cell, Math.max(1, spanCols),
                Math.max(1, spanRows));
        widgets.add(slot);
        v.setVisibility(View.VISIBLE);
        addView(v);
        requestLayout();
    }

    /**
     * Reflow icons and other widgets out of the live resize preview. This is
     * visual only until the caller commits a successful resize.
     */
    void previewResizeDisplacement(View active, int cell, int spanCols, int spanRows) {
        if (getWidth() <= 0 || getHeight() <= 0 || cell < 0) {
            clearResizeDisplacement();
            return;
        }
        resizeActive = active;
        int targetCols = Math.max(1, Math.min(cols, spanCols));
        int targetRows = Math.max(1, Math.min(rows, spanRows));
        int c = cell % cols;
        int r = cell / cols;
        targetCols = Math.min(targetCols, cols - c);
        targetRows = Math.min(targetRows, rows - r);
        Set<Integer> targetCells = new HashSet<>();
        for (int y = 0; y < targetRows; y++) {
            for (int x = 0; x < targetCols; x++) {
                targetCells.add((r + y) * cols + c + x);
            }
        }

        // Cells occupied by widgets other than the active resizing widget.
        boolean[] occupied = new boolean[cols * rows];
        for (WidgetSlot slot : widgets) {
            if (slot.view == active) continue;
            int sc = slot.cell % cols;
            int sr = slot.cell / cols;
            for (int y = 0; y < slot.rows; y++) {
                for (int x = 0; x < slot.cols; x++) {
                    int idx = (sr + y) * cols + sc + x;
                    if (idx >= 0 && idx < occupied.length) occupied[idx] = true;
                }
            }
        }
        // Free cells outside the preview, and reserve cells already chosen.
        for (int i = 0; i < cells.size() && i < occupied.length; i++) {
            if (!targetCells.contains(i) && cells.get(i) != null) occupied[i] = true;
        }

        Map<Integer, Integer> nextMoves = new LinkedHashMap<>();
        List<Integer> sources = new ArrayList<>();
        for (int source : targetCells) {
            if (source >= 0 && source < cells.size() && cells.get(source) != null) {
                sources.add(source);
            }
        }
        for (int source : sources) {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            int sr = source / cols;
            int sc = source % cols;
            for (int i = 0; i < occupied.length; i++) {
                if (occupied[i] || targetCells.contains(i)) continue;
                int dist = Math.abs(i % cols - sc) + Math.abs(i / cols - sr);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    best = i;
                }
            }
            if (best >= 0) {
                occupied[best] = true;
                nextMoves.put(source, best);
            }
        }

        // Return icons that are no longer displaced.
        for (ItemView icon : new ArrayList<>(displacedIcons)) {
            if (!nextMoves.containsKey(icon.cellIndex)) {
                icon.animate().translationX(0f).translationY(0f).alpha(1f)
                        .setDuration(140L)
                        .setInterpolator(new DecelerateInterpolator(1.8f)).start();
                displacedIcons.remove(icon);
            }
        }
        for (Map.Entry<Integer, Integer> entry : nextMoves.entrySet()) {
            ItemView icon = cells.get(entry.getKey());
            if (icon == null) continue;
            RectF from = new RectF(icon.getLeft(), icon.getTop(),
                    icon.getRight(), icon.getBottom());
            RectF to = new RectF();
            cellRect(entry.getValue(), to);
            float dx = to.centerX() - from.centerX();
            float dy = to.centerY() - from.centerY();
            icon.animate().translationX(dx).translationY(dy).alpha(1f)
                    .setDuration(140L)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            displacedIcons.add(icon);
        }
        resizeMoves.clear();
        resizeMoves.putAll(nextMoves);

        // If the preview crosses another widget, let that widget visibly get out
        // of the way too. It returns if the resize is canceled.
        RectF preview = new RectF();
        cellRect(cell, targetCols, targetRows, preview);
        for (WidgetSlot slot : widgets) {
            if (slot.view == active) continue;
            RectF wr = new RectF();
            cellRect(slot.cell, slot.cols, slot.rows, wr);
            if (!RectF.intersects(preview, wr)) {
                if (displacedWidgets.remove(slot.view)) {
                    slot.view.animate().translationX(0f).translationY(0f)
                            .alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(150L).start();
                }
                continue;
            }
            float dx = wr.centerX() < preview.centerX() ? -cellWidth() : cellWidth();
            float dy = wr.centerY() < preview.centerY() ? -cellHeight() : cellHeight();
            slot.view.animate().translationX(dx).translationY(dy).alpha(0.18f)
                    .scaleX(0.94f).scaleY(0.94f)
                    .setDuration(150L).start();
            displacedWidgets.add(slot.view);
        }
    }

    Map<Integer, Integer> resizeDisplacements() {
        return new LinkedHashMap<>(resizeMoves);
    }

    void clearResizeDisplacement() {
        resizeActive = null;
        resizeMoves.clear();
        for (ItemView icon : displacedIcons) {
            icon.animate().translationX(0f).translationY(0f).alpha(1f)
                    .setDuration(140L)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
        for (View widget : displacedWidgets) {
            widget.animate().translationX(0f).translationY(0f).alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150L).start();
        }
        displacedIcons.clear();
        displacedWidgets.clear();
    }

    /** Live resize of a placed widget; the caller owns persistence. */
    void resizeWidget(View view, int cell, int spanCols, int spanRows) {
        for (WidgetSlot slot : widgets) {
            if (slot.view != view) continue;
            slot.cell = Math.max(0, cell);
            slot.cols = Math.max(1, spanCols);
            slot.rows = Math.max(1, spanRows);
            requestLayout();
            invalidate();
            return;
        }
    }

    List<ItemView> cellViews() {
        return cells;
    }

    boolean hasWidgets() {
        return !widgets.isEmpty();
    }

    List<View> widgetViews() {
        List<View> out = new ArrayList<>();
        for (WidgetSlot slot : widgets) {
            if (slot != null && slot.view != null) out.add(slot.view);
        }
        return out;
    }

    /** True when the cell is covered by a widget or the clock. */
    boolean widgetAt(int cell) {
        if (cell < 0) return false;
        int c = cell % cols;
        int r = cell / cols;
        for (WidgetSlot w : widgets) {
            int wc = w.cell % cols;
            int wr = w.cell / cols;
            if (c >= wc && c < wc + w.cols && r >= wr && r < wr + w.rows) return true;
        }
        return false;
    }

    ItemView viewAt(int cell) {
        return cell >= 0 && cell < cells.size() ? cells.get(cell) : null;
    }

    void setHighlight(int cell) {
        setHighlight(cell, 1, 1);
    }

    void setHighlight(int cell, int spanCols, int spanRows) {
        int cc = Math.max(1, spanCols);
        int rr = Math.max(1, spanRows);
        if (highlight == cell && highlightCols == cc && highlightRows == rr) return;
        boolean wasEmpty = highlight < 0;
        highlight = cell;
        highlightCols = cc;
        highlightRows = rr;
        if (highlightAnimator != null) {
            highlightAnimator.cancel();
            highlightAnimator = null;
        }
        if (cell < 0) {
            highlightProgress = 0f;
            invalidate();
            return;
        }
        if (wasEmpty && highlightProgress < 0.999f) {
            highlightAnimator = ValueAnimator.ofFloat(highlightProgress, 1f);
            highlightAnimator.setDuration(150L);
            highlightAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
            highlightAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    highlightProgress = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            highlightAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (highlightAnimator == animation) highlightAnimator = null;
                }
            });
            highlightAnimator.start();
        } else {
            highlightProgress = 1f;
            invalidate();
        }
    }

    int highlight() {
        return highlight;
    }

    int cellAt(float x, float y) {
        int cw = Math.max(1, getWidth() / cols);
        int ch = Math.max(1, getHeight() / rows);
        int c = (int) Math.floor(x / cw);
        int r = (int) Math.floor(y / ch);
        if (c < 0 || c >= cols || r < 0 || r >= rows) return -1;
        return r * cols + c;
    }

    int nearestCell(float x, float y) {
        int cw = Math.max(1, getWidth() / cols);
        int ch = Math.max(1, getHeight() / rows);
        int c = (int) Math.floor(x / cw);
        int r = (int) Math.floor(y / ch);
        c = Math.max(0, Math.min(cols - 1, c));
        r = Math.max(0, Math.min(rows - 1, r));
        return r * cols + c;
    }

    void cellRect(int cell, RectF out) {
        cellRect(cell, 1, 1, out);
    }

    void cellRect(int cell, int spanCols, int spanRows, RectF out) {
        if (cell < 0) {
            out.setEmpty();
            return;
        }
        float cw = getWidth() / (float) cols;
        float ch = getHeight() / (float) rows;
        int c = cell % cols;
        int r = cell / cols;
        out.set(c * cw, r * ch, (c + Math.max(1, spanCols)) * cw,
                (r + Math.max(1, spanRows)) * ch);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int cw = Math.max(1, w / cols);
        int ch = Math.max(1, h / rows);
        int cwSpec = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY);
        int chSpec = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
        for (ItemView v : cells) {
            if (v != null && v.getParent() == this) v.measure(cwSpec, chSpec);
        }
        int inset = Ui.dp(getContext(), 4);
        for (WidgetSlot slot : widgets) {
            int ww = Math.max(1, cw * slot.cols - inset * 2);
            int wh = Math.max(1, ch * slot.rows - inset * 2);
            slot.view.measure(MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(wh, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int cw = Math.max(1, w / cols);
        int ch = Math.max(1, h / rows);
        for (int i = 0; i < cells.size(); i++) {
            ItemView v = cells.get(i);
            if (v == null || v.getParent() != this) continue;
            int c = i % cols;
            int rr = i / cols;
            int left = c * cw;
            int top = rr * ch;
            v.layout(left, top, left + cw, top + ch);
        }
        int inset = Ui.dp(getContext(), 4);
        for (WidgetSlot slot : widgets) {
            int c = slot.cell % cols;
            int rr = slot.cell / cols;
            int left = c * cw + inset;
            int top = rr * ch + inset;
            int right = (c + slot.cols) * cw - inset;
            int bottom = (rr + slot.rows) * ch - inset;
            slot.view.layout(left, top, Math.max(left + 1, right), Math.max(top + 1, bottom));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cardMode && getWidth() > 0 && getHeight() > 0 && cardProgress > 0.001f) {
            // Nearly the full page surface, so widgets and edge cells stay
            // visually inside the card instead of poking through its border.
            float inset = 1f;
            cardRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float radius = Ui.dpf(getContext(), 24);
            cardFill.setAlpha((int) (0x4D * cardProgress));
            cardStroke.setAlpha((int) (0x33 * cardProgress));
            canvas.drawRoundRect(cardRect, radius, radius, cardFill);
            canvas.drawRoundRect(cardRect, radius, radius, cardStroke);

            // During a drag, show the actual grid cells. Empty cells are
            // brighter so it is obvious where an icon can be dropped.
            float cellRadius = Ui.dpf(getContext(), 11);
            float gridInset = Ui.dpf(getContext(), 4);
            for (int i = 0; i < cols * rows; i++) {
                if (widgetAt(i)) continue;
                boolean free = i >= cells.size() || cells.get(i) == null;
                cellRect(i, gridRect);
                gridRect.inset(gridInset, gridInset);
                if (free) {
                    gridFill.setAlpha((int) (0x14 * cardProgress));
                    canvas.drawRoundRect(gridRect, cellRadius, cellRadius, gridFill);
                }
                gridStroke.setAlpha((int) ((free ? 0x30 : 0x14) * cardProgress));
                canvas.drawRoundRect(gridRect, cellRadius, cellRadius, gridStroke);
            }
        }
        if (highlight >= 0 && highlightProgress > 0.001f) {
            cellRect(highlight, highlightCols, highlightRows, highlightRect);
            float inset = Ui.dpf(getContext(), 6)
                    + (1f - highlightProgress) * Ui.dpf(getContext(), 9);
            highlightRect.inset(inset, inset);
            float radius = Ui.dpf(getContext(), 18);
            highlightFill.setAlpha((int) (0x1F * highlightProgress));
            highlightPaint.setAlpha((int) (0xB3 * highlightProgress));
            canvas.drawRoundRect(highlightRect, radius, radius, highlightFill);
            canvas.drawRoundRect(highlightRect, radius, radius, highlightPaint);
        }
    }
}
