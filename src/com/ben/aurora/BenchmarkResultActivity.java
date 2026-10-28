package com.ben.aurora;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Result page for the built-in Stress Test app, with a rolling run history. */
public final class BenchmarkResultActivity extends AuroraGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;

    private Prefs prefs;
    private final List<BenchmarkResult> results = new ArrayList<>();
    private int selectedIndex;

    private boolean incomingDetail;
    private LinearLayout content;
    private TextView subtitle;
    private TextView closeButton;
    private ControllerNav nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        loadHistory();
        buildUi();
        nav = new ControllerNav(this, getWindow().getDecorView());
        getWindow().getDecorView().post(new Runnable() {
            @Override public void run() { nav.decorateFocusables(); }
        });
        hideBars();
    }

    private void loadHistory() {
        results.addAll(BenchmarkResultStore.load(this));
        BenchmarkResult incoming = BenchmarkResult.fromIntent(getIntent());
        incomingDetail = incoming.timestamp > 0L;
        if (incoming.timestamp > 0L) {
            int existing = -1;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).timestamp == incoming.timestamp) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                results.set(existing, incoming);
                selectedIndex = existing;
            } else {
                results.add(0, incoming);
                selectedIndex = 0;
            }
        }
        if (results.isEmpty()) {
            results.add(incoming);
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, results.size() - 1));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (nav != null && nav.dispatchKeyEvent(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            finishAnimated();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (nav != null && nav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        if (nav != null) nav.cancel();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (nav != null) nav.release();
        super.onDestroy();
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1218);

        WallpaperView wallpaper = new WallpaperView(this);
        wallpaper.setBlurRadius(prefs.blur());
        wallpaper.setMode(prefs.wallpaperMode());
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GlassPanel sheet = new GlassPanel(this, null, ThemeColors.accent(),
                0x800B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        sheet.setPadding(dp(20), dp(11), dp(20), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, lp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        sheet.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, dp(8));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        column.addView(buildHeader(), matchWrap());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content, matchWrap());

        setContentView(root);
        applyWallpaperBackdrop(wallpaper, sheet);
        if (incomingDetail && selectedIndex >= 0 && selectedIndex < results.size()) {
            showDetail(selectedIndex);
        } else {
            showList();
        }
    }

    private void applyWallpaperBackdrop(final WallpaperView wallpaper,
                                        final GlassPanel sheet) {
        wallpaper.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                Bitmap blurred = wallpaper.getBlurred();
                if (blurred == null || blurred.isRecycled()
                        || wallpaper.getWidth() <= 0 || wallpaper.getHeight() <= 0) {
                    wallpaper.postDelayed(this, 180L);
                    return;
                }
                Backdrop backdrop = new Backdrop(blurred,
                        wallpaper.getWidth(), wallpaper.getHeight());
                sheet.setBackdrop(backdrop);
                sheet.setBackdropScreenAnchored(true);
                sheet.setFrosted(true);
                sheet.invalidate();
            }
        }, 220L);
    }

    private void showList() {
        if (content == null) return;
        content.removeAllViews();
        if (subtitle != null) {
            subtitle.setText(results.size() + " "
                    + tr(results.size() == 1 ? "SAVED RUN" : "SAVED RUNS"));
        }
        if (results.isEmpty()) {
            TextView empty = text("No saved runs yet", 11f, MUTED, true);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, dp(30));
            content.addView(empty, matchWrap());
            focusLater(closeButton);
            return;
        }
        addSection(content, "RESULTS");
        View firstCard = null;
        for (int i = 0; i < results.size(); i++) {
            final int index = i;
            BenchmarkResult r = results.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(13), dp(9), dp(13), dp(9));
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackground(Ui.rounded(0x24101820, dp(13), 0x22FFFFFF, dp(1)));
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showDetail(index); }
            });

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text("#" + (i + 1) + "  " + modeText(r.modeLabel()),
                    10f, TEXT, true);
            top.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView rating = text(r.throttleLabel(), 9f, r.throttleColor(), true);
            rating.setGravity(Gravity.RIGHT);
            top.addView(rating);
            TextView chevron = text("  ›", 13f, MUTED, false);
            top.addView(chevron);
            TextView delete = text("×", 16f, 0xFFFF6B6B, false);
            delete.setGravity(Gravity.CENTER);
            delete.setIncludeFontPadding(false);
            delete.setPadding(dp(6), 0, dp(6), 0);
            delete.setMinWidth(dp(28));
            delete.setMinHeight(dp(28));
            delete.setFocusable(true);
            delete.setClickable(true);
            delete.setId(View.generateViewId());
            card.setId(View.generateViewId());
            card.setNextFocusRightId(delete.getId());
            delete.setNextFocusLeftId(card.getId());
            delete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmDelete(r, index);
                }
            });
            top.addView(delete);
            card.addView(top, matchWrap());

            TextView meta = text(formatDate(r.timestamp) + "  ·  "
                            + String.format(Locale.US, "%.1f s", r.durationMs / 1000f)
                            + "  ·  " + r.threads + "T", 8.5f, MUTED, true);
            meta.setPadding(0, dp(2), 0, 0);
            card.addView(meta);
            TextView stats = text("CPU " + Math.round(r.cpuTempMax) + "°  ·  GPU "
                            + Math.round(r.gpuTempMax) + "°  ·  " + r.throttlePct
                            + "% " + tr("HOLD"), 8.5f, MUTED, true);
            stats.setPadding(0, dp(1), 0, 0);
            card.addView(stats);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            content.addView(card, lp);
            if (firstCard == null) firstCard = card;
        }
        focusLater(firstCard);
        if (nav != null) content.post(new Runnable() {
            @Override public void run() { nav.decorateFocusables(); }
        });
    }

    private void showDetail(int index) {
        if (results.isEmpty()) { showList(); return; }
        selectedIndex = Math.max(0, Math.min(index, results.size() - 1));
        BenchmarkResult result = results.get(selectedIndex);
        if (subtitle != null) {
            subtitle.setText(modeText(result.modeLabel()) + "  ·  "
                    + String.format(Locale.getDefault(), "%.1f s", result.durationMs / 1000f)
                    + "  ·  " + result.threads + " " + tr("THREADS") + "  ·  "
                    + formatDate(result.timestamp));
        }
        if (content == null) return;
        content.removeAllViews();
        View detailFocus = null;
        if (results.size() > 1) {
            TextView all = text("‹  " + tr("ALL RESULTS"), 9f,
                    ThemeColors.accent(), false);
            all.setPadding(dp(2), dp(3), dp(2), dp(5));
            all.setFocusable(true);
            all.setClickable(true);
            all.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showList(); }
            });
            content.addView(all, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            detailFocus = all;
        }

        boolean narrow = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || getResources().getConfiguration().screenWidthDp < 520;
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        cards.setPadding(0, dp(4), 0, dp(2));
        content.addView(cards, matchWrap());
        View throttleCard = scoreCard("THROTTLE SCORE", result.throttleLabel(),
                tr("HELD") + " " + result.primaryHoldSec() + "s  ·  " + tr("SUS")
                        + " " + result.primarySustainedPct() + "%", result.throttleColor());
        View gpuCard = scoreCard("GPU FPS", String.format(Locale.US, "%.0f", result.gpuFps),
                tr("AVG") + String.format(Locale.US, " %.0f", result.avgFps),
                ThemeColors.accent());
        if (narrow) {
            cards.addView(throttleCard, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            second.topMargin = dp(8);
            cards.addView(gpuCard, second);
        } else {
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cards.addView(throttleCard, cardLp);
            LinearLayout.LayoutParams cardLp2 = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cardLp2.leftMargin = dp(8);
            cards.addView(gpuCard, cardLp2);
        }

        BenchmarkStageView graph = new BenchmarkStageView(this);
        graph.configure(result.mode, result.threads);
        graph.setHistory(result.fpsHistory, result.cpuTempHistory,
                result.gpuTempHistory, result.cpuUseHistory, result.gpuUseHistory);
        content.addView(graph, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        addSection(content, "TELEMETRY");
        addRow(content, "CPU TEMP", String.format(Locale.US, "%.0f\u00b0 AVG", result.cpuTempAvg),
                String.format(Locale.US, "%.0f\u00b0 MAX", result.cpuTempMax));
        addRow(content, "GPU TEMP", String.format(Locale.US, "%.0f\u00b0 AVG", result.gpuTempAvg),
                String.format(Locale.US, "%.0f\u00b0 MAX", result.gpuTempMax));
        addRow(content, "CPU LOAD", result.cpuUseAvg + "% AVG", result.cpuUseMax + "% MAX");
        addRow(content, "GPU LOAD", result.gpuUseAvg + "% AVG", result.gpuUseMax + "% MAX");
        addRow(content, "THROTTLE", result.throttleLabel(),
                result.throttlePct + "% " + tr("HOLD"));
        addRow(content, "EVIDENCE", result.evidenceLabel(),
                result.evidencePct() + "% " + tr("TEST LENGTH"));
        addRow(content, "CPU HOLD", result.cpuHeldPct + "%",
                result.cpuHoldSec + "s " + tr("AT CLOCK"));
        addRow(content, "GPU HOLD", result.gpuHeldPct + "%",
                result.gpuHoldSec + "s " + tr("AT CLOCK"));
        addRow(content, "CPU SUSTAINED", result.cpuSustainedPct + "%", "LAST QUARTER");
        addRow(content, "GPU SUSTAINED", result.gpuSustainedPct + "%", "LAST QUARTER");
        addRow(content, "SAMPLES", String.valueOf(result.samples), "LIVE GRAPH");

        addSection(content, "RUN");
        addRow(content, "MODE", modeText(result.modeLabel()),
                result.threads + " " + tr("THREADS"));
        addRow(content, "DURATION", String.format(Locale.US, "%.1f s",
                result.durationMs / 1000f), result.lengthLabel() + " " + tr("SET"));
        addRow(content, "TUNING", result.tuneLabel(this), "PSERVER ONLY");
        addRow(content, "STATUS", result.completed ? "COMPLETE" : "STOPPED",
                formatDate(result.timestamp));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, dp(2));
        content.addView(buttons, matchWrap());
        TextView back = button("BACK TO STRESS TEST", true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishAnimated(); }
        });
        buttons.addView(back, new LinearLayout.LayoutParams(0, dp(38), 1f));
        focusLater(detailFocus == null ? back : detailFocus);
        if (nav != null) content.post(new Runnable() {
            @Override public void run() { nav.decorateFocusables(); }
        });
    }

    private void focusLater(final View target) {
        if (target == null) return;
        target.post(new Runnable() {
            @Override public void run() {
                if (!target.isFocused() && target.isFocusable()) target.requestFocus();
            }
        });
    }

    private void confirmDelete(final BenchmarkResult result, final int index) {
        final Dialog dialog = new Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        Backdrop backdrop;
        try {
            backdrop = Backdrop.capture(getWindow().getDecorView(), 12);
        } catch (Throwable t) {
            backdrop = null;
        }
        GlassPanel panel = new GlassPanel(this, backdrop, ThemeColors.accent(),
                0x800B0E14, 20, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0xD8);
        panel.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        panel.setBackdropScreenAnchored(true);
        panel.setPadding(dp(20), dp(16), dp(20), dp(14));

        TextView eyebrow = text("RESULTS  ·  DELETE", 8f, ThemeColors.accent(), false);
        eyebrow.setLetterSpacing(0.14f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(eyebrow);

        TextView title = text("DELETE RESULT?", 16f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(3), 0, 0);
        panel.addView(title);

        TextView message = text("Remove this saved run from the results list?",
                9f, MUTED, false);
        message.setPadding(0, dp(4), 0, dp(8));
        panel.addView(message);

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(10), dp(7), dp(10), dp(7));
        summary.setBackground(Ui.rounded(0x22101820, dp(11), 0x22FFFFFF, dp(1)));
        TextView run = text("#" + (index + 1) + "  " + modeText(result.modeLabel()),
                9f, TEXT, true);
        summary.addView(run, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rating = text(result.throttleLabel(), 9f,
                result.throttleColor(), true);
        rating.setGravity(Gravity.RIGHT);
        summary.addView(rating);
        panel.addView(summary, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        TextView cancel = text("CANCEL", 10f, 0xE6FFFFFF, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setBackground(Ui.rounded(0x22FFFFFF, dp(11), 0x55FFFFFF, dp(1)));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        TextView delete = text("DELETE", 10f, 0xFFFF8A80, true);
        delete.setGravity(Gravity.CENTER);
        delete.setFocusable(true);
        delete.setClickable(true);
        delete.setBackground(Ui.rounded(0x22FF6B6B, dp(11), 0x88FF6B6B, dp(1)));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                BenchmarkResultStore.remove(BenchmarkResultActivity.this, result.timestamp);
                results.remove(index);
                if (results.isEmpty()) showList();
                else {
                    selectedIndex = Math.min(selectedIndex, results.size() - 1);
                    showList();
                }
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        dlp.leftMargin = dp(8);
        buttons.addView(delete, dlp);
        panel.addView(buttons, matchWrap());

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(0x00000000));
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64),
                    dp(390));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.52f;
            win.setAttributes(lp);
        }
        dialog.show();
        final ControllerNav dialogNav = new ControllerNav(this, null);
        dialogNav.attachDialog(dialog, panel);
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int keyCode,
                                           KeyEvent event) {
                if (dialogNav.dispatchKeyEvent(event)) return true;
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                dialogNav.release();
            }
        });
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(170L).start();
        cancel.requestFocus();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("STRESS TEST RESULTS", 14f, ThemeColors.accent(), false);
        title.setLetterSpacing(0.13f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle = text("", 8.5f, MUTED, false);
        subtitle.setLetterSpacing(0.08f);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setMinWidth(dp(36));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishAnimated(); }
        });
        closeButton = close;
        header.addView(close);
        return header;
    }

    private View scoreCard(String label, String value, String sub, int valueColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(Ui.rounded(0x22101820, dp(14), 0x22FFFFFF, dp(1)));
        TextView l = text(label, 7.5f, MUTED, false);
        l.setLetterSpacing(0.12f);
        l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(l);
        TextView v = text(value, 21f, valueColor, true);
        v.setPadding(0, dp(1), 0, 0);
        card.addView(v);
        TextView s = text(sub, 8f, MUTED, true);
        s.setPadding(0, dp(2), 0, 0);
        card.addView(s);
        return card;
    }

    private void addSection(LinearLayout parent, String label) {
        TextView v = text(label, 8.5f, MUTED, false);
        v.setLetterSpacing(0.13f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(9), 0, dp(3));
        parent.addView(v);
    }

    private void addRow(LinearLayout parent, String label, String value, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = text(label, 8.5f, MUTED, false);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 9.5f, TEXT, true);
        v.setGravity(Gravity.RIGHT);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        TextView d = text(detail, 8f, MUTED, true);
        d.setGravity(Gravity.RIGHT);
        row.addView(d, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, matchWrap());
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 11f, primary ? 0xFF071015 : 0xE6FFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(primary ? ThemeColors.accent() : 0x22FFFFFF,
                dp(12), primary ? 0x00000000 : 0x44FFFFFF, dp(1)));
        return v;
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String formatDate(long timestamp) {
        if (timestamp <= 0L) return "--";
        return new SimpleDateFormat("MMM d  ·  HH:mm", Locale.getDefault())
                .format(new Date(timestamp));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String tr(String value) {
        return Loc.s(this, value);
    }

    private String modeText(String mode) {
        return "COMBINED".equals(mode) ? tr("COMBINED") : mode;
    }
}
