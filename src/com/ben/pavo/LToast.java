package com.ben.pavo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * App-wide toast with a single pending slot.
 *
 * <p>Spamming an action used to queue one toast per tap and play them back
 * slowly. Requests now overwrite the pending message and it is shown once the
 * caller has been quiet for {@link #SETTLE_MS}, so only the newest message is
 * ever displayed.
 */
final class LToast {
    private static final long SETTLE_MS = 900L;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static Context pendingContext;
    private static CharSequence pendingText;
    private static int pendingDuration;
    private static Toast showing;

    private static final Runnable SHOW = new Runnable() {
        @Override
        public void run() {
            if (pendingContext == null || pendingText == null) return;
            if (showing != null) showing.cancel();
            showing = Toast.makeText(pendingContext, pendingText, pendingDuration);
            showing.show();
        }
    };

    private LToast() {}

    /** Returned by {@link #makeText}; only {@link #show()} is used by callers. */
    static final class T {
        private final Context context;
        private final CharSequence text;
        private final int duration;

        T(Context c, CharSequence t, int d) {
            context = c.getApplicationContext();
            text = t;
            duration = d;
        }

        void show() {
            enqueue(context, text, duration);
        }

        void cancel() {
            pendingContext = null;
            pendingText = null;
            HANDLER.removeCallbacks(SHOW);
        }
    }

    static T makeText(Context c, CharSequence text, int duration) {
        return new T(c, Loc.t(c, text), duration);
    }

    static T makeText(Context c, int resId, int duration) {
        return new T(c, Loc.t(c, c.getString(resId)), duration);
    }

    private static void enqueue(Context c, CharSequence text, int duration) {
        pendingContext = c;
        pendingText = text;
        pendingDuration = duration;
        HANDLER.removeCallbacks(SHOW);
        HANDLER.postDelayed(SHOW, SETTLE_MS);
    }
}
