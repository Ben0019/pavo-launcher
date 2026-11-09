package com.ben.pavo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the audio sessions that are actually playing media. Android redacts
 * other apps' session IDs from the public API, so this asks PServer (root) for
 * the same information the audio dumps use.
 */
final class SessionFx {
    private static final Pattern SESSION = Pattern.compile("sessionId:(\\d+)");

    private SessionFx() {}

    static int[] activeMediaSessions() {
        if (!PServer.available()) return new int[0];
        String out = PServer.exec("dumpsys audio | grep state:started"
                + " | grep -E 'usage=USAGE_MEDIA|usage=USAGE_GAME'"
                + " | grep -oE 'sessionId:[0-9]+'");
        if (out == null || out.isEmpty()) return new int[0];
        List<Integer> ids = new ArrayList<>();
        Matcher m = SESSION.matcher(out);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group(1));
                if (id > 0 && !ids.contains(id)) ids.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
        return result;
    }
}
