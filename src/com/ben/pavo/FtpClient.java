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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small passive-mode FTP client: browse a remote tree and download files. */
final class FtpClient {

    static final class Entry {
        final String name;
        final long size;
        final boolean dir;

        Entry(String name, long size, boolean dir) {
            this.name = name;
            this.size = size;
            this.dir = dir;
        }
    }

    private Socket control;
    private BufferedReader in;
    private Writer out;
    private int lastCode;

    void connect(String host, int port, String user, String password, int timeoutMs)
            throws IOException {
        control = new Socket();
        control.connect(new InetSocketAddress(host, port), timeoutMs);
        control.setSoTimeout(timeoutMs);
        in = new BufferedReader(new InputStreamReader(control.getInputStream(), "UTF-8"));
        out = new OutputStreamWriter(control.getOutputStream(), "UTF-8");
        if (readReply() / 100 != 2) throw new IOException("Server did not greet");
        String u = user == null || user.isEmpty() ? "anonymous" : user;
        String p = password == null || password.isEmpty() ? "anonymous@" : password;
        command("USER " + u);
        if (lastCode == 331 || lastCode == 332) command("PASS " + p);
        if (lastCode / 100 != 2) throw new IOException("Login failed (" + lastCode + ")");
        command("TYPE I");
        command("OPTS UTF8 ON");
    }

    boolean isConnected() {
        return control != null && control.isConnected() && !control.isClosed();
    }

    List<Entry> list(String path) throws IOException {
        Socket data = passive();
        send("LIST " + path);
        int code = readReply();
        if (code / 100 != 1 && code / 100 != 2) {
            closeQuietly(data);
            throw new IOException("LIST failed (" + code + ")");
        }
        List<Entry> entries = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(data.getInputStream(), "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            Entry entry = parse(line);
            if (entry != null) entries.add(entry);
        }
        closeQuietly(data);
        readReply();
        return entries;
    }

    void download(String remote, File local) throws IOException {
        Socket data = passive();
        send("RETR " + remote);
        int code = readReply();
        if (code / 100 != 1) {
            closeQuietly(data);
            throw new IOException("RETR failed (" + code + ")");
        }
        File parent = local.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream input = data.getInputStream();
             OutputStream output = new FileOutputStream(local)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        } catch (Throwable t) {
            closeQuietly(data);
            throw t;
        }
        closeQuietly(data);
        readReply();
    }

    void upload(File local, String remote) throws IOException {
        Socket data = passive();
        send("STOR " + remote);
        int code = readReply();
        if (code / 100 != 1) {
            closeQuietly(data);
            throw new IOException("STOR failed (" + code + ")");
        }
        try (InputStream input = new FileInputStream(local);
             OutputStream output = data.getOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        } catch (Throwable t) {
            closeQuietly(data);
            throw t;
        }
        closeQuietly(data);
        readReply();
    }

    void deleteFile(String path) throws IOException {
        command("DELE " + path);
        if (lastCode / 100 != 2) throw new IOException("DELE failed (" + lastCode + ")");
    }

    void deleteDir(String path) throws IOException {
        command("RMD " + path);
        if (lastCode / 100 != 2) throw new IOException("RMD failed (" + lastCode + ")");
    }

    void makeDir(String path) throws IOException {
        command("MKD " + path);
        if (lastCode / 100 != 2) throw new IOException("MKD failed (" + lastCode + ")");
    }

    void rename(String from, String to) throws IOException {
        command("RNFR " + from);
        if (lastCode / 100 != 3) throw new IOException("RNFR failed (" + lastCode + ")");
        command("RNTO " + to);
        if (lastCode / 100 != 2) throw new IOException("RNTO failed (" + lastCode + ")");
    }

    void close() {
        try {
            if (out != null) {
                out.write("QUIT\r\n");
                out.flush();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (control != null) control.close();
        } catch (Throwable ignored) {
        }
        control = null;
    }

    private Socket passive() throws IOException {
        // EPSV keeps the data connection on the control host, which is the only
        // address that is guaranteed to work through NAT or a port forward.
        Socket extended = extendedPassive();
        if (extended != null) return extended;
        command("PASV");
        if (lastCode != 227) throw new IOException("PASV refused (" + lastCode + ")");
        String reply = lastReply;
        int open = reply.indexOf('(');
        int close = reply.indexOf(')', open);
        if (open < 0 || close < 0) throw new IOException("Bad PASV reply");
        String[] parts = reply.substring(open + 1, close).split(",");
        if (parts.length != 6) throw new IOException("Bad PASV reply");
        String host = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        int port = (Integer.parseInt(parts[4].trim()) << 8)
                + Integer.parseInt(parts[5].trim());
        // Servers behind NAT advertise their private address here. If that
        // address cannot be the one we are talking to, use the control host.
        String controlHost = control.getInetAddress().getHostAddress();
        if (isPrivate(host) && !host.equals(controlHost)) host = controlHost;
        return openData(host, port);
    }

    private Socket extendedPassive() {
        try {
            command("EPSV");
            if (lastCode != 229) return null;
            String reply = lastReply;
            int open = reply.indexOf('(');
            int close = reply.indexOf(')', open);
            if (open < 0 || close < 0) return null;
            String body = reply.substring(open + 1, close);
            String[] parts = body.split("\\|");
            int port = -1;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) continue;
                try {
                    port = Integer.parseInt(parts[i].trim());
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (port <= 0 || port > 65535) return null;
            return openData(control.getInetAddress().getHostAddress(), port);
        } catch (Throwable t) {
            return null;
        }
    }

    private Socket openData(String host, int port) throws IOException {
        Socket data = new Socket();
        try {
            data.connect(new InetSocketAddress(host, port), 15_000);
            data.setSoTimeout(30_000);
            return data;
        } catch (IOException e) {
            try {
                data.close();
            } catch (Throwable ignored) {
            }
            throw new IOException("Data connection to " + host + ":" + port
                    + " failed: " + e.getMessage(), e);
        }
    }

    private static boolean isPrivate(String host) {
        if (host == null || host.isEmpty()) return true;
        if ("0.0.0.0".equals(host)) return true;
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        int a, b;
        try {
            a = Integer.parseInt(p[0]);
            b = Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return false;
        }
        return a == 10 || a == 127 || (a == 192 && b == 168)
                || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254)
                || (a == 100 && b >= 64 && b <= 127);
    }

    private String lastReply = "";

    private void command(String line) throws IOException {
        send(line);
        readReply();
    }

    private void send(String line) throws IOException {
        out.write(line + "\r\n");
        out.flush();
    }

    private int readReply() throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("Connection closed");
        if (line.length() < 3) throw new IOException("Bad reply");
        int code;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (Throwable t) {
            throw new IOException("Bad reply");
        }
        StringBuilder text = new StringBuilder(line);
        if (line.length() > 3 && line.charAt(3) == '-') {
            String terminator = line.substring(0, 3) + " ";
            String next;
            while ((next = in.readLine()) != null) {
                text.append('\n').append(next);
                if (next.startsWith(terminator)) break;
            }
        }
        lastCode = code;
        lastReply = text.toString();
        return code;
    }

    private static Entry parse(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        String trimmed = line.trim();
        char first = trimmed.charAt(0);
        if (first == 'd' || first == '-' || first == 'l') {
            // -rw-r--r-- 1 owner group 12345 Jan 01 12:00 name
            int fields = 0;
            int i = 0;
            int nameStart = -1;
            long size = 0;
            while (i < trimmed.length()) {
                while (i < trimmed.length() && trimmed.charAt(i) == ' ') i++;
                int start = i;
                while (i < trimmed.length() && trimmed.charAt(i) != ' ') i++;
                if (start == i) break;
                fields++;
                if (fields == 5) {
                    try {
                        size = Long.parseLong(trimmed.substring(start, i));
                    } catch (Throwable ignored) {
                    }
                }
                if (fields == 8) {
                    nameStart = i;
                    break;
                }
            }
            if (nameStart < 0) return null;
            String name = trimmed.substring(nameStart).trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
            return new Entry(name, size, first == 'd');
        }
        // DOS listing: 01-01-24  12:00PM       <DIR>          name
        String upper = trimmed.toUpperCase(Locale.ROOT);
        int dirAt = upper.indexOf("<DIR>");
        if (dirAt >= 0) {
            String name = trimmed.substring(dirAt + 5).trim();
            return name.isEmpty() ? null : new Entry(name, 0, true);
        }
        String[] parts = trimmed.split("\\s+", 4);
        if (parts.length == 4) {
            try {
                long size = Long.parseLong(parts[2]);
                return new Entry(parts[3], size, false);
            } catch (Throwable ignored) {
            }
        }
        return new Entry(trimmed, 0, false);
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
    }
}
