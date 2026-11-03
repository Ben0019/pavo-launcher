package com.ben.pavo;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Discovers the connected controller and the buttons it actually exposes.
 * Standard gamepad keys come from InputDevice key-code capabilities; extra
 * keys (M1/M2, paddles, vendor buttons) are learned from their first press and
 * persisted, because Android does not enumerate their evdev scan codes.
 */
final class ControllerButtons {
    private static final String FILE = "controller_buttons";
    private static final String KEY_LEARNED = "learned";

    static final class Catalog {
        String name = "";
        boolean odin;
        boolean fallback;
        final List<Integer> raws = new ArrayList<>();
    }

    private ControllerButtons() {}

    static Catalog detect(Context context) {
        Catalog catalog = new Catalog();
        InputDevice best = null;
        int bestScore = -1;
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im != null) {
                for (int id : im.getInputDeviceIds()) {
                    InputDevice device = im.getInputDevice(id);
                    if (device == null || device.isVirtual()) continue;
                    String name = device.getName();
                    if (name == null || name.trim().isEmpty()) continue;
                    String lower = name.toLowerCase(Locale.US);
                    if (lower.contains("pavo")) continue;
                    int sources = device.getSources();
                    boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) != 0;
                    boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) != 0;
                    if (!gamepad && !joystick) continue;
                    int score = gamepad ? 3 : 1;
                    if (lower.contains("odin")) score += 4;
                    else if (lower.contains("retroid")) score += 3;
                    else if (lower.contains("controller") || lower.contains("gamepad")) score += 2;
                    if (score > bestScore) {
                        bestScore = score;
                        best = device;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        Set<Integer> found = new LinkedHashSet<>();
        if (best != null) {
            catalog.name = best.getName() == null ? "" : best.getName().trim();
            String lower = catalog.name.toLowerCase(Locale.US);
            catalog.odin = lower.contains("odin");
            try {
                int max = KeyEvent.getMaxKeyCode();
                for (int code = 0; code <= max; code++) {
                    int raw = ControllerMapConfig.rawForKeyCode(code);
                    if (raw <= 0) continue;
                    boolean[] present = best.hasKeys(code);
                    if (present != null && present.length > 0 && present[0]) {
                        found.add(raw);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        found.addAll(learned(context));
        if (found.isEmpty()) {
            catalog.fallback = true;
            for (int raw : ControllerMapConfig.BUTTONS) found.add(raw);
        }
        // Keep the familiar face/shoulder/system order first, then extras.
        for (int raw : ControllerMapConfig.BUTTONS) {
            if (found.remove(raw)) catalog.raws.add(raw);
        }
        List<Integer> extras = new ArrayList<>(found);
        Collections.sort(extras);
        catalog.raws.addAll(extras);
        return catalog;
    }

    static Set<Integer> learned(Context context) {
        Set<Integer> out = new LinkedHashSet<>();
        if (context == null) return out;
        try {
            String value = prefs(context).getString(KEY_LEARNED, "");
            if (value == null || value.trim().isEmpty()) return out;
            for (String part : value.split(",")) {
                int raw = parseInt(part);
                if (raw > 0 && raw < 1024) out.add(raw);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static boolean learn(Context context, int raw) {
        if (context == null || raw <= 0 || raw >= 1024) return false;
        Set<Integer> values = learned(context);
        if (!values.add(raw)) return false;
        StringBuilder b = new StringBuilder();
        for (int value : values) {
            if (b.length() > 0) b.append(',');
            b.append(value);
        }
        prefs(context).edit().putString(KEY_LEARNED, b.toString()).apply();
        return true;
    }

    /** Any raw code the event carries, including a not-yet-catalogued extra button. */
    static int rawForAny(KeyEvent event) {
        if (event == null) return -1;
        int scan = event.getScanCode();
        if (scan > 0) return scan;
        return ControllerMapConfig.rawForKeyCode(event.getKeyCode());
    }

    static boolean isControllerEvent(Context context, KeyEvent event) {
        if (event == null) return false;
        try {
            InputManager im = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return false;
            InputDevice device = im.getInputDevice(event.getDeviceId());
            if (device == null || device.isVirtual()) return false;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) != 0
                    || (sources & InputDevice.SOURCE_JOYSTICK) != 0) {
                return true;
            }
            String name = device.getName();
            Catalog catalog = detect(context);
            return name != null && !catalog.name.isEmpty() && catalog.name.equals(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
