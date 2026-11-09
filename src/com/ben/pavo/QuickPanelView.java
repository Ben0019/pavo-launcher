package com.ben.pavo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Side quick-controls panel. It lives in an overlay, so it can be pulled over a
 * running game without pausing it. It uses the launcher's glass card, section
 * cards, uppercase labels, preset colours and slim accent sliders.
 */
final class QuickPanelView extends FrameLayout {

    interface Listener {
        void onDismiss();
    }

    private static final int FAN_OFF = PerfTuner.FAN_OFF;
    private static final int FAN_SMART = PerfTuner.FAN_SMART;
    private static final int FAN_HIGH = PerfTuner.FAN_HIGH;
    private static final int FAN_CURVE = FanProfile.MODE_CURVE;
    private static final int FAN_SMALL = FanProfile.MODE_SMALL;
    private static final int FAN_MEDIUM = FanProfile.MODE_MEDIUM;
    private static final int FAN_LARGE = FanProfile.MODE_LARGE;
    private static final int FAN_HOLD = FanProfile.MODE_HOLD;

    private static final int[] FAN_MODES = {
            FAN_OFF, FAN_SMART, FAN_HIGH, FAN_CURVE
    };

    private static final int[] FAN_PRESETS = {
            FAN_SMALL, FAN_MEDIUM, FAN_LARGE, FAN_HOLD
    };

    private static final int CARD_FILL = 0x0FFFFFFF;
    private static final int CARD_EDGE = 0x1AFFFFFF;
    private static final int TEXT = 0xE6FFFFFF;
    private static final int LABEL = 0x8CFFFFFF;

    private final Context context;
    private final Prefs prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final List<TextView> perfChips = new ArrayList<>();
    private final List<String> perfNames = new ArrayList<>();
    private final List<TextView> fanChips = new ArrayList<>();
    private final List<Integer> fanValues = new ArrayList<>();
    private final List<TextView> refreshChips = new ArrayList<>();
    private final List<Integer> refreshValues = new ArrayList<>();
    private int refreshSelection = -1;

    private FrameLayout card;
    private CappedScrollView scroll;
    private int cardW;
    private int topInset;
    private int edge;
    private boolean closing;
    private TextView brightnessValue;
    private TextView volumeValue;
    private final ArrayList<View> navItems = new ArrayList<>();
    private FocusRingDrawable navRing;
    private final java.util.HashMap<View, Float> navRadii = new java.util.HashMap<>();
    private View navFocus;
    private boolean navLongFired;
    private final Runnable navLongPress = new Runnable() {
        @Override
        public void run() {
            if (navFocus == null) return;
            navLongFired = true;
            Haptics.longPress(navFocus);
            navFocus.performLongClick();
        }
    };
    private PanelVerticalSlider brightnessSlider;
    private PanelVerticalSlider volumeSlider;
    private PanelVerticalSlider lastSlider;
    private long lastStickAdjust;
    private int lastBrightnessPercent = -1;
    private long lastBrightnessAt;
    private int lastVolumePercent = -1;
    private long lastVolumeAt;

    QuickPanelView(Context c, Backdrop backdrop, Listener listener) {
        super(c);
        this.context = c;
        this.listener = listener;
        this.prefs = new Prefs(c);
        ThemeColors.initialize(c);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        edge = Ui.dp(c, 10);
        topInset = edge + statusBarHeight(c);
        cardW = Ui.dp(c, 212);

        setClickable(true);
        View outside = new View(c);
        outside.setBackgroundColor(0x00000000);
        outside.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissAnimated();
            }
        });
        addView(outside, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new RoundedClipFrameLayout(c);
        ((RoundedClipFrameLayout) card).setRadiusDp(26);
        card.setClickable(true);
        card.setClipChildren(true);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(c, 13), Ui.dp(c, 12), Ui.dp(c, 13), Ui.dp(c, 14));

        panel.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new CappedScrollView(c);
        scroll.setOnScrollChangeListener(new OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                updateRingBounds();
            }
        });
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setMaxHeight(Math.min(screenH - topInset - edge - Ui.dp(c, 70),
                Ui.dp(c, 440)));
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildTopRow(content);
        buildFanRow(content);
        buildScreenshot(content);
        GlassPanel glass = new GlassPanel(c, backdrop, accent(), 0x4D0B0E14, 26, true);
        glass.setBackdropScreenAnchored(true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setSurfaceAlpha(prefs.drawerOverlayAlpha() / 100f);
        glass.setBackdropSaturation(prefs.popupDrawerSaturation() / 100f);
        glass.setBackdropDarkness(prefs.popupDrawerBackdropDarkness() / 100f);
        glass.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(glass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.rightMargin = edge;
        cardLp.topMargin = topInset;
        cardLp.gravity = Gravity.END;
        addView(card, cardLp);
        card.setTranslationX(cardW + Ui.dp(c, 30));
        card.animate().translationX(0f).setDuration(170)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                .start();

        syncChips();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (scroll == null || h <= 0) return;
        int cap = Math.max(Ui.dp(context, 220),
                Math.min(h - topInset - edge - Ui.dp(context, 70), Ui.dp(context, 640)));
        scroll.setMaxHeight(cap);
        scroll.requestLayout();
    }

    // ------------------------------------------------------------------
    // Sections
    // ------------------------------------------------------------------

    private View buildHeader() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(context, 4), Ui.dp(context, 2),
                Ui.dp(context, 2), Ui.dp(context, 8));

        TextView title = new LTextView(context);
        title.setText(Loc.s(context, "Quick Controls"));
        title.setAllCaps(true);
        title.setTextColor(accent());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        title.setLetterSpacing(0.10f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new LTextView(context);
        close.setText("\u00d7");
        close.setTextColor(0xCCFFFFFF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dismissAnimated();
            }
        });
        header.addView(close);
        return header;
    }

    private void buildTopRow(LinearLayout content) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 150));
        rowLp.bottomMargin = Ui.dp(context, 8);
        content.addView(row, rowLp);

        LinearLayout sliders = sectionCard();
        sliders.setPadding(Ui.dp(context, 12), Ui.dp(context, 8),
                Ui.dp(context, 12), Ui.dp(context, 1));
        row.addView(sliders, new LinearLayout.LayoutParams(
                Ui.dp(context, 104), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout cols = new LinearLayout(context);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        sliders.addView(cols, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int brightness = readBrightnessPercent();
        brightnessValue = valueLabel();
        brightnessValue.setText(brightness + "%");
        brightnessValue.setGravity(Gravity.CENTER);
        cols.addView(sliderColumn("BRIT",
                brightnessValue, brightness, new PanelVerticalSlider.Listener() {
                    @Override
                    public void onProgress(int percent, boolean released) {
                        brightnessValue.setText(percent + "%");
                        long now = SystemClock.uptimeMillis();
                        if (!released && percent == lastBrightnessPercent) return;
                        if (!released && now - lastBrightnessAt < 50) return;
                        lastBrightnessPercent = percent;
                        lastBrightnessAt = now;
                        DisplayTuner.setBrightness(percent);
                    }
                }), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        brightnessSlider = lastSlider;

        View gap = new View(context);
        cols.addView(gap, new LinearLayout.LayoutParams(
                Ui.dp(context, 2), ViewGroup.LayoutParams.MATCH_PARENT));

        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int max = 15;
        int cur = 8;
        if (audio != null) {
            max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            cur = Math.max(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
        final int volumeMax = max;
        int volumePercent = Math.round(cur * 100f / max);
        volumeValue = valueLabel();
        volumeValue.setText(volumePercent + "%");
        volumeValue.setGravity(Gravity.CENTER);
        cols.addView(sliderColumn("VOL",
                volumeValue, volumePercent, new PanelVerticalSlider.Listener() {
                    @Override
                    public void onProgress(int percent, boolean released) {
                        volumeValue.setText(percent + "%");
                        long now = SystemClock.uptimeMillis();
                        if (!released && percent == lastVolumePercent) return;
                        if (!released && now - lastVolumeAt < 50) return;
                        lastVolumePercent = percent;
                        lastVolumeAt = now;
                        AudioManager am = (AudioManager) context
                                .getSystemService(Context.AUDIO_SERVICE);
                        if (am == null) return;
                        try {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                                    Math.round(percent * volumeMax / 100f), 0);
                        } catch (Throwable ignored) {
                        }
                    }
                }), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        volumeSlider = lastSlider;

        LinearLayout perf = sectionCard();
        LinearLayout.LayoutParams perfLp = new LinearLayout.LayoutParams(
                Ui.dp(context, 78), ViewGroup.LayoutParams.MATCH_PARENT);
        perfLp.leftMargin = Ui.dp(context, 4);
        row.addView(perf, perfLp);
        perf.addView(sectionLabel("PERF"));

        String selected = currentPerf();
        for (String name : PerfProfiles.NAMES) {
            final String profile = name;
            TextView chip = chip(Loc.s(context, name), true);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    applyPerformance(profile);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
            lp.bottomMargin = Ui.dp(context, 4);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            perf.addView(chip, lp);
            registerNav(chip);
            perfChips.add(chip);
            perfNames.add(name);
            if (name.equals(selected)) styleProfileChip(chip, profile, true);
        }
    }

    private void buildFanRow(LinearLayout content) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 150));
        rowLp.bottomMargin = Ui.dp(context, 8);
        content.addView(row, rowLp);

        LinearLayout fanCard = sectionCard();
        row.addView(fanCard, new LinearLayout.LayoutParams(
                Ui.dp(context, 104), ViewGroup.LayoutParams.MATCH_PARENT));
        fanCard.addView(sectionLabel(Loc.s(context, "Fan")));

        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        fanCard.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int[] fanAll = new int[]{
                FAN_SMALL, FAN_MEDIUM, FAN_LARGE, FAN_HOLD,
                FAN_OFF, FAN_SMART, FAN_HIGH, FAN_CURVE
        };
        int fanSelected = prefs.fanAppMode();
        for (int r = 0; r < 4; r++) {
            LinearLayout gridRow = new LinearLayout(context);
            gridRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams gridRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r < 3) gridRowLp.bottomMargin = Ui.dp(context, 3);
            grid.addView(gridRow, gridRowLp);
            for (int c = 0; c < 2; c++) {
                final int fanMode = fanAll[r * 2 + c];
                TextView chip = chip(Loc.s(context, fanName(fanMode)), true);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 6.5f);
                chip.setPadding(Ui.dp(context, 1), Ui.dp(context, 1),
                        Ui.dp(context, 1), Ui.dp(context, 1));
                chip.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Haptics.click(v);
                        applyFan(fanMode);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c == 0) lp.rightMargin = Ui.dp(context, 3);
                gridRow.addView(chip, lp);
                registerNav(chip);
                fanChips.add(chip);
                fanValues.add(fanMode);
                styleFanChip(chip, fanMode, fanMode == fanSelected);
            }
        }

        LinearLayout refreshCard = sectionCard();
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                Ui.dp(context, 78), ViewGroup.LayoutParams.MATCH_PARENT);
        refreshLp.leftMargin = Ui.dp(context, 4);
        row.addView(refreshCard, refreshLp);
        refreshCard.addView(sectionLabel("REF"));

        DisplayTuner.Snapshot snapshot = null;
        try {
            snapshot = DisplayTuner.read(context);
        } catch (Throwable ignored) {
        }
        int current = 0;
        int[] rates = new int[]{60, 120};
        if (snapshot != null) {
            rates = snapshot.refreshRates;
            current = snapshot.minRefresh <= 0 ? 0 : snapshot.minRefresh;
        }
        addRefreshChip(refreshCard, Loc.s(context, "Auto"), 0, current == 0);
        for (int hz : rates) {
            if (hz < 30) continue;
            addRefreshChip(refreshCard, hz + " Hz", hz, hz == current);
        }
    }

    private View sliderColumn(String title, TextView value, int percent,
                              PanelVerticalSlider.Listener listener) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        column.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = sectionLabel(title);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, 0, 0, Ui.dp(context, 3));
        column.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PanelVerticalSlider slider = new PanelVerticalSlider(context);
        lastSlider = slider;
        registerNav(slider, 11f);
        slider.setProgress(percent);
        slider.setListener(listener);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sliderLp.topMargin = Ui.dp(context, 2);
        column.addView(slider, sliderLp);
        return column;
    }

    // ------------------------------------------------------------------
    // Controller navigation
    // ------------------------------------------------------------------

    private void registerNav(View v) {
        registerNav(v, 10f);
    }

    /** @param radiusDp corner radius of the control; the ring follows it. */
    private void registerNav(View v, float radiusDp) {
        if (v == null || navItems.contains(v)) return;
        navItems.add(v);
        navRadii.put(v, radiusDp);
    }

    private int[] centerOf(View v) {
        int[] p = new int[2];
        v.getLocationOnScreen(p);
        return new int[]{p[0] + v.getWidth() / 2, p[1] + v.getHeight() / 2};
    }

    private static String navLabel(View v) {
        if (v == null) return "none";
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            return "chip:" + (t == null ? "?" : t);
        }
        if (v instanceof PanelVerticalSlider) return "slider";
        return v.getClass().getSimpleName();
    }

    private void focusItem(View v) {
        if (v == navFocus) return;
        navFocus = v;
        if (v == null) {
            if (navRing != null) navRing.setBounds(0, 0, 0, 0);
            return;
        }
        if (navRing == null) {
            navRing = new FocusRingDrawable(context, ThemeColors.accent());
            getOverlay().add(navRing);
        }
        Float radius = navRadii.get(v);
        navRing.setRingRadius(Ui.dpf(context, radius == null ? 10f : radius));
        updateRingBounds();
        ensureVisible(v);
    }

    private void updateRingBounds() {
        if (navRing == null || navFocus == null) return;
        int[] loc = new int[2];
        int[] self = new int[2];
        navFocus.getLocationOnScreen(loc);
        getLocationOnScreen(self);
        int left = loc[0] - self[0];
        int top = loc[1] - self[1];
        navRing.setBounds(left, top, left + navFocus.getWidth(),
                top + navFocus.getHeight());
    }

    /** The row (direct child of the scrolled content) that holds a control. */
    private View rowOf(View v) {
        if (scroll == null || v == null) return null;
        View content = scroll.getChildAt(0);
        if (content == null) return null;
        View cur = v;
        while (cur != null && cur.getParent() != null
                && cur.getParent() != content) {
            if (!(cur.getParent() instanceof View)) return null;
            cur = (View) cur.getParent();
        }
        return cur != null && cur.getParent() == content ? cur : null;
    }

    private void ensureVisible(View v) {
        if (scroll == null || v == null) return;
        View row = rowOf(v);
        View target = row != null ? row : v;
        int[] item = new int[2];
        int[] host = new int[2];
        target.getLocationOnScreen(item);
        scroll.getLocationOnScreen(host);
        int top = item[1] - host[1];
        int bottom = top + target.getHeight();
        int viewport = scroll.getHeight();
        int pad = Ui.dp(context, 10);
        if (bottom - top <= viewport - pad * 2) {
            // The whole row fits: show all of it, so card titles stay visible.
            if (top < pad) scroll.scrollBy(0, top - pad);
            else if (bottom > viewport - pad) scroll.scrollBy(0, bottom - viewport + pad);
        } else {
            int[] f = new int[2];
            v.getLocationOnScreen(f);
            int vTop = f[1] - host[1];
            int vBottom = vTop + v.getHeight();
            if (vTop < pad) scroll.scrollBy(0, vTop - pad);
            else if (vBottom > viewport - pad) scroll.scrollBy(0, vBottom - viewport + pad);
        }
        updateRingBounds();
    }

    private final Runnable ringRefresh = new Runnable() {
        @Override
        public void run() {
            updateRingBounds();
        }
    };

    private void moveFocus(int dx, int dy) {
        if (navItems.isEmpty()) return;
        if (navFocus == null) {
            focusItem(navItems.get(0));
            return;
        }
        int[] c = centerOf(navFocus);
        int minStep = Ui.dp(context, 14);
        View focusRow = rowOf(navFocus);
        View best = null;
        double bestScore = Double.MAX_VALUE;
        // Horizontal moves prefer the same row first, so left/right walks the
        // cards or the button row instead of jumping diagonally.
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (View v : navItems) {
                if (v == navFocus || v.getVisibility() != View.VISIBLE) continue;
                if (dx != 0 && pass == 0 && rowOf(v) != focusRow) continue;
                int[] p = centerOf(v);
                int vx = p[0] - c[0];
                int vy = p[1] - c[1];
                if (dx != 0 && Integer.signum(vx) != dx) continue;
                if (dy != 0 && Integer.signum(vy) != dy) continue;
                // Centred chips differ by a pixel or two, so a pure vertical
                // neighbour could otherwise count as "left" and win.
                if (dx != 0 && Math.abs(vx) < minStep) continue;
                if (dy != 0 && Math.abs(vy) < minStep) continue;
                double primary = dx != 0 ? Math.abs(vx) : Math.abs(vy);
                double perp = dx != 0 ? Math.abs(vy) : Math.abs(vx);
                if (primary < 1) continue;
                double score = primary + perp * 1.8;
                if (score < bestScore) {
                    bestScore = score;
                    best = v;
                }
            }
        }
        if (best != null) focusItem(best);
    }

    /** @return true when the key was consumed. */
    boolean handleControllerKey(String key, boolean down, boolean repeat) {
        if ("BTN_SOUTH".equals(key) || "BTN_GAMEPAD".equals(key)
                || "KEY_ENTER".equals(key)) {
            if (down) {
                navLongFired = false;
                main.removeCallbacks(navLongPress);
                main.postDelayed(navLongPress,
                        android.view.ViewConfiguration.getLongPressTimeout());
            } else if (!repeat) {
                main.removeCallbacks(navLongPress);
                if (!navLongFired && navFocus != null) navFocus.performClick();
            }
            return true;
        }
        if (!down && !repeat) return true;
        if (navFocus == null) {
            focusItem(navItems.isEmpty() ? null : navItems.get(0));
            if (!repeat) return true;
        }
        switch (key) {
            case "BTN_DPAD_UP":
                moveFocus(0, -1);
                return true;
            case "BTN_DPAD_DOWN":
                moveFocus(0, 1);
                return true;
            case "BTN_DPAD_LEFT":
                moveFocus(-1, 0);
                return true;
            case "BTN_DPAD_RIGHT":
                moveFocus(1, 0);
                return true;
            case "BTN_EAST":
            case "KEY_BACK":
                dismissAnimated();
                return true;
            default:
                return false;
        }
    }

    private int stickNavDir = -1;
    private long lastStickNav;
    private PanelVerticalSlider stickAdjustSlider;
    private int stickAdjustSign;

    private final Runnable stickNavRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickNavDir < 0) return;
            stepFocus(stickNavDir);
            main.postDelayed(this, 180L);
        }
    };

    private final Runnable stickAdjustRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickAdjustSlider == null || stickAdjustSign == 0) return;
            stickAdjustSlider.nudge(stickAdjustSign * 2);
            main.postDelayed(this, 90L);
        }
    };

    private void stepFocus(int dir) {
        if (dir == 0) moveFocus(-1, 0);
        else if (dir == 1) moveFocus(1, 0);
        else if (dir == 2) moveFocus(0, -1);
        else moveFocus(0, 1);
    }

    /** Left stick: one focus step per deflection, fast repeat while held. */
    boolean handleControllerStick(float x, float y) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        if (Math.max(ax, ay) < 0.5f) {
            stickNavDir = -1;
            main.removeCallbacks(stickNavRepeat);
            return true;
        }
        int dir = ax >= ay ? (x < 0 ? 0 : 1) : (y < 0 ? 2 : 3);
        if (navFocus == null) {
            focusItem(navItems.isEmpty() ? null : navItems.get(0));
            return true;
        }
        if (dir != stickNavDir) {
            stickNavDir = dir;
            stepFocus(dir);
            main.removeCallbacks(stickNavRepeat);
            main.postDelayed(stickNavRepeat, 340L);
        }
        return true;
    }

    /**
     * The right stick is reserved for the sliders: it adjusts the last slider the
     * D-pad touched (brightness by default) and never moves focus.
     */
    boolean handleControllerAxis(int code, float value) {
        // Only the slider under the ring responds. If nothing is focused yet,
        // the first stick movement focuses brightness and starts there.
        if (navFocus == null) focusItem(brightnessSlider);
        if (!(navFocus instanceof PanelVerticalSlider)) return true;
        PanelVerticalSlider slider = (PanelVerticalSlider) navFocus;
        if (Math.abs(value) < 0.5f) {
            stickAdjustSlider = null;
            stickAdjustSign = 0;
            main.removeCallbacks(stickAdjustRepeat);
            return true;
        }
        // ABS_RZ reports stick-up as a negative value; flip it so up increases.
        float dir = -value;
        int sign = dir > 0 ? 1 : -1;
        if (slider != stickAdjustSlider || sign != stickAdjustSign) {
            long now = SystemClock.uptimeMillis();
            if (now - lastStickAdjust < 80) return true;
            lastStickAdjust = now;
            stickAdjustSlider = slider;
            stickAdjustSign = sign;
            slider.nudge(sign * 3);
            main.removeCallbacks(stickAdjustRepeat);
            main.postDelayed(stickAdjustRepeat, 300L);
        }
        return true;
    }

    private void buildScreenshot(LinearLayout content) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(context, 2);
        content.addView(row, rowLp);

        TextView shot = actionButton("Shot");
        shot.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                takeScreenshot();
            }
        });
        shot.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Haptics.longPress(v);
                startRegionCapture();
                return true;
            }
        });
        LinearLayout.LayoutParams shotLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        shotLp.rightMargin = Ui.dp(context, 4);
        row.addView(shot, shotLp);
        registerNav(shot, 14f);

        TextView record = actionButton("Rec");
        record.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                startRecording(false);
            }
        });
        record.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Haptics.longPress(v);
                startRecording(true);
                return true;
            }
        });
        LinearLayout.LayoutParams recordLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        recordLp.rightMargin = Ui.dp(context, 4);
        row.addView(record, recordLp);
        registerNav(record, 14f);

        TextView mapper = actionButton("Map");
        mapper.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openTouchMapperOverlay();
            }
        });
        row.addView(mapper, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        registerNav(mapper, 14f);
    }

    /**
     * Opens the in-game Touch Mapper overlay, never the Touch Mapper app. When the
     * app behind the panel has no touch map yet, the mapper is enabled for that
     * app only, so every edit made from here is stored against it.
     */
    private void openTouchMapperOverlay() {
        TouchMapStore store = new TouchMapStore(context);
        String target = ForegroundApp.read(context);
        boolean usable = target != null && !target.isEmpty()
                && !target.equals(context.getPackageName())
                && !target.equals("com.android.systemui")
                && !target.equals("android");
        if (usable && !store.isAppEnabled(target)) {
            // Bind the mapper (and every later edit) to the app behind the panel.
            store.setTarget(target);
            store.setAppEnabled(target, true);
        } else if (usable) {
            store.setTarget(target);
        } else if (!store.enabled()) {
            toast(Loc.s(context, "Touch Mapper is off"));
            return;
        }
        dismissAnimated();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                TouchMapService.start(context);
                TouchMapService.refresh(context);
            }
        }, 150L);
    }

    private TextView actionButton(String label) {
        TextView button = new LTextView(context);
        button.setText(Loc.s(context, label));
        button.setSingleLine(true);
        button.setAllCaps(true);
        button.setTextColor(accent());
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        button.setLetterSpacing(0.08f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Ui.dp(context, 42));
        button.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0);
        button.setBackground(Ui.rounded(0x12FFFFFF, Ui.dpf(context, 14),
                0x2EFFFFFF, Ui.dpf(context, 1)));
        return button;
    }

    /**
     * Opens the launcher's screen recorder and starts a capture without showing
     * its UI. Long press limits the capture to the app chosen in the system prompt.
     */
    private void startRecording(final boolean singleApp) {
        dismissAnimated();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(context, ScreenRecorderActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!ScreenRecorderService.isRecording()
                        && !ScreenRecorderService.isSaving()) {
                    // Invisible start: the recorder UI never appears.
                    if (singleApp) {
                        intent.putExtra(ScreenRecorderActivity.EXTRA_QUICK_START_SINGLE_APP,
                                true);
                    } else {
                        intent.putExtra(ScreenRecorderActivity.EXTRA_QUICK_START, true);
                    }
                }
                try {
                    context.startActivity(intent);
                } catch (Throwable t) {
                    toast(Loc.s(context, "Could not start recording."));
                }
            }
        }, 140L);
    }

    private void addRefreshChip(LinearLayout card, String label, int hz, boolean selected) {
        TextView chip = chip(label, true);
        chip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                DisplayTuner.setRefresh(context, hz, hz == 0);
                refreshSelection = hz;
                styleRefreshChips();
            }
        });
        card.addView(chip, stackedChipLp());
        registerNav(chip);
        refreshChips.add(chip);
        refreshValues.add(hz);
        if (selected) styleChip(chip, true);
    }

    private void styleRefreshChips() {
        for (int i = 0; i < refreshChips.size(); i++) {
            styleChip(refreshChips.get(i), refreshValues.get(i) == refreshSelection);
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void applyPerformance(final String name) {
        for (int i = 0; i < perfChips.size(); i++) {
            styleProfileChip(perfChips.get(i), perfNames.get(i),
                    perfNames.get(i).equals(name));
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PerfTuner.State state = PerfTuner.readState(context);
                    PerfProfiles.Spec factory = PerfProfiles.get(name, state);
                    TuneStore store = new TuneStore(context);
                    TuneConfig stored = store.preset(name);
                    int p0Max = stored != null ? stored.p0Max : factory.p0Max;
                    int p0Min = stored != null ? stored.p0Min : factory.p0Min;
                    int p6Max = stored != null ? stored.p6Max : factory.p6Max;
                    int p6Min = stored != null ? stored.p6Min : factory.p6Min;
                    int gpuMax = stored != null ? stored.gpuMax : factory.gpuMax;
                    int gpuMin = stored != null ? stored.gpuMin : factory.gpuMin;
                    String gov = stored != null && stored.p0Gov != null
                            && !stored.p0Gov.isEmpty() ? stored.p0Gov : factory.governor;
                    PerfTuner.Root.ensure();
                    PerfTuner.setCpu(state.efficiency, p0Max, p0Min, gov);
                    PerfTuner.setCpu(state.performance, p6Max, p6Min, gov);
                    PerfTuner.setGpu(state.gpu, gpuMax, gpuMin);

                    prefs.setTuneTargets(p0Max, p0Min, gov, p6Max, p6Min, gov, gpuMax, gpuMin);
                    prefs.setPerfHoldEnabled(true);
                    prefs.setHoldMode(PerfTuner.currentMode(context));
                    TuneConfig cfg = store.global();
                    if (cfg == null) cfg = new TuneConfig();
                    cfg.p0Max = p0Max;
                    cfg.p0Min = p0Min;
                    cfg.p6Max = p6Max;
                    cfg.p6Min = p6Min;
                    cfg.gpuMax = gpuMax;
                    cfg.gpuMin = gpuMin;
                    cfg.p0Gov = gov;
                    cfg.p6Gov = gov;
                    cfg.profile = name;
                    store.setGlobal(cfg);
                    PerfTuneService.start(context);
                    toast(name + " " + Loc.s(context, "APPLIED"));
                } catch (Throwable t) {
                    toast(Loc.s(context, "Could not apply the preset"));
                }
            }
        });
    }

    private void applyFan(final int mode) {
        for (int i = 0; i < fanChips.size(); i++) {
            styleFanChip(fanChips.get(i), fanValues.get(i), fanValues.get(i) == mode);
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    new FanProfile(mode, prefs.fanManualPercent(), prefs.fanCurve())
                            .saveToPrefs(prefs);
                    boolean managed = mode == FAN_CURVE || FanCurve.isPresetMode(mode)
                            || mode == FAN_HOLD || mode == FAN_SMART || mode == FAN_HIGH;
                    if (managed) {
                        FanControl.start(context);
                    } else {
                        FanControl.stop(context);
                        if (mode == FAN_OFF) PerfTuner.setFanOff(context);
                    }
                    if (mode == FAN_HOLD) {
                        prefs.setPerfHoldEnabled(true);
                        PerfTuneService.start(context);
                    }
                    toast(Loc.s(context, fanName(mode)));
                } catch (Throwable t) {
                    toast(Loc.s(context, "Could not apply the fan preset"));
                }
            }
        });
    }

    /** Long press: free the screen, drag a rectangle, save that crop. */
    private void startRegionCapture() {
        dismissAnimated();
        QuickPanelEdgeHandle.hide();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                RegionCapture.start(context);
            }
        }, 220L);
    }

    private void takeScreenshot() {
        dismissAnimated();
        QuickPanelEdgeHandle.hide();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                final File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "Pavo Screenshots");
                final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                        Locale.US).format(new Date());
                final File file = new File(dir, "Aurora_" + stamp + ".png");
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = false;
                        try {
                            if (!dir.exists()) dir.mkdirs();
                            ok = PServer.available() && PServer.run("screencap -p "
                                    + shellQuote(file.getAbsolutePath()));
                        } catch (Throwable ignored) {
                        }
                        try {
                            Thread.sleep(700L);
                        } catch (InterruptedException ignored) {
                        }
                        final boolean captured = ok && file.isFile() && file.length() > 0;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                QuickPanelEdgeHandle.show();
                            }
                        });
                        if (captured) {
                            MediaScannerConnection.scanFile(context,
                                    new String[]{file.getAbsolutePath()},
                                    new String[]{"image/png"}, null);
                            toast(Loc.s(context, "Screenshot saved"));
                        } else {
                            toast(Loc.s(context, "Screenshot needs PServer/root"));
                        }
                    }
                });
            }
        }, 140L);
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private String currentPerf() {
        try {
            TuneStore store = new TuneStore(context);
            TuneConfig global = store.global();
            if (global != null && global.profile != null
                    && PerfProfiles.isProfile(global.profile)) {
                return global.profile;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void syncChips() {
        String perf = currentPerf();
        for (int i = 0; i < perfChips.size(); i++) {
            styleProfileChip(perfChips.get(i), perfNames.get(i),
                    perfNames.get(i).equals(perf));
        }
        int fan = prefs.fanAppMode();
        for (int i = 0; i < fanChips.size(); i++) {
            styleFanChip(fanChips.get(i), fanValues.get(i), fanValues.get(i) == fan);
        }
    }

    private static String fanName(int mode) {
        switch (mode) {
            case FAN_OFF:
                return "OFF";
            case FAN_SMART:
                return "SMART";
            case FAN_HIGH:
                return "HIGH";
            case FAN_CURVE:
                return "CURVE";
            case FAN_SMALL:
                return "SMALL";
            case FAN_MEDIUM:
                return "MEDIUM";
            case FAN_LARGE:
                return "LARGE";
            case FAN_HOLD:
                return "HOLD";
            default:
                return "SMART";
        }
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private LinearLayout sectionCard() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(context, 12), Ui.dp(context, 8),
                Ui.dp(context, 12), Ui.dp(context, 10));
        card.setBackground(Ui.rounded(CARD_FILL, Ui.dpf(context, 14),
                CARD_EDGE, Ui.dpf(context, 1)));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(context, 8);
        return lp;
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView sectionLabel(String text) {
        TextView label = new LTextView(context);
        label.setText(text);
        label.setAllCaps(true);
        label.setTextColor(LABEL);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, 0, 0, Ui.dp(context, 6));
        return label;
    }

    private LinearLayout sliderHead(String title, TextView value) {
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(sectionLabel(title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(value);
        return head;
    }

    private TextView valueLabel() {
        TextView value = new LTextView(context);
        value.setTextColor(accent());
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setGravity(Gravity.END);
        return value;
    }

    private TextView chip(String label, boolean small) {
        TextView chip = new LTextView(context);
        chip.setText(label);
        chip.setAllCaps(true);
        chip.setTextColor(TEXT);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? 7.5f : 8.5f);
        chip.setLetterSpacing(0.02f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setMinHeight(Ui.dp(context, small ? 24 : 34));
        chip.setPadding(Ui.dp(context, 7), Ui.dp(context, 2),
                Ui.dp(context, 7), Ui.dp(context, 2));
        chip.setBackground(Ui.rounded(0x12FFFFFF, Ui.dpf(context, 10),
                0x26FFFFFF, Ui.dpf(context, 1)));
        return chip;
    }

    private LinearLayout.LayoutParams stackedChipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f);
        lp.bottomMargin = Ui.dp(context, 4);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private LinearLayout.LayoutParams chipWrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(context, 4);
        return lp;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(context, 4);
        return lp;
    }

    private void styleChip(TextView chip, boolean selected) {
        if (chip == null) return;
        if (selected) {
            chip.setTextColor(0xFF071018);
            chip.setBackground(Ui.rounded(accent(), Ui.dpf(context, 10)));
        } else {
            chip.setTextColor(TEXT);
            chip.setBackground(Ui.rounded(0x12FFFFFF, Ui.dpf(context, 10),
                    0x26FFFFFF, Ui.dpf(context, 1)));
        }
    }

    /** Fan chips keep the Fan Control app's per-mode colour. */
    private void styleFanChip(TextView chip, int mode, boolean selected) {
        if (chip == null) return;
        int color = fanModeColor(mode);
        if (selected) {
            chip.setTextColor(0xFF071018);
            chip.setBackground(Ui.rounded(color, Ui.dpf(context, 10)));
        } else {
            chip.setTextColor(HeatColor.mixColor(color, 0xFFFFFFFF, 0.38f));
            chip.setBackground(Ui.rounded(HeatColor.withAlpha(color, 0x1C),
                    Ui.dpf(context, 10), HeatColor.withAlpha(color, 0x77),
                    Ui.dpf(context, 1)));
        }
    }

    private static int fanModeColor(int mode) {
        // Mirrors FanActivity.presetColor so the panel matches Fan Control.
        if (mode == FAN_SMALL) return HeatColor.ramp(1f);
        if (mode == FAN_MEDIUM) return HeatColor.ramp(1f / 3f);
        if (mode == FAN_LARGE) return HeatColor.ramp(0f);
        if (mode == FAN_HOLD) return 0xFF4DD0E1;
        if (mode == FAN_OFF) return 0xFF8C98A4;
        if (mode == FAN_SMART) return 0xFF5FB8FF;
        if (mode == FAN_HIGH) return 0xFFFF9F43;
        return 0xFFB98CFF;
    }

    /** Same colours and chip shape as the Performance Tuner's preset buttons. */
    private void styleProfileChip(TextView chip, String profile, boolean selected) {
        if (chip == null) return;
        int color = PerformanceTunerActivity.profileColor(profile);
        int light = PerformanceTunerActivity.lighten(color, 0.36f);
        chip.setTextColor(selected ? PerformanceTunerActivity.DARK_TEXT : light);
        if (selected) {
            chip.setBackground(Ui.rounded(color, Ui.dpf(context, 13),
                    0xFFFFFFFF, Ui.dpf(context, 1)));
        } else {
            chip.setBackground(Ui.rounded(
                    PerformanceTunerActivity.withAlpha(color, 0x2C), Ui.dpf(context, 13),
                    PerformanceTunerActivity.withAlpha(color, 0x88), Ui.dpf(context, 1)));
        }
    }

    private static int accent() {
        return ThemeColors.accent();
    }

    private int readBrightnessPercent() {
        try {
            int raw = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            return Math.max(1, Math.min(100, Math.round(raw * 100f / 255f)));
        } catch (Throwable ignored) {
            return 50;
        }
    }

    private void toast(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                LToast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static int statusBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return Ui.dp(c, 24);
    }

    /** Space the card occupies at the right edge, for overlays that dodge it. */
    int occupiedWidthPx() {
        return cardW + edge;
    }

    void dismissAnimated() {
        if (closing) return;
        closing = true;
        if (card == null) {
            listener.onDismiss();
            return;
        }
        card.animate()
                .translationX(cardW + Ui.dp(context, 30))
                .alpha(0.4f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDismiss();
                    }
                })
                .start();
    }

    void closeNow() {
        closing = true;
        if (io != null) io.shutdownNow();
    }

    /** Scrolls only when the content is taller than the space left on screen. */
    private static final class CappedScrollView extends ScrollView {
        private int maxHeight;

        CappedScrollView(Context c) {
            super(c);
        }

        void setMaxHeight(int height) {
            maxHeight = height;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (maxHeight > 0) {
                heightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
