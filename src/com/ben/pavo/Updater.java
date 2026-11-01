package com.ben.pavo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Over-the-air updates from the latest GitHub release. */
final class Updater {
    private static final String REPO = "Ben0019/pavo-launcher";
    private static final String API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ASSET = "Pavo.apk";
    private static final String PREFS = "pavo_update";
    private static final String KEY_LAST_CHECK = "last_check";
    // Interval is user-selectable (2-24 h); see Prefs.updateCheckHours().
    private static final String CHANNEL = "aurora_update";
    private static final String KEY_AVAILABLE = "available";
    private static final String KEY_TAG = "tag";

    private static volatile boolean available;
    private static volatile boolean cancelDownload;
    private static volatile boolean downloadCancelled;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private Updater() {}

    /** True when the last check found a newer release; drives the icon badge. */
    static boolean isUpdateAvailable() {
        return available;
    }

    /** Restore the badge state from prefs without touching the network. */
    static void refreshState(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tag = sp.getString(KEY_TAG, null);
        if (tag == null || tag.isEmpty()) {
            available = false;
            return;
        }
        available = compare(tag.replaceFirst("^v", ""), versionName(app)) > 0;
    }

    static void setAvailable(Context context, boolean on, String tag) {
        available = on;
        Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit().putBoolean(KEY_AVAILABLE, on);
        if (tag == null) e.remove(KEY_TAG);
        else e.putString(KEY_TAG, tag);
        e.apply();
    }

    static final class Check {
        String tag;
        String name;
        String url;
        String digest;
        String body;
    }

    /** Runs a check/install off the main thread; safe to call repeatedly. */
    static void checkAndInstall(final Context context) {
        final Context app = context.getApplicationContext();
        LToast.makeText(app, "Checking GitHub for updates…", Toast.LENGTH_SHORT).show();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Check check = latest();
                    if (check == null) {
                        toast(app, "Could not read the latest release");
                        return;
                    }
                    boolean fresh = isNewer(app, check);
                    setAvailable(app, fresh, check.tag);
                    if (!fresh) {
                        toast(app, "Pavo is up to date (" + versionName(app) + ")");
                        return;
                    }
                    toast(app, "Downloading " + check.tag + "…");
                    File apk = download(app, check, null);
                    if (apk == null) {
                        toast(app, "Download failed");
                        return;
                    }
                    toast(app, "Installing " + check.tag + "…");
                    install(app, apk);
                } catch (Throwable t) {
                    toast(app, "Update failed: " + t.getMessage());
                }
            }
        });
    }

    /** Silent once-a-day check; only notifies when something is available. */
    static void checkDaily(final Context context) {
        final Context app = context.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = sp.getLong(KEY_LAST_CHECK, 0L);
        long every = new Prefs(app).updateCheckHours() * 60L * 60L * 1000L;
        if (System.currentTimeMillis() - last < every) return;
        sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Check check = latest();
                    boolean fresh = check != null && isNewer(app, check);
                    if (check != null) setAvailable(app, fresh, check.tag);
                    if (fresh) notifyAvailable(app, check);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void notifyAvailable(Context context, Check check) {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    Loc.s(context, "App updates"), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(Loc.s(context, "New Pavo releases from GitHub"));
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(context, MainActivity.class)
                .putExtra("pavo_check_updates", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 71, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Pavo " + check.tag.replaceFirst("^v", "") + " is available")
                .setContentText("Tap to download and install")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);
        try {
            nm.notify(0x55504441, b.build());
        } catch (Throwable ignored) {
        }
    }

    static Check latest() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        con.setConnectTimeout(12000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "Pavo-Updater");
        try {
            if (con.getResponseCode() != 200) return null;
            String body = readAll(con.getInputStream());
            JSONObject json = new JSONObject(body);
            JSONArray assets = json.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!ASSET.equals(asset.optString("name"))) continue;
                Check c = new Check();
                c.tag = json.optString("tag_name", "");
                c.name = json.optString("name", c.tag);
                c.url = asset.optString("browser_download_url", "");
                c.digest = asset.optString("digest", "");
                c.body = json.optString("body", "");
                return c.url.isEmpty() ? null : c;
            }
            return null;
        } finally {
            con.disconnect();
        }
    }

    static boolean isNewer(Context context, Check check) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            String remote = check.tag.replaceFirst("^v", "");
            int cmp = compare(remote, pi.versionName);
            if (cmp != 0) return cmp > 0;
            // The tag can stay the same across rebuilds; fall back to the APK.
            String local = sha256(new File(pi.applicationInfo.sourceDir));
            String want = check.digest != null && check.digest.startsWith("sha256:")
                    ? check.digest.substring(7) : null;
            return local != null && want != null && !local.equalsIgnoreCase(want);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int compare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? digits(pa[i]) : 0;
            int vb = i < pb.length ? digits(pb[i]) : 0;
            if (va != vb) return va < vb ? -1 : 1;
        }
        return 0;
    }

    private static int digits(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void cancelDownload() {
        cancelDownload = true;
    }

    static boolean wasDownloadCancelled() {
        return downloadCancelled;
    }

    static File download(Context context, Check check, Progress progress) throws Exception {
        cancelDownload = false;
        downloadCancelled = false;
        File dir = context.getCacheDir();
        File out = new File(dir, "pavo-update.apk");
        HttpURLConnection con = (HttpURLConnection) new URL(check.url).openConnection();
        con.setConnectTimeout(12000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "Pavo-Updater");
        InputStream in = null;
        OutputStream os = null;
        try {
            if (con.getResponseCode() != 200) return null;
            long total = con.getContentLengthLong();
            in = con.getInputStream();
            os = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelDownload) {
                    downloadCancelled = true;
                    break;
                }
                os.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (os != null) try { os.close(); } catch (Throwable ignored) {}
            con.disconnect();
        }
        if (downloadCancelled) {
            out.delete();
            return null;
        }
        String want = check.digest != null && check.digest.startsWith("sha256:")
                ? check.digest.substring(7) : null;
        String got = sha256(out);
        if (want != null && got != null && !got.equalsIgnoreCase(want)) {
            out.delete();
            throw new IllegalStateException("checksum mismatch");
        }
        return out;
    }

    /** Silent install when PServer is available, otherwise the system prompt. */
    static void install(Context context, File apk) {
        if (PServer.available()) {
            // PServer is fire-and-forget; `pm install` replaces the app and the
            // process is killed by the installer, so nothing needs the result.
            PServer.exec("cp " + apk.getAbsolutePath()
                    + " /data/local/tmp/pavo-update.apk; pm install -r"
                    + " /data/local/tmp/pavo-update.apk", 120000L);
            return;
        }
        try {
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            int id = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(id);
            try {
                InputStream in = new FileInputStream(apk);
                OutputStream out = session.openWrite("base.apk", 0, apk.length());
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
                in.close();
                out.close();
                Intent result = new Intent(context, UpdateReceiver.class)
                        .setAction(UpdateReceiver.ACTION_RESULT)
                        .putExtra(UpdateReceiver.EXTRA_SESSION, id);
                PendingIntent pending = PendingIntent.getBroadcast(context, id, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
            } finally {
                session.close();
            }
        } catch (Throwable t) {
            toast(context, "Install failed: " + t.getMessage());
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private static void toast(Context c, String text) {
        try {
            LToast.makeText(c, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(file);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
