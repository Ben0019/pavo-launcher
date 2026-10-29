package com.ben.aurora;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ScrollView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in Aurora app: CPU, GPU and fan tuning through the Odin's root interfaces. */
public final class PerformanceTunerActivity extends AuroraGlassActivity {

    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int RED = 0xFFFF8A80;

    static final int DARK_TEXT = 0xFF061015;
    private static final int RAMP_GREEN = 0xFF4DDB91;
    private static final int RAMP_YELLOW = 0xFFFFD166;
    private static final int RAMP_RED = 0xFFFF5C6C;

    static int ramp(float t) {
        t = Math.max(0f, Math.min(1f, t));
        if (t < 0.5f) return mixColor(RAMP_GREEN, RAMP_YELLOW, t * 2f);
        return mixColor(RAMP_YELLOW, RAMP_RED, (t - 0.5f) * 2f);
    }

    static int mixColor(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(Color.red(from) * (1f - t) + Color.red(to) * t);
        int g = Math.round(Color.green(from) * (1f - t) + Color.green(to) * t);
        int b = Math.round(Color.blue(from) * (1f - t) + Color.blue(to) * t);
        return Color.rgb(r, g, b);
    }

    static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    static int lighten(int color, float amount) {
        return mixColor(color, 0xFFFFFFFF, amount);
    }

    static int profileColor(int index) {
        return ramp(index / 3f);
    }

    static int profileColor(String name) {
        if ("QUIET".equals(name)) return profileColor(0);
        if ("PERFORMANCE".equals(name)) return profileColor(2);
        if ("EXTREME".equals(name)) return profileColor(3);
        if ("BALANCED".equals(name)) return profileColor(1);
        return AMBER;
    }

    private static int fanColor(int index) {
        return ramp(index / 4f);
    }

    private static int governorColor(String governor) {
        if (governor == null) return ramp(0.5f);
        String g = governor.toLowerCase(Locale.US);
        if (g.contains("power")) return ramp(0f);
        if (g.contains("conserv")) return ramp(0.18f);
        if (g.contains("sched")) return ramp(0.42f);
        if (g.contains("walt")) return ramp(0.52f);
        if (g.contains("interactive")) return ramp(0.68f);
        if (g.contains("performance")) return ramp(1f);
        return ramp(0.5f);
    }

    private static PerformanceTunerActivity instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> focusables = new ArrayList<>();
    private final List<View> headerFocus = new ArrayList<>();
    private ExecutorService io;
    private Prefs prefs;
    private PerfTuner.State state;
    private boolean updating;
    private boolean interacting;
    private boolean destroyed;
    private boolean refreshInFlight;
    private boolean resumed;
    private String selectedProfile = "";
    private int fanPercent = 50;
    private long lastNavAt;
    private long lastStickNavAt;
    private long lastDpadKeyAt;
    private long lastFocusMoveAt;
    private boolean stickNavEngaged;
    private final Set<Integer> heldDpad = new HashSet<>();
    private int pendingDpadDir = -1;
    private Runnable pendingDpadTask;
    private long lastSliderHapticAt;
    private int cardFocusIndex = -1;
    private final long[] sliderStickAt = {0L};
    private boolean rightStickHeld;
    private float rightStickX;
    private SeekBar rightStickBar;
    private final Runnable rightStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (!rightStickHeld) return;
            View focus = getCurrentFocus();
            if (!(focus instanceof SeekBar)) {
                stopRightStickRepeat();
                return;
            }
            SeekBar bar = (SeekBar) focus;
            if (!SliderStick.active(rightStickX) || !bar.isEnabled()) {
                stopRightStickRepeat();
                return;
            }
            SliderStick.adjust(bar, rightStickX, sliderStickAt, bar);
            handler.postDelayed(this, 16L);
        }
    };

    private TextView profileStatus;
    private TextView rootStatus;
    private TextView footerRoot;
    private TextView modeStatus;
    private TextView holdChip;
    private LinearLayout appSelector;
    private ImageView appSelectorIcon;
    private TextView appSelectorLabel;
    private TextView resetAppChip;
    private FrameLayout rootView;
    private AppPickerStripView appPicker;
    private TuneStore tuneStore;
    private TuneConfig contextConfig;
    private String selectedAppPkg = "";
    private TextView tempLine;
    private TextView fanDetail;
    private SeekBar fanSeek;
    private TextView fanValue;
    private final TextView[] profileChips = new TextView[4];
    private final String[] profileNames = {"QUIET", "BALANCED", "PERFORMANCE", "EXTREME"};
    private final TextView[] fanChips = new TextView[5];
    private CpuCard efficiencyCard;
    private CpuCard performanceCard;
    private GpuCard gpuCard;
    private LinearLayout fanCardView;
    private LinearLayout thermalCardView;
    private ThermalBoardView thermalView;
    private TextView demoChip;
    private boolean demoActive;
    private long demoStart;
    private final Runnable fanApply = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            PerfTuner.setFanManual(fanPercent);
            if (fanDetail != null) {
                fanDetail.setText("Applying manual fan…");
            }
        }
    };
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed || destroyed) return;
            refresh();
        }
    };

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            if (!demoActive || thermalView == null) return;
            long t = android.os.SystemClock.uptimeMillis() - demoStart;
            float p = (t % 16000L) / 16000f;
            float heat;
            if (p < 0.12f) {
                heat = 0.08f + 0.07f * (p / 0.12f);
            } else if (p < 0.55f) {
                float q = (p - 0.12f) / 0.43f;
                heat = 0.15f + 0.78f * (q * q * (3f - 2f * q));
            } else if (p < 0.70f) {
                heat = 0.93f + 0.05f * (float) Math.sin((p - 0.70f) * 90f);
            } else {
                float q = (p - 0.70f) / 0.30f;
                heat = 0.93f * (1f - q * q * (3f - 2f * q));
            }
            int duty = Math.round(8f + 92f * heat);
            int rpm = Math.round(900f + 8400f * heat);
            float cpu = 33f + 52f * heat;
            float gpu = 31f + 48f * heat;
            thermalView.setTelemetry(rpm, duty, cpu, gpu);
            if (tempLine != null) {
                tempLine.setTextColor(accent);
                tempLine.setText("DEMO  ·  CPU " + Math.round(cpu) + "°C  ·  GPU "
                        + Math.round(gpu) + "°C  ·  FAN " + duty + "%");
            }
            handler.postDelayed(this, 50L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        instance = this;
        io = Executors.newSingleThreadExecutor();
        prefs = new Prefs(this);
        tuneStore = new TuneStore(this);
        PerAppTuneService.setTunerVisible(true);
        if (!prefs.perfHoldMigrated()) {
            prefs.setPerfHoldEnabled(true);
            prefs.setPerfHoldMigrated(true);
        }
        buildUi();
        hideBars();

        // Fill every slider, chip and toggle from local sysfs/settings on the
        // first frame. The privileged read/verify then happens on the worker
        // thread instead of leaving the controls blank for a second or two.
        PerfTuner.State local = PerfTuner.readStateLocal(this);
        TuneConfig localConfig = tuneStore.globalOrInit(local, prefs);
        contextConfig = localConfig != null ? localConfig : TuneConfig.fromState(local);
        selectedAppPkg = "";
        state = local;
        updateAppSelector();
        applyState(local, false);
        focusFirst();

        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean root = PerfTuner.prepare();
                PerfTuner.State snapshot = PerfTuner.readState(PerformanceTunerActivity.this);
                final TuneConfig global = tuneStore.globalOrInit(snapshot, prefs);
                if (root && global != null) {
                    syncHoldTargets(global);
                    global.apply(PerformanceTunerActivity.this, snapshot);
                    snapshot = PerfTuner.readState(PerformanceTunerActivity.this);
                }
                final TuneConfig initial = global == null
                        ? TuneConfig.fromState(snapshot) : global;
                final PerfTuner.State shown = snapshot;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        contextConfig = initial;
                        selectedAppPkg = "";
                        state = shown;
                        updateAppSelector();
                        applyState(shown, true);
                        if (root) {
                            maybeResumeHold();
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        PerAppTuneService.setTunerVisible(true);
        hideBars();
        hidePerformanceOverlay();
        maybeResumeHold();
        scheduleRefresh(500L);
    }

    @Override
    protected void onPause() {
        resumed = false;
        heldDpad.clear();
        pendingDpadDir = -1;
        if (pendingDpadTask != null) {
            handler.removeCallbacks(pendingDpadTask);
            pendingDpadTask = null;
        }
        if (demoActive) stopThermalDemo();
        stopRightStickRepeat();
        handler.removeCallbacks(refreshTask);
        if (appPicker != null) {
            appPicker.dismiss();
            appPicker = null;
        }
        PerAppTuneService.setTunerVisible(false);
        if (tuneStore != null && tuneStore.hasOverrides()) {
            PerAppTuneService.refresh(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        PerAppTuneService.setTunerVisible(false);
        handler.removeCallbacksAndMessages(null);
        if (io != null) io.shutdownNow();
        PerfTuner.Root.close();
        if (instance == this) instance = null;
        super.onDestroy();
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
    // UI construction
    // ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        card.setPadding(dp(22), dp(14), dp(22), dp(12));
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

        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        profileLp.topMargin = dp(8);
        column.addView(buildProfiles(), profileLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBaselineAligned(false);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(8);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setBaselineAligned(false);
        body.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setBaselineAligned(false);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bottomLp.topMargin = dp(7);
        body.addView(bottomRow, bottomLp);

        DeviceProfile profile = DeviceProfile.get(this);
        DeviceProfile.Cluster eff = profile.efficiency();
        DeviceProfile.Cluster perf = profile.performance();
        efficiencyCard = new CpuCard(topRow, 0,
                eff == null || eff.label.isEmpty() ? "EFFICIENCY" : eff.label,
                clusterLabel(eff));
        performanceCard = new CpuCard(bottomRow, 0,
                perf == null || perf.label.isEmpty() ? "PERFORMANCE" : perf.label,
                clusterLabel(perf));
        thermalCard(topRow);
        gpuCard = new GpuCard(bottomRow);

        boolean narrow = Ui.columns(this, 3) == 1;
        boolean shortScreen = Ui.layoutHeightDp(this) < 440;
        if (narrow || shortScreen) {
            // Scroll instead of squeezing the cards on short or narrow panels.
            if (narrow) {
                topRow.setOrientation(LinearLayout.VERTICAL);
                bottomRow.setOrientation(LinearLayout.VERTICAL);
            }
            int cardHeight = narrow ? 236 : 190;
            topRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bottomRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            efficiencyCard.card.setLayoutParams(stackedCard(narrow ? 0 : 0, cardHeight));
            thermalCardView.setLayoutParams(stackedCard(narrow ? 7 : 0, cardHeight));
            performanceCard.card.setLayoutParams(stackedCard(7, cardHeight));
            gpuCard.card.setLayoutParams(stackedCard(7, cardHeight));
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(body, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(scroll, bodyLp);
        } else {
            LinearLayout.LayoutParams halfLeft = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            halfLeft.rightMargin = dp(4);
            LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            halfRight.leftMargin = dp(4);
            efficiencyCard.card.setLayoutParams(halfLeft);
            thermalCardView.setLayoutParams(halfRight);
            performanceCard.card.setLayoutParams(halfLeft);
            gpuCard.card.setLayoutParams(halfRight);
            column.addView(body, bodyLp);
        }

        column.addView(buildFooter(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateHoldChip();
    }

    private LinearLayout.LayoutParams stackedCard(int topMarginDp, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private View buildHeader() {
        // Horizontal row so the centred title can never be covered by the
        // status chips: the title takes the space the two side groups leave.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("PERFORMANCE TUNER", 19f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setAutoSizeTextTypeUniformWithConfiguration(
                11, 19, 1, TypedValue.COMPLEX_UNIT_SP);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = text(DeviceProfile.get(this).name.toUpperCase()
                + "  ·  CPU / GPU / THERMALS", 9.5f, MUTED, false);
        subtitle.setLetterSpacing(0.13f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setSingleLine(true);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout leftControls = new LinearLayout(this);
        leftControls.setOrientation(LinearLayout.HORIZONTAL);
        leftControls.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(leftControls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        appSelector = new LinearLayout(this);
        appSelector.setOrientation(LinearLayout.HORIZONTAL);
        appSelector.setGravity(Gravity.CENTER_VERTICAL);
        appSelector.setPadding(dp(7), dp(4), dp(10), dp(4));
        appSelector.setBackground(Ui.rounded(0x1EFFFFFF, dp(11),
                0x33FFFFFF, dp(1)));
        appSelector.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openAppPicker();
            }
        });
        appSelectorIcon = new ImageView(this);
        appSelectorIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appSelector.addView(appSelectorIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        appSelectorLabel = text("ALL APPS", 9f, TEXT, true);
        if (appSelectorLabel instanceof LTextView) ((LTextView) appSelectorLabel).setLocalize(false);
        appSelectorLabel.setSingleLine(true);
        appSelectorLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        appSelectorLabel.setMaxWidth(dp(112));
        appSelectorLabel.setPadding(dp(6), 0, 0, 0);
        appSelector.addView(appSelectorLabel);
        leftControls.addView(appSelector);
        addFocusable(appSelector, true);
        headerFocus.add(appSelector);

        resetAppChip = text("USE GLOBAL", 8.5f, 0xFFFFC857, true);
        resetAppChip.setLetterSpacing(0.06f);
        resetAppChip.setGravity(Gravity.CENTER);
        resetAppChip.setPadding(dp(8), dp(5), dp(8), dp(5));
        resetAppChip.setBackground(Ui.rounded(0x22FFC857, dp(10),
                0x55FFC857, dp(1)));
        resetAppChip.setVisibility(View.GONE);
        resetAppChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                resetSelectedApp();
            }
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.leftMargin = dp(6);
        leftControls.addView(resetAppChip, resetLp);
        addFocusable(resetAppChip, true);
        headerFocus.add(resetAppChip);

        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = dp(12);
        titlesLp.rightMargin = dp(12);
        header.addView(titles, titlesLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        profileStatus = text("READING…", 9.5f, accent, true);
        profileStatus.setLetterSpacing(0.12f);
        profileStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
        profileStatus.setBackground(Ui.rounded(ThemeColors.withAlpha(0x1A), dp(11),
                ThemeColors.withAlpha(0x44), dp(1)));
        LinearLayout.LayoutParams pslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pslp.rightMargin = dp(8);
        controls.addView(profileStatus, pslp);

        TextView stock = text("STOCK", 9.5f, 0xFFFFC36A, true);
        stock.setLetterSpacing(0.10f);
        stock.setGravity(Gravity.CENTER);
        stock.setPadding(dp(11), dp(5), dp(11), dp(5));
        stock.setBackground(Ui.rounded(0x22FFC36A, dp(11),
                0x55FFC36A, dp(1)));
        stock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                applyStock();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(8);
        controls.addView(stock, slp);
        addFocusable(stock, true);
        headerFocus.add(stock);

        holdChip = text("HOLD ON", 9.5f, GREEN, true);
        holdChip.setLetterSpacing(0.08f);
        holdChip.setGravity(Gravity.CENTER);
        holdChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        holdChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                boolean next = !prefs.perfHoldEnabled();
                prefs.setPerfHoldEnabled(next);
                if (next) startHold();
                else {
                    PerfTuneService.stop(PerformanceTunerActivity.this);
                    updateHoldChip();
                }
            }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = dp(8);
        controls.addView(holdChip, hlp);
        addFocusable(holdChip, true);
        headerFocus.add(holdChip);

        TextView close = text("\u00D7", 22f, RAMP_RED, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), 0, dp(3), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                finishAnimated();
            }
        });
        controls.addView(close);
        addFocusable(close, true);
        headerFocus.add(close);

        // On narrow panels (4:3 included) the title and the chips do not fit on
        // one line, so the header becomes two rows.
        if (Ui.columns(this, 3) == 1 || Ui.widthDp(this) < 640) {
            header.removeAllViews();
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.START);
            header.addView(titles, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(4);
            header.addView(row, rowLp);
            row.addView(leftControls, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(controls);
        }

        return header;
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
        if (state == null || tuneStore == null) return;
        selectedAppPkg = pkg == null ? "" : pkg;
        TuneConfig cfg = selectedAppPkg.isEmpty()
                ? tuneStore.globalOrInit(state, prefs) : tuneStore.app(selectedAppPkg);
        if (cfg == null) {
            TuneConfig global = tuneStore.globalOrInit(state, prefs);
            cfg = global == null ? TuneConfig.fromState(state) : global.copy();
        }
        contextConfig = cfg;
        selectedProfile = cfg.profile == null || cfg.profile.isEmpty()
                ? "CUSTOM" : cfg.profile;
        updateAppSelector();
        markProfileChips();
        updateProfileStatus();
        applyContextConfig(cfg);
        if (appSelector != null) {
            appSelector.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) appSelector.requestFocus();
                }
            });
        }
    }

    private void applyContextConfig(final TuneConfig cfg) {
        if (cfg == null) return;
        syncHoldTargets(cfg);
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean root = PerfTuner.prepare();
                PerfTuner.State snapshot = PerfTuner.readState(PerformanceTunerActivity.this);
                if (root) {
                    cfg.apply(PerformanceTunerActivity.this, snapshot);
                    snapshot = PerfTuner.readState(PerformanceTunerActivity.this);
                }
                final PerfTuner.State shown = snapshot;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        state = shown;
                        applyState(shown);
                        setRootState(root);
                        updateHoldChip();
                    }
                });
            }
        });
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
                        pm.getApplicationIcon(selectedAppPkg), dp(32));
            } catch (Throwable ignored) {
                label = selectedAppPkg;
            }
        }
        if (bitmap == null) {
            try {
                bitmap = Apps.drawableToBitmap(getDrawable(R.mipmap.ic_launcher), dp(32));
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
        if (selectedAppPkg.isEmpty() || tuneStore == null) return;
        String old = selectedAppPkg;
        tuneStore.removeApp(old);
        if (tuneStore.hasOverrides()) PerAppTuneService.refresh(this);
        else PerAppTuneService.stop(this);
        LToast.makeText(this, "Using global tuner settings", Toast.LENGTH_SHORT).show();
        selectContext("");
    }

    private void saveCurrentConfig() {
        if (state == null || contextConfig == null || tuneStore == null) return;
        TuneConfig c = contextConfig;
        if (state.efficiency != null) {
            c.p0Max = state.efficiency.max;
            c.p0Min = state.efficiency.min;
            c.p0Gov = state.efficiency.governor == null ? "walt" : state.efficiency.governor;
        }
        if (state.performance != null) {
            c.p6Max = state.performance.max;
            c.p6Min = state.performance.min;
            c.p6Gov = state.performance.governor == null ? "walt" : state.performance.governor;
        }
        if (state.gpu != null) {
            c.gpuMax = state.gpu.max;
            c.gpuMin = state.gpu.min;
        }
        c.performanceMode = state.performanceMode;
        c.profile = selectedProfile == null || selectedProfile.isEmpty()
                ? "CUSTOM" : selectedProfile;
        if (selectedAppPkg.isEmpty()) {
            tuneStore.setGlobal(c);
        } else {
            tuneStore.setApp(selectedAppPkg, c);
            PerAppTuneService.start(this);
        }
        syncHoldTargets(c);
    }

    private void syncHoldTargets(TuneConfig c) {
        if (c == null || prefs == null) return;
        if (prefs.perfHoldEnabled()) {
            prefs.setTuneTargets(c.p0Max, c.p0Min, c.p0Gov,
                    c.p6Max, c.p6Min, c.p6Gov, c.gpuMax, c.gpuMin);
            if (!PerfTuneService.isRunning()) PerfTuneService.start(this);
        }
    }

    private void updateProfileStatus() {
        if (profileStatus == null) return;
        String name = selectedProfile == null || selectedProfile.isEmpty()
                ? "CUSTOM" : selectedProfile;
        if ("CUSTOM".equals(name) || "STOCK".equals(name)) {
            profileStatus.setText(name);
        } else {
            profileStatus.setText(name + " APPLIED");
        }
        styleProfileStatus(name);
    }

    private View buildProfiles() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(10);
        wrap.setLayoutParams(wlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(row);

        String[] subs = {
                "2.23 / 1.96 GHz",
                "2.75 / 3.28 GHz",
                "3.53 / 4.32 GHz",
                "MAX / CPU"
        };
        for (int i = 0; i < profileNames.length; i++) {
            final String name = profileNames[i];
            TextView chip = text(profileNames[i] + "\n" + subs[i], 10f, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setLineSpacing(dp(2), 1f);
            chip.setPadding(dp(4), dp(7), dp(4), dp(7));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    applyProfile(name);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(48), 1f);
            lp.rightMargin = i == profileNames.length - 1 ? 0 : dp(7);
            row.addView(chip, lp);
            profileChips[i] = chip;
            addFocusable(chip, true);
            styleProfileChip(chip, i, false, false);
        }
        return wrap;
    }

    private void thermalCard(LinearLayout parent) {
        LinearLayout card = section("THERMALS", "CPU and RAM board");
        thermalCardView = card;
        parent.addView(card);
        FrameLayout holder = new FrameLayout(this);
        card.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        thermalView = new ThermalBoardView(this);
        holder.addView(thermalView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        demoChip = text("DEMO", 8.5f, accent, true);
        demoChip.setGravity(Gravity.CENTER);
        demoChip.setPadding(dp(9), dp(4), dp(9), dp(4));
        demoChip.setBackground(Ui.rounded(ThemeColors.withAlpha(0x1A), dp(8),
                ThemeColors.withAlpha(0x55), dp(1)));
        demoChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                if (demoActive) stopThermalDemo(); else startThermalDemo();
            }
        });
        addFocusable(demoChip, true);
        FrameLayout.LayoutParams demoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        demoLp.topMargin = dp(2);
        demoLp.rightMargin = dp(2);
        holder.addView(demoChip, demoLp);
        tempLine = text("CPU —  ·  GPU —", 10f, MUTED, false);
        tempLine.setPadding(0, dp(4), 0, 0);
        card.addView(tempLine);
    }

    private void fanCard(LinearLayout parent) {
        LinearLayout card = section("FAN", "Mode and precise manual duty");
        fanCardView = card;
        parent.addView(card);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < PerfTuner.FAN_MODE_LABELS.length; i++) {
            final int value = PerfTuner.FAN_MODE_VALUES[i];
            TextView chip = modeChip(PerfTuner.FAN_MODE_LABELS[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    selectFanMode(value);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(30), 1f);
            lp.rightMargin = i == PerfTuner.FAN_MODE_LABELS.length - 1 ? 0 : dp(5);
            modes.addView(chip, lp);
            fanChips[i] = chip;
            addFocusable(chip, true);
        }
        card.addView(modes);

        LinearLayout dutyRow = new LinearLayout(this);
        dutyRow.setOrientation(LinearLayout.HORIZONTAL);
        dutyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        drlp.topMargin = dp(7);
        card.addView(dutyRow, drlp);
        TextView dutyLabel = text("DUTY", 10f, MUTED, true);
        dutyLabel.setLetterSpacing(0.10f);
        dutyRow.addView(dutyLabel);

        fanSeek = seekBar(90);
        fanSeek.setPadding(dp(12), 0, dp(12), 0);
        dutyRow.addView(fanSeek, new LinearLayout.LayoutParams(
                0, dp(32), 1f));
        addFocusable(fanSeek, true);
        fanValue = text("50%", 11f, accent, true);
        fanValue.setGravity(Gravity.END);
        fanValue.setMinWidth(dp(54));
        dutyRow.addView(fanValue);

        fanDetail = text("—", 10f, MUTED, false);
        fanDetail.setPadding(0, dp(7), 0, 0);
        card.addView(fanDetail);

        tempLine = text("CPU —  ·  GPU —", 10f, MUTED, false);
        tempLine.setPadding(0, dp(3), 0, 0);
        card.addView(tempLine);

        fanSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updating) return;
                sliderHaptic(fanSeek);
                fanPercent = progress + 10;
                fanValue.setText(fanPercent + "%");
                fanValue.setTextColor(tintSeekBar(fanSeek,
                        progress, fanSeek.getMax()));
                selectFanChip(PerfTuner.FAN_MANUAL);
                markCustom();
                handler.removeCallbacks(fanApply);
                handler.postDelayed(fanApply, 90L);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                interacting = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                interacting = false;
                PerfTuner.setFanManual(fanPercent);
                scheduleRefresh(350L);
            }
        });
    }

    private void infoCard(LinearLayout parent) {
        LinearLayout card = section("STATUS", "Root and thermal state");
        rootStatus = text("CHECKING ROOT…", 11f, AMBER, true);
        rootStatus.setPadding(0, dp(3), 0, 0);
        card.addView(rootStatus);
        modeStatus = text("MODE —", 10f, MUTED, false);
        modeStatus.setPadding(0, dp(3), 0, 0);
        card.addView(modeStatus);
        TextView hint = text("Tuning reset on reboot. Values are limited to the "
                + "frequencies and governors exported by the kernel.", 10f, MUTED, false);
        hint.setPadding(0, dp(5), 0, 0);
        card.addView(hint);
        rootStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                retryRoot();
            }
        });
        addFocusable(rootStatus, true);
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hints = text("D-PAD/L-STICK move  ·  R-STICK adjust (hold = fast)  ·  L1/R1 card  ·  A select  ·  B close",
                9f, 0x66FFFFFF, false);
        footer.addView(hints, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        modeStatus = text("MODE —", 8.5f, 0x88FFFFFF, false);
        modeStatus.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.rightMargin = dp(12);
        footer.addView(modeStatus, mlp);

        rootStatus = text("CHECKING ROOT", 9f, AMBER, true);
        rootStatus.setLetterSpacing(0.08f);
        rootStatus.setPadding(dp(8), dp(4), dp(8), dp(4));
        rootStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                retryRoot();
            }
        });
        footer.addView(rootStatus);
        addFocusable(rootStatus, true);
        footerRoot = rootStatus;
        return footer;
    }

    private LinearLayout section(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(7), dp(12), dp(7));
        card.setBackground(Ui.rounded(0x300B0E14, dp(16),
                0x2EFFFFFF, dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 10f, accent, true);
        t.setLetterSpacing(0.13f);
        titleRow.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sub = text(subtitle, 9f, MUTED, false);
        titleRow.addView(sub);
        card.addView(titleRow);
        return card;
    }

    private TextView modeChip(String label) {
        TextView chip = text(label, 9f, TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.rounded(0x22FFFFFF, dp(9),
                0x30FFFFFF, dp(1)));
        return chip;
    }

    private void styleProfileChip(TextView chip, int index, boolean selected,
                                  boolean focused) {
        int color = profileColor(index);
        int light = lighten(color, 0.36f);
        chip.setTextColor(selected ? DARK_TEXT : light);
        if (selected) {
            chip.setBackground(Ui.rounded(color, dp(13), 0xFFFFFFFF, dp(1)));
        } else {
            chip.setBackground(Ui.rounded(
                    withAlpha(color, focused ? 0x48 : 0x2C), dp(13),
                    withAlpha(focused ? light : color, focused ? 0xE0 : 0x88),
                    dp(1)));
        }
    }

    private void styleFanChip(TextView chip, int index, boolean selected) {
        int color = fanColor(index);
        chip.setTextColor(selected ? DARK_TEXT : lighten(color, 0.34f));
        chip.setBackground(Ui.rounded(
                selected ? color : withAlpha(color, 0x22), dp(9),
                selected ? 0xFFFFFFFF : withAlpha(color, 0x70), dp(1)));
    }

    private void styleProfileStatus(String name) {
        if (profileStatus == null) return;
        int color = profileColor(name);
        profileStatus.setTextColor(lighten(color, 0.28f));
        profileStatus.setBackground(Ui.rounded(withAlpha(color, 0x24),
                dp(11), withAlpha(color, 0x90), dp(1)));
    }

    // ------------------------------------------------------------------
    // CPU card
    // ------------------------------------------------------------------

    private static String clusterLabel(DeviceProfile.Cluster c) {
        if (c == null) return "";
        int n = c.cores();
        String range = c.cpus.length > 0
                ? "C" + c.cpus[0] + "–C" + c.cpus[c.cpus.length - 1] : "";
        return n + " core" + (n == 1 ? "" : "s")
                + (range.isEmpty() ? "" : " · " + range);
    }

    private final class CpuCard {
        final int cluster;
        final LinearLayout card;
        final TextView current;
        final SeekBar maxSeek;
        final SeekBar minSeek;
        final TextView maxValue;
        final TextView minValue;
        final TextView governorValue;
        final LinearLayout governorRow;
        final List<TextView> governorChips = new ArrayList<>();
        PerfTuner.Cpu data;
        int[] minFreqs = new int[0];

        CpuCard(LinearLayout parent, int cluster, String title, String subtitle) {
            this.cluster = cluster;
            card = section(title, subtitle);
            parent.addView(card);

            current = text("—", 9f, MUTED, false);
            card.addView(current);

            maxSeek = seekBar(1);
            maxValue = text("—", 10f, accent, true);
            addSliderRow(card, "MAX", maxSeek, maxValue, dp(8));
            addFocusable(maxSeek, true);

            minSeek = seekBar(1);
            minValue = text("—", 10f, accent, true);
            addSliderRow(card, "MIN", minSeek, minValue, dp(7));
            addFocusable(minSeek, true);

            LinearLayout govRow = new LinearLayout(PerformanceTunerActivity.this);
            govRow.setOrientation(LinearLayout.HORIZONTAL);
            govRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams grlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            grlp.topMargin = dp(6);
            card.addView(govRow, grlp);
            TextView gl = text("GOV", 9f, MUTED, true);
            gl.setLetterSpacing(0.10f);
            gl.setMinWidth(dp(34));
            govRow.addView(gl);
            governorRow = new LinearLayout(PerformanceTunerActivity.this);
            governorRow.setOrientation(LinearLayout.HORIZONTAL);
            govRow.addView(governorRow, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            governorValue = text("", 9f, MUTED, false);
            // The selected governor chip already names the active governor.

            maxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updating) return;
                    sliderHaptic(card);
                    int max = data.freqs[progress];
                    int min = minFreqs.length == 0 ? data.floorMin
                            : minFreqs[Math.min(minSeek.getProgress(),
                            minFreqs.length - 1)];
                    if (max < min) {
                        // Clamp the slider being moved. Moving the opposite
                        // slider here makes a single controller nudge look
                        // like both MAX and MIN changed at once.
                        int idx = PerfTuner.closestIndex(data.freqs, min);
                        maxSeek.setProgress(idx);
                        max = data.freqs[idx];
                    }
                    data.max = max;
                    data.min = min;
                    updateSliderLabels();
                    markCustom();
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            PerfTuner.setCpu(data, data.max, data.min, data.governor);
                        }
                    });
                    startHold();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    interacting = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    interacting = false;
                    scheduleRefresh(300L);
                }
            });

            minSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updating) return;
                    if (minFreqs.length == 0) return;
                    sliderHaptic(card);
                    int min = minFreqs[progress];
                    int max = data.freqs[Math.min(maxSeek.getProgress(),
                            data.freqs.length - 1)];
                    if (min > max) {
                        // Keep MIN from pushing MAX; clamp the slider that is
                        // actually being moved so each control stays independent.
                        int idx = PerfTuner.closestIndex(minFreqs, max);
                        minSeek.setProgress(idx);
                        min = minFreqs[idx];
                    }
                    data.min = min;
                    data.max = max;
                    updateSliderLabels();
                    markCustom();
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            PerfTuner.setCpu(data, data.max, data.min, data.governor);
                        }
                    });
                    startHold();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    interacting = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    interacting = false;
                    scheduleRefresh(300L);
                }
            });
        }

        LinearLayout context() {
            return card;
        }

        void bind(PerfTuner.Cpu cpu, int targetMax, int targetMin, String governor) {
            data = cpu;
            if (cpu == null || cpu.freqs.length == 0) return;
            minFreqs = new int[cpu.freqs.length];
            int count = 0;
            for (int f : cpu.freqs) {
                if (f >= cpu.floorMin) minFreqs[count++] = f;
            }
            minFreqs = java.util.Arrays.copyOf(minFreqs, count);
            if (minFreqs.length == 0) minFreqs = new int[]{cpu.freqs[0]};

            updating = true;
            maxSeek.setMax(Math.max(0, cpu.freqs.length - 1));
            minSeek.setMax(Math.max(0, minFreqs.length - 1));
            int shownMax = targetMax > 0 ? targetMax : cpu.max;
            int shownMin = targetMin > 0 ? targetMin : cpu.min;
            maxSeek.setProgress(PerfTuner.closestIndex(cpu.freqs, shownMax));
            minSeek.setProgress(PerfTuner.closestIndex(minFreqs, shownMin));
            data.max = shownMax;
            data.min = shownMin;
            data.governor = governor;
            current.setText("now " + ghz(cpu.cur) + "  ·  " + cpu.cores + " cores");
            updateSliderLabels();
            buildGovernorChips(cpu, governor);
            updating = false;
        }

        void updateSliderLabels() {
            if (data == null) return;
            maxValue.setText(ghz(data.freqs[Math.min(maxSeek.getProgress(),
                    data.freqs.length - 1)]));
            maxValue.setTextColor(tintSeekBar(maxSeek,
                    maxSeek.getProgress(), maxSeek.getMax()));
            if (minFreqs.length > 0) {
                minValue.setText(ghz(minFreqs[Math.min(minSeek.getProgress(),
                        minFreqs.length - 1)]));
                minValue.setTextColor(tintSeekBar(minSeek,
                        minSeek.getProgress(), minSeek.getMax()));
            }
        }

        void buildGovernorChips(PerfTuner.Cpu cpu, String selectedGovernor) {
            if (governorRow.getChildCount() > 0) {
                // Only rebuild when the governor list changes.
                if (governorChips.size() == cpu.governors.length) {
                    updateGovernorStyles(selectedGovernor);
                    return;
                }
                governorChips.clear();
                governorRow.removeAllViews();
            }
            for (final String gov : cpu.governors) {
                TextView chip = modeChip(shortGovernor(gov));
                chip.setTag(governorColor(gov));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
                chip.setPadding(dp(4), 0, dp(4), 0);
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Haptics.click(v);
                        data.governor = gov;
                        updateGovernorStyles(gov);
                        markCustom();
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                PerfTuner.setCpu(data, data.max, data.min, gov);
                            }
                        });
                        startHold();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, dp(23), 1f);
                lp.rightMargin = dp(3);
                governorRow.addView(chip, lp);
                governorChips.add(chip);
                addFocusable(chip, true);
            }
            updateGovernorStyles(selectedGovernor);
        }

        View selectedGovernorChip() {
            if (governorChips.isEmpty()) return null;
            if (data != null && data.governor != null) {
                String want = shortGovernor(data.governor);
                for (TextView chip : governorChips) {
                    if (want.contentEquals(chip.getText())) return chip;
                }
            }
            return governorChips.get(0);
        }

        void updateGovernorStyles(String selected) {
            for (TextView chip : governorChips) {
                boolean is = selected != null
                        && shortGovernor(selected).contentEquals(chip.getText());
                int color = chip.getTag() instanceof Integer
                        ? (Integer) chip.getTag() : ramp(0.5f);
                chip.setTextColor(is ? DARK_TEXT : lighten(color, 0.34f));
                chip.setBackground(Ui.rounded(
                        is ? color : withAlpha(color, 0x1E), dp(9),
                        is ? 0xFFFFFFFF : withAlpha(color, 0x60), dp(1)));
            }
            governorValue.setText(selected == null ? "" : selected);
        }
    }

    // ------------------------------------------------------------------
    // GPU card
    // ------------------------------------------------------------------

    private final class GpuCard {
        final LinearLayout card;
        final TextView current;
        final SeekBar maxSeek;
        final SeekBar minSeek;
        final TextView maxValue;
        final TextView minValue;
        PerfTuner.Gpu data;

        GpuCard(LinearLayout parent) {
            card = section("GPU", "Adreno · devfreq clock");
            parent.addView(card);

            current = text("—", 9f, MUTED, false);
            card.addView(current);

            maxSeek = seekBar(1);
            maxValue = text("—", 10f, accent, true);
            addSliderRow(card, "MAX", maxSeek, maxValue, dp(8));
            addFocusable(maxSeek, true);

            minSeek = seekBar(1);
            minValue = text("—", 10f, accent, true);
            addSliderRow(card, "MIN", minSeek, minValue, dp(7));
            addFocusable(minSeek, true);

            TextView gpuGov = text("GOVERNOR  msm-adreno-tz", 8.5f, MUTED, false);
            gpuGov.setLetterSpacing(0.06f);
            gpuGov.setPadding(0, dp(6), 0, 0);
            card.addView(gpuGov);

            maxSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updating) return;
                    sliderHaptic(maxSeek);
                    int max = data.freqs[progress];
                    int min = data.freqs[Math.min(minSeek.getProgress(),
                            data.freqs.length - 1)];
                    if (max < min) {
                        // Clamp MAX at MIN without moving the MIN slider.
                        int idx = PerfTuner.closestIndex(data.freqs, min);
                        maxSeek.setProgress(idx);
                        max = data.freqs[idx];
                    }
                    data.max = max;
                    data.min = min;
                    updateSliderLabels();
                    markCustom();
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            PerfTuner.setGpu(data, data.max, data.min);
                        }
                    });
                    startHold();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    interacting = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    interacting = false;
                    scheduleRefresh(300L);
                }
            });

            minSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || updating) return;
                    sliderHaptic(minSeek);
                    int min = data.freqs[progress];
                    int max = data.freqs[Math.min(maxSeek.getProgress(),
                            data.freqs.length - 1)];
                    if (min > max) {
                        // Clamp the moved slider at MAX rather than dragging
                        // MAX along with it.
                        int idx = PerfTuner.closestIndex(data.freqs, max);
                        minSeek.setProgress(idx);
                        min = data.freqs[idx];
                    }
                    data.min = min;
                    data.max = max;
                    updateSliderLabels();
                    markCustom();
                    io.execute(new Runnable() {
                        @Override
                        public void run() {
                            PerfTuner.setGpu(data, data.max, data.min);
                        }
                    });
                    startHold();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    interacting = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    interacting = false;
                    scheduleRefresh(300L);
                }
            });
        }

        void bind(PerfTuner.Gpu gpu, int targetMax, int targetMin) {
            data = gpu;
            if (gpu == null || gpu.freqs.length == 0) return;
            updating = true;
            maxSeek.setMax(Math.max(0, gpu.freqs.length - 1));
            minSeek.setMax(Math.max(0, gpu.freqs.length - 1));
            int shownMax = targetMax > 0 ? targetMax : gpu.max;
            int shownMin = targetMin > 0 ? targetMin : gpu.min;
            maxSeek.setProgress(PerfTuner.closestIndex(gpu.freqs, shownMax));
            minSeek.setProgress(PerfTuner.closestIndex(gpu.freqs, shownMin));
            current.setText("now " + mhz(gpu.cur) + "  ·  " + gpu.governor);
            updateSliderLabels();
            updating = false;
        }

        void updateSliderLabels() {
            if (data == null) return;
            maxValue.setText(mhz(data.freqs[Math.min(maxSeek.getProgress(),
                    data.freqs.length - 1)]));
            maxValue.setTextColor(tintSeekBar(maxSeek,
                    maxSeek.getProgress(), maxSeek.getMax()));
            minValue.setText(mhz(data.freqs[Math.min(minSeek.getProgress(),
                    data.freqs.length - 1)]));
            minValue.setTextColor(tintSeekBar(minSeek,
                    minSeek.getProgress(), minSeek.getMax()));
        }
    }

    // ------------------------------------------------------------------
    // State and actions
    // ------------------------------------------------------------------

    private void applyState(PerfTuner.State snapshot) {
        applyState(snapshot, true);
    }

    private void applyState(PerfTuner.State snapshot, boolean updateRoot) {
        if (snapshot == null) return;
        if (contextConfig == null) {
            contextConfig = TuneConfig.fromState(snapshot);
        }
        TuneConfig cfg = contextConfig;
        updating = true;
        if (efficiencyCard != null) {
            efficiencyCard.bind(snapshot.efficiency,
                    cfg.p0Max, cfg.p0Min, cfg.p0Gov);
        }
        if (performanceCard != null) {
            performanceCard.bind(snapshot.performance,
                    cfg.p6Max, cfg.p6Min, cfg.p6Gov);
        }
        if (gpuCard != null) {
            gpuCard.bind(snapshot.gpu, cfg.gpuMax, cfg.gpuMin);
        }
        if (thermalView != null && !demoActive) {
            thermalView.setTelemetry(0, 0, snapshot.cpuTemp, snapshot.gpuTemp);
        }
        updating = false;

        selectedProfile = cfg.profile == null || cfg.profile.isEmpty()
                ? "CUSTOM" : cfg.profile;
        markProfileChips();
        updateProfileStatus();
        if (!demoActive) {
            if (snapshot.cpuTemp > 0 || snapshot.gpuTemp > 0) {
                tempLine.setText("CPU " + Math.round(snapshot.cpuTemp) + "°C"
                        + "  ·  GPU " + Math.round(snapshot.gpuTemp) + "°C");
            } else {
                tempLine.setText("CPU —  ·  GPU —");
            }
        }
        if (modeStatus != null) {
            String min = "min " + ghz(cfg.p0Min) + " / " + ghz(cfg.p6Min);
            modeStatus.setText(DeviceProfile.get(this).performanceModes
                    ? "MODE " + cfg.performanceMode + "  ·  " + min : min);
        }
        if (updateRoot) setRootState(snapshot.root);
    }

    private void bindFan(PerfTuner.Fan fan, TuneConfig cfg) {
        if (fan == null || cfg == null) return;
        int mode = cfg.fanMode;
        selectFanChip(mode);

        int percent;
        if (mode == PerfTuner.FAN_MANUAL) {
            percent = Math.max(10, Math.min(100, cfg.fanPct));
        } else if (fan.period > 0 && fan.duty > 0) {
            percent = Math.max(10, Math.min(100,
                    Math.round(fan.duty * 100f / fan.period)));
        } else {
            percent = Math.max(10, Math.min(100, cfg.fanPct));
        }
        fanPercent = percent;
        if (fanSeek != null && !fanSeek.isPressed()) {
            fanSeek.setProgress(percent - 10);
        }
        if (fanValue != null) {
            fanValue.setText(percent + "%");
            if (fanSeek != null) {
                fanValue.setTextColor(tintSeekBar(fanSeek,
                        fanSeek.getProgress(), fanSeek.getMax()));
            }
        }

        boolean manual = mode == PerfTuner.FAN_MANUAL;
        if (fanSeek != null) {
            fanSeek.setEnabled(true);
            fanSeek.setAlpha(manual ? 1f : 0.65f);
        }
        if (fanDetail != null) {
            int dutyPct = fan.period > 0
                    ? Math.max(0, Math.min(100, Math.round(fan.duty * 100f / fan.period))) : 0;
            String duty = fan.period > 0 ? dutyPct + "%" : "—";
            String rpm;
            if (fan.rpm > 0) {
                rpm = String.format(Locale.US, "%,d RPM", fan.rpm);
            } else if (fan.state != 0 && dutyPct > 0) {
                rpm = "~" + String.format(Locale.US, "%,d RPM",
                        PerfTuner.estimatedRpm(dutyPct));
            } else {
                rpm = "—";
            }
            fanDetail.setText("DUTY " + duty + "  ·  " + rpm + "  ·  "
                    + modeLabel(fan.mode));
        }
    }

    private void setRootState(boolean root) {
        if (rootStatus == null) return;
        String execution = PerfTuner.Root.executionName();
        if (root) {
            String label = execution == null ? "PRIVILEGED" : execution + " CONNECTED";
            rootStatus.setText(label);
            rootStatus.setTextColor(GREEN);
            if (footerRoot != null) {
                footerRoot.setText(label);
                footerRoot.setTextColor(GREEN);
            }
            enableControls(true);
        } else {
            rootStatus.setText("NO PRIVILEGED SERVICE — tap to retry");
            rootStatus.setTextColor(RED);
            if (footerRoot != null) {
                footerRoot.setText("PSERVER REQUIRED");
                footerRoot.setTextColor(RED);
            }
            enableControls(false);
        }
    }

    private void enableControls(boolean enabled) {
        for (View v : focusables) {
            if (v == rootStatus) continue;
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : 0.38f);
        }
    }

    private void updateHoldChip() {
        if (holdChip == null || prefs == null) return;
        boolean on = prefs.perfHoldEnabled();
        holdChip.setText(on ? "HOLD ON" : "HOLD OFF");
        holdChip.setTextColor(on ? GREEN : AMBER);
        holdChip.setBackground(Ui.rounded(on ? 0x2216C784 : 0x22FFC857,
                dp(11), on ? 0x5569D98B : 0x55FFC857, dp(1)));
    }

    private void startHold() {
        if (prefs == null || state == null) {
            updateHoldChip();
            return;
        }
        // Editing one app must not switch HOLD on globally. The per-app watcher
        // already re-asserts the app's clocks while that app is in front.
        if (!selectedAppPkg.isEmpty() && !prefs.perfHoldEnabled()) {
            updateHoldChip();
            return;
        }
        prefs.setPerfHoldEnabled(true);
        TuneConfig c = contextConfig;
        if (c == null) c = TuneConfig.fromState(state);
        prefs.setTuneTargets(c.p0Max, c.p0Min, c.p0Gov,
                c.p6Max, c.p6Min, c.p6Gov, c.gpuMax, c.gpuMin);
        if (!PerfTuneService.isRunning()) {
            PerfTuneService.start(this);
        }
        updateHoldChip();
    }

    private void stopHold() {
        if (prefs != null) {
            prefs.setPerfHoldEnabled(false);
            prefs.clearTuneTargets();
        }
        PerfTuneService.stop(this);
        updateHoldChip();
    }

    private void maybeResumeHold() {
        if (prefs == null || state == null) return;
        if (!prefs.perfHoldEnabled()) return;
        if (prefs.tuneP0Max() <= 0 || prefs.tuneP6Max() <= 0
                || prefs.tuneGpuMax() <= 0) return;
        if (!PerfTuneService.isRunning()) PerfTuneService.start(this);
    }

    private void retryRoot() {
        rootStatus.setText("CHECKING ROOT…");
        PerfTuner.Root.resetCheck();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean root = PerfTuner.prepare();
                final PerfTuner.State snapshot = PerfTuner.readState(
                        PerformanceTunerActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        state = snapshot;
                        applyState(snapshot);
                        setRootState(root);
                        if (root) focusFirst();
                    }
                });
            }
        });
    }

    private void selectFanMode(int mode) {
        selectFanChip(mode);
        if (contextConfig != null) contextConfig.fanMode = mode;
        markCustom();
        if (mode == PerfTuner.FAN_MANUAL) {
            if (fanSeek != null) {
                fanSeek.setEnabled(true);
                fanSeek.setAlpha(1f);
            }
            PerfTuner.setFanManual(fanPercent);
        } else {
            if (fanSeek != null) {
                fanSeek.setEnabled(true);
                fanSeek.setAlpha(0.65f);
            }
            PerfTuner.setFanMode(this, mode);
        }
        scheduleRefresh(500L);
    }

    private int currentFanMode() {
        for (int i = 0; i < fanChips.length; i++) {
            if (fanChips[i] != null && isSelectedChip(fanChips[i])) {
                return PerfTuner.FAN_MODE_VALUES[i];
            }
        }
        return PerfTuner.FAN_SMART;
    }

    private boolean isSelectedChip(TextView chip) {
        return chip.getCurrentTextColor() == 0xFF061015;
    }

    private View selectedFanChip() {
        for (TextView chip : fanChips) {
            if (chip != null && isSelectedChip(chip)) return chip;
        }
        return fanChips.length > 2 ? fanChips[2] : null;
    }

    private void selectFanChip(int mode) {
        int index = fanModeIndex(mode);
        if (index < 0) index = 2;
        for (int i = 0; i < fanChips.length; i++) {
            styleFanChip(fanChips[i], i, i == index);
        }
    }

    private int fanModeIndex(int mode) {
        for (int i = 0; i < PerfTuner.FAN_MODE_VALUES.length; i++) {
            if (PerfTuner.FAN_MODE_VALUES[i] == mode) return i;
        }
        return -1;
    }

    private String modeLabel(int mode) {
        int index = fanModeIndex(mode);
        return index >= 0 ? PerfTuner.FAN_MODE_LABELS[index] : "MODE " + mode;
    }

    private void applyProfile(String name) {
        selectedProfile = name;
        markProfileChips();
        profileStatus.setText(name + " APPLIED");
        styleProfileStatus(name);
        final PerfProfiles.Spec spec = PerfProfiles.get(name);
        if (state != null) {
            applySliderTargets(state.efficiency, spec.p0Max);
            applySliderTargets(state.performance, spec.p6Max);
            applySliderTargets(state.gpu, spec.gpuMax);
            state.efficiency.min = spec.p0Min;
            state.performance.min = spec.p6Min;
            state.efficiency.governor = spec.governor;
            state.performance.governor = spec.governor;
            state.gpu.min = state.gpu.freqs.length > 0 ? state.gpu.freqs[0] : 160000000;
            state.performanceMode = spec.performanceMode;
            if (contextConfig != null) {
                contextConfig.performanceMode = spec.performanceMode;
            }
            saveCurrentConfig();
            startHold();
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                PerfTuner.State s = state != null ? state : PerfTuner.readState(
                        PerformanceTunerActivity.this);
                PerfTuner.setPerformanceMode(spec.performanceMode);
                sleepBriefly();
                PerfTuner.setCpu(s.efficiency, spec.p0Max, spec.p0Min, spec.governor);
                PerfTuner.setCpu(s.performance, spec.p6Max, spec.p6Min, spec.governor);
                PerfTuner.setGpu(s.gpu, spec.gpuMax,
                        s.gpu.freqs.length > 0 ? s.gpu.freqs[0] : 160000000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scheduleRefresh(450L);
                    }
                });
            }
        });
    }

    private void applyStock() {
        selectedProfile = "STOCK";
        markProfileChips();
        profileStatus.setText("STOCK");
        styleProfileStatus("STOCK");
        stopHold();
        if (state != null) {
            state.efficiency.min = 1996800;
            state.performance.min = 2246400;
            state.efficiency.max = 3532800;
            state.performance.max = 4320000;
            state.gpu.max = 1100000000;
            state.performanceMode = 0;
            if (contextConfig != null) {
                contextConfig.performanceMode = 0;
            }
            saveCurrentConfig();
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                PerfTuner.State s = state != null ? state : PerfTuner.readState(
                        PerformanceTunerActivity.this);
                PerfTuner.setPerformanceMode(0);
                sleepBriefly();
                PerfTuner.setCpu(s.efficiency, 3532800, 1996800, "walt");
                PerfTuner.setCpu(s.performance, 4320000, 2246400, "walt");
                PerfTuner.setGpu(s.gpu, 1100000000,
                        s.gpu.freqs.length > 0 ? s.gpu.freqs[0] : 160000000);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scheduleRefresh(450L);
                    }
                });
            }
        });
    }

    private void applySliderTargets(PerfTuner.Cpu cpu, int target) {
        if (cpu == null || cpu.freqs.length == 0) return;
        cpu.max = cpu.freqs[PerfTuner.closestIndex(cpu.freqs, target)];
    }

    private void applySliderTargets(PerfTuner.Gpu gpu, int target) {
        if (gpu == null || gpu.freqs.length == 0) return;
        gpu.max = gpu.freqs[PerfTuner.closestIndex(gpu.freqs, target)];
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(260L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void markCustom() {
        selectedProfile = "";
        markProfileChips();
        if (profileStatus != null) {
            profileStatus.setText("CUSTOM");
            styleProfileStatus("CUSTOM");
        }
        saveCurrentConfig();
    }

    private void markProfileChips() {
        for (int i = 0; i < profileChips.length; i++) {
            boolean selected = profileNames[i].equals(selectedProfile);
            styleProfileChip(profileChips[i], i, selected, false);
        }
    }

    private void startThermalDemo() {
        if (demoActive) return;
        demoActive = true;
        demoStart = android.os.SystemClock.uptimeMillis();
        handler.removeCallbacks(refreshTask);
        handler.removeCallbacks(demoTick);
        demoChip.setText("STOP");
        demoChip.setTextColor(0xFF061015);
        demoChip.setBackground(Ui.rounded(accent, dp(8), 0x00000000, dp(1)));
        handler.post(demoTick);
    }

    private void stopThermalDemo() {
        if (!demoActive) return;
        demoActive = false;
        handler.removeCallbacks(demoTick);
        if (demoChip != null) {
            demoChip.setText("DEMO");
            demoChip.setTextColor(accent);
            demoChip.setBackground(Ui.rounded(ThemeColors.withAlpha(0x1A), dp(8),
                    ThemeColors.withAlpha(0x55), dp(1)));
        }
        if (tempLine != null) tempLine.setTextColor(MUTED);
        scheduleRefresh(250L);
    }

    private void scheduleRefresh(long delay) {
        handler.removeCallbacks(refreshTask);
        if (!demoActive && resumed && !destroyed) handler.postDelayed(refreshTask, delay);
    }

    private void refresh() {
        if (refreshInFlight || interacting) {
            scheduleRefresh(500L);
            return;
        }
        refreshInFlight = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final PerfTuner.State snapshot = PerfTuner.readState(
                        PerformanceTunerActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshInFlight = false;
                        if (destroyed) return;
                        state = snapshot;
                        applyState(snapshot);
                        scheduleRefresh(1000L);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Controller focus
    // ------------------------------------------------------------------

    static void onSidebarDrawerOpened() {
        final PerformanceTunerActivity activity = instance;
        if (activity == null) return;
        activity.handler.post(new Runnable() {
            @Override
            public void run() {
                if (activity.appPicker != null) {
                    activity.appPicker.dismiss();
                    activity.appPicker = null;
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (appPicker != null) {
            appPicker.dismiss();
            return;
        }
        super.onBackPressed();
    }

    private void startRightStick(SeekBar bar, float x) {
        if (bar == null) return;
        if (rightStickBar != bar) {
            SliderStick.reset(rightStickBar);
            rightStickBar = bar;
        }
        rightStickHeld = true;
        rightStickX = x;
        handler.removeCallbacks(rightStickRepeat);
        handler.post(rightStickRepeat);
    }

    private void stopRightStickRepeat() {
        rightStickHeld = false;
        handler.removeCallbacks(rightStickRepeat);
        SliderStick.reset(rightStickBar);
        rightStickBar = null;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (SliderStick.isGamepad(event)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            View focus = getCurrentFocus();
            boolean sliderActive = false;
            if (focus instanceof SeekBar) {
                float rx = SliderStick.rightX(event);
                if (SliderStick.active(rx)) {
                    startRightStick((SeekBar) focus, rx);
                    sliderActive = true;
                } else {
                    stopRightStickRepeat();
                }
            }

            // Left stick / D-pad HAT navigate. The right stick is reserved for
            // sliders, but navigation still works while a slider is focused.
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float leftX = event.getAxisValue(MotionEvent.AXIS_X);
            float leftY = event.getAxisValue(MotionEvent.AXIS_Y);
            float x = Math.abs(hatX) > Math.abs(leftX) ? hatX : leftX;
            float y = Math.abs(hatY) > Math.abs(leftY) ? hatY : leftY;
            final float threshold = 0.62f;
            final float release = 0.38f;
            boolean active = Math.abs(x) >= threshold || Math.abs(y) >= threshold;
            if (!active) {
                if (Math.abs(x) < release && Math.abs(y) < release) {
                    stickNavEngaged = false;
                    lastStickNavAt = 0L;
                }
                if (sliderActive) return true;
                return super.dispatchGenericMotionEvent(event);
            }

            long now = android.os.SystemClock.uptimeMillis();
            // The Odin D-pad also produces left-stick axis events. Do not let
            // the same physical press navigate once through the D-pad key and
            // again through the synthetic analogue event.
            if (now - lastDpadKeyAt < 250L) return true;
            // One focus step per stick deflection. The stick must return to
            // neutral before it can move again, so holding it cannot skip
            // through menus.
            if (stickNavEngaged) return true;
            if (now - Math.max(lastNavAt, lastStickNavAt) < 140L) return true;
            stickNavEngaged = true;
            lastStickNavAt = now;

            boolean diagonal = focus instanceof SeekBar
                    && Math.abs(x) >= threshold && Math.abs(y) >= threshold;
            if (diagonal) {
                moveFocusDiagonal(x > 0f ? 1 : 0, y > 0f ? 3 : 2);
            } else if (Math.abs(x) >= Math.abs(y)) {
                moveFocus(x > 0f ? 1 : 0);
            } else {
                moveFocus(y > 0f ? 3 : 2);
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = down && event.getRepeatCount() > 0;
        if (appPicker != null) {
            if (appPicker.handleKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (down && !repeat) jumpCard(code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (down && !repeat) {
                Haptics.key(getWindow().getDecorView());
                finishAnimated();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && !repeat) {
                View focus = getCurrentFocus();
                if (focus != null && !(focus instanceof SeekBar)) {
                    focus.performClick();
                }
            }
            return true;
        }
        int dir = direction(code);
        if (dir >= 0) {
            lastDpadKeyAt = android.os.SystemClock.uptimeMillis();
            if (!down) {
                heldDpad.remove(code);
                if (pendingDpadDir == dir && pendingDpadTask != null
                        && heldDpad.isEmpty()) {
                    handler.removeCallbacks(pendingDpadTask);
                    pendingDpadTask = null;
                    pendingDpadDir = -1;
                    stopRightStickRepeat();
                    moveFocus(dir);
                }
                return true;
            }
            if (repeat) return true;
            heldDpad.add(code);
            int horizontal = heldDpad.contains(KeyEvent.KEYCODE_DPAD_LEFT) ? 0
                    : heldDpad.contains(KeyEvent.KEYCODE_DPAD_RIGHT) ? 1 : -1;
            int vertical = heldDpad.contains(KeyEvent.KEYCODE_DPAD_UP) ? 2
                    : heldDpad.contains(KeyEvent.KEYCODE_DPAD_DOWN) ? 3 : -1;
            if (horizontal >= 0 && vertical >= 0) {
                if (pendingDpadTask != null) handler.removeCallbacks(pendingDpadTask);
                pendingDpadTask = null;
                pendingDpadDir = -1;
                stopRightStickRepeat();
                lastNavAt = android.os.SystemClock.uptimeMillis();
                moveFocusDiagonal(horizontal, vertical);
            } else if (pendingDpadTask == null) {
                pendingDpadDir = dir;
                pendingDpadTask = new Runnable() {
                    @Override public void run() {
                        if (pendingDpadDir < 0) return;
                        int pending = pendingDpadDir;
                        pendingDpadDir = -1;
                        pendingDpadTask = null;
                        if (heldDpad.isEmpty()) return;
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastStickNavAt < 250L) return;
                        stopRightStickRepeat();
                        lastNavAt = now;
                        moveFocus(pending);
                    }
                };
                handler.postDelayed(pendingDpadTask, 180L);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 1;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 2;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 3;
            default:
                return -1;
        }
    }

    private void moveFocus(int dir) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFocusMoveAt < 160L) return;
        lastFocusMoveAt = now;
        performFocusMove(dir);
    }

    private void performFocusMove(int dir) {
        View focused = getCurrentFocus();
        if (focused == null) {
            focusInitial();
            return;
        }
        int focusDirection = dir == 0 ? View.FOCUS_LEFT
                : dir == 1 ? View.FOCUS_RIGHT
                : dir == 2 ? View.FOCUS_UP : View.FOCUS_DOWN;
        // Use Android's actual focus search, exactly like the other built-in
        // apps. The hand-built row list was the source of skipped controls.
        View next = null;
        View root = getWindow() != null ? getWindow().getDecorView() : null;
        if (root instanceof ViewGroup) {
            next = ((ViewGroup) root).focusSearch(focused, focusDirection);
        }
        if (next == null) next = focused.focusSearch(focusDirection);
        if (next != null && next != focused && next.isFocusable()
                && next.getVisibility() == View.VISIBLE && next.isEnabled()) {
            focusView(next);
        }
    }

    private void moveFocusDiagonal(int horizontalDir, int verticalDir) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFocusMoveAt < 160L) return;
        lastFocusMoveAt = now;
        float dx = horizontalDir == 1 ? 1f : -1f;
        float dy = verticalDir == 3 ? 1f : -1f;
        if (!moveFocusGeometry(dx, dy, true)) {
            if (Math.abs(dx) >= Math.abs(dy)) {
                performFocusMove(horizontalDir);
            } else {
                performFocusMove(verticalDir);
            }
        }
    }

    /** Spatial diagonal walk. Only controls in a different card are eligible. */
    private boolean moveFocusGeometry(float dirX, float dirY, boolean diagonal) {
        View current = getCurrentFocus();
        if (current == null) {
            focusInitial();
            return false;
        }
        int currentCard = cardIndexFor(current);
        int[] cur = new int[2];
        current.getLocationOnScreen(cur);
        float cx = cur[0] + current.getWidth() / 2f;
        float cy = cur[1] + current.getHeight() / 2f;
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (View v : focusables) {
            if (v == current || !isNavigable(v)) continue;
            if (diagonal && currentCard >= 0 && cardIndexFor(v) == currentCard) continue;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float vx = loc[0] + v.getWidth() / 2f - cx;
            float vy = loc[1] + v.getHeight() / 2f - cy;
            float projection = vx * dirX + vy * dirY;
            if (projection <= 8f) continue;
            if (diagonal && (vx * dirX < 4f || vy * dirY < 4f)) continue;
            float perpendicular = Math.abs(vx * dirY - vy * dirX);
            float score = projection + perpendicular * (diagonal ? 1.35f : 2.1f);
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) {
            focusView(best);
            return true;
        }
        return false;
    }



    private static boolean isNavigable(View view) {
        return view != null
                && view.getVisibility() == View.VISIBLE
                && view.isEnabled()
                && view.isFocusable()
                && view.isAttachedToWindow()
                && view.getWidth() > 0
                && view.getHeight() > 0;
    }

    private View cpuVerticalTarget(View current, int dir) {
        if (efficiencyCard != null && isCpuControl(efficiencyCard, current)) {
            View target = verticalWithinCard(efficiencyCard, current, dir);
            if (target == null && dir == 3 && performanceCard != null) {
                target = performanceCard.maxSeek;
            }
            return target;
        }
        if (performanceCard != null && isCpuControl(performanceCard, current)) {
            View target = verticalWithinCard(performanceCard, current, dir);
            if (target == null && dir == 2 && efficiencyCard != null) {
                target = efficiencyCard.selectedGovernorChip();
            }
            return target;
        }
        return null;
    }

    private static boolean isCpuControl(CpuCard card, View view) {
        return view == card.maxSeek || view == card.minSeek
                || card.governorChips.contains(view);
    }

    private static View verticalWithinCard(CpuCard card, View current, int dir) {
        if (current == card.maxSeek) return dir == 3 ? card.minSeek : null;
        if (current == card.minSeek) {
            return dir == 3 ? card.selectedGovernorChip() : card.maxSeek;
        }
        if (card.governorChips.contains(current)) {
            return dir == 2 ? card.minSeek : null;
        }
        return null;
    }

    private List<List<View>> focusRows() {
        List<List<View>> rows = new ArrayList<>();
        rows.add(headerFocus);
        List<View> profiles = new ArrayList<>();
        for (TextView chip : profileChips) if (chip != null) profiles.add(chip);
        rows.add(profiles);
        if (efficiencyCard != null) {
            List<View> maxRow = new ArrayList<>();
            maxRow.add(efficiencyCard.maxSeek);
            if (demoChip != null) maxRow.add(demoChip);
            rows.add(maxRow);
            List<View> minRow = new ArrayList<>();
            minRow.add(efficiencyCard.minSeek);
            rows.add(minRow);
            rows.add(new ArrayList<View>(efficiencyCard.governorChips));
        }
        if (performanceCard != null) {
            List<View> maxRow = new ArrayList<>();
            maxRow.add(performanceCard.maxSeek);
            if (gpuCard != null) maxRow.add(gpuCard.maxSeek);
            rows.add(maxRow);
            List<View> minRow = new ArrayList<>();
            minRow.add(performanceCard.minSeek);
            rows.add(minRow);
            List<View> govRow = new ArrayList<>(performanceCard.governorChips);
            if (gpuCard != null) govRow.add(gpuCard.minSeek);
            rows.add(govRow);
        }
        List<View> fanRow = new ArrayList<>();
        for (TextView chip : fanChips) if (chip != null) fanRow.add(chip);
        if (!fanRow.isEmpty()) rows.add(fanRow);
        if (fanSeek != null) {
            List<View> seekRow = new ArrayList<>();
            seekRow.add(fanSeek);
            rows.add(seekRow);
        }
        List<View> footer = new ArrayList<>();
        if (rootStatus != null) footer.add(rootStatus);
        rows.add(footer);
        return rows;
    }

    private static int[] locate(List<List<View>> rows, View current) {
        for (int r = 0; r < rows.size(); r++) {
            List<View> line = rows.get(r);
            int index = line.indexOf(current);
            if (index >= 0) return new int[]{r, index};
        }
        return null;
    }

    private static List<View> usable(List<View> line) {
        List<View> out = new ArrayList<>();
        for (View v : line) {
            if (v != null && v.getVisibility() == View.VISIBLE
                    && v.isEnabled() && v.isAttachedToWindow()
                    && v.getWidth() > 0 && v.isFocusable()) {
                out.add(v);
            }
        }
        return out;
    }

    private View nearestByX(List<View> line, View current) {
        List<View> usable = usable(line);
        if (usable.isEmpty()) return null;
        int[] cur = new int[2];
        current.getLocationOnScreen(cur);
        float cx = cur[0] + current.getWidth() / 2f;
        View best = null;
        float bestDx = Float.MAX_VALUE;
        for (View v : usable) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float dx = Math.abs(loc[0] + v.getWidth() / 2f - cx);
            if (dx < bestDx) {
                bestDx = dx;
                best = v;
            }
        }
        return best;
    }

    private void focusView(View target) {
        target.requestFocus();
        Haptics.key(target);
        target.post(new Runnable() {
            @Override
            public void run() {
                target.requestRectangleOnScreen(new Rect(0, 0,
                        target.getWidth(), target.getHeight()), false);
            }
        });
    }

    private void jumpCard(int direction) {
        List<View> anchors = new ArrayList<>();
        if (efficiencyCard != null) anchors.add(efficiencyCard.maxSeek);
        if (performanceCard != null) anchors.add(performanceCard.maxSeek);
        View fanAnchor = null;
        for (TextView chip : fanChips) {
            if (chip != null && isSelectedChip(chip)) {
                fanAnchor = chip;
                break;
            }
        }
        if (fanAnchor == null && fanChips.length > 2) fanAnchor = fanChips[2];
        if (fanAnchor != null) anchors.add(fanAnchor);
        if (gpuCard != null) anchors.add(gpuCard.maxSeek);
        if (anchors.isEmpty()) return;
        int currentCard = cardIndexFor(getCurrentFocus());
        if (currentCard >= 0) cardFocusIndex = currentCard;
        cardFocusIndex = (cardFocusIndex + (direction > 0 ? 1 : -1)
                + anchors.size()) % anchors.size();
        focusView(anchors.get(cardFocusIndex));
    }

    private int cardIndexFor(View v) {
        if (v == null) return -1;
        if (efficiencyCard != null) {
            if (v == efficiencyCard.maxSeek || v == efficiencyCard.minSeek
                    || efficiencyCard.governorChips.contains(v)) return 0;
        }
        if (performanceCard != null) {
            if (v == performanceCard.maxSeek || v == performanceCard.minSeek
                    || performanceCard.governorChips.contains(v)) return 1;
        }
        int next = 2;
        if (hasFanControls()) {
            if (v == fanSeek) return next;
            for (TextView chip : fanChips) if (chip == v) return next;
            next++;
        }
        if (gpuCard != null && (v == gpuCard.maxSeek || v == gpuCard.minSeek)) {
            return next;
        }
        return -1;
    }

    private boolean hasFanControls() {
        if (fanSeek != null) return true;
        for (TextView chip : fanChips) if (chip != null) return true;
        return false;
    }

    private void sliderHaptic(View view) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSliderHapticAt < 55L) return;
        lastSliderHapticAt = now;
        Haptics.key(view);
    }

    private void focusFirst() {
        focusInitial();
    }

    private void focusInitial() {
        for (int i = 0; i < profileChips.length; i++) {
            if (profileNames[i].equals(selectedProfile)) {
                profileChips[i].requestFocus();
                return;
            }
        }
        // Start on Balanced; it is the most neutral profile.
        if (profileChips[1] != null && profileChips[1].isEnabled()) {
            profileChips[1].requestFocus();
            return;
        }
        for (View v : focusables) {
            if (v.getVisibility() == View.VISIBLE && v.isEnabled() && v.isFocusable()) {
                v.requestFocus();
                return;
            }
        }
    }

    private void addFocusable(final View view, boolean focusRing) {
        if (focusRing) {
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setDefaultFocusHighlightEnabled(false);
            StateListDrawable ring = new StateListDrawable();
            ring.addState(new int[]{android.R.attr.state_focused},
                    Ui.rounded(0x00000000, dp(10), accent, dp(1.4f)));
            ring.addState(new int[]{}, Ui.rounded(0x00000000, dp(10)));
            view.setForeground(ring);
            if (view instanceof TextView && view.isClickable()) {
                view.setOnTouchListener(new View.OnTouchListener() {
                    private float downX;
                    private float downY;
                    private boolean moved;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (!v.isEnabled()) return false;
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                downX = event.getX();
                                downY = event.getY();
                                moved = false;
                                v.setPressed(true);
                                v.requestFocus();
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                if (Math.abs(event.getX() - downX) > dp(8)
                                        || Math.abs(event.getY() - downY) > dp(8)) {
                                    moved = true;
                                    v.setPressed(false);
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                                v.setPressed(false);
                                if (!moved) v.performClick();
                                return true;
                            case MotionEvent.ACTION_CANCEL:
                                v.setPressed(false);
                                return true;
                            default:
                                return true;
                        }
                    }
                });
            }
        }
        focusables.add(view);
    }

    // ------------------------------------------------------------------
    // Small UI helpers
    // ------------------------------------------------------------------

    private SeekBar seekBar(int max) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(Math.max(0, max));
        bar.setSplitTrack(false);
        bar.setPadding(dp(12), 0, dp(12), 0);
        int initial = ramp(0f);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(initial));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(initial));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        bar.setFocusable(true);
        bar.setFocusableInTouchMode(true);
        bar.setDefaultFocusHighlightEnabled(false);
        bar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.requestFocus();
                }
                return false;
            }
        });
        return bar;
    }

    private int tintSeekBar(SeekBar bar, int value, int max) {
        int color = ramp(max <= 0 ? 0f : value / (float) max);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(color));
        return color;
    }

    private void addSliderRow(LinearLayout parent, String label, SeekBar bar,
                              TextView value, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        parent.addView(row, lp);
        TextView name = text(label, 9f, MUTED, true);
        name.setLetterSpacing(0.10f);
        name.setMinWidth(dp(34));
        row.addView(name);
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(26), 1f));
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(68));
        row.addView(value);
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView t = new LTextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    private static String ghz(int khz) {
        return String.format(Locale.US, "%.2f GHz", khz / 1000000f);
    }

    private static String mhz(int hz) {
        return String.format(Locale.US, "%.0f MHz", hz / 1000000f);
    }

    private static String shortGovernor(String gov) {
        if (gov == null) return "";
        if (gov.equals("schedutil")) return "SCHED";
        if (gov.equals("performance")) return "PERF";
        if (gov.equals("conservative")) return "CONS";
        if (gov.equals("powersave")) return "POWER";
        if (gov.equals("walt")) return "WALT";
        return gov.toUpperCase(Locale.US);
    }
}
