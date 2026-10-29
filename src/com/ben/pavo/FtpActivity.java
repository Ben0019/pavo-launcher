package com.ben.pavo;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FTP share: host the phone's storage over FTP, or connect to another FTP
 * server and download from it.
 */
public final class FtpActivity extends PavoGlassActivity {

    private static final int TEXT = 0xF2FFFFFF;
    private static final int MUTED = 0x88FFFFFF;
    private static final int DIM = 0x66FFFFFF;
    private static final int OK = 0xFF69D98B;
    private static final int BAD = 0xFFFF6B6B;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> nav = new ArrayList<>();

    private Prefs prefs;
    private FtpClient client;
    private String clientPath = "/";

    private TextView serverTab;
    private TextView clientTab;
    private ScrollView serverScroll;
    private ScrollView clientScroll;

    private EditText portField;
    private EditText userField;
    private EditText passField;
    private TextView anonChip;
    private final TextView[] rootChips = new TextView[3];
    private TextView startButton;
    private TextView serverStatus;
    private TextView serverUrl;
    private TextView logView;

    private EditText hostField;
    private EditText remotePortField;
    private EditText remoteUserField;
    private EditText remotePassField;
    private TextView connectButton;
    private TextView clientStatus;
    private TextView pathLabel;
    private TextView upButton;
    private LinearLayout entryList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeColors.initialize(this);
        prefs = new Prefs(this);
        buildUi();
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        hidePerformanceOverlay();
        main.removeCallbacks(statusTick);
        main.post(statusTick);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(statusTick);
        super.onPause();
    }

    /** The server lives in a foreground service, so read its state instead of ours. */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            updateServerStatus();
            if (startButton != null) {
                startButton.setText(FtpService.isRunning() ? "Stop server" : "Start server");
            }
            if (logView != null) logView.setText(FtpService.logText());
            main.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onBackPressed() {
        closeFtp();
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.close();
            client = null;
        }
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void closeFtp() {
        finishAnimated();
    }

    private void hideBars() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(android.view.WindowInsets.Type.statusBars());
            c.show(android.view.WindowInsets.Type.navigationBars());
            c.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color, boolean mono) {
        TextView v = new LTextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(mono ? Typeface.MONOSPACE
                : Typeface.create("sans-serif", Typeface.NORMAL));
        return v;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(DIM);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        e.setInputType(inputType);
        e.setBackground(Ui.rounded(0x1AFFFFFF, dpf(10), 0x33FFFFFF, dpf(1)));
        e.setPadding(dp(12), dp(7), dp(12), dp(7));
        return e;
    }

    private float dpf(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = text(label, 10f, TEXT, false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        v.setFocusable(true);
        v.setBackground(Ui.rounded(0x24FFFFFF, dpf(11), 0x33FFFFFF, dpf(1)));
        v.setOnClickListener(click);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                view.setBackground(focused
                        ? Ui.focusRing(FtpActivity.this, dpf(11))
                        : Ui.rounded(0x24FFFFFF, dpf(11), 0x33FFFFFF, dpf(1)));
            }
        });
        addNav(v);
        return v;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setFocusableInTouchMode(true);

        GlassPanel sheet = new GlassPanel(this, null, ThemeColors.accent(), 0x4D0B0E14, 26, true);
        sheet.setClearGlass(true);
        sheet.setClearBodyAlpha(0xE6);
        sheet.setSurfaceAlpha(prefs.builtinTransparency() / 100f);
        sheet.setPadding(dp(20), dp(11), dp(20), dp(10));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        sheetLp.setMargins(dp(14), dp(10), dp(14), dp(10));
        root.addView(sheet, sheetLp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(7);
        column.addView(divider, dlp);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(7), 0, dp(2));
        serverTab = chip("Host", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(true);
            }
        });
        clientTab = chip("Connect", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTab(false);
            }
        });
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.rightMargin = dp(6);
        tabs.addView(serverTab, tabLp);
        tabs.addView(clientTab, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        serverScroll = buildServer();
        clientScroll = buildClient();
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = dp(4);
        column.addView(serverScroll, contentLp);
        column.addView(clientScroll, contentLp);

        setContentView(root);
        showTab(true);
        root.post(new Runnable() {
            @Override
            public void run() {
                if (serverTab != null) serverTab.requestFocus();
            }
        });
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("FTP", 12f, ThemeColors.accent(), false);
        title.setLetterSpacing(0.15f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = text("SHARE & BROWSE OVER THE NETWORK", 8.5f, MUTED, false);
        sub.setLetterSpacing(0.12f);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = text("\u00d7", 22f, 0xCCFFFFFF, false);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setFocusable(true);
        close.setBackground(Ui.rounded(0x00000000, dpf(12)));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                closeFtp();
            }
        });
        close.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                view.setBackground(focused ? Ui.focusRing(FtpActivity.this, dpf(12))
                        : Ui.rounded(0x00000000, dpf(12)));
            }
        });
        addNav(close);
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        return header;
    }

    // ------------------------------------------------------------------
    // Host
    // ------------------------------------------------------------------

    private ScrollView buildServer() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        body.addView(label("Port"), wrap());
        portField = field("2121", "2121", android.text.InputType.TYPE_CLASS_NUMBER);
        portField.setFocusable(true);
        body.addView(portField, new LinearLayout.LayoutParams(dp(140), dp(38)));

        body.addView(label("Access"), wrap());
        anonChip = chip("Anonymous: on", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !anonymous();
                prefs.setFtpAnonymous(on);
                userField.setVisibility(on ? View.GONE : View.VISIBLE);
                passField.setVisibility(on ? View.GONE : View.VISIBLE);
                styleAnonymous();
            }
        });
        body.addView(anonChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        userField = field("user", prefs.ftpUser(), android.text.InputType.TYPE_CLASS_TEXT);
        passField = field("password", prefs.ftpPassword(), android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        userField.setFocusable(true);
        passField.setFocusable(true);
        body.addView(userField, wrap());
        body.addView(passField, wrap());
        userField.setVisibility(anonymous() ? View.GONE : View.VISIBLE);
        passField.setVisibility(anonymous() ? View.GONE : View.VISIBLE);

        body.addView(label("Shared folders"), wrap());
        LinearLayout roots = new LinearLayout(this);
        roots.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Internal", "SD card", "Both"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            rootChips[i] = chip(names[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setFtpRoot(index);
                    styleRoots();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            roots.addView(rootChips[i], lp);
        }
        body.addView(roots, wrap());
        styleRoots();

        startButton = chip("Start server", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (FtpService.isRunning()) stopServer();
                else startServer();
            }
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(dp(190), dp(40));
        sbLp.topMargin = dp(10);
        body.addView(startButton, sbLp);

        serverStatus = text("Idle", 10f, MUTED, false);
        serverStatus.setPadding(0, dp(8), 0, 0);
        body.addView(serverStatus, wrap());

        serverUrl = text("", 12f, 0xFFFFFFFF, true);
        serverUrl.setPadding(0, dp(3), 0, 0);
        serverUrl.setTextIsSelectable(true);
        body.addView(serverUrl, wrap());

        TextView shareQr = chip("Share connection QR", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrDialog();
            }
        });
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        qrLp.topMargin = dp(8);
        body.addView(shareQr, qrLp);

        TextView hint = text("Keep this screen open while sharing. Browse with any FTP client on the same network, or scan the QR with another phone.",
                9.5f, DIM, false);
        hint.setPadding(0, dp(6), 0, dp(6));
        body.addView(hint, wrap());

        logView = text("", 9.5f, MUTED, true);
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView.setBackground(Ui.rounded(0x14FFFFFF, dpf(10), 0x22FFFFFF, dpf(1)));
        body.addView(logView, wrap());
        return scroll;
    }

    private boolean anonymous() {
        return prefs.ftpAnonymous();
    }

    private void styleAnonymous() {
        boolean on = anonymous();
        anonChip.setText(on ? "Anonymous: on" : "Anonymous: off");
        userField.setVisibility(on ? View.GONE : View.VISIBLE);
        passField.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void styleRoots() {
        int selected = prefs.ftpRoot();
        for (int i = 0; i < rootChips.length; i++) {
            TextView chip = rootChips[i];
            if (chip == null) continue;
            boolean on = i == selected;
            chip.setTextColor(on ? 0xFF10131A : TEXT);
            chip.setBackground(Ui.rounded(on ? ThemeColors.accent() : 0x24FFFFFF,
                    dpf(11), on ? 0x00000000 : 0x33FFFFFF, dpf(1)));
        }
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (Throwable t) {
            port = 2121;
        }
        port = Math.max(1024, Math.min(65535, port));
        portField.setText(String.valueOf(port));
        prefs.setFtpPort(port);
        prefs.setFtpUser(userField.getText().toString().trim());
        prefs.setFtpPassword(passField.getText().toString());

        boolean hasInternal = prefs.ftpRoot() == 0 || prefs.ftpRoot() == 2;
        if (!hasInternal && removableRoot() == null) {
            serverStatus.setText("No folder available to share");
            serverStatus.setTextColor(BAD);
            return;
        }
        FtpService.start(this);
        startButton.setText("Stop server");
        main.removeCallbacks(statusTick);
        main.postDelayed(statusTick, 250L);
    }

    private void stopServer() {
        FtpService.stop(this);
        startButton.setText("Start server");
        serverStatus.setText("Stopped");
        serverStatus.setTextColor(MUTED);
        serverUrl.setText("");
    }

    private void updateServerStatus() {
        if (!FtpService.isRunning()) {
            if (startButton != null && !"Start server".contentEquals(startButton.getText())) {
                startButton.setText("Start server");
            }
            return;
        }
        int clients = FtpService.clientCount();
        serverStatus.setText(clients == 0 ? "Sharing · waiting for clients"
                : "Sharing · " + clients + (clients == 1 ? " client" : " clients"));
        serverStatus.setTextColor(OK);
        String host = FtpServer.localAddress();
        serverUrl.setText(host == null ? "No network address" : host);
    }

    private void appendLog(String line) {
        if (logView == null) return;
        String current = logView.getText().toString();
        String[] lines = current.split("\n");
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, lines.length - 7);
        for (int i = start; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            out.append(lines[i]).append('\n');
        }
        out.append(line);
        logView.setText(out.toString());
    }

    private void showQrDialog() {
        // The floating performance card is a separate window above the activity
        // and would cover part of the code, so put it away while scanning.
        hidePerformanceOverlay();
        if (!FtpService.isRunning()) {
            LToast.makeText(this, "Start the server first", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String ip = FtpServer.localAddress();
        if (ip == null) {
            LToast.makeText(this, "No network address", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int port = prefs.ftpPort();
        final String url;
        if (anonymous()) {
            url = "ftp://" + ip + ":" + port + "/";
        } else {
            url = "ftp://" + encode(prefs.ftpUser()) + ":" + encode(prefs.ftpPassword())
                    + "@" + ip + ":" + port + "/";
        }
        final android.graphics.Bitmap qr = QrCode.render(url, dp(280),
                0xFF0B0E14, 0xFFFFFFFF);
        if (qr == null) {
            LToast.makeText(this, "Address is too long for a QR code",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        GlassPanel panel = new GlassPanel(this, null, ThemeColors.accent(),
                0xE60B0E14, 22, true);
        panel.setClearGlass(false);
        panel.setBackdropDarkness(0.35f);
        panel.setPadding(dp(22), dp(16), dp(22), dp(14));
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text("Scan to connect", 11f, ThemeColors.accent(), false);
        title.setLetterSpacing(0.14f);
        panel.addView(title, wrap());

        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageBitmap(qr);
        image.setAdjustViewBounds(true);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(280), dp(280));
        ilp.topMargin = dp(10);
        panel.addView(image, ilp);

        TextView address = text(url, 10.5f, 0xF2FFFFFF, true);
        address.setPadding(0, dp(10), 0, dp(4));
        address.setTextIsSelectable(true);
        address.setGravity(Gravity.CENTER);
        panel.addView(address, wrap());

        TextView note = text(anonymous()
                        ? "Anonymous access · open port " + port
                        : "User and password are inside the code",
                9f, MUTED, false);
        panel.addView(note, wrap());

        TextView close = chip("Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        clp.topMargin = dp(12);
        panel.addView(close, clp);

        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.55f;
            window.setAttributes(lp);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private static String encode(String value) {
        if (value == null) return "";
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return value;
        }
    }

    private File removableRoot() {
        try {
            File[] dirs = getExternalFilesDirs(null);
            if (dirs != null && dirs.length > 1 && dirs[1] != null) {
                // .../Android/data/<pkg>/files -> the mount point directly under /storage
                File mount = dirs[1];
                while (mount.getParentFile() != null
                        && !"/storage".equals(mount.getParentFile().getAbsolutePath())) {
                    mount = mount.getParentFile();
                }
                if (mount.isDirectory()) return mount;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Connect
    // ------------------------------------------------------------------

    private ScrollView buildClient() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        hostField = field("host or IP", prefs.ftpRemoteHost(),
                android.text.InputType.TYPE_CLASS_TEXT);
        remotePortField = field("port", String.valueOf(prefs.ftpRemotePort()),
                android.text.InputType.TYPE_CLASS_NUMBER);
        hostField.setFocusable(true);
        remotePortField.setFocusable(true);
        fields.addView(hostField, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(90), dp(38));
        plp.leftMargin = dp(6);
        fields.addView(remotePortField, plp);
        body.addView(fields, wrap());

        LinearLayout creds = new LinearLayout(this);
        creds.setOrientation(LinearLayout.HORIZONTAL);
        remoteUserField = field("user", prefs.ftpRemoteUser(), android.text.InputType.TYPE_CLASS_TEXT);
        remotePassField = field("password", prefs.ftpRemotePassword(),
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        remoteUserField.setFocusable(true);
        remotePassField.setFocusable(true);
        creds.addView(remoteUserField, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        clp.leftMargin = dp(6);
        creds.addView(remotePassField, clp);
        body.addView(creds, wrap());

        connectButton = chip("Connect", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (client != null && client.isConnected()) disconnect();
                else connectClient();
            }
        });
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(dp(150), dp(40));
        cbLp.topMargin = dp(8);
        body.addView(connectButton, cbLp);

        clientStatus = text("Not connected", 10f, MUTED, false);
        clientStatus.setPadding(0, dp(7), 0, dp(4));
        body.addView(clientStatus, wrap());

        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        upButton = chip("\u2191", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        pathLabel = text("/", 11f, TEXT, true);
        pathLabel.setPadding(dp(10), 0, 0, 0);
        pathRow.addView(upButton, new LinearLayout.LayoutParams(dp(44), dp(32)));
        pathRow.addView(pathLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(pathRow, wrap());

        entryList = new LinearLayout(this);
        entryList.setOrientation(LinearLayout.VERTICAL);
        body.addView(entryList, wrap());
        return scroll;
    }

    private void connectClient() {
        final String host = hostField.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(remotePortField.getText().toString().trim());
        } catch (Throwable t) {
            port = 21;
        }
        final int finalPort = Math.max(1, Math.min(65535, port));
        final String user = remoteUserField.getText().toString().trim();
        final String pass = remotePassField.getText().toString();
        prefs.setFtpRemote(host, finalPort, user, pass);
        clientStatus.setText("Connecting…");
        clientStatus.setTextColor(MUTED);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    FtpClient c = new FtpClient();
                    c.connect(host, finalPort, user, pass, 8000);
                    final List<FtpClient.Entry> entries = c.list("/");
                    client = c;
                    clientPath = "/";
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            connectButton.setText("DISCONNECT");
                            clientStatus.setText("Connected to " + host);
                            clientStatus.setTextColor(OK);
                            showEntries(entries);
                        }
                    });
                } catch (Throwable t) {
                    final String message = t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            clientStatus.setText("Connection failed: " + message);
                            clientStatus.setTextColor(BAD);
                        }
                    });
                }
            }
        });
    }

    private void disconnect() {
        if (client != null) client.close();
        client = null;
        connectButton.setText("CONNECT");
        clientStatus.setText("Not connected");
        clientStatus.setTextColor(MUTED);
        entryList.removeAllViews();
        pathLabel.setText("/");
    }

    private void goUp() {
        if (client == null || !client.isConnected()) return;
        if (clientPath.equals("/")) return;
        int slash = clientPath.lastIndexOf('/');
        String next = slash <= 0 ? "/" : clientPath.substring(0, slash);
        openPath(next);
    }

    private void openPath(final String path) {
        if (client == null || !client.isConnected()) return;
        clientStatus.setText("Reading " + path + "…");
        clientStatus.setTextColor(MUTED);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<FtpClient.Entry> entries = client.list(path);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            clientPath = path;
                            pathLabel.setText(path);
                            clientStatus.setText("Connected");
                            clientStatus.setTextColor(OK);
                            showEntries(entries);
                        }
                    });
                } catch (Throwable t) {
                    final String message = t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            clientStatus.setText("Could not read folder: " + message);
                            clientStatus.setTextColor(BAD);
                        }
                    });
                }
            }
        });
    }

    private void showEntries(List<FtpClient.Entry> entries) {
        entryList.removeAllViews();
        if (entries.isEmpty()) {
            entryList.addView(text("Empty folder", 10.5f, DIM, false), wrap());
            return;
        }
        for (final FtpClient.Entry entry : entries) {
            String label = entry.dir ? "\u25b8 " + entry.name
                    : entry.name + "   " + size(entry.size);
            TextView row = text(label, 10.5f, entry.dir ? 0xF2FFFFFF : 0xD9FFFFFF, false);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            final android.graphics.drawable.Drawable normal = Ui.rounded(0x10FFFFFF, dpf(9));
            row.setBackground(normal);
            row.setFocusable(true);
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean focused) {
                    v.setBackground(focused ? Ui.focusRing(FtpActivity.this, dpf(9)) : normal);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (entry.dir) {
                        openPath(join(clientPath, entry.name));
                    } else {
                        download(join(clientPath, entry.name), entry.name, entry.size);
                    }
                }
            });
            entryList.addView(row, wrap());
        }
    }

    private void download(final String remote, final String name, long size) {
        if (client == null || !client.isConnected()) return;
        final File target = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                name);
        clientStatus.setText("Downloading " + name + "…");
        clientStatus.setTextColor(MUTED);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    client.download(remote, target);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            clientStatus.setText("Saved to Download/" + name);
                            clientStatus.setTextColor(OK);
                        }
                    });
                } catch (Throwable t) {
                    final String message = t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            clientStatus.setText("Download failed: " + message);
                            clientStatus.setTextColor(BAD);
                        }
                    });
                }
            }
        });
    }

    private static String join(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024f);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }

    private void showTab(boolean host) {
        if (serverScroll == null || clientScroll == null) return;
        serverScroll.setVisibility(host ? View.VISIBLE : View.GONE);
        clientScroll.setVisibility(host ? View.GONE : View.VISIBLE);
        serverTab.setTextColor(host ? 0xFF10131A : TEXT);
        clientTab.setTextColor(host ? TEXT : 0xFF10131A);
        serverTab.setBackground(Ui.rounded(host ? ThemeColors.accent() : 0x24FFFFFF,
                dpf(11), host ? 0x00000000 : 0x33FFFFFF, dpf(1)));
        clientTab.setBackground(Ui.rounded(host ? 0x24FFFFFF : ThemeColors.accent(),
                dpf(11), host ? 0x33FFFFFF : 0x00000000, dpf(1)));
    }

    // ------------------------------------------------------------------
    // Controller
    // ------------------------------------------------------------------

    private void addNav(View v) {
        if (v == null) return;
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        nav.add(v);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_B && event.getAction() == KeyEvent.ACTION_UP) {
            closeFtp();
            return true;
        }
        boolean direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (direction && event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean forward = code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            View focused = getCurrentFocus();
            int index = nav.indexOf(focused);
            int next = Math.max(0, Math.min(nav.size() - 1,
                    (index < 0 ? 0 : index) + (forward ? 1 : -1)));
            View target = nav.get(next);
            if (target != null) {
                target.requestFocus();
                scrollTo(target);
            }
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER)
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused != null) focused.performClick();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void scrollTo(View view) {
        ScrollView scroll = serverScroll;
        if (clientScroll != null && clientScroll.getVisibility() == View.VISIBLE) scroll = clientScroll;
        if (scroll == null) return;
        android.graphics.Rect r = new android.graphics.Rect();
        view.getDrawingRect(r);
        try {
            scroll.offsetDescendantRectToMyCoords(view, r);
        } catch (Throwable ignored) {
            return;
        }
        int pad = dp(12);
        if (r.top < scroll.getScrollY() + pad) {
            scroll.smoothScrollTo(0, Math.max(0, r.top - pad));
        } else if (r.bottom > scroll.getScrollY() + scroll.getHeight() - pad) {
            scroll.smoothScrollTo(0, r.bottom - scroll.getHeight() + pad);
        }
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView label(String value) {
        TextView v = text(value, 8.5f, MUTED, false);
        v.setLetterSpacing(0.12f);
        v.setPadding(0, dp(8), 0, dp(2));
        return v;
    }
}
