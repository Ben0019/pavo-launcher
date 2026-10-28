package com.ben.aurora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.provider.Settings;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that owns the draggable performance overlay. It works above other
 * apps because it uses a TYPE_APPLICATION_OVERLAY window and SYSTEM_ALERT_WINDOW.
 *
 * The panel is rendered at a fixed base size and then scaled inside its window. This lets
 * the same view handle pinch-to-resize without rebuilding the metric rows.
 */
public final class PerfOverlayService extends Service implements PerfOverlayMenuView.Listener {
    static final String ACTION_STOP = "com.ben.aurora.action.STOP_PERF_OVERLAY";
    private static final String CHANNEL_ID = "aurora_performance";
    private static final int NOTIFICATION_ID = 4211;
    private static final int BASE_WIDTH_DP = 300;

    private static PerfOverlayService instance;
    private static volatile boolean quiet;
    private static boolean pendingMenuFromShortcut;
    private static boolean pendingEditFromShortcut;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private android.content.Context uiContext;
    private PerfSampler sampler;
    private PowerManager powerManager;
    private WindowManager windowManager;

    private FrameLayout container;
    private PerfOverlayView panel;
    private WindowManager.LayoutParams params;
    private boolean editMode;
    private boolean mapperMode;
    private boolean mapperCheckInFlight;
    private int baseWidthPx;
    private int baseHeightPx;
    private float scale = 1f;

    private PerfOverlayMenuView menu;
    private WindowManager.LayoutParams menuParams;
    private boolean menuOnAccessibility;

    private PerfDetailView detail;
    private WindowManager.LayoutParams detailParams;
    private boolean detailOnAccessibility;
    private boolean detailOpen;
    private static boolean pendingDetail;

    private PerfStats lastStats;
    private String sessionPkg;
    private String sessionCls;
    private String sessionLabel;
    private String backdropPkg;
    private int backdropToken;
    private long sessionStartMs;
    private int sessionStartBattery = -1;
    private long sessionStartChargeUah = -1;
    private int fpsSamples;
    private long fpsSum;
    private int fpsMin = Integer.MAX_VALUE;
    private int fpsMax;
    private float peakPowerW;
    private final ArrayDeque<Integer> fpsHistory = new ArrayDeque<>();

    private PerfLogStore logStore;
    private PerfLogEntry currentLog;
    private long lastLogSaveMs;
    private long lastLogCleanupMs;

    private boolean running;
    private boolean sampling;
    private int touchSlop;

    private float downRawX, downRawY;
    private int downX, downY;
    private boolean moved, pinching, longPressed, gestureLocked;
    private boolean overlayLocked, lockWarned;
    private long lastTapUpMs;
    private float lastTapUpX, lastTapUpY;
    private float pinchStartSpan, pinchStartScale;
    private int pinchCenterX, pinchCenterY;
    private float targetScale;
    private boolean scaleTickerRunning;
    private boolean scaleSettleSave;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!running || menu != null || pinching || moved || longPressed) return;
            longPressed = true;
            openMenu();
        }
    };

    private final Runnable scaleTicker = new Runnable() {
        @Override
        public void run() {
            if (!running || container == null) {
                scaleTickerRunning = false;
                return;
            }
            float diff = targetScale - scale;
            if (Math.abs(diff) < 0.002f) {
                scale = targetScale;
                applyScaleAt(scale, pinchCenterX, pinchCenterY);
                scaleTickerRunning = false;
                if (scaleSettleSave) {
                    scaleSettleSave = false;
                    saveGeometry();
                }
                return;
            }
            scale += diff * 0.24f;
            applyScaleAt(scale, pinchCenterX, pinchCenterY);
            main.postDelayed(this, 16);
        }
    };

    /**
     * Mapper-mode polling is deliberately independent of the metric refresh and
     * the burn-in quiet window.  Closing a game with the system back gesture must
     * return the overlay to touch mode even when stats redraws are paused.
     */
    private final Runnable mapperTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            syncMapperMode();
            main.postDelayed(this, 500L);
        }
    };

    private final Runnable sampleTick = new Runnable() {
        @Override
        public void run() {
            if (!running || quiet) return;
            cleanupOldLogsIfDue();
            if (powerManager != null && !powerManager.isInteractive()) {
                main.postDelayed(this, prefs.perfRefreshMs());
                return;
            }
            if (!sampling) {
                sampling = true;
                final boolean wantFps = prefs.perfFps() || prefs.perfFpsGraph()
                        || detailOpen || detail != null || prefs.perfLogging();
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        final PerfStats stats = sampler.sample(PerfOverlayService.this, wantFps);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                sampling = false;
                                updateSession(stats);
                                if (running && panel != null) panel.update(stats);
                                if (detail != null) detail.update(buildDetail());
                            }
                        });
                    }
                });
            }
            main.postDelayed(this, prefs.perfRefreshMs());
        }
    };

    static void start(Context c) {
        if (!Settings.canDrawOverlays(c)) return;
        Intent i = new Intent(c, PerfOverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, PerfOverlayService.class));
        } catch (Throwable ignored) {
        }
    }

    static void refreshIfRunning() {
        PerfOverlayService s = instance;
        if (s != null) {
            s.main.post(new Runnable() {
                @Override
                public void run() {
                    s.applyPrefs();
                }
            });
        }
    }

    static boolean isRunning() {
        return instance != null;
    }

    /** Freeze overlay redraws while the launcher is giving the system pixel shifter a quiet window. */
    static void setQuiet(boolean value) {
        quiet = value;
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.main.removeCallbacks(s.sampleTick);
                if (!quiet && s.running) s.main.post(s.sampleTick);
            }
        });
    }

    static boolean isMenuOpen() {
        return instance != null && instance.menu != null;
    }

    /** Route raw controller buttons to the open overlay control menu. */
    static void handleMenuDirection(final int dir) {
        final PerfOverlayService s = instance;
        if (s == null || s.menu == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                if (s.menu != null) s.menu.handleControllerDirection(dir);
            }
        });
    }

    static boolean handleMenuControllerKey(String key, boolean down, boolean up,
                                           boolean repeat) {
        final PerfOverlayService s = instance;
        if (s == null || s.menu == null) return false;
        boolean nav = "BTN_DPAD_UP".equals(key) || "BTN_DPAD_DOWN".equals(key)
                || "BTN_DPAD_LEFT".equals(key) || "BTN_DPAD_RIGHT".equals(key)
                || "KEY_UP".equals(key) || "KEY_DOWN".equals(key)
                || "KEY_LEFT".equals(key) || "KEY_RIGHT".equals(key);
        boolean select = "BTN_SOUTH".equals(key) || "BTN_GAMEPAD".equals(key)
                || "KEY_ENTER".equals(key);
        boolean back = "BTN_EAST".equals(key) || "KEY_BACK".equals(key);
        if (!nav && !select && !back) return false;
        if (down || repeat) {
            if (repeat && (select || back)) return true;
            final String raw = key;
            final boolean isRepeat = repeat;
            s.main.post(new Runnable() {
                @Override
                public void run() {
                    if (s.menu != null) s.menu.handleControllerKey(raw, isRepeat);
                }
            });
        }
        return true;
    }

    static void resetPosition() {
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                if (s.params == null) return;
                if (s.overlayLocked) {
                    Haptics.longPress(s.container);
                    LToast.makeText(s, "Performance overlay is locked",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                s.params.x = -1;
                s.params.y = -1;
                s.clampPosition(false);
            }
        });
    }

    static void toggleOverlayFromShortcut(Context c) {
        PerfOverlayService s = instance;
        if (s == null) {
            Haptics.click(c);
            new Prefs(c).setPerfOverlay(true);
            start(c);
            return;
        }
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.toggleOverlayPanel();
            }
        });
    }

    static void flushCurrentSession() {
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.finalizeCurrentLog();
            }
        });
    }

    static void showDetailFromShortcut(Context c) {
        final PerfOverlayService s = instance;
        if (s == null) {
            if (!Settings.canDrawOverlays(c)) {
                LToast.makeText(c, "Display over other apps permission is required",
                        Toast.LENGTH_LONG).show();
                return;
            }
            new Prefs(c).setPerfOverlay(true);
            pendingDetail = true;
            start(c);
            return;
        }
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.openDetail();
            }
        });
    }

    static void enterEditModeFromShortcut(Context c) {
        if (c == null) return;
        final PerfOverlayService s = instance;
        if (s == null) {
            Haptics.longPress(c);
            new Prefs(c).setPerfOverlay(true);
            pendingEditFromShortcut = true;
            start(c);
            return;
        }
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.enterEditMode();
            }
        });
    }

    static void showMenuFromShortcut(Context c) {
        final PerfOverlayService s = instance;
        if (s == null) {
            Haptics.longPress(c);
            new Prefs(c).setPerfOverlay(true);
            pendingMenuFromShortcut = true;
            start(c);
            return;
        }
        s.main.post(new Runnable() {
            @Override
            public void run() {
                if (s.menu != null) {
                    // Menu is open. A long press closes both surfaces, unless the
                    // performance panel was already hidden.
                    boolean panelVisible = s.container != null
                            && s.container.getVisibility() == View.VISIBLE;
                    s.closeMenu();
                    if (panelVisible) s.hideOverlayPanel();
                } else {
                    s.ensureOverlayShown();
                    s.openMenu();
                }
            }
        });
    }

    private void toggleOverlayPanel() {
        if (container == null) return;
        if (menu != null) {
            // While the popup is open a short press only dismisses the popup.
            closeMenu();
            return;
        }
        boolean visible = container.getVisibility() == View.VISIBLE;
        if (visible) hideOverlayPanel();
        else showOverlayPanel();
    }

    private void showOverlayPanel() {
        if (container == null) return;
        Haptics.key(container);
        container.setVisibility(View.VISIBLE);
        applyCardTouchability();
        capturePanelBackdrop();
    }

    private void hideOverlayPanel() {
        if (container == null) return;
        if (editMode) exitEditMode();
        Haptics.key(container);
        setOverlayTouchable(false);
        container.setVisibility(View.INVISIBLE);
    }

    private void toggleOverlayLock() {
        overlayLocked = !overlayLocked;
        prefs.setPerfOverlayLocked(overlayLocked);
        if (panel != null) panel.setLocked(overlayLocked);
        if (overlayLocked) saveGeometry();
        Haptics.longPress(container);
        LToast.makeText(this, overlayLocked
                ? "Performance overlay locked"
                : "Performance overlay unlocked", Toast.LENGTH_SHORT).show();
    }

    private void ensureOverlayShown() {
        if (container == null) return;
        container.setVisibility(View.VISIBLE);
        applyCardTouchability();
    }

    static boolean isEditMode() {
        PerfOverlayService s = instance;
        return s != null && s.editMode;
    }

    private void enterEditMode() {
        if (!running || container == null || panel == null) return;
        if (!mapperMode) {
            editMode = false;
            applyCardTouchability();
            return;
        }
        if (menu != null) closeMenu();
        editMode = true;
        container.setVisibility(View.VISIBLE);
        panel.setEditMode(true, new Runnable() {
            @Override
            public void run() {
                exitEditMode();
            }
        });
        measurePanel();
        applyScaleGeometry(false);
        applyCardTouchability();
        Haptics.longPress(container);
        TouchMapService.notifyModalOverlayChanged();
    }

    private void exitEditMode() {
        if (!editMode) return;
        editMode = false;
        if (panel != null) {
            panel.setEditMode(false, null);
            measurePanel();
            applyScaleGeometry(false);
        }
        applyCardTouchability();
        saveGeometry();
        Haptics.key(container);
        TouchMapService.notifyModalOverlayChanged();
    }

    private void syncMapperMode() {
        if (mapperCheckInFlight) return;
        mapperCheckInFlight = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean enabled = false;
                String foreground = null;
                try {
                    // The accessibility service already knows the focused window,
                    // so avoid three dumpsys calls every poll. Fall back to the
                    // PServer snapshot only when accessibility is unavailable.
                    foreground = AuroraA11yService.focusedPackage();
                    if (foreground == null) {
                        ForegroundApp.request(PerfOverlayService.this);
                        foreground = ForegroundApp.read(PerfOverlayService.this);
                    }
                    enabled = TouchMapService.isMapperEnabledForForeground(PerfOverlayService.this);
                } catch (Throwable ignored) {
                }
                final boolean now = enabled;
                final String pkg = foreground;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        mapperCheckInFlight = false;
                        if (!running) return;
                        applyMapperMode(now);
                        maybeRefreshBackdrop(pkg);
                    }
                });
            }
        });
    }

    private void maybeRefreshBackdrop(String pkg) {
        if (pkg == null || pkg.isEmpty() || isOwnPackage(pkg)) return;
        if (backdropPkgEquals(pkg)) return;
        backdropPkg = pkg;
        final int token = ++backdropToken;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running && token == backdropToken) capturePanelBackdrop();
            }
        }, 80L);
    }

    private void applyMapperMode(boolean now) {
        // While the controls card is open the focused package is Aurora, not
        // the game behind it. Keep the menu's mapper affordances stable until
        // it closes; the next poll will update the panel.
        if (menu != null) return;
        if (now == mapperMode) return;
        mapperMode = now;
        if (!mapperMode && editMode) {
            exitEditMode();
        } else {
            applyCardTouchability();
        }
    }

    private void applyCardTouchability() {
        if (container == null || params == null) return;
        params.alpha = 1f;
        boolean touchable = (editMode || !mapperMode) && menu == null
                && container.getVisibility() == View.VISIBLE;
        if (touchable) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            windowManager.updateViewLayout(container, params);
        } catch (Throwable ignored) {
        }
    }

    static boolean isOverlayPanelVisible() {
        PerfOverlayService s = instance;
        return s != null && s.container != null
                && s.container.getVisibility() == View.VISIBLE;
    }

    static boolean hasAnyOverlayVisible() {
        PerfOverlayService s = instance;
        return s != null && ((s.container != null
                && s.container.getVisibility() == View.VISIBLE)
                || s.menu != null || s.detail != null);
    }

    /** Close every performance overlay surface before an inbuilt app shows. */
    static void hideForApp() {
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                if (s.menu != null) s.closeMenu();
                if (s.detail != null) s.closeDetail();
                if (s.container != null) {
                    if (s.editMode) s.exitEditMode();
                    s.container.setVisibility(View.INVISIBLE);
                    s.applyCardTouchability();
                }
            }
        });
    }

    static void setOverlayVisible(final boolean visible) {
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                if (s.container != null) {
                    if (!visible && s.editMode) s.exitEditMode();
                    s.container.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                    s.applyCardTouchability();
                }
            }
        });
    }

    static void setOverlayTouchable(final boolean touchable) {
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                boolean allow = touchable && (s.editMode || !s.mapperMode)
                        && s.menu == null;
                if (s.container == null || s.params == null) return;
                if (allow) {
                    s.params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    s.params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                try {
                    s.windowManager.updateViewLayout(s.container, s.params);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static void setOverlayScale(final float requested) {
        final PerfOverlayService s = instance;
        if (s == null) return;
        s.main.post(new Runnable() {
            @Override
            public void run() {
                s.targetScale = s.clampScale(requested);
                s.scale = s.targetScale;
                s.scaleTickerRunning = false;
                s.scaleSettleSave = false;
                s.main.removeCallbacks(s.scaleTicker);
                s.applyScaleGeometry(true);
                s.prefs.setPerfOverlayScale(s.scale);
            }
        });
    }

    private Backdrop lastPanelBackdrop;
    private int burnInIndex;
    private final java.util.Random burnInRandom = new java.util.Random();
    private final Runnable burnInTick = new Runnable() {
        @Override
        public void run() {
            if (!running || panel == null || container == null
                    || menu != null || editMode) {
                main.postDelayed(this, 30000L);
                return;
            }
            burnInIndex = (burnInIndex + 1) % BurnInShift.OFFSETS.length;
            int[] offset = BurnInShift.OFFSETS[burnInIndex];
            panel.animate().translationX(offset[0]).translationY(offset[1])
                    .setDuration(450L).start();
            long jitter = (long) ((burnInRandom.nextDouble() * 2.0 - 1.0) * 15000L);
            main.postDelayed(this, 120000L + jitter);
        }
    };

    private View dimOverlay;
    private final Runnable dimTick = new Runnable() {
        @Override
        public void run() {
            if (!running || dimOverlay == null || menu != null || editMode) {
                main.postDelayed(this, 30000L);
                return;
            }
            dimOverlay.animate().cancel();
            dimOverlay.animate().alpha(0.10f).setDuration(1200L).start();
        }
    };

    private void scheduleOverlayDim() {
        main.removeCallbacks(dimTick);
        main.postDelayed(dimTick, 5L * 60L * 1000L);
    }

    private void clearOverlayDim() {
        main.removeCallbacks(dimTick);
        if (dimOverlay != null) {
            dimOverlay.animate().cancel();
            dimOverlay.animate().alpha(0f).setDuration(150L).start();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        uiContext = Ui.adaptiveContext(this);
        sampler = new PerfSampler();
        logStore = new PerfLogStore(this);
        logStore.load();
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        if (!Settings.canDrawOverlays(this) || !prefs.perfOverlay()) {
            prefs.setPerfOverlay(false);
            stopSelf();
            return;
        }
        ensureScreenshotAccessibility();
        addOverlay();
        running = true;
        main.removeCallbacks(mapperTick);
        main.post(sampleTick);
        main.post(mapperTick);
        main.removeCallbacks(burnInTick);
        main.postDelayed(burnInTick, 60000L);
        scheduleOverlayDim();
        if (pendingMenuFromShortcut) {
            pendingMenuFromShortcut = false;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    openMenu();
                }
            }, 300);
        }
        if (pendingEditFromShortcut) {
            pendingEditFromShortcut = false;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    enterEditMode();
                }
            }, 300);
        }
        if (pendingDetail) {
            pendingDetail = false;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    openDetail();
                }
            }, 450);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.setPerfOverlay(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (prefs != null && !prefs.perfOverlay()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (container == null && Settings.canDrawOverlays(this)) {
            addOverlay();
            running = true;
            main.removeCallbacks(sampleTick);
            main.removeCallbacks(mapperTick);
            main.post(sampleTick);
            main.post(mapperTick);
            main.removeCallbacks(burnInTick);
            main.postDelayed(burnInTick, 60000L);
        } else {
            applyPrefs();
            main.removeCallbacks(mapperTick);
            main.post(mapperTick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        quiet = false;
        main.removeCallbacks(sampleTick);
        main.removeCallbacks(mapperTick);
        main.removeCallbacks(burnInTick);
        main.removeCallbacks(dimTick);
        dimOverlay = null;
        finalizeCurrentLog();
        closeMenu();
        closeDetail();
        worker.shutdownNow();
        if (container != null && windowManager != null) {
            try {
                windowManager.removeView(container);
            } catch (Throwable ignored) {
            }
            container = null;
            panel = null;
        }
        if (instance == this) instance = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (container != null) container.post(new Runnable() {
            @Override
            public void run() {
                applyScaleGeometry(false);
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, Loc.s(this, "Performance overlay"),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Live CPU, GPU, FPS and battery stats above other apps");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, PerfOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 11, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_perf)
                .setContentTitle(Loc.s(this, "Aurora performance overlay"))
                .setContentText(Loc.s(this, "CPU, GPU, FPS and battery live above other apps"))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent);
        return b.build();
    }

    /**
     * The zero-blink window capture needs Aurora's own accessibility service.
     * Android removes accessibility services when the package is force-stopped
     * or updated, so restore the previously available screenshot capability via
     * the privileged launcher path when needed.
     */
    private void ensureScreenshotAccessibility() {
        if (Build.VERSION.SDK_INT < 30) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String component = getPackageName() + "/"
                            + AuroraA11yService.class.getName();
                    String current = PerfTuner.Root.run(
                            "settings get secure enabled_accessibility_services").trim();
                    if ("null".equals(current)) current = "";
                    if (!current.contains(component)) {
                        String value = current.isEmpty() ? component
                                : current + ":" + component;
                        PerfTuner.Root.write("settings put secure "
                                + "enabled_accessibility_services '" + value + "'");
                    }
                    PerfTuner.Root.write("settings put secure accessibility_enabled 1");
                } catch (Throwable ignored) {
                }
            }
        }, "overlay-screenshot-accessibility").start();
    }

    private void addOverlay() {
        if (container != null) return;
        baseWidthPx = Ui.dp(uiContext, BASE_WIDTH_DP);

        container = new FrameLayout(uiContext);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        container.setClickable(true);
        container.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return overlayTouch(e);
            }
        });

        panel = new PerfOverlayView(uiContext);
        overlayLocked = prefs.perfOverlayLocked();
        panel.applyPrefs(prefs);
        panel.setLocked(overlayLocked);
        panel.setPanelAlpha(prefs.perfOverlayAlpha());
        panel.setPivotX(0f);
        panel.setPivotY(0f);
        measurePanel();
        scale = clampScale(prefs.perfOverlayScale());
        targetScale = scale;
        panel.setScaleX(scale);
        panel.setScaleY(scale);
        container.addView(panel, new FrameLayout.LayoutParams(baseWidthPx, baseHeightPx));
        dimOverlay = new View(uiContext);
        dimOverlay.setBackgroundColor(0xFF000000);
        dimOverlay.setAlpha(0f);
        dimOverlay.setClickable(false);
        dimOverlay.setFocusable(false);
        container.addView(dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mapperMode = TouchMapService.isMapperEnabledForForeground(this);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params = new WindowManager.LayoutParams(
                Math.round(baseWidthPx * scale),
                Math.round(baseHeightPx * scale),
                type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.alpha = 1f;
        params.setTitle("Aurora performance overlay");
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        params.x = prefs.perfOverlayX();
        params.y = prefs.perfOverlayY();

        try {
            windowManager.addView(container, params);
        } catch (Throwable ignored) {
            container = null;
            panel = null;
            return;
        }
        container.post(new Runnable() {
            @Override
            public void run() {
                clampPosition(false);
            }
        });
        applyCardTouchability();
    }

    private void capturePanelBackdrop() {
        if (container == null || panel == null || menu != null || detail != null) return;
        final PerfOverlayView view = panel;
        // Preferred path: capture only the app window through accessibility, so
        // the overlay never has to disappear (no blink).
        boolean requested = AuroraA11yService.captureApplicationWindow(
                new AuroraA11yService.WindowCaptureCallback() {
                    @Override
                    public void onCaptured(Bitmap bitmap, Rect bounds) {
                        final Backdrop backdrop = ScreenCaptureBackdrop.fromWindow(
                                PerfOverlayService.this, bitmap, bounds);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (panel == view && running) {
                                    lastPanelBackdrop = backdrop;
                                    view.setBackdrop(backdrop);
                                    applyCardTouchability();
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailed() {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (panel == view && running) capturePanelBackdropLegacy();
                            }
                        });
                    }
                });
        if (!requested) capturePanelBackdropLegacy();
    }

    private void capturePanelBackdropLegacy() {
        if (container == null || panel == null || menu != null || detail != null) return;
        final FrameLayout target = container;
        final PerfOverlayView view = panel;
        final boolean wasVisible = target.getVisibility() == View.VISIBLE;
        final float oldAlpha = target.getAlpha();
        // Keep a trace of the overlay in the captured frame instead of hiding it
        // completely. The screenshot only sees a faint ghost, while the user sees
        // a quick fade rather than a hard blink.
        if (wasVisible) {
            target.setAlpha(0.08f);
        } else {
            target.setVisibility(View.INVISIBLE);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        Backdrop backdrop = ScreenCaptureBackdrop.capture(PerfOverlayService.this);
                        if (backdrop == null) {
                            backdrop = WallpaperBackdrop.capture(PerfOverlayService.this);
                        }
                        final Backdrop captured = backdrop;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (panel == view) {
                                    if (captured != null) lastPanelBackdrop = captured;
                                    view.setBackdrop(captured);
                                    target.setVisibility(wasVisible ? View.VISIBLE : View.INVISIBLE);
                                    if (wasVisible) {
                                        target.setAlpha(0.08f);
                                        target.animate().alpha(oldAlpha)
                                                .setDuration(140L).start();
                                    } else {
                                        target.setAlpha(oldAlpha);
                                    }
                                    applyCardTouchability();
                                }
                            }
                        });
                    }
                });
            }
        }, 90L);
    }

    private void measurePanel() {
        if (panel == null) return;
        int widthSpec = View.MeasureSpec.makeMeasureSpec(baseWidthPx, View.MeasureSpec.EXACTLY);
        panel.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        baseHeightPx = Math.max(Ui.dp(uiContext, 120), panel.getMeasuredHeight());
        FrameLayout.LayoutParams lp = panel.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) panel.getLayoutParams() : null;
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(baseWidthPx, baseHeightPx);
            panel.setLayoutParams(lp);
        } else {
            lp.width = baseWidthPx;
            lp.height = baseHeightPx;
        }
    }

    private float clampScale(float wanted) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        float maxW = (sw - Ui.dp(uiContext, 12)) / (float) Math.max(1, baseWidthPx);
        float maxH = (sh - Ui.dp(uiContext, 12)) / (float) Math.max(1, baseHeightPx);
        float max = Math.min(1.8f, Math.min(maxW, maxH));
        max = Math.max(0.55f, max);
        return Math.max(0.55f, Math.min(max, wanted));
    }

    private void applyScaleGeometry(boolean keepCenter) {
        if (container == null || panel == null || params == null) return;
        scale = clampScale(scale);
        int newW = Math.round(baseWidthPx * scale);
        int newH = Math.round(baseHeightPx * scale);
        int cx = params.x + params.width / 2;
        int cy = params.y + params.height / 2;
        params.width = newW;
        params.height = newH;
        panel.setScaleX(scale);
        panel.setScaleY(scale);
        if (keepCenter) {
            params.x = cx - newW / 2;
            params.y = cy - newH / 2;
        }
        clampPosition(false);
    }

    private void applyPrefs() {
        if (panel == null) return;
        panel.applyPrefs(prefs);
        panel.setPanelAlpha(prefs.perfOverlayAlpha());
        measurePanel();
        applyScaleGeometry(false);
    }

    private boolean overlayTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (menu != null) return false;
                if (panel != null) {
                    panel.setTranslationX(0f);
                    panel.setTranslationY(0f);
                }
                burnInIndex = 0;
                clearOverlayDim();
                scheduleOverlayDim();
                main.removeCallbacks(longPress);
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = params.x;
                downY = params.y;
                moved = false;
                pinching = false;
                longPressed = false;
                gestureLocked = false;
                lockWarned = false;
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() < 2) return true;
                main.removeCallbacks(longPress);
                if (overlayLocked) {
                    gestureLocked = true;
                    moved = true;
                    longPressed = false;
                    if (!lockWarned) {
                        lockWarned = true;
                        Haptics.longPress(container);
                    }
                    return true;
                }
                pinching = true;
                gestureLocked = true;
                moved = true;
                longPressed = false;
                pinchStartSpan = span(e);
                pinchStartScale = scale;
                targetScale = scale;
                pinchCenterX = params.x + params.width / 2;
                pinchCenterY = params.y + params.height / 2;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (gestureLocked) {
                    if (pinching && e.getPointerCount() >= 2) {
                        float s = span(e);
                        if (pinchStartSpan > 1f) {
                            float ratio = s / pinchStartSpan;
                            if (ratio > 0.98f && ratio < 1.02f) return true;
                            // A sub-linear response plus eased motion avoids the twitchy
                            // feel of directly mapping every raw touch delta to window size.
                            float damped = (float) Math.pow(Math.max(0.01f, ratio), 0.60f);
                            targetScale = clampScale(pinchStartScale * damped);
                            if (!scaleTickerRunning) {
                                scaleTickerRunning = true;
                                main.post(scaleTicker);
                            }
                        }
                    }
                    return true;
                }
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (Math.hypot(dx, dy) > touchSlop) {
                    moved = true;
                    longPressed = false;
                    main.removeCallbacks(longPress);
                    if (overlayLocked) {
                        if (!lockWarned) {
                            lockWarned = true;
                            Haptics.longPress(container);
                        }
                        return true;
                    }
                }
                if (longPressed) return true;
                if (moved) {
                    moveTo(downX + Math.round(dx), downY + Math.round(dy));
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (pinching) {
                    pinching = false;
                    gestureLocked = true;
                    if (scaleTickerRunning) {
                        scaleSettleSave = true;
                    } else {
                        saveGeometry();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                boolean wasMoved = moved;
                boolean wasPinching = pinching;
                boolean wasLongPressed = longPressed;
                if (wasMoved || wasPinching) {
                    if (scaleTickerRunning) {
                        scaleSettleSave = true;
                    } else {
                        saveGeometry();
                    }
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && !wasMoved && !wasPinching && !wasLongPressed) {
                    long now = SystemClock.uptimeMillis();
                    float tdx = e.getRawX() - lastTapUpX;
                    float tdy = e.getRawY() - lastTapUpY;
                    if (now - lastTapUpMs <= ViewConfiguration.getDoubleTapTimeout()
                            && Math.hypot(tdx, tdy) <= touchSlop * 2f) {
                        lastTapUpMs = 0L;
                        toggleOverlayLock();
                    } else {
                        lastTapUpMs = now;
                        lastTapUpX = e.getRawX();
                        lastTapUpY = e.getRawY();
                    }
                }
                moved = false;
                pinching = false;
                longPressed = false;
                gestureLocked = false;
                return true;
        }
        return false;
    }

    private float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private void applyScaleAt(float newScale, int centerX, int centerY) {
        if (params == null) return;
        scale = clampScale(newScale);
        int nw = Math.round(baseWidthPx * scale);
        int nh = Math.round(baseHeightPx * scale);
        params.width = nw;
        params.height = nh;
        params.x = centerX - nw / 2;
        params.y = centerY - nh / 2;
        if (panel != null) {
            panel.setScaleX(scale);
            panel.setScaleY(scale);
        }
        clampPosition(false);
    }

    private void saveGeometry() {
        if (params == null) return;
        prefs.setPerfOverlayScale(scaleTickerRunning ? targetScale : scale);
        prefs.setPerfOverlayPosition(params.x, params.y);
    }

    private void moveTo(int x, int y) {
        if (container == null || params == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        params.x = Math.max(0, Math.min(x, Math.max(0, sw - params.width)));
        params.y = Math.max(0, Math.min(y, Math.max(0, sh - params.height)));
        try {
            windowManager.updateViewLayout(container, params);
        } catch (Throwable ignored) {
        }
        if (panel != null) panel.invalidate();
    }

    private void clampPosition(boolean save) {
        if (container == null || params == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (params.x < 0) params.x = Math.max(0, sw - params.width - Ui.dp(uiContext, 16));
        if (params.y < 0) params.y = Ui.dp(uiContext, 90);
        params.x = Math.max(0, Math.min(params.x, Math.max(0, sw - params.width)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, sh - params.height)));
        try {
            windowManager.updateViewLayout(container, params);
        } catch (Throwable ignored) {
        }
        if (panel != null) panel.invalidate();
        if (save) prefs.setPerfOverlayPosition(params.x, params.y);
    }

    private void startControlActivity() {
        try {
            android.content.Intent i = new android.content.Intent(this, PerfControlActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, "Could not open overlay controls", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMenu() {
        if (menu != null || !running) return;
        if (editMode) exitEditMode();
        Haptics.longPress(container);
        int mode = currentPerformanceMode();
        // Show the controls immediately with the neutral clear-glass card; the
        // live backdrop is attached asynchronously below. Capturing the screen
        // synchronously here blocked the menu for up to a second.
        menu = new PerfOverlayMenuView(uiContext, copyBackdrop(lastPanelBackdrop),
                prefs, mode, scale, this);
        captureMenuBackdrop(menu);
        menu.setMapperMode(mapperMode);
        menu.blockTouchesFor(650);
        setOverlayTouchable(false);
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.setTitle("Aurora performance controls");
        // The GlassPanel card samples a blurred backdrop itself; no window-wide blur.
        // Prefer the accessibility-overlay layer: it is trusted, sits above application
        // overlays and is not subject to other apps' hide/occlude-untrusted-overlays policy.
        menuOnAccessibility = AuroraA11yService.addAccessibilityOverlay(menu, menuParams);
        if (!menuOnAccessibility) {
            menuParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                windowManager.addView(menu, menuParams);
            } catch (Throwable ignored) {
                menu = null;
                menuParams = null;
            }
        }
        if (menu == null) {
            setOverlayTouchable(true);
        } else {
            TouchMapService.notifyModalOverlayChanged();
            menu.post(new Runnable() {
                @Override
                public void run() {
                    if (menu != null) menu.requestFocus();
                }
            });
        }
    }

    private void closeMenu() {
        Haptics.key(container);
        if (menu != null) {
            if (menuOnAccessibility) {
                AuroraA11yService.removeAccessibilityOverlay(menu);
            } else if (windowManager != null) {
                try {
                    windowManager.removeView(menu);
                } catch (Throwable ignored) {
                }
            }
            menu = null;
            menuParams = null;
            menuOnAccessibility = false;
        }
        applyCardTouchability();
        TouchMapService.notifyModalOverlayChanged();
    }

    private int currentPerformanceMode() {
        try {
            int v = Settings.System.getInt(getContentResolver(), "performance_mode", 0);
            return Math.max(0, Math.min(2, v));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void setPerformanceMode(final int mode) {
        if (menu != null) menu.setPerformanceMode(mode);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    String cmd = "settings put system performance_mode " + mode
                            + "; setprop persist.vendor.debug.mode " + mode;
                    Process p = new ProcessBuilder("su", "-c", cmd)
                            .redirectErrorStream(true).start();
                    p.waitFor(3, TimeUnit.SECONDS);
                    ok = p.exitValue() == 0;
                } catch (Throwable ignored) {
                }
                final boolean success = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            LToast.makeText(PerfOverlayService.this,
                                    "Performance mode: " + modeName(mode),
                                    Toast.LENGTH_SHORT).show();
                            closeMenu();
                        } else {
                            if (menu != null) {
                                menu.setPerformanceMode(currentPerformanceMode());
                            }
                            LToast.makeText(PerfOverlayService.this,
                                    "Performance mode needs root access",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private Backdrop copyBackdrop(Backdrop source) {
        if (source == null || source.bitmap == null || source.bitmap.isRecycled()) {
            return null;
        }
        try {
            Bitmap copy = source.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            return copy == null ? null
                    : new Backdrop(copy, source.screenWidth, source.screenHeight);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Attach the app window behind the controls without blocking the menu. */
    private void captureMenuBackdrop(final PerfOverlayMenuView target) {
        AuroraA11yService.captureApplicationWindow(
                new AuroraA11yService.WindowCaptureCallback() {
                    @Override
                    public void onCaptured(Bitmap bitmap, Rect bounds) {
                        final Backdrop backdrop = ScreenCaptureBackdrop.fromWindow(
                                PerfOverlayService.this, bitmap, bounds);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (menu == target) {
                                    target.setBackdrop(backdrop);
                                } else if (backdrop != null && backdrop.bitmap != null) {
                                    backdrop.bitmap.recycle();
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailed() {
                        // Keep the neutral clear-glass card.
                    }
                });
    }

    private void openDetail() {
        if (detail != null || !running) return;
        detailOpen = true;
        detail = new PerfDetailView(uiContext, new Runnable() {
            @Override
            public void run() {
                closeDetail();
            }
        });
        detail.update(buildDetail());
        int type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        detailParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        detailParams.gravity = Gravity.TOP | Gravity.START;
        detailParams.setTitle("Aurora performance details");
        Ui.blurWindow(detailParams, this, 30);
        detailOnAccessibility = AuroraA11yService.addAccessibilityOverlay(detail, detailParams);
        if (!detailOnAccessibility) {
            detailParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                windowManager.addView(detail, detailParams);
            } catch (Throwable t) {
                detail = null;
                detailParams = null;
                detailOpen = false;
                LToast.makeText(this, "Could not open performance details",
                        Toast.LENGTH_SHORT).show();
            }
        }
        updateSession(lastStats);
        if (detail != null) detail.update(buildDetail());
    }

    private void closeDetail() {
        if (detail != null) {
            if (detailOnAccessibility) {
                AuroraA11yService.removeAccessibilityOverlay(detail);
            } else if (windowManager != null) {
                try {
                    windowManager.removeView(detail);
                } catch (Throwable ignored) {
                }
            }
            detail = null;
            detailParams = null;
            detailOnAccessibility = false;
        }
        detailOpen = false;
    }

    /** Keep roughly two minutes of FPS history, independent of the refresh interval. */
    private int historyCapacity() {
        int ms = Math.max(500, Math.min(10000, prefs.perfRefreshMs()));
        return Math.max(12, Math.min(480, Math.round(120_000f / ms)));
    }

    private void updateSession(PerfStats s) {
        if (s == null) return;
        boolean changed = s.targetPkg != null && !s.targetPkg.isEmpty()
                && (!s.targetPkg.equals(sessionPkg)
                || (s.targetCls != null && !s.targetCls.equals(sessionCls)));
        if (changed) {
            finalizeCurrentLog();
            sessionPkg = s.targetPkg;
            sessionCls = s.targetCls;
            sessionLabel = labelFor(s.targetPkg);
            sessionStartMs = SystemClock.elapsedRealtime();
            sessionStartBattery = s.battery;
            sessionStartChargeUah = s.chargeCounterUah;
            fpsSamples = 0;
            fpsSum = 0;
            fpsMin = Integer.MAX_VALUE;
            fpsMax = 0;
            peakPowerW = 0f;
            fpsHistory.clear();
            // The overlay is persistent; refresh the frozen card sample when the
            // app underneath changes instead of keeping the home snapshot forever.
            maybeRefreshBackdrop(s.targetPkg);
        }
        lastStats = s;
        if (s.fpsValid && s.fps > 0) {
            int fps = Math.max(1, Math.min(240, s.fps));
            fpsSamples++;
            fpsSum += fps;
            fpsMin = Math.min(fpsMin, fps);
            fpsMax = Math.max(fpsMax, fps);
            if (fpsHistory.size() >= historyCapacity()) fpsHistory.removeFirst();
            fpsHistory.addLast(fps);
        }
        if (s.powerW > peakPowerW) peakPowerW = s.powerW;

        if (currentLog == null && s.targetPkg != null && !isOwnPackage(s.targetPkg)
                && prefs.perfLogging()) {
            startLog(s);
        }
        if (currentLog != null) updateLog(s);
    }

    private boolean isOwnPackage(String pkg) {
        return pkg != null && pkg.equals(getPackageName());
    }

    private boolean backdropPkgEquals(String pkg) {
        return pkg == null ? backdropPkg == null : pkg.equals(backdropPkg);
    }

    private void startLog(PerfStats s) {
        currentLog = new PerfLogEntry();
        currentLog.id = System.currentTimeMillis();
        currentLog.startMs = currentLog.id;
        currentLog.endMs = currentLog.id;
        currentLog.appLabel = sessionLabel == null ? s.targetPkg : sessionLabel;
        currentLog.pkg = s.targetPkg == null ? "" : s.targetPkg;
        currentLog.cls = s.targetCls == null ? "" : s.targetCls;
        currentLog.batteryStart = s.battery;
        currentLog.batteryEnd = s.battery;
        currentLog.chargeStartUah = s.chargeCounterUah;
        lastLogSaveMs = 0L;
    }

    private void updateLog(PerfStats s) {
        if (currentLog == null) return;
        long now = System.currentTimeMillis();
        currentLog.endMs = now;
        currentLog.durationMs = Math.max(0, now - currentLog.startMs);
        currentLog.samples++;
        if (s.fpsValid && s.fps > 0) {
            int fps = Math.max(1, Math.min(240, s.fps));
            currentLog.fpsSamples++;
            currentLog.fpsSum += fps;
            currentLog.minFps = currentLog.minFps == 0
                    ? fps : Math.min(currentLog.minFps, fps);
            currentLog.maxFps = Math.max(currentLog.maxFps, fps);
            if (currentLog.history.size() >= historyCapacity()) currentLog.history.remove(0);
            currentLog.history.add(fps);
        }
        if (s.powerW > 0) {
            currentLog.powerSum += s.powerW;
            currentLog.powerSamples++;
            currentLog.peakPowerW = Math.max(currentLog.peakPowerW, s.powerW);
        }
        currentLog.maxCpuTemp = Math.max(currentLog.maxCpuTemp, s.cpuTempC);
        currentLog.maxGpuTemp = Math.max(currentLog.maxGpuTemp, s.gpuTempC);
        currentLog.cpuTempHistory.add(Math.max(0, Math.round(s.cpuTempC)));
        currentLog.gpuTempHistory.add(Math.max(0, Math.round(s.gpuTempC)));
        currentLog.cpuUsageHistory.add(Math.max(0, Math.min(100, s.cpuUsage)));
        currentLog.gpuUsageHistory.add(Math.max(0, Math.min(100, s.gpuUsage)));
        int cap = historyCapacity();
        while (currentLog.cpuTempHistory.size() > cap) currentLog.cpuTempHistory.remove(0);
        while (currentLog.gpuTempHistory.size() > cap) currentLog.gpuTempHistory.remove(0);
        while (currentLog.cpuUsageHistory.size() > cap) currentLog.cpuUsageHistory.remove(0);
        while (currentLog.gpuUsageHistory.size() > cap) currentLog.gpuUsageHistory.remove(0);
        currentLog.cpuUsageSum += s.cpuUsage;
        currentLog.gpuUsageSum += s.gpuUsage;
        currentLog.usageSamples++;
        if (throttleRank(s.throttle) > throttleRank(currentLog.throttle)) {
            currentLog.throttle = s.throttle;
        }
        currentLog.batteryEnd = s.battery;
        if (currentLog.batteryStart >= 0 && s.battery >= 0) {
            currentLog.batteryUsedPct = Math.max(0,
                    currentLog.batteryStart - s.battery);
        }
        if (currentLog.chargeStartUah > 0 && s.chargeCounterUah > 0) {
            currentLog.batteryUsedMah = Math.max(0f,
                    (currentLog.chargeStartUah - s.chargeCounterUah) / 1000f);
        }
        currentLog.appRamKb = Math.max(currentLog.appRamKb, s.appRamKb);
        currentLog.memPercent = Math.max(currentLog.memPercent, s.memPercent);

        if (now - lastLogSaveMs >= 5000L) {
            lastLogSaveMs = now;
            if (logStore != null) {
                logStore.addOrUpdate(currentLog);
                logStore.save();
                if (prefs != null) {
                    logStore.cleanupAll(prefs.perfCleanupHours());
                }
            }
        }
    }

    private void cleanupOldLogsIfDue() {
        if (logStore == null || prefs == null) return;
        long now = System.currentTimeMillis();
        if (now - lastLogCleanupMs < 10L * 60L * 1000L) return;
        lastLogCleanupMs = now;
        logStore.cleanupAll(prefs.perfCleanupHours());
    }

    private void finalizeCurrentLog() {
        if (currentLog == null) return;
        if (currentLog.samples > 0 && currentLog.durationMs >= 250L) {
            if (currentLog.fpsSamples > 0) {
                currentLog.avgFps = Math.round(currentLog.fpsSum
                        / (float) currentLog.fpsSamples);
                currentLog.minFps = currentLog.minFps == 0
                        ? currentLog.avgFps : currentLog.minFps;
            }
            if (currentLog.powerSamples > 0) {
                currentLog.avgPowerW = currentLog.powerSum
                        / (float) currentLog.powerSamples;
            }
            if (currentLog.usageSamples > 0) {
                currentLog.avgCpuUsage = Math.round(currentLog.cpuUsageSum
                        / (float) currentLog.usageSamples);
                currentLog.avgGpuUsage = Math.round(currentLog.gpuUsageSum
                        / (float) currentLog.usageSamples);
            }
            if (logStore != null) {
                logStore.addOrUpdate(currentLog);
                logStore.save();
            }
        }
        currentLog = null;
    }

    private static int throttleRank(String value) {
        if ("THERMAL".equals(value)) return 3;
        if ("CPU CAP".equals(value) || "GPU CAP".equals(value)) return 2;
        return 0;
    }

    private String labelFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "No foreground app";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private PerfDetailData buildDetail() {
        PerfDetailData d = new PerfDetailData();
        d.appLabel = sessionLabel == null ? "No foreground app" : sessionLabel;
        d.pkg = sessionPkg == null ? "" : sessionPkg;
        d.cls = sessionCls == null ? "" : sessionCls;
        PerfStats s = lastStats;
        if (s != null) {
            d.fps = s.fps;
            d.fpsValid = s.fpsValid;
            d.cpuTempC = s.cpuTempC;
            d.gpuTempC = s.gpuTempC;
            d.cpuUsage = s.cpuUsage;
            d.gpuUsage = s.gpuUsage;
            d.cpuHz = s.cpuHz;
            d.cpuMaxHz = s.cpuMaxHz;
            d.gpuHz = s.gpuHz;
            d.gpuMaxHz = s.gpuMaxHz;
            d.powerW = s.powerW;
            d.throttle = s.throttle;
            d.battery = s.battery;
            d.appRamKb = s.appRamKb;
            d.memTotalKb = s.memTotalKb;
            d.memAvailKb = s.memAvailKb;
            d.memPercent = s.memPercent;
        }
        d.avgFps = fpsSamples > 0 ? Math.round(fpsSum / (float) fpsSamples) : 0;
        d.minFps = fpsSamples > 0 ? fpsMin : 0;
        d.maxFps = fpsSamples > 0 ? fpsMax : 0;
        d.history = new int[fpsHistory.size()];
        int index = 0;
        for (Integer v : fpsHistory) d.history[index++] = v;
        d.peakPowerW = peakPowerW;
        d.runtimeMs = sessionStartMs > 0
                ? SystemClock.elapsedRealtime() - sessionStartMs : 0;
        d.batteryStart = sessionStartBattery;
        if (s != null && sessionStartBattery >= 0 && s.battery >= 0) {
            d.batteryUsedPct = Math.max(0, sessionStartBattery - s.battery);
            if (sessionStartChargeUah > 0 && s.chargeCounterUah > 0) {
                d.batteryUsedMah = Math.max(0f,
                        (sessionStartChargeUah - s.chargeCounterUah) / 1000f);
            }
        }
        return d;
    }

    private static String modeName(int mode) {
        if (mode == 1) return "Standard";
        if (mode == 2) return "High";
        return "Normal";
    }

    // PerfOverlayMenuView.Listener

    @Override
    public void onDismiss() {
        closeMenu();
    }

    @Override
    public void onOverlayEnabled(boolean enabled) {
        if (enabled) return;
        prefs.setPerfOverlay(false);
        closeMenu();
        closeDetail();
        stopSelf();
    }

    @Override
    public void onMetricsChanged() {
        applyPrefs();
    }

    @Override
    public void onRefreshChanged(int ms) {
        prefs.setPerfRefreshMs(ms);
        applyPrefs();
        main.removeCallbacks(sampleTick);
        if (running && !quiet) main.post(sampleTick);
    }

    @Override
    public void onOpacityChanged(int percent) {
        prefs.setPerfOverlayAlpha(percent);
        if (panel != null) panel.setPanelAlpha(percent);
        if (menu != null) menu.setCardAlpha(percent);
    }

    @Override
    public void onScaleChanged(float newScale) {
        scale = clampScale(newScale);
        applyScaleGeometry(true);
        prefs.setPerfOverlayScale(scale);
    }

    @Override
    public void onPerformanceMode(int mode) {
        setPerformanceMode(mode);
    }

    @Override
    public void onResetPosition() {
        prefs.setPerfOverlayPosition(-1, -1);
        if (params != null) {
            params.x = -1;
            params.y = -1;
            clampPosition(false);
        }
    }

    @Override
    public void onEditPlacement() {
        closeMenu();
        main.post(new Runnable() {
            @Override
            public void run() {
                enterEditMode();
            }
        });
    }

    @Override
    public void onMapController() {
        if (AuroraInputService.beginKeyCapture(this)) {
            closeMenu();
        } else {
            LToast.makeText(this,
                    "Controller service unavailable. Check PServer/root or accessibility.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onClearController() {
        prefs.setPerfControllerRaw("");
        LToast.makeText(this, "Controller shortcut cleared", Toast.LENGTH_SHORT).show();
        closeMenu();
    }

    @Override
    public void onMapDrawerController() {
        if (AuroraInputService.beginDrawerKeyCapture(this)) {
            closeMenu();
        } else {
            LToast.makeText(this,
                    "Controller service unavailable. Check PServer/root or accessibility.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onClearDrawerController() {
        prefs.setDrawerControllerRaw("");
        LToast.makeText(this, "App drawer shortcut cleared", Toast.LENGTH_SHORT).show();
        closeMenu();
    }

    @Override
    public void onShowDetails() {
        closeMenu();
        openDetail();
    }
}
