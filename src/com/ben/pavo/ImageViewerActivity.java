package com.ben.pavo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Built-in image viewer with pinch zoom, pan and basic controls. */
public final class ImageViewerActivity extends PavoGlassActivity {
    static final String EXTRA_PATH = "image_viewer_path";

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;

    private Prefs prefs;
    private ControllerNav controllerNav;
    private TextView previousButton;
    private File file;
    private Bitmap bitmap;
    private ZoomImageView image;
    private TextView title;
    private TextView subtitle;
    private TextView status;
    private final List<File> siblings = new ArrayList<>();
    private int siblingIndex;
    private int loadToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) { finishAnimated(); return; }
        file = new File(path);
        if (!file.isFile()) { finishAnimated(); return; }
        buildSiblings();
        buildUi();
        hideBars();
        loadCurrent();
        installController();
    }

    private void installController() {
        controllerNav = new ControllerNav(this, getWindow().getDecorView());
        controllerNav.decorateFocusables();
        View first = previousButton;
        if (first == null) return;
        first.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    previousButton.requestFocus();
                    controllerNav.decorateFocusables();
                }
            }
        }, 180L);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BUTTON_B && down && event.getRepeatCount() == 0) {
            finishAnimated();
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1)
                && down && event.getRepeatCount() == 0) {
            showSibling(code == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
            return true;
        }
        if (controllerNav != null && controllerNav.dispatchKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (controllerNav != null && controllerNav.dispatchGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onPause() {
        if (controllerNav != null) controllerNav.cancel();
        super.onPause();
    }

    private void hideBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.statusBars());
            c.show(WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        WallpaperView wallpaper = new WallpaperView(this);
        wallpaper.setBlurRadius(prefs.blur());
        wallpaper.setMode(prefs.wallpaperMode());
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        GlassPanel sheet = new GlassPanel(this, null, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        sheet.setBackdropScreenAnchored(true);
        sheet.setPadding(dp(18), dp(10), dp(18), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, lp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), matchWrap());

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xE6000000);
        image = new ZoomImageView(this);
        image.setScaleType(ImageView.ScaleType.MATRIX);
        image.setBackgroundColor(0xE6000000);
        image.setSwipeListener(new ZoomImageView.SwipeListener() {
            @Override public void onSwipe(int direction) { showSibling(direction); }
        });
        frame.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(4);
        column.addView(frame, bodyLp);

        status = text("", 7.5f, MUTED, true);
        status.setPadding(dp(2), dp(5), dp(2), 0);
        column.addView(status, matchWrap());

        setContentView(root);
        applyWallpaperBackdrop(wallpaper, sheet);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, 0, 0, dp(2));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("IMAGE", 10f, 0xFFFFFFFF, false);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title = plainText(file.getName(), 14f, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle = plainText(file.getAbsolutePath(), 7.5f, MUTED, true);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titles.addView(eyebrow);
        titles.addView(title);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = closeButton();
        top.addView(close);
        header.addView(top, matchWrap());

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(0, dp(2), 0, 0);
        previousButton = toolButton("PREV", new Runnable() {
            @Override public void run() { showSibling(-1); }
        });
        tools.addView(previousButton, toolLp(0));
        tools.addView(toolButton("NEXT", new Runnable() {
            @Override public void run() { showSibling(1); }
        }), toolLp(5));
        tools.addView(toolButton("ZOOM OUT", new Runnable() {
            @Override public void run() { if (image != null) image.zoom(0.75f); }
        }), toolLp(5));
        tools.addView(toolButton("ZOOM IN", new Runnable() {
            @Override public void run() { if (image != null) image.zoom(1.33f); }
        }), toolLp(5));
        tools.addView(toolButton("RESET", new Runnable() {
            @Override public void run() { if (image != null) image.reset(); }
        }), toolLp(5));
        tools.addView(toolButton("SHARE", new Runnable() {
            @Override public void run() { share(); }
        }), toolLp(5));
        header.addView(tools, matchWrap());
        return header;
    }

    private TextView closeButton() {
        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(7), 0, dp(7), 0);
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishAnimated(); }
        });
        return close;
    }

    private LinearLayout.LayoutParams toolLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.leftMargin = dp(left);
        return lp;
    }

    private TextView toolButton(String label, final Runnable action) {
        TextView v = button(label, false);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
        v.setPadding(dp(8), 0, dp(8), 0);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        return v;
    }

    private void buildSiblings() {
        siblings.clear();
        File parent = file.getParentFile();
        File[] children = parent == null ? null : parent.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!isImage(child)) continue;
                if (child.getName().startsWith(".") && !child.equals(file)) continue;
                siblings.add(child);
            }
        }
        if (!siblings.contains(file)) siblings.add(file);
        Collections.sort(siblings, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        siblingIndex = siblings.indexOf(file);
        if (siblingIndex < 0) siblingIndex = 0;
    }

    private boolean isImage(File f) {
        if (f == null || !f.isFile()) return false;
        String name = f.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "bmp": case "heic": case "heif": case "avif":
                return true;
            default:
                String mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext);
                return mime != null && mime.startsWith("image/");
        }
    }

    private void showSibling(int delta) {
        if (siblings.isEmpty()) return;
        int next = siblingIndex + delta;
        if (next < 0 || next >= siblings.size()) return;
        siblingIndex = next;
        file = siblings.get(siblingIndex);
        updateHeader();
        if (image != null) image.reset();
        loadCurrent();
        Haptics.tick(image);
    }

    private void updateHeader() {
        if (title != null) title.setText(file.getName());
        if (subtitle != null) subtitle.setText(file.getAbsolutePath());
    }

    private void loadCurrent() {
        final File current = file;
        final int token = ++loadToken;
        final String path = current.getAbsolutePath();
        status.setText(Loc.s(this, "LOADING") + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap decoded = null;
                String error = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, bounds);
                    int sample = 1;
                    int largest = Math.max(bounds.outWidth, bounds.outHeight);
                    int target = Math.max(getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    while (largest / (sample * 2) >= target) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    decoded = BitmapFactory.decodeFile(path, opts);
                    if (decoded != null) {
                        int rotation = 0;
                        try {
                            ExifInterface exif = new ExifInterface(path);
                            int orientation = exif.getAttributeInt(
                                    ExifInterface.TAG_ORIENTATION,
                                    ExifInterface.ORIENTATION_NORMAL);
                            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                        } catch (Throwable ignored) {}
                        if (rotation != 0) {
                            Matrix matrix = new Matrix();
                            matrix.postRotate(rotation);
                            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0,
                                    decoded.getWidth(), decoded.getHeight(), matrix, true);
                            if (rotated != decoded) {
                                decoded.recycle();
                                decoded = rotated;
                            }
                        }
                    }
                } catch (Throwable t) {
                    error = t.getMessage();
                }
                final Bitmap result = decoded;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed() || token != loadToken) {
                            if (result != null && !result.isRecycled()) result.recycle();
                            return;
                        }
                        if (result == null) {
                            LToast.makeText(ImageViewerActivity.this,
                                    Loc.s(ImageViewerActivity.this, "Could not open image"),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Bitmap old = bitmap;
                        bitmap = result;
                        image.setBitmap(bitmap);
                        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
                        status.setText((siblingIndex + 1) + " / " + siblings.size()
                                + "  ·  " + formatLabel() + "  ·  " + bitmap.getWidth()
                                + " × " + bitmap.getHeight() + "  ·  " + formatBytes(current.length()));
                    }
                });
            }
        }, "ImageLoad").start();
    }

    private void share() {
        try {
            android.net.Uri uri = PavoFileProvider.uriFor(file);
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType(mimeOf(file))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, Loc.s(this, "SHARE")));
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "No app can share this file"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String formatLabel() {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toUpperCase(Locale.US) : "IMAGE";
    }

    private String mimeOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "image/*" : mime;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i]);
    }

    private void applyWallpaperBackdrop(final WallpaperView wp, final GlassPanel panel) {
        wp.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                Bitmap blurred = wp.getBlurred();
                if (blurred == null || blurred.isRecycled()
                        || wp.getWidth() <= 0 || wp.getHeight() <= 0) {
                    wp.postDelayed(this, 180L);
                    return;
                }
                Backdrop backdrop = new Backdrop(blurred, wp.getWidth(), wp.getHeight());
                panel.setBackdrop(backdrop);
                panel.setBackdropScreenAnchored(true);
                panel.setFrosted(true);
                panel.invalidate();
            }
        }, 220L);
    }

    @Override
    protected void onDestroy() {
        if (controllerNav != null) controllerNav.release();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private TextView plainText(String value, float sp, int color, boolean mono) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (mono) v.setTypeface(Typeface.MONOSPACE);
        return v;
    }

    private TextView button(String label, boolean primary) {
        TextView v = text(label, 9f, primary ? 0xFF071015 : 0xE6FFFFFF, true);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(Ui.rounded(primary ? ThemeColors.accent() : 0x22FFFFFF,
                dp(10), primary ? 0x00000000 : 0x44FFFFFF, dp(1)));
        return v;
    }

    /** Simple matrix image view: pinch to zoom and drag to pan. */
    private static final class ZoomImageView extends ImageView {
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float baseScale = 1f;
        private float lastX;
        private float lastY;
        private float downX;
        private float downY;
        private SwipeListener swipeListener;

        ZoomImageView(android.content.Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(c,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScale(ScaleGestureDetector detector) {
                            float factor = detector.getScaleFactor();
                            matrix.postScale(factor, factor,
                                    detector.getFocusX(), detector.getFocusY());
                            setImageMatrix(matrix);
                            return true;
                        }
                    });
        }

        interface SwipeListener {
            void onSwipe(int direction);
        }

        void setSwipeListener(SwipeListener listener) {
            swipeListener = listener;
        }

        float currentScale() {
            float[] values = new float[9];
            matrix.getValues(values);
            return values[0];
        }

        void setBitmap(Bitmap bitmap) {
            setImageBitmap(bitmap);
            post(new Runnable() {
                @Override public void run() { reset(); }
            });
        }

        void reset() {
            if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) return;
            float bw = getDrawable().getIntrinsicWidth();
            float bh = getDrawable().getIntrinsicHeight();
            if (bw <= 0 || bh <= 0) return;
            baseScale = Math.min(getWidth() / bw, getHeight() / bh);
            matrix.reset();
            matrix.postScale(baseScale, baseScale);
            matrix.postTranslate((getWidth() - bw * baseScale) / 2f,
                    (getHeight() - bh * baseScale) / 2f);
            setImageMatrix(matrix);
        }

        void zoom(float factor) {
            if (getDrawable() == null) return;
            matrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
            setImageMatrix(matrix);
        }

        private float dp(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    getResources().getDisplayMetrics());
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && currentScale() > baseScale * 1.01f) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        setImageMatrix(matrix);
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (currentScale() <= baseScale * 1.02f
                            && Math.abs(dx) > dp(52)
                            && Math.abs(dx) > Math.abs(dy) * 1.35f
                            && swipeListener != null) {
                        swipeListener.onSwipe(dx < 0 ? 1 : -1);
                        return true;
                    }
                    if (!scaleDetector.isInProgress() && currentScale() > baseScale * 1.01f) {
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
