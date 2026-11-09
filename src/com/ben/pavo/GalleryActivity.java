package com.ben.pavo;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in gallery. Reads MediaStore first and falls back to a filesystem scan
 * for unindexed files, skipping hidden folders and any folder with a .nomedia
 * file. Photos and videos open in the File Manager's own viewer and player.
 */
public final class GalleryActivity extends PavoGlassActivity {
    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;

    private static final int TAB_FOLDERS = 0;
    private static final int TAB_PHOTOS = 1;
    private static final int TAB_VIDEOS = 2;
    private static final String[] TAB_KEYS = {
            "FOLDERS", "ALL PHOTOS", "ALL VIDEOS"
    };

    private static final String COL_BUCKET_ID = "bucket_id";
    private static final String COL_BUCKET_NAME = "bucket_display_name";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> cache;
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<String>());

    private Prefs prefs;
    private int accent;
    private ControllerNav nav;
    private TextView subtitle;
    private TextView status;
    private TextView backChip;
    private GridView grid;
    private final List<TextView> tabChips = new ArrayList<>();
    private TileAdapter adapter;

    private final List<MediaItem> allPhotos = new ArrayList<>();
    private final List<MediaItem> allVideos = new ArrayList<>();
    private final List<Folder> allFolders = new ArrayList<>();
    private final List<MediaItem> items = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();

    private int tab = TAB_FOLDERS;
    private String folderId;
    private String folderName;
    private int token;
    private boolean scanned;
    private long lastScan;

    public GalleryActivity() {
        cache = new LruCache<String, Bitmap>(cacheSize()) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    private static int cacheSize() {
        return (int) Math.max(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        accent = ThemeColors.accent();
        buildUi();
        hideBars();
        installController();
        requestMediaAccess();
        scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        if (scanned && System.currentTimeMillis() - lastScan > 4000L) scan();
    }

    @Override
    protected void onPause() {
        if (nav != null) nav.cancel();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        cache.evictAll();
        if (nav != null) nav.release();
        super.onDestroy();
    }

    private void hideBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsController c = window.getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void installController() {
        nav = new ControllerNav(this, getWindow().getDecorView());
        // Views stay focusable for the controller but not focusable in touch
        // mode, so a touch tap activates a tile immediately instead of only
        // giving it focus. The controller focuses the first view on its first
        // D-pad press.
    }

    @Override
    public void onBackPressed() {
        if (folderId != null) {
            folderId = null;
            populate();
            grid.setSelection(0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B)
                && down && event.getRepeatCount() == 0) {
            if (folderId != null) {
                folderId = null;
                populate();
                grid.setSelection(0);
            } else {
                finishAnimated();
            }
            return true;
        }
        if (nav != null && nav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (nav != null && nav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        GlassPanel card = new GlassPanel(this, null, accent, 0x4D0B0E14, 26, true);
        card.setClearGlass(true);
        card.setClearBodyAlpha(0);
        card.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        boolean tight = Ui.widthDp(this) < 700;
        card.setPadding(dp(tight ? 16 : 22), dp(tight ? 8 : 12),
                dp(tight ? 16 : 22), dp(tight ? 7 : 10));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        root.addView(card, cardLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(buildTabs(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(4), dp(4), dp(4), dp(2));

        backChip = text("\u2039", 13f, accent, true);
        backChip.setGravity(Gravity.CENTER);
        backChip.setFocusable(true);
        backChip.setPadding(dp(10), dp(2), dp(10), dp(2));
        backChip.setVisibility(View.GONE);
        backChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Haptics.click(v);
                folderId = null;
                populate();
                grid.setSelection(0);
            }
        });
        statusRow.addView(backChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = text("", 9f, MUTED, false);
        status.setPadding(dp(3), 0, dp(3), 0);
        statusRow.addView(status, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridView(this);
        int columns = Ui.widthDp(this) >= 700 ? 6 : 4;
        grid.setNumColumns(columns);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(4), 0, dp(4));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(true);
        grid.setFocusable(true);
        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(("Pavo " + Loc.s(this, "Gallery"))
                .toUpperCase(Locale.getDefault()), 20f, TEXT, true);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.05f);
        title.setGravity(Gravity.CENTER);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        subtitle = text(Loc.s(this, "PHOTOS & VIDEOS"), 9.5f, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(34);
        tlp.rightMargin = dp(34);
        header.addView(titles, tlp);

        TextView close = text("\u00d7", 24f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(true);
        close.setPadding(dp(10), 0, dp(2), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finishAnimated();
            }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.END;
        header.addView(close, clp);
        return header;
    }

    private View buildTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));
        for (int i = 0; i < TAB_KEYS.length; i++) {
            final int index = i;
            TextView chip = text(Loc.s(this, TAB_KEYS[i]), 8.5f, TEXT, true);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setFocusable(true);
            chip.setPadding(dp(2), dp(8), dp(2), dp(8));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    tab = index;
                    folderId = null;
                    populate();
                    grid.setSelection(0);
                }
            });
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    updateTabChip(index, hasFocus);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < TAB_KEYS.length - 1 ? dp(4) : 0;
            row.addView(chip, lp);
            tabChips.add(chip);
        }
        return row;
    }

    private void updateTabs() {
        for (int i = 0; i < tabChips.size(); i++) updateTabChip(i, tabChips.get(i).hasFocus());
    }

    private void updateTabChip(int index, boolean hasFocus) {
        if (index < 0 || index >= tabChips.size()) return;
        TextView chip = tabChips.get(index);
        boolean selected = tab == index && folderId == null;
        if (hasFocus) {
            chip.setTextColor(0xFF071018);
            chip.setBackground(Ui.rounded(accent, dp(10), accent, dp(1)));
        } else if (selected) {
            chip.setTextColor(0xFF071018);
            chip.setBackground(Ui.rounded(accent, dp(10), accent, dp(1)));
        } else {
            chip.setTextColor(TEXT);
            chip.setBackground(Ui.rounded(0x18FFFFFF, dp(10), 0x22FFFFFF, dp(1)));
        }
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(mono ? Typeface.MONOSPACE : Typeface.create("sans-serif", Typeface.NORMAL));
        return v;
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    // ------------------------------------------------------------------
    // Media scan
    // ------------------------------------------------------------------

    private void requestMediaAccess() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (Environment.isExternalStorageManager()) return;
        boolean images = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED;
        boolean videos = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                == PackageManager.PERMISSION_GRANTED;
        if (images && videos) return;
        List<String> request = new ArrayList<>();
        request.add(Manifest.permission.READ_MEDIA_IMAGES);
        request.add(Manifest.permission.READ_MEDIA_VIDEO);
        if (Build.VERSION.SDK_INT >= 34) {
            request.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        }
        requestPermissions(request.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) scan();
    }

    private void scan() {
        final int myToken = ++token;
        final Context context = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                Map<String, MediaItem> merged = new HashMap<>();
                for (MediaItem m : queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)) {
                    merged.put(m.path, m);
                }
                for (MediaItem m : queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)) {
                    merged.put(m.path, m);
                }
                File root = Environment.getExternalStorageDirectory();
                Set<String> visited = new HashSet<>();
                scanDir(new File(root, "DCIM"), 0, merged, visited);
                scanDir(new File(root, "Pictures"), 0, merged, visited);
                scanDir(new File(root, "Movies"), 0, merged, visited);
                scanDir(new File(root, "Download"), 0, merged, visited);
                scanDir(new File(root, "Documents"), 0, merged, visited);
                scanDir(new File(root, "Android/media"), 0, merged, visited);
                scanRootFiles(root, merged);

                List<MediaItem> photos = new ArrayList<>();
                List<MediaItem> videos = new ArrayList<>();
                for (MediaItem m : merged.values()) {
                    if (m.video) videos.add(m); else photos.add(m);
                }
                sortItems(photos);
                sortItems(videos);
                List<Folder> folders = buildFolders(photos, videos);
                final List<MediaItem> finalPhotos = photos;
                final List<MediaItem> finalVideos = videos;
                final List<Folder> finalFolders = folders;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (myToken != token || isFinishing() || isDestroyed()) return;
                        allPhotos.clear();
                        allPhotos.addAll(finalPhotos);
                        allVideos.clear();
                        allVideos.addAll(finalVideos);
                        allFolders.clear();
                        allFolders.addAll(finalFolders);
                        scanned = true;
                        lastScan = System.currentTimeMillis();
                        populate();
                    }
                });
            }
        });
    }

    private List<MediaItem> queryMedia(Uri collection, boolean video) {
        List<MediaItem> out = new ArrayList<>();
        String[] projection;
        if (video) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    COL_BUCKET_ID,
                    COL_BUCKET_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.Video.VideoColumns.DURATION
            };
        } else {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    COL_BUCKET_ID,
                    COL_BUCKET_NAME,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.MIME_TYPE
            };
        }
        try (Cursor c = getContentResolver().query(collection, projection,
                null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            int bucketCol = c.getColumnIndexOrThrow(COL_BUCKET_ID);
            int nameCol = c.getColumnIndexOrThrow(COL_BUCKET_NAME);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int durationCol = video
                    ? c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) : -1;
            while (c.moveToNext()) {
                String path = c.getString(dataCol);
                if (path == null || path.isEmpty()) continue;
                MediaItem m = new MediaItem();
                m.id = c.getLong(idCol);
                m.path = path;
                m.uri = ContentUris.withAppendedId(collection, m.id);
                m.bucketId = c.getString(bucketCol);
                if (m.bucketId == null || m.bucketId.isEmpty()) {
                    m.bucketId = new File(path).getParent();
                }
                m.bucketName = c.getString(nameCol);
                m.date = c.getLong(dateCol) * 1000L;
                m.video = video;
                m.name = new File(path).getName();
                m.durationMs = durationCol >= 0 ? c.getLong(durationCol) : 0;
                out.add(m);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void scanRootFiles(File root, Map<String, MediaItem> out) {
        File[] files = root.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) addFile(f, root, out);
        }
    }

    private void scanDir(File dir, int depth, Map<String, MediaItem> out, Set<String> visited) {
        if (dir == null || depth > 6) return;
        try {
            String canonical = dir.getCanonicalPath();
            if (!visited.add(canonical)) return;
        } catch (Throwable ignored) {
            return;
        }
        String name = dir.getName();
        if (name.startsWith(".")) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        // Android/data and Android/obb are app-private; media lives in Android/media.
        if (name.equals("Android")) {
            File media = new File(dir, "media");
            if (media.isDirectory()) scanDir(media, depth + 1, out, visited);
            return;
        }
        // A .nomedia file hides this folder and everything below it.
        if (new File(dir, ".nomedia").exists()) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDir(f, depth + 1, out, visited);
            } else if (f.isFile()) {
                addFile(f, dir, out);
            }
        }
    }

    private void addFile(File f, File dir, Map<String, MediaItem> out) {
        String lower = f.getName().toLowerCase(Locale.US);
        boolean video = lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".mov")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp")
                || lower.endsWith(".avi");
        boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".gif") || lower.endsWith(".bmp")
                || lower.endsWith(".heic") || lower.endsWith(".heif");
        if (!video && !image) return;
        String path = f.getAbsolutePath();
        if (out.containsKey(path)) return;
        MediaItem m = new MediaItem();
        m.path = path;
        m.uri = Uri.fromFile(f);
        m.video = video;
        m.date = f.lastModified();
        m.bucketId = dir.getAbsolutePath();
        m.bucketName = dir.getName();
        if (m.bucketName == null || m.bucketName.isEmpty()) m.bucketName = "Internal storage";
        m.name = f.getName();
        if (video) m.durationMs = videoDuration(path);
        out.put(path, m);
    }

    private static long videoDuration(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d);
        } catch (Throwable ignored) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sortItems(List<MediaItem> list) {
        Collections.sort(list, new Comparator<MediaItem>() {
            @Override public int compare(MediaItem a, MediaItem b) {
                return Long.compare(b.date, a.date);
            }
        });
    }

    private static List<Folder> buildFolders(List<MediaItem> photos, List<MediaItem> videos) {
        Map<String, Folder> map = new HashMap<>();
        for (MediaItem m : photos) addToFolder(map, m);
        for (MediaItem m : videos) addToFolder(map, m);
        List<Folder> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<Folder>() {
            @Override public int compare(Folder a, Folder b) {
                return Long.compare(b.date, a.date);
            }
        });
        return out;
    }

    private static void addToFolder(Map<String, Folder> map, MediaItem m) {
        String key = m.bucketId == null || m.bucketId.isEmpty() ? "unknown" : m.bucketId;
        Folder f = map.get(key);
        if (f == null) {
            f = new Folder();
            f.id = key;
            f.name = m.bucketName == null || m.bucketName.isEmpty()
                    ? "Internal storage" : m.bucketName;
            map.put(f.id, f);
        }
        f.count++;
        if (m.date >= f.date) {
            f.date = m.date;
            if (f.cover == null || m.date >= f.cover.date) f.cover = m;
        }
    }

    private void populate() {
        items.clear();
        folders.clear();
        if (folderId != null) {
            for (MediaItem m : allPhotos) if (folderId.equals(m.bucketId)) items.add(m);
            for (MediaItem m : allVideos) if (folderId.equals(m.bucketId)) items.add(m);
            sortItems(items);
            backChip.setVisibility(View.VISIBLE);
            status.setText(items.size() + " " + Loc.s(this, "items"));
        } else {
            backChip.setVisibility(View.GONE);
            if (tab == TAB_FOLDERS) {
                folders.addAll(allFolders);
                status.setText(folders.size() + " " + Loc.s(this, "folders"));
            } else if (tab == TAB_PHOTOS) {
                items.addAll(allPhotos);
                status.setText(items.size() + " " + Loc.s(this, "photos"));
            } else {
                items.addAll(allVideos);
                status.setText(items.size() + " " + Loc.s(this, "videos"));
            }
        }
        if (!scanned) status.setText(Loc.s(this, "Scanning\u2026"));
        else if (folders.isEmpty() && items.isEmpty()) status.setText(Loc.s(this, "No media"));
        adapter.notifyDataSetChanged();
        updateTabs();
    }

    private void open(MediaItem item) {
        Intent intent = new Intent(this,
                item.video ? VideoPlayerActivity.class : ImageViewerActivity.class);
        intent.putExtra(item.video
                ? VideoPlayerActivity.EXTRA_PATH : ImageViewerActivity.EXTRA_PATH, item.path);
        startActivity(intent);
    }

    // ------------------------------------------------------------------
    // Grid
    // ------------------------------------------------------------------

    private final class TileAdapter extends BaseAdapter {
        @Override public int getCount() {
            return folders.isEmpty() ? items.size() : folders.size();
        }

        @Override public Object getItem(int position) {
            return folders.isEmpty() ? items.get(position) : folders.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Tile tile = convertView instanceof Tile ? (Tile) convertView : new Tile();
            if (folders.isEmpty()) {
                tile.bindMedia(items.get(position));
            } else {
                tile.bindFolder(folders.get(position));
            }
            return tile;
        }
    }

    private final class Tile extends FrameLayout {
        private final ImageView image;
        private final TextView label;
        private final TextView badge;
        private final TextView play;
        private String boundKey = "";

        Tile() {
            super(GalleryActivity.this);
            setFocusable(true);
            setClipToOutline(true);
            setBackground(tileBackground(false));

            image = new ImageView(GalleryActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0x22000000);
            addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            label = new TextView(GalleryActivity.this);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
            label.setTextColor(0xF2FFFFFF);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.BOTTOM | Gravity.START);
            label.setPadding(dp(6), dp(4), dp(6), dp(4));
            label.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.gravity = Gravity.BOTTOM;
            addView(label, llp);

            badge = new TextView(GalleryActivity.this);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
            badge.setTextColor(0xFFFFFFFF);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            badge.setBackground(Ui.rounded(0xCC000000, dp(7)));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.TOP | Gravity.END;
            blp.setMargins(dp(6), dp(6), dp(6), dp(6));
            addView(badge, blp);

            play = new TextView(GalleryActivity.this);
            play.setText("\u25b6");
            play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            play.setTextColor(0xE6FFFFFF);
            play.setGravity(Gravity.CENTER);
            play.setShadowLayer(dp(3), 0, dp(1), 0xAA000000);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.gravity = Gravity.CENTER;
            addView(play, plp);

            setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(tileBackground(hasFocus));
                }
            });
        }

        private GradientDrawable tileBackground(boolean focused) {
            return Ui.rounded(focused ? 0x33FFFFFF : 0x18FFFFFF, dp(10),
                    focused ? accent : 0x22FFFFFF, dp(focused ? 2 : 1));
        }

        void bindFolder(final Folder f) {
            boundKey = "folder:" + f.id;
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    folderId = f.id;
                    folderName = f.name;
                    populate();
                    grid.setSelection(0);
                }
            });
            label.setVisibility(View.VISIBLE);
            label.setText(f.name);
            badge.setVisibility(f.count > 0 ? View.VISIBLE : View.GONE);
            badge.setText(String.valueOf(f.count));
            play.setVisibility(View.GONE);
            setContentDescription(f.name);
            if (f.cover != null) showThumb(f.cover, boundKey); else clearThumb();
        }

        void bindMedia(final MediaItem m) {
            boundKey = "media:" + m.path;
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Haptics.click(v);
                    open(m);
                }
            });
            label.setVisibility(View.GONE);
            play.setVisibility(m.video ? View.VISIBLE : View.GONE);
            if (m.video && m.durationMs > 0) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(formatDuration(m.durationMs));
            } else {
                badge.setVisibility(View.GONE);
            }
            setContentDescription(m.name == null ? "" : m.name);
            showThumb(m, boundKey);
        }

        private void clearThumb() {
            image.setTag("");
            image.setImageBitmap(null);
        }

        private void showThumb(MediaItem m, String key) {
            image.setTag(key);
            String cacheKey = m.path + "|" + (m.video ? 1 : 0);
            Bitmap cached = cache.get(cacheKey);
            if (cached != null) {
                image.setImageBitmap(cached);
                return;
            }
            image.setImageBitmap(null);
            if (!loading.add(cacheKey)) return;
            final ImageView target = image;
            final String tileKey = key;
            final MediaItem item = m;
            io.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap bmp = decodeThumb(item);
                    loading.remove(cacheKey);
                    if (bmp != null) cache.put(cacheKey, bmp);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (bmp != null && tileKey.equals(target.getTag())) {
                                target.setImageBitmap(bmp);
                            }
                        }
                    });
                }
            });
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
        }
    }

    private Bitmap decodeThumb(MediaItem m) {
        if (m.uri != null && "content".equals(m.uri.getScheme())) {
            try {
                Bitmap bmp = getContentResolver().loadThumbnail(
                        m.uri, new Size(320, 320), null);
                if (bmp != null) return bmp;
            } catch (Throwable ignored) {
            }
        }
        if (m.video) {
            Bitmap frame = videoFrame(m.path);
            return frame == null ? null : scaleDown(frame, 320);
        }
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(m.path, probe);
            int sample = 1;
            while ((probe.outWidth / sample) > 640 || (probe.outHeight / sample) > 640) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(m.path, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap videoFrame(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            Bitmap frame = r.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = r.getFrameAtTime(0);
            return frame;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap scaleDown(Bitmap source, int max) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= max) return source;
        float scale = max / (float) longest;
        Bitmap out = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), true);
        if (out != source) source.recycle();
        return out;
    }

    private static String formatDuration(long ms) {
        long total = ms / 1000L;
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static final class MediaItem {
        long id;
        Uri uri;
        String path;
        String bucketId;
        String bucketName;
        String name;
        long date;
        long durationMs;
        boolean video;
    }

    private static final class Folder {
        String id;
        String name;
        MediaItem cover;
        int count;
        long date;
    }
}
