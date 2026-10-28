package com.ben.aurora;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/** Lightweight anchored popup menu (no AndroidX). */
final class Menu {

    static final class Entry {
        final String label;
        final Runnable action;
        boolean danger;
        boolean enabled = true;

        Entry(String label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        Entry danger() {
            danger = true;
            return this;
        }

        Entry disabled() {
            enabled = false;
            return this;
        }
    }

    private Menu() {}

    /** Anchor the menu just below (or above) the given view. Returns the popup handle. */
    static PopupWindow show(Context c, Backdrop backdrop, int accent, View anchor, String title,
                            List<Entry> entries, boolean focusable) {
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        return show(c, backdrop, accent, anchor,
                loc[0] + anchor.getWidth() / 2f,
                loc[1] + anchor.getHeight() / 2f,
                title, entries, focusable);
    }

    /** Same as {@link #show}, but the magic-lamp origin is the actual touch point. */
    static PopupWindow show(Context c, Backdrop backdrop, int accent, View anchor,
                            float touchX, float touchY, String title,
                            List<Entry> entries, boolean focusable) {
        Panel panel = build(c, backdrop, accent, title, entries, focusable);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        if (touchX <= 0f && touchY <= 0f) {
            touchX = loc[0] + anchor.getWidth() / 2f;
            touchY = loc[1] + anchor.getHeight() / 2f;
        }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int x = loc[0] + anchor.getWidth() / 2 - panel.width / 2;
        int y = loc[1] + anchor.getHeight() + Ui.dp(c, 6);
        boolean above = false;
        if (y + panel.height > dm.heightPixels) {
            y = loc[1] - panel.height - Ui.dp(c, 6);
            above = true;
        }
        panel.show(anchor.getRootView(), c, x, y, above, touchX, touchY);
        return panel.popup;
    }

    /** Show the menu with its top-left near the given screen position. */
    static PopupWindow showAt(Context c, Backdrop backdrop, int accent, View parent,
                              int x, int y, String title, List<Entry> entries,
                              boolean focusable) {
        return showAt(c, backdrop, accent, parent, x, y, title, entries, focusable,
                x, y);
    }

    /** Same as {@link #showAt}, with an explicit magic-lamp origin. */
    static PopupWindow showAt(Context c, Backdrop backdrop, int accent, View parent,
                              int x, int y, String title, List<Entry> entries,
                              boolean focusable, float touchX, float touchY) {
        Panel panel = build(c, backdrop, accent, title, entries, focusable);
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int gap = Ui.dp(c, 10);
        // Open just beside the touch point instead of directly under the finger.
        int px = x + gap;
        int py = y + gap;
        boolean above = false;
        if (px + panel.width > dm.widthPixels - gap) {
            px = x - panel.width - gap;
        }
        if (py + panel.height > dm.heightPixels - gap) {
            py = y - panel.height - gap;
            above = true;
        }
        panel.show(parent, c, px, py, above, touchX, touchY);
        return panel.popup;
    }

    private static Panel build(Context c, Backdrop backdrop, int accent, String title,
                               List<Entry> entries, boolean focusable) {
        final GlassPanel card = new GlassPanel(c, backdrop, accent, 0x330B0E14, 18, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0x4D);
        card.setSurfaceAlpha(new Prefs(c).menuTransparency() / 100f);
        card.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        card.setMinimumWidth(Ui.dp(c, 210));
        card.setElevation(Ui.dpf(c, 20));

        // The popup is full-screen with a dim over the *live* screen. Drawing a
        // full-screen snapshot here made frozen copies of the clock and widgets
        // ghost over a video wallpaper; the card still samples the backdrop for
        // its own glass. Outside taps dismiss through the root.
        final FrameLayout root = new FrameLayout(c);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        View dim = new View(c);
        dim.setBackgroundColor(0x33000000);
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final PopupWindow popup = new PopupWindow(root,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                focusable);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(0);
        popup.setClippingEnabled(false);
        popup.setTouchInterceptor(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_OUTSIDE) {
                    Haptics.outside(v);
                }
                return false;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            private boolean downOutside;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    int[] loc = new int[2];
                    card.getLocationOnScreen(loc);
                    downOutside = event.getRawX() < loc[0]
                            || event.getRawX() >= loc[0] + card.getWidth()
                            || event.getRawY() < loc[1]
                            || event.getRawY() >= loc[1] + card.getHeight();
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP && downOutside) {
                    Haptics.outside(v);
                    popup.dismiss();
                }
                return true;
            }
        });

        if (title != null && !title.isEmpty()) {
            TextView t = new LTextView(c);
            t.setText(title);
            t.setTextColor(0x8CFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setLetterSpacing(0.08f);
            t.setPadding(Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 12), Ui.dp(c, 8));
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMinimumWidth(Ui.dp(c, 210));
            card.addView(t);
        }

        for (final Entry e : entries) {
            TextView row = new LTextView(c);
            row.setText(e.label);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            row.setTextColor(!e.enabled ? 0x4DFFFFFF : (e.danger ? 0xFFFF8A80 : 0xFFEEF1F6));
            row.setPadding(Ui.dp(c, 14), Ui.dp(c, 11), Ui.dp(c, 14), Ui.dp(c, 11));
            row.setBackground(Ui.ripple(c, Ui.dpf(c, 12)));
            row.setClickable(true);
            row.setEnabled(e.enabled);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(row);
                    popup.dismiss();
                    if (e.enabled && e.action != null) {
                        new Handler(Looper.getMainLooper()).postDelayed(e.action, 80);
                    }
                }
            });
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return new Panel(popup, root, card,
                card.getMeasuredWidth(), card.getMeasuredHeight());
    }

    private static final class Panel {
        final PopupWindow popup;
        final FrameLayout root;
        final View card;
        final int width;
        final int height;
        private Bitmap lampBitmap;
        private MagicLampView lamp;
        private ValueAnimator lampAnim;

        Panel(PopupWindow popup, FrameLayout root, View card, int width, int height) {
            this.popup = popup;
            this.root = root;
            this.card = card;
            this.width = width;
            this.height = height;
        }

        void show(View parent, Context c, int x, int y, boolean above,
                  float touchX, float touchY) {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            x = Math.max(Ui.dp(c, 10), Math.min(x, dm.widthPixels - width - Ui.dp(c, 10)));
            y = Math.max(Ui.dp(c, 10), Math.min(y, dm.heightPixels - height - Ui.dp(c, 10)));
            final int left = x;
            final int top = y;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
            lp.leftMargin = left;
            lp.topMargin = top;
            card.setLayoutParams(lp);
            card.setVisibility(View.VISIBLE);
            // Animate the real shader-backed card. Snapshotting a card containing
            // a RenderEffect into a software bitmap loses the effect, which made
            // menus look plain until the entry animation ended.
            card.setPivotX(Math.max(0f, Math.min(card.getWidth(), touchX - left)));
            card.setPivotY(Math.max(0f, Math.min(card.getHeight(), touchY - top)));
            card.setAlpha(0f);
            card.setScaleX(0.92f);
            card.setScaleY(0.92f);
            popup.showAtLocation(parent, Gravity.TOP | Gravity.START, 0, 0);

            root.post(new Runnable() {
                @Override
                public void run() {
                    if (!popup.isShowing()) return;
                    // Keep the live shader-backed card and restore the popup's
                    // springy arrival.
                    Motion.giggleIn(card, 0.92f, 340L, 1.35f, 1.1f, true);
                }
            });
        }

        private void startLamp(Context c, int left, int top,
                               float touchX, float touchY) {
            if (!popup.isShowing() || card.getWidth() <= 0 || card.getHeight() <= 0) {
                card.setVisibility(View.VISIBLE);
                return;
            }
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            lampBitmap = Bitmap.createBitmap(card.getWidth(), card.getHeight(),
                    Bitmap.Config.ARGB_8888);
            card.draw(new Canvas(lampBitmap));
            card.setVisibility(View.INVISIBLE);

            final MagicLampView view = new MagicLampView(c);
            view.setElevation(Ui.dpf(c, 64));
            lamp = view;
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            RectF from = new RectF(left, top, left + card.getWidth(),
                    top + card.getHeight());
            float tx = touchX - loc[0];
            float ty = touchY - loc[1];
            float targetW = Ui.dpf(c, 24);
            float targetH = Ui.dpf(c, 24);
            view.setSnapshot(lampBitmap, from, tx, ty, targetW, targetH);
            view.setProgress(1f);

            final ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
            anim.setDuration(310L);
            anim.setInterpolator(new PathInterpolator(0.20f, 0.80f, 0.30f, 1f));
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    view.setProgress((float) a.getAnimatedValue());
                }
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (lampAnim != anim) return;
                    card.setVisibility(View.VISIBLE);
                    if (lamp != null) {
                        root.removeView(lamp);
                        lamp.release();
                        lamp = null;
                    }
                    lampAnim = null;
                    if (lampBitmap != null) {
                        lampBitmap.recycle();
                        lampBitmap = null;
                    }
                    Motion.giggleIn(card, 0.95f, 380L, 1.35f);
                }
            });
            lampAnim = anim;
            anim.start();
        }

        private void cancelLamp() {
            ValueAnimator anim = lampAnim;
            lampAnim = null;
            if (anim != null) anim.cancel();
            if (lamp != null) {
                root.removeView(lamp);
                lamp.release();
                lamp = null;
            }
            if (lampBitmap != null) {
                lampBitmap.recycle();
                lampBitmap = null;
            }
        }
    }
}
