package com.ben.pavo;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * First-run setup. Explains every permission the launcher uses, lets each one be
 * granted or skipped, and asks for the home role so Pavo actually becomes the
 * home screen after installation.
 */
public final class SetupActivity extends Activity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x99FFFFFF;
    private static final int GREEN = 0xFF69D98B;
    private static final int AMBER = 0xFFFFC857;
    private static final int DIM = 0x66FFFFFF;

    private static final int REQ_RUNTIME = 41;

    private Prefs prefs;
    private LinearLayout list;
    private final List<Item> items = new ArrayList<>();

    private final class Item {
        String key;
        String title;
        String why;
        TextView status;
        TextView allow;
        TextView skip;
        boolean granted;
        String[] permissions;
        Runnable action;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------------ ui

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xF2080B10);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(14));
        card.setBackground(Ui.rounded(0xFF0E131A, dp(22), 0x33FFFFFF, dp(1)));

        TextView title = text("SET UP PAVO", 20f, TEXT, true);
        title.setLetterSpacing(0.06f);
        card.addView(title);

        TextView sub = text("Android needs a few permissions for the launcher to work "
                + "properly. Each one is optional - skip anything you do not want.",
                9.5f, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(10));
        card.addView(sub);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addItems();

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.END);
        bottom.setPadding(0, dp(10), 0, 0);

        TextView skipAll = button("SKIP ALL", false);
        skipAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (Item item : items) {
                    if (!item.granted) prefs.skipSetup(item.key);
                }
                finishSetup();
            }
        });
        bottom.addView(skipAll, wrapButton());

        TextView done = button("CONTINUE", true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finishSetup();
            }
        });
        LinearLayout.LayoutParams dlp = wrapButton();
        dlp.leftMargin = dp(8);
        bottom.addView(done, dlp);
        card.addView(bottom);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.setMargins(dp(14), dp(12), dp(14), dp(12));
        root.addView(card, clp);
        setContentView(root);
    }

    private void addItems() {
        addItem("home", "Home screen",
                "Makes Pavo your launcher, so pressing Home lands here.",
                null, new Runnable() {
                    @Override public void run() { requestHome(); }
                });
        addItem("notifications", "Notifications",
                "Fan, FTP and recording status stay visible while they run.",
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, null);
        addItem("overlay", "Display over other apps",
                "The performance HUD and the in-game menus need to draw on top.",
                null, new Runnable() {
                    @Override public void run() {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        safeStart(i, Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    }
                });
        addItem("accessibility", "Accessibility service",
                "Used for the controller remap overlay and touch helpers.",
                null, new Runnable() {
                    @Override public void run() {
                        safeStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                Settings.ACTION_SETTINGS);
                    }
                });
        addItem("battery", "Ignore battery optimisation",
                "Keeps fan control and FTP hosting alive with the screen off.",
                null, new Runnable() {
                    @Override public void run() { requestBattery(); }
                });
        addItem("files", "All files access",
                "The file manager and FTP host browse internal storage and the SD card.",
                null, new Runnable() {
                    @Override public void run() { requestFiles(); }
                });
        addItem("microphone", "Microphone",
                "Only used when you record a clip with sound.",
                new String[]{Manifest.permission.RECORD_AUDIO}, null);
        addItem("location", "Location",
                "Rough location for the local weather line on the home screen.",
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, null);
    }

    private void addItem(String key, String title, String why, String[] permissions,
                         Runnable action) {
        final Item item = new Item();
        item.key = key;
        item.title = title;
        item.why = why;
        item.permissions = permissions;
        item.action = action;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackground(Ui.rounded(0x14FFFFFF, dp(12), 0x22FFFFFF, dp(1)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(7);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(title, 11.5f, TEXT, true);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        item.status = text("", 8.5f, DIM, true);
        item.status.setGravity(Gravity.END);
        head.addView(item.status);
        row.addView(head);

        TextView reason = text(why, 8.8f, MUTED, false);
        reason.setPadding(0, dp(2), 0, dp(6));
        row.addView(reason);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        item.allow = button("ALLOW", true);
        item.allow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (item.granted) return;
                if (item.action != null) item.action.run();
                else if (item.permissions != null) askPermissions(item.permissions, REQ_RUNTIME);
            }
        });
        actions.addView(item.allow, wrapSmall());
        item.skip = button("SKIP", false);
        item.skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.skipSetup(item.key);
                refresh();
            }
        });
        LinearLayout.LayoutParams slp = wrapSmall();
        slp.leftMargin = dp(6);
        actions.addView(item.skip, slp);
        row.addView(actions);

        list.addView(row, rlp);
        items.add(item);
    }

    // ------------------------------------------------------------------ grants

    private void requestHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = (RoleManager) getSystemService(Context.ROLE_SERVICE);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (!roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    try {
                        startActivityForResult(roles.createRequestRoleIntent(
                                RoleManager.ROLE_HOME), 7);
                        return;
                    } catch (Throwable ignored) {
                    }
                } else {
                    prefs.skipSetup("home");
                    refresh();
                    return;
                }
            }
        }
        safeStart(new Intent(Settings.ACTION_HOME_SETTINGS), Settings.ACTION_SETTINGS);
    }

    private void requestBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Settings.ACTION_SETTINGS);
        }
    }

    private void requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        safeStart(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Settings.ACTION_SETTINGS);
    }

    private void safeStart(Intent intent, String fallbackAction) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(fallbackAction));
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        for (Item item : items) {
            boolean granted = granted(item.key);
            item.granted = granted;
            boolean skipped = prefs.setupSkipped(item.key);
            if (granted) {
                item.status.setText("GRANTED");
                item.status.setTextColor(GREEN);
            } else if (skipped) {
                item.status.setText("SKIPPED");
                item.status.setTextColor(DIM);
            } else {
                item.status.setText("NEEDED");
                item.status.setTextColor(AMBER);
            }
            item.allow.setVisibility(granted ? View.GONE : View.VISIBLE);
            item.skip.setVisibility(granted ? View.GONE : View.VISIBLE);
        }
    }

    private boolean granted(String key) {
        switch (key) {
            case "home":
                if (Build.VERSION.SDK_INT >= 29) {
                    RoleManager roles = (RoleManager) getSystemService(Context.ROLE_SERVICE);
                    return roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
                }
                return false;
            case "notifications":
                return Build.VERSION.SDK_INT < 33
                        || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
            case "overlay":
                return Settings.canDrawOverlays(this);
            case "accessibility":
                return accessibilityEnabled();
            case "battery":
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            case "files":
                return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
            case "microphone":
                return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED;
            case "location":
                return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            default:
                return false;
        }
    }

    private boolean accessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            String me = getPackageName() + "/" + PavoA11yService.class.getName();
            return enabled.contains(me) || enabled.contains(
                    getPackageName() + "/.PavoA11yService");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void finishSetup() {
        prefs.setSetupDone(true);
        // Balanced clocks and Smart fan for a fresh install.
        new Thread(new Runnable() {
            @Override public void run() {
                Defaults.apply(SetupActivity.this);
            }
        }, "pavo-defaults").start();
        finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ helpers

    private void hideBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsController c = window.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
        }
    }

    private void askPermissions(String[] permissions, int code) {
        List<String> missing = new ArrayList<>();
        for (String p : permissions) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            refresh();
            return;
        }
        requestPermissions(missing.toArray(new String[0]), code);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (medium) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 10f, primary ? 0xFF06110B : 0xF2FFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(14), dp(7), dp(14), dp(7));
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(primary ? ThemeColors.accent() : 0x1AFFFFFF, dp(10),
                primary ? 0x00000000 : 0x44FFFFFF, dp(1)));
        return v;
    }

    private LinearLayout.LayoutParams wrapButton() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapSmall() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
