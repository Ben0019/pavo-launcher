package com.ben.pavo;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Accessibility opt-in helpers. Android intentionally blocks apps from
 * enabling an accessibility service themselves; on this rooted device the
 * vendor PServer can write the secure setting directly, which also sidesteps
 * the "restricted settings" gate for sideloaded builds.
 */
final class A11ySetup {
    private A11ySetup() {}

    static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            String full = context.getPackageName() + "/"
                    + PavoA11yService.class.getName();
            return enabled.contains(full) || enabled.contains(
                    context.getPackageName() + "/.PavoA11yService");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Append Pavo to the enabled list and turn accessibility on, via PServer. */
    static boolean rootEnable(Context context) {
        if (context == null || !PServer.available()) return false;
        String component = context.getPackageName() + "/"
                + PavoA11yService.class.getName();
        String script = "cur=$(settings get secure enabled_accessibility_services)\n"
                + "case \":$cur:\" in *\":$COMPONENT:\"*) ;; *) "
                + "if [ -z \"$cur\" ] || [ \"$cur\" = \"null\" ]; then "
                + "settings put secure enabled_accessibility_services \"$COMPONENT\"; "
                + "else settings put secure enabled_accessibility_services "
                + "\"$cur:$COMPONENT\"; fi ;; esac\n"
                + "settings put secure accessibility_enabled 1\n";
        script = script.replace("$COMPONENT", component);
        try {
            PServer.exec(script, 2500L);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
