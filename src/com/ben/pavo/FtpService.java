package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the FTP server alive while the user is in another app or the screen is
 * off, with a notification that shows the address and a stop action.
 */
public final class FtpService extends Service {

    static final String ACTION_START = "com.ben.pavo.ftp.START";
    static final String ACTION_STOP = "com.ben.pavo.ftp.STOP";
    private static final String CHANNEL = "pavo-ftp";
    private static final int NOTIFICATION_ID = 2201;

    private static FtpServer server;
    private static final List<String> log = new ArrayList<>();

    static boolean isRunning() {
        return server != null && server.isRunning();
    }

    static int clientCount() {
        return server == null ? 0 : server.clientCount();
    }

    static String host() {
        return FtpServer.localAddress();
    }

    static int port() {
        return server == null ? 0 : server.port();
    }

    static String logText() {
        synchronized (log) {
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, log.size() - 8);
            for (int i = start; i < log.size(); i++) {
                if (i > start) out.append('\n');
                out.append(log.get(i));
            }
            return out.toString();
        }
    }

    static void start(Context c) {
        Intent intent = new Intent(c, FtpService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent);
        else c.startService(intent);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, FtpService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (server == null || !server.isRunning()) {
            startServer();
        }
        updateNotification();
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "FTP sharing",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startServer() {
        Prefs prefs = new Prefs(this);
        List<FtpServer.Root> roots = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        File sd = removableRoot();
        int mode = prefs.ftpRoot();
        if ((mode == 0 || mode == 2) && internal != null) {
            roots.add(new FtpServer.Root("Internal", internal));
        }
        if ((mode == 1 || mode == 2) && sd != null) {
            roots.add(new FtpServer.Root("SD card", sd));
        }
        if (roots.isEmpty()) {
            push("No folder available to share");
            stopSelf();
            return;
        }
        synchronized (log) {
            log.clear();
        }
        server = new FtpServer(prefs.ftpPort(), roots, prefs.ftpAnonymous(),
                prefs.ftpUser(), prefs.ftpPassword(), new FtpServer.Listener() {
            @Override
            public void onLog(final String line) {
                push(line);
                updateNotification();
            }

            @Override
            public void onClients(int count) {
                updateNotification();
            }
        });
        server.start();
    }

    private static void push(String line) {
        synchronized (log) {
            log.add(line);
            while (log.size() > 40) log.remove(0);
        }
    }

    private File removableRoot() {
        try {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs != null && dirs.length > 1 && dirs[1] != null) {
                File mount = dirs[1];
                while (mount.getParentFile() != null
                        && !"/storage".equals(mount.getParentFile().getAbsolutePath())) {
                    mount = mount.getParentFile();
                }
                if (mount.isDirectory()) return mount;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void updateNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, FtpActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, FtpService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        int clients = clientCount();
        String host = host();
        String portText = port() == 0 ? "" : "port " + port() + " \u00b7 ";
        builder.setContentTitle(host == null ? "FTP sharing" : host)
                .setContentText(portText + (clients == 0 ? "waiting for connections"
                        : clients + (clients == 1 ? " client connected" : " clients connected")))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(null, "Stop", stopIntent).build());
        return builder.build();
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
