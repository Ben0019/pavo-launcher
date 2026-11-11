package com.ben.pavo;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Two-press Home guard, implemented by the controller daemon which owns the
 * ff_key node. The daemon polls the marker file, so no restart is needed.
 */
final class HomeGuard {
    private HomeGuard() {}

    /** Write the flag the daemon polls; safe to call on every service start. */
    static void sync(Context c) {
        if (c == null) return;
        boolean on = new Prefs(c).homeTwoPress();
        File file = new File(c.getFilesDir(), "home_guard");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((on ? "1\n" : "0\n").getBytes(StandardCharsets.UTF_8));
            file.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    /** Enable/disable and make sure the Odin firmware guard stays out of the way. */
    static void set(Context c, boolean on) {
        if (c == null) return;
        new Prefs(c).setHomeTwoPress(on);
        // The firmware guard swallows presses before our daemon can see them.
        PerfTuner.Root.writeOk("settings put system prevent_press_home_accidentally 0");
        sync(c);
    }
}
