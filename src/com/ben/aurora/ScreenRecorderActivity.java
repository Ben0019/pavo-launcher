package com.ben.aurora;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Built-in screen recorder with full-screen/single-app capture and audio options. */
public final class ScreenRecorderActivity extends AuroraGlassActivity {
    static final String EXTRA_AUTO_FULLSCREEN = "screen_recorder_auto_fullscreen";

    private static final int REQ_PROJECTION = 8101;
    private static final int REQ_PERMISSIONS = 8102;
    private static final String PREFS = "screen_recorder";

    private static final int TEXT = 0xF5FFFFFF;
    private static final int MUTED = 0xB8FFFFFF;
    private static final int BITRATE_MIN = 2000;
    private static final int BITRATE_MAX = 100000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private GlassPanel sheet;
    private SharedPreferences settings;

    private int mode = ScreenRecorderConfig.MODE_FULLSCREEN;
    private int quality = ScreenRecorderConfig.QUALITY_BALANCED;
    private int resolution = ScreenRecorderConfig.RES_1080;
    private int format = ScreenRecorderConfig.FORMAT_MP4_AVC;
    private int fps = 30;
    private int bitrateKbps = 10000;
    private boolean deviceAudio = true;
    private boolean micAudio;
    private boolean showTouches = true;

    private final List<TextView> modeChips = new ArrayList<>();
    private final List<TextView> qualityChips = new ArrayList<>();
    private final List<TextView> resolutionChips = new ArrayList<>();
    private final List<TextView> fpsChips = new ArrayList<>();
    private final List<TextView> formatChips = new ArrayList<>();
    private TextView bitrateValue;
    private TextView formatNote;
    private TextView videoEstimate;
    private TextView audioStatus;
    private TextView summaryLine;
    private TextView deviceChip;
    private TextView micChip;
    private TextView touchChip;
    private TextView status;
    private TextView pathLabel;
    private TextView recordButton;
    private SeekBar bitrateBar;

    private boolean pendingStart;
    private boolean projectionRequestActive;
    private boolean autoStart;
    private boolean updating;
    private ControllerNav controllerNav;
    private final List<View> controllerOrder = new ArrayList<>();
    private TextView closeButton;
    private long lastControllerMove;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        ensureWindowBackdrop();
        hidePerformanceOverlay();
        settings = getSharedPreferences(PREFS, MODE_PRIVATE);
        readSettings();
        autoStart = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_FULLSCREEN, false);
        if (autoStart) mode = ScreenRecorderConfig.MODE_FULLSCREEN;
        buildUi();
        hideBars();
        if (!ScreenRecorderService.isRecording() && !ScreenRecorderService.isSaving()) {
            ScreenRecorderService.forceTouchDotsOff(this);
        }
        updateAll();
        if (autoStart) {
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && !isDestroyed()
                            && !ScreenRecorderService.isRecording()
                            && !ScreenRecorderService.isSaving()) {
                        requestStart();
                    }
                }
            }, 300L);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTO_FULLSCREEN, false)
                && !ScreenRecorderService.isRecording()
                && !ScreenRecorderService.isSaving()) {
            mode = ScreenRecorderConfig.MODE_FULLSCREEN;
            updateAll();
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && !isDestroyed()) requestStart();
                }
            }, 250L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hidePerformanceOverlay();
        if (ScreenRecorderService.isRecording() || ScreenRecorderService.isSaving()) {
            main.removeCallbacks(ticker);
            main.post(ticker);
        }
        updateAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
        if (controllerNav != null) controllerNav.cancel();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        if (controllerNav != null) controllerNav.release();
        super.onDestroy();
    }

    @Override
    protected boolean readableTextPass() {
        return false;
    }

    @Override
    public void onBackPressed() {
        finishAnimated();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ControllerNav.isSyntheticStickDpad(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            onBackPressed();
            return true;
        }
        if ((event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R1)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            View target = event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1
                    ? (modeChips.isEmpty() ? null : modeChips.get(0))
                    : deviceChip;
            if (target != null) {
                target.requestFocus();
                Haptics.tick(target);
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            ControllerNav.noteExternalDpad(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                lastControllerMove = android.os.SystemClock.uptimeMillis();
                moveControllerFocus(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            }
            return true;
        }
        if (controllerNav != null && controllerNav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (controllerNav != null && controllerNav.dispatchGenericMotionEvent(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void rebuildControllerOrder() {
        controllerOrder.clear();
        for (TextView chip : modeChips) controllerOrder.add(chip);
        for (TextView chip : qualityChips) controllerOrder.add(chip);
        for (TextView chip : resolutionChips) controllerOrder.add(chip);
        for (TextView chip : fpsChips) controllerOrder.add(chip);
        for (TextView chip : formatChips) controllerOrder.add(chip);
        if (bitrateBar != null) controllerOrder.add(bitrateBar);
        if (deviceChip != null) controllerOrder.add(deviceChip);
        if (micChip != null) controllerOrder.add(micChip);
        if (touchChip != null) controllerOrder.add(touchChip);
        if (recordButton != null) controllerOrder.add(recordButton);
        if (closeButton != null) controllerOrder.add(closeButton);
    }

    private void moveControllerFocus(int delta) {
        if (controllerOrder.isEmpty()) return;
        View focus = getCurrentFocus();
        int index = controllerOrder.indexOf(focus);
        if (index < 0) index = delta > 0 ? -1 : 0;
        index = (index + delta + controllerOrder.size()) % controllerOrder.size();
        View target = controllerOrder.get(index);
        if (target != null) {
            target.requestFocus();
            final View current = target;
            main.post(new Runnable() {
                @Override public void run() {
                    current.requestRectangleOnScreen(new android.graphics.Rect(
                            0, 0, current.getWidth(), current.getHeight()), false);
                }
            });
            Haptics.tick(target);
        }
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

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private void readSettings() {
        mode = settings.getInt("mode", mode);
        quality = settings.getInt("quality", quality);
        resolution = settings.getInt("resolution", resolution);
        format = settings.getInt("format", format);
        if (format != ScreenRecorderConfig.FORMAT_MP4_AVC
                && format != ScreenRecorderConfig.FORMAT_MP4_HEVC) {
            format = ScreenRecorderConfig.FORMAT_MP4_AVC;
        }
        fps = settings.getInt("fps", fps);
        bitrateKbps = settings.getInt("bitrate", bitrateKbps);
        deviceAudio = settings.getBoolean("device_audio", deviceAudio);
        micAudio = settings.getBoolean("mic_audio", micAudio);
        showTouches = settings.getBoolean("show_touches", showTouches);
        if (Build.VERSION.SDK_INT < 34) mode = ScreenRecorderConfig.MODE_FULLSCREEN;
    }

    private void saveSettings() {
        settings.edit()
                .putInt("mode", mode)
                .putInt("quality", quality)
                .putInt("resolution", resolution)
                .putInt("format", format)
                .putInt("fps", fps)
                .putInt("bitrate", bitrateKbps)
                .putBoolean("device_audio", deviceAudio)
                .putBoolean("mic_audio", micAudio)
                .putBoolean("show_touches", showTouches)
                .apply();
    }

    private void applyQuality(int index) {
        quality = index;
        if (index == ScreenRecorderConfig.QUALITY_EFFICIENT) {
            resolution = ScreenRecorderConfig.RES_720;
            fps = 30;
            bitrateKbps = 4000;
            format = ScreenRecorderConfig.FORMAT_MP4_AVC;
        } else if (index == ScreenRecorderConfig.QUALITY_HIGH) {
            resolution = ScreenRecorderConfig.RES_1080;
            fps = 60;
            bitrateKbps = 24000;
            format = ScreenRecorderConfig.FORMAT_MP4_HEVC;
        } else {
            resolution = ScreenRecorderConfig.RES_1080;
            fps = 30;
            bitrateKbps = 10000;
            format = ScreenRecorderConfig.FORMAT_MP4_AVC;
        }
        saveSettings();
        updateAll();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private static void disableDefaultFocusHighlight(View view) {
        if (view == null) return;
        view.setDefaultFocusHighlightEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableDefaultFocusHighlight(group.getChildAt(i));
            }
        }
    }

    private void ensureWindowBackdrop() {
        Backdrop existing = BuiltInBackdrop.take();
        if (existing != null) {
            BuiltInBackdrop.put(existing);
            return;
        }
        Backdrop captured = ScreenCaptureBackdrop.captureSharp(this);
        if (captured != null) BuiltInBackdrop.put(captured);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        sheet = new GlassPanel(this, null, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        sheet.setBackdropScreenAnchored(true);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(9));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sheetLp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, sheetLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(buildHeader(), matchWrap());

        android.content.res.Configuration cfg = getResources().getConfiguration();
        boolean narrow = cfg.orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || cfg.screenWidthDp < 620
                || cfg.screenHeightDp < 430
                || cfg.screenWidthDp / (float) Math.max(1, cfg.screenHeightDp) < 1.30f;

        if (narrow) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            content.addView(buildModeSection(), sectionLp(0));
            content.addView(buildQualitySection(), sectionLp(6));
            content.addView(buildVideoSection(), sectionLp(6));
            content.addView(buildAudioSection(), sectionLp(6));
        } else {
            // Keep the larger, higher-contrast typography usable on shorter
            // landscape screens by allowing the card grid to scroll as one page.
            ScrollView mainScroll = new ScrollView(this);
            mainScroll.setFillViewport(true);
            mainScroll.setVerticalScrollBarEnabled(false);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            mainScroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            column.addView(mainScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            View mode = buildModeSection();
            View quality = buildQualitySection();
            top.addView(mode, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams qualityLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            qualityLp.leftMargin = dp(6);
            top.addView(quality, qualityLp);
            content.addView(top, matchWrap());
            equalizeCards(top, mode, quality);

            LinearLayout middle = new LinearLayout(this);
            middle.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams middleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            middleLp.topMargin = dp(6);
            content.addView(middle, middleLp);
            View video = buildVideoSection();
            View audio = buildAudioSection();
            middle.addView(video, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams audioLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            audioLp.leftMargin = dp(6);
            middle.addView(audio, audioLp);
            equalizeCards(middle, video, audio);
        }

        column.addView(buildFooter(), matchWrap());
        setContentView(root);
        updateAll();
        rebuildControllerOrder();
        controllerNav = new ControllerNav(this, root);
        controllerNav.decorateFocusables();
        disableDefaultFocusHighlight(root);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed() || controllerNav == null) return;
                controllerNav.decorateFocusables();
                disableDefaultFocusHighlight(root);
            }
        }, 350L);
        main.post(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                View first = recordButton != null ? recordButton
                        : (modeChips.isEmpty() ? null : modeChips.get(0));
                if (first != null) first.requestFocus();
            }
        });
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(3));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("SCREEN RECORDER", 18f, 0xFFFFFFFF, false);
        title.setLetterSpacing(0.13f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = text("CAPTURE  \u00b7  ENCODE  \u00b7  SAVE", 9.5f, MUTED, false);
        sub.setLetterSpacing(0.09f);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView recordingsButton = text("RECORDINGS", 8.5f, 0xE6FFFFFF, false);
        recordingsButton.setGravity(Gravity.CENTER);
        recordingsButton.setPadding(dp(10), 0, dp(10), 0);
        recordingsButton.setMinHeight(dp(30));
        recordingsButton.setBackground(Ui.rounded(0x33000000, dp(9), 0x44FFFFFF, dp(1)));
        recordingsButton.setFocusable(true);
        recordingsButton.setClickable(true);
        recordingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRecordingsPage(); }
        });
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        recLp.rightMargin = dp(6);
        header.addView(recordingsButton, recLp);

        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        closeButton = close;
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(7), 0, dp(7), 0);
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishAnimated(); }
        });
        header.addView(close);
        return header;
    }

    private View buildModeSection() {
        LinearLayout section = section("CAPTURE MODE", "Choose what Android may share");
        LinearLayout row = chipRow();
        modeChips.clear();
        modeChips.add(chip("ENTIRE SCREEN", new Runnable() {
            @Override public void run() {
                mode = ScreenRecorderConfig.MODE_FULLSCREEN;
                saveSettings();
                updateAll();
            }
        }));
        modeChips.add(chip("SINGLE APP", new Runnable() {
            @Override public void run() {
                if (Build.VERSION.SDK_INT < 34) {
                    toast("Single-app capture needs Android 14 or newer.");
                    return;
                }
                mode = ScreenRecorderConfig.MODE_SINGLE_APP;
                saveSettings();
                updateAll();
            }
        }));
        for (TextView c : modeChips) row.addView(c, chipLp(row.getChildCount() > 0));
        section.addView(row, matchWrap());
        TextView note = note("Single app opens Android's app picker. Entire screen records everything "
                + "including system UI.");
        section.addView(note, matchWrap());
        return section;
    }

    private View buildQualitySection() {
        LinearLayout section = section("QUALITY PRESET", "Fast to set, fully adjustable below");
        LinearLayout row = chipRow();
        qualityChips.clear();
        qualityChips.add(chip("EFFICIENT", new Runnable() {
            @Override public void run() { applyQuality(ScreenRecorderConfig.QUALITY_EFFICIENT); }
        }));
        qualityChips.add(chip("BALANCED", new Runnable() {
            @Override public void run() { applyQuality(ScreenRecorderConfig.QUALITY_BALANCED); }
        }));
        qualityChips.add(chip("HIGH QUALITY", new Runnable() {
            @Override public void run() { applyQuality(ScreenRecorderConfig.QUALITY_HIGH); }
        }));
        for (TextView c : qualityChips) row.addView(c, chipLp(row.getChildCount() > 0));
        section.addView(row, matchWrap());
        section.addView(note("Efficient saves space, Balanced is the everyday choice, "
                + "High Quality favours detail and frame rate."), matchWrap());
        return section;
    }

    private View buildVideoSection() {
        LinearLayout section = section("VIDEO", "Resolution, frame rate, bitrate and format");

        section.addView(rowLabel("RESOLUTION"), matchWrap());
        LinearLayout resRow = chipRow();
        resolutionChips.clear();
        String[] resLabels = {"480P", "720P", "1080P", "NATIVE"};
        for (int i = 0; i < resLabels.length; i++) {
            final int value = i;
            resolutionChips.add(chip(resLabels[i], new Runnable() {
                @Override public void run() {
                    resolution = value;
                    saveSettings();
                    updateAll();
                }
            }));
        }
        for (TextView c : resolutionChips) resRow.addView(c, chipLp(resRow.getChildCount() > 0));
        section.addView(resRow, matchWrap());

        section.addView(rowLabel("FRAME RATE"), rowLabelLp());
        LinearLayout rateRow = chipRow();
        fpsChips.clear();
        fpsChips.add(chip("30 FPS", new Runnable() {
            @Override public void run() { fps = 30; saveSettings(); updateAll(); }
        }));
        fpsChips.add(chip("60 FPS", new Runnable() {
            @Override public void run() { fps = 60; saveSettings(); updateAll(); }
        }));
        fpsChips.add(chip("120 FPS", new Runnable() {
            @Override public void run() { fps = 120; saveSettings(); updateAll(); }
        }));
        for (TextView c : fpsChips) rateRow.addView(c, chipLp(rateRow.getChildCount() > 0));
        section.addView(rateRow, matchWrap());

        section.addView(rowLabel("FORMAT / CODEC"), rowLabelLp());
        HorizontalScrollView formatScroll = new HorizontalScrollView(this);
        formatScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout formatRow = chipRow();
        formatChips.clear();
        formatChips.add(chip("MP4 \u00b7 H.264", new Runnable() {
            @Override public void run() { setFormat(ScreenRecorderConfig.FORMAT_MP4_AVC); }
        }));
        formatChips.add(chip("MP4 \u00b7 HEVC", new Runnable() {
            @Override public void run() { setFormat(ScreenRecorderConfig.FORMAT_MP4_HEVC); }
        }));
        for (TextView c : formatChips) formatRow.addView(c, chipLp(formatRow.getChildCount() > 0));
        formatScroll.addView(formatRow);
        section.addView(formatScroll, matchWrap());
        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);
        bitrateRow.setGravity(Gravity.CENTER_VERTICAL);
        bitrateRow.setPadding(0, dp(8), 0, 0);
        TextView brLabel = text("BITRATE", 8.5f, MUTED, false);
        brLabel.setLetterSpacing(0.08f);
        bitrateRow.addView(brLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bitrateValue = text("10 Mbps", 9.5f, 0xF2FFFFFF, true);
        bitrateRow.addView(bitrateValue);
        section.addView(bitrateRow, matchWrap());

        bitrateBar = new SeekBar(this);
        bitrateBar.setMax(BITRATE_MAX - BITRATE_MIN);
        bitrateBar.setFocusable(true);
        bitrateBar.setClickable(true);
        bitrateBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        bitrateBar.setThumbTintList(android.content.res.ColorStateList.valueOf(
                ThemeColors.accent()));
        bitrateBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                0x33FFFFFF));
        bitrateBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || updating) return;
                bitrateKbps = BITRATE_MIN + progress;
                saveSettings();
                updateBitrateLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        sliderLp.topMargin = dp(1);
        section.addView(bitrateBar, sliderLp);

        LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.HORIZONTAL);
        scale.setPadding(0, dp(1), 0, 0);
        TextView minLabel = note("2 Mbps");
        minLabel.setTextColor(0x9AFFFFFF);
        TextView maxLabel = note("MAX 100 Mbps");
        maxLabel.setTextColor(0x9AFFFFFF);
        maxLabel.setGravity(Gravity.END);
        scale.addView(minLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scale.addView(maxLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        section.addView(scale, matchWrap());
        LinearLayout spacer = new LinearLayout(this);
        section.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return section;
    }

    private void setFormat(int value) {
        format = value;
        saveSettings();
        if (value == ScreenRecorderConfig.FORMAT_AVI_AVC) {
            // AVI PCM has no compressed audio track to negotiate.
        }
        updateAll();
    }

    private View buildAudioSection() {
        LinearLayout section = section("AUDIO & TOUCHES", "Ingest sound and show interaction points");
        deviceChip = toggleRow(section, "Device audio",
                "Internal media and game sound. Protected apps may stay silent.",
                deviceAudio, new Runnable() {
                    @Override public void run() {
                        deviceAudio = !deviceAudio;
                        saveSettings();
                        updateAll();
                    }
                });
        micChip = toggleRow(section, "Microphone",
                "Mixes your voice with the selected audio source.",
                micAudio, new Runnable() {
                    @Override public void run() {
                        micAudio = !micAudio;
                        saveSettings();
                        updateAll();
                    }
                });
        touchChip = toggleRow(section, "Show touches",
                "System touch dots, enabled through PServer/root while recording.",
                showTouches, new Runnable() {
                    @Override public void run() {
                        showTouches = !showTouches;
                        saveSettings();
                        updateAll();
                    }
                });
        audioStatus = note("");
        section.addView(audioStatus, matchWrap());

        LinearLayout spacer = new LinearLayout(this);
        section.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        dividerLp.topMargin = dp(7);
        section.addView(divider, dividerLp);
        TextView summaryTitle = text("SESSION SUMMARY", 9f, 0xFFFFFFFF, false);
        summaryTitle.setLetterSpacing(0.09f);
        summaryTitle.setPadding(0, dp(6), 0, 0);
        section.addView(summaryTitle, matchWrap());
        summaryLine = note("");
        section.addView(summaryLine, matchWrap());
        return section;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(5), 0, 0);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        status = text("", 9f, 0xE6FFFFFF, false);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusRow.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pathLabel = text("", 8f, 0x9AFFFFFF, true);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        statusRow.addView(pathLabel);
        footer.addView(statusRow, matchWrap());

        recordButton = button("START RECORDING", true);
        recordButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        recordButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(43));
        buttonLp.topMargin = dp(3);
        footer.addView(recordButton, buttonLp);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ScreenRecorderService.isRecording()) {
                    Intent stop = new Intent(ScreenRecorderActivity.this,
                            ScreenRecorderService.class);
                    stop.setAction(ScreenRecorderService.ACTION_STOP);
                    startService(stop);
                    status.setText("Stopping and saving\u2026");
                    updateRecordingButton();
                } else {
                    requestStart();
                }
            }
        });
        return footer;
    }

    private TextView toggleRow(LinearLayout parent, String title, String description,
            boolean on, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(Ui.widthDp(this) < 700 ? 3 : 5), 0,
                dp(Ui.widthDp(this) < 700 ? 2 : 3));
        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(title, Ui.widthDp(this) < 700 ? 9f : 10f,
                0xFFFFFFFF, false);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView desc = note(description);
        textColumn.addView(label);
        textColumn.addView(desc);
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chip = chip(on ? "ON" : "OFF", action);
        chip.setMinWidth(dp(44));
        row.addView(chip, new LinearLayout.LayoutParams(dp(48), dp(28)));
        parent.addView(row, matchWrap());
        return chip;
    }

    private void equalizeCards(final LinearLayout row, final View first, final View second) {
        row.post(new Runnable() {
            @Override public void run() {
                int h = Math.max(first.getHeight(), second.getHeight());
                if (h <= 0) {
                    row.postDelayed(this, 30L);
                    return;
                }
                ViewGroup.LayoutParams a = first.getLayoutParams();
                ViewGroup.LayoutParams b = second.getLayoutParams();
                if (a.height != h || b.height != h) {
                    a.height = h;
                    b.height = h;
                    first.setLayoutParams(a);
                    second.setLayoutParams(b);
                }
            }
        });
    }

    private LinearLayout section(String title, String subtitle) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        boolean tight = Ui.widthDp(this) < 700;
        section.setPadding(dp(tight ? 11 : 14), dp(tight ? 8 : 11),
                dp(tight ? 11 : 14), dp(tight ? 8 : 11));
        section.setBackground(Ui.rounded(0x30000000, dp(12), 0x33FFFFFF, dp(1)));
        TextView label = text(title, tight ? 9f : 10f, 0xFFFFFFFF, false);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.11f);
        section.addView(label, matchWrap());
        TextView sub = note(subtitle);
        section.addView(sub, matchWrap());
        return section;
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(1));
        return row;
    }

    private TextView chip(String label, final Runnable action) {
        TextView v = text(label, 9f, 0xF2FFFFFF, false);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(dp(11), 0, dp(11), 0);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        return v;
    }

    private void styleChip(TextView v, boolean selected) {
        if (v == null) return;
        int accent = ThemeColors.accent();
        int fill = selected ? (0x66000000 | (accent & 0x00FFFFFF)) : 0x4D000000;
        v.setBackground(Ui.rounded(fill, dp(9),
                selected ? accent : 0x44FFFFFF, dp(1)));
        v.setTextColor(0xFFFFFFFF);
    }

    private void updateAll() {
        updating = true;
        styleChips(modeChips, mode);
        styleChips(qualityChips, quality);
        styleChips(resolutionChips, resolution);
        styleChips(fpsChips, fps >= 120 ? 2 : fps == 60 ? 1 : 0);
        styleChips(formatChips, format);
        styleToggle(deviceChip, deviceAudio);
        styleToggle(micChip, micAudio);
        styleToggle(touchChip, showTouches);
        if (bitrateBar != null) {
            int progress = Math.max(0, Math.min(BITRATE_MAX - BITRATE_MIN,
                    bitrateKbps - BITRATE_MIN));
            if (!bitrateBar.isPressed() && bitrateBar.getProgress() != progress) {
                bitrateBar.setProgress(progress);
            }
        }
        updateBitrateLabel();
        updateFormatNote();
        updateAudioStatus();
        updateSummary();
        updateRecordingButton();
        updating = false;
    }

    private void styleChips(List<TextView> chips, int selected) {
        for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), i == selected);
    }

    private void styleToggle(TextView v, boolean on) {
        if (v == null) return;
        v.setText(on ? "ON" : "OFF");
        styleChip(v, on);
    }

    private void updateBitrateLabel() {
        if (bitrateValue != null) {
            bitrateValue.setText(String.format(Locale.US, "%.0f Mbps",
                    bitrateKbps / 1000f));
        }
    }

    private void updateFormatNote() {
        if (formatNote == null) return;
        int[] dims = resolvedDimensions();
        String note = dims[0] + " \u00d7 " + dims[1] + "  \u00b7  " + fps + " fps"
                + "  \u00b7  " + (format == ScreenRecorderConfig.FORMAT_AVI_AVC
                ? "PCM audio" : format == ScreenRecorderConfig.FORMAT_WEBM_VP9
                ? "Opus audio" : "AAC audio");
        formatNote.setText(note);
    }

    private void updateSummary() {
        if (summaryLine == null) return;
        int[] dims = resolvedDimensions();
        String audio = !deviceAudio && !micAudio ? "No audio"
                : deviceAudio && micAudio ? "Device + mic"
                : deviceAudio ? "Device audio" : "Microphone";
        String touches = showTouches ? "Touch dots on" : "Touch dots off";
        summaryLine.setText(formatLabel() + "\n"
                + dims[0] + " \u00d7 " + dims[1] + "  \u00b7  " + fps + " fps  \u00b7  "
                + Math.round(bitrateKbps / 1000f) + " Mbps\n"
                + (mode == ScreenRecorderConfig.MODE_SINGLE_APP
                ? "Single app" : "Entire screen") + "  \u00b7  " + audio + "  \u00b7  "
                + touches + "\nSaved to Movies/Aurora Recordings");

    }

    private void updateAudioStatus() {
        if (audioStatus == null) return;
        if (format == ScreenRecorderConfig.FORMAT_AVI_AVC) {
            audioStatus.setText("AVI uses uncompressed PCM audio. Large files; "
                    + "best for short clips and compatibility.");
        } else if (!PServer.available() && showTouches) {
            audioStatus.setText("Touch dots need PServer/root; recording can still continue.");
        } else {
            audioStatus.setText("Device audio may be silent for protected apps. "
                    + "Microphone needs the audio permission.");
        }
    }

    private void updateRecordingButton() {
        if (recordButton == null) return;
        if (ScreenRecorderService.isRecording()) {
            recordButton.setText("STOP RECORDING");
            recordButton.setBackground(Ui.rounded(0xE6FF3D3D, dp(10), 0x66FFD0D0, dp(1)));
            recordButton.setTextColor(0xFFFFFFFF);
            recordButton.setEnabled(true);
        } else if (ScreenRecorderService.isSaving()) {
            recordButton.setText("SAVING\u2026");
            recordButton.setBackground(Ui.rounded(0x33FFFFFF, dp(10), 0x33FFFFFF, dp(1)));
            recordButton.setTextColor(0x99FFFFFF);
            recordButton.setEnabled(false);
        } else {
            recordButton.setText("START RECORDING");
            recordButton.setBackground(Ui.rounded(ThemeColors.accent(), dp(10),
                    0x55FFFFFF, dp(1)));
            recordButton.setTextColor(0xFF071015);
            recordButton.setEnabled(true);
        }
        if (ScreenRecorderService.isRecording()) {
            status.setText("Recording \u00b7 " + elapsedText());
        } else if (ScreenRecorderService.isSaving()) {
            status.setText("Saving recording\u2026");
        } else if (ScreenRecorderService.lastError() != null) {
            status.setText(ScreenRecorderService.lastError());
        } else if (!status.getText().toString().startsWith("Stopping")) {
            status.setText(mode == ScreenRecorderConfig.MODE_SINGLE_APP
                    ? "Ready \u00b7 single app \u00b7 " + formatLabel()
                    : "Ready \u00b7 entire screen \u00b7 " + formatLabel());
        }
        String saved = ScreenRecorderService.lastSavedPath();
        if (pathLabel != null) {
            pathLabel.setText(saved == null ? "" : shortPath(saved));
        }
        main.removeCallbacks(ticker);
        if (ScreenRecorderService.isRecording() || ScreenRecorderService.isSaving()) {
            main.postDelayed(ticker, 500L);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateRecordingButton();
        }
    };

    private String elapsedText() {
        long secs = Math.max(0L,
                (System.currentTimeMillis() - ScreenRecorderService.recordingStartedAt()) / 1000L);
        return String.format(Locale.US, "%02d:%02d", secs / 60L, secs % 60L);
    }

    private String shortPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private String formatLabel() {
        ScreenRecorderConfig c = buildConfig();
        return c.formatLabel();
    }

    // ------------------------------------------------------------------
    // Start flow
    // ------------------------------------------------------------------

    private void requestStart() {
        if (ScreenRecorderService.isRecording() || ScreenRecorderService.isSaving()) return;
        if (Build.VERSION.SDK_INT < 34) mode = ScreenRecorderConfig.MODE_FULLSCREEN;
        saveSettings();
        if (!ensurePermissions()) return;
        requestProjection();
    }

    private boolean ensurePermissions() {
        List<String> wanted = new ArrayList<>();
        if ((deviceAudio || micAudio)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (wanted.isEmpty()) return true;
        pendingStart = true;
        requestPermissions(wanted.toArray(new String[0]), REQ_PERMISSIONS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        boolean audioDenied = false;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])
                    && (i >= grantResults.length
                    || grantResults[i] != PackageManager.PERMISSION_GRANTED)) {
                audioDenied = true;
            }
        }
        if (audioDenied) {
            pendingStart = false;
            toast("Microphone permission is needed for audio capture.");
            updateAll();
            return;
        }
        if (pendingStart) {
            pendingStart = false;
            requestProjection();
        }
    }

    private void requestProjection() {
        if (projectionRequestActive) return;
        projectionRequestActive = true;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                toast("Screen capture is unavailable.");
                return;
            }
            Intent intent;
            if (Build.VERSION.SDK_INT >= 34) {
                MediaProjectionConfig projectionConfig =
                        mode == ScreenRecorderConfig.MODE_SINGLE_APP
                                ? MediaProjectionConfig.createConfigForUserChoice()
                                : MediaProjectionConfig.createConfigForDefaultDisplay();
                intent = manager.createScreenCaptureIntent(projectionConfig);
            } else {
                intent = manager.createScreenCaptureIntent();
            }
            AuroraA11yService.armProjectionAutoAccept(
                    mode == ScreenRecorderConfig.MODE_FULLSCREEN);
            startActivityForResult(intent, REQ_PROJECTION);
        } catch (Throwable t) {
            projectionRequestActive = false;
            AuroraA11yService.armProjectionAutoAccept(false);
            toast(t.getMessage() == null ? "Could not open screen capture." : t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        projectionRequestActive = false;
        AuroraA11yService.armProjectionAutoAccept(false);
        if (resultCode != Activity.RESULT_OK || data == null) {
            status.setText("Screen capture permission was cancelled.");
            return;
        }
        ScreenRecorderConfig config = buildConfig();
        Intent configIntent = new Intent();
        config.writeTo(configIntent);
        Intent service = new Intent(this, ScreenRecorderService.class)
                .setAction(ScreenRecorderService.ACTION_START)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenRecorderService.EXTRA_CONFIG, configIntent);
        try {
            startForegroundService(service);
            status.setText("Starting recorder\u2026");
            updateRecordingButton();
            if (config.mode == ScreenRecorderConfig.MODE_FULLSCREEN) {
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing() && !isDestroyed()) moveTaskToBack(true);
                    }
                }, 900L);
            }
        } catch (Throwable t) {
            toast(t.getMessage() == null ? "Could not start recording." : t.getMessage());
        }
    }

    private ScreenRecorderConfig buildConfig() {
        int[] dims = resolvedDimensions();
        return new ScreenRecorderConfig(mode, quality, resolution, format, fps,
                bitrateKbps, deviceAudio, micAudio, showTouches,
                dims[0], dims[1], realMetrics().densityDpi);
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);
        } catch (Throwable ignored) {
            metrics.setTo(getResources().getDisplayMetrics());
        }
        return metrics;
    }

    private int[] resolvedDimensions() {
        DisplayMetrics metrics = realMetrics();
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        if (w <= 0 || h <= 0) return new int[]{1920, 1080};
        if (resolution == ScreenRecorderConfig.RES_NATIVE) {
            return new int[]{even(w), even(h)};
        }
        int target = resolution == ScreenRecorderConfig.RES_480 ? 480
                : resolution == ScreenRecorderConfig.RES_720 ? 720 : 1080;
        if (target >= Math.min(w, h)) return new int[]{even(w), even(h)};
        if (w >= h) {
            return new int[]{even(Math.round(target * (float) w / h)), target};
        }
        return new int[]{target, even(Math.round(target * (float) h / w))};
    }

    private static int even(int value) {
        return Math.max(2, value & ~1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams sectionLp(int top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(top);
        return lp;
    }

    private LinearLayout.LayoutParams chipLp(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(33));
        if (left) lp.leftMargin = dp(5);
        return lp;
    }

    private TextView rowLabel(String value) {
        TextView v = text(value, 8.5f, MUTED, false);
        v.setLetterSpacing(0.07f);
        v.setPadding(0, dp(6), 0, 0);
        return v;
    }

    private LinearLayout.LayoutParams rowLabelLp() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(2);
        return lp;
    }

    private TextView note(String value) {
        TextView v = text(value, Ui.widthDp(this) < 700 ? 8f : 8.5f, 0xB8FFFFFF, false);
        v.setLineSpacing(0f, 1.12f);
        v.setPadding(0, dp(2), 0, 0);
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 10f, primary ? 0xFF071015 : 0xF2FFFFFF, true);
        v.setShadowLayer(0f, 0f, 0f, 0x00000000);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(primary ? ThemeColors.accent() : 0x22FFFFFF,
                dp(10), primary ? 0x00000000 : 0x44FFFFFF, dp(1)));
        return v;
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        v.setShadowLayer(dp(1.5f), 0f, dp(0.8f), 0x99000000);
        return v;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Recorded videos list; opens the same player used by File Manager. */
    private void showRecordingsPage() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final ControllerNav pageNav = new ControllerNav(this, null);
        ControllerNav.RootLayout root = new ControllerNav.RootLayout(this);
        root.setNavigator(pageNav);
        root.setKeyInterceptor(new ControllerNav.KeyInterceptor() {
            @Override public boolean onKey(KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN
                        || event.getRepeatCount() > 0) return false;
                int code = event.getKeyCode();
                if (code != KeyEvent.KEYCODE_DPAD_LEFT
                        && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                View focus = dialog.getWindow() == null ? null
                        : dialog.getWindow().getDecorView().findFocus();
                Object target = focus == null ? null : focus.getTag();
                if (!(target instanceof View)) return false;
                boolean rowToDelete = code == KeyEvent.KEYCODE_DPAD_RIGHT
                        && focus instanceof ViewGroup;
                boolean deleteToRow = code == KeyEvent.KEYCODE_DPAD_LEFT
                        && focus instanceof TextView;
                if (!rowToDelete && !deleteToRow) return false;
                View view = (View) target;
                if (view.getVisibility() != View.VISIBLE || !view.isEnabled()) return false;
                view.requestFocus();
                return true;
            }
        });
        root.setFocusable(false);
        root.setBackgroundColor(0x00000000);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        Backdrop backdrop = sheet == null ? null : sheet.getBackdrop();
        View veil = new View(this);
        veil.setBackgroundColor(backdrop == null ? 0x73000000 : 0x33000000);
        root.addView(veil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GlassPanel card = new GlassPanel(this, backdrop, ThemeColors.accent(),
                0x4D0B0E14, 26, true);
        card.setFrosted(true);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(760)),
                Math.min(getResources().getDisplayMetrics().heightPixels - dp(32), dp(620)),
                Gravity.CENTER);
        root.addView(card, cardLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("RECORDINGS", 15f, 0xFFFFFFFF, false);
        title.setLetterSpacing(0.12f);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(36), dp(32)));
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(4));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final View[] firstFocus = new View[1];
        List<File> videos = recordings();
        if (videos.isEmpty()) {
            TextView empty = text("No recordings yet.\nSaved videos appear here.", 10f, 0xB8FFFFFF, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(30), dp(16), dp(30));
            list.addView(empty);
        } else {
            final java.text.SimpleDateFormat date = new java.text.SimpleDateFormat(
                    "MMM d, HH:mm", Locale.US);
            for (final File file : videos) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackground(Ui.rounded(0x33000000, dp(11)));
                row.setFocusable(true);
                row.setClickable(true);
                TextView name = text(file.getName(), 10.5f, 0xFFFFFFFF, false);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView meta = text(date.format(new Date(file.lastModified()))
                        + "  ·  " + formatBytes(file.length()), 8.5f, 0xB8FFFFFF, false);
                LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                metaLp.leftMargin = dp(8);
                row.addView(meta, metaLp);
                final Dialog owner = dialog;
                TextView delete = text("DELETE", 8f, 0xFFFF8A80, false);
                delete.setGravity(Gravity.CENTER);
                delete.setPadding(dp(8), 0, dp(8), 0);
                delete.setBackground(Ui.rounded(0x33FF3D3D, dp(8),
                        0x66FF3D3D, dp(1)));
                delete.setFocusable(true);
                delete.setClickable(true);
                delete.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        confirmDeleteRecording(owner, file);
                    }
                });
                row.setTag(delete);
                delete.setTag(row);
                LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
                deleteLp.leftMargin = dp(8);
                row.addView(delete, deleteLp);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // Keep the recordings page alive behind the player so
                        // closing playback returns to this list.
                        Intent play = new Intent(ScreenRecorderActivity.this,
                                VideoPlayerActivity.class);
                        play.putExtra(VideoPlayerActivity.EXTRA_PATH, file.getAbsolutePath());
                        startActivity(play);
                    }
                });
                if (firstFocus[0] == null) firstFocus[0] = row;
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(6);
                list.addView(row, rowLp);
            }
        }
        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;
            window.setAttributes(lp);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        pageNav.attachDialog(dialog, card);
        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                pageNav.release();
            }
        });
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int keyCode,
                                           KeyEvent event) {
                if ((keyCode == KeyEvent.KEYCODE_BUTTON_B
                        || keyCode == KeyEvent.KEYCODE_BACK)
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    if (!dialog.isShowing()) return;
                    pageNav.decorateFocusables();
                    View target = firstFocus[0] != null ? firstFocus[0] : close;
                    target.requestFocus();
                }
            }, 240L);
        }
        Window shown = dialog.getWindow();
        if (shown != null) {
            shown.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            WindowInsetsController controller = shown.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void confirmDeleteRecording(final Dialog owner, final File file) {
        final Dialog confirm = new Dialog(this);
        confirm.requestWindowFeature(Window.FEATURE_NO_TITLE);
        confirm.setCanceledOnTouchOutside(true);

        final ControllerNav confirmNav = new ControllerNav(this, null);
        ControllerNav.RootLayout root = new ControllerNav.RootLayout(this);
        root.setNavigator(confirmNav);
        root.setFocusable(false);
        root.setBackgroundColor(0x00000000);
        View veil = new View(this);
        veil.setBackgroundColor(0x66000000);
        root.addView(veil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirm.dismiss(); }
        });

        // Keep the recordings page visible behind the confirmation. This card
        // is a clear glass veil rather than a fresh capture of the home screen.
        GlassPanel card = new GlassPanel(this, null, ThemeColors.accent(),
                0x660B0E14, 24, true);
        card.setFrosted(true);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }
        });
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(430)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(card, cardLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("DELETE RECORDING?", 12.5f, 0xFFFFFFFF, false);
        title.setLetterSpacing(0.10f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 21f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirm.dismiss(); }
        });
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(32), dp(30)));
        card.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = text(file.getName(), 10.5f, 0xFFFFFFFF, true);
        name.setPadding(0, dp(8), 0, 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        card.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView warning = text("This permanently removes the video from Movies/Aurora Recordings.",
                9f, 0xB8FFFFFF, false);
        warning.setLineSpacing(0f, 1.12f);
        warning.setPadding(0, dp(6), 0, dp(12));
        card.addView(warning, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = button(Loc.s(this, "CANCEL"), false);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirm.dismiss(); }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(dp(92), dp(34));
        cancelLp.rightMargin = dp(8);
        actions.addView(cancel, cancelLp);

        TextView delete = button(Loc.s(this, "DELETE"), false);
        delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        delete.setTextColor(0xFFFFFFFF);
        delete.setBackground(Ui.rounded(0xE6FF3D3D, dp(10),
                0x99FF8A80, dp(1)));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (file.delete()) {
                    MediaScannerConnection.scanFile(ScreenRecorderActivity.this,
                            new String[]{file.getAbsolutePath()}, null, null);
                    confirm.dismiss();
                    if (owner != null && owner.isShowing()) owner.dismiss();
                    showRecordingsPage();
                } else {
                    LToast.makeText(ScreenRecorderActivity.this,
                            "Could not delete recording", Toast.LENGTH_SHORT).show();
                }
            }
        });
        actions.addView(delete, new LinearLayout.LayoutParams(dp(94), dp(34)));
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        confirm.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window window = confirm.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;
            window.setAttributes(lp);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        confirm.show();
        confirmNav.attachDialog(confirm, card);
        confirm.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                confirmNav.release();
            }
        });
        confirm.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int keyCode,
                                           KeyEvent event) {
                if ((keyCode == KeyEvent.KEYCODE_BUTTON_B
                        || keyCode == KeyEvent.KEYCODE_BACK)
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    confirm.dismiss();
                    return true;
                }
                return false;
            }
        });
        Window shown = confirm.getWindow();
        if (shown != null) {
            shown.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            WindowInsetsController controller = shown.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        card.postDelayed(new Runnable() {
            @Override public void run() {
                Motion.giggleIn(card, 0.95f, 280L, 1.25f, 0.8f, false);
                confirmNav.decorateFocusables();
                if (!cancel.isFocused()) cancel.requestFocus();
            }
        }, 240L);
    }

    private List<File> recordings() {
        List<File> out = new ArrayList<>();
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "Aurora Recordings");
        File fallback = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File fallbackDir = fallback == null ? null
                : new File(fallback, "Aurora Recordings");
        addVideos(out, dir);
        if (fallbackDir != null) addVideos(out, fallbackDir);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private void addVideos(List<File> out, File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".mp4") || n.endsWith(".avi") || n.endsWith(".webm")
                    || n.endsWith(".mkv") || n.endsWith(".mov")) out.add(f);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024f * 1024f * 1024f));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
        }
        return String.format(Locale.US, "%.0f KB", bytes / 1024f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
