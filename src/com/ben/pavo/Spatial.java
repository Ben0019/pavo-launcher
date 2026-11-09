package com.ben.pavo;

import android.content.Context;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * System-wide spatial effect. It uses a Virtualizer for headphones, which the
 * Odin HAL refuses on the built-in speakers, and a short reverb for the
 * speakers, where the HAL does accept it. Both run on the playing app's own
 * session as well as the global session.
 */
final class Spatial {
    private static final int MIN_STRENGTH = 0;
    private static final int MAX_STRENGTH = 1000;

    private static Virtualizer virtualizer;
    private static boolean failed;
    private static final Map<Integer, Virtualizer> perSession = new HashMap<>();
    private static final Map<Integer, PresetReverb> perSessionReverb = new HashMap<>();
    private static int lastStrength = -1;
    private static short lastReverbPreset = -1;
    private static boolean lastEnabled;

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
        releaseSessions();
        refreshSessions(c, SessionFx.activeMediaSessions());
        apply(c);
    }

    static synchronized void setStrength(Context c, int value) {
        new Prefs(c).setSpatialStrength(value);
        apply(c);
    }

    private static void releaseSessions() {
        for (Virtualizer effect : perSession.values()) {
            try {
                effect.release();
            } catch (Throwable ignored) {
            }
        }
        perSession.clear();
        for (PresetReverb effect : perSessionReverb.values()) {
            try {
                effect.release();
            } catch (Throwable ignored) {
            }
        }
        perSessionReverb.clear();
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
        Iterator<Map.Entry<Integer, PresetReverb>> rit = perSessionReverb.entrySet().iterator();
        while (rit.hasNext()) {
            Map.Entry<Integer, PresetReverb> e = rit.next();
            if (active.contains(e.getKey())) continue;
            try {
                e.getValue().release();
            } catch (Throwable ignored) {
            }
            rit.remove();
        }
        Prefs p = new Prefs(c);
        if (!p.spatialEnabled()) {
            releaseSessions();
            return;
        }
        for (int session : active) {
            if (!perSession.containsKey(session)) {
                try {
                    // The HAL proxy sometimes leaves the first instance
                    // disabled until the chain is rebuilt; recreate once.
                    Virtualizer first = new Virtualizer(0, session);
                    try {
                        first.release();
                    } catch (Throwable ignored) {
                    }
                    Virtualizer effect = new Virtualizer(0, session);
                    applyVirtualizer(effect, p);
                    effect.setEnabled(true);
                    perSession.put(session, effect);
                } catch (Throwable ignored) {
                }
            }
            if (!perSessionReverb.containsKey(session)) {
                try {
                    PresetReverb first = new PresetReverb(0, session);
                    try {
                        first.release();
                    } catch (Throwable ignored) {
                    }
                    PresetReverb reverb = new PresetReverb(0, session);
                    applyReverb(reverb, p);
                    reverb.setEnabled(true);
                    perSessionReverb.put(session, reverb);
                } catch (Throwable ignored) {
                }
            }
        }
        // A freshly created session effect can be left disabled until its
        // chain is ready; re-apply on every poll so it settles enabled.
        apply(c);
    }

    static synchronized void apply(Context c) {
        Prefs p = new Prefs(c);
        boolean on = p.spatialEnabled();
        int value = Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, p.spatialStrength()));
        short preset = reverbPreset(value);

        if (available(c)) {
            try {
                if (virtualizer.getStrengthSupported()) {
                    virtualizer.setStrength((short) value);
                }
            } catch (Throwable ignored) {
            }
        }
        if (on != lastEnabled) {
            if (available(c)) {
                try {
                    virtualizer.setEnabled(on);
                } catch (Throwable ignored) {
                }
            }
            for (Virtualizer effect : perSession.values()) {
                try {
                    effect.setEnabled(on);
                } catch (Throwable ignored) {
                }
            }
            for (PresetReverb effect : perSessionReverb.values()) {
                try {
                    effect.setEnabled(on);
                } catch (Throwable ignored) {
                }
            }
            lastEnabled = on;
        }
        if (value != lastStrength) {
            for (Virtualizer effect : perSession.values()) {
                try {
                    if (effect.getStrengthSupported()) {
                        effect.setStrength((short) value);
                    }
                } catch (Throwable ignored) {
                }
            }
            lastStrength = value;
        }
        if (preset != lastReverbPreset) {
            for (PresetReverb effect : perSessionReverb.values()) {
                try {
                    effect.setPreset(preset);
                } catch (Throwable ignored) {
                }
            }
            lastReverbPreset = preset;
        }
    }

    private static void applyVirtualizer(Virtualizer effect, Prefs p) {
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

    private static void applyReverb(PresetReverb effect, Prefs p) {
        try {
            effect.setPreset(reverbPreset(p.spatialStrength()));
        } catch (Throwable ignored) {
        }
    }

    private static short reverbPreset(int strength) {
        if (strength < 340) return PresetReverb.PRESET_SMALLROOM;
        if (strength < 670) return PresetReverb.PRESET_MEDIUMROOM;
        return PresetReverb.PRESET_LARGEROOM;
    }
}
