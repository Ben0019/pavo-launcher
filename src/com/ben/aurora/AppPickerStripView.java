package com.ben.aurora;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin, transparent vertical app picker used by the tuner. The first entry is
 * the global tuner context; the rest are launchable apps.
 */
final class AppPickerStripView extends FrameLayout {

    interface Listener {
        void onPick(String pkg, Apps.Entry entry);
        void onClose();
    }

    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xB8FFFFFF;
    private static final int ACCENT = 0xFF8BE9FF;

    private final Context context;
    private final Prefs prefs;
    private final String selectedPkg;
    private final Listener listener;
    private final int iconPx;
    private final boolean showLabels;
    private final List<Item> items = new ArrayList<>();
    private LinearLayout list;
    private TextView loading;
    private boolean closing;
    private long lastNavAt;

    private final class Item {
        String pkg;
        Apps.Entry entry;
        LinearLayout view;
        boolean global;
    }

    AppPickerStripView(Context context, Prefs prefs, String selectedPkg,
                       View liveSource, Listener listener) {
        super(context);
        this.context = context;
        this.prefs = prefs;
        this.selectedPkg = selectedPkg == null ? "" : selectedPkg;
        this.listener = listener;
        this.iconPx = dp(prefs.drawerOverlayIconDp());
        this.showLabels = prefs.drawerLabels();

        View scrim = new View(context);
        scrim.setBackgroundColor(0x1A000000);
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int sh = getResources().getDisplayMetrics().heightPixels;
        int edge = dp(10);
        int width = Math.max(dp(showLabels ? 76 : 56),
                iconPx + dp(showLabels ? 30 : 28));
        int panelHeight = Math.min(sh - edge * 2, dp(360));

        Backdrop fallback = liveSource instanceof GlassPanel
                ? ((GlassPanel) liveSource).getBackdrop() : null;
        FrameLayout strip = new FrameLayout(context);
        strip.setBackgroundColor(0x440B0E14);
        strip.setClipToOutline(true);
        strip.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });
        strip.setClickable(true);
        strip.setFocusable(false);

        if (liveSource != null) {
            LiveBackdropView live = new LiveBackdropView(context);
            live.setSource(liveSource);
            live.setRadiusDp(24f);
            live.setBackdropEnabled(true);
            strip.addView(live, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        GlassPanel material = new GlassPanel(context, null, ACCENT,
                liveSource != null ? 0x2A0B0E14 : 0x800B0E14, 24, true);
        material.setClearGlass(true);
        material.setClearBodyAlpha(0);
        if (liveSource == null) {
            if (fallback != null && fallback.bitmap != null) {
                material.setBackdrop(fallback);
                material.setBackdropScreenAnchored(true);
                material.setLiquidEffect(true);
            } else {
                material.setFrosted(true);
            }
        }
        material.setSurfaceAlpha(prefs.drawerOverlayAlpha() / 100f);
        material.setClickable(false);
        material.setFocusable(false);
        strip.addView(material, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(8), dp(4), 0);
        strip.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                width, panelHeight, Gravity.START | Gravity.TOP);
        lp.leftMargin = edge;
        lp.topMargin = edge;
        addView(strip, lp);
        post(new Runnable() {
            @Override public void run() {
                int available = getHeight();
                if (available <= 0) {
                    postDelayed(this, 40L);
                    return;
                }
                int top = edge;
                int target = Math.min(available - edge * 2,
                        Math.max(dp(320), Math.round(available * 0.94f)));
                if (liveSource != null && liveSource.getHeight() > 0) {
                    int[] sourceLoc = new int[2];
                    int[] overlayLoc = new int[2];
                    liveSource.getLocationOnScreen(sourceLoc);
                    getLocationOnScreen(overlayLoc);
                    int sourceTop = Math.max(edge, sourceLoc[1] - overlayLoc[1]);
                    int sourceHeight = liveSource.getHeight();
                    if (sourceHeight > 0 && sourceTop + sourceHeight <= available) {
                        top = sourceTop;
                        target = sourceHeight;
                    }
                }
                if (lp.topMargin != top || lp.height != target) {
                    lp.topMargin = top;
                    lp.height = target;
                    strip.setLayoutParams(lp);
                }
            }
        });
        strip.setTranslationX(-(width + dp(30)));
        strip.animate().translationX(0f).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();

        TextView title = text(Loc.s(context, "APPS"), 11f, ACCENT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(6));
        title.setShadowLayer(dp(2f), 0f, dp(1f), 0x99000000);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loading = text(Loc.s(context, "LOADING…"), 8f, MUTED, true);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(12), 0, dp(12));
        list.addView(loading);

        addGlobalItem();
        loadApps();
        postDelayed(new Runnable() {
            @Override public void run() {
                ReadableText.apply(AppPickerStripView.this);
            }
        }, 90L);
    }

    private void addGlobalItem() {
        Item item = new Item();
        item.global = true;
        item.pkg = "";
        item.view = buildRow(item, Apps.drawableToBitmap(
                getResources().getDrawable(R.mipmap.ic_launcher, null), iconPx),
                Loc.s(context, "ALL APPS"), true);
        items.add(item);
        list.addView(item.view, rowParams());
        styleItem(item, false);
        item.view.requestFocus();
    }

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Apps.Entry> entries = new ArrayList<>();
                try {
                    entries = Apps.query(context);
                    Apps.loadIcons(context, entries, iconPx);
                } catch (Throwable ignored) {
                }
                final List<Apps.Entry> result = entries;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (!closing) populate(result);
                    }
                });
            }
        }, "AuroraAppPicker").start();
    }

    private void populate(List<Apps.Entry> entries) {
        if (loading != null) {
            list.removeView(loading);
            loading = null;
        }
        for (Apps.Entry entry : entries) {
            Item item = new Item();
            item.entry = entry;
            item.pkg = entry.pkg;
            Bitmap icon = entry.icon != null ? entry.icon
                    : Apps.letterTile(entry.label, iconPx);
            item.view = buildRow(item, icon, entry.label, showLabels);
            items.add(item);
            list.addView(item.view, rowParams());
            styleItem(item, false);
        }
        Item found = null;
        for (Item item : items) {
            if (!item.global && item.pkg.equals(selectedPkg)) {
                found = item;
                break;
            }
        }
        final Item selected = found;
        if (selected != null) {
            selected.view.requestFocus();
            selected.view.post(new Runnable() {
                @Override
                public void run() {
                    selected.view.requestRectangleOnScreen(new android.graphics.Rect(
                            0, 0, selected.view.getWidth(), selected.view.getHeight()), false);
                }
            });
        }
        ReadableText.apply(AppPickerStripView.this);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(1);
        return lp;
    }

    private LinearLayout buildRow(final Item item, Bitmap bitmap, String label,
                                  boolean withLabel) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER);
        row.setMinimumHeight(iconPx + dp(withLabel ? 30 : 12));
        row.setPadding(dp(5), dp(5), dp(5), dp(5));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setDefaultFocusHighlightEnabled(false);
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                v.requestFocus();
                pick(item);
            }
        });
        row.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focused) {
                styleItem(item, focused);
                if (focused) {
                    v.requestRectangleOnScreen(new android.graphics.Rect(
                            0, 0, v.getWidth(), v.getHeight()), false);
                }
            }
        });

        ImageView icon = new ImageView(context);
        icon.setImageBitmap(bitmap);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));

        if (withLabel) {
            TextView name = text(label, 9f, 0xFFFFFFFF, false);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(false);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setShadowLayer(dp(2.5f), 0, dp(1), 0xB3000000);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(3);
            row.addView(name, llp);
        }
        return row;
    }

    private void styleItem(Item item, boolean focused) {
        boolean selected = item.global ? selectedPkg.isEmpty() : item.pkg.equals(selectedPkg);
        if (focused) {
            item.view.setBackground(Ui.focusRing(context, dp(12)));
        } else if (selected) {
            item.view.setBackground(Ui.rounded(0x249BE7D5, dp(12),
                    0x669BE7D5, dp(1)));
        } else {
            item.view.setBackground(Ui.ripple(context, dp(12)));
        }
    }

    private void pick(Item item) {
        if (closing || item == null) return;
        closing = true;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (listener != null) listener.onPick(item.pkg, item.entry);
    }

    private void close() {
        if (closing) return;
        closing = true;
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        if (listener != null) listener.onClose();
    }

    void dismiss() {
        close();
    }

    boolean handleKey(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean repeat = down && event.getRepeatCount() > 0;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) {
            if (down && !repeat) close();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && !repeat) {
                for (Item item : items) {
                    if (item.view.isFocused()) {
                        pick(item);
                        break;
                    }
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!down) return true;
            long now = SystemClock.uptimeMillis();
            if (repeat && now - lastNavAt < 65L) return true;
            lastNavAt = now;
            move(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (down && !repeat) move(code == KeyEvent.KEYCODE_BUTTON_R1 ? 6 : -6);
            return true;
        }
        return false;
    }

    private long lastStickNavAt;
    private boolean stickLatched;

    /**
     * Analogue navigation for controllers whose D-pad arrives as HAT/stick
     * axes. One step per deflection, and the stick must recentre first.
     */
    boolean handleStick(float x, float y) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        if (ax < 0.45f && ay < 0.45f) {
            stickLatched = false;
            return true;
        }
        if (ax < 0.62f && ay < 0.62f) return true;
        long now = SystemClock.uptimeMillis();
        if (stickLatched && now - lastStickNavAt < 260L) return true;
        stickLatched = true;
        lastStickNavAt = now;
        if (ay >= ax) move(y > 0f ? 1 : -1);
        else move(x > 0f ? 1 : -1);
        return true;
    }

    private void move(int delta) {
        if (items.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).view.isFocused()) {
                index = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(items.size() - 1, index + delta));
        items.get(next).view.requestFocus();
        Haptics.tick(items.get(next).view);
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setTypeface(android.graphics.Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
        return t;
    }

    private int dp(float value) {
        return Ui.dp(context, value);
    }
}
