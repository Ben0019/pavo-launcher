package com.ben.aurora;

import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Built-in Aurora app: maps the controller to on-screen touch controls. */
public final class TouchMapperActivity extends AuroraGlassActivity {
    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int[] RIGHT_MODES = {
            RightStick.OFF, RightStick.AIM, RightStick.SWIPE, RightStick.MOUSE,
            RightStick.JOYSTICK, RightStick.MOBA
    };

    private Prefs prefs;
    private TouchMapStore store;
    private FrameLayout rootView;
    private AppPickerStripView appPicker;
    private TouchMapEditorView editor;
    private ImageView targetIcon;
    private TextView targetLabel;
    private TextView targetPkg;
    private TextView mappingStatus;
    private TextView mappingDetail;
    private TextView toggle;
    private TextView inputChip;
    private TextView rightModeChip;
    private TextView closeChip;
    private TextView resetChip;
    private TextView chooseChip;
    private final List<View> nav = new ArrayList<>();
    private long lastStickNav;
    private TextView rightHint;
    private TextView pserverChip;
    private String target = "";
    private TouchStick stick = TouchStick.defaults();
    private boolean useStick;
    private int rightMode = RightStick.OFF;
    private TouchStick rightZone = RightStick.defaultZone(RightStick.OFF);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideHud = new Runnable() {
        @Override
        public void run() {
            hidePerformanceOverlay();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        store = new TouchMapStore(this);
        target = store.target();
        if (target.isEmpty() && !store.enabledApps().isEmpty()) {
            target = store.enabledAppList().get(0);
            store.setTarget(target);
        }
        stick = store.stick(target);
        useStick = store.stickEnabled(target);
        rightMode = store.rightMode(target);
        rightZone = store.rightZone(target, rightMode);
        buildUi();
        hideBars();
        editor.setLayout(store.layout(target), stick, useStick,
                new RightStick(rightMode, rightZone));
        updateTarget();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        hidePerformanceOverlay();
        main.removeCallbacks(hideHud);
        main.postDelayed(hideHud, 700L);
        if (store.enabled() && !target.isEmpty()) TouchMapService.start(this);
        updateStatus();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (appPicker == null) focusInitialControl();
            }
        }, 350L);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(hideHud);
        if (appPicker != null) {
            appPicker.dismiss();
            appPicker = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (appPicker != null) {
            if (appPicker.handleKey(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (ControllerNav.isSyntheticStickDpad(event)) return true;
        ControllerNav.noteExternalDpad(event);
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
                if (event.getRepeatCount() == 0) finishAnimated();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_A
                    || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                if (event.getRepeatCount() == 0) activateNav();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveNav(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveNav(-1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (appPicker != null) {
            float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float x = Math.abs(hx) > Math.abs(event.getAxisValue(MotionEvent.AXIS_X))
                    ? hx : event.getAxisValue(MotionEvent.AXIS_X);
            float y = Math.abs(hy) > Math.abs(event.getAxisValue(MotionEvent.AXIS_Y))
                    ? hy : event.getAxisValue(MotionEvent.AXIS_Y);
            appPicker.handleStick(x, y);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE
                && (event.getSource() & (InputDevice.SOURCE_JOYSTICK
                | InputDevice.SOURCE_GAMEPAD)) != 0) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            if (Math.abs(x) < 0.2f && Math.abs(y) < 0.2f) {
                x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            }
            if (Math.abs(x) < 0.2f && Math.abs(y) < 0.2f) {
                x = event.getAxisValue(MotionEvent.AXIS_Z);
                y = event.getAxisValue(MotionEvent.AXIS_RZ);
            }
            if (Math.abs(x) > 0.60f || Math.abs(y) > 0.60f) {
                long now = SystemClock.uptimeMillis();
                if (ControllerNav.externalDpadRecent()) return true;
                if (now - lastStickNav < 190L) return true;
                lastStickNav = now;
                if (Math.abs(y) >= Math.abs(x)) moveNav(y > 0f ? 1 : -1);
                else moveNav(x > 0f ? 1 : -1);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
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

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        card.setPadding(dp(22), dp(14), dp(22), dp(12));
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

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setBaselineAligned(false);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);
        column.addView(body, bodyLp);

        LinearLayout editorCard = section();
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.55f);
        editorLp.rightMargin = dp(7);
        body.addView(editorCard, editorLp);

        LinearLayout editorHeader = new LinearLayout(this);
        editorHeader.setOrientation(LinearLayout.HORIZONTAL);
        editorHeader.setGravity(Gravity.CENTER_VERTICAL);
        editorCard.addView(editorHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView editorTitle = text("SCREEN LAYOUT", 9.5f, MUTED, true);
        editorTitle.setLetterSpacing(0.14f);
        editorHeader.addView(editorTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resetChip = chip("RESET", AMBER, 0x22FFC857, 0x55FFC857);
        resetChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                List<TouchTarget> defaults = TouchTarget.defaults();
                useStick = false;
                stick = TouchStick.defaults();
                rightMode = RightStick.OFF;
                rightZone = RightStick.defaultZone(RightStick.OFF);
                if (!target.isEmpty()) {
                    store.setStickEnabled(target, false);
                    store.setRightMode(target, RightStick.OFF);
                }
                editor.setLayout(defaults, stick, useStick,
                        new RightStick(rightMode, rightZone));
                saveEditor();
                updateStatus();
            }
        });
        resetChip.setBackground(navBackground(resetChip.getBackground()));
        editorHeader.addView(resetChip);

        editor = new TouchMapEditorView(this);
        editor.setListener(new TouchMapEditorView.Listener() {
            @Override
            public void onLayoutMoved(boolean finished) {
                saveEditor();
            }
        });
        LinearLayout.LayoutParams editorLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        editorLp2.topMargin = dp(7);
        editorCard.addView(editor, editorLp2);

        TextView editorHint = text(
                "Drag each controller button onto the matching game control. "
                        + "Taps, holds and overlapping multi-touch are injected.",
                9.5f, MUTED, false);
        editorHint.setPadding(0, dp(7), 0, 0);
        editorCard.addView(editorHint);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f);
        rightLp.leftMargin = dp(7);
        body.addView(right, rightLp);

        right.addView(buildTargetCard(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mapLp.topMargin = dp(8);
        right.addView(buildMappingCard(), mapLp);

        TextView footer = text(
                "Right stick: AIM · SWIPE · MOUSE · JOY · MOBA  ·  Remapper pauses while active",
                8.5f, 0x66FFFFFF, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(7), 0, 0);
        column.addView(footer);

        setupControllerNav();
        setContentView(root);
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("TOUCH MAPPER", 19f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView subtitle = text(DeviceProfile.get(this).name.toUpperCase()
                + "  ·  CONTROLLER TO ON-SCREEN TOUCH", 9.5f, MUTED, false);
        subtitle.setLetterSpacing(0.13f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(titles, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(left, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));

        pserverChip = chip("PSERVER", accent, ThemeColors.withAlpha(0x1A), ThemeColors.withAlpha(0x44));
        left.addView(pserverChip);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL));
        closeChip = text("\u00D7", 22f, 0xFFFF8A80, false);
        closeChip.setGravity(Gravity.CENTER);
        closeChip.setPadding(0, 0, 0, 0);
        closeChip.setBackground(navBackground(
                new ColorDrawable(Color.TRANSPARENT)));
        closeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                finishAnimated();
            }
        });
        controls.addView(closeChip, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return header;
    }

    private View buildTargetCard() {
        LinearLayout card = section();
        TextView title = text("TARGET GAME", 9.5f, MUTED, true);
        title.setLetterSpacing(0.14f);
        card.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        card.addView(row, rowLp);

        boolean tight = Ui.widthDp(this) < 700;
        targetIcon = new ImageView(this);
        targetIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = tight ? 36 : 44;
        row.addView(targetIcon, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(9), 0, dp(7), 0);
        row.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        targetLabel = text("NO GAME SELECTED", tight ? 10.5f : 12.5f, TEXT, true);
        if (targetLabel instanceof LTextView) ((LTextView) targetLabel).setLocalize(false);
        targetLabel.setSingleLine(true);
        targetLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(targetLabel);
        targetPkg = text("Choose a game without controller support",
                tight ? 7.5f : 8.5f, MUTED, false);
        targetPkg.setSingleLine(true);
        targetPkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(targetPkg);

        chooseChip = chip("CHOOSE", accent, ThemeColors.withAlpha(0x1A), ThemeColors.withAlpha(0x44));
        chooseChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openAppPicker();
            }
        });
        chooseChip.setBackground(navBackground(chooseChip.getBackground()));
        row.addView(chooseChip);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openAppPicker();
            }
        });
        return card;
    }

    private View buildMappingCard() {
        LinearLayout card = section();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);
        TextView title = text("MAPPING", 9.5f, MUTED, true);
        title.setLetterSpacing(0.14f);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mappingStatus = text("OFF", 9.5f, AMBER, true);
        mappingStatus.setLetterSpacing(0.10f);
        top.addView(mappingStatus);

        mappingDetail = text("Ready", 11f, TEXT, false);
        mappingDetail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        detailLp.topMargin = dp(8);
        card.addView(mappingDetail, detailLp);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(7);
        card.addView(inputRow, inputLp);
        TextView inputLabel = text("MOVEMENT", 9f, MUTED, true);
        inputLabel.setLetterSpacing(0.12f);
        inputRow.addView(inputLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        inputChip = chip("D-PAD", AMBER, 0x22FFC857, 0x55FFC857);
        inputChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                toggleMovementInput();
            }
        });
        inputRow.addView(inputChip);

        LinearLayout rightRow = new LinearLayout(this);
        rightRow.setOrientation(LinearLayout.HORIZONTAL);
        rightRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rightLp.topMargin = dp(6);
        card.addView(rightRow, rightLp);
        TextView rightLabel = text("RIGHT STICK", 9f, MUTED, true);
        rightLabel.setLetterSpacing(0.12f);
        rightRow.addView(rightLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rightModeChip = chip("OFF", 0xFFB99CFF, 0x22B99CFF, 0x55B99CFF);
        rightModeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                cycleRightMode();
            }
        });
        rightRow.addView(rightModeChip);

        rightHint = text("Right stick is unused.", 9f, 0x99FFFFFF, false);
        rightHint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams rightHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rightHintLp.topMargin = dp(3);
        card.addView(rightHint, rightHintLp);

        toggle = chip("ENABLE IN GAME", GREEN, 0x2669D98B, 0x5569D98B);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(dp(12), dp(11), dp(12), dp(11));
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                toggleMapping();
            }
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.topMargin = dp(8);
        card.addView(toggle, toggleLp);

        TextView help = text(
                "Each button is an independent finger. Holds and overlapping "
                        + "movement/action touches work in the game.",
                9.5f, 0x99FFFFFF, false);
        help.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(9);
        card.addView(help, helpLp);
        return card;
    }

    private View buildInfoCard() {
        LinearLayout card = section();
        TextView title = text("HOW IT WORKS", 9.5f, MUTED, true);
        title.setLetterSpacing(0.14f);
        card.addView(title);
        String[] lines = {
                "A / B / X / Y become independent touch taps and holds.",
                "The D-pad becomes four on-screen touch points.",
                "Each button gets its own finger, so movement and actions overlap.",
                "Switch MOVEMENT to VIRTUAL STICK for analog games; the left stick drives it.",
                "The game only sees the virtual touchscreen, not a controller."
        };
        for (String line : lines) {
            TextView t = text("·  " + line, 9.5f, 0xCCFFFFFF, false);
            t.setLineSpacing(dp(2), 1f);
            t.setPadding(0, dp(5), 0, 0);
            card.addView(t);
        }
        return card;
    }

    private void openAppPicker() {
        if (appPicker != null || rootView == null) return;
        GlassPanel baseCard = AuroraGlassActivity.largestGlassPanel(rootView);
        appPicker = new AppPickerStripView(this, prefs, target, baseCard,
                new AppPickerStripView.Listener() {
                    @Override
                    public void onPick(String pkg, Apps.Entry entry) {
                        appPicker = null;
                        selectTarget(pkg);
                        focusInitialControl();
                    }

                    @Override
                    public void onClose() {
                        appPicker = null;
                        focusInitialControl();
                    }
                });
        rootView.addView(appPicker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appPicker.bringToFront();
    }

    private void selectTarget(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            LToast.makeText(this, "Choose an installed game", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pkg.equals(target)) return;
        if (!target.isEmpty()) {
            store.saveLayout(target, editor.targets());
            store.saveStick(target, editor.stick());
            RightStick oldRight = editor.rightStick();
            store.saveRightZone(target, oldRight.mode, oldRight.zone);
        }
        target = pkg;
        store.setTarget(target);
        stick = store.stick(target);
        useStick = store.stickEnabled(target);
        rightMode = store.rightMode(target);
        rightZone = store.rightZone(target, rightMode);
        editor.setLayout(store.layout(target), stick, useStick,
                new RightStick(rightMode, rightZone));
        updateTarget();
        updateStatus();
        if (store.enabled()) {
            TouchMapService.start(this);
            TouchMapService.refresh(this);
        }
    }

    private void toggleMapping() {
        if (target.isEmpty()) {
            LToast.makeText(this, "Choose a target game first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!store.isAppEnabled(target)) {
            if (!PServer.available()) {
                LToast.makeText(this, "PServerBinder is unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            saveEditor();
            store.setAppEnabled(target, true);
            store.setTarget(target);
            TouchMapService.start(this);
            TouchMapService.refresh(this);
        } else {
            saveEditor();
            store.setAppEnabled(target, false);
            if (store.enabled()) TouchMapService.refresh(this);
            else TouchMapService.stop(this);
        }
        updateStatus();
    }

    private void toggleMovementInput() {
        if (target.isEmpty()) {
            LToast.makeText(this, "Choose a target game first", Toast.LENGTH_SHORT).show();
            return;
        }
        useStick = !useStick;
        store.setStickEnabled(target, useStick);
        editor.setLayout(editor.targets(), stick, useStick,
                new RightStick(rightMode, rightZone));
        saveEditor();
        updateStatus();
    }

    private void cycleRightMode() {
        if (target.isEmpty()) {
            LToast.makeText(this, "Choose a target game first", Toast.LENGTH_SHORT).show();
            return;
        }
        RightStick current = editor.rightStick();
        if (current.mode != RightStick.OFF) {
            store.saveRightZone(target, current.mode, current.zone);
        }
        int index = 0;
        for (int i = 0; i < RIGHT_MODES.length; i++) {
            if (RIGHT_MODES[i] == rightMode) {
                index = i;
                break;
            }
        }
        rightMode = RIGHT_MODES[(index + 1) % RIGHT_MODES.length];
        rightZone = store.rightZone(target, rightMode);
        store.setRightMode(target, rightMode);
        editor.setLayout(editor.targets(), stick, useStick,
                new RightStick(rightMode, rightZone));
        saveEditor();
        updateStatus();
    }

    private void saveEditor() {
        if (target.isEmpty()) return;
        stick = editor.stick();
        RightStick right = editor.rightStick();
        rightMode = right.mode;
        rightZone = right.zone;
        store.saveLayout(target, editor.targets());
        store.saveStick(target, stick);
        store.saveRightZone(target, rightMode, rightZone);
        if (store.enabled()) TouchMapService.refresh(this);
        updateTarget();
    }

    private void updateTarget() {
        if (targetIcon == null) return;
        String label = "NO GAME SELECTED";
        String detail = "Choose a game without controller support";
        Bitmap icon = null;
        if (!target.isEmpty()) {
            try {
                PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo info = pm.getApplicationInfo(target, 0);
                label = String.valueOf(pm.getApplicationLabel(info));
                detail = target;
                icon = Apps.drawableToBitmap(pm.getApplicationIcon(info), dp(88));
            } catch (Throwable t) {
                label = target;
                detail = "Installed app not found";
            }
        }
        if (icon == null) {
            icon = Apps.letterTile(label, dp(88));
        }
        targetIcon.setImageBitmap(icon);
        targetLabel.setText(label);
        targetPkg.setText(detail);
    }

    private void updateStatus() {
        if (pserverChip == null) return;
        boolean ready = PServer.available();
        pserverChip.setText(ready ? "PSERVER READY" : "PSERVER OFFLINE");
        pserverChip.setTextColor(ready ? GREEN : 0xFFFF8A80);
        pserverChip.setBackground(Ui.rounded(ready ? 0x2669D98B : 0x26FF8A80,
                dp(10), ready ? 0x5569D98B : 0x55FF8A80, dp(1)));

        boolean enabled = store.isAppEnabled(target);
        int enabledCount = store.enabledCount();
        if (inputChip != null) {
            inputChip.setText(useStick ? "VIRTUAL STICK" : "D-PAD");
            inputChip.setTextColor(useStick ? GREEN : AMBER);
            inputChip.setBackground(navBackground(Ui.rounded(
                    useStick ? 0x2669D98B : 0x22FFC857, dp(10),
                    useStick ? 0x5569D98B : 0x55FFC857, dp(1))));
        }
        if (rightModeChip != null) {
            rightModeChip.setText(RightStick.label(rightMode));
            rightModeChip.setTextColor(rightMode == RightStick.OFF ? 0xFFB99CFF : accent);
            rightModeChip.setBackground(navBackground(Ui.rounded(
                    rightMode == RightStick.OFF ? 0x22B99CFF : ThemeColors.withAlpha(0x22),
                    dp(10), rightMode == RightStick.OFF ? 0x55B99CFF : ThemeColors.withAlpha(0x55),
                    dp(1))));
        }
        if (rightHint != null) {
            switch (rightMode) {
                case RightStick.AIM:
                    rightHint.setText("360° swipe aim: stick left/right rotates; release holds facing.");
                    break;
                case RightStick.SWIPE:
                    rightHint.setText("Right stick drags a virtual finger inside the SWIPE zone.");
                    break;
                case RightStick.MOUSE:
                    rightHint.setText("Right stick moves the system mouse. R1 clicks, R2 right-clicks.");
                    break;
                case RightStick.MOBA:
                    rightHint.setText("Right stick drives a MOBA movement stick on the right.");
                    break;
                case RightStick.JOYSTICK:
                    rightHint.setText("Normal analog joystick movement on the right side.");
                    break;
                default:
                    rightHint.setText("Right stick is unused.");
                    break;
            }
        }
        if (mappingStatus != null) {
            mappingStatus.setText(enabled ? "ACTIVE" : "OFF");
            mappingStatus.setTextColor(enabled ? GREEN : AMBER);
        }
        if (mappingDetail != null) {
            if (target.isEmpty()) {
                mappingDetail.setText("Pick a target game, place the buttons, then enable the mapping.");
            } else if (enabled) {
                mappingDetail.setText("Enabled for " + appName(target) + "."
                        + (enabledCount > 1
                        ? " " + enabledCount + " apps are enabled and switch automatically."
                        : "")
                        + "\n\nReturn to the game to use the map.");
            } else if (enabledCount > 0) {
                mappingDetail.setText("This app is off, but " + enabledCount
                        + " other app" + (enabledCount == 1 ? " is" : "s are")
                        + " still enabled. Enable this app to add it.");
            } else {
                mappingDetail.setText("Disabled. Configure the layout, then enable it for "
                        + appName(target) + ".");
            }
        }
        if (toggle != null) {
            toggle.setText(enabled ? "DISABLE MAPPING" : "ENABLE IN GAME");
            toggle.setTextColor(enabled ? 0xFFFF8A80 : GREEN);
            toggle.setBackground(navBackground(Ui.rounded(
                    enabled ? 0x26FF8A80 : 0x2669D98B, dp(10),
                    enabled ? 0x55FF8A80 : 0x5569D98B, dp(1))));
        }
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            return "the game";
        }
    }

    private LinearLayout section() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(11), dp(12), dp(11));
        box.setBackground(Ui.rounded(0x12FFFFFF, dp(14), 0x22FFFFFF, dp(1)));
        return box;
    }

    private TextView chip(String value, int color, int fill, int border) {
        TextView t = text(value, 8.5f, color, true);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(6), dp(9), dp(6));
        t.setBackground(Ui.rounded(fill, dp(10), border, dp(1)));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        return t;
    }

    private Drawable navBackground(Drawable normal) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},
                Ui.focusRing(this, dp(12)));
        states.addState(new int[]{}, normal);
        return states;
    }

    private void setupControllerNav() {
        nav.clear();
        addNav(closeChip);
        addNav(resetChip);
        addNav(chooseChip);
        addNav(inputChip);
        addNav(rightModeChip);
        addNav(toggle);
    }

    private void addNav(View v) {
        if (v == null) return;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        nav.add(v);
    }

    private void focusInitialControl() {
        // Match FanActivity: the app picker is the entry point, so the
        // controller starts there instead of on the enable switch.
        View v = chooseChip != null ? chooseChip : toggle;
        if (v != null) v.requestFocus();
    }

    private void moveNav(int direction) {
        if (nav.isEmpty()) return;
        View current = getCurrentFocus();
        int index = nav.indexOf(current);
        if (index < 0) index = direction > 0 ? -1 : 0;
        for (int i = 0; i < nav.size(); i++) {
            index = (index + direction + nav.size()) % nav.size();
            View candidate = nav.get(index);
            if (candidate.getVisibility() == View.VISIBLE && candidate.isEnabled()
                    && candidate.requestFocus()) {
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
        focused.performClick();
    }

    private void addFocusable(View v) {
        if (v == null) return;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView t = new LTextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
