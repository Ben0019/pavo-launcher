package com.ben.aurora;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One controller button mapped to a normalized on-screen touch point. */
final class TouchTarget {
    static final float DEFAULT_RADIUS = 0.058f;
    static final float MIN_RADIUS = 0.024f;
    static final float MAX_RADIUS = 0.13f;
    static final int A = 304;
    static final int B = 305;
    static final int X = 307;
    static final int Y = 308;
    static final int DPAD_UP = 544;
    static final int DPAD_DOWN = 545;
    static final int DPAD_LEFT = 546;
    static final int DPAD_RIGHT = 547;
    static final int L1 = 310;
    static final int R1 = 311;
    static final int L2 = 312;
    static final int R2 = 313;
    static final int SELECT = 314;
    static final int START = 315;
    static final int MODE = 316;
    static final int L3 = 317;
    static final int R3 = 318;
    static final int C = 306;
    static final int Z = 309;
    static final int HOME = 102;
    static final int BACK = 158;
    static final int VOLUME_UP = 115;
    static final int VOLUME_DOWN = 114;
    static final int APPSELECT = 580;
    // UI-only code: physical R3 both presses a placed touch target and gates
    // the right stick to its current mode while held.
    static final int R3_STICK = 2000 + R3; // secondary right-stick zone, toggled by R3
    // UI-only combo inputs. They map to stick movement, not physical key events.
    static final int LEFT_JOY = 3001;
    static final int RIGHT_JOY = 3002;

    static final int[] PALETTE = {
            A, B, X, Y, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
            L1, R1, L2, R2, L3, R3_STICK, SELECT, START, C, Z,
            MODE, HOME, BACK, APPSELECT, VOLUME_UP, VOLUME_DOWN
    };

    final int code;
    final String label;
    float x;
    float y;
    float radius = DEFAULT_RADIUS;
    float alpha = 1f;
    boolean hidden;

    TouchTarget(int code, String label, float x, float y) {
        this(code, label, x, y, DEFAULT_RADIUS, 1f, false);
    }

    TouchTarget(int code, String label, float x, float y,
                float radius, float alpha, boolean hidden) {
        this.code = code;
        this.label = label;
        this.x = clamp(x);
        this.y = clamp(y);
        this.radius = clampRadius(radius);
        this.alpha = clampAlpha(alpha);
        this.hidden = hidden;
    }

    TouchTarget copy() {
        return new TouchTarget(code, label, x, y, radius, alpha, hidden);
    }

    void set(float nx, float ny) {
        x = clamp(nx);
        y = clamp(ny);
    }

    String encode() {
        return code + ":" + String.format(Locale.US, "%.4f", x)
                + ":" + String.format(Locale.US, "%.4f", y)
                + ":" + String.format(Locale.US, "%.4f", radius)
                + ":" + String.format(Locale.US, "%.4f", alpha)
                + ":" + (hidden ? "1" : "0");
    }

    static List<TouchTarget> defaults() {
        List<TouchTarget> out = new ArrayList<>();
        out.add(new TouchTarget(DPAD_UP, "↑", 0.18f, 0.52f));
        out.add(new TouchTarget(DPAD_DOWN, "↓", 0.18f, 0.72f));
        out.add(new TouchTarget(DPAD_LEFT, "←", 0.09f, 0.62f));
        out.add(new TouchTarget(DPAD_RIGHT, "→", 0.27f, 0.62f));
        out.add(new TouchTarget(Y, "Y", 0.82f, 0.52f));
        out.add(new TouchTarget(A, "A", 0.91f, 0.62f));
        out.add(new TouchTarget(B, "B", 0.82f, 0.72f));
        out.add(new TouchTarget(X, "X", 0.73f, 0.62f));
        return out;
    }

    static List<TouchTarget> decode(String raw) {
        if (raw == null) return defaults();
        List<TouchTarget> out = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String part : raw.split(";")) {
                String[] fields = part.split(":");
                if (fields.length < 3) continue;
                try {
                    int code = Integer.parseInt(fields[0]);
                    float x = Float.parseFloat(fields[1]);
                    float y = Float.parseFloat(fields[2]);
                    float radius = fields.length >= 4
                            ? Float.parseFloat(fields[3]) : DEFAULT_RADIUS;
                    float alpha = fields.length >= 5
                            ? Float.parseFloat(fields[4]) : 1f;
                    boolean hidden = fields.length >= 6
                            && "1".equals(fields[5]);
                    boolean duplicate = false;
                    for (TouchTarget existing : out) {
                        if (existing.code == code) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) out.add(new TouchTarget(code, labelFor(code),
                            x, y, radius, alpha, hidden));
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    static String encode(List<TouchTarget> targets) {
        StringBuilder b = new StringBuilder();
        for (TouchTarget t : targets) {
            if (b.length() > 0) b.append(';');
            b.append(t.encode());
        }
        return b.toString();
    }

    static int colorFor(int code) {
        switch (code) {
            case A: return 0xFF6DBE45;
            case B: return 0xFFE5484D;
            case X: return 0xFF3B82F6;
            case Y: return 0xFFF4C542;
            default: return 0xFF8BE9FF;
        }
    }

    static boolean isFaceButton(int code) {
        return code == A || code == B || code == X || code == Y;
    }

    static String labelFor(int code) {
        switch (code) {
            case A: return "A";
            case B: return "B";
            case X: return "X";
            case Y: return "Y";
            case DPAD_UP: return "↑";
            case DPAD_DOWN: return "↓";
            case DPAD_LEFT: return "←";
            case DPAD_RIGHT: return "→";
            case L1: return "L1";
            case R1: return "R1";
            case L2: return "L2";
            case R2: return "R2";
            case SELECT: return "SELECT";
            case START: return "START";
            case MODE: return "MODE";
            case L3: return "L3";
            case R3: return "R3";
            case R3_STICK: return "R3 STICK";
            case LEFT_JOY: return "L JOY";
            case RIGHT_JOY: return "R JOY";
            case C: return "C";
            case Z: return "Z";
            case HOME: return "HOME";
            case BACK: return "BACK";
            case VOLUME_UP: return "VOL+";
            case VOLUME_DOWN: return "VOL−";
            case APPSELECT: return "APPS";
            default: return "BTN " + code;
        }
    }

    private static float clamp(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }

    static float clampRadius(float v) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, v));
    }

    static float clampAlpha(float v) {
        return Math.max(0.2f, Math.min(1f, v));
    }
}
