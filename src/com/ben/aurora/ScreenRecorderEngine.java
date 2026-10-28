package com.ben.aurora;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MediaProjection -> MediaCodec -> muxer pipeline.  Video and audio are encoded
 * independently and joined by the sink; this keeps internal audio capture
 * possible without MediaRecorder's microphone-only audio source.
 */
final class ScreenRecorderEngine {
    interface Listener {
        void onInfo(String message);
        void onError(String message);
    }

    private static final String TAG = "AuroraRecorder";
    private static final int SAMPLE_RATE = 48000;
    private static final int AUDIO_FRAMES = 1024;
    private static final long VIDEO_WAIT_US = 10_000L;

    private final Context context;
    private final ScreenRecorderConfig config;
    private final MediaProjection projection;
    private final Listener listener;

    private MediaCodec videoCodec;
    private Surface videoSurface;
    private VirtualDisplay virtualDisplay;
    private MediaCodec audioCodec;
    private AudioRecord deviceRecord;
    private AudioRecord micRecord;
    private Thread videoThread;
    private Thread audioThread;
    private Sink sink;
    private File output;

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile Throwable failure;
    private volatile long firstVideoUs = -1L;
    private volatile boolean audioEnabled;
    private volatile boolean paused;
    private volatile boolean deviceAudioOn;
    private volatile boolean micAudioOn;
    private long audioFrames;
    private boolean audioEos;

    ScreenRecorderEngine(Context context, ScreenRecorderConfig config,
            MediaProjection projection, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.projection = projection;
        this.listener = listener;
        this.deviceAudioOn = config.deviceAudio;
        this.micAudioOn = config.micAudio;
    }

    void setPaused(boolean value) {
        paused = value;
        if (virtualDisplay != null && videoSurface != null) {
            try { virtualDisplay.setSurface(value ? null : videoSurface); }
            catch (Throwable ignored) {}
        }
    }

    boolean isPaused() { return paused; }

    void setDeviceAudioEnabled(boolean enabled) { deviceAudioOn = enabled; }
    void setMicAudioEnabled(boolean enabled) { micAudioOn = enabled; }

    File outputFile() {
        return output;
    }

    void start() throws Exception {
        if (running) return;
        output = createOutputFile();
        prepareVideo();
        prepareAudio();
        sink = config.isAvi()
                ? new AviSink(output, config, audioEnabled)
                : new MuxerSink(output, config, audioEnabled);
        running = true;
        try {
            startAudio();
            videoSurface = videoCodec.createInputSurface();
            try {
                videoSurface.setFrameRate(config.fps,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (Throwable ignored) {}
            videoCodec.start();
            virtualDisplay = projection.createVirtualDisplay("Aurora recorder",
                    config.width, config.height, config.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    videoSurface, null, null);
            startVideoThread();
        } catch (Throwable t) {
            running = false;
            releasePartial();
            throw t instanceof Exception ? (Exception) t
                    : new IOException("Could not start screen capture", t);
        }
    }

    /** Stops capture and finalises the output. Safe to call more than once. */
    File stop() throws Exception {
        if (stopRequested) return output;
        stopRequested = true;
        running = false;

        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        stopRecord(deviceRecord);
        stopRecord(micRecord);

        if (audioThread != null) {
            try { audioThread.join(2500L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (videoCodec != null) {
            try { videoCodec.signalEndOfInputStream(); } catch (Throwable ignored) {}
        }
        if (videoThread != null) {
            try { videoThread.join(5000L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (sink != null) {
                if (audioEnabled) sink.disableAudio();
                sink.finish();
            }
        } catch (Throwable t) {
            if (failure == null) failure = t;
        }

        releasePartial();
        if (failure != null) {
            throw failure instanceof Exception ? (Exception) failure
                    : new IOException("Recording failed", failure);
        }
        if (output == null || !output.isFile() || output.length() == 0L) {
            throw new IOException("The recording file is empty.");
        }
        return output;
    }

    private File createOutputFile() throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        String name = "Aurora_" + stamp + "." + config.extension();
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "Aurora Recordings");
        try {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
            File file = new File(dir, name);
            if (!file.exists() && file.createNewFile()) return file;
        } catch (Throwable ignored) {}
        File fallback = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (fallback == null) fallback = context.getFilesDir();
        File fallbackDir = new File(fallback, "Aurora Recordings");
        if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
            throw new IOException("No writable recording directory.");
        }
        File file = new File(fallbackDir, name);
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Could not create recording file.");
        }
        return file;
    }

    // ------------------------------------------------------------------
    // Video
    // ------------------------------------------------------------------

    private void prepareVideo() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(config.videoMime(),
                config.width, config.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger("max-fps-to-encoder", config.fps);
        format.setInteger("repeat-previous-frame-after", 1000000 / Math.max(1, config.fps));
        if (config.format == ScreenRecorderConfig.FORMAT_MP4_HEVC) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        try {
            videoCodec = MediaCodec.createEncoderByType(config.videoMime());
            videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Throwable t) {
            releaseCodec(videoCodec);
            videoCodec = null;
            throw new IOException("No encoder available for " + config.videoMime(), t);
        }
    }

    private void startVideoThread() {
        videoThread = new Thread(new Runnable() {
            @Override public void run() {
                drainVideo();
            }
        }, "aurora-video-encode");
        videoThread.start();
    }

    private void drainVideo() {
        if (videoCodec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;
        long emptyReads = 0L;
        while (!eos) {
            try {
                int index = videoCodec.dequeueOutputBuffer(info, VIDEO_WAIT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.setVideoFormat(videoCodec.getOutputFormat());
                    emptyReads = 0L;
                } else if (index >= 0) {
                    emptyReads = 0L;
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        long first = firstVideoUs;
                        if (first < 0) {
                            first = info.presentationTimeUs;
                            firstVideoUs = first;
                        }
                        long pts = Math.max(0L, info.presentationTimeUs - first);
                        ByteBuffer buffer = videoCodec.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            MediaCodec.BufferInfo sample = new MediaCodec.BufferInfo();
                            sample.set(0, info.size, pts, info.flags);
                            try {
                                sink.writeVideo(buffer, sample);
                            } catch (Throwable t) {
                                Log.e(TAG, "sink video write failed", t);
                                throw t;
                            }
                        }
                    }
                    eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    videoCodec.releaseOutputBuffer(index, false);
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    emptyReads++;
                    if (!running && emptyReads > 300L) break;
                }
            } catch (Throwable t) {
                failure = t;
                running = false;
                Log.e(TAG, "video encoder failed", t);
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private void prepareAudio() {
        boolean wantDevice = config.deviceAudio;
        boolean wantMic = config.micAudio;
        if (!wantDevice && !wantMic) return;

        if (wantDevice) {
            try {
                deviceRecord = buildPlaybackRecord();
            } catch (Throwable t) {
                Log.w(TAG, "device audio unavailable", t);
                info("Device audio is unavailable for this capture.");
            }
        }
        if (wantMic) {
            try {
                micRecord = buildMicRecord();
            } catch (Throwable t) {
                Log.w(TAG, "microphone unavailable", t);
                info("Microphone audio is unavailable.");
            }
        }
        if (deviceRecord == null && micRecord == null) {
            audioEnabled = false;
            return;
        }
        if (!config.isAvi()) {
            try {
                MediaFormat format = MediaFormat.createAudioFormat(config.audioMime(),
                        SAMPLE_RATE, 2);
                format.setInteger(MediaFormat.KEY_BIT_RATE,
                        config.isWebm() ? 160000 : 192000);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
                if (!config.isWebm()) {
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                }
                audioCodec = MediaCodec.createEncoderByType(config.audioMime());
                audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable t) {
                releaseCodec(audioCodec);
                audioCodec = null;
                releaseRecord(deviceRecord);
                releaseRecord(micRecord);
                deviceRecord = null;
                micRecord = null;
                audioEnabled = false;
                info("Audio encoder unavailable; recording video only.");
                return;
            }
        }
        audioEnabled = true;
    }

    private AudioRecord buildPlaybackRecord() {
        AudioPlaybackCaptureConfiguration capture =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, AUDIO_FRAMES * 8))
                .setAudioPlaybackCaptureConfig(capture)
                .build();
    }

    private AudioRecord buildMicRecord() {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, AUDIO_FRAMES * 4))
                .build();
    }

    private void startAudio() {
        if (!audioEnabled) return;
        try {
            if (deviceRecord != null) {
                if (deviceRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    releaseRecord(deviceRecord);
                    deviceRecord = null;
                } else {
                    deviceRecord.startRecording();
                }
            }
            if (micRecord != null) {
                if (micRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    releaseRecord(micRecord);
                    micRecord = null;
                } else {
                    micRecord.startRecording();
                }
            }
            if (deviceRecord == null && micRecord == null) {
                audioEnabled = false;
                if (sink != null) sink.disableAudio();
                return;
            }
            if (audioCodec != null) audioCodec.start();
        } catch (Throwable t) {
            Log.w(TAG, "audio start failed", t);
            releaseRecord(deviceRecord);
            releaseRecord(micRecord);
            deviceRecord = null;
            micRecord = null;
            audioEnabled = false;
            if (sink != null) sink.disableAudio();
            info("Audio capture could not start; recording video only.");
            return;
        }
        audioThread = new Thread(new Runnable() {
            @Override public void run() {
                drainAudio();
            }
        }, "aurora-audio-encode");
        audioThread.start();
    }

    private void drainAudio() {
        short[] deviceData = deviceRecord == null ? null : new short[AUDIO_FRAMES * 2];
        short[] micData = micRecord == null ? null : new short[AUDIO_FRAMES];
        short[] mixed = new short[AUDIO_FRAMES * 2];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            if (paused) {
                try {
                    if (deviceRecord != null) deviceRecord.read(deviceData, 0, deviceData.length);
                    if (micRecord != null) micRecord.read(micData, 0, micData.length);
                } catch (Throwable ignored) {}
                try { Thread.sleep(8L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (!deviceAudioOn && !micAudioOn) {
                try { Thread.sleep(8L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            int frames;
            try {
                frames = readAudio(deviceData, micData, mixed);
            } catch (Throwable t) {
                failure = t;
                running = false;
                break;
            }
            if (frames <= 0) {
                try { Thread.sleep(4L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            long pts = audioFrames * 1000000L / SAMPLE_RATE;
            try {
                if (config.isAvi()) {
                    sink.writePcm(mixed, frames, pts);
                } else {
                    encodeAudio(mixed, frames, pts);
                    drainAudioEncoder(info, false);
                }
            } catch (Throwable t) {
                failure = t;
                running = false;
                break;
            }
            audioFrames += frames;
        }

        stopRecord(deviceRecord);
        stopRecord(micRecord);
        if (audioCodec != null) {
            try {
                long pts = audioFrames * 1000000L / SAMPLE_RATE;
                feedAudioEos(pts);
                drainAudioEncoder(info, true);
            } catch (Throwable t) {
                if (failure == null) failure = t;
            }
        }
    }

    private int readAudio(short[] deviceData, short[] micData, short[] mixed) {
        boolean useDevice = deviceAudioOn && deviceData != null;
        boolean useMic = micAudioOn && micData != null;
        if (!useDevice && !useMic) return 0;
        int frames = AUDIO_FRAMES;
        if (useDevice) {
            int got = deviceRecord.read(deviceData, 0, deviceData.length);
            frames = got > 1 ? got / 2 : 0;
        }
        if (useMic) {
            int got = micRecord.read(micData, 0, micData.length);
            int micFrames = Math.max(0, got);
            frames = useDevice ? Math.min(frames, micFrames) : micFrames;
        }
        if (frames <= 0) return 0;
        if (frames > AUDIO_FRAMES) frames = AUDIO_FRAMES;
        for (int i = 0; i < frames; i++) {
            int left = 0;
            int right = 0;
            if (useDevice) {
                left = deviceData[i * 2];
                right = deviceData[i * 2 + 1];
            }
            if (useMic) {
                int mic = micData[i];
                left += mic;
                right += mic;
            }
            mixed[i * 2] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, left));
            mixed[i * 2 + 1] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, right));
        }
        return frames;
    }

    private void encodeAudio(short[] pcm, int frames, long ptsUs) throws IOException {
        if (audioCodec == null) return;
        int index = audioCodec.dequeueInputBuffer(10000L);
        if (index < 0) return;
        ByteBuffer input = audioCodec.getInputBuffer(index);
        if (input == null) return;
        input.clear();
        input.order(ByteOrder.LITTLE_ENDIAN);
        int samples = frames * 2;
        for (int i = 0; i < samples; i++) input.putShort(pcm[i]);
        int bytes = samples * 2;
        audioCodec.queueInputBuffer(index, 0, bytes, ptsUs, 0);
    }

    private void feedAudioEos(long ptsUs) {
        if (audioCodec == null) return;
        try {
            int index = audioCodec.dequeueInputBuffer(500000L);
            if (index >= 0) {
                audioCodec.queueInputBuffer(index, 0, 0, ptsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Throwable ignored) {}
    }

    private void drainAudioEncoder(MediaCodec.BufferInfo info, boolean untilEos) {
        if (audioCodec == null) return;
        long deadline = System.nanoTime() + (untilEos ? 5_000_000_000L : 0L);
        while (true) {
            try {
                int index = audioCodec.dequeueOutputBuffer(info, untilEos ? 10000L : 0L);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.setAudioFormat(audioCodec.getOutputFormat());
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            ByteBuffer buffer = audioCodec.getOutputBuffer(index);
                            if (buffer != null) {
                                buffer.position(info.offset);
                                buffer.limit(info.offset + info.size);
                                sink.writeAudio(buffer, info);
                            }
                        }
                    } finally {
                        audioCodec.releaseOutputBuffer(index, false);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                } else if (!untilEos) {
                    return;
                }
            } catch (Throwable t) {
                if (failure == null) failure = t;
                return;
            }
            if (untilEos && System.nanoTime() > deadline) return;
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private void releasePartial() {
        if (videoSurface != null) {
            try { videoSurface.release(); } catch (Throwable ignored) {}
            videoSurface = null;
        }
        releaseCodec(videoCodec);
        videoCodec = null;
        releaseCodec(audioCodec);
        audioCodec = null;
        releaseRecord(deviceRecord);
        deviceRecord = null;
        releaseRecord(micRecord);
        micRecord = null;
        if (sink != null) {
            try { sink.release(); } catch (Throwable ignored) {}
        }
    }

    private static void releaseCodec(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop(); } catch (Throwable ignored) {}
        try { codec.release(); } catch (Throwable ignored) {}
    }

    private static void releaseRecord(AudioRecord record) {
        if (record == null) return;
        try { record.release(); } catch (Throwable ignored) {}
    }

    private static void stopRecord(AudioRecord record) {
        if (record == null) return;
        try { record.stop(); } catch (Throwable ignored) {}
    }

    private void info(String message) {
        if (listener != null) listener.onInfo(message);
    }

    // ------------------------------------------------------------------
    // Sinks
    // ------------------------------------------------------------------

    private interface Sink {
        void setVideoFormat(MediaFormat format) throws IOException;
        void setAudioFormat(MediaFormat format) throws IOException;
        void writeVideo(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException;
        void writeAudio(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException;
        void writePcm(short[] samples, int frames, long ptsUs) throws IOException;
        void disableAudio();
        void finish() throws IOException;
        void release();
    }

    private static final class MuxerSink implements Sink {
        private final MediaMuxer muxer;
        private final boolean ownsAudio;
        private MediaFormat videoFormat;
        private MediaFormat audioFormat;
        private boolean audioExpected;
        private boolean started;
        private boolean released;
        private int videoTrack = -1;
        private int audioTrack = -1;

        MuxerSink(File file, ScreenRecorderConfig config, boolean audio) throws IOException {
            muxer = new MediaMuxer(file.getAbsolutePath(), config.muxerOutputFormat());
            ownsAudio = audio;
            audioExpected = audio;
        }

        @Override public synchronized void setVideoFormat(MediaFormat format) {
            videoFormat = format;
            startIfReady();
        }

        @Override public synchronized void setAudioFormat(MediaFormat format) {
            audioFormat = format;
            startIfReady();
        }

        private void startIfReady() {
            if (started || videoFormat == null) return;
            if (audioExpected && audioFormat == null) return;
            videoTrack = muxer.addTrack(videoFormat);
            if (audioExpected && audioFormat != null) {
                audioTrack = muxer.addTrack(audioFormat);
            }
            muxer.start();
            started = true;
            notifyAll();
        }

        @Override public synchronized void disableAudio() {
            audioExpected = false;
            audioFormat = null;
            startIfReady();
        }

        @Override public synchronized void writeVideo(ByteBuffer data,
                MediaCodec.BufferInfo info) throws IOException {
            awaitStarted();
            if (videoTrack < 0) return;
            muxer.writeSampleData(videoTrack, data, info);
        }

        @Override public synchronized void writeAudio(ByteBuffer data,
                MediaCodec.BufferInfo info) throws IOException {
            if (!ownsAudio) return;
            awaitStarted();
            if (audioTrack < 0) return;
            muxer.writeSampleData(audioTrack, data, info);
        }

        @Override public void writePcm(short[] samples, int frames, long ptsUs) {
            // PCM is only used by the AVI sink.
        }

        private void awaitStarted() throws IOException {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!started) {
                if (!audioExpected) {
                    startIfReady();
                    if (started) return;
                }
                if (System.nanoTime() > deadline) {
                    // An audio capture source that never produced samples must
                    // not prevent the video track from being written.
                    disableAudio();
                    startIfReady();
                    if (!started) throw new IOException("Muxer did not start.");
                    return;
                }
                try {
                    wait(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while starting muxer.", e);
                }
            }
        }

        @Override public synchronized void finish() throws IOException {
            if (started && !released) {
                try { muxer.stop(); } catch (Throwable t) {
                    throw new IOException("Could not finish recording.", t);
                }
            }
            release();
        }

        @Override public synchronized void release() {
            if (released) return;
            released = true;
            try { muxer.release(); } catch (Throwable ignored) {}
        }
    }

    private static final class AviSink implements Sink {
        private final AviWriter writer;

        AviSink(File file, ScreenRecorderConfig config, boolean audio) throws IOException {
            writer = new AviWriter(file, config.width, config.height, config.fps,
                    audio, SAMPLE_RATE, 2);
        }

        @Override public void setVideoFormat(MediaFormat format) {
            writer.setCodecConfig(format);
        }

        @Override public void setAudioFormat(MediaFormat format) {
        }

        @Override public void writeVideo(ByteBuffer data, MediaCodec.BufferInfo info)
                throws IOException {
            writer.writeVideo(data, info.presentationTimeUs,
                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
        }

        @Override public void writeAudio(ByteBuffer data, MediaCodec.BufferInfo info) {
        }

        @Override public void writePcm(short[] samples, int frames, long ptsUs)
                throws IOException {
            writer.writePcm(samples, frames);
        }

        @Override public void disableAudio() {
        }

        @Override public void finish() throws IOException {
            writer.finish();
        }

        @Override public void release() {
            writer.release();
        }
    }
}
