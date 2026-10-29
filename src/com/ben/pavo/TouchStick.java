package com.ben.pavo;

import java.util.Locale;

/** Optional analog touch-stick region for games that need free movement. */
final class TouchStick {
    float x;
    float y;
    float radius;

    TouchStick(float x, float y, float radius) {
        this.x = clamp(x, 0.04f, 0.96f);
        this.y = clamp(y, 0.04f, 0.96f);
        this.radius = clamp(radius, 0.08f, 0.24f);
    }

    TouchStick copy() {
        return new TouchStick(x, y, radius);
    }

    void set(float nx, float ny) {
        x = clamp(nx, 0.04f, 0.96f);
        y = clamp(ny, 0.04f, 0.96f);
    }

    String encode() {
        return String.format(Locale.US, "%.4f:%.4f:%.4f", x, y, radius);
    }

    static TouchStick decode(String raw) {
        if (raw != null) {
            String[] f = raw.split(":");
            if (f.length == 3) {
                try {
                    return new TouchStick(Float.parseFloat(f[0]),
                            Float.parseFloat(f[1]), Float.parseFloat(f[2]));
                } catch (Throwable ignored) {
                }
            }
        }
        return defaults();
    }

    static TouchStick defaults() {
        return new TouchStick(0.18f, 0.62f, 0.16f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
