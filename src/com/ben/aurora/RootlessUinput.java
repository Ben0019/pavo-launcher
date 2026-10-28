package com.ben.aurora;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Rootless output path for the accessibility remapper.
 *
 * /dev/uinput is world-writable on the Odin build and SELinux is permissive,
 * so the system uinput command can create a virtual controller while running
 * as the normal Aurora app UID.  No su/root process is involved.
 */
final class RootlessUinput {
    private static final String TAG = "AuroraRootless";
    static final String DEVICE_NAME = "Aurora Accessibility Controller";

    private Process process;
    private BufferedWriter input;
    private volatile boolean ready;

    synchronized boolean start(Context context) {
        if (process != null && process.isAlive()) return ready;
        stop();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "/system/bin/uinput", "-");
            pb.redirectErrorStream(true);
            pb.environment().put("PATH", "/system/bin:/system/xbin");
            process = pb.start();
            input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            drain();
            input.write(registerJson());
            input.write("\n");
            input.write("{\"id\":1,\"command\":\"delay\",\"duration\":450}\n");
            input.flush();
            Thread.sleep(650L);
            ready = process.isAlive();
            if (!ready) Log.w(TAG, "uinput helper exited during setup");
            return ready;
        } catch (Throwable t) {
            Log.w(TAG, "could not start uinput helper", t);
            stop();
            return false;
        }
    }

    synchronized boolean isReady() {
        return ready && process != null && process.isAlive();
    }

    synchronized void inject(int raw, boolean down) {
        if (!isReady() || input == null) return;
        String label = linuxLabel(raw);
        if (label == null) return;
        try {
            input.write("{\"id\":1,\"command\":\"inject\",\"events\":[\"EV_KEY\",\""
                    + label + "\"," + (down ? 1 : 0)
                    + ",\"EV_SYN\",\"SYN_REPORT\",0]}\n");
            input.flush();
        } catch (Throwable t) {
            Log.w(TAG, "inject failed", t);
            ready = false;
        }
    }

    synchronized void stop() {
        ready = false;
        BufferedWriter w = input;
        input = null;
        if (w != null) {
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        }
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private void drain() {
        final Process p = process;
        if (p == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("error") || line.contains("Error")) {
                            Log.w(TAG, "uinput: " + line);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "AuroraUinputLog").start();
    }

    private static String registerJson() {
        List<Integer> keys = new ArrayList<>();
        for (int code : ControllerMapConfig.BUTTONS) keys.add(code);
        StringBuilder bits = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String label = linuxLabel(keys.get(i));
            if (label == null) continue;
            if (bits.length() > 0) bits.append(',');
            bits.append('"').append(label).append('"');
        }
        return "{\"id\":1,\"command\":\"register\","
                + "\"name\":\"" + DEVICE_NAME + "\","
                + "\"vid\":8224,\"pid\":273,\"bus\":\"usb\","
                + "\"configuration\":["
                + "{\"type\":\"UI_SET_EVBIT\",\"data\":[\"EV_KEY\",\"EV_SYN\"]},"
                + "{\"type\":\"UI_SET_KEYBIT\",\"data\":[" + bits + "]}"
                + "]}";
    }

    static int rawForLabel(String label) {
        if (label == null || label.isEmpty()) return -1;
        for (int code : ControllerMapConfig.BUTTONS) {
            if (label.equals(linuxLabel(code))) return code;
        }
        return -1;
    }

    static String linuxLabel(int raw) {
        switch (raw) {
            case 304: return "BTN_GAMEPAD";
            case 305: return "BTN_EAST";
            case 306: return "BTN_C";
            case 307: return "BTN_NORTH";
            case 308: return "BTN_WEST";
            case 309: return "BTN_Z";
            case 310: return "BTN_TL";
            case 311: return "BTN_TR";
            case 312: return "BTN_TL2";
            case 313: return "BTN_TR2";
            case 314: return "BTN_SELECT";
            case 315: return "BTN_START";
            case 316: return "BTN_MODE";
            case 317: return "BTN_THUMBL";
            case 318: return "BTN_THUMBR";
            case 544: return "BTN_DPAD_UP";
            case 545: return "BTN_DPAD_DOWN";
            case 546: return "BTN_DPAD_LEFT";
            case 547: return "BTN_DPAD_RIGHT";
            case 102: return "KEY_HOME";
            case 158: return "KEY_BACK";
            case 114: return "KEY_VOLUMEDOWN";
            case 115: return "KEY_VOLUMEUP";
            default: return null;
        }
    }
}
