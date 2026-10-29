package com.ben.pavo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the automatic charge-separation guard after a reboot. */
public final class DeviceHealthBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        DeviceHealthStore store = new DeviceHealthStore(context);
        if (store.autoSeparation()) BatteryGuardService.start(context);
    }
}
