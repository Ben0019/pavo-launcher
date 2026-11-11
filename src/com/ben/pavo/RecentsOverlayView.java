package com.ben.pavo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Large bottom recents panel with task-preview cards and a swipe-down clear handle. */
final class RecentsOverlayView extends FrameLayout {
    interface Listener {
        void onDismiss();
    }

    private final Context ctx;
    private final Listener listener;
    private long ignoreBackUntil;
    private boolean empty;
    private boolean dismissing;
    private LinearLayout panel;
    private LinearLayout strip;

    RecentsOverlayView(Context c, Listener listener) {
        super(c);
        ctx = c;
        this.listener = listener;
        setClipChildren(false);
        setClickable(true);
        setFocusableInTouchMode(true);
        setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), Ui.dp(ctx, 10));
        setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && isOutsideSheet(event)) {
                dismiss();
                return true;
            }
            return false;
        });
        build();
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
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && SystemClock.uptimeMillis() >= ignoreBackUntil) {
                dismiss();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP) {
            dismiss();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void build() {
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(ctx, 10), 0,
                Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        panel.setBackground(Ui.rounded(0xF210141A, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        panel.setClickable(true);

        final List<String> visible = new ArrayList<>();

        FrameLayout handleArea = new FrameLayout(ctx);
        View handle = new View(ctx);
        handle.setBackground(Ui.rounded(0x66FFFFFF, Ui.dp(ctx, 2)));
        handleArea.addView(handle, new FrameLayout.LayoutParams(
                Ui.dp(ctx, 40), Ui.dp(ctx, 4), Gravity.CENTER));
        // Symmetrical header: the pill sits halfway between the sheet edge
        // and the top of the cards.
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 120), Ui.dp(ctx, 26));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handleArea, handleLp);
        final float[] handleDownY = {0f};
        final boolean[] handleDragging = {false};
        handleArea.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    handleDownY[0] = event.getRawY();
                    handleDragging[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - handleDownY[0];
                    if (dy > 0f) {
                        handleDragging[0] = true;
                        panel.setTranslationY(dy);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (handleDragging[0]) {
                        float dy = event.getRawY() - handleDownY[0];
                        if (dy > Ui.dp(ctx, 60)) animateClear(visible);
                        else panel.animate().translationY(0f).setDuration(150L).start();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    panel.animate().translationY(0f).setDuration(150L).start();
                    return true;
                default:
                    return false;
            }
        });

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        List<String> recent = RecentApps.list(ctx);
        String foreground = PavoA11yService.focusedPackage();
        if (foreground == null || foreground.isEmpty()) {
            foreground = PavoA11yService.foregroundPackage();
        }
        if (foreground != null && !foreground.isEmpty()) recent.remove(foreground);

        List<Apps.Entry> entries = new ArrayList<>();
        for (String pkg : recent) {
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
            entries.add(e);
        }
        Apps.loadIcons(ctx, entries, Ui.dp(ctx, 24));

        final int previewH = Ui.dp(ctx, 110);

        View firstCard = null;
        for (Apps.Entry e : entries) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6),
                    Ui.dp(ctx, 6), Ui.dp(ctx, 6));
            card.setBackground(Ui.rounded(0xE814181F, Ui.dp(ctx, 18),
                    0x33FFFFFF, Ui.dp(ctx, 1)));
            card.setFocusable(true);
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
            TextView close = text("\u2715", 10f, 0xCCFFFFFF, false);
            close.setGravity(Gravity.CENTER);
            close.setFocusable(false);
            close.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 3),
                    Ui.dp(ctx, 6), Ui.dp(ctx, 3));
            close.setOnClickListener(v -> {
                boolean wasFocused = card.isFocused();
                RecentApps.remove(ctx, e.pkg);
                new Thread(() -> RecentApps.removeSystemTask(e.pkg)).start();
                ViewGroup parent = (ViewGroup) card.getParent();
                parent.removeView(card);
                if (!hasCards(parent)) {
                    dismiss();
                } else {
                    resizePanel();
                    if (wasFocused) {
                        View next = firstCard(parent);
                        if (next != null) next.requestFocus();
                    }
                }
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
            card.setOnFocusChangeListener((v, has) -> card.setBackground(
                    Ui.rounded(0xE814181F, Ui.dp(ctx, 20),
                            has ? 0x99FFFFFF : 0x33FFFFFF, Ui.dp(ctx, 1))));
            card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_BUTTON_A
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    card.performClick();
                    return true;
                }
                return false;
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(ctx, 8);
            strip.addView(card, clp);
            normalizeSpacing();
            visible.add(e.pkg);
            if (firstCard == null) firstCard = card;
        }

        empty = visible.isEmpty();
        if (empty) return;

        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // The sheet hugs its cards; each card keeps its own size.
        addView(panel, new FrameLayout.LayoutParams(
                panelWidth(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        if (firstCard != null) firstCard.requestFocus();
    }

    /** True when a touch lands on the empty area around the sheet. */
    private boolean isOutsideSheet(android.view.MotionEvent event) {
        if (panel == null || panel.getWidth() == 0) return false;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        float x = event.getRawX();
        float y = event.getRawY();
        return x < loc[0] || x > loc[0] + panel.getWidth()
                || y < loc[1] || y > loc[1] + panel.getHeight();
    }

    private static final String CARD_TAG = "pavo-recent-card";

    private static boolean hasCards(ViewGroup strip) {
        for (int i = 0; i < strip.getChildCount(); i++) {
            if (CARD_TAG.equals(strip.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static View firstCard(ViewGroup strip) {
        for (int i = 0; i < strip.getChildCount(); i++) {
            View v = strip.getChildAt(i);
            if (CARD_TAG.equals(v.getTag())) return v;
        }
        return null;
    }

    /** Only the gaps between cards, never a trailing one, so both sides match. */
    private void normalizeSpacing() {
        if (strip == null) return;
        int gap = Ui.dp(ctx, 8);
        int cards = 0;
        for (int i = 0; i < strip.getChildCount(); i++) {
            if (CARD_TAG.equals(strip.getChildAt(i).getTag())) cards++;
        }
        int seen = 0;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View card = strip.getChildAt(i);
            if (!CARD_TAG.equals(card.getTag())) continue;
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) card.getLayoutParams();
            if (lp == null) continue;
            lp.rightMargin = (++seen < cards) ? gap : 0;
            card.setLayoutParams(lp);
        }
    }

    /** Sheet width for the current row of cards, capped to the screen. */
    private int panelWidth() {
        int row = 0;
        if (strip != null) {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View card = strip.getChildAt(i);
                ViewGroup.LayoutParams lp = card.getLayoutParams();
                if (lp == null) continue;
                row += lp.width + ((LinearLayout.LayoutParams) lp).rightMargin;
            }
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

    private void animateClear(final List<String> visible) {
        if (dismissing) return;
        dismissing = true;
        Haptics.tick(panel);
        float target = Math.max(panel.getHeight(), Ui.dp(ctx, 120)) + Ui.dp(ctx, 30);
        panel.animate().translationY(target).alpha(0f).setDuration(180L)
                .withEndAction(() -> {
                    for (String pkg : visible) RecentApps.remove(ctx, pkg);
                    new Thread(() -> {
                        for (String pkg : visible) RecentApps.removeSystemTask(pkg);
                    }).start();
                    if (listener != null) listener.onDismiss();
                }).start();
    }

    private void dismiss() {
        if (dismissing) return;
        dismissing = true;
        if (listener != null) listener.onDismiss();
    }
}
