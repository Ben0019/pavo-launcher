package com.ben.pavo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

/** Copies Android's stored task snapshots into the app cache for the recents strip. */
final class RecentsSnapshotCache {
    private static final Object LOCK = new Object();
    private static volatile long lastRefresh;
    private static volatile boolean refreshing;

    private RecentsSnapshotCache() {}

    static void refresh(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            long now = android.os.SystemClock.uptimeMillis();
            if (refreshing || now - lastRefresh < 700L) return;
            refreshing = true;
        }
        try {
            if (!PServer.available()) return;
            File script = new File(c.getCacheDir(), "pavo-recents-cache.sh");
            String text = "#!/system/bin/sh\n"
                    + "dir=/data/data/com.ben.pavo/cache/recents\n"
                    + "rm -rf \"$dir\"\n"
                    + "mkdir -p \"$dir\"\n"
                    + "chmod 755 \"$dir\"\n"
                    + ": > \"$dir/map.txt\"\n"
                    + "for f in /data/system_ce/0/recent_tasks/*_task.xml; do\n"
                    + "  [ -f \"$f\" ] || continue\n"
                    + "  id=${f##*/}; id=${id%_task.xml}\n"
                    + "  act=$(strings \"$f\" 2>/dev/null | grep -oE "
                    + "'[a-z][a-z0-9_]*([.][a-z0-9_]+)+/[A-Za-z0-9_.$]+' | head -1)\n"
                    + "  [ -z \"$act\" ] && continue\n"
                    + "  pkg=${act%%/*}\n"
                    + "  img=/data/system_ce/0/snapshots/${id}_reduced.jpg\n"
                    + "  [ -f \"$img\" ] || img=/data/system_ce/0/snapshots/${id}.jpg\n"
                    + "  [ -f \"$img\" ] || continue\n"
                    + "  cp \"$img\" \"$dir/$id.jpg\"\n"
                    + "  chmod 644 \"$dir/$id.jpg\"\n"
                    + "  echo \"$pkg|$act|$id\" >> \"$dir/map.txt\"\n"
                    + "done\n"
                    + "chmod 644 \"$dir/map.txt\"\n";
            try (FileOutputStream out = new FileOutputStream(script)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            PServer.exec("sh " + script.getAbsolutePath(), 1800L);
            lastRefresh = android.os.SystemClock.uptimeMillis();
        } catch (Throwable ignored) {
        } finally {
            synchronized (LOCK) {
                refreshing = false;
            }
        }
    }

    static File snapshotFor(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "recents");
        File map = new File(dir, "map.txt");
        if (!map.isFile()) return null;
        try (BufferedReader in = new BufferedReader(new FileReader(map))) {
            String line;
            while ((line = in.readLine()) != null) {
                int a = line.indexOf('|');
                int b = line.indexOf('|', a + 1);
                if (a <= 0 || b <= a) continue;
                if (!pkg.equals(line.substring(0, a))) continue;
                String id = line.substring(b + 1).trim();
                if (id.isEmpty()) continue;
                File img = new File(dir, id + ".jpg");
                if (img.isFile()) return img;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static void clear(Context c) {
        if (c == null) return;
        File dir = new File(c.getCacheDir(), "recents");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
