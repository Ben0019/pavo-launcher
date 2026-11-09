package com.ben.pavo;

import android.content.Context;
import android.media.audiofx.Equalizer;

/**
 * Owns the system-wide equalizer on audio session 0. The effect is created in
 * the app process and kept alive by the accessibility service, so it applies to
 * every app rather than only to Pavo's own playback.
 */
final class AudioEq {
    private static Equalizer eq;
    private static boolean failed;
    private static short bands = (short) Prefs.EQ_BANDS;
    private static short[] range = {-1500, 1500};

    private AudioEq() {}

    static synchronized boolean available(Context c) {
        if (eq != null) return true;
        if (failed) return false;
        try {
            eq = new Equalizer(-1, 0);
            bands = eq.getNumberOfBands();
            range = eq.getBandLevelRange();
            return true;
        } catch (Throwable t) {
            failed = true;
            eq = null;
            return false;
        }
    }

    static synchronized int bandCount(Context c) {
        available(c);
        return bands;
    }

    static synchronized int minLevel(Context c) {
        available(c);
        return range[0];
    }

    static synchronized int maxLevel(Context c) {
        available(c);
        return range[1];
    }

    static synchronized int centerHz(Context c, int band) {
        if (!available(c) || band < 0 || band >= bands) return 0;
        try {
            return Math.round(eq.getCenterFreq((short) band) / 1000f);
        } catch (Throwable t) {
            return 0;
        }
    }

    static synchronized boolean enabled(Context c) {
        return new Prefs(c).eqEnabled();
    }

    static synchronized void setEnabled(Context c, boolean on) {
        new Prefs(c).setEqEnabled(on);
        apply(c);
    }

    static synchronized void setBand(Context c, int band, int millibels) {
        new Prefs(c).setEqBand(band, millibels);
        apply(c);
    }

    static synchronized void setBands(Context c, int[] levels) {
        Prefs p = new Prefs(c);
        for (int i = 0; i < Prefs.EQ_BANDS; i++) {
            p.setEqBand(i, i < levels.length ? levels[i] : 0);
        }
        apply(c);
    }

    static synchronized void apply(Context c) {
        if (!available(c)) return;
        Prefs p = new Prefs(c);
        try {
            eq.setEnabled(p.eqEnabled());
            for (int i = 0; i < bands && i < Prefs.EQ_BANDS; i++) {
                int level = Math.max(range[0], Math.min(range[1], p.eqBand(i)));
                eq.setBandLevel((short) i, (short) level);
            }
        } catch (Throwable ignored) {
        }
    }
}
