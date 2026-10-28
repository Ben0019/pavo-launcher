package com.ben.aurora;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

/** Built-in audio player with basic playback controls. */
public final class AudioPlayerActivity extends AuroraGlassActivity {
    static final String EXTRA_PATH = "audio_player_path";

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private ControllerNav controllerNav;
    private File file;
    private MediaPlayer player;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private final List<File> siblings = new ArrayList<>();
    private int siblingIndex;
    private TextView playButton;
    private TextView timeLabel;
    private TextView status;
    private SeekBar seekBar;
    private boolean prepared;
    private boolean dragging;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player != null && prepared && !dragging) {
                int position = player.getCurrentPosition();
                seekBar.setProgress(position);
                timeLabel.setText(formatTime(position) + " / "
                        + formatTime(player.getDuration()));
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
        startAudio();
        installController();
    }

    @Override
    protected void onPause() {
        if (controllerNav != null) controllerNav.cancel();
        if (player != null && player.isPlaying()) player.pause();
        if (playButton != null) playButton.setText("\u25b6");
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
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
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
        sheet.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
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

        FrameLayout art = new FrameLayout(this);
        AudioArtView artView = new AudioArtView(this);
        art.addView(artView, new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER));
        column.addView(art, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ThemeColors.accent()));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ThemeColors.accent()));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        seekBar.setFocusable(true);
        seekBar.setFocusableInTouchMode(true);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) timeLabel.setText(formatTime(progress) + " / "
                        + formatTime(player == null ? 0 : player.getDuration()));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (prepared) player.seekTo(bar.getProgress());
            }
        });
        column.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(2), 0, dp(3));
        TextView previous = button("\u23ee", false);
        previous.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSibling(-1); }
        });
        controls.addView(previous, new LinearLayout.LayoutParams(dp(44), dp(34)));
        TextView rewind = button("\u2212 10", false);
        rewind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        rewind.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(-10000); }
        });
        controls.addView(rewind, new LinearLayout.LayoutParams(dp(58), dp(34)));
        playButton = button("\u25b6", true);
        playButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(58), dp(38));
        playLp.leftMargin = dp(12);
        playLp.rightMargin = dp(12);
        controls.addView(playButton, playLp);
        TextView forward = button("+ 10", false);
        forward.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        forward.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { seekBy(10000); }
        });
        controls.addView(forward, new LinearLayout.LayoutParams(dp(58), dp(34)));
        TextView next = button("\u23ed", false);
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSibling(1); }
        });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(44), dp(34));
        nextLp.leftMargin = dp(4);
        controls.addView(next, nextLp);
        column.addView(controls, matchWrap());

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeLabel = text("00:00 / 00:00", 8f, MUTED, true);
        timeRow.addView(timeLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status = text("", 7.5f, MUTED, true);
        status.setGravity(Gravity.RIGHT);
        timeRow.addView(status);
        column.addView(timeRow, matchWrap());

        setContentView(root);
        applyWallpaperBackdrop(wallpaper, sheet);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("AUDIO", 10f, 0xFFFFFFFF, false);
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
                if (!isAudio(child)) continue;
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

    private boolean isAudio(File f) {
        if (f == null || !f.isFile()) return false;
        String name = f.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "mp3": case "m4a": case "aac": case "flac": case "wav":
            case "ogg": case "opus": case "amr": case "mid": case "midi":
                return true;
            default:
                String mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext);
                return mime != null && mime.startsWith("audio/");
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
        updateHeader();
        prepared = false;
        if (seekBar != null) seekBar.setProgress(0);
        if (timeLabel != null) timeLabel.setText("00:00 / 00:00");
        startAudio();
        Haptics.tick(seekBar);
    }

    private void startAudio() {
        status.setText(Loc.s(this, "LOADING") + "…");
        if (player == null) player = new MediaPlayer();
        else {
            try { player.reset(); } catch (Throwable ignored) {}
        }
        try {
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    prepared = true;
                    seekBar.setMax(Math.max(1, player.getDuration()));
                    timeLabel.setText("00:00 / " + formatTime(player.getDuration()));
                    status.setText((siblingIndex + 1) + " / " + siblings.size()
                            + "  ·  " + formatLabel() + "  ·  " + formatBytes(file.length()));
                    player.start();
                    playButton.setText("\u275a\u275a");
                    main.post(progress);
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    playButton.setText("\u25b6");
                    seekBar.setProgress(0);
                    timeLabel.setText("00:00 / " + formatTime(player.getDuration()));
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    LToast.makeText(AudioPlayerActivity.this,
                            Loc.s(AudioPlayerActivity.this, "Could not play audio"),
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "Could not play audio"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
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
        return dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.US) : "AUDIO";
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

    private static final class AudioArtView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        AudioArtView(android.content.Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            int accent = ThemeColors.accent();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x22101820);
            canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) * 0.43f, paint);
            line.setStrokeWidth(Math.max(1.5f, w * 0.012f));
            line.setColor(alpha(accent, 0x55));
            for (int i = 1; i <= 3; i++) {
                canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) * (0.18f + i * 0.075f), line);
            }
            line.setColor(accent);
            line.setStrokeWidth(Math.max(2f, w * 0.025f));
            for (int i = 0; i < 9; i++) {
                float x = w * (0.27f + i * 0.0575f);
                float amp = (float) (0.12f + 0.16f * Math.abs(Math.sin(i * 1.7f)));
                canvas.drawLine(x, h * (0.5f - amp), x, h * (0.5f + amp), line);
            }
        }

        private static int alpha(int color, int a) {
            return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
        }
    }
}
