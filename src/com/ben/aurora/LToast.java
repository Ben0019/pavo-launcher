package com.ben.aurora;

import android.content.Context;
import android.widget.Toast;

/** Toast helper that translates the message before showing it. */
final class LToast {
    private LToast() {}

    static Toast makeText(Context c, CharSequence text, int duration) {
        return Toast.makeText(c, Loc.t(c, text), duration);
    }

    static Toast makeText(Context c, int resId, int duration) {
        return Toast.makeText(c, Loc.t(c, c.getString(resId)), duration);
    }
}
