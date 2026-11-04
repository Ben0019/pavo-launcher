package com.ben.pavo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Device Health: live Odin battery state, charging animation and charge controls. */
public final class DeviceHealthActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int RED = 0xFFFF6B6B;
    private static final int CARD = 0x12FFFFFF;
    private static final int CARD_BORDER = 0x22FFFFFF;

    private int accent() {
        return ThemeColors.accent();
    }

    /**
     * Sample the launcher behind the sheet like the other built-ins. This was
     * disabled once because a bright home capture made the sheet look
     * transparent after a wake-up or a reopen; the blank-capture guard and the
     * readable-frost fallback are expected to cover that now.
     */
    @Override
    protected boolean samplesBackdrop() {
        return true;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private DeviceHealthStore store;
    private DeviceHealthReader.Snapshot snap;
    private BatteryPackView batteryView;
    private TextView levelText;
    private TextView levelSub;
    private TextView statusChip;
    private TextView heroHealth;
    private TextView heroCycles;
    private TextView heroTemp;
    private TextView serverState;
    private TextView demoChip;
    private TextView closeChip;
    private ScrollView scrollView;
    private final List<View> nav = new ArrayList<>();
    private long lastStickNav;
    private long lastRightStickAdjust;
    private long rightStickHeldSince;
    private float rightStickValue;
    private long rightStickLastEvent;
    private long rightStickStart;
    private boolean rightStickRepeatActive;

    private TextView limitChip;
    private TextView limitValue;
    private TextView limitNode;
    private TextView sepChip;
    private TextView autoChip;
    private TextView autoValue;
    private SeekBar autoBar;
    private TextView autoHint;
    private SeekBar limitBar;
    private TextView limitHint;

    private TextView vStatus, vHealth, vCycles, vTemp, vVoltage, vCurrent, vPower;
    private TextView vChargeType, vTimeFull, vTimeEmpty;
    private TextView vDesign, vFull, vCounter, vWear, vSoh, vResistance;
    private TextView vCpu, vGpu, vBatteryTemp;
    private TextView vModel, vAndroid, vBatteryModel, vUsb, vCcLimit;
    private TextView vConn, vInput, vInputPower, vInputLimit, vPd, vPath;

    private boolean resumed;
    private boolean applying;
    private boolean suppress;
    private boolean initialSync = true;
    private boolean demoActive;
    private long demoStart;
    private float demoLevel = 22f;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    final DeviceHealthReader.Snapshot s = DeviceHealthReader.read(DeviceHealthActivity.this);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (resumed) update(s);
                        }
                    });
                }
            });
            main.postDelayed(this, 1000L);
        }
    };

    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed || !demoActive) return;
            long t = (SystemClock.uptimeMillis() - demoStart) % 18000L;
            float level;
            boolean plugged;
            boolean charging;
            String label;
            int tone;
            if (t < 2400L) {
                level = 0f;
                plugged = false;
                charging = false;
                label = "DEMO · EMPTY";
                tone = RED;
            } else if (t < 3600L) {
                level = 0f;
                plugged = true;
                charging = false;
                label = "DEMO · CONNECTING";
                tone = AMBER;
            } else if (t < 10800L) {
                float p = (t - 3600f) / 7200f;
                level = 84f * p;
                plugged = true;
                charging = true;
                label = "DEMO · POURING";
                tone = GREEN;
            } else if (t < 12600L) {
                level = 84f;
                plugged = true;
                charging = false;
                label = "DEMO · TOPPED UP";
                tone = accent();
            } else if (t < 14200L) {
                level = 84f;
                plugged = false;
                charging = false;
                label = "DEMO · UNPLUGGED";
                tone = AMBER;
            } else {
                float p = (t - 14200f) / 3800f;
                level = 84f * (1f - Math.max(0f, Math.min(1f, p)));
                plugged = false;
                charging = false;
                label = "DEMO · DISCHARGING";
                tone = MUTED;
            }
            demoLevel = level;
            batteryView.setBattery(Math.round(level), charging, plugged, false, level >= 99f);
            levelText.setText(Math.round(level) + "%");
            long design = snap != null && snap.designUah > 0 ? snap.designUah : 7_838_000L;
            long counter = Math.round(design * level / 100f);
            levelSub.setText("  " + capText(counter) + " / " + capText(design) + Loc.s(DeviceHealthActivity.this, " mAh"));
            statusChip.setText(label);
            statusChip.setTextColor(tone);
            statusChip.setBackground(Ui.rounded(0x1AFFFFFF, dp(11),
                    withAlpha(tone, 0x55), dp(1)));
            String demoStatus = charging
                    ? Loc.s(DeviceHealthActivity.this, "Charging") + " · " + Loc.s(DeviceHealthActivity.this, "demo")
                    : plugged
                    ? Loc.s(DeviceHealthActivity.this, "Connected") + " · " + Loc.s(DeviceHealthActivity.this, "demo")
                    : Loc.s(DeviceHealthActivity.this, "Discharging") + " · " + Loc.s(DeviceHealthActivity.this, "demo");
            vStatus.setText(demoStatus);
            vCurrent.setText(charging ? "+1.82 A" : "--");
            vPower.setText(charging ? "8.72 W" : "--");
            vChargeType.setText(plugged ? "Demo charging pipe" : "Disconnected");
            serverState.setText("DEMO PREVIEW  ·  real battery data paused");
            main.postDelayed(this, 45L);
        }
    };

    private final Runnable rightStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (!resumed || rightStickValue == 0f || autoBar == null
                    || !autoBar.isEnabled()) {
                rightStickRepeatActive = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - rightStickLastEvent > 320L) {
                rightStickValue = 0f;
                rightStickRepeatActive = false;
                return;
            }
            if (getCurrentFocus() != autoBar) {
                autoBar.requestFocus();
                ensureVisible(autoBar);
            }
            long held = now - rightStickStart;
            int step = held > 700L ? 10 : 5;
            adjustAutoLevel(rightStickValue > 0f ? step : -step);
            long interval = Math.abs(rightStickValue) > 0.90f
                    ? (held > 600L ? 45L : 70L)
                    : (held > 600L ? 75L : 110L);
            main.postDelayed(this, interval);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        store = new DeviceHealthStore(this);
        buildUi();
        hideBars();
        if (getIntent() != null && getIntent().getBooleanExtra("demo", false)) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (resumed) startDemo();
                }
            }, 700L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideBars();
        hidePerformanceOverlay();
        if (batteryView != null) batteryView.setAnimating(true);
        BatteryGuardService.armIfNeeded(this);
        main.removeCallbacks(poll);
        main.post(poll);
        if (demoChip != null) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (resumed && getCurrentFocus() == null) {
                        demoChip.requestFocus();
                    }
                }
            }, 350L);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (resumed) hidePerformanceOverlay();
                }
            }, 320L);
        }
    }

    @Override
    public void onBackPressed() {
        closeHealth();
    }

    @Override
    protected void onUserLeaveHint() {
        stopHealthAnimations();
        super.onUserLeaveHint();
    }

    private void closeHealth() {
        stopHealthAnimations();
        finishAnimated();
    }

    private void stopHealthAnimations() {
        main.removeCallbacks(poll);
        main.removeCallbacks(demoTick);
        main.removeCallbacks(rightStickRepeat);
        rightStickValue = 0f;
        rightStickRepeatActive = false;
        if (batteryView != null) batteryView.setAnimating(false);
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (batteryView != null) batteryView.setAnimating(false);
        main.removeCallbacks(poll);
        rightStickValue = 0f;
        rightStickRepeatActive = false;
        main.removeCallbacks(rightStickRepeat);
        if (demoActive) stopDemo();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
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
        root.setFocusableInTouchMode(true);

        GlassPanel sheet = new GlassPanel(this, null, accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(new Prefs(this).builtinTransparency() / 100f);
        sheet.setPadding(dp(20), dp(11), dp(20), dp(10));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sheetLp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, sheetLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new LinearLayout.LayoutParams(
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
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = dp(7);
        column.addView(content, contentLp);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.94f);
        leftLp.rightMargin = dp(8);
        content.addView(left, leftLp);

        boolean tight = Ui.widthDp(this) < 700;
        ScrollView scroll = new ScrollView(this);
        scrollView = scroll;
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.06f);
        if (tight) {
            // The right column runs past the fold on 4:3 panels. A soft fade at
            // the bottom edge shows there is more to scroll to.
            FrameLayout wrap = new FrameLayout(this);
            wrap.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            final View fade = new View(this);
            android.graphics.drawable.PaintDrawable pd =
                    new android.graphics.drawable.PaintDrawable(0x00000000);
            pd.setShaderFactory(new android.graphics.drawable.ShapeDrawable.ShaderFactory() {
                @Override
                public android.graphics.Shader resize(int width, int height) {
                    return new android.graphics.LinearGradient(0, 0, 0, height,
                            new int[]{0x00080B10, 0xE6080B10},
                            null, android.graphics.Shader.TileMode.CLAMP);
                }
            });
            fade.setBackground(pd);
            FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(26), Gravity.BOTTOM);
            wrap.addView(fade, fadeLp);
            scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                    fade.setVisibility(v.canScrollVertically(1) ? View.VISIBLE : View.GONE);
                }
            });
            content.addView(wrap, scrollLp);
        } else {
            content.addView(scroll, scrollLp);
        }
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(0, 0, dp(2), dp(tight ? 22 : 8));
        scroll.addView(right, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildHero(left);
        buildBatterySection(right);
        buildCapacitySection(right);
        buildChargerSection(right);
        buildChargingSection(right);
        buildThermalSection(right);
        buildDeviceSection(right);

        setupControllerNav();
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("DEVICE HEALTH", 12f, accent(), false);
        title.setLetterSpacing(0.15f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = text(DeviceProfile.get(this).name.toUpperCase()
                + "  ·  " + Loc.s(this, "BATTERY & POWER"), 8.5f, MUTED, false);
        sub.setLetterSpacing(0.12f);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusChip = text("READING", 9f, TEXT, false);
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusChip.setBackground(Ui.rounded(0x24FFFFFF, dp(11), 0x33FFFFFF, dp(1)));
        header.addView(statusChip);

        closeChip = text("\u00d7", 22f, 0xCCFFFFFF, false);
        closeChip.setGravity(Gravity.CENTER);
        closeChip.setIncludeFontPadding(false);
        closeChip.setPadding(0, 0, 0, 0);
        closeChip.setContentDescription("Close device health");
        closeChip.setBackground(navBackground(0x00000000, 12, 0x00000000, 0));
        closeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                closeHealth();
            }
        });
        header.addView(closeChip, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return header;
    }

    private void buildHero(LinearLayout left) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(12), dp(9), dp(12), dp(9));
        hero.setBackground(Ui.rounded(CARD, dp(18), CARD_BORDER, dp(1)));
        left.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout packHead = new LinearLayout(this);
        packHead.setOrientation(LinearLayout.HORIZONTAL);
        packHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView caption = text("BATTERY PACK", 8.5f, MUTED, false);
        caption.setLetterSpacing(0.14f);
        packHead.addView(caption, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        demoChip = text("DEMO", 7.5f, 0xCCFFFFFF, false);
        demoChip.setGravity(Gravity.CENTER);
        demoChip.setPadding(dp(9), dp(3), dp(9), dp(3));
        demoChip.setBackground(navBackground(0x1AFFFFFF, 8, 0x33FFFFFF, 1));
        demoChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                if (demoActive) stopDemo(); else startDemo();
            }
        });
        packHead.addView(demoChip);
        hero.addView(packHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        batteryView = new BatteryPackView(this);
        // The sheet has one page: draw the pack even while the open animation is
        // still placing it, otherwise it can stay blank until the first poll.
        batteryView.setAlwaysDraw(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(2);
        hero.addView(batteryView, blp);

        LinearLayout levelRow = new LinearLayout(this);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        levelRow.setGravity(Gravity.BOTTOM);
        levelText = text("--%", 38f, TEXT, false);
        levelText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        levelRow.addView(levelText);
        levelSub = text("  -- / -- mAh", 11f, MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        subLp.leftMargin = dp(8);
        subLp.bottomMargin = dp(4);
        levelRow.addView(levelSub, subLp);
        hero.addView(levelRow);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        heroHealth = statTile(stats, "HEALTH", "--", 1f);
        heroCycles = statTile(stats, "CYCLES", "--", 1f);
        heroTemp = statTile(stats, "TEMP", "--", 1f);
        hero.addView(stats, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        serverState = text(Loc.s(this, "PServer: checking…"), 8f, 0x66FFFFFF, false);
        serverState.setPadding(0, dp(5), 0, 0);
        hero.addView(serverState);
    }

    private TextView statTile(LinearLayout parent, String label, String value, float weight) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(8), dp(6), dp(8), dp(6));
        tile.setBackground(Ui.rounded(0x16FFFFFF, dp(11), 0x1FFFFFFF, dp(1)));
        TextView l = text(label, 7.5f, MUTED, false);
        l.setLetterSpacing(0.12f);
        TextView v = text(value, 13f, TEXT, false);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tile.addView(l);
        tile.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(4);
        parent.addView(tile, lp);
        return v;
    }

    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        boolean dhTight = Ui.widthDp(this) < 700;
        card.setPadding(dp(dhTight ? 10 : 12), dp(dhTight ? 6 : 8),
                dp(dhTight ? 10 : 12), dp(dhTight ? 7 : 9));
        card.setBackground(Ui.rounded(CARD, dp(15), CARD_BORDER, dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(Ui.widthDp(this) < 700 ? 5 : 7);
        parent.addView(card, lp);
        TextView heading = text(title, Ui.widthDp(this) < 700 ? 8f : 8.5f,
                accent(), false);
        heading.setLetterSpacing(0.14f);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(0, 0, 0, dp(3));
        card.addView(heading);
        return card;
    }

    private TextView row(LinearLayout parent, String label, String value) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        boolean tight = Ui.widthDp(this) < 700;
        line.setPadding(0, dp(tight ? 1 : 2), 0, dp(tight ? 1 : 2));
        TextView l = text(label, tight ? 9.5f : 10f, MUTED, false);
        line.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, tight ? 10f : 10.5f, TEXT, false);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        line.addView(v);
        parent.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private void buildBatterySection(LinearLayout right) {
        LinearLayout card = section(right, "BATTERY STATUS");
        vStatus = row(card, "State", "--");
        vHealth = row(card, "Health", "--");
        vCycles = row(card, "Charge cycles", "--");
        vTemp = row(card, "Temperature", "--");
        vVoltage = row(card, "Voltage", "--");
        vCurrent = row(card, "Current", "--");
        vPower = row(card, "Power", "--");
        vChargeType = row(card, "Charge type", "--");
        vTimeFull = row(card, "Time to full", "--");
        vTimeEmpty = row(card, "Time to empty", "--");
    }

    private void buildCapacitySection(LinearLayout right) {
        LinearLayout card = section(right, "CAPACITY & WEAR");
        vDesign = row(card, "Design capacity", "--");
        vFull = row(card, "Full charge capacity", "--");
        vCounter = row(card, "Charge counter", "--");
        vWear = row(card, "Estimated wear", "--");
        vSoh = row(card, "State of health (SOH)", "--");
        vResistance = row(card, "Internal resistance", "--");
    }

    private void buildChargerSection(LinearLayout right) {
        LinearLayout card = section(right, "CHARGER");
        vConn = row(card, "Connection", "--");
        vInput = row(card, "Input", "--");
        vInputPower = row(card, "Input power", "--");
        vInputLimit = row(card, "Input limit", "--");
        vPd = row(card, "Power delivery", "--");
        vPath = row(card, "Charge path", "--");

    }

    private void buildChargingSection(LinearLayout right) {
        LinearLayout card = section(right, "CHARGING CONTROL");

        LinearLayout limitHead = new LinearLayout(this);
        limitHead.setOrientation(LinearLayout.HORIZONTAL);
        limitHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView limitLabel = text("Charge limit", 10f, TEXT, false);
        limitHead.addView(limitLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        limitValue = text(store.limitPercent() + "%", 10.5f, accent(), false);
        limitValue.setPadding(0, 0, dp(8), 0);
        limitHead.addView(limitValue);
        limitChip = text("OFF", 8.5f, TEXT, false);
        limitChip.setGravity(Gravity.CENTER);
        limitChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        limitChip.setBackground(navBackground(0x22FFFFFF, 10, 0x33FFFFFF, 1));
        limitChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !(store.limitEnabled() && store.limitPercent() < 100);
                if (on && store.limitPercent() >= 100) {
                    toast(Loc.s(DeviceHealthActivity.this, "Set a level below 100% first"));
                    return;
                }
                store.setLimitEnabled(on);
                applyLimit(on, true);
                syncChips();
            }
        });
        limitHead.addView(limitChip);
        card.addView(limitHead);

        limitBar = seekBar(50, 100, store.limitPercent());
        limitBar.setBackground(navBackground(0x00000000, 10, 0x00000000, 0));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        llp.topMargin = dp(3);
        card.addView(limitBar, llp);
        limitHint = text(String.format(Loc.displayLocale(DeviceHealthActivity.this),
                Loc.s(this, "Stops charging at %d%% and runs from the cable."),
                store.limitPercent()), 8.5f, 0x66FFFFFF, false);
        card.addView(limitHint);
        limitBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int pct = 50 + progress * 5;
                store.setLimitPercent(pct);
                limitValue.setText(pct + "%");
                if (store.limitEnabled() && pct < 100) {
                    BatteryGuardService.start(DeviceHealthActivity.this);
                }
                limitHint.setText(pct >= 100
                        ? Loc.s(DeviceHealthActivity.this, "No ceiling: the battery charges to full.")
                        : String.format(Loc.displayLocale(DeviceHealthActivity.this),
                                Loc.s(DeviceHealthActivity.this,
                                        "Stops charging at %d%% and runs from the cable."), pct));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (store.limitPercent() >= 100) {
                    // 100% means "no ceiling": leave the battery free to fill.
                    store.setLimitEnabled(false);
                    applyLimit(false, false);
                    syncChips();
                    toast(Loc.s(DeviceHealthActivity.this, "Charge limit off \u00b7 charging to full"));
                    return;
                }
                if (!store.limitEnabled()) {
                    store.setLimitEnabled(true);
                    BatteryGuardService.start(DeviceHealthActivity.this);
                    if (snap != null && snap.level >= store.limitPercent()) {
                        DeviceHealthController.setSeparation(DeviceHealthActivity.this, true);
                    }
                    syncChips();
                }
            }
        });
        limitNode = text(Loc.s(this, "Charge limit is enforced by charge separation."),
                8.5f, 0x66FFFFFF, false);
        card.addView(limitNode);

        LinearLayout sepHead = new LinearLayout(this);
        sepHead.setOrientation(LinearLayout.HORIZONTAL);
        sepHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView sepLabel = text("Charge separation", 10f, TEXT, false);
        sepHead.addView(sepLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sepChip = text("OFF", 8.5f, TEXT, false);
        sepChip.setGravity(Gravity.CENTER);
        sepChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        sepChip.setBackground(navBackground(0x22FFFFFF, 10, 0x33FFFFFF, 1));
        sepChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !(snap != null && snap.separated);
                if (store.autoSeparation()) {
                    store.setAutoSeparation(false);
                    BatteryGuardService.stop(DeviceHealthActivity.this);
                }
                store.setSeparated(on);
                applySeparation(on, true);
                syncChips();
            }
        });
        sepHead.addView(sepChip);
        card.addView(sepHead);
        TextView sepHint = text("Runs the device from the charger without charging the battery.",
                8.5f, 0x66FFFFFF, false);
        sepHint.setPadding(0, dp(2), 0, dp(6));
        card.addView(sepHint);

        LinearLayout autoHead = new LinearLayout(this);
        autoHead.setOrientation(LinearLayout.HORIZONTAL);
        autoHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView autoLabel = text("Auto separation", 10f, TEXT, false);
        autoHead.addView(autoLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        autoValue = text("80%", 10.5f, accent(), false);
        autoValue.setPadding(0, 0, dp(8), 0);
        autoHead.addView(autoValue);
        autoChip = text("OFF", 8.5f, TEXT, false);
        autoChip.setGravity(Gravity.CENTER);
        autoChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        autoChip.setBackground(navBackground(0x22FFFFFF, 10, 0x33FFFFFF, 1));
        autoChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !store.autoSeparation();
                store.setAutoSeparation(on);
                if (on) BatteryGuardService.start(DeviceHealthActivity.this);
                else BatteryGuardService.stop(DeviceHealthActivity.this);
                syncChips();
                if (on) {
                    toast(Loc.s(DeviceHealthActivity.this, "Automatic separation at ")
                            + store.autoLevel() + "%");
                }
            }
        });
        autoHead.addView(autoChip);
        card.addView(autoHead);

        autoBar = seekBar(50, 100, store.autoLevel());
        autoBar.setBackground(navBackground(0x00000000, 10, 0x00000000, 0));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        alp.topMargin = dp(3);
        card.addView(autoBar, alp);
        autoHint = text("Enables separation automatically when charging reaches this level.",
                8.5f, 0x66FFFFFF, false);
        card.addView(autoHint);
    }

    private void buildThermalSection(LinearLayout right) {
        LinearLayout card = section(right, "THERMALS");
        vCpu = row(card, "CPU", "--");
        vGpu = row(card, "GPU", "--");
        vBatteryTemp = row(card, "Battery", "--");
    }

    private void buildDeviceSection(LinearLayout right) {
        LinearLayout card = section(right, "DEVICE");
        vModel = row(card, "Model", "--");
        vAndroid = row(card, "Android", "--");
        vBatteryModel = row(card, "Battery model", "--");
        vUsb = row(card, "USB / charger", "--");
        vCcLimit = row(card, "Charge current limit", "--");
    }

    private SeekBar seekBar(int min, int max, int current) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(10);
        bar.setProgress(Math.max(0, Math.min(10,
                Math.round((current - min) / 5f))));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent()));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent()));
        bar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        bar.setPadding(dp(8), 0, dp(8), 0);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppress || !fromUser) return;
                int value = min + progress * 5;
                autoValue.setText(value + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = min + seekBar.getProgress() * 5;
                store.setAutoLevel(value);
                autoValue.setText(value + "%");
                if (store.autoSeparation()) {
                    BatteryGuardService.start(DeviceHealthActivity.this);
                    toast(Loc.s(DeviceHealthActivity.this, "Auto separation target: ")
                            + value + "%");
                }
            }
        });
        return bar;
    }

    // ------------------------------------------------------------------
    // Live update and controls
    // ------------------------------------------------------------------

    private void startDemo() {
        if (demoActive) return;
        demoActive = true;
        demoStart = SystemClock.uptimeMillis();
        demoLevel = 22f;
        demoChip.setText("STOP");
        demoChip.setTextColor(0xFF061015);
        demoChip.setBackground(navBackground(accent(), 8, 0x00000000, 1));
        limitChip.setEnabled(false);
        if (limitBar != null) limitBar.setEnabled(false);
        sepChip.setEnabled(false);
        autoChip.setEnabled(false);
        autoBar.setEnabled(false);
        main.removeCallbacks(poll);
        main.removeCallbacks(demoTick);
        rightStickValue = 0f;
        rightStickRepeatActive = false;
        main.removeCallbacks(rightStickRepeat);
        main.post(demoTick);
    }

    private void stopDemo() {
        if (!demoActive) return;
        demoActive = false;
        main.removeCallbacks(demoTick);
        demoChip.setText("DEMO");
        demoChip.setTextColor(0xCCFFFFFF);
        demoChip.setBackground(navBackground(0x1AFFFFFF, 8, 0x33FFFFFF, 1));
        limitChip.setEnabled(true);
        if (limitBar != null) limitBar.setEnabled(true);
        sepChip.setEnabled(true);
        autoChip.setEnabled(true);
        autoBar.setEnabled(true);
        syncChips();
        if (resumed) {
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

    private Drawable navBackground(int fill, int radiusDp, int border, int borderWidthDp) {
        int focusBorder = (fill == accent() || fill == AMBER) ? 0xFFFFFFFF : accent();
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused},
                Ui.rounded(fill, dp(radiusDp), focusBorder, dp(Math.max(2, borderWidthDp))));
        d.addState(new int[]{},
                Ui.rounded(fill, dp(radiusDp), border, dp(borderWidthDp)));
        return d;
    }

    private void setupControllerNav() {
        nav.clear();
        addNav(demoChip);
        addNav(limitChip);
        addNav(sepChip);
        addNav(autoChip);
        addNav(autoBar);
        addNav(closeChip);
    }

    private void addNav(final View v) {
        if (v == null) return;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                if (focused) ensureVisible(view);
            }
        });
        nav.add(v);
    }

    private void ensureVisible(View v) {
        if (scrollView == null) return;
        try {
            Rect r = new Rect();
            v.getDrawingRect(r);
            scrollView.offsetDescendantRectToMyCoords(v, r);
            int scrollY = scrollView.getScrollY();
            int pad = dp(14);
            if (r.top < scrollY + pad) {
                scrollView.smoothScrollTo(0, Math.max(0, r.top - pad));
            } else if (r.bottom > scrollY + scrollView.getHeight() - pad) {
                scrollView.smoothScrollTo(0,
                        r.bottom - scrollView.getHeight() + pad);
            }
        } catch (Throwable ignored) {
        }
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
                ensureVisible(candidate);
                return;
            }
        }
    }

    private void activateNav() {
        View focused = getCurrentFocus();
        if (focused == null || !nav.contains(focused)) {
            moveNav(1);
            return;
        }
        if (focused == autoBar) {
            toast(Loc.s(DeviceHealthActivity.this,
                    "Use left / right to adjust the auto-separation target"));
            return;
        }
        focused.performClick();
    }

    private void adjustAutoLevel(int delta) {
        int value = Math.max(50, Math.min(100, store.autoLevel() + delta));
        if (value == store.autoLevel()) return;
        store.setAutoLevel(value);
        suppress = true;
        autoBar.setProgress(Math.max(0, Math.min(10, (value - 50) / 5)));
        suppress = false;
        autoValue.setText(value + "%");
        syncChips();
        if (store.autoSeparation()) BatteryGuardService.start(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ControllerNav.isSyntheticStickDpad(event)) return true;
        ControllerNav.noteExternalDpad(event);
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getRepeatCount() == 0) closeHealth();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_A
                    || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) activateNav();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveNav(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                moveNav(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (getCurrentFocus() == autoBar) adjustAutoLevel(5);
                else moveNav(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (getCurrentFocus() == autoBar) adjustAutoLevel(-5);
                else moveNav(-1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE
                && (event.getSource() & (InputDevice.SOURCE_JOYSTICK
                | InputDevice.SOURCE_GAMEPAD)) != 0) {
            long now = SystemClock.uptimeMillis();

            // Left stick: focus navigation only. It must never move the
            // auto-separation slider.
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < 0.2f && Math.abs(y) < 0.2f) {
                x = hatX;
                y = hatY;
            }
            if (Math.abs(x) > 0.60f || Math.abs(y) > 0.60f) {
                rightStickHeldSince = 0L;
                if (ControllerNav.externalDpadRecent()) return true;
                if (now - lastStickNav < 190L) return true;
                lastStickNav = now;
                if (Math.abs(y) >= Math.abs(x)) {
                    moveNav(y > 0f ? 1 : -1);
                } else {
                    moveNav(x > 0f ? 1 : -1);
                }
                return true;
            }

            // Right stick (ABS_Z on the Odin): the only analog control that
            // moves the auto-separation slider. A repeating task gives it fast
            // sliding instead of relying on the controller's event rate.
            float rx = event.getAxisValue(MotionEvent.AXIS_Z);
            if (Math.abs(rx) < 0.10f) {
                float rxAlt = event.getAxisValue(MotionEvent.AXIS_RX);
                if (Math.abs(rxAlt) > Math.abs(rx)) rx = rxAlt;
            }
            if (Math.abs(rx) > 0.55f) {
                if (autoBar == null || !autoBar.isEnabled()) return true;
                rightStickValue = rx;
                rightStickLastEvent = now;
                if (rightStickStart == 0L) rightStickStart = now;
                if (!rightStickRepeatActive) {
                    rightStickRepeatActive = true;
                    main.removeCallbacks(rightStickRepeat);
                    main.postDelayed(rightStickRepeat, 30L);
                }
                return true;
            }
            if (Math.abs(rx) < 0.25f) {
                rightStickValue = 0f;
                rightStickStart = 0L;
                rightStickRepeatActive = false;
                main.removeCallbacks(rightStickRepeat);
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private void update(DeviceHealthReader.Snapshot s) {
        if (s == null) return;
        snap = s;
        int level = Math.max(0, s.level);
        levelText.setText(level + "%");
        levelSub.setText("  " + capText(s.counterUah) + " / " + capText(s.designUah) + Loc.s(this, " mAh"));
        batteryView.setBattery(level, s.charging, s.plugged, s.separated, s.full);
        statusChip.setText(Loc.s(this, statusLabel(s)).toUpperCase(Locale.getDefault()));
        int statusColor = s.charging ? GREEN : s.separated ? AMBER : s.full ? accent() : MUTED;
        statusChip.setTextColor(statusColor);
        statusChip.setBackground(Ui.rounded(0x1AFFFFFF, dp(11),
                withAlpha(statusColor, 0x55), dp(1)));

        heroHealth.setText(s.healthPct >= 0 ? s.healthPct + "%" : "--");
        heroHealth.setTextColor(healthColor(s.healthPct));
        heroCycles.setText(s.cycleCount >= 0 ? String.valueOf(s.cycleCount) : "--");
        heroTemp.setText(s.tempC >= 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.0f%s", s.tempC, Loc.s(this, "°C")) : "--");
        heroTemp.setTextColor(tempColor(s.tempC));

        vStatus.setText(Loc.s(this, statusLabel(s)));
        vHealth.setText((s.health == null ? "--" : Loc.s(this, s.health))
                + (s.healthPct >= 0 ? " · " + s.healthPct + "%" : ""));
        vCycles.setText(s.cycleCount >= 0
                ? s.cycleCount + (s.cycleSource == null ? "" : " · " + s.cycleSource)
                : "--");
        vTemp.setText(s.tempC >= 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.1f %s", s.tempC, Loc.s(this, "°C")) : "--");
        vVoltage.setText(s.voltageV > 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.3f %s", s.voltageV, Loc.s(this, "V")) : "--");
        vCurrent.setText(formatCurrent(s.currentA));
        vPower.setText(s.powerW >= 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.2f %s", s.powerW, Loc.s(this, "W")) : "--");
        vChargeType.setText(s.chargeType == null ? "--" : s.chargeType);
        vTimeFull.setText(formatDuration(s.timeToFullSec));
        vTimeEmpty.setText(formatDuration(s.timeToEmptySec));

        vDesign.setText(capText(s.designUah) + Loc.s(this, " mAh"));
        vFull.setText(capText(s.fullUah) + Loc.s(this, " mAh"));
        vCounter.setText(capText(s.counterUah) + Loc.s(this, " mAh"));
        vWear.setText(s.healthPct >= 0 ? (100 - s.healthPct) + "%" : "--");
        vSoh.setText(s.soh >= 0 ? s.soh + "%" : "--");
        vResistance.setText(s.resistance > 0
                ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.1f %s", s.resistance, Loc.s(this, "mΩ")) : "--");

        vCpu.setText(s.cpuTempC > 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.1f %s", s.cpuTempC, Loc.s(this, "°C")) : "--");
        vGpu.setText(s.gpuTempC > 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.1f %s", s.gpuTempC, Loc.s(this, "°C")) : "--");
        vBatteryTemp.setText(s.tempC >= 0 ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.1f %s", s.tempC, Loc.s(this, "°C")) : "--");

        vModel.setText(String.valueOf(android.os.Build.MANUFACTURER) + " "
                + String.valueOf(android.os.Build.MODEL));
        vAndroid.setText(Loc.s(this, "Android ") + android.os.Build.VERSION.RELEASE
                + "  ·  " + android.os.Build.ID);
        String batteryModel = s.model == null ? "--" : s.model;
        if (batteryModel.toUpperCase(Locale.US).contains("8000MAH")) {
            batteryModel = "Quectel 8000 mAh";
        } else if (batteryModel.length() > 32) {
            batteryModel = batteryModel.substring(0, 29) + "…";
        }
        vBatteryModel.setText(batteryModel);
        String usb = (s.usbType == null ? "--" : s.usbType)
                + (s.usbChargeNow == 1 ? "  ·  charging path" : "");
        vUsb.setText(usb);
        vCcLimit.setText(s.chargeControlLimit > 0
                ? String.format(Loc.displayLocale(DeviceHealthActivity.this),
                "%.2f %s / %.2f %s", s.chargeControlLimit / 1_000_000f,
                Loc.s(this, "A"), s.chargeControlLimitMax / 1_000_000f,
                Loc.s(this, "A"))
                : "--");

        String connType = s.inputType == null || "--".equals(s.inputType)
                ? "USB-C" : s.inputType;
        vConn.setText(s.plugged ? connType : "Not connected");
        vInput.setText(s.inputVoltageV > 0f
                ? String.format(Loc.displayLocale(DeviceHealthActivity.this),
                "%.2f %s  ·  %.2f %s", s.inputVoltageV, Loc.s(this, "V"),
                s.inputCurrentA, Loc.s(this, "A"))
                : "--");
        vInputPower.setText(s.inputVoltageV > 0f && s.inputCurrentA > 0f
                ? String.format(Loc.displayLocale(DeviceHealthActivity.this),
                "%.1f %s", s.inputVoltageV * s.inputCurrentA, Loc.s(this, "W"))
                : "--");
        vInputLimit.setText(s.inputLimitA > 0f
                ? String.format(Loc.displayLocale(DeviceHealthActivity.this), "%.2f %s", s.inputLimitA, Loc.s(this, "A")) : "--");
        vPd.setText(!s.plugged ? "--"
                : s.pdSupported ? "USB-PD " + s.pdRevision
                : Loc.s(this, "Not offered by charger"));
        vPath.setText(!s.plugged ? Loc.s(this, "Disconnected")
                : s.separated ? Loc.s(this, "Separation \u00b7 from cable")
                : s.charging ? Loc.s(this, "Battery charging")
                : Loc.s(this, "Connected \u00b7 idle"));

        updateLimitTexts();
        serverState.setText(s.pserver
                ? Loc.s(this, "PServer ready  ·  privileged battery controls")
                : Loc.s(this, "PServer unavailable  ·  read-only battery data"));

        if (initialSync) {
            initialSync = false;
            // Hardware separation may have been set by the guard, so it must not
            // be mistaken for the user's manual separation preference.
        }
        if (!applying) {
            suppress = true;
            autoBar.setProgress(Math.max(0, Math.min(10,
                    Math.round((store.autoLevel() - 50) / 5f))));
            suppress = false;
            if (!limitBar.isPressed()) updateLimitTexts();
            autoValue.setText(store.autoLevel() + "%");
            limitBar.setProgress(Math.max(0, Math.min(10,
                    Math.round((store.limitPercent() - 50) / 5f))));
            limitChip.setEnabled(true);
        }
        syncChips();
    }

    private void updateLimitTexts() {
        if (limitValue == null) return;
        int pct = store.limitPercent();
        boolean on = store.limitEnabled() && pct < 100;
        limitValue.setText(pct + "%");
        limitHint.setText(!on
                ? (pct >= 100
                        ? Loc.s(this, "No ceiling: the battery charges to full.")
                        : Loc.s(this, "Off \u00b7 slide to set a ceiling."))
                : String.format(Loc.displayLocale(DeviceHealthActivity.this),
                        Loc.s(this, "Stops charging at %d%% and runs from the cable."), pct));
        limitNode.setText(!on ? Loc.s(this, "Charge limit off")
                : snap != null && snap.separated
                        ? String.format(Loc.displayLocale(DeviceHealthActivity.this),
                                Loc.s(this, "Charge separation active \u00b7 holding %d%%"), pct)
                        : String.format(Loc.displayLocale(DeviceHealthActivity.this),
                                Loc.s(this, "Guard armed \u00b7 stops at %d%%"), pct));
    }

    private void syncChips() {
        if (limitChip == null) return;
        setChip(limitChip, store.limitEnabled() && store.limitPercent() < 100, accent());
        updateLimitTexts();
        setChip(sepChip, snap != null && snap.separated, AMBER);
        setChip(autoChip, store.autoSeparation(), accent());
        sepChip.setEnabled(!store.autoSeparation() || (snap != null && snap.separated));
        autoHint.setText(store.autoSeparation()
                ? Loc.s(this, "Active while charging · target ") + store.autoLevel() + "%"
                : Loc.s(this, "Enables separation automatically when charging reaches this level."));
    }

    private void setChip(TextView chip, boolean on, int color) {
        chip.setText(on ? "ON" : "OFF");
        chip.setTextColor(on ? 0xFF061015 : 0xCCFFFFFF);
        chip.setBackground(navBackground(on ? color : 0x22FFFFFF, 10,
                on ? 0x00000000 : 0x33FFFFFF, 1));
    }

    private void applyLimit(boolean enabled, boolean feedback) {
        applying = true;
        final int pct = store.limitPercent();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                // The Odin only offers a fixed 80% hardware threshold, so an
                // arbitrary ceiling is held by the guard switching the charge
                // path to separation at the requested level.
                boolean ok = true;
                if (enabled && pct < 100 && snap != null && snap.plugged
                        && snap.level >= pct) {
                    ok = DeviceHealthController.setSeparation(
                            DeviceHealthActivity.this, true).ok;
                }
                if (!enabled) {
                    if (!store.autoSeparation()) {
                        if (!store.separated()) {
                            DeviceHealthController.setSeparation(
                                    DeviceHealthActivity.this, false);
                        }
                        BatteryGuardService.stop(DeviceHealthActivity.this);
                    }
                } else if (pct < 100) {
                    BatteryGuardService.start(DeviceHealthActivity.this);
                } else if (!store.autoSeparation()) {
                    BatteryGuardService.stop(DeviceHealthActivity.this);
                }
                final boolean success = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        applying = false;
                        if (!success && store.limitEnabled()) {
                            store.setLimitEnabled(false);
                            syncChips();
                        }
                        if (feedback) {
                            toast(!enabled ? Loc.s(DeviceHealthActivity.this, "Charge limit off")
                                    : pct >= 100
                                            ? Loc.s(DeviceHealthActivity.this,
                                                    "Charge limit at 100% · charges to full")
                                            : String.format(Loc.displayLocale(DeviceHealthActivity.this),
                                                    Loc.s(DeviceHealthActivity.this,
                                                            "Charge limit at %d%% · running from the cable once reached"),
                                                    pct));
                        }
                        main.removeCallbacks(poll);
                        main.post(poll);
                    }
                });
            }
        });
    }

    private void applySeparation(boolean enabled, boolean feedback) {
        applying = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final DeviceHealthController.Result r =
                        DeviceHealthController.setSeparation(DeviceHealthActivity.this, enabled);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        applying = false;
                        if (!r.ok) {
                            store.setSeparated(snap != null && snap.separated);
                            syncChips();
                        }
                        if (feedback) toast(r.message);
                        main.removeCallbacks(poll);
                        main.post(poll);
                    }
                });
            }
        });
    }

    private String statusLabel(DeviceHealthReader.Snapshot s) {
        if (s.separated) return "Charge separated";
        if (s.charging) return "Charging";
        if (s.full || s.level >= 100) return "Full";
        if (s.plugged) return "Not charging";
        return "Discharging";
    }

    private int healthColor(int pct) {
        if (pct < 0) return TEXT;
        if (pct >= 80) return GREEN;
        if (pct >= 60) return AMBER;
        return RED;
    }

    private int tempColor(float temp) {
        if (temp < 0) return TEXT;
        if (temp < 38f) return GREEN;
        if (temp < 45f) return AMBER;
        return RED;
    }

    private String capText(long uah) {
        if (uah < 0) return "--";
        return String.format(Loc.displayLocale(DeviceHealthActivity.this), "%,d", uah / 1000);
    }

    private String formatCurrent(float amps) {
        if (amps == 0f) return "--";
        return String.format(Loc.displayLocale(DeviceHealthActivity.this), "%+.0f %s", amps * 1000f, Loc.s(this, "mA"));
    }

    private String formatDuration(long seconds) {
        if (seconds <= 0 || seconds > 86_400L * 2) return "--";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0) return h + Loc.s(this, "h") + " " + m + Loc.s(this, "m");
        return m + Loc.s(this, "m");
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private void toast(String text) {
        if (!TextUtils.isEmpty(text)) {
            LToast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }
}
