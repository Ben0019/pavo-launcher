package com.ben.pavo;

/** Optional right-stick behaviour for one game profile. */
final class RightStick {
    static final int OFF = 0;
    static final int SWIPE = 1;
    static final int MOUSE = 2;
    static final int MOBA = 3;
    static final int JOYSTICK = 4;
    static final int AIM = 5;

    final int mode;
    final TouchStick zone;

    RightStick(int mode, TouchStick zone) {
        this.mode = mode < OFF || mode > AIM ? OFF : mode;
        this.zone = zone == null ? defaultZone(this.mode) : zone.copy();
    }

    RightStick copy() {
        return new RightStick(mode, zone);
    }

    static RightStick defaults() {
        return new RightStick(OFF, defaultZone(OFF));
    }

    static RightStick of(int mode) {
        return new RightStick(mode, defaultZone(mode));
    }

    static TouchStick defaultZone(int mode) {
        if (mode == AIM) return new TouchStick(0.62f, 0.45f, 0.18f);
        if (mode == MOBA) return new TouchStick(0.80f, 0.70f, 0.16f);
        if (mode == JOYSTICK) return new TouchStick(0.78f, 0.64f, 0.16f);
        if (mode == SWIPE) return new TouchStick(0.62f, 0.42f, 0.18f);
        return new TouchStick(0.50f, 0.50f, 0.18f);
    }

    static String label(int mode) {
        switch (mode) {
            case SWIPE: return "SWIPE";
            case MOUSE: return "MOUSE";
            case MOBA: return "MOBA";
            case JOYSTICK: return "JOY";
            case AIM: return "AIM";
            default: return "OFF";
        }
    }

    boolean usesTouchZone() {
        return mode == SWIPE || mode == MOBA || mode == JOYSTICK || mode == AIM;
    }
}
