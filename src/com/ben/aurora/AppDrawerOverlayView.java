package com.ben.aurora;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Floating left-edge app drawer. It slides in from the left, can show one or two
 * app columns, reserves four favourite slots, adapts its icon/search sizes to its
 * horizontal size, and exposes style controls from a long-press on the header.
 */
final class AppDrawerOverlayView extends FrameLayout {
    interface Listener {
        void onDismiss();
        void onSearchFocusRequested(boolean focus);
    }

    private static int accent() {
        return ThemeColors.accent();
    }
    private static final int FAVORITE_SLOTS = 4;
    private static final String PERF_TUNER_PKG = "com.ben.aurora.perftuner";
    private static final String PERF_LOG_PKG = "com.ben.aurora.perflogs";
    private static final String DISPLAY_PKG = "com.ben.aurora.display";
    private static final String CONTROLLER_PKG = "com.ben.aurora.controllermap";
    private static final String RGB_PKG = "com.ben.aurora.rgb";
    private static final String FAN_PKG = "com.ben.aurora.fan";
    private static final String OVERLAY_PKG = "com.ben.aurora.overlay";
    private static final String TOUCH_MAP_PKG = "com.ben.aurora.touchmapper";
    private static final String DEVICE_HEALTH_PKG = "com.ben.aurora.devicehealth";
    private static final String BENCHMARK_PKG = "com.ben.aurora.benchmark";
    private static final String FILE_MANAGER_PKG = "com.ben.aurora.files";
    private static final String SCREEN_RECORDER_PKG = "com.ben.aurora.screenrecorder";

    private final Context context;
    private final Prefs prefs;
    private final Store store;
    private final IconPack.Resolver iconPack;
    private final List<Apps.Entry> allApps = new ArrayList<>();
    private final List<Apps.Entry> shownApps = new ArrayList<>();
    private final List<Apps.Entry> favorites = new ArrayList<>();
    private final Listener listener;
    private boolean packageReceiverRegistered;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            refreshApps();
        }
    };

    private FrameLayout cardFrame;
    private GlassPanel glass;
    private ListView list;
    private EditText search;
    private TextView emptyView;
    private TextView columnsButton;
    private TextView favoritesLabel;
    private LinearLayout favoritesBox;
    private TextView allAppsLabel;
    private LinearLayout appMenuPanel;
    private FrameLayout appMenuOverlay;
    private LinearLayout stylePanel;
    private FrameLayout styleOverlay;
    private TextView alphaValue;
    private TextView iconValue;
    private TextView alphaMinus;
    private TextView alphaPlus;
    private TextView iconMinus;
    private TextView iconPlus;
    private TextView styleDone;
    private TextView styleTitle;
    private FrameLayout.LayoutParams stylePanelLp;
    private final List<View> styleNavItems = new ArrayList<>();
    private int styleNavIndex = -1;
    private FocusRingDrawable styleNavRing;
    private final List<View> appMenuNavItems = new ArrayList<>();
    private int appMenuNavIndex = -1;
    private FocusRingDrawable appMenuNavRing;
    private float styleDragDownX;
    private float styleDragDownY;
    private int styleDragStartLeft;
    private int styleDragStartTop;
    private final DrawerAdapter adapter = new DrawerAdapter();

    private int columns;
    private int oneColW;
    private int twoColW;
    private int screenW;
    private int screenH;
    private int edgePad;
    private int iconPx;
    private float labelSp = 14f;
    private String query = "";
    private boolean closing;
    private boolean searchOpen;
    private ValueAnimator widthAnim;
    private boolean controllerSelection;
    private boolean mapperMode;
    private String selectedKey;
    private long lastControllerHapticAt;
    private float navStickX, navStickY, navFastY;
    private int navStickAxis, navStickDir = -1, navStickHeld = -1;
    private int navFastDir = -1;
    private int navHoldDir = -1;
    private long navHoldStartedAt;
    private boolean drawerShortcutDown;
    private boolean drawerShortcutLongFired;
    private long ignoreShortcutUntil;
    private final Runnable drawerShortcutLongPress = new Runnable() {
        @Override
        public void run() {
            if (!drawerShortcutDown) return;
            drawerShortcutLongFired = true;
            Haptics.longPress(AppDrawerOverlayView.this);
            showStyleOverlay();
        }
    };

    private final Runnable navStickRepeat = new Runnable() {
        @Override
        public void run() {
            if (!mapperMode || navStickHeld < 0) return;
            boolean submenu = isSubmenuVisible();
            int steps = submenu ? 1 : navRepeatSteps();
            long delay = submenu ? 70L : navRepeatDelay();
            int dx = navStickHeld == 0 ? -1 : navStickHeld == 1 ? 1 : 0;
            int dy = navStickHeld == 2 ? -1 : navStickHeld == 3 ? 1 : 0;
            onControllerDirection(dx, dy, steps);
            postDelayed(this, delay);
        }
    };

    private final Runnable navFastRepeat = new Runnable() {
        @Override
        public void run() {
            if (!mapperMode || navFastDir < 0) return;
            int dy = navFastDir == 2 ? -1 : 1;
            int steps = Math.abs(navFastY) > 0.60f ? 2 : 1;
            onControllerDirection(0, dy, steps);
            postDelayed(this, 28L);
        }
    };
    private Apps.Entry draggingEntry;
    private View draggingCell;
    private ImageView dragGhost;
    private boolean dragOverFavorites;

    AppDrawerOverlayView(Context c, Listener listener) {
        super(c);
        this.context = c;
        this.listener = listener;
        this.prefs = new Prefs(c);
        ThemeColors.initialize(c);
        this.store = new Store(c, prefs);
        store.load();
        this.iconPack = IconPack.resolver(c, prefs.iconPack(), Ui.dp(c, 96));
        allApps.addAll(builtinEntries(Ui.dp(c, 56)));
        allApps.addAll(Apps.query(c));
        Apps.loadIcons(c, allApps, Ui.dp(c, 56));
        loadFavorites();

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        edgePad = Ui.dp(c, 10);
        columns = Math.max(1, Math.min(2, prefs.drawerOverlayColumns()));
        recomputeWidths();

        setFocusableInTouchMode(true);
        setClickable(true);
        setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    dismissAnimated();
                    return true;
                }
                return false;
            }
        });

        View outside = new View(c);
        // The drawer card is the only glass surface; the rest of the screen
        // stays sharp. This view still catches outside taps to dismiss.
        outside.setBackgroundColor(0x00000000);
        outside.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissAnimated();
            }
        });
        addView(outside, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cardFrame = new RoundedClipFrameLayout(c);
        ((RoundedClipFrameLayout) cardFrame).setRadiusDp(26);
        cardFrame.setClickable(true);
        cardFrame.setClipChildren(true);

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6), 0);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLongClickable(true);
        View.OnLongClickListener styleLongPress = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Haptics.longPress(v);
                showStyleOverlay();
                return true;
            }
        };
        header.setOnLongClickListener(styleLongPress);

        TextView title = new LTextView(c);
        title.setText("APPS");
        title.setTextColor(accent());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setLetterSpacing(0.16f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLongClickable(true);
        title.setOnLongClickListener(styleLongPress);
        title.setContentDescription("Hold for drawer style controls");
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView searchButton = new ImageView(c);
        searchButton.setImageResource(R.drawable.ic_search);
        searchButton.setColorFilter(0xCCFFFFFF);
        searchButton.setPadding(Ui.dp(c, 5), Ui.dp(c, 5),
                Ui.dp(c, 5), Ui.dp(c, 5));
        searchButton.setContentDescription("Search apps");
        searchButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                openSearchField();
            }
        });
        searchButton.setOnLongClickListener(styleLongPress);
        LinearLayout.LayoutParams searchButtonLp = new LinearLayout.LayoutParams(
                Ui.dp(c, 32), Ui.dp(c, 32));
        searchButtonLp.rightMargin = Ui.dp(c, 4);
        header.addView(searchButton, searchButtonLp);

        TextView styleGrip = new LTextView(c);
        styleGrip.setText("\u283F");
        styleGrip.setTextColor(0x88FFFFFF);
        styleGrip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        styleGrip.setGravity(Gravity.CENTER);
        styleGrip.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        styleGrip.setContentDescription("Drawer style: transparency and size");
        styleGrip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                showStyleOverlay();
            }
        });
        styleGrip.setOnLongClickListener(styleLongPress);
        header.addView(styleGrip);

        columnsButton = new LTextView(c);
        columnsButton.setTextColor(0xE6FFFFFF);
        columnsButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        columnsButton.setGravity(Gravity.CENTER);
        columnsButton.setPadding(Ui.dp(c, 8), Ui.dp(c, 5),
                Ui.dp(c, 8), Ui.dp(c, 5));
        columnsButton.setBackground(Ui.rounded(0x22FFFFFF, Ui.dpf(c, 10),
                0x33FFFFFF, Ui.dpf(c, 1)));
        columnsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                toggleColumns();
            }
        });
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.rightMargin = Ui.dp(c, 6);
        header.addView(columnsButton, colLp);

        TextView close = new LTextView(c);
        close.setText("\u00d7");
        close.setTextColor(0xCCFFFFFF);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setContentDescription("Close app drawer");
        close.setPadding(Ui.dp(c, 7), 0, Ui.dp(c, 7), 0);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dismissAnimated();
            }
        });
        header.addView(close);
        title.setVisibility(View.GONE);
        styleGrip.setVisibility(View.GONE);
        columnsButton.setVisibility(View.GONE);
        close.setVisibility(View.GONE);
        header.setGravity(Gravity.CENTER);
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(c);
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0x80FFFFFF);
        search.setHint(Loc.t(getContext(), "Search apps"));
        search.setBackground(c.getDrawable(R.drawable.bg_search));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawablePadding(Ui.dp(c, 8));
        search.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 8), 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                dismissKeyboard();
                return true;
            }
        });
        search.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!search.hasFocus() && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    listener.onSearchFocusRequested(true);
                    return true;
                }
                return false;
            }
        });
        search.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    showKeyboard();
                } else if (!closing) {
                    listener.onSearchFocusRequested(false);
                    if (search.getText().length() == 0) {
                        search.setVisibility(View.GONE);
                        if (searchOpen) {
                            searchOpen = false;
                            applyCardWidth(true);
                        }
                    }
                }
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s == null ? "" : s.toString();
                filter(query);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 40));
        searchLp.topMargin = Ui.dp(c, 6);
        search.setVisibility(View.GONE);
        panel.addView(search, searchLp);

        favoritesLabel = new LTextView(c);
        favoritesLabel.setText("FAVORITES");
        favoritesLabel.setTextColor(0x99FFFFFF);
        favoritesLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        favoritesLabel.setLetterSpacing(0.12f);
        favoritesLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        favoritesLabel.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2));
        favoritesLabel.setVisibility(View.GONE);
        panel.addView(favoritesLabel);

        favoritesBox = new LinearLayout(c);
        favoritesBox.setOrientation(LinearLayout.VERTICAL);
        panel.addView(favoritesBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        allAppsLabel = new LTextView(c);
        allAppsLabel.setText("ALL APPS");
        allAppsLabel.setTextColor(0x99FFFFFF);
        allAppsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        allAppsLabel.setLetterSpacing(0.12f);
        allAppsLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        allAppsLabel.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 2));
        allAppsLabel.setVisibility(View.GONE);
        panel.addView(allAppsLabel);

        list = new ListView(c);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(true);
        list.setClipChildren(true);
        list.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 12));
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Same captured-backdrop material as the app cards and the app picker.
        glass = new GlassPanel(c, null, accent(), 0x8C0B0E14, 26, true);
        glass.setBackdropScreenAnchored(true);
        glass.setClearGlass(true);
        glass.setClearBodyAlpha(0);
        glass.setFrosted(true);
        glass.setSurfaceAlpha(prefs.drawerOverlayAlpha() / 100f);
        glass.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cardFrame.addView(glass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new LTextView(c);
        emptyView.setText("No apps found");
        emptyView.setTextColor(0x80FFFFFF);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        emptyView.setVisibility(View.GONE);
        cardFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        buildStyleOverlay();
        buildAppMenuOverlay();

        int cardW = cardWidth();
        int cardH = screenH - edgePad * 2;
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardW, cardH);
        cardLp.leftMargin = edgePad;
        cardLp.topMargin = edgePad;
        addView(cardFrame, cardLp);

        updateColumnsButton();
        updateMetrics();
        cardFrame.setTranslationX(-(cardW + Ui.dp(c, 30)));
        cardFrame.animate()
                .translationX(0f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator(2.0f))
                .start();

        post(new Runnable() {
            @Override
            public void run() {
                requestFocus();
            }
        });
    }

    /**
     * Supply a card-only blurred sample of the screen. The service captures this
     * before adding the overlay, so the card does not blur the whole window.
     */
    void setScreenBackdrop(Backdrop backdrop) {
        if (glass == null) return;
        glass.setBackdrop(backdrop);
        if (backdrop != null && backdrop.bitmap != null) {
            glass.setFrosted(false);
            glass.setLiquidEffect(true);
        }
        if (stylePanel instanceof GlassPanel) {
            ((GlassPanel) stylePanel).setBackdrop(backdrop);
        }
        if (appMenuPanel instanceof GlassPanel) {
            ((GlassPanel) appMenuPanel).setBackdrop(backdrop);
        }
        glass.invalidate();
    }

    // ------------------------------------------------------------------
    // Size and preferences
    // ------------------------------------------------------------------

    private int cardWidth() {
        int strip = Math.max(Ui.dp(context, 56),
                Ui.dp(context, prefs.drawerOverlayIconDp()) + Ui.dp(context, 28));
        if (searchOpen) return Math.max(strip, Ui.dp(context, 230));
        // The two-column drawer has to widen, otherwise both cells are squeezed
        // into one strip and the second column is clipped away.
        recomputeWidths();
        return columns == 2 ? twoColW : oneColW;
    }

    private void recomputeWidths() {
        int strip = Math.max(Ui.dp(context, 56),
                Ui.dp(context, prefs.drawerOverlayIconDp()) + Ui.dp(context, 28));
        int max = screenW - edgePad * 2;
        oneColW = Math.min(max, strip);
        // Two columns must actually be twice as wide, otherwise both cells are
        // squeezed into a single strip and the second one is clipped.
        twoColW = Math.min(max, oneColW * 2 + Ui.dp(context, 6));
    }

    private void updateColumnsButton() {
        columnsButton.setText(columns == 1 ? "1 COL" : "2 COL");
        columnsButton.setContentDescription(columns == 1
                ? "Show two apps per row" : "Show one app per row");
    }

    private void toggleColumns() {
        columns = columns == 1 ? 2 : 1;
        prefs.setDrawerOverlayColumns(columns);
        updateColumnsButton();
        applyCardWidth(true);
        updateMetrics();
        if (adapter != null) adapter.notifyDataSetChanged();
        list.postDelayed(new Runnable() {
            @Override public void run() { list.requestLayout(); }
        }, 200L);
    }

    private void applyCardWidth(boolean animate) {
        FrameLayout.LayoutParams lp = cardFrame.getLayoutParams()
                instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) cardFrame.getLayoutParams() : null;
        if (lp == null) return;
        int to = cardWidth();
        if (!animate) {
            lp.width = to;
            cardFrame.setLayoutParams(lp);
            return;
        }
        int from = cardFrame.getWidth() > 0 ? cardFrame.getWidth() : lp.width;
        if (widthAnim != null) widthAnim.cancel();
        widthAnim = ValueAnimator.ofInt(from, to);
        widthAnim.setDuration(180);
        widthAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        widthAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                lp.width = (Integer) animation.getAnimatedValue();
                cardFrame.setLayoutParams(lp);
            }
        });
        widthAnim.start();
    }

    /** Recompute icon and search sizes from the current card width, then reflow rows. */
    private void updateMetrics() {
        int w = cardWidth();
        iconPx = Ui.dp(context, prefs.drawerOverlayIconDp());
        int sh = Math.max(Ui.dp(context, 36),
                Math.min(Ui.dp(context, 52), Math.round(w * 0.09f)));
        ViewGroup.LayoutParams slp = search.getLayoutParams();
        if (slp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) slp;
            l.height = sh;
            search.setLayoutParams(l);
        }
        float density = getResources().getDisplayMetrics().density;
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                Math.max(12f, Math.min(15f, sh / density * 0.30f)));

        rebuildFavorites();
        filter(query);
    }

    private void refreshContent() {
        rebuildFavorites();
        filter(query);
    }

    // ------------------------------------------------------------------
    // Favourites
    // ------------------------------------------------------------------

    private List<Apps.Entry> builtinEntries(int px) {
        List<Apps.Entry> out = new ArrayList<>();
        DeviceProfile profile = DeviceProfile.get(context);
        int accent = ColorTuning.colour(accent());
        Apps.Entry tuner = new Apps.Entry(Loc.s(context, "Performance Tuner"), PERF_TUNER_PKG, "");
        tuner.icon = Apps.performanceTunerIcon(px, accent);
        out.add(tuner);
        Apps.Entry logs = new Apps.Entry(Loc.s(context, "Perf Logs"), PERF_LOG_PKG, "");
        logs.icon = Apps.performanceLogsIcon(px, accent);
        out.add(logs);
        Apps.Entry overlay = new Apps.Entry(Loc.s(context, "Overlay & Drawer"),
                OVERLAY_PKG, "");
        overlay.icon = Apps.overlayIcon(px, accent);
        out.add(overlay);
        Apps.Entry display = new Apps.Entry(Loc.s(context, "Display"), DISPLAY_PKG, "");
        display.icon = Apps.displayIcon(px, accent);
        out.add(display);
        Apps.Entry controller = new Apps.Entry(Loc.s(context, "Controller Remapper"), CONTROLLER_PKG, "");
        controller.icon = Apps.controllerMapIcon(px, accent);
        out.add(controller);
        if (profile.rgb) {
            Apps.Entry rgb = new Apps.Entry(Loc.s(context, "RGB Lighting"), RGB_PKG, "");
            rgb.icon = Apps.rgbIcon(px, accent);
            out.add(rgb);
        }
        if (profile.fanControl) {
            Apps.Entry fan = new Apps.Entry(Loc.s(context, "Fan Control"), FAN_PKG, "");
            fan.icon = Apps.fanIcon(px, accent);
            out.add(fan);
        }
        Apps.Entry touchMap = new Apps.Entry(Loc.s(context, "Touch Mapper"), TOUCH_MAP_PKG, "");
        touchMap.icon = Apps.touchMapperIcon(px, accent);
        out.add(touchMap);
        Apps.Entry health = new Apps.Entry(Loc.s(context, "Device Health"), DEVICE_HEALTH_PKG, "");
        health.icon = Apps.deviceHealthIcon(px, accent);
        out.add(health);
        Apps.Entry benchmark = new Apps.Entry(Loc.s(context, "Stress Test"), BENCHMARK_PKG, "");
        benchmark.icon = Apps.benchmarkIcon(px, accent);
        out.add(benchmark);
        Apps.Entry files = new Apps.Entry(Loc.s(context, "File Manager"), FILE_MANAGER_PKG, "");
        files.icon = Apps.fileManagerIcon(px, accent);
        out.add(files);
        Apps.Entry recorder = new Apps.Entry(Loc.s(context, "Screen Recorder"),
                SCREEN_RECORDER_PKG, "");
        recorder.icon = Apps.screenRecorderIcon(px, accent);
        out.add(recorder);
        return out;
    }

    private static boolean isBuiltinPkg(String pkg) {
        return PERF_TUNER_PKG.equals(pkg) || PERF_LOG_PKG.equals(pkg)
                || DISPLAY_PKG.equals(pkg) || CONTROLLER_PKG.equals(pkg)
                || RGB_PKG.equals(pkg) || FAN_PKG.equals(pkg)
                || OVERLAY_PKG.equals(pkg)
                || TOUCH_MAP_PKG.equals(pkg) || DEVICE_HEALTH_PKG.equals(pkg)
                || BENCHMARK_PKG.equals(pkg) || FILE_MANAGER_PKG.equals(pkg)
                || SCREEN_RECORDER_PKG.equals(pkg);
    }

    private void launchEntry(final Apps.Entry e) {
        if (e == null) return;
        Haptics.launch(this);
        Intent intent = null;
        if (PERF_TUNER_PKG.equals(e.pkg)) {
            intent = new Intent(context, PerformanceTunerActivity.class);
        } else if (PERF_LOG_PKG.equals(e.pkg)) {
            intent = new Intent(context, PerfLogActivity.class);
        } else if (DISPLAY_PKG.equals(e.pkg)) {
            intent = new Intent(context, DisplayActivity.class);
        } else if (CONTROLLER_PKG.equals(e.pkg)) {
            intent = new Intent(context, ControllerMapActivity.class);
        } else if (RGB_PKG.equals(e.pkg)) {
            intent = new Intent(context, RgbActivity.class);
        } else if (OVERLAY_PKG.equals(e.pkg)) {
            intent = new Intent(context, OverlayActivity.class);
        } else if (FAN_PKG.equals(e.pkg)) {
            intent = new Intent(context, FanActivity.class);
        } else if (TOUCH_MAP_PKG.equals(e.pkg)) {
            intent = new Intent(context, TouchMapperActivity.class);
        } else if (DEVICE_HEALTH_PKG.equals(e.pkg)) {
            intent = new Intent(context, DeviceHealthActivity.class);
        } else if (BENCHMARK_PKG.equals(e.pkg)) {
            intent = new Intent(context, BenchmarkActivity.class);
        } else if (FILE_MANAGER_PKG.equals(e.pkg)) {
            intent = new Intent(context, FileManagerActivity.class);
        } else if (SCREEN_RECORDER_PKG.equals(e.pkg)) {
            intent = new Intent(context, ScreenRecorderActivity.class);
        }
        // Remove the floating drawer before the new activity takes focus. If we
        // rely on the closing animation while the window is being covered, its
        // end callback can be missed and leave a translucent overlay on top of
        // the launched app.
        dismissForLaunch();
        if (intent != null) {
            final Intent builtInIntent = intent;
            builtInIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // The side drawer lives above another app. Let it disappear first,
            // then sample that app so the built-in card gets the same frosted
            // backdrop it gets when launched from the home screen.
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Backdrop backdrop = ScreenCaptureBackdrop.captureSharp(context);
                    if (backdrop == null) backdrop = WallpaperBackdrop.captureSharp(context);
                    if (backdrop != null) BuiltInBackdrop.put(backdrop);
                    try {
                        context.startActivity(builtInIntent);
                    } catch (Throwable t) {
                        LToast.makeText(context, "Can't open " + e.label,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            }, 90L);
        } else {
            Apps.launch(context, e);
        }
    }

    private void dismissForLaunch() {
        closing = true;
        dismissKeyboard();
        listener.onDismiss();
    }

    private static String keyOf(Apps.Entry e) {
        return e.pkg + "/" + (e.activity == null ? "" : e.activity);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!packageReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            try {
                context.registerReceiver(packageReceiver, filter);
                packageReceiverRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (packageReceiverRegistered) {
            try {
                context.unregisterReceiver(packageReceiver);
            } catch (Throwable ignored) {
            }
            packageReceiverRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private void refreshApps() {
        allApps.clear();
        allApps.addAll(builtinEntries(Ui.dp(context, 56)));
        allApps.addAll(Apps.query(context));
        Apps.loadIcons(context, allApps, Ui.dp(context, 56));
        loadFavorites();
        refreshContent();
    }

    private void loadFavorites() {
        favorites.clear();
        String raw = prefs.drawerFavorites();
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\\n")) {
            if (line.isEmpty() || favorites.size() >= FAVORITE_SLOTS) continue;
            Apps.Entry match = findEntryByKey(line);
            if (match == null) continue;
            boolean exists = false;
            for (Apps.Entry e : favorites) {
                if (keyOf(e).equals(keyOf(match))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) favorites.add(match);
        }
    }

    private Apps.Entry findEntryByKey(String key) {
        for (Apps.Entry e : allApps) {
            if (keyOf(e).equals(key)) return e;
        }
        return null;
    }

    private boolean isFavorite(Apps.Entry e) {
        String key = keyOf(e);
        for (Apps.Entry f : favorites) {
            if (keyOf(f).equals(key)) return true;
        }
        return false;
    }

    private void saveFavorites() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < favorites.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(keyOf(favorites.get(i)));
        }
        prefs.setDrawerFavorites(sb.toString());
    }

    private void toggleFavorite(Apps.Entry e) {
        String key = keyOf(e);
        for (int i = 0; i < favorites.size(); i++) {
            if (keyOf(favorites.get(i)).equals(key)) {
                favorites.remove(i);
                saveFavorites();
                refreshContent();
                return;
            }
        }
        if (favorites.size() >= FAVORITE_SLOTS) {
            LToast.makeText(context, "Favorites are limited to four apps",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        favorites.add(e);
        saveFavorites();
        refreshContent();
    }

    private void rebuildFavorites() {
        if (favoritesBox == null) return;
        favoritesBox.removeAllViews();
        if (favorites.isEmpty()) {
            TextView hint = new LTextView(context);
            hint.setText("\u2606");
            hint.setTextColor(0x55FFFFFF);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hint.setGravity(Gravity.CENTER);
            hint.setContentDescription("Drop an app here to add a favorite");
            hint.setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 4));
            favoritesBox.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 28)));
            return;
        }
        for (int start = 0; start < favorites.size(); start += Math.max(1, columns)) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBaselineAligned(false);
            for (int c = 0; c < columns; c++) {
                int index = start + c;
                if (index < favorites.size()) {
                    View cell = makeAppRow(favorites.get(index));
                    row.addView(cell, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    row.addView(new Space(context), new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
            }
            favoritesBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void filter(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.getDefault());
        boolean searching = !needle.isEmpty();
        shownApps.clear();
        for (Apps.Entry e : allApps) {
            String label = store.labelFor(Item.app(e.pkg, e.activity), e.label);
            boolean match = needle.isEmpty()
                    || label.toLowerCase(Locale.getDefault()).contains(needle)
                    || e.pkg.toLowerCase(Locale.getDefault()).contains(needle);
            if (!match) continue;
            if (!searching && isFavorite(e)) continue;
            shownApps.add(e);
        }
        favoritesLabel.setVisibility(View.GONE);
        favoritesBox.setVisibility(searching ? View.GONE : View.VISIBLE);
        allAppsLabel.setVisibility(View.GONE);
        boolean noResults = shownApps.isEmpty()
                && (searching || favorites.isEmpty());
        emptyView.setVisibility(noResults ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        if (controllerSelection) {
            List<Apps.Entry> entries = controllerEntries();
            if (entries.isEmpty()) {
                selectedKey = null;
            } else if (indexOfKey(entries, selectedKey) < 0) {
                selectedKey = keyOf(entries.get(0));
                rebuildFavorites();
                adapter.notifyDataSetChanged();
            }
        }
    }

    // ------------------------------------------------------------------
    // Long-press style controls
    // ------------------------------------------------------------------

    private void buildStyleOverlay() {
        styleOverlay = new FrameLayout(context);
        styleOverlay.setVisibility(View.GONE);
        View scrim = new View(context);
        scrim.setBackgroundColor(0x4D000000);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                styleOverlay.setVisibility(View.GONE);
            }
        });
        styleOverlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GlassPanel styleGlass = new GlassPanel(context, null, accent(),
                0x330B0E14, 18, true);
        styleGlass.setFrosted(true);
        styleGlass.setBackdropScreenAnchored(true);
        styleGlass.setSurfaceAlpha(prefs.drawerOverlayAlpha() / 100f);
        stylePanel = styleGlass;
        stylePanel.setOrientation(LinearLayout.VERTICAL);
        stylePanel.setPadding(Ui.dp(context, 16), Ui.dp(context, 14),
                Ui.dp(context, 16), Ui.dp(context, 14));
        stylePanel.setClickable(true);
        stylePanel.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
                float x = event.getRawX();
                float y = event.getRawY();
                if (hit(alphaMinus, x, y)) { Haptics.tick(v); adjustAlpha(-5); }
                else if (hit(alphaPlus, x, y)) { Haptics.tick(v); adjustAlpha(5); }
                else if (hit(iconMinus, x, y)) { Haptics.tick(v); adjustIconSize(-2); }
                else if (hit(iconPlus, x, y)) { Haptics.tick(v); adjustIconSize(2); }
                else if (hit(styleDone, x, y)) { Haptics.click(v); styleOverlay.setVisibility(View.GONE); }
                return true;
            }
        });

        TextView title = new LTextView(context);
        styleTitle = title;
        title.setText("DRAWER STYLE  \u283F");
        title.setTextColor(accent());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 5));
        title.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (stylePanelLp == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        styleDragDownX = event.getRawX();
                        styleDragDownY = event.getRawY();
                        styleDragStartLeft = stylePanelLp.leftMargin;
                        styleDragStartTop = stylePanelLp.topMargin;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int left = Math.round(styleDragStartLeft
                                + event.getRawX() - styleDragDownX);
                        int top = Math.round(styleDragStartTop
                                + event.getRawY() - styleDragDownY);
                        left = Math.max(Ui.dp(context, 4),
                                Math.min(left, screenW - stylePanel.getWidth()
                                        - Ui.dp(context, 4)));
                        top = Math.max(Ui.dp(context, 4),
                                Math.min(top, screenH - stylePanel.getHeight()
                                        - Ui.dp(context, 4)));
                        stylePanelLp.leftMargin = left;
                        stylePanelLp.topMargin = top;
                        stylePanel.setLayoutParams(stylePanelLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        Haptics.tick(stylePanel);
                        return true;
                    default:
                        return true;
                }
            }
        });
        stylePanel.addView(title);

        TextView hint = new LTextView(context);
        hint.setText("Long-press the search icon for this menu.");
        hint.setTextColor(0x77FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hint.setPadding(0, Ui.dp(context, 3), 0, Ui.dp(context, 8));
        stylePanel.addView(hint);

        stylePanel.addView(styleRow("Transparency", 0),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stylePanel.addView(styleRow("Icon size", 1),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView done = new LTextView(context);
        styleDone = done;
        done.setText("Done");
        done.setTextColor(0xFF061015);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, Ui.dp(context, 9), 0, Ui.dp(context, 9));
        done.setBackground(Ui.rounded(accent(), Ui.dpf(context, 14)));
        done.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                styleOverlay.setVisibility(View.GONE);
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(context, 12);
        stylePanel.addView(done, dlp);

        int panelW = Math.min(screenW - Ui.dp(context, 24), Ui.dp(context, 280));
        stylePanelLp = new FrameLayout.LayoutParams(
                panelW, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        stylePanelLp.leftMargin = Ui.dp(context, 12);
        stylePanelLp.topMargin = Ui.dp(context, 64);
        styleOverlay.addView(stylePanel, stylePanelLp);
        styleNavItems.clear();
        if (alphaMinus != null) styleNavItems.add(alphaMinus);
        if (alphaPlus != null) styleNavItems.add(alphaPlus);
        if (iconMinus != null) styleNavItems.add(iconMinus);
        if (iconPlus != null) styleNavItems.add(iconPlus);
        if (styleDone != null) styleNavItems.add(styleDone);
        styleNavRing = new FocusRingDrawable(context, ColorTuning.colour(accent()));
        stylePanel.getOverlay().add(styleNavRing);
        // The style controls belong outside the sidebar card.
        addView(styleOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static boolean hit(View v, float rawX, float rawY) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
    }

    private LinearLayout styleRow(String label, int kind) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 5));

        TextView name = new LTextView(context);
        name.setText(label);
        name.setTextColor(0xE6FFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView minus = stepButton("\u2212");
        TextView value = new LTextView(context);
        value.setTextColor(accent());
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(Ui.dp(context, 46));
        TextView plus = stepButton("+");
        if (kind == 0) {
            alphaValue = value;
            alphaMinus = minus;
            alphaPlus = plus;
        } else {
            iconValue = value;
            iconMinus = minus;
            iconPlus = plus;
        }

        minus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                if (kind == 0) adjustAlpha(-5);
                else adjustIconSize(-2);
            }
        });
        plus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.tick(v);
                if (kind == 0) adjustAlpha(5);
                else adjustIconSize(2);
            }
        });
        row.addView(minus);
        row.addView(value);
        row.addView(plus);
        return row;
    }

    private TextView stepButton(String textValue) {
        TextView t = new LTextView(context);
        t.setText(textValue);
        t.setTextColor(0xF2FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(context, 30));
        t.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0);
        t.setBackground(Ui.rounded(0x22FFFFFF, Ui.dpf(context, 9),
                0x33FFFFFF, Ui.dpf(context, 1)));
        return t;
    }

    void showStyleOverlay() {
        if (closing) return;
        if (appMenuOverlay != null) appMenuOverlay.setVisibility(View.GONE);
        styleNavIndex = -1;
        if (styleNavRing != null) {
            styleNavRing.setBounds(0, 0, 0, 0);
            styleNavRing.invalidateSelf();
        }
        updateStyleLabels();
        if (stylePanelLp != null && cardFrame != null) {
            int x = cardFrame.getRight() + Ui.dp(context, 12);
            x = Math.min(x, screenW - stylePanelLp.width - Ui.dp(context, 12));
            stylePanelLp.leftMargin = Math.max(Ui.dp(context, 12), x);
            stylePanel.setLayoutParams(stylePanelLp);
        }
        styleOverlay.setVisibility(View.VISIBLE);
        styleOverlay.bringToFront();
    }

    boolean isStyleVisible() {
        return styleOverlay != null && styleOverlay.getVisibility() == View.VISIBLE;
    }

    void hideStyleOverlay() {
        styleNavIndex = -1;
        if (styleNavRing != null) {
            styleNavRing.setBounds(0, 0, 0, 0);
            styleNavRing.invalidateSelf();
        }
        if (styleOverlay != null) styleOverlay.setVisibility(View.GONE);
    }

    private void updateStyleLabels() {
        if (alphaValue != null) alphaValue.setText(prefs.drawerOverlayAlpha() + "%");
        if (iconValue != null) iconValue.setText(prefs.drawerOverlayIconDp() + " dp");
    }

    private void adjustAlpha(int delta) {
        int value = Math.max(20, Math.min(100, prefs.drawerOverlayAlpha() + delta));
        prefs.setDrawerOverlayAlpha(value);
        glass.setSurfaceAlpha(value / 100f);
        if (stylePanel instanceof GlassPanel) {
            ((GlassPanel) stylePanel).setSurfaceAlpha(value / 100f);
        }
        if (alphaValue != null) alphaValue.setText(value + "%");
    }

    private void adjustIconSize(int delta) {
        int value = Math.max(34, Math.min(76, prefs.drawerOverlayIconDp() + delta));
        prefs.setDrawerOverlayIconDp(value);
        recomputeWidths();
        applyCardWidth(true);
        updateMetrics();
        if (iconValue != null) iconValue.setText(value + " dp");
        if (searchOpen) applyCardWidth(true);
    }

    // ------------------------------------------------------------------
    // App long-press menu
    // ------------------------------------------------------------------

    private void buildAppMenuOverlay() {
        appMenuOverlay = new FrameLayout(context);
        appMenuOverlay.setVisibility(View.GONE);
        View scrim = new View(context);
        scrim.setBackgroundColor(0x4D000000);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                appMenuOverlay.setVisibility(View.GONE);
            }
        });
        appMenuOverlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GlassPanel appMenuGlass = new GlassPanel(context, null,
                ColorTuning.colour(accent()), 0x330B0E14, 18, true);
        appMenuGlass.setClearGlass(true);
        appMenuGlass.setClearBodyAlpha(0);
        appMenuGlass.setPadding(Ui.dp(context, 10), Ui.dp(context, 10),
                Ui.dp(context, 10), Ui.dp(context, 10));
        appMenuGlass.setClickable(true);
        appMenuPanel = appMenuGlass;
        appMenuOverlay.addView(appMenuPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        appMenuNavRing = new FocusRingDrawable(context, ColorTuning.colour(accent()));
        appMenuPanel.getOverlay().add(appMenuNavRing);
        cardFrame.addView(appMenuOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showAppMenu(final Apps.Entry e) {
        styleOverlay.setVisibility(View.GONE);
        appMenuPanel.removeAllViews();
        appMenuNavItems.clear();
        appMenuNavIndex = -1;
        if (appMenuNavRing != null) {
            appMenuNavRing.setBounds(0, 0, 0, 0);
            appMenuNavRing.invalidateSelf();
        }
        TextView title = new TextView(context);
        title.setText(store.labelFor(Item.app(e.pkg, e.activity), e.label));
        title.setTextColor(0x99FFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        title.setLetterSpacing(0.08f);
        title.setPadding(Ui.dp(context, 8), Ui.dp(context, 3),
                Ui.dp(context, 8), Ui.dp(context, 7));
        appMenuPanel.addView(title);

        final boolean favorite = isFavorite(e);
        addAppMenuOption(favorite ? "Remove from favorites" : "Add to favorites",
                new Runnable() {
                    @Override
                    public void run() {
                        appMenuOverlay.setVisibility(View.GONE);
                        toggleFavorite(e);
                    }
                }, false);
        addAppMenuOption("Uninstall", new Runnable() {
            @Override
            public void run() {
                appMenuOverlay.setVisibility(View.GONE);
                uninstall(e);
            }
        }, true);
        addAppMenuOption("App info", new Runnable() {
            @Override
            public void run() {
                appMenuOverlay.setVisibility(View.GONE);
                Apps.appInfo(context, e);
                dismissAnimated();
            }
        }, false);
        addAppMenuOption("Cancel", new Runnable() {
            @Override
            public void run() {
                appMenuOverlay.setVisibility(View.GONE);
            }
        }, false);
        appMenuOverlay.setVisibility(View.VISIBLE);
        appMenuOverlay.bringToFront();
    }

    private void addAppMenuOption(String label, final Runnable action, boolean danger) {
        TextView row = new LTextView(context);
        row.setText(label);
        row.setTextColor(danger ? 0xFFFF8A80 : 0xFFEEF1F6);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.setPadding(Ui.dp(context, 12), Ui.dp(context, 10),
                Ui.dp(context, 12), Ui.dp(context, 10));
        row.setBackground(Ui.ripple(context, Ui.dpf(context, 11)));
        row.setClickable(true);
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(row);
                action.run();
            }
        });
        appMenuPanel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appMenuNavItems.add(row);
    }

    private void uninstall(final Apps.Entry e) {
        try {
            Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    Uri.fromParts("package", e.pkg, null));
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            dismissAnimated();
        } catch (Throwable t) {
            LToast.makeText(context, "Can't uninstall", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------
    // Sliding / focus
    // ------------------------------------------------------------------

    /** Animate the sidebar back out to the left, then let the service remove the window. */
    void dismissAnimated() {
        if (closing) return;
        closing = true;
        Haptics.key(this);
        dismissKeyboard();
        int target = -(cardFrame.getWidth() + Ui.dp(context, 30));
        cardFrame.animate()
                .translationX(target)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDismiss();
                    }
                })
                .start();
    }

    private void openSearchField() {
        searchOpen = true;
        applyCardWidth(true);
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        listener.onSearchFocusRequested(true);
        showKeyboard();
    }

    void focusSearchField() {
        if (closing) return;
        searchOpen = true;
        applyCardWidth(true);
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        showKeyboard();
    }

    void dismissKeyboard() {
        if (search == null) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        if (search.getText().length() == 0) {
            search.setVisibility(View.GONE);
            if (searchOpen) {
                searchOpen = false;
                applyCardWidth(true);
            }
        }
        requestFocus();
    }

    private void showKeyboard() {
        search.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) context
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        }, 100);
    }

    // ------------------------------------------------------------------
    // Controller navigation
    // ------------------------------------------------------------------

    /**
     * The drawer is the focused window while it is open, so controller keys arrive
     * here directly. This is what keeps D-pad/A/B working when Touch Mapper has
     * released the controller and its raw-key bridge is no longer running.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (search != null && search.hasFocus()) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (mapperMode && key == KeyEvent.KEYCODE_C) {
            handleDrawerShortcutKey(event);
            return true;
        }
        int dx = 0;
        int dy = 0;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            default: break;
        }
        if (dx != 0 || dy != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int steps = event.getRepeatCount() == 0 ? 1
                        : Math.min(5, 1 + event.getRepeatCount() / 2);
                onControllerDirection(dx, dy, steps);
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_A
                || key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        onControllerSelect();
                    }
                });
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        onControllerBack();
                    }
                });
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (search != null && search.hasFocus()) return super.dispatchGenericMotionEvent(event);
        if (rawControllerReady()) return super.dispatchGenericMotionEvent(event);
        if (!mapperMode || event == null
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_JOYSTICK) == 0
                && (source & InputDevice.SOURCE_GAMEPAD) == 0) {
            return super.dispatchGenericMotionEvent(event);
        }
        float sx = event.getAxisValue(MotionEvent.AXIS_X);
        float sy = event.getAxisValue(MotionEvent.AXIS_Y);
        float right = StickScroll.rightY(event);
        if (Math.abs(right) < 0.12f) right = event.getAxisValue(MotionEvent.AXIS_Z);
        updateNavStick(sx, sy);
        updateNavFast(right);
        return true;
    }

    void setMapperMode(boolean enabled) {
        mapperMode = enabled;
    }

    void setShortcutGrace(long millis) {
        ignoreShortcutUntil = android.os.SystemClock.uptimeMillis() + millis;
    }

    private boolean rawControllerReady() {
        return mapperMode && AuroraInputService.isReady()
                && ControllerRemapManager.isRunning(context);
    }

    private void handleDrawerShortcutKey(KeyEvent event) {
        if (android.os.SystemClock.uptimeMillis() < ignoreShortcutUntil) return;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                drawerShortcutDown = true;
                drawerShortcutLongFired = false;
                removeCallbacks(drawerShortcutLongPress);
                postDelayed(drawerShortcutLongPress,
                        ViewConfiguration.getLongPressTimeout());
            }
            return;
        }
        if (action == KeyEvent.ACTION_UP) {
            removeCallbacks(drawerShortcutLongPress);
            boolean shortTap = drawerShortcutDown && !drawerShortcutLongFired;
            drawerShortcutDown = false;
            drawerShortcutLongFired = false;
            if (!shortTap) return;
            if (isStyleVisible()) {
                Haptics.key(this);
                hideStyleOverlay();
            } else {
                dismissAnimated();
            }
            return;
        }
    }

    private void updateNavStick(float sx, float sy) {
        float ax = Math.abs(sx);
        float ay = Math.abs(sy);
        if (ax < 0.40f && ay < 0.40f) {
            navStickAxis = 0;
            navStickDir = -1;
            navStickHeld = -1;
            removeCallbacks(navStickRepeat);
            return;
        }
        if (navStickAxis == 0) {
            if (Math.max(ax, ay) < 0.62f) return;
            navStickAxis = ax >= ay ? 1 : 2;
        } else if (navStickAxis == 1) {
            if (ay > ax + 0.30f) navStickAxis = 2;
        } else if (ax > ay + 0.30f) {
            navStickAxis = 1;
        }
        float selected = navStickAxis == 1 ? ax : ay;
        if (selected < 0.58f) {
            navStickHeld = -1;
            navStickDir = -1;
            removeCallbacks(navStickRepeat);
            return;
        }
        int dir = navStickAxis == 1 ? (sx < 0 ? 0 : 1) : (sy < 0 ? 2 : 3);
        if (dir != navStickDir) {
            navStickDir = dir;
            noteNavHold(dir);
            int dx = dir == 0 ? -1 : dir == 1 ? 1 : 0;
            int dy = dir == 2 ? -1 : dir == 3 ? 1 : 0;
            onControllerDirection(dx, dy);
        }
        if (dir != navStickHeld) {
            navStickHeld = dir;
            removeCallbacks(navStickRepeat);
            postDelayed(navStickRepeat, 160L);
        }
    }

    private void updateNavFast(float right) {
        if (Math.abs(right) < 0.25f) {
            navFastDir = -1;
            removeCallbacks(navFastRepeat);
            return;
        }
        int dir = right < 0f ? 2 : 3;
        if (dir != navFastDir) {
            navFastDir = dir;
            noteNavHold(dir);
            onControllerDirection(0, dir == 2 ? -1 : 1);
            removeCallbacks(navFastRepeat);
            postDelayed(navFastRepeat, 70L);
        }
    }

    private void noteNavHold(int dir) {
        if (navHoldDir != dir) {
            navHoldDir = dir;
            navHoldStartedAt = android.os.SystemClock.uptimeMillis();
        }
    }

    private int navRepeatSteps() {
        long held = android.os.SystemClock.uptimeMillis() - navHoldStartedAt;
        if (held < 250L) return 1;
        if (held < 700L) return 2;
        if (held < 1300L) return 3;
        return 5;
    }

    private long navRepeatDelay() {
        long held = android.os.SystemClock.uptimeMillis() - navHoldStartedAt;
        if (held < 250L) return 100L;
        if (held < 700L) return 60L;
        if (held < 1300L) return 35L;
        return 22L;
    }


    /** Keep a long held direction from flooding the haptic motor. */
    private void controllerTick() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastControllerHapticAt < 100L) return;
        lastControllerHapticAt = now;
        Haptics.tick(this);
    }

    private List<Apps.Entry> controllerEntries() {
        List<Apps.Entry> entries = new ArrayList<>();
        if (favoritesBox != null && favoritesBox.getVisibility() == View.VISIBLE) {
            entries.addAll(favorites);
        }
        entries.addAll(shownApps);
        return entries;
    }

    private static int indexOfKey(List<Apps.Entry> entries, String key) {
        if (key == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (key.equals(keyOf(entries.get(i)))) return i;
        }
        return -1;
    }

    private boolean appMenuVisible() {
        return appMenuOverlay != null && appMenuOverlay.getVisibility() == View.VISIBLE;
    }

    /** Start each new sidebar presentation with the first item selected. */
    void resetControllerFocus() {
        controllerSelection = false;
        selectedKey = null;
    }

    /** Prepare the list for D-pad navigation, selecting the first visible app. */
    void ensureControllerSelection() {
        if (closing) return;
        List<Apps.Entry> entries = controllerEntries();
        if (entries.isEmpty()) return;
        if (!controllerSelection || indexOfKey(entries, selectedKey) < 0) {
            selectedKey = keyOf(entries.get(0));
        }
        controllerSelection = true;
        refreshControllerSelection();
        Haptics.tick(this);
    }

    boolean isSubmenuVisible() {
        return isStyleVisible() || appMenuVisible();
    }

    /** Move one cell in the floating drawer; dx/dy are -1, 0 or 1. */
    boolean onControllerDirection(int dx, int dy) {
        return onControllerDirection(dx, dy, 1);
    }

    /**
     * Move through the drawer, optionally skipping several cells for a held
     * controller direction. Submenus always move one item at a time.
     */
    boolean onControllerDirection(int dx, int dy, int steps) {
        if (closing) return false;
        steps = Math.max(1, steps);
        if (isStyleVisible()) {
            if (dx != 0 || dy != 0) navigateStyle(dx != 0 ? dx : dy);
            return true;
        }
        if (appMenuVisible()) {
            if (dx != 0 || dy != 0) navigateAppMenu(dx != 0 ? dx : dy);
            return true;
        }
        List<Apps.Entry> entries = controllerEntries();
        if (entries.isEmpty()) return false;
        if (!controllerSelection) {
            // The first directional press only establishes the selection, so the
            // top app is not skipped.
            selectedKey = keyOf(entries.get(0));
            controllerSelection = true;
            refreshControllerSelection();
            return true;
        }
        int pos = indexOfKey(entries, selectedKey);
        if (pos < 0) pos = 0;
        int step = (dx != 0 ? dx : dy * Math.max(1, columns)) * steps;
        pos = Math.max(0, Math.min(entries.size() - 1, pos + step));
        selectedKey = keyOf(entries.get(pos));
        controllerSelection = true;
        refreshControllerSelection();
        controllerTick();
        int shownIndex = -1;
        for (int i = 0; i < shownApps.size(); i++) {
            if (keyOf(shownApps.get(i)).equals(selectedKey)) {
                shownIndex = i;
                break;
            }
        }
        if (shownIndex >= 0) {
            final int row = shownIndex / Math.max(1, columns);
            list.post(new Runnable() {
                @Override
                public void run() {
                    int first = list.getFirstVisiblePosition();
                    View child = list.getChildAt(row - first);
                    if (child == null) {
                        list.setSelectionFromTop(row, list.getPaddingTop());
                        return;
                    }
                    int safeTop = list.getPaddingTop();
                    int safeBottom = list.getHeight() - list.getPaddingBottom();
                    // Keep the whole row inside the list. Scrolling only when the
                    // row left the viewport left half a row clipped under the
                    // drawer's search/favourite header, so the ring looked cut.
                    if (child.getTop() < safeTop) {
                        list.setSelectionFromTop(row, safeTop);
                    } else if (child.getBottom() > safeBottom) {
                        list.setSelectionFromTop(row,
                                safeBottom - child.getHeight());
                    }
                }
            });
        } else {
            list.setSelection(0);
        }
        return true;
    }

    /** Activate the selected app (A button). */
    boolean onControllerSelect() {
        if (closing) return false;
        if (isStyleVisible()) {
            activateStyleNav();
            return true;
        }
        if (appMenuVisible()) {
            activateAppMenuNav();
            return true;
        }
        ensureControllerSelection();
        List<Apps.Entry> entries = controllerEntries();
        int pos = indexOfKey(entries, selectedKey);
        if (pos < 0) return false;
        Apps.Entry e = entries.get(pos);
        launchEntry(e);
        return true;
    }

    /** Back/B: close a submenu first, then the whole drawer. */
    boolean onControllerBack() {
        if (isStyleVisible()) {
            Haptics.key(this);
            hideStyleOverlay();
            return true;
        }
        if (appMenuVisible()) {
            Haptics.key(this);
            appMenuOverlay.setVisibility(View.GONE);
            return true;
        }
        dismissAnimated();
        return true;
    }

    private void navigateStyle(int step) {
        if (styleNavItems.isEmpty()) return;
        if (styleNavIndex < 0) styleNavIndex = 0;
        else styleNavIndex = (styleNavIndex + (step < 0 ? -1 : 1)
                + styleNavItems.size()) % styleNavItems.size();
        showStyleNavFocus();
        Haptics.tick(this);
    }

    private void activateStyleNav() {
        if (styleNavItems.isEmpty()) return;
        if (styleNavIndex < 0) styleNavIndex = 0;
        showStyleNavFocus();
        styleNavItems.get(styleNavIndex).performClick();
    }

    private void showStyleNavFocus() {
        if (styleNavRing == null || styleNavIndex < 0 || styleNavIndex >= styleNavItems.size()) {
            if (styleNavRing != null) styleNavRing.setBounds(0, 0, 0, 0);
            return;
        }
        final View target = styleNavItems.get(styleNavIndex);
        post(new Runnable() {
            @Override
            public void run() {
                positionFocusRing(styleNavRing, stylePanel, target);
            }
        });
        postDelayed(new Runnable() {
            @Override
            public void run() {
                positionFocusRing(styleNavRing, stylePanel, target);
            }
        }, 80);
    }

    private void navigateAppMenu(int step) {
        if (appMenuNavItems.isEmpty()) return;
        if (appMenuNavIndex < 0) appMenuNavIndex = 0;
        else appMenuNavIndex = (appMenuNavIndex + (step < 0 ? -1 : 1)
                + appMenuNavItems.size()) % appMenuNavItems.size();
        showAppMenuNavFocus();
        Haptics.tick(this);
    }

    private void activateAppMenuNav() {
        if (appMenuNavItems.isEmpty()) return;
        if (appMenuNavIndex < 0) appMenuNavIndex = 0;
        showAppMenuNavFocus();
        appMenuNavItems.get(appMenuNavIndex).performClick();
    }

    private void showAppMenuNavFocus() {
        if (appMenuNavRing == null || appMenuNavIndex < 0
                || appMenuNavIndex >= appMenuNavItems.size()) {
            if (appMenuNavRing != null) appMenuNavRing.setBounds(0, 0, 0, 0);
            return;
        }
        final View target = appMenuNavItems.get(appMenuNavIndex);
        post(new Runnable() {
            @Override
            public void run() {
                positionFocusRing(appMenuNavRing, appMenuPanel, target);
            }
        });
        postDelayed(new Runnable() {
            @Override
            public void run() {
                positionFocusRing(appMenuNavRing, appMenuPanel, target);
            }
        }, 80);
    }

    private void positionFocusRing(FocusRingDrawable ring, View root, View target) {
        if (ring == null || root == null || target == null || target.getWidth() <= 0
                || target.getHeight() <= 0) {
            if (ring != null) {
                ring.setBounds(0, 0, 0, 0);
                ring.invalidateSelf();
            }
            return;
        }
        int[] rootLoc = new int[2];
        int[] targetLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        target.getLocationOnScreen(targetLoc);
        int pad = Ui.dp(context, 2);
        int left = targetLoc[0] - rootLoc[0] - pad;
        int top = targetLoc[1] - rootLoc[1] - pad;
        ring.setBounds(left, top,
                left + target.getWidth() + pad * 2, top + target.getHeight() + pad * 2);
        ring.setRingRadius(FocusRingView.radiusFor(target, context));
        ring.invalidateSelf();
    }

    private void refreshControllerSelection() {
        rebuildFavorites();
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private Bitmap iconFor(Apps.Entry e) {
        if (isBuiltinPkg(e.pkg) && e.icon != null) return e.icon;
        Item item = Item.app(e.pkg, e.activity);
        Bitmap themed = store.iconFor(item);
        if (themed == null && iconPack != null) {
            themed = iconPack.iconFor(e.pkg, e.activity);
        }
        if (themed != null) return themed;
        if (e.icon != null) return e.icon;
        return Apps.letterTile(e.label, Math.max(Ui.dp(context, 24), iconPx));
    }

    private void beginAppDrag(Apps.Entry e, View cell) {
        if (closing || e == null) return;
        cancelAppDrag();
        draggingEntry = e;
        draggingCell = cell;
        dragOverFavorites = false;
        if (cell.getParent() != null) {
            cell.getParent().requestDisallowInterceptTouchEvent(true);
        }
        cell.setAlpha(0.3f);
        int size = Math.max(Ui.dp(context, 48), iconPx);
        dragGhost = new ImageView(context);
        dragGhost.setScaleType(ImageView.ScaleType.FIT_CENTER);
        dragGhost.setImageBitmap(iconFor(e));
        dragGhost.setAlpha(0.96f);
        dragGhost.setElevation(Ui.dpf(context, 20));
        addView(dragGhost, new FrameLayout.LayoutParams(size, size));
        dragGhost.bringToFront();
        int[] cl = new int[2];
        cell.getLocationOnScreen(cl);
        int[] rl = new int[2];
        getLocationOnScreen(rl);
        dragGhost.setX(cl[0] + cell.getWidth() / 2f - rl[0] - size / 2f);
        dragGhost.setY(cl[1] + cell.getHeight() / 2f - rl[1] - size / 2f);
        setFavoritesHighlight(false);
    }

    private void moveAppDrag(float rawX, float rawY) {
        if (dragGhost == null) return;
        int[] rl = new int[2];
        getLocationOnScreen(rl);
        dragGhost.setX(rawX - rl[0] - dragGhost.getWidth() / 2f);
        dragGhost.setY(rawY - rl[1] - dragGhost.getHeight() / 2f);
        boolean over = isOverFavorites(rawX, rawY);
        if (over != dragOverFavorites) {
            dragOverFavorites = over;
            setFavoritesHighlight(over);
            if (over) Haptics.hover(dragGhost);
        }
    }

    private void finishAppDrag(float rawX, float rawY) {
        Apps.Entry e = draggingEntry;
        boolean over = isOverFavorites(rawX, rawY);
        cancelAppDrag();
        if (!over || e == null || isFavorite(e)) return;
        if (favorites.size() >= FAVORITE_SLOTS) {
            LToast.makeText(context, "Favorites are limited to four apps",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        favorites.add(e);
        saveFavorites();
        Haptics.drop(this);
        refreshContent();
    }

    private void cancelAppDrag() {
        if (dragGhost != null) {
            if (dragGhost.getParent() instanceof ViewGroup) {
                ((ViewGroup) dragGhost.getParent()).removeView(dragGhost);
            }
            dragGhost = null;
        }
        if (draggingCell != null) draggingCell.setAlpha(1f);
        draggingCell = null;
        draggingEntry = null;
        dragOverFavorites = false;
        setFavoritesHighlight(false);
    }

    private boolean isOverFavorites(float rawX, float rawY) {
        if (favoritesBox == null || favoritesBox.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] loc = new int[2];
        favoritesBox.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + favoritesBox.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + favoritesBox.getHeight();
    }

    private void setFavoritesHighlight(boolean highlighted) {
        if (favoritesBox == null) return;
        favoritesBox.setBackground(highlighted
                ? Ui.rounded(0x332E9BD6, Ui.dpf(context, 12), accent(), Ui.dpf(context, 1))
                : null);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (draggingEntry != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    moveAppDrag(ev.getRawX(), ev.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    finishAppDrag(ev.getRawX(), ev.getRawY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelAppDrag();
                    return true;
                default:
                    return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private View makeAppRow(final Apps.Entry e) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(Ui.dp(context, 5), Ui.dp(context, 5),
                Ui.dp(context, 5), Ui.dp(context, 5));
        boolean showLabels = prefs.drawerLabels();
        cell.setMinimumHeight(iconPx + Ui.dp(context, showLabels ? 30 : 12));
        cell.setContentDescription(store.labelFor(Item.app(e.pkg, e.activity), e.label));
        boolean selected = controllerSelection && selectedKey != null
                && selectedKey.equals(keyOf(e));
        cell.setBackground(selected
                ? Ui.focusRing(context, Ui.dpf(context, 12))
                : Ui.ripple(context, Ui.dpf(context, 12)));
        if (selected) {
            cell.setScaleX(0.92f);
            cell.setScaleY(0.92f);
            cell.animate().scaleX(1f).scaleY(1f)
                    .setDuration(140)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
        }
        cell.setClickable(true);
        cell.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                launchEntry(e);
            }
        });
        cell.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Haptics.longPress(cell);
                beginAppDrag(e, cell);
                return true;
            }
        });
        cell.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (draggingEntry == null || draggingCell != v) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        moveAppDrag(event.getRawX(), event.getRawY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        finishAppDrag(event.getRawX(), event.getRawY());
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelAppDrag();
                        return true;
                    default:
                        return true;
                }
            }
        });

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageBitmap(iconFor(e));
        cell.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        if (showLabels) {
            String labelText = store.labelFor(Item.app(e.pkg, e.activity), e.label);
            android.widget.TextView label = new android.widget.TextView(context);
            label.setText(labelText);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f);
            label.setGravity(Gravity.CENTER);
            label.setShadowLayer(Ui.dpf(context, 2.5f), 0, Ui.dpf(context, 1), 0xB3000000);
            label.setSingleLine(true);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(context, 3);
            cell.addView(label, llp);
        }
        return cell;
    }

    private final class DrawerAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            int count = shownApps.size();
            return columns == 1 ? count : (count + 1) / 2;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBaselineAligned(false);
            int first = position * columns;
            for (int c = 0; c < columns; c++) {
                int index = first + c;
                if (index < shownApps.size()) {
                    View cell = makeAppRow(shownApps.get(index));
                    row.addView(cell, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    row.addView(new Space(context), new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
            }
            return row;
        }
    }
}
