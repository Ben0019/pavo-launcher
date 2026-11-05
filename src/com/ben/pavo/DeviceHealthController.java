package com.ben.pavo;

import android.content.Context;
import android.os.SystemClock;

/**
 * Odin firmware exposes charge control through its privileged Settings bridge.
 * Writing the system switches lets the vendor service apply the correct qcom
 * battery state, while we verify the resulting hardware node.
 */
final class DeviceHealthController {
    private static final String USB_CHARGE_NODE = "/sys/class/qcom-battery/usb_charge_now";
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

    static Result setSeparation(Context c, boolean enabled) {
        int target = enabled ? 0 : 1;
        // The vendor clears the hardware separation when the display sleeps
        // but leaves the settings value at 1. Rewriting the same value does
        // nothing, so force a 0 -> 1 transition to re-arm it.
        if (enabled) {
            runPrivileged("settings put system " + SEPARATION_SETTING + " 0");
            SystemClock.sleep(160L);
        }
        runPrivileged("settings put system " + SEPARATION_SETTING + " "
                + (enabled ? "1" : "0"));
        if (waitForNode(c, USB_CHARGE_NODE, target, 2000L)) {
            return new Result(true, enabled
                    ? "Charge separation enabled · running from charger"
                    : "Charge separation disabled");
        }
        if (enabled) {
            // A second transition covers a slow vendor service after wake.
            runPrivileged("settings put system " + SEPARATION_SETTING + " 0");
            SystemClock.sleep(300L);
            runPrivileged("settings put system " + SEPARATION_SETTING + " 1");
            if (waitForNode(c, USB_CHARGE_NODE, 0, 2500L)) {
                return new Result(true,
                        "Charge separation enabled · running from charger");
            }
        }
        return new Result(false, "Odin charge separation did not respond");
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
