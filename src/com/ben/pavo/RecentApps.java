package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Small, launcher-owned recent-app list, fed by the accessibility foreground tracker. */
final class RecentApps {
    private static final String FILE = "pavo_recents";
    private static final String KEY = "apps";
    private static final String LOCKED_KEY = "locked";
    private static final int MAX = 8;
    private static final Object LOCK = new Object();

    private RecentApps() {}

    static void note(Context c, String pkg) {
        note(c, pkg, null);
    }

    /**
     * Records a recent. Built-in screens share the launcher package, so they
     * are keyed by "package/activity" and everything else by package alone.
     */
    static void note(Context c, String pkg, String cls) {
        if (c == null) return;
        String key = keyFor(c, pkg, cls);
        if (key == null) return;
        synchronized (LOCK) {
            List<String> list = read(c);
            if (!list.isEmpty() && key.equals(list.get(0))) return;
            list.remove(key);
            list.add(0, key);
            while (list.size() > MAX) list.remove(list.size() - 1);
            StringBuilder b = new StringBuilder();
            for (String p : list) {
                if (b.length() > 0) b.append('|');
                b.append(p);
            }
            prefs(c).edit().putString(KEY, b.toString()).apply();
        }
    }

    /** Recents key for a window, or null when it should not be listed. */
    static String keyFor(Context c, String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (pkg.equals(c.getPackageName())) {
            return Builtins.isBuiltinClass(cls) ? pkg + "/" + cls : null;
        }
        return eligible(c, pkg) ? pkg : null;
    }

    static String packageOf(String key) {
        if (key == null) return null;
        int slash = key.indexOf('/');
        return slash < 0 ? key : key.substring(0, slash);
    }

    static String activityOf(String key) {
        if (key == null) return null;
        int slash = key.indexOf('/');
        return slash < 0 ? null : key.substring(slash + 1);
    }

    static List<String> list(Context c) {
        synchronized (LOCK) {
            return read(c);
        }
    }

    /** Locked apps survive clear-all, the X button and Y until unlocked. */
    static boolean isLocked(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return false;
        synchronized (LOCK) {
            return lockedSet(c).contains(pkg);
        }
    }

    static void setLocked(Context c, String pkg, boolean locked) {
        if (c == null || pkg == null || pkg.isEmpty()) return;
        synchronized (LOCK) {
            Set<String> set = new LinkedHashSet<>(lockedSet(c));
            boolean changed = locked ? set.add(pkg) : set.remove(pkg);
            if (!changed) return;
            StringBuilder b = new StringBuilder();
            for (String p : set) {
                if (b.length() > 0) b.append('|');
                b.append(p);
            }
            if (b.length() == 0) prefs(c).edit().remove(LOCKED_KEY).apply();
            else prefs(c).edit().putString(LOCKED_KEY, b.toString()).apply();
        }
    }

    private static Set<String> lockedSet(Context c) {
        String raw = prefs(c).getString(LOCKED_KEY, "");
        if (raw == null || raw.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String pkg : raw.split("\\|")) {
            if (pkg != null && !pkg.isEmpty()) out.add(pkg);
        }
        return out;
    }

    static void remove(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return;
        synchronized (LOCK) {
            if (lockedSet(c).contains(pkg)) return;
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
