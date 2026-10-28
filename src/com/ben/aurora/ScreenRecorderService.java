package com.ben.aurora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Foreground service that owns the MediaProjection session and recording engine. */
public final class ScreenRecorderService extends Service {
    static final String ACTION_START = "com.ben.aurora.action.SR_START";
    static final String ACTION_STOP = "com.ben.aurora.action.SR_STOP";
    static final String ACTION_STATE = "com.ben.aurora.action.SR_STATE";
    static final String EXTRA_RESULT_CODE = "sr_result_code";
    static final String EXTRA_RESULT_DATA = "sr_result_data";
    static final String EXTRA_CONFIG = "sr_config";

    private static final String CHANNEL_ID = "aurora_screen_record";
    private static final int NOTIFICATION_ID = 4301;
    private static final int COLOR = 0xFFFF3D3D;

    private static volatile boolean recording;
    private static volatile boolean saving;
    private static volatile long startedAt;
    private static volatile String lastSavedPath;
    private static volatile String lastError;
    private volatile String targetPackage;
    private volatile String targetLabel;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScreenRecorderEngine engine;
    private MediaProjection projection;
    private ScreenRecorderConfig config;
    private String notificationText = "Preparing\u2026";
    private int previousShowTouches = -1;
    private boolean touchesChanged;
    private ScreenRecorderOverlay overlay;
    private boolean paused;
    private long pausedAt;
    private long pausedTotal;
    private boolean deviceAudioOn;
    private boolean micAudioOn;
    private boolean started;
    private boolean stopping;

    static boolean isRecording() {
        return recording;
    }

    static boolean isSaving() {
        return saving;
    }

    static long recordingStartedAt() {
        return startedAt;
    }

    static String lastSavedPath() {
        return lastSavedPath;
    }

    static String lastError() {
        return lastError;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording(true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        if (started) return START_STICKY;

        Intent configIntent = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_CONFIG, Intent.class)
                : intent.getParcelableExtra(EXTRA_CONFIG);
        config = ScreenRecorderConfig.fromIntent(configIntent);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (config == null || data == null) {
            fail("The screen recording permission was not returned.");
            return START_NOT_STICKY;
        }

        started = true;
        recording = true;
        saving = false;
        startedAt = System.currentTimeMillis();
        lastSavedPath = null;
        lastError = null;
        startForegroundCompat();

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager == null ? null
                    : manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("Projection unavailable");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    main.post(new Runnable() {
                        @Override public void run() { stopRecording(false); }
                    });
                }
            }, main);

            resolveRecordingTarget();
            if (config.showTouches) applyTouchDots(true);
            else applyTouchDots(false);
            engine = new ScreenRecorderEngine(this, config, projection,
                    new ScreenRecorderEngine.Listener() {
                        @Override public void onInfo(String message) {
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (message != null && !message.isEmpty()) {
                                        Toast.makeText(ScreenRecorderService.this, message,
                                                Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                        @Override public void onError(final String message) {
                            main.post(new Runnable() {
                                @Override public void run() { fail(message); }
                            });
                        }
                    });
            engine.start();
            deviceAudioOn = config.deviceAudio;
            micAudioOn = config.micAudio;
            paused = false;
            pausedAt = 0L;
            pausedTotal = 0L;
            overlay = new ScreenRecorderOverlay(this);
            overlay.show(new ScreenRecorderOverlay.Listener() {
                @Override public void onPauseResume() { togglePause(); }
                @Override public void onStop() { stopRecording(true); }
                @Override public void onToggleDevice() { toggleDeviceAudio(); }
                @Override public void onToggleMic() { toggleMicAudio(); }
                @Override public void onScreenshot() { takeScreenshot(); }
            }, deviceAudioOn, micAudioOn);
            notificationText = "Recording";
            updateNotification();
            main.removeCallbacks(ticker);
            main.post(ticker);
        } catch (Throwable t) {
            fail(t.getMessage() == null ? "Could not start recording." : t.getMessage());
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification("Preparing\u2026");
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (config != null && config.micAudio) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(NOTIFICATION_ID, n, type);
        } catch (Throwable t) {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Screen recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Aurora screen recording status and stop control");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, ScreenRecorderActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ScreenRecorderService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 11, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_recording)
                .setContentTitle("Aurora screen recording")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(COLOR)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi);
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        String text = notificationText;
        if (recording) {
            long secs = elapsedSeconds();
            text = String.format(Locale.US, "%s  %02d:%02d",
                    paused ? "Paused" : "Recording", secs / 60L, secs % 60L);
            if (overlay != null) {
                overlay.update(paused, deviceAudioOn, micAudioOn, secs);
            }
        }
        nm.notify(NOTIFICATION_ID, buildNotification(text));
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (recording) {
                updateNotification();
                main.postDelayed(this, 1000L);
            }
        }
    };

    private void applyTouchDots(boolean enable) {
        try {
            if (enable) {
                boolean changed = PServer.available()
                        && PServer.run("settings put system show_touches 1");
                if (!changed) {
                    try {
                        Settings.System.putInt(getContentResolver(), "show_touches", 1);
                        changed = true;
                    } catch (Throwable ignored) {}
                }
                touchesChanged = changed;
            } else {
                boolean off = PServer.available()
                        && PServer.run("settings put system show_touches 0");
                if (!off) {
                    try {
                        Settings.System.putInt(getContentResolver(),
                                "show_touches", 0);
                    } catch (Throwable ignored) {}
                }
                touchesChanged = false;
            }
        } catch (Throwable ignored) {}
    }

    /** The launcher keeps touch dots off unless a recorder session is running. */
    static void forceTouchDotsOff(Context context) {
        try {
            if (PServer.available()) {
                if (PServer.run("settings put system show_touches 0")) return;
            }
            Settings.System.putInt(context.getContentResolver(), "show_touches", 0);
        } catch (Throwable ignored) {}
    }

    private long elapsedSeconds() {
        long elapsed = System.currentTimeMillis() - startedAt - pausedTotal;
        if (paused && pausedAt > 0L) elapsed -= System.currentTimeMillis() - pausedAt;
        return Math.max(0L, elapsed / 1000L);
    }

    private void togglePause() {
        if (!recording || engine == null) return;
        if (!paused) {
            paused = true;
            pausedAt = System.currentTimeMillis();
            engine.setPaused(true);
        } else {
            if (pausedAt > 0L) pausedTotal += System.currentTimeMillis() - pausedAt;
            pausedAt = 0L;
            paused = false;
            engine.setPaused(false);
        }
        if (overlay != null) overlay.update(paused, deviceAudioOn, micAudioOn, elapsedSeconds());
        updateNotification();
        Toast.makeText(this, paused ? "Recording paused" : "Recording resumed",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleDeviceAudio() {
        deviceAudioOn = !deviceAudioOn;
        if (engine != null) engine.setDeviceAudioEnabled(deviceAudioOn);
        if (overlay != null) overlay.update(paused, deviceAudioOn, micAudioOn, elapsedSeconds());
        Toast.makeText(this, deviceAudioOn ? "System audio recording on"
                : "System audio recording off", Toast.LENGTH_SHORT).show();
    }

    private void toggleMicAudio() {
        micAudioOn = !micAudioOn;
        if (engine != null) engine.setMicAudioEnabled(micAudioOn);
        if (overlay != null) overlay.update(paused, deviceAudioOn, micAudioOn, elapsedSeconds());
        Toast.makeText(this, micAudioOn ? "Microphone recording on"
                : "Microphone recording off", Toast.LENGTH_SHORT).show();
    }

    private void takeScreenshot() {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Aurora Screenshots");
        final String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.US).format(new java.util.Date());
        final File file = new File(dir, "Aurora_" + stamp + ".png");
        final boolean[] hidden = {false};
        if (overlay != null) {
            overlay.setVisible(false);
            hidden[0] = true;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                boolean started = false;
                try {
                    if (!dir.exists()) dir.mkdirs();
                    String command = "screencap -p " + shellQuote(file.getAbsolutePath());
                    started = PServer.available() && PServer.run(command);
                } catch (Throwable ignored) {}
                final boolean ok = started;
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (hidden[0] && overlay != null) overlay.setVisible(true);
                        if (ok && file.isFile()) {
                            MediaScannerConnection.scanFile(ScreenRecorderService.this,
                                    new String[]{file.getAbsolutePath()},
                                    new String[]{"image/png"}, null);
                            Toast.makeText(ScreenRecorderService.this, "Screenshot saved",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ScreenRecorderService.this,
                                    "Screenshot needs PServer/root", Toast.LENGTH_SHORT).show();
                        }
                    }
                }, 900L);
            }
        }, 160L);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void fail(String message) {
        if (overlay != null) { overlay.hide(); overlay = null; }
        lastError = message == null ? "Recording failed." : message;
        recording = false;
        saving = false;
        notificationText = "Recording failed";
        Toast.makeText(this, lastError, Toast.LENGTH_LONG).show();
        cleanup();
        stopSelf();
    }

    private void stopRecording(boolean fromUser) {
        if (stopping) return;
        if (!started) {
            stopSelf();
            return;
        }
        stopping = true;
        recording = false;
        if (overlay != null) { overlay.hide(); overlay = null; }
        saving = true;
        notificationText = "Saving\u2026";
        main.removeCallbacks(ticker);
        updateNotification();
        final ScreenRecorderEngine current = engine;
        new Thread(new Runnable() {
            @Override public void run() {
                File result = null;
                String error = null;
                try {
                    if (current != null) result = current.stop();
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "Could not save recording." : t.getMessage();
                }
                if (result != null) {
                    result = renameRecording(result, config);
                    lastSavedPath = result.getAbsolutePath();
                    MediaScannerConnection.scanFile(ScreenRecorderService.this,
                            new String[]{result.getAbsolutePath()},
                            new String[]{config == null ? "video/mp4" : config.mimeType()}, null);
                } else if (error == null) {
                    error = "No recording file was created.";
                }
                if (error != null) lastError = error;
                final String finalError = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (finalError != null) {
                            Toast.makeText(ScreenRecorderService.this, finalError,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(ScreenRecorderService.this,
                                    "Recording saved", Toast.LENGTH_SHORT).show();
                        }
                        finishService();
                    }
                });
            }
        }, "sr-stop").start();
    }

    private void resolveRecordingTarget() {
        if (config == null || config.mode != ScreenRecorderConfig.MODE_SINGLE_APP) return;
        for (int i = 1; i <= 6; i++) {
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!recording && !saving) return;
                    ForegroundApp.request(ScreenRecorderService.this);
                    String pkg = ForegroundApp.read(ScreenRecorderService.this);
                    if (pkg == null || pkg.isEmpty()
                            || pkg.equals(getPackageName())
                            || pkg.equals("com.android.systemui")
                            || pkg.equals("android")) return;
                    targetPackage = pkg;
                    try {
                        android.content.pm.PackageManager pm = getPackageManager();
                        android.content.pm.ApplicationInfo info =
                                pm.getApplicationInfo(pkg, 0);
                        CharSequence label = pm.getApplicationLabel(info);
                        if (label != null) targetLabel = label.toString();
                    } catch (Throwable ignored) {
                    }
                }
            }, i * 1000L);
        }
    }

    private File renameRecording(File file, ScreenRecorderConfig cfg) {
        if (file == null || !file.isFile() || cfg == null) return file;
        String prefix;
        if (cfg.mode == ScreenRecorderConfig.MODE_SINGLE_APP) {
            prefix = targetLabel != null && !targetLabel.trim().isEmpty()
                    ? targetLabel : (targetPackage == null ? "app" : targetPackage);
        } else {
            prefix = "screen";
        }
        prefix = prefix.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_").trim();
        if (prefix.isEmpty()) prefix = "app";
        if (prefix.length() > 42) prefix = prefix.substring(0, 42);
        String name = file.getName();
        String stamp = name.startsWith("Aurora_") ? name.substring(7) : name;
        File dest = new File(file.getParentFile(), prefix + "_" + stamp);
        int i = 2;
        while (dest.exists()) {
            dest = new File(file.getParentFile(), prefix + "_" + i + "_" + stamp);
            i++;
        }
        try {
            if (file.renameTo(dest)) return dest;
        } catch (Throwable ignored) {}
        return file;
    }

    private void cleanup() {
        if (overlay != null) { overlay.hide(); overlay = null; }
        applyTouchDots(false);
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
        engine = null;
    }

    private void finishService() {
        saving = false;
        notificationText = "Saved";
        cleanup();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()));
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(ticker);
        if (engine != null) {
            try { engine.stop(); } catch (Throwable ignored) {}
        }
        cleanup();
        recording = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
