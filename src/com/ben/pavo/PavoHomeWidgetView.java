package com.ben.pavo;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/** Native, animated Pavo widgets placed directly on the launcher grid. */
final class PavoHomeWidgetView extends FrameLayout {
    static final String KIND_BATTERY = "pavo.battery";
    static final String KIND_FAN = "pavo.fan";
    static final String KIND_THERMAL = "pavo.thermal";

    private final String kind;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View visual;
    private final Drawable cardBackground;
    private volatile boolean running;
    private Thread worker;

    static boolean validKind(String kind) {
        return KIND_BATTERY.equals(kind) || KIND_FAN.equals(kind)
                || KIND_THERMAL.equals(kind);
    }

    static String labelFor(android.content.Context c, String kind) {
        if (KIND_BATTERY.equals(kind)) return Loc.s(c, "Device Health");
        if (KIND_FAN.equals(kind)) return Loc.s(c, "Fan Control");
        if (KIND_THERMAL.equals(kind)) return Loc.s(c, "Performance Tuner");
        return Loc.s(c, "Pavo Widget");
    }

    static int spanCols(String kind) {
        if (KIND_BATTERY.equals(kind)) return 2;
        return 3;
    }

    static int spanRows(String kind) {
        return KIND_BATTERY.equals(kind) ? 2 : 1;
    }

    PavoHomeWidgetView(Context c, String kind) {
        super(c);
        this.kind = kind;
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Ui.dpf(c, 18));
            }
        });
        cardBackground = Ui.rounded(0x4D0B0E14, Ui.dp(c, 18),
                0x33FFFFFF, Ui.dp(c, 1));
        setBackground(null);
        setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        setContentDescription(labelFor(getContext(), kind));

        if (KIND_BATTERY.equals(kind)) {
            visual = new BatteryPackView(c, false);
        } else if (KIND_FAN.equals(kind)) {
            visual = new FanBackView(c, false);
        } else {
            visual = new ThermalBoardView(c, false);
        }
        addView(visual, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

    }

    /** The widget card is only visible while it is being arranged or moved. */
    void setCardVisible(boolean visible) {
        setBackground(visible ? cardBackground : null);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, "aurora-home-widget");
        worker.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        super.onDetachedFromWindow();
    }

    private final boolean[] visible = {true};
    private volatile boolean activePage = true;

    void setActivePage(boolean active) {
        activePage = active;
    }

    private void poll() {
        while (running) {
            try {
                // Off-screen pages keep their widgets attached; sampling there
                // only burns CPU and repaints hidden views.
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        visible[0] = Ui.onScreen(PavoHomeWidgetView.this);
                    }
                });
                if (!activePage || !visible[0]) {
                    Thread.sleep(750L);
                    continue;
                }
                if (KIND_BATTERY.equals(kind)) {
                    // Fast, PServer-free read: the widget must paint with the
                    // real level immediately, not after a hidden node times out.
                    final DeviceHealthReader.Snapshot s =
                            DeviceHealthReader.readFast(getContext());
                    if (!running) break;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!running || !(visual instanceof BatteryPackView)) return;
                            int level = Math.max(0, Math.min(100, s.level));
                            ((BatteryPackView) visual).setBattery(level, s.charging,
                                    s.plugged, s.separated, s.full);
                        }
                    });
                } else if (KIND_FAN.equals(kind)) {
                    final PerfTuner.State s = PerfTuner.readStateLocal(getContext());
                    if (!running) break;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!running || !(visual instanceof FanBackView)) return;
                            int rpm = 0;
                            int duty = 0;
                            if (s.fan != null && s.fan.state != 0) {
                                rpm = Math.max(0, s.fan.rpm);
                                if (s.fan.period > 0) {
                                    duty = Math.max(0, Math.min(100,
                                            Math.round(s.fan.duty * 100f / s.fan.period)));
                                } else if (s.fan.speedPref > 0) {
                                    duty = Math.max(0, Math.min(100,
                                            Math.round(s.fan.speedPref / 500f)));
                                }
                                if (rpm <= 0) rpm = duty * 180;
                            }
                            ((FanBackView) visual).setTelemetry(rpm, duty,
                                    s.cpuTemp, s.gpuTemp);
                        }
                    });
                } else {
                    final float[] temps = PerfTuner.readTemperatures();
                    if (!running) break;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!running || !(visual instanceof ThermalBoardView)) return;
                            ((ThermalBoardView) visual).setTelemetry(0, 0,
                                    temps.length > 0 ? temps[0] : 0f,
                                    temps.length > 1 ? temps[1] : 0f);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(750L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
