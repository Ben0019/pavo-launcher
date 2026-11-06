package com.ben.pavo;

import android.content.Context;

/**
 * Keeps the daemon's config file pointed at the profile for the app that is in
 * front: a per-app override when one exists, the global profile otherwise.
 */
final class ControllerProfiles {

    /** True while Controller Remapper is open, so editing is not overwritten. */
    static volatile boolean activityOpen;
    private static volatile boolean dirty = true;
    private static String lastKey = "";

    private ControllerProfiles() {}

    static void markDirty() {
        dirty = true;
    }

    static void sync(Context context) {
        if (context == null || activityOpen) return;
        Context c = context.getApplicationContext();
        // The accessibility service is direct-boot aware and starts before the
        // user unlocks; credential-encrypted prefs throw until then. The 1.2 s
        // sync loop retries, so skipping early is safe.
        android.os.UserManager um =
                (android.os.UserManager) c.getSystemService(Context.USER_SERVICE);
        if (um != null && !um.isUserUnlocked()) return;
        ControllerProfileStore store = new ControllerProfileStore(c);
        String pkg = ForegroundApp.read(c);
        if (pkg != null && pkg.equals(c.getPackageName())) pkg = null;
        String text = pkg == null ? null : store.app(pkg);
        String key = (pkg == null ? "global" : pkg) + "#" + (text == null ? "-" : text);
        if (!dirty && key.equals(lastKey)) return;
        String body = text != null ? text : store.global();
        if (body == null) {
            // Nothing saved yet: leave whatever the file already holds.
            lastKey = key;
            dirty = false;
            return;
        }
        ControllerMapConfig cfg = new ControllerMapConfig();
        cfg.load(ControllerRemapManager.configFile(c));
        cfg.loadProfile(body);
        // An app profile without stick tuning inherits the global stick block,
        // not whatever profile happened to be applied before.
        ControllerMapConfig probe = new ControllerMapConfig();
        probe.loadText(body);
        if (!probe.stickCalibrated) {
            ControllerMapConfig globalSticks = new ControllerMapConfig();
            String g = store.global();
            if (g != null) globalSticks.loadText(g);
            if (globalSticks.stickCalibrated) cfg.copySticksFrom(globalSticks);
        }
        // The master switch is device-wide, so a profile never overrides it.
        cfg.save(ControllerRemapManager.configFile(c),
                new Prefs(c).controllerRemapEnabled(), cfg.capture);
        lastKey = key;
        dirty = false;
    }
}
