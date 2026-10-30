package com.ben.pavo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal passive-mode FTP server.
 *
 * The virtual root lists each shared folder as a directory ("Internal",
 * "SD card"), so one session can browse every root without leaving the tree.
 */
final class FtpServer {

    interface Listener {
        void onLog(String line);
        void onClients(int count);
    }

    static final class Root {
        final String name;
        final File dir;
        final Prefs prefs;
        final String permKey;

        Root(String name, File dir, Prefs prefs, String permKey) {
            this.name = name;
            this.dir = dir;
            this.prefs = prefs;
            this.permKey = permKey;
        }

        /** Live read: flipping the toggle applies without restarting the server. */
        boolean writable() {
            return prefs == null || permKey == null || prefs.ftpShareWritable(permKey);
        }
    }

    private final int port;
    private final List<Root> roots;
    private final boolean anonymous;
    private final String user;
    private final String password;
    private final Listener listener;
    private final AtomicInteger clients = new AtomicInteger();

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    FtpServer(int port, List<Root> roots, boolean anonymous, String user, String password,
              Listener listener) {
        this.port = port;
        this.roots = roots;
        this.anonymous = anonymous;
        this.user = user == null ? "" : user;
        this.password = password == null ? "" : password;
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    int port() {
        return port;
    }

    int clientCount() {
        return clients.get();
    }

    /** Primary IPv4 address of an active interface, or null. */
    static String localAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name != null && name.startsWith("rmnet")) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address.isLoopbackAddress()) continue;
                    String host = address.getHostAddress();
                    if (host != null && host.indexOf(':') < 0) return host;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    boolean start() {
        if (running) return true;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port), 8);
            running = true;
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "ftp-accept");
            acceptThread.start();
            log("Listening on " + port);
            return true;
        } catch (Throwable t) {
            closeQuietly();
            log("Could not start: " + t.getMessage());
            return false;
        }
    }

    void stop() {
        running = false;
        closeQuietly();
        log("Stopped");
        if (listener != null) listener.onClients(0);
    }

    private void closeQuietly() {
        try {
            if (server != null) server.close();
        } catch (Throwable ignored) {
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                Session session = new Session(socket);
                clients.incrementAndGet();
                if (listener != null) listener.onClients(clients.get());
                new Thread(session, "ftp-client").start();
            } catch (Throwable t) {
                if (running) log("Accept failed: " + t.getMessage());
                break;
            }
        }
    }

    private void log(String line) {
        if (listener != null) listener.onLog(line);
    }

    private File resolve(String cwd, String path) {
        String combined = path == null || path.isEmpty() ? cwd
                : (path.startsWith("/") ? path : join(cwd, path));
        String[] parts = combined.split("/");
        if (parts.length < 2) return null;
        Root root = null;
        for (Root r : roots) {
            if (r.name.equals(parts[1])) {
                root = r;
                break;
            }
        }
        if (root == null) return null;
        File file = root.dir;
        for (int i = 2; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!file.equals(root.dir)) file = file.getParentFile();
                continue;
            }
            if (part.indexOf('\0') >= 0) return null;
            file = new File(file, part);
        }
        try {
            String canonical = file.getCanonicalPath();
            String base = root.dir.getCanonicalPath();
            if (!canonical.equals(base) && !canonical.startsWith(base + File.separator)) {
                return null;
            }
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String join(String a, String b) {
        if (a.endsWith("/")) return a + b;
        return a + "/" + b;
    }

    private final class Session implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private Writer out;
        private String cwd = "/";
        private ServerSocket passive;
        private java.net.InetSocketAddress active;
        private File renameFrom;
        private boolean loggedIn;

        Session(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(180_000);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                out = new OutputStreamWriter(socket.getOutputStream(), "UTF-8");
                reply("220 Pavo FTP ready");
                String line;
                while ((line = in.readLine()) != null) {
                    if (!handle(line)) break;
                }
            } catch (Throwable ignored) {
            } finally {
                closePassive();
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
                int now = clients.decrementAndGet();
                if (listener != null) listener.onClients(Math.max(0, now));
            }
        }

        private boolean handle(String raw) throws IOException {
            int space = raw.indexOf(' ');
            String cmd = (space < 0 ? raw : raw.substring(0, space)).trim().toUpperCase(Locale.ROOT);
            String arg = space < 0 ? "" : raw.substring(space + 1).trim();
            if (cmd.equals("QUIT")) {
                reply("221 Bye");
                return false;
            }
            if (cmd.equals("USER")) {
                if (anonymous) {
                    reply("331 Anonymous login ok, send any password");
                } else if (arg.equals(user)) {
                    reply("331 Password required");
                } else {
                    reply("530 Unknown user");
                }
                return true;
            }
            if (cmd.equals("PASS")) {
                if (anonymous || arg.equals(password)) {
                    loggedIn = true;
                    reply("230 Logged in");
                    log("Login from " + socket.getInetAddress().getHostAddress());
                } else {
                    reply("530 Login incorrect");
                }
                return true;
            }
            if (cmd.equals("SYST")) {
                reply("215 UNIX Type: L8");
                return true;
            }
            if (cmd.equals("FEAT")) {
                replyLines("211-Features:", " UTF8", " EPSV", " PASV", " SIZE", " MDTM",
                        " REST STREAM", "211 End");
                return true;
            }
            if (cmd.equals("OPTS")) {
                reply("200 OK");
                return true;
            }
            if (cmd.equals("NOOP")) {
                reply("200 OK");
                return true;
            }
            if (!loggedIn) {
                reply("530 Log in first");
                return true;
            }
            if (cmd.equals("TYPE")) {
                reply("200 Type set to " + (arg.isEmpty() ? "I" : arg));
                return true;
            }
            if (cmd.equals("PWD") || cmd.equals("XPWD")) {
                reply("257 \"" + cwd + "\" is the current directory");
                return true;
            }
            if (cmd.equals("CWD") || cmd.equals("XCWD")) {
                if (arg.isEmpty() || arg.equals("/")) {
                    cwd = "/";
                    reply("250 Directory changed to /");
                    return true;
                }
                File target = resolve(cwd, arg);
                if (target != null && target.isDirectory()) {
                    cwd = virtualPath(target);
                    reply("250 Directory changed to " + cwd);
                } else {
                    reply("550 No such directory");
                }
                return true;
            }
            if (cmd.equals("CDUP") || cmd.equals("XCUP")) {
                int slash = cwd.lastIndexOf('/');
                cwd = slash <= 0 ? "/" : cwd.substring(0, slash);
                reply("250 Directory changed to " + cwd);
                return true;
            }
            if (cmd.equals("PORT")) {
                try {
                    String[] p = arg.split(",");
                    if (p.length != 6) throw new NumberFormatException();
                    String host = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
                    int port = (Integer.parseInt(p[4]) << 8) + Integer.parseInt(p[5]);
                    active = new java.net.InetSocketAddress(host, port);
                    closePassive();
                    reply("200 Active mode");
                } catch (Throwable t) {
                    reply("501 Bad PORT");
                }
                return true;
            }
            if (cmd.equals("EPRT")) {
                try {
                    String[] p = arg.split("\\|");
                    if (p.length < 4) throw new NumberFormatException();
                    active = new java.net.InetSocketAddress(p[2], Integer.parseInt(p[3]));
                    closePassive();
                    reply("200 Active mode");
                } catch (Throwable t) {
                    reply("501 Bad EPRT");
                }
                return true;
            }
            if (cmd.equals("PASV")) {
                openPassive(false);
                return true;
            }
            if (cmd.equals("EPSV")) {
                openPassive(true);
                return true;
            }
            if (cmd.equals("LIST") || cmd.equals("NLST")) {
                String path = arg;
                while (path.startsWith("-")) {
                    int flagEnd = path.indexOf(' ');
                    path = flagEnd < 0 ? "" : path.substring(flagEnd + 1).trim();
                }
                list(cmd.equals("NLST"), path);
                return true;
            }
            if (cmd.equals("MLSD")) {
                listMlsd(arg);
                return true;
            }
            if (cmd.equals("RETR")) {
                transfer(resolve(cwd, arg), true);
                return true;
            }
            if (cmd.equals("STOR")) {
                File target = resolve(cwd, arg);
                if (!writable(target)) {
                    reply("550 Read-only storage");
                    return true;
                }
                transfer(target, false);
                return true;
            }
            if (cmd.equals("SIZE")) {
                File f = resolve(cwd, arg);
                reply(f != null && f.isFile() ? "213 " + f.length() : "550 Not a file");
                return true;
            }
            if (cmd.equals("MDTM")) {
                File f = resolve(cwd, arg);
                if (f != null && f.isFile()) {
                    reply("213 " + timestamp(f.lastModified()));
                } else {
                    reply("550 Not a file");
                }
                return true;
            }
            if (cmd.equals("DELE")) {
                File f = resolve(cwd, arg);
                if (!writable(f)) {
                    reply("550 Read-only storage");
                    return true;
                }
                reply(f != null && f.isFile() && f.delete() ? "250 Deleted" : "550 Delete failed");
                return true;
            }
            if (cmd.equals("MKD") || cmd.equals("XMKD")) {
                File f = resolve(cwd, arg);
                if (!writable(f)) {
                    reply("550 Read-only storage");
                    return true;
                }
                if (f != null && f.mkdirs()) {
                    reply("257 \"" + virtualPath(f) + "\" created");
                } else {
                    reply("550 Could not create folder");
                }
                return true;
            }
            if (cmd.equals("RMD") || cmd.equals("XRMD")) {
                File f = resolve(cwd, arg);
                if (!writable(f)) {
                    reply("550 Read-only storage");
                    return true;
                }
                reply(f != null && f.isDirectory() && f.delete()
                        ? "250 Removed" : "550 Could not remove");
                return true;
            }
            if (cmd.equals("RNFR")) {
                File f = resolve(cwd, arg);
                if (!writable(f)) {
                    reply("550 Read-only storage");
                    return true;
                }
                if (f != null && f.exists()) {
                    renameFrom = f;
                    reply("350 Ready for destination name");
                } else {
                    reply("550 No such file");
                }
                return true;
            }
            if (cmd.equals("RNTO")) {
                File to = resolve(cwd, arg);
                if (!writable(to)) {
                    reply("550 Read-only storage");
                    renameFrom = null;
                    return true;
                }
                if (renameFrom != null && to != null && renameFrom.renameTo(to)) {
                    reply("250 Renamed");
                } else {
                    reply("550 Rename failed");
                }
                renameFrom = null;
                return true;
            }
            reply("502 Command not implemented");
            return true;
        }

        private void openPassive(boolean extended) throws IOException {
            closePassive();
            passive = new ServerSocket();
            passive.setReuseAddress(true);
            InetAddress local = socket.getLocalAddress();
            passive.bind(new InetSocketAddress(local, 0), 1);
            passive.setSoTimeout(15_000);
            int p = passive.getLocalPort();
            if (extended) {
                reply("229 Entering Extended Passive Mode (|||" + p + "|)");
            } else {
                byte[] a = local.getAddress();
                reply("227 Entering Passive Mode (" + (a[0] & 0xFF) + "," + (a[1] & 0xFF) + ","
                        + (a[2] & 0xFF) + "," + (a[3] & 0xFF) + ","
                        + (p >> 8) + "," + (p & 0xFF) + ")");
            }
        }

        private Socket acceptPassive() throws IOException {
            if (active != null) {
                java.net.InetSocketAddress target = active;
                active = null;
                Socket data = new Socket();
                data.connect(target, 10_000);
                return data;
            }
            if (passive == null) throw new IOException("No passive socket");
            Socket data = passive.accept();
            closePassive();
            return data;
        }

        /** Machine-readable listing used by FileZilla, WinSCP and friends. */
        private void listMlsd(String arg) throws IOException {
            boolean atRoot = cwd.equals("/") && (arg.isEmpty() || arg.equals("/"));
            File dir = atRoot ? null : resolve(cwd, arg);
            if (!atRoot && (dir == null || !dir.isDirectory())) {
                reply("550 No such directory");
                return;
            }
            reply("150 Opening data connection");
            try (Socket data = acceptPassive(); Writer w = new OutputStreamWriter(
                    data.getOutputStream(), "UTF-8")) {
                if (atRoot) {
                    for (Root root : roots) {
                        w.write("type=dir;modify=" + timestamp(root.dir.lastModified())
                                + "; " + root.name + "\r\n");
                    }
                } else {
                    File[] children = dir.listFiles();
                    if (children != null) {
                        List<File> sorted = new ArrayList<>();
                        Collections.addAll(sorted, children);
                        Collections.sort(sorted, (a, b) -> {
                            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        });
                        for (File child : sorted) {
                            w.write("type=" + (child.isDirectory() ? "dir" : "file")
                                    + ";size=" + child.length()
                                    + ";modify=" + timestamp(child.lastModified())
                                    + "; " + child.getName() + "\r\n");
                        }
                    }
                }
                w.flush();
            } catch (Throwable t) {
                reply("425 Data connection failed");
                return;
            }
            reply("226 Transfer complete");
        }

        private void closePassive() {
            try {
                if (passive != null) passive.close();
            } catch (Throwable ignored) {
            }
            passive = null;
        }

        private void list(boolean namesOnly, String arg) throws IOException {
            boolean atRoot = cwd.equals("/") && (arg.isEmpty() || arg.equals("/")
                    || arg.equals("."));
            File dir = atRoot ? null : resolve(cwd, arg);
            if (!atRoot && (dir == null || !dir.isDirectory())) {
                reply("550 No such directory");
                return;
            }
            reply("150 Opening data connection");
            try (Socket data = acceptPassive(); Writer w = new OutputStreamWriter(
                    data.getOutputStream(), "UTF-8")) {
                if (atRoot) {
                    for (Root root : roots) {
                        w.write(namesOnly ? root.name + "\r\n"
                                : line("drwxr-xr-x", 0, root.dir.lastModified(), root.name));
                    }
                } else {
                    File[] children = dir.listFiles();
                    if (children != null) {
                        List<File> sorted = new ArrayList<>();
                        Collections.addAll(sorted, children);
                        Collections.sort(sorted, (a, b) -> {
                            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        });
                        for (File child : sorted) {
                            w.write(namesOnly ? child.getName() + "\r\n"
                                    : line(child.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--",
                                    child.length(), child.lastModified(), child.getName()));
                        }
                    }
                }
                w.flush();
            } catch (Throwable t) {
                reply("425 Data connection failed");
                return;
            }
            reply("226 Transfer complete");
        }

        private String line(String mode, long size, long modified, String name) {
            // Names keep their spaces: LIST is whitespace-delimited only through
            // the eighth field, so the rest of the line is the name verbatim.
            return mode + " 1 ftp ftp " + size + " " + date(modified) + " "
                    + name + "\r\n";
        }

        private void transfer(File file, boolean download) throws IOException {
            if (file == null) {
                reply("550 Invalid path");
                return;
            }
            if (download && !file.isFile()) {
                reply("550 Not a file");
                return;
            }
            reply("150 Opening data connection");
            try (Socket data = acceptPassive()) {
                if (download) {
                    try (InputStream in = new FileInputStream(file);
                         OutputStream out = data.getOutputStream()) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                } else {
                    File parent = file.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (InputStream in = data.getInputStream();
                         OutputStream out = new FileOutputStream(file)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            } catch (Throwable t) {
                reply("426 Transfer aborted");
                return;
            }
            reply("226 Transfer complete");
        }

        private boolean writable(File file) {
            if (file == null) return false;
            try {
                String path = file.getCanonicalPath();
                for (Root r : roots) {
                    String base = r.dir.getCanonicalPath();
                    if (path.equals(base) || path.startsWith(base + "/")) return r.writable();
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        private String virtualPath(File file) {
            for (Root root : roots) {
                try {
                    String base = root.dir.getCanonicalPath();
                    String canonical = file.getCanonicalPath();
                    if (canonical.equals(base)) return "/" + root.name;
                    if (canonical.startsWith(base + File.separator)) {
                        return "/" + root.name + canonical.substring(base.length())
                                .replace(File.separatorChar, '/');
                    }
                } catch (Throwable ignored) {
                }
            }
            return "/";
        }

        private String date(long millis) {
            SimpleDateFormat f = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
            return f.format(new Date(millis));
        }

        private String timestamp(long millis) {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.format(new Date(millis));
        }

        private void reply(String line) {
            try {
                out.write(line + "\r\n");
                out.flush();
            } catch (Throwable ignored) {
            }
        }

        private void replyLines(String... lines) {
            for (String line : lines) reply(line);
        }
    }
}
