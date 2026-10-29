package com.ben.pavo;

import android.os.IBinder;
import android.os.Parcel;

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

    static void reset() {
        synchronized (PServer.class) {
            binder = null;
            checked = false;
        }
    }
}
