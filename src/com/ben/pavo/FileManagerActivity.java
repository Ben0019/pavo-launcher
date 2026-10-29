package com.ben.pavo;

import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.content.ClipData;
import android.content.ClipDescription;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in two-pane file manager. */
public final class FileManagerActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF5FFFFFF;
    private static final int MUTED = 0xB8FFFFFF;

    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_DATE = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<File> clipboard = new ArrayList<>();

    private Prefs prefs;
    private GlassPanel sheet;
    private LinearLayout panes;
    private LinearLayout actionRow;
    private HorizontalScrollView actionScroll;
    private TextView clipboardLabel;
    private TextView status;
    private Pane leftPane;
    private Pane rightPane;
    private Pane activePane;
    private Dialog openDialog;
    private View permissionBanner;

    private File internalRoot;
    private File removableRoot;
    private boolean hasRemovable;
    private String pendingLeftPath;
    private String pendingRightPath;
    private boolean pendingLeftActive;

    private int sortMode = SORT_NAME;
    private boolean sortAscending = true;
    private boolean showHidden;
    private boolean clipboardCut;
    private boolean loaded;
    private boolean editorOpen;
    private View dragSourceView;
    private Pane dragSourcePane;
    private boolean lastAllFiles;
    private ControllerNav controllerNav;
    private View controllerRoot;
    private boolean initialFocusDone;
    private View controllerAFocus;
    private boolean controllerALongFired;
    private long lastRowNavAt;
    private boolean stickRowEngaged;
    private boolean cursorMode = true;
    private LinearLayout headerBar;
    private boolean chromeStickLatched;
    private boolean stickLatched;
    private long lastCursorMoveAt;
    private int heldDir;
    private long holdStartedAt;
    private final Runnable holdScroll = new Runnable() {
        @Override public void run() {
            if (heldDir == 0 || !cursorMode) { heldDir = 0; return; }
            moveCursor(heldDir);
            long held = SystemClock.uptimeMillis() - holdStartedAt;
            long interval = held > 1600L ? 40L : held > 900L ? 55L : 80L;
            main.postDelayed(this, interval);
        }
    };
    private long lastDpadKeyAt;
    private Pane navPane;
    private int navIndex;
    private final long[] stickScrollAt = {0L};
    private final List<View> dialogFocusOrder = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        ensureWindowBackdrop();
        hidePerformanceOverlay();
        readPrefs();
        internalRoot = Environment.getExternalStorageDirectory();
        if (internalRoot == null) internalRoot = getFilesDir();
        removableRoot = findRemovableRoot();
        hasRemovable = removableRoot != null && !removableRoot.equals(internalRoot);
        if (!hasRemovable) removableRoot = internalRoot;
        if (savedInstanceState != null) {
            pendingLeftPath = savedInstanceState.getString("fm_left");
            pendingRightPath = savedInstanceState.getString("fm_right");
            pendingLeftActive = savedInstanceState.getBoolean("fm_left_active", true);
        }

        buildUi();
        hideBars();
        lastAllFiles = hasAllFiles();
        reloadAll();
        restorePanePaths();
        main.postDelayed(new Runnable() {
            @Override public void run() { focusFirstFile(false); }
        }, 420L);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (leftPane != null && leftPane.dir != null) {
            outState.putString("fm_left", leftPane.dir.getAbsolutePath());
        }
        if (rightPane != null && rightPane.dir != null) {
            outState.putString("fm_right", rightPane.dir.getAbsolutePath());
        }
        outState.putBoolean("fm_left_active",
                activePane != null && activePane == leftPane);
    }

    private void restorePanePaths() {
        if (pendingLeftPath == null && pendingRightPath == null) return;
        boolean changed = false;
        if (pendingLeftPath != null) {
            File dir = new File(pendingLeftPath);
            if (dir.isDirectory()) {
                leftPane.dir = dir;
                changed = true;
            }
        }
        if (pendingRightPath != null) {
            File dir = new File(pendingRightPath);
            if (dir.isDirectory()) {
                rightPane.dir = dir;
                changed = true;
            }
        }
        if (changed) reloadAll();
        if (pendingLeftActive) setActive(leftPane);
        pendingLeftPath = null;
        pendingRightPath = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        hidePerformanceOverlay();
        if (editorOpen) {
            editorOpen = false;
            reloadAll();
        }
        boolean access = hasAllFiles();
        if (access && permissionBanner != null) {
            permissionBanner.setVisibility(View.GONE);
        }
        if (!loaded || (access && !lastAllFiles)) reloadAll();
        lastAllFiles = access;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (controllerNav != null) controllerNav.release();
        dismissDialog();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (controllerNav != null) controllerNav.cancel();
        main.removeCallbacks(controllerALongPress);
        controllerAFocus = null;
        controllerALongFired = false;
    }

    @Override
    protected boolean readableTextPass() {
        return false;
    }

    @Override
    public void onBackPressed() {
        if (openDialog != null && openDialog.isShowing()) {
            openDialog.dismiss();
            return;
        }
        if (activePane != null) {
            if (activePane.query != null && !activePane.query.isEmpty()) {
                activePane.query = "";
                activePane.clearSelection();
                reload(activePane);
                Haptics.tick(activePane.card);
                return;
            }
            if (!activePane.selected.isEmpty()) {
                activePane.clearSelection();
                Haptics.tick(activePane.card);
                return;
            }
            if (activePane.dir != null && activePane.root != null
                    && !activePane.dir.getAbsolutePath()
                            .equals(activePane.root.getAbsolutePath())) {
                goUp(activePane);
                Haptics.tick(activePane.card);
                return;
            }
        }
        // At the file manager's home screen there is no previous folder.
        // Device Back closes the app here, using the shared Pavo close motion.
        finishAnimated();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (openDialog != null && handleDialogKey(event)) return true;
        if (openDialog == null && ControllerNav.isSyntheticStickDpad(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            if (!cursorMode) {
                backToCursor();
                return true;
            }
            onBackPressed();
            return true;
        }
        if (openDialog == null
                && (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R1)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            switchControllerPane(event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L1);
            return true;
        }
        if (openDialog == null && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() > 0) return true;
                controllerALongFired = false;
                if (cursorMode) {
                    // Hold A selects; a normal press opens. Both act on the
                    // cursor, never on Android focus.
                    main.postDelayed(cursorLongPress, 380L);
                } else {
                    controllerAFocus = getCurrentFocus();
                    if (controllerAFocus == null && controllerNav != null) {
                        controllerNav.focusFirst();
                        controllerAFocus = getCurrentFocus();
                    }
                    if (controllerAFocus != null) {
                        main.postDelayed(controllerALongPress, 380L);
                    }
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                main.removeCallbacks(cursorLongPress);
                main.removeCallbacks(controllerALongPress);
                boolean longFired = controllerALongFired;
                controllerALongFired = false;
                if (!longFired) {
                    if (cursorMode) activateCursor();
                    else if (controllerAFocus != null) controllerAFocus.performClick();
                }
                controllerAFocus = null;
                return true;
            }
            return true;
        }
        if ((event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_X
                || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Y)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && openDialog == null) {
            Pane pane = navPane != null ? navPane : activePane;
            if (pane != null && !pane.files.isEmpty()) {
                File file = pane.files.get(clampIndex(pane));
                setActive(pane, false);
                cursorMode = true;
                toggleSelected(pane, file);
                refreshRowRings();
                Haptics.tick(pane.list);
                return true;
            }
        }
        if (openDialog == null && ControllerNav.isDirectionKey(event)) {
            // The Odin emits HAT axes alongside the D-pad; mark the key so the
            // analogue path does not move the cursor a second time.
            ControllerNav.noteExternalDpad(event);
            int code = event.getKeyCode();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            boolean repeat = down && event.getRepeatCount() > 0;
            View navFocus = getCurrentFocus();
            boolean toolbarFocus = navFocus != null && !cursorMode
                    && (isDescendant(actionRow, navFocus)
                            || navFocus == (activePane == null ? null : activePane.title));
            if (!cursorMode) {
                // Chrome has focus: the shared navigator moves between buttons.
                return false;
            }
            if (cursorMode) {
                lastDpadKeyAt = SystemClock.uptimeMillis();
                if (!down) return true;
                long now = SystemClock.uptimeMillis();
                if (repeat && now - lastCursorMoveAt < 80L) return true;
                lastCursorMoveAt = now;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    if ((navPane != null ? navPane : activePane) != null
                            && clampIndex(navPane != null ? navPane : activePane) == 0
                            && navPane != null && navPane.title != null) {
                        cursorMode = false;
                        navPane.title.requestFocus();
                    } else {
                        moveCursor(-1);
                    }
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    Pane pane = navPane != null ? navPane : activePane;
                    if (pane != null && !pane.files.isEmpty()
                            && clampIndex(pane) == pane.files.size() - 1) {
                        enterChrome(false);
                    } else {
                        startHoldScroll(1);
                        moveCursor(1);
                    }
                } else {
                    stopHoldScroll();
                    switchControllerPane(code == KeyEvent.KEYCODE_DPAD_LEFT);
                }
                return true;
            }
            // Toolbar focus: DOWN returns to the file cursor.
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && down && !repeat) {
                cursorMode = true;
                refreshRowRings();
                moveCursor(0);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && !repeat) {
                    switchControllerPane(code == KeyEvent.KEYCODE_DPAD_LEFT);
                    return true;
                }
            }
        }
        if (openDialog == null && controllerNav != null
                && controllerNav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (openDialog == null && event != null
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int source = event.getSource();
            boolean pad = (source & android.view.InputDevice.SOURCE_GAMEPAD) != 0
                    || (source & android.view.InputDevice.SOURCE_JOYSTICK) != 0;
            if (pad && !cursorMode) {
                float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
                if (ry > 0.62f) {
                    if (!chromeStickLatched) {
                        chromeStickLatched = true;
                        backToCursor();
                    }
                    return true;
                }
                if (Math.abs(ry) < 0.38f) chromeStickLatched = false;
            }
            if (pad && cursorMode) {
                float x = event.getAxisValue(MotionEvent.AXIS_X);
                float y = event.getAxisValue(MotionEvent.AXIS_Y);
                float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
                if (Math.abs(hatX) > Math.abs(x)) x = hatX;
                if (Math.abs(hatY) > Math.abs(y)) y = hatY;
                float rx = event.getAxisValue(MotionEvent.AXIS_Z);
                float ry = event.getAxisValue(MotionEvent.AXIS_RZ);
                if (Math.abs(ry) >= 0.62f || Math.abs(rx) >= 0.72f) {
                    // Right stick always escapes the pane the way it points.
                    if (!chromeStickLatched) {
                        chromeStickLatched = true;
                        if (Math.abs(ry) >= 0.62f) enterChrome(ry < 0f);
                        else switchControllerPane(rx < 0f);
                    }
                    return true;
                }
                if (Math.abs(ry) < 0.38f && Math.abs(rx) < 0.45f) chromeStickLatched = false;
                if (ControllerNav.externalDpadRecent()) return true;
                if (Math.abs(y) >= 0.62f || Math.abs(x) >= 0.72f) {
                    if (!stickLatched) {
                        stickLatched = true;
                        if (Math.abs(y) >= Math.abs(x)) {
                            int dir = y > 0f ? 1 : -1;
                            startHoldScroll(dir);
                            moveCursor(dir);
                        } else {
                            stopHoldScroll();
                            switchControllerPane(x < 0f);
                        }
                    }
                    return true;
                }
                if (Math.abs(y) < 0.38f && Math.abs(x) < 0.45f) {
                    if (stickLatched) stopHoldScroll();
                    stickLatched = false;
                }
                return true;
            }
        }
        if (openDialog == null && controllerNav != null
                && controllerNav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    private void focusFirstFile(boolean force) {
        if (activePane == null || controllerNav == null) return;
        if (!force && getCurrentFocus() != null) return;
        focusRow(activePane, 0);
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

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private static void disableDefaultFocusHighlight(View view) {
        if (view == null) return;
        view.setDefaultFocusHighlightEnabled(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                disableDefaultFocusHighlight(group.getChildAt(i));
            }
        }
    }

    private void ensureWindowBackdrop() {
        Backdrop existing = BuiltInBackdrop.take();
        if (existing != null) {
            BuiltInBackdrop.put(existing);
            return;
        }
        Backdrop captured = ScreenCaptureBackdrop.captureSharp(this);
        if (captured != null) BuiltInBackdrop.put(captured);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        sheet = new GlassPanel(this, null, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        sheet.setBackdropScreenAnchored(true);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(9));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sheetLp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, sheetLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), matchWrap());

        permissionBanner = buildPermissionBanner();
        column.addView(permissionBanner, matchWrap());

        panes = new LinearLayout(this);
        android.content.res.Configuration cfg = getResources().getConfiguration();
        boolean stacked = cfg.orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT
                || cfg.screenWidthDp < 520
                || cfg.screenHeightDp < 440
                || cfg.screenWidthDp / (float) Math.max(1, cfg.screenHeightDp) < 1.25f;
        panes.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams panesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panesLp.topMargin = dp(4);
        column.addView(panes, panesLp);

        leftPane = new Pane(false);
        rightPane = new Pane(true);
        if (stacked) {
            panes.addView(leftPane.card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            rightLp.topMargin = dp(5);
            panes.addView(rightPane.card, rightLp);
        } else {
            panes.addView(leftPane.card, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            rightLp.leftMargin = dp(5);
            panes.addView(rightPane.card, rightLp);
        }

        column.addView(buildFooter(), matchWrap());

        setContentView(root);
        setActive(leftPane, false);
        updatePermissionBanner();
        updateFooter();
        controllerRoot = root;
        controllerNav = new ControllerNav(this, root);
        controllerNav.decorateFocusables();
        disableDefaultFocusHighlight(root);
        // The controller owns a data cursor, not Android focus. Let the root
        // hold focus so labels and buttons never draw a second, stray ring.
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed() || controllerNav == null) return;
                controllerNav.decorateFocusables();
                disableDefaultFocusHighlight(root);
                root.requestFocus();
            }
        }, 350L);
    }

    private final Runnable cursorLongPress = new Runnable() {
        @Override public void run() {
            Pane pane = navPane != null ? navPane : activePane;
            if (pane == null || pane.files.isEmpty()) return;
            controllerALongFired = true;
            setActive(pane, false);
            toggleSelected(pane, pane.files.get(clampIndex(pane)));
            refreshRowRings();
            Haptics.longPress(pane.list);
        }
    };

    private int clampIndex(Pane pane) {
        if (pane == null || pane.files.isEmpty()) return 0;
        return Math.max(0, Math.min(pane.files.size() - 1, navIndex));
    }

    /**
     * Leave the file list for the chrome. up goes to the top bar, down to the
     * footer actions; the row ring is cleared so only the focused button is lit.
     */
    private void enterChrome(boolean top) {
        stopHoldScroll();
        cursorMode = false;
        refreshRowRings();
        if (controllerNav != null) controllerNav.decorateFocusables();
        View target = null;
        if (top) {
            target = firstFocusable(headerBar);
        } else {
            target = actionRow != null && actionRow.getChildCount() > 0
                    ? firstFocusable(actionRow) : null;
        }
        if (target == null) target = firstFocusable(controllerRoot);
        if (target != null) {
            target.setFocusableInTouchMode(true);
            target.requestFocus();
            Haptics.tick(target);
        }
    }

    private View firstFocusable(View view) {
        if (view == null) return null;
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE
                && view.isEnabled()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void backToCursor() {
        if (cursorMode) return;
        cursorMode = true;
        Pane pane = navPane != null ? navPane : activePane;
        if (pane == null) return;
        setActive(pane, false);
        refreshRowRings();
        scrollCursorIntoView(pane);
    }

    private void startHoldScroll(int dir) {
        main.removeCallbacks(holdScroll);
        heldDir = dir;
        holdStartedAt = SystemClock.uptimeMillis();
        main.postDelayed(holdScroll, 300L);
    }

    private void stopHoldScroll() {
        heldDir = 0;
        main.removeCallbacks(holdScroll);
    }

    private void moveCursor(int delta) {
        Pane pane = navPane != null ? navPane : activePane;
        if (pane == null || pane.files.isEmpty()) return;
        int next = clampIndex(pane) + delta;
        focusRow(pane, Math.max(0, Math.min(pane.files.size() - 1, next)));
    }

    private void activateCursor() {
        Pane pane = navPane != null ? navPane : activePane;
        if (pane == null || pane.files.isEmpty()) return;
        chooseFile(pane, pane.files.get(clampIndex(pane)));
    }

    private void scrollCursorIntoView(Pane pane) {
        if (pane == null || pane.list == null) return;
        final ListView list = pane.list;
        final int pos = clampIndex(pane);
        list.post(new Runnable() {
            @Override public void run() {
                int first = list.getFirstVisiblePosition();
                View child = list.getChildAt(pos - first);
                int top = list.getPaddingTop();
                int bottom = list.getHeight() - list.getPaddingBottom();
                if (child == null) {
                    list.setSelectionFromTop(pos, top);
                } else if (child.getTop() < top) {
                    list.setSelectionFromTop(pos, top);
                } else if (child.getBottom() > bottom) {
                    list.setSelectionFromTop(pos, bottom - child.getHeight());
                }
            }
        });
    }

    private final Runnable controllerALongPress = new Runnable() {
        @Override public void run() {
            View focus = controllerAFocus;
            if (focus == null) return;
            controllerALongFired = true;
            boolean handled = focus.performLongClick();
            if (handled) Haptics.longPress(focus);
        }
    };

    private void switchControllerPane(boolean left) {
        stopHoldScroll();
        Pane target = left ? leftPane : rightPane;
        if (target == null || target == navPane) return;
        focusRow(target, target.cursor);
        Haptics.tick(target.card);
    }

    /** The one place that moves the file cursor: state first, paint, scroll. */
    private void focusRow(Pane pane, int index) {
        if (pane == null) return;
        setActive(pane, false);
        cursorMode = true;
        navPane = pane;
        navIndex = pane.files.isEmpty()
                ? 0 : Math.max(0, Math.min(pane.files.size() - 1, index));
        pane.cursor = navIndex;
        refreshRowRings();
        scrollCursorIntoView(pane);
    }

    private static boolean isDescendant(ViewGroup parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        headerBar = header;
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(3));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("FILES", 18f, 0xFFFFFFFF, false);
        title.setLetterSpacing(0.13f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = text(hasRemovable ? "INTERNAL  ·  SD CARD" : "INTERNAL  ·  NO SD CARD",
                9.5f, MUTED, false);
        sub.setLetterSpacing(0.09f);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(headerButton("NEW", new Runnable() {
            @Override public void run() { showNewFolderDialog(); }
        }), headerLp(0));
        header.addView(headerButton("SORT", new Runnable() {
            @Override public void run() { showSortDialog(); }
        }), headerLp(5));
        header.addView(headerButton("SEARCH", new Runnable() {
            @Override public void run() { showSearchDialog(activePane); }
        }), headerLp(5));
        header.addView(headerButton("\u21bb", new Runnable() {
            @Override public void run() { reloadAll(); }
        }), headerLp(5));
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

    private LinearLayout.LayoutParams headerLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.leftMargin = dp(left);
        return lp;
    }

    private TextView headerButton(String label, final Runnable action) {
        TextView v = button(label, false);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        v.setMinWidth(0);
        v.setPadding(dp(9), 0, dp(9), 0);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        return v;
    }

    private View buildPermissionBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(9), dp(5), dp(7), dp(5));
        banner.setBackground(Ui.rounded(0x33FFC857, dp(10), 0x66FFC857, dp(1)));
        TextView label = text("ALL FILES ACCESS REQUIRED TO BROWSE STORAGE", 9f,
                0xFFFFD98A, false);
        label.setLetterSpacing(0.07f);
        banner.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grant = button("GRANT ACCESS", true);
        grant.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        grant.setPadding(dp(8), 0, dp(8), 0);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAllFilesAccess(); }
        });
        banner.addView(grant, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        return banner;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(4), 0, 0);
        clipboardLabel = text("", 9f, 0xE6FFFFFF, false);
        clipboardLabel.setLetterSpacing(0.06f);
        footer.addView(clipboardLabel, matchWrap());
        actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        return footer;
    }

    private void updateFooter() {
        if (actionRow == null) return;
        actionRow.removeAllViews();
        Pane p = activePane;
        if (p == null) return;
        if (!p.selected.isEmpty()) {
            clipboardLabel.setText(p.selected.size() + " "
                    + Loc.s(this, p.selected.size() == 1 ? "ITEM SELECTED" : "ITEMS SELECTED")
                    + "  ·  " + p.titleText());
            actionRow.addView(actionButton("COPY", new Runnable() {
                @Override public void run() { copySelection(false); }
            }), actionLp(0));
            actionRow.addView(actionButton("CUT", new Runnable() {
                @Override public void run() { copySelection(true); }
            }), actionLp(5));
            if (p.selected.size() == 1) {
                actionRow.addView(actionButton("RENAME", new Runnable() {
                    @Override public void run() { showRenameDialog(p.selected.iterator().next()); }
                }), actionLp(5));
            }
            actionRow.addView(actionButton("DELETE", new Runnable() {
                @Override public void run() { confirmDeleteSelection(); }
            }), actionLp(5));
            actionRow.addView(actionButton("SHARE", new Runnable() {
                @Override public void run() { shareSelection(); }
            }), actionLp(5));
            if (p.selected.size() == 1) {
                actionRow.addView(actionButton("INFO", new Runnable() {
                    @Override public void run() { showInfo(p.selected.iterator().next()); }
                }), actionLp(5));
            }
            actionRow.addView(actionButton("CANCEL", new Runnable() {
                @Override public void run() { clearSelection(); }
            }), actionLp(5));
        } else if (!clipboard.isEmpty()) {
            clipboardLabel.setText(clipboard.size() + " "
                    + Loc.s(this, clipboard.size() == 1 ? "ITEM" : "ITEMS")
                    + "  ·  " + Loc.s(this, clipboardCut ? "MOVE" : "COPY")
                    + "  ·  " + Loc.s(this, "PASTE INTO") + " " + p.titleText());
            actionRow.addView(actionButton("PASTE", true, new Runnable() {
                @Override public void run() { pasteClipboard(); }
            }), actionLp(0));
            actionRow.addView(actionButton("CLEAR", new Runnable() {
                @Override public void run() {
                    clipboard.clear();
                    updateFooter();
                }
            }), actionLp(5));
        } else {
            clipboardLabel.setText(Loc.s(this, "TAP TO OPEN  ·  LONG PRESS TO SELECT")
                    + "  ·  " + p.titleText());
            actionRow.addView(actionButton("REFRESH", new Runnable() {
                @Override public void run() { reloadAll(); }
            }), actionLp(0));
            actionRow.addView(actionButton("NEW FOLDER", new Runnable() {
                @Override public void run() { showNewFolderDialog(); }
            }), actionLp(5));
            actionRow.addView(actionButton("NEW FILE", new Runnable() {
                @Override public void run() { showNewFileDialog(); }
            }), actionLp(5));
            if (!hasRemovable) {
                actionRow.addView(actionButton("NO SD CARD", false, null), actionLp(5));
            }
        }
        if (actionRow != null) {
            actionRow.requestLayout();
            actionRow.post(new Runnable() {
                @Override public void run() { actionRow.requestLayout(); }
            });
        }
    }

    private LinearLayout.LayoutParams actionLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.leftMargin = dp(left);
        return lp;
    }

    private TextView actionButton(String label, Runnable action) {
        return actionButton(label, false, action);
    }

    private TextView actionButton(String label, boolean primary, final Runnable action) {
        TextView v = button(label, primary);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        v.setPadding(dp(10), 0, dp(10), 0);
        if (action != null) {
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { action.run(); }
            });
        } else {
            v.setEnabled(false);
            v.setAlpha(0.5f);
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Panes
    // ------------------------------------------------------------------

    private final class Pane {
        final boolean right;
        final FrameLayout card;
        final TextView title;
        final TextView path;
        final TextView free;
        final TextView empty;
        final ListView list;
        final DrawerAdapter adapter;
        final List<File> files = new ArrayList<>();
        final Set<File> selected = new HashSet<>();
        final File root;
        File dir;
        int cursor;
        String query = "";
        int loadToken;
        boolean suppressRowClick;

        Pane(boolean right) {
            this.right = right;
            root = right ? removableRoot : internalRoot;
            dir = root;
            card = new PaneCard(this);
            card.setBackground(paneBackground(false));
            card.setPadding(dp(8), dp(6), dp(8), dp(5));

            LinearLayout column = new LinearLayout(FileManagerActivity.this);
            column.setOrientation(LinearLayout.VERTICAL);
            card.addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout head = new LinearLayout(FileManagerActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            title = text(right ? (hasRemovable ? "SD CARD" : "INTERNAL") : "INTERNAL",
                    10.5f, 0xFFFFFFFF, false);
            title.setLetterSpacing(0.10f);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            // The pane title is a label; the controller cursor lives in the list.
            title.setFocusable(false);
            title.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setActive(Pane.this); }
            });
            head.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView up = button("\u2191", false);
            up.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            up.setMinWidth(dp(30));
            up.setPadding(0, 0, 0, dp(3));
            up.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (activePane != Pane.this) {
                        setActive(Pane.this);
                    } else {
                        goUp(Pane.this);
                    }
                }
            });
            head.addView(up, new LinearLayout.LayoutParams(dp(30), dp(28)));
            column.addView(head, matchWrap());

            path = plainText("", 9f, 0xB8FFFFFF, true);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            path.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setActive(Pane.this); }
            });
            column.addView(path, matchWrap());

            free = plainText("", 8.5f, 0xA0FFFFFF, true);
            free.setMaxLines(1);
            free.setPadding(0, dp(1), 0, dp(2));
            free.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setActive(Pane.this); }
            });
            column.addView(free, matchWrap());

            FrameLayout holder = new FrameLayout(FileManagerActivity.this);
            list = new ListView(FileManagerActivity.this);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            list.setVerticalScrollBarEnabled(false);
            list.setClipToPadding(true);
            list.setClipChildren(true);
            list.setPadding(0, dp(2), 0, dp(6));
            list.setFocusable(false);
            list.setFocusableInTouchMode(false);
            list.setItemsCanFocus(true);
            adapter = new DrawerAdapter(Pane.this);
            list.setAdapter(adapter);
            list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override public boolean onItemLongClick(AdapterView<?> parent, View view,
                                                         int position, long id) {
                    if (position < 0 || position >= files.size()) return false;
                    setActive(Pane.this);
                    toggleSelected(Pane.this, files.get(position));
                    Haptics.longPress(view);
                    return true;
                }
            });
            list.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (activePane != Pane.this) {
                            suppressRowClick = true;
                            setActive(Pane.this);
                        }
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        int pos = list.pointToPosition((int) event.getX(), (int) event.getY());
                        if (pos == ListView.INVALID_POSITION) setActive(Pane.this);
                        list.postDelayed(new Runnable() {
                            @Override public void run() { suppressRowClick = false; }
                        }, 180L);
                    }
                    return false;
                }
            });
            holder.addView(list, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            empty = plainText("", 10.5f, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setVisibility(View.GONE);
            empty.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setActive(Pane.this); }
            });
            holder.setClickable(true);
            holder.setFocusable(false);
            holder.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setActive(Pane.this); }
            });
            holder.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            list.setEmptyView(empty);
            column.addView(holder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            card.setFocusable(false);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setActive(Pane.this); }
            });
            card.setOnDragListener(new PaneDropListener(this));
        }

        String titleText() {
            if (query != null && !query.isEmpty()) return tr("SEARCH") + ": " + query;
            return title.getText().toString();
        }

        void clearSelection() {
            selected.clear();
            adapter.notifyDataSetChanged();
            if (activePane == this) updateFooter();
        }
    }

    private Drawable paneBackground(boolean active) {
        return Ui.rounded(active ? 0x4D0B1218 : 0x3D0B1218, dp(14),
                active ? 0xB3FFFFFF : 0x33FFFFFF, dp(active ? 1.6f : 1f));
    }

    private void setActive(Pane p, boolean haptic) {
        if (p == null) return;
        activePane = p;
        if (leftPane != null) leftPane.card.setBackground(paneBackground(leftPane == p));
        if (rightPane != null) rightPane.card.setBackground(paneBackground(rightPane == p));
        updateFooter();
        refreshRowRings();
        if (haptic) Haptics.tick(p.card);
    }

    /**
     * Rows are created per bind, so the row that just lost focus keeps the ring
     * it was built with. Repaint every visible row from the real state instead:
     * only the controller row in the active pane, plus multi-selected rows.
     */
    private void refreshRowRings() {
        if (leftPane != null) refreshRowRings(leftPane);
        if (rightPane != null) refreshRowRings(rightPane);
    }

    private void refreshRowRings(Pane pane) {
        if (pane == null || pane.list == null) return;
        ListView list = pane.list;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            Object tag = row == null ? null : row.getTag();
            if (!(tag instanceof File)) continue;
            File file = (File) tag;
            boolean controller = cursorMode && pane == navPane
                    && !pane.files.isEmpty()
                    && pane.files.indexOf(file) == navIndex;
            boolean selected = pane.selected.contains(file);
            row.setBackground(controller || selected
                    ? Ui.focusRing(FileManagerActivity.this, dp(10))
                    : Ui.ripple(FileManagerActivity.this, dp(10)));
        }
    }

    private void setActive(Pane p) {
        setActive(p, true);
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void reloadAll() {
        if (leftPane == null) return;
        reload(leftPane);
        reload(rightPane);
        loaded = true;
    }

    private void reload(final Pane pane) {
        final int token = ++pane.loadToken;
        final File dir = pane.dir;
        final String query = pane.query;
        final boolean hidden = showHidden;
        pane.files.clear();
        pane.adapter.notifyDataSetChanged();
        pane.free.setText(tr("LOADING") + "…");
        io.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<File> result = new ArrayList<>();
                String error = null;
                try {
                    if (query != null && !query.isEmpty()) {
                        walk(dir, query.toLowerCase(Locale.getDefault()), result, hidden, 0);
                    } else {
                        File[] children = dir == null ? null : dir.listFiles();
                        if (children != null) {
                            for (File f : children) {
                                if (!hidden && f.getName().startsWith(".")) continue;
                                result.add(f);
                            }
                        }
                    }
                    sort(result);
                } catch (Throwable t) {
                    error = t.getMessage();
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (token != pane.loadToken || isFinishing()) return;
                        pane.files.clear();
                        pane.files.addAll(result);
                        pane.adapter.notifyDataSetChanged();
                        pane.path.setText(displayPath(pane));
                        pane.free.setText(storageFree(pane.root));
                        if (pane.files.isEmpty()) {
                            pane.empty.setText(err == null
                                    ? Loc.s(FileManagerActivity.this,
                                            query.isEmpty() ? "Folder is empty" : "No matches")
                                    : err);
                        }
                        if (pane == activePane) updateFooter();
                        if (!initialFocusDone && pane == leftPane) {
                            initialFocusDone = true;
                            focusFirstFile(true);
                        } else if (pane == navPane || navPane == null) {
                            // Entering or leaving a folder rebuilds the rows; put
                            // the ring back so the controller always has a cursor.
                            final int restore = Math.max(0,
                                    Math.min(pane.files.size() - 1, navIndex));
                            if (!pane.files.isEmpty()) {
                                pane.list.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        focusRow(pane, restore);
                                    }
                                }, 30L);
                            }
                        }
                    }
                });
            }
        });
    }

    private void walk(File dir, String needle, List<File> out, boolean hidden, int depth) {
        if (dir == null || depth > 10 || out.size() >= 500) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (out.size() >= 500) return;
            if (!hidden && f.getName().startsWith(".")) continue;
            if (f.getName().toLowerCase(Locale.getDefault()).contains(needle)) out.add(f);
            if (f.isDirectory()) walk(f, needle, out, hidden, depth + 1);
        }
    }

    private void sort(List<File> list) {
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                int cmp;
                if (sortMode == SORT_SIZE) {
                    cmp = Long.compare(a.length(), b.length());
                } else if (sortMode == SORT_DATE) {
                    cmp = Long.compare(a.lastModified(), b.lastModified());
                } else {
                    cmp = a.getName().compareToIgnoreCase(b.getName());
                }
                return sortAscending ? cmp : -cmp;
            }
        });
    }

    private String displayPath(Pane pane) {
        if (pane.query != null && !pane.query.isEmpty()) {
            return tr("SEARCH") + ": " + pane.query;
        }
        String prefix = pane.root.getAbsolutePath();
        String path = pane.dir == null ? prefix : pane.dir.getAbsolutePath();
        String label = pane.title.getText().toString();
        if (path.equals(prefix)) return label;
        if (path.startsWith(prefix)) return label + path.substring(prefix.length());
        return path;
    }

    private String storageFree(File root) {
        try {
            StatFs stat = new StatFs(root.getAbsolutePath());
            return formatBytes(stat.getAvailableBytes()) + " " + tr("FREE");
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // File actions
    // ------------------------------------------------------------------

    private void beginDrag(View source, File file) {
        if (file == null) return;
        resetDragVisuals();
        dragSourceView = source;
        dragSourcePane = activePane;
        source.animate()
                .scaleX(0.94f)
                .scaleY(0.94f)
                .alpha(0.55f)
                .setDuration(120L)
                .start();
        ClipData data = ClipData.newPlainText("aurora-file", file.getAbsolutePath());
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(source);
        source.startDragAndDrop(data, shadow, file, 0);
    }

    private void resetDragVisuals() {
        if (dragSourceView != null) {
            dragSourceView.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(160L).start();
            dragSourceView = null;
        }
        dragSourcePane = null;
        if (leftPane != null) {
            leftPane.card.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
        }
        if (rightPane != null) {
            rightPane.card.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
        }
    }

    private final class PaneDropListener implements View.OnDragListener {
        private final Pane pane;

        PaneDropListener(Pane pane) {
            this.pane = pane;
        }

        @Override public boolean onDrag(View view, DragEvent event) {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    ClipDescription desc = event.getClipDescription();
                    return desc != null
                            && desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
                case DragEvent.ACTION_DRAG_ENTERED:
                    setActive(pane, false);
                    pane.card.animate().scaleX(1.008f).scaleY(1.008f)
                            .setDuration(140L).start();
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    pane.card.animate().scaleX(1f).scaleY(1f)
                            .setDuration(140L).start();
                    return true;
                case DragEvent.ACTION_DROP:
                    pane.card.animate().scaleX(1.012f).scaleY(1.012f)
                            .setDuration(90L).start();
                    File source = null;
                    try {
                        if (event.getClipData() != null && event.getClipData().getItemCount() > 0) {
                            CharSequence text = event.getClipData().getItemAt(0).getText();
                            if (text != null) source = new File(text.toString());
                        }
                    } catch (Throwable ignored) {}
                    if (source != null && source.exists()) copyDropped(source, pane);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    resetDragVisuals();
                    return true;
                default:
                    return true;
            }
        }
    }

    private void copyDropped(final File source, final Pane target) {
        final File dest = target.dir;
        if (dest == null) return;
        io.execute(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    copyRecursive(source, dest);
                } catch (Throwable t) {
                    error = t.getMessage();
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        LToast.makeText(FileManagerActivity.this,
                                Loc.s(FileManagerActivity.this, err == null
                                        ? "Copied" : "Operation failed"),
                                android.widget.Toast.LENGTH_SHORT).show();
                        target.card.animate().scaleX(1f).scaleY(1f)
                                .setDuration(160L).start();
                        reloadAll();
                    }
                });
            }
        });
    }

    private void goUp(Pane pane) {
        if (pane == null) return;
        if (pane.query != null && !pane.query.isEmpty()) {
            pane.query = "";
            pane.clearSelection();
            reload(pane);
            return;
        }
        if (pane.dir == null || pane.root == null) return;
        String current = pane.dir.getAbsolutePath();
        String root = pane.root.getAbsolutePath();
        if (current.equals(root)) return;
        File parent = pane.dir.getParentFile();
        if (parent == null) return;
        String next = parent.getAbsolutePath();
        if (!next.startsWith(root)) return;
        pane.dir = parent;
        pane.query = "";
        pane.clearSelection();
        reload(pane);
    }

    private void chooseFile(Pane pane, File file) {
        if (!pane.selected.isEmpty()) {
            toggleSelected(pane, file);
            return;
        }
        if (file.isDirectory()) {
            pane.dir = file;
            pane.query = "";
            pane.clearSelection();
            reload(pane);
        } else if (isImageFile(file)) {
            Intent intent = new Intent(this, ImageViewerActivity.class);
            intent.putExtra(ImageViewerActivity.EXTRA_PATH, file.getAbsolutePath());
            startActivity(intent);
        } else if (isVideoFile(file)) {
            Intent intent = new Intent(this, VideoPlayerActivity.class);
            intent.putExtra(VideoPlayerActivity.EXTRA_PATH, file.getAbsolutePath());
            startActivity(intent);
        } else if (isAudioFile(file)) {
            Intent intent = new Intent(this, AudioPlayerActivity.class);
            intent.putExtra(AudioPlayerActivity.EXTRA_PATH, file.getAbsolutePath());
            startActivity(intent);
        } else if (isTextFile(file)) {
            editorOpen = true;
            Intent intent = new Intent(this, FileEditorActivity.class);
            intent.putExtra(FileEditorActivity.EXTRA_PATH, file.getAbsolutePath());
            startActivity(intent);
        } else {
            openFile(file);
        }
    }

    private boolean isImageFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "bmp": case "heic": case "heif": case "avif":
                return true;
            default:
                String mime = mimeOf(file);
                return mime != null && mime.startsWith("image/");
        }
    }

    private boolean isVideoFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "mp4": case "mkv": case "webm": case "avi": case "mov":
            case "3gp": case "m4v": case "ts": case "mpeg": case "mpg":
                return true;
            default:
                String mime = mimeOf(file);
                return mime != null && mime.startsWith("video/");
        }
    }

    private boolean isAudioFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "mp3": case "m4a": case "aac": case "flac": case "wav":
            case "ogg": case "opus": case "amr": case "mid": case "midi":
                return true;
            default:
                String mime = mimeOf(file);
                return mime != null && mime.startsWith("audio/");
        }
    }

    private boolean isTextFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "txt": case "xml": case "xsd": case "svg": case "json":
            case "md": case "markdown": case "log": case "csv": case "ini":
            case "conf": case "cfg": case "properties": case "yml":
            case "yaml": case "html": case "htm": case "css": case "js":
            case "java": case "kt": case "sh": case "py": case "c": case "h":
            case "cpp": case "hpp": case "gradle": case "pro":
                return true;
            default:
                String mime = mimeOf(file);
                return mime != null && mime.startsWith("text/");
        }
    }

    private void openFile(File file) {
        try {
            Uri uri = PavoFileProvider.uriFor(file);
            String mime = mimeOf(file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "No app can open this file"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleSelected(Pane pane, File file) {
        if (!pane.selected.remove(file)) pane.selected.add(file);
        pane.adapter.notifyDataSetChanged();
        updateFooter();
    }

    private void clearSelection() {
        if (activePane != null) activePane.clearSelection();
    }

    private void copySelection(boolean cut) {
        if (activePane == null || activePane.selected.isEmpty()) return;
        clipboard.clear();
        clipboard.addAll(activePane.selected);
        clipboardCut = cut;
        activePane.clearSelection();
        updateFooter();
        LToast.makeText(this, clipboard.size() + " " + Loc.s(this, "ITEM(S) IN CLIPBOARD"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void pasteClipboard() {
        if (activePane == null || clipboard.isEmpty()) return;
        final Pane target = activePane;
        final File dest = target.dir;
        final ArrayList<File> source = new ArrayList<>(clipboard);
        final boolean cut = clipboardCut;
        target.selected.clear();
        target.adapter.notifyDataSetChanged();
        updateFooter();
        io.execute(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    for (File f : source) {
                        copyRecursive(f, dest);
                        if (cut) deleteRecursive(f);
                    }
                } catch (Throwable t) {
                    error = t.getMessage();
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (err == null) {
                            if (cut) clipboard.clear();
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Paste complete"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Operation failed"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                        reloadAll();
                    }
                });
            }
        });
    }

    private void confirmDeleteSelection() {
        if (activePane == null || activePane.selected.isEmpty()) return;
        final ArrayList<File> targets = new ArrayList<>(activePane.selected);
        showConfirm(Loc.s(this, "DELETE ITEM(S)?"),
                Loc.s(this, "This cannot be undone."),
                Loc.s(this, "DELETE"), new Runnable() {
                    @Override public void run() {
                        final Pane pane = activePane;
                        pane.selected.clear();
                        updateFooter();
                        io.execute(new Runnable() {
                            @Override public void run() {
                                for (File f : targets) {
                                    try { deleteRecursive(f); } catch (Throwable ignored) {}
                                }
                                main.post(new Runnable() {
                                    @Override public void run() {
                                        LToast.makeText(FileManagerActivity.this,
                                                Loc.s(FileManagerActivity.this, "Deleted"),
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        reloadAll();
                                    }
                                });
                            }
                        });
                    }
                });
    }

    private void shareSelection() {
        if (activePane == null || activePane.selected.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : activePane.selected) {
            if (f.isFile()) uris.add(PavoFileProvider.uriFor(f));
        }
        if (uris.isEmpty()) return;
        try {
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND)
                        .setType(mimeOf(activePane.selected.iterator().next()));
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*");
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(intent, Loc.s(this, "SHARE")));
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "No app can share this file"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showNewFolderDialog() {
        if (activePane == null) return;
        showTextDialog(Loc.s(this, "NEW FOLDER"), "", Loc.s(this, "CREATE"), true,
                new TextCallback() {
                    @Override public void onText(String value) {
                        final File dir = activePane.dir;
                        final File target = new File(dir, value.trim());
                        if (TextUtils.isEmpty(value.trim())) return;
                        if (target.exists()) {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Already exists"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!target.mkdirs()) {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Could not create folder"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        reloadAll();
                    }
                });
    }

    private void showNewFileDialog() {
        if (activePane == null) return;
        showTextDialog(Loc.s(this, "NEW FILE"), "", Loc.s(this, "CREATE"), true,
                new TextCallback() {
                    @Override public void onText(String value) {
                        String name = value == null ? "" : value.trim();
                        if (name.isEmpty()) return;
                        File target = new File(activePane.dir, name);
                        if (target.exists()) {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Already exists"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        try {
                            if (!target.createNewFile()) throw new Exception();
                        } catch (Throwable t) {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Could not create file"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        reloadAll();
                    }
                });
    }

    private void showRenameDialog(final File file) {
        if (file == null) return;
        showTextDialog(Loc.s(this, "RENAME"), file.getName(), Loc.s(this, "RENAME"), false,
                new TextCallback() {
                    @Override public void onText(String value) {
                        String name = value.trim();
                        if (name.isEmpty() || name.equals(file.getName())) return;
                        File target = new File(file.getParentFile(), name);
                        if (target.exists()) {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Already exists"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!file.renameTo(target)) {
                            LToast.makeText(FileManagerActivity.this,
                                    Loc.s(FileManagerActivity.this, "Rename failed"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        clearSelection();
                        reloadAll();
                    }
                });
    }

    private void showInfo(final File file) {
        if (file == null) return;
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GlassPanel panel = dialogPanel();
        TextView eyebrow = text("INFO", 10f, 0xFFFFFFFF, false);
        eyebrow.setLetterSpacing(0.14f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(eyebrow);
        TextView name = plainText(file.getName(), 16f, TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setPadding(0, dp(2), 0, dp(8));
        panel.addView(name, matchWrap());

        final TextView sizeValue = addInfoRow(panel, "SIZE", "…");
        addInfoRow(panel, "FORMAT", formatLabel(file));
        addInfoRow(panel, "CREATED", formatInfoTime(createdTime(file)));
        addInfoRow(panel, "MODIFIED", formatInfoTime(file.lastModified()));
        addInfoRow(panel, "READ", Loc.s(this, file.canRead() ? "YES" : "NO"));
        addInfoRow(panel, "WRITE", Loc.s(this, file.canWrite() ? "YES" : "NO"));
        addInfoRow(panel, "PATH", file.getAbsolutePath());

        TextView close = button(Loc.s(this, "CLOSE"), true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams closeLp = matchWrap();
        closeLp.topMargin = dp(9);
        panel.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        showPanel(d, panel, 330);

        if (file.isDirectory()) {
            io.execute(new Runnable() {
                @Override public void run() {
                    final long size = folderSize(file);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (sizeValue.isAttachedToWindow()) {
                                sizeValue.setText(formatBytes(size));
                            }
                        }
                    });
                }
            });
        } else {
            sizeValue.setText(formatBytes(file.length()));
        }
    }

    private TextView addInfoRow(LinearLayout panel, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));
        TextView l = text(Loc.s(this, label), 9f, MUTED, false);
        l.setLetterSpacing(0.08f);
        l.setMinWidth(dp(62));
        row.addView(l, new LinearLayout.LayoutParams(dp(62),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = plainText(value == null ? "—" : value, 11f, TEXT, true);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(row, matchWrap());
        return v;
    }

    private String formatLabel(File file) {
        if (file.isDirectory()) return Loc.s(this, "FOLDER");
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toUpperCase(Locale.US) : "";
        String mime = mimeOf(file);
        if (ext.isEmpty()) ext = Loc.s(this, "FILE");
        return mime == null || mime.equals("*/*") ? ext : ext + "  ·  " + mime;
    }

    private long createdTime(File file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(
                    file.toPath(), BasicFileAttributes.class);
            long created = attrs.creationTime().toMillis();
            return created > 0L ? created : file.lastModified();
        } catch (Throwable t) {
            return file.lastModified();
        }
    }

    private long folderSize(File file) {
        if (file == null) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) total += folderSize(child);
        }
        return total;
    }

    private String formatInfoTime(long time) {
        if (time <= 0L) return "—";
        return new SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
                .format(new Date(time));
    }

    // ------------------------------------------------------------------
    // Sort / search
    // ------------------------------------------------------------------

    private void showSortDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        addDialogOption(box, Loc.s(this, "NAME") + (sortMode == SORT_NAME ? "  ✓" : ""),
                new Runnable() { @Override public void run() { sortMode = SORT_NAME; savePrefs(); reloadAll(); } });
        addDialogOption(box, Loc.s(this, "SIZE") + (sortMode == SORT_SIZE ? "  ✓" : ""),
                new Runnable() { @Override public void run() { sortMode = SORT_SIZE; savePrefs(); reloadAll(); } });
        addDialogOption(box, Loc.s(this, "DATE") + (sortMode == SORT_DATE ? "  ✓" : ""),
                new Runnable() { @Override public void run() { sortMode = SORT_DATE; savePrefs(); reloadAll(); } });
        addDialogOption(box, Loc.s(this, sortAscending ? "DESCENDING" : "ASCENDING"),
                new Runnable() { @Override public void run() { sortAscending = !sortAscending; savePrefs(); reloadAll(); } });
        addDialogOption(box, Loc.s(this, showHidden ? "HIDE HIDDEN" : "SHOW HIDDEN"),
                new Runnable() { @Override public void run() { showHidden = !showHidden; savePrefs(); reloadAll(); } });
        showContentDialog(Loc.s(this, "SORT & VIEW"), box);
    }

    private void showSearchDialog(final Pane pane) {
        if (pane == null) return;
        showTextDialog(Loc.s(this, "SEARCH"), pane.query, Loc.s(this, "SEARCH"), false,
                new TextCallback() {
                    @Override public void onText(String value) {
                        pane.query = value == null ? "" : value.trim();
                        pane.selected.clear();
                        reload(pane);
                    }
                });
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private interface TextCallback {
        void onText(String value);
    }

    private void showTextDialog(String title, String initial, String positive,
                                final boolean create, final TextCallback callback) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GlassPanel panel = dialogPanel();
        TextView heading = text(title, 15f, TEXT, false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(0, 0, 0, dp(7));
        panel.addView(heading);

        final EditText input = new EditText(this);
        input.setText(initial == null ? "" : initial);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        input.setHintTextColor(0xA0FFFFFF);
        input.setPadding(dp(10), dp(6), dp(10), dp(6));
        input.setBackground(Ui.rounded(0x33000000, dp(9), 0x44FFFFFF, dp(1)));
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        panel.addView(input, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        TextView cancel = button(Loc.s(this, "CANCEL"), false);
        TextView ok = button(positive, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String value = input.getText().toString();
                d.dismiss();
                callback.onText(value);
            }
        });
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    String value = input.getText().toString();
                    d.dismiss();
                    callback.onText(value);
                    return true;
                }
                return false;
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        okLp.leftMargin = dp(8);
        buttons.addView(ok, okLp);
        panel.addView(buttons, matchWrap());
        showPanel(d, panel, 420);
        Window window = d.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24),
                    dp(360));
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(14);
            lp.y = dp(14);
            window.setAttributes(lp);
        }
    }

    private void showConfirm(String title, String message, String positive,
                             final Runnable onConfirm) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GlassPanel panel = dialogPanel();
        TextView eyebrow = text(title, 10f, 0xFFFFFFFF, false);
        eyebrow.setLetterSpacing(0.12f);
        panel.addView(eyebrow);
        TextView heading = text(title, 17f, TEXT, false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(0, dp(3), 0, dp(4));
        panel.addView(heading);
        TextView body = text(message, 10f, MUTED, false);
        body.setPadding(0, dp(2), 0, dp(10));
        panel.addView(body);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(Loc.s(this, "CANCEL"), false);
        TextView ok = button(positive, true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); onConfirm.run(); }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        okLp.leftMargin = dp(8);
        buttons.addView(ok, okLp);
        panel.addView(buttons, matchWrap());
        showPanel(d, panel, 420);
    }

    private void showContentDialog(String title, View content) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GlassPanel panel = dialogPanel();
        TextView heading = text(title, 16f, TEXT, false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(heading);
        content.setPadding(0, dp(8), 0, dp(2));
        panel.addView(content, matchWrap());
        TextView close = button(Loc.s(this, "CLOSE"), false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        panel.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        showPanel(d, panel, 420);
    }

    private void addDialogOption(LinearLayout panel, String label, final Runnable action) {
        TextView row = text(label, 11.5f, 0xF5FFFFFF, false);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setFocusable(true);
        row.setClickable(true);
        final android.graphics.drawable.Drawable normal = Ui.rounded(0x22FFFFFF, dp(9));
        row.setBackground(normal);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                v.setBackground(focused ? Ui.focusRing(FileManagerActivity.this, dp(9)) : normal);
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (openDialog != null) openDialog.dismiss();
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(4);
        panel.addView(row, lp);
    }

    private Dialog baseDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GlassPanel panel = dialogPanel();
        d.setContentView(panel);
        return d;
    }

    private GlassPanel dialogPanel() {
        Backdrop backdrop;
        try {
            backdrop = Backdrop.capture(getWindow().getDecorView(), 12);
        } catch (Throwable t) {
            backdrop = null;
        }
        GlassPanel panel = new GlassPanel(this, backdrop, ThemeColors.accent(),
                0x800B0E14, 20, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0xD8);
        panel.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        panel.setBackdropScreenAnchored(true);
        panel.setPadding(dp(20), dp(15), dp(20), dp(13));
        return panel;
    }

    private boolean handleDialogKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (down && event.getRepeatCount() == 0) openDialog.dismiss();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            if (down && event.getRepeatCount() == 0) {
                View focus = openDialog.getWindow() == null ? null
                        : openDialog.getWindow().getDecorView().findFocus();
                if (focus != null) focus.performClick();
            }
            return true;
        }
        int dir = (code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_RIGHT) ? 1
                : (code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_LEFT) ? -1 : 0;
        if (dir == 0) return false;
        if (!down) return true;
        if (dialogFocusOrder.isEmpty()) return true;
        View focus = openDialog.getWindow() == null ? null
                : openDialog.getWindow().getDecorView().findFocus();
        int index = dialogFocusOrder.indexOf(focus);
        if (index < 0) index = 0;
        index = Math.max(0, Math.min(dialogFocusOrder.size() - 1, index + dir));
        View target = dialogFocusOrder.get(index);
        target.requestFocus();
        target.requestRectangleOnScreen(new android.graphics.Rect(
                0, 0, target.getWidth(), target.getHeight()), false);
        return true;
    }

    private void showPanel(final Dialog dialog, GlassPanel panel, int maxDp) {
        dismissDialog();
        final View previousFocus = getCurrentFocus();
        openDialog = dialog;
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(28),
                    dp(maxDp));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.52f;
            window.setAttributes(lp);
        }
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (openDialog == dialog) openDialog = null;
                dialogFocusOrder.clear();
                if (controllerNav != null) controllerNav.setRoot(controllerRoot);
                // Whatever the dialog was opened from (list cursor or a toolbar
                // button), come back to a visible file cursor.
                cursorMode = true;
                main.post(new Runnable() {
                    @Override public void run() { backToCursor(); }
                });
            }
        });
        dialog.show();
        if (controllerNav != null) {
            final View dialogRoot = dialog.getWindow() == null ? null
                    : dialog.getWindow().getDecorView();
            if (dialogRoot != null) {
                dialogRoot.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (controllerNav == null) return;
                        controllerNav.attachDialog(dialog, panel);
                        controllerNav.decorateFocusables();
                        disableDefaultFocusHighlight(dialogRoot);
                        final List<View> order = new ArrayList<>();
                        collectDialogFocusables(panel, order);
                        dialogFocusOrder.clear();
                        dialogFocusOrder.addAll(order);
                        if (!order.isEmpty()) order.get(0).requestFocus();
                        dialog.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
                            @Override public boolean onKey(android.content.DialogInterface d,
                                    int keyCode, KeyEvent event) {
                                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                                if (keyCode == KeyEvent.KEYCODE_BUTTON_B
                                        || keyCode == KeyEvent.KEYCODE_BACK) {
                                    if (down && event.getRepeatCount() == 0) d.dismiss();
                                    return true;
                                }
                                if (keyCode == KeyEvent.KEYCODE_BUTTON_A
                                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                                    if (down && event.getRepeatCount() == 0) {
                                        View f = getCurrentFocus();
                                        if (f != null) f.performClick();
                                    }
                                    return true;
                                }
                                int dir = (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ? 1
                                        : (keyCode == KeyEvent.KEYCODE_DPAD_UP
                                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? -1 : 0;
                                if (dir == 0) return false;
                                if (!down) return true;
                                if (order.isEmpty()) return true;
                                int index = order.indexOf(getCurrentFocus());
                                if (index < 0) index = 0;
                                index = Math.max(0, Math.min(order.size() - 1, index + dir));
                                View target = order.get(index);
                                target.requestFocus();
                                target.requestRectangleOnScreen(new android.graphics.Rect(
                                        0, 0, target.getWidth(), target.getHeight()), false);
                                return true;
                            }
                        });
                    }
                }, 180L);
            }
        }
    }

    private static void collectDialogFocusables(View view, List<View> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (view.isFocusable() && view.isEnabled()) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectDialogFocusables(group.getChildAt(i), out);
            }
        }
    }

    private void dismissDialog() {
        if (openDialog != null) {
            openDialog.dismiss();
            openDialog = null;
        }
    }

    // ------------------------------------------------------------------
    // File operations
    // ------------------------------------------------------------------

    private void copyRecursive(File src, File destDir) throws IOException {
        File target = uniqueTarget(destDir, src.getName());
        if (src.isDirectory()) {
            if (!target.mkdirs()) throw new IOException("mkdirs");
            File[] children = src.listFiles();
            if (children != null) for (File child : children) copyRecursive(child, target);
        } else {
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[64 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private File uniqueTarget(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            target = new File(dir, base + " (" + i + ")" + ext);
            if (!target.exists()) return target;
        }
        return new File(dir, name + System.currentTimeMillis());
    }

    private void deleteRecursive(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private String mimeOf(File file) {
        if (file == null) return "*/*";
        if (file.isDirectory()) return "resource/folder";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "*/*";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i]);
    }

    private String formatDate(long time) {
        return new SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
                .format(new Date(time));
    }

    // ------------------------------------------------------------------
    // Storage and permissions
    // ------------------------------------------------------------------

    private File findRemovableRoot() {
        try {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs != null) {
                for (int i = 1; i < dirs.length; i++) {
                    if (dirs[i] == null) continue;
                    String path = dirs[i].getAbsolutePath();
                    int cut = path.indexOf("/Android/");
                    File root = cut > 0 ? new File(path.substring(0, cut)) : dirs[i];
                    if (root.exists() && root.canRead()) return root;
                }
            }
        } catch (Throwable ignored) {
        }
        File storage = new File("/storage");
        File[] roots = storage.listFiles();
        if (roots != null) {
            for (File f : roots) {
                String n = f.getName();
                if ("emulated".equals(n) || "self".equals(n)) continue;
                if (f.isDirectory() && f.canRead()) return f;
            }
        }
        return null;
    }

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30 && PServer.available()) {
                PServer.run("appops set " + getPackageName()
                        + " MANAGE_EXTERNAL_STORAGE allow");
                if (hasAllFiles()) {
                    lastAllFiles = true;
                    updatePermissionBanner();
                    reloadAll();
                    return;
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                        9001);
            }
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable ignored) {
            }
        }
    }

    private void updatePermissionBanner() {
        if (permissionBanner != null) {
            permissionBanner.setVisibility(hasAllFiles() ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    private void readPrefs() {
        SharedPreferences sp = getSharedPreferences(Prefs.FILE, MODE_PRIVATE);
        sortMode = sp.getInt("file_sort_mode", SORT_NAME);
        sortAscending = sp.getBoolean("file_sort_ascending", true);
        showHidden = sp.getBoolean("file_show_hidden", false);
    }

    private void savePrefs() {
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit()
                .putInt("file_sort_mode", sortMode)
                .putBoolean("file_sort_ascending", sortAscending)
                .putBoolean("file_show_hidden", showHidden)
                .apply();
    }

    // ------------------------------------------------------------------
    // Adapter and row helpers
    // ------------------------------------------------------------------

    private final class PaneCard extends FrameLayout {
        private final Pane pane;

        PaneCard(Pane pane) {
            super(FileManagerActivity.this);
            this.pane = pane;
            setClickable(true);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && activePane != pane) {
                setActive(pane);
                return true;
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return true;
        }
    }

    private final class DrawerAdapter extends BaseAdapter {
        private final Pane pane;

        DrawerAdapter(Pane pane) {
            this.pane = pane;
        }

        @Override public int getCount() { return pane.files.size(); }
        @Override public Object getItem(int position) { return pane.files.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            File file = pane.files.get(position);
            LinearLayout row = new LinearLayout(FileManagerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            boolean selected = pane.selected.contains(file);
            boolean controllerCurrent = cursorMode && pane == navPane
                    && position == navIndex;
            row.setBackground(selected || controllerCurrent
                    ? Ui.focusRing(FileManagerActivity.this, dp(10))
                    : Ui.ripple(FileManagerActivity.this, dp(10)));
            // Rows are a data cursor, not Android focus: focus would be lost
            // every time the adapter rebinds, which is what made the ring blink
            // out and the stick scroll without a visible selection.
            row.setFocusable(false);
            row.setClickable(true);
            row.setTag(file);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    navPane = pane;
                    navIndex = pane.files.indexOf(file);
                    cursorMode = true;
                    refreshRowRings();
                    if (pane.suppressRowClick) {
                        pane.suppressRowClick = false;
                        return;
                    }
                    if (activePane != pane) {
                        setActive(pane);
                        return;
                    }
                    chooseFile(pane, file);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    setActive(pane);
                    toggleSelected(pane, file);
                    Haptics.longPress(v);
                    return true;
                }
            });

            FileGlyphView glyph = new FileGlyphView(FileManagerActivity.this, file);
            row.addView(glyph, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout labels = new LinearLayout(FileManagerActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(10), 0, dp(4), 0);
            TextView name = plainText(file.getName(), 11f, TEXT, false);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            labels.addView(name, matchWrap());
            String meta = file.isDirectory()
                    ? Loc.s(FileManagerActivity.this, "Folder")
                    : formatBytes(file.length());
            TextView detail = plainText(meta, 9f, MUTED, true);
            detail.setSingleLine(true);
            labels.addView(detail, matchWrap());
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView drag = plainText("\u283f", 14f, 0x8CFFFFFF, false);
            drag.setGravity(Gravity.CENTER);
            drag.setMinWidth(dp(25));
            drag.setOnTouchListener(new View.OnTouchListener() {
                private final Runnable start = new Runnable() {
                    @Override public void run() {
                        if (!drag.isAttachedToWindow()) return;
                        setActive(pane);
                        Haptics.longPress(drag);
                        beginDrag(row, file);
                    }
                };
                private float downX;
                private float downY;

                @Override public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getX();
                            downY = event.getY();
                            drag.postDelayed(start, 380L);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (Math.abs(event.getX() - downX) > dp(5)
                                    || Math.abs(event.getY() - downY) > dp(5)) {
                                drag.removeCallbacks(start);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            drag.removeCallbacks(start);
                            return true;
                        default:
                            return true;
                    }
                }
            });
            row.addView(drag, new LinearLayout.LayoutParams(dp(27), dp(42)));

            if (selected) {
                TextView check = text("✓", 14f, ThemeColors.accent(), true);
                check.setGravity(Gravity.CENTER);
                check.setMinWidth(dp(24));
                row.addView(check);
            } else if (file.isDirectory()) {
                TextView chevron = text("›", 17f, MUTED, false);
                chevron.setGravity(Gravity.CENTER);
                chevron.setMinWidth(dp(24));
                row.addView(chevron);
            }
            return row;
        }
    }

    private static final class FileGlyphView extends View {
        private final boolean folder;
        private final String ext;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FileGlyphView(Context c, File file) {
            super(c);
            folder = file.isDirectory();
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            ext = dot >= 0 && dot < name.length() - 1
                    ? name.substring(dot + 1).toUpperCase(Locale.US) : "";
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            int accent = ThemeColors.accent();
            if (folder) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(alpha(accent, 0x2A));
                RectF body = new RectF(w * 0.12f, h * 0.28f, w * 0.88f, h * 0.83f);
                canvas.drawRoundRect(body, w * 0.08f, w * 0.08f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.4f, w * 0.045f));
                paint.setColor(alpha(accent, 0xD0));
                canvas.drawRoundRect(body, w * 0.08f, w * 0.08f, paint);
                Path tab = new Path();
                tab.moveTo(w * 0.12f, h * 0.28f);
                tab.lineTo(w * 0.12f, h * 0.18f);
                tab.lineTo(w * 0.43f, h * 0.18f);
                tab.lineTo(w * 0.51f, h * 0.28f);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawPath(tab, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x22FFFFFF);
                RectF body = new RectF(w * 0.20f, h * 0.12f, w * 0.80f, h * 0.88f);
                canvas.drawRoundRect(body, w * 0.07f, w * 0.07f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.2f, w * 0.038f));
                paint.setColor(0x99FFFFFF);
                canvas.drawRoundRect(body, w * 0.07f, w * 0.07f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, w * 0.025f));
                paint.setColor(0x33FFFFFF);
                canvas.drawLine(w * 0.31f, h * 0.34f, w * 0.69f, h * 0.34f, paint);
                canvas.drawLine(w * 0.31f, h * 0.48f, w * 0.69f, h * 0.48f, paint);
                if (!ext.isEmpty()) {
                    textPaint.setColor(alpha(accent, 0xD8));
                    textPaint.setTextSize(Math.max(5.5f, w * 0.19f));
                    canvas.drawText(ext.length() > 4 ? ext.substring(0, 4) : ext,
                            w * 0.5f, h * 0.71f, textPaint);
                }
            }
        }
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    // ------------------------------------------------------------------
    // Small view helpers
    // ------------------------------------------------------------------

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
        v.setShadowLayer(dp(1.5f), 0f, dp(0.8f), 0x99000000);
        return v;
    }

    private TextView plainText(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        v.setShadowLayer(dp(1.5f), 0f, dp(0.8f), 0x99000000);
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 10f, primary ? 0xFF071015 : 0xF2FFFFFF, true);
        v.setShadowLayer(0f, 0f, 0f, 0x00000000);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(primary ? ThemeColors.accent() : 0x22FFFFFF,
                dp(10), primary ? 0x00000000 : 0x44FFFFFF, dp(1)));
        return v;
    }

    private String tr(String value) {
        return Loc.s(this, value);
    }
}
