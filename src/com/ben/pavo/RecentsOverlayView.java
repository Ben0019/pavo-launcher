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

/** Large bottom recents panel with task-preview cards and a Clear all action. */
final class RecentsOverlayView extends FrameLayout {
    interface Listener {
        void onDismiss();
    }

    private final Context ctx;
    private final Listener listener;
    private long ignoreBackUntil;
    private boolean empty;

    RecentsOverlayView(Context c, Listener listener) {
        super(c);
        ctx = c;
        this.listener = listener;
        setClipChildren(false);
        setClickable(false);
        setFocusableInTouchMode(true);
        setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), Ui.dp(ctx, 10));
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
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 9),
                Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        panel.setBackground(Ui.rounded(0xF210141A, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        panel.setClickable(true);

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        LinearLayout strip = new LinearLayout(ctx);
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

        List<String> visible = new ArrayList<>();
        View firstCard = null;
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
            List<Apps.Entry> one = new ArrayList<>();
            one.add(e);
            Apps.loadIcons(ctx, one, Ui.dp(ctx, 24));

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
                } else if (wasFocused) {
                    View next = firstCard(parent);
                    if (next != null) next.requestFocus();
                }
            });
            header.addView(close);

            FrameLayout preview = new FrameLayout(ctx);
            File snap = RecentsSnapshotCache.snapshotFor(ctx, pkg);
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

            card.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 110));
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
                    Ui.dp(ctx, 210), ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(ctx, 8);
            strip.addView(card, clp);
            visible.add(pkg);
            if (firstCard == null) firstCard = card;
        }

        empty = visible.isEmpty();
        if (empty) return;

        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView clear = text("Clear all", 11f, 0xFFE8C86A, false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), 0);
        clear.setBackground(Ui.rounded(0x1FFFFFFF, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        clear.setOnClickListener(v -> {
            for (String pkg : visible) RecentApps.remove(ctx, pkg);
            new Thread(() -> {
                for (String pkg : visible) RecentApps.removeSystemTask(pkg);
            }).start();
            dismiss();
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 190), Ui.dp(ctx, 36));
        clearLp.gravity = Gravity.CENTER_HORIZONTAL;
        clearLp.topMargin = Ui.dp(ctx, 8);
        panel.addView(clear, clearLp);

        addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        if (firstCard != null) firstCard.requestFocus();
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
        if (listener != null) listener.onDismiss();
    }
}
