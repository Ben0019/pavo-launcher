package com.ben.pavo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight foreground-package lookup for the per-app watcher. PServer writes a
 * tiny top-activity snapshot into Pavo's own files directory; no accessibility
 * or usage-access permission is required.
 */
final class ForegroundApp {

    private static final String FILE = "top_app.txt";
    private static final Pattern PACKAGE = Pattern.compile(
            "([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/");

    private ForegroundApp() {
    }

    static void request(Context context) {
        if (context == null) return;
        String dir = context.getFilesDir().getAbsolutePath();
        // Prefer the focused window. On some Android 15 builds topResumedActivity
        // can still name the previously mapped game while its task is in Recents,
        // which leaves the performance overlay stuck in mapper mode.
        String command = "cd " + quote(dir)
                + "; { dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus='; "
                + "dumpsys window 2>/dev/null | grep -m1 'mFocusedApp='; "
                + "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity; }"
                + " > " + FILE
                + "; chmod 644 " + FILE;
        if (PServer.available() && PServer.run(command)) return;
        try {
            Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Throwable ignored) {
        }
    }

    static String read(Context context) {
        if (context == null) return null;
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return null;
        if (System.currentTimeMillis() - file.lastModified() > 12_000L) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = PACKAGE.matcher(line);
                if (m.find()) return m.group(1);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
