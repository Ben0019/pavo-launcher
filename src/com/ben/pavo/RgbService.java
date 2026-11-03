package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.graphics.Color;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Keeps animated joystick LED modes running after the RGB app is closed. */
public final class RgbService extends Service {

    static final String ACTION_APPLY = "com.ben.pavo.RGB_APPLY";
    static final String ACTION_STOP = "com.ben.pavo.RGB_STOP";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_COLOR = "color";
    static final String EXTRA_BRIGHTNESS = "brightness";
    static final String EXTRA_SPEED = "speed";
    static final String EXTRA_LEFT = "left";
    static final String EXTRA_RIGHT = "right";

    private static final String CHANNEL = "aurora_rgb";
    private static final int NOTIFICATION_ID = 101;
    private static volatile boolean running;
    private static volatile int previewColor;
    private static volatile int previewColorRight;
    private static volatile boolean previewActive;
    private static final Handler wakeHandler = new Handler(Looper.getMainLooper());
    private static Runnable pendingWakeReapply;
    private static volatile long wakeHoldUntil;

    private HandlerThread thread;
    private Handler handler;
    private int mode = RgbControl.MODE_OFF;
    private int color = 0xFF2BE0D8;
    private float colorHue;
    private int brightness = 50;
    private int speed = 100;
    private boolean left = true;
    private boolean right = true;
    private long startedAt;
    private boolean foreground;

    static boolean isRunning() {
        return running;
    }

    static int previewColor() {
        return previewColor;
    }

    static int previewColorRight() {
        return previewColorRight;
    }

    static boolean previewActive() {
        return previewActive;
    }

    static void apply(Context c, int mode, int color, int brightness, int speed,
                      boolean left, boolean right) {
        if (c == null) return;
        Intent intent = new Intent(c, RgbService.class);
        intent.setAction(ACTION_APPLY);
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_COLOR, color);
        intent.putExtra(EXTRA_BRIGHTNESS, brightness);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_LEFT, left);
        intent.putExtra(EXTRA_RIGHT, right);
        c.startService(intent);
    }

    /** Re-apply the saved RGB mode after the display comes back on. */
    static void reapply(Context c) {
        if (c == null) return;
        Prefs prefs = new Prefs(c);
        int mode = prefs.rgbMode();
        if (mode == RgbControl.MODE_SCREEN) {
            mode = RgbControl.MODE_STATIC;
            prefs.setRgbMode(mode);
        }
        if (mode == RgbControl.MODE_OFF) return;
        // The panel's wake sequence writes a black LED frame ~0.5 s after the
        // screen becomes interactive and can drop the enable with it. Every
        // mode waits for the panel to settle, then restores itself: static
        // paints the colour, animated modes re-enable and restart their loop.
        scheduleWakeReapply(c.getApplicationContext());
    }

    private static void scheduleWakeReapply(final Context app) {
        // The wake watcher and the screen-on broadcast can both call this;
        // keep the first deadline so duplicate triggers cannot push the
        // restore later.
        if (pendingWakeReapply != null) return;
        wakeHoldUntil = SystemClock.uptimeMillis() + 650L;
        pendingWakeReapply = new Runnable() {
            private boolean repeated;

            @Override
            public void run() {
                if (!repeated) {
                    // A second pass covers a late panel write; repainting the
                    // same colour is invisible when the first one survived.
                    repeated = true;
                    wakeHandler.postDelayed(this, 650L);
                } else {
                    pendingWakeReapply = null;
                }
                try {
                    PowerManager pm = (PowerManager) app.getSystemService(
                            Context.POWER_SERVICE);
                    if (pm != null && !pm.isInteractive()) return;
                    Prefs prefs = new Prefs(app);
                    int saved = prefs.rgbMode();
                    if (saved == RgbControl.MODE_SCREEN) saved = RgbControl.MODE_STATIC;
                    if (saved == RgbControl.MODE_OFF) return;
                    int c = prefs.rgbColor();
                    int b = prefs.rgbBrightness();
                    int sp = prefs.rgbSpeed();
                    boolean l = prefs.rgbLeft();
                    boolean r = prefs.rgbRight();
                    wakeHoldUntil = 0L;
                    if (saved == RgbControl.MODE_STATIC) {
                        RgbControl.wakeApply(c, b, l, r);
                    } else {
                        RgbControl.wakeEnable(l, r);
                        apply(app, saved, c, b, sp, l, r);
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        wakeHandler.postDelayed(pendingWakeReapply, 650L);
    }

    /** Pause painting while the panel is blank; the wake path resumes it. */
    static void onScreenOff() {
        wakeHoldUntil = Long.MAX_VALUE;
    }

    static void stop(Context c) {
        if (c == null) return;
        Intent intent = new Intent(c, RgbService.class);
        intent.setAction(ACTION_STOP);
        c.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        thread = new HandlerThread("AuroraRgb");
        thread.start();
        handler = new Handler(thread.getLooper());
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, Loc.s(this, "RGB lighting"),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Joystick RGB animation");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_APPLY;
        if (ACTION_STOP.equals(action)) {
            RgbControl.off();
            new Prefs(this).setRgbMode(RgbControl.MODE_OFF);
            previewActive = false;
            previewColor = 0;
            previewColorRight = 0;
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        int nextMode = intent != null ? intent.getIntExtra(EXTRA_MODE, mode) : mode;
        int nextColor = intent != null ? intent.getIntExtra(EXTRA_COLOR, color) : color;
        int nextBrightness = intent != null
                ? intent.getIntExtra(EXTRA_BRIGHTNESS, brightness) : brightness;
        int nextSpeed = intent != null ? intent.getIntExtra(EXTRA_SPEED, speed) : speed;
        boolean nextLeft = intent != null ? intent.getBooleanExtra(EXTRA_LEFT, left) : left;
        boolean nextRight = intent != null
                ? intent.getBooleanExtra(EXTRA_RIGHT, right) : right;
        if (nextMode == RgbControl.MODE_SCREEN) {
            // Screen-follow mode has been removed; fall back to Static.
            nextMode = RgbControl.MODE_STATIC;
            new Prefs(this).setRgbMode(nextMode);
        }
        boolean modeChanged = nextMode != mode;
        mode = nextMode;
        color = nextColor;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        colorHue = hsv[0];
        brightness = Math.max(1, Math.min(100, nextBrightness));
        speed = Math.max(10, Math.min(300, nextSpeed));
        left = nextLeft;
        right = nextRight;

        if (mode == RgbControl.MODE_OFF) {
            RgbControl.off();
            previewActive = false;
            previewColor = 0;
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (mode == RgbControl.MODE_STATIC) {
            RgbControl.applyStatic(color, brightness, left, right);
            previewActive = false;
            previewColor = RgbControl.scaledColor(color, 1f, brightness);
            previewColorRight = previewColor;
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        previewActive = true;
        if (modeChanged || startedAt == 0L) {
            // Only touch the enable when the service or mode actually changes.
            // A redundant enable write during wake made the LEDs blink.
            RgbControl.setEnabled(left, right);
            startedAt = SystemClock.uptimeMillis();
        }
        if (!foreground) {
            if (!ServiceNotifications.hosted()) {
                startForeground(NOTIFICATION_ID, notification());
                ServiceNotifications.hideLater(this);
            }
            foreground = true;
        } else {
            ServiceNotifications.update(this, NOTIFICATION_ID, notification());
        }
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || handler == null) return;
            long now = SystemClock.uptimeMillis();
            if (now < wakeHoldUntil) {
                // The screen blanked; stop the loop instead of waking the CPU
                // every frame. The wake path restarts it after the panel has
                // finished its own LED write.
                return;
            }
            long elapsed = now - startedAt;
            long scaledElapsed = elapsed * speed / 100L;
            long strobeInterval = Math.max(35L, 140L * 100L / speed);
            int effective = brightness;
            int out = color;
            int outRight = out;
            if (mode == RgbControl.MODE_BREATH) {
                effective = Math.max(1, Math.round(brightness
                        * RgbControl.breathFactor(scaledElapsed) / 100f));
            } else if (mode == RgbControl.MODE_RAINBOW) {
                out = RgbControl.rainbowColor(scaledElapsed);
                outRight = out;
            } else if (mode == RgbControl.MODE_STROBE) {
                if ((elapsed / strobeInterval) % 2L != 0L) out = 0;
                outRight = out;
            } else if (mode == RgbControl.MODE_MULTI) {
                // Two hues at once, one per stick, both cycling. The ring is
                // a single RGB zone, so this is the closest the hardware can
                // get to a multi-colour gradient.
                out = RgbControl.multiColor(scaledElapsed, colorHue, 0);
                outRight = RgbControl.multiColor(scaledElapsed, colorHue, 120);
            }
            previewColor = RgbControl.scaledColor(out, 1f, effective);
            previewColorRight = RgbControl.scaledColor(outRight, 1f, effective);
            previewActive = true;
            // Re-assert the enable with every frame: the panel/vendor wake
            // sequence can clear it after the first frame, which left the
            // animated modes dark.
            RgbControl.setEnabled(left, right);
            if (mode == RgbControl.MODE_MULTI) {
                RgbControl.applyColorsLR(out, outRight, effective, left, right);
            } else {
                RgbControl.applyColors(out, effective, left, right);
            }
            handler.postDelayed(this, mode == RgbControl.MODE_STROBE
                    ? strobeInterval : 70L);
        }
    };

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, RgbActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RgbService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_apps)
                .setContentTitle(Loc.s(this, "Pavo RGB"))
                .setContentText(Loc.s(this, modeName(mode)) + " · " + Loc.s(this, "joystick lighting"))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_apps),
                        "STOP", stop).build());
        return b.build();
    }

    static String modeName(int mode) {
        switch (mode) {
            case RgbControl.MODE_BREATH: return "Breathing";
            case RgbControl.MODE_RAINBOW: return "Rainbow";
            case RgbControl.MODE_STROBE: return "Strobe";
            case RgbControl.MODE_MULTI: return "Multicolor";
            case RgbControl.MODE_STATIC: return "Static";
            default: return "Off";
        }
    }


    @Override
    public void onDestroy() {
        running = false;
        previewActive = false;
        previewColor = 0;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        foreground = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
