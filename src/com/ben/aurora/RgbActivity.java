package com.ben.aurora;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in Aurora app: joystick RGB modelling, modes and brightness. */
public final class RgbActivity extends AuroraGlassActivity {

    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int RED = 0xFFFF8A80;
    private static final int CARD_FILL = 0x12FFFFFF;
    private static final int CARD_BORDER = 0x22FFFFFF;
    private static final int CARD_RADIUS = 18;
    private static final int CARD_PADDING = 14;

    private static final int[] MODE_VALUES = {
            RgbControl.MODE_STATIC, RgbControl.MODE_BREATH,
            RgbControl.MODE_RAINBOW, RgbControl.MODE_STROBE,
            RgbControl.MODE_SCREEN, RgbControl.MODE_OFF
    };
    private static final String[] MODE_LABELS = {
            "STATIC", "BREATH", "RAINBOW", "STROBE", "SCREEN", "OFF"
    };

    private static volatile boolean visible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextView> modeButtons = new ArrayList<>();
    private final ExecutorService staticWriter = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private ColorWheelView wheel;
    private TextView hexView;
    private TextView rgbView;
    private TextView status;
    private SeekBar hueBar;
    private SeekBar satBar;
    private SeekBar brightnessBar;
    private SeekBar speedBar;
    private JoystickPreviewView leftPreview;
    private JoystickPreviewView rightPreview;
    private TextView leftLabel;
    private TextView rightLabel;

    private int mode;
    private int color;
    private int brightness;
    private int speed;
    private boolean leftOn;
    private boolean rightOn;
    private boolean suppress;
    private boolean tight;
    private boolean firstApply;
    private boolean initialFocusDone;
    private int serviceMode = -1;
    private int writeGeneration;
    private long lastApplyAt;
    private long lastStickNavAt;
    private int lastStickNavDir;
    private boolean colorStickActive;
    private float colorStickX;
    private float colorStickY;
    private long lastWheelHaptic;
    private boolean sliderStickActive;
    private float sliderStickAxis;
    private long lastSliderHaptic;

    static boolean isVisible() {
        return visible;
    }

    private final Runnable applyRunnable = new Runnable() {
        @Override
        public void run() {
            applyNow();
        }
    };

    private final Runnable previewPoll = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            updatePreview();
            handler.postDelayed(this, 70L);
        }
    };

    private final Runnable colorStickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!colorStickActive || wheel == null || !wheel.isFocused()) {
                colorStickActive = false;
                return;
            }
            float step = 0.024f;
            double angle = Math.toRadians(wheel.hue());
            float x = (float) Math.cos(angle) * wheel.saturation()
                    + colorStickX * 1.1f * step;
            float y = (float) Math.sin(angle) * wheel.saturation()
                    + colorStickY * 1.1f * step;
            float magnitude = (float) Math.hypot(x, y);
            if (magnitude > 1f) {
                x /= magnitude;
                y /= magnitude;
                magnitude = 1f;
            }
            float hue = (float) Math.toDegrees(Math.atan2(y, x));
            if (hue < 0f) hue += 360f;
            wheel.setHueSaturation(hue, magnitude);
            color = wheel.color();
            updateFromWheel();
            scheduleApply();
            long now = SystemClock.uptimeMillis();
            if (now - lastWheelHaptic >= 50L) {
                lastWheelHaptic = now;
                Haptics.tick(wheel);
            }
            handler.postDelayed(this, 24L);
        }
    };

    private final Runnable sliderStickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sliderStickActive) return;
            SeekBar bar = focusedSlider();
            if (bar == null) {
                sliderStickActive = false;
                return;
            }
            float axis = sliderStickAxis;
            if (Math.abs(axis) < 0.18f) {
                sliderStickActive = false;
                return;
            }
            int max = bar.getMax();
            int delta = Math.round(axis * max * 0.04f);
            if (delta == 0) delta = axis > 0f ? 1 : -1;
            int next = Math.max(0, Math.min(max, bar.getProgress() + delta));
            if (next != bar.getProgress()) bar.setProgress(next);
            handler.postDelayed(this, 24L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        tight = Ui.widthDp(this) < 700;
        mode = prefs.rgbMode();
        color = prefs.rgbColor();
        brightness = prefs.rgbBrightness();
        speed = prefs.rgbSpeed();
        leftOn = prefs.rgbLeft();
        rightOn = prefs.rgbRight();
        serviceMode = mode == RgbControl.MODE_OFF ? RgbControl.MODE_OFF : -1;
        buildUi();
        hideBars();
        syncControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        hideBars();
        hidePerformanceOverlay();
        if (!firstApply) {
            firstApply = true;
            if (mode != RgbControl.MODE_OFF) applyNow();
        }
        handler.removeCallbacks(previewPoll);
        handler.post(previewPoll);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusWheelOnce();
            }
        }, 120L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            wheel.postDelayed(new Runnable() {
                @Override
                public void run() {
                    focusWheelOnce();
                }
            }, 40L);
        }
    }

    private void focusWheelOnce() {
        if (initialFocusDone || wheel == null) return;
        boolean focused = wheel.requestFocus();
        if (!focused) focused = wheel.requestFocusFromTouch();
        if (focused) initialFocusDone = true;
    }

    @Override
    protected void onPause() {
        visible = false;
        handler.removeCallbacks(previewPoll);
        stopColorStick();
        stopSliderStick();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        staticWriter.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
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
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(2), 0, dp(2));
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Joystick outlines get their own compact card. The title overlays the
        // card so the two outlines stay centred in the full card width.
        FrameLayout joystickCard = new FrameLayout(this);
        joystickCard.setPadding(dp(tight ? 10 : 14), dp(tight ? 4 : 7),
                dp(tight ? 10 : 14), dp(tight ? 4 : 7));
        joystickCard.setBackground(Ui.rounded(CARD_FILL, dp(CARD_RADIUS),
                CARD_BORDER, dp(1)));
        LinearLayout.LayoutParams joystickCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        joystickCardLp.topMargin = dp(8);
        content.addView(joystickCard, joystickCardLp);

        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.HORIZONTAL);
        previews.setGravity(Gravity.CENTER);
        addJoystickPreview(previews, true);
        View stickDivider = new View(this);
        stickDivider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams stickDividerLp = new LinearLayout.LayoutParams(
                dp(1), dp(34));
        stickDividerLp.gravity = Gravity.CENTER_VERTICAL;
        previews.addView(stickDivider, stickDividerLp);
        addJoystickPreview(previews, false);
        FrameLayout.LayoutParams previewsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        joystickCard.addView(previews, previewsLp);

        LinearLayout joystickTitle = new LinearLayout(this);
        joystickTitle.setOrientation(LinearLayout.VERTICAL);
        TextView jt = text("JOYSTICKS", 10.5f, accent, true);
        jt.setLetterSpacing(0.12f);
        joystickTitle.addView(jt);
        FrameLayout.LayoutParams joystickTitleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        joystickCard.addView(joystickTitle, joystickTitleLp);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mainLp.topMargin = dp(9);
        content.addView(main, mainLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLp.rightMargin = dp(5);
        main.addView(left, leftLp);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = dp(5);
        main.addView(right, rightLp);

        LinearLayout colorSection = section(left, "COLOR", "");
        LinearLayout.LayoutParams colorLp =
                (LinearLayout.LayoutParams) colorSection.getLayoutParams();
        colorLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        colorLp.topMargin = 0;
        colorSection.setLayoutParams(colorLp);
        FrameLayout wheelBox = new FrameLayout(this);
        wheel = new ColorWheelView(this);
        wheelBox.addView(wheel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams wheelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wheelLp.topMargin = dp(6);
        colorSection.addView(wheelBox, wheelLp);

        hexView = text("#2BE0D8", 15f, TEXT, true);
        hexView.setGravity(Gravity.CENTER);
        hexView.setPadding(0, dp(5), 0, 0);
        colorSection.addView(hexView);
        rgbView = text("R 43 · G 224 · B 216", 9.5f, MUTED, false);
        rgbView.setGravity(Gravity.CENTER);
        rgbView.setPadding(0, dp(1), 0, dp(4));
        colorSection.addView(rgbView);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        right.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tight ? 126 : 150)));

        LinearLayout outputSection = section(top, "OUTPUT", "");
        LinearLayout.LayoutParams outputLp =
                (LinearLayout.LayoutParams) outputSection.getLayoutParams();
        outputLp.width = 0;
        outputLp.weight = 1f;
        outputLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        outputLp.topMargin = 0;
        outputLp.rightMargin = dp(5);
        outputSection.setLayoutParams(outputLp);
        brightnessBar = addSlider(outputSection, "Brightness", "%", 1, 100, brightness,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        brightness = Math.max(1, value);
                        updatePreview();
                        scheduleApply();
                    }
                });
        View outputDivider = new View(this);
        outputDivider.setBackgroundColor(0x20FFFFFF);
        LinearLayout.LayoutParams outputDividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        outputDividerLp.topMargin = dp(5);
        outputSection.addView(outputDivider, outputDividerLp);
        speedBar = addSlider(outputSection, "Speed", "%", 10, 300, speed,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        speed = Math.max(10, Math.min(300, value));
                        scheduleApply();
                    }
                });

        LinearLayout modeSection = section(top, "MODE", "");
        LinearLayout.LayoutParams modeLp =
                (LinearLayout.LayoutParams) modeSection.getLayoutParams();
        modeLp.width = 0;
        modeLp.weight = 1f;
        modeLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        modeLp.topMargin = 0;
        modeLp.leftMargin = dp(5);
        modeSection.setLayoutParams(modeLp);
        LinearLayout modeBody = new LinearLayout(this);
        modeBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams modeBodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        modeBodyLp.topMargin = dp(4);
        modeSection.addView(modeBody, modeBodyLp);

        LinearLayout modeRow1 = new LinearLayout(this);
        modeRow1.setOrientation(LinearLayout.HORIZONTAL);
        modeRow1.setGravity(Gravity.CENTER_VERTICAL);
        modeBody.addView(modeRow1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addModeButton(modeRow1, "STATIC", RgbControl.MODE_STATIC, false);
        addModeButton(modeRow1, "BREATH", RgbControl.MODE_BREATH, false);
        addModeButton(modeRow1, "RAINBOW", RgbControl.MODE_RAINBOW, true);
        LinearLayout modeRow2 = new LinearLayout(this);
        modeRow2.setOrientation(LinearLayout.HORIZONTAL);
        modeRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams modeRow2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        modeRow2Lp.topMargin = dp(5);
        modeBody.addView(modeRow2, modeRow2Lp);
        addModeButton(modeRow2, "STROBE", RgbControl.MODE_STROBE, false);
        addModeButton(modeRow2, "SCREEN", RgbControl.MODE_SCREEN, false);
        addModeButton(modeRow2, "OFF", RgbControl.MODE_OFF, true);
        outputSection.setMinimumHeight(dp(150));
        modeSection.setMinimumHeight(dp(150));

        LinearLayout tuneSection = section(right, "HUE & SATURATION", "");
        LinearLayout.LayoutParams tuneLp =
                (LinearLayout.LayoutParams) tuneSection.getLayoutParams();
        tuneLp.height = 0;
        tuneLp.weight = 1f;
        tuneSection.setLayoutParams(tuneLp);
        hueBar = addSlider(tuneSection, "Hue", "", 0, 360, 180, new IntCallback() {
            @Override
            public void onValue(int value) {
                updateFromSliders();
            }
        });
        satBar = addSlider(tuneSection, "Saturation", "", 0, 100, 100,
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        updateFromSliders();
                    }
                });

        setContentView(root);

        wheel.setListener(new ColorWheelView.Listener() {
            @Override
            public void onColorChanged(int c) {
                stopColorStick();
                color = c;
                updateFromWheel();
                scheduleApply();
            }
        });
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("RGB LIGHTING", 19f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        titles.addView(title);
        TextView subtitle = text("JOYSTICK LIGHT RINGS", 10f, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        status = text("PSERVER", 10f, GREEN, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(6), dp(12), dp(6));
        status.setBackground(Ui.rounded(0x1816C784, dp(12), 0x4469D98B, dp(1)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(10);
        header.addView(status, slp);

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
        header.addView(close);
        return header;
    }

    private LinearLayout section(LinearLayout parent, String title, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int cp = tight ? 10 : CARD_PADDING;
        box.setPadding(dp(cp), dp(tight ? 6 : 9), dp(cp), dp(tight ? 6 : 9));
        box.setBackground(Ui.rounded(CARD_FILL, dp(CARD_RADIUS),
                CARD_BORDER, dp(1)));
        TextView t = text(title, tight ? 9.5f : 10.5f, accent, true);
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
        lp.topMargin = dp(10);
        parent.addView(box, lp);
        return box;
    }

    private void addModeButton(LinearLayout parent, String label, int value, boolean last) {
        final TextView b = text(label, 9.5f, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinHeight(dp(36));
        // The mode buttons are only a third of the card wide; shrink longer
        // labels (RAINBOW) instead of clipping the last letter.
        b.setPadding(dp(5), dp(7), dp(5), dp(7));
        b.setAutoSizeTextTypeUniformWithConfiguration(7, 9, 1,
                TypedValue.COMPLEX_UNIT_SP);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setDefaultFocusHighlightEnabled(false);
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        v.requestFocus();
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        b.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        final int modeValue = value;
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                selectMode(modeValue);
            }
        });
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) Haptics.tick(v);
                styleModeButton(b, mode == modeValue);
            }
        });
        modeButtons.add(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!last) lp.rightMargin = dp(7);
        parent.addView(b, lp);
    }

    private void styleModeButton(TextView b, boolean selected) {
        if (selected) {
            b.setTextColor(0xFF071018);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setBackground(b.isFocused()
                    ? Ui.rounded(accent, dp(14), 0xCCFFFFFF, dp(2))
                    : Ui.rounded(accent, dp(14)));
        } else if (b.isFocused()) {
            b.setTextColor(0xFFFFFFFF);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setBackground(Ui.rounded(0x26FFFFFF, dp(14), accent, dp(1)));
        } else {
            b.setTextColor(0xE6FFFFFF);
            b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            b.setBackground(Ui.rounded(0x14FFFFFF, dp(14), 0x22FFFFFF, dp(1)));
        }
    }

    private void styleModeButtons() {
        for (int i = 0; i < modeButtons.size() && i < MODE_VALUES.length; i++) {
            styleModeButton(modeButtons.get(i), mode == MODE_VALUES[i]);
        }
    }

    private SeekBar addSlider(LinearLayout parent, String label, String suffix, int min, int max,
                              int value, final IntCallback callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        TextView l = text(label, 11.5f, 0xCCFFFFFF, false);
        row.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView valueView = text(value + suffix, 11.5f, accent, true);
        row.addView(valueView);
        parent.addView(row);
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
        bar.setDefaultFocusHighlightEnabled(false);
        bar.setFocusableInTouchMode(true);
        bar.setPadding(dp(14), dp(5), dp(14), dp(5));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(v + suffix);
                if (!suppress) {
                    if (callback != null) callback.onValue(v);
                    long now = SystemClock.uptimeMillis();
                    if (now - lastSliderHaptic >= 45L) {
                        lastSliderHaptic = now;
                        Haptics.tick(seekBar);
                    }
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
        bar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) Haptics.tick(v);
            }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void addJoystickPreview(LinearLayout parent, final boolean left) {
        final JoystickPreviewView view = new JoystickPreviewView(this);
        view.setContentDescription((left ? "Left" : "Right")
                + " joystick preview. Tap to toggle its RGB ring.");
        view.setListener(new JoystickPreviewView.Listener() {
            @Override
            public void onToggle(JoystickPreviewView v) {
                toggleTarget(left);
            }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), 0, dp(6), 0);
        box.addView(view, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView label = text(left ? "LEFT · ON" : "RIGHT · ON", 8.5f, MUTED, true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(4), 0, 0);
        box.addView(label);
        parent.addView(box, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (left) {
            leftPreview = view;
            leftLabel = label;
        } else {
            rightPreview = view;
            rightLabel = label;
        }
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private void toggleTarget(boolean left) {
        Haptics.click(left ? leftPreview : rightPreview);
        if (left) leftOn = !leftOn;
        else rightOn = !rightOn;
        updatePreview();
        scheduleApply();
    }

    private void selectMode(int value) {
        mode = value;
        styleModeButtons();
        applyNow();
    }

    private void updateFromWheel() {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        suppress = true;
        hueBar.setProgress(Math.round(hsv[0]));
        satBar.setProgress(Math.round(hsv[1] * 100f));
        suppress = false;
        updateColorText();
        updatePreview();
    }

    private void updateFromSliders() {
        if (suppress || wheel == null) return;
        float hue = hueBar.getProgress();
        float sat = satBar.getProgress() / 100f;
        wheel.setHueSaturation(hue, sat);
        color = wheel.color();
        updateColorText();
        updatePreview();
        scheduleApply();
    }

    private SeekBar focusedSlider() {
        View focused = getCurrentFocus();
        if (focused == null) return null;
        if (focused == hueBar || focused == satBar
                || focused == brightnessBar || focused == speedBar) {
            return (SeekBar) focused;
        }
        return null;
    }

    private void startColorStick(float x, float y) {
        colorStickX = x;
        colorStickY = y;
        if (!colorStickActive) {
            colorStickActive = true;
            lastWheelHaptic = 0L;
            handler.post(colorStickRunnable);
        }
    }

    private void stopColorStick() {
        colorStickActive = false;
        handler.removeCallbacks(colorStickRunnable);
    }

    private void startSliderStick(float axis) {
        sliderStickAxis = axis;
        if (!sliderStickActive) {
            sliderStickActive = true;
            handler.post(sliderStickRunnable);
        }
    }

    private void stopSliderStick() {
        sliderStickActive = false;
        handler.removeCallbacks(sliderStickRunnable);
    }

    private void updateColorText() {
        if (hexView == null) return;
        String hex = String.format("#%06X", 0xFFFFFF & color);
        hexView.setText(hex);
        rgbView.setText("R " + Color.red(color) + " · G " + Color.green(color)
                + " · B " + Color.blue(color));
    }

    private void updatePreview() {
        if (leftPreview == null) return;
        boolean animated = isAnimated(mode);
        int ring;
        if (animated && RgbService.previewActive()) {
            ring = RgbService.previewColor();
        } else {
            ring = RgbControl.scaledColor(color, 1f, brightness);
        }
        boolean active = mode != RgbControl.MODE_OFF;
        leftPreview.setPowered(active);
        rightPreview.setPowered(active);
        leftPreview.setRingColor(ring);
        rightPreview.setRingColor(ring);
        leftPreview.setTargetEnabled(leftOn);
        rightPreview.setTargetEnabled(rightOn);
        leftLabel.setText("LEFT · " + (leftOn ? "ON" : "OFF"));
        rightLabel.setText("RIGHT · " + (rightOn ? "ON" : "OFF"));
    }

    private void syncControls() {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        suppress = true;
        wheel.setHueSaturation(hsv[0], hsv[1]);
        hueBar.setProgress(Math.round(hsv[0]));
        satBar.setProgress(Math.round(hsv[1] * 100f));
        brightnessBar.setProgress(Math.max(0, Math.min(99, brightness - 1)));
        speedBar.setProgress(Math.max(0, Math.min(290, speed - 10)));
        suppress = false;
        styleModeButtons();
        updateColorText();
        updatePreview();
        updateStatus();
    }

    private void scheduleApply() {
        long now = SystemClock.uptimeMillis();
        long delay = 55L - (now - lastApplyAt);
        if (delay <= 0L) {
            applyNow();
        } else {
            handler.removeCallbacks(applyRunnable);
            handler.postDelayed(applyRunnable, delay);
        }
    }

    private void applyNow() {
        handler.removeCallbacks(applyRunnable);
        lastApplyAt = SystemClock.uptimeMillis();
        prefs.setRgbMode(mode);
        prefs.setRgbColor(color);
        prefs.setRgbBrightness(brightness);
        prefs.setRgbSpeed(speed);
        prefs.setRgbLeft(leftOn);
        prefs.setRgbRight(rightOn);

        if (mode == RgbControl.MODE_STATIC) {
            if (isAnimated(serviceMode)) {
                RgbService.apply(this, RgbControl.MODE_STATIC,
                        color, brightness, speed, leftOn, rightOn);
            }
            serviceMode = RgbControl.MODE_STATIC;
            scheduleStaticWrite();
        } else if (mode == RgbControl.MODE_OFF) {
            if (serviceMode != RgbControl.MODE_OFF || RgbService.isRunning()) {
                RgbService.apply(this, RgbControl.MODE_OFF,
                        color, brightness, speed, leftOn, rightOn);
                final int generation = ++writeGeneration;
                staticWriter.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (generation == writeGeneration) RgbControl.off();
                    }
                });
            }
            serviceMode = RgbControl.MODE_OFF;
        } else {
            ++writeGeneration;
            RgbService.apply(this, mode, color, brightness, speed, leftOn, rightOn);
            serviceMode = mode;
        }
        updateStatus();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateStatus();
            }
        }, 350L);
    }

    private void scheduleStaticWrite() {
        final int generation = ++writeGeneration;
        final int c = color;
        final int b = brightness;
        final boolean l = leftOn;
        final boolean r = rightOn;
        staticWriter.execute(new Runnable() {
            @Override
            public void run() {
                if (generation != writeGeneration) return;
                RgbControl.applyStatic(c, b, l, r);
            }
        });
    }

    private boolean isAnimated(int value) {
        return value == RgbControl.MODE_BREATH
                || value == RgbControl.MODE_RAINBOW
                || value == RgbControl.MODE_STROBE
                || value == RgbControl.MODE_SCREEN;
    }

    private void updateStatus() {
        if (status == null) return;
        String transport = RgbControl.transportLabel();
        status.setText(transport);
        if ("PSERVER".equals(transport)) {
            status.setTextColor(GREEN);
            status.setBackground(Ui.rounded(0x1816C784, dp(12), 0x4469D98B, dp(1)));
        } else if ("OFFLINE".equals(transport)) {
            status.setTextColor(RED);
            status.setBackground(Ui.rounded(0x22FF5C6C, dp(12), 0x66FF8A80, dp(1)));
        } else {
            status.setTextColor(AMBER);
            status.setBackground(Ui.rounded(0x22FFC857, dp(12), 0x66FFC857, dp(1)));
        }
    }

    // ------------------------------------------------------------------
    // Controller input and navigation
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
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
            if (leftPreview != null) leftPreview.setStick(lx, ly);
            if (rightPreview != null) rightPreview.setStick(rx, ry);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float navX = hatX;
            float navY = hatY;
            if (Math.abs(navX) < 0.55f && Math.abs(navY) < 0.55f) {
                navX = lx;
                navY = ly;
            }
            handleStickNavigation(navX, navY);
            SeekBar slider = focusedSlider();
            float rightMagnitude = (float) Math.hypot(rx, ry);
            if (wheel != null && wheel.isFocused()) {
                stopSliderStick();
                if (rightMagnitude > 0.28f) startColorStick(rx, ry);
                else stopColorStick();
            } else if (slider != null) {
                stopColorStick();
                if (Math.abs(rx) > 0.25f) startSliderStick(rx);
                else stopSliderStick();
            } else {
                stopColorStick();
                stopSliderStick();
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void handleStickNavigation(float x, float y) {
        if (ControllerNav.externalDpadRecent()) {
            lastStickNavDir = 0;
            return;
        }
        float magnitude = (float) Math.hypot(x, y);
        if (magnitude < 0.62f) {
            lastStickNavDir = 0;
            return;
        }
        int direction;
        if (Math.abs(x) > Math.abs(y)) {
            direction = x > 0f ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
        } else {
            direction = y > 0f ? View.FOCUS_DOWN : View.FOCUS_UP;
        }
        long now = SystemClock.uptimeMillis();
        if (direction != lastStickNavDir || now - lastStickNavAt >= 320L) {
            moveFocus(direction);
            lastStickNavDir = direction;
            lastStickNavAt = now;
        }
    }

    private void moveFocus(int direction) {
        stopColorStick();
        stopSliderStick();
        View focused = getCurrentFocus();
        if (focused == null) {
            if (wheel != null) wheel.requestFocus();
            return;
        }
        View next = null;
        View root = getWindow() != null ? getWindow().getDecorView() : null;
        if (root instanceof ViewGroup) {
            next = ((ViewGroup) root).focusSearch(focused, direction);
        }
        if (next == null) next = focused.focusSearch(direction);
        if (next != null && next != focused && next.isFocusable()) {
            next.requestFocus(direction);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ControllerNav.isSyntheticStickDpad(event)) return true;
        ControllerNav.noteExternalDpad(event);
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) finishAnimated();
            return true;
        }
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)
                && focusedSlider() != null) {
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            View focused = getCurrentFocus();
            if (focused == leftPreview || focused == rightPreview) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    focused.performClick();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
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

    private interface IntCallback {
        void onValue(int value);
    }
}
