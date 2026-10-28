package com.ben.aurora;

import android.content.Intent;
import android.os.Build;

/** Immutable settings for one screen-recording session. */
final class ScreenRecorderConfig {
    static final int MODE_FULLSCREEN = 0;
    static final int MODE_SINGLE_APP = 1;

    static final int QUALITY_EFFICIENT = 0;
    static final int QUALITY_BALANCED = 1;
    static final int QUALITY_HIGH = 2;

    static final int RES_480 = 0;
    static final int RES_720 = 1;
    static final int RES_1080 = 2;
    static final int RES_NATIVE = 3;

    static final int FORMAT_MP4_AVC = 0;
    static final int FORMAT_MP4_HEVC = 1;
    static final int FORMAT_WEBM_VP9 = 2;
    static final int FORMAT_AVI_AVC = 3;

    private static final String EXTRA_MODE = "sr_mode";
    private static final String EXTRA_QUALITY = "sr_quality";
    private static final String EXTRA_RES = "sr_res";
    private static final String EXTRA_FORMAT = "sr_format";
    private static final String EXTRA_FPS = "sr_fps";
    private static final String EXTRA_BITRATE = "sr_bitrate";
    private static final String EXTRA_DEVICE_AUDIO = "sr_device_audio";
    private static final String EXTRA_MIC_AUDIO = "sr_mic_audio";
    private static final String EXTRA_TOUCHES = "sr_touches";
    private static final String EXTRA_WIDTH = "sr_width";
    private static final String EXTRA_HEIGHT = "sr_height";
    private static final String EXTRA_DENSITY = "sr_density";

    final int mode;
    final int quality;
    final int resolution;
    final int format;
    final int fps;
    final int bitrateKbps;
    final boolean deviceAudio;
    final boolean micAudio;
    final boolean showTouches;
    final int width;
    final int height;
    final int densityDpi;

    ScreenRecorderConfig(int mode, int quality, int resolution, int format, int fps,
            int bitrateKbps, boolean deviceAudio, boolean micAudio, boolean showTouches,
            int width, int height, int densityDpi) {
        this.mode = mode;
        this.quality = quality;
        this.resolution = resolution;
        this.format = format;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.deviceAudio = deviceAudio;
        this.micAudio = micAudio;
        this.showTouches = showTouches;
        this.width = even(width);
        this.height = even(height);
        this.densityDpi = Math.max(1, densityDpi);
    }

    private static int even(int value) {
        return Math.max(2, value & ~1);
    }

    boolean hasAudio() {
        return deviceAudio || micAudio;
    }

    String videoMime() {
        switch (format) {
            case FORMAT_MP4_HEVC: return "video/hevc";
            case FORMAT_WEBM_VP9: return "video/x-vnd.on2.vp9";
            case FORMAT_AVI_AVC:
            case FORMAT_MP4_AVC:
            default: return "video/avc";
        }
    }

    String audioMime() {
        if (format == FORMAT_WEBM_VP9) {
            return Build.VERSION.SDK_INT >= 29 ? "audio/opus" : "audio/vorbis";
        }
        return "audio/mp4a-latm";
    }

    boolean isWebm() {
        return format == FORMAT_WEBM_VP9;
    }

    boolean isAvi() {
        return format == FORMAT_AVI_AVC;
    }

    int muxerOutputFormat() {
        return isWebm() ? android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                : android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    }

    String extension() {
        if (isWebm()) return "webm";
        if (isAvi()) return "avi";
        return "mp4";
    }

    String mimeType() {
        if (isWebm()) return "video/webm";
        if (isAvi()) return "video/x-msvideo";
        return "video/mp4";
    }

    String formatLabel() {
        switch (format) {
            case FORMAT_MP4_HEVC: return "MP4 \u00b7 HEVC";
            case FORMAT_WEBM_VP9: return "WebM \u00b7 VP9";
            case FORMAT_AVI_AVC: return "AVI \u00b7 H.264";
            case FORMAT_MP4_AVC:
            default: return "MP4 \u00b7 H.264";
        }
    }

    void writeTo(Intent intent) {
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra(EXTRA_QUALITY, quality);
        intent.putExtra(EXTRA_RES, resolution);
        intent.putExtra(EXTRA_FORMAT, format);
        intent.putExtra(EXTRA_FPS, fps);
        intent.putExtra(EXTRA_BITRATE, bitrateKbps);
        intent.putExtra(EXTRA_DEVICE_AUDIO, deviceAudio);
        intent.putExtra(EXTRA_MIC_AUDIO, micAudio);
        intent.putExtra(EXTRA_TOUCHES, showTouches);
        intent.putExtra(EXTRA_WIDTH, width);
        intent.putExtra(EXTRA_HEIGHT, height);
        intent.putExtra(EXTRA_DENSITY, densityDpi);
    }

    static ScreenRecorderConfig fromIntent(Intent intent) {
        if (intent == null) return null;
        return new ScreenRecorderConfig(
                intent.getIntExtra(EXTRA_MODE, MODE_FULLSCREEN),
                intent.getIntExtra(EXTRA_QUALITY, QUALITY_BALANCED),
                intent.getIntExtra(EXTRA_RES, RES_1080),
                intent.getIntExtra(EXTRA_FORMAT, FORMAT_MP4_AVC),
                intent.getIntExtra(EXTRA_FPS, 30),
                intent.getIntExtra(EXTRA_BITRATE, 10000),
                intent.getBooleanExtra(EXTRA_DEVICE_AUDIO, true),
                intent.getBooleanExtra(EXTRA_MIC_AUDIO, false),
                intent.getBooleanExtra(EXTRA_TOUCHES, true),
                intent.getIntExtra(EXTRA_WIDTH, 1920),
                intent.getIntExtra(EXTRA_HEIGHT, 1080),
                intent.getIntExtra(EXTRA_DENSITY, 369));
    }
}
