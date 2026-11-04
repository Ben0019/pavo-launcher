package com.ben.pavo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.input.InputManager;
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
public final class ControllerMapActivity extends PavoGlassActivity {
    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x80FFFFFF;
    private static final int GREEN = 0xFF00E6A8;
    private static final int RED = 0xFFFF3D3D;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ControllerMapConfig config = new ControllerMapConfig();
    private final List<View> focusRows = new ArrayList<>();
    private File configFile;
    private ControllerProfileStore profileStore;
    private String selectedAppPkg = "";
    private TextView profileChip;
    private TextView profileReset;
    private AppPickerStripView appPicker;
    private Prefs prefs;
    private FrameLayout rootView;
    private LinearLayout listContent;
    private ScrollView scroll;
    private final long[] scrollStickAt = {0L};
    private Switch masterSwitch;
    private OdinOutlineView outlineView;
    private TextView controllerText;
    private TextView statusText;
    private TextView hintText;
    private TextView cancelButton;
    private boolean updatingSwitch;
    private long lastStickMotionAt;
    private final PavoA11yService.VisualListener visualListener =
            new PavoA11yService.VisualListener() {
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
    private boolean learnPhase;
    private String lastCatalogKey = "";
    private InputManager inputManager;
    private final InputManager.InputDeviceListener inputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    refreshControllerUi();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    refreshControllerUi();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    refreshControllerUi();
                }
            };
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
        profileStore = new ControllerProfileStore(this);
        rootView = findViewById(android.R.id.content);
        loadProfileSilently("");
        buildUi();
        hideBars();
        loadProfile("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        hidePerformanceOverlay();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hidePerformanceOverlay();
            }
        }, 450L);
        ControllerProfiles.activityOpen = true;
        PavoA11yService.setRemapUiActive(true);
        PavoInputService.setRemapUiActive(true);
        PavoA11yService.setVisualListener(visualListener);
        PavoInputService.setVisualListener(visualListener);
        PavoInputService.ensureStarted(this);
        if (inputManager == null) {
            inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        }
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(inputDeviceListener, handler);
        }
        persist(true);
        if (prefs.controllerRemapEnabled()) ControllerRemapManager.ensureStarted(this);
        refreshControllerUi();
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        refreshHeader();
    }

    @Override
    protected void onPause() {
        if (inputManager != null) {
            try {
                inputManager.unregisterInputDeviceListener(inputDeviceListener);
            } catch (Throwable ignored) {
            }
        }
        learnPhase = false;
        ControllerProfiles.activityOpen = false;
        ControllerProfiles.markDirty();
        PavoA11yService.setRemapUiActive(false);
        PavoInputService.setRemapUiActive(false);
        PavoA11yService.setVisualListener(null);
        PavoInputService.setVisualListener(null);
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
        if (captureSource >= 0 || comboPhase != 0 || learnPhase) {
            cancelCapture();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int raw = ControllerMapConfig.rawFor(event);
        int anyRaw = ControllerButtons.rawForAny(event);
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

        if (learnPhase) {
            if (down && anyRaw > 0) {
                ControllerButtons.learn(this, anyRaw);
                learnPhase = false;
                refreshControllerUi();
                refreshHeader();
                Haptics.click(rootView);
                toast(String.format(Loc.s(this, "%s added"), buttonLabel(anyRaw)));
            }
            return true;
        }
        if (captureSource >= 0) {
            if (down) {
                int target = raw > 0 ? raw : anyRaw;
                if (target > 0) {
                    if (!ControllerMapConfig.isKnown(target)) {
                        ControllerButtons.learn(this, target);
                    }
                    captureSource(target);
                }
            }
            return true;
        }
        if (comboPhase == 1) {
            int key = raw > 0 ? raw : anyRaw;
            if (down && key > 0 && !comboKeys.contains(key)) {
                if (!ControllerMapConfig.isKnown(key)) ControllerButtons.learn(this, key);
                if (comboKeys.isEmpty()) {
                    handler.removeCallbacks(comboSourceTimeout);
                    handler.postDelayed(comboSourceTimeout, 1400L);
                }
                if (comboKeys.size() < 4) comboKeys.add(key);
                refreshHeader();
            }
            return true;
        }
        if (comboPhase == 2) {
            int target = raw > 0 ? raw : anyRaw;
            if (down && target > 0) {
                if (!ControllerMapConfig.isKnown(target)) {
                    ControllerButtons.learn(this, target);
                }
                comboTarget = target;
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
                if (dir == View.FOCUS_DOWN) focusFirstRow();
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
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        card.setPadding(dp(20), dp(12), dp(20), dp(10));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(dp(900), sw - dp(24)), sh - dp(20), Gravity.CENTER);
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
        TextView subtitle = text("System-wide remapping  \u00b7  PServer",
                9.5f, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, 0);
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

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        profileRow.setPadding(0, dp(6), 0, dp(3));

        profileChip = text("ALL APPS  \u25be", 10f, TEXT, false);
        profileChip.setGravity(Gravity.CENTER);
        profileChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        profileChip.setFocusable(true);
        profileChip.setClickable(true);
        profileChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openAppPicker();
            }
        });
        profileRow.addView(profileChip);

        profileReset = text("USE GLOBAL", 9f, 0xCCFFFFFF, false);
        profileReset.setGravity(Gravity.CENTER);
        profileReset.setPadding(dp(9), dp(6), dp(9), dp(6));
        profileReset.setFocusable(true);
        profileReset.setClickable(true);
        profileReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectedAppPkg.isEmpty()) return;
                profileStore.removeApp(selectedAppPkg);
                ControllerProfiles.markDirty();
                loadProfile("");
                LToast.makeText(ControllerMapActivity.this,
                        "Using the global mapping", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.leftMargin = dp(6);
        profileRow.addView(profileReset, resetLp);

        TextView profileHint = text("Per-app profiles", 8.5f, MUTED, false);
        profileHint.setPadding(dp(8), 0, 0, 0);
        profileRow.addView(profileHint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(profileRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View topDivider = new View(this);
        topDivider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams tdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        tdlp.topMargin = dp(2);
        card.addView(topDivider, tdlp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setBaselineAligned(false);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(4);
        card.addView(body, bodyLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(10), dp(8), dp(8), dp(8));
        left.setBackground(Ui.rounded(0x300B0E14, dp(16), 0x2EFFFFFF, dp(1)));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.43f);
        leftLp.rightMargin = dp(5);
        body.addView(left, leftLp);

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setClipToPadding(false);
        listContent = new LinearLayout(this);
        listContent.setOrientation(LinearLayout.VERTICAL);
        listContent.setPadding(0, dp(2), 0, dp(2));
        scroll.addView(listContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.57f);
        rightLp.leftMargin = dp(5);
        body.addView(right, rightLp);

        outlineView = new OdinOutlineView(this);
        right.addView(outlineView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(8), dp(12), dp(8));
        controls.setBackground(Ui.rounded(0x300B0E14, dp(16), 0x2EFFFFFF, dp(1)));
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLp.topMargin = dp(6);
        right.addView(controls, controlsLp);

        controllerText = text("Detecting controller\u2026", 8.5f, MUTED, true);
        controllerText.setGravity(Gravity.END);
        controls.addView(controllerText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = text("", 9.5f, MUTED, true);
        statusText.setGravity(Gravity.END);
        statusText.setPadding(0, dp(2), 0, 0);
        controls.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View controlDivider = new View(this);
        controlDivider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams ctrlDlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        ctrlDlp.topMargin = dp(8);
        ctrlDlp.bottomMargin = dp(4);
        controls.addView(controlDivider, ctrlDlp);

        LinearLayout masterRow = new LinearLayout(this);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        masterRow.setPadding(0, dp(2), 0, dp(2));
        TextView masterLabel = text("Enable remapping", 12f, TEXT, false);
        masterLabel.setPadding(0, 0, dp(6), 0);
        masterRow.addView(masterLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        controls.addView(masterRow);

        hintText = text("", 8.8f, 0x99FFFFFF, false);
        hintText.setGravity(Gravity.END);
        hintText.setPadding(0, dp(5), 0, 0);
        controls.addView(hintText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cancelButton = text("CANCEL", 9f, 0xFFFF8A80, false);
        cancelButton.setGravity(Gravity.CENTER);
        cancelButton.setPadding(dp(9), dp(4), dp(9), dp(4));
        cancelButton.setBackground(Ui.rounded(0x22FF5C6C, dp(10), 0x66FF5C6C, dp(1)));
        cancelButton.setVisibility(View.GONE);
        cancelButton.setFocusable(false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelCapture();
            }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.topMargin = dp(4);
        cancelLp.gravity = Gravity.END;
        controls.addView(cancelButton, cancelLp);

        setContentView(root);
    }

    private void refreshHeader() {
        boolean enabled = prefs.controllerRemapEnabled();
        boolean running = ControllerRemapManager.isRunning(this);
        if (enabled) {
            statusText.setText(running ? "ACTIVE \u00b7 PSERVER"
                    : "STARTING / UNAVAILABLE");
            statusText.setTextColor(running ? GREEN : 0xFFFFC107);
        } else {
            statusText.setText("OFF \u00b7 mappings are saved but not applied");
            statusText.setTextColor(MUTED);
        }
        cancelButton.setVisibility(captureSource >= 0 || comboPhase != 0 || learnPhase
                ? View.VISIBLE : View.GONE);
        if (learnPhase) {
            hintText.setText("Press any controller button to add it to the list  \u00b7  CANCEL aborts");
            hintText.setTextColor(accent);
        } else if (captureSource >= 0) {
            hintText.setText(String.format(Loc.s(this,
                    "Press the target button for %s  \u00b7  CANCEL aborts"),
                    buttonLabel(captureSource)));
            hintText.setTextColor(accent);
        } else if (comboPhase == 1) {
            String combo = comboKeyLabels();
            hintText.setText(combo.isEmpty()
                    ? Loc.s(this, "Press the buttons to combine")
                    : String.format(Loc.s(this, "Press the buttons to combine  \u00b7  %s"),
                            combo));
            hintText.setTextColor(accent);
        } else if (comboPhase == 2) {
            hintText.setText(String.format(Loc.s(this,
                    "Press the shortcut target for %s"), comboKeyLabels()));
            hintText.setTextColor(accent);
        } else {
            hintText.setText("A edit  \u00b7  Y turbo  \u00b7  B close  \u00b7  same button clears");
            hintText.setTextColor(0x99FFFFFF);
        }
        if (updatingSwitch) return;
        updatingSwitch = true;
        masterSwitch.setChecked(enabled);
        updatingSwitch = false;
    }

    private void refreshControllerUi() {
        if (isFinishing() || isDestroyed()) return;
        ControllerButtons.Catalog catalog = ControllerButtons.detect(this);
        if (controllerText != null) {
            if (catalog.name.isEmpty()) {
                controllerText.setText("NO CONTROLLER DETECTED  \u00b7  standard buttons");
                controllerText.setTextColor(0xFFFFC107);
            } else {
                controllerText.setText(String.format(Loc.s(this,
                        "DETECTED  \u00b7  %s  \u00b7  %d buttons"),
                        catalog.name.toUpperCase(), catalog.raws.size()));
                controllerText.setTextColor(GREEN);
            }
        }
        if (outlineView != null) {
            // Keep the controller outline on every device; only the button
            // rows and detected header adapt to the connected hardware.
            outlineView.setVisibility(View.VISIBLE);
        }
        String key = catalog.name + "|" + catalog.raws;
        boolean changed = !key.equals(lastCatalogKey);
        lastCatalogKey = key;
        // Starting/stopping the PServer remapper creates and removes a virtual
        // controller.  Those device-change callbacks used to rebuild every row,
        // which looked like the buttons card shaking.  Only rebuild when the
        // real controller or its button set actually changed.
        if (changed && captureSource < 0 && comboPhase == 0 && !learnPhase
                && listContent != null) {
            rebuildList();
        }
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
                        toast("Remapper could not start. PServer is required.");
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
        boolean enabled = prefs.controllerRemapEnabled();
        String text = config.encode(enabled, capture);
        if (selectedAppPkg.isEmpty()) {
            config.save(configFile, enabled, capture);
            profileStore.setGlobal(text);
        } else {
            String global = profileStore.global();
            ControllerMapConfig base = new ControllerMapConfig();
            base.loadText(global);
            // Identical to the global mapping: no override needed.
            if (global != null && base.sameRules(config)) {
                profileStore.removeApp(selectedAppPkg);
            } else {
                profileStore.setApp(selectedAppPkg, text);
            }
        }
        ControllerProfiles.markDirty();
    }

    private void loadProfileSilently(String pkg) {
        selectedAppPkg = pkg == null ? "" : pkg;
        if (selectedAppPkg.isEmpty()) {
            String saved = profileStore.global();
            if (saved == null) {
                // First run with profiles: the existing file is the global profile.
                config.load(configFile);
                profileStore.setGlobal(config.encode(config.enabled, config.capture));
            } else {
                config.loadText(saved);
            }
        } else {
            String saved = profileStore.app(selectedAppPkg);
            String base = profileStore.global();
            if (saved == null) {
                // Start a new profile from the global mapping, not from nothing.
                if (base == null) {
                    config.load(configFile);
                    base = config.encode(config.enabled, config.capture);
                    profileStore.setGlobal(base);
                } else {
                    config.loadText(base);
                }
            } else {
                config.loadText(saved);
            }
            // Enable remapping is device-wide; a profile only carries mappings.
            config.enabled = prefs.controllerRemapEnabled();
        }
    }

    private void loadProfile(String pkg) {
        loadProfileSilently(pkg);
        rebuildList();
        if (outlineView != null) outlineView.setMappings(config.mappings);
        updateProfileChip();
        refreshHeader();
    }

    private void updateProfileChip() {
        if (profileChip == null) return;
        String label = "ALL APPS";
        if (!selectedAppPkg.isEmpty()) {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(
                        pm.getApplicationInfo(selectedAppPkg, 0)));
            } catch (Throwable ignored) {
                label = selectedAppPkg;
            }
        }
        profileChip.setText(label + "  \u25be");
        boolean perApp = !selectedAppPkg.isEmpty();
        profileChip.setTextColor(perApp ? 0xFF06110B : 0xF2FFFFFF);
        profileChip.setBackground(Ui.rounded(perApp ? accent : 0x1AFFFFFF, dp(10),
                perApp ? 0x00000000 : 0x44FFFFFF, dp(1)));
        if (profileReset != null) profileReset.setVisibility(perApp ? View.VISIBLE : View.GONE);
    }

    private void openAppPicker() {
        if (appPicker != null || rootView == null) return;
        GlassPanel base = PavoGlassActivity.largestGlassPanel(rootView);
        appPicker = new AppPickerStripView(this, prefs, selectedAppPkg, base,
                new AppPickerStripView.Listener() {
                    @Override public void onPick(String pkg, Apps.Entry entry) {
                        appPicker = null;
                        ControllerProfiles.markDirty();
                        loadProfile(pkg == null ? "" : pkg);
                    }
                    @Override public void onClose() {
                        appPicker = null;
                    }
                });
        rootView.addView(appPicker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appPicker.bringToFront();
    }

    private void rebuildList() {
        if (outlineView != null) outlineView.setMappings(config.mappings);
        listContent.removeAllViews();
        focusRows.clear();

        ControllerButtons.Catalog catalog = ControllerButtons.detect(this);
        java.util.LinkedHashSet<Integer> sourceSet = new java.util.LinkedHashSet<>(catalog.raws);
        sourceSet.addAll(config.mappings.keySet());
        java.util.ArrayList<Integer> sources = new java.util.ArrayList<>(sourceSet);
        addSection(String.format(Loc.s(this, "BUTTONS \u00b7 %d"), sources.size()));
        if (sources.isEmpty()) {
            TextView empty = text("No controller buttons detected.", 10.5f, 0x77FFFFFF, false);
            empty.setPadding(dp(4), dp(2), dp(4), dp(6));
            listContent.addView(empty);
        }
        for (int source : sources) {
            View row = makeMappingRow(source);
            focusRows.add(row);
            listContent.addView(row);
        }
        TextView learn = text("+ LEARN BUTTON", 9.5f, accent, false);
        learn.setGravity(Gravity.CENTER);
        learn.setPadding(dp(10), dp(7), dp(10), dp(7));
        learn.setFocusable(true);
        learn.setFocusableInTouchMode(true);
        learn.setBackground(Ui.rounded(ThemeColors.withAlpha(0x1B), dp(10),
                ThemeColors.withAlpha(0x55), dp(1)));
        learn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (captureSource >= 0 || comboPhase != 0) return;
                Haptics.click(v);
                learnPhase = true;
                refreshHeader();
            }
        });
        LinearLayout.LayoutParams learnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        learnLp.topMargin = dp(4);
        learnLp.bottomMargin = dp(2);
        focusRows.add(learn);
        listContent.addView(learn, learnLp);
        if (catalog.fallback) {
            TextView fallback = text("No controller detected \u2014 showing the standard button set. "
                    + "Connect one, then use + LEARN for extra buttons.", 9.5f, 0x99FFC857, false);
            fallback.setPadding(dp(4), dp(3), dp(4), dp(7));
            listContent.addView(fallback);
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
                TextView label = text(comboLabel(combo), 11.5f, 0xE6FFFFFF, true);
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
        TextView hint = text("PServer grabs the Odin controller and exposes one virtual controller.",
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
        row.setPadding(dp(12), dp(5), dp(9), dp(5));
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
        TextView name = text(buttonLabel(source), 12.5f, face, false);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setShadowLayer(dp(2.4f), 0f, 0f, (face & 0x00FFFFFF) | 0xA0000000);
        row.addView(name, new LinearLayout.LayoutParams(
                dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));

        int targetColour = mapped ? faceColour(target) : 0x66FFFFFF;
        TextView targetView = text(mapped
                ? ("\u2192  " + buttonLabel(target)) : "no change",
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
        lp.bottomMargin = dp(3);
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
                ? String.format(Loc.s(this, "%s cleared"), buttonLabel(source))
                : String.format(Loc.s(this, "%s \u2192 %s"),
                        buttonLabel(source), buttonLabel(raw)));
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
        learnPhase = false;
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

    private String buttonLabel(int raw) {
        String value = ControllerMapConfig.labelFor(raw);
        if (value.startsWith("Button ")) {
            try {
                return String.format(Loc.s(this, "Button %d"),
                        Integer.parseInt(value.substring(7)));
            } catch (Throwable ignored) {
            }
        }
        return Loc.s(this, value);
    }

    private String comboLabel(ControllerMapConfig.Combo combo) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < combo.keys.length; i++) {
            if (i > 0) b.append(" + ");
            b.append(buttonLabel(combo.keys[i]));
        }
        b.append("  \u2192  ").append(buttonLabel(combo.target));
        return b.toString();
    }

    private String comboKeyLabels() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < comboKeys.size(); i++) {
            if (i > 0) b.append(" + ");
            b.append(buttonLabel(comboKeys.get(i)));
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
