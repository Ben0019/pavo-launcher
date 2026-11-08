package com.ben.pavo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Region screenshot: freeze the display, let the user draw/move/resize a box,
 * then confirm with the floating SAVE / DELETE / RESET / CANCEL bar.
 */
final class RegionCapture {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static FrameLayout overlayRoot;
    private static RegionCaptureView view;
    private static WindowManager windowManager;
    private static boolean onAccessibility;

    private RegionCapture() {}

    static void start(final Context host) {
        if (view != null || host == null) return;
        final Context app = host.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap shot = ScreenCaptureBackdrop.captureFull(app);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (shot == null) {
                            QuickPanelEdgeHandle.show();
                            LToast.makeText(app, "Screenshot needs PServer/root",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (view != null) {
                            shot.recycle();
                            return;
                        }
                        show(host, app, shot);
                    }
                });
            }
        }, "aurora-region-capture").start();
    }

    private static void show(Context host, final Context app, final Bitmap shot) {
        final WindowManager wm =
                (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            shot.recycle();
            QuickPanelEdgeHandle.show();
            LToast.makeText(app, "Could not open the region selector",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final Context ui = Ui.adaptiveContext(host);

        final FrameLayout[] rootRef = new FrameLayout[1];
        final LinearLayout[] barRef = new LinearLayout[1];
        final RegionCaptureView[] selectorRef = new RegionCaptureView[1];
        RegionCaptureView selector = new RegionCaptureView(ui, shot,
                new RegionCaptureView.Listener() {
                    @Override
                    public void onSelectionChanged(boolean hasSelection) {
                        LinearLayout bar = barRef[0];
                        FrameLayout root = rootRef[0];
                        if (bar == null || root == null) return;
                        bar.setVisibility(hasSelection ? View.VISIBLE : View.GONE);
                        positionBar(root, selectorRef[0], bar);
                    }

                    @Override
                    public void onCancel() {
                        dismiss();
                    }
                });

        final LinearLayout bar = buildBar(ui, app, selector);
        bar.setVisibility(View.GONE);
        barRef[0] = bar;

        selectorRef[0] = selector;
        FrameLayout root = new FrameLayout(ui);
        rootRef[0] = root;
        root.addView(selector, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.bottomMargin = dp(ui, 26);
        root.addView(bar, barLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Pavo region capture");

        boolean added = false;
        if (PavoA11yService.hasAccessibility()) {
            added = PavoA11yService.addAccessibilityOverlay(root, lp);
            onAccessibility = added;
        }
        if (!added) {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                wm.addView(root, lp);
                added = true;
                onAccessibility = false;
            } catch (Throwable ignored) {
                added = false;
            }
        }
        if (!added) {
            selector.release();
            QuickPanelEdgeHandle.show();
            LToast.makeText(app, "Could not open the region selector",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        root.post(new Runnable() {
            @Override
            public void run() {
                positionBar(rootRef[0], selectorRef[0], barRef[0]);
            }
        });
        overlayRoot = root;
        view = selector;
        windowManager = wm;
        TouchMapService.notifyModalOverlayChanged();
    }

    /**
     * Keep the action bar clear of the selection: it sits at the bottom by
     * default and hops to the top (or the roomier side) when the box reaches it.
     */
    private static void positionBar(FrameLayout root, RegionCaptureView selector,
                                    LinearLayout bar) {
        if (root.getHeight() <= 0 || selector == null) return;
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) bar.getLayoutParams();
        if (lp == null) return;
        int margin = dp(bar.getContext(), 26);
        int barHeight = Math.max(bar.getHeight(), dp(bar.getContext(), 40));
        int h = root.getHeight();

        RectF box = new RectF();
        boolean has = selector.copySelection(box);
        boolean bottomFree = !has || box.bottom <= h - margin - barHeight;
        boolean topFree = !has || box.top >= margin + barHeight;
        boolean useTop;
        if (!has) {
            useTop = false;
        } else if (bottomFree) {
            useTop = false;
        } else if (topFree) {
            useTop = true;
        } else {
            useTop = box.top > h - box.bottom;
        }

        int gravity = useTop
                ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        if (lp.gravity == gravity) return;
        lp.gravity = gravity;
        lp.topMargin = useTop ? margin : 0;
        lp.bottomMargin = useTop ? 0 : margin;
        bar.setLayoutParams(lp);
    }

    private static LinearLayout buildBar(Context c, final Context app,
                                         final RegionCaptureView selector) {
        ThemeColors.initialize(c);
        int accent = ThemeColors.accent();

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(c, 7), dp(c, 5), dp(c, 7), dp(c, 5));
        bar.setBackground(Ui.rounded(0xE61A171B, Ui.dpf(c, 15),
                ThemeColors.withAlpha(0xCC), Ui.dpf(c, 1)));

        TextView save = barButton(c, "Save", ThemeColors.withAlpha(0x3D), 0xFFFFFFFF);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                Bitmap crop = selector.cropSelection();
                if (crop == null) return;
                dismiss();
                save(app, crop);
            }
        });
        bar.addView(save, barLp(c, 56, 0));

        TextView delete = barButton(c, "Delete", 0x33FF6B6B, 0xFFFFD3D5);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                selector.clearSelection();
            }
        });
        bar.addView(delete, barLp(c, 60, 4));

        TextView reset = barButton(c, "Reset", 0x33FFFFFF, 0xE6FFFFFF);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                selector.resetSelection();
            }
        });
        bar.addView(reset, barLp(c, 56, 4));

        TextView cancel = barButton(c, "Cancel", 0x33FFFFFF, 0xE6FFFFFF);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                dismiss();
            }
        });
        bar.addView(cancel, barLp(c, 62, 4));
        return bar;
    }

    private static TextView barButton(Context c, String label, int fill, int textColor) {
        TextView v = new LTextView(c);
        v.setText(Loc.s(c, label));
        v.setSingleLine(true);
        v.setAllCaps(true);
        v.setTextColor(textColor);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setMinWidth(dp(c, 56));
        v.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        v.setBackground(Ui.rounded(fill, Ui.dpf(c, 9), 0x55FFFFFF, Ui.dpf(c, 1)));
        return v;
    }

    private static LinearLayout.LayoutParams barLp(Context c, int minWidthDp, int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 30));
        lp.leftMargin = dp(c, leftDp);
        return lp;
    }

    static boolean isVisible() {
        return view != null;
    }

    static void dismiss() {
        View root = overlayRoot;
        WindowManager wm = windowManager;
        RegionCaptureView v = view;
        overlayRoot = null;
        windowManager = null;
        view = null;
        TouchMapService.notifyModalOverlayChanged();
        if (root == null) {
            if (v != null) v.release();
            return;
        }
        if (onAccessibility) {
            PavoA11yService.removeAccessibilityOverlay(root);
        } else if (wm != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
        }
        onAccessibility = false;
        if (v != null) v.release();
        QuickPanelEdgeHandle.show();
    }

    private static void save(final Context app, final Bitmap crop) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "Pavo Screenshots");
                final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date());
                final File file = new File(dir, "Aurora_" + stamp + "_region.png");
                boolean ok = false;
                FileOutputStream out = null;
                try {
                    if (!dir.exists()) dir.mkdirs();
                    out = new FileOutputStream(file);
                    ok = crop.compress(Bitmap.CompressFormat.PNG, 100, out);
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (out != null) out.close();
                    } catch (Throwable ignored) {
                    }
                    crop.recycle();
                }
                final boolean saved = ok && file.isFile() && file.length() > 0;
                if (saved) {
                    MediaScannerConnection.scanFile(app,
                            new String[]{file.getAbsolutePath()},
                            new String[]{"image/png"}, null);
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        LToast.makeText(app, saved ? "Screenshot saved"
                                : "Could not save screenshot", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "aurora-region-save").start();
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
