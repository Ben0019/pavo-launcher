package com.ben.aurora;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.OverScroller;

import java.util.Random;

/** Minimal horizontal, snapping pager (no AndroidX). */
final class Pager extends ViewGroup {
    static final float CARD_SCALE = 0.94f;

    private static final String[] STYLES = {
            "slide", "fade", "zoom", "cube", "stack", "parallax"
    };
    private static final String[] RANDOM_STYLES = {
            "fade", "zoom", "cube", "stack", "parallax"
    };

    interface Listener {
        void onPageChanged(int index, int count);
    }

    private final OverScroller scroller;
    private final int touchSlop;
    private final int minFling;
    private final int maxFling;
    private final Random random = new Random();
    private Listener listener;
    private float downX, downY, lastX;
    private VelocityTracker vt;
    private boolean locked;
    private int pageWidth = 1;
    private int axisLock; // 0 undecided, 1 horizontal, 2 vertical
    private boolean verticalSuppress;
    private int gestureStartPage;
    private String baseStyle = "slide";
    private String activeStyle = "slide";
    private int dragStyleToken;
    private int peekBasePage = -1;
    private int peekDir;
    private boolean peekVisual;
    private boolean cardMode;
    private float cardVisual;
    private ValueAnimator cardAnimator;
    private ValueAnimator dragAnimator;
    private int dragAnimatorPage = -1;
    private int notifiedPage = -1;
    private int notifiedCount = -1;

    Pager(Context c) {
        super(c);
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        setClipChildren(false);
    }

    void setAnimationStyle(String style) {
        // Page transition effects are intentionally disabled for now. The
        // selected style is retained by the settings UI but every page change
        // uses the plain, clip-free slide until the drag/page-card work settles.
        baseStyle = "slide";
        activeStyle = "slide";
        invalidate();
    }

    private String randomStyle() {
        return "slide";
    }

    private void pickStyle() {
        activeStyle = "slide";
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setLocked(boolean l) {
        locked = l;
    }

    boolean isLocked() {
        return locked;
    }

    int currentPage() {
        if (pageWidth <= 0) return 0;
        return Math.max(0, Math.min(Math.max(0, getChildCount() - 1),
                Math.round(getScrollX() / (float) pageWidth)));
    }

    int pageCount() {
        return getChildCount();
    }

    int pageWidth() {
        return pageWidth;
    }

    void setPage(int index, boolean animate) {
        pickStyle();
        setPageInternal(index, animate);
    }

    /**
     * Page change initiated while an icon is being carried. Always use a plain slide
     * so cube/zoom/stack transforms cannot clip the dragged icon or blank the page
     * underneath the finger.
     */
    void setPageForDrag(int index, boolean animate) {
        activeStyle = "slide";
        if (animate) {
            animateDragPage(index);
        } else {
            setPageInternal(index, false);
        }
        final int token = ++dragStyleToken;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != dragStyleToken) return;
                activeStyle = "random".equals(baseStyle) ? randomStyle() : baseStyle;
                invalidate();
            }
        }, 400);
    }

    /**
     * Dedicated drag-page animation. Instead of OverScroller's fast initial
     * kick, this uses a material ease-in-out curve so the card accelerates
     * gently and settles without a hard stop.
     */
    private void animateDragPage(int index) {
        int count = getChildCount();
        if (count == 0 || pageWidth <= 0) return;
        index = Math.max(0, Math.min(count - 1, index));
        final int target = index * pageWidth;
        if (!scroller.isFinished()) scroller.abortAnimation();
        if (dragAnimator != null) {
            ValueAnimator old = dragAnimator;
            dragAnimator = null;
            old.cancel();
        }
        if (getScrollX() == target) {
            notifyPage();
            return;
        }
        dragAnimatorPage = index;
        final ValueAnimator anim = ValueAnimator.ofInt(getScrollX(), target);
        anim.setDuration(360L);
        anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (dragAnimator != animation) return;
                scrollTo((Integer) animation.getAnimatedValue(), 0);
                notifyPage();
                postInvalidateOnAnimation();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (dragAnimator != animation) return;
                scrollTo(target, 0);
                notifyPage();
                dragAnimator = null;
                invalidate();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (dragAnimator == animation) dragAnimator = null;
            }
        });
        dragAnimator = anim;
        anim.start();
    }

    private void setPageInternal(int index, boolean animate) {
        int count = getChildCount();
        if (count == 0) return;
        index = Math.max(0, Math.min(count - 1, index));
        int target = index * pageWidth;
        if (animate) {
            int distance = Math.abs(target - getScrollX());
            int duration = Math.max(220, Math.min(380,
                    180 + Math.round(220f * distance / Math.max(1, pageWidth))));
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, duration);
            postInvalidateOnAnimation();
        } else {
            scrollTo(target, 0);
            notifyPage();
        }
    }

    /** Toggle the inset drag-card look for all pages. */
    void setCardMode(boolean enabled) {
        if (enabled) {
            cardMode = true;
            for (int i = 0; i < getChildCount(); i++) {
                View page = getChildAt(i);
                if (page == null) continue;
                if (page instanceof GridPage) ((GridPage) page).setCardMode(true);
                // Cache the finished page as a hardware layer for the duration
                // of the drag. Scaling and fading the whole page then composites
                // the existing layer instead of redrawing every icon and widget
                // each frame during the card slide.
                page.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
            animateCardVisual(1f, 220L);
        } else {
            cardMode = false;
            if (cardVisual <= 0.001f) {
                finishCardExit();
            } else {
                // Ease the card back to full size instead of snapping the page
                // open before the icon has finished landing.
                animateCardVisual(0f, 190L);
            }
        }
    }

    void finishCardImmediately() {
        cardMode = false;
        if (cardAnimator != null) {
            cardAnimator.cancel();
            cardAnimator = null;
        }
        finishCardExit();
    }

    private void finishCardExit() {
        cardVisual = 0f;
        setCardProgressOnPages(0f);
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            if (page == null) continue;
            if (page instanceof GridPage) ((GridPage) page).setCardMode(false);
            page.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        invalidate();
    }

    private void animateCardVisual(float target, long duration) {
        if (cardAnimator != null) {
            cardAnimator.cancel();
            cardAnimator = null;
        }
        if (Math.abs(cardVisual - target) < 0.001f) {
            cardVisual = target;
            setCardProgressOnPages(target);
            invalidate();
            return;
        }
        cardAnimator = ValueAnimator.ofFloat(cardVisual, target);
        cardAnimator.setDuration(duration);
        cardAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        cardAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                cardVisual = (Float) animation.getAnimatedValue();
                setCardProgressOnPages(cardVisual);
                invalidate();
            }
        });
        cardAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cardAnimator == animation) {
                    cardAnimator = null;
                    if (!cardMode) finishCardExit();
                }
            }
        });
        cardAnimator.start();
    }

    private void setCardProgressOnPages(float progress) {
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            if (page instanceof GridPage) ((GridPage) page).setCardProgress(progress);
        }
    }

    float cardScale() {
        return 1f + (CARD_SCALE - 1f) * cardVisual;
    }

    float cardProgress() {
        return cardVisual;
    }

    /** Reveal the adjacent page as a card without committing a page change. */
    void peekPage(int dir, boolean animate) {
        int count = getChildCount();
        if (count == 0 || pageWidth <= 0 || dir == 0) return;
        peekBasePage = currentPage();
        peekDir = dir;
        peekVisual = true;
        int targetPage = peekBasePage + dir;
        if (targetPage < 0 || targetPage >= count) {
            peekBasePage = -1;
            peekDir = 0;
            return;
        }
        activeStyle = "slide";
        int target = Math.round(peekBasePage * pageWidth + dir * pageWidth * 0.30f);
        target = Math.max(0, Math.min((count - 1) * pageWidth, target));
        if (animate) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 240);
            postInvalidateOnAnimation();
        } else {
            scrollTo(target, 0);
            invalidate();
        }
    }

    boolean hasPeek() {
        return peekBasePage >= 0;
    }

    int peekBasePage() {
        return peekBasePage;
    }

    int peekDir() {
        return peekDir;
    }

    void cancelPeek(boolean animate) {
        if (peekBasePage < 0) return;
        int base = peekBasePage;
        peekBasePage = -1;
        peekDir = 0;
        peekVisual = false;
        activeStyle = "slide";
        setPageInternal(base, animate);
    }

    void commitPeek(boolean animate) {
        if (peekBasePage < 0) return;
        int target = peekBasePage + peekDir;
        peekBasePage = -1;
        peekDir = 0;
        // keep the card visual during the page slide; the owner clears it
        // before final drop/hit testing.
        setPageForDrag(target, animate);
    }

    void setPeekVisual(boolean enabled) {
        if (peekVisual == enabled) return;
        peekVisual = enabled;
        invalidate();
    }

    boolean isCardMode() {
        return cardMode;
    }

    boolean isScrolling() {
        return (dragAnimator != null && dragAnimator.isRunning()) || !scroller.isFinished();
    }

    /** Finish any in-flight page scroll and land on its target page. */
    void settleScrolling() {
        int count = getChildCount();
        if (count <= 0 || pageWidth <= 0) return;
        if (dragAnimator != null) {
            ValueAnimator anim = dragAnimator;
            dragAnimator = null;
            anim.cancel();
            int page = dragAnimatorPage >= 0 ? dragAnimatorPage : currentPage();
            page = Math.max(0, Math.min(count - 1, page));
            scrollTo(page * pageWidth, 0);
            notifyPage();
            invalidate();
            return;
        }
        if (!scroller.isFinished()) {
            int finalX = scroller.getFinalX();
            scroller.abortAnimation();
            int page = Math.max(0, Math.min(count - 1,
                    Math.round(finalX / (float) pageWidth)));
            scrollTo(page * pageWidth, 0);
            notifyPage();
            invalidate();
            return;
        }
        int target = currentPage() * pageWidth;
        if (getScrollX() != target) {
            scrollTo(target, 0);
            notifyPage();
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        applyPageTransforms();
        super.dispatchDraw(canvas);
    }

    /**
     * Applies the active page transition. The pager still scrolls horizontally, then each
     * page gets a style-specific transform based on its distance from the viewport.
     */
    private void applyPageTransforms() {
        int count = getChildCount();
        if (count == 0 || pageWidth <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int scroll = getScrollX();
        boolean slide = "slide".equals(activeStyle);
        for (int i = 0; i < count; i++) {
            View page = getChildAt(i);
            if (page == null) continue;
            page.setTranslationX(0f);
            page.setTranslationY(0f);
            page.setScaleX(1f);
            page.setScaleY(1f);
            page.setRotationX(0f);
            page.setRotationY(0f);
            page.setAlpha(1f);

            float offset = (i * pageWidth - scroll) / (float) pageWidth;
            float abs = Math.abs(offset);
            // Do not draw pages that cannot be on screen. The pager is allowed
            // to overdraw for focus rings, so without this every full grid layer
            // was still composited on every swipe frame.
            if (abs > 1.001f) {
                if (page.getVisibility() != View.INVISIBLE) page.setVisibility(View.INVISIBLE);
                continue;
            } else if (page.getVisibility() != View.VISIBLE) {
                page.setVisibility(View.VISIBLE);
            }
            if (cardMode || cardVisual > 0.001f) {
                // The outgoing card dips and fades while the incoming card grows
                // into place. Alpha reaches zero exactly at one page away, so the
                // incoming card cannot pop into view at the start of the slide.
                float depth = Math.min(0.045f, abs * 0.06f);
                float visualScale = cardScale() * (1f - depth);
                page.setScaleX(visualScale);
                page.setScaleY(visualScale);
                page.setTranslationY(Ui.dpf(getContext(), 4) * cardVisual
                        + abs * density * 5f);
                page.setAlpha(Math.max(0f, 1f - abs * abs));
            } else if (peekVisual) {
                page.setScaleX(CARD_SCALE);
                page.setScaleY(CARD_SCALE);
                page.setTranslationY(Ui.dpf(getContext(), 4));
            }
            // Pages outside the viewport must be fully invisible. Without this, styles
            // that translate/rotate a page can leave the previous page's icons drawn
            // over the current page after the transition has finished.
            if (cardMode ? abs >= 0.999f : abs >= 0.95f) {
                page.setAlpha(0f);
                continue;
            }
            if (slide) continue;

            switch (activeStyle) {
                case "fade":
                    page.setAlpha(Math.max(0f, 1f - abs * 1.55f));
                    page.setScaleX(1f - abs * 0.08f);
                    page.setScaleY(1f - abs * 0.08f);
                    break;
                case "zoom": {
                    float s = 1f - Math.min(0.38f, abs * 0.38f);
                    page.setScaleX(s);
                    page.setScaleY(s);
                    page.setAlpha(Math.max(0f, 1f - abs * 0.75f));
                    break;
                }
                case "cube":
                    page.setCameraDistance(density * 8000f);
                    page.setRotationY(-offset * 95f);
                    page.setAlpha(Math.max(0f, 1f - abs * 0.45f));
                    break;
                case "stack": {
                    float s = 1f - Math.min(0.34f, abs * 0.34f);
                    page.setScaleX(s);
                    page.setScaleY(s);
                    page.setTranslationY(abs * density * 55f);
                    page.setAlpha(Math.max(0f, 1f - abs * 0.65f));
                    break;
                }
                case "parallax":
                    // Move the pages apart instead of through each other, so the
                    // outgoing page can never bleed its icons across the incoming page.
                    page.setTranslationX(offset * pageWidth * 0.38f);
                    page.setAlpha(Math.max(0.20f, 1f - abs * 0.50f));
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        pageWidth = Math.max(1, w);
        setMeasuredDimension(w, h);
        int childW = MeasureSpec.makeMeasureSpec(pageWidth, MeasureSpec.EXACTLY);
        int childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != null) child.measure(childW, childH);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        pageWidth = Math.max(1, w);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c != null) c.layout(i * pageWidth, 0, (i + 1) * pageWidth, h);
        }
        if (dragAnimator != null && dragAnimator.isRunning()) return;
        if (!scroller.computeScrollOffset()) {
            int p = currentPage();
            scrollTo(p * pageWidth, 0);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            notifyPage();
            postInvalidateOnAnimation();
        }
    }

    private void notifyPage() {
        int page = currentPage();
        int count = getChildCount();
        if (page == notifiedPage && count == notifiedCount) return;
        notifiedPage = page;
        notifiedCount = count;
        if (listener != null) listener.onPageChanged(page, count);
    }

    /** Abort/forbid horizontal paging for the current gesture after a vertical intent. */
    void cancelHorizontalForVertical() {
        cancelHorizontalForVertical(-1);
    }

    /**
     * Restore the page that was visible when the gesture began. The caller supplies
     * this because the pager's own gesture-start value can be stale when a vertical
     * drawer swipe begins immediately after a page fling.
     */
    void cancelHorizontalForVertical(int restorePage) {
        if (getChildCount() <= 1 || pageWidth <= 0) return;
        if (!verticalSuppress) {
            verticalSuppress = true;
            if (!scroller.isFinished()) scroller.abortAnimation();
            if (dragAnimator != null && dragAnimator.isRunning()) {
                ValueAnimator old = dragAnimator;
                dragAnimator = null;
                old.cancel();
            }
            int page = restorePage >= 0 ? restorePage : gestureStartPage;
            page = Math.max(0, Math.min(getChildCount() - 1,
                    page >= 0 ? page : currentPage()));
            scrollTo(page * pageWidth, 0);
            notifyPage();
            invalidate();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (locked) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                axisLock = 0;
                verticalSuppress = false;
                gestureStartPage = currentPage();
                // A new touch always stops a settling page animation. Otherwise
                // a down-swipe could make the previous page fling continue.
                scroller.abortAnimation();
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                if (axisLock == 0) {
                    float density = getResources().getDisplayMetrics().density;
                    float verticalSlop = Math.max(8f * density, touchSlop * 0.65f);
                    float horizontalSlop = Math.max(20f * density, touchSlop * 1.8f);
                    if (dy > verticalSlop && dy >= dx * 0.65f) {
                        axisLock = 2;
                    } else if (dx > horizontalSlop && dx > dy * 1.80f) {
                        axisLock = 1;
                    }
                }
                if (verticalSuppress || axisLock == 2) return false;
                if (axisLock == 1 && getChildCount() > 1 && dx > touchSlop) {
                    scroller.abortAnimation();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                axisLock = 0;
                verticalSuppress = false;
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (locked) return false;
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                axisLock = 0;
                verticalSuppress = false;
                gestureStartPage = currentPage();
                scroller.abortAnimation();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX();
                float adx = Math.abs(x - downX);
                float ady = Math.abs(e.getY() - downY);
                if (axisLock == 0) {
                    float density = getResources().getDisplayMetrics().density;
                    float verticalSlop = Math.max(8f * density, touchSlop * 0.65f);
                    float horizontalSlop = Math.max(20f * density, touchSlop * 1.8f);
                    if (ady > verticalSlop && ady >= adx * 0.65f) {
                        axisLock = 2;
                    } else if (adx > horizontalSlop && adx > ady * 1.80f) {
                        axisLock = 1;
                    }
                }
                // Empty home space is handled by the Pager itself. Without
                // this guard a diagonal down-swipe was also scrolling sideways.
                if (verticalSuppress || axisLock != 1) return true;
                int dx = Math.round(lastX - x);
                lastX = x;
                int max = Math.max(0, pageWidth * (getChildCount() - 1));
                int nx = getScrollX() + dx;
                if (nx < 0) nx = 0;
                else if (nx > max) nx = max;
                scrollTo(nx, 0);
                notifyPage();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean suppressed = verticalSuppress;
                axisLock = 0;
                verticalSuppress = false;
                if (suppressed) {
                    scrollTo(Math.max(0, Math.min(getChildCount() - 1,
                            gestureStartPage)) * pageWidth, 0);
                    notifyPage();
                    return true;
                }
                float frac = pageWidth <= 0 ? 0
                        : (getScrollX() - currentPage() * pageWidth) / (float) pageWidth;
                int page = currentPage();
                if (vt != null) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float vx = vt.getXVelocity();
                    if (vx < -minFling) page++;
                    else if (vx > minFling) page--;
                    else if (frac > 0.5f) page++;
                    else if (frac < -0.5f) page--;
                    vt.recycle();
                    vt = null;
                }
                setPage(page, true);
                return true;
            }
        }
        return true;
    }
}
