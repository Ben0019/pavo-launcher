package com.ben.aurora;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Transparent control sheet for the performance overlay. Living in a real activity window
 * (rather than a second application overlay) gives reliable touch delivery on devices or
 * launchers that hide or occlude non-system overlays.
 */
public final class PerfControlActivity extends Activity implements PerfOverlayMenuView.Listener {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private PerfOverlayMenuView menu;

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
        Loc.applyLocale(this);
        prefs = new Prefs(this);
        int mode = currentMode();
        Backdrop backdrop = ScreenCaptureBackdrop.capture(this);
        menu = new PerfOverlayMenuView(this, backdrop, prefs, mode,
                prefs.perfOverlayScale(), this);
        menu.setMapperMode(TouchMapService.isMapperEnabledForForeground(this));
        setContentView(menu);

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        hideBars();

        menu.blockTouchesFor(650);
        PerfOverlayService.setOverlayTouchable(false);
    }

    @Override
    protected void onDestroy() {
        PerfOverlayService.setOverlayTouchable(true);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    private int currentMode() {
        try {
            int v = Settings.System.getInt(getContentResolver(), "performance_mode", 0);
            return Math.max(0, Math.min(2, v));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void applyPerformanceMode(final int mode) {
        if (menu != null) menu.setPerformanceMode(mode);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    String cmd = "settings put system performance_mode " + mode
                            + "; setprop persist.vendor.debug.mode " + mode;
                    Process p = new ProcessBuilder("su", "-c", cmd)
                            .redirectErrorStream(true).start();
                    p.waitFor(3, TimeUnit.SECONDS);
                    ok = p.exitValue() == 0;
                } catch (Throwable ignored) {
                }
                final boolean success = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            LToast.makeText(PerfControlActivity.this,
                                    "Performance mode: " + modeName(mode),
                                    Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            if (menu != null) menu.setPerformanceMode(currentMode());
                            LToast.makeText(PerfControlActivity.this,
                                    "Performance mode needs root access",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private static String modeName(int mode) {
        if (mode == 1) return "Standard";
        if (mode == 2) return "High";
        return "Normal";
    }

    @Override
    public void onDismiss() {
        finish();
    }

    @Override
    public void onOverlayEnabled(boolean enabled) {
        if (enabled) return;
        prefs.setPerfOverlay(false);
        PerfOverlayService.stop(this);
        finish();
    }

    @Override
    public void onMetricsChanged() {
        PerfOverlayService.refreshIfRunning();
    }

    @Override
    public void onRefreshChanged(int ms) {
        prefs.setPerfRefreshMs(ms);
        PerfOverlayService.refreshIfRunning();
    }

    @Override
    public void onOpacityChanged(int percent) {
        prefs.setPerfOverlayAlpha(percent);
        PerfOverlayService.refreshIfRunning();
    }

    @Override
    public void onScaleChanged(float scale) {
        prefs.setPerfOverlayScale(scale);
        PerfOverlayService.setOverlayScale(scale);
    }

    @Override
    public void onPerformanceMode(int mode) {
        applyPerformanceMode(mode);
    }

    @Override
    public void onResetPosition() {
        prefs.setPerfOverlayPosition(-1, -1);
        PerfOverlayService.resetPosition();
    }

    @Override
    public void onEditPlacement() {
        finish();
        PerfOverlayService.enterEditModeFromShortcut(this);
    }

    @Override
    public void onMapController() {
        if (AuroraInputService.beginKeyCapture(this)) {
            finish();
        } else {
            LToast.makeText(this,
                    "Controller service unavailable. Check PServer/root or accessibility.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onClearController() {
        prefs.setPerfControllerRaw("");
        finish();
    }

    @Override
    public void onMapDrawerController() {
        if (AuroraInputService.beginDrawerKeyCapture(this)) {
            finish();
        } else {
            LToast.makeText(this,
                    "Controller service unavailable. Check PServer/root or accessibility.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onClearDrawerController() {
        prefs.setDrawerControllerRaw("");
        finish();
    }

    @Override
    public void onShowDetails() {
        PerfOverlayService.showDetailFromShortcut(this);
        finish();
    }
}
