package com.ben.pavo;

import android.media.audiofx.Visualizer;

/**
 * Smoothed level of the device's audio output, used to pulse the joystick
 * brightness with music and game sound. The Visualizer is attached to the
 * global output mix (session 0), so it follows whatever is playing.
 */
final class SoundLevel {
    private static final String TAG = "PavoSound";
    private static final float RELEASE = 0.55f;

    private volatile float level;
    private Visualizer visualizer;
    private boolean running;

    synchronized boolean start() {
        if (running) return true;
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = range != null && range.length > 1 ? range[1] : 1024;
            if (size > 1024) size = 1024;
            v.setCaptureSize(size);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer source, byte[] waveform,
                                                  int samplingRate) {
                    if (waveform == null || waveform.length == 0) return;
                    float sum = 0f;
                    int peak = 0;
                    for (byte b : waveform) {
                        int sample = (b & 0xFF) - 128;
                        sum += (float) sample * sample;
                        int magnitude = Math.abs(sample);
                        if (magnitude > peak) peak = magnitude;
                    }
                    float rms = (float) Math.sqrt(sum / waveform.length) / 128f;
                    float target = Math.min(1f, rms * 2.8f + (peak / 128f) * 0.20f);
                    // Fast attack, slow release so the pulse reads like a VU meter.
                    float next = target > level ? target : level * RELEASE;
                    level = Math.max(0f, Math.min(1f, next));
                }

                @Override
                public void onFftDataCapture(Visualizer source, byte[] fft, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate(), true, false);
            if (v.setEnabled(true) != Visualizer.SUCCESS) {
                v.release();
                return false;
            }
            visualizer = v;
            running = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    synchronized void stop() {
        running = false;
        level = 0f;
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                visualizer.release();
            } catch (Throwable ignored) {
            }
            visualizer = null;
        }
    }

    float level() {
        return level;
    }
}
