package com.ben.pavo;

import android.content.Context;
import android.media.audiofx.Virtualizer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * System-wide stereo widener built on Android's Virtualizer effect. It runs on
 * the global session (0) and on every currently playing media session, because
 * apps such as music players get their own effect chain and bypass session 0.
 */
final class Spatial {
    private static final int MIN_STRENGTH = 0;
    private static final int MAX_STRENGTH = 1000;

    private static Virtualizer virtualizer;
    private static boolean failed;
    private static final Map<Integer, Virtualizer> perSession = new HashMap<>();

    private Spatial() {}

    static synchronized boolean available(Context c) {
        if (virtualizer != null) return true;
        if (failed) return false;
        try {
            virtualizer = new Virtualizer(0, 0);
            return true;
        } catch (Throwable t) {
            failed = true;
            virtualizer = null;
            return false;
        }
    }

    static synchronized boolean enabled(Context c) {
        return new Prefs(c).spatialEnabled();
    }

    static synchronized int strength(Context c) {
        return new Prefs(c).spatialStrength();
    }

    static int minStrength() {
        return MIN_STRENGTH;
    }

    static int maxStrength() {
        return MAX_STRENGTH;
    }

    static synchronized void setEnabled(Context c, boolean on) {
        new Prefs(c).setSpatialEnabled(on);
        // Rebuild the per-session effects: a chain created before the track
        // settles can be left disabled by the HAL, while a fresh one enables.
        for (Virtualizer effect : perSession.values()) {
            try {
                effect.release();
            } catch (Throwable ignored) {
            }
        }
        perSession.clear();
        refreshSessions(c, SessionFx.activeMediaSessions());
        apply(c);
    }

    static synchronized void setStrength(Context c, int value) {
        new Prefs(c).setSpatialStrength(value);
        apply(c);
    }

    /** Create effects for newly started media sessions and drop stale ones. */
    static synchronized void refreshSessions(Context c, int[] sessions) {
        Set<Integer> active = new HashSet<>();
        for (int s : sessions) {
            if (s > 0) active.add(s);
        }
        Iterator<Map.Entry<Integer, Virtualizer>> it = perSession.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Virtualizer> e = it.next();
            if (active.contains(e.getKey())) continue;
            try {
                e.getValue().release();
            } catch (Throwable ignored) {
            }
            it.remove();
        }
        Prefs p = new Prefs(c);
        if (!p.spatialEnabled()) {
            for (Virtualizer effect : perSession.values()) {
                try {
                    effect.release();
                } catch (Throwable ignored) {
                }
            }
            perSession.clear();
            return;
        }
        for (int session : active) {
            if (perSession.containsKey(session)) continue;
            try {
                // The HAL proxy sometimes leaves the first instance disabled
                // until the chain is rebuilt; a fresh second instance enables.
                Virtualizer first = new Virtualizer(0, session);
                try {
                    first.release();
                } catch (Throwable ignored) {
                }
                Virtualizer effect = new Virtualizer(0, session);
                applyTo(effect, p);
                effect.setEnabled(p.spatialEnabled());
                perSession.put(session, effect);
            } catch (Throwable ignored) {
            }
        }
        // A freshly created session effect can be left disabled until its
        // chain is ready; re-apply on every poll so it settles enabled.
        apply(c);
    }

    static synchronized void apply(Context c) {
        Prefs p = new Prefs(c);
        if (available(c)) {
            try {
                applyTo(virtualizer, p);
                virtualizer.setEnabled(p.spatialEnabled());
            } catch (Throwable ignored) {
            }
        }
        for (Virtualizer effect : perSession.values()) {
            try {
                applyTo(effect, p);
                effect.setEnabled(p.spatialEnabled());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyTo(Virtualizer effect, Prefs p) {
        int value = Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, p.spatialStrength()));
        try {
            if (effect.getStrengthSupported()) {
                effect.setStrength((short) value);
            }
        } catch (Throwable ignored) {
        }
        try {
            effect.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_TRANSAURAL);
        } catch (Throwable ignored) {
        }
    }
}
