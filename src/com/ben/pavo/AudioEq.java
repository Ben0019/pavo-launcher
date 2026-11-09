package com.ben.pavo;

import android.content.Context;
import android.media.audiofx.DynamicsProcessing;

/**
 * System-wide five-band equalizer built on Android's DynamicsProcessing
 * effect. The legacy Equalizer effect is deprecated for the global (session 0)
 * mix, and the Odin audio HAL keeps that old chain suspended until a track is
 * rebuilt. DynamicsProcessing is the API used by rootless system EQ apps and
 * applies to the live output mix.
 */
final class AudioEq {
    private static final float[] FREQ_HZ = {60f, 230f, 910f, 3600f, 14000f};
    private static final int BANDS = FREQ_HZ.length;
    private static final float MAX_GAIN_DB = 12f;
    private static final int CHANNELS = 2;

    private static DynamicsProcessing dp;
    private static boolean failed;

    private AudioEq() {}

    static synchronized boolean available(Context c) {
        if (dp != null) return true;
        if (failed) return false;
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    CHANNELS,
                    true, BANDS,      // pre-EQ
                    false, 0,         // multiband compressor
                    false, 0,         // post-EQ
                    false             // limiter
            ).build();
            for (int ch = 0; ch < CHANNELS; ch++) {
                DynamicsProcessing.Eq eq = config.getPreEqByChannelIndex(ch);
                for (int i = 0; i < BANDS; i++) {
                    eq.setBand(i, new DynamicsProcessing.EqBand(true, FREQ_HZ[i], 0f));
                }
                config.setPreEqByChannelIndex(ch, eq);
            }
            dp = new DynamicsProcessing(-1, 0, config);
            return true;
        } catch (Throwable t) {
            failed = true;
            dp = null;
            return false;
        }
    }

    static synchronized int bandCount(Context c) {
        available(c);
        return BANDS;
    }

    static synchronized int minLevel(Context c) {
        return Math.round(-MAX_GAIN_DB * 100f);
    }

    static synchronized int maxLevel(Context c) {
        return Math.round(MAX_GAIN_DB * 100f);
    }

    static synchronized int centerHz(Context c, int band) {
        if (band < 0 || band >= BANDS) return 0;
        return Math.round(FREQ_HZ[band]);
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
            dp.setEnabled(p.eqEnabled());
            for (int i = 0; i < BANDS; i++) {
                float gainDb = p.eqBand(i) / 100f;
                gainDb = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
                dp.setPreEqBandAllChannelsTo(i,
                        new DynamicsProcessing.EqBand(true, FREQ_HZ[i], gainDb));
            }
        } catch (Throwable ignored) {
        }
    }
}
