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

    /**
     * Caps the current the charger may deliver. 2000 mA (or more) means "leave
     * it to the charger" and lifts any ceiling Pavo set before.
     */
    static Result setInputLimit(Context c, int milliamps) {
        if (milliamps >= 2000) {
            // Lift the ceiling and let the charger/PD contract decide again.
            runPrivileged("echo 3000000 > /sys/class/power_supply/usb/input_current_limit");
            return new Result(true, "Charging at the charger's maximum");
        }
        int target = milliamps * 1000;
        String node = "/sys/class/power_supply/usb/input_current_limit";
        runPrivileged("echo " + target + " > " + node);
        long end = SystemClock.uptimeMillis() + 1200L;
        int seen = -1;
        do {
            seen = DeviceHealthReader.num(c, node, -1);
            // A weaker source clamps lower than asked, which is still a success.
            if (seen > 0 && seen <= target + 60000) {
                return new Result(true, "Charge speed limited to "
                        + (milliamps / 100f) + " A");
            }
            SystemClock.sleep(80L);
        } while (SystemClock.uptimeMillis() < end);
        return new Result(false, "The charger would not accept that limit (stuck at "
                + (seen / 1000) + " mA)");
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
