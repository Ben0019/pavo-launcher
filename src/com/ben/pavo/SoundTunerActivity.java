package com.ben.pavo;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Built-in Pavo app: a system-wide five-band equalizer for every app. */
public final class SoundTunerActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;

    private static final String[] PRESET_NAMES = {
            "PAVO", "GAME", "FLAT",
            "ACOUSTIC", "BASS", "BASS CUT", "CLASSICAL", "DANCE", "DEEP",
            "ELECTRONIC", "HIP HOP", "JAZZ", "LATIN", "LOUDNESS", "LOUNGE",
            "PIANO", "POP", "R&B", "ROCK", "SMALL", "SPOKEN", "TREBLE",
            "TREBLE CUT", "VOCAL", "WARM", "BRIGHT", "FPS"
    };
    private static final int[][] PRESET_LEVELS = {
            // Device-tuned.
            {150, -250, 0, -150, -150},   // PAVO
            {350, 100, 0, 350, 100},      // GAME
            {0, 0, 0, 0, 0},              // FLAT
            // Apple/iTunes curves, folded from their 10 bands to ours.
            {500, 150, 200, 400, 250},    // ACOUSTIC
            {450, 250, 0, 0, 0},          // BASS
            {-450, -250, 0, 0, 0},        // BASS CUT
            {400, 250, -150, 200, 350},   // CLASSICAL
            {650, 50, 350, 450, 50},      // DANCE
            {350, 100, 250, -150, -450},  // DEEP
            {400, 0, 150, 100, 450},      // ELECTRONIC
            {450, 300, -100, 0, 300},     // HIP HOP
            {300, 200, -150, 150, 350},   // JAZZ
            {300, 0, -150, 0, 400},       // LATIN
            {400, 0, -50, -450, 200},     // LOUDNESS
            {-150, 150, 250, -150, 100},  // LOUNGE
            {200, 200, 150, 450, 350},    // PIANO
            {-100, 200, 400, 50, -150},   // POP
            {650, 200, -150, 250, 350},   // R&B
            {400, 150, -100, 200, 450},   // ROCK
            {450, 250, 0, -250, -400},    // SMALL
            {-100, 50, 450, 450, 50},     // SPOKEN
            {0, 0, 100, 350, 550},        // TREBLE
            {0, 0, -100, -350, -550},     // TREBLE CUT
            {-300, 100, 400, 150, -100},  // VOCAL
            {300, 200, 100, 0, -100},     // WARM
            {-100, 0, 100, 300, 500},     // BRIGHT
            {-400, -300, 200, 500, 100}   // FPS
    };

    private Prefs prefs;
    private int accent;
    private ControllerNav nav;
    private boolean locked;
    private TextView lockChip;

    private TextView enableChip;
    private TextView status;
    private final List<EqBandSlider> sliders = new ArrayList<>();
    private final List<TextView> values = new ArrayList<>();
    private final List<TextView> presetChips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        locked = prefs.eqLocked();
        ensurePavoDefault();
        buildUi();
        hideBars();
        installController();
        AudioEq.apply(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        hidePerformanceOverlay();
        AudioEq.apply(this);
        refresh();
    }

    @Override
    protected void onPause() {
        if (nav != null) nav.cancel();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (nav != null) nav.release();
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

    private void installController() {
        nav = new ControllerNav(this, getWindow().getDecorView());
        nav.decorateFocusables();
        if (enableChip == null) return;
        enableChip.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    enableChip.requestFocus();
                    nav.decorateFocusables();
                }
            }
        }, 180L);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B)
                && down && event.getRepeatCount() == 0) {
            finishAnimated();
            return true;
        }
        View focus = getCurrentFocus();
        if (focus instanceof EqBandSlider
                && (code == KeyEvent.KEYCODE_DPAD_LEFT
                        || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (!locked && down) {
                ((EqBandSlider) focus).nudge(
                        code == KeyEvent.KEYCODE_DPAD_RIGHT ? 50 : -50);
                Haptics.tick(focus);
            }
            return true;
        }
        if (nav != null && nav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (nav != null && nav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        boolean tight = Ui.widthDp(this) < 700;
        card.setPadding(dp(tight ? 16 : 22), dp(tight ? 8 : 12),
                dp(tight ? 16 : 22), dp(tight ? 7 : 10));
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

        status = text("", 9f, MUTED, false);
        status.setPadding(dp(3), dp(6), dp(3), dp(4));
        column.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(8));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView bandLabel = sectionLabel(Loc.s(this, "BANDS"));
        body.addView(bandLabel);

        int count = Math.min(Prefs.EQ_BANDS, Math.max(1, AudioEq.bandCount(this)));
        int min = AudioEq.minLevel(this);
        int max = AudioEq.maxLevel(this);
        addVerticalBands(body, count, min, max);

        TextView presetsLabel = sectionLabel(Loc.s(this, "PRESETS"));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        body.addView(presetsLabel, plp);
        addPresetRows(body);

        updateLockChip();
        applyLockState();
        setContentView(root);
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(Loc.s(this, "Sound Tuner").toUpperCase(Locale.getDefault()),
                20f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = text(Loc.s(this, "SYSTEM-WIDE EQUALIZER"), 9.5f, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(34);
        tlp.rightMargin = dp(34);
        header.addView(titles, tlp);

        TextView close = text("\u00d7", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(true);
        close.setPadding(dp(7), dp(4), dp(7), dp(4));
        close.setMinWidth(dp(36));
        close.setMinHeight(dp(36));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAnimated();
            }
        });
        enableChip = text("", 9.5f, TEXT, true);
        enableChip.setGravity(Gravity.CENTER);
        enableChip.setSingleLine(true);
        enableChip.setMinWidth(dp(64));
        enableChip.setPadding(dp(9), dp(6), dp(9), dp(6));
        enableChip.setFocusable(true);
        enableChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                AudioEq.setEnabled(SoundTunerActivity.this,
                        !AudioEq.enabled(SoundTunerActivity.this));
                refresh();
            }
        });
        enableChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateEnableChip();
            }
        });

        lockChip = text("LOCK", 8.5f, MUTED, true);
        lockChip.setGravity(Gravity.CENTER);
        lockChip.setPadding(dp(9), dp(6), dp(9), dp(6));
        lockChip.setFocusable(true);
        lockChip.setDefaultFocusHighlightEnabled(false);
        lockChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                toggleLock();
            }
        });
        lockChip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                updateLockChip();
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(enableChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lockLp.leftMargin = dp(6);
        actions.addView(lockChip, lockLp);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(4);
        actions.addView(close, closeLp);

        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.gravity = Gravity.TOP | Gravity.END;
        alp.topMargin = dp(2);
        header.addView(actions, alp);
        return header;
    }

    private TextView sectionLabel(String value) {
        TextView v = text(value, 8.5f, MUTED, true);
        v.setLetterSpacing(0.14f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(dp(3), dp(4), dp(3), dp(4));
        return v;
    }

    private void addVerticalBands(LinearLayout body, int count, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        for (int i = 0; i < count; i++) {
            final int index = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(dp(1), 0, dp(1), 0);

            int level = prefs.eqBand(index);
            TextView value = text(formatDb(level), 8.5f, accent, true);
            value.setGravity(Gravity.CENTER);
            value.setSingleLine(true);
            col.addView(value, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            EqBandSlider slider = new EqBandSlider(this);
            slider.setRange(min, max);
            slider.setLevel(level);
            slider.setAccent(accent);
            slider.setListener(new EqBandSlider.Listener() {
                @Override public void onLevel(int millibels, boolean released) {
                    applyCurveLevel(index, millibels);
                }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
            slp.topMargin = dp(4);
            slp.bottomMargin = dp(3);
            col.addView(slider, slp);

            int hz = AudioEq.centerHz(this, index);
            if (hz <= 0) hz = new int[]{31, 62, 125, 250, 500,
                    1000, 2000, 4000, 8000, 16000}[index % 10];
            TextView freq = text(formatHz(hz), 8f, MUTED, true);
            freq.setGravity(Gravity.CENTER);
            freq.setSingleLine(true);
            col.addView(freq, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            sliders.add(slider);
            values.add(value);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(2);
        body.addView(row, rlp);
    }

    private void addPresetRows(LinearLayout body) {
        final int columns = 9;
        LinearLayout row = null;
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(3);
                body.addView(row, rlp);
            }
            final int preset = i;
            TextView chip = text(Loc.s(this, PRESET_NAMES[i]), 8f, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setFocusable(true);
            chip.setDefaultFocusHighlightEnabled(false);
            chip.setPadding(dp(2), dp(4), dp(2), dp(4));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    AudioEq.setBands(SoundTunerActivity.this, presetLevels(preset));
                    AudioEq.setEnabled(SoundTunerActivity.this, true);
                    refresh();
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        v.setBackground(Ui.rounded(0x26FFFFFF, dp(9), accent, dp(1)));
                    } else {
                        updatePresetSelection();
                    }
                }
            });
            if (preset == 0) {
                chip.setLongClickable(true);
                chip.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        Haptics.longPress(v);
                        prefs.setEqPavoDefault(currentLevels());
                        updatePresetSelection();
                        android.widget.Toast.makeText(SoundTunerActivity.this,
                                "PAVO default saved", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(24), 1f);
            lp.rightMargin = (i % columns != columns - 1
                    && i != PRESET_NAMES.length - 1) ? dp(3) : 0;
            row.addView(chip, lp);
            presetChips.add(chip);
        }
    }

    private void refresh() {
        if (enableChip == null) return;
        boolean available = AudioEq.available(this);
        boolean on = available && AudioEq.enabled(this);
        enableChip.setText("EQ " + Loc.s(this, on ? "ON" : "OFF"));
        enableChip.setTextColor(on ? 0xFF071018 : TEXT);
        enableChip.setBackground(Ui.rounded(on ? accent : 0x1AFFFFFF, dp(12),
                on ? accent : 0x33FFFFFF, dp(1)));
        status.setText(Loc.s(this, available
                ? "Applies to every app. Uses the global audio session."
                : "Equalizer unavailable on this device."));
        status.setTextColor(available ? MUTED : 0xFFFF8A80);

        int count = Math.min(sliders.size(), AudioEq.bandCount(this));
        for (int i = 0; i < count; i++) {
            int level = prefs.eqBand(i);
            sliders.get(i).setLevel(level);
            values.get(i).setText(formatDb(level));
        }
        updatePresetSelection();
        for (EqBandSlider slider : sliders) slider.setEnabled(available && !locked);
        applyLockState();
    }

    private void applyCurveLevel(int index, int level) {
        if (index < 0 || index >= sliders.size()) return;
        int min = AudioEq.minLevel(this);
        int max = AudioEq.maxLevel(this);
        level = Math.max(min, Math.min(max, level));
        AudioEq.setBand(this, index, level);
        sliders.get(index).setLevel(level);
        values.get(index).setText(formatDb(level));
        updatePresetSelection();
    }

    private void toggleLock() {
        locked = !locked;
        prefs.setEqLocked(locked);
        applyLockState();
        updateLockChip();
    }

    private void applyLockState() {
        for (EqBandSlider slider : sliders) {
            slider.setAlpha(locked ? 0.5f : 1f);
            slider.setEnabled(!locked && AudioEq.available(this));
        }
        for (TextView value : values) {
            value.setAlpha(locked ? 0.6f : 1f);
        }
    }

    private void updateEnableChip() {
        if (enableChip == null) return;
        boolean on = AudioEq.enabled(this);
        boolean focused = enableChip.hasFocus();
        enableChip.setText("EQ " + Loc.s(this, on ? "ON" : "OFF"));
        enableChip.setTextColor(on ? 0xFF071018 : TEXT);
        if (on) {
            enableChip.setBackground(focused
                    ? Ui.rounded(accent, dp(12), 0xFFFFFFFF, dp(1.5f))
                    : Ui.rounded(accent, dp(12)));
        } else {
            enableChip.setBackground(focused
                    ? Ui.rounded(0x1AFFFFFF, dp(12), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1AFFFFFF, dp(12), 0x33FFFFFF, dp(1)));
        }
    }

    private void updateLockChip() {
        if (lockChip == null) return;
        boolean focused = lockChip.hasFocus();
        lockChip.setText(locked ? "LOCKED" : "LOCK");
        lockChip.setTextColor(locked ? 0xFFFFC857 : MUTED);
        if (locked) {
            lockChip.setBackground(focused
                    ? Ui.rounded(0x26FFC857, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFC857, dp(9), 0x44FFC857, dp(1)));
        } else {
            lockChip.setBackground(focused
                    ? Ui.rounded(0x1EFFFFFF, dp(9), 0xE6FFFFFF, dp(1.5f))
                    : Ui.rounded(0x1EFFFFFF, dp(9), 0x33FFFFFF, dp(1)));
        }
    }

    /** Freezes the current curve as PAVO on upgrade; otherwise seeds the standard one. */
    private void ensurePavoDefault() {
        if (prefs.eqPavoDefault() != null) return;
        int count = AudioEq.bandCount(this);
        int[] current = new int[count];
        boolean any = false;
        for (int i = 0; i < count; i++) {
            current[i] = prefs.eqBand(i);
            if (current[i] != 0) any = true;
        }
        prefs.setEqPavoDefault(any ? current : AudioEq.expandFive(PRESET_LEVELS[0]));
    }

    /** Saved PAVO curve when present, otherwise the expanded built-in curve. */
    private int[] presetLevels(int index) {
        if (index == 0) {
            int[] saved = prefs.eqPavoDefault();
            if (saved != null && saved.length == AudioEq.bandCount(this)) {
                return saved;
            }
        }
        return AudioEq.expandFive(PRESET_LEVELS[index]);
    }

    private int[] currentLevels() {
        int count = sliders.size();
        int[] levels = new int[count];
        for (int i = 0; i < count; i++) levels[i] = prefs.eqBand(i);
        return levels;
    }

    private void updatePresetSelection() {
        int[] levels = currentLevels();
        for (int i = 0; i < presetChips.size(); i++) {
            boolean selected = matches(levels, presetLevels(i));
            TextView chip = presetChips.get(i);
            if (chip.hasFocus() && selected) {
                chip.setBackground(Ui.rounded(accent, dp(10), accent, dp(1)));
                chip.setTextColor(0xFF071018);
            } else if (selected && AudioEq.enabled(this)) {
                chip.setBackground(Ui.rounded(0x33FFFFFF, dp(10), 0x66FFFFFF, dp(1)));
                chip.setTextColor(TEXT);
            } else {
                chip.setBackground(Ui.rounded(0x18FFFFFF, dp(10), 0x22FFFFFF, dp(1)));
                chip.setTextColor(TEXT);
            }
        }
    }

    private static boolean matches(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (Math.abs(a[i] - b[i]) > 60) return false;
        }
        return true;
    }

    private static String formatDb(int millibels) {
        return String.format(Locale.US, "%+.1f dB", millibels / 100f);
    }

    private static String formatHz(int hz) {
        if (hz >= 1000) return String.format(Locale.US, "%.1f kHz", hz / 1000f);
        return hz + " Hz";
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create("sans-serif", Typeface.NORMAL));
        return v;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

}
