package com.ben.pavo;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * Direct fan control through Odin's own IFanControlProvider.
 *
 * <p>The vendor settings app runs a four second loop that applies its
 * temperature curve and rewrites the fan duty. Writing fan_mode/fan_speed
 * ourselves makes that loop fight back, which is the audible up/down ramp.
 * The provider's set-speed call cancels that loop and writes the duty node
 * directly, so a steady duty stays steady. It also keeps the fan inside the
 * firmware's own enable/disable path instead of racing the vendor daemon.</p>
 */
final class VendorFan {
    private static final String SERVICE = "SettingsController";
    private static final String MANAGER_TOKEN = "com.ro.settings.IExternalControlManager";
    private static final String FAN_TOKEN = "com.ro.settings.IFanControlProvider";
    private static final String FAN_NAME = "FanProvider";

    private static final int TX_GET_PROVIDER = 1;
    private static final int TX_ENABLE = 8;   // a(boolean)
    private static final int TX_SPEED = 9;    // b(int), raw 0..50000

    private static final long RETRY_MS = 30000L;

    private static IBinder provider;
    private static long failedAt;

    private VendorFan() {}

    /** True when the vendor fan provider can be reached. */
    static synchronized boolean available() {
        if (provider != null && provider.isBinderAlive()) return true;
        provider = null;
        DeviceProfile profile = DeviceProfile.get(null);
        if (profile == null || !profile.fanControl
                || profile.fanDir == null || profile.fanDir.isEmpty()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (failedAt != 0 && now - failedAt < RETRY_MS) return false;
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            IBinder manager = (IBinder) get.invoke(null, SERVICE);
            if (manager == null) {
                failedAt = now;
                return false;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(MANAGER_TOKEN);
                data.writeString(FAN_NAME);
                if (!manager.transact(TX_GET_PROVIDER, data, reply, 0)) {
                    failedAt = now;
                    return false;
                }
                reply.readException();
                provider = reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
            failedAt = provider == null ? now : 0;
            return provider != null;
        } catch (Throwable ignored) {
            provider = null;
            failedAt = now;
            return false;
        }
    }

    static synchronized boolean setEnabled(boolean on) {
        if (!available()) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(FAN_TOKEN);
            data.writeInt(on ? 1 : 0);
            if (!provider.transact(TX_ENABLE, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Throwable ignored) {
            provider = null;
            failedAt = SystemClock.uptimeMillis();
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Set the fan duty as a percentage, cancelling the vendor smart loop. */
    static synchronized boolean setPercent(int percent) {
        if (!available()) return false;
        // The firmware can switch the blower off on its own; re-enable it
        // before writing the duty, otherwise the write is silently ignored.
        if (!nodeEnabled()) setEnabled(true);
        int period = DeviceProfile.get(null).fanPeriod;
        if (period <= 0) period = 50000;
        int raw = Math.max(0, Math.min(100, percent)) * period / 100;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(FAN_TOKEN);
            data.writeInt(raw);
            if (!provider.transact(TX_SPEED, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Throwable ignored) {
            provider = null;
            failedAt = SystemClock.uptimeMillis();
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** The firmware can disable the blower on its own; make sure state is 1. */
    private static boolean nodeEnabled() {
        DeviceProfile profile = DeviceProfile.get(null);
        if (profile == null || profile.fanDir == null || profile.fanDir.isEmpty()) {
            return false;
        }
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(
                    profile.fanDir + "/state");
            byte[] b = new byte[8];
            int n = in.read(b);
            in.close();
            return n > 0 && new String(b, 0, n).trim().equals("1");
        } catch (Throwable ignored) {
            return true;
        }
    }
}
