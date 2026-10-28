package com.ben.aurora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * PServer-backed controller and floating-drawer service.
 *
 * The native remapper is launched by PServerBinder (su is only a fallback) and
 * sends raw controller buttons over an abstract local socket.  This service
 * consumes those events, runs the mapped shortcuts, and hosts the floating
 * drawer.  It does not require the accessibility service.  Accessibility is
 * still used, when available, for its trusted overlay layer.
 */
public final class AuroraInputService extends Service {

    private android.content.Context uiContext;

    private static final String CHANNEL = "aurora_controller";
    private static final int NOTIFICATION_ID = 0x415552;

    private static AuroraInputService instance;
    private static volatile boolean socketReady;
    private static volatile boolean remapUiActive;
    private static volatile AuroraA11yService.VisualListener visualListener;
    private static volatile boolean pendingDrawerToggle;
    private static volatile boolean pendingDrawerStyle;

    private static final int CAPTURE_PERF = 0;
    private static final int CAPTURE_DRAWER = 1;
    private static final int CAPTURE_PERFLOG = 2;
    private static volatile boolean capturing;
    private static volatile int captureTarget = CAPTURE_PERF;
    private static volatile long captureDeadline;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private LocalSocket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread socketThread;
    private volatile boolean swallowWanted;
    private long lastEnsureAt;

    private WindowManager windowManager;
    private AppDrawerOverlayView drawerOverlay;
    private WindowManager.LayoutParams drawerOverlayParams;
    private boolean drawerOnAccessibility;
    private boolean drawerOpening;
    private boolean drawerMapperMode;
    private volatile boolean drawerOverlayVisible;

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
            if (SystemClock.uptimeMillis() < captureDeadline) {
                main.postDelayed(this, 250L);
                return;
            }
            capturing = false;
            LToast.makeText(AuroraInputService.this,
                    "No controller button detected", Toast.LENGTH_SHORT).show();
        }
    };

    private final Runnable mappedLongPress = new Runnable() {
        @Override
        public void run() {
            if (mappedDown == null) return;
            mappedLongFired = true;
            PerfOverlayService.showMenuFromShortcut(AuroraInputService.this);
        }
    };

    private final Runnable perfLogLongPress = new Runnable() {
        @Override
        public void run() {
            if (perfLogMappedDown == null) return;
            perfLogLongFired = true;
            Haptics.longPress(AuroraInputService.this);
            PerfLogActivity.showOptionsFromShortcut();
        }
    };

    private final Runnable drawerLongPress = new Runnable() {
        @Override
        public void run() {
            if (drawerMappedDown == null) return;
            drawerLongFired = true;
            Haptics.longPress(AuroraInputService.this);
            showDrawerStyleFromShortcut();
        }
    };

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    static boolean shouldRun(Context c) {
        if (c == null) return false;
        Prefs prefs = new Prefs(c);
        if (prefs.rootlessRemap()) return false;
        return prefs.controllerRemapEnabled()
                || !prefs.perfControllerRaw().isEmpty()
                || !prefs.drawerControllerRaw().isEmpty()
                || !prefs.perfLogControllerRaw().isEmpty();
    }

    static void ensureStarted(Context c) {
        if (!shouldRun(c)) return;
        try {
            Intent intent = new Intent(c, AuroraInputService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent);
            else c.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, AuroraInputService.class));
        } catch (Throwable ignored) {
        }
    }

    static boolean isReady() {
        return instance != null && socketReady;
    }

    static boolean isActive() {
        return instance != null;
    }

    static boolean isFloatingDrawerVisible() {
        AuroraInputService s = instance;
        return s != null && s.drawerOverlayVisible;
    }

    static void setRemapUiActive(boolean active) {
        remapUiActive = active;
    }

    static boolean isRemapUiActive() {
        return remapUiActive;
    }

    static void setVisualListener(AuroraA11yService.VisualListener listener) {
        visualListener = listener;
    }

    static void toggleFloatingDrawerFromShortcut(Context c) {
        if (c == null) return;
        ensureStarted(c);
        AuroraInputService s = instance;
        if (s == null) {
            pendingDrawerToggle = true;
        } else {
            s.main.post(new Runnable() {
                @Override
                public void run() {
                    s.toggleDrawerOverlay();
                }
            });
        }
    }

    static void showFloatingDrawerStyleFromShortcut(Context c) {
        if (c == null) return;
        ensureStarted(c);
        AuroraInputService s = instance;
        if (s == null) {
            pendingDrawerStyle = true;
        } else {
            s.main.post(new Runnable() {
                @Override
                public void run() {
                    s.showDrawerStyleFromShortcut();
                }
            });
        }
    }

    static boolean beginKeyCapture(Context c) {
        return beginCapture(c, CAPTURE_PERF);
    }

    static boolean beginDrawerKeyCapture(Context c) {
        return beginCapture(c, CAPTURE_DRAWER);
    }

    static boolean beginPerfLogKeyCapture(Context c) {
        return beginCapture(c, CAPTURE_PERFLOG);
    }

    private static boolean beginCapture(final Context context, final int target) {
        if (context == null) return false;
        if (new Prefs(context).rootlessRemap()) {
            return AuroraA11yService.beginKeyCapture();
        }
        ensureStarted(context);
        captureTarget = target;
        capturing = true;
        captureDeadline = SystemClock.uptimeMillis() + 5000L;
        AuroraInputService s = instance;
        if (s != null) {
            s.main.removeCallbacks(s.captureTimeout);
            s.main.postDelayed(s.captureTimeout, 5200L);
        }
        LToast.makeText(context, target == CAPTURE_DRAWER
                ? "Press a controller button for the app drawer"
                : target == CAPTURE_PERFLOG
                ? "Press a controller button to open Perf Log options"
                : "Press a controller button for the overlay", Toast.LENGTH_LONG).show();
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        uiContext = Ui.adaptiveContext(this);
        running = true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Controller shortcuts starting"));
        if (!TouchMapManager.isRunning(this)
                && !TouchMapManager.isStarting()
                && !TouchMapService.isMapperEnabledForForeground(this)) {
            ControllerRemapManager.ensureInputDaemon(this);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingDrawerStyle) {
                    pendingDrawerStyle = false;
                    pendingDrawerToggle = false;
                    showDrawerStyleFromShortcut();
                } else if (pendingDrawerToggle) {
                    pendingDrawerToggle = false;
                    toggleDrawerOverlay();
                }
            }
        }, 250L);
        socketThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "AuroraControllerSocket");
        socketThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!shouldRun(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!TouchMapManager.isRunning(this)
                && !TouchMapManager.isStarting()
                && !TouchMapService.isMapperEnabledForForeground(this)) {
            ControllerRemapManager.ensureInputDaemon(this);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        hideDrawerOverlay();
        closeSocket();
        main.removeCallbacksAndMessages(null);
        instance = null;
        socketReady = false;
        capturing = false;
        captureDeadline = 0L;
        swallowWanted = false;
        pendingDrawerToggle = false;
        pendingDrawerStyle = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // Native socket
    // ------------------------------------------------------------------

    private void connectLoop() {
        while (running) {
            long now = SystemClock.uptimeMillis();
            if (!ControllerRemapManager.isRunning(this)
                    && !TouchMapManager.isRunning(this)
                    && !TouchMapManager.isStarting()
                    && !TouchMapService.isMapperEnabledForForeground(this)
                    && now - lastEnsureAt > 5000L) {
                lastEnsureAt = now;
                ControllerRemapManager.ensureInputDaemon(this);
            }
            LocalSocket local = null;
            try {
                local = new LocalSocket();
                local.connect(new LocalSocketAddress(ControllerRemapManager.SOCKET,
                        LocalSocketAddress.Namespace.ABSTRACT));
                synchronized (AuroraInputService.this) {
                    socket = local;
                    writer = new BufferedWriter(new OutputStreamWriter(
                            local.getOutputStream(), StandardCharsets.UTF_8));
                    reader = new BufferedReader(new InputStreamReader(
                            local.getInputStream(), StandardCharsets.UTF_8));
                }
                socketReady = true;
                updateNotification("Controller shortcuts active");
                sendSwallow(swallowWanted);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!capturing) return;
                        if (SystemClock.uptimeMillis() >= captureDeadline) {
                            capturing = false;
                            return;
                        }
                        main.removeCallbacks(captureTimeout);
                        main.postDelayed(captureTimeout, 5200L);
                    }
                });
                String line;
                while (running && (line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Throwable ignored) {
            } finally {
                socketReady = false;
                closeSocket();
                local = null;
                sleep(900L);
            }
        }
    }

    private synchronized void closeSocket() {
        socketReady = false;
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        try {
            if (writer != null) writer.close();
        } catch (Throwable ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
        reader = null;
        writer = null;
        socket = null;
    }

    private synchronized void sendCommand(String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.flush();
        } catch (Throwable ignored) {
        }
    }

    private void sendSwallow(boolean swallow) {
        swallowWanted = swallow;
        sendCommand("swallow " + (swallow ? 1 : 0) + "\n");
    }

    private void handleLine(String line) {
        if (line == null || line.length() < 3) return;
        char type = line.charAt(0);
        if (type != 'K' && type != 'A') return;
        int space = line.indexOf(' ', 2);
        if (space <= 2) return;
        try {
            final int code = Integer.parseInt(line.substring(2, space));
            final int value = Integer.parseInt(line.substring(space + 1).trim());
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (type == 'K') handleRawKey(code, value);
                    else handleRawAxis(code, value);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Controller events
    // ------------------------------------------------------------------

    private void handleRawKey(int raw, int value) {
        boolean down = value == 1;
        boolean up = value == 0;
        boolean repeat = value == 2;
        if (!down && !up && !repeat) return;

        AuroraA11yService.VisualListener visual = visualListener;
        if (visual != null && raw > 0 && (down || up)) {
            visual.onControllerVisual(raw, down);
        }
        if (visual != null && (raw == 102 || raw == 158)) return;
        if (raw <= 0) return;

        if (remapUiActive) return;
        if (isSystemKey(raw)) return;

        if (capturing) {
            if (!down || SystemClock.uptimeMillis() > captureDeadline) return;
            Prefs prefs = new Prefs(this);
            final int target = captureTarget;
            String key = RootlessUinput.linuxLabel(raw);
            if (key == null) return;
            if (target == CAPTURE_DRAWER) prefs.setDrawerControllerRaw(key);
            else if (target == CAPTURE_PERFLOG) prefs.setPerfLogControllerRaw(key);
            else prefs.setPerfControllerRaw(key);
            capturing = false;
            swallowCaptureUp = key;
            main.removeCallbacks(captureTimeout);
            final String label = friendlyKey(key);
            main.post(new Runnable() {
                @Override
                public void run() {
                    String prefix = target == CAPTURE_DRAWER ? "App drawer shortcut: "
                            : target == CAPTURE_PERFLOG ? "Perf Log popup shortcut: "
                            : "Controller shortcut: ";
                    LToast.makeText(AuroraInputService.this, prefix + label,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        String key = RootlessUinput.linuxLabel(raw);
        if (key == null) return;
        if (swallowCaptureUp != null) {
            if (key.equals(swallowCaptureUp) && up) {
                swallowCaptureUp = null;
                sendSwallow(drawerOverlayVisible);
            }
            return;
        }

        if (drawerOverlayVisible && handleDrawerControllerKey(raw, down, up, repeat)) {
            return;
        }
        handleShortcut(key, down, up, repeat);
    }

    private void handleRawAxis(int code, int value) {
        if (!drawerOverlayVisible) return;
        if (code == 16) {
            drawerHatX = value;
            updateDrawerHat();
            return;
        }
        if (code == 17) {
            drawerHatY = value;
            updateDrawerHat();
            return;
        }
        if (code == 5) {
            float v = Math.max(-1f, Math.min(1f, value / 32767f));
            drawerFastY = v;
            updateDrawerFast();
            return;
        }
        if (code != 0 && code != 1) return;
        float v = Math.max(-1f, Math.min(1f, value / 32767f));
        if (code == 0) drawerStickX = v;
        else drawerStickY = v;
        updateDrawerStick();
    }

    private static boolean isSystemKey(int raw) {
        return raw == 114 || raw == 115 || raw == 116 || raw == 102
                || raw == 158 || raw == 139;
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

    private void handleShortcut(final String key, boolean down, boolean up, boolean repeat) {
        Prefs prefs = new Prefs(this);
        if (PerfLogActivity.isVisible()) {
            String perfLogMapped = prefs.perfLogControllerRaw();
            if (perfLogMapped != null && !perfLogMapped.isEmpty()
                    && perfLogMapped.equals(key)) {
                if (down) {
                    if (perfLogMappedDown == null) {
                        perfLogMappedDown = key;
                        perfLogLongFired = false;
                        main.removeCallbacks(perfLogLongPress);
                        main.postDelayed(perfLogLongPress,
                                ViewConfiguration.getLongPressTimeout());
                    }
                } else if (up) {
                    main.removeCallbacks(perfLogLongPress);
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
                    main.removeCallbacks(drawerLongPress);
                    main.postDelayed(drawerLongPress,
                            ViewConfiguration.getLongPressTimeout());
                    if (!drawerOverlayVisible) {
                        toggleDrawerOverlay();
                        drawerShortcutShownOnDown = true;
                    }
                }
            } else if (up) {
                main.removeCallbacks(drawerLongPress);
                final boolean shortTap = drawerMappedDown != null && !drawerLongFired;
                final boolean shown = drawerShortcutShownOnDown;
                drawerMappedDown = null;
                drawerLongFired = false;
                drawerShortcutShownOnDown = false;
                if (shortTap && !shown) toggleDrawerOverlay();
            }
            return;
        }
        String mapped = prefs.perfControllerRaw();
        if (mapped == null || mapped.isEmpty() || !mapped.equals(key)) return;
        if (down) {
            if (mappedDown == null) {
                mappedDown = key;
                mappedLongFired = false;
                main.removeCallbacks(mappedLongPress);
                main.postDelayed(mappedLongPress, ViewConfiguration.getLongPressTimeout());
            }
        } else if (up) {
            main.removeCallbacks(mappedLongPress);
            final boolean shortTap = mappedDown != null && !mappedLongFired;
            mappedDown = null;
            mappedLongFired = false;
            if (shortTap) PerfOverlayService.toggleOverlayFromShortcut(this);
        }
    }

    // ------------------------------------------------------------------
    // Drawer navigation
    // ------------------------------------------------------------------

    /**
     * Feed a focused drawer window's key events through the exact same drawer
     * navigation used by the normal raw-input path. This is the mapper-mode bridge.
     */
    static boolean handleFocusedDrawerKey(KeyEvent event) {
        AuroraInputService s = instance;
        if (s == null || event == null || !s.drawerOverlayVisible) return false;
        int raw;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: raw = 544; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: raw = 545; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: raw = 546; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: raw = 547; break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: raw = 304; break;
            case KeyEvent.KEYCODE_BUTTON_B: raw = 305; break;
            default: return false;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        return s.handleDrawerControllerKey(raw, down, up, event.getRepeatCount() > 0);
    }

    /**
     * Feed the focused drawer's analogue axes through the normal-mode hat/stick and
     * right-stick scrolling machinery. This keeps hold acceleration and repeat rates
     * identical to the raw controller path.
     */
    static boolean handleFocusedDrawerMotion(MotionEvent event) {
        AuroraInputService s = instance;
        if (s == null || event == null || !s.drawerOverlayVisible) return false;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) == 0
                && (source & InputDevice.SOURCE_GAMEPAD) == 0) {
            return false;
        }
        s.drawerHatX = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_X));
        s.drawerHatY = Math.round(event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        s.drawerStickX = Math.max(-1f, Math.min(1f,
                event.getAxisValue(MotionEvent.AXIS_X)));
        s.drawerStickY = Math.max(-1f, Math.min(1f,
                event.getAxisValue(MotionEvent.AXIS_Y)));
        s.updateDrawerHat();
        s.updateDrawerStick();
        float right = StickScroll.rightY(event);
        if (Math.abs(right) < 0.12f) {
            right = event.getAxisValue(MotionEvent.AXIS_Z);
        }
        s.drawerFastY = Math.max(-1f, Math.min(1f, right));
        s.updateDrawerFast();
        return true;
    }

    private boolean handleDrawerControllerKey(int raw, boolean down, boolean up,
                                               boolean repeat) {
        int dx = 0;
        int dy = 0;
        switch (raw) {
            case 546:
                dx = -1;
                break;
            case 547:
                dx = 1;
                break;
            case 544:
                dy = -1;
                break;
            case 545:
                dy = 1;
                break;
            default:
                break;
        }
        boolean select = raw == 304 || raw == 28 || raw == 96;
        boolean back = raw == 305 || raw == 158;
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

    // ------------------------------------------------------------------
    // Floating drawer
    // ------------------------------------------------------------------

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

    private void showDrawerStyleFromShortcut() {
        if (drawerOverlay != null) {
            drawerOverlay.showStyleOverlay();
            return;
        }
        showDrawerOverlay(true);
    }

    private void showDrawerOverlay(final boolean showStyle) {
        if (drawerOpening || drawerOverlay != null) return;
        if (!Settings.canDrawOverlays(this) && !AuroraA11yService.hasAccessibility()) {
            LToast.makeText(this, "Allow Aurora to display over other apps first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;
        drawerOpening = true;
        // Sample the app behind the side drawer before attaching the overlay.
        new Thread(new Runnable() {
            @Override
            public void run() {
                Backdrop backdrop = ScreenCaptureBackdrop.captureSharp(AuroraInputService.this);
                if (backdrop == null) backdrop = WallpaperBackdrop.captureSharp(AuroraInputService.this);
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
        drawerMapperMode = TouchMapService.isMapperEnabledForForeground(this);
        view.setMapperMode(drawerMapperMode);
        int drawerFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!drawerMapperMode) {
            drawerFlags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                drawerFlags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        lp.setTitle("Aurora app drawer");
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        drawerOnAccessibility = false;
        // Prefer the trusted accessibility layer when it is available. The app
        // overlay path keeps the drawer working when accessibility is not bound.
        if (AuroraA11yService.hasAccessibility()) {
            drawerOnAccessibility = AuroraA11yService.addAccessibilityOverlay(view, lp);
        }
        if (!drawerOnAccessibility) {
            // addAccessibilityOverlay mutates lp.type before its addView attempt;
            // restore the application-overlay type for the fallback path.
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                windowManager.addView(view, lp);
            } catch (Throwable t) {
                drawerOpening = false;
                LToast.makeText(this, "Could not open the floating app drawer",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        drawerOverlay = view;
        drawerOverlayParams = lp;
        drawerOverlayVisible = true;
        TouchMapService.notifyModalOverlayChanged();
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // The trusted accessibility window disappears if that service
                // unbinds.  Stop swallowing input instead of leaving it stuck.
                if (drawerOverlay == v) hideDrawerOverlay();
            }
        });
        sendSwallow(true);
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
    }

    private void setDrawerFocusable(boolean focus) {
        if (drawerOverlay == null || drawerOverlayParams == null) return;
        if (drawerMapperMode || focus) {
            drawerOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            drawerOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (windowManager != null) {
            try {
                windowManager.updateViewLayout(drawerOverlay, drawerOverlayParams);
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
        drawerOverlayParams = null;
        drawerOverlayVisible = false;
        drawerOpening = false;
        TouchMapService.notifyModalOverlayChanged();
        drawerMapperMode = false;
        sendSwallow(false);
        resetDrawerNav();
        if (view == null) return;
        view.dismissKeyboard();
        if (drawerOnAccessibility) {
            AuroraA11yService.removeAccessibilityOverlay(view);
        } else if (windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        drawerOnAccessibility = false;
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Controller shortcuts", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Keeps mapped controller shortcuts and the floating drawer available");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(Loc.s(this, "Aurora controller"))
                .setContentText(Loc.s(this, text))
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }
}
