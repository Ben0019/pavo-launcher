package com.ben.pavo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives the result of a PackageInstaller session (non-root updates). */
public final class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION_RESULT = "com.ben.pavo.UPDATE_RESULT";
    static final String EXTRA_SESSION = "session";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !ACTION_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        LToast.makeText(context, "Update failed" + (message == null ? "" : ": " + message),
                Toast.LENGTH_SHORT).show();
    }
}
