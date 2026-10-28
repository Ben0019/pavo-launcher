package com.ben.aurora;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.FocusFinder;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Built-in launcher editor for the system-wide controller remapper. */
public final class ControllerMapActivity extends AuroraGlassActivity {
    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x80FFFFFF;
    private static final int GREEN = 0xFF00E6A8;
    private static final int RED = 0xFFFF3D3D;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ControllerMapConfig config = new ControllerMapConfig();
    private final List<View> focusRows = new ArrayList<>();
    private File configFile;
    private Prefs prefs;
    private FrameLayout rootView;
    private LinearLayout listContent;
    private ScrollView scroll;
    private final long[] scrollStickAt = {0L};
    private Switch masterSwitch;
    private Switch rootlessSwitch;
    private OdinOutlineView outlineView;
    private TextView statusText;
    private TextView hintText;
    private TextView cancelButton;
    private boolean updatingSwitch;
    private long lastStickMotionAt;
    private final AuroraA11yService.VisualListener visualListener =
            new AuroraA11yService.VisualListener() {
                @Override
                public void onControllerVisual(int raw, boolean down) {
                    if (outlineView != null) outlineView.setPressed(raw, down);
                }
            };
    private final Runnable clearStickGlow = new Runnable() {
        @Override
        public void run() {
            if (outlineView != null) {
                outlineView.setPressed(317, false);
                outlineView.setPressed(318, false);
            }
        }
    };

    private int captureSource = -1;
    private int focusedSource = -1;
    private final Runnable captureTimeout = new Runnable() {
        @Override
        public void run() {
            if (captureSource < 0) return;
            captureSource = -1;
            toast("Remap canceled");
            refreshHeader();
        }
    };

    private int comboPhase; // 0 idle, 1 sources, 2 target
    private final List<Integer> comboKeys = new ArrayList<>();
    private int comboTarget = -1;
    private final Runnable comboSourceTimeout = new Runnable() {
        @Override
        public void run() {
            if (comboPhase != 1) return;
            finishComboSources();
        }
    };
    private final Runnable comboTargetTimeout = new Runnable() {
        @Override
        public void run() {
            if (comboPhase != 2) return;
            comboPhase = 0;
            comboKeys.clear();
            comboTarget = -1;
            toast("Combination canceled");
            refreshHeader();
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshHeader();
            handler.postDelayed(this, 1200L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        configFile = ControllerRemapManager.configFile(this);
        config.load(configFile);
        buildUi();
        hideBars();
        rebuildList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        AuroraA11yService.setRemapUiActive(true);
        AuroraInputService.setRemapUiActive(true);
        AuroraA11yService.setVisualListener(visualListener);
        AuroraInputService.setVisualListener(visualListener);
        AuroraInputService.ensureStarted(this);
        persist(true);
        if (prefs.controllerRemapEnabled()) ControllerRemapManager.ensureStarted(this);
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        refreshHeader();
    }

    @Override
    protected void onPause() {
        AuroraA11yService.setRemapUiActive(false);
        AuroraInputService.setRemapUiActive(false);
        AuroraA11yService.setVisualListener(null);
        AuroraInputService.setVisualListener(null);
        if (outlineView != null) outlineView.clearPressed();
        handler.removeCallbacks(clearStickGlow);
        persist(false);
        handler.removeCallbacks(statusTick);
        handler.removeCallbacks(captureTimeout);
        handler.removeCallbacks(comboSourceTimeout);
        handler.removeCallbacks(comboTargetTimeout);
        captureSource = -1;
        comboPhase = 0;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (captureSource >= 0 || comboPhase != 0) {
            cancelCapture();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int raw = ControllerMapConfig.rawFor(event);
        if (outlineView != null && raw > 0) {
            boolean dpad = raw >= 544 && raw <= 547;
            boolean recentStick = dpad
                    && SystemClock.uptimeMillis() - lastStickMotionAt < 300L;
            if (!recentStick || event.getAction() == KeyEvent.ACTION_UP) {
                outlineView.setPressed(raw, event.getAction() == KeyEvent.ACTION_DOWN);
            }
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0;

        if (captureSource >= 0) {
            if (down && raw > 0) {
                captureSource(raw);
            }
            return true;
        }
        if (comboPhase == 1) {
            if (down && raw > 0 && !comboKeys.contains(raw)) {
                if (comboKeys.isEmpty()) {
                    handler.removeCallbacks(comboSourceTimeout);
                    handler.postDelayed(comboSourceTimeout, 1400L);
                }
                if (comboKeys.size() < 4) comboKeys.add(raw);
                refreshHeader();
            }
            return true;
        }
        if (comboPhase == 2) {
            if (down && raw > 0) {
                comboTarget = raw;
                finishCombo();
            }
            return true;
        }

        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
            if (down) onBackPressed();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y && down) {
            if (focusedSource > 0) {
                config.toggleTurbo(focusedSource);
                persist(true);
                rebuildList();
                Haptics.click(rootView);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down) {
                View focused = getCurrentFocus();
                if (focused == null || focused == scroll) {
                    focusFirstRow();
                    focused = getCurrentFocus();
                }
                if (focused != null && focused.isClickable()) {
                    focused.performClick();
                }
            }
            return true;
        }
        int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : -1;
        if (dir >= 0) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            View focused = getCurrentFocus();
            if (focused == null || focused == scroll) {
                focusFirstRow();
                return true;
            }
            int index = focusRows.indexOf(focused);
            if (index >= 0) {
                if (dir == View.FOCUS_DOWN) index++;
                else if (dir == View.FOCUS_UP) index--;
                if (index < 0) masterSwitch.requestFocus();
                else if (index >= focusRows.size()) focusRows.get(focusRows.size() - 1).requestFocus();
                else focusRows.get(index).requestFocus();
                return true;
            }
            if (focused == masterSwitch) {
                if (dir == View.FOCUS_DOWN) {
                    focusFirstRow();
                    return true;
                }
                if (dir == View.FOCUS_UP) {
                    rootlessSwitch.requestFocus();
                    return true;
                }
            }
            if (focused == rootlessSwitch) {
                if (dir == View.FOCUS_DOWN) masterSwitch.requestFocus();
                else if (dir == View.FOCUS_UP) masterSwitch.requestFocus();
                return true;
            }
            View next = focused.getParent() instanceof ViewGroup
                    ? FocusFinder.getInstance().findNextFocus(
                            (ViewGroup) focused.getParent(), focused, dir) : null;
            if (next != null) next.requestFocus();
            else if (scroll != null) {
                scroll.smoothScrollBy(0, dir == View.FOCUS_DOWN ? dp(42) : -dp(42));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        card.setPadding(dp(22), dp(14), dp(22), dp(12));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(dp(860), sw - dp(32)), sh - dp(32), Gravity.CENTER);
        root.addView(card, cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("CONTROLLER REMAPPER", 19f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        titles.addView(title);
        TextView subtitle = text("System-wide remapping  \u00b7  PServer or accessibility",
                10f, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = text("\u00d7", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(10), 0, dp(2), 0);
        close.setFocusable(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAnimated();
            }
        });
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(0, dp(4), 0, dp(2));

        LinearLayout controlsColumn = new LinearLayout(this);
        controlsColumn.setOrientation(LinearLayout.VERTICAL);
        controlsColumn.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        masterRow.setPadding(0, dp(1), 0, dp(1));
        TextView masterLabel = text("Enable remapping", 12.5f, TEXT, false);
        masterLabel.setPadding(0, 0, dp(6), 0);
        masterRow.addView(masterLabel);
        masterSwitch = new Switch(this);
        masterSwitch.setChecked(prefs.controllerRemapEnabled());
        masterSwitch.setShowText(false);
        masterSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                         boolean isChecked) {
                if (updatingSwitch) return;
                Haptics.click(buttonView);
                setEnabled(isChecked);
            }
        });
        masterRow.addView(masterSwitch);
        controlsColumn.addView(masterRow);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(0, dp(1), 0, dp(1));
        TextView modeLabel = text("No-root accessibility", 11.5f, 0xCCFFFFFF, false);
        modeLabel.setPadding(0, 0, dp(6), 0);
        modeRow.addView(modeLabel);
        rootlessSwitch = new Switch(this);
        rootlessSwitch.setChecked(prefs.rootlessRemap());
        rootlessSwitch.setShowText(false);
        rootlessSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                         boolean isChecked) {
                if (updatingSwitch) return;
                Haptics.click(buttonView);
                prefs.setRootlessRemap(isChecked);
                if (prefs.controllerRemapEnabled()) {
                    ControllerRemapManager.restart(ControllerMapActivity.this,
                            new ControllerRemapManager.Callback() {
                                @Override
                                public void onResult(boolean running) {
                                    if (isFinishing() || isDestroyed()) return;
                                    refreshHeader();
                                    if (!running) toast("Could not start the selected mode");
                                }
                            });
                }
                refreshHeader();
            }
        });
        modeRow.addView(rootlessSwitch);
        controlsColumn.addView(modeRow);
        hero.addView(controlsColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        outlineView = new OdinOutlineView(this);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                0, dp(138), 1f);
        olp.leftMargin = dp(8);
        hero.addView(outlineView, olp);
        card.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusText = text("", 10.5f, MUTED, true);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancelButton = text("CANCEL", 9.5f, 0xFFFF8A80, false);
        cancelButton.setPadding(dp(9), dp(5), dp(9), dp(5));
        cancelButton.setBackground(Ui.rounded(0x22FF5C6C, dp(10), 0x66FF5C6C, dp(1)));
        cancelButton.setVisibility(View.GONE);
        cancelButton.setFocusable(false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelCapture();
            }
        });
        statusRow.addView(cancelButton);
        card.addView(statusRow);

        hintText = text("", 10.5f, 0x99FFFFFF, false);
        hintText.setPadding(0, dp(2), 0, dp(8));
        card.addView(hintText);

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(3);
        card.addView(divider, dlp);

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setClipToPadding(false);
        listContent = new LinearLayout(this);
        listContent.setOrientation(LinearLayout.VERTICAL);
        listContent.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(listContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void refreshHeader() {
        boolean enabled = prefs.controllerRemapEnabled();
        boolean running = ControllerRemapManager.isRunning(this);
        if (enabled) {
            String mode = prefs.rootlessRemap() ? "no-root accessibility" : "PServer root daemon";
            statusText.setText(running ? "ACTIVE \u00b7 " + mode
                    : "STARTING / UNAVAILABLE");
            statusText.setTextColor(running ? GREEN : 0xFFFFC107);
        } else {
            statusText.setText("OFF \u00b7 mappings are saved but not applied");
            statusText.setTextColor(MUTED);
        }
        cancelButton.setVisibility(captureSource >= 0 || comboPhase != 0
                ? View.VISIBLE : View.GONE);
        if (captureSource >= 0) {
            hintText.setText("Press the target button for "
                    + ControllerMapConfig.labelFor(captureSource) + "  \u00b7  CANCEL aborts");
            hintText.setTextColor(accent);
        } else if (comboPhase == 1) {
            hintText.setText("Press the buttons to combine"
                    + (comboKeys.isEmpty() ? "" : "  \u00b7  " + comboKeyLabels()));
            hintText.setTextColor(accent);
        } else if (comboPhase == 2) {
            hintText.setText("Press the shortcut target for " + comboKeyLabels());
            hintText.setTextColor(accent);
        } else {
            hintText.setText("A edit target  \u00b7  Y turbo  \u00b7  B close  \u00b7  "
                    + "A mapping to the same button clears it");
            hintText.setTextColor(0x99FFFFFF);
        }
        if (updatingSwitch) return;
        updatingSwitch = true;
        masterSwitch.setChecked(enabled);
        if (rootlessSwitch != null) rootlessSwitch.setChecked(prefs.rootlessRemap());
        updatingSwitch = false;
    }

    private void setEnabled(final boolean enabled) {
        prefs.setControllerRemapEnabled(enabled);
        persist(true);
        refreshHeader();
        if (enabled) {
            ControllerRemapManager.start(this, new ControllerRemapManager.Callback() {
                @Override
                public void onResult(boolean running) {
                    if (isFinishing() || isDestroyed()) return;
                    refreshHeader();
                    if (!running) {
                        toast("Remapper could not start. PServer or root is required.");
                        updatingSwitch = true;
                        masterSwitch.setChecked(false);
                        updatingSwitch = false;
                        prefs.setControllerRemapEnabled(false);
                        persist(true);
                        refreshHeader();
                    }
                }
            });
        } else {
            ControllerRemapManager.stop(this);
        }
    }

    private void persist(boolean capture) {
        config.save(configFile, prefs.controllerRemapEnabled(), capture);
    }

    private void rebuildList() {
        if (outlineView != null) outlineView.setMappings(config.mappings);
        listContent.removeAllViews();
        focusRows.clear();

        addSection("BUTTONS");
        for (int source : ControllerMapConfig.BUTTONS) {
            View row = makeMappingRow(source);
            focusRows.add(row);
            listContent.addView(row);
        }

        LinearLayout combosHead = new LinearLayout(this);
        combosHead.setOrientation(LinearLayout.HORIZONTAL);
        combosHead.setGravity(Gravity.CENTER_VERTICAL);
        combosHead.setPadding(0, dp(14), 0, dp(5));
        TextView comboTitle = text("COMBINATIONS", 9.5f, accent, false);
        comboTitle.setLetterSpacing(0.14f);
        comboTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        combosHead.addView(comboTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = text("+ ADD", 9.5f, accent, false);
        add.setPadding(dp(9), dp(5), dp(9), dp(5));
        add.setBackground(Ui.rounded(ThemeColors.withAlpha(0x1F), dp(10), ThemeColors.withAlpha(0x66), dp(1)));
        add.setFocusable(true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                beginCombo();
            }
        });
        combosHead.addView(add);
        focusRows.add(add);
        listContent.addView(combosHead);

        if (config.combos.isEmpty()) {
            TextView empty = text("No combinations yet. Add one to turn a chord into a shortcut.",
                    10.5f, 0x77FFFFFF, false);
            empty.setPadding(dp(4), dp(4), dp(4), dp(8));
            listContent.addView(empty);
        } else {
            for (final ControllerMapConfig.Combo combo : config.combos) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(9), dp(8));
                row.setBackground(Ui.rounded(0x14FFFFFF, dp(12), 0x26FFFFFF, dp(1)));
                row.setFocusable(true);
                row.setFocusableInTouchMode(true);
                row.setDefaultFocusHighlightEnabled(false);
                TextView label = text(combo.label(), 11.5f, 0xE6FFFFFF, true);
                row.addView(label, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView remove = text("\u00d7", 19f, 0xFFFF8A80, false);
                remove.setGravity(Gravity.CENTER);
                remove.setPadding(dp(10), 0, dp(5), 0);
                remove.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Haptics.click(v);
                        config.removeCombo(combo);
                        persist(true);
                        rebuildList();
                    }
                });
                row.addView(remove);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(5);
                focusRows.add(row);
                listContent.addView(row, lp);
            }
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, dp(14), 0, dp(4));
        TextView hint = text("PServer/root daemon grabs the Odin controller and exposes one virtual controller.",
                9.5f, 0x66FFFFFF, false);
        footer.addView(hint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView reset = text("RESET ALL", 9.5f, 0xFFFF8A80, false);
        reset.setPadding(dp(9), dp(5), dp(9), dp(5));
        reset.setFocusable(true);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                config.mappings.clear();
                config.turbo.clear();
                config.combos.clear();
                persist(true);
                rebuildList();
            }
        });
        footer.addView(reset);
        focusRows.add(reset);
        listContent.addView(footer);

        listContent.post(new Runnable() {
            @Override
            public void run() {
                if (getCurrentFocus() == null) focusFirstRow();
            }
        });
    }

    private void addSection(String title) {
        TextView label = text(title, 9.5f, accent, false);
        label.setLetterSpacing(0.14f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(4), dp(5), 0, dp(5));
        listContent.addView(label);
    }

    private View makeMappingRow(final int source) {
        final boolean mapped = config.hasMapping(source);
        final int target = config.targetFor(source);
        final boolean turbo = config.turbo.contains(source);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(7), dp(9), dp(7));
        row.setBackground(Ui.rounded(0x14FFFFFF, dp(12), 0x26FFFFFF, dp(1)));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setDefaultFocusHighlightEnabled(false);
        row.setTag(source);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                if (focused) {
                    focusedSource = source;
                    Haptics.tick(v);
                }
                v.setBackground(focused
                        ? Ui.focusRing(ControllerMapActivity.this, dp(12))
                        : Ui.rounded(0x14FFFFFF, dp(12), 0x26FFFFFF, dp(1)));
            }
        });

        int face = faceColour(source);
        TextView name = text(ControllerMapConfig.labelFor(source), 12.5f, face, false);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setShadowLayer(dp(2.4f), 0f, 0f, (face & 0x00FFFFFF) | 0xA0000000);
        row.addView(name, new LinearLayout.LayoutParams(
                dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        int targetColour = mapped ? faceColour(target) : 0x66FFFFFF;
        TextView targetView = text(mapped
                ? ("\u2192  " + ControllerMapConfig.labelFor(target)) : "no change",
                11.5f, targetColour, mapped);
        if (mapped) {
            targetView.setShadowLayer(dp(2.0f), 0f, 0f,
                    (targetColour & 0x00FFFFFF) | 0x80000000);
        }
        row.addView(targetView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView turboView = text("TURBO", 8.5f, turbo ? GREEN : 0x55FFFFFF, false);
        turboView.setPadding(dp(7), dp(4), dp(7), dp(4));
        turboView.setBackground(turbo
                ? Ui.rounded(0x2200E6A8, dp(9), 0x6600E6A8, dp(1))
                : Ui.rounded(0x12FFFFFF, dp(9), 0x22FFFFFF, dp(1)));
        turboView.setFocusable(false);
        turboView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                config.toggleTurbo(source);
                persist(true);
                rebuildList();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(7);
        row.addView(turboView, tlp);

        if (mapped) {
            TextView clear = text("\u00d7", 18f, 0x99FFFFFF, false);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(dp(8), 0, dp(1), 0);
            clear.setFocusable(false);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    config.clearMapping(source);
                    persist(true);
                    rebuildList();
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(2);
            row.addView(clear, clp);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                beginCapture(source);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void beginCapture(int source) {
        if (comboPhase != 0) return;
        captureSource = source;
        focusedSource = source;
        handler.removeCallbacks(captureTimeout);
        handler.postDelayed(captureTimeout, 20000L);
        refreshHeader();
    }

    private void captureSource(int raw) {
        int source = captureSource;
        if (source < 0) return;
        handler.removeCallbacks(captureTimeout);
        config.setMapping(source, raw);
        captureSource = -1;
        persist(true);
        rebuildList();
        Haptics.click(rootView);
        toast(raw == source
                ? ControllerMapConfig.labelFor(source) + " cleared"
                : ControllerMapConfig.labelFor(source) + " \u2192 "
                        + ControllerMapConfig.labelFor(raw));
        refreshHeader();
    }

    private void beginCombo() {
        if (captureSource >= 0) return;
        comboPhase = 1;
        comboKeys.clear();
        comboTarget = -1;
        handler.removeCallbacks(comboSourceTimeout);
        handler.postDelayed(comboSourceTimeout, 4500L);
        refreshHeader();
    }

    private void finishComboSources() {
        handler.removeCallbacks(comboSourceTimeout);
        if (comboPhase != 1) return;
        if (comboKeys.size() < 2) {
            comboPhase = 0;
            comboKeys.clear();
            toast("A combination needs at least two buttons");
            refreshHeader();
            return;
        }
        comboPhase = 2;
        handler.removeCallbacks(comboTargetTimeout);
        handler.postDelayed(comboTargetTimeout, 20000L);
        refreshHeader();
    }

    private void finishCombo() {
        handler.removeCallbacks(comboTargetTimeout);
        int[] keys = new int[comboKeys.size()];
        for (int i = 0; i < comboKeys.size(); i++) keys[i] = comboKeys.get(i);
        config.addCombo(keys, comboTarget);
        comboPhase = 0;
        comboKeys.clear();
        comboTarget = -1;
        persist(true);
        rebuildList();
        Haptics.click(rootView);
        toast("Combination saved");
        refreshHeader();
    }

    private void cancelCapture() {
        handler.removeCallbacks(captureTimeout);
        handler.removeCallbacks(comboSourceTimeout);
        handler.removeCallbacks(comboTargetTimeout);
        captureSource = -1;
        comboPhase = 0;
        comboKeys.clear();
        comboTarget = -1;
        toast("Canceled");
        refreshHeader();
    }

    private void focusFirstRow() {
        for (View row : focusRows) {
            if (row.getVisibility() == View.VISIBLE && row.isFocusable()) {
                row.requestFocus();
                return;
            }
        }
    }

    private String comboKeyLabels() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < comboKeys.size(); i++) {
            if (i > 0) b.append(" + ");
            b.append(ControllerMapConfig.labelFor(comboKeys.get(i)));
        }
        return b.toString();
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

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (outlineView != null) {
            int source = event.getSource();
            boolean gamepad = (source & InputDevice.SOURCE_JOYSTICK) != 0
                    || (source & InputDevice.SOURCE_GAMEPAD) != 0;
            if (gamepad && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float lx = event.getAxisValue(MotionEvent.AXIS_X);
                float ly = event.getAxisValue(MotionEvent.AXIS_Y);
                float rx = event.getAxisValue(MotionEvent.AXIS_Z);
                float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
                if (Math.abs(rx) < 0.02f && Math.abs(ry) < 0.02f) {
                    rx = event.getAxisValue(MotionEvent.AXIS_RX);
                    ry = event.getAxisValue(MotionEvent.AXIS_RY);
                }
                float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
                boolean left = Math.hypot(lx, ly) > 0.30f;
                boolean right = Math.hypot(rx, ry) > 0.30f;
                lastStickMotionAt = SystemClock.uptimeMillis();
                outlineView.setStickAxes(lx, ly, rx, ry);
                outlineView.setHat(left || right ? 0f : hatX,
                        left || right ? 0f : hatY);
                if (left || right) {
                    outlineView.setPressed(544, false);
                    outlineView.setPressed(545, false);
                    outlineView.setPressed(546, false);
                    outlineView.setPressed(547, false);
                }
                outlineView.setPressed(317, left);
                outlineView.setPressed(318, right);
                if (Math.abs(rx) > 0.18f) outlineView.rotateYawByStick(rx);
                else outlineView.stopYawStick();
                handler.removeCallbacks(clearStickGlow);
                if (left || right) handler.postDelayed(clearStickGlow, 700L);
                float scrollY = StickScroll.rightY(event);
                if (StickScroll.active(scrollY)
                        && StickScroll.scroll(scroll, scrollY, scrollStickAt)) {
                    return true;
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static boolean isSyntheticStickDpad(KeyEvent event) {
        int code = event.getKeyCode();
        boolean dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        return dpad && event.getScanCode() == 0
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBars();
            if (listContent != null) {
                listContent.post(new Runnable() {
                    @Override
                    public void run() {
                        View focused = getCurrentFocus();
                        if (focused == null || !focusRows.contains(focused)) {
                            focusFirstRow();
                        }
                    }
                });
            }
        }
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    static int faceColour(int raw) {
        switch (raw) {
            case 304: return 0xFF7ED957;
            case 305: return 0xFFFF4D4D;
            case 306: return 0xFFC792EA;
            case 307: return 0xFF4DA3FF;
            case 308: return 0xFFFFD54D;
            case 309: return 0xFF80D8FF;
            default: return 0xE6FFFFFF;
        }
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    private void toast(String value) {
        LToast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
