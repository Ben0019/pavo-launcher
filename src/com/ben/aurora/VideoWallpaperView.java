package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import java.io.File;

/**
 * Looping, muted video wallpaper rendered inside the launcher. Unlike a system
 * live wallpaper this stays under Aurora's own icons and glass and does not
 * depend on a transparent home window.
 */
final class VideoWallpaperView extends ViewGroup {
    interface Listener {
        void onVideoAccent(int color);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextureView texture;
    private MediaPlayer player;
    private Surface surface;
    private String path;
    private String preparedPath;
    private boolean prepared;
    private boolean paused;
    private boolean visible = true;
    private int videoWidth;
    private int videoHeight;
    private Listener listener;
    private int accent;
    private int token;

    VideoWallpaperView(Context context) {
        super(context);
        setBackgroundColor(0xFF000000);
        texture = new TextureView(context);
        texture.setOpaque(true);
        addView(texture, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                prepare();
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyTransform();
            }

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                releasePlayer();
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    int accent() {
        return accent;
    }

    void setVideo(String path) {
        if (path == null) path = "";
        if (path.equals(this.path) && player != null) {
            if (!paused) play();
            return;
        }
        this.path = path;
        prepared = false;
        preparedPath = null;
        releasePlayer();
        if (!path.isEmpty() && surface != null) prepare();
    }

    void play() {
        paused = false;
        visible = true;
        if (player != null && prepared && !player.isPlaying()) {
            try {
                player.start();
            } catch (Throwable ignored) {
            }
        } else if (player == null && !path.isEmpty() && surface != null) {
            prepare();
        }
    }

    void pause() {
        paused = true;
        if (player != null && prepared) {
            try {
                player.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    void stop() {
        releasePlayer();
        prepared = false;
        preparedPath = null;
    }

    void release() {
        releasePlayer();
        path = null;
        prepared = false;
        preparedPath = null;
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void prepare() {
        if (path == null || path.isEmpty() || surface == null) return;
        final String source = path;
        final int generation = ++token;
        releasePlayer();
        try {
            MediaPlayer media = new MediaPlayer();
            media.setDataSource(source);
            media.setSurface(surface);
            media.setLooping(true);
            media.setVolume(0f, 0f);
            media.setOnPreparedListener(mp -> {
                if (generation != token) return;
                prepared = true;
                preparedPath = source;
                applyTransform();
                if (!paused && visible) {
                    try {
                        mp.start();
                    } catch (Throwable ignored) {
                    }
                }
            });
            media.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (generation != token) return;
                videoWidth = width;
                videoHeight = height;
                applyTransform();
            });
            media.setOnErrorListener((mp, what, extra) -> {
                if (generation == token) {
                    prepared = false;
                    releasePlayer();
                }
                return true;
            });
            player = media;
            media.prepareAsync();
            loadAccent(source, generation);
        } catch (Throwable t) {
            releasePlayer();
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private void applyTransform() {
        if (texture == null) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float viewAspect = w / (float) h;
        float videoAspect = videoWidth / (float) videoHeight;
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect;
        } else if (videoAspect < viewAspect) {
            scaleY = viewAspect / videoAspect;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, w / 2f, h / 2f);
        texture.setTransform(matrix);
    }

    private void loadAccent(String source, int generation) {
        new Thread(() -> {
            int colour = 0;
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(source);
                Bitmap frame = retriever.getScaledFrameAtTime(1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
                if (frame != null) {
                    colour = AccentColor.fromBitmap(frame);
                    frame.recycle();
                }
            } catch (Throwable ignored) {
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (colour == 0) return;
            final int value = colour;
            main.post(() -> {
                if (generation != token) return;
                accent = value;
                ThemeColors.set(value);
                if (listener != null) listener.onVideoAccent(value);
            });
        }, "aurora-video-accent").start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTransform();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(0, 0, w, h);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        visible = false;
        pause();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        visible = true;
    }

    String preparedPath() {
        return preparedPath;
    }

    boolean isPrepared() {
        return prepared;
    }
}
