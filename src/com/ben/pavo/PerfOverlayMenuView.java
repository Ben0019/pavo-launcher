package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen dim + liquid-glass control card shown by long-pressing the performance
 * overlay. It is a separate WindowManager surface so it can appear above the game.
 */
final class PerfOverlayMenuView extends FrameLayout {
    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x80FFFFFF;

    interface Listener {
        void onDismiss();
        void onOverlayEnabled(boolean enabled);
        void onMetricsChanged();
        void onRefreshChanged(int ms);
        void onOpacityChanged(int percent);
        void onScaleChanged(float scale);
        void onPerformanceMode(int mode);
        void onResetPosition();
        void onEditPlacement();
        void onMapController();
        void onClearController();
        void onMapDrawerController();
        void onClearDrawerController();
        void onShowDetails();
    }

    private final Prefs prefs;
    private final Listener listener;
    private final List<TextView> modeChips = new ArrayList<>();
    private int performanceMode;
    private int opacityPercent;
    private int refreshMs;
    private float overlayScale;
    private TextView opacityValue;
    private TextView scaleValue;
    private TextView editPlacementButton;
    private TextView editPlacementHint;
    private TextView bottomHint;
    private boolean mapperMode = true;
    private long touchesBlockedUntil;
    private GlassPanel card;
    private final List<View> navItems = new ArrayList<>();
    private int navIndex = -1;
    private FocusRingDrawable navRing;
    private LinearLayout contentBox;
    private long lastNavAt;
    private float stickX, stickY;
    private int stickAxis, stickDir = -1, stickHeld = -1;
    private float dragDownRawX, dragDownRawY;
    private float dragStartTranslationX, dragStartTranslationY;

    private final View.OnTouchListener menuDrag = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragDownRawX = e.getRawX();
                    dragDownRawY = e.getRawY();
                    dragStartTranslationX = card != null ? card.getTranslationX() : 0f;
                    dragStartTranslationY = card != null ? card.getTranslationY() : 0f;
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    moveCard(dragStartTranslationX + e.getRawX() - dragDownRawX,
                            dragStartTranslationY + e.getRawY() - dragDownRawY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
            }
            return false;
        }
    };

    PerfOverlayMenuView(Context c, Prefs prefs, int performanceMode, float overlayScale,
                        Listener listener) {
        this(c, null, prefs, performanceMode, overlayScale, listener);
    }

    PerfOverlayMenuView(Context c, Backdrop backdrop, Prefs prefs, int performanceMode,
                        float overlayScale, Listener listener) {
        super(c);
        ThemeColors.initialize(c);
        accent = ThemeColors.accent();
        this.prefs = prefs;
        this.performanceMode = performanceMode;
        this.overlayScale = overlayScale;
        this.listener = listener;
        this.opacityPercent = prefs.perfOverlayAlpha();
        this.refreshMs = prefs.perfRefreshMs();
        setClickable(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        View scrim = new View(c);
        scrim.setBackgroundColor(0x00000000);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onDismiss();
            }
        });
        addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int cardW = Math.min(dp(205), screenW - dp(24));
        // Leave room above/below so the card can actually be dragged around.
        int cardH = Math.min(dp(540), Math.max(dp(280), screenH - dp(200)));
        // Clear Liquid Glass, same body as the app drawer: the capture is the
        // card, the glass only tints and lenses it.
        card = new GlassPanel(c, backdrop, accent, 0x4D0B0E14, 26, true);
        card.setBackdropScreenAnchored(true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.perfOverlayAlpha() / 100f);
        card.setFrosted(backdrop == null);
        card.setClickable(true);
        card.setPadding(dp(16), dp(12), dp(10), dp(12));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, cardH, Gravity.CENTER);
        addView(card, cardLp);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(4));
        header.setOnTouchListener(menuDrag);
        header.setClickable(true);
        TextView title = text("OVERLAY CONTROLS", 10f, accent, false);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setOnTouchListener(menuDrag);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grip = text("\u283F", 17f, 0x99FFFFFF, false);
        grip.setGravity(Gravity.CENTER);
        grip.setPadding(dp(8), 0, dp(8), 0);
        grip.setContentDescription("Drag to move overlay controls");
        grip.setOnTouchListener(menuDrag);
        header.addView(grip);
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(8), 0, dp(4), 0);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onDismiss();
            }
        });
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentBox = content;
        navRing = new FocusRingDrawable(c, accent);
        content.getOverlay().add(navRing);

        TextView moveHint = text("Drag the \u283F grip or title to move this panel.", 9.5f,
                0x66FFFFFF, false);
        moveHint.setPadding(0, 0, 0, dp(2));
        content.addView(moveHint);

        addToggle(content, "Overlay", prefs.perfOverlay(), new BoolCallback() {
            @Override
            public void onChanged(boolean value) {
                listener.onOverlayEnabled(value);
            }
        });

        editPlacementButton = text("MOVE & RESIZE", 10f, 0xFF061015, false);
        editPlacementButton.setGravity(Gravity.CENTER);
        editPlacementButton.setSingleLine(true);
        editPlacementButton.setPadding(dp(10), dp(7), dp(10), dp(7));
        editPlacementButton.setBackground(Ui.rounded(accent, dp(12)));
        editPlacementButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                listener.onEditPlacement();
            }
        });
        navItems.add(editPlacementButton);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.topMargin = dp(8);
        content.addView(editPlacementButton, editLp);
        editPlacementHint = text("Drag the card to move it; pinch with two fingers to resize. Mapping pauses until you tap Done.",
                9f, 0x66FFFFFF, false);
        editPlacementHint.setPadding(0, dp(4), 0, 0);
        content.addView(editPlacementHint);

        addSection(content, "METRICS");
        addToggle(content, "CPU temperature", prefs.perfCpuTemp(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfCpuTemp(v); }
        }));
        addToggle(content, "CPU clock", prefs.perfCpuClock(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfCpuClock(v); }
        }));
        addToggle(content, "CPU load", prefs.perfCpuUsage(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfCpuUsage(v); }
        }));
        addToggle(content, "GPU temperature", prefs.perfGpuTemp(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfGpuTemp(v); }
        }));
        addToggle(content, "GPU clock", prefs.perfGpuClock(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfGpuClock(v); }
        }));
        addToggle(content, "GPU load", prefs.perfGpuUsage(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfGpuUsage(v); }
        }));
        addToggle(content, "Framerate", prefs.perfFps(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfFps(v); }
        }));
        addToggle(content, "Framerate graph", prefs.perfFpsGraph(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfFpsGraph(v); }
        }));
        addToggle(content, "Battery percentage", prefs.perfBattery(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfBattery(v); }
        }));
        addToggle(content, "Memory (RAM)", prefs.perfRam(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfRam(v); }
        }));
        addToggle(content, "Per-core clocks & load", prefs.perfCores(), metric(new BoolSetter() {
            @Override public void set(boolean v) { prefs.setPerfCores(v); }
        }));

        addSection(content, "UPDATE RATE");
        LinearLayout refreshRow = new LinearLayout(c);
        refreshRow.setOrientation(LinearLayout.HORIZONTAL);
        refreshRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView refreshLabel = text("Update every", 12f, 0xE6FFFFFF, false);
        refreshRow.addView(refreshLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refreshMinus = stepButton("\u2212");
        final TextView refreshValue = text(refreshMs + " ms", 10f, accent, true);
        refreshValue.setGravity(Gravity.CENTER);
        refreshValue.setMinWidth(dp(58));
        TextView refreshPlus = stepButton("+");
        refreshMinus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                adjustRefresh(-250);
                refreshValue.setText(refreshMs + " ms");
            }
        });
        refreshPlus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                adjustRefresh(250);
                refreshValue.setText(refreshMs + " ms");
            }
        });
        refreshRow.addView(refreshMinus);
        refreshRow.addView(refreshValue);
        refreshRow.addView(refreshPlus);
        navItems.add(refreshMinus);
        navItems.add(refreshPlus);
        content.addView(refreshRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView refreshHint = text("250 ms (0.25 s) to 10,000 ms. Faster updates use more CPU and PServer calls.",
                9.5f, 0x66FFFFFF, false);
        refreshHint.setPadding(0, dp(4), 0, 0);
        content.addView(refreshHint);

        addSection(content, "OVERLAY OPACITY");
        LinearLayout opacityRow = new LinearLayout(c);
        opacityRow.setOrientation(LinearLayout.HORIZONTAL);
        opacityRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView opacityLabel = text("Transparency", 12f, 0xE6FFFFFF, false);
        opacityRow.addView(opacityLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView minus = stepButton("\u2212");
        opacityValue = text(opacityPercent + "%", 10f, accent, true);
        opacityValue.setGravity(Gravity.CENTER);
        opacityValue.setMinWidth(dp(42));
        TextView plus = stepButton("+");
        minus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                adjustOpacity(-5);
            }
        });
        plus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                adjustOpacity(5);
            }
        });
        opacityRow.addView(minus);
        opacityRow.addView(opacityValue);
        opacityRow.addView(plus);
        navItems.add(minus);
        navItems.add(plus);
        content.addView(opacityRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addSection(content, "DEVICE PERFORMANCE MODE");
        LinearLayout modeRow = new LinearLayout(c);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] labels = {"Normal", "Standard", "High"};
        for (int i = 0; i < labels.length; i++) {
            final int mode = i;
            TextView chip = text(labels[i], 9.5f, TEXT, false);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(5), dp(6), dp(5), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < labels.length - 1 ? dp(4) : 0;
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    setPerformanceMode(mode);
                    listener.onPerformanceMode(mode);
                }
            });
            modeRow.addView(chip, lp);
            modeChips.add(chip);
            navItems.add(chip);
        }
        content.addView(modeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView modeHint = text("Uses the same Odin performance state as the Quick Settings tile.",
                9.5f, 0x66FFFFFF, false);
        modeHint.setPadding(0, dp(4), 0, 0);
        content.addView(modeHint);

        addSection(content, "CONTROLLER SHORTCUT");
        String mappedRaw = prefs.perfControllerRaw();
        String mappedName = PavoA11yService.controllerLabel(prefs);
        TextView mapButton = text("Map a button: " + mappedName, 12f, 0xE6FFFFFF, false);
        mapButton.setPadding(dp(12), dp(9), dp(12), dp(9));
        mapButton.setBackground(Ui.rounded(0x18FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
        mapButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                listener.onMapController();
            }
        });
        navItems.add(mapButton);
        content.addView(mapButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (mappedRaw != null && !mappedRaw.isEmpty()) {
            TextView clearMapping = text("Clear controller shortcut", 10f, 0x99FFFFFF, false);
            clearMapping.setPadding(dp(2), dp(7), 0, 0);
            clearMapping.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    listener.onClearController();
                }
            });
            navItems.add(clearMapping);
            content.addView(clearMapping);
        }
        TextView controllerHint = text("Single tap the mapped button to show/hide the overlay; hold it to open this menu. Mapping is consumed by the overlay.",
                9.5f, 0x66FFFFFF, false);
        controllerHint.setPadding(0, dp(4), 0, 0);
        content.addView(controllerHint);

        addSection(content, "APP DRAWER SHORTCUT");
        String drawerRaw = prefs.drawerControllerRaw();
        String drawerName = PavoA11yService.drawerControllerLabel(prefs);
        TextView drawerMap = text("Map a button: " + drawerName, 12f, 0xE6FFFFFF, false);
        drawerMap.setPadding(dp(12), dp(9), dp(12), dp(9));
        drawerMap.setBackground(Ui.rounded(0x18FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
        drawerMap.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                listener.onMapDrawerController();
            }
        });
        navItems.add(drawerMap);
        content.addView(drawerMap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (drawerRaw != null && !drawerRaw.isEmpty()) {
            TextView clearDrawer = text("Clear app drawer shortcut", 10f, 0x99FFFFFF, false);
            clearDrawer.setPadding(dp(2), dp(7), 0, 0);
            clearDrawer.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    listener.onClearDrawerController();
                }
            });
            navItems.add(clearDrawer);
            content.addView(clearDrawer);
        }
        TextView drawerHint = text("Single-press the mapped button to open the floating app drawer above any app.",
                9.5f, 0x66FFFFFF, false);
        drawerHint.setPadding(0, dp(4), 0, 0);
        content.addView(drawerHint);

        addSection(content, "POSITION & SIZE");
        LinearLayout sizeRow = new LinearLayout(c);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sizeLabel = text("Size", 12f, 0xE6FFFFFF, false);
        sizeRow.addView(sizeLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sizeMinus = stepButton("\u2212");
        scaleValue = text(Math.round(overlayScale * 100f) + "%", 10f, accent, true);
        scaleValue.setGravity(Gravity.CENTER);
        scaleValue.setMinWidth(dp(42));
        TextView sizePlus = stepButton("+");
        sizeMinus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                adjustScale(-0.1f);
            }
        });
        sizePlus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                adjustScale(0.1f);
            }
        });
        sizeRow.addView(sizeMinus);
        sizeRow.addView(scaleValue);
        sizeRow.addView(sizePlus);
        navItems.add(sizeMinus);
        navItems.add(sizePlus);
        content.addView(sizeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView reset = text("Reset overlay position", 12f, 0xE6FFFFFF, false);
        reset.setPadding(dp(12), dp(9), dp(12), dp(9));
        reset.setBackground(Ui.rounded(0x18FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
        reset.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                listener.onResetPosition();
            }
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = dp(2);
        navItems.add(reset);
        content.addView(reset, resetLp);

        bottomHint = text("Hold the performance shortcut to reopen this menu. Move & resize keeps mapping paused until Done.",
                9.5f, 0x66FFFFFF, false);
        bottomHint.setPadding(0, dp(8), 0, 0);
        content.addView(bottomHint);

        applyModeChipStyle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String raw = null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: raw = "BTN_DPAD_UP"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: raw = "BTN_DPAD_DOWN"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: raw = "BTN_DPAD_LEFT"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: raw = "BTN_DPAD_RIGHT"; break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: raw = "BTN_SOUTH"; break;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK: raw = "BTN_EAST"; break;
            default: break;
        }
        if (raw != null) {
            boolean direction = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT;
            // Android maps the stick/HAT to synthetic DPAD keys. The axis event is
            // authoritative (and keeps the correct sign), so let generic motion
            // handle those instead of double-handling a possibly inverted key.
            if (direction
                    && event.getScanCode() == 0
                    && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                handleControllerKey(raw, event.getRepeatCount() > 0);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (handleStick(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    private boolean handleStick(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) == 0
                && (source & InputDevice.SOURCE_GAMEPAD) == 0) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f) x = e.getAxisValue(MotionEvent.AXIS_X);
        if (Math.abs(y) < 0.5f) y = e.getAxisValue(MotionEvent.AXIS_Y);
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        if (ax < 0.42f && ay < 0.42f) {
            stickAxis = 0;
            stickDir = -1;
            stickHeld = -1;
            removeCallbacks(stickRepeat);
            return true;
        }
        if (stickAxis == 0) {
            if (Math.max(ax, ay) < 0.70f) return true;
            stickAxis = ax >= ay ? 1 : 2;
        } else if (stickAxis == 1) {
            if (ay > ax + 0.28f) stickAxis = 2;
        } else if (ax > ay + 0.28f) {
            stickAxis = 1;
        }
        float selected = stickAxis == 1 ? ax : ay;
        if (selected < 0.60f) {
            stickDir = -1;
            stickHeld = -1;
            removeCallbacks(stickRepeat);
            return true;
        }
        int dir = stickAxis == 1 ? (x < 0 ? 0 : 1) : (y < 0 ? 2 : 3);
        if (dir != stickDir) {
            stickDir = dir;
            navigateNav((dir == 0 || dir == 2) ? -1 : 1);
        }
        if (dir != stickHeld) {
            stickHeld = dir;
            removeCallbacks(stickRepeat);
            postDelayed(stickRepeat, 340);
        }
        return true;
    }

    private final Runnable stickRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickHeld < 0) return;
            stickDir = stickHeld;
            navigateNav((stickHeld == 0 || stickHeld == 2) ? -1 : 1);
            postDelayed(this, 150);
        }
    };

    /** Controller navigation from the accessibility raw-key listener. */
    void handleControllerKey(String key, boolean repeat) {
        int dir = 0;
        boolean select = false;
        boolean back = false;
        switch (key) {
            case "BTN_DPAD_UP":
            case "KEY_UP":
                dir = -1;
                break;
            case "BTN_DPAD_DOWN":
            case "KEY_DOWN":
                dir = 1;
                break;
            case "BTN_DPAD_LEFT":
            case "KEY_LEFT":
                dir = -1;
                break;
            case "BTN_DPAD_RIGHT":
            case "KEY_RIGHT":
                dir = 1;
                break;
            case "BTN_SOUTH":
            case "BTN_GAMEPAD":
            case "KEY_ENTER":
                select = true;
                break;
            case "BTN_EAST":
            case "KEY_BACK":
                back = true;
                break;
            default:
                return;
        }
        if (back) {
            if (!repeat) {
                Haptics.key(this);
                // Let the matching key-up reach this window before it closes; otherwise
                // the game can receive the up event and act on it.
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDismiss();
                    }
                });
            }
            return;
        }
        if (select) {
            if (!repeat) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        activateNav();
                    }
                });
            }
            return;
        }
        navigateNav(dir);
    }

    void handleControllerDirection(int dir) {
        navigateNav(dir);
    }

    private void navigateNav(int dir) {
        if (navItems.isEmpty()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastNavAt < 70L) return;
        lastNavAt = now;
        if (navIndex < 0) navIndex = 0;
        else navIndex = (navIndex + (dir < 0 ? -1 : 1) + navItems.size()) % navItems.size();
        showNavFocus();
        Haptics.tick(this);
    }

    private void activateNav() {
        if (navItems.isEmpty()) return;
        if (navIndex < 0) navIndex = 0;
        showNavFocus();
        View target = navItems.get(navIndex);
        if (target != null) target.performClick();
    }

    private void showNavFocus() {
        if (navIndex < 0 || navIndex >= navItems.size() || navRing == null) {
            hideNavFocus();
            return;
        }
        final View target = navItems.get(navIndex);
        if (target.getWidth() > 0) {
            target.requestRectangleOnScreen(new Rect(0, 0,
                    target.getWidth(), target.getHeight()), true);
        }
        post(new Runnable() {
            @Override
            public void run() {
                updateNavRing(target);
            }
        });
        postDelayed(new Runnable() {
            @Override
            public void run() {
                updateNavRing(target);
            }
        }, 80);
    }

    private void updateNavRing(View target) {
        if (navRing == null || contentBox == null || target == null
                || target.getWidth() <= 0 || target.getHeight() <= 0) {
            hideNavFocus();
            return;
        }
        int[] root = new int[2];
        int[] loc = new int[2];
        contentBox.getLocationOnScreen(root);
        target.getLocationOnScreen(loc);
        int pad = dp(2);
        int left = loc[0] - root[0] - pad;
        int top = loc[1] - root[1] - pad;
        navRing.setBounds(left, top,
                left + target.getWidth() + pad * 2, top + target.getHeight() + pad * 2);
        navRing.setRingRadius(FocusRingView.radiusFor(target, getContext()));
        navRing.invalidateSelf();
    }

    private void hideNavFocus() {
        if (navRing != null) {
            navRing.setBounds(0, 0, 0, 0);
            navRing.invalidateSelf();
        }
    }

    /** Attach the live backdrop after the card is already on screen. */
    void setBackdrop(Backdrop b) {
        if (card == null || b == null) return;
        card.setBackdrop(b);
        card.setBackdropScreenAnchored(true);
        card.setLiquidEffect(true);
        card.setFrosted(false);
        card.invalidate();
    }

    void setPerformanceMode(int mode) {
        performanceMode = Math.max(0, Math.min(2, mode));
        applyModeChipStyle();
    }

    /** The direct-touch fallback is only offered where Touch Mapper is not active. */
    void setMapperMode(boolean enabled) {
        mapperMode = enabled;
        if (editPlacementButton != null) {
            editPlacementButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
            if (enabled) {
                if (!navItems.contains(editPlacementButton)) navItems.add(editPlacementButton);
            } else {
                navItems.remove(editPlacementButton);
                if (navIndex >= navItems.size()) navIndex = navItems.size() - 1;
            }
        }
        if (editPlacementHint != null) {
            editPlacementHint.setText(enabled
                    ? "Drag the card to move it; pinch with two fingers to resize. Mapping pauses until you tap Done."
                    : "The overlay is directly draggable and pinch-resizable while it is visible.");
        }
        if (bottomHint != null) {
            bottomHint.setText(enabled
                    ? "Hold the performance shortcut to reopen this menu. Move & resize keeps mapping paused until Done."
                    : "Long-press the overlay to reopen this menu.");
        }
    }

    void blockTouchesFor(long millis) {
        touchesBlockedUntil = android.os.SystemClock.uptimeMillis() + millis;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) Haptics.tick(this);
        if (android.os.SystemClock.uptimeMillis() < touchesBlockedUntil) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Move the glass card inside the full-screen menu surface, keeping it on screen. */
    private void moveCard(float tx, float ty) {
        if (card == null || getWidth() <= 0 || getHeight() <= 0) return;
        int pad = dp(8);
        float minX = pad - card.getLeft();
        float maxX = getWidth() - pad - card.getWidth() - card.getLeft();
        float minY = pad - card.getTop();
        float maxY = getHeight() - pad - card.getHeight() - card.getTop();
        if (minX > maxX) tx = (minX + maxX) / 2f;
        else tx = Math.max(minX, Math.min(maxX, tx));
        if (minY > maxY) ty = (minY + maxY) / 2f;
        else ty = Math.max(minY, Math.min(maxY, ty));
        card.setTranslationX(tx);
        card.setTranslationY(ty);
    }

    private void applyModeChipStyle() {
        for (int i = 0; i < modeChips.size(); i++) {
            TextView chip = modeChips.get(i);
            boolean selected = i == performanceMode;
            chip.setTextColor(selected ? 0xFF061015 : 0xD9FFFFFF);
            chip.setBackground(Ui.rounded(selected ? accent : 0x18FFFFFF,
                    dp(12), selected ? 0x00000000 : 0x33FFFFFF, dp(1)));
        }
    }

    private void adjustRefresh(int delta) {
        refreshMs = Math.max(250, Math.min(10000, refreshMs + delta));
        listener.onRefreshChanged(refreshMs);
    }

    private void adjustOpacity(int delta) {
        opacityPercent = Math.max(25, Math.min(100, opacityPercent + delta));
        if (opacityValue != null) opacityValue.setText(opacityPercent + "%");
        if (card != null) card.setSurfaceAlpha(opacityPercent / 100f);
        listener.onOpacityChanged(opacityPercent);
    }

    void setCardAlpha(int percent) {
        opacityPercent = Math.max(25, Math.min(100, percent));
        if (card != null) card.setSurfaceAlpha(opacityPercent / 100f);
        if (opacityValue != null) opacityValue.setText(opacityPercent + "%");
    }

    private void adjustScale(float delta) {
        overlayScale = Math.max(0.55f, Math.min(1.8f, overlayScale + delta));
        if (scaleValue != null) scaleValue.setText(Math.round(overlayScale * 100f) + "%");
        listener.onScaleChanged(overlayScale);
    }

    private TextView stepButton(String s) {
        TextView t = text(s, 15f, 0xF2FFFFFF, false);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(32));
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        t.setBackground(Ui.rounded(0x22FFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        return t;
    }

    private void addSection(LinearLayout parent, String title) {
        TextView t = text(title, 9f, 0x66FFFFFF, false);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        parent.addView(t, lp);
    }

    private void addToggle(LinearLayout parent, String name, boolean value,
                           BoolCallback callback) {
        ToggleRow row = new ToggleRow(getContext(), name, value, callback);
        navItems.add(row);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private BoolCallback metric(final BoolSetter setter) {
        return new BoolCallback() {
            @Override
            public void onChanged(boolean value) {
                setter.set(value);
                listener.onMetricsChanged();
            }
        };
    }

    private TextView text(String s, float sp, int color, boolean mono) {
        TextView t = new LTextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private interface BoolCallback {
        void onChanged(boolean value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    /** Label and a small ON/OFF pill; tapping anywhere on the row toggles. */
    private final class ToggleRow extends LinearLayout {
        private final TextView pill;
        private boolean checked;

        ToggleRow(Context c, String label, boolean checked, final BoolCallback callback) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(2), 0, dp(2));
            this.checked = checked;

            TextView name = text(label, 12f, 0xE6FFFFFF, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            pill = text("", 9f, 0xFF061015, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(6), dp(3), dp(6), dp(3));
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    dp(40), ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.leftMargin = dp(8);
            addView(pill, pillLp);
            stylePill();
            name.setClickable(true);
            pill.setClickable(true);

            OnClickListener click = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    ToggleRow.this.checked = !ToggleRow.this.checked;
                    stylePill();
                    callback.onChanged(ToggleRow.this.checked);
                }
            };
            setOnClickListener(click);
            name.setOnClickListener(click);
            pill.setOnClickListener(click);
        }

        private void stylePill() {
            pill.setText(checked ? "ON" : "OFF");
            pill.setTextColor(checked ? 0xFF061015 : 0x99FFFFFF);
            pill.setBackground(Ui.rounded(checked ? accent : 0x18FFFFFF, dp(9)));
        }

    }

    private final class GlassCard extends LinearLayout {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float surfaceAlpha;

        GlassCard(Context c) {
            super(c);
            surfaceAlpha = prefs.settingsTransparency() / 100f;
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setClipChildren(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            int layer = -1;
            if (surfaceAlpha < 0.999f) {
                layer = canvas.saveLayerAlpha(0f, 0f, w, h,
                        (int) (255f * surfaceAlpha));
            }
            rect.set(0, 0, w, h);
            float radius = dp(22);
            bg.setColor(0xB30B0E14);
            canvas.drawRoundRect(rect, radius, radius, bg);
            LiquidGlass.draw(canvas, rect, radius, dp(7), null, null, accent);
            if (layer >= 0) canvas.restoreToCount(layer);
        }
    }
}
