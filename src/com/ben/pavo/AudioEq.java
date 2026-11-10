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
    /** Standard ten-band graphic equalizer. */
    private static final float[] FREQ_HZ = {
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f};
    /** Frequencies used by the original five-band build, for migration. */
    static final int[] OLD_FREQ_HZ = {60, 230, 910, 3600, 14000};
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

    /** Expands a five-band preset/curve onto the ten-band frequency grid. */
    static int[] expandFive(int[] five) {
        int[] out = new int[BANDS];
        if (five == null || five.length == 0) return out;
        for (int i = 0; i < BANDS; i++) {
            float f = FREQ_HZ[i];
            if (f <= OLD_FREQ_HZ[0]) {
                out[i] = five[0];
                continue;
            }
            if (f >= OLD_FREQ_HZ[OLD_FREQ_HZ.length - 1]) {
                out[i] = five[five.length - 1];
                continue;
            }
            int lo = 0;
            while (lo < OLD_FREQ_HZ.length - 2 && f > OLD_FREQ_HZ[lo + 1]) lo++;
            float a = (float) Math.log(OLD_FREQ_HZ[lo]);
            float b = (float) Math.log(OLD_FREQ_HZ[lo + 1]);
            float t = ((float) Math.log(f) - a) / Math.max(0.0001f, b - a);
            t = Math.max(0f, Math.min(1f, t));
            out[i] = Math.round(five[lo] + (five[lo + 1] - five[lo]) * t);
        }
        return out;
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
        if (c != null) new Prefs(c).migrateEqBands();
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
