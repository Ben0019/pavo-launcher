package com.ben.pavo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable snapshot of the launcher and the Pavo settings that shape it.
 *
 * The archive deliberately uses ordinary ZIP + JSON rather than an opaque
 * database so a backup remains inspectable. Live AppWidget host bindings are
 * owned by Android and are not serialisable; their placement and provider
 * information are preserved in the layout so they can be restored when the
 * same bindings still exist.
 */
final class SetupBackup {
    static final String MIME = "application/zip";
    static final int FORMAT = 1;

    private static final String APP_ID = "com.ben.pavo";
    private static final String MANIFEST = "manifest.json";
    private static final String PREFS = "prefs.json";
    private static final String FILES = "files/";
    private static final long MAX_ENTRY_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 32L * 1024L * 1024L;
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9._-]+");

    private static final String[] PREF_FILES = {
            Prefs.FILE,
            "aurora_tune",
            "aurora_fan_profiles",
            "aurora_touchmap",
            "aurora_game_refresh",
            "device_health"
    };

    private static final String[] FIXED_FILES = {
            "wallpaper.png",
            "dock_icon.png",
            "controller_map.conf"
    };

    private SetupBackup() {
    }

    static final class Snapshot {
        String archiveName;
        long exportedAt;
        int pageCount;
        int itemCount;
        int widgetCount;
        int customIconCount;
        int prefFileCount;
        JSONObject prefs;
        final Map<String, byte[]> files = new LinkedHashMap<>();
    }

    /**
     * Writes a complete setup archive into the public Downloads collection and
     * returns its display name.
     */
    static String export(Context context) throws Exception {
        Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
                .format(new Date(now));
        String fileName = "aurora-setup-" + stamp + ".zip";

        JSONObject prefsRoot = new JSONObject();
        JSONArray prefNames = new JSONArray();
        int prefCount = 0;
        for (String name : PREF_FILES) {
            SharedPreferences sp = app.getSharedPreferences(name, Context.MODE_PRIVATE);
            prefsRoot.put(name, encodePreferences(sp));
            prefNames.put(name);
            prefCount++;
        }

        List<String> fileNames = collectFiles(app);
        int[] counts = launcherCounts(
                app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE));
        int iconCount = 0;
        for (String name : fileNames) {
            if (name.startsWith("icon_")) iconCount++;
        }

        JSONObject manifest = new JSONObject();
        manifest.put("app", app.getPackageName());
        manifest.put("format", FORMAT);
        manifest.put("exportedAt", now);
        manifest.put("versionName", versionName(app));
        manifest.put("prefs", prefNames);
        JSONArray files = new JSONArray();
        for (String name : fileNames) files.put(name);
        manifest.put("files", files);
        manifest.put("pages", counts[0]);
        manifest.put("items", counts[1]);
        manifest.put("widgets", counts[2]);
        manifest.put("customIcons", iconCount);

        ContentResolver resolver = app.getContentResolver();
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, MIME);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Downloads collection unavailable");

            OutputStream raw = resolver.openOutputStream(uri);
            if (raw == null) throw new IOException("Could not open download");
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(raw))) {
                writeJson(zip, MANIFEST, manifest);
                writeJson(zip, PREFS, prefsRoot);
                for (String name : fileNames) {
                    File f = new File(app.getFilesDir(), name);
                    if (!f.isFile()) continue;
                    zip.putNextEntry(new ZipEntry(FILES + name));
                    try (InputStream in = new BufferedInputStream(
                            new FileInputStream(f))) {
                        copy(in, zip);
                    }
                    zip.closeEntry();
                }
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return fileName;
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            if (t instanceof Exception) throw (Exception) t;
            throw new IOException(t);
        }
    }

    /** Parses and validates a setup archive without modifying anything. */
    static Snapshot read(Context context, Uri uri) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0L;
        ContentResolver resolver = context.getContentResolver();
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) throw new IOException("Could not open backup");
            try (ZipInputStream zip = new ZipInputStream(
                    new BufferedInputStream(raw))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    byte[] data = readLimited(zip, MAX_ENTRY_BYTES);
                    total += data.length;
                    if (total > MAX_TOTAL_BYTES) {
                        throw new IOException("Backup is too large");
                    }
                    entries.put(entry.getName(), data);
                }
            }
        }
        if (entries.isEmpty()) throw new IOException("Backup is empty");

        byte[] manifestBytes = entries.get(MANIFEST);
        byte[] prefsBytes = entries.get(PREFS);
        if (manifestBytes == null || prefsBytes == null) {
            throw new IOException("Not an Pavo setup backup");
        }

        JSONObject manifest = new JSONObject(
                new String(manifestBytes, StandardCharsets.UTF_8));
        if (!APP_ID.equals(manifest.optString("app"))
                || manifest.optInt("format", 0) != FORMAT) {
            throw new IOException("Unsupported Pavo setup backup");
        }

        Snapshot snapshot = new Snapshot();
        snapshot.exportedAt = manifest.optLong("exportedAt", 0L);
        snapshot.pageCount = Math.max(0, manifest.optInt("pages", 0));
        snapshot.itemCount = Math.max(0, manifest.optInt("items", 0));
        snapshot.widgetCount = Math.max(0, manifest.optInt("widgets", 0));
        snapshot.customIconCount = Math.max(0, manifest.optInt("customIcons", 0));
        snapshot.prefs = new JSONObject(
                new String(prefsBytes, StandardCharsets.UTF_8));
        snapshot.prefFileCount = snapshot.prefs.length();

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(FILES)) continue;
            String fileName = name.substring(FILES.length());
            if (!isAllowedRestoreFile(fileName)) continue;
            snapshot.files.put(fileName, e.getValue());
        }
        snapshot.archiveName = snapshot.exportedAt > 0
                ? new SimpleDateFormat("MMM d, yyyy, HH:mm", Locale.US)
                        .format(new Date(snapshot.exportedAt))
                : "Pavo setup";
        return snapshot;
    }

    /**
     * Applies a previously parsed snapshot. Files are written before preferences
     * so a failure never leaves the launcher pointing at a half-imported icon.
     */
    static void apply(Context context, Snapshot snapshot) throws Exception {
        if (snapshot == null || snapshot.prefs == null) {
            throw new IOException("Nothing to restore");
        }
        Context app = context.getApplicationContext();
        File filesDir = app.getFilesDir();
        if (!filesDir.exists() && !filesDir.mkdirs()) {
            throw new IOException("App storage unavailable");
        }

        for (Map.Entry<String, byte[]> e : snapshot.files.entrySet()) {
            String name = e.getKey();
            if (!isAllowedRestoreFile(name)) continue;
            File target = new File(filesDir, name);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(e.getValue());
                out.flush();
            }
        }

        for (String name : PREF_FILES) {
            JSONObject values = snapshot.prefs.optJSONObject(name);
            if (values == null) continue;
            SharedPreferences sp = app.getSharedPreferences(name, Context.MODE_PRIVATE);
            SharedPreferences.Editor edit = sp.edit().clear();
            JSONArray keys = values.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.getString(i);
                    JSONObject encoded = values.optJSONObject(key);
                    if (encoded == null) continue;
                    putPreference(edit, key, encoded);
                }
            }
            if (!edit.commit()) {
                throw new IOException("Could not save " + name);
            }
        }
    }

    private static JSONObject encodePreferences(SharedPreferences sp) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object value = e.getValue();
            JSONObject item = new JSONObject();
            if (value instanceof Boolean) {
                item.put("t", "b");
                item.put("v", value);
            } else if (value instanceof Integer) {
                item.put("t", "i");
                item.put("v", value);
            } else if (value instanceof Long) {
                item.put("t", "l");
                item.put("v", value);
            } else if (value instanceof Float) {
                item.put("t", "f");
                float f = (Float) value;
                item.put("v", (Float.isNaN(f) || Float.isInfinite(f))
                        ? Float.toString(f) : f);
            } else if (value instanceof String) {
                item.put("t", "s");
                item.put("v", value);
            } else if (value instanceof Set) {
                item.put("t", "ss");
                JSONArray values = new JSONArray();
                for (Object v : (Set<?>) value) {
                    values.put(String.valueOf(v));
                }
                item.put("v", values);
            } else {
                item.put("t", "s");
                item.put("v", String.valueOf(value));
            }
            out.put(e.getKey(), item);
        }
        return out;
    }

    private static void putPreference(SharedPreferences.Editor edit, String key,
                                      JSONObject item) {
        String type = item.optString("t");
        switch (type) {
            case "b":
                edit.putBoolean(key, item.optBoolean("v"));
                break;
            case "i":
                edit.putInt(key, item.optInt("v"));
                break;
            case "l":
                edit.putLong(key, item.optLong("v"));
                break;
            case "f":
                edit.putFloat(key, (float) item.optDouble("v"));
                break;
            case "ss": {
                JSONArray values = item.optJSONArray("v");
                Set<String> set = new HashSet<>();
                if (values != null) {
                    for (int i = 0; i < values.length(); i++) {
                        set.add(values.optString(i));
                    }
                }
                edit.putStringSet(key, set);
                break;
            }
            case "s":
            default:
                edit.putString(key, item.optString("v"));
                break;
        }
    }

    private static List<String> collectFiles(Context context) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : FIXED_FILES) {
            File f = new File(context.getFilesDir(), name);
            if (f.isFile() && seen.add(name)) out.add(name);
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(
                    Prefs.FILE, Context.MODE_PRIVATE);
            String raw = sp.getString("custom", null);
            if (raw != null && !raw.isEmpty()) {
                JSONObject customs = new JSONObject(raw);
                JSONArray keys = customs.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        JSONObject item = customs.optJSONObject(keys.getString(i));
                        if (item == null) continue;
                        String icon = item.optString("i", "");
                        if (!icon.isEmpty() && isSafeFile(icon)
                                && new File(context.getFilesDir(), icon).isFile()
                                && seen.add(icon)) {
                            out.add(icon);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        File[] files = context.getFilesDir().listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (f.isFile() && name.startsWith("icon_") && name.endsWith(".png")
                        && isSafeFile(name) && seen.add(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static int[] launcherCounts(SharedPreferences sp) {
        int pages = 0;
        int items = 0;
        int widgets = 0;
        try {
            String raw = sp.getString("layout", null);
            if (raw != null && !raw.isEmpty()) {
                JSONObject root = new JSONObject(raw);
                JSONArray ps = root.optJSONArray("pages");
                if (ps != null) {
                    pages = ps.length();
                    for (int i = 0; i < ps.length(); i++) {
                        JSONArray page = ps.optJSONArray(i);
                        if (page == null) continue;
                        for (int j = 0; j < page.length(); j++) {
                            if (!page.isNull(j)) items++;
                        }
                    }
                }
                JSONArray ws = root.optJSONArray("widgets");
                if (ws != null) widgets = ws.length();
            }
        } catch (Throwable ignored) {
        }
        return new int[] {pages, items, widgets};
    }

    private static String versionName(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void writeJson(ZipOutputStream zip, String name, JSONObject value)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] data = value.toString().getBytes(StandardCharsets.UTF_8);
        zip.write(data);
        zip.closeEntry();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Backup entry is too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isAllowedRestoreFile(String name) {
        if (!isSafeFile(name)) return false;
        if (name.equals("wallpaper.png")
                || name.equals("dock_icon.png")
                || name.equals("controller_map.conf")) {
            return true;
        }
        return name.startsWith("icon_") && name.endsWith(".png");
    }

    private static boolean isSafeFile(String name) {
        return name != null && SAFE_FILE.matcher(name).matches()
                && !name.equals(".") && !name.equals("..");
    }
}
