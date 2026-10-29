package com.ben.pavo;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.NotificationManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.XmlResourceParser;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.util.DisplayMetrics;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.VelocityTracker;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class MainActivity extends Activity {

    private static final String[] SEED_DOCK = {
            "com.android.chrome",
            "com.epicgames.fortnite",
            "com.android.gallery3d",
            "com.android.documentsui",
            "com.android.settings",
            "com.android.vending"
    };

    private static final String[] SEED_FAVORITES = {
            "com.android.deskclock",
            "com.android.music",
            "com.android.calendar",
            "com.android.soundrecorder",
            "com.aure.clustertune",
            "com.franco.kernel"
    };

    private static final int REQ_PICK_IMAGE = 4211;
    private static final int REQ_LOCATION = 4212;
    private static final int REQ_WIDGET_BIND = 4213;
    private static final int REQ_WIDGET_CONFIGURE = 4214;
    private static final int REQ_IMPORT_SETUP = 4215;
    private static final int WIDGET_HOST_ID = 0x4175726F;

    private static final int PICK_WALLPAPER = 0;
    private static final int PICK_ICON = 1;
    private static final int PICK_DOCK_ICON = 2;
    private static final String PERF_LOG_PKG = "com.ben.pavo.perflogs";
    private static final String CONTROLLER_MAP_PKG = "com.ben.pavo.controllermap";
    private static final String PERF_TUNER_PKG = "com.ben.pavo.perftuner";
    private static final String DISPLAY_PKG = "com.ben.pavo.display";
    private static final String RGB_PKG = "com.ben.pavo.rgb";
    private static final String FAN_PKG = "com.ben.pavo.fan";
    private static final String OVERLAY_PKG = "com.ben.pavo.overlay";
    private static final String TOUCH_MAP_PKG = "com.ben.pavo.touchmapper";
    private static final String DEVICE_HEALTH_PKG = "com.ben.pavo.devicehealth";
    private static final String BENCHMARK_PKG = "com.ben.pavo.benchmark";
    private static final String FILE_MANAGER_PKG = "com.ben.pavo.files";
    private static final String SCREEN_RECORDER_PKG = "com.ben.pavo.screenrecorder";

    private static final int SRC_NONE = -1;
    private static final int SRC_HOME = 0;
    private static final int SRC_DOCK = 1;
    private static final int SRC_DRAWER = 2;
    private static final int SRC_FOLDER = 3;

    private static final int HIT_NONE = 0;
    private static final int HIT_HOME = 1;
    private static final int HIT_DOCK = 2;
    private static final int HIT_TRASH = 3;

    private Prefs prefs;
    private Store store;
    private BroadcastReceiver shortcutRefresh;
    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private final Map<Integer, String> widgetSizeKeys = new HashMap<>();
    private WidgetFrame dragWidgetFrame;
    private Store.PlacedWidget dragWidget;
    private WidgetFrame resizeWidgetFrame;
    private WidgetFrame resizeGestureFrame;
    private GridPage resizeDelegatePage;
    private Store.PlacedWidget resizeWidget;
    private boolean widgetResizeMode;
    private GridPage resizePage;
    private int resizeEdge;
    private int resizeStartCell;
    private int resizeStartCols;
    private int resizeStartRows;
    private int resizeCurrentCell;
    private int resizeCurrentCols;
    private int resizeCurrentRows;
    private boolean resizeTargetValid = true;
    private float resizeStartX;
    private float resizeStartY;
    private float resizeCellW;
    private float resizeCellH;
    private float resizeFrameLeft, resizeFrameTop;
    private float resizeFrameWidth, resizeFrameHeight;
    private float widgetDragStartX, widgetDragStartY;
    private float widgetDragDownX, widgetDragDownY;
    private Bitmap widgetDragBitmap;
    private int widgetDropPage = -1, widgetDropRow = -1, widgetDropCol = -1;
    private boolean widgetDropTrash;
    private AppWidgetProviderInfo pendingWidgetInfo;
    private int pendingWidgetId = -1;

    private WallpaperView wallpaper;
    private VideoWallpaperView videoWallpaper;
    private boolean pendingLiveWallpaper;
    private FrameLayout root;
    private FrameLayout homeLayer;
    private LinearLayout content;
    private Pager pager;
    private LinearLayout dots;
    private DockView dock;
    private ImageButton dockDrawerButton;

    private TextView timeView;
    private TextView ampmView;
    private TextView dateView;
    private TextView weatherView;
    private WeatherGlyph weatherGlyph;
    private LinearLayout clockBox;
    private boolean clockLongFired, clockDragging;
    private boolean clockResizeMode, clockResizing;
    private int clockResizeEdge;
    private int clockPendingEdge;
    private float clockResizeStartScale;
    private float clockResizeStartX, clockResizeStartY;
    private long clockLastTapAt;
    private Runnable clockSingleTap;
    private float clockDownRawX, clockDownRawY;
    private int clockSpanCols = 2, clockSpanRows = 1;
    private int gridCols = 6, gridRows = 3;
    private Bitmap clockDragBitmap;
    private TextView batteryText;
    private Intent lastBatteryIntent;
    private LinearLayout batteryPill;
    private TextView statsText;
    private BatteryGlyph batteryGlyph;
    private LinearLayout headerStatusCluster;
    private RecordingStatusGlyph recordingStatusGlyph;
    private boolean recordingIndicatorDown;
    private float recordingIndicatorDownX, recordingIndicatorDownY;
    private HeaderStatusGlyph bluetoothStatusGlyph;
    private HeaderStatusGlyph soundStatusGlyph;

    private FrameLayout drawer;
    private FrameLayout drawerCard;
    private DrawerScrimView drawerScrim;
    private LiquidGlassShadowView drawerShadow;
    private GlassHighlightView drawerHighlight;
    private LiveBackdropView drawerBackdrop;
    private GlassPanel drawerGlass;
    private EditText search;
    private GridView drawerGrid;
    private DrawerAdapter drawerAdapter;
    private boolean drawerPopup;
    private boolean reopenDrawerAfterLaunch;
    private boolean reopenDrawerPopup;
    private IconPack.Resolver iconPack;
    private TextView emptyView;
    private MagicLampView magicLamp;
    private ValueAnimator magicAnim;
    private Bitmap magicBitmap;
    private int magicSerial;
    private int drawerAnimationToken;
    private boolean drawerSwipeActive;
    private int homeSwipeStartPage = -1;
    private boolean drawerCloseSwipeActive;
    private boolean drawerCloseArmed;
    private float drawerCloseStartProgress;
    private float drawerSwipeProgress;
    private ValueAnimator drawerSlideAnim;
    private VelocityTracker homeVelocity;
    private VelocityTracker drawerCloseVelocity;

    // folder overlay
    private FrameLayout folderOverlay;
    private FrameLayout folderCard;
    private TextView folderTitle;
    private GridView folderGrid;
    private FolderAdapter folderAdapter;
    private final List<Apps.Entry> folderApps = new ArrayList<>();
    private Item openFolderItem;
    private Item dragFolder;
    private Item dragChild;
    private int folderCols = 3;
    private int folderCardW = 0;
    private int folderCardH = 0;
    private final RectF folderSourceRect = new RectF();
    private GlassPanel folderGlass;
    private GlassPanel settingsGlass;
    private GlassPanel settingsSubmenuGlass;
    private Backdrop settingsBackdrop;
    private final Map<String, LinearLayout> settingsCategoryPanels = new HashMap<>();
    private Dialog settingsDialog;
    private FrameLayout settingsRootView;
    private View settingsCardView;
    private View settingsSubmenuScrim;
    private View settingsFocusVeil;
    private View settingsMainPanel;
    private ScrollView settingsMainScroll;
    private FocusRingDrawable settingsFocusRing;
    private View settingsFocusHost;
    private View settingsFocusOwner;
    private final List<View> settingsFocusList = new ArrayList<>();
    private final List<SettingsOptionIcon> settingsCategoryIcons = new ArrayList<>();
    private int settingsFocusIndex = -1;
    private int settingsFocusToken;
    private String settingsSubmenuKey;
    private int settingsStickDir = -1;
    private int settingsStickAxis;
    private final long[] settingsSliderStickAt = {0L};
    private final long[] settingsScrollStickAt = {0L};
    private final long[] drawerScrollStickAt = {0L};
    private int settingsStickHeldDir = -1;
    private int settingsStickTicks = 1;
    private long lastSettingsNavAt;
    private boolean settingsCardShifted;

    private final Runnable settingsStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (settingsDialog == null || settingsStickHeldDir < 0) return;
            settingsStickDir = settingsStickHeldDir;
            moveSettingsFocus(settingsStickHeldDir, settingsStickTicks);
            settingsStickTicks = Math.min(6, settingsStickTicks + 1);
            handler.postDelayed(this, 110);
        }
    };
    private Backdrop folderBackdrop;

    private Bitmap dragBitmap;
    private ImageView dragView;
    private LinearLayout trash;

    private final List<Apps.Entry> allApps = new ArrayList<>();
    private final List<Apps.Entry> drawerApps = new ArrayList<>();
    private Apps.Entry perfLogEntry;
    private Apps.Entry displayEntry;
    private Apps.Entry rgbEntry;
    private Apps.Entry fanEntry;
    private Apps.Entry overlayEntry;
    private Apps.Entry touchMapEntry;
    private Apps.Entry deviceHealthEntry;
    private Apps.Entry benchmarkEntry;
    private Apps.Entry fileManagerEntry;
    private Apps.Entry screenRecorderEntry;
    private Apps.Entry controllerMapEntry;
    private Apps.Entry perfTunerEntry;

    private int lastHapticPage = -1;
    private int lastDotsPage = -1;
    private int lastDotsCount = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler clockHandler = new Handler(Looper.getMainLooper());

    private boolean controllerActive;
    private View controllerReturnFocus;
    private boolean controllerAHeld;
    private boolean controllerALongFired;
    private boolean controllerYHeld;
    private boolean controllerYLongFired;
    private final Runnable controllerALongPress = new Runnable() {
        @Override
        public void run() {
            if (!controllerAHeld || controllerALongFired) return;
            View focused = getCurrentFocus();
            if (!(focused instanceof ItemView)) return;
            ItemView icon = (ItemView) focused;
            if (icon.item == null || icon.getWidth() <= 0) return;
            controllerALongFired = true;
            startLongPressRipple(icon);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!icon.isAttachedToWindow()) return;
                    if (isDrawerVisible() && icon.getParent() == drawerGrid) {
                        showItemMenu(icon, true);
                    } else if (isFolderVisible() && icon.getParent() == folderGrid) {
                        Haptics.longPress(icon);
                        showFolderItemMenu(icon);
                    } else if (!isDrawerVisible() && !isFolderVisible()) {
                        showItemMenu(icon, false);
                    }
                }
            }, 120L);
        }
    };
    private final Runnable controllerYLongPress = new Runnable() {
        @Override
        public void run() {
            if (!controllerYHeld || controllerYLongFired) return;
            if (!activityResumed || !hasWindowFocus()
                    || (settingsDialog != null && settingsDialog.isShowing())
                    || isDrawerVisible() || isFolderVisible()
                    || activeMenu != null || dragSource != null || dragging) return;
            controllerYLongFired = true;
            int w = root != null ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int h = root != null ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
            final float x = w * 0.5f;
            final float y = h * 0.55f;
            startHomeRipple(x, y, false);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!dragging && dragSource == null) showHomeMenu(x, y);
                }
            }, 140L);
        }
    };
    private LinearLayout drawerButtonFocusHost;
    private android.graphics.drawable.Drawable drawerButtonFocusRing;
    private int lastStickDir = -1;
    private int stickHeldDir = -1;
    private int stickAxis;
    private int pageTurnStickDir = -1;
    private long lastPageTurnAt;

    private final Runnable stickRepeat = new Runnable() {
        @Override
        public void run() {
            if (stickHeldDir < 0) return;
            lastStickDir = stickHeldDir;
            navigateStick(stickHeldDir);
            handler.postDelayed(this, 140);
        }
    };

    private static final long PAGE_DOTS_HIDE_MS = 1600L;
    // OLED pixel-shift. Monitor practice is 1-2 px every 1-3 minutes over a
    // small area; a 5x5 spiral gives every offset equal time, moves only one
    // pixel per step and never parks on the centre. Everything stays live.
    private static final long BURN_IN_FIRST_MS = 60000L;
    private static final long BURN_IN_INTERVAL_MS = 120000L;
    private static final long BURN_IN_JITTER_MS = 15000L;
    private static final long BURN_IN_RECHECK_MS = 15000L;
    private static final int[][] BURN_IN_OFFSETS = BurnInShift.OFFSETS;
    private final Random burnInRandom = new Random();
    private static final long BURN_IN_DIM_DELAY_MS = 5L * 60L * 1000L;
    private static final float BURN_IN_DIM_ALPHA = 0.10f;
    private View burnInDim;
    private final Runnable hidePageDots = new Runnable() {
        @Override
        public void run() {
            if (dots == null || !prefs.autoHidePageDots()) return;
            dots.animate().cancel();
            dots.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
    };

    private int burnInShiftIndex;
    private final Runnable burnInShift = new Runnable() {
        @Override
        public void run() {
            if (content == null) return;
            if (!prefs.burnInProtection()) {
                resetHomeOffset();
                handler.removeCallbacks(this);
                return;
            }
            // Never move the home under the user's finger or while a menu,
            // drawer or drag is open. Live stats and icons keep running.
            if (!homeIdleEligible()
                    || SystemClock.uptimeMillis() - lastUserActivityAt < BURN_IN_FIRST_MS) {
                handler.postDelayed(this, BURN_IN_RECHECK_MS);
                return;
            }
            burnInShiftIndex = (burnInShiftIndex + 1) % BURN_IN_OFFSETS.length;
            int[] offset = BURN_IN_OFFSETS[burnInShiftIndex];
            float dx = offset[0];
            float dy = offset[1];
            content.animate().translationX(dx).translationY(dy)
                    .setDuration(450L)
                    .setInterpolator(new DecelerateInterpolator(1.2f)).start();
            if (wallpaper != null) wallpaper.setBurnInOffset(dx, dy);
            long jitter = (long) ((burnInRandom.nextDouble() * 2.0 - 1.0)
                    * BURN_IN_JITTER_MS);
            handler.postDelayed(this, BURN_IN_INTERVAL_MS + jitter);
        }
    };

    private void resetHomeOffset() {
        if (content != null) {
            content.setTranslationX(0f);
            content.setTranslationY(0f);
        }
        if (wallpaper != null) wallpaper.setBurnInOffset(0f, 0f);
    }

    private final Runnable burnInDimCheck = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            if (!activityResumed || !prefs.burnInProtection()) {
                hideBurnInDim();
                return;
            }
            if (!homeIdleEligible()
                    || SystemClock.uptimeMillis() - lastUserActivityAt < BURN_IN_DIM_DELAY_MS) {
                handler.postDelayed(this, 15000L);
                return;
            }
            showBurnInDim();
        }
    };

    private void scheduleBurnInDim() {
        handler.removeCallbacks(burnInDimCheck);
        if (activityResumed && prefs.burnInProtection()) {
            handler.postDelayed(burnInDimCheck, BURN_IN_DIM_DELAY_MS);
        }
    }

    private void showBurnInDim() {
        if (burnInDim == null || !prefs.burnInProtection()) return;
        burnInDim.animate().cancel();
        burnInDim.animate().alpha(BURN_IN_DIM_ALPHA).setDuration(1200L).start();
    }

    private void hideBurnInDim() {
        if (burnInDim == null) return;
        burnInDim.animate().cancel();
        if (burnInDim.getAlpha() > 0f) {
            burnInDim.animate().alpha(0f).setDuration(150L).start();
        }
    }

    private void startBurnInProtection() {
        handler.removeCallbacks(burnInShift);
        if (content == null || !prefs.burnInProtection()) {
            resetHomeOffset();
            return;
        }
        // Whole home (wallpaper + icons + live stats) drifts in 1 dp steps.
        burnInShiftIndex = 0;
        handler.postDelayed(burnInShift, BURN_IN_FIRST_MS);
    }

    private void stopBurnInProtection() {
        handler.removeCallbacks(burnInShift);
    }

    private boolean activityResumed;
    private long lastUserActivityAt;

    private boolean homeIdleEligible() {
        return activityResumed && !isDrawerVisible() && !isFolderVisible()
                && activeMenu == null && settingsDialog == null && !dragging;
    }

    private boolean homeVisualsIdle;

    /** Any real input resets the pixel-shift offset immediately. */
    private void noteUserActivity() {
        lastUserActivityAt = SystemClock.uptimeMillis();
        resetHomeOffset();
        hideBurnInDim();
        scheduleBurnInDim();
        if (homeVisualsIdle) {
            homeVisualsIdle = false;
            setHomeMarqueePaused(false);
            setHomeLiquidPaused(false);
            setHomeIconsPaused(false);
        }
        handler.removeCallbacks(homeIdleTick);
        handler.postDelayed(homeIdleTick, HOME_IDLE_MS);
    }

    private static final long HOME_IDLE_MS = 20000L;

    /**
     * The live icons, dock sheen and label marquees redraw every frame. That is
     * what keeps the launcher at ~120 fps and warms the SoC while the user is
     * just looking at the home screen, so they stop after a short idle.
     */
    private final Runnable homeIdleTick = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed || homeVisualsIdle) return;
            if (!homeIdleEligible()) {
                handler.postDelayed(this, 4000L);
                return;
            }
            if (SystemClock.uptimeMillis() - lastUserActivityAt < HOME_IDLE_MS) {
                handler.postDelayed(this, 2000L);
                return;
            }
            homeVisualsIdle = true;
            setHomeMarqueePaused(true);
            setHomeLiquidPaused(true);
            setHomeIconsPaused(true);
        }
    };

    private void setHomeMarqueePaused(boolean paused) {
        if (pager != null) {
            for (int i = 0; i < pager.getChildCount(); i++) {
                View page = pager.getChildAt(i);
                if (!(page instanceof GridPage)) continue;
                for (ItemView v : ((GridPage) page).cellViews()) {
                    if (v != null) v.setMarqueePaused(paused);
                }
            }
        }
        if (dock != null) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                View child = dock.getChildAt(i);
                if (child instanceof ItemView) ((ItemView) child).setMarqueePaused(paused);
            }
        }
    }

    private void setHomeLiquidPaused(boolean paused) {
        if (dock != null) dock.setLiquidPaused(paused);
    }

    /** Freeze the animated built-in icons so the OLED can go truly static. */
    /** Only the widgets on the visible page need to sample and repaint. */
    private void updateWidgetPages(int current) {
        if (pager == null) return;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page instanceof ViewGroup) setWidgetsActive((ViewGroup) page, i == current);
        }
    }

    private void setWidgetsActive(ViewGroup group, boolean active) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof PavoHomeWidgetView) {
                ((PavoHomeWidgetView) child).setActivePage(active);
            } else if (child instanceof ViewGroup) {
                setWidgetsActive((ViewGroup) child, active);
            }
        }
    }

    private void setHomeIconsPaused(boolean paused) {
        if (content != null) setIconsPaused(content, paused);
        if (dock != null) setIconsPaused(dock, paused);
    }

    private void setIconsPaused(View view, boolean paused) {
        if (view == null) return;
        if (view instanceof ItemView) {
            ((ItemView) view).setIconAnimationPaused(paused);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setIconsPaused(group.getChildAt(i), paused);
            }
        }
    }

    // image picker
    private int pickMode = PICK_WALLPAPER;
    private Item pickTarget;
    private boolean pendingPerfOverlay;

    // drag state
    private ItemView dragSource;
    private ItemView launchSource;
    private final RectF launchReturnRect = new RectF();
    private boolean hasLaunchReturnRect;
    private int dragSrcKind = SRC_NONE;
    private int dragSrcPage = -1;
    private int dragSrcCell = -1;
    private int dragSrcDock = -1;
    private boolean dragging;
    private int hitKind;
    private int hitPage;
    private int hitCell;
    private int hitDock;
    private long lastAutoScroll;
    private float lastRawX, lastRawY;
    private long dropAnimStart;
    private boolean dragSparePage;
    private boolean dockDrawerDragging;

    private static final long EDGE_HOLD_MS = 0L;
    private static final long EDGE_SWITCH_MS = 0L;
    private int edgeDir;
    private boolean edgeScheduled;
    private float edgeAnchorX, edgeAnchorY;
    private boolean dragPeek;
    private boolean dragPeekCommitted;
    private boolean dragEdgeLatched;
    private boolean skipRefreshAfterDrag;
    private int dragEdgeDir;
    private int dragEdgePendingDir;
    private int dragSwitchPage = -1;
    private final Runnable dragEdgeSwitch = new Runnable() {
        @Override
        public void run() {
            if (!dragging || !pager.isCardMode() || dragEdgePendingDir == 0) return;
            int dir = dragEdgePendingDir;
            dragEdgePendingDir = 0;
            int current = dragSwitchPage >= 0 ? dragSwitchPage : pager.currentPage();
            int target = current + dir;
            if (target < 0 || target >= pager.pageCount()) return;
            dragSwitchPage = target;
            dragEdgeLatched = true;
            clearHoverPreview(false);
            lastAutoScroll = System.currentTimeMillis();
            // Let the cards carry across with a short, eased slide rather than
            // snapping. The dragged icon lives above the pager, so it stays
            // under the finger throughout.
            pager.setPageForDrag(target, true);
            if (dragWidget != null) {
                computeWidgetTarget(lastRawX, lastRawY);
                updateWidgetHighlight();
            }
            Haptics.edge(root);
        }
    };
    private final RectF dragSourceNormalBounds = new RectF();
    private final RectF dragSourceCardBounds = new RectF();
    private final List<WaterTarget> homeRippleTargets = new ArrayList<>();
    private ValueAnimator homeRippleAnimator;
    private boolean homeRippleActive;
    private float homeRippleX;
    private float homeRippleY;
    private float homeRippleProgress;
    private boolean homeRippleStrong;
    private boolean pendingDropRipple;
    private float pendingDropRippleX;
    private float pendingDropRippleY;
    private static final class WaterTarget {
        View view;
        float ux;
        float uy;
        float tx;
        float ty;
        float amp;
        float falloff;
        float delay;
    }
    private ItemView hoverPreviewView;
    private View hoverPreviewWidget;
    private String hoverPreviewKey;
    private int hoverPreviewPage = -1;
    private int hoverPreviewCell = -1;
    private int hoverPreviewDestPage = -1;
    private int hoverPreviewDestCell = -1;
    private Store.PlacedWidget hoverPreviewWidgetItem;
    private Store.PlacedWidget dropWidget;
    private int dropWidgetDestCell = -1;
    private final Runnable showHoverPreview = new Runnable() {
        @Override
        public void run() {
            if (!dragging) return;
            applyHoverPreview();
        }
    };
    private Map<String, List<RectF>> dropOldItemRects;
    private Map<Integer, RectF> dropOldWidgetRects;
    private String dropMovedKey;
    private String dropPreviewKey;
    private int dropMovedPage = -1;
    private int dropMovedCell = -1;
    private int dropPreviewPage = -1;
    private int dropPreviewCell = -1;
    private boolean dropTransitionPending;
    private ImageView dragPagePreview;
    private Bitmap dragPagePreviewBitmap;
    private int dragPreviewPage = -1;
    private int dragPendingPage = -1;
    private int dragPendingCell = -1;
    private final RectF dragPreviewRect = new RectF();
    private int cornerDir;
    private boolean cornerScheduled;
    private TextView pageTurnLeftHint;
    private TextView pageTurnRightHint;

    /**
     * Page-flip while dragging requires hovering at the edge for a moment, instead of
     * flipping on the first touch at the edge. This makes it much easier to choose the
     * page you actually want.
     */
    private final Runnable edgeScroll = new Runnable() {
        @Override
        public void run() {
            edgeScheduled = false;
            if (!dragging || edgeDir == 0 || pager == null) return;
            int pw = Math.max(1, pager.pageWidth());
            int page = dragPageAt(lastRawX);
            float lx = dragLocalXAt(lastRawX);
            float zone = Ui.dpf(MainActivity.this, 60);
            int dir = edgeDir;
            edgeDir = 0;
            boolean allowed = (dir < 0 && page > 0 && lx < zone)
                    || (dir > 0 && page < pager.pageCount() - 1 && lx > pw - zone);
            if (!allowed) return;
            if (!dragPeek) {
                pager.peekPage(dir, true);
                if (pager.hasPeek()) {
                    dragPeek = true;
                    dragPeekCommitted = false;
                    dragPreviewPage = pager.peekBasePage() + dir;
                    hitKind = HIT_NONE;
                    hitPage = -1;
                    hitCell = -1;
                    updateHighlights();
                    Haptics.edge(root);
                }
            }
            lastAutoScroll = System.currentTimeMillis();
        }
    };

    /** Bottom-corner page turns used when a vertical side dock blocks the usual edges. */
    private final Runnable cornerScroll = new Runnable() {
        @Override
        public void run() {
            cornerScheduled = false;
            if (!dragging || cornerDir == 0 || pager == null) return;
            int dir = cornerDir;
            cornerDir = 0;
            if (verticalDockCorner(lastRawX, lastRawY) != dir) return;
            int current = pager.currentPage();
            int target = current + dir;
            if (target < 0 || target >= pager.pageCount()) return;
            if (!dragPeek) {
                pager.peekPage(dir, true);
                if (pager.hasPeek()) {
                    dragPeek = true;
                    dragPeekCommitted = false;
                    dragPreviewPage = pager.peekBasePage() + dir;
                    hitKind = HIT_NONE;
                    hitPage = -1;
                    hitCell = -1;
                    updateHighlights();
                    Haptics.edge(root);
                }
            }
            lastAutoScroll = System.currentTimeMillis();
        }
    };

    // menus / long press
    private PopupWindow activeMenu;
    private PopupWindow menuRowsOwner;
    private FocusRingDrawable menuFocusRing;
    private final List<View> activeMenuRows = new ArrayList<>();
    private int activeMenuSelection = -1;
    private long lastMenuNavAt;
    private boolean emptyArmed;
    private boolean emptyTapCandidate;
    private long lastSleepTapAt;
    private float lastSleepTapX, lastSleepTapY;
    private float emptyDownX, emptyDownY;
    private boolean animateOnResume;
    private boolean firstHomeShown;
    private boolean animateDock;
    private boolean justCreatedFolder;
    private String lastFolderKey;
    private float dropFraction = 0.5f;

    private final Runnable emptyLongPress = new Runnable() {
        @Override
        public void run() {
            if (!emptyArmed) return;
            emptyArmed = false;
            emptyTapCandidate = false;
            if (drawer != null && drawer.getVisibility() == View.VISIBLE) return;
            if (dragSource != null || dragging) return;
            final float x = emptyDownX;
            final float y = emptyDownY;
            startHomeRipple(x, y, false);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!dragging && dragSource == null) showHomeMenu(x, y);
                }
            }, 140L);
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            // Align to the minute so the home produces one frame per minute
            // instead of one every 10 s; this is what lets an OLED idle.
            long now = System.currentTimeMillis();
            clockHandler.postDelayed(this, 60000L - (now % 60000L) + 40L);
        }
    };

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            updateStats();
            updateHeaderStatus();
            handler.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lastBatteryIntent = intent;
            handleBattery(intent);
        }
    };

    private final Runnable reloadApps = new Runnable() {
        @Override
        public void run() {
            boolean pruned = pruneMissingApps();
            pruned |= pruneMissingAllApps();
            if (pruned) {
                renderHome();
                populateDock();
            }
            loadApps();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IconPack.invalidate();
            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                android.net.Uri data = intent.getData();
                String added = data == null ? null : data.getSchemeSpecificPart();
                // Chrome installs "Add to Home screen" pages as WebAPKs; place
                // them on the home screen like the platform launcher does.
                if (added != null && added.startsWith("org.chromium.webapk")) {
                    addWebAppToHome(added);
                }
            }
            handler.removeCallbacks(reloadApps);
            handler.postDelayed(reloadApps, 400);
        }
    };

    /**
     * Puts a new shortcut in the nearest free cell: the current page first, then
     * the following pages, then the previous ones, and only then a new page.
     */
    private int placeNewItem(Item item) {
        int current = Math.max(0, pager.currentPage());
        int count = store.pageCount();
        java.util.List<Integer> order = new java.util.ArrayList<>();
        order.add(current);
        for (int p = current + 1; p < count; p++) order.add(p);
        for (int p = current - 1; p >= 0; p--) order.add(p);
        int capacity = capacity();
        for (int page : order) {
            for (int cell = 0; cell < capacity; cell++) {
                if (widgetCovers(page, cell)) continue;
                if (store.cellFree(page, cell)) {
                    store.put(page, cell, item);
                    return page;
                }
            }
        }
        return store.addToNewPage(item);
    }

    private void addWebAppToHome(String pkg) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null || launch.getComponent() == null) return;
            Item item = Item.app(pkg, launch.getComponent().getClassName());
            if (store.contains(item.key())) return;
            int page = placeNewItem(item);
            store.save();
            refreshVisible();
            final int target = page;
            pager.post(new Runnable() {
                @Override
                public void run() {
                    if (target >= 0 && target < store.pageCount()
                            && target != pager.currentPage()) {
                        pager.setPage(target, true);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(Ui.adaptiveContext(newBase));
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recreate();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installDefaultPrefs();
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        Loc.applyLocale(this);
        store = new Store(this, prefs);
        store.load();
        sanitizeLayout();
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, WIDGET_HOST_ID);

        // Keep package changes while another app (installer/store) is in front.
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        try {
            registerReceiver(packageReceiver, packageFilter);
        } catch (Throwable ignored) {
        }

        configureWindow();
        buildUi();
        loadApps();
        applyWallpaperPresentation();

        // Third-party apps can add a shortcut to the home screen. Legacy
        // broadcasts refresh the grid through this receiver; pinned shortcuts
        // arrive in the launch intent.
        shortcutRefresh = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                store = new Store(MainActivity.this, prefs);
                store.load();
                refreshVisible();
            }
        };
        try {
            registerReceiver(shortcutRefresh,
                    new IntentFilter(ShortcutReceiver.ACTION_ADDED));
        } catch (Throwable ignored) {
        }
        handleShortcutIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcutIntent(intent);
    }

    private volatile long pinnedSyncAt;

    /**
     * Picks up shortcuts that were pinned while the launcher was not in front
     * (the system only delivers the pin intent once).
     */
    private void syncPinnedShortcuts() {
        if (Build.VERSION.SDK_INT < 26) return;
        long now = SystemClock.uptimeMillis();
        if (now - pinnedSyncAt < 4000L) return;
        pinnedSyncAt = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final java.util.List<Item> missing = new java.util.ArrayList<>();
                final java.util.List<Bitmap> icons = new java.util.ArrayList<>();
                try {
                    LauncherApps launcherApps = getSystemService(LauncherApps.class);
                    if (launcherApps == null) return;
                    LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
                    query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                    java.util.List<ShortcutInfo> pinned = launcherApps.getShortcuts(
                            query, android.os.Process.myUserHandle());
                    if (pinned == null || pinned.isEmpty()) return;
                    Store current = new Store(MainActivity.this, prefs);
                    current.load();
                    for (ShortcutInfo info : pinned) {
                        String url = "shortcut://" + info.getPackage() + "/" + info.getId();
                        Item item = Item.web(url, shortcutLabel(info));
                        if (current.contains(item.key())) continue;
                        missing.add(item);
                        icons.add(shortcutIcon(info));
                    }
                } catch (Throwable ignored) {
                    return;
                }
                if (missing.isEmpty()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (int i = 0; i < missing.size(); i++) {
                            Item item = missing.get(i);
                            placeNewItem(item);
                            if (icons.get(i) != null) store.setIcon(item, icons.get(i));
                        }
                        store.save();
                        refreshVisible();
                        LToast.makeText(MainActivity.this,
                                "Pinned shortcut added to home",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "pinned-shortcuts").start();
    }

    private static String shortcutLabel(ShortcutInfo info) {
        CharSequence label = info.getShortLabel();
        if (label == null || label.length() == 0) label = info.getLongLabel();
        return label == null ? null : label.toString();
    }

    /** Adds a pinned shortcut requested by another app (requestPinShortcut). */
    private void handleShortcutIntent(Intent intent) {
        if (intent == null || Build.VERSION.SDK_INT < 26) return;
        if (!LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT.equals(intent.getAction())) return;
        LauncherApps.PinItemRequest request = intent.getParcelableExtra(
                LauncherApps.EXTRA_PIN_ITEM_REQUEST);
        if (request == null
                || request.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            return;
        }
        ShortcutInfo info = request.getShortcutInfo();
        if (info == null) return;
        if (!request.accept()) return;

        // The launcher is not given the shortcut's intent, only its identity.
        // Keep the identity and start it later through LauncherApps.
        String url = "shortcut://" + info.getPackage() + "/" + info.getId();
        CharSequence label = info.getShortLabel();
        if (label == null || label.length() == 0) label = info.getLongLabel();
        String name = label == null ? null : label.toString();
        Item item = Item.web(url, name);
        if (!store.contains(item.key())) {
            int page = placeNewItem(item);
            Bitmap icon = shortcutIcon(info);
            if (icon != null) store.setIcon(item, icon);
            store.save();
            refreshVisible();
            final int target = page;
            pager.post(new Runnable() {
                @Override
                public void run() {
                    if (target >= 0 && target < store.pageCount()
                            && target != pager.currentPage()) {
                        pager.setPage(target, true);
                    }
                }
            });
        }
        LToast.makeText(this, (name == null ? "Shortcut" : name) + " added to home",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private Bitmap shortcutIcon(ShortcutInfo info) {
        Drawable d = null;
        try {
            LauncherApps launcherApps = getSystemService(LauncherApps.class);
            if (launcherApps != null) {
                java.lang.reflect.Method getIcon = LauncherApps.class.getMethod(
                        "getShortcutIcon", String.class, String.class, int.class);
                Object value = getIcon.invoke(launcherApps, info.getPackage(), info.getId(),
                        getResources().getDisplayMetrics().densityDpi);
                if (value instanceof Drawable) d = (Drawable) value;
            }
        } catch (Throwable ignored) {
        }
        if (d == null) {
            try {
                d = getPackageManager().getApplicationIcon(info.getPackage());
            } catch (Throwable ignored) {
            }
        }
        return ShortcutReceiver.drawableBitmap(d, Ui.dp(this, 72));
    }

    private boolean launchPinnedShortcut(Item item, ItemView source) {
        String rest = item.url.substring("shortcut://".length());
        int slash = rest.lastIndexOf('/');
        if (slash <= 0) return false;
        String pkg = rest.substring(0, slash);
        String id = rest.substring(slash + 1);
        try {
            LauncherApps launcherApps = getSystemService(LauncherApps.class);
            if (launcherApps == null) return false;
            android.graphics.Rect bounds = null;
            if (source != null) {
                int[] loc = new int[2];
                source.getLocationOnScreen(loc);
                bounds = new android.graphics.Rect(loc[0], loc[1],
                        loc[0] + source.getWidth(), loc[1] + source.getHeight());
            }
            launcherApps.startShortcut(pkg, id, bounds, null,
                    android.os.Process.myUserHandle());
            return true;
        } catch (Throwable t) {
            LToast.makeText(this, "Shortcut is no longer available",
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (widgetHost != null) widgetHost.startListening();
    }

    @Override
    protected void onStop() {
        if (videoWallpaper != null) videoWallpaper.pause();
        if (widgetHost != null) widgetHost.stopListening();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (videoWallpaper != null) videoWallpaper.release();
        handler.removeCallbacks(burnInShift);
        handler.removeCallbacks(burnInDimCheck);
        cancelMagicLamp(false);
        cancelDrawerSlide();
        recycleHomeVelocity();
        recycleDrawerCloseVelocity();
        try {
            unregisterReceiver(packageReceiver);
        } catch (Throwable ignored) {
        }
        if (widgetHost != null) widgetHost.stopListening();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncPinnedShortcuts();
        if (Loc.applyLocale(this)) {
            recreate();
            return;
        }
        activityResumed = true;
        lastUserActivityAt = SystemClock.uptimeMillis();
        hideSystemBars();
        if (reopenDrawerAfterLaunch) {
            final boolean popup = reopenDrawerPopup;
            reopenDrawerAfterLaunch = false;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && drawer != null
                            && drawer.getVisibility() != View.VISIBLE) {
                        openDrawer(popup);
                    }
                }
            }, 180L);
        }
        if (wallpaper != null) wallpaper.refreshWallpaper();
        if (pendingLiveWallpaper) {
            pendingLiveWallpaper = false;
            try {
                WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
                if (info != null && !isImageWallpaper(info.getComponent())) {
                    prefs.setWallpaperMode(Prefs.MODE_DEVICE);
                }
            } catch (Throwable ignored) {
            }
        }
        applyWallpaperPresentation();
        ColorTuning.set(prefs.colorSaturation(), prefs.glowStrength());
        if (prefs.panelCalibrationActive()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    DisplayTuner.applyCalibrationOnStart(prefs);
                }
            }, "panel-calibration").start();
        }
        if (prefs.controllerRemapEnabled()) ControllerRemapManager.ensureStarted(this);
        if (PavoInputService.shouldRun(this)) PavoInputService.ensureStarted(this);
        else PavoInputService.stop(this);
        DeviceProfile.get(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                DeviceProfile.refreshWithPServer(MainActivity.this);
            }
        }, "device-probe").start();
        if (new TuneStore(this).hasOverrides()) PerAppTuneService.start(this);
        // Re-arm the background fan control after an update, a reboot or a
        // process kill; otherwise the curve only comes back when Fan Control
        // is opened by hand.
        if (new FanProfileStore(this).hasOverrides()) {
            PerAppFanService.start(this);
        } else if (prefs.fanAppMode() == FanActivity.MODE_CURVE) {
            FanCurveService.start(this);
        }
        applySurfaceTransparency();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        lastBatteryIntent = registerReceiver(batteryReceiver, f);

        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        handler.removeCallbacks(statsTick);
        handler.post(statsTick);
        updateStatsVisibility();
        updateHeaderStatusVisibility();
        updateHeaderStatus();
        if (!ScreenRecorderService.isRecording() && !ScreenRecorderService.isSaving()) {
            ScreenRecorderService.forceTouchDotsOff(this);
        }
        setHomeMarqueePaused(false);
        setHomeLiquidPaused(false);
        setHomeIconsPaused(false);
        if (pager != null) updateWidgetPages(pager.currentPage());
        homeVisualsIdle = false;
        handler.removeCallbacks(homeIdleTick);
        handler.postDelayed(homeIdleTick, HOME_IDLE_MS);
        hideBurnInDim();
        startBurnInProtection();
        scheduleBurnInDim();

        ensureDefaultWallpaper();
        updateWeatherView();
        refreshWeather(false);
        handler.removeCallbacks(weatherTick);
        handler.postDelayed(weatherTick, 30L * 60L * 1000L);
        handler.post(dockBackdropUpdate);

        if (pendingPerfOverlay) {
            pendingPerfOverlay = false;
            if (Settings.canDrawOverlays(this)) {
                prefs.setPerfOverlay(true);
                PerfOverlayService.start(this);
                LToast.makeText(this, "Performance overlay enabled", Toast.LENGTH_SHORT).show();
            } else {
                prefs.setPerfOverlay(false);
                LToast.makeText(this, "Display over other apps permission is required",
                        Toast.LENGTH_LONG).show();
            }
        } else if (prefs.perfOverlay()) {
            PerfOverlayService.start(this);
        }

        boolean pruned = pruneMissingApps();
        pruned |= pruneMissingAllApps();
        if (pruned) {
            renderHome();
            populateDock();
        }
        if (sanitizeWidgets()) renderHome();

        // Returning from an app: settle the home back in without a flash.
        if (content != null) {
            content.setAlpha(1f);
            if (launchSource != null) {
                launchSource.animate().cancel();
                launchSource.setScaleX(1f);
                launchSource.setScaleY(1f);
                launchSource.setAlpha(1f);
                launchSource = null;
            }
            if (animateOnResume) {
                animateOnResume = false;
                content.setScaleX(1.012f);
                content.setScaleY(1.012f);
                content.animate().scaleX(1f).scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(new DecelerateInterpolator(1.8f))
                        .start();
            } else if (content.getScaleX() != 1f) {
                content.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        }
    }

    /** Staggered fade/rise of the visible home icons. */
    private void animateHomeIn() {
        if (pager == null || dock == null) return;
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) return;

        List<View> views = new ArrayList<>();
        int page = pager.currentPage();
        if (page >= 0 && page < pager.getChildCount()) {
            View c = pager.getChildAt(page);
            if (c instanceof GridPage) {
                for (ItemView v : ((GridPage) c).cellViews()) {
                    if (v != null) views.add(v);
                }
            }
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            if (c instanceof ItemView) views.add(c);
        }
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            v.setAlpha(0f);
            v.setTranslationY(Ui.dpf(this, 12));
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(i * 13, 170))
                    .setDuration(230)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
        recycleHomeVelocity();
        recycleDrawerCloseVelocity();
        // Freeze only while the launcher itself is not visible.
        setHomeMarqueePaused(true);
        setHomeLiquidPaused(true);
        setHomeIconsPaused(true);
        handler.removeCallbacks(homeIdleTick);
        resetHomeOffset();
        hideBurnInDim();
        handler.removeCallbacks(burnInDimCheck);
        cancelStickRepeat();
        controllerAHeld = false;
        controllerALongFired = false;
        controllerYHeld = false;
        controllerYLongFired = false;
        handler.removeCallbacks(controllerALongPress);
        handler.removeCallbacks(controllerYLongPress);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {
        }
        try {
            unregisterReceiver(shortcutRefresh);
        } catch (Throwable ignored) {
        }
        clockHandler.removeCallbacks(clockTick);
        handler.removeCallbacks(statsTick);
        stopBurnInProtection();
        handler.removeCallbacks(weatherTick);
    }

    @Override
    public void onBackPressed() {
        if (folderOverlay != null && folderOverlay.getVisibility() == View.VISIBLE) {
            closeFolder();
            return;
        }
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        if (activeMenu != null) {
            Haptics.key(root);
            dismissActiveMenu();
            return;
        }
        // A home screen should never exit on Back.
    }

    /** Keep the dock within its visible capacity after older builds or odd drops. */
    private void sanitizeLayout() {
        boolean changed = false;
        while (store.dock.size() > 8) {
            Item extra = store.dock.remove(store.dock.size() - 1);
            if (extra != null) {
                store.addToHome(extra, capacity());
                changed = true;
            }
        }
        if (changed) {
            store.trim();
            store.save();
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
        window.setDecorFitsSystemWindows(false);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        // Redundant legacy flags keep the status bar hidden while the window is gaining
        // focus or after a transient system-bar swipe on this Android build.
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Everything that sits behind the drawer's glass card lives in one home
        // layer; the card renders a live copy of it through the glass effect.
        homeLayer = new FrameLayout(this);
        homeLayer.setClipChildren(false);
        homeLayer.setClipToPadding(false);
        root.addView(homeLayer, match());

        wallpaper = new WallpaperView(this);
        wallpaper.setBlurRadius(prefs.blur());
        wallpaper.setMode(prefs.wallpaperMode() == Prefs.MODE_VIDEO
                ? Prefs.MODE_DEVICE : prefs.wallpaperMode());
        wallpaper.setListener(new WallpaperView.Listener() {
            @Override
            public void onWallpaperChanged() {
                if (dock != null) dock.invalidate();
                if (search != null) {
                    search.setCompoundDrawableTintList(ColorStateList.valueOf(
                            wallpaper != null ? wallpaper.accent() : ThemeColors.accent()));
                }
                if (dots != null && pager != null) {
                    lastDotsCount = -1;
                    updateDots(pager.currentPage(), pager.pageCount());
                }
                refreshBuiltinIcons();
                handler.post(dockBackdropUpdate);
            }
        });
        homeLayer.addView(wallpaper, match());

        videoWallpaper = new VideoWallpaperView(this);
        videoWallpaper.setVisibility(View.GONE);
        videoWallpaper.setListener(color -> {
            if (prefs != null) prefs.setThemeAccent(color);
            if (wallpaper != null) wallpaper.setAccent(color);
        });
        homeLayer.addView(videoWallpaper, 1, match());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        int pad = Ui.dp(this, 30);
        content.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 10));
        homeLayer.addView(content, match());

        burnInDim = new View(this);
        burnInDim.setBackgroundColor(0xFF000000);
        burnInDim.setAlpha(0f);
        burnInDim.setClickable(false);
        burnInDim.setFocusable(false);
        homeLayer.addView(burnInDim, match());

        buildHeader();
        updateHeaderStatusVisibility();
        updateHeaderStatus();

        pager = new Pager(this);
        pager.setAnimationStyle(prefs.pageAnimation());
        pager.setListener(new Pager.Listener() {
            @Override
            public void onPageChanged(int index, int count) {
                boolean pageChanged = lastHapticPage != index;
                if (lastHapticPage >= 0 && pageChanged && !dragging) Haptics.page(root);
                lastHapticPage = index;
                updateDots(index, count);
                if (pageChanged) {
                    handler.removeCallbacks(dockBackdropUpdate);
                    handler.postDelayed(dockBackdropUpdate, 60);
                    updateWidgetPages(index);
                }
            }
        });
        content.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 18));
        content.addView(dots, dlp);

        dock = new DockView(this, wallpaper, prefs);
        dock.setGlassTuning(prefs.dockSaturation() / 100f,
                prefs.dockBackdropDarkness() / 100f);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 94));
        dockLp.gravity = Gravity.CENTER_HORIZONTAL;
        dockLp.topMargin = Ui.dp(this, 2);
        content.addView(dock, dockLp);
        applyDockPosition();

        buildDrawer();
        root.addView(drawer, match());

        setContentView(root);
        hideSystemBars();
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        statsText = new LTextView(this);
        statsText.setTextColor(0xB3FFFFFF);
        statsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statsText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statsText.setLetterSpacing(0.04f);
        statsText.setPadding(0, 0, Ui.dp(this, 12), 0);
        statsText.setText("--");
        statsText.setShadowLayer(Ui.dpf(this, 2.4f), 0f, 0f, 0xB000E6A8);
        controls.addView(statsText);

        headerStatusCluster = new LinearLayout(this);
        headerStatusCluster.setOrientation(LinearLayout.HORIZONTAL);
        headerStatusCluster.setGravity(Gravity.CENTER_VERTICAL);
        headerStatusCluster.setPadding(0, 0, Ui.dp(this, 10), 0);

        recordingStatusGlyph = new RecordingStatusGlyph(this);
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 18), Ui.dp(this, 18));
        recLp.rightMargin = Ui.dp(this, 6);
        headerStatusCluster.addView(recordingStatusGlyph, recLp);
        installHeaderFocus(recordingStatusGlyph, false);
        recordingStatusGlyph.setContentDescription("Start entire-screen recording");
        recordingStatusGlyph.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openScreenRecorderFromIndicator(); }
        });

        bluetoothStatusGlyph = new HeaderStatusGlyph(this, HeaderStatusGlyph.BLUETOOTH);
        LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16));
        btLp.rightMargin = Ui.dp(this, 6);
        headerStatusCluster.addView(bluetoothStatusGlyph, btLp);
        installHeaderFocus(bluetoothStatusGlyph, false);
        bluetoothStatusGlyph.setContentDescription("Bluetooth off");
        bluetoothStatusGlyph.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS,
                        "Bluetooth settings unavailable");
            }
        });

        soundStatusGlyph = new HeaderStatusGlyph(this, HeaderStatusGlyph.SOUND);
        headerStatusCluster.addView(soundStatusGlyph,
                new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        installHeaderFocus(soundStatusGlyph, false);
        soundStatusGlyph.setContentDescription("Notification sound mode");
        soundStatusGlyph.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openSystemSettings(Settings.ACTION_SOUND_SETTINGS,
                        "Sound settings unavailable");
            }
        });
        controls.addView(headerStatusCluster);

        batteryPill = new LinearLayout(this);
        batteryPill.setOrientation(LinearLayout.HORIZONTAL);
        batteryPill.setGravity(Gravity.CENTER_VERTICAL);
        batteryPill.setBackground(getDrawable(R.drawable.bg_pill));
        batteryPill.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        batteryPill.setClickable(true);
        batteryPill.setContentDescription("Battery settings");
        batteryPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBatterySettings();
            }
        });
        installHeaderFocus(batteryPill, true);

        batteryGlyph = new BatteryGlyph(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 13));
        glp.rightMargin = Ui.dp(this, 7);
        batteryPill.addView(batteryGlyph, glp);

        batteryText = new LTextView(this);
        batteryText.setTextColor(0xFF00E6A8);
        batteryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        batteryText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        batteryText.setShadowLayer(Ui.dpf(this, 2.2f), 0f, 0f, 0xA000E6A8);
        batteryText.setText("--%");
        batteryPill.addView(batteryText);
        controls.addView(batteryPill);

        header.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(controls);
        content.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildClockWidget();
    }

    private boolean accessibilitySettingEnabled() {
        try {
            if (Settings.Secure.getInt(getContentResolver(), "accessibility_enabled", 0) == 0) {
                return false;
            }
            String services = Settings.Secure.getString(getContentResolver(),
                    "enabled_accessibility_services");
            return services != null && services.contains(
                    getPackageName() + "/" + PavoA11yService.class.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void installHeaderFocus(final View view, final boolean pill) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setDefaultFocusHighlightEnabled(false);
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                if (pill) {
                    v.setBackground(focused
                            ? Ui.focusRing(MainActivity.this, Ui.dpf(MainActivity.this, 16))
                            : getDrawable(R.drawable.bg_pill));
                } else if (v instanceof HeaderStatusGlyph) {
                    HeaderStatusGlyph glyph = (HeaderStatusGlyph) v;
                    glyph.setFocusRing(focused,
                            wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
                    v.setBackground(null);
                } else {
                    v.setBackground(focused
                            ? Ui.focusRing(MainActivity.this, Ui.dpf(MainActivity.this, 9))
                            : null);
                }
                if (focused) Haptics.tick(v);
            }
        });
    }

    private void openSystemSettings(String action, String failure) {
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, failure, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isHeaderControl(View v) {
        return v != null && (v == batteryPill || v == bluetoothStatusGlyph
                || v == soundStatusGlyph || v == recordingStatusGlyph);
    }

    private Runnable clockLongPress;

    private void buildClockWidget() {
        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);

        timeView = new LTextView(this);
        timeView.setTextColor(0xFFF7F9FC);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        timeView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeView.setIncludeFontPadding(false);
        timeView.setFontFeatureSettings("tnum");
        timeView.setLetterSpacing(-0.02f);

        ampmView = new LTextView(this);
        ampmView.setTextColor(0x99FFFFFF);
        ampmView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        ampmView.setLetterSpacing(0.10f);
        ampmView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ampmView.setPadding(Ui.dp(this, 8), 0, 0, Ui.dp(this, 8));

        clockRow.addView(timeView);
        clockRow.addView(ampmView);

        dateView = new LTextView(this);
        dateView.setTextColor(0xB3FFFFFF);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        dateView.setLetterSpacing(0.16f);
        dateView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dateView.setPadding(Ui.dp(this, 1), 0, 0, Ui.dp(this, 2));

        weatherView = new LTextView(this);
        weatherView.setTextColor(0xCCFFFFFF);
        weatherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        weatherView.setLetterSpacing(0.01f);
        weatherView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        weatherView.setPadding(Ui.dp(this, 4), 0, 0, 0);
        weatherView.setMaxLines(1);
        weatherView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        weatherView.setVisibility(View.GONE);

        weatherGlyph = new WeatherGlyph(this);
        weatherGlyph.setVisibility(View.GONE);
        LinearLayout.LayoutParams wglp = new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16));
        wglp.leftMargin = Ui.dp(this, 8);
        wglp.gravity = Gravity.CENTER_VERTICAL;

        LinearLayout weatherRow = new LinearLayout(this);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.addView(weatherGlyph, wglp);
        weatherRow.addView(weatherView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM | Gravity.START);
        info.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 4));
        info.addView(dateView);
        info.addView(weatherRow);
        clockRow.addView(info);

        clockBox.addView(clockRow);
        clockBox.setVisibility(prefs.clockVisible() ? View.VISIBLE : View.GONE);

        timeView.setClickable(false);
        timeView.setLongClickable(false);
        ampmView.setClickable(false);
        ampmView.setLongClickable(false);
        dateView.setClickable(false);
        dateView.setLongClickable(false);
        weatherView.setClickable(false);
        weatherView.setLongClickable(false);

        applyClockScale();
        applyClockColors();

        clockLongPress = new Runnable() {
            @Override
            public void run() {
                if (clockBox == null) return;
                clockLongFired = true;
                cancelClockSingleTap();
                clockBox.animate().scaleX(1.02f).scaleY(1.02f).setDuration(110).start();
                showClockMenu();
            }
        };

    }

    private boolean clockTouch(MotionEvent e) {
        float rx = e.getRawX();
        float ry = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clockDownRawX = rx;
                clockDownRawY = ry;
                clockLongFired = false;
                clockDragging = false;
                clockPendingEdge = 0;
                if (clockResizeMode) {
                    // Do not start resizing on touch down: a tap (or the second
                    // tap of a double-tap) must still toggle resize mode off.
                    int edge = clockEdgeAt(e.getX(), e.getY());
                    if (edge != 0) {
                        clockPendingEdge = edge;
                        return true;
                    }
                }
                handler.removeCallbacks(clockLongPress);
                handler.postDelayed(clockLongPress, 500);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (clockResizing) {
                    moveClockResize(rx, ry);
                    return true;
                }
                float dist = (float) Math.hypot(rx - clockDownRawX, ry - clockDownRawY);
                if (clockPendingEdge != 0) {
                    if (dist > Ui.dpf(this, 8)) {
                        int edge = clockPendingEdge;
                        clockPendingEdge = 0;
                        beginClockResize(edge, clockDownRawX, clockDownRawY);
                        moveClockResize(rx, ry);
                    }
                    return true;
                }
                if (!clockLongFired) {
                    if (dist > Ui.dpf(this, 9)) handler.removeCallbacks(clockLongPress);
                } else if (!clockDragging && dist > Ui.dpf(this, 9)) {
                    startClockDrag();
                }
                if (clockDragging) moveClockDrag(rx, ry);
                return true;
            }
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(clockLongPress);
                clockPendingEdge = 0;
                if (clockResizing) {
                    endClockResize();
                } else if (clockDragging) {
                    endClockDrag(rx, ry);
                } else if (clockLongFired) {
                    clockBox.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                } else {
                    handleClockTap(rx, ry);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(clockLongPress);
                clockPendingEdge = 0;
                if (clockResizing) cancelClockResize();
                else if (clockDragging) cancelClockDrag();
                else clockBox.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                return true;
        }
        return false;
    }

    private float clampClockScale(float scale) {
        return Math.max(0.7f, Math.min(1.6f, scale));
    }

    private int clockEdgeAt(float x, float y) {
        if (clockBox == null) return 0;
        float w = clockBox.getWidth();
        float h = clockBox.getHeight();
        if (w <= 0 || h <= 0) return 0;
        float mx = Math.min(Ui.dp(this, 72), w * 0.48f);
        float my = Math.min(Ui.dp(this, 72), h * 0.48f);
        int edge = 0;
        if (x <= mx) edge |= WidgetFrame.EDGE_LEFT;
        if (x >= w - mx) edge |= WidgetFrame.EDGE_RIGHT;
        if (y <= my) edge |= WidgetFrame.EDGE_TOP;
        if (y >= h - my) edge |= WidgetFrame.EDGE_BOTTOM;
        return edge;
    }

    private void beginClockResize(int edge, float rawX, float rawY) {
        clockResizing = true;
        clockResizeEdge = edge;
        clockResizeStartScale = prefs.clockScale();
        clockResizeStartX = rawX;
        clockResizeStartY = rawY;
        handler.removeCallbacks(clockLongPress);
        cancelClockSingleTap();
        homeGesture.active = false;
        cancelEmptyLongPress();
        pager.setLocked(true);
        Haptics.pickup(clockBox);
    }

    private void moveClockResize(float rawX, float rawY) {
        if (clockBox == null) return;
        float w = Math.max(1f, clockBox.getWidth());
        float h = Math.max(1f, clockBox.getHeight());
        float dx = rawX - clockResizeStartX;
        float dy = rawY - clockResizeStartY;
        float horizontal = 0f;
        float vertical = 0f;
        int parts = 0;
        if ((clockResizeEdge & WidgetFrame.EDGE_RIGHT) != 0) {
            horizontal = dx / w;
            parts++;
        } else if ((clockResizeEdge & WidgetFrame.EDGE_LEFT) != 0) {
            horizontal = -dx / w;
            parts++;
        }
        if ((clockResizeEdge & WidgetFrame.EDGE_BOTTOM) != 0) {
            vertical = dy / h;
            parts++;
        } else if ((clockResizeEdge & WidgetFrame.EDGE_TOP) != 0) {
            vertical = -dy / h;
            parts++;
        }
        float delta = parts == 0 ? 0f : (horizontal + vertical) / parts;
        prefs.setClockScale(clampClockScale(clockResizeStartScale + delta * 0.85f));
        applyClockScale();
        clockBox.requestLayout();
        clockBox.invalidate();
    }

    private void endClockResize() {
        clockResizing = false;
        Haptics.drop(clockBox);
        scheduleClockReflow();
    }

    private void cancelClockResize() {
        clockResizing = false;
        prefs.setClockScale(clockResizeStartScale);
        applyClockScale();
        scheduleClockReflow();
        Haptics.outside(clockBox);
    }

    private void handleClockTap(float rawX, float rawY) {
        long now = SystemClock.uptimeMillis();
        if (now - clockLastTapAt <= 300L) {
            clockLastTapAt = 0L;
            cancelClockSingleTap();
            toggleClockResizeMode();
            return;
        }
        clockLastTapAt = now;
        cancelClockSingleTap();
        final long stamp = now;
        clockSingleTap = new Runnable() {
            @Override
            public void run() {
                if (clockLastTapAt != stamp) return;
                clockLastTapAt = 0L;
                if (isWeatherTap(rawX, rawY)) openGoogleWeather();
                else openClockApp();
            }
        };
        handler.postDelayed(clockSingleTap, 280L);
    }

    private void cancelClockSingleTap() {
        if (clockSingleTap != null) {
            handler.removeCallbacks(clockSingleTap);
            clockSingleTap = null;
        }
    }

    private void toggleClockResizeMode() {
        clockResizeMode = !clockResizeMode;
        if (clockBox != null) {
            clockBox.setBackground(clockResizeMode
                    ? Ui.rounded(0x00000000, Ui.dp(this, 14), wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF, Ui.dp(this, 1.5f))
                    : null);
            clockBox.invalidate();
        }
        pager.setLocked(clockResizeMode);
        if (clockResizeMode) {
            homeGesture.active = false;
            cancelEmptyLongPress();
        }
        Haptics.click(clockBox);
    }

    /** True when the tap landed on the weather part of the clock widget. */
    private boolean isWeatherTap(float rawX, float rawY) {
        if (weatherView == null || weatherView.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        int[] loc = new int[2];
        weatherView.getLocationOnScreen(loc);
        r.set(loc[0], loc[1], loc[0] + weatherView.getWidth(),
                loc[1] + weatherView.getHeight());
        if (weatherGlyph != null && weatherGlyph.getVisibility() == View.VISIBLE) {
            weatherGlyph.getLocationOnScreen(loc);
            r.union(loc[0], loc[1], loc[0] + weatherGlyph.getWidth(),
                    loc[1] + weatherGlyph.getHeight());
        }
        r.inset(-Ui.dp(this, 6), -Ui.dp(this, 8));
        return r.contains((int) rawX, (int) rawY);
    }

    private void openClockApp() {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.android.deskclock");
            if (i == null) throw new IllegalStateException();
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, "No clock app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGoogleWeather() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=weather"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, "Battery settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void showClockMenu() {
        Haptics.longPress(clockBox);
        List<Menu.Entry> entries = new ArrayList<>();
        entries.add(new Menu.Entry("Change clock color", new Runnable() {
            @Override
            public void run() {
                showColourDialog(false);
            }
        }));
        entries.add(new Menu.Entry("Change date color", new Runnable() {
            @Override
            public void run() {
                showColourDialog(true);
            }
        }));
        dismissActiveMenu();
        activeMenu = Menu.show(this, captureBackdrop(false, 0), wallpaper.accent(), clockBox,
                clockDownRawX, clockDownRawY, "Clock & date", entries, false);
    }

    // ------------------------------------------------------------------
    // Home rendering
    // ------------------------------------------------------------------

    private Apps.Entry findEntry(String pkg, String cls) {
        if (PERF_LOG_PKG.equals(pkg)) return perfLogsEntry();
        if (CONTROLLER_MAP_PKG.equals(pkg)) return controllerMapEntry();
        if (PERF_TUNER_PKG.equals(pkg)) return perfTunerEntry();
        if (DISPLAY_PKG.equals(pkg)) return displayEntry();
        if (RGB_PKG.equals(pkg)) return rgbEntry();
        if (FAN_PKG.equals(pkg)) return fanEntry();
        if (OVERLAY_PKG.equals(pkg)) return overlayEntry();
        if (TOUCH_MAP_PKG.equals(pkg)) return touchMapEntry();
        if (DEVICE_HEALTH_PKG.equals(pkg)) return deviceHealthEntry();
        if (BENCHMARK_PKG.equals(pkg)) return benchmarkEntry();
        if (FILE_MANAGER_PKG.equals(pkg)) return fileManagerEntry();
        if (SCREEN_RECORDER_PKG.equals(pkg)) return screenRecorderEntry();
        for (Apps.Entry e : allApps) {
            if (e.pkg.equals(pkg) && (cls == null || e.activity.equals(cls))) return e;
        }
        return null;
    }

    private boolean isPerfLogs(String pkg) {
        return PERF_LOG_PKG.equals(pkg);
    }

    private boolean isBuiltinApp(String pkg) {
        return PERF_LOG_PKG.equals(pkg) || CONTROLLER_MAP_PKG.equals(pkg)
                || PERF_TUNER_PKG.equals(pkg) || DISPLAY_PKG.equals(pkg)
                || RGB_PKG.equals(pkg) || FAN_PKG.equals(pkg)
                || OVERLAY_PKG.equals(pkg)
                || TOUCH_MAP_PKG.equals(pkg) || DEVICE_HEALTH_PKG.equals(pkg)
                || BENCHMARK_PKG.equals(pkg) || FILE_MANAGER_PKG.equals(pkg)
                || SCREEN_RECORDER_PKG.equals(pkg);
    }

    private Apps.Entry perfLogsEntry() {
        if (perfLogEntry == null) {
            perfLogEntry = new Apps.Entry(Loc.s(MainActivity.this, "Perf Logs"), PERF_LOG_PKG, "");
            perfLogEntry.icon = Apps.performanceLogsIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return perfLogEntry;
    }

    private Apps.Entry controllerMapEntry() {
        if (controllerMapEntry == null) {
            controllerMapEntry = new Apps.Entry(Loc.s(MainActivity.this, "Controller Remapper"), CONTROLLER_MAP_PKG, "");
            controllerMapEntry.icon = Apps.controllerMapIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return controllerMapEntry;
    }

    private Apps.Entry perfTunerEntry() {
        if (perfTunerEntry == null) {
            perfTunerEntry = new Apps.Entry(Loc.s(MainActivity.this, "Performance Tuner"), PERF_TUNER_PKG, "");
            perfTunerEntry.icon = Apps.performanceTunerIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return perfTunerEntry;
    }

    private Apps.Entry displayEntry() {
        if (displayEntry == null) {
            displayEntry = new Apps.Entry(Loc.s(MainActivity.this, "Display"), DISPLAY_PKG, "");
            displayEntry.icon = Apps.displayIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return displayEntry;
    }

    private Apps.Entry rgbEntry() {
        if (rgbEntry == null) {
            rgbEntry = new Apps.Entry(Loc.s(MainActivity.this, "RGB Lighting"), RGB_PKG, "");
            rgbEntry.icon = Apps.rgbIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return rgbEntry;
    }

    private Apps.Entry fanEntry() {
        if (fanEntry == null) {
            fanEntry = new Apps.Entry(Loc.s(MainActivity.this, "Fan Control"), FAN_PKG, "");
            fanEntry.icon = Apps.fanIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return fanEntry;
    }

    private Apps.Entry overlayEntry() {
        if (overlayEntry == null) {
            overlayEntry = new Apps.Entry(Loc.s(MainActivity.this, "Overlay & Drawer"),
                    OVERLAY_PKG, "");
            overlayEntry.icon = Apps.overlayIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return overlayEntry;
    }

    private Apps.Entry touchMapEntry() {
        if (touchMapEntry == null) {
            touchMapEntry = new Apps.Entry(Loc.s(MainActivity.this, "Touch Mapper"), TOUCH_MAP_PKG, "");
            touchMapEntry.icon = Apps.touchMapperIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return touchMapEntry;
    }

    private Apps.Entry deviceHealthEntry() {
        if (deviceHealthEntry == null) {
            deviceHealthEntry = new Apps.Entry(Loc.s(MainActivity.this, "Device Health"), DEVICE_HEALTH_PKG, "");
            deviceHealthEntry.icon = Apps.deviceHealthIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return deviceHealthEntry;
    }

    private Apps.Entry benchmarkEntry() {
        if (benchmarkEntry == null) {
            benchmarkEntry = new Apps.Entry(Loc.s(MainActivity.this, "Stress Test"), BENCHMARK_PKG, "");
            benchmarkEntry.icon = Apps.benchmarkIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return benchmarkEntry;
    }

    private Apps.Entry fileManagerEntry() {
        if (fileManagerEntry == null) {
            fileManagerEntry = new Apps.Entry(Loc.s(MainActivity.this, "File Manager"), FILE_MANAGER_PKG, "");
            fileManagerEntry.icon = Apps.fileManagerIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return fileManagerEntry;
    }

    private Apps.Entry screenRecorderEntry() {
        if (screenRecorderEntry == null) {
            screenRecorderEntry = new Apps.Entry(Loc.s(MainActivity.this, "Screen Recorder"),
                    SCREEN_RECORDER_PKG, "");
            screenRecorderEntry.icon = Apps.screenRecorderIcon(Ui.dp(this, 72),
                    wallpaper != null ? wallpaper.accent() : 0xFF8BE9FF);
        }
        return screenRecorderEntry;
    }

    /** Rebuild cached built-in icons when the wallpaper accent changes. */
    private void refreshBuiltinIcons() {
        if (wallpaper == null) return;
        int px = Ui.dp(this, 72);
        int accent = wallpaper.accent();
        if (perfLogEntry != null) {
            perfLogEntry.icon = Apps.performanceLogsIcon(px, accent);
        }
        if (controllerMapEntry != null) {
            controllerMapEntry.icon = Apps.controllerMapIcon(px, accent);
        }
        if (perfTunerEntry != null) {
            perfTunerEntry.icon = Apps.performanceTunerIcon(px, accent);
        }
        if (displayEntry != null) {
            displayEntry.icon = Apps.displayIcon(px, accent);
        }
        if (overlayEntry != null) {
            overlayEntry.icon = Apps.overlayIcon(px, accent);
        }
        if (rgbEntry != null) {
            rgbEntry.icon = Apps.rgbIcon(px, accent);
        }
        if (fanEntry != null) {
            fanEntry.icon = Apps.fanIcon(px, accent);
        }
        if (touchMapEntry != null) {
            touchMapEntry.icon = Apps.touchMapperIcon(px, accent);
        }
        if (deviceHealthEntry != null) {
            deviceHealthEntry.icon = Apps.deviceHealthIcon(px, accent);
        }
        if (benchmarkEntry != null) {
            benchmarkEntry.icon = Apps.benchmarkIcon(px, accent);
        }
        if (fileManagerEntry != null) {
            fileManagerEntry.icon = Apps.fileManagerIcon(px, accent);
        }
        if (screenRecorderEntry != null) {
            screenRecorderEntry.icon = Apps.screenRecorderIcon(px, accent);
        }
        if (pager != null) renderHome();
        if (dock != null) populateDock();
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
    }

    private void renderHome() {
        updateGridSize();
        int cap = capacity();
        computeClockSpan();
        store.normalize(cap);
        ensureFoldersHaveTwoApps();
        ensureClockDoesNotOverlapWidgets();
        ensureWidgetsClear();

        if (clockBox != null && clockBox.getParent() instanceof ViewGroup) {
            ((ViewGroup) clockBox.getParent()).removeView(clockBox);
        }

        int beforePage = pager.currentPage();
        pager.removeAllViews();
        int clockPage = clockPage();
        int clockCell = clockCell();
        for (int p = 0; p < store.pageCount(); p++) {
            final GridPage gp = new GridPage(this);
            gp.setGrid(gridCols, gridRows);
            List<Item> page = store.page(p);
            List<ItemView> views = new ArrayList<>();
            for (int i = 0; i < cap; i++) {
                Item it = i < page.size() ? page.get(i) : null;
                views.add(it == null ? null : makeItemView(it, false, true));
            }
            gp.bind(views);
            gp.setTag(p);
            if (prefs.clockVisible() && p == clockPage) {
                addClockWidget(gp);
            }
            addAppWidgets(gp, p);
            pager.addView(gp);
        }
        int shownPage = Math.max(0, Math.min(pager.pageCount() - 1, beforePage));
        pager.setPage(shownPage, false);
        updateDots(shownPage, pager.pageCount());
    }

    /** Clock uses the same frame/drag/trash interaction as other widgets. */
    private void addClockWidget(GridPage gp) {
        if (clockBox == null || gp == null) return;
        WidgetFrame frame = new WidgetFrame(this);
        frame.setResizeEnabled(false);
        frame.setTouchPassthrough(true);
        clockBox.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return clockTouch(e);
            }
        });
        frame.listener = new WidgetFrame.Listener() {
            @Override
            public void onTap(WidgetFrame f) {
                openClockApp();
            }

            @Override
            public void onLongPress(WidgetFrame f) {
                clockDownRawX = f.touchX();
                clockDownRawY = f.touchY();
            }

            @Override
            public void onLongPressReleased(WidgetFrame f) {
                showClockMenu();
            }

            @Override
            public void onDragStart(WidgetFrame f) {
                startClockDrag();
            }

            @Override
            public void onDragMove(WidgetFrame f, float rawX, float rawY) {
                moveClockDrag(rawX, rawY);
            }

            @Override
            public void onRelease(WidgetFrame f, float rawX, float rawY) {
                endClockDrag(rawX, rawY);
            }

            @Override
            public void onDragCancel(WidgetFrame f) {
                cancelClockDrag();
            }

            @Override
            public void onResizeModeChanged(WidgetFrame f, boolean enabled) {
            }

            @Override
            public void onResizeStart(WidgetFrame f, float rawX, float rawY) {
            }

            @Override
            public void onResizeMove(WidgetFrame f, float rawX, float rawY) {
            }

            @Override
            public void onResizeEnd(WidgetFrame f, float rawX, float rawY) {
            }

            @Override
            public void onResizeCancel(WidgetFrame f) {
            }
        };
        frame.addView(clockBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gp.addWidget(frame, clockCell(), clockSpanCols, clockSpanRows);
    }

    /**
     * The home grid is made of icon-sized cells and covers the full page, so empty
     * wallpaper is still a reachable cell instead of an unusable margin.
     */
    private void updateGridSize() {
        int pw = pager != null ? pager.getWidth() : 0;
        int ph = pager != null ? pager.getHeight() : 0;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (pw <= 0) pw = dm.widthPixels - Ui.dp(this, 60);
        if (ph <= 0) {
            ph = dm.heightPixels - Ui.dp(this, 14 + 10 + 18 + 94 + 46);
        }
        int iconPx = Ui.dp(this, prefs.iconDp());
        int cw = Math.max(1, iconPx + Ui.dp(this, 16));
        int ch = Math.max(1, iconPx + Ui.dp(this, 34));
        gridCols = Math.max(1, pw / cw);
        // Fill the available height instead of rounding down and stretching
        // the last row (or leaving a dead strip). fitToCell() trims the icon
        // slightly if the resulting row is a little shorter.
        gridRows = Math.max(1, Math.round(ph / (float) ch));
    }

    // ------------------------------------------------------------------
    // App widgets
    // ------------------------------------------------------------------

    /** All cells on a page covered by the clock or an AppWidget. */
    private Set<Integer> widgetCells(int page) {
        Set<Integer> cells = new HashSet<>();
        if (prefs.clockVisible() && page == clockPage()) cells.addAll(clockCells());
        for (Store.PlacedWidget w : store.widgets) {
            if (w.page != page) continue;
            int c = w.cell % gridCols;
            int r = w.cell / gridCols;
            for (int y = r; y < r + w.spanRows; y++) {
                for (int x = c; x < c + w.spanCols; x++) {
                    if (x >= 0 && x < gridCols && y >= 0 && y < gridRows) {
                        cells.add(y * gridCols + x);
                    }
                }
            }
        }
        return cells;
    }

    private boolean widgetCovers(int page, int cell) {
        if (cell < 0) return false;
        return widgetCells(page).contains(cell);
    }

    /**
     * Relocate apps that ended up under the clock or a widget (for example after the
     * grid density changed), keeping the widget footprint clear.
     */
    private void ensureWidgetsClear() {
        int maxPage = 0;
        for (Store.PlacedWidget w : store.widgets) maxPage = Math.max(maxPage, w.page);
        while (store.pageCount() <= maxPage) store.page(store.pageCount());

        boolean changed = false;
        for (int p = 0; p < store.pageCount(); p++) {
            Set<Integer> covered = widgetCells(p);
            if (covered.isEmpty()) continue;
            List<Item> page = store.page(p);
            List<Item> displaced = new ArrayList<>();
            for (int cell : covered) {
                if (cell < page.size() && page.get(cell) != null) {
                    displaced.add(page.get(cell));
                    page.set(cell, null);
                    changed = true;
                }
            }
            for (Item item : displaced) {
                if (!placeItemInFreeCell(item)) {
                    List<Item> np = new ArrayList<>();
                    np.add(item);
                    store.pages.add(np);
                }
                changed = true;
            }
        }
        if (changed) store.save();
    }

    private boolean placeItemInFreeCell(Item item) {
        int cap = capacity();
        for (int p = 0; p < store.pageCount(); p++) {
            Set<Integer> covered = widgetCells(p);
            List<Item> page = store.page(p);
            for (int i = 0; i < cap; i++) {
                if (covered.contains(i)) continue;
                if (i >= page.size() || page.get(i) == null) {
                    while (page.size() <= i) page.add(null);
                    page.set(i, item);
                    return true;
                }
            }
        }
        return false;
    }

    /** First free rectangular slot on a page, or -1. */
    private int findFreeWidgetCell(int page, int spanCols, int spanRows) {
        int sc = Math.max(1, Math.min(gridCols, spanCols));
        int sr = Math.max(1, Math.min(gridRows, spanRows));
        Set<Integer> occupied = new HashSet<>();
        List<Item> pg = store.page(page);
        for (int i = 0; i < gridCols * gridRows; i++) {
            if (i < pg.size() && pg.get(i) != null) occupied.add(i);
        }
        occupied.addAll(widgetCells(page));
        for (int r = 0; r <= gridRows - sr; r++) {
            for (int c = 0; c <= gridCols - sc; c++) {
                boolean free = true;
                for (int y = 0; y < sr && free; y++) {
                    for (int x = 0; x < sc; x++) {
                        if (occupied.contains((r + y) * gridCols + c + x)) {
                            free = false;
                            break;
                        }
                    }
                }
                if (free) return r * gridCols + c;
            }
        }
        return -1;
    }

    private void addAppWidgets(GridPage gp, int page) {
        for (Store.PlacedWidget w : store.widgets) {
            if (w.page != page) continue;
            View content;
            if (w.kind != null) {
                if (!PavoHomeWidgetView.validKind(w.kind)) continue;
                content = new PavoHomeWidgetView(this, w.kind);
            } else {
                AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(w.appWidgetId);
                if (info == null) continue;

                AppWidgetHostView hv = widgetViews.get(w.appWidgetId);
                if (hv == null) {
                    try {
                        hv = widgetHost.createView(this, w.appWidgetId, info);
                    } catch (Throwable t) {
                        hv = null;
                    }
                    if (hv == null) continue;
                    widgetViews.put(w.appWidgetId, hv);
                }
                if (hv.getParent() instanceof ViewGroup) {
                    ((ViewGroup) hv.getParent()).removeView(hv);
                }
                content = hv;
            }

            final Store.PlacedWidget widget = w;
            WidgetFrame frame = new WidgetFrame(this);
            frame.listener = new WidgetFrame.Listener() {
                @Override
                public void onTap(WidgetFrame f) {
                    openWidgetApp(widget);
                }

                @Override
                public void onLongPress(WidgetFrame f) {
                    Haptics.longPress(f);
                }

                @Override
                public void onLongPressReleased(WidgetFrame f) {
                }

                @Override
                public void onDragStart(WidgetFrame f) {
                    startWidgetDrag(f, widget);
                }

                @Override
                public void onDragMove(WidgetFrame f, float rawX, float rawY) {
                    moveWidgetDrag(rawX, rawY);
                }

                @Override
                public void onRelease(WidgetFrame f, float rawX, float rawY) {
                    endWidgetDrag(rawX, rawY);
                }

                @Override
                public void onDragCancel(WidgetFrame f) {
                    cancelWidgetDrag();
                }

                @Override
                public void onResizeModeChanged(WidgetFrame f, boolean enabled) {
                    widgetResizeMode = enabled;
                    pager.setLocked(enabled || resizeWidget != null);
                    if (enabled) {
                        homeGesture.active = false;
                        cancelEmptyLongPress();
                        resizeGestureFrame = f;
                        updateResizeGestureExclusion();
                        updateResizeTouchDelegate(f, true);
                    } else {
                        resizeGestureFrame = null;
                        updateResizeTouchDelegate(null, false);
                        clearResizeGestureExclusion();
                    }
                }

                @Override
                public void onResizeStart(WidgetFrame f, float rawX, float rawY) {
                    startWidgetResize(f, widget, rawX, rawY);
                }

                @Override
                public void onResizeMove(WidgetFrame f, float rawX, float rawY) {
                    moveWidgetResize(rawX, rawY);
                }

                @Override
                public void onResizeEnd(WidgetFrame f, float rawX, float rawY) {
                    endWidgetResize(rawX, rawY);
                }

                @Override
                public void onResizeCancel(WidgetFrame f) {
                    cancelWidgetResize();
                }
            };
            frame.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            gp.addWidget(frame, w.cell, w.spanCols, w.spanRows);
            updateWidgetSize(w);
        }
    }

    private void addClockWidgetRow(LinearLayout list, final Dialog dialog) {
        String description = prefs.clockVisible()
                ? "Placed on home · tap to move to a free cell"
                : "Live time, date and weather";
        LinearLayout row = widgetRow(
                Apps.clockIcon(Ui.dp(this, 40), wallpaper.accent()),
                "Clock & date", description);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
                placeClockWidget();
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addWidgetDivider(list);
    }

    private void placeClockWidget() {
        computeClockSpan();
        int[] slot = findFreeClockCell(pager.currentPage(), clockSpanCols, clockSpanRows, true);
        if (slot == null) {
            slot = findFreeClockCell(pager.currentPage(), clockSpanCols, clockSpanRows, false);
        }
        if (slot == null) {
            int page = store.pageCount();
            store.page(page);
            slot = new int[]{page, 0};
        }
        prefs.setClockVisible(true);
        prefs.setClockCell(slot[0], slot[1] / gridCols, slot[1] % gridCols);
        renderHome();
        pager.setPage(slot[0], false);
        updateDots(slot[0], pager.pageCount());
        LToast.makeText(this, "Clock placed on home", Toast.LENGTH_SHORT).show();
    }

    private void ensureClockDoesNotOverlapWidgets() {
        if (!prefs.clockVisible()) return;
        int page = clockPage();
        int cell = clockCell();
        if (!clockOverlapsWidgets(page, cell % gridCols, cell / gridCols,
                clockSpanCols, clockSpanRows)) return;
        int[] slot = findFreeClockCell(page, clockSpanCols, clockSpanRows, false);
        if (slot == null) {
            int newPage = store.pageCount();
            store.page(newPage);
            slot = new int[]{newPage, 0};
        }
        prefs.setClockCell(slot[0], slot[1] / gridCols, slot[1] % gridCols);
    }

    private int[] findFreeClockCell(int startPage, int spanCols, int spanRows,
                                    boolean avoidIcons) {
        int sc = Math.max(1, Math.min(gridCols, spanCols));
        int sr = Math.max(1, Math.min(gridRows, spanRows));
        int pageCount = Math.max(1, store.pageCount());
        int first = Math.max(0, Math.min(pageCount - 1, startPage));
        for (int n = 0; n < pageCount; n++) {
            int page = (first + n) % pageCount;
            List<Item> items = store.page(page);
            for (int r = 0; r <= gridRows - sr; r++) {
                for (int c = 0; c <= gridCols - sc; c++) {
                    if (clockOverlapsWidgets(page, c, r, sc, sr)) continue;
                    if (avoidIcons) {
                        boolean blocked = false;
                        for (int y = 0; y < sr && !blocked; y++) {
                            for (int x = 0; x < sc; x++) {
                                int cell = (r + y) * gridCols + c + x;
                                if (cell < items.size() && items.get(cell) != null) {
                                    blocked = true;
                                    break;
                                }
                            }
                        }
                        if (blocked) continue;
                    }
                    return new int[]{page, r * gridCols + c};
                }
            }
        }
        return null;
    }

    private boolean clockOverlapsWidgets(int page, int col, int row,
                                         int spanCols, int spanRows) {
        int left = col;
        int top = row;
        int right = col + spanCols;
        int bottom = row + spanRows;
        for (Store.PlacedWidget w : store.widgets) {
            if (w.page != page) continue;
            int wc = w.cell % gridCols;
            int wr = w.cell / gridCols;
            int wr2 = wc + w.spanCols;
            int wb = wr + w.spanRows;
            if (left < wr2 && right > wc && top < wb && bottom > wr) return true;
        }
        return false;
    }

    private void openWidgetApp(Store.PlacedWidget w) {
        if (w == null) return;
        Haptics.launch(this);
        if (w.kind != null) {
            Class<?> target = PavoHomeWidgetView.KIND_BATTERY.equals(w.kind)
                    ? DeviceHealthActivity.class
                    : PavoHomeWidgetView.KIND_FAN.equals(w.kind)
                    ? FanActivity.class : PerformanceTunerActivity.class;
            try {
                startActivity(new Intent(this, target));
            } catch (Throwable t) {
                LToast.makeText(this, "Can't open widget app", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        try {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(w.appWidgetId);
            if (info == null || info.provider == null) {
                LToast.makeText(this, "Widget app unavailable", Toast.LENGTH_SHORT).show();
                return;
            }
            String pkg = info.provider.getPackageName();
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                LToast.makeText(this, "No app to open for this widget", Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        } catch (Throwable t) {
            LToast.makeText(this, "Can't open widget app", Toast.LENGTH_SHORT).show();
        }
    }

    private void startWidgetResize(WidgetFrame frame, Store.PlacedWidget w,
                                   float rawX, float rawY) {
        if (frame == null || w == null || dragging || resizeWidget != null) return;
        View pageView = pager.getChildAt(w.page);
        if (!(pageView instanceof GridPage)) return;
        GridPage gp = (GridPage) pageView;
        Haptics.pickup(frame);
        dismissActiveMenu();
        resizeWidgetFrame = frame;
        resizeWidget = w;
        resizePage = gp;
        resizeEdge = frame.resizeEdge();
        resizeStartCell = w.cell;
        resizeStartCols = Math.max(1, w.spanCols);
        resizeStartRows = Math.max(1, w.spanRows);
        resizeCurrentCell = resizeStartCell;
        resizeCurrentCols = resizeStartCols;
        resizeCurrentRows = resizeStartRows;
        resizeTargetValid = true;
        resizeStartX = rawX;
        resizeStartY = rawY;
        resizeCellW = Math.max(1f, gp.cellWidth());
        resizeCellH = Math.max(1f, gp.cellHeight());
        resizeFrameLeft = frame.getLeft();
        resizeFrameTop = frame.getTop();
        resizeFrameWidth = Math.max(1f, frame.getWidth());
        resizeFrameHeight = Math.max(1f, frame.getHeight());
        frame.setPivotX(0f);
        frame.setPivotY(0f);
        frame.setTranslationX(0f);
        frame.setTranslationY(0f);
        frame.setScaleX(1f);
        frame.setScaleY(1f);
        homeGesture.active = false;
        cancelEmptyLongPress();
        pager.setLocked(true);
        resizeGestureFrame = frame;
        updateResizeGestureExclusion();
        updateResizeTouchDelegate(frame, true);
        frame.invalidate();
    }

    private void moveWidgetResize(float rawX, float rawY) {
        if (resizeWidget == null || resizePage == null || resizeWidgetFrame == null) return;
        float left = resizeStartCell % gridCols * resizeCellW;
        float top = resizeStartCell / gridCols * resizeCellH;
        float right = left + resizeStartCols * resizeCellW;
        float bottom = top + resizeStartRows * resizeCellH;
        float dx = rawX - resizeStartX;
        float dy = rawY - resizeStartY;
        if ((resizeEdge & WidgetFrame.EDGE_LEFT) != 0) left += dx;
        if ((resizeEdge & WidgetFrame.EDGE_RIGHT) != 0) right += dx;
        if ((resizeEdge & WidgetFrame.EDGE_TOP) != 0) top += dy;
        if ((resizeEdge & WidgetFrame.EDGE_BOTTOM) != 0) bottom += dy;
        left = Math.max(0f, Math.min(left, right - resizeCellW * 0.5f));
        top = Math.max(0f, Math.min(top, bottom - resizeCellH * 0.5f));
        right = Math.max(left + resizeCellW * 0.5f,
                Math.min(right, resizePage.getWidth()));
        bottom = Math.max(top + resizeCellH * 0.5f,
                Math.min(bottom, resizePage.getHeight()));

        // Show the live geometry immediately. Grid snapping happens only when
        // the finger is released, so crossing a cell boundary no longer makes
        // the widget pop between two layouts.
        float sx = Math.max(0.2f, (right - left) / resizeFrameWidth);
        float sy = Math.max(0.2f, (bottom - top) / resizeFrameHeight);
        resizeWidgetFrame.setScaleX(sx);
        resizeWidgetFrame.setScaleY(sy);
        resizeWidgetFrame.setTranslationX(left - resizeFrameLeft);
        resizeWidgetFrame.setTranslationY(top - resizeFrameTop);

        int col = Math.max(0, Math.min(gridCols - 1,
                Math.round(left / resizeCellW)));
        int row = Math.max(0, Math.min(gridRows - 1,
                Math.round(top / resizeCellH)));
        int cols = Math.max(1, Math.min(gridCols - col,
                Math.round((right - left) / resizeCellW)));
        int rows = Math.max(1, Math.min(gridRows - row,
                Math.round((bottom - top) / resizeCellH)));
        int cell = row * gridCols + col;
        resizeTargetValid = !widgetOverlapAt(resizeWidget, resizeWidget.page,
                cell, cols, rows);
        resizePage.previewResizeDisplacement(resizeWidgetFrame, cell, cols, rows);
        if (!resizeTargetValid) return;
        resizeCurrentCell = cell;
        resizeCurrentCols = cols;
        resizeCurrentRows = rows;
        resizePage.setHighlight(cell, cols, rows);
    }

    private void endWidgetResize(float rawX, float rawY) {
        if (resizeWidget == null) return;
        moveWidgetResize(rawX, rawY);
        Store.PlacedWidget w = resizeWidget;
        boolean changed = resizeCurrentCell != resizeStartCell
                || resizeCurrentCols != resizeStartCols
                || resizeCurrentRows != resizeStartRows;
        if (!resizeTargetValid || !changed) {
            // An invalid or no-op resize must leave the resting home untouched.
            cancelWidgetResize();
            return;
        }

        // Icons that moved out of preview cells become part of the new layout.
        Map<Integer, Integer> moves = resizePage.resizeDisplacements();
        List<Item> page = store.page(w.page);
        boolean movedIcons = false;
        if (!moves.isEmpty()) {
            while (page.size() < gridCols * gridRows) page.add(null);
            for (Map.Entry<Integer, Integer> move : moves.entrySet()) {
                int from = move.getKey();
                int to = move.getValue();
                if (from < 0 || from >= page.size() || to < 0 || to >= page.size()) continue;
                Item icon = page.get(from);
                if (icon != null && page.get(to) == null) {
                    page.set(to, icon);
                    page.set(from, null);
                    movedIcons = true;
                }
            }
        }

        w.cell = resizeCurrentCell;
        w.spanCols = resizeCurrentCols;
        w.spanRows = resizeCurrentRows;
        store.save();
        resizePage.clearResizeDisplacement();
        resetResizePreview();
        resizePage.resizeWidget(resizeWidgetFrame, w.cell, w.spanCols, w.spanRows);
        updateWidgetSize(w);
        Haptics.drop(root);
        if (resizeWidgetFrame != null) resizeWidgetFrame.invalidate();
        clearWidgetResize();
        if (movedIcons) renderHome();
    }

    private void resetResizePreview() {
        if (resizeWidgetFrame == null) return;
        resizeWidgetFrame.setScaleX(1f);
        resizeWidgetFrame.setScaleY(1f);
        resizeWidgetFrame.setTranslationX(0f);
        resizeWidgetFrame.setTranslationY(0f);
        resizeWidgetFrame.setPivotX(resizeWidgetFrame.getWidth() / 2f);
        resizeWidgetFrame.setPivotY(resizeWidgetFrame.getHeight() / 2f);
    }

    private void cancelWidgetResize() {
        if (resizePage != null) resizePage.clearResizeDisplacement();
        resetResizePreview();
        if (resizeWidget != null && resizePage != null && resizeWidgetFrame != null) {
            resizePage.resizeWidget(resizeWidgetFrame, resizeStartCell,
                    resizeStartCols, resizeStartRows);
        }
        if (resizePage != null) resizePage.setHighlight(-1);
        Haptics.outside(root);
        clearWidgetResize();
    }

    private void clearWidgetResize() {
        if (resizePage != null) resizePage.clearResizeDisplacement();
        resetResizePreview();
        if (resizePage != null) resizePage.setHighlight(-1);
        resizeWidgetFrame = null;
        resizeWidget = null;
        resizePage = null;
        resizeEdge = 0;
        updateResizeTouchDelegate(null, false);
        clearResizeGestureExclusion();
        pager.setLocked(widgetResizeMode);
    }

    /**
     * Keep Android's edge-back/home arbitration away from a resize handle. This
     * is set while resize mode is armed, before the user starts dragging, because
     * the system gesture decision happens at ACTION_DOWN.
     */
    private void updateResizeGestureExclusion() {
        if (root == null || android.os.Build.VERSION.SDK_INT < 29) return;
        java.util.List<Rect> rects = new ArrayList<>();
        WidgetFrame frame = resizeWidgetFrame != null ? resizeWidgetFrame : resizeGestureFrame;
        if (frame != null && frame.getVisibility() == View.VISIBLE) {
            Rect r = new Rect();
            if (frame.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0) {
                int pad = Ui.dp(this, 24);
                r.inset(-pad, -pad);
                rects.add(r);
            }
        }
        try {
            root.setSystemGestureExclusionRects(rects);
        } catch (Throwable ignored) {
        }
    }

    private void clearResizeGestureExclusion() {
        if (root == null || android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            root.setSystemGestureExclusionRects(new ArrayList<Rect>());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Extend the resize hit target a little beyond the widget itself. Without
     * this, the handle is only as large as the card and is easy to miss.
     */
    private void updateResizeTouchDelegate(WidgetFrame frame, boolean enabled) {
        if (resizeDelegatePage != null) {
            resizeDelegatePage.setTouchDelegate(null);
            resizeDelegatePage = null;
        }
        if (!enabled || frame == null || !(frame.getParent() instanceof GridPage)) return;
        GridPage page = (GridPage) frame.getParent();
        int pad = Ui.dp(this, 56);
        Rect r = new Rect(frame.getLeft() - pad, frame.getTop() - pad,
                frame.getRight() + pad, frame.getBottom() + pad);
        page.setTouchDelegate(new android.view.TouchDelegate(r, frame));
        resizeDelegatePage = page;
    }

    private void startWidgetDrag(WidgetFrame frame, Store.PlacedWidget w) {
        if (frame == null || w == null || dragging) return;
        Haptics.pickup(frame);
        dismissActiveMenu();
        dragging = true;
        pager.setCardMode(true);
        pager.setLocked(true);
        resetEdgeAutoScroll();
        dragSwitchPage = -1;
        dragEdgeLatched = false;
        dragEdgeDir = 0;
        dragEdgePendingDir = 0;
        handler.removeCallbacks(dragEdgeSwitch);
        lastAutoScroll = 0;
        dragWidgetFrame = frame;
        dragWidget = w;

        int width = Math.max(1, frame.getWidth());
        int height = Math.max(1, frame.getHeight());
        if (widgetDragBitmap != null) widgetDragBitmap.recycle();
        widgetDragBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        frame.draw(new Canvas(widgetDragBitmap));

        dragView = new ImageView(this);
        dragView.setImageBitmap(widgetDragBitmap);
        dragView.setAlpha(0.95f);
        dragView.setElevation(Ui.dpf(this, 30));
        configDragViewSize(width, height);
        root.addView(dragView, new FrameLayout.LayoutParams(width, height));
        int[] fl = new int[2];
        frame.getLocationOnScreen(fl);
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        dragView.setX(fl[0] - rl[0]);
        dragView.setY(fl[1] - rl[1]);
        widgetDragStartX = dragView.getX();
        widgetDragStartY = dragView.getY();
        widgetDragDownX = frame.touchX();
        widgetDragDownY = frame.touchY();
        frame.setAlpha(0.25f);
        ensureTrash();
    }

    private void configDragViewSize(int width, int height) {
        if (dragView == null) return;
        ViewGroup.LayoutParams lp = dragView.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            lp.width = width;
            lp.height = height;
            dragView.setLayoutParams(lp);
        }
    }

    private void moveWidgetDrag(float rawX, float rawY) {
        if (dragView == null) return;
        lastRawX = rawX;
        lastRawY = rawY;
        int w = dragView.getWidth();
        int h = dragView.getHeight();
        dragView.setX(widgetDragStartX + (rawX - widgetDragDownX));
        dragView.setY(widgetDragStartY + (rawY - widgetDragDownY));
        // The ghost center is what the widget is really over; using the raw
        // finger position makes edge drops feel detached and jumpy.
        computeWidgetTarget(dragView.getX() + root.getLeft() + w / 2f,
                dragView.getY() + root.getTop() + h / 2f);
        updateWidgetHighlight();
    }

    private void computeWidgetTarget(float rawX, float rawY) {
        widgetDropTrash = false;
        widgetDropPage = -1;
        widgetDropRow = -1;
        widgetDropCol = -1;
        if (dragWidget == null) return;

        if (trash != null && trash.getParent() != null) {
            int[] tv = new int[2];
            trash.getLocationOnScreen(tv);
            if (rawX >= tv[0] - Ui.dp(this, 20)
                    && rawX <= tv[0] + trash.getWidth() + Ui.dp(this, 20)
                    && rawY >= tv[1] - Ui.dp(this, 24)
                    && rawY <= tv[1] + trash.getHeight() + Ui.dp(this, 24)) {
                widgetDropTrash = true;
                return;
            }
        }

        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        if (rawX < pl[0] - Ui.dp(this, 30)
                || rawX > pl[0] + pager.getWidth() + Ui.dp(this, 30)) return;
        if (rawY < pl[1] - Ui.dp(this, 40)
                || rawY > pl[1] + pager.getHeight() + Ui.dp(this, 40)) return;

        int cols = gridCols;
        int rows = gridRows;
        int pw = Math.max(1, pager.pageWidth());
        int page = dragSwitchPage >= 0 ? dragSwitchPage : pager.currentPage();
        page = Math.max(0, Math.min(pager.pageCount() - 1, page));
        View pageView = pager.getChildAt(page);
        if (!(pageView instanceof GridPage)) return;
        GridPage gp = (GridPage) pageView;
        // The page is scaled/dipped as a card while dragging. Map through the
        // actual page transform; using raw pager coordinates lands the widget
        // several cells away from the highlight near the card edges.
        int[] pageLoc = new int[2];
        gp.getLocationOnScreen(pageLoc);
        float pageScaleX = Math.max(0.01f, gp.getScaleX());
        float pageScaleY = Math.max(0.01f, gp.getScaleY());
        float lx = (rawX - pageLoc[0]) / pageScaleX;
        float ly = (rawY - pageLoc[1]) / pageScaleY;

        queueEdgeAutoScroll(page, Math.max(0f, Math.min(pw - 1f, lx)), pw);
        int spanC = Math.max(1, Math.min(cols, dragWidget.spanCols));
        int spanR = Math.max(1, Math.min(rows, dragWidget.spanRows));
        int spanW = spanC * gp.cellWidth();
        int spanH = spanR * gp.cellHeight();
        int cell = gp.nearestCell(lx - spanW / 2f, ly - spanH / 2f);
        int col = cell % cols;
        int row = cell / cols;
        col = Math.max(0, Math.min(cols - spanC, col));
        row = Math.max(0, Math.min(rows - spanR, row));
        int target = row * cols + col;
        if (widgetOverlapAt(dragWidget, page, target, spanC, spanR)) return;
        widgetDropPage = page;
        widgetDropRow = row;
        widgetDropCol = col;
    }

    private boolean widgetOverlapAt(Store.PlacedWidget target, int page, int cell,
                                    int spanCols, int spanRows) {
        int c = cell % gridCols;
        int r = cell / gridCols;
        if (c < 0 || r < 0 || c + spanCols > gridCols || r + spanRows > gridRows) {
            return true;
        }
        Set<Integer> wanted = new HashSet<>();
        for (int y = 0; y < spanRows; y++) {
            for (int x = 0; x < spanCols; x++) {
                wanted.add((r + y) * gridCols + c + x);
            }
        }
        if (prefs.clockVisible() && page == clockPage()) {
            for (int covered : clockCells()) {
                if (wanted.contains(covered)) return true;
            }
        }
        for (Store.PlacedWidget other : store.widgets) {
            if (other == target || other.page != page) continue;
            int oc = other.cell % gridCols;
            int or = other.cell / gridCols;
            for (int y = 0; y < other.spanRows; y++) {
                for (int x = 0; x < other.spanCols; x++) {
                    if (wanted.contains((or + y) * gridCols + oc + x)) return true;
                }
            }
        }
        return false;
    }

    private void updateWidgetHighlight() {
        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (!(c instanceof GridPage)) continue;
            if (widgetDropPage == i) {
                ((GridPage) c).setHighlight(
                        widgetDropRow * gridCols + widgetDropCol,
                        dragWidget.spanCols, dragWidget.spanRows);
            } else {
                ((GridPage) c).setHighlight(-1);
            }
        }
        if (dock != null) dock.setDropIndex(-1);
    }

    private void endWidgetDrag(float rawX, float rawY) {
        Haptics.drop(root);
        if (dragView == null || dragWidget == null || dragWidgetFrame == null) {
            finishWidgetDrag();
            return;
        }
        pager.settleScrolling();
        // The ghost has a preserved grab offset, so its center is not the
        // finger position. Use the ghost center for the final target or the
        // widget lands one cell away from the highlight the user saw.
        float dropX = rawX;
        float dropY = rawY;
        if (dragView != null) {
            dropX = dragView.getX() + root.getLeft() + dragView.getWidth() / 2f;
            dropY = dragView.getY() + root.getTop() + dragView.getHeight() / 2f;
        }
        computeWidgetTarget(dropX, dropY);
        final boolean toTrash = widgetDropTrash;
        final int page = widgetDropPage;
        final int row = widgetDropRow;
        final int col = widgetDropCol;
        final Store.PlacedWidget widget = dragWidget;

        if (page >= 0) {
            // The drag may have exposed a temporary spare page. Commit it to the
            // persistent layout before the widget renderer rebuilds the pages.
            if (page >= store.pageCount()) store.page(page);
            widget.page = page;
            widget.cell = row * gridCols + col;
            store.save();
        }

        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        Runnable done = new Runnable() {
            @Override
            public void run() {
                finishWidgetDrag();
                if (toTrash) {
                    removeAppWidget(widget);
                } else {
                    renderHome();
                }
            }
        };

        if (toTrash && trash != null) {
            int[] tv = new int[2];
            trash.getLocationOnScreen(tv);
            float tx = tv[0] + trash.getWidth() / 2f - rl[0] - dragView.getWidth() / 2f;
            float ty = tv[1] + trash.getHeight() / 2f - rl[1] - dragView.getHeight() / 2f;
            dragView.animate().x(tx).y(ty).scaleX(0.2f).scaleY(0.2f).alpha(0f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(done).start();
            return;
        }

        RectF target = page >= 0
                ? widgetTargetRect(page, row * gridCols + col, widget.spanCols, widget.spanRows)
                : widgetFrameRect(dragWidgetFrame);
        if (target == null) {
            done.run();
            return;
        }
        float sx = Math.max(0.2f, target.width() / Math.max(1, dragView.getWidth()));
        float sy = Math.max(0.2f, target.height() / Math.max(1, dragView.getHeight()));
        dragView.animate()
                .x(target.centerX() - rl[0] - dragView.getWidth() / 2f)
                .y(target.centerY() - rl[1] - dragView.getHeight() / 2f)
                .scaleX(sx).scaleY(sy).alpha(0.9f)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(done).start();
    }

    private RectF widgetTargetRect(int page, int cell, int spanCols, int spanRows) {
        if (page < 0 || page >= pager.getChildCount()) return null;
        View c = pager.getChildAt(page);
        if (!(c instanceof GridPage)) return null;
        GridPage gp = (GridPage) c;
        RectF r = new RectF();
        gp.cellRect(cell, spanCols, spanRows, r);
        r.inset(Ui.dp(this, 10), Ui.dp(this, 10));
        int[] loc = new int[2];
        gp.getLocationOnScreen(loc);
        float sx = Math.max(0.01f, gp.getScaleX());
        float sy = Math.max(0.01f, gp.getScaleY());
        float left = loc[0] + r.left * sx;
        float top = loc[1] + r.top * sy;
        return new RectF(left, top, left + r.width() * sx, top + r.height() * sy);
    }

    private RectF widgetFrameRect(WidgetFrame frame) {
        if (frame == null) return null;
        int[] fl = new int[2];
        frame.getLocationOnScreen(fl);
        return new RectF(fl[0], fl[1], fl[0] + frame.getWidth(), fl[1] + frame.getHeight());
    }

    private void cancelWidgetDrag() {
        finishWidgetDrag();
        renderHome();
    }

    private void finishWidgetDrag() {
        if (dragWidgetFrame != null) dragWidgetFrame.setAlpha(1f);
        if (dragView != null) {
            root.removeView(dragView);
            dragView = null;
        }
        if (widgetDragBitmap != null) {
            widgetDragBitmap.recycle();
            widgetDragBitmap = null;
        }
        if (trash != null && trash.getParent() != null) {
            root.removeView(trash);
        }
        dragging = false;
        resetEdgeAutoScroll();
        dragPeek = false;
        dragPeekCommitted = false;
        dragPreviewPage = -1;
        dragEdgeLatched = false;
        dragEdgeDir = 0;
        dragEdgePendingDir = 0;
        dragSwitchPage = -1;
        handler.removeCallbacks(dragEdgeSwitch);
        pager.cancelPeek(false);
        pager.setPeekVisual(false);
        pager.setCardMode(false);
        removePageTurnHints();
        pager.setLocked(widgetResizeMode);
        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (c instanceof GridPage) ((GridPage) c).setHighlight(-1);
        }
        dragWidget = null;
        dragWidgetFrame = null;
        dragSparePage = false;
        widgetDropPage = -1;
        widgetDropRow = -1;
        widgetDropCol = -1;
        widgetDropTrash = false;
    }

    private void updateWidgetSize(Store.PlacedWidget w) {
        if (w.kind != null) return;
        AppWidgetHostView hv = widgetViews.get(w.appWidgetId);
        if (hv == null) return;
        int cw = Math.max(1, pager.getWidth() / Math.max(1, gridCols));
        int ch = Math.max(1, pager.getHeight() / Math.max(1, gridRows));
        int maxW = Math.max(1, cw * w.spanCols - Ui.dp(this, 8));
        int maxH = Math.max(1, ch * w.spanRows - Ui.dp(this, 8));
        String key = gridCols + "x" + gridRows + ":" + w.spanCols + "x" + w.spanRows
                + ":" + cw + "x" + ch;
        if (key.equals(widgetSizeKeys.get(w.appWidgetId))) return;
        widgetSizeKeys.put(w.appWidgetId, key);
        try {
            hv.updateAppWidgetSize(null, maxW, maxH, maxW, maxH);
        } catch (Throwable ignored) {
        }
        try {
            float density = getResources().getDisplayMetrics().density;
            Bundle opts = new Bundle();
            opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                    Math.max(1, Math.round(maxW / density)));
            opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    Math.max(1, Math.round(maxH / density)));
            opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                    Math.max(1, Math.round(maxW / density)));
            opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                    Math.max(1, Math.round(maxH / density)));
            widgetManager.updateAppWidgetOptions(w.appWidgetId, opts);
        } catch (Throwable ignored) {
        }
    }

    private boolean sanitizeWidgets() {
        if (widgetManager == null || store == null) return false;
        if (pager != null) updateGridSize();
        boolean changed = false;
        for (int i = store.widgets.size() - 1; i >= 0; i--) {
            Store.PlacedWidget w = store.widgets.get(i);
            if (w.kind != null) {
                if (PavoHomeWidgetView.validKind(w.kind)) continue;
                store.widgets.remove(i);
                changed = true;
                continue;
            }
            AppWidgetProviderInfo info = null;
            try {
                info = widgetManager.getAppWidgetInfo(w.appWidgetId);
            } catch (Throwable ignored) {
            }
            if (info == null) {
                widgetViews.remove(w.appWidgetId);
                widgetSizeKeys.remove(w.appWidgetId);
                try {
                    widgetHost.deleteAppWidgetId(w.appWidgetId);
                } catch (Throwable ignored) {
                }
                store.widgets.remove(i);
                changed = true;
                continue;
            }
            String pkg = info.provider.getPackageName();
            String cls = info.provider.getClassName();
            if (!pkg.equals(w.providerPkg) || !cls.equals(w.providerCls)) {
                w.providerPkg = pkg;
                w.providerCls = cls;
                changed = true;
            }
            int sc = Math.max(1, Math.min(gridCols, w.spanCols));
            int sr = Math.max(1, Math.min(gridRows, w.spanRows));
            if (sc != w.spanCols || sr != w.spanRows) {
                w.spanCols = sc;
                w.spanRows = sr;
                changed = true;
            }
            int c = w.cell % gridCols;
            int r = w.cell / gridCols;
            c = Math.max(0, Math.min(gridCols - w.spanCols, c));
            r = Math.max(0, Math.min(gridRows - w.spanRows, r));
            int cell = r * gridCols + c;
            if (cell != w.cell) {
                w.cell = cell;
                changed = true;
            }
        }
        if (changed) store.save();
        return changed;
    }

    private void showAppWidgetMenu(final Store.PlacedWidget w, WidgetFrame anchor) {
        List<Menu.Entry> entries = new ArrayList<>();
        entries.add(new Menu.Entry("Resize widget", new Runnable() {
            @Override
            public void run() {
                showWidgetResizeDialog(w);
            }
        }));
        entries.add(new Menu.Entry("Remove widget", new Runnable() {
            @Override
            public void run() {
                removeAppWidget(w);
            }
        }).danger());
        dismissActiveMenu();
        activeMenu = Menu.show(this, captureBackdrop(false, 0), wallpaper.accent(),
                anchor, anchor.touchX(), anchor.touchY(), "Widget", entries, false);
    }

    private void removeAppWidget(Store.PlacedWidget w) {
        if (w == null) return;
        store.widgets.remove(w);
        store.save();
        if (w.kind == null) {
            widgetViews.remove(w.appWidgetId);
            widgetSizeKeys.remove(w.appWidgetId);
            try {
                widgetHost.deleteAppWidgetId(w.appWidgetId);
            } catch (Throwable ignored) {
            }
        }
        store.trim();
        store.save();
        renderHome();
        LToast.makeText(this, "Widget removed", Toast.LENGTH_SHORT).show();
    }

    private void showWidgetResizeDialog(final Store.PlacedWidget w) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Backdrop backdrop = captureBackdrop(false, 0);

        final GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Widget size");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        glass.addView(title);

        final int[] span = {Math.max(1, Math.min(gridCols, w.spanCols)),
                Math.max(1, Math.min(gridRows, w.spanRows))};
        addSlider(glass, "Columns", 1, gridCols, span[0], new IntCallback() {
            @Override
            public void onValue(int value) {
                span[0] = value;
            }
        });
        addSlider(glass, "Rows", 1, gridRows, span[1], new IntCallback() {
            @Override
            public void onValue(int value) {
                span[1] = value;
            }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(this, 12), 0, 0);

        TextView cancel = new LTextView(this);
        cancel.setText("Cancel");
        styleTextButton(cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(cancel);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView apply = new LTextView(this);
        apply.setText("Apply");
        styleTextButton(apply, true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (widgetOverlap(w, span[0], span[1])) {
                    LToast.makeText(MainActivity.this,
                            "Not enough room for that size", Toast.LENGTH_SHORT).show();
                    return;
                }
                w.spanCols = span[0];
                w.spanRows = span[1];
                store.save();
                widgetSizeKeys.remove(w.appWidgetId);
                dialog.dismiss();
                renderHome();
            }
        });
        buttons.addView(apply);
        glass.addView(buttons);

        showGlassDialog(dialog, glass, backdrop, 420, 0f);
        dialog.show();
    }

    /** True when enlarging this widget would collide with the clock or another widget. */
    private boolean widgetOverlap(Store.PlacedWidget target, int spanCols, int spanRows) {
        int c = target.cell % gridCols;
        int r = target.cell / gridCols;
        if (c + spanCols > gridCols || r + spanRows > gridRows) return true;
        Set<Integer> wanted = new HashSet<>();
        for (int y = 0; y < spanRows; y++) {
            for (int x = 0; x < spanCols; x++) wanted.add((r + y) * gridCols + c + x);
        }
        if (prefs.clockVisible() && target.page == clockPage()) {
            for (int cell : clockCells()) if (wanted.contains(cell)) return true;
        }
        for (Store.PlacedWidget other : store.widgets) {
            if (other == target || other.page != target.page) continue;
            int oc = other.cell % gridCols;
            int or = other.cell / gridCols;
            for (int y = 0; y < other.spanRows; y++) {
                for (int x = 0; x < other.spanCols; x++) {
                    if (wanted.contains((or + y) * gridCols + oc + x)) return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Icon packs
    // ------------------------------------------------------------------

    private void showIconPackPicker() {
        if (!IconPack.hasCachedPacks()) {
            LToast.makeText(this, "Scanning installed icon packs…", Toast.LENGTH_SHORT).show();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<IconPack.Pack> packs = IconPack.findPacks(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showIconPackDialog(packs);
                    }
                });
            }
        }, "icon-pack-scan").start();
    }

    /**
     * Present a clear Liquid Glass card over a soft, dimmed snapshot of the
     * launcher. The snapshot is screen-positioned, so the wallpaper, icons and
     * widgets stay in the right place behind the glass while the card itself
     * remains transparent and lenses its edges.
     */
    private void showGlassDialog(final Dialog dialog, final GlassPanel card,
                                 Backdrop backdrop, int cardWidthDp,
                                 float heightFraction) {
        showGlassDialog(dialog, card, backdrop, cardWidthDp, heightFraction, false);
    }

    /**
     * @param liftForKeyboard keeps an input dialog in the upper half so a
     *        landscape keyboard cannot cover its field or buttons.
     */
    private void showGlassDialog(final Dialog dialog, final GlassPanel card,
                                 Backdrop backdrop, int cardWidthDp,
                                 float heightFraction, boolean liftForKeyboard) {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        // Keep the card above the keyboard: the dialog draws edge to edge, so
        // pad by the IME inset instead of letting the keyboard cover the input
        // or the Save row.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                if (v.getPaddingBottom() != ime) {
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                            v.getPaddingRight(), ime);
                }
                return insets;
            }
        });
        // Fallback for windows that are not resized by the IME: watch the
        // visible frame and pad the bottom by whatever the keyboard covers.
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                android.graphics.Rect visible = new android.graphics.Rect();
                root.getWindowVisibleDisplayFrame(visible);
                int covered = Math.max(0, root.getRootView().getHeight() - visible.bottom);
                if (covered > root.getPaddingBottom() + 40
                        || covered + 40 < root.getPaddingBottom()) {
                    root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                            root.getPaddingRight(), covered);
                }
            }
        });

        View dim = new View(this);
        dim.setBackgroundColor(0x33000000);
        root.addView(dim, match());

        int cardWidth = Ui.dp(this, cardWidthDp);
        int cardHeight = heightFraction > 0f
                ? Math.round(getResources().getDisplayMetrics().heightPixels * heightFraction)
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        boolean lifted = liftForKeyboard && Ui.layoutHeightDp(this) < 600;
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidth, cardHeight, lifted ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                        : Gravity.CENTER);
        if (lifted) cardLp.topMargin = Ui.dp(this, 18);
        card.setClickable(true);
        card.setElevation(Ui.dpf(this, 18));
        root.addView(card, cardLp);

        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.outside(v);
                dialog.dismiss();
            }
        });
        root.setFocusable(false);
        root.setFocusableInTouchMode(false);

        final boolean overlayWasVisible = PerfOverlayService.isOverlayPanelVisible();
        if (overlayWasVisible) {
            PerfOverlayService.setOverlayVisible(false);
        }
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                if (overlayWasVisible) {
                    PerfOverlayService.setOverlayVisible(true);
                }
            }
        });

        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) {
                card.post(new Runnable() {
                    @Override
                    public void run() {
                        Motion.giggleIn(card, 0.90f, 360L, 1.25f, 0.80f, true);
                    }
                });
            }
        });
        dialog.setContentView(root, match());
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0f;
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = w.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /**
     * Controller support for the per-app icon grid. The grid keeps its own
     * selection transparent and the launcher draws the focus ring after the
     * GridView has scrolled. D-pad/hat/left stick moves by cell, A opens the
     * focused icon, up reaches the pack chips, down reaches the action row,
     * and B closes.
     */
    private void attachIconGridController(final Dialog dialog, final GridView grid,
                                          final View ringHost, final List<TextView> chips,
                                          final View buttonsRow) {
        final int MODE_GRID = 0;
        final int MODE_CHIP = 1;
        final int MODE_BUTTON = 2;
        final int[] mode = {MODE_GRID};
        final int[] index = {0};
        final int[] navDir = {-1};
        final int[] ringGeneration = {0};
        final long[] lastNav = {0L};
        final long[] lastStickAt = {0L};
        final int[] lastStickDir = {-1};
        final List<View> buttons = new ArrayList<>();
        if (buttonsRow instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) buttonsRow;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.isClickable()) buttons.add(child);
            }
        }

        final FocusRingDrawable ring;
        try {
            ring = new FocusRingDrawable(this, wallpaper.accent());
            ringHost.getOverlay().add(ring);
        } catch (Throwable t) {
            return;
        }

        final Runnable drawRing = new Runnable() {
            @Override
            public void run() {
                final int generation = ++ringGeneration[0];
                if (mode[0] == MODE_GRID) {
                    int count = grid.getAdapter() == null ? 0 : grid.getAdapter().getCount();
                    if (count == 0) return;
                    final int pos = Math.max(0, Math.min(index[0], count - 1));
                    index[0] = pos;
                    grid.setSelection(pos);
                    final int[] tries = {0};
                    final Runnable place = new Runnable() {
                        @Override
                        public void run() {
                            if (generation != ringGeneration[0]) return;
                            int first = grid.getFirstVisiblePosition();
                            View child = grid.getChildAt(pos - first);
                            if (child == null) child = grid.getSelectedView();
                            if (child != null) {
                                placeOverlayRing(ring, ringHost, child);
                            } else if (tries[0]++ < 2) {
                                grid.postDelayed(this, 16L);
                            }
                        }
                    };
                    grid.postDelayed(place, 16L);
                } else if (mode[0] == MODE_CHIP && !chips.isEmpty()) {
                    TextView chip = chips.get(Math.min(index[0], chips.size() - 1));
                    chip.requestRectangleOnScreen(new Rect(0, 0,
                            chip.getWidth(), chip.getHeight()), true);
                    placeOverlayRing(ring, ringHost, chip);
                } else if (mode[0] == MODE_BUTTON && !buttons.isEmpty()) {
                    View button = buttons.get(Math.min(index[0], buttons.size() - 1));
                    button.requestRectangleOnScreen(new Rect(0, 0,
                            button.getWidth(), button.getHeight()), true);
                    placeOverlayRing(ring, ringHost, button);
                }
            }
        };

        final Runnable activate = new Runnable() {
            @Override
            public void run() {
                if (mode[0] == MODE_GRID) {
                    int count = grid.getAdapter() == null ? 0 : grid.getAdapter().getCount();
                    if (count == 0) return;
                    final int pos = Math.max(0, Math.min(index[0], count - 1));
                    index[0] = pos;
                    grid.setSelection(pos);
                    final int[] tries = {0};
                    final Runnable click = new Runnable() {
                        @Override
                        public void run() {
                            View child = grid.getChildAt(pos - grid.getFirstVisiblePosition());
                            if (child == null && tries[0]++ < 2) {
                                grid.postDelayed(this, 16L);
                            } else if (child != null) {
                                Haptics.click(child);
                                child.performClick();
                            }
                        }
                    };
                    grid.postDelayed(click, 16L);
                } else if (mode[0] == MODE_CHIP && !chips.isEmpty()) {
                    TextView chip = chips.get(Math.min(index[0], chips.size() - 1));
                    Haptics.click(chip);
                    chip.performClick();
                } else if (mode[0] == MODE_BUTTON && !buttons.isEmpty()) {
                    View button = buttons.get(Math.min(index[0], buttons.size() - 1));
                    Haptics.click(button);
                    button.performClick();
                }
            }
        };

        final Runnable navigate = new Runnable() {
            @Override
            public void run() {
                int dir = navDir[0];
                if (dir < 0) return;
                int count = grid.getAdapter() == null ? 0 : grid.getAdapter().getCount();
                int cols = Math.max(1, grid.getNumColumns());
                if (mode[0] == MODE_GRID) {
                    if (count > 0) {
                        int next = index[0];
                        if (dir == 0) next--;
                        else if (dir == 1) next++;
                        else if (dir == 2) next -= cols;
                        else next += cols;
                        if (next >= 0 && next < count) {
                            index[0] = next;
                        } else if (dir == 2 && !chips.isEmpty()) {
                            mode[0] = MODE_CHIP;
                            index[0] = 0;
                        } else if (dir == 3 && !buttons.isEmpty()) {
                            mode[0] = MODE_BUTTON;
                            index[0] = 0;
                        } else if (dir == 0 || dir == 1) {
                            index[0] = dir == 0 ? count - 1 : 0;
                        } else {
                            return;
                        }
                    } else if (dir == 2 && !chips.isEmpty()) {
                        mode[0] = MODE_CHIP;
                        index[0] = 0;
                    } else if (dir == 3 && !buttons.isEmpty()) {
                        mode[0] = MODE_BUTTON;
                        index[0] = 0;
                    } else {
                        return;
                    }
                } else if (mode[0] == MODE_CHIP) {
                    if (dir == 3) {
                        mode[0] = MODE_GRID;
                        index[0] = 0;
                    } else if (dir == 2) {
                        return;
                    } else if (!chips.isEmpty()) {
                        int next = index[0] + (dir == 1 ? 1 : -1);
                        index[0] = (next + chips.size()) % chips.size();
                    }
                } else {
                    if (dir == 2) {
                        mode[0] = MODE_GRID;
                        index[0] = Math.max(0, count - 1);
                    } else if (!buttons.isEmpty()) {
                        int next = index[0] + (dir == 1 ? 1 : -1);
                        index[0] = (next + buttons.size()) % buttons.size();
                    }
                }
                drawRing.run();
                // A GridView scrolls asynchronously after setSelection(), so
                // re-place the ring once the new children have laid out.
                grid.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        drawRing.run();
                    }
                }, 60L);
                Haptics.tick(ringHost);
            }
        };

        final Dialog.OnKeyListener keyHandler = new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface d, int keyCode, KeyEvent event) {
                int dir = keyDirection(keyCode);
                boolean select = isSelectKey(keyCode);
                boolean back = isBackKey(keyCode);
                if (dir < 0 && !select && !back) return false;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (back) {
                    if (event.getRepeatCount() == 0) {
                        Haptics.key(ringHost);
                        dialog.dismiss();
                    }
                    return true;
                }
                if (select) {
                    if (event.getRepeatCount() == 0) activate.run();
                    return true;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (event.getRepeatCount() > 0 && now - lastNav[0] < 70L) return true;
                lastNav[0] = now;
                navDir[0] = dir;
                navigate.run();
                return true;
            }
        };
        dialog.setOnKeyListener(keyHandler);
        ringHost.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return keyHandler.onKey(dialog, keyCode, event);
            }
        });

        final View.OnGenericMotionListener generic = new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_MOVE) return false;
                int source = event.getSource();
                boolean gamepad = (source & InputDevice.SOURCE_JOYSTICK) != 0
                        || (source & InputDevice.SOURCE_GAMEPAD) != 0;
                if (!gamepad) return false;
                float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
                float x = hatX;
                float y = hatY;
                if (Math.abs(x) < 0.55f && Math.abs(y) < 0.55f) {
                    x = event.getAxisValue(MotionEvent.AXIS_X);
                    y = event.getAxisValue(MotionEvent.AXIS_Y);
                }
                float magnitude = (float) Math.hypot(x, y);
                if (magnitude < 0.62f) {
                    lastStickDir[0] = -1;
                    return true;
                }
                int dir = Math.abs(x) > Math.abs(y)
                        ? (x > 0f ? 1 : 0) : (y > 0f ? 3 : 2);
                long now = android.os.SystemClock.uptimeMillis();
                if (dir != lastStickDir[0] || now - lastStickAt[0] >= 280L) {
                    lastStickDir[0] = dir;
                    lastStickAt[0] = now;
                    navDir[0] = dir;
                    navigate.run();
                }
                return true;
            }
        };
        grid.setFocusable(false);
        grid.setFocusableInTouchMode(false);
        grid.setOnGenericMotionListener(generic);
        buttonsRow.setFocusable(false);
        buttonsRow.setFocusableInTouchMode(false);
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setFocusable(false);
            buttons.get(i).setFocusableInTouchMode(false);
        }
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            chip.setFocusable(false);
            chip.setFocusableInTouchMode(false);
            if (chip.getParent() instanceof View) {
                ((View) chip.getParent()).setFocusable(false);
                if (chip.getParent().getParent() instanceof View) {
                    ((View) chip.getParent().getParent()).setFocusable(false);
                }
            }
        }
        buttonsRow.setOnGenericMotionListener(generic);
        ringHost.setFocusable(true);
        ringHost.setFocusableInTouchMode(true);
        ringHost.setOnGenericMotionListener(generic);
        ringHost.post(new Runnable() {
            @Override
            public void run() {
                Window w = dialog.getWindow();
                if (w != null) {
                    View decor = w.getDecorView();
                    decor.setOnGenericMotionListener(generic);
                    grid.clearFocus();
                    ringHost.requestFocus();
                    ringHost.requestFocusFromTouch();
                }
            }
        });

        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                mode[0] = MODE_GRID;
                int first = grid.getFirstVisiblePosition();
                int start = 0;
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View child = grid.getChildAt(i);
                    if (child instanceof android.widget.ImageView
                            && ((android.widget.ImageView) child).getDrawable() != null) {
                        start = first + i;
                        break;
                    }
                }
                index[0] = Math.max(0, start);
                grid.setSelection(index[0]);
                drawRing.run();
            }
        }, 120L);
    }

    private void showIconPackDialog(final List<IconPack.Pack> packs) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Backdrop backdrop = captureBackdrop(false, 0);

        GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 26, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        glass.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 12));

        TextView title = new LTextView(this);
        title.setText("Icon packs");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        glass.addView(title);

        TextView hint = new LTextView(this);
        hint.setText("Themes are read from each pack's appfilter.xml. Custom per-app icons still take priority.");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 6));
        glass.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        scroll.addView(list);

        final String current = prefs.iconPack();
        final PackageManager pm = getPackageManager();
        final int iconPx = Ui.dp(this, 40);

        addIconPackRow(list, null, "System default", null, current.isEmpty(), dialog, pm, iconPx);
        for (final IconPack.Pack pack : packs) {
            addIconPackRow(list, pack.pkg, pack.label, pack.icon,
                    current.equals(pack.pkg), dialog, pm, iconPx);
        }
        if (packs.isEmpty()) {
            TextView empty = new LTextView(this);
            empty.setText("No icon packs found. Install an icon pack that includes appfilter.xml.");
            empty.setTextColor(0x80FFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                    Ui.dp(this, 10), Ui.dp(this, 8));
            list.addView(empty);
        }

        glass.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        showGlassDialog(dialog, glass, backdrop, 480, 0.78f);
        dialog.show();
        attachCardController(dialog, glass, list);
    }

    private void addIconPackRow(LinearLayout list, final String pkg, String label,
                                Bitmap packIcon, boolean selected, final Dialog dialog,
                                PackageManager pm, int iconPx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 8));
        row.setBackground(Ui.ripple(this, Ui.dpf(this, 14)));
        row.setClickable(true);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bmp = packIcon;
        if (bmp == null) {
            try {
                bmp = Apps.drawableToBitmap(pm.getApplicationIcon(getPackageName()), iconPx);
            } catch (Throwable ignored) {
            }
        }
        icon.setImageBitmap(bmp);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconPx, iconPx);
        ilp.rightMargin = Ui.dp(this, 12);
        row.addView(icon, ilp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFFF2F4F8);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new LTextView(this);
        check.setText(selected ? "\u2713" : "");
        check.setTextColor(0xFF7CF7C4);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(check);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                applyIconPack(pkg == null ? "" : pkg);
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        dlp.leftMargin = Ui.dp(this, 62);
        list.addView(divider, dlp);
    }

    private void applyIconPack(final String pkg) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final IconPack.Resolver resolver = pkg.isEmpty() ? null
                        : IconPack.resolver(MainActivity.this, pkg, Ui.dp(MainActivity.this, 96));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (!pkg.isEmpty() && resolver == null) {
                            LToast.makeText(MainActivity.this,
                                    "Could not load that icon pack", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        iconPack = resolver;
                        prefs.setIconPack(pkg);
                        refreshVisible();
                        LToast.makeText(MainActivity.this,
                                pkg.isEmpty() ? "System icons restored" : "Icon pack applied",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "icon-pack-apply").start();
    }

    private void showIconChooser(final Item item) {
        if (item == null) return;
        if (!IconPack.hasCachedPacks()) {
            LToast.makeText(this, "Loading icon packs…", Toast.LENGTH_SHORT).show();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<IconPack.Pack> packs = IconPack.findPacks(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showIconChooserDialog(item, false, packs);
                    }
                });
            }
        }, "icon-chooser-scan").start();
    }

    private void showDockIconChooser() {
        if (!IconPack.hasCachedPacks()) {
            LToast.makeText(this, "Loading icon packs…", Toast.LENGTH_SHORT).show();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<IconPack.Pack> packs = IconPack.findPacks(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        showIconChooserDialog(null, true, packs);
                    }
                });
            }
        }, "dock-icon-scan").start();
    }

    private void showIconChooserDialog(final Item item, final boolean dockButton,
                                       final List<IconPack.Pack> packs) {
        String titleText;
        if (dockButton) {
            titleText = "App drawer button";
        } else {
            titleText = item.type == Item.TYPE_WEB ? item.title : item.pkg;
            if (item.type == Item.TYPE_APP) {
                Apps.Entry e = isPerfLogs(item.pkg) ? perfLogsEntry()
                        : findEntry(item.pkg, item.cls);
                if (e == null) e = Apps.entryFor(this, item.pkg, item.cls);
                titleText = store.labelFor(item, e != null ? e.label : item.pkg);
            }
        }
        final String itemLabel = titleText;

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Backdrop backdrop = captureBackdrop(false, 0);

        final GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x4D0B0E14, 26, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        glass.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 12));

        TextView title = new LTextView(this);
        title.setText(dockButton ? "Drawer icon" : "Change icon");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        glass.addView(title);

        TextView hint = new LTextView(this);
        hint.setText(itemLabel + " — choose any icon from an installed pack.");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 8));
        glass.addView(hint);

        final IconPack.Pack[] selected = {null};
        String preferred = prefs.iconPack();
        for (IconPack.Pack p : packs) {
            if (p.pkg.equals(preferred)) {
                selected[0] = p;
                break;
            }
        }
        if (selected[0] == null && !packs.isEmpty()) selected[0] = packs.get(0);

        final int iconPx = Ui.dp(this, 52);
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return selected[0] == null || selected[0].resolver == null
                        ? 0 : selected[0].resolver.iconNames().size();
            }

            @Override
            public Object getItem(int position) {
                if (selected[0] == null || selected[0].resolver == null) return null;
                List<String> names = selected[0].resolver.iconNames();
                return position >= 0 && position < names.size() ? names.get(position) : null;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView image;
                if (convertView instanceof ImageView) {
                    image = (ImageView) convertView;
                } else {
                    image = new ImageView(MainActivity.this);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    image.setPadding(Ui.dp(MainActivity.this, 5), Ui.dp(MainActivity.this, 5),
                            Ui.dp(MainActivity.this, 5), Ui.dp(MainActivity.this, 5));
                    image.setBackground(Ui.ripple(MainActivity.this, Ui.dpf(MainActivity.this, 12)));
                }
                image.setImageDrawable(null);
                if (selected[0] == null || selected[0].resolver == null) return image;
                List<String> names = selected[0].resolver.iconNames();
                if (position < 0 || position >= names.size()) return image;
                final String name = names.get(position);
                image.setContentDescription(name);
                Bitmap bmp = selected[0].resolver.iconByName(name, iconPx);
                if (bmp != null) image.setImageBitmap(bmp);
                image.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (selected[0] == null || selected[0].resolver == null) return;
                        Bitmap chosen = selected[0].resolver.iconByName(name,
                                Ui.dp(MainActivity.this, 192));
                        if (chosen == null) {
                            LToast.makeText(MainActivity.this, "Could not load that icon",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (dockButton) {
                            saveDockIcon(chosen);
                            dialog.dismiss();
                            populateDock();
                            LToast.makeText(MainActivity.this, "Drawer icon updated",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            store.setIcon(item, chosen);
                            dialog.dismiss();
                            refreshVisible();
                            LToast.makeText(MainActivity.this, "Icon updated",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                return image;
            }
        };

        final List<TextView> chips = new ArrayList<>();
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, 0, 0, Ui.dp(this, 8));
        chipScroll.addView(chipRow);
        glass.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < packs.size(); i++) {
            final int index = i;
            final IconPack.Pack pack = packs.get(i);
            final TextView chip = new TextView(this);
            chip.setText(pack.label);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(Ui.dp(this, 13), Ui.dp(this, 7),
                    Ui.dp(this, 13), Ui.dp(this, 7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 8);
            chipRow.addView(chip, clp);
            chips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected[0] = pack;
                    styleChooserChip(chips, selected[0], packs);
                    adapter.notifyDataSetChanged();
                }
            });
        }

        final GridView grid = new GridView(this);
        grid.setNumColumns(8);
        grid.setVerticalSpacing(Ui.dp(this, 4));
        grid.setHorizontalSpacing(0);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setPadding(0, 0, 0, Ui.dp(this, 4));
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        grid.setVerticalScrollBarEnabled(false);
        glass.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (packs.isEmpty()) {
            TextView empty = new LTextView(this);
            empty.setText("No icon packs are installed. Install an icon pack that includes appfilter.xml or drawable.xml, or choose a custom image below.");
            empty.setTextColor(0x80FFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setPadding(Ui.dp(this, 6), Ui.dp(this, 6),
                    Ui.dp(this, 6), Ui.dp(this, 10));
            glass.addView(empty);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(this, 8), 0, 0);

        TextView reset = new LTextView(this);
        reset.setText("Reset");
        styleTextButton(reset, false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dockButton) {
                    clearDockIcon();
                    dialog.dismiss();
                    populateDock();
                    LToast.makeText(MainActivity.this, "Drawer icon reset",
                            Toast.LENGTH_SHORT).show();
                } else {
                    store.clearIcon(item);
                    dialog.dismiss();
                    refreshVisible();
                    LToast.makeText(MainActivity.this, "Custom icon removed",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        buttons.addView(reset);

        TextView pick = new LTextView(this);
        pick.setText("Choose image…");
        styleTextButton(pick, false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                pickMode = dockButton ? PICK_DOCK_ICON : PICK_ICON;
                pickTarget = dockButton ? null : item;
                launchImagePicker();
            }
        });
        buttons.addView(pick);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView close = new LTextView(this);
        close.setText("Close");
        styleTextButton(close, true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
            }
        });
        buttons.addView(close);
        glass.addView(buttons);

        styleChooserChip(chips, selected[0], packs);

        showGlassDialog(dialog, glass, backdrop, 720, 0.84f);
        attachIconGridController(dialog, grid, glass, chips, buttons);
        dialog.show();
    }

    private void styleChooserChip(List<TextView> chips, IconPack.Pack selected,
                                  List<IconPack.Pack> packs) {
        for (int i = 0; i < chips.size() && i < packs.size(); i++) {
            TextView chip = chips.get(i);
            boolean on = packs.get(i) == selected;
            if (on) {
                chip.setTextColor(0xFF0B0E12);
                chip.setBackground(Ui.rounded(
                        wallpaper != null ? wallpaper.accent() : ThemeColors.accent(),
                        Ui.dpf(this, 17)));
            } else {
                chip.setTextColor(0xE6FFFFFF);
                chip.setBackground(Ui.rounded(0x14FFFFFF, Ui.dpf(this, 17),
                        0x22FFFFFF, Ui.dpf(this, 1)));
            }
        }
    }

    private void showWidgetPicker() {
        List<AppWidgetProviderInfo> providers = new ArrayList<>();
        try {
            providers.addAll(widgetManager.getInstalledProviders());
        } catch (Throwable ignored) {
        }
        final PackageManager pm = getPackageManager();
        providers.sort((a, b) -> widgetLabel(a, pm).compareToIgnoreCase(widgetLabel(b, pm)));

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Backdrop backdrop = captureBackdrop(false, 0);

        GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 24, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        glass.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 10));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new LTextView(this);
        title.setText("Add widget");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 9), Ui.dp(this, 3),
                Ui.dp(this, 9), Ui.dp(this, 3));
        title.setBackground(Ui.rounded(0x0EFFFFFF, Ui.dpf(this, 10),
                0x44FFFFFF, Ui.dpf(this, 1)));
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grip = new LTextView(this);
        grip.setText("\u283F");
        grip.setTextColor(0x80FFFFFF);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        grip.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        titleRow.addView(grip);
        glass.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new LTextView(this);
        hint.setText("Tap a widget to open it  ·  double-tap to resize  ·  then drag anywhere on the card");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), 0, Ui.dp(this, 7));
        glass.addView(hint);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, 0, 0, Ui.dp(this, 5));
        final TextView pavoTab = widgetTab("PAVO WIDGETS", true);
        final TextView installedTab = widgetTab("INSTALLED", false);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(pavoTab, tabLp);
        LinearLayout.LayoutParams installedTabLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        installedTabLp.leftMargin = Ui.dp(this, 6);
        tabs.addView(installedTab, installedTabLp);
        glass.addView(tabs);

        FrameLayout pageHost = new FrameLayout(this);
        ScrollView pavoScroll = widgetScroll();
        LinearLayout auroraList = widgetList();
        pavoScroll.addView(auroraList);

        addClockWidgetRow(auroraList, dialog);
        addPavoWidgetRow(auroraList, PavoHomeWidgetView.KIND_BATTERY,
                Loc.s(MainActivity.this, "Device Health"), Loc.s(MainActivity.this, "Battery pack outline · liquid animation"),
                Apps.deviceHealthIcon(Ui.dp(this, 40), wallpaper.accent()), dialog);
        addPavoWidgetRow(auroraList, PavoHomeWidgetView.KIND_FAN,
                Loc.s(MainActivity.this, "Fan Control"), Loc.s(MainActivity.this, "Rear shell outline · airflow animation"),
                Apps.fanIcon(Ui.dp(this, 40), wallpaper.accent()), dialog);
        addPavoWidgetRow(auroraList, PavoHomeWidgetView.KIND_THERMAL,
                Loc.s(MainActivity.this, "Performance Tuner"), Loc.s(MainActivity.this, "Thermal board · heat and steam animation"),
                Apps.performanceTunerIcon(Ui.dp(this, 40), wallpaper.accent()), dialog);

        ScrollView installedScroll = widgetScroll();
        LinearLayout installedList = widgetList();
        installedScroll.addView(installedList);
        if (providers.isEmpty()) {
            TextView empty = new LTextView(this);
            empty.setText("No installed app widgets");
            empty.setTextColor(0x80FFFFFF);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setPadding(Ui.dp(this, 12), Ui.dp(this, 14),
                    Ui.dp(this, 12), Ui.dp(this, 14));
            installedList.addView(empty);
        } else {
            for (final AppWidgetProviderInfo info : providers) {
                if (info == null || info.provider == null) continue;
                addInstalledWidgetRow(installedList, info, pm, dialog);
            }
        }

        pageHost.addView(pavoScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageHost.addView(installedScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        installedScroll.setVisibility(View.GONE);
        glass.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final boolean[] showPavo = {true};
        final Runnable applyPage = new Runnable() {
            @Override
            public void run() {
                pavoScroll.setVisibility(showPavo[0] ? View.VISIBLE : View.GONE);
                installedScroll.setVisibility(showPavo[0] ? View.GONE : View.VISIBLE);
                styleWidgetTab(pavoTab, showPavo[0]);
                styleWidgetTab(installedTab, !showPavo[0]);
                attachCardController(dialog, glass, glass);
            }
        };
        pavoTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (showPavo[0]) return;
                showPavo[0] = true;
                Haptics.click(v);
                applyPage.run();
            }
        });
        installedTab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!showPavo[0]) return;
                showPavo[0] = false;
                Haptics.click(v);
                applyPage.run();
            }
        });

        showGlassDialog(dialog, glass, backdrop, 380, 0.64f);
        final DisplayMetrics wdm = getResources().getDisplayMetrics();
        final float[] drag = new float[4];
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        drag[0] = e.getRawX();
                        drag[1] = e.getRawY();
                        drag[2] = glass.getX();
                        drag[3] = glass.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float nx = drag[2] + e.getRawX() - drag[0];
                        float ny = drag[3] + e.getRawY() - drag[1];
                        nx = Math.max(0, Math.min(nx,
                                wdm.widthPixels - glass.getWidth()));
                        ny = Math.max(0, Math.min(ny,
                                wdm.heightPixels - glass.getHeight()));
                        glass.setX(nx);
                        glass.setY(ny);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        Haptics.tick(v);
                        return true;
                    default:
                        return true;
                }
            }
        };
        titleRow.setOnTouchListener(dragListener);
        title.setOnTouchListener(dragListener);
        grip.setOnTouchListener(dragListener);
        dialog.show();
        applyPage.run();
    }

    private TextView widgetTab(String text, boolean active) {
        TextView tab = new LTextView(this);
        tab.setText(text);
        tab.setGravity(Gravity.CENTER);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tab.setLetterSpacing(0.08f);
        tab.setPadding(Ui.dp(this, 8), Ui.dp(this, 8),
                Ui.dp(this, 8), Ui.dp(this, 8));
        styleWidgetTab(tab, active);
        return tab;
    }

    private void styleWidgetTab(TextView tab, boolean active) {
        int accent = wallpaper != null ? wallpaper.accent() : ThemeColors.accent();
        tab.setTextColor(active ? 0xFF061015 : 0xCCFFFFFF);
        tab.setBackground(Ui.rounded(active ? accent : 0x16FFFFFF,
                Ui.dpf(this, 12), active ? 0x00000000 : 0x33FFFFFF,
                Ui.dpf(this, 1)));
    }

    private ScrollView widgetScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    private LinearLayout widgetList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        return list;
    }

    private void addPavoWidgetRow(LinearLayout list, final String kind,
                                    String name, String description, Bitmap icon,
                                    final Dialog dialog) {
        LinearLayout row = widgetRow(icon, name, description);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
                placePavoWidget(kind);
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addWidgetDivider(list);
    }

    private void addInstalledWidgetRow(LinearLayout list,
                                       final AppWidgetProviderInfo info,
                                       PackageManager pm, final Dialog dialog) {
        String label = widgetLabel(info, pm);
        String sub = info.provider.getPackageName();
        Bitmap icon = null;
        try {
            icon = Apps.drawableToBitmap(
                    pm.getApplicationIcon(info.provider.getPackageName()),
                    Ui.dp(this, 40));
        } catch (Throwable ignored) {
        }
        LinearLayout row = widgetRow(icon, label, sub);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
                beginBindWidget(info);
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addWidgetDivider(list);
    }

    private LinearLayout widgetRow(Bitmap icon, String name, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 8));
        row.setBackground(Ui.ripple(this, Ui.dpf(this, 14)));
        row.setClickable(true);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (icon != null) image.setImageBitmap(icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 40));
        ilp.rightMargin = Ui.dp(this, 12);
        row.addView(image, ilp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new LTextView(this);
        title.setText(name);
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.addView(title);
        TextView sub = new LTextView(this);
        sub.setText(description);
        sub.setTextColor(0x66FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setPadding(0, Ui.dp(this, 2), 0, 0);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void addWidgetDivider(LinearLayout list) {
        View divider = new View(this);
        divider.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        dlp.leftMargin = Ui.dp(this, 62);
        list.addView(divider, dlp);
    }

    private String widgetLabel(AppWidgetProviderInfo info, PackageManager pm) {
        CharSequence c = null;
        try {
            c = info.loadLabel(pm);
        } catch (Throwable ignored) {
        }
        String s = c == null ? "" : c.toString().trim();
        if (s.isEmpty()) {
            try {
                s = pm.getApplicationLabel(
                        pm.getApplicationInfo(info.provider.getPackageName(), 0)).toString().trim();
            } catch (Throwable ignored) {
            }
        }
        return s.isEmpty() ? info.provider.getPackageName() : s;
    }

    private void beginBindWidget(AppWidgetProviderInfo info) {
        if (info == null || info.provider == null) return;
        cleanupPendingWidget();
        pendingWidgetInfo = info;
        try {
            pendingWidgetId = widgetHost.allocateAppWidgetId();
        } catch (Throwable t) {
            pendingWidgetInfo = null;
            pendingWidgetId = -1;
            LToast.makeText(this, "Could not create the widget", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean allowed = false;
        try {
            allowed = widgetManager.bindAppWidgetIdIfAllowed(pendingWidgetId, info.provider);
        } catch (Throwable ignored) {
        }
        if (allowed) {
            startWidgetConfigurationOrPlace();
            return;
        }

        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        try {
            startActivityForResult(bind, REQ_WIDGET_BIND);
        } catch (Throwable t) {
            cleanupPendingWidget();
            LToast.makeText(this, "Widget binding is unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void startWidgetConfigurationOrPlace() {
        if (pendingWidgetInfo == null || pendingWidgetId < 0) return;
        if (pendingWidgetInfo.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId,
                        0, REQ_WIDGET_CONFIGURE, null);
                return;
            } catch (Throwable ignored) {
            }
        }
        placePendingWidget();
    }

    private void placePendingWidget() {
        if (pendingWidgetInfo == null || pendingWidgetId < 0) return;
        AppWidgetProviderInfo info = pendingWidgetInfo;
        int id = pendingWidgetId;

        float density = getResources().getDisplayMetrics().density;
        int cw = Math.max(1, Math.round(Math.max(1, pager.getWidth() / gridCols) / density));
        int ch = Math.max(1, Math.round(Math.max(1, pager.getHeight() / gridRows) / density));
        int spanCols;
        int spanRows;
        if (info.targetCellWidth > 0) {
            spanCols = info.targetCellWidth;
        } else if (info.minWidth > 0) {
            spanCols = (int) Math.ceil(info.minWidth / (float) cw);
        } else {
            spanCols = 2;
        }
        if (info.targetCellHeight > 0) {
            spanRows = info.targetCellHeight;
        } else if (info.minHeight > 0) {
            spanRows = (int) Math.ceil(info.minHeight / (float) ch);
        } else {
            spanRows = 1;
        }
        spanCols = Math.max(1, Math.min(gridCols, spanCols));
        spanRows = Math.max(1, Math.min(gridRows, spanRows));

        int page = Math.max(0, Math.min(pager.pageCount() - 1, pager.currentPage()));
        int cell = findFreeWidgetCell(page, spanCols, spanRows);
        if (cell < 0) {
            for (int p = 0; p < store.pageCount(); p++) {
                if (p == page) continue;
                cell = findFreeWidgetCell(p, spanCols, spanRows);
                if (cell >= 0) {
                    page = p;
                    break;
                }
            }
        }
        if (cell < 0) {
            page = store.pageCount();
            store.page(page);
            cell = 0;
        }

        Store.PlacedWidget w = new Store.PlacedWidget();
        w.appWidgetId = id;
        w.providerPkg = info.provider.getPackageName();
        w.providerCls = info.provider.getClassName();
        w.page = page;
        w.cell = cell;
        w.spanCols = spanCols;
        w.spanRows = spanRows;
        store.widgets.add(w);
        store.save();

        pendingWidgetInfo = null;
        pendingWidgetId = -1;
        renderHome();
        pager.setPage(page, false);
        updateDots(page, pager.pageCount());
        LToast.makeText(this, "Widget added", Toast.LENGTH_SHORT).show();
    }

    private void placePavoWidget(String kind) {
        if (!PavoHomeWidgetView.validKind(kind)) return;
        int spanCols = Math.max(1, Math.min(gridCols,
                PavoHomeWidgetView.spanCols(kind)));
        int spanRows = Math.max(1, Math.min(gridRows,
                PavoHomeWidgetView.spanRows(kind)));
        int page = Math.max(0, Math.min(pager.pageCount() - 1, pager.currentPage()));
        int cell = findFreeWidgetCell(page, spanCols, spanRows);
        if (cell < 0) {
            for (int p = 0; p < store.pageCount(); p++) {
                if (p == page) continue;
                cell = findFreeWidgetCell(p, spanCols, spanRows);
                if (cell >= 0) {
                    page = p;
                    break;
                }
            }
        }
        if (cell < 0) {
            page = store.pageCount();
            store.page(page);
            cell = 0;
        }

        Store.PlacedWidget w = new Store.PlacedWidget();
        w.appWidgetId = -1;
        w.kind = kind;
        w.page = page;
        w.cell = cell;
        w.spanCols = spanCols;
        w.spanRows = spanRows;
        store.widgets.add(w);
        store.save();
        renderHome();
        pager.setPage(page, false);
        updateDots(page, pager.pageCount());
        LToast.makeText(this, PavoHomeWidgetView.labelFor(this, kind) + " " + Loc.s(MainActivity.this, "widget added"),
                Toast.LENGTH_SHORT).show();
    }

    private void cleanupPendingWidget() {
        if (pendingWidgetId >= 0) {
            try {
                widgetHost.deleteAppWidgetId(pendingWidgetId);
            } catch (Throwable ignored) {
            }
        }
        pendingWidgetInfo = null;
        pendingWidgetId = -1;
    }

    private int clockPage() {        return Math.max(0, Math.min(store.pageCount() - 1, prefs.clockPage()));
    }

    private int clockCell() {
        int cols = gridCols;
        int rows = gridRows;
        int col = Math.max(0, Math.min(cols - clockSpanCols, prefs.clockCol()));
        int row = Math.max(0, Math.min(rows - clockSpanRows, prefs.clockRow()));
        return row * cols + col;
    }

    private void computeClockSpan() {
        if (clockBox == null) return;
        // Make sure the weather line participates in the measurement, otherwise the span
        // is computed too small and the text is clipped once the weather arrives.
        if (weatherView != null && prefs.weatherVisible() && prefs.weatherCode() >= 0) {
            weatherView.setVisibility(View.VISIBLE);
        }
        if (weatherGlyph != null && prefs.weatherVisible() && prefs.weatherCode() >= 0) {
            weatherGlyph.setCode(prefs.weatherCode());
            weatherGlyph.setVisibility(View.VISIBLE);
        }
        clockBox.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int pw = pager.getWidth();
        int ph = pager.getHeight();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (pw <= 0) pw = dm.widthPixels - Ui.dp(this, 60);
        if (ph <= 0) {
            ph = dm.heightPixels - Ui.dp(this, 14 + 10 + 18 + 94 + 46);
        }
        int cw = Math.max(1, pw / Math.max(1, gridCols));
        int ch = Math.max(1, ph / Math.max(1, gridRows));
        int pad = Ui.dp(this, 6);
        int sc = (clockBox.getMeasuredWidth() + pad + cw - 1) / cw;
        int sr = (clockBox.getMeasuredHeight() + pad + ch - 1) / ch;
        clockSpanCols = Math.max(1, Math.min(gridCols, sc));
        clockSpanRows = Math.max(1, Math.min(gridRows, sr));
    }

    private final Runnable clockReflow = new Runnable() {
        @Override
        public void run() {
            if (clockBox == null) return;
            int oldCols = clockSpanCols;
            int oldRows = clockSpanRows;
            computeClockSpan();
            if (oldCols != clockSpanCols || oldRows != clockSpanRows) {
                renderHome();
            } else {
                clockBox.requestLayout();
            }
        }
    };

    private void scheduleClockReflow() {
        handler.removeCallbacks(clockReflow);
        handler.post(clockReflow);
    }

    private java.util.Set<Integer> clockCells() {
        java.util.Set<Integer> cells = new java.util.HashSet<>();
        if (!prefs.clockVisible()) return cells;
        int cols = gridCols;
        int cell = clockCell();
        int r = cell / cols;
        int c = cell % cols;
        for (int y = r; y < r + clockSpanRows; y++) {
            for (int x = c; x < c + clockSpanCols; x++) {
                cells.add(y * cols + x);
            }
        }
        return cells;
    }

    private boolean clockCovers(int page, int cell) {
        if (!prefs.clockVisible() || page != clockPage()) return false;
        return clockCells().contains(cell);
    }

    /** Move any apps sitting under the clock widget into free slots. */
    private void ensureClockClear() {
        if (!prefs.clockVisible() || store.pageCount() == 0) return;
        int page = clockPage();
        java.util.Set<Integer> covered = clockCells();
        List<Item> pg = store.page(page);
        int cap = capacity();
        List<Item> displaced = new ArrayList<>();
        for (int cell : covered) {
            if (cell < pg.size() && pg.get(cell) != null) {
                displaced.add(pg.get(cell));
                pg.set(cell, null);
            }
        }
        boolean changed = !displaced.isEmpty();
        for (Item item : displaced) {
            boolean placed = false;
            for (int p = 0; p < store.pageCount() && !placed; p++) {
                List<Item> pl = store.page(p);
                for (int i = 0; i < cap; i++) {
                    if (p == page && covered.contains(i)) continue;
                    if (i >= pl.size() || pl.get(i) == null) {
                        while (pl.size() <= i) pl.add(null);
                        pl.set(i, item);
                        placed = true;
                        break;
                    }
                }
            }
            if (!placed) {
                List<Item> np = new ArrayList<>();
                np.add(item);
                store.pages.add(np);
            }
        }
        if (changed) store.save();
    }

    /** The icon pack's drawer/app-grid icon, when the pack provides one. */
    private Bitmap drawerButtonIcon() {
        File custom = new File(getFilesDir(), "dock_icon.png");
        if (custom.exists()) {
            try {
                Bitmap b = BitmapFactory.decodeFile(custom.getAbsolutePath());
                if (b != null) return b;
            } catch (Throwable ignored) {
            }
        }
        if (iconPack == null) return null;
        int px = Ui.dp(this, 48);
        String[] candidates = {
                "app_drawer", "appdrawer", "ic_apps", "ic_drawer", "drawer",
                "all_apps", "allapps", "app_menu", "appmenu", "apps", "menu"
        };
        for (String name : candidates) {
            Bitmap b = iconPack.iconByName(name, px);
            if (b != null) return b;
        }
        for (String name : iconPack.iconNames()) {
            String lower = name.toLowerCase(Locale.US);
            if (lower.contains("drawer") || lower.contains("all_apps")
                    || lower.contains("allapps") || lower.contains("app_menu")) {
                Bitmap b = iconPack.iconByName(name, px);
                if (b != null) return b;
            }
        }
        return null;
    }

    private void saveDockIcon(Bitmap bitmap) {
        try {
            FileOutputStream fos = new FileOutputStream(
                    new File(getFilesDir(), "dock_icon.png"));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private void clearDockIcon() {
        File f = new File(getFilesDir(), "dock_icon.png");
        if (f.exists()) f.delete();
    }

    private void showDockMenu(View anchor) {
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        showDockMenu(anchor, loc[0] + anchor.getWidth() / 2f,
                loc[1] + anchor.getHeight() / 2f);
    }

    private void showDockMenu(View anchor, final float touchX, final float touchY) {
        List<Menu.Entry> entries = new ArrayList<>();
        entries.add(new Menu.Entry("Open app drawer", new Runnable() {
            @Override
            public void run() {
                openDrawer(prefs.drawerButtonPopup());
            }
        }));
        entries.add(new Menu.Entry("Rename drawer button", new Runnable() {
            @Override
            public void run() {
                renameDockDrawer();
            }
        }));
        entries.add(new Menu.Entry("Change icon", new Runnable() {
            @Override
            public void run() {
                showDockIconChooser();
            }
        }));
        entries.add(new Menu.Entry("Choose image…", new Runnable() {
            @Override
            public void run() {
                pickMode = PICK_DOCK_ICON;
                pickTarget = null;
                launchImagePicker();
            }
        }));
        entries.add(new Menu.Entry("Reset icon", new Runnable() {
            @Override
            public void run() {
                clearDockIcon();
                populateDock();
                LToast.makeText(MainActivity.this, "Drawer icon reset",
                        Toast.LENGTH_SHORT).show();
            }
        }));
        entries.add(new Menu.Entry("Launcher settings", new Runnable() {
            @Override
            public void run() {
                showSettingsDialog(anchor);
            }
        }));
        dismissActiveMenu();
        activeMenu = Menu.show(this, captureBackdrop(false, 0), wallpaper.accent(),
                anchor, touchX, touchY, "App drawer", entries, false);
    }

    private void renameDockDrawer() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 18),
                Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Drawer button name");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        TextView hint = new LTextView(this);
        hint.setText("Shown under the app-drawer button in the dock.");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 9));
        panel.addView(hint);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.dockDrawerName());
        input.setSelectAllOnFocus(true);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 9),
                Ui.dp(this, 12), Ui.dp(this, 9));
        input.setBackground(Ui.rounded(0x22FFFFFF, Ui.dpf(this, 12),
                0x33FFFFFF, Ui.dpf(this, 1)));
        panel.addView(input);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView cancel = new LTextView(this);
        cancel.setText("Cancel");
        styleTextButton(cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(cancel);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(
                Ui.dp(this, 8), 1));
        TextView apply = new LTextView(this);
        apply.setText("Apply");
        styleTextButton(apply, true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setDockDrawerName(input.getText().toString());
                populateDock();
                dialog.dismiss();
            }
        });
        buttons.addView(apply);
        panel.addView(buttons);

        showGlassDialog(dialog, panel, backdrop, 430, 0f);
        dialog.show();
        input.requestFocus();
    }

    private void populateDock() {
        clearDrawerButtonFocusRing();
        float s = prefs.dockScale();
        boolean side = dock.getOrientation() == LinearLayout.VERTICAL;
        dock.removeAllViews();
        dockDrawerButton = null;

        int pinned = Math.min(store.dock.size(), prefs.dockCount());
        boolean showDrawer = prefs.dockDrawerButton();
        int buttonCount = showDrawer ? 1 : 0;
        int buttonIndex = showDrawer
                ? Math.max(0, Math.min(pinned, prefs.dockDrawerIndex())) : -1;
        int itemIconDp;
        int cellWidthDp;
        if (side) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int availableDp = Math.round(dm.heightPixels / dm.density) - 28;
            int slots = Math.max(1, pinned + buttonCount + 1);
            int perSlot = availableDp / slots;
            int base = Math.max(26, Math.min(32, Math.round(36 * s)));
            itemIconDp = Math.max(22, Math.min(base, perSlot - 23));
            cellWidthDp = Math.max(44, itemIconDp + 18);
        } else {
            itemIconDp = Math.max(28, Math.round(Math.min(prefs.iconDp(), 46) * s));
            cellWidthDp = Math.round(64 * s);
        }
        int cellWidthPx = Ui.dp(this, cellWidthDp);
        int iconPx = Ui.dp(this, itemIconDp);

        List<Item> items = store.dock;
        // The drawer button is a movable slot among the pinned apps: index N means
        // "after the first N dock apps". This loop renders apps and button together.
        for (int i = 0; i <= pinned; i++) {
            if (showDrawer && i == buttonIndex) {
                ImageButton all = new ImageButton(this);
                Bitmap drawerBmp = drawerButtonIcon();
                if (drawerBmp != null) {
                    all.setImageBitmap(drawerBmp);
                } else {
                    all.setImageResource(R.drawable.ic_apps);
                }
                all.setBackground(Ui.pressHighlight(this, Ui.dpf(this, 21 * s)));
                all.setContentDescription("Open app drawer; long-press and drag to reposition, "
                        + "long-press and release for options");
                all.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                int padPx = Ui.dp(this, Math.max(3, Math.round(5 * s)));
                all.setPadding(padPx, padPx, padPx, padPx);
                all.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openDrawer(prefs.drawerButtonPopup());
                    }
                });
                all.setOnTouchListener(new DockDrawerDrag(all));

                LinearLayout allBox = new LinearLayout(this);
                allBox.setOrientation(LinearLayout.VERTICAL);
                allBox.setGravity(Gravity.CENTER_HORIZONTAL);
                allBox.setClipChildren(false);
                allBox.setClipToPadding(false);
                allBox.setPadding(Ui.dp(this, 2), Ui.dp(this, 2),
                        Ui.dp(this, 2), Ui.dp(this, 3));
                int bs = iconPx + padPx * 2;
                allBox.addView(all, new LinearLayout.LayoutParams(bs, bs));
                all.setFocusable(true);
                all.setFocusableInTouchMode(true);
                all.setDefaultFocusHighlightEnabled(false);
                all.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View v, boolean focused) {
                        if (focused) {
                            Haptics.tick(all);
                            allBox.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateDrawerButtonFocusRing(allBox, all);
                                }
                            });
                        } else {
                            clearDrawerButtonFocusRing();
                        }
                    }
                });
                if (prefs.dockLabels()) {
                    TextView drawerName = new LTextView(this);
                    drawerName.setText(prefs.dockDrawerName());
                    drawerName.setTextColor(0xFFFFFFFF);
                    drawerName.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                            side ? 9f : Math.max(8f, Math.min(13f, 10f * s)));
                    drawerName.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    drawerName.setGravity(Gravity.CENTER);
                    drawerName.setSingleLine(true);
                    drawerName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    drawerName.setShadowLayer(Ui.dpf(this, 3), 0, Ui.dpf(this, 1),
                            0xB3000000);
                    drawerName.setPadding(0, Ui.dp(this, 3), 0, 0);
                    drawerName.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openDrawer(prefs.drawerButtonPopup());
                        }
                    });
                    drawerName.setFocusable(false);
                    drawerName.setFocusableInTouchMode(false);
                    allBox.addView(drawerName, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        cellWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
                alp.gravity = side ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL;
                dock.addView(allBox, alp);
                dockDrawerButton = all;
            }

            if (i < pinned) {
                Item it = items.get(i);
                ItemView v = makeItemView(it, false, true);
                v.setIconDp(itemIconDp);
                v.setFocusScale(1f);
                v.setLabelSp(side ? 9f : Math.max(8f, Math.min(13f, 10f * s)));
                v.setLabelVisible(prefs.dockLabels());
                v.dockIndex = i;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        cellWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (side) lp.gravity = Gravity.CENTER_HORIZONTAL;
                dock.addView(v, lp);
            }
        }

        applyDockSize();
        dock.requestLayout();
        dock.invalidate();
        if (animateDock) {
            animateDock = false;
            dock.setAlpha(0.55f);
            dock.setScaleX(0.97f);
            dock.setScaleY(0.97f);
            dock.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    /** Index among the pinned apps for a drawer-button drop, or -1 outside the dock. */
    private int dockDrawerSlotAt(float rawX, float rawY) {
        if (dock == null || dock.getVisibility() != View.VISIBLE) return -1;
        int[] dl = new int[2];
        dock.getLocationOnScreen(dl);
        int pad = Ui.dp(this, 18);
        if (rawX < dl[0] - pad || rawX > dl[0] + dock.getWidth() + pad
                || rawY < dl[1] - pad || rawY > dl[1] + dock.getHeight() + pad) {
            return -1;
        }
        boolean vertical = dock.getOrientation() == LinearLayout.VERTICAL;
        float coord = vertical ? rawY - dl[1] : rawX - dl[0];
        int index = 0;
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            if (!(c instanceof ItemView)) continue;
            float center = vertical
                    ? c.getTop() + c.getHeight() / 2f
                    : c.getLeft() + c.getWidth() / 2f;
            if (coord >= center) index = ((ItemView) c).dockIndex + 1;
        }
        int pinned = Math.min(store.dock.size(), prefs.dockCount());
        return Math.max(0, Math.min(pinned, index));
    }

    /** Screen-space centre of the drawer button; null when it has no usable position. */
    private int[] dockDrawerButtonCentre() {
        if (dock == null || dock.getVisibility() != View.VISIBLE
                || dockDrawerButton == null
                || dockDrawerButton.getWidth() <= 0 || dockDrawerButton.getHeight() <= 0) {
            return null;
        }
        int[] loc = new int[2];
        dockDrawerButton.getLocationOnScreen(loc);
        return new int[]{loc[0] + dockDrawerButton.getWidth() / 2,
                loc[1] + dockDrawerButton.getHeight() / 2};
    }

    /**
     * Long-press the dock's drawer button to pick it up. Drop it between any two
     * pinned apps to change its slot; a long-press without dragging opens the
     * existing dock menu, and a short tap opens the drawer.
     */
    private final class DockDrawerDrag implements View.OnTouchListener {
        private final ImageButton button;
        private final int slop;
        private float downX, downY, lastX, lastY;
        private boolean longFired, dragging, moved;
        private ImageView ghost;
        private int ghostSize;
        private int targetIndex = -1;

        private final Runnable longPress = new Runnable() {
            @Override
            public void run() {
                if (button.getParent() == null || root == null) return;
                longFired = true;
                dockDrawerDragging = true;
                Haptics.longPress(button);
                button.setPressed(false);
                button.animate().scaleX(1.12f).scaleY(1.12f).alpha(0.95f)
                        .setDuration(120).start();
            }
        };

        DockDrawerDrag(ImageButton button) {
            this.button = button;
            slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        }

        private void startGhost(float rawX, float rawY) {
            if (ghost != null || root == null) return;
            ghostSize = Math.max(Ui.dp(MainActivity.this, 48), button.getWidth());
            ghost = new ImageView(MainActivity.this);
            ghost.setImageDrawable(button.getDrawable());
            ghost.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            ghost.setAlpha(0.98f);
            ghost.setElevation(Ui.dpf(MainActivity.this, 28));
            ghost.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            ghost.setScaleX(0.9f);
            ghost.setScaleY(0.9f);
            root.addView(ghost, new FrameLayout.LayoutParams(ghostSize, ghostSize));
            ghost.animate().scaleX(1.08f).scaleY(1.08f)
                    .setDuration(120)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
            moveGhost(rawX, rawY);
            button.animate().alpha(0.22f).setDuration(120).start();
        }

        private void moveGhost(float rawX, float rawY) {
            if (ghost == null || root == null) return;
            int[] rl = new int[2];
            root.getLocationOnScreen(rl);
            ghost.animate().cancel();
            ghost.setScaleX(1.08f);
            ghost.setScaleY(1.08f);
            ghost.setX(rawX - rl[0] - ghostSize / 2f);
            ghost.setY(rawY - rl[1] - ghostSize / 2f);
        }

        private void resetButtonVisual() {
            button.setPressed(false);
            button.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(120).start();
        }

        private void finishDrag(boolean cancel) {
            handler.removeCallbacks(longPress);
            if (dock != null) dock.setDropIndex(-1);
            if (ghost != null) {
                if (ghost.getParent() instanceof ViewGroup) {
                    ((ViewGroup) ghost.getParent()).removeView(ghost);
                }
                ghost = null;
            }
            boolean wasDragging = dragging;
            int target = targetIndex;
            dragging = false;
            longFired = false;
            dockDrawerDragging = false;
            moved = false;
            targetIndex = -1;
            resetButtonVisual();

            if (!wasDragging || cancel || target < 0) return;
            // Index 8 is the "always at the end" slot. Saving that for a drop at
            // the end keeps the button after any apps added to the dock later.
            int pinned = Math.min(store.dock.size(), prefs.dockCount());
            int desired = target >= pinned ? 8 : target;
            if (desired != prefs.dockDrawerIndex()) {
                prefs.setDockDrawerIndex(desired);
                populateDock();
            }
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    handler.removeCallbacks(longPress);
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    longFired = false;
                    dragging = false;
                    moved = false;
                    targetIndex = -1;
                    dockDrawerDragging = true;
                    button.setPressed(true);
                    handler.postDelayed(longPress, 520);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    float dx = lastX - downX;
                    float dy = lastY - downY;
                    if (!longFired) {
                        if (Math.hypot(dx, dy) > slop) {
                            handler.removeCallbacks(longPress);
                            moved = true;
                            button.setPressed(false);
                        }
                        return true;
                    }
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        moved = true;
                        dismissActiveMenu();
                        startGhost(lastX, lastY);
                    }
                    if (!dragging) return true;
                    moveGhost(lastX, lastY);
                    int idx = dockDrawerSlotAt(lastX, lastY);
                    if (idx >= 0) {
                        targetIndex = idx;
                        dock.setDropIndex(idx);
                    } else {
                        targetIndex = -1;
                        dock.setDropIndex(-1);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    handler.removeCallbacks(longPress);
                    boolean wasDragging = dragging;
                    boolean wasLong = longFired;
                    boolean wasMoved = moved;
                    if (wasDragging) {
                        finishDrag(false);
                    } else {
                        resetButtonVisual();
                        dockDrawerDragging = false;
                        longFired = false;
                        moved = false;
                        if (!wasLong && !wasMoved) button.performClick();
                        else if (wasLong && !wasMoved) showDockMenu(button, lastX, lastY);
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    dismissActiveMenu();
                    finishDrag(true);
                    return true;

                default:
                    return false;
            }
        }
    }

    /** Center, side-align, or hide the dock and reflow the grid around it. */
    private void applyDockPosition() {
        if (dock == null) return;
        int pos = prefs.dockPosition();
        boolean side = pos == Prefs.DOCK_LEFT || pos == Prefs.DOCK_RIGHT;
        ViewGroup oldParent = (ViewGroup) dock.getParent();
        if (oldParent != null) oldParent.removeView(dock);

        if (pos == Prefs.DOCK_HIDDEN) {
            dock.setVisibility(View.GONE);
            applyContentInsets(false, pos);
            if (pager != null) {
                pager.post(new Runnable() {
                    @Override
                    public void run() {
                        renderHome();
                    }
                });
            }
            return;
        }

        dock.setVisibility(View.VISIBLE);
        dock.setOrientation(side ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        if (side) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    (pos == Prefs.DOCK_LEFT ? Gravity.START : Gravity.END)
                            | Gravity.CENTER_VERTICAL);
            int margin = Ui.dp(this, 12);
            if (pos == Prefs.DOCK_LEFT) lp.leftMargin = margin;
            else lp.rightMargin = margin;
            int index = drawer != null ? root.indexOfChild(drawer) : root.getChildCount();
            if (index < 0) index = root.getChildCount();
            root.addView(dock, index, lp);
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, Math.round(94 * prefs.dockScale())));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = Ui.dp(this, 2);
            content.addView(dock, lp);
        }

        applyContentInsets(side, pos);
        applyDockSize();
        populateDock();
        if (pager != null) {
            pager.post(new Runnable() {
                @Override
                public void run() {
                    renderHome();
                }
            });
        }
        handler.postDelayed(dockBackdropUpdate, 60);
    }

    /** Keep the home content clear of a vertical side dock. */
    private void applyContentInsets(boolean side, int pos) {
        if (content == null) return;
        int horizontal = Ui.dp(this, 30);
        int sideInset = side ? Ui.dp(this, 78) : 0;
        int left = horizontal + (side && pos == Prefs.DOCK_LEFT ? sideInset : 0);
        int right = horizontal + (side && pos == Prefs.DOCK_RIGHT ? sideInset : 0);
        content.setPadding(left, Ui.dp(this, 14), right, Ui.dp(this, 10));
    }

    private void applyDockSize() {
        if (dock == null) return;
        float s = prefs.dockScale();
        boolean side = prefs.dockPosition() == Prefs.DOCK_LEFT
                || prefs.dockPosition() == Prefs.DOCK_RIGHT;
        if (side) {
            int pad = Ui.dp(this, Math.max(3, Math.round(5 * s)));
            dock.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8));
            return;
        }
        ViewGroup.LayoutParams raw = dock.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.height = Ui.dp(this, Math.round(94 * s));
            dock.setLayoutParams(lp);
        }
        dock.setPadding(Ui.dp(this, Math.round(16 * s)), Ui.dp(this, Math.round(8 * s)),
                Ui.dp(this, Math.round(16 * s)), Ui.dp(this, Math.round(6 * s)));
    }

    private void updateDots(int current, int count) {
        if (dots == null) return;
        // onPageChanged fires every frame during a slide. Rebuilding the dot
        // views at that rate was the main source of jitter; only touch them
        // when the selected page or the page count actually changes.
        if (current == lastDotsPage && count == lastDotsCount) return;
        lastDotsPage = current;
        lastDotsCount = count;
        dots.removeAllViews();
        // A single page needs no indicator; only show dots when paging is possible.
        if (count <= 1) {
            handler.removeCallbacks(hidePageDots);
            dots.animate().cancel();
            dots.setAlpha(1f);
            return;
        }
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, i == current ? 8 : 6), Ui.dp(this, i == current ? 8 : 6));
            lp.leftMargin = Ui.dp(this, 4);
            lp.rightMargin = Ui.dp(this, 4);
            lp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(lp);
            int dotAccent = wallpaper != null ? wallpaper.accent() : ThemeColors.accent();
            dot.setBackground(Ui.rounded(i == current
                            ? ColorTuning.withAlpha(dotAccent, 0xF2)
                            : ColorTuning.withAlpha(dotAccent, 0x59),
                    Ui.dpf(this, 5)));
            dots.addView(dot);
        }
        revealPageDots();
    }

    /**
     * The page dots stay visible while pages are being swiped/changed, then fade out
     * shortly afterwards. Turning the setting off pins them fully visible.
     */
    private void revealPageDots() {
        if (dots == null) return;
        handler.removeCallbacks(hidePageDots);
        dots.animate().cancel();
        dots.setAlpha(1f);
        if (prefs.autoHidePageDots()) {
            handler.postDelayed(hidePageDots, PAGE_DOTS_HIDE_MS);
        }
    }

    private ItemView makeItemView(Item item, boolean drawer, boolean dragEnabled) {
        ItemView v = new ItemView(this);
        v.setMarqueeEnabled(true);
        v.setClipChildren(true);
        v.setIconDp(prefs.iconDp());
        v.dragEnabled = dragEnabled && !drawer;
        boolean show = drawer ? prefs.drawerLabels() : prefs.homeLabels();

        if (item.type == Item.TYPE_FOLDER) {
            List<Apps.Entry> kids = new ArrayList<>();
            for (Item c : item.children) {
                if (c.type != Item.TYPE_APP) continue;
                Apps.Entry ce = findEntry(c.pkg, c.cls);
                if (ce == null) ce = Apps.entryFor(this, c.pkg, c.cls);
                Bitmap themed = iconPack == null ? null : iconPack.iconFor(c.pkg, c.cls);
                if (themed != null) {
                    Apps.Entry copy = new Apps.Entry(ce.label, ce.pkg, ce.activity);
                    copy.icon = themed;
                    kids.add(copy);
                } else {
                    kids.add(ce);
                }
            }
            Bitmap folderBmp = Apps.folderIcon(kids,
                    Math.max(1, Ui.dp(this, prefs.iconDp())), wallpaper.accent(),
                    prefs.folderTransparency() / 100f);
            String folderLabel = store.labelFor(item,
                    item.title != null && !item.title.isEmpty() ? item.title : "Folder");
            v.bind(item, null, folderBmp, folderLabel, show);
            v.listener = itemListener;
            return v;
        }

        if (item.type == Item.TYPE_APP && isPerfLogs(item.pkg)) {
            Apps.Entry pe = perfLogsEntry();
            String label = store.labelFor(item, pe.label);
            Bitmap custom = store.iconFor(item);
            v.bind(item, pe, custom, label, show);
            v.listener = itemListener;
            return v;
        }

        Apps.Entry e = item.type == Item.TYPE_APP ? findEntry(item.pkg, item.cls) : null;
        if (item.type == Item.TYPE_APP && e == null) {
            e = Apps.entryFor(this, item.pkg, item.cls);
        }
        String fallback = item.type == Item.TYPE_WEB ? item.title : (e != null ? e.label : item.pkg);
        String label = store.labelFor(item, fallback);
        Bitmap custom = store.iconFor(item);
        if (custom == null && item.type == Item.TYPE_APP && iconPack != null) {
            custom = iconPack.iconFor(item.pkg, item.cls);
        }
        v.bind(item, e, custom, label, show);
        v.listener = itemListener;
        return v;
    }

    /** Web links open directly; shortcut intents keep their original action. */
    private static Intent parseWebIntent(String url) {
        if (url != null && url.startsWith("intent:")) {
            try {
                Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (parsed != null) return parsed;
            } catch (Throwable ignored) {
            }
        }
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private void startLongPressRipple(ItemView v) {
        if (v == null) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = loc[0] + v.getWidth() / 2f;
        float y = loc[1] + v.getHeight() / 2f;
        startHomeRipple(x, y, false);
        // Drawer and folder grids live above the home pager, so add their
        // visible icons to the same wave when the hold came from one of them.
        ViewGroup grid = v.getParent() instanceof ViewGroup
                ? (ViewGroup) v.getParent() : null;
        if (grid == drawerGrid || grid == folderGrid) {
            addGridRippleTargets(grid, x, y);
        }
    }

    private void addGridRippleTargets(ViewGroup grid, float x, float y) {
        if (grid == null) return;
        float radius = Ui.dpf(this, 900);
        float amp = Ui.dpf(this, 24);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (child instanceof ItemView) {
                addRippleTarget(child, x, y, radius, amp);
            }
        }
    }

    private void startHomeRipple(float x, float y, boolean strong) {
        cancelHomeRipple();
        homeRippleX = x;
        homeRippleY = y;
        homeRippleStrong = strong;
        homeRippleProgress = 0f;
        homeRippleActive = true;
        collectHomeRippleTargets();
        if (homeRippleTargets.isEmpty()) {
            homeRippleActive = false;
            return;
        }
        homeRippleAnimator = ValueAnimator.ofFloat(0f, 1f);
        homeRippleAnimator.setDuration(strong ? 880L : 650L);
        homeRippleAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        homeRippleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                homeRippleProgress = (Float) a.getAnimatedValue();
                applyHomeRipple(homeRippleProgress);
            }
        });
        homeRippleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                resetHomeRippleTargets();
                homeRippleAnimator = null;
                homeRippleActive = false;
                homeRippleProgress = 0f;
            }
        });
        homeRippleAnimator.start();
    }

    private void cancelHomeRipple() {
        if (homeRippleAnimator != null) {
            homeRippleAnimator.cancel();
            homeRippleAnimator = null;
        }
        homeRippleActive = false;
        homeRippleProgress = 0f;
        resetHomeRippleTargets();
    }

    private void resetHomeRippleTargets() {
        for (WaterTarget t : homeRippleTargets) {
            if (t.view != null) {
                t.view.setTranslationX(0f);
                t.view.setTranslationY(0f);
            }
        }
        homeRippleTargets.clear();
    }

    private void collectHomeRippleTargets() {
        homeRippleTargets.clear();
        if (root == null || root.getWidth() <= 0) return;
        float radius = Ui.dpf(this, homeRippleStrong ? 1400 : 900);
        float amp = Ui.dpf(this, homeRippleStrong ? 42 : 24);
        for (int p = 0; p < pager.getChildCount(); p++) {
            View pv = pager.getChildAt(p);
            if (!(pv instanceof GridPage)) continue;
            GridPage gp = (GridPage) pv;
            for (ItemView v : gp.cellViews()) {
                addRippleTarget(v, homeRippleX, homeRippleY, radius, amp);
            }
            for (View wv : gp.widgetViews()) {
                addRippleTarget(wv, homeRippleX, homeRippleY, radius,
                        amp * (homeRippleStrong ? 0.65f : 0.50f));
            }
        }
        for (AppWidgetHostView hv : widgetViews.values()) {
            addRippleTarget(hv, homeRippleX, homeRippleY, radius,
                    amp * (homeRippleStrong ? 0.65f : 0.50f));
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            addRippleTarget(dock.getChildAt(i), homeRippleX, homeRippleY, radius, amp);
        }
    }

    /** Apply the same in-flight wave phase to the newly rendered page views. */
    private void reattachHomeRipple() {
        if (!homeRippleActive) return;
        resetHomeRippleTargets();
        collectHomeRippleTargets();
        applyHomeRipple(homeRippleProgress);
    }

    private void applyHomeRipple(float t) {
        for (WaterTarget target : homeRippleTargets) {
            if (target.view == null || target.view.getParent() == null) continue;
            float local = (t - target.delay) / Math.max(0.01f, 1f - target.delay);
            local = Math.max(0f, Math.min(1f, local));
            if (local <= 0f || local >= 1f) {
                target.view.setTranslationX(0f);
                target.view.setTranslationY(0f);
                continue;
            }

            // A fast outward wavefront, followed by a smaller secondary wave
            // and a tangential swirl. Everything decays to exactly zero.
            float attack = 1f - (float) Math.exp(-local * 26f);
            float decay = (float) Math.exp(-local * 2.9f) * (1f - local);
            float primary = (float) Math.sin(Math.PI * 2f * 1.38f * local);
            float secondary = (float) Math.sin(Math.PI * 2f * 2.55f * local + 0.65f);
            float wave = (primary * 0.92f + secondary * 0.18f) * attack * decay;
            float swirlWave = (float) Math.sin(Math.PI * 2f * 1.38f * local + Math.PI * 0.5f)
                    * attack * decay;

            float radial = wave * target.amp;
            float swirl = swirlWave * target.amp * 0.30f;
            target.view.setTranslationX(target.ux * radial + target.tx * swirl);
            target.view.setTranslationY(target.uy * radial + target.ty * swirl);
        }
    }

    private void addRippleTarget(View v, float x, float y, float radius, float amp) {
        if (v == null || !v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return;
        // Include edge icons even if a hand-off animation has already put a
        // small translation on them; the wave owns the translation while it
        // runs and resets it at the end.
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float vx = loc[0] + v.getWidth() / 2f;
        float vy = loc[1] + v.getHeight() / 2f;
        float dx = vx - x;
        float dy = vy - y;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < Ui.dp(this, 55) || dist > radius || dist < 1f) return;
        float falloff = 1f - dist / radius;
        float ux = dx / dist;
        float uy = dy / dist;
        WaterTarget target = new WaterTarget();
        target.view = v;
        target.ux = ux;
        target.uy = uy;
        target.tx = -uy;
        target.ty = ux;
        target.falloff = falloff;
        target.amp = amp * (0.30f + 0.70f * falloff);
        target.delay = (homeRippleStrong ? 0.28f : 0.22f) * (dist / radius);
        homeRippleTargets.add(target);
    }

    private void captureHomePositions() {
        dropOldItemRects = new HashMap<>();
        for (int p = 0; p < pager.getChildCount(); p++) {
            View pv = pager.getChildAt(p);
            if (!(pv instanceof GridPage)) continue;
            GridPage gp = (GridPage) pv;
            for (int i = 0; i < gp.cellViews().size(); i++) {
                ItemView v = gp.viewAt(i);
                if (v == null || v.item == null) continue;
                RectF r = new RectF();
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                r.set(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
                List<RectF> list = dropOldItemRects.get(v.item.key());
                if (list == null) {
                    list = new ArrayList<>();
                    dropOldItemRects.put(v.item.key(), list);
                }
                list.add(r);
            }
        }
        dropOldWidgetRects = new HashMap<>();
        for (Map.Entry<Integer, AppWidgetHostView> e : widgetViews.entrySet()) {
            AppWidgetHostView hv = e.getValue();
            if (hv == null || hv.getWidth() <= 0 || hv.getHeight() <= 0) continue;
            int[] loc = new int[2];
            hv.getLocationOnScreen(loc);
            dropOldWidgetRects.put(e.getKey(),
                    new RectF(loc[0], loc[1], loc[0] + hv.getWidth(), loc[1] + hv.getHeight()));
        }
    }

    private void animateDropLayout() {
        if (pager == null) return;
        Map<String, List<RectF>> oldItems = dropOldItemRects;
        Map<Integer, RectF> oldWidgets = dropOldWidgetRects;
        dropOldItemRects = null;
        dropOldWidgetRects = null;
        if (oldItems != null) {
            for (int p = 0; p < pager.getChildCount(); p++) {
                View pv = pager.getChildAt(p);
                if (!(pv instanceof GridPage)) continue;
                GridPage gp = (GridPage) pv;
                for (int i = 0; i < gp.cellViews().size(); i++) {
                    ItemView v = gp.viewAt(i);
                    if (v == null || v.item == null) continue;
                    String key = v.item.key();
                    boolean isMoved = key.equals(dropMovedKey) && p == dropMovedPage
                            && i == dropMovedCell;
                    boolean isPreview = key.equals(dropPreviewKey) && p == dropPreviewPage
                            && i == dropPreviewCell;
                    List<RectF> list = oldItems.get(key);
                    RectF old = null;
                    if (!isMoved && !isPreview && list != null && !list.isEmpty()) {
                        old = list.remove(0);
                    }
                    if (isMoved) {
                        // The drag ghost has already landed at this exact size
                        // and position. Leave the replacement fully opaque so
                        // there is no one-frame blink between ghost and icon.
                        v.setScaleX(1f);
                        v.setScaleY(1f);
                        v.setAlpha(1f);
                    } else if (isPreview) {
                        pulseIn(v, 0.96f, 1f);
                    } else if (old != null) {
                        animateViewFrom(v, old, 0.96f);
                    } else if (list == null) {
                        pulseIn(v, 0.92f, 1f);
                    }
                }
            }
        }
        if (oldWidgets != null) {
            for (Store.PlacedWidget w : store.widgets) {
                AppWidgetHostView hv = widgetViews.get(w.appWidgetId);
                if (hv == null || hv.getWidth() <= 0) continue;
                RectF old = oldWidgets.get(w.appWidgetId);
                if (old != null) animateViewFrom(hv, old, 1f);
            }
        }
        dropMovedKey = null;
        dropPreviewKey = null;
        dropMovedPage = -1;
        dropMovedCell = -1;
        dropPreviewPage = -1;
        dropPreviewCell = -1;
        reattachHomeRipple();
    }

    private void animateViewFrom(View v, RectF old, float startScale) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float dx = old.left - loc[0];
        float dy = old.top - loc[1];
        if (Math.abs(dx) < 1f && Math.abs(dy) < 1f) return;
        v.setTranslationX(dx);
        v.setTranslationY(dy);
        v.setScaleX(startScale);
        v.setScaleY(startScale);
        v.setTranslationZ(Ui.dpf(this, 10));
        final View fv = v;
        v.animate().translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .setDuration(275)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        fv.setTranslationZ(0f);
                    }
                }).start();
    }

    private void pulseIn(View v, float startScale, float startAlpha) {
        // A shallow droplet squash instead of a uniform bounce.
        v.setScaleX(startScale * 1.04f);
        v.setScaleY(startScale * 0.96f);
        v.setAlpha(startAlpha);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(245)
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .start();
    }

    private void refreshVisible() {
        renderHome();
        populateDock();
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
        if (dropTransitionPending) {
            dropTransitionPending = false;
            final ViewTreeObserver observer = pager.getViewTreeObserver();
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    animateDropLayout();
                    return true;
                }
            });
        }
        if (pendingDropRipple) {
            final float rx = pendingDropRippleX;
            final float ry = pendingDropRippleY;
            schedulePendingDropRipple(rx, ry, 0);
        }
    }

    private void schedulePendingDropRipple(final float x, final float y,
                                           final int attempt) {
        if (!pendingDropRipple) return;
        pager.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!pendingDropRipple) return;
                pendingDropRipple = false;
                startHomeRipple(x, y, true);
                if (!homeRippleActive && attempt < 3) {
                    pendingDropRipple = true;
                    schedulePendingDropRipple(x, y, attempt + 1);
                }
            }
        }, 16L);
    }

    // ------------------------------------------------------------------
    // Item interaction
    // ------------------------------------------------------------------

    private final ItemView.Listener itemListener = new ItemView.Listener() {
        @Override
        public void onItemClick(ItemView v) {
            if (v.item == null) return;
            if (v.item.type == Item.TYPE_FOLDER) {
                openFolder(v.item, v);
            } else {
                launch(v.item, v);
            }
        }

        @Override
        public void onItemLongPress(ItemView v) {
            if (v.item == null) return;
            if (!v.dragEnabled) return;
            if (dragSource != null && dragSource != v) dragSource.setLifted(false);
            dragSource = v;
            dragSrcKind = v.dockIndex >= 0 ? SRC_DOCK : SRC_HOME;
            dragSrcPage = pageOf(v);
            dragSrcCell = v.cellIndex;
            dragSrcDock = v.dockIndex;
            v.setLifted(true);
            startLongPressRipple(v);
            // Opening the menu only on release keeps the PopupWindow from stealing
            // the first move of a drag.
        }

        @Override
        public void onItemDragStart(ItemView v) {
            beginDrag(v);
            dismissActiveMenu();
        }

        @Override
        public void onItemDragMove(ItemView v, float rawX, float rawY) {
            moveDrag(rawX, rawY);
        }

        @Override
        public void onItemRelease(ItemView v, float rawX, float rawY, boolean dragged,
                                  boolean longFired) {
            if (dragged) {
                endDrag(rawX, rawY);
            } else {
                if (dragSource == v) {
                    v.setLifted(false);
                    resetDragSource();
                }
                if (longFired) showItemMenu(v, false);
            }
        }

        @Override
        public void onItemDragCancel(ItemView v) {
            cancelDrag();
        }
    };

    private int pageOf(ItemView v) {
        View parent = (View) v.getParent();
        if (parent instanceof GridPage) {
            Object tag = ((GridPage) parent).getTag();
            if (tag instanceof Integer) return (Integer) tag;
            for (int i = 0; i < pager.getChildCount(); i++) {
                if (pager.getChildAt(i) == parent) return i;
            }
        }
        return -1;
    }

    private void resetDragSource() {
        if (dragSource != null) dragSource.setLifted(false);
        resetEdgeAutoScroll();
        dragSource = null;
        dragSrcKind = SRC_NONE;
        dragSrcPage = -1;
        dragSrcCell = -1;
        dragSrcDock = -1;
        dragFolder = null;
        dragChild = null;
        dragSparePage = false;
        dragSwitchPage = -1;
        dragSourceNormalBounds.setEmpty();
        dragSourceCardBounds.setEmpty();
    }

    /** Start an inbuilt app without the platform's activity zoom; its glass window
     *  appears immediately and the close animation is handled by PavoGlassActivity.
     *
     *  Each inbuilt app has its own taskAffinity in the manifest. NEW_TASK makes the
     *  system launch it into that separate task instead of the home task. Sharing the
     *  home task made the swipe-up Home gesture move the whole task to the back (bare
     *  wallpaper), then bring it forward again (app flash) before MainActivity showed. */
    private void startBuiltIn(Intent intent) {
        // Hand a blurred home frame to the app card, so only the card is frosted;
        // the window itself is fully transparent and neither dims nor blurs the
        // screen around it.
        // Capture the home layer itself, not the whole decor. The app drawer is
        // a sibling overlay; drawing the decor while it is closing would bake the
        // drawer into the built-in card and make the two launch paths differ.
        Backdrop launchBackdrop = null;
        if (homeLayer != null && homeLayer.getWidth() > 0 && homeLayer.getHeight() > 0) {
            launchBackdrop = Backdrop.capture(homeLayer, 0);
        } else if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            launchBackdrop = captureBackdrop(false, 0);
        }
        if (launchBackdrop != null) {
            BuiltInBackdrop.put(launchBackdrop);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void startBuiltIn(Intent intent, ItemView source) {
        if (source != null) {
            source.iconScreenRect(launchReturnRect);
            hasLaunchReturnRect = launchReturnRect.width() > 1
                    && launchReturnRect.height() > 1;
        }
        if (source != null && hasLaunchReturnRect) {
            intent.putExtra(PavoGlassActivity.EXTRA_RETURN_LEFT,
                    Math.round(launchReturnRect.left));
            intent.putExtra(PavoGlassActivity.EXTRA_RETURN_TOP,
                    Math.round(launchReturnRect.top));
            intent.putExtra(PavoGlassActivity.EXTRA_RETURN_RIGHT,
                    Math.round(launchReturnRect.right));
            intent.putExtra(PavoGlassActivity.EXTRA_RETURN_BOTTOM,
                    Math.round(launchReturnRect.bottom));
        }
        startBuiltIn(intent);
    }

    private void launch(final Item item, final ItemView source) {
        Haptics.launch(source != null ? source : root);
        animateOnResume = true;
        launchSource = source;
        hasLaunchReturnRect = false;
        if (source != null) {
            source.iconScreenRect(launchReturnRect);
            hasLaunchReturnRect = launchReturnRect.width() > 1
                    && launchReturnRect.height() > 1;
        }

        // Built-in apps own their window, so they can expand the real card out of
        // the icon and shrink it back on Back / swipe-home, matching the reference
        // window-restore motion instead of a full-screen icon tile.
        if (source != null) {
            source.animate().cancel();
            source.setScaleX(1f);
            source.setScaleY(1f);
            source.setAlpha(1f);
        }
        performLaunch(item, source, true);
    }

    private void performLaunch(Item item, ItemView source, boolean fromLamp) {
        if (item.type == Item.TYPE_WEB && item.url != null
                && item.url.startsWith("shortcut://")) {
            if (launchPinnedShortcut(item, source)) return;
        }
        if (isPerfLogs(item.pkg)) {
            startBuiltIn(new Intent(this, PerfLogActivity.class), source);
            return;
        }
        if (CONTROLLER_MAP_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, ControllerMapActivity.class), source);
            return;
        }
        if (PERF_TUNER_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, PerformanceTunerActivity.class), source);
            return;
        }
        if (DISPLAY_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, DisplayActivity.class), source);
            return;
        }
        if (RGB_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, RgbActivity.class), source);
            return;
        }
        if (FAN_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, FanActivity.class), source);
            return;
        }
        if (OVERLAY_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, OverlayActivity.class), source);
            return;
        }
        if (TOUCH_MAP_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, TouchMapperActivity.class), source);
            return;
        }
        if (DEVICE_HEALTH_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, DeviceHealthActivity.class), source);
            return;
        }
        if (BENCHMARK_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, BenchmarkActivity.class), source);
            return;
        }
        if (FILE_MANAGER_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, FileManagerActivity.class), source);
            return;
        }
        if (SCREEN_RECORDER_PKG.equals(item.pkg)) {
            startBuiltIn(new Intent(this, ScreenRecorderActivity.class), source);
            return;
        }
        Intent intent;
        if (item.type == Item.TYPE_WEB) {
            intent = parseWebIntent(item.url);
        } else {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(item.pkg, item.cls));
            intent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Bundle opts = null;
        if (!fromLamp && source != null) {
            try {
                content.animate()
                        .scaleX(1.025f).scaleY(1.025f).alpha(0.82f)
                        .setDuration(150)
                        .start();
                opts = ActivityOptions.makeScaleUpAnimation(source, 0, 0,
                        Math.max(1, source.getWidth()), Math.max(1, source.getHeight())).toBundle();
            } catch (Throwable ignored) {
            }
        }

        try {
            startActivity(intent, opts);
            if (fromLamp) overridePendingTransition(0, 0);
        } catch (Throwable t) {
            try {
                startActivity(intent);
                if (fromLamp) overridePendingTransition(0, 0);
            } catch (Throwable ignored) {
                LToast.makeText(this, "Could not open " + (item.type == Item.TYPE_WEB
                        ? item.url : item.pkg), Toast.LENGTH_SHORT).show();
            }
            restoreLaunchVisuals(source);
        }

        // If nothing actually launched, put the icon back.
        if (source != null) {
            final ItemView src = source;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (hasWindowFocus()) restoreLaunchVisuals(src);
                }
            }, 1100);
        }
    }

    private void restoreLaunchVisuals(ItemView source) {
        if (source != null) {
            source.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(180)
                    .start();
            if (launchSource == source) launchSource = null;
        }
        if (content != null) {
            content.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200)
                    .start();
        }
    }

    // ------------------------------------------------------------------
    // Drag and drop
    // ------------------------------------------------------------------

    private void beginDrag(ItemView v) {
        Haptics.pickup(v);
        dragging = true;
        pager.setLocked(true);
        resetEdgeAutoScroll();
        lastAutoScroll = 0;
        dragPeek = false;
        dragPeekCommitted = false;
        dragEdgeLatched = false;
        dragEdgeDir = 0;
        dragEdgePendingDir = 0;
        dragSwitchPage = -1;
        dropWidget = null;
        dropWidgetDestCell = -1;
        pendingDropRipple = false;
        cancelHomeRipple();
        clearHoverPreview(false);
        handler.removeCallbacks(dragEdgeSwitch);
        snapHomeVisualsForDrag();
        dragSourceNormalBounds.setEmpty();
        dragSourceCardBounds.setEmpty();
        if (dragSrcKind == SRC_HOME && dragSrcPage >= 0 && dragSrcCell >= 0) {
            RectF bounds = cellScreenBounds(dragSrcPage, dragSrcCell);
            if (bounds != null) dragSourceNormalBounds.set(bounds);
            else if (dragSource != null) {
                int[] sourceLoc = new int[2];
                dragSource.getLocationOnScreen(sourceLoc);
                dragSourceNormalBounds.set(sourceLoc[0], sourceLoc[1],
                        sourceLoc[0] + dragSource.getWidth(),
                        sourceLoc[1] + dragSource.getHeight());
            }
        }
        pager.setCardMode(true);
        if (dragSource != null) {
            // The real icon is now the one under the finger. Hide the source
            // immediately rather than leaving a faded copy/mask in the cell.
            dragSource.animate().cancel();
            dragSource.setScaleX(1f);
            dragSource.setScaleY(1f);
            dragSource.setAlpha(0f);
            if (dragSource.isFocused()) dragSource.clearFocus();
        }

        int size = Math.max(Ui.dp(this, 48), v.iconSizePx() + Ui.dp(this, 10));
        dragBitmap = v.iconBitmap();
        dragView = new ImageView(this);
        if (dragBitmap != null) dragView.setImageBitmap(dragBitmap);
        dragView.setAlpha(0.98f);
        dragView.setElevation(Ui.dpf(this, 28));
        dragView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // Start at the source icon's size and lift from there; starting small
        // made the first drag frame look like a snap.
        dragView.setScaleX(1f);
        dragView.setScaleY(1f);

        int[] sl = new int[2];
        v.getLocationOnScreen(sl);
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        dragView.setX(sl[0] + v.getWidth() / 2f - rl[0] - size / 2f);
        dragView.setY(sl[1] + v.getHeight() / 2f - rl[1] - size / 2f);
        root.addView(dragView, new FrameLayout.LayoutParams(size, size));
        dragView.animate().scaleX(1.08f).scaleY(1.08f)
                .setDuration(120)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();

        ensureDragSparePage();
        ensureTrash();
        ensurePageTurnHints();
    }

    /** Snap any finishing layout/ripple animation before measuring a new drag. */
    private void snapHomeVisualsForDrag() {
        for (int p = 0; p < pager.getChildCount(); p++) {
            View pv = pager.getChildAt(p);
            if (!(pv instanceof GridPage)) continue;
            GridPage gp = (GridPage) pv;
            for (ItemView v : gp.cellViews()) {
                if (v == null) continue;
                v.animate().cancel();
                v.setTranslationX(0f);
                v.setTranslationY(0f);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setTranslationZ(0f);
                v.setAlpha(1f);
            }
            for (View wv : gp.widgetViews()) {
                if (wv == null) continue;
                wv.animate().cancel();
                wv.setTranslationX(0f);
                wv.setTranslationY(0f);
                wv.setScaleX(1f);
                wv.setScaleY(1f);
                wv.setTranslationZ(0f);
                wv.setAlpha(1f);
            }
        }
        for (AppWidgetHostView hv : widgetViews.values()) {
            if (hv == null) continue;
            hv.animate().cancel();
            hv.setTranslationX(0f);
            hv.setTranslationY(0f);
            hv.setScaleX(1f);
            hv.setScaleY(1f);
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            c.animate().cancel();
            c.setTranslationX(0f);
            c.setTranslationY(0f);
            c.setScaleX(1f);
            c.setScaleY(1f);
            c.setTranslationZ(0f);
        }
    }

    /** Screen bounds of a cell after the page's current card/scroll transform. */
    private RectF cellScreenBounds(int page, int cell) {
        View pv = pager.getChildAt(page);
        if (!(pv instanceof GridPage)) return null;
        GridPage gp = (GridPage) pv;
        if (cell < 0 || cell >= gp.capacity()) return null;
        RectF cellRect = new RectF();
        gp.cellRect(cell, cellRect);
        int[] loc = new int[2];
        gp.getLocationOnScreen(loc);
        float sx = Math.max(0.01f, gp.getScaleX());
        float sy = Math.max(0.01f, gp.getScaleY());
        return new RectF(loc[0] + cellRect.left * sx,
                loc[1] + cellRect.top * sy,
                loc[0] + cellRect.right * sx,
                loc[1] + cellRect.bottom * sy);
    }

    /**
     * While dragging, expose one extra empty page after the last one so an item can be
     * carried onto a brand-new page. The spare page only becomes real if something is
     * dropped on it; otherwise the next render removes it.
     */
    private void ensureDragSparePage() {
        if (pager == null || dragSparePage || pager.getChildCount() == 0) return;
        View lastChild = pager.getChildAt(pager.getChildCount() - 1);
        if (!(lastChild instanceof GridPage)) return;
        GridPage last = (GridPage) lastChild;
        boolean empty = !last.hasWidgets();
        if (empty) {
            for (ItemView v : last.cellViews()) {
                if (v != null) {
                    empty = false;
                    break;
                }
            }
        }
        if (empty) return;

        GridPage spare = new GridPage(this);
        spare.setGrid(gridCols, gridRows);
        spare.bind(new ArrayList<ItemView>());
        spare.setTag(pager.getChildCount());
        spare.setCardMode(true);
        spare.setCardProgress(pager.cardProgress());
        pager.addView(spare, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dragSparePage = true;
        updateDots(pager.currentPage(), pager.pageCount());
    }

    private void ensureTrash() {
        if (trash == null) {
            trash = new LinearLayout(this);
            trash.setOrientation(LinearLayout.VERTICAL);
            trash.setGravity(Gravity.CENTER);
            trash.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 7));
            trash.setBackground(Ui.rounded(0xCC2A1215, Ui.dpf(this, 26),
                    0x99FF8A80, Ui.dpf(this, 1)));
            trash.setElevation(Ui.dpf(this, 16));
            trash.setContentDescription("Remove widget");
            TextView t = new LTextView(this);
            t.setText("\uD83D\uDDD1");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, 0, 0, 0);
            trash.addView(t);
            TextView label = new LTextView(this);
            label.setText("REMOVE");
            label.setTextColor(0xFFFFB4AB);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
            label.setLetterSpacing(0.12f);
            label.setGravity(Gravity.CENTER);
            trash.addView(label);
        }
        if (trash.getParent() == null) {
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            tlp.topMargin = Ui.dp(this, 26);
            root.addView(trash, tlp);
        }
        trash.setAlpha(0f);
        trash.setScaleX(0.88f);
        trash.setScaleY(0.88f);
        trash.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator(1.7f))
                .start();
    }

    private void resetEdgeAutoScroll() {
        if (dragPeek) {
            pager.cancelPeek(false);
            dragPeek = false;
            dragPeekCommitted = false;
            dragPreviewPage = -1;
        }
        handler.removeCallbacks(edgeScroll);
        handler.removeCallbacks(cornerScroll);
        edgeDir = 0;
        edgeScheduled = false;
        edgeAnchorX = 0f;
        edgeAnchorY = 0f;
        cornerDir = 0;
        cornerScheduled = false;
    }

    /** True while the dock is vertical, so the side edges are blocked by it. */
    private int verticalDockCorner(float rawX, float rawY) {
        int pos = prefs.dockPosition();
        if (pos != Prefs.DOCK_LEFT && pos != Prefs.DOCK_RIGHT) return 0;
        if (root == null) return 0;
        float w = root.getWidth();
        float h = root.getHeight();
        if (w <= 0 || h <= 0) return 0;
        if (rawY < h * 0.68f) return 0;
        if (rawX < w * 0.22f) return -1;
        if (rawX > w * 0.78f) return 1;
        return 0;
    }

    private void queueCornerPageTurn(int dir) {
        if (!dragging) {
            resetEdgeAutoScroll();
            return;
        }
        if (dir == 0) {
            handler.removeCallbacks(cornerScroll);
            cornerDir = 0;
            cornerScheduled = false;
            return;
        }
        if (dir != cornerDir) {
            handler.removeCallbacks(cornerScroll);
            cornerDir = dir;
            cornerScheduled = false;
        }
        if (cornerScheduled) return;
        long now = System.currentTimeMillis();
        long elapsed = lastAutoScroll == 0 ? EDGE_SWITCH_MS : now - lastAutoScroll;
        long wait = Math.max(EDGE_HOLD_MS, EDGE_SWITCH_MS - elapsed);
        handler.postDelayed(cornerScroll, wait);
        cornerScheduled = true;
    }

    private void ensurePageTurnHints() {
        if (prefs.dockPosition() != Prefs.DOCK_LEFT
                && prefs.dockPosition() != Prefs.DOCK_RIGHT) return;
        if (pageTurnLeftHint == null) {
            pageTurnLeftHint = makePageTurnHint("‹");
        }
        if (pageTurnRightHint == null) {
            pageTurnRightHint = makePageTurnHint("›");
        }
        if (pageTurnLeftHint.getParent() == null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 42), Ui.dp(this, 42), Gravity.BOTTOM | Gravity.START);
            lp.leftMargin = Ui.dp(this, 96);
            lp.bottomMargin = Ui.dp(this, 18);
            root.addView(pageTurnLeftHint, lp);
        }
        if (pageTurnRightHint.getParent() == null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 42), Ui.dp(this, 42), Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = Ui.dp(this, 96);
            lp.bottomMargin = Ui.dp(this, 18);
            root.addView(pageTurnRightHint, lp);
        }
    }

    private TextView makePageTurnHint(String text) {
        TextView v = new LTextView(this);
        v.setText(text);
        v.setTextColor(0xE6FFFFFF);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.rounded(0x660B0E14, Ui.dpf(this, 21),
                0x44FFFFFF, Ui.dpf(this, 1)));
        v.setElevation(Ui.dpf(this, 24));
        v.setClickable(false);
        v.setFocusable(false);
        return v;
    }

    private void removePageTurnHints() {
        if (pageTurnLeftHint != null && pageTurnLeftHint.getParent() instanceof ViewGroup) {
            ((ViewGroup) pageTurnLeftHint.getParent()).removeView(pageTurnLeftHint);
        }
        if (pageTurnRightHint != null && pageTurnRightHint.getParent() instanceof ViewGroup) {
            ((ViewGroup) pageTurnRightHint.getParent()).removeView(pageTurnRightHint);
        }
        pageTurnLeftHint = null;
        pageTurnRightHint = null;
    }

    private void queueEdgeAutoScroll(int page, float lx, float pageW) {
        if (!dragging) {
            resetEdgeAutoScroll();
            return;
        }
        float zone = Ui.dpf(this, 60);
        int dir = 0;
        if (lx < zone && page > 0) dir = -1;
        else if (lx > pageW - zone && page < pager.pageCount() - 1) dir = 1;
        if (dragWidget != null) {
            // Widgets carry the whole card between pages. Unlike icon drag, this
            // path never commits a peek, so switch the pager directly. The normal
            // right-edge test excludes the last page (there is no next page yet),
            // so promote the right-edge intent before checking pageCount.
            if (lx > pageW - zone && page == pager.pageCount() - 1) {
                dir = 1;
                ensureDragSparePage();
            }
            maybeSwitchDragPage(dir);
            return;
        }
        if (dir == 0) {
            resetEdgeAutoScroll();
            return;
        }
        if (dir != edgeDir) {
            handler.removeCallbacks(edgeScroll);
            edgeDir = dir;
            edgeScheduled = false;
            edgeAnchorX = lastRawX;
            edgeAnchorY = lastRawY;
        }
        if (edgeScheduled) return;
        long now = System.currentTimeMillis();
        long elapsed = lastAutoScroll == 0 ? EDGE_SWITCH_MS : now - lastAutoScroll;
        long wait = Math.max(EDGE_HOLD_MS, EDGE_SWITCH_MS - elapsed);
        handler.postDelayed(edgeScroll, wait);
        edgeScheduled = true;
    }

    private void moveDrag(float rawX, float rawY) {
        if (dragView == null) return;
        lastRawX = rawX;
        lastRawY = rawY;
        int w = dragView.getWidth();
        int h = dragView.getHeight();
        float tx = rawX - root.getLeft() - w / 2f;
        float ty = rawY - root.getTop() - h / 2f;
        // Follow the finger directly, like the performance overlay. The old 55 ms
        // catch-up animation made icons feel like they were lagging behind the touch.
        dragView.animate().cancel();
        dragView.setScaleX(1.08f);
        dragView.setScaleY(1.08f);
        dragView.setX(tx);
        dragView.setY(ty);

        computeHit(rawX, rawY);
        updateHighlights();
        scheduleHoverPreview();
    }

    private void maybeSwitchDragPage(int dir) {
        if (dir == 0) {
            dragEdgeDir = 0;
            dragEdgeLatched = false;
            dragEdgePendingDir = 0;
            handler.removeCallbacks(dragEdgeSwitch);
            return;
        }
        if (dir != dragEdgeDir) {
            dragEdgeDir = dir;
            dragEdgeLatched = false;
            dragEdgePendingDir = 0;
            handler.removeCallbacks(dragEdgeSwitch);
        }
        if (dragEdgeLatched) return;
        // Do not queue another page while the current card slide is still running.
        if (pager.isScrolling()) return;
        int current = dragSwitchPage >= 0 ? dragSwitchPage : pager.currentPage();
        int target = current + dir;
        if (target < 0 || target >= pager.pageCount()) return;
        if (dragEdgePendingDir == dir) return;
        dragEdgePendingDir = dir;
        // A short deliberate dwell in the outside margin. Releasing before it
        // finishes places the icon on the edge cell of the current page.
        handler.postDelayed(dragEdgeSwitch, 160);
    }

    /** Page under the pointer, clamped when the finger is outside the pager area. */
    private int dragPageAt(float rawX) {
        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        int pw = Math.max(1, pager.pageWidth());
        int current = pager.currentPage();
        if (rawX <= pl[0] || rawX >= pl[0] + pw) return current;
        int page = (int) Math.floor((rawX - pl[0] + pager.getScrollX()) / (float) pw);
        return Math.max(0, Math.min(pager.pageCount() - 1, page));
    }

    /** Clamped x inside the current page; never maps an off-screen edge to a neighbour. */
    private float dragLocalXAt(float rawX) {
        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        int pw = Math.max(1, pager.pageWidth());
        float x = rawX - pl[0];
        return Math.max(0f, Math.min(pw - 1f, x));
    }

    private void computeHit(float rawX, float rawY) {
        if (dragPeek && !releaseOverPagePreview(rawX, rawY)) {
            pager.cancelPeek(true);
            dragPeek = false;
            dragPeekCommitted = false;
            dragPreviewPage = -1;
        }
        if (dragPeek) {
            hitKind = HIT_NONE;
            hitPage = -1;
            hitCell = -1;
            dock.setDropIndex(-1);
            return;
        }
        hitKind = HIT_NONE;
        hitPage = -1;
        hitCell = -1;
        hitDock = -1;
        dock.setDropIndex(-1);

        // The drag-card scale moves the whole grid under the finger. If the
        // finger never left the original cell's screen bounds, keep this as a
        // drop onto the source cell instead of letting the scaled geometry
        // turn a no-op release into a move to the neighbouring cell.
        if (dragSrcKind == SRC_HOME && dragSrcPage >= 0 && dragSrcCell >= 0
                && pager.currentPage() == dragSrcPage
                && ((!dragSourceNormalBounds.isEmpty()
                        && dragSourceNormalBounds.contains(rawX, rawY))
                    || (!dragSourceCardBounds.isEmpty()
                        && dragSourceCardBounds.contains(rawX, rawY)))) {
            hitKind = HIT_HOME;
            hitPage = dragSrcPage;
            hitCell = dragSrcCell;
            return;
        }

        // trash zone
        if (dragView != null) {
            int[] tv = new int[2];
            trash.getLocationOnScreen(tv);
            float tvw = trash.getWidth();
            float tvh = trash.getHeight();
            if (rawX >= tv[0] - Ui.dp(this, 20) && rawX <= tv[0] + tvw + Ui.dp(this, 20)
                    && rawY >= tv[1] - Ui.dp(this, 24) && rawY <= tv[1] + tvh + Ui.dp(this, 24)) {
                hitKind = HIT_TRASH;
                return;
            }
        }

        // Vertical side dock: the usual side edge is occupied by the glass bar.
        // Bottom corners become the page-turn hotspots instead.
        int corner = verticalDockCorner(rawX, rawY);
        if (corner != 0) {
            queueCornerPageTurn(corner);
            hitKind = HIT_NONE;
            hitPage = -1;
            hitCell = -1;
            return;
        }
        queueCornerPageTurn(0);

        // dock
        if (dock != null && dock.getVisibility() == View.VISIBLE) {
            int[] dl = new int[2];
            dock.getLocationOnScreen(dl);
            boolean vertical = dock.getOrientation() == LinearLayout.VERTICAL;
            boolean inDockRow = rawY >= dl[1] - Ui.dp(this, 12)
                    && rawY <= dl[1] + dock.getHeight() + Ui.dp(this, 16);
            if (inDockRow && vertical) {
                if (rawX >= dl[0] - Ui.dp(this, 24)
                        && rawX <= dl[0] + dock.getWidth() + Ui.dp(this, 24)) {
                    hitKind = HIT_DOCK;
                    hitDock = dockIndexAt(rawY - dl[1]);
                    dock.setDropIndex(hitDock);
                    return;
                }
            } else if (inDockRow) {
                // The empty horizontal strips on either side of the bottom dock are
                // part of the dock's drop target, so icons can be dropped there too.
                int screenW = getResources().getDisplayMetrics().widthPixels;
                if (rawX >= 0 && rawX <= screenW) {
                    hitKind = HIT_DOCK;
                    hitDock = dockIndexAt(rawX - dl[0]);
                    dock.setDropIndex(hitDock);
                    return;
                }
            }
        }

        // pager
        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        int pageW = Math.max(1, pager.pageWidth());
        if (pager.isCardMode()) {
            if (rawY < pl[1] - Ui.dp(this, 40)
                    || rawY > pl[1] + pager.getHeight() + Ui.dp(this, 40)) return;
            float cardScale = pager.cardScale();
            float cardW = pageW * cardScale;
            float cardH = pager.getHeight() * cardScale;
            float left = pl[0] + (pageW - cardW) / 2f;
            float right = left + cardW;
            float top = pl[1] + (pager.getHeight() - cardH) / 2f
                    + Ui.dpf(this, 4) * pager.cardProgress();
            // Trigger from the visible card edge, not the old full-page edge.
            // Only the margin outside the card requests a page switch. Anything
            // inside the card — including its outer icon columns — is a normal
            // drop target.
            int dir = rawX <= left ? -1 : rawX >= right ? 1 : 0;
            maybeSwitchDragPage(dir);
            float lx = (rawX - left) / cardScale;
            float ly = (rawY - top) / cardScale;
            if (lx < 0 || lx > pageW || ly < 0 || ly > pager.getHeight()) return;
            int page = dragSwitchPage >= 0 ? dragSwitchPage : pager.currentPage();
            if (page < 0 || page >= pager.getChildCount()) return;
            GridPage gp = (GridPage) pager.getChildAt(page);
            if (gp == null) return;
            int cell = gp.cellAt(lx, ly);
            if (cell < 0) return;
            hitKind = HIT_HOME;
            hitPage = page;
            hitCell = cell;
            return;
        }
        int page = dragPageAt(rawX);
        float lx = dragLocalXAt(rawX);
        float ly = rawY - pl[1];
        if (ly < -Ui.dp(this, 40) || ly > pager.getHeight() + Ui.dp(this, 40)) return;
        GridPage gp = (GridPage) pager.getChildAt(page);
        if (gp == null) return;
        int cell = gp.cellAt(lx, ly);
        if (cell < 0) return;
        hitKind = HIT_HOME;
        hitPage = page;
        hitCell = cell;
    }

    private int dockIndexAt(float coord) {
        boolean vertical = dock.getOrientation() == LinearLayout.VERTICAL;
        int best = store.dock.size();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            if (!(c instanceof ItemView)) continue;
            float center = vertical
                    ? c.getTop() + c.getHeight() / 2f
                    : c.getLeft() + c.getWidth() / 2f;
            if (coord < center) {
                return ((ItemView) c).dockIndex;
            }
            best = ((ItemView) c).dockIndex + 1;
        }
        return Math.min(best, store.dock.size());
    }

    private void updateHighlights() {
        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (c instanceof GridPage) {
                int cell = hitKind == HIT_HOME && hitPage == i ? hitCell : -1;
                // Do not flash a target frame over the empty source cell just
                // because the finger has not left it yet.
                if (dragging && dragSrcKind == SRC_HOME && i == dragSrcPage
                        && cell == dragSrcCell) {
                    cell = -1;
                }
                ((GridPage) c).setHighlight(cell);
            }
        }
    }

    private void scheduleHoverPreview() {
        if (!dragging || hitKind != HIT_HOME || hitPage < 0 || hitCell < 0) {
            clearHoverPreview(true);
            return;
        }
        String key = hitPage + ":" + hitCell;
        if (key.equals(hoverPreviewKey)) return;
        clearHoverPreview(true);
        hoverPreviewKey = key;
        handler.removeCallbacks(showHoverPreview);
        handler.postDelayed(showHoverPreview, 75);
    }

    /**
     * While the finger rests over an occupied cell, slide that item toward the
     * slot it will occupy after the drop. The gap left behind is the drop target.
     */
    private void applyHoverPreview() {
        if (!dragging || hitKind != HIT_HOME || hitPage < 0 || hitCell < 0) return;
        View pageView = pager.getChildAt(hitPage);
        if (!(pageView instanceof GridPage)) return;
        GridPage gp = (GridPage) pageView;
        if (gp.widgetAt(hitCell)) {
            Store.PlacedWidget w = widgetAt(hitPage, hitCell);
            if (w == null || !previewWidgetMove(w, hitPage)) clearHoverPreview(true);
            return;
        }
        ItemView target = gp.viewAt(hitCell);
        if (target == null || target == dragSource || target.item == null) {
            clearHoverPreview(true);
            return;
        }
        if (target.item.type == Item.TYPE_FOLDER) {
            clearHoverPreview(true);
            return;
        }
        if (dragSource != null && dragSource.item != null
                && target.item.key().equals(dragSource.item.key())) {
            // Dropping an app onto another copy of the same app is a no-op,
            // not a rearrangement or folder creation.
            clearHoverPreview(true);
            return;
        }
        int destPage = -1;
        int destCell = -1;
        if (dragSrcKind == SRC_HOME) {
            if (dragSrcPage != hitPage || dragSrcCell < 0 || dragSrcCell == hitCell) {
                clearHoverPreview(true);
                return;
            }
            if (target.item.type == Item.TYPE_APP && dragSource != null
                    && dragSource.item != null && dragSource.item.type == Item.TYPE_APP
                    && isCentreDropForPreview(hitPage, hitCell)) {
                clearHoverPreview(true);
                return;
            }
            destPage = dragSrcPage;
            destCell = dragSrcCell;
        } else if (dragSrcKind == SRC_DRAWER) {
            if (target.item.type != Item.TYPE_APP
                    || isCentreDropForPreview(hitPage, hitCell)) {
                clearHoverPreview(true);
                return;
            }
            destPage = hitPage;
            destCell = nearestFreeCellExcept(hitPage, hitCell, hitCell);
        } else {
            clearHoverPreview(true);
            return;
        }
        if (destPage < 0 || destCell < 0) {
            clearHoverPreview(true);
            return;
        }
        RectF from = new RectF();
        RectF to = new RectF();
        gp.cellRect(hitCell, from);
        gp.cellRect(destCell, to);
        float dx = to.left - from.left;
        float dy = to.top - from.top;
        hoverPreviewView = target;
        hoverPreviewPage = hitPage;
        hoverPreviewCell = hitCell;
        hoverPreviewDestPage = destPage;
        hoverPreviewDestCell = destCell;
        target.animate().cancel();
        target.setTranslationZ(Ui.dpf(this, 14));
        target.animate().translationX(dx).translationY(dy)
                .scaleX(0.90f).scaleY(0.90f).alpha(0.86f)
                .setDuration(190)
                .setInterpolator(new OvershootInterpolator(0.55f))
                .start();
    }

    private boolean previewWidgetMove(Store.PlacedWidget w, int page) {
        View wv = widgetViews.get(w.appWidgetId);
        int dest = findFreeWidgetCellForMove(page, w);
        if (wv == null || dest < 0) return false;
        int cw = Math.max(1, pager.getWidth() / Math.max(1, gridCols));
        int ch = Math.max(1, pager.getHeight() / Math.max(1, gridRows));
        int oldCol = w.cell % Math.max(1, gridCols);
        int oldRow = w.cell / Math.max(1, gridCols);
        int newCol = dest % Math.max(1, gridCols);
        int newRow = dest / Math.max(1, gridCols);
        float dx = (newCol - oldCol) * cw;
        float dy = (newRow - oldRow) * ch;
        hoverPreviewWidget = wv;
        hoverPreviewWidgetItem = w;
        hoverPreviewPage = page;
        hoverPreviewCell = w.cell;
        hoverPreviewDestPage = page;
        hoverPreviewDestCell = dest;
        wv.animate().cancel();
        wv.setTranslationZ(Ui.dpf(this, 12));
        wv.animate().translationX(dx).translationY(dy)
                .scaleX(0.97f).scaleY(0.97f).alpha(0.92f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
        return true;
    }

    private void clearHoverPreview(boolean animate) {
        handler.removeCallbacks(showHoverPreview);
        hoverPreviewKey = null;
        ItemView v = hoverPreviewView;
        if (v != null) {
            v.animate().cancel();
            if (animate) {
                final ItemView fv = v;
                fv.animate().translationX(0f).translationY(0f)
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(150)
                        .setInterpolator(new DecelerateInterpolator(1.6f))
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                fv.setTranslationZ(0f);
                            }
                        }).start();
            } else {
                v.setTranslationX(0f);
                v.setTranslationY(0f);
                v.setScaleX(1f);
                v.setScaleY(1f);
                v.setAlpha(1f);
                v.setTranslationZ(0f);
            }
        }
        View w = hoverPreviewWidget;
        if (w != null) {
            w.animate().cancel();
            w.setTranslationX(0f);
            w.setTranslationY(0f);
            w.setScaleX(1f);
            w.setScaleY(1f);
            w.setAlpha(1f);
            w.setTranslationZ(0f);
        }
        hoverPreviewView = null;
        hoverPreviewWidget = null;
        hoverPreviewWidgetItem = null;
        hoverPreviewPage = -1;
        hoverPreviewCell = -1;
        hoverPreviewDestPage = -1;
        hoverPreviewDestCell = -1;
    }

    private Store.PlacedWidget widgetAt(int page, int cell) {
        int cols = Math.max(1, gridCols);
        int c = cell % cols;
        int r = cell / cols;
        for (Store.PlacedWidget w : store.widgets) {
            if (w.page != page) continue;
            int wc = w.cell % cols;
            int wr = w.cell / cols;
            if (c >= wc && c < wc + w.spanCols && r >= wr && r < wr + w.spanRows) {
                return w;
            }
        }
        return null;
    }

    private int nearestFreeCellExcept(int page, int fromCell, int exclude) {
        int cols = Math.max(1, gridCols);
        int cap = capacity();
        List<Item> list = store.page(page);
        int fc = fromCell % cols;
        int fr = fromCell / cols;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < cap; i++) {
            if (i == exclude || widgetCovers(page, i)) continue;
            if (i < list.size() && list.get(i) != null) continue;
            int c = i % cols;
            int r = i / cols;
            float d = Math.abs(c - fc) + Math.abs(r - fr) * 2f;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private int findFreeWidgetCellForMove(int page, Store.PlacedWidget moving) {
        int cols = Math.max(1, gridCols);
        int rows = Math.max(1, gridRows);
        int sc = Math.max(1, Math.min(cols, moving.spanCols));
        int sr = Math.max(1, Math.min(rows, moving.spanRows));
        Set<Integer> occupied = new HashSet<>();
        List<Item> pg = store.page(page);
        for (int i = 0; i < cols * rows; i++) {
            if (i < pg.size() && pg.get(i) != null) occupied.add(i);
        }
        if (prefs.clockVisible() && page == clockPage()) {
            occupied.addAll(clockCells());
        }
        for (Store.PlacedWidget other : store.widgets) {
            if (other == moving || other.page != page) continue;
            int c = other.cell % cols;
            int r = other.cell / cols;
            for (int y = r; y < r + other.spanRows; y++) {
                for (int x = c; x < c + other.spanCols; x++) {
                    occupied.add(y * cols + x);
                }
            }
        }
        // Reserve the widget's current footprint as well: a destination that
        // overlaps it would only look like a small shift, not a clear move.
        int mc = moving.cell % cols;
        int mr = moving.cell / cols;
        for (int y = mr; y < mr + moving.spanRows; y++) {
            for (int x = mc; x < mc + moving.spanCols; x++) {
                occupied.add(y * cols + x);
            }
        }
        for (int r = 0; r <= rows - sr; r++) {
            for (int c = 0; c <= cols - sc; c++) {
                boolean free = true;
                for (int y = 0; y < sr && free; y++) {
                    for (int x = 0; x < sc; x++) {
                        if (occupied.contains((r + y) * cols + c + x)) {
                            free = false;
                            break;
                        }
                    }
                }
                if (free) return r * cols + c;
            }
        }
        return -1;
    }

    private void planDropWidget() {
        dropWidget = null;
        dropWidgetDestCell = -1;
        if (hitKind != HIT_HOME || hitPage < 0 || hitCell < 0
                || !widgetCovers(hitPage, hitCell)) return;
        Store.PlacedWidget w = widgetAt(hitPage, hitCell);
        if (w == null) return;
        int dest = findFreeWidgetCellForMove(hitPage, w);
        if (dest < 0) return;
        dropWidget = w;
        dropWidgetDestCell = dest;
    }

    private boolean isCentreDropForPreview(int page, int cell) {
        View c = pager.getChildAt(page);
        if (!(c instanceof GridPage)) return false;
        GridPage gp = (GridPage) c;
        RectF r = new RectF();
        gp.cellRect(cell, r);
        int[] loc = new int[2];
        gp.getLocationOnScreen(loc);
        float scale = Math.max(0.01f, gp.getScaleX());
        float lx = (lastRawX - loc[0]) / scale;
        if (r.width() <= 0) return false;
        float fraction = (lx - r.left) / r.width();
        return fraction >= 0.32f && fraction <= 0.68f;
    }

    private boolean showDragPagePreview(int targetPage) {
        if (pager == null || targetPage < 0 || targetPage >= pager.pageCount()) return false;
        View target = pager.getChildAt(targetPage);
        if (!(target instanceof GridPage) || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return false;
        }
        hideDragPagePreview();
        float oldAlpha = target.getAlpha();
        float oldScaleX = target.getScaleX();
        float oldScaleY = target.getScaleY();
        float oldTx = target.getTranslationX();
        float oldTy = target.getTranslationY();
        target.setAlpha(1f);
        target.setScaleX(1f);
        target.setScaleY(1f);
        target.setTranslationX(0f);
        target.setTranslationY(0f);
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(target.getWidth(), target.getHeight(),
                    Bitmap.Config.ARGB_8888);
            target.draw(new Canvas(bmp));
        } catch (Throwable t) {
            if (bmp != null) bmp.recycle();
            bmp = null;
        } finally {
            target.setAlpha(oldAlpha);
            target.setScaleX(oldScaleX);
            target.setScaleY(oldScaleY);
            target.setTranslationX(oldTx);
            target.setTranslationY(oldTy);
        }
        if (bmp == null) return false;

        int sw = root.getWidth();
        int sh = root.getHeight();
        float aspect = target.getWidth() / (float) Math.max(1, target.getHeight());
        int cardW = Math.round(sw * 0.28f);
        int cardH = Math.round(cardW / Math.max(0.01f, aspect));
        if (cardH > sh * 0.72f) {
            cardH = Math.round(sh * 0.72f);
            cardW = Math.round(cardH * aspect);
        }
        int dir = targetPage > pager.currentPage() ? 1 : -1;
        int left = dir > 0 ? sw - cardW - Ui.dp(this, 18) : Ui.dp(this, 18);
        int top = (sh - cardH) / 2;
        dragPreviewPage = targetPage;
        dragPagePreviewBitmap = bmp;
        dragPreviewRect.set(left, top, left + cardW, top + cardH);

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setImageBitmap(bmp);
        preview.setBackground(Ui.rounded(0xCC0B0E14, Ui.dpf(this, 22),
                0x66FFFFFF, Ui.dpf(this, 1)));
        preview.setPadding(Ui.dp(this, 4), Ui.dp(this, 4),
                Ui.dp(this, 4), Ui.dp(this, 4));
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Ui.dpf(MainActivity.this, 22));
            }
        });
        preview.setElevation(Ui.dpf(this, 26));
        dragPagePreview = preview;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cardW, cardH);
        lp.leftMargin = left;
        lp.topMargin = top;
        root.addView(preview, lp);
        preview.setAlpha(0f);
        preview.setTranslationX(dir > 0 ? Ui.dpf(this, 24) : -Ui.dpf(this, 24));
        preview.animate().alpha(1f).translationX(0f)
                .setDuration(150).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        if (dragView != null) dragView.bringToFront();
        return true;
    }

    private void hideDragPagePreview() {
        ImageView preview = dragPagePreview;
        dragPagePreview = null;
        dragPreviewPage = -1;
        if (preview != null && preview.getParent() instanceof ViewGroup) {
            ((ViewGroup) preview.getParent()).removeView(preview);
        }
        if (dragPagePreviewBitmap != null) {
            dragPagePreviewBitmap.recycle();
            dragPagePreviewBitmap = null;
        }
    }

    private boolean releaseOverPagePreview(float rawX, float rawY) {
        if (pager == null || !pager.hasPeek()) return false;
        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        if (rawY < pl[1] - Ui.dp(this, 20)
                || rawY > pl[1] + pager.getHeight() + Ui.dp(this, 20)) return false;
        int pw = Math.max(1, pager.pageWidth());
        if (pager.peekDir() > 0) return rawX >= pl[0] + pw * 0.70f;
        return rawX <= pl[0] + pw * 0.30f;
    }

    private void endDrag(float rawX, float rawY) {
        if (!dragging || dragView == null) {
            cleanupDrag(false);
            return;
        }
        // If the page is still carrying across, let that finish instead of
        // snapping the card out from under the drop. The icon stays lifted for
        // the remaining few frames, then lands on the settled page.
        if (pager.isScrolling()) {
            final float fx = rawX;
            final float fy = rawY;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (dragging && dragView != null) endDrag(fx, fy);
                }
            }, 400);
            return;
        }
        Haptics.drop(root);
        if (dragPeek && !dragPeekCommitted) {
            if (releaseOverPagePreview(rawX, rawY)) {
                dragPeekCommitted = true;
                dragPeek = false;
                pager.commitPeek(true);
                final float fx = rawX;
                final float fy = rawY;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (dragging && dragView != null) endDrag(fx, fy);
                    }
                }, 330);
                return;
            }
            pager.cancelPeek(true);
            dragPeek = false;
            dragPeekCommitted = false;
            dragPreviewPage = -1;
        }
        // Releasing before the dwell finishes must place on the current page,
        // including the edge cell.
        dragEdgePendingDir = 0;
        handler.removeCallbacks(dragEdgeSwitch);
        // If a page turn was still finishing, land on its target page before
        // restoring full-size hit geometry.
        pager.setPeekVisual(false);
        pager.settleScrolling();
        dragSourceCardBounds.setEmpty();
        if (dragSrcKind == SRC_HOME && dragSrcPage >= 0 && dragSrcCell >= 0
                && pager.currentPage() == dragSrcPage) {
            RectF cardBounds = cellScreenBounds(dragSrcPage, dragSrcCell);
            if (cardBounds != null) dragSourceCardBounds.set(cardBounds);
        }
        pager.finishCardImmediately();
        if (dragPendingPage >= 0 && dragPendingCell >= 0) {
            hitKind = HIT_HOME;
            hitPage = dragPendingPage;
            hitCell = dragPendingCell;
            dragPendingPage = -1;
            dragPendingCell = -1;
        } else {
            computeHit(rawX, rawY);
        }
        // A cross-page drop often lands on the clock/widget at the far edge.
        // Move to the nearest free cell on that page instead of springing back.
        if (hitKind == HIT_HOME && hitCell >= 0 && dragSrcPage >= 0
                && hitPage != dragSrcPage) {
            List<Item> targetPage = store.page(hitPage);
            Item targetItem = hitCell < targetPage.size() ? targetPage.get(hitCell) : null;
            boolean blocked = widgetCovers(hitPage, hitCell)
                    || (targetItem != null && targetItem.type != Item.TYPE_FOLDER);
            if (blocked) {
                int free = nearestFreeCell(hitPage, hitCell);
                if (free >= 0) {
                    hitCell = free;
                    dropFraction = 0.5f;
                }
            }
        }
        planDropWidget();
        final int droppedKind = hitKind;
        dropFraction = 0.5f;
        if (hitKind == HIT_HOME && hitCell >= 0) {
            View pv = pager.getChildAt(hitPage);
            if (pv instanceof GridPage) {
                RectF cell = new RectF();
                ((GridPage) pv).cellRect(hitCell, cell);
                int[] pl = new int[2];
                pager.getLocationOnScreen(pl);
                float localX = rawX - pl[0] + pager.getScrollX()
                        - hitPage * pager.pageWidth();
                if (cell.width() > 0) {
                    dropFraction = Math.max(0f,
                            Math.min(1f, (localX - cell.left) / cell.width()));
                }
            }
        }

        boolean sameAppTarget = false;
        if (dragSource != null && dragSource.item != null
                && hitKind == HIT_HOME && hitPage >= 0 && hitCell >= 0) {
            List<Item> targetPage = store.page(hitPage);
            Item targetItem = hitCell < targetPage.size() ? targetPage.get(hitCell) : null;
            sameAppTarget = targetItem != null
                    && targetItem.key().equals(dragSource.item.key());
        }
        boolean sameSpot = sameAppTarget;
        if (dragSrcKind == SRC_HOME && hitKind == HIT_HOME
                && dragSrcPage == hitPage && dragSrcCell == hitCell) {
            sameSpot = true;
        } else if (dragSrcKind == SRC_DOCK && hitKind == HIT_DOCK
                && (hitDock == dragSrcDock || hitDock == dragSrcDock + 1)) {
            sameSpot = true;
        }
        if (!sameSpot && hitKind == HIT_HOME && hitCell >= 0
                && widgetCovers(hitPage, hitCell) && dropWidget == null) {
            // A movable widget has already found a free slot; the clock (or a
            // widget with no room) still springs the app back.
            sameSpot = true;
        }

        RectF target = dropTargetRect();
        boolean springBack = sameSpot || (target == null && droppedKind != HIT_TRASH);
        skipRefreshAfterDrag = springBack;
        if (springBack) {
            target = sourceRect();
            // The source stays hidden until the returning icon lands; cleanup
            // restores it, so there is never a ghost behind the drag.
        }
        if (target != null && droppedKind != HIT_TRASH) {
            if (springBack) {
                // No rerender: the same views stay on screen.
                startHomeRipple(target.centerX(), target.centerY(), true);
            } else {
                // Start after the immediate rerender, at layout time. This is
                // still the release frame, but targets the final views.
                pendingDropRipple = true;
                pendingDropRippleX = target.centerX();
                pendingDropRippleY = target.centerY();
            }
        }
        // If there is no valid target (or the gesture is springing back), never
        // run the store mutation. In particular, releasing just outside a cell
        // near the page edge must leave the item where it came from.
        Item movedItem = (dragSrcKind == SRC_HOME || dragSrcKind == SRC_DOCK)
                && dragSource != null ? dragSource.item : null;
        if (dragSrcKind == SRC_DRAWER && dragSource != null) movedItem = dragSource.item;
        if (!springBack) {
            // Snapshot the visible cells/widgets before mutating the store, so the
            // refreshed layout can play a short hand-off animation from the old
            // positions instead of appearing as a new, static page.
            dropMovedKey = movedItem != null && hitKind == HIT_HOME ? movedItem.key() : null;
            dropMovedPage = hitKind == HIT_HOME ? hitPage : -1;
            dropMovedCell = hitKind == HIT_HOME ? hitCell : -1;
            dropPreviewKey = hoverPreviewView != null && hoverPreviewView.item != null
                    ? hoverPreviewView.item.key() : null;
            dropPreviewPage = hoverPreviewDestPage;
            dropPreviewCell = hoverPreviewDestCell;
            captureHomePositions();
            dropTransitionPending = true;
            applyDrop();
        }
        // Safety net for failed/odd drops: never silently lose a home/dock item.
        if (!springBack && droppedKind != HIT_TRASH && movedItem != null
                && !storeContainsKey(movedItem.key())) {
            if (dragSrcKind == SRC_HOME) {
                putHome(dragSrcPage, dragSrcCell, movedItem);
            } else {
                store.dock.add(Math.max(0, Math.min(dragSrcDock, store.dock.size())),
                        movedItem);
            }
            store.save();
        }
        dismissActiveMenu();
        dock.setDropIndex(-1);

        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (c instanceof GridPage) ((GridPage) c).setHighlight(-1);
        }
        if (trash != null) {
            trash.animate().alpha(0f).setDuration(120).start();
        }

        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        final boolean animateDockAfter = droppedKind == HIT_DOCK && !sameSpot;
        Runnable done = new Runnable() {
            @Override
            public void run() {
                if (animateDockAfter) animateDock = true;
                if (dragSrcKind == SRC_DRAWER) {
                    if (droppedKind == HIT_HOME || droppedKind == HIT_DOCK) {
                        closeDrawer(false);
                    } else {
                        restoreDrawerAfterDrag();
                    }
                } else if (dragSrcKind == SRC_FOLDER) {
                    if (droppedKind == HIT_HOME || droppedKind == HIT_DOCK
                            || droppedKind == HIT_TRASH) {
                        closeFolder();
                    } else {
                        restoreFolderAfterDrag();
                    }
                }
                final boolean madeFolder = justCreatedFolder;
                final String folderKey = lastFolderKey;
                cleanupDrag(false);
                if (madeFolder) {
                    justCreatedFolder = false;
                    lastFolderKey = null;
                    LToast.makeText(MainActivity.this, "Folder created", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (folderKey == null) return;
                            for (List<Item> p : store.pages) {
                                for (Item it : p) {
                                    if (it != null && it.type == Item.TYPE_FOLDER
                                            && it.key().equals(folderKey)) {
                                        openFolder(it);
                                        return;
                                    }
                                }
                            }
                        }
                    }, 160);
                }
            }
        };

        if (dragView != null) dragView.animate().cancel();
        if (droppedKind == HIT_TRASH && trash != null && dragSrcKind != SRC_DRAWER) {
            int[] tl = new int[2];
            trash.getLocationOnScreen(tl);
            float tx = tl[0] + trash.getWidth() / 2f - rl[0] - dragView.getWidth() / 2f;
            float ty = tl[1] + trash.getHeight() / 2f - rl[1] - dragView.getHeight() / 2f;
            dragView.animate().x(tx).y(ty).scaleX(0.22f).scaleY(0.22f).alpha(0f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.3f))
                    .withEndAction(done)
                    .start();
        } else if (target != null) {
            // The ripple is the drop animation: commit and restore the views
            // in the release frame, with no landing flight before it.
            done.run();
        } else {
            dragView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f)
                    .setDuration(170)
                    .withEndAction(done)
                    .start();
        }
    }

    /**
     * A short curved landing. Icons lifted from the drawer get a little more
     * lift and travel, while same-page moves stay compact and snappy.
     */
    private void animateDragLanding(RectF target, boolean springBack,
                                    boolean fromDrawer, int[] rootLoc, final Runnable done) {
        if (dragView == null) {
            done.run();
            return;
        }
        final ImageView view = dragView;
        final float startX = view.getX();
        final float startY = view.getY();
        final float endX = target.centerX() - rootLoc[0] - view.getWidth() / 2f;
        final float endY = target.centerY() - rootLoc[1] - view.getHeight() / 2f;
        final float lift = springBack ? 0f : Ui.dpf(this, fromDrawer ? 16 : 8);
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(springBack ? 245L : (fromDrawer ? 300L : 260L));
        anim.setInterpolator(springBack
                ? new DecelerateInterpolator(2.4f)
                : new PathInterpolator(0.22f, 0f, 0.18f, 1f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                float x = startX + (endX - startX) * t;
                float y = startY + (endY - startY) * t;
                if (lift > 0f) y -= (float) Math.sin(Math.PI * t) * lift;
                view.setX(x);
                view.setY(y);
                float s;
                if (springBack) {
                    // Returning to the start should look like a soft settle, not
                    // a second bounce on top of the release.
                    s = 1.08f + (1f - 1.08f) * t;
                } else {
                    s = 1.08f + 0.04f * (float) Math.sin(Math.PI * t) - 0.08f * t;
                }
                view.setScaleX(s);
                view.setScaleY(s);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                view.setX(endX);
                view.setY(endY);
                view.setScaleX(1f);
                view.setScaleY(1f);
                if (!springBack) Haptics.tick(root);
                done.run();
            }
        });
        anim.start();
    }

    /** Screen-space rectangle the dragged icon should settle into. */
    private RectF dropTargetRect() {
        if (hitKind == HIT_HOME && hitPage >= 0 && hitCell >= 0) {
            View c = pager.getChildAt(hitPage);
            if (c instanceof GridPage) {
                GridPage gp = (GridPage) c;
                ItemView targetView = gp.viewAt(hitCell);
                if (targetView != null) {
                    RectF icon = new RectF();
                    targetView.iconLocalRect(icon);
                    int[] pl = new int[2];
                    pager.getLocationOnScreen(pl);
                    float left = pl[0] - pager.getScrollX()
                            + hitPage * pager.pageWidth();
                    float top = pl[1];
                    return new RectF(left + icon.left, top + icon.top,
                            left + icon.right, top + icon.bottom);
                }
                RectF cell = new RectF();
                gp.cellRect(hitCell, cell);
                int[] pl = new int[2];
                pager.getLocationOnScreen(pl);
                float left = pl[0] - pager.getScrollX() + hitPage * pager.pageWidth() + cell.left;
                float top = pl[1] + cell.top;
                float iconW = dragView != null ? dragView.getWidth() : cell.width() * 0.45f;
                float iconH = dragView != null ? dragView.getHeight() : cell.height() * 0.45f;
                float ix = left + (cell.width() - iconW) / 2f;
                float iy = top + Ui.dp(this, 8);
                return new RectF(ix, iy, ix + iconW, iy + iconH);
            }
        } else if (hitKind == HIT_DOCK) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                View c = dock.getChildAt(i);
                if (c instanceof ItemView && ((ItemView) c).dockIndex == hitDock) {
                    RectF icon = new RectF();
                    ((ItemView) c).iconScreenRect(icon);
                    return icon;
                }
            }
            int[] dl = new int[2];
            dock.getLocationOnScreen(dl);
            float cx = dl[0] + dock.getWidth() * 0.82f;
            float cy = dl[1] + dock.getHeight() / 2f;
            return new RectF(cx - 30, cy - 30, cx + 30, cy + 30);
        }
        return null;
    }

    /** Screen-space rectangle of the item being dragged (for a spring-back). */
    private RectF sourceRect() {
        if (dragSource == null) return null;
        if (dragSrcKind == SRC_HOME && dragSrcPage >= 0 && dragSrcCell >= 0) {
            View pv = pager.getChildAt(dragSrcPage);
            if (pv instanceof GridPage) {
                RectF icon = new RectF();
                dragSource.iconLocalRect(icon);
                int[] pl = new int[2];
                pager.getLocationOnScreen(pl);
                float left = pl[0] - pager.getScrollX()
                        + dragSrcPage * pager.pageWidth();
                return new RectF(left + icon.left, pl[1] + icon.top,
                        left + icon.right, pl[1] + icon.bottom);
            }
        }
        RectF icon = new RectF();
        dragSource.iconScreenRect(icon);
        return icon;
    }

    private void cancelDrag() {
        if (dragSrcKind == SRC_DRAWER) {
            restoreDrawerAfterDrag();
        } else if (dragSrcKind == SRC_FOLDER) {
            restoreFolderAfterDrag();
        }
        pager.setCardMode(false);
        skipRefreshAfterDrag = true;
        cleanupDrag(true);
    }

    private int nearestFreeCell(int page, int fromCell) {
        int cols = Math.max(1, gridCols);
        int cap = capacity();
        List<Item> list = store.page(page);
        int fc = fromCell % cols;
        int fr = fromCell / cols;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < cap; i++) {
            if (widgetCovers(page, i)) continue;
            if (i < list.size() && list.get(i) != null) continue;
            int c = i % cols;
            int r = i / cols;
            float d = Math.abs(c - fc) + Math.abs(r - fr) * 2f;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private boolean storeContainsKey(String key) {
        if (key == null) return false;
        for (List<Item> page : store.pages) {
            for (Item it : page) {
                if (itemOrChildHasKey(it, key)) return true;
            }
        }
        for (Item it : store.dock) {
            if (itemOrChildHasKey(it, key)) return true;
        }
        return false;
    }

    private boolean itemOrChildHasKey(Item item, String key) {
        if (item == null) return false;
        if (key.equals(item.key())) return true;
        if (item.type == Item.TYPE_FOLDER) {
            for (Item child : item.children) {
                if (itemOrChildHasKey(child, key)) return true;
            }
        }
        return false;
    }

    private void applyDrop() {
        if (dragSrcKind == SRC_NONE) return;
        justCreatedFolder = false;
        lastFolderKey = null;
        if (dragSrcKind == SRC_DRAWER) {
            applyDrawerDrop();
            return;
        }
        if (dragSrcKind == SRC_FOLDER) {
            applyFolderDrop();
            return;
        }

        if (hitKind == HIT_TRASH) {
            if (dragSrcKind == SRC_HOME) {
                takeHome(dragSrcPage, dragSrcCell);
            } else if (dragSrcKind == SRC_DOCK && dragSrcDock >= 0
                    && dragSrcDock < store.dock.size()) {
                store.dock.remove(dragSrcDock);
            }
        } else if (hitKind == HIT_DOCK) {
            Item moving;
            int srcPage = -1;
            int srcCell = -1;
            if (dragSrcKind == SRC_HOME) {
                moving = takeHome(dragSrcPage, dragSrcCell);
                srcPage = dragSrcPage;
                srcCell = dragSrcCell;
            } else if (dragSrcKind == SRC_DOCK) {
                if (dragSrcDock < 0 || dragSrcDock >= store.dock.size()) return;
                moving = store.dock.remove(dragSrcDock);
            } else {
                return;
            }
            if (moving == null) return;
            int idx = hitDock;
            if (dragSrcKind == SRC_DOCK && dragSrcDock < hitDock) idx--;
            int maxDock = prefs.dockCount();
            idx = Math.max(0, Math.min(idx, store.dock.size()));
            if (store.dock.size() >= maxDock) {
                idx = Math.min(idx, maxDock - 1);
                Item displaced = store.dock.set(idx, moving);
                if (displaced != null) {
                    if (srcPage >= 0) {
                        putHome(srcPage, srcCell, displaced);
                    } else {
                        store.addToHome(displaced, capacity());
                    }
                }
            } else {
                store.dock.add(Math.min(idx, maxDock), moving);
            }
        } else if (hitKind == HIT_HOME && hitCell >= 0 && hitPage >= 0) {
            if (dragSrcKind == SRC_HOME) {
                if (dragSrcPage == hitPage && dragSrcCell == hitCell) return;
                commitDropWidget();
                Item moving = takeHome(dragSrcPage, dragSrcCell);
                if (moving == null) return;
                Item target = takeHome(hitPage, hitCell);
                if (target == null) {
                    putHome(hitPage, hitCell, moving);
                } else if (target.type == Item.TYPE_FOLDER) {
                    putHome(hitPage, hitCell, target);
                    addToFolder(target, moving);
                } else if (target.type == Item.TYPE_APP && moving.type == Item.TYPE_APP
                        && isCentreDrop() && !target.key().equals(moving.key())) {
                    makeFolderAt(hitPage, hitCell, target, moving);
                } else {
                    putHome(hitPage, hitCell, moving);
                    putHome(dragSrcPage, dragSrcCell, target);
                }
            } else if (dragSrcKind == SRC_DOCK) {
                if (dragSrcDock < 0 || dragSrcDock >= store.dock.size()) return;
                commitDropWidget();
                Item moving = store.dock.remove(dragSrcDock);
                if (moving == null) return;
                Item target = takeHome(hitPage, hitCell);
                if (target == null) {
                    putHome(hitPage, hitCell, moving);
                } else if (target.type == Item.TYPE_FOLDER) {
                    putHome(hitPage, hitCell, target);
                    addToFolder(target, moving);
                } else if (target.type == Item.TYPE_APP && moving.type == Item.TYPE_APP
                        && isCentreDrop() && !target.key().equals(moving.key())) {
                    makeFolderAt(hitPage, hitCell, target, moving);
                } else {
                    putHome(hitPage, hitCell, moving);
                    int at = Math.max(0, Math.min(dragSrcDock, store.dock.size()));
                    store.dock.add(at, target);
                }
            }
        }
        store.trim();
        store.save();
    }

    private int capacity() {
        return Math.max(1, gridCols * gridRows);
    }

    private boolean isCentreDrop() {
        // Dropping on another icon should make a folder. Only the extreme edges
        // of the target cell swap positions, so the common "drop on the icon"
        // gesture always does the obvious thing.
        return dropFraction >= 0.12f && dropFraction <= 0.88f;
    }

    /** Remove the item at a cell, leaving the cell empty. */
    private Item takeHome(int page, int cell) {
        if (page < 0 || cell < 0) return null;
        List<Item> list = store.page(page);
        if (cell >= list.size()) return null;
        Item it = list.get(cell);
        if (it != null) list.set(cell, null);
        return it;
    }

    private void putHome(int page, int cell, Item item) {
        if (page < 0 || cell < 0 || item == null) return;
        List<Item> list = store.page(page);
        while (list.size() <= cell) list.add(null);
        list.set(cell, item);
    }

    private void makeFolderAt(int page, int cell, Item target, Item moving) {
        if (target == null || moving == null
                || target.type != Item.TYPE_APP || moving.type != Item.TYPE_APP
                || target.key().equals(moving.key())) {
            return;
        }
        List<Item> kids = new ArrayList<>();
        kids.add(target.copy());
        kids.add(moving.copy());
        if (kids.size() < 2) return;
        justCreatedFolder = true;
        Item f = Item.folder("Folder", kids);
        lastFolderKey = f.key();
        putHome(page, cell, f);
    }

    /**
     * Folders must contain at least two apps. If removing an app leaves a
     * one-app folder, put that app back on the grid instead of keeping a
     * pointless folder around.
     */
    private void ensureFoldersHaveTwoApps() {
        boolean changed = false;
        for (List<Item> page : store.pages) {
            for (int i = 0; i < page.size(); i++) {
                Item it = page.get(i);
                if (it == null || it.type != Item.TYPE_FOLDER || it.children.size() >= 2) {
                    continue;
                }
                if (it.children.size() == 1) page.set(i, it.children.get(0).copy());
                else page.set(i, null);
                changed = true;
            }
        }
        for (int i = 0; i < store.dock.size(); i++) {
            Item it = store.dock.get(i);
            if (it == null || it.type != Item.TYPE_FOLDER || it.children.size() >= 2) {
                continue;
            }
            if (it.children.size() == 1) store.dock.set(i, it.children.get(0).copy());
            else store.dock.remove(i--);
            changed = true;
        }
        if (changed) {
            store.trim();
            store.save();
        }
    }

    /** Place an item that is not already on the page (drawer / folder source). */
    private void placeNewOnHome(Item moving, int page, int cell) {
        if (moving == null || page < 0 || cell < 0) return;
        commitDropWidget();
        Item target = takeHome(page, cell);
        if (target == null) {
            putHome(page, cell, moving);
        } else if (target.type == Item.TYPE_FOLDER) {
            putHome(page, cell, target);
            addToFolder(target, moving);
        } else if (target.type == Item.TYPE_APP && moving.type == Item.TYPE_APP
                && isCentreDrop() && !target.key().equals(moving.key())) {
            makeFolderAt(page, cell, target, moving);
        } else {
            putHome(page, cell, moving);
            int dest = nearestFreeCellExcept(page, cell, cell);
            if (dest >= 0) putHome(page, dest, target);
            else store.addToHome(target, capacity());
        }
    }

    private void commitDropWidget() {
        if (dropWidget == null || dropWidgetDestCell < 0) return;
        dropWidget.cell = dropWidgetDestCell;
        dropWidget = null;
        dropWidgetDestCell = -1;
    }

    /** Add an app (or another folder's children) to a folder, avoiding duplicates. */
    private void addToFolder(Item folder, Item app) {
        if (folder == null || folder.type != Item.TYPE_FOLDER || app == null) return;
        if (app.type == Item.TYPE_FOLDER) {
            List<Item> kids = new ArrayList<>(app.children);
            for (Item c : kids) addToFolder(folder, c);
            return;
        }
        for (Item c : folder.children) {
            if (c.key().equals(app.key())) return;
        }
        folder.children.add(app.copy());
    }

    /**
     * A drag that started in the app drawer. The drawer item is a template: it is copied
     * into the home grid or the dock and stays in the drawer.
     */
    private void applyDrawerDrop() {
        if (dragSource == null || dragSource.item == null) return;
        Item item = dragSource.item.copy();
        if (hitKind == HIT_HOME && hitCell >= 0) {
            placeNewOnHome(item, hitPage, hitCell);
        } else if (hitKind == HIT_DOCK) {
            int max = prefs.dockCount();
            int idx = Math.max(0, Math.min(hitDock, store.dock.size()));
            if (store.dock.size() >= max) {
                Item displaced = store.dock.set(Math.min(idx, max - 1), item);
                if (displaced != null) store.addToHome(displaced, capacity());
            } else {
                store.dock.add(idx, item);
            }
        } else {
            return;
        }
        store.trim();
        store.save();
    }

    private void beginDragFromDrawer(ItemView v) {
        beginDrag(v);
        // Reveal the home while the drawer fades; the item view stays attached and keeps
        // receiving the gesture until it is released.
        if (drawer != null) {
            drawer.animate().alpha(0f).setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
        content.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    private void restoreDrawerAfterDrag() {
        if (drawer == null) return;
        drawer.animate().alpha(1f).setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        content.animate().scaleX(0.945f).scaleY(0.945f).alpha(0.5f)
                .setDuration(170)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    // ------------------------------------------------------------------
    // Folder drag-out
    // ------------------------------------------------------------------

    private void beginDragFromFolder(ItemView v) {
        dragFolder = openFolderItem;
        dragChild = v.item;
        beginDrag(v);
        if (folderOverlay != null) {
            folderOverlay.animate().alpha(0f).setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
    }

    // ------------------------------------------------------------------
    // Clock widget drag
    // ------------------------------------------------------------------

    private int clockDropPage = -1, clockDropRow = -1, clockDropCol = -1;
    private boolean clockDropTrash;

    private void startClockDrag() {
        if (clockBox == null || clockDragging) return;
        Haptics.pickup(clockBox);
        clockDragging = true;
        dragging = true;
        pager.setLocked(true);
        dismissActiveMenu();
        clockBox.animate().scaleX(1f).scaleY(1f).setDuration(80).start();

        int w = Math.max(1, clockBox.getWidth());
        int h = Math.max(1, clockBox.getHeight());
        if (clockDragBitmap != null) clockDragBitmap.recycle();
        clockDragBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        clockBox.draw(new Canvas(clockDragBitmap));

        dragView = new ImageView(this);
        dragView.setImageBitmap(clockDragBitmap);
        dragView.setAlpha(0.95f);
        dragView.setElevation(Ui.dpf(this, 30));
        int dw = Math.max(1, Math.round(w * 0.8f));
        int dh = Math.max(1, Math.round(h * 0.8f));
        root.addView(dragView, new FrameLayout.LayoutParams(dw, dh));
        clockBox.setAlpha(0.25f);
        ensureTrash();
    }

    private void moveClockDrag(float rawX, float rawY) {
        if (dragView == null) return;
        int w = dragView.getWidth();
        int h = dragView.getHeight();
        dragView.setX(rawX - root.getLeft() - w / 2f);
        dragView.setY(rawY - root.getTop() - h / 2f);
        computeClockTarget(rawX, rawY);
        updateClockHighlight();
    }

    private void computeClockTarget(float rawX, float rawY) {
        clockDropTrash = false;
        clockDropPage = -1;
        clockDropRow = -1;
        clockDropCol = -1;

        if (trash != null && trash.getParent() != null) {
            int[] tv = new int[2];
            trash.getLocationOnScreen(tv);
            if (rawX >= tv[0] - Ui.dp(this, 20)
                    && rawX <= tv[0] + trash.getWidth() + Ui.dp(this, 20)
                    && rawY >= tv[1] - Ui.dp(this, 24)
                    && rawY <= tv[1] + trash.getHeight() + Ui.dp(this, 24)) {
                clockDropTrash = true;
                return;
            }
        }

        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        if (rawX < pl[0] - Ui.dp(this, 30)
                || rawX > pl[0] + pager.getWidth() + Ui.dp(this, 30)) return;
        if (rawY < pl[1] - Ui.dp(this, 40)
                || rawY > pl[1] + pager.getHeight() + Ui.dp(this, 40)) return;

        int cols = gridCols;
        int rows = gridRows;
        int pw = Math.max(1, pager.pageWidth());
        float px = rawX - pl[0] + pager.getScrollX();
        int page = (int) Math.floor(px / pw);
        page = Math.max(0, Math.min(pager.pageCount() - 1, page));
        float lx = px - page * pw;
        float ly = rawY - pl[1];
        View pageView = pager.getChildAt(page);
        if (!(pageView instanceof GridPage)) return;
        GridPage gp = (GridPage) pageView;
        int spanW = clockSpanCols * gp.cellWidth();
        int spanH = clockSpanRows * gp.cellHeight();
        int cell = gp.nearestCell(lx - spanW / 2f, ly - spanH / 2f);
        int col = cell % cols;
        int row = cell / cols;
        clockDropCol = Math.max(0, Math.min(cols - clockSpanCols, col));
        clockDropRow = Math.max(0, Math.min(rows - clockSpanRows, row));
        if (clockOverlapsWidgets(page, clockDropCol, clockDropRow,
                clockSpanCols, clockSpanRows)) {
            clockDropPage = -1;
            clockDropRow = -1;
            clockDropCol = -1;
            return;
        }
        clockDropPage = page;
    }

    private void updateClockHighlight() {
        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (!(c instanceof GridPage)) continue;
            if (clockDropPage == i) {
                ((GridPage) c).setHighlight(
                        clockDropRow * gridCols + clockDropCol,
                        clockSpanCols, clockSpanRows);
            } else {
                ((GridPage) c).setHighlight(-1);
            }
        }
        if (dock != null) dock.setDropIndex(-1);
    }

    private void endClockDrag(float rawX, float rawY) {
        Haptics.drop(root);
        computeClockTarget(rawX, rawY);
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        final boolean toTrash = clockDropTrash;
        final int page = clockDropPage;
        final int row = clockDropRow;
        final int col = clockDropCol;

        if (dragView == null) {
            finishClockDrag();
            return;
        }

        Runnable done = new Runnable() {
            @Override
            public void run() {
                if (toTrash) {
                    prefs.setClockVisible(false);
                } else if (page >= 0) {
                    prefs.setClockCell(page, row, col);
                }
                finishClockDrag();
                renderHome();
            }
        };

        if (toTrash && trash != null) {
            int[] tv = new int[2];
            trash.getLocationOnScreen(tv);
            float tx = tv[0] + trash.getWidth() / 2f - rl[0] - dragView.getWidth() / 2f;
            float ty = tv[1] + trash.getHeight() / 2f - rl[1] - dragView.getHeight() / 2f;
            dragView.animate().x(tx).y(ty).scaleX(0.2f).scaleY(0.2f).alpha(0f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(done).start();
        } else if (page >= 0) {
            RectF target = clockTargetRect(page, row * gridCols + col);
            if (target == null) {
                done.run();
                return;
            }
            float sx = Math.max(0.2f, target.width() / dragView.getWidth());
            float sy = Math.max(0.2f, target.height() / dragView.getHeight());
            dragView.animate()
                    .x(target.centerX() - rl[0] - dragView.getWidth() / 2f)
                    .y(target.centerY() - rl[1] - dragView.getHeight() / 2f)
                    .scaleX(sx).scaleY(sy).alpha(0.9f)
                    .setDuration(190)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(done).start();
        } else {
            RectF target = clockCurrentRect();
            if (target == null) {
                done.run();
                return;
            }
            dragView.animate()
                    .x(target.centerX() - rl[0] - dragView.getWidth() / 2f)
                    .y(target.centerY() - rl[1] - dragView.getHeight() / 2f)
                    .scaleX(1f).scaleY(1f).alpha(0.95f)
                    .setDuration(160)
                    .withEndAction(done).start();
        }
    }

    private RectF clockTargetRect(int page, int cell) {
        if (page < 0 || page >= pager.getChildCount()) return null;
        View c = pager.getChildAt(page);
        if (!(c instanceof GridPage)) return null;
        RectF r = new RectF();
        ((GridPage) c).cellRect(cell, clockSpanCols, clockSpanRows, r);
        r.inset(Ui.dp(this, 10), Ui.dp(this, 10));
        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        float left = pl[0] - pager.getScrollX() + page * pager.pageWidth() + r.left;
        float top = pl[1] + r.top;
        return new RectF(left, top, left + r.width(), top + r.height());
    }

    private RectF clockCurrentRect() {
        if (clockBox == null || clockBox.getParent() == null) return null;
        int[] cl = new int[2];
        clockBox.getLocationOnScreen(cl);
        return new RectF(cl[0], cl[1], cl[0] + clockBox.getWidth(),
                cl[1] + clockBox.getHeight());
    }

    private void cancelClockDrag() {
        finishClockDrag();
    }

    private void finishClockDrag() {
        clockDragging = false;
        dragging = false;
        resetEdgeAutoScroll();
        removePageTurnHints();
        pager.setLocked(false);
        if (clockBox != null) clockBox.setAlpha(1f);
        if (dragView != null) {
            root.removeView(dragView);
            dragView = null;
        }
        if (trash != null && trash.getParent() != null) {
            root.removeView(trash);
        }
        if (clockDragBitmap != null) {
            clockDragBitmap.recycle();
            clockDragBitmap = null;
        }
        dragBitmap = null;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (c instanceof GridPage) ((GridPage) c).setHighlight(-1);
        }
    }

    private void restoreFolderAfterDrag() {
        if (folderOverlay != null) {
            folderOverlay.animate().alpha(1f).setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
    }

    /** Move an app out of the open folder onto the home, into the dock, or off the folder. */
    private void applyFolderDrop() {
        if (dragFolder == null || dragChild == null) return;
        if (hitKind == HIT_NONE) return;

        Item moving = detachChild(dragFolder, dragChild);
        if (moving == null) return;

        if (hitKind == HIT_HOME && hitCell >= 0) {
            commitDropWidget();
            Item target = takeHome(hitPage, hitCell);
            if (target == dragFolder) {
                putHome(hitPage, hitCell, target);
                addToFolder(target, moving);
            } else if (target == null) {
                putHome(hitPage, hitCell, moving);
            } else if (target.type == Item.TYPE_FOLDER) {
                putHome(hitPage, hitCell, target);
                addToFolder(target, moving);
            } else if (target.type == Item.TYPE_APP && moving.type == Item.TYPE_APP
                    && isCentreDrop() && !target.key().equals(moving.key())) {
                makeFolderAt(hitPage, hitCell, target, moving);
            } else {
                putHome(hitPage, hitCell, moving);
                store.addToHome(target, capacity());
            }
        } else if (hitKind == HIT_DOCK) {
            int max = prefs.dockCount();
            int idx = Math.max(0, Math.min(hitDock, store.dock.size()));
            if (store.dock.size() >= max) {
                Item displaced = store.dock.set(Math.min(idx, max - 1), moving);
                if (displaced != null) store.addToHome(displaced, capacity());
            } else {
                store.dock.add(idx, moving);
            }
        }
        // HIT_TRASH: the app is simply detached from the folder.

        if (dragFolder.children.isEmpty()) {
            store.removeFromHome(dragFolder.key());
            store.removeFromDock(dragFolder.key());
        }
        store.trim();
        store.save();
    }

    private Item detachChild(Item folder, Item app) {
        if (folder == null || app == null) return null;
        for (int i = 0; i < folder.children.size(); i++) {
            if (folder.children.get(i).key().equals(app.key())) {
                return folder.children.remove(i);
            }
        }
        return null;
    }

    private void cleanupDrag(boolean deferRender) {
        dragging = false;
        clearHoverPreview(false);
        dropWidget = null;
        dropWidgetDestCell = -1;
        resetEdgeAutoScroll();
        removePageTurnHints();
        pager.setLocked(false);
        // cleanupDrag used to call updateHighlights() with the just-dropped
        // cell still live. In the no-refresh spring-back path that left the
        // target cell's focus/highlight plate stuck on screen.
        hitKind = HIT_NONE;
        hitPage = -1;
        hitCell = -1;
        hitDock = -1;
        updateHighlights();
        dock.setDropIndex(-1);
        for (int i = 0; i < pager.getChildCount(); i++) {
            View c = pager.getChildAt(i);
            if (c instanceof GridPage) {
                GridPage gp = (GridPage) c;
                for (ItemView v : gp.cellViews()) {
                    if (v != null) {
                        v.setVisibility(View.VISIBLE);
                        // On a spring-back, let resetDragSource() fade the
                        // original icon back in after the ghost lands instead
                        // of snapping it on under the flight.
                        v.setAlpha(skipRefreshAfterDrag && v == dragSource ? 0f : 1f);
                        v.setTranslationX(0f);
                        v.setTranslationY(0f);
                        v.setScaleX(1f);
                        v.setScaleY(1f);
                        v.setTranslationZ(0f);
                        if (skipRefreshAfterDrag) v.requestLayout();
                    }
                }
                if (skipRefreshAfterDrag) gp.requestLayout();
            }
        }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            c.setVisibility(View.VISIBLE);
            c.setAlpha(skipRefreshAfterDrag && c == dragSource ? 0f : 1f);
            c.setTranslationX(0f);
            c.setTranslationY(0f);
            c.setScaleX(1f);
            c.setScaleY(1f);
            c.setTranslationZ(0f);
        }
        if (drawerGrid != null) {
            for (int i = 0; i < drawerGrid.getChildCount(); i++) {
                View c = drawerGrid.getChildAt(i);
                c.setAlpha(skipRefreshAfterDrag && c == dragSource ? 0f : 1f);
                c.setTranslationX(0f);
                c.setTranslationY(0f);
                c.setScaleX(1f);
                c.setScaleY(1f);
                c.setTranslationZ(0f);
            }
        }
        if (dragView != null) {
            root.removeView(dragView);
            dragView = null;
        }
        if (dragBitmap != null) {
            dragBitmap.recycle();
            dragBitmap = null;
        }
        if (trash != null && trash.getParent() != null) {
            root.removeView(trash);
        }
        resetDragSource();
        handler.removeCallbacks(renderAfterDrag);
        boolean skip = skipRefreshAfterDrag;
        skipRefreshAfterDrag = false;
        if (skip) {
            // A spring-back never changed the store. Rebuilding here would make
            // the icon flash/snap under the returning drag ghost; the original
            // view is already in the right place. Just drop the temporary spare
            // page that was exposed while dragging.
            while (pager.getChildCount() > store.pageCount()) {
                pager.removeViewAt(pager.getChildCount() - 1);
            }
            int shown = Math.max(0, Math.min(
                    Math.max(0, store.pageCount() - 1), pager.currentPage()));
            pager.setPage(shown, false);
            updateDots(shown, pager.pageCount());
        } else if (deferRender) {
            // Rebuilding the pager from inside an item's touch handler would mutate the
            // view tree mid-dispatch, so defer it by a frame.
            handler.postDelayed(renderAfterDrag, 32);
        } else {
            refreshVisible();
        }
    }

    private final Runnable renderAfterDrag = new Runnable() {
        @Override
        public void run() {
            refreshVisible();
        }
    };

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    private void showHomeMenu(float rawX, float rawY) {
        Haptics.longPress(root);
        List<Menu.Entry> entries = new ArrayList<>();
        entries.add(new Menu.Entry("Launcher settings", new Runnable() {
            @Override
            public void run() {
                showSettingsDialog(rawX, rawY);
            }
        }));
        final boolean overlayShowing = prefs.perfOverlay()
                && PerfOverlayService.isOverlayPanelVisible();
        entries.add(new Menu.Entry(overlayShowing
                ? "Close performance overlay"
                : "Show performance overlay", new Runnable() {
            @Override
            public void run() {
                if (overlayShowing) {
                    prefs.setPerfOverlay(false);
                    PerfOverlayService.stop(MainActivity.this);
                    LToast.makeText(MainActivity.this, "Performance overlay closed",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    pendingPerfOverlay = true;
                    LToast.makeText(MainActivity.this,
                            "Allow Pavo to display over other apps",
                            Toast.LENGTH_LONG).show();
                    openOverlayPermission();
                    return;
                }
                prefs.setPerfOverlay(true);
                PerfOverlayService.start(MainActivity.this);
                PerfOverlayService.setOverlayVisible(true);
            }
        }));
        entries.add(new Menu.Entry("Add app shortcut", new Runnable() {
            @Override
            public void run() {
                showAppChooser();
            }
        }));
        entries.add(new Menu.Entry("Add web shortcut", new Runnable() {
            @Override
            public void run() {
                showWebDialog();
            }
        }));
        entries.add(new Menu.Entry("Change wallpaper", new Runnable() {
            @Override
            public void run() {
                showWallpaperCard();
            }
        }));
        entries.add(new Menu.Entry("Widgets", new Runnable() {
            @Override
            public void run() {
                showWidgetPicker();
            }
        }));
        entries.add(new Menu.Entry("Icon packs", new Runnable() {
            @Override
            public void run() {
                showIconPackPicker();
            }
        }));
        showMenuAt(rawX, rawY, entries);
    }

    private void showItemMenu(final ItemView v, boolean fromDrawer) {
        final Item item = v.item;
        if (item == null) return;
        Haptics.longPress(v);
        final String label;
        if (item.type == Item.TYPE_WEB) {
            label = item.title;
        } else if (item.type == Item.TYPE_FOLDER) {
            label = store.labelFor(item,
                    item.title != null && !item.title.isEmpty() ? item.title : "Folder");
        } else {
            label = v.entry != null ? v.entry.label : item.pkg;
        }

        List<Menu.Entry> entries = new ArrayList<>();
        if (item.type == Item.TYPE_FOLDER) {
            entries.add(new Menu.Entry("Open", new Runnable() {
                @Override
                public void run() {
                    openFolder(item, v);
                }
            }));
            entries.add(new Menu.Entry("Rename", new Runnable() {
                @Override
                public void run() {
                    renameDialog(item, label);
                }
            }));
            entries.add(new Menu.Entry("Unpack folder", new Runnable() {
                @Override
                public void run() {
                    unpackFolder(item);
                }
            }));
            entries.add(new Menu.Entry(v.dockIndex >= 0
                    ? "Remove from dock" : "Remove from home", new Runnable() {
                @Override
                public void run() {
                    removeItemView(v);
                }
            }).danger());
        } else if (item.type == Item.TYPE_APP) {
            final boolean builtin = isBuiltinApp(item.pkg);
            if (builtin) {
                entries.add(new Menu.Entry("Open", new Runnable() {
                    @Override
                    public void run() {
                        launch(item, v);
                    }
                }));
            } else {
                entries.add(new Menu.Entry("App info", new Runnable() {
                    @Override
                    public void run() {
                        Apps.appInfo(MainActivity.this,
                                Apps.entryFor(MainActivity.this, item.pkg, item.cls));
                    }
                }));
                entries.add(new Menu.Entry("Uninstall", new Runnable() {
                    @Override
                    public void run() {
                        uninstall(item);
                    }
                }).danger());
            }
            entries.add(new Menu.Entry("Rename", new Runnable() {
                @Override
                public void run() {
                    renameDialog(item, label);
                }
            }));
            entries.add(new Menu.Entry("Change icon", new Runnable() {
                @Override
                public void run() {
                    showIconChooser(item);
                }
            }));
            final int menuPage = pageOf(v);
            final int menuCell = v.cellIndex;
            final int menuDock = v.dockIndex;
            final boolean onDock = menuDock >= 0;
            final boolean onHome = menuPage >= 0 && menuCell >= 0;
            final boolean dockRoom = store.dock.size() < prefs.dockCount();

            if (onHome) {
                entries.add(new Menu.Entry("New folder with…", new Runnable() {
                    @Override
                    public void run() {
                        showNewFolderPicker(item, menuPage, menuCell);
                    }
                }));
            }

            if (onDock) {
                if (dockRoom) {
                    entries.add(new Menu.Entry("Add another to dock", new Runnable() {
                        @Override
                        public void run() {
                            store.addToDock(item, prefs.dockCount());
                            store.save();
                            refreshVisible();
                        }
                    }));
                }
                entries.add(new Menu.Entry("Remove from dock", new Runnable() {
                    @Override
                    public void run() {
                        store.removeDockAt(menuDock);
                        store.save();
                        refreshVisible();
                    }
                }).danger());
            } else if (dockRoom) {
                String dockLabel = store.dockContains(item.key())
                        ? "Add another to dock" : "Pin to dock";
                entries.add(new Menu.Entry(dockLabel, new Runnable() {
                    @Override
                    public void run() {
                        store.addToDock(item, prefs.dockCount());
                        store.save();
                        refreshVisible();
                    }
                }));
            }

            String homeLabel = onHome || store.contains(item.key())
                    ? "Add another to home" : "Add to home";
            entries.add(new Menu.Entry(homeLabel, new Runnable() {
                @Override
                public void run() {
                    store.addToHome(item, capacity());
                    store.save();
                    refreshVisible();
                }
            }));
            if (onHome) {
                entries.add(new Menu.Entry("Remove from home", new Runnable() {
                    @Override
                    public void run() {
                        takeHome(menuPage, menuCell);
                        store.trim();
                        store.save();
                        refreshVisible();
                    }
                }).danger());
            }
        } else {
            entries.add(new Menu.Entry("Open", new Runnable() {
                @Override
                public void run() {
                    launch(item, null);
                }
            }));
            entries.add(new Menu.Entry("Rename", new Runnable() {
                @Override
                public void run() {
                    renameDialog(item, label);
                }
            }));
            entries.add(new Menu.Entry("Change icon", new Runnable() {
                @Override
                public void run() {
                    showIconChooser(item);
                }
            }));
            entries.add(new Menu.Entry(v.dockIndex >= 0
                    ? "Remove from dock" : "Remove from home", new Runnable() {
                @Override
                public void run() {
                    removeItemView(v);
                }
            }).danger());
        }
        dismissActiveMenu();
        PopupWindow popup = Menu.show(this, captureBackdrop(false, 0), wallpaper.accent(),
                v, v.lastTouchX(), v.lastTouchY(), label, entries, false);
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (!dragging && dragSource == v) {
                    v.setLifted(false);
                    resetDragSource();
                }
            }
        });
        activeMenu = popup;
    }

    private void showFolderItemMenu(final ItemView v) {
        if (v == null || v.item == null) return;
        List<Menu.Entry> entries = new ArrayList<>();
        entries.add(new Menu.Entry("Open", new Runnable() {
            @Override
            public void run() {
                closeFolder();
                launch(v.item, null);
            }
        }));
        if (!isBuiltinApp(v.item.pkg)) {
            entries.add(new Menu.Entry("App info", new Runnable() {
                @Override
                public void run() {
                    Apps.appInfo(MainActivity.this,
                            Apps.entryFor(MainActivity.this, v.item.pkg, v.item.cls));
                }
            }));
        }
        entries.add(new Menu.Entry("Remove from folder", new Runnable() {
            @Override
            public void run() {
                removeFromFolder(openFolderItem, v.item);
            }
        }).danger());
        dismissActiveMenu();
        activeMenu = Menu.show(this, captureBackdrop(false, 0),
                wallpaper.accent(), v, v.lastTouchX(), v.lastTouchY(),
                "Folder", entries, false);
    }

    /** Remove exactly the icon that owns this menu, not every copy with the same key. */
    private void removeItemView(ItemView v) {
        if (v == null) return;
        if (v.dockIndex >= 0) {
            store.removeDockAt(v.dockIndex);
        } else {
            int page = pageOf(v);
            int cell = v.cellIndex;
            if (page >= 0 && cell >= 0) {
                takeHome(page, cell);
            } else if (v.item != null) {
                store.removeFromHome(v.item.key());
                store.removeFromDock(v.item.key());
            }
        }
        store.trim();
        store.save();
        refreshVisible();
    }

    private void showMenuAt(float rawX, float rawY, List<Menu.Entry> entries) {
        dismissActiveMenu();
        activeMenu = Menu.showAt(this, captureBackdrop(false, 0), wallpaper.accent(), root,
                Math.round(rawX), Math.round(rawY), null, entries, false);
    }

    private void dismissActiveMenu() {
        cancelStickRepeat();
        activeMenuRows.clear();
        menuRowsOwner = null;
        menuFocusRing = null;
        activeMenuSelection = -1;
        if (activeMenu != null) {
            activeMenu.dismiss();
            activeMenu = null;
        }
    }

    /** Rebuild the selectable rows when the active popup changes. */
    private void ensureMenuRows() {
        if (activeMenu == null || !activeMenu.isShowing()) {
            activeMenuRows.clear();
            menuRowsOwner = null;
            activeMenuSelection = -1;
            return;
        }
        if (menuRowsOwner == activeMenu && !activeMenuRows.isEmpty()) return;
        menuRowsOwner = activeMenu;
        activeMenuRows.clear();
        activeMenuSelection = -1;
        collectMenuRows(activeMenu.getContentView(), activeMenuRows);
        if (activeMenuSelection >= activeMenuRows.size()) activeMenuSelection = -1;
    }

    private void collectMenuRows(View v, List<View> out) {
        if (v == null) return;
        if (v.isClickable()) {
            if (v.isEnabled() && v instanceof TextView) out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectMenuRows(group.getChildAt(i), out);
            }
        }
    }

    private void navigateMenu(int dir) {
        ensureMenuRows();
        if (activeMenuRows.isEmpty()) return;
        int count = activeMenuRows.size();
        if (activeMenuSelection < 0) {
            activeMenuSelection = (dir == 2 || dir == 0) ? count - 1 : 0;
        } else if (dir == 2 || dir == 0) {
            activeMenuSelection = (activeMenuSelection - 1 + count) % count;
        } else {
            activeMenuSelection = (activeMenuSelection + 1) % count;
        }
        applyMenuSelection();
        Haptics.tick(activeMenuRows.get(activeMenuSelection));
    }

    private void activateMenuSelection() {
        ensureMenuRows();
        if (activeMenuRows.isEmpty()) {
            Haptics.key(root);
            return;
        }
        if (activeMenuSelection < 0) activeMenuSelection = 0;
        applyMenuSelection();
        View row = activeMenuRows.get(activeMenuSelection);
        if (row != null) row.performClick();
    }

    private void applyMenuSelection() {
        if (menuFocusRing != null) menuFocusRing.setBounds(0, 0, 0, 0);
        if (activeMenuSelection < 0 || activeMenuSelection >= activeMenuRows.size()) return;
        if (activeMenu == null || !activeMenu.isShowing()) return;
        View content = activeMenu.getContentView();
        if (!(content instanceof ViewGroup)) return;
        View target = activeMenuRows.get(activeMenuSelection);
        if (target == null) return;
        if (menuFocusRing == null) {
            menuFocusRing = new FocusRingDrawable(this, wallpaper.accent());
            try {
                content.getOverlay().add(menuFocusRing);
            } catch (Throwable t) {
                menuFocusRing = null;
                return;
            }
        }
        placeOverlayRing(menuFocusRing, content, target);
    }

    private void placeOverlayRing(FocusRingDrawable ring, View root, View target) {
        if (ring == null || root == null || target == null) return;
        int[] rootLoc = new int[2];
        int[] targetLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        target.getLocationOnScreen(targetLoc);
        int pad = Ui.dp(this, 2);
        int left = targetLoc[0] - rootLoc[0] - pad;
        int top = targetLoc[1] - rootLoc[1] - pad;
        ring.setBounds(left, top, left + target.getWidth() + pad * 2,
                top + target.getHeight() + pad * 2);
        ring.setRingRadius(FocusRingDrawable.radiusFor(target, this));
        ring.invalidateSelf();
    }

    private void uninstall(Item item) {
        try {
            Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.fromParts("package", item.pkg, null));
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, "Can't uninstall", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Deterministic folder creation: long-press an app, choose the second app
     * from a list, and the folder appears in place. No dragging onto another
     * icon, no drop-zone guessing.
     */
    private void showNewFolderPicker(final Item source, final int page, final int cell) {
        final List<Item> choices = new ArrayList<>();
        final java.util.HashSet<String> seen = new java.util.HashSet<>();
        seen.add(source.key());
        for (List<Item> pageItems : store.pages) {
            for (Item it : pageItems) {
                if (it == null || it.type != Item.TYPE_APP) continue;
                if (!seen.add(it.key())) continue;
                choices.add(it);
            }
        }
        for (Item it : store.dock) {
            if (it == null || it.type != Item.TYPE_APP) continue;
            if (!seen.add(it.key())) continue;
            choices.add(it);
        }
        if (choices.isEmpty()) {
            LToast.makeText(this, "Add another app to the home screen first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        GlassPanel panel = new GlassPanel(this, captureBackdrop(false, 0),
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setBackdropSaturation(prefs.folderSaturation() / 100f);
        panel.setBackdropDarkness(prefs.folderBackdropDarkness() / 100f);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 12));

        TextView title = new LTextView(this);
        title.setText("New folder with\u2026");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        TextView hint = new LTextView(this);
        hint.setText("Pick the app to join the new folder.");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        panel.addView(hint);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final Item other : choices) {
            TextView row = new LTextView(this);
            String label = appLabel(other);
            boolean onDock = store.dock.contains(other);
            row.setText(onDock ? label + "  ·  Dock" : label);
            row.setTextColor(0xF2FFFFFF);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 11),
                    Ui.dp(this, 12), Ui.dp(this, 11));
            row.setBackground(Ui.ripple(this, Ui.dpf(this, 10)));
            row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    dialog.dismiss();
                    createFolderWith(source, other, page, cell);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, 380), Math.min(Ui.dp(this, 340),
                        Ui.dp(this, 44) * Math.min(choices.size(), 6)));
        slp.topMargin = Ui.dp(this, 2);
        panel.addView(scroll, slp);

        TextView cancel = new LTextView(this);
        cancel.setText("Cancel");
        styleTextButton(cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        buttons.addView(cancel);
        panel.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private void createFolderWith(Item source, Item other, int page, int cell) {
        if (source == null || other == null || page < 0 || cell < 0) return;
        List<Item> items = store.page(page);
        Item at = cell < items.size() ? items.get(cell) : null;
        if (at != source) {
            LToast.makeText(this, "That app moved; try again", Toast.LENGTH_SHORT).show();
            return;
        }
        removeItemEverywhere(other);
        List<Item> kids = new ArrayList<>();
        kids.add(source.copy());
        kids.add(other.copy());
        justCreatedFolder = true;
        Item folder = Item.folder("Folder", kids);
        lastFolderKey = folder.key();
        while (items.size() <= cell) items.add(null);
        items.set(cell, folder);
        store.trim();
        store.save();
        refreshVisible();
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    openFolder(folder, null);
                }
            }
        }, 260L);
    }

    /** Friendly name for a home/dock item, with any custom rename applied. */
    private String appLabel(Item item) {
        if (item == null) return "";
        if (item.type == Item.TYPE_WEB) {
            return store.labelFor(item, item.title != null && !item.title.isEmpty()
                    ? item.title : "Shortcut");
        }
        if (item.type == Item.TYPE_FOLDER) {
            return store.labelFor(item, item.title != null && !item.title.isEmpty()
                    ? item.title : "Folder");
        }
        Apps.Entry e = findEntry(item.pkg, item.cls);
        if (e == null) e = Apps.entryFor(this, item.pkg, item.cls);
        String fallback = e != null && e.label != null && !e.label.isEmpty()
                ? e.label : item.pkg;
        return store.labelFor(item, fallback);
    }

    private boolean removeItemEverywhere(Item target) {
        for (List<Item> pageItems : store.pages) {
            for (int i = 0; i < pageItems.size(); i++) {
                if (pageItems.get(i) == target) {
                    pageItems.set(i, null);
                    return true;
                }
            }
        }
        for (int i = 0; i < store.dock.size(); i++) {
            if (store.dock.get(i) == target) {
                store.dock.remove(i);
                return true;
            }
        }
        return false;
    }

    private void renameDialog(final Item item, String current) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Rename");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        final EditText input = new EditText(this);
        input.setText(current == null ? "" : current);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x66FFFFFF);
        input.setHint(Loc.t(this, "Name"));
        input.setBackground(Ui.rounded(0x1AFFFFFF, Ui.dpf(this, 14), 0x33FFFFFF, Ui.dpf(this, 1)));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = Ui.dp(this, 12);
        panel.addView(input, ilp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);

        TextView reset = new LTextView(this);
        reset.setText("Reset");
        styleTextButton(reset, false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setLabel(item, null);
                dialog.dismiss();
                refreshVisible();
            }
        });
        buttons.addView(reset);

        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView ok = new LTextView(this);
        ok.setText("Save");
        styleTextButton(ok, true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.setLabel(item, input.getText().toString());
                dialog.dismiss();
                refreshVisible();
            }
        });
        buttons.addView(ok);
        panel.addView(buttons);

        showGlassDialog(dialog, panel, backdrop, 430, 0f, true);
        dialog.show();
        input.requestFocus();
        input.setSelection(input.getText().length());
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void showWebDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("New web shortcut");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        final EditText name = new EditText(this);
        name.setHint(Loc.t(this, "Name"));
        name.setSingleLine(true);
        name.setImeOptions(EditorInfo.IME_ACTION_NEXT
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        name.setTextColor(0xFFFFFFFF);
        name.setHintTextColor(0x66FFFFFF);
        name.setBackground(Ui.rounded(0x1AFFFFFF, Ui.dpf(this, 14), 0x33FFFFFF, Ui.dpf(this, 1)));
        name.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 380), ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(this, 12);
        panel.addView(name, nlp);

        final EditText url = new EditText(this);
        url.setHint("https://example.com");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        url.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        url.setTextColor(0xFFFFFFFF);
        url.setHintTextColor(0x66FFFFFF);
        url.setBackground(Ui.rounded(0x1AFFFFFF, Ui.dpf(this, 14), 0x33FFFFFF, Ui.dpf(this, 1)));
        url.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                Ui.dp(this, 380), ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = Ui.dp(this, 10);
        panel.addView(url, ulp);
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence pasted = clipboard.getPrimaryClip().getItemAt(0)
                        .coerceToText(this);
                String text = pasted == null ? "" : pasted.toString().trim();
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    url.setText(text);
                }
            }
        } catch (Throwable ignored) {
        }

        TextView add = new LTextView(this);
        add.setText("Add");
        styleTextButton(add, true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(this, 14);
        alp.gravity = Gravity.END;
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = url.getText().toString().trim();
                if (u.isEmpty()) {
                    LToast.makeText(MainActivity.this, "Enter a URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                String n = name.getText().toString().trim();
                if (n.isEmpty()) n = u;
                placeNewItem(Item.web(u, n));
                store.save();
                dialog.dismiss();
                refreshVisible();
            }
        });
        panel.addView(add, alp);

        showGlassDialog(dialog, panel, backdrop, 460, 0f, true);
        dialog.show();
        name.requestFocus();
    }

    private void showAppChooser() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Add app to home");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setVerticalSpacing(Ui.dp(this, 6));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return allApps.size();
            }

            @Override
            public Object getItem(int position) {
                return allApps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final Apps.Entry e = allApps.get(position);
                ItemView v = new ItemView(MainActivity.this);
                v.setIconDp(prefs.iconDp());
                v.dragEnabled = false;
                v.listener = null;
                Item item = Item.app(e.pkg, e.activity);
                Bitmap themed = store.iconFor(item);
                if (themed == null && iconPack != null) themed = iconPack.iconFor(e.pkg, e.activity);
                v.bind(item, e, themed, store.labelFor(item, e.label), true);
                v.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Item item = Item.app(e.pkg, e.activity);
                        store.addToHome(item, capacity());
                        store.save();
                        dialog.dismiss();
                        refreshVisible();
                        LToast.makeText(MainActivity.this, e.label + " added",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                return v;
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                Ui.dp(this, 640), 0, 1f);
        glp.topMargin = Ui.dp(this, 10);
        panel.addView(grid, glp);

        showGlassDialog(dialog, panel, backdrop, 680, 0.84f);
        dialog.show();
    }

    private void styleTextButton(TextView v, boolean primary) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        if (primary) {
            v.setTextColor(0xFF0B0D12);
            v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            v.setBackground(Ui.rounded(0xFFF2F4F8, Ui.dpf(this, 20)));
        } else {
            v.setTextColor(0xE6FFFFFF);
            v.setBackground(Ui.rounded(0x14FFFFFF, Ui.dpf(this, 20), 0x22FFFFFF, Ui.dpf(this, 1)));
        }
    }

    // ------------------------------------------------------------------
    // App drawer
    // ------------------------------------------------------------------

    /** Use the preferred drawer columns unless the current display is too narrow. */
    private int effectiveDrawerColumns() {
        int preferred = Math.max(4, Math.min(9, prefs.drawerCols()));
        int width = getResources().getDisplayMetrics().widthPixels;
        if (drawerPopup) width = Math.round(width * 0.72f);
        // Leave room for the card inset, panel padding and grid padding. The
        // minimum cell is driven by the longest app labels, not just the icon.
        int avail = width - Ui.dp(this, drawerPopup ? 44 : 76);
        int cell = Math.max(1, Ui.dp(this, 90));
        int fit = Math.max(4, avail / cell);
        return Math.max(4, Math.min(preferred, fit));
    }

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        drawer.setAlpha(0f);
        drawer.setElevation(Ui.dpf(this, 32));

        // Transparent scrim: still receives outside taps, but never darkens the home.
        drawerScrim = new DrawerScrimView(this, 0x00000000, Ui.dpf(this, 26));
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Only the visible margin around the card should dismiss it.
                if (drawerPopup) closeDrawer();
            }
        });
        drawer.addView(drawerScrim, match());

        drawerShadow = new LiquidGlassShadowView(this);
        drawerShadow.setRadiusDp(26);
        drawer.addView(drawerShadow);

        drawerCard = new RoundedClipFrameLayout(this);
        ((RoundedClipFrameLayout) drawerCard).setRadiusDp(26);
        drawerCard.setClipToOutline(true);
        drawerCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Ui.dpf(MainActivity.this, 26));
            }
        });
        drawer.addView(drawerCard);
        drawerScrim.setCard(drawerCard);
        // Taps on the card itself must never leak through to the dim scrim and close the
        // popup. While the search keyboard is up, they only put the keyboard away.
        drawerCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
            }
        });

        // Same clear Liquid Glass body as the built-in Performance Logs card. The
        // backdrop is a blurred screen sample, drawn fully opaque; the transparency
        // slider only fades the tint and lighting above it.
        drawerGlass = new GlassPanel(this, null,
                wallpaper != null ? wallpaper.accent() : ThemeColors.accent(),
                0x4D0B0E14, 26, true);
        drawerGlass.setBackdropScreenAnchored(true);
        drawerGlass.setClearGlass(true);
        drawerGlass.setClearBodyAlpha(0);
        drawerGlass.setSurfaceAlpha(prefs.appDrawerTransparency() / 100f);
        drawerBackdrop = new LiveBackdropView(this);
        drawerBackdrop.setRadiusDp(26);
        drawerBackdrop.setAlpha(1f);
        drawerBackdrop.setGlassTuning(prefs.drawerSaturation() / 100f,
                prefs.drawerBackdropDarkness() / 100f);
        drawerGlass.setBackdropSaturation(prefs.drawerSaturation() / 100f);
        drawerGlass.setBackdropDarkness(prefs.drawerBackdropDarkness() / 100f);
        drawerCard.addView(drawerBackdrop, match());

        drawerCard.addView(drawerGlass, match());

        drawerHighlight = new GlassHighlightView(this);
        drawerHighlight.setRadiusDp(26);
        drawerCard.addView(drawerHighlight, match());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), 0);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0x80FFFFFF);
        search.setHint(Loc.t(this, "Search apps"));
        search.setBackground(getDrawable(R.drawable.bg_search));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(
                wallpaper != null ? wallpaper.accent() : ThemeColors.accent()));
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setPadding(Ui.dp(this, 14), Ui.dp(this, 0), Ui.dp(this, 12), Ui.dp(this, 0));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                hideKeyboard();
                return true;
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_HORIZONTAL);
        searchRow.addView(search, new LinearLayout.LayoutParams(
                Ui.dp(this, 360), Ui.dp(this, 42)));
        panel.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        drawerAdapter = new DrawerAdapter();
        drawerGrid = new GridView(this);
        drawerGrid.setNumColumns(effectiveDrawerColumns());
        drawerGrid.setVerticalSpacing(Ui.dp(this, 6));
        drawerGrid.setHorizontalSpacing(0);
        drawerGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        drawerGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        drawerGrid.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 20));
        drawerGrid.setClipToPadding(true);
        drawerGrid.setClipChildren(true);
        drawerGrid.setAdapter(drawerAdapter);
        drawerGrid.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        panel.addView(drawerGrid, glp);

        drawerCard.addView(panel, match());

        emptyView = new LTextView(this);
        emptyView.setText("No apps found");
        emptyView.setTextColor(0x80FFFFFF);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyView.setVisibility(View.GONE);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        drawer.addView(emptyView, elp);
    }

    private void applyDrawerMode() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        FrameLayout.LayoutParams lp;
        if (drawerPopup) {
            int w = Math.round(dm.widthPixels * 0.72f);
            int h = Math.round(dm.heightPixels * 0.80f);
            int pos = prefs.dockPosition();
            int margin = Ui.dp(this, 24);
            int left = (dm.widthPixels - w) / 2;
            int top = (dm.heightPixels - h) / 2;
            int[] anchor = dockDrawerButtonCentre();
            if (pos == Prefs.DOCK_LEFT || pos == Prefs.DOCK_RIGHT) {
                int sideGap = Ui.dp(this, 96);
                left = pos == Prefs.DOCK_LEFT ? sideGap : dm.widthPixels - w - sideGap;
                if (anchor != null) top = Math.round(anchor[1] - h / 2f);
            } else if (anchor != null) {
                // Bottom dock: the pill follows the drawer button horizontally.
                left = Math.round(anchor[0] - w / 2f);
            }
            left = Math.max(margin, Math.min(left, dm.widthPixels - w - margin));
            top = Math.max(margin, Math.min(top, dm.heightPixels - h - margin));
            lp = new FrameLayout.LayoutParams(w, h);
            lp.leftMargin = left;
            lp.topMargin = top;
        } else {
            // The full drawer is a large inset card, exactly like a built-in app,
            // rather than a full-screen glass sheet.
            int inset = Ui.dp(this, 16);
            lp = new FrameLayout.LayoutParams(
                    dm.widthPixels - inset * 2, dm.heightPixels - inset * 2);
            lp.leftMargin = inset;
            lp.topMargin = inset;
            lp.leftMargin = inset;
            lp.topMargin = inset;
        }
        drawerCard.setBackground(null);
        drawerCard.setLayoutParams(lp);
        boolean full = !drawerPopup;
        if (drawerCard instanceof RoundedClipFrameLayout) {
            ((RoundedClipFrameLayout) drawerCard).setRadiusDp(26f);
        }
        if (drawerGlass != null) {
            drawerGlass.setRadiusDp(26f);
            // No white rim on the full drawer, but keep the rounded card.
            drawerGlass.setRimEnabled(false);
        }
        if (drawerBackdrop != null) drawerBackdrop.setRadiusDp(26f);
        if (drawerHighlight != null) {
            drawerHighlight.setRadiusDp(26f);
            drawerHighlight.setVisibility(View.GONE);
        }
        if (drawerShadow != null) {
            drawerShadow.setRadiusDp(26f);
            drawerShadow.setVisibility(full ? View.GONE : View.VISIBLE);
        }
        drawerCard.setClipToOutline(true);
        drawerScrim.setCutoutRadius(Ui.dpf(this, 26));
        if (drawerShadow != null) {
            int sp = (int) Math.ceil(drawerShadow.requiredPadding());
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    lp.width + sp * 2, lp.height + sp * 2);
            slp.leftMargin = lp.leftMargin - sp;
            slp.topMargin = lp.topMargin - sp;
            drawerShadow.setLayoutParams(slp);
            drawerShadow.setAlpha(drawerPopup ? 0f : 1f);
        }
        drawerScrim.setCutoutRadius(Ui.dpf(this, 26));
        drawerGrid.setNumColumns(drawerPopup
                ? Math.min(5, effectiveDrawerColumns()) : effectiveDrawerColumns());
        if (drawerGlass != null) {
            drawerGlass.setClearGlass(prefs.drawerGlass());
            drawerGlass.setClearBodyAlpha(0);
            }
        applyDrawerSurfaceTransparency();
    }

    private void applyDockGlassTuning() {
        if (dock != null) {
            dock.setGlassTuning(prefs.dockSaturation() / 100f,
                    prefs.dockBackdropDarkness() / 100f);
        }
    }

    private void applyDrawerGlassTuning() {
        float saturation = prefs.drawerSaturation() / 100f;
        float darkness = prefs.drawerBackdropDarkness() / 100f;
        if (drawerBackdrop != null) drawerBackdrop.setGlassTuning(saturation, darkness);
        if (drawerGlass != null) {
            drawerGlass.setBackdropSaturation(saturation);
            drawerGlass.setBackdropDarkness(darkness);
        }
    }

    private void applyFolderGlassTuning() {
        if (folderGlass != null) {
            folderGlass.setBackdropSaturation(prefs.folderSaturation() / 100f);
            folderGlass.setBackdropDarkness(prefs.folderBackdropDarkness() / 100f);
        }
    }

    private void applyMenuGlassTuning() {
        float saturation = prefs.menuSaturation() / 100f;
        float darkness = prefs.menuBackdropDarkness() / 100f;
        if (settingsGlass != null) {
            settingsGlass.setBackdropSaturation(saturation);
            settingsGlass.setBackdropDarkness(darkness);
        }
        if (settingsSubmenuGlass != null) {
            settingsSubmenuGlass.setBackdropSaturation(saturation);
            settingsSubmenuGlass.setBackdropDarkness(darkness);
        }
    }

    private void applySurfaceTransparency() {
        if (dock != null) dock.setSurfaceAlpha(prefs.dockTransparency() / 100f);
        applyDrawerSurfaceTransparency();
    }

    private void applyDrawerSurfaceTransparency() {
        float alpha = (drawerPopup ? prefs.popupDrawerTransparency()
                : prefs.appDrawerTransparency()) / 100f;
        if (drawerGlass != null) drawerGlass.setSurfaceAlpha(alpha);
    }

    private void openDrawer() {
        // Swipe-up always opens the full drawer; the dock button controls the popup.
        openDrawer(false);
    }

    private void openDrawer(boolean popup) {
        if (drawer == null || drawer.getVisibility() == View.VISIBLE) return;
        Haptics.key(root);
        if (!prepareDrawer(popup)) return;
        cancelDrawerSlide();
        drawerSwipeActive = false;

        if (drawerPopup) {
            final int openToken = drawerAnimationToken;
            // Animate the real live card instead of a snapshot lamp, so the glass
            // backdrop stays live from the first frame through the end of the
            // opening transition.
            drawerCard.setVisibility(View.VISIBLE);
            drawerCard.setAlpha(0f);
            drawerCard.setScaleX(0.08f);
            drawerCard.setScaleY(0.08f);
            drawerCard.post(new Runnable() {
                @Override
                public void run() {
                    if (openToken == drawerAnimationToken) animatePopupCard(true);
                }
            });
        } else {
            drawerCard.setVisibility(View.VISIBLE);
            drawerSwipeProgress = 1f;
            drawer.setAlpha(0.82f);
            drawer.setTranslationY(Ui.dpf(this, 30));
            drawer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(330)
                    .setInterpolator(new OvershootInterpolator(0.45f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            bounceDrawerCard();
                        }
                    })
                    .start();
        }
        if (controllerActive) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                        focusFirstInGrid(drawerGrid);
                    }
                }
            }, drawerPopup ? 540 : 360);
        }
    }

    /** Show and populate the drawer without animating it. */
    private boolean prepareDrawer(boolean popup) {
        if (drawer == null) return false;
        if (drawer.getVisibility() == View.VISIBLE) return false;
        drawerCloseSwipeActive = false;
        drawerCloseArmed = false;
        recycleDrawerCloseVelocity();
        rememberControllerFocus();
        // Keep the side dock from drawing over the popup card.
        if (drawer.getParent() instanceof ViewGroup) {
            ((ViewGroup) drawer.getParent()).bringChildToFront(drawer);
        }
        drawerPopup = popup;
        ++drawerAnimationToken;
        hideSystemBars();
        cancelPendingGestures();

        search.setText("");
        pruneMissingAllApps();
        filter("");

        int statsVisibility = statsText != null ? statsText.getVisibility() : View.GONE;
        int batteryVisibility = batteryPill != null ? batteryPill.getVisibility() : View.GONE;
        int statusVisibility = headerStatusCluster != null
                ? headerStatusCluster.getVisibility() : View.GONE;
        if (statsText != null) statsText.setVisibility(View.INVISIBLE);
        if (batteryPill != null) batteryPill.setVisibility(View.INVISIBLE);
        if (headerStatusCluster != null) headerStatusCluster.setVisibility(View.INVISIBLE);
        if (statsText != null) statsText.setVisibility(statsVisibility);
        if (batteryPill != null) batteryPill.setVisibility(batteryVisibility);
        if (headerStatusCluster != null) headerStatusCluster.setVisibility(statusVisibility);
        // Blur the live home layer behind the transparent glass, like the
        // backdrop-filter used by liquid-glass-react. Nothing is frozen, so the
        // material keeps reacting to the page underneath.
        drawerGlass.setBackdrop(null);
        applyDrawerMode();
        setHomeLiveBlur(true);

        search.setAlpha(1f);
        search.setTranslationY(0f);
        drawerGrid.setAlpha(1f);
        drawerGrid.setTranslationY(0f);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(1f);
        drawer.setTranslationY(0f);
        drawer.setScaleX(1f);
        drawer.setScaleY(1f);
        drawerScrim.setAlpha(drawerPopup ? 0f : 1f);
        drawerCard.setVisibility(View.VISIBLE);
        if (drawerShadow != null) {
            drawerShadow.setVisibility(View.VISIBLE);
            drawerShadow.setAlpha(drawerPopup ? 0f : 1f);
        }
        drawerCard.setScaleX(1f);
        drawerCard.setScaleY(1f);
        drawerSwipeProgress = drawerPopup ? 1f : 0f;
        return true;
    }

    private void applyDrawerSwipeProgress(float progress) {
        drawerSwipeProgress = Math.max(0f, Math.min(1f, progress));
        if (drawer == null) return;
        float height = drawer.getHeight() > 0 ? drawer.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        drawer.setTranslationY((1f - drawerSwipeProgress) * height);
        float cardScale = 0.96f + 0.04f * drawerSwipeProgress;
        float cw = drawerCard.getWidth() > 0 ? drawerCard.getWidth()
                : (root != null ? root.getWidth() : 0);
        float ch = drawerCard.getHeight() > 0 ? drawerCard.getHeight()
                : (root != null ? root.getHeight() : 0);
        if (cw > 0 && ch > 0) {
            drawerCard.setPivotX(cw / 2f);
            drawerCard.setPivotY(ch / 2f);
        }
        drawerCard.setScaleX(cardScale);
        drawerCard.setScaleY(cardScale);
        drawerScrim.invalidate();
    }

    private void bounceDrawerCard() {
        Motion.giggleIn(drawerCard, 0.975f, 360L, 1.20f, 0.65f, false);
        ValueAnimator sync = ValueAnimator.ofFloat(0f, 1f);
        sync.setDuration(400L);
        sync.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                drawerScrim.invalidate();
            }
        });
        sync.start();
    }

    private void settleDrawer(final boolean open, float velocityY) {
        cancelDrawerSlide();
        if (drawer == null) return;
        final float from = drawerSwipeProgress;
        final float to = open ? 1f : 0f;
        float height = drawer.getHeight() > 0 ? drawer.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        float distance = Math.abs(to - from) * height;
        float speed = Math.max(Math.abs(velocityY), 1100f);
        long duration = Math.max(120L, Math.min(380L,
                Math.round(distance / speed * 1000f) + 90L));

        drawerSlideAnim = ValueAnimator.ofFloat(from, to);
        drawerSlideAnim.setDuration(duration);
        drawerSlideAnim.setInterpolator(open
                ? new DecelerateInterpolator(1.35f)
                : new AccelerateInterpolator(1.25f));
        drawerSlideAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                applyDrawerSwipeProgress((float) animation.getAnimatedValue());
            }
        });
        drawerSlideAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                drawerSlideAnim = null;
                if (open) {
                    drawerSwipeProgress = 1f;
                    drawer.setTranslationY(0f);
                    drawer.setAlpha(1f);
                    bounceDrawerCard();
                    if (controllerActive) {
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (drawer != null && drawer.getVisibility()
                                        == View.VISIBLE) {
                                    focusFirstInGrid(drawerGrid);
                                }
                            }
                        }, 120L);
                    }
                } else {
                    drawerSwipeProgress = 0f;
                    drawer.setVisibility(View.GONE);
                    setHomeLiveBlur(false);
                    drawer.setTranslationY(0f);
                    drawer.setAlpha(1f);
                    drawerCard.setScaleX(1f);
                    drawerCard.setScaleY(1f);
                    drawerScrim.setAlpha(1f);
                }
            }
        });
        drawerSlideAnim.start();
    }

    private void cancelDrawerSlide() {
        if (drawerSlideAnim != null) {
            drawerSlideAnim.cancel();
            drawerSlideAnim = null;
        }
    }

    /** Live backdrop-filter equivalent for the home behind the app drawer. */
    private void setHomeLiveBlur(boolean enabled) {
        if (dock != null) dock.setLiquidPaused(enabled);
        if (drawerBackdrop == null) return;
        if (!enabled) {
            drawerBackdrop.setBackdropEnabled(false);
            return;
        }
        int left = 0;
        int top = 0;
        if (drawerCard != null) {
            left = drawerCard.getLeft();
            top = drawerCard.getTop();
            if (left == 0 && top == 0
                    && drawerCard.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) drawerCard.getLayoutParams();
                left = lp.leftMargin;
                top = lp.topMargin;
            }
        }
        drawerBackdrop.setSource(homeLayer);
        try {
            Backdrop snap = Backdrop.capture(homeLayer, 8);
            drawerBackdrop.setSnapshot(snap.bitmap);
        } catch (Throwable ignored) {
        }
        drawerBackdrop.setBackdropEnabled(true);
    }

    private void closeDrawer() {
        closeDrawer(true);
    }

    private void closeDrawer(boolean animate) {
        if (drawer == null || drawer.getVisibility() != View.VISIBLE) return;
        drawerAnimationToken++;
        drawerSwipeActive = false;
        drawerCloseSwipeActive = false;
        drawerCloseArmed = false;
        recycleDrawerCloseVelocity();
        cancelDrawerSlide();
        Motion.cancelGiggle(drawerCard);
        drawerCard.animate().cancel();
        drawerCard.setScaleX(1f);
        drawerCard.setScaleY(1f);
        drawerCard.setRotation(0f);
        drawerScrim.invalidate();
        Haptics.key(root);
        hideKeyboard();
        if (!animate) {
            cancelMagicLamp(false);
            drawer.setVisibility(View.GONE);
            drawer.setTranslationY(0f);
            drawer.setAlpha(1f);
            drawerScrim.setAlpha(1f);
            drawerCard.setVisibility(View.VISIBLE);
            drawerCard.setScaleX(1f);
            drawerCard.setScaleY(1f);
            content.setScaleX(1f);
            content.setScaleY(1f);
            content.setAlpha(1f);
            setHomeLiveBlur(false);
            return;
        }
        if (drawerPopup) {
            // Keep the live glass visible through the exit animation.
            animatePopupCard(false);
            return;
        }
        cancelMagicLamp(false);
        drawer.animate()
                .alpha(0f)
                .translationY(0f)
                .setDuration(170)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        drawer.setVisibility(View.GONE);
                        setHomeLiveBlur(false);
                    }
                })
                .start();
    }

    /** Scale the real live glass card out of/into the dock drawer button. */
    private void animatePopupCard(final boolean opening) {
        if (drawer == null || drawerCard == null || drawer.getVisibility() != View.VISIBLE) {
            return;
        }
        drawerCard.animate().cancel();
        if (drawerShadow != null) drawerShadow.animate().cancel();

        int[] anchor = dockDrawerButtonCentre();
        int[] cardLoc = new int[2];
        drawerCard.getLocationOnScreen(cardLoc);
        float pivotX = anchor != null ? anchor[0] - cardLoc[0] : drawerCard.getWidth() / 2f;
        float pivotY = anchor != null ? anchor[1] - cardLoc[1] : drawerCard.getHeight() / 2f;
        drawerCard.setPivotX(pivotX);
        drawerCard.setPivotY(pivotY);

        if (opening) {
            drawerCard.setAlpha(0f);
            drawerCard.setScaleX(0.08f);
            drawerCard.setScaleY(0.08f);
            drawerCard.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300L)
                    .setInterpolator(new PathInterpolator(0.16f, 0.86f, 0.24f, 1f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                                bounceDrawerCard();
                            }
                        }
                    })
                    .start();
            if (drawerShadow != null) {
                drawerShadow.setAlpha(0f);
                drawerShadow.animate().alpha(1f).setDuration(300L).start();
            }
        } else {
            drawerCard.animate()
                    .alpha(0f)
                    .scaleX(0.08f)
                    .scaleY(0.08f)
                    .setDuration(220L)
                    .setInterpolator(new PathInterpolator(0.4f, 0f, 0.75f, 0.35f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            drawer.setVisibility(View.GONE);
                            drawerCard.setAlpha(1f);
                            drawerCard.setScaleX(1f);
                            drawerCard.setScaleY(1f);
                            if (drawerShadow != null) drawerShadow.setAlpha(0f);
                            setHomeLiveBlur(false);
                        }
                    })
                    .start();
        }
    }

    /**
     * macOS-style magic-lamp / genie transition for the popup drawer. The card is
     * snapshotted once, then a live mesh necks it into the dock's drawer button.
     * Opening plays the same mesh backwards.
     */
    private void animatePopupLamp(final boolean opening) {
        if (drawer == null || drawerCard == null || root == null) return;
        if (drawer.getVisibility() != View.VISIBLE || !drawerPopup) return;
        if (drawerCard.getWidth() <= 1 || drawerCard.getHeight() <= 1) {
            drawerCard.setVisibility(View.VISIBLE);
            drawerScrim.setAlpha(1f);
            if (!opening) drawer.setVisibility(View.GONE);
            return;
        }
        cancelMagicLamp(false);
        final int serial = ++magicSerial;

        final Bitmap snapshot = Bitmap.createBitmap(drawerCard.getWidth(),
                drawerCard.getHeight(), Bitmap.Config.ARGB_8888);
        drawerCard.draw(new Canvas(snapshot));
        drawerCard.setVisibility(View.INVISIBLE);
        magicBitmap = snapshot;

        final MagicLampView lamp = new MagicLampView(this);
        lamp.setElevation(Ui.dpf(this, 64));
        magicLamp = lamp;
        root.addView(lamp, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int[] cardLoc = new int[2];
        int[] rootLoc = new int[2];
        drawerCard.getLocationInWindow(cardLoc);
        root.getLocationInWindow(rootLoc);
        RectF source = new RectF(cardLoc[0] - rootLoc[0], cardLoc[1] - rootLoc[1],
                cardLoc[0] - rootLoc[0] + drawerCard.getWidth(),
                cardLoc[1] - rootLoc[1] + drawerCard.getHeight());

        int[] anchor = dockDrawerButtonCentre();
        float targetX;
        float targetY;
        if (anchor != null) {
            targetX = anchor[0] - rootLoc[0];
            targetY = anchor[1] - rootLoc[1];
        } else {
            targetX = source.centerX();
            targetY = Math.min(root.getHeight() - Ui.dpf(this, 16),
                    source.bottom + Ui.dpf(this, 36));
        }
        float targetW = Math.min(source.width(), Ui.dpf(this, 46));
        float targetH = targetW * 0.62f;
        lamp.setSnapshot(snapshot, source, targetX, targetY, targetW, targetH);
        lamp.setProgress(opening ? 1f : 0f);

        if (opening) {
            drawerScrim.animate().cancel();
            drawerScrim.setAlpha(0f);
            drawerScrim.animate().alpha(1f)
                    .setStartDelay(35L)
                    .setDuration(430L)
                    .setInterpolator(new DecelerateInterpolator(1.55f))
                    .start();
        } else {
            drawerScrim.animate().cancel();
            drawerScrim.animate().alpha(0f).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }

        magicAnim = ValueAnimator.ofFloat(opening ? 1f : 0f, opening ? 0f : 1f);
        magicAnim.setDuration(opening ? 500 : 380);
        magicAnim.setInterpolator(opening
                ? new PathInterpolator(0.20f, 0.75f, 0.30f, 1f)
                : new PathInterpolator(0.40f, 0.00f, 0.75f, 0.35f));
        magicAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (serial != magicSerial) return;
                lamp.setProgress((float) animation.getAnimatedValue());
            }
        });
        magicAnim.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (canceled || serial != magicSerial) return;
                drawerCard.setVisibility(View.VISIBLE);
                if (magicLamp != null) {
                    root.removeView(magicLamp);
                    magicLamp.release();
                    magicLamp = null;
                }
                magicAnim = null;
                if (opening) bounceDrawerCard();
                if (magicBitmap != null) {
                    magicBitmap.recycle();
                    magicBitmap = null;
                }
                drawerScrim.setAlpha(1f);
                if (!opening) {
                    drawer.setVisibility(View.GONE);
                }
            }
        });
        magicAnim.start();
    }

    private void cancelMagicLamp(boolean revealCard) {
        magicSerial++;
        if (magicAnim != null) {
            magicAnim.cancel();
            magicAnim = null;
        }
        if (magicLamp != null && root != null) {
            root.removeView(magicLamp);
            magicLamp.release();
            magicLamp = null;
        }
        if (magicBitmap != null) {
            magicBitmap.recycle();
            magicBitmap = null;
        }
        if (revealCard && drawerCard != null) {
            drawerCard.setVisibility(View.VISIBLE);
        }
    }

    private final Runnable dockBackdropUpdate = new Runnable() {
        @Override
        public void run() {
            updateDockBackdrop();
        }
    };

    /** The dock now uses the same live clear glass as the built-in app cards. */
    private void updateDockBackdrop() {
        if (dock != null) dock.invalidate();
    }

    private Backdrop captureBackdrop(boolean hideDock, int blurRadius) {
        View decor = findViewById(android.R.id.content);
        boolean dockVisible = dock != null && dock.getVisibility() == View.VISIBLE;
        if (hideDock && dock != null) dock.setVisibility(View.INVISIBLE);
        Backdrop backdrop = Backdrop.capture(decor, blurRadius);
        if (hideDock && dock != null) {
            dock.setVisibility(dockVisible ? View.VISIBLE : View.INVISIBLE);
        }
        return backdrop;
    }

    private void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        drawerApps.clear();
        Apps.Entry tunerEntry = perfTunerEntry();
        if (q.isEmpty()
                || tunerEntry.label.toLowerCase(Locale.getDefault()).contains(q)
                || tunerEntry.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(tunerEntry);
        }
        if (perfLogEntry != null && (q.isEmpty()
                || perfLogEntry.label.toLowerCase(Locale.getDefault()).contains(q)
                || perfLogEntry.pkg.toLowerCase(Locale.getDefault()).contains(q))) {
            drawerApps.add(perfLogEntry);
        }
        Apps.Entry overlay = overlayEntry();
        if (q.isEmpty()
                || overlay.label.toLowerCase(Locale.getDefault()).contains(q)
                || overlay.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(overlay);
        }
        if (displayEntry != null && (q.isEmpty()
                || displayEntry.label.toLowerCase(Locale.getDefault()).contains(q)
                || displayEntry.pkg.toLowerCase(Locale.getDefault()).contains(q))) {
            drawerApps.add(displayEntry);
        }
        if (controllerMapEntry != null && (q.isEmpty()
                || controllerMapEntry.label.toLowerCase(Locale.getDefault()).contains(q)
                || controllerMapEntry.pkg.toLowerCase(Locale.getDefault()).contains(q))) {
            drawerApps.add(controllerMapEntry);
        }
        DeviceProfile profile = DeviceProfile.get(this);
        if (profile.rgb) {
            Apps.Entry rgb = rgbEntry();
            if (q.isEmpty()
                    || rgb.label.toLowerCase(Locale.getDefault()).contains(q)
                    || rgb.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
                drawerApps.add(rgb);
            }
        }
        if (profile.fanControl) {
            Apps.Entry fan = fanEntry();
            if (q.isEmpty()
                    || fan.label.toLowerCase(Locale.getDefault()).contains(q)
                    || fan.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
                drawerApps.add(fan);
            }
        }
        Apps.Entry touchMap = touchMapEntry();
        if (q.isEmpty()
                || touchMap.label.toLowerCase(Locale.getDefault()).contains(q)
                || touchMap.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(touchMap);
        }
        Apps.Entry health = deviceHealthEntry();
        if (q.isEmpty()
                || health.label.toLowerCase(Locale.getDefault()).contains(q)
                || health.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(health);
        }
        Apps.Entry benchmark = benchmarkEntry();
        if (q.isEmpty()
                || benchmark.label.toLowerCase(Locale.getDefault()).contains(q)
                || benchmark.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(benchmark);
        }
        Apps.Entry fileManager = fileManagerEntry();
        if (q.isEmpty()
                || fileManager.label.toLowerCase(Locale.getDefault()).contains(q)
                || fileManager.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(fileManager);
        }
        Apps.Entry recorder = screenRecorderEntry();
        if (q.isEmpty()
                || recorder.label.toLowerCase(Locale.getDefault()).contains(q)
                || recorder.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
            drawerApps.add(recorder);
        }
        if (q.isEmpty()) {
            drawerApps.addAll(allApps);
        } else {
            for (Apps.Entry e : allApps) {
                String label = store.labelFor(Item.app(e.pkg, e.activity), e.label);
                if (label.toLowerCase(Locale.getDefault()).contains(q)
                        || e.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
                    drawerApps.add(e);
                }
            }
        }
        if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
        if (emptyView != null) {
            emptyView.setVisibility(drawerApps.isEmpty() && !allApps.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void hideKeyboard() {
        if (search == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSystemBars();
            }
        }, 180);
    }

    private final class DrawerAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return drawerApps.size();
        }

        @Override
        public Object getItem(int position) {
            return drawerApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Apps.Entry e = drawerApps.get(position);
            ItemView v = new ItemView(MainActivity.this);
            v.setMarqueeEnabled(true);
            v.setClipChildren(true);
            int cols = effectiveDrawerColumns();
            int dm = getResources().getDisplayMetrics().widthPixels;
            int avail = (drawerPopup
                    ? Math.round(dm * 0.72f)
                    : dm - Ui.dp(MainActivity.this, 32)) - Ui.dp(MainActivity.this, 44);
            int cell = Math.max(1, avail / cols);
            int iconPx = Math.min(Ui.dp(MainActivity.this, prefs.iconDp()), cell - Ui.dp(MainActivity.this, 8));
            if (prefs.drawerLabels()) {
                iconPx = Math.min(iconPx, cell - Ui.dp(MainActivity.this, 34));
            }
            v.setIconSizePx(Math.max(Ui.dp(MainActivity.this, 24), iconPx));
            boolean builtin = isBuiltinApp(e.pkg);
            v.dragEnabled = true;
            Item item = Item.app(e.pkg, e.activity);
            Bitmap themed = store.iconFor(item);
            if (builtin && themed == null) themed = e.icon;
            if (!builtin && themed == null && iconPack != null) {
                themed = iconPack.iconFor(e.pkg, e.activity);
            }
            v.bind(item, e, themed, store.labelFor(item, e.label), prefs.drawerLabels());
            v.listener = drawerListener;
            v.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }

    private final ItemView.Listener drawerListener = new ItemView.Listener() {
        @Override
        public void onItemClick(ItemView v) {
            // Keep the drawer alive behind the launched app. Closing and
            // rebuilding it on return produced a visible home flash and a
            // second drawer animation; the app now simply reveals the drawer
            // that was already underneath it, with its scroll position intact.
            reopenDrawerAfterLaunch = false;
            if (v.entry != null && PERF_LOG_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, PerfLogActivity.class));
                return;
            }
            if (v.entry != null && PERF_TUNER_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, PerformanceTunerActivity.class));
                return;
            }
            if (v.entry != null && CONTROLLER_MAP_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, ControllerMapActivity.class));
                return;
            }
            if (v.entry != null && DISPLAY_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, DisplayActivity.class));
                return;
            }
            if (v.entry != null && RGB_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, RgbActivity.class));
                return;
            }
            if (v.entry != null && FAN_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, FanActivity.class));
                return;
            }
            if (v.entry != null && TOUCH_MAP_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, TouchMapperActivity.class));
                return;
            }
            if (v.entry != null && DEVICE_HEALTH_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, DeviceHealthActivity.class));
                return;
            }
            if (v.entry != null && BENCHMARK_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, BenchmarkActivity.class));
                return;
            }
            if (v.entry != null && FILE_MANAGER_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, FileManagerActivity.class));
                return;
            }
            if (v.entry != null && SCREEN_RECORDER_PKG.equals(v.entry.pkg)) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, ScreenRecorderActivity.class));
                return;
            }
            if (v.item != null) launch(v.item, null);
        }

        @Override
        public void onItemLongPress(ItemView v) {
            if (v.item == null) return;
            if (dragSource != null && dragSource != v) dragSource.setLifted(false);
            dragSource = v;
            dragSrcKind = SRC_DRAWER;
            dragSrcPage = -1;
            dragSrcCell = -1;
            dragSrcDock = -1;
            v.setLifted(true);
            startLongPressRipple(v);
        }

        @Override
        public void onItemDragStart(ItemView v) {
            beginDragFromDrawer(v);
            dismissActiveMenu();
        }

        @Override
        public void onItemDragMove(ItemView v, float rawX, float rawY) {
            moveDrag(rawX, rawY);
        }

        @Override
        public void onItemRelease(ItemView v, float rawX, float rawY, boolean dragged,
                                  boolean longFired) {
            if (dragged) {
                endDrag(rawX, rawY);
            } else {
                if (dragSource == v) {
                    v.setLifted(false);
                    resetDragSource();
                }
                if (longFired) showItemMenu(v, true);
            }
        }

        @Override
        public void onItemDragCancel(ItemView v) {
            cancelDrag();
        }
    };

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    private void ensureFolderOverlay() {
        if (folderOverlay != null) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        folderOverlay = new FrameLayout(this);
        folderOverlay.setVisibility(View.GONE);
        folderOverlay.setElevation(Ui.dpf(this, 34));

        View scrim = new View(this);
        scrim.setBackgroundColor(0x73000000);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeFolder();
            }
        });
        folderOverlay.addView(scrim, match());

        folderCard = new FrameLayout(this);
        folderCard.setClipToOutline(true);
        folderCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Ui.dpf(MainActivity.this, 26));
            }
        });
        int w = Math.round(dm.widthPixels * 0.64f);
        int h = Math.round(dm.heightPixels * 0.62f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        folderOverlay.addView(folderCard, cardLp);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));

        folderTitle = new LTextView(this);
        folderTitle.setTextColor(0xFFF5F7FA);
        folderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        folderTitle.setShadowLayer(Ui.dpf(this, 5), 0, Ui.dpf(this, 1), 0x99000000);
        folderTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        folderTitle.setLetterSpacing(0.04f);
        panel.addView(folderTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        folderAdapter = new FolderAdapter();
        folderGrid = new GridView(this);
        folderGrid.setNumColumns(Math.min(effectiveDrawerColumns(), 6));
        folderGrid.setVerticalSpacing(Ui.dp(this, 6));
        folderGrid.setHorizontalSpacing(0);
        folderGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        folderGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        folderGrid.setPadding(Ui.dp(this, 10), Ui.dp(this, 6),
                Ui.dp(this, 10), Ui.dp(this, 6));
        folderGrid.setClipToPadding(false);
        folderGrid.setAdapter(folderAdapter);
        folderGrid.setVerticalScrollBarEnabled(false);
        panel.addView(folderGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        folderCard.addView(panel, match());

        root.addView(folderOverlay, match());
    }

    private void openFolder(Item folder) {
        openFolder(folder, null);
    }

    private void openFolder(Item folder, View source) {
        if (folder == null || folder.type != Item.TYPE_FOLDER) return;
        Haptics.click(source != null ? source : root);
        rememberControllerFocus();
        ensureFolderOverlay();
        folderOverlay.animate().cancel();
        folderCard.animate().cancel();
        loadFolderContents(folder);
        folderSourceRect.set(sourceRectForFolder(folder, source));

        Backdrop backdrop = captureBackdrop(false, 0);
        if (folderBackdrop != null && folderBackdrop.bitmap != null) {
            folderBackdrop.bitmap.recycle();
        }
        folderBackdrop = backdrop;
        if (folderGlass != null) folderCard.removeView(folderGlass);
        folderGlass = new GlassPanel(this, backdrop, wallpaper.accent(),
                0x660B0E14, 26, true);
        folderGlass.setSurfaceAlpha(prefs.folderTransparency() / 100f);
        folderGlass.setBackdropSaturation(prefs.folderSaturation() / 100f);
        folderGlass.setBackdropDarkness(prefs.folderBackdropDarkness() / 100f);
        folderCard.addView(folderGlass, 0, match());

        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        int overlayW = root.getWidth() > 0 ? root.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int overlayH = root.getHeight() > 0 ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        float centerX = rl[0] + overlayW / 2f;
        float centerY = rl[1] + overlayH / 2f;

        int cardW = Math.max(1, folderCardW);
        int cardH = Math.max(1, folderCardH);
        final float sx = Math.max(0.12f, Math.min(1f, folderSourceRect.width() / cardW));
        final float sy = Math.max(0.12f, Math.min(1f, folderSourceRect.height() / cardH));
        final float tx = folderSourceRect.centerX() - centerX;
        final float ty = folderSourceRect.centerY() - centerY;

        folderCard.setPivotX(cardW / 2f);
        folderCard.setPivotY(cardH / 2f);
        folderCard.setScaleX(sx);
        folderCard.setScaleY(sy);
        folderCard.setTranslationX(tx);
        folderCard.setTranslationY(ty);
        folderCard.setRotation(-2.5f);
        folderCard.setAlpha(0.25f);
        folderOverlay.setVisibility(View.VISIBLE);
        folderOverlay.setAlpha(0f);

        folderOverlay.post(new Runnable() {
            @Override
            public void run() {
                if (folderOverlay.getVisibility() != View.VISIBLE) return;
                folderOverlay.animate().alpha(1f)
                        .setDuration(180)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
                folderCard.animate()
                        .scaleX(1f).scaleY(1f)
                        .translationX(0f).translationY(0f)
                        .rotation(0f).alpha(1f)
                        .setDuration(320)
                        .setInterpolator(new OvershootInterpolator(0.65f))
                        .start();
                if (controllerActive) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (folderOverlay != null
                                    && folderOverlay.getVisibility() == View.VISIBLE) {
                                focusFirstInGrid(folderGrid);
                            }
                        }
                    }, 340);
                }
            }
        });
    }

    /** Screen rectangle the folder card grows from / shrinks back into. */
    private RectF sourceRectForFolder(Item folder, View source) {
        RectF r = new RectF();
        if (source != null && source.getWidth() > 0 && source.getHeight() > 0) {
            int[] loc = new int[2];
            source.getLocationOnScreen(loc);
            r.set(loc[0], loc[1], loc[0] + source.getWidth(), loc[1] + source.getHeight());
            return r;
        }
        if (folder != null) {
            for (int p = 0; p < store.pageCount(); p++) {
                List<Item> page = store.page(p);
                for (int i = 0; i < page.size(); i++) {
                    Item it = page.get(i);
                    if (it == null || !it.key().equals(folder.key())) continue;
                    View pv = pager.getChildAt(p);
                    if (!(pv instanceof GridPage)) continue;
                    RectF cell = new RectF();
                    ((GridPage) pv).cellRect(i, cell);
                    int[] pl = new int[2];
                    pager.getLocationOnScreen(pl);
                    float left = pl[0] - pager.getScrollX() + p * pager.pageWidth() + cell.left;
                    float top = pl[1] + cell.top;
                    r.set(left, top, left + cell.width(), top + cell.height());
                    return r;
                }
            }
        }
        int w = Ui.dp(this, 72);
        int h = Ui.dp(this, 72);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        r.set(dm.widthPixels / 2f - w / 2f, dm.heightPixels / 2f - h / 2f,
                dm.widthPixels / 2f + w / 2f, dm.heightPixels / 2f + h / 2f);
        return r;
    }

    /** Refresh the title, app list and card height without replaying the opening motion. */
    private void loadFolderContents(Item folder) {
        openFolderItem = folder;
        folderTitle.setText(store.labelFor(folder,
                folder.title != null && !folder.title.isEmpty() ? folder.title : "Folder"));
        folderApps.clear();
        for (Item c : folder.children) {
            if (c.type != Item.TYPE_APP) continue;
            Apps.Entry e = findEntry(c.pkg, c.cls);
            if (e == null) e = Apps.entryFor(this, c.pkg, c.cls);
            folderApps.add(e);
        }

        // Compact sizing: the card grows with the number of apps.
        int n = folderApps.size();
        if (n <= 4) folderCols = 2;
        else if (n <= 9) folderCols = 3;
        else if (n <= 16) folderCols = 4;
        else folderCols = Math.min(effectiveDrawerColumns(), 6);
        int rows = Math.max(1, (n + folderCols - 1) / folderCols);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cell = Ui.dp(this, Math.min(108, Math.max(62, prefs.iconDp() + 22)));
        int padH = Ui.dp(this, 18);
        int padV = Ui.dp(this, 12);
        int headerH = Ui.dp(this, 36);
        int maxW = Math.round(dm.widthPixels * 0.66f);
        int maxH = Math.round(dm.heightPixels * 0.66f);
        folderCardW = Math.min(maxW, padH * 2 + folderCols * cell);
        int h = Math.min(maxH, headerH + rows * (cell + Ui.dp(this, 6)) + padV * 2);
        folderCardH = h;

        folderGrid.setNumColumns(folderCols);
        ViewGroup.LayoutParams cardLp = folderCard.getLayoutParams();
        cardLp.width = folderCardW;
        cardLp.height = h;
        folderCard.setLayoutParams(cardLp);
        folderAdapter.notifyDataSetChanged();
    }

    private void closeFolder() {
        if (folderOverlay == null || folderOverlay.getVisibility() != View.VISIBLE) return;
        Haptics.key(root);
        folderOverlay.animate().cancel();
        folderCard.animate().cancel();

        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        int overlayW = root.getWidth() > 0 ? root.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int overlayH = root.getHeight() > 0 ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        float centerX = rl[0] + overlayW / 2f;
        float centerY = rl[1] + overlayH / 2f;
        int cardW = Math.max(1, folderCardW);
        int cardH = Math.max(1, folderCardH);

        float sx;
        float sy;
        float tx;
        float ty;
        if (!folderSourceRect.isEmpty()) {
            sx = Math.max(0.08f, Math.min(1f, folderSourceRect.width() / cardW));
            sy = Math.max(0.08f, Math.min(1f, folderSourceRect.height() / cardH));
            tx = folderSourceRect.centerX() - centerX;
            ty = folderSourceRect.centerY() - centerY;
        } else {
            sx = 0.8f;
            sy = 0.8f;
            tx = 0f;
            ty = 0f;
        }

        folderCard.setPivotX(cardW / 2f);
        folderCard.setPivotY(cardH / 2f);
        folderCard.animate()
                .scaleX(sx).scaleY(sy)
                .translationX(tx).translationY(ty)
                .rotation(-2.5f).alpha(0.35f)
                .setDuration(250)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        folderOverlay.setVisibility(View.GONE);
                        folderOverlay.setAlpha(1f);
                        folderCard.setScaleX(1f);
                        folderCard.setScaleY(1f);
                        folderCard.setTranslationX(0f);
                        folderCard.setTranslationY(0f);
                        folderCard.setRotation(0f);
                        folderCard.setAlpha(1f);
                    }
                }).start();
        folderOverlay.animate().alpha(0f)
                .setDuration(230)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
        openFolderItem = null;
    }

    private void unpackFolder(Item folder) {
        int cap = capacity();
        for (Item c : new ArrayList<>(folder.children)) {
            store.addToHome(c, cap);
        }
        store.removeFromHome(folder.key());
        store.removeFromDock(folder.key());
        store.trim();
        store.save();
        refreshVisible();
    }

    private void removeFromFolder(Item folder, Item app) {
        if (folder == null || app == null) return;
        for (int i = 0; i < folder.children.size(); i++) {
            if (folder.children.get(i).key().equals(app.key())) {
                folder.children.remove(i);
                break;
            }
        }
        if (folder.children.isEmpty()) {
            store.removeFromHome(folder.key());
            store.removeFromDock(folder.key());
            store.trim();
            store.save();
            closeFolder();
            refreshVisible();
        } else {
            store.save();
            loadFolderContents(folder);
            refreshVisible();
        }
    }

    private final class FolderAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return folderApps.size();
        }

        @Override
        public Object getItem(int position) {
            return folderApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Apps.Entry e = folderApps.get(position);
            ItemView v = new ItemView(MainActivity.this);
            int cols = Math.max(1, folderCols);
            int cardW = folderCardW > 0 ? folderCardW
                    : Math.round(getResources().getDisplayMetrics().widthPixels * 0.64f);
            int avail = cardW - Ui.dp(MainActivity.this, 36);
            int cell = Math.max(1, avail / cols);
            int iconPx = Math.min(Ui.dp(MainActivity.this, prefs.iconDp()),
                    cell - Ui.dp(MainActivity.this, 8));
            if (prefs.drawerLabels()) {
                iconPx = Math.min(iconPx, cell - Ui.dp(MainActivity.this, 34));
            }
            v.setIconSizePx(Math.max(Ui.dp(MainActivity.this, 24), iconPx));
            v.dragEnabled = true;
            Item item = Item.app(e.pkg, e.activity);
            Bitmap themed = store.iconFor(item);
            if (themed == null && iconPack != null) themed = iconPack.iconFor(e.pkg, e.activity);
            v.bind(item, e, themed, store.labelFor(item, e.label),
                    prefs.drawerLabels());
            v.listener = folderItemListener;
            v.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }

    private final ItemView.Listener folderItemListener = new ItemView.Listener() {
        @Override
        public void onItemClick(ItemView v) {
            closeFolder();
            if (v.item != null) launch(v.item, null);
        }

        @Override
        public void onItemLongPress(final ItemView v) {
            if (v.item == null) return;
            if (dragSource != null && dragSource != v) dragSource.setLifted(false);
            dragSource = v;
            dragSrcKind = SRC_FOLDER;
            dragSrcPage = -1;
            dragSrcCell = -1;
            dragSrcDock = -1;
            v.setLifted(true);
            startLongPressRipple(v);
        }

        @Override
        public void onItemDragStart(ItemView v) {
            beginDragFromFolder(v);
            dismissActiveMenu();
        }

        @Override
        public void onItemDragMove(ItemView v, float rawX, float rawY) {
            moveDrag(rawX, rawY);
        }

        @Override
        public void onItemRelease(ItemView v, float rawX, float rawY, boolean dragged,
                                  boolean longFired) {
            if (dragged) {
                endDrag(rawX, rawY);
            } else {
                if (dragSource == v) {
                    v.setLifted(false);
                    resetDragSource();
                }
                if (longFired) MainActivity.this.showFolderItemMenu(v);
            }
        }

        @Override
        public void onItemDragCancel(ItemView v) {
            cancelDrag();
        }
    };

    // ------------------------------------------------------------------
    // Apps
    // ------------------------------------------------------------------

    /** Remove shortcuts whose package has been uninstalled, including folder children. */
    private boolean pruneMissingApps() {
        if (store == null) return false;
        boolean[] changed = {false};
        for (List<Item> page : store.pages) {
            for (int i = 0; i < page.size(); i++) {
                if (pruneMissingItem(page.get(i), changed)) {
                    page.set(i, null);
                }
            }
        }
        for (int i = 0; i < store.dock.size(); i++) {
            if (pruneMissingItem(store.dock.get(i), changed)) {
                store.dock.remove(i);
                i--;
            }
        }
        if (changed[0]) {
            store.trim();
            store.save();
        }
        return changed[0];
    }

    private boolean pruneMissingAllApps() {
        if (allApps.isEmpty()) return false;
        boolean changed = false;
        for (int i = 0; i < allApps.size(); i++) {
            Apps.Entry e = allApps.get(i);
            if (e == null) {
                allApps.remove(i--);
                changed = true;
                continue;
            }
            if (isBuiltinApp(e.pkg)) continue;
            if (!Apps.isInstalled(this, e.pkg)) {
                allApps.remove(i--);
                changed = true;
            }
        }
        if (changed && drawerAdapter != null) {
            filter(search == null ? "" : search.getText().toString());
        }
        return changed;
    }

    private boolean pruneMissingItem(Item item, boolean[] changed) {
        if (item == null) return false;
        if (item.type == Item.TYPE_APP) {
            if (item.pkg != null && isBuiltinApp(item.pkg)) return false;
            if (item.pkg == null || !Apps.isInstalled(this, item.pkg)) {
                changed[0] = true;
                return true;
            }
            return false;
        }
        if (item.type == Item.TYPE_FOLDER) {
            for (int i = 0; i < item.children.size(); i++) {
                if (pruneMissingItem(item.children.get(i), changed)) {
                    item.children.remove(i);
                    i--;
                }
            }
            if (item.children.isEmpty()) {
                changed[0] = true;
                return true;
            }
        }
        return false;
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Apps.Entry> list = Apps.query(MainActivity.this);
                Apps.loadIcons(MainActivity.this, list, Ui.dp(MainActivity.this, 72));
                final IconPack.Resolver pack = IconPack.resolver(MainActivity.this,
                        prefs.iconPack(), Ui.dp(MainActivity.this, 96));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        iconPack = pack;
                        if (!prefs.iconPack().isEmpty() && pack == null) {
                            prefs.setIconPack("");
                        }
                        allApps.clear();
                        allApps.addAll(list);
                        perfLogEntry = new Apps.Entry(Loc.s(MainActivity.this, "Perf Logs"), PERF_LOG_PKG, "");
                        perfLogEntry.icon = Apps.performanceLogsIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        controllerMapEntry = new Apps.Entry(Loc.s(MainActivity.this, "Controller Remapper"),
                                CONTROLLER_MAP_PKG, "");
                        controllerMapEntry.icon = Apps.controllerMapIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        displayEntry = new Apps.Entry(Loc.s(MainActivity.this, "Display"), DISPLAY_PKG, "");
                        displayEntry.icon = Apps.displayIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        rgbEntry = new Apps.Entry(Loc.s(MainActivity.this, "RGB Lighting"), RGB_PKG, "");
                        rgbEntry.icon = Apps.rgbIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        fanEntry = new Apps.Entry(Loc.s(MainActivity.this, "Fan Control"), FAN_PKG, "");
                        fanEntry.icon = Apps.fanIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        touchMapEntry = new Apps.Entry(Loc.s(MainActivity.this, "Touch Mapper"), TOUCH_MAP_PKG, "");
                        touchMapEntry.icon = Apps.touchMapperIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        deviceHealthEntry = new Apps.Entry(Loc.s(MainActivity.this, "Device Health"), DEVICE_HEALTH_PKG, "");
                        deviceHealthEntry.icon = Apps.deviceHealthIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        benchmarkEntry = new Apps.Entry(Loc.s(MainActivity.this, "Stress Test"), BENCHMARK_PKG, "");
                        benchmarkEntry.icon = Apps.benchmarkIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        fileManagerEntry = new Apps.Entry(Loc.s(MainActivity.this, "File Manager"), FILE_MANAGER_PKG, "");
                        fileManagerEntry.icon = Apps.fileManagerIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        screenRecorderEntry = new Apps.Entry(Loc.s(MainActivity.this, "Screen Recorder"),
                                SCREEN_RECORDER_PKG, "");
                        screenRecorderEntry.icon = Apps.screenRecorderIcon(
                                Ui.dp(MainActivity.this, 72), wallpaper.accent());
                        if (store.isEmpty()) seedLayout();
                        renderHome();
                        populateDock();
                        filter(search == null ? "" : search.getText().toString());
                        if (!firstHomeShown) {
                            firstHomeShown = true;
                            animateHomeIn();
                        }
                        if (!IconPack.hasCachedPacks()) {
                            final Context appContext = getApplicationContext();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    IconPack.findPacks(appContext);
                                }
                            }, "icon-pack-warm").start();
                        }
                    }
                });
            }
        }, "app-loader").start();
    }

    private void seedLayout() {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        int dockMax = prefs.dockCount();
        for (String pkg : SEED_DOCK) {
            Apps.Entry e = findPkg(pkg);
            if (e != null && used.add(e.pkg) && store.dock.size() < dockMax) {
                store.dock.add(Item.app(e.pkg, e.activity));
            }
        }
        for (Apps.Entry e : allApps) {
            if (store.dock.size() >= dockMax) break;
            if (used.add(e.pkg)) store.dock.add(Item.app(e.pkg, e.activity));
        }

        List<Item> page = store.page(0);
        LinkedHashSet<String> favUsed = new LinkedHashSet<>(used);
        for (String pkg : SEED_FAVORITES) {
            Apps.Entry e = findPkg(pkg);
            if (e != null && favUsed.add(e.pkg)) page.add(Item.app(e.pkg, e.activity));
        }
        for (Apps.Entry e : allApps) {
            if (page.size() >= capacity()) break;
            if (favUsed.add(e.pkg)) page.add(Item.app(e.pkg, e.activity));
        }
        store.save();
    }

    private Apps.Entry findPkg(String pkg) {
        for (Apps.Entry e : allApps) {
            if (e.pkg.equals(pkg)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Clock / battery / stats
    // ------------------------------------------------------------------

    private void updateClock() {
        Date now = new Date();
        boolean h24 = prefs.clock24();
        Locale locale = Locale.getDefault();
        timeView.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", locale).format(now));
        ampmView.setVisibility(h24 ? View.GONE : View.VISIBLE);
        if (!h24) {
            ampmView.setText(new SimpleDateFormat("a", locale).format(now).toUpperCase(locale));
        }
        String datePattern = Loc.EN_US.equals(Loc.languageCode(this))
                ? "EEE MMM dd"
                : android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd");
        String date = new SimpleDateFormat(datePattern, locale).format(now);
        dateView.setText(date.toUpperCase(locale));
    }

    private void applyClockScale() {
        float s = prefs.clockScale();
        if (timeView != null) timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52 * s);
        if (ampmView != null) ampmView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * s);
        if (dateView != null) dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * s);
        if (weatherView != null) weatherView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * s);
        if (weatherGlyph != null) {
            ViewGroup.LayoutParams lp = weatherGlyph.getLayoutParams();
            if (lp != null) {
                lp.width = Ui.dp(this, Math.max(10, Math.round(16 * s)));
                lp.height = Ui.dp(this, Math.max(10, Math.round(16 * s)));
                weatherGlyph.setLayoutParams(lp);
            }
        }
        applyClockColors();
    }

    private void applyClockColors() {
        int clock = prefs.clockColor();
        int date = prefs.dateColor();
        applyClockGradient(timeView, clock, 1f);
        applyClockGradient(ampmView, clock, 0.62f);
        applyClockGradient(dateView, date, 1f);
    }

    /** Gives the clock/date text a top-to-bottom coloured gradient based on its chosen colour. */
    private void applyClockGradient(TextView view, int color, float alpha) {
        if (view == null) return;
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255f);
        int base = ColorTuning.colour((color & 0x00FFFFFF) | (a << 24));
        int light = blendColour(base, 0xFFFFFFFF, 0.48f);
        int dark = blendColour(base, 0xFF000000, 0.38f);
        float height = Math.max(1f, view.getTextSize() * 1.45f);
        LinearGradient gradient = new LinearGradient(
                0f, 0f, 0f, height,
                new int[]{light, base, dark},
                new float[]{0f, 0.54f, 1f},
                Shader.TileMode.CLAMP);
        view.setTextColor(base);
        view.getPaint().setShader(gradient);
        int glowAlpha = view == timeView ? 0xB8 : 0x98;
        float glowRadius = view == timeView ? 7.5f : 2.8f;
        view.setShadowLayer(Ui.dpf(this, ColorTuning.radius(glowRadius)), 0f, 0f,
                clockGlowColour(base, glowAlpha));
        view.invalidate();
    }

    /** Re-apply the global saturation and glow values to the live home UI. */
    private void applyColorTuning() {
        ColorTuning.set(prefs.colorSaturation(), prefs.glowStrength());
        applyClockColors();
        updateStats();
        if (lastBatteryIntent != null) {
            handleBattery(lastBatteryIntent);
        } else if (batteryGlyph != null) {
            batteryGlyph.invalidate();
        }
        updateHeaderStatus();
        if (bluetoothStatusGlyph != null) bluetoothStatusGlyph.invalidate();
        if (soundStatusGlyph != null) soundStatusGlyph.invalidate();
        if (batteryGlyph != null) batteryGlyph.invalidate();
        if (statsText != null) statsText.invalidate();
        if (wallpaper != null) wallpaper.invalidate();
        if (dock != null) dock.invalidate();
        if (pager != null) pager.invalidate();
        for (SettingsOptionIcon icon : settingsCategoryIcons) icon.invalidate();
        if (settingsRootView != null) settingsRootView.invalidate();
    }

    private int clockGlowColour(int color, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.85f * ColorTuning.saturation());
        hsv[2] = Math.max(hsv[2], 0.92f);
        return Color.HSVToColor(ColorTuning.glowAlpha(alpha), hsv);
    }

    private int blendColour(int base, int target, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = Color.alpha(base);
        int r = Math.round(Color.red(base) * (1f - t) + Color.red(target) * t);
        int g = Math.round(Color.green(base) * (1f - t) + Color.green(target) * t);
        int b = Math.round(Color.blue(base) * (1f - t) + Color.blue(target) * t);
        return Color.argb(a, r, g, b);
    }

    private void updateWeatherView() {
        if (weatherView == null) return;
        if (!prefs.weatherVisible()) {
            if (weatherView.getVisibility() != View.GONE) {
                weatherView.setVisibility(View.GONE);
                if (weatherGlyph != null) weatherGlyph.setVisibility(View.GONE);
                scheduleClockReflow();
            }
            return;
        }
        int code = prefs.weatherCode();
        float temp = prefs.weatherTemp();
        if (code < 0 || Float.isNaN(temp)) {
            if (weatherView.getVisibility() != View.GONE) {
                weatherView.setVisibility(View.GONE);
                if (weatherGlyph != null) weatherGlyph.setVisibility(View.GONE);
                scheduleClockReflow();
            }
            return;
        }
        String text = Math.round(temp) + "\u00B0  " + Loc.s(this, Weather.describe(code));
        boolean changed = weatherView.getVisibility() != View.VISIBLE
                || !text.contentEquals(weatherView.getText());
        weatherView.setText(text);
        weatherView.setVisibility(View.VISIBLE);
        if (weatherGlyph != null) {
            weatherGlyph.setCode(code);
            weatherGlyph.setVisibility(View.VISIBLE);
        }
        if (changed) scheduleClockReflow();
    }

    private boolean weatherLoading;

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather(true);
            handler.postDelayed(this, 30L * 60L * 1000L);
        }
    };

    private void refreshWeather(boolean force) {
        if (!prefs.weatherVisible()) return;
        long age = System.currentTimeMillis() - prefs.weatherAt();
        if (!force && age < 30L * 60L * 1000L) {
            updateWeatherView();
            return;
        }
        if (weatherLoading) return;
        weatherLoading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Weather.Data data = Weather.load(MainActivity.this,
                        prefs.weatherUseLocation());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        weatherLoading = false;
                        if (data != null) {
                            prefs.setWeather(data.temp, data.code);
                            updateWeatherView();
                        }
                    }
                });
            }
        }, "weather").start();
    }

    private void showColourDialog(final boolean date) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Backdrop backdrop = captureBackdrop(false, 0);

        final GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 24, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText(date ? "Date color" : "Clock color");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        glass.addView(title);

        TextView hint = new LTextView(this);
        hint.setText("Drag around the wheel, then tune the brightness.");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 4), 0, 0);
        glass.addView(hint);

        final ColorWheel wheel = new ColorWheel(this);
        wheel.setColour(date ? prefs.dateColor() : prefs.clockColor());
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 220), Ui.dp(this, 220));
        wlp.gravity = Gravity.CENTER_HORIZONTAL;
        wlp.topMargin = Ui.dp(this, 14);
        glass.addView(wheel, wlp);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 12);
        glass.addView(preview, plp);

        final View swatch = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, 30), Ui.dp(this, 30));
        slp.rightMargin = Ui.dp(this, 10);
        preview.addView(swatch, slp);

        final TextView hex = new LTextView(this);
        hex.setTextColor(0xE6FFFFFF);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hex.setTypeface(Typeface.MONOSPACE);
        preview.addView(hex);
        updateColourPreview(swatch, hex, wheel.getColour());

        LinearLayout brightnessRow = new LinearLayout(this);
        brightnessRow.setOrientation(LinearLayout.HORIZONTAL);
        brightnessRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = Ui.dp(this, 10);
        glass.addView(brightnessRow, brlp);

        TextView brightnessLabel = new LTextView(this);
        brightnessLabel.setText("Brightness");
        brightnessLabel.setTextColor(0xE6FFFFFF);
        brightnessLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        brightnessRow.addView(brightnessLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView brightnessValue = new LTextView(this);
        brightnessValue.setTextColor(0x8CFFFFFF);
        brightnessValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        brightnessValue.setTypeface(Typeface.MONOSPACE);
        brightnessValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        brightnessValue.setMinWidth(Ui.dp(this, 46));
        brightnessValue.setPadding(0, 0, Ui.dp(this, 5), 0);
        brightnessRow.setPadding(0, 0, Ui.dp(this, 10), 0);
        brightnessRow.addView(brightnessValue);

        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        int bright = Math.round(wheel.value() * 100f);
        bar.setProgress(bright);
        brightnessValue.setText(bright + "%");
        bar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 2), Ui.dp(this, 14), 0);
        final long[] brightnessHaptic = {0L};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                brightnessValue.setText(progress + "%");
                wheel.setValue(progress / 100f);
                updateColourPreview(swatch, hex, wheel.getColour());
                if (fromUser) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - brightnessHaptic[0] >= 45L) {
                        brightnessHaptic[0] = now;
                        Haptics.tick(seekBar);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.key(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.click(seekBar);
                applySettingsLive();
            }
        });
        glass.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));

        wheel.setListener(new ColorWheel.Listener() {
            @Override
            public void onColorChanged(int color) {
                updateColourPreview(swatch, hex, color);
            }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(this, 12), 0, 0);

        TextView cancel = new LTextView(this);
        cancel.setText("Cancel");
        styleTextButton(cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(cancel);

        TextView defaults = new LTextView(this);
        defaults.setText("Default");
        styleTextButton(defaults, false);
        defaults.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int color = date ? 0xFFFFB3B8 : 0xFFFF4D5E;
                wheel.setColour(color);
                int pct = Math.round(wheel.value() * 100f);
                bar.setProgress(pct);
                brightnessValue.setText(pct + "%");
                updateColourPreview(swatch, hex, color);
            }
        });
        buttons.addView(defaults);

        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView apply = new LTextView(this);
        apply.setText("Apply");
        styleTextButton(apply, true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int color = wheel.getColour();
                if (date) prefs.setDateColor(color);
                else prefs.setClockColor(color);
                applyClockColors();
                dialog.dismiss();
                LToast.makeText(MainActivity.this,
                        date ? "Date color updated" : "Clock color updated",
                        Toast.LENGTH_SHORT).show();
            }
        });
        buttons.addView(apply);
        glass.addView(buttons);

        showGlassDialog(dialog, glass, backdrop, 460, 0f);
        dialog.show();
        attachCardController(dialog, glass, glass);
    }

    private void updateColourPreview(View swatch, TextView hex, int color) {
        swatch.setBackground(Ui.rounded(color, Ui.dpf(this, 15),
                0x66FFFFFF, Ui.dpf(this, 1)));
        hex.setText(String.format(Locale.US, "#%06X", color & 0xFFFFFF));
    }

    private void showClockResizeDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Backdrop backdrop = captureBackdrop(false, 0);

        final GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Widget size");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        glass.addView(title);

        final TextView value = new LTextView(this);
        value.setTextColor(0x8CFFFFFF);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        value.setTypeface(Typeface.MONOSPACE);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 2));
        int current = Math.round(prefs.clockScale() * 100f);
        value.setText(current + "%");
        glass.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar bar = new SeekBar(this);
        bar.setMax(90);
        bar.setProgress(Math.max(0, Math.min(90, current - 70)));
        bar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 2), Ui.dp(this, 14), 0);
        final long[] clockHaptic = {0L};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = 70 + progress;
                value.setText(percent + "%");
                if (fromUser) {
                    prefs.setClockScale(percent / 100f);
                    applyClockScale();
                    scheduleClockReflow();
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - clockHaptic[0] >= 45L) {
                        clockHaptic[0] = now;
                        Haptics.tick(seekBar);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.key(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.click(seekBar);
                renderHome();
            }
        });
        glass.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(this, 12), 0, 0);

        TextView reset = new LTextView(this);
        reset.setText("Reset");
        styleTextButton(reset, false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setClockScale(1f);
                applyClockScale();
                renderHome();
                dialog.dismiss();
            }
        });
        buttons.addView(reset);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView done = new LTextView(this);
        done.setText("Done");
        styleTextButton(done, true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(done);
        glass.addView(buttons);

        showGlassDialog(dialog, glass, backdrop, 460, 0f);
        dialog.show();
        attachCardController(dialog, glass, glass);
    }

    private void updateStats() {
        float cpu = SysStats.cpuPercent();
        long net = SysStats.netBytesPerSec(2000);
        String cpuValue = cpu < 0 ? "--" : String.format(Locale.US, "%.0f%%", cpu);
        String netValue = SysStats.fmtRate(net);
        String text = "CPU " + cpuValue + "  \u00B7  " + netValue;
        SpannableString spans = new SpannableString(text);
        int cpuStart = 4;
        int cpuEnd = cpuStart + cpuValue.length();
        int netStart = text.length() - netValue.length();
        spans.setSpan(new ForegroundColorSpan(
                ColorTuning.colour(usageColour(cpu, 40f, 70f))),
                cpuStart, cpuEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        spans.setSpan(new ForegroundColorSpan(ColorTuning.colour(netColour(net))),
                netStart, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int glow;
        if ((cpu >= 70f) || (net >= 1024L * 1024L)) glow = 0xFFFF3D3D;
        else if ((cpu >= 40f) || (net >= 100L * 1024L)) glow = 0xFFFFC107;
        else glow = 0xFF00E6A8;
        statsText.setShadowLayer(Ui.dpf(this, ColorTuning.radius(2.4f)), 0f, 0f,
                ColorTuning.glowColour(glow, 0xB0));
        statsText.setText(spans);
    }

    /** Green below the low threshold, amber in the middle, red at high load. */
    private int usageColour(float value, float warn, float high) {
        if (value < 0) return 0x80FFFFFF;
        if (value >= high) return 0xFFFF3D3D;
        if (value >= warn) return 0xFFFFC107;
        return 0xFF00E6A8;
    }

    /** Network activity is coloured on the same low/medium/high ramp. */
    private int netColour(long bps) {
        if (bps < 0) return 0x80FFFFFF;
        if (bps >= 1024L * 1024L) return 0xFFFF3D3D;
        if (bps >= 100L * 1024L) return 0xFFFFC107;
        return 0xFF00E6A8;
    }

    private void updateStatsVisibility() {
        if (statsText != null) {
            statsText.setVisibility(prefs.statsVisible() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateHeaderStatusVisibility() {
        if (recordingStatusGlyph != null) {
            recordingStatusGlyph.setVisibility(View.VISIBLE);
        }
        if (bluetoothStatusGlyph != null) {
            bluetoothStatusGlyph.setVisibility(prefs.bluetoothStatusVisible()
                    ? View.VISIBLE : View.GONE);
        }
        if (soundStatusGlyph != null) {
            soundStatusGlyph.setVisibility(prefs.soundStatusVisible()
                    ? View.VISIBLE : View.GONE);
        }
        if (headerStatusCluster != null) {
            // The recording indicator is always present so it can start a
            // capture with one tap, even when both other status icons are off.
            headerStatusCluster.setVisibility(View.VISIBLE);
        }
    }

    private void updateHeaderStatus() {
        if (recordingStatusGlyph != null) {
            boolean recording = ScreenRecorderService.isRecording();
            recordingStatusGlyph.setActive(recording);
            recordingStatusGlyph.setContentDescription(recording
                    ? "Screen recording active, open recorder"
                    : "Start entire-screen recording");
            recordingStatusGlyph.invalidate();
        }
        if (bluetoothStatusGlyph != null && prefs.bluetoothStatusVisible()) {
            int mode = bluetoothMode();
            bluetoothStatusGlyph.setBluetoothMode(mode);
            bluetoothStatusGlyph.setContentDescription(
                    mode == HeaderStatusGlyph.BLUETOOTH_CONNECTED
                            ? "Bluetooth connected"
                            : mode == HeaderStatusGlyph.BLUETOOTH_ON
                            ? "Bluetooth on, not connected"
                            : "Bluetooth off");
        }
        if (soundStatusGlyph != null && prefs.soundStatusVisible()) {
            int mode = notificationSoundMode();
            soundStatusGlyph.setSoundMode(mode);
            soundStatusGlyph.setContentDescription(
                    mode == HeaderStatusGlyph.SOUND_NORMAL
                            ? "Notification sounds on"
                            : mode == HeaderStatusGlyph.SOUND_VIBRATE
                            ? "Notification sounds on vibrate"
                            : "Notification sounds silent");
        }
    }

    /**
     * Reads Bluetooth state without prompting for Bluetooth permission. An
     * enabled adapter with no active profile is "on", not "connected".
     */
    private int bluetoothMode() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        boolean enabled = false;
        if (adapter != null) {
            try {
                enabled = adapter.isEnabled();
            } catch (SecurityException ignored) {
                // Fall through to the global setting below.
            }
        }
        if (!enabled) {
            try {
                enabled = Settings.Global.getInt(getContentResolver(), "bluetooth_on", 0) != 0;
            } catch (Throwable ignored) {
                enabled = false;
            }
        }
        if (!enabled) return HeaderStatusGlyph.BLUETOOTH_OFF;
        if (adapter == null) return HeaderStatusGlyph.BLUETOOTH_ON;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return HeaderStatusGlyph.BLUETOOTH_ON;
        }

        int[] profiles = {
                BluetoothProfile.A2DP,
                BluetoothProfile.HEADSET,
                BluetoothProfile.HID_DEVICE,
                BluetoothProfile.GATT,
                BluetoothProfile.GATT_SERVER,
                BluetoothProfile.LE_AUDIO
        };
        for (int profile : profiles) {
            try {
                if (adapter.getProfileConnectionState(profile)
                        == BluetoothProfile.STATE_CONNECTED) {
                    return HeaderStatusGlyph.BLUETOOTH_CONNECTED;
                }
            } catch (Throwable ignored) {
                // Some profiles may be unavailable on this build.
            }
        }
        if (manager != null) {
            try {
                if (!manager.getConnectedDevices(BluetoothProfile.GATT).isEmpty()) {
                    return HeaderStatusGlyph.BLUETOOTH_CONNECTED;
                }
            } catch (Throwable ignored) {
                // Permission/profile unavailable.
            }
        }
        return HeaderStatusGlyph.BLUETOOTH_ON;
    }

    private int notificationSoundMode() {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return HeaderStatusGlyph.SOUND_SILENT;
        try {
            int ringer = audio.getRingerMode();
            if (ringer == AudioManager.RINGER_MODE_SILENT) {
                return HeaderStatusGlyph.SOUND_SILENT;
            }
            if (ringer == AudioManager.RINGER_MODE_VIBRATE) {
                return HeaderStatusGlyph.SOUND_VIBRATE;
            }
            if (audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 0) {
                return HeaderStatusGlyph.SOUND_SILENT;
            }
            NotificationManager notifications =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (notifications != null) {
                int filter = notifications.getCurrentInterruptionFilter();
                if (filter != NotificationManager.INTERRUPTION_FILTER_ALL
                        && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    return HeaderStatusGlyph.SOUND_SILENT;
                }
            }
            return HeaderStatusGlyph.SOUND_NORMAL;
        } catch (Throwable ignored) {
            return HeaderStatusGlyph.SOUND_SILENT;
        }
    }

    private void handleBattery(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = Math.round(level * 100f / scale);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int batteryColour;
        if (pct <= 15) batteryColour = 0xFFFF3D3D;
        else if (pct <= 40) batteryColour = 0xFFFFC107;
        else batteryColour = 0xFF00E6A8;
        batteryColour = ColorTuning.colour(batteryColour);
        batteryText.setText(pct + "%");
        batteryText.setTextColor(batteryColour);
        batteryText.setShadowLayer(Ui.dpf(this, ColorTuning.radius(2.2f)), 0f, 0f,
                ColorTuning.withAlpha(batteryColour, ColorTuning.glowAlpha(0xA0)));
        if (batteryGlyph != null) batteryGlyph.setLevel(pct, charging);
    }

    // ------------------------------------------------------------------
    // Image picker
    // ------------------------------------------------------------------

    private void exportSetup() {
        if (store == null) return;
        store.save();
        LToast.makeText(this, "Saving setup…", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String name = SetupBackup.export(app);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            LToast.makeText(MainActivity.this,
                                    "Setup saved to Downloads/" + name,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            LToast.makeText(MainActivity.this,
                                    "Could not save the setup",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "aurora-setup-export").start();
    }

    private void pickSetupBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
        });
        LToast.makeText(this, "Choose an Pavo setup ZIP", Toast.LENGTH_SHORT).show();
        try {
            startActivityForResult(intent, REQ_IMPORT_SETUP);
        } catch (Throwable t) {
            LToast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmImportSetup(final SetupBackup.Snapshot snapshot) {
        if (snapshot == null) return;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 18),
                Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Restore setup?");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        panel.addView(title);

        TextView message = new LTextView(this);
        message.setText("This replaces your current home layout, icons, wallpaper, dock and Pavo preferences.");
        message.setTextColor(0xB3FFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        message.setPadding(0, Ui.dp(this, 8), 0, 0);
        panel.addView(message);

        TextView details = new LTextView(this);
        details.setText(snapshot.archiveName
                + "  ·  " + snapshot.pageCount + " pages"
                + "  ·  " + snapshot.itemCount + " icons"
                + "  ·  " + snapshot.widgetCount + " widgets");
        details.setTextColor(0x73FFFFFF);
        details.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        details.setPadding(0, Ui.dp(this, 6), 0, 0);
        panel.addView(details);

        TextView safety = new LTextView(this);
        safety.setText("A safety copy of the current setup will be saved to Downloads first.");
        safety.setTextColor(0x73FFFFFF);
        safety.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        safety.setPadding(0, Ui.dp(this, 6), 0, 0);
        panel.addView(safety);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(this, 16), 0, 0);

        TextView cancel = new LTextView(this);
        cancel.setText("Cancel");
        styleTextButton(cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
            }
        });
        buttons.addView(cancel);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView restore = new LTextView(this);
        restore.setText("Restore");
        styleTextButton(restore, true);
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
                applySetupImport(snapshot);
            }
        });
        buttons.addView(restore);
        panel.addView(buttons);

        showGlassDialog(dialog, panel, backdrop, 450, 0f);
        attachCardController(dialog, panel, panel);
        dialog.show();
    }

    private void applySetupImport(final SetupBackup.Snapshot snapshot) {
        if (snapshot == null) return;
        store.save();
        LToast.makeText(this, "Restoring setup…", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String safetyName;
                try {
                    safetyName = SetupBackup.export(app);
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            LToast.makeText(MainActivity.this,
                                    "Could not save the current setup; restore canceled",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                try {
                    SetupBackup.apply(app, snapshot);
                    final String saved = safetyName;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            LToast.makeText(MainActivity.this,
                                    "Setup restored · safety copy in Downloads/" + saved,
                                    Toast.LENGTH_LONG).show();
                            recreate();
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            LToast.makeText(MainActivity.this,
                                    "Could not restore the setup",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "aurora-setup-restore").start();
    }

    private void launchImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable t) {
            LToast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show();
        }
    }

    /** Wallpaper picker that accepts stills and video files. */
    private void launchWallpaperPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable t) {
            LToast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            prefs.setWeatherUseLocation(granted);
            refreshWeather(true);
            LToast.makeText(this, granted ? "Using device location for weather"
                    : "Using approximate location", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_SETUP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            LToast.makeText(this, "Reading setup…", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final SetupBackup.Snapshot snapshot = SetupBackup.read(
                                MainActivity.this, uri);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                confirmImportSetup(snapshot);
                            }
                        });
                    } catch (Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                LToast.makeText(MainActivity.this,
                                        "That is not a valid Pavo setup backup",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }, "aurora-setup-read").start();
            return;
        }
        if (requestCode == REQ_WIDGET_BIND) {
            if (pendingWidgetInfo != null && pendingWidgetId >= 0) {
                boolean bound = false;
                try {
                    bound = widgetManager.bindAppWidgetIdIfAllowed(
                            pendingWidgetId, pendingWidgetInfo.provider);
                } catch (Throwable ignored) {
                }
                if (bound || resultCode == RESULT_OK) {
                    startWidgetConfigurationOrPlace();
                } else {
                    cleanupPendingWidget();
                    LToast.makeText(this, "Widget access was not granted",
                            Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if (requestCode == REQ_WIDGET_CONFIGURE) {
            if (resultCode == RESULT_OK) {
                placePendingWidget();
            } else {
                cleanupPendingWidget();
            }
            return;
        }
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        if (pickMode == PICK_DOCK_ICON) {
            try {
                Bitmap bmp = decodeScaled(data.getData(), 320);
                if (bmp == null) throw new IllegalStateException();
                saveDockIcon(bmp);
                populateDock();
                LToast.makeText(this, "Drawer icon updated", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                LToast.makeText(this, "Could not load that image", Toast.LENGTH_SHORT).show();
            }
            pickMode = PICK_WALLPAPER;
            return;
        }
        if (pickMode == PICK_ICON && pickTarget != null) {
            try {
                Bitmap bmp = decodeScaled(data.getData(), 320);
                if (bmp == null) throw new IllegalStateException();
                store.setIcon(pickTarget, bmp);
                refreshVisible();
                LToast.makeText(this, "Icon updated", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                LToast.makeText(this, "Could not load that image", Toast.LENGTH_SHORT).show();
            }
            pickTarget = null;
            pickMode = PICK_WALLPAPER;
        } else if (isVideoUri(data.getData())) {
            applyPickedVideo(data.getData());
        } else {
            try {
                Bitmap bmp = decodeScaled(data.getData(), 2560);
                if (bmp == null) throw new IllegalStateException();
                FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), "wallpaper.png"));
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                fos.flush();
                fos.close();
                File archived = archiveWallpaper(bmp);
                if (archived != null) {
                    prefs.setWallpaperHistoryPath(archived.getAbsolutePath());
                }
                bmp.recycle();
                prefs.setWallpaperMode(Prefs.MODE_CUSTOM);
                wallpaper.setMode(Prefs.MODE_CUSTOM);
                applyWallpaperPresentation();
                LToast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                LToast.makeText(this, "Could not load that image", Toast.LENGTH_SHORT).show();
            }
            pickMode = PICK_WALLPAPER;
        }
    }

    private File wallpaperHistoryDir() {
        File dir = new File(getFilesDir(), "wallpapers");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File archiveWallpaper(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            File dir = wallpaperHistoryDir();
            File out = new File(dir, "wallpaper_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fos = new FileOutputStream(out);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            fos.flush();
            fos.close();
            File[] files = dir.listFiles();
            if (files != null && files.length > 16) {
                java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (int i = 0; i < files.length - 16; i++) files[i].delete();
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<File> wallpaperHistory() {
        File dir = wallpaperHistoryDir();
        File[] files = dir.listFiles();
        List<File> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg")
                        || n.endsWith(".png") || n.endsWith(".webp"))) out.add(f);
            }
        }
        if (out.isEmpty()) {
            File current = new File(getFilesDir(), "wallpaper.png");
            if (current.isFile()) {
                File copy = new File(dir, "wallpaper_" + System.currentTimeMillis() + ".jpg");
                try {
                    FileInputStream in = new FileInputStream(current);
                    FileOutputStream os = new FileOutputStream(copy);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    in.close(); os.close();
                    out.add(copy);
                } catch (Throwable ignored) {
                }
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private Bitmap wallpaperThumbnail(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) > 320
                    || bounds.outHeight / (sample * 2) > 320) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when a picked document is a video rather than a still image. */
    private boolean isVideoUri(Uri uri) {
        if (uri == null) return false;
        try {
            String type = getContentResolver().getType(uri);
            if (type != null) {
                if (type.startsWith("video/")) return true;
                if (type.startsWith("image/")) return false;
            }
        } catch (Throwable ignored) {
        }
        String name = displayNameForUri(uri);
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".mkv") || lower.endsWith(".mov")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp");
    }

    private String displayNameForUri(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return c.getString(index);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean copyUriToFile(Uri uri, File dest) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return false;
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            out.close();
            in.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A video chosen from the picker becomes the active video wallpaper. */
    private void applyPickedVideo(Uri uri) {
        String name = displayNameForUri(uri);
        if (name == null || name.trim().isEmpty()) {
            name = "wallpaper_" + System.currentTimeMillis() + ".mp4";
        }
        name = name.replace('/', '_').replace('\\', '_');
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".mkv") || lower.endsWith(".mov")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp"))) {
            name += ".mp4";
        }

        File dir = wallpaperDir();
        final File dest = uniqueVideoFile(dir, name);
        new Thread(() -> {
            File target = dest;
            boolean ok = copyUriToFile(uri, dest);
            if (!ok) {
                dest.delete();
                target = new File(getFilesDir(),
                        "video_wallpaper_" + System.currentTimeMillis() + ".mp4");
                ok = copyUriToFile(uri, target);
            }
            final boolean copied = ok;
            final File chosen = target;
            runOnUiThread(() -> {
                if (!copied || !chosen.isFile() || chosen.length() == 0) {
                    LToast.makeText(this, "Could not load that video",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                applyVideoWallpaper(chosen);
                LToast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show();
            });
        }, "aurora-picked-video").start();
    }

    /** Never overwrite an existing clip: pick the next free "name (2)" slot. */
    private static File uniqueVideoFile(File dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".mp4";
        File candidate = new File(dir, name);
        int n = 2;
        while (candidate.exists()) {
            candidate = new File(dir, base + " (" + n + ")" + ext);
            n++;
        }
        return candidate;
    }

    /** True when this history file is the still wallpaper currently on screen. */
    private boolean isActiveHistoryFile(File file) {
        if (file == null || prefs == null || prefs.wallpaperMode() != Prefs.MODE_CUSTOM) {
            return false;
        }
        String active = prefs.wallpaperHistoryPath();
        if (active != null && !active.isEmpty()) {
            return active.equals(file.getAbsolutePath());
        }
        // Wallpapers applied before the path was recorded: applyWallpaperFile
        // copies bytes verbatim, so identical sizes identify the current file.
        try {
            File current = new File(getFilesDir(), "wallpaper.png");
            if (current.isFile() && file.isFile() && current.length() > 0
                    && current.length() == file.length()) {
                prefs.setWallpaperHistoryPath(file.getAbsolutePath());
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Delete a saved still or video wallpaper from the card. */
    private void removeWallpaperItem(Object item) {
        if (item instanceof VideoWallpaper) {
            File file = ((VideoWallpaper) item).file;
            boolean active = prefs.wallpaperMode() == Prefs.MODE_VIDEO
                    && file.getAbsolutePath().equals(prefs.videoWallpaper());
            if (active) {
                prefs.setWallpaperMode(Prefs.MODE_DEVICE);
                wallpaper.setMode(Prefs.MODE_DEVICE);
                applyWallpaperPresentation();
            }
            if (!file.delete()) {
                LToast.makeText(this, "Operation failed", Toast.LENGTH_SHORT).show();
            }
        } else if (item instanceof File) {
            File file = (File) item;
            boolean active = prefs.wallpaperMode() == Prefs.MODE_CUSTOM
                    && file.getAbsolutePath().equals(prefs.wallpaperHistoryPath());
            if (active) {
                prefs.setWallpaperHistoryPath("");
                prefs.setWallpaperMode(Prefs.MODE_DEVICE);
                wallpaper.setMode(Prefs.MODE_DEVICE);
                applyWallpaperPresentation();
            }
            if (!file.delete()) {
                LToast.makeText(this, "Operation failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void applyWallpaperFile(File source) {
        try {
            FileInputStream in = new FileInputStream(source);
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "wallpaper.png"));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close(); out.close();
            source.setLastModified(System.currentTimeMillis());
            prefs.setWallpaperHistoryPath(source.getAbsolutePath());
            prefs.setWallpaperMode(Prefs.MODE_CUSTOM);
            wallpaper.setMode(Prefs.MODE_CUSTOM);
            applyWallpaperPresentation();
            final File saved = new File(getFilesDir(), "wallpaper.png");
            new Thread(() -> setSystemWallpaperFromFile(saved),
                    "aurora-system-wallpaper").start();
            LToast.makeText(this, "Wallpaper updated", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            LToast.makeText(this, "Could not load that image", Toast.LENGTH_SHORT).show();
        }
    }

    /** A live wallpaper service that can be previewed and applied. */
    private static final class LiveWallpaper {
        ComponentName component;
        String label;
        Drawable thumbnail;
        boolean active;
    }

    private List<LiveWallpaper> liveWallpapers() {
        List<LiveWallpaper> out = new ArrayList<>();
        try {
            PackageManager pm = getPackageManager();
            Intent query = new Intent(WallpaperService.SERVICE_INTERFACE);
            List<ResolveInfo> services = pm.queryIntentServices(query,
                    PackageManager.GET_META_DATA);
            WallpaperInfo currentInfo = WallpaperManager.getInstance(this)
                    .getWallpaperInfo();
            ComponentName current = currentInfo == null ? null : currentInfo.getComponent();
            for (ResolveInfo ri : services) {
                ServiceInfo si = ri.serviceInfo;
                if (si == null) continue;
                if (!"android.permission.BIND_WALLPAPER".equals(si.permission)) continue;
                if (si.metaData == null
                        || !si.metaData.containsKey("android.service.wallpaper")) continue;
                ComponentName component = new ComponentName(si.packageName, si.name);
                if (isImageWallpaper(component)) continue;
                LiveWallpaper item = new LiveWallpaper();
                item.component = component;
                try {
                    item.label = si.loadLabel(pm).toString();
                } catch (Throwable ignored) {
                    item.label = si.packageName;
                }
                item.thumbnail = liveThumbnail(si);
                item.active = component.equals(current);
                out.add(item);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private Drawable liveThumbnail(ServiceInfo si) {
        try {
            XmlResourceParser parser = si.loadXmlMetaData(getPackageManager(),
                    "android.service.wallpaper");
            if (parser != null) {
                try {
                    int type;
                    while ((type = parser.next()) != XmlResourceParser.END_DOCUMENT
                            && type != XmlResourceParser.START_TAG) {
                    }
                    String ns = "http://schemas.android.com/apk/res/android";
                    int res = parser.getAttributeResourceValue(ns, "thumbnail", 0);
                    if (res != 0) {
                        Drawable drawable = getPackageManager().getDrawable(
                                si.packageName, res, si.applicationInfo);
                        if (drawable != null) return drawable;
                    }
                } finally {
                    parser.close();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return si.loadIcon(getPackageManager());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void openLiveWallpaper(ComponentName component) {
        if (component == null) return;
        pendingLiveWallpaper = true;
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component);
            startActivity(intent);
        } catch (Throwable t) {
            pendingLiveWallpaper = false;
            LToast.makeText(this, "Could not open live wallpaper", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isImageWallpaper(ComponentName component) {
        if (component == null) return false;
        String name = component.getClassName();
        return component.getPackageName().equals("com.android.systemui")
                && name != null && name.contains("ImageWallpaper");
    }

    private boolean shouldShowLiveWallpaper() {
        if (prefs == null || prefs.wallpaperMode() != Prefs.MODE_DEVICE) return false;
        try {
            WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
            return info != null && !isImageWallpaper(info.getComponent());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The launcher normally paints its own wallpaper copy. When a real live
     * wallpaper is active, hide that copy and let the system wallpaper window
     * show through the transparent home surface instead.
     */
    /**
     * First launch: seed the owner's layout, dock and transparency values from
     * the bundled defaults so a fresh install looks finished out of the box.
     */
    private void installDefaultPrefs() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(Prefs.FILE, MODE_PRIVATE);
            if (sp.getBoolean("defaults_seeded", false)) return;
            java.io.InputStream in = getAssets().open("default_prefs.xml");
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            android.content.SharedPreferences.Editor edit = sp.edit();
            int type;
            while ((type = parser.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (type != org.xmlpull.v1.XmlPullParser.START_TAG) continue;
                String name = parser.getAttributeValue(null, "name");
                if (name == null) continue;
                String tag = parser.getName();
                String text = parser.nextText();
                try {
                    if ("string".equals(tag)) edit.putString(name, text);
                    else if ("int".equals(tag)) edit.putInt(name, Integer.parseInt(text.trim()));
                    else if ("boolean".equals(tag)) edit.putBoolean(name, "true".equals(text.trim()));
                    else if ("float".equals(tag)) edit.putFloat(name, Float.parseFloat(text.trim()));
                    else if ("long".equals(tag)) edit.putLong(name, Long.parseLong(text.trim()));
                } catch (Throwable ignored) {
                }
            }
            in.close();
            edit.putBoolean("defaults_seeded", true);
            edit.commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * First launch: install the bundled wallpaper so the home never starts
     * bare. Only applies while the user has not chosen anything themselves.
     */
    private void ensureDefaultWallpaper() {
        try {
            if (prefs == null || prefs.wallpaperMode() != Prefs.MODE_DEVICE) return;
            final File target = new File(getFilesDir(), "wallpaper.png");
            if (!target.isFile() || target.length() == 0) {
                java.io.InputStream in = getAssets().open("default_wallpaper.jpg");
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
            }
            prefs.setWallpaperMode(Prefs.MODE_CUSTOM);
            if (wallpaper != null) wallpaper.setMode(Prefs.MODE_CUSTOM);
            applyWallpaperPresentation();
            new Thread(() -> setSystemWallpaperFromFile(target),
                    "pavo-system-wallpaper").start();
        } catch (Throwable ignored) {
        }
    }

    private void applyWallpaperPresentation() {
        if (root == null || wallpaper == null) return;
        Window window = getWindow();
        int mode = prefs == null ? Prefs.MODE_DEVICE : prefs.wallpaperMode();
        String videoPath = prefs == null ? "" : prefs.videoWallpaper();
        boolean hasVideo = mode == Prefs.MODE_VIDEO && videoPath != null
                && !videoPath.isEmpty() && new File(videoPath).isFile();
        if (mode == Prefs.MODE_VIDEO && !hasVideo) {
            prefs.setWallpaperMode(Prefs.MODE_DEVICE);
            mode = Prefs.MODE_DEVICE;
        }
        if (hasVideo) {
            wallpaper.setAccentTracking(false);
            wallpaper.setVisibility(View.GONE);
            videoWallpaper.setVisibility(View.VISIBLE);
            videoWallpaper.setVideo(videoPath);
            videoWallpaper.play();
            root.setBackgroundColor(Color.BLACK);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            return;
        }
        videoWallpaper.setVisibility(View.GONE);
        videoWallpaper.stop();
        if (shouldShowLiveWallpaper()) {
            wallpaper.setAccentTracking(false);
            wallpaper.setVisibility(View.GONE);
            root.setBackgroundColor(Color.TRANSPARENT);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            updateAccentFromLiveWallpaper();
        } else {
            wallpaper.setAccentTracking(true);
            wallpaper.setVisibility(View.VISIBLE);
            root.setBackgroundColor(Color.BLACK);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }
    }

    private void activateWallpaperItem(Object item) {
        if (item instanceof LiveWallpaper) {
            openLiveWallpaper(((LiveWallpaper) item).component);
        } else if (item instanceof VideoWallpaper) {
            applyVideoWallpaper(((VideoWallpaper) item).file);
        } else if (item instanceof File) {
            applyWallpaperFile((File) item);
        }
    }

    private void setSystemWallpaper(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            WallpaperManager.getInstance(this).setBitmap(bitmap, null, true,
                    WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable ignored) {
        }
    }

    private void setSystemWallpaperFromFile(File file) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth > 0 && bounds.outHeight > 0
                    && (bounds.outWidth / (sample * 2) >= 2560
                    || bounds.outHeight / (sample * 2) >= 2560)) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            setSystemWallpaper(bitmap);
        } catch (Throwable ignored) {
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private void setSystemWallpaperFromVideo(File file) {
        MediaMetadataRetriever retriever = null;
        Bitmap frame = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            frame = retriever.getScaledFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1080);
            setSystemWallpaper(frame);
        } catch (Throwable ignored) {
        } finally {
            if (frame != null) frame.recycle();
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void applyVideoWallpaper(File file) {
        if (file == null || !file.isFile()) return;
        prefs.setVideoWallpaper(file.getAbsolutePath());
        prefs.setWallpaperMode(Prefs.MODE_VIDEO);
        applyWallpaperPresentation();
        final File video = file;
        new Thread(() -> setSystemWallpaperFromVideo(video),
                "aurora-system-wallpaper-video").start();
    }

    private void updateAccentFromLiveWallpaper() {
        try {
            WallpaperColors colors = WallpaperManager.getInstance(this)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors == null || colors.getPrimaryColor() == null) return;
            int primary = colors.getPrimaryColor().toArgb();
            ThemeColors.set(primary);
            if (prefs != null) prefs.setThemeAccent(primary);
            if (wallpaper != null) wallpaper.setAccent(primary);
            refreshBuiltinIcons();
        } catch (Throwable ignored) {
        }
    }

    private static final class VideoWallpaper {
        File file;
        String label;
        Bitmap thumbnail;
    }

    /** Videos moved here from the pre-rename "Pavo Wallpapers" folder. */
    private static File wallpaperDir() {
        File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File dir = new File(base, "Pavo Wallpapers");
        File legacy = new File(base, "Pavo Wallpapers");
        try {
            if (legacy.isDirectory()) {
                if (!dir.exists()) dir.mkdirs();
                File[] old = legacy.listFiles();
                if (old != null) {
                    for (File f : old) {
                        if (f == null || !f.isFile()) continue;
                        File target = new File(dir, f.getName());
                        if (!target.exists()) {
                            if (!f.renameTo(target)) {
                                java.io.FileInputStream in = new java.io.FileInputStream(f);
                                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                                byte[] buf = new byte[64 * 1024];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                                in.close();
                                out.close();
                                f.delete();
                            }
                        }
                    }
                }
                if (legacy.list() != null && legacy.list().length == 0) legacy.delete();
            }
        } catch (Throwable ignored) {
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private List<VideoWallpaper> videoWallpapers() {
        List<VideoWallpaper> out = new ArrayList<>();
        File dir = wallpaperDir();
        File[] files = dir.listFiles();
        if (files == null) return out;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName().toLowerCase(java.util.Locale.getDefault());
            if (!(name.endsWith(".mp4") || name.endsWith(".webm")
                    || name.endsWith(".mkv") || name.endsWith(".mov"))) continue;
            VideoWallpaper item = new VideoWallpaper();
            item.file = file;
            int dot = file.getName().lastIndexOf('.');
            item.label = dot > 0 ? file.getName().substring(0, dot) : file.getName();
            item.thumbnail = videoThumbnail(file);
            out.add(item);
        }
        return out;
    }

    private Bitmap videoThumbnail(File file) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getScaledFrameAtTime(0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 144);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void showWallpaperCard() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final ControllerNav nav = new ControllerNav(this, null);
        ControllerNav.RootLayout root = new ControllerNav.RootLayout(this);
        root.setNavigator(nav);
        root.setBackgroundColor(0x00000000);
        root.setFocusable(false);
        Backdrop backdrop = captureBackdrop(false, 0);
        View dim = new View(this);
        dim.setBackgroundColor(0x4D000000);
        root.addView(dim, match());
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        GlassPanel card = new GlassPanel(this, backdrop, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        card.setFrosted(true);
        card.setScrimAlpha(0xC4);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.setClickable(true);
        int cw = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32), Ui.dp(this, 720));
        int ch = Math.min(getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 32), Ui.dp(this, 620));
        root.addView(card, new FrameLayout.LayoutParams(cw, ch, Gravity.CENTER));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new LTextView(this);
        title.setText("WALLPAPER");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.10f);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new LTextView(this);
        close.setText("\u00d7");
        close.setTextColor(0xCCFFFFFF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 34)));
        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new LTextView(this);
        hint.setText("Live, video and recent wallpapers");
        hint.setTextColor(0xB8FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        hint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final List<File> history = wallpaperHistory();
        final List<LiveWallpaper> live = liveWallpapers();
        final List<VideoWallpaper> videos = videoWallpapers();
        final List<Object> items = new ArrayList<>();
        items.addAll(live);
        items.addAll(videos);
        items.addAll(history);
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        grid.setVerticalSpacing(Ui.dp(this, 8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Only one delete affordance is visible at a time.
        final TextView[] deleteButton = {null};
        final BaseAdapter[] adapterHolder = new BaseAdapter[1];
        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Object item = items.get(position);
                boolean isLive = item instanceof LiveWallpaper;
                boolean isVideo = item instanceof VideoWallpaper;
                boolean isActive = (isLive && ((LiveWallpaper) item).active)
                        || (isVideo && prefs.wallpaperMode() == Prefs.MODE_VIDEO
                        && ((VideoWallpaper) item).file.getAbsolutePath()
                        .equals(prefs.videoWallpaper()))
                        || (!isLive && !isVideo && isActiveHistoryFile((File) item));

                LinearLayout tile = new LinearLayout(MainActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                int tileBg = isActive ? ThemeColors.withAlpha(0x30) : 0x33000000;
                tile.setBackground(Ui.rounded(tileBg, Ui.dp(MainActivity.this, 12),
                        isActive ? ThemeColors.accent() : 0x22FFFFFF,
                        Ui.dpf(MainActivity.this, 1)));

                final FrameLayout photo = new FrameLayout(MainActivity.this);
                tile.addView(photo, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

                ImageView image = new ImageView(MainActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                // Clip the artwork to the tile's rounded corners; otherwise the
                // square bitmap bleeds through the white focus/navigation ring.
                final float tileRadius = Ui.dpf(MainActivity.this, 12);
                image.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), tileRadius);
                    }
                });
                image.setClipToOutline(true);
                if (isLive) {
                    LiveWallpaper liveItem = (LiveWallpaper) item;
                    if (liveItem.thumbnail != null) {
                        image.setImageDrawable(liveItem.thumbnail);
                    } else {
                        image.setImageBitmap(Apps.letterTile(liveItem.label,
                                Ui.dp(MainActivity.this, 96)));
                    }
                } else if (isVideo) {
                    VideoWallpaper videoItem = (VideoWallpaper) item;
                    if (videoItem.thumbnail != null) {
                        image.setImageBitmap(videoItem.thumbnail);
                    } else {
                        image.setImageBitmap(Apps.letterTile(videoItem.label,
                                Ui.dp(MainActivity.this, 96)));
                    }
                } else {
                    Bitmap thumb = wallpaperThumbnail((File) item);
                    if (thumb != null) image.setImageBitmap(thumb);
                }
                photo.addView(image, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                if (isLive || isVideo) {
                    TextView badge = new LTextView(MainActivity.this);
                    badge.setText(isLive ? "LIVE" : "VIDEO");
                    badge.setTextColor(0xFF071015);
                    badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
                    badge.setLetterSpacing(0.12f);
                    badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    badge.setPadding(Ui.dp(MainActivity.this, 6),
                            Ui.dp(MainActivity.this, 2),
                            Ui.dp(MainActivity.this, 6),
                            Ui.dp(MainActivity.this, 2));
                    badge.setBackground(Ui.rounded(ThemeColors.accent(),
                            Ui.dpf(MainActivity.this, 7)));
                    FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.START);
                    badgeLp.setMargins(Ui.dp(MainActivity.this, 5),
                            0, 0, Ui.dp(MainActivity.this, 5));
                    photo.addView(badge, badgeLp);
                }

                // "In use" pill on the artwork, matching the LIVE/VIDEO badge.
                if (isActive) {
                    TextView inUse = new LTextView(MainActivity.this);
                    inUse.setText("IN USE");
                    inUse.setTextColor(0xFF071015);
                    inUse.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
                    inUse.setLetterSpacing(0.12f);
                    inUse.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    inUse.setPadding(Ui.dp(MainActivity.this, 6),
                            Ui.dp(MainActivity.this, 2),
                            Ui.dp(MainActivity.this, 6),
                            Ui.dp(MainActivity.this, 2));
                    inUse.setBackground(Ui.rounded(ThemeColors.accent(),
                            Ui.dpf(MainActivity.this, 7)));
                    FrameLayout.LayoutParams inUseLp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.END);
                    inUseLp.setMargins(0, 0, Ui.dp(MainActivity.this, 5),
                            Ui.dp(MainActivity.this, 5));
                    photo.addView(inUse, inUseLp);
                }

                tile.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Ui.dp(MainActivity.this, 96)));
                tile.setClickable(false);
                tile.setFocusable(true);
                tile.setFocusableInTouchMode(false);

                final float[] down = new float[2];
                final boolean[] longFired = {false};
                final Object selectedItem = item;
                final Runnable longPress = new Runnable() {
                    @Override public void run() {
                        longFired[0] = true;
                        if (isLive) return;
                        if (deleteButton[0] != null) {
                            ViewGroup oldParent = (ViewGroup) deleteButton[0].getParent();
                            if (oldParent != null) oldParent.removeView(deleteButton[0]);
                            deleteButton[0] = null;
                        }
                        final TextView remove = new LTextView(MainActivity.this);
                        remove.setText("\u00d7");
                        remove.setTextColor(0xFFFFFFFF);
                        remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                        remove.setGravity(Gravity.CENTER);
                        remove.setTypeface(Typeface.create("sans-serif-medium",
                                Typeface.NORMAL));
                        remove.setBackground(Ui.rounded(0xE6101418,
                                Ui.dpf(MainActivity.this, 11), ThemeColors.accent(),
                                Ui.dpf(MainActivity.this, 1)));
                        remove.setClickable(true);
                        remove.setFocusable(true);
                        remove.setFocusableInTouchMode(true);
                        FrameLayout.LayoutParams removeLp = new FrameLayout.LayoutParams(
                                Ui.dp(MainActivity.this, 22),
                                Ui.dp(MainActivity.this, 22),
                                Gravity.TOP | Gravity.END);
                        removeLp.setMargins(0, Ui.dp(MainActivity.this, 4),
                                Ui.dp(MainActivity.this, 4), 0);
                        remove.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                ViewGroup oldParent = (ViewGroup) remove.getParent();
                                if (oldParent != null) oldParent.removeView(remove);
                                if (deleteButton[0] == remove) deleteButton[0] = null;
                                removeWallpaperItem(selectedItem);
                                items.clear();
                                items.addAll(live);
                                items.addAll(videoWallpapers());
                                items.addAll(wallpaperHistory());
                                if (adapterHolder[0] != null) {
                                    adapterHolder[0].notifyDataSetChanged();
                                }
                            }
                        });
                        photo.addView(remove, removeLp);
                        deleteButton[0] = remove;
                        remove.requestFocus();
                    }
                };

                tile.setOnTouchListener((v, event) -> {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            down[0] = event.getX();
                            down[1] = event.getY();
                            longFired[0] = false;
                            v.postDelayed(longPress, 550L);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (Math.hypot(event.getX() - down[0],
                                    event.getY() - down[1]) > Ui.dp(MainActivity.this, 12)) {
                                v.removeCallbacks(longPress);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            v.removeCallbacks(longPress);
                            float dx = event.getX() - down[0];
                            float dy = event.getY() - down[1];
                            if (!longFired[0]
                                    && Math.hypot(dx, dy) < Ui.dp(MainActivity.this, 12)) {
                                dialog.dismiss();
                                activateWallpaperItem(selectedItem);
                            }
                            return true;
                        case MotionEvent.ACTION_CANCEL:
                            v.removeCallbacks(longPress);
                            return true;
                        default:
                            return true;
                    }
                });
                return tile;
            }
        };
        adapterHolder[0] = adapter;
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            Object item = items.get(position);
            dialog.dismiss();
            activateWallpaperItem(item);
        });
        card.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (items.isEmpty()) {
            TextView empty = new LTextView(this);
            empty.setText("No saved wallpapers yet");
            empty.setTextColor(0x80FFFFFF);
            empty.setGravity(Gravity.CENTER);
            card.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            grid.setVisibility(View.GONE);
        }

        TextView choose = new LTextView(this);
        choose.setText("CHOOSE IMAGE OR VIDEO");
        choose.setGravity(Gravity.CENTER);
        choose.setTextColor(0xFF071015);
        choose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        choose.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        choose.setBackground(Ui.rounded(ThemeColors.accent(), Ui.dpf(this, 10),
                0x55FFFFFF, Ui.dpf(this, 1)));
        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                pickMode = PICK_WALLPAPER;
                launchWallpaperPicker();
            }
        });
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
        chooseLp.topMargin = Ui.dp(this, 10);
        choose.setFocusable(true);
        choose.setClickable(true);
        card.addView(choose, chooseLp);

        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { nav.release(); }
        });
        dialog.setContentView(root, match());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;
            window.setAttributes(lp);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        nav.attachDialog(dialog, card);
        card.postDelayed(new Runnable() {
            @Override public void run() {
                if (grid.getVisibility() == View.VISIBLE) {
                    grid.requestFocus();
                } else {
                    choose.requestFocus();
                }
            }
        }, 180L);
        Window shown = dialog.getWindow();
        if (shown != null) {
            shown.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            WindowInsetsController c = shown.getDecorView().getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    private Bitmap decodeScaled(Uri uri, int max) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream s = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(s, null, bounds);
        if (s != null) s.close();

        int sample = 1;
        while (bounds.outWidth > 0 && bounds.outHeight > 0
                && (bounds.outWidth / (sample * 2) >= max
                || bounds.outHeight / (sample * 2) >= max)) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        InputStream s2 = getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(s2, null, opts);
        if (s2 != null) s2.close();
        return bmp;
    }

    // ------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------

    private interface IntCallback {
        void onValue(int value);
    }

    private interface BoolCallback {
        void onValue(boolean value);
    }

    private void showSettingsDialog() {
        showSettingsDialog(Float.NaN, Float.NaN);
    }

    private void showSettingsDialog(View anchor) {
        if (anchor == null) {
            showSettingsDialog();
            return;
        }
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        showSettingsDialog(loc[0] + anchor.getWidth() / 2f,
                loc[1] + anchor.getHeight() / 2f);
    }

    private final class SettingsRootLayout extends FrameLayout {
        SettingsRootLayout(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (handleSettingsStick(event)) return true;
            return super.dispatchGenericMotionEvent(event);
        }
    }

    private final class SettingsBody extends LinearLayout {
        private LinearLayout target;

        SettingsBody(Context c) {
            super(c);
            setOrientation(VERTICAL);
        }

        void use(String key) {
            target = null;
            if (key == null) return;
            LinearLayout panel = settingsCategoryPanels.get(key);
            if (panel == null) {
                panel = new LinearLayout(MainActivity.this);
                panel.setOrientation(VERTICAL);
                settingsCategoryPanels.put(key, panel);
            }
            target = panel;
        }

        @Override
        public void addView(View child) {
            if (target != null) target.addView(child);
            else super.addView(child);
        }

        @Override
        public void addView(View child, int index) {
            if (target != null) target.addView(child, index);
            else super.addView(child, index);
        }

        @Override
        public void addView(View child, ViewGroup.LayoutParams params) {
            if (target != null) target.addView(child, params);
            else super.addView(child, params);
        }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (target != null) target.addView(child, index, params);
            else super.addView(child, index, params);
        }
    }

    private static int settingsIconColor(String key) {
        if (key == null) return 0xFF8BE9FF;
        switch (key) {
            case "Dock": return 0xFF00C2FF;
            case "Transparency": return 0xFF00E6A8;
            case "Builtins": return 0xFF00E6A8;
            case "Menus": return 0xFF6EE7FF;
            case "Home": return 0xFFFF8A00;
            case "Display": return 0xFFFFC857;
            case "Colors": return 0xFFFF6FD8;
            case "Gestures": return 0xFF8B5CF6;
            case "Feedback": return 0xFFFFC107;
            case "Controls": return 0xFF00C2FF;
            case "Drawer": return 0xFF2F8CFF;
            case "Widgets": return 0xFFFF4FA3;
            case "Performance": return 0xFFFF3D3D;
            case "Wallpaper": return 0xFF00D9C0;
            case "Backup": return 0xFF7CFFB2;
            case "Language": return 0xFFFFD166;
            default: return 0xFF8BE9FF;
        }
    }

    private View makeSettingsCategoryRow(String title, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 8));
        row.setBackground(Ui.ripple(this, Ui.dpf(this, 12)));
        row.setClickable(true);

        SettingsOptionIcon icon = new SettingsOptionIcon(this, key, settingsIconColor(key));
        settingsCategoryIcons.add(icon);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 28));
        iconLp.rightMargin = Ui.dp(this, 11);
        row.addView(icon, iconLp);

        TextView label = new LTextView(this);
        label.setText(title);
        label.setTextColor(0xF2FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setPadding(Ui.dp(this, 9), Ui.dp(this, 3),
                Ui.dp(this, 9), Ui.dp(this, 3));
        label.setBackground(Ui.rounded(0x0EFFFFFF, Ui.dpf(this, 10),
                0x44FFFFFF, Ui.dpf(this, 1)));
        row.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(new Space(this), new LinearLayout.LayoutParams(
                0, 1, 1f));

        TextView arrow = new LTextView(this);
        arrow.setText("\u203a");
        arrow.setTextColor(0x99FFFFFF);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                openSettingsSubmenu(key, title,
                        loc[0] + v.getWidth() / 2f,
                        loc[1] + v.getHeight() / 2f);
            }
        });
        return row;
    }

    private void openSettingsSubmenu(String key, String title,
                                     float touchX, float touchY) {
        closeSettingsSubmenu();
        settingsSubmenuKey = key;
        final LinearLayout content = settingsCategoryPanels.get(key);
        if (content == null || settingsRootView == null || settingsCardView == null) return;
        if (content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        settingsSubmenuScrim = new View(this);
        settingsSubmenuScrim.setBackgroundColor(0x12000000);
        settingsSubmenuScrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                closeSettingsSubmenu();
            }
        });
        settingsSubmenuScrim.setAlpha(0f);
        settingsRootView.addView(settingsSubmenuScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settingsSubmenuScrim.animate().alpha(1f).setDuration(220).start();

        final boolean cardMoving = !settingsCardShifted;
        final float finalCardX;
        final float finalCardY;
        if (cardMoving) {
            finalCardX = Ui.dp(this, 12);
            finalCardY = Ui.dp(this, 12);
            settingsCardShifted = true;
            settingsCardView.animate()
                    .x(finalCardX)
                    .y(finalCardY)
                    .setDuration(340)
                    .setInterpolator(new PathInterpolator(0.18f, 0.78f, 0.20f, 1f))
                    .start();
        } else {
            finalCardX = settingsCardView.getX();
            finalCardY = settingsCardView.getY();
        }

        settingsSubmenuGlass = new GlassPanel(this, settingsBackdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        settingsSubmenuGlass.setBackdropSaturation(prefs.menuSaturation() / 100f);
        settingsSubmenuGlass.setBackdropDarkness(prefs.menuBackdropDarkness() / 100f);
        settingsSubmenuGlass.setClearGlass(true);
        settingsSubmenuGlass.setClearBodyAlpha(0x40);
        settingsSubmenuGlass.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        settingsSubmenuGlass.setPadding(Ui.dp(this, 14), Ui.dp(this, 12),
                Ui.dp(this, 14), Ui.dp(this, 8));
        ScrollView subScroll = new ScrollView(this);
        subScroll.setFillViewport(false);
        subScroll.setVerticalScrollBarEnabled(true);
        subScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        subScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY,
                                       int oldScrollX, int oldScrollY) {
                if (settingsFocusRing != null
                        && settingsFocusIndex >= 0
                        && settingsFocusIndex < settingsFocusList.size()) {
                    View target = settingsFocusList.get(settingsFocusIndex);
                    if (target instanceof SeekBar
                            || target instanceof android.widget.CompoundButton) {
                        View parent = target.getParent() instanceof View
                                ? (View) target.getParent() : target;
                        updateSettingsFocusRing(parent);
                    } else {
                        updateSettingsFocusRing(target);
                    }
                }
            }
        });
        LinearLayout subHeader = new LinearLayout(this);
        subHeader.setOrientation(LinearLayout.HORIZONTAL);
        subHeader.setGravity(Gravity.CENTER_VERTICAL);
        subHeader.setPadding(0, 0, 0, Ui.dp(this, 6));
        TextView subTitle = new LTextView(this);
        subTitle.setText(Loc.s(this, title) + "  \u283F");
        subTitle.setTextColor(wallpaper.accent());
        subTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subTitle.setLetterSpacing(0.10f);
        subTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subHeader.addView(subTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final float[] subDrag = new float[4];
        View.OnTouchListener subDragListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (settingsSubmenuGlass == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        subDrag[0] = e.getRawX();
                        subDrag[1] = e.getRawY();
                        subDrag[2] = settingsSubmenuGlass.getX();
                        subDrag[3] = settingsSubmenuGlass.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float x = subDrag[2] + e.getRawX() - subDrag[0];
                        float y = subDrag[3] + e.getRawY() - subDrag[1];
                        x = Math.max(Ui.dp(MainActivity.this, 4),
                                Math.min(x, screenW - settingsSubmenuGlass.getWidth()
                                        - Ui.dp(MainActivity.this, 4)));
                        y = Math.max(Ui.dp(MainActivity.this, 4),
                                Math.min(y, screenH - settingsSubmenuGlass.getHeight()
                                        - Ui.dp(MainActivity.this, 4)));
                        settingsSubmenuGlass.setX(x);
                        settingsSubmenuGlass.setY(y);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        Haptics.tick(settingsSubmenuGlass);
                        return true;
                    default:
                        return true;
                }
            }
        };
        subHeader.setOnTouchListener(subDragListener);
        subTitle.setOnTouchListener(subDragListener);
        settingsSubmenuGlass.addView(subHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsSubmenuGlass.addView(subScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int subW = Math.min(Ui.dp(this, 320), screenW - Ui.dp(this, 24));
        int subH = Math.min(settingsCardView.getHeight(), screenH - Ui.dp(this, 40));
        int cardX = Math.round(finalCardX);
        int cardY = Math.round(finalCardY);
        int x = cardX + settingsCardView.getWidth() + Ui.dp(this, 14);
        if (x + subW > screenW - Ui.dp(this, 12)) {
            x = cardX - subW - Ui.dp(this, 14);
        }
        x = Math.max(Ui.dp(this, 12), Math.min(x, screenW - subW - Ui.dp(this, 12)));
        int y = Math.max(Ui.dp(this, 12), Math.min(cardY, screenH - subH - Ui.dp(this, 12)));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(subW, subH);
        slp.leftMargin = x;
        slp.topMargin = y;
        settingsRootView.addView(settingsSubmenuGlass, slp);
        settingsSubmenuGlass.setVisibility(View.INVISIBLE);

        final GlassPanel openingPanel = settingsSubmenuGlass;
        openingPanel.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (settingsSubmenuGlass != openingPanel) return;
                playMagicLamp(openingPanel, touchX, touchY);
            }
        }, cardMoving ? 150 : 0);

        settingsCardView.setAlpha(1f);
        if (settingsFocusVeil != null) {
            settingsFocusVeil.animate().alpha(1f).setDuration(cardMoving ? 320 : 180).start();
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            settingsCardView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    Ui.dpf(this, 2.5f), Ui.dpf(this, 2.5f), Shader.TileMode.CLAMP));
        }
    }

    /** Open the main Launcher Settings card with the same lamp + giggle. */
    private void playSettingsCardOpen(final Dialog dialog, final FrameLayout root,
                                      final View cardFrame, float launchX, float launchY) {
        if (root == null || cardFrame == null || cardFrame.getWidth() <= 1
                || cardFrame.getHeight() <= 1) {
            if (cardFrame != null) {
                cardFrame.setVisibility(View.VISIBLE);
                Motion.giggleIn(cardFrame, 0.97f, 360L, 1.20f, 0.70f, false);
            }
            return;
        }
        int[] rootLoc = new int[2];
        int[] cardLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        cardFrame.getLocationOnScreen(cardLoc);
        float px = Float.isNaN(launchX) ? cardFrame.getWidth() / 2f
                : launchX - cardLoc[0];
        float py = Float.isNaN(launchY) ? cardFrame.getHeight() / 2f
                : launchY - cardLoc[1];
        cardFrame.setPivotX(Math.max(0f, Math.min(cardFrame.getWidth(), px)));
        cardFrame.setPivotY(Math.max(0f, Math.min(cardFrame.getHeight(), py)));
        cardFrame.setVisibility(View.VISIBLE);
        cardFrame.setAlpha(0f);
        cardFrame.setScaleX(0.94f);
        cardFrame.setScaleY(0.94f);
        cardFrame.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320L)
                .setInterpolator(new PathInterpolator(0.16f, 0.84f, 0.24f, 1f))
                .start();
        Motion.giggleIn(cardFrame, 0.985f, 300L, 1.10f, 0.55f, false);
    }

    private void playMagicLamp(final View panel, final float touchX, final float touchY) {
        if (panel.getParent() == null || !(panel.getParent() instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) panel.getParent();
        int left = 0;
        int top = 0;
        if (panel.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            left = lp.leftMargin;
            top = lp.topMargin;
        }
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        panel.setVisibility(View.VISIBLE);
        panel.setPivotX(Math.max(0f, Math.min(panel.getWidth(), touchX - rootLoc[0] - left)));
        panel.setPivotY(Math.max(0f, Math.min(panel.getHeight(), touchY - rootLoc[1] - top)));
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300L)
                .setInterpolator(new PathInterpolator(0.16f, 0.84f, 0.24f, 1f))
                .start();
        Motion.giggleIn(panel, 0.97f, 300L, 1.15f, 0.65f, false);
    }

    private void closeSettingsSubmenu() {
        settingsSubmenuKey = null;
        clearSettingsFocus();
        if (settingsSubmenuScrim != null && settingsSubmenuScrim.getParent() instanceof ViewGroup) {
            ((ViewGroup) settingsSubmenuScrim.getParent()).removeView(settingsSubmenuScrim);
        }
        settingsSubmenuScrim = null;
        if (settingsSubmenuGlass != null) {
            if (settingsSubmenuGlass.getParent() instanceof ViewGroup) {
                ((ViewGroup) settingsSubmenuGlass.getParent()).removeView(settingsSubmenuGlass);
            }
            settingsSubmenuGlass = null;
        }
        if (settingsFocusVeil != null) {
            settingsFocusVeil.animate().alpha(0f).setDuration(200).start();
        }
        if (settingsCardView != null) {
            settingsCardView.setAlpha(1f);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                settingsCardView.setRenderEffect(null);
            }
            // Keep the card where it slid to; it no longer springs back.
        }
    }

    private boolean handleSettingsKey(int keyCode, KeyEvent event) {
        if (settingsDialog == null || settingsRootView == null) return false;
        int dir = keyDirection(keyCode);
        boolean select = isSelectKey(keyCode);
        boolean back = isBackKey(keyCode);
        if (dir < 0 && !select && !back) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (back) {
            if (event.getRepeatCount() == 0) {
                Haptics.key(settingsRootView);
                if (settingsSubmenuGlass != null) closeSettingsSubmenu();
                else settingsDialog.dismiss();
            }
            return true;
        }
        if (select) {
            if (event.getRepeatCount() == 0) activateSettingsFocus();
            return true;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (event.getRepeatCount() > 0 && now - lastSettingsNavAt < 80L) return true;
        lastSettingsNavAt = now;
        int multiplier = event.getRepeatCount() > 0
                ? Math.min(6, 1 + event.getRepeatCount() / 4) : 1;
        moveSettingsFocus(dir, multiplier);
        return true;
    }

    private boolean handleSettingsStick(MotionEvent event) {
        if (settingsDialog == null || settingsRootView == null
                || !settingsDialog.isShowing()
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return false;
        }
        int source = event.getSource();
        boolean joystick = (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) != 0;
        if (!joystick) return false;
        float rx = SliderStick.rightX(event);
        if (SliderStick.active(rx)) {
            refreshSettingsFocusList();
            if (settingsFocusIndex >= 0 && settingsFocusIndex < settingsFocusList.size()
                    && settingsFocusList.get(settingsFocusIndex) instanceof SeekBar) {
                SliderStick.adjust((SeekBar) settingsFocusList.get(settingsFocusIndex),
                        rx, settingsSliderStickAt,
                        settingsFocusList.get(settingsFocusIndex));
                return true;
            }
        }
        float ry = StickScroll.rightY(event);
        if (StickScroll.active(ry)) {
            View scroll = settingsMainScroll;
            if (scroll == null) {
                refreshSettingsFocusList();
                if (settingsFocusIndex >= 0 && settingsFocusIndex < settingsFocusList.size()) {
                    scroll = StickScroll.scrollParent(
                            settingsFocusList.get(settingsFocusIndex));
                }
            }
            if (StickScroll.scroll(scroll, ry, settingsScrollStickAt)) return true;
        }
        float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f) x = event.getAxisValue(MotionEvent.AXIS_X);
        if (Math.abs(y) < 0.5f) y = event.getAxisValue(MotionEvent.AXIS_Y);
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        if (ax < 0.42f && ay < 0.42f) {
            cancelSettingsStick();
            return true;
        }
        if (settingsStickAxis == 0) {
            if (Math.max(ax, ay) < 0.68f) return true;
            settingsStickAxis = ax >= ay ? 1 : 2;
        } else if (settingsStickAxis == 1) {
            if (ay > ax + 0.25f) settingsStickAxis = 2;
        } else {
            if (ax > ay + 0.25f) settingsStickAxis = 1;
        }
        float selected = settingsStickAxis == 1 ? ax : ay;
        if (selected < 0.58f) {
            settingsStickDir = -1;
            settingsStickHeldDir = -1;
            handler.removeCallbacks(settingsStickRepeat);
            return true;
        }
        int dir = settingsStickAxis == 1 ? (x < 0 ? 0 : 1) : (y < 0 ? 2 : 3);
        if (dir != settingsStickDir) {
            settingsStickDir = dir;
            settingsStickTicks = 1;
            moveSettingsFocus(dir, 1);
        }
        if (dir != settingsStickHeldDir) {
            settingsStickHeldDir = dir;
            settingsStickTicks = 1;
            handler.removeCallbacks(settingsStickRepeat);
            handler.postDelayed(settingsStickRepeat, 340);
        }
        return true;
    }

    private void cancelSettingsStick() {
        settingsStickDir = -1;
        settingsStickHeldDir = -1;
        settingsStickAxis = 0;
        handler.removeCallbacks(settingsStickRepeat);
    }

    private void refreshSettingsFocusList() {
        View container = null;
        if (settingsSubmenuGlass != null && settingsSubmenuKey != null) {
            container = settingsCategoryPanels.get(settingsSubmenuKey);
        }
        if (container == null) container = settingsMainPanel;
        if (container == null) {
            clearSettingsFocus();
            return;
        }
        if (settingsFocusOwner == container) return;
        settingsFocusOwner = container;
        settingsFocusList.clear();
        settingsFocusIndex = -1;
        collectSettingsTargets(container, settingsFocusList);
    }

    private void collectSettingsTargets(View v, List<View> out) {
        if (v == null || v.getVisibility() != View.VISIBLE || !v.isEnabled()) return;
        if (v instanceof SeekBar || v instanceof android.widget.CompoundButton) {
            out.add(v);
            return;
        }
        if (v.isClickable()) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSettingsTargets(group.getChildAt(i), out);
            }
        }
    }

    /** Controller navigation for simple card dialogs (widgets, icon packs, colour, resize). */
    private void attachCardController(final Dialog dialog, final View content,
                                       final View ringHost) {
        final List<View> rows = new ArrayList<>();
        final FocusRingDrawable[] focusRing = {null};
        final int[] index = {-1};
        final long[] lastNav = {0L};
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface d, int keyCode, KeyEvent event) {
                int dir = keyDirection(keyCode);
                boolean select = isSelectKey(keyCode);
                boolean back = isBackKey(keyCode);
                if (dir < 0 && !select && !back) return false;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (back) {
                    if (event.getRepeatCount() == 0) {
                        Haptics.key(content);
                        dialog.dismiss();
                    }
                    return true;
                }
                if (rows.isEmpty()) collectSettingsTargets(content, rows);
                if (rows.isEmpty()) return true;
                if (select) {
                    if (event.getRepeatCount() > 0) return true;
                    if (index[0] < 0) index[0] = 0;
                    View target = rows.get(index[0]);
                    if (target instanceof SeekBar) {
                        Haptics.tick(target);
                    } else {
                        if (!(target instanceof android.widget.CompoundButton)) {
                            Haptics.click(target);
                        }
                        target.performClick();
                    }
                    return true;
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (event.getRepeatCount() > 0 && now - lastNav[0] < 80L) return true;
                lastNav[0] = now;
                if (index[0] < 0) index[0] = 0;
                else if (dir == 0 || dir == 2) index[0] = (index[0] - 1 + rows.size()) % rows.size();
                else index[0] = (index[0] + 1) % rows.size();
                View target = rows.get(index[0]);
                View ringTarget = target;
                if ((target instanceof SeekBar || target instanceof android.widget.CompoundButton)
                        && target.getParent() instanceof View) {
                    ringTarget = (View) target.getParent();
                }
                if (target.getWidth() > 0) {
                    target.requestRectangleOnScreen(new Rect(0, 0,
                            target.getWidth(), target.getHeight()), true);
                }
                if (content instanceof ViewGroup) {
                    if (focusRing[0] == null) {
                        focusRing[0] = new FocusRingDrawable(MainActivity.this, wallpaper.accent());
                        try {
                            ringHost.getOverlay().add(focusRing[0]);
                        } catch (Throwable t) {
                            focusRing[0] = null;
                        }
                    }
                    if (focusRing[0] != null) {
                        placeOverlayRing(focusRing[0], ringHost, ringTarget);
                    }
                }
                Haptics.tick(target);
                return true;
            }
        });

        final long[] cardSliderStickAt = {0L};
        View.OnGenericMotionListener cardStick = new View.OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (!SliderStick.isGamepad(event)
                        || event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
                if (rows.isEmpty()) collectSettingsTargets(content, rows);
                if (index[0] < 0 || index[0] >= rows.size()) return false;
                View target = rows.get(index[0]);
                if (!(target instanceof SeekBar)) return false;
                float rx = SliderStick.rightX(event);
                if (!SliderStick.active(rx)) return false;
                SliderStick.adjust((SeekBar) target, rx, cardSliderStickAt, target);
                return true;
            }
        };
        content.setOnGenericMotionListener(cardStick);
        ringHost.setOnGenericMotionListener(cardStick);
    }

    private void clearSettingsFocus() {
        settingsFocusToken++;
        settingsFocusList.clear();
        settingsFocusIndex = -1;
        settingsFocusOwner = null;
        hideSettingsFocusRing();
        cancelSettingsStick();
    }

    private void moveSettingsFocus(int dir, int multiplier) {
        refreshSettingsFocusList();
        if (settingsFocusList.isEmpty()) return;
        int count = settingsFocusList.size();
        View current = settingsFocusIndex >= 0 && settingsFocusIndex < count
                ? settingsFocusList.get(settingsFocusIndex) : null;
        if (settingsFocusIndex < 0) settingsFocusIndex = 0;
        else if (dir == 0 || dir == 2) settingsFocusIndex = (settingsFocusIndex - 1 + count) % count;
        else settingsFocusIndex = (settingsFocusIndex + 1) % count;
        showSettingsFocus();
        Haptics.tick(settingsFocusList.get(settingsFocusIndex));
    }

    private void activateSettingsFocus() {
        refreshSettingsFocusList();
        if (settingsFocusList.isEmpty()) {
            Haptics.key(settingsRootView);
            return;
        }
        if (settingsFocusIndex < 0) settingsFocusIndex = 0;
        showSettingsFocus();
        View target = settingsFocusList.get(settingsFocusIndex);
        if (target instanceof SeekBar) {
            Haptics.tick(target);
            return;
        }
        target.performClick();
    }

    private void showSettingsFocus() {
        if (settingsRootView == null
                || settingsFocusIndex < 0 || settingsFocusIndex >= settingsFocusList.size()) {
            hideSettingsFocusRing();
            return;
        }
        View target = settingsFocusList.get(settingsFocusIndex);
        View ringTarget = target;
        if ((target instanceof SeekBar || target instanceof android.widget.CompoundButton)
                && target.getParent() instanceof View) {
            ringTarget = (View) target.getParent();
        }
        if (target.getWidth() > 0) {
            target.requestRectangleOnScreen(new Rect(0, 0,
                    target.getWidth(), target.getHeight()), true);
        }
        final View visibleTarget = ringTarget;
        final int token = ++settingsFocusToken;
        settingsRootView.post(new Runnable() {
            @Override
            public void run() {
                if (token == settingsFocusToken) updateSettingsFocusRing(visibleTarget);
            }
        });
        settingsRootView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token == settingsFocusToken) updateSettingsFocusRing(visibleTarget);
            }
        }, 90);
    }

    private void updateSettingsFocusRing(View target) {
        if (settingsRootView == null || target == null
                || target.getVisibility() != View.VISIBLE
                || target.getWidth() <= 0 || target.getHeight() <= 0) {
            hideSettingsFocusRing();
            return;
        }
        View host = settingsFocusOwner != null ? settingsFocusOwner : settingsMainPanel;
        if (host == null) {
            hideSettingsFocusRing();
            return;
        }
        if (settingsFocusRing == null) {
            settingsFocusRing = new FocusRingDrawable(this, wallpaper.accent());
        }
        if (settingsFocusHost != host) {
            if (settingsFocusHost != null) {
                try {
                    settingsFocusHost.getOverlay().remove(settingsFocusRing);
                } catch (Throwable ignored) {
                }
            }
            try {
                host.getOverlay().add(settingsFocusRing);
                settingsFocusHost = host;
            } catch (Throwable t) {
                settingsFocusHost = null;
                return;
            }
        }
        int[] hostLoc = new int[2];
        int[] targetLoc = new int[2];
        host.getLocationOnScreen(hostLoc);
        target.getLocationOnScreen(targetLoc);
        int pad = Ui.dp(this, 2);
        int left = targetLoc[0] - hostLoc[0] - pad;
        int top = targetLoc[1] - hostLoc[1] - pad;
        settingsFocusRing.setBounds(left, top,
                left + target.getWidth() + pad * 2, top + target.getHeight() + pad * 2);
        settingsFocusRing.setRingRadius(FocusRingView.radiusFor(target, this));
        settingsFocusRing.invalidateSelf();
    }

    private void hideSettingsFocusRing() {
        if (settingsFocusRing != null) {
            settingsFocusRing.setBounds(0, 0, 0, 0);
            settingsFocusRing.invalidateSelf();
        }
    }

    private void showSettingsDialog(float launchX, float launchY) {
        settingsCategoryPanels.clear();
        settingsCategoryIcons.clear();
        settingsCardShifted = false;
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        // The floating performance panel can sit over the settings sliders and
        // intercept their touches. Hide it while settings are open, then bring
        // it back when the dialog closes.
        final boolean overlayWasVisible = PerfOverlayService.isOverlayPanelVisible();
        if (overlayWasVisible) PerfOverlayService.setOverlayVisible(false);

        ScrollView scroll = new ScrollView(this);
        LinearLayout settingsHeader = new LinearLayout(this);
        settingsHeader.setOrientation(LinearLayout.VERTICAL);
        settingsHeader.setPadding(Ui.dp(this, 16), Ui.dp(this, 10),
                Ui.dp(this, 16), 0);
        final SettingsBody panel = new SettingsBody(this);
        panel.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12));
        panel.setBackground(null);

        TextView title = new LTextView(this);
        title.setText("Launcher settings");
        title.setTextColor(0xFFF7F9FC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 9), Ui.dp(this, 2),
                Ui.dp(this, 9), Ui.dp(this, 2));
        title.setBackground(Ui.rounded(0x0EFFFFFF, Ui.dpf(this, 10),
                0x44FFFFFF, Ui.dpf(this, 1)));
        settingsHeader.addView(title);

        panel.use("Dock");
        addSection(panel, "Dock");
        addSlider(panel, "Glass sheen", 0, 100, prefs.dockSheen(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDockSheen(value);
                dock.refreshStyle();
            }
        });
        addSlider(panel, "Glass tint", 0, 50, prefs.glassPercent(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setGlassPercent(value);
                dock.refreshStyle();
            }
        });
        addSlider(panel, "Dock transparency", 20, 100, prefs.dockTransparency(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDockTransparency(value);
                if (dock != null) dock.setSurfaceAlpha(value / 100f);
            }
        });
        addSlider(panel, "Saturation", 0, 200, prefs.dockSaturation(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDockSaturation(value);
                applyDockGlassTuning();
            }
        });
        addSlider(panel, "Darkening", 0, 100, prefs.dockBackdropDarkness(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDockBackdropDarkness(value);
                applyDockGlassTuning();
            }
        });
        addSlider(panel, "Corner radius", 8, 40, prefs.cornerDp(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setCornerDp(value);
                dock.refreshStyle();
            }
        });
        addSwitch(panel, "Dock labels", prefs.dockLabels(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setDockLabels(value);
                populateDock();
            }
        });
        addSwitch(panel, "Light glass", prefs.lightGlass(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setLightGlass(value);
                dock.refreshStyle();
            }
        });
        addSwitch(panel, "Show app drawer button", prefs.dockDrawerButton(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setDockDrawerButton(value);
                populateDock();
            }
        });
        addSlider(panel, "Dock icons", 3, 8, prefs.dockCount(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDockCount(value);
                populateDock();
            }
        });
        addSlider(panel, "Dock size", 80, 130,
                Math.round(prefs.dockScale() * 100f), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDockScale(value / 100f);
                applyDockSize();
                populateDock();
            }
        });
        addDockPositionChooser(panel);

        panel.use("Builtins");
        addSection(panel, "Built-in apps");
        TextView transparencyHint = new LTextView(this);
        transparencyHint.setText("Glass surfaces fade; icons, text and controls stay opaque.");
        transparencyHint.setTextColor(0x80FFFFFF);
        transparencyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        transparencyHint.setPadding(0, 0, 0, Ui.dp(this, 4));
        panel.addView(transparencyHint);
        addSlider(panel, "Transparency", 20, 100, prefs.builtinTransparency(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setBuiltinTransparency(value);
            }
        });
        TextView builtinHint = new LTextView(this);
        builtinHint.setText("One value for every built-in app: Fan Control, Display, "
                + "Stress Test, Performance Logs, File Manager, Device Health, "
                + "Controller Remapper, Touch Mapper, Overlay & Drawer, the players "
                + "and the recorder.");
        builtinHint.setTextColor(0x66FFFFFF);
        builtinHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        builtinHint.setPadding(0, 0, 0, Ui.dp(this, 10));
        panel.addView(builtinHint);
        addSlider(panel, "Saturation", 0, 200, prefs.builtinSaturation(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setBuiltinSaturation(value);
            }
        });
        TextView saturationHint = new LTextView(this);
        saturationHint.setText("Colour strength of the screen captured behind built-in app "
                + "cards. 150% matches the original glass; 100% is neutral.");
        saturationHint.setTextColor(0x66FFFFFF);
        saturationHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        saturationHint.setPadding(0, 0, 0, Ui.dp(this, 10));
        panel.addView(saturationHint);
        addSlider(panel, "Darkening", 0, 100,
                prefs.builtinBackdropDarkness(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setBuiltinBackdropDarkness(value);
            }
        });
        TextView darkHint = new LTextView(this);
        darkHint.setText("Darkens the captured background inside built-in app cards. "
                + "0% leaves it unchanged, 100% is black.");
        darkHint.setTextColor(0x66FFFFFF);
        darkHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        darkHint.setPadding(0, 0, 0, Ui.dp(this, 10));
        panel.addView(darkHint);

        panel.use("Menus");
        addSection(panel, "Menus");
        addSlider(panel, "Popup menus", 20, 100, prefs.menuTransparency(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setMenuTransparency(value);
            }
        });
        addSlider(panel, "Launcher settings", 20, 100, prefs.settingsTransparency(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setSettingsTransparency(value);
                if (settingsGlass != null) settingsGlass.setSurfaceAlpha(value / 100f);
                if (settingsSubmenuGlass != null) {
                    settingsSubmenuGlass.setSurfaceAlpha(value / 100f);
                }
            }
        });
        addSlider(panel, "Saturation", 0, 200, prefs.menuSaturation(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setMenuSaturation(value);
                applyMenuGlassTuning();
            }
        });
        addSlider(panel, "Darkening", 0, 100, prefs.menuBackdropDarkness(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setMenuBackdropDarkness(value);
                applyMenuGlassTuning();
            }
        });

        panel.use("Home");
        addSection(panel, "Home screen");
        TextView gridHint = new LTextView(this);
        gridHint.setText("Cells auto-fit the page around the icon size. Smaller icons fit more cells; drag icons into any open space.");
        gridHint.setTextColor(0x80FFFFFF);
        gridHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        gridHint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 10));
        panel.addView(gridHint);
        addSwitch(panel, "Home icon labels", prefs.homeLabels(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setHomeLabels(value);
                renderHome();
                populateDock();
            }
        });
        addSlider(panel, "Icon size", 36, 72, prefs.iconDp(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setIconDp(value);
                renderHome();
                populateDock();
                if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
            }
        });
        addSwitch(panel, "Auto-hide page indicator", prefs.autoHidePageDots(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setAutoHidePageDots(value);
                        revealPageDots();
                    }
                });
        TextView dotsHint = new LTextView(this);
        dotsHint.setText("Fades the page dots out shortly after you stop swiping. Turn off to keep them visible.");
        dotsHint.setTextColor(0x66FFFFFF);
        dotsHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dotsHint.setPadding(0, 0, 0, Ui.dp(this, 4));
        panel.addView(dotsHint);
        addPageAnimationChooser(panel);

        addSlider(panel, "Folder transparency", 20, 100, prefs.folderTransparency(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setFolderTransparency(value);
                if (folderGlass != null) folderGlass.setSurfaceAlpha(value / 100f);
                renderHome();
            }
        });
        addSlider(panel, "Folder saturation", 0, 200, prefs.folderSaturation(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setFolderSaturation(value);
                applyFolderGlassTuning();
            }
        });
        addSlider(panel, "Folder darkening", 0, 100, prefs.folderBackdropDarkness(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setFolderBackdropDarkness(value);
                applyFolderGlassTuning();
            }
        });

        panel.use("Gestures");
        addSection(panel, "Gestures");
        addSwitch(panel, "Double-tap empty space to sleep", prefs.sleepEnabled(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setSleepEnabled(value);
                    }
                });
        addSleepMethodChooser(panel);

        panel.use("Feedback");
        addSection(panel, "Feedback");
        addSwitch(panel, "Haptic feedback", prefs.hapticsEnabled(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setHapticsEnabled(value);
                if (value) Haptics.click(panel);
            }
        });
        addSlider(panel, "Haptic strength", 0, 100, prefs.hapticsStrength(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setHapticsStrength(value);
            }
        });

        panel.use("Controls");
        addSection(panel, "Controller remapping");
        addSwitch(panel, "Enable system-wide remapping",
                prefs.controllerRemapEnabled(), new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setControllerRemapEnabled(value);
                        if (value) {
                            ControllerRemapManager.start(MainActivity.this,
                                    new ControllerRemapManager.Callback() {
                                        @Override
                                        public void onResult(boolean running) {
                                            if (!running) {
                                                prefs.setControllerRemapEnabled(false);
                                                LToast.makeText(MainActivity.this,
                                                        "Remapper could not start. PServer or root is required.",
                                                        Toast.LENGTH_LONG).show();
                                            }
                                        }
                                    });
                        } else {
                            ControllerRemapManager.stop(MainActivity.this);
                        }
                    }
                });
        addSwitch(panel, "No-root accessibility mode", prefs.rootlessRemap(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setRootlessRemap(value);
                        if (value) {
                            PavoInputService.stop(MainActivity.this);
                            if (prefs.controllerRemapEnabled()) {
                                ControllerRemapManager.restart(MainActivity.this, null);
                            }
                        } else {
                            PavoInputService.ensureStarted(MainActivity.this);
                            ControllerRemapManager.restart(MainActivity.this, null);
                        }
                    }
                });
        final TextView serviceStatus = new LTextView(this);
        boolean a11yReady = PavoA11yService.isReady();
        boolean a11yEnabled = accessibilitySettingEnabled();
        boolean inputReady = PavoInputService.isReady();
        boolean inputActive = PavoInputService.isActive();
        serviceStatus.setText(inputReady
                ? "Controller service: PServer connected"
                : inputActive
                ? "Controller service: starting"
                : a11yReady
                ? "Accessibility service: connected"
                : a11yEnabled
                ? "Accessibility: enabled"
                : "Controller service unavailable — tap for options");
        serviceStatus.setTextColor(inputReady || a11yReady || inputActive
                ? 0xFF69D98B : 0xFFFFC857);
        serviceStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        serviceStatus.setPadding(Ui.dp(this, 9), Ui.dp(this, 7),
                Ui.dp(this, 9), Ui.dp(this, 7));
        serviceStatus.setBackground(Ui.rounded(inputReady || a11yReady ? 0x1216C784 : 0x14FFC107,
                Ui.dpf(this, 10), inputReady || a11yReady ? 0x3369D98B : 0x44FFC107,
                Ui.dpf(this, 1)));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Ui.dp(this, 8);
        serviceStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    LToast.makeText(MainActivity.this, "Open Settings → Accessibility",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        panel.addView(serviceStatus, statusLp);
        if (!inputReady && !a11yReady) {
            TextView reconnectHint = new LTextView(this);
            reconnectHint.setText("Controller shortcuts use PServer automatically. "
                    + "No-root mode still uses Accessibility. Tap above to open the "
                    + "relevant settings.");
            reconnectHint.setTextColor(0x66FFFFFF);
            reconnectHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            reconnectHint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));
            panel.addView(reconnectHint);
        }

        TextView openRemap = new LTextView(this);
        openRemap.setText("Open Controller Remapper");
        styleTextButton(openRemap, false);
        LinearLayout.LayoutParams remapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        remapLp.topMargin = Ui.dp(this, 10);
        openRemap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, ControllerMapActivity.class));
            }
        });
        panel.addView(openRemap, remapLp);
        TextView remapHint = new LTextView(this);
        remapHint.setText("Maps the Odin controller system-wide, including inside games. "
                + "PServer launches the root remapper without a su prompt; root is the fallback. "
                + "No-root accessibility mode uses the accessibility filter instead.");
        remapHint.setTextColor(0x66FFFFFF);
        remapHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        remapHint.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 3));
        panel.addView(remapHint);

        panel.use("Drawer");
        addSection(panel, "App drawer");
        addSlider(panel, "Drawer columns", 4, 9, prefs.drawerCols(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDrawerCols(value);
                if (drawerGrid != null) drawerGrid.setNumColumns(effectiveDrawerColumns());
            }
        });
        addSwitch(panel, "Drawer icon labels", prefs.drawerLabels(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setDrawerLabels(value);
                if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
            }
        });
        addSwitch(panel, "Dock button opens popup drawer", prefs.drawerButtonPopup(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setDrawerButtonPopup(value);
                    }
                });
        addSwitch(panel, "Clear liquid glass in app drawer", prefs.drawerGlass(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setDrawerGlass(value);
                        if (drawerGlass != null) {
                            drawerGlass.setClearGlass(value);
                            drawerGlass.setClearBodyAlpha(170);
                            drawerGlass.invalidate();
                        }
                    }
                });
        addSlider(panel, "App drawer transparency", 20, 100, prefs.appDrawerTransparency(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setAppDrawerTransparency(value);
                applyDrawerSurfaceTransparency();
            }
        });
        addSlider(panel, "Popup app drawer transparency", 20, 100,
                prefs.popupDrawerTransparency(), new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setPopupDrawerTransparency(value);
                applyDrawerSurfaceTransparency();
            }
        });
        addSlider(panel, "Saturation", 0, 200, prefs.drawerSaturation(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDrawerSaturation(value);
                applyDrawerGlassTuning();
            }
        });
        addSlider(panel, "Darkening", 0, 100, prefs.drawerBackdropDarkness(),
                new IntCallback() {
            @Override
            public void onValue(int value) {
                prefs.setDrawerBackdropDarkness(value);
                applyDrawerGlassTuning();
            }
        });

        panel.use("Widgets");
        addSection(panel, "Clock & status");
        addSwitch(panel, "Show clock & date", prefs.clockVisible(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setClockVisible(value);
                applyClockScale();
                renderHome();
            }
        });
        addSwitch(panel, "24-hour clock", prefs.clock24(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setClock24(value);
                updateClock();
            }
        });
        addSwitch(panel, "Show CPU & network", prefs.statsVisible(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setStatsVisible(value);
                updateStatsVisibility();
            }
        });
        addSwitch(panel, "Show Bluetooth status", prefs.bluetoothStatusVisible(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setBluetoothStatusVisible(value);
                        updateHeaderStatusVisibility();
                        updateHeaderStatus();
                    }
                });
        addSwitch(panel, "Show notification sound status", prefs.soundStatusVisible(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setSoundStatusVisible(value);
                        updateHeaderStatusVisibility();
                        updateHeaderStatus();
                    }
                });

        addSection(panel, "Weather");
        addSwitch(panel, "Show weather", prefs.weatherVisible(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setWeatherVisible(value);
                updateWeatherView();
                if (value) refreshWeather(true);
            }
        });
        addSwitch(panel, "Use device location",
                prefs.weatherUseLocation() && Weather.hasLocationPermission(this),
                new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setWeatherUseLocation(value);
                if (value && !Weather.hasLocationPermission(MainActivity.this)) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                            REQ_LOCATION);
                } else {
                    refreshWeather(true);
                }
            }
        });
        TextView refreshWeatherRow = new LTextView(this);
        refreshWeatherRow.setText("Refresh weather now");
        styleTextButton(refreshWeatherRow, false);
        LinearLayout.LayoutParams rwlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rwlp.topMargin = Ui.dp(this, 10);
        refreshWeatherRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshWeather(true);
                LToast.makeText(MainActivity.this, "Refreshing weather…", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(refreshWeatherRow, rwlp);

        panel.use("Performance");
        addSection(panel, "Performance overlay");
        addSwitch(panel, "Show performance overlay", prefs.perfOverlay(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        if (!value) {
                            prefs.setPerfOverlay(false);
                            PerfOverlayService.stop(MainActivity.this);
                            return;
                        }
                        if (!Settings.canDrawOverlays(MainActivity.this)) {
                            pendingPerfOverlay = true;
                            LToast.makeText(MainActivity.this,
                                    "Allow Pavo to display over other apps",
                                    Toast.LENGTH_LONG).show();
                            dialog.dismiss();
                            openOverlayPermission();
                            return;
                        }
                        prefs.setPerfOverlay(true);
                        PerfOverlayService.start(MainActivity.this);
                    }
                });
        addSwitch(panel, "Log performance sessions", prefs.perfLogging(),
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        prefs.setPerfLogging(value);
                    }
                });

        TextView monitorRow = new LTextView(this);
        monitorRow.setText("Open detailed performance monitor");
        styleTextButton(monitorRow, false);
        LinearLayout.LayoutParams monitorLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        monitorLp.topMargin = Ui.dp(this, 6);
        monitorRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PerfOverlayService.showDetailFromShortcut(MainActivity.this);
            }
        });
        panel.addView(monitorRow, monitorLp);

        TextView tunerRow = new LTextView(this);
        tunerRow.setText("Open CPU / GPU / fan tuner");
        styleTextButton(tunerRow, false);
        LinearLayout.LayoutParams tunerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tunerLp.topMargin = Ui.dp(this, 6);
        tunerRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, PerformanceTunerActivity.class));
            }
        });
        panel.addView(tunerRow, tunerLp);

        TextView benchmarkRow = new LTextView(this);
        benchmarkRow.setText("Open CPU / GPU stress test");
        styleTextButton(benchmarkRow, false);
        LinearLayout.LayoutParams benchmarkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        benchmarkLp.topMargin = Ui.dp(this, 6);
        benchmarkRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, BenchmarkActivity.class));
            }
        });
        panel.addView(benchmarkRow, benchmarkLp);

        TextView overlayHint = new LTextView(this);
        overlayHint.setText("Drag the glass panel anywhere. Pinch to resize and long-press for the live control menu. It stays above other apps while enabled.");
        overlayHint.setTextColor(0x80FFFFFF);
        overlayHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        overlayHint.setPadding(0, 0, 0, Ui.dp(this, 4));
        panel.addView(overlayHint);
        addSlider(panel, "Overlay opacity", 25, 100, prefs.perfOverlayAlpha(),
                new IntCallback() {
                    @Override
                    public void onValue(int value) {
                        prefs.setPerfOverlayAlpha(value);
                        PerfOverlayService.refreshIfRunning();
                    }
                });

        String mappedLabel = PavoA11yService.controllerLabel(prefs);
        TextView controllerRow = new LTextView(this);
        controllerRow.setText("Controller shortcut: " + mappedLabel + "  ·  tap to map");
        controllerRow.setTextColor(0xE6FFFFFF);
        controllerRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        controllerRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 9),
                Ui.dp(this, 12), Ui.dp(this, 9));
        controllerRow.setBackground(Ui.rounded(0x18FFFFFF, Ui.dpf(this, 12),
                0x22FFFFFF, Ui.dpf(this, 1)));
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = Ui.dp(this, 4);
        controllerRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (PavoInputService.beginKeyCapture(MainActivity.this)) {
                    LToast.makeText(MainActivity.this,
                            "Press a controller button to map it", Toast.LENGTH_LONG).show();
                } else {
                    LToast.makeText(MainActivity.this,
                            "Controller service unavailable. Check PServer/root or accessibility.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        panel.addView(controllerRow, crlp);
        TextView controllerHint = new LTextView(this);
        controllerHint.setText("Tap the mapped button to show/hide the overlay; hold it to open the control menu.");
        controllerHint.setTextColor(0x66FFFFFF);
        controllerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        controllerHint.setPadding(0, Ui.dp(this, 3), 0, 0);
        panel.addView(controllerHint);

        String drawerMapped = PavoA11yService.drawerControllerLabel(prefs);
        TextView drawerControllerRow = new LTextView(this);
        drawerControllerRow.setText("App drawer shortcut: " + drawerMapped + "  ·  tap to map");
        drawerControllerRow.setTextColor(0xE6FFFFFF);
        drawerControllerRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        drawerControllerRow.setPadding(Ui.dp(this, 12), Ui.dp(this, 9),
                Ui.dp(this, 12), Ui.dp(this, 9));
        drawerControllerRow.setBackground(Ui.rounded(0x18FFFFFF, Ui.dpf(this, 12),
                0x22FFFFFF, Ui.dpf(this, 1)));
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        drlp.topMargin = Ui.dp(this, 4);
        drawerControllerRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (PavoInputService.beginDrawerKeyCapture(MainActivity.this)) {
                    LToast.makeText(MainActivity.this,
                            "Press a controller button for the app drawer",
                            Toast.LENGTH_LONG).show();
                } else {
                    LToast.makeText(MainActivity.this,
                            "Controller service unavailable. Check PServer/root or accessibility.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        panel.addView(drawerControllerRow, drlp);
        TextView drawerControllerHint = new LTextView(this);
        drawerControllerHint.setText("Single-press that button to open the floating app drawer above any app; press it again to close it.");
        drawerControllerHint.setTextColor(0x66FFFFFF);
        drawerControllerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        drawerControllerHint.setPadding(0, Ui.dp(this, 3), 0, 0);
        panel.addView(drawerControllerHint);
        if (!prefs.drawerControllerRaw().isEmpty()) {
            TextView clearDrawer = new LTextView(this);
            clearDrawer.setText("Clear app drawer shortcut");
            clearDrawer.setTextColor(0x99FFFFFF);
            clearDrawer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            clearDrawer.setPadding(0, Ui.dp(this, 5), 0, 0);
            clearDrawer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setDrawerControllerRaw("");
                    LToast.makeText(MainActivity.this,
                            "App drawer shortcut cleared", Toast.LENGTH_SHORT).show();
                }
            });
            panel.addView(clearDrawer);
        }

        TextView grant = new LTextView(this);
        grant.setText("Grant display over other apps…");
        styleTextButton(grant, false);
        grant.setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(this, 4);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingPerfOverlay = true;
                dialog.dismiss();
                openOverlayPermission();
            }
        });
        panel.addView(grant, glp);

        TextView resetOverlay = new LTextView(this);
        resetOverlay.setText("Reset overlay position");
        styleTextButton(resetOverlay, false);
        LinearLayout.LayoutParams rolp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rolp.topMargin = Ui.dp(this, 4);
        resetOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setPerfOverlayPosition(-1, -1);
                PerfOverlayService.resetPosition();
                LToast.makeText(MainActivity.this, "Overlay position reset",
                        Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(resetOverlay, rolp);

        TextView metricsTitle = new LTextView(this);
        metricsTitle.setText("SHOWN METRICS");
        metricsTitle.setTextColor(0x66FFFFFF);
        metricsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        metricsTitle.setLetterSpacing(0.14f);
        metricsTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        metricsTitle.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        panel.addView(metricsTitle);

        addSwitch(panel, "CPU temperature", prefs.perfCpuTemp(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfCpuTemp(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "CPU clock", prefs.perfCpuClock(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfCpuClock(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "CPU load", prefs.perfCpuUsage(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfCpuUsage(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "GPU temperature", prefs.perfGpuTemp(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfGpuTemp(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "GPU clock", prefs.perfGpuClock(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfGpuClock(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "GPU load", prefs.perfGpuUsage(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfGpuUsage(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "Framerate", prefs.perfFps(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfFps(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "Framerate graph", prefs.perfFpsGraph(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfFpsGraph(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "Battery percentage", prefs.perfBattery(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfBattery(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "RAM usage", prefs.perfRam(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfRam(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        addSwitch(panel, "Per-core clocks & load", prefs.perfCores(), new BoolCallback() {
            @Override
            public void onValue(boolean value) {
                prefs.setPerfCores(value);
                PerfOverlayService.refreshIfRunning();
            }
        });
        TextView perfHint = new LTextView(this);
        perfHint.setText("Per-core rows show load and clock for every online CPU core. Framerate uses SurfaceFlinger through root; without root it shows —.");
        perfHint.setTextColor(0x66FFFFFF);
        perfHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        perfHint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        panel.addView(perfHint);

        panel.use("Wallpaper");
        addSection(panel, "Wallpaper");
        LinearLayout wallRow = new LinearLayout(this);
        wallRow.setOrientation(LinearLayout.HORIZONTAL);
        wallRow.setGravity(Gravity.CENTER_VERTICAL);
        wallRow.setPadding(0, Ui.dp(this, 4), 0, 0);

        TextView pick = new LTextView(this);
        pick.setText("Choose image or video…");
        styleTextButton(pick, false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickMode = PICK_WALLPAPER;
                launchWallpaperPicker();
            }
        });
        wallRow.addView(pick, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wallRow.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));

        TextView device = new LTextView(this);
        device.setText("Device");
        styleTextButton(device, false);
        device.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setWallpaperMode(Prefs.MODE_DEVICE);
                wallpaper.setMode(Prefs.MODE_DEVICE);
                applyWallpaperPresentation();
            }
        });
        wallRow.addView(device);
        panel.addView(wallRow);

        addSwitch(panel, "Pavo backdrop", prefs.wallpaperMode() == Prefs.MODE_AURORA,
                new BoolCallback() {
                    @Override
                    public void onValue(boolean value) {
                        int m = value ? Prefs.MODE_AURORA : defaultWallpaperMode();
                        prefs.setWallpaperMode(m);
                        wallpaper.setMode(m);
                        applyWallpaperPresentation();
                    }
                });

        panel.use("Display");
        addSection(panel, "Display");
        TextView openDisplay = new LTextView(this);
        openDisplay.setText("Open Display app");
        styleTextButton(openDisplay, false);
        LinearLayout.LayoutParams openDisplayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        openDisplayLp.topMargin = Ui.dp(this, 4);
        openDisplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                startBuiltIn(new Intent(MainActivity.this, DisplayActivity.class));
            }
        });
        panel.addView(openDisplay, openDisplayLp);
        TextView displayHint = new LTextView(this);
        displayHint.setText("Refresh rate, resolution, density, brightness, rotation, night light, launcher colors and burn-in protection now live here.");
        displayHint.setTextColor(0x66FFFFFF);
        displayHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        displayHint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        panel.addView(displayHint);

        panel.use("Backup");
        addSection(panel, "Backup & restore");
        TextView backupHint = new LTextView(this);
        backupHint.setText("Save the home layout, wallpaper, custom icons, dock, tuning profiles, touch maps and launcher settings.");
        backupHint.setTextColor(0x80FFFFFF);
        backupHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        backupHint.setPadding(0, 0, 0, Ui.dp(this, 6));
        panel.addView(backupHint);

        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        backupRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView exportSetup = new LTextView(this);
        exportSetup.setText("Export setup");
        styleTextButton(exportSetup, false);
        exportSetup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                exportSetup();
            }
        });
        backupRow.addView(exportSetup, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        backupRow.addView(new Space(this), new LinearLayout.LayoutParams(
                Ui.dp(this, 8), 1));

        TextView importSetup = new LTextView(this);
        importSetup.setText("Import setup…");
        styleTextButton(importSetup, false);
        importSetup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                pickSetupBackup();
            }
        });
        backupRow.addView(importSetup, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(backupRow);

        TextView backupNote = new LTextView(this);
        backupNote.setText("Exports are ZIP files in Downloads. Import replaces the current setup after confirmation and saves a safety copy first.");
        backupNote.setTextColor(0x66FFFFFF);
        backupNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        backupNote.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        panel.addView(backupNote);

        panel.use("Language");
        addSection(panel, "Language");
        TextView languageButton = new LTextView(this);
        languageButton.setText(Loc.s(this, "Language") + ": " + Loc.configuredLabel(this));
        styleTextButton(languageButton, false);
        languageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                showLanguageDialog();
            }
        });
        panel.addView(languageButton);
        TextView languageHint = new LTextView(this);
        languageHint.setText("Automatic follows the device language. A manual choice is kept for Pavo only.");
        languageHint.setTextColor(0x66FFFFFF);
        languageHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        languageHint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        panel.addView(languageHint);

        panel.use(null);
        String[][] settingsSections = {
                {"Home", "Home screen"},
                {"Drawer", "App drawer"},
                {"Dock", "Dock"},
                {"Builtins", "Built-in apps"},
                {"Menus", "Menus"},
                {"Gestures", "Gestures"},
                {"Feedback", "Feedback & haptics"},
                {"Widgets", "Clock, weather & status"},
                {"Backup", "Backup & restore"},
                {"Language", "Language"}
        };
        for (String[] category : settingsSections) {
            panel.addView(makeSettingsCategoryRow(category[1], category[0]),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);

        TextView reset = new LTextView(this);
        reset.setText("Reset style");
        styleTextButton(reset, false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetStyle();
                wallpaper.setBlurRadius(prefs.blur());
                wallpaper.setMode(prefs.wallpaperMode());
                dock.refreshStyle();
                applyDockPosition();
                if (pager != null) pager.setAnimationStyle(prefs.pageAnimation());
                renderHome();
                populateDock();
                updateClock();
                applyClockColors();
                updateStatsVisibility();
                if (drawerGrid != null) drawerGrid.setNumColumns(effectiveDrawerColumns());
                if (drawerAdapter != null) drawerAdapter.notifyDataSetChanged();
                applyColorTuning();
                dialog.dismiss();
                LToast.makeText(MainActivity.this, "Style reset", Toast.LENGTH_SHORT).show();
            }
        });
        buttons.addView(reset);

        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView done = new LTextView(this);
        done.setText("Done");
        styleTextButton(done, true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(done);
        panel.addView(buttons);

        scroll.addView(panel);
        scroll.setBackground(null);
        scroll.setVerticalScrollBarEnabled(true);
        settingsMainPanel = panel;
        settingsMainScroll = scroll;
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY,
                                       int oldScrollX, int oldScrollY) {
                if (settingsFocusRing != null
                        && settingsFocusIndex >= 0
                        && settingsFocusIndex < settingsFocusList.size()) {
                    View target = settingsFocusList.get(settingsFocusIndex);
                    if (target instanceof SeekBar || target instanceof android.widget.CompoundButton) {
                        View parent = target.getParent() instanceof View
                                ? (View) target.getParent() : target;
                        updateSettingsFocusRing(parent);
                    } else {
                        updateSettingsFocusRing(target);
                    }
                }
            }
        });
        settingsBackdrop = captureBackdrop(false, 0);
        GlassPanel glass = new GlassPanel(this, settingsBackdrop,
                wallpaper.accent(), 0x330B0E14, 26, true);
        settingsGlass = glass;
        glass.setBackdropSaturation(prefs.menuSaturation() / 100f);
        glass.setBackdropDarkness(prefs.menuBackdropDarkness() / 100f);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0x40);
        glass.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        glass.addView(settingsHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        glass.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int screenW = dm.widthPixels;
        final int screenH = dm.heightPixels;
        final int edgePad = Ui.dp(this, 10);
        final int gap = Ui.dp(this, 12);
        final int cardW = Math.min(Ui.dp(this, 330), screenW - Ui.dp(this, 24));
        final int cardH = Math.max(Ui.dp(this, 280), Math.min(
                Ui.dp(this, 520), Math.round(screenH * 0.66f)));

        int left;
        int top;
        if (Float.isNaN(launchX) || Float.isNaN(launchY)) {
            left = (screenW - cardW) / 2;
            top = (screenH - cardH) / 2;
        } else {
            left = Math.round(launchX) + gap;
            if (left + cardW > screenW - edgePad) {
                left = Math.round(launchX) - cardW - gap;
            }
            top = Math.round(launchY) + gap;
            if (top + cardH > screenH - edgePad) {
                top = Math.round(launchY) - cardH - gap;
            }
            left = Math.max(edgePad, Math.min(left, screenW - cardW - edgePad));
            top = Math.max(edgePad, Math.min(top, screenH - cardH - edgePad));
        }

        final SettingsRootLayout settingsRoot = new SettingsRootLayout(this);
        settingsRoot.setFocusable(true);
        settingsRoot.setFocusableInTouchMode(true);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
            }
        });
        settingsRoot.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final FrameLayout cardFrame = new FrameLayout(this);
        cardFrame.setClickable(true);
        final View focusVeil = new View(this);
        focusVeil.setBackground(Ui.rounded(0x7A070A12, Ui.dpf(this, 26),
                0x55FFFFFF, Ui.dpf(this, 1)));
        focusVeil.setAlpha(0f);
        settingsFocusVeil = focusVeil;
        cardFrame.addView(focusVeil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cardFrame.addView(glass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final View.OnTouchListener drag = new View.OnTouchListener() {
            private float downRawX, downRawY, startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = cardFrame.getX();
                        startY = cardFrame.getY();
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float nx = startX + e.getRawX() - downRawX;
                        float ny = startY + e.getRawY() - downRawY;
                        nx = Math.max(edgePad,
                                Math.min(nx, screenW - cardFrame.getWidth() - edgePad));
                        ny = Math.max(edgePad,
                                Math.min(ny, screenH - cardFrame.getHeight() - edgePad));
                        cardFrame.setX(nx);
                        cardFrame.setY(ny);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        return true;
                }
                return false;
            }
        };
        title.setOnTouchListener(drag);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardW, cardH);
        cardLp.leftMargin = left;
        cardLp.topMargin = top;
        settingsRoot.addView(cardFrame, cardLp);
        settingsDialog = dialog;
        settingsRootView = settingsRoot;
        settingsCardView = cardFrame;
        cardFrame.setClipChildren(false);
        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface d) {
                closeSettingsSubmenu();
                clearSettingsFocus();
                settingsFocusRing = null;
                settingsFocusHost = null;
                settingsMainPanel = null;
                settingsMainScroll = null;
                settingsBackdrop = null;
                settingsDialog = null;
                settingsRootView = null;
                settingsCardView = null;
                if (overlayWasVisible) PerfOverlayService.setOverlayVisible(true);
                lastUserActivityAt = SystemClock.uptimeMillis();
                resetHomeOffset();
            }
        });
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface d, int keyCode, KeyEvent event) {
                return handleSettingsKey(keyCode, event);
            }
        });
        dialog.setContentView(settingsRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            lp.dimAmount = 0f;
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            window.setAttributes(lp);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        settingsRoot.post(new Runnable() {
            @Override
            public void run() {
                if (settingsDialog == dialog && settingsRootView != null) {
                    playSettingsCardOpen(dialog, settingsRoot, cardFrame, launchX, launchY);
                    settingsRoot.requestFocus();
                }
            }
        });
        if (window != null && android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private int defaultWallpaperMode() {
        File f = new File(getFilesDir(), "wallpaper.png");
        return (f.exists() && f.length() > 0) ? Prefs.MODE_CUSTOM : Prefs.MODE_DEVICE;
    }

    private void openOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            LToast.makeText(this, "Open Settings → Apps → Pavo → Display over other apps",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showLanguageDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        final Backdrop backdrop = captureBackdrop(false, 0);

        GlassPanel glass = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x4D0B0E14, 26, true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        glass.setPadding(Ui.dp(this, 20), Ui.dp(this, 16),
                Ui.dp(this, 20), Ui.dp(this, 10));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new LTextView(this);
        title.setText("Language");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 9), Ui.dp(this, 3),
                Ui.dp(this, 9), Ui.dp(this, 3));
        title.setBackground(Ui.rounded(0x0EFFFFFF, Ui.dpf(this, 10),
                0x44FFFFFF, Ui.dpf(this, 1)));
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView grip = new LTextView(this);
        grip.setText("\u283F");
        grip.setTextColor(0x80FFFFFF);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        grip.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        titleRow.addView(grip);
        glass.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new LTextView(this);
        hint.setText("Automatic follows the device language. A manual choice is kept for Pavo only.");
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 7));
        glass.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final String configured = Loc.configured(this);
        final String[][] languages = Loc.LANGUAGES;
        final int accent = ColorTuning.colour(wallpaper.accent());
        final View[] selectedRow = {null};
        for (final String[] language : languages) {
            final String code = language[0];
            boolean selected = code.equals(configured);
            String label = language[1];
            if (Loc.AUTO.equals(code)) {
                label = Loc.s(this, "Automatic") + " (" + Loc.displayName(Loc.deviceCode()) + ")";
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                    Ui.dp(this, 12), Ui.dp(this, 10));
            row.setBackground(selected
                    ? Ui.rounded(0x24FFFFFF, Ui.dpf(this, 14),
                            ColorTuning.withAlpha(accent, 0x88), Ui.dpf(this, 1))
                    : Ui.ripple(this, Ui.dpf(this, 14)));
            row.setClickable(true);

            TextView check = new LTextView(this);
            check.setText(selected ? "\u2713" : "\u25CB");
            check.setTextColor(selected ? accent : 0x55FFFFFF);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, selected ? 17 : 14);
            check.setGravity(Gravity.CENTER);
            check.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            row.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 28),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = new TextView(this);
            name.setText(label);
            name.setTextColor(selected ? 0xFFFFFFFF : 0xE6FFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif",
                    Typeface.NORMAL));
            name.setPadding(Ui.dp(this, 4), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    prefs.setLanguage(code);
                    Loc.invalidate();
                    Loc.applyLocale(MainActivity.this);
                    dialog.dismiss();
                    recreate();
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (selected) selectedRow[0] = row;
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, Ui.dp(this, 8), 0, 0);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView cancel = new LTextView(this);
        cancel.setText("Cancel");
        styleTextButton(cancel, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dialog.dismiss();
            }
        });
        buttons.addView(cancel);
        body.addView(buttons);
        glass.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        showGlassDialog(dialog, glass, backdrop, 460, 0.78f);
        dialog.show();
        // A Dialog without a focused view lets controller keys fall through to
        // the launcher.  Focus the decorated window so the card's key listener
        // receives D-pad / left-stick navigation and A/B.
        View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
        if (decor != null) {
            decor.setFocusableInTouchMode(true);
            decor.requestFocus();
        }
        glass.setFocusableInTouchMode(true);
        glass.requestFocus();
        if (selectedRow[0] != null) {
            final View targetRow = selectedRow[0];
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.smoothScrollTo(0, Math.max(0, targetRow.getTop() - Ui.dp(MainActivity.this, 8)));
                }
            });
        }
        attachCardController(dialog, body, glass);
    }

    private void addSection(LinearLayout parent, String name) {
        TextView t = new LTextView(this);
        t.setText(name);
        t.setAllCaps(true);
        t.setTextColor(0x66FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 2));
        parent.addView(t);
    }

    private void addDockPositionChooser(LinearLayout parent) {
        TextView label = new LTextView(this);
        label.setText("Dock position");
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        parent.addView(label);

        final String[] keys = {"left", "center", "right", "hidden"};
        final String[] labels = {"Left", "Center", "Right", "Hidden"};
        final int[] values = {Prefs.DOCK_LEFT, Prefs.DOCK_CENTER,
                Prefs.DOCK_RIGHT, Prefs.DOCK_HIDDEN};
        final List<TextView> chips = new ArrayList<>();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, Ui.dp(this, 4));
        scroll.addView(row);

        for (int i = 0; i < keys.length; i++) {
            final int index = i;
            TextView chip = new LTextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(Ui.dp(this, 13), Ui.dp(this, 7),
                    Ui.dp(this, 13), Ui.dp(this, 7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 8);
            row.addView(chip, clp);
            chips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setDockPosition(values[index]);
                    applyDockPosition();
                    stylePageAnimationChips(chips, keys, keys[index]);
                }
            });
        }
        stylePageAnimationChips(chips, keys, dockPositionKey(prefs.dockPosition()));
        parent.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new LTextView(this);
        hint.setText("Hidden removes the dock. Swipe up still opens the full app drawer.");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        parent.addView(hint);
    }

    private String dockPositionKey(int position) {
        if (position == Prefs.DOCK_LEFT) return "left";
        if (position == Prefs.DOCK_RIGHT) return "right";
        if (position == Prefs.DOCK_HIDDEN) return "hidden";
        return "center";
    }

    private void addSleepMethodChooser(LinearLayout parent) {
        TextView label = new LTextView(this);
        label.setText("Sleep method");
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        parent.addView(label);

        final String[] keys = {"accessibility", "root"};
        final String[] labels = {"Accessibility", "Root"};
        final List<TextView> chips = new ArrayList<>();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, Ui.dp(this, 4));
        scroll.addView(row);

        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            TextView chip = new LTextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(Ui.dp(this, 13), Ui.dp(this, 7),
                    Ui.dp(this, 13), Ui.dp(this, 7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 8);
            row.addView(chip, clp);
            chips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setSleepMethod(key);
                    stylePageAnimationChips(chips, keys, key);
                }
            });
        }
        stylePageAnimationChips(chips, keys, prefs.sleepMethod());
        parent.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new LTextView(this);
        hint.setText("Root runs `su`; Accessibility uses Pavo's enabled service to lock the screen. The other method is used as a fallback.");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        parent.addView(hint);

        TextView open = new LTextView(this);
        open.setText("Open accessibility settings");
        styleTextButton(open, false);
        open.setGravity(Gravity.CENTER);
        open.setIncludeFontPadding(false);
        open.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {
                }
            }
        });
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(open, openLp);
    }

    private void addPageAnimationChooser(LinearLayout parent) {
        TextView label = new LTextView(this);
        label.setText("Page animation");
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        parent.addView(label);

        final String[] keys = {"slide", "fade", "zoom", "cube", "stack", "parallax", "random"};
        final String[] labels = {"Slide", "Fade", "Zoom", "Cube", "Stack", "Parallax", "Random"};
        final List<TextView> chips = new ArrayList<>();
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, Ui.dp(this, 4));
        scroll.addView(row);

        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            TextView chip = new LTextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setPadding(Ui.dp(this, 13), Ui.dp(this, 7),
                    Ui.dp(this, 13), Ui.dp(this, 7));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Ui.dp(this, 8);
            row.addView(chip, clp);
            chips.add(chip);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setPageAnimation(key);
                    if (pager != null) pager.setAnimationStyle(key);
                    stylePageAnimationChips(chips, keys, key);
                }
            });
        }
        stylePageAnimationChips(chips, keys, prefs.pageAnimation());
        parent.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new LTextView(this);
        hint.setText("Transition effects are temporarily disabled while drag/page-card reliability is being fixed; all page changes use Slide for now.");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        parent.addView(hint);
    }

    private void stylePageAnimationChips(List<TextView> chips, String[] keys, String selected) {
        for (int i = 0; i < chips.size() && i < keys.length; i++) {
            TextView chip = chips.get(i);
            boolean on = keys[i].equals(selected);
            if (on) {
                chip.setTextColor(0xFF0B0E12);
                chip.setBackground(Ui.rounded(0xFFF2F4F8, Ui.dpf(this, 17)));
            } else {
                chip.setTextColor(0xE6FFFFFF);
                chip.setBackground(Ui.rounded(0x14FFFFFF, Ui.dpf(this, 17),
                        0x22FFFFFF, Ui.dpf(this, 1)));
            }
        }
    }

    private SeekBar addSlider(LinearLayout parent, String name, int min, int max, int value,
                              final IntCallback callback) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 5), 0, 0);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new LTextView(this);
        label.setText(name);
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueView = new LTextView(this);
        valueView.setTextColor(0x8CFFFFFF);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        valueView.setTypeface(Typeface.MONOSPACE);
        valueView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        valueView.setMinWidth(Ui.dp(this, 46));
        valueView.setPadding(0, 0, Ui.dp(this, 5), 0);
        valueView.setText(String.valueOf(value));
        row.setPadding(0, 0, Ui.dp(this, 12), 0);
        row.addView(valueView);
        box.addView(row);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x33FFFFFF));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 2), Ui.dp(this, 14), 0);

        final String suffix = name.contains("opacity") || name.equals("Saturation")
                || name.equals("Glow") ? "%" : "";
        final long[] lastHaptic = {0L};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                valueView.setText(v + suffix);
                if (fromUser) {
                    callback.onValue(v);
                    scheduleLiveSettings();
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastHaptic[0] >= 45L) {
                        lastHaptic[0] = now;
                        Haptics.tick(seekBar);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                Haptics.key(seekBar);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Haptics.click(seekBar);
                applySettingsLive();
            }
        });

        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));
        parent.addView(box);
        return bar;
    }

    private boolean liveSettingsPosted;

    /** Coalesce rapid slider changes into one repaint. */
    private void scheduleLiveSettings() {
        if (liveSettingsPosted) return;
        liveSettingsPosted = true;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                liveSettingsPosted = false;
                applySettingsLive();
            }
        }, 90L);
    }

    /**
     * Re-apply every visual setting immediately so a change is visible on the
     * home behind the panel, instead of only after closing it.
     */
    private void applySettingsLive() {
        if (isFinishing() || isDestroyed()) return;
        try {
            ThemeColors.initialize(this);
            applyColorTuning();
            applyWallpaperPresentation();
            if (dock != null) populateDock();
            renderHome();
            if (pager != null) updateDots(pager.currentPage(), pager.pageCount());
            updateHeaderStatus();
        } catch (Throwable ignored) {
        }
    }

    private void addSwitch(LinearLayout parent, String name, boolean value,
                           final BoolCallback callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        TextView label = new LTextView(this);
        label.setText(name);
        label.setTextColor(0xE6FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setShowText(false);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Haptics.click(buttonView);
                callback.onValue(isChecked);
                applySettingsLive();
            }
        });
        row.addView(sw);
        parent.addView(row);
    }

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    private void cancelPendingGestures() {
        emptyArmed = false;
        emptyTapCandidate = false;
        handler.removeCallbacks(emptyLongPress);
        dismissActiveMenu();
    }

    private void openQuickSettings() {
        Haptics.key(root);
        cancelPendingGestures();
        if (PavoA11yService.openShade()) return;
        // The service may still be connecting after an update; retry once.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!PavoA11yService.openShade()) showQuickSettingsHelp();
            }
        }, 240);
    }

    private void sleepScreen() {
        Haptics.longPress(root);
        String method = prefs.sleepMethod();
        if ("root".equals(method)) {
            if (tryRootSleep()) return;
            if (PavoA11yService.lockScreen()) return;
        } else {
            if (PavoA11yService.lockScreen()) return;
            if (tryRootSleep()) return;
        }
        if ("accessibility".equals(method)) {
            showSleepAccessibilityHelp();
        } else {
            LToast.makeText(this, "Could not sleep: enable root or Pavo accessibility",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Run KEYCODE_SLEEP through PServer (or su) without blocking the UI. */
    private boolean tryRootSleep() {
        if (PServer.available() && PServer.run("input keyevent 223")) return true;
        try {
            final Process process = new ProcessBuilder("su", "-c", "input keyevent 223").start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int exit = process.waitFor();
                        if (exit != 0) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!PavoA11yService.lockScreen()) {
                                        LToast.makeText(MainActivity.this,
                                                "Root sleep failed; enable Pavo accessibility",
                                                Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    } catch (Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!PavoA11yService.lockScreen()) {
                                    LToast.makeText(MainActivity.this,
                                            "Root sleep failed; enable Pavo accessibility",
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }
            }, "sleep-root").start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void showSleepAccessibilityHelp() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Double-tap to sleep");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        TextView body = new LTextView(this);
        body.setText("Locking the screen through accessibility requires Pavo's service. "
                + "Enable Pavo under Settings > Accessibility, or choose Root as the sleep "
                + "method in Launcher settings.");
        body.setTextColor(0xB3FFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        panel.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        TextView later = new LTextView(this);
        later.setText("Not now");
        styleTextButton(later, false);
        later.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(later);
        buttons.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));

        TextView open = new LTextView(this);
        open.setText("Open settings");
        styleTextButton(open, true);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {
                }
            }
        });
        buttons.addView(open);
        panel.addView(buttons);

        showGlassDialog(dialog, panel, backdrop, 460, 0f);
        dialog.show();
    }

    private void showQuickSettingsHelp() {
        // Not enabled yet: offer to turn the gesture helper on.
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Backdrop backdrop = captureBackdrop(false, 0);
        GlassPanel panel = new GlassPanel(this, backdrop,
                wallpaper.accent(), 0x330B0E14, 22, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0);
        panel.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));

        TextView title = new LTextView(this);
        title.setText("Notification drawer gesture");
        title.setTextColor(0xFFF2F4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        panel.addView(title);

        TextView body = new LTextView(this);
        body.setText("Android 15 only lets an accessibility service open the notification "
                + "drawer. Enable Pavo under Settings > Accessibility once, and the swipe-down "
                + "gesture will work.");
        body.setTextColor(0xB3FFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        panel.addView(body);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        TextView later = new LTextView(this);
        later.setText("Not now");
        styleTextButton(later, false);
        later.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        buttons.addView(later);

        buttons.addView(new Space(this), new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));

        TextView open = new LTextView(this);
        open.setText("Open settings");
        styleTextButton(open, true);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {
                }
            }
        });
        buttons.addView(open);
        panel.addView(buttons);

        showGlassDialog(dialog, panel, backdrop, 460, 0f);
        dialog.show();
    }

    // ------------------------------------------------------------------
    // Controller navigation
    // ------------------------------------------------------------------

    private boolean isDrawerVisible() {
        return drawer != null && drawer.getVisibility() == View.VISIBLE;
    }

    private boolean isFolderVisible() {
        return folderOverlay != null && folderOverlay.getVisibility() == View.VISIBLE;
    }

    private static boolean isSelectKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isBackKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK;
    }

    private static boolean isPageKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_R1
                || code == KeyEvent.KEYCODE_BUTTON_L1
                || code == KeyEvent.KEYCODE_PAGE_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP;
    }

    private static boolean isDrawerToggleKey(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_L2
                || code == KeyEvent.KEYCODE_BUTTON_R2;
    }

    private static int dpadKeyCode(int dir) {
        switch (dir) {
            case 0: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 1: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 2: return KeyEvent.KEYCODE_DPAD_UP;
            default: return KeyEvent.KEYCODE_DPAD_DOWN;
        }
    }

    private static int keyDirection(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 1;
            case KeyEvent.KEYCODE_DPAD_UP: return 2;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 3;
            default: return -1;
        }
    }

    private ArrayList<View> controllerCandidates() {
        ArrayList<View> out = new ArrayList<>();
        if (pager != null && pager.getChildCount() > 0) {
            View page = pager.getChildAt(pager.currentPage());
            if (page instanceof GridPage) {
                for (ItemView v : ((GridPage) page).cellViews()) {
                    if (v != null && v.getVisibility() == View.VISIBLE
                            && v.getParent() != null) {
                        out.add(v);
                    }
                }
            }
        }
        if (dock != null && dock.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                View c = dock.getChildAt(i);
                if (c instanceof ItemView && c.getVisibility() == View.VISIBLE) {
                    out.add(c);
                }
            }
        }
        if (dockDrawerButton != null && dockDrawerButton.getParent() != null
                && dockDrawerButton.getVisibility() == View.VISIBLE) {
            out.add(dockDrawerButton);
        }
        if (recordingStatusGlyph != null
                && recordingStatusGlyph.getVisibility() == View.VISIBLE) {
            out.add(recordingStatusGlyph);
        }
        if (bluetoothStatusGlyph != null
                && bluetoothStatusGlyph.getVisibility() == View.VISIBLE) {
            out.add(bluetoothStatusGlyph);
        }
        if (soundStatusGlyph != null
                && soundStatusGlyph.getVisibility() == View.VISIBLE) {
            out.add(soundStatusGlyph);
        }
        if (batteryPill != null && batteryPill.getVisibility() == View.VISIBLE) {
            out.add(batteryPill);
        }
        return out;
    }

    private void updateDrawerButtonFocusRing(LinearLayout box, View icon) {
        clearDrawerButtonFocusRing();
        if (box == null || icon == null || box.getWidth() <= 0 || icon.getWidth() <= 0) return;
        int pad = Ui.dp(this, 3);
        int edge = Ui.dp(this, 2);
        // Only the drawer glyph is ringed; the "Apps" label stays outside the
        // selection, matching the adaptive icon ring used elsewhere.
        int left = Math.max(edge, icon.getLeft() - pad);
        int top = Math.max(edge, icon.getTop() - pad);
        int right = Math.min(box.getWidth() - edge, icon.getRight() + pad);
        int bottom = Math.min(box.getHeight() - edge, icon.getBottom() + pad);
        if (right <= left || bottom <= top) return;
        drawerButtonFocusHost = box;
        drawerButtonFocusRing = Ui.focusRing(this, Ui.dpf(this, 18));
        drawerButtonFocusRing.setBounds(left, top, right, bottom);
        box.getOverlay().add(drawerButtonFocusRing);
    }

    private void clearDrawerButtonFocusRing() {
        if (drawerButtonFocusHost != null && drawerButtonFocusRing != null) {
            drawerButtonFocusHost.getOverlay().remove(drawerButtonFocusRing);
        }
        drawerButtonFocusHost = null;
        drawerButtonFocusRing = null;
    }

    private void rememberControllerFocus() {
        if (!controllerActive) return;
        View f = getCurrentFocus();
        if (f == dockDrawerButton || isHeaderControl(f)
                || (f instanceof ItemView && f.getParent() != drawerGrid
                && f.getParent() != folderGrid)) {
            controllerReturnFocus = f;
        }
    }

    private void restoreControllerFocus() {
        View v = controllerReturnFocus;
        controllerReturnFocus = null;
        if (v != null && v.isAttachedToWindow() && v.getVisibility() == View.VISIBLE
                && (v == dockDrawerButton || isHeaderControl(v) || v instanceof ItemView)) {
            v.requestFocus();
            return;
        }
        ensureControllerFocus();
    }

    private void ensureControllerFocus() {
        if (isDrawerVisible()) {
            if (!(getCurrentFocus() instanceof ItemView)) focusFirstInGrid(drawerGrid);
            return;
        }
        if (isFolderVisible()) {
            if (!(getCurrentFocus() instanceof ItemView)) focusFirstInGrid(folderGrid);
            return;
        }
        ArrayList<View> candidates = controllerCandidates();
        View current = getCurrentFocus();
        if (current != null && candidates.contains(current)) return;
        if (!candidates.isEmpty()) candidates.get(0).requestFocus();
    }

    private void focusFirstInGrid(GridView grid) {
        if (grid == null) return;
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (grid.getChildCount() == 0) {
                    grid.requestFocus();
                    return;
                }
                for (int i = 0; i < grid.getChildCount(); i++) {
                    View c = grid.getChildAt(i);
                    if (c instanceof ItemView && c.getVisibility() == View.VISIBLE) {
                        c.requestFocus();
                        return;
                    }
                }
                grid.requestFocus();
            }
        });
    }

    private boolean moveControllerFocus(int dir) {
        ArrayList<View> candidates = controllerCandidates();
        if (candidates.isEmpty()) return false;
        View current = getCurrentFocus();
        if (current == null || !current.isAttachedToWindow()) {
            candidates.get(0).requestFocus();
            return true;
        }
        int[] cur = new int[2];
        current.getLocationOnScreen(cur);
        float cx = cur[0] + current.getWidth() / 2f;
        float cy = cur[1] + current.getHeight() / 2f;
        int dx = dir == 0 ? -1 : dir == 1 ? 1 : 0;
        int dy = dir == 2 ? -1 : dir == 3 ? 1 : 0;
        View best = null;
        float bestScore = Float.MAX_VALUE;
        float minPrimary = (dx != 0 ? current.getWidth() : current.getHeight()) * 0.35f;
        for (View v : candidates) {
            if (v == current) continue;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float vx = loc[0] + v.getWidth() / 2f;
            float vy = loc[1] + v.getHeight() / 2f;
            float rx = vx - cx;
            float ry = vy - cy;
            float primary = dx != 0 ? rx * dx : ry * dy;
            if (primary <= Math.max(8f, minPrimary)) continue;
            float secondary = Math.abs(dx != 0 ? ry : rx);
            float score = primary + secondary * 1.65f;
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best == null) {
            if (candidates.contains(current)) return false;
            candidates.get(0).requestFocus();
            return true;
        }
        best.requestFocus();
        return true;
    }

    private void focusPageFirst(int page) {
        if (pager == null || page < 0 || page >= pager.getChildCount()) return;
        View p = pager.getChildAt(page);
        if (p instanceof GridPage) {
            GridPage gp = (GridPage) p;
            for (ItemView v : gp.cellViews()) {
                if (v != null && v.getVisibility() == View.VISIBLE) {
                    v.requestFocus();
                    return;
                }
            }
        }
        if (dock != null) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                View c = dock.getChildAt(i);
                if (c instanceof ItemView) {
                    c.requestFocus();
                    return;
                }
            }
        }
        if (dockDrawerButton != null) dockDrawerButton.requestFocus();
    }

    private boolean pageByController(int delta) {
        if (pager == null || isDrawerVisible() || isFolderVisible()) return false;
        int target = pager.currentPage() + delta;
        if (target < 0 || target >= pager.pageCount()) return false;
        pager.setPage(target, true);
        lastPageTurnAt = android.os.SystemClock.uptimeMillis();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                focusPageFirst(target);
            }
        }, 430);
        return true;
    }

    /** True when focus is on the current page and has no icon further left/right in its row. */
    private boolean focusAtPageHorizontalEdge(int dir) {
        if (pager == null || (dir != 0 && dir != 1)) return false;
        View focused = getCurrentFocus();
        if (!(focused instanceof ItemView)) return false;
        int page = pager.currentPage();
        if (page < 0 || page >= pager.getChildCount()) return false;
        View pageView = pager.getChildAt(page);
        if (!(pageView instanceof GridPage) || focused.getParent() != pageView) return false;
        ItemView item = (ItemView) focused;
        if (item.cellIndex < 0) return false;
        int cols = Math.max(1, gridCols);
        int column = item.cellIndex % cols;
        int row = item.cellIndex / cols;
        for (ItemView v : ((GridPage) pageView).cellViews()) {
            if (v == null || v.getVisibility() != View.VISIBLE || v.cellIndex < 0) continue;
            if (v.cellIndex / cols != row) continue;
            int c = v.cellIndex % cols;
            if (dir == 0 ? c < column : c > column) return false;
        }
        return true;
    }

    /**
     * Move focus, but when the current page has no icon further left/right,
     * flip to the adjacent existing page. This is used by both D-pad and the
     * left stick. A stick page turn is allowed once per deflection so holding
     * the stick cannot race through every page.
     */
    private boolean moveControllerFocusOrPage(int dir, boolean fromStick, boolean allowPage) {
        if (focusAtPageHorizontalEdge(dir)) {
            if (!allowPage) return false;
            if (SystemClock.uptimeMillis() - lastPageTurnAt < 320L) return true;
            if (pageByController(dir == 0 ? -1 : 1)) {
                finishStickPageTurn(dir, fromStick);
                return true;
            }
            return moveControllerFocus(dir);
        }
        if (moveControllerFocus(dir)) return true;
        if (!allowPage || (dir != 0 && dir != 1)) return false;
        if (SystemClock.uptimeMillis() - lastPageTurnAt < 320L) return true;
        if (pageByController(dir == 0 ? -1 : 1)) {
            finishStickPageTurn(dir, fromStick);
            return true;
        }
        return false;
    }

    private void finishStickPageTurn(int dir, boolean fromStick) {
        if (!fromStick) return;
        pageTurnStickDir = dir;
        stickHeldDir = -1;
        lastStickDir = dir;
        handler.removeCallbacks(stickRepeat);
    }

    private boolean selectControllerView() {
        View focused = getCurrentFocus();
        if (isHeaderControl(focused)) {
            if (focused.isClickable()) focused.performClick();
            return true;
        }
        if (isDrawerVisible()) {
            if (!(focused instanceof ItemView) || focused.getParent() != drawerGrid) {
                focusFirstInGrid(drawerGrid);
                return true;
            }
        } else if (isFolderVisible()) {
            if (!(focused instanceof ItemView) || focused.getParent() != folderGrid) {
                focusFirstInGrid(folderGrid);
                return true;
            }
        } else {
            if (focused != dockDrawerButton && !(focused instanceof ItemView)) {
                ensureControllerFocus();
                return true;
            }
        }
        if (focused == null) return true;
        if (focused.isClickable()) focused.performClick();
        return true;
    }

    private void navigateStick(int dir) {
        if (activeMenu != null && activeMenu.isShowing()) navigateMenu(dir);
        else if (isDrawerVisible()) sendGridDirection(drawerGrid, dir);
        else if (isFolderVisible()) sendGridDirection(folderGrid, dir);
        else moveControllerFocusOrPage(dir, true, true);
    }

    private void cancelStickRepeat() {
        stickHeldDir = -1;
        lastStickDir = -1;
        stickAxis = 0;
        pageTurnStickDir = -1;
        handler.removeCallbacks(stickRepeat);
    }

    private boolean sendGridDirection(GridView grid, int dir) {
        if (grid == null) return false;
        View focused = getCurrentFocus();
        if (!(focused instanceof ItemView) || focused.getParent() != grid) {
            focusFirstInGrid(grid);
            return true;
        }
        int focusDir = dir == 0 ? View.FOCUS_LEFT : dir == 1 ? View.FOCUS_RIGHT
                : dir == 2 ? View.FOCUS_UP : View.FOCUS_DOWN;
        View next = FocusFinder.getInstance().findNextFocus(grid, focused, focusDir);
        if (next != null && next != focused) {
            next.requestFocus();
            return true;
        }
        // At the visible edge of a scrolling grid, scroll one row and keep the
        // selection in the same column.
        if (dir == 2 || dir == 3) {
            android.widget.ListAdapter adapter = grid.getAdapter();
            if (dir == 3 && adapter != null
                    && grid.getLastVisiblePosition() >= adapter.getCount() - 1) {
                return false;
            }
            if (dir == 2 && grid.getFirstVisiblePosition() == 0) return false;
            int[] loc = new int[2];
            focused.getLocationOnScreen(loc);
            final float oldX = loc[0] + focused.getWidth() / 2f;
            View sample = grid.getChildAt(0);
            int step = sample != null ? sample.getHeight() : Ui.dp(this, 80);
            grid.scrollListBy(dir == 3 ? step : -step);
            grid.postDelayed(new Runnable() {
                @Override
                public void run() {
                    focusGridEdge(grid, dir, oldX);
                }
            }, 30);
            return true;
        }
        return false;
    }

    private void focusGridEdge(GridView grid, int dir, float oldX) {
        int[] loc = new int[2];
        int bestY = dir == 3 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (!(c instanceof ItemView) || c.getVisibility() != View.VISIBLE) continue;
            c.getLocationOnScreen(loc);
            int cy = loc[1] + c.getHeight() / 2;
            if (dir == 3 ? cy > bestY : cy < bestY) bestY = cy;
        }
        if (bestY == Integer.MIN_VALUE || bestY == Integer.MAX_VALUE) return;
        View best = null;
        float bestDx = Float.MAX_VALUE;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (!(c instanceof ItemView) || c.getVisibility() != View.VISIBLE) continue;
            c.getLocationOnScreen(loc);
            int cy = loc[1] + c.getHeight() / 2;
            if (Math.abs(cy - bestY) > 4) continue;
            float dx = Math.abs(loc[0] + c.getWidth() / 2f - oldX);
            if (dx < bestDx) {
                bestDx = dx;
                best = c;
            }
        }
        if (best != null) best.requestFocus();
    }

    private void toggleLauncherDrawer(boolean popup) {
        controllerActive = true;
        if (isDrawerVisible()) {
            if (drawerPopup == popup) {
                closeDrawer();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDrawerVisible()) restoreControllerFocus();
                    }
                }, drawerPopup ? 430 : 300);
                return;
            }
            closeDrawer(false);
        }
        if (isFolderVisible()) {
            closeFolder();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    openDrawer(popup);
                }
            }, 240);
            return;
        }
        if (activeMenu != null) dismissActiveMenu();
        openDrawer(popup);
    }

    private void beginControllerALongPress() {
        controllerAHeld = true;
        controllerALongFired = false;
        handler.removeCallbacks(controllerALongPress);
        handler.postDelayed(controllerALongPress, 520L);
    }

    private void finishControllerA() {
        if (!controllerAHeld) return;
        controllerAHeld = false;
        handler.removeCallbacks(controllerALongPress);
        if (!controllerALongFired) selectControllerView();
        controllerALongFired = false;
    }

    private void beginControllerYLongPress() {
        controllerYHeld = true;
        controllerYLongFired = false;
        handler.removeCallbacks(controllerYLongPress);
        if (activityResumed && hasWindowFocus()
                && (settingsDialog == null || !settingsDialog.isShowing())
                && !isDrawerVisible() && !isFolderVisible() && activeMenu == null) {
            handler.postDelayed(controllerYLongPress, 520L);
        }
    }

    private void finishControllerY() {
        controllerYHeld = false;
        controllerYLongFired = false;
        handler.removeCallbacks(controllerYLongPress);
    }

    private boolean handleControllerKeyDown(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_Y) {
            if (event.getRepeatCount() == 0) {
                controllerActive = true;
                beginControllerYLongPress();
            }
            return true;
        }
        if (isDrawerToggleKey(code)) {
            if (event.getRepeatCount() == 0) {
                toggleLauncherDrawer(code == KeyEvent.KEYCODE_BUTTON_L2);
            }
            return true;
        }
        if (activeMenu != null) {
            // The PopupWindow itself is not focusable; controller input stays here
            // and drives the menu rows.
            if (!activeMenu.isShowing()) {
                dismissActiveMenu();
                return true;
            }
            int menuDir = keyDirection(code);
            if (menuDir >= 0) {
                long now = android.os.SystemClock.uptimeMillis();
                if (event.getRepeatCount() > 0 && now - lastMenuNavAt < 80L) return true;
                lastMenuNavAt = now;
                navigateMenu(menuDir);
                return true;
            }
            if (isSelectKey(code)) {
                if (event.getRepeatCount() == 0) activateMenuSelection();
                return true;
            }
            if (isBackKey(code) && event.getRepeatCount() == 0) {
                Haptics.key(root);
                dismissActiveMenu();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ensureControllerFocus();
                    }
                }, 120);
            }
            return true;
        }
        if (isSelectKey(code)) {
            if (event.getRepeatCount() > 0) return true;
            controllerActive = true;
            if (code == KeyEvent.KEYCODE_BUTTON_A
                    && getCurrentFocus() instanceof ItemView) {
                beginControllerALongPress();
                return true;
            }
            return selectControllerView();
        }
        if (isBackKey(code)) {
            if (event.getRepeatCount() > 0) return true;
            controllerActive = true;
            if (isDrawerVisible() || isFolderVisible()) {
                final boolean popupClose = drawerPopup && isDrawerVisible();
                onBackPressed();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDrawerVisible() && !isFolderVisible()) restoreControllerFocus();
                    }
                }, popupClose ? 430 : 320);
            }
            // Back on the home screen should not leave the launcher.
            return true;
        }
        if (isPageKey(code)) {
            if (event.getRepeatCount() > 0) return true;
            controllerActive = true;
            if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_PAGE_UP) {
                pageByController(-1);
            } else {
                pageByController(1);
            }
            return true;
        }
        int dir = keyDirection(code);
        if (dir >= 0) {
            controllerActive = true;
            cancelStickRepeat();
            if (isDrawerVisible()) {
                sendGridDirection(drawerGrid, dir);
                return true;
            }
            if (isFolderVisible()) {
                sendGridDirection(folderGrid, dir);
                return true;
            }
            moveControllerFocusOrPage(dir, false, event.getRepeatCount() == 0);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        noteUserActivity();
        int floatingCode = event.getKeyCode();
        if (PavoA11yService.isFloatingDrawerVisible()
                && (isSelectKey(floatingCode) || isBackKey(floatingCode)
                || floatingCode == KeyEvent.KEYCODE_BUTTON_Y
                || keyDirection(floatingCode) >= 0 || isDrawerToggleKey(floatingCode))) {
            // The accessibility service reads the raw controller and drives the
            // floating drawer; consume here so the home screen does not also react.
            return true;
        }
        // Android's joystick mapper turns the left stick into DPAD key events at a
        // fairly low threshold. Those have scan code 0 and source JOYSTICK; the
        // real D-pad keeps its Linux scan code. Ignore the synthetic keys and let
        // the analog handler below apply the larger dead zone and slower repeat.
        if (keyDirection(event.getKeyCode()) >= 0
                && event.getScanCode() == 0
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0
                && !RootlessRemap.isVirtualEvent(event)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (handleControllerKeyDown(event)) return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BUTTON_A) {
                finishControllerA();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_Y) {
                finishControllerY();
                return true;
            }
            if (isSelectKey(code) || isBackKey(code) || isPageKey(code)
                    || isDrawerToggleKey(code)) return true;
            if (keyDirection(code) >= 0
                    && !isDrawerVisible() && !isFolderVisible()) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        noteUserActivity();
        int source = event.getSource();
        boolean joystick = (source & InputDevice.SOURCE_JOYSTICK) != 0
                || (source & InputDevice.SOURCE_GAMEPAD) != 0;
        if (joystick && PavoA11yService.isFloatingDrawerVisible()) {
            return true;
        }
        if (joystick && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float ry = StickScroll.rightY(event);
            if (StickScroll.active(ry)) {
                View scroll = null;
                if (isDrawerVisible()) scroll = drawerGrid;
                else if (isFolderVisible()) scroll = folderGrid;
                if (StickScroll.scroll(scroll, ry, drawerScrollStickAt)) return true;
            }
            float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < 0.5f) x = event.getAxisValue(MotionEvent.AXIS_X);
            if (Math.abs(y) < 0.5f) y = event.getAxisValue(MotionEvent.AXIS_Y);
            float ax = Math.abs(x);
            float ay = Math.abs(y);

            // Centre release: require the stick to come back near the middle
            // before a new deliberate push counts.
            if (ax < 0.42f && ay < 0.42f) {
                cancelStickRepeat();
                return false;
            }
            if (stickAxis == 0) {
                // Higher entry threshold: small thumb movements no longer move focus.
                if (Math.max(ax, ay) < 0.72f) return false;
                stickAxis = ax >= ay ? 1 : 2;
            } else if (stickAxis == 1) {
                // Small diagonal wobble should not turn a horizontal sweep into
                // an up/down jump. Require a clear turn before changing axes.
                if (ay > ax + 0.25f) stickAxis = 2;
            } else {
                if (ax > ay + 0.25f) stickAxis = 1;
            }

            float selected = stickAxis == 1 ? ax : ay;
            if (selected < 0.62f) {
                // Partial release stops auto-repeat, but keeps the axis lock until
                // the stick is centred so returning is stable.
                stickHeldDir = -1;
                lastStickDir = -1;
                pageTurnStickDir = -1;
                handler.removeCallbacks(stickRepeat);
                return true;
            }

            int dir = stickAxis == 1 ? (x < 0 ? 0 : 1) : (y < 0 ? 2 : 3);
            if (dir != lastStickDir) {
                lastStickDir = dir;
                controllerActive = true;
                navigateStick(dir);
            }
            if (pageTurnStickDir == dir) {
                // A page turn is one action per stick deflection. Wait for the
                // stick to release before accepting another navigation step.
            } else if (dir != stickHeldDir) {
                stickHeldDir = dir;
                handler.removeCallbacks(stickRepeat);
                // A confident push moves one step; a long hold then repeats slowly.
                handler.postDelayed(stickRepeat, 340);
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void openScreenRecorderFromIndicator() {
        Haptics.click(recordingStatusGlyph);
        Intent recorder = new Intent(MainActivity.this, ScreenRecorderActivity.class);
        if (!ScreenRecorderService.isRecording()) {
            recorder.putExtra(ScreenRecorderActivity.EXTRA_AUTO_FULLSCREEN, true);
        }
        startBuiltIn(recorder, null);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        noteUserActivity();
        if (recordingStatusGlyph != null && drawer != null
                && drawer.getVisibility() != View.VISIBLE
                && (folderOverlay == null || folderOverlay.getVisibility() != View.VISIBLE)
                && !dragging && dragSource == null) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    && pointInView(recordingStatusGlyph, ev.getRawX(), ev.getRawY())) {
                recordingIndicatorDown = true;
                recordingIndicatorDownX = ev.getRawX();
                recordingIndicatorDownY = ev.getRawY();
                return true;
            }
            if (recordingIndicatorDown) {
                if (action == MotionEvent.ACTION_UP) {
                    float dx = ev.getRawX() - recordingIndicatorDownX;
                    float dy = ev.getRawY() - recordingIndicatorDownY;
                    recordingIndicatorDown = false;
                    if (Math.hypot(dx, dy) <= Ui.dpf(this, 12)) {
                        openScreenRecorderFromIndicator();
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    recordingIndicatorDown = false;
                    return true;
                }
            }
        }
        // A widget resize owns the gesture. Do not let the launcher's home
        // swipe (drawer) or swipe-down (notification shade) handlers see it.
        if (dragging || dragSource != null || resizeWidget != null || widgetResizeMode
                || clockDragging || clockResizeMode || clockResizing) {
            return super.dispatchTouchEvent(ev);
        }
        if (drawerSwipeActive) {
            handleDrawerSwipe(ev);
            return true;
        }
        if (drawerCloseSwipeActive) {
            handleDrawerCloseSwipe(ev);
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            controllerActive = false;
            View focused = getCurrentFocus();
            if (focused instanceof ItemView) focused.clearFocus();
            cancelStickRepeat();
        }
        final boolean drawerOpen = drawer != null && drawer.getVisibility() == View.VISIBLE;
        final boolean folderOpen = folderOverlay != null
                && folderOverlay.getVisibility() == View.VISIBLE;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (activeMenu != null) {
                    if (activeMenu.isShowing()) {
                        Haptics.outside(root);
                        dismissActiveMenu();
                        return true;
                    }
                    activeMenu = null;
                }
                cancelEmptyLongPress();
                boolean emptyPoint = !drawerOpen && !folderOpen && !dragging
                        && dragSource == null
                        && isEmptyHomePoint(ev.getRawX(), ev.getRawY());
                if (emptyPoint) {
                    emptyArmed = true;
                    emptyTapCandidate = prefs.sleepEnabled();
                    emptyDownX = ev.getRawX();
                    emptyDownY = ev.getRawY();
                    handler.postDelayed(emptyLongPress, 560);
                } else {
                    emptyTapCandidate = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (emptyArmed) {
                    float mdx = ev.getRawX() - emptyDownX;
                    float mdy = ev.getRawY() - emptyDownY;
                    if (Math.hypot(mdx, mdy) > Ui.dpf(this, 9)) {
                        emptyTapCandidate = false;
                        cancelEmptyLongPress();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (emptyTapCandidate && prefs.sleepEnabled()) {
                    float dx = ev.getRawX() - emptyDownX;
                    float dy = ev.getRawY() - emptyDownY;
                    if (Math.hypot(dx, dy) <= Ui.dpf(this, 12)) {
                        long now = System.currentTimeMillis();
                        boolean secondTap = now - lastSleepTapAt <= 350
                                && Math.hypot(ev.getRawX() - lastSleepTapX,
                                ev.getRawY() - lastSleepTapY) <= Ui.dpf(this, 90);
                        emptyTapCandidate = false;
                        cancelEmptyLongPress();
                        if (secondTap) {
                            lastSleepTapAt = 0;
                            sleepScreen();
                        } else {
                            lastSleepTapAt = now;
                            lastSleepTapX = ev.getRawX();
                            lastSleepTapY = ev.getRawY();
                        }
                        break;
                    }
                }
                emptyTapCandidate = false;
                cancelEmptyLongPress();
                break;
            case MotionEvent.ACTION_CANCEL:
                emptyTapCandidate = false;
                cancelEmptyLongPress();
                break;
        }

        if (folderOpen && !dragging) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    folderState.downX = ev.getRawX();
                    folderState.downY = ev.getRawY();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = ev.getRawX() - folderState.downX;
                    float dy = ev.getRawY() - folderState.downY;
                    if (dy > Ui.dpf(this, 80) && Math.abs(dy) > Math.abs(dx)) {
                        closeFolder();
                    }
                    break;
                }
            }
        } else if (drawerOpen && !dragging) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drawerState.downX = ev.getRawX();
                    drawerState.downY = ev.getRawY();
                    recycleDrawerCloseVelocity();
                    drawerCloseArmed = false;
                    if (!drawerPopup) {
                        boolean onGrid = pointInView(drawerGrid, ev.getRawX(), ev.getRawY());
                        boolean canScrollUp = drawerGrid != null
                                && drawerGrid.canScrollVertically(-1);
                        drawerCloseArmed = !(onGrid && canScrollUp);
                        drawerCloseVelocity = VelocityTracker.obtain();
                        drawerCloseVelocity.addMovement(ev);
                    }
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (drawerCloseVelocity != null) drawerCloseVelocity.addMovement(ev);
                    if (!drawerCloseArmed || drawerCloseSwipeActive) break;
                    float dx = ev.getRawX() - drawerState.downX;
                    float dy = ev.getRawY() - drawerState.downY;
                    int slop = Math.max(Ui.dp(this, 10),
                            ViewConfiguration.get(this).getScaledTouchSlop());
                    if (dy > slop && Math.abs(dy) > Math.abs(dx) * 1.1f) {
                        drawerCloseArmed = false;
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        drawerCloseSwipeActive = true;
                        Motion.cancelGiggle(drawerCard);
                        drawerCard.setRotation(0f);
                        float height = drawer.getHeight() > 0 ? drawer.getHeight()
                                : getResources().getDisplayMetrics().heightPixels;
                        drawerCloseStartProgress = Math.max(0f, Math.min(1f,
                                1f - drawer.getTranslationY() / height));
                        drawerSwipeProgress = drawerCloseStartProgress;
                        drawer.setAlpha(1f);
                        float range = Math.max(1f,
                                getResources().getDisplayMetrics().heightPixels * 0.48f);
                        applyDrawerSwipeProgress(drawerCloseStartProgress - dy / range);
                        return true;
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!drawerCloseSwipeActive) {
                        float dx = ev.getRawX() - drawerState.downX;
                        float dy = ev.getRawY() - drawerState.downY;
                        if (drawerPopup && dy > Ui.dpf(this, 80)
                                && Math.abs(dy) > Math.abs(dx)) {
                            closeDrawer();
                        }
                    }
                    drawerCloseArmed = false;
                    recycleDrawerCloseVelocity();
                    break;
                }
            }
        } else {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    homeGesture.downX = ev.getRawX();
                    homeGesture.downY = ev.getRawY();
                    homeGesture.axis = 0;
                    homeGesture.active = true;
                    homeSwipeStartPage = pager != null ? pager.currentPage() : -1;
                    recycleHomeVelocity();
                    homeVelocity = VelocityTracker.obtain();
                    homeVelocity.addMovement(ev);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (homeVelocity != null) homeVelocity.addMovement(ev);
                    if (!homeGesture.active || dragging || dockDrawerDragging
                            || dragSource != null) break;
                    float dx = ev.getRawX() - homeGesture.downX;
                    float dy = ev.getRawY() - homeGesture.downY;
                    int slop = Math.max(Ui.dp(this, 10),
                            ViewConfiguration.get(this).getScaledTouchSlop());
                    if (homeGesture.axis == 0) {
                        float adx = Math.abs(dx);
                        float ady = Math.abs(dy);
                        float verticalSlop = Math.max(Ui.dp(this, 8), slop * 0.65f);
                        float horizontalSlop = Math.max(Ui.dp(this, 20), slop * 1.8f);
                        if (ady > verticalSlop && ady >= adx * 0.65f) {
                            homeGesture.axis = 2;
                        } else if (adx > horizontalSlop && adx > ady * 1.80f) {
                            homeGesture.axis = 1;
                        }
                    }
                    // The pager applies the same axis lock internally. Do not
                    // leave it locked if the system shade takes over the gesture
                    // and no ACTION_UP ever comes back.
                    if (homeGesture.axis == 1) break;
                    if (homeGesture.axis == 2 && pager != null) {
                        pager.cancelHorizontalForVertical(homeSwipeStartPage);
                    }
                    if ((homeGesture.axis == 2 && dy < -Ui.dp(this, 90))
                            || (dy < -slop && Math.abs(dy) > Math.abs(dx) * 1.1f)) {
                        homeGesture.active = false;
                        cancelEmptyLongPress();
                        // Let the touched icon leave its pressed/long-press state
                        // before the drawer takes over the gesture.
                        MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        if (prepareDrawer(false)) {
                            drawerSwipeActive = true;
                            drawer.setAlpha(1f);
                            float range = Math.max(1f,
                                    getResources().getDisplayMetrics().heightPixels * 0.48f);
                            applyDrawerSwipeProgress(Math.min(1f, -dy / range));
                            return true;
                        }
                    } else if (dy > Ui.dpf(this, 70)
                            && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        homeGesture.active = false;
                        openQuickSettings();
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    homeGesture.active = false;
                    homeGesture.axis = 0;
                    if (pager != null) {
                        pager.setLocked(widgetResizeMode || resizeWidget != null
                                || clockResizeMode || clockResizing);
                    }
                    recycleHomeVelocity();
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void handleDrawerSwipe(MotionEvent ev) {
        if (homeVelocity != null) homeVelocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getRawY() - homeGesture.downY;
                float range = Math.max(1f,
                        getResources().getDisplayMetrics().heightPixels * 0.48f);
                applyDrawerSwipeProgress(Math.min(1f, -dy / range));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0f;
                if (homeVelocity != null) {
                    homeVelocity.computeCurrentVelocity(1000);
                    vy = homeVelocity.getYVelocity();
                }
                boolean open;
                if (vy < -500f) open = true;
                else if (vy > 500f) open = false;
                else open = drawerSwipeProgress > 0.34f;
                if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) open = false;
                drawerSwipeActive = false;
                recycleHomeVelocity();
                settleDrawer(open, vy);
                break;
            }
            default:
                break;
        }
    }

    private void handleDrawerCloseSwipe(MotionEvent ev) {
        if (drawerCloseVelocity != null) drawerCloseVelocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getRawY() - drawerState.downY;
                float range = Math.max(1f,
                        getResources().getDisplayMetrics().heightPixels * 0.48f);
                applyDrawerSwipeProgress(drawerCloseStartProgress - dy / range);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0f;
                if (drawerCloseVelocity != null) {
                    drawerCloseVelocity.computeCurrentVelocity(1000);
                    vy = drawerCloseVelocity.getYVelocity();
                }
                boolean close;
                if (ev.getActionMasked() == MotionEvent.ACTION_CANCEL) close = true;
                else if (vy > 500f) close = true;
                else if (vy < -500f) close = false;
                else close = drawerSwipeProgress < 0.68f;
                drawerCloseSwipeActive = false;
                drawerCloseArmed = false;
                recycleDrawerCloseVelocity();
                settleDrawer(!close, vy);
                break;
            }
            default:
                break;
        }
    }

    private void recycleHomeVelocity() {
        if (homeVelocity != null) {
            homeVelocity.recycle();
            homeVelocity = null;
        }
    }

    private void recycleDrawerCloseVelocity() {
        if (drawerCloseVelocity != null) {
            drawerCloseVelocity.recycle();
            drawerCloseVelocity = null;
        }
    }

    private boolean pointInView(View view, float rawX, float rawY) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX < loc[0] + view.getWidth()
                && rawY >= loc[1] && rawY < loc[1] + view.getHeight();
    }

    private void cancelEmptyLongPress() {
        emptyArmed = false;
        handler.removeCallbacks(emptyLongPress);
    }

    /** True when the point lands on an unoccupied cell of the current home page. */
    private boolean isEmptyHomePoint(float rawX, float rawY) {
        if (pager == null || pager.getChildCount() == 0) return false;
        if (clockBox != null && clockBox.getVisibility() == View.VISIBLE
                && clockBox.getParent() != null) {
            int[] cl = new int[2];
            clockBox.getLocationOnScreen(cl);
            if (rawX >= cl[0] && rawX < cl[0] + clockBox.getWidth()
                    && rawY >= cl[1] && rawY < cl[1] + clockBox.getHeight()) {
                return false;
            }
        }
        int[] pl = new int[2];
        pager.getLocationOnScreen(pl);
        if (rawX < pl[0] || rawX >= pl[0] + pager.getWidth()) return false;
        if (rawY < pl[1] || rawY >= pl[1] + pager.getHeight()) return false;

        int pw = Math.max(1, pager.pageWidth());
        float px = rawX - pl[0] + pager.getScrollX();
        int page = (int) Math.floor(px / pw);
        page = Math.max(0, Math.min(pager.pageCount() - 1, page));
        float lx = px - page * pw;
        float ly = rawY - pl[1];

        View c = pager.getChildAt(page);
        if (!(c instanceof GridPage)) return false;
        GridPage gp = (GridPage) c;
        int cell = gp.cellAt(lx, ly);
        // The tight grid sits in the middle; the wallpaper around it is empty space too.
        if (cell < 0) return true;
        if (widgetCovers(page, cell)) return false;
        return gp.viewAt(cell) == null;
    }

    private static final class Gesture {
        float downX, downY;
        boolean active;
        int axis; // 0 = undecided, 1 = horizontal, 2 = vertical
    }

    private final Gesture homeGesture = new Gesture();
    private final Gesture drawerState = new Gesture();
    private final Gesture folderState = new Gesture();

    // ------------------------------------------------------------------
    // Insets
    // ------------------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            applySurfaceTransparency();
        }
    }
}
