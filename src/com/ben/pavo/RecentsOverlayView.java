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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Thin floating dock of recent apps, shown over any app by the accessibility layer. */
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
        build();
    }

    void setIgnoreBackUntil(long timeMs) {
        ignoreBackUntil = timeMs;
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
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6),
                Ui.dp(ctx, 8), Ui.dp(ctx, 5));
        bar.setBackground(Ui.rounded(0xE80C1017, Ui.dp(ctx, 24),
                0x44FFFFFF, Ui.dp(ctx, 1)));

        List<String> recent = RecentApps.list(ctx);
        String foreground = PavoA11yService.focusedPackage();
        if (foreground == null || foreground.isEmpty()) {
            foreground = PavoA11yService.foregroundPackage();
        }
        if (foreground != null && !foreground.isEmpty()) recent.remove(foreground);

        boolean added = false;
        View firstTile = null;
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
            Apps.loadIcons(ctx, one, Ui.dp(ctx, 52));

            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setFocusable(true);
            cell.setTag(TILE_TAG);

            FrameLayout thumb = new FrameLayout(ctx);
            File snap = RecentsSnapshotCache.snapshotFor(ctx, pkg);
            Bitmap previewBmp = null;
            if (snap != null) {
                try {
                    previewBmp = BitmapFactory.decodeFile(snap.getAbsolutePath());
                } catch (Throwable ignored) {
                }
            }
            if (previewBmp != null) {
                thumb.setBackgroundColor(0xFF10141A);
                ImageView preview = new ImageView(ctx);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.setImageBitmap(previewBmp);
                thumb.addView(preview, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                thumb.setClipToOutline(true);
                thumb.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                                Ui.dp(ctx, 10));
                    }
                });
            } else {
                thumb.setBackground(fallbackBackground(e.icon));
                ImageView icon = new ImageView(ctx);
                icon.setImageBitmap(e.icon);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        Ui.dp(ctx, 52), Ui.dp(ctx, 52), Gravity.CENTER);
                thumb.addView(icon, ilp);
            }

            TextView x = text("\u2715", 11f, 0xAAFFFFFF, false);
            x.setGravity(Gravity.CENTER);
            x.setBackground(Ui.rounded(0x66000000, Ui.dp(ctx, 10)));
            x.setFocusable(false);
            FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(
                    Ui.dp(ctx, 20), Ui.dp(ctx, 20), Gravity.TOP | Gravity.END);
            xlp.topMargin = Ui.dp(ctx, 2);
            xlp.rightMargin = Ui.dp(ctx, 2);
            thumb.addView(x, xlp);
            x.setOnClickListener(v -> {
                boolean wasFocused = cell.isFocused();
                RecentApps.remove(ctx, e.pkg);
                new Thread(() -> RecentApps.removeSystemTask(e.pkg)).start();
                ViewGroup parent = (ViewGroup) cell.getParent();
                parent.removeView(cell);
                if (!hasTiles(parent)) {
                    dismiss();
                } else if (wasFocused) {
                    View next = firstTile(parent);
                    if (next != null) next.requestFocus();
                }
            });

            TextView name = text(e.label == null || e.label.isEmpty() ? e.pkg : e.label,
                    10.5f, 0xE6FFFFFF, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);

            cell.addView(thumb, new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 68), Ui.dp(ctx, 68)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = Ui.dp(ctx, 2);
            cell.addView(name, nlp);

            final Apps.Entry entry = e;
            cell.setOnClickListener(v -> {
                launch(entry);
                dismiss();
            });
            cell.setOnFocusChangeListener((v, has) -> thumb.setAlpha(has ? 1f : 0.92f));
            cell.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_BUTTON_A
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    cell.performClick();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 76), ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(ctx, 6);
            bar.addView(cell, clp);
            added = true;
            if (firstTile == null) firstTile = cell;
        }

        empty = !added;
        addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        if (firstTile != null) firstTile.requestFocus();
    }

    boolean isEmpty() {
        return empty;
    }

    private static final String TILE_TAG = "pavo-recent-tile";

    private static boolean hasTiles(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            if (TILE_TAG.equals(bar.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static View firstTile(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View v = bar.getChildAt(i);
            if (TILE_TAG.equals(v.getTag())) return v;
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
                new int[]{0xB0000000 | (base & 0x00FFFFFF), 0xF010141A});
        g.setCornerRadius(Ui.dp(ctx, 10));
        g.setStroke(Ui.dp(ctx, 1), 0x44FFFFFF);
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
