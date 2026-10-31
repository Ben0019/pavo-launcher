package com.ben.pavo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the automatic charge-separation guard after a reboot. */
public final class DeviceHealthBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        // A package update kills the guard; re-arm it immediately instead of
        // waiting for the launcher or Device Health to be opened again.
        BatteryGuardService.armIfNeeded(context);
    }
}
