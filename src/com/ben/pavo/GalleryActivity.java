package com.ben.pavo;

import android.Manifest;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in gallery. Reads MediaStore first and falls back to a filesystem scan
 * for unindexed files, skipping hidden folders and any folder with a .nomedia
 * file. Photos and videos open in the File Manager's own viewer and player.
 */
public final class GalleryActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;

    private static final int TAB_FOLDERS = 0;
    private static final int TAB_PHOTOS = 1;
    private static final int TAB_VIDEOS = 2;
    private static final String[] TAB_KEYS = {
            "FOLDERS", "ALL PHOTOS", "ALL VIDEOS"
    };

    private static final String COL_BUCKET_ID = "bucket_id";
    private static final String COL_BUCKET_NAME = "bucket_display_name";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> cache;
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<String>());

    private Prefs prefs;
    private int accent;
    private ControllerNav nav;
    private TextView subtitle;
    private TextView status;
    private TextView backChip;
    private LinearLayout statusRow;
    private LinearLayout selectionRow;
    private TextView selectionCount;
    private TextView copyChip;
    private String selectedFolderId;
    private GridView grid;
    private View rootView;
    private FocusRingDrawable focusRing;
    private boolean gridActive;
    private int gridFocus = -1;
    private int gridColumns = 6;
    private TextView deleteChip;
    private TextView clearChip;
    private int stickDir = -1;
    private long lastStickMove;
    private boolean aDown;
    private boolean aLongFired;
    private final Runnable aLongPress = new Runnable() {
        @Override public void run() {
            if (!aDown) return;
            aDown = false;
            aLongFired = true;
            selectFocused();
            Haptics.click(grid);
        }
    };
    private final Set<String> selectedPaths = new HashSet<>();
    private boolean selectionMode;
    private final List<TextView> tabChips = new ArrayList<>();
    private TileAdapter adapter;

    private final List<MediaItem> allPhotos = new ArrayList<>();
    private final List<MediaItem> allVideos = new ArrayList<>();
    private final List<Folder> allFolders = new ArrayList<>();
    private final List<MediaItem> items = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();

    private int tab = TAB_FOLDERS;
    private String folderId;
    private String folderName;
    private int token;
    private boolean scanned;
    private long lastScan;

    public GalleryActivity() {
        cache = new LruCache<String, Bitmap>(cacheSize()) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    private static int cacheSize() {
        return (int) Math.max(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        buildUi();
        hideBars();
        installController();
        requestMediaAccess();
        scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (scanned && System.currentTimeMillis() - lastScan > 4000L) scan();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(aLongPress);
        aDown = false;
        aLongFired = false;
        if (nav != null) nav.cancel();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        cache.evictAll();
        if (nav != null) nav.release();
        super.onDestroy();
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

    private void installController() {
        nav = new ControllerNav(this, getWindow().getDecorView());
        nav.setInitialFocus(grid);
        gridActive = !getWindow().getDecorView().isInTouchMode();
        if (gridActive) gridFocus = 0;
        grid.postDelayed(new Runnable() {
            @Override public void run() {
                if (gridActive) updateGridRing();
            }
        }, 250L);
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            clearSelection();
        } else if (folderId != null) {
            folderId = null;
            populate();
            grid.setSelection(0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = down && event.getRepeatCount() > 0;
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B)
                && down && !repeat) {
            if (selectionMode) {
                clearSelection();
            } else if (folderId != null) {
                folderId = null;
                populate();
                resetGridFocus();
            } else {
                finishAnimated();
            }
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1)
                && down && !repeat) {
            int direction = code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1;
            clearSelection();
            folderId = null;
            tab = (tab + direction + TAB_KEYS.length) % TAB_KEYS.length;
            populate();
            resetGridFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_X && down && !repeat) {
            if (!gridActive) {
                View focus = getCurrentFocus();
                if (focus == null || focus == grid) {
                    gridActive = true;
                    gridFocus = Math.max(0, gridFocus);
                    grid.setSelection(gridFocus);
                    updateFocusRing(grid);
                }
            }
            selectFocused();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            if (down && !repeat) {
                View focus = getCurrentFocus();
                boolean gridContext = gridActive || focus == null || focus == grid;
                if (gridContext) {
                    if (!gridActive) {
                        gridActive = true;
                        gridFocus = Math.max(0, gridFocus);
                        grid.setSelection(gridFocus);
                        updateFocusRing(grid);
                    }
                    aDown = true;
                    aLongFired = false;
                    main.removeCallbacks(aLongPress);
                    main.postDelayed(aLongPress,
                            ViewConfiguration.getLongPressTimeout());
                    return true;
                }
            } else if (!down) {
                if (aDown || aLongFired) {
                    main.removeCallbacks(aLongPress);
                    boolean fire = aDown && !aLongFired;
                    aDown = false;
                    aLongFired = false;
                    if (fire) activateFocused();
                    return true;
                }
            }
        }
        if (isDirection(code)) {
            View focus = getCurrentFocus();
            boolean onActions = focus == deleteChip || focus == copyChip || focus == clearChip;
            if (!gridActive && !onActions) {
                gridActive = true;
                if (gridFocus < 0) gridFocus = 0;
                grid.setSelection(gridFocus);
                updateFocusRing(grid);
                return true;
            }
            if (gridActive) {
                moveGrid(code);
                return true;
            }
        }
        if (nav != null && nav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (gridActive && SliderStick.isGamepad(event)
                && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hatX) > Math.abs(x)) x = hatX;
            if (Math.abs(hatY) > Math.abs(y)) y = hatY;
            if (Math.max(Math.abs(x), Math.abs(y)) >= 0.62f) {
                int dir = Math.abs(x) >= Math.abs(y)
                        ? (x < 0 ? KeyEvent.KEYCODE_DPAD_LEFT
                                : KeyEvent.KEYCODE_DPAD_RIGHT)
                        : (y < 0 ? KeyEvent.KEYCODE_DPAD_UP
                                : KeyEvent.KEYCODE_DPAD_DOWN);
                long now = SystemClock.uptimeMillis();
                if (dir != stickDir || now - lastStickMove > 190L) {
                    stickDir = dir;
                    lastStickMove = now;
                    moveGrid(dir);
                }
                return true;
            }
            stickDir = -1;
        }
        if (nav != null && nav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    private static boolean isDirection(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private int currentCount() {
        return folders.isEmpty() ? items.size() : folders.size();
    }

    private void resetGridFocus() {
        gridActive = true;
        gridFocus = 0;
        grid.setSelection(0);
        updateFocusRing(grid);
    }

    private void moveGrid(int code) {
        int count = currentCount();
        if (count <= 0) return;
        if (gridFocus < 0) gridFocus = 0;
        int col = gridFocus % gridColumns;
        int row = gridFocus / gridColumns;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && col > 0) {
            gridFocus--;
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT
                && col < gridColumns - 1 && gridFocus + 1 < count) {
            gridFocus++;
        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (row > 0) {
                gridFocus -= gridColumns;
            } else {
                gridActive = false;
                if (tab >= 0 && tab < tabChips.size()) tabChips.get(tab).requestFocus();
                return;
            }
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (gridFocus + gridColumns < count) {
                gridFocus += gridColumns;
            } else if (selectionMode && deleteChip != null) {
                gridActive = false;
                deleteChip.requestFocus();
                return;
            } else {
                return;
            }
        }
        gridFocus = Math.max(0, Math.min(count - 1, gridFocus));
        grid.setSelection(gridFocus);
        grid.post(new Runnable() {
            @Override public void run() { updateGridRing(); }
        });
    }

    private void activateFocused() {
        int count = currentCount();
        if (gridFocus < 0 || gridFocus >= count) return;
        if (folders.isEmpty()) {
            MediaItem m = items.get(gridFocus);
            if (selectionMode) toggleSelection(m); else open(m);
        } else {
            Folder f = folders.get(gridFocus);
            if (selectionMode) {
                toggleFolder(f);
            } else {
                folderId = f.id;
                folderName = f.name;
                populate();
                resetGridFocus();
            }
        }
    }

    private void selectFocused() {
        if (gridActive) {
            int count = currentCount();
            if (gridFocus < 0 || gridFocus >= count) return;
            if (folders.isEmpty()) {
                MediaItem m = items.get(gridFocus);
                if (selectionMode) toggleSelection(m); else select(m);
            } else {
                Folder f = folders.get(gridFocus);
                if (selectionMode) toggleFolder(f); else selectFolder(f);
            }
            return;
        }
        View focus = getCurrentFocus();
        if (focus instanceof Tile) {
            Tile tile = (Tile) focus;
            if (tile.mediaItem != null) {
                if (selectionMode) toggleSelection(tile.mediaItem);
                else select(tile.mediaItem);
            } else if (tile.folderItem != null) {
                if (selectionMode) toggleFolder(tile.folderItem);
                else selectFolder(tile.folderItem);
            }
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        boolean tight = Ui.widthDp(this) < 700;
        card.setPadding(dp(tight ? 16 : 22), dp(tight ? 8 : 12),
                dp(tight ? 16 : 22), dp(tight ? 7 : 10));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        root.addView(card, cardLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(buildTabs(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(4), dp(4), dp(4), dp(2));

        backChip = text("\u2039", 13f, accent, true);
        backChip.setGravity(Gravity.CENTER);
        backChip.setFocusable(true);
        backChip.setPadding(dp(10), dp(2), dp(10), dp(2));
        backChip.setVisibility(View.GONE);
        backChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                clearSelection();
                folderId = null;
                populate();
                gridActive = true;
                gridFocus = 0;
                grid.setSelection(0);
                updateFocusRing(grid);
            }
        });
        statusRow.addView(backChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = text("", 9f, MUTED, false);
        status.setPadding(dp(3), 0, dp(3), 0);
        statusRow.addView(status, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        selectionRow = new LinearLayout(this);
        selectionRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionRow.setGravity(Gravity.CENTER_VERTICAL);
        selectionRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        selectionRow.setVisibility(View.GONE);
        selectionCount = text("", 9f, TEXT, true);
        selectionCount.setPadding(dp(6), 0, dp(3), 0);
        selectionCount.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView selDelete = actionChip(Loc.s(this, "DELETE"), true);
        selDelete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                confirmDeleteSelection();
            }
        });
        deleteChip = selDelete;
        selectionRow.addView(selDelete, chipLp(0));
        copyChip = actionChip(Loc.s(this, "COPY"), false);
        copyChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                copySelection();
            }
        });
        selectionRow.addView(copyChip, chipLp(4));
        TextView selClear = actionChip("\u00d7", false);
        selClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                clearSelection();
            }
        });
        clearChip = selClear;
        selectionRow.addView(selClear, chipLp(4));
        selectionRow.addView(selectionCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        grid = new GridView(this);
        int columns = Ui.widthDp(this) >= 700 ? 6 : 4;
        gridColumns = columns;
        grid.setNumColumns(columns);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(4), 0, dp(4));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(true);
        grid.setFocusable(true);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(selectionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        focusRing = new FocusRingDrawable(this, accent);
        root.getOverlay().add(focusRing);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(
                new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        if (newFocus == grid) {
                            gridActive = true;
                            if (gridFocus < 0) gridFocus = 0;
                            grid.post(new Runnable() {
                                @Override public void run() { updateGridRing(); }
                            });
                        } else if (newFocus != null) {
                            if (!tabChips.contains(newFocus)) gridActive = false;
                            updateFocusRing(newFocus);
                        } else {
                            updateFocusRing(null);
                        }
                    }
                });
        root.getViewTreeObserver().addOnTouchModeChangeListener(
                new ViewTreeObserver.OnTouchModeChangeListener() {
                    @Override public void onTouchModeChanged(boolean isInTouchMode) {
                        if (isInTouchMode) gridActive = false;
                        updateFocusRing(getCurrentFocus());
                    }
                });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }
            @Override public void onScroll(AbsListView view, int firstVisibleItem,
                    int visibleItemCount, int totalItemCount) {
                updateFocusRing(getCurrentFocus());
            }
        });
    }

    private void updateFocusRing(View focus) {
        if (focusRing == null) return;
        if (gridActive) {
            updateGridRing();
            return;
        }
        if (focus == null || !focus.isShown() || rootView == null
                || getWindow().getDecorView().isInTouchMode()) {
            focusRing.setBounds(0, 0, 0, 0);
            return;
        }
        positionRing(focus);
    }

    private void updateGridRing() {
        if (focusRing == null) return;
        if (!gridActive || grid == null) {
            updateFocusRing(getCurrentFocus());
            return;
        }
        int first = grid.getFirstVisiblePosition();
        View child = grid.getChildAt(gridFocus - first);
        if (child != null && child.isShown()) {
            positionRing(child);
        } else {
            focusRing.setBounds(0, 0, 0, 0);
        }
    }

    private void positionRing(View focus) {
        if (focus == null || rootView == null || focusRing == null) {
            if (focusRing != null) focusRing.setBounds(0, 0, 0, 0);
            return;
        }
        int[] loc = new int[2];
        int[] self = new int[2];
        focus.getLocationOnScreen(loc);
        rootView.getLocationOnScreen(self);
        int left = loc[0] - self[0];
        int top = loc[1] - self[1];
        boolean accentFilled = focus == deleteChip
                || (tabChips.contains(focus) && folderId == null
                        && tab == tabChips.indexOf(focus));
        focusRing.setAccent(accentFilled ? 0xFFFFFFFF : accent);
        focusRing.setRingRadius(FocusRingDrawable.radiusFor(focus, this));
        focusRing.setBounds(left, top, left + focus.getWidth(), top + focus.getHeight());
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(("Pavo " + Loc.s(this, "Gallery"))
                .toUpperCase(Locale.getDefault()), 20f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        subtitle = text(Loc.s(this, "PHOTOS & VIDEOS"), 9.5f, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(34);
        tlp.rightMargin = dp(34);
        header.addView(titles, tlp);

        TextView close = text("\u00d7", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(true);
        close.setPadding(dp(10), 0, dp(2), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finishAnimated();
            }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.END;
        header.addView(close, clp);
        return header;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));
        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int index = i;
            TextView chip = text(Loc.s(this, TAB_KEYS[i]), 8.5f, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setFocusable(true);
            chip.setPadding(dp(2), dp(8), dp(2), dp(8));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    clearSelection();
                    tab = index;
                    folderId = null;
                    populate();
                    gridActive = true;
                    gridFocus = 0;
                    grid.setSelection(0);
                    updateFocusRing(grid);
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    updateTabChip(index, hasFocus);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < TAB_KEYS.length - 1 ? dp(4) : 0;
            row.addView(chip, lp);
            tabChips.add(chip);
        }
        return row;
    }

    private void updateTabs() {
        for (int i = 0; i < tabChips.size(); i++) updateTabChip(i, tabChips.get(i).hasFocus());
    }

    private void updateTabChip(int index, boolean hasFocus) {
        if (index < 0 || index >= tabChips.size()) return;
        TextView chip = tabChips.get(index);
        boolean selected = tab == index && folderId == null;
        if (selected) {
            chip.setTextColor(0xFF071018);
            chip.setBackground(Ui.rounded(accent, dp(10), accent, dp(1)));
        } else {
            chip.setTextColor(TEXT);
            chip.setBackground(Ui.rounded(0x18FFFFFF, dp(10), 0x22FFFFFF, dp(1)));
        }
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create("sans-serif", Typeface.NORMAL));
        return v;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    // ------------------------------------------------------------------
    // Media scan
    // ------------------------------------------------------------------

    private void requestMediaAccess() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (Environment.isExternalStorageManager()) return;
        boolean images = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED;
        boolean videos = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED;
        if (images && videos) return;
        List<String> request = new ArrayList<>();
        request.add(Manifest.permission.READ_MEDIA_IMAGES);
        request.add(Manifest.permission.READ_MEDIA_VIDEO);
        if (Build.VERSION.SDK_INT >= 34) {
            request.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        requestPermissions(request.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) scan();
    }

    private void scan() {
        final int myToken = ++token;
        final Context context = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                Map<String, MediaItem> merged = new HashMap<>();
                for (MediaItem m : queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)) {
                    merged.put(m.path, m);
                }
                for (MediaItem m : queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)) {
                    merged.put(m.path, m);
                }
                File root = Environment.getExternalStorageDirectory();
                Set<String> visited = new HashSet<>();
                scanDir(new File(root, "DCIM"), 0, merged, visited);
                scanDir(new File(root, "Pictures"), 0, merged, visited);
                scanDir(new File(root, "Movies"), 0, merged, visited);
                scanDir(new File(root, "Download"), 0, merged, visited);
                scanDir(new File(root, "Documents"), 0, merged, visited);
                scanDir(new File(root, "Android/media"), 0, merged, visited);
                scanRootFiles(root, merged);

                List<MediaItem> photos = new ArrayList<>();
                List<MediaItem> videos = new ArrayList<>();
                for (MediaItem m : merged.values()) {
                    if (m.video) videos.add(m); else photos.add(m);
                }
                sortItems(photos);
                sortItems(videos);
                List<Folder> folders = buildFolders(photos, videos);
                final List<MediaItem> finalPhotos = photos;
                final List<MediaItem> finalVideos = videos;
                final List<Folder> finalFolders = folders;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (myToken != token || isFinishing() || isDestroyed()) return;
                        allPhotos.clear();
                        allPhotos.addAll(finalPhotos);
                        allVideos.clear();
                        allVideos.addAll(finalVideos);
                        allFolders.clear();
                        allFolders.addAll(finalFolders);
                        scanned = true;
                        lastScan = System.currentTimeMillis();
                        populate();
                    }
                });
            }
        });
    }

    private List<MediaItem> queryMedia(Uri collection, boolean video) {
        List<MediaItem> out = new ArrayList<>();
        String[] projection;
        if (video) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    COL_BUCKET_ID,
                    COL_BUCKET_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.Video.VideoColumns.DURATION
            };
        } else {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    COL_BUCKET_ID,
                    COL_BUCKET_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.MIME_TYPE
            };
        }
        try (Cursor c = getContentResolver().query(collection, projection,
                null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int bucketCol = c.getColumnIndexOrThrow(COL_BUCKET_ID);
            int nameCol = c.getColumnIndexOrThrow(COL_BUCKET_NAME);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int durationCol = video
                    ? c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) : -1;
            while (c.moveToNext()) {
                String path = c.getString(dataCol);
                if (path == null || path.isEmpty()) continue;
                MediaItem m = new MediaItem();
                m.id = c.getLong(idCol);
                m.path = path;
                m.uri = ContentUris.withAppendedId(collection, m.id);
                m.bucketId = c.getString(bucketCol);
                if (m.bucketId == null || m.bucketId.isEmpty()) {
                    m.bucketId = new File(path).getParent();
                }
                m.bucketName = c.getString(nameCol);
                m.date = c.getLong(dateCol) * 1000L;
                m.video = video;
                m.name = new File(path).getName();
                m.durationMs = durationCol >= 0 ? c.getLong(durationCol) : 0;
                out.add(m);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void scanRootFiles(File root, Map<String, MediaItem> out) {
        File[] files = root.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) addFile(f, root, out);
        }
    }

    private void scanDir(File dir, int depth, Map<String, MediaItem> out, Set<String> visited) {
        if (dir == null || depth > 6) return;
        try {
            String canonical = dir.getCanonicalPath();
            if (!visited.add(canonical)) return;
        } catch (Throwable ignored) {
            return;
        }
        String name = dir.getName();
        if (name.startsWith(".")) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        // Android/data and Android/obb are app-private; media lives in Android/media.
        if (name.equals("Android")) {
            File media = new File(dir, "media");
            if (media.isDirectory()) scanDir(media, depth + 1, out, visited);
            return;
        }
        // A .nomedia file hides this folder and everything below it.
        if (new File(dir, ".nomedia").exists()) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(f, depth + 1, out, visited);
            } else if (f.isFile()) {
                addFile(f, dir, out);
            }
        }
    }

    private void addFile(File f, File dir, Map<String, MediaItem> out) {
        String lower = f.getName().toLowerCase(Locale.US);
        boolean video = lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp")
                || lower.endsWith(".avi");
        boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp")
                || lower.endsWith(".heic") || lower.endsWith(".heif");
        if (!video && !image) return;
        String path = f.getAbsolutePath();
        if (out.containsKey(path)) return;
        MediaItem m = new MediaItem();
        m.path = path;
        m.uri = Uri.fromFile(f);
        m.video = video;
        m.date = f.lastModified();
        m.bucketId = dir.getAbsolutePath();
        m.bucketName = dir.getName();
        if (m.bucketName == null || m.bucketName.isEmpty()) m.bucketName = "Internal storage";
        m.name = f.getName();
        if (video) m.durationMs = videoDuration(path);
        out.put(path, m);
    }

    private static long videoDuration(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Throwable ignored) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sortItems(List<MediaItem> list) {
        Collections.sort(list, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem a, MediaItem b) {
                return Long.compare(b.date, a.date);
            }
        });
    }

    private static List<Folder> buildFolders(List<MediaItem> photos, List<MediaItem> videos) {
        Map<String, Folder> map = new HashMap<>();
        for (MediaItem m : photos) addToFolder(map, m);
        for (MediaItem m : videos) addToFolder(map, m);
        List<Folder> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<Folder>() {
            @Override public int compare(Folder a, Folder b) {
                return Long.compare(b.date, a.date);
            }
        });
        return out;
    }

    private static void addToFolder(Map<String, Folder> map, MediaItem m) {
        String key = m.bucketId == null || m.bucketId.isEmpty() ? "unknown" : m.bucketId;
        Folder f = map.get(key);
        if (f == null) {
            f = new Folder();
            f.id = key;
            f.name = m.bucketName == null || m.bucketName.isEmpty()
                    ? "Internal storage" : m.bucketName;
            map.put(f.id, f);
        }
        f.count++;
        if (m.date >= f.date) {
            f.date = m.date;
            if (f.cover == null || m.date >= f.cover.date) f.cover = m;
        }
    }

    private void populate() {
        items.clear();
        folders.clear();
        if (folderId != null) {
            for (MediaItem m : allPhotos) if (folderId.equals(m.bucketId)) items.add(m);
            for (MediaItem m : allVideos) if (folderId.equals(m.bucketId)) items.add(m);
            sortItems(items);
            backChip.setVisibility(View.VISIBLE);
            status.setText(items.size() + " " + Loc.s(this, "items"));
        } else {
            backChip.setVisibility(View.GONE);
            if (tab == TAB_FOLDERS) {
                folders.addAll(allFolders);
                status.setText(folders.size() + " " + Loc.s(this, "folders"));
            } else if (tab == TAB_PHOTOS) {
                items.addAll(allPhotos);
                status.setText(items.size() + " " + Loc.s(this, "photos"));
            } else {
                items.addAll(allVideos);
                status.setText(items.size() + " " + Loc.s(this, "videos"));
            }
        }
        if (!scanned) status.setText(Loc.s(this, "Scanning\u2026"));
        else if (folders.isEmpty() && items.isEmpty()) status.setText(Loc.s(this, "No media"));
        adapter.notifyDataSetChanged();
        updateTabs();
        updateSelectionUi();
        int count = currentCount();
        if (gridFocus >= count) gridFocus = Math.max(0, count - 1);
        if (gridActive && grid != null) {
            grid.setSelection(Math.max(0, gridFocus));
            grid.post(new Runnable() {
                @Override public void run() { updateGridRing(); }
            });
        }
    }

    private void open(MediaItem item) {
        Intent intent = new Intent(this,
                item.video ? VideoPlayerActivity.class : ImageViewerActivity.class);
        intent.putExtra(item.video
                ? VideoPlayerActivity.EXTRA_PATH : ImageViewerActivity.EXTRA_PATH, item.path);
        startActivity(intent);
    }

    // ------------------------------------------------------------------
    // Grid
    // ------------------------------------------------------------------

    private LinearLayout.LayoutParams chipLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.leftMargin = dp(leftMargin);
        return lp;
    }

    private TextView actionChip(String label, boolean primary) {
        TextView b = text(label, 9f, primary ? 0xFF071018 : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(Ui.rounded(primary ? accent : 0x18FFFFFF, dp(9),
                primary ? accent : 0x22FFFFFF, dp(1)));
        return b;
    }

    private void select(MediaItem item) {
        selectedFolderId = null;
        selectedPaths.add(item.path);
        selectionMode = true;
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(MediaItem item) {
        selectedFolderId = null;
        if (!selectedPaths.remove(item.path)) selectedPaths.add(item.path);
        selectionMode = !selectedPaths.isEmpty();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void clearSelection() {
        if (selectedPaths.isEmpty() && !selectionMode) return;
        selectedPaths.clear();
        selectedFolderId = null;
        selectionMode = false;
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    private void updateSelectionUi() {
        if (selectionRow == null) return;
        selectionRow.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (statusRow != null) {
            statusRow.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        }
        if (copyChip != null) {
            copyChip.setVisibility(selectedFolderId == null ? View.VISIBLE : View.GONE);
        }
        if (selectionMode && selectionCount != null) {
            selectionCount.setText(selectedPaths.size() + " " + Loc.s(this, "items"));
        }
    }

    private void selectFolder(Folder folder) {
        selectedFolderId = folder.id;
        for (MediaItem m : allPhotos) {
            if (folder.id.equals(m.bucketId)) selectedPaths.add(m.path);
        }
        for (MediaItem m : allVideos) {
            if (folder.id.equals(m.bucketId)) selectedPaths.add(m.path);
        }
        selectionMode = !selectedPaths.isEmpty();
        updateSelectionUi();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void toggleFolder(Folder folder) {
        if (folder.id.equals(selectedFolderId)) {
            selectedFolderId = null;
            for (MediaItem m : allPhotos) {
                if (folder.id.equals(m.bucketId)) selectedPaths.remove(m.path);
            }
            for (MediaItem m : allVideos) {
                if (folder.id.equals(m.bucketId)) selectedPaths.remove(m.path);
            }
            selectionMode = !selectedPaths.isEmpty();
            updateSelectionUi();
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        selectedFolderId = folder.id;
        boolean all = folderFullySelected(folder);
        for (MediaItem m : allPhotos) {
            if (!folder.id.equals(m.bucketId)) continue;
            if (all) selectedPaths.remove(m.path); else selectedPaths.add(m.path);
        }
        for (MediaItem m : allVideos) {
            if (!folder.id.equals(m.bucketId)) continue;
            if (all) selectedPaths.remove(m.path); else selectedPaths.add(m.path);
        }
        selectionMode = !selectedPaths.isEmpty();
        updateSelectionUi();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private boolean folderFullySelected(Folder folder) {
        int total = 0;
        int selected = 0;
        for (MediaItem m : allPhotos) {
            if (!folder.id.equals(m.bucketId)) continue;
            total++;
            if (selectedPaths.contains(m.path)) selected++;
        }
        for (MediaItem m : allVideos) {
            if (!folder.id.equals(m.bucketId)) continue;
            total++;
            if (selectedPaths.contains(m.path)) selected++;
        }
        return total > 0 && selected == total;
    }

    private List<MediaItem> selectedItems() {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem m : allPhotos) {
            if (selectedPaths.contains(m.path)) out.add(m);
        }
        for (MediaItem m : allVideos) {
            if (selectedPaths.contains(m.path)) out.add(m);
        }
        return out;
    }

    private void confirmDeleteSelection() {
        final List<MediaItem> targets = selectedItems();
        if (targets.isEmpty()) return;
        showDeleteDialog(targets.size() + " " + Loc.s(this, "items"),
                Loc.s(this, "This cannot be undone."), new Runnable() {
                    @Override public void run() {
                        deleteItems(targets);
                        clearSelection();
                    }
                });
    }

    private void copySelection() {
        List<MediaItem> targets = selectedItems();
        if (targets.isEmpty()) return;
        try {
            String label = "Pavo " + Loc.s(this, "Gallery");
            ClipData clip = null;
            for (MediaItem m : targets) {
                Uri uri = clipboardUri(m);
                if (uri == null) continue;
                if (clip == null) {
                    clip = ClipData.newUri(getContentResolver(), label, uri);
                } else {
                    clip.addItem(new ClipData.Item(uri));
                }
            }
            if (clip == null) throw new IllegalStateException();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException();
            cm.setPrimaryClip(clip);
            LToast.makeText(this, Loc.s(this, "Copied"), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "Could not copy"),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private Uri clipboardUri(MediaItem item) {
        if (item.uri != null && "content".equals(item.uri.getScheme())) return item.uri;
        try {
            return PavoFileProvider.uriFor(new File(item.path));
        } catch (Throwable t) {
            return null;
        }
    }

    private void showDeleteConfirm(final MediaItem item) {
        String name = item.name == null ? new File(item.path).getName() : item.name;
        showDeleteDialog(name, Loc.s(this, "This cannot be undone."), new Runnable() {
            @Override public void run() {
                deleteItems(Collections.singletonList(item));
            }
        });
    }

    private void showDeleteFolder(final Folder folder) {
        final List<MediaItem> targets = new ArrayList<>();
        for (MediaItem m : allPhotos) if (folder.id.equals(m.bucketId)) targets.add(m);
        for (MediaItem m : allVideos) if (folder.id.equals(m.bucketId)) targets.add(m);
        if (targets.isEmpty()) return;
        String message = targets.size() + " " + Loc.s(this, "items")
                + " \u00b7 " + Loc.s(this, "This cannot be undone.");
        showDeleteDialog(folder.name, message, new Runnable() {
            @Override public void run() {
                deleteItems(targets);
            }
        });
    }

    private void showDeleteDialog(String headingText, String message, final Runnable onConfirm) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Backdrop backdrop = null;
        try {
            backdrop = Backdrop.capture(getWindow().getDecorView(), 12);
        } catch (Throwable ignored) {
        }
        GlassPanel panel = new GlassPanel(this, backdrop, accent, 0x800B0E14, 20, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0xD8);
        panel.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        panel.setBackdropScreenAnchored(true);
        panel.setPadding(dp(20), dp(15), dp(20), dp(13));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text(Loc.s(this, "DELETE ITEM(S)?"), 10f, 0xFFFFFFFF, false);
        eyebrow.setLetterSpacing(0.12f);
        col.addView(eyebrow);
        TextView heading = text(headingText, 17f, TEXT, false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        heading.setPadding(0, dp(3), 0, dp(4));
        col.addView(heading);
        TextView body = text(message, 10f, MUTED, false);
        body.setPadding(0, dp(2), 0, dp(10));
        col.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = dialogButton(Loc.s(this, "CANCEL"), false);
        TextView ok = dialogButton(Loc.s(this, "DELETE"), true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                onConfirm.run();
            }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        okLp.leftMargin = dp(8);
        buttons.addView(ok, okLp);
        col.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(col);

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(460));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.52f;
            window.setAttributes(lp);
        }
        final ControllerNav dialogNav = new ControllerNav(this,
                dialog.getWindow().getDecorView());
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                dialogNav.release();
            }
        });
        dialog.show();
        dialogNav.attachDialog(dialog, panel);
    }

    private TextView dialogButton(String label, boolean primary) {
        TextView b = text(label, 10f, primary ? 0xFF071018 : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(Ui.rounded(primary ? accent : 0x18FFFFFF, dp(10),
                primary ? accent : 0x22FFFFFF, dp(1)));
        return b;
    }

    private void deleteItems(List<MediaItem> targets) {
        boolean any = false;
        for (MediaItem item : targets) any |= deleteFile(item);
        if (!any) {
            LToast.makeText(this, Loc.s(this, "Could not delete"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        for (MediaItem item : targets) {
            cache.remove(item.path + "|" + (item.video ? 1 : 0));
            allPhotos.remove(item);
            allVideos.remove(item);
        }
        allFolders.clear();
        allFolders.addAll(buildFolders(allPhotos, allVideos));
        populate();
        LToast.makeText(this, Loc.s(this, "Deleted"), Toast.LENGTH_SHORT).show();
    }

    private boolean deleteFile(MediaItem item) {
        boolean ok = false;
        try {
            if (item.uri != null && "content".equals(item.uri.getScheme())) {
                ok = getContentResolver().delete(item.uri, null, null) > 0;
            }
        } catch (Throwable ignored) {
        }
        try {
            File file = new File(item.path);
            if (file.exists()) ok = file.delete() || ok;
            if (!file.exists()) ok = true;
        } catch (Throwable ignored) {
        }
        try {
            MediaScannerConnection.scanFile(this, new String[]{item.path}, null, null);
        } catch (Throwable ignored) {
        }
        return ok;
    }

    private final class TileAdapter extends BaseAdapter {
        @Override public int getCount() {
            return folders.isEmpty() ? items.size() : folders.size();
        }

        @Override public Object getItem(int position) {
            return folders.isEmpty() ? items.get(position) : folders.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile = convertView instanceof Tile ? (Tile) convertView : new Tile();
            if (folders.isEmpty()) {
                tile.bindMedia(items.get(position));
            } else {
                tile.bindFolder(folders.get(position));
            }
            return tile;
        }
    }

    private final class Tile extends FrameLayout {
        private final ImageView image;
        private final TextView label;
        private final TextView badge;
        private final TextView play;
        private final TextView check;
        private String boundKey = "";
        private boolean selectedNow;
        private MediaItem mediaItem;
        private Folder folderItem;

        Tile() {
            super(GalleryActivity.this);
            setFocusable(true);
            setClipToOutline(true);
            setBackground(tileBackground(false));

            image = new ImageView(GalleryActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0x22000000);
            addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            label = new TextView(GalleryActivity.this);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            label.setTextColor(0xF2FFFFFF);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.BOTTOM | Gravity.START);
            label.setPadding(dp(6), dp(4), dp(6), dp(4));
            label.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.gravity = Gravity.BOTTOM;
            addView(label, llp);

            badge = new TextView(GalleryActivity.this);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
            badge.setTextColor(0xFFFFFFFF);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            badge.setBackground(Ui.rounded(0xCC000000, dp(7)));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.TOP | Gravity.END;
            blp.setMargins(dp(6), dp(6), dp(6), dp(6));
            addView(badge, blp);

            play = new TextView(GalleryActivity.this);
            play.setText("\u25b6");
            play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            play.setTextColor(0xE6FFFFFF);
            play.setGravity(Gravity.CENTER);
            play.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            addView(play, plp);

            check = new TextView(GalleryActivity.this);
            check.setText("\u2713");
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            check.setTextColor(0xFF071018);
            check.setGravity(Gravity.CENTER);
            check.setPadding(dp(4), dp(1), dp(4), dp(2));
            check.setBackground(Ui.rounded(accent, dp(9)));
            check.setVisibility(View.GONE);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.TOP | Gravity.START;
            clp.setMargins(dp(6), dp(6), dp(6), dp(6));
            addView(check, clp);

            setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(tileBackground(selectedNow));
                }
            });
        }

        private GradientDrawable tileBackground(boolean selected) {
            int fill = selected ? 0x44FFFFFF : 0x18FFFFFF;
            int stroke = selected ? accent : 0x22FFFFFF;
            return Ui.rounded(fill, dp(10), stroke, dp(selected ? 2 : 1));
        }

        void bindFolder(final Folder f) {
            boundKey = "folder:" + f.id;
            mediaItem = null;
            folderItem = f;
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    if (selectionMode) {
                        toggleFolder(f);
                    } else {
                        folderId = f.id;
                        folderName = f.name;
                        populate();
                        grid.setSelection(0);
                    }
                }
            });
            setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Haptics.click(v);
                    selectFolder(f);
                    return true;
                }
            });
            label.setVisibility(View.VISIBLE);
            label.setText(f.name);
            badge.setVisibility(f.count > 0 ? View.VISIBLE : View.GONE);
            badge.setText(String.valueOf(f.count));
            play.setVisibility(View.GONE);
            selectedNow = folderFullySelected(f);
            check.setVisibility(selectedNow ? View.VISIBLE : View.GONE);
            setBackground(tileBackground(selectedNow));
            setContentDescription(f.name);
            if (f.cover != null) showThumb(f.cover, boundKey); else clearThumb();
        }

        void bindMedia(final MediaItem m) {
            boundKey = "media:" + m.path;
            mediaItem = m;
            folderItem = null;
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    if (selectionMode) {
                        toggleSelection(m);
                    } else {
                        open(m);
                    }
                }
            });
            setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Haptics.click(v);
                    select(m);
                    return true;
                }
            });
            label.setVisibility(View.GONE);
            play.setVisibility(m.video ? View.VISIBLE : View.GONE);
            if (m.video && m.durationMs > 0) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(formatDuration(m.durationMs));
            } else {
                badge.setVisibility(View.GONE);
            }
            selectedNow = selectedPaths.contains(m.path);
            check.setVisibility(selectedNow ? View.VISIBLE : View.GONE);
            setBackground(tileBackground(selectedNow));
            setContentDescription(m.name == null ? "" : m.name);
            showThumb(m, boundKey);
        }

        private void clearThumb() {
            image.setTag("");
            image.setImageBitmap(null);
        }

        private void showThumb(MediaItem m, String key) {
            image.setTag(key);
            String cacheKey = m.path + "|" + (m.video ? 1 : 0);
            Bitmap cached = cache.get(cacheKey);
            if (cached != null) {
                image.setImageBitmap(cached);
                return;
            }
            image.setImageBitmap(null);
            if (!loading.add(cacheKey)) return;
            final ImageView target = image;
            final String tileKey = key;
            final MediaItem item = m;
            io.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap bmp = decodeThumb(item);
                    loading.remove(cacheKey);
                    if (bmp != null) cache.put(cacheKey, bmp);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (bmp != null && tileKey.equals(target.getTag())) {
                                target.setImageBitmap(bmp);
                            }
                        }
                    });
                }
            });
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
        }
    }

    private Bitmap decodeThumb(MediaItem m) {
        if (m.uri != null && "content".equals(m.uri.getScheme())) {
            try {
                Bitmap bmp = getContentResolver().loadThumbnail(
                        m.uri, new Size(320, 320), null);
                if (bmp != null) return bmp;
            } catch (Throwable ignored) {
            }
        }
        if (m.video) {
            Bitmap frame = videoFrame(m.path);
            return frame == null ? null : scaleDown(frame, 320);
        }
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(m.path, probe);
            int sample = 1;
            while ((probe.outWidth / sample) > 640 || (probe.outHeight / sample) > 640) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(m.path, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap videoFrame(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap frame = r.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = r.getFrameAtTime(0);
            return frame;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap scaleDown(Bitmap source, int max) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= max) return source;
        float scale = max / (float) longest;
        Bitmap out = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), true);
        if (out != source) source.recycle();
        return out;
    }

    private static String formatDuration(long ms) {
        long total = ms / 1000L;
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static final class MediaItem {
        long id;
        Uri uri;
        String path;
        String bucketId;
        String bucketName;
        String name;
        long date;
        long durationMs;
        boolean video;
    }

    private static final class Folder {
        String id;
        String name;
        MediaItem cover;
        int count;
        long date;
    }
}
