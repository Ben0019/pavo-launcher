package com.ben.aurora;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in Aurora app: system fan presets, precise curve and live rear fan model. */
public final class FanActivity extends AuroraGlassActivity {
    static final int MODE_CURVE = 7;

    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int RED = 0xFFFF8A80;
    private static final int CARD_FILL = 0x12FFFFFF;
    private static final int CARD_BORDER = 0x22FFFFFF;

    private static final int[] MODE_VALUES = {
            PerfTuner.FAN_OFF, PerfTuner.FAN_LOW, PerfTuner.FAN_SMART,
            PerfTuner.FAN_HIGH, MODE_CURVE, PerfTuner.FAN_MANUAL
    };
    private static final String[] MODE_LABELS = {
            "OFF", "LOW", "SMART", "HIGH", "CURVE", "MANUAL"
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final long[] sliderStickAt = {0L};
    private long graphSelectStickAt;
    private long graphDutyStickAt;
    private long graphDpadAt;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();

    private Prefs prefs;
    private FanProfileStore profileStore;
    private FrameLayout rootView;
    private LinearLayout appSelector;
    private ImageView appSelectorIcon;
    private TextView appSelectorLabel;
    private TextView resetAppChip;
    private AppPickerStripView appPicker;
    private String selectedAppPkg = "";
    private boolean resumed;
    private boolean localCurve;
    private FanCurveView curveView;
    private FanBackView backView;
    private TextView demoChip;
    private boolean demoActive;
    private long demoStart;
    private TextView rpmValue;
    private TextView dutyValue;
    private TextView tempValue;
    private TextView status;
    private TextView curveHint;
    private SeekBar manualBar;
    private TextView manualValue;
    private GradientDrawable manualThumb;
    private float currentTemp = 40f;

    private int mode;
    private int manualPct;
    private String curve;
    private boolean polling;
    private boolean suppress;
    private boolean applying;
    private int lastDuty = -1;
    private final Runnable manualApply = new Runnable() {
        @Override
        public void run() {
            final int percent = manualPct;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    PerfTuner.setFanManual(percent);
                }
            });
        }
    };
    private final Runnable curveServiceUpdate = new Runnable() {
        @Override
        public void run() {
            if (mode != MODE_CURVE) return;
            if (profileStore != null && profileStore.hasOverrides()) startLocalCurve();
            else FanCurveService.update(FanActivity.this);
        }
    };
    private final Runnable localCurveTick = new Runnable() {
        @Override
        public void run() {
            if (!localCurve || !resumed || mode != MODE_CURVE) return;
            final String currentCurve = curve;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    float[] temps = PerfTuner.readTemperatures();
                    float temp = Math.max(temps[0], temps[1]);
                    if (temp <= 0f) temp = 40f;
                    PerfTuner.setFanManual(FanCurve.dutyAt(currentCurve, temp));
                }
            });
            main.postDelayed(this, 1500L);
        }
    };

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            if (!demoActive || backView == null) return;
            long t = android.os.SystemClock.uptimeMillis() - demoStart;
            float p = (t % 16000L) / 16000f;
            float heat;
            if (p < 0.12f) {
                heat = 0.06f + 0.06f * (p / 0.12f);
            } else if (p < 0.55f) {
                float q = (p - 0.12f) / 0.43f;
                heat = 0.12f + 0.84f * (q * q * (3f - 2f * q));
            } else if (p < 0.70f) {
                heat = 0.96f + 0.04f * (float) Math.sin((p - 0.70f) * 90f);
            } else {
                float q = (p - 0.70f) / 0.30f;
                heat = 0.96f * (1f - q * q * (3f - 2f * q));
            }
            int duty = Math.round(4f + 96f * heat);
            int rpm = Math.round(500f + 9300f * heat);
            float cpu = 33f + 48f * heat;
            float gpu = 31f + 44f * heat;
            backView.setTelemetry(rpm, duty, cpu, gpu);
            if (rpmValue != null) {
                rpmValue.setText(String.format(Locale.US, "%,d", rpm));
                dutyValue.setText(duty + "%");
                tempValue.setText(Math.round(cpu) + "°");
                dutyValue.setTextColor(HeatColor.ramp(
                        HeatColor.fanSeverity(duty, rpm, Math.max(cpu, gpu))));
                tempValue.setTextColor(HeatColor.ramp(
                        HeatColor.severity(0, Math.max(cpu, gpu))));
            }
            if (curveView != null) curveView.setLive(cpu, duty, rpm);
            main.postDelayed(this, 50L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        profileStore = new FanProfileStore(this);
        FanProfile global = FanProfile.fromPrefs(prefs);
        mode = global.mode;
        manualPct = global.manualPct;
        curve = global.curve;
        buildUi();
        hideBars();
        updateAppSelector();
        syncModeUi();
        PerAppFanService.setTunerVisible(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        PerAppFanService.setTunerVisible(true);
        hideBars();
        hidePerformanceOverlay();
        polling = true;
        main.post(poll);
        if (profileStore.hasOverrides()) {
            FanCurveService.stop(this);
            PerAppFanService.start(this);
            if (mode == MODE_CURVE) startLocalCurve(); else stopLocalCurve();
        } else {
            stopLocalCurve();
            if (PerAppFanService.isRunning()) PerAppFanService.stop(this);
            if (mode == MODE_CURVE) FanCurveService.start(this);
            else FanCurveService.stop(this);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!chips.isEmpty()) chips.get(selectedIndex()).requestFocus();
            }
        }, 140L);
    }

    @Override
    protected void onPause() {
        resumed = false;
        polling = false;
        if (demoActive) stopFanDemo(false);
        stopLocalCurve();
        if (appPicker != null) {
            appPicker.dismiss();
            appPicker = null;
        }
        main.removeCallbacks(poll);
        main.removeCallbacks(manualApply);
        main.removeCallbacks(curveServiceUpdate);
        PerAppFanService.setTunerVisible(false);
        if (profileStore != null && profileStore.hasOverrides()) {
            PerAppFanService.refresh(this);
        } else if (mode == MODE_CURVE) {
            FanCurveService.start(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        PerAppFanService.setTunerVisible(false);
        if (profileStore != null && profileStore.hasOverrides()) {
            PerAppFanService.refresh(this);
        }
        stopLocalCurve();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (appPicker != null) {
            if (appPicker.handleKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                View focused = getCurrentFocus();
                if (focused != null && focused != curveView
                        && !(focused instanceof SeekBar) && focused.isClickable()) {
                    focused.performClick();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                jumpCard(code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
            }
            return true;
        }
        View focus = getCurrentFocus();
        if (focus == curveView) {
            if (ControllerNav.isSyntheticStickDpad(event)) return true;
            ControllerNav.noteExternalDpad(event);
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    if (code == KeyEvent.KEYCODE_DPAD_UP) focusPreset();
                    else focusManual();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (event.getRepeatCount() == 0 || now - graphDpadAt >= 90L) {
                        graphDpadAt = now;
                        curveView.moveSelection(
                                code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                }
                return true;
            }
        }
        if (focus == manualBar) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP) {
                        focusGraph();
                    } else {
                        focusPreset();
                    }
                }
                return true;
            }
        }
        int chipIndex = chipIndexForFocus(focus);
        if (chipIndex >= 0) {
            if (code == KeyEvent.KEYCODE_DPAD_UP && chipIndex < 3) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) focusGraph();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && chipIndex >= 3) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) focusManual();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) finishAnimated();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (SliderStick.isGamepad(event)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            View focus = getCurrentFocus();
            if (focus == curveView) {
                float lx = event.getAxisValue(MotionEvent.AXIS_X);
                long now = android.os.SystemClock.uptimeMillis();
                if (ControllerNav.externalDpadRecent()) return true;
                if (Math.abs(lx) > 0.56f && now - graphSelectStickAt >= 170L) {
                    graphSelectStickAt = now;
                    curveView.moveSelection(lx > 0f ? 1 : -1);
                    return true;
                }
                float ry = StickScroll.rightY(event);
                if (StickScroll.active(ry) && now - graphDutyStickAt >= 55L) {
                    graphDutyStickAt = now;
                    float power = Math.min(1f, (Math.abs(ry) - 0.18f) / 0.82f);
                    int steps = 1 + (power > 0.60f ? 1 : 0) + (power > 0.88f ? 1 : 0);
                    curveView.adjustSelectedDuty(ry < 0f ? 5 * steps : -5 * steps);
                    return true;
                }
            }
            if (focus instanceof SeekBar) {
                float rx = SliderStick.rightX(event);
                if (SliderStick.active(rx)) {
                    SliderStick.adjust((SeekBar) focus, rx, sliderStickAt, focus);
                    return true;
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
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

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        card.setPadding(dp(22), dp(12), dp(22), dp(10));
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
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.12f);
        leftLp.rightMargin = dp(5);
        content.addView(left, leftLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.08f);
        rightLp.leftMargin = dp(5);
        content.addView(right, rightLp);

        // The graph now occupies the large left area where the outline used to be.
        LinearLayout curveCard = card(left, "FAN CURVE", "");
        LinearLayout.LayoutParams curveLp = (LinearLayout.LayoutParams) curveCard.getLayoutParams();
        curveLp.height = 0;
        curveLp.weight = 1f;
        curveLp.topMargin = 0;
        curveCard.setLayoutParams(curveLp);
        curveView = new FanCurveView(this);
        curveView.setCurve(displayedCurve());
        curveView.setListener(new FanCurveView.Listener() {
            @Override
            public void onCurveChanged(String value) {
                curve = value;
                saveCurrentProfile();
                if (mode != MODE_CURVE) selectMode(MODE_CURVE);
                main.removeCallbacks(curveServiceUpdate);
                main.postDelayed(curveServiceUpdate, 140L);
                updateLiveHint();
            }
        });
        curveCard.addView(curveView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        curveHint = text("", 9f, MUTED, false);
        curveHint.setPadding(dp(2), dp(4), dp(2), 0);
        curveCard.addView(curveHint);

        // Rear outline tucked into the card's top-right corner.
        FrameLayout outlineHolder = new FrameLayout(this);
        LinearLayout.LayoutParams holderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(175));
        right.addView(outlineHolder, holderLp);
        backView = new FanBackView(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        outlineHolder.addView(backView, blp);
        demoChip = text("DEMO", 8.5f, accent, true);
        demoChip.setGravity(Gravity.CENTER);
        demoChip.setPadding(dp(9), dp(4), dp(9), dp(4));
        demoChip.setContentDescription("Demo the fan airflow outline");
        demoChip.setFocusable(true);
        demoChip.setFocusableInTouchMode(true);
        demoChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                if (demoActive) stopFanDemo(true); else startFanDemo();
            }
        });
        demoChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                styleDemoChip();
            }
        });
        FrameLayout.LayoutParams demoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        demoLp.topMargin = dp(2);
        demoLp.leftMargin = dp(2);
        outlineHolder.addView(demoChip, demoLp);
        styleDemoChip();

        // Labels and live telemetry sit directly beneath the outline.
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(2), 0, dp(2), dp(2));
        TextView rear = text("ODIN 3  ·  REAR", 10.5f, accent, true);
        rear.setLetterSpacing(0.12f);
        rear.setGravity(Gravity.END);
        info.addView(rear);
        TextView live = text("LIVE REAR AIRFLOW", 9f, MUTED, false);
        live.setGravity(Gravity.END);
        live.setPadding(0, dp(2), 0, 0);
        info.addView(live);
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.END);
        stats.setPadding(0, dp(4), 0, 0);
        rpmValue = stat(stats, "RPM", "—", 1f);
        dutyValue = stat(stats, "DUTY", "—", 1f);
        tempValue = stat(stats, "TEMP", "—", 1.2f);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.gravity = Gravity.END;
        info.addView(stats, statsLp);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                dp(240), ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.gravity = Gravity.END;
        right.addView(info, infoLp);

        View rightSpacer = new View(this);
        right.addView(rightSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout presets = card(right, "SYSTEM PRESET", "");
        LinearLayout.LayoutParams presetsLp = (LinearLayout.LayoutParams) presets.getLayoutParams();
        presetsLp.topMargin = dp(4);
        presets.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        presets.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(7);
        presets.addView(row2, row2Lp);
        for (int i = 0; i < MODE_VALUES.length; i++) {
            LinearLayout row = i < 3 ? row1 : row2;
            addModeChip(row, MODE_LABELS[i], MODE_VALUES[i], i == 2);
        }

        LinearLayout manual = card(left, "MANUAL DUTY", "");
        LinearLayout.LayoutParams manualLp = (LinearLayout.LayoutParams) manual.getLayoutParams();
        manualLp.topMargin = dp(4);
        manual.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout manualRow = new LinearLayout(this);
        manualRow.setOrientation(LinearLayout.HORIZONTAL);
        manualRow.setGravity(Gravity.CENTER_VERTICAL);
        manual.addView(manualRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView ml = text("OUTPUT", 10f, MUTED, true);
        manualRow.addView(ml, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        manualValue = text(manualPct + "%", 11f, accent, true);
        manualRow.addView(manualValue);
        manualBar = new SeekBar(this);
        manualBar.setMax(90);
        manualBar.setProgress(Math.max(0, Math.min(90, manualPct - 10)));
        manualBar.setProgressTintList(ColorStateList.valueOf(accent));
        manualThumb = new GradientDrawable();
        manualThumb.setShape(GradientDrawable.OVAL);
        manualThumb.setColor(accent);
        manualThumb.setSize(dp(16), dp(16));
        manualBar.setThumb(manualThumb);
        manualBar.setThumbOffset(0);
        manualBar.setSplitTrack(false);
        manualBar.setDefaultFocusHighlightEnabled(false);
        manualBar.setFocusableInTouchMode(true);
        manualBar.setMinimumHeight(dp(32));
        manualBar.setPadding(dp(14), dp(2), dp(14), dp(2));
        manual.addView(manualBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        manualBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(hasFocus ? Ui.focusRing(FanActivity.this, dp(10)) : null);
                if (hasFocus) Haptics.tick(v);
            }
        });
        manualBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                manualPct = progress + 10;
                manualValue.setText(manualPct + "%");
                updateManualAppearance();
                if (fromUser && !suppress) {
                    if (mode != PerfTuner.FAN_MANUAL) selectMode(PerfTuner.FAN_MANUAL);
                    saveCurrentProfile();
                    if (mode == PerfTuner.FAN_MANUAL) {
                        refreshCurveDisplay();
                        updateLiveHint();
                    }
                    main.removeCallbacks(manualApply);
                    main.postDelayed(manualApply, 90L);
                    Haptics.tick(seekBar);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.key(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.click(seekBar);
            }
        });
        updateManualAppearance();

        setContentView(root);
    }

    private void openAppPicker() {
        if (appPicker != null || rootView == null) return;
        GlassPanel baseCard = AuroraGlassActivity.largestGlassPanel(rootView);
        appPicker = new AppPickerStripView(this, prefs, selectedAppPkg, baseCard,
                new AppPickerStripView.Listener() {
                    @Override
                    public void onPick(String pkg, Apps.Entry entry) {
                        appPicker = null;
                        selectContext(pkg);
                    }

                    @Override
                    public void onClose() {
                        appPicker = null;
                    }
                });
        rootView.addView(appPicker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appPicker.bringToFront();
    }

    private void selectContext(String pkg) {
        if (profileStore == null) return;
        selectedAppPkg = pkg == null ? "" : pkg;
        FanProfile profile = selectedAppPkg.isEmpty()
                ? FanProfile.fromPrefs(prefs) : profileStore.app(selectedAppPkg);
        if (profile == null) profile = FanProfile.fromPrefs(prefs).copy();
        mode = normalizeMode(profile.mode);
        manualPct = profile.manualPct;
        curve = profile.curve;
        suppress = true;
        if (manualBar != null) {
            manualBar.setProgress(Math.max(0, Math.min(90, manualPct - 10)));
            manualValue.setText(manualPct + "%");
        }
        suppress = false;
        syncModeUi();
        updateManualAppearance();
        updateAppSelector();
        applyProfileFanMode();
        syncBackgroundServices();
        if (appSelector != null) {
            appSelector.post(new Runnable() {
                @Override
                public void run() {
                    appSelector.requestFocus();
                }
            });
        }
    }

    private void updateAppSelector() {
        if (appSelectorIcon == null || appSelectorLabel == null) return;
        String label = "ALL APPS";
        Bitmap bitmap = null;
        if (!selectedAppPkg.isEmpty()) {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(
                        pm.getApplicationInfo(selectedAppPkg, 0)));
                bitmap = Apps.drawableToBitmap(
                        pm.getApplicationIcon(selectedAppPkg), dp(26));
            } catch (Throwable ignored) {
                label = selectedAppPkg;
            }
        }
        if (bitmap == null) {
            try {
                bitmap = Apps.drawableToBitmap(getDrawable(R.mipmap.ic_launcher), dp(26));
            } catch (Throwable ignored) {
            }
        }
        appSelectorIcon.setImageBitmap(bitmap);
        appSelectorLabel.setText(label);
        if (resetAppChip != null) {
            resetAppChip.setVisibility(selectedAppPkg.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void resetSelectedApp() {
        if (selectedAppPkg.isEmpty() || profileStore == null) return;
        profileStore.removeApp(selectedAppPkg);
        if (profileStore.hasOverrides()) PerAppFanService.refresh(this);
        else PerAppFanService.stop(this);
        LToast.makeText(this, "Using global fan profile", Toast.LENGTH_SHORT).show();
        selectContext("");
    }

    private void saveCurrentProfile() {
        if (suppress || profileStore == null) return;
        FanProfile profile = new FanProfile(mode, manualPct, curve);
        if (selectedAppPkg.isEmpty()) {
            profile.saveToPrefs(prefs);
        } else {
            profileStore.setApp(selectedAppPkg, profile);
        }
        syncBackgroundServices();
    }

    private void applyProfileFanMode() {
        stopLocalCurve();
        if (mode == MODE_CURVE) {
            if (profileStore.hasOverrides()) {
                if (resumed) startLocalCurve();
            } else {
                FanCurveService.start(this);
            }
            return;
        }
        if (!profileStore.hasOverrides()) FanCurveService.stop(this);
        final int target = mode;
        final int percent = manualPct;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (target == PerfTuner.FAN_MANUAL) {
                    PerfTuner.setFanManual(percent);
                } else {
                    PerfTuner.setFanMode(FanActivity.this, target);
                }
            }
        });
    }

    private void syncBackgroundServices() {
        if (profileStore == null) return;
        if (profileStore.hasOverrides()) {
            FanCurveService.stop(this);
            PerAppFanService.start(this);
            if (resumed && mode == MODE_CURVE) startLocalCurve();
            else stopLocalCurve();
        } else {
            if (PerAppFanService.isRunning()) PerAppFanService.stop(this);
            stopLocalCurve();
            if (mode == MODE_CURVE) FanCurveService.start(this);
            else FanCurveService.stop(this);
        }
    }

    private void startLocalCurve() {
        if (!resumed || mode != MODE_CURVE || profileStore == null
                || !profileStore.hasOverrides() || localCurve) return;
        localCurve = true;
        main.removeCallbacks(localCurveTick);
        main.post(localCurveTick);
    }

    private void stopLocalCurve() {
        localCurve = false;
        main.removeCallbacks(localCurveTick);
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("FAN CONTROL", 19f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = text("ODIN 3  ·  ACTIVE COOLING", 10f, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(3), 0, 0);
        titles.addView(sub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(titles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout leftControls = new LinearLayout(this);
        leftControls.setOrientation(LinearLayout.HORIZONTAL);
        leftControls.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(leftControls, leftLp);

        appSelector = new LinearLayout(this);
        appSelector.setOrientation(LinearLayout.HORIZONTAL);
        appSelector.setGravity(Gravity.CENTER_VERTICAL);
        appSelector.setPadding(dp(6), dp(4), dp(8), dp(4));
        appSelector.setBackground(Ui.rounded(0x1EFFFFFF, dp(11), 0x33FFFFFF, dp(1)));
        appSelector.setClickable(true);
        appSelector.setFocusable(true);
        appSelector.setFocusableInTouchMode(true);
        appSelector.setDefaultFocusHighlightEnabled(false);
        appSelector.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openAppPicker();
            }
        });
        appSelector.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        v.requestFocus();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        appSelector.performClick();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        appSelector.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused ? Ui.focusRing(FanActivity.this, dp(11))
                        : Ui.rounded(0x1EFFFFFF, dp(11), 0x33FFFFFF, dp(1)));
            }
        });
        appSelectorIcon = new ImageView(this);
        appSelectorIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appSelector.addView(appSelectorIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        appSelectorLabel = text("ALL APPS", 8.5f, TEXT, true);
        if (appSelectorLabel instanceof LTextView) ((LTextView) appSelectorLabel).setLocalize(false);
        appSelectorLabel.setSingleLine(true);
        appSelectorLabel.setEllipsize(TextUtils.TruncateAt.END);
        appSelectorLabel.setMaxWidth(dp(82));
        appSelectorLabel.setPadding(dp(5), 0, 0, 0);
        appSelector.addView(appSelectorLabel);
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        appLp.rightMargin = dp(6);
        leftControls.addView(appSelector, appLp);

        resetAppChip = text("USE GLOBAL", 8f, 0xFFFFC857, true);
        resetAppChip.setLetterSpacing(0.04f);
        resetAppChip.setGravity(Gravity.CENTER);
        resetAppChip.setPadding(dp(7), dp(5), dp(7), dp(5));
        resetAppChip.setBackground(Ui.rounded(0x22FFC857, dp(10),
                0x55FFC857, dp(1)));
        resetAppChip.setVisibility(View.GONE);
        resetAppChip.setFocusable(true);
        resetAppChip.setFocusableInTouchMode(true);
        resetAppChip.setDefaultFocusHighlightEnabled(false);
        resetAppChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                resetSelectedApp();
            }
        });
        resetAppChip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        v.requestFocus();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        resetAppChip.performClick();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        resetAppChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused ? Ui.focusRing(FanActivity.this, dp(10))
                        : Ui.rounded(0x22FFC857, dp(10), 0x55FFC857, dp(1)));
            }
        });
        leftControls.addView(resetAppChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout rightControls = new LinearLayout(this);
        rightControls.setOrientation(LinearLayout.HORIZONTAL);
        rightControls.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(rightControls, rightLp);

        status = text("...", 10f, AMBER, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setBackground(Ui.rounded(0x22FFC857, dp(12), 0x66FFC857, dp(1)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(10);
        rightControls.addView(status, slp);
        TextView close = text("×", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(false);
        close.setPadding(dp(10), 0, dp(2), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAnimated();
            }
        });
        rightControls.addView(close);
        return header;
    }

    private LinearLayout card(LinearLayout parent, String title, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(9), dp(14), dp(9));
        box.setBackground(Ui.rounded(CARD_FILL, dp(18), CARD_BORDER, dp(1)));
        TextView t = text(title, 10.5f, accent, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(2, 0, 2, 0);
        box.addView(t);
        if (hint != null && !hint.isEmpty()) {
            TextView h = text(hint, 9.5f, MUTED, false);
            h.setPadding(2, dp(2), 2, dp(5));
            box.addView(h);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        parent.addView(box, lp);
        return box;
    }

    private void addModeChip(LinearLayout row, String label, int value, boolean selected) {
        final TextView chip = text(label, 9.5f, TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setMinHeight(dp(40));
        chip.setPadding(dp(4), dp(7), dp(4), dp(7));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setClickable(true);
        chip.setDefaultFocusHighlightEnabled(false);
        chip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        v.requestFocus();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        chip.performClick();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                selectMode(value);
            }
        });
        chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) Haptics.tick(v);
                styleChip(chip, mode == value, value);
            }
        });
        chips.add(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.rightMargin = dp(5);
        row.addView(chip, lp);
    }

    private void styleChip(TextView chip, boolean selected, int value) {
        int color = presetColor(value);
        if (selected) {
            chip.setTextColor(0xFF071018);
            chip.setBackground(chip.isFocused()
                    ? Ui.rounded(color, dp(14), 0xCCFFFFFF, dp(2))
                    : Ui.rounded(color, dp(14)));
        } else if (chip.isFocused()) {
            chip.setTextColor(0xFFFFFFFF);
            chip.setBackground(Ui.rounded(HeatColor.withAlpha(color, 0x30), dp(14), color, dp(1)));
        } else {
            chip.setTextColor(HeatColor.mixColor(color, 0xFFFFFFFF, 0.38f));
            chip.setBackground(Ui.rounded(HeatColor.withAlpha(color, 0x1C), dp(14),
                    HeatColor.withAlpha(color, 0x77), dp(1)));
        }
    }

    private void styleChips() {
        for (int i = 0; i < chips.size() && i < MODE_VALUES.length; i++) {
            styleChip(chips.get(i), mode == MODE_VALUES[i], MODE_VALUES[i]);
        }
    }

    private TextView stat(LinearLayout row, String label, String value, float weight) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);
        TextView l = text(label, 8.5f, MUTED, true);
        l.setGravity(Gravity.END);
        TextView v = text(value, 12f, TEXT, true);
        v.setSingleLine(true);
        v.setGravity(Gravity.END);
        v.setPadding(0, dp(1), 0, 0);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(l, textLp);
        box.addView(v, textLp);
        row.addView(box, new LinearLayout.LayoutParams(
                dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private void syncModeUi() {
        suppress = true;
        styleChips();
        if (curveView != null) {
            curveView.setEditable(true);
            refreshCurveDisplay();
        }
        if (manualBar != null) {
            manualBar.setAlpha(mode == PerfTuner.FAN_MANUAL ? 1f : 0.62f);
        }
        suppress = false;
        updateLiveHint();
    }

    private int selectedIndex() {
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i] == mode) return i;
        }
        return 2;
    }

    private void selectMode(int value) {
        mode = normalizeMode(value);
        styleChips();
        if (curveView != null) {
            curveView.setEditable(true);
            refreshCurveDisplay();
        }
        if (manualBar != null) manualBar.setAlpha(mode == PerfTuner.FAN_MANUAL ? 1f : 0.62f);
        previewFanTarget();
        saveCurrentProfile();
        applyProfileFanMode();
        updateLiveHint();
    }

    /** Graph shape for the currently active preset. */
    private String displayedCurve() {
        switch (mode) {
            case PerfTuner.FAN_OFF:
                return FanCurve.flat(0);
            case PerfTuner.FAN_LOW:
                return FanCurve.flat(10);
            case PerfTuner.FAN_SMART:
                return FanCurve.SMART;
            case PerfTuner.FAN_HIGH:
                return FanCurve.flat(100);
            case PerfTuner.FAN_MANUAL:
                return FanCurve.flat(manualPct);
            case MODE_CURVE:
            default:
                return curve;
        }
    }

    private void refreshCurveDisplay() {
        if (curveView != null) curveView.setCurve(displayedCurve());
    }

    private String modeLabel(int value) {
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i] == value) return MODE_LABELS[i];
        }
        return "SYSTEM";
    }

    private int presetDuty(int value) {
        float temp = currentTemp > 0 ? currentTemp : 40f;
        switch (value) {
            case PerfTuner.FAN_OFF:
                return 0;
            case PerfTuner.FAN_LOW:
                return 10;
            case PerfTuner.FAN_SMART:
                return FanCurve.dutyAt(FanCurve.SMART, temp);
            case PerfTuner.FAN_HIGH:
                return 100;
            case PerfTuner.FAN_MANUAL:
                return manualPct;
            case MODE_CURVE:
            default:
                return FanCurve.dutyAt(curve, temp);
        }
    }

    private int presetColor(int value) {
        return HeatColor.ramp(HeatColor.severity(presetDuty(value), currentTemp));
    }

    private void updateManualAppearance() {
        int color = HeatColor.ramp(HeatColor.severity(manualPct, currentTemp));
        if (manualValue != null) manualValue.setTextColor(color);
        if (manualBar != null) manualBar.setProgressTintList(ColorStateList.valueOf(color));
        if (manualThumb != null) manualThumb.setColor(color);
    }

    private void previewFanTarget() {
        if (backView == null) return;
        if (mode == PerfTuner.FAN_OFF) {
            backView.setTelemetry(0, 0, currentTemp, currentTemp);
            return;
        }
        int duty = presetDuty(mode);
        if (duty <= 0) return;
        int estimate = Math.min(10000, Math.max(1200, duty * 180));
        backView.setTelemetry(estimate, duty, currentTemp, currentTemp);
    }

    private int normalizeMode(int value) {
        if (value == MODE_CURVE || value == PerfTuner.FAN_MANUAL
                || value == PerfTuner.FAN_OFF || value == PerfTuner.FAN_LOW
                || value == PerfTuner.FAN_SMART || value == PerfTuner.FAN_HIGH) {
            return value;
        }
        return PerfTuner.FAN_SMART;
    }

    private int chipIndexForFocus(View focus) {
        if (focus == null) return -1;
        for (int i = 0; i < chips.size() && i < MODE_VALUES.length; i++) {
            if (chips.get(i) == focus) return i;
        }
        return -1;
    }

    private void focusGraph() {
        if (curveView != null) {
            curveView.requestFocus();
            Haptics.tick(curveView);
        }
    }

    private void focusManual() {
        if (manualBar != null) {
            manualBar.requestFocus();
            Haptics.tick(manualBar);
        }
    }

    private void focusPreset() {
        int index = selectedIndex();
        if (index >= 0 && index < chips.size()) {
            chips.get(index).requestFocus();
            Haptics.tick(chips.get(index));
        }
    }

    private void jumpCard(int direction) {
        View[] anchors = {curveView, manualBar, chips.isEmpty() ? null
                : chips.get(Math.max(0, Math.min(chips.size() - 1, selectedIndex())))};
        View focus = getCurrentFocus();
        int current = -1;
        for (int i = 0; i < anchors.length; i++) {
            if (anchors[i] == focus) {
                current = i;
                break;
            }
        }
        if (current < 0) current = 0;
        current = (current + (direction > 0 ? 1 : -1) + anchors.length) % anchors.length;
        View target = anchors[current];
        if (target != null) {
            target.requestFocus();
            Haptics.tick(target);
        }
    }

    private void updateLiveHint() {
        if (curveHint == null) return;
        if (mode == MODE_CURVE) {
            curveHint.setText("CURVE · right stick duty · D-pad/left stick point · L1/R1 cards");
            curveHint.setTextColor(0xB3FFFFFF);
        } else if (mode == PerfTuner.FAN_MANUAL) {
            curveHint.setText("MANUAL " + manualPct + "% · right stick duty · D-pad/left stick point · L1/R1");
            curveHint.setTextColor(MUTED);
        } else {
            curveHint.setText("SYSTEM " + modeLabel(mode)
                    + " · right stick duty · D-pad/left stick point · L1/R1");
            curveHint.setTextColor(MUTED);
        }
    }

    // ------------------------------------------------------------------
    // Live telemetry
    // ------------------------------------------------------------------

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    PerfTuner.Root.ensure();
                    final PerfTuner.State state = PerfTuner.readState(FanActivity.this);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            applyState(state);
                            if (polling) main.postDelayed(poll, 850L);
                        }
                    });
                }
            });
        }
    };

    private void startFanDemo() {
        if (demoActive) return;
        demoActive = true;
        demoStart = android.os.SystemClock.uptimeMillis();
        polling = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(demoTick);
        styleDemoChip();
        main.post(demoTick);
    }

    private void stopFanDemo(boolean restartPolling) {
        if (!demoActive && demoChip != null) return;
        demoActive = false;
        main.removeCallbacks(demoTick);
        styleDemoChip();
        if (restartPolling && resumed) {
            polling = true;
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

    private void styleDemoChip() {
        if (demoChip == null) return;
        demoChip.setText(demoActive ? "STOP" : "DEMO");
        if (demoChip.isFocused()) {
            demoChip.setBackground(Ui.focusRing(this, dp(8)));
        } else if (demoActive) {
            demoChip.setTextColor(0xFF061015);
            demoChip.setBackground(Ui.rounded(accent, dp(8), 0x00000000, dp(1)));
        } else {
            demoChip.setTextColor(accent);
            demoChip.setBackground(Ui.rounded(ThemeColors.withAlpha(0x1A), dp(8),
                    ThemeColors.withAlpha(0x55), dp(1)));
        }
    }

    private void applyState(PerfTuner.State state) {
        if (demoActive) return;
        if (state == null || state.fan == null) return;
        PerfTuner.Fan fan = state.fan;
        boolean fanRunning = fan.state != 0 && mode != PerfTuner.FAN_OFF;
        int actualDuty = 0;
        if (fanRunning) {
            if (fan.period > 0) {
                actualDuty = Math.max(0, Math.min(100,
                        Math.round(fan.duty * 100f / fan.period)));
            } else if (fan.speedPref > 0) {
                actualDuty = Math.max(0, Math.min(100, Math.round(fan.speedPref / 500f)));
            }
        }
        int measuredRpm = fanRunning ? Math.max(0, fan.rpm) : 0;
        boolean estimated = measuredRpm <= 0 && fanRunning && actualDuty > 0;
        int displayRpm = measuredRpm > 0 ? measuredRpm
                : (fanRunning ? PerfTuner.estimatedRpm(actualDuty) : 0);
        float temp = Math.max(state.cpuTemp, state.gpuTemp);
        if (temp > 0) currentTemp = temp;
        backView.setTelemetry(displayRpm, actualDuty, state.cpuTemp, state.gpuTemp);
        if (displayRpm > 0) {
            rpmValue.setText((estimated ? "~" : "") + String.format(Locale.US, "%,d", displayRpm));
            rpmValue.setContentDescription(estimated
                    ? "Estimated fan speed from PWM duty" : "Measured fan speed");
        } else {
            rpmValue.setText(fanRunning ? "0" : "—");
            rpmValue.setContentDescription(fanRunning ? "Fan stopped" : "Fan speed unavailable");
        }
        dutyValue.setText(actualDuty + "%");
        tempValue.setText(temp > 0 ? Math.round(temp) + "°" : "—");
        int dutyColor = HeatColor.ramp(HeatColor.fanSeverity(actualDuty, displayRpm, currentTemp));
        int heatColor = HeatColor.ramp(HeatColor.severity(0, currentTemp));
        dutyValue.setTextColor(dutyColor);
        tempValue.setTextColor(heatColor);
        updateManualAppearance();
        styleChips();
        curveView.setLive(temp, actualDuty, displayRpm);
        if (state.root) {
            String ex = PerfTuner.Root.executionName();
            status.setText(ex == null ? "ROOT" : ex);
            status.setTextColor(GREEN);
            status.setBackground(Ui.rounded(0x1816C784, dp(12), 0x4469D98B, dp(1)));
        } else {
            status.setText("OFFLINE");
            status.setTextColor(RED);
            status.setBackground(Ui.rounded(0x22FF5C6C, dp(12), 0x66FF8A80, dp(1)));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
