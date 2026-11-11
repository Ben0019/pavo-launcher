package com.ben.pavo;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bottom recents sheet: task-preview cards, a swipe-down clear handle, and
 * controller navigation with a focus ring. A opens the focused app (or clears
 * everything from the handle), Y closes the focused app, and a long press on a
 * card or on A locks/unlocks it.
 */
final class RecentsOverlayView extends FrameLayout {
    interface Listener {
        void onDismiss();
    }

    private static final String CARD_TAG = "pavo-recent-card";
    private static final String LOCK_TAG = "pavo-recent-lock";
    private static final String CLOSE_TAG = "pavo-recent-close";
    private static final float STICK_THRESHOLD = 0.60f;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> cards = new ArrayList<>();
    private final Map<View, String> cardPkg = new HashMap<>();

    private long ignoreBackUntil;
    private boolean empty;
    private boolean dismissing;
    private LinearLayout panel;
    private LinearLayout strip;
    private HorizontalScrollView hScroll;
    private View handleRow;
    private FocusRingDrawable ring;
    private ValueAnimator ringAnim;
    private View focusedView;
    private Runnable aHold;
    private boolean aLongFired;
    private long dPadNext;
    private long analogBlockedUntil;
    private int hatXDir;
    private int hatYDir;
    private int stickXDir;
    private int stickYDir;
    private Runnable repeatRun;
    private int repeatDx;
    private int repeatDy;
    private int repeatCount;

    RecentsOverlayView(Context c, Listener listener) {
        super(c);
        ctx = c;
        this.listener = listener;
        setClipChildren(false);
        setClickable(true);
        setFocusableInTouchMode(true);
        setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), Ui.dp(ctx, 10));
        setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && isOutsideSheet(event)) {
                dismiss();
                return true;
            }
            return false;
        });
        build();
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> positionRing());
    }

    void setIgnoreBackUntil(long timeMs) {
        ignoreBackUntil = timeMs;
    }

    boolean isEmpty() {
        return empty;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
        post(this::positionRing);
    }

    // ------------------------------------------------------------------
    // Controller input
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        long now = SystemClock.uptimeMillis();

        // The left stick also emits synthetic DPAD keys on this controller.
        // Let the analog path be the only one that moves the ring.
        if (ControllerNav.isSyntheticStickDpad(event)) return true;

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (!down && SystemClock.uptimeMillis() >= ignoreBackUntil) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME) {
            if (!down) dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down && (event.getRepeatCount() == 0 || now >= dPadNext)) {
                dPadNext = now + 160L;
                ControllerNav.noteExternalDpad(event);
                moveCard(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (down && event.getRepeatCount() == 0) {
                ControllerNav.noteExternalDpad(event);
                focusHandle();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && event.getRepeatCount() == 0) {
                ControllerNav.noteExternalDpad(event);
                focusCard(0);
            }
            return true;
        }
        if (isSelectKey(code)) {
            if (down) {
                if (event.getRepeatCount() == 0) beginAHold();
                return true;
            }
            cancelAHold();
            if (!aLongFired) activate();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y) {
            if (down && event.getRepeatCount() == 0) removeFocused();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_MOVE
                || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0) {
            return super.dispatchGenericMotionEvent(event);
        }
        long now = SystemClock.uptimeMillis();
        // The Odin reports its D-pad as hat axes, so the hat is the digital
        // path and AXIS_X/Y is the analog path. Never let both move the ring.
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        int hx = hatX > 0.5f ? 1 : (hatX < -0.5f ? -1 : 0);
        int hy = hatY > 0.5f ? 1 : (hatY < -0.5f ? -1 : 0);
        if (hx != 0 || hy != 0) {
            analogBlockedUntil = now + 260L;
            if (hx != hatXDir || hy != hatYDir) {
                hatXDir = hx;
                hatYDir = hy;
                step(hx, hy);
            }
            return true;
        }
        if (hatXDir != 0 || hatYDir != 0) {
            hatXDir = 0;
            hatYDir = 0;
            cancelRepeat();
            return true;
        }
        if (now < analogBlockedUntil || ControllerNav.externalDpadRecent()) return true;
        int dx = nextDirection(stickXDir, event.getAxisValue(MotionEvent.AXIS_X));
        int dy = nextDirection(stickYDir, event.getAxisValue(MotionEvent.AXIS_Y));
        if (dx == 0 && dy == 0) {
            if (stickXDir != 0 || stickYDir != 0) {
                stickXDir = 0;
                stickYDir = 0;
                cancelRepeat();
            }
            return true;
        }
        if (dx != stickXDir || dy != stickYDir) {
            stickXDir = dx;
            stickYDir = dy;
            step(dx, dy);
        }
        return true;
    }

    /** Move once now and keep sweeping while the direction is held. */
    private void step(int dx, int dy) {
        if (dx != 0) moveCard(dx);
        else if (dy != 0) moveVertical(dy);
        startRepeat(dx, dy);
    }

    private void startRepeat(final int dx, final int dy) {
        cancelRepeat();
        repeatDx = dx;
        repeatDy = dy;
        repeatCount = 0;
        scheduleRepeat();
    }

    private void scheduleRepeat() {
        repeatRun = () -> {
            repeatRun = null;
            if (!isAttachedToWindow() || (repeatDx == 0 && repeatDy == 0)) return;
            if (repeatDx != 0) moveCard(repeatDx);
            else moveVertical(repeatDy);
            scheduleRepeat();
        };
        main.postDelayed(repeatRun, repeatDelay(repeatCount++));
    }

    private void cancelRepeat() {
        if (repeatRun != null) {
            main.removeCallbacks(repeatRun);
            repeatRun = null;
        }
        repeatDx = 0;
        repeatDy = 0;
        repeatCount = 0;
    }

    /** A held direction starts slow and then sweeps quickly through the row. */
    private static long repeatDelay(int held) {
        if (held <= 0) return 330L;
        if (held <= 3) return 200L;
        if (held <= 8) return 120L;
        return 75L;
    }

    /** Hysteresis so a stick hovering at the edge does not re-trigger. */
    private static int nextDirection(int current, float value) {
        float on = STICK_THRESHOLD;
        float off = 0.35f;
        if (current == 0) {
            if (value > on) return 1;
            if (value < -on) return -1;
            return 0;
        }
        if (current > 0) {
            if (value < off) return value < -on ? -1 : 0;
            return 1;
        }
        if (value > -off) return value > on ? 1 : 0;
        return -1;
    }

    private void moveVertical(int direction) {
        if (direction < 0) focusHandle();
        else focusCard(0);
    }

    private static boolean isSelectKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void beginAHold() {
        cancelAHold();
        aLongFired = false;
        aHold = () -> {
            aLongFired = true;
            View card = focusedCard();
            if (card != null) toggleLock(card);
        };
        main.postDelayed(aHold, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelAHold() {
        if (aHold != null) {
            main.removeCallbacks(aHold);
            aHold = null;
        }
    }

    private void activate() {
        if (focusedView == handleRow) {
            clearAll();
            return;
        }
        View card = focusedCard();
        if (card != null) card.performClick();
    }

    private View focusedCard() {
        if (focusedView == null || !cards.contains(focusedView)) return null;
        return focusedView;
    }

    private void focusCard(int index) {
        if (cards.isEmpty()) {
            focusHandle();
            return;
        }
        int i = Math.max(0, Math.min(cards.size() - 1, index));
        View card = cards.get(i);
        focusedView = card;
        card.requestFocus();
        ensureVisible(card);
        post(() -> positionRing(true));
    }

    private void moveCard(int direction) {
        if (cards.isEmpty()) return;
        int i = cards.indexOf(focusedView);
        if (i < 0) i = 0;
        else i = Math.max(0, Math.min(cards.size() - 1, i + direction));
        focusCard(i);
    }

    private void focusHandle() {
        if (handleRow == null) return;
        focusedView = handleRow;
        handleRow.requestFocus();
        post(() -> positionRing(true));
    }

    private void ensureVisible(View card) {
        if (hScroll == null || card == null) return;
        int viewWidth = hScroll.getWidth();
        if (viewWidth <= 0) return;
        int scrollX = hScroll.getScrollX();
        if (card.getLeft() < scrollX) {
            hScroll.smoothScrollTo(card.getLeft(), 0);
        } else if (card.getRight() > scrollX + viewWidth) {
            hScroll.smoothScrollTo(card.getRight() - viewWidth, 0);
        }
    }

    private void positionRing() {
        positionRing(false);
    }

    private void positionRing(boolean animate) {
        if (ring == null) return;
        View target = focusedView;
        if (ringAnim != null) {
            ringAnim.cancel();
            ringAnim = null;
        }
        if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
            ring.setBounds(0, 0, 0, 0);
            ring.invalidateSelf();
            return;
        }
        int[] self = new int[2];
        int[] loc = new int[2];
        getLocationOnScreen(self);
        target.getLocationOnScreen(loc);
        int pad = Ui.dp(ctx, 2);
        final int left = loc[0] - self[0] - pad;
        final int top = loc[1] - self[1] - pad;
        final int right = left + target.getWidth() + pad * 2;
        final int bottom = top + target.getHeight() + pad * 2;
        ring.setAccent(ThemeColors.accent());
        ring.setRingRadius(FocusRingView.radiusFor(target, ctx));

        android.graphics.Rect old = new android.graphics.Rect(ring.getBounds());
        if (!animate || old.width() <= 0 || old.height() <= 0) {
            ring.setBounds(left, top, right, bottom);
            ring.invalidateSelf();
            return;
        }
        // Glide between cards when they are already on screen; while the row
        // itself scrolls the ring is pinned to the card instead.
        final int sl = old.left, st = old.top, sr = old.right, sb = old.bottom;
        ringAnim = ValueAnimator.ofFloat(0f, 1f);
        ringAnim.setDuration(130L);
        ringAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        ringAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            ring.setBounds(Math.round(sl + (left - sl) * f),
                    Math.round(st + (top - st) * f),
                    Math.round(sr + (right - sr) * f),
                    Math.round(sb + (bottom - sb) * f));
            ring.invalidateSelf();
        });
        ringAnim.start();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void build() {
        ring = new FocusRingDrawable(ctx, ThemeColors.accent());
        ring.setPlateColor(0x00000000);
        getOverlay().add(ring);

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(ctx, 10), 0,
                Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        panel.setBackground(Ui.rounded(0xF210141A, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        panel.setClickable(true);

        handleRow = new FrameLayout(ctx);
        View handle = new View(ctx);
        handle.setBackground(Ui.rounded(0x66FFFFFF, Ui.dp(ctx, 2)));
        ((FrameLayout) handleRow).addView(handle, new FrameLayout.LayoutParams(
                Ui.dp(ctx, 40), Ui.dp(ctx, 4), Gravity.CENTER));
        // Symmetrical header: the pill sits halfway between the sheet edge
        // and the top of the cards.
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 120), Ui.dp(ctx, 26));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handleRow, handleLp);

        handleRow.setFocusable(true);
        handleRow.setFocusableInTouchMode(true);
        handleRow.setClickable(true);
        handleRow.setOnClickListener(v -> clearAll());
        handleRow.setOnFocusChangeListener((v, has) -> {
            if (has) {
                focusedView = v;
                positionRing();
            }
        });
        final float[] handleDownY = {0f};
        final boolean[] handleDragging = {false};
        handleRow.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handleDownY[0] = event.getRawY();
                    handleDragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - handleDownY[0];
                    if (dy > 0f) {
                        handleDragging[0] = true;
                        panel.setTranslationY(dy);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (handleDragging[0]) {
                        float dy = event.getRawY() - handleDownY[0];
                        if (dy > Ui.dp(ctx, 60)) clearAll();
                        else panel.animate().translationY(0f).setDuration(150L).start();
                    } else {
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    panel.animate().translationY(0f).setDuration(150L).start();
                    return true;
                default:
                    return false;
            }
        });

        hScroll = new HorizontalScrollView(ctx);
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setClipToPadding(false);
        hScroll.setFillViewport(true);
        hScroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> positionRing());
        strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_HORIZONTAL);
        hScroll.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        List<String> recent = RecentApps.list(ctx);
        String foreground = PavoA11yService.focusedPackage();
        if (foreground == null || foreground.isEmpty()) {
            foreground = PavoA11yService.foregroundPackage();
        }
        List<Apps.Entry> entries = new ArrayList<>();
        boolean skippedForeground = false;
        if (foreground != null && !foreground.isEmpty()) {
            List<String> without = new ArrayList<>(recent);
            without.remove(foreground);
            entries = entriesFor(without);
            // Holding Z from an app that is the only recent would otherwise
            // show nothing at all, so fall back to listing it.
            skippedForeground = !entries.isEmpty();
        }
        if (!skippedForeground) entries = entriesFor(recent);
        Apps.loadIcons(ctx, entries, Ui.dp(ctx, 24));

        final int previewH = Ui.dp(ctx, 110);

        for (Apps.Entry e : entries) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6),
                    Ui.dp(ctx, 6), Ui.dp(ctx, 6));
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setTag(CARD_TAG);

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(Ui.dp(ctx, 5), Ui.dp(ctx, 3),
                    Ui.dp(ctx, 3), Ui.dp(ctx, 3));
            header.setBackground(Ui.rounded(0x14FFFFFF, Ui.dp(ctx, 11)));
            ImageView appIcon = new ImageView(ctx);
            appIcon.setImageBitmap(e.icon);
            header.addView(appIcon, new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 20), Ui.dp(ctx, 20)));
            TextView appName = text(e.label == null || e.label.isEmpty() ? e.pkg : e.label,
                    10.5f, 0xF2FFFFFF, false);
            appName.setSingleLine(true);
            appName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = Ui.dp(ctx, 7);
            header.addView(appName, nlp);

            LockGlyph lock = new LockGlyph(ctx);
            lock.setTag(LOCK_TAG);
            LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 13), Ui.dp(ctx, 13));
            lockLp.rightMargin = Ui.dp(ctx, 6);
            lock.setVisibility(RecentApps.isLocked(ctx, e.pkg) ? VISIBLE : GONE);
            header.addView(lock, lockLp);

            TextView close = text("\u2715", 10f, 0xCCFFFFFF, false);
            close.setGravity(Gravity.CENTER);
            close.setFocusable(false);
            close.setTag(CLOSE_TAG);
            close.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 3),
                    Ui.dp(ctx, 6), Ui.dp(ctx, 3));
            close.setOnClickListener(v -> {
                if (RecentApps.isLocked(ctx, e.pkg)) {
                    Haptics.longPress(card);
                    return;
                }
                removeCard(card);
            });
            header.addView(close);

            FrameLayout preview = new FrameLayout(ctx);
            File snap = RecentsSnapshotCache.snapshotFor(ctx, e.pkg);
            Bitmap previewBmp = null;
            if (snap != null) {
                try {
                    previewBmp = BitmapFactory.decodeFile(snap.getAbsolutePath());
                } catch (Throwable ignored) {
                }
            }
            if (previewBmp != null) {
                preview.setBackgroundColor(0xFF0C1017);
                ImageView image = new ImageView(ctx);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageBitmap(previewBmp);
                preview.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                preview.setClipToOutline(true);
                preview.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                                Ui.dp(ctx, 12));
                    }
                });
            } else {
                preview.setBackground(fallbackBackground(e.icon));
                ImageView icon = new ImageView(ctx);
                icon.setImageBitmap(e.icon);
                preview.addView(icon, new FrameLayout.LayoutParams(
                        Ui.dp(ctx, 46), Ui.dp(ctx, 46), Gravity.CENTER));
            }

            final int cardW = cardWidthFor(previewBmp);
            card.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, previewH);
            plp.topMargin = Ui.dp(ctx, 7);
            card.addView(preview, plp);

            final Apps.Entry entry = e;
            card.setOnClickListener(v -> {
                launch(entry);
                dismiss();
            });
            card.setOnLongClickListener(v -> {
                toggleLock(card);
                return true;
            });
            card.setOnFocusChangeListener((v, has) -> {
                applyCardLook(v);
                if (has) {
                    focusedView = v;
                    ensureVisible(v);
                    positionRing();
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(ctx, 8);
            strip.addView(card, clp);
            cards.add(card);
            cardPkg.put(card, e.pkg);
            applyCardLook(card);
            normalizeSpacing();
        }

        empty = cards.isEmpty();
        if (empty) return;

        panel.addView(hScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The sheet hugs its cards; each card keeps its own size.
        addView(panel, new FrameLayout.LayoutParams(
                panelWidth(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        focusCard(0);
    }

    private void applyCardLook(View card) {
        String pkg = cardPkg.get(card);
        boolean locked = pkg != null && RecentApps.isLocked(ctx, pkg);
        boolean focused = card.isFocused();
        int border = focused ? 0x99FFFFFF : (locked ? 0x66E8C86A : 0x33FFFFFF);
        card.setBackground(Ui.rounded(0xE814181F, Ui.dp(ctx, 20),
                border, Ui.dp(ctx, 1)));
        View close = card.findViewWithTag(CLOSE_TAG);
        if (close != null) close.setAlpha(locked ? 0.35f : 1f);
        View lock = card.findViewWithTag(LOCK_TAG);
        if (lock != null) lock.setVisibility(locked ? VISIBLE : GONE);
    }

    private void toggleLock(View card) {
        String pkg = cardPkg.get(card);
        if (pkg == null) return;
        boolean locked = !RecentApps.isLocked(ctx, pkg);
        RecentApps.setLocked(ctx, pkg, locked);
        Haptics.longPress(card);
        applyCardLook(card);
        positionRing();
    }

    private void removeFocused() {
        View card = focusedCard();
        if (card == null) return;
        String pkg = cardPkg.get(card);
        if (pkg != null && RecentApps.isLocked(ctx, pkg)) {
            Haptics.longPress(card);
            return;
        }
        removeCard(card);
    }

    /** Remove one card and keep the sheet around whatever is left. */
    private void removeCard(View card) {
        String pkg = cardPkg.get(card);
        if (pkg == null) return;
        boolean wasFocused = card.isFocused() || focusedView == card;
        RecentApps.remove(ctx, pkg);
        new Thread(() -> RecentApps.removeSystemTask(pkg)).start();
        Haptics.tick(card);
        strip.removeView(card);
        cards.remove(card);
        cardPkg.remove(card);
        if (cards.isEmpty()) {
            dismiss();
            return;
        }
        normalizeSpacing();
        resizePanel();
        if (wasFocused) focusCard(0);
        else post(this::positionRing);
    }

    /** Clear every unlocked app; locked cards stay put. */
    private void clearAll() {
        if (dismissing) return;
        List<View> removable = new ArrayList<>();
        for (View card : cards) {
            String pkg = cardPkg.get(card);
            if (pkg != null && !RecentApps.isLocked(ctx, pkg)) removable.add(card);
        }
        if (removable.isEmpty()) {
            Haptics.longPress(panel);
            panel.animate().translationY(0f).setDuration(150L).start();
            return;
        }
        if (removable.size() < cards.size()) {
            // Locked apps stay; drop the rest in place.
            for (View card : new ArrayList<>(removable)) {
                String pkg = cardPkg.get(card);
                if (pkg == null) continue;
                RecentApps.remove(ctx, pkg);
                new Thread(() -> RecentApps.removeSystemTask(pkg)).start();
                strip.removeView(card);
                cards.remove(card);
                cardPkg.remove(card);
            }
            Haptics.tick(panel);
            normalizeSpacing();
            resizePanel();
            panel.animate().translationY(0f).setDuration(150L).start();
            focusCard(0);
            return;
        }
        animateClear(removable);
    }

    private void animateClear(final List<View> removed) {
        dismissing = true;
        Haptics.tick(panel);
        final List<String> pkgs = new ArrayList<>();
        for (View card : removed) {
            String pkg = cardPkg.get(card);
            if (pkg != null) pkgs.add(pkg);
        }
        float target = Math.max(panel.getHeight(), Ui.dp(ctx, 120)) + Ui.dp(ctx, 30);
        panel.animate().translationY(target).alpha(0f).setDuration(180L)
                .withEndAction(() -> {
                    for (String pkg : pkgs) RecentApps.remove(ctx, pkg);
                    new Thread(() -> {
                        for (String pkg : pkgs) RecentApps.removeSystemTask(pkg);
                    }).start();
                    if (listener != null) listener.onDismiss();
                }).start();
    }

    /** Resolve stored packages to launchable app entries. */
    private List<Apps.Entry> entriesFor(List<String> pkgs) {
        List<Apps.Entry> list = new ArrayList<>();
        for (String pkg : pkgs) {
            String cls = null;
            try {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null && launch.getComponent() != null) {
                    cls = launch.getComponent().getClassName();
                }
            } catch (Throwable ignored) {
            }
            if (cls == null) continue;
            Apps.Entry e = Apps.entryFor(ctx, pkg, cls);
            if (e == null) continue;
            list.add(e);
        }
        return list;
    }

    /** True when a touch lands on the empty area around the sheet. */
    private boolean isOutsideSheet(MotionEvent event) {
        if (panel == null || panel.getWidth() == 0) return false;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        float x = event.getRawX();
        float y = event.getRawY();
        return x < loc[0] || x > loc[0] + panel.getWidth()
                || y < loc[1] || y > loc[1] + panel.getHeight();
    }

    /** Only the gaps between cards, never a trailing one, so both sides match. */
    private void normalizeSpacing() {
        if (strip == null) return;
        int gap = Ui.dp(ctx, 8);
        int count = cards.size();
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) cards.get(i).getLayoutParams();
            if (lp == null) continue;
            lp.rightMargin = (i + 1 < count) ? gap : 0;
            cards.get(i).setLayoutParams(lp);
        }
    }

    /** Sheet width for the current row of cards, capped to the screen. */
    private int panelWidth() {
        int row = 0;
        for (View card : cards) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp == null) continue;
            row += lp.width + ((LinearLayout.LayoutParams) lp).rightMargin;
        }
        int avail = getResources().getDisplayMetrics().widthPixels - Ui.dp(ctx, 20);
        return Math.max(Ui.dp(ctx, 150),
                Math.min(row + Ui.dp(ctx, 20), avail));
    }

    /** Keep the sheet wrapped around the cards after one is removed. */
    private void resizePanel() {
        normalizeSpacing();
        if (panel == null) return;
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp == null) return;
        lp.width = panelWidth();
        panel.setLayoutParams(lp);
    }

    /**
     * Card width follows the app window's own shape, so the preview is shown at
     * its real proportions instead of being stretched wide.
     */
    private int cardWidthFor(Bitmap preview) {
        float aspect = 16f / 9f;
        if (preview != null && !preview.isRecycled()
                && preview.getWidth() > 0 && preview.getHeight() > 0) {
            aspect = preview.getWidth() / (float) preview.getHeight();
        }
        aspect = Math.max(0.62f, Math.min(2.35f, aspect));
        int w = Math.round(Ui.dp(ctx, 110) * aspect) + Ui.dp(ctx, 12);
        return Math.max(Ui.dp(ctx, 130), Math.min(Ui.dp(ctx, 340), w));
    }

    private GradientDrawable fallbackBackground(Bitmap icon) {
        int base = 0xFF2A3340;
        if (icon != null && !icon.isRecycled()) {
            long r = 0, g = 0, b = 0, n = 0;
            int step = Math.max(1, Math.min(icon.getWidth(), icon.getHeight()) / 10);
            for (int y = 0; y < icon.getHeight(); y += step) {
                for (int x = 0; x < icon.getWidth(); x += step) {
                    int p = icon.getPixel(x, y);
                    if (Color.alpha(p) < 80) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    b += Color.blue(p);
                    n++;
                }
            }
            if (n > 0) {
                float[] hsv = new float[3];
                Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
                hsv[1] = Math.max(0.35f, Math.min(0.85f, hsv[1]));
                hsv[2] = 0.55f;
                base = Color.HSVToColor(hsv);
            }
        }
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xB0000000 | (base & 0x00FFFFFF), 0xF00C1017});
        g.setCornerRadius(Ui.dp(ctx, 14));
        return g;
    }

    private void launch(Apps.Entry entry) {
        Intent intent = null;
        try {
            intent = ctx.getPackageManager().getLaunchIntentForPackage(entry.pkg);
        } catch (Throwable ignored) {
        }
        if (intent == null) {
            intent = new Intent().setComponent(
                    new ComponentName(entry.pkg, entry.activity));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new LTextView(ctx);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private void dismiss() {
        if (dismissing) return;
        dismissing = true;
        cancelAHold();
        cancelRepeat();
        if (listener != null) listener.onDismiss();
    }

    /** Small padlock badge shown on locked cards. */
    private static final class LockGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LockGlyph(Context c) {
            super(c);
            paint.setColor(0xFFE8C86A);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dpf(c, 1.3f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float bodyW = w * 0.74f;
            float bodyH = h * 0.50f;
            float left = (w - bodyW) / 2f;
            float top = h * 0.46f;
            float r = Ui.dpf(getContext(), 1.4f);
            canvas.drawRoundRect(left, top, left + bodyW, top + bodyH, r, r, paint);
            float shackleR = bodyW * 0.30f;
            canvas.drawArc(left + bodyW / 2f - shackleR, top - shackleR * 1.55f,
                    left + bodyW / 2f + shackleR, top + shackleR * 0.55f,
                    180f, 180f, false, paint);
        }
    }
}
