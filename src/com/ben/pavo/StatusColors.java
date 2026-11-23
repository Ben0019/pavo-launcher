package com.ben.pavo;

/**
 * Semantic status colours shared by the built-in apps and the overlay: OK for
 * healthy values, WARN for caution or throttling, ALERT for errors and
 * destructive actions. Neutral chrome (focus rings, rims) uses
 * {@link ThemeColors} instead; do not use these for decoration.
 */
final class StatusColors {
    static final int OK = 0xFF69D98B;
    static final int WARN = 0xFFFFC857;
    static final int ALERT = 0xFFFF8A80;

    private StatusColors() {}
}
