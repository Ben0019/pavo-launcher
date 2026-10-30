package com.ben.pavo;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * AYN/Retroid firmware exposes a privileged PServerBinder service. It accepts a
 * short shell command and runs it as root in the pservice context. ClusterTune
 * and PULSE use this service to tune protected sysfs nodes without Magisk or a
 * user-granted root shell.
 */
final class PServer {

    private static IBinder binder;
    private static boolean checked;

    private PServer() {}

    static boolean available() {
        synchronized (PServer.class) {
            if (binder != null && binder.isBinderAlive()) return true;
            if (checked && binder == null) return false;
            checked = true;
            try {
                Class<?> manager = Class.forName("android.os.ServiceManager");
                Method getService = manager.getDeclaredMethod("getService", String.class);
                getService.setAccessible(true);
                binder = (IBinder) getService.invoke(null, "PServerBinder");
            } catch (Throwable ignored) {
                binder = null;
            }
            return binder != null && binder.isBinderAlive();
        }
    }

    /** Fire-and-forget. PServer truncates commands beyond roughly 255 bytes. */
    static boolean run(String command) {
        if (command == null || command.isEmpty()) return false;
        if (!available()) return false;
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeStringArray(new String[]{command, "0"});
                return binder.transact(0, data, reply, 0);
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            synchronized (PServer.class) {
                if (binder != null && !binder.isBinderAlive()) {
                    binder = null;
                    checked = false;
                }
            }
            return false;
        }
    }

    /**
     * Runs a command through PServer and returns its output. PService is
     * fire-and-forget, so the command is written to a script in the app cache
     * and its output is picked up from a sibling file. Call from a worker
     * thread; this waits for the command to finish.
     */
    static String exec(String command) {
        return exec(command, 900L);
    }

    static String exec(String command, long timeoutMs) {
        if (command == null || command.isEmpty() || !available()) return "";
        Context context = appContext();
        if (context == null) return "";
        File cache = context.getCacheDir();
        if (cache == null) return "";
        synchronized (EXEC_LOCK) {
            try {
                File script = new File(cache, "pavo-pserver.sh");
                File out = new File(cache, "pavo-pserver.out");
                write(script, "#!/system/bin/sh\n" + command + "\n");
                out.delete();
                String line = "cd " + cache.getAbsolutePath()
                        + "; sh " + script.getName() + " > " + out.getName()
                        + " 2>&1; chmod 644 " + out.getName();
                if (line.length() > 250 || !run(line)) return "";
                long deadline = SystemClock.uptimeMillis() + timeoutMs;
                long last = -1L;
                while (SystemClock.uptimeMillis() < deadline) {
                    long size = out.length();
                    if (size > 0 && size == last) break;
                    last = size;
                    Thread.sleep(15L);
                }
                return read(out);
            } catch (Throwable t) {
                return "";
            }
        }
    }

    private static final Object EXEC_LOCK = new Object();

    private static Context appContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getMethod("currentApplication");
            return (Context) current.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[8192];
            int n = in.read(data);
            return n <= 0 ? "" : new String(data, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    static void reset() {
        synchronized (PServer.class) {
            binder = null;
            checked = false;
        }
    }
}
