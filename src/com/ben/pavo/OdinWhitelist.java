package com.ben.pavo;

import android.content.Context;
import android.provider.Settings;

/**
 * The Odin/RedMagic "Whitelist application" screen edits two system settings:
 * app_whiteList and display_app_whiteList. PServer can write them as the
 * privileged service, so setup can keep Pavo's overlay and background services
 * alive without sending the user through the OEM settings app.
 */
final class OdinWhitelist {
    static final String KEY = "app_whiteList";
    static final String DISPLAY_KEY = "display_app_whiteList";

    private OdinWhitelist() {}

    static boolean isWhitelisted(Context c) {
        if (c == null) return false;
        String list = Settings.System.getString(c.getContentResolver(), KEY);
        return contains(list, c.getPackageName());
    }

    /**
     * Adds the app to both lists if needed. Call on a worker thread: it waits
     * for PServer to run the shell snippet.
     */
    static boolean ensure(Context c) {
        if (c == null) return false;
        if (isWhitelisted(c)) return true;
        if (!PServer.available()) return false;
        String pkg = c.getPackageName();
        String cmd = "for k in " + KEY + " " + DISPLAY_KEY + "; do "
                + "v=$(settings get system $k); "
                + "case \",$v,\" in *\"," + pkg + ",\"*) ;; *) "
                + "if [ -z \"$v\" ] || [ \"$v\" = null ]; then "
                + "settings put system $k " + pkg + "; "
                + "else settings put system $k \"$v," + pkg + "\"; fi;; esac; done";
        PServer.exec(cmd);
        return isWhitelisted(c);
    }

    private static boolean contains(String list, String pkg) {
        if (list == null || pkg == null) return false;
        for (String part : list.split(",")) {
            if (pkg.equals(part.trim())) return true;
        }
        return false;
    }
}
