package com.ben.pavo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
    private int deadzone = 60;
    private int curve = 100;
    private final int[] centers = new int[6];
    private final int[] mins = new int[6];
    private final int[] maxs = new int[6];
    private final int[] rangeMin = new int[6];
    private final int[] rangeMax = new int[6];
    private static final int GATE_POINTS = ControllerMapConfig.GATE_POINTS;
    private final int[][] gateGain = new int[2][GATE_POINTS];
    private final float[] gateLeft = new float[GATE_POINTS];
    private final float[] gateRight = new float[GATE_POINTS];
    private boolean gateEnabled;
    private boolean gateCapturing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean rangeCapturing;
    private SeekBar deadzoneBar;
    private SeekBar curveBar;
    private TextView deadzoneValue;
    private TextView curveValue;
    private float rawLx, rawLy, rawRx, rawRy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        pads = new StickPadView(this);
        loadCalibration();
        buildUi();
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        calibrating = false;
        hideBars();
        remapWasEnabled = prefs.controllerRemapEnabled();
        ControllerRemapManager.setPassthrough(this, true);
        PavoA11yService.setRemapUiActive(true);
        PavoInputService.setRemapUiActive(true);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(rangeDone);
        handler.removeCallbacks(gateDone);
        rangeCapturing = false;
        gateCapturing = false;
        ControllerRemapManager.setPassthrough(this, false);
        if (!calibrating) restoreRemapper();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ControllerRemapManager.setPassthrough(this, false);
        if (!calibrating) restoreRemapper();
        super.onDestroy();
    }

    private void restoreRemapper() {
        PavoA11yService.setRemapUiActive(false);
        PavoInputService.setRemapUiActive(false);
        if (remapWasEnabled || calibrationApplied) {
            ControllerRemapManager.ensureStarted(this);
        }
    }

    private ControllerMapConfig loadCalibration() {
        ControllerMapConfig cfg = new ControllerMapConfig();
        cfg.load(ControllerRemapManager.configFile(this));
        stickEnabled = cfg.stickEnabled;
        deadzone = cfg.stickDeadzone;
        curve = cfg.stickCurve;
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
        pads.setAxes(0f, 0f, 0f, 0f);
        values.setText(String.format(Locale.US,
                "L  %+.2f / %+.2f        R  %+.2f / %+.2f",
                rawLx, rawLy, rawRx, rawRy));
    }

    private void saveCalibration(boolean enabled) {
        try {
            ControllerMapConfig cfg = new ControllerMapConfig();
            cfg.load(ControllerRemapManager.configFile(this));
            cfg.stickEnabled = enabled;
            System.arraycopy(centers, 0, cfg.stickCenter, 0, centers.length);
            System.arraycopy(mins, 0, cfg.stickMin, 0, mins.length);
            System.arraycopy(maxs, 0, cfg.stickMax, 0, maxs.length);
            cfg.stickDeadzone = deadzone;
            cfg.stickCurve = curve;
            cfg.gateEnabled = gateEnabled;
            System.arraycopy(gateGain[0], 0, cfg.gateGain[0], 0, GATE_POINTS);
            System.arraycopy(gateGain[1], 0, cfg.gateGain[1], 0, GATE_POINTS);
            cfg.save(ControllerRemapManager.configFile(this),
                    prefs.controllerRemapEnabled(), false);
            calibrationApplied = enabled;
            LToast.makeText(this, Loc.s(this, "Saved"), Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private void resetCalibration() {
        for (int i = 0; i < centers.length; i++) {
            centers[i] = 0;
            mins[i] = -32767;
            maxs[i] = 32767;
        }
        deadzone = 60;
        curve = 100;
        if (deadzoneBar != null) deadzoneBar.setProgress(deadzone);
        pads.setDeadzone(deadzone / 1000f);
        gateEnabled = false;
        for (int s2 = 0; s2 < 2; s2++) {
            for (int i = 0; i < GATE_POINTS; i++) gateGain[s2][i] = 1000;
        }
        if (curveBar != null) curveBar.setProgress(curve - 50);
        stickEnabled = false;
        saveCalibration(false);
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
            float[] c = corrected(rawLx, rawLy, rawRx, rawRy);
            pads.setAxes(c[0], c[1], c[2], c[3]);
            LToast.makeText(JoystickActivity.this,
                    Loc.s(JoystickActivity.this, "Gate captured"),
                    Toast.LENGTH_SHORT).show();
        }
    };

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
        float dz = Math.max(0f, Math.min(0.8f, deadzone / 1000f));
        float m = r <= dz ? 0f : (r - dz) / (1f - dz);
        float gamma = Math.max(0.2f, Math.min(4f, curve / 100f));
        if (m > 0f) m = (float) Math.pow(m, gamma);
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
        root.setBackgroundColor(0xF2080B10);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(16), dp(22), dp(14));
        card.setBackground(Ui.rounded(0xFF0E131A, dp(22), 0x33FFFFFF, dp(1)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("JOYSTICKS", 20f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        titles.addView(title);
        TextView sub = text("CALIBRATION", 9f, MUTED, true);
        sub.setLetterSpacing(0.12f);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u2715", 16f, TEXT, false);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text("Centre both sticks, roll each one around its full edge, "
                + "then press Finish in the Odin calibration screen.",
                9f, MUTED, false);
        hint.setPadding(0, dp(8), 0, dp(6));
        card.addView(hint);

        card.addView(pads, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        values = text("", 9f, 0xB3FFFFFF, true);
        values.setGravity(Gravity.CENTER);
        values.setPadding(0, dp(2), 0, dp(4));
        card.addView(values);

        deadzoneValue = text("6.0%", 9f, 0xB3FFFFFF, true);
        addSlider(card, "Deadzone", deadzone, 300, true);
        curveValue = text("100%", 9f, 0xB3FFFFFF, true);
        addSlider(card, "CURVE", Math.max(0, curve - 50), 150, false);

        LinearLayout tuneRow = new LinearLayout(this);
        tuneRow.setOrientation(LinearLayout.HORIZONTAL);
        tuneRow.setGravity(Gravity.CENTER);

        TextView capture = smallButton("Center", false);
        capture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { captureCenter(); }
        });
        tuneRow.addView(capture, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView range = smallButton("Range", false);
        range.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRangeCapture(); }
        });
        LinearLayout.LayoutParams rglp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rglp.leftMargin = dp(6);
        tuneRow.addView(range, rglp);

        TextView gate = smallButton("Gate", false);
        gate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startGateCapture(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        glp.leftMargin = dp(6);
        tuneRow.addView(gate, glp);

        TextView apply = smallButton("Apply", true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveCalibration(true); }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        al.leftMargin = dp(6);
        tuneRow.addView(apply, al);

        TextView reset = smallButton("RESET", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetCalibration(); }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rl.leftMargin = dp(6);
        tuneRow.addView(reset, rl);
        card.addView(tuneRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView testHint = text(
                "Release the sticks; both dots should sit inside the centre rings.",
                8.8f, 0x99FFFFFF, false);
        testHint.setGravity(Gravity.CENTER);
        testHint.setPadding(0, 0, 0, dp(8));
        card.addView(testHint);

        TextView calibrate = text("CALIBRATION", 10f, 0xFF061015, true);
        calibrate.setGravity(Gravity.CENTER);
        calibrate.setPadding(dp(12), dp(8), dp(12), dp(8));
        calibrate.setBackground(Ui.rounded(ACCENT, dp(12)));
        calibrate.setFocusable(true);
        calibrate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openOdinCalibration();
            }
        });
        card.addView(calibrate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        root.addView(card, cardLp);
        setContentView(root);
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
        if (gateCapturing) {
            sampleGate(gateLeft, axisNormalized(lx, 0), axisNormalized(ly, 1));
            sampleGate(gateRight, axisNormalized(rx, 2), axisNormalized(ry, 3));
        }
        if (rangeCapturing) {
            int[] raw = {
                    Math.round(lx * 32767f), Math.round(ly * 32767f),
                    Math.round(rx * 32767f), Math.round(ry * 32767f)
            };
            for (int i = 0; i < 4; i++) {
                if (raw[i] < rangeMin[i]) rangeMin[i] = raw[i];
                if (raw[i] > rangeMax[i]) rangeMax[i] = raw[i];
            }
        }
        float[] c = corrected(lx, ly, rx, ry);
        pads.setAxes(c[0], c[1], c[2], c[3]);
        values.setText(String.format(Locale.US,
                "L  %+.2f / %+.2f        R  %+.2f / %+.2f", c[0], c[1], c[2], c[3]));
        return true;
    }

    private void addSlider(LinearLayout parent, String label, int value,
                           int max, boolean isDeadzone) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(2), 0, 0);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 9f, 0xB3FFFFFF, true);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueText = isDeadzone ? deadzoneValue : curveValue;
        if (valueText != null) row.addView(valueText);
        box.addView(row);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, value)));
        bar.setPadding(dp(2), 0, dp(2), 0);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                if (isDeadzone) {
                    deadzone = progress;
                    pads.setDeadzone(progress / 1000f);
                    if (deadzoneValue != null) {
                        deadzoneValue.setText(String.format(Locale.US,
                                "%.1f%%", progress / 10f));
                    }
                } else {
                    curve = progress + 50;
                    if (curveValue != null) {
                        curveValue.setText(curve + "%");
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(box);
        if (isDeadzone) {
            deadzoneBar = bar;
            pads.setDeadzone(value / 1000f);
        } else {
            curveBar = bar;
        }
    }

    private TextView smallButton(String label, boolean primary) {
        TextView v = text(label, 9f, primary ? 0xFF061015 : TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(6), dp(8), dp(6));
        v.setBackground(primary ? Ui.rounded(ACCENT, dp(10))
                : Ui.rounded(0x22FFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        v.setFocusable(true);
        return v;
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

    /** Two stick pads with a live dot, centre cross and deadzone ring. */
    private static final class StickPadView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] left = new float[2];
        private final float[] right = new float[2];
        private float deadzone;

        StickPadView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2f);
            ring.setColor(0x66A9BEFF);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.2f);
            cross.setColor(0x33FFFFFF);
            dot.setColor(0xFFA9BEFF);
            label.setColor(0x88FFFFFF);
            label.setTextSize(context.getResources().getDisplayMetrics().scaledDensity * 8f);
            label.setTextAlign(Paint.Align.CENTER);
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

        private static float clamp(float v) {
            return Math.max(-1f, Math.min(1f, v));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w * 0.19f, h * 0.32f);
            float y = h * 0.45f;
            drawPad(canvas, w * 0.27f, y, r, left, Loc.s(getContext(), "LEFT STICK"));
            drawPad(canvas, w * 0.73f, y, r, right, Loc.s(getContext(), "RIGHT STICK"));
        }

        private void drawPad(Canvas canvas, float cx, float cy, float r,
                             float[] axes, String name) {
            ring.setColor(0x66A9BEFF);
            canvas.drawCircle(cx, cy, r, ring);
            ring.setColor(0x33A9BEFF);
            float dz = Math.max(0f, Math.min(0.8f, deadzone));
            canvas.drawCircle(cx, cy, Math.max(r * 0.04f, r * 0.88f * dz), ring);
            canvas.drawLine(cx - r, cy, cx + r, cy, cross);
            canvas.drawLine(cx, cy - r, cx, cy + r, cross);

            float px = cx + axes[0] * r * 0.88f;
            float py = cy + axes[1] * r * 0.88f;
            float magnitude = (float) Math.hypot(axes[0], axes[1]);
            dot.setColor(magnitude > 0.10f ? 0xFFA9BEFF : 0xFF69D98B);
            canvas.drawCircle(px, py, r * 0.09f, dot);
            canvas.drawText(name, cx, cy + r + r * 0.22f, label);
        }
    }
}
