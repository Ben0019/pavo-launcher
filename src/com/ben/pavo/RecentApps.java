package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Small, launcher-owned recent-app list, fed by the accessibility foreground tracker. */
final class RecentApps {
    private static final String FILE = "pavo_recents";
    private static final String KEY = "apps";
    private static final int MAX = 8;
    private static final Object LOCK = new Object();

    private RecentApps() {}

    static void note(Context c, String pkg) {
        if (c == null || !eligible(c, pkg)) return;
        synchronized (LOCK) {
            List<String> list = read(c);
            if (!list.isEmpty() && pkg.equals(list.get(0))) return;
            list.remove(pkg);
            list.add(0, pkg);
            while (list.size() > MAX) list.remove(list.size() - 1);
            StringBuilder b = new StringBuilder();
            for (String p : list) {
                if (b.length() > 0) b.append('|');
                b.append(p);
            }
            prefs(c).edit().putString(KEY, b.toString()).apply();
        }
    }

    static List<String> list(Context c) {
        synchronized (LOCK) {
            return read(c);
        }
    }

    static void remove(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return;
        synchronized (LOCK) {
            List<String> list = read(c);
            if (!list.remove(pkg)) return;
            StringBuilder b = new StringBuilder();
            for (String p : list) {
                if (b.length() > 0) b.append('|');
                b.append(p);
            }
            if (b.length() == 0) prefs(c).edit().remove(KEY).apply();
            else prefs(c).edit().putString(KEY, b.toString()).apply();
        }
    }

    static void removeSystemTask(String pkg) {
        if (pkg == null || pkg.isEmpty() || !PServer.available()) return;
        String out = PServer.exec("dumpsys activity recents | grep -A1 -F \""
                + pkg + "\"", 1400L);
        if (out == null || out.isEmpty()) return;
        java.util.regex.Matcher root = java.util.regex.Pattern
                .compile("rootTaskId=(\\d+)").matcher(out);
        boolean any = false;
        while (root.find()) {
            any = true;
            PServer.run("am stack remove " + root.group(1));
        }
        if (any) return;
        java.util.regex.Matcher task = java.util.regex.Pattern
                .compile("Task\\{[^}]*#(\\d+)").matcher(out);
        while (task.find()) PServer.run("am stack remove " + task.group(1));
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            prefs(c).edit().remove(KEY).apply();
        }
    }

    private static List<String> read(Context c) {
        ArrayList<String> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String pkg : raw.split("\\|")) {
            if (pkg != null && !pkg.isEmpty() && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static boolean eligible(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String own = c.getPackageName();
        if (pkg.equals(own)) return false;
        if (pkg.equals("android") || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.launcher3")
                || pkg.equals("com.odin.odinlauncher")
                || pkg.equals("com.odin.gameassistant")) return false;
        String lower = pkg.toLowerCase(java.util.Locale.US);
        return !lower.contains("inputmethod")
                && !lower.contains("permissioncontroller")
                && !lower.contains(".keyboard");
    }
}
