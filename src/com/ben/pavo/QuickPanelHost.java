package com.ben.pavo;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Hosts the side quick-controls panel in an overlay window. The accessibility
 * layer is preferred so the panel can sit above games; the application-overlay
 * type keeps it working when accessibility is not bound.
 */
final class QuickPanelHost {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static QuickPanelView panel;
    private static WindowManager.LayoutParams params;
    private static WindowManager windowManager;
    private static boolean onAccessibility;
    private static boolean opening;

    private QuickPanelHost() {}

    static boolean isVisible() {
        return panel != null;
    }

    /** Controller shortcut: open the panel, or close it when it is already up. */
    static void toggleFromShortcut(Context host) {
        if (panel != null) {
            dismiss();
            return;
        }
        if (host != null) show(host);
    }

    static boolean handleControllerKey(String key, boolean down, boolean repeat) {
        QuickPanelView view = panel;
        return view != null && view.handleControllerKey(key, down, repeat);
    }

    static boolean handleControllerStick(float x, float y) {
        QuickPanelView view = panel;
        return view != null && view.handleControllerStick(x, y);
    }

    static boolean handleControllerAxis(int code, float value) {
        QuickPanelView view = panel;
        return view != null && view.handleControllerAxis(code, value);
    }

    static void show(final Context host) {
        if (panel != null || opening || host == null) return;
        if (!Settings.canDrawOverlays(host) && !PavoA11yService.hasAccessibility()) {
            LToast.makeText(host, "Allow Pavo to display over other apps first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        opening = true;
        final Context app = host.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Backdrop backdrop = ScreenCaptureBackdrop.captureSharp(app);
                if (backdrop == null) backdrop = BuiltInBackdrop.copy();
                if (backdrop == null) backdrop = WallpaperBackdrop.captureSharp(app);
                final Backdrop sampled = backdrop;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        showNow(host, sampled);
                    }
                });
            }
        }, "aurora-quick-capture").start();
    }

    private static void showNow(Context host, Backdrop backdrop) {
        opening = false;
        if (panel != null) return;
        WindowManager wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        Context ui = Ui.adaptiveContext(host);
        QuickPanelView view = new QuickPanelView(ui, backdrop,
                new QuickPanelView.Listener() {
                    @Override
                    public void onDismiss() {
                        dismiss();
                    }
                });
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("Pavo quick controls");
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        boolean added = false;
        if (PavoA11yService.hasAccessibility()) {
            added = PavoA11yService.addAccessibilityOverlay(view, lp);
            onAccessibility = added;
        }
        if (!added) {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            try {
                wm.addView(view, lp);
                added = true;
                onAccessibility = false;
            } catch (Throwable ignored) {
                added = false;
            }
        }
        if (!added) {
            view.closeNow();
            LToast.makeText(host, "Could not open quick controls",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        panel = view;
        params = lp;
        windowManager = wm;
        // Take the controller and nudge the performance overlay aside.
        PavoInputService.setQuickPanelOpen(host, true);
        PerfOverlayService.shiftForQuickPanel(true, view.occupiedWidthPx());
        TouchMapService.notifyModalOverlayChanged();
    }

    static void dismiss() {
        QuickPanelView view = panel;
        WindowManager wm = windowManager;
        panel = null;
        params = null;
        windowManager = null;
        PavoInputService.setQuickPanelOpen(null, false);
        PerfOverlayService.shiftForQuickPanel(false, 0);
        TouchMapService.notifyModalOverlayChanged();
        if (view == null) return;
        view.closeNow();
        if (onAccessibility) {
            PavoA11yService.removeAccessibilityOverlay(view);
        } else if (wm != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        onAccessibility = false;
    }
}
