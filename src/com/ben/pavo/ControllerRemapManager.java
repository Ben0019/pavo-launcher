package com.ben.pavo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Starts/stops the privileged evdev remapper and owns its config file.
 *
 * The daemon can be launched by PServerBinder (no su/Magisk prompt) and exposes
 * an abstract local socket so PavoInputService can read the raw controller
 * buttons and temporarily swallow input while the floating drawer is open.
 */
final class ControllerRemapManager {

    private static final String TAG = "PavoRemap";
    private static final String BINARY = "pavo-remap";
    private static final String CONFIG = "controller_map.conf";
    private static final String PID = "controller_map.pid";
    private static final String LAUNCHER = "controller-launch.sh";
    private static final String LOG = "controller-remap.log";
    private static final String VERSION = "controller-daemon.version";
    private static final int DAEMON_VERSION = 4;
    static final String SOCKET = "aurora-input";

    private static Process process;
    private static boolean starting;
    private static volatile boolean running;

    private ControllerRemapManager() {
    }

    static File configFile(Context c) {
        return new File(c.getFilesDir(), CONFIG);
    }

    static File binaryFile(Context c) {
        return new File(c.getFilesDir(), BINARY);
    }

    static boolean isRunning(Context c) {
        if (new Prefs(c).rootlessRemap()) return RootlessRemap.isActive();
        if (!versionMatches(c)) return false;
        if (running && process != null && process.isAlive()) return true;
        int pid = readPid(c);
        if (pid > 0 && processMatches(pid)) return true;
        return socketListening();
    }

    /**
     * Starts the daemon even when remapping is disabled. In that mode the native
     * daemon passes buttons through unchanged and still reports raw events on the
     * socket, so mapped shortcuts and the floating drawer keep working.
     */
    static void ensureInputDaemon(Context context) {
        if (context == null) return;
        if (new Prefs(context).rootlessRemap()) return;
        if (isRunning(context) || starting) return;
        startInternal(context, null, true);
    }

    static void ensureStarted(final Context context) {
        if (context == null) return;
        if (new Prefs(context).controllerRemapEnabled()) start(context, null);
        else ensureInputDaemon(context);
    }

    interface Callback {
        void onResult(boolean running);
    }

    static synchronized void start(final Context context, final Callback callback) {
        startInternal(context, callback, false);
    }

    private static synchronized void startInternal(final Context context,
                                                   final Callback callback,
                                                   final boolean forInput) {
        if (starting) {
            if (callback != null) notifyWhenReady(context, callback);
            return;
        }
        persistEnabled(context);
        if (isRunning(context)) {
            if (callback != null) callback.onResult(true);
            return;
        }
        starting = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = startBlocking(app, forInput);
                } catch (Throwable t) {
                    Log.w(TAG, "start failed", t);
                } finally {
                    starting = false;
                }
                final boolean result = ok;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) callback.onResult(result);
                        if (result) {
                            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    PavoA11yService.restartControllerListener();
                                }
                            }, 900L);
                        }
                    }
                });
            }
        }, "PavoRemapStart").start();
    }

    private static boolean startBlocking(Context c, boolean forInput) throws Exception {
        File config = configFile(c);
        ControllerMapConfig cfg = new ControllerMapConfig();
        cfg.load(config);
        boolean enabled = new Prefs(c).controllerRemapEnabled();
        cfg.save(config, enabled, PavoInputService.isRemapUiActive());

        if (new Prefs(c).rootlessRemap()) {
            boolean ok = RootlessRemap.start(c);
            Log.i(TAG, ok ? "rootless accessibility remapper started"
                    : "rootless remapper unavailable");
            return ok;
        }

        File binary = binaryFile(c);
        if (!extractBinary(c, binary)) return false;
        killExisting(c);
        String device = findControllerDevice();
        File launcher = writeLauncher(c, device);
        if (launcher == null) return false;
        new File(c.getFilesDir(), PID).delete();

        boolean launched = PServer.run("sh " + shellQuote(launcher.getAbsolutePath()));
        if (launched) Log.i(TAG, "launcher sent through PServer");
        if (!launched) return false;

        long end = System.currentTimeMillis() + 6000L;
        while (System.currentTimeMillis() < end) {
            int pid = readPid(c);
            if ((pid > 0 && processMatches(pid)) || socketListening()) {
                running = true;
                writeVersion(c);
                Log.i(TAG, "remapper started on " + (device == null ? "auto" : device));
                return true;
            }
            Thread.sleep(80L);
        }
        Log.w(TAG, "remapper did not open " + SOCKET + "; see " + LOG);
        return false;
    }

    static synchronized void stop(final Context context) {
        running = false;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File config = configFile(app);
                    ControllerMapConfig cfg = new ControllerMapConfig();
                    cfg.load(config);
                    cfg.save(config, false, false);
                    RootlessRemap.stop();
                    Thread.sleep(60L);
                    killExisting(app);
                } catch (Throwable t) {
                    Log.w(TAG, "stop failed", t);
                }
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        PavoA11yService.restartControllerListener();
                    }
                }, 500L);
            }
        }, "PavoRemapStop").start();
    }

    static void restart(final Context context, final Callback callback) {
        stop(context);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                start(context, callback);
            }
        }, 750L);
    }

    private static void notifyWhenReady(final Context context, final Callback callback) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int tries;

            @Override
            public void run() {
                if (isRunning(context)) {
                    callback.onResult(true);
                } else if (++tries < 9) {
                    handler.postDelayed(this, 800L);
                } else {
                    callback.onResult(false);
                }
            }
        }, 1000L);
    }

    private static void persistEnabled(Context c) {
        try {
            File config = configFile(c);
            ControllerMapConfig cfg = new ControllerMapConfig();
            cfg.load(config);
            cfg.save(config, new Prefs(c).controllerRemapEnabled(),
                    PavoInputService.isRemapUiActive());
        } catch (Throwable ignored) {
        }
    }

    private static File writeLauncher(Context c, String device) {
        File launcher = new File(c.getFilesDir(), LAUNCHER);
        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("umask 022\n");
        s.append("cd ").append(shellQuote(c.getFilesDir().getAbsolutePath()))
                .append(" || exit 1\n");
        s.append("nohup setsid ./").append(BINARY)
                .append(" --config ").append(shellQuote("./" + CONFIG))
                .append(" --pid ").append(shellQuote("./" + PID))
                .append(" --socket ").append(shellQuote(SOCKET));
        if (device != null && !device.isEmpty()) {
            s.append(" --device ").append(shellQuote(device));
        }
        s.append(" >").append(shellQuote("./" + LOG)).append(" 2>&1 </dev/null &\n");
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

    private static boolean extractBinary(Context c, File out) {
        try (InputStream in = c.getAssets().open(BINARY);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) fos.write(buffer, 0, n);
            out.setReadable(true, false);
            out.setExecutable(true, false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not extract remapper", t);
            out.delete();
            return false;
        }
    }

    private static void consume(final Process p) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) Log.d(TAG, line);
                } catch (Throwable ignored) {
                }
            }
        }, "PavoRemapLog").start();
    }

    private static void killExisting(Context c) {
        running = false;
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
            process = null;
        }
        int pid = readPid(c);
        if (pid > 0) {
            privileged("kill -TERM " + pid);
            sleep(60L);
            if (processMatches(pid)) privileged("kill -KILL " + pid);
        }
        // A stale daemon from an older install may not have written a pid file.
        privileged("killall pavo-remap 2>/dev/null");
        new File(c.getFilesDir(), PID).delete();
        new File(c.getFilesDir(), VERSION).delete();
    }

    private static boolean versionMatches(Context c) {
        File f = new File(c.getFilesDir(), VERSION);
        if (!f.isFile()) return false;
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) {
            String line = r.readLine();
            return line != null && Integer.parseInt(line.trim()) == DAEMON_VERSION;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeVersion(Context c) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), VERSION))) {
            out.write((DAEMON_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static void privileged(String command) {
        PServer.run(command);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int readPid(Context c) {
        File f = new File(c.getFilesDir(), PID);
        if (!f.isFile()) return -1;
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) {
            String line = r.readLine();
            if (line == null) return -1;
            return Integer.parseInt(line.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean socketListening() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/net/unix"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("@" + SOCKET)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean processMatches(int pid) {
        if (pid <= 0) return false;
        File proc = new File("/proc/" + pid);
        if (!proc.exists()) return false;
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return false;
            String cmd = new String(buf, 0, n, StandardCharsets.UTF_8);
            return cmd.contains(BINARY);
        } catch (Throwable ignored) {
            return proc.exists();
        }
    }

    /** Parse /proc/bus/input/devices, which is world-readable. */
    private static String findControllerDevice() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/bus/input/devices"), StandardCharsets.UTF_8))) {
            String name = null;
            String handlers = null;
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty()) {
                    String device = eventNode(name, handlers);
                    if (device != null) return device;
                    name = null;
                    handlers = null;
                } else if (t.startsWith("N: Name=")) {
                    name = t.substring(7).trim();
                } else if (t.startsWith("H: Handlers=")) {
                    handlers = t.substring(12).trim();
                }
            }
            return eventNode(name, handlers);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String eventNode(String name, String handlers) {
        if (name == null || handlers == null) return null;
        String lower = name.toLowerCase(java.util.Locale.US);
        if (!lower.contains("odin controller") || lower.contains("virtual")) return null;
        for (String token : handlers.split("\\s+")) {
            if (token.startsWith("event")) return "/dev/input/" + token;
        }
        return null;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
