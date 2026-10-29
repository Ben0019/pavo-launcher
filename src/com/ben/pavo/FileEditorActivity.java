package com.ben.pavo;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Built-in plain-text / XML viewer and editor. */
public final class FileEditorActivity extends PavoGlassActivity {
    static final String EXTRA_PATH = "file_editor_path";

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final long MAX_EDITABLE_BYTES = 4L * 1024L * 1024L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private ControllerNav controllerNav;
    private WallpaperView wallpaper;
    private GlassPanel sheet;
    private EditText editor;
    private TextView title;
    private TextView subtitle;
    private TextView status;
    private TextView saveButton;
    private TextView wrapButton;
    private File file;
    private String original = "";
    private boolean dirty;
    private boolean loading;
    private boolean wrap = true;
    private boolean readOnly;
    private Dialog openDialog;
    private CharSequence pendingText;
    private int pendingSelection;
    private boolean pendingDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ThemeColors.initialize(this);
        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || path.isEmpty()) {
            finishAnimated();
            return;
        }
        file = new File(path);
        if (!file.exists()) {
            finishAnimated();
            return;
        }
        if (savedInstanceState != null) {
            pendingText = savedInstanceState.getCharSequence("editor_text");
            pendingDirty = savedInstanceState.getBoolean("editor_dirty", false);
            pendingSelection = savedInstanceState.getInt("editor_selection", 0);
            wrap = savedInstanceState.getBoolean("editor_wrap", wrap);
        }
        readOnly = !file.canWrite();
        buildUi();
        hideBars();
        installController();
        loadText();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (editor != null) {
            outState.putCharSequence("editor_text", editor.getText());
            outState.putInt("editor_selection", editor.getSelectionStart());
            outState.putBoolean("editor_dirty", dirty);
            outState.putBoolean("editor_wrap", wrap);
        }
    }

    @Override
    protected void onPause() {
        if (controllerNav != null) controllerNav.cancel();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controllerNav != null) controllerNav.release();
        super.onDestroy();
    }

    private void installController() {
        controllerNav = new ControllerNav(this, getWindow().getDecorView());
        controllerNav.decorateFocusables();
        final View first = saveButton != null && saveButton.isEnabled()
                ? saveButton : wrapButton;
        if (first == null) return;
        first.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                first.requestFocus();
                controllerNav.decorateFocusables();
            }
        }, 180L);
    }

    @Override
    public void onBackPressed() {
        if (openDialog != null && openDialog.isShowing()) {
            openDialog.dismiss();
            return;
        }
        if (dirty) {
            showDiscardDialog();
        } else {
            finishAnimated();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            onBackPressed();
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
        wallpaper = new WallpaperView(this);
        wallpaper.setBlurRadius(prefs.blur());
        wallpaper.setMode(prefs.wallpaperMode());
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sheet = new GlassPanel(this, null, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0);
        sheet.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
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

        FrameLayout editorHolder = new FrameLayout(this);
        editorHolder.setPadding(dp(1), dp(3), dp(1), dp(1));
        editorHolder.setBackground(Ui.rounded(0x440B1218, dp(14), 0x22FFFFFF, dp(1)));
        editor = new EditText(this);
        editor.setTextColor(TEXT);
        editor.setHintTextColor(0x55FFFFFF);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(12), dp(9), dp(12), dp(9));
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(false);
        editor.setVerticalScrollBarEnabled(true);
        editor.setEnabled(!readOnly);
        editorHolder.addView(editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(4);
        column.addView(editorHolder, bodyLp);

        status = text("", 7.5f, MUTED, true);
        status.setPadding(dp(2), dp(5), dp(2), 0);
        column.addView(status, matchWrap());

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!loading) dirty = true;
                updateStatus();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        setContentView(root);
        applyWallpaperBackdrop(wallpaper, sheet);
        updateHeader();
        updateStatus();
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
        TextView eyebrow = text(isXml() ? "XML EDITOR" : "TEXT EDITOR",
                10f, 0xFFFFFFFF, false);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title = plainText(file.getName(), 14f, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle = plainText(file.getAbsolutePath(), 7.5f, MUTED, true);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(eyebrow);
        titles.addView(title);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = text("×", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setPadding(dp(7), 0, dp(7), 0);
        close.setMinWidth(dp(34));
        close.setMinHeight(dp(32));
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        top.addView(close);
        header.addView(top, matchWrap());

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(0, dp(2), 0, 0);
        saveButton = headerButton("SAVE", new Runnable() {
            @Override public void run() { saveText(); }
        });
        tools.addView(saveButton, headerLp(0));
        tools.addView(headerButton("RELOAD", new Runnable() {
            @Override public void run() { reloadRequested(); }
        }), headerLp(5));
        wrapButton = headerButton("WRAP", new Runnable() {
            @Override public void run() { toggleWrap(); }
        });
        tools.addView(wrapButton, headerLp(5));
        header.addView(tools, matchWrap());
        return header;
    }

    private LinearLayout.LayoutParams headerLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.leftMargin = dp(left);
        return lp;
    }

    private TextView headerButton(String label, final Runnable action) {
        TextView v = button(label, false);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
        v.setPadding(dp(8), 0, dp(8), 0);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        return v;
    }

    private void updateHeader() {
        if (title != null) title.setText(file.getName());
        if (subtitle != null) subtitle.setText(file.getAbsolutePath());
    }

    private void updateStatus() {
        if (status == null) return;
        String value = editor.getText().toString();
        int lines = value.isEmpty() ? 1 : 1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
        String modified = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                .format(new Date(file.lastModified()));
        status.setText(lines + " " + Loc.s(this, "LINES") + "  ·  "
                + value.length() + " " + Loc.s(this, "CHARS") + "  ·  UTF-8  ·  "
                + modified + (readOnly ? "  ·  " + Loc.s(this, "READ ONLY") : "")
                + (dirty ? "  ·  " + Loc.s(this, "UNSAVED") : ""));
        if (saveButton != null) {
            saveButton.setEnabled(!readOnly && dirty);
            saveButton.setAlpha(!readOnly && dirty ? 1f : 0.45f);
        }
        if (wrapButton != null) {
            wrapButton.setText(wrap ? "WRAP" : "NO WRAP");
        }
    }

    private void loadText() {
        loading = true;
        String error = null;
        String value = "";
        if (file.length() > MAX_EDITABLE_BYTES) {
            error = Loc.s(this, "File is too large to edit");
        } else {
            InputStream in = null;
            ByteArrayOutputStream out = null;
            try {
                in = new FileInputStream(file);
                out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                byte[] bytes = out.toByteArray();
                int start = 0;
                if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                        && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                    start = 3;
                }
                value = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                error = Loc.s(this, "Could not read file");
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
                try { if (out != null) out.close(); } catch (Throwable ignored) {}
            }
        }
        original = value;
        editor.setText(value);
        editor.setSelection(0);
        loading = false;
        dirty = false;
        editor.setEnabled(!readOnly);
        updateStatus();
        if (pendingText != null) {
            CharSequence draft = pendingText;
            pendingText = null;
            editor.setText(draft);
            int selection = Math.max(0, Math.min(pendingSelection,
                    editor.getText().length()));
            editor.setSelection(selection);
            dirty = pendingDirty;
            updateStatus();
        }
        if (error != null) {
            LToast.makeText(this, error, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void reloadRequested() {
        if (dirty) {
            showDiscardDialog(new Runnable() {
                @Override public void run() {
                    dirty = false;
                    loadText();
                }
            });
        } else {
            loadText();
        }
    }

    private void saveText() {
        if (readOnly) {
            LToast.makeText(this, Loc.s(this, "File is read only"),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            original = editor.getText().toString();
            dirty = false;
            updateStatus();
            setResult(RESULT_OK);
            LToast.makeText(this, Loc.s(this, "Saved"), android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            LToast.makeText(this, Loc.s(this, "Could not save file"),
                    android.widget.Toast.LENGTH_SHORT).show();
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private void toggleWrap() {
        wrap = !wrap;
        editor.setHorizontallyScrolling(!wrap);
        editor.setSingleLine(false);
        updateStatus();
    }

    private void showDiscardDialog() {
        showDiscardDialog(new Runnable() {
            @Override public void run() { finishAnimated(); }
        });
    }

    private void showDiscardDialog(final Runnable discard) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Backdrop backdrop;
        try {
            backdrop = Backdrop.capture(getWindow().getDecorView(), 12);
        } catch (Throwable t) {
            backdrop = null;
        }
        GlassPanel panel = new GlassPanel(this, backdrop, ThemeColors.accent(),
                0x800B0E14, 20, true);
        panel.setClearGlass(true);
        panel.setClearBodyAlpha(0xD8);
        panel.setSurfaceAlpha(prefs.settingsTransparency() / 100f);
        panel.setBackdropScreenAnchored(true);
        panel.setPadding(dp(20), dp(15), dp(20), dp(13));
        TextView heading = text("UNSAVED CHANGES", 15f, TEXT, false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(heading);
        TextView message = text("Discard changes to this file?", 9f, MUTED, false);
        message.setPadding(0, dp(5), 0, dp(10));
        panel.addView(message);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(Loc.s(this, "CANCEL"), false);
        TextView discardButton = button("DISCARD", true);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        discardButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); discard.run(); }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(36), 1f));
        LinearLayout.LayoutParams discardLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        discardLp.leftMargin = dp(8);
        buttons.addView(discardButton, discardLp);
        panel.addView(buttons, matchWrap());
        final ControllerNav dialogNav = new ControllerNav(this, null);
        ControllerNav.RootLayout dialogRoot = new ControllerNav.RootLayout(this);
        dialogRoot.setNavigator(dialogNav);
        dialogRoot.setFocusable(false);
        dialogRoot.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(dialogRoot);
        d.setCanceledOnTouchOutside(true);
        Window window = d.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32),
                    dp(400));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.52f;
            window.setAttributes(lp);
        }
        openDialog = d;
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) {
                dialogNav.release();
                if (openDialog == d) openDialog = null;
            }
        });
        d.show();
        dialogNav.attachDialog(d, panel);
        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface dialog,
                                           int keyCode, KeyEvent event) {
                if ((keyCode == KeyEvent.KEYCODE_BUTTON_B
                        || keyCode == KeyEvent.KEYCODE_BACK)
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    d.dismiss();
                    return true;
                }
                return false;
            }
        });
        if (d.getWindow() != null) {
            d.getWindow().getDecorView().postDelayed(new Runnable() {
                @Override public void run() {
                    if (d.isShowing() && !cancel.isFocused()) {
                        dialogNav.decorateFocusables();
                        cancel.requestFocus();
                    }
                }
            }, 260L);
        }
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

    private boolean isXml() {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".xml") || name.endsWith(".xsd") || name.endsWith(".svg");
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
}
