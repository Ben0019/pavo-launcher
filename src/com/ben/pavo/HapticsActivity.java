package com.ben.pavo;

import android.graphics.Color;
import android.graphics.Typeface;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Built-in Pavo app: system-wide haptic strength, previews and switches. */
public final class HapticsActivity extends PavoGlassActivity {
    private static final String TOUCH_KEY = "haptic_feedback_intensity";
    private static final String NOTIFY_KEY = "notification_vibration_intensity";
    private static final String ALARM_KEY = "alarm_vibration_intensity";
    private static final String RING_KEY = "ringtone_vibration_intensity";

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private final List<View> focusables = new ArrayList<>();
    private final long[] sliderStickAt = {0L};
    private final long[] scrollStickAt = {0L};

    private Prefs prefs;
    private int accent = 0xFFA9BEFF;
    private boolean tight;
    private boolean updating;
    private boolean destroyed;
    private boolean initialFocus;
    private long lastNavAt;

    private ScrollView scrollView;
    private TextView status;
    private IntensityRow touchRow;
    private IntensityRow notifyRow;
    private IntensityRow alarmRow;
    private IntensityRow ringRow;
    private ToggleRow hapticSwitch;
    private ToggleRow ringSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        tight = Ui.widthDp(this) < 700;
        buildUi();
        hideBars();
        readSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        hidePerformanceOverlay();
        readSettings();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        PerfTuner.Root.close();
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

    // ------------------------------------------------------------------ UI

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

        LinearLayout strength = section(body, "STRENGTH",
                "Applies to every app. Range is 0.8x to 1.2x.");
        touchRow = new IntensityRow(strength, "Touch feedback", TOUCH_KEY);
        notifyRow = new IntensityRow(strength, "Notifications", NOTIFY_KEY);
        alarmRow = new IntensityRow(strength, "Alarms", ALARM_KEY);
        ringRow = new IntensityRow(strength, "Ringtones", RING_KEY);

        LinearLayout test = section(body, "TEST", "Uses the current touch strength.");
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setPadding(0, dp(4), 0, dp(2));
        test.addView(testRow);
        addPreview(testRow, "TICK", 12, 170);
        addPreview(testRow, "CLICK", 28, 255);
        addPreview(testRow, "THUD", 45, 255);
        addPreview(testRow, "LONG", 120, 210);

        LinearLayout system = section(body, "SYSTEM", null);
        hapticSwitch = new ToggleRow(system, "Haptic feedback", "haptic_feedback_enabled");
        ringSwitch = new ToggleRow(system, "Vibrate when ringing", "vibrate_when_ringing");

        LinearLayout resetSection = section(body, "RESET", null);
        new ActionRow(resetSection, "Restore default strength", new Runnable() {
            @Override
            public void run() {
                deleteSetting(TOUCH_KEY);
                deleteSetting(NOTIFY_KEY);
                deleteSetting(ALARM_KEY);
                deleteSetting(RING_KEY);
                toast("Haptic strength reset");
                readSettings();
            }
        });

        status = text("", 9f, GREEN, true);
        status.setPadding(dp(4), dp(6), dp(4), 0);
        body.addView(status);

        setContentView(rootView);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("HAPTICS", tight ? 18f : 22f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.06f);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u2715", 12f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setFocusable(true);
        close.setFocusableInTouchMode(true);
        close.setDefaultFocusHighlightEnabled(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAnimated();
            }
        });
        close.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused ? Ui.focusRing(HapticsActivity.this, dp(10))
                        : Ui.rounded(0x16FFFFFF, dp(10), 0x22FFFFFF, dp(1)));
            }
        });
        close.setBackground(Ui.rounded(0x16FFFFFF, dp(10), 0x22FFFFFF, dp(1)));
        close.setId(View.generateViewId());
        top.addView(close);
        focusables.add(close);
        header.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = text("SYSTEM WIDE", 8.5f, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.1f);
        sub.setPadding(0, dp(2), 0, dp(2));
        header.addView(sub);
        return header;
    }

    private void addPreview(LinearLayout parent, String label, final int ms, final int amp) {
        TextView chip = text(label, 9f, 0xCCFFFFFF, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(4), dp(9), dp(4), dp(9));
        chip.setBackground(Ui.rounded(0x16FFFFFF, dp(11), 0x22FFFFFF, dp(1)));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setDefaultFocusHighlightEnabled(false);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.requestFocus();
                playPreview(ms, amp);
            }
        });
        chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused ? Ui.focusRing(HapticsActivity.this, dp(11))
                        : Ui.rounded(0x16FFFFFF, dp(11), 0x22FFFFFF, dp(1)));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(5);
        parent.addView(chip, lp);
        focusables.add(chip);
    }

    private LinearLayout section(LinearLayout parent, String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(tight ? 10 : 12), dp(tight ? 5 : 7),
                dp(tight ? 10 : 12), dp(tight ? 5 : 7));
        card.setBackground(Ui.rounded(0x300B0E14, dp(16), 0x2EFFFFFF, dp(1)));
        TextView t = text(title, 10f, accent, true);
        t.setLetterSpacing(0.13f);
        t.setPadding(dp(2), 0, dp(2), 0);
        card.addView(t);
        if (subtitle != null && !tight) {
            TextView s = text(subtitle, 8.8f, MUTED, false);
            s.setPadding(dp(2), dp(1), dp(2), dp(3));
            card.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        parent.addView(card, lp);
        return card;
    }

    // ------------------------------------------------------------------ rows

    private abstract class Row {
        final View view;

        Row(LinearLayout parent, String label) {
            view = makeRowView(parent, label);
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setDefaultFocusHighlightEnabled(false);
            view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean focused) {
                    styleRows(v, focused);
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

        boolean isSlider() {
            return false;
        }

        boolean adjustStick(float x, long[] lastAt) {
            return false;
        }
    }

    private View makeRowView(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(9), dp(3), dp(9), dp(3));
        row.setBackground(Ui.rounded(0x16FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
        TextView name = text(label, 11f, TEXT, false);
        name.setPadding(dp(2), 0, dp(6), 0);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        parent.addView(row, lp);
        return row;
    }

    private void styleRows(View v, boolean focused) {
        v.setBackground(focused ? Ui.focusRing(this, dp(12))
                : Ui.rounded(0x16FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
    }

    private final class IntensityRow extends Row {
        private final SeekBar bar;
        private final TextView value;
        private final String key;

        IntensityRow(LinearLayout parent, String label, String key) {
            super(parent, label);
            this.key = key;
            LinearLayout row = (LinearLayout) view;
            bar = new SeekBar(HapticsActivity.this);
            bar.setMax(2);
            bar.setProgress(1);
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
                    value.setText(levelLabel(progress + 1));
                    if (fromUser && !updating) writeSetting(key, progress + 1);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            row.addView(bar, new LinearLayout.LayoutParams(0, dp(30), 1f));
            value = text(levelLabel(2), 9.5f, accent, true);
            value.setGravity(Gravity.END);
            value.setMinWidth(dp(72));
            row.addView(value);
        }

        @Override
        void left() {
            setValue(bar.getProgress());
        }

        @Override
        void right() {
            setValue(bar.getProgress() + 2);
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

        void setValue(int level) {
            int next = Math.max(1, Math.min(3, level));
            if (bar.getProgress() == next - 1) return;
            bar.setProgress(next - 1);
            writeSetting(key, next);
            Haptics.tick(view);
        }

        void setLevel(int level) {
            updating = true;
            bar.setProgress(Math.max(0, Math.min(2, level - 1)));
            value.setText(levelLabel(level));
            updating = false;
        }
    }

    private final class ToggleRow extends Row {
        private final TextView value;
        private final String key;

        ToggleRow(LinearLayout parent, String label, String key) {
            super(parent, label);
            this.key = key;
            LinearLayout row = (LinearLayout) view;
            value = text("ON", 9.5f, accent, true);
            value.setGravity(Gravity.END);
            value.setMinWidth(dp(48));
            row.addView(value);
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
            boolean next = !"ON".equals(value.getText().toString());
            writeSetting(key, next ? 1 : 0);
            setOn(next);
            Haptics.tick(view);
        }

        void setOn(boolean on) {
            value.setText(on ? "ON" : "OFF");
            value.setTextColor(on ? accent : MUTED);
        }
    }

    private final class ActionRow extends Row {
        private final Runnable action;

        ActionRow(LinearLayout parent, String label, Runnable action) {
            super(parent, label);
            this.action = action;
            LinearLayout row = (LinearLayout) view;
            TextView go = text("RUN", 9.5f, accent, true);
            go.setGravity(Gravity.END);
            row.addView(go);
        }

        @Override
        void left() {
            action.run();
        }

        @Override
        void right() {
            action.run();
        }

        @Override
        void activate() {
            action.run();
        }
    }

    // ------------------------------------------------------------------ shell

    private void writeSetting(final String key, final int value) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PerfTuner.Root.writeOk("settings put system " + key + " " + value);
            }
        }, "pavo-haptics-write").start();
    }

    private void deleteSetting(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PerfTuner.Root.writeOk("settings delete system " + key);
            }
        }, "pavo-haptics-reset").start();
    }

    private void playPreview(int ms, int amplitude) {
        try {
            android.os.VibratorManager vm =
                    (android.os.VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            android.os.Vibrator vibrator = vm.getDefaultVibrator();
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                    ms, Math.max(1, Math.min(255, amplitude))));
        } catch (Throwable ignored) {
        }
    }

    private void readSettings() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int touch = readLevel(TOUCH_KEY);
                final int notify = readLevel(NOTIFY_KEY);
                final int alarm = readLevel(ALARM_KEY);
                final int ring = readLevel(RING_KEY);
                final boolean hapticOn = readOn("haptic_feedback_enabled");
                final boolean ringOn = readOn("vibrate_when_ringing");
                final boolean ok = PServer.available();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        touchRow.setLevel(touch);
                        notifyRow.setLevel(notify);
                        alarmRow.setLevel(alarm);
                        ringRow.setLevel(ring);
                        hapticSwitch.setOn(hapticOn);
                        ringSwitch.setOn(ringOn);
                        status.setText(ok ? "PSERVER CONNECTED" : "PSERVER UNAVAILABLE");
                        status.setTextColor(ok ? GREEN : AMBER);
                        if (!initialFocus && !focusables.isEmpty()) {
                            initialFocus = true;
                            View first = focusables.size() > 1
                                    ? focusables.get(1) : focusables.get(0);
                            first.requestFocus();
                        }
                    }
                });
            }
        }, "pavo-haptics-read").start();
    }

    private int readLevel(String key) {
        String out = PServer.exec("settings get system " + key, 1200L);
        if (out == null) return 2;
        out = out.trim();
        try {
            int v = Integer.parseInt(out);
            return Math.max(1, Math.min(3, v));
        } catch (Throwable ignored) {
            return 2;
        }
    }

    private boolean readOn(String key) {
        String out = PServer.exec("settings get system " + key, 1200L);
        if (out == null) return true;
        out = out.trim();
        return !"0".equals(out) && !"null".equals(out);
    }

    private String levelLabel(int level) {
        if (level <= 1) return Loc.s(this, "LOW");
        if (level >= 3) return Loc.s(this, "HIGH");
        return Loc.s(this, "MEDIUM");
    }

    // ------------------------------------------------------------------ input

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
                View focused = getCurrentFocus();
                Row row = rowFor(focused);
                if (row != null) row.activate();
                else if (focused != null) focused.performClick();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down && !repeat) {
                Row row = rowFor(getCurrentFocus());
                if (row != null) {
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) row.right();
                    else row.left();
                    if (!row.isSlider()) Haptics.tick(getCurrentFocus());
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

    // ------------------------------------------------------------------ util

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

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
