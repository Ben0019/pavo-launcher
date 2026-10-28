package com.ben.aurora;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Floating recording controls shown above every app while a capture is active. */
final class ScreenRecorderOverlay {
    interface Listener {
        void onPauseResume();
        void onStop();
        void onToggleDevice();
        void onToggleMic();
        void onScreenshot();
    }

    private final Context context;
    private WindowManager windowManager;
    private LinearLayout root;
    private WindowManager.LayoutParams params;
    private TextView timer;
    private TextView pause;
    private TextView device;
    private TextView mic;
    private Listener listener;
    private boolean paused;
    private boolean deviceOn;
    private boolean micOn;
    private int seconds;

    ScreenRecorderOverlay(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean show(Listener listener, boolean deviceOn, boolean micOn) {
        if (!Settings.canDrawOverlays(context)) return false;
        this.listener = listener;
        this.deviceOn = deviceOn;
        this.micOn = micOn;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return false;
        if (root != null) hide();

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(7), dp(5), dp(7), dp(5));
        root.setBackground(Ui.rounded(0xE61A171B, dp(15), 0xCCFF5F68, dp(1)));

        TextView grip = label("\u283f", 14f, 0xCCFFFFFF, false);
        grip.setGravity(Gravity.CENTER);
        grip.setContentDescription("Drag recording controls");
        root.addView(grip, widthLp(22, 0));

        timer = label("REC 00:00", 8f, 0xFFFFD3D5, true);
        timer.setGravity(Gravity.CENTER);
        root.addView(timer, widthLp(64, 2));

        pause = control("PAUSE", 0x33FFFFFF);
        pause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onPauseResume();
            }
        });
        root.addView(pause, widthLp(48, 4));

        TextView shot = control("SHOT", 0x338BE9FF);
        shot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onScreenshot();
            }
        });
        root.addView(shot, widthLp(44, 4));

        device = control("SYS", 0x33FF6B6B);
        device.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onToggleDevice();
            }
        });
        root.addView(device, widthLp(42, 4));

        mic = control("MIC", 0x338BE9FF);
        mic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onToggleMic();
            }
        });
        root.addView(mic, widthLp(42, 4));

        TextView stop = control("STOP", 0xE6FF3D3D);
        stop.setTextColor(0xFFFFFFFF);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onStop();
            }
        });
        root.addView(stop, widthLp(52, 5));

        int overlayWidth = Math.min(dp(356),
                context.getResources().getDisplayMetrics().widthPixels - dp(24));
        params = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(56);
        params.setTitle("Aurora recorder controls");
        DragTouch drag = new DragTouch();
        grip.setOnTouchListener(drag);
        timer.setOnTouchListener(drag);
        root.setOnTouchListener(drag);
        try {
            windowManager.addView(root, params);
        } catch (Throwable t) {
            root = null;
            return false;
        }
        update(paused, deviceOn, micOn, seconds);
        return true;
    }

    void update(boolean paused, boolean deviceOn, boolean micOn, long elapsedSeconds) {
        this.paused = paused;
        this.deviceOn = deviceOn;
        this.micOn = micOn;
        this.seconds = (int) Math.max(0L, elapsedSeconds);
        if (root == null) return;
        timer.setText((paused ? "PAUSED " : "REC ")
                + String.format(java.util.Locale.US, "%02d:%02d",
                seconds / 60, seconds % 60));
        timer.setTextColor(paused ? 0xFFFFC857 : 0xFFFFD3D5);
        pause.setText(paused ? "RESUME" : "PAUSE");
        pause.setBackground(Ui.rounded(paused ? 0x66FFC857 : 0x33FFFFFF,
                dp(9), paused ? 0xFFFFC857 : 0x55FFFFFF, dp(1)));
        device.setText("SYS");
        device.setContentDescription(deviceOn ? "System audio on" : "System audio off");
        device.setTextColor(deviceOn ? 0xFFFFC1C4 : 0x88FFFFFF);
        device.setBackground(Ui.rounded(deviceOn ? 0x33FF6B6B : 0x22FFFFFF,
                dp(9), deviceOn ? 0x99FF6B6B : 0x33FFFFFF, dp(1)));
        mic.setText("MIC");
        mic.setContentDescription(micOn ? "Microphone on" : "Microphone off");
        mic.setTextColor(micOn ? 0xFFBDEFFF : 0x88FFFFFF);
        mic.setBackground(Ui.rounded(micOn ? 0x338BE9FF : 0x22FFFFFF,
                dp(9), micOn ? 0x998BE9FF : 0x33FFFFFF, dp(1)));
    }

    void setVisible(boolean visible) {
        if (root != null) root.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void hide() {
        if (root == null) return;
        try {
            if (windowManager != null) windowManager.removeView(root);
        } catch (Throwable ignored) {}
        root = null;
        listener = null;
    }

    private final class DragTouch implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        @Override public boolean onTouch(View v, MotionEvent event) {
            if (params == null || root == null || windowManager == null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                moved = false;
                Haptics.tick(v);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (Math.hypot(dx, dy) > dp(3)) moved = true;
                params.x = startX + Math.round(dx);
                params.y = startY + Math.round(dy);
                try { windowManager.updateViewLayout(root, params); }
                catch (Throwable ignored) {}
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                return moved;
            }
            return false;
        }
    }

    private TextView control(String value, int fill) {
        TextView v = label(value, 7.5f, 0xE6FFFFFF, false);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(false);
        v.setPadding(dp(6), 0, dp(6), 0);
        v.setBackground(Ui.rounded(fill, dp(9), 0x55FFFFFF, dp(1)));
        return v;
    }

    private TextView label(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setSingleLine(true);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private LinearLayout.LayoutParams widthLp(int widthDp, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(widthDp), dp(30));
        lp.leftMargin = dp(left);
        return lp;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
