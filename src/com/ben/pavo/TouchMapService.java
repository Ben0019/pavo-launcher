package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches the selected game, starts/stops the virtual touch daemon and draws the
 * non-interactive button guide while that game is in front.
 */
public final class TouchMapService extends Service {
    private static TouchMapService instance;
    static final String ACTION_STOP = "com.ben.pavo.touchmap.STOP";
    static final String ACTION_REFRESH = "com.ben.pavo.touchmap.REFRESH";

    private static final String CHANNEL = "aurora_touch_mapper";
    private static final int NOTIFICATION_ID = 0x544D4150; // "TMAP"
    private static final long POLL_MS = 300L;
    private static final String SHORTCUT_SOCKET = "pavo-touchmap-shortcuts";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TouchMapStore store;
    private WindowManager windowManager;
    private TouchMapOverlayView overlay;
    private TouchMapFabView fab;
    private WindowManager.LayoutParams fabParams;
    private boolean fabShown;
    private TouchMapOverlayEditor mapEditor;
    private volatile boolean editorOpen;
    private boolean running;
    private boolean tickInFlight;
    private boolean reconfigure;
    private volatile boolean helperActive;
    private boolean remapperStopped;
    private String lastForegroundLog = "";
    private boolean pausedForPerf;
    private boolean perfOpenAtStart;
    private long lastStartAttempt;
    private String notificationText = "";
    private String shownTarget = "";
    private String activeTarget = "";
    private long inactiveSince;
    private String editorTarget = "";
    private String helperTarget = "";
    private volatile LocalServerSocket shortcutServer;
    private Thread shortcutThread;
    private int perfShortcutCode = -1;
    private int drawerShortcutCode = -1;
    private boolean perfShortcutDown;
    private boolean drawerShortcutDown;
    private boolean perfShortcutLongFired;
    private boolean drawerShortcutLongFired;
    private boolean perfShortcutShownOnDown;
    private boolean drawerShortcutShownOnDown;

    private final Runnable perfShortcutLongPress = new Runnable() {
        @Override
        public void run() {
            if (!perfShortcutDown) return;
            perfShortcutLongFired = true;
            PerfOverlayService.showMenuFromShortcut(TouchMapService.this);
            schedule(0L);
        }
    };

    private final Runnable drawerShortcutLongPress = new Runnable() {
        @Override
        public void run() {
            if (!drawerShortcutDown) return;
            drawerShortcutLongFired = true;
            if (!PavoA11yService.isFloatingDrawerVisible()) {
                pauseMapperForDrawer();
            }
            PavoInputService.showFloatingDrawerStyleFromShortcut(TouchMapService.this);
            schedule(0L);
        }
    };

    static void start(Context context) {
        if (context == null) return;
        TouchMapStore store = new TouchMapStore(context);
        if (!store.enabled()) return;
        try {
            Intent intent = new Intent(context, TouchMapService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, TouchMapService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Throwable ignored) {
            context.stopService(new Intent(context, TouchMapService.class));
        }
    }

    static void refresh(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context, TouchMapService.class);
            intent.setAction(ACTION_REFRESH);
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * True when the current foreground app has Touch Mapper enabled. This is the
     * signal used by the performance overlay and app drawer to decide whether they
     * must protect their input from the virtual mapper.
     */
    static boolean isMapperEnabledForForeground(Context c) {
        if (c == null || instance == null || !instance.running) return false;
        String pkg = ForegroundApp.read(c);
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(c.getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.equals("android")
                || pkg.equals("com.odin.gameassistant")
                || pkg.equals("com.android.launcher3")
                || pkg.equals("com.odin.odinlauncher")) {
            return false;
        }
        TouchMapStore store = new TouchMapStore(c);
        return store.enabled() && store.isAppEnabled(pkg);
    }

    /** Nudge the watcher so it pauses or resumes mapping as soon as a modal overlay changes. */
    static void notifyModalOverlayChanged() {
        final TouchMapService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.schedule(0L);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        store = new TouchMapStore(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Touch Mapper ready"));
        refreshShortcutCodes();
        startShortcutBridge();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            store.disableAllApps();
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!store.enabled()) {
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            reconfigure = true;
        }
        schedule(0L);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        cleanup();
        stopShortcutBridge();
        io.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void schedule(long delay) {
        main.removeCallbacks(tick);
        main.postDelayed(tick, delay);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (tickInFlight || !running) return;
            tickInFlight = true;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    boolean finish = processOnce();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            tickInFlight = false;
                            if (!running) return;
                            if (finish) {
                                cleanup();
                                stopSelf();
                            } else {
                                schedule(POLL_MS);
                            }
                        }
                    });
                }
            });
        }
    };

    /** @return true when the service should stop. */
    private boolean processOnce() {
        if (!store.enabled()) return true;
        if (!PServer.available()) {
            setNotification("PServerBinder unavailable");
            return true;
        }
        if (shortcutServer == null) startShortcutBridge();
        String rawPkg = foregroundPackage();
        boolean snapshotKnown = rawPkg != null && !rawPkg.isEmpty();
        if (!snapshotKnown) {
            // No snapshot at all (PServer busy or between dumpsys writes) means
            // "unknown", never "the game left". Keep whatever is running and wait
            // for the next poll instead of tearing down a working controller.
            inactiveSince = 0L;
            return false;
        }
        String pkg = usablePackage(rawPkg);
        boolean active = pkg != null && store.isAppEnabled(pkg);
        if (!rawPkg.equals(lastForegroundLog)) {
            lastForegroundLog = rawPkg;
            Log.i("AuroraTouchMap", "foreground " + rawPkg + " active=" + active);
        }
        if (active) {
            inactiveSince = 0L;
            activeTarget = pkg;
            if (editorOpen && !pkg.equals(editorTarget)) {
                discardEditor();
            }
            boolean perfPanel = PerfOverlayService.isOverlayPanelVisible();
            boolean drawerOpen = PavoA11yService.isFloatingDrawerVisible()
                    || PavoInputService.isFloatingDrawerVisible();
            boolean modalOverlay = PerfOverlayService.isMenuOpen()
                    || PerfOverlayService.isEditMode()
                    || drawerOpen;
            if (modalOverlay) {
                Log.i("AuroraTouchMap", "pause: menu=" + PerfOverlayService.isMenuOpen()
                        + " edit=" + PerfOverlayService.isEditMode()
                        + " drawer=" + drawerOpen);
                hideFab();
                hideOverlay();
                if (helperActive || TouchMapManager.isRunning(this)) {
                    TouchMapManager.stop(this);
                }
                // Every modal surface (drawer, overlay menu, edit mode) runs on
                // the normal pass-through pipeline. Without it the controller is
                // ungrabbed and no shortcut can close the menu again.
                ControllerRemapManager.ensureInputDaemon(this);
                helperActive = false;
                helperTarget = "";
                lastStartAttempt = 0L;
                setNotification("Mapping paused · overlay open");
                return false;
            }
            showFab();
            if (editorOpen) {
                hideOverlay();
                setNotification("Editing " + appLabel(pkg));
                return false;
            }
            if (perfPanel) {
                boolean mapping = helperActive || TouchMapManager.isRunning(this);
                if (!pausedForPerf && mapping && !perfOpenAtStart) {
                    // Opening the performance overlay hides the virtual controls
                    // and hands the controller back to the normal pass-through
                    // daemon, so the overlay can be used. Mapping resumes when it
                    // is closed.
                    pausedForPerf = true;
                    hideFab();
                    hideOverlay();
                    TouchMapManager.stop(this);
                    helperActive = false;
                    helperTarget = "";
                    lastStartAttempt = 0L;
                    remapperStopped = true;
                    ControllerRemapManager.ensureInputDaemon(this);
                    setNotification("Mapping paused · overlay open");
                    return false;
                }
                if (pausedForPerf) {
                    // Still open: stay paused until it is closed again.
                    hideFab();
                    hideOverlay();
                    return false;
                }
                // The panel was already open before mapping started; keep
                // mapping and only hide the guide so the overlay stays usable.
                hideOverlay();
            } else {
                pausedForPerf = false;
                perfOpenAtStart = false;
            }
            if (!pkg.equals(helperTarget)) {
                if (helperActive || TouchMapManager.isRunning(this)) {
                    TouchMapManager.stop(this);
                }
                helperActive = false;
                helperTarget = "";
                lastStartAttempt = 0L;
            }
            List<TouchTarget> layout = store.layout(pkg);
            boolean useStick = store.stickEnabled(pkg);
            TouchStick stick = store.stick(pkg);
            int rightMode = store.rightMode(pkg);
            RightStick right = new RightStick(rightMode, store.rightZone(pkg, rightMode));
            boolean rightHold = store.rightHold(pkg);
            List<TouchCombo> combos = store.combos(pkg);
            boolean buttonsHidden = store.buttonsHidden(pkg);
            if (helperActive && !TouchMapManager.isRunning(this)) {
                helperActive = false;
                helperTarget = "";
            }
            if (reconfigure) {
                reconfigure = false;
                TouchMapManager.stop(this);
                helperActive = false;
                helperTarget = "";
                lastStartAttempt = 0L;
            }
            if (!helperActive
                    && System.currentTimeMillis() - lastStartAttempt >= 1200L) {
                lastStartAttempt = System.currentTimeMillis();
                Log.i("AuroraTouchMap", "start helper for " + pkg);
                // If the overlay panel is already open when mapping starts, keep
                // mapping; only a panel opened later pauses the mapper.
                perfOpenAtStart = perfPanel;
                setNotification("Starting touch map for " + appLabel(pkg) + "…");
                helperActive = TouchMapManager.start(this, layout, stick, useStick, right,
                        combos, rightHold, remapperStopped);
                if (helperActive) helperTarget = pkg;
                remapperStopped = true;
            }
            if (helperActive) {
                if (!perfPanel) {
                    showOverlay(pkg, layout, stick, useStick, right, combos, buttonsHidden);
                }
                setNotification("Mapping " + appLabel(pkg)
                        + " · " + RightStick.label(right.mode));
            } else {
                hideOverlay();
                setNotification("Touch map unavailable · check PServer");
            }
            return false;
        }

        long now = System.currentTimeMillis();
        if (inactiveSince == 0L) inactiveSince = now;
        if (now - inactiveSince < 3000L) return false;
        inactiveSince = 0L;
        pausedForPerf = false;
        hideOverlay();
        hideFab();
        discardEditor();
        if (helperActive || TouchMapManager.isRunning(this)) {
            Log.i("AuroraTouchMap", "stop helper for " + activeTarget
                    + " (foreground " + rawPkg + ")");
            TouchMapManager.stop(this);
            helperActive = false;
            helperTarget = "";
        }
        if (remapperStopped) {
            remapperStopped = false;
            ControllerRemapManager.ensureStarted(this);
        }
        int count = store.enabledCount();
        setNotification(count == 1
                ? "Waiting for the enabled app"
                : "Waiting for " + count + " enabled apps");
        shownTarget = "";
        return false;
    }

    private void refreshShortcutCodes() {
        Prefs prefs = new Prefs(this);
        perfShortcutCode = RootlessUinput.rawForLabel(prefs.perfControllerRaw());
        drawerShortcutCode = RootlessUinput.rawForLabel(prefs.drawerControllerRaw());
        if (drawerShortcutCode == perfShortcutCode) drawerShortcutCode = -1;
    }

    private void startShortcutBridge() {
        if (shortcutServer != null) return;
        try {
            shortcutServer = new LocalServerSocket(SHORTCUT_SOCKET);
        } catch (Throwable t) {
            // Retried from the poll loop; a name left over by a dying process
            // must not disable the controller shortcuts for the whole session.
            Log.w("AuroraTouchMap", "shortcut bridge unavailable", t);
            shortcutServer = null;
            return;
        }
        Log.i("AuroraTouchMap", "shortcut bridge listening");
        shortcutThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && shortcutServer != null) {
                    LocalSocket client = null;
                    try {
                        client = shortcutServer.accept();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                client.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while (running && (line = reader.readLine()) != null) {
                            handleShortcutLine(line);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (client != null) {
                            try {
                                client.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }, "touchmap-shortcuts");
        shortcutThread.setDaemon(true);
        shortcutThread.start();
    }

    private void stopShortcutBridge() {
        LocalServerSocket server = shortcutServer;
        shortcutServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (Throwable ignored) {
            }
        }
        main.removeCallbacks(perfShortcutLongPress);
        main.removeCallbacks(drawerShortcutLongPress);
        perfShortcutDown = false;
        drawerShortcutDown = false;
    }

    private void pauseMapperForDrawer() {
        if (helperActive || TouchMapManager.isRunning(this)) {
            TouchMapManager.stop(this);
        }
        helperActive = false;
        helperTarget = "";
        lastStartAttempt = 0L;
    }

    private void handleShortcutLine(String line) {
        if (line == null) return;
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3 || !"K".equals(parts[0])) return;
        try {
            final int code = Integer.parseInt(parts[1]);
            final int value = Integer.parseInt(parts[2]);
            main.post(new Runnable() {
                @Override
                public void run() {
                    handleShortcutKey(code, value);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void handleShortcutKey(int code, int value) {
        // Route controller shortcuts through the normal pass-through handler so
        // they behave exactly as they do when Touch Mapper is not active.
        PavoInputService.handleRawKeyFromMapper(this, code, value);
        if (value == 1) schedule(0L);
    }

    private String foregroundPackage() {
        String cached = ForegroundApp.read(this);
        ForegroundApp.request(this);
        return cached == null || cached.isEmpty() ? null : cached;
    }

    private String usablePackage(String pkg) {
        if (pkg == null) return null;
        if (pkg.equals(getPackageName())) return null;
        if (pkg.equals("com.android.systemui")) return null;
        if (pkg.equals("android")) return null;
        if (pkg.equals("com.odin.gameassistant")) return null;
        if (pkg.equals("com.android.launcher3")) return null;
        if (pkg.equals("com.odin.odinlauncher")) return null;
        return pkg;
    }

    private void showOverlay(final String target, final List<TouchTarget> layout,
                             final TouchStick stick, final boolean useStick,
                             final RightStick right, final List<TouchCombo> combos,
                             final boolean buttonsHidden) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!running || editorOpen || !Settings.canDrawOverlays(TouchMapService.this)) return;
                if (overlay == null) {
                    overlay = new TouchMapOverlayView(TouchMapService.this);
                    try {
                        Backdrop guideBackdrop = ScreenCaptureBackdrop.capture(TouchMapService.this);
                        if (guideBackdrop != null) overlay.setBackdrop(guideBackdrop);
                    } catch (Throwable ignored) {
                    }
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.START;
                    lp.setTitle("Pavo touch guide");
                    try {
                        windowManager.addView(overlay, lp);
                    } catch (Throwable t) {
                        overlay = null;
                        return;
                    }
                }
                overlay.setLayout(layout, stick, useStick, right, combos);
                overlay.setButtonsHidden(buttonsHidden);
                overlay.setJoystickAppearance(
                        store.leftJoyHidden(target), store.leftJoyAlpha(target),
                        store.rightJoyHidden(target), store.rightJoyAlpha(target));
                overlay.bringToFront();
                shownTarget = target;
            }
        });
    }

    private void showFab() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!running || editorOpen
                        || !Settings.canDrawOverlays(TouchMapService.this)) return;
                final int size = Ui.dp(TouchMapService.this, 44);
                final android.util.DisplayMetrics dm =
                        getResources().getDisplayMetrics();
                if (fab == null) {
                    fab = new TouchMapFabView(TouchMapService.this);
                    int fx = store.fabX();
                    int fy = store.fabY();
                    if (fx == Integer.MIN_VALUE) fx = (dm.widthPixels - size) / 2;
                    if (fy == Integer.MIN_VALUE) fy = Ui.dp(TouchMapService.this, 10);
                    fx = Math.max(0, Math.min(dm.widthPixels - size, fx));
                    fy = Math.max(0, Math.min(dm.heightPixels - size, fy));
                    fabParams = new WindowManager.LayoutParams(
                            size, size,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    fabParams.gravity = Gravity.TOP | Gravity.START;
                    fabParams.x = fx;
                    fabParams.y = fy;
                    fabParams.setTitle("Pavo touch map handle");
                    fab.setOnTouchListener(new View.OnTouchListener() {
                        private float downRawX;
                        private float downRawY;
                        private int startX;
                        private int startY;
                        private boolean moved;

                        @Override
                        public boolean onTouch(View v, MotionEvent e) {
                            switch (e.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    downRawX = e.getRawX();
                                    downRawY = e.getRawY();
                                    startX = fabParams.x;
                                    startY = fabParams.y;
                                    moved = false;
                                    Haptics.tick(v);
                                    return true;
                                case MotionEvent.ACTION_MOVE: {
                                    float dx = e.getRawX() - downRawX;
                                    float dy = e.getRawY() - downRawY;
                                    if (Math.abs(dx) > Ui.dp(TouchMapService.this, 4)
                                            || Math.abs(dy) > Ui.dp(TouchMapService.this, 4)) {
                                        moved = true;
                                    }
                                    int maxX = Math.max(0, dm.widthPixels - size);
                                    int maxY = Math.max(0, dm.heightPixels - size);
                                    fabParams.x = Math.max(0, Math.min(maxX,
                                            Math.round(startX + dx)));
                                    fabParams.y = Math.max(0, Math.min(maxY,
                                            Math.round(startY + dy)));
                                    try {
                                        windowManager.updateViewLayout(fab, fabParams);
                                    } catch (Throwable ignored) {
                                    }
                                    store.saveFabPosition(fabParams.x, fabParams.y);
                                    return true;
                                }
                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL:
                                    store.saveFabPosition(fabParams.x, fabParams.y);
                                    if (!moved) {
                                        Haptics.click(fab);
                                        toggleEditor();
                                    }
                                    return true;
                            }
                            return false;
                        }
                    });
                }
                if (fab != null && fabParams != null) {
                    int sx = store.fabX();
                    int sy = store.fabY();
                    if (sx != Integer.MIN_VALUE && sy != Integer.MIN_VALUE) {
                        int maxX = Math.max(0, dm.widthPixels - size);
                        int maxY = Math.max(0, dm.heightPixels - size);
                        fabParams.x = Math.max(0, Math.min(maxX, sx));
                        fabParams.y = Math.max(0, Math.min(maxY, sy));
                        if (fabShown) {
                            try {
                                windowManager.updateViewLayout(fab, fabParams);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                fab.setEditing(editorOpen);
                if (!fabShown) {
                    try {
                        windowManager.addView(fab, fabParams);
                        fabShown = true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private void hideFab() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (fab == null || !fabShown) return;
                try {
                    windowManager.removeView(fab);
                } catch (Throwable ignored) {
                }
                fabShown = false;
            }
        });
    }


    private void toggleEditor() {
        if (fabParams != null && fab != null) {
            store.saveFabPosition(fabParams.x, fabParams.y);
        }
        if (editorOpen) closeEditor();
        else openEditor();
    }

    private void openEditor() {
        if (editorOpen || !Settings.canDrawOverlays(this)) return;
        String target = !activeTarget.isEmpty() ? activeTarget : store.target();
        if (target.isEmpty() || !store.isAppEnabled(target)) return;
        editorOpen = true;
        editorTarget = target;
        hideOverlay();
        hideFab();
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (TouchMapManager.isRunning(TouchMapService.this)) {
                    TouchMapManager.stop(TouchMapService.this);
                }
                helperActive = false;
                remapperStopped = true;
            }
        });
        mapEditor = new TouchMapOverlayEditor(this, target, appLabel(target), store,
                new TouchMapOverlayEditor.Listener() {
                    @Override
                    public void onClose() {
                        closeEditor();
                    }
                });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Pavo touch map editor");
        try {
            windowManager.addView(mapEditor, lp);
        } catch (Throwable t) {
            mapEditor = null;
            editorOpen = false;
            return;
        }
        setNotification("Editing " + appLabel(target));
    }

    private void closeEditor() {
        if (!editorOpen) return;
        editorOpen = false;
        editorTarget = "";
        if (mapEditor != null) {
            mapEditor.save(false);
            try {
                windowManager.removeView(mapEditor);
            } catch (Throwable ignored) {
            }
            mapEditor = null;
        }
        if (fab != null) fab.setEditing(false);
        showFab();
        helperActive = false;
        lastStartAttempt = 0L;
        setNotification("Applying touch map…");
        schedule(0L);
    }

    private void discardEditor() {
        if (!editorOpen && mapEditor == null) return;
        editorOpen = false;
        editorTarget = "";
        final TouchMapOverlayEditor e = mapEditor;
        mapEditor = null;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (e != null) {
                    e.save(false);
                    try {
                        windowManager.removeView(e);
                    } catch (Throwable ignored) {
                    }
                }
                if (fab != null) fab.setEditing(false);
                showFab();
            }
        });
    }

    private void hideOverlay() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (overlay == null) return;
                try {
                    windowManager.removeView(overlay);
                } catch (Throwable ignored) {
                }
                overlay = null;
            }
        });
    }

    private void cleanup() {
        hideOverlay();
        hideFab();
        discardEditor();
        if (helperActive || TouchMapManager.isRunning(this)) {
            TouchMapManager.stop(this);
        }
        helperActive = false;
        helperTarget = "";
        activeTarget = "";
        if (remapperStopped) {
            remapperStopped = false;
            ControllerRemapManager.ensureStarted(this);
        }
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
            return pkg == null ? "game" : pkg;
        }
    }

    private void setNotification(String text) {
        if (text == null || text.equals(notificationText)) return;
        notificationText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, TouchMapperActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 84, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TouchMapService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 85, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(Loc.s(this, "Touch Mapper"))
                .setContentText(Loc.s(this, text))
                .setContentIntent(content)
                .addAction(0, "DISABLE", stopIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Touch Mapper", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Maps the controller to on-screen touch controls");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
