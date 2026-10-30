package com.ben.pavo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import java.util.List;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Accessibility helper for the shade/lock-screen gestures, plus a root-backed listener for
 * the Odin's built-in controller. The controller listener watches the Odin Controller evdev
 * node directly, so mapped buttons work globally without pausing the foreground game.
 */
public final class PavoA11yService extends AccessibilityService {

    private android.content.Context uiContext;

    private static PavoA11yService instance;
    private boolean touchMotionRequested;
    private boolean drawerMotionRequested;
    private static volatile boolean drawerOverlayVisible;
    private boolean drawerMapperMode;
    private static volatile boolean remapUiActive;
    private static volatile VisualListener visualListener;
    private static volatile boolean autoAcceptProjection;
    private static volatile long autoAcceptProjectionUntil;

    interface VisualListener {
        void onControllerVisual(int raw, boolean down);
    }

    interface WindowCaptureCallback {
        void onCaptured(Bitmap bitmap, Rect bounds);
        void onFailed();
    }

    /**
     * Capture only the focused application window, excluding Pavo's overlay
     * windows. This lets the performance card refresh its backdrop without
     * hiding itself and blinking.
     */
    /** Focused app package without spawning dumpsys every poll. */
    static String focusedPackage() {
        PavoA11yService s = instance;
        if (s == null) return null;
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg != null && pkg.length() > 0) return pkg.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            List<AccessibilityWindowInfo> windows = s.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null || !w.isFocused()
                            || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null && root.getPackageName() != null) {
                        return root.getPackageName().toString();
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static boolean captureApplicationWindow(WindowCaptureCallback callback) {
        final PavoA11yService service = instance;
        if (service == null || callback == null || Build.VERSION.SDK_INT < 34) {
            return false;
        }
        try {
            AccessibilityWindowInfo target = null;
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        continue;
                    }
                    if (target == null || window.isFocused()) target = window;
                    if (window.isFocused()) break;
                }
            }
            if (target == null) {
                return false;
            }
            final Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            if (bounds.width() <= 0 || bounds.height() <= 0) return false;
            service.takeScreenshotOfWindow(target.getId(), service.getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            Bitmap copy = null;
                            try {
                                HardwareBuffer buffer = result.getHardwareBuffer();
                                Bitmap wrapped = Bitmap.wrapHardwareBuffer(
                                        buffer, result.getColorSpace());
                                if (wrapped != null) {
                                    copy = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                                }
                                buffer.close();
                            } catch (Throwable ignored) {
                            }
                            if (copy == null) {
                                callback.onFailed();
                            } else {
                                callback.onCaptured(copy, new Rect(bounds));
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            callback.onFailed();
                        }
                    });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void setVisualListener(VisualListener listener) {
        visualListener = listener;
    }

    static void setRemapUiActive(boolean active) {
        remapUiActive = active;
    }

    /** Armed immediately before launching the MediaProjection consent dialog.
     *  The system still owns the permission; this only presses its Start button. */
    static void armProjectionAutoAccept(boolean arm) {
        autoAcceptProjection = arm;
        autoAcceptProjectionUntil = arm ? SystemClock.uptimeMillis() + 20_000L : 0L;
    }

    static void restartControllerListener() {
        // The PServer-backed PavoInputService owns the controller in root mode.
        // In no-root mode the accessibility key filter below is the input path.
        final PavoA11yService s = instance;
        if (s == null) return;
        if (!new Prefs(s).rootlessRemap()) PavoInputService.ensureStarted(s);
    }

    static boolean isFloatingDrawerVisible() {
        return PavoInputService.isFloatingDrawerVisible() || drawerOverlayVisible;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler keyHandler = new Handler(Looper.getMainLooper());
    private volatile boolean controllerRunning;
    private volatile int controllerGeneration;
    private Thread controllerThread;

    private AppDrawerOverlayView drawerOverlay;
    private WindowManager.LayoutParams drawerOverlayParams;
    private boolean drawerOpening;

    private static final int CAPTURE_PERF = 0;
    private static final int CAPTURE_DRAWER = 1;
    private static final int CAPTURE_PERFLOG = 2;

    private static volatile boolean capturing;
    private static volatile int captureTarget = CAPTURE_PERF;
    private long captureDeadline;
    private String swallowCaptureUp;
    private String mappedDown;
    private boolean mappedLongFired;
    private String drawerMappedDown;
    private boolean drawerLongFired;
    private String perfLogMappedDown;
    private boolean perfLogLongFired;
    private boolean perfShortcutShownOnDown;
    private boolean drawerShortcutShownOnDown;

    private volatile float drawerStickX;
    private volatile float drawerStickY;
    private volatile int drawerStickAxis;
    private volatile int drawerStickDir = -1;
    private volatile int drawerStickHeld = -1;
    private volatile float drawerFastY;
    private volatile int drawerFastHeld = -1;
    private long lastDrawerNavAt;
    private long lastDpadNavAt;
    private long lastHatNavAt;
    private int drawerHatX;
    private int drawerHatY;
    private int drawerHatDir = -1;
    private long drawerHoldStartedAt;
    private int drawerHoldDir = -1;
    private float perfMenuStickX;
    private float perfMenuStickY;
    private int perfMenuStickAxis;
    private int perfMenuStickDir = -1;
    private int perfMenuStickHeld = -1;
    private int perfMenuHatX;
    private int perfMenuHatY;
    private int perfMenuHatDir = -1;

    private final Runnable drawerHatRepeat = new Runnable() {
        @Override
        public void run() {
            if (!drawerOverlayVisible || drawerHatDir < 0) return;
            noteDrawerHold(drawerHatDir);
            boolean submenu = drawerOverlay != null && drawerOverlay.isSubmenuVisible();
            int steps = submenu ? 1 : drawerRepeatSteps();
            long delay = submenu ? 70L : drawerRepeatDelay();
            moveFloatingDrawer(drawerHatDir, steps);
            main.postDelayed(this, delay);
        }
    };

    private final Runnable drawerStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (!drawerOverlayVisible || drawerStickHeld < 0) return;
            drawerStickDir = drawerStickHeld;
            noteDrawerHold(drawerStickHeld);
            boolean submenu = drawerOverlay != null && drawerOverlay.isSubmenuVisible();
            int steps = submenu ? 1 : drawerRepeatSteps();
            long delay = submenu ? 70L : drawerRepeatDelay();
            moveFloatingDrawer(drawerStickHeld, steps);
            main.postDelayed(this, delay);
        }
    };

    private final Runnable drawerFastRepeat = new Runnable() {
        @Override
        public void run() {
            if (!drawerOverlayVisible || drawerFastHeld < 0) return;
            noteDrawerHold(drawerFastHeld);
            int steps = Math.abs(drawerFastY) > 0.60f ? 2 : 1;
            moveFloatingDrawer(drawerFastHeld, steps);
            main.postDelayed(this, 28L);
        }
    };

    private final Runnable captureTimeout = new Runnable() {
        @Override
        public void run() {
            if (!capturing) return;
            capturing = false;
            LToast.makeText(PavoA11yService.this,
                    "No controller button detected", Toast.LENGTH_SHORT).show();
        }
    };

    private final Runnable perfMenuHatRepeat = new Runnable() {
        @Override
        public void run() {
            if (!PerfOverlayService.isMenuOpen() || perfMenuHatDir < 0) return;
            movePerfMenu(perfMenuHatDir);
            main.postDelayed(this, 280);
        }
    };

    private final Runnable perfMenuStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (!PerfOverlayService.isMenuOpen() || perfMenuStickHeld < 0) return;
            perfMenuStickDir = perfMenuStickHeld;
            movePerfMenu(perfMenuStickHeld);
            main.postDelayed(this, 150);
        }
    };

    private final Runnable mappedLongPress = new Runnable() {
        @Override
        public void run() {
            if (mappedDown == null) return;
            mappedLongFired = true;
            PerfOverlayService.showMenuFromShortcut(PavoA11yService.this);
        }
    };

    private final Runnable perfLogLongPress = new Runnable() {
        @Override
        public void run() {
            if (perfLogMappedDown == null) return;
            perfLogLongFired = true;
            Haptics.longPress(PavoA11yService.this);
            PerfLogActivity.showOptionsFromShortcut();
        }
    };

    private final Runnable drawerLongPress = new Runnable() {
        @Override
        public void run() {
            if (drawerMappedDown == null) return;
            drawerLongFired = true;
            Haptics.longPress(PavoA11yService.this);
            showDrawerStyleFromShortcut();
        }
    };

    static boolean openShade() {
        if (PServer.available() && PServer.run("cmd statusbar expand-notifications")) {
            return true;
        }
        PavoA11yService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
    }

    static boolean lockScreen() {
        if (PServer.available() && PServer.run("input keyevent 223")) return true;
        PavoA11yService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
    }

    static boolean isReady() {
        return PavoInputService.isReady() || instance != null;
    }

    static boolean hasAccessibility() {
        return instance != null;
    }

    static boolean beginKeyCapture() {
        PavoA11yService s = instance;
        if (s != null && !new Prefs(s).rootlessRemap()) {
            return PavoInputService.beginKeyCapture(s);
        }
        return beginCapture(CAPTURE_PERF);
    }

    static boolean beginDrawerKeyCapture() {
        PavoA11yService s = instance;
        if (s != null && !new Prefs(s).rootlessRemap()) {
            return PavoInputService.beginDrawerKeyCapture(s);
        }
        return beginCapture(CAPTURE_DRAWER);
    }

    static boolean beginPerfLogKeyCapture() {
        PavoA11yService s = instance;
        if (s != null && !new Prefs(s).rootlessRemap()) {
            return PavoInputService.beginPerfLogKeyCapture(s);
        }
        return beginCapture(CAPTURE_PERFLOG);
    }

    private static boolean beginCapture(int target) {
        final PavoA11yService s = instance;
        if (s == null) return false;
        captureTarget = target;
        capturing = true;
        s.captureDeadline = SystemClock.uptimeMillis() + 5000L;
        s.keyHandler.removeCallbacks(s.captureTimeout);
        s.keyHandler.postDelayed(s.captureTimeout, 5200L);
        LToast.makeText(s, target == CAPTURE_DRAWER
                ? "Press a controller button for the app drawer"
                : target == CAPTURE_PERFLOG
                ? "Press a controller button to open Perf Log options"
                : "Press a controller button for the overlay", Toast.LENGTH_LONG).show();
        return true;
    }

    static String controllerLabel(Prefs prefs) {
        if (prefs == null) return "Not mapped";
        String raw = prefs.perfControllerRaw();
        String label = friendlyKey(raw);
        return Prefs.DEFAULT_CONTROLLER_RAW.equals(raw) ? label + " (default)" : label;
    }

    static String drawerControllerLabel(Prefs prefs) {
        return friendlyKey(prefs == null ? "" : prefs.drawerControllerRaw());
    }

    static String perfLogControllerLabel(Prefs prefs) {
        return friendlyKey(prefs == null ? "" : prefs.perfLogControllerRaw());
    }

    private static String friendlyKey(String raw) {
        if (raw == null || raw.isEmpty()) return "Not mapped";
        switch (raw) {
            case "BTN_SOUTH":
            case "BTN_GAMEPAD":
                return "A / South";
            case "BTN_EAST":
                return "B / East";
            case "BTN_NORTH":
                return "X / North";
            case "BTN_WEST":
                return "Y / West";
            case "BTN_TL":
                return "L1";
            case "BTN_TR":
                return "R1";
            case "BTN_TL2":
                return "L2";
            case "BTN_TR2":
                return "R2";
            case "BTN_SELECT":
                return "Select";
            case "BTN_START":
                return "Start";
            case "BTN_MODE":
                return "Guide";
            case "BTN_THUMBL":
                return "L3";
            case "BTN_THUMBR":
                return "R3";
            default:
                return raw.replace('_', ' ');
        }
    }

    /**
     * Trusted overlays owned by the accessibility service are not blocked by apps that
     * hide non-system overlays, and are above application overlays.
     */
    static boolean addAccessibilityOverlay(View view, WindowManager.LayoutParams lp) {
        PavoA11yService s = instance;
        if (s == null || view == null || lp == null) return false;
        try {
            WindowManager wm = (WindowManager) s.getSystemService(WINDOW_SERVICE);
            if (wm == null) return false;
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            lp.format = PixelFormat.TRANSLUCENT;
            wm.addView(view, lp);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void removeAccessibilityOverlay(View view) {
        PavoA11yService s = instance;
        if (s == null || view == null) return;
        try {
            WindowManager wm = (WindowManager) s.getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.removeView(view);
        } catch (Throwable ignored) {
        }
    }

    /** Short press: open the drawer, close it, or close only its style card. */
    private void toggleDrawerOverlay() {
        if (drawerOverlay != null) {
            if (drawerOverlay.isStyleVisible()) {
                Haptics.key(drawerOverlay);
                drawerOverlay.hideStyleOverlay();
            } else {
                drawerOverlay.dismissAnimated();
            }
            return;
        }
        showDrawerOverlay(false);
    }

    /** Long press: show the style card while the physical button is still held. */
    private void showDrawerStyleFromShortcut() {
        if (drawerOverlay != null) {
            drawerOverlay.showStyleOverlay();
            return;
        }
        showDrawerOverlay(true);
    }

    private void showDrawerOverlay(final boolean showStyle) {
        if (drawerOpening || drawerOverlay != null) return;
        if (!Settings.canDrawOverlays(this)) {
            LToast.makeText(this, "Allow Pavo to display over other apps first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        drawerOpening = true;
        // Capture a sharp sample before attaching the overlay, so the side
        // drawer card can run the real glass shader. The capture runs off the
        // main thread and the overlay is shown as soon as it is ready.
        new Thread(new Runnable() {
            @Override
            public void run() {
                Backdrop backdrop = ScreenCaptureBackdrop.captureSharp(PavoA11yService.this);
                if (backdrop == null) backdrop = BuiltInBackdrop.copy();
                if (backdrop == null) backdrop = WallpaperBackdrop.captureSharp(PavoA11yService.this);
                final Backdrop sampled = backdrop;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (drawerOverlay == null) {
                            showDrawerOverlayNow(showStyle, sampled);
                        } else {
                            drawerOpening = false;
                        }
                    }
                });
            }
        }, "aurora-side-drawer-capture").start();
    }

    private void showDrawerOverlayNow(final boolean showStyle, final Backdrop backdrop) {
        if (drawerOverlay != null) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        PerformanceTunerActivity.onSidebarDrawerOpened();

        AppDrawerOverlayView view = new AppDrawerOverlayView(uiContext,
                new AppDrawerOverlayView.Listener() {
                    @Override
                    public void onDismiss() {
                        hideDrawerOverlay();
                    }

                    @Override
                    public void onSearchFocusRequested(boolean focus) {
                        setDrawerFocusable(focus);
                    }
                });
        view.setScreenBackdrop(backdrop);
        view.setShortcutGrace(400L);
        // Accessibility overlays are trusted and are not hidden by system apps that
        // set HIDE_NON_SYSTEM_OVERLAY_WINDOWS. This is the same layer used by the
        // performance control menu.
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        drawerMapperMode = TouchMapService.isMapperEnabledForForeground(this);
        view.setMapperMode(drawerMapperMode);
        int drawerFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!drawerMapperMode) drawerFlags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                drawerFlags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        lp.setTitle("Pavo app drawer");
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(view, lp);
            drawerOverlay = view;
            drawerOverlayParams = lp;
            drawerOverlayVisible = true;
            TouchMapService.notifyModalOverlayChanged();
            setDrawerMotionEvents(true);
            resetDrawerNav();
            drawerOverlay.post(new Runnable() {
                @Override
                public void run() {
                    if (drawerOverlay != null) {
                        if (drawerMapperMode) drawerOverlay.requestFocus();
                        drawerOverlay.resetControllerFocus();
                        drawerOverlay.ensureControllerSelection();
                    }
                }
            });
            if (showStyle) {
                drawerOverlay.post(new Runnable() {
                    @Override
                    public void run() {
                        if (drawerOverlay != null) drawerOverlay.showStyleOverlay();
                    }
                });
            }
        } catch (Throwable t) {
            drawerOpening = false;
            drawerOverlay = null;
            drawerOverlayParams = null;
            drawerOverlayVisible = false;
            setDrawerMotionEvents(false);
            resetDrawerNav();
            LToast.makeText(this, "Could not open the floating app drawer",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The drawer starts non-focusable so it never pauses or switches the foreground app.
     * Search temporarily makes the accessibility overlay focusable so the IME can attach.
     */
    /**
     * The drawer starts non-focusable so it never pauses or switches the foreground app.
     * Search temporarily makes the accessibility overlay focusable so the IME can attach.
     */
    private void setDrawerFocusable(boolean focus) {
        if (drawerOverlay == null || drawerOverlayParams == null) return;
        if (drawerMapperMode || focus) {
            drawerOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            drawerOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            try {
                wm.updateViewLayout(drawerOverlay, drawerOverlayParams);
            } catch (Throwable ignored) {
            }
        }
        drawerOverlay.post(new Runnable() {
            @Override
            public void run() {
                if (drawerOverlay == null) return;
                if (focus) drawerOverlay.focusSearchField();
                else if (drawerMapperMode) drawerOverlay.requestFocus();
            }
        });
    }

    private void hideDrawerOverlay() {
        AppDrawerOverlayView view = drawerOverlay;
        drawerOverlay = null;
        drawerOpening = false;
        drawerOverlayParams = null;
        drawerOverlayVisible = false;
        TouchMapService.notifyModalOverlayChanged();
        drawerMapperMode = false;
        setDrawerMotionEvents(false);
        resetDrawerNav();
        if (view == null) return;
        view.dismissKeyboard();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        uiContext = Ui.adaptiveContext(this);
        if (!new Prefs(this).rootlessRemap()) PavoInputService.ensureStarted(this);
        if (new Prefs(this).controllerRemapEnabled()
                && !TouchMapManager.isRunning(this)
                && !TouchMapManager.isStarting()
                && !TouchMapService.isMapperEnabledForForeground(this)) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!TouchMapManager.isRunning(PavoA11yService.this)
                            && !TouchMapManager.isStarting()
                            && !TouchMapService.isMapperEnabledForForeground(
                                    PavoA11yService.this)) {
                        ControllerRemapManager.ensureStarted(PavoA11yService.this);
                    }
                }
            }, 700L);
        }
    }

    /**
     * The app needs ordinary joystick motion events for home and drawer focus
     * navigation. Request them only while the floating drawer is on screen, so
     * the accessibility service does not take them away from the foreground app.
     */
    private void setDrawerMotionEvents(boolean enabled) {
        drawerMotionRequested = enabled;
        applyMotionSources();
    }

    private void applyMotionSources() {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int sources = 0;
            if (drawerMotionRequested) {
                sources |= InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD;
            }
            if (touchMotionRequested) {
                sources |= InputDevice.SOURCE_TOUCHSCREEN | InputDevice.SOURCE_MOUSE;
            }
            if (sources != 0) {
                info.flags |= AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS;
            } else {
                info.flags &= ~AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS;
            }
            info.setMotionEventSources(sources);
            setServiceInfo(info);
        } catch (Throwable ignored) {
        }
    }

    static boolean canObserveTouches() {
        return instance != null;
    }

    static void setTouchObservation(boolean enabled) {
        PavoA11yService service = instance;
        if (service == null) return;
        service.touchMotionRequested = enabled;
        service.applyMotionSources();
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int raw = ControllerMapConfig.rawFor(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        boolean repeat = event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() > 0;
        VisualListener visual = visualListener;
        if (visual != null && raw > 0 && (down || up)) {
            visual.onControllerVisual(raw, down);
        }
        // While the visual editor is open, keep its Home/Back keys from leaving
        // the editor so the outline can show them.
        if (visual != null && (raw == 102 || raw == 158)) return true;

        // In PServer/root mode PavoInputService owns controller input.  This
        // service is only the no-root fallback and the trusted overlay layer.
        if (!new Prefs(this).rootlessRemap()) return false;

        // Android turns the analogue stick into low-threshold D-pad keys. The
        // motion-event handler below applies its own dead zone and repeat, so
        // swallow those duplicates while the drawer owns the controller.
        boolean syntheticStick = event.getScanCode() == 0
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0
                && isDirectionKey(raw);
        if (drawerOverlayVisible && syntheticStick) return true;

        if (capturing) {
            if (down && raw > 0 && SystemClock.uptimeMillis() <= captureDeadline) {
                String key = RootlessUinput.linuxLabel(raw);
                if (key != null) {
                    Prefs prefs = new Prefs(this);
                    final int target = captureTarget;
                    if (target == CAPTURE_DRAWER) prefs.setDrawerControllerRaw(key);
                    else if (target == CAPTURE_PERFLOG) prefs.setPerfLogControllerRaw(key);
                    else prefs.setPerfControllerRaw(key);
                    capturing = false;
                    swallowCaptureUp = key;
                    keyHandler.removeCallbacks(captureTimeout);
                    final String label = friendlyKey(key);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            String prefix = target == CAPTURE_DRAWER ? "App drawer shortcut: "
                                    : target == CAPTURE_PERFLOG ? "Perf Log popup shortcut: "
                                    : "Controller shortcut: ";
                            LToast.makeText(PavoA11yService.this, prefix + label,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
            return true;
        }
        if (swallowCaptureUp != null) {
            String key = RootlessUinput.linuxLabel(raw);
            if (key != null && key.equals(swallowCaptureUp) && up) swallowCaptureUp = null;
            return true;
        }

        if (remapUiActive || !RootlessRemap.isActive()) return false;
        if (RootlessRemap.isVirtualEvent(event)) return false;
        if (raw > 0) handleRootlessShortcut(RootlessRemap.mappedRaw(raw), down, up, repeat);
        return RootlessRemap.handle(event);
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        // The PServer input service reads analogue axes from the native socket.
        if (PavoInputService.isActive()) return;
        if (event == null || !drawerOverlayVisible || controllerRunning) return;
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) == 0
                && (source & InputDevice.SOURCE_GAMEPAD) == 0) return;

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hatX - drawerHatX) >= 0.01f
                || Math.abs(hatY - drawerHatY) >= 0.01f) {
            drawerHatX = Math.round(hatX);
            drawerHatY = Math.round(hatY);
            updateDrawerHat();
        }

        float stickX = clampAxis(event.getAxisValue(MotionEvent.AXIS_X));
        float stickY = clampAxis(event.getAxisValue(MotionEvent.AXIS_Y));
        if (Math.abs(stickX - drawerStickX) >= 0.01f
                || Math.abs(stickY - drawerStickY) >= 0.01f) {
            drawerStickX = stickX;
            drawerStickY = stickY;
            updateDrawerStick();
        }
        float rightY = StickScroll.rightY(event);
        if (Math.abs(rightY - drawerFastY) >= 0.01f) {
            drawerFastY = rightY;
            updateDrawerFast();
        }
    }

    private static float clampAxis(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static boolean isDirectionKey(int raw) {
        return raw == 544 || raw == 545 || raw == 546 || raw == 547;
    }

    private void handleRootlessShortcut(int raw, boolean down, boolean up, boolean repeat) {
        String key = RootlessUinput.linuxLabel(raw);
        if (key == null) return;
        if (drawerOverlayVisible && handleDrawerControllerKey(key, down, up, repeat)) return;
        Prefs prefs = new Prefs(this);
        if (PerfLogActivity.isVisible()) {
            String perfLogMapped = prefs.perfLogControllerRaw();
            if (perfLogMapped != null && !perfLogMapped.isEmpty()
                    && perfLogMapped.equals(key)) {
                if (down) {
                    if (perfLogMappedDown == null) {
                        perfLogMappedDown = key;
                        perfLogLongFired = false;
                        keyHandler.removeCallbacks(perfLogLongPress);
                        keyHandler.postDelayed(perfLogLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                } else if (up) {
                    keyHandler.removeCallbacks(perfLogLongPress);
                    perfLogMappedDown = null;
                    perfLogLongFired = false;
                }
                return;
            }
        }
        String drawerMapped = prefs.drawerControllerRaw();
        if (drawerMapped != null && !drawerMapped.isEmpty() && drawerMapped.equals(key)) {
            if (down) {
                if (drawerMappedDown == null) {
                    drawerMappedDown = key;
                    drawerLongFired = false;
                    keyHandler.removeCallbacks(drawerLongPress);
                    keyHandler.postDelayed(drawerLongPress,
                            ViewConfiguration.getLongPressTimeout());
                    if (!drawerOverlayVisible) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                toggleDrawerOverlay();
                            }
                        });
                        drawerShortcutShownOnDown = true;
                    }
                }
            } else if (up) {
                keyHandler.removeCallbacks(drawerLongPress);
                final boolean shortTap = drawerMappedDown != null && !drawerLongFired;
                final boolean shown = drawerShortcutShownOnDown;
                drawerMappedDown = null;
                drawerLongFired = false;
                drawerShortcutShownOnDown = false;
                if (shortTap && !shown) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            toggleDrawerOverlay();
                        }
                    });
                }
            }
            return;
        }
        String mapped = prefs.perfControllerRaw();
        if (mapped == null || mapped.isEmpty() || !mapped.equals(key)) return;
        if (down) {
            if (mappedDown == null) {
                mappedDown = key;
                mappedLongFired = false;
                keyHandler.removeCallbacks(mappedLongPress);
                keyHandler.postDelayed(mappedLongPress,
                        ViewConfiguration.getLongPressTimeout());
            }
        } else if (up) {
            keyHandler.removeCallbacks(mappedLongPress);
            final boolean shortTap = mappedDown != null && !mappedLongFired;
            mappedDown = null;
            mappedLongFired = false;
            if (shortTap) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        PerfOverlayService.toggleOverlayFromShortcut(PavoA11yService.this);
                    }
                });
            }
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopControllerListener();
        hideDrawerOverlay();
        keyHandler.removeCallbacksAndMessages(null);
        capturing = false;
        mappedDown = null;
        drawerMappedDown = null;
        drawerLongFired = false;
        keyHandler.removeCallbacks(drawerLongPress);
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopControllerListener();
        hideDrawerOverlay();
        keyHandler.removeCallbacksAndMessages(null);
        capturing = false;
        mappedDown = null;
        drawerMappedDown = null;
        drawerLongFired = false;
        keyHandler.removeCallbacks(drawerLongPress);
        instance = null;
        super.onDestroy();
    }

    private void startControllerListener() {
        if (controllerRunning) return;
        killGeteventListeners();
        controllerRunning = true;
        final int generation = ++controllerGeneration;
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (controllerRunning && generation == controllerGeneration) {
                    String device = findControllerDevice();
                    if (!controllerRunning || generation != controllerGeneration) break;
                    if (device == null) {
                        sleep(3000);
                        continue;
                    }
                    listenGetEvent(device, generation);
                    killGeteventListeners();
                    if (!controllerRunning || generation != controllerGeneration) break;
                    sleep(1000);
                }
            }
        }, "OdinControllerKeys");
        controllerThread = thread;
        thread.start();
    }

    /**
     * PServer cannot hand back a live stream, so getevent writes into a cache
     * file and this thread tails it. No su is involved anywhere.
     */
    private void listenGetEvent(String device, int generation) {
        if (device == null) return;
        File log = new File(getCacheDir(), "pavo-getevent.txt");
        try (FileOutputStream out = new FileOutputStream(log, false)) {
            // Truncate; the helper appends from byte zero.
        } catch (Throwable ignored) {
        }
        String cache = getCacheDir().getAbsolutePath();
        if (!PServer.run("cd " + cache + "; getevent -lt " + device
                + " > pavo-getevent.txt 2>&1 &")) return;
        long offset = 0L;
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            while (controllerRunning && generation == controllerGeneration) {
                long length = raf.length();
                if (length > offset) {
                    int want = (int) Math.min(length - offset, 8192);
                    byte[] chunk = new byte[want];
                    raf.seek(offset);
                    int n = raf.read(chunk);
                    if (n > 0) {
                        int end = -1;
                        for (int i = n - 1; i >= 0; i--) {
                            if (chunk[i] == '\n') {
                                end = i;
                                break;
                            }
                        }
                        if (end >= 0) {
                            String text = new String(chunk, 0, end + 1,
                                    StandardCharsets.UTF_8);
                            for (String line : text.split("\n")) parseKeyLine(line);
                            offset += end + 1;
                        }
                    }
                }
                sleep(60);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopControllerListener() {
        controllerRunning = false;
        controllerGeneration++;
        killGeteventListeners();
        Thread t = controllerThread;
        controllerThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** The getevent child lives on when the helper shell exits. */
    private void killGeteventListeners() {
        PServer.run("killall getevent 2>/dev/null");
    }

    private String findControllerDevice() {
        String text = PServer.exec("getevent -lp", 1500L);
        if (text == null || text.isEmpty()) return null;
        String current = null;
        String virtual = null;
        String odin = null;
        for (String raw : text.split("\n")) {
            String t = raw.trim();
            if (t.startsWith("add device")) {
                int slash = t.indexOf('/');
                if (slash >= 0) current = t.substring(slash).trim();
            } else if (t.startsWith("name:") && current != null) {
                String name = t.substring(5).trim().toLowerCase(Locale.US);
                if (name.contains("pavo virtual controller")) {
                    virtual = current;
                } else if (name.contains("odin controller")) {
                    odin = current;
                }
            }
        }
        if (new Prefs(this).controllerRemapEnabled() && virtual != null) return virtual;
        return odin != null ? odin : virtual;
    }

    private void parseKeyLine(String line) {
        if (line == null) return;
        if (line.contains("EV_ABS")) {
            parseAbsLine(line);
            return;
        }
        if (!line.contains("EV_KEY")) return;
        String[] p = line.trim().split("\\s+");
        int at = -1;
        for (int i = 0; i < p.length; i++) {
            if ("EV_KEY".equals(p[i])) {
                at = i;
                break;
            }
        }
        if (at < 0 || at + 2 >= p.length) return;
        String key = p[at + 1];
        String value = p[at + 2];
        boolean down = value.startsWith("DOWN") || "1".equals(value);
        boolean up = value.startsWith("UP") || "0".equals(value);
        boolean repeat = value.startsWith("REPEAT") || "2".equals(value);
        if (!down && !up && !repeat) return;

        if (isSystemKey(key)) return;
        if (remapUiActive) return;

        if (capturing) {
            if (!down || SystemClock.uptimeMillis() > captureDeadline) return;
            Prefs prefs = new Prefs(this);
            final int target = captureTarget;
            if (target == CAPTURE_DRAWER) prefs.setDrawerControllerRaw(key);
            else if (target == CAPTURE_PERFLOG) prefs.setPerfLogControllerRaw(key);
            else prefs.setPerfControllerRaw(key);
            capturing = false;
            swallowCaptureUp = key;
            keyHandler.removeCallbacks(captureTimeout);
            final String label = friendlyKey(key);
            main.post(new Runnable() {
                @Override
                public void run() {
                    String prefix = target == CAPTURE_DRAWER ? "App drawer shortcut: "
                            : target == CAPTURE_PERFLOG ? "Perf Log popup shortcut: "
                            : "Controller shortcut: ";
                    LToast.makeText(PavoA11yService.this, prefix + label,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        if (swallowCaptureUp != null) {
            if (key.equals(swallowCaptureUp) && up) swallowCaptureUp = null;
            return;
        }

        if (drawerOverlayVisible
                && handleDrawerControllerKey(key, down, up, repeat)) {
            return;
        }

        Prefs prefs = new Prefs(this);
        if (PerfLogActivity.isVisible()) {
            String perfLogMapped = prefs.perfLogControllerRaw();
            if (perfLogMapped != null && !perfLogMapped.isEmpty()
                    && perfLogMapped.equals(key)) {
                if (down) {
                    if (perfLogMappedDown == null) {
                        perfLogMappedDown = key;
                        perfLogLongFired = false;
                        keyHandler.removeCallbacks(perfLogLongPress);
                        keyHandler.postDelayed(perfLogLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                } else if (up) {
                    keyHandler.removeCallbacks(perfLogLongPress);
                    perfLogMappedDown = null;
                    perfLogLongFired = false;
                }
                return;
            }
        }
        String drawerMapped = prefs.drawerControllerRaw();
        if (drawerMapped != null && !drawerMapped.isEmpty() && drawerMapped.equals(key)) {
            if (down) {
                if (drawerMappedDown == null) {
                    drawerMappedDown = key;
                    drawerLongFired = false;
                    keyHandler.removeCallbacks(drawerLongPress);
                    keyHandler.postDelayed(drawerLongPress,
                            ViewConfiguration.getLongPressTimeout());
                }
            } else if (up) {
                keyHandler.removeCallbacks(drawerLongPress);
                final boolean shortTap = drawerMappedDown != null && !drawerLongFired;
                drawerMappedDown = null;
                drawerLongFired = false;
                if (shortTap) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            toggleDrawerOverlay();
                        }
                    });
                }
            }
            return;
        }

        String mapped = prefs.perfControllerRaw();
        if (mapped == null || mapped.isEmpty() || !mapped.equals(key)) return;

        if (down) {
            if (mappedDown == null) {
                mappedDown = key;
                mappedLongFired = false;
                keyHandler.removeCallbacks(mappedLongPress);
                keyHandler.postDelayed(mappedLongPress,
                        ViewConfiguration.getLongPressTimeout());
            }
        } else if (up) {
            keyHandler.removeCallbacks(mappedLongPress);
            final boolean shortTap = mappedDown != null && !mappedLongFired;
            mappedDown = null;
            mappedLongFired = false;
            if (shortTap) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        PerfOverlayService.toggleOverlayFromShortcut(PavoA11yService.this);
                    }
                });
            }
        }
    }

    private boolean handleDrawerControllerKey(String key, boolean down, boolean up,
                                               boolean repeat) {
        int dx = 0;
        int dy = 0;
        switch (key) {
            case "BTN_DPAD_LEFT":
            case "KEY_LEFT":
                dx = -1;
                break;
            case "BTN_DPAD_RIGHT":
            case "KEY_RIGHT":
                dx = 1;
                break;
            case "BTN_DPAD_UP":
            case "KEY_UP":
                dy = -1;
                break;
            case "BTN_DPAD_DOWN":
            case "KEY_DOWN":
                dy = 1;
                break;
            default:
                break;
        }
        boolean select = "BTN_SOUTH".equals(key) || "BTN_GAMEPAD".equals(key)
                || "KEY_ENTER".equals(key) || "KEY_KPENTER".equals(key);
        boolean back = "BTN_EAST".equals(key) || "KEY_BACK".equals(key);
        if (dx == 0 && dy == 0 && !select && !back) return false;

        long now = SystemClock.uptimeMillis();
        if (repeat && now - lastDrawerNavAt < 40L) return true;
        if ((dx != 0 || dy != 0) && now - lastHatNavAt < 40L) return true;
        lastDrawerNavAt = now;

        if (dx != 0 || dy != 0) {
            lastDpadNavAt = now;
            final int dir = dx < 0 ? 0 : dx > 0 ? 1 : dy < 0 ? 2 : 3;
            if (up) {
                if (drawerHoldDir == dir) {
                    drawerHoldDir = -1;
                    drawerHoldStartedAt = 0L;
                }
            } else if (down || repeat) {
                if (down && !repeat) noteDrawerHold(dir);
                else if (drawerHoldDir != dir) noteDrawerHold(dir);
                final int fdx = dx;
                final int fdy = dy;
                final int steps = (down && !repeat) ? 1 : drawerRepeatSteps();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (drawerOverlay != null) {
                            drawerOverlay.onControllerDirection(fdx, fdy, steps);
                        }
                    }
                });
            }
            return true;
        }
        if (select) {
            if (down && !repeat) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (drawerOverlay != null) drawerOverlay.onControllerSelect();
                    }
                });
            }
            return true;
        }
        if (back) {
            if (down && !repeat) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (drawerOverlay != null) drawerOverlay.onControllerBack();
                    }
                });
            }
            return true;
        }
        return true;
    }

    private void parseAbsLine(String line) {
        if (!drawerOverlayVisible) return;
        String[] p = line.trim().split("\\s+");
        int at = -1;
        for (int i = 0; i < p.length; i++) {
            if ("EV_ABS".equals(p[i])) {
                at = i;
                break;
            }
        }
        if (at < 0 || at + 2 >= p.length) return;
        String axis = p[at + 1];
        String value = p[at + 2];
        int raw;
        try {
            raw = value.startsWith("-")
                    ? Integer.parseInt(value) : (int) Long.parseLong(value, 16);
        } catch (Throwable t) {
            return;
        }
        if ("ABS_HAT0X".equals(axis)) {
            drawerHatX = raw;
            updateDrawerHat();
            return;
        }
        if ("ABS_HAT0Y".equals(axis)) {
            drawerHatY = raw;
            updateDrawerHat();
            return;
        }
        if (!"ABS_X".equals(axis) && !"ABS_Y".equals(axis)) return;
        float v = Math.max(-1f, Math.min(1f, raw / 32767f));
        if ("ABS_X".equals(axis)) drawerStickX = v;
        else drawerStickY = v;
        updateDrawerStick();
    }

    private void updateDrawerHat() {
        if (!drawerOverlayVisible) return;
        if (drawerHatX == 0 && drawerHatY == 0) {
            resetDrawerHat();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastDpadNavAt < 40L) return;
        int dir = drawerHatY != 0 ? (drawerHatY > 0 ? 3 : 2)
                : (drawerHatX < 0 ? 0 : 1);
        if (dir != drawerHatDir) {
            drawerHatDir = dir;
            noteDrawerHold(dir);
            lastHatNavAt = now;
            moveFloatingDrawer(dir);
            main.removeCallbacks(drawerHatRepeat);
            main.postDelayed(drawerHatRepeat, 160);
        }
    }

    private void updatePerfMenuAbs(String axis, int raw) {
        if ("ABS_HAT0X".equals(axis)) {
            perfMenuHatX = raw;
            updatePerfMenuHat();
            return;
        }
        if ("ABS_HAT0Y".equals(axis)) {
            perfMenuHatY = raw;
            updatePerfMenuHat();
            return;
        }
        if (!"ABS_X".equals(axis) && !"ABS_Y".equals(axis)) return;
        float v = Math.max(-1f, Math.min(1f, raw / 32767f));
        if ("ABS_X".equals(axis)) perfMenuStickX = v;
        else perfMenuStickY = v;
        updatePerfMenuStick();
    }

    private void updatePerfMenuHat() {
        if (!PerfOverlayService.isMenuOpen()) {
            perfMenuHatDir = -1;
            main.removeCallbacks(perfMenuHatRepeat);
            return;
        }
        if (perfMenuHatX == 0 && perfMenuHatY == 0) {
            perfMenuHatDir = -1;
            main.removeCallbacks(perfMenuHatRepeat);
            return;
        }
        int dir = perfMenuHatY != 0 ? (perfMenuHatY > 0 ? 3 : 2)
                : (perfMenuHatX < 0 ? 0 : 1);
        if (dir != perfMenuHatDir) {
            perfMenuHatDir = dir;
            movePerfMenu(dir);
            main.removeCallbacks(perfMenuHatRepeat);
            main.postDelayed(perfMenuHatRepeat, 280);
        }
    }

    private void updatePerfMenuStick() {
        if (!PerfOverlayService.isMenuOpen()) {
            resetPerfMenuStick();
            return;
        }
        float ax = Math.abs(perfMenuStickX);
        float ay = Math.abs(perfMenuStickY);
        if (ax < 0.42f && ay < 0.42f) {
            resetPerfMenuStick();
            return;
        }
        if (perfMenuStickAxis == 0) {
            if (Math.max(ax, ay) < 0.70f) return;
            perfMenuStickAxis = ax >= ay ? 1 : 2;
        } else if (perfMenuStickAxis == 1) {
            if (ay > ax + 0.28f) perfMenuStickAxis = 2;
        } else if (ax > ay + 0.28f) {
            perfMenuStickAxis = 1;
        }
        float selected = perfMenuStickAxis == 1 ? ax : ay;
        if (selected < 0.60f) {
            perfMenuStickHeld = -1;
            perfMenuStickDir = -1;
            main.removeCallbacks(perfMenuStickRepeat);
            return;
        }
        int dir = perfMenuStickAxis == 1
                ? (perfMenuStickX < 0 ? 0 : 1) : (perfMenuStickY < 0 ? 2 : 3);
        if (dir != perfMenuStickDir) {
            perfMenuStickDir = dir;
            movePerfMenu(dir);
        }
        if (dir != perfMenuStickHeld) {
            perfMenuStickHeld = dir;
            main.removeCallbacks(perfMenuStickRepeat);
            main.postDelayed(perfMenuStickRepeat, 340);
        }
    }

    private void resetPerfMenuStick() {
        perfMenuStickAxis = 0;
        perfMenuStickDir = -1;
        perfMenuStickHeld = -1;
        perfMenuStickX = 0f;
        perfMenuStickY = 0f;
        main.removeCallbacks(perfMenuStickRepeat);
    }

    private void movePerfMenu(final int dir) {
        main.post(new Runnable() {
            @Override
            public void run() {
                PerfOverlayService.handleMenuDirection(dir);
            }
        });
    }

    private void updateDrawerStick() {
        if (!drawerOverlayVisible) return;
        float ax = Math.abs(drawerStickX);
        float ay = Math.abs(drawerStickY);
        if (ax < 0.40f && ay < 0.40f) {
            resetDrawerStick();
            return;
        }
        if (drawerStickAxis == 0) {
            if (Math.max(ax, ay) < 0.62f) return;
            drawerStickAxis = ax >= ay ? 1 : 2;
        } else if (drawerStickAxis == 1) {
            if (ay > ax + 0.30f) drawerStickAxis = 2;
        } else if (ax > ay + 0.30f) {
            drawerStickAxis = 1;
        }
        float selected = drawerStickAxis == 1 ? ax : ay;
        if (selected < 0.58f) {
            drawerStickHeld = -1;
            drawerStickDir = -1;
            main.removeCallbacks(drawerStickRepeat);
            return;
        }
        int dir = drawerStickAxis == 1
                ? (drawerStickX < 0 ? 0 : 1) : (drawerStickY < 0 ? 2 : 3);
        if (dir != drawerStickDir) {
            drawerStickDir = dir;
            noteDrawerHold(dir);
            moveFloatingDrawer(dir);
        }
        if (dir != drawerStickHeld) {
            drawerStickHeld = dir;
            main.removeCallbacks(drawerStickRepeat);
            main.postDelayed(drawerStickRepeat, 160);
        }
    }

    private void moveFloatingDrawer(int dir) {
        moveFloatingDrawer(dir, 1);
    }

    private void moveFloatingDrawer(int dir, final int steps) {
        final int dx = dir == 0 ? -1 : dir == 1 ? 1 : 0;
        final int dy = dir == 2 ? -1 : dir == 3 ? 1 : 0;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (drawerOverlay != null) {
                    drawerOverlay.onControllerDirection(dx, dy, steps);
                }
            }
        });
    }

    private void noteDrawerHold(int dir) {
        if (drawerHoldDir != dir) {
            drawerHoldDir = dir;
            drawerHoldStartedAt = SystemClock.uptimeMillis();
        }
    }

    private int drawerRepeatSteps() {
        long held = SystemClock.uptimeMillis() - drawerHoldStartedAt;
        if (held < 250L) return 1;
        if (held < 700L) return 2;
        if (held < 1300L) return 3;
        return 5;
    }

    private long drawerRepeatDelay() {
        long held = SystemClock.uptimeMillis() - drawerHoldStartedAt;
        if (held < 250L) return 100L;
        if (held < 700L) return 60L;
        if (held < 1300L) return 35L;
        return 22L;
    }

    private void updateDrawerFast() {
        if (!drawerOverlayVisible) return;
        if (Math.abs(drawerFastY) < 0.25f) {
            drawerFastHeld = -1;
            main.removeCallbacks(drawerFastRepeat);
            return;
        }
        int dir = drawerFastY < 0f ? 2 : 3;
        if (dir != drawerFastHeld) {
            drawerFastHeld = dir;
            noteDrawerHold(dir);
            moveFloatingDrawer(dir, 1);
            main.removeCallbacks(drawerFastRepeat);
            main.postDelayed(drawerFastRepeat, 70L);
        }
    }

    private void resetDrawerHat() {
        drawerHatX = 0;
        drawerHatY = 0;
        drawerHatDir = -1;
        main.removeCallbacks(drawerHatRepeat);
        clearDrawerHoldIfIdle();
    }

    private void resetDrawerStick() {
        drawerStickAxis = 0;
        drawerStickDir = -1;
        drawerStickHeld = -1;
        drawerStickX = 0f;
        drawerStickY = 0f;
        main.removeCallbacks(drawerStickRepeat);
        drawerFastY = 0f;
        drawerFastHeld = -1;
        main.removeCallbacks(drawerFastRepeat);
        clearDrawerHoldIfIdle();
    }

    private void resetDrawerNav() {
        resetDrawerHat();
        resetDrawerStick();
        drawerHoldDir = -1;
        drawerHoldStartedAt = 0L;
    }

    private void clearDrawerHoldIfIdle() {
        if (drawerHatDir < 0 && drawerStickHeld < 0 && drawerFastHeld < 0) {
            drawerHoldDir = -1;
            drawerHoldStartedAt = 0L;
        }
    }

    private static boolean isSystemKey(String key) {
        return "KEY_VOLUMEUP".equals(key) || "KEY_VOLUMEDOWN".equals(key)
                || "KEY_POWER".equals(key) || "KEY_HOME".equals(key)
                || "KEY_BACK".equals(key) || "KEY_MENU".equals(key);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!autoAcceptProjection) return;
        if (SystemClock.uptimeMillis() > autoAcceptProjectionUntil) {
            autoAcceptProjection = false;
            return;
        }
        CharSequence pkg = event == null ? null : event.getPackageName();
        if (pkg == null || !pkg.toString().contains("systemui")) return;
        main.removeCallbacks(projectionAcceptTask);
        main.postDelayed(projectionAcceptTask, 260L);
    }

    private final Runnable projectionAcceptTask = new Runnable() {
        private int attempts;
        @Override public void run() {
            if (!autoAcceptProjection) { attempts = 0; return; }
            if (SystemClock.uptimeMillis() > autoAcceptProjectionUntil) {
                autoAcceptProjection = false;
                attempts = 0;
                return;
            }
            if (acceptProjectionDialog()) {
                autoAcceptProjection = false;
                attempts = 0;
                return;
            }
            attempts++;
            if (attempts < 10) main.postDelayed(this, 220L);
            else { attempts = 0; autoAcceptProjection = false; }
        }
    };

    private boolean acceptProjectionDialog() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !pkg.toString().contains("systemui")) return false;
            String[] labels = {"START", "Start now", "Start recording", "Start"}
                    ;
            for (String label : labels) {
                List<AccessibilityNodeInfo> nodes =
                        root.findAccessibilityNodeInfosByText(label);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo node : nodes) {
                    if (node == null) continue;
                    AccessibilityNodeInfo click = node;
                    for (int i = 0; i < 4 && click != null; i++) {
                        if (click.isClickable() && click.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true;
                        }
                        AccessibilityNodeInfo parent = click.getParent();
                        if (parent == click) break;
                        click = parent;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
