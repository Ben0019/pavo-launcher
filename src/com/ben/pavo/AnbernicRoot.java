package com.ben.pavo;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;

/**
 * Anbernic firmware (RG477V and siblings) ships /system/bin/anbernicsu, a
 * setuid-root helper that refuses only uid 0 and uid 2000 (adb shell).
 * Control Center runs its "Root Scripts" through it as real root.
 *
 * Ordinary apps are blocked: zygote's seccomp filter kills the process with
 * SIGSYS when it calls the setuid syscalls, so available() is false on current
 * firmware. The install path is kept as a best-effort silent route (it is what
 * PServer gives on AYN devices) and falls back to the system installer.
 */
final class AnbernicRoot {
    private static final String BIN = "/system/bin/anbernicsu";
    private static final String CMD = "/system/bin/cmd";

    private static Boolean available;
    private static long checkedAt;

    private AnbernicRoot() {}

    static boolean available() {
        synchronized (AnbernicRoot.class) {
            if (available != null && SystemClock.uptimeMillis() - checkedAt < 30000L)
                return available;
            checkedAt = SystemClock.uptimeMillis();
            available = false;
            if (!new File(BIN).canExecute()) return false;
            try {
                Process p = new ProcessBuilder(BIN, "root", "id", "-u").start();
                String out = read(p.getInputStream()).trim();
                p.waitFor();
                available = "0".equals(out);
            } catch (Throwable ignored) {
                available = false;
            }
            return available;
        }
    }

    /** Runs argv as root and returns its combined output. Call from a worker. */
    static String exec(String... argv) {
        if (argv == null || argv.length == 0) return "";
        String[] command = new String[argv.length + 2];
        command[0] = BIN;
        command[1] = "root";
        System.arraycopy(argv, 0, command, 2, argv.length);
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true).start();
            String out = read(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Installs an APK through the package service as root. The APK is streamed
     * on stdin because the package manager cannot read /sdcard paths.
     * Fire-and-forget: the installer replaces this process on success.
     */
    static boolean install(final Context context, final File apk) {
        if (apk == null || !apk.isFile() || !available()) return false;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                String result = "";
                try {
                    Process p = new ProcessBuilder(BIN, "root", CMD, "package", "install",
                            "-S", String.valueOf(apk.length()), "-r")
                            .redirectErrorStream(true).start();
                    OutputStream stdin = p.getOutputStream();
                    InputStream in = new FileInputStream(apk);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) stdin.write(buf, 0, n);
                    in.close();
                    stdin.close();
                    result = read(p.getInputStream());
                    p.waitFor();
                } catch (Throwable t) {
                    result = t.getMessage() == null ? "install failed" : t.getMessage();
                }
                if (!result.contains("Success")) {
                    try {
                        Toast.makeText(app, "Update failed: " + result.trim(),
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "pavo-anbernic-install").start();
        return true;
    }

    private static String read(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }
}
