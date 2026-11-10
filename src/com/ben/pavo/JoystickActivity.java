package com.ben.pavo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import android.text.TextUtils;
import java.util.Locale;

/** Built-in joystick calibration and live stick accuracy test. */
public final class JoystickActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int ACCENT = 0xFFA9BEFF;

    private StickPadView pads;
    private TextView values;
    private Prefs prefs;
    private boolean remapWasEnabled;
    private boolean calibrating;
    private boolean calibrationApplied;
    private boolean stickEnabled;
    private int selectedStick;
    private boolean expertMode;
    private TextView expertChip;
    private final int[] deadzone = {60, 60};
    private final int[] curve = {100, 100};
    private final int[] floor = {0, 0};
    private TextView leftTab;
    private TextView rightTab;
    private TextView layerChip;
    private final List<View> nav = new ArrayList<>();
    private String targetPkg = "";
    private ControllerProfileStore profileStore;
    private AppPickerStripView appPicker;
    private FrameLayout rootView;
    private LinearLayout appSelector;
    private ImageView appSelectorIcon;
    private TextView appSelectorLabel;
    private TextView resetAppChip;
    private ControllerNav controller;
    private long lastNavAdjust;
    private long lastHatNav;
    private long lastStickNav;
    private View navPressTarget;
    private boolean navLongFired;
    private final Runnable navLongPress = new Runnable() {
        @Override public void run() {
            View v = navPressTarget;
            if (v == null || !v.isFocused()) return;
            navLongFired = true;
            if (v.isLongClickable()) {
                Haptics.longPress(v);
                v.performLongClick();
            }
        }
    };
    private final int[] centers = new int[6];
    private final int[] mins = new int[6];
    private final int[] maxs = new int[6];
    private final int[] rangeMin = new int[6];
    private final int[] rangeMax = new int[6];
    private static final int GATE_POINTS = ControllerMapConfig.GATE_POINTS;
    private final int[][] gateGain = new int[2][GATE_POINTS];
    private final boolean[] stickActive = new boolean[2];
    private final float[] gateLeft = new float[GATE_POINTS];
    private final float[] gateRight = new float[GATE_POINTS];
    private boolean gateEnabled;
    private boolean gateCapturing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean rangeCapturing;
    private SeekBar deadzoneBar;
    private SeekBar curveBar;
    private SeekBar floorBar;
    private TextView deadzoneValue;
    private TextView curveValue;
    private TextView floorValue;
    private float rawLx, rawLy, rawRx, rawRy;
    private TextView statusText;
    private float restMax;
    private long lastStatusAt;

    // Guided one-pass calibration: rest, then a single roll around the edge.
    private static final int GUIDE_NONE = 0;
    private static final int GUIDE_REST = 1;
    private static final int GUIDE_ROLL = 2;
    private static final long GUIDE_REST_MS = 1800L;
    private static final long GUIDE_ROLL_MS = 6000L;
    private int guidePhase = GUIDE_NONE;
    private long guidePhaseStart;
    private int guideScore = -1;
    private int restCount;
    private float restJitter;
    private final float[] restSum = new float[4];
    private final float[] restMinSeen = new float[4];
    private final float[] restMaxSeen = new float[4];
    private final float[] rollDxL = new float[GATE_POINTS];
    private final float[] rollDyL = new float[GATE_POINTS];
    private final float[] rollDxR = new float[GATE_POINTS];
    private final float[] rollDyR = new float[GATE_POINTS];
    private final float[] rollRL = new float[GATE_POINTS];
    private final float[] rollRR = new float[GATE_POINTS];
    private ProgressBar guideBar;
    private TextView guideText;
    private TextView autoChip;
    private TextView bypassChip;
    private final TextView[] presetChips = new TextView[7];
    private static final String[] PRESET_KEYS =
            {"LINEAR", "SMOOTH", "PRECISE", "FAST", "RACING", "FPS", "ACCEL"};
    private static final int[] PRESET_CURVE = {100, 130, 170, 70, 200, 100, 200};
    private static final int[] PRESET_FLOOR = {0, 0, 0, 0, 60, 0, 0};
    /** -1 leaves the current deadzone alone; FPS and ACCEL use 5%. */
    private static final int[] PRESET_DEADZONE = {-1, -1, -1, -1, -1, 50, 50};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        profileStore = new ControllerProfileStore(this);
        pads = new StickPadView(this);
        expertMode = prefs.stickExpert();
        ensureGlobalStickProfile();
        loadCalibration();
        ensureExpertForValues();
        buildUi();
        hideBars();
        controller = new ControllerNav(this, getWindow().getDecorView());
        controller.decorateFocusables();
        leftTab.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing() && !isDestroyed()) leftTab.requestFocus();
            }
        }, 180L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        calibrating = false;
        hideBars();
        hidePerformanceOverlay();
        remapWasEnabled = prefs.controllerRemapEnabled();
        ControllerProfiles.activityOpen = true;
        ControllerRemapManager.setPassthrough(this, true);
        handler.removeCallbacks(healthTick);
        handler.post(healthTick);
        PavoA11yService.setRemapUiActive(true);
        PavoInputService.setRemapUiActive(true);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(rangeDone);
        handler.removeCallbacks(gateDone);
        handler.removeCallbacks(guideTick);
        handler.removeCallbacks(healthTick);
        rangeCapturing = false;
        gateCapturing = false;
        if (guidePhase != GUIDE_NONE) {
            guidePhase = GUIDE_NONE;
            if (guideBar != null) guideBar.setVisibility(View.GONE);
            if (guideText != null) guideText.setVisibility(View.GONE);
        }
        ControllerRemapManager.setPassthrough(this, false);
        ControllerProfiles.activityOpen = false;
        ControllerProfiles.markDirty();
        if (!calibrating) restoreRemapper();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ControllerRemapManager.setPassthrough(this, false);
        ControllerProfiles.activityOpen = false;
        ControllerProfiles.markDirty();
        if (!calibrating) restoreRemapper();
        super.onDestroy();
    }

    private void restoreRemapper() {
        PavoA11yService.setRemapUiActive(false);
        PavoInputService.setRemapUiActive(false);
        if (remapWasEnabled || calibrationApplied) {
            ControllerProfiles.sync(this);
            if (!ControllerProfiles.bypassActive) {
                ControllerRemapManager.ensureStarted(this);
            }
        }
    }

    /**
     * The global profile must always carry a stick block, otherwise switching
     * back from an app profile has nothing to restore. Seed it from the live
     * config the first time the editor opens, before any app override exists.
     */
    private void ensureGlobalStickProfile() {
        try {
            if (profileStore.hasOverrides()) return;
            String g = profileStore.global();
            ControllerMapConfig global = new ControllerMapConfig();
            if (g != null) global.loadText(g);
            if (global.stickCalibrated) return;
            ControllerMapConfig live = new ControllerMapConfig();
            live.load(ControllerRemapManager.configFile(this));
            if (!live.stickCalibrated) return;
            global.copySticksFrom(live);
            profileStore.setGlobal(global.encode(prefs.controllerRemapEnabled(), global.capture));
            ControllerProfiles.markDirty();
        } catch (Throwable ignored) {
        }
    }

    /** Global stick profile, falling back to the live config. */
    private ControllerMapConfig globalStickConfig() {
        ControllerMapConfig cfg = new ControllerMapConfig();
        String body = profileStore == null ? null : profileStore.global();
        if (body != null) cfg.loadText(body);
        if (!cfg.stickCalibrated) cfg.load(ControllerRemapManager.configFile(this));
        return cfg;
    }

    /** Stick settings for the selected target, inheriting the global ones. */
    private ControllerMapConfig targetStickConfig() {
        ControllerMapConfig global = globalStickConfig();
        if (targetPkg.isEmpty() || profileStore == null) return global;
        String body = profileStore.app(targetPkg);
        if (body != null) {
            ControllerMapConfig app = new ControllerMapConfig();
            app.loadText(body);
            if (app.stickCalibrated) return app;
        }
        return global;
    }

    private ControllerMapConfig loadCalibration() {
        ControllerMapConfig cfg = targetStickConfig();
        stickEnabled = cfg.stickEnabled;
        System.arraycopy(cfg.stickDeadzone, 0, deadzone, 0, 2);
        System.arraycopy(cfg.stickCurve, 0, curve, 0, 2);
        System.arraycopy(cfg.stickFloor, 0, floor, 0, 2);
        System.arraycopy(cfg.stickCenter, 0, centers, 0, centers.length);
        System.arraycopy(cfg.stickMin, 0, mins, 0, mins.length);
        System.arraycopy(cfg.stickMax, 0, maxs, 0, maxs.length);
        gateEnabled = cfg.gateEnabled;
        System.arraycopy(cfg.gateGain[0], 0, gateGain[0], 0, GATE_POINTS);
        System.arraycopy(cfg.gateGain[1], 0, gateGain[1], 0, GATE_POINTS);
        return cfg;
    }

    private void captureCenter() {
        centers[0] = Math.round(rawLx * 32767f);
        centers[1] = Math.round(rawLy * 32767f);
        centers[2] = Math.round(rawRx * 32767f);
        centers[3] = Math.round(rawRy * 32767f);
        stickEnabled = true;
        updateLayerChip();
        pads.setAxes(0f, 0f, 0f, 0f);
        values.setText(String.format(Locale.US,
                "L  %+.2f / %+.2f        R  %+.2f / %+.2f",
                rawLx, rawLy, rawRx, rawRy));
    }

    private void saveCalibration(boolean enabled) {
        saveCalibration(enabled, false);
    }

    private void saveCalibration(boolean enabled, boolean quiet) {
        try {
            ControllerMapConfig cfg = targetProfileConfig();
            cfg.stickEnabled = enabled;
            cfg.stickCalibrated = true;
            System.arraycopy(centers, 0, cfg.stickCenter, 0, centers.length);
            System.arraycopy(mins, 0, cfg.stickMin, 0, mins.length);
            System.arraycopy(maxs, 0, cfg.stickMax, 0, maxs.length);
            System.arraycopy(deadzone, 0, cfg.stickDeadzone, 0, 2);
            System.arraycopy(curve, 0, cfg.stickCurve, 0, 2);
            System.arraycopy(floor, 0, cfg.stickFloor, 0, 2);
            cfg.gateEnabled = gateEnabled;
            System.arraycopy(gateGain[0], 0, cfg.gateGain[0], 0, GATE_POINTS);
            System.arraycopy(gateGain[1], 0, cfg.gateGain[1], 0, GATE_POINTS);
            writeTargetProfile(cfg);
            calibrationApplied = enabled;
            if (!quiet) {
                LToast.makeText(this, Loc.s(this, "Saved"), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void clearCalibration() {
        try {
            ControllerMapConfig cfg = targetProfileConfig();
            cfg.stickEnabled = false;
            cfg.stickCalibrated = false;
            writeTargetProfile(cfg);
            calibrationApplied = false;
        } catch (Throwable ignored) {
        }
    }

    /** Existing profile for the selected target, seeded from global mappings. */
    private ControllerMapConfig targetProfileConfig() {
        ControllerMapConfig cfg = new ControllerMapConfig();
        String body = targetPkg.isEmpty()
                ? profileStore.global() : profileStore.app(targetPkg);
        if (body != null) {
            cfg.loadText(body);
        } else if (!targetPkg.isEmpty()) {
            String global = profileStore.global();
            if (global != null) cfg.loadText(global);
        }
        return cfg;
    }

    private void writeTargetProfile(ControllerMapConfig cfg) {
        String text = cfg.encode(prefs.controllerRemapEnabled(), false);
        if (targetPkg.isEmpty()) {
            profileStore.setGlobal(text);
            // Keep the live file in step for the global profile.
            cfg.save(ControllerRemapManager.configFile(this),
                    prefs.controllerRemapEnabled(), false);
        } else {
            profileStore.setApp(targetPkg, text);
        }
        ControllerProfiles.markDirty();
    }

    private void resetCalibration() {
        for (int i = 0; i < centers.length; i++) {
            centers[i] = 0;
            mins[i] = -32767;
            maxs[i] = 32767;
        }
        deadzone[0] = 60;
        deadzone[1] = 60;
        curve[0] = 100;
        curve[1] = 100;
        floor[0] = 0;
        floor[1] = 0;
        if (deadzoneBar != null) deadzoneBar.setProgress(deadzone[selectedStick]);
        if (deadzoneValue != null) deadzoneValue.setText(percent(deadzone[selectedStick]));
        pads.setDeadzone(deadzone[selectedStick] / 1000f);
        gateEnabled = false;
        for (int s2 = 0; s2 < 2; s2++) {
            for (int i = 0; i < GATE_POINTS; i++) gateGain[s2][i] = 1000;
        }
        if (curveBar != null) curveBar.setProgress(Math.max(0, curve[selectedStick] - 50));
        if (curveValue != null) curveValue.setText(curve[selectedStick] + "%");
        if (floorBar != null) floorBar.setProgress(floor[selectedStick]);
        if (floorValue != null) floorValue.setText(percent(floor[selectedStick]));
        stickEnabled = false;
        updateLayerChip();
        updatePresetChips();
        clearCalibration();
        pads.setAxes(rawLx, rawLy, rawRx, rawRy);
    }

    private void startRangeCapture() {
        if (rangeCapturing) return;
        rangeCapturing = true;
        for (int i = 0; i < rangeMin.length; i++) {
            rangeMin[i] = Integer.MAX_VALUE;
            rangeMax[i] = Integer.MIN_VALUE;
        }
        LToast.makeText(this, Loc.s(this,
                "Rotate both sticks around their full edge for five seconds."),
                Toast.LENGTH_LONG).show();
        handler.removeCallbacks(rangeDone);
        handler.postDelayed(rangeDone, 5000L);
    }

    private final Runnable rangeDone = new Runnable() {
        @Override public void run() {
            if (!rangeCapturing) return;
            rangeCapturing = false;
            for (int i = 0; i < 4; i++) {
                if (rangeMax[i] != Integer.MIN_VALUE
                        && rangeMax[i] - rangeMin[i] > 4000) {
                    mins[i] = rangeMin[i];
                    maxs[i] = rangeMax[i];
                }
            }
            float[] c = corrected(rawLx, rawLy, rawRx, rawRy);
            pads.setAxes(c[0], c[1], c[2], c[3]);
            LToast.makeText(JoystickActivity.this,
                    Loc.s(JoystickActivity.this, "Range captured"),
                    Toast.LENGTH_SHORT).show();
        }
    };

    private void startGateCapture() {
        if (gateCapturing) return;
        gateCapturing = true;
        java.util.Arrays.fill(gateLeft, 0f);
        java.util.Arrays.fill(gateRight, 0f);
        LToast.makeText(this, Loc.s(this,
                "Roll both sticks fully around the edge for six seconds."),
                Toast.LENGTH_LONG).show();
        handler.removeCallbacks(gateDone);
        handler.postDelayed(gateDone, 6000L);
    }

    private final Runnable gateDone = new Runnable() {
        @Override public void run() {
            if (!gateCapturing) return;
            gateCapturing = false;
            int[] left = buildGate(gateLeft);
            int[] right = buildGate(gateRight);
            if (left == null || right == null) {
                LToast.makeText(JoystickActivity.this,
                        Loc.s(JoystickActivity.this, "Gate needs a full circle"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            System.arraycopy(left, 0, gateGain[0], 0, GATE_POINTS);
            System.arraycopy(right, 0, gateGain[1], 0, GATE_POINTS);
            gateEnabled = true;
            stickEnabled = true;
            updateLayerChip();
            float[] c = corrected(rawLx, rawLy, rawRx, rawRy);
            pads.setAxes(c[0], c[1], c[2], c[3]);
            LToast.makeText(JoystickActivity.this,
                    Loc.s(JoystickActivity.this, "Gate captured"),
                    Toast.LENGTH_SHORT).show();
        }
    };

    // ---- Guided calibration -------------------------------------------------

    /** One pass: rest for a stable centre, then a full roll for range and gate. */
    private void startGuided() {
        if (guidePhase != GUIDE_NONE) {
            cancelGuided();
            return;
        }
        rangeCapturing = false;
        gateCapturing = false;
        handler.removeCallbacks(rangeDone);
        handler.removeCallbacks(gateDone);
        guidePhase = GUIDE_REST;
        guidePhaseStart = SystemClock.uptimeMillis();
        guideScore = -1;
        restCount = 0;
        restJitter = 0f;
        Arrays.fill(restSum, 0f);
        Arrays.fill(restMinSeen, 1f);
        Arrays.fill(restMaxSeen, -1f);
        if (guideText != null) {
            guideText.setVisibility(View.VISIBLE);
            guideText.setText(Loc.s(this, "Release both sticks"));
        }
        if (guideBar != null) {
            guideBar.setVisibility(View.VISIBLE);
            guideBar.setProgress(0);
        }
        handler.removeCallbacks(guideTick);
        handler.post(guideTick);
    }

    private void cancelGuided() {
        guidePhase = GUIDE_NONE;
        handler.removeCallbacks(guideTick);
        if (guideBar != null) guideBar.setVisibility(View.GONE);
        if (guideText != null) guideText.setVisibility(View.GONE);
    }

    private final Runnable guideTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (guidePhase == GUIDE_NONE) return;
            long now = SystemClock.uptimeMillis();
            long elapsed = now - guidePhaseStart;
            if (guidePhase == GUIDE_REST) {
                sampleRest(rawLx, rawLy, rawRx, rawRy);
                int pct = (int) Math.min(100, elapsed * 100 / GUIDE_REST_MS);
                if (guideBar != null) guideBar.setProgress(pct);
                if (guideText != null) {
                    guideText.setText(Loc.s(JoystickActivity.this, "Release both sticks")
                            + "  " + pct + "%");
                }
                if (elapsed >= GUIDE_REST_MS) beginRoll(now);
                handler.postDelayed(this, 100L);
                return;
            }
            if (guidePhase == GUIDE_ROLL) {
                sampleRollRange(rawLx, rawLy, rawRx, rawRy);
                if (rangeCapturing || gateCapturing) {
                    rangeCapturing = false;
                    gateCapturing = false;
                }
                int pct = (int) Math.min(100, elapsed * 100 / GUIDE_ROLL_MS);
                if (guideBar != null) guideBar.setProgress(pct);
                if (guideText != null) {
                    guideText.setText(Loc.s(JoystickActivity.this,
                            "Roll both sticks fully around the edge") + "  " + pct + "%");
                }
                if (elapsed >= GUIDE_ROLL_MS) finishGuided();
                else handler.postDelayed(this, 100L);
            }
        }
    };

    private void sampleRest(float lx, float ly, float rx, float ry) {
        if ((float) Math.hypot(lx, ly) > 0.28f
                || (float) Math.hypot(rx, ry) > 0.28f) {
            return;
        }
        float[] v = {lx, ly, rx, ry};
        restCount++;
        for (int i = 0; i < 4; i++) {
            restSum[i] += v[i];
            restMinSeen[i] = Math.min(restMinSeen[i], v[i]);
            restMaxSeen[i] = Math.max(restMaxSeen[i], v[i]);
        }
    }

    private void beginRoll(long now) {
        if (restCount >= 3) {
            for (int i = 0; i < 4; i++) {
                centers[i] = Math.round(restSum[i] / restCount * 32767f);
            }
        }
        restJitter = 0.02f;
        if (restCount > 0) {
            restJitter = 0f;
            for (int i = 0; i < 4; i++) {
                restJitter = Math.max(restJitter, (restMaxSeen[i] - restMinSeen[i]) * 0.5f);
            }
        }
        for (int i = 0; i < rangeMin.length; i++) {
            rangeMin[i] = Integer.MAX_VALUE;
            rangeMax[i] = Integer.MIN_VALUE;
        }
        Arrays.fill(rollDxL, 0f);
        Arrays.fill(rollDyL, 0f);
        Arrays.fill(rollDxR, 0f);
        Arrays.fill(rollDyR, 0f);
        Arrays.fill(rollRL, 0f);
        Arrays.fill(rollRR, 0f);
        guidePhase = GUIDE_ROLL;
        guidePhaseStart = now;
        if (guideText != null) {
            guideText.setText(Loc.s(this, "Roll both sticks fully around the edge"));
        }
    }

    /** Captures raw range and the raw point at the outer edge of every angle. */
    private void sampleRoll(float lx, float ly, float rx, float ry) {
        if (guidePhase != GUIDE_ROLL) return;
        sampleRollRange(lx, ly, rx, ry);
        sampleRawGate(rollDxL, rollDyL, rollRL, 0, 1,
                Math.round(lx * 32767f), Math.round(ly * 32767f));
        sampleRawGate(rollDxR, rollDyR, rollRR, 2, 3,
                Math.round(rx * 32767f), Math.round(ry * 32767f));
    }

    private void sampleRollRange(float lx, float ly, float rx, float ry) {
        if (guidePhase != GUIDE_ROLL) return;
        int[] raw = {
                Math.round(lx * 32767f), Math.round(ly * 32767f),
                Math.round(rx * 32767f), Math.round(ry * 32767f)
        };
        for (int i = 0; i < 4; i++) {
            if (raw[i] < rangeMin[i]) rangeMin[i] = raw[i];
            if (raw[i] > rangeMax[i]) rangeMax[i] = raw[i];
        }
    }

    private void sampleRawGate(float[] dxs, float[] dys, float[] radii,
                               int ix, int iy, int x, int y) {
        float dx = x - centers[ix];
        float dy = y - centers[iy];
        float r = (float) Math.hypot(dx, dy) / 32767f;
        if (r < 0.30f) return;
        int i = gateIndex(dx, dy);
        if (r > radii[i]) {
            radii[i] = r;
            dxs[i] = dx;
            dys[i] = dy;
        }
    }

    private void finishGuided() {
        guidePhase = GUIDE_NONE;
        handler.removeCallbacks(guideTick);
        for (int i = 0; i < 4; i++) {
            if (rangeMax[i] != Integer.MIN_VALUE
                    && rangeMax[i] - rangeMin[i] > 4000) {
                mins[i] = rangeMin[i];
                maxs[i] = rangeMax[i];
            }
        }
        float[] rl = normalizedGateRadii(rollDxL, rollDyL, rollRL, 0, 1);
        float[] rr = normalizedGateRadii(rollDxR, rollDyR, rollRR, 2, 3);
        int[] left = buildGate(rl);
        int[] right = buildGate(rr);
        if (left != null) {
            System.arraycopy(left, 0, gateGain[0], 0, GATE_POINTS);
            gateEnabled = true;
        }
        if (right != null) {
            System.arraycopy(right, 0, gateGain[1], 0, GATE_POINTS);
            gateEnabled = true;
        }
        guideScore = guidedScore(rl, rr, restJitter);
        stickEnabled = true;
        saveCalibration(true, true);
        syncTuningUi();
        if (guideBar != null) guideBar.setProgress(100);
        if (guideText != null) {
            guideText.setText(String.format(Locale.US, "%s %d%%  ·  %s",
                    Loc.s(this, "QUALITY"), guideScore, Loc.s(this, guidedGrade(guideScore))));
            guideText.setVisibility(View.VISIBLE);
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (guidePhase == GUIDE_NONE && guideBar != null) {
                    guideBar.setVisibility(View.GONE);
                }
            }
        }, 5000L);
        LToast.makeText(this, Loc.s(this, "Saved"), Toast.LENGTH_SHORT).show();
    }

    /** Converts raw edge points to the per-angle radius used by buildGate. */
    private float[] normalizedGateRadii(float[] dxs, float[] dys, float[] rawRadii,
                                        int ix, int iy) {
        float[] out = new float[GATE_POINTS];
        for (int i = 0; i < GATE_POINTS; i++) {
            if (rawRadii[i] <= 0.05f) continue;
            float dx = dxs[i];
            float dy = dys[i];
            float nx = dx >= 0f
                    ? dx / (float) Math.max(1, maxs[ix] - centers[ix])
                    : dx / (float) Math.max(1, centers[ix] - mins[ix]);
            float ny = dy >= 0f
                    ? dy / (float) Math.max(1, maxs[iy] - centers[iy])
                    : dy / (float) Math.max(1, centers[iy] - mins[iy]);
            out[i] = (float) Math.hypot(nx, ny);
        }
        return out;
    }

    private int guidedScore(float[] rl, float[] rr, float jitter) {
        // 48 of 64 angles is a complete roll; buildGate interpolates the rest.
        float coverage = Math.min(1f, Math.min(coverage(rl), coverage(rr)) * 64f / 48f);
        float roundness = Math.min(roundness(rl), roundness(rr));
        float steady = 1f - Math.min(1f, jitter / 0.08f);
        int score = Math.round(100f * (0.35f * coverage + 0.45f * roundness
                + 0.20f * steady));
        return Math.max(0, Math.min(100, score));
    }

    private static float coverage(float[] radii) {
        int valid = 0;
        for (float r : radii) if (r > 0.05f) valid++;
        return valid / (float) GATE_POINTS;
    }

    private static float roundness(float[] radii) {
        float sum = 0f;
        int n = 0;
        for (float r : radii) {
            if (r > 0.05f) {
                sum += r;
                n++;
            }
        }
        if (n < 8) return 0f;
        float mean = sum / n;
        float var = 0f;
        for (float r : radii) {
            if (r > 0.05f) var += (r - mean) * (r - mean);
        }
        float sd = (float) Math.sqrt(var / n);
        return Math.max(0f, Math.min(1f, 1f - sd / Math.max(0.08f, mean)));
    }

    private String guidedGrade(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 75) return "Good";
        if (score >= 55) return "Fair";
        return "Poor";
    }

    /** Records the furthest radius seen at each angle while the stick is rolled. */
    private static void sampleGate(float[] table, float x, float y) {
        float r = (float) Math.hypot(x, y);
        if (r < 0.30f) return;
        int i = gateIndex(x, y);
        if (r > table[i]) table[i] = r;
    }

    private static int gateIndex(float x, float y) {
        float a = (float) Math.atan2(y, x) + (float) Math.PI;
        int i = Math.round(a / (2f * (float) Math.PI) * GATE_POINTS);
        if (i < 0) i = 0;
        if (i >= GATE_POINTS) i = 0;
        return i;
    }

    /**
     * Turns the measured radius per angle into a gain table. Missing angles are
     * interpolated from their nearest neighbours and the result is smoothed so
     * the correction does not step at bucket edges.
     */
    private static int[] buildGate(float[] table) {
        int valid = 0;
        for (float v : table) if (v > 0.05f) valid++;
        if (valid < 8) return null;
        float[] r = new float[GATE_POINTS];
        for (int i = 0; i < GATE_POINTS; i++) {
            if (table[i] > 0.05f) {
                r[i] = table[i];
                continue;
            }
            int prev = -1;
            int next = -1;
            for (int d = 1; d <= GATE_POINTS; d++) {
                int p = (i - d + GATE_POINTS) % GATE_POINTS;
                if (table[p] > 0.05f) { prev = p; break; }
            }
            for (int d = 1; d <= GATE_POINTS; d++) {
                int n = (i + d) % GATE_POINTS;
                if (table[n] > 0.05f) { next = n; break; }
            }
            if (prev < 0 || next < 0) return null;
            int span = (next - prev + GATE_POINTS) % GATE_POINTS;
            int dist = (i - prev + GATE_POINTS) % GATE_POINTS;
            float f = span == 0 ? 0f : dist / (float) span;
            r[i] = table[prev] + (table[next] - table[prev]) * f;
        }
        for (int pass = 0; pass < 1; pass++) {
            float[] smooth = new float[GATE_POINTS];
            for (int i = 0; i < GATE_POINTS; i++) {
                float p = r[(i - 1 + GATE_POINTS) % GATE_POINTS];
                float n = r[(i + 1) % GATE_POINTS];
                smooth[i] = (p + 2f * r[i] + n) * 0.25f;
            }
            r = smooth;
        }
        int[] gains = new int[GATE_POINTS];
        for (int i = 0; i < GATE_POINTS; i++) {
            float radius = Math.max(0.35f, Math.min(1.6f, r[i]));
            float gain = Math.max(0.75f, Math.min(1.4f, 1f / radius));
            gains[i] = Math.round(gain * 1000f);
        }
        return gains;
    }

    private float gateGain(int stick, float x, float y) {
        if (!gateEnabled || stick < 0 || stick > 1) return 1f;
        if (x == 0f && y == 0f) return 1f;
        float pos = ((float) Math.atan2(y, x) + (float) Math.PI)
                / (2f * (float) Math.PI) * GATE_POINTS;
        int i0 = Math.max(0, Math.min(GATE_POINTS - 1, (int) pos));
        int i1 = (i0 + 1) % GATE_POINTS;
        float f = pos - i0;
        float gain = (1f - f) * gateGain[stick][i0] + f * gateGain[stick][i1];
        return gain / 1000f;
    }

    /** Redraws the pads and readout with the current tuning, without input. */
    private void refreshPreview() {
        float[] c = corrected(rawLx, rawLy, rawRx, rawRy);
        pads.setAxes(c[0], c[1], c[2], c[3]);
        pads.setRaw(axisNormalized(rawLx, 0), axisNormalized(rawLy, 1),
                axisNormalized(rawRx, 2), axisNormalized(rawRy, 3));
        pads.setGate(gateGain[0], gateGain[1], gateEnabled);
        values.setText(String.format(Locale.US,
                "L  %+.2f / %+.2f        R  %+.2f / %+.2f", c[0], c[1], c[2], c[3]));
    }

    private float[] corrected(float lx, float ly, float rx, float ry) {
        float[] out = new float[4];
        radialPair(lx, ly, 0, 1, out, 0);
        radialPair(rx, ry, 2, 3, out, 2);
        return out;
    }

    /** Mirrors the native radial correction: unit-circle clamp, circular
     *  deadzone and magnitude curve, with the direction preserved. */
    private void radialPair(float x, float y, int ix, int iy, float[] out, int at) {
        if (!stickEnabled) {
            out[at] = x;
            out[at + 1] = y;
            return;
        }
        float nx = axisNormalized(x, ix);
        float ny = axisNormalized(y, iy);
        float gain = gateGain(ix >> 1, nx, ny);
        nx *= gain;
        ny *= gain;
        float r = (float) Math.hypot(nx, ny);
        if (r > 1f) {
            nx /= r;
            ny /= r;
            r = 1f;
        }
        int stick = ix >> 1;
        float dz = Math.max(0f, Math.min(0.8f, deadzone[stick] / 1000f));
        if (r > dz) stickActive[stick] = true;
        else if (r < dz * 0.75f) stickActive[stick] = false;
        if (!stickActive[stick]) {
            out[at] = 0f;
            out[at + 1] = 0f;
            return;
        }
        float m = Math.max(0f, (r - dz) / (1f - dz));
        float gamma = Math.max(0.2f, Math.min(4f, curve[ix >> 1] / 100f));
        if (m > 0f) {
            float base = m;
            float shaped = (float) Math.pow(base, gamma);
            float blend = 0.25f * (gamma > 1f ? gamma - 1f : 0f);
            if (blend > 0f) {
                float w = blend * (1f - base);
                m = w * base + (1f - w) * shaped;
            } else {
                m = shaped;
            }
        }
        float fl = Math.min(0.9f, floor[stick] / 1000f);
        if (fl > 0f) {
            float ramp = Math.min(1f, m / 0.15f);
            m = (1f - fl) * m + fl * ramp;
        }
        if (r > 0f) {
            out[at] = nx / r * m;
            out[at + 1] = ny / r * m;
        } else {
            out[at] = 0f;
            out[at + 1] = 0f;
        }
    }

    private float axisNormalized(float value, int index) {
        int raw = Math.round(value * 32767f);
        int c = centers[index];
        int mn = mins[index];
        int mx = maxs[index];
        float n = raw >= c ? (raw - c) / (float) Math.max(1, mx - c)
                : (raw - c) / (float) Math.max(1, c - mn);
        return Math.max(-1f, Math.min(1f, n));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;

        GlassPanel card = new GlassPanel(this, null, ACCENT, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        card.setPadding(dp(20), dp(10), dp(20), dp(12));
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
        updateAppSelector();

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(8);
        column.addView(divider, dlp);

        column.addView(pads, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        values = text("L  +0.00 / +0.00        R  +0.00 / +0.00",
                9.5f, 0xB3FFFFFF, true);
        values.setGravity(Gravity.CENTER);
        values.setPadding(0, dp(1), 0, dp(2));
        column.addView(values);

        LinearLayout guideRow = new LinearLayout(this);
        guideRow.setOrientation(LinearLayout.VERTICAL);
        guideText = text("", 8.5f, ACCENT, true);
        guideText.setGravity(Gravity.CENTER);
        guideText.setVisibility(View.GONE);
        guideRow.addView(guideText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        guideBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        guideBar.setMax(100);
        guideBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        guideBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        guideBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams guideBarLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        guideBarLp.topMargin = dp(1);
        guideBarLp.bottomMargin = dp(2);
        guideRow.addView(guideBar, guideBarLp);
        column.addView(guideRow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusText = text("", 8.5f, MUTED, false);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        statusText.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (stickEnabled && !ControllerRemapManager.isRunning(JoystickActivity.this)) {
                    ControllerRemapManager.start(JoystickActivity.this, null);
                    updateStatus();
                }
            }
        });
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        autoChip = text("AUTO", 8.5f, 0xFF69D98B, true);
        autoChip.setPadding(dp(8), dp(4), dp(8), dp(4));
        autoChip.setFocusable(true);
        autoChip.setDefaultFocusHighlightEnabled(false);
        autoChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                boolean on = !prefs.controllerStickAutoApp();
                prefs.setControllerStickAutoApp(on);
                ControllerProfiles.markDirty();
                updateAutoChip();
            }
        });
        autoChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateAutoChip();
            }
        });
        tapClicks(autoChip);
        addNav(autoChip);
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.rightMargin = dp(6);
        statusRow.addView(autoChip, autoLp);

        bypassChip = text("BYPASS", 8.5f, MUTED, true);
        bypassChip.setPadding(dp(8), dp(4), dp(8), dp(4));
        bypassChip.setFocusable(true);
        bypassChip.setDefaultFocusHighlightEnabled(false);
        bypassChip.setVisibility(View.GONE);
        bypassChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                toggleBypass();
            }
        });
        bypassChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateBypassChip();
            }
        });
        tapClicks(bypassChip);
        addNav(bypassChip);
        statusRow.addView(bypassChip);

        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = dp(4);
        column.addView(statusRow, statusLp);

        LinearLayout tuning = panel(column);

        LinearLayout stickRow = new LinearLayout(this);
        stickRow.setOrientation(LinearLayout.HORIZONTAL);
        leftTab = stickTab("Left");
        leftTab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectStick(0); }
        });
        rightTab = stickTab("Right");
        rightTab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectStick(1); }
        });
        leftTab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateStickTabs();
            }
        });
        rightTab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateStickTabs();
            }
        });
        tapClicks(leftTab);
        tapClicks(rightTab);
        addNav(leftTab);
        addNav(rightTab);
        stickRow.addView(leftTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightParams.leftMargin = dp(6);
        stickRow.addView(rightTab, rightParams);

        expertChip = text("EXPERT", 8f, MUTED, true);
        expertChip.setGravity(Gravity.CENTER);
        expertChip.setPadding(dp(8), dp(5), dp(8), dp(5));
        expertChip.setFocusable(true);
        expertChip.setDefaultFocusHighlightEnabled(false);
        expertChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                toggleExpert();
            }
        });
        expertChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateExpertChip();
            }
        });
        tapClicks(expertChip);
        addNav(expertChip);
        LinearLayout.LayoutParams expertLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        expertLp.leftMargin = dp(6);
        stickRow.addView(expertChip, expertLp);
        tuning.addView(stickRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout tuningRow = new LinearLayout(this);
        tuningRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        tuningRow.addView(leftCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rightColLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightColLp.leftMargin = dp(16);
        tuningRow.addView(rightCol, rightColLp);
        tuning.addView(tuningRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.setFillViewport(true);
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < PRESET_KEYS.length; i++) {
            final int preset = i;
            TextView chip = text(PRESET_KEYS[i], 8f, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), dp(5), dp(4), dp(5));
            chip.setFocusable(true);
            chip.setDefaultFocusHighlightEnabled(false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    applyPreset(preset);
                }
            });
            chip.setLongClickable(true);
            chip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Haptics.longPress(v);
                    applyPreset(preset);
                    LToast.makeText(JoystickActivity.this,
                            Loc.s(JoystickActivity.this, PRESET_KEYS[preset]) + " "
                                    + Loc.s(JoystickActivity.this, "RESET"),
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean focused) {
                    updatePresetChips();
                }
            });
            tapClicks(chip);
            addNav(chip);
            presetChips[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            chip.setMinWidth(dp(72));
            chip.setPadding(dp(8), dp(5), dp(8), dp(5));
            if (i > 0) lp.leftMargin = dp(4);
            presetRow.addView(chip, lp);
        }
        presetScroll.addView(presetRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams presetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetLp.topMargin = dp(5);
        tuning.addView(presetScroll, presetLp);

        deadzoneValue = text(percent(deadzone[selectedStick]), 9.5f, TEXT, true);
        addSlider(leftCol, "Deadzone", deadzone[selectedStick], 300, 0);
        curveValue = text(curve[selectedStick] + "%", 9.5f, TEXT, true);
        addSlider(rightCol, "CURVE", Math.max(0, curve[selectedStick] - 50), 150, 1);
        floorValue = text(percent(floor[selectedStick]), 9.5f, TEXT, true);
        addSlider(tuning, "Min output", floor[selectedStick], 300, 2);
        updateStickTabs();
        updateLayerChip();
        updatePresetChips();
        updateAutoChip();
        updateBypassChip();
        ensureExpertForValues();
        applyExpertLimits();
        updateExpertChip();
        refreshPreview();

        LinearLayout tuneRow = new LinearLayout(this);
        tuneRow.setOrientation(LinearLayout.HORIZONTAL);
        tuneRow.setGravity(Gravity.CENTER);

        TextView capture = smallButton("Center", false);
        capture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Haptics.click(v); captureCenter(); }
        });
        tuneRow.addView(capture, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addNav(capture);

        TextView range = smallButton("Range", false);
        range.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Haptics.click(v); startRangeCapture(); }
        });
        LinearLayout.LayoutParams rglp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rglp.leftMargin = dp(6);
        tuneRow.addView(range, rglp);
        addNav(range);

        TextView gate = smallButton("Gate", false);
        gate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Haptics.click(v); startGateCapture(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        glp.leftMargin = dp(6);
        tuneRow.addView(gate, glp);
        addNav(gate);

        TextView apply = smallButton("Apply", true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                saveCalibration(stickEnabled, false);
            }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        al.leftMargin = dp(6);
        tuneRow.addView(apply, al);
        addNav(apply);

        TextView reset = smallButton("RESET", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Haptics.click(v); resetCalibration(); }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rl.leftMargin = dp(6);
        tuneRow.addView(reset, rl);
        addNav(reset);

        LinearLayout.LayoutParams tuneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tuneLp.topMargin = dp(4);
        column.addView(tuneRow, tuneLp);

        LinearLayout calRow = new LinearLayout(this);
        calRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView guided = smallButton("GUIDED", true);
        guided.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                startGuided();
            }
        });
        calRow.addView(guided, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        addNav(guided);
        TextView odin = smallButton("ODIN", false);
        odin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                openOdinCalibration();
            }
        });
        LinearLayout.LayoutParams odinLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        odinLp.leftMargin = dp(6);
        calRow.addView(odin, odinLp);
        addNav(odin);
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        calLp.topMargin = dp(8);
        column.addView(calRow, calLp);

        setContentView(root);
        root.post(new Runnable() {
            @Override public void run() {
                if (leftTab != null && !isFinishing()) leftTab.requestFocus();
            }
        });
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("JOYSTICKS", 19f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = text("CALIBRATION", 9.5f, MUTED, false);
        sub.setLetterSpacing(0.13f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(titles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout leftControls = new LinearLayout(this);
        leftControls.setOrientation(LinearLayout.HORIZONTAL);
        leftControls.setGravity(Gravity.CENTER_VERTICAL);

        appSelector = new LinearLayout(this);
        appSelector.setOrientation(LinearLayout.HORIZONTAL);
        appSelector.setGravity(Gravity.CENTER_VERTICAL);
        appSelector.setPadding(dp(7), dp(4), dp(8), dp(4));
        appSelector.setBackground(Ui.rounded(0x1EFFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        appSelector.setClickable(true);
        appSelector.setFocusable(true);
        appSelector.setFocusable(true);
        appSelector.setDefaultFocusHighlightEnabled(false);
        appSelector.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                openAppPicker();
            }
        });
        appSelector.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused
                        ? Ui.rounded(0x1EFFFFFF, dp(10), 0xE6FFFFFF, dp(1.5f))
                        : Ui.rounded(0x1EFFFFFF, dp(10), 0x33FFFFFF, dp(1)));
            }
        });
        tapClicks(appSelector);
        addNav(appSelector);

        appSelectorIcon = new ImageView(this);
        appSelectorIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appSelector.addView(appSelectorIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        appSelectorLabel = text("ALL APPS", 8.5f, TEXT, true);
        appSelectorLabel.setSingleLine(true);
        appSelectorLabel.setEllipsize(TextUtils.TruncateAt.END);
        appSelectorLabel.setMaxWidth(dp(96));
        appSelectorLabel.setPadding(dp(6), 0, dp(2), 0);
        appSelector.addView(appSelectorLabel);
        appSelector.addView(text("\u25BE", 8f, MUTED, true));
        leftControls.addView(appSelector);

        resetAppChip = text("USE GLOBAL", 8f, 0xFFFFC857, true);
        resetAppChip.setLetterSpacing(0.04f);
        resetAppChip.setGravity(Gravity.CENTER);
        resetAppChip.setPadding(dp(7), dp(5), dp(7), dp(5));
        resetAppChip.setBackground(Ui.rounded(0x22FFC857, dp(10), 0x55FFC857, dp(1)));
        resetAppChip.setVisibility(View.GONE);
        resetAppChip.setFocusable(true);
        resetAppChip.setFocusable(true);
        resetAppChip.setDefaultFocusHighlightEnabled(false);
        resetAppChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                resetSelectedApp();
            }
        });
        resetAppChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused
                        ? Ui.rounded(0x22FFC857, dp(10), 0xE6FFFFFF, dp(1.5f))
                        : Ui.rounded(0x22FFC857, dp(10), 0x55FFC857, dp(1)));
            }
        });
        tapClicks(resetAppChip);
        addNav(resetAppChip);
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.leftMargin = dp(6);
        leftControls.addView(resetAppChip, resetLp);

        layerChip = text("ON", 9f, TEXT, true);
        layerChip.setLetterSpacing(0.08f);
        layerChip.setGravity(Gravity.CENTER);
        layerChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        layerChip.setFocusable(true);
        layerChip.setFocusable(true);
        layerChip.setDefaultFocusHighlightEnabled(false);
        layerChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                setStickLayer(!stickEnabled, true);
            }
        });
        layerChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateLayerChip();
            }
        });
        tapClicks(layerChip);
        LinearLayout.LayoutParams onLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        onLp.leftMargin = dp(6);
        leftControls.addView(layerChip, onLp);
        addNav(layerChip);
        header.addView(leftControls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        TextView close = text("\u00D7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setFocusable(true);
        close.setDefaultFocusHighlightEnabled(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                finishAnimated();
            }
        });
        close.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused
                        ? Ui.rounded(0x00000000, dp(12), 0xE6FFFFFF, dp(1.5f))
                        : new ColorDrawable(0x00000000));
            }
        });
        header.addView(close, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        addNav(close);
        return header;
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(7), dp(14), dp(7));
        box.setBackground(Ui.rounded(0x12FFFFFF, dp(18), 0x22FFFFFF, dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        parent.addView(box, lp);
        return box;
    }

    private void openOdinCalibration() {
        calibrating = true;
        ControllerRemapManager.stop(this);
        PavoA11yService.setRemapUiActive(true);
        PavoInputService.setRemapUiActive(true);
        Intent i = new Intent();
        i.setClassName("com.odin.factorytest",
                "com.ro.factorytest.itemstest.GamepadCalibrationActivity");
        try {
            startActivity(i);
        } catch (Throwable t) {
            calibrating = false;
            restoreRemapper();
            LToast.makeText(this, Loc.s(this, "Calibration unavailable"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event == null) return super.onGenericMotionEvent(event);
        if (controller != null) controller.dispatchGenericMotionEvent(event);
        int source = event.getSource();
        boolean gamepad = (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) != 0;
        if (!gamepad) return super.onGenericMotionEvent(event);
        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
        if (Math.abs(rx) < 0.02f && Math.abs(ry) < 0.02f) {
            rx = event.getAxisValue(MotionEvent.AXIS_RX);
            ry = event.getAxisValue(MotionEvent.AXIS_RY);
        }
        rawLx = lx;
        rawLy = ly;
        rawRx = rx;
        rawRy = ry;
        if (!rangeCapturing && !gateCapturing) {
            float mag = (float) Math.hypot(lx, ly);
            if (mag < 0.12f && mag > restMax) restMax = mag;
        }
        // Coalesced motion events carry the points between two frames. Sampling
        // them matters for a fast roll, where reading only the latest axes
        // would leave most gate angles empty.
        int history = event.getHistorySize();
        for (int h = 0; h < history; h++) {
            float hlx = event.getHistoricalAxisValue(MotionEvent.AXIS_X, h);
            float hly = event.getHistoricalAxisValue(MotionEvent.AXIS_Y, h);
            float hrx = event.getHistoricalAxisValue(MotionEvent.AXIS_Z, h);
            float hry = event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, h);
            if (Math.abs(hrx) < 0.02f && Math.abs(hry) < 0.02f) {
                hrx = event.getHistoricalAxisValue(MotionEvent.AXIS_RX, h);
                hry = event.getHistoricalAxisValue(MotionEvent.AXIS_RY, h);
            }
            sampleInput(hlx, hly, hrx, hry);
        }
        sampleInput(lx, ly, rx, ry);
        float[] c = corrected(lx, ly, rx, ry);
        pads.setAxes(c[0], c[1], c[2], c[3]);
        pads.setRaw(axisNormalized(lx, 0), axisNormalized(ly, 1),
                axisNormalized(rx, 2), axisNormalized(ry, 3));
        values.setText(String.format(Locale.US,
                "L  %+.2f / %+.2f        R  %+.2f / %+.2f", c[0], c[1], c[2], c[3]));
        return true;
    }

    /** Feeds one stick sample into the capture modes that are active. */
    private void sampleInput(float lx, float ly, float rx, float ry) {
        if (gateCapturing) {
            sampleGate(gateLeft, axisNormalized(lx, 0), axisNormalized(ly, 1));
            sampleGate(gateRight, axisNormalized(rx, 2), axisNormalized(ry, 3));
        }
        if (guidePhase == GUIDE_ROLL) {
            sampleRoll(lx, ly, rx, ry);
        }
        if (rangeCapturing) {
            captureRangeSample(lx, ly, rx, ry);
        }
    }

    private void captureRangeSample(float lx, float ly, float rx, float ry) {
        int[] raw = {
                Math.round(lx * 32767f), Math.round(ly * 32767f),
                Math.round(rx * 32767f), Math.round(ry * 32767f)
        };
        for (int i = 0; i < 4; i++) {
            if (raw[i] < rangeMin[i]) rangeMin[i] = raw[i];
            if (raw[i] > rangeMax[i]) rangeMax[i] = raw[i];
        }
    }

    private void addSlider(LinearLayout parent, String label, int value,
                           int max, final int kind) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(2), 0, 0);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 9.5f, MUTED, true);
        name.setLetterSpacing(0.08f);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueText = kind == 0 ? deadzoneValue
                : kind == 1 ? curveValue : floorValue;
        if (valueText != null) {
            valueText.setTextColor(TEXT);
            row.addView(valueText);
        }
        box.addView(row);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, value)));
        bar.setPadding(dp(14), dp(3), dp(14), dp(3));
        bar.setSplitTrack(false);
        bar.setFocusable(true);
        bar.setFocusable(true);
        bar.setDefaultFocusHighlightEnabled(false);
        bar.setBackground(new ColorDrawable(0x00000000));
        bar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused
                        ? Ui.rounded(0x24FFFFFF, dp(12), 0xE6FFFFFF, dp(1.5f))
                        : new ColorDrawable(0x00000000));
            }
        });
        addNav(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                if (kind == 0) {
                    deadzone[selectedStick] = progress;
                    pads.setDeadzone(progress / 1000f);
                    if (deadzoneValue != null) {
                        deadzoneValue.setText(percent(progress));
                    }
                } else if (kind == 1) {
                    curve[selectedStick] = progress + 50;
                    if (curveValue != null) {
                        curveValue.setText(curve[selectedStick] + "%");
                    }
                } else {
                    floor[selectedStick] = progress;
                    if (floorValue != null) {
                        floorValue.setText(percent(progress));
                    }
                }
                if (fromUser) {
                    stickEnabled = true;
                    updateLayerChip();
                    updatePresetChips();
                    refreshPreview();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                // Tuning is a live control: commit on release, no Apply needed.
                saveCalibration(true, true);
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(box);
        if (kind == 0) {
            deadzoneBar = bar;
            pads.setDeadzone(value / 1000f);
        } else if (kind == 1) {
            curveBar = bar;
        } else {
            floorBar = bar;
        }
    }

    /** Selects which stick the Deadzone and CURVE sliders edit. */
    private void selectStick(int stick) {
        if (stick == selectedStick) return;
        selectedStick = stick;
        updateStickTabs();
        if (deadzoneBar != null) deadzoneBar.setProgress(deadzone[stick]);
        if (deadzoneValue != null) deadzoneValue.setText(percent(deadzone[stick]));
        pads.setDeadzone(deadzone[stick] / 1000f);
        if (curveBar != null) curveBar.setProgress(Math.max(0, curve[stick] - 50));
        if (curveValue != null) curveValue.setText(curve[stick] + "%");
        if (floorBar != null) floorBar.setProgress(floor[stick]);
        if (floorValue != null) floorValue.setText(percent(floor[stick]));
        refreshPreview();
    }

    private final Runnable healthTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (stickEnabled && !ControllerProfiles.bypassActive
                    && !ControllerRemapManager.isRunning(JoystickActivity.this)) {
                ControllerRemapManager.start(JoystickActivity.this, null);
            }
            updateStatus();
            updateBypassChip();
            handler.postDelayed(this, 2000L);
        }
    };

    private void updateStatus() {
        if (statusText == null) return;
        StringBuilder b = new StringBuilder();
        b.append(ControllerRemapManager.isRunning(this) ? "\u25CF " : "\u25CB ");
        b.append(Loc.s(this, stickEnabled ? "ON" : "OFF"));
        float jitter = restMax;
        if (jitter > 0.001f) {
            b.append(String.format(Locale.US, " \u00B7 rest \u00B1%.1f%%", jitter * 100f));
        }
        statusText.setText(b.toString());
    }

    private void openAppPicker() {
        if (appPicker != null || rootView == null) return;
        GlassPanel baseCard = PavoGlassActivity.largestGlassPanel(rootView);
        appPicker = new AppPickerStripView(this, prefs, targetPkg, baseCard,
                new AppPickerStripView.Listener() {
                    @Override public void onPick(String pkg, Apps.Entry entry) {
                        appPicker = null;
                        selectTarget(pkg);
                    }
                    @Override public void onClose() {
                        appPicker = null;
                    }
                });
        rootView.addView(appPicker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appPicker.bringToFront();
    }

    private void resetSelectedApp() {
        if (targetPkg.isEmpty()) return;
        profileStore.removeApp(targetPkg);
        ControllerProfiles.markDirty();
        selectTarget("");
    }

    private void selectTarget(String pkg) {
        targetPkg = pkg == null ? "" : pkg;
        updateAppSelector();
        ControllerMapConfig cfg = targetStickConfig();
        stickEnabled = cfg.stickEnabled;
        gateEnabled = cfg.gateEnabled;
        System.arraycopy(cfg.stickCenter, 0, centers, 0, centers.length);
        System.arraycopy(cfg.stickMin, 0, mins, 0, mins.length);
        System.arraycopy(cfg.stickMax, 0, maxs, 0, maxs.length);
        System.arraycopy(cfg.stickDeadzone, 0, deadzone, 0, 2);
        System.arraycopy(cfg.stickCurve, 0, curve, 0, 2);
        System.arraycopy(cfg.stickFloor, 0, floor, 0, 2);
        System.arraycopy(cfg.gateGain[0], 0, gateGain[0], 0, GATE_POINTS);
        System.arraycopy(cfg.gateGain[1], 0, gateGain[1], 0, GATE_POINTS);
        syncTuningUi();
    }

    private void syncTuningUi() {
        if (deadzoneBar != null) deadzoneBar.setProgress(deadzone[selectedStick]);
        if (deadzoneValue != null) deadzoneValue.setText(percent(deadzone[selectedStick]));
        pads.setDeadzone(deadzone[selectedStick] / 1000f);
        if (curveBar != null) curveBar.setProgress(Math.max(0, curve[selectedStick] - 50));
        if (curveValue != null) curveValue.setText(curve[selectedStick] + "%");
        if (floorBar != null) floorBar.setProgress(floor[selectedStick]);
        if (floorValue != null) floorValue.setText(percent(floor[selectedStick]));
        updateStickTabs();
        updateLayerChip();
        updatePresetChips();
        updateAutoChip();
        updateBypassChip();
        ensureExpertForValues();
        applyExpertLimits();
        updateExpertChip();
        refreshPreview();
    }

    private void updateAppSelector() {
        if (appSelectorLabel == null) return;
        String label = Loc.s(this, "ALL APPS");
        Bitmap bitmap = null;
        if (!targetPkg.isEmpty()) {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                label = String.valueOf(pm.getApplicationLabel(
                        pm.getApplicationInfo(targetPkg, 0)));
                bitmap = Apps.drawableToBitmap(pm.getApplicationIcon(targetPkg), dp(20));
            } catch (Throwable ignored) {
                label = targetPkg;
            }
        }
        if (bitmap == null) {
            try {
                bitmap = Apps.drawableToBitmap(getDrawable(R.mipmap.ic_launcher), dp(20));
            } catch (Throwable ignored) {
            }
        }
        if (appSelectorIcon != null) appSelectorIcon.setImageBitmap(bitmap);
        appSelectorLabel.setText(label);
        if (resetAppChip != null) {
            resetAppChip.setVisibility(targetPkg.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** Turns the software correction on or off without losing the tuning. */
    private void setStickLayer(boolean on, boolean persist) {
        if (stickEnabled == on) return;
        stickEnabled = on;
        updateLayerChip();
        refreshPreview();
        if (persist) saveCalibration(on, true);
    }

    private void updateLayerChip() {
        if (layerChip == null) return;
        layerChip.setText(Loc.s(this, stickEnabled ? "ON" : "OFF"));
        boolean focused = layerChip.hasFocus();
        if (stickEnabled) {
            layerChip.setTextColor(0xFF69D98B);
            layerChip.setBackground(focused
                    ? Ui.rounded(0x2669D98B, dp(10), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1E69D98B, dp(10), 0x4469D98B, dp(1)));
        } else {
            layerChip.setTextColor(MUTED);
            layerChip.setBackground(focused
                    ? Ui.rounded(0x1EFFFFFF, dp(10), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        }
    }

    private void ensureExpertForValues() {
        if (expertMode) return;
        for (int s = 0; s < 2; s++) {
            if (deadzone[s] > 300 || curve[s] > 200) {
                expertMode = true;
                return;
            }
        }
    }

    private void applyExpertLimits() {
        int dzMax = expertMode ? 500 : 300;
        int curveMax = expertMode ? 250 : 150;
        if (deadzoneBar != null) {
            deadzoneBar.setMax(dzMax);
            deadzoneBar.setProgress(Math.max(0, Math.min(dzMax, deadzone[selectedStick])));
        }
        if (curveBar != null) {
            curveBar.setMax(curveMax);
            curveBar.setProgress(Math.max(0, Math.min(curveMax, curve[selectedStick] - 50)));
        }
    }

    private void toggleExpert() {
        expertMode = !expertMode;
        prefs.setStickExpert(expertMode);
        if (!expertMode) {
            for (int s = 0; s < 2; s++) {
                deadzone[s] = Math.min(300, deadzone[s]);
                curve[s] = Math.max(50, Math.min(200, curve[s]));
            }
        }
        applyExpertLimits();
        updateExpertChip();
        syncTuningUi();
        saveCalibration(stickEnabled, true);
    }

    private void updateExpertChip() {
        if (expertChip == null) return;
        boolean focused = expertChip.hasFocus();
        if (expertMode) {
            expertChip.setTextColor(0xFF061015);
            expertChip.setBackground(focused
                    ? Ui.rounded(0xFFFFC857, dp(9), 0xFFFFFFFF, dp(1.5f))
                    : Ui.rounded(0xFFFFC857, dp(9)));
        } else {
            expertChip.setTextColor(MUTED);
            expertChip.setBackground(focused
                    ? Ui.rounded(0x1EFFFFFF, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFFFFF, dp(9), 0x33FFFFFF, dp(1)));
        }
    }

    private void applyPreset(int preset) {
        curve[0] = PRESET_CURVE[preset];
        curve[1] = PRESET_CURVE[preset];
        floor[0] = PRESET_FLOOR[preset];
        floor[1] = PRESET_FLOOR[preset];
        int dz = PRESET_DEADZONE[preset];
        if (dz >= 0) {
            deadzone[0] = dz;
            deadzone[1] = dz;
        }
        stickEnabled = true;
        syncTuningUi();
        saveCalibration(true, true);
    }

    private boolean presetActive(int preset) {
        boolean base = curve[0] == PRESET_CURVE[preset]
                && curve[1] == PRESET_CURVE[preset]
                && floor[0] == PRESET_FLOOR[preset]
                && floor[1] == PRESET_FLOOR[preset];
        int dz = PRESET_DEADZONE[preset];
        return base && (dz < 0 || (deadzone[0] == dz && deadzone[1] == dz));
    }

    private void updatePresetChips() {
        for (int i = 0; i < presetChips.length; i++) {
            TextView chip = presetChips[i];
            if (chip == null) continue;
            boolean active = presetActive(i);
            boolean focused = chip.hasFocus();
            if (active) {
                chip.setTextColor(0xFF061015);
                chip.setBackground(focused
                        ? Ui.rounded(ACCENT, dp(9), 0xFFFFFFFF, dp(1.5f))
                        : Ui.rounded(ACCENT, dp(9)));
            } else {
                chip.setTextColor(focused ? TEXT : 0xCCFFFFFF);
                chip.setBackground(focused
                        ? Ui.rounded(0x26FFFFFF, dp(9), 0xE6FFFFFF, dp(1.5f))
                        : Ui.rounded(0x1EFFFFFF, dp(9), 0x33FFFFFF, dp(1)));
            }
        }
    }

    /** Shows whether per-app stick profiles switch automatically. */
    private void updateAutoChip() {
        if (autoChip == null) return;
        boolean on = prefs.controllerStickAutoApp();
        boolean focused = autoChip.hasFocus();
        autoChip.setText(Loc.s(this, on ? "AUTO" : "MANUAL"));
        if (on) {
            autoChip.setTextColor(0xFF69D98B);
            autoChip.setBackground(focused
                    ? Ui.rounded(0x2669D98B, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1E69D98B, dp(9), 0x4469D98B, dp(1)));
        } else {
            autoChip.setTextColor(MUTED);
            autoChip.setBackground(focused
                    ? Ui.rounded(0x1EFFFFFF, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFFFFF, dp(9), 0x33FFFFFF, dp(1)));
        }
    }

    private void updateBypassChip() {
        if (bypassChip == null) return;
        boolean show = targetPkg != null && !targetPkg.isEmpty();
        bypassChip.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        boolean on = prefs.controllerBypass(targetPkg);
        boolean focused = bypassChip.hasFocus();
        bypassChip.setTextColor(on ? 0xFFFFC857 : MUTED);
        if (on) {
            bypassChip.setBackground(focused
                    ? Ui.rounded(0x26FFC857, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFC857, dp(9), 0x44FFC857, dp(1)));
        } else {
            bypassChip.setBackground(focused
                    ? Ui.rounded(0x1EFFFFFF, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFFFFF, dp(9), 0x33FFFFFF, dp(1)));
        }
    }

    private void toggleBypass() {
        if (targetPkg == null || targetPkg.isEmpty()) return;
        prefs.setControllerBypass(targetPkg, !prefs.controllerBypass(targetPkg));
        ControllerProfiles.markDirty();
        updateBypassChip();
    }

    private void updateStickTabs() {
        if (leftTab == null || rightTab == null) return;
        boolean left = selectedStick == 0;
        boolean leftFocus = leftTab.hasFocus();
        boolean rightFocus = rightTab.hasFocus();
        leftTab.setBackground(left
                ? Ui.rounded(ACCENT, dp(10), leftFocus ? 0xFFFFFFFF : 0x00000000, dp(1.5f))
                : Ui.rounded(0x22FFFFFF, dp(10), leftFocus ? 0xE6FFFFFF : 0x33FFFFFF, dp(1.5f)));
        leftTab.setTextColor(left ? 0xFF061015 : TEXT);
        rightTab.setBackground(!left
                ? Ui.rounded(ACCENT, dp(10), rightFocus ? 0xFFFFFFFF : 0x00000000, dp(1.5f))
                : Ui.rounded(0x22FFFFFF, dp(10), rightFocus ? 0xE6FFFFFF : 0x33FFFFFF, dp(1.5f)));
        rightTab.setTextColor(!left ? 0xFF061015 : TEXT);
    }

    /**
     * Focusable-in-touch-mode views can swallow the first tap as a focus
     * change, so handle the press ourselves for a deterministic click.
     */
    private void tapClicks(final View v) {
        final long[] downAt = {0L};
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downAt[0] = SystemClock.uptimeMillis();
                    view.setPressed(true);
                    view.requestFocus();
                } else if (action == MotionEvent.ACTION_UP) {
                    boolean held = SystemClock.uptimeMillis() - downAt[0]
                            >= android.view.ViewConfiguration.getLongPressTimeout();
                    view.setPressed(false);
                    if (held && view.isLongClickable()) view.performLongClick();
                    else view.performClick();
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    view.setPressed(false);
                }
                return true;
            }
        });
    }

    private void addNav(View v) {
        if (v == null || nav.contains(v)) return;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        nav.add(v);
    }

    private void moveNav(int direction) {
        if (nav.isEmpty()) return;
        View current = getCurrentFocus();
        int idx = nav.indexOf(current);
        if (idx < 0) idx = direction > 0 ? -1 : 0;
        for (int i = 0; i < nav.size(); i++) {
            idx = (idx + direction + nav.size()) % nav.size();
            View candidate = nav.get(idx);
            if (candidate.getVisibility() == View.VISIBLE && candidate.isEnabled()
                    && candidate.requestFocus()) {
                return;
            }
        }
    }

    private boolean adjustFocusedBar(int direction) {
        View focused = getCurrentFocus();
        if (!(focused instanceof SeekBar)) return false;
        SeekBar bar = (SeekBar) focused;
        int next = Math.max(0, Math.min(bar.getMax(), bar.getProgress() + direction * 5));
        if (next == bar.getProgress()) return true;
        bar.setProgress(next);
        stickEnabled = true;
        refreshPreview();
        saveCalibration(true, true);
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            finishAnimated();
            return true;
        }
        if (controller != null && controller.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private TextView stickTab(String label) {
        TextView v = text(label, 9.5f, TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(7), dp(8), dp(7));
        v.setFocusable(true);
        v.setFocusable(true);
        v.setDefaultFocusHighlightEnabled(false);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                updateStickTabs();
            }
        });
        tapClicks(v);
        addNav(v);
        return v;
    }

    private static String percent(int permille) {
        return String.format(Locale.US, "%.1f%%", permille / 10f);
    }

    private TextView smallButton(final String label, final boolean primary) {
        TextView v = text(label, 9.5f, primary ? 0xFF061015 : TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(7), dp(8), dp(7));
        v.setBackground(buttonBackground(primary, false));
        v.setFocusable(true);
        v.setFocusable(true);
        v.setDefaultFocusHighlightEnabled(false);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                view.setBackground(buttonBackground(primary, focused));
            }
        });
        tapClicks(v);
        return v;
    }

    private android.graphics.drawable.GradientDrawable buttonBackground(boolean primary,
                                                                       boolean focused) {
        if (primary) {
            return focused ? Ui.rounded(ACCENT, dp(11), 0xFFFFFFFF, dp(1.5f))
                    : Ui.rounded(ACCENT, dp(11));
        }
        return focused ? Ui.rounded(0x26FFFFFF, dp(11), 0xE6FFFFFF, dp(1.5f))
                : Ui.rounded(0x22FFFFFF, dp(11), 0x33FFFFFF, dp(1));
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
        }
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(Loc.s(this, value));
        v.setTextSize(size);
        v.setTextColor(color);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Two stick pads. The filled dot is the corrected output, the hollow amber
     * dot is the raw stick position. When a gate is active its measured shape
     * is drawn around the rim, with a per-angle heat ring showing where the
     * stick falls short (red) or overshoots (blue).
     */
    private static final class StickPadView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rawDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gateLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint heat = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] left = new float[2];
        private final float[] right = new float[2];
        private final float[] leftRaw = new float[2];
        private final float[] rightRaw = new float[2];
        private final int[][] gate = new int[2][GATE_POINTS];
        private boolean gateOn;
        private float deadzone;
        private final Path path = new Path();
        private final RectF arc = new RectF();
        private final float density;

        StickPadView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            float scaled = context.getResources().getDisplayMetrics().scaledDensity;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(density * 2f);
            ring.setColor(0x66A9BEFF);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeWidth(density * 1.2f);
            cross.setColor(0x33FFFFFF);
            dot.setColor(0xFFA9BEFF);
            rawDot.setStyle(Paint.Style.STROKE);
            rawDot.setStrokeWidth(density * 1.4f);
            rawDot.setColor(0xCCFFC857);
            gateLine.setStyle(Paint.Style.STROKE);
            gateLine.setStrokeWidth(density * 1.2f);
            gateLine.setColor(0x88FFC857);
            heat.setStyle(Paint.Style.STROKE);
            heat.setStrokeWidth(density * 3.2f);
            heat.setStrokeCap(Paint.Cap.ROUND);
            label.setColor(0x99FFFFFF);
            label.setTextSize(scaled * 8.5f);
            label.setTextAlign(Paint.Align.CENTER);
            legend.setColor(0x99FFFFFF);
            legend.setTextSize(scaled * 7.5f);
            legend.setTextAlign(Paint.Align.LEFT);
            setFocusable(false);
            setWillNotDraw(false);
        }

        void setDeadzone(float value) {
            deadzone = value;
            invalidate();
        }

        void setAxes(float lx, float ly, float rx, float ry) {
            left[0] = clamp(lx);
            left[1] = clamp(ly);
            right[0] = clamp(rx);
            right[1] = clamp(ry);
            invalidate();
        }

        void setRaw(float lx, float ly, float rx, float ry) {
            leftRaw[0] = clamp(lx);
            leftRaw[1] = clamp(ly);
            rightRaw[0] = clamp(rx);
            rightRaw[1] = clamp(ry);
            invalidate();
        }

        void setGate(int[] leftGain, int[] rightGain, boolean on) {
            if (leftGain != null) {
                System.arraycopy(leftGain, 0, gate[0], 0, GATE_POINTS);
            }
            if (rightGain != null) {
                System.arraycopy(rightGain, 0, gate[1], 0, GATE_POINTS);
            }
            gateOn = on;
            invalidate();
        }

        private static float clamp(float v) {
            return Math.max(-1f, Math.min(1f, v));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w * 0.19f, h * 0.33f);
            float y = h * 0.42f;
            drawPad(canvas, w * 0.27f, y, r, left, leftRaw, 0,
                    Loc.s(getContext(), "LEFT STICK"));
            drawPad(canvas, w * 0.73f, y, r, right, rightRaw, 1,
                    Loc.s(getContext(), "RIGHT STICK"));
            String rawText = Loc.s(getContext(), "RAW");
            String outText = Loc.s(getContext(), "OUT");
            float baseline = density * 11f;
            float dotCy = baseline - density * 2.5f;
            float gap = density * 4f;
            float groupGap = density * 10f;
            float dotR = density * 2.4f;
            float rawW = legend.measureText(rawText);
            float outW = legend.measureText(outText);
            float total = rawW + gap + dotR * 2f + groupGap
                    + outW + gap + dotR * 2f;
            float x = w * 0.5f - total * 0.5f;
            legend.setTextAlign(Paint.Align.LEFT);
            legend.setColor(rawDot.getColor());
            canvas.drawText(rawText, x, baseline, legend);
            x += rawW + gap;
            canvas.drawCircle(x + dotR, dotCy, dotR, rawDot);
            x += dotR * 2f + groupGap;
            legend.setColor(dot.getColor());
            canvas.drawText(outText, x, baseline, legend);
            x += outW + gap;
            canvas.drawCircle(x + dotR, dotCy, dotR, dot);
        }

        private void drawPad(Canvas canvas, float cx, float cy, float r,
                             float[] axes, float[] raw, int stick, String name) {
            ring.setColor(0x66A9BEFF);
            canvas.drawCircle(cx, cy, r, ring);
            ring.setColor(0x33A9BEFF);
            float dz = Math.max(0f, Math.min(0.8f, deadzone));
            canvas.drawCircle(cx, cy, Math.max(r * 0.04f, r * 0.88f * dz), ring);
            canvas.drawLine(cx - r, cy, cx + r, cy, cross);
            canvas.drawLine(cx, cy - r, cx, cy + r, cross);

            drawGate(canvas, cx, cy, r, stick);

            float rawPx = cx + raw[0] * r * 0.88f;
            float rawPy = cy + raw[1] * r * 0.88f;
            canvas.drawCircle(rawPx, rawPy, r * 0.07f, rawDot);

            float px = cx + axes[0] * r * 0.88f;
            float py = cy + axes[1] * r * 0.88f;
            float magnitude = (float) Math.hypot(axes[0], axes[1]);
            dot.setColor(magnitude > 0.10f ? 0xFFA9BEFF : 0xFF69D98B);
            canvas.drawCircle(px, py, r * 0.09f, dot);
            canvas.drawText(name, cx, cy + r + r * 0.30f, label);
        }

        private void drawGate(Canvas canvas, float cx, float cy, float r, int stick) {
            float outer = r * 0.88f;
            if (!gateOn) return;
            path.reset();
            for (int i = 0; i < GATE_POINTS; i++) {
                float gain = Math.max(0.7f, Math.min(1.5f, gate[stick][i] / 1000f));
                float radius = Math.min(outer * 1.02f, outer / gain);
                float a = (float) (-Math.PI + i * 2.0 * Math.PI / GATE_POINTS);
                float px = cx + (float) Math.cos(a) * radius;
                float py = cy + (float) Math.sin(a) * radius;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            path.close();
            canvas.drawPath(path, gateLine);

            arc.set(cx - outer * 0.985f, cy - outer * 0.985f,
                    cx + outer * 0.985f, cy + outer * 0.985f);
            for (int i = 0; i < GATE_POINTS; i++) {
                float gain = gate[stick][i] / 1000f;
                if (Math.abs(gain - 1f) < 0.035f) continue;
                heat.setColor(heatColor(gain));
                float start = 180f + i * 360f / GATE_POINTS;
                canvas.drawArc(arc, start, 360f / GATE_POINTS + 0.6f, false, heat);
            }
        }

        /** Draws a measured radius table as a closed polygon. */
        private void drawPolygon(Canvas canvas, float cx, float cy, float outer,
                                 float[] radii, Paint paint, boolean joined) {
            path.reset();
            boolean started = false;
            int last = -2;
            for (int i = 0; i < GATE_POINTS; i++) {
                if (radii[i] <= 0.05f) continue;
                float radius = Math.max(0.3f, Math.min(1.6f, radii[i])) * outer;
                float a = (float) (-Math.PI + i * 2.0 * Math.PI / GATE_POINTS);
                float px = cx + (float) Math.cos(a) * radius;
                float py = cy + (float) Math.sin(a) * radius;
                if (!started || i != last + 1) {
                    if (started) {
                        if (joined) path.close();
                        canvas.drawPath(path, paint);
                        path.reset();
                    }
                    path.moveTo(px, py);
                    started = true;
                } else {
                    path.lineTo(px, py);
                }
                last = i;
            }
            if (started) {
                if (joined) path.close();
                canvas.drawPath(path, paint);
            }
        }

        private static int heatColor(float gain) {
            float d = gain - 1f;
            if (d >= 0f) {
                return mix(0x99FFD166, 0xCCFF5C6C, Math.min(1f, d / 0.30f));
            }
            return mix(0x998BE9FF, 0xCC6C8CFF, Math.min(1f, -d / 0.30f));
        }

        private static int mix(int from, int to, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int a = Math.round(Color.alpha(from) * (1f - t) + Color.alpha(to) * t);
            int r = Math.round(Color.red(from) * (1f - t) + Color.red(to) * t);
            int g = Math.round(Color.green(from) * (1f - t) + Color.green(to) * t);
            int b = Math.round(Color.blue(from) * (1f - t) + Color.blue(to) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
}
