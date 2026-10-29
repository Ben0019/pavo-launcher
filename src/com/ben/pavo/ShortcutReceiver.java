package com.ben.pavo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/**
 * Accepts shortcut installs from third-party apps.
 *
 * <p>Two paths exist on Android: the legacy {@code INSTALL_SHORTCUT} broadcast
 * and the modern {@code requestPinShortcut} request handled by the launcher.
 * Both end up as a web item on the home screen, keeping the shortcut intent in
 * {@link Item#url}.
 */
public final class ShortcutReceiver extends BroadcastReceiver {

    static final String ACTION_ADDED = "com.ben.pavo.SHORTCUT_ADDED";
    static final String ACTION_INSTALL = "com.android.launcher.action.INSTALL_SHORTCUT";
    static final String ACTION_UNINSTALL = "com.android.launcher.action.UNINSTALL_SHORTCUT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (ACTION_INSTALL.equals(action)) {
            Intent target = null;
            try {
                Object raw = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
                if (raw instanceof Intent) target = (Intent) raw;
            } catch (Throwable ignored) {
            }
            if (target == null) {
                // Some callers send the data URI as a plain string.
                String raw = intent.getStringExtra(Intent.EXTRA_SHORTCUT_INTENT);
                if (raw != null && !raw.isEmpty()) {
                    target = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(raw));
                }
            }
            String name = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
            Bitmap icon = null;
            try {
                Object extra = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);
                if (extra instanceof Bitmap) icon = (Bitmap) extra;
            } catch (Throwable ignored) {
            }
            String url = shortcutUrl(target);
            if (url == null) return;
            add(context, url, name, icon);
        } else if (ACTION_UNINSTALL.equals(action)) {
            Intent target = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
            String url = shortcutUrl(target);
            if (url != null) remove(context, url);
        }
    }

    /** Stores the shortcut intent, using the plain URL for normal web links. */
    static String shortcutUrl(Intent target) {
        if (target == null) return null;
        if (Intent.ACTION_VIEW.equals(target.getAction()) && target.getData() != null) {
            String data = target.getData().toString();
            if (!data.isEmpty()) return data;
        }
        try {
            return "intent:" + target.toUri(Intent.URI_INTENT_SCHEME);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void add(Context context, String url, String title, Bitmap icon) {
        if (url == null || url.isEmpty()) return;
        Prefs prefs = new Prefs(context);
        Store store = new Store(context, prefs);
        store.load();
        Item item = Item.web(url, title == null || title.trim().isEmpty()
                ? url : title.trim());
        if (store.contains(item.key())) return;
        store.addToNewPage(item);
        if (icon != null) store.setIcon(item, scale(icon, 144));
        store.save();
        notifyHome(context);
    }

    static void remove(Context context, String url) {
        Prefs prefs = new Prefs(context);
        Store store = new Store(context, prefs);
        store.load();
        store.removeFromHome("web:" + url);
        store.save();
        notifyHome(context);
    }

    static void notifyHome(Context context) {
        try {
            context.sendBroadcast(new Intent(ACTION_ADDED)
                    .setPackage(context.getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    static Bitmap drawableBitmap(Drawable drawable, int size) {
        if (drawable == null) return null;
        try {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(c);
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap bitmap, int size) {
        if (bitmap == null) return null;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) return null;
        float ratio = Math.max(size / (float) w, size / (float) h);
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        Bitmap out = Bitmap.createScaledBitmap(bitmap, nw, nh, true);
        int left = Math.max(0, (nw - size) / 2);
        int top = Math.max(0, (nh - size) / 2);
        return Bitmap.createBitmap(out, left, top,
                Math.min(size, nw), Math.min(size, nh));
    }
}
