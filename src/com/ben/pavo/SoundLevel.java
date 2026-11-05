package com.ben.pavo;

import android.media.audiofx.Visualizer;
import android.os.SystemClock;

/**
 * Smoothed level of the device's audio output, used to pulse the joystick
 * brightness with music and game sound. The Visualizer is attached to the
 * global output mix (session 0).
 */
final class SoundLevel {
    private static final long ATTACK_MS = 35L;
    private static final long RELEASE_MS = 200L;

    private volatile float level;
    private volatile float fftLevel;
    private volatile long levelAt;
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
                    for (byte b : waveform) {
                        int sample = (b & 0xFF) - 128;
                        sum += (float) sample * sample;
                    }
                    float rms = (float) Math.sqrt(sum / waveform.length) / 128f;
                    // Leave headroom so loud passages do not clip the pulse.
                    float target = Math.max(Math.min(1f, rms * 1.8f),
                            Math.min(1f, fftLevel * 0.9f));
                    long now = SystemClock.uptimeMillis();
                    long dt = levelAt == 0L ? 20L : now - levelAt;
                    levelAt = now;
                    if (dt < 1L) dt = 1L;
                    if (dt > 120L) dt = 120L;
                    float tau = target > level ? ATTACK_MS : RELEASE_MS;
                    float alpha = 1f - (float) Math.exp(-dt / (double) tau);
                    level += (target - level) * alpha;
                }

                @Override
                public void onFftDataCapture(Visualizer source, byte[] fft, int samplingRate) {
                    if (fft == null || fft.length < 6) return;
                    // Low bins carry the kick/bass movement that a plain RMS
                    // envelope misses on smooth music.
                    float energy = 0f;
                    int bins = 0;
                    int limit = Math.min(fft.length, 20);
                    for (int i = 2; i + 1 < limit; i += 2) {
                        float re = fft[i];
                        float im = fft[i + 1];
                        energy += (float) Math.sqrt(re * re + im * im);
                        bins++;
                    }
                    if (bins == 0) return;
                    fftLevel = Math.min(1f, (energy / (bins * 180f)) * 2.2f);
                }
            }, Visualizer.getMaxCaptureRate(), true, true);
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
        fftLevel = 0f;
        levelAt = 0L;
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
