package com.ben.pavo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Built-in video player using MediaPlayer + TextureView so it composites with the glass card. */
public final class VideoPlayerActivity extends PavoGlassActivity {
    static final String EXTRA_PATH = "video_player_path";

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private ControllerNav controllerNav;
    private File file;
    private AspectTextureView texture;
    private FrameLayout videoFrame;
    private MediaPlayer player;
    private Surface surface;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private final List<File> siblings = new ArrayList<>();
    private int siblingIndex;
    private TextView playButton;
    private TextView timeLabel;
    private SeekBar seekBar;
    private TextView status;
    private boolean prepared;
    private boolean dragging;
    private int videoWidth;
    private int videoHeight;
    private int metadataDurationMs;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && !dragging) {
                int position = player.getCurrentPosition();
                seekBar.setProgress(position);
                timeLabel.setText(formatTime(position) + " / "
                        + formatTime(currentDuration()));
            }
            main.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) { finishAnimated(); return; }
        file = new File(path);
        if (!file.isFile()) { finishAnimated(); return; }
        buildSiblings();
        buildUi();
        hideBars();
        startVideo();
        installController();
    }

    @Override
    protected void onPause() {
        if (controllerNav != null) controllerNav.cancel();
        if (player != null && player.isPlaying()) {
            player.pause();
            if (playButton != null) playButton.setText("\u25b6");
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
    }

    @Override
    protected void onDestroy() {
        if (controllerNav != null) controllerNav.release();
        main.removeCallbacks(progress);
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        super.onDestroy();
    }

    private void installController() {
        controllerNav = new ControllerNav(this, getWindow().getDecorView());
        controllerNav.decorateFocusables();
        if (playButton == null) return;
        playButton.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    playButton.requestFocus();
                    controllerNav.decorateFocusables();
                }
            }
        }, 180L);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BUTTON_B && down && event.getRepeatCount() == 0) {
            finishAnimated();
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1)
                && down && event.getRepeatCount() == 0) {
            showSibling(code == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
            return true;
        }
        if (controllerNav != null && controllerNav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (controllerNav != null && controllerNav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        WallpaperView wallpaper = new WallpaperView(this);
        wallpaper.setBlurRadius(prefs.blur());
        wallpaper.setMode(prefs.wallpaperMode());
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GlassPanel sheet = new GlassPanel(this, null, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        sheet.setBackdropScreenAnchored(true);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, lp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(buildHeader(), matchWrap());

        videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(0xFF000000);
        texture = new AspectTextureView(this);
        texture.setOpaque(true);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                if (surface != null) surface.release();
                surface = new Surface(st);
                if (player != null) {
                    try {
                        player.setSurface(surface);
                        if (prepared && !player.isPlaying()) {
                            player.start();
                            playButton.setText("\u275a\u275a");
                            main.removeCallbacks(progress);
                            main.post(progress);
                        }
                    } catch (Throwable ignored) {}
                }
                fitVideo();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                fitVideo();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (player != null) {
                    try { player.setSurface(null); } catch (Throwable ignored) {}
                }
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        videoFrame.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(4);
        column.addView(videoFrame, bodyLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(4), 0, 0);
        TextView previous = button("\u23ee", false);
        previous.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSibling(-1); }
        });
        controls.addView(previous, new LinearLayout.LayoutParams(dp(42), dp(32)));
        playButton = button("\u25b6", true);
        playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(44), dp(32));
        playLp.leftMargin = dp(4);
        controls.addView(playButton, playLp);
        TextView back = button("\u2212 10", false);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-10000); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(50), dp(32));
        backLp.leftMargin = dp(5);
        controls.addView(back, backLp);
        TextView forward = button("+ 10", false);
        forward.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
        forward.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(10000); }
        });
        controls.addView(forward, new LinearLayout.LayoutParams(dp(50), dp(32)));
        TextView next = button("\u23ed", false);
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSibling(1); }
        });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(42), dp(32));
        nextLp.leftMargin = dp(4);
        controls.addView(next, nextLp);
        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ThemeColors.accent()));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ThemeColors.accent()));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        seekBar.setFocusable(true);
        seekBar.setFocusableInTouchMode(true);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) timeLabel.setText(formatTime(progress) + " / "
                        + formatTime(currentDuration()));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (prepared && player != null) player.seekTo(bar.getProgress());
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        seekLp.leftMargin = dp(7);
        controls.addView(seekBar, seekLp);
        timeLabel = text("00:00 / 00:00", 7.5f, MUTED, true);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.leftMargin = dp(8);
        controls.addView(timeLabel, timeLp);
        column.addView(controls, matchWrap());

        status = text("", 7.5f, MUTED, true);
        status.setPadding(dp(2), dp(4), dp(2), 0);
        column.addView(status, matchWrap());

        setContentView(root);
        applyWallpaperBackdrop(wallpaper, sheet);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("VIDEO", 10f, 0xFFFFFFFF, false);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerTitle = plainText(file.getName(), 14f, TEXT, false);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        headerTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headerSubtitle = plainText(file.getAbsolutePath(), 7.5f, MUTED, true);
        headerSubtitle.setSingleLine(true);
        headerSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titles.addView(eyebrow);
        titles.addView(headerTitle);
        titles.addView(headerSubtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(7), 0, dp(7), 0);
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishAnimated(); }
        });
        header.addView(close);
        return header;
    }

    private void buildSiblings() {
        siblings.clear();
        File parent = file.getParentFile();
        File[] children = parent == null ? null : parent.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!isVideo(child)) continue;
                if (child.getName().startsWith(".") && !child.equals(file)) continue;
                siblings.add(child);
            }
        }
        if (!siblings.contains(file)) siblings.add(file);
        Collections.sort(siblings, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        siblingIndex = siblings.indexOf(file);
        if (siblingIndex < 0) siblingIndex = 0;
    }

    private boolean isVideo(File f) {
        if (f == null || !f.isFile()) return false;
        String name = f.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "mp4": case "mkv": case "webm": case "avi": case "mov":
            case "3gp": case "m4v": case "ts": case "mpeg": case "mpg":
                return true;
            default:
                String mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext);
                return mime != null && mime.startsWith("video/");
        }
    }

    private void updateHeader() {
        if (headerTitle != null) headerTitle.setText(file.getName());
        if (headerSubtitle != null) headerSubtitle.setText(file.getAbsolutePath());
    }

    private void showSibling(int delta) {
        if (siblings.isEmpty()) return;
        int next = siblingIndex + delta;
        if (next < 0 || next >= siblings.size()) return;
        siblingIndex = next;
        file = siblings.get(siblingIndex);
        prepared = false;
        updateHeader();
        if (seekBar != null) seekBar.setProgress(0);
        if (timeLabel != null) timeLabel.setText("00:00 / 00:00");
        startVideo();
        Haptics.tick(texture);
    }

    private void startVideo() {
        releasePlayer();
        prepared = false;
        metadataDurationMs = 0;
        try {
            MediaMetadataRetriever probe = new MediaMetadataRetriever();
            probe.setDataSource(file.getAbsolutePath());
            String duration = probe.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) metadataDurationMs = Integer.parseInt(duration);
            probe.release();
        } catch (Throwable ignored) {
        }
        status.setText(Loc.s(this, "LOADING") + "…");
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            if (surface != null) player.setSurface(surface);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    int duration = mp.getDuration() > 0
                            ? mp.getDuration() : metadataDurationMs;
                    seekBar.setMax(Math.max(1, duration));
                    timeLabel.setText("00:00 / " + formatTime(duration));
                    status.setText((siblingIndex + 1) + " / " + siblings.size()
                            + "  ·  " + formatLabel() + "  ·  " + formatBytes(file.length()));
                    try {
                        player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    } catch (Throwable ignored) {}
                    if (surface != null) {
                        try { player.setSurface(surface); } catch (Throwable ignored) {}
                    }
                    player.start();
                    playButton.setText("\u275a\u275a");
                    main.removeCallbacks(progress);
                    main.post(progress);
                }
            });
            player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    videoWidth = width;
                    videoHeight = height;
                    if (texture != null) texture.post(new Runnable() {
                        @Override public void run() { fitVideo(); }
                    });
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    playButton.setText("\u25b6");
                    seekBar.setProgress(0);
                    timeLabel.setText("00:00 / " + formatTime(currentDuration()));
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    LToast.makeText(VideoPlayerActivity.this,
                            Loc.s(VideoPlayerActivity.this, "Could not play video"),
                            android.widget.Toast.LENGTH_SHORT).show();
                    status.setText(Loc.s(VideoPlayerActivity.this, "CODEC / FILE ERROR"));
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "Could not play video"),
                    android.widget.Toast.LENGTH_SHORT).show();
            status.setText(Loc.s(this, "CODEC / FILE ERROR"));
        }
    }

    private int currentDuration() {
        if (player != null) {
            try {
                int duration = player.getDuration();
                if (duration > 0) return duration;
            } catch (Throwable ignored) {
            }
        }
        return metadataDurationMs;
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.reset(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        prepared = false;
    }

    private void fitVideo() {
        if (texture == null || videoFrame == null || videoWidth <= 0 || videoHeight <= 0) return;
        int pw = videoFrame.getWidth();
        int ph = videoFrame.getHeight();
        if (pw <= 0 || ph <= 0) {
            videoFrame.post(new Runnable() {
                @Override public void run() { fitVideo(); }
            });
            return;
        }
        float videoAspect = videoWidth / (float) videoHeight;
        int dw = pw;
        int dh = ph;
        if (pw / (float) ph > videoAspect) {
            dw = Math.max(1, Math.round(ph * videoAspect));
        } else {
            dh = Math.max(1, Math.round(pw / videoAspect));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dw, dh, Gravity.CENTER);
        texture.setLayoutParams(lp);
    }

    private void togglePlay() {
        if (!prepared || player == null) return;
        if (player.isPlaying()) {
            player.pause();
            playButton.setText("\u25b6");
        } else {
            player.start();
            playButton.setText("\u275a\u275a");
        }
    }

    private void seekBy(int delta) {
        if (!prepared || player == null) return;
        int target = Math.max(0, Math.min(player.getDuration(),
                player.getCurrentPosition() + delta));
        player.seekTo(target);
        seekBar.setProgress(target);
    }

    private String formatTime(int ms) {
        int total = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private String formatLabel() {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.US) : "VIDEO";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i]);
    }

    private void applyWallpaperBackdrop(final WallpaperView wp, final GlassPanel panel) {
        wp.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                Bitmap blurred = wp.getBlurred();
                if (blurred == null || blurred.isRecycled()
                        || wp.getWidth() <= 0 || wp.getHeight() <= 0) {
                    wp.postDelayed(this, 180L);
                    return;
                }
                Backdrop backdrop = new Backdrop(blurred, wp.getWidth(), wp.getHeight());
                panel.setBackdrop(backdrop);
                panel.setBackdropScreenAnchored(true);
                panel.setFrosted(true);
                panel.invalidate();
            }
        }, 220L);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private TextView plainText(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 9f, primary ? 0xFF071015 : 0xE6FFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(primary ? ThemeColors.accent() : 0x22FFFFFF,
                dp(10), primary ? 0x00000000 : 0x44FFFFFF, dp(1)));
        return v;
    }
    /** TextureView that keeps the video's aspect ratio inside its frame. */
    private static final class AspectTextureView extends TextureView {
        private int videoWidth;
        private int videoHeight;

        AspectTextureView(android.content.Context c) {
            super(c);
        }

        void setVideoSize(int width, int height) {
            if (width <= 0 || height <= 0) return;
            if (width == videoWidth && height == videoHeight) return;
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0) {
                float videoAspect = videoWidth / (float) videoHeight;
                if (width / (float) height > videoAspect) {
                    width = Math.max(1, Math.round(height * videoAspect));
                } else {
                    height = Math.max(1, Math.round(width / videoAspect));
                }
            }
            setMeasuredDimension(width, height);
        }
    }

}
