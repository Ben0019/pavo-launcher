package com.ben.pavo;

import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in Pavo app: release notes and one-tap updates from GitHub. */
public final class UpdatesActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> nav = new ArrayList<>();
    private int accent = 0xFFA9BEFF;
    private Prefs prefs;

    private ScrollView scrollView;
    private TextView versionChip;
    private TextView statusView;
    private TextView notesView;
    private TextView checkButton;
    private TextView installButton;
    private TextView cancelButton;
    private ProgressBar downloadBar;
    private String downloadingTag;
    private long lastProgressAt;
    private long lastProgressBytes;
    private long lastProgressTime;
    private TextView closeChip;
    private SeekBar intervalBar;
    private Updater.Check current;
    private boolean newer;
    private boolean suppress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = ThemeColors.accent();
        prefs = new Prefs(this);
        buildUi();
        runCheck();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setFocusableInTouchMode(true);

        GlassPanel sheet = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        sheet.setPadding(dp(20), dp(11), dp(20), dp(10));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sheetLp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, sheetLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(7);
        column.addView(divider, dlp);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(7);
        column.addView(scrollView, scrollLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = text("Checking GitHub…", 11f, MUTED, false);
        statusView.setPadding(0, dp(2), 0, dp(8));
        content.addView(statusView, wrap());

        notesView = text("", 11f, TEXT, false);
        notesView.setLineSpacing(dp(2), 1f);
        content.addView(notesView, wrap());

        LinearLayout interval = new LinearLayout(this);
        interval.setOrientation(LinearLayout.HORIZONTAL);
        interval.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams intervalLp = wrap();
        intervalLp.topMargin = dp(14);
        TextView intervalLabel = text("AUTO CHECK EVERY", 8.5f, MUTED, false);
        intervalLabel.setLetterSpacing(0.12f);
        interval.addView(intervalLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView intervalValue = text(prefs.updateCheckHours() + " h", 10f, accent, true);
        interval.addView(intervalValue);
        content.addView(interval, intervalLp);

        intervalBar = new SeekBar(this);
        intervalBar.setMax(22);
        intervalBar.setProgress(prefs.updateCheckHours() - 2);
        intervalBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        intervalBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        intervalBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        intervalBar.setPadding(dp(2), dp(2), dp(2), 0);
        intervalBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                intervalValue.setText((progress + 2) + " h");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.key(seekBar);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.click(seekBar);
                prefs.setUpdateCheckHours(seekBar.getProgress() + 2);
            }
        });
        content.addView(intervalBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        TextView hint = text("Updates come from github.com/Ben0019/pavo-launcher. "
                + "Silent install on PServer, otherwise the system installer asks.",
                9f, 0x66FFFFFF, false);
        hint.setPadding(0, dp(2), 0, dp(4));
        content.addView(hint, wrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        checkButton = button("CHECK AGAIN", false);
        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                runCheck();
            }
        });
        buttons.addView(checkButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        downloadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadBar.setMax(100);
        downloadBar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        downloadBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        downloadBar.setVisibility(View.GONE);
        buttons.addView(downloadBar, new LinearLayout.LayoutParams(0, dp(10), 1f));
        cancelButton = button("CANCEL", false);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                cancelButton.setEnabled(false);
                statusView.setText("Cancelling…");
                statusView.setTextColor(MUTED);
                Updater.cancelDownload();
            }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f);
        cancelLp.leftMargin = dp(8);
        buttons.addView(cancelButton, cancelLp);
        installButton = button("INSTALL", true);
        installButton.setVisibility(View.GONE);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                install();
            }
        });
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        installLp.leftMargin = dp(8);
        buttons.addView(installButton, installLp);
        LinearLayout.LayoutParams buttonsLp = wrap();
        buttonsLp.topMargin = dp(8);
        column.addView(buttons, buttonsLp);

        setupControllerNav();
        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("PAVO UPDATES", 12f, accent, false);
        title.setLetterSpacing(0.15f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = text("APP UPDATES  ·  GITHUB.COM/BEN0019/PAVO-LAUNCHER",
                8.5f, MUTED, false);
        sub.setLetterSpacing(0.10f);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        versionChip = text(versionName(), 10f, accent, true);
        versionChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        versionChip.setBackground(Ui.rounded(0x1AFFFFFF, dp(10), 0x33FFFFFF, dp(1)));
        header.addView(versionChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        closeChip = text("\u2715", 12f, 0xCCFFFFFF, false);
        closeChip.setGravity(Gravity.CENTER);
        closeChip.setPadding(dp(9), dp(4), dp(9), dp(4));
        closeChip.setFocusable(true);
        closeChip.setFocusableInTouchMode(true);
        closeChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                finishAnimated();
            }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(8);
        header.addView(closeChip, closeLp);
        return header;
    }

    private void setupControllerNav() {
        nav.clear();
        nav.add(checkButton);
        nav.add(installButton);
        nav.add(cancelButton);
        nav.add(intervalBar);
        nav.add(closeChip);
        for (View v : nav) {
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean focused) {
                    if (focused && scrollView != null && view != closeChip) {
                        scrollView.smoothScrollTo(0, 0);
                    }
                }
            });
        }
        checkButton.requestFocus();
    }

    private void moveNav(int direction) {
        if (nav.isEmpty()) return;
        View current = getCurrentFocus();
        int idx = nav.indexOf(current);
        if (idx < 0) idx = direction > 0 ? -1 : 0;
        for (int i = 0; i < nav.size(); i++) {
            idx = (idx + direction + nav.size()) % nav.size();
            View candidate = nav.get(idx);
            if (candidate.getVisibility() == View.VISIBLE && candidate.isEnabled()
                    && candidate.requestFocus()) {
                return;
            }
        }
    }

    private void runCheck() {
        statusView.setText("Checking GitHub…");
        statusView.setTextColor(MUTED);
        installButton.setVisibility(View.GONE);
        worker.execute(new Runnable() {
            @Override public void run() {
                Updater.Check check = null;
                boolean fresh = false;
                try {
                    check = Updater.latest();
                    fresh = check != null && Updater.isNewer(UpdatesActivity.this, check);
                } catch (Throwable ignored) {
                }
                final Updater.Check result = check;
                final boolean available = fresh;
                main.post(new Runnable() {
                    @Override public void run() {
                        render(result, available);
                    }
                });
            }
        });
    }

    private void render(Updater.Check check, boolean available) {
        if (isFinishing()) return;
        current = check;
        newer = available;
        Updater.setAvailable(this, available, check == null ? null : check.tag);
        if (check == null) {
            statusView.setText("Could not reach GitHub");
            statusView.setTextColor(AMBER);
            notesView.setText("");
            return;
        }
        String tag = check.tag.replaceFirst("^v", "");
        if (available) {
            statusView.setText("Update available: " + tag + " (you have "
                    + versionName() + ")");
            statusView.setTextColor(GREEN);
            installButton.setVisibility(View.VISIBLE);
        } else {
            statusView.setText("You're up to date (" + versionName() + ")");
            statusView.setTextColor(GREEN);
        }
        notesView.setText(prettyNotes(check.body));
    }

    private void install() {
        if (current == null || !newer) return;
        final Updater.Check check = current;
        downloadingTag = check.tag;
        statusView.setText("Downloading " + check.tag + "…");
        statusView.setTextColor(MUTED);
        installButton.setVisibility(View.GONE);
        checkButton.setVisibility(View.GONE);
        downloadBar.setVisibility(View.VISIBLE);
        downloadBar.setProgress(0);
        cancelButton.setVisibility(View.VISIBLE);
        cancelButton.setEnabled(true);
        lastProgressAt = 0L;
        lastProgressBytes = 0L;
        lastProgressTime = SystemClock.uptimeMillis();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    final java.io.File apk = Updater.download(UpdatesActivity.this,
                            check, new Updater.Progress() {
                                @Override public void onProgress(long done, long total) {
                                    showProgress(done, total);
                                }
                            });
                    if (apk == null) {
                        final boolean cancelled = Updater.wasDownloadCancelled();
                        main.post(new Runnable() {
                            @Override public void run() {
                                statusView.setText(cancelled ? "Download cancelled"
                                        : "Download failed");
                                statusView.setTextColor(cancelled ? MUTED : AMBER);
                                restoreIdle();
                            }
                        });
                        return;
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Installing " + check.tag + "…");
                            statusView.setTextColor(MUTED);
                            downloadBar.setProgress(100);
                            cancelButton.setVisibility(View.GONE);
                        }
                    });
                    Updater.install(UpdatesActivity.this, apk);
                } catch (Throwable t) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            statusView.setText("Update failed: " + t.getMessage());
                            statusView.setTextColor(AMBER);
                            restoreIdle();
                        }
                    });
                }
            }
        });
    }

    private void showProgress(long done, long total) {
        final long now = SystemClock.uptimeMillis();
        if (now - lastProgressAt < 150L && (total <= 0 || done < total)) return;
        long dt = Math.max(1L, now - lastProgressTime);
        long speed = Math.max(0L, (done - lastProgressBytes) * 1000L / dt);
        lastProgressAt = now;
        lastProgressTime = now;
        lastProgressBytes = done;
        final int pct = total > 0 ? (int) Math.min(100L, done * 100L / total) : 0;
        final long speedBps = speed;
        final boolean wait = total <= 0;
        main.post(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if (wait) {
                    downloadBar.setIndeterminate(true);
                    statusView.setText("Downloading " + downloadingTag + "…");
                } else {
                    downloadBar.setIndeterminate(false);
                    downloadBar.setProgress(pct);
                    statusView.setText("Downloading " + downloadingTag + " · " + pct
                            + "% · " + fmtSpeed(speedBps));
                }
            }
        });
    }

    private void restoreIdle() {
        downloadBar.setVisibility(View.GONE);
        downloadBar.setIndeterminate(false);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setEnabled(true);
        checkButton.setVisibility(View.VISIBLE);
        checkButton.setEnabled(true);
        if (newer) {
            installButton.setVisibility(View.VISIBLE);
            installButton.setEnabled(true);
        }
    }

    private static String fmtSpeed(long bps) {
        if (bps >= 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB/s", bps / (1024f * 1024f));
        }
        if (bps >= 1024L) {
            return String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024f);
        }
        return bps + " B/s";
    }

    private static String prettyNotes(String body) {
        if (body == null || body.trim().isEmpty()) return "No release notes.";
        String text = body.replace("\r", "").trim();
        return text.replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("**", "")
                .replace("`", "");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (ControllerNav.isSyntheticStickDpad(event)) return true;
        ControllerNav.noteExternalDpad(event);
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getRepeatCount() == 0) finishAnimated();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_A
                    || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                View focus = getCurrentFocus();
                if (focus == intervalBar) return true;
                if (focus != null) {
                    focus.performClick();
                    return true;
                }
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                moveNav(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                moveNav(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (getCurrentFocus() == intervalBar) {
                    intervalBar.setProgress(Math.min(22, intervalBar.getProgress() + 1));
                    prefs.setUpdateCheckHours(intervalBar.getProgress() + 2);
                    return true;
                }
                moveNav(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (getCurrentFocus() == intervalBar) {
                    intervalBar.setProgress(Math.max(0, intervalBar.getProgress() - 1));
                    prefs.setUpdateCheckHours(intervalBar.getProgress() + 2);
                    return true;
                }
                moveNav(-1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif", Typeface.NORMAL));
        return v;
    }

    private TextView button(String label, boolean filled) {
        TextView v = text(label, 10f, filled ? 0xFF061015 : accent, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), dp(9), dp(10), dp(9));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        styleButton(v, filled, false);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                styleButton((TextView) view, filled, focused);
            }
        });
        return v;
    }

    private void styleButton(TextView v, boolean filled, boolean focused) {
        int fill = filled ? accent : (focused ? 0x2EFFFFFF : 0x1AFFFFFF);
        int stroke = filled ? 0x00000000 : (focused ? accent : 0x44FFFFFF);
        v.setBackground(Ui.rounded(fill, dp(12), stroke, dp(1)));
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
