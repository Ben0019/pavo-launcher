package com.ben.pavo;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Owns the PServer-launched virtual-touch daemon. The normal controller remapper
 * is stopped while this daemon is active because both helpers need EVIOCGRAB on
 * the physical Odin controller.
 */
final class TouchMapManager {
    private static final String TAG = "AuroraTouchMap";
    private static final String BINARY = "pavo-touchmap";
    private static final String CONFIG = "touchmap.conf";
    private static final String PID = "touchmap.pid";
    private static final String LAUNCHER = "touchmap-launch.sh";
    private static final String LOG = "touchmap.log";

    private static boolean starting;

    private TouchMapManager() {
    }

    static File pidFile(Context c) {
        return new File(c.getFilesDir(), PID);
    }

    static synchronized boolean isRunning(Context c) {
        // /proc is hidepid-protected for this app UID, so the pid file is the
        // readiness signal. A stale pid is cleaned up by stopBlocking().
        return readPid(c) > 0;
    }

    static synchronized boolean isStarting() {
        return starting;
    }

    /**
     * Starts (or restarts) the helper. Call only from a worker thread; the
     * controller daemon shutdown and PID handshake take a few hundred ms.
     */
    static synchronized boolean start(Context context, List<TouchTarget> targets,
                                      TouchStick stick, boolean useStick, RightStick right,
                                      List<TouchCombo> combos, boolean rightHold,
                                      boolean controllerAlreadyStopped) {
        if (context == null || targets == null || combos == null) return false;
        boolean hasControls = !targets.isEmpty() || useStick || !combos.isEmpty()
                || (right != null && right.mode != RightStick.OFF);
        if (!hasControls) return false;
        if (!PServer.available()) {
            Log.w(TAG, "PServerBinder unavailable");
            return false;
        }
        if (starting) return isRunning(context);
        starting = true;
        Context app = context.getApplicationContext();
        try {
            stopBlocking(app);
            // The normal remapper may have been restarted while a modal overlay was
            // open. Always clear it before grabbing the controller, even when the
            // service believed it had already been stopped.
            if (ControllerRemapManager.isRunning(app)) {
                ControllerRemapManager.stop(app);
                Thread.sleep(200L);
            }

            File binary = new File(app.getFilesDir(), BINARY);
            if (!extract(app, binary)) return false;
            File config = new File(app.getFilesDir(), CONFIG);
            if (!writeConfig(config, targets, stick, useStick, right, combos,
                    rightHold, app)) return false;
            File launcher = writeLauncher(app);
            if (launcher == null) return false;
            pidFile(app).delete();

            boolean sent = PServer.run("sh " + shellQuote(launcher.getAbsolutePath()));
            if (!sent) {
                Log.w(TAG, "PServer rejected touch mapper launcher");
                return false;
            }
            long end = System.currentTimeMillis() + 7000L;
            while (System.currentTimeMillis() < end) {
                if (isRunning(app)) {
                    Log.i(TAG, "virtual touch daemon ready");
                    return true;
                }
                Thread.sleep(80L);
            }
            Log.w(TAG, "touch mapper did not write " + PID + "; see " + LOG);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
            return false;
        } finally {
            starting = false;
        }
    }

    static synchronized void stop(Context context) {
        if (context == null) return;
        stopBlocking(context.getApplicationContext());
    }

    private static void stopBlocking(Context app) {
        int pid = readPid(app);
        if (pid > 0) {
            if (PServer.available()) PServer.run("kill -TERM " + pid);
            try {
                Thread.sleep(60L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (PServer.available()) PServer.run("kill -KILL " + pid + " 2>/dev/null");
        }
        pidFile(app).delete();
    }

    private static boolean writeConfig(File file, List<TouchTarget> targets,
                                       TouchStick stick, boolean useStick, RightStick right,
                                       List<TouchCombo> combos, boolean rightHold,
                                       Context context) {
        StringBuilder b = new StringBuilder();
        Prefs prefs = new Prefs(context);
        int perfShortcut = RootlessUinput.rawForLabel(prefs.perfControllerRaw());
        int drawerShortcut = RootlessUinput.rawForLabel(prefs.drawerControllerRaw());
        if (perfShortcut > 0) b.append("shortcut ").append(perfShortcut).append('\n');
        if (drawerShortcut > 0 && drawerShortcut != perfShortcut) {
            b.append("shortcut ").append(drawerShortcut).append('\n');
        }
        float r3Radius = right == null ? 0.16f : right.zone.radius;
        for (TouchTarget t : targets) {
            if (useStick && (t.code == TouchTarget.DPAD_UP
                    || t.code == TouchTarget.DPAD_DOWN
                    || t.code == TouchTarget.DPAD_LEFT
                    || t.code == TouchTarget.DPAD_RIGHT)) {
                continue;
            }
            if (t.code == TouchTarget.R3_STICK) {
                b.append("r3stick ")
                        .append(String.format(java.util.Locale.US, "%.5f", t.x)).append(' ')
                        .append(String.format(java.util.Locale.US, "%.5f", t.y)).append(' ')
                        .append(String.format(java.util.Locale.US, "%.5f", r3Radius)).append('\n');
                continue;
            }
            b.append("map ").append(t.code).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", t.x)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", t.y)).append('\n');
        }
        for (TouchCombo c : combos) {
            b.append("combo ").append(c.modifier).append(' ').append(c.button).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", c.x)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", c.y)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", c.radius)).append('\n');
        }
        if (useStick) {
            TouchStick s = stick == null ? TouchStick.defaults() : stick;
            b.append("stick 0 1 ")
                    .append(String.format(java.util.Locale.US, "%.5f", s.x)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", s.y)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", s.radius)).append('\n');
        }
        if (right != null && right.mode != RightStick.OFF) {
            TouchStick z = right.zone;
            // ABS_Z / ABS_RZ are the Odin's right-stick axes.
            b.append("rstick ").append(right.mode).append(" 2 5 ")
                    .append(String.format(java.util.Locale.US, "%.5f", z.x)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", z.y)).append(' ')
                    .append(String.format(java.util.Locale.US, "%.5f", z.radius)).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            file.setReadable(true, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not write config", t);
            return false;
        }
    }

    private static File writeLauncher(Context app) {
        File launcher = new File(app.getFilesDir(), LAUNCHER);
        String dir = app.getFilesDir().getAbsolutePath();
        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("umask 022\n");
        s.append("cd ").append(shellQuote(dir)).append(" || exit 1\n");
        s.append("nohup setsid ./").append(BINARY)
                .append(" --config ").append(shellQuote("./" + CONFIG))
                .append(" --pid ").append(shellQuote("./" + PID))
                .append(" --client ").append(android.os.Process.myPid())
                .append(" >").append(shellQuote("./" + LOG))
                .append(" 2>&1 </dev/null &\n");
        try (FileOutputStream out = new FileOutputStream(launcher)) {
            out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            launcher.setReadable(true, false);
            launcher.setExecutable(true, false);
            return launcher;
        } catch (Throwable t) {
            Log.w(TAG, "could not write launcher", t);
            return null;
        }
    }

    private static boolean extract(Context app, File out) {
        try (InputStream in = app.getAssets().open(BINARY);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) fos.write(buffer, 0, n);
            out.setReadable(true, false);
            out.setExecutable(true, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not extract helper", t);
            out.delete();
            return false;
        }
    }

    private static int readPid(Context c) {
        File file = new File(c.getFilesDir(), PID);
        if (!file.isFile()) return -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader(file))) {
            String line = r.readLine();
            if (line == null) return -1;
            return Integer.parseInt(line.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
