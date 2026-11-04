package com.ben.pavo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
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
        // PServer truncates commands at roughly 255 bytes. Put the long
        // multi-snapshot command in a script and invoke it with a short path.
        // The snapshot is written to a temporary file and renamed, so readers
        // never observe a half-written or empty file while dumpsys is running.
        String tmp = FILE + ".new";
        String body = "#!/system/bin/sh\ncd " + quote(dir)
                + " || exit 1\n{ dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus='; "
                + "dumpsys window 2>/dev/null | grep -m1 'mFocusedApp='; "
                + "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity; }"
                + " > " + tmp + " 2>/dev/null\nuid=$(stat -c %u .. 2>/dev/null)\n"
                + "[ -n \"$uid\" ] && chown $uid:$uid " + tmp + " 2>/dev/null\n"
                + "chmod 644 " + tmp + " 2>/dev/null\n"
                + "mv -f " + tmp + " " + FILE + " 2>/dev/null\n";
        File script = new File(context.getFilesDir(), "top_app.sh");
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(body.getBytes("UTF-8"));
        } catch (Throwable ignored) {
            return;
        }
        script.setReadable(true, false);
        script.setExecutable(true, false);
        PServer.run("sh " + quote(script.getAbsolutePath()));
    }

    static String read(Context context) {
        // The PServer snapshot names the real focused window, so Pavo's own
        // overlays and the mapper editor cannot be mistaken for the foreground
        // app. Accessibility is only a fallback when PServer is unavailable.
        if (context != null) {
            File file = new File(context.getFilesDir(), FILE);
            if (file.isFile()
                    && System.currentTimeMillis() - file.lastModified() <= 12_000L) {
                String pkg = parse(file);
                if (pkg != null) return pkg;
            }
        }
        return PavoA11yService.foregroundPackage();
    }

    private static String parse(File file) {
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
