package com.ben.pavo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
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
    private boolean dismissing;
    private LinearLayout sheet;
    private LinearLayout panel;

    RecentsOverlayView(Context c, Listener listener) {
        super(c);
        ctx = c;
        this.listener = listener;
        setClipChildren(false);
        setClickable(false);
        setFocusableInTouchMode(true);
        setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 10), Ui.dp(ctx, 22));
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
        if (sheet != null) {
            Haptics.tick(sheet);
        }
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
        sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);

        View shadow = new View(ctx);
        GradientDrawable shadowBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000});
        shadow.setBackground(shadowBg);
        sheet.addView(shadow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 16)));

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 2),
                Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        panel.setBackground(Ui.rounded(0xF210141A, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        panel.setClickable(true);

        // Grab handle: drag down to dismiss.
        FrameLayout handleArea = new FrameLayout(ctx);
        View handle = new View(ctx);
        handle.setBackground(Ui.rounded(0x66FFFFFF, Ui.dp(ctx, 2)));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 36), Ui.dp(ctx, 4), Gravity.CENTER);
        handleArea.addView(handle, hlp);
        final float[] downY = {0f};
        final boolean[] dragging = {false};
        handleArea.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY[0] = event.getRawY();
                    dragging[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dy = event.getRawY() - downY[0];
                    if (dy > 0f) {
                        dragging[0] = true;
                        sheet.setTranslationY(dy);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (dragging[0]) {
                        if (sheet.getTranslationY() > Ui.dp(ctx, 60)) dismiss();
                        else sheet.animate().translationY(0f).setDuration(150L).start();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    sheet.animate().translationY(0f).setDuration(150L).start();
                    return true;
                default:
                    return false;
            }
        });
        panel.addView(handleArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 18)));

        LinearLayout strip = new LinearLayout(ctx);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_HORIZONTAL);

        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.addView(strip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout scrollBox = new FrameLayout(ctx);
        scrollBox.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View fade = new View(ctx);
        GradientDrawable fadeBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x0010141A, 0xF210141A});
        fade.setBackground(fadeBg);
        fade.setClickable(false);
        fade.setFocusable(false);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 28), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        scrollBox.addView(fade, flp);
        fade.setVisibility(GONE);
        Runnable updateFade = () -> {
            if (scroll.getChildCount() == 0) return;
            int childW = scroll.getChildAt(0).getWidth();
            int max = Math.max(0, childW - scroll.getWidth());
            fade.setVisibility(max > 4 && scroll.getScrollX() < max - 2
                    ? View.VISIBLE : View.GONE);
        };
        scroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> updateFade.run());
        scroll.post(updateFade);

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

            LinearLayout title = new LinearLayout(ctx);
            title.setOrientation(LinearLayout.HORIZONTAL);
            title.setGravity(Gravity.CENTER_VERTICAL);
            ImageView appIcon = new ImageView(ctx);
            appIcon.setImageBitmap(e.icon);
            title.addView(appIcon, new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 20), Ui.dp(ctx, 20)));
            TextView appName = text(e.label == null || e.label.isEmpty() ? e.pkg : e.label,
                    10.5f, 0xF2FFFFFF, false);
            appName.setSingleLine(true);
            appName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = Ui.dp(ctx, 7);
            title.addView(appName, nlp);
            header.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = text("\u2715", 10f, 0xCCFFFFFF, false);
            close.setGravity(Gravity.CENTER);
            close.setFocusable(false);
            close.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 3),
                    Ui.dp(ctx, 6), Ui.dp(ctx, 3));
            close.setOnClickListener(v -> removeCard(card, e.pkg));
            header.addView(close);

            final Apps.Entry entry = e;
            final float[] cardDownY = {0f};
            final boolean[] cardDragging = {false};
            title.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        cardDownY[0] = event.getRawY();
                        cardDragging[0] = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dy = event.getRawY() - cardDownY[0];
                        if (dy < -Ui.dp(ctx, 6)) {
                            cardDragging[0] = true;
                            card.setTranslationY(Math.max(dy, -Ui.dp(ctx, 90)));
                            card.setAlpha(1f - Math.min(0.55f,
                                    -dy / (float) Ui.dp(ctx, 180)));
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (cardDragging[0]) {
                            float dy = event.getRawY() - cardDownY[0];
                            if (dy < -Ui.dp(ctx, 45)) {
                                Haptics.tick(card);
                                card.animate().translationY(-Ui.dp(ctx, 130))
                                        .alpha(0f).setDuration(120L)
                                        .withEndAction(() -> removeCard(card, entry.pkg))
                                        .start();
                            } else {
                                card.animate().translationY(0f).alpha(1f)
                                        .setDuration(130L).start();
                            }
                        } else {
                            card.performClick();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        card.animate().translationY(0f).alpha(1f)
                                .setDuration(130L).start();
                        return true;
                    default:
                        return false;
                }
            });

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
                Bitmap bg = fallbackPreview(e.icon,
                        Ui.dp(ctx, 198), Ui.dp(ctx, 110));
                preview.setBackground(new BitmapDrawable(ctx.getResources(), bg));
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

            card.setOnClickListener(v -> {
                Haptics.click(card);
                launch(entry);
                dismiss();
            });
            card.setOnFocusChangeListener((v, has) -> card.setBackground(
                    Ui.rounded(0xE814181F, Ui.dp(ctx, 18),
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

        panel.addView(scrollBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView clear = text("Clear all", 11f, 0xFFF2F5F9, false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(Ui.dp(ctx, 20), 0, Ui.dp(ctx, 20), 0);
        clear.setBackground(Ui.rounded(0x1FFFFFFF, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        clear.setOnClickListener(v -> {
            Haptics.tick(clear);
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
        sheet.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        if (firstCard != null) firstCard.requestFocus();
    }

    private void removeCard(View card, String pkg) {
        boolean wasFocused = card.isFocused();
        Haptics.tick(card);
        RecentApps.remove(ctx, pkg);
        new Thread(() -> RecentApps.removeSystemTask(pkg)).start();
        ViewGroup parent = (ViewGroup) card.getParent();
        if (parent != null) parent.removeView(card);
        if (!hasCards(parent)) {
            dismiss();
        } else if (wasFocused) {
            View next = firstCard(parent);
            if (next != null) next.requestFocus();
        }
    }

    private static final String CARD_TAG = "pavo-recent-card";

    private static boolean hasCards(ViewGroup strip) {
        if (strip == null) return false;
        for (int i = 0; i < strip.getChildCount(); i++) {
            if (CARD_TAG.equals(strip.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static View firstCard(ViewGroup strip) {
        if (strip == null) return null;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View v = strip.getChildAt(i);
            if (CARD_TAG.equals(v.getTag())) return v;
        }
        return null;
    }

    private Bitmap fallbackPreview(Bitmap icon, int w, int h) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
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
                hsv[2] = 0.5f;
                base = Color.HSVToColor(hsv);
            }
        }
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, w, h,
                base, 0xFF0C1017, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        if (icon != null && !icon.isRecycled()) {
            Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
            blur.setAlpha(90);
            blur.setMaskFilter(new BlurMaskFilter(Ui.dp(ctx, 10),
                    BlurMaskFilter.Blur.NORMAL));
            float scale = Math.max(w / (float) icon.getWidth(),
                    h / (float) icon.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - icon.getWidth() * scale) / 2f,
                    (h - icon.getHeight() * scale) / 2f);
            c.drawBitmap(icon, m, blur);
        }
        Paint scrim = new Paint();
        scrim.setColor(0x66000000);
        c.drawRect(0, 0, w, h, scrim);
        return out;
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
        Haptics.tick(sheet);
        if (listener != null) listener.onDismiss();
    }
}
