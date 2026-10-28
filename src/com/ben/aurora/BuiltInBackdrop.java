package com.ben.aurora;

/**
 * A single hand-off bitmap for the next built-in app. It preserves the frosted
 * appearance inside the app card without asking the compositor to blur (and
 * therefore dim/blur) the whole screen around the card.
 */
final class BuiltInBackdrop {
    private static Backdrop pending;

    private BuiltInBackdrop() {}

    static void put(Backdrop backdrop) {
        if (pending != null && pending.bitmap != null
                && (backdrop == null || pending.bitmap != backdrop.bitmap)) {
            pending.bitmap.recycle();
        }
        pending = backdrop;
    }

    static Backdrop take() {
        Backdrop backdrop = pending;
        pending = null;
        return backdrop;
    }
}
