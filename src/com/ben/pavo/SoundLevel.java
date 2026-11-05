package com.ben.pavo;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Process;

/**
 * Smoothed level of the device's audio output, used to pulse the joystick
 * brightness with music and game sound. The Visualizer is attached to the
 * global output mix (session 0).
 */
final class SoundLevel {
    private static final float RELEASE = 0.55f;

    private volatile float level;
    private volatile float micLevel;
    private Visualizer visualizer;
    private volatile AudioRecord mic;
    private Thread micThread;
    private volatile boolean running;

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

    synchronized void ensureMic() {
        if (running && micThread == null) startMic();
    }

    synchronized void dropMic() {
        if (mic != null) {
            try { mic.stop(); } catch (Throwable ignored) {}
        }
        mic = null;
        if (micThread != null) {
            final Thread thread = micThread;
            micThread = null;
            try { thread.join(120L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        micLevel = 0f;
    }

    private void startMic() {
        try {
            int min = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return;
            AudioRecord candidate = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
            if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                candidate.release();
                candidate = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
            }
            final AudioRecord record = candidate;
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                return;
            }
            mic = record;
            micThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                    short[] buffer = new short[1024];
                    try {
                        record.startRecording();
                        while (running && mic == record) {
                            int read = record.read(buffer, 0, buffer.length);
                            if (read <= 0) continue;
                            float sum = 0f;
                            for (int i = 0; i < read; i++) {
                                float sample = buffer[i] / 32768f;
                                sum += sample * sample;
                            }
                            float rms = (float) Math.sqrt(sum / read);
                            float target = Math.max(0f,
                                    Math.min(1f, (rms - 0.0045f) * 450f));
                            float next = target > micLevel ? target : micLevel * RELEASE;
                            micLevel = Math.max(0f, Math.min(1f, next));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try {
                            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                record.stop();
                            }
                        } catch (Throwable ignored) {
                        }
                        try { record.release(); } catch (Throwable ignored) {}
                    }
                }
            }, "PavoMicLevel");
            micThread.start();
        } catch (Throwable t) {
            mic = null;
            micThread = null;
        }
    }

    synchronized void stop() {
        running = false;
        micLevel = 0f;
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
        if (mic != null) {
            try { mic.stop(); } catch (Throwable ignored) {}
        }
        mic = null;
        if (micThread != null) {
            try { micThread.join(200L); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            micThread = null;
        }
    }

    float outputLevel() {
        return level;
    }

    float level() {
        return Math.max(level, micLevel);
    }
}
