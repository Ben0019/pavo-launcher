package com.ben.pavo;

import android.content.Context;
import android.os.SystemClock;

/**
 * Odin firmware exposes charge control through its privileged Settings bridge.
 * Writing the system switches lets the vendor service apply the correct qcom
 * battery state, while we verify the resulting hardware node.
 */
final class DeviceHealthController {
    private static final String LIMIT_NODE = "/sys/class/qcom-battery/limit_capacity_charge";
    private static final String USB_CHARGE_NODE = "/sys/class/qcom-battery/usb_charge_now";
    private static final String LIMIT_SETTING = "percent_80_charge_limit";
    private static final String SEPARATION_SETTING = "is_charging_separation";

    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private DeviceHealthController() {}

    /** Odin's hardware limit exposes the standard 80% threshold. */
    static Result setLimit80(Context c, boolean enabled) {
        int target = enabled ? 1 : 0;
        runPrivileged("settings put system " + LIMIT_SETTING + " " + target);
        if (waitForNode(c, LIMIT_NODE, target, 1200L)) {
            return new Result(true, enabled
                    ? "80% charge limit enabled"
                    : "80% charge limit disabled");
        }
        return new Result(false, "Odin charge limit did not respond");
    }

    static Result setSeparation(Context c, boolean enabled) {
        int target = enabled ? 0 : 1;
        runPrivileged("settings put system " + SEPARATION_SETTING + " "
                + (enabled ? "1" : "0"));
        if (waitForNode(c, USB_CHARGE_NODE, target, 1500L)) {
            return new Result(true, enabled
                    ? "Charge separation enabled · running from charger"
                    : "Charge separation disabled");
        }
        return new Result(false, "Odin charge separation did not respond");
    }

    static boolean isLimitOn(Context c) {
        return DeviceHealthReader.num(c, LIMIT_NODE, 0) == 1;
    }

    static boolean isSeparated(Context c) {
        return DeviceHealthReader.num(c, USB_CHARGE_NODE, 1) == 0;
    }

    private static boolean waitForNode(Context c, String path, int expected, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (DeviceHealthReader.num(c, path, Integer.MIN_VALUE) == expected) return true;
            SystemClock.sleep(70L);
        } while (SystemClock.uptimeMillis() < end);
        return false;
    }

    private static boolean runPrivileged(String command) {
        return PServer.available() && PServer.run(command);
    }
}
