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

    /** A private copy so a second surface can sample the same capture safely. */
    static Backdrop copy() {
        if (pending == null || pending.bitmap == null || pending.bitmap.isRecycled()) {
            return null;
        }
        android.graphics.Bitmap bmp = pending.bitmap.copy(
                android.graphics.Bitmap.Config.ARGB_8888, false);
        if (bmp == null) return null;
        return new Backdrop(bmp, pending.screenWidth, pending.screenHeight);
    }

    static Backdrop take() {
        Backdrop backdrop = pending;
        pending = null;
        return backdrop;
    }
}
