package com.ben.aurora;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Built-in app for the performance overlay and the floating (sideview) app drawer. */
public final class OverlayActivity extends AuroraGlassActivity {

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int CARD_FILL = 0x12FFFFFF;
    private static final int CARD_BORDER = 0x22FFFFFF;
    private static final int CARD_RADIUS = 18;
    private static final int CARD_PADDING = 14;

    private static final String[] METRICS = {
            "CPU TEMP", "CPU CLOCK", "CPU LOAD", "GPU TEMP", "GPU CLOCK",
            "GPU LOAD", "FPS", "FPS GRAPH", "RAM", "BATTERY", "CORES"
    };

    private final List<TextView> metricChips = new ArrayList<>();
    private final List<TextView> refreshChips = new ArrayList<>();
    private final List<TextView> columnChips = new ArrayList<>();

    private Prefs prefs;
    private int accent;
    private TextView status;
    private TextView overlayChip;
    private TextView lockChip;
    private TextView shortcutRow;
    private TextView perfShortcutRow;
    private TextView perfComboRow;
    private TextView drawerComboRow;
    private boolean suppress;
    private boolean pendingOverlay;
    private long lastHaptic;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private final android.os.Handler handler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final long[] lastScrollAt = {0L};
    private final long[] lastSliderAt = {0L};

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (SliderStick.isGamepad(event)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            View focus = getCurrentFocus();
            float rx = SliderStick.rightX(event);
            if (focus instanceof SeekBar && SliderStick.active(rx)) {
                SliderStick.adjust((SeekBar) focus, rx, lastSliderAt, focus);
                return true;
            }
            float ry = StickScroll.rightY(event);
            if (StickScroll.active(ry)) {
                StickScroll.scroll(StickScroll.scrollParent(focus), ry, lastScrollAt);
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            finishAnimated();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private final Runnable visibilityPoll = new Runnable() {
        @Override
        public void run() {
            refreshState();
            handler.postDelayed(this, 350L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        buildUi();
        hideBars();
        refreshState();
    }

    @Override
    protected void onPause() {
        if (prefListener != null) {
            prefs.shared().unregisterOnSharedPreferenceChangeListener(prefListener);
            prefListener = null;
        }
        handler.removeCallbacks(visibilityPoll);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        // The overlay and drawer can also be toggled from the controller
        // shortcuts while this screen is open; follow the stored state.
        prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if (key != null && !key.startsWith("perf_") && !key.startsWith("drawer_")) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshState();
                    }
                });
            }
        };
        prefs.shared().registerOnSharedPreferenceChangeListener(prefListener);
        handler.removeCallbacks(visibilityPoll);
        handler.post(visibilityPoll);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getCurrentFocus() == null && overlayChip != null) {
                    overlayChip.requestFocus();
                }
            }
        }, 160L);
        if (pendingOverlay && Settings.canDrawOverlays(this)) {
            pendingOverlay = false;
            prefs.setPerfOverlay(true);
            PerfOverlayService.start(this);
        }
        refreshState();
    }

    private void hideBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsController c = window.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    // ------------------------------------------------------------------ shell

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        card.setPadding(dp(22), dp(12), dp(22), dp(12));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        root.addView(card, cardLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(7);
        column.addView(divider, dlp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = dp(8);
        column.addView(content, contentLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f);
        leftLp.rightMargin = dp(6);
        content.addView(wrapScroll(left), leftLp);

        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f);
        midLp.rightMargin = dp(6);
        content.addView(wrapScroll(middle), midLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f);
        content.addView(wrapScroll(right), rightLp);

        buildOverlaySection(left);
        buildMetricsSection(middle);
        buildDrawerSection(right);
        reflowColumns(content, left, middle, right, Ui.columns(this, 3));

        setContentView(root);
    }

    /** Re-flows the six cards into 1, 2 or 3 equal columns. */
    private void reflowColumns(LinearLayout content, LinearLayout a,
                               LinearLayout b, LinearLayout c, int cols) {
        List<View> cards = new ArrayList<>();
        for (LinearLayout column : new LinearLayout[]{a, b, c}) {
            while (column.getChildCount() > 0) {
                cards.add(column.getChildAt(0));
                column.removeViewAt(0);
            }
        }
        content.removeAllViews();
        int perColumn = Math.max(1, (cards.size() + cols - 1) / cols);
        // A wide, tall panel gets equal-height cards with their content centred,
        // so the grid is symmetrical and no card ends in a void. Short panels
        // keep content-sized cards that scroll, otherwise the content clips.
        boolean stretch = cols == 3 && Ui.layoutHeightDp(this) >= 560;
        for (int i = 0; i < cols; i++) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            int from = i * perColumn;
            int to = Math.min(cards.size(), from + perColumn);
            for (int k = from; k < to; k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        stretch ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                        stretch ? 1f : 0f);
                lp.topMargin = dp(9);
                View cardView = cards.get(k);
                if (stretch && cardView instanceof LinearLayout) {
                    ((LinearLayout) cardView).setGravity(Gravity.CENTER_VERTICAL);
                }
                column.addView(cardView, lp);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) clp.leftMargin = dp(6);
            content.addView(wrapScroll(column), clp);
        }
    }

    private View wrapScroll(LinearLayout column) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("OVERLAY & DRAWER", 19f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        titles.addView(title);
        TextView subtitle = text(DeviceProfile.get(this).name.toUpperCase()
                + "  ·  LIVE OVERLAY", 10f, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        status = text("OFF", 10f, MUTED, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(10);
        header.addView(status, slp);

        TextView close = text("×", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(false);
        close.setDefaultFocusHighlightEnabled(false);
        close.setPadding(dp(10), 0, dp(2), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAnimated();
            }
        });
        header.addView(close);
        return header;
    }

    private LinearLayout sectionBox(String title, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int cp = Ui.widthDp(this) < 700 ? 11 : CARD_PADDING;
        box.setPadding(dp(cp), dp(8), dp(cp), dp(8));
        box.setBackground(Ui.rounded(CARD_FILL, dp(CARD_RADIUS), CARD_BORDER, dp(1)));

        TextView t = text(title, 10.5f, accent, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(2), 0, dp(2), 0);
        box.addView(t);
        if (hint != null && !hint.isEmpty()) {
            TextView h = text(hint, 9f, MUTED, false);
            h.setPadding(dp(2), dp(2), dp(2), 0);
            box.addView(h);
        }
        return box;
    }

    private LinearLayout section(LinearLayout parent, String title, String hint) {
        LinearLayout box = sectionBox(title, hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        parent.addView(box, lp);
        return box;
    }

    /** Card that owns an equal share of its column, so every card lines up. */
    private LinearLayout sectionWeighted(LinearLayout parent, String title, String hint) {
        LinearLayout box = sectionBox(title, hint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(9);
        parent.addView(box, lp);
        return box;
    }

    // ------------------------------------------------------------- overlay UI

    private void buildOverlaySection(LinearLayout parent) {
        LinearLayout box = sectionWeighted(parent, "PERFORMANCE OVERLAY",
                "Pinch to resize, long-press for the menu.");
        overlayChip = toggle(box, "SHOW OVERLAY", prefs.perfOverlay(),
                new Toggle() {
                    @Override
                    public void onChanged(boolean value) {
                        setOverlayEnabled(value);
                    }
                });
        slider(box, "Opacity", 25, 100, prefs.perfOverlayAlpha(), "%",
                new IntValue() {
                    @Override
                    public void onValue(int value) {
                        prefs.setPerfOverlayAlpha(value);
                        PerfOverlayService.refreshIfRunning();
                    }
                });

        TextView refreshLabel = text("UPDATE RATE", 8.5f, MUTED, true);
        refreshLabel.setLetterSpacing(0.08f);
        refreshLabel.setPadding(dp(2), dp(7), 0, 0);
        box.addView(refreshLabel);
        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        refreshRow.setPadding(0, dp(4), 0, 0);
        box.addView(refreshRow);
        int[] rates = {250, 500, 1000, 2000, 5000};
        for (int i = 0; i < rates.length; i++) {
            final int ms = rates[i];
            TextView chip = modeChip(rates[i] < 1000 ? rates[i] / 1000f + "s"
                    : rates[i] / 1000 + "s");
            refreshChips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(v);
                    prefs.setPerfRefreshMs(ms);
                    PerfOverlayService.refreshIfRunning();
                    refreshState();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < rates.length - 1 ? dp(5) : 0;
            refreshRow.addView(chip, lp);
        }

        LinearLayout shortcuts = sectionWeighted(parent, "SHORTCUTS", null);

        perfShortcutRow = text("", 10f, TEXT, false);
        perfShortcutRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        perfShortcutRow.setBackground(Ui.rounded(0x18FFFFFF, dp(11), 0x22FFFFFF, dp(1)));
        attachFocus(perfShortcutRow);
        perfShortcutRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AuroraInputService.beginKeyCapture(OverlayActivity.this)) {
                    LToast.makeText(OverlayActivity.this,
                            "Press a controller button for the overlay",
                            android.widget.Toast.LENGTH_LONG).show();
                    v.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            refreshState();
                        }
                    }, 4200L);
                } else {
                    LToast.makeText(OverlayActivity.this,
                            "Controller service unavailable. Check PServer/root or accessibility.",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pslp.topMargin = dp(7);
        shortcuts.addView(perfShortcutRow, pslp);

        perfComboRow = comboRow(shortcuts, true);
        clearRow(shortcuts, true);
    }

    private void buildMetricsSection(LinearLayout parent) {
        LinearLayout box = sectionWeighted(parent, "METRICS", null);
        LinearLayout row = null;
        for (int i = 0; i < METRICS.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = i == 0 ? 0 : dp(5);
                box.addView(row, rlp);
            }
            final int index = i;
            TextView chip = modeChip(METRICS[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
            chip.setPadding(dp(3), dp(5), dp(3), dp(5));
            metricChips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(v);
                    setMetric(index, !isMetric(index));
                    refreshState();
                    PerfOverlayService.refreshIfRunning();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = (i % 3 != 2) ? dp(4) : 0;
            row.addView(chip, lp);
        }

        LinearLayout actions = sectionWeighted(parent, "OVERLAY ACTIONS", null);
        lockChip = toggle(actions, "LOCK POSITION", prefs.perfOverlayLocked(),
                new Toggle() {
                    @Override
                    public void onChanged(boolean value) {
                        prefs.setPerfOverlayLocked(value);
                        PerfOverlayService.refreshIfRunning();
                        refreshState();
                    }
                });
        action(actions, "RESET POSITION", new Runnable() {
            @Override
            public void run() {
                prefs.setPerfOverlayPosition(-1, -1);
                PerfOverlayService.refreshIfRunning();
            }
        });
        action(actions, "OPEN DETAILS", new Runnable() {
            @Override
            public void run() {
                PerfOverlayService.showDetailFromShortcut(OverlayActivity.this);
            }
        });
    }

    // -------------------------------------------------------------- drawer UI

    private void buildDrawerSection(LinearLayout parent) {
        LinearLayout box = sectionWeighted(parent, "SIDEVIEW DRAWER",
                "Floating app drawer above any app.");

        action(box, "OPEN DRAWER", new Runnable() {
            @Override
            public void run() {
                AuroraInputService.toggleFloatingDrawerFromShortcut(OverlayActivity.this);
            }
        });

        shortcutRow = text("", 10f, TEXT, false);
        shortcutRow.setPadding(dp(10), dp(8), dp(10), dp(8));
        shortcutRow.setBackground(Ui.rounded(0x18FFFFFF, dp(11), 0x22FFFFFF, dp(1)));
        attachFocus(shortcutRow);
        shortcutRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AuroraInputService.beginDrawerKeyCapture(OverlayActivity.this)) {
                    LToast.makeText(OverlayActivity.this,
                            "Press a controller button for the app drawer",
                            android.widget.Toast.LENGTH_LONG).show();
                } else {
                    LToast.makeText(OverlayActivity.this,
                            "Controller service unavailable. Check PServer/root or accessibility.",
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(7);
        box.addView(shortcutRow, slp);

        drawerComboRow = comboRow(box, false);
        clearRow(box, false);

        LinearLayout style = sectionWeighted(parent, "DRAWER STYLE", null);

        TextView colLabel = text("COLUMNS", 8.5f, MUTED, true);
        colLabel.setLetterSpacing(0.08f);
        colLabel.setPadding(dp(2), dp(5), 0, 0);
        style.addView(colLabel);
        LinearLayout colRow = new LinearLayout(this);
        colRow.setOrientation(LinearLayout.HORIZONTAL);
        colRow.setPadding(0, dp(4), 0, 0);
        style.addView(colRow);
        for (int i = 0; i < 2; i++) {
            final int columns = i + 1;
            TextView chip = modeChip(columns == 1 ? "1 COLUMN" : "2 COLUMNS");
            columnChips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(v);
                    prefs.setDrawerOverlayColumns(columns);
                    refreshState();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i == 0 ? dp(5) : 0;
            colRow.addView(chip, lp);
        }

        slider(style, "Drawer opacity", 20, 100, prefs.drawerOverlayAlpha(), "%",
                new IntValue() {
                    @Override
                    public void onValue(int value) {
                        prefs.setDrawerOverlayAlpha(value);
                    }
                });
        slider(style, "Icon size", 34, 76, prefs.drawerOverlayIconDp(), " dp",
                new IntValue() {
                    @Override
                    public void onValue(int value) {
                        prefs.setDrawerOverlayIconDp(value);
                    }
                });
    }

    // ------------------------------------------------------------------ state

    private void refreshState() {
        boolean panelShown = prefs.perfOverlay() && PerfOverlayService.showsPanel();
        if (overlayChip != null) {
            setChipSelected(overlayChip, panelShown);
        }
        if (lockChip != null) {
            setChipSelected(lockChip, prefs.perfOverlayLocked());
        }
        for (int i = 0; i < metricChips.size(); i++) {
            setChipSelected(metricChips.get(i), isMetric(i));
        }
        int refresh = prefs.perfRefreshMs();
        for (int i = 0; i < refreshChips.size(); i++) {
            int[] rates = {250, 500, 1000, 2000, 5000};
            setChipSelected(refreshChips.get(i), rates[i] == refresh);
        }
        int columns = prefs.drawerOverlayColumns();
        for (int i = 0; i < columnChips.size(); i++) {
            setChipSelected(columnChips.get(i), columns == i + 1);
        }
        if (shortcutRow != null) {
            String mapped = AuroraA11yService.drawerControllerLabel(prefs);
            shortcutRow.setText("Shortcut: " + mapped + "  ·  tap to map");
        }
        if (perfShortcutRow != null) {
            String mapped = AuroraA11yService.controllerLabel(prefs);
            perfShortcutRow.setText("Shortcut: " + mapped + "  ·  tap to map");
        }
        if (perfComboRow != null) {
            perfComboRow.setText(comboLabel(prefs.perfCombo(), "Overlay combo"));
        }
        if (drawerComboRow != null) {
            drawerComboRow.setText(comboLabel(prefs.drawerCombo(), "Drawer combo"));
        }
        if (status != null) {
            boolean on = panelShown;
            status.setText(on ? "LIVE" : "OFF");
            status.setTextColor(on ? GREEN : MUTED);
            status.setBackground(Ui.rounded(on ? 0x1816C784 : 0x18FFFFFF, dp(12),
                    on ? 0x4469D98B : 0x33FFFFFF, dp(1)));
        }
    }

    private void setOverlayEnabled(boolean on) {
        if (!on) {
            prefs.setPerfOverlay(false);
            PerfOverlayService.stop(this);
        } else if (prefs.perfOverlay() && !PerfOverlayService.isPanelVisible()) {
            // Service is alive but the shortcut hid the panel: bring it back.
            PerfOverlayService.toggleOverlayFromShortcut(this);
        } else {
            if (!Settings.canDrawOverlays(this)) {
                pendingOverlay = true;
                LToast.makeText(this, "Allow Aurora to display over other apps",
                        android.widget.Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable ignored) {
                }
                refreshState();
                return;
            }
            prefs.setPerfOverlay(true);
            PerfOverlayService.start(this);
        }
        refreshState();
    }

    // ------------------------------------------------------------------ prefs

    private boolean isMetric(int i) {
        switch (i) {
            case 0: return prefs.perfCpuTemp();
            case 1: return prefs.perfCpuClock();
            case 2: return prefs.perfCpuUsage();
            case 3: return prefs.perfGpuTemp();
            case 4: return prefs.perfGpuClock();
            case 5: return prefs.perfGpuUsage();
            case 6: return prefs.perfFps();
            case 7: return prefs.perfFpsGraph();
            case 8: return prefs.perfRam();
            case 9: return prefs.perfBattery();
            default: return prefs.perfCores();
        }
    }

    private void setMetric(int i, boolean on) {
        switch (i) {
            case 0: prefs.setPerfCpuTemp(on); break;
            case 1: prefs.setPerfCpuClock(on); break;
            case 2: prefs.setPerfCpuUsage(on); break;
            case 3: prefs.setPerfGpuTemp(on); break;
            case 4: prefs.setPerfGpuClock(on); break;
            case 5: prefs.setPerfGpuUsage(on); break;
            case 6: prefs.setPerfFps(on); break;
            case 7: prefs.setPerfFpsGraph(on); break;
            case 8: prefs.setPerfRam(on); break;
            case 9: prefs.setPerfBattery(on); break;
            default: prefs.setPerfCores(on); break;
        }
    }

    private TextView comboRow(LinearLayout parent, final boolean overlay) {
        TextView row = text("", 10f, TEXT, false);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setBackground(Ui.rounded(0x18FFFFFF, dp(11), 0x22FFFFFF, dp(1)));
        attachFocus(row);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = overlay
                        ? AuroraInputService.beginOverlayComboCapture(OverlayActivity.this)
                        : AuroraInputService.beginDrawerComboCapture(OverlayActivity.this);
                if (!ok) {
                    LToast.makeText(OverlayActivity.this,
                            "Controller service unavailable. Check PServer/root.",
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshState();
                    }
                }, 4200L);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(7);
        parent.addView(row, lp);
        return row;
    }

    /** Both clear actions on one line so the card stays inside its row. */
    private void clearRow(LinearLayout parent, final boolean overlay) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(2), dp(7), 0, 0);

        TextView shortcut = text("CLEAR SHORTCUT", 8.5f, 0x99FFFFFF, true);
        shortcut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (overlay) prefs.setPerfControllerRaw("");
                else prefs.setDrawerControllerRaw("");
                refreshState();
            }
        });
        row.addView(shortcut, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView combo = text("CLEAR COMBO", 8.5f, 0x99FFFFFF, true);
        combo.setGravity(Gravity.END);
        combo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (overlay) prefs.setPerfCombo("");
                else prefs.setDrawerCombo("");
                refreshState();
            }
        });
        row.addView(combo);
        parent.addView(row);
    }

    private static String comboLabel(String combo, String name) {
        if (combo == null || combo.isEmpty()) return "Combo: not set  ·  tap to set";
        String pretty = combo.replace("+", " + ")
                .replace("BTN_", "")
                .replace("KEY_", "");
        return "Combo: " + pretty + "  ·  tap to change";
    }

    // --------------------------------------------------------------- widgets

    private interface Toggle {
        void onChanged(boolean value);
    }

    private interface IntValue {
        void onValue(int value);
    }

    private TextView toggle(LinearLayout parent, String label, boolean on,
                            final Toggle callback) {
        final TextView chip = text(label, 9.5f, TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        attachFocus(chip);
        setChipSelected(chip, on);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                haptic(v);
                boolean next = !(chip.getTag() == Boolean.TRUE);
                setChipSelected(chip, next);
                callback.onChanged(next);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        parent.addView(chip, lp);
        return chip;
    }

    private void setChipSelected(TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setTag(selected);
        chip.setTextColor(selected ? 0xFF071018 : TEXT);
        chip.setBackground(Ui.rounded(selected ? accent : 0x1AFFFFFF, dp(10),
                selected ? accent : 0x33FFFFFF, dp(1)));
    }

    private TextView modeChip(String label) {
        TextView chip = text(label, 8.5f, TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(5), dp(6), dp(5));
        attachFocus(chip);
        chip.setBackground(Ui.rounded(0x1AFFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        return chip;
    }

    private void action(LinearLayout parent, String label, final Runnable run) {
        TextView row = text(label, 9.5f, TEXT, true);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));
        attachFocus(row);
        row.setBackground(actionBackground());
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                haptic(v);
                flash(v);
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        parent.addView(row, lp);
    }

    private void slider(LinearLayout parent, String label, final int min, int max,
                        int value, final String suffix, final IntValue callback) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        parent.addView(header, hlp);

        TextView name = text(label, 9f, MUTED, false);
        header.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView valueView = text(value + suffix, 9.5f, accent, true);
        header.addView(valueView);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(accent);
        thumb.setSize(dp(16), dp(16));
        bar.setThumb(thumb);
        bar.setThumbOffset(0);
        bar.setSplitTrack(false);
        attachFocus(bar);
        bar.setPadding(dp(10), dp(2), dp(10), dp(2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(v + suffix);
                if (!suppress) {
                    callback.onValue(v);
                    if (fromUser) haptic(seekBar);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Controller focus ring drawn as a foreground so states stay intact. */
    private void attachFocus(View v) {
        v.setFocusable(true);
        v.setDefaultFocusHighlightEnabled(false);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                view.setForeground(focused
                        ? Ui.focusRing(OverlayActivity.this, dp(10)) : null);
            }
        });
    }

    /** Accent flash on press, used by the action rows. */
    private Drawable actionBackground() {
        StateListDrawable states = new StateListDrawable();
        int pressed = (0x66 << 24) | (accent & 0x00FFFFFF);
        states.addState(new int[]{android.R.attr.state_pressed},
                Ui.rounded(pressed, dp(10), accent, dp(1)));
        states.addState(new int[]{},
                Ui.rounded(0x1AFFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        return states;
    }

    private void flash(final View v) {
        int pressed = (0x66 << 24) | (accent & 0x00FFFFFF);
        v.setBackground(Ui.rounded(pressed, dp(10), accent, dp(1)));
        v.postDelayed(new Runnable() {
            @Override
            public void run() {
                v.setBackground(actionBackground());
            }
        }, 350L);
    }

    private void haptic(View v) {
        long now = SystemClock.uptimeMillis();
        if (now - lastHaptic < 45L) return;
        lastHaptic = now;
        Haptics.tick(v);
    }

    private TextView text(String value, float sp, int color, boolean medium) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        return v;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
