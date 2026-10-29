package com.ben.aurora;

import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persisted controller remapping profile plus the evdev button catalogue. */
final class ControllerMapConfig {

    static final int[] BUTTONS = {
            304, 305, 306, 307, 308, 309, 310, 311, 312, 313,
            314, 315, 316, 317, 318,
            544, 545, 546, 547,
            102, 158, 114, 115
    };

    static final class Combo {
        final int[] keys;
        final int target;

        Combo(int[] keys, int target) {
            this.keys = keys;
            this.target = target;
        }

        String label() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) b.append(" + ");
                b.append(labelFor(keys[i]));
            }
            b.append("  \u2192  ").append(labelFor(target));
            return b.toString();
        }
    }

    final Map<Integer, Integer> mappings = new LinkedHashMap<>();
    final Set<Integer> turbo = new LinkedHashSet<>();
    final List<Combo> combos = new ArrayList<>();
    boolean enabled;
    boolean capture;

    boolean hasMapping(int source) {
        return mappings.containsKey(source);
    }

    int targetFor(int source) {
        Integer target = mappings.get(source);
        return target == null ? source : target;
    }

    void setMapping(int source, int target) {
        if (source == target) mappings.remove(source);
        else mappings.put(source, target);
    }

    void clearMapping(int source) {
        mappings.remove(source);
    }

    void toggleTurbo(int source) {
        if (!turbo.remove(source)) turbo.add(source);
    }

    void addCombo(int[] keys, int target) {
        if (keys == null || keys.length < 2 || target <= 0) return;
        for (int i = 0; i < combos.size(); i++) {
            Combo c = combos.get(i);
            if (sameKeys(c.keys, keys) && c.target == target) return;
        }
        combos.add(new Combo(keys.clone(), target));
    }

    void removeCombo(Combo combo) {
        combos.remove(combo);
    }

    private static boolean sameKeys(int[] a, int[] b) {
        if (a.length != b.length) return false;
        for (int x : a) {
            boolean found = false;
            for (int y : b) if (x == y) found = true;
            if (!found) return false;
        }
        return true;
    }

    void load(File file) {
        mappings.clear();
        turbo.clear();
        combos.clear();
        enabled = false;
        capture = false;
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ("enabled".equals(key)) {
                    enabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                } else if ("capture".equals(key)) {
                    capture = "1".equals(value) || "true".equalsIgnoreCase(value);
                } else if ("map".equals(key)) {
                    int colon = value.indexOf(':');
                    if (colon < 0) continue;
                    int source = parseInt(value.substring(0, colon));
                    int target = parseInt(value.substring(colon + 1));
                    if (source > 0 && target > 0) mappings.put(source, target);
                } else if ("turbo".equals(key)) {
                    int source = parseInt(value);
                    if (source > 0) turbo.add(source);
                } else if ("combo".equals(key)) {
                    int colon = value.indexOf(':');
                    if (colon < 0) continue;
                    String[] parts = value.substring(0, colon).split(",");
                    int target = parseInt(value.substring(colon + 1));
                    int[] keys = new int[parts.length];
                    int count = 0;
                    for (String part : parts) {
                        int source = parseInt(part);
                        if (source <= 0) continue;
                        boolean exists = false;
                        for (int i = 0; i < count; i++) if (keys[i] == source) exists = true;
                        if (!exists && count < keys.length) keys[count++] = source;
                    }
                    if (count >= 2 && target > 0) {
                        int[] trimmed = new int[count];
                        System.arraycopy(keys, 0, trimmed, 0, count);
                        combos.add(new Combo(trimmed, target));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    void save(File file, boolean enabled, boolean capture) {
        this.enabled = enabled;
        this.capture = capture;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp, false)) {
            w.write("# Pavo controller remapping\n");
            w.write("enabled=" + (enabled ? "1" : "0") + "\n");
            w.write("capture=" + (capture ? "1" : "0") + "\n");
            for (Map.Entry<Integer, Integer> e : mappings.entrySet()) {
                w.write("map=" + e.getKey() + ":" + e.getValue() + "\n");
            }
            for (Integer source : turbo) {
                w.write("turbo=" + source + "\n");
            }
            for (Combo combo : combos) {
                w.write("combo=");
                for (int i = 0; i < combo.keys.length; i++) {
                    if (i > 0) w.write(",");
                    w.write(String.valueOf(combo.keys[i]));
                }
                w.write(":" + combo.target + "\n");
            }
        } catch (IOException ignored) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) tmp.delete();
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int rawFor(KeyEvent event) {
        if (event == null) return -1;
        int scan = event.getScanCode();
        if (isKnown(scan)) return scan;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: return 304;
            case KeyEvent.KEYCODE_BUTTON_B: return 305;
            case KeyEvent.KEYCODE_BUTTON_C: return 306;
            case KeyEvent.KEYCODE_BUTTON_X: return 307;
            case KeyEvent.KEYCODE_BUTTON_Y: return 308;
            case KeyEvent.KEYCODE_BUTTON_Z: return 309;
            case KeyEvent.KEYCODE_BUTTON_L1: return 310;
            case KeyEvent.KEYCODE_BUTTON_R1: return 311;
            case KeyEvent.KEYCODE_BUTTON_L2: return 312;
            case KeyEvent.KEYCODE_BUTTON_R2: return 313;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 314;
            case KeyEvent.KEYCODE_BUTTON_START: return 315;
            case KeyEvent.KEYCODE_BUTTON_MODE: return 316;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 317;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 318;
            case KeyEvent.KEYCODE_DPAD_UP: return 544;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 545;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 546;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 547;
            case KeyEvent.KEYCODE_HOME: return 102;
            case KeyEvent.KEYCODE_BACK: return 158;
            case KeyEvent.KEYCODE_VOLUME_DOWN: return 114;
            case KeyEvent.KEYCODE_VOLUME_UP: return 115;
            default: return -1;
        }
    }

    static boolean isKnown(int raw) {
        for (int code : BUTTONS) if (code == raw) return true;
        return false;
    }

    static String labelFor(int raw) {
        switch (raw) {
            case 304: return "A";
            case 305: return "B";
            case 306: return "C";
            case 307: return "X";
            case 308: return "Y";
            case 309: return "Z";
            case 310: return "L1";
            case 311: return "R1";
            case 312: return "L2";
            case 313: return "R2";
            case 314: return "Select";
            case 315: return "Start";
            case 316: return "Guide";
            case 317: return "L3";
            case 318: return "R3";
            case 544: return "D-Pad Up";
            case 545: return "D-Pad Down";
            case 546: return "D-Pad Left";
            case 547: return "D-Pad Right";
            case 102: return "Home";
            case 158: return "Back";
            case 114: return "Volume Down";
            case 115: return "Volume Up";
            default: return "BTN " + raw;
        }
    }
}
