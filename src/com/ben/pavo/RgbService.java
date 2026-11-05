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
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
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
    static final String EXTRA_LEFT_MODE = "left_mode";
    static final String EXTRA_LEFT_COLOR = "left_color";
    static final String EXTRA_RIGHT_MODE = "right_mode";
    static final String EXTRA_RIGHT_COLOR = "right_color";
    static final String EXTRA_BRIGHTNESS = "brightness";
    static final String EXTRA_SPEED = "speed";
    static final String EXTRA_LINKED = "linked";

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
    private int leftMode = RgbControl.MODE_OFF;
    private int rightMode = RgbControl.MODE_OFF;
    private int leftColor = 0xFF2BE0D8;
    private int rightColor = 0xFF2BE0D8;
    private float leftHue;
    private float rightHue;
    private int ambientLeft;
    private int ambientRight;
    private int ambientTargetLeft;
    private int ambientTargetRight;
    private long lastAmbientSample;
    private volatile boolean ambientSampling;
    private int brightness = 50;
    private int speed = 100;
    private long leftStart;
    private long rightStart;
    private long lastEnableAt;
    private boolean foreground;
    private boolean started;

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

    static void apply(Context c, int leftMode, int leftColor,
                      int rightMode, int rightColor, int brightness, int speed,
                      boolean linked) {
        if (c == null) return;
        Intent intent = new Intent(c, RgbService.class);
        intent.setAction(ACTION_APPLY);
        intent.putExtra(EXTRA_LEFT_MODE, leftMode);
        intent.putExtra(EXTRA_LEFT_COLOR, leftColor);
        intent.putExtra(EXTRA_RIGHT_MODE, rightMode);
        intent.putExtra(EXTRA_RIGHT_COLOR, rightColor);
        intent.putExtra(EXTRA_BRIGHTNESS, brightness);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_LINKED, linked);
        c.startService(intent);
    }

    static boolean isAnimated(int mode) {
        return mode == RgbControl.MODE_BREATH
                || mode == RgbControl.MODE_RAINBOW
                || mode == RgbControl.MODE_STROBE
                || mode == RgbControl.MODE_MULTI
                || mode == RgbControl.MODE_SCREEN;
    }

    /** Re-apply the saved RGB modes after the display comes back on. */
    static void reapply(Context c) {
        if (c == null) return;
        Prefs prefs = new Prefs(c);
        if (prefs.rgbLeftMode() == RgbControl.MODE_OFF
                && prefs.rgbRightMode() == RgbControl.MODE_OFF) return;
        // The panel's wake sequence writes a black LED frame ~0.5 s after the
        // screen becomes interactive and can drop the enable with it. Every
        // mode waits for the panel to settle, then restores itself: static
        // paints the colours, animated modes re-enable and restart their loop.
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
                    int lm = prefs.rgbLeftMode();
                    int rm = prefs.rgbRightMode();
                    if (lm == RgbControl.MODE_OFF && rm == RgbControl.MODE_OFF) return;
                    int lc = prefs.rgbLeftColor();
                    int rc = prefs.rgbRightColor();
                    int b = prefs.rgbBrightness();
                    int sp = prefs.rgbSpeed();
                    boolean l = lm != RgbControl.MODE_OFF;
                    boolean r = rm != RgbControl.MODE_OFF;
                    wakeHoldUntil = 0L;
                    if (!isAnimated(lm) && !isAnimated(rm)) {
                        RgbControl.wakeApply(lc, rc, b, l, r);
                    } else {
                        RgbControl.wakeEnable(l, r);
                        apply(app, lm, lc, rm, rc, b, sp, prefs.rgbLinked());
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
        thread = new HandlerThread("AuroraRgb", android.os.Process.THREAD_PRIORITY_DISPLAY);
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
            Prefs stopPrefs = new Prefs(this);
            stopPrefs.setRgbLeftMode(RgbControl.MODE_OFF);
            stopPrefs.setRgbRightMode(RgbControl.MODE_OFF);
            previewActive = false;
            previewColor = 0;
            previewColorRight = 0;
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        int nextLM = intent != null ? intent.getIntExtra(EXTRA_LEFT_MODE, leftMode) : leftMode;
        int nextRM = intent != null ? intent.getIntExtra(EXTRA_RIGHT_MODE, rightMode) : rightMode;
        int nextLC = intent != null ? intent.getIntExtra(EXTRA_LEFT_COLOR, leftColor) : leftColor;
        int nextRC = intent != null
                ? intent.getIntExtra(EXTRA_RIGHT_COLOR, rightColor) : rightColor;
        int nextBrightness = intent != null
                ? intent.getIntExtra(EXTRA_BRIGHTNESS, brightness) : brightness;
        int nextSpeed = intent != null ? intent.getIntExtra(EXTRA_SPEED, speed) : speed;
        boolean linked = intent != null && intent.getBooleanExtra(EXTRA_LINKED, false);
        boolean leftChanged = nextLM != leftMode;
        boolean rightChanged = nextRM != rightMode;
        boolean anyChanged = leftChanged || rightChanged;
        leftMode = nextLM;
        rightMode = nextRM;
        leftColor = nextLC;
        rightColor = nextRC;
        float[] hsv = new float[3];
        Color.colorToHSV(leftColor, hsv);
        leftHue = hsv[0];
        Color.colorToHSV(rightColor, hsv);
        rightHue = hsv[0];
        brightness = Math.max(1, Math.min(100, nextBrightness));
        speed = Math.max(10, Math.min(300, nextSpeed));
        boolean leftOn = leftMode != RgbControl.MODE_OFF;
        boolean rightOn = rightMode != RgbControl.MODE_OFF;

        if (!leftOn && !rightOn) {
            RgbControl.off();
            previewActive = false;
            previewColor = 0;
            previewColorRight = 0;
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!isAnimated(leftMode) && !isAnimated(rightMode)) {
            RgbControl.applyStaticLR(leftColor, rightColor, brightness, leftOn, rightOn);
            previewActive = false;
            previewColor = RgbControl.scaledColor(leftColor, 1f, brightness);
            previewColorRight = RgbControl.scaledColor(rightColor, 1f, brightness);
            stopForeground(true);
            foreground = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        // An explicit mode apply means the user is looking at the LEDs now;
        // do not leave a stale screen-off hold blocking the animation.
        if (intent != null && ACTION_APPLY.equals(intent.getAction())) {
            wakeHoldUntil = 0L;
        }
        if ((leftMode == RgbControl.MODE_SCREEN || rightMode == RgbControl.MODE_SCREEN)
                && (anyChanged || !started)) {
            if (leftMode == RgbControl.MODE_SCREEN) {
                ambientLeft = leftColor;
                ambientTargetLeft = 0;
            }
            if (rightMode == RgbControl.MODE_SCREEN) {
                ambientRight = rightColor;
                ambientTargetRight = 0;
            }
            lastAmbientSample = 0L;
        }
        previewActive = true;
        long now = SystemClock.uptimeMillis();
        if (linked && anyChanged) {
            // Linked sticks must animate in step; reset both phases when
            // either side adopts a new mode.
            leftStart = now;
            rightStart = now;
        } else {
            if (leftChanged || !started) leftStart = now;
            if (rightChanged || !started) rightStart = now;
        }
        if (anyChanged || !started) {
            // Only touch the enable when the service or a mode actually
            // changes. A redundant enable write during wake made the LEDs blink.
            RgbControl.setEnabled(leftOn, rightOn);
            started = true;
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
            if (wakeHoldUntil == Long.MAX_VALUE) {
                // Screen-off pause. Keep a slow poll so a missed wake event
                // cannot leave the animation stopped forever.
                PowerManager pm = (PowerManager) getSystemService(
                        Context.POWER_SERVICE);
                if (pm == null || !pm.isInteractive()) {
                    handler.postDelayed(this, 1000L);
                    return;
                }
                wakeHoldUntil = 0L;
            }
            if (now < wakeHoldUntil) {
                // Let the panel finish its wake write, then resume even if the
                // scheduled wake callback was lost.
                handler.postDelayed(this, Math.min(100L, wakeHoldUntil - now));
                return;
            }
            long leftElapsed = now - leftStart;
            long rightElapsed = now - rightStart;
            long strobeInterval = Math.max(35L, 140L * 100L / speed);
            int effectiveLeft = brightness;
            int effectiveRight = brightness;
            int outLeft = leftColor;
            int outRight = rightColor;

            if (leftMode == RgbControl.MODE_SCREEN
                    || rightMode == RgbControl.MODE_SCREEN) {
                sampleAmbient(now);
                blendAmbient();
            }

            if (leftMode == RgbControl.MODE_BREATH) {
                effectiveLeft = Math.max(1, Math.round(brightness
                        * RgbControl.breathFactor(leftElapsed * speed / 100L) / 100f));
            } else if (leftMode == RgbControl.MODE_RAINBOW) {
                outLeft = RgbControl.rainbowColor(leftElapsed * speed / 100L);
            } else if (leftMode == RgbControl.MODE_STROBE) {
                if ((leftElapsed / strobeInterval) % 2L != 0L) outLeft = 0;
            } else if (leftMode == RgbControl.MODE_MULTI) {
                outLeft = RgbControl.multiColor(
                        leftElapsed * speed / 100L, leftHue, 0);
            } else if (leftMode == RgbControl.MODE_SCREEN) {
                outLeft = ambientLeft;
            }

            if (rightMode == RgbControl.MODE_BREATH) {
                effectiveRight = Math.max(1, Math.round(brightness
                        * RgbControl.breathFactor(rightElapsed * speed / 100L) / 100f));
            } else if (rightMode == RgbControl.MODE_RAINBOW) {
                outRight = RgbControl.rainbowColor(rightElapsed * speed / 100L);
            } else if (rightMode == RgbControl.MODE_STROBE) {
                if ((rightElapsed / strobeInterval) % 2L != 0L) outRight = 0;
            } else if (rightMode == RgbControl.MODE_MULTI) {
                // The ring is a single RGB zone; the two sticks act as two
                // simultaneous colours when both run Multicolour.
                outRight = RgbControl.multiColor(rightElapsed * speed / 100L, rightHue,
                        leftMode == RgbControl.MODE_MULTI ? 120 : 0);
            } else if (rightMode == RgbControl.MODE_SCREEN) {
                outRight = ambientRight;
            }

            // Bake each stick's brightness into its colour so one pass can
            // paint different modes (a dim breathing left, a bright static
            // right) at the same time.
            int litLeft = RgbControl.scaledColor(outLeft, 1f, effectiveLeft);
            int litRight = RgbControl.scaledColor(outRight, 1f, effectiveRight);
            previewColor = litLeft;
            previewColorRight = litRight;
            previewActive = true;
            boolean leftOn = leftMode != RgbControl.MODE_OFF;
            boolean rightOn = rightMode != RgbControl.MODE_OFF;
            // Re-assert the enable with every frame: the panel/vendor wake
            // sequence can clear it after the first frame, which left the
            // animated modes dark.
            if (now - lastEnableAt >= 250L) {
                // Re-assert periodically rather than every frame. The wake
                // path enables before the loop starts, so this only recovers
                // from a late panel/vendor write.
                RgbControl.setEnabledFast(leftOn, rightOn);
                lastEnableAt = now;
            }
            RgbControl.applyColorsLR(litLeft, litRight, 100, leftOn, rightOn);
            long frameMs = (leftMode == RgbControl.MODE_STROBE
                    || rightMode == RgbControl.MODE_STROBE) ? strobeInterval
                    : ((leftMode == RgbControl.MODE_BREATH
                    || rightMode == RgbControl.MODE_BREATH) ? 12L : 70L);
            long work = SystemClock.uptimeMillis() - now;
            handler.postDelayed(this, Math.max(1L, frameMs - work));
        }
    };

    private void sampleAmbient(long now) {
        if (RgbActivity.isVisible() || ambientSampling
                || now - lastAmbientSample < 260L) return;
        lastAmbientSample = now;
        ambientSampling = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final int[] sampled = ScreenAmbient.sampleLR(getApplicationContext());
                if (handler != null) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            ambientSampling = false;
                            if (!running || sampled == null) return;
                            if (leftMode == RgbControl.MODE_SCREEN
                                    && sampled[0] != 0) {
                                ambientTargetLeft = sampled[0];
                            }
                            if (rightMode == RgbControl.MODE_SCREEN
                                    && sampled[1] != 0) {
                                ambientTargetRight = sampled[1];
                            }
                        }
                    });
                } else {
                    ambientSampling = false;
                }
            }
        }, "rgb-ambient").start();
    }

    private void blendAmbient() {
        // Screen changes should read quickly; Speed still tunes the snap.
        float blendAmount = Math.max(0.30f,
                Math.min(0.90f, 0.45f + 0.45f * speed / 100f));
        if (ambientTargetLeft != 0) {
            ambientLeft = blend(ambientLeft, ambientTargetLeft, blendAmount);
        }
        if (ambientTargetRight != 0) {
            ambientRight = blend(ambientRight, ambientTargetRight, blendAmount);
        }
    }

    private static int blend(int from, int to, float amount) {
        float f = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.rgb(r, g, b);
    }

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
                .setContentText(modeSummary() + " · " + Loc.s(this, "joystick lighting"))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_apps),
                        "STOP", stop).build());
        return b.build();
    }

    private String modeSummary() {
        String l = Loc.s(this, modeName(leftMode));
        String r = Loc.s(this, modeName(rightMode));
        return l.equals(r) ? l : l + " + " + r;
    }


    static String modeName(int mode) {
        switch (mode) {
            case RgbControl.MODE_BREATH: return "Breathing";
            case RgbControl.MODE_RAINBOW: return "Rainbow";
            case RgbControl.MODE_STROBE: return "Strobe";
            case RgbControl.MODE_MULTI: return "Multicolor";
            case RgbControl.MODE_SCREEN: return "Screen";
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
