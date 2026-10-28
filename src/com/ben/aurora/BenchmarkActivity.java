package com.ben.aurora;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
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
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Built-in CPU/GPU benchmark and thermal stress test for Odin. */
public final class BenchmarkActivity extends AuroraGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final long WARMUP_MS = 1200L;
    private static final int LENGTH_MIN_SEC = 10;
    private static final int LENGTH_MAX_SEC = 1200;
    private static final int LENGTH_SLIDER_MAX = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cpuRun = new AtomicBoolean(false);
    private final AtomicLong cpuOps = new AtomicLong();
    private final List<Thread> cpuThreads = new ArrayList<>();

    private Prefs prefs;
    private BenchmarkStageView stage;
    private BenchmarkGpuWorkloadView gpuWorkload;
    private TextView rootStatus;
    private TextView cpuResult;
    private TextView gpuResult;
    private TextView progressLabel;
    private TextView threadValue;
    private TextView startButton;
    private TextView stopButton;
    private TextView resultsButton;
    private TextView lengthValue;
    private TextView cpuEffClockValue;
    private TextView cpuPerfClockValue;
    private TextView gpuClockValue;
    private TextView fanSpeedValue;
    private TextView fanModeButton;
    private TextView cpuEffGovButton;
    private TextView cpuPerfGovButton;
    private SeekBar lengthBar;
    private SeekBar cpuEffClockBar;
    private SeekBar cpuPerfClockBar;
    private SeekBar gpuClockBar;
    private SeekBar fanSpeedBar;
    private Dialog resultDialog;
    private View resultPanel;
    private ControllerNav nav;
    private BenchmarkResult lastResult;
    private SeekBar threadBar;
    private final List<View> controls = new ArrayList<>();
    private final List<View> liveControls = new ArrayList<>();

    private int mode = BenchmarkStageView.MODE_BOTH;
    private int threads = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()));
    private int durationSec = 15;
    private int cpuEffPct = 100;
    private int cpuPerfPct = 100;
    private int gpuPct = 100;
    private int fanPct = 100;
    private int fanModeIndex = 1;
    private boolean fanManual;
    private boolean idleMonitoring;
    private long idleFrameStartNs;
    private long idleFrames;
    private int idleFps;
    private int[] cpuEffClockTable = new int[0];
    private int[] cpuPerfClockTable = new int[0];
    private int[] gpuClockTable = new int[0];
    private String[] cpuEffGovernors = new String[0];
    private String[] cpuPerfGovernors = new String[0];
    private String cpuEffGovernor;
    private String cpuPerfGovernor;

    private boolean running;
    private boolean warmingUp;
    private Runnable warmupTask;
    private Runnable fanHoldTask;
    private long startMs;
    private long durationMs;
    private PerfTuner.State original;
    private boolean tuned;
    private final Object tuningLock = new Object();
    private PerfSampler sampler;
    private int lastCpuUsage;
    private int lastGpuUsage;
    private float lastCpuTemp;
    private float lastGpuTemp;
    private boolean pserverActive;
    private long statsSamples;
    private double cpuTempSum;
    private double gpuTempSum;
    private double cpuUseSum;
    private double gpuUseSum;
    private double fpsSum;
    private float maxCpuTemp;
    private float maxGpuTemp;
    private float minFps;
    private float maxFps;
    private int maxCpuUse;
    private int maxGpuUse;
    private int fpsSamples;
    private double cpuThrottleSum;
    private double gpuThrottleSum;
    private int cpuThrottleSamples;
    private int gpuThrottleSamples;
    private final List<Integer> cpuClockHistory = new ArrayList<>();
    private final List<Integer> gpuClockHistory = new ArrayList<>();
    private long lastClockSampleMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long elapsed = Math.max(0, SystemClock.uptimeMillis() - startMs);
            float progress = durationMs <= 0 ? 1f : Math.min(1f, elapsed / (float) durationMs);
            long ops = cpuOps.get();
            float cpuScore = elapsed > 0 ? ops / (elapsed / 1000f) : 0f;
            float gpuFps = gpuWorkload != null && gpuWorkload.fps() > 0f
                    ? gpuWorkload.fps() : stage != null ? stage.gpuFps() : 0f;
            PerfStats stats = sampler != null ? sampler.sample(BenchmarkActivity.this, false) : null;
            float cpuTemp = stats != null ? stats.cpuTempC : 0f;
            float gpuTemp = stats != null ? stats.gpuTempC : 0f;
            int cpuUse = stats != null ? stats.cpuUsage : 0;
            int gpuUse = stats != null ? stats.gpuUsage : 0;
            if (cpuUse == 0 && mode != BenchmarkStageView.MODE_GPU) {
                cpuUse = Math.min(100, Math.round(threads * 100f / 8f));
            }
            if (gpuUse == 0 && gpuActive()) {
                gpuUse = Math.max(1, Math.min(100, Math.round(gpuFps / 120f * 100f)));
            }
            lastCpuUsage = cpuUse;
            lastGpuUsage = gpuActive() ? gpuUse : 0;
            lastCpuTemp = cpuTemp;
            lastGpuTemp = gpuTemp;
            recordSample(cpuTemp, gpuTemp, lastCpuUsage, lastGpuUsage, gpuFps);
            recordThrottle(stats);
            float[] temps = new float[]{cpuTemp, gpuTemp};
            if (stage != null) {
                stage.setState(progress, cpuTemp, gpuTemp, lastCpuUsage,
                        lastGpuUsage, gpuFps);
            }
            updateResults(cpuScore, gpuFps, temps);
            progressLabel.setText(Math.round(progress * 100) + "%  ·  "
                    + formatLength(Math.max(0, durationSec - (int) (elapsed / 1000))));
            if (elapsed >= durationMs) finishRun(true);
            else main.postDelayed(this, 100L);
        }
    };

    private final Runnable idleTick = new Runnable() {
        @Override
        public void run() {
            if (!idleMonitoring || running) return;
            PerfStats stats = sampler != null
                    ? sampler.sample(BenchmarkActivity.this, false) : null;
            if (stats != null && stage != null) {
                stage.setIdleSample(stats.cpuTempC, stats.gpuTempC,
                        stats.cpuUsage, stats.gpuUsage, idleFps);
                lastCpuUsage = stats.cpuUsage;
                lastGpuUsage = stats.gpuUsage;
                updateResults(0f, idleFps,
                        new float[]{stats.cpuTempC, stats.gpuTempC});
                if (progressLabel != null) progressLabel.setText("LIVE  ·  IDLE");
            }
            main.postDelayed(this, 1000L);
        }
    };

    private final Choreographer.FrameCallback idleFrameCallback =
            new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    if (!idleMonitoring) return;
                    if (idleFrameStartNs == 0L) {
                        idleFrameStartNs = frameTimeNanos;
                    } else {
                        idleFrames++;
                        long span = frameTimeNanos - idleFrameStartNs;
                        if (span >= 1_000_000_000L) {
                            idleFps = (int) Math.max(0, Math.min(240,
                                    Math.round(idleFrames * 1_000_000_000f / span)));
                            idleFrames = 0;
                            idleFrameStartNs = frameTimeNanos;
                        }
                    }
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        sampler = new PerfSampler();
        buildUi();
        nav = new ControllerNav(this, getWindow().getDecorView());
        final View firstFocus = modeRow == null ? null : modeRow.getChildAt(
                mode == BenchmarkStageView.MODE_CPU ? 0
                        : mode == BenchmarkStageView.MODE_GPU ? 1 : 2);
        getWindow().getDecorView().post(new Runnable() {
            @Override public void run() {
                nav.decorateFocusables();
                if (firstFocus != null) firstFocus.requestFocus();
            }
        });
        hideBars();
        startIdleMonitoring();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gpuWorkload != null) gpuWorkload.onResume();
        startIdleMonitoring();
    }

    @Override
    protected void onPause() {
        if (nav != null) nav.cancel();
        if (running) {
            finishRun(false);
            // Restore even if the pending worker task is cancelled as the
            // activity is destroyed. This is the close-app path.
            restoreTuning();
        }
        if (stage != null) stage.stop();
        stopIdleMonitoring();
        if (gpuWorkload != null) {
            gpuWorkload.setRunning(false);
            gpuWorkload.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        // Extra lifecycle safety net for launcher/Recents dismissal.
        if (original != null) restoreTuning();
        super.onStop();
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
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (nav != null && nav.dispatchGenericMotionEvent(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onDestroy() {
        if (nav != null) nav.release();
        stopIdleMonitoring();
        main.removeCallbacksAndMessages(null);
        if (warmupTask != null) {
            main.removeCallbacks(warmupTask);
            warmupTask = null;
        }
        stopFanHold();
        cpuRun.set(false);
        if (running) finishRun(false);
        dismissResultDialog();
        restoreTuning();
        worker.shutdownNow();
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
        PerfTuner.State localState = PerfTuner.readStateLocal(this);
        cpuEffClockTable = localState != null
                ? clockTable(localState.efficiency) : new int[0];
        cpuPerfClockTable = localState != null
                ? clockTable(localState.performance) : new int[0];
        if (cpuEffClockTable.length == 0) cpuEffClockTable = cpuPerfClockTable;
        if (cpuPerfClockTable.length == 0) cpuPerfClockTable = cpuEffClockTable;
        gpuClockTable = localState != null && localState.gpu != null
                ? sortedCopy(localState.gpu.freqs) : new int[0];
        cpuEffGovernors = governorOptions(localState != null ? localState.efficiency : null);
        cpuPerfGovernors = governorOptions(localState != null ? localState.performance : null);
        cpuEffGovernor = defaultGovernor(cpuEffGovernors,
                localState != null && localState.efficiency != null
                        ? localState.efficiency.governor : null);
        cpuPerfGovernor = defaultGovernor(cpuPerfGovernors,
                localState != null && localState.performance != null
                        ? localState.performance.governor : null);
        boolean stacked = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || getResources().getConfiguration().screenWidthDp < 520;
        FrameLayout root = new FrameLayout(this);
        GlassPanel sheet = new GlassPanel(this, null, ThemeColors.accent(),
                0x4D0B0E14, 26, true);
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

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout stageHolder = new FrameLayout(this);
        gpuWorkload = new BenchmarkGpuWorkloadView(this);
        gpuWorkload.setZOrderMediaOverlay(false);
        stage = new BenchmarkStageView(this);
        stage.configure(mode, threads);
        stageHolder.addView(gpuWorkload, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stageHolder.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(stageHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));

        LinearLayout lower = new LinearLayout(this);
        lower.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        lower.setGravity(Gravity.TOP);
        lower.setPadding(0, dp(2), 0, 0);
        column.addView(lower, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(10), dp(7), dp(10), dp(7));
        left.setBackground(Ui.rounded(0x330B1218, dp(14), 0x22FFFFFF, dp(1)));
        if (stacked) {
            lower.addView(left, matchWrap());
        } else {
            lower.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0.92f));
        }

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), dp(7), dp(12), dp(7));
        right.setBackground(Ui.rounded(0x550B1218, dp(14), 0x22FFFFFF, dp(1)));
        if (stacked) {
            LinearLayout.LayoutParams rightLp = matchWrap();
            rightLp.topMargin = dp(6);
            lower.addView(right, rightLp);
        } else {
            lower.addView(right, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.08f));
        }

        LinearLayout modeLine = inlineLine();
        modeLine.addView(inlineLabel("MODE"));
        modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeLine.addView(modeRow, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(modeLine, matchWrap());
        rebuildModeChips();

        LinearLayout threadRow = inlineLine();
        threadRow.addView(inlineLabel("THREADS"));
        threadBar = new SeekBar(this);
        threadBar.setMax(7);
        threadBar.setProgress(Math.max(0, threads - 1));
        threadBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        threadBar.setThumbTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        threadBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        threadBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                threads = progress + 1;
                threadValue.setText(String.valueOf(threads));
                if (stage != null && !running) stage.configure(mode, threads);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        threadRow.addView(threadBar, new LinearLayout.LayoutParams(0, dp(25), 1f));
        threadValue = text(String.valueOf(threads), 11f, TEXT, true);
        threadValue.setGravity(Gravity.CENTER);
        threadValue.setMinWidth(dp(28));
        threadRow.addView(threadValue);
        controls.add(threadBar);
        left.addView(threadRow, matchWrap());

        LinearLayout durationLine = inlineLine();
        durationLine.addView(inlineLabel("LENGTH"));
        lengthBar = new SeekBar(this);
        lengthBar.setMax(LENGTH_SLIDER_MAX);
        lengthBar.setProgress(lengthToProgress(durationSec));
        lengthBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        lengthBar.setThumbTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        lengthBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        lengthBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (running) return;
                durationSec = progressToLength(progress);
                lengthValue.setText(formatLength(durationSec));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        durationLine.addView(lengthBar, new LinearLayout.LayoutParams(0, dp(25), 1f));
        lengthValue = text(formatLength(durationSec), 10.5f, TEXT, true);
        lengthValue.setGravity(Gravity.CENTER);
        lengthValue.setMinWidth(dp(46));
        durationLine.addView(lengthValue);
        controls.add(lengthBar);
        left.addView(durationLine, matchWrap());

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.VERTICAL);
        metrics.setPadding(0, dp(5), 0, 0);
        cpuResult = text("CPU   0%   0.0M ops/s", 10.5f, TEXT, true);
        gpuResult = text("GPU   0%   0 FPS", 10.5f, TEXT, true);
        cpuResult.setPadding(0, dp(1), 0, dp(1));
        gpuResult.setPadding(0, dp(1), 0, dp(1));
        metrics.addView(cpuResult);
        metrics.addView(gpuResult);
        progressLabel = text("READY", 9f, MUTED, true);
        progressLabel.setPadding(0, dp(3), 0, 0);
        metrics.addView(progressLabel);
        left.addView(metrics, matchWrap());

        cpuEffGovButton = governorButton(cpuEffGovernor, true);
        cpuEffClockValue = text("", 9.5f, TEXT, true);
        cpuEffClockBar = percentControl(right, "EFFICIENCY", cpuEffClockValue, cpuEffPct,
                new IntCallback() {
                    @Override public void onValue(int value) {
                        cpuEffPct = value;
                        updateClockLabels();
                        applyLiveTuning();
                    }
                }, cpuEffGovButton, false);
        cpuPerfGovButton = governorButton(cpuPerfGovernor, false);
        cpuPerfClockValue = text("", 9.5f, TEXT, true);
        cpuPerfClockBar = percentControl(right, "PERFORMANCE", cpuPerfClockValue, cpuPerfPct,
                new IntCallback() {
                    @Override public void onValue(int value) {
                        cpuPerfPct = value;
                        updateClockLabels();
                        applyLiveTuning();
                    }
                }, cpuPerfGovButton, false);
        gpuClockValue = text("", 9.5f, TEXT, true);
        gpuClockBar = percentControl(right, "GPU CLOCK", gpuClockValue, gpuPct,
                new IntCallback() {
                    @Override public void onValue(int value) {
                        gpuPct = value;
                        updateClockLabels();
                        applyLiveTuning();
                    }
                }, null, false);
        fanSpeedValue = text("", 9.5f, TEXT, true);
        fanModeButton = text("SMART", 7.3f, 0xE6FFFFFF, false);
        fanModeButton.setGravity(Gravity.CENTER);
        fanModeButton.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        fanModeButton.setIncludeFontPadding(false);
        fanModeButton.setMinHeight(0);
        fanModeButton.setMinimumHeight(0);
        fanModeButton.setMinWidth(dp(54));
        fanModeButton.setMaxWidth(dp(54));
        fanModeButton.setPadding(0, 0, 0, 0);
        fanModeButton.setFocusable(true);
        fanModeButton.setClickable(true);
        fanModeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (fanManual) {
                    fanManual = false;
                } else {
                    fanModeIndex = (fanModeIndex + 1) % 3;
                }
                updateFanModeButton();
                if (running) {
                    startFanHold();
                    applyLiveTuning();
                }
            }
        });
        liveControls.add(fanModeButton);
        fanSpeedBar = percentControl(right, "FAN SPEED", fanSpeedValue, fanPct,
                new IntCallback() {
                    @Override public void onValue(int value) {
                        fanPct = value;
                        fanManual = true;
                        updateFanModeButton();
                        updateClockLabels();
                        applyLiveTuning();
                        if (running) startFanHold();
                    }
                }, fanModeButton, true);
        fanModeButton.setId(View.generateViewId());
        fanSpeedBar.setId(View.generateViewId());
        fanSpeedBar.setNextFocusRightId(fanModeButton.getId());
        fanModeButton.setNextFocusLeftId(fanSpeedBar.getId());
        fanModeButton.setNextFocusDownId(fanSpeedBar.getId());
        updateClockLabels();
        updateFanModeButton();
        lower.post(new Runnable() {
            @Override public void run() {
                if (stacked) return;
                int targetH = Math.max(left.getHeight(), right.getHeight());
                if (targetH <= 0) return;
                ViewGroup.LayoutParams llp = left.getLayoutParams();
                ViewGroup.LayoutParams rlp = right.getLayoutParams();
                if (llp.height != targetH) {
                    llp.height = targetH;
                    left.setLayoutParams(llp);
                }
                if (rlp.height != targetH) {
                    rlp.height = targetH;
                    right.setLayoutParams(rlp);
                }
            }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(3), 0, dp(2));
        column.addView(buttons, matchWrap());
        startButton = button("START", true);
        stopButton = button("STOP", false);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRun(); }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishRun(false); }
        });
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(38), 0.55f);
        slp.leftMargin = dp(8);
        buttons.addView(stopButton, slp);
        controls.add(startButton);
        controls.add(stopButton);

        setContentView(root);
        setControlsEnabled(true);
    }

    private LinearLayout modeRow;

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("STRESS TEST", 14f, ThemeColors.accent(), false);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = text("CPU / GPU STRESS  ·  LIVE TELEMETRY", 8.5f, MUTED, false);
        sub.setLetterSpacing(0.10f);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean ps = PServer.available();
        resultsButton = text("RESULTS", 8f, 0xFF071015, true);
        resultsButton.setGravity(Gravity.CENTER);
        resultsButton.setPadding(dp(9), dp(4), dp(9), dp(4));
        resultsButton.setBackground(Ui.rounded(ThemeColors.accent(),
                dp(10), 0x00000000, dp(1)));
        resultsButton.setFocusable(true);
        resultsButton.setClickable(true);
        resultsButton.setVisibility(View.GONE);
        resultsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openResultPage(lastResult); }
        });
        if (!BenchmarkResultStore.load(this).isEmpty()) {
            resultsButton.setVisibility(View.VISIBLE);
        }
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resultLp.rightMargin = dp(6);
        header.addView(resultsButton, resultLp);
        rootStatus = text(ps ? "PSERVER READY" : "READY", 8.5f,
                ps ? 0xFF69D98B : 0xFFFFC857, true);
        rootStatus.setPadding(dp(8), dp(4), dp(8), dp(4));
        rootStatus.setBackground(Ui.rounded(0x18FFC857, dp(10), 0x44FFC857, dp(1)));
        header.addView(rootStatus);
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
        header.addView(close);
        return header;
    }

    private void rebuildModeChips() {
        if (modeRow == null) return;
        if (modeRow.getChildCount() == 0) {
            addChoice(modeRow, "CPU", mode == BenchmarkStageView.MODE_CPU, new Runnable() {
                @Override public void run() {
                    if (running) return;
                    mode = BenchmarkStageView.MODE_CPU;
                    stage.configure(mode, threads);
                    rebuildModeChips();
                }
            });
            addChoice(modeRow, "GPU", mode == BenchmarkStageView.MODE_GPU, new Runnable() {
                @Override public void run() {
                    if (running) return;
                    mode = BenchmarkStageView.MODE_GPU;
                    stage.configure(mode, threads);
                    rebuildModeChips();
                }
            });
            addChoice(modeRow, "COMBINED", mode == BenchmarkStageView.MODE_BOTH, new Runnable() {
                @Override public void run() {
                    if (running) return;
                    mode = BenchmarkStageView.MODE_BOTH;
                    stage.configure(mode, threads);
                    rebuildModeChips();
                }
            });
            return;
        }
        for (int i = 0; i < modeRow.getChildCount() && i < 3; i++) {
            View child = modeRow.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView chip = (TextView) child;
            boolean selected = (i == 0 && mode == BenchmarkStageView.MODE_CPU)
                    || (i == 1 && mode == BenchmarkStageView.MODE_GPU)
                    || (i == 2 && mode == BenchmarkStageView.MODE_BOTH);
            chip.setTextColor(selected ? 0xFF071015 : 0xE6FFFFFF);
            chip.setBackground(Ui.rounded(selected ? ThemeColors.accent() : 0x16FFFFFF,
                    dp(10), selected ? 0x00000000 : 0x33FFFFFF, dp(1)));
        }
    }

    private LinearLayout inlineLine() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        return row;
    }

    private TextView inlineLabel(String label) {
        TextView v = text(label, 8f, MUTED, false);
        v.setLetterSpacing(0.11f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setMinWidth(dp(58));
        v.setPadding(0, 0, dp(6), 0);
        return v;
    }

    private TextView addChoice(LinearLayout row, String label, boolean selected,
                               final Runnable action) {
        TextView v = text(label, 10f, selected ? 0xFF071015 : 0xE6FFFFFF, false);
        if (("CPU".equals(label) || "GPU".equals(label)) && v instanceof LTextView) {
            ((LTextView) v).setLocalize(false);
            v.setText(label);
        }
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(7), dp(10), dp(7));
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(selected ? ThemeColors.accent() : 0x16FFFFFF,
                dp(10), selected ? 0x00000000 : 0x33FFFFFF, dp(1)));
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!running) action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(5);
        row.addView(v, lp);
        controls.add(v);
        return v;
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String modeText(String mode) {
        return "COMBINED".equals(mode) ? tr("COMBINED") : mode;
    }

    private String tr(String value) {
        return Loc.s(this, value);
    }

    private void startIdleMonitoring() {
        if (idleMonitoring || running || isFinishing() || isDestroyed()) return;
        idleMonitoring = true;
        idleFrameStartNs = 0L;
        idleFrames = 0L;
        idleFps = 0;
        if (stage != null) stage.startIdle();
        Choreographer.getInstance().postFrameCallback(idleFrameCallback);
        main.removeCallbacks(idleTick);
        main.post(idleTick);
    }

    private void stopIdleMonitoring() {
        if (!idleMonitoring) return;
        idleMonitoring = false;
        main.removeCallbacks(idleTick);
        Choreographer.getInstance().removeFrameCallback(idleFrameCallback);
    }

    private void startFanHold() {
        stopFanHold();
        if (fanManual && fanPct <= 0) return;
        fanHoldTask = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (fanManual) {
                    PerfTuner.setFanManualPServer(fanPct);
                    main.postDelayed(this, 2000L);
                } else {
                    int speed = fanCurveSpeed();
                    PerfTuner.setFanManualPServer(speed);
                    if (fanSpeedValue != null) fanSpeedValue.setText(speed + "%");
                    main.postDelayed(this, 500L);
                }
            }
        };
        main.postDelayed(fanHoldTask, 500L);
    }

    private float currentFanTemp() {
        float temp = Math.max(lastCpuTemp, lastGpuTemp);
        if (temp <= 0f) {
            float[] temps = PerfTuner.temperatures();
            temp = temps.length > 1 ? Math.max(temps[0], temps[1])
                    : temps.length > 0 ? temps[0] : 0f;
        }
        return temp;
    }

    private int fanCurveSpeed() {
        float temp = currentFanTemp();
        if (fanModeIndex == 0) {
            // Quiet: keep it civil, but never leave the device at a quarter
            // blower speed once it is genuinely hot.
            if (temp >= 90f) return 90;
            if (temp >= 85f) return 75;
            if (temp >= 80f) return 60;
            if (temp >= 70f) return 45;
            if (temp >= 60f) return 32;
            return 22;
        }
        if (fanModeIndex == 2) {
            // Sport: aggressive immediately and full speed from 80 C.
            if (temp >= 80f) return 100;
            if (temp >= 75f) return 95;
            if (temp >= 70f) return 85;
            if (temp >= 65f) return 75;
            if (temp >= 60f) return 65;
            if (temp >= 50f) return 50;
            return 40;
        }
        // Smart: responsive balanced curve. 80 C means 90%, not a quarter speed.
        if (temp >= 85f) return 100;
        if (temp >= 80f) return 90;
        if (temp >= 75f) return 80;
        if (temp >= 70f) return 68;
        if (temp >= 65f) return 56;
        if (temp >= 60f) return 46;
        if (temp >= 50f) return 36;
        return 30;
    }

    private String fanModeLabel() {
        if (fanModeIndex == 0) return "QUIET";
        if (fanModeIndex == 2) return "SPORT";
        return "SMART";
    }

    private void applyLiveTuning() {
        if (!running || !tuned || original == null) return;
        if (cpuEffPct > 0) stressCpu(original.efficiency, cpuEffPct, cpuEffGovernor);
        if (cpuPerfPct > 0) stressCpu(original.performance, cpuPerfPct, cpuPerfGovernor);
        if (gpuPct > 0 && original.gpu != null
                && original.gpu.freqs.length > 0) {
            int clock = frequencyForPct(original.gpu.freqs, gpuPct);
            PerfTuner.setGpuPServer(original.gpu, clock, clock);
        }
        if (fanManual) {
            if (fanPct > 0) PerfTuner.setFanManualPServer(fanPct);
        } else {
            PerfTuner.setFanManualPServer(fanCurveSpeed());
        }
    }

    private void stopFanHold() {
        if (fanHoldTask != null) {
            main.removeCallbacks(fanHoldTask);
            fanHoldTask = null;
        }
    }

    private int lengthToProgress(int seconds) {
        int sec = Math.max(LENGTH_MIN_SEC, Math.min(LENGTH_MAX_SEC, seconds));
        double t = Math.log(sec / (double) LENGTH_MIN_SEC)
                / Math.log(LENGTH_MAX_SEC / (double) LENGTH_MIN_SEC);
        return Math.max(0, Math.min(LENGTH_SLIDER_MAX,
                (int) Math.round(t * LENGTH_SLIDER_MAX)));
    }

    private int progressToLength(int progress) {
        double t = Math.max(0, Math.min(1, progress / (double) LENGTH_SLIDER_MAX));
        int sec = (int) Math.round(LENGTH_MIN_SEC
                * Math.pow(LENGTH_MAX_SEC / (double) LENGTH_MIN_SEC, t));
        if (sec >= 60) {
            int step = sec < 300 ? 5 : 15;
            sec = Math.round(sec / (float) step) * step;
        }
        return Math.max(LENGTH_MIN_SEC, Math.min(LENGTH_MAX_SEC, sec));
    }

    private String formatLength(int seconds) {
        int s = Math.max(0, seconds);
        if (s < 60) return s + "s";
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private TextView governorButton(final String initial, final boolean efficiency) {
        TextView b = text(initial == null ? "--" : initial, 6.8f, 0xE6FFFFFF, false);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setMinWidth(dp(66));
        b.setMaxWidth(dp(66));
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(focusedButtonBackground(0x22FFFFFF, 0x44FFFFFF));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cycleGovernor(efficiency);
            }
        });
        liveControls.add(b);
        return b;
    }

    private void cycleGovernor(boolean efficiency) {
        String[] list = efficiency ? cpuEffGovernors : cpuPerfGovernors;
        if (list.length == 0) return;
        String current = efficiency ? cpuEffGovernor : cpuPerfGovernor;
        int index = 0;
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(current)) {
                index = i;
                break;
            }
        }
        String next = list[(index + 1) % list.length];
        if (efficiency) {
            cpuEffGovernor = next;
            if (cpuEffGovButton != null) cpuEffGovButton.setText(next);
        } else {
            cpuPerfGovernor = next;
            if (cpuPerfGovButton != null) cpuPerfGovButton.setText(next);
        }
        applyLiveTuning();
    }

    private String[] governorOptions(PerfTuner.Cpu cpu) {
        if (cpu != null && cpu.governors != null && cpu.governors.length > 0) {
            return cpu.governors.clone();
        }
        if (cpu != null && cpu.governor != null && !cpu.governor.isEmpty()) {
            return new String[]{cpu.governor};
        }
        return new String[]{"schedutil"};
    }

    private String defaultGovernor(String[] options, String current) {
        for (String g : options) if ("performance".equals(g)) return g;
        if (current != null) for (String g : options) if (current.equals(g)) return current;
        return options.length == 0 ? "schedutil" : options[0];
    }

    private SeekBar percentControl(LinearLayout parent, String label,
                                   TextView value, int pct, final IntCallback callback,
                                   View endAction, boolean actionInHeader) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 7.5f, 0xD9FFFFFF, false);
        name.setLetterSpacing(0.09f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        value.setGravity(Gravity.RIGHT);
        header.addView(value);
        if (actionInHeader && endAction != null) {
            LinearLayout.LayoutParams headerActionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(23));
            headerActionLp.leftMargin = dp(6);
            header.addView(endAction, headerActionLp);
        }
        parent.addView(header, matchWrap());

        SeekBar bar = new SeekBar(this);
        bar.setMax(90);
        bar.setProgress(Math.max(0, Math.min(90, pct - 10)));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                callback.onValue(progress + 10);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        if (endAction == null || actionInHeader) {
            parent.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(21)));
        } else {
            LinearLayout barRow = new LinearLayout(this);
            barRow.setOrientation(LinearLayout.HORIZONTAL);
            barRow.setGravity(Gravity.CENTER_VERTICAL);
            barRow.addView(bar, new LinearLayout.LayoutParams(0, dp(21), 1f));
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(23));
            actionLp.leftMargin = dp(7);
            barRow.addView(endAction, actionLp);
            parent.addView(barRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(23)));
        }
        liveControls.add(bar);
        return bar;
    }

    private void updateClockLabels() {
        if (cpuEffClockValue != null) {
            cpuEffClockValue.setText(cpuEffPct + "%  ·  " + formatClock(
                    (long) frequencyForPct(cpuEffClockTable, cpuEffPct) * 1000L));
        }
        if (cpuPerfClockValue != null) {
            cpuPerfClockValue.setText(cpuPerfPct + "%  ·  " + formatClock(
                    (long) frequencyForPct(cpuPerfClockTable, cpuPerfPct) * 1000L));
        }
        if (gpuClockValue != null) {
            gpuClockValue.setText(gpuPct + "%  ·  " + formatClock(
                    frequencyForPct(gpuClockTable, gpuPct)));
        }
        if (fanSpeedValue != null) {
            fanSpeedValue.setText(fanManual ? fanPct + "%" : fanCurveSpeed() + "%");
        }
    }

    private StateListDrawable focusedButtonBackground(int baseFill, int baseStroke) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                Ui.rounded(baseFill, dp(7), 0xFFFFFFFF, dp(2)));
        states.addState(new int[]{}, Ui.rounded(baseFill, dp(7), baseStroke, dp(1)));
        return states;
    }

    private void updateFanModeButton() {
        if (fanModeButton == null) return;
        fanModeButton.setText(fanManual ? "MANUAL" : fanModeLabel());
        fanModeButton.setTextColor(!fanManual ? 0xFF071015 : 0xE6FFFFFF);
        fanModeButton.setBackground(focusedButtonBackground(
                !fanManual ? ThemeColors.accent() : 0x55FFFFFF,
                !fanManual ? 0x00000000 : 0xAAFFFFFF));
        if (fanSpeedValue != null) {
            fanSpeedValue.setText(fanManual ? fanPct + "%" : fanCurveSpeed() + "%");
        }
    }

    private int frequencyForPct(int[] freqs, int pct) {
        if (freqs == null || freqs.length == 0) return 0;
        int[] sorted = sortedCopy(freqs);
        int index = Math.round((sorted.length - 1) * Math.max(0, Math.min(100, pct)) / 100f);
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private int[] clockTable(PerfTuner.Cpu cpu) {
        return cpu == null ? new int[0] : sortedCopy(cpu.freqs);
    }

    private int[] sortedCopy(int[] values) {
        if (values == null || values.length == 0) return new int[0];
        int[] out = values.clone();
        java.util.Arrays.sort(out);
        return out;
    }

    private String formatClock(long hz) {
        if (hz <= 0) return "--";
        if (hz >= 1_000_000_000) {
            return String.format(Locale.US, "%.1fG", hz / 1_000_000_000f);
        }
        return Math.round(hz / 1_000_000f) + "M";
    }

    private void resetRunStats() {
        lastCpuUsage = 0;
        lastGpuUsage = 0;
        lastCpuTemp = 0f;
        lastGpuTemp = 0f;
        statsSamples = 0;
        cpuTempSum = 0;
        gpuTempSum = 0;
        cpuUseSum = 0;
        gpuUseSum = 0;
        fpsSum = 0;
        minFps = 0;
        maxFps = 0;
        maxCpuTemp = 0;
        maxGpuTemp = 0;
        maxCpuUse = 0;
        maxGpuUse = 0;
        fpsSamples = 0;
        cpuThrottleSum = 0;
        gpuThrottleSum = 0;
        cpuThrottleSamples = 0;
        gpuThrottleSamples = 0;
        cpuClockHistory.clear();
        gpuClockHistory.clear();
        lastClockSampleMs = 0L;
    }

    private void startRun() {
        if (running) return;
        stopIdleMonitoring();
        running = true;
        tuned = false;
        startMs = SystemClock.uptimeMillis();
        durationMs = durationSec * 1000L;
        cpuOps.set(0);
        cpuRun.set(true);
        warmingUp = false;
        if (warmupTask != null) main.removeCallbacks(warmupTask);
        resetRunStats();
        if (resultsButton != null) resultsButton.setVisibility(View.GONE);
        dismissResultDialog();
        if (stage != null) {
            stage.configure(mode, threads);
            stage.start();
            stage.setState(0f, 0f, 0f, 0, 0, 0f);
        }
        rootStatus.setText("CHECKING");
        rootStatus.setTextColor(0xFFFFC857);
        progressLabel.setText("PREPARING CONTROL…");
        setControlsEnabled(false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final boolean pserver = PerfTuner.preparePServer();
                pserverActive = pserver;
                original = PerfTuner.readStateLocal(BenchmarkActivity.this);
                if (!running) return;
                if (pserver && original != null) {
                    applyTuning(original);
                    tuned = true;
                }
                if (!running) {
                    restoreTuning();
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!running) return;
                        rootStatus.setText(pserver ? "PSERVER ONLY" : "PSERVER OFFLINE");
                        rootStatus.setTextColor(pserver ? 0xFF69D98B : 0xFFFFC857);
                        if (gpuWorkload != null) gpuWorkload.setRunning(gpuActive());
                        startCpuWorkers();
                        startFanHold();
                        warmingUp = true;
                        progressLabel.setText("WARMING UP…");
                        // Give the CPU/GPU time to reach their selected maximum
                        // and let the GPU shader/FPS loop settle before timing.
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!running || !tuned || original == null) return;
                                worker.execute(new Runnable() {
                                    @Override public void run() { applyTuning(original); }
                                });
                            }
                        }, 600L);
                        warmupTask = new Runnable() {
                            @Override public void run() {
                                if (!running) return;
                                warmingUp = false;
                                cpuOps.set(0);
                                resetRunStats();
                                startMs = SystemClock.uptimeMillis();
                                durationMs = durationSec * 1000L;
                                if (stage != null) {
                                    stage.configure(mode, threads);
                                    stage.setSampleInterval(Math.max(100L,
                                            durationMs / 240L));
                                    stage.start();
                                    stage.setState(0f, 0f, 0f, 0, 0, 0f);
                                }
                                progressLabel.setText(tr("RUNNING") + "  ·  "
                                        + formatLength(durationSec));
                                main.post(tick);
                            }
                        };
                        main.postDelayed(warmupTask, WARMUP_MS);
                    }
                });
            }
        });
    }

    private void startCpuWorkers() {
        if (mode == BenchmarkStageView.MODE_GPU) return;
        int count = Math.max(1, Math.min(threads, 8));
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(new CpuWorker(cpuRun, cpuOps), "benchmark-cpu-" + i);
            cpuThreads.add(t);
            t.start();
        }
    }

    private void applyTuning(PerfTuner.State s) {
        synchronized (tuningLock) {
            if (s == null) return;
        if (cpuEffPct > 0) stressCpu(s.efficiency, cpuEffPct, cpuEffGovernor);
        if (cpuPerfPct > 0) stressCpu(s.performance, cpuPerfPct, cpuPerfGovernor);
        if (gpuPct > 0 && s.gpu != null && s.gpu.freqs.length > 0) {
            int clock = frequencyForPct(s.gpu.freqs, gpuPct);
            PerfTuner.setGpuPServer(s.gpu, clock, clock);
        }
        if (fanManual) {
            if (fanPct > 0) PerfTuner.setFanManualPServer(fanPct);
        } else {
            PerfTuner.setFanManualPServer(fanCurveSpeed());
        }
        }
    }

    private void stressCpu(PerfTuner.Cpu c, int pct, String governor) {
        if (c == null || c.freqs.length == 0) return;
        int clock = frequencyForPct(c.freqs, pct);
        String gov = governor;
        boolean available = false;
        if (gov != null) {
            for (String g : c.governors) {
                if (gov.equals(g)) {
                    available = true;
                    break;
                }
            }
        }
        if (!available) gov = c.governor;
        PerfTuner.setCpuPServer(c, clock, clock, gov);
    }

    private void restoreTuning() {
        synchronized (tuningLock) {
            if (original == null) {
                tuned = false;
                return;
            }
            if (original.efficiency != null) {
                PerfTuner.setCpuPServer(original.efficiency, original.efficiency.max,
                        original.efficiency.min, original.efficiency.governor);
            }
            if (original.performance != null) {
                PerfTuner.setCpuPServer(original.performance, original.performance.max,
                        original.performance.min, original.performance.governor);
            }
            if (original.gpu != null) {
                PerfTuner.setGpuPServer(original.gpu, original.gpu.max, original.gpu.min);
            }
            if (original.fan != null) {
                PerfTuner.setFanModePServer(original.fan.mode);
                PerfTuner.setFanSpeedPServer(original.fan.speedPref);
            }
            original = null;
            tuned = false;
        }
    }

    private void recordSample(float cpuTemp, float gpuTemp, int cpuUse,
                             int gpuUse, float fps) {
        statsSamples++;
        if (cpuTemp > 0f) {
            cpuTempSum += cpuTemp;
            maxCpuTemp = Math.max(maxCpuTemp, cpuTemp);
        }
        if (gpuTemp > 0f) {
            gpuTempSum += gpuTemp;
            maxGpuTemp = Math.max(maxGpuTemp, gpuTemp);
        }
        cpuUseSum += cpuUse;
        gpuUseSum += gpuUse;
        maxCpuUse = Math.max(maxCpuUse, cpuUse);
        maxGpuUse = Math.max(maxGpuUse, gpuUse);
        if (fps > 0f) {
            fpsSamples++;
            fpsSum += fps;
            if (minFps <= 0f || fps < minFps) minFps = fps;
            maxFps = Math.max(maxFps, fps);
        }
    }

    private void recordThrottle(PerfStats stats) {
        if (stats == null) return;
        long now = SystemClock.uptimeMillis();
        boolean sampleClock = lastClockSampleMs == 0L
                || now - lastClockSampleMs >= 1000L;
        if (mode != BenchmarkStageView.MODE_GPU) {
            long target = (long) frequencyForPct(cpuPerfClockTable, cpuPerfPct) * 1000L;
            if (target > 0 && stats.cpuHz > 0) {
                float ratio = Math.max(0f, Math.min(1f, stats.cpuHz / (float) target));
                cpuThrottleSum += ratio;
                cpuThrottleSamples++;
                if (sampleClock) appendClock(cpuClockHistory, Math.round(ratio * 100f));
            }
        }
        if (gpuActive()) {
            int target = frequencyForPct(gpuClockTable, gpuPct);
            if (target > 0 && stats.gpuHz > 0) {
                float ratio = Math.max(0f, Math.min(1f, stats.gpuHz / (float) target));
                gpuThrottleSum += ratio;
                gpuThrottleSamples++;
                if (sampleClock) appendClock(gpuClockHistory, Math.round(ratio * 100f));
            }
        }
        if (sampleClock) lastClockSampleMs = now;
    }

    private void appendClock(List<Integer> history, int value) {
        history.add(Math.max(0, Math.min(100, value)));
        while (history.size() > 1200) history.remove(0);
    }

    private BenchmarkResult buildResult(boolean completed, long elapsed,
                                        float cpuScore, float gpu,
                                        float cpuTemp, float gpuTemp) {
        BenchmarkResult r = new BenchmarkResult();
        r.timestamp = System.currentTimeMillis();
        r.durationMs = elapsed;
        r.mode = mode;
        r.threads = threads;
        r.lengthSec = durationSec;
        r.cpuEffTune = cpuEffPct;
        r.cpuTune = cpuPerfPct;
        r.gpuTune = gpuPct;
        r.fanTune = fanManual ? fanPct : -(fanModeIndex + 1);
        r.completed = completed;
        r.cpuScore = cpuScore;
        r.gpuFps = gpu;
        r.avgFps = fpsSamples > 0 ? (float) (fpsSum / fpsSamples) : gpu;
        r.minFps = minFps;
        r.maxFps = maxFps > 0f ? maxFps : gpu;
        r.cpuTempMax = maxCpuTemp > 0f ? maxCpuTemp : cpuTemp;
        r.cpuTempAvg = statsSamples > 0
                ? (float) (cpuTempSum / statsSamples) : cpuTemp;
        r.gpuTempMax = maxGpuTemp > 0f ? maxGpuTemp : gpuTemp;
        r.gpuTempAvg = statsSamples > 0
                ? (float) (gpuTempSum / statsSamples) : gpuTemp;
        r.cpuUseAvg = statsSamples > 0
                ? (int) Math.round(cpuUseSum / statsSamples) : lastCpuUsage;
        r.cpuUseMax = maxCpuUse;
        r.gpuUseAvg = statsSamples > 0
                ? (int) Math.round(gpuUseSum / statsSamples) : lastGpuUsage;
        r.gpuUseMax = maxGpuUse;
        r.samples = (int) statsSamples;
        r.cpuThrottlePct = cpuThrottleSamples > 0
                ? (int) Math.round(cpuThrottleSum * 100.0 / cpuThrottleSamples) : 100;
        r.gpuThrottlePct = gpuThrottleSamples > 0
                ? (int) Math.round(gpuThrottleSum * 100.0 / gpuThrottleSamples) : 100;
        r.cpuClockHistory = toIntArray(cpuClockHistory);
        r.gpuClockHistory = toIntArray(gpuClockHistory);
        if (stage != null) {
            r.fpsHistory = stage.fpsSnapshot();
            r.cpuTempHistory = stage.cpuTempSnapshot();
            r.gpuTempHistory = stage.gpuTempSnapshot();
            r.cpuUseHistory = stage.cpuUseSnapshot();
            r.gpuUseHistory = stage.gpuUseSnapshot();
        }
        r.normalizeThrottle();
        return r;
    }

    private void showResultDialog(final BenchmarkResult result) {
        if (result == null || isFinishing() || isDestroyed()) return;
        dismissResultDialog();
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
                0x660B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0xD8);
        panel.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        panel.setBackdropScreenAnchored(true);
        panel.setPadding(dp(22), dp(18), dp(22), dp(16));

        TextView eyebrow = text("TEST COMPLETE", 8.5f, ThemeColors.accent(), false);
        eyebrow.setLetterSpacing(0.16f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(eyebrow);
        TextView title = text("STRESS TEST RESULT", 17f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(2), 0, 0);
        panel.addView(title);
        TextView sub = text(modeText(result.modeLabel()) + "  ·  " + result.lengthLabel()
                        + "  ·  " + result.threads + " " + tr("THREADS"),
                8.5f, MUTED, true);
        sub.setPadding(0, dp(2), 0, dp(10));
        panel.addView(sub);

        boolean narrow = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || getResources().getConfiguration().screenWidthDp < 520;
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        View throttleCard = popupScoreCard("THROTTLE SCORE",
                result.throttleLabel(),
                tr("HELD") + " " + result.primaryHoldSec() + "s  ·  " + tr("SUS")
                        + " " + result.primarySustainedPct() + "%", result.throttleColor());
        View gpuCard = popupScoreCard("GPU FPS",
                String.format(Locale.US, "%.0f", result.gpuFps),
                tr("MAX") + " " + Math.round(result.gpuTempMax) + "\u00b0  ·  "
                        + result.gpuUseAvg + "% " + tr("LOAD"), ThemeColors.accent());
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
        panel.addView(cards, matchWrap());

        addPopupRow(panel, "CPU TEMP",
                Math.round(result.cpuTempAvg) + "\u00b0 " + tr("AVG"),
                Math.round(result.cpuTempMax) + "\u00b0 " + tr("MAX"));
        addPopupRow(panel, "GPU TEMP",
                Math.round(result.gpuTempAvg) + "\u00b0 " + tr("AVG"),
                Math.round(result.gpuTempMax) + "\u00b0 " + tr("MAX"));
        addPopupRow(panel, "CPU HOLD",
                result.cpuHeldPct + "% " + tr("CLOCK"), result.cpuHoldSec + "s");
        addPopupRow(panel, "GPU HOLD",
                result.gpuHeldPct + "% " + tr("CLOCK"), result.gpuHoldSec + "s");
        addPopupRow(panel, "FRAME RATE",
                String.format(Locale.US, "%.0f ", result.avgFps) + tr("AVG"),
                String.format(Locale.US, "%.0f–%.0f FPS", result.minFps, result.maxFps));
        addPopupRow(panel, "LOAD", result.cpuUseAvg + "% CPU", result.gpuUseAvg + "% GPU");
        addPopupRow(panel, "TUNING", result.tuneLabel(this), "PSERVER ONLY");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, 0);
        TextView view = button("VIEW FULL RESULT", true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissResultDialogAnimated(new Runnable() {
                    @Override public void run() { openResultPage(result); }
                });
            }
        });
        TextView close = button("CLOSE", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissResultDialogAnimated(null); }
        });
        buttons.addView(view, new LinearLayout.LayoutParams(0, dp(38), 1.45f));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(38), 0.75f);
        closeLp.leftMargin = dp(8);
        buttons.addView(close, closeLp);
        panel.addView(buttons, matchWrap());

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                    dp(560));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.54f;
            window.setAttributes(lp);
        }
        dialog.setCanceledOnTouchOutside(false);
        resultDialog = dialog;
        resultPanel = panel;
        dialog.show();
        final ControllerNav dialogNav = new ControllerNav(this, null);
        dialogNav.attachDialog(dialog, panel);
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int keyCode,
                                           android.view.KeyEvent event) {
                if (dialogNav.dispatchKeyEvent(event)) return true;
                boolean back = keyCode == android.view.KeyEvent.KEYCODE_BACK
                        || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B;
                if (back && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    dismissResultDialogAnimated(null);
                    return true;
                }
                return false;
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                dialogNav.release();
                if (resultDialog == dialog) resultDialog = null;
                resultPanel = null;
            }
        });
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180L).start();
        view.requestFocus();
    }

    private View popupScoreCard(String label, String value, String sub, int valueColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(Ui.rounded(0x22101820, dp(14),
                0x22FFFFFF, dp(1)));
        TextView l = text(label, 7.5f, MUTED, false);
        l.setLetterSpacing(0.12f);
        l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(l);
        TextView v = text(value, 20f, valueColor, true);
        v.setPadding(0, dp(1), 0, 0);
        card.addView(v);
        TextView s = text(sub, 8f, MUTED, true);
        s.setPadding(0, dp(2), 0, 0);
        card.addView(s);
        return card;
    }

    private void addPopupRow(LinearLayout panel, String label, String value, String extra) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = text(label, 8.5f, MUTED, false);
        l.setMinWidth(dp(84));
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 9.5f, TEXT, true);
        v.setGravity(Gravity.RIGHT);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        TextView e = text(extra, 8f, MUTED, true);
        e.setGravity(Gravity.RIGHT);
        row.addView(e, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(row, matchWrap());
    }

    private String formatOps(float score) {
        if (score >= 1_000_000f) {
            return String.format(Locale.US, "%.1fM", score / 1_000_000f);
        }
        return String.format(Locale.US, "%.0fK", score / 1_000f);
    }

    private void openResultPage(BenchmarkResult result) {
        if (result == null && BenchmarkResultStore.load(this).isEmpty()) return;
        dismissResultDialog();
        Intent intent = new Intent(this, BenchmarkResultActivity.class);
        startActivity(intent);
    }

    private void dismissResultDialog() {
        Dialog dialog = resultDialog;
        resultDialog = null;
        resultPanel = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private void dismissResultDialogAnimated(final Runnable after) {
        final Dialog dialog = resultDialog;
        final View panel = resultPanel;
        resultDialog = null;
        resultPanel = null;
        if (dialog == null) {
            if (after != null) after.run();
            return;
        }
        if (panel == null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
            if (after != null) after.run();
            return;
        }
        panel.animate().alpha(0f).scaleX(0.965f).scaleY(0.965f)
                .setDuration(170L)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (after != null) after.run();
                    }
                }).start();
    }

    private void finishRun(boolean completed) {
        if (!running) return;
        running = false;
        warmingUp = false;
        if (warmupTask != null) {
            main.removeCallbacks(warmupTask);
            warmupTask = null;
        }
        cpuRun.set(false);
        main.removeCallbacks(tick);
        stopFanHold();
        if (gpuWorkload != null) gpuWorkload.setRunning(false);
        if (stage != null) stage.stop();
        for (Thread t : cpuThreads) {
            if (t != null) t.interrupt();
        }
        cpuThreads.clear();
        final long elapsed = Math.max(0, SystemClock.uptimeMillis() - startMs);
        final float cpuScore = elapsed > 0 ? cpuOps.get() / (elapsed / 1000f) : 0f;
        final float gpu = gpuWorkload != null && gpuWorkload.fps() > 0f
                ? gpuWorkload.fps() : stage != null ? stage.gpuFps() : 0f;
        progressLabel.setText(completed ? "COMPLETE" : "STOPPED");
        PerfStats stats = sampler != null ? sampler.sample(this, false) : null;
        float cpuTemp = stats != null ? stats.cpuTempC : 0f;
        float gpuTemp = stats != null ? stats.gpuTempC : 0f;
        int cpuUse = lastCpuUsage;
        int gpuUse = gpuActive() ? lastGpuUsage : 0;
        if (cpuUse == 0 && mode != BenchmarkStageView.MODE_GPU) {
            cpuUse = Math.min(100, Math.round(threads * 100f / 8f));
        }
        if (gpuUse == 0 && gpuActive()) {
            gpuUse = Math.max(1, Math.min(100, Math.round(gpu / 120f * 100f)));
        }
        lastCpuUsage = cpuUse;
        lastGpuUsage = gpuUse;
        if (statsSamples == 0) {
            recordSample(cpuTemp, gpuTemp, cpuUse, gpuUse, gpu);
        }
        float[] temps = new float[]{cpuTemp, gpuTemp};
        if (stage != null) stage.setState(completed ? 1f : Math.min(1f, elapsed / (float) durationMs),
                cpuTemp, gpuTemp, cpuUse, gpuUse, gpu);
        updateResults(cpuScore, gpu, temps);
        lastResult = buildResult(completed, elapsed, cpuScore, gpu, cpuTemp, gpuTemp);
        BenchmarkResultStore.add(this, lastResult);
        if (resultsButton != null) {
            resultsButton.setVisibility(View.VISIBLE);
            resultsButton.setEnabled(true);
        }
        if (completed) showResultDialog(lastResult);
        startIdleMonitoring();
        worker.execute(new Runnable() {
            @Override public void run() {
                restoreTuning();
                main.post(new Runnable() {
                    @Override public void run() {
                        setControlsEnabled(true);
                    }
                });
            }
        });
    }

    private int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private boolean gpuActive() {
        return mode == BenchmarkStageView.MODE_GPU || mode == BenchmarkStageView.MODE_BOTH;
    }

    private void updateResults(float cpuScore, float gpuFps, float[] temps) {
        if (cpuResult != null) {
            cpuResult.setText(String.format(Locale.US, "CPU   %d%%  %.1fM ops/s",
                    lastCpuUsage, cpuScore / 1_000_000f));
            if (temps.length > 0) {
                cpuResult.append("   " + Math.round(temps[0]) + "\u00b0");
            }
        }
        if (gpuResult != null) {
            gpuResult.setText(String.format(Locale.US, "GPU   %d%%  %.0f FPS",
                    lastGpuUsage, gpuFps));
            if (temps.length > 1) {
                gpuResult.append("   " + Math.round(temps[1]) + "\u00b0");
            }
        }
    }

    private void setControlsEnabled(boolean enabled) {
        View before = getCurrentFocus();
        for (View v : controls) {
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : 0.45f);
        }
        if (startButton != null) startButton.setAlpha(enabled ? 1f : 0.35f);
        if (stopButton != null) stopButton.setAlpha(enabled ? 0.35f : 1f);
        startButton.setEnabled(enabled);
        stopButton.setEnabled(!enabled);
        final TextView target = enabled ? startButton : stopButton;
        if (before == startButton || before == stopButton
                || (before != null && !before.isEnabled())) {
            target.post(new Runnable() {
                @Override public void run() {
                    if (target.isEnabled()) target.requestFocus();
                }
            });
        }
    }

    private interface IntCallback {
        void onValue(int value);
    }

    private static final class CpuWorker implements Runnable {
        private final AtomicBoolean running;
        private final AtomicLong ops;

        CpuWorker(AtomicBoolean running, AtomicLong ops) {
            this.running = running;
            this.ops = ops;
        }

        @Override
        public void run() {
            double x = 0.123456789;
            long local = 0;
            while (running.get()) {
                for (int i = 0; i < 8192; i++) {
                    x = Math.sin(x * 1.000000119 + i * 0.000001) * 1.000000357;
                    x = Math.sqrt(Math.abs(x) + 1.0000001);
                }
                local += 8192;
                if (local >= 131072) {
                    ops.addAndGet(local);
                    local = 0;
                }
            }
            if (local > 0) ops.addAndGet(local);
        }
    }
}
