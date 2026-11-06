package com.ben.pavo;

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

    static final int STICK_AXES = 6;
    static final int GATE_POINTS = 64;
    final Map<Integer, Integer> mappings = new LinkedHashMap<>();
    final Set<Integer> turbo = new LinkedHashSet<>();
    final List<Combo> combos = new ArrayList<>();
    boolean enabled;
    boolean capture;
    boolean stickEnabled;
    final int[] stickCenter = new int[STICK_AXES];
    final int[] stickMin = new int[STICK_AXES];
    final int[] stickMax = new int[STICK_AXES];
    int stickDeadzone = 60;
    int stickCurve = 100;
    boolean stickSpecified;
    boolean gateEnabled;
    final int[][] gateGain = new int[2][GATE_POINTS];

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

    /** True when both profiles carry the same buttons, turbos and combos. */
    boolean sameRules(ControllerMapConfig other) {
        if (other == null || !mappings.equals(other.mappings)) return false;
        if (!turbo.equals(other.turbo) || combos.size() != other.combos.size()) return false;
        for (int i = 0; i < combos.size(); i++) {
            Combo a = combos.get(i);
            Combo b = other.combos.get(i);
            if (a.target != b.target || a.keys.length != b.keys.length) return false;
            for (int k = 0; k < a.keys.length; k++) if (a.keys[k] != b.keys[k]) return false;
        }
        return true;
    }

    void loadText(String text) {
        mappings.clear();
        turbo.clear();
        combos.clear();
        enabled = false;
        capture = false;
        resetSticks();
        if (text == null) return;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            parseLine(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    String encode(boolean enabled, boolean capture) {
        StringBuilder b = new StringBuilder();
        b.append("# Pavo controller remapping\n");
        b.append("enabled=").append(enabled ? "1" : "0").append('\n');
        b.append("capture=").append(capture ? "1" : "0").append('\n');
        for (Map.Entry<Integer, Integer> e : mappings.entrySet()) {
            b.append("map=").append(e.getKey()).append(':').append(e.getValue()).append('\n');
        }
        for (Integer source : turbo) {
            b.append("turbo=").append(source).append('\n');
        }
        if (stickEnabled) {
            b.append("stick_enabled=1\n");
            b.append("axis_x=").append(stickCenter[0]).append(':')
                    .append(stickMin[0]).append(':').append(stickMax[0]).append('\n');
            b.append("axis_y=").append(stickCenter[1]).append(':')
                    .append(stickMin[1]).append(':').append(stickMax[1]).append('\n');
            b.append("axis_z=").append(stickCenter[2]).append(':')
                    .append(stickMin[2]).append(':').append(stickMax[2]).append('\n');
            b.append("axis_rz=").append(stickCenter[3]).append(':')
                    .append(stickMin[3]).append(':').append(stickMax[3]).append('\n');
            b.append("stick_deadzone=").append(stickDeadzone).append('\n');
            b.append("stick_curve=").append(stickCurve).append('\n');
            if (gateEnabled) {
                b.append("gate_enabled=1\n");
                appendGate(b, 0);
                appendGate(b, 1);
            }
        }
        for (Combo combo : combos) {
            b.append("combo=");
            for (int i = 0; i < combo.keys.length; i++) {
                if (i > 0) b.append(',');
                b.append(combo.keys[i]);
            }
            b.append(':').append(combo.target).append('\n');
        }
        return b.toString();
    }

    private void appendGate(StringBuilder b, int stick) {
        b.append(stick == 0 ? "gate_left=" : "gate_right=");
        for (int i = 0; i < GATE_POINTS; i++) {
            if (i > 0) b.append(',');
            b.append(gateGain[stick][i]);
        }
        b.append('\n');
    }

    void load(File file) {
        mappings.clear();
        turbo.clear();
        combos.clear();
        enabled = false;
        capture = false;
        resetSticks();
        if (file == null || !file.isFile()) return;
        StringBuilder text = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                text.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
        loadText(text.toString());
    }

    private void resetSticks() {
        stickEnabled = false;
        stickSpecified = false;
        stickDeadzone = 60;
        stickCurve = 100;
        gateEnabled = false;
        for (int i = 0; i < STICK_AXES; i++) {
            stickCenter[i] = 0;
            stickMin[i] = -32767;
            stickMax[i] = 32767;
        }
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i < GATE_POINTS; i++) gateGain[s][i] = 1000;
        }
    }

    private void parseLine(String key, String value) {
        if ("stick_enabled".equals(key)) {
            stickEnabled = "1".equals(value) || "true".equalsIgnoreCase(value);
            stickSpecified = true;
            return;
        }
        if ("stick_deadzone".equals(key)) {
            stickDeadzone = Math.max(0, Math.min(800, parseInt(value)));
            stickSpecified = true;
            return;
        }
        if ("gate_enabled".equals(key)) {
            gateEnabled = "1".equals(value) || "true".equalsIgnoreCase(value);
            stickSpecified = true;
            return;
        }
        if ("gate_left".equals(key) || "gate_right".equals(key)) {
            int stick = "gate_left".equals(key) ? 0 : 1;
            String[] parts = value.split(",");
            for (int i = 0; i < GATE_POINTS && i < parts.length; i++) {
                int gain = parseInt(parts[i]);
                if (gain < 500) gain = 500;
                if (gain > 2000) gain = 2000;
                gateGain[stick][i] = gain;
            }
            stickSpecified = true;
            return;
        }
        if ("stick_curve".equals(key)) {
            int v = parseInt(value);
            if (v >= 20 && v <= 400) stickCurve = v;
            stickSpecified = true;
            return;
        }
        if (key.startsWith("axis_")) {
            int idx = -1;
            if ("axis_x".equals(key)) idx = 0;
            else if ("axis_y".equals(key)) idx = 1;
            else if ("axis_z".equals(key)) idx = 2;
            else if ("axis_rz".equals(key)) idx = 3;
            else if ("axis_rx".equals(key)) idx = 4;
            else if ("axis_ry".equals(key)) idx = 5;
            if (idx < 0) return;
            String[] p = value.split(":");
            if (p.length != 3) return;
            int c = parseInt(p[0]);
            int mn = parseInt(p[1]);
            int mx = parseInt(p[2]);
            if (mx > mn) {
                stickCenter[idx] = c;
                stickMin[idx] = mn;
                stickMax[idx] = mx;
                stickSpecified = true;
            }
            return;
        }
        if ("enabled".equals(key)) {
            enabled = "1".equals(value) || "true".equalsIgnoreCase(value);
        } else if ("capture".equals(key)) {
            capture = "1".equals(value) || "true".equalsIgnoreCase(value);
        } else if ("map".equals(key)) {
            int colon = value.indexOf(':');
            if (colon < 0) return;
            int source = parseInt(value.substring(0, colon));
            int target = parseInt(value.substring(colon + 1));
            if (source > 0 && target > 0) mappings.put(source, target);
        } else if ("turbo".equals(key)) {
            int source = parseInt(value);
            if (source > 0) turbo.add(source);
        } else if ("combo".equals(key)) {
            int colon = value.indexOf(':');
            if (colon < 0) return;
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

    /**
     * Applies a saved button profile while keeping the live stick calibration.
     * Profiles never carry stick tuning, so they must not wipe it.
     */
    void loadProfile(String text) {
        boolean hadSticks = stickEnabled;
        int dz = stickDeadzone;
        int cv = stickCurve;
        int[] center = stickCenter.clone();
        int[] min = stickMin.clone();
        int[] max = stickMax.clone();
        boolean hadGate = gateEnabled;
        int[][] gate = new int[2][];
        gate[0] = gateGain[0].clone();
        gate[1] = gateGain[1].clone();
        loadText(text);
        if (!stickSpecified) {
            stickEnabled = hadSticks;
            stickDeadzone = dz;
            stickCurve = cv;
            System.arraycopy(center, 0, stickCenter, 0, STICK_AXES);
            System.arraycopy(min, 0, stickMin, 0, STICK_AXES);
            System.arraycopy(max, 0, stickMax, 0, STICK_AXES);
            gateEnabled = hadGate;
            System.arraycopy(gate[0], 0, gateGain[0], 0, GATE_POINTS);
            System.arraycopy(gate[1], 0, gateGain[1], 0, GATE_POINTS);
            stickSpecified = true;
        }
    }

    void save(File file, boolean enabled, boolean capture) {
        this.enabled = enabled;
        this.capture = capture;
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp, false)) {
            w.write(encode(enabled, capture));
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
        return rawForKeyCode(event.getKeyCode());
    }

    /** Android key code to Linux evdev code, including extra gamepad buttons. */
    static int rawForKeyCode(int code) {
        switch (code) {
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
            default:
                if (code >= KeyEvent.KEYCODE_BUTTON_1
                        && code <= KeyEvent.KEYCODE_BUTTON_16) {
                    return 704 + (code - KeyEvent.KEYCODE_BUTTON_1);
                }
                return -1;
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
            default:
                if (raw >= 704 && raw <= 743) return "Button " + (raw - 703);
                return "BTN " + raw;
        }
    }
}
