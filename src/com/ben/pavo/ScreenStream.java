package com.ben.pavo;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Low-latency screen colours for SCREEN mode.
 *
 * <p>{@code screencap} takes about 137 ms on this device, capping the fallback
 * path at 5 Hz. The vendor PServer runs as root, so it can start the platform
 * {@code screenrecord} tool with {@code --output-format=raw-frames} at a small
 * resolution. Raw frames arrive at display rate, giving 30 Hz colour updates
 * without a MediaProjection consent dialog.</p>
 *
 * <p>The stream is deliberately best-effort: if PServer or screenrecord is
 * unavailable the caller keeps using {@link ScreenAmbient}.</p>
 */
final class ScreenStream {
    interface Listener {
        void onColors(int left, int right);
    }

    // 16:9 at 1/6 scale. Each pixel is RGB888, so a frame is 172,800 bytes.
    static final int WIDTH = 480;
    static final int HEIGHT = 270;
    private static final int BPP = 3;
    private static final int FRAME_BYTES = WIDTH * HEIGHT * BPP;
    private static final long FRAME_INTERVAL_MS = 33L;
    private static final int MAX_FAILURES = 3;
    private static final Object LOCK = new Object();

    private static ScreenStream active;
    private static int failures;

    private final Context context;
    private final Listener listener;
    private final File fifo;
    private final File pidFile;
    private final File script;
    private final File logFile;
    private volatile boolean running = true;
    private volatile boolean delivering;
    private volatile FileInputStream input;
    private Thread reader;

    private ScreenStream(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        File cache = this.context.getCacheDir();
        fifo = new File(cache, "pavo_ambient.fifo");
        pidFile = new File(cache, "pavo_ambient.pid");
        script = new File(cache, "pavo_ambient.sh");
        logFile = new File(cache, "pavo_ambient.log");
    }

    /** True once raw frames are actually arriving. */
    static boolean isActive() {
        ScreenStream stream;
        synchronized (LOCK) {
            stream = active;
        }
        return stream != null && stream.running && stream.delivering;
    }

    static boolean start(Context context, Listener listener) {
        synchronized (LOCK) {
            if (active != null && active.running) return true;
            if (failures >= MAX_FAILURES) return false;
            ScreenStream stream = new ScreenStream(context, listener);
            if (!stream.launch()) {
                failures++;
                return false;
            }
            stream.reader.start();
            stream.startWatchdog();
            active = stream;
            return true;
        }
    }

    static void stop() {
        ScreenStream stream;
        synchronized (LOCK) {
            stream = active;
            active = null;
        }
        if (stream != null) stream.shutdown();
    }

    /** Called by the caller after a screen-size/rotation change. */
    static void resetFailures() {
        synchronized (LOCK) {
            failures = 0;
        }
    }

    private boolean launch() {
        if (!PServer.available()) return false;
        try {
            String f = fifo.getAbsolutePath();
            String p = pidFile.getAbsolutePath();
            String sh = "#!/system/bin/sh\n"
                    + "pkill -f 'screenrecord.*pavo_ambient' 2>/dev/null\n"
                    + "rm -f " + f + " " + p + "\n"
                    + "mkfifo " + f + "\n"
                    + "chmod 666 " + f + "\n"
                    + "screenrecord --output-format=raw-frames --size "
                    + WIDTH + "x" + HEIGHT + " --time-limit 0 " + f
                    + " >" + logFile.getAbsolutePath() + " 2>&1 &\n"
                    + "echo $! > " + p + "\n";
            FileOutputStream out = new FileOutputStream(script);
            out.write(sh.getBytes("UTF-8"));
            out.close();
            if (!PServer.run("sh " + script.getAbsolutePath())) return false;
            reader = new Thread(new Runnable() {
                @Override public void run() {
                    readLoop();
                }
            }, "PavoScreenFrames");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void startWatchdog() {
        new Thread(new Runnable() {
            @Override public void run() {
                SystemClock.sleep(3500L);
                if (running && !delivering) {
                    // screenrecord never opened the FIFO. Unblock the reader
                    // and let the caller fall back to screencap.
                    running = false;
                    unblockReader();
                    cleanupProcess();
                    synchronized (LOCK) {
                        if (active == ScreenStream.this) active = null;
                        failures++;
                    }
                }
            }
        }, "PavoScreenWatchdog").start();
    }

    private void readLoop() {
        FileInputStream stream = null;
        try {
            long deadline = SystemClock.uptimeMillis() + 3000L;
            while (running && !fifo.exists()
                    && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20L);
            }
            if (!running || !fifo.exists()) return;
            stream = new FileInputStream(fifo);
            input = stream;
            byte[] frame = new byte[FRAME_BYTES];
            int filled = 0;
            long lastPaint = 0L;
            int painted = 0;
            while (running) {
                int read = stream.read(frame, filled, FRAME_BYTES - filled);
                if (read < 0) break;
                filled += read;
                if (filled < FRAME_BYTES) continue;
                filled = 0;
                long now = SystemClock.uptimeMillis();
                if (now - lastPaint < FRAME_INTERVAL_MS) continue;
                lastPaint = now;
                int[] colors = ScreenAmbient.sampleFrame(
                        frame, WIDTH, HEIGHT, BPP, 1, 1);
                if (colors == null || !running) continue;
                delivering = true;
                painted++;
                if (!RgbActivity.isVisible()) {
                    listener.onColors(colors[0], colors[1]);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
            }
            running = false;
            delivering = false;
            synchronized (LOCK) {
                if (active == ScreenStream.this) active = null;
            }
            cleanupProcess();
        }
    }

    private void shutdown() {
        running = false;
        delivering = false;
        unblockReader();
        cleanupProcess();
        if (reader != null) reader.interrupt();
    }

    private void unblockReader() {
        FileInputStream stream = input;
        if (stream != null) {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
            return;
        }
        // The reader may still be blocked opening the FIFO. A paired writer
        // open lets it return, and closing the writer then gives it EOF.
        try {
            FileOutputStream out = new FileOutputStream(fifo);
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private void cleanupProcess() {
        try {
            String f = fifo.getAbsolutePath();
            String p = pidFile.getAbsolutePath();
            PServer.run("kill $(cat " + p + ") 2>/dev/null; rm -f "
                    + f + " " + p + " " + logFile.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }
}
