package com.ben.pavo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Floating recent-apps sheet used over any app through the accessibility layer. */
final class RecentsOverlayView extends FrameLayout {
    interface Listener {
        void onDismiss();
    }

    private final Context ctx;
    private final Listener listener;
    private long ignoreBackUntil;

    RecentsOverlayView(Context c, Listener listener) {
        super(c);
        ctx = c;
        this.listener = listener;
        setBackgroundColor(0x66000000);
        setFocusableInTouchMode(true);
        setClickable(true);
        setOnClickListener(v -> dismiss());
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
        int pad = Ui.dp(ctx, 14);
        FrameLayout wrapper = new FrameLayout(ctx);
        wrapper.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 16),
                Ui.dp(ctx, 18), Ui.dp(ctx, 14));
        card.setBackground(Ui.rounded(0xF210141A, Ui.dp(ctx, 22),
                0x33FFFFFF, Ui.dp(ctx, 1)));
        card.setClickable(true);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("RECENT APPS", 11f, 0xCCFFFFFF, true);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("CLOSE", 10f, 0x99FFFFFF, false);
        close.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6),
                Ui.dp(ctx, 4), Ui.dp(ctx, 6));
        close.setOnClickListener(v -> dismiss());
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        List<String> recent = RecentApps.list(ctx);
        // Do not offer the app that is currently underneath the sheet.
        String foreground = PavoA11yService.focusedPackage();
        if (foreground == null || foreground.isEmpty()) {
            foreground = PavoA11yService.foregroundPackage();
        }
        if (foreground != null && !foreground.isEmpty()) recent.remove(foreground);
        if (recent.isEmpty()) {
            TextView empty = text("No recent apps yet", 13f, 0x88FFFFFF, false);
            empty.setPadding(0, Ui.dp(ctx, 18), 0, Ui.dp(ctx, 18));
            card.addView(empty);
        } else {
            View firstRow = null;
            boolean added = false;
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
                Apps.loadIcons(ctx, one, Ui.dp(ctx, 40));

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 7),
                        Ui.dp(ctx, 8), Ui.dp(ctx, 7));
                row.setBackground(Ui.rounded(0x14FFFFFF, Ui.dp(ctx, 12)));
                row.setFocusable(true);
                row.setTag(ROW_TAG);

                ImageView icon = new ImageView(ctx);
                icon.setImageBitmap(e.icon);
                row.addView(icon, new LinearLayout.LayoutParams(
                        Ui.dp(ctx, 40), Ui.dp(ctx, 40)));

                TextView label = text(e.label == null || e.label.isEmpty() ? e.pkg : e.label,
                        14f, 0xF2FFFFFF, false);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                llp.leftMargin = Ui.dp(ctx, 12);
                row.addView(label, llp);

                TextView x = text("\u2715", 13f, 0x88FFFFFF, false);
                x.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8),
                        Ui.dp(ctx, 8), Ui.dp(ctx, 8));
                x.setFocusable(false);
                x.setOnClickListener(v -> {
                    boolean wasFocused = row.isFocused();
                    RecentApps.remove(ctx, e.pkg);
                    new Thread(() -> RecentApps.removeSystemTask(e.pkg)).start();
                    card.removeView(row);
                    if (!hasRows(card)) {
                        dismiss();
                    } else if (wasFocused) {
                        View next = firstRow(card);
                        if (next != null) next.requestFocus();
                    }
                });
                row.addView(x);

                final Apps.Entry entry = e;
                row.setOnClickListener(v -> {
                    launch(entry);
                    dismiss();
                });
                row.setOnFocusChangeListener((v, has) -> row.setBackground(
                        Ui.rounded(has ? 0x30FFFFFF : 0x14FFFFFF, Ui.dp(ctx, 12),
                                has ? 0x66FFFFFF : 0, Ui.dp(ctx, 1))));
                row.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && (keyCode == KeyEvent.KEYCODE_BUTTON_A
                            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        row.performClick();
                        return true;
                    }
                    return false;
                });
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Ui.dp(ctx, 4);
                card.addView(row, rlp);
                added = true;
                if (firstRow == null) firstRow = row;
            }
            if (!added) {
                TextView empty = text("No recent apps yet", 13f, 0x88FFFFFF, false);
                empty.setPadding(0, Ui.dp(ctx, 18), 0, Ui.dp(ctx, 18));
                card.addView(empty);
            }
            TextView clear = text("Clear all", 11f, 0xFF8FB8FF, false);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(0, Ui.dp(ctx, 12), 0, Ui.dp(ctx, 2));
            clear.setOnClickListener(v -> {
                final List<String> closing = new ArrayList<>(RecentApps.list(ctx));
                RecentApps.clear(ctx);
                new Thread(() -> {
                    for (String pkg : closing) RecentApps.removeSystemTask(pkg);
                }).start();
                dismiss();
            });
            if (added) card.addView(clear);
            if (firstRow != null) firstRow.requestFocus();
        }

        wrapper.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        addView(wrapper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new LTextView(ctx);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private static final String ROW_TAG = "pavo-recent-row";

    private static boolean hasRows(LinearLayout card) {
        for (int i = 0; i < card.getChildCount(); i++) {
            if (ROW_TAG.equals(card.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static View firstRow(LinearLayout card) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            if (ROW_TAG.equals(v.getTag())) return v;
        }
        return null;
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

    private void dismiss() {
        if (listener != null) listener.onDismiss();
    }
}
