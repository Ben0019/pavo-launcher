package com.ben.pavo;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Toast;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Built-in launcher app: saved performance sessions with delete / clear-all. */
public final class PerfLogActivity extends PavoGlassActivity {
    private int accent = 0xFFA9BEFF;
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x80FFFFFF;
    private static final int DETAIL_MUTED = 0xB8FFFFFF;
    private static final int DETAIL_LABEL = 0xA8FFFFFF;
    private static PerfLogActivity instance;
    private static boolean perfLogVisible;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PerfLogEntry> entries = new ArrayList<>();
    private PerfLogStore store;
    private Prefs prefs;
    private LogAdapter adapter;
    private TextView emptyView;
    private ListView list;
    private FrameLayout rootView;
    private FrameLayout detailOverlay;
    private GlassPanel detailCard;
    private View detailSource;
    private View detailScrim;
    private GlassPanel mainCard;
    private ScrollView detailScroll;
    private int listFocusIndex = -1;
    private int listHoldDir;
    private int listHoldTicks;
    private int detailHoldDir;
    private int detailHoldTicks;
    private long lastListHaptic;
    private long lastDetailHaptic;
    private Dialog optionsDialog;
    private boolean logLongFired;
    private boolean logLongEligible;
    private long lastOptionsStickAt;
    private boolean capturingPerfLogShortcut;
    private String perfLogMappedDown;
    private final Runnable finishPerfLogCapture = new Runnable() {
        @Override
        public void run() {
            if (!capturingPerfLogShortcut) return;
            capturingPerfLogShortcut = false;
            LToast.makeText(PerfLogActivity.this,
                    "No controller button detected", Toast.LENGTH_SHORT).show();
        }
    };
    private final Runnable perfLogMappedLongPress = new Runnable() {
        @Override
        public void run() {
            if (perfLogMappedDown == null || detailOverlay != null) return;
            Haptics.longPress(PerfLogActivity.this);
            showLogOptions();
        }
    };
    private final Runnable logOptionsLongPress = new Runnable() {
        @Override
        public void run() {
            if (detailOverlay != null || entries.isEmpty() || listFocusIndex < 0) return;
            logLongFired = true;
            Haptics.longPress(list);
            showLogOptions();
        }
    };
    private final Runnable listScrollRepeat = new Runnable() {
        @Override
        public void run() {
            if (listHoldDir == 0 || detailOverlay != null) return;
            if (!moveListFocus(listHoldDir * (listHoldTicks > 7 ? 2 : 1))) {
                stopListScroll();
                return;
            }
            listHoldTicks++;
            handler.postDelayed(this, listHoldTicks > 7 ? 42L : 82L);
        }
    };
    private final Runnable detailScrollRepeat = new Runnable() {
        @Override
        public void run() {
            if (detailHoldDir == 0 || detailScroll == null || detailOverlay == null) return;
            if (!detailScroll.canScrollVertically(detailHoldDir)) {
                stopDetailScroll();
                return;
            }
            int step = dp(detailHoldTicks > 24 ? 18 : 10);
            detailScroll.scrollBy(0, detailHoldDir * step);
            long now = SystemClock.uptimeMillis();
            if (now - lastDetailHaptic > 110L) {
                lastDetailHaptic = now;
                Haptics.tick(detailScroll);
            }
            detailHoldTicks++;
            handler.postDelayed(this, 16L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        instance = this;
        prefs = new Prefs(this);
        store = new PerfLogStore(this);
        buildUi();
        hideBars();
        PerfOverlayService.flushCurrentSession();
        // Fill the existing sessions before the first frame is drawn. The delayed
        // refresh below still picks up the session that was just flushed.
        refresh();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 350);
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        perfLogVisible = true;
        hideBars();
        hidePerformanceOverlay();
        PerfOverlayService.flushCurrentSession();
        // Show the saved cards on the first visible frame; the delayed pass only
        // catches the session that was just flushed.
        refresh();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 500);
    }

    @Override
    protected void onPause() {
        perfLogVisible = false;
        capturingPerfLogShortcut = false;
        perfLogMappedDown = null;
        handler.removeCallbacks(perfLogMappedLongPress);
        handler.removeCallbacks(finishPerfLogCapture);
        stopListScroll();
        stopDetailScroll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (instance == this) {
            instance = null;
            perfLogVisible = false;
        }
        super.onDestroy();
    }

    static boolean isVisible() {
        return perfLogVisible;
    }

    static void showOptionsFromShortcut() {
        final PerfLogActivity activity = instance;
        if (activity == null || !perfLogVisible) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity.detailOverlay == null) activity.showLogOptions();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (detailOverlay != null) {
            hideDetail();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (capturingPerfLogShortcut) {
            if (down && event.getRepeatCount() == 0) {
                String mapped = perfLogKeyLabel(event);
                if (mapped != null) {
                    capturingPerfLogShortcut = false;
                    handler.removeCallbacks(finishPerfLogCapture);
                    prefs.setPerfLogControllerRaw(mapped);
                    LToast.makeText(this, "Perf Log popup shortcut: "
                            + PavoA11yService.perfLogControllerLabel(prefs),
                            Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }

        String perfLogMapped = prefs.perfLogControllerRaw();
        String pressed = perfLogKeyLabel(event);
        if (perfLogMapped != null && !perfLogMapped.isEmpty()
                && perfLogMapped.equals(pressed)) {
            if (down) {
                if (event.getRepeatCount() == 0 && perfLogMappedDown == null) {
                    perfLogMappedDown = pressed;
                    handler.removeCallbacks(perfLogMappedLongPress);
                    handler.postDelayed(perfLogMappedLongPress, 520L);
                }
            } else {
                handler.removeCallbacks(perfLogMappedLongPress);
                perfLogMappedDown = null;
            }
            return true;
        }

        if (detailOverlay != null) {
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
                if (down && event.getRepeatCount() == 0) hideDetail();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getScanCode() == 0
                        && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
                    return true;
                }
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        startDetailScroll(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1, true);
                    }
                } else {
                    stopDetailScroll();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) onBackPressed();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && event.getRepeatCount() == 0) {
                logLongFired = false;
                logLongEligible = false;
                if (!entries.isEmpty()) {
                    if (listFocusIndex < 0) {
                        focusLogAt(0, true);
                    } else {
                        logLongEligible = true;
                        handler.postDelayed(logOptionsLongPress, 520L);
                    }
                }
            } else if (!down) {
                handler.removeCallbacks(logOptionsLongPress);
                if (!logLongFired && logLongEligible) openSelectedLog();
                logLongEligible = false;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getScanCode() == 0
                    && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
                return true;
            }
            if (down) {
                if (event.getRepeatCount() == 0) {
                    startListScroll(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1, true);
                }
            } else {
                stopListScroll();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        boolean joystick = (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) != 0;
        if (joystick) {
            float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(y) < 0.5f) y = event.getAxisValue(MotionEvent.AXIS_Y);
            if (Math.abs(y) < 0.5f) y = StickScroll.rightY(event);
            if (detailOverlay != null) {
                if (Math.abs(y) >= 0.45f) startDetailScroll(y > 0 ? 1 : -1, false);
                else stopDetailScroll();
                return true;
            }
            if (!entries.isEmpty()) {
                if (Math.abs(y) >= 0.45f) startListScroll(y > 0 ? 1 : -1, false);
                else stopListScroll();
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            stopListScroll();
            stopDetailScroll();
        }
        return super.dispatchTouchEvent(event);
    }

    private void startListScroll(int dir, boolean restart) {
        if (dir == 0 || entries.isEmpty()) return;
        if (!restart && listHoldDir == dir) return;
        stopListScroll();
        listHoldDir = dir;
        listHoldTicks = 0;
        if (listFocusIndex < 0) {
            focusLogAt(0, true);
        } else {
            moveListFocus(dir);
        }
        handler.postDelayed(listScrollRepeat, 280L);
    }

    private void stopListScroll() {
        listHoldDir = 0;
        listHoldTicks = 0;
        handler.removeCallbacks(listScrollRepeat);
    }

    private boolean moveListFocus(int delta) {
        if (entries.isEmpty() || delta == 0) return false;
        int next = Math.max(0, Math.min(entries.size() - 1, listFocusIndex + delta));
        if (next == listFocusIndex) {
            long now = SystemClock.uptimeMillis();
            if (now - lastListHaptic > 140L) {
                lastListHaptic = now;
                Haptics.tick(list);
            }
            return false;
        }
        listFocusIndex = next;
        focusLogAt(next, true);
        long now = SystemClock.uptimeMillis();
        if (now - lastListHaptic > 90L) {
            lastListHaptic = now;
            Haptics.tick(list);
        }
        return true;
    }

    private void focusLogAt(int index, boolean animate) {
        if (entries.isEmpty() || list == null) return;
        listFocusIndex = Math.max(0, Math.min(entries.size() - 1, index));
        if (animate) {
            list.smoothScrollToPositionFromTop(listFocusIndex, dp(8), 150);
        } else {
            list.setSelectionFromTop(listFocusIndex, dp(8));
        }
        refreshLogRowStyles();
        list.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshLogRowStyles();
            }
        }, 180L);
    }

    private void refreshLogRowStyles() {
        if (list == null) return;
        int first = list.getFirstVisiblePosition();
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row == null) continue;
            int position = first + i;
            row.setBackground(position == listFocusIndex
                    ? Ui.focusRing(PerfLogActivity.this, dp(14))
                    : Ui.rounded(0x14FFFFFF, dp(14), 0x26FFFFFF, dp(1)));
        }
    }

    private View visibleLogRow(int index) {
        if (list == null) return null;
        int first = list.getFirstVisiblePosition();
        return list.getChildAt(index - first);
    }

    private void startDetailScroll(int dir, boolean restart) {
        if (dir == 0 || detailScroll == null) return;
        if (!restart && detailHoldDir == dir) return;
        stopDetailScroll();
        detailHoldDir = dir;
        detailHoldTicks = 0;
        if (detailScroll.canScrollVertically(dir)) {
            detailScroll.smoothScrollBy(0, dir * dp(90));
            Haptics.tick(detailScroll);
            lastDetailHaptic = SystemClock.uptimeMillis();
            handler.postDelayed(detailScrollRepeat, 55L);
        }
    }

    private void stopDetailScroll() {
        detailHoldDir = 0;
        detailHoldTicks = 0;
        handler.removeCallbacks(detailScrollRepeat);
    }

    private String perfLogKeyLabel(KeyEvent event) {
        int raw = ControllerMapConfig.rawFor(event);
        if (raw <= 0) return null;
        String label = RootlessUinput.linuxLabel(raw);
        return label == null || label.isEmpty() ? null : label;
    }

    private void openSelectedLog() {
        if (entries.isEmpty()) return;
        View row = visibleLogRow(listFocusIndex);
        if (row != null) {
            row.performClick();
        } else {
            focusLogAt(listFocusIndex, true);
            list.postDelayed(new Runnable() {
                @Override
                public void run() {
                    View target = visibleLogRow(listFocusIndex);
                    if (target != null) target.performClick();
                }
            }, 170);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private void hideBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsController c = window.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        mainCard = card;
        card.setPadding(dp(22), dp(16), dp(22), dp(14));
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Haptics.longPress(v);
                showLogOptions();
                return true;
            }
        });
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                sw - dp(32), sh - dp(32), Gravity.CENTER);
        root.addView(card, cardLp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("PERFORMANCE LOG", 19f, TEXT, false);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.04f);
        titles.addView(title);
        TextView subtitle = text("Saved sessions · delete any entry or clear all", 10f, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = text("CLEAR ALL", 10f, 0xFFFF8A80, false);
        clear.setPadding(dp(12), dp(7), dp(12), dp(7));
        clear.setBackground(Ui.rounded(0x22FF5C6C, dp(12), 0x66FF5C6C, dp(1)));
        clear.setFocusable(false);
        clear.setDefaultFocusHighlightEnabled(false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                store.clear();
                refresh();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(10);
        header.addView(clear, clp);

        TextView close = text("\u00d7", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(10), 0, dp(2), 0);
        close.setFocusable(false);
        close.setDefaultFocusHighlightEnabled(false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAnimated();
            }
        });
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        card.addView(divider, dlp);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(8));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setItemsCanFocus(false);
        list.setFocusable(false);
        list.setFocusableInTouchMode(false);
        list.setPadding(0, dp(8), 0, dp(8));
        adapter = new LogAdapter();
        list.setAdapter(adapter);
        card.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyView = text("No performance sessions logged yet.\n"
                + "Keep the performance overlay running while you play.", 14f, MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        card.addView(emptyView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void refresh() {
        store.load();
        cleanupConfiguredLogs();
        entries.clear();
        entries.addAll(store.entries());
        adapter.notifyDataSetChanged();
        if (entries.isEmpty()) listFocusIndex = -1;
        else if (listFocusIndex >= entries.size()) listFocusIndex = entries.size() - 1;
        boolean empty = entries.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void cleanupConfiguredLogs() {
        store.cleanupAll(prefs.perfCleanupHours());
    }

    private final class LogAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return entries.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final PerfLogEntry e = entries.get(position);
            LinearLayout row = new LinearLayout(PerfLogActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(10), dp(10));
            row.setFocusable(false);
            row.setFocusableInTouchMode(false);
            row.setDefaultFocusHighlightEnabled(false);
            row.setBackground(position == listFocusIndex
                    ? Ui.focusRing(PerfLogActivity.this, dp(14))
                    : Ui.rounded(0x14FFFFFF, dp(14), 0x26FFFFFF, dp(1)));

            LinearLayout head = new LinearLayout(PerfLogActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView app = text(e.appLabel == null || e.appLabel.isEmpty()
                    ? e.pkg : e.appLabel, 15f, TEXT, false);
            app.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            head.addView(app, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView delete = text("\u00d7", 20f, 0xFFFF8A80, false);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(dp(10), dp(2), dp(4), dp(2));
            delete.setContentDescription("Delete this log");
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    store.delete(e.id);
                    refresh();
                }
            });
            head.addView(delete);
            row.addView(head);

            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
            String pkg = e.pkg == null ? "" : e.pkg;
            TextView meta = text(pkg + "  ·  " + fmt.format(new Date(e.startMs))
                    + "  ·  " + formatDuration(e.durationMs), 9.5f, 0x77FFFFFF, true);
            meta.setPadding(0, dp(2), 0, dp(5));
            row.addView(meta);

            SpannableStringBuilder metrics = new SpannableStringBuilder();
            addColored(metrics, "FPS  ", MUTED, false);
            addColored(metrics, "avg " + e.avgFps, PerfOverlayView.fpsColor(e.avgFps), true);
            addColored(metrics, "  min " + e.minFps, PerfOverlayView.fpsColor(e.minFps), true);
            addColored(metrics, "  max " + e.maxFps, PerfOverlayView.fpsColor(e.maxFps), true);
            addColored(metrics, "\nCPU  ", MUTED, false);
            addColored(metrics, Math.round(e.maxCpuTemp) + "\u00b0  ",
                    PerfOverlayView.heat((e.maxCpuTemp - 32f) / 55f), true);
            addColored(metrics, e.avgCpuUsage + "%  ", PerfOverlayView.heat(e.avgCpuUsage / 100f), true);
            addColored(metrics, "GPU  ", MUTED, false);
            addColored(metrics, Math.round(e.maxGpuTemp) + "\u00b0  ",
                    PerfOverlayView.heat((e.maxGpuTemp - 32f) / 55f), true);
            addColored(metrics, e.avgGpuUsage + "%  ", PerfOverlayView.heat(e.avgGpuUsage / 100f), true);
            addColored(metrics, "power " + String.format(Locale.US, "%.1f", e.avgPowerW)
                    + "W / peak " + String.format(Locale.US, "%.1f", e.peakPowerW) + "W",
                    PerfOverlayView.heat(e.peakPowerW / 9f), true);
            addColored(metrics, "\nthrottle ", MUTED, false);
            addColored(metrics, e.throttle, throttleColor(e.throttle), true);
            addColored(metrics, "  battery ", MUTED, false);
            addColored(metrics, String.format(Locale.US, "%.1f%%", e.batteryUsedPct),
                    PerfOverlayView.heat(Math.min(1f, e.batteryUsedPct / 18f)), true);
            if (e.batteryEnd >= 0) {
                addColored(metrics, " (" + (e.batteryStart >= 0 ? e.batteryStart + "\u2192" : "")
                        + e.batteryEnd + "%)", 0x99FFFFFF, true);
            }
            if (e.batteryUsedMah > 0) {
                addColored(metrics, String.format(Locale.US, " (%.0f mAh)", e.batteryUsedMah),
                        0xCCFFFFFF, true);
            }
            addColored(metrics, "\nRAM ", MUTED, false);
            addColored(metrics, fmtBytes(e.appRamKb * 1024L), 0xE6FFFFFF, true);
            addColored(metrics, "  sys " + e.memPercent + "%", MUTED, true);
            TextView metricView = text(metrics, 10.5f, TEXT, true);
            metricView.setLineSpacing(0f, 1.18f);
            row.addView(metricView);

            LogGraph graph = new LogGraph(PerfLogActivity.this);
            graph.setHistory(e.history, e.cpuTempHistory, e.gpuTempHistory,
                    e.cpuUsageHistory, e.gpuUsageHistory);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
            glp.topMargin = dp(6);
            row.addView(graph, glp);

            row.setContentDescription((e.appLabel == null ? e.pkg : e.appLabel)
                    + " — tap for detailed metrics");
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listFocusIndex = position;
                    Haptics.click(row);
                    showDetail(e, row);
                }
            });
            return row;
        }
    }

    private void showDetail(final PerfLogEntry e, final View source) {
        if (detailOverlay != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setClickable(true);

        final View scrim = new View(this);
        scrim.setBackgroundColor(0x99070A12);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                hideDetail();
            }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailScrim = scrim;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int cardW = Math.min(dp(820), sw - dp(56));
        int cardH = sh - dp(44);
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0x1A);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        Backdrop detailBackdrop = mainCard == null ? null : mainCard.getBackdrop();
        if (detailBackdrop != null && detailBackdrop.bitmap != null) {
            card.setBackdrop(detailBackdrop);
            card.setBackdropScreenAnchored(true);
            card.setLiquidEffect(true);
        } else {
            card.setFrosted(true);
        }
        card.setClickable(true);
        card.setPadding(dp(24), dp(16), dp(24), dp(14));
        card.setAlpha(0f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, cardH, Gravity.CENTER);
        overlay.addView(card, cardLp);
        detailCard = card;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailScroll = scroll;
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("SESSION DETAILS", 10f, accent, false);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(9), 0, dp(4), 0);
        close.setContentDescription("Close details");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                hideDetail();
            }
        });
        header.addView(close);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView app = text(e.appLabel == null || e.appLabel.isEmpty()
                ? e.pkg : e.appLabel, 26f, TEXT, false);
        app.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        app.setPadding(0, dp(4), 0, 0);
        content.addView(app);
        SimpleDateFormat full = new SimpleDateFormat("MMM d, yyyy · HH:mm:ss", Locale.US);
        TextView meta = text((e.pkg == null ? "" : e.pkg) + "\n"
                + full.format(new Date(e.startMs)) + "  ·  " + formatDuration(e.durationMs),
                9.5f, DETAIL_LABEL, true);
        meta.setPadding(0, dp(2), 0, dp(8));
        content.addView(meta);

        LinearLayout fpsHead = new LinearLayout(this);
        fpsHead.setOrientation(LinearLayout.HORIZONTAL);
        fpsHead.setGravity(Gravity.BOTTOM);
        TextView avg = text(String.valueOf(e.avgFps), 44f,
                PerfOverlayView.fpsColor(e.avgFps), true);
        fpsHead.addView(avg);
        TextView fpsUnit = text("  avg fps", 11f, DETAIL_MUTED, false);
        fpsUnit.setPadding(0, 0, 0, dp(8));
        fpsHead.addView(fpsUnit);
        fpsHead.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        fpsHead.addView(chip("MIN " + e.minFps, PerfOverlayView.fpsColor(e.minFps)));
        fpsHead.addView(chip("MAX " + e.maxFps, PerfOverlayView.fpsColor(e.maxFps)));
        content.addView(fpsHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LogGraph graph = new LogGraph(this);
        graph.setHistory(e.history, e.cpuTempHistory, e.gpuTempHistory,
                e.cpuUsageHistory, e.gpuUsageHistory);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(270));
        glp.topMargin = dp(8);
        content.addView(graph, glp);

        addDetailSection(content, "HARDWARE");
        LinearLayout hw1 = detailRow(content);
        addDetailCell(hw1, "MAX CPU TEMP", Math.round(e.maxCpuTemp) + "\u00b0",
                PerfOverlayView.heat((e.maxCpuTemp - 32f) / 55f));
        addDetailCell(hw1, "AVG CPU LOAD", e.avgCpuUsage + "%",
                PerfOverlayView.heat(e.avgCpuUsage / 100f));
        LinearLayout hw2 = detailRow(content);
        addDetailCell(hw2, "MAX GPU TEMP", Math.round(e.maxGpuTemp) + "\u00b0",
                PerfOverlayView.heat((e.maxGpuTemp - 32f) / 55f));
        addDetailCell(hw2, "AVG GPU LOAD", e.avgGpuUsage + "%",
                PerfOverlayView.heat(e.avgGpuUsage / 100f));

        addDetailSection(content, "POWER & THERMAL");
        LinearLayout power1 = detailRow(content);
        addDetailCell(power1, "AVERAGE POWER", fmtPower(e.avgPowerW),
                PerfOverlayView.heat(e.avgPowerW / 9f));
        addDetailCell(power1, "PEAK POWER", fmtPower(e.peakPowerW),
                PerfOverlayView.heat(e.peakPowerW / 9f));
        LinearLayout power2 = detailRow(content);
        addDetailCell(power2, "THROTTLE", e.throttle, throttleColor(e.throttle));
        addDetailCell(power2, "FPS SAMPLES", String.valueOf(e.fpsSamples), 0xE6FFFFFF);

        addDetailSection(content, "BATTERY & MEMORY");
        LinearLayout batt = detailRow(content);
        addDetailCell(batt, "BATTERY USED",
                String.format(Locale.US, "%.1f%%", e.batteryUsedPct),
                PerfOverlayView.heat(Math.min(1f, e.batteryUsedPct / 18f)));
        addDetailCell(batt, "BATTERY LEVEL",
                (e.batteryStart >= 0 ? e.batteryStart + "→" : "") + e.batteryEnd + "%",
                e.batteryEnd >= 0 ? PerfOverlayView.batteryColor(e.batteryEnd) : 0x55FFFFFF);
        LinearLayout ram = detailRow(content);
        addDetailCell(ram, "ENERGY USED",
                e.batteryUsedMah > 0
                        ? String.format(Locale.US, "%.0f mAh", e.batteryUsedMah) : "\u2014",
                0xE6FFFFFF);
        addDetailCell(ram, "APP RAM", fmtBytes(e.appRamKb * 1024L), 0xE6FFFFFF);
        LinearLayout sys = detailRow(content);
        addDetailCell(sys, "SYSTEM MEMORY PEAK", e.memPercent + "%",
                PerfOverlayView.heat(e.memPercent / 100f));
        addDetailCell(sys, "SAMPLES", String.valueOf(e.samples), 0xE6FFFFFF);

        TextView delete = text("DELETE THIS SESSION", 10f, 0xFFFF8A80, false);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(12), dp(9), dp(12), dp(9));
        delete.setBackground(Ui.rounded(0x22FF5C6C, dp(12), 0x66FF5C6C, dp(1)));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.delete(e.id);
                hideDetail();
                refresh();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        content.addView(delete, dlp);

        setMainCardBlurred(true);
        rootView.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        detailOverlay = overlay;
        detailSource = source;
        overlay.postDelayed(new Runnable() {
            @Override public void run() {
                ReadableText.apply(overlay);
            }
        }, 90L);

        card.post(new Runnable() {
            @Override
            public void run() {
                if (detailCard == null) return;
                int[] target = new int[2];
                detailCard.getLocationOnScreen(target);
                if (source != null && source.isAttachedToWindow()) {
                    int[] start = new int[2];
                    source.getLocationOnScreen(start);
                    float sx = Math.max(0.15f,
                            source.getWidth() / (float) detailCard.getWidth());
                    float sy = Math.max(0.15f,
                            source.getHeight() / (float) detailCard.getHeight());
                    detailCard.setPivotX(0f);
                    detailCard.setPivotY(0f);
                    detailCard.setScaleX(sx);
                    detailCard.setScaleY(sy);
                    detailCard.setTranslationX(start[0] - target[0]);
                    detailCard.setTranslationY(start[1] - target[1]);
                } else {
                    detailCard.setScaleX(0.9f);
                    detailCard.setScaleY(0.9f);
                    detailCard.setTranslationY(dp(24));
                }
                detailCard.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .translationX(0f).translationY(0f)
                        .setDuration(260)
                        .setInterpolator(new DecelerateInterpolator(1.8f))
                        .start();
                scrim.animate().alpha(1f).setDuration(220).start();
            }
        });
    }

    private void setMainCardBlurred(boolean blurred) {
        if (mainCard == null) return;
        if (Build.VERSION.SDK_INT >= 31) {
            mainCard.setRenderEffect(blurred
                    ? RenderEffect.createBlurEffect(dp(3f), dp(3f), Shader.TileMode.CLAMP)
                    : null);
        }
        mainCard.animate().alpha(blurred ? 0.72f : 1f)
                .setDuration(blurred ? 190L : 240L).start();
    }

    private void hideDetail() {
        final FrameLayout overlay = detailOverlay;
        if (overlay == null) return;
        final GlassPanel card = detailCard;
        final View source = detailSource;
        stopDetailScroll();
        detailOverlay = null;
        detailCard = null;
        detailScroll = null;
        detailSource = null;
        if (detailScrim != null) {
            detailScrim.animate().alpha(0f).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
        detailScrim = null;
        if (card != null) {
            card.animate().cancel();
            if (source != null && source.isAttachedToWindow()) {
                int[] target = new int[2];
                int[] start = new int[2];
                card.getLocationOnScreen(target);
                source.getLocationOnScreen(start);
                float sx = Math.max(0.15f, source.getWidth() / (float) card.getWidth());
                float sy = Math.max(0.15f, source.getHeight() / (float) card.getHeight());
                card.setPivotX(0f);
                card.setPivotY(0f);
                card.animate().alpha(0f).scaleX(sx).scaleY(sy)
                        .translationX(start[0] - target[0])
                        .translationY(start[1] - target[1])
                        .setDuration(230)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            } else {
                card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                        .translationY(dp(24)).setDuration(210)
                        .setInterpolator(new DecelerateInterpolator(1.4f)).start();
            }
        }
        overlay.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (overlay.getParent() instanceof ViewGroup) {
                    ((ViewGroup) overlay.getParent()).removeView(overlay);
                }
                setMainCardBlurred(false);
                refreshLogRowStyles();
            }
        }, 250L);
    }

    private void showLogOptions() {
        if (optionsDialog != null) return;
        final int[] retention = {prefs.perfCleanupHours()};
        final int[] transparency = {prefs.builtinTransparency()};

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99070A12);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        final GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(transparency[0] / 100f);
        card.setClickable(true);
        card.setPadding(dp(22), dp(16), dp(22), dp(14));
        int sw = getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(dp(520), sw - dp(48));
        FrameLayout cardClip = new FrameLayout(this);
        cardClip.setBackgroundColor(Color.TRANSPARENT);
        cardClip.setClipToOutline(true);
        cardClip.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                float r = dp(26);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(cardClip, cardLp);
        cardClip.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("LOG OPTIONS", 10f, accent, false);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(8), 0, dp(2), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
            }
        });
        attachOptionTouch(close);
        header.addView(close);
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = text("Apply to every saved performance session.", 12f, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        card.addView(subtitle);

        addDetailSection(card, "AUTO-CLEAN ALL LOGS");
        TextView retentionHint = text("Delete saved sessions for every app older than the selected age. Off keeps every session.",
                10.5f, MUTED, false);
        retentionHint.setPadding(0, dp(2), 0, dp(7));
        card.addView(retentionHint);

        final int[] values = {0, 1, 3, 6, 12, 24};
        final String[] labels = {"Off", "1h", "3h", "6h", "12h", "24h"};
        final List<TextView> retentionChips = new ArrayList<>();
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            final TextView chip = text(labels[i], 10f, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            chip.setDefaultFocusHighlightEnabled(false);
            chip.setPadding(dp(7), dp(6), dp(7), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < values.length - 1) lp.rightMargin = dp(4);
            retentionChips.add(chip);
            styleCleanupChip(chip, value == retention[0]);
            attachOptionTouch(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retention[0] = value;
                    prefs.setPerfCleanupHours(value);
                    store.cleanupAll(value);
                    for (int j = 0; j < retentionChips.size(); j++) {
                        styleCleanupChip(retentionChips.get(j), values[j] == retention[0]);
                    }
                    refresh();
                    Haptics.click(v);
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean focused) {
                    if (focused) {
                        v.setBackground(Ui.focusRing(PerfLogActivity.this, dp(10)));
                        Haptics.tick(v);
                    } else {
                        styleCleanupChip(chip, value == retention[0]);
                    }
                }
            });
            chipRow.addView(chip, lp);
        }
        card.addView(chipRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addDetailSection(card, "GLASS TRANSPARENCY");
        LinearLayout transparencyRow = new LinearLayout(this);
        transparencyRow.setOrientation(LinearLayout.HORIZONTAL);
        transparencyRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView transparencyLabel = text("Log glass", 12f, 0xE6FFFFFF, false);
        transparencyRow.addView(transparencyLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView transparencyValue = text(transparency[0] + "%", 10f, accent, true);
        transparencyValue.setGravity(Gravity.CENTER);
        transparencyValue.setMinWidth(dp(48));
        transparencyRow.addView(transparencyValue);
        card.addView(transparencyRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final SeekBar transparencyBar = new SeekBar(this);
        transparencyBar.setFocusable(true);
        transparencyBar.setFocusableInTouchMode(true);
        transparencyBar.setMax(80);
        transparencyBar.setProgress(Math.max(0, transparency[0] - 20));
        transparencyBar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        transparencyBar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        transparencyBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        transparencyBar.setPadding(dp(10), dp(2), dp(10), 0);
        transparencyBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                transparency[0] = 20 + progress;
                transparencyValue.setText(transparency[0] + "%");
                prefs.setSettingsTransparency(transparency[0]);
                float alpha = transparency[0] / 100f;
                if (mainCard != null) mainCard.setSurfaceAlpha(alpha);
                if (detailCard != null) detailCard.setSurfaceAlpha(alpha);
                if (fromUser) Haptics.tick(seekBar);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.key(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.click(seekBar);
            }
        });
        card.addView(transparencyBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        transparencyBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused
                        ? Ui.focusRing(PerfLogActivity.this, dp(12)) : null);
                if (focused) Haptics.tick(v);
            }
        });
        TextView transparencyHint = text("Applies to both the log list and session detail cards. Shared with Controller Remapper.",
                10.5f, MUTED, false);
        transparencyHint.setPadding(0, dp(2), 0, 0);
        card.addView(transparencyHint);

        addDetailSection(card, "CONTROLLER SHORTCUT");
        final TextView mapButton = text("Map long press: "
                + PavoA11yService.perfLogControllerLabel(prefs), 12f, 0xE6FFFFFF, false);
        mapButton.setPadding(dp(12), dp(9), dp(12), dp(9));
        mapButton.setBackground(Ui.rounded(0x18FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
        mapButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
                capturingPerfLogShortcut = true;
                perfLogMappedDown = null;
                handler.removeCallbacks(finishPerfLogCapture);
                handler.postDelayed(finishPerfLogCapture, 5000L);
                LToast.makeText(PerfLogActivity.this,
                        "Press a controller button to open Perf Log options",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mapLp.topMargin = dp(4);
        card.addView(mapButton, mapLp);
        TextView mapHint = text("Hold the mapped button while in Perf Logs to open this popup.",
                10.5f, MUTED, false);
        mapHint.setPadding(0, dp(4), 0, 0);
        card.addView(mapHint);

        TextView done = text("DONE", 10f, 0xFF061015, false);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(16), dp(9), dp(16), dp(9));
        done.setBackground(Ui.rounded(accent, dp(12)));
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doneLp.topMargin = dp(12);
        card.addView(done, doneLp);

        final List<View> nav = new ArrayList<>();
        nav.addAll(retentionChips);
        nav.add(transparencyBar);
        nav.add(done);
        attachOptionTouch(done);
        attachOptionTouch(mapButton);
        attachOptionFocus(done, true);
        attachOptionFocus(mapButton, false);

        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface d, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.dismiss();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    View focused = dialog.getCurrentFocus();
                    if (focused == transparencyBar) return true;
                    if (focused != null) focused.performClick();
                    else retentionChips.get(0).requestFocus();
                    return true;
                }
                int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1
                        : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1
                        : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                if (dx != 0 || dy != 0) {
                    int multiplier = event.getRepeatCount() > 0
                            ? Math.min(6, 1 + event.getRepeatCount() / 4) : 1;
                    moveOptionsFocus(retentionChips, transparencyBar, mapButton, done,
                            dx, dy, multiplier);
                    return true;
                }
                return false;
            }
        });

        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                optionsDialog = null;
                setMainCardBlurred(false);
                refreshLogRowStyles();
                hideBars();
            }
        });
        optionsDialog = dialog;
        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface d, int keyCode, KeyEvent event) {
                return (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        && dialog.getCurrentFocus() == transparencyBar;
            }
        });
        setMainCardBlurred(true);
        dialog.setContentView(overlay);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;
            window.setAttributes(lp);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.getDecorView().setBackgroundColor(Color.TRANSPARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getDecorView()
                        .getWindowInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        }
        final long[] optionSliderStickAt = {0L};
        View.OnGenericMotionListener optionsStick = new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                int source = event.getSource();
                if ((source & InputDevice.SOURCE_JOYSTICK) == 0
                        && (source & InputDevice.SOURCE_GAMEPAD) == 0) return false;
                if (optionsDialog != null && optionsDialog.getCurrentFocus() == transparencyBar) {
                    float rx = SliderStick.rightX(event);
                    if (SliderStick.active(rx)) {
                        SliderStick.adjust(transparencyBar, rx, optionSliderStickAt, transparencyBar);
                        return true;
                    }
                }
                float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                if (Math.abs(x) < 0.5f) x = event.getAxisValue(MotionEvent.AXIS_X);
                if (Math.abs(x) < 0.5f) x = event.getAxisValue(MotionEvent.AXIS_RX);
                float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
                if (Math.abs(y) < 0.5f) y = event.getAxisValue(MotionEvent.AXIS_Y);
                if (Math.abs(y) < 0.5f) y = StickScroll.rightY(event);
                if (Math.abs(x) < 0.45f && Math.abs(y) < 0.45f) return true;
                long now = SystemClock.uptimeMillis();
                if (now - lastOptionsStickAt < 160L) return true;
                lastOptionsStickAt = now;
                int dx = Math.abs(x) >= Math.abs(y) ? (x > 0 ? 1 : -1) : 0;
                int dy = dx == 0 ? (y > 0 ? 1 : -1) : 0;
                moveOptionsFocus(retentionChips, transparencyBar, mapButton, done, dx, dy, 1);
                return true;
            }
        };
        overlay.setOnGenericMotionListener(optionsStick);
        if (window != null) window.getDecorView().setOnGenericMotionListener(optionsStick);
        nav.get(0).post(new Runnable() {
            @Override
            public void run() {
                nav.get(0).requestFocus();
            }
        });
    }

    private void moveOptionsFocus(List<? extends View> chips, SeekBar slider,
                                  View mapButton, View done, int dx, int dy,
                                  int multiplier) {
        Dialog dialog = optionsDialog;
        if (dialog == null) return;
        View focused = dialog.getCurrentFocus();
        int chipIndex = chips.indexOf(focused);
        int row = chipIndex >= 0 ? 0
                : (focused == slider ? 1 : focused == mapButton ? 2
                : focused == done ? 3 : -1);
        if (row < 0) {
            chips.get(0).requestFocus();
            Haptics.tick(chips.get(0));
            return;
        }
        if (dy != 0) {
            View target;
            if (row == 0) target = dy > 0 ? slider : done;
            else if (row == 1) target = dy > 0 ? mapButton : chips.get(0);
            else if (row == 2) target = dy > 0 ? done : slider;
            else target = dy > 0 ? chips.get(0) : mapButton;
            target.requestFocus();
            Haptics.tick(target);
            return;
        }
        if (dx == 0) return;
        if (row == 0) {
            int next = Math.max(0, Math.min(chips.size() - 1, chipIndex + dx));
            if (next != chipIndex) {
                chips.get(next).requestFocus();
                Haptics.tick(chips.get(next));
            }
        }
    }

    private void attachOptionTouch(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        v.requestFocus();
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                }
                return false;
            }
        });
    }

    private void attachOptionFocus(final View view, final boolean accent) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setDefaultFocusHighlightEnabled(false);
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                if (focused) {
                    v.setBackground(Ui.focusRing(PerfLogActivity.this, dp(12)));
                    Haptics.tick(v);
                } else {
                    v.setBackground(Ui.rounded(accent
                                    ? PerfLogActivity.this.accent : 0x22FFFFFF,
                            dp(12), accent ? 0x00000000 : 0x33FFFFFF, dp(1)));
                }
            }
        });
    }

    private void styleCleanupChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? 0xFF061015 : 0xE6FFFFFF);
        chip.setBackground(selected
                ? Ui.rounded(accent, dp(10))
                : Ui.rounded(0x18FFFFFF, dp(10), 0x33FFFFFF, dp(1)));
    }

    private TextView chip(String label, int color) {
        TextView t = text(label, 10f, color, true);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setBackground(Ui.rounded(0x18FFFFFF, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(5);
        return t;
    }

    private LinearLayout detailRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addDetailCell(LinearLayout row, String label, String value, int color) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(0, dp(2), dp(16), dp(2));
        TextView name = text(label, 9f, DETAIL_MUTED, false);
        name.setLetterSpacing(0.08f);
        cell.addView(name);
        TextView v = text(value, 15f, color, true);
        v.setPadding(0, dp(1), 0, 0);
        cell.addView(v);
        row.addView(cell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void addDetailSection(LinearLayout parent, String title) {
        TextView t = text(title, 9f, DETAIL_LABEL, false);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(2);
        parent.addView(t, lp);
    }

    private static String fmtPower(float watts) {
        if (watts <= 0f) return "\u2014";
        if (watts < 1f) return Math.round(watts * 1000f) + " mW";
        return String.format(Locale.US, "%.1f W", watts);
    }

    private TextView text(CharSequence s, float sp, int color, boolean mono) {
        TextView t = new LTextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    private void addColored(SpannableStringBuilder b, String s, int color, boolean mono) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (mono) {
            b.setSpan(new android.text.style.TypefaceSpan("monospace"), start, b.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int throttleColor(String value) {
        if ("THERMAL".equals(value)) return 0xFFFF5C6C;
        if ("CPU CAP".equals(value) || "GPU CAP".equals(value)) return 0xFFFFC857;
        return 0xFF69D98B;
    }

    private static String formatDuration(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String fmtBytes(long bytes) {
        if (bytes <= 0) return "\u2014";
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    /** Recorded FPS plus CPU/GPU temperature and load history. */
    private static final class LogGraph extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint legend = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path fillPath = new Path();
        private final RectF box = new RectF();
        private final float density;
        private final int touchSlop;
        private int[] values = new int[0];
        private int[] cpuValues = new int[0];
        private int[] gpuValues = new int[0];
        private int[] cpuUse = new int[0];
        private int[] gpuUse = new int[0];
        private float scrollX;
        private float downX, downY;
        private float downScroll;
        private boolean horizontalDrag;
        private int contentWidthPx;

        LogGraph(android.content.Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
            setWillNotDraw(false);
            grid.setStrokeWidth(Math.max(1f, density * 0.7f));
            line.setStrokeWidth(Math.max(1.4f, density * 1.6f));
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            fill.setStyle(Paint.Style.FILL);
            legend.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            legend.setTextSize(dp(7.8f));
            axis.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            axis.setTextSize(dp(6.4f));
        }

        void setHistory(List<Integer> history, List<Integer> cpuHistory,
                        List<Integer> gpuHistory, List<Integer> cpuUsage,
                        List<Integer> gpuUsage) {
            values = toArray(history);
            cpuValues = toArray(cpuHistory);
            gpuValues = toArray(gpuHistory);
            cpuUse = toArray(cpuUsage);
            gpuUse = toArray(gpuUsage);
            int samples = Math.max(Math.max(values.length, cpuValues.length),
                    Math.max(gpuValues.length, Math.max(cpuUse.length, gpuUse.length)));
            contentWidthPx = Math.round(Math.max(1, samples - 1) * dp(16));
            scrollX = clampScroll(scrollX);
            invalidate();
        }

        private int[] toArray(List<Integer> list) {
            int[] out = new int[list == null ? 0 : list.size()];
            for (int i = 0; i < out.length; i++) out[i] = list.get(i);
            return out;
        }

        private float plotLeft() { return dp(30); }
        private float plotRight() { return Math.max(plotLeft() + 1, getWidth() - dp(32)); }
        private float plotTop() { return dp(29); }
        private float plotBottom() { return Math.max(plotTop() + 1, getHeight() - dp(5)); }
        private float maxScroll() {
            return Math.max(0, contentWidthPx - (plotRight() - plotLeft()));
        }
        private float clampScroll(float value) {
            return Math.max(0, Math.min(maxScroll(), value));
        }
        private int sampleCount() {
            return Math.max(Math.max(values.length, cpuValues.length),
                    Math.max(gpuValues.length, Math.max(cpuUse.length, gpuUse.length)));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (maxScroll() <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    downScroll = scrollX;
                    horizontalDrag = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (!horizontalDrag) {
                        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                            horizontalDrag = true;
                            if (getParent() != null) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        } else if (Math.abs(dy) > touchSlop) {
                            return false;
                        }
                    }
                    if (!horizontalDrag) return true;
                    scrollX = clampScroll(downScroll - dx);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean consumed = horizontalDrag;
                    horizontalDrag = false;
                    return consumed;
                default:
                    return horizontalDrag;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 2 || h <= 2) return;
            float r = dp(8);
            box.set(0, 0, w, h);
            bg.setColor(0x22101820);
            canvas.drawRoundRect(box, r, r, bg);

            int lastFps = last(values);
            int lastCpu = last(cpuValues);
            int lastGpu = last(gpuValues);
            int lastCpuUse = last(cpuUse);
            int lastGpuUse = last(gpuUse);
            int fpsColor = PerfOverlayView.fpsColor(lastFps);
            int cpuColor = 0xFFFF8A65;
            int gpuColor = 0xFF64B5F6;
            int cpuUseColor = 0xFFFFC857;
            int gpuUseColor = 0xFF7CF7C4;

            canvas.drawText("FPS " + (lastFps > 0 ? lastFps : "--"),
                    dp(8), dp(11), tint(legend, fpsColor));
            canvas.drawText("CPU " + (lastCpu > 0 ? lastCpu + "°" : "--"),
                    dp(58), dp(11), tint(legend, cpuColor));
            canvas.drawText("GPU " + (lastGpu > 0 ? lastGpu + "°" : "--"),
                    dp(145), dp(11), tint(legend, gpuColor));
            canvas.drawText("CPU " + lastCpuUse + "%", dp(58), dp(21),
                    tint(legend, cpuUseColor));
            canvas.drawText("GPU " + lastGpuUse + "%", dp(145), dp(21),
                    tint(legend, gpuUseColor));

            float left = plotLeft();
            float right = plotRight();
            float top = plotTop();
            float bottom = plotBottom();
            float height = bottom - top;
            float visibleWidth = right - left;
            int samples = sampleCount();
            float content = Math.max(visibleWidth, contentWidthPx);
            scrollX = Math.max(0, Math.min(content - visibleWidth, scrollX));
            float step = samples > 1 ? content / (samples - 1f) : visibleWidth;

            // Horizontal percentage grid fixed to the card.
            grid.setColor(0x1FFFFFFF);
            axis.setColor(0x99FFFFFF);
            for (int pct = 0; pct <= 100; pct += 10) {
                float y = bottom - pct / 100f * height;
                canvas.drawLine(left, y, right, y, grid);
                if (h >= dp(130) || pct % 50 == 0) {
                    canvas.drawText(pct + "%", dp(2), y + dp(2.2f), axis);
                }
            }

            int minTemp = 0;
            int maxTemp = 0;
            boolean tempValid = false;
            for (int v : cpuValues) if (v > 0) { minTemp = tempValid ? Math.min(minTemp, v) : v; maxTemp = tempValid ? Math.max(maxTemp, v) : v; tempValid = true; }
            for (int v : gpuValues) if (v > 0) { minTemp = tempValid ? Math.min(minTemp, v) : v; maxTemp = tempValid ? Math.max(maxTemp, v) : v; tempValid = true; }
            if (tempValid) {
                if (maxTemp - minTemp < 6) {
                    minTemp -= 3;
                    maxTemp += 3;
                }
                minTemp = Math.max(10, minTemp - 1);
                maxTemp = Math.min(120, maxTemp + 1);
                if (maxTemp <= minTemp) maxTemp = minTemp + 1;
                int labelGap = Math.max(1, (int) Math.ceil((maxTemp - minTemp)
                        / Math.max(1f, height / dp(8.5f))));
                for (int v = minTemp; v <= maxTemp; v += labelGap) {
                    float y = bottom - (v - minTemp) / (float) (maxTemp - minTemp) * height;
                    canvas.drawText(v + "°", right + dp(4), y + dp(2.2f), axis);
                }
            }

            canvas.save();
            canvas.clipRect(left, top, right, bottom);
            // Vertical sample/time divisions scroll with the data.
            int division = Math.max(1, (samples - 1) / 6);
            for (int i = division; i < samples; i += division) {
                float x = left + i * step - scrollX;
                if (x >= left && x <= right) canvas.drawLine(x, top, x, bottom, grid);
            }
            drawFps(canvas, values, fpsColor, left, top, bottom, step, scrollX);
            drawTemp(canvas, cpuValues, cpuColor, minTemp, maxTemp,
                    left, top, bottom, step, scrollX);
            drawTemp(canvas, gpuValues, gpuColor, minTemp, maxTemp,
                    left, top, bottom, step, scrollX);
            drawUsage(canvas, cpuUse, cpuUseColor, left, top, bottom, step, scrollX);
            drawUsage(canvas, gpuUse, gpuUseColor, left, top, bottom, step, scrollX);
            canvas.restore();

            // Scroll affordance: a tiny track along the bottom edge.
            if (content > visibleWidth + 1f) {
                float trackY = h - dp(2.5f);
                grid.setColor(0x33FFFFFF);
                canvas.drawLine(left, trackY, right, trackY, grid);
                float thumbW = Math.max(dp(24), visibleWidth * visibleWidth / content);
                float thumbX = left + (visibleWidth - thumbW) * scrollX
                        / Math.max(1f, content - visibleWidth);
                grid.setColor(0x99FFFFFF);
                grid.setStrokeWidth(Math.max(1.5f, density * 1.2f));
                canvas.drawLine(thumbX, trackY, thumbX + thumbW, trackY, grid);
                grid.setStrokeWidth(Math.max(1f, density * 0.7f));
            }
        }

        private Paint tint(Paint p, int color) {
            p.setColor(color);
            return p;
        }

        private int last(int[] a) {
            return a.length == 0 ? 0 : a[a.length - 1];
        }

        private float xAt(int index, float left, float step, float scroll) {
            return left + index * step - scroll;
        }

        private void drawFps(Canvas c, int[] data, int color,
                             float left, float top, float bottom,
                             float step, float scroll) {
            if (data.length < 2) return;
            int max = 60;
            for (int v : data) max = Math.max(max, v);
            max = Math.max(30, Math.min(240, max));
            path.reset();
            fillPath.reset();
            for (int i = 0; i < data.length; i++) {
                float x = xAt(i, left, step, scroll);
                float y = bottom - Math.min(data[i], max) / (float) max * (bottom - top);
                if (i == 0) {
                    path.moveTo(x, y);
                    fillPath.moveTo(x, bottom);
                    fillPath.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
            }
            fillPath.lineTo(xAt(data.length - 1, left, step, scroll), bottom);
            fillPath.close();
            fill.setShader(new LinearGradient(0, top, 0, bottom,
                    (color & 0x00FFFFFF) | 0x44000000,
                    (color & 0x00FFFFFF) | 0x04000000, Shader.TileMode.CLAMP));
            c.drawPath(fillPath, fill);
            fill.setShader(null);
            line.setColor(color);
            c.drawPath(path, line);
        }

        private void drawTemp(Canvas c, int[] data, int color, int min, int max,
                              float left, float top, float bottom,
                              float step, float scroll) {
            if (data.length < 2 || max <= min) return;
            path.reset();
            boolean started = false;
            for (int i = 0; i < data.length; i++) {
                int v = data[i];
                if (v <= 0) { started = false; continue; }
                float x = xAt(i, left, step, scroll);
                float y = bottom - (Math.max(min, Math.min(max, v)) - min)
                        / (float) (max - min) * (bottom - top);
                if (!started) { path.moveTo(x, y); started = true; }
                else path.lineTo(x, y);
            }
            line.setColor(color);
            line.setStrokeWidth(Math.max(1.2f, density * 1.35f));
            c.drawPath(path, line);
            line.setStrokeWidth(Math.max(1.4f, density * 1.6f));
        }

        private void drawUsage(Canvas c, int[] data, int color,
                               float left, float top, float bottom,
                               float step, float scroll) {
            if (data.length < 2) return;
            path.reset();
            for (int i = 0; i < data.length; i++) {
                float x = xAt(i, left, step, scroll);
                float y = bottom - Math.max(0, Math.min(100, data[i])) / 100f * (bottom - top);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            line.setColor(color);
            line.setStrokeWidth(Math.max(1f, density * 1.1f));
            line.setPathEffect(new android.graphics.DashPathEffect(
                    new float[]{dp(3), dp(3)}, 0f));
            c.drawPath(path, line);
            line.setPathEffect(null);
            line.setStrokeWidth(Math.max(1.4f, density * 1.6f));
        }

        private float dp(float v) {
            return v * density;
        }
    }
}
