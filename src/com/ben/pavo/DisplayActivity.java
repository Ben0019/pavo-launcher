package com.ben.pavo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in Pavo app: panel, colour, motion and launcher display settings. */
public final class DisplayActivity extends PavoGlassActivity {
    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int RED = 0xFFFF8A80;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long[] sliderStickAt = {0L};
    private final long[] scrollStickAt = {0L};
    private ScrollView scrollView;
    private final List<Row> rows = new ArrayList<>();
    private final List<View> focusables = new ArrayList<>();
    private final List<Row> systemRows = new ArrayList<>();
    private ExecutorService io;
    private Prefs prefs;
    private DisplayTuner.Snapshot state;
    private boolean updating;
    private boolean destroyed;
    private boolean root;
    private boolean initialFocus;
    private long lastNavAt;
    private long lastSliderHapticAt;

    private TextView status;
    private TextView footerStatus;
    private TextView info;
    private TextView close;
    private ChoiceRow refreshRow;
    private ChoiceRow resolutionRow;
    private ChoiceRow aspectRow;
    private ChoiceRow orientationRow;
    private ChoiceRow densityRow;
    private ToggleRow burnInRow;
    private SliderRow saturationRow;
    private SliderRow glowRow;
    private ActionRow resetDisplayRow;
    private SliderRow panelSaturationRow;
    private SliderRow panelTemperatureRow;
    private SliderRow panelRedRow;
    private SliderRow panelGreenRow;
    private SliderRow panelBlueRow;
    private ToggleRow panelHdrRow;
    private ActionRow resetCalibrationRow;
    private volatile boolean calibrationApplied;
    private volatile boolean hdrSynced;
    private int selectedHeight;
    private int selectedAspect;
    private boolean tight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        io = Executors.newSingleThreadExecutor();
        prefs = new Prefs(this);
        tight = Ui.widthDp(this) < 700;
        buildUi();
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        hidePerformanceOverlay();
        calibrationApplied = false;
        hdrSynced = false;
        refresh(0L);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (io != null) io.shutdownNow();
        PerfTuner.Root.close();
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
        FrameLayout rootView = new FrameLayout(this);
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        card.setPadding(dp(tight ? 16 : 22), dp(tight ? 8 : 12),
                dp(tight ? 16 : 22), dp(tight ? 7 : 10));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        rootView.addView(card, cardLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scrollView = scroll;
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(5), 0, dp(5));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        info = text("READING DISPLAY…", 10.5f, MUTED, false);
        info.setPadding(dp(4), dp(3), dp(4), dp(5));
        body.addView(info);

        LinearLayout panelSection = section(body, "PANEL", "Current mode and capability");
        refreshRow = new ChoiceRow(panelSection, "Refresh rate",
                new String[]{"Auto", "60 Hz", "120 Hz"}, 0, new ChoiceCallback() {
            @Override
            public void onSelected(int index) {
                if (index == 0) runSystem(new Runnable() {
                    @Override
                    public void run() {
                        DisplayTuner.setRefresh(DisplayActivity.this, 0, true);
                    }
                }, true);
                else {
                    final int hz = index == 1 ? 60 : 120;
                    runSystem(new Runnable() {
                        @Override
                        public void run() {
                            DisplayTuner.setRefresh(DisplayActivity.this, hz, false);
                        }
                    }, true);
                }
            }
        });
        systemRows.add(refreshRow);
        resolutionRow = new ChoiceRow(panelSection, "Resolution",
                new String[]{"Native", "1200p", "1080p", "900p", "720p"}, 0,
                new ChoiceCallback() {
                    @Override
                    public void onSelected(int index) {
                        if (index == 0) {
                            runSystem(new Runnable() {
                                @Override
                                public void run() {
                                    DisplayTuner.resetResolution();
                                }
                            }, true);
                        } else {
                            selectedHeight = resolutionHeight(index);
                            applyResolution();
                        }
                        toastAfterResolution();
                    }
                });
        systemRows.add(resolutionRow);
        aspectRow = new ChoiceRow(panelSection, "Aspect ratio",
                new String[]{"Native", "16:9", "16:10", "3:2", "4:3", "21:9"}, 0,
                new ChoiceCallback() {
                    @Override
                    public void onSelected(int index) {
                        selectedAspect = index;
                        if (index == 0) {
                            runSystem(new Runnable() {
                                @Override
                                public void run() {
                                    DisplayTuner.resetResolution();
                                }
                            }, true);
                            selectedHeight = 0;
                        } else {
                            if (selectedHeight <= 0) selectedHeight = 1080;
                            applyAspectResolution();
                        }
                        toastAfterResolution();
                    }
                });
        systemRows.add(aspectRow);
        orientationRow = new ChoiceRow(panelSection, "Orientation",
                new String[]{"Auto", "Landscape", "Portrait"}, 0, new ChoiceCallback() {
                    @Override
                    public void onSelected(int index) {
                        final String mode = index == 0 ? "auto"
                                : index == 1 ? "landscape" : "portrait";
                        runSystem(new Runnable() {
                            @Override
                            public void run() {
                                DisplayTuner.setRotation(DisplayActivity.this, mode);
                            }
                        }, true);
                    }
                });
        systemRows.add(orientationRow);
        densityRow = new ChoiceRow(panelSection, "Display size",
                new String[]{"Native", "320", "360", "420", "480"}, 0,
                new ChoiceCallback() {
                    @Override
                    public void onSelected(int index) {
                        final int dpi = index == 0 ? 0 : Integer.parseInt(
                                index == 1 ? "320" : index == 2 ? "360"
                                        : index == 3 ? "420" : "480");
                        runSystem(new Runnable() {
                            @Override
                            public void run() {
                                DisplayTuner.setDensity(dpi);
                            }
                        }, true);
                    }
                });
        systemRows.add(densityRow);

        LinearLayout calibration = section(body, "CALIBRATION",
                "Global panel saturation, white point and RGB");
        panelSaturationRow = new SliderRow(calibration, "Saturation", 0, 100,
                prefs.panelSaturation(), 5, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setPanelSaturation(value);
                applyColorSoon();
            }

            @Override
            public void onFinished() {
                applyColorNow();
            }
        });
        systemRows.add(panelSaturationRow);
        panelTemperatureRow = new SliderRow(calibration, "Temperature", 2500, 6500,
                prefs.panelTemperature(), 100, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setPanelTemperature(value);
                applyColorSoon();
            }

            @Override
            public void onFinished() {
                applyColorNow();
            }
        });
        systemRows.add(panelTemperatureRow);
        panelHdrRow = new ToggleRow(calibration, "HDR", prefs.panelHdrEnabled(),
                new ToggleCallback() {
            @Override
            public void onChanged(boolean value) {
                prefs.setPanelHdrEnabled(value);
                runSystem(new Runnable() {
                    @Override
                    public void run() {
                        DisplayTuner.setHdrEnabled(value);
                    }
                }, false);
            }
        });
        systemRows.add(panelHdrRow);
        panelRedRow = new SliderRow(calibration, "Red", 50, 150,
                prefs.panelRed(), 5, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setPanelRed(value);
                applyColorSoon();
            }

            @Override
            public void onFinished() {
                applyColorNow();
            }
        });
        systemRows.add(panelRedRow);
        panelGreenRow = new SliderRow(calibration, "Green", 50, 150,
                prefs.panelGreen(), 5, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setPanelGreen(value);
                applyColorSoon();
            }

            @Override
            public void onFinished() {
                applyColorNow();
            }
        });
        systemRows.add(panelGreenRow);
        panelBlueRow = new SliderRow(calibration, "Blue", 50, 150,
                prefs.panelBlue(), 5, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setPanelBlue(value);
                applyColorSoon();
            }

            @Override
            public void onFinished() {
                applyColorNow();
            }
        });
        systemRows.add(panelBlueRow);
        resetCalibrationRow = new ActionRow(calibration, "Reset calibration",
                "100% saturation · 6500 K · neutral RGB · HDR on",
                new Runnable() {
            @Override
            public void run() {
                prefs.resetPanelCalibration();
                panelSaturationRow.setValue(100, false);
                panelTemperatureRow.setValue(6500, false);
                panelRedRow.setValue(100, false);
                panelGreenRow.setValue(100, false);
                panelBlueRow.setValue(100, false);
                panelHdrRow.setOn(true);
                runSystem(new Runnable() {
                    @Override
                    public void run() {
                        DisplayTuner.resetCalibration();
                    }
                }, true);
                toast("Panel calibration reset");
            }
        });
        systemRows.add(resetCalibrationRow);

        LinearLayout pavoSection = section(body, "PAVO DISPLAY",
                "Launcher colors and OLED burn-in protection");
        saturationRow = new SliderRow(pavoSection, "Saturation", 0, 200,
                prefs.colorSaturation(), 5, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setColorSaturation(value);
                ColorTuning.set(prefs.colorSaturation(), prefs.glowStrength());
            }

            @Override
            public void onFinished() {
            }
        });
        glowRow = new SliderRow(pavoSection, "Glow", 0, 200,
                prefs.glowStrength(), 5, new SliderCallback() {
            @Override
            public void onValue(int value) {
                prefs.setGlowStrength(value);
                ColorTuning.set(prefs.colorSaturation(), prefs.glowStrength());
            }

            @Override
            public void onFinished() {
            }
        });
        burnInRow = new ToggleRow(pavoSection, "Burn-in protection",
                prefs.burnInProtection(), new ToggleCallback() {
            @Override
            public void onChanged(boolean value) {
                prefs.setBurnInProtection(value);
            }
        });
        ActionRow resetColours = new ActionRow(pavoSection, "Reset launcher colors",
                "Back to 100% saturation and glow", new Runnable() {
            @Override
            public void run() {
                prefs.setColorSaturation(100);
                prefs.setGlowStrength(100);
                ColorTuning.set(100, 100);
                saturationRow.setValue(100, false);
                glowRow.setValue(100, false);
                toast("Launcher colors reset");
            }
        });
        // Pavo rows are safe without root.

        LinearLayout resetSection = section(body, "RESET", "Return the panel to stock");
        resetDisplayRow = new ActionRow(resetSection, "Reset display",
                "Native size, density, auto refresh and rotation", new Runnable() {
            @Override
            public void run() {
                runSystem(new Runnable() {
                    @Override
                    public void run() {
                        DisplayTuner.resetDisplay();
                    }
                }, true);
                toast("Display reset to stock");
            }
        });
        systemRows.add(resetDisplayRow);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = text("D-PAD move  ·  ◀ ▶ adjust  ·  A select  ·  B close",
                9f, 0x66FFFFFF, false);
        footer.addView(hint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footerStatus = text("PSERVER", 9f, AMBER, true);
        footerStatus.setLetterSpacing(0.08f);
        footerStatus.setPadding(dp(8), dp(4), dp(8), dp(4));
        footer.addView(footerStatus);
        column.addView(footer);

        setContentView(rootView);
        close.requestFocus();
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("DISPLAY", 20f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = text("PANEL  ·  PAVO DISPLAY", 9.5f, MUTED, false);
        subtitle.setLetterSpacing(0.13f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(titles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = text("CHECKING", 9f, AMBER, true);
        status.setLetterSpacing(0.06f);
        status.setPadding(dp(9), dp(5), dp(9), dp(5));
        status.setBackground(Ui.rounded(0x18FFC857, dp(10), 0x44FFC857, dp(1)));
        status.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PerfTuner.Root.resetCheck();
                refresh(0L);
            }
        });
        header.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        close = text("\u00D7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(8), 0, dp(8), 0);
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setFocusableInTouchMode(true);
        close.setDefaultFocusHighlightEnabled(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                finishAnimated();
            }
        });
        close.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                if (focused) Haptics.tick(v);
                v.setBackground(focused
                        ? Ui.rounded(0x00000000, dp(12), 0xE6FFFFFF, dp(1.5f))
                        : new ColorDrawable(0x00000000));
            }
        });
        header.addView(close, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        focusables.add(close);
        return header;
    }

    private LinearLayout section(LinearLayout parent, String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(tight ? 10 : 12), dp(tight ? 5 : 7),
                dp(tight ? 10 : 12), dp(tight ? 5 : 7));
        card.setBackground(Ui.rounded(0x300B0E14, dp(16), 0x2EFFFFFF, dp(1)));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 10f, accent, true);
        t.setLetterSpacing(0.13f);
        titleRow.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(text(subtitle, 9f, MUTED, false));
        card.addView(titleRow);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(card, lp);
        return card;
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private abstract class Row {
        final View view;

        Row(LinearLayout parent, String label) {
            this.view = makeRowView(parent, label);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setDefaultFocusHighlightEnabled(false);
            view.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean focused) {
                    styleRow(v, focused);
                    if (focused) {
                        v.requestRectangleOnScreen(new android.graphics.Rect(
                                0, 0, v.getWidth(), v.getHeight()), false);
                    }
                }
            });
            view.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    v.requestFocus();
                    activate();
                }
            });
            focusables.add(view);
            rows.add(this);
        }

        abstract void left();
        abstract void right();
        abstract void activate();
        abstract void setEnabled(boolean enabled);

        boolean isSlider() {
            return false;
        }

        boolean adjustStick(float x, long[] lastAt) {
            return false;
        }

        void adjustFast(int direction, int multiplier) {
            if (direction > 0) right();
            else left();
        }
    }

    private void styleRow(View v, boolean focused) {
        v.setBackground(focused
                ? Ui.focusRing(this, dp(12))
                : Ui.rounded(0x16FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
    }

    private interface ChoiceCallback {
        void onSelected(int index);
    }

    private final class ChoiceRow extends Row {
        private final TextView[] chips;
        private final String[] options;
        private int selected;
        private final ChoiceCallback callback;

        ChoiceRow(LinearLayout parent, String label, String[] options, int selected,
                  ChoiceCallback callback) {
            super(parent, label);
            this.options = options;
            this.selected = selected;
            this.callback = callback;
            LinearLayout row = (LinearLayout) view;
            LinearLayout chipRow = new LinearLayout(DisplayActivity.this);
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.setGravity(Gravity.CENTER_VERTICAL);
            chips = new TextView[options.length];
            for (int i = 0; i < options.length; i++) {
                final int index = i;
                TextView chip = text(options[i], 9f, 0xCCFFFFFF, true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(7), dp(tight ? 3 : 5), dp(7), dp(tight ? 3 : 5));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        view.requestFocus();
                        select(index, true);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, dp(28), 1f);
                if (i > 0) lp.leftMargin = dp(4);
                chipRow.addView(chip, lp);
                chips[i] = chip;
            }
            row.addView(chipRow, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            styleSelection();
        }

        @Override
        void left() {
            cycle(-1);
        }

        @Override
        void right() {
            cycle(1);
        }

        @Override
        void activate() {
            cycle(1);
        }

        private void cycle(int dir) {
            if (options.length == 0) return;
            select((selected + (dir >= 0 ? 1 : -1) + options.length) % options.length, true);
        }

        void select(int index, boolean notify) {
            selected = Math.max(0, Math.min(options.length - 1, index));
            styleSelection();
            if (notify && callback != null) callback.onSelected(selected);
        }

        void selectSilently(int index) {
            selected = Math.max(0, Math.min(options.length - 1, index));
            styleSelection();
        }

        private void styleSelection() {
            for (int i = 0; i < chips.length; i++) {
                boolean on = i == selected;
                chips[i].setTextColor(on ? 0xFF061015 : 0xCCFFFFFF);
                chips[i].setBackground(Ui.rounded(on ? accent : 0x18FFFFFF,
                        dp(8), on ? 0xFFFFFFFF : 0x22FFFFFF, dp(1)));
            }
        }

        @Override
        void setEnabled(boolean enabled) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.4f);
            for (TextView chip : chips) chip.setEnabled(enabled);
        }
    }

    private interface ToggleCallback {
        void onChanged(boolean value);
    }

    private final class ToggleRow extends Row {
        private final TextView chip;
        private final ToggleCallback callback;
        private boolean on;

        ToggleRow(LinearLayout parent, String label, boolean on, ToggleCallback callback) {
            super(parent, label);
            this.on = on;
            this.callback = callback;
            chip = text(on ? "ON" : "OFF", 9f, 0xFF061015, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(5), dp(8), dp(5));
            ((LinearLayout) view).addView(chip, new LinearLayout.LayoutParams(
                    dp(72), dp(28)));
            style();
        }

        @Override
        void left() {
            toggle();
        }

        @Override
        void right() {
            toggle();
        }

        @Override
        void activate() {
            toggle();
        }

        private void toggle() {
            on = !on;
            style();
            if (callback != null) callback.onChanged(on);
        }

        void setOn(boolean value) {
            on = value;
            style();
        }

        private void style() {
            chip.setText(on ? "ON" : "OFF");
            chip.setTextColor(on ? 0xFF061015 : 0xCCFFFFFF);
            chip.setBackground(Ui.rounded(on ? accent : 0x18FFFFFF, dp(8),
                    on ? 0xFFFFFFFF : 0x22FFFFFF, dp(1)));
        }

        @Override
        void setEnabled(boolean enabled) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.4f);
            chip.setEnabled(enabled);
        }
    }

    private interface SliderCallback {
        void onValue(int value);
        void onFinished();
    }

    private final class SliderRow extends Row {
        private final SeekBar bar;
        private final TextView value;
        private final SliderCallback callback;
        private final int min;
        private final int max;
        private final int step;

        SliderRow(LinearLayout parent, String label, int min, int max,
                  int initial, int step, SliderCallback callback) {
            super(parent, label);
            this.min = min;
            this.max = max;
            this.step = Math.max(1, step);
            this.callback = callback;
            LinearLayout row = (LinearLayout) view;
            bar = new SeekBar(DisplayActivity.this);
            bar.setMax(Math.max(0, (max - min) / this.step));
            bar.setProgress(clamp((initial - min) / this.step));
            bar.setFocusable(false);
            bar.setSplitTrack(false);
            bar.setPadding(dp(9), 0, dp(9), 0);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
            bar.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int valueNow = min + progress * SliderRow.this.step;
                    value.setText(label(valueNow));
                    if (fromUser && !updating && SliderRow.this.callback != null) {
                        SliderRow.this.callback.onValue(valueNow);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (SliderRow.this.callback != null) SliderRow.this.callback.onFinished();
                }
            });
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(30), 1f));
            value = text(label(initial), 9.5f, accent, true);
            value.setGravity(Gravity.END);
            value.setMinWidth(dp(72));
            row.addView(value);
        }

        @Override
        void left() {
            adjust(-step);
        }

        @Override
        void right() {
            adjust(step);
        }

        @Override
        void activate() {
        }

        @Override
        boolean isSlider() {
            return true;
        }

        @Override
        boolean adjustStick(float x, long[] lastAt) {
            return SliderStick.adjust(bar, x, lastAt, view);
        }

        @Override
        void adjustFast(int direction, int multiplier) {
            adjust((direction > 0 ? step : -step) * Math.max(1, multiplier));
        }

        private void adjust(int delta) {
            int current = min + bar.getProgress() * step;
            int next = Math.max(min, Math.min(max, current + delta));
            bar.setProgress(clamp((next - min) / step));
            if (callback != null) callback.onValue(next);
        }

        void setValue(int valueNow, boolean notify) {
            updating = true;
            bar.setProgress(clamp((valueNow - min) / step));
            value.setText(label(valueNow));
            updating = false;
            if (notify && callback != null) callback.onValue(valueNow);
        }

        private String label(int valueNow) {
            if (min == 2500) return valueNow + " K";
            return valueNow + "%";
        }

        @Override
        void setEnabled(boolean enabled) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.4f);
            bar.setEnabled(enabled);
        }
    }

    private final class ActionRow extends Row {
        private final Runnable action;

        ActionRow(LinearLayout parent, String label, String detail, Runnable action) {
            super(parent, label);
            this.action = action;
            LinearLayout row = (LinearLayout) view;
            TextView d = text(detail, 9f, MUTED, false);
            d.setGravity(Gravity.END);
            row.addView(d);
        }

        @Override
        void left() {
            activate();
        }

        @Override
        void right() {
            activate();
        }

        @Override
        void activate() {
            Haptics.click(view);
            if (action != null) action.run();
        }

        @Override
        void setEnabled(boolean enabled) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : 0.4f);
        }
    }

    private LinearLayout makeRowView(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(tight ? 2 : 4), dp(8), dp(tight ? 2 : 4));
        row.setMinimumHeight(dp(tight ? 34 : 42));
        TextView name = text(label, tight ? 10.5f : 11f, 0xE6FFFFFF, false);
        row.addView(name, new LinearLayout.LayoutParams(dp(tight ? 112 : 126),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        parent.addView(row, lp);
        return row;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private void refresh(long delay) {
        if (destroyed) return;
        handler.removeCallbacks(refreshTick);
        handler.postDelayed(refreshTick, Math.max(0L, delay));
    }

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = DisplayTuner.available();
                    if (ok && !hdrSynced) {
                        int hdr = DisplayTuner.readHdrState();
                        if (hdr >= 0) prefs.setPanelHdrEnabled(hdr == 0);
                        hdrSynced = true;
                    }
                    if (ok && !calibrationApplied) {
                        DisplayTuner.applyCalibration(prefs);
                        calibrationApplied = true;
                    }
                    final DisplayTuner.Snapshot snapshot = DisplayTuner.read(DisplayActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            root = ok;
                            state = snapshot;
                            bind(snapshot);
                        }
                    });
                }
            });
        }
    };

    private static int resolutionHeight(int index) {
        switch (index) {
            case 1: return 1200;
            case 2: return 1080;
            case 3: return 900;
            case 4: return 720;
            default: return 0;
        }
    }

    private void applyResolution() {
        if (selectedHeight <= 0) {
            runSystem(new Runnable() {
                @Override
                public void run() {
                    DisplayTuner.resetResolution();
                }
            }, true);
            return;
        }
        applyAspectResolution();
    }

    private void applyAspectResolution() {
        final int height = selectedHeight <= 0 ? 1080 : selectedHeight;
        int aspect = selectedAspect <= 0 ? 1 : selectedAspect;
        float ratio;
        switch (aspect) {
            case 2: ratio = 16f / 10f; break;
            case 3: ratio = 3f / 2f; break;
            case 4: ratio = 4f / 3f; break;
            case 5: ratio = 21f / 9f; break;
            default: ratio = 16f / 9f; break;
        }
        final int width = Math.max(height + 1, Math.round(height * ratio));
        runSystem(new Runnable() {
            @Override
            public void run() {
                DisplayTuner.setResolution(width, height);
            }
        }, true);
    }

    private void toastAfterResolution() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                toast("Resolution changed · some apps may restart");
            }
        }, 250L);
    }

    private void bind(DisplayTuner.Snapshot s) {
        if (s == null) return;
        updating = true;
        info.setText(s.width + " × " + s.height + "  ·  " + s.densityDpi
                + " dpi  ·  " + DisplayTuner.refreshLabel(s.refresh)
                + "  ·  " + (prefs.panelHdrEnabled() ? s.hdr : "HDR OFF"));
        status.setText(root ? "PSERVER CONNECTED" : "PSERVER REQUIRED");
        status.setTextColor(root ? GREEN : RED);
        status.setBackground(Ui.rounded(root ? 0x1816C784 : 0x22FF5C6C,
                dp(10), root ? 0x4469D98B : 0x66FF8A80, dp(1)));

        int refreshIndex = 0;
        if (s.minRefresh >= 120) refreshIndex = 2;
        else if (s.minRefresh >= 60 && s.peakRefresh <= 60) refreshIndex = 1;
        refreshRow.selectSilently(refreshIndex);

        int shortSide = Math.min(s.width, s.height);
        int longSide = Math.max(s.width, s.height);
        int res;
        if (s.width == s.nativeWidth && s.height == s.nativeHeight) {
            res = 0;
        } else if (shortSide == 1200) {
            res = 1;
        } else if (shortSide == 1080) {
            res = 2;
        } else if (shortSide == 900) {
            res = 3;
        } else if (shortSide == 720) {
            res = 4;
        } else {
            res = 0;
        }
        resolutionRow.selectSilently(res);
        selectedHeight = resolutionHeight(res);
        if (res == 0) selectedHeight = 0;

        int aspect = 0;
        if (!(s.width == s.nativeWidth && s.height == s.nativeHeight)) {
            float ratio = longSide / (float) Math.max(1, shortSide);
            if (Math.abs(ratio - 16f / 9f) < 0.035f) aspect = 1;
            else if (Math.abs(ratio - 16f / 10f) < 0.035f) aspect = 2;
            else if (Math.abs(ratio - 3f / 2f) < 0.035f) aspect = 3;
            else if (Math.abs(ratio - 4f / 3f) < 0.035f) aspect = 4;
            else if (Math.abs(ratio - 21f / 9f) < 0.045f) aspect = 5;
        }
        selectedAspect = aspect;
        aspectRow.selectSilently(aspect);

        boolean portrait;
        if (s.naturalLandscape) portrait = s.rotation == 1 || s.rotation == 3;
        else portrait = s.rotation == 0 || s.rotation == 2;
        int orientation = s.autoRotate ? 0 : (portrait ? 2 : 1);
        orientationRow.selectSilently(orientation);

        int dens = 0;
        if (s.densityDpi == 320) dens = 1;
        else if (s.densityDpi == 360) dens = 2;
        else if (s.densityDpi == 420) dens = 3;
        else if (s.densityDpi == 480) dens = 4;
        densityRow.selectSilently(dens);

        panelSaturationRow.setValue(prefs.panelSaturation(), false);
        panelTemperatureRow.setValue(prefs.panelTemperature(), false);
        panelRedRow.setValue(prefs.panelRed(), false);
        panelGreenRow.setValue(prefs.panelGreen(), false);
        panelBlueRow.setValue(prefs.panelBlue(), false);
        panelHdrRow.setOn(prefs.panelHdrEnabled());
        saturationRow.setValue(prefs.colorSaturation(), false);
        glowRow.setValue(prefs.glowStrength(), false);
        burnInRow.setOn(prefs.burnInProtection());
        updating = false;
        setSystemEnabled(root);
        if (footerStatus != null) {
            footerStatus.setText(root ? "PSERVER CONNECTED" : "PSERVER REQUIRED");
            footerStatus.setTextColor(root ? GREEN : RED);
        }
        if (!initialFocus) {
            initialFocus = true;
            refreshRow.view.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) refreshRow.view.requestFocus();
                }
            });
        }
    }

    private void setSystemEnabled(boolean enabled) {
        for (Row row : systemRows) row.setEnabled(enabled);
    }

    private void runSystem(Runnable action, boolean refreshSlowly) {
        if (action == null || !root) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                action.run();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refresh(refreshSlowly ? 900L : 500L);
                    }
                });
            }
        });
    }

    private final Runnable panelColorTask = new Runnable() {
        @Override
        public void run() {
            applyColorNow();
        }
    };

    /** Coalesce the rapid stream of D-pad slider steps into one root write. */
    private void applyColorSoon() {
        handler.removeCallbacks(panelColorTask);
        handler.postDelayed(panelColorTask, 45L);
    }

    private void applyColorNow() {
        handler.removeCallbacks(panelColorTask);
        if (destroyed || !root) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                DisplayTuner.applyPanelColor(prefs);
            }
        });
    }

    // ------------------------------------------------------------------
    // Controller
    // ------------------------------------------------------------------

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (SliderStick.isGamepad(event)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            Row row = rowFor(getCurrentFocus());
            if (row != null && row.isSlider()) {
                float rx = SliderStick.rightX(event);
                if (SliderStick.active(rx) && row.adjustStick(rx, sliderStickAt)) {
                    return true;
                }
            }
            float ry = StickScroll.rightY(event);
            if (StickScroll.active(ry)
                    && StickScroll.scroll(scrollView, ry, scrollStickAt)) return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = down && event.getRepeatCount() > 0;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (down && !repeat) finishAnimated();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && !repeat) {
                if (getCurrentFocus() == close) finishAnimated();
                else {
                    Row row = rowFor(getCurrentFocus());
                    if (row != null) row.activate();
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) {
                Row row = rowFor(getCurrentFocus());
                if (row != null) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (row.isSlider()) {
                        return true;
                    } else if (!repeat) {
                        if (dir > 0) row.right();
                        else row.left();
                        Haptics.tick(getCurrentFocus());
                    }
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!down) return true;
            long now = android.os.SystemClock.uptimeMillis();
            if (repeat && now - lastNavAt < 65L) return true;
            lastNavAt = now;
            moveFocus(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private Row rowFor(View v) {
        for (Row row : rows) if (row.view == v) return row;
        return null;
    }

    private void moveFocus(int delta) {
        View focused = getCurrentFocus();
        int index = focusables.indexOf(focused);
        if (index < 0) index = 0;
        int next = index;
        for (int i = 0; i < focusables.size(); i++) {
            next = Math.max(0, Math.min(focusables.size() - 1, next + delta));
            View candidate = focusables.get(next);
            if (candidate.isEnabled()) {
                candidate.requestFocus();
                Haptics.tick(candidate);
                return;
            }
            if (next == 0 || next == focusables.size() - 1) break;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private void toast(String message) {
        LToast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int clamp(int value) {
        return Math.max(0, value);
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
